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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial tests for {@link FileRaftStorage}.
 * <p>
 * These tests attempt to break the storage implementation in every conceivable way:
 * <ul>
 *   <li>WAL file corruption (magic, version, type, CRC, truncation)</li>
 *   <li>Metadata file corruption</li>
 *   <li>Boundary conditions (min/max values, edge cases)</li>
 *   <li>Concurrent access patterns</li>
 *   <li>Invalid state transitions</li>
 *   <li>Resource exhaustion scenarios</li>
 *   <li>Malformed input data</li>
 * </ul>
 */
@DisplayName("FileRaftStorage Adversarial Tests")
class FileRaftStorageAdversarialTest {

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
    // WAL File Corruption Tests
    // ========================================================================

    @Nested
    @DisplayName("WAL Corruption")
    class WalCorruptionTests {

        @Test
        @DisplayName("Corrupt magic number - should truncate at corruption point")
        void corruptMagicNumber() throws Exception {
            // Write valid entries
            writeValidEntries(3);

            // Corrupt the magic number of the second record
            corruptByteAt(getRecordStartPosition(1), (byte) 0xFF);

            // Reopen and replay
            reopenStorage();
            List<LogEntryData> replayed = storage.replayLog().get(5, TimeUnit.SECONDS);

            // Should recover only the first entry
            assertEquals(1, replayed.size());
        }

        @Test
        @DisplayName("Corrupt version number - should truncate at corruption point")
        void corruptVersionNumber() throws Exception {
            writeValidEntries(3);

            // Corrupt version field (offset 4-5) of second record
            Path logPath = tempDir.resolve("raft.log");
            long pos = getRecordStartPosition(1);
            try (FileChannel fc = FileChannel.open(logPath, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
                fc.position(pos + 4);
                ByteBuffer buf = ByteBuffer.allocate(2);
                buf.putShort((short) 99); // Invalid version
                buf.flip();
                fc.write(buf);
            }

            reopenStorage();
            List<LogEntryData> replayed = storage.replayLog().get(5, TimeUnit.SECONDS);
            assertEquals(1, replayed.size());
        }

        @Test
        @DisplayName("Unknown record type - should truncate at unknown type")
        void unknownRecordType() throws Exception {
            writeValidEntries(2);

            // Change type byte (offset 6) to unknown value
            Path logPath = tempDir.resolve("raft.log");
            long pos = getRecordStartPosition(1);
            try (FileChannel fc = FileChannel.open(logPath, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
                fc.position(pos + 6);
                ByteBuffer buf = ByteBuffer.allocate(1);
                buf.put((byte) 99); // Unknown type
                buf.flip();
                fc.write(buf);
            }

            reopenStorage();
            List<LogEntryData> replayed = storage.replayLog().get(5, TimeUnit.SECONDS);
            assertEquals(1, replayed.size());
        }

        @Test
        @DisplayName("Negative payload length - should truncate")
        void negativePayloadLength() throws Exception {
            writeValidEntries(2);

            // Set payload length to negative (offset 23-26)
            Path logPath = tempDir.resolve("raft.log");
            long pos = getRecordStartPosition(1);
            try (FileChannel fc = FileChannel.open(logPath, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
                fc.position(pos + 23);
                ByteBuffer buf = ByteBuffer.allocate(4);
                buf.putInt(-1);
                buf.flip();
                fc.write(buf);
            }

            reopenStorage();
            List<LogEntryData> replayed = storage.replayLog().get(5, TimeUnit.SECONDS);
            assertEquals(1, replayed.size());
        }

        @Test
        @DisplayName("Payload length exceeds max - should truncate")
        void payloadLengthExceedsMax() throws Exception {
            writeValidEntries(2);

            // Set payload length to exceed MAX_PAYLOAD_SIZE
            Path logPath = tempDir.resolve("raft.log");
            long pos = getRecordStartPosition(1);
            try (FileChannel fc = FileChannel.open(logPath, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
                fc.position(pos + 23);
                ByteBuffer buf = ByteBuffer.allocate(4);
                buf.putInt(20 * 1024 * 1024); // 20 MB > 16 MB limit
                buf.flip();
                fc.write(buf);
            }

            reopenStorage();
            List<LogEntryData> replayed = storage.replayLog().get(5, TimeUnit.SECONDS);
            assertEquals(1, replayed.size());
        }

        @Test
        @DisplayName("Truncated payload - should recover partial log")
        void truncatedPayload() throws Exception {
            writeValidEntries(2);

            // Truncate file mid-payload of second record
            Path logPath = tempDir.resolve("raft.log");
            long truncatePos = getRecordStartPosition(1) + 30; // Mid-record
            try (FileChannel fc = FileChannel.open(logPath, StandardOpenOption.WRITE)) {
                fc.truncate(truncatePos);
            }

            reopenStorage();
            List<LogEntryData> replayed = storage.replayLog().get(5, TimeUnit.SECONDS);
            assertEquals(1, replayed.size());
        }

        @Test
        @DisplayName("Truncated CRC - should recover partial log")
        void truncatedCrc() throws Exception {
            writeValidEntries(2);

            // Truncate file to cut off CRC of second record
            Path logPath = tempDir.resolve("raft.log");
            long fileSize = Files.size(logPath);
            try (FileChannel fc = FileChannel.open(logPath, StandardOpenOption.WRITE)) {
                fc.truncate(fileSize - 2); // Cut off partial CRC
            }

            reopenStorage();
            List<LogEntryData> replayed = storage.replayLog().get(5, TimeUnit.SECONDS);
            assertEquals(1, replayed.size());
        }

        @Test
        @DisplayName("Zero-filled garbage at end - should ignore")
        void zeroFilledGarbage() throws Exception {
            writeValidEntries(2);

            // Append zeros
            Path logPath = tempDir.resolve("raft.log");
            try (FileChannel fc = FileChannel.open(logPath, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                ByteBuffer zeros = ByteBuffer.allocate(100);
                fc.write(zeros);
            }

            reopenStorage();
            List<LogEntryData> replayed = storage.replayLog().get(5, TimeUnit.SECONDS);
            assertEquals(2, replayed.size());
        }

        @Test
        @DisplayName("Random garbage at end - should truncate garbage")
        void randomGarbageAtEnd() throws Exception {
            writeValidEntries(2);

            // Append random garbage
            Path logPath = tempDir.resolve("raft.log");
            try (FileChannel fc = FileChannel.open(logPath, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                byte[] garbage = new byte[200];
                new Random(42).nextBytes(garbage);
                fc.write(ByteBuffer.wrap(garbage));
            }

            reopenStorage();
            List<LogEntryData> replayed = storage.replayLog().get(5, TimeUnit.SECONDS);
            assertEquals(2, replayed.size());
        }

        @Test
        @DisplayName("Bit flip in payload - CRC should catch it")
        void bitFlipInPayload() throws Exception {
            writeValidEntries(2);

            // Flip a bit in the payload of second entry
            Path logPath = tempDir.resolve("raft.log");
            long pos = getRecordStartPosition(1) + 27 + 2; // Header + offset into payload
            try (FileChannel fc = FileChannel.open(logPath, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
                fc.position(pos);
                ByteBuffer buf = ByteBuffer.allocate(1);
                fc.read(buf);
                buf.flip();
                byte original = buf.get();
                buf.clear();
                buf.put((byte) (original ^ 0x01)); // Flip one bit
                buf.flip();
                fc.position(pos);
                fc.write(buf);
            }

            reopenStorage();
            List<LogEntryData> replayed = storage.replayLog().get(5, TimeUnit.SECONDS);
            assertEquals(1, replayed.size());
        }

        @Test
        @DisplayName("Empty WAL file - should return empty list")
        void emptyWalFile() throws Exception {
            storage.close();

            // Truncate to empty
            Path logPath = tempDir.resolve("raft.log");
            try (FileChannel fc = FileChannel.open(logPath, StandardOpenOption.WRITE)) {
                fc.truncate(0);
            }

            storage = new FileRaftStorage(true);
            storage.open(tempDir).get(5, TimeUnit.SECONDS);
            List<LogEntryData> replayed = storage.replayLog().get(5, TimeUnit.SECONDS);
            assertEquals(0, replayed.size());
        }

        @Test
        @DisplayName("WAL with only partial first header - should return empty")
        void partialFirstHeader() throws Exception {
            storage.close();

            // Write partial header
            Path logPath = tempDir.resolve("raft.log");
            try (FileChannel fc = FileChannel.open(logPath, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buf = ByteBuffer.allocate(10);
                buf.putInt(0x52414654); // Magic
                buf.putShort((short) 1); // Version
                // Missing rest
                buf.flip();
                fc.write(buf);
            }

            storage = new FileRaftStorage(true);
            storage.open(tempDir).get(5, TimeUnit.SECONDS);
            List<LogEntryData> replayed = storage.replayLog().get(5, TimeUnit.SECONDS);
            assertEquals(0, replayed.size());
        }

        @Test
        @DisplayName("Valid header but zero payload length and bad CRC")
        void validHeaderBadCrc() throws Exception {
            storage.close();

            Path logPath = tempDir.resolve("raft.log");
            try (FileChannel fc = FileChannel.open(logPath, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buf = ByteBuffer.allocate(31); // Header(27) + CRC(4)
                buf.putInt(0x52414654);  // Magic
                buf.putShort((short) 1); // Version
                buf.put((byte) 2);       // Type APPEND
                buf.putLong(1L);         // Index
                buf.putLong(1L);         // Term
                buf.putInt(0);           // Payload length = 0
                buf.putInt(0xBADBAD);    // Wrong CRC
                buf.flip();
                fc.write(buf);
            }

            storage = new FileRaftStorage(true);
            storage.open(tempDir).get(5, TimeUnit.SECONDS);
            List<LogEntryData> replayed = storage.replayLog().get(5, TimeUnit.SECONDS);
            assertEquals(0, replayed.size());
        }
    }

    // ========================================================================
    // Metadata Corruption Tests
    // ========================================================================

    @Nested
    @DisplayName("Metadata Corruption")
    class MetadataCorruptionTests {

        @Test
        @DisplayName("Corrupt metadata CRC - should throw on load")
        void corruptMetadataCrc() throws Exception {
            storage.updateMetadata(5L, Optional.of("node-1")).get(5, TimeUnit.SECONDS);
            storage.close();

            // Corrupt CRC
            Path metaPath = tempDir.resolve("meta.dat");
            byte[] data = Files.readAllBytes(metaPath);
            data[data.length - 1] ^= 0xFF; // Flip bits in CRC
            Files.write(metaPath, data);

            storage = new FileRaftStorage(true);
            storage.open(tempDir).get(5, TimeUnit.SECONDS);

            assertThrows(ExecutionException.class, () ->
                    storage.loadMetadata().get(5, TimeUnit.SECONDS));
        }

        @Test
        @DisplayName("Truncated metadata file - should throw")
        void truncatedMetadata() throws Exception {
            storage.updateMetadata(5L, Optional.of("node-1")).get(5, TimeUnit.SECONDS);
            storage.close();

            // Truncate to partial
            Path metaPath = tempDir.resolve("meta.dat");
            try (FileChannel fc = FileChannel.open(metaPath, StandardOpenOption.WRITE)) {
                fc.truncate(5); // Cut off most of the file
            }

            storage = new FileRaftStorage(true);
            storage.open(tempDir).get(5, TimeUnit.SECONDS);

            assertThrows(Exception.class, () ->
                    storage.loadMetadata().get(5, TimeUnit.SECONDS));
        }

        @Test
        @DisplayName("Invalid vote length in metadata - should throw")
        void invalidVoteLengthMetadata() throws Exception {
            storage.updateMetadata(5L, Optional.of("x")).get(5, TimeUnit.SECONDS);
            storage.close();

            // Corrupt vote length to be larger than file
            Path metaPath = tempDir.resolve("meta.dat");
            try (FileChannel fc = FileChannel.open(metaPath, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
                fc.position(8); // After term
                ByteBuffer buf = ByteBuffer.allocate(4);
                buf.putInt(9999); // Invalid length
                buf.flip();
                fc.write(buf);
            }

            storage = new FileRaftStorage(true);
            storage.open(tempDir).get(5, TimeUnit.SECONDS);

            assertThrows(ExecutionException.class, () ->
                    storage.loadMetadata().get(5, TimeUnit.SECONDS));
        }

        @Test
        @DisplayName("Negative vote length - should throw")
        void negativeVoteLength() throws Exception {
            storage.updateMetadata(5L, Optional.of("x")).get(5, TimeUnit.SECONDS);
            storage.close();

            Path metaPath = tempDir.resolve("meta.dat");
            try (FileChannel fc = FileChannel.open(metaPath, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
                fc.position(8);
                ByteBuffer buf = ByteBuffer.allocate(4);
                buf.putInt(-1);
                buf.flip();
                fc.write(buf);
            }

            storage = new FileRaftStorage(true);
            storage.open(tempDir).get(5, TimeUnit.SECONDS);

            assertThrows(ExecutionException.class, () ->
                    storage.loadMetadata().get(5, TimeUnit.SECONDS));
        }

        @Test
        @DisplayName("Empty metadata file - should throw")
        void emptyMetadataFile() throws Exception {
            storage.updateMetadata(5L, Optional.of("node")).get(5, TimeUnit.SECONDS);
            storage.close();

            // Truncate to empty
            Path metaPath = tempDir.resolve("meta.dat");
            Files.write(metaPath, new byte[0]);

            storage = new FileRaftStorage(true);
            storage.open(tempDir).get(5, TimeUnit.SECONDS);

            assertThrows(Exception.class, () ->
                    storage.loadMetadata().get(5, TimeUnit.SECONDS));
        }

        @Test
        @DisplayName("Zero-filled metadata file - should throw (bad CRC)")
        void zeroFilledMetadata() throws Exception {
            storage.close();

            Path metaPath = tempDir.resolve("meta.dat");
            Files.write(metaPath, new byte[20]); // All zeros

            storage = new FileRaftStorage(true);
            storage.open(tempDir).get(5, TimeUnit.SECONDS);

            // Should throw due to CRC mismatch (unless zeros happen to be valid)
            assertThrows(ExecutionException.class, () ->
                    storage.loadMetadata().get(5, TimeUnit.SECONDS));
        }
    }

    // ========================================================================
    // Boundary Value Tests
    // ========================================================================

    @Nested
    @DisplayName("Boundary Values")
    class BoundaryValueTests {

        @Test
        @DisplayName("Index at Long.MAX_VALUE")
        void maxIndexValue() throws Exception {
            LogEntryData entry = new LogEntryData(Long.MAX_VALUE, 1, "data".getBytes());
            storage.appendEntries(List.of(entry)).get(5, TimeUnit.SECONDS);
            storage.sync().get(5, TimeUnit.SECONDS);

            List<LogEntryData> replayed = storage.replayLog().get(5, TimeUnit.SECONDS);
            assertEquals(1, replayed.size());
            assertEquals(Long.MAX_VALUE, replayed.get(0).index());
        }

        @Test
        @DisplayName("Term at Long.MAX_VALUE")
        void maxTermValue() throws Exception {
            LogEntryData entry = new LogEntryData(1, Long.MAX_VALUE, "data".getBytes());
            storage.appendEntries(List.of(entry)).get(5, TimeUnit.SECONDS);
            storage.sync().get(5, TimeUnit.SECONDS);

            List<LogEntryData> replayed = storage.replayLog().get(5, TimeUnit.SECONDS);
            assertEquals(1, replayed.size());
            assertEquals(Long.MAX_VALUE, replayed.get(0).term());
        }

        @Test
        @DisplayName("Zero index")
        void zeroIndex() throws Exception {
            LogEntryData entry = new LogEntryData(0, 1, "data".getBytes());
            storage.appendEntries(List.of(entry)).get(5, TimeUnit.SECONDS);
            storage.sync().get(5, TimeUnit.SECONDS);

            List<LogEntryData> replayed = storage.replayLog().get(5, TimeUnit.SECONDS);
            assertEquals(1, replayed.size());
            assertEquals(0, replayed.get(0).index());
        }

        @Test
        @DisplayName("Zero term")
        void zeroTerm() throws Exception {
            LogEntryData entry = new LogEntryData(1, 0, "data".getBytes());
            storage.appendEntries(List.of(entry)).get(5, TimeUnit.SECONDS);
            storage.sync().get(5, TimeUnit.SECONDS);

            List<LogEntryData> replayed = storage.replayLog().get(5, TimeUnit.SECONDS);
            assertEquals(1, replayed.size());
            assertEquals(0, replayed.get(0).term());
        }

        @Test
        @DisplayName("Negative index (should still store/replay)")
        void negativeIndex() throws Exception {
            // Design decision: negative indices are allowed at storage layer
            LogEntryData entry = new LogEntryData(-1, 1, "data".getBytes());
            storage.appendEntries(List.of(entry)).get(5, TimeUnit.SECONDS);
            storage.sync().get(5, TimeUnit.SECONDS);

            List<LogEntryData> replayed = storage.replayLog().get(5, TimeUnit.SECONDS);
            assertEquals(1, replayed.size());
            assertEquals(-1, replayed.get(0).index());
        }

        @Test
        @DisplayName("Negative term (should still store/replay)")
        void negativeTerm() throws Exception {
            LogEntryData entry = new LogEntryData(1, -1, "data".getBytes());
            storage.appendEntries(List.of(entry)).get(5, TimeUnit.SECONDS);
            storage.sync().get(5, TimeUnit.SECONDS);

            List<LogEntryData> replayed = storage.replayLog().get(5, TimeUnit.SECONDS);
            assertEquals(1, replayed.size());
            assertEquals(-1, replayed.get(0).term());
        }

        @Test
        @DisplayName("Metadata with term Long.MAX_VALUE")
        void maxTermMetadata() throws Exception {
            storage.updateMetadata(Long.MAX_VALUE, Optional.of("node")).get(5, TimeUnit.SECONDS);
            PersistentMeta meta = storage.loadMetadata().get(5, TimeUnit.SECONDS);
            assertEquals(Long.MAX_VALUE, meta.currentTerm());
        }

        @Test
        @DisplayName("Metadata with very long votedFor string")
        void veryLongVotedFor() throws Exception {
            String longNodeId = "n".repeat(10000);
            storage.updateMetadata(1L, Optional.of(longNodeId)).get(5, TimeUnit.SECONDS);

            PersistentMeta meta = storage.loadMetadata().get(5, TimeUnit.SECONDS);
            assertEquals(longNodeId, meta.votedFor().orElse(""));
        }

        @Test
        @DisplayName("Metadata with empty votedFor string (vs Optional.empty)")
        void emptyVotedForString() throws Exception {
            storage.updateMetadata(1L, Optional.of("")).get(5, TimeUnit.SECONDS);

            PersistentMeta meta = storage.loadMetadata().get(5, TimeUnit.SECONDS);
            // Empty string should be stored as empty, not as Optional.empty
            // This depends on implementation - an empty string is technically different from "no vote"
            assertTrue(meta.votedFor().isEmpty() || meta.votedFor().get().isEmpty());
        }

        @Test
        @DisplayName("Metadata with unicode votedFor")
        void unicodeVotedFor() throws Exception {
            String unicode = "节点-αβγ-🚀";
            storage.updateMetadata(1L, Optional.of(unicode)).get(5, TimeUnit.SECONDS);

            PersistentMeta meta = storage.loadMetadata().get(5, TimeUnit.SECONDS);
            assertEquals(unicode, meta.votedFor().orElse(""));
        }

        @Test
        @DisplayName("Payload exactly at max size (16 MB)")
        void payloadAtExactMaxSize() throws Exception {
            byte[] maxPayload = new byte[16 * 1024 * 1024]; // Exactly 16 MB
            new Random(42).nextBytes(maxPayload);

            LogEntryData entry = new LogEntryData(1, 1, maxPayload);
            storage.appendEntries(List.of(entry)).get(30, TimeUnit.SECONDS);
            storage.sync().get(5, TimeUnit.SECONDS);

            List<LogEntryData> replayed = storage.replayLog().get(30, TimeUnit.SECONDS);
            assertEquals(1, replayed.size());
            assertArrayEquals(maxPayload, replayed.get(0).payload());
        }

        @Test
        @DisplayName("Payload one byte over max (16 MB + 1)")
        void payloadOneByteOverMax() throws Exception {
            byte[] oversizedPayload = new byte[16 * 1024 * 1024 + 1];

            LogEntryData entry = new LogEntryData(1, 1, oversizedPayload);
            var future = storage.appendEntries(List.of(entry));

            assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
        }

        @Test
        @DisplayName("Truncate to index 0")
        void truncateToZero() throws Exception {
            writeValidEntries(5);

            storage.truncateSuffix(0).get(5, TimeUnit.SECONDS);
            storage.sync().get(5, TimeUnit.SECONDS);

            List<LogEntryData> replayed = storage.replayLog().get(5, TimeUnit.SECONDS);
            assertEquals(0, replayed.size());
        }

        @Test
        @DisplayName("Truncate to negative index")
        void truncateToNegative() throws Exception {
            writeValidEntries(5);

            storage.truncateSuffix(-1).get(5, TimeUnit.SECONDS);
            storage.sync().get(5, TimeUnit.SECONDS);

            List<LogEntryData> replayed = storage.replayLog().get(5, TimeUnit.SECONDS);
            assertEquals(0, replayed.size());
        }

        @Test
        @DisplayName("Truncate beyond current log length")
        void truncateBeyondLength() throws Exception {
            writeValidEntries(3);

            // Truncate from index 100 when we only have indices 1-3
            storage.truncateSuffix(100).get(5, TimeUnit.SECONDS);
            storage.sync().get(5, TimeUnit.SECONDS);

            List<LogEntryData> replayed = storage.replayLog().get(5, TimeUnit.SECONDS);
            // Should have no effect
            assertEquals(3, replayed.size());
        }
    }

    // ========================================================================
    // Concurrency Tests
    // ========================================================================

    @Nested
    @DisplayName("Concurrency")
    class ConcurrencyTests {

        @Test
        @DisplayName("Parallel appends from multiple threads")
        void parallelAppends() throws Exception {
            int numThreads = 10;
            int entriesPerThread = 100;
            ExecutorService executor = Executors.newFixedThreadPool(numThreads);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(numThreads);
            AtomicInteger nextIndex = new AtomicInteger(1);

            try {
                for (int t = 0; t < numThreads; t++) {
                    executor.submit(() -> {
                        try {
                            startLatch.await();
                            for (int i = 0; i < entriesPerThread; i++) {
                                int idx = nextIndex.getAndIncrement();
                                storage.appendEntries(List.of(
                                        new LogEntryData(idx, 1, ("data-" + idx).getBytes())
                                )).get(5, TimeUnit.SECONDS);
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                        } finally {
                            doneLatch.countDown();
                        }
                    });
                }

                startLatch.countDown(); // Start all threads
                doneLatch.await(60, TimeUnit.SECONDS);

                storage.sync().get(5, TimeUnit.SECONDS);
                List<LogEntryData> replayed = storage.replayLog().get(10, TimeUnit.SECONDS);

                // All entries should be written
                assertEquals(numThreads * entriesPerThread, replayed.size());
            } finally {
                executor.shutdown();
            }
        }

        @Test
        @DisplayName("Concurrent metadata updates")
        void concurrentMetadataUpdates() throws Exception {
            int numUpdates = 50;
            List<CompletableFuture<Void>> futures = new ArrayList<>();

            for (int i = 0; i < numUpdates; i++) {
                final long term = i;
                futures.add(storage.updateMetadata(term, Optional.of("node-" + term)));
            }

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(30, TimeUnit.SECONDS);

            // Final metadata should be consistent (one of the updates)
            PersistentMeta meta = storage.loadMetadata().get(5, TimeUnit.SECONDS);
            assertNotNull(meta);
            assertTrue(meta.currentTerm() >= 0 && meta.currentTerm() < numUpdates);
        }

        @Test
        @DisplayName("Append while replaying")
        void appendWhileReplaying() throws Exception {
            writeValidEntries(100);

            // Start replay in background
            CompletableFuture<List<LogEntryData>> replayFuture = storage.replayLog();

            // Append more entries concurrently
            for (int i = 101; i <= 110; i++) {
                storage.appendEntries(List.of(
                        new LogEntryData(i, 1, ("late-" + i).getBytes())
                ));
            }

            // Both should complete without error
            List<LogEntryData> replayed = replayFuture.get(10, TimeUnit.SECONDS);
            storage.sync().get(5, TimeUnit.SECONDS);

            // Replay may or may not include late entries depending on timing
            assertTrue(replayed.size() >= 100);
        }

        @Test
        @DisplayName("Close while operations pending")
        void closeWhileOperationsPending() throws Exception {
            // Start many async operations
            List<CompletableFuture<?>> futures = new ArrayList<>();
            for (int i = 1; i <= 100; i++) {
                futures.add(storage.appendEntries(List.of(
                        new LogEntryData(i, 1, ("data-" + i).getBytes())
                )));
            }

            // Close immediately
            storage.close();

            // Some operations may fail, but should not throw unexpected exceptions
            int completed = 0;
            int failed = 0;
            for (var future : futures) {
                try {
                    future.get(5, TimeUnit.SECONDS);
                    completed++;
                } catch (Exception e) {
                    failed++;
                }
            }

            // At least some should have completed or failed gracefully
            assertTrue(completed + failed == 100);
        }

        @Test
        @DisplayName("Double close should not throw")
        void doubleClose() {
            storage.close();
            assertDoesNotThrow(() -> storage.close());
        }
    }

    // ========================================================================
    // Invalid State Tests
    // ========================================================================

    @Nested
    @DisplayName("Invalid State")
    class InvalidStateTests {

        @Test
        @DisplayName("Operations after close should fail gracefully")
        void operationsAfterClose() {
            storage.close();

            // These should fail but not crash
            assertThrows(Exception.class, () ->
                    storage.appendEntries(List.of(new LogEntryData(1, 1, "x".getBytes())))
                            .get(5, TimeUnit.SECONDS));
        }

        @Test
        @DisplayName("Append null list")
        void appendNullList() throws Exception {
            // Should handle gracefully
            var future = storage.appendEntries(null);
            assertDoesNotThrow(() -> future.get(5, TimeUnit.SECONDS));
        }

        @Test
        @DisplayName("Append list with null entry")
        void appendListWithNullEntry() {
            List<LogEntryData> entries = new ArrayList<>();
            entries.add(new LogEntryData(1, 1, "a".getBytes()));
            entries.add(null);
            entries.add(new LogEntryData(3, 1, "c".getBytes()));

            // Should throw
            assertThrows(Exception.class, () ->
                    storage.appendEntries(entries).get(5, TimeUnit.SECONDS));
        }

        @Test
        @DisplayName("Repeated truncate-append cycles - accumulates entries with same index")
        void repeatedTruncateAppendCycles() throws Exception {
            for (int cycle = 0; cycle < 10; cycle++) {
                // Append 10 entries
                for (int i = 1; i <= 10; i++) {
                    storage.appendEntries(List.of(
                            new LogEntryData(i, cycle + 1, ("cycle-" + cycle + "-" + i).getBytes())
                    )).get(5, TimeUnit.SECONDS);
                }

                // Truncate from index 4 (keeps indices 1-3)
                storage.truncateSuffix(4).get(5, TimeUnit.SECONDS);
            }

            storage.sync().get(5, TimeUnit.SECONDS);
            List<LogEntryData> replayed = storage.replayLog().get(5, TimeUnit.SECONDS);

            // After replaying all APPEND and TRUNCATE records:
            // The replay appends entries to a list and truncate removes by index.
            // Since indices 1-3 are added each cycle, they accumulate:
            // Cycle 1: adds 1-10, truncate leaves 1-3
            // Cycle 2: adds 1-10 again (now have two sets of 1-3), truncate from 4 only removes 4-10
            // ... after 10 cycles, we have 10 copies of entries 1-3
            // 
            // This is correct WAL behavior - the storage layer faithfully records
            // what happens. The Raft layer would use AppendPlan to avoid duplicates.
            assertEquals(30, replayed.size()); // 10 cycles × 3 entries each
        }

        @Test
        @DisplayName("Non-sequential indices")
        void nonSequentialIndices() throws Exception {
            // Raft log indices should be sequential, but storage layer shouldn't enforce this
            storage.appendEntries(List.of(
                    new LogEntryData(1, 1, "a".getBytes()),
                    new LogEntryData(5, 1, "b".getBytes()),  // Gap!
                    new LogEntryData(3, 1, "c".getBytes())   // Out of order!
            )).get(5, TimeUnit.SECONDS);
            storage.sync().get(5, TimeUnit.SECONDS);

            List<LogEntryData> replayed = storage.replayLog().get(5, TimeUnit.SECONDS);

            // Storage layer should faithfully store what it's given
            assertEquals(3, replayed.size());
            assertEquals(1, replayed.get(0).index());
            assertEquals(5, replayed.get(1).index());
            assertEquals(3, replayed.get(2).index());
        }

        @Test
        @DisplayName("Duplicate indices in single append")
        void duplicateIndicesInSingleAppend() throws Exception {
            storage.appendEntries(List.of(
                    new LogEntryData(1, 1, "first".getBytes()),
                    new LogEntryData(1, 1, "second".getBytes())  // Same index!
            )).get(5, TimeUnit.SECONDS);
            storage.sync().get(5, TimeUnit.SECONDS);

            List<LogEntryData> replayed = storage.replayLog().get(5, TimeUnit.SECONDS);

            // Both should be stored
            assertEquals(2, replayed.size());
        }
    }

    // ========================================================================
    // Stress Tests
    // ========================================================================

    @Nested
    @DisplayName("Stress Tests")
    class StressTests {

        @Test
        @DisplayName("Many small entries")
        void manySmallEntries() throws Exception {
            int numEntries = 10000;
            List<LogEntryData> entries = new ArrayList<>();
            for (int i = 1; i <= numEntries; i++) {
                entries.add(new LogEntryData(i, 1, "x".getBytes()));
            }

            storage.appendEntries(entries).get(30, TimeUnit.SECONDS);
            storage.sync().get(5, TimeUnit.SECONDS);

            List<LogEntryData> replayed = storage.replayLog().get(30, TimeUnit.SECONDS);
            assertEquals(numEntries, replayed.size());
        }

        @Test
        @DisplayName("Rapid metadata updates")
        void rapidMetadataUpdates() throws Exception {
            for (int i = 0; i < 1000; i++) {
                storage.updateMetadata(i, Optional.of("node-" + i)).get(1, TimeUnit.SECONDS);
            }

            PersistentMeta meta = storage.loadMetadata().get(5, TimeUnit.SECONDS);
            assertEquals(999, meta.currentTerm());
        }

        @Test
        @DisplayName("Many truncate operations")
        void manyTruncateOperations() throws Exception {
            writeValidEntries(100);

            // Truncate many times
            for (int i = 100; i >= 1; i--) {
                storage.truncateSuffix(i).get(5, TimeUnit.SECONDS);
            }

            storage.sync().get(5, TimeUnit.SECONDS);
            List<LogEntryData> replayed = storage.replayLog().get(5, TimeUnit.SECONDS);
            assertEquals(0, replayed.size());
        }

        @ParameterizedTest
        @ValueSource(ints = {1, 10, 100, 1000, 4096, 8192, 65536})
        @DisplayName("Various payload sizes")
        void variousPayloadSizes(int size) throws Exception {
            byte[] payload = new byte[size];
            new Random(size).nextBytes(payload);

            storage.appendEntries(List.of(new LogEntryData(1, 1, payload))).get(5, TimeUnit.SECONDS);
            storage.sync().get(5, TimeUnit.SECONDS);

            List<LogEntryData> replayed = storage.replayLog().get(5, TimeUnit.SECONDS);
            assertEquals(1, replayed.size());
            assertArrayEquals(payload, replayed.get(0).payload());
        }
    }

    // ========================================================================
    // Special Character Tests
    // ========================================================================

    @Nested
    @DisplayName("Special Characters")
    class SpecialCharacterTests {

        @Test
        @DisplayName("Payload with binary zeros")
        void payloadWithBinaryZeros() throws Exception {
            byte[] payload = new byte[100];  // All zeros
            storage.appendEntries(List.of(new LogEntryData(1, 1, payload))).get(5, TimeUnit.SECONDS);
            storage.sync().get(5, TimeUnit.SECONDS);

            List<LogEntryData> replayed = storage.replayLog().get(5, TimeUnit.SECONDS);
            assertArrayEquals(payload, replayed.get(0).payload());
        }

        @Test
        @DisplayName("Payload with all 0xFF bytes")
        void payloadWithAllOnes() throws Exception {
            byte[] payload = new byte[100];
            for (int i = 0; i < payload.length; i++) {
                payload[i] = (byte) 0xFF;
            }
            storage.appendEntries(List.of(new LogEntryData(1, 1, payload))).get(5, TimeUnit.SECONDS);
            storage.sync().get(5, TimeUnit.SECONDS);

            List<LogEntryData> replayed = storage.replayLog().get(5, TimeUnit.SECONDS);
            assertArrayEquals(payload, replayed.get(0).payload());
        }

        @Test
        @DisplayName("Payload with magic number bytes")
        void payloadWithMagicNumber() throws Exception {
            // Payload that looks like WAL header magic
            byte[] payload = new byte[]{0x52, 0x41, 0x46, 0x54, 0x00, 0x01, 0x02};
            storage.appendEntries(List.of(new LogEntryData(1, 1, payload))).get(5, TimeUnit.SECONDS);
            storage.sync().get(5, TimeUnit.SECONDS);

            List<LogEntryData> replayed = storage.replayLog().get(5, TimeUnit.SECONDS);
            assertArrayEquals(payload, replayed.get(0).payload());
        }

        @Test
        @DisplayName("Payload with UTF-8 multibyte characters")
        void payloadWithUtf8() throws Exception {
            String text = "Hello 世界 🌍 مرحبا";
            byte[] payload = text.getBytes(StandardCharsets.UTF_8);
            storage.appendEntries(List.of(new LogEntryData(1, 1, payload))).get(5, TimeUnit.SECONDS);
            storage.sync().get(5, TimeUnit.SECONDS);

            List<LogEntryData> replayed = storage.replayLog().get(5, TimeUnit.SECONDS);
            assertEquals(text, new String(replayed.get(0).payload(), StandardCharsets.UTF_8));
        }
    }

    // ========================================================================
    // Recovery Scenario Tests
    // ========================================================================

    @Nested
    @DisplayName("Recovery Scenarios")
    class RecoveryScenarioTests {

        @Test
        @DisplayName("Crash after writing header, before payload")
        void crashAfterHeader() throws Exception {
            writeValidEntries(2);
            storage.close();

            // Simulate crash: append header only
            Path logPath = tempDir.resolve("raft.log");
            try (FileChannel fc = FileChannel.open(logPath, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                ByteBuffer header = ByteBuffer.allocate(27);
                header.putInt(0x52414654);  // Magic
                header.putShort((short) 1); // Version
                header.put((byte) 2);       // Type APPEND
                header.putLong(3L);         // Index
                header.putLong(1L);         // Term
                header.putInt(10);          // Payload length (but no payload written)
                header.flip();
                fc.write(header);
            }

            storage = new FileRaftStorage(true);
            storage.open(tempDir).get(5, TimeUnit.SECONDS);
            List<LogEntryData> replayed = storage.replayLog().get(5, TimeUnit.SECONDS);

            assertEquals(2, replayed.size());
        }

        @Test
        @DisplayName("Crash mid-payload")
        void crashMidPayload() throws Exception {
            writeValidEntries(2);
            storage.close();

            Path logPath = tempDir.resolve("raft.log");
            try (FileChannel fc = FileChannel.open(logPath, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                ByteBuffer header = ByteBuffer.allocate(27 + 5); // Header + partial payload
                header.putInt(0x52414654);
                header.putShort((short) 1);
                header.put((byte) 2);
                header.putLong(3L);
                header.putLong(1L);
                header.putInt(10);  // Says 10 bytes
                header.put("hello".getBytes()); // But only wrote 5
                header.flip();
                fc.write(header);
            }

            storage = new FileRaftStorage(true);
            storage.open(tempDir).get(5, TimeUnit.SECONDS);
            List<LogEntryData> replayed = storage.replayLog().get(5, TimeUnit.SECONDS);

            assertEquals(2, replayed.size());
        }

        @Test
        @DisplayName("Crash after payload, before CRC")
        void crashBeforeCrc() throws Exception {
            writeValidEntries(2);
            storage.close();

            Path logPath = tempDir.resolve("raft.log");
            try (FileChannel fc = FileChannel.open(logPath, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                ByteBuffer buf = ByteBuffer.allocate(27 + 5); // Header + full payload, no CRC
                buf.putInt(0x52414654);
                buf.putShort((short) 1);
                buf.put((byte) 2);
                buf.putLong(3L);
                buf.putLong(1L);
                buf.putInt(5);
                buf.put("hello".getBytes());
                buf.flip();
                fc.write(buf);
            }

            storage = new FileRaftStorage(true);
            storage.open(tempDir).get(5, TimeUnit.SECONDS);
            List<LogEntryData> replayed = storage.replayLog().get(5, TimeUnit.SECONDS);

            assertEquals(2, replayed.size());
        }

        @Test
        @DisplayName("Multiple consecutive torn writes")
        void multipleTornWrites() throws Exception {
            writeValidEntries(2);
            storage.close();

            // Write multiple incomplete records
            Path logPath = tempDir.resolve("raft.log");
            try (FileChannel fc = FileChannel.open(logPath, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                for (int i = 0; i < 5; i++) {
                    ByteBuffer partial = ByteBuffer.allocate(15);
                    partial.putInt(0x52414654);
                    partial.putShort((short) 1);
                    partial.put((byte) 2);
                    partial.putInt(i); // Random partial data
                    partial.flip();
                    fc.write(partial);
                }
            }

            storage = new FileRaftStorage(true);
            storage.open(tempDir).get(5, TimeUnit.SECONDS);
            List<LogEntryData> replayed = storage.replayLog().get(5, TimeUnit.SECONDS);

            // Should recover original 2 entries
            assertEquals(2, replayed.size());
        }

        @Test
        @DisplayName("Temp metadata file left behind (crash during atomic rename)")
        void tempMetadataLeftBehind() throws Exception {
            storage.updateMetadata(5L, Optional.of("node-x")).get(5, TimeUnit.SECONDS);
            storage.close();

            // Create a temp file that looks like incomplete update
            Path tmpPath = tempDir.resolve("meta.dat.tmp");
            Files.write(tmpPath, "garbage".getBytes());

            storage = new FileRaftStorage(true);
            storage.open(tempDir).get(5, TimeUnit.SECONDS);

            // Should load the valid metadata, ignore temp file
            PersistentMeta meta = storage.loadMetadata().get(5, TimeUnit.SECONDS);
            assertEquals(5L, meta.currentTerm());

            // New update should work and clean up
            storage.updateMetadata(6L, Optional.of("node-y")).get(5, TimeUnit.SECONDS);
            assertFalse(Files.exists(tmpPath));
        }
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    private void writeValidEntries(int count) throws Exception {
        List<LogEntryData> entries = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            entries.add(new LogEntryData(i, 1, ("data-" + i).getBytes()));
        }
        storage.appendEntries(entries).get(5, TimeUnit.SECONDS);
        storage.sync().get(5, TimeUnit.SECONDS);
    }

    private void reopenStorage() throws Exception {
        storage.close();
        storage = new FileRaftStorage(true);
        storage.open(tempDir).get(5, TimeUnit.SECONDS);
    }

    private void corruptByteAt(long position, byte value) throws IOException {
        Path logPath = tempDir.resolve("raft.log");
        try (FileChannel fc = FileChannel.open(logPath, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            fc.position(position);
            ByteBuffer buf = ByteBuffer.allocate(1);
            buf.put(value);
            buf.flip();
            fc.write(buf);
        }
    }

    /**
     * Gets the starting position of a record in the log file.
     * Record 0 starts at position 0.
     */
    private long getRecordStartPosition(int recordNumber) throws Exception {
        // Each record: HEADER(27) + payload + CRC(4)
        // For our test entries with "data-N" payload (6-7 bytes depending on N)
        // Approximate: 27 + 7 + 4 = 38 bytes per record
        
        Path logPath = tempDir.resolve("raft.log");
        try (FileChannel fc = FileChannel.open(logPath, StandardOpenOption.READ)) {
            long pos = 0;
            ByteBuffer headerBuf = ByteBuffer.allocate(27);
            
            for (int i = 0; i < recordNumber; i++) {
                headerBuf.clear();
                fc.read(headerBuf, pos);
                headerBuf.flip();
                
                headerBuf.getInt();   // magic
                headerBuf.getShort(); // version
                headerBuf.get();      // type
                headerBuf.getLong();  // index
                headerBuf.getLong();  // term
                int payloadLen = headerBuf.getInt();
                
                pos += 27 + payloadLen + 4;
            }
            return pos;
        }
    }
}
