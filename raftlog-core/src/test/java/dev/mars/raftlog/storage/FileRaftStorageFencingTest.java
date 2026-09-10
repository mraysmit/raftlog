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

import dev.mars.raftlog.storage.RaftStorage.LogEntryData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fencing after durability failures, and the torn-tail versus corruption
 * classification during replay.
 * <p>
 * Durability failures are injected through the package-private {@link CompactionIo}
 * seam at real filesystem boundaries; no mocking framework is used.
 */
class FileRaftStorageFencingTest {

    @TempDir Path dir;

    private static final List<LogEntryData> SEED = List.of(entry(1, 1), entry(2, 1), entry(3, 1));

    private static LogEntryData entry(long index, long term) {
        return new LogEntryData(index, term, ("e-" + index + "-" + term).getBytes());
    }

    private static <T> T await(CompletableFuture<T> future) throws Exception {
        return future.get(10, TimeUnit.SECONDS);
    }

    private static Throwable failureOf(CompletableFuture<?> future) {
        ExecutionException failure = assertThrows(ExecutionException.class, () -> await(future));
        return failure.getCause();
    }

    private static void close(FileRaftStorage storage, Path dir) throws Exception {
        storage.close();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        try (FileChannel channel = FileChannel.open(dir.resolve("raft.lock"), StandardOpenOption.WRITE)) {
            while (System.nanoTime() < deadline) {
                try (var lock = channel.tryLock()) {
                    if (lock != null) return;
                } catch (OverlappingFileLockException pendingClose) {
                    // still owned by the closing instance
                }
                Thread.sleep(5);
            }
        }
        fail("Storage did not release its lock after close");
    }

    private void seed() throws Exception {
        FileRaftStorage storage = new FileRaftStorage(true);
        await(storage.open(dir));
        try {
            await(storage.appendEntries(SEED));
            await(storage.sync());
        } finally { close(storage, dir); }
    }

    private static void assertEntries(List<LogEntryData> expected, List<LogEntryData> actual) {
        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) {
            assertEquals(expected.get(i).index(), actual.get(i).index());
            assertEquals(expected.get(i).term(), actual.get(i).term());
            assertArrayEquals(expected.get(i).payload(), actual.get(i).payload());
        }
    }

    private void assertFenced(FileRaftStorage storage, Class<? extends Throwable> cause) {
        assertInstanceOf(cause, failureOf(storage.appendEntries(List.of(entry(9, 9)))));
        assertInstanceOf(cause, failureOf(storage.truncateSuffix(1)));
        assertInstanceOf(cause, failureOf(storage.sync()));
        assertInstanceOf(cause, failureOf(storage.updateMetadata(9, Optional.empty())));
        assertInstanceOf(cause, failureOf(storage.replayLog()));
        assertInstanceOf(cause, failureOf(storage.truncatePrefix(1)));
    }

    // ------------------------------------------------------------------
    // fsync failure fences the instance
    // ------------------------------------------------------------------

    /** Fails the Nth force of the live WAL or metadata staging file. */
    private static final class FailingForce extends CompactionIo {
        final AtomicInteger calls = new AtomicInteger();
        final int failOn;
        FailingForce(int failOn) { this.failOn = failOn; }
        @Override void forceChannel(FileChannel channel) throws IOException {
            if (calls.incrementAndGet() == failOn) throw new IOException("Injected fsync failure");
            super.forceChannel(channel);
        }
    }

    @Test void failedWalSyncFencesTheInstanceAndDoesNotRetry() throws Exception {
        seed();
        FailingForce io = new FailingForce(1);
        FileRaftStorage storage = new FileRaftStorage(RaftStorageConfig.builder().build(), io);
        await(storage.open(dir));
        try {
            await(storage.appendEntries(List.of(entry(4, 2))));
            Throwable cause = failureOf(storage.sync());
            assertInstanceOf(FileRaftStorage.StorageException.class, cause);
            assertEquals("Injected fsync failure", cause.getCause().getMessage());

            // No later call may re-force: the page cache state is undefined.
            assertFenced(storage, FileRaftStorage.StorageException.class);
            assertEquals(1, io.calls.get(), "sync must not be retried after a failure");
        } finally { close(storage, dir); }

        // A fresh instance recovers whatever actually reached the file.
        FileRaftStorage fresh = new FileRaftStorage(true);
        await(fresh.open(dir));
        try {
            List<LogEntryData> replayed = await(fresh.replayLog());
            assertTrue(replayed.size() == 3 || replayed.size() == 4);
            assertEntries(SEED, replayed.subList(0, 3));
            await(fresh.appendEntries(List.of(entry(10, 3))));
            await(fresh.sync());
        } finally { close(fresh, dir); }
    }

    @Test void failedMetadataStagingForceFencesTheInstanceAndKeepsOldMetadata() throws Exception {
        seed();
        FileRaftStorage seeded = new FileRaftStorage(true);
        await(seeded.open(dir));
        try { await(seeded.updateMetadata(5, Optional.of("node-a"))); } finally { close(seeded, dir); }

        FailingForce io = new FailingForce(1);
        FileRaftStorage storage = new FileRaftStorage(RaftStorageConfig.builder().build(), io);
        await(storage.open(dir));
        try {
            assertInstanceOf(FileRaftStorage.StorageException.class,
                    failureOf(storage.updateMetadata(6, Optional.of("node-b"))));
            assertFenced(storage, FileRaftStorage.StorageException.class);
        } finally { close(storage, dir); }

        FileRaftStorage fresh = new FileRaftStorage(true);
        await(fresh.open(dir));
        try {
            RaftStorage.PersistentMeta meta = await(fresh.loadMetadata());
            assertEquals(5, meta.currentTerm());
            assertEquals(Optional.of("node-a"), meta.votedFor());
        } finally { close(fresh, dir); }
    }

    @Test void failedDirectoryForceAfterMetadataRenameFencesTheInstance() throws Exception {
        seed();
        CompactionIo io = new CompactionIo() {
            @Override void forceDirectory(Path directory) throws IOException {
                throw new IOException("Injected directory fsync failure");
            }
        };
        FileRaftStorage storage = new FileRaftStorage(RaftStorageConfig.builder().build(), io);
        await(storage.open(dir));
        try {
            Throwable cause = failureOf(storage.updateMetadata(7, Optional.of("node-c")));
            assertInstanceOf(FileRaftStorage.StorageException.class, cause);
            assertEquals("Injected directory fsync failure", cause.getCause().getMessage());
            // The rename itself completed; the directory entry is not known to be durable.
            // The instance must not carry on as if it were.
            assertFenced(storage, FileRaftStorage.StorageException.class);
        } finally { close(storage, dir); }
    }

    @Test void testOnlyFsyncBypassSkipsForceAndNeverFences() throws Exception {
        seed();
        FailingForce io = new FailingForce(1);
        FileRaftStorage storage = FileRaftStorage.unsafeWithoutFsyncForTesting(
                RaftStorageConfig.builder().build(), io);
        await(storage.open(dir));
        try {
            await(storage.appendEntries(List.of(entry(4, 2))));
            await(storage.sync());
            await(storage.updateMetadata(3, Optional.empty()));
            assertEquals(0, io.calls.get());
        } finally { close(storage, dir); }
    }

    @Test void verifyWritesForceFailureFencesTheInstance() throws Exception {
        seed();
        FailingForce io = new FailingForce(1);
        FileRaftStorage storage = new FileRaftStorage(RaftStorageConfig.builder().verifyWrites(true).build(), io);
        await(storage.open(dir));
        try {
            assertInstanceOf(FileRaftStorage.StorageException.class,
                    failureOf(storage.appendEntries(List.of(entry(4, 2)))));
            assertFenced(storage, FileRaftStorage.StorageException.class);
        } finally { close(storage, dir); }
    }

    // ------------------------------------------------------------------
    // Replay classification: torn tail versus corruption
    // ------------------------------------------------------------------

    private long recordStart(int recordIndex) throws IOException {
        Path log = dir.resolve("raft.log");
        try (FileChannel ch = FileChannel.open(log, StandardOpenOption.READ)) {
            long pos = 0;
            ByteBuffer header = ByteBuffer.allocate(27);
            for (int i = 0; i < recordIndex; i++) {
                header.clear();
                ch.read(header, pos);
                header.flip();
                int payloadLen = header.getInt(23);
                pos += 27 + payloadLen + 4;
            }
            return pos;
        }
    }

    private void flip(long offset) throws IOException {
        Path log = dir.resolve("raft.log");
        byte[] bytes = Files.readAllBytes(log);
        bytes[(int) offset] ^= 0x55;
        Files.write(log, bytes);
    }

    @ParameterizedTest
    @ValueSource(strings = {"header", "payload", "crc"})
    void damageInsideCommittedRegionIsReportedFencedAndLeftIntact(String where) throws Exception {
        seed();
        long second = recordStart(1);
        long offset = switch (where) {
            case "header" -> second + 5;   // version byte
            case "payload" -> second + 27 + 1;
            default -> second + 27 + "e-2-1".length() + 1; // CRC byte
        };
        flip(offset);
        byte[] before = Files.readAllBytes(dir.resolve("raft.log"));

        FileRaftStorage storage = new FileRaftStorage(true);
        await(storage.open(dir));
        try {
            Throwable cause = failureOf(storage.replayLog());
            var corrupt = assertInstanceOf(FileRaftStorage.CorruptLogException.class, cause);
            assertEquals(second, corrupt.corruptOffset());
            assertEquals(1, corrupt.entriesBeforeCorruption());
            assertEquals(before.length, corrupt.fileSize());
            assertEquals(dir.resolve("raft.log"), corrupt.logPath());
            assertFenced(storage, FileRaftStorage.CorruptLogException.class);
        } finally { close(storage, dir); }
        assertArrayEquals(before, Files.readAllBytes(dir.resolve("raft.log")), "WAL must be unchanged");
    }

    @Test void damageInLastRecordIsReportedFencedAndLeftIntact() throws Exception {
        seed();
        long third = recordStart(2);
        flip(third + 27 + 1);
        byte[] before = Files.readAllBytes(dir.resolve("raft.log"));

        FileRaftStorage storage = new FileRaftStorage(true);
        await(storage.open(dir));
        try {
            var corrupt = assertInstanceOf(FileRaftStorage.CorruptLogException.class,
                    failureOf(storage.replayLog()));
            assertEquals(third, corrupt.corruptOffset());
            assertEquals(2, corrupt.entriesBeforeCorruption());
            assertFenced(storage, FileRaftStorage.CorruptLogException.class);
        } finally { close(storage, dir); }
        assertArrayEquals(before, Files.readAllBytes(dir.resolve("raft.log")));
    }

    @Test void damagedLengthFieldCannotHideValidRecordsAfterIt() throws Exception {
        seed();
        long second = recordStart(1);
        // Make the second record claim a huge payload so naive parsing would run off the end.
        Path log = dir.resolve("raft.log");
        try (FileChannel ch = FileChannel.open(log, StandardOpenOption.WRITE)) {
            ByteBuffer len = ByteBuffer.allocate(4).putInt(8 * 1024 * 1024);
            len.flip();
            ch.write(len, second + 23);
        }
        byte[] before = Files.readAllBytes(log);

        FileRaftStorage storage = new FileRaftStorage(true);
        await(storage.open(dir));
        try {
            var corrupt = assertInstanceOf(FileRaftStorage.CorruptLogException.class, failureOf(storage.replayLog()));
            assertEquals(second, corrupt.corruptOffset());
        } finally { close(storage, dir); }
        assertArrayEquals(before, Files.readAllBytes(log));
    }

    @Test void garbageAndZeroTailsWithoutValidRecordsAreReportedAsAmbiguousCorruption() throws Exception {
        seed();
        long validSize = Files.size(dir.resolve("raft.log"));
        byte[] garbage = new byte[70 * 1024]; // longer than the forward-scan window
        new Random(7).nextBytes(garbage);
        for (int i = 0; i < 4096; i++) garbage[garbage.length - 1 - i] = 0;
        Files.write(dir.resolve("raft.log"), garbage, StandardOpenOption.APPEND);

        FileRaftStorage storage = new FileRaftStorage(true);
        await(storage.open(dir));
        try {
            var corrupt = assertInstanceOf(FileRaftStorage.CorruptLogException.class,
                    failureOf(storage.replayLog()));
            assertEquals(validSize, corrupt.corruptOffset());
        } finally { close(storage, dir); }
        assertEquals(validSize + garbage.length, Files.size(dir.resolve("raft.log")));
    }

    @Test void validRecordBeyondScanWindowIsStillFound() throws Exception {
        seed();
        long validSize = Files.size(dir.resolve("raft.log"));
        byte[] garbage = new byte[70 * 1024];
        new Random(11).nextBytes(garbage);
        Files.write(dir.resolve("raft.log"), garbage, StandardOpenOption.APPEND);

        // Append a genuine record after the garbage, as bitrot in the middle would leave.
        FileRaftStorage writer = new FileRaftStorage(RaftStorageConfig.builder().build(), new CompactionIo());
        await(writer.open(dir));
        try {
            await(writer.appendEntries(List.of(entry(4, 1))));
            await(writer.sync());
        } finally { close(writer, dir); }

        FileRaftStorage storage = new FileRaftStorage(true);
        await(storage.open(dir));
        try {
            var corrupt = assertInstanceOf(FileRaftStorage.CorruptLogException.class, failureOf(storage.replayLog()));
            assertEquals(validSize, corrupt.corruptOffset());
            assertEquals(3, corrupt.entriesBeforeCorruption());
        } finally { close(storage, dir); }
    }

    @Test void compactionOnCorruptSourceFailsWithoutModifyingItAndFences() throws Exception {
        seed();
        flip(recordStart(1) + 27 + 1);
        byte[] before = Files.readAllBytes(dir.resolve("raft.log"));

        FileRaftStorage storage = new FileRaftStorage(true);
        await(storage.open(dir));
        try {
            assertInstanceOf(FileRaftStorage.CorruptLogException.class, failureOf(storage.truncatePrefix(1)));
            assertFalse(Files.exists(dir.resolve("raft.log.tmp")));
            assertFenced(storage, FileRaftStorage.CorruptLogException.class);
        } finally { close(storage, dir); }
        assertArrayEquals(before, Files.readAllBytes(dir.resolve("raft.log")));
    }
}
