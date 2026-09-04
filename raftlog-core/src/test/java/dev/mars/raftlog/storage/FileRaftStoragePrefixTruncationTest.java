package dev.mars.raftlog.storage;

import dev.mars.raftlog.storage.RaftStorage.LogEntryData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prefix truncation is how a Raft node compacts its log after a snapshot: every entry at or
 * below the snapshot index is removed from disk while later entries, and the ability to keep
 * appending, are preserved. Without it the journal grows without bound.
 */
class FileRaftStoragePrefixTruncationTest {

    @TempDir
    Path tempDir;

    private FileRaftStorage storage;

    @BeforeEach
    void setUp() throws Exception {
        storage = new FileRaftStorage(true);
        storage.open(tempDir).get(5, TimeUnit.SECONDS);
    }

    @AfterEach
    void tearDown() {
        if (storage != null) {
            storage.close();
        }
    }

    @Test
    @DisplayName("entries at or below the index are removed and later entries are kept intact")
    void truncatePrefixRemovesEntriesUpToIndex() throws Exception {
        append(1, 1);
        append(2, 1);
        append(3, 2);
        append(4, 2);
        storage.sync().get(5, TimeUnit.SECONDS);

        storage.truncatePrefix(2).get(5, TimeUnit.SECONDS);

        List<LogEntryData> replayed = storage.replayLog().get(5, TimeUnit.SECONDS);
        assertEquals(List.of(3L, 4L), indices(replayed));
        assertEquals(List.of(2L, 2L), terms(replayed));
        assertEquals("index-3-term-2", new String(replayed.get(0).payload()));
    }

    @Test
    @DisplayName("the compacted log shrinks on disk, survives reopen, and accepts further appends")
    void truncatePrefixSurvivesReopenAndFurtherAppends() throws Exception {
        for (long index = 1; index <= 4; index++) {
            append(index, 1);
        }
        storage.sync().get(5, TimeUnit.SECONDS);
        long sizeBefore = Files.size(tempDir.resolve("raft.log"));

        storage.truncatePrefix(2).get(5, TimeUnit.SECONDS);
        storage.sync().get(5, TimeUnit.SECONDS);
        long sizeAfter = Files.size(tempDir.resolve("raft.log"));
        assertTrue(sizeAfter < sizeBefore, "compaction must release disk space");
        storage.close();

        storage = new FileRaftStorage(true);
        storage.open(tempDir).get(5, TimeUnit.SECONDS);
        assertEquals(List.of(3L, 4L), indices(storage.replayLog().get(5, TimeUnit.SECONDS)));

        append(5, 1);
        storage.sync().get(5, TimeUnit.SECONDS);
        assertEquals(List.of(3L, 4L, 5L), indices(storage.replayLog().get(5, TimeUnit.SECONDS)));
    }

    @Test
    @DisplayName("truncating at or beyond the last index empties the log without losing later appends")
    void truncatePrefixBeyondLastIndexEmptiesLog() throws Exception {
        append(1, 1);
        append(2, 1);
        append(3, 1);
        storage.sync().get(5, TimeUnit.SECONDS);

        storage.truncatePrefix(3).get(5, TimeUnit.SECONDS);
        assertEquals(List.of(), indices(storage.replayLog().get(5, TimeUnit.SECONDS)));

        append(4, 1);
        storage.sync().get(5, TimeUnit.SECONDS);
        assertEquals(List.of(4L), indices(storage.replayLog().get(5, TimeUnit.SECONDS)));

        storage.truncatePrefix(10).get(5, TimeUnit.SECONDS);
        assertEquals(List.of(), indices(storage.replayLog().get(5, TimeUnit.SECONDS)));
    }

    @Test
    @DisplayName("truncating below the first index changes nothing")
    void truncatePrefixBelowFirstIndexIsNoOp() throws Exception {
        append(1, 1);
        append(2, 1);
        storage.sync().get(5, TimeUnit.SECONDS);
        long sizeBefore = Files.size(tempDir.resolve("raft.log"));

        storage.truncatePrefix(0).get(5, TimeUnit.SECONDS);

        assertEquals(List.of(1L, 2L), indices(storage.replayLog().get(5, TimeUnit.SECONDS)));
        assertEquals(sizeBefore, Files.size(tempDir.resolve("raft.log")));
    }

    @Test
    @DisplayName("compaction resolves earlier suffix truncations instead of carrying their records")
    void truncatePrefixResolvesEarlierSuffixTruncation() throws Exception {
        append(1, 1);
        append(2, 1);
        append(3, 1);
        append(4, 1);
        storage.truncateSuffix(3).get(5, TimeUnit.SECONDS);
        append(3, 2);
        storage.sync().get(5, TimeUnit.SECONDS);

        storage.truncatePrefix(1).get(5, TimeUnit.SECONDS);

        List<LogEntryData> replayed = storage.replayLog().get(5, TimeUnit.SECONDS);
        assertEquals(List.of(2L, 3L), indices(replayed));
        assertEquals(List.of(1L, 2L), terms(replayed));
    }

    private void append(long index, long term) throws Exception {
        byte[] payload = ("index-" + index + "-term-" + term).getBytes();
        storage.appendEntries(List.of(new LogEntryData(index, term, payload))).get(5, TimeUnit.SECONDS);
    }

    private static List<Long> indices(List<LogEntryData> entries) {
        return entries.stream().map(LogEntryData::index).toList();
    }

    private static List<Long> terms(List<LogEntryData> entries) {
        return entries.stream().map(LogEntryData::term).toList();
    }
}
