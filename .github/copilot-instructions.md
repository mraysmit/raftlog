# RaftLog - AI Agent Instructions

## Project Overview
RaftLog is a minimal, crash-safe Write-Ahead Log (WAL) for Raft consensus in Java 21. It implements **only** append, truncate (suffix deletion), and sequential replay—it is **not** a general-purpose storage engine.

**Integration context:** Designed for Vert.x 5.x Raft implementations. WAL operations are blocking and should run on a dedicated `WorkerExecutor` while Raft logic remains on the event loop.

## Architecture: Prepare → Persist → Apply Pattern
All log modifications follow this critical 3-phase pattern to ensure Raft safety:

```java
// 1. Prepare: Calculate what changes are needed (pure function, no side effects)
AppendPlan plan = AppendPlan.from(startIndex, incomingEntries, memoryLog);

// 2. Persist: Write to WAL with durability barrier
storage.truncateSuffix(plan.truncateFromIndex())   // if needed
    .thenCompose(v -> storage.appendEntries(plan.entriesToAppend()))
    .thenCompose(v -> storage.sync())              // CRITICAL: fsync barrier

// 3. Apply: Update in-memory state ONLY after sync succeeds
    .thenAccept(v -> plan.applyTo(memoryLog));
```

**Never modify in-memory state before `sync()` completes.** This is the core Raft "persist-before-response" rule.

## Key Components

| Class | Purpose |
|-------|---------|
| `RaftStorage` | Interface defining the storage contract (allows swapping WAL for RocksDB) |
| `FileRaftStorage` | File-based WAL implementation with CRC32C checksums |
| `RaftStorageConfig` | Configuration with system property/env/file resolution |
| `AppendPlan` | Pure function that calculates truncate/append operations |

## WAL Binary Format
```
MAGIC(4) | VERSION(2) | TYPE(1) | INDEX(8) | TERM(8) | PAYLOAD_LEN(4) | PAYLOAD(N) | CRC32C(4)
```
- `MAGIC = 0x52414654` ("RAFT")
- `TYPE_TRUNCATE = 1`, `TYPE_APPEND = 2`
- All writes use CRC32C for integrity validation

## Storage Files (in `dataDir`)
- `raft.log` — Append-only WAL (TRUNCATE + APPEND records)
- `meta.dat` — Persistent metadata (currentTerm, votedFor) with atomic rename
- `raft.lock` — Exclusive lock preventing concurrent access

## Build & Test Commands
```bash
mvn clean install                       # Full build with tests
mvn test                                # Run all 192 tests
mvn test -Dtest=FileRaftStorageTest     # Run specific test class
mvn test -Dtest=AppendPlanTest          # Pure function tests (fast)
mvn package -DskipTests                 # Build without tests
```

### Running the Demo
```bash
mvn package -pl raftlog-demo -am -DskipTests

# Default config (~/.raftlog/data)
java -jar raftlog-demo/target/raftlog-demo-1.0-SNAPSHOT.jar

# Custom data directory
java -jar raftlog-demo/target/raftlog-demo-1.0-SNAPSHOT.jar /tmp/wal-demo

# With system properties
java -Draftlog.dataDir=/tmp/wal -Draftlog.verifyWrites=true -jar raftlog-demo/target/raftlog-demo-1.0-SNAPSHOT.jar
```

### Running Chaos Tests (interactive stress testing)
```bash
java -cp raftlog-demo/target/raftlog-demo-1.0-SNAPSHOT.jar dev.mars.raftlog.demo.WalChaos
java -cp raftlog-demo/target/raftlog-demo-1.0-SNAPSHOT.jar dev.mars.raftlog.demo.WalChaos concurrent
java -cp raftlog-demo/target/raftlog-demo-1.0-SNAPSHOT.jar dev.mars.raftlog.demo.WalChaos corruption
```

## Test Class Reference

| Test Class | Purpose | When to Use |
|------------|---------|-------------|
| `AppendPlanTest` | Pure function tests (15 tests) | Validating append/truncate calculation logic |
| `FileRaftStorageTest` | Core WAL functionality (18 tests) | Basic append, replay, metadata, recovery |
| `FileRaftStorageAdversarialTest` | Break-the-system tests (64 tests) | Corruption, boundaries, stress testing |
| `ProtectionGuaranteeTest` | Thread safety & crash consistency (24 tests) | Verifying safety guarantees |
| `EnhancedProtectionTest` | File locking, disk space, verification (16 tests) | Testing protection mechanisms |
| `NastyEdgeCaseTest` | JVM/OS/Hardware failure modes (17 tests) | Subtle edge cases (zero-fill, overflow) |
| `WalChaos` | Interactive chaos suite (38 tests) | Manual stress testing beyond JUnit |

## Testing Conventions
- Tests use `@TempDir` for isolated test directories
- Use `new FileRaftStorage(true)` for tests (sync enabled)
- Always call `storage.sync().get()` after writes in tests
- Use `TimeUnit.SECONDS` timeouts (typically 5s) for `CompletableFuture.get()`

Example test pattern:
```java
@TempDir Path tempDir;
FileRaftStorage storage = new FileRaftStorage(true);
storage.open(tempDir).get(5, TimeUnit.SECONDS);
storage.appendEntries(entries).get(5, TimeUnit.SECONDS);
storage.sync().get(5, TimeUnit.SECONDS);  // REQUIRED before verification
List<LogEntryData> replayed = storage.replayLog().get(5, TimeUnit.SECONDS);
```

## Configuration Priority (highest to lowest)
1. Programmatic via `RaftStorageConfig.builder()`
2. System property: `-Draftlog.dataDir=/path`
3. Environment variable: `RAFTLOG_DATA_DIR=/path`
4. Properties file: `raftlog.properties` on classpath
5. Defaults

## Thread Safety Model
- All WAL operations run on a **single-threaded executor** (`walExecutor`)
- **Never increase pool size** or add parallel write paths
- `FileChannel` position is always consistent due to serialization

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

### ❌ Never weaken durability for "performance"
```java
// WRONG: Batching syncs across unrelated operations
// WRONG: Using fdatasync instead of fsync
// WRONG: Periodic sync instead of per-operation
```

### ✅ Correct patterns
```java
// Use AppendPlan for atomic truncate+append
AppendPlan plan = AppendPlan.from(prevLogIndex + 1, entries, log);
storage.truncateSuffix(plan.truncateFromIndex())
    .thenCompose(v -> storage.appendEntries(plan.entriesToAppend()))
    .thenCompose(v -> storage.sync())
    .thenAccept(v -> plan.applyTo(log));  // Memory update AFTER sync
```

## Important Constraints
- **No random disk reads** during runtime (WAL is write-only, log lives in memory)
- **No log segmentation** or compaction (single append-only file)
- **No snapshots** (out of scope)
- **Storage layer doesn't validate indices/terms** — that's the protocol layer's job
- Payload max size: 16 MB (configurable via `maxPayloadSizeMb`)
- Minimum free disk space: 64 MB before writes fail

## Error Handling
- `StorageException` wraps all I/O failures
- Corrupt/partial records at WAL tail are truncated during replay
- CRC mismatches stop replay at the corruption point
- Middle-of-log corruption returns only entries before the corruption point

## Package Structure
```
raftlog-core/src/main/java/dev/mars/raftlog/storage/
├── RaftStorage.java         # Interface (allows WAL or RocksDB backend)
├── FileRaftStorage.java     # WAL implementation (~860 lines)
├── RaftStorageConfig.java   # Configuration with env/sysprop/file resolution
├── AppendPlan.java          # Pure append/truncate calculator
├── StorageException.java    # Unchecked exception wrapper
└── package-info.java

raftlog-demo/src/main/java/dev/mars/raftlog/demo/
├── WalDemo.java             # Basic usage demonstration
└── WalChaos.java            # Interactive chaos testing suite
```
