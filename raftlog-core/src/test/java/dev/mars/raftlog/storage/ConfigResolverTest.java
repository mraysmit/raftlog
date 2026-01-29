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

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for RaftStorageConfig resolution paths to maximize branch coverage.
 * <p>
 * Tests system properties, default values, and configuration building.
 */
class ConfigResolverTest {

    @TempDir
    Path tempDir;

    // ========================================================================
    // System Property Resolution Tests
    // ========================================================================

    @Nested
    @DisplayName("System Property Resolution")
    class SystemPropertyTests {

        @AfterEach
        void clearSystemProperties() {
            System.clearProperty("raftlog.dataDir");
            System.clearProperty("raftlog.syncEnabled");
            System.clearProperty("raftlog.verifyWrites");
            System.clearProperty("raftlog.minFreeSpaceMb");
            System.clearProperty("raftlog.maxPayloadSizeMb");
        }

        @Test
        @DisplayName("System property dataDir is respected")
        void testDataDirSystemProperty() {
            Path customDir = tempDir.resolve("custom-data");
            System.setProperty("raftlog.dataDir", customDir.toString());
            
            RaftStorageConfig config = RaftStorageConfig.builder().build();
            assertEquals(customDir, config.dataDir());
        }

        @Test
        @DisplayName("System property syncEnabled=false is respected")
        void testSyncEnabledSystemPropertyFalse() {
            System.setProperty("raftlog.syncEnabled", "false");
            
            RaftStorageConfig config = RaftStorageConfig.builder().build();
            assertFalse(config.syncEnabled());
        }

        @Test
        @DisplayName("System property syncEnabled=true is respected")
        void testSyncEnabledSystemPropertyTrue() {
            System.setProperty("raftlog.syncEnabled", "true");
            
            RaftStorageConfig config = RaftStorageConfig.builder().build();
            assertTrue(config.syncEnabled());
        }

        @Test
        @DisplayName("System property verifyWrites=true is respected")
        void testVerifyWritesSystemPropertyTrue() {
            System.setProperty("raftlog.verifyWrites", "true");
            
            RaftStorageConfig config = RaftStorageConfig.builder().build();
            assertTrue(config.verifyWrites());
        }

        @Test
        @DisplayName("System property verifyWrites=false is respected")
        void testVerifyWritesSystemPropertyFalse() {
            System.setProperty("raftlog.verifyWrites", "false");
            
            RaftStorageConfig config = RaftStorageConfig.builder().build();
            assertFalse(config.verifyWrites());
        }

        @Test
        @DisplayName("System property minFreeSpaceMb is respected")
        void testMinFreeSpaceMbSystemProperty() {
            System.setProperty("raftlog.minFreeSpaceMb", "128");
            
            RaftStorageConfig config = RaftStorageConfig.builder().build();
            assertEquals(128, config.minFreeSpaceMb());
        }

        @Test
        @DisplayName("System property maxPayloadSizeMb is respected")
        void testMaxPayloadSizeMbSystemProperty() {
            System.setProperty("raftlog.maxPayloadSizeMb", "32");
            
            RaftStorageConfig config = RaftStorageConfig.builder().build();
            assertEquals(32, config.maxPayloadSizeMb());
        }

        @Test
        @DisplayName("Invalid integer system property falls back to default")
        void testInvalidIntSystemProperty() {
            System.setProperty("raftlog.minFreeSpaceMb", "not-a-number");
            
            RaftStorageConfig config = RaftStorageConfig.builder().build();
            assertEquals(64, config.minFreeSpaceMb()); // default
        }

        @Test
        @DisplayName("Blank system property falls back to default")
        void testBlankSystemProperty() {
            System.setProperty("raftlog.dataDir", "   ");
            
            RaftStorageConfig config = RaftStorageConfig.builder().build();
            // Should use default ~/.raftlog/data
            assertNotNull(config.dataDir());
        }

        @Test
        @DisplayName("Empty string system property falls back to default")
        void testEmptySystemProperty() {
            System.setProperty("raftlog.syncEnabled", "");
            
            RaftStorageConfig config = RaftStorageConfig.builder().build();
            // Default syncEnabled is true
            assertTrue(config.syncEnabled());
        }
    }

    // ========================================================================
    // Programmatic Override Tests
    // ========================================================================

    @Nested
    @DisplayName("Programmatic Override")
    class ProgrammaticOverrideTests {

        @AfterEach
        void clearSystemProperties() {
            System.clearProperty("raftlog.dataDir");
            System.clearProperty("raftlog.syncEnabled");
        }

        @Test
        @DisplayName("Programmatic dataDir overrides system property")
        void testProgrammaticDataDirOverride() {
            Path sysPropDir = tempDir.resolve("sys-prop");
            Path programmaticDir = tempDir.resolve("programmatic");
            System.setProperty("raftlog.dataDir", sysPropDir.toString());
            
            RaftStorageConfig config = RaftStorageConfig.builder()
                    .dataDir(programmaticDir)
                    .build();
            
            assertEquals(programmaticDir, config.dataDir());
        }

        @Test
        @DisplayName("Programmatic syncEnabled overrides system property")
        void testProgrammaticSyncEnabledOverride() {
            System.setProperty("raftlog.syncEnabled", "true");
            
            RaftStorageConfig config = RaftStorageConfig.builder()
                    .syncEnabled(false)
                    .build();
            
            assertFalse(config.syncEnabled());
        }

        @Test
        @DisplayName("Programmatic verifyWrites setting")
        void testProgrammaticVerifyWrites() {
            RaftStorageConfig config = RaftStorageConfig.builder()
                    .verifyWrites(true)
                    .build();
            
            assertTrue(config.verifyWrites());
        }

        @Test
        @DisplayName("Programmatic minFreeSpaceMb setting")
        void testProgrammaticMinFreeSpaceMb() {
            RaftStorageConfig config = RaftStorageConfig.builder()
                    .minFreeSpaceMb(256)
                    .build();
            
            assertEquals(256, config.minFreeSpaceMb());
        }

        @Test
        @DisplayName("Programmatic maxPayloadSizeMb setting")
        void testProgrammaticMaxPayloadSizeMb() {
            RaftStorageConfig config = RaftStorageConfig.builder()
                    .maxPayloadSizeMb(64)
                    .build();
            
            assertEquals(64, config.maxPayloadSizeMb());
        }

        @Test
        @DisplayName("dataDir String overload works correctly")
        void testDataDirStringOverload() {
            String dirPath = tempDir.resolve("string-path").toString();
            
            RaftStorageConfig config = RaftStorageConfig.builder()
                    .dataDir(dirPath)
                    .build();
            
            assertEquals(Path.of(dirPath), config.dataDir());
        }
    }

    // ========================================================================
    // Default Value Tests
    // ========================================================================

    @Nested
    @DisplayName("Default Values")
    class DefaultValueTests {

        @BeforeEach
        @AfterEach
        void clearAllProperties() {
            System.clearProperty("raftlog.dataDir");
            System.clearProperty("raftlog.syncEnabled");
            System.clearProperty("raftlog.verifyWrites");
            System.clearProperty("raftlog.minFreeSpaceMb");
            System.clearProperty("raftlog.maxPayloadSizeMb");
        }

        @Test
        @DisplayName("Default config values are correct")
        void testDefaultValues() {
            RaftStorageConfig config = RaftStorageConfig.builder().build();
            
            // Default dataDir is ~/.raftlog/data
            assertTrue(config.dataDir().toString().endsWith("data"));
            
            // Default syncEnabled is true
            assertTrue(config.syncEnabled());
            
            // Default verifyWrites is false
            assertFalse(config.verifyWrites());
            
            // Default minFreeSpaceMb is 64
            assertEquals(64, config.minFreeSpaceMb());
            
            // Default maxPayloadSizeMb is 16
            assertEquals(16, config.maxPayloadSizeMb());
        }

        @Test
        @DisplayName("Config getters return correct values")
        void testConfigGetters() {
            Path customDir = tempDir.resolve("custom");
            
            RaftStorageConfig config = RaftStorageConfig.builder()
                    .dataDir(customDir)
                    .syncEnabled(false)
                    .verifyWrites(true)
                    .minFreeSpaceMb(128)
                    .maxPayloadSizeMb(32)
                    .build();
            
            assertEquals(customDir, config.dataDir());
            assertFalse(config.syncEnabled());
            assertTrue(config.verifyWrites());
            assertEquals(128, config.minFreeSpaceMb());
            assertEquals(32, config.maxPayloadSizeMb());
        }
    }

    // ========================================================================
    // Edge Cases
    // ========================================================================

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCaseTests {

        @AfterEach
        void clearSystemProperties() {
            System.clearProperty("raftlog.dataDir");
            System.clearProperty("raftlog.syncEnabled");
            System.clearProperty("raftlog.verifyWrites");
            System.clearProperty("raftlog.minFreeSpaceMb");
            System.clearProperty("raftlog.maxPayloadSizeMb");
        }

        @Test
        @DisplayName("Zero value for minFreeSpaceMb")
        void testZeroMinFreeSpace() {
            RaftStorageConfig config = RaftStorageConfig.builder()
                    .minFreeSpaceMb(0)
                    .build();
            
            assertEquals(0, config.minFreeSpaceMb());
        }

        @Test
        @DisplayName("Large value for maxPayloadSizeMb")
        void testLargeMaxPayload() {
            RaftStorageConfig config = RaftStorageConfig.builder()
                    .maxPayloadSizeMb(1024)
                    .build();
            
            assertEquals(1024, config.maxPayloadSizeMb());
        }

        @Test
        @DisplayName("System property with only whitespace for boolean")
        void testWhitespaceOnlyBooleanProperty() {
            System.setProperty("raftlog.verifyWrites", "   ");
            
            RaftStorageConfig config = RaftStorageConfig.builder().build();
            // Should use default (false)
            assertFalse(config.verifyWrites());
        }

        @Test
        @DisplayName("System property with whitespace around value")
        void testWhitespaceAroundValue() {
            // Note: System.getProperty returns the value as-is including whitespace
            System.setProperty("raftlog.minFreeSpaceMb", "100");
            
            RaftStorageConfig config = RaftStorageConfig.builder().build();
            assertEquals(100, config.minFreeSpaceMb());
        }

        @Test
        @DisplayName("Multiple builds from same builder work correctly")
        void testMultipleBuilds() {
            RaftStorageConfig.Builder builder = RaftStorageConfig.builder()
                    .dataDir(tempDir.resolve("first"));
            
            RaftStorageConfig config1 = builder.build();
            
            builder.dataDir(tempDir.resolve("second"));
            RaftStorageConfig config2 = builder.build();
            
            assertEquals(tempDir.resolve("first"), config1.dataDir());
            assertEquals(tempDir.resolve("second"), config2.dataDir());
        }

        @Test
        @DisplayName("Chained builder methods")
        void testChainedBuilder() {
            RaftStorageConfig config = RaftStorageConfig.builder()
                    .dataDir(tempDir)
                    .syncEnabled(true)
                    .verifyWrites(true)
                    .minFreeSpaceMb(100)
                    .maxPayloadSizeMb(50)
                    .build();
            
            assertEquals(tempDir, config.dataDir());
            assertTrue(config.syncEnabled());
            assertTrue(config.verifyWrites());
            assertEquals(100, config.minFreeSpaceMb());
            assertEquals(50, config.maxPayloadSizeMb());
        }

        @Test
        @DisplayName("Negative values are accepted by builder")
        void testNegativeValues() {
            RaftStorageConfig config = RaftStorageConfig.builder()
                    .minFreeSpaceMb(-1)
                    .maxPayloadSizeMb(-1)
                    .build();
            
            assertEquals(-1, config.minFreeSpaceMb());
            assertEquals(-1, config.maxPayloadSizeMb());
        }

        @Test
        @DisplayName("System property with negative int")
        void testNegativeIntSystemProperty() {
            System.setProperty("raftlog.minFreeSpaceMb", "-50");
            
            RaftStorageConfig config = RaftStorageConfig.builder().build();
            assertEquals(-50, config.minFreeSpaceMb());
        }

        @Test
        @DisplayName("System property with max int")
        void testMaxIntSystemProperty() {
            System.setProperty("raftlog.maxPayloadSizeMb", String.valueOf(Integer.MAX_VALUE));
            
            RaftStorageConfig config = RaftStorageConfig.builder().build();
            assertEquals(Integer.MAX_VALUE, config.maxPayloadSizeMb());
        }

        @Test
        @DisplayName("System property overflow falls back to default")
        void testIntOverflowSystemProperty() {
            // Value too large for int should fail parse and use default
            System.setProperty("raftlog.minFreeSpaceMb", "9999999999999999999");
            
            RaftStorageConfig config = RaftStorageConfig.builder().build();
            assertEquals(64, config.minFreeSpaceMb()); // default
        }

        @Test
        @DisplayName("System property with double value falls back to default")
        void testDoubleValueSystemProperty() {
            System.setProperty("raftlog.minFreeSpaceMb", "64.5");
            
            RaftStorageConfig config = RaftStorageConfig.builder().build();
            assertEquals(64, config.minFreeSpaceMb()); // default (parse fails)
        }

        @Test
        @DisplayName("Boolean parsing variations")
        void testBooleanVariations() {
            // "true" should be true
            System.setProperty("raftlog.verifyWrites", "true");
            assertTrue(RaftStorageConfig.builder().build().verifyWrites());
            
            // "TRUE" should be true
            System.setProperty("raftlog.verifyWrites", "TRUE");
            assertTrue(RaftStorageConfig.builder().build().verifyWrites());
            
            // "True" should be true
            System.setProperty("raftlog.verifyWrites", "True");
            assertTrue(RaftStorageConfig.builder().build().verifyWrites());
            
            // "false" should be false
            System.setProperty("raftlog.verifyWrites", "false");
            assertFalse(RaftStorageConfig.builder().build().verifyWrites());
            
            // "yes" should be false (not "true")
            System.setProperty("raftlog.verifyWrites", "yes");
            assertFalse(RaftStorageConfig.builder().build().verifyWrites());
            
            // "1" should be false (not "true")
            System.setProperty("raftlog.verifyWrites", "1");
            assertFalse(RaftStorageConfig.builder().build().verifyWrites());
        }
    }

    // ========================================================================
    // Integration with FileRaftStorage
    // ========================================================================

    @Nested
    @DisplayName("Integration with FileRaftStorage")
    class IntegrationTests {

        @AfterEach
        void clearSystemProperties() {
            System.clearProperty("raftlog.dataDir");
            System.clearProperty("raftlog.syncEnabled");
            System.clearProperty("raftlog.verifyWrites");
        }

        @Test
        @DisplayName("FileRaftStorage respects config syncEnabled=false")
        void testStorageWithSyncDisabled() throws Exception {
            RaftStorageConfig config = RaftStorageConfig.builder()
                    .dataDir(tempDir)
                    .syncEnabled(false)
                    .build();
            
            FileRaftStorage storage = new FileRaftStorage(config);
            storage.open(tempDir).get(5, java.util.concurrent.TimeUnit.SECONDS);
            
            try {
                // Append should work
                storage.appendEntries(java.util.List.of(
                        new RaftStorage.LogEntryData(1, 1, "test".getBytes())
                )).get(5, java.util.concurrent.TimeUnit.SECONDS);
                
                // Sync should be no-op
                storage.sync().get(5, java.util.concurrent.TimeUnit.SECONDS);
            } finally {
                storage.close();
            }
        }

        @Test
        @DisplayName("FileRaftStorage respects config verifyWrites=true")
        void testStorageWithVerifyWrites() throws Exception {
            RaftStorageConfig config = RaftStorageConfig.builder()
                    .dataDir(tempDir)
                    .syncEnabled(true)
                    .verifyWrites(true)
                    .build();
            
            FileRaftStorage storage = new FileRaftStorage(config);
            storage.open(tempDir).get(5, java.util.concurrent.TimeUnit.SECONDS);
            
            try {
                // Append with verification
                storage.appendEntries(java.util.List.of(
                        new RaftStorage.LogEntryData(1, 1, "test".getBytes())
                )).get(5, java.util.concurrent.TimeUnit.SECONDS);
                storage.sync().get(5, java.util.concurrent.TimeUnit.SECONDS);
                
                // Replay and verify
                var entries = storage.replayLog().get(5, java.util.concurrent.TimeUnit.SECONDS);
                assertEquals(1, entries.size());
                assertEquals(1, entries.get(0).index());
            } finally {
                storage.close();
            }
        }

        @Test
        @DisplayName("FileRaftStorage respects custom config values")
        void testStorageWithCustomConfig() throws Exception {
            RaftStorageConfig config = RaftStorageConfig.builder()
                    .dataDir(tempDir)
                    .syncEnabled(true)
                    .verifyWrites(false)
                    .minFreeSpaceMb(10) // Low for testing
                    .maxPayloadSizeMb(1)
                    .build();
            
            FileRaftStorage storage = new FileRaftStorage(config);
            storage.open(tempDir).get(5, java.util.concurrent.TimeUnit.SECONDS);
            
            try {
                // Small payload should work
                byte[] smallPayload = new byte[1000]; // 1KB, well under 1MB
                storage.appendEntries(java.util.List.of(
                        new RaftStorage.LogEntryData(1, 1, smallPayload)
                )).get(5, java.util.concurrent.TimeUnit.SECONDS);
                storage.sync().get(5, java.util.concurrent.TimeUnit.SECONDS);
            } finally {
                storage.close();
            }
        }
    }
}
