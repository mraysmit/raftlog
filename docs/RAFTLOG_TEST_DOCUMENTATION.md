# RaftLog Test Documentation

This document provides a comprehensive overview of all test cases in the RaftLog project, organized by test class and category.

## Running Tests

### Run everything

```bash
java scripts/VerifyAll.java
```

This is the verification for a release and for any change to the storage, the planner or the
configuration. It needs the JDK and Docker, takes well over an hour, and runs, in order:

1. a clean build of the whole reactor with the coverage gate: every test of every module,
   packaging, and the 99% line and branch requirement on the storage package;
2. a check that no test was skipped, other than the Linux-only tests when not on Linux;
3. every program the project ships, from the packaged jar the README tells users to run, twice
   against the same directory so that the restart and replay path is exercised;
4. the chaos program, all categories;
5. the model soak over 2000 seeds, with proof in its output that the seeds ran;
6. the mutation gate: its self-test, then every mutant;
7. all of the above again inside a Linux container as an unprivileged user.

A step that cannot run is a failure, never a skip, and that includes Docker being unavailable.
The exit code is non-zero unless every step passed.

Do not substitute a subset because a change "only touches" one area. Deciding which checks a
change needs is a judgement, and on this project that judgement was wrong repeatedly: a soak that
ran no seeds, a mutation check skipped because the guards "had not changed", two programs whose
unit tests passed but which were never actually run, a packaged jar that was never built, and a
whole-reactor coverage build that had been failing unnoticed because the modules were only ever
built one at a time. The commands below are for working on one thing at a time. They are not
verification.

### Unit Tests (JUnit)

```bash
# Run all tests
mvn test

# Run specific test class
mvn test -Dtest=FileRaftStorageAdversarialTest

# Run specific test category
mvn test '-Dtest=ProtectionGuaranteeTest$ThreadSafetyGuarantees'

# Run with verbose output
mvn test -Dtest=EnhancedProtectionTest -Dsurefire.useFile=false
```

### Demo Examples

Run these commands from the project root:

```powershell
# Build the executable demo JAR and its dependencies
mvn package -pl raftlog-demo -am -DskipTests

# Run the main WAL example
java -jar raftlog-demo/target/raftlog-demo-1.4.0.jar .\run-data\wal-demo

# Run the key/value replay example
java -cp raftlog-demo/target/raftlog-demo-1.4.0.jar dev.mars.raftlog.demo.KeyValueExample .\run-data\key-values
```

### Chaos Tests (WalChaos)

WalChaos is an interactive chaos testing suite that runs as a standalone Java application:

```bash
# Build the demo module
mvn package -pl raftlog-demo -am -DskipTests

# Run all 38 chaos tests
java -cp raftlog-demo/target/raftlog-demo-1.4.0.jar dev.mars.raftlog.demo.WalChaos

# Run specific category
java -cp raftlog-demo/target/raftlog-demo-1.4.0.jar dev.mars.raftlog.demo.WalChaos concurrent
java -cp raftlog-demo/target/raftlog-demo-1.4.0.jar dev.mars.raftlog.demo.WalChaos corruption
java -cp raftlog-demo/target/raftlog-demo-1.4.0.jar dev.mars.raftlog.demo.WalChaos boundary
java -cp raftlog-demo/target/raftlog-demo-1.4.0.jar dev.mars.raftlog.demo.WalChaos stress
```

**WalChaos Output:**

- Creates temporary directory for test files
- Runs tests with real-time pass/fail indication
- Reports total passed/failed at end
- Returns exit code 0 on success, 1 on any failure
- Automatically cleans up temporary files

---

## Prefix compaction and recovery contract tests

- `FileRaftStoragePrefixCompactionTest`: 9 cases covering reclaiming bytes, inclusive boundaries, retained entries/metadata, repeated operations and restart.
- `FileRaftStorageCompactionFailureTest`: 14 cases covering real filesystem failures, publication ordering, fencing, corruption, and four abruptly terminated child JVMs.
- `FileRaftStorageRecoveryContractTest`: 38 cases covering append/truncate/replay semantics, torn-tail fixtures, and lifecycle races: close draining accepted work, immediate reopen after close, idempotent concurrent opens, operations queued before or behind a failed open, and `sync()` as an ordering barrier with fsync disabled. Also the Raft invariant checks: contiguous appends, term regression, vote change within a term, truncation bounds, the replay-before-write precondition, and `AppendPlan` position arithmetic after prefix compaction.
- `FileRaftStorageInvariantEdgeCaseTest`: 57 cases attacking the invariant checks. The governing property is that the write path and the replay path agree. Covers the last term surviving suffix truncation and compaction, batches that fail part way (a torn record from a device error, an unchecked failure, a disk filling up) never leaving a stale tail, unreadable `meta.dat` refusing updates, index arithmetic at `Long.MAX_VALUE` including a batch that wraps to `Long.MIN_VALUE`, format versioning, the compaction boundary accessor with `AppendPlan`, and null arguments failing the future. Forty seeds of a model-based test drive random operation sequences with restarts against a reference model; `mvn -pl raftlog-core test -Dtest='FileRaftStorageInvariantEdgeCaseTest#soakWritePathAndReplayPathAgainstTheModel' -Draftlog.model.soakSeeds=N` runs the same check over N further seeds. Pass it as a plain Maven property; passing it through `-DargLine` does not reach the forked test JVM, and the soak then runs no seeds and passes in milliseconds. The test prints `MODEL SOAK: ran N seeds`. Two thousand seeds take several minutes; a soak that finishes instantly ran nothing.
- `DurableState` (test support) and `DurableStateTest` (9 cases): the rule that a refusal test must prove more than the refusal. `DurableState.expectUnchanged(dir)` hashes every file under the test directory, except the lock file, and fails if a block of code changed, added or removed any of them, which also catches a leftover staging file. `DurableState.assertRestartAgrees(storage, dir)` closes the instance, reopens the directory, and requires the replayed log to match what the live instance replayed, payload bytes included. Every refusal in the suite is wrapped in the first, and every refusal test ends with a restart. This covers validation rejections, operations on closed, unopened or fenced instances, a second instance blocked by the lock, and every helper that asserts corruption was reported with the file left intact. Injected I/O failures are deliberately not wrapped: those may leave a staging file or a torn record by design. `DurableStateTest` proves the checker itself notices appended bytes, a same-size content change, truncation, a new file, a removed file and a change in a nested directory.
- `WalRecords` (test support) and `WalRecordsTest` (8 cases): exact accounting for tests where many threads write at once. A single refusal cannot be checked byte-for-byte while other threads are legitimately writing, but the end state can: the WAL is append-only, so every record in it must come from an operation the storage accepted, and every accepted operation must be in it. The reader is written independently of the production decoder from the documented record format, and is strict about CRCs and trailing bytes. `assertNoStrayFiles` rejects leftover staging files. The concurrent tests in `ProtectionGuaranteeTest` and `FileRaftStorageAdversarialTest` record what was accepted and assert this, then restart. The concurrent metadata test ends on a guaranteed refusal, because an accepted update replaces the staging file and would otherwise mask a refusal that left one behind. `WalChaos` applies the same rule: `expectRefusal` compares a hash of the data directory before and after, and the concurrent scenarios require the WAL length to equal the bytes of the accepted records.
- **The mutation gate is a hard requirement, and it is a program, not a habit.** Run `java scripts/MutationCheck.java` from the repository root before every release, and after any change that adds or alters a refusal, a failure path or a safety rule. It needs only the JDK. Each mutant removes or inverts one safety rule, the tests named for it are run, and they must fail. Three outcomes fail the gate: SURVIVED (the tests passed with the rule removed), STALE (the text to mutate is no longer in the source exactly once, so the mutant was silently not being applied), and INVALID (the mutant did not compile, which also exits non-zero and must never be mistaken for a kill). Sources are restored after every mutant and verified by checksum. `--verify` checks in seconds that every mutation still applies. When a safety rule is added, its mutant is added in the same change. This exists because the check was once skipped on a judgement that the guards had not changed, in a round that changed the decoder, the configuration and the planner. Line coverage cannot replace it: coverage says a line ran, not that any test would notice if it were wrong.
- **How the first guards were validated.** These guards were validated by deliberately breaking the storage, first so that a refused append writes a record before validation and then so that a refused metadata update leaves its staging file. The guarded tests and the chaos program must fail under those mutations. One guard, the concurrent metadata test, did not fail at first, which is how its masking problem was found. Repeat the mutation whenever a new kind of refusal is added.
- `FileRaftStorageFailurePathTest` (56 cases), `LogSanitizationTest` (10) and `RaftStorageDefaultsTest` (4): one test for every refusal and failure path that line coverage showed no test had ever executed. The list came from `mvn -Pcoverage test`, not from intuition, and it was long: the replay shape checks (gap, duplicate, term regression, first entry not following the boundary, misplaced prefix marker) had never run in any test. Also covered: historical truncate records below 1 or below the compaction boundary, torn-tail classification for a newer format, an unknown type, a version-0 record, a torn TRUNCATE record, a torn payload that contains the magic bytes or spans several scan windows, and a valid record several windows beyond an apparently torn header. Open: an orphaned compaction output, a second directory on an open instance, a lock held by another process (child JVM), failure after the channel exists, and close racing a failing open. Close: on the executor thread, a refused close task, and failure to close either channel or release the lock. Metadata: an unusable staging path and a metadata path that cannot be read at all. Writes: checked and unchecked failure writing a TRUNCATE record, failure to measure disk space, compaction running out of disk before publication, close or cleanup failing inside a failed compaction, and write verification catching a changed payload, a damaged stored checksum and a short write. Fencing: operations queued before a fence fail with the fencing failure and write nothing. Each test asserts the failure seen, the bytes on disk afterwards, and what the instance will and will not do next.
- **Coverage as the work list.** Every line of `FileRaftStorage`, `AppendPlan` and `RaftStorage` is now executed by a test. Between Windows and Linux every line of `CompactionIo` is executed too: the directory force body runs on Linux and its early return runs on Windows. No partly taken branches remain in the storage package. Previously: `if (warn)` logging branches, and three defensive branches that are unreachable by construction (a second fence, a scheduling failure on an already-fenced instance, and a stale failed-open future). Before adding a refusal or failure path, run the coverage profile and confirm no `throw`, `fence` or failed-future line is unexecuted.
- **Run it on Linux as an unprivileged user.** Root ignores read-only permissions, so as root the permission-based tests skip themselves and their paths go unexecuted. From the repository root, with Docker running: mount the repository read-only into `maven:3.9-eclipse-temurin-25`, copy it to a scratch directory inside the container, write a `toolchains.xml` pointing at `$JAVA_HOME`, install the parent POM with `mvn -N install`, then run `mvn -pl raftlog-core -Pcoverage install` with `--user 1000:1000`. The expected result is no skipped tests at all. A path whose only test depends on the platform or the user also needs a deterministic test through a `CompactionIo` seam; the replay I/O failure has both.
- **Give each seam one meaning.** Replay briefly shared the `reopen` seam with compaction. Two compaction tests count and fail `reopen` calls, and they broke, because compaction reads the log before it rewrites it. Replay now has `openForReplay`. Run the whole suite after touching a seam, not just the test that needed it.
- **A test that silently stopped testing.** `verifyWritesForceFailureFencesTheInstance` passed for two days without fencing anything: the replay precondition refused its append with `LOG_STATE_UNKNOWN`, which is also a `StorageException`, so its loose type check was satisfied. `assertFenced` now requires the identical failure instance for every operation and rejects an ordinary refusal. Assert the specific failure, never just its supertype.
- `GoldenFileCompatibilityTest` (27 cases) and `src/test/resources/golden`: reference data directories kept as binary fixtures. They are organised by WAL record format, which is what decides whether a file can be read: `format-1` holds `APPEND` and `TRUNCATE` records only and was written by a published jar downloaded from Maven Central and verified against its published checksum, never rebuilt from source; `format-2` is the current format, in which a compacted log begins with a `PREFIX` record, and was written by the current build. The test pins the number of scenarios in each (13 and 8) and requires every binary fixture to be listed in `SHA256SUMS`. `GoldenFileGenerator.java.txt` beside the fixtures is the program that produced them, with the instructions for adding more. Each scenario carries `expected.txt`, the replay recorded by the jar that wrote the files, so the oracle is recorded behaviour and not an expectation of it. Contract one: a well-formed directory replays exactly as recorded, payload bytes included, keeps its metadata, is not rewritten by replay, can be continued, and survives a restart. Contract two: a log that is not a valid Raft log (a gap, a duplicate index, a term regression) is refused without being modified. Fixtures are copied before use, because the storage locks the directory and may repair a torn tail. `SHA256SUMS` plus a `.gitattributes` in that directory guard against a checkout rewriting line endings inside a binary fixture. Generate fixtures with every release; without them, existing data directories still replaying is only an assumption.
- **Reads that come back short** (in `FileRaftStorageFailurePathTest`): a positional-read seam simulates the WAL shrinking underneath replay. Before the fix a short read during tail classification answered "incomplete", which is the answer that gets a record truncated: it destroyed a complete record with a bad checksum, and in the scan it destroyed an intact record lying beyond a damaged length field. A short header read in the main loop declared a healthy log corrupt and fenced the instance.
- `AppendPlanStrictnessTest` (50 cases): `AppendPlan` is not lenient. Every inconsistency in its arguments throws: null lists or elements, a start index that disagrees with the entries or is below 1, a negative boundary, gaps, duplicates, decreasing or negative terms, indices that wrap past `Long.MAX_VALUE`, a log that does not begin right after its boundary, a gap between the log and the incoming entries, a term lower than the entry it would follow, and two entries with the same index and term but different payloads, which means the logs have diverged. The record constructor and `applyTo` are covered as well, including that a refused `applyTo` leaves the log untouched. What stays legal is pinned too: a heartbeat, entries already held, entries covered by the snapshot, and a conflicting tail. Eleven older tests asserted the previous leniency, for example that planning entries 3 and 4 onto a log ending at 1 was fine; each now asserts the refusal.
- `RaftStorageConfigSourcesTest` (32 cases): configuration arrives from the builder, system properties, environment variables and a properties file. Only the first two had ever been tested. Seams replace the environment and the properties file, since a JVM cannot change its own environment. Covers precedence, blank values, that fsync cannot be disabled from any source, and validation of the resolved limits whatever supplied them. Configuration is never guessed: a value that cannot be parsed is refused from every source with a message naming the setting, the source and the value, even when a higher-priority source overrides it, and a properties file that exists but cannot be read is an error. Six older tests asserted the opposite, one of them that `yes` quietly means false; each now asserts the refusal. The payload limit must be 1 to 2047 MB, because its size in bytes is computed in 32-bit arithmetic and 2048 overflows to a negative number.
- **The write limit is not a read limit** (in `FileRaftStorageFailurePathTest`): lowering `maxPayloadSizeMb` below the size of an entry already in the log must not make a healthy log replay as corrupt. A record being read is bounded by the file that holds it, and the CRC decides whether it is genuine. The limit still applies to new writes, and still serves as a plausibility check on torn writes.
- **The coverage gate.** `mvn -Pcoverage verify` fails if the `dev.mars.raftlog.storage` package drops below 99% of lines or branches. It was validated by setting it to 100%, which Windows alone cannot reach, and confirming the build broke. The remaining fraction is the half of `CompactionIo.forceDirectory` that the other platform runs.
- `WalChaosTest` (7 cases, in `raftlog-demo`): runs the chaos suite as part of the build. A chaos program that somebody has to remember to launch lets the build be green while every scenario is failing, so `WalChaos.run(category)` returns a summary, and `main` only turns it into an exit code. The test runs each category and the whole suite, requires zero failures, and pins the number of scenarios per category (6, 8, 9, 5 and 10, which is 38), so a scenario that is deleted or silently stops being registered fails the build. An unknown category throws, because running nothing and reporting success is worse than refusing. Every category, `nasty` included, can be run on its own.
- `FileRaftStorageFencingTest`: 13 cases covering fencing after a failed WAL, metadata or directory force, and the replay classification of torn tails versus corruption inside the committed region.

Replay policy: only a structurally incomplete EOF fragment is treated as a torn write and truncated. A complete record with a bad CRC, a malformed header, arbitrary garbage, or an invalid record followed by a valid record is reported as `CorruptLogException`; the file is left untouched and the instance is fenced.

The 23 compaction cases were retained as behavioral failures before implementation and then passed. See [Prefix compaction](RAFTLOG_RAFT_WAL_DESIGN.md#139-prefix-compaction-implementation-notes).

## Test Summary

| Module | Test Class | Tests | Purpose |
|--------|------------|-------|---------|
| `raftlog-core` | `AppendPlanTest` | 15 | Append plan calculation |
| `raftlog-core` | `AppendPlanStrictnessTest` | 50 | Every inconsistent `AppendPlan` argument is an error |
| `raftlog-core` | `FileRaftStorageTest` | 18 | Core storage functionality |
| `raftlog-core` | `FileRaftStorageAdversarialTest` | 65 | Break-the-system stress tests |
| `raftlog-core` | `FileRaftStorageInvariantEdgeCaseTest` | 57 | Raft log and metadata invariants, written and replayed in the same test |
| `raftlog-core` | `FileRaftStorageFailurePathTest` | 56 | Every refusal and failure path |
| `raftlog-core` | `FileRaftStorageRecoveryContractTest` | 38 | Replay and recovery contract |
| `raftlog-core` | `FileRaftStorageFencingTest` | 13 | Fencing after a failed force; torn tail versus corruption |
| `raftlog-core` | `FileRaftStorageCompactionFailureTest` | 14 | Prefix compaction under injected I/O failure |
| `raftlog-core` | `FileRaftStoragePrefixCompactionTest` | 9 | Prefix compaction |
| `raftlog-core` | `FileRaftStorageLoggingTest` | 5 | What the storage logs |
| `raftlog-core` | `LogSanitizationTest` | 10 | Untrusted text never reaches the log unbounded or multi-line |
| `raftlog-core` | `GoldenFileCompatibilityTest` | 27 | Existing data directories, by WAL format |
| `raftlog-core` | `ProtectionGuaranteeTest` | 24 | Thread safety and crash consistency |
| `raftlog-core` | `EnhancedProtectionTest` | 16 | File locking, disk space, verification |
| `raftlog-core` | `NastyEdgeCaseTest` | 18 | JVM/OS/hardware interaction failure modes |
| `raftlog-core` | `ConfigResolverTest` | 33 | Configuration resolution |
| `raftlog-core` | `RaftStorageConfigSourcesTest` | 32 | Every configuration source, strictly parsed |
| `raftlog-core` | `RaftStorageDefaultsTest` | 4 | Defaults of the `RaftStorage` interface |
| `raftlog-core` | `CoverageBoostTest` | 47 | Paths no other test reaches |
| `raftlog-core` | `HighCoverageTest` | 40 | Paths no other test reaches |
| `raftlog-core` | `DurableStateTest` | 9 | The test support that proves a directory is untouched |
| `raftlog-core` | `WalRecordsTest` | 8 | The independent raw WAL reader used by the tests |
| `raftlog-demo` | `WalChaosTest` | 7 | Runs all 38 chaos scenarios in the build |
| `raftlog-demo` | `KeyValueExampleTest` | 4 | Key/value replay example |
| `raftlog-demo` | `DemoInfoLoggingSafetyTest` | 2 | Demo logging |
| `raftlog-demo` | `ExampleInfoLoggingTest` | 2 | Example logging |
| | **Total** | **623** | 608 in `raftlog-core`, 15 in `raftlog-demo` |

Three of the core tests need POSIX file permissions and are skipped on Windows; they run in the Linux half of `java scripts/VerifyAll.java`, where nothing is skipped.

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
| `testRecovery_CorruptCRC` | Corrupt CRC on the last record is reported and preserved |

### Edge Cases

| Test | Description |
|------|-------------|
| `testAppend_EmptyList` | Empty list append is no-op |
| `testReplay_EmptyLog` | Empty log replays to empty list |

---

## 3. FileRaftStorageAdversarialTest

**File:** `raftlog-core/src/test/java/dev/mars/raftlog/storage/FileRaftStorageAdversarialTest.java`

Adversarial tests that attempt to break the storage implementation through corruption, boundary conditions, concurrency, and stress.

### WAL Corruption Tests (14 tests)

| Test | Description |
|------|-------------|
| `corruptMagicNumber` | Bad magic with a valid record after it is reported, file untouched |
| `corruptMagicNumberAtTail` | Bad magic on the last record is reported and preserved |
| `corruptVersionNumber` | Invalid version on the last record is reported and preserved |
| `unknownRecordType` | Unknown type byte is reported and preserved |
| `negativePayloadLength` | Negative length is reported and preserved |
| `payloadLengthExceedsMax` | Length > 16 MB is reported and preserved |
| `truncatedPayload` | Incomplete payload causes truncation |
| `truncatedCrc` | Missing CRC bytes causes truncation |
| `zeroFilledGarbage` | Zeros at the end are reported as ambiguous corruption |
| `randomGarbageAtEnd` | Random bytes at the end are reported as ambiguous corruption |
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
| `maxIndexValue` | `Long.MAX_VALUE` index works as the continuation of a log compacted through `Long.MAX_VALUE - 1` |
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
java -cp raftlog-demo/target/raftlog-demo-1.4.0.jar dev.mars.raftlog.demo.WalChaos

# Run specific test category
java -cp raftlog-demo/target/raftlog-demo-1.4.0.jar dev.mars.raftlog.demo.WalChaos concurrent
java -cp raftlog-demo/target/raftlog-demo-1.4.0.jar dev.mars.raftlog.demo.WalChaos corruption
java -cp raftlog-demo/target/raftlog-demo-1.4.0.jar dev.mars.raftlog.demo.WalChaos boundary
java -cp raftlog-demo/target/raftlog-demo-1.4.0.jar dev.mars.raftlog.demo.WalChaos stress
```

### Concurrency Chaos Tests (6 tests)

Tests for race conditions, deadlocks, and concurrent access patterns.

| Test | Description | Validation |
|------|-------------|------------|
| `Concurrent Writer Storm` | 20 threads × 100 entries racing to append indices from a shared counter | Only appends that continue the tail are accepted; every other one is refused with `INDEX_NOT_CONTIGUOUS`; accepted + refused = 2,000; replay returns exactly the accepted entries, contiguous, with intact payloads |
| `Concurrent Metadata Thrashing` | 50 threads persisting unordered terms | Lower terms refused with `TERM_REGRESSION`; persisted term is the highest accepted one with its own vote |
| `Mixed Operations Chaos` | 10 threads performing random append/truncate/metadata/sync ops | Every ordering violation refused with a reason, nothing else fails; surviving log is contiguous |
| `Rapid Open/Close Cycles` | 50 cycles of open, write single entry, close immediately | Writing before replay is refused with `LOG_STATE_UNKNOWN`; after replay all 50 entries survive across restarts |
| `Concurrent Replay During Writes` | Writer thread and replay thread running in parallel for 2 seconds | No crashes, deadlocks, or corruption |
| `Thread Interrupt Storm` | 10 threads racing to write while being randomly interrupted | Out-of-order appends refused; log survives contiguous |

**What these tests catch:**
- Race conditions between concurrent writers
- Silent reordering or duplication when the caller breaks Raft's single-writer rule
- Deadlocks in internal synchronization
- Data interleaving between writers
- Resource cleanup issues during interrupts
- Lock contention under high load

### Corruption Chaos Tests (8 tests)

Tests for resilience against file-level corruption scenarios.

| Test | Description | Validation |
|------|-------------|------------|
| `Random Byte Corruption in WAL` | Flip random byte in second half of WAL file | Torn tail repaired, or corruption reported with file untouched |
| `Zero-Fill Corruption` | Append 4KB of zeros (SSD block failure simulation) | Corruption reported and file preserved |
| `Magic Number Corruption` | Corrupt magic number of 3rd entry to `0xDEADBEEF` | Corruption reported; entries 4-5 not discarded |
| `CRC Bit Flip` | Single bit flip in payload of first entry | CRC mismatch reported; file untouched, instance fenced |
| `Partial Record (Torn Write)` | Append partial 10-byte header (no index/term/payload/CRC) | All 5 valid entries recovered, partial record ignored |
| `Metadata File Corruption` | Flip first byte of `meta.dat` file | Corruption detected on metadata load |
| `Garbage Append After Valid Data` | Append 256 bytes of random garbage after valid entries | Corruption reported and file preserved |
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
| `Max Long Index` | Entry with `index = Long.MAX_VALUE` after compaction through `Long.MAX_VALUE - 1` | Refused on a fresh log; accepted after compaction and preserved across restart via the persisted boundary |
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
| `Double Close` | Call `close()` twice on same instance | No exception thrown (idempotent close); `close()` returns only after the lock is released |
| `Operations After Close` | Attempt append after `close()` | Operation rejected with appropriate exception |
| `Negative Index (Protocol Violation)` | Append entry with `index = -1` | Refused with `INDEX_NOT_CONTIGUOUS`; nothing written |
| `Negative Term (Protocol Violation)` | Append entry with `term = -1` | Refused with `TERM_REGRESSION`; nothing written |
| `Non-Sequential Indices` | Batch with indices [1, 5, 3, 100] (gaps and out-of-order) | Whole batch refused with `INDEX_NOT_CONTIGUOUS`; entry 1 not written either |
| `Duplicate Indices in Batch` | Batch with 3 entries all having `index = 1`; then re-send of an existing index | Both refused with `INDEX_NOT_CONTIGUOUS`; only the one valid append survives |
| `Empty Batch Append` | Append empty `List.of()` followed by real entry | No-op for empty, real entry stored |
| `Truncate to Negative` | `truncateSuffix(-1)` and `truncateSuffix(0)` after writing 2 entries | Both refused with `INVALID_TRUNCATION`; both entries preserved |
| `Truncate Beyond Log` | `truncateSuffix(1000)` on 2-entry log, then `truncateSuffix(3)` | 1000 refused with `INVALID_TRUNCATION`; 3 (exactly at the tail) is a legal no-op |

**What these tests catch:**
- File locking mechanism effectiveness
- Resource cleanup on close
- Idempotent operations
- Storage layer refusing sequences that are not a valid Raft log
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
| `G9: recoveryPreservesValidEntriesBeforeCorruption` | Mid-log corruption reported with the count of clean entries before it; file untouched |
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
| `F5: recoveryWithInterleavedCorruption` | Corruption mid-log is reported, not truncated |

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
| `zeroFilledTailIsReported` | Zero-filled region after valid entries is reported and preserved |
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
| `indexWrapAround` | Index at Long.MAX_VALUE handled correctly after compaction through `Long.MAX_VALUE - 1` |

### Middle-of-the-Log Corruption (5 tests)

**CRITICAL**: Tests for corruption in middle of log (not just tail).

| Test | Description |
|------|-------------|
| `corruptionInMiddleIsReported` | Corruption at entry #5 of 10 is reported with offset and 4 clean entries; file untouched |
| `corruptInstanceIsFenced` | After the report, appends, sync and replay all fail and nothing is written |
| `operatorTruncationAtReportedOffsetRecovers` | Truncating at the reported offset, once the tail is known to be unacknowledged, recovers entries 1-4 |
| `corruptionAtFirstEntryIsReported` | Corruption at entry 1 with a valid entry 2 is reported, not truncated to empty |
| `corruptionInLastRecordIsReported` | Corruption in the last record is reported and preserved |

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
| **Torn writes / power loss** | Structurally incomplete EOF recovery; ambiguous corruption is fenced | G6-G12, F1-F5, WalChaos Corruption |
| **Bit rot / bit flips** | CRC32C checksums | G6-G8, F4, WalChaos CRC Bit Flip |
| **Process crashes** | WAL replay on restart | Recovery Tests, WalChaos Open/Close Cycles |
| **Partial metadata updates** | Atomic rename | G10, WalChaos Metadata Corruption |
| **Disk full** | Pre-flight space check | Disk Space Tests |
| **Silent filesystem corruption** | Read-after-write verification | Verification Tests |
| **Zero-fill persistence trap** | MAGIC != 0x00000000 | NastyEdgeCaseTest, WalChaos Zero-Fill |
| **WAL corruption** | Report, preserve the file, and fence the instance | NastyEdgeCaseTest, FileRaftStorageFencingTest, WalChaos Random Corruption |
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
