/*
 * Copyright 2026 Mark Andrew Ray-Smith
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.mars.raftlog.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.zip.CRC32C;

/**
 * File-based implementation of {@link RaftStorage}.
 * <p>
 * This is a minimal, crash-safe WAL implementation using {@link FileChannel}.
 * It is the default implementation for development and small deployments.
 * <p>
 * <b>Files:</b>
 * <pre>
 * data/
 *  ├─ meta.dat     // currentTerm + votedFor (atomic replace)
 *  ├─ raft.log     // WAL: APPEND and TRUNCATE records; a leading PREFIX record after compaction
 *  └─ raft.log.tmp // unpublished prefix-compaction output
 * </pre>
 * <p>
 * <b>Thread Safety:</b>
 * All write operations are serialized through a single-threaded executor.
 * This ensures no concurrent writes can corrupt the log.
 * <p>
 * <b>Durability:</b>
 * <ul>
 *   <li>meta.dat: Uses atomic rename (write temp → fsync → rename → fsync dir)</li>
 *   <li>raft.log: Appends use {@link #sync()}; prefix compaction forces and atomically replaces it</li>
 * </ul>
 * <p>
 * <b>Protection Mechanisms:</b>
 * <ul>
 *   <li><b>File Locking:</b> Exclusive lock on raft.lock prevents multiple processes
 *       from writing simultaneously. Lock is held for the lifetime of the storage instance.</li>
 *   <li><b>Disk Space Checking:</b> Pre-flight check before writes to detect low disk space
 *       early and fail gracefully rather than mid-write.</li>
 *   <li><b>Read-After-Write Verification:</b> Optional check that each record can be read
 *       back with a matching CRC. The read goes through the page cache, so this detects
 *       in-process encoding bugs and some filesystem faults, not media or controller faults.</li>
 * </ul>
 * <p>
 * <b>Fencing:</b>
 * Once a durability call has failed the state of the page cache is undefined, so a
 * later retry can succeed while the data is gone. After a failed force of the WAL, the
 * metadata staging file or the data directory, after a compaction publication failure,
 * and after replay detects corruption that is not a structurally incomplete EOF
 * fragment, this instance
 * rejects every further operation with the original failure. Close it and open a fresh
 * instance; if the failure was corruption, restore the node from its peers.
 * <p>
 * <b>Replay policy:</b>
 * A structurally incomplete EOF fragment is treated as a torn write and truncated. A
 * complete record with a bad CRC, a malformed header, arbitrary garbage, or an invalid
 * record followed by a valid record may be acknowledged data damaged later; it is reported
 * as {@link CorruptLogException} without modifying the file.
 *
 * @see RaftStorage
 */
public final class FileRaftStorage implements RaftStorage {

    // ========================================================================
    // Logger
    // ========================================================================

    private static final Logger LOG = LoggerFactory.getLogger(FileRaftStorage.class);

    // ========================================================================
    // Constants
    // ========================================================================

    /** Magic number: 'RAFT' in ASCII */
    private static final int MAGIC = 0x52414654;

    /** Original record format: APPEND and TRUNCATE records. */
    private static final short VERSION = 1;

    /** Format 2 added the PREFIX record. APPEND and TRUNCATE are unchanged and stay at 1. */
    private static final short VERSION_PREFIX = 2;

    /** Highest format this build can interpret. */
    private static final short MAX_SUPPORTED_VERSION = VERSION_PREFIX;

    /** Record type: Truncate suffix from given index */
    private static final byte TYPE_TRUNCATE = 1;

    /** Record type: Append a log entry */
    private static final byte TYPE_APPEND = 2;

    /**
     * Record type: prefix compaction boundary. Written first in a compacted WAL so a
     * restart knows the next index even when no entries were retained. INDEX holds the
     * inclusive boundary; TERM and payload are unused.
     */
    private static final byte TYPE_PREFIX = 3;

    /** Header size: MAGIC(4) + VERSION(2) + TYPE(1) + INDEX(8) + TERM(8) + PAYLOAD_LEN(4) */
    private static final int HEADER_SIZE = 4 + 2 + 1 + 8 + 8 + 4;

    /** CRC size */
    private static final int CRC_SIZE = 4;

    /** Lock file name */
    private static final String LOCK_FILE = "raft.lock";

    /** Metadata file name */
    private static final String META_FILE = "meta.dat";

    /** Metadata temp file name */
    private static final String META_TMP_FILE = "meta.dat.tmp";

    /** WAL file name */
    private static final String LOG_FILE = "raft.log";
    private static final String LOG_TMP_FILE = "raft.log.tmp";

    /** Maximum source characters retained when a node identifier is written to a log. */
    private static final int MAX_LOGGED_VOTED_FOR_CHARS = 64;
    private static final int MAX_LOGGED_PATH_CHARS = 512;

    // ========================================================================
    // State
    // ========================================================================

    /**
     * Single-threaded executor for all WAL operations.
     * <p>
     * <b>INVARIANT:</b> All write operations (append, truncate, metadata update)
     * are serialized through this executor. This ensures:
     * <ul>
     *   <li>No concurrent writes can corrupt the log</li>
     *   <li>Write ordering is preserved</li>
     *   <li>FileChannel position is always consistent</li>
     * </ul>
     * <b>DO NOT</b> increase the pool size or add parallel write paths.
     */
    private final ExecutorService walExecutor;
    /** The single executor thread, so close() can avoid joining its own queue. */
    private volatile Thread walThread;
    private final RaftStorageConfig config;
    private final boolean syncEnabled;
    private final boolean verifyWrites;
    private final int maxPayloadSize;
    private final long minFreeSpace;
    private final CompactionIo compactionIo;
    private final String storageId = UUID.randomUUID().toString();
    private final AtomicLong operationSequence = new AtomicLong();

    /**
     * Written on the WAL executor by open(); also read on caller threads for log
     * context and error messages, hence volatile.
     */
    private volatile Path dataDir;

    // ------------------------------------------------------------------------
    // Raft log invariants. Owned by the WAL executor: every read and write of
    // these fields happens inside an executor task.
    // ------------------------------------------------------------------------

    private static final long UNKNOWN_TERM = -1L;

    /** True once the tail is known: after replay, or when opened on an empty WAL. */
    private boolean logStateKnown;
    /**
     * Index of the last entry, or the compaction boundary when none is retained, or 0
     * for a fresh log. Held as the last index rather than the next one so that no
     * comparison has to compute {@code Long.MAX_VALUE + 1}.
     */
    private long lastIndex;
    /** Inclusive prefix compaction boundary persisted in the WAL, or 0 if never compacted. */
    private long prefixBoundary;
    /**
     * Runs of equal term over the retained log, ascending, as {firstIndex, term}. Terms
     * never decrease, so this holds one element per term change rather than per entry,
     * and lets a suffix truncation restore the term of the new last entry.
     */
    private final ArrayList<long[]> termRuns = new ArrayList<>();
    /** True when meta.dat exists but could not be read, so a regression cannot be ruled out. */
    private boolean metaUnreadable;
    private long persistedTerm;
    private Optional<String> persistedVote = Optional.empty();
    private FileChannel logChannel;
    private FileChannel lockChannel;
    private FileLock exclusiveLock;
    /** Where the lock file is, recorded when the lock is taken, for reporting release failures. */
    private Path lockPath;
    private volatile boolean closed = false;
    private Path openPath;
    private CompletableFuture<Void> openFuture;
    private CompletableFuture<Void> closeFuture;

    /**
     * Set once a durability call has failed or replay has found corruption that it
     * cannot safely repair. Every subsequent operation fails with this exception.
     */
    private volatile StorageException fatalFailure;

    // ========================================================================
    // Constructor
    // ========================================================================

    /**
     * Creates a new FileRaftStorage with configuration loaded from
     * system properties, environment variables, properties file, or defaults.
     * <p>
     * This is the recommended constructor for production use.
     *
     * @see RaftStorageConfig
     */
    public FileRaftStorage() {
        this(RaftStorageConfig.load());
    }

    /**
     * Creates a new FileRaftStorage with the specified configuration.
     *
     * @param config the storage configuration
     */
    public FileRaftStorage(RaftStorageConfig config) {
        this(config, new CompactionIo());
    }

    FileRaftStorage(RaftStorageConfig config, CompactionIo compactionIo) {
        this(config, compactionIo, false);
    }

    private FileRaftStorage(RaftStorageConfig config, CompactionIo compactionIo,
                            boolean disableFsyncForTesting) {
        this.compactionIo = java.util.Objects.requireNonNull(compactionIo);
        this.config = java.util.Objects.requireNonNull(config);
        this.syncEnabled = !disableFsyncForTesting;
        this.verifyWrites = config.verifyWrites();
        this.maxPayloadSize = config.maxPayloadSizeBytes();
        this.minFreeSpace = config.minFreeSpaceBytes();

        // Single-threaded executor ensures write serialization
        this.walExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "wal-executor");
            t.setDaemon(true);
            walThread = t;
            return t;
        });

        LOG.atInfo().addKeyValue("event", "storage.initialized").addKeyValue("storageId", storageId)
                .log("FileRaftStorage initialized: syncEnabled={}, verifyWrites={}, maxPayloadSize={} MB, minFreeSpace={} MB",
                        syncEnabled, verifyWrites, maxPayloadSize / 1024 / 1024, minFreeSpace / 1024 / 1024);

        if (!syncEnabled) {
            LOG.warn("FileRaftStorage created with fsync DISABLED. Do NOT use in production!");
        }
        if (verifyWrites) {
            LOG.info("Write verification enabled: forces and re-reads every record through the page cache");
        }
    }

    /** Package-private seam for tests that need to observe behavior without fsync. */
    static FileRaftStorage unsafeWithoutFsyncForTesting(boolean verifyWrites) {
        RaftStorageConfig config = RaftStorageConfig.builder()
                .verifyWrites(verifyWrites)
                .build();
        return new FileRaftStorage(config, new CompactionIo(), true);
    }

    /** Package-private seam for deterministic I/O tests that need fsync disabled. */
    static FileRaftStorage unsafeWithoutFsyncForTesting(RaftStorageConfig config, CompactionIo compactionIo) {
        return new FileRaftStorage(config, compactionIo, true);
    }

    /**
     * Creates a new FileRaftStorage with specified sync setting.
     * <p>
     * <b>Deprecated:</b> Use {@link #FileRaftStorage(RaftStorageConfig)} instead.
     *
     * @param syncEnabled must be true; false is rejected
     * @throws IllegalArgumentException if {@code syncEnabled} is false
     */
    @Deprecated(since = "1.4.0", forRemoval = true)
    public FileRaftStorage(boolean syncEnabled) {
        this(RaftStorageConfig.builder().syncEnabled(syncEnabled).build());
    }

    /**
     * Creates a new FileRaftStorage with specified sync and verify settings.
     * <p>
     * <b>Deprecated:</b> Use {@link #FileRaftStorage(RaftStorageConfig)} instead.
     *
     * @param syncEnabled   must be true; false is rejected
     * @param verifyWrites  if true, perform read-after-write verification
     * @throws IllegalArgumentException if {@code syncEnabled} is false
     */
    @Deprecated(since = "1.4.0", forRemoval = true)
    public FileRaftStorage(boolean syncEnabled, boolean verifyWrites) {
        this(RaftStorageConfig.builder()
                .syncEnabled(syncEnabled)
                .verifyWrites(verifyWrites)
                .build());
    }

    /**
     * Returns the configuration used by this storage instance.
     */
    public RaftStorageConfig config() {
        return config;
    }

    // ========================================================================
    // Open / Close
    // ========================================================================

    /**
     * Opens the storage using the data directory from the configuration.
     * <p>
     * This is the recommended method when using {@link RaftStorageConfig}.
     *
     * @return a future that completes when storage is ready
     */
    public CompletableFuture<Void> open() {
        return open(config.dataDir());
    }

    @Override
    public synchronized CompletableFuture<Void> open(Path dataDir) {
        Path requestedPath = java.util.Objects.requireNonNull(dataDir, "dataDir")
                .toAbsolutePath().normalize();
        StorageException rejection = rejectionForNewOperation();
        if (rejection != null) return CompletableFuture.failedFuture(rejection);
        if (openFuture != null) {
            if (requestedPath.equals(openPath)) return openFuture;
            return CompletableFuture.failedFuture(new StorageException(
                    "Storage is already opening or open at " + pathForLog(openPath)));
        }

        CompletableFuture<Void> opening = runOperation("open", requestedPath, () -> {
            try {
                LOG.atInfo().addKeyValue("event", "storage.open.started")
                        .log("Opening WAL storage at: {}", pathForLog(requestedPath));
                this.dataDir = requestedPath;
                Files.createDirectories(requestedPath);
                LOG.debug("Created/verified data directory: {}", requestedPath);

                // Acquire exclusive lock to prevent multiple processes
                acquireExclusiveLock();

                // Only the published WAL is authoritative. A process interrupted
                // before atomic replacement may leave an incomplete rewrite.
                if (Files.exists(requestedPath.resolve(LOG_TMP_FILE)) && !Files.exists(requestedPath.resolve(LOG_FILE))) {
                    throw new IOException("Unpublished rewrite exists without raft.log; preserve directory for recovery");
                }
                Files.deleteIfExists(requestedPath.resolve(LOG_TMP_FILE));

                // Check available disk space
                checkDiskSpace();

                Path logPath = requestedPath.resolve(LOG_FILE);
                this.logChannel = compactionIo.openLog(logPath);

                // Seek to end for appends
                long logSize = logChannel.size();
                logChannel.position(logSize);

                // An empty WAL has a known tail. Anything else must be replayed before
                // the first write so appends can be checked against the real tail.
                logStateKnown = logSize == 0;
                lastIndex = 0;
                prefixBoundary = 0;
                termRuns.clear();
                seedMetadataBaseline(requestedPath);
                LOG.atInfo().addKeyValue("event", "storage.open.completed")
                        .addKeyValue("walBytes", logSize)
                        .log("WAL opened successfully: path={}, size={} bytes", pathForLog(logPath), logSize);

            } catch (IOException e) {
                LOG.atError().addKeyValue("event", "storage.open.failed")
                        .setCause(e).log("Failed to open WAL at {}: {}", pathForLog(requestedPath), e.getMessage());
                abandonFailedOpen();
                throw new StorageException("Failed to open WAL at " + requestedPath, e);
            } catch (RuntimeException e) {
                abandonFailedOpen();
                throw e;
            }
        });
        openPath = requestedPath;
        openFuture = opening;
        opening.whenComplete((ignored, error) -> {
            if (error != null) resetFailedOpen(opening);
        });
        return opening;
    }

    /**
     * Releases whatever a failed open acquired so queued operations see a closed
     * instance rather than a half-opened channel. Runs on the WAL executor.
     */
    private void abandonFailedOpen() {
        FileChannel channel = logChannel;
        logChannel = null;
        if (channel != null) {
            try {
                compactionIo.closeChannel(channel);
            } catch (IOException e) {
                LOG.warn("Could not close log channel after failed open: {}", e.getMessage(), e);
            }
        }
        releaseExclusiveLock();
    }

    /** Package-private so the stale-callback guard can be tested directly. */
    synchronized void resetFailedOpen(CompletableFuture<Void> failedOpen) {
        if (!closed && openFuture == failedOpen) {
            openFuture = null;
            openPath = null;
        }
    }

    /**
     * Closes the storage and blocks until the log channel and directory lock have been
     * released, so a new instance can open the same directory as soon as this returns.
     * <p>
     * When called from the WAL executor thread itself (for example inside a future
     * callback) it cannot wait on its own queue; it then behaves like {@link #closeAsync()}.
     *
     * @throws StorageException if resource release fails
     */
    @Override
    public void close() {
        CompletableFuture<Void> completion = closeAsync();
        if (Thread.currentThread() == walThread) {
            LOG.debug("close() invoked on the WAL executor thread; not waiting for completion");
            return;
        }
        try {
            completion.join();
        } catch (java.util.concurrent.CompletionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof StorageException se) throw se;
            throw new StorageException("Failed to close WAL storage at " + pathForLog(dataDir), cause);
        }
    }

    @Override
    public synchronized CompletableFuture<Void> closeAsync() {
        if (closeFuture != null) {
            LOG.debug("Storage already closed, ignoring duplicate close()");
            return closeFuture;
        }
        closed = true;
        closeFuture = new CompletableFuture<>();
        CompletableFuture<Void> completion = closeFuture;
        long closeStarted = System.nanoTime();
        String operationId = nextOperationId("close");
        LOG.atInfo().addKeyValue("event", "storage.close.requested").addKeyValue("storageId", storageId)
                .addKeyValue("operationId", operationId)
                .log("Closing WAL storage at: {}", pathForLog(dataDir));

        try {
            walExecutor.execute(() -> withLogContext(operationId, dataDir, () -> {
                try {
                    boolean cleanupSucceeded = true;
                    try {
                        if (logChannel != null) {
                            compactionIo.closeChannel(logChannel);
                            LOG.debug("Log channel closed");
                        }
                    } catch (IOException e) {
                        cleanupSucceeded = false;
                        LOG.warn("Error closing log channel: {}", e.getMessage(), e);
                    }
                    cleanupSucceeded &= releaseExclusiveLock();
                    long elapsedMs = (System.nanoTime() - closeStarted) / 1_000_000;
                    LOG.atInfo().addKeyValue("event", cleanupSucceeded
                                    ? "storage.close.completed" : "storage.close.completed_with_warnings")
                            .addKeyValue("durationMs", elapsedMs)
                            .addKeyValue("cleanupSucceeded", cleanupSucceeded)
                            .log("WAL storage closed: path={}, cleanupSucceeded={}, elapsedMs={}",
                                    pathForLog(dataDir), cleanupSucceeded, elapsedMs);
                    if (cleanupSucceeded) {
                        completion.complete(null);
                    } else {
                        completion.completeExceptionally(new StorageException(
                                "WAL storage closed with resource-release failures at " + dataDir));
                    }
                } catch (Throwable e) {
                    LOG.atError().addKeyValue("event", "storage.close.failed").setCause(e)
                            .log("Failed to close WAL storage at {}: {}", pathForLog(dataDir), e.getMessage());
                    completion.completeExceptionally(e);
                }
                return null;
            }));
        } catch (Throwable error) {
            completion.completeExceptionally(error);
        } finally {
            walExecutor.shutdown();
        }
        return completion;
    }

    // ========================================================================
    // Metadata Operations
    // ========================================================================

    @Override
    public CompletableFuture<Void> updateMetadata(long currentTerm, Optional<String> votedFor) {
        return runOperation("metadata-update", dataDir, () -> {
            ensureHealthy();
            validateMetadataUpdate(currentTerm, votedFor);
            try {
                LOG.debug("Updating metadata: term={}, votedFor={}", currentTerm, votedForForLog(votedFor));
                Path tmpPath = dataDir.resolve(META_TMP_FILE);
                Path metaPath = dataDir.resolve(META_FILE);

                byte[] voteBytes = votedFor
                        .map(s -> s.getBytes(StandardCharsets.UTF_8))
                        .orElse(new byte[0]);

                // Format: TERM(8) + VOTE_LEN(4) + VOTE_BYTES(var) + CRC(4)
                ByteBuffer buf = ByteBuffer.allocate(8 + 4 + voteBytes.length + 4);
                buf.putLong(currentTerm);
                buf.putInt(voteBytes.length);
                buf.put(voteBytes);

                // Calculate CRC over term + length + vote
                CRC32C crc = new CRC32C();
                crc.update(buf.array(), 0, 8 + 4 + voteBytes.length);
                buf.putInt((int) crc.getValue());
                buf.flip();

                // Write to temp file
                try (FileChannel ch = FileChannel.open(tmpPath,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE)) {
                    while (buf.hasRemaining()) {
                        ch.write(buf);
                    }
                    if (syncEnabled) {
                        try {
                            compactionIo.forceChannel(ch);
                        } catch (IOException e) {
                            throw fence("Failed to force metadata staging file", e);
                        }
                        LOG.trace("Synced temp metadata file");
                    }
                }

                // Atomic rename
                Files.move(tmpPath, metaPath,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
                LOG.trace("Atomic rename: {} -> {}", tmpPath, metaPath);

                // The rename is not durable across power loss until the directory is
                // forced. A failure here is a durability failure and fences the instance.
                if (syncEnabled) {
                    try {
                        compactionIo.forceDirectory(dataDir);
                    } catch (IOException e) {
                        throw fence("Failed to force data directory after metadata rename", e);
                    }
                }

                persistedTerm = currentTerm;
                persistedVote = votedFor;
                LOG.atDebug().addKeyValue("event", "metadata.update.completed")
                        .log("Metadata updated: term={}, votedFor={}", currentTerm, votedForForLog(votedFor));

            } catch (IOException e) {
                LOG.atError().addKeyValue("event", "metadata.update.failed").setCause(e)
                        .log("Failed to update metadata: {}", e.getMessage());
                throw new StorageException("Failed to update metadata", e);
            }
        });
    }

    @Override
    public CompletableFuture<PersistentMeta> loadMetadata() {
        return supplyOperation("metadata-load", dataDir, () -> {
            ensureHealthy();
            try {
                PersistentMeta meta = readMetadataFile(dataDir);
                metaUnreadable = false;
                persistedTerm = meta.currentTerm();
                persistedVote = meta.votedFor();
                LOG.atInfo().addKeyValue("event", "metadata.load.completed")
                        .log("Metadata loaded: term={}, votedFor={}", meta.currentTerm(), votedForForLog(meta.votedFor()));
                return meta;
            } catch (IOException e) {
                LOG.atError().addKeyValue("event", "metadata.load.failed").setCause(e)
                        .log("Failed to load metadata: {}", e.getMessage());
                throw new StorageException("Failed to load metadata", e);
            }
        });
    }

    /** Parses meta.dat, or returns EMPTY when it does not exist. Called only on the WAL executor. */
    private static PersistentMeta readMetadataFile(Path dir) throws IOException {
                Path metaPath = dir.resolve(META_FILE);
                if (!Files.exists(metaPath)) {
                    LOG.debug("No metadata file found, returning empty metadata");
                    return PersistentMeta.EMPTY;
                }

                LOG.debug("Loading metadata from: {}", metaPath);
                byte[] all = Files.readAllBytes(metaPath);
                if (all.length < Long.BYTES + Integer.BYTES + Integer.BYTES) {
                    LOG.atError().addKeyValue("event", "metadata.corrupt")
                            .log("Corrupt metadata: file is {} bytes, minimum valid size is {} bytes",
                                    all.length, Long.BYTES + Integer.BYTES + Integer.BYTES);
                    throw new StorageException("Corrupt meta.dat: file is too short (" + all.length + " bytes)");
                }
                ByteBuffer buf = ByteBuffer.wrap(all);

                long term = buf.getLong();
                int voteLen = buf.getInt();

                // Validate length
                if (voteLen < 0 || voteLen > (all.length - 8 - 4 - 4)) {
                    LOG.atError().addKeyValue("event", "metadata.corrupt")
                            .log("Corrupt metadata: invalid vote length {}", voteLen);
                    throw new StorageException("Corrupt meta.dat: invalid vote length " + voteLen);
                }

                byte[] voteBytes = new byte[voteLen];
                buf.get(voteBytes);

                int expectedCrc = buf.getInt();

                // Verify CRC
                CRC32C crc = new CRC32C();
                crc.update(all, 0, 8 + 4 + voteLen);
                if ((int) crc.getValue() != expectedCrc) {
                    LOG.atError().addKeyValue("event", "metadata.corrupt")
                            .log("Corrupt metadata: CRC mismatch (expected={}, computed={})",
                                    expectedCrc, (int) crc.getValue());
                    throw new StorageException("Corrupt meta.dat: CRC mismatch");
                }

                Optional<String> votedFor = voteLen == 0
                        ? Optional.empty()
                        : Optional.of(new String(voteBytes, StandardCharsets.UTF_8));

                return new PersistentMeta(term, votedFor);
    }

    /**
     * Establishes the term/vote baseline at open so a later update can be checked for
     * regression. Unreadable metadata leaves the baseline unknown; loadMetadata()
     * reports the problem to the caller.
     */
    private void seedMetadataBaseline(Path dir) {
        try {
            PersistentMeta meta = readMetadataFile(dir);
            metaUnreadable = false;
            persistedTerm = meta.currentTerm();
            persistedVote = meta.votedFor();
        } catch (IOException | StorageException e) {
            // readMetadataFile returns EMPTY for a missing file, so reaching here means the
            // file exists and holds a term this node may already have voted in.
            metaUnreadable = true;
            LOG.atError().addKeyValue("event", "metadata.unreadable")
                    .log("meta.dat is unreadable; metadata updates are refused until it is restored or removed: {}",
                            e.getMessage());
        }
    }

    // ========================================================================
    // Log Operations
    // ========================================================================

    @Override
    public CompletableFuture<Void> appendEntries(List<LogEntryData> entries) {
        StorageException rejection = rejectionForNewOperation();
        if (rejection != null) return CompletableFuture.failedFuture(rejection);
        if (entries == null || entries.isEmpty()) {
            LOG.trace("appendEntries called with empty list, no-op");
            return CompletableFuture.completedFuture(null);
        }

        // Validate and detach caller-owned data before returning. The actual write is
        // asynchronous, so retaining the list or payload arrays would make the WAL
        // contents depend on mutations performed after this method has accepted them.
        List<LogEntryData> acceptedEntries = new ArrayList<>(entries.size());
        for (LogEntryData entry : entries) {
            if (entry == null) {
                return CompletableFuture.failedFuture(
                        new IllegalArgumentException("entries must not contain null"));
            }
            if (entry.payload() != null && entry.payload().length > maxPayloadSize) {
                LOG.atError().addKeyValue("event", "wal.append.rejected")
                        .addKeyValue("entryIndex", entry.index())
                        .log("Payload too large for entry index {}: {} bytes (max: {})",
                                entry.index(), entry.payload().length, maxPayloadSize);
                return CompletableFuture.failedFuture(
                        new WriteRejectedException(WriteRejectionReason.PAYLOAD_TOO_LARGE,
                                "Payload too large: " + entry.payload().length +
                                        " bytes (max: " + maxPayloadSize + ")"));
            }
            acceptedEntries.add(new LogEntryData(entry.index(), entry.term(),
                    entry.payload() == null ? null : entry.payload().clone()));
        }

        return runOperation("append", dataDir, () -> {
            ensureHealthy();
            validateAppend(acceptedEntries);
            // Checked for the whole batch before the first record is written, so running
            // out of space refuses the batch instead of abandoning it half written.
            requireDiskSpaceFor(acceptedEntries);
            LOG.debug("Appending {} entries (indices {}-{})",
                    acceptedEntries.size(), acceptedEntries.getFirst().index(), acceptedEntries.getLast().index());
            try {
                long started = System.nanoTime();
                long totalBytes = 0;
                for (LogEntryData entry : acceptedEntries) {
                    writeRecord(TYPE_APPEND, entry.index(), entry.term(),
                            entry.payload() != null ? entry.payload() : new byte[0]);
                    totalBytes += HEADER_SIZE + (entry.payload() != null ? entry.payload().length : 0) + CRC_SIZE;
                    LOG.trace("Appended entry: index={}, term={}, payloadSize={}",
                            entry.index(), entry.term(),
                            entry.payload() != null ? entry.payload().length : 0);
                }
                lastIndex = acceptedEntries.getLast().index();
                for (LogEntryData entry : acceptedEntries) rememberTerm(entry.index(), entry.term());
                long elapsedMicros = (System.nanoTime() - started) / 1_000;
                LOG.atDebug().addKeyValue("event", "wal.append.completed")
                        .addKeyValue("entryCount", acceptedEntries.size()).addKeyValue("walBytes", totalBytes)
                        .addKeyValue("durationMicros", elapsedMicros)
                        .log("Appended {} entries to WAL: indices [{}-{}], terms [{}-{}], {} bytes, elapsedUs={}",
                                acceptedEntries.size(), acceptedEntries.getFirst().index(), acceptedEntries.getLast().index(),
                                acceptedEntries.getFirst().term(), acceptedEntries.getLast().term(), totalBytes, elapsedMicros);
            } catch (IOException e) {
                LOG.atError().addKeyValue("event", "wal.append.failed")
                        .addKeyValue("firstIndex", acceptedEntries.getFirst().index())
                        .addKeyValue("lastIndex", acceptedEntries.getLast().index()).setCause(e)
                        .log("Failed to append entries [{}-{}] at {}: {}",
                                acceptedEntries.getFirst().index(), acceptedEntries.getLast().index(),
                                pathForLog(dataDir), e.getMessage());
                // Part of the batch may be on disk. The tail is unknown until replay.
                logStateKnown = false;
                throw new StorageException("Failed to append entries", e);
            } catch (RuntimeException e) {
                // Verification failures and fencing are unchecked. The same applies:
                // some records of the batch may already be in the file.
                logStateKnown = false;
                throw e;
            }
        });
    }

    @Override
    public CompletableFuture<Void> truncateSuffix(long fromIndex) {
        return runOperation("suffix-truncate", dataDir, () -> {
            ensureHealthy();
            validateSuffixTruncation(fromIndex);
            LOG.debug("Truncating log suffix from index {}", fromIndex);
            try {
                // Write a TRUNCATE record (no payload needed)
                writeRecord(TYPE_TRUNCATE, fromIndex, 0L, new byte[0]);
                if (fromIndex - 1 < lastIndex) lastIndex = fromIndex - 1;
                forgetTermsFrom(fromIndex);
                LOG.atInfo().addKeyValue("event", "wal.suffix_truncate.completed")
                        .log("Truncate record written: fromIndex={}", fromIndex);
            } catch (IOException e) {
                logStateKnown = false;
                LOG.atError().addKeyValue("event", "wal.suffix_truncate.failed").setCause(e)
                        .log("Failed to write truncate record from index {} at {}: {}",
                                fromIndex, pathForLog(dataDir), e.getMessage());
                throw new StorageException("Failed to write truncate record", e);
            } catch (RuntimeException e) {
                logStateKnown = false;
                throw e;
            }
        });
    }

    @Override
    public CompletableFuture<Void> sync() {
        StorageException rejection = rejectionForNewOperation();
        if (rejection != null) return CompletableFuture.failedFuture(rejection);
        // Even with fsync disabled the request goes through the executor so that a
        // joined sync() is still an ordering barrier for earlier appends.
        return runOperation("sync", dataDir, () -> {
            ensureHealthy();
            if (!syncEnabled) {
                LOG.trace("sync() called but fsync is disabled");
                return;
            }
            LOG.debug("Syncing WAL to disk");
            try {
                long startNanos = System.nanoTime();
                compactionIo.forceChannel(logChannel);
                long elapsedMicros = (System.nanoTime() - startNanos) / 1000;
                LOG.atDebug().addKeyValue("event", "wal.sync.completed")
                        .addKeyValue("durationMicros", elapsedMicros)
                        .log("WAL synced to disk in {} us", elapsedMicros);
            } catch (IOException e) {
                // After a failed fsync the kernel may have discarded the dirty pages;
                // a retry could report success for data that never reached the disk.
                throw fence("Failed to sync WAL", e);
            }
        });
    }

    @Override
    public CompletableFuture<Void> truncatePrefix(long toIndex) {
        return runOperation("prefix-compaction", dataDir, () -> {
            ensureHealthy();
            if (toIndex < 0) throw new IllegalArgumentException("Prefix boundary must not be negative");
            if (toIndex == 0) return;
            Path temporary = dataDir.resolve(LOG_TMP_FILE);
            Path published = dataDir.resolve(LOG_FILE);
            boolean publicationAttempted = false;
            long started = System.nanoTime();
            LOG.atDebug().addKeyValue("event", "wal.compaction.started")
                    .log("Compacting WAL prefix through index {}: source={}, temporary={}",
                            toIndex, pathForLog(published), pathForLog(temporary));
            try {
                // Do not turn source corruption into acknowledged prefix deletion.
                // The caller must explicitly replay/repair a torn tail first.
                List<LogEntryData> retained = readLog(false).stream().filter(e -> e.index() > toIndex).toList();
                checkDiskSpace();
                Files.deleteIfExists(temporary);
                long boundary = Math.max(toIndex, prefixBoundary);
                try (FileChannel output = FileChannel.open(temporary,
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    // The boundary is persisted so a restart knows where the log continues
                    // even when nothing is retained.
                    compactionIo.write(output, encodeRecord(TYPE_PREFIX, boundary, 0L, new byte[0]));
                    for (LogEntryData entry : retained) {
                        compactionIo.write(output, encodeRecord(TYPE_APPEND, entry.index(), entry.term(), entry.payload()));
                    }
                    // Compaction always forces the new WAL, even with append sync disabled.
                    compactionIo.force(output);
                }
                // Windows requires closing the old handle before replacing the file.
                // Once publication begins an exception may mean either generation is
                // present: reject subsequent operations until a fresh open recovers it.
                publicationAttempted = true;
                compactionIo.closeChannel(logChannel);
                compactionIo.replace(temporary, published);
                compactionIo.forceDirectory(dataDir);
                logChannel = compactionIo.reopen(published);
                logChannel.position(logChannel.size());
                // The compaction boundary establishes the tail even when nothing is retained.
                logStateKnown = true;
                prefixBoundary = boundary;
                lastIndex = retained.isEmpty() ? boundary : retained.getLast().index();
                rebuildTermRuns(retained);
                long elapsedMs = (System.nanoTime() - started) / 1_000_000;
                LOG.atInfo().addKeyValue("event", "wal.compaction.completed")
                        .addKeyValue("durationMs", elapsedMs).addKeyValue("retainedEntries", retained.size())
                        .log("Compacted WAL through index {}: {} entries retained, elapsedMs={}",
                                toIndex, retained.size(), elapsedMs);
            } catch (UnsupportedFormatException e) {
                throw fence("WAL was written by a newer format", e);
            } catch (CorruptLogException e) {
                // Nothing was written, but the source cannot be trusted for any later write.
                throw fence("WAL contains ambiguous corruption", e);
            } catch (IOException | RuntimeException e) {
                StorageException failure = new StorageException("Prefix compaction failed", e);
                if (publicationAttempted) {
                    fatalFailure = failure;
                    try {
                        compactionIo.closeChannel(logChannel);
                    } catch (IOException closeFailure) {
                        failure.addSuppressed(closeFailure);
                    }
                } else {
                    try {
                        compactionIo.discard(temporary);
                    } catch (IOException cleanupFailure) {
                        failure.addSuppressed(cleanupFailure);
                    }
                }
                LOG.atError().addKeyValue("event", "wal.compaction.failed")
                        .addKeyValue("publicationAttempted", publicationAttempted).setCause(failure)
                        .log("Prefix compaction failed through index {}: publicationAttempted={}, source={}, temporary={}, error={}",
                                toIndex, publicationAttempted, pathForLog(published), pathForLog(temporary), e.getMessage());
                throw failure;
            }
        });
    }

    // ------------------------------------------------------------------------
    // Raft invariant checks. All run on the WAL executor before any byte is
    // written, so a rejected operation leaves the WAL untouched.
    // ------------------------------------------------------------------------

    /** Term of the last retained entry, or {@link #UNKNOWN_TERM} when the log holds no entries. */
    private long lastTerm() {
        return termRuns.isEmpty() ? UNKNOWN_TERM : termRuns.getLast()[1];
    }

    private void rememberTerm(long index, long term) {
        if (termRuns.isEmpty() || termRuns.getLast()[1] != term) termRuns.add(new long[]{index, term});
    }

    /** Drops every run that starts at or after {@code fromIndex}; an earlier run still covers fromIndex - 1. */
    private void forgetTermsFrom(long fromIndex) {
        while (!termRuns.isEmpty() && termRuns.getLast()[0] >= fromIndex) termRuns.removeLast();
    }

    private void rebuildTermRuns(List<LogEntryData> entries) {
        termRuns.clear();
        for (LogEntryData entry : entries) rememberTerm(entry.index(), entry.term());
    }

    /** Pre-flight check for batches that contain a record over 1 MB. Runs before any write. */
    private void requireDiskSpaceFor(List<LogEntryData> entries) {
        boolean large = false;
        for (LogEntryData entry : entries) {
            if (entry.payload() != null && entry.payload().length > LARGE_RECORD_BYTES) large = true;
        }
        if (!large) return;
        LOG.debug("Large write detected in batch, checking disk space");
        try {
            checkDiskSpace();
        } catch (IOException e) {
            throw new StorageException("Failed to check disk space before append", e);
        }
    }

    private static final int LARGE_RECORD_BYTES = 1024 * 1024;

    private void requireKnownLogState() {
        if (!logStateKnown) {
            throw new WriteRejectedException(WriteRejectionReason.LOG_STATE_UNKNOWN,
                    "Log tail is unknown: call replayLog() before writing to " + pathForLog(dataDir));
        }
    }

    private void validateAppend(List<LogEntryData> entries) {
        LogEntryData first = entries.getFirst();
        if (first.index() < 1) {
            throw new WriteRejectedException(WriteRejectionReason.INDEX_NOT_CONTIGUOUS,
                    "Log indices start at 1, got " + first.index());
        }
        if (lastIndex == Long.MAX_VALUE) {
            throw new WriteRejectedException(WriteRejectionReason.INDEX_NOT_CONTIGUOUS,
                    "Log is full: the last index is Long.MAX_VALUE, got " + first.index());
        }
        if (first.index() != lastIndex + 1) {
            throw new WriteRejectedException(WriteRejectionReason.INDEX_NOT_CONTIGUOUS,
                    "Append must start at index " + (lastIndex + 1) + ", got " + first.index());
        }
        long previousTerm = lastTerm();
        long previousIndex = lastIndex;
        for (LogEntryData entry : entries) {
            // previousIndex + 1 must not be computed at the top of the index space.
            if (previousIndex == Long.MAX_VALUE || entry.index() != previousIndex + 1) {
                throw new WriteRejectedException(WriteRejectionReason.INDEX_NOT_CONTIGUOUS,
                        "Batch is not contiguous: entry " + entry.index() + " cannot follow " + previousIndex);
            }
            previousIndex = entry.index();
            if (entry.term() < 0 || (previousTerm != UNKNOWN_TERM && entry.term() < previousTerm)) {
                throw new WriteRejectedException(WriteRejectionReason.TERM_REGRESSION,
                        "Entry " + entry.index() + " has term " + entry.term()
                                + " below the preceding term " + previousTerm);
            }
            previousTerm = entry.term();
        }
    }

    private void validateSuffixTruncation(long fromIndex) {
        requireKnownLogState();
        if (fromIndex < 1) {
            throw new WriteRejectedException(WriteRejectionReason.INVALID_TRUNCATION,
                    "Suffix truncation boundary must be at least 1, got " + fromIndex);
        }
        if (fromIndex <= prefixBoundary) {
            throw new WriteRejectedException(WriteRejectionReason.INVALID_TRUNCATION,
                    "Suffix truncation from " + fromIndex + " reaches into the compacted prefix (boundary "
                            + prefixBoundary + ")");
        }
        // fromIndex is at least 1 here, so fromIndex - 1 cannot underflow.
        if (fromIndex - 1 > lastIndex) {
            throw new WriteRejectedException(WriteRejectionReason.INVALID_TRUNCATION,
                    "Suffix truncation from " + fromIndex + " is beyond the end of the log (last index "
                            + lastIndex + ")");
        }
    }

    private void validateMetadataUpdate(long term, Optional<String> votedFor) {
        if (term < 0) {
            throw new WriteRejectedException(WriteRejectionReason.TERM_REGRESSION,
                    "Term must not be negative: " + term);
        }
        if (metaUnreadable) {
            throw new WriteRejectedException(WriteRejectionReason.METADATA_UNREADABLE,
                    "meta.dat is unreadable, so the persisted term is unknown and overwriting it could allow a "
                            + "second vote in the same term. Restore the file, or remove it if this node may "
                            + "safely start from term 0");
        }
        // An absent meta.dat reads as term 0 with no vote, so there is always a baseline here.
        if (term < persistedTerm) {
            throw new WriteRejectedException(WriteRejectionReason.TERM_REGRESSION,
                    "Term " + term + " is below the persisted term " + persistedTerm);
        }
        if (term == persistedTerm && persistedVote.isPresent() && !persistedVote.equals(votedFor)) {
            throw new WriteRejectedException(WriteRejectionReason.VOTE_CHANGED,
                    "Vote in term " + term + " already cast for " + votedForForLog(persistedVote)
                            + "; cannot change it to " + votedForForLog(votedFor));
        }
    }

    /** A replayed log must be a well-formed Raft log: contiguous indices and non-decreasing terms. */
    private static void validateReplayedLog(List<LogEntryData> entries, long boundary, Path logPath) {
        if (!entries.isEmpty() && boundary > 0 && entries.getFirst().index() != boundary + 1) {
            throw new StorageException("WAL " + logPath + " is compacted through index " + boundary
                    + " but its first entry is " + entries.getFirst().index());
        }
        for (int i = 1; i < entries.size(); i++) {
            LogEntryData previous = entries.get(i - 1);
            LogEntryData current = entries.get(i);
            if (current.index() != previous.index() + 1) {
                throw new StorageException("WAL " + logPath + " is not a contiguous Raft log: entry "
                        + previous.index() + " is followed by entry " + current.index());
            }
            if (current.term() < previous.term()) {
                throw new StorageException("WAL " + logPath + " has a term regression: entry "
                        + previous.index() + " (term " + previous.term() + ") is followed by entry "
                        + current.index() + " (term " + current.term() + ")");
            }
        }
    }

    /**
     * Called on the WAL executor at the start of every operation. Rejects fenced
     * instances and operations that were queued before an open completed, or behind an
     * open that failed, so they fail with a StorageException rather than a
     * NullPointerException from a missing channel.
     */
    /**
     * The inclusive prefix compaction boundary persisted in the WAL, or 0 if the log
     * has never been compacted. Requires a known log state, like any write.
     */
    public CompletableFuture<Long> compactionBoundary() {
        return supplyOperation("compaction-boundary", dataDir, () -> {
            ensureHealthy();
            requireKnownLogState();
            return prefixBoundary;
        });
    }

    private void ensureHealthy() {
        if (fatalFailure != null) throw fatalFailure;
        if (logChannel == null) throw new StorageException("Storage is not open: " + pathForLog(dataDir));
    }

    /**
     * Returns the failure to report for an operation submitted without touching the
     * executor, or {@code null} when the operation may proceed. Rejecting closed or
     * fenced instances up front keeps callers from seeing executor rejections.
     */
    private StorageException rejectionForNewOperation() {
        if (closed) return closedFailure();
        return fatalFailure;
    }

    private StorageException closedFailure() {
        return new StorageException("Storage is closed: " + pathForLog(dataDir));
    }

    private synchronized CompletableFuture<Void> runOperation(String operation, Path path, Runnable action) {
        StorageException rejection = rejectionForNewOperation();
        if (rejection != null) return CompletableFuture.failedFuture(rejection);
        String operationId = nextOperationId(operation);
        try {
            return CompletableFuture.runAsync(
                    () -> withLogContext(operationId, path, () -> {
                        action.run();
                        return null;
                    }),
                    walExecutor);
        } catch (java.util.concurrent.RejectedExecutionException error) {
            return CompletableFuture.failedFuture(schedulingFailure(operation, error));
        }
    }

    private synchronized <T> CompletableFuture<T> supplyOperation(String operation, Path path, Supplier<T> action) {
        StorageException rejection = rejectionForNewOperation();
        if (rejection != null) return CompletableFuture.failedFuture(rejection);
        String operationId = nextOperationId(operation);
        try {
            return CompletableFuture.supplyAsync(() -> withLogContext(operationId, path, action), walExecutor);
        } catch (java.util.concurrent.RejectedExecutionException error) {
            return CompletableFuture.failedFuture(schedulingFailure(operation, error));
        }
    }

    /** Package-private so both outcomes can be tested directly. */
    StorageException schedulingFailure(String operation, RuntimeException cause) {
        StorageException rejection = rejectionForNewOperation();
        return rejection != null ? rejection : new StorageException(
                "Storage operation could not be scheduled: " + operation, cause);
    }

    private <T> T withLogContext(String operationId, Path path, Supplier<T> action) {
        try (MDC.MDCCloseable ignoredStorage = MDC.putCloseable("storageId", storageId);
             MDC.MDCCloseable ignoredOperation = MDC.putCloseable("operationId", operationId);
             MDC.MDCCloseable ignoredPath = MDC.putCloseable("storagePath", pathForLog(path))) {
            return action.get();
        }
    }

    private String nextOperationId(String operation) {
        return operation + "-" + operationSequence.incrementAndGet();
    }

    /** Produces a bounded, single-line representation of a path controlled by the caller. */
    private static String pathForLog(Path path) {
        if (path == null) return "(not-open)";
        return boundedSingleLine(path.toAbsolutePath().normalize().toString(), MAX_LOGGED_PATH_CHARS);
    }

    static String boundedSingleLine(String value, int limit) {
        int end = Math.min(value.length(), limit);
        if (end < value.length() && end > 0 && Character.isHighSurrogate(value.charAt(end - 1))) end--;

        StringBuilder safe = new StringBuilder(Math.min(end + 16, limit + 16));
        for (int i = 0; i < end; i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\r' -> safe.append("\\r");
                case '\n' -> safe.append("\\n");
                case '\t' -> safe.append("\\t");
                default -> {
                    if (Character.isISOControl(c) || c == '\u2028' || c == '\u2029') {
                        String hex = Integer.toHexString(c);
                        safe.append("\\u").append("0".repeat(4 - hex.length())).append(hex);
                    } else {
                        safe.append(c);
                    }
                }
            }
        }
        if (end < value.length()) safe.append("…[").append(value.length()).append(" chars]");
        return safe.toString();
    }

    /**
     * Records a fatal failure so that every later operation fails with it, and
     * returns the exception for the caller to throw. Called only on the WAL executor.
     */
    StorageException fence(String message, Throwable cause) {
        String detail = String.valueOf(cause.getMessage()).replaceFirst("\\.+$", "");
        LOG.atError().addKeyValue("event", "storage.fenced").setCause(cause)
                .log("{}: {}. Storage instance is now fenced; close it and open a fresh instance",
                        message, detail);
        StorageException failure = cause instanceof StorageException se ? se : new StorageException(message, cause);
        if (fatalFailure == null) fatalFailure = failure;
        return failure;
    }

    @Override
    public CompletableFuture<List<LogEntryData>> replayLog() {
        return supplyOperation("replay", dataDir, () -> {
            ensureHealthy();
            try {
                return readLog(true);
            } catch (UnsupportedFormatException e) {
                throw fence("WAL was written by a newer format", e);
            } catch (CorruptLogException e) {
                // The channel is positioned after the corrupt region. Appending there would
                // splice new records onto data that cannot be trusted.
                throw fence("WAL contains ambiguous corruption", e);
            } catch (IOException e) {
                LOG.atError().addKeyValue("event", "wal.replay.failed").setCause(e)
                        .log("Failed to replay WAL at {}: {}", pathForLog(dataDir.resolve(LOG_FILE)), e.getMessage());
                throw new StorageException("Failed to replay log", e);
            }
        });
    }

    /** Result of decoding one record at a file position. */
    private record DecodedRecord(byte type, long index, long term, byte[] payload, long end) {
    }

    /** Produces a bounded, single-line representation of a potentially untrusted node identifier. */
    private static String votedForForLog(Optional<String> votedFor) {
        if (votedFor.isEmpty()) return "(none)";
        return boundedSingleLine(votedFor.get(), MAX_LOGGED_VOTED_FOR_CHARS);
    }

    /**
     * Decodes the record at {@code pos}, or returns {@code null} if the bytes there
     * are not a complete, well-formed record with a matching CRC. The reason for a
     * {@code null} is logged at the given level so the main loop can warn while the
     * forward scan stays quiet. Replay separately classifies whether the bytes are a
     * structurally incomplete EOF fragment or ambiguous corruption.
     */
    private DecodedRecord decodeRecord(FileChannel ch, long pos, long fileSize, boolean warn) throws IOException {
        ByteBuffer headerBuf = ByteBuffer.allocate(HEADER_SIZE);
        int headerRead = readFully(ch, headerBuf, pos, fileSize);
        if (headerRead < HEADER_SIZE) {
            if (warn) {
                LOG.debug("Incomplete header at pos {}: read {} bytes, expected {}", pos, headerRead, HEADER_SIZE);
            }
            return null;
        }
        headerBuf.flip();

        int magic = headerBuf.getInt();
        short version = headerBuf.getShort();
        byte type = headerBuf.get();
        long index = headerBuf.getLong();
        long term = headerBuf.getLong();
        int payloadLen = headerBuf.getInt();

        if (magic != MAGIC || version < VERSION) {
            if (warn) LOG.warn("Invalid header at pos {}: magic=0x{}, version={}", pos, Integer.toHexString(magic), version);
            return null;
        }
        if (payloadLen < 0) {
            if (warn) LOG.warn("Invalid payload length at pos {}: {}", pos, payloadLen);
            return null;
        }
        // The configured payload limit governs what may be WRITTEN. It must not decide what can be
        // read: an operator who lowers it below the size of an entry already in the log would
        // otherwise see a healthy log reported as corrupt. A record being read is bounded by the
        // file that holds it, which also bounds the allocation below. Whether the bytes are a
        // genuine record is then for the CRC to say.
        if (payloadLen > fileSize - pos - HEADER_SIZE - CRC_SIZE) {
            if (warn) LOG.debug("Record at pos {} declares {} payload bytes, which runs past the end of the file", pos, payloadLen);
            return null;
        }
        // A newer format may define types this build does not know, so the type is only
        // judged once the version is known to be supported. Whether a higher version is
        // genuine or a damaged byte is decided by the CRC below.
        boolean newerFormat = version > MAX_SUPPORTED_VERSION;
        if (!newerFormat && type != TYPE_TRUNCATE && type != TYPE_APPEND && type != TYPE_PREFIX) {
            if (warn) LOG.warn("Unknown record type at pos {}: {}", pos, type);
            return null;
        }

        // The whole record is known to lie inside the file, so these reads either complete or
        // throw because the file is shrinking underneath replay. Neither can come back short.
        ByteBuffer payloadBuf = ByteBuffer.allocate(payloadLen);
        readFully(ch, payloadBuf, pos + HEADER_SIZE, fileSize);
        payloadBuf.flip();

        ByteBuffer crcBuf = ByteBuffer.allocate(CRC_SIZE);
        readFully(ch, crcBuf, pos + HEADER_SIZE + payloadLen, fileSize);
        crcBuf.flip();
        int expectedCrc = crcBuf.getInt();

        CRC32C crc = new CRC32C();
        headerBuf.rewind();
        crc.update(headerBuf);
        crc.update(payloadBuf.duplicate());
        if ((int) crc.getValue() != expectedCrc) {
            if (warn) LOG.warn("CRC mismatch at pos {}: expected={}, computed={}", pos, expectedCrc, (int) crc.getValue());
            return null;
        }

        if (newerFormat) {
            // The checksum holds, so this is an intact record written by a newer build.
            throw new UnsupportedFormatException(version, MAX_SUPPORTED_VERSION, pos);
        }

        byte[] payload = new byte[payloadLen];
        payloadBuf.get(payload);
        return new DecodedRecord(type, index, term, payload, pos + HEADER_SIZE + payloadLen + CRC_SIZE);
    }

    /**
     * Scans forward from {@code from} for any position holding a complete, valid record.
     * Finding a valid record after the first invalid byte proves that the damage is not
     * an EOF fragment. Absence of a later record is not proof of a torn write; the
     * structural EOF check makes that decision separately. The scan is byte-granular
     * so a damaged length field cannot hide valid records after it.
     */
    private boolean validRecordExistsAfter(FileChannel ch, long from, long fileSize) throws IOException {
        final int window = 64 * 1024;
        byte[] magicBytes = {(byte) (MAGIC >>> 24), (byte) (MAGIC >>> 16), (byte) (MAGIC >>> 8), (byte) MAGIC};
        ByteBuffer buf = ByteBuffer.allocate(window);
        long scanPos = from;
        while (scanPos + HEADER_SIZE + CRC_SIZE <= fileSize) {
            buf.clear();
            // The loop condition guarantees a whole minimal record remains, so this read
            // returns at least that many bytes or fails.
            int read = readFully(ch, buf, scanPos, fileSize);
            byte[] bytes = buf.array();
            for (int i = 0; i + magicBytes.length <= read; i++) {
                if (bytes[i] != magicBytes[0] || bytes[i + 1] != magicBytes[1]
                        || bytes[i + 2] != magicBytes[2] || bytes[i + 3] != magicBytes[3]) continue;
                if (decodeRecord(ch, scanPos + i, fileSize, false) != null) return true;
            }
            // Overlap by three bytes so a magic straddling the window edge is still seen.
            scanPos += read - (magicBytes.length - 1);
            if (read < window) break;
        }
        return false;
    }

    /**
     * Returns true only when the bytes at {@code pos} end before the minimum record
     * header, or when a valid header declares a payload/CRC that reaches beyond EOF.
     * Complete records with a bad CRC and malformed headers are ambiguous: they may
     * be acknowledged data damaged after the write, so replay must not erase them.
     */
    private boolean isStructurallyIncompleteEofFragment(FileChannel ch, long pos, long fileSize) throws IOException {
        long remaining = fileSize - pos;
        if (remaining < HEADER_SIZE) return true;

        ByteBuffer header = ByteBuffer.allocate(HEADER_SIZE);
        readFully(ch, header, pos, fileSize);
        header.flip();

        int magic = header.getInt();
        short version = header.getShort();
        byte type = header.get();
        header.getLong(); // index
        header.getLong(); // term
        int payloadLen = header.getInt();

        if (magic != MAGIC || version < VERSION || version > MAX_SUPPORTED_VERSION) return false;
        if (type != TYPE_TRUNCATE && type != TYPE_APPEND && type != TYPE_PREFIX) return false;
        // The compaction boundary is published atomically after a force, so a crash cannot
        // tear it. A short one is later damage, and repairing it would truncate the file to
        // nothing and silently restart the index space at 1.
        if (type == TYPE_PREFIX) return false;
        if (payloadLen < 0 || payloadLen > maxPayloadSize) return false;

        long completeSize = (long) HEADER_SIZE + payloadLen + CRC_SIZE;
        return remaining < completeSize;
    }

    /** Called only on the WAL executor, without submitting another executor task. */
    private List<LogEntryData> readLog(boolean repairTail) throws IOException {
        Path logPath = dataDir.resolve(LOG_FILE);
        if (!Files.exists(logPath)) {
            LOG.debug("No WAL file found, returning empty log");
            return List.of();
        }

        LOG.atInfo().addKeyValue("event", "wal.replay.started")
                .log("Replaying WAL from: {}", pathForLog(logPath));
        long startTime = System.currentTimeMillis();
        List<LogEntryData> entries = new ArrayList<>();
        int appendCount = 0;
        int truncateCount = 0;
        long last = 0;
        long boundary = 0;

        try (FileChannel ch = compactionIo.openForReplay(logPath)) {

            long fileSize = ch.size();
            LOG.debug("WAL file size: {} bytes", fileSize);

            long pos = 0;
            while (pos < fileSize) {
                DecodedRecord record = decodeRecord(ch, pos, fileSize, true);
                if (record == null) break;

                if (record.type() == TYPE_PREFIX) {
                    // Written by compaction as the first record of the rewritten WAL.
                    if (pos != 0) {
                        throw new StorageException("WAL " + logPath + " has a prefix marker at byte " + pos
                                + "; it is only valid as the first record");
                    }
                    boundary = record.index();
                    last = boundary;
                    LOG.trace("Replay PREFIX: compacted through index {}", boundary);
                } else if (record.type() == TYPE_TRUNCATE) {
                    long truncateFrom = record.index();
                    int beforeSize = entries.size();
                    entries.removeIf(e -> e.index() >= truncateFrom);
                    // A boundary at or below the compacted prefix (including the 0 and negative
                    // values an existing format-1 log may hold) empties the log back to the prefix.
                    // Otherwise truncateFrom is at least 1, so truncateFrom - 1 cannot underflow.
                    long lastAfterTruncate = truncateFrom <= boundary ? boundary : truncateFrom - 1;
                    if (lastAfterTruncate < last) last = lastAfterTruncate;
                    truncateCount++;
                    LOG.trace("Replay TRUNCATE: fromIndex={}, removed {} entries", truncateFrom, beforeSize - entries.size());
                } else {
                    entries.add(new LogEntryData(record.index(), record.term(), record.payload()));
                    last = record.index();
                    appendCount++;
                    LOG.trace("Replay APPEND: index={}, term={}, payloadLen={}",
                            record.index(), record.term(), record.payload().length);
                }
                pos = record.end();
            }
            long lastGoodPos = pos;

            if (lastGoodPos < fileSize) {
                // Only a structurally incomplete EOF fragment is repaired. A complete
                // bad record or malformed header at the tail may be acknowledged data
                // damaged later and therefore must be reported without modifying it.
                boolean incompleteEof = isStructurallyIncompleteEofFragment(ch, lastGoodPos, fileSize);
                if (!incompleteEof || validRecordExistsAfter(ch, lastGoodPos + 1, fileSize)) {
                    throw new CorruptLogException(logPath, lastGoodPos, fileSize, entries.size());
                }
                if (!repairTail) throw new IOException("WAL contains an incomplete tail; replay before compaction");
                LOG.atWarn().addKeyValue("event", "wal.tail.repaired")
                        .addKeyValue("removedBytes", fileSize - lastGoodPos)
                        .log("Truncating torn tail: {} bytes removed (file was {} bytes, valid data {} bytes)",
                                fileSize - lastGoodPos, fileSize, lastGoodPos);
                ch.truncate(lastGoodPos);
            }
        }

        // Update log channel position
        logChannel.position(Files.size(logPath));

        validateReplayedLog(entries, boundary, logPath);
        logStateKnown = true;
        // Releases before format 2 compacted without writing a marker, so their compacted logs
        // simply start above index 1. A log whose first entry is N was compacted through N - 1,
        // and that range must be protected from truncation and rewriting exactly as if the
        // marker were there. With a marker the two agree, because validation just checked it.
        prefixBoundary = entries.isEmpty() ? boundary : entries.getFirst().index() - 1;
        lastIndex = entries.isEmpty() ? last : entries.getLast().index();
        rebuildTermRuns(entries);

        long elapsed = System.currentTimeMillis() - startTime;
        LOG.atInfo().addKeyValue("event", "wal.replay.completed").addKeyValue("durationMs", elapsed)
                .addKeyValue("recoveredEntries", entries.size())
                .log("WAL replay complete: {} entries recovered, {} appends, {} truncates, {} ms",
                        entries.size(), appendCount, truncateCount, elapsed);

        return entries;
    }

    /**
     * Reads until the buffer is full or the file ends, and returns the number of bytes read.
     * <p>
     * A short result is legitimate only when the requested range runs past {@code fileSize},
     * which is how a torn tail looks. If the file ends inside a range that {@code fileSize}
     * says exists, the file is shrinking while it is being read. Nothing can be concluded
     * about its contents then, and in particular it must not be classified as a torn write,
     * because that verdict leads to truncation. It is reported as an I/O failure instead.
     */
    private int readFully(FileChannel channel, ByteBuffer buffer, long position, long fileSize) throws IOException {
        int requested = buffer.remaining();
        int total = 0;
        while (buffer.hasRemaining()) {
            int count = compactionIo.read(channel, buffer, position + total);
            if (count < 0) {
                if (position + requested <= fileSize) {
                    throw new IOException("WAL ended at byte " + (position + total) + " while reading " + requested
                            + " bytes at " + position + " of a " + fileSize + "-byte file; it is changing underneath replay");
                }
                break;
            }
            if (count == 0) throw new IOException("No progress reading WAL");
            total += count;
        }
        return total;
    }

    // ========================================================================
    // Internal Helpers
    // ========================================================================

    /**
     * Writes a single record to the WAL.
     * Must be called from the walExecutor thread.
     */
    private void writeRecord(byte type, long index, long term, byte[] payload) throws IOException {
        int payloadLen = payload.length;
        int recordSize = HEADER_SIZE + payloadLen + CRC_SIZE;

        LOG.trace("Writing record: type={}, index={}, term={}, payloadLen={}, recordSize={}",
                type == TYPE_APPEND ? "APPEND" : "TRUNCATE",
                index, term, payloadLen, recordSize);

        ByteBuffer buf = encodeRecord(type, index, term, payload);
        int crcValue = buf.getInt(recordSize - CRC_SIZE);

        // Record position before write for verification
        long writePosition = logChannel.position();
        LOG.trace("Writing {} bytes at position {}", recordSize, writePosition);

        // Write to channel
        compactionIo.writeRecord(logChannel, buf);

        // Optional read-after-write verification
        if (verifyWrites && syncEnabled) {
            LOG.trace("Verifying written record at position {}", writePosition);
            verifyWrittenRecord(writePosition, recordSize, crcValue);
        }
    }

    private static ByteBuffer encodeRecord(byte type, long index, long term, byte[] payload) {
        int payloadLen = payload.length;
        int recordSize = HEADER_SIZE + payloadLen + CRC_SIZE;
        ByteBuffer buf = ByteBuffer.allocate(recordSize);

        // Write header
        buf.putInt(MAGIC);
        buf.putShort(type == TYPE_PREFIX ? VERSION_PREFIX : VERSION);
        buf.put(type);
        buf.putLong(index);
        buf.putLong(term);
        buf.putInt(payloadLen);

        // Write payload
        buf.put(payload);

        // Calculate and write CRC
        CRC32C crc = new CRC32C();
        crc.update(buf.array(), 0, HEADER_SIZE + payloadLen);
        int crcValue = (int) crc.getValue();
        buf.putInt(crcValue);

        buf.flip();
        return buf;

    }

    /**
     * Acquires an exclusive lock on the WAL directory to prevent multiple processes.
     * <p>
     * Uses a separate lock file to avoid holding a lock on the WAL file itself,
     * which could interfere with file operations.
     *
     * @throws StorageException if lock cannot be acquired (another process holds it)
     */
    private void acquireExclusiveLock() throws IOException {
        lockPath = dataDir.resolve(LOCK_FILE);
        LOG.debug("Acquiring exclusive lock: {}", pathForLog(lockPath));

        lockChannel = FileChannel.open(lockPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE);

        try {
            exclusiveLock = lockChannel.tryLock();
            if (exclusiveLock == null) {
                lockChannel.close();
                LOG.error("Cannot acquire exclusive lock at {}: another process holds the lock", pathForLog(lockPath));
                throw new StorageException(
                        "Cannot acquire exclusive lock on WAL directory: " + dataDir +
                        ". Another process may be using this storage.");
            }
            LOG.info("Exclusive lock acquired: {}", pathForLog(lockPath));
        } catch (OverlappingFileLockException e) {
            lockChannel.close();
            LOG.error("Cannot acquire exclusive lock at {}: lock already held in this JVM", pathForLog(lockPath));
            throw new StorageException(
                    "Cannot acquire exclusive lock: lock already held in this JVM", e);
        }
    }

    /**
     * Releases the exclusive lock and closes the lock channel.
     */
    private boolean releaseExclusiveLock() {
        boolean succeeded = true;
        try {
            if (exclusiveLock != null && exclusiveLock.isValid()) {
                compactionIo.releaseLock(exclusiveLock);
                LOG.debug("Exclusive lock released");
            }
        } catch (IOException e) {
            succeeded = false;
            LOG.warn("Could not release lock at {}: {}", pathForLog(lockPath),
                    e.getMessage(), e);
        }
        try {
            if (lockChannel != null && lockChannel.isOpen()) {
                compactionIo.closeChannel(lockChannel);
                LOG.trace("Lock channel closed");
            }
        } catch (IOException e) {
            succeeded = false;
            LOG.warn("Could not close lock channel at {}: {}",
                    pathForLog(lockPath), e.getMessage(), e);
        }
        return succeeded;
    }

    /**
     * Checks that sufficient disk space is available.
     *
     * @throws StorageException if disk space is below minimum threshold
     */
    private void checkDiskSpace() throws IOException {
        long usableSpace = compactionIo.usableSpace(dataDir);
        long usableSpaceMb = usableSpace / 1024 / 1024;
        long minFreeSpaceMb = minFreeSpace / 1024 / 1024;

        LOG.trace("Disk space check at {}: {} MB available, {} MB required",
                pathForLog(dataDir), usableSpaceMb, minFreeSpaceMb);

        if (usableSpace < minFreeSpace) {
            LOG.error("Insufficient disk space at {}: {} MB available, need at least {} MB",
                    pathForLog(dataDir), usableSpaceMb, minFreeSpaceMb);
            throw new WriteRejectedException(WriteRejectionReason.INSUFFICIENT_DISK_SPACE,
                    "Insufficient disk space: " + usableSpaceMb + " MB available, " +
                            "need at least " + minFreeSpaceMb + " MB. " +
                            "Free up space or data loss may occur.");
        }
    }

    /**
     * Verifies a written record by forcing it and reading it back through the same
     * channel, then checking the CRC.
     * <p>
     * The read is served from the page cache on every mainstream operating system, so
     * this detects in-process encoding faults and filesystem-level write failures that
     * surface on read. It does not detect faults in the disk controller or the media;
     * those require reading through a separate path or comparing against peers.
     *
     * @param position    the file position where the record was written
     * @param recordSize  the total size of the record
     * @param expectedCrc the expected CRC32C value
     * @throws StorageException if verification fails
     */
    private void verifyWrittenRecord(long position, int recordSize, int expectedCrc) throws IOException {
        try {
            compactionIo.forceChannel(logChannel);
        } catch (IOException e) {
            throw fence("Failed to force WAL before write verification", e);
        }

        // Read back the record
        ByteBuffer readBuf = ByteBuffer.allocate(recordSize);
        int bytesRead = logChannel.read(readBuf, position);

        if (bytesRead != recordSize) {
            LOG.error("Write verification failed: expected {} bytes, read {} bytes", recordSize, bytesRead);
            throw new StorageException(
                    "Write verification failed: expected to read " + recordSize +
                    " bytes but got " + bytesRead);
        }

        readBuf.flip();

        // Verify CRC
        CRC32C verifyCrc = new CRC32C();
        verifyCrc.update(readBuf.array(), 0, HEADER_SIZE + readBuf.getInt(HEADER_SIZE - 4));
        int actualCrc = (int) verifyCrc.getValue();

        // Read the stored CRC
        readBuf.position(recordSize - CRC_SIZE);
        int storedCrc = readBuf.getInt();

        if (storedCrc != expectedCrc || actualCrc != expectedCrc) {
            LOG.error("Write verification CRC mismatch: written={}, stored={}, computed={}",
                    expectedCrc, storedCrc, actualCrc);
            throw new StorageException(
                    "Write verification failed: CRC mismatch. Written=" + expectedCrc +
                    ", Stored=" + storedCrc + ", Computed=" + actualCrc +
                    ". Possible silent data corruption!");
        }

        LOG.trace("Write verification passed at position {}", position);
    }

    // ========================================================================
    // Exception
    // ========================================================================

    /**
     * Exception thrown when storage operations fail.
     */
    public static class StorageException extends RuntimeException {
        public StorageException(String message) {
            super(message);
        }

        public StorageException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * The WAL holds an intact record whose format version is newer than this build
     * understands. This is not corruption: the file was written by a later release, and
     * the remedy is to run that release. The file is left untouched and the instance is
     * fenced so nothing can be appended behind records this build cannot interpret.
     */
    public static final class UnsupportedFormatException extends StorageException {
        private final int foundVersion;
        private final int supportedVersion;

        UnsupportedFormatException(int foundVersion, int supportedVersion, long offset) {
            super("WAL record at byte " + offset + " uses format version " + foundVersion
                    + "; this build supports up to " + supportedVersion
                    + ". The WAL was written by a newer release and cannot be read by this one.");
            this.foundVersion = foundVersion;
            this.supportedVersion = supportedVersion;
        }

        /** Format version declared by the record. */
        public int foundVersion() { return foundVersion; }

        /** Highest format version this build can read. */
        public int supportedVersion() { return supportedVersion; }
    }

    /**
     * A categorized rejection that preserves the historical StorageException
     * hierarchy while exposing a stable reason through the RaftStorage contract.
     */
    public static final class WriteRejectedException extends StorageException implements WriteRejection {
        private final WriteRejectionReason reason;

        public WriteRejectedException(WriteRejectionReason reason, String message) {
            super(message);
            this.reason = java.util.Objects.requireNonNull(reason, "reason");
        }

        @Override
        public WriteRejectionReason reason() {
            return reason;
        }
    }

    /**
     * Replay found an invalid record that cannot be safely classified as an incomplete
     * EOF write. It may be followed by a valid record or may be a complete final record
     * damaged after acknowledgment. The WAL is not modified and the instance is fenced.
     * <p>
     * A node in this state must not repair itself by truncation: if it then formed a
     * majority with lagging peers, acknowledged entries could be lost cluster-wide.
     * Restore the node from its peers or from a whole-node backup. An operator who has
     * established that everything from {@link #corruptOffset()} onward was never
     * acknowledged may truncate {@code raft.log} to that offset and restart.
     */
    public static final class CorruptLogException extends StorageException {
        private final Path logPath;
        private final long corruptOffset;
        private final long fileSize;
        private final int entriesBeforeCorruption;

        CorruptLogException(Path logPath, long corruptOffset, long fileSize, int entriesBeforeCorruption) {
            super("WAL " + logPath + " is corrupt at byte " + corruptOffset + " of " + fileSize
                    + "; " + entriesBeforeCorruption
                    + " entries precede the damage. Not repaired: restore this node from its peers.");
            this.logPath = logPath;
            this.corruptOffset = corruptOffset;
            this.fileSize = fileSize;
            this.entriesBeforeCorruption = entriesBeforeCorruption;
        }

        /** The WAL file that is corrupt. */
        public Path logPath() { return logPath; }

        /** Byte offset of the first invalid record; every byte before it decoded cleanly. */
        public long corruptOffset() { return corruptOffset; }

        /** Size of the WAL file when the corruption was found. */
        public long fileSize() { return fileSize; }

        /** Number of logical entries that replay had reconstructed before the damage. */
        public int entriesBeforeCorruption() { return entriesBeforeCorruption; }
    }
}
