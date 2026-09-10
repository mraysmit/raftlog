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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
 *  ├─ raft.log     // WAL: TRUNCATE and APPEND records; replaced on compaction
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

    /** Record format version */
    private static final short VERSION = 1;

    /** Record type: Truncate suffix from given index */
    private static final byte TYPE_TRUNCATE = 1;

    /** Record type: Append a log entry */
    private static final byte TYPE_APPEND = 2;

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
    private final RaftStorageConfig config;
    private final boolean syncEnabled;
    private final boolean verifyWrites;
    private final int maxPayloadSize;
    private final long minFreeSpace;
    private final CompactionIo compactionIo;

    private Path dataDir;
    private FileChannel logChannel;
    private FileChannel lockChannel;
    private FileLock exclusiveLock;
    private volatile boolean closed = false;

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
            return t;
        });

        LOG.info("FileRaftStorage initialized: syncEnabled={}, verifyWrites={}, maxPayloadSize={} MB, minFreeSpace={} MB",
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
    @Deprecated(since = "1.3.0", forRemoval = true)
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
    @Deprecated(since = "1.3.0", forRemoval = true)
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
    public CompletableFuture<Void> open(Path dataDir) {
        return CompletableFuture.runAsync(() -> {
            try {
                LOG.info("Opening WAL storage at: {}", dataDir);
                this.dataDir = dataDir;
                Files.createDirectories(dataDir);
                LOG.debug("Created/verified data directory: {}", dataDir);

                // Acquire exclusive lock to prevent multiple processes
                acquireExclusiveLock();

                // Only the published WAL is authoritative. A process interrupted
                // before atomic replacement may leave an incomplete rewrite.
                if (Files.exists(dataDir.resolve(LOG_TMP_FILE)) && !Files.exists(dataDir.resolve(LOG_FILE))) {
                    throw new IOException("Unpublished rewrite exists without raft.log; preserve directory for recovery");
                }
                Files.deleteIfExists(dataDir.resolve(LOG_TMP_FILE));

                // Check available disk space
                checkDiskSpace();

                Path logPath = dataDir.resolve(LOG_FILE);
                this.logChannel = FileChannel.open(logPath,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.READ,
                        StandardOpenOption.WRITE);

                // Seek to end for appends
                long logSize = logChannel.size();
                logChannel.position(logSize);
                LOG.info("WAL opened successfully: path={}, size={} bytes", logPath, logSize);

            } catch (IOException e) {
                LOG.error("Failed to open WAL at {}: {}", dataDir, e.getMessage(), e);
                releaseExclusiveLock();
                throw new StorageException("Failed to open WAL at " + dataDir, e);
            }
        }, walExecutor);
    }

    @Override
    public void close() {
        if (closed) {
            LOG.debug("Storage already closed, ignoring duplicate close()");
            return;
        }
        closed = true;
        LOG.info("Closing WAL storage at: {}", dataDir);

        walExecutor.execute(() -> {
            try {
                if (logChannel != null) {
                    logChannel.close();
                    LOG.debug("Log channel closed");
                }
            } catch (IOException e) {
                LOG.warn("Error closing log channel: {}", e.getMessage());
            }
            releaseExclusiveLock();
        });

        walExecutor.shutdown();
        LOG.info("WAL storage closed");
    }

    // ========================================================================
    // Metadata Operations
    // ========================================================================

    @Override
    public CompletableFuture<Void> updateMetadata(long currentTerm, Optional<String> votedFor) {
        return CompletableFuture.runAsync(() -> {
            ensureHealthy();
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

                LOG.info("Metadata updated: term={}, votedFor={}", currentTerm, votedForForLog(votedFor));

            } catch (IOException e) {
                LOG.error("Failed to update metadata: {}", e.getMessage(), e);
                throw new StorageException("Failed to update metadata", e);
            }
        }, walExecutor);
    }

    @Override
    public CompletableFuture<PersistentMeta> loadMetadata() {
        return CompletableFuture.supplyAsync(() -> {
            ensureHealthy();
            try {
                Path metaPath = dataDir.resolve(META_FILE);
                if (!Files.exists(metaPath)) {
                    LOG.debug("No metadata file found, returning empty metadata");
                    return PersistentMeta.EMPTY;
                }

                LOG.debug("Loading metadata from: {}", metaPath);
                byte[] all = Files.readAllBytes(metaPath);
                ByteBuffer buf = ByteBuffer.wrap(all);

                long term = buf.getLong();
                int voteLen = buf.getInt();

                // Validate length
                if (voteLen < 0 || voteLen > (all.length - 8 - 4 - 4)) {
                    LOG.error("Corrupt metadata: invalid vote length {}", voteLen);
                    throw new StorageException("Corrupt meta.dat: invalid vote length " + voteLen);
                }

                byte[] voteBytes = new byte[voteLen];
                buf.get(voteBytes);

                int expectedCrc = buf.getInt();

                // Verify CRC
                CRC32C crc = new CRC32C();
                crc.update(all, 0, 8 + 4 + voteLen);
                if ((int) crc.getValue() != expectedCrc) {
                    LOG.error("Corrupt metadata: CRC mismatch (expected={}, computed={})",
                            expectedCrc, (int) crc.getValue());
                    throw new StorageException("Corrupt meta.dat: CRC mismatch");
                }

                Optional<String> votedFor = voteLen == 0
                        ? Optional.empty()
                        : Optional.of(new String(voteBytes, StandardCharsets.UTF_8));

                LOG.info("Metadata loaded: term={}, votedFor={}", term, votedForForLog(votedFor));
                return new PersistentMeta(term, votedFor);

            } catch (IOException e) {
                LOG.error("Failed to load metadata: {}", e.getMessage(), e);
                throw new StorageException("Failed to load metadata", e);
            }
        }, walExecutor);
    }

    // ========================================================================
    // Log Operations
    // ========================================================================

    @Override
    public CompletableFuture<Void> appendEntries(List<LogEntryData> entries) {
        if (fatalFailure != null) return CompletableFuture.failedFuture(fatalFailure);
        if (entries == null || entries.isEmpty()) {
            LOG.trace("appendEntries called with empty list, no-op");
            return CompletableFuture.completedFuture(null);
        }

        // Validate payload sizes before writing (Section 19.2: Payload Length Trust Boundary)
        for (LogEntryData entry : entries) {
            if (entry.payload() != null && entry.payload().length > maxPayloadSize) {
                LOG.error("Payload too large: {} bytes (max: {})", entry.payload().length, maxPayloadSize);
                return CompletableFuture.failedFuture(
                        new StorageException("Payload too large: " + entry.payload().length +
                                " bytes (max: " + maxPayloadSize + ")"));
            }
        }

        LOG.debug("Appending {} entries (indices {}-{})",
                entries.size(),
                entries.getFirst().index(),
                entries.getLast().index());

        return CompletableFuture.runAsync(() -> {
            ensureHealthy();
            try {
                long totalBytes = 0;
                for (LogEntryData entry : entries) {
                    writeRecord(TYPE_APPEND, entry.index(), entry.term(),
                            entry.payload() != null ? entry.payload() : new byte[0]);
                    totalBytes += HEADER_SIZE + (entry.payload() != null ? entry.payload().length : 0) + CRC_SIZE;
                    LOG.trace("Appended entry: index={}, term={}, payloadSize={}",
                            entry.index(), entry.term(),
                            entry.payload() != null ? entry.payload().length : 0);
                }
                LOG.info("Appended {} entries to WAL: indices [{}-{}], terms [{}-{}], {} bytes",
                        entries.size(),
                        entries.getFirst().index(),
                        entries.getLast().index(),
                        entries.getFirst().term(),
                        entries.getLast().term(),
                        totalBytes);
            } catch (IOException e) {
                LOG.error("Failed to append entries: {}", e.getMessage(), e);
                throw new StorageException("Failed to append entries", e);
            }
        }, walExecutor);
    }

    @Override
    public CompletableFuture<Void> truncateSuffix(long fromIndex) {
        LOG.debug("Truncating log suffix from index {}", fromIndex);
        return CompletableFuture.runAsync(() -> {
            ensureHealthy();
            try {
                // Write a TRUNCATE record (no payload needed)
                writeRecord(TYPE_TRUNCATE, fromIndex, 0L, new byte[0]);
                LOG.info("Truncate record written: fromIndex={}", fromIndex);
            } catch (IOException e) {
                LOG.error("Failed to write truncate record: {}", e.getMessage(), e);
                throw new StorageException("Failed to write truncate record", e);
            }
        }, walExecutor);
    }

    @Override
    public CompletableFuture<Void> sync() {
        if (fatalFailure != null) return CompletableFuture.failedFuture(fatalFailure);
        if (!syncEnabled) {
            LOG.trace("sync() called but fsync is disabled");
            return CompletableFuture.completedFuture(null);
        }

        LOG.debug("Syncing WAL to disk");
        return CompletableFuture.runAsync(() -> {
            ensureHealthy();
            try {
                long startNanos = System.nanoTime();
                compactionIo.forceChannel(logChannel);
                long elapsedMicros = (System.nanoTime() - startNanos) / 1000;
                LOG.debug("WAL synced to disk in {} us", elapsedMicros);
            } catch (IOException e) {
                // After a failed fsync the kernel may have discarded the dirty pages;
                // a retry could report success for data that never reached the disk.
                throw fence("Failed to sync WAL", e);
            }
        }, walExecutor);
    }

    @Override
    public CompletableFuture<Void> truncatePrefix(long toIndex) {
        return CompletableFuture.runAsync(() -> {
            ensureHealthy();
            if (toIndex < 0) throw new IllegalArgumentException("Prefix boundary must not be negative");
            if (toIndex == 0) return;
            Path temporary = dataDir.resolve(LOG_TMP_FILE);
            Path published = dataDir.resolve(LOG_FILE);
            boolean publicationAttempted = false;
            try {
                // Do not turn source corruption into acknowledged prefix deletion.
                // The caller must explicitly replay/repair a torn tail first.
                List<LogEntryData> retained = readLog(false).stream().filter(e -> e.index() > toIndex).toList();
                checkDiskSpace();
                Files.deleteIfExists(temporary);
                try (FileChannel output = FileChannel.open(temporary,
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
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
                logChannel.close();
                compactionIo.replace(temporary, published);
                compactionIo.forceDirectory(dataDir);
                logChannel = compactionIo.reopen(published);
                logChannel.position(logChannel.size());
                LOG.info("Compacted WAL through index {}: {} entries retained", toIndex, retained.size());
            } catch (CorruptLogException e) {
                // Nothing was written, but the source cannot be trusted for any later write.
                throw fence("WAL contains ambiguous corruption", e);
            } catch (IOException | RuntimeException e) {
                StorageException failure = new StorageException("Prefix compaction failed", e);
                if (publicationAttempted) {
                    fatalFailure = failure;
                    try { logChannel.close(); } catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
                } else {
                    try { Files.deleteIfExists(temporary); } catch (IOException cleanupFailure) { failure.addSuppressed(cleanupFailure); }
                }
                throw failure;
            }
        }, walExecutor);
    }

    private void ensureHealthy() {
        if (fatalFailure != null) throw fatalFailure;
    }

    /**
     * Records a fatal failure so that every later operation fails with it, and
     * returns the exception for the caller to throw. Called only on the WAL executor.
     */
    private StorageException fence(String message, Throwable cause) {
        String detail = String.valueOf(cause.getMessage()).replaceFirst("\\.+$", "");
        LOG.error("{}: {}. Storage instance is now fenced; close it and open a fresh instance",
                message, detail, cause);
        StorageException failure = cause instanceof StorageException se ? se : new StorageException(message, cause);
        if (fatalFailure == null) fatalFailure = failure;
        return failure;
    }

    @Override
    public CompletableFuture<List<LogEntryData>> replayLog() {
        return CompletableFuture.supplyAsync(() -> {
            ensureHealthy();
            try {
                return readLog(true);
            } catch (CorruptLogException e) {
                // The channel is positioned after the corrupt region. Appending there would
                // splice new records onto data that cannot be trusted.
                throw fence("WAL contains ambiguous corruption", e);
            } catch (IOException e) {
                throw new StorageException("Failed to replay log", e);
            }
        }, walExecutor);
    }

    /** Result of decoding one record at a file position. */
    private record DecodedRecord(byte type, long index, long term, byte[] payload, long end) {
    }

    /** Produces a bounded, single-line representation of a potentially untrusted node identifier. */
    private static String votedForForLog(Optional<String> votedFor) {
        if (votedFor.isEmpty()) return "(none)";

        String value = votedFor.get();
        int end = Math.min(value.length(), MAX_LOGGED_VOTED_FOR_CHARS);
        if (end < value.length() && end > 0 && Character.isHighSurrogate(value.charAt(end - 1))) end--;

        StringBuilder safe = new StringBuilder(128);
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
     * Decodes the record at {@code pos}, or returns {@code null} if the bytes there
     * are not a complete, well-formed record with a matching CRC. The reason for a
     * {@code null} is logged at the given level so the main loop can warn while the
     * forward scan stays quiet. Replay separately classifies whether the bytes are a
     * structurally incomplete EOF fragment or ambiguous corruption.
     */
    private DecodedRecord decodeRecord(FileChannel ch, long pos, boolean warn) throws IOException {
        ByteBuffer headerBuf = ByteBuffer.allocate(HEADER_SIZE);
        int headerRead = readFully(ch, headerBuf, pos);
        if (headerRead < HEADER_SIZE) {
            if (warn && headerRead > 0) {
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

        if (magic != MAGIC || version != VERSION) {
            if (warn) LOG.warn("Invalid header at pos {}: magic=0x{}, version={}", pos, Integer.toHexString(magic), version);
            return null;
        }
        if (payloadLen < 0 || payloadLen > maxPayloadSize) {
            if (warn) LOG.warn("Invalid payload length at pos {}: {}", pos, payloadLen);
            return null;
        }
        if (type != TYPE_TRUNCATE && type != TYPE_APPEND) {
            if (warn) LOG.warn("Unknown record type at pos {}: {}", pos, type);
            return null;
        }

        ByteBuffer payloadBuf = ByteBuffer.allocate(payloadLen);
        int payloadRead = readFully(ch, payloadBuf, pos + HEADER_SIZE);
        if (payloadRead < payloadLen) {
            if (warn) LOG.debug("Incomplete payload at pos {}: read {} bytes, expected {}", pos, payloadRead, payloadLen);
            return null;
        }
        payloadBuf.flip();

        ByteBuffer crcBuf = ByteBuffer.allocate(CRC_SIZE);
        int crcRead = readFully(ch, crcBuf, pos + HEADER_SIZE + payloadLen);
        if (crcRead < CRC_SIZE) {
            if (warn) LOG.debug("Incomplete CRC at pos {}", pos);
            return null;
        }
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
            int read = readFully(ch, buf, scanPos);
            if (read < magicBytes.length) break;
            byte[] bytes = buf.array();
            for (int i = 0; i + magicBytes.length <= read; i++) {
                if (bytes[i] != magicBytes[0] || bytes[i + 1] != magicBytes[1]
                        || bytes[i + 2] != magicBytes[2] || bytes[i + 3] != magicBytes[3]) continue;
                if (decodeRecord(ch, scanPos + i, false) != null) return true;
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
        if (readFully(ch, header, pos) < HEADER_SIZE) return true;
        header.flip();

        int magic = header.getInt();
        short version = header.getShort();
        byte type = header.get();
        header.getLong(); // index
        header.getLong(); // term
        int payloadLen = header.getInt();

        if (magic != MAGIC || version != VERSION) return false;
        if (type != TYPE_TRUNCATE && type != TYPE_APPEND) return false;
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

        LOG.info("Replaying WAL from: {}", logPath);
        long startTime = System.currentTimeMillis();
        List<LogEntryData> entries = new ArrayList<>();
        int appendCount = 0;
        int truncateCount = 0;

        try (FileChannel ch = FileChannel.open(logPath,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE)) {

            long fileSize = ch.size();
            LOG.debug("WAL file size: {} bytes", fileSize);

            long pos = 0;
            while (pos < fileSize) {
                DecodedRecord record = decodeRecord(ch, pos, true);
                if (record == null) break;

                if (record.type() == TYPE_TRUNCATE) {
                    long truncateFrom = record.index();
                    int beforeSize = entries.size();
                    entries.removeIf(e -> e.index() >= truncateFrom);
                    truncateCount++;
                    LOG.trace("Replay TRUNCATE: fromIndex={}, removed {} entries", truncateFrom, beforeSize - entries.size());
                } else {
                    entries.add(new LogEntryData(record.index(), record.term(), record.payload()));
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
                LOG.warn("Truncating torn tail: {} bytes removed (file was {} bytes, valid data {} bytes)",
                        fileSize - lastGoodPos, fileSize, lastGoodPos);
                ch.truncate(lastGoodPos);
            }
        }

        // Update log channel position
        logChannel.position(Files.size(logPath));

        long elapsed = System.currentTimeMillis() - startTime;
        LOG.info("WAL replay complete: {} entries recovered, {} appends, {} truncates, {} ms",
                entries.size(), appendCount, truncateCount, elapsed);

        return entries;
    }

    private static int readFully(FileChannel channel, ByteBuffer buffer, long position) throws IOException {
        int total = 0;
        while (buffer.hasRemaining()) {
            int count = channel.read(buffer, position + total);
            if (count < 0) break;
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
                type == TYPE_APPEND ? "APPEND" : "TRUNCATE", index, term, payloadLen, recordSize);

        // Pre-flight disk space check for large writes
        if (recordSize > 1024 * 1024) { // Check for writes > 1MB
            LOG.debug("Large write detected ({} bytes), checking disk space", recordSize);
            checkDiskSpace();
        }

        ByteBuffer buf = encodeRecord(type, index, term, payload);
        int crcValue = buf.getInt(recordSize - CRC_SIZE);

        // Record position before write for verification
        long writePosition = logChannel.position();
        LOG.trace("Writing {} bytes at position {}", recordSize, writePosition);

        // Write to channel
        while (buf.hasRemaining()) {
            logChannel.write(buf);
        }

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
        buf.putShort(VERSION);
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
        Path lockPath = dataDir.resolve(LOCK_FILE);
        LOG.debug("Acquiring exclusive lock: {}", lockPath);

        lockChannel = FileChannel.open(lockPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE);

        try {
            exclusiveLock = lockChannel.tryLock();
            if (exclusiveLock == null) {
                lockChannel.close();
                LOG.error("Cannot acquire exclusive lock: another process holds the lock");
                throw new StorageException(
                        "Cannot acquire exclusive lock on WAL directory: " + dataDir +
                        ". Another process may be using this storage.");
            }
            LOG.info("Exclusive lock acquired: {}", lockPath);
        } catch (OverlappingFileLockException e) {
            lockChannel.close();
            LOG.error("Cannot acquire exclusive lock: lock already held in this JVM");
            throw new StorageException(
                    "Cannot acquire exclusive lock: lock already held in this JVM", e);
        }
    }

    /**
     * Releases the exclusive lock and closes the lock channel.
     */
    private void releaseExclusiveLock() {
        try {
            if (exclusiveLock != null && exclusiveLock.isValid()) {
                exclusiveLock.release();
                LOG.debug("Exclusive lock released");
            }
        } catch (IOException e) {
            LOG.warn("Could not release lock: {}", e.getMessage());
        }
        try {
            if (lockChannel != null && lockChannel.isOpen()) {
                lockChannel.close();
                LOG.trace("Lock channel closed");
            }
        } catch (IOException e) {
            LOG.warn("Could not close lock channel: {}", e.getMessage());
        }
    }

    /**
     * Checks that sufficient disk space is available.
     *
     * @throws StorageException if disk space is below minimum threshold
     */
    private void checkDiskSpace() throws IOException {
        FileStore store = Files.getFileStore(dataDir);
        long usableSpace = store.getUsableSpace();
        long usableSpaceMb = usableSpace / 1024 / 1024;
        long minFreeSpaceMb = minFreeSpace / 1024 / 1024;

        LOG.trace("Disk space check: {} MB available, {} MB required", usableSpaceMb, minFreeSpaceMb);

        if (usableSpace < minFreeSpace) {
            LOG.error("Insufficient disk space: {} MB available, need at least {} MB",
                    usableSpaceMb, minFreeSpaceMb);
            throw new StorageException(
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
