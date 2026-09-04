package dev.mars.raftlog.storage;

import dev.mars.raftlog.storage.RaftStorage.LogEntryData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The journal is append-only, so a caller that retransmits an entry, or a crash that lets a
 * writer repeat one, can leave two APPEND records for the same index. Replay must resolve
 * records by index the way it already resolves TRUNCATE records: a repeated record with the
 * same term is the same entry under the Raft log matching property and is ignored, and a record
 * with a different term is a later write that supersedes the tail from that index.
 */
class FileRaftStorageReplayIdempotenceTest {

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
    @DisplayName("a repeated APPEND record with the same term is ignored")
    void replayIgnoresSameTermDuplicateRecord() throws Exception {
        append(1, 1);
        append(2, 1);
        append(1, 1);

        List<LogEntryData> replayed = storage.replayLog().get(5, TimeUnit.SECONDS);

        assertEquals(List.of(1L, 2L), indices(replayed));
        assertEquals(List.of(1L, 1L), terms(replayed));
    }

    @Test
    @DisplayName("an APPEND record with a different term supersedes the tail from its index")
    void replayLetsDifferentTermRecordSupersedeTail() throws Exception {
        append(1, 1);
        append(2, 1);
        append(3, 1);
        append(2, 2);

        List<LogEntryData> replayed = storage.replayLog().get(5, TimeUnit.SECONDS);

        assertEquals(List.of(1L, 2L), indices(replayed));
        assertEquals(List.of(1L, 2L), terms(replayed));
    }

    @Test
    @DisplayName("resolution by index survives close and reopen")
    void replayResolutionSurvivesReopen() throws Exception {
        append(1, 1);
        append(2, 1);
        append(1, 1);
        storage.sync().get(5, TimeUnit.SECONDS);
        storage.close();

        storage = new FileRaftStorage(true);
        storage.open(tempDir).get(5, TimeUnit.SECONDS);
        List<LogEntryData> replayed = storage.replayLog().get(5, TimeUnit.SECONDS);

        assertEquals(List.of(1L, 2L), indices(replayed));
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
