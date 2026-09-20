<p align="left">
  <img src="docs/RaftLog%20logo%20design%20with%20tagline.png" alt="RaftLog Logo" width="220"/>
</p>

# RaftLog

The persistent state of a Raft node, as a Java library: current term, vote, and the replicated log.

## Overview

RaftLog is the "persistent state on all servers" box from the Raft paper, and nothing
else. It is not a consensus implementation. There are no elections, no RPCs and no
replication here; you pair it with your own node, which owns the consensus logic and
calls this library to make its state durable.

The API is shaped by Raft's storage contract rather than by a generic log:

- `updateMetadata(currentTerm, votedFor)` persists the term and vote atomically, so a
  node can persist before it grants a vote.
- Log entries carry a term. `truncateSuffix(fromIndex)` exists for the AppendEntries
  conflict rule, and `truncatePrefix(toIndex)` for compaction after a snapshot.
- `AppendPlan` computes the AppendEntries receiver step: find the first term mismatch,
  truncate there, append the rest.
- Replay never truncates a tail it cannot prove was an incomplete write. A node that
  forgets an acknowledged entry can lose it cluster-wide, so ambiguous damage is
  reported and left in place for the operator.

Underneath that contract is a conventional write-ahead log:

- **CRC32C checksums** for data integrity validation
- **Atomic metadata updates** using rename-based persistence
- **Serialized writes** via single-threaded executor for thread safety
- **Efficient replay** that repairs structurally incomplete EOF writes and reports all ambiguous corruption without truncating it
- **Prefix compaction** that reclaims WAL space after caller-owned durable snapshots

### What the library checks

A generic WAL records whatever it is given. This one refuses anything that would not
be a valid Raft log, before writing a byte, with a categorised `WriteRejectedException`:

| Rule | Reason |
|------|--------|
| An append must continue the log at its tail, with contiguous indices from 1 | `INDEX_NOT_CONTIGUOUS` |
| Entry terms must not decrease; a metadata term must not go below the persisted one | `TERM_REGRESSION` |
| A vote cast in a term cannot be changed within that term | `VOTE_CHANGED` |
| A suffix truncation must be at least 1 and not beyond the tail | `INVALID_TRUNCATION` |
| A non-empty log must be replayed before it is written to, and again after any write that failed part way | `LOG_STATE_UNKNOWN` |
| Metadata cannot be updated while `meta.dat` exists but is unreadable | `METADATA_UNREADABLE` |

Replay likewise refuses a log that is not contiguous. A fresh log starts at index 1.
Prefix compaction writes its boundary as the first record of the rewritten WAL, so a
compacted log continues at the boundary plus one across restarts even when nothing was
retained. `FileRaftStorage.compactionBoundary()` reports it, and `AppendPlan.from` takes it
so that entries already covered by the snapshot are skipped. A compacted log that carries no
boundary record has the boundary inferred from its first entry.

`AppendPlan` is as strict as the storage. It throws on any inconsistency in its arguments
rather than producing a plan the storage would refuse later.

The write path and the replay path are held to the same rules: anything the storage
accepts must replay after a restart. A model-based test drives random operation
sequences with restarts against a reference model to check exactly that.

**Format versions.** `APPEND` and `TRUNCATE` records are format 1. The `PREFIX` record
is format 2. An intact record with a format version this build does not know is reported
as `UnsupportedFormatException` rather than as corruption, and the file is left untouched.

The point is loud failure over silent divergence. A node that appends out of order or
regresses its term has a bug, and the storage reports it at the call site rather than
handing back a log that replays into something `AppendPlan` cannot reason about.

## Requirements

- Java 25 or later
- Maven 3.8+

## Maven Coordinates

### Maven

```xml
<dependency>
    <groupId>io.github.mraysmit</groupId>
    <artifactId>raftlog-core</artifactId>
    <version>1.4.0</version>
</dependency>
```

### Gradle (Groovy)

```groovy
implementation 'io.github.mraysmit:raftlog-core:1.4.0'
```

### Gradle (Kotlin)

```kotlin
implementation("io.github.mraysmit:raftlog-core:1.4.0")
```

## Building

```bash
mvn clean install
```

## Running Tests

```bash
mvn test
```

That runs the unit tests of both modules, including the chaos suite. It is for working on
something. To verify a change or a release, run everything:

```bash
java scripts/VerifyAll.java
```

It runs every test under the coverage gate, every shipped program from the packaged jar, the
model soak and the mutation gate, on this platform and again on Linux as an unprivileged user.
It needs Docker and takes well over an hour. See `docs/RAFTLOG_TEST_DOCUMENTATION.md`.

## Command style for captured test logs

Use the qraft-style command format to capture Maven output to both console and a timestamped log file:

```powershell
mvn -B "-Dstyle.color=never" test 2>&1 | Tee-Object ".\logs\raftlog-tests-$(Get-Date -Format 'yyyy-MM-dd_HH-mm-ss').log"
```

If you need a single-module targeted run:

```powershell
mvn -B "-Dstyle.color=never" test -pl raftlog-core -Dtest=ConfigResolverTest 2>&1 | Tee-Object ".\logs\raftlog-documents-test-$(Get-Date -Format 'yyyy-MM-dd_HH-mm-ss').log"
```

Notes:
- `-B` keeps Maven output machine-friendly.
- `-Dstyle.color=never` avoids ANSI control codes in captured logs.
- `2>&1` captures both stdout and stderr.
- `Tee-Object` keeps logs visible in the terminal while also writing to a file.
- Create `.\logs` first if it does not exist: `New-Item -ItemType Directory -Force .\logs`.

## Quick Start

```java
import dev.mars.raftlog.storage.*;

// Option 1: Use configuration (recommended)
RaftStorageConfig config = RaftStorageConfig.builder()
    .dataDir("/var/data/raft-wal")
    .verifyWrites(true)
    .build();
RaftStorage storage = new FileRaftStorage(config);
storage.open().join();

// Option 2: Load config from properties/env/system props
RaftStorage storage = new FileRaftStorage();  // Uses RaftStorageConfig.load()
storage.open().join();  // Opens using config.dataDir()

// Append log entries
var entries = List.of(
    new RaftStorage.LogEntryData(1, 1, "SET key1 value1".getBytes()),
    new RaftStorage.LogEntryData(2, 1, "SET key2 value2".getBytes())
);
storage.appendEntries(entries).join();
storage.sync().join();

// Replay log on restart
List<RaftStorage.LogEntryData> replayed = storage.replayLog().join();
for (var entry : replayed) {
    System.out.println("Index: " + entry.index() + ", Term: " + entry.term());
}

// Close when done. close() blocks until the channel and directory lock are
// released; closeAsync() returns a future for callers that must not block.
storage.close();
```

## Prefix compaction

After durably saving a covering application snapshot, call
`storage.truncatePrefix(lastIncludedIndex).join()`. The inclusive prefix is removed
and the retained WAL is forced and atomically replaced before completion. No extra
`sync()` is needed for compaction. Application snapshots and their boundary metadata
remain the caller's responsibility. Raw append/replay semantics are unchanged.

See the main design document for failure recovery, Windows directory-durability limits, compatibility, and memory/disk costs.

## Configuration

RaftLog uses `RaftStorageConfig` for configuration with the following resolution priority:

1. **Programmatic** (builder pattern)
2. **System property** (`-Draftlog.dataDir=/path`)
3. **Environment variable** (`RAFTLOG_DATA_DIR=/path`)
4. **Properties file** (`raftlog.properties` on the classpath, then in the working directory)
5. **Default value**

Configuration is never guessed. A value that cannot be parsed (`32MB`, `ture`, `1.5`) throws an
`IllegalArgumentException` naming the setting, the source it came from and the offending value; it
is not replaced by the default. That holds for every source that supplies the setting, including
one that a higher-priority source overrides, so a bad value cannot wait unnoticed for the day the
override is removed. Booleans are `true` or `false` in any letter case, and nothing else. A
properties file that exists but cannot be read is an error; a missing one is not. A blank value
means the setting is absent from that source, and surrounding whitespace is ignored.

### Configuration Properties

| Property | System Property | Env Variable | Default | Description |
|----------|-----------------|--------------|---------|-------------|
| `dataDir` | `raftlog.dataDir` | `RAFTLOG_DATA_DIR` | `~/.raftlog/data` | Storage directory |
| `syncEnabled` | `raftlog.syncEnabled` | `RAFTLOG_SYNC_ENABLED` | `true` | Must remain `true`; public configuration rejects `false` |
| `verifyWrites` | `raftlog.verifyWrites` | `RAFTLOG_VERIFY_WRITES` | `false` | Read-after-write verification |
| `minFreeSpaceMb` | `raftlog.minFreeSpaceMb` | `RAFTLOG_MIN_FREE_SPACE_MB` | `64` | Minimum free disk space (MB). Must not be negative |
| `maxPayloadSizeMb` | `raftlog.maxPayloadSizeMb` | `RAFTLOG_MAX_PAYLOAD_SIZE_MB` | `16` | Maximum payload size (MB) for new writes, 1 to 2047. Lowering it never makes existing entries unreadable |

### Example Properties File

```properties
# raftlog.properties
raftlog.dataDir=/var/lib/raftlog
raftlog.syncEnabled=true
raftlog.verifyWrites=false
raftlog.minFreeSpaceMb=64
raftlog.maxPayloadSizeMb=16
```

## Architecture

RaftLog follows a **Prepare → Persist → Apply** pattern:

1. **Prepare**: Calculate the `AppendPlan` determining which entries to append/truncate
2. **Persist**: Write entries to the WAL with fsync barrier
3. **Apply**: Update in-memory state only after durability is confirmed

### WAL Record Format

```
┌─────────┬─────────┬──────┬───────┬──────┬─────────────┬─────────┬─────────┐
│ MAGIC   │ VERSION │ TYPE │ INDEX │ TERM │ PAYLOAD_LEN │ PAYLOAD │ CRC32C  │
│ 4 bytes │ 2 bytes │ 1 b  │ 8 b   │ 8 b  │ 4 bytes     │ N bytes │ 4 bytes │
└─────────┴─────────┴──────┴───────┴──────┴─────────────┴─────────┴─────────┘
```

### Binary Format Constants

These values are fixed and define the on-disk format:

| Constant | Value | Description |
|----------|-------|-------------|
| `MAGIC` | `0x52414654` | "RAFT" in ASCII - file format identifier |
| `VERSION` | `1` | Record format version |
| `TYPE_TRUNCATE` | `1` | Record type: truncate suffix |
| `TYPE_APPEND` | `2` | Record type: append entry |
| `TYPE_PREFIX` | `3` | Record type: prefix compaction boundary; first record of a compacted WAL, INDEX = inclusive boundary |
| `HEADER_SIZE` | `27` | Header size in bytes |
| `CRC_SIZE` | `4` | CRC32C checksum size |

### Storage Files

| File | Purpose |
|------|---------|
| `raft.log` | Append-only WAL of APPEND and TRUNCATE records, led by a PREFIX record once compacted |
| `meta.dat` | Persistent metadata (currentTerm, votedFor) with atomic updates |
| `meta.dat.tmp` | Temporary file for atomic metadata rename |
| `raft.lock` | Exclusive lock file to prevent concurrent access |

## Project Structure

```
raftlog/
├── docs/                    # Design documents
├── raftlog-core/           # Core WAL implementation
│   └── src/
│       ├── main/java/dev/mars/raftlog/
│       │   └── storage/    # RaftStorage, FileRaftStorage, RaftStorageConfig, AppendPlan
│       └── test/java/      # Unit tests (154 tests)
├── raftlog-demo/           # Demo applications and chaos scenarios
│   └── src/main/java/      # Basic WAL and key/value replay examples
├── LICENSE                 # Apache License 2.0
├── NOTICE                  # Third-party attributions
└── OPEN_SOURCE_USAGE.md    # Open source compliance guide
```

## Running the Demo

```bash
# Build the project
mvn clean package -DskipTests

# Run with default config (~/.raftlog/data)
java -jar raftlog-demo/target/raftlog-demo-1.4.0.jar

# Run with custom data directory
java -Draftlog.dataDir=/tmp/wal-demo -jar raftlog-demo/target/raftlog-demo-1.4.0.jar
```

Run the separate key/value replay example:

```bash
java -cp raftlog-demo/target/raftlog-demo-1.4.0.jar \
  dev.mars.raftlog.demo.KeyValueExample /tmp/raftlog-key-values
```

## Documentation

See [RAFTLOG_RAFT_WAL_DESIGN.md](docs/RAFTLOG_RAFT_WAL_DESIGN.md) for the complete design specification.

## License

Copyright 2026 Mark Andrew Ray-Smith

Licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE) for details.
