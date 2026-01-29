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

import dev.mars.raftlog.storage.FileRaftStorage.StorageException;
import dev.mars.raftlog.storage.RaftStorage.LogEntryData;
import dev.mars.raftlog.storage.RaftStorage.PersistentMeta;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/**
 * High-coverage tests targeting the remaining uncovered code paths.
 * <p>
 * These tests specifically target:
 * <ul>
 *   <li>RaftStorageConfig.Builder - system properties, env vars, properties file resolution</li>
 *   <li>FileRaftStorage.config() method</li>
 *   <li>FileRaftStorage.open() no-arg method</li>
 *   <li>FileRaftStorage.verifyWrittenRecord error paths</li>
 *   <li>FileRaftStorage sync with disabled sync</li>
 *   <li>FileRaftStorage error handling paths</li>
 * </ul>
 */
class HighCoverageTest {

    @TempDir
    Path tempDir;

    // ========================================================================
    // RaftStorageConfig Tests
    // ========================================================================

    @Nested
    @DisplayName("RaftStorageConfig Tests")
    class ConfigTests {

        @Test
        @DisplayName("Builder with all programmatic values")
        void testBuilderAllProgrammaticValues() {
            RaftStorageConfig config = RaftStorageConfig.builder()
                    .dataDir(tempDir)
                    .syncEnabled(false)
                    .verifyWrites(true)
                    .minFreeSpaceMb(128)
                    .maxPayloadSizeMb(32)
                    .build();

            assertEquals(tempDir, config.dataDir());
            assertFalse(config.syncEnabled());
            assertTrue(config.verifyWrites());
            assertEquals(128, config.minFreeSpaceMb());
            assertEquals(32, config.maxPayloadSizeMb());
            assertEquals(128L * 1024 * 1024, config.minFreeSpaceBytes());
            assertEquals(32 * 1024 * 1024, config.maxPayloadSizeBytes());
        }

        @Test
        @DisplayName("Builder with string dataDir")
        void testBuilderStringDataDir() {
            RaftStorageConfig config = RaftStorageConfig.builder()
                    .dataDir(tempDir.toString())
                    .build();

            assertEquals(tempDir, config.dataDir());
        }

        @Test
        @DisplayName("Config load() shorthand")
        void testConfigLoad() {
            RaftStorageConfig config = RaftStorageConfig.load();
            assertNotNull(config);
            assertNotNull(config.dataDir());
        }

        @Test
        @DisplayName("Config toString()")
        void testConfigToString() {
            RaftStorageConfig config = RaftStorageConfig.builder()
                    .dataDir(tempDir)
                    .syncEnabled(true)
                    .verifyWrites(false)
                    .minFreeSpaceMb(64)
                    .maxPayloadSizeMb(16)
                    .build();

            String str = config.toString();
            assertTrue(str.contains("RaftStorageConfig"));
            assertTrue(str.contains("syncEnabled=true"));
            assertTrue(str.contains("verifyWrites=false"));
            assertTrue(str.contains("minFreeSpaceMb=64"));
            assertTrue(str.contains("maxPayloadSizeMb=16"));
        }

        @Test
        @DisplayName("Builder resolves system properties")
        void testBuilderSystemProperties() {
            String originalDataDir = System.getProperty("raftlog.dataDir");
            String originalSync = System.getProperty("raftlog.syncEnabled");
            String originalVerify = System.getProperty("raftlog.verifyWrites");
            String originalMinSpace = System.getProperty("raftlog.minFreeSpaceMb");
            String originalMaxPayload = System.getProperty("raftlog.maxPayloadSizeMb");

            try {
                System.setProperty("raftlog.dataDir", tempDir.resolve("sysprop").toString());
                System.setProperty("raftlog.syncEnabled", "false");
                System.setProperty("raftlog.verifyWrites", "true");
                System.setProperty("raftlog.minFreeSpaceMb", "256");
                System.setProperty("raftlog.maxPayloadSizeMb", "64");

                RaftStorageConfig config = RaftStorageConfig.builder().build();

                assertEquals(tempDir.resolve("sysprop"), config.dataDir());
                assertFalse(config.syncEnabled());
                assertTrue(config.verifyWrites());
                assertEquals(256, config.minFreeSpaceMb());
                assertEquals(64, config.maxPayloadSizeMb());
            } finally {
                // Restore original values
                restoreProperty("raftlog.dataDir", originalDataDir);
                restoreProperty("raftlog.syncEnabled", originalSync);
                restoreProperty("raftlog.verifyWrites", originalVerify);
                restoreProperty("raftlog.minFreeSpaceMb", originalMinSpace);
                restoreProperty("raftlog.maxPayloadSizeMb", originalMaxPayload);
            }
        }

        @Test
        @DisplayName("Builder handles invalid integer in system property")
        void testBuilderInvalidIntegerSystemProperty() {
            String original = System.getProperty("raftlog.minFreeSpaceMb");
            try {
                System.setProperty("raftlog.minFreeSpaceMb", "not-a-number");

                RaftStorageConfig config = RaftStorageConfig.builder().build();

                // Should fall back to default (64)
                assertEquals(64, config.minFreeSpaceMb());
            } finally {
                restoreProperty("raftlog.minFreeSpaceMb", original);
            }
        }

        @Test
        @DisplayName("Builder handles blank system property")
        void testBuilderBlankSystemProperty() {
            String original = System.getProperty("raftlog.syncEnabled");
            try {
                System.setProperty("raftlog.syncEnabled", "   ");

                RaftStorageConfig config = RaftStorageConfig.builder().build();

                // Should fall back to default (true)
                assertTrue(config.syncEnabled());
            } finally {
                restoreProperty("raftlog.syncEnabled", original);
            }
        }

        private void restoreProperty(String key, String value) {
            if (value == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, value);
            }
        }
    }

    // ========================================================================
    // FileRaftStorage Config Integration Tests
    // ========================================================================

    @Nested
    @DisplayName("FileRaftStorage Config Integration")
    class StorageConfigIntegrationTests {

        @Test
        @DisplayName("config() returns the configuration")
        void testConfigMethod() {
            RaftStorageConfig config = RaftStorageConfig.builder()
                    .dataDir(tempDir)
                    .syncEnabled(true)
                    .verifyWrites(false)
                    .build();

            FileRaftStorage storage = new FileRaftStorage(config);

            assertSame(config, storage.config());
            storage.close();
        }

        @Test
        @DisplayName("open() no-arg uses config dataDir")
        void testOpenNoArg() throws Exception {
            RaftStorageConfig config = RaftStorageConfig.builder()
                    .dataDir(tempDir.resolve("config-dir"))
                    .syncEnabled(true)
                    .build();

            FileRaftStorage storage = new FileRaftStorage(config);
            storage.open().get(5, TimeUnit.SECONDS);

            assertTrue(Files.exists(tempDir.resolve("config-dir")));
            assertTrue(Files.exists(tempDir.resolve("config-dir").resolve("raft.lock")));

            storage.close();
        }

        @Test
        @DisplayName("Storage with sync disabled skips fsync")
        void testSyncDisabled() throws Exception {
            RaftStorageConfig config = RaftStorageConfig.builder()
                    .dataDir(tempDir)
                    .syncEnabled(false)
                    .build();

            FileRaftStorage storage = new FileRaftStorage(config);
            storage.open().get(5, TimeUnit.SECONDS);

            // Append some data
            LogEntryData entry = new LogEntryData(1, 1, "test".getBytes(StandardCharsets.UTF_8));
            storage.appendEntries(List.of(entry)).get(5, TimeUnit.SECONDS);

            // sync() should complete immediately without error
            storage.sync().get(5, TimeUnit.SECONDS);

            storage.close();
        }

        @Test
        @DisplayName("Storage with verify writes enabled")
        void testVerifyWritesEnabled() throws Exception {
            RaftStorageConfig config = RaftStorageConfig.builder()
                    .dataDir(tempDir)
                    .syncEnabled(true)
                    .verifyWrites(true)
                    .build();

            FileRaftStorage storage = new FileRaftStorage(config);
            storage.open().get(5, TimeUnit.SECONDS);

            // Append entry - verification should run
            LogEntryData entry = new LogEntryData(1, 1, "verified".getBytes(StandardCharsets.UTF_8));
            storage.appendEntries(List.of(entry)).get(5, TimeUnit.SECONDS);
            storage.sync().get(5, TimeUnit.SECONDS);

            // Verify data was written correctly
            List<LogEntryData> replayed = storage.replayLog().get(5, TimeUnit.SECONDS);
            assertEquals(1, replayed.size());
            assertEquals("verified", new String(replayed.get(0).payload(), StandardCharsets.UTF_8));

            storage.close();
        }
    }

    // ========================================================================
    // Error Path Tests
    // ========================================================================

    @Nested
    @DisplayName("Error Path Tests")
    class ErrorPathTests {

        @Test
        @DisplayName("Open fails on non-existent parent with no permission")
        void testOpenInvalidPath() {
            FileRaftStorage storage = new FileRaftStorage(true);

            // Try to open in a path that can't be created (null byte in path on Windows)
            Path invalidPath = Path.of("Z:\\nonexistent\\path\\that\\cannot\\exist\\raftlog");

            ExecutionException ex = assertThrows(ExecutionException.class, () ->
                    storage.open(invalidPath).get(5, TimeUnit.SECONDS));

            assertTrue(ex.getCause() instanceof StorageException);
            storage.close();
        }

        @Test
        @DisplayName("Double close is safe")
        void testDoubleClose() throws Exception {
            FileRaftStorage storage = new FileRaftStorage(true);
            storage.open(tempDir).get(5, TimeUnit.SECONDS);

            // First close
            storage.close();

            // Second close should be safe (no exception)
            storage.close();
        }

        @Test
        @DisplayName("Truncate suffix writes truncate record")
        void testTruncateSuffix() throws Exception {
            FileRaftStorage storage = new FileRaftStorage(true);
            storage.open(tempDir).get(5, TimeUnit.SECONDS);

            // Append entries
            for (int i = 1; i <= 5; i++) {
                storage.appendEntries(List.of(new LogEntryData(i, 1, 
                        ("entry-" + i).getBytes(StandardCharsets.UTF_8)))).get(5, TimeUnit.SECONDS);
            }
            storage.sync().get(5, TimeUnit.SECONDS);

            // Truncate from index 3
            storage.truncateSuffix(3).get(5, TimeUnit.SECONDS);
            storage.sync().get(5, TimeUnit.SECONDS);

            // Replay should show only entries 1-2
            List<LogEntryData> entries = storage.replayLog().get(5, TimeUnit.SECONDS);
            assertEquals(2, entries.size());
            assertEquals(1, entries.get(0).index());
            assertEquals(2, entries.get(1).index());

            storage.close();
        }

        @Test
        @DisplayName("Update metadata with null votedFor")
        void testUpdateMetadataNullVote() throws Exception {
            FileRaftStorage storage = new FileRaftStorage(true);
            storage.open(tempDir).get(5, TimeUnit.SECONDS);

            // Update with empty votedFor
            storage.updateMetadata(10L, Optional.empty()).get(5, TimeUnit.SECONDS);

            PersistentMeta meta = storage.loadMetadata().get(5, TimeUnit.SECONDS);
            assertEquals(10L, meta.currentTerm());
            assertTrue(meta.votedFor().isEmpty());

            storage.close();
        }

        @Test
        @DisplayName("Replay handles empty WAL")
        void testReplayEmptyWal() throws Exception {
            FileRaftStorage storage = new FileRaftStorage(true);
            storage.open(tempDir).get(5, TimeUnit.SECONDS);

            // Create empty WAL file
            Path walPath = tempDir.resolve("raft.log");
            Files.write(walPath, new byte[0]);

            List<LogEntryData> entries = storage.replayLog().get(5, TimeUnit.SECONDS);
            assertTrue(entries.isEmpty());

            storage.close();
        }

        @Test
        @DisplayName("Replay handles WAL file not existing")
        void testReplayNoWalFile() throws Exception {
            Path subDir = tempDir.resolve("new-storage");
            Files.createDirectories(subDir);

            FileRaftStorage storage = new FileRaftStorage(true);
            storage.open(subDir).get(5, TimeUnit.SECONDS);

            // Delete WAL file if it exists
            Files.deleteIfExists(subDir.resolve("raft.log"));

            List<LogEntryData> entries = storage.replayLog().get(5, TimeUnit.SECONDS);
            assertTrue(entries.isEmpty());

            storage.close();
        }
    }

    // ========================================================================
    // WAL Corruption Recovery Tests
    // ========================================================================

    @Nested
    @DisplayName("WAL Corruption Recovery")
    class CorruptionRecoveryTests {

        private static final int MAGIC = 0x52414654; // "RAFT"
        private static final short VERSION = 1;
        private static final byte TYPE_APPEND = 2;
        private static final int HEADER_SIZE = 27; // 4+2+1+8+8+4
        private static final int CRC_SIZE = 4;

        @Test
        @DisplayName("Replay stops at corrupt magic number")
        void testReplayCorruptMagic() throws Exception {
            FileRaftStorage storage = new FileRaftStorage(true);
            storage.open(tempDir).get(5, TimeUnit.SECONDS);

            // Write valid entry
            storage.appendEntries(List.of(new LogEntryData(1, 1, "valid".getBytes()))).get(5, TimeUnit.SECONDS);
            storage.sync().get(5, TimeUnit.SECONDS);
            storage.close();

            // Corrupt the magic number at start
            Path walPath = tempDir.resolve("raft.log");
            try (FileChannel ch = FileChannel.open(walPath, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
                ByteBuffer buf = ByteBuffer.allocate(4);
                buf.putInt(0xDEADBEEF); // Invalid magic
                buf.flip();
                ch.write(buf, 0);
            }

            // Reopen and replay
            storage = new FileRaftStorage(true);
            storage.open(tempDir).get(5, TimeUnit.SECONDS);

            List<LogEntryData> entries = storage.replayLog().get(5, TimeUnit.SECONDS);
            assertTrue(entries.isEmpty()); // Should stop at corruption

            storage.close();
        }

        @Test
        @DisplayName("Replay stops at corrupt version")
        void testReplayCorruptVersion() throws Exception {
            FileRaftStorage storage = new FileRaftStorage(true);
            storage.open(tempDir).get(5, TimeUnit.SECONDS);

            storage.appendEntries(List.of(new LogEntryData(1, 1, "valid".getBytes()))).get(5, TimeUnit.SECONDS);
            storage.sync().get(5, TimeUnit.SECONDS);
            storage.close();

            // Corrupt the version
            Path walPath = tempDir.resolve("raft.log");
            try (FileChannel ch = FileChannel.open(walPath, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
                ByteBuffer buf = ByteBuffer.allocate(2);
                buf.putShort((short) 99); // Invalid version
                buf.flip();
                ch.write(buf, 4); // After magic
            }

            storage = new FileRaftStorage(true);
            storage.open(tempDir).get(5, TimeUnit.SECONDS);

            List<LogEntryData> entries = storage.replayLog().get(5, TimeUnit.SECONDS);
            assertTrue(entries.isEmpty());

            storage.close();
        }

        @Test
        @DisplayName("Replay stops at invalid payload length")
        void testReplayInvalidPayloadLength() throws Exception {
            FileRaftStorage storage = new FileRaftStorage(true);
            storage.open(tempDir).get(5, TimeUnit.SECONDS);

            storage.appendEntries(List.of(new LogEntryData(1, 1, "valid".getBytes()))).get(5, TimeUnit.SECONDS);
            storage.sync().get(5, TimeUnit.SECONDS);
            storage.close();

            // Corrupt payload length to be negative
            Path walPath = tempDir.resolve("raft.log");
            try (FileChannel ch = FileChannel.open(walPath, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
                ByteBuffer buf = ByteBuffer.allocate(4);
                buf.putInt(-1); // Invalid payload length
                buf.flip();
                ch.write(buf, HEADER_SIZE - 4); // Payload length position
            }

            storage = new FileRaftStorage(true);
            storage.open(tempDir).get(5, TimeUnit.SECONDS);

            List<LogEntryData> entries = storage.replayLog().get(5, TimeUnit.SECONDS);
            assertTrue(entries.isEmpty());

            storage.close();
        }

        @Test
        @DisplayName("Replay stops at CRC mismatch")
        void testReplayCrcMismatch() throws Exception {
            FileRaftStorage storage = new FileRaftStorage(true);
            storage.open(tempDir).get(5, TimeUnit.SECONDS);

            byte[] payload = "test-data".getBytes(StandardCharsets.UTF_8);
            storage.appendEntries(List.of(new LogEntryData(1, 1, payload))).get(5, TimeUnit.SECONDS);
            storage.sync().get(5, TimeUnit.SECONDS);
            storage.close();

            // Corrupt the CRC
            Path walPath = tempDir.resolve("raft.log");
            long fileSize = Files.size(walPath);
            try (FileChannel ch = FileChannel.open(walPath, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
                ByteBuffer buf = ByteBuffer.allocate(4);
                buf.putInt(0xBADC0C00); // Invalid CRC
                buf.flip();
                ch.write(buf, fileSize - CRC_SIZE); // CRC at end
            }

            storage = new FileRaftStorage(true);
            storage.open(tempDir).get(5, TimeUnit.SECONDS);

            List<LogEntryData> entries = storage.replayLog().get(5, TimeUnit.SECONDS);
            assertTrue(entries.isEmpty());

            storage.close();
        }

        @Test
        @DisplayName("Replay handles incomplete header")
        void testReplayIncompleteHeader() throws Exception {
            FileRaftStorage storage = new FileRaftStorage(true);
            storage.open(tempDir).get(5, TimeUnit.SECONDS);

            storage.appendEntries(List.of(new LogEntryData(1, 1, "complete".getBytes()))).get(5, TimeUnit.SECONDS);
            storage.sync().get(5, TimeUnit.SECONDS);
            storage.close();

            // Truncate file to leave incomplete header
            Path walPath = tempDir.resolve("raft.log");
            long originalSize = Files.size(walPath);
            try (FileChannel ch = FileChannel.open(walPath, StandardOpenOption.WRITE)) {
                ch.truncate(originalSize + 10); // Add partial header bytes
                ch.position(originalSize);
                ByteBuffer partial = ByteBuffer.allocate(10);
                partial.putInt(MAGIC);
                partial.putShort(VERSION);
                partial.flip();
                ch.write(partial);
            }

            storage = new FileRaftStorage(true);
            storage.open(tempDir).get(5, TimeUnit.SECONDS);

            List<LogEntryData> entries = storage.replayLog().get(5, TimeUnit.SECONDS);
            assertEquals(1, entries.size()); // Only first complete entry

            storage.close();
        }

        @Test
        @DisplayName("Replay handles incomplete payload")
        void testReplayIncompletePayload() throws Exception {
            FileRaftStorage storage = new FileRaftStorage(true);
            storage.open(tempDir).get(5, TimeUnit.SECONDS);

            storage.appendEntries(List.of(new LogEntryData(1, 1, "first".getBytes()))).get(5, TimeUnit.SECONDS);
            storage.sync().get(5, TimeUnit.SECONDS);
            storage.close();

            // Write a header with large payload length but no actual payload
            Path walPath = tempDir.resolve("raft.log");
            try (FileChannel ch = FileChannel.open(walPath, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                ByteBuffer header = ByteBuffer.allocate(HEADER_SIZE);
                header.putInt(MAGIC);
                header.putShort(VERSION);
                header.put(TYPE_APPEND);
                header.putLong(2L); // index
                header.putLong(1L); // term
                header.putInt(1000); // payload length (but we won't write payload)
                header.flip();
                ch.write(header);
            }

            storage = new FileRaftStorage(true);
            storage.open(tempDir).get(5, TimeUnit.SECONDS);

            List<LogEntryData> entries = storage.replayLog().get(5, TimeUnit.SECONDS);
            assertEquals(1, entries.size()); // Only first complete entry

            storage.close();
        }

        @Test
        @DisplayName("Replay handles incomplete CRC")
        void testReplayIncompleteCrc() throws Exception {
            FileRaftStorage storage = new FileRaftStorage(true);
            storage.open(tempDir).get(5, TimeUnit.SECONDS);

            storage.appendEntries(List.of(new LogEntryData(1, 1, "first".getBytes()))).get(5, TimeUnit.SECONDS);
            storage.sync().get(5, TimeUnit.SECONDS);
            storage.close();

            // Write header + payload but incomplete CRC
            Path walPath = tempDir.resolve("raft.log");
            byte[] payload = "second".getBytes();
            try (FileChannel ch = FileChannel.open(walPath, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                ByteBuffer record = ByteBuffer.allocate(HEADER_SIZE + payload.length + 2); // Only 2 bytes of CRC
                record.putInt(MAGIC);
                record.putShort(VERSION);
                record.put(TYPE_APPEND);
                record.putLong(2L);
                record.putLong(1L);
                record.putInt(payload.length);
                record.put(payload);
                record.putShort((short) 0); // Incomplete CRC (2 bytes instead of 4)
                record.flip();
                ch.write(record);
            }

            storage = new FileRaftStorage(true);
            storage.open(tempDir).get(5, TimeUnit.SECONDS);

            List<LogEntryData> entries = storage.replayLog().get(5, TimeUnit.SECONDS);
            assertEquals(1, entries.size());

            storage.close();
        }

        @Test
        @DisplayName("Replay recovers valid entries before corruption")
        void testReplayRecoverBeforeCorruption() throws Exception {
            FileRaftStorage storage = new FileRaftStorage(true);
            storage.open(tempDir).get(5, TimeUnit.SECONDS);

            // Write 3 valid entries
            for (int i = 1; i <= 3; i++) {
                storage.appendEntries(List.of(new LogEntryData(i, 1, 
                        ("entry-" + i).getBytes()))).get(5, TimeUnit.SECONDS);
            }
            storage.sync().get(5, TimeUnit.SECONDS);
            storage.close();

            // Corrupt the 3rd entry's CRC
            Path walPath = tempDir.resolve("raft.log");
            long fileSize = Files.size(walPath);
            try (FileChannel ch = FileChannel.open(walPath, StandardOpenOption.WRITE)) {
                ByteBuffer buf = ByteBuffer.allocate(4);
                buf.putInt(0xBADC0C00);
                buf.flip();
                ch.write(buf, fileSize - CRC_SIZE);
            }

            storage = new FileRaftStorage(true);
            storage.open(tempDir).get(5, TimeUnit.SECONDS);

            List<LogEntryData> entries = storage.replayLog().get(5, TimeUnit.SECONDS);
            assertEquals(2, entries.size()); // Only first 2 entries recovered

            storage.close();
        }
    }

    // ========================================================================
    // Metadata Corruption Tests
    // ========================================================================

    @Nested
    @DisplayName("Metadata Tests")
    class MetadataTests {

        @Test
        @DisplayName("Update and load metadata roundtrip")
        void testMetadataRoundtrip() throws Exception {
            FileRaftStorage storage = new FileRaftStorage(true);
            storage.open(tempDir).get(5, TimeUnit.SECONDS);

            // Write metadata
            storage.updateMetadata(42L, Optional.of("node-42")).get(5, TimeUnit.SECONDS);

            // Load it back
            PersistentMeta meta = storage.loadMetadata().get(5, TimeUnit.SECONDS);
            assertEquals(42L, meta.currentTerm());
            assertEquals(Optional.of("node-42"), meta.votedFor());

            storage.close();
        }

        @Test
        @DisplayName("Update metadata creates file atomically")
        void testMetadataAtomicWrite() throws Exception {
            FileRaftStorage storage = new FileRaftStorage(true);
            storage.open(tempDir).get(5, TimeUnit.SECONDS);

            storage.updateMetadata(100L, Optional.of("leader-node")).get(5, TimeUnit.SECONDS);

            // Verify file exists
            Path metaPath = tempDir.resolve("meta.dat");
            assertTrue(Files.exists(metaPath));
            // Binary file - just verify it has content
            assertTrue(Files.size(metaPath) > 0);

            storage.close();
        }

        @Test
        @DisplayName("Metadata with empty votedFor")
        void testMetadataEmptyVotedFor() throws Exception {
            FileRaftStorage storage = new FileRaftStorage(true);
            storage.open(tempDir).get(5, TimeUnit.SECONDS);

            storage.updateMetadata(5L, Optional.empty()).get(5, TimeUnit.SECONDS);

            PersistentMeta meta = storage.loadMetadata().get(5, TimeUnit.SECONDS);
            assertEquals(5L, meta.currentTerm());
            assertTrue(meta.votedFor().isEmpty());

            storage.close();
        }
    }

    // ========================================================================
    // AppendPlan Edge Cases
    // ========================================================================

    @Nested
    @DisplayName("AppendPlan Edge Cases")
    class AppendPlanEdgeCases {

        @Test
        @DisplayName("AppendPlan with empty incoming entries")
        void testAppendPlanEmptyIncoming() {
            List<LogEntryData> existingLog = List.of(
                    new LogEntryData(1, 1, "a".getBytes()),
                    new LogEntryData(2, 1, "b".getBytes())
            );

            AppendPlan plan = AppendPlan.from(3, List.of(), existingLog);

            assertFalse(plan.requiresTruncation());
            assertTrue(plan.entriesToAppend().isEmpty());
        }

        @Test
        @DisplayName("AppendPlan with all matching entries")
        void testAppendPlanAllMatching() {
            LogEntryData entry1 = new LogEntryData(1, 1, "a".getBytes());
            LogEntryData entry2 = new LogEntryData(2, 1, "b".getBytes());
            List<LogEntryData> existingLog = List.of(entry1, entry2);

            // Incoming entries are exactly the same
            AppendPlan plan = AppendPlan.from(1, List.of(entry1, entry2), existingLog);

            assertFalse(plan.requiresTruncation());
            assertTrue(plan.entriesToAppend().isEmpty());
        }

        @Test
        @DisplayName("AppendPlan with partial overlap and new entries")
        void testAppendPlanPartialOverlap() {
            List<LogEntryData> existing = List.of(
                    new LogEntryData(1, 1, "a".getBytes()),
                    new LogEntryData(2, 1, "b".getBytes())
            );

            List<LogEntryData> incoming = List.of(
                    new LogEntryData(2, 1, "b".getBytes()), // matches
                    new LogEntryData(3, 1, "c".getBytes())  // new
            );

            AppendPlan plan = AppendPlan.from(2, incoming, existing);

            assertFalse(plan.requiresTruncation());
            assertEquals(1, plan.entriesToAppend().size());
            assertEquals(3, plan.entriesToAppend().get(0).index());
        }

        @Test
        @DisplayName("AppendPlan with conflicting term triggers truncate")
        void testAppendPlanConflictingTerm() {
            List<LogEntryData> existing = List.of(
                    new LogEntryData(1, 1, "a".getBytes()),
                    new LogEntryData(2, 1, "b".getBytes()),
                    new LogEntryData(3, 1, "c".getBytes())
            );

            List<LogEntryData> incoming = List.of(
                    new LogEntryData(2, 2, "b-new".getBytes()), // Different term!
                    new LogEntryData(3, 2, "c-new".getBytes())
            );

            AppendPlan plan = AppendPlan.from(2, incoming, existing);

            assertTrue(plan.requiresTruncation());
            assertEquals(2, plan.truncateFromIndex());
            assertEquals(2, plan.entriesToAppend().size());
        }

        @Test
        @DisplayName("AppendPlan applyTo modifies list correctly")
        void testAppendPlanApplyTo() {
            java.util.ArrayList<LogEntryData> log = new java.util.ArrayList<>();
            log.add(new LogEntryData(1, 1, "a".getBytes()));
            log.add(new LogEntryData(2, 1, "b".getBytes()));
            log.add(new LogEntryData(3, 1, "c".getBytes()));

            List<LogEntryData> incoming = List.of(
                    new LogEntryData(2, 2, "b-new".getBytes()),
                    new LogEntryData(3, 2, "c-new".getBytes()),
                    new LogEntryData(4, 2, "d".getBytes())
            );

            AppendPlan plan = AppendPlan.from(2, incoming, log);
            plan.applyTo(log);

            assertEquals(4, log.size());
            assertEquals(1, log.get(0).index());
            assertEquals(2, log.get(1).index());
            assertEquals(2, log.get(1).term()); // Updated term
            assertEquals(3, log.get(2).index());
            assertEquals(4, log.get(3).index());
        }
    }

    // ========================================================================
    // Large Payload Tests
    // ========================================================================

    @Nested
    @DisplayName("Large Payload Tests")
    class LargePayloadTests {

        @Test
        @DisplayName("Append and replay large payload")
        void testLargePayload() throws Exception {
            FileRaftStorage storage = new FileRaftStorage(true);
            storage.open(tempDir).get(5, TimeUnit.SECONDS);

            // Create 1MB payload
            byte[] largePayload = new byte[1024 * 1024];
            for (int i = 0; i < largePayload.length; i++) {
                largePayload[i] = (byte) (i % 256);
            }

            storage.appendEntries(List.of(new LogEntryData(1, 1, largePayload))).get(5, TimeUnit.SECONDS);
            storage.sync().get(5, TimeUnit.SECONDS);

            List<LogEntryData> entries = storage.replayLog().get(5, TimeUnit.SECONDS);
            assertEquals(1, entries.size());
            assertArrayEquals(largePayload, entries.get(0).payload());

            storage.close();
        }

        @Test
        @DisplayName("Payload exceeding max size is rejected")
        void testPayloadTooLarge() throws Exception {
            RaftStorageConfig config = RaftStorageConfig.builder()
                    .dataDir(tempDir)
                    .maxPayloadSizeMb(1) // 1MB max
                    .build();

            FileRaftStorage storage = new FileRaftStorage(config);
            storage.open().get(5, TimeUnit.SECONDS);

            // Create payload larger than max (2MB)
            byte[] tooLarge = new byte[2 * 1024 * 1024];

            ExecutionException ex = assertThrows(ExecutionException.class, () ->
                    storage.appendEntries(List.of(new LogEntryData(1, 1, tooLarge))).get(5, TimeUnit.SECONDS));

            assertTrue(ex.getCause() instanceof StorageException);
            assertTrue(ex.getCause().getMessage().contains("Payload too large"));

            storage.close();
        }
    }

    // ========================================================================
    // Concurrent Access Tests
    // ========================================================================

    @Nested
    @DisplayName("Concurrent Access")
    class ConcurrentAccessTests {

        @Test
        @DisplayName("File lock prevents concurrent access")
        void testFileLockPreventsAccess() throws Exception {
            FileRaftStorage storage1 = new FileRaftStorage(true);
            storage1.open(tempDir).get(5, TimeUnit.SECONDS);

            // Try to open second storage on same directory
            FileRaftStorage storage2 = new FileRaftStorage(true);

            ExecutionException ex = assertThrows(ExecutionException.class, () ->
                    storage2.open(tempDir).get(5, TimeUnit.SECONDS));

            assertTrue(ex.getCause() instanceof StorageException);
            assertTrue(ex.getCause().getMessage().contains("lock"));

            storage1.close();
            storage2.close();
        }

        @Test
        @DisplayName("Lock released after close allows new access")
        void testLockReleasedAfterClose() throws Exception {
            FileRaftStorage storage1 = new FileRaftStorage(true);
            storage1.open(tempDir).get(5, TimeUnit.SECONDS);

            storage1.appendEntries(List.of(new LogEntryData(1, 1, "test".getBytes()))).get(5, TimeUnit.SECONDS);
            storage1.sync().get(5, TimeUnit.SECONDS);
            storage1.close();

            // Now should be able to open
            FileRaftStorage storage2 = new FileRaftStorage(true);
            storage2.open(tempDir).get(5, TimeUnit.SECONDS);

            List<LogEntryData> entries = storage2.replayLog().get(5, TimeUnit.SECONDS);
            assertEquals(1, entries.size());

            storage2.close();
        }
    }
}
