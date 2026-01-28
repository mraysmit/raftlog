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
 *  └─ raft.log     // append-only WAL: TRUNCATE and APPEND records
 * </pre>
 * <p>
 * <b>Thread Safety:</b>
 * All write operations are serialized through a single-threaded executor.
 * This ensures no concurrent writes can corrupt the log.
 * <p>
 * <b>Durability:</b>
 * <ul>
 *   <li>meta.dat: Uses atomic rename (write temp → fsync → rename → fsync dir)</li>
 *   <li>raft.log: Append-only with explicit {@link #sync()} barrier</li>
 * </ul>
 * <p>
 * <b>Protection Mechanisms:</b>
 * <ul>
 *   <li><b>File Locking:</b> Exclusive lock on raft.log prevents multiple processes
 *       from writing simultaneously. Lock is held for the lifetime of the storage instance.</li>
 *   <li><b>Disk Space Checking:</b> Pre-flight check before writes to detect low disk space
 *       early and fail gracefully rather than mid-write.</li>
 *   <li><b>Read-After-Write Verification:</b> Optional verification that written data
 *       can be read back correctly, detecting silent filesystem corruption.</li>
 * </ul>
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

    private Path dataDir;
    private FileChannel logChannel;
    private FileChannel lockChannel;
    private FileLock exclusiveLock;
    private volatile boolean closed = false;

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
        this.config = config;
        this.syncEnabled = config.syncEnabled();
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
            LOG.info("Write verification enabled (slower but safer)");
        }
    }

    /**
     * Creates a new FileRaftStorage with specified sync setting.
     * <p>
     * <b>Deprecated:</b> Use {@link #FileRaftStorage(RaftStorageConfig)} instead.
     *
     * @param syncEnabled if false, fsync is skipped (ONLY for testing!)
     */
    public FileRaftStorage(boolean syncEnabled) {
        this(RaftStorageConfig.builder().syncEnabled(syncEnabled).build());
    }

    /**
     * Creates a new FileRaftStorage with specified sync and verify settings.
     * <p>
     * <b>Deprecated:</b> Use {@link #FileRaftStorage(RaftStorageConfig)} instead.
     *
     * @param syncEnabled   if false, fsync is skipped (ONLY for testing!)
     * @param verifyWrites  if true, perform read-after-write verification
     */
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
            try {
                LOG.debug("Updating metadata: term={}, votedFor={}", currentTerm, votedFor.orElse("(none)"));
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
                        ch.force(true);
                        LOG.trace("Synced temp metadata file");
                    }
                }

                // Atomic rename
                Files.move(tmpPath, metaPath,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
                LOG.trace("Atomic rename: {} -> {}", tmpPath, metaPath);

                // Fsync directory (critical on Linux)
                if (syncEnabled) {
                    syncDirectory(dataDir);
                }

                LOG.info("Metadata updated: term={}, votedFor={}", currentTerm, votedFor.orElse("(none)"));

            } catch (IOException e) {
                LOG.error("Failed to update metadata: {}", e.getMessage(), e);
                throw new StorageException("Failed to update metadata", e);
            }
        }, walExecutor);
    }

    @Override
    public CompletableFuture<PersistentMeta> loadMetadata() {
        return CompletableFuture.supplyAsync(() -> {
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

                LOG.info("Metadata loaded: term={}, votedFor={}", term, votedFor.orElse("(none)"));
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
        if (!syncEnabled) {
            LOG.trace("sync() called but fsync is disabled");
            return CompletableFuture.completedFuture(null);
        }

        LOG.debug("Syncing WAL to disk");
        return CompletableFuture.runAsync(() -> {
            try {
                long startNanos = System.nanoTime();
                logChannel.force(true);
                long elapsedMicros = (System.nanoTime() - startNanos) / 1000;
                LOG.debug("WAL synced to disk in {} us", elapsedMicros);
            } catch (IOException e) {
                LOG.error("Failed to sync WAL: {}", e.getMessage(), e);
                throw new StorageException("Failed to sync WAL", e);
            }
        }, walExecutor);
    }

    @Override
    public CompletableFuture<List<LogEntryData>> replayLog() {
        return CompletableFuture.supplyAsync(() -> {
            try {
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
                    long lastGoodPos = 0;
                    ByteBuffer headerBuf = ByteBuffer.allocate(HEADER_SIZE);

                    while (true) {
                        // Read header
                        headerBuf.clear();
                        int headerRead = ch.read(headerBuf, pos);
                        if (headerRead < HEADER_SIZE) {
                            if (headerRead > 0) {
                                LOG.debug("Incomplete header at pos {}: read {} bytes, expected {}", 
                                        pos, headerRead, HEADER_SIZE);
                            }
                            break; // Incomplete header
                        }
                        headerBuf.flip();

                        int magic = headerBuf.getInt();
                        short version = headerBuf.getShort();
                        byte type = headerBuf.get();
                        long index = headerBuf.getLong();
                        long term = headerBuf.getLong();
                        int payloadLen = headerBuf.getInt();

                        // Validate header
                        if (magic != MAGIC || version != VERSION) {
                            LOG.warn("Invalid header at pos {}: magic=0x{}, version={}", 
                                    pos, Integer.toHexString(magic), version);
                            break; // Corrupt or torn header
                        }
                        if (payloadLen < 0 || payloadLen > maxPayloadSize) {
                            LOG.warn("Invalid payload length at pos {}: {}", pos, payloadLen);
                            break; // Invalid payload length
                        }

                        // Read payload
                        ByteBuffer payloadBuf = ByteBuffer.allocate(payloadLen);
                        int payloadRead = ch.read(payloadBuf, pos + HEADER_SIZE);
                        if (payloadRead < payloadLen) {
                            LOG.debug("Incomplete payload at pos {}: read {} bytes, expected {}", 
                                    pos, payloadRead, payloadLen);
                            break; // Incomplete payload
                        }
                        payloadBuf.flip();

                        // Read CRC
                        ByteBuffer crcBuf = ByteBuffer.allocate(CRC_SIZE);
                        int crcRead = ch.read(crcBuf, pos + HEADER_SIZE + payloadLen);
                        if (crcRead < CRC_SIZE) {
                            LOG.debug("Incomplete CRC at pos {}", pos);
                            break; // Incomplete CRC
                        }
                        crcBuf.flip();
                        int expectedCrc = crcBuf.getInt();

                        // Verify CRC over header + payload
                        CRC32C crc = new CRC32C();
                        headerBuf.rewind();
                        crc.update(headerBuf);
                        crc.update(payloadBuf.duplicate());
                        if ((int) crc.getValue() != expectedCrc) {
                            LOG.warn("CRC mismatch at pos {}: expected={}, computed={}", 
                                    pos, expectedCrc, (int) crc.getValue());
                            break; // CRC mismatch
                        }

                        // Process record
                        if (type == TYPE_TRUNCATE) {
                            // Remove entries with index >= truncate index
                            long truncateFrom = index;
                            int beforeSize = entries.size();
                            entries.removeIf(e -> e.index() >= truncateFrom);
                            int removed = beforeSize - entries.size();
                            truncateCount++;
                            LOG.trace("Replay TRUNCATE: fromIndex={}, removed {} entries", truncateFrom, removed);
                        } else if (type == TYPE_APPEND) {
                            byte[] payload = new byte[payloadLen];
                            payloadBuf.get(payload);
                            entries.add(new LogEntryData(index, term, payload));
                            appendCount++;
                            LOG.trace("Replay APPEND: index={}, term={}, payloadLen={}", index, term, payloadLen);
                        } else {
                            LOG.warn("Unknown record type at pos {}: {}", pos, type);
                            break; // Unknown record type
                        }

                        // Advance to next record
                        lastGoodPos = pos + HEADER_SIZE + payloadLen + CRC_SIZE;
                        pos = lastGoodPos;
                    }

                    // Truncate file to last good position (remove torn tail)
                    if (lastGoodPos < fileSize) {
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

            } catch (IOException e) {
                LOG.error("Failed to replay log: {}", e.getMessage(), e);
                throw new StorageException("Failed to replay log", e);
            }
        }, walExecutor);
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

    /**
     * Fsyncs a directory to ensure metadata changes (renames) are durable.
     * <p>
     * On Windows, this may fail or be a no-op. That's acceptable for development.
     * On Linux (ext4/xfs), this is critical for durability.
     */
    private void syncDirectory(Path dir) throws IOException {
        // Skip on Windows - directory sync isn't supported the same way
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            LOG.trace("Skipping directory sync on Windows");
            return;
        }

        try (FileChannel fc = FileChannel.open(dir, StandardOpenOption.READ)) {
            fc.force(true);
            LOG.trace("Directory synced: {}", dir);
        } catch (IOException e) {
            // Some systems don't support directory fsync - log but continue
            LOG.warn("Could not fsync directory {}: {}", dir, e.getMessage());
        }
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
     * Verifies a written record by reading it back and checking the CRC.
     * <p>
     * This detects silent filesystem corruption where writes appear to succeed
     * but data is not correctly persisted (e.g., faulty disk controller, bad RAM).
     *
     * @param position    the file position where the record was written
     * @param recordSize  the total size of the record
     * @param expectedCrc the expected CRC32C value
     * @throws StorageException if verification fails
     */
    private void verifyWrittenRecord(long position, int recordSize, int expectedCrc) throws IOException {
        // Flush to disk first
        logChannel.force(true);

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
}
