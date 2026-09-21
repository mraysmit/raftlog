# RaftLog Demo

`raftlog-demo` contains runnable examples and the standalone chaos suite for
RaftLog. It produces an executable, dependency-inclusive JAR whose default main
class is `dev.mars.raftlog.demo.WalDemo`.

The examples write real RaftLog data. Use a separate data directory for each
example; `WalDemo` and `KeyValueExample` store different payload formats and are
not intended to share a WAL.

## Requirements

- Java 25 or later
- Maven 3.8 or later when building from source

Run all commands below from the repository root.

## Build

```bash
mvn package -pl raftlog-demo -am -DskipTests
```

The executable JAR is written to
`raftlog-demo/target/raftlog-demo-1.4.0.jar`.

## WAL walkthrough

`WalDemo` demonstrates the storage lifecycle: it opens the WAL, loads and
updates the persistent term and vote, replays existing entries, appends two new
entries, performs a durability barrier, and verifies the appended entries by
replaying them.

The central use case looks like this:

```java
RaftStorageConfig config = RaftStorageConfig.builder()
        .dataDir("./run-data/wal-demo")
        .build();

try (FileRaftStorage storage = new FileRaftStorage(config)) {
    storage.open().join();

    var metadata = storage.loadMetadata().join();
    long nextTerm = metadata.currentTerm() + 1;
    storage.updateMetadata(nextTerm, Optional.of("node-1")).join();

    List<RaftStorage.LogEntryData> existing = storage.replayLog().join();
    long nextIndex = existing.isEmpty()
            ? 1
            : existing.get(existing.size() - 1).index() + 1;

    storage.appendEntries(List.of(
            new RaftStorage.LogEntryData(
                    nextIndex, nextTerm, "SET greeting hello".getBytes(UTF_8))))
            .join();
    storage.sync().join();
}
```

This is a shortened version of the demo. It assumes imports for
`FileRaftStorage`, `RaftStorage`, `RaftStorageConfig`, `List`, `Optional`, and
the static `StandardCharsets.UTF_8` constant.

```bash
# Use RaftLog's default data directory (~/.raftlog/data)
java -jar raftlog-demo/target/raftlog-demo-1.4.0.jar

# Pass a dedicated data directory as the first argument
java -jar raftlog-demo/target/raftlog-demo-1.4.0.jar ./run-data/wal-demo
```

Run the second command again with the same directory to see restart recovery in
action. Each run increments the persisted term and appends two entries.

When no command-line directory is supplied, `WalDemo` loads the normal RaftLog
configuration sources. For example:

```bash
java -Draftlog.dataDir=./run-data/wal-demo \
     -Draftlog.verifyWrites=true \
     -jar raftlog-demo/target/raftlog-demo-1.4.0.jar
```

The first command-line argument, when present, selects the data directory and
takes precedence over configuration loaded from system properties, environment
variables, or properties files. See the root [configuration
documentation](../README.md#configuration) for all settings and their normal
resolution order.

## Key/value replay example

`KeyValueExample` shows how an application can encode its own payload format. It
writes length-prefixed UTF-8 key/value pairs, replays the complete log, and
reconstructs a last-write-wins map. The key/value layer is example code, not an
API supplied by `raftlog-core`.

A minimal application-specific encoding and replay loop is:

```java
record KeyValue(String key, String value) {}

static byte[] encode(KeyValue item) {
    byte[] key = item.key().getBytes(UTF_8);
    byte[] value = item.value().getBytes(UTF_8);
    return ByteBuffer.allocate(8 + key.length + value.length)
            .putInt(key.length).put(key)
            .putInt(value.length).put(value)
            .array();
}

try (FileRaftStorage storage = new FileRaftStorage(
        RaftStorageConfig.builder().dataDir("./run-data/key-values").build())) {
    storage.open().join();
    List<RaftStorage.LogEntryData> existing = storage.replayLog().join();
    long nextIndex = existing.isEmpty()
            ? 1
            : existing.get(existing.size() - 1).index() + 1;

    storage.appendEntries(List.of(new RaftStorage.LogEntryData(
            nextIndex, 1, encode(new KeyValue("ui.theme", "dark")))))
            .join();
    storage.sync().join();

    List<RaftStorage.LogEntryData> recovered = storage.replayLog().join();
    // Decode each recovered payload and apply it to the application's map.
}
```

The full example also validates payload lengths and UTF-8, decodes every
record, and replaces earlier map values when a key appears again. The snippet
assumes the same storage imports as the WAL walkthrough, plus `ByteBuffer` and
the static `StandardCharsets.UTF_8` constant.

```bash
java -cp raftlog-demo/target/raftlog-demo-1.4.0.jar \
  dev.mars.raftlog.demo.KeyValueExample ./run-data/key-values
```

If the directory argument is omitted, this example uses
`target/key-value-example-data`. Run it twice against the same directory to see
the existing records replayed before the next batch is appended.

Unlike `WalDemo`, `KeyValueExample` builds its configuration directly from its
optional directory argument. It does not load `raftlog.properties`,
`RAFTLOG_DATA_DIR`, or `raftlog.dataDir`.

## Chaos suite

`WalChaos` exercises the implementation with concurrent writers, deliberate
corruption, partial writes, boundary conditions, stress cases, and other hostile
conditions. It creates a fresh temporary directory for every invocation and
removes that directory before returning.

The suite can also be called from Java, which is how its JUnit test exercises
the scenarios:

```java
WalChaos.Summary summary = WalChaos.run("corruption");
if (summary.failed() != 0) {
    throw new IllegalStateException(
            "Chaos failures: " + summary.failed() + " of "
                    + (summary.passed() + summary.failed()));
}
```

Use `"all"` to run the complete suite. `WalChaos.run` reports the result rather
than terminating the JVM; the command-line `main` method converts that result
to process exit status `0` or `1`.

```bash
# Run all 38 scenarios
java -cp raftlog-demo/target/raftlog-demo-1.4.0.jar \
  dev.mars.raftlog.demo.WalChaos

# Run one category
java -cp raftlog-demo/target/raftlog-demo-1.4.0.jar \
  dev.mars.raftlog.demo.WalChaos concurrent
```

The available categories are:

| Category | Focus |
|----------|-------|
| `concurrent` | Concurrent writes, metadata updates, replay, and lifecycle operations |
| `corruption` | Corrupt, truncated, or partially written WAL data |
| `boundary` | Empty, large, Unicode, and numeric boundary cases |
| `stress` | Repeated and high-volume operations |
| `nasty` | Hostile filesystem and lifecycle edge cases |
| `all` | Every category; this is the default |

Category names are case-insensitive. The process exits with status `0` when all
selected scenarios pass and status `1` when a scenario fails or the category is
unknown. The same scenarios also run as part of the Maven test suite through
`WalChaosTest`.

The chaos suite intentionally damages files only inside the temporary directory
it creates. It does not accept a user-supplied data directory.

## Logging

All programs log to the console and to rotating text and JSON files. By default,
the files are created under `./logs` and INFO messages are enabled.

Set these environment variables before starting a program to change that
behavior:

| Variable | Default | Purpose |
|----------|---------|---------|
| `RAFTLOG_LOG_LEVEL` | `INFO` | Log level for `dev.mars.raftlog`; use `DEBUG` for entry and scenario details |
| `RAFTLOG_LOG_DIR` | `./logs` | Directory for text and JSON log files |
| `RAFTLOG_INSTANCE_ID` | Hostname or `local` | Identifier included in log filenames |

PowerShell example:

```powershell
$env:RAFTLOG_LOG_LEVEL = "DEBUG"
$env:RAFTLOG_LOG_DIR = ".\run-data\logs"
java -jar raftlog-demo/target/raftlog-demo-1.4.0.jar .\run-data\wal-demo
```

The text and JSON files use the name
`raftlog-demo-<instance>-<timestamp>.<log|json>` and roll at 100 MB, retaining up
to nine rolled files for that run.

## Generated files and cleanup

`WalDemo` and `KeyValueExample` leave their data in place so subsequent runs can
demonstrate replay. A data directory can contain:

| File | Purpose |
|------|---------|
| `raft.log` | Write-ahead log records |
| `meta.dat` | Persistent term and vote metadata when used by the program |
| `raft.lock` | Exclusive process lock; the file may remain after the lock is released |

Stop the example before removing its data directory. These are demonstration
directories, so delete them when their persisted state is no longer needed.

## Tests

Run the demo module's tests and its required dependencies with:

```bash
mvn test -pl raftlog-demo -am
```

For the complete project and release verification procedures, see [RaftLog test
documentation](../docs/RAFTLOG_TEST_DOCUMENTATION.md).
