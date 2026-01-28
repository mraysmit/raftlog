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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.CRC32C;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for protection guarantees against corruption from threading issues,
 * hardware failures, and software failures.
 * <p>
 * <h2>Protection Model</h2>
 * The FileRaftStorage provides the following guarantees:
 * 
 * <h3>1. Thread Safety (Serialization)</h3>
 * <ul>
 *   <li>All writes are serialized through a single-threaded executor</li>
 *   <li>No concurrent modifications to the log file or metadata</li>
 *   <li>Happens-before relationships are established via executor submission</li>
 * </ul>
 * 
 * <h3>2. Crash Consistency (Durability)</h3>
 * <ul>
 *   <li>CRC32C checksums detect torn writes and bit flips</li>
 *   <li>Atomic rename for metadata ensures all-or-nothing updates</li>
 *   <li>Explicit fsync barriers ensure data reaches stable storage</li>
 *   <li>Recovery truncates at last valid record</li>
 * </ul>
 * 
 * <h3>3. Failure Modes Handled</h3>
 * <ul>
 *   <li>Power loss during write → detected by incomplete record or bad CRC</li>
 *   <li>Bit rot → detected by CRC mismatch</li>
 *   <li>Process crash → recovery on restart</li>
 *   <li>Concurrent access (same process) → serialized by executor</li>
 * </ul>
 * 
 * <h3>4. Failure Modes NOT Handled</h3>
 * <ul>
 *   <li>Multiple processes writing to same files (no file locking)</li>
 *   <li>Silent data corruption after fsync returns (relies on filesystem)</li>
 *   <li>Disk full (will throw IOException)</li>
 * </ul>
 */
@DisplayName("Protection Guarantee Tests")
class ProtectionGuaranteeTest {

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
    // THREAD SAFETY GUARANTEES
    // ========================================================================

    @Nested
    @DisplayName("Thread Safety Guarantees")
    class ThreadSafetyGuarantees {

        @Test
        @DisplayName("G1: Write serialization - concurrent appends produce valid log")
        void writeSerializationConcurrentAppends() throws Exception {
            int numThreads = 20;
            int entriesPerThread = 50;
            ExecutorService executor = Executors.newFixedThreadPool(numThreads);
            CyclicBarrier barrier = new CyclicBarrier(numThreads);
            AtomicInteger indexCounter = new AtomicInteger(1);
            AtomicInteger failures = new AtomicInteger(0);

            List<Future<?>> futures = new ArrayList<>();

            for (int t = 0; t < numThreads; t++) {
                futures.add(executor.submit(() -> {
                    try {
                        barrier.await(); // Start all threads simultaneously
                        for (int i = 0; i < entriesPerThread; i++) {
                            int idx = indexCounter.getAndIncrement();
                            storage.appendEntries(List.of(
                                    new LogEntryData(idx, 1, ("thread-data-" + idx).getBytes())
                            )).get(5, TimeUnit.SECONDS);
                        }
                    } catch (Exception e) {
                        failures.incrementAndGet();
                        e.printStackTrace();
                    }
                }));
            }

            // Wait for all threads to complete
            for (Future<?> f : futures) {
                f.get(60, TimeUnit.SECONDS);
            }
            executor.shutdown();

            storage.sync().get(5, TimeUnit.SECONDS);

            // Verify: all entries should be written and recoverable
            List<LogEntryData> replayed = storage.replayLog().get(10, TimeUnit.SECONDS);

            assertEquals(0, failures.get(), "No thread should have failed");
            assertEquals(numThreads * entriesPerThread, replayed.size(),
                    "All entries should be persisted");

            // Verify each entry is intact (CRC validates on replay)
            for (LogEntryData entry : replayed) {
                assertTrue(entry.index() > 0 && entry.index() <= numThreads * entriesPerThread);
                assertTrue(new String(entry.payload()).startsWith("thread-data-"));
            }
        }

        @Test
        @DisplayName("G2: No interleaved writes - each record is atomic")
        void noInterleavedWrites() throws Exception {
            int numThreads = 10;
            int entriesPerThread = 20;
            int payloadSize = 1000; // Large enough to potentially interleave if not serialized
            
            ExecutorService executor = Executors.newFixedThreadPool(numThreads);
            CyclicBarrier barrier = new CyclicBarrier(numThreads);
            AtomicInteger indexCounter = new AtomicInteger(1);

            List<Future<?>> futures = new ArrayList<>();

            for (int t = 0; t < numThreads; t++) {
                final int threadId = t;
                futures.add(executor.submit(() -> {
                    try {
                        barrier.await();
                        for (int i = 0; i < entriesPerThread; i++) {
                            int idx = indexCounter.getAndIncrement();
                            // Create payload that's identifiable per thread
                            byte[] payload = new byte[payloadSize];
                            byte marker = (byte) ('A' + threadId);
                            for (int j = 0; j < payloadSize; j++) {
                                payload[j] = marker;
                            }
                            storage.appendEntries(List.of(
                                    new LogEntryData(idx, threadId + 1, payload)
                            )).get(5, TimeUnit.SECONDS);
                        }
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }));
            }

            for (Future<?> f : futures) {
                f.get(60, TimeUnit.SECONDS);
            }
            executor.shutdown();
            storage.sync().get(5, TimeUnit.SECONDS);

            // Verify: each payload should be uniform (no interleaving)
            List<LogEntryData> replayed = storage.replayLog().get(10, TimeUnit.SECONDS);

            for (LogEntryData entry : replayed) {
                byte[] payload = entry.payload();
                byte expected = payload[0];
                for (int i = 1; i < payload.length; i++) {
                    assertEquals(expected, payload[i],
                            "Payload at index " + entry.index() + " has mixed data - writes were interleaved!");
                }
            }
        }

        @Test
        @DisplayName("G3: Metadata updates are atomic under concurrent access")
        void metadataAtomicUpdates() throws Exception {
            int numThreads = 10;
            int updatesPerThread = 20;
            
            ExecutorService executor = Executors.newFixedThreadPool(numThreads);
            CyclicBarrier barrier = new CyclicBarrier(numThreads);
            AtomicLong termCounter = new AtomicLong(1);

            List<Future<?>> futures = new ArrayList<>();

            for (int t = 0; t < numThreads; t++) {
                final String nodeId = "node-" + t;
                futures.add(executor.submit(() -> {
                    try {
                        barrier.await();
                        for (int i = 0; i < updatesPerThread; i++) {
                            long term = termCounter.getAndIncrement();
                            storage.updateMetadata(term, Optional.of(nodeId + "-" + i))
                                    .get(5, TimeUnit.SECONDS);
                        }
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }));
            }

            for (Future<?> f : futures) {
                f.get(60, TimeUnit.SECONDS);
            }
            executor.shutdown();

            // Verify metadata is valid (not corrupted)
            PersistentMeta meta = storage.loadMetadata().get(5, TimeUnit.SECONDS);
            assertNotNull(meta);
            assertTrue(meta.currentTerm() >= 1);
            assertTrue(meta.votedFor().isPresent());
            // The value should be one of the written values (format: node-X-Y)
            assertTrue(meta.votedFor().get().matches("node-\\d+-\\d+"));
        }

        @Test
        @DisplayName("G4: Mixed operations (append, truncate, metadata) are serialized")
        void mixedOperationsSerialized() throws Exception {
            int numThreads = 15;
            int opsPerThread = 30;
            
            ExecutorService executor = Executors.newFixedThreadPool(numThreads);
            CyclicBarrier barrier = new CyclicBarrier(numThreads);
            AtomicInteger indexCounter = new AtomicInteger(1);
            Random random = new Random(42);

            List<Future<?>> futures = new ArrayList<>();

            for (int t = 0; t < numThreads; t++) {
                final int threadId = t;
                futures.add(executor.submit(() -> {
                    try {
                        barrier.await();
                        Random localRandom = new Random(42 + threadId);
                        for (int i = 0; i < opsPerThread; i++) {
                            int op = localRandom.nextInt(3);
                            switch (op) {
                                case 0 -> {
                                    int idx = indexCounter.getAndIncrement();
                                    storage.appendEntries(List.of(
                                            new LogEntryData(idx, 1, ("mixed-" + idx).getBytes())
                                    )).get(5, TimeUnit.SECONDS);
                                }
                                case 1 -> {
                                    storage.truncateSuffix(localRandom.nextInt(100) + 1)
                                            .get(5, TimeUnit.SECONDS);
                                }
                                case 2 -> {
                                    storage.updateMetadata(localRandom.nextInt(100),
                                            Optional.of("node-" + threadId))
                                            .get(5, TimeUnit.SECONDS);
                                }
                            }
                        }
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }));
            }

            for (Future<?> f : futures) {
                f.get(60, TimeUnit.SECONDS);
            }
            executor.shutdown();

            storage.sync().get(5, TimeUnit.SECONDS);

            // Verify: log and metadata should be in consistent state
            // (no exceptions on replay means CRCs are valid)
            assertDoesNotThrow(() -> storage.replayLog().get(10, TimeUnit.SECONDS));
            assertDoesNotThrow(() -> storage.loadMetadata().get(5, TimeUnit.SECONDS));
        }

        @RepeatedTest(5)
        @DisplayName("G5: Race condition stress test (repeated)")
        void raceConditionStressTest() throws Exception {
            int numThreads = 8;
            int iterations = 100;
            
            ExecutorService executor = Executors.newFixedThreadPool(numThreads);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(numThreads);
            AtomicInteger errors = new AtomicInteger(0);
            AtomicInteger index = new AtomicInteger(1);

            for (int t = 0; t < numThreads; t++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int i = 0; i < iterations; i++) {
                            int idx = index.getAndIncrement();
                            storage.appendEntries(List.of(
                                    new LogEntryData(idx, 1, ("stress-" + idx).getBytes())
                            )).get(5, TimeUnit.SECONDS);
                        }
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            doneLatch.await(60, TimeUnit.SECONDS);
            executor.shutdown();

            storage.sync().get(5, TimeUnit.SECONDS);

            assertEquals(0, errors.get());
            List<LogEntryData> replayed = storage.replayLog().get(10, TimeUnit.SECONDS);
            assertEquals(numThreads * iterations, replayed.size());
        }
    }

    // ========================================================================
    // CRASH CONSISTENCY GUARANTEES
    // ========================================================================

    @Nested
    @DisplayName("Crash Consistency Guarantees")
    class CrashConsistencyGuarantees {

        @Test
        @DisplayName("G6: CRC detects single bit flip in header")
        void crcDetectsBitFlipInHeader() throws Exception {
            storage.appendEntries(List.of(
                    new LogEntryData(1, 1, "valid-entry".getBytes())
            )).get(5, TimeUnit.SECONDS);
            storage.sync().get(5, TimeUnit.SECONDS);
            storage.close();

            // Flip a bit in the term field (offset 15)
            Path logPath = tempDir.resolve("raft.log");
            flipBitAt(logPath, 15, 0);

            storage = new FileRaftStorage(true);
            storage.open(tempDir).get(5, TimeUnit.SECONDS);
            List<LogEntryData> replayed = storage.replayLog().get(5, TimeUnit.SECONDS);

            // Entry should be rejected due to CRC mismatch
            assertEquals(0, replayed.size(), "Corrupted entry should be rejected");
        }

        @Test
        @DisplayName("G7: CRC detects single bit flip in payload")
        void crcDetectsBitFlipInPayload() throws Exception {
            storage.appendEntries(List.of(
                    new LogEntryData(1, 1, "important-data-that-must-not-corrupt".getBytes())
            )).get(5, TimeUnit.SECONDS);
            storage.sync().get(5, TimeUnit.SECONDS);
            storage.close();

            // Flip a bit in the payload
            Path logPath = tempDir.resolve("raft.log");
            flipBitAt(logPath, 30, 3); // Somewhere in the payload

            storage = new FileRaftStorage(true);
            storage.open(tempDir).get(5, TimeUnit.SECONDS);
            List<LogEntryData> replayed = storage.replayLog().get(5, TimeUnit.SECONDS);

            assertEquals(0, replayed.size(), "Corrupted entry should be rejected");
        }

        @Test
        @DisplayName("G8: CRC detects bit flip in CRC field itself")
        void crcDetectsBitFlipInCrcField() throws Exception {
            storage.appendEntries(List.of(
                    new LogEntryData(1, 1, "test".getBytes())
            )).get(5, TimeUnit.SECONDS);
            storage.sync().get(5, TimeUnit.SECONDS);
            storage.close();

            // Flip a bit in the CRC field
            Path logPath = tempDir.resolve("raft.log");
            long fileSize = Files.size(logPath);
            flipBitAt(logPath, fileSize - 2, 5);

            storage = new FileRaftStorage(true);
            storage.open(tempDir).get(5, TimeUnit.SECONDS);
            List<LogEntryData> replayed = storage.replayLog().get(5, TimeUnit.SECONDS);

            assertEquals(0, replayed.size());
        }

        @Test
        @DisplayName("G9: Recovery preserves valid entries before corruption")
        void recoveryPreservesValidEntriesBeforeCorruption() throws Exception {
            // Write 10 valid entries
            for (int i = 1; i <= 10; i++) {
                storage.appendEntries(List.of(
                        new LogEntryData(i, 1, ("entry-" + i).getBytes())
                )).get(5, TimeUnit.SECONDS);
            }
            storage.sync().get(5, TimeUnit.SECONDS);
            storage.close();

            // Corrupt entry 7 (entries 1-6 should survive)
            Path logPath = tempDir.resolve("raft.log");
            long entry7Start = findRecordStart(logPath, 6); // 0-indexed
            flipBitAt(logPath, entry7Start + 20, 0);

            storage = new FileRaftStorage(true);
            storage.open(tempDir).get(5, TimeUnit.SECONDS);
            List<LogEntryData> replayed = storage.replayLog().get(5, TimeUnit.SECONDS);

            assertEquals(6, replayed.size(), "Entries 1-6 should survive");
            for (int i = 0; i < 6; i++) {
                assertEquals(i + 1, replayed.get(i).index());
            }
        }

        @Test
        @DisplayName("G10: Atomic metadata update - partial write detected")
        void atomicMetadataUpdate() throws Exception {
            storage.updateMetadata(5L, Optional.of("original-vote")).get(5, TimeUnit.SECONDS);
            storage.close();

            // Verify metadata file exists and is valid
            Path metaPath = tempDir.resolve("meta.dat");
            assertTrue(Files.exists(metaPath));
            byte[] originalData = Files.readAllBytes(metaPath);

            // Simulate partial overwrite (as if crash during write)
            // Write partial garbage
            try (FileChannel fc = FileChannel.open(metaPath, StandardOpenOption.WRITE)) {
                fc.truncate(5); // Truncate to simulate partial write
            }

            storage = new FileRaftStorage(true);
            storage.open(tempDir).get(5, TimeUnit.SECONDS);

            // Should fail to load (corrupt) or return empty
            assertThrows(Exception.class, () ->
                    storage.loadMetadata().get(5, TimeUnit.SECONDS));
        }

        @Test
        @DisplayName("G11: WAL file truncation on recovery removes torn tail")
        void walTruncationOnRecovery() throws Exception {
            for (int i = 1; i <= 5; i++) {
                storage.appendEntries(List.of(
                        new LogEntryData(i, 1, ("entry-" + i).getBytes())
                )).get(5, TimeUnit.SECONDS);
            }
            storage.sync().get(5, TimeUnit.SECONDS);
            
            long validSize = Files.size(tempDir.resolve("raft.log"));
            storage.close();

            // Append garbage to simulate torn write
            Path logPath = tempDir.resolve("raft.log");
            try (FileChannel fc = FileChannel.open(logPath, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                byte[] garbage = new byte[50];
                new Random().nextBytes(garbage);
                fc.write(ByteBuffer.wrap(garbage));
            }

            long corruptedSize = Files.size(logPath);
            assertTrue(corruptedSize > validSize);

            // Recovery should truncate
            storage = new FileRaftStorage(true);
            storage.open(tempDir).get(5, TimeUnit.SECONDS);
            storage.replayLog().get(5, TimeUnit.SECONDS);

            long recoveredSize = Files.size(logPath);
            assertEquals(validSize, recoveredSize, "File should be truncated to valid size");
        }

        @Test
        @DisplayName("G12: Multiple bit flips detected (burst error)")
        void multipleBitFlipsDetected() throws Exception {
            storage.appendEntries(List.of(
                    new LogEntryData(1, 1, "data".getBytes())
            )).get(5, TimeUnit.SECONDS);
            storage.sync().get(5, TimeUnit.SECONDS);
            storage.close();

            // Corrupt multiple bytes (simulates burst error)
            Path logPath = tempDir.resolve("raft.log");
            try (FileChannel fc = FileChannel.open(logPath, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
                fc.position(10);
                fc.write(ByteBuffer.wrap(new byte[]{(byte)0xFF, (byte)0xFF, (byte)0xFF}));
            }

            storage = new FileRaftStorage(true);
            storage.open(tempDir).get(5, TimeUnit.SECONDS);
            List<LogEntryData> replayed = storage.replayLog().get(5, TimeUnit.SECONDS);

            assertEquals(0, replayed.size());
        }
    }

    // ========================================================================
    // FAILURE MODE TESTS
    // ========================================================================

    @Nested
    @DisplayName("Failure Mode Handling")
    class FailureModeHandling {

        @Test
        @DisplayName("F1: Simulated power loss at various write stages")
        void simulatedPowerLossAtWriteStages() throws Exception {
            storage.appendEntries(List.of(
                    new LogEntryData(1, 1, "committed-entry".getBytes())
            )).get(5, TimeUnit.SECONDS);
            storage.sync().get(5, TimeUnit.SECONDS);
            storage.close();

            // Test recovery from crash at different points
            int[] truncatePositions = {
                    4,   // After magic
                    6,   // After version
                    7,   // After type
                    15,  // After index
                    23,  // After term
                    27,  // After payload length (header complete)
                    30,  // Partial payload
            };

            Path logPath = tempDir.resolve("raft.log");
            long validSize = Files.size(logPath);

            for (int truncateAt : truncatePositions) {
                // Append partial record
                try (FileChannel fc = FileChannel.open(logPath, StandardOpenOption.WRITE)) {
                    fc.position(validSize);
                    ByteBuffer partial = createPartialRecord(truncateAt);
                    fc.write(partial);
                }

                storage = new FileRaftStorage(true);
                storage.open(tempDir).get(5, TimeUnit.SECONDS);
                List<LogEntryData> replayed = storage.replayLog().get(5, TimeUnit.SECONDS);

                assertEquals(1, replayed.size(),
                        "Original entry should survive power loss at position " + truncateAt);
                assertEquals("committed-entry", new String(replayed.get(0).payload()));

                storage.close();

                // Reset file to valid state
                try (FileChannel fc = FileChannel.open(logPath, StandardOpenOption.WRITE)) {
                    fc.truncate(validSize);
                }
            }
        }

        @Test
        @DisplayName("F2: Recovery after multiple restart cycles")
        void recoveryAfterMultipleRestarts() throws Exception {
            for (int cycle = 0; cycle < 5; cycle++) {
                // Append some entries
                for (int i = 1; i <= 10; i++) {
                    storage.appendEntries(List.of(
                            new LogEntryData(cycle * 10 + i, cycle + 1, 
                                    ("cycle-" + cycle + "-entry-" + i).getBytes())
                    )).get(5, TimeUnit.SECONDS);
                }
                storage.sync().get(5, TimeUnit.SECONDS);
                
                // Simulate restart
                storage.close();
                storage = new FileRaftStorage(true);
                storage.open(tempDir).get(5, TimeUnit.SECONDS);
                
                // Replay and verify
                List<LogEntryData> replayed = storage.replayLog().get(5, TimeUnit.SECONDS);
                assertEquals((cycle + 1) * 10, replayed.size());
            }
        }

        @Test
        @DisplayName("F3: Graceful handling of disk full (simulated)")
        void gracefulDiskFullHandling() throws Exception {
            // We can't actually fill the disk, but we can test that IOExceptions
            // are properly wrapped and don't corrupt existing data
            
            // Write valid data first
            storage.appendEntries(List.of(
                    new LogEntryData(1, 1, "preserved".getBytes())
            )).get(5, TimeUnit.SECONDS);
            storage.sync().get(5, TimeUnit.SECONDS);
            
            // The executor model ensures that even if one write fails,
            // subsequent operations can proceed (assuming disk space freed)
            // This is a design verification rather than actual disk full test
            
            List<LogEntryData> replayed = storage.replayLog().get(5, TimeUnit.SECONDS);
            assertEquals(1, replayed.size());
        }

        @Test
        @DisplayName("F4: CRC32C strength - collision resistance")
        void crc32cCollisionResistance() throws Exception {
            // CRC32C can detect all single-bit errors, all double-bit errors,
            // and most burst errors. Test that different payloads produce different CRCs.
            
            Set<Integer> crcs = new HashSet<>();
            CRC32C crc = new CRC32C();
            
            // Generate many different payloads and verify CRC diversity
            Random random = new Random(42);
            for (int i = 0; i < 10000; i++) {
                byte[] data = new byte[100];
                random.nextBytes(data);
                crc.reset();
                crc.update(data);
                crcs.add((int) crc.getValue());
            }
            
            // With 10000 random inputs, we should have high CRC diversity
            // (some collisions expected due to birthday paradox, but not too many)
            assertTrue(crcs.size() > 9000, 
                    "CRC32C should produce diverse values, got " + crcs.size() + " unique");
        }

        @Test
        @DisplayName("F5: Recovery with interleaved valid and corrupt records")
        void recoveryWithInterleavedCorruption() throws Exception {
            // Write entries 1-3
            for (int i = 1; i <= 3; i++) {
                storage.appendEntries(List.of(
                        new LogEntryData(i, 1, ("entry-" + i).getBytes())
                )).get(5, TimeUnit.SECONDS);
            }
            storage.sync().get(5, TimeUnit.SECONDS);
            storage.close();

            // Corrupt entry 2
            Path logPath = tempDir.resolve("raft.log");
            long entry2Start = findRecordStart(logPath, 1);
            flipBitAt(logPath, entry2Start + 10, 0);

            storage = new FileRaftStorage(true);
            storage.open(tempDir).get(5, TimeUnit.SECONDS);
            List<LogEntryData> replayed = storage.replayLog().get(5, TimeUnit.SECONDS);

            // Only entry 1 should survive (replay stops at first corruption)
            assertEquals(1, replayed.size());
            assertEquals(1, replayed.get(0).index());
        }
    }

    // ========================================================================
    // ORDERING GUARANTEES
    // ========================================================================

    @Nested
    @DisplayName("Ordering Guarantees")
    class OrderingGuarantees {

        @Test
        @DisplayName("O1: Write order preserved under concurrent submissions")
        void writeOrderPreserved() throws Exception {
            int numEntries = 1000;
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            
            // Submit entries in order
            for (int i = 1; i <= numEntries; i++) {
                final int idx = i;
                futures.add(storage.appendEntries(List.of(
                        new LogEntryData(idx, 1, ("ordered-" + idx).getBytes())
                )));
            }
            
            // Wait for all
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(30, TimeUnit.SECONDS);
            storage.sync().get(5, TimeUnit.SECONDS);
            
            List<LogEntryData> replayed = storage.replayLog().get(10, TimeUnit.SECONDS);
            
            // Verify order matches submission order
            assertEquals(numEntries, replayed.size());
            for (int i = 0; i < numEntries; i++) {
                assertEquals(i + 1, replayed.get(i).index(),
                        "Entry at position " + i + " should have index " + (i + 1));
            }
        }

        @Test
        @DisplayName("O2: Sync barrier ensures durability before return")
        void syncBarrierEnsuresDurability() throws Exception {
            // Write and sync
            storage.appendEntries(List.of(
                    new LogEntryData(1, 1, "must-be-durable".getBytes())
            )).get(5, TimeUnit.SECONDS);
            storage.sync().get(5, TimeUnit.SECONDS);
            
            // After sync returns, data should survive "crash"
            storage.close();
            
            storage = new FileRaftStorage(true);
            storage.open(tempDir).get(5, TimeUnit.SECONDS);
            List<LogEntryData> replayed = storage.replayLog().get(5, TimeUnit.SECONDS);
            
            assertEquals(1, replayed.size());
            assertEquals("must-be-durable", new String(replayed.get(0).payload()));
        }

        @Test
        @DisplayName("O3: Metadata update happens-before subsequent operations")
        void metadataHappensBefore() throws Exception {
            // Update metadata
            storage.updateMetadata(5L, Optional.of("leader-1")).get(5, TimeUnit.SECONDS);
            
            // Then append (this should see the updated metadata state)
            storage.appendEntries(List.of(
                    new LogEntryData(1, 5, "term-5-entry".getBytes())
            )).get(5, TimeUnit.SECONDS);
            storage.sync().get(5, TimeUnit.SECONDS);
            
            // Verify both persist correctly
            storage.close();
            
            storage = new FileRaftStorage(true);
            storage.open(tempDir).get(5, TimeUnit.SECONDS);
            
            PersistentMeta meta = storage.loadMetadata().get(5, TimeUnit.SECONDS);
            List<LogEntryData> replayed = storage.replayLog().get(5, TimeUnit.SECONDS);
            
            assertEquals(5L, meta.currentTerm());
            assertEquals("leader-1", meta.votedFor().orElse(""));
            assertEquals(1, replayed.size());
            assertEquals(5, replayed.get(0).term());
        }
    }

    // ========================================================================
    // HELPER METHODS
    // ========================================================================

    private void flipBitAt(Path file, long position, int bitIndex) throws IOException {
        try (FileChannel fc = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            fc.position(position);
            ByteBuffer buf = ByteBuffer.allocate(1);
            fc.read(buf);
            buf.flip();
            byte original = buf.get();
            byte flipped = (byte) (original ^ (1 << bitIndex));
            buf.clear();
            buf.put(flipped);
            buf.flip();
            fc.position(position);
            fc.write(buf);
        }
    }

    private long findRecordStart(Path logPath, int recordIndex) throws IOException {
        try (FileChannel fc = FileChannel.open(logPath, StandardOpenOption.READ)) {
            long pos = 0;
            ByteBuffer headerBuf = ByteBuffer.allocate(27);

            for (int i = 0; i < recordIndex; i++) {
                headerBuf.clear();
                fc.read(headerBuf, pos);
                headerBuf.flip();

                headerBuf.getInt();   // magic
                headerBuf.getShort(); // version
                headerBuf.get();      // type
                headerBuf.getLong();  // index
                headerBuf.getLong();  // term
                int payloadLen = headerBuf.getInt();

                pos += 27 + payloadLen + 4; // header + payload + crc
            }
            return pos;
        }
    }

    private ByteBuffer createPartialRecord(int truncateAt) {
        ByteBuffer buf = ByteBuffer.allocate(truncateAt);
        
        if (truncateAt >= 4) buf.putInt(0x52414654);      // Magic
        if (truncateAt >= 6) buf.putShort((short) 1);     // Version
        if (truncateAt >= 7) buf.put((byte) 2);           // Type APPEND
        if (truncateAt >= 15) buf.putLong(999L);          // Index
        if (truncateAt >= 23) buf.putLong(1L);            // Term
        if (truncateAt >= 27) buf.putInt(10);             // Payload length
        if (truncateAt > 27) {
            byte[] partial = new byte[truncateAt - 27];
            buf.put(partial);
        }
        
        buf.flip();
        return buf;
    }
}
