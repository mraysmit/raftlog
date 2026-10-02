# RaftLog - AI Agent Instructions

## Project Overview
RaftLog is a minimal, crash-safe Write-Ahead Log (WAL) for Raft consensus in Java 25. It implements append, suffix truncation, prefix compaction and sequential replay, plus durable term and vote metadata. It is **not** a general-purpose storage engine.

**Integration context:** Designed for Vert.x 5.x Raft implementations. Every operation returns a `CompletableFuture` and runs on the storage's own single WAL thread, so Raft logic can stay on the event loop.

## Architecture: Prepare → Persist → Apply Pattern
All log modifications follow this 3-phase pattern to ensure Raft safety:

```java
// 1. Prepare: calculate what changes are needed (pure function, no side effects)
AppendPlan plan = AppendPlan.from(startIndex, incomingEntries, memoryLog, compactionBoundary);

// 2. Persist: write to the WAL, then the durability barrier
CompletableFuture<Void> truncated = plan.requiresTruncation()
        ? storage.truncateSuffix(plan.truncateFromIndex())
        : CompletableFuture.completedFuture(null);
truncated.thenCompose(v -> storage.appendEntries(plan.entriesToAppend()))
    .thenCompose(v -> storage.sync())              // CRITICAL: fsync barrier

// 3. Apply: update in-memory state ONLY after sync succeeds
    .thenAccept(v -> plan.applyTo(memoryLog));
```

**Never modify in-memory state before `sync()` completes.** This is the core Raft "persist-before-response" rule.

`compactionBoundary` is 0 for a log that was never compacted, and the three-argument `AppendPlan.from` assumes that. After `truncatePrefix`, read it from `FileRaftStorage.compactionBoundary()`. `truncateFromIndex()` is `null` when no truncation is needed.

## Key Components

| Class | Purpose |
|-------|---------|
| `RaftStorage` | Interface defining the storage contract (allows swapping the WAL for another backend) |
| `FileRaftStorage` | File-based WAL with CRC32C checksums, invariant checks, fencing and prefix compaction |
| `RaftStorageConfig` | Configuration resolved from builder, system properties, environment and properties file |
| `AppendPlan` | Pure, strict calculator of the truncate/append delta for an AppendEntries request |
| `WriteRejection`, `WriteRejectionReason` | Stable categories for understood refusals |
| `CompactionIo` | Package-private filesystem seam; tests subclass it to inject real I/O failures |

## WAL Binary Format
```
MAGIC(4) | VERSION(2) | TYPE(1) | INDEX(8) | TERM(8) | PAYLOAD_LEN(4) | PAYLOAD(N) | CRC32C(4)
```
- `MAGIC = 0x52414654` ("RAFT")
- `TYPE_TRUNCATE = 1` and `TYPE_APPEND = 2`, written as format version 1
- `TYPE_PREFIX = 3`, written as format version 2: the compaction boundary, valid only as the first record
- A record with a valid CRC and a higher version fails replay with `UnsupportedFormatException`
- A format change needs new golden fixtures under `raftlog-core/src/test/resources/golden`

## Storage Files (in `dataDir`)
- `raft.log` — the WAL: `APPEND` and `TRUNCATE` records, with a leading `PREFIX` record after compaction
- `raft.log.tmp` — unpublished compaction output; discarded on open when `raft.log` exists
- `meta.dat` — currentTerm and votedFor, replaced atomically through `meta.dat.tmp`
- `raft.lock` — exclusive lock preventing concurrent access

## Storage Contract
- **Replay before writing.** A non-empty log must be replayed with `replayLog()` after open. Until then writes are refused with `LOG_STATE_UNKNOWN`. A write that fails part way also leaves the tail unknown until the next replay.
- **The storage validates Raft invariants.** Appends must continue the log with contiguous indices from the tail and non-decreasing terms. Metadata terms must not go backwards and a vote cannot change within a term. Violations fail the future with `WriteRejectedException`, carrying a `WriteRejectionReason`, and write nothing.
- **Durability barriers.** Appends and suffix truncations are durable only after `sync()`. `updateMetadata` and `truncatePrefix` are durable when their futures complete.
- **Prefix compaction.** `truncatePrefix(toIndex)` rewrites the WAL without entries up to `toIndex`. The caller must durably publish a covering snapshot first. Snapshots themselves are out of scope.
- **Fencing.** After a failed force, a failed compaction publication, or corruption found by replay, the instance rejects every further operation. Close it and open a fresh instance.

## Error Handling
- `FileRaftStorage.StorageException` is the unchecked base type for storage failures
- Replay truncates only a structurally incomplete fragment at the end of the file whose header describes the operation that could validly come next; that is a torn write
- Anything else that cannot be decoded, including a complete record with a bad CRC, fails replay with `CorruptLogException`, leaves the file unchanged and fences the instance; such a node is restored from its peers
- A null argument or element fails the returned future with `IllegalArgumentException`; nothing is thrown synchronously

## Build & Test Commands
```bash
mvn -Pcoverage clean verify             # Full build, all tests, 99% coverage gate on the storage package
mvn test                                # Run all tests
mvn test -Dtest=FileRaftStorageTest     # Run a specific test class
mvn test -Dtest=AppendPlanTest          # Pure function tests (fast)
mvn package -DskipTests                 # Build without tests
```

Three tests need POSIX permissions and are skipped on Windows. The release procedure, including the Linux run, is in `docs/RAFTLOG_TEST_DOCUMENTATION.md`.

### Running the Demo
```bash
mvn package -pl raftlog-demo -am -DskipTests

# Default config (~/.raftlog/data)
java -jar raftlog-demo/target/raftlog-demo-1.4.0.jar

# Custom data directory
java -jar raftlog-demo/target/raftlog-demo-1.4.0.jar /tmp/wal-demo

# Key/value replay example
java -cp raftlog-demo/target/raftlog-demo-1.4.0.jar dev.mars.raftlog.demo.KeyValueExample /tmp/key-values
```

### Running Chaos Scenarios
The build runs every scenario through `WalChaosTest`. To run them by hand:
```bash
java -cp raftlog-demo/target/raftlog-demo-1.4.0.jar dev.mars.raftlog.demo.WalChaos
java -cp raftlog-demo/target/raftlog-demo-1.4.0.jar dev.mars.raftlog.demo.WalChaos concurrent
```
Categories: `concurrent`, `corruption`, `boundary`, `stress`, `nasty`, `all`.

## Test Class Reference

| Test Class | Purpose |
|------------|---------|
| `AppendPlanTest`, `AppendPlanStrictnessTest` | Plan calculation, and refusal of every inconsistent argument |
| `FileRaftStorageTest` | Core append, replay, metadata, construction and recovery |
| `FileRaftStorageRecoveryContractTest` | Replay, restart and lifecycle contract |
| `FileRaftStorageInvariantEdgeCaseTest` | Raft invariants; write path and replay path checked against a model |
| `FileRaftStorageFailurePathTest` | Every refusal and failure path, using `CompactionIo` seams |
| `FileRaftStorageFencingTest` | Fencing after failed forces; torn tail versus corruption |
| `FileRaftStoragePrefixCompactionTest`, `FileRaftStorageCompactionFailureTest` | Prefix compaction and its failure modes |
| `GoldenFileCompatibilityTest` | Binary fixtures for each WAL format |
| `FileRaftStorageDiagnosticLoggingTest`, `FileRaftStorageLoggingTest`, `LogSanitizationTest` | Every decision is logged, bounded and single-line |
| `ConfigResolverTest`, `RaftStorageConfigSourcesTest` | Configuration sources, precedence and strict parsing |
| `FileRaftStorageAdversarialTest`, `ProtectionGuaranteeTest`, `EnhancedProtectionTest`, `NastyEdgeCaseTest` | Corruption, boundaries, concurrency, locking, disk space |
| `FileRaftStorageLinuxTest` | POSIX permissions and symbolic links; skipped on other platforms |
| `WalChaosTest` (demo module) | Runs all chaos scenarios in the build |

## Testing Conventions
- Tests use `@TempDir` for isolated test directories
- Build storages with `new FileRaftStorage(RaftStorageConfig.builder().dataDir(dir).build())`; the boolean constructors are deprecated for removal
- A refusal test must also prove the disk is unchanged: wrap the call in `DurableState.expectUnchanged(dir)`
- Inject I/O failures by subclassing `CompactionIo`, not with a mocking framework
- Name test classes for the behaviour they pin, never for coverage
- A test class that changes JVM-wide state (system properties, configuration seams, logger levels) carries `@Isolated`
- Every log statement in the storage package carries an `event` key; a test enforces this
- Use `TimeUnit.SECONDS` timeouts (typically 5s) for `CompletableFuture.get()`

Example test pattern:
```java
@TempDir Path tempDir;
FileRaftStorage storage = new FileRaftStorage(RaftStorageConfig.builder().dataDir(tempDir).build());
storage.open().get(5, TimeUnit.SECONDS);
storage.appendEntries(entries).get(5, TimeUnit.SECONDS);
storage.sync().get(5, TimeUnit.SECONDS);  // REQUIRED before acknowledging
List<LogEntryData> replayed = storage.replayLog().get(5, TimeUnit.SECONDS);
storage.close();
```

## Configuration Priority (highest to lowest)
1. Programmatic via `RaftStorageConfig.builder()`
2. System property: `-Draftlog.dataDir=/path`
3. Environment variable: `RAFTLOG_DATA_DIR=/path`
4. Properties file: `raftlog.properties` on the classpath, else in the working directory
5. Defaults

A value that is present but cannot be parsed is an error from every source, never a fallback to the default. `syncEnabled=false` is rejected from every source.

## Thread Safety Model
- All WAL operations run on a **single-threaded executor** (`walExecutor`)
- **Never increase pool size** or add parallel write paths
- Tail state (`lastIndex`, term runs, compaction boundary) is owned by that thread; never read it from a caller thread

## Anti-Patterns to Avoid

### ❌ Never do this
```java
// WRONG: Modifying memory before sync completes
storage.appendEntries(entries);
memoryLog.addAll(entries);  // BUG: not durable yet!
storage.sync();
```

### ❌ Never skip the sync barrier
```java
// WRONG: Responding to RPC before durability
storage.appendEntries(entries);
return AppendEntriesResponse.success();  // BUG: data may be lost on crash!
```

### ❌ Never weaken durability or recovery
```java
// WRONG: Retrying sync() after it failed; the instance is fenced for a reason
// WRONG: Using fdatasync instead of fsync, or periodic sync instead of per-request
// WRONG: Repairing a corrupt WAL by truncation; only a torn EOF fragment may be truncated
// WRONG: Using the configured payload limit to decide what replay may read
```

## Important Constraints
- **No random disk reads** during normal operation; the log lives in the caller's memory and the WAL is read only by replay and compaction
- **No log segmentation**: one WAL file, rewritten by prefix compaction
- **No snapshots**: the caller owns them, with their index and term
- Payload max size: 16 MB (configurable via `maxPayloadSizeMb`, at most 2047)
- Minimum free disk space: 64 MB (`minFreeSpaceMb`), checked at open, before large appends and before compaction

## Package Structure
```
raftlog-core/src/main/java/dev/mars/raftlog/storage/
├── RaftStorage.java           # Interface, LogEntryData, PersistentMeta
├── FileRaftStorage.java       # WAL implementation and its exception types
├── CompactionIo.java          # Package-private filesystem seam
├── RaftStorageConfig.java     # Configuration with builder/sysprop/env/file resolution
├── AppendPlan.java            # Pure append/truncate calculator
├── WriteRejection.java        # Implemented by understood write refusals
├── WriteRejectionReason.java  # Stable refusal categories
└── package-info.java

raftlog-demo/src/main/java/dev/mars/raftlog/demo/
├── WalDemo.java               # Basic usage demonstration
├── KeyValueExample.java       # Key/value replay example
└── WalChaos.java              # Chaos scenarios, also run by the build
```
