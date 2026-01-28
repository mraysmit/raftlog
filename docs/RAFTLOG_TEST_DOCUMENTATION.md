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
| **Total** | **154** | |

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
| **Concurrent writes (same process)** | Single-threaded executor | G1-G5 |
| **Concurrent writes (different processes)** | Exclusive file lock | File Locking Tests |
| **Torn writes / power loss** | CRC32C + recovery truncation | G6-G12, F1-F5 |
| **Bit rot / bit flips** | CRC32C checksums | G6-G8, F4 |
| **Process crashes** | WAL replay on restart | Recovery Tests |
| **Partial metadata updates** | Atomic rename | G10 |
| **Disk full** | Pre-flight space check | Disk Space Tests |
| **Silent filesystem corruption** | Read-after-write verification | Verification Tests |
| **Zero-fill persistence trap** | MAGIC != 0x00000000 | NastyEdgeCaseTest |
| **Middle-of-log corruption** | Truncate at corruption point | NastyEdgeCaseTest |
| **Integer overflow in batches** | Payload size limits | NastyEdgeCaseTest |
| **Clock skew / timestamp attacks** | Atomic rename only | NastyEdgeCaseTest |

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

---

## Test Design Principles

1. **Deterministic**: All tests use fixed seeds for random data when needed
2. **Isolated**: Each test uses `@TempDir` for isolated file system state
3. **Fast**: Most tests complete in milliseconds
4. **Comprehensive**: Cover happy path, edge cases, and failure modes
5. **Documented**: Each test name describes what it verifies

---

*Generated: January 28, 2026*
