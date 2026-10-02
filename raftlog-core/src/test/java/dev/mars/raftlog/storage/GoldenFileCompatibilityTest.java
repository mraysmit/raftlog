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
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Data directories already on disk must keep working.
 * <p>
 * The fixtures under {@code src/test/resources/golden} are organised by WAL record format, which
 * is what decides whether a file can be read, not by which build wrote it:
 * <ul>
 *   <li>{@code format-1}: APPEND and TRUNCATE records only. A compacted log in this format has no
 *       boundary record and simply begins above index 1. Written by a published jar downloaded
 *       from Maven Central and verified against its published checksum;</li>
 *   <li>{@code format-2}: the current format, in which a compacted log begins with a PREFIX
 *       record carrying the boundary. Written by the current build.</li>
 * </ul>
 * {@code GoldenFileGenerator.java.txt} beside the fixtures is the program that produced them. Each
 * scenario carries {@code expected.txt}: the replay recorded by the jar that wrote the files. That
 * record, not anyone's expectation, is the oracle here.
 * <p>
 * Two contracts are pinned:
 * <ul>
 *   <li>a directory holding a well-formed log replays exactly as recorded, byte for byte in the
 *       payloads, keeps its metadata, is not modified by replay, and can be continued and
 *       restarted;</li>
 *   <li>a directory holding a log that is not a valid Raft log (a gap, a duplicate index, a term
 *       regression), which format 1 could contain, is refused by replay without being modified.</li>
 * </ul>
 */
class GoldenFileCompatibilityTest {
    /** Logs that format 1 could contain and that are not valid Raft logs. */
    private static final Set<String> MALFORMED =
            Set.of("permissive-gap", "permissive-duplicate-index", "permissive-term-regression");

    @TempDir Path work;

    private static Path goldenRoot() throws Exception {
        return Path.of(GoldenFileCompatibilityTest.class.getResource("/golden/SHA256SUMS").toURI()).getParent();
    }

    static Stream<String> wellFormedScenarios() throws Exception { return scenarios(false); }
    static Stream<String> malformedScenarios() throws Exception { return scenarios(true); }

    private static Stream<String> scenarios(boolean malformed) throws Exception {
        List<String> found = new ArrayList<>();
        for (String format : List.of("format-1", "format-2")) {
            try (Stream<Path> dirs = Files.list(goldenRoot().resolve(format))) {
                dirs.filter(Files::isDirectory).map(d -> format + "/" + d.getFileName())
                        .filter(s -> MALFORMED.contains(s.substring(s.indexOf('/') + 1)) == malformed).forEach(found::add);
            }
        }
        found.sort(null);
        return found.stream();
    }

    // ------------------------------------------------------------------ fixture handling

    private record Expected(List<LogEntryData> entries, PersistentMeta meta) { }

    private static Expected expected(Path scenario) throws Exception {
        Map<String, String> p = new TreeMap<>();
        for (String line : Files.readAllLines(scenario.resolve("expected.txt"))) {
            int eq = line.indexOf('=');
            if (eq > 0) p.put(line.substring(0, eq), line.substring(eq + 1).strip());
        }
        assertEquals("OK", p.get("replay"), "the jar that wrote the fixture replayed it successfully");
        List<LogEntryData> entries = new ArrayList<>();
        for (int i = 0; i < Integer.parseInt(p.get("entries")); i++) {
            String[] parts = p.get("entry." + i).split(",", -1);
            entries.add(new LogEntryData(Long.parseLong(parts[0]), Long.parseLong(parts[1]),
                    Base64.getDecoder().decode(parts[2])));
        }
        String vote = p.get("meta.vote");
        Optional<String> votedFor = vote.equals("NONE") ? Optional.empty()
                : Optional.of(new String(Base64.getDecoder().decode(vote.substring(4)), StandardCharsets.UTF_8));
        return new Expected(entries, new PersistentMeta(Long.parseLong(p.get("meta.term")), votedFor));
    }

    /** Fixtures are never opened in place: the storage takes a lock and may repair a torn tail. */
    private Path copyOf(String scenario) throws Exception {
        Path source = goldenRoot().resolve(scenario);
        Path target = Files.createDirectories(work.resolve(scenario.replace('/', '-')));
        for (String file : List.of("raft.log", "meta.dat")) {
            if (Files.exists(source.resolve(file))) Files.copy(source.resolve(file), target.resolve(file));
        }
        return target;
    }

    private static <T> T await(CompletableFuture<T> future) throws Exception {
        return future.get(20, TimeUnit.SECONDS);
    }

    private static FileRaftStorage open(Path dir) throws Exception {
        FileRaftStorage storage = new FileRaftStorage(RaftStorageConfig.builder().build());
        await(storage.open(dir));
        return storage;
    }

    private static void assertSameLog(List<LogEntryData> expected, List<LogEntryData> actual, String what) {
        assertEquals(expected.size(), actual.size(), what + ": entry count");
        for (int i = 0; i < expected.size(); i++) {
            assertEquals(expected.get(i).index(), actual.get(i).index(), what + ": index at " + i);
            assertEquals(expected.get(i).term(), actual.get(i).term(), what + ": term at " + i);
            assertArrayEquals(expected.get(i).payload(), actual.get(i).payload(), what + ": payload at " + i);
        }
    }

    // ------------------------------------------------------------------ the fixtures themselves

    @Test void everyFormatHasItsFullSetOfScenarios() throws Exception {
        for (var expected : java.util.Map.of("format-1", 13L, "format-2", 8L).entrySet()) {
            try (Stream<Path> dirs = Files.list(goldenRoot().resolve(expected.getKey()))) {
                assertEquals(expected.getValue(), dirs.filter(Files::isDirectory).count(), expected.getKey() + " scenarios");
            }
        }
    }

    @Test void fixturesAreByteIdenticalToWhatThePublishedJarsWrote() throws Exception {
        // Guards against a checkout rewriting line endings inside a binary file.
        int verified = 0;
        for (String line : Files.readAllLines(goldenRoot().resolve("SHA256SUMS"))) {
            if (line.isBlank()) continue;
            String[] parts = line.strip().split("\\s+", 2);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(goldenRoot().resolve(parts[1])));
            assertEquals(parts[0], HexFormat.of().formatHex(digest), "fixture was altered: " + parts[1]);
            verified++;
        }
        long onDisk;
        try (Stream<Path> files = Files.walk(goldenRoot())) {
            onDisk = files.filter(f -> List.of("raft.log", "meta.dat").contains(f.getFileName().toString())).count();
        }
        assertTrue(verified > 0, "no fixture was verified");
        assertEquals(onDisk, verified, "every binary fixture must be listed in SHA256SUMS");
    }

    // ------------------------------------------------------------------ contract 1: well-formed logs keep working

    @ParameterizedTest(name = "{0}")
    @MethodSource("wellFormedScenarios")
    void directoryFromAPublishedReleaseReplaysExactlyAsThatReleaseDidAndCanBeContinued(String scenario) throws Exception {
        Expected old = expected(goldenRoot().resolve(scenario));
        Path dir = copyOf(scenario);
        boolean tornTail = scenario.endsWith("/torn-tail");

        FileRaftStorage storage = open(dir);
        try {
            // Replay must not rewrite an old file. The one exception is repairing a torn tail.
            List<LogEntryData> replayed;
            if (tornTail) {
                replayed = await(storage.replayLog());
            } else {
                try (var ignoredUntouched = DurableState.expectUnchanged(dir)) {
                    replayed = await(storage.replayLog());
                }
            }
            assertSameLog(old.entries(), replayed, scenario + " replay");
            assertEquals(old.meta(), await(storage.loadMetadata()), scenario + " metadata");

            // The log continues where the fixture left it, and the metadata baseline holds.
            long next = replayed.isEmpty() ? await(storage.compactionBoundary()) + 1 : replayed.getLast().index() + 1;
            long term = replayed.isEmpty() ? 1 : replayed.getLast().term();
            LogEntryData added = new LogEntryData(next, term, "written-by-the-new-build".getBytes(StandardCharsets.UTF_8));
            await(storage.appendEntries(List.of(added)));
            await(storage.updateMetadata(old.meta().currentTerm(), old.meta().votedFor()));
            await(storage.sync());

            List<LogEntryData> continued = new ArrayList<>(old.entries());
            continued.add(added);
            assertSameLog(continued, DurableState.assertRestartAgrees(storage, dir), scenario + " after restart");
        } finally { await(storage.closeAsync()); }
    }

    @Test void compactedLogWithNoBoundaryRecordHasItsBoundaryInferredSoTheCompactedPrefixStaysProtected() throws Exception {
        // Format 1 has no boundary record: the compacted file simply starts at entry 6.
        // Entries 1 to 5 are in the node's snapshot. Nothing may be written back into that range.
        Path dir = copyOf("format-1/compacted-with-retained-entries");
        FileRaftStorage storage = open(dir);
        try {
            List<LogEntryData> replayed = await(storage.replayLog());
            assertEquals(6, replayed.getFirst().index());
            assertEquals(5L, await(storage.compactionBoundary()), "a log that starts at 6 was compacted through 5");
            try (var ignoredUntouched = DurableState.expectUnchanged(dir)) {
                for (long into : new long[]{1, 3, 5}) {
                    var rejected = assertInstanceOf(FileRaftStorage.WriteRejectedException.class,
                            assertThrows(ExecutionException.class, () -> await(storage.truncateSuffix(into))).getCause());
                    assertEquals(WriteRejectionReason.INVALID_TRUNCATION, rejected.reason());
                }
            }
            // Truncating the whole retained log is legal, and the log then continues at 6, not below.
            await(storage.truncateSuffix(6));
            var rejected = assertInstanceOf(FileRaftStorage.WriteRejectedException.class,
                    assertThrows(ExecutionException.class,
                            () -> await(storage.appendEntries(List.of(new LogEntryData(3, 2, new byte[0]))))).getCause());
            assertEquals(WriteRejectionReason.INDEX_NOT_CONTIGUOUS, rejected.reason());
            await(storage.appendEntries(List.of(new LogEntryData(6, 2, new byte[]{1}))));
        } finally { await(storage.closeAsync()); }
        assertEquals(6, DurableState.replayAfterRestart(dir).getFirst().index());
    }

    @Test void nextCompactionOfAFormatOneLogWritesTheBoundaryRecordWithoutLosingTheInferredBoundary() throws Exception {
        Path dir = copyOf("format-1/compacted-with-retained-entries");
        FileRaftStorage storage = open(dir);
        try {
            await(storage.replayLog());
            await(storage.truncatePrefix(7));
            assertEquals(7L, await(storage.compactionBoundary()));
        } finally { await(storage.closeAsync()); }
        FileRaftStorage reopened = open(dir);
        try {
            assertEquals(8, await(reopened.replayLog()).getFirst().index());
            assertEquals(7L, await(reopened.compactionBoundary()), "now persisted by the marker");
        } finally { await(reopened.closeAsync()); }
        assertEquals(WalRecords.PREFIX, WalRecords.read(dir.resolve("raft.log")).getFirst().type(), "upgraded to the current format");
    }

    // ------------------------------------------------------------------ the current format

    @Test void compactedLogInTheCurrentFormatKeepsItsBoundaryEvenWhenNothingWasRetained() throws Exception {
        Path dir = copyOf("format-2/compacted-to-empty");
        WalRecords.Raw first = WalRecords.read(dir.resolve("raft.log")).getFirst();
        assertEquals(WalRecords.PREFIX, first.type());
        assertEquals(3, first.index());

        FileRaftStorage storage = open(dir);
        try {
            assertTrue(await(storage.replayLog()).isEmpty());
            assertEquals(3L, await(storage.compactionBoundary()), "read back from the PREFIX record");
            try (var ignoredUntouched = DurableState.expectUnchanged(dir)) {
                for (long wrong : new long[]{1, 3, 5}) {
                    var rejected = assertInstanceOf(FileRaftStorage.WriteRejectedException.class,
                            assertThrows(ExecutionException.class,
                                    () -> await(storage.appendEntries(List.of(new LogEntryData(wrong, 2, new byte[0]))))).getCause());
                    assertEquals(WriteRejectionReason.INDEX_NOT_CONTIGUOUS, rejected.reason());
                }
            }
            await(storage.appendEntries(List.of(new LogEntryData(4, 2, new byte[]{4}))));
        } finally { await(storage.closeAsync()); }
        assertEquals(4, DurableState.replayAfterRestart(dir).getFirst().index());
    }

    @Test void compactedLogInTheCurrentFormatBeginsWithItsBoundaryRecord() throws Exception {
        Path dir = copyOf("format-2/compacted-with-retained-entries");
        List<WalRecords.Raw> records = WalRecords.read(dir.resolve("raft.log"));
        assertEquals(WalRecords.PREFIX, records.getFirst().type());
        assertEquals(5, records.getFirst().index());
        assertEquals(1, records.stream().filter(r -> r.type() == WalRecords.PREFIX).count());
        FileRaftStorage storage = open(dir);
        try {
            assertEquals(6, await(storage.replayLog()).getFirst().index());
            assertEquals(5L, await(storage.compactionBoundary()));
        } finally { await(storage.closeAsync()); }
    }

    // ------------------------------------------------------------------ contract 2: malformed logs are refused, untouched

    @ParameterizedTest(name = "{0}")
    @MethodSource("malformedScenarios")
    void logThatIsNotAValidRaftLogIsRefusedWithoutBeingModified(String scenario) throws Exception {
        Path dir = copyOf(scenario);
        FileRaftStorage storage = open(dir);
        try (var ignoredUntouched = DurableState.expectUnchanged(dir)) {
            Throwable cause = assertThrows(ExecutionException.class, () -> await(storage.replayLog())).getCause();
            assertInstanceOf(FileRaftStorage.StorageException.class, cause);
            assertFalse(cause instanceof FileRaftStorage.CorruptLogException,
                    scenario + ": the records are intact, so this is a malformed log and not disk corruption");
            var refused = assertInstanceOf(FileRaftStorage.WriteRejectedException.class,
                    assertThrows(ExecutionException.class,
                            () -> await(storage.appendEntries(List.of(new LogEntryData(1, 1, new byte[0]))))).getCause());
            assertEquals(WriteRejectionReason.LOG_STATE_UNKNOWN, refused.reason());
            // Metadata is independent of the log and must stay readable.
            assertDoesNotThrow(() -> await(storage.loadMetadata()));
        } finally { await(storage.closeAsync()); }
    }
}
