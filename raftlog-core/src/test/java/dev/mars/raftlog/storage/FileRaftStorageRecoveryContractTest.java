package dev.mars.raftlog.storage;

import dev.mars.raftlog.storage.RaftStorage.LogEntryData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
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
        await(storage.closeAsync());
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

    private static FileRaftStorage.WriteRejectedException assertRejected(
            CompletableFuture<?> future, WriteRejectionReason reason) {
        var failure = assertThrows(java.util.concurrent.ExecutionException.class, () -> await(future));
        var rejected = assertInstanceOf(FileRaftStorage.WriteRejectedException.class, failure.getCause());
        assertEquals(reason, rejected.reason());
        return rejected;
    }

    private static List<LogEntryData> recover(Path dir) throws Exception {
        FileRaftStorage storage = open(dir);
        try { return await(storage.replayLog()); }
        finally { close(storage, dir); }
    }

    @Test void closeCompletionAllowsImmediateReopenAndIsIdempotent() throws Exception {
        FileRaftStorage first = open(tempDir);
        CompletableFuture<Void> firstClose = first.closeAsync();
        assertSame(firstClose, first.closeAsync());
        await(firstClose);

        FileRaftStorage second = open(tempDir);
        close(second, tempDir);
    }

    @Test void concurrentOpenOfSameDirectoryIsIdempotent() throws Exception {
        FileRaftStorage storage = new FileRaftStorage(true);
        CompletableFuture<Void> firstOpen = storage.open(tempDir);
        CompletableFuture<Void> secondOpen = storage.open(tempDir);

        await(firstOpen);
        await(secondOpen);
        await(storage.closeAsync());
    }

    @Test void operationsAfterCloseFailWithStorageExceptionNotExecutorRejection() throws Exception {
        FileRaftStorage storage = open(tempDir);
        await(storage.closeAsync());

        List<CompletableFuture<?>> rejected = List.of(
                storage.appendEntries(List.of(entry(1, 1))),
                storage.sync(),
                storage.replayLog(),
                storage.loadMetadata(),
                storage.updateMetadata(1, Optional.empty()),
                storage.truncateSuffix(1),
                storage.truncatePrefix(1),
                storage.open(tempDir));
        for (CompletableFuture<?> future : rejected) {
            assertTrue(future.isCompletedExceptionally());
            Throwable cause = assertThrows(java.util.concurrent.ExecutionException.class, future::get).getCause();
            assertInstanceOf(FileRaftStorage.StorageException.class, cause);
            assertTrue(cause.getMessage().startsWith("Storage is closed"), cause.getMessage());
        }
    }

    @Test void operationsQueuedBehindFailedOpenFailWithStorageException() throws Exception {
        // Hold the directory lock so the open fails, then queue work behind it.
        FileRaftStorage holder = open(tempDir);
        try {
            // Real state, so "untouched" is a claim about something. A leftover compaction
            // file is included because a successful open deletes it and a failed one must not.
            await(holder.appendEntries(List.of(entry(1, 1), entry(2, 1))));
            await(holder.updateMetadata(3, Optional.of("holder")));
            await(holder.sync());
            Files.write(tempDir.resolve("raft.log.tmp"), new byte[]{1, 2, 3});

            // The holder is idle from here on, so the failed open and everything queued
            // behind it must leave the directory byte-for-byte as it was.
            try (var ignoredUntouched = DurableState.expectUnchanged(tempDir)) {
                FileRaftStorage storage = new FileRaftStorage(true);
                CompletableFuture<Void> opening = storage.open(tempDir);
                CompletableFuture<Void> append = storage.appendEntries(List.of(entry(3, 1)));
                CompletableFuture<Void> truncate = storage.truncateSuffix(1);
                CompletableFuture<Void> metadata = storage.updateMetadata(9, Optional.of("intruder"));
                CompletableFuture<Void> compact = storage.truncatePrefix(1);
                CompletableFuture<List<LogEntryData>> replay = storage.replayLog();
                CompletableFuture<Void> sync = storage.sync();

                assertThrows(java.util.concurrent.ExecutionException.class, () -> await(opening));
                for (CompletableFuture<?> future : List.of(append, truncate, metadata, compact, replay, sync)) {
                    Throwable cause = assertThrows(java.util.concurrent.ExecutionException.class,
                            () -> await(future)).getCause();
                    assertInstanceOf(FileRaftStorage.StorageException.class, cause);
                    assertTrue(cause.getMessage().startsWith("Storage is not open"), cause.getMessage());
                }
                await(storage.closeAsync());
            }
            // The holder still owns a working log.
            await(holder.appendEntries(List.of(entry(3, 1))));
            await(holder.sync());
        } finally {
            await(holder.closeAsync());
        }
        assertEntries(List.of(entry(1, 1), entry(2, 1), entry(3, 1)), recover(tempDir));
    }

    @Test void operationsBeforeOpenFailWithStorageException() throws Exception {
        FileRaftStorage storage = new FileRaftStorage(true);
        DurableState untouchedAtLine145 = DurableState.expectUnchanged(tempDir);
        Throwable cause = assertThrows(java.util.concurrent.ExecutionException.class,
                () -> await(storage.appendEntries(List.of(entry(1, 1))))).getCause();
        untouchedAtLine145.close();
        assertInstanceOf(FileRaftStorage.StorageException.class, cause);
        assertTrue(cause.getMessage().startsWith("Storage is not open"), cause.getMessage());
        await(storage.closeAsync());
    }

    @Test void syncIsAnOrderingBarrierEvenWithFsyncDisabled() throws Exception {
        FileRaftStorage storage = FileRaftStorage.unsafeWithoutFsyncForTesting(false);
        await(storage.open(tempDir));
        CompletableFuture<Void> append = storage.appendEntries(List.of(entry(1, 1)));
        await(storage.sync());
        assertTrue(append.isDone(), "sync() completed before an earlier append");
        await(storage.closeAsync());
    }

    @Test void closeDrainsOperationsAcceptedBeforeIt() throws Exception {
        CountDownLatch forceStarted = new CountDownLatch(1);
        CountDownLatch releaseForce = new CountDownLatch(1);
        CompactionIo blockingIo = new CompactionIo() {
            @Override
            void forceChannel(FileChannel channel) throws IOException {
                forceStarted.countDown();
                try {
                    assertTrue(releaseForce.await(10, TimeUnit.SECONDS), "Timed out waiting to release force");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while blocking force", e);
                }
                super.forceChannel(channel);
            }
        };
        RaftStorageConfig config = RaftStorageConfig.builder().dataDir(tempDir.toString()).build();
        FileRaftStorage storage = new FileRaftStorage(config, blockingIo);
        await(storage.open());

        CompletableFuture<Void> blockingSync = storage.sync();
        assertTrue(forceStarted.await(10, TimeUnit.SECONDS));
        CompletableFuture<Void> acceptedAppend = storage.appendEntries(List.of(entry(1, 1)));
        CompletableFuture<Void> close = storage.closeAsync();
        releaseForce.countDown();

        await(blockingSync);
        await(acceptedAppend);
        await(close);
        assertEntries(List.of(entry(1, 1)), recover(tempDir));
    }

    @Test void executorRefusalIsReportedThroughFailedFuture() throws Exception {
        FileRaftStorage storage = new FileRaftStorage(true);
        Field executorField = FileRaftStorage.class.getDeclaredField("walExecutor");
        executorField.setAccessible(true);
        ((ExecutorService) executorField.get(storage)).shutdown();

        CompletableFuture<Void> rejected = assertDoesNotThrow(() -> storage.open(tempDir));
        Throwable cause = assertThrows(java.util.concurrent.ExecutionException.class, rejected::get).getCause();
        assertInstanceOf(FileRaftStorage.StorageException.class, cause);
        assertTrue(cause.getMessage().startsWith("Storage operation could not be scheduled"), cause.getMessage());
    }

    @Test void appendCapturesCallerOwnedListBeforeReturning() throws Exception {
        CountDownLatch forceStarted = new CountDownLatch(1);
        CountDownLatch releaseForce = new CountDownLatch(1);
        FileRaftStorage storage = storageWithBlockingForce(forceStarted, releaseForce);
        await(storage.open());
        CompletableFuture<Void> blockingSync = storage.sync();
        assertTrue(forceStarted.await(10, TimeUnit.SECONDS));

        ArrayList<LogEntryData> entries = new ArrayList<>(List.of(entry(1, 1)));
        CompletableFuture<Void> append = storage.appendEntries(entries);
        entries.clear();
        releaseForce.countDown();

        await(blockingSync);
        await(append);
        await(storage.closeAsync());
        assertEntries(List.of(entry(1, 1)), recover(tempDir));
    }

    @Test void appendCapturesCallerOwnedPayloadBeforeReturning() throws Exception {
        CountDownLatch forceStarted = new CountDownLatch(1);
        CountDownLatch releaseForce = new CountDownLatch(1);
        FileRaftStorage storage = storageWithBlockingForce(forceStarted, releaseForce);
        await(storage.open());
        CompletableFuture<Void> blockingSync = storage.sync();
        assertTrue(forceStarted.await(10, TimeUnit.SECONDS));

        byte[] payload = {1};
        CompletableFuture<Void> append = storage.appendEntries(List.of(new LogEntryData(1, 1, payload)));
        payload[0] = 9;
        releaseForce.countDown();

        await(blockingSync);
        await(append);
        await(storage.closeAsync());
        assertEntries(List.of(entry(1, 1)), recover(tempDir));
    }

    private FileRaftStorage storageWithBlockingForce(CountDownLatch forceStarted,
                                                     CountDownLatch releaseForce) {
        CompactionIo blockingIo = new CompactionIo() {
            @Override
            void forceChannel(FileChannel channel) throws IOException {
                forceStarted.countDown();
                try {
                    assertTrue(releaseForce.await(10, TimeUnit.SECONDS), "Timed out waiting to release force");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while blocking force", e);
                }
            }
        };
        RaftStorageConfig config = RaftStorageConfig.builder().dataDir(tempDir.toString()).build();
        return new FileRaftStorage(config, blockingIo);
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

    @Test void suffixTruncationBeyondTheTailIsRejectedWithoutWriting() throws Exception {
        FileRaftStorage storage = open(tempDir);
        try {
            await(storage.appendEntries(List.of(entry(1, 1), entry(2, 1))));
            long before = Files.size(tempDir.resolve("raft.log"));
            try (var ignoredUntouched = DurableState.expectUnchanged(tempDir)) {
                assertRejected(storage.truncateSuffix(99), WriteRejectionReason.INVALID_TRUNCATION);
            }
            assertEquals(before, Files.size(tempDir.resolve("raft.log")));
        } finally { close(storage, tempDir); }
        assertEntries(List.of(entry(1, 1), entry(2, 1)), recover(tempDir));
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 2, 4, 5})
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
            await(storage.replayLog());
            LogEntryData next = entry(expected.size() + 1L, 2);
            await(storage.appendEntries(List.of(next)));
            await(storage.sync());
            expected.add(next);
        } finally { close(storage, tempDir); }
        assertEntries(expected, recover(tempDir));
    }

    @Test void batchWithDuplicateIndexIsRejectedWholeAndTruncationThenContinues() throws Exception {
        FileRaftStorage storage = open(tempDir);
        try {
            try (var ignoredUntouched = DurableState.expectUnchanged(tempDir)) {
                assertRejected(storage.appendEntries(List.of(entry(1, 1), entry(2, 1), entry(3, 1), entry(2, 2))),
                        WriteRejectionReason.INDEX_NOT_CONTIGUOUS);
            }
            assertEquals(0, Files.size(tempDir.resolve("raft.log")), "a rejected batch writes nothing");
            await(storage.appendEntries(List.of(entry(1, 1), entry(2, 1), entry(3, 1))));
            await(storage.truncateSuffix(2));
            await(storage.appendEntries(List.of(entry(2, 3))));
            await(storage.sync());
        } finally { close(storage, tempDir); }
        assertEntries(List.of(entry(1, 1), entry(2, 3)), recover(tempDir));
    }

    @Test void writesBeforeReplayOnExistingLogAreRejected() throws Exception {
        FileRaftStorage storage = open(tempDir);
        try {
            await(storage.appendEntries(List.of(entry(1, 1))));
            await(storage.sync());
        } finally { close(storage, tempDir); }
        storage = open(tempDir);
        try {
            try (var ignoredUntouched = DurableState.expectUnchanged(tempDir)) {
                assertRejected(storage.appendEntries(List.of(entry(2, 1))), WriteRejectionReason.LOG_STATE_UNKNOWN);
            }
            try (var ignoredUntouched = DurableState.expectUnchanged(tempDir)) {
                assertRejected(storage.truncateSuffix(1), WriteRejectionReason.LOG_STATE_UNKNOWN);
            }
            await(storage.replayLog());
            await(storage.appendEntries(List.of(entry(2, 1))));
            await(storage.sync());
        } finally { close(storage, tempDir); }
        assertEntries(List.of(entry(1, 1), entry(2, 1)), recover(tempDir));
    }

    @Test void freshLogMustStartAtIndexOne() throws Exception {
        FileRaftStorage storage = open(tempDir);
        try {
            try (var ignoredUntouched = DurableState.expectUnchanged(tempDir)) {
                assertRejected(storage.appendEntries(List.of(entry(2, 1))), WriteRejectionReason.INDEX_NOT_CONTIGUOUS);
            }
            try (var ignoredUntouched = DurableState.expectUnchanged(tempDir)) {
                assertRejected(storage.appendEntries(List.of(entry(7, 1))), WriteRejectionReason.INDEX_NOT_CONTIGUOUS);
            }
            assertEquals(0, Files.size(tempDir.resolve("raft.log")));
            await(storage.appendEntries(List.of(entry(1, 1))));
        } finally { close(storage, tempDir); }
        assertEntries(List.of(entry(1, 1)), recover(tempDir));
    }

    @Test void compactionBoundarySurvivesRestartEvenWhenNothingIsRetained() throws Exception {
        FileRaftStorage storage = open(tempDir);
        try {
            await(storage.appendEntries(List.of(entry(1, 1), entry(2, 1), entry(3, 1))));
            await(storage.truncatePrefix(3));
        } finally { close(storage, tempDir); }
        storage = open(tempDir);
        try {
            assertEntries(List.of(), await(storage.replayLog()));
            try (var ignoredUntouched = DurableState.expectUnchanged(tempDir)) {
                assertRejected(storage.appendEntries(List.of(entry(1, 1))), WriteRejectionReason.INDEX_NOT_CONTIGUOUS);
            }
            try (var ignoredUntouched = DurableState.expectUnchanged(tempDir)) {
                assertRejected(storage.appendEntries(List.of(entry(5, 1))), WriteRejectionReason.INDEX_NOT_CONTIGUOUS);
            }
            // Truncating into the compacted prefix is refused too.
            try (var ignoredUntouched = DurableState.expectUnchanged(tempDir)) {
                assertRejected(storage.truncateSuffix(3), WriteRejectionReason.INVALID_TRUNCATION);
            }
            await(storage.appendEntries(List.of(entry(4, 1))));
            await(storage.sync());
        } finally { close(storage, tempDir); }
        assertEntries(List.of(entry(4, 1)), recover(tempDir));
    }

    @Test void appendMustContinueAtTheTailAndTermsMustNotRegress() throws Exception {
        FileRaftStorage storage = open(tempDir);
        try {
            await(storage.appendEntries(List.of(entry(1, 2), entry(2, 2))));
            try (var ignoredUntouched = DurableState.expectUnchanged(tempDir)) {
                assertRejected(storage.appendEntries(List.of(entry(4, 2))), WriteRejectionReason.INDEX_NOT_CONTIGUOUS);
            }
            try (var ignoredUntouched = DurableState.expectUnchanged(tempDir)) {
                assertRejected(storage.appendEntries(List.of(entry(2, 2))), WriteRejectionReason.INDEX_NOT_CONTIGUOUS);
            }
            try (var ignoredUntouched = DurableState.expectUnchanged(tempDir)) {
                assertRejected(storage.appendEntries(List.of(entry(3, 1))), WriteRejectionReason.TERM_REGRESSION);
            }
            await(storage.appendEntries(List.of(entry(3, 2), entry(4, 5))));
            await(storage.sync());
        } finally { close(storage, tempDir); }
        assertEntries(List.of(entry(1, 2), entry(2, 2), entry(3, 2), entry(4, 5)), recover(tempDir));
    }

    @Test void metadataTermCannotRegressAndVoteCannotChangeWithinTerm() throws Exception {
        FileRaftStorage storage = open(tempDir);
        try {
            await(storage.updateMetadata(5, Optional.of("a")));
            try (var ignoredUntouched = DurableState.expectUnchanged(tempDir)) {
                assertRejected(storage.updateMetadata(4, Optional.empty()), WriteRejectionReason.TERM_REGRESSION);
            }
            try (var ignoredUntouched = DurableState.expectUnchanged(tempDir)) {
                assertRejected(storage.updateMetadata(5, Optional.of("b")), WriteRejectionReason.VOTE_CHANGED);
            }
            try (var ignoredUntouched = DurableState.expectUnchanged(tempDir)) {
                assertRejected(storage.updateMetadata(5, Optional.empty()), WriteRejectionReason.VOTE_CHANGED);
            }
            await(storage.updateMetadata(5, Optional.of("a")));
            await(storage.updateMetadata(6, Optional.empty()));
            await(storage.updateMetadata(6, Optional.of("c")));
        } finally { close(storage, tempDir); }
        // The baseline survives a restart without an explicit loadMetadata().
        storage = open(tempDir);
        try {
            try (var ignoredUntouched = DurableState.expectUnchanged(tempDir)) {
                assertRejected(storage.updateMetadata(5, Optional.of("c")), WriteRejectionReason.TERM_REGRESSION);
            }
            assertEquals(new RaftStorage.PersistentMeta(6, Optional.of("c")), await(storage.loadMetadata()));
        } finally { close(storage, tempDir); }
    }

    @Test void appendPlanUsesTheLogsOwnIndicesAfterPrefixCompaction() throws Exception {
        FileRaftStorage storage = open(tempDir);
        List<LogEntryData> memory;
        try {
            List<LogEntryData> original = new ArrayList<>();
            for (int i = 1; i <= 10; i++) original.add(entry(i, 1));
            await(storage.appendEntries(original));
            await(storage.truncatePrefix(6));
            memory = new ArrayList<>(await(storage.replayLog()));
            assertEquals(7, memory.getFirst().index());

            // Leader's view diverged at index 9: a conflict the plan must find at position 2.
            AppendPlan plan = AppendPlan.from(8, List.of(entry(8, 1), entry(9, 2), entry(10, 2)), memory);
            assertEquals(9L, plan.truncateFromIndex());
            assertEntries(List.of(entry(9, 2), entry(10, 2)), plan.entriesToAppend());
            persist(storage, plan, memory);
            await(storage.sync());

            // Entries below the retained log are already in the snapshot and are skipped.
            AppendPlan below = AppendPlan.from(5, List.of(entry(5, 1), entry(6, 1), entry(7, 1)), memory);
            assertFalse(below.requiresPersistence());
        } finally { close(storage, tempDir); }
        List<LogEntryData> expected = List.of(entry(7, 1), entry(8, 1), entry(9, 2), entry(10, 2));
        assertEntries(expected, memory);
        assertEntries(expected, recover(tempDir));
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
            try (var ignoredUntouched = DurableState.expectUnchanged(tempDir)) {
                assertThrows(java.util.concurrent.ExecutionException.class,
                        () -> await(damaged.appendEntries(List.of(entry(9, 9)))));
            }
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
            await(storage.replayLog());
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
                await(storage.replayLog());
                persist(storage, AppendPlan.from(2, List.of(entry(2, 2), entry(3, 2)), memory), memory);
            } finally { close(storage, dir); }
            assertEntries(List.of(entry(1, 1), entry(2, 2), entry(3, 2)), recover(dir));
        }
    }

    @Test void rawAppendOfAnAlreadyPresentIndexIsRefusedSoNoDuplicateSurvivesReopen() throws Exception {
        for (int count : List.of(2, 3)) {
            Path dir = tempDir.resolve("raw-" + count);
            List<LogEntryData> expected = new ArrayList<>();
            for (int i = 1; i <= count; i++) expected.add(entry(i, 1));
            FileRaftStorage storage = open(dir);
            try {
                await(storage.appendEntries(expected));
                // Re-sending an existing index without a preceding truncation is a node bug.
                try (var ignoredUntouched = DurableState.expectUnchanged(tempDir)) {
                    assertRejected(storage.appendEntries(List.of(entry(count - 1, 2))),
                            WriteRejectionReason.INDEX_NOT_CONTIGUOUS);
                }
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
