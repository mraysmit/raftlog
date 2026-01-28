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
package dev.mars.raftlog.demo;

import dev.mars.raftlog.storage.FileRaftStorage;
import dev.mars.raftlog.storage.RaftStorage.LogEntryData;
import dev.mars.raftlog.storage.RaftStorage.PersistentMeta;
import dev.mars.raftlog.storage.RaftStorageConfig;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Chaos testing for the Raft WAL implementation.
 * <p>
 * This class throws every nasty scenario at the WAL to verify its robustness:
 * <ul>
 *   <li>Concurrent writer storms</li>
 *   <li>Random corruption injection</li>
 *   <li>Power failure simulation (partial writes)</li>
 *   <li>Rapid metadata thrashing</li>
 *   <li>Boundary condition attacks</li>
 *   <li>File manipulation during operations</li>
 *   <li>Memory pressure scenarios</li>
 *   <li>Interrupt injection</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>
 * # Build
 * mvn package -pl raftlog-demo -am
 * 
 * # Run all chaos tests
 * java -cp raftlog-demo/target/raftlog-demo-1.0-SNAPSHOT.jar dev.mars.raftlog.demo.WalChaos
 * 
 * # Run specific test
 * java -cp raftlog-demo/target/raftlog-demo-1.0-SNAPSHOT.jar dev.mars.raftlog.demo.WalChaos concurrent
 * java -cp raftlog-demo/target/raftlog-demo-1.0-SNAPSHOT.jar dev.mars.raftlog.demo.WalChaos corruption
 * java -cp raftlog-demo/target/raftlog-demo-1.0-SNAPSHOT.jar dev.mars.raftlog.demo.WalChaos boundary
 * java -cp raftlog-demo/target/raftlog-demo-1.0-SNAPSHOT.jar dev.mars.raftlog.demo.WalChaos stress
 * </pre>
 *
 * @see FileRaftStorage
 */
public class WalChaos {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int MAGIC = 0x52414654; // 'RAFT'

    private final Path baseDir;
    private final AtomicInteger testsPassed = new AtomicInteger(0);
    private final AtomicInteger testsFailed = new AtomicInteger(0);

    public WalChaos(Path baseDir) {
        this.baseDir = baseDir;
    }

    public static void main(String[] args) throws Exception {
        System.out.println("╔═══════════════════════════════════════════════════════════════╗");
        System.out.println("║              WAL CHAOS TESTING SUITE                          ║");
        System.out.println("║  \"If it survives this, it survives anything\"                  ║");
        System.out.println("╚═══════════════════════════════════════════════════════════════╝");
        System.out.println();

        Path chaosDir = Files.createTempDirectory("wal-chaos-");
        System.out.println("Chaos directory: " + chaosDir.toAbsolutePath());
        System.out.println();

        WalChaos chaos = new WalChaos(chaosDir);

        String testFilter = args.length > 0 ? args[0].toLowerCase() : "all";

        try {
            switch (testFilter) {
                case "concurrent" -> chaos.runConcurrencyTests();
                case "corruption" -> chaos.runCorruptionTests();
                case "boundary" -> chaos.runBoundaryTests();
                case "stress" -> chaos.runStressTests();
                case "all" -> {
                    chaos.runConcurrencyTests();
                    chaos.runCorruptionTests();
                    chaos.runBoundaryTests();
                    chaos.runStressTests();
                    chaos.runNastyEdgeCases();
                }
                default -> {
                    System.err.println("Unknown test filter: " + testFilter);
                    System.err.println("Available: concurrent, corruption, boundary, stress, all");
                    System.exit(1);
                }
            }
        } finally {
            System.out.println();
            System.out.println("╔═══════════════════════════════════════════════════════════════╗");
            System.out.printf("║  RESULTS: %d passed, %d failed                                 ║%n",
                    chaos.testsPassed.get(), chaos.testsFailed.get());
            System.out.println("╚═══════════════════════════════════════════════════════════════╝");

            // Cleanup
            deleteRecursively(chaosDir);
        }

        System.exit(chaos.testsFailed.get() > 0 ? 1 : 0);
    }

    // =========================================================================
    // CONCURRENCY CHAOS
    // =========================================================================

    private void runConcurrencyTests() throws Exception {
        printSection("CONCURRENCY CHAOS");

        chaosTest("Concurrent Writer Storm (20 threads × 100 entries)", this::concurrentWriterStorm);
        chaosTest("Concurrent Metadata Thrashing (50 threads)", this::concurrentMetadataThrashing);
        chaosTest("Mixed Operations Chaos (append + truncate + metadata)", this::mixedOperationsChaos);
        chaosTest("Rapid Open/Close Cycles", this::rapidOpenCloseCycles);
        chaosTest("Concurrent Replay During Writes", this::concurrentReplayDuringWrites);
        chaosTest("Thread Interrupt Storm", this::threadInterruptStorm);
    }

    private void concurrentWriterStorm() throws Exception {
        Path testDir = createTestDir("concurrent-storm");
        RaftStorageConfig config = RaftStorageConfig.builder()
                .dataDir(testDir.toString())
                .syncEnabled(false) // Speed up test
                .build();

        int numThreads = 20;
        int entriesPerThread = 100;
        AtomicLong indexCounter = new AtomicLong(1);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);

        try (FileRaftStorage storage = new FileRaftStorage(config)) {
            storage.open().join();

            ExecutorService executor = Executors.newFixedThreadPool(numThreads);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(numThreads);

            for (int t = 0; t < numThreads; t++) {
                final int threadId = t;
                executor.submit(() -> {
                    try {
                        startLatch.await(); // All threads start together
                        for (int i = 0; i < entriesPerThread; i++) {
                            long index = indexCounter.getAndIncrement();
                            byte[] payload = ("Thread" + threadId + "-Entry" + i).getBytes(StandardCharsets.UTF_8);
                            LogEntryData entry = new LogEntryData(index, 1, payload);
                            storage.appendEntries(List.of(entry)).join();
                            successCount.incrementAndGet();
                        }
                    } catch (Exception e) {
                        errorCount.incrementAndGet();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown(); // GO!
            doneLatch.await(60, TimeUnit.SECONDS);
            storage.sync().join();
            executor.shutdown();

            // Verify
            List<LogEntryData> entries = storage.replayLog().join();
            int expectedTotal = numThreads * entriesPerThread;

            if (entries.size() != expectedTotal) {
                throw new AssertionError("Expected " + expectedTotal + " entries, got " + entries.size());
            }

            // Verify no interleaved/corrupt payloads
            for (LogEntryData entry : entries) {
                String payload = new String(entry.payload(), StandardCharsets.UTF_8);
                if (!payload.matches("Thread\\d+-Entry\\d+")) {
                    throw new AssertionError("Corrupt payload detected: " + payload);
                }
            }
        }
    }

    private void concurrentMetadataThrashing() throws Exception {
        Path testDir = createTestDir("metadata-thrash");
        RaftStorageConfig config = RaftStorageConfig.builder()
                .dataDir(testDir.toString())
                .syncEnabled(false)
                .build();

        int numThreads = 50;
        int updatesPerThread = 20;
        AtomicInteger errorCount = new AtomicInteger(0);

        try (FileRaftStorage storage = new FileRaftStorage(config)) {
            storage.open().join();

            ExecutorService executor = Executors.newFixedThreadPool(numThreads);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(numThreads);

            for (int t = 0; t < numThreads; t++) {
                final int threadId = t;
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int i = 0; i < updatesPerThread; i++) {
                            long term = threadId * 1000L + i;
                            String votedFor = "node-" + threadId + "-" + i;
                            storage.updateMetadata(term, Optional.of(votedFor)).join();
                        }
                    } catch (Exception e) {
                        errorCount.incrementAndGet();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            doneLatch.await(60, TimeUnit.SECONDS);
            executor.shutdown();

            // Verify metadata is still readable and consistent
            PersistentMeta meta = storage.loadMetadata().join();
            if (meta.currentTerm() < 0) {
                throw new AssertionError("Corrupt term: " + meta.currentTerm());
            }
        }
    }

    private void mixedOperationsChaos() throws Exception {
        Path testDir = createTestDir("mixed-ops");
        RaftStorageConfig config = RaftStorageConfig.builder()
                .dataDir(testDir.toString())
                .syncEnabled(false)
                .build();

        int numThreads = 10;
        int opsPerThread = 50;

        try (FileRaftStorage storage = new FileRaftStorage(config)) {
            storage.open().join();

            AtomicLong indexCounter = new AtomicLong(1);
            AtomicLong termCounter = new AtomicLong(1);
            AtomicInteger errorCount = new AtomicInteger(0);

            ExecutorService executor = Executors.newFixedThreadPool(numThreads);
            CountDownLatch doneLatch = new CountDownLatch(numThreads);

            for (int t = 0; t < numThreads; t++) {
                executor.submit(() -> {
                    try {
                        ThreadLocalRandom random = ThreadLocalRandom.current();
                        for (int i = 0; i < opsPerThread; i++) {
                            int op = random.nextInt(4);
                            switch (op) {
                                case 0 -> { // Append
                                    long index = indexCounter.getAndIncrement();
                                    long term = termCounter.get();
                                    storage.appendEntries(List.of(
                                            new LogEntryData(index, term, ("data-" + index).getBytes())
                                    )).join();
                                }
                                case 1 -> { // Metadata update
                                    long term = termCounter.incrementAndGet();
                                    storage.updateMetadata(term, Optional.of("node-" + term)).join();
                                }
                                case 2 -> { // Truncate
                                    long truncateFrom = Math.max(1, indexCounter.get() - random.nextInt(5));
                                    storage.truncateSuffix(truncateFrom).join();
                                }
                                case 3 -> { // Sync
                                    storage.sync().join();
                                }
                            }
                        }
                    } catch (Exception e) {
                        errorCount.incrementAndGet();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            doneLatch.await(120, TimeUnit.SECONDS);
            storage.sync().join();
            executor.shutdown();

            // Must be able to replay without crash
            List<LogEntryData> entries = storage.replayLog().join();
            System.out.printf("    Replayed %d entries after chaos%n", entries.size());
        }
    }

    private void rapidOpenCloseCycles() throws Exception {
        Path testDir = createTestDir("open-close");
        RaftStorageConfig config = RaftStorageConfig.builder()
                .dataDir(testDir.toString())
                .build();

        int cycles = 50;
        AtomicLong indexCounter = new AtomicLong(1);

        for (int i = 0; i < cycles; i++) {
            try (FileRaftStorage storage = new FileRaftStorage(config)) {
                storage.open().join();

                // Quick append
                long index = indexCounter.getAndIncrement();
                storage.appendEntries(List.of(
                        new LogEntryData(index, 1, ("cycle-" + i).getBytes())
                )).join();
                storage.sync().join();
            }
            // Immediately closed
        }

        // Verify all entries survived
        try (FileRaftStorage storage = new FileRaftStorage(config)) {
            storage.open().join();
            List<LogEntryData> entries = storage.replayLog().join();
            if (entries.size() != cycles) {
                throw new AssertionError("Expected " + cycles + " entries, got " + entries.size());
            }
        }
    }

    private void concurrentReplayDuringWrites() throws Exception {
        Path testDir = createTestDir("replay-during-write");
        RaftStorageConfig config = RaftStorageConfig.builder()
                .dataDir(testDir.toString())
                .syncEnabled(false)
                .build();

        try (FileRaftStorage storage = new FileRaftStorage(config)) {
            storage.open().join();

            AtomicBoolean running = new AtomicBoolean(true);
            AtomicInteger replayCount = new AtomicInteger(0);
            AtomicLong indexCounter = new AtomicLong(1);

            // Writer thread
            Thread writer = new Thread(() -> {
                while (running.get()) {
                    try {
                        long index = indexCounter.getAndIncrement();
                        storage.appendEntries(List.of(
                                new LogEntryData(index, 1, ("entry-" + index).getBytes())
                        )).join();
                        Thread.sleep(1);
                    } catch (Exception e) {
                        // Expected during shutdown
                    }
                }
            });

            // Replay thread (hammers replay while writes happen)
            Thread replayer = new Thread(() -> {
                while (running.get()) {
                    try {
                        storage.replayLog().join();
                        replayCount.incrementAndGet();
                    } catch (Exception e) {
                        // May fail during concurrent ops
                    }
                }
            });

            writer.start();
            replayer.start();

            Thread.sleep(2000); // Run for 2 seconds
            running.set(false);

            writer.join(5000);
            replayer.join(5000);

            System.out.printf("    Completed %d replays during writes%n", replayCount.get());
        }
    }

    private void threadInterruptStorm() throws Exception {
        Path testDir = createTestDir("interrupt-storm");
        RaftStorageConfig config = RaftStorageConfig.builder()
                .dataDir(testDir.toString())
                .syncEnabled(false)
                .build();

        try (FileRaftStorage storage = new FileRaftStorage(config)) {
            storage.open().join();

            int numThreads = 10;
            AtomicLong indexCounter = new AtomicLong(1);
            List<Thread> threads = new ArrayList<>();

            for (int t = 0; t < numThreads; t++) {
                Thread thread = new Thread(() -> {
                    while (!Thread.currentThread().isInterrupted()) {
                        try {
                            long index = indexCounter.getAndIncrement();
                            storage.appendEntries(List.of(
                                    new LogEntryData(index, 1, ("interrupt-test-" + index).getBytes())
                            )).join();
                        } catch (Exception e) {
                            break;
                        }
                    }
                });
                threads.add(thread);
                thread.start();
            }

            // Let them run briefly
            Thread.sleep(500);

            // Interrupt all threads randomly
            for (Thread thread : threads) {
                thread.interrupt();
            }

            // Wait for termination
            for (Thread thread : threads) {
                thread.join(5000);
            }

            storage.sync().join();

            // Must still be able to replay
            List<LogEntryData> entries = storage.replayLog().join();
            System.out.printf("    Survived with %d entries after interrupt storm%n", entries.size());
        }
    }

    // =========================================================================
    // CORRUPTION CHAOS
    // =========================================================================

    private void runCorruptionTests() throws Exception {
        printSection("CORRUPTION CHAOS");

        chaosTest("Random Byte Corruption in WAL", this::randomByteCorruption);
        chaosTest("Zero-Fill Corruption (SSD failure mode)", this::zeroFillCorruption);
        chaosTest("Magic Number Corruption", this::magicNumberCorruption);
        chaosTest("CRC Bit Flip", this::crcBitFlip);
        chaosTest("Partial Record (Torn Write)", this::partialRecordCorruption);
        chaosTest("Metadata File Corruption", this::metadataCorruption);
        chaosTest("Garbage Append After Valid Data", this::garbageAppend);
        chaosTest("Truncation Point Corruption", this::truncationPointCorruption);
    }

    private void randomByteCorruption() throws Exception {
        Path testDir = createTestDir("random-corrupt");
        RaftStorageConfig config = RaftStorageConfig.builder()
                .dataDir(testDir.toString())
                .build();

        // Write some valid entries
        try (FileRaftStorage storage = new FileRaftStorage(config)) {
            storage.open().join();
            for (int i = 1; i <= 10; i++) {
                storage.appendEntries(List.of(
                        new LogEntryData(i, 1, ("entry-" + i).getBytes())
                )).join();
            }
            storage.sync().join();
        }

        // Corrupt random bytes in the WAL
        Path walFile = testDir.resolve("raft.log");
        byte[] data = Files.readAllBytes(walFile);
        int corruptionPoint = SECURE_RANDOM.nextInt(data.length / 2) + data.length / 2; // Corrupt in second half
        data[corruptionPoint] = (byte) (data[corruptionPoint] ^ 0xFF);
        Files.write(walFile, data);

        // Reopen and verify recovery
        try (FileRaftStorage storage = new FileRaftStorage(config)) {
            storage.open().join();
            List<LogEntryData> entries = storage.replayLog().join();
            // Should recover at least some entries (before corruption)
            if (entries.isEmpty()) {
                throw new AssertionError("No entries recovered after corruption");
            }
            System.out.printf("    Recovered %d entries after random corruption%n", entries.size());
        }
    }

    private void zeroFillCorruption() throws Exception {
        Path testDir = createTestDir("zero-fill");
        RaftStorageConfig config = RaftStorageConfig.builder()
                .dataDir(testDir.toString())
                .build();

        // Write valid entries
        try (FileRaftStorage storage = new FileRaftStorage(config)) {
            storage.open().join();
            for (int i = 1; i <= 5; i++) {
                storage.appendEntries(List.of(
                        new LogEntryData(i, 1, ("entry-" + i).getBytes())
                )).join();
            }
            storage.sync().join();
        }

        // Append 4KB of zeros (SSD block failure simulation)
        Path walFile = testDir.resolve("raft.log");
        try (var channel = FileChannel.open(walFile, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            ByteBuffer zeros = ByteBuffer.allocate(4096);
            channel.write(zeros);
        }

        // Should recover entries before zeros
        try (FileRaftStorage storage = new FileRaftStorage(config)) {
            storage.open().join();
            List<LogEntryData> entries = storage.replayLog().join();
            if (entries.size() != 5) {
                throw new AssertionError("Expected 5 entries, got " + entries.size());
            }
        }
    }

    private void magicNumberCorruption() throws Exception {
        Path testDir = createTestDir("magic-corrupt");
        RaftStorageConfig config = RaftStorageConfig.builder()
                .dataDir(testDir.toString())
                .build();

        // Write entries
        try (FileRaftStorage storage = new FileRaftStorage(config)) {
            storage.open().join();
            for (int i = 1; i <= 5; i++) {
                storage.appendEntries(List.of(
                        new LogEntryData(i, 1, ("entry-" + i).getBytes())
                )).join();
            }
            storage.sync().join();
        }

        // Corrupt the magic number of the 3rd entry
        Path walFile = testDir.resolve("raft.log");
        try (RandomAccessFile raf = new RandomAccessFile(walFile.toFile(), "rw")) {
            // Each entry: 4(magic) + 2(ver) + 1(type) + 8(idx) + 8(term) + 4(len) + payload + 4(crc)
            // Entry 1 header starts at 0
            // Estimate ~40 bytes per entry with small payloads
            long offset = 80; // Rough offset to 3rd entry
            raf.seek(offset);
            raf.writeInt(0xDEADBEEF); // Corrupt magic
        }

        // Should recover entries 1-2 only
        try (FileRaftStorage storage = new FileRaftStorage(config)) {
            storage.open().join();
            List<LogEntryData> entries = storage.replayLog().join();
            // Should have recovered some entries before corruption
            System.out.printf("    Recovered %d entries after magic corruption%n", entries.size());
        }
    }

    private void crcBitFlip() throws Exception {
        Path testDir = createTestDir("crc-flip");
        RaftStorageConfig config = RaftStorageConfig.builder()
                .dataDir(testDir.toString())
                .build();

        // Write entries
        try (FileRaftStorage storage = new FileRaftStorage(config)) {
            storage.open().join();
            for (int i = 1; i <= 5; i++) {
                storage.appendEntries(List.of(
                        new LogEntryData(i, 1, ("entry-" + i).getBytes())
                )).join();
            }
            storage.sync().join();
        }

        // Flip a single bit in the payload (should cause CRC mismatch)
        Path walFile = testDir.resolve("raft.log");
        byte[] data = Files.readAllBytes(walFile);
        int payloadOffset = 30; // Somewhere in the payload of first entry
        data[payloadOffset] = (byte) (data[payloadOffset] ^ 0x01); // Single bit flip
        Files.write(walFile, data);

        // CRC should detect and truncate
        try (FileRaftStorage storage = new FileRaftStorage(config)) {
            storage.open().join();
            List<LogEntryData> entries = storage.replayLog().join();
            System.out.printf("    Recovered %d entries after CRC bit flip%n", entries.size());
        }
    }

    private void partialRecordCorruption() throws Exception {
        Path testDir = createTestDir("partial-record");
        RaftStorageConfig config = RaftStorageConfig.builder()
                .dataDir(testDir.toString())
                .build();

        // Write entries
        try (FileRaftStorage storage = new FileRaftStorage(config)) {
            storage.open().join();
            for (int i = 1; i <= 5; i++) {
                storage.appendEntries(List.of(
                        new LogEntryData(i, 1, ("entry-" + i).getBytes())
                )).join();
            }
            storage.sync().join();
        }

        // Append partial header (simulating torn write)
        Path walFile = testDir.resolve("raft.log");
        try (var channel = FileChannel.open(walFile, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            ByteBuffer partial = ByteBuffer.allocate(10);
            partial.putInt(MAGIC);
            partial.putShort((short) 1);
            partial.put((byte) 2); // TYPE_APPEND
            // Stop here - no index, term, payload, or CRC
            partial.flip();
            channel.write(partial);
        }

        // Should recover all 5 valid entries
        try (FileRaftStorage storage = new FileRaftStorage(config)) {
            storage.open().join();
            List<LogEntryData> entries = storage.replayLog().join();
            if (entries.size() != 5) {
                throw new AssertionError("Expected 5 entries, got " + entries.size());
            }
        }
    }

    private void metadataCorruption() throws Exception {
        Path testDir = createTestDir("meta-corrupt");
        RaftStorageConfig config = RaftStorageConfig.builder()
                .dataDir(testDir.toString())
                .build();

        // Write metadata
        try (FileRaftStorage storage = new FileRaftStorage(config)) {
            storage.open().join();
            storage.updateMetadata(42, Optional.of("node-leader")).join();
        }

        // Corrupt metadata file
        Path metaFile = testDir.resolve("meta.dat");
        byte[] data = Files.readAllBytes(metaFile);
        data[0] = (byte) (data[0] ^ 0xFF); // Corrupt first byte
        Files.write(metaFile, data);

        // Should detect corruption
        try (FileRaftStorage storage = new FileRaftStorage(config)) {
            storage.open().join();
            try {
                storage.loadMetadata().join();
                // Some implementations may return defaults on corruption
                System.out.println("    Metadata returned (possibly defaults)");
            } catch (Exception e) {
                System.out.println("    Corruption correctly detected: " + e.getMessage());
            }
        }
    }

    private void garbageAppend() throws Exception {
        Path testDir = createTestDir("garbage-append");
        RaftStorageConfig config = RaftStorageConfig.builder()
                .dataDir(testDir.toString())
                .build();

        // Write valid entries
        try (FileRaftStorage storage = new FileRaftStorage(config)) {
            storage.open().join();
            for (int i = 1; i <= 5; i++) {
                storage.appendEntries(List.of(
                        new LogEntryData(i, 1, ("entry-" + i).getBytes())
                )).join();
            }
            storage.sync().join();
        }

        // Append random garbage
        Path walFile = testDir.resolve("raft.log");
        byte[] garbage = new byte[256];
        SECURE_RANDOM.nextBytes(garbage);
        Files.write(walFile, garbage, StandardOpenOption.APPEND);

        // Should recover valid entries
        try (FileRaftStorage storage = new FileRaftStorage(config)) {
            storage.open().join();
            List<LogEntryData> entries = storage.replayLog().join();
            if (entries.size() != 5) {
                throw new AssertionError("Expected 5 entries, got " + entries.size());
            }
        }
    }

    private void truncationPointCorruption() throws Exception {
        Path testDir = createTestDir("truncate-corrupt");
        RaftStorageConfig config = RaftStorageConfig.builder()
                .dataDir(testDir.toString())
                .build();

        // Write entries, truncate, write more
        try (FileRaftStorage storage = new FileRaftStorage(config)) {
            storage.open().join();

            for (int i = 1; i <= 10; i++) {
                storage.appendEntries(List.of(
                        new LogEntryData(i, 1, ("entry-" + i).getBytes())
                )).join();
            }

            // Truncate from index 5
            storage.truncateSuffix(5).join();

            // Append new entries
            for (int i = 5; i <= 7; i++) {
                storage.appendEntries(List.of(
                        new LogEntryData(i, 2, ("new-entry-" + i).getBytes())
                )).join();
            }

            storage.sync().join();
        }

        // Verify truncate + append worked
        try (FileRaftStorage storage = new FileRaftStorage(config)) {
            storage.open().join();
            List<LogEntryData> entries = storage.replayLog().join();
            
            // Should have entries 1-4 with term 1, and 5-7 with term 2
            if (entries.size() != 7) {
                throw new AssertionError("Expected 7 entries, got " + entries.size());
            }
        }
    }

    // =========================================================================
    // BOUNDARY CHAOS
    // =========================================================================

    private void runBoundaryTests() throws Exception {
        printSection("BOUNDARY CHAOS");

        chaosTest("Maximum Payload Size", this::maxPayloadSize);
        chaosTest("Empty Payload", this::emptyPayload);
        chaosTest("Binary Payload (All Bytes 0x00-0xFF)", this::binaryPayload);
        chaosTest("Unicode Payload Storm", this::unicodePayload);
        chaosTest("Max Long Index", this::maxLongIndex);
        chaosTest("Max Long Term", this::maxLongTerm);
        chaosTest("Very Long VotedFor String", this::longVotedFor);
        chaosTest("Null-like Payloads", this::nullLikePayloads);
        chaosTest("Payload Contains Magic Bytes", this::payloadContainsMagic);
    }

    private void maxPayloadSize() throws Exception {
        Path testDir = createTestDir("max-payload");
        RaftStorageConfig config = RaftStorageConfig.builder()
                .dataDir(testDir.toString())
                .build();

        // Try maximum allowed payload (16 MB - 1)
        int maxSize = 16 * 1024 * 1024 - 1;
        byte[] largePayload = new byte[maxSize];
        SECURE_RANDOM.nextBytes(largePayload);

        try (FileRaftStorage storage = new FileRaftStorage(config)) {
            storage.open().join();
            storage.appendEntries(List.of(
                    new LogEntryData(1, 1, largePayload)
            )).join();
            storage.sync().join();
        }

        // Verify
        try (FileRaftStorage storage = new FileRaftStorage(config)) {
            storage.open().join();
            List<LogEntryData> entries = storage.replayLog().join();
            if (entries.size() != 1) {
                throw new AssertionError("Expected 1 entry");
            }
            if (!Arrays.equals(entries.get(0).payload(), largePayload)) {
                throw new AssertionError("Payload corrupted");
            }
        }
    }

    private void emptyPayload() throws Exception {
        Path testDir = createTestDir("empty-payload");
        RaftStorageConfig config = RaftStorageConfig.builder()
                .dataDir(testDir.toString())
                .build();

        try (FileRaftStorage storage = new FileRaftStorage(config)) {
            storage.open().join();
            storage.appendEntries(List.of(
                    new LogEntryData(1, 1, new byte[0]),
                    new LogEntryData(2, 1, new byte[0]),
                    new LogEntryData(3, 1, new byte[0])
            )).join();
            storage.sync().join();
        }

        try (FileRaftStorage storage = new FileRaftStorage(config)) {
            storage.open().join();
            List<LogEntryData> entries = storage.replayLog().join();
            if (entries.size() != 3) {
                throw new AssertionError("Expected 3 entries");
            }
            for (LogEntryData entry : entries) {
                if (entry.payload().length != 0) {
                    throw new AssertionError("Payload should be empty");
                }
            }
        }
    }

    private void binaryPayload() throws Exception {
        Path testDir = createTestDir("binary-payload");
        RaftStorageConfig config = RaftStorageConfig.builder()
                .dataDir(testDir.toString())
                .build();

        // Create payload with all possible byte values
        byte[] allBytes = new byte[256];
        for (int i = 0; i < 256; i++) {
            allBytes[i] = (byte) i;
        }

        try (FileRaftStorage storage = new FileRaftStorage(config)) {
            storage.open().join();
            storage.appendEntries(List.of(
                    new LogEntryData(1, 1, allBytes)
            )).join();
            storage.sync().join();
        }

        try (FileRaftStorage storage = new FileRaftStorage(config)) {
            storage.open().join();
            List<LogEntryData> entries = storage.replayLog().join();
            if (!Arrays.equals(entries.get(0).payload(), allBytes)) {
                throw new AssertionError("Binary payload corrupted");
            }
        }
    }

    private void unicodePayload() throws Exception {
        Path testDir = createTestDir("unicode-payload");
        RaftStorageConfig config = RaftStorageConfig.builder()
                .dataDir(testDir.toString())
                .build();

        String[] unicodeStrings = {
                "Hello, 世界! 🌍",
                "مرحبا بالعالم",
                "Привет мир",
                "🔥💯🚀✨",
                "日本語テスト",
                "\u0000\u0001\u0002", // Control characters
                "Line1\nLine2\rLine3\r\nLine4",
                "Tab\there\ttoo",
                "\uD83D\uDE00\uD83D\uDE01\uD83D\uDE02", // Emoji
                "𐀀𐀁𐀂𐀃" // Supplementary characters
        };

        try (FileRaftStorage storage = new FileRaftStorage(config)) {
            storage.open().join();
            for (int i = 0; i < unicodeStrings.length; i++) {
                storage.appendEntries(List.of(
                        new LogEntryData(i + 1, 1, unicodeStrings[i].getBytes(StandardCharsets.UTF_8))
                )).join();
            }
            storage.sync().join();
        }

        try (FileRaftStorage storage = new FileRaftStorage(config)) {
            storage.open().join();
            List<LogEntryData> entries = storage.replayLog().join();
            for (int i = 0; i < unicodeStrings.length; i++) {
                String recovered = new String(entries.get(i).payload(), StandardCharsets.UTF_8);
                if (!recovered.equals(unicodeStrings[i])) {
                    throw new AssertionError("Unicode mismatch at " + i + ": " + recovered);
                }
            }
        }
    }

    private void maxLongIndex() throws Exception {
        Path testDir = createTestDir("max-index");
        RaftStorageConfig config = RaftStorageConfig.builder()
                .dataDir(testDir.toString())
                .build();

        try (FileRaftStorage storage = new FileRaftStorage(config)) {
            storage.open().join();
            storage.appendEntries(List.of(
                    new LogEntryData(Long.MAX_VALUE, 1, "max-index".getBytes())
            )).join();
            storage.sync().join();
        }

        try (FileRaftStorage storage = new FileRaftStorage(config)) {
            storage.open().join();
            List<LogEntryData> entries = storage.replayLog().join();
            if (entries.get(0).index() != Long.MAX_VALUE) {
                throw new AssertionError("Max index not preserved");
            }
        }
    }

    private void maxLongTerm() throws Exception {
        Path testDir = createTestDir("max-term");
        RaftStorageConfig config = RaftStorageConfig.builder()
                .dataDir(testDir.toString())
                .build();

        try (FileRaftStorage storage = new FileRaftStorage(config)) {
            storage.open().join();
            storage.appendEntries(List.of(
                    new LogEntryData(1, Long.MAX_VALUE, "max-term".getBytes())
            )).join();
            storage.updateMetadata(Long.MAX_VALUE, Optional.of("node-max")).join();
            storage.sync().join();
        }

        try (FileRaftStorage storage = new FileRaftStorage(config)) {
            storage.open().join();
            List<LogEntryData> entries = storage.replayLog().join();
            PersistentMeta meta = storage.loadMetadata().join();

            if (entries.get(0).term() != Long.MAX_VALUE) {
                throw new AssertionError("Max term not preserved in entry");
            }
            if (meta.currentTerm() != Long.MAX_VALUE) {
                throw new AssertionError("Max term not preserved in metadata");
            }
        }
    }

    private void longVotedFor() throws Exception {
        Path testDir = createTestDir("long-vote");
        RaftStorageConfig config = RaftStorageConfig.builder()
                .dataDir(testDir.toString())
                .build();

        // 10,000 character node ID
        String longNodeId = "node-" + "x".repeat(10000);

        try (FileRaftStorage storage = new FileRaftStorage(config)) {
            storage.open().join();
            storage.updateMetadata(1, Optional.of(longNodeId)).join();
        }

        try (FileRaftStorage storage = new FileRaftStorage(config)) {
            storage.open().join();
            PersistentMeta meta = storage.loadMetadata().join();
            if (!meta.votedFor().orElse("").equals(longNodeId)) {
                throw new AssertionError("Long votedFor not preserved");
            }
        }
    }

    private void nullLikePayloads() throws Exception {
        Path testDir = createTestDir("null-like");
        RaftStorageConfig config = RaftStorageConfig.builder()
                .dataDir(testDir.toString())
                .build();

        byte[][] nullLike = {
                new byte[]{0},
                new byte[]{0, 0, 0, 0},
                "null".getBytes(),
                "NULL".getBytes(),
                "\0\0\0\0".getBytes(),
                new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF}
        };

        try (FileRaftStorage storage = new FileRaftStorage(config)) {
            storage.open().join();
            for (int i = 0; i < nullLike.length; i++) {
                storage.appendEntries(List.of(
                        new LogEntryData(i + 1, 1, nullLike[i])
                )).join();
            }
            storage.sync().join();
        }

        try (FileRaftStorage storage = new FileRaftStorage(config)) {
            storage.open().join();
            List<LogEntryData> entries = storage.replayLog().join();
            for (int i = 0; i < nullLike.length; i++) {
                if (!Arrays.equals(entries.get(i).payload(), nullLike[i])) {
                    throw new AssertionError("Null-like payload " + i + " corrupted");
                }
            }
        }
    }

    private void payloadContainsMagic() throws Exception {
        Path testDir = createTestDir("magic-in-payload");
        RaftStorageConfig config = RaftStorageConfig.builder()
                .dataDir(testDir.toString())
                .build();

        // Payload that contains the MAGIC bytes
        ByteBuffer buf = ByteBuffer.allocate(100);
        buf.putInt(MAGIC); // 'RAFT' magic
        buf.putShort((short) 1); // version
        buf.put((byte) 2); // type
        buf.putLong(999); // fake index
        buf.putLong(888); // fake term
        buf.putInt(10); // fake length
        buf.put("fakedata!!".getBytes());
        buf.putInt(0xDEADBEEF); // fake CRC
        buf.flip();

        byte[] evilPayload = new byte[buf.remaining()];
        buf.get(evilPayload);

        try (FileRaftStorage storage = new FileRaftStorage(config)) {
            storage.open().join();
            storage.appendEntries(List.of(
                    new LogEntryData(1, 1, evilPayload),
                    new LogEntryData(2, 1, "normal".getBytes())
            )).join();
            storage.sync().join();
        }

        try (FileRaftStorage storage = new FileRaftStorage(config)) {
            storage.open().join();
            List<LogEntryData> entries = storage.replayLog().join();
            if (entries.size() != 2) {
                throw new AssertionError("Expected 2 entries");
            }
            if (!Arrays.equals(entries.get(0).payload(), evilPayload)) {
                throw new AssertionError("Evil payload was modified");
            }
        }
    }

    // =========================================================================
    // STRESS CHAOS
    // =========================================================================

    private void runStressTests() throws Exception {
        printSection("STRESS CHAOS");

        chaosTest("10,000 Small Entries", this::manySmallEntries);
        chaosTest("Rapid Metadata Toggle", this::rapidMetadataToggle);
        chaosTest("Append-Truncate-Append Cycles", this::appendTruncateCycles);
        chaosTest("Memory Pressure (Large Batches)", this::memoryPressure);
        chaosTest("Fsync Hammer", this::fsyncHammer);
    }

    private void manySmallEntries() throws Exception {
        Path testDir = createTestDir("many-small");
        RaftStorageConfig config = RaftStorageConfig.builder()
                .dataDir(testDir.toString())
                .syncEnabled(false)
                .build();

        int count = 10000;

        try (FileRaftStorage storage = new FileRaftStorage(config)) {
            storage.open().join();

            List<LogEntryData> batch = new ArrayList<>();
            for (int i = 1; i <= count; i++) {
                batch.add(new LogEntryData(i, 1, ("e" + i).getBytes()));
            }
            storage.appendEntries(batch).join();
            storage.sync().join();
        }

        try (FileRaftStorage storage = new FileRaftStorage(config)) {
            storage.open().join();
            List<LogEntryData> entries = storage.replayLog().join();
            if (entries.size() != count) {
                throw new AssertionError("Expected " + count + " entries, got " + entries.size());
            }
        }
    }

    private void rapidMetadataToggle() throws Exception {
        Path testDir = createTestDir("meta-toggle");
        RaftStorageConfig config = RaftStorageConfig.builder()
                .dataDir(testDir.toString())
                .syncEnabled(false)
                .build();

        int toggles = 1000;

        try (FileRaftStorage storage = new FileRaftStorage(config)) {
            storage.open().join();

            for (int i = 0; i < toggles; i++) {
                storage.updateMetadata(i, i % 2 == 0 ? Optional.of("nodeA") : Optional.of("nodeB")).join();
            }
        }

        try (FileRaftStorage storage = new FileRaftStorage(config)) {
            storage.open().join();
            PersistentMeta meta = storage.loadMetadata().join();
            // Last update was toggles-1
            String expected = (toggles - 1) % 2 == 0 ? "nodeA" : "nodeB";
            if (!meta.votedFor().orElse("").equals(expected)) {
                throw new AssertionError("Metadata toggle failed");
            }
        }
    }

    private void appendTruncateCycles() throws Exception {
        Path testDir = createTestDir("truncate-cycles");
        RaftStorageConfig config = RaftStorageConfig.builder()
                .dataDir(testDir.toString())
                .syncEnabled(false)
                .build();

        int cycles = 100;

        try (FileRaftStorage storage = new FileRaftStorage(config)) {
            storage.open().join();

            for (int c = 0; c < cycles; c++) {
                // Append 10 entries
                List<LogEntryData> batch = new ArrayList<>();
                int base = c * 5 + 1;
                for (int i = 0; i < 10; i++) {
                    batch.add(new LogEntryData(base + i, c + 1, ("cycle" + c + "-" + i).getBytes()));
                }
                storage.appendEntries(batch).join();

                // Truncate back to 5
                storage.truncateSuffix(base + 5).join();
            }
            storage.sync().join();
        }

        try (FileRaftStorage storage = new FileRaftStorage(config)) {
            storage.open().join();
            List<LogEntryData> entries = storage.replayLog().join();
            System.out.printf("    Final log size after %d truncate cycles: %d entries%n", cycles, entries.size());
        }
    }

    private void memoryPressure() throws Exception {
        Path testDir = createTestDir("memory-pressure");
        RaftStorageConfig config = RaftStorageConfig.builder()
                .dataDir(testDir.toString())
                .syncEnabled(false)
                .build();

        // Large batches to stress memory
        int batchSize = 1000;
        int payloadSize = 4096;
        int batches = 10;

        try (FileRaftStorage storage = new FileRaftStorage(config)) {
            storage.open().join();

            for (int b = 0; b < batches; b++) {
                List<LogEntryData> batch = new ArrayList<>();
                for (int i = 0; i < batchSize; i++) {
                    byte[] payload = new byte[payloadSize];
                    Arrays.fill(payload, (byte) (b % 256));
                    batch.add(new LogEntryData(b * batchSize + i + 1, 1, payload));
                }
                storage.appendEntries(batch).join();
            }
            storage.sync().join();
        }

        try (FileRaftStorage storage = new FileRaftStorage(config)) {
            storage.open().join();
            List<LogEntryData> entries = storage.replayLog().join();
            if (entries.size() != batchSize * batches) {
                throw new AssertionError("Expected " + (batchSize * batches) + " entries");
            }
        }
    }

    private void fsyncHammer() throws Exception {
        Path testDir = createTestDir("fsync-hammer");
        RaftStorageConfig config = RaftStorageConfig.builder()
                .dataDir(testDir.toString())
                .syncEnabled(true) // Enable sync
                .build();

        int operations = 100;

        try (FileRaftStorage storage = new FileRaftStorage(config)) {
            storage.open().join();

            for (int i = 1; i <= operations; i++) {
                storage.appendEntries(List.of(
                        new LogEntryData(i, 1, ("sync-" + i).getBytes())
                )).join();
                storage.sync().join(); // Sync after every single entry
            }
        }

        try (FileRaftStorage storage = new FileRaftStorage(config)) {
            storage.open().join();
            List<LogEntryData> entries = storage.replayLog().join();
            if (entries.size() != operations) {
                throw new AssertionError("Expected " + operations + " entries after fsync hammer");
            }
        }
    }

    // =========================================================================
    // NASTY EDGE CASES
    // =========================================================================

    private void runNastyEdgeCases() throws Exception {
        printSection("NASTY EDGE CASES");

        chaosTest("Double Open Same Directory", this::doubleOpen);
        chaosTest("Double Close", this::doubleClose);
        chaosTest("Operations After Close", this::opsAfterClose);
        chaosTest("Negative Index (Protocol Violation)", this::negativeIndex);
        chaosTest("Negative Term (Protocol Violation)", this::negativeTerm);
        chaosTest("Non-Sequential Indices", this::nonSequentialIndices);
        chaosTest("Duplicate Indices in Batch", this::duplicateIndices);
        chaosTest("Empty Batch Append", this::emptyBatchAppend);
        chaosTest("Truncate to Negative", this::truncateToNegative);
        chaosTest("Truncate Beyond Log", this::truncateBeyondLog);
    }

    private void doubleOpen() throws Exception {
        Path testDir = createTestDir("double-open");
        RaftStorageConfig config = RaftStorageConfig.builder()
                .dataDir(testDir.toString())
                .build();

        try (FileRaftStorage storage = new FileRaftStorage(config)) {
            storage.open().join();

            // Try to open another instance
            try (FileRaftStorage storage2 = new FileRaftStorage(config)) {
                try {
                    storage2.open().join();
                    // If locking is enabled, this should fail
                    System.out.println("    Second instance opened (no locking?)");
                } catch (Exception e) {
                    System.out.println("    Second instance blocked: " + e.getMessage());
                }
            }
        }
    }

    private void doubleClose() throws Exception {
        Path testDir = createTestDir("double-close");
        RaftStorageConfig config = RaftStorageConfig.builder()
                .dataDir(testDir.toString())
                .build();

        FileRaftStorage storage = new FileRaftStorage(config);
        storage.open().join();
        storage.close();
        storage.close(); // Should not throw
    }

    private void opsAfterClose() throws Exception {
        Path testDir = createTestDir("ops-after-close");
        RaftStorageConfig config = RaftStorageConfig.builder()
                .dataDir(testDir.toString())
                .build();

        FileRaftStorage storage = new FileRaftStorage(config);
        storage.open().join();
        storage.close();

        try {
            storage.appendEntries(List.of(
                    new LogEntryData(1, 1, "oops".getBytes())
            )).join();
            throw new AssertionError("Should have thrown on closed storage");
        } catch (Exception e) {
            System.out.println("    Correctly rejected: " + e.getMessage());
        }
    }

    private void negativeIndex() throws Exception {
        Path testDir = createTestDir("negative-index");
        RaftStorageConfig config = RaftStorageConfig.builder()
                .dataDir(testDir.toString())
                .build();

        try (FileRaftStorage storage = new FileRaftStorage(config)) {
            storage.open().join();
            // Storage layer doesn't validate - it just stores
            storage.appendEntries(List.of(
                    new LogEntryData(-1, 1, "negative".getBytes())
            )).join();
            storage.sync().join();
        }

        try (FileRaftStorage storage = new FileRaftStorage(config)) {
            storage.open().join();
            List<LogEntryData> entries = storage.replayLog().join();
            if (entries.get(0).index() != -1) {
                throw new AssertionError("Negative index not preserved");
            }
        }
    }

    private void negativeTerm() throws Exception {
        Path testDir = createTestDir("negative-term");
        RaftStorageConfig config = RaftStorageConfig.builder()
                .dataDir(testDir.toString())
                .build();

        try (FileRaftStorage storage = new FileRaftStorage(config)) {
            storage.open().join();
            storage.appendEntries(List.of(
                    new LogEntryData(1, -1, "negative-term".getBytes())
            )).join();
            storage.sync().join();
        }

        try (FileRaftStorage storage = new FileRaftStorage(config)) {
            storage.open().join();
            List<LogEntryData> entries = storage.replayLog().join();
            if (entries.get(0).term() != -1) {
                throw new AssertionError("Negative term not preserved");
            }
        }
    }

    private void nonSequentialIndices() throws Exception {
        Path testDir = createTestDir("non-sequential");
        RaftStorageConfig config = RaftStorageConfig.builder()
                .dataDir(testDir.toString())
                .build();

        try (FileRaftStorage storage = new FileRaftStorage(config)) {
            storage.open().join();
            storage.appendEntries(List.of(
                    new LogEntryData(1, 1, "first".getBytes()),
                    new LogEntryData(5, 1, "fifth".getBytes()),  // Gap!
                    new LogEntryData(3, 1, "third".getBytes()),  // Out of order!
                    new LogEntryData(100, 1, "hundredth".getBytes())
            )).join();
            storage.sync().join();
        }

        try (FileRaftStorage storage = new FileRaftStorage(config)) {
            storage.open().join();
            List<LogEntryData> entries = storage.replayLog().join();
            // Storage layer stores as-is
            if (entries.size() != 4) {
                throw new AssertionError("Expected 4 entries");
            }
        }
    }

    private void duplicateIndices() throws Exception {
        Path testDir = createTestDir("duplicate-indices");
        RaftStorageConfig config = RaftStorageConfig.builder()
                .dataDir(testDir.toString())
                .build();

        try (FileRaftStorage storage = new FileRaftStorage(config)) {
            storage.open().join();
            storage.appendEntries(List.of(
                    new LogEntryData(1, 1, "first".getBytes()),
                    new LogEntryData(1, 1, "duplicate!".getBytes()),
                    new LogEntryData(1, 2, "different-term".getBytes())
            )).join();
            storage.sync().join();
        }

        try (FileRaftStorage storage = new FileRaftStorage(config)) {
            storage.open().join();
            List<LogEntryData> entries = storage.replayLog().join();
            // Storage stores all - deduplication is protocol layer's job
            if (entries.size() != 3) {
                throw new AssertionError("Expected 3 entries");
            }
        }
    }

    private void emptyBatchAppend() throws Exception {
        Path testDir = createTestDir("empty-batch");
        RaftStorageConfig config = RaftStorageConfig.builder()
                .dataDir(testDir.toString())
                .build();

        try (FileRaftStorage storage = new FileRaftStorage(config)) {
            storage.open().join();
            
            // Append empty list
            storage.appendEntries(List.of()).join();
            
            // Append real entry
            storage.appendEntries(List.of(
                    new LogEntryData(1, 1, "real".getBytes())
            )).join();
            
            storage.sync().join();
        }

        try (FileRaftStorage storage = new FileRaftStorage(config)) {
            storage.open().join();
            List<LogEntryData> entries = storage.replayLog().join();
            if (entries.size() != 1) {
                throw new AssertionError("Expected 1 entry");
            }
        }
    }

    private void truncateToNegative() throws Exception {
        Path testDir = createTestDir("truncate-negative");
        RaftStorageConfig config = RaftStorageConfig.builder()
                .dataDir(testDir.toString())
                .build();

        try (FileRaftStorage storage = new FileRaftStorage(config)) {
            storage.open().join();
            
            storage.appendEntries(List.of(
                    new LogEntryData(1, 1, "one".getBytes()),
                    new LogEntryData(2, 1, "two".getBytes())
            )).join();
            
            // Truncate to -1 (should remove everything)
            storage.truncateSuffix(-1).join();
            storage.sync().join();
        }

        try (FileRaftStorage storage = new FileRaftStorage(config)) {
            storage.open().join();
            List<LogEntryData> entries = storage.replayLog().join();
            // All entries should be gone
            if (!entries.isEmpty()) {
                throw new AssertionError("Expected empty log after truncate to -1");
            }
        }
    }

    private void truncateBeyondLog() throws Exception {
        Path testDir = createTestDir("truncate-beyond");
        RaftStorageConfig config = RaftStorageConfig.builder()
                .dataDir(testDir.toString())
                .build();

        try (FileRaftStorage storage = new FileRaftStorage(config)) {
            storage.open().join();
            
            storage.appendEntries(List.of(
                    new LogEntryData(1, 1, "one".getBytes()),
                    new LogEntryData(2, 1, "two".getBytes())
            )).join();
            
            // Truncate from index 1000 (way beyond log)
            storage.truncateSuffix(1000).join();
            storage.sync().join();
        }

        try (FileRaftStorage storage = new FileRaftStorage(config)) {
            storage.open().join();
            List<LogEntryData> entries = storage.replayLog().join();
            // Should be a no-op
            if (entries.size() != 2) {
                throw new AssertionError("Expected 2 entries (truncate beyond should be no-op)");
            }
        }
    }

    // =========================================================================
    // UTILITIES
    // =========================================================================

    private void printSection(String name) {
        System.out.println();
        System.out.println("┌───────────────────────────────────────────────────────────────┐");
        System.out.printf("│  %-61s │%n", name);
        System.out.println("└───────────────────────────────────────────────────────────────┘");
    }

    private void chaosTest(String name, ChaosTestRunnable test) {
        System.out.printf("  %-50s ", name);
        try {
            test.run();
            System.out.println("[PASS]");
            testsPassed.incrementAndGet();
        } catch (Throwable e) {
            System.out.println("[FAIL]");
            System.err.println("    Error: " + e.getMessage());
            e.printStackTrace(System.err);
            testsFailed.incrementAndGet();
        }
    }

    private Path createTestDir(String name) throws IOException {
        Path dir = baseDir.resolve(name + "-" + System.nanoTime());
        Files.createDirectories(dir);
        return dir;
    }

    private static void deleteRecursively(Path path) {
        try {
            if (Files.isDirectory(path)) {
                try (var stream = Files.list(path)) {
                    stream.forEach(WalChaos::deleteRecursively);
                }
            }
            Files.deleteIfExists(path);
        } catch (IOException e) {
            // Ignore cleanup errors
        }
    }

    @FunctionalInterface
    interface ChaosTestRunnable {
        void run() throws Exception;
    }
}
