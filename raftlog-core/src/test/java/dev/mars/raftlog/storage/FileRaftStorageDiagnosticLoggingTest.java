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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.mars.raftlog.storage.RaftStorage.LogEntryData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.CRC32C;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The log must explain every decision the storage takes about a write or about a file it reads.
 * <p>
 * A refusal reaches the caller as a failed future, and a caller that drops the future leaves no
 * trace of it. The storage therefore logs every refusal itself: once, at ERROR, with the reason and
 * with the state the decision was taken from. It is an error because no refusal is routine: a
 * correct consensus layer never sends a gap, a term regression or a second vote, so a refusal means
 * the layer above tried to break a Raft safety rule, or the disk is full, or the metadata cannot be
 * read, and each of those needs a person. What tells a refusal apart from a damaged storage is the
 * event, not the level: after {@code storage.write.rejected} nothing was written and the instance
 * is usable, after {@code storage.fenced} it is not. The same state is reported when it is established (open and replay) and when it is lost (a
 * write that failed part way), so a refusal can be traced back to its cause from the log alone.
 */
class FileRaftStorageDiagnosticLoggingTest {

    @TempDir
    Path dir;

    private Logger logger;
    private Level levelBefore;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void captureStorageLogs() {
        logger = (Logger) LoggerFactory.getLogger(FileRaftStorage.class);
        levelBefore = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void stopCapturingStorageLogs() {
        logger.detachAppender(appender);
        logger.setLevel(levelBefore);
        appender.stop();
    }

    // ------------------------------------------------------------------ every refusal is logged

    @Test void appendThatDoesNotContinueTheLogIsLoggedWithTheTailItWasCheckedAgainst() throws Exception {
        FileRaftStorage storage = openWithEntries(entry(1, 1), entry(2, 3));
        try {
            ILoggingEvent refusal = refusalOf(storage.appendEntries(List.of(entry(5, 3))),
                    WriteRejectionReason.INDEX_NOT_CONTIGUOUS, "append");
            assertKeyValue(refusal, "tailKnown", true);
            assertKeyValue(refusal, "lastIndex", 2L);
            assertKeyValue(refusal, "lastTerm", 3L);
            assertKeyValue(refusal, "prefixBoundary", 0L);
            assertTrue(refusal.getFormattedMessage().contains("Append must start at index 3, got 5"),
                    refusal.getFormattedMessage());
        } finally { await(storage.closeAsync()); }
    }

    @Test void appendWithATermBelowTheTailIsLogged() throws Exception {
        FileRaftStorage storage = openWithEntries(entry(1, 4));
        try {
            ILoggingEvent refusal = refusalOf(storage.appendEntries(List.of(entry(2, 3))),
                    WriteRejectionReason.TERM_REGRESSION, "append");
            assertKeyValue(refusal, "lastTerm", 4L);
        } finally { await(storage.closeAsync()); }
    }

    @Test void writeBeforeReplayIsLoggedAsAnUnknownTail() throws Exception {
        await(openWithEntries(entry(1, 1)).closeAsync());
        FileRaftStorage storage = open(dir);
        try {
            ILoggingEvent refusal = refusalOf(storage.appendEntries(List.of(entry(2, 1))),
                    WriteRejectionReason.LOG_STATE_UNKNOWN, "append");
            assertKeyValue(refusal, "tailKnown", false);
        } finally { await(storage.closeAsync()); }
    }

    @Test void truncationThatIsNotASuffixOfTheLogIsLogged() throws Exception {
        FileRaftStorage storage = openWithEntries(entry(1, 1), entry(2, 1));
        try {
            ILoggingEvent refusal = refusalOf(storage.truncateSuffix(9),
                    WriteRejectionReason.INVALID_TRUNCATION, "suffix-truncate");
            assertKeyValue(refusal, "lastIndex", 2L);
        } finally { await(storage.closeAsync()); }
    }

    @Test void truncationIntoTheCompactedPrefixIsLoggedWithTheBoundary() throws Exception {
        FileRaftStorage storage = openWithEntries(entry(1, 1), entry(2, 1), entry(3, 1));
        try {
            await(storage.truncatePrefix(2));
            ILoggingEvent refusal = refusalOf(storage.truncateSuffix(2),
                    WriteRejectionReason.INVALID_TRUNCATION, "suffix-truncate");
            assertKeyValue(refusal, "prefixBoundary", 2L);
            assertKeyValue(refusal, "lastIndex", 3L);
        } finally { await(storage.closeAsync()); }
    }

    @Test void secondVoteInTheSameTermIsLoggedWithoutLeakingAnUnboundedCandidateName() throws Exception {
        FileRaftStorage storage = open(dir);
        try {
            await(storage.updateMetadata(7, Optional.of("node-a")));
            String hostile = "node-b\nWARN forged line " + "x".repeat(10_000);
            ILoggingEvent refusal = refusalOf(storage.updateMetadata(7, Optional.of(hostile)),
                    WriteRejectionReason.VOTE_CHANGED, "metadata-update");
            assertKeyValue(refusal, "persistedTerm", 7L);
            assertKeyValue(refusal, "metadataReadable", true);
            assertTrue(refusal.getFormattedMessage().indexOf('\n') < 0, "one line only");
            assertTrue(refusal.getFormattedMessage().length() < 2_000, "bounded");
        } finally { await(storage.closeAsync()); }
    }

    @Test void termBelowThePersistedTermIsLogged() throws Exception {
        FileRaftStorage storage = open(dir);
        try {
            await(storage.updateMetadata(7, Optional.empty()));
            ILoggingEvent refusal = refusalOf(storage.updateMetadata(6, Optional.empty()),
                    WriteRejectionReason.TERM_REGRESSION, "metadata-update");
            assertKeyValue(refusal, "persistedTerm", 7L);
        } finally { await(storage.closeAsync()); }
    }

    @Test void updateOverUnreadableMetadataIsLogged() throws Exception {
        Files.createDirectory(dir.resolve("meta.dat"));
        FileRaftStorage storage = open(dir);
        try {
            assertEquals(Level.ERROR, only("metadata.unreadable").getLevel(), "unreadable metadata is an error when it is found");
            appender.list.clear();
            ILoggingEvent refusal = refusalOf(storage.updateMetadata(1, Optional.empty()),
                    WriteRejectionReason.METADATA_UNREADABLE, "metadata-update");
            assertKeyValue(refusal, "metadataReadable", false);
        } finally { await(storage.closeAsync()); }
    }

    @Test void oversizedPayloadIsLoggedLikeEveryOtherRefusal() throws Exception {
        FileRaftStorage storage = new FileRaftStorage(RaftStorageConfig.builder().maxPayloadSizeMb(1).build());
        await(storage.open(dir));
        try {
            ILoggingEvent refusal = refusalOf(
                    storage.appendEntries(List.of(new LogEntryData(1, 1, new byte[1024 * 1024 + 1]))),
                    WriteRejectionReason.PAYLOAD_TOO_LARGE, "append");
            assertKeyValue(refusal, "entryIndex", 1L);
        } finally { await(storage.closeAsync()); }
    }

    @Test void fullDiskIsLoggedLikeEveryOtherRefusal() throws Exception {
        Disk disk = new Disk();
        FileRaftStorage storage = FileRaftStorage.unsafeWithoutFsyncForTesting(
                RaftStorageConfig.builder().minFreeSpaceMb(64).build(), disk);
        await(storage.open(dir));
        try {
            disk.full = true;
            ILoggingEvent refusal = refusalOf(
                    storage.appendEntries(List.of(new LogEntryData(1, 1, new byte[2 * 1024 * 1024]))),
                    WriteRejectionReason.INSUFFICIENT_DISK_SPACE, "append");
            assertKeyValue(refusal, "lastIndex", 0L);
        } finally { await(storage.closeAsync()); }
    }

    // ------------------------------------------------------------------ the state behind the decisions

    @Test void openSaysWhetherReplayIsRequiredAndWhichMetadataItLoaded() throws Exception {
        FileRaftStorage first = open(dir);
        try {
            ILoggingEvent opened = only("storage.open.completed");
            assertKeyValue(opened, "replayRequired", false);
            assertKeyValue(opened, "persistedTerm", 0L);
            assertKeyValue(opened, "voted", false);
            assertKeyValue(opened, "metadataReadable", true);
            await(first.updateMetadata(4, Optional.of("node-a")));
            await(first.appendEntries(List.of(entry(1, 4))));
        } finally { await(first.closeAsync()); }

        appender.list.clear();
        FileRaftStorage second = open(dir);
        try {
            ILoggingEvent opened = only("storage.open.completed");
            assertKeyValue(opened, "replayRequired", true);
            assertKeyValue(opened, "persistedTerm", 4L);
            assertKeyValue(opened, "voted", true);
        } finally { await(second.closeAsync()); }
    }

    @Test void replaySummaryOfAnUncompactedLogCarriesTheTail() throws Exception {
        await(openWithEntries(entry(1, 1), entry(2, 5)).closeAsync());
        appender.list.clear();
        FileRaftStorage storage = open(dir);
        try {
            await(storage.replayLog());
            ILoggingEvent replayed = only("wal.replay.completed");
            assertKeyValue(replayed, "recoveredEntries", 2);
            assertKeyValue(replayed, "lastIndex", 2L);
            assertKeyValue(replayed, "lastTerm", 5L);
            assertKeyValue(replayed, "prefixBoundary", 0L);
            assertKeyValue(replayed, "boundarySource", "none");
        } finally { await(storage.closeAsync()); }
    }

    @Test void replaySummaryOfACompactedLogSaysTheBoundaryWasReadFromItsRecord() throws Exception {
        FileRaftStorage writer = openWithEntries(entry(1, 1), entry(2, 1), entry(3, 2));
        await(writer.truncatePrefix(3));
        await(writer.closeAsync());
        appender.list.clear();
        FileRaftStorage storage = open(dir);
        try {
            await(storage.replayLog());
            ILoggingEvent replayed = only("wal.replay.completed");
            assertKeyValue(replayed, "recoveredEntries", 0);
            assertKeyValue(replayed, "lastIndex", 3L);
            assertKeyValue(replayed, "lastTerm", -1L);
            assertKeyValue(replayed, "prefixBoundary", 3L);
            assertKeyValue(replayed, "boundarySource", "prefix-record");
        } finally { await(storage.closeAsync()); }
    }

    @Test void replaySummaryOfACompactedLogWithNoBoundaryRecordSaysTheBoundaryWasInferred() throws Exception {
        write(dir.resolve("raft.log"), record(APPEND, 6, 2, new byte[]{6}), record(APPEND, 7, 2, new byte[]{7}));
        FileRaftStorage storage = open(dir);
        try {
            await(storage.replayLog());
            ILoggingEvent replayed = only("wal.replay.completed");
            assertKeyValue(replayed, "lastIndex", 7L);
            assertKeyValue(replayed, "prefixBoundary", 5L);
            assertKeyValue(replayed, "boundarySource", "inferred");
        } finally { await(storage.closeAsync()); }
    }

    @Test void failedAppendSaysTheTailIsNowUnknownSoTheNextRefusalCanBeTracedToIt() throws Exception {
        FailingWrites io = new FailingWrites();
        FileRaftStorage storage = FileRaftStorage.unsafeWithoutFsyncForTesting(RaftStorageConfig.builder().build(), io);
        await(storage.open(dir));
        try {
            io.fail = true;
            assertThrows(ExecutionException.class, () -> await(storage.appendEntries(List.of(entry(1, 1)))));
            ILoggingEvent lost = only("wal.tail.unknown");
            assertEquals(Level.WARN, lost.getLevel());
            assertKeyValue(lost, "operation", "append");
            assertTrue(lost.getFormattedMessage().contains("replayLog()"), lost.getFormattedMessage());

            io.fail = false;
            appender.list.clear();
            refusalOf(storage.appendEntries(List.of(entry(1, 1))), WriteRejectionReason.LOG_STATE_UNKNOWN, "append");
        } finally { await(storage.closeAsync()); }
    }

    @Test void failedTruncationSaysTheTailIsNowUnknown() throws Exception {
        FailingWrites io = new FailingWrites();
        FileRaftStorage storage = FileRaftStorage.unsafeWithoutFsyncForTesting(RaftStorageConfig.builder().build(), io);
        await(storage.open(dir));
        try {
            await(storage.appendEntries(List.of(entry(1, 1), entry(2, 1))));
            io.fail = true;
            assertThrows(ExecutionException.class, () -> await(storage.truncateSuffix(2)));
            assertKeyValue(only("wal.tail.unknown"), "operation", "suffix-truncate");
        } finally { await(storage.closeAsync()); }
    }

    // ------------------------------------------------------------------ a file that is not a valid Raft log

    @Test void replayedLogWithAGapIsLogged() throws Exception {
        write(dir.resolve("raft.log"), record(APPEND, 1, 1, new byte[0]), record(APPEND, 3, 1, new byte[0]));
        assertInvalidLog("index-gap");
    }

    @Test void replayedLogWithATermRegressionIsLogged() throws Exception {
        write(dir.resolve("raft.log"), record(APPEND, 1, 5, new byte[0]), record(APPEND, 2, 4, new byte[0]));
        assertInvalidLog("term-regression");
    }

    @Test void replayedLogThatDoesNotStartAfterItsBoundaryIsLogged() throws Exception {
        write(dir.resolve("raft.log"), record(PREFIX, 5, 0, new byte[0]), record(APPEND, 9, 1, new byte[0]));
        assertInvalidLog("boundary-mismatch");
    }

    @Test void replayedLogWithABoundaryRecordThatIsNotFirstIsLogged() throws Exception {
        write(dir.resolve("raft.log"), record(APPEND, 1, 1, new byte[0]), record(PREFIX, 1, 0, new byte[0]));
        assertInvalidLog("prefix-not-first");
    }

    private void assertInvalidLog(String violation) throws Exception {
        FileRaftStorage storage = open(dir);
        try {
            assertThrows(ExecutionException.class, () -> await(storage.replayLog()));
            ILoggingEvent invalid = only("wal.replay.invalid");
            assertEquals(Level.ERROR, invalid.getLevel());
            assertKeyValue(invalid, "violation", violation);
        } finally { await(storage.closeAsync()); }
    }

    @Test void everyVerdictOnARecordThatCannotBeDecodedIsAWarningThatNamesTheDefect() throws Exception {
        byte[] whole = record(APPEND, 2, 1, new byte[64]);
        byte[] tornPayload = java.util.Arrays.copyOf(whole, whole.length - 10);
        write(dir.resolve("raft.log"), record(APPEND, 1, 1, new byte[0]), tornPayload);
        FileRaftStorage storage = open(dir);
        try {
            assertEquals(1, await(storage.replayLog()).size());
            ILoggingEvent verdict = only("wal.record.invalid");
            assertEquals(Level.WARN, verdict.getLevel());
            assertKeyValue(verdict, "defect", "payload-past-end-of-file");
            assertKeyValue(verdict, "position", (long) record(APPEND, 1, 1, new byte[0]).length);
        } finally { await(storage.closeAsync()); }
    }

    @Test void tornHeaderIsAWarningThatNamesTheDefect() throws Exception {
        write(dir.resolve("raft.log"), record(APPEND, 1, 1, new byte[0]), new byte[]{0x52, 0x41, 0x46});
        FileRaftStorage storage = open(dir);
        try {
            await(storage.replayLog());
            ILoggingEvent verdict = only("wal.record.invalid");
            assertEquals(Level.WARN, verdict.getLevel());
            assertKeyValue(verdict, "defect", "incomplete-header");
            assertKeyValue(only("wal.replay.tail_classified"), "incompleteEof", true);
            assertEquals(Level.DEBUG, only("wal.replay.validation_completed").getLevel());
        } finally { await(storage.closeAsync()); }
    }

    // ------------------------------------------------------------------ compaction

    @Test void debugEventsTraceAnOperationFromQueueToCompletion() throws Exception {
        FileRaftStorage storage = open(dir);
        try {
            appender.list.clear();
            await(storage.appendEntries(List.of(entry(1, 1))));
            ILoggingEvent queued = only("storage.operation.queued");
            ILoggingEvent started = only("storage.operation.started");
            ILoggingEvent completed = only("storage.operation.completed");
            assertKeyValue(queued, "operation", "append");
            assertKeyValue(started, "operation", "append");
            assertKeyValue(completed, "operation", "append");
            assertTrue(appender.list.indexOf(queued) < appender.list.indexOf(started));
            assertTrue(appender.list.indexOf(started) < appender.list.indexOf(completed));
            assertTrue(started.getKeyValuePairs().stream().anyMatch(pair ->
                    "queueWaitMicros".equals(pair.key) && ((Long) pair.value) >= 0));
            assertTrue(completed.getKeyValuePairs().stream().anyMatch(pair ->
                    "durationMicros".equals(pair.key) && ((Long) pair.value) >= 0));
        } finally { await(storage.closeAsync()); }
    }

    @Test void debugIncludesEachWrittenAndReplayedRecord() throws Exception {
        FileRaftStorage storage = open(dir);
        try {
            appender.list.clear();
            await(storage.appendEntries(List.of(entry(1, 1), entry(2, 1))));
            assertKeyValue(only("wal.append.validation_started"), "entryCount", 2);
            assertEquals(2, events("wal.append.entry_validated").size());
            assertKeyValue(only("wal.append.validation_completed"), "lastIndex", 2L);
            assertEquals(2, events("wal.record.write.started").size());
            assertEquals(2, events("wal.record.write.completed").size());
            assertEquals(2, events("wal.append.entry_written").size());

            appender.list.clear();
            await(storage.replayLog());
            assertEquals(2, events("wal.replay.append").size());
            assertTrue(events("wal.replay.append").stream()
                    .allMatch(event -> event.getLevel() == Level.DEBUG));
        } finally { await(storage.closeAsync()); }
    }

    @Test void debugEventsIdentifyEachCompactionPublicationStep() throws Exception {
        FileRaftStorage storage = openWithEntries(entry(1, 1), entry(2, 1));
        try {
            appender.list.clear();
            await(storage.truncatePrefix(1));
            List<String> steps = List.of("wal.compaction.scanned", "wal.compaction.staged",
                    "wal.compaction.source_closed", "wal.compaction.published",
                    "wal.compaction.directory_forced", "wal.compaction.reopened");
            int previous = -1;
            for (String step : steps) {
                int position = appender.list.indexOf(only(step));
                assertTrue(position > previous, () -> "compaction step out of order: " + step);
                previous = position;
            }
        } finally { await(storage.closeAsync()); }
    }

    @Test void compactionReportsTheBoundaryItStoredAndTheBytesItReclaimed() throws Exception {
        FileRaftStorage storage = openWithEntries(entry(1, 1), entry(2, 1), entry(3, 1), entry(4, 1));
        try {
            long before = Files.size(dir.resolve("raft.log"));
            await(storage.truncatePrefix(3));
            ILoggingEvent compacted = only("wal.compaction.completed");
            assertKeyValue(compacted, "requestedIndex", 3L);
            assertKeyValue(compacted, "boundary", 3L);
            assertKeyValue(compacted, "retainedEntries", 1);
            assertKeyValue(compacted, "bytesBefore", before);
            assertKeyValue(compacted, "bytesAfter", Files.size(dir.resolve("raft.log")));
        } finally { await(storage.closeAsync()); }
    }

    @Test void compactionBelowTheExistingBoundarySaysTheBoundaryStays() throws Exception {
        FileRaftStorage storage = openWithEntries(entry(1, 1), entry(2, 1), entry(3, 1), entry(4, 1));
        try {
            await(storage.truncatePrefix(3));
            appender.list.clear();
            await(storage.truncatePrefix(2));
            ILoggingEvent retained = only("wal.compaction.boundary_retained");
            assertEquals(Level.DEBUG, retained.getLevel());
            assertKeyValue(retained, "requestedIndex", 2L);
            assertKeyValue(retained, "boundary", 3L);
            ILoggingEvent compacted = only("wal.compaction.completed");
            assertKeyValue(compacted, "requestedIndex", 2L);
            assertKeyValue(compacted, "boundary", 3L);
        } finally { await(storage.closeAsync()); }
    }

    @Test void compactionThroughIndexZeroSaysItDidNothing() throws Exception {
        FileRaftStorage storage = openWithEntries(entry(1, 1));
        try (var ignoredUntouched = DurableState.expectUnchanged(dir)) {
            await(storage.truncatePrefix(0));
            ILoggingEvent skipped = only("wal.compaction.skipped");
            assertEquals(Level.DEBUG, skipped.getLevel());
            assertTrue(events("wal.compaction.completed").isEmpty());
        } finally { await(storage.closeAsync()); }
    }

    // ------------------------------------------------------------------ one style

    /**
     * Every statement at DEBUG or above carries a stable {@code event} key, so that an operation's
     * start and per-record detail can be filtered exactly like its completion.
     */
    @Test void everyLogStatementAtDebugOrAboveCarriesAnEventKey() throws Exception {
        Path sources = Path.of("src/main/java/dev/mars/raftlog/storage");
        assertTrue(Files.isDirectory(sources), "run from the module directory: " + sources.toAbsolutePath());
        Pattern plain = Pattern.compile("LOG\\s*\\.\\s*(debug|info|warn|error)\\s*\\(");
        Pattern fluent = Pattern.compile("LOG\\s*\\.\\s*at(Debug|Info|Warn|Error)\\(\\)(\\s*\\.addKeyValue\\(\"event\", )?");
        List<String> offenders = new ArrayList<>();
        int statements = 0;
        try (Stream<Path> files = Files.list(sources)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                Matcher m = plain.matcher(source);
                while (m.find()) offenders.add(file.getFileName() + ":" + lineOf(source, m.start()) + " plain " + m.group(1));
                m = fluent.matcher(source);
                while (m.find()) {
                    statements++;
                    if (m.group(2) == null) offenders.add(file.getFileName() + ":" + lineOf(source, m.start()) + " has no event key");
                }
            }
        }
        assertTrue(statements > 40, "the scan found only " + statements + " statements, so it is not looking at the sources");
        assertEquals(List.of(), offenders);
    }

    /**
     * Nothing in the project writes to the console directly: not the library, not the demo, not the
     * tests, not the scripts. Output that bypasses the logger has no level, no timestamp and no
     * source, and cannot be turned up to DEBUG or down to ERROR. The one permitted reference is the
     * scripts' own logger set-up, which has to name the stream it writes to.
     */
    @Test void nothingInTheProjectWritesToTheConsoleDirectly() throws Exception {
        Path root = Path.of("..").toAbsolutePath().normalize();
        Pattern console = Pattern.compile("System\\s*\\.\\s*(out|err)\\b|\\.printStackTrace\\s*\\(");
        List<String> offenders = new ArrayList<>();
        int scanned = 0;
        for (String tree : List.of("raftlog-core/src", "raftlog-demo/src", "scripts")) {
            assertTrue(Files.isDirectory(root.resolve(tree)), "missing " + root.resolve(tree));
            try (Stream<Path> files = Files.walk(root.resolve(tree))) {
                for (Path file : files.filter(f -> f.toString().endsWith(".java") || f.toString().endsWith(".java.txt")).toList()) {
                    scanned++;
                    if (file.getFileName().toString().equals("ScriptLog.java")) continue;
                    String source = Files.readString(file);
                    Matcher m = console.matcher(source);
                    while (m.find()) offenders.add(root.relativize(file).toString().replace('\\', '/') + ":" + lineOf(source, m.start()));
                }
            }
        }
        assertTrue(scanned > 40, "the scan found only " + scanned + " files, so it is not looking at the project");
        assertEquals(List.of(), offenders);
    }

    private static int lineOf(String source, int offset) {
        return (int) source.substring(0, offset).chars().filter(c -> c == '\n').count() + 1;
    }

    // ------------------------------------------------------------------ support

    /** The refusal must be logged exactly once, at ERROR, and must be the only error: the storage is not fenced. */
    private ILoggingEvent refusalOf(CompletableFuture<?> refused, WriteRejectionReason reason, String operation) {
        ExecutionException failure = assertThrows(ExecutionException.class, () -> await(refused));
        var rejected = assertInstanceOf(FileRaftStorage.WriteRejectedException.class, failure.getCause());
        assertEquals(reason, rejected.reason());
        ILoggingEvent refusal = only("storage.write.rejected");
        assertEquals(Level.ERROR, refusal.getLevel());
        assertKeyValue(refusal, "reason", reason.name());
        assertKeyValue(refusal, "operation", operation);
        assertTrue(refusal.getFormattedMessage().contains(rejected.getMessage()),
                () -> "the log must carry what the caller was told: " + refusal.getFormattedMessage());
        assertEquals(List.of(refusal.getFormattedMessage()), appender.list.stream().filter(e -> e.getLevel() == Level.ERROR)
                .map(ILoggingEvent::getFormattedMessage).toList(), "the refusal is the only error: nothing failed and nothing is fenced");
        assertTrue(events("storage.fenced").isEmpty(), "a refusal does not fence the storage");
        return refusal;
    }

    private List<ILoggingEvent> events(String event) {
        return List.copyOf(appender.list).stream()
                .filter(e -> e.getKeyValuePairs() != null && e.getKeyValuePairs().stream()
                        .anyMatch(pair -> "event".equals(pair.key) && event.equals(pair.value)))
                .toList();
    }

    private ILoggingEvent only(String event) {
        List<ILoggingEvent> found = events(event);
        assertEquals(1, found.size(), () -> "expected exactly one " + event + " in "
                + appender.list.stream().map(e -> e.getLevel() + " " + e.getKeyValuePairs() + " " + e.getFormattedMessage()).toList());
        return found.getFirst();
    }

    private static void assertKeyValue(ILoggingEvent event, String key, Object value) {
        assertTrue(event.getKeyValuePairs().stream().anyMatch(pair -> key.equals(pair.key) && value.equals(pair.value)),
                () -> "Expected " + key + "=" + value + " in " + event.getKeyValuePairs());
    }

    private static final class Disk extends CompactionIo {
        volatile boolean full;
        @Override long usableSpace(Path directory) {
            return full ? 1024 : Long.MAX_VALUE;
        }
    }

    private static final class FailingWrites extends CompactionIo {
        volatile boolean fail;
        @Override void writeRecord(FileChannel channel, ByteBuffer record) throws IOException {
            if (fail) throw new IOException("Injected write failure");
            super.writeRecord(channel, record);
        }
    }

    private FileRaftStorage openWithEntries(LogEntryData... entries) throws Exception {
        FileRaftStorage storage = open(dir);
        await(storage.appendEntries(List.of(entries)));
        return storage;
    }

    private static FileRaftStorage open(Path dir) throws Exception {
        FileRaftStorage storage = FileRaftStorage.unsafeWithoutFsyncForTesting(false);
        await(storage.open(dir));
        return storage;
    }

    private static LogEntryData entry(long index, long term) {
        return new LogEntryData(index, term, new byte[]{(byte) index});
    }

    private static final byte APPEND = 2;
    private static final byte PREFIX = 3;

    /** Encodes a record independently of the storage, so a file the storage would never write can be built. */
    private static byte[] record(byte type, long index, long term, byte[] payload) {
        ByteBuffer buffer = ByteBuffer.allocate(27 + payload.length + 4);
        buffer.putInt(0x52414654).putShort((short) (type == PREFIX ? 2 : 1)).put(type)
                .putLong(index).putLong(term).putInt(payload.length).put(payload);
        CRC32C crc = new CRC32C();
        crc.update(buffer.array(), 0, 27 + payload.length);
        buffer.putInt((int) crc.getValue());
        return buffer.array();
    }

    private static void write(Path file, byte[]... records) throws IOException {
        for (byte[] bytes : records) {
            Files.write(file, bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
    }

    private static <T> T await(CompletableFuture<T> future) throws Exception {
        return future.get(10, TimeUnit.SECONDS);
    }
}
