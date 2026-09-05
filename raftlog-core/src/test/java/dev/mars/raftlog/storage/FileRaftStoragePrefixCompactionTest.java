package dev.mars.raftlog.storage;

import dev.mars.raftlog.storage.RaftStorage.LogEntryData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class FileRaftStoragePrefixCompactionTest {
    @TempDir Path dir;

    static <T> T await(CompletableFuture<T> future) {
        return assertDoesNotThrow(() -> future.get(10, TimeUnit.SECONDS));
    }

    static LogEntryData entry(long index, long term) {
        return new LogEntryData(index, term, new byte[]{0, (byte) index, (byte) term, -1});
    }

    static FileRaftStorage open(Path dir) {
        FileRaftStorage storage = new FileRaftStorage(true);
        await(storage.open(dir));
        return storage;
    }

    static void close(FileRaftStorage storage, Path dir) throws Exception {
        storage.close();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        try (var channel = FileChannel.open(dir.resolve("raft.lock"), StandardOpenOption.WRITE)) {
            while (System.nanoTime() < deadline) {
                try (var lock = channel.tryLock()) {
                    if (lock != null) return;
                } catch (OverlappingFileLockException pending) { }
                Thread.sleep(5);
            }
        }
        fail("Storage did not release lock");
    }

    static void assertEntries(List<LogEntryData> expected, List<LogEntryData> actual) {
        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) {
            assertEquals(expected.get(i).index(), actual.get(i).index());
            assertEquals(expected.get(i).term(), actual.get(i).term());
            assertArrayEquals(expected.get(i).payload(), actual.get(i).payload());
        }
    }

    static void assertReopened(Path dir, List<LogEntryData> expected) throws Exception {
        FileRaftStorage storage = open(dir);
        try { assertEntries(expected, await(storage.replayLog())); }
        finally { close(storage, dir); }
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 1, 2, 4, Long.MAX_VALUE})
    void inclusiveBoundaryReclaimsBytesAndRetainsMetadataAcrossRestart(long boundary) throws Exception {
        var original = List.of(entry(1, 1), entry(2, 1), entry(3, 2), entry(4, 2));
        var expected = original.stream().filter(e -> e.index() > boundary).toList();
        FileRaftStorage storage = open(dir);
        try {
            await(storage.updateMetadata(7, Optional.of("node-test")));
            await(storage.appendEntries(original));
            await(storage.sync());
            await(storage.truncatePrefix(boundary));
            assertEntries(expected, await(storage.replayLog()));
            assertEquals(expected.size() * 35L, Files.size(dir.resolve("raft.log")));
        } finally { close(storage, dir); }
        storage = open(dir);
        try {
            assertEntries(expected, await(storage.replayLog()));
            assertEquals(new RaftStorage.PersistentMeta(7, Optional.of("node-test")), await(storage.loadMetadata()));
        } finally { close(storage, dir); }
    }

    @Test void compactionResolvesSuffixMarkersButPreservesRawAppendOrderAndDuplicates() throws Exception {
        var expected = List.of(entry(2, 1), entry(2, 1), entry(3, 3), entry(5, 3), entry(4, 3));
        FileRaftStorage storage = open(dir);
        try {
            await(storage.appendEntries(List.of(entry(1, 1), entry(2, 1), entry(2, 1), entry(3, 2), entry(4, 2))));
            await(storage.truncateSuffix(3));
            await(storage.appendEntries(expected.subList(2, 5)));
            await(storage.truncatePrefix(1));
            assertEntries(expected, await(storage.replayLog()));
            assertEquals(175, Files.size(dir.resolve("raft.log")));
        } finally { close(storage, dir); }
        assertReopened(dir, expected);
    }

    @Test void repeatCompactAppendAndSuffixReplaceSurviveMultipleRestarts() throws Exception {
        FileRaftStorage storage = open(dir);
        try {
            await(storage.appendEntries(List.of(entry(1, 1), entry(2, 1), entry(3, 1))));
            await(storage.truncatePrefix(2));
            byte[] compacted = Files.readAllBytes(dir.resolve("raft.log"));
            await(storage.truncatePrefix(2));
            assertArrayEquals(compacted, Files.readAllBytes(dir.resolve("raft.log")));
        } finally { close(storage, dir); }
        storage = open(dir);
        try {
            await(storage.replayLog());
            await(storage.appendEntries(List.of(entry(4, 1))));
            await(storage.truncateSuffix(3));
            await(storage.appendEntries(List.of(entry(3, 2), entry(4, 2))));
            await(storage.truncatePrefix(3));
        } finally { close(storage, dir); }
        assertReopened(dir, List.of(entry(4, 2)));
    }

    @Test void emptyWalCompactsAndAcceptsNonOneBasedAppend() throws Exception {
        FileRaftStorage storage = open(dir);
        try {
            await(storage.truncatePrefix(100));
            assertEquals(0, Files.size(dir.resolve("raft.log")));
            await(storage.appendEntries(List.of(entry(101, 5))));
            await(storage.sync());
        } finally { close(storage, dir); }
        assertReopened(dir, List.of(entry(101, 5)));
    }

    @Test void queuedAppendCompactionAppendUseSubmissionOrder() throws Exception {
        FileRaftStorage storage = open(dir);
        try {
            var first = storage.appendEntries(List.of(entry(1, 1), entry(2, 1)));
            var compact = storage.truncatePrefix(1);
            var next = storage.appendEntries(List.of(entry(3, 1)));
            await(CompletableFuture.allOf(first, compact, next));
            await(storage.sync());
        } finally { close(storage, dir); }
        assertReopened(dir, List.of(entry(2, 1), entry(3, 1)));
    }
}
