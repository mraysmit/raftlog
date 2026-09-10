package dev.mars.raftlog.storage;

import dev.mars.raftlog.storage.RaftStorage.LogEntryData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Verifies append, suffix truncation, and recovery contracts across storage restarts. */
class FileRaftStorageRecoveryContractTest {
    @TempDir Path tempDir;

    private static LogEntryData entry(long index, long term) {
        return new LogEntryData(index, term, new byte[]{(byte) term});
    }

    private static <T> T await(CompletableFuture<T> future) throws Exception {
        return future.get(10, TimeUnit.SECONDS);
    }

    private static FileRaftStorage open(Path dir) throws Exception {
        FileRaftStorage storage = new FileRaftStorage(true);
        await(storage.open(dir));
        return storage;
    }

    private static void close(FileRaftStorage storage, Path dir) throws Exception {
        storage.close();
        // close() queues cleanup. Observe real lock release before a fresh instance opens.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        try (FileChannel channel = FileChannel.open(dir.resolve("raft.lock"), StandardOpenOption.WRITE)) {
            while (System.nanoTime() < deadline) {
                try (var lock = channel.tryLock()) {
                    if (lock != null) return;
                } catch (OverlappingFileLockException pendingClose) {
                    // The previous instance still owns the lock.
                }
                Thread.sleep(5);
            }
        }
        fail("Storage did not release its lock after close");
    }

    private static void assertEntries(List<LogEntryData> expected, List<LogEntryData> actual) {
        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) {
            assertEquals(expected.get(i).index(), actual.get(i).index());
            assertEquals(expected.get(i).term(), actual.get(i).term());
            assertArrayEquals(expected.get(i).payload(), actual.get(i).payload());
        }
    }

    private static void persist(FileRaftStorage storage, AppendPlan plan,
                                List<LogEntryData> memory) throws Exception {
        if (plan.requiresTruncation()) await(storage.truncateSuffix(plan.truncateFromIndex()));
        if (plan.hasEntriesToAppend()) await(storage.appendEntries(plan.entriesToAppend()));
        await(storage.sync());
        plan.applyTo(memory);
    }

    private static List<LogEntryData> recover(Path dir) throws Exception {
        FileRaftStorage storage = open(dir);
        try { return await(storage.replayLog()); }
        finally { close(storage, dir); }
    }

    @Test void overlappingBatchAppendsOnlyNewEntriesAndRetryAfterRestartWritesNothing() throws Exception {
        List<LogEntryData> memory = new ArrayList<>(List.of(entry(1, 1), entry(2, 1)));
        List<LogEntryData> incoming = List.of(entry(2, 1), entry(3, 2), entry(4, 2));
        FileRaftStorage storage = open(tempDir);
        try {
            await(storage.appendEntries(memory));
            await(storage.sync());
            long before = Files.size(tempDir.resolve("raft.log"));
            persist(storage, AppendPlan.from(2, incoming, memory), memory);
            assertEquals(before + 64, Files.size(tempDir.resolve("raft.log")));
        } finally { close(storage, tempDir); }
        memory = new ArrayList<>(recover(tempDir));
        assertEntries(List.of(entry(1, 1), entry(2, 1), entry(3, 2), entry(4, 2)), memory);
        storage = open(tempDir);
        try {
            long before = Files.size(tempDir.resolve("raft.log"));
            AppendPlan retry = AppendPlan.from(2, incoming, memory);
            assertFalse(retry.requiresPersistence());
            persist(storage, retry, memory);
            assertEquals(before, Files.size(tempDir.resolve("raft.log")));
        } finally { close(storage, tempDir); }
        assertEntries(memory, recover(tempDir));
    }

    @Test void conflictRewritesEntireIncomingRemainderEvenWhenLaterTermsMatch() throws Exception {
        List<LogEntryData> memory = new ArrayList<>(List.of(entry(1, 1), entry(2, 1), entry(3, 3)));
        FileRaftStorage storage = open(tempDir);
        try {
            await(storage.appendEntries(memory));
            await(storage.sync());
            persist(storage, AppendPlan.from(2, List.of(entry(2, 2), entry(3, 3), entry(4, 3)), memory), memory);
        } finally { close(storage, tempDir); }
        // A planner that skips index 3 after detecting the conflict loses it on truncate.
        assertEntries(List.of(entry(1, 1), entry(2, 2), entry(3, 3), entry(4, 3)), recover(tempDir));
    }

    @Test void matchingShortRequestAndHeartbeatPreserveExistingFollowerTail() throws Exception {
        List<LogEntryData> expected = List.of(entry(1, 1), entry(2, 2), entry(3, 3));
        List<LogEntryData> memory = new ArrayList<>(expected);
        FileRaftStorage storage = open(tempDir);
        try {
            await(storage.appendEntries(memory));
            await(storage.sync());
            long before = Files.size(tempDir.resolve("raft.log"));
            persist(storage, AppendPlan.from(2, List.of(entry(2, 2)), memory), memory);
            persist(storage, AppendPlan.from(4, List.of(), memory), memory);
            assertEquals(before, Files.size(tempDir.resolve("raft.log")));
            assertEntries(expected, memory);
        } finally { close(storage, tempDir); }
        assertEntries(expected, recover(tempDir));
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 2, 4, 5, 99})
    void suffixBoundaryIsInclusiveAndRepeatedTruncationAllowsContinuedAppend(long from) throws Exception {
        List<LogEntryData> original = List.of(entry(1, 1), entry(2, 1), entry(3, 1), entry(4, 1));
        List<LogEntryData> expected = new ArrayList<>(original.stream().filter(e -> e.index() < from).toList());
        FileRaftStorage storage = open(tempDir);
        try {
            await(storage.appendEntries(original));
            await(storage.truncateSuffix(from));
            await(storage.truncateSuffix(from));
            await(storage.sync());
        } finally { close(storage, tempDir); }
        assertEntries(expected, recover(tempDir));
        storage = open(tempDir);
        try {
            LogEntryData next = entry(expected.size() + 1L, 2);
            await(storage.appendEntries(List.of(next)));
            await(storage.sync());
            expected.add(next);
        } finally { close(storage, tempDir); }
        assertEntries(expected, recover(tempDir));
    }

    @Test void suffixTruncationRemovesEveryDuplicateAtAndBeyondBoundary() throws Exception {
        FileRaftStorage storage = open(tempDir);
        try {
            await(storage.appendEntries(List.of(entry(1, 1), entry(2, 1), entry(3, 1), entry(2, 2))));
            await(storage.truncateSuffix(2));
            await(storage.appendEntries(List.of(entry(2, 3))));
            await(storage.sync());
        } finally { close(storage, tempDir); }
        assertEntries(List.of(entry(1, 1), entry(2, 3)), recover(tempDir));
    }

    @ParameterizedTest
    @CsvSource({"truncate,0", "truncate,1", "truncate,2", "truncate,3",
                "append,0", "append,1", "append,2", "append,3"})
    void invalidChecksumCannotApplyTruncationOrResurrectDiscardedTail(String record, int crcByte) throws Exception {
        List<LogEntryData> original = List.of(entry(1, 1), entry(2, 1), entry(3, 1));
        FileRaftStorage storage = open(tempDir);
        int truncateStart;
        int appendStart;
        try {
            await(storage.appendEntries(original));
            await(storage.sync());
            truncateStart = (int) Files.size(tempDir.resolve("raft.log"));
            await(storage.truncateSuffix(2));
            appendStart = (int) Files.size(tempDir.resolve("raft.log"));
            await(storage.appendEntries(List.of(entry(2, 2), entry(3, 2))));
            await(storage.sync());
        } finally { close(storage, tempDir); }
        boolean corruptTruncate = record.equals("truncate");
        byte[] bytes = Files.readAllBytes(tempDir.resolve("raft.log"));
        bytes[(corruptTruncate ? truncateStart + 27 : appendStart + 28) + crcByte] ^= 1;
        Files.write(tempDir.resolve("raft.log"), bytes);
        int corruptOffset = corruptTruncate ? truncateStart : appendStart;
        // Valid records follow the damaged one, so this is not a torn tail. Replay must
        // neither apply the unverifiable truncation nor resurrect the discarded tail,
        // and it must not delete the possibly-acknowledged records after the damage.
        FileRaftStorage damaged = open(tempDir);
        try {
            var failure = assertThrows(java.util.concurrent.ExecutionException.class, () -> await(damaged.replayLog()));
            var corrupt = assertInstanceOf(FileRaftStorage.CorruptLogException.class, failure.getCause());
            assertEquals(corruptOffset, corrupt.corruptOffset());
            assertEquals(corruptTruncate ? 3 : 1, corrupt.entriesBeforeCorruption());
            assertArrayEquals(bytes, Files.readAllBytes(tempDir.resolve("raft.log")));
            // Fenced: the damaged log cannot be appended to by this instance.
            assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> await(damaged.appendEntries(List.of(entry(9, 9)))));
        } finally { close(damaged, tempDir); }
        assertArrayEquals(bytes, Files.readAllBytes(tempDir.resolve("raft.log")));
        // An operator who knows the tail was unacknowledged truncates at the reported offset.
        try (FileChannel channel = FileChannel.open(tempDir.resolve("raft.log"), StandardOpenOption.WRITE)) {
            channel.truncate(corruptOffset);
        }
        List<LogEntryData> expected = new ArrayList<>(corruptTruncate ? original : List.of(entry(1, 1)));
        assertEntries(expected, recover(tempDir));
        storage = open(tempDir);
        try {
            LogEntryData next = entry(expected.size() + 1L, 4);
            await(storage.appendEntries(List.of(next)));
            await(storage.sync());
            expected.add(next);
        } finally { close(storage, tempDir); }
        assertEntries(expected, recover(tempDir));
    }

    @Test void tornSecondReplacementRetainsFirstReplacementAndCanFinishOnRetry() throws Exception {
        Path source = tempDir.resolve("batch-source");
        FileRaftStorage storage = open(source);
        int secondStart;
        try {
            await(storage.appendEntries(List.of(entry(1, 1), entry(2, 1), entry(3, 1), entry(4, 1))));
            await(storage.truncateSuffix(2));
            await(storage.appendEntries(List.of(entry(2, 2))));
            secondStart = (int) Files.size(source.resolve("raft.log"));
            await(storage.appendEntries(List.of(entry(3, 2))));
            await(storage.sync());
        } finally { close(storage, source); }
        byte[] bytes = Files.readAllBytes(source.resolve("raft.log"));
        for (int retained = 0; retained < 32; retained++) {
            Path dir = tempDir.resolve("batch-cut-" + retained);
            Files.createDirectories(dir);
            Files.write(dir.resolve("raft.log"), java.util.Arrays.copyOf(bytes, secondStart + retained));
            List<LogEntryData> memory = new ArrayList<>(recover(dir));
            assertEntries(List.of(entry(1, 1), entry(2, 2)), memory);
            storage = open(dir);
            try {
                persist(storage, AppendPlan.from(2, List.of(entry(2, 2), entry(3, 2)), memory), memory);
            } finally { close(storage, dir); }
            assertEntries(List.of(entry(1, 1), entry(2, 2), entry(3, 2)), recover(dir));
        }
    }

    @Test void rawAppendsReproduceBothReportedSequencesAfterReopen() throws Exception {
        for (int count : List.of(2, 3)) {
            Path dir = tempDir.resolve("raw-" + count);
            List<LogEntryData> expected = new ArrayList<>();
            for (int i = 1; i <= count; i++) expected.add(entry(i, 1));
            FileRaftStorage storage = open(dir);
            try {
                await(storage.appendEntries(expected));
                LogEntryData repeated = entry(count - 1, 2);
                await(storage.appendEntries(List.of(repeated)));
                expected.add(repeated);
                await(storage.sync());
            } finally { close(storage, dir); }
            storage = open(dir);
            try { assertEntries(expected, await(storage.replayLog())); }
            finally { close(storage, dir); }
        }
    }

    @Test void appendPlanSkipsRetriesAndTruncatesConflictingTailAcrossRestarts() throws Exception {
        List<LogEntryData> memory = new ArrayList<>(List.of(entry(1, 1), entry(2, 1), entry(3, 1)));
        FileRaftStorage storage = open(tempDir);
        try {
            await(storage.appendEntries(memory));
            await(storage.sync());
            long beforeRetry = Files.size(tempDir.resolve("raft.log"));
            AppendPlan retry = AppendPlan.from(1, List.copyOf(memory), memory);
            assertFalse(retry.requiresPersistence());
            retry.applyTo(memory);
            assertEquals(beforeRetry, Files.size(tempDir.resolve("raft.log")));
            AppendPlan replacement = AppendPlan.from(2, List.of(entry(2, 2)), memory);
            assertEquals(2L, replacement.truncateFromIndex());
            await(storage.truncateSuffix(replacement.truncateFromIndex()));
            await(storage.appendEntries(replacement.entriesToAppend()));
            await(storage.sync());
            replacement.applyTo(memory);
        } finally { close(storage, tempDir); }
        for (int restart = 0; restart < 2; restart++) {
            storage = open(tempDir);
            try { assertEntries(List.of(entry(1, 1), entry(2, 2)), await(storage.replayLog())); }
            finally { close(storage, tempDir); }
        }
    }

    @Test void suffixTruncationRemovesLogicalEntriesButReclaimsNoWalBytes() throws Exception {
        FileRaftStorage storage = open(tempDir);
        long after;
        try {
            await(storage.updateMetadata(7, Optional.of("synthetic-node")));
            await(storage.appendEntries(List.of(entry(1, 1), entry(2, 1), entry(3, 1), entry(4, 1))));
            await(storage.sync());
            long before = Files.size(tempDir.resolve("raft.log"));
            await(storage.truncateSuffix(3));
            await(storage.sync());
            after = Files.size(tempDir.resolve("raft.log"));
            assertEquals(before + 31, after, "Suffix deletion appends a tombstone");
        } finally { close(storage, tempDir); }
        storage = open(tempDir);
        try {
            assertEntries(List.of(entry(1, 1), entry(2, 1)), await(storage.replayLog()));
            assertEquals(after, Files.size(tempDir.resolve("raft.log")));
            assertEquals(new RaftStorage.PersistentMeta(7, Optional.of("synthetic-node")), await(storage.loadMetadata()));
        } finally { close(storage, tempDir); }
    }

    @Test void everyTornSuffixUpdateBoundaryRecoversAndAllowsAnotherRestart() throws Exception {
        // Generate actual encoded records, then retain every possible byte prefix of
        // TRUNCATE(31 bytes) + APPEND(32 bytes). This models torn tails, not power loss.
        Path source = tempDir.resolve("source");
        FileRaftStorage storage = open(source);
        long base;
        try {
            await(storage.appendEntries(List.of(entry(1, 1), entry(2, 1), entry(3, 1))));
            await(storage.sync());
            base = Files.size(source.resolve("raft.log"));
            await(storage.truncateSuffix(2));
            await(storage.appendEntries(List.of(entry(2, 2))));
            await(storage.sync());
        } finally { close(storage, source); }
        byte[] complete = Files.readAllBytes(source.resolve("raft.log"));
        for (int retained = 0; retained <= 63; retained++) {
            Path dir = tempDir.resolve("cut-" + retained);
            Files.createDirectories(dir);
            Files.write(dir.resolve("raft.log"), java.util.Arrays.copyOf(complete, (int) base + retained));
            List<LogEntryData> expected = new ArrayList<>(retained < 31
                    ? List.of(entry(1, 1), entry(2, 1), entry(3, 1))
                    : retained < 63 ? List.of(entry(1, 1)) : List.of(entry(1, 1), entry(2, 2)));
            storage = open(dir);
            try {
                assertEntries(expected, await(storage.replayLog()));
                assertEquals(base + (retained < 31 ? 0 : retained < 63 ? 31 : 63), Files.size(dir.resolve("raft.log")));
                LogEntryData next = entry(expected.size() + 1L, 3);
                await(storage.appendEntries(List.of(next)));
                await(storage.sync());
                expected.add(next);
            } finally { close(storage, dir); }
            storage = open(dir);
            try { assertEntries(expected, await(storage.replayLog())); }
            finally { close(storage, dir); }
        }
    }
}
