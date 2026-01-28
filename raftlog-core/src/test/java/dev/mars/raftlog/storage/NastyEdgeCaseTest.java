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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32C;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Nasty edge case tests targeting subtle JVM/OS/Hardware interaction failures.
 * <p>
 * These tests cover failure modes that often slip through even comprehensive test suites:
 * <ol>
 *   <li><b>Zero-Fill Hard Drive Failure:</b> SSD/VM crash leaving zero-filled blocks</li>
 *   <li><b>Directory Metadata Loss:</b> File synced but directory entry not flushed</li>
 *   <li><b>Unchecked Wrap-Around:</b> Integer overflow in batch size calculations</li>
 *   <li><b>Middle-of-Log Corruption:</b> Bit flip in middle entry, not tail</li>
 *   <li><b>Clock Skew:</b> Relying on timestamps instead of atomic operations</li>
 * </ol>
 * 
 * @see <a href="https://queue.acm.org/detail.cfm?id=2903987">Files are hard (ACM Queue)</a>
 */
@DisplayName("Nasty Edge Case Tests (JVM/OS/Hardware)")
class NastyEdgeCaseTest {

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
    // 1. ZERO-FILL HARD DRIVE FAILURE
    // ========================================================================

    @Nested
    @DisplayName("1. Zero-Fill Hard Drive Failure")
    class ZeroFillFailureTests {

        @Test
        @DisplayName("Zero-filled 4KB file treated as empty log (not parsed as record)")
        void zeroFilledFileIsEmptyLog() throws Exception {
            storage.close();

            // Create a file that's all zeros (simulates SSD/VM crash with zero-fill)
            Path logPath = tempDir.resolve("raft.log");
            byte[] zeros = new byte[4096]; // 4KB of zeros
            Files.write(logPath, zeros);

            storage = new FileRaftStorage(true);
            storage.open(tempDir).get(5, TimeUnit.SECONDS);

            // Should treat as empty - magic 0x00000000 != 0x52414654
            List<LogEntryData> replayed = storage.replayLog().get(5, TimeUnit.SECONDS);
            assertEquals(0, replayed.size(), 
                    "Zero-filled file should be treated as empty log");

            // File should be truncated to 0 (garbage removed)
            assertEquals(0, Files.size(logPath), 
                    "Zero-filled garbage should be truncated");
        }

        @Test
        @DisplayName("Zero-filled region after valid entries is truncated")
        void zeroFilledTailTruncated() throws Exception {
            // Write valid entries
            storage.appendEntries(List.of(
                    new LogEntryData(1, 1, "valid-1".getBytes()),
                    new LogEntryData(2, 1, "valid-2".getBytes())
            )).get(5, TimeUnit.SECONDS);
            storage.sync().get(5, TimeUnit.SECONDS);
            
            long validSize = Files.size(tempDir.resolve("raft.log"));
            storage.close();

            // Append zeros (simulates partial zero-fill corruption)
            Path logPath = tempDir.resolve("raft.log");
            try (FileChannel fc = FileChannel.open(logPath, 
                    StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                fc.write(ByteBuffer.allocate(4096)); // 4KB zeros
            }

            storage = new FileRaftStorage(true);
            storage.open(tempDir).get(5, TimeUnit.SECONDS);
            List<LogEntryData> replayed = storage.replayLog().get(5, TimeUnit.SECONDS);

            assertEquals(2, replayed.size(), "Valid entries should survive");
            assertEquals(validSize, Files.size(logPath), 
                    "Zero-filled tail should be truncated");
        }

        @Test
        @DisplayName("Zero magic (0x00000000) correctly rejected as invalid")
        void zeroMagicRejected() throws Exception {
            storage.close();

            // Write a record with magic = 0 (what a zero-fill looks like)
            Path logPath = tempDir.resolve("raft.log");
            try (FileChannel fc = FileChannel.open(logPath, 
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                ByteBuffer buf = ByteBuffer.allocate(100);
                buf.putInt(0x00000000);  // WRONG magic (would be valid if magic were 0)
                buf.putShort((short) 1); // version
                buf.put((byte) 2);       // type APPEND
                buf.putLong(1L);         // index
                buf.putLong(1L);         // term
                buf.putInt(4);           // payload len
                buf.put("test".getBytes());
                // Add CRC
                CRC32C crc = new CRC32C();
                crc.update(buf.array(), 0, buf.position());
                buf.putInt((int) crc.getValue());
                buf.flip();
                fc.write(buf);
            }

            storage = new FileRaftStorage(true);
            storage.open(tempDir).get(5, TimeUnit.SECONDS);
            List<LogEntryData> replayed = storage.replayLog().get(5, TimeUnit.SECONDS);

            assertEquals(0, replayed.size(), 
                    "Record with zero magic should be rejected");
        }
    }

    // ========================================================================
    // 2. DIRECTORY METADATA LOSS
    // ========================================================================

    @Nested
    @DisplayName("2. Directory Metadata Loss")
    class DirectoryMetadataLossTests {

        @Test
        @DisplayName("Atomic rename used for metadata (not timestamp-based)")
        void metadataUsesAtomicRename() throws Exception {
            // Write metadata
            storage.updateMetadata(5L, Optional.of("leader-1")).get(5, TimeUnit.SECONDS);

            // Verify meta.dat exists (result of atomic rename)
            Path metaPath = tempDir.resolve("meta.dat");
            assertTrue(Files.exists(metaPath), "meta.dat should exist after update");

            // Verify temp file does NOT exist (rename was atomic)
            Path tmpPath = tempDir.resolve("meta.dat.tmp");
            assertFalse(Files.exists(tmpPath), 
                    "Temp file should not exist after successful atomic rename");

            // Verify content
            PersistentMeta meta = storage.loadMetadata().get(5, TimeUnit.SECONDS);
            assertEquals(5L, meta.currentTerm());
        }

        @Test
        @DisplayName("Interrupted rename leaves only temp file (detectable state)")
        void interruptedRenameDetectable() throws Exception {
            // Simulate crash: temp file exists but rename didn't complete
            Path tmpPath = tempDir.resolve("meta.dat.tmp");
            // Write "old" metadata
            storage.updateMetadata(1L, Optional.of("old")).get(5, TimeUnit.SECONDS);
            storage.close();

            // Simulate crash during update: both files exist
            // (temp has new data, meta has old data)
            Files.write(tmpPath, "garbage-incomplete".getBytes());

            storage = new FileRaftStorage(true);
            storage.open(tempDir).get(5, TimeUnit.SECONDS);

            // Should load the valid (old) metadata, ignore incomplete temp
            PersistentMeta meta = storage.loadMetadata().get(5, TimeUnit.SECONDS);
            assertEquals(1L, meta.currentTerm(), 
                    "Should use valid meta.dat, not incomplete temp");
        }

        @Test
        @DisplayName("New metadata update cleans up stale temp file")
        void newUpdateCleansStaleTemp() throws Exception {
            Path tmpPath = tempDir.resolve("meta.dat.tmp");
            
            // Create stale temp file
            Files.write(tmpPath, "stale".getBytes());
            assertTrue(Files.exists(tmpPath));

            // New update should overwrite temp then rename
            storage.updateMetadata(10L, Optional.of("new")).get(5, TimeUnit.SECONDS);

            // Temp should be gone
            assertFalse(Files.exists(tmpPath), 
                    "Stale temp file should be cleaned up by new update");

            // New metadata should be valid
            PersistentMeta meta = storage.loadMetadata().get(5, TimeUnit.SECONDS);
            assertEquals(10L, meta.currentTerm());
        }
    }

    // ========================================================================
    // 3. UNCHECKED WRAP-AROUND (INTEGER OVERFLOW)
    // ========================================================================

    @Nested
    @DisplayName("3. Unchecked Wrap-Around (Integer Overflow)")
    class IntegerOverflowTests {

        @Test
        @DisplayName("Batch with total size near Integer.MAX_VALUE handled")
        void largeBatchSizeHandled() throws Exception {
            // We can't actually allocate 2GB+ in a test, but we can verify
            // the implementation doesn't use int arithmetic unsafely
            
            // Calculate: if we had 200 entries of 15MB each = 3GB total
            // This would overflow int (max ~2.1GB)
            int numEntries = 200;
            int payloadSize = 15 * 1024 * 1024; // 15 MB each
            long totalSize = (long) numEntries * payloadSize;
            
            assertTrue(totalSize > Integer.MAX_VALUE, 
                    "Test setup: total should exceed int max");

            // The implementation should reject payloads > 16MB individually
            // So this scenario can't actually occur with valid payloads
            // But let's verify the limit is enforced per-entry
            byte[] tooLarge = new byte[17 * 1024 * 1024]; // 17 MB
            
            var future = storage.appendEntries(List.of(
                    new LogEntryData(1, 1, tooLarge)
            ));
            
            assertThrows(Exception.class, () -> future.get(5, TimeUnit.SECONDS),
                    "Individual 17MB payload should be rejected");
        }

        @Test
        @DisplayName("Many small entries in single batch doesn't overflow")
        void manySmallEntriesBatch() throws Exception {
            // 10,000 entries of 1KB each = 10MB total (safe)
            List<LogEntryData> entries = new ArrayList<>();
            byte[] payload = new byte[1024];
            for (int i = 1; i <= 10000; i++) {
                entries.add(new LogEntryData(i, 1, payload));
            }

            storage.appendEntries(entries).get(60, TimeUnit.SECONDS);
            storage.sync().get(5, TimeUnit.SECONDS);

            List<LogEntryData> replayed = storage.replayLog().get(60, TimeUnit.SECONDS);
            assertEquals(10000, replayed.size());
        }

        @Test
        @DisplayName("Payload length field overflow (negative) rejected")
        void payloadLengthOverflowRejected() throws Exception {
            storage.close();

            // Write a record with payload length that looks negative when cast to int
            // but would be huge if treated as unsigned
            Path logPath = tempDir.resolve("raft.log");
            try (FileChannel fc = FileChannel.open(logPath, 
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                ByteBuffer buf = ByteBuffer.allocate(27);
                buf.putInt(0x52414654);  // Magic
                buf.putShort((short) 1); // version
                buf.put((byte) 2);       // type APPEND
                buf.putLong(1L);         // index
                buf.putLong(1L);         // term
                buf.putInt(Integer.MIN_VALUE); // Negative/huge payload length
                buf.flip();
                fc.write(buf);
            }

            storage = new FileRaftStorage(true);
            storage.open(tempDir).get(5, TimeUnit.SECONDS);
            List<LogEntryData> replayed = storage.replayLog().get(5, TimeUnit.SECONDS);

            assertEquals(0, replayed.size(), 
                    "Negative payload length should be rejected");
        }

        @Test
        @DisplayName("Index wrap-around at Long.MAX_VALUE")
        void indexWrapAround() throws Exception {
            // Write entry at Long.MAX_VALUE
            storage.appendEntries(List.of(
                    new LogEntryData(Long.MAX_VALUE, 1, "max".getBytes())
            )).get(5, TimeUnit.SECONDS);
            storage.sync().get(5, TimeUnit.SECONDS);

            List<LogEntryData> replayed = storage.replayLog().get(5, TimeUnit.SECONDS);
            assertEquals(1, replayed.size());
            assertEquals(Long.MAX_VALUE, replayed.get(0).index());
            
            // Note: In a real Raft implementation, you'd never reach this index,
            // but the storage layer should handle it without overflow errors
        }
    }

    // ========================================================================
    // 4. MIDDLE-OF-THE-LOG CORRUPTION (Critical!)
    // ========================================================================

    @Nested
    @DisplayName("4. Middle-of-the-Log Corruption")
    class MiddleOfLogCorruptionTests {

        @Test
        @DisplayName("Corruption in entry #5 of 10 returns only entries 1-4")
        void corruptionInMiddleReturnsOnlyPriorEntries() throws Exception {
            // Write 10 valid entries
            for (int i = 1; i <= 10; i++) {
                storage.appendEntries(List.of(
                        new LogEntryData(i, 1, ("entry-" + i).getBytes())
                )).get(5, TimeUnit.SECONDS);
            }
            storage.sync().get(5, TimeUnit.SECONDS);
            storage.close();

            // Corrupt entry #5 by flipping a bit in its payload
            // Record layout: HEADER(27) + PAYLOAD(7 for "entry-5") + CRC(4)
            Path logPath = tempDir.resolve("raft.log");
            long entry5Start = findRecordStart(logPath, 4); // 0-indexed
            // Corrupt inside the payload area (after header)
            long payloadOffset = entry5Start + 27 + 2; // into the payload
            
            corruptByteAt(logPath, payloadOffset, (byte) 0xFF);

            storage = new FileRaftStorage(true);
            storage.open(tempDir).get(5, TimeUnit.SECONDS);
            List<LogEntryData> replayed = storage.replayLog().get(5, TimeUnit.SECONDS);

            // CRITICAL: Must return ONLY entries 1-4
            assertEquals(4, replayed.size(), 
                    "Corruption at entry 5 should return only entries 1-4");
            
            for (int i = 0; i < 4; i++) {
                assertEquals(i + 1, replayed.get(i).index(),
                        "Entry " + (i + 1) + " should be intact");
            }
        }

        @Test
        @DisplayName("File truncated at corruption point (no Frankenstein log)")
        void fileTruncatedAtCorruptionPoint() throws Exception {
            // Write 10 entries
            for (int i = 1; i <= 10; i++) {
                storage.appendEntries(List.of(
                        new LogEntryData(i, 1, ("entry-" + i).getBytes())
                )).get(5, TimeUnit.SECONDS);
            }
            storage.sync().get(5, TimeUnit.SECONDS);
            
            // Get size with entries 1-4 (we'll corrupt #5)
            long fullSize = Files.size(tempDir.resolve("raft.log"));
            storage.close();

            // Find where entry 5 starts
            Path logPath = tempDir.resolve("raft.log");
            long entry5Start = findRecordStart(logPath, 4);

            // Corrupt entry 5
            corruptByteAt(logPath, entry5Start + 15, (byte) 0xFF);

            storage = new FileRaftStorage(true);
            storage.open(tempDir).get(5, TimeUnit.SECONDS);
            storage.replayLog().get(5, TimeUnit.SECONDS);

            // CRITICAL: File must be truncated at entry 5 start
            long truncatedSize = Files.size(logPath);
            assertEquals(entry5Start, truncatedSize,
                    "File should be truncated at corruption point, not contain orphaned entries 6-10");
            
            assertTrue(truncatedSize < fullSize,
                    "File must be smaller after truncation");
        }

        @Test
        @DisplayName("New append after middle corruption continues correctly")
        void appendAfterMiddleCorruptionWorks() throws Exception {
            // Write 10 entries
            for (int i = 1; i <= 10; i++) {
                storage.appendEntries(List.of(
                        new LogEntryData(i, 1, ("old-" + i).getBytes())
                )).get(5, TimeUnit.SECONDS);
            }
            storage.sync().get(5, TimeUnit.SECONDS);
            storage.close();

            // Corrupt entry 5
            Path logPath = tempDir.resolve("raft.log");
            long entry5Start = findRecordStart(logPath, 4);
            corruptByteAt(logPath, entry5Start + 20, (byte) 0xFF);

            // Reopen, replay (truncates at corruption), then append new entries
            storage = new FileRaftStorage(true);
            storage.open(tempDir).get(5, TimeUnit.SECONDS);
            storage.replayLog().get(5, TimeUnit.SECONDS); // Triggers truncation

            // Append new entries starting from index 5
            storage.appendEntries(List.of(
                    new LogEntryData(5, 2, "new-5".getBytes()),
                    new LogEntryData(6, 2, "new-6".getBytes())
            )).get(5, TimeUnit.SECONDS);
            storage.sync().get(5, TimeUnit.SECONDS);

            // Verify final state
            List<LogEntryData> replayed = storage.replayLog().get(5, TimeUnit.SECONDS);
            
            assertEquals(6, replayed.size(), "Should have entries 1-6");
            
            // Entries 1-4 from old term
            for (int i = 0; i < 4; i++) {
                assertEquals(1, replayed.get(i).term(), "Entry " + (i+1) + " should be term 1");
            }
            
            // Entries 5-6 from new term
            assertEquals(2, replayed.get(4).term(), "Entry 5 should be term 2 (new)");
            assertEquals(2, replayed.get(5).term(), "Entry 6 should be term 2 (new)");
        }

        @Test
        @DisplayName("Corruption at first entry returns empty log")
        void corruptionAtFirstEntryReturnsEmpty() throws Exception {
            // Write entries
            storage.appendEntries(List.of(
                    new LogEntryData(1, 1, "first".getBytes()),
                    new LogEntryData(2, 1, "second".getBytes())
            )).get(5, TimeUnit.SECONDS);
            storage.sync().get(5, TimeUnit.SECONDS);
            storage.close();

            // Corrupt first entry
            Path logPath = tempDir.resolve("raft.log");
            corruptByteAt(logPath, 10, (byte) 0xFF);

            storage = new FileRaftStorage(true);
            storage.open(tempDir).get(5, TimeUnit.SECONDS);
            List<LogEntryData> replayed = storage.replayLog().get(5, TimeUnit.SECONDS);

            assertEquals(0, replayed.size(), 
                    "Corruption at first entry should return empty log");
            assertEquals(0, Files.size(logPath),
                    "File should be truncated to 0");
        }
    }

    // ========================================================================
    // 5. CLOCK SKEW AND FILE TIMESTAMPS
    // ========================================================================

    @Nested
    @DisplayName("5. Clock Skew and File Timestamps")
    class ClockSkewTests {

        @Test
        @DisplayName("Metadata selection uses atomic rename, not timestamps")
        void metadataUsesAtomicRenameNotTimestamps() throws Exception {
            // This test verifies we don't rely on lastModified()
            
            // Write initial metadata
            storage.updateMetadata(5L, Optional.of("node-a")).get(5, TimeUnit.SECONDS);
            storage.close();

            Path tmpPath = tempDir.resolve("meta.dat.tmp");

            // Create a "newer" temp file with older content (clock skew scenario)
            // If we relied on timestamps, this would be chosen incorrectly
            Thread.sleep(100); // Ensure different timestamp
            Files.write(tmpPath, "newer-timestamp-but-invalid".getBytes());

            // The implementation should:
            // 1. Load meta.dat (the atomically renamed file)
            // 2. Ignore meta.dat.tmp (incomplete transaction)
            storage = new FileRaftStorage(true);
            storage.open(tempDir).get(5, TimeUnit.SECONDS);

            PersistentMeta meta = storage.loadMetadata().get(5, TimeUnit.SECONDS);
            assertEquals(5L, meta.currentTerm(),
                    "Should use atomically renamed file, not newer temp file");
        }

        @Test
        @DisplayName("ATOMIC_MOVE flag is used (not copy-delete)")
        void atomicMoveUsed() throws Exception {
            // We can't directly test that Files.move uses ATOMIC_MOVE,
            // but we can verify the behavior matches atomic semantics:
            // Either the full new content exists, or the old content exists
            
            // Write initial metadata
            storage.updateMetadata(1L, Optional.of("initial")).get(5, TimeUnit.SECONDS);
            
            // Write new metadata
            storage.updateMetadata(2L, Optional.of("updated")).get(5, TimeUnit.SECONDS);
            
            // At this point, we should have ONLY meta.dat (no temp)
            Path metaPath = tempDir.resolve("meta.dat");
            Path tmpPath = tempDir.resolve("meta.dat.tmp");
            
            assertTrue(Files.exists(metaPath), "meta.dat should exist");
            assertFalse(Files.exists(tmpPath), "temp should not exist after atomic move");
            
            // Content should be the new value (atomic swap)
            PersistentMeta meta = storage.loadMetadata().get(5, TimeUnit.SECONDS);
            assertEquals(2L, meta.currentTerm());
            assertEquals("updated", meta.votedFor().orElse(""));
        }

        @Test
        @DisplayName("Recovery doesn't use file modification times")
        void recoveryDoesntUseModificationTimes() throws Exception {
            // Write multiple entries
            for (int i = 1; i <= 5; i++) {
                storage.appendEntries(List.of(
                        new LogEntryData(i, 1, ("entry-" + i).getBytes())
                )).get(5, TimeUnit.SECONDS);
            }
            storage.sync().get(5, TimeUnit.SECONDS);
            storage.close();

            // Modify file timestamps (simulating clock skew)
            Path logPath = tempDir.resolve("raft.log");
            // Set modification time to the past
            Files.setLastModifiedTime(logPath, 
                    java.nio.file.attribute.FileTime.fromMillis(0));

            // Recovery should work based on file content, not timestamps
            storage = new FileRaftStorage(true);
            storage.open(tempDir).get(5, TimeUnit.SECONDS);
            List<LogEntryData> replayed = storage.replayLog().get(5, TimeUnit.SECONDS);

            assertEquals(5, replayed.size(), 
                    "Recovery should work regardless of file timestamps");
        }
    }

    // ========================================================================
    // HELPER METHODS
    // ========================================================================

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

    private void corruptByteAt(Path file, long position, byte xorMask) throws IOException {
        try (FileChannel fc = FileChannel.open(file, 
                StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            fc.position(position);
            ByteBuffer buf = ByteBuffer.allocate(1);
            fc.read(buf);
            buf.flip();
            byte original = buf.get();
            buf.clear();
            buf.put((byte) (original ^ xorMask));
            buf.flip();
            fc.position(position);
            fc.write(buf);
        }
    }
}
