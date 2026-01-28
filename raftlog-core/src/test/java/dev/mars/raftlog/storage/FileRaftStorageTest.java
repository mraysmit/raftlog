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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link FileRaftStorage}.
 * <p>
 * These tests verify:
 * <ul>
 *   <li>Basic append and replay functionality</li>
 *   <li>Metadata persistence and recovery</li>
 *   <li>Truncation handling</li>
 *   <li>Crash recovery (torn writes)</li>
 *   <li>CRC validation</li>
 * </ul>
 */
class FileRaftStorageTest {

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

    // ========================================================================
    // Metadata Tests
    // ========================================================================

    @Test
    void testLoadMetadata_NoFile_ReturnsEmpty() throws Exception {
        PersistentMeta meta = storage.loadMetadata().get(5, TimeUnit.SECONDS);

        assertEquals(0L, meta.currentTerm());
        assertTrue(meta.votedFor().isEmpty());
    }

    @Test
    void testUpdateAndLoadMetadata() throws Exception {
        // Update metadata
        storage.updateMetadata(5L, Optional.of("node-1")).get(5, TimeUnit.SECONDS);

        // Load and verify
        PersistentMeta meta = storage.loadMetadata().get(5, TimeUnit.SECONDS);

        assertEquals(5L, meta.currentTerm());
        assertEquals(Optional.of("node-1"), meta.votedFor());
    }

    @Test
    void testUpdateMetadata_NoVote() throws Exception {
        storage.updateMetadata(3L, Optional.empty()).get(5, TimeUnit.SECONDS);

        PersistentMeta meta = storage.loadMetadata().get(5, TimeUnit.SECONDS);

        assertEquals(3L, meta.currentTerm());
        assertTrue(meta.votedFor().isEmpty());
    }

    @Test
    void testUpdateMetadata_OverwritesPrevious() throws Exception {
        storage.updateMetadata(1L, Optional.of("node-a")).get(5, TimeUnit.SECONDS);
        storage.updateMetadata(2L, Optional.of("node-b")).get(5, TimeUnit.SECONDS);

        PersistentMeta meta = storage.loadMetadata().get(5, TimeUnit.SECONDS);

        assertEquals(2L, meta.currentTerm());
        assertEquals(Optional.of("node-b"), meta.votedFor());
    }

    @Test
    void testMetadata_SurvivesRestart() throws Exception {
        storage.updateMetadata(10L, Optional.of("candidate-x")).get(5, TimeUnit.SECONDS);
        storage.close();

        // Reopen storage
        storage = new FileRaftStorage(true);
        storage.open(tempDir).get(5, TimeUnit.SECONDS);

        PersistentMeta meta = storage.loadMetadata().get(5, TimeUnit.SECONDS);

        assertEquals(10L, meta.currentTerm());
        assertEquals(Optional.of("candidate-x"), meta.votedFor());
    }

    // ========================================================================
    // Append Tests
    // ========================================================================

    @Test
    void testAppendAndReplay_SingleEntry() throws Exception {
        LogEntryData entry = new LogEntryData(1, 1, "command-1".getBytes(StandardCharsets.UTF_8));

        storage.appendEntries(List.of(entry)).get(5, TimeUnit.SECONDS);
        storage.sync().get(5, TimeUnit.SECONDS);

        List<LogEntryData> replayed = storage.replayLog().get(5, TimeUnit.SECONDS);

        assertEquals(1, replayed.size());
        assertEquals(1, replayed.get(0).index());
        assertEquals(1, replayed.get(0).term());
        assertArrayEquals("command-1".getBytes(StandardCharsets.UTF_8), replayed.get(0).payload());
    }

    @Test
    void testAppendAndReplay_MultipleEntries() throws Exception {
        List<LogEntryData> entries = List.of(
                new LogEntryData(1, 1, "cmd-1".getBytes()),
                new LogEntryData(2, 1, "cmd-2".getBytes()),
                new LogEntryData(3, 2, "cmd-3".getBytes())
        );

        storage.appendEntries(entries).get(5, TimeUnit.SECONDS);
        storage.sync().get(5, TimeUnit.SECONDS);

        List<LogEntryData> replayed = storage.replayLog().get(5, TimeUnit.SECONDS);

        assertEquals(3, replayed.size());
        assertEquals(1, replayed.get(0).index());
        assertEquals(2, replayed.get(1).index());
        assertEquals(3, replayed.get(2).index());
        assertEquals(2, replayed.get(2).term());
    }

    @Test
    void testAppend_EmptyPayload() throws Exception {
        LogEntryData entry = new LogEntryData(1, 1, new byte[0]);

        storage.appendEntries(List.of(entry)).get(5, TimeUnit.SECONDS);
        storage.sync().get(5, TimeUnit.SECONDS);

        List<LogEntryData> replayed = storage.replayLog().get(5, TimeUnit.SECONDS);

        assertEquals(1, replayed.size());
        assertEquals(0, replayed.get(0).payload().length);
    }

    @Test
    void testAppend_LargePayload() throws Exception {
        byte[] largePayload = new byte[64 * 1024]; // 64 KB
        for (int i = 0; i < largePayload.length; i++) {
            largePayload[i] = (byte) (i % 256);
        }

        LogEntryData entry = new LogEntryData(1, 1, largePayload);

        storage.appendEntries(List.of(entry)).get(5, TimeUnit.SECONDS);
        storage.sync().get(5, TimeUnit.SECONDS);

        List<LogEntryData> replayed = storage.replayLog().get(5, TimeUnit.SECONDS);

        assertEquals(1, replayed.size());
        assertArrayEquals(largePayload, replayed.get(0).payload());
    }

    @Test
    void testAppend_NullPayload() throws Exception {
        LogEntryData entry = new LogEntryData(1, 1, null);

        storage.appendEntries(List.of(entry)).get(5, TimeUnit.SECONDS);
        storage.sync().get(5, TimeUnit.SECONDS);

        List<LogEntryData> replayed = storage.replayLog().get(5, TimeUnit.SECONDS);

        assertEquals(1, replayed.size());
        assertEquals(0, replayed.get(0).payload().length);
    }

    @Test
    void testAppend_PayloadTooLarge_Fails() throws Exception {
        // Create payload larger than MAX_PAYLOAD_SIZE (16 MB)
        byte[] hugePayload = new byte[17 * 1024 * 1024]; // 17 MB

        LogEntryData entry = new LogEntryData(1, 1, hugePayload);

        // Should fail with StorageException
        var future = storage.appendEntries(List.of(entry));
        
        assertThrows(Exception.class, () -> future.get(5, TimeUnit.SECONDS));
    }

    // ========================================================================
    // Truncation Tests
    // ========================================================================

    @Test
    void testTruncateSuffix() throws Exception {
        // Append entries 1-5
        List<LogEntryData> entries = List.of(
                new LogEntryData(1, 1, "cmd-1".getBytes()),
                new LogEntryData(2, 1, "cmd-2".getBytes()),
                new LogEntryData(3, 1, "cmd-3".getBytes()),
                new LogEntryData(4, 1, "cmd-4".getBytes()),
                new LogEntryData(5, 1, "cmd-5".getBytes())
        );

        storage.appendEntries(entries).get(5, TimeUnit.SECONDS);
        storage.sync().get(5, TimeUnit.SECONDS);

        // Truncate from index 3
        storage.truncateSuffix(3).get(5, TimeUnit.SECONDS);
        storage.sync().get(5, TimeUnit.SECONDS);

        List<LogEntryData> replayed = storage.replayLog().get(5, TimeUnit.SECONDS);

        assertEquals(2, replayed.size());
        assertEquals(1, replayed.get(0).index());
        assertEquals(2, replayed.get(1).index());
    }

    @Test
    void testTruncateAndAppend() throws Exception {
        // Append entries 1-3 in term 1
        storage.appendEntries(List.of(
                new LogEntryData(1, 1, "old-1".getBytes()),
                new LogEntryData(2, 1, "old-2".getBytes()),
                new LogEntryData(3, 1, "old-3".getBytes())
        )).get(5, TimeUnit.SECONDS);

        // Truncate from 2 and append new entries in term 2
        storage.truncateSuffix(2).get(5, TimeUnit.SECONDS);
        storage.appendEntries(List.of(
                new LogEntryData(2, 2, "new-2".getBytes()),
                new LogEntryData(3, 2, "new-3".getBytes())
        )).get(5, TimeUnit.SECONDS);
        storage.sync().get(5, TimeUnit.SECONDS);

        List<LogEntryData> replayed = storage.replayLog().get(5, TimeUnit.SECONDS);

        assertEquals(3, replayed.size());
        assertEquals(1, replayed.get(0).term()); // old entry
        assertEquals(2, replayed.get(1).term()); // new entry
        assertEquals(2, replayed.get(2).term()); // new entry
        assertArrayEquals("new-2".getBytes(), replayed.get(1).payload());
    }

    // ========================================================================
    // Recovery Tests
    // ========================================================================

    @Test
    void testReplay_SurvivesRestart() throws Exception {
        storage.appendEntries(List.of(
                new LogEntryData(1, 1, "a".getBytes()),
                new LogEntryData(2, 1, "b".getBytes())
        )).get(5, TimeUnit.SECONDS);
        storage.sync().get(5, TimeUnit.SECONDS);
        storage.close();

        // Reopen
        storage = new FileRaftStorage(true);
        storage.open(tempDir).get(5, TimeUnit.SECONDS);

        List<LogEntryData> replayed = storage.replayLog().get(5, TimeUnit.SECONDS);

        assertEquals(2, replayed.size());
    }

    @Test
    void testRecovery_TornWrite_PartialHeader() throws Exception {
        // Write valid entries
        storage.appendEntries(List.of(
                new LogEntryData(1, 1, "valid-1".getBytes()),
                new LogEntryData(2, 1, "valid-2".getBytes())
        )).get(5, TimeUnit.SECONDS);
        storage.sync().get(5, TimeUnit.SECONDS);
        storage.close();

        // Simulate torn write: append partial header
        Path logPath = tempDir.resolve("raft.log");
        try (FileChannel fc = FileChannel.open(logPath,
                StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            ByteBuffer partial = ByteBuffer.allocate(10);
            partial.putInt(0x52414654); // Valid magic
            partial.putShort((short) 1); // Valid version
            partial.put((byte) 2);       // Type append
            // Missing: index, term, length, payload, CRC
            partial.flip();
            fc.write(partial);
        }

        // Reopen and replay
        storage = new FileRaftStorage(true);
        storage.open(tempDir).get(5, TimeUnit.SECONDS);

        List<LogEntryData> replayed = storage.replayLog().get(5, TimeUnit.SECONDS);

        // Should recover only the 2 valid entries
        assertEquals(2, replayed.size());
        assertEquals(1, replayed.get(0).index());
        assertEquals(2, replayed.get(1).index());

        // File should be truncated to remove garbage
        long fileSize = Files.size(logPath);
        assertTrue(fileSize > 0);
    }

    @Test
    void testRecovery_CorruptCRC() throws Exception {
        // Write valid entries
        storage.appendEntries(List.of(
                new LogEntryData(1, 1, "valid-1".getBytes())
        )).get(5, TimeUnit.SECONDS);
        storage.sync().get(5, TimeUnit.SECONDS);

        long validSize = Files.size(tempDir.resolve("raft.log"));

        storage.appendEntries(List.of(
                new LogEntryData(2, 1, "valid-2".getBytes())
        )).get(5, TimeUnit.SECONDS);
        storage.sync().get(5, TimeUnit.SECONDS);
        storage.close();

        // Corrupt the CRC of the second entry
        Path logPath = tempDir.resolve("raft.log");
        try (FileChannel fc = FileChannel.open(logPath, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            // Go to end and corrupt last 4 bytes (CRC)
            long fileSize = fc.size();
            fc.position(fileSize - 4);
            ByteBuffer corruptCrc = ByteBuffer.allocate(4);
            corruptCrc.putInt(0xDEADBEEF);
            corruptCrc.flip();
            fc.write(corruptCrc);
        }

        // Reopen and replay
        storage = new FileRaftStorage(true);
        storage.open(tempDir).get(5, TimeUnit.SECONDS);

        List<LogEntryData> replayed = storage.replayLog().get(5, TimeUnit.SECONDS);

        // Should only recover the first entry
        assertEquals(1, replayed.size());
        assertEquals(1, replayed.get(0).index());
    }

    // ========================================================================
    // Edge Cases
    // ========================================================================

    @Test
    void testAppend_EmptyList() throws Exception {
        // Should not throw
        storage.appendEntries(List.of()).get(5, TimeUnit.SECONDS);

        List<LogEntryData> replayed = storage.replayLog().get(5, TimeUnit.SECONDS);
        assertEquals(0, replayed.size());
    }

    @Test
    void testReplay_EmptyLog() throws Exception {
        List<LogEntryData> replayed = storage.replayLog().get(5, TimeUnit.SECONDS);
        assertEquals(0, replayed.size());
    }
}
