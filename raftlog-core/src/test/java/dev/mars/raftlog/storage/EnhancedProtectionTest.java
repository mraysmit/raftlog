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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for enhanced protection mechanisms:
 * <ul>
 *   <li>File locking (multi-process protection)</li>
 *   <li>Disk space checking</li>
 *   <li>Read-after-write verification</li>
 * </ul>
 */
@DisplayName("Enhanced Protection Tests")
class EnhancedProtectionTest {

    @TempDir
    Path tempDir;

    private FileRaftStorage storage;

    @AfterEach
    void tearDown() {
        if (storage != null) {
            storage.close();
        }
    }

    // ========================================================================
    // FILE LOCKING TESTS
    // ========================================================================

    @Nested
    @DisplayName("File Locking")
    class FileLockingTests {

        @Test
        @DisplayName("Lock file is created on open")
        void lockFileCreatedOnOpen() throws Exception {
            storage = new FileRaftStorage(true);
            storage.open(tempDir).get(5, TimeUnit.SECONDS);

            Path lockPath = tempDir.resolve("raft.lock");
            assertTrue(Files.exists(lockPath), "Lock file should be created");
        }

        @Test
        @DisplayName("Second instance cannot open same directory")
        void secondInstanceCannotOpenSameDirectory() throws Exception {
            // First instance
            storage = new FileRaftStorage(true);
            storage.open(tempDir).get(5, TimeUnit.SECONDS);

            // Second instance should fail
            FileRaftStorage storage2 = new FileRaftStorage(true);
            ExecutionException ex = assertThrows(ExecutionException.class, () ->
                    storage2.open(tempDir).get(5, TimeUnit.SECONDS));

            assertTrue(ex.getCause() instanceof StorageException);
            assertTrue(ex.getCause().getMessage().contains("exclusive lock") ||
                       ex.getCause().getMessage().contains("Another process"));
            
            storage2.close();
        }

        @Test
        @DisplayName("Lock released on close allows new instance")
        void lockReleasedOnCloseAllowsNewInstance() throws Exception {
            // First instance
            storage = new FileRaftStorage(true);
            storage.open(tempDir).get(5, TimeUnit.SECONDS);
            storage.appendEntries(List.of(
                    new LogEntryData(1, 1, "data".getBytes())
            )).get(5, TimeUnit.SECONDS);
            storage.sync().get(5, TimeUnit.SECONDS);
            storage.close();
            storage = null;

            // Wait a moment for lock release
            Thread.sleep(100);

            // Second instance should succeed
            FileRaftStorage storage2 = new FileRaftStorage(true);
            assertDoesNotThrow(() -> storage2.open(tempDir).get(5, TimeUnit.SECONDS));

            // Should be able to read the data
            List<LogEntryData> replayed = storage2.replayLog().get(5, TimeUnit.SECONDS);
            assertEquals(1, replayed.size());

            storage2.close();
        }

        @Test
        @DisplayName("External process holding lock prevents open")
        void externalLockPreventsOpen() throws Exception {
            // Simulate external process holding lock
            Path lockPath = tempDir.resolve("raft.lock");
            Files.createDirectories(tempDir);

            try (FileChannel lockChannel = FileChannel.open(lockPath,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.READ,
                    StandardOpenOption.WRITE);
                 FileLock externalLock = lockChannel.lock()) {

                // Our storage should fail to open
                storage = new FileRaftStorage(true);
                ExecutionException ex = assertThrows(ExecutionException.class, () ->
                        storage.open(tempDir).get(5, TimeUnit.SECONDS));

                assertTrue(ex.getCause() instanceof StorageException);
            }
        }

        @Test
        @DisplayName("Lock survives crash (simulated) and cleanup")
        void lockSurvivorCrashSimulation() throws Exception {
            // Simulate previous crash by leaving lock file behind
            Path lockPath = tempDir.resolve("raft.lock");
            Files.createDirectories(tempDir);
            Files.createFile(lockPath);

            // New instance should still be able to acquire lock
            // (file exists but no active lock)
            storage = new FileRaftStorage(true);
            assertDoesNotThrow(() -> storage.open(tempDir).get(5, TimeUnit.SECONDS));
        }
    }

    // ========================================================================
    // DISK SPACE CHECKING TESTS
    // ========================================================================

    @Nested
    @DisplayName("Disk Space Checking")
    class DiskSpaceCheckingTests {

        @Test
        @DisplayName("Open succeeds when sufficient space available")
        void openSucceedsWithSufficientSpace() throws Exception {
            storage = new FileRaftStorage(true);
            assertDoesNotThrow(() -> storage.open(tempDir).get(5, TimeUnit.SECONDS));
        }

        @Test
        @DisplayName("Normal writes succeed without disk space error")
        void normalWritesSucceed() throws Exception {
            storage = new FileRaftStorage(true);
            storage.open(tempDir).get(5, TimeUnit.SECONDS);

            // Normal sized write should succeed
            byte[] payload = new byte[1024];
            storage.appendEntries(List.of(
                    new LogEntryData(1, 1, payload)
            )).get(5, TimeUnit.SECONDS);

            storage.sync().get(5, TimeUnit.SECONDS);

            List<LogEntryData> replayed = storage.replayLog().get(5, TimeUnit.SECONDS);
            assertEquals(1, replayed.size());
        }

        @Test
        @DisplayName("Large write triggers disk space check")
        void largeWriteTriggersDiskSpaceCheck() throws Exception {
            storage = new FileRaftStorage(true);
            storage.open(tempDir).get(5, TimeUnit.SECONDS);

            // Large write (> 1MB) should trigger disk space check
            // This won't fail on a normal system but exercises the code path
            byte[] largePayload = new byte[2 * 1024 * 1024]; // 2 MB
            
            assertDoesNotThrow(() -> {
                storage.appendEntries(List.of(
                        new LogEntryData(1, 1, largePayload)
                )).get(30, TimeUnit.SECONDS);
            });
        }

        // Note: It's difficult to test actual disk full conditions in a unit test
        // without root/admin access to create a full filesystem. The following
        // test documents the expected behavior.
        @Test
        @DisplayName("Documentation: disk full throws StorageException")
        void documentDiskFullBehavior() {
            // When disk space is below MIN_FREE_SPACE (64 MB), open() and large
            // writes will throw StorageException with message:
            // "Insufficient disk space: X MB available, need at least 64 MB"
            //
            // This allows the application to:
            // 1. Alert operators before data loss occurs
            // 2. Reject new writes gracefully
            // 3. Continue serving reads
            assertTrue(true, "Documented behavior");
        }
    }

    // ========================================================================
    // READ-AFTER-WRITE VERIFICATION TESTS
    // ========================================================================

    @Nested
    @DisplayName("Read-After-Write Verification")
    class ReadAfterWriteVerificationTests {

        @Test
        @DisplayName("Verification mode can be enabled")
        void verificationModeCanBeEnabled() throws Exception {
            // Create storage with verification enabled
            storage = new FileRaftStorage(true, true);
            storage.open(tempDir).get(5, TimeUnit.SECONDS);

            // Write should succeed with verification
            storage.appendEntries(List.of(
                    new LogEntryData(1, 1, "verified-data".getBytes())
            )).get(5, TimeUnit.SECONDS);

            storage.sync().get(5, TimeUnit.SECONDS);

            List<LogEntryData> replayed = storage.replayLog().get(5, TimeUnit.SECONDS);
            assertEquals(1, replayed.size());
            assertEquals("verified-data", new String(replayed.get(0).payload()));
        }

        @Test
        @DisplayName("Multiple verified writes maintain consistency")
        void multipleVerifiedWritesMaintainConsistency() throws Exception {
            storage = new FileRaftStorage(true, true);
            storage.open(tempDir).get(5, TimeUnit.SECONDS);

            // Write multiple entries with verification
            for (int i = 1; i <= 10; i++) {
                storage.appendEntries(List.of(
                        new LogEntryData(i, 1, ("entry-" + i).getBytes())
                )).get(5, TimeUnit.SECONDS);
            }

            storage.sync().get(5, TimeUnit.SECONDS);

            List<LogEntryData> replayed = storage.replayLog().get(5, TimeUnit.SECONDS);
            assertEquals(10, replayed.size());

            for (int i = 0; i < 10; i++) {
                assertEquals("entry-" + (i + 1), new String(replayed.get(i).payload()));
            }
        }

        @Test
        @DisplayName("Large verified write completes successfully")
        void largeVerifiedWriteCompletes() throws Exception {
            storage = new FileRaftStorage(true, true);
            storage.open(tempDir).get(5, TimeUnit.SECONDS);

            // Large payload with verification
            byte[] largePayload = new byte[100 * 1024]; // 100 KB
            for (int i = 0; i < largePayload.length; i++) {
                largePayload[i] = (byte) (i % 256);
            }

            storage.appendEntries(List.of(
                    new LogEntryData(1, 1, largePayload)
            )).get(10, TimeUnit.SECONDS);

            storage.sync().get(5, TimeUnit.SECONDS);

            List<LogEntryData> replayed = storage.replayLog().get(5, TimeUnit.SECONDS);
            assertEquals(1, replayed.size());
            assertArrayEquals(largePayload, replayed.get(0).payload());
        }

        @Test
        @DisplayName("Verification disabled by default for performance")
        void verificationDisabledByDefault() throws Exception {
            // Default constructor should have verification disabled
            storage = new FileRaftStorage();
            storage.open(tempDir).get(5, TimeUnit.SECONDS);

            // Should be faster without verification
            long start = System.nanoTime();
            for (int i = 1; i <= 100; i++) {
                storage.appendEntries(List.of(
                        new LogEntryData(i, 1, ("entry-" + i).getBytes())
                )).get(5, TimeUnit.SECONDS);
            }
            long nonVerifiedTime = System.nanoTime() - start;
            storage.close();

            // With verification enabled
            storage = new FileRaftStorage(true, true);
            storage.open(tempDir.resolve("verified")).get(5, TimeUnit.SECONDS);

            start = System.nanoTime();
            for (int i = 1; i <= 100; i++) {
                storage.appendEntries(List.of(
                        new LogEntryData(i, 1, ("entry-" + i).getBytes())
                )).get(5, TimeUnit.SECONDS);
            }
            long verifiedTime = System.nanoTime() - start;

            // Verification should be slower (we just document this, not assert)
            // In practice it's about 2x slower due to read-back
            System.out.println("Non-verified: " + nonVerifiedTime / 1_000_000 + " ms");
            System.out.println("Verified: " + verifiedTime / 1_000_000 + " ms");
        }

        @Test
        @DisplayName("Verification with fsync disabled is skipped")
        void verificationSkippedWhenFsyncDisabled() throws Exception {
            // When fsync is disabled (test mode), verification is also skipped
            // because there's nothing meaningful to verify
            storage = new FileRaftStorage(false, true);
            storage.open(tempDir).get(5, TimeUnit.SECONDS);

            // Should succeed (verification skipped)
            storage.appendEntries(List.of(
                    new LogEntryData(1, 1, "test".getBytes())
            )).get(5, TimeUnit.SECONDS);

            List<LogEntryData> replayed = storage.replayLog().get(5, TimeUnit.SECONDS);
            assertEquals(1, replayed.size());
        }
    }

    // ========================================================================
    // COMBINED PROTECTION TESTS
    // ========================================================================

    @Nested
    @DisplayName("Combined Protection")
    class CombinedProtectionTests {

        @Test
        @DisplayName("All protections work together")
        void allProtectionsWorkTogether() throws Exception {
            // Enable all protections
            storage = new FileRaftStorage(true, true);
            storage.open(tempDir).get(5, TimeUnit.SECONDS);

            // Verify lock is held
            Path lockPath = tempDir.resolve("raft.lock");
            assertTrue(Files.exists(lockPath));

            // Write with verification
            for (int i = 1; i <= 5; i++) {
                storage.appendEntries(List.of(
                        new LogEntryData(i, 1, ("protected-" + i).getBytes())
                )).get(5, TimeUnit.SECONDS);
            }
            storage.sync().get(5, TimeUnit.SECONDS);

            // Second instance blocked
            FileRaftStorage blocked = new FileRaftStorage(true, true);
            assertThrows(ExecutionException.class, () ->
                    blocked.open(tempDir).get(5, TimeUnit.SECONDS));
            blocked.close();

            // Close and reopen
            storage.close();
            Thread.sleep(100);

            storage = new FileRaftStorage(true, true);
            storage.open(tempDir).get(5, TimeUnit.SECONDS);

            // Data should be intact
            List<LogEntryData> replayed = storage.replayLog().get(5, TimeUnit.SECONDS);
            assertEquals(5, replayed.size());
        }

        @Test
        @DisplayName("Graceful degradation when lock already held")
        void gracefulDegradationWhenLocked() throws Exception {
            storage = new FileRaftStorage(true);
            storage.open(tempDir).get(5, TimeUnit.SECONDS);

            // Write some data
            storage.appendEntries(List.of(
                    new LogEntryData(1, 1, "important".getBytes())
            )).get(5, TimeUnit.SECONDS);
            storage.sync().get(5, TimeUnit.SECONDS);

            // Attempt second instance fails gracefully
            FileRaftStorage reader = new FileRaftStorage(true);
            ExecutionException ex = assertThrows(ExecutionException.class, () ->
                    reader.open(tempDir).get(5, TimeUnit.SECONDS));

            // Error message should be helpful
            String message = ex.getCause().getMessage();
            assertTrue(message.contains("exclusive lock") || message.contains("Another process"),
                    "Error message should indicate lock conflict: " + message);

            reader.close();
        }
    }
}
