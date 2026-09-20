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

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.CRC32C;

import static org.junit.jupiter.api.Assertions.*;

/**
 * One test for every refusal and failure path that line coverage showed no test had ever
 * executed. Each asserts three things: the failure the caller sees, what is on disk
 * afterwards, and what the instance will and will not do next.
 * <p>
 * Keep this list honest. Run {@code mvn -Pcoverage test} and check that no {@code throw},
 * {@code fence} or failed-future line in the storage is unexecuted before adding a new one.
 */
class FileRaftStorageFailurePathTest {
    @TempDir Path dir;

    // ------------------------------------------------------------------ helpers

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

    private static FileRaftStorage open(Path dir, CompactionIo io) throws Exception {
        FileRaftStorage storage = FileRaftStorage.unsafeWithoutFsyncForTesting(RaftStorageConfig.builder().build(), io);
        await(storage.open(dir));
        return storage;
    }

    private static void assertEntries(List<LogEntryData> expected, List<LogEntryData> actual) {
        assertEquals(expected.stream().map(e -> e.index() + "@" + e.term()).toList(),
                actual.stream().map(e -> e.index() + "@" + e.term()).toList());
    }

    private static byte[] record(int version, int type, long index, long term, byte[] payload) {
        ByteBuffer buf = ByteBuffer.allocate(27 + payload.length + 4);
        buf.putInt(0x52414654).putShort((short) version).put((byte) type).putLong(index).putLong(term)
                .putInt(payload.length).put(payload);
        CRC32C crc = new CRC32C();
        crc.update(buf.array(), 0, 27 + payload.length);
        return buf.putInt((int) crc.getValue()).array();
    }

    private static byte[] append(long index, long term) { return record(1, 2, index, term, new byte[]{1}); }
    private static byte[] truncate(long from) { return record(1, 1, from, 0, new byte[0]); }
    private static byte[] prefix(long boundary) { return record(2, 3, boundary, 0, new byte[0]); }

    private void writeWal(byte[]... records) throws IOException {
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        for (byte[] r : records) all.write(r);
        Files.write(dir.resolve("raft.log"), all.toByteArray());
    }

    /**
     * Replays a hand-built WAL that is intact at the record level but is not a valid Raft
     * log. It must be refused without being called corruption, the file must be left
     * alone, and nothing may be written behind it.
     */
    private void assertReplayRefusesMalformedLog(String expectedMessagePart) throws Exception {
        FileRaftStorage storage = open(dir);
        try {
            try (var ignoredUntouched = DurableState.expectUnchanged(dir)) {
                Throwable cause = failureOf(storage.replayLog());
                assertInstanceOf(FileRaftStorage.StorageException.class, cause);
                assertFalse(cause instanceof FileRaftStorage.CorruptLogException,
                        "every record is intact, so this is a malformed log, not disk corruption: " + cause);
                assertTrue(cause.getMessage().contains(expectedMessagePart), cause.getMessage());
                assertRejected(storage.appendEntries(List.of(entry(1, 1))), WriteRejectionReason.LOG_STATE_UNKNOWN);
                assertRejected(storage.truncateSuffix(1), WriteRejectionReason.LOG_STATE_UNKNOWN);
            }
        } finally { await(storage.closeAsync()); }
    }

    // ------------------------------------------------------------------ replay shape

    @Test void replayRefusesALogWithAGap() throws Exception {
        writeWal(append(1, 1), append(3, 1));
        assertReplayRefusesMalformedLog("not a contiguous Raft log");
    }

    @Test void replayRefusesADuplicateIndex() throws Exception {
        writeWal(append(1, 1), append(1, 1));
        assertReplayRefusesMalformedLog("not a contiguous Raft log");
    }

    @Test void replayRefusesATermRegression() throws Exception {
        writeWal(append(1, 5), append(2, 3));
        assertReplayRefusesMalformedLog("term regression");
    }

    @Test void replayRefusesAFirstEntryThatDoesNotFollowTheCompactionBoundary() throws Exception {
        writeWal(prefix(10), append(12, 1));
        assertReplayRefusesMalformedLog("compacted through index 10");
    }

    @Test void replayRefusesAPrefixMarkerThatIsNotTheFirstRecord() throws Exception {
        writeWal(append(1, 1), prefix(1));
        assertReplayRefusesMalformedLog("only valid as the first record");
    }

    @Test void historicalTruncateRecordBelowOneEmptiesTheLogAndTheNextAppendStartsAtOne() throws Exception {
        // Builds before the invariant checks accepted truncateSuffix(0) and wrote this record.
        writeWal(append(1, 1), append(2, 1), truncate(0));
        FileRaftStorage storage = open(dir);
        try {
            assertEntries(List.of(), await(storage.replayLog()));
            assertRejected(storage.appendEntries(List.of(entry(3, 1))), WriteRejectionReason.INDEX_NOT_CONTIGUOUS);
            await(storage.appendEntries(List.of(entry(1, 2))));
        } finally { await(storage.closeAsync()); }
        assertEntries(List.of(entry(1, 2)), DurableState.replayAfterRestart(dir));
    }

    @Test void historicalTruncateRecordBelowTheCompactionBoundaryLeavesTheTailAtTheBoundary() throws Exception {
        writeWal(prefix(10), append(11, 1), truncate(4));
        FileRaftStorage storage = open(dir);
        try {
            assertEntries(List.of(), await(storage.replayLog()));
            assertEquals(10L, await(storage.compactionBoundary()));
            assertRejected(storage.appendEntries(List.of(entry(4, 1))), WriteRejectionReason.INDEX_NOT_CONTIGUOUS);
            await(storage.appendEntries(List.of(entry(11, 2))));
        } finally { await(storage.closeAsync()); }
        assertEntries(List.of(entry(11, 2)), DurableState.replayAfterRestart(dir));
    }

    // ------------------------------------------------------------------ torn tail classification

    @Test void fragmentOfANewerFormatAtTheTailIsReportedNotTruncated() throws Exception {
        byte[] newer = record(3, 2, 2, 1, new byte[]{1, 2, 3, 4});
        ByteArrayOutputStream wal = new ByteArrayOutputStream();
        wal.write(append(1, 1));
        wal.write(newer, 0, newer.length - 3);          // header complete, record cut short
        Files.write(dir.resolve("raft.log"), wal.toByteArray());
        FileRaftStorage storage = open(dir);
        try (var ignoredUntouched = DurableState.expectUnchanged(dir)) {
            // This build cannot tell how long a format-3 record is, so it must not guess.
            assertInstanceOf(FileRaftStorage.CorruptLogException.class, failureOf(storage.replayLog()));
        } finally { await(storage.closeAsync()); }
    }

    @Test void fragmentWithAnUnknownTypeAtTheTailIsReportedNotTruncated() throws Exception {
        byte[] unknown = record(1, 9, 2, 1, new byte[]{1, 2, 3, 4});
        ByteArrayOutputStream wal = new ByteArrayOutputStream();
        wal.write(append(1, 1));
        wal.write(unknown, 0, unknown.length - 3);
        Files.write(dir.resolve("raft.log"), wal.toByteArray());
        FileRaftStorage storage = open(dir);
        try (var ignoredUntouched = DurableState.expectUnchanged(dir)) {
            assertInstanceOf(FileRaftStorage.CorruptLogException.class, failureOf(storage.replayLog()));
        } finally { await(storage.closeAsync()); }
    }

    // ------------------------------------------------------------------ format

    @Test void compactingAWalFromANewerFormatIsUnsupportedLeavesItAloneAndFences() throws Exception {
        writeWal(append(1, 1), record(3, 2, 2, 1, new byte[0]));
        FileRaftStorage storage = open(dir);
        try (var ignoredUntouched = DurableState.expectUnchanged(dir)) {
            var unsupported = assertInstanceOf(FileRaftStorage.UnsupportedFormatException.class,
                    failureOf(storage.truncatePrefix(1)));
            assertEquals(3, unsupported.foundVersion());
            assertEquals(2, unsupported.supportedVersion());
            assertSame(unsupported, failureOf(storage.appendEntries(List.of(entry(3, 1)))), "the instance must be fenced");
            assertSame(unsupported, failureOf(storage.replayLog()));
        } finally { await(storage.closeAsync()); }
    }

    // ------------------------------------------------------------------ open

    @Test void openRefusesADirectoryThatHoldsOnlyAnUnpublishedRewrite() throws Exception {
        // raft.log.tmp without raft.log means a compaction died at a point this build never
        // reaches. The rewrite may be the only copy of the log, so it must be preserved.
        Files.write(dir.resolve("raft.log.tmp"), append(1, 1));
        FileRaftStorage storage = FileRaftStorage.unsafeWithoutFsyncForTesting(false);
        try (var ignoredUntouched = DurableState.expectUnchanged(dir)) {
            Throwable cause = failureOf(storage.open(dir));
            assertInstanceOf(FileRaftStorage.StorageException.class, cause);
            assertTrue(cause.getCause().getMessage().contains("Unpublished rewrite"), String.valueOf(cause.getCause()));
            assertFalse(Files.exists(dir.resolve("raft.log")), "open must not invent an empty log");
        } finally { await(storage.closeAsync()); }
        // The failed open released the lock: another attempt gets the same answer, not a lock error.
        FileRaftStorage again = FileRaftStorage.unsafeWithoutFsyncForTesting(false);
        try {
            assertTrue(failureOf(again.open(dir)).getCause().getMessage().contains("Unpublished rewrite"));
        } finally { await(again.closeAsync()); }
    }

    @Test void openingAnotherDirectoryOnAnOpenInstanceIsRefusedWithoutCreatingIt() throws Exception {
        Path first = dir.resolve("first");
        Path second = dir.resolve("second");
        FileRaftStorage storage = open(first);
        try {
            await(storage.appendEntries(List.of(entry(1, 1))));
            try (var ignoredUntouched = DurableState.expectUnchanged(dir)) {
                Throwable cause = failureOf(storage.open(second));
                assertInstanceOf(FileRaftStorage.StorageException.class, cause);
                assertTrue(cause.getMessage().contains("already opening or open"), cause.getMessage());
            }
            assertFalse(Files.exists(second), "a refused open must not create the directory");
            assertSame(storage.open(first), storage.open(first), "opening the same directory again is idempotent");
            await(storage.appendEntries(List.of(entry(2, 1))));
        } finally { await(storage.closeAsync()); }
        assertEntries(List.of(entry(1, 1), entry(2, 1)), DurableState.replayAfterRestart(first));
    }

    /** Child process: holds the directory open until its stdin closes. */
    public static void main(String[] args) throws Exception {
        FileRaftStorage holder = new FileRaftStorage(RaftStorageConfig.builder().build());
        holder.open(Path.of(args[0])).get(30, TimeUnit.SECONDS);
        holder.appendEntries(List.of(entry(1, 1))).get(30, TimeUnit.SECONDS);
        holder.updateMetadata(4, Optional.of("holder")).get(30, TimeUnit.SECONDS);
        holder.sync().get(30, TimeUnit.SECONDS);
        System.out.println("HOLDING");
        System.out.flush();
        while (System.in.read() >= 0) { /* until the parent closes the pipe */ }
        holder.close();
    }

    @Test void lockHeldByAnotherProcessRefusesOpenAndLeavesTheDirectoryUntouched() throws Exception {
        Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                FileRaftStorageFailurePathTest.class.getName(), dir.toString())
                .redirectErrorStream(true).start();
        try {
            CompletableFuture<Boolean> holding = CompletableFuture.supplyAsync(() -> {
                try (BufferedReader out = new BufferedReader(new InputStreamReader(child.getInputStream()))) {
                    for (String line; (line = out.readLine()) != null; ) if (line.equals("HOLDING")) return true;
                    return false;
                } catch (IOException e) { return false; }
            });
            assertTrue(holding.get(60, TimeUnit.SECONDS), "child process must take the directory lock");

            FileRaftStorage intruder = new FileRaftStorage(RaftStorageConfig.builder().build());
            try (var ignoredUntouched = DurableState.expectUnchanged(dir)) {
                Throwable cause = failureOf(intruder.open(dir));
                assertInstanceOf(FileRaftStorage.StorageException.class, cause);
                assertTrue(cause.getMessage().contains("Another process"), cause.getMessage());
                assertRejectedAsNotOpen(intruder.appendEntries(List.of(entry(2, 1))));
                assertRejectedAsNotOpen(intruder.updateMetadata(9, Optional.of("intruder")));
            } finally { await(intruder.closeAsync()); }
        } finally {
            child.getOutputStream().close();
            if (!child.waitFor(20, TimeUnit.SECONDS)) { child.destroyForcibly(); child.waitFor(10, TimeUnit.SECONDS); }
        }
        // The holder's state survived the intrusion attempt.
        assertEntries(List.of(entry(1, 1)), DurableState.replayAfterRestart(dir));
        FileRaftStorage after = open(dir);
        try { assertEquals(new PersistentMeta(4, Optional.of("holder")), await(after.loadMetadata())); }
        finally { await(after.closeAsync()); }
    }

    private static void assertRejectedAsNotOpen(CompletableFuture<?> future) {
        Throwable cause = failureOf(future);
        assertInstanceOf(FileRaftStorage.StorageException.class, cause);
        assertTrue(cause.getMessage().startsWith("Storage is not open"), cause.getMessage());
    }

    // ------------------------------------------------------------------ close

    /** Blocks the first force until released, optionally failing it. */
    private static final class GatedForce extends CompactionIo {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final IOException failure;
        GatedForce(IOException failure) { this.failure = failure; }
        @Override void forceChannel(FileChannel channel) throws IOException {
            entered.countDown();
            try { release.await(); } catch (InterruptedException e) { throw new IOException(e); }
            if (failure != null) throw failure;
            super.forceChannel(channel);
        }
    }

    @Test void closeCalledOnTheWalExecutorThreadDoesNotDeadlock() throws Exception {
        GatedForce io = new GatedForce(null);
        FileRaftStorage storage = new FileRaftStorage(RaftStorageConfig.builder().build(), io);
        await(storage.open(dir));
        await(storage.appendEntries(List.of(entry(1, 1))));

        CompletableFuture<Void> sync = storage.sync();
        assertTrue(io.entered.await(10, TimeUnit.SECONDS));
        AtomicReference<String> callbackThread = new AtomicReference<>();
        // Registered while sync is still running, so the callback runs on the executor thread.
        CompletableFuture<Void> closedInCallback = sync.thenRun(() -> {
            callbackThread.set(Thread.currentThread().getName());
            storage.close();
        });
        io.release.countDown();

        await(closedInCallback);
        assertEquals("wal-executor", callbackThread.get());
        await(storage.closeAsync());
        assertEntries(List.of(entry(1, 1)), DurableState.replayAfterRestart(dir));
    }

    /** Really releases the resource, then reports a failure, so nothing leaks into other tests. */
    private static final class FailingRelease extends CompactionIo {
        final AtomicInteger channelCloses = new AtomicInteger();
        final int failChannelCloseOn;
        final boolean failLockRelease;
        final RuntimeException unchecked;
        FailingRelease(int failChannelCloseOn, boolean failLockRelease, RuntimeException unchecked) {
            this.failChannelCloseOn = failChannelCloseOn;
            this.failLockRelease = failLockRelease;
            this.unchecked = unchecked;
        }
        @Override void closeChannel(FileChannel channel) throws IOException {
            super.closeChannel(channel);
            if (channelCloses.incrementAndGet() == failChannelCloseOn) {
                if (unchecked != null) throw unchecked;
                throw new IOException("Injected close failure");
            }
        }
        @Override void releaseLock(FileLock lock) throws IOException {
            super.releaseLock(lock);
            if (failLockRelease) throw new IOException("Injected lock release failure");
        }
    }

    private void assertCloseReportsReleaseFailure(FailingRelease io) throws Exception {
        FileRaftStorage storage = open(dir, io);
        await(storage.appendEntries(List.of(entry(1, 1))));
        Throwable cause = failureOf(storage.closeAsync());
        assertInstanceOf(FileRaftStorage.StorageException.class, cause);
        assertTrue(cause.getMessage().contains("resource-release failures"), cause.getMessage());
        // The blocking form reports the same failure instead of swallowing it.
        assertSame(cause, assertThrows(FileRaftStorage.StorageException.class, storage::close));
        assertEntries(List.of(entry(1, 1)), DurableState.replayAfterRestart(dir));
    }

    @Test void failureToCloseTheLogChannelIsReportedByBothFormsOfClose() throws Exception {
        assertCloseReportsReleaseFailure(new FailingRelease(1, false, null));
    }

    @Test void failureToCloseTheLockChannelIsReported() throws Exception {
        assertCloseReportsReleaseFailure(new FailingRelease(2, false, null));
    }

    @Test void failureToReleaseTheLockIsReported() throws Exception {
        assertCloseReportsReleaseFailure(new FailingRelease(0, true, null));
    }

    @Test void uncheckedFailureWhileClosingIsReportedNotLost() throws Exception {
        IllegalStateException injected = new IllegalStateException("Injected unchecked close failure");
        FileRaftStorage storage = open(dir, new FailingRelease(1, false, injected));
        assertSame(injected, failureOf(storage.closeAsync()));
        var thrown = assertThrows(FileRaftStorage.StorageException.class, storage::close);
        assertSame(injected, thrown.getCause());
    }

    // ------------------------------------------------------------------ metadata

    @Test void metadataUpdateThatCannotCreateItsStagingFileFailsWithoutFencingOrLosingTheBaseline() throws Exception {
        FileRaftStorage storage = open(dir);
        try {
            await(storage.updateMetadata(5, Optional.of("a")));
            Files.createDirectory(dir.resolve("meta.dat.tmp"));         // the staging path is unusable
            try (var ignoredUntouched = DurableState.expectUnchanged(dir)) {
                Throwable cause = failureOf(storage.updateMetadata(6, Optional.of("b")));
                assertInstanceOf(FileRaftStorage.StorageException.class, cause);
                assertEquals("Failed to update metadata", cause.getMessage());
                assertInstanceOf(IOException.class, cause.getCause());
                // The failed update must not have moved the baseline forward.
                assertRejected(storage.updateMetadata(4, Optional.empty()), WriteRejectionReason.TERM_REGRESSION);
                assertRejected(storage.updateMetadata(5, Optional.of("b")), WriteRejectionReason.VOTE_CHANGED);
            }
            assertEquals(new PersistentMeta(5, Optional.of("a")), await(storage.loadMetadata()));
            Files.delete(dir.resolve("meta.dat.tmp"));
            await(storage.updateMetadata(6, Optional.of("b")));          // not fenced: it recovers
        } finally { await(storage.closeAsync()); }
        FileRaftStorage reopened = open(dir);
        try { assertEquals(new PersistentMeta(6, Optional.of("b")), await(reopened.loadMetadata())); }
        finally { await(reopened.closeAsync()); }
    }

    @Test void metadataPathThatCannotBeReadAtAllIsUnreadableAndLoadReportsTheIoFailure() throws Exception {
        Files.createDirectory(dir.resolve("meta.dat"));
        FileRaftStorage storage = open(dir);
        try (var ignoredUntouched = DurableState.expectUnchanged(dir)) {
            assertRejected(storage.updateMetadata(1, Optional.empty()), WriteRejectionReason.METADATA_UNREADABLE);
            Throwable cause = failureOf(storage.loadMetadata());
            assertInstanceOf(FileRaftStorage.StorageException.class, cause);
            assertEquals("Failed to load metadata", cause.getMessage());
            assertInstanceOf(IOException.class, cause.getCause());
        } finally { await(storage.closeAsync()); }
    }

    // ------------------------------------------------------------------ suffix truncation failures

    /** Fails the Nth WAL record write, after writing half of it, with a checked or unchecked failure. */
    private static final class FailingRecordWrite extends CompactionIo {
        final AtomicInteger calls = new AtomicInteger();
        final int failOn;
        final RuntimeException unchecked;
        FailingRecordWrite(int failOn, RuntimeException unchecked) { this.failOn = failOn; this.unchecked = unchecked; }
        @Override void writeRecord(FileChannel channel, ByteBuffer record) throws IOException {
            if (calls.incrementAndGet() != failOn) { super.writeRecord(channel, record); return; }
            ByteBuffer half = record.duplicate();
            half.limit(half.position() + record.remaining() / 2);
            while (half.hasRemaining()) channel.write(half);
            if (unchecked != null) throw unchecked;
            throw new IOException("Injected device error after a partial write");
        }
    }

    private void assertFailedTruncationLeavesTheTailUnknown(RuntimeException unchecked) throws Exception {
        FileRaftStorage storage = open(dir, new FailingRecordWrite(3, unchecked));
        try {
            await(storage.appendEntries(List.of(entry(1, 1), entry(2, 1))));
            Throwable cause = failureOf(storage.truncateSuffix(2));
            if (unchecked != null) assertSame(unchecked, cause);
            else {
                assertEquals("Failed to write truncate record", cause.getMessage());
                assertInstanceOf(IOException.class, cause.getCause());
            }
            // Half a TRUNCATE record is on disk. Nothing may be written behind it unread.
            try (var ignoredUntouched = DurableState.expectUnchanged(dir)) {
                assertRejected(storage.appendEntries(List.of(entry(2, 2))), WriteRejectionReason.LOG_STATE_UNKNOWN);
                assertRejected(storage.truncateSuffix(2), WriteRejectionReason.LOG_STATE_UNKNOWN);
            }
            // Replay repairs the torn record. The truncation never took effect.
            assertEntries(List.of(entry(1, 1), entry(2, 1)), await(storage.replayLog()));
            await(storage.truncateSuffix(2));
            await(storage.appendEntries(List.of(entry(2, 2))));
        } finally { await(storage.closeAsync()); }
        assertEntries(List.of(entry(1, 1), entry(2, 2)), DurableState.replayAfterRestart(dir));
    }

    @Test void ioFailureWritingATruncateRecordLeavesTheTailUnknownUntilReplay() throws Exception {
        assertFailedTruncationLeavesTheTailUnknown(null);
    }

    @Test void uncheckedFailureWritingATruncateRecordLeavesTheTailUnknownUntilReplay() throws Exception {
        assertFailedTruncationLeavesTheTailUnknown(new IllegalStateException("Injected unchecked failure"));
    }

    // ------------------------------------------------------------------ disk space

    private static final class Disk extends CompactionIo {
        volatile boolean full;
        volatile boolean unreadable;
        @Override long usableSpace(Path directory) throws IOException {
            if (unreadable) throw new IOException("Injected failure reading the file store");
            return full ? 1024 : Long.MAX_VALUE;
        }
    }

    @Test void failureToMeasureDiskSpaceRefusesALargeAppendBeforeWritingAnything() throws Exception {
        Disk disk = new Disk();
        FileRaftStorage storage = open(dir, disk);
        try {
            disk.unreadable = true;
            LogEntryData large = new LogEntryData(2, 1, new byte[2 * 1024 * 1024]);
            try (var ignoredUntouched = DurableState.expectUnchanged(dir)) {
                Throwable cause = failureOf(storage.appendEntries(List.of(entry(1, 1), large)));
                assertEquals("Failed to check disk space before append", cause.getMessage());
            }
            // Nothing was written, so the tail is still known and small appends carry on.
            await(storage.appendEntries(List.of(entry(1, 1))));
        } finally { await(storage.closeAsync()); }
        assertEntries(List.of(entry(1, 1)), DurableState.replayAfterRestart(dir));
    }

    @Test void compactionThatRunsOutOfDiskBeforePublicationLeavesTheWalAloneAndDoesNotFence() throws Exception {
        Disk disk = new Disk();
        FileRaftStorage storage = open(dir, disk);
        try {
            await(storage.appendEntries(List.of(entry(1, 1), entry(2, 1), entry(3, 1))));
            disk.full = true;
            try (var ignoredUntouched = DurableState.expectUnchanged(dir)) {
                Throwable cause = failureOf(storage.truncatePrefix(2));
                assertEquals("Prefix compaction failed", cause.getMessage());
                var rejected = assertInstanceOf(FileRaftStorage.WriteRejectedException.class, cause.getCause());
                assertEquals(WriteRejectionReason.INSUFFICIENT_DISK_SPACE, rejected.reason());
            }
            // Publication never began, so the instance is still usable and the boundary unmoved.
            assertEquals(0L, await(storage.compactionBoundary()));
            await(storage.appendEntries(List.of(entry(4, 1))));
            disk.full = false;
            await(storage.truncatePrefix(2));
        } finally { await(storage.closeAsync()); }
        assertEntries(List.of(entry(3, 1), entry(4, 1)), DurableState.replayAfterRestart(dir));
    }

    // ------------------------------------------------------------------ write verification

    /** Writes something other than what it was given, once. */
    private static final class LyingDevice extends CompactionIo {
        final AtomicInteger calls = new AtomicInteger();
        final boolean shortWrite;
        final boolean damageCrcField;
        LyingDevice(boolean shortWrite) { this(shortWrite, false); }
        LyingDevice(boolean shortWrite, boolean damageCrcField) {
            this.shortWrite = shortWrite;
            this.damageCrcField = damageCrcField;
        }
        @Override void writeRecord(FileChannel channel, ByteBuffer record) throws IOException {
            if (calls.incrementAndGet() != 2) { super.writeRecord(channel, record); return; }
            byte[] bytes = new byte[record.remaining()];
            record.get(bytes);
            if (shortWrite) {
                super.writeRecord(channel, ByteBuffer.wrap(bytes, 0, bytes.length / 2));
            } else {
                bytes[damageCrcField ? bytes.length - 1 : 28] ^= 1;     // the stored CRC, or the first payload byte
                super.writeRecord(channel, ByteBuffer.wrap(bytes));
            }
        }
    }

    private FileRaftStorage openVerifying(CompactionIo io) throws Exception {
        FileRaftStorage storage = new FileRaftStorage(RaftStorageConfig.builder().verifyWrites(true).build(), io);
        await(storage.open(dir));
        return storage;
    }

    @Test void writeVerificationCatchesADeviceThatStoredDifferentBytes() throws Exception {
        FileRaftStorage storage = openVerifying(new LyingDevice(false));
        try {
            await(storage.appendEntries(List.of(entry(1, 1))));
            Throwable cause = failureOf(storage.appendEntries(List.of(entry(2, 1))));
            assertInstanceOf(FileRaftStorage.StorageException.class, cause);
            assertTrue(cause.getMessage().contains("CRC mismatch"), cause.getMessage());
            assertRejected(storage.appendEntries(List.of(entry(2, 1))), WriteRejectionReason.LOG_STATE_UNKNOWN);
            // A complete record with a bad CRC is ambiguous: reported and preserved, never repaired.
            try (var ignoredUntouched = DurableState.expectUnchanged(dir)) {
                var corrupt = assertInstanceOf(FileRaftStorage.CorruptLogException.class, failureOf(storage.replayLog()));
                assertEquals(1, corrupt.entriesBeforeCorruption());
            }
        } finally { await(storage.closeAsync()); }
    }

    @Test void writeVerificationCatchesADeviceThatDamagedTheStoredChecksum() throws Exception {
        FileRaftStorage storage = openVerifying(new LyingDevice(false, true));
        try {
            await(storage.appendEntries(List.of(entry(1, 1))));
            Throwable cause = failureOf(storage.appendEntries(List.of(entry(2, 1))));
            assertTrue(cause.getMessage().contains("CRC mismatch"), cause.getMessage());
            assertRejected(storage.appendEntries(List.of(entry(2, 1))), WriteRejectionReason.LOG_STATE_UNKNOWN);
            try (var ignoredUntouched = DurableState.expectUnchanged(dir)) {
                assertInstanceOf(FileRaftStorage.CorruptLogException.class, failureOf(storage.replayLog()));
            }
        } finally { await(storage.closeAsync()); }
    }

    @Test void writeVerificationCatchesAShortWriteAndReplayRepairsTheTornRecord() throws Exception {
        FileRaftStorage storage = openVerifying(new LyingDevice(true));
        try {
            await(storage.appendEntries(List.of(entry(1, 1))));
            Throwable cause = failureOf(storage.appendEntries(List.of(entry(2, 1))));
            assertTrue(cause.getMessage().contains("expected to read"), cause.getMessage());
            assertRejected(storage.appendEntries(List.of(entry(2, 1))), WriteRejectionReason.LOG_STATE_UNKNOWN);
            assertEntries(List.of(entry(1, 1)), await(storage.replayLog()));
            await(storage.appendEntries(List.of(entry(2, 1))));
        } finally { await(storage.closeAsync()); }
        assertEntries(List.of(entry(1, 1), entry(2, 1)), DurableState.replayAfterRestart(dir));
    }

    // ------------------------------------------------------------------ fencing and scheduling

    @Test void operationQueuedBeforeAFenceFailsWithTheFencingFailureAndWritesNothing() throws Exception {
        GatedForce io = new GatedForce(new IOException("Injected fsync failure"));
        FileRaftStorage storage = new FileRaftStorage(RaftStorageConfig.builder().build(), io);
        await(storage.open(dir));
        try {
            await(storage.appendEntries(List.of(entry(1, 1))));
            CompletableFuture<Void> sync = storage.sync();
            assertTrue(io.entered.await(10, TimeUnit.SECONDS));
            long walBytes = Files.size(dir.resolve("raft.log"));
            // Accepted into the queue: the instance is not fenced yet.
            CompletableFuture<Void> queuedAppend = storage.appendEntries(List.of(entry(2, 1)));
            CompletableFuture<Void> queuedTruncate = storage.truncateSuffix(1);
            CompletableFuture<Void> queuedMetadata = storage.updateMetadata(3, Optional.empty());
            io.release.countDown();

            Throwable fence = failureOf(sync);
            assertInstanceOf(FileRaftStorage.StorageException.class, fence);
            assertSame(fence, failureOf(queuedAppend));
            assertSame(fence, failureOf(queuedTruncate));
            assertSame(fence, failureOf(queuedMetadata));
            assertEquals(walBytes, Files.size(dir.resolve("raft.log")), "work queued behind the fence must not reach the WAL");
            assertFalse(Files.exists(dir.resolve("meta.dat")));
            WalRecords.assertNoStrayFiles(dir);
        } finally { await(storage.closeAsync()); }
    }

    @Test void readOperationsRefusedByTheExecutorFailTheirFuture() throws Exception {
        FileRaftStorage storage = FileRaftStorage.unsafeWithoutFsyncForTesting(false);
        Field executorField = FileRaftStorage.class.getDeclaredField("walExecutor");
        executorField.setAccessible(true);
        ((ExecutorService) executorField.get(storage)).shutdown();
        for (CompletableFuture<?> refused : List.of(
                assertDoesNotThrow(storage::replayLog), assertDoesNotThrow(storage::loadMetadata),
                assertDoesNotThrow(storage::compactionBoundary))) {
            Throwable cause = failureOf(refused);
            assertInstanceOf(FileRaftStorage.StorageException.class, cause);
            assertTrue(cause.getMessage().startsWith("Storage operation could not be scheduled"), cause.getMessage());
        }
    }

    // ------------------------------------------------------------------ failures while cleaning up after a failure

    @Test void replayThatCannotOpenTheWalFailsWithoutFencingAndWithoutLosingTheKnownTail() throws Exception {
        FileRaftStorage storage = open(dir);
        java.io.File wal = dir.resolve("raft.log").toFile();
        try {
            await(storage.appendEntries(List.of(entry(1, 1))));
            // Replay opens the file a second time for read and write; a read-only file refuses that.
            org.junit.jupiter.api.Assumptions.assumeTrue(wal.setWritable(false) && !Files.isWritable(wal.toPath()),
                    "this platform or user can write to read-only files");
            Throwable cause = failureOf(storage.replayLog());
            assertEquals("Failed to replay log", cause.getMessage());
            assertInstanceOf(IOException.class, cause.getCause());
            assertFalse(cause instanceof FileRaftStorage.CorruptLogException);
            // Nothing was read, so what the instance knew about its tail is still true.
            await(storage.appendEntries(List.of(entry(2, 1))));
            assertTrue(wal.setWritable(true));
            assertEntries(List.of(entry(1, 1), entry(2, 1)), await(storage.replayLog()));
        } finally {
            wal.setWritable(true);
            await(storage.closeAsync());
        }
        assertEntries(List.of(entry(1, 1), entry(2, 1)), DurableState.replayAfterRestart(dir));
    }

    /** Fails the second open of the WAL, which is the one replay performs. */
    private static final class ReplayCannotOpenWal extends CompactionIo {
        volatile boolean armed;
        @Override FileChannel openForReplay(Path path) throws IOException {
            if (armed) throw new IOException("Injected failure opening the WAL for replay");
            return super.openForReplay(path);
        }
    }

    @Test void replayIoFailureIsReportedWithoutFencingOnEveryPlatformAndUser() throws Exception {
        // The permission-based test above cannot run as root, where read-only is not enforced.
        ReplayCannotOpenWal io = new ReplayCannotOpenWal();
        FileRaftStorage storage = open(dir, io);
        try {
            await(storage.appendEntries(List.of(entry(1, 1))));
            io.armed = true;
            try (var ignoredUntouched = DurableState.expectUnchanged(dir)) {
                Throwable cause = failureOf(storage.replayLog());
                assertEquals("Failed to replay log", cause.getMessage());
                assertEquals("Injected failure opening the WAL for replay", cause.getCause().getMessage());
                assertFalse(cause instanceof FileRaftStorage.CorruptLogException);
            }
            // Not fenced, and the tail it knew before the failed replay is still true.
            await(storage.appendEntries(List.of(entry(2, 1))));
            io.armed = false;
            assertEntries(List.of(entry(1, 1), entry(2, 1)), await(storage.replayLog()));
        } finally { await(storage.closeAsync()); }
        assertEntries(List.of(entry(1, 1), entry(2, 1)), DurableState.replayAfterRestart(dir));
    }

    /** Hands back a WAL channel that is already closed, so open fails after the channel exists. */
    private static final class DeadChannelOnOpen extends CompactionIo {
        final AtomicInteger closes = new AtomicInteger();
        @Override FileChannel openLog(Path path) throws IOException {
            FileChannel channel = super.openLog(path);
            channel.close();
            return channel;
        }
        @Override void closeChannel(FileChannel channel) throws IOException {
            super.closeChannel(channel);
            if (closes.incrementAndGet() == 1) throw new IOException("Injected failure closing the half-opened channel");
        }
    }

    @Test void openThatFailsAfterTheChannelExistsReleasesEverythingEvenIfClosingItFails() throws Exception {
        DeadChannelOnOpen io = new DeadChannelOnOpen();
        FileRaftStorage storage = FileRaftStorage.unsafeWithoutFsyncForTesting(RaftStorageConfig.builder().build(), io);
        Throwable cause = failureOf(storage.open(dir));
        assertInstanceOf(FileRaftStorage.StorageException.class, cause);
        assertTrue(cause.getMessage().startsWith("Failed to open WAL"), cause.getMessage());
        assertTrue(io.closes.get() >= 1, "the half-opened channel must be closed through the failure path");
        assertRejectedAsNotOpen(storage.appendEntries(List.of(entry(1, 1))));
        await(storage.closeAsync());
        // The lock was released despite the close failure: the directory is usable at once.
        FileRaftStorage next = open(dir);
        try { await(next.appendEntries(List.of(entry(1, 1)))); } finally { await(next.closeAsync()); }
        assertEntries(List.of(entry(1, 1)), DurableState.replayAfterRestart(dir));
    }

    @Test void closeWhenTheExecutorRefusesTheCloseTaskIsReportedByBothFormsOfClose() throws Exception {
        FileRaftStorage storage = FileRaftStorage.unsafeWithoutFsyncForTesting(false);
        Field executorField = FileRaftStorage.class.getDeclaredField("walExecutor");
        executorField.setAccessible(true);
        ((ExecutorService) executorField.get(storage)).shutdown();
        Throwable cause = failureOf(storage.closeAsync());
        assertInstanceOf(java.util.concurrent.RejectedExecutionException.class, cause);
        assertSame(cause, assertThrows(FileRaftStorage.StorageException.class, storage::close).getCause());
    }

    /** Fails publication, and also fails every close, so the failure path's own close fails too. */
    private static final class PublicationAndCloseFail extends CompactionIo {
        volatile boolean armed;
        @Override void replace(Path source, Path target) throws IOException {
            if (armed) throw new IOException("Injected publication failure");
            super.replace(source, target);
        }
        @Override void closeChannel(FileChannel channel) throws IOException {
            super.closeChannel(channel);
            if (armed) throw new IOException("Injected close failure during compaction");
        }
    }

    @Test void closeFailureDuringAFailedPublicationIsRecordedAndTheInstanceIsFenced() throws Exception {
        PublicationAndCloseFail io = new PublicationAndCloseFail();
        FileRaftStorage storage = open(dir, io);
        try {
            await(storage.appendEntries(List.of(entry(1, 1), entry(2, 1))));
            io.armed = true;
            Throwable failure = failureOf(storage.truncatePrefix(1));
            assertEquals("Prefix compaction failed", failure.getMessage());
            assertEquals("Injected close failure during compaction", failure.getCause().getMessage());
            assertEquals(1, failure.getSuppressed().length, "the second close failure must not be lost");
            assertEquals("Injected close failure during compaction", failure.getSuppressed()[0].getMessage());
            // Publication was attempted, so either generation may be on disk: fenced.
            assertSame(failure, failureOf(storage.appendEntries(List.of(entry(3, 1)))));
        } finally {
            io.armed = false;
            await(storage.closeAsync());
        }
        // The old WAL is still authoritative and intact.
        assertEntries(List.of(entry(1, 1), entry(2, 1)), DurableState.replayAfterRestart(dir));
    }

    /** Fails the compaction output write, then fails the cleanup of the partial output too. */
    private static final class WriteAndCleanupFail extends CompactionIo {
        volatile boolean armed;
        @Override void write(FileChannel channel, ByteBuffer bytes) throws IOException {
            if (armed) throw new IOException("Injected compaction write failure");
            super.write(channel, bytes);
        }
        @Override void discard(Path path) throws IOException {
            if (armed) throw new IOException("Injected cleanup failure");
            super.discard(path);
        }
    }

    @Test void cleanupFailureAfterAPrePublicationFailureIsRecordedAndTheInstanceStaysUsable() throws Exception {
        WriteAndCleanupFail io = new WriteAndCleanupFail();
        FileRaftStorage storage = open(dir, io);
        try {
            await(storage.appendEntries(List.of(entry(1, 1), entry(2, 1))));
            io.armed = true;
            Throwable failure = failureOf(storage.truncatePrefix(1));
            assertEquals("Prefix compaction failed", failure.getMessage());
            assertEquals("Injected compaction write failure", failure.getCause().getMessage());
            assertEquals(1, failure.getSuppressed().length, "the cleanup failure must not be lost");
            assertEquals("Injected cleanup failure", failure.getSuppressed()[0].getMessage());
            io.armed = false;
            // Publication never began, so the WAL is untouched and the instance is not fenced.
            await(storage.appendEntries(List.of(entry(3, 1))));
            await(storage.truncatePrefix(1));
        } finally { await(storage.closeAsync()); }
        assertEntries(List.of(entry(2, 1), entry(3, 1)), DurableState.replayAfterRestart(dir));
        WalRecords.assertNoStrayFiles(dir);
    }

    // ------------------------------------------------------------------ torn tail versus hidden records

    private static byte[] tornHeader(long index, int declaredPayload, byte[] presentPayload) {
        ByteBuffer buf = ByteBuffer.allocate(27 + presentPayload.length);
        buf.putInt(0x52414654).putShort((short) 1).put((byte) 2).putLong(index).putLong(1L).putInt(declaredPayload)
                .put(presentPayload);
        return buf.array();
    }

    private static void plant(byte[] target, int offset, String ascii) {
        byte[] bytes = ascii.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, target, offset, bytes.length);
    }

    @Test void tornPayloadThatHappensToContainTheMagicBytesIsStillATornTail() throws Exception {
        byte[] payload = new byte[60];
        plant(payload, 3, "R");            // every partial match of the magic, then the whole of it
        plant(payload, 9, "RA");
        plant(payload, 15, "RAF");
        plant(payload, 21, "RAFT");
        plant(payload, 40, "RAFTRAFT");
        byte[] first = append(1, 1);
        writeWal(first, tornHeader(2, 100, payload));
        FileRaftStorage storage = open(dir);
        try {
            assertEntries(List.of(entry(1, 1)), await(storage.replayLog()));
            assertEquals(first.length, Files.size(dir.resolve("raft.log")), "only the torn record is removed");
            await(storage.appendEntries(List.of(entry(2, 1))));
        } finally { await(storage.closeAsync()); }
        assertEntries(List.of(entry(1, 1), entry(2, 1)), DurableState.replayAfterRestart(dir));
    }

    @Test void tornPayloadSpanningSeveralScanWindowsIsRepaired() throws Exception {
        byte[] payload = new byte[200_000];                    // the scan window is 64 KiB
        for (int i = 0; i < payload.length; i += 4_099) plant(payload, i, "RAF");
        plant(payload, 65_534, "RAFT");                        // magic straddling a window edge
        byte[] first = append(1, 1);
        writeWal(first, tornHeader(2, 300_000, payload));
        FileRaftStorage storage = open(dir);
        try {
            assertEntries(List.of(entry(1, 1)), await(storage.replayLog()));
            assertEquals(first.length, Files.size(dir.resolve("raft.log")));
        } finally { await(storage.closeAsync()); }
    }

    @Test void validRecordSeveralWindowsBeyondAnApparentlyTornHeaderIsNeverTruncatedAway() throws Exception {
        // A damaged length field makes record 2 look torn. Record 3 sits 150 KB further on,
        // intact, and may have been acknowledged. Truncating at record 2 would destroy it.
        byte[] filler = new byte[150_000];
        ByteArrayOutputStream wal = new ByteArrayOutputStream();
        wal.write(append(1, 1));
        wal.write(tornHeader(2, 900_000, filler));
        wal.write(append(3, 1));
        Files.write(dir.resolve("raft.log"), wal.toByteArray());
        FileRaftStorage storage = open(dir);
        try (var ignoredUntouched = DurableState.expectUnchanged(dir)) {
            var corrupt = assertInstanceOf(FileRaftStorage.CorruptLogException.class, failureOf(storage.replayLog()));
            assertEquals(1, corrupt.entriesBeforeCorruption());
        } finally { await(storage.closeAsync()); }
    }

    @Test void completeRecordWithFormatVersionZeroIsCorruptionNotATornTail() throws Exception {
        writeWal(append(1, 1), record(0, 2, 2, 1, new byte[]{1}));
        FileRaftStorage storage = open(dir);
        try (var ignoredUntouched = DurableState.expectUnchanged(dir)) {
            assertInstanceOf(FileRaftStorage.CorruptLogException.class, failureOf(storage.replayLog()));
        } finally { await(storage.closeAsync()); }
    }

    @Test void tornTruncateRecordIsRepairedAndTheTruncationNeverHappened() throws Exception {
        byte[] whole = truncate(2);
        ByteArrayOutputStream wal = new ByteArrayOutputStream();
        wal.write(append(1, 1));
        wal.write(append(2, 1));
        wal.write(whole, 0, whole.length - 2);                 // the CRC is cut short
        Files.write(dir.resolve("raft.log"), wal.toByteArray());
        FileRaftStorage storage = open(dir);
        try {
            assertEntries(List.of(entry(1, 1), entry(2, 1)), await(storage.replayLog()));
            await(storage.appendEntries(List.of(entry(3, 1))));
        } finally { await(storage.closeAsync()); }
        assertEntries(List.of(entry(1, 1), entry(2, 1), entry(3, 1)), DurableState.replayAfterRestart(dir));
    }

    @Test void tornPrefixMarkerIsReportedNeverRepaired() throws Exception {
        // Repairing it would truncate the file to nothing and silently restart the index
        // space at 1, on a node whose snapshot says the log continues far beyond that.
        byte[] whole = prefix(1_000);
        Files.write(dir.resolve("raft.log"), java.util.Arrays.copyOf(whole, whole.length - 2));
        FileRaftStorage storage = open(dir);
        try (var ignoredUntouched = DurableState.expectUnchanged(dir)) {
            assertInstanceOf(FileRaftStorage.CorruptLogException.class, failureOf(storage.replayLog()));
            assertInstanceOf(FileRaftStorage.CorruptLogException.class,
                    failureOf(storage.appendEntries(List.of(entry(1, 1)))), "must be fenced, not writable from index 1");
        } finally { await(storage.closeAsync()); }
    }

    // ------------------------------------------------------------------ reads that come back short

    /**
     * Makes one positional read behave as though the file had shrunk underneath the reader
     * (end of file) or the device made no progress (zero bytes). Chosen by file position and
     * by which read of that position it is, because replay reads some positions twice.
     */
    private static final class AnomalousRead extends CompactionIo {
        final long position;
        final int occurrence;
        final int result;
        final AtomicInteger seen = new AtomicInteger();
        AnomalousRead(long position, int occurrence, int result) {
            this.position = position;
            this.occurrence = occurrence;
            this.result = result;
        }
        @Override int read(FileChannel channel, ByteBuffer buffer, long pos) throws IOException {
            if (pos == position && seen.incrementAndGet() == occurrence) return result;
            return super.read(channel, buffer, pos);
        }
    }

    private static final int END_OF_FILE = -1;
    private static final int NO_PROGRESS = 0;
    private static final int RECORD = 32;                       // append(index, term): 27 + 1 + 4

    /**
     * A read that ends early inside the known length of the file means the file is changing
     * while it is being read. Nothing about its contents can be concluded, so the only safe
     * outcome is an I/O failure: no corruption verdict, no fence, and above all no truncation.
     */
    private void assertReplayFailsWithoutVerdictOrDamage(CompactionIo io, List<LogEntryData> afterwards) throws Exception {
        FileRaftStorage storage = open(dir, io);
        try {
            try (var ignoredUntouched = DurableState.expectUnchanged(dir)) {
                Throwable cause = failureOf(storage.replayLog());
                assertEquals("Failed to replay log", cause.getMessage(), String.valueOf(cause));
                assertInstanceOf(IOException.class, cause.getCause());
                assertFalse(cause instanceof FileRaftStorage.CorruptLogException, "an unreadable file is not a corrupt one");
            }
            // The anomaly was transient. The instance is not fenced and a second replay sees the truth.
            if (afterwards != null) assertEntries(afterwards, await(storage.replayLog()));
        } finally { await(storage.closeAsync()); }
    }

    @Test void recordHeaderReadThatEndsEarlyIsAnIoFailureNotCorruption() throws Exception {
        writeWal(append(1, 1), append(2, 1));
        assertReplayFailsWithoutVerdictOrDamage(new AnomalousRead(RECORD, 1, END_OF_FILE),
                List.of(new LogEntryData(1, 1, new byte[]{1}), new LogEntryData(2, 1, new byte[]{1})));
    }

    @Test void shortReadWhileClassifyingTheTailNeverTruncatesACompleteRecordWithABadChecksum() throws Exception {
        // Record 2 is complete but its checksum is wrong. Policy: it may be acknowledged data
        // damaged later, so it is reported and preserved. A short read while deciding whether
        // it is a torn write must not turn that verdict into "incomplete, truncate it".
        byte[] damaged = append(2, 1);
        damaged[damaged.length - 1] ^= 1;
        writeWal(append(1, 1), damaged);
        assertReplayFailsWithoutVerdictOrDamage(new AnomalousRead(RECORD, 2, END_OF_FILE), null);

        // With no anomaly the same file gets the correct verdict, and is still untouched.
        FileRaftStorage storage = open(dir);
        try (var ignoredUntouched = DurableState.expectUnchanged(dir)) {
            assertInstanceOf(FileRaftStorage.CorruptLogException.class, failureOf(storage.replayLog()));
        } finally { await(storage.closeAsync()); }
    }

    @Test void shortReadWhileScanningForLaterRecordsNeverTruncatesAValidRecordAway() throws Exception {
        // Record 2 looks torn because its length field is damaged. Record 3 is intact beyond it.
        // If the scan that looks for record 3 gives up on a short read, the file is truncated
        // at record 2 and an entry that may have been acknowledged is destroyed.
        ByteArrayOutputStream wal = new ByteArrayOutputStream();
        wal.write(append(1, 1));
        wal.write(tornHeader(2, 900_000, new byte[150_000]));
        wal.write(append(3, 1));
        Files.write(dir.resolve("raft.log"), wal.toByteArray());
        assertReplayFailsWithoutVerdictOrDamage(new AnomalousRead(RECORD + 1, 1, END_OF_FILE), null);
    }

    @Test void readThatMakesNoProgressIsAnIoFailure() throws Exception {
        writeWal(append(1, 1), append(2, 1));
        assertReplayFailsWithoutVerdictOrDamage(new AnomalousRead(0, 1, NO_PROGRESS),
                List.of(new LogEntryData(1, 1, new byte[]{1}), new LogEntryData(2, 1, new byte[]{1})));
    }

    @Test void shortReadDuringCompactionLeavesTheWalAloneAndDoesNotFence() throws Exception {
        writeWal(append(1, 1), append(2, 1), append(3, 1));
        FileRaftStorage storage = open(dir, new AnomalousRead(RECORD, 1, END_OF_FILE));
        try {
            try (var ignoredUntouched = DurableState.expectUnchanged(dir)) {
                Throwable cause = failureOf(storage.truncatePrefix(1));
                assertEquals("Prefix compaction failed", cause.getMessage());
                assertInstanceOf(IOException.class, cause.getCause());
            }
            WalRecords.assertNoStrayFiles(dir);
            // Publication never began, so nothing is fenced and the compaction can simply be retried.
            await(storage.truncatePrefix(1));
            assertEquals(1L, await(storage.compactionBoundary()));
        } finally { await(storage.closeAsync()); }
        assertEntries(List.of(new LogEntryData(2, 1, new byte[]{1}), new LogEntryData(3, 1, new byte[]{1})),
                DurableState.replayAfterRestart(dir));
    }

    // ------------------------------------------------------------------ the write limit is not a read limit

    @Test void loweringThePayloadLimitDoesNotMakeExistingRecordsUnreadable() throws Exception {
        byte[] big = new byte[2 * 1024 * 1024];
        for (int i = 0; i < big.length; i++) big[i] = (byte) (i * 7);
        FileRaftStorage generous = new FileRaftStorage(RaftStorageConfig.builder().maxPayloadSizeMb(4).build());
        await(generous.open(dir));
        try {
            await(generous.appendEntries(List.of(new LogEntryData(1, 1, big), entry(2, 1))));
            await(generous.sync());
        } finally { await(generous.closeAsync()); }

        // An operator lowers the limit. The log is healthy; it must not be reported as corrupt.
        FileRaftStorage strict = new FileRaftStorage(RaftStorageConfig.builder().maxPayloadSizeMb(1).build());
        await(strict.open(dir));
        try {
            List<LogEntryData> replayed;
            try (var ignoredUntouched = DurableState.expectUnchanged(dir)) {
                replayed = await(strict.replayLog());
            }
            assertEquals(2, replayed.size());
            assertArrayEquals(big, replayed.get(0).payload());

            // The lower limit governs what may be written from now on.
            try (var ignoredUntouched = DurableState.expectUnchanged(dir)) {
                assertRejected(strict.appendEntries(List.of(new LogEntryData(3, 1, big))), WriteRejectionReason.PAYLOAD_TOO_LARGE);
            }
            await(strict.appendEntries(List.of(entry(3, 1))));
            // Compaction reads the log too, and must keep the large entry it retains.
            await(strict.truncatePrefix(0));
            await(strict.sync());
        } finally { await(strict.closeAsync()); }

        FileRaftStorage again = new FileRaftStorage(RaftStorageConfig.builder().maxPayloadSizeMb(1).build());
        await(again.open(dir));
        try {
            List<LogEntryData> replayed = await(again.replayLog());
            assertEquals(3, replayed.size());
            assertArrayEquals(big, replayed.get(0).payload());
        } finally { await(again.closeAsync()); }
    }

    @Test void tornRecordDeclaringMoreThanTheLimitIsStillReportedRatherThanRepaired() throws Exception {
        // The limit stays in force as a plausibility check on torn writes: this build never writes
        // a record larger than its limit, so a fragment claiming to be one was not torn by a crash.
        writeWal(append(1, 1), tornHeader(2, 40 * 1024 * 1024, new byte[64]));
        FileRaftStorage storage = open(dir);
        try (var ignoredUntouched = DurableState.expectUnchanged(dir)) {
            assertInstanceOf(FileRaftStorage.CorruptLogException.class, failureOf(storage.replayLog()));
        } finally { await(storage.closeAsync()); }
    }

    // ------------------------------------------------------------------ the scanner decodes quietly

    private static byte[] header(int version, int type, long index, long term, int declaredPayload) {
        return ByteBuffer.allocate(27).putInt(0x52414654).putShort((short) version).put((byte) type)
                .putLong(index).putLong(term).putInt(declaredPayload).array();
    }

    /**
     * A torn record whose surviving payload is full of things that begin like records and are
     * not: every way a candidate can fail to decode. The scan for later valid records must
     * reject each of them without being fooled, so the tail is still just a torn write.
     */
    private void assertTornTailIsRepairedDespite(byte[]... impostors) throws Exception {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        for (byte[] impostor : impostors) {
            payload.write(new byte[11]);
            payload.write(impostor);
        }
        byte[] first = append(1, 1);
        writeWal(first, tornHeader(2, 100_000, payload.toByteArray()));
        FileRaftStorage storage = open(dir);
        try {
            assertEntries(List.of(new LogEntryData(1, 1, new byte[]{1})), await(storage.replayLog()));
            assertEquals(first.length, Files.size(dir.resolve("raft.log")), "only the torn record is removed");
            await(storage.appendEntries(List.of(entry(2, 1))));
        } finally { await(storage.closeAsync()); }
        assertEquals(2, DurableState.replayAfterRestart(dir).size());
    }

    @Test void scanRejectsEveryKindOfImpostorRecordInsideATornPayload() throws Exception {
        byte[] badChecksum = record(1, 2, 9, 1, new byte[]{7});
        badChecksum[badChecksum.length - 1] ^= 1;
        byte[] unknownType = ByteBuffer.allocate(31).put(header(1, 9, 9, 1, 0)).putInt(0).array();
        byte[] checksumCutShort = ByteBuffer.allocate(29).put(header(1, 2, 9, 1, 0)).array();
        assertTornTailIsRepairedDespite(
                header(1, 2, 9, 1, -5),              // a negative payload length
                header(1, 2, 9, 1, Integer.MAX_VALUE), // a length beyond the configured maximum
                unknownType,
                badChecksum,
                header(1, 2, 9, 1, 5_000),           // a payload that runs past the end of the file
                checksumCutShort);                   // last: its checksum runs past the end of the file
    }

    @Test void scanRejectsAMagicNumberTooCloseToTheEndToHoldAHeader() throws Exception {
        assertTornTailIsRepairedDespite("RAFT".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    // ------------------------------------------------------------------ defensive code, stated as tests

    @Test void staleFailedOpenCallbackCannotResetAnOpenThatSucceededLater() throws Exception {
        FileRaftStorage storage = open(dir);
        try {
            CompletableFuture<Void> current = storage.open(dir);
            // A callback from some earlier, failed attempt arrives late.
            storage.resetFailedOpen(CompletableFuture.failedFuture(new IOException("an older attempt")));
            assertSame(current, storage.open(dir), "the live open must not have been forgotten");
            await(storage.appendEntries(List.of(entry(1, 1))));
        } finally { await(storage.closeAsync()); }
    }

    @Test void schedulingFailureReportsClosureOrFencingInPreferenceToTheExecutor() throws Exception {
        java.util.concurrent.RejectedExecutionException refused = new java.util.concurrent.RejectedExecutionException("refused");
        FileRaftStorage healthy = open(dir.resolve("healthy"));
        try {
            FileRaftStorage.StorageException failure = healthy.schedulingFailure("append", refused);
            assertTrue(failure.getMessage().startsWith("Storage operation could not be scheduled"), failure.getMessage());
            assertSame(refused, failure.getCause());

            // Once fenced, the fencing cause is the truth, whatever the executor said.
            FileRaftStorage.StorageException fence = healthy.fence("Injected", new IOException("device gone"));
            assertSame(fence, healthy.schedulingFailure("append", refused));
        } finally { await(healthy.closeAsync()); }
        assertTrue(healthy.schedulingFailure("append", refused).getMessage().startsWith("Storage is closed"));
    }

    @Test void firstFencingFailureIsKeptWhenASecondOneFollows() throws Exception {
        FileRaftStorage storage = open(dir);
        try {
            FileRaftStorage.StorageException first = storage.fence("First", new IOException("the real cause"));
            FileRaftStorage.StorageException second = storage.fence("Second", new IOException("a consequence"));
            assertNotSame(first, second);
            // Callers must keep seeing the original cause, not whatever failed afterwards.
            assertSame(first, failureOf(storage.appendEntries(List.of(entry(1, 1)))));
            assertSame(first, failureOf(storage.replayLog()));
        } finally { await(storage.closeAsync()); }
    }

    // ------------------------------------------------------------------ close racing a failing open

    /** Holds open() at the point where it opens the WAL, then fails it. */
    private static final class GatedFailingOpen extends CompactionIo {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        @Override FileChannel openLog(Path path) throws IOException {
            entered.countDown();
            try { release.await(); } catch (InterruptedException e) { throw new IOException(e); }
            throw new IOException("Injected open failure");
        }
    }

    @Test void closeRequestedWhileAnOpenIsFailingKeepsTheInstanceClosed() throws Exception {
        GatedFailingOpen io = new GatedFailingOpen();
        FileRaftStorage storage = FileRaftStorage.unsafeWithoutFsyncForTesting(RaftStorageConfig.builder().build(), io);
        CompletableFuture<Void> opening = storage.open(dir);
        assertTrue(io.entered.await(10, TimeUnit.SECONDS));
        CompletableFuture<Void> closing = storage.closeAsync();
        io.release.countDown();

        assertInstanceOf(FileRaftStorage.StorageException.class, failureOf(opening));
        await(closing);
        // A failed open normally allows a retry. A closed instance must not come back to life.
        Throwable cause = failureOf(storage.open(dir));
        assertTrue(cause.getMessage().startsWith("Storage is closed"), cause.getMessage());
        FileRaftStorage next = open(dir);
        try { await(next.appendEntries(List.of(entry(1, 1)))); } finally { await(next.closeAsync()); }
    }

    @Test void openThatFailsAfterTheChannelExistsClosesItCleanly() throws Exception {
        CompactionIo deadChannel = new CompactionIo() {
            @Override FileChannel openLog(Path path) throws IOException {
                FileChannel channel = super.openLog(path);
                channel.close();
                return channel;
            }
        };
        FileRaftStorage storage = FileRaftStorage.unsafeWithoutFsyncForTesting(RaftStorageConfig.builder().build(), deadChannel);
        assertTrue(failureOf(storage.open(dir)).getMessage().startsWith("Failed to open WAL"));
        // After a failed open the same instance may try again.
        assertTrue(failureOf(storage.open(dir)).getMessage().startsWith("Failed to open WAL"));
        await(storage.closeAsync());
    }

    // ------------------------------------------------------------------ append plan

    @Test void planWithATruncationAppliesToAnEmptyInMemoryLog() {
        AppendPlan plan = new AppendPlan(5L, List.of(entry(5, 2)));
        List<LogEntryData> memory = new java.util.ArrayList<>();
        plan.applyTo(memory);
        assertEntries(List.of(entry(5, 2)), memory);
    }
}
