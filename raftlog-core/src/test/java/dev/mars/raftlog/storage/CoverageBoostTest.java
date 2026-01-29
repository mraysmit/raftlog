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
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Additional coverage tests targeting specific uncovered branches.
 * <p>
 * Focus areas:
 * <ul>
 *   <li>AppendPlan - null constructor, negative index, boundary conditions</li>
 *   <li>RaftStorageConfig.Builder - properties file resolution, env fallback</li>
 *   <li>FileRaftStorage - verifyWrittenRecord error paths, sync error paths</li>
 *   <li>FileRaftStorage - truncate and metadata edge cases</li>
 * </ul>
 */
class CoverageBoostTest {

    @TempDir
    Path tempDir;

    // ========================================================================
    // AppendPlan Branch Coverage
    // ========================================================================

    @Nested
    @DisplayName("AppendPlan Branch Coverage")
    class AppendPlanBranchTests {

        @Test
        @DisplayName("Constructor with null entries creates empty list")
        void testConstructorNullEntries() {
            AppendPlan plan = new AppendPlan(5L, null);
            
            assertEquals(5L, plan.truncateFromIndex());
            assertNotNull(plan.entriesToAppend());
            assertTrue(plan.entriesToAppend().isEmpty());
            assertTrue(plan.requiresTruncation());
            assertFalse(plan.hasEntriesToAppend());
            assertTrue(plan.requiresPersistence()); // has truncation
        }

        @Test
        @DisplayName("Plan with entries but no truncation")
        void testEntriesNoTruncation() {
            List<LogEntryData> entries = List.of(
                    new LogEntryData(1, 1, "a".getBytes()),
                    new LogEntryData(2, 1, "b".getBytes())
            );
            AppendPlan plan = new AppendPlan(null, entries);
            
            assertNull(plan.truncateFromIndex());
            assertEquals(2, plan.entriesToAppend().size());
            assertFalse(plan.requiresTruncation());
            assertTrue(plan.hasEntriesToAppend());
            assertTrue(plan.requiresPersistence());
        }

        @Test
        @DisplayName("Empty plan - no truncation, no entries")
        void testEmptyPlanNoPersistence() {
            AppendPlan plan = AppendPlan.empty();
            
            assertNull(plan.truncateFromIndex());
            assertTrue(plan.entriesToAppend().isEmpty());
            assertFalse(plan.requiresTruncation());
            assertFalse(plan.hasEntriesToAppend());
            assertFalse(plan.requiresPersistence()); // nothing to do
        }

        @Test
        @DisplayName("from() with null incoming entries returns empty")
        void testFromNullEntries() {
            List<LogEntryData> log = new ArrayList<>();
            log.add(new LogEntryData(1, 1, "a".getBytes()));
            
            AppendPlan plan = AppendPlan.from(1, null, log);
            
            assertFalse(plan.requiresPersistence());
            assertTrue(plan.entriesToAppend().isEmpty());
        }

        @Test
        @DisplayName("from() with empty incoming entries returns empty")
        void testFromEmptyEntries() {
            List<LogEntryData> log = new ArrayList<>();
            log.add(new LogEntryData(1, 1, "a".getBytes()));
            
            AppendPlan plan = AppendPlan.from(1, Collections.emptyList(), log);
            
            assertFalse(plan.requiresPersistence());
        }

        @Test
        @DisplayName("from() with negative index treats all as new")
        void testFromNegativeIndex() {
            List<LogEntryData> incoming = List.of(
                    new LogEntryData(1, 1, "a".getBytes()),
                    new LogEntryData(2, 1, "b".getBytes())
            );
            List<LogEntryData> log = new ArrayList<>();
            
            // startIndex = 0 means logPos = -1 (invalid)
            AppendPlan plan = AppendPlan.from(0, incoming, log);
            
            assertEquals(2, plan.entriesToAppend().size());
            assertFalse(plan.requiresTruncation());
        }

        @Test
        @DisplayName("applyTo with truncation beyond log size")
        void testApplyToTruncateBeyondLogSize() {
            List<LogEntryData> log = new ArrayList<>();
            log.add(new LogEntryData(1, 1, "a".getBytes()));
            log.add(new LogEntryData(2, 1, "b".getBytes()));
            
            // Truncate from index 10 (beyond log size)
            AppendPlan plan = new AppendPlan(10L, List.of(new LogEntryData(3, 1, "c".getBytes())));
            plan.applyTo(log);
            
            // Truncation should be no-op, just append
            assertEquals(3, log.size());
        }

        @Test
        @DisplayName("applyTo with truncation at negative position")
        void testApplyToTruncateNegativePosition() {
            List<LogEntryData> log = new ArrayList<>();
            log.add(new LogEntryData(1, 1, "a".getBytes()));
            
            // truncateFromIndex = 0 means fromPos = -1 (invalid)
            AppendPlan plan = new AppendPlan(0L, List.of(new LogEntryData(2, 1, "b".getBytes())));
            plan.applyTo(log);
            
            // Truncation should be no-op, just append
            assertEquals(2, log.size());
        }

        @Test
        @DisplayName("from() all entries already exist - returns empty")
        void testFromAllEntriesExist() {
            List<LogEntryData> log = new ArrayList<>();
            log.add(new LogEntryData(1, 1, "a".getBytes()));
            log.add(new LogEntryData(2, 1, "b".getBytes()));
            log.add(new LogEntryData(3, 1, "c".getBytes()));
            
            // Incoming entries match what's in log
            List<LogEntryData> incoming = List.of(
                    new LogEntryData(1, 1, "a".getBytes()),
                    new LogEntryData(2, 1, "b".getBytes())
            );
            
            AppendPlan plan = AppendPlan.from(1, incoming, log);
            
            assertFalse(plan.requiresTruncation());
            assertFalse(plan.hasEntriesToAppend());
            assertFalse(plan.requiresPersistence());
        }

        @Test
        @DisplayName("from() partial overlap with new entries")
        void testFromPartialOverlap() {
            List<LogEntryData> log = new ArrayList<>();
            log.add(new LogEntryData(1, 1, "a".getBytes()));
            log.add(new LogEntryData(2, 1, "b".getBytes()));
            
            // First entry matches, second and third are new
            List<LogEntryData> incoming = List.of(
                    new LogEntryData(1, 1, "a".getBytes()),
                    new LogEntryData(2, 1, "b".getBytes()),
                    new LogEntryData(3, 1, "c".getBytes())
            );
            
            AppendPlan plan = AppendPlan.from(1, incoming, log);
            
            assertFalse(plan.requiresTruncation());
            assertTrue(plan.hasEntriesToAppend());
            assertEquals(1, plan.entriesToAppend().size());
            assertEquals(3, plan.entriesToAppend().get(0).index());
        }

        @Test
        @DisplayName("from() conflict at first entry")
        void testFromConflictAtFirstEntry() {
            List<LogEntryData> log = new ArrayList<>();
            log.add(new LogEntryData(1, 1, "a".getBytes()));
            log.add(new LogEntryData(2, 1, "b".getBytes()));
            
            // Different term at index 1
            List<LogEntryData> incoming = List.of(
                    new LogEntryData(1, 2, "x".getBytes()),
                    new LogEntryData(2, 2, "y".getBytes())
            );
            
            AppendPlan plan = AppendPlan.from(1, incoming, log);
            
            assertTrue(plan.requiresTruncation());
            assertEquals(1L, plan.truncateFromIndex());
            assertEquals(2, plan.entriesToAppend().size());
        }

        @Test
        @DisplayName("from() incoming starts beyond log")
        void testFromIncomingStartsBeyondLog() {
            List<LogEntryData> log = new ArrayList<>();
            log.add(new LogEntryData(1, 1, "a".getBytes()));
            
            // Start at index 3, log only has up to index 1
            List<LogEntryData> incoming = List.of(
                    new LogEntryData(3, 1, "c".getBytes()),
                    new LogEntryData(4, 1, "d".getBytes())
            );
            
            AppendPlan plan = AppendPlan.from(3, incoming, log);
            
            assertFalse(plan.requiresTruncation());
            assertEquals(2, plan.entriesToAppend().size());
        }
    }

    // ========================================================================
    // FileRaftStorage Edge Cases
    // ========================================================================

    @Nested
    @DisplayName("FileRaftStorage Edge Cases")
    class FileRaftStorageEdgeCases {

        @Test
        @DisplayName("Append entry with index 0 (boundary)")
        void testAppendEntryWithIndexZero() throws Exception {
            FileRaftStorage storage = new FileRaftStorage(true);
            storage.open(tempDir).get(5, TimeUnit.SECONDS);

            // Index 0 should work (though unusual for Raft)
            storage.appendEntries(List.of(new LogEntryData(0, 1, "zero".getBytes())))
                    .get(5, TimeUnit.SECONDS);
            storage.sync().get(5, TimeUnit.SECONDS);

            List<LogEntryData> replayed = storage.replayLog().get(5, TimeUnit.SECONDS);
            assertEquals(1, replayed.size());
            assertEquals(0, replayed.get(0).index());

            storage.close();
        }

        @Test
        @DisplayName("Append with term 0")
        void testAppendWithTermZero() throws Exception {
            FileRaftStorage storage = new FileRaftStorage(true);
            storage.open(tempDir).get(5, TimeUnit.SECONDS);

            storage.appendEntries(List.of(new LogEntryData(1, 0, "term-zero".getBytes())))
                    .get(5, TimeUnit.SECONDS);
            storage.sync().get(5, TimeUnit.SECONDS);

            List<LogEntryData> replayed = storage.replayLog().get(5, TimeUnit.SECONDS);
            assertEquals(1, replayed.size());
            assertEquals(0, replayed.get(0).term());

            storage.close();
        }

        @Test
        @DisplayName("Append with Long.MAX_VALUE index")
        void testAppendWithMaxIndex() throws Exception {
            FileRaftStorage storage = new FileRaftStorage(true);
            storage.open(tempDir).get(5, TimeUnit.SECONDS);

            storage.appendEntries(List.of(new LogEntryData(Long.MAX_VALUE, 1, "max".getBytes())))
                    .get(5, TimeUnit.SECONDS);
            storage.sync().get(5, TimeUnit.SECONDS);

            List<LogEntryData> replayed = storage.replayLog().get(5, TimeUnit.SECONDS);
            assertEquals(1, replayed.size());
            assertEquals(Long.MAX_VALUE, replayed.get(0).index());

            storage.close();
        }

        @Test
        @DisplayName("Append with Long.MAX_VALUE term")
        void testAppendWithMaxTerm() throws Exception {
            FileRaftStorage storage = new FileRaftStorage(true);
            storage.open(tempDir).get(5, TimeUnit.SECONDS);

            storage.appendEntries(List.of(new LogEntryData(1, Long.MAX_VALUE, "max-term".getBytes())))
                    .get(5, TimeUnit.SECONDS);
            storage.sync().get(5, TimeUnit.SECONDS);

            List<LogEntryData> replayed = storage.replayLog().get(5, TimeUnit.SECONDS);
            assertEquals(1, replayed.size());
            assertEquals(Long.MAX_VALUE, replayed.get(0).term());

            storage.close();
        }

        @Test
        @DisplayName("Append with empty payload")
        void testAppendEmptyPayload() throws Exception {
            FileRaftStorage storage = new FileRaftStorage(true);
            storage.open(tempDir).get(5, TimeUnit.SECONDS);

            storage.appendEntries(List.of(new LogEntryData(1, 1, new byte[0])))
                    .get(5, TimeUnit.SECONDS);
            storage.sync().get(5, TimeUnit.SECONDS);

            List<LogEntryData> replayed = storage.replayLog().get(5, TimeUnit.SECONDS);
            assertEquals(1, replayed.size());
            assertEquals(0, replayed.get(0).payload().length);

            storage.close();
        }

        @Test
        @DisplayName("Truncate to index 0")
        void testTruncateToIndexZero() throws Exception {
            FileRaftStorage storage = new FileRaftStorage(true);
            storage.open(tempDir).get(5, TimeUnit.SECONDS);

            storage.appendEntries(List.of(
                    new LogEntryData(1, 1, "a".getBytes()),
                    new LogEntryData(2, 1, "b".getBytes())
            )).get(5, TimeUnit.SECONDS);
            storage.sync().get(5, TimeUnit.SECONDS);

            // Truncate from index 0 removes everything
            storage.truncateSuffix(0).get(5, TimeUnit.SECONDS);
            storage.sync().get(5, TimeUnit.SECONDS);

            // Should be able to replay and get nothing
            storage.close();
            
            FileRaftStorage storage2 = new FileRaftStorage(true);
            storage2.open(tempDir).get(5, TimeUnit.SECONDS);
            List<LogEntryData> replayed = storage2.replayLog().get(5, TimeUnit.SECONDS);
            assertEquals(0, replayed.size());
            storage2.close();
        }

        @Test
        @DisplayName("Multiple truncates in sequence")
        void testMultipleTruncates() throws Exception {
            FileRaftStorage storage = new FileRaftStorage(true);
            storage.open(tempDir).get(5, TimeUnit.SECONDS);

            storage.appendEntries(List.of(
                    new LogEntryData(1, 1, "a".getBytes()),
                    new LogEntryData(2, 1, "b".getBytes()),
                    new LogEntryData(3, 1, "c".getBytes()),
                    new LogEntryData(4, 1, "d".getBytes())
            )).get(5, TimeUnit.SECONDS);
            storage.sync().get(5, TimeUnit.SECONDS);

            storage.truncateSuffix(3).get(5, TimeUnit.SECONDS);
            storage.truncateSuffix(2).get(5, TimeUnit.SECONDS);
            storage.truncateSuffix(1).get(5, TimeUnit.SECONDS);
            storage.sync().get(5, TimeUnit.SECONDS);

            storage.close();
            
            FileRaftStorage storage2 = new FileRaftStorage(true);
            storage2.open(tempDir).get(5, TimeUnit.SECONDS);
            List<LogEntryData> replayed = storage2.replayLog().get(5, TimeUnit.SECONDS);
            assertEquals(0, replayed.size());
            storage2.close();
        }

        @Test
        @DisplayName("Truncate then append")
        void testTruncateThenAppend() throws Exception {
            FileRaftStorage storage = new FileRaftStorage(true);
            storage.open(tempDir).get(5, TimeUnit.SECONDS);

            storage.appendEntries(List.of(
                    new LogEntryData(1, 1, "a".getBytes()),
                    new LogEntryData(2, 1, "b".getBytes()),
                    new LogEntryData(3, 1, "c".getBytes())
            )).get(5, TimeUnit.SECONDS);
            storage.sync().get(5, TimeUnit.SECONDS);

            storage.truncateSuffix(2).get(5, TimeUnit.SECONDS);
            storage.appendEntries(List.of(
                    new LogEntryData(2, 2, "b2".getBytes()),
                    new LogEntryData(3, 2, "c2".getBytes())
            )).get(5, TimeUnit.SECONDS);
            storage.sync().get(5, TimeUnit.SECONDS);

            List<LogEntryData> replayed = storage.replayLog().get(5, TimeUnit.SECONDS);
            assertEquals(3, replayed.size());
            assertEquals(2, replayed.get(1).term());
            assertEquals(2, replayed.get(2).term());

            storage.close();
        }

        @Test
        @DisplayName("Multiple rapid appends")
        void testMultipleRapidAppends() throws Exception {
            FileRaftStorage storage = new FileRaftStorage(true);
            storage.open(tempDir).get(5, TimeUnit.SECONDS);

            for (int i = 1; i <= 100; i++) {
                storage.appendEntries(List.of(new LogEntryData(i, 1, ("entry" + i).getBytes())))
                        .get(5, TimeUnit.SECONDS);
            }
            storage.sync().get(5, TimeUnit.SECONDS);

            List<LogEntryData> replayed = storage.replayLog().get(5, TimeUnit.SECONDS);
            assertEquals(100, replayed.size());

            storage.close();
        }
    }

    // ========================================================================
    // Metadata Edge Cases
    // ========================================================================

    @Nested
    @DisplayName("Metadata Edge Cases")
    class MetadataEdgeCases {

        @Test
        @DisplayName("Update metadata with max long term")
        void testUpdateMetadataMaxTerm() throws Exception {
            FileRaftStorage storage = new FileRaftStorage(true);
            storage.open(tempDir).get(5, TimeUnit.SECONDS);

            storage.updateMetadata(Long.MAX_VALUE, Optional.of("node-max")).get(5, TimeUnit.SECONDS);

            PersistentMeta meta = storage.loadMetadata().get(5, TimeUnit.SECONDS);
            assertEquals(Long.MAX_VALUE, meta.currentTerm());
            assertEquals(Optional.of("node-max"), meta.votedFor());

            storage.close();
        }

        @Test
        @DisplayName("Update metadata with zero term")
        void testUpdateMetadataZeroTerm() throws Exception {
            FileRaftStorage storage = new FileRaftStorage(true);
            storage.open(tempDir).get(5, TimeUnit.SECONDS);

            storage.updateMetadata(0, Optional.of("initial")).get(5, TimeUnit.SECONDS);

            PersistentMeta meta = storage.loadMetadata().get(5, TimeUnit.SECONDS);
            assertEquals(0, meta.currentTerm());

            storage.close();
        }

        @Test
        @DisplayName("Update metadata multiple times")
        void testUpdateMetadataMultipleTimes() throws Exception {
            FileRaftStorage storage = new FileRaftStorage(true);
            storage.open(tempDir).get(5, TimeUnit.SECONDS);

            for (int i = 1; i <= 10; i++) {
                storage.updateMetadata(i, Optional.of("node-" + i)).get(5, TimeUnit.SECONDS);
            }

            PersistentMeta meta = storage.loadMetadata().get(5, TimeUnit.SECONDS);
            assertEquals(10, meta.currentTerm());
            assertEquals(Optional.of("node-10"), meta.votedFor());

            storage.close();
        }

        @Test
        @DisplayName("Update votedFor to empty then back to value")
        void testUpdateVotedForToggle() throws Exception {
            FileRaftStorage storage = new FileRaftStorage(true);
            storage.open(tempDir).get(5, TimeUnit.SECONDS);

            storage.updateMetadata(1, Optional.of("node-1")).get(5, TimeUnit.SECONDS);
            storage.updateMetadata(2, Optional.empty()).get(5, TimeUnit.SECONDS);
            storage.updateMetadata(3, Optional.of("node-3")).get(5, TimeUnit.SECONDS);

            PersistentMeta meta = storage.loadMetadata().get(5, TimeUnit.SECONDS);
            assertEquals(3, meta.currentTerm());
            assertEquals(Optional.of("node-3"), meta.votedFor());

            storage.close();
        }

        @Test
        @DisplayName("Metadata survives close and reopen")
        void testMetadataPersistence() throws Exception {
            FileRaftStorage storage = new FileRaftStorage(true);
            storage.open(tempDir).get(5, TimeUnit.SECONDS);

            storage.updateMetadata(42, Optional.of("persistent")).get(5, TimeUnit.SECONDS);
            storage.close();

            FileRaftStorage storage2 = new FileRaftStorage(true);
            storage2.open(tempDir).get(5, TimeUnit.SECONDS);

            PersistentMeta meta = storage2.loadMetadata().get(5, TimeUnit.SECONDS);
            assertEquals(42, meta.currentTerm());
            assertEquals(Optional.of("persistent"), meta.votedFor());

            storage2.close();
        }

        @Test
        @DisplayName("Metadata with unicode votedFor")
        void testMetadataUnicode() throws Exception {
            FileRaftStorage storage = new FileRaftStorage(true);
            storage.open(tempDir).get(5, TimeUnit.SECONDS);

            storage.updateMetadata(1, Optional.of("节点-α-ノード")).get(5, TimeUnit.SECONDS);

            PersistentMeta meta = storage.loadMetadata().get(5, TimeUnit.SECONDS);
            assertEquals(Optional.of("节点-α-ノード"), meta.votedFor());

            storage.close();
        }

        @Test
        @DisplayName("Metadata with very long votedFor")
        void testMetadataLongVotedFor() throws Exception {
            FileRaftStorage storage = new FileRaftStorage(true);
            storage.open(tempDir).get(5, TimeUnit.SECONDS);

            String longNodeId = "node-" + "x".repeat(10000);
            storage.updateMetadata(1, Optional.of(longNodeId)).get(5, TimeUnit.SECONDS);

            PersistentMeta meta = storage.loadMetadata().get(5, TimeUnit.SECONDS);
            assertEquals(Optional.of(longNodeId), meta.votedFor());

            storage.close();
        }
    }

    // ========================================================================
    // Config Builder Additional Tests
    // ========================================================================

    @Nested
    @DisplayName("Config Builder Additional")
    class ConfigBuilderAdditional {

        @Test
        @DisplayName("Builder resolves system properties")
        void testBuilderResolvesSystemProperties() {
            String originalDir = System.getProperty("raftlog.dataDir");
            String originalSync = System.getProperty("raftlog.syncEnabled");
            String originalVerify = System.getProperty("raftlog.verifyWrites");
            String originalMinSpace = System.getProperty("raftlog.minFreeSpaceMb");
            String originalMaxPayload = System.getProperty("raftlog.maxPayloadSizeMb");

            try {
                System.setProperty("raftlog.dataDir", tempDir.toString());
                System.setProperty("raftlog.syncEnabled", "false");
                System.setProperty("raftlog.verifyWrites", "true");
                System.setProperty("raftlog.minFreeSpaceMb", "256");
                System.setProperty("raftlog.maxPayloadSizeMb", "64");

                RaftStorageConfig config = RaftStorageConfig.builder().build();

                assertEquals(tempDir, config.dataDir());
                assertFalse(config.syncEnabled());
                assertTrue(config.verifyWrites());
                assertEquals(256, config.minFreeSpaceMb());
                assertEquals(64, config.maxPayloadSizeMb());
            } finally {
                // Restore original values
                restoreProperty("raftlog.dataDir", originalDir);
                restoreProperty("raftlog.syncEnabled", originalSync);
                restoreProperty("raftlog.verifyWrites", originalVerify);
                restoreProperty("raftlog.minFreeSpaceMb", originalMinSpace);
                restoreProperty("raftlog.maxPayloadSizeMb", originalMaxPayload);
            }
        }

        @Test
        @DisplayName("Builder handles invalid integer in system property")
        void testBuilderHandlesInvalidInteger() {
            String original = System.getProperty("raftlog.minFreeSpaceMb");
            try {
                System.setProperty("raftlog.minFreeSpaceMb", "not-a-number");

                RaftStorageConfig config = RaftStorageConfig.builder().build();

                // Should fall back to default
                assertEquals(64, config.minFreeSpaceMb());
            } finally {
                restoreProperty("raftlog.minFreeSpaceMb", original);
            }
        }

        @Test
        @DisplayName("Builder handles blank system property")
        void testBuilderHandlesBlankSystemProperty() {
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

        @Test
        @DisplayName("Programmatic value overrides system property")
        void testProgrammaticOverridesSystemProperty() {
            String original = System.getProperty("raftlog.minFreeSpaceMb");
            try {
                System.setProperty("raftlog.minFreeSpaceMb", "256");

                RaftStorageConfig config = RaftStorageConfig.builder()
                        .minFreeSpaceMb(512)
                        .build();

                // Programmatic value should win
                assertEquals(512, config.minFreeSpaceMb());
            } finally {
                restoreProperty("raftlog.minFreeSpaceMb", original);
            }
        }

        private void restoreProperty(String key, String original) {
            if (original == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, original);
            }
        }
    }

    // ========================================================================
    // Config Properties File Tests
    // ========================================================================

    @Nested
    @DisplayName("Config Properties File")
    class ConfigPropertiesFile {

        @Test
        @DisplayName("Properties file in working directory")
        void testPropertiesFileInWorkingDir() throws Exception {
            // This test creates a properties file but since Builder is already 
            // instantiated, we need to verify the loading mechanism works
            Path propsFile = Path.of("raftlog.properties");
            
            // Properties file loading happens at Builder construction time,
            // and the working directory might already have a file or not.
            // We just verify the config loads without error
            RaftStorageConfig config = RaftStorageConfig.load();
            assertNotNull(config);
            assertNotNull(config.dataDir());
        }
    }

    // ========================================================================
    // Verify Writes Tests
    // ========================================================================

    @Nested
    @DisplayName("Verify Writes")
    class VerifyWritesTests {

        @Test
        @DisplayName("Verify writes enabled - normal operation")
        void testVerifyWritesNormalOperation() throws Exception {
            RaftStorageConfig config = RaftStorageConfig.builder()
                    .dataDir(tempDir)
                    .verifyWrites(true)
                    .build();

            FileRaftStorage storage = new FileRaftStorage(config);
            storage.open().get(5, TimeUnit.SECONDS);

            // Normal write should succeed with verification
            storage.appendEntries(List.of(
                    new LogEntryData(1, 1, "verified".getBytes())
            )).get(5, TimeUnit.SECONDS);
            storage.sync().get(5, TimeUnit.SECONDS);

            List<LogEntryData> replayed = storage.replayLog().get(5, TimeUnit.SECONDS);
            assertEquals(1, replayed.size());

            storage.close();
        }

        @Test
        @DisplayName("Verify writes with multiple entries")
        void testVerifyWritesMultipleEntries() throws Exception {
            RaftStorageConfig config = RaftStorageConfig.builder()
                    .dataDir(tempDir)
                    .verifyWrites(true)
                    .build();

            FileRaftStorage storage = new FileRaftStorage(config);
            storage.open().get(5, TimeUnit.SECONDS);

            storage.appendEntries(List.of(
                    new LogEntryData(1, 1, "first".getBytes()),
                    new LogEntryData(2, 1, "second".getBytes()),
                    new LogEntryData(3, 1, "third".getBytes())
            )).get(5, TimeUnit.SECONDS);
            storage.sync().get(5, TimeUnit.SECONDS);

            List<LogEntryData> replayed = storage.replayLog().get(5, TimeUnit.SECONDS);
            assertEquals(3, replayed.size());

            storage.close();
        }
    }

    // ========================================================================
    // Sync Disabled Tests
    // ========================================================================

    @Nested
    @DisplayName("Sync Disabled")
    class SyncDisabledTests {

        @Test
        @DisplayName("Operations work with sync disabled")
        void testSyncDisabledOperations() throws Exception {
            FileRaftStorage storage = new FileRaftStorage(false); // sync disabled
            storage.open(tempDir).get(5, TimeUnit.SECONDS);

            storage.appendEntries(List.of(
                    new LogEntryData(1, 1, "no-sync".getBytes())
            )).get(5, TimeUnit.SECONDS);

            // Sync call should complete even when disabled
            storage.sync().get(5, TimeUnit.SECONDS);

            List<LogEntryData> replayed = storage.replayLog().get(5, TimeUnit.SECONDS);
            assertEquals(1, replayed.size());

            storage.close();
        }

        @Test
        @DisplayName("Metadata works with sync disabled")
        void testMetadataSyncDisabled() throws Exception {
            FileRaftStorage storage = new FileRaftStorage(false);
            storage.open(tempDir).get(5, TimeUnit.SECONDS);

            storage.updateMetadata(5, Optional.of("no-sync-node")).get(5, TimeUnit.SECONDS);

            PersistentMeta meta = storage.loadMetadata().get(5, TimeUnit.SECONDS);
            assertEquals(5, meta.currentTerm());

            storage.close();
        }
    }

    // ========================================================================
    // Recovery and Corruption Edge Cases
    // ========================================================================

    @Nested
    @DisplayName("Recovery Edge Cases")
    class RecoveryEdgeCases {

        @Test
        @DisplayName("Replay empty WAL file")
        void testReplayEmptyWal() throws Exception {
            // Create empty WAL file
            Files.createDirectories(tempDir);
            Files.createFile(tempDir.resolve("raft.log"));
            Files.createFile(tempDir.resolve("raft.lock"));

            FileRaftStorage storage = new FileRaftStorage(true);
            storage.open(tempDir).get(5, TimeUnit.SECONDS);

            List<LogEntryData> replayed = storage.replayLog().get(5, TimeUnit.SECONDS);
            assertTrue(replayed.isEmpty());

            storage.close();
        }

        @Test
        @DisplayName("Replay WAL with only truncate records")
        void testReplayOnlyTruncateRecords() throws Exception {
            FileRaftStorage storage = new FileRaftStorage(true);
            storage.open(tempDir).get(5, TimeUnit.SECONDS);

            // Write entries then truncate all
            storage.appendEntries(List.of(
                    new LogEntryData(1, 1, "a".getBytes()),
                    new LogEntryData(2, 1, "b".getBytes())
            )).get(5, TimeUnit.SECONDS);
            storage.sync().get(5, TimeUnit.SECONDS);

            storage.truncateSuffix(1).get(5, TimeUnit.SECONDS);
            storage.sync().get(5, TimeUnit.SECONDS);

            storage.close();

            // Reopen and verify
            FileRaftStorage storage2 = new FileRaftStorage(true);
            storage2.open(tempDir).get(5, TimeUnit.SECONDS);

            List<LogEntryData> replayed = storage2.replayLog().get(5, TimeUnit.SECONDS);
            assertTrue(replayed.isEmpty());

            storage2.close();
        }

        @Test
        @DisplayName("Replay interleaved append and truncate")
        void testReplayInterleavedAppendTruncate() throws Exception {
            FileRaftStorage storage = new FileRaftStorage(true);
            storage.open(tempDir).get(5, TimeUnit.SECONDS);

            storage.appendEntries(List.of(new LogEntryData(1, 1, "a".getBytes()))).get(5, TimeUnit.SECONDS);
            storage.appendEntries(List.of(new LogEntryData(2, 1, "b".getBytes()))).get(5, TimeUnit.SECONDS);
            storage.truncateSuffix(2).get(5, TimeUnit.SECONDS);
            storage.appendEntries(List.of(new LogEntryData(2, 2, "b2".getBytes()))).get(5, TimeUnit.SECONDS);
            storage.appendEntries(List.of(new LogEntryData(3, 2, "c".getBytes()))).get(5, TimeUnit.SECONDS);
            storage.truncateSuffix(3).get(5, TimeUnit.SECONDS);
            storage.appendEntries(List.of(new LogEntryData(3, 3, "c3".getBytes()))).get(5, TimeUnit.SECONDS);
            storage.sync().get(5, TimeUnit.SECONDS);

            storage.close();

            FileRaftStorage storage2 = new FileRaftStorage(true);
            storage2.open(tempDir).get(5, TimeUnit.SECONDS);

            List<LogEntryData> replayed = storage2.replayLog().get(5, TimeUnit.SECONDS);
            assertEquals(3, replayed.size());
            assertEquals(1, replayed.get(0).term());
            assertEquals(2, replayed.get(1).term());
            assertEquals(3, replayed.get(2).term());

            storage2.close();
        }
    }

    // ========================================================================
    // Constructor Variations
    // ========================================================================

    @Nested
    @DisplayName("Constructor Variations")
    class ConstructorVariations {

        @Test
        @DisplayName("Default constructor")
        void testDefaultConstructor() throws Exception {
            FileRaftStorage storage = new FileRaftStorage();
            // Opens with default config
            RaftStorageConfig config = storage.config();
            assertNotNull(config);
            assertTrue(config.syncEnabled()); // default is true
        }

        @Test
        @DisplayName("Boolean constructor - sync enabled")
        void testBooleanConstructorSyncEnabled() throws Exception {
            FileRaftStorage storage = new FileRaftStorage(true);
            storage.open(tempDir).get(5, TimeUnit.SECONDS);
            
            assertTrue(storage.config().syncEnabled());
            
            storage.close();
        }

        @Test
        @DisplayName("Boolean constructor - sync disabled")
        void testBooleanConstructorSyncDisabled() throws Exception {
            FileRaftStorage storage = new FileRaftStorage(false);
            storage.open(tempDir).get(5, TimeUnit.SECONDS);
            
            assertFalse(storage.config().syncEnabled());
            
            storage.close();
        }

        @Test
        @DisplayName("Two boolean constructor")
        void testTwoBooleanConstructor() throws Exception {
            FileRaftStorage storage = new FileRaftStorage(true, true);
            storage.open(tempDir).get(5, TimeUnit.SECONDS);
            
            assertTrue(storage.config().syncEnabled());
            assertTrue(storage.config().verifyWrites());
            
            storage.close();
        }

        @Test
        @DisplayName("Config constructor")
        void testConfigConstructor() throws Exception {
            RaftStorageConfig config = RaftStorageConfig.builder()
                    .dataDir(tempDir)
                    .syncEnabled(false)
                    .verifyWrites(true)
                    .minFreeSpaceMb(32)
                    .maxPayloadSizeMb(8)
                    .build();

            FileRaftStorage storage = new FileRaftStorage(config);
            storage.open().get(5, TimeUnit.SECONDS);

            RaftStorageConfig actual = storage.config();
            assertEquals(tempDir, actual.dataDir());
            assertFalse(actual.syncEnabled());
            assertTrue(actual.verifyWrites());
            assertEquals(32, actual.minFreeSpaceMb());
            assertEquals(8, actual.maxPayloadSizeMb());

            storage.close();
        }
    }

    // ========================================================================
    // Close and Cleanup
    // ========================================================================

    @Nested
    @DisplayName("Close and Cleanup")
    class CloseAndCleanup {

        @Test
        @DisplayName("Close releases resources")
        void testCloseReleasesResources() throws Exception {
            FileRaftStorage storage = new FileRaftStorage(true);
            storage.open(tempDir).get(5, TimeUnit.SECONDS);

            storage.appendEntries(List.of(new LogEntryData(1, 1, "data".getBytes())))
                    .get(5, TimeUnit.SECONDS);
            storage.sync().get(5, TimeUnit.SECONDS);

            storage.close();

            // Another storage should be able to acquire lock
            FileRaftStorage storage2 = new FileRaftStorage(true);
            storage2.open(tempDir).get(5, TimeUnit.SECONDS);
            storage2.close();
        }

        @Test
        @DisplayName("Multiple close calls are safe")
        void testMultipleCloseCalls() throws Exception {
            FileRaftStorage storage = new FileRaftStorage(true);
            storage.open(tempDir).get(5, TimeUnit.SECONDS);

            storage.close();
            storage.close(); // Should not throw
            storage.close();
        }
    }
}
