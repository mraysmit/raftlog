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

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The accounting used by the concurrent tests must itself notice every unexplained byte. */
class WalRecordsTest {
    @TempDir Path dir;

    private static LogEntryData entry(long index, long term) {
        return new LogEntryData(index, term, ("payload-" + index).getBytes());
    }

    /** Writes 1..3, truncates from 3, appends 3 again; returns what was accepted. */
    private List<WalRecords.Raw> writeKnownLog() throws Exception {
        List<WalRecords.Raw> accepted = new ArrayList<>();
        FileRaftStorage storage = new FileRaftStorage(RaftStorageConfig.builder().build());
        storage.open(dir).get(10, TimeUnit.SECONDS);
        try {
            List<LogEntryData> batch = List.of(entry(1, 1), entry(2, 1), entry(3, 1));
            storage.appendEntries(batch).get(10, TimeUnit.SECONDS);
            batch.forEach(e -> accepted.add(WalRecords.append(e)));
            storage.truncateSuffix(3).get(10, TimeUnit.SECONDS);
            accepted.add(WalRecords.truncate(3));
            storage.appendEntries(List.of(entry(3, 2))).get(10, TimeUnit.SECONDS);
            accepted.add(WalRecords.append(entry(3, 2)));
        } finally { storage.close(); }
        return accepted;
    }

    private Path wal() { return dir.resolve("raft.log"); }

    @Test void independentReaderAgreesWithWhatTheStorageWrote() throws Exception {
        List<WalRecords.Raw> accepted = writeKnownLog();
        assertEquals(5, WalRecords.read(wal()).size());
        assertDoesNotThrow(() -> WalRecords.assertExactly(wal(), accepted));
        assertDoesNotThrow(() -> WalRecords.assertNoStrayFiles(dir));
    }

    @Test void recordNobodyAcceptedIsDetected() throws Exception {
        List<WalRecords.Raw> accepted = writeKnownLog();
        accepted.removeLast();                       // the file now holds one record too many
        assertThrows(AssertionError.class, () -> WalRecords.assertExactly(wal(), accepted));
    }

    @Test void acceptedRecordMissingFromTheFileIsDetected() throws Exception {
        List<WalRecords.Raw> accepted = writeKnownLog();
        accepted.add(WalRecords.append(entry(4, 2)));
        assertThrows(AssertionError.class, () -> WalRecords.assertExactly(wal(), accepted));
    }

    @Test void duplicateRecordIsDetected() throws Exception {
        List<WalRecords.Raw> accepted = writeKnownLog();
        byte[] all = Files.readAllBytes(wal());
        int firstRecord = 27 + "payload-1".length() + 4;
        Files.write(wal(), java.util.Arrays.copyOf(all, firstRecord), StandardOpenOption.APPEND);
        assertThrows(AssertionError.class, () -> WalRecords.assertExactly(wal(), accepted));
    }

    @Test void differentPayloadAtTheSameIndexIsDetected() throws Exception {
        List<WalRecords.Raw> accepted = writeKnownLog();
        accepted.set(0, WalRecords.append(new LogEntryData(1, 1, "other".getBytes())));
        assertThrows(AssertionError.class, () -> WalRecords.assertExactly(wal(), accepted));
    }

    @Test void trailingBytesAreDetected() throws Exception {
        List<WalRecords.Raw> accepted = writeKnownLog();
        Files.write(wal(), new byte[]{0}, StandardOpenOption.APPEND);
        assertThrows(AssertionError.class, () -> WalRecords.assertExactly(wal(), accepted));
    }

    @Test void damagedRecordIsDetected() throws Exception {
        List<WalRecords.Raw> accepted = writeKnownLog();
        byte[] all = Files.readAllBytes(wal());
        all[30] ^= 1;
        Files.write(wal(), all);
        assertThrows(AssertionError.class, () -> WalRecords.assertExactly(wal(), accepted));
    }

    @Test void leftoverStagingFileIsDetected() throws Exception {
        writeKnownLog();
        Files.write(dir.resolve("meta.dat.tmp"), new byte[]{1});
        assertThrows(AssertionError.class, () -> WalRecords.assertNoStrayFiles(dir));
    }
}
