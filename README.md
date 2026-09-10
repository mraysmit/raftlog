<p align="left">
  <img src="docs/RaftLog%20logo%20design%20with%20tagline.png" alt="RaftLog Logo" width="220"/>
</p>

# RaftLog

A minimal, high-performance Write-Ahead Log (WAL) implementation for Raft consensus in Java.

## Overview

RaftLog provides the durability core for Raft-based distributed systems. It implements a write-ahead log with:

- **CRC32C checksums** for data integrity validation
- **Atomic metadata updates** using rename-based persistence
- **Serialized writes** via single-threaded executor for thread safety
- **Efficient replay** that repairs structurally incomplete EOF writes and reports all ambiguous corruption without truncating it
- **Prefix compaction** that reclaims WAL space after caller-owned durable snapshots

## Requirements

- Java 21 or later
- Maven 3.8+

## Maven Coordinates

### Maven

```xml
<dependency>
    <groupId>io.github.mraysmit</groupId>
    <artifactId>raftlog-core</artifactId>
    <version>1.3.0</version>
</dependency>
```

### Gradle (Groovy)

```groovy
implementation 'io.github.mraysmit:raftlog-core:1.3.0'
```

### Gradle (Kotlin)

```kotlin
implementation("io.github.mraysmit:raftlog-core:1.3.0")
```

## Building

```bash
mvn clean install
```

## Running Tests

```bash
mvn test
```

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

// Close when done
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
4. **Properties file** (`raftlog.properties` in classpath)
5. **Default value**

### Configuration Properties

| Property | System Property | Env Variable | Default | Description |
|----------|-----------------|--------------|---------|-------------|
| `dataDir` | `raftlog.dataDir` | `RAFTLOG_DATA_DIR` | `~/.raftlog/data` | Storage directory |
| `syncEnabled` | `raftlog.syncEnabled` | `RAFTLOG_SYNC_ENABLED` | `true` | Must remain `true`; public configuration rejects `false` |
| `verifyWrites` | `raftlog.verifyWrites` | `RAFTLOG_VERIFY_WRITES` | `false` | Read-after-write verification |
| `minFreeSpaceMb` | `raftlog.minFreeSpaceMb` | `RAFTLOG_MIN_FREE_SPACE_MB` | `64` | Minimum free disk space (MB) |
| `maxPayloadSizeMb` | `raftlog.maxPayloadSizeMb` | `RAFTLOG_MAX_PAYLOAD_SIZE_MB` | `16` | Maximum payload size (MB) |

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
| `HEADER_SIZE` | `27` | Header size in bytes |
| `CRC_SIZE` | `4` | CRC32C checksum size |

### Storage Files

| File | Purpose |
|------|---------|
| `raft.log` | Append-only WAL containing TRUNCATE and APPEND records |
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
java -jar raftlog-demo/target/raftlog-demo-1.3.0.jar

# Run with custom data directory
java -Draftlog.dataDir=/tmp/wal-demo -jar raftlog-demo/target/raftlog-demo-1.3.0.jar
```

Run the separate key/value replay example:

```bash
java -cp raftlog-demo/target/raftlog-demo-1.3.0.jar \
  dev.mars.raftlog.demo.KeyValueExample /tmp/raftlog-key-values
```

## Documentation

See [RAFTLOG_RAFT_WAL_DESIGN.md](docs/RAFTLOG_RAFT_WAL_DESIGN.md) for the complete design specification.

## License

Copyright 2026 Mark Andrew Ray-Smith

Licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE) for details.
