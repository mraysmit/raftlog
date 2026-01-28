package dev.mars.raftlog.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
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
 *
 * @see RaftStorage
 */
public final class FileRaftStorage implements RaftStorage {

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

    /** Maximum allowed payload size (16 MB) */
    private static final int MAX_PAYLOAD_SIZE = 16 * 1024 * 1024;

    /** Metadata file name */
    private static final String META_FILE = "meta.dat";

    /** Metadata temp file name */
    private static final String META_TMP_FILE = "meta.dat.tmp";

    /** WAL file name */
    private static final String LOG_FILE = "raft.log";

    // ========================================================================
    // State
    // ========================================================================

    private final ExecutorService walExecutor;
    private final boolean syncEnabled;

    private Path dataDir;
    private FileChannel logChannel;
    private volatile boolean closed = false;

    // ========================================================================
    // Constructor
    // ========================================================================

    /**
     * Creates a new FileRaftStorage with fsync enabled.
     */
    public FileRaftStorage() {
        this(true);
    }

    /**
     * Creates a new FileRaftStorage.
     *
     * @param syncEnabled if false, fsync is skipped (ONLY for testing!)
     */
    public FileRaftStorage(boolean syncEnabled) {
        this.syncEnabled = syncEnabled;
        // Single-threaded executor ensures write serialization
        this.walExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "wal-executor");
            t.setDaemon(true);
            return t;
        });

        if (!syncEnabled) {
            System.err.println("⚠️ WARNING: FileRaftStorage created with fsync DISABLED. " +
                    "Do NOT use in production!");
        }
    }

    // ========================================================================
    // Open / Close
    // ========================================================================

    @Override
    public CompletableFuture<Void> open(Path dataDir) {
        return CompletableFuture.runAsync(() -> {
            try {
                this.dataDir = dataDir;
                Files.createDirectories(dataDir);

                Path logPath = dataDir.resolve(LOG_FILE);
                this.logChannel = FileChannel.open(logPath,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.READ,
                        StandardOpenOption.WRITE);

                // Seek to end for appends
                logChannel.position(logChannel.size());

            } catch (IOException e) {
                throw new StorageException("Failed to open WAL at " + dataDir, e);
            }
        }, walExecutor);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;

        walExecutor.execute(() -> {
            try {
                if (logChannel != null) {
                    logChannel.close();
                }
            } catch (IOException e) {
                // Log but don't throw on close
                System.err.println("Error closing log channel: " + e.getMessage());
            }
        });

        walExecutor.shutdown();
    }

    // ========================================================================
    // Metadata Operations
    // ========================================================================

    @Override
    public CompletableFuture<Void> updateMetadata(long currentTerm, Optional<String> votedFor) {
        return CompletableFuture.runAsync(() -> {
            try {
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
                    }
                }

                // Atomic rename
                Files.move(tmpPath, metaPath,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);

                // Fsync directory (critical on Linux)
                if (syncEnabled) {
                    syncDirectory(dataDir);
                }

            } catch (IOException e) {
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
                    return PersistentMeta.EMPTY;
                }

                byte[] all = Files.readAllBytes(metaPath);
                ByteBuffer buf = ByteBuffer.wrap(all);

                long term = buf.getLong();
                int voteLen = buf.getInt();

                // Validate length
                if (voteLen < 0 || voteLen > (all.length - 8 - 4 - 4)) {
                    throw new StorageException("Corrupt meta.dat: invalid vote length " + voteLen);
                }

                byte[] voteBytes = new byte[voteLen];
                buf.get(voteBytes);

                int expectedCrc = buf.getInt();

                // Verify CRC
                CRC32C crc = new CRC32C();
                crc.update(all, 0, 8 + 4 + voteLen);
                if ((int) crc.getValue() != expectedCrc) {
                    throw new StorageException("Corrupt meta.dat: CRC mismatch");
                }

                Optional<String> votedFor = voteLen == 0
                        ? Optional.empty()
                        : Optional.of(new String(voteBytes, StandardCharsets.UTF_8));

                return new PersistentMeta(term, votedFor);

            } catch (IOException e) {
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
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.runAsync(() -> {
            try {
                for (LogEntryData entry : entries) {
                    writeRecord(TYPE_APPEND, entry.index(), entry.term(), entry.payload());
                }
            } catch (IOException e) {
                throw new StorageException("Failed to append entries", e);
            }
        }, walExecutor);
    }

    @Override
    public CompletableFuture<Void> truncateSuffix(long fromIndex) {
        return CompletableFuture.runAsync(() -> {
            try {
                // Write a TRUNCATE record (no payload needed)
                writeRecord(TYPE_TRUNCATE, fromIndex, 0L, new byte[0]);
            } catch (IOException e) {
                throw new StorageException("Failed to write truncate record", e);
            }
        }, walExecutor);
    }

    @Override
    public CompletableFuture<Void> sync() {
        if (!syncEnabled) {
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.runAsync(() -> {
            try {
                logChannel.force(true);
            } catch (IOException e) {
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
                    return List.of();
                }

                List<LogEntryData> entries = new ArrayList<>();

                try (FileChannel ch = FileChannel.open(logPath,
                        StandardOpenOption.READ,
                        StandardOpenOption.WRITE)) {

                    long pos = 0;
                    long lastGoodPos = 0;
                    ByteBuffer headerBuf = ByteBuffer.allocate(HEADER_SIZE);

                    while (true) {
                        // Read header
                        headerBuf.clear();
                        int headerRead = ch.read(headerBuf, pos);
                        if (headerRead < HEADER_SIZE) {
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
                            break; // Corrupt or torn header
                        }
                        if (payloadLen < 0 || payloadLen > MAX_PAYLOAD_SIZE) {
                            break; // Invalid payload length
                        }

                        // Read payload
                        ByteBuffer payloadBuf = ByteBuffer.allocate(payloadLen);
                        int payloadRead = ch.read(payloadBuf, pos + HEADER_SIZE);
                        if (payloadRead < payloadLen) {
                            break; // Incomplete payload
                        }
                        payloadBuf.flip();

                        // Read CRC
                        ByteBuffer crcBuf = ByteBuffer.allocate(CRC_SIZE);
                        int crcRead = ch.read(crcBuf, pos + HEADER_SIZE + payloadLen);
                        if (crcRead < CRC_SIZE) {
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
                            break; // CRC mismatch
                        }

                        // Process record
                        if (type == TYPE_TRUNCATE) {
                            // Remove entries with index >= truncate index
                            long truncateFrom = index;
                            entries.removeIf(e -> e.index() >= truncateFrom);
                        } else if (type == TYPE_APPEND) {
                            byte[] payload = new byte[payloadLen];
                            payloadBuf.get(payload);
                            entries.add(new LogEntryData(index, term, payload));
                        } else {
                            break; // Unknown record type
                        }

                        // Advance to next record
                        lastGoodPos = pos + HEADER_SIZE + payloadLen + CRC_SIZE;
                        pos = lastGoodPos;
                    }

                    // Truncate file to last good position (remove torn tail)
                    ch.truncate(lastGoodPos);
                }

                // Update log channel position
                logChannel.position(Files.size(logPath));

                return entries;

            } catch (IOException e) {
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
        ByteBuffer buf = ByteBuffer.allocate(HEADER_SIZE + payloadLen + CRC_SIZE);

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
        buf.putInt((int) crc.getValue());

        buf.flip();

        // Write to channel
        while (buf.hasRemaining()) {
            logChannel.write(buf);
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
            return;
        }

        try (FileChannel fc = FileChannel.open(dir, StandardOpenOption.READ)) {
            fc.force(true);
        } catch (IOException e) {
            // Some systems don't support directory fsync - log but continue
            System.err.println("Warning: Could not fsync directory " + dir + ": " + e.getMessage());
        }
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
