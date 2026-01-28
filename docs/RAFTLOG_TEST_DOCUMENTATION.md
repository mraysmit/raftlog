# RaftLog Test Documentation

This document provides a comprehensive overview of all test cases in the RaftLog project, organized by test class and category.

## Test Summary

| Test Class | Tests | Purpose |
|------------|-------|---------|
| `AppendPlanTest` | 15 | Pure function tests for append plan calculation |
| `FileRaftStorageTest` | 18 | Core storage functionality |
| `FileRaftStorageAdversarialTest` | 64 | Break-the-system stress tests |
| `ProtectionGuaranteeTest` | 24 | Thread safety and crash consistency |
| `EnhancedProtectionTest` | 16 | File locking, disk space, verification |
| `NastyEdgeCaseTest` | 17 | JVM/OS/Hardware interaction failure modes |
| `WalChaos` | 38 | Interactive chaos testing suite |
| **Total** | **192** | |

---

## 1. AppendPlanTest

**File:** `raftlog-core/src/test/java/dev/mars/raftlog/storage/AppendPlanTest.java`

Tests the `AppendPlan` class which calculates what entries to append or truncate based on leader's entries vs local log state.

### Test Cases

| Test | Description |
|------|-------------|
| `emptyLeaderEntries_ReturnsNoOp` | Empty input produces no-op plan |
| `emptyLocalLog_AppendsAll` | All entries appended to empty log |
| `perfectMatch_ReturnsNoOp` | Identical entries produce no-op |
| `newEntriesAfterMatch_AppendsNew` | New entries appended after matching prefix |
| `conflictAtStart_TruncatesAndAppends` | Conflict at index 1 truncates entire log |
| `conflictInMiddle_TruncatesFromConflict` | Conflict mid-log truncates suffix |
| `leaderBehind_ReturnsNoOp` | Leader with older entries doesn't change log |
| `gapInLeaderEntries_AppendsAll` | Gap in indices still appends (storage layer doesn't validate) |
| `singleEntryConflict_Truncates` | Single conflicting entry handled correctly |
| `applyTo_EmptyPlan_NoChange` | No-op plan doesn't modify log |
| `applyTo_AppendOnly_AddsEntries` | Append-only plan adds entries |
| `applyTo_TruncateOnly_RemovesEntries` | Truncate-only plan removes entries |
| `applyTo_TruncateAndAppend_ModifiesLog` | Combined truncate+append works |
| `applyTo_PreservesUnaffectedEntries` | Entries before truncate point preserved |
| `applyTo_EmptyLocalLog_AppendWorks` | Append to empty list works |

---

## 2. FileRaftStorageTest

**File:** `raftlog-core/src/test/java/dev/mars/raftlog/storage/FileRaftStorageTest.java`

Tests core WAL functionality including append, replay, metadata, and basic recovery.

### Metadata Tests

| Test | Description |
|------|-------------|
| `testLoadMetadata_NoFile_ReturnsEmpty` | Missing metadata returns default values |
| `testUpdateAndLoadMetadata` | Metadata round-trip (write then read) |
| `testUpdateMetadata_NoVote` | Empty votedFor stored correctly |
| `testUpdateMetadata_OverwritesPrevious` | New metadata overwrites old |
| `testMetadata_SurvivesRestart` | Metadata persists across storage restarts |

### Append Tests

| Test | Description |
|------|-------------|
| `testAppendAndReplay_SingleEntry` | Single entry append and replay |
| `testAppendAndReplay_MultipleEntries` | Multiple entries in one append |
| `testAppend_EmptyPayload` | Zero-length payload supported |
| `testAppend_LargePayload` | 64 KB payload works correctly |
| `testAppend_NullPayload` | Null payload treated as empty |
| `testAppend_PayloadTooLarge_Fails` | Payload > 16 MB rejected |

### Truncation Tests

| Test | Description |
|------|-------------|
| `testTruncateSuffix` | Truncate removes entries from index onward |
| `testTruncateAndAppend` | Truncate followed by append (leader change scenario) |

### Recovery Tests

| Test | Description |
|------|-------------|
| `testReplay_SurvivesRestart` | Log survives close and reopen |
| `testRecovery_TornWrite_PartialHeader` | Partial header at end is ignored |
| `testRecovery_CorruptCRC` | Corrupt CRC causes truncation at that point |

### Edge Cases

| Test | Description |
|------|-------------|
| `testAppend_EmptyList` | Empty list append is no-op |
| `testReplay_EmptyLog` | Empty log replays to empty list |

---

## 3. FileRaftStorageAdversarialTest

**File:** `raftlog-core/src/test/java/dev/mars/raftlog/storage/FileRaftStorageAdversarialTest.java`

Adversarial tests that attempt to break the storage implementation through corruption, boundary conditions, concurrency, and stress.

### WAL Corruption Tests (13 tests)

| Test | Description |
|------|-------------|
| `corruptMagicNumber` | Bad magic number causes truncation |
| `corruptVersionNumber` | Invalid version causes truncation |
| `unknownRecordType` | Unknown type byte causes truncation |
| `negativePayloadLength` | Negative length causes truncation |
| `payloadLengthExceedsMax` | Length > 16 MB causes truncation |
| `truncatedPayload` | Incomplete payload causes truncation |
| `truncatedCrc` | Missing CRC bytes causes truncation |
| `zeroFilledGarbage` | Zeros at end ignored (no valid magic) |
| `randomGarbageAtEnd` | Random bytes at end truncated |
| `bitFlipInPayload` | Single bit flip detected by CRC |
| `emptyWalFile` | Empty file returns empty log |
| `partialFirstHeader` | Partial first record returns empty |
| `validHeaderBadCrc` | Valid header but wrong CRC rejected |

### Metadata Corruption Tests (6 tests)

| Test | Description |
|------|-------------|
| `corruptMetadataCrc` | Corrupt CRC throws on load |
| `truncatedMetadata` | Truncated file throws |
| `invalidVoteLengthMetadata` | Vote length > file size throws |
| `negativeVoteLength` | Negative length throws |
| `emptyMetadataFile` | Empty file throws |
| `zeroFilledMetadata` | All zeros fails CRC check |

### Boundary Value Tests (15 tests)

| Test | Description |
|------|-------------|
| `maxIndexValue` | `Long.MAX_VALUE` index works |
| `maxTermValue` | `Long.MAX_VALUE` term works |
| `zeroIndex` | Index 0 is valid |
| `zeroTerm` | Term 0 is valid |
| `negativeIndex` | Negative index stored (not validated at storage layer) |
| `negativeTerm` | Negative term stored |
| `maxTermMetadata` | `Long.MAX_VALUE` metadata term works |
| `veryLongVotedFor` | 10,000 character node ID works |
| `emptyVotedForString` | Empty string votedFor handled |
| `unicodeVotedFor` | Unicode characters in votedFor work |
| `payloadAtExactMaxSize` | Exactly 16 MB payload works |
| `payloadOneByteOverMax` | 16 MB + 1 byte rejected |
| `truncateToZero` | Truncate to 0 removes all |
| `truncateToNegative` | Truncate to -1 removes all |
| `truncateBeyondLength` | Truncate beyond log length is no-op |

### Concurrency Tests (5 tests)

| Test | Description |
|------|-------------|
| `parallelAppends` | 10 threads × 100 entries all written |
| `concurrentMetadataUpdates` | 50 concurrent metadata updates succeed |
| `appendWhileReplaying` | Concurrent append and replay work |
| `closeWhileOperationsPending` | Close during writes handled gracefully |
| `doubleClose` | Double close doesn't throw |

### Invalid State Tests (6 tests)

| Test | Description |
|------|-------------|
| `operationsAfterClose` | Operations after close fail gracefully |
| `appendNullList` | Null list handled as no-op |
| `appendListWithNullEntry` | Null entry in list throws |
| `repeatedTruncateAppendCycles` | 10 cycles of truncate+append accumulate entries |
| `nonSequentialIndices` | Non-sequential indices stored as-is |
| `duplicateIndicesInSingleAppend` | Duplicate indices in batch stored |

### Stress Tests (10 tests)

| Test | Description |
|------|-------------|
| `manySmallEntries` | 10,000 entries in one append |
| `rapidMetadataUpdates` | 1,000 metadata updates in sequence |
| `manyTruncateOperations` | 100 truncate operations |
| `variousPayloadSizes` | Parameterized: 1, 10, 100, 1K, 4K, 8K, 64K bytes |

### Special Character Tests (4 tests)

| Test | Description |
|------|-------------|
| `payloadWithBinaryZeros` | All-zero payload works |
| `payloadWithAllOnes` | All 0xFF payload works |
| `payloadWithMagicNumber` | Payload containing "RAFT" magic works |
| `payloadWithUtf8` | UTF-8 multibyte characters work |

### Recovery Scenario Tests (5 tests)

| Test | Description |
|------|-------------|
| `crashAfterHeader` | Crash after header, before payload → recovers previous entries |
| `crashMidPayload` | Crash mid-payload → recovers previous entries |
| `crashBeforeCrc` | Crash before CRC → recovers previous entries |
| `multipleTornWrites` | Multiple incomplete records → all cleaned up |
| `tempMetadataLeftBehind` | Stale temp file ignored on restart |

---

## 7. WalChaos (Interactive Chaos Testing)

**File:** `raftlog-demo/src/main/java/dev/mars/raftlog/demo/WalChaos.java`

An interactive chaos testing suite that throws every nasty scenario at the WAL to verify its robustness. Unlike the JUnit tests, this is a standalone executable that can be run manually to stress test the storage implementation.

### Running WalChaos

```bash
# Build
mvn package -pl raftlog-demo -am -DskipTests

# Run all chaos tests
java -cp raftlog-demo/target/raftlog-demo-1.0-SNAPSHOT.jar dev.mars.raftlog.demo.WalChaos

# Run specific test category
java -cp raftlog-demo/target/raftlog-demo-1.0-SNAPSHOT.jar dev.mars.raftlog.demo.WalChaos concurrent
java -cp raftlog-demo/target/raftlog-demo-1.0-SNAPSHOT.jar dev.mars.raftlog.demo.WalChaos corruption
java -cp raftlog-demo/target/raftlog-demo-1.0-SNAPSHOT.jar dev.mars.raftlog.demo.WalChaos boundary
java -cp raftlog-demo/target/raftlog-demo-1.0-SNAPSHOT.jar dev.mars.raftlog.demo.WalChaos stress
```

### Concurrency Chaos Tests (6 tests)

Tests for race conditions, deadlocks, and concurrent access patterns.

| Test | Description | Validation |
|------|-------------|------------|
| `Concurrent Writer Storm` | 20 threads × 100 entries all writing simultaneously | All 2,000 entries written without corruption or data loss |
| `Concurrent Metadata Thrashing` | 50 threads rapidly updating metadata | Final metadata readable and consistent |
| `Mixed Operations Chaos` | 10 threads performing random append/truncate/metadata/sync ops | Log replays successfully after chaos |
| `Rapid Open/Close Cycles` | 50 cycles of open, write single entry, close immediately | All 50 entries survive across restarts |
| `Concurrent Replay During Writes` | Writer thread and replay thread running in parallel for 2 seconds | No crashes, deadlocks, or corruption |
| `Thread Interrupt Storm` | 10 threads writing while being randomly interrupted | Log survives with partial data intact |

**What these tests catch:**
- Race conditions between concurrent writers
- Deadlocks in internal synchronization
- Data interleaving between writers
- Resource cleanup issues during interrupts
- Lock contention under high load

### Corruption Chaos Tests (8 tests)

Tests for resilience against file-level corruption scenarios.

| Test | Description | Validation |
|------|-------------|------------|
| `Random Byte Corruption in WAL` | Flip random byte in second half of WAL file | Entries before corruption point recovered |
| `Zero-Fill Corruption` | Append 4KB of zeros (SSD block failure simulation) | All 5 valid entries recovered, zeros ignored |
| `Magic Number Corruption` | Corrupt magic number of 3rd entry to `0xDEADBEEF` | Entries before corruption recovered |
| `CRC Bit Flip` | Single bit flip in payload of first entry | CRC mismatch detected, file truncated at corruption |
| `Partial Record (Torn Write)` | Append partial 10-byte header (no index/term/payload/CRC) | All 5 valid entries recovered, partial record ignored |
| `Metadata File Corruption` | Flip first byte of `meta.dat` file | Corruption detected on metadata load |
| `Garbage Append After Valid Data` | Append 256 bytes of random garbage after valid entries | All 5 valid entries recovered |
| `Truncation Point Corruption` | Write 10 entries, truncate at 5, write 3 more | Correct 7 entries with proper terms after recovery |

**What these tests catch:**
- Recovery from disk-level corruption
- CRC32C checksum effectiveness
- Handling of torn/partial writes
- Zero-fill persistence trap (SSD/VM failure mode)
- Garbage data rejection
- Metadata integrity verification

### Boundary Chaos Tests (9 tests)

Tests for extreme values and boundary conditions.

| Test | Description | Validation |
|------|-------------|------------|
| `Maximum Payload Size` | Store 16 MB - 1 byte payload | Payload survives round-trip intact |
| `Empty Payload` | Store 3 entries with zero-length payloads | All 3 entries with `payload.length == 0` |
| `Binary Payload (All Bytes 0x00-0xFF)` | Store payload containing all 256 possible byte values | Exact byte-for-byte match on replay |
| `Unicode Payload Storm` | 10 entries with various Unicode (Chinese, Arabic, Cyrillic, Emoji, control chars, supplementary) | All UTF-8 encoded strings survive intact |
| `Max Long Index` | Entry with `index = Long.MAX_VALUE` | Index preserved exactly |
| `Max Long Term` | Entry and metadata with `term = Long.MAX_VALUE` | Term preserved in both entry and metadata |
| `Very Long VotedFor String` | 10,000+ character node ID in votedFor | String preserved exactly |
| `Null-like Payloads` | Payloads: `{0}`, `{0,0,0,0}`, `"null"`, `"NULL"`, `"\0\0\0\0"`, `{0xFF,0xFF,0xFF,0xFF}` | Each preserved exactly |
| `Payload Contains Magic Bytes` | Payload containing fake RAFT header (magic, version, type, index, term, length, CRC) | Evil payload stored without parser confusion |

**What these tests catch:**
- Integer overflow in size calculations
- Off-by-one errors at boundaries
- Binary data handling (no string assumptions)
- Unicode encoding/decoding correctness
- Long.MAX_VALUE handling in serialization
- Parser confusion from magic bytes in data

### Stress Chaos Tests (5 tests)

High-volume and resource-intensive tests.

| Test | Description | Validation |
|------|-------------|------------|
| `10,000 Small Entries` | Single batch with 10,000 tiny entries | All entries recovered on replay |
| `Rapid Metadata Toggle` | 1,000 sequential metadata updates toggling between nodeA/nodeB | Final metadata reflects last update |
| `Append-Truncate-Append Cycles` | 100 cycles of: append 10, truncate to 5, repeat | Final log has correct entries |
| `Memory Pressure (Large Batches)` | 10 batches × 1,000 entries × 4KB payload (≈40MB total) | All 10,000 entries recovered |
| `Fsync Hammer` | 100 entries, each followed by `sync()` (100 fsyncs) | All entries durable after fsync storm |

**What these tests catch:**
- Performance under high volume
- Memory management during large operations
- Truncate/append interaction correctness
- File handle exhaustion
- I/O subsystem saturation
- Fsync reliability

### Nasty Edge Cases Tests (10 tests)

Tests for subtle protocol violations and API misuse.

| Test | Description | Validation |
|------|-------------|------------|
| `Double Open Same Directory` | Open two FileRaftStorage instances on same directory | Second instance blocked by file lock |
| `Double Close` | Call `close()` twice on same instance | No exception thrown (idempotent close) |
| `Operations After Close` | Attempt append after `close()` | Operation rejected with appropriate exception |
| `Negative Index (Protocol Violation)` | Store entry with `index = -1` | Value stored as-is (storage doesn't validate) |
| `Negative Term (Protocol Violation)` | Store entry with `term = -1` | Value stored as-is |
| `Non-Sequential Indices` | Batch with indices [1, 5, 3, 100] (gaps and out-of-order) | All 4 entries stored as-is |
| `Duplicate Indices in Batch` | Batch with 3 entries all having `index = 1` | All 3 entries stored (dedup is protocol layer's job) |
| `Empty Batch Append` | Append empty `List.of()` followed by real entry | No-op for empty, real entry stored |
| `Truncate to Negative` | `truncateSuffix(-1)` after writing 2 entries | All entries removed (truncate everything) |
| `Truncate Beyond Log` | `truncateSuffix(1000)` on 2-entry log | No-op (both entries preserved) |

**What these tests catch:**
- File locking mechanism effectiveness
- Resource cleanup on close
- Idempotent operations
- Storage layer vs protocol layer responsibility separation
- Edge cases in truncation logic
- Handling of invalid/malicious input

---

## 4. ProtectionGuaranteeTest

**File:** `raftlog-core/src/test/java/dev/mars/raftlog/storage/ProtectionGuaranteeTest.java`

Tests that verify the documented protection guarantees around thread safety, crash consistency, and ordering.

### Thread Safety Guarantees (9 tests)

| Test | Guarantee |
|------|-----------|
| `G1: writeSerializationConcurrentAppends` | 20 threads × 50 entries all written without corruption |
| `G2: noInterleavedWrites` | Each record's payload is uniform (no interleaving) |
| `G3: metadataAtomicUpdates` | Concurrent metadata updates don't corrupt |
| `G4: mixedOperationsSerialized` | Append/truncate/metadata mixed operations work |
| `G5: raceConditionStressTest` | Repeated race condition test (5 repetitions) |

### Crash Consistency Guarantees (7 tests)

| Test | Guarantee |
|------|-----------|
| `G6: crcDetectsBitFlipInHeader` | Single bit flip in header detected |
| `G7: crcDetectsBitFlipInPayload` | Single bit flip in payload detected |
| `G8: crcDetectsBitFlipInCrcField` | Bit flip in CRC field itself detected |
| `G9: recoveryPreservesValidEntriesBeforeCorruption` | Entries before corruption point survive |
| `G10: atomicMetadataUpdate` | Partial metadata write detected |
| `G11: walTruncationOnRecovery` | Torn tail removed on recovery |
| `G12: multipleBitFlipsDetected` | Burst errors detected |

### Failure Mode Handling (5 tests)

| Test | Guarantee |
|------|-----------|
| `F1: simulatedPowerLossAtWriteStages` | Power loss at 7 different points → previous data survives |
| `F2: recoveryAfterMultipleRestarts` | 5 restart cycles all data intact |
| `F3: gracefulDiskFullHandling` | IOException doesn't corrupt existing data |
| `F4: crc32cCollisionResistance` | 10,000 random payloads produce >9,000 unique CRCs |
| `F5: recoveryWithInterleavedCorruption` | Corruption mid-log truncates at that point |

### Ordering Guarantees (3 tests)

| Test | Guarantee |
|------|-----------|
| `O1: writeOrderPreserved` | 1,000 concurrent submissions maintain order |
| `O2: syncBarrierEnsuresDurability` | Data survives "crash" after sync() returns |
| `O3: metadataHappensBefore` | Metadata visible to subsequent operations |

---

## 5. EnhancedProtectionTest

**File:** `raftlog-core/src/test/java/dev/mars/raftlog/storage/EnhancedProtectionTest.java`

Tests for the enhanced protection mechanisms: file locking, disk space checking, and read-after-write verification.

### File Locking Tests (5 tests)

| Test | Description |
|------|-------------|
| `lockFileCreatedOnOpen` | `raft.lock` file created when storage opens |
| `secondInstanceCannotOpenSameDirectory` | Second storage instance blocked |
| `lockReleasedOnCloseAllowsNewInstance` | After close, new instance can open |
| `externalLockPreventsOpen` | External process holding lock blocks open |
| `lockSurvivorCrashSimulation` | Stale lock file (from crash) doesn't block new instance |

### Disk Space Checking Tests (4 tests)

| Test | Description |
|------|-------------|
| `openSucceedsWithSufficientSpace` | Normal open works |
| `normalWritesSucceed` | Normal writes don't trigger space error |
| `largeWriteTriggersDiskSpaceCheck` | >1 MB writes check disk space |
| `documentDiskFullBehavior` | Documents expected behavior when disk full |

### Read-After-Write Verification Tests (5 tests)

| Test | Description |
|------|-------------|
| `verificationModeCanBeEnabled` | `new FileRaftStorage(true, true)` enables verification |
| `multipleVerifiedWritesMaintainConsistency` | 10 verified writes all succeed |
| `largeVerifiedWriteCompletes` | 100 KB verified write works |
| `verificationDisabledByDefault` | Default constructor doesn't verify (faster) |
| `verificationSkippedWhenFsyncDisabled` | Verification skipped in test mode |

### Combined Protection Tests (2 tests)

| Test | Description |
|------|-------------|
| `allProtectionsWorkTogether` | Lock + space check + verification all work |
| `gracefulDegradationWhenLocked` | Helpful error message when lock unavailable |

---

## 6. NastyEdgeCaseTest

**File:** `raftlog-core/src/test/java/dev/mars/raftlog/storage/NastyEdgeCaseTest.java`

Tests for subtle JVM/OS/Hardware interaction failure modes that often slip through standard testing.

### Zero-Fill Hard Drive Failure (3 tests)

Tests for SSD/VM crash scenarios that leave zero-filled blocks.

| Test | Description |
|------|-------------|
| `zeroFilledFileIsEmptyLog` | 4KB zero-filled file treated as empty (not parsed as records) |
| `zeroFilledTailTruncated` | Zero-filled region after valid entries is truncated |
| `zeroMagicRejected` | Record with magic=0x00000000 correctly rejected |

### Directory Metadata Loss (3 tests)

Tests for file sync without directory entry flush scenarios.

| Test | Description |
|------|-------------|
| `metadataUsesAtomicRename` | Atomic rename used for metadata (not timestamp-based) |
| `interruptedRenameDetectable` | Interrupted rename leaves detectable state |
| `newUpdateCleansStaleTemp` | New metadata update cleans up stale temp file |

### Unchecked Wrap-Around / Integer Overflow (4 tests)

Tests for integer overflow in batch size calculations.

| Test | Description |
|------|-------------|
| `largeBatchSizeHandled` | Batch with total size near Integer.MAX_VALUE handled |
| `manySmallEntriesBatch` | 10,000 small entries in single batch doesn't overflow |
| `payloadLengthOverflowRejected` | Negative payload length (overflow) rejected |
| `indexWrapAround` | Index at Long.MAX_VALUE handled correctly |

### Middle-of-the-Log Corruption (4 tests)

**CRITICAL**: Tests for corruption in middle of log (not just tail).

| Test | Description |
|------|-------------|
| `corruptionInMiddleReturnsOnlyPriorEntries` | Corruption at entry #5 of 10 returns only entries 1-4 |
| `fileTruncatedAtCorruptionPoint` | File truncated at corruption point (no orphaned entries 6-10) |
| `appendAfterMiddleCorruptionWorks` | New append after middle corruption continues correctly |
| `corruptionAtFirstEntryReturnsEmpty` | Corruption at first entry returns empty log |

### Clock Skew and File Timestamps (3 tests)

Tests that verify we never rely on file timestamps.

| Test | Description |
|------|-------------|
| `metadataUsesAtomicRenameNotTimestamps` | Metadata selection uses atomic rename, not timestamps |
| `atomicMoveUsed` | ATOMIC_MOVE flag is used (not copy-delete) |
| `recoveryDoesntUseModificationTimes` | Recovery doesn't use file modification times |

---

## Protection Model Summary

### ✅ Protected Against

| Threat | Protection Mechanism | Test Coverage |
|--------|---------------------|---------------|
| **Concurrent writes (same process)** | Single-threaded executor | G1-G5, WalChaos Concurrency |
| **Concurrent writes (different processes)** | Exclusive file lock | File Locking Tests, WalChaos Double Open |
| **Torn writes / power loss** | CRC32C + recovery truncation | G6-G12, F1-F5, WalChaos Corruption |
| **Bit rot / bit flips** | CRC32C checksums | G6-G8, F4, WalChaos CRC Bit Flip |
| **Process crashes** | WAL replay on restart | Recovery Tests, WalChaos Open/Close Cycles |
| **Partial metadata updates** | Atomic rename | G10, WalChaos Metadata Corruption |
| **Disk full** | Pre-flight space check | Disk Space Tests |
| **Silent filesystem corruption** | Read-after-write verification | Verification Tests |
| **Zero-fill persistence trap** | MAGIC != 0x00000000 | NastyEdgeCaseTest, WalChaos Zero-Fill |
| **Middle-of-log corruption** | Truncate at corruption point | NastyEdgeCaseTest, WalChaos Random Corruption |
| **Integer overflow in batches** | Payload size limits | NastyEdgeCaseTest, WalChaos Boundary |
| **Clock skew / timestamp attacks** | Atomic rename only | NastyEdgeCaseTest |
| **Thread interrupts during I/O** | Graceful handling | WalChaos Thread Interrupt Storm |
| **Large payload attacks** | 16 MB limit enforced | WalChaos Max Payload Size |
| **Binary/Unicode data corruption** | Byte-for-byte storage | WalChaos Binary/Unicode Payloads |
| **API misuse** | Defensive coding | WalChaos Nasty Edge Cases |

### ⚠️ Known Limitations

| Limitation | Mitigation |
|------------|------------|
| File locking is advisory on some systems | Document as production requirement |
| Disk space check is point-in-time | Check before large writes |
| Read-after-write has ~5x overhead | Optional, for mission-critical use |
| Directory fsync not implemented | Documented as future enhancement |
| Controller cache lies possible | Requires battery-backed cache in production |

---

## Hardware/OS Assumptions

The implementation relies on the following assumptions about the underlying system:

1. **Atomic Rename**: `Files.move(src, dst, ATOMIC_MOVE)` is atomic with respect to crashes.
   - Standard POSIX guarantee
   - Windows provides similar guarantees on NTFS

2. **Flush Persistence**: `FileChannel.force(true)` ensures data reaches non-volatile storage.
   - Disk write caches must honor flush commands
   - Battery-backed write caches are acceptable

3. **No "Ghost" Writes**: Data written before a crash either appears completely or not at all.
   - No partial block corruption that produces valid-looking data
   - CRC32C provides detection for bit-level corruption

---

## Running Tests

### Unit Tests (JUnit)

```bash
# Run all tests
mvn test

# Run specific test class
mvn test -Dtest=FileRaftStorageAdversarialTest

# Run specific test category
mvn test -Dtest=ProtectionGuaranteeTest$ThreadSafetyGuarantees

# Run with verbose output
mvn test -Dtest=EnhancedProtectionTest -Dsurefire.useFile=false
```

### Chaos Tests (WalChaos)

WalChaos is an interactive chaos testing suite that runs as a standalone Java application:

```bash
# Build the demo module
mvn package -pl raftlog-demo -am -DskipTests

# Run all 38 chaos tests
java -cp raftlog-demo/target/raftlog-demo-1.0-SNAPSHOT.jar dev.mars.raftlog.demo.WalChaos

# Run specific category
java -cp raftlog-demo/target/raftlog-demo-1.0-SNAPSHOT.jar dev.mars.raftlog.demo.WalChaos concurrent
java -cp raftlog-demo/target/raftlog-demo-1.0-SNAPSHOT.jar dev.mars.raftlog.demo.WalChaos corruption
java -cp raftlog-demo/target/raftlog-demo-1.0-SNAPSHOT.jar dev.mars.raftlog.demo.WalChaos boundary
java -cp raftlog-demo/target/raftlog-demo-1.0-SNAPSHOT.jar dev.mars.raftlog.demo.WalChaos stress
```

**WalChaos Output:**
- Creates temporary directory for test files
- Runs tests with real-time pass/fail indication
- Reports total passed/failed at end
- Returns exit code 0 on success, 1 on any failure
- Automatically cleans up temporary files

---

## Test Design Principles

1. **Deterministic**: All tests use fixed seeds for random data when needed
2. **Isolated**: Each test uses `@TempDir` for isolated file system state
3. **Fast**: Most tests complete in milliseconds
4. **Comprehensive**: Cover happy path, edge cases, and failure modes
5. **Documented**: Each test name describes what it verifies

### WalChaos vs Unit Tests

| Aspect | JUnit Tests | WalChaos |
|--------|-------------|----------|
| **Execution** | Automated via Maven | Manual/CI via command line |
| **Output** | JUnit reports | Console with pass/fail indicators |
| **Isolation** | Per-test temp directories | Single chaos directory with cleanup |
| **Purpose** | Regression testing | Exploratory chaos testing |
| **Timing** | Fixed scenarios | Extended stress durations |
| **Verbosity** | Configurable | Real-time logging from WAL |

WalChaos complements the JUnit tests by:
- Running longer-duration stress scenarios
- Providing visual feedback during execution
- Testing multi-threaded scenarios that are timing-dependent
- Being runnable outside of the build system
- Simulating realistic chaos scenarios (power loss, corruption, races)

---

*Generated: January 28, 2026*
