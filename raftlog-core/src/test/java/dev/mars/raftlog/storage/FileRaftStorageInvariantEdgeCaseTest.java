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
import dev.mars.raftlog.storage.RaftStorage.PersistentMeta;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32C;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Edge cases of the Raft invariant checks. The governing property is that the write
 * path and the replay path agree: anything the storage accepts must replay after a
 * restart, and anything replay would refuse must be refused at write time.
 */
class FileRaftStorageInvariantEdgeCaseTest {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(FileRaftStorageInvariantEdgeCaseTest.class);
    @TempDir Path dir;

    private static LogEntryData entry(long index, long term) {
        return new LogEntryData(index, term, new byte[]{(byte) index, (byte) term});
    }

    private static <T> T await(CompletableFuture<T> future) throws Exception {
        return future.get(10, TimeUnit.SECONDS);
    }

    private static Throwable failureOf(CompletableFuture<?> future) {
        return assertThrows(ExecutionException.class, () -> await(future)).getCause();
    }

    private static void assertRejected(CompletableFuture<?> future, WriteRejectionReason reason) {
        var rejected = assertInstanceOf(FileRaftStorage.WriteRejectedException.class, failureOf(future));
        assertEquals(reason, rejected.reason(), rejected.getMessage());
    }

    private static FileRaftStorage open(Path dir) throws Exception {
        FileRaftStorage storage = FileRaftStorage.unsafeWithoutFsyncForTesting(false);
        await(storage.open(dir));
        return storage;
    }

    private static List<LogEntryData> restartAndReplay(Path dir) throws Exception {
        FileRaftStorage storage = open(dir);
        try { return await(storage.replayLog()); }
        finally { await(storage.closeAsync()); }
    }

    private static void assertEntries(List<LogEntryData> expected, List<LogEntryData> actual) {
        assertEquals(expected.stream().map(e -> e.index() + "@" + e.term()).toList(),
                actual.stream().map(e -> e.index() + "@" + e.term()).toList());
    }

    // ------------------------------------------------------------------
    // 1. The last term must survive a suffix truncation
    // ------------------------------------------------------------------

    @Test void termRegressionAgainstRetainedEntryIsRefusedAfterSuffixTruncation() throws Exception {
        FileRaftStorage storage = open(dir);
        try {
            await(storage.appendEntries(List.of(entry(1, 5), entry(2, 5))));
            await(storage.truncateSuffix(2));
            // Entry 1 at term 5 is retained, so entry 2 cannot be at term 3.
            try (var ignoredUntouched = DurableState.expectUnchanged(dir)) {
                assertRejected(storage.appendEntries(List.of(entry(2, 3))), WriteRejectionReason.TERM_REGRESSION);
            }
            await(storage.appendEntries(List.of(entry(2, 5), entry(3, 6))));
        } finally { await(storage.closeAsync()); }
        assertEntries(List.of(entry(1, 5), entry(2, 5), entry(3, 6)), restartAndReplay(dir));
    }

    @Test void truncationAcrossSeveralTermsRestoresTheTermOfTheNewLastEntry() throws Exception {
        FileRaftStorage storage = open(dir);
        try {
            await(storage.appendEntries(List.of(entry(1, 1), entry(2, 2), entry(3, 2), entry(4, 4), entry(5, 7))));
            await(storage.truncateSuffix(4));                       // last retained is 3@2
            assertRejected(storage.appendEntries(List.of(entry(4, 1))), WriteRejectionReason.TERM_REGRESSION);
            await(storage.appendEntries(List.of(entry(4, 2))));     // equal term is fine
            await(storage.truncateSuffix(2));                       // last retained is 1@1
            assertRejected(storage.appendEntries(List.of(entry(2, 0))), WriteRejectionReason.TERM_REGRESSION);
            await(storage.appendEntries(List.of(entry(2, 1))));
        } finally { await(storage.closeAsync()); }
        assertEntries(List.of(entry(1, 1), entry(2, 1)), restartAndReplay(dir));
    }

    @Test void truncatingTheWholeLogLiftsTheTermConstraint() throws Exception {
        FileRaftStorage storage = open(dir);
        try {
            await(storage.appendEntries(List.of(entry(1, 9), entry(2, 9))));
            await(storage.truncateSuffix(1));
            // Nothing is retained, so there is no preceding term to regress from.
            await(storage.appendEntries(List.of(entry(1, 2))));
        } finally { await(storage.closeAsync()); }
        assertEntries(List.of(entry(1, 2)), restartAndReplay(dir));
    }

    @Test void termConstraintSurvivesCompactionThatRetainsEntries() throws Exception {
        FileRaftStorage storage = open(dir);
        try {
            await(storage.appendEntries(List.of(entry(1, 1), entry(2, 3), entry(3, 3), entry(4, 6))));
            await(storage.truncatePrefix(2));                       // retained 3@3, 4@6
            await(storage.truncateSuffix(4));                       // retained 3@3
            assertRejected(storage.appendEntries(List.of(entry(4, 2))), WriteRejectionReason.TERM_REGRESSION);
            await(storage.appendEntries(List.of(entry(4, 3))));
        } finally { await(storage.closeAsync()); }
        assertEntries(List.of(entry(3, 3), entry(4, 3)), restartAndReplay(dir));
    }

    // ------------------------------------------------------------------
    // 2. A batch that fails part way must not leave a stale tail
    // ------------------------------------------------------------------

    /** Throws an unchecked exception from the Nth force, which verifyWrites performs per record. */
    private static final class UncheckedFailureOnForce extends CompactionIo {
        final AtomicInteger calls = new AtomicInteger();
        final int failOn;
        UncheckedFailureOnForce(int failOn) { this.failOn = failOn; }
        @Override void forceChannel(FileChannel channel) throws IOException {
            if (calls.incrementAndGet() == failOn) throw new IllegalStateException("Injected unchecked failure");
            super.forceChannel(channel);
        }
    }

    @Test void uncheckedFailureMidBatchLeavesTheTailUnknownSoARetryCannotDuplicate() throws Exception {
        RaftStorageConfig config = RaftStorageConfig.builder().verifyWrites(true).build();
        FileRaftStorage storage = new FileRaftStorage(config, new UncheckedFailureOnForce(2));
        await(storage.open(dir));
        List<LogEntryData> batch = List.of(entry(1, 1), entry(2, 1));
        try {
            assertInstanceOf(IllegalStateException.class, failureOf(storage.appendEntries(batch)));
            // Entry 1 is on disk. A blind retry must be refused, not written a second time.
            try (var ignoredUntouched = DurableState.expectUnchanged(dir)) {
                assertRejected(storage.appendEntries(batch), WriteRejectionReason.LOG_STATE_UNKNOWN);
            }
            List<LogEntryData> onDisk = await(storage.replayLog());
            await(storage.appendEntries(List.of(entry(onDisk.size() + 1L, 1))));
        } finally { await(storage.closeAsync()); }
        List<LogEntryData> replayed = restartAndReplay(dir);
        for (int i = 0; i < replayed.size(); i++) assertEquals(i + 1, replayed.get(i).index());
    }

    /** Tears the Nth WAL record: writes half of it, then fails like a device error would. */
    private static final class TornRecordWrite extends CompactionIo {
        final AtomicInteger calls = new AtomicInteger();
        final int failOn;
        TornRecordWrite(int failOn) { this.failOn = failOn; }
        @Override void writeRecord(FileChannel channel, ByteBuffer record) throws IOException {
            if (calls.incrementAndGet() != failOn) { super.writeRecord(channel, record); return; }
            ByteBuffer half = record.duplicate();
            half.limit(half.position() + record.remaining() / 2);
            while (half.hasRemaining()) channel.write(half);
            throw new IOException("Injected device error after a partial write");
        }
    }

    @Test void ioFailureMidBatchTearsARecordAndReplayRepairsItBeforeTheNextWrite() throws Exception {
        FileRaftStorage storage = FileRaftStorage.unsafeWithoutFsyncForTesting(
                RaftStorageConfig.builder().build(), new TornRecordWrite(2));
        await(storage.open(dir));
        try {
            Throwable cause = failureOf(storage.appendEntries(List.of(entry(1, 1), entry(2, 1))));
            assertInstanceOf(FileRaftStorage.StorageException.class, cause);
            assertInstanceOf(IOException.class, cause.getCause());
            // Entry 1 is whole and entry 2 is torn. Neither a retry nor a continuation may
            // be written behind a tail the storage has not re-read.
            try (var ignoredUntouched = DurableState.expectUnchanged(dir)) {
                assertRejected(storage.appendEntries(List.of(entry(1, 1), entry(2, 1))), WriteRejectionReason.LOG_STATE_UNKNOWN);
            }
            try (var ignoredUntouched = DurableState.expectUnchanged(dir)) {
                assertRejected(storage.appendEntries(List.of(entry(2, 1))), WriteRejectionReason.LOG_STATE_UNKNOWN);
            }
            try (var ignoredUntouched = DurableState.expectUnchanged(dir)) {
                assertRejected(storage.truncateSuffix(1), WriteRejectionReason.LOG_STATE_UNKNOWN);
            }
            assertEntries(List.of(entry(1, 1)), await(storage.replayLog()));
            await(storage.appendEntries(List.of(entry(2, 1))));
        } finally { await(storage.closeAsync()); }
        assertEntries(List.of(entry(1, 1), entry(2, 1)), restartAndReplay(dir));
    }

    /** Reports ample space until told otherwise. */
    private static final class FillingDisk extends CompactionIo {
        volatile boolean full;
        @Override long usableSpace(Path directory) { return full ? 1024 : Long.MAX_VALUE; }
    }

    @Test void diskSpaceIsCheckedBeforeTheFirstRecordOfABatchIsWritten() throws Exception {
        FillingDisk disk = new FillingDisk();
        FileRaftStorage storage = FileRaftStorage.unsafeWithoutFsyncForTesting(RaftStorageConfig.builder().build(), disk);
        await(storage.open(dir));
        try {
            disk.full = true;
            LogEntryData large = new LogEntryData(2, 1, new byte[2 * 1024 * 1024]);
            try (var ignoredUntouched = DurableState.expectUnchanged(dir)) {
                assertRejected(storage.appendEntries(List.of(entry(1, 1), large)),
                        WriteRejectionReason.INSUFFICIENT_DISK_SPACE);
            }
            assertEquals(0, Files.size(dir.resolve("raft.log")), "a refused batch must write nothing");
            // Nothing was written, so the tail is still known and a small append proceeds.
            await(storage.appendEntries(List.of(entry(1, 1))));
        } finally { await(storage.closeAsync()); }
        assertEntries(List.of(entry(1, 1)), restartAndReplay(dir));
    }

    // ------------------------------------------------------------------
    // 3. Unreadable metadata must not disable the term check
    // ------------------------------------------------------------------

    @Test void unreadableMetadataRefusesUpdatesAndLeavesTheFileUntouched() throws Exception {
        FileRaftStorage storage = open(dir);
        try { await(storage.updateMetadata(9, Optional.of("a"))); }
        finally { await(storage.closeAsync()); }

        Path meta = dir.resolve("meta.dat");
        byte[] damaged = Files.readAllBytes(meta);
        damaged[damaged.length - 1] ^= 1;
        Files.write(meta, damaged);

        storage = open(dir);
        try {
            try (var ignoredUntouched = DurableState.expectUnchanged(dir)) {
                assertRejected(storage.updateMetadata(2, Optional.of("b")), WriteRejectionReason.METADATA_UNREADABLE);
            }
            try (var ignoredUntouched = DurableState.expectUnchanged(dir)) {
                assertRejected(storage.updateMetadata(50, Optional.empty()), WriteRejectionReason.METADATA_UNREADABLE);
            }
            assertInstanceOf(FileRaftStorage.StorageException.class, failureOf(storage.loadMetadata()));
            assertArrayEquals(damaged, Files.readAllBytes(meta));
        } finally { await(storage.closeAsync()); }

        // An operator who has decided the node may start from scratch removes the file.
        Files.delete(meta);
        storage = open(dir);
        try {
            await(storage.updateMetadata(2, Optional.of("b")));
            assertEquals(new PersistentMeta(2, Optional.of("b")), await(storage.loadMetadata()));
        } finally { await(storage.closeAsync()); }
    }

    @Test void truncatedMetadataIsUnreadableToo() throws Exception {
        Files.write(dir.resolve("meta.dat"), new byte[]{1, 2, 3});
        FileRaftStorage storage = open(dir);
        try {
            try (var ignoredUntouched = DurableState.expectUnchanged(dir)) {
                assertRejected(storage.updateMetadata(1, Optional.empty()), WriteRejectionReason.METADATA_UNREADABLE);
            }
        } finally { await(storage.closeAsync()); }
    }

    // ------------------------------------------------------------------
    // 4. No arithmetic overflow at the top of the index space
    // ------------------------------------------------------------------

    @Test void lastPossibleIndexCanBeTruncatedAndRewritten() throws Exception {
        FileRaftStorage storage = open(dir);
        try {
            await(storage.truncatePrefix(Long.MAX_VALUE - 1));
            await(storage.appendEntries(List.of(entry(Long.MAX_VALUE, 1))));
            // The log is full: nothing can follow the last index.
            try (var ignoredUntouched = DurableState.expectUnchanged(dir)) {
                assertRejected(storage.appendEntries(List.of(entry(Long.MAX_VALUE, 1))),
                        WriteRejectionReason.INDEX_NOT_CONTIGUOUS);
            }
            await(storage.truncateSuffix(Long.MAX_VALUE));
            await(storage.appendEntries(List.of(entry(Long.MAX_VALUE, 2))));
        } finally { await(storage.closeAsync()); }
        assertEntries(List.of(entry(Long.MAX_VALUE, 2)), restartAndReplay(dir));
    }

    @Test void batchCannotWrapAroundTheIndexSpace() throws Exception {
        FileRaftStorage storage = open(dir);
        try {
            await(storage.truncatePrefix(Long.MAX_VALUE - 1));
            // MAX + 1 wraps to MIN. That is not the next index, it is overflow.
            try (var ignoredUntouched = DurableState.expectUnchanged(dir)) {
                assertRejected(storage.appendEntries(List.of(entry(Long.MAX_VALUE, 1), entry(Long.MIN_VALUE, 1))),
                        WriteRejectionReason.INDEX_NOT_CONTIGUOUS);
            }
            assertEquals(31, Files.size(dir.resolve("raft.log")), "only the prefix marker may be present");
        } finally { await(storage.closeAsync()); }
        assertEntries(List.of(), restartAndReplay(dir));
    }

    @Test void logCompactedThroughTheLastIndexAcceptsNothingMore() throws Exception {
        FileRaftStorage storage = open(dir);
        try {
            await(storage.truncatePrefix(Long.MAX_VALUE));
            try (var ignoredUntouched = DurableState.expectUnchanged(dir)) {
                assertRejected(storage.appendEntries(List.of(entry(1, 1))), WriteRejectionReason.INDEX_NOT_CONTIGUOUS);
            }
            try (var ignoredUntouched = DurableState.expectUnchanged(dir)) {
                assertRejected(storage.appendEntries(List.of(entry(Long.MAX_VALUE, 1))),
                        WriteRejectionReason.INDEX_NOT_CONTIGUOUS);
            }
            try (var ignoredUntouched = DurableState.expectUnchanged(dir)) {
                assertRejected(storage.truncateSuffix(Long.MAX_VALUE), WriteRejectionReason.INVALID_TRUNCATION);
            }
        } finally { await(storage.closeAsync()); }
        FileRaftStorage reopened = open(dir);
        try {
            assertEntries(List.of(), await(reopened.replayLog()));
            try (var ignoredUntouched = DurableState.expectUnchanged(dir)) {
                assertRejected(reopened.appendEntries(List.of(entry(1, 1))), WriteRejectionReason.INDEX_NOT_CONTIGUOUS);
            }
        } finally { await(reopened.closeAsync()); }
    }

    // ------------------------------------------------------------------
    // 5. Format version
    // ------------------------------------------------------------------

    private static byte[] rawRecord(short version, byte type, long index, long term) {
        ByteBuffer buf = ByteBuffer.allocate(27 + 4);
        buf.putInt(0x52414654).putShort(version).put(type).putLong(index).putLong(term).putInt(0);
        CRC32C crc = new CRC32C();
        crc.update(buf.array(), 0, 27);
        return buf.putInt((int) crc.getValue()).array();
    }

    @Test void prefixMarkerIsWrittenAsFormatVersionTwo() throws Exception {
        FileRaftStorage storage = open(dir);
        try {
            await(storage.appendEntries(List.of(entry(1, 1), entry(2, 1))));
            await(storage.truncatePrefix(1));
        } finally { await(storage.closeAsync()); }
        ByteBuffer head = ByteBuffer.wrap(Files.readAllBytes(dir.resolve("raft.log")));
        assertEquals(0x52414654, head.getInt());
        assertEquals(2, head.getShort(), "the record type added in format 2 must declare format 2");
        assertEquals(3, head.get());
        assertEntries(List.of(entry(2, 1)), restartAndReplay(dir));
    }

    @Test void recordFromANewerFormatIsReportedAsUnsupportedNotAsCorruption() throws Exception {
        byte[] newer = rawRecord((short) 3, (byte) 2, 1, 1);
        Files.write(dir.resolve("raft.log"), newer);
        FileRaftStorage storage = open(dir);
        try {
            Throwable cause = failureOf(storage.replayLog());
            assertInstanceOf(FileRaftStorage.StorageException.class, cause);
            assertFalse(cause instanceof FileRaftStorage.CorruptLogException,
                    "a valid record from a newer build is not corruption: " + cause);
            assertTrue(cause.getMessage().contains("format version 3"), cause.getMessage());
            // Appending behind records this build cannot interpret must be impossible.
            assertInstanceOf(FileRaftStorage.StorageException.class,
                    failureOf(storage.appendEntries(List.of(entry(1, 1)))));
        } finally { await(storage.closeAsync()); }
        assertArrayEquals(newer, Files.readAllBytes(dir.resolve("raft.log")));
    }

    // ------------------------------------------------------------------
    // 6. The compaction boundary is available to the node
    // ------------------------------------------------------------------

    @Test void compactionBoundaryIsReportedAndSurvivesRestart() throws Exception {
        FileRaftStorage storage = open(dir);
        try {
            assertEquals(0L, await(storage.compactionBoundary()));
            await(storage.appendEntries(List.of(entry(1, 1), entry(2, 1), entry(3, 1))));
            await(storage.truncatePrefix(3));
            assertEquals(3L, await(storage.compactionBoundary()));
        } finally { await(storage.closeAsync()); }
        storage = open(dir);
        try {
            try (var ignoredUntouched = DurableState.expectUnchanged(dir)) {
                assertRejected(storage.compactionBoundary(), WriteRejectionReason.LOG_STATE_UNKNOWN);
            }
            List<LogEntryData> memory = new ArrayList<>(await(storage.replayLog()));
            long boundary = await(storage.compactionBoundary());
            assertEquals(3L, boundary);

            // A leader resends from below the boundary. The retained log is empty, so only
            // the boundary can tell the plan that 2 and 3 are already in the snapshot.
            AppendPlan plan = AppendPlan.from(2, List.of(entry(2, 1), entry(3, 1), entry(4, 1), entry(5, 1)),
                    memory, boundary);
            assertFalse(plan.requiresTruncation());
            assertEntries(List.of(entry(4, 1), entry(5, 1)), plan.entriesToAppend());
            await(storage.appendEntries(plan.entriesToAppend()));
        } finally { await(storage.closeAsync()); }
        assertEntries(List.of(entry(4, 1), entry(5, 1)), restartAndReplay(dir));
    }

    // ------------------------------------------------------------------
    // 7. Invalid arguments fail the future, they do not throw
    // ------------------------------------------------------------------

    @Test void nullEntryFailsTheFutureWithoutThrowingOrWriting() throws Exception {
        FileRaftStorage storage = open(dir);
        try {
            List<LogEntryData> withNull = new ArrayList<>();
            withNull.add(entry(1, 1));
            withNull.add(null);
            CompletableFuture<Void> result = assertDoesNotThrow(() -> storage.appendEntries(withNull));
            assertInstanceOf(IllegalArgumentException.class, failureOf(result));
            assertEquals(0, Files.size(dir.resolve("raft.log")));
            await(storage.appendEntries(List.of(entry(1, 1))));
        } finally { await(storage.closeAsync()); }
    }

    // ------------------------------------------------------------------
    // The governing property, checked against a reference model
    // ------------------------------------------------------------------

    /** What a conforming Raft storage must hold after each operation. */
    private static final class Model {
        final List<LogEntryData> log = new ArrayList<>();
        long boundary;
        boolean metaKnown;
        long term;
        Optional<String> vote = Optional.empty();

        long lastIndex() { return log.isEmpty() ? boundary : log.getLast().index(); }

        WriteRejectionReason append(List<LogEntryData> batch) {
            long last = lastIndex();
            if (batch.getFirst().index() < 1 || last == Long.MAX_VALUE || batch.getFirst().index() != last + 1) {
                return WriteRejectionReason.INDEX_NOT_CONTIGUOUS;
            }
            long previousTerm = log.isEmpty() ? -1 : log.getLast().term();
            long expected = batch.getFirst().index();
            for (LogEntryData e : batch) {
                if (e.index() != expected++) return WriteRejectionReason.INDEX_NOT_CONTIGUOUS;
                if (e.term() < 0 || e.term() < previousTerm) return WriteRejectionReason.TERM_REGRESSION;
                previousTerm = e.term();
            }
            log.addAll(batch);
            return null;
        }

        WriteRejectionReason truncateSuffix(long from) {
            if (from < 1 || from <= boundary || from - 1 > lastIndex()) return WriteRejectionReason.INVALID_TRUNCATION;
            log.removeIf(e -> e.index() >= from);
            return null;
        }

        void truncatePrefix(long to) {
            if (to <= 0) return;
            log.removeIf(e -> e.index() <= to);
            boundary = Math.max(boundary, to);
        }

        WriteRejectionReason updateMetadata(long newTerm, Optional<String> newVote) {
            if (newTerm < 0) return WriteRejectionReason.TERM_REGRESSION;
            if (metaKnown) {
                if (newTerm < term) return WriteRejectionReason.TERM_REGRESSION;
                if (newTerm == term && vote.isPresent() && !vote.equals(newVote)) return WriteRejectionReason.VOTE_CHANGED;
            }
            metaKnown = true;
            term = newTerm;
            vote = newVote;
            return null;
        }
    }

    private static void assertOutcome(String what, WriteRejectionReason expected, CompletableFuture<?> actual) {
        if (expected == null) {
            assertDoesNotThrow(() -> await(actual), what + " must be accepted");
        } else {
            Throwable cause = assertThrows(ExecutionException.class, () -> await(actual),
                    what + " must be refused with " + expected).getCause();
            var rejected = assertInstanceOf(FileRaftStorage.WriteRejectedException.class, cause, what);
            assertEquals(expected, rejected.reason(), what + ": " + rejected.getMessage());
        }
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20,
            21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40})
    void writePathAndReplayPathAgreeWithTheModelAcrossRestarts(long seed) throws Exception {
        runAgainstModel(seed, dir);
    }

    /**
     * The same check over many more seeds, for hunting. Off by default; run with
     * {@code -Draftlog.model.soakSeeds=2000}.
     */
    @Test void soakWritePathAndReplayPathAgainstTheModel() throws Exception {
        int seeds = Integer.getInteger("raftlog.model.soakSeeds", 0);
        for (long seed = 1_000; seed < 1_000 + seeds; seed++) {
            Path seedDir = Files.createDirectory(dir.resolve("seed-" + seed));
            runAgainstModel(seed, seedDir);
        }
        // Said out loud, because a soak that was given no seeds passes instantly and proves nothing.
        LOG.info("MODEL SOAK: ran " + seeds + " seeds"
                + (seeds == 0 ? " (disabled; pass -Draftlog.model.soakSeeds=N as a Maven property)" : ""));
    }

    private static void runAgainstModel(long seed, Path dir) throws Exception {
        Random random = new Random(seed);
        Model model = new Model();
        FileRaftStorage storage = open(dir);
        try {
            for (int step = 0; step < 120; step++) {
                String at = "seed " + seed + " step " + step + ": ";
                long last = model.lastIndex();
                long lastTerm = model.log.isEmpty() ? 1 : model.log.getLast().term();
                switch (random.nextInt(10)) {
                    case 0, 1, 2, 3 -> {
                        // Mostly well-formed batches, perturbed often enough to probe every rule.
                        long first = last + 1 + perturb(random);
                        long term = Math.max(0, lastTerm + random.nextInt(3) - (random.nextInt(4) == 0 ? 2 : 0));
                        List<LogEntryData> batch = new ArrayList<>();
                        for (int i = 0, n = 1 + random.nextInt(4); i < n; i++) {
                            long index = first + i + (random.nextInt(12) == 0 ? 1 : 0);
                            long entryTerm = term + (random.nextInt(10) == 0 ? -1 : 0) + (i > 0 && random.nextBoolean() ? 1 : 0);
                            batch.add(entry(index, entryTerm));
                            term = Math.max(term, entryTerm);
                        }
                        assertOutcome(at + "append " + describe(batch), model.append(batch), storage.appendEntries(batch));
                    }
                    case 4, 5 -> {
                        long from = Math.max(-1, last - random.nextInt(4) + random.nextInt(3));
                        assertOutcome(at + "truncateSuffix(" + from + ")", model.truncateSuffix(from), storage.truncateSuffix(from));
                    }
                    case 6 -> {
                        long to = Math.max(0, last - random.nextInt(5) + random.nextInt(2));
                        model.truncatePrefix(to);
                        await(storage.truncatePrefix(to));
                        assertEquals(model.boundary, await(storage.compactionBoundary()), at + "boundary");
                    }
                    case 7 -> {
                        long newTerm = model.term + random.nextInt(3) - (random.nextInt(4) == 0 ? 1 : 0);
                        Optional<String> vote = random.nextBoolean() ? Optional.of("n" + random.nextInt(2)) : Optional.empty();
                        assertOutcome(at + "updateMetadata(" + newTerm + "," + vote + ")",
                                model.updateMetadata(newTerm, vote), storage.updateMetadata(newTerm, vote));
                    }
                    default -> {
                        // Restart: everything accepted so far must replay, and match the model.
                        await(storage.closeAsync());
                        storage = open(dir);
                        if (Files.size(dir.resolve("raft.log")) > 0) {
                            try (var ignoredUntouched = DurableState.expectUnchanged(dir)) {
                                assertRejected(storage.appendEntries(List.of(entry(last + 1, lastTerm))),
                                        WriteRejectionReason.LOG_STATE_UNKNOWN);
                            }
                        }
                        assertEntries(model.log, await(storage.replayLog()));
                        assertEquals(model.boundary, await(storage.compactionBoundary()), at + "boundary after restart");
                        if (model.metaKnown) {
                            assertEquals(new PersistentMeta(model.term, model.vote), await(storage.loadMetadata()), at);
                        }
                    }
                }
            }
            assertEntries(model.log, await(storage.replayLog()));
        } finally { await(storage.closeAsync()); }
        assertEntries(model.log, restartAndReplay(dir));
    }

    private static long perturb(Random random) {
        return switch (random.nextInt(8)) { case 0 -> -1; case 1 -> 1; case 2 -> -random.nextInt(5); default -> 0; };
    }

    private static String describe(List<LogEntryData> batch) {
        return batch.stream().map(e -> e.index() + "@" + e.term()).toList().toString();
    }
}
