# Prefix compaction

RaftLog 1.2.0 adds `RaftStorage.truncatePrefix(long toIndex)`. It removes entries with indexes **less than or equal to** the boundary and physically rewrites `raft.log` to reclaim obsolete bytes. Terms, payloads, indexes and replay order of retained entries are unchanged; term/vote metadata is untouched. Zero is a no-op, a negative boundary fails, and a boundary at or above the last entry produces an empty WAL. Repeating a completed compaction is safe.

The application must first durably publish a covering snapshot, including its last included index and term, and retain any recovery-dependency marker its integration requires. RaftLog does not store application snapshots or a persistent minimum append index. It cannot determine whether deleting a prefix is safe for the application. Old indexes must not be reintroduced by the caller after compaction. `AppendPlan` still assumes an uncompacted memory log starting at index 1; snapshot-aware callers must account for their base index.

```java
// snapshotStore is application-owned; success must mean durable publication.
snapshotStore.save(snapshotBytes, lastIncludedIndex, lastIncludedTerm).join();
storage.truncatePrefix(lastIncludedIndex).join();
// Only now trim the matching in-memory prefix. No extra storage.sync() is needed.
```

## Publication and failures

The WAL executor serializes compaction with append, suffix truncation, metadata, replay and sync operations. Compaction reads and resolves existing suffix markers, writes retained APPEND records into `raft.log.tmp`, forces that file, closes the old WAL handle, atomically replaces `raft.log`, forces the directory on non-Windows providers, and opens the replacement for subsequent appends. There is no non-atomic move fallback. Compaction always forces its output even if append sync is disabled; production must still enable sync for ordinary writes.

Failure before publication preserves the original WAL and allows subsequent operations. Once publication starts, any failure makes the current storage instance reject operations: close it and create a fresh instance to recover the authoritative `raft.log`. This includes an uncertain rename outcome, failed directory force or failed reopen. Do not retry writes on that failed instance. The original or compacted WAL may survive; both require the already-published snapshot to remain available.

On open, the directory lock is acquired before stale `raft.log.tmp` is discarded. A temporary file is never promoted to authority. If a temporary file exists without `raft.log`, open fails and preserves the files for recovery rather than inventing an empty log. A corrupt/incomplete source WAL causes compaction to fail without modifying it; invoke the existing explicit `replayLog()` recovery policy first, then retry compaction if the recovered state is acceptable.

Java's Windows filesystem provider cannot force directories. On Windows this implementation forces the new file and uses atomic replacement, but does not promise directory-fsync or machine-power-loss durability. On non-Windows providers, a directory-force error fails compaction and fences the instance. Validate the actual deployment filesystem and snapshot publication policy before deployment accreditation. Child-JVM termination tests establish process-interruption behavior, not a power-cut simulation.

## Compatibility and cost

The WAL format remains version 1, using the existing APPEND records. Raw append/replay still permits duplicate or nonsequential indexes. Prefix compaction does not introduce last-write-wins semantics. Conflict replacement still requires suffix truncation, append and sync; matching retries must be suppressed by the Raft caller.

The interface default fails explicitly with `UnsupportedOperationException`, preserving compatibility for alternate implementations without silently pretending they compact. Existing FileRaftStorage clients need no source changes. Older binaries can decode the retained records, but cannot restore deleted history; application-level downgrade is safe only if that binary understands the snapshot boundary and recovery metadata. Preserve whole-node backups, not independent WAL/snapshot copies.

Compaction scans the complete WAL and materializes its logical entries in memory, then writes the retained entries. It requires temporary disk space for the retained WAL and blocks other WAL operations until completion. It is explicit, not automatic or background compaction. Suffix truncation alone still adds a marker and does not reclaim disk space.

## Validation

The implementation followed behavioral red/green tests on base revision `872a8c0e88abaf3938fe056066fef32b6159f5a8`: nine initial contract failures, then 23 contract/failure cases failing with zero compilation errors, followed by all 23 passing with the implementation. The default unsupported API and package-private filesystem seam were scaffolding for compiling the tests; no working compaction existed during red.

`FileRaftStoragePrefixCompactionTest` covers inclusive boundaries, empty/all-deleted logs, metadata, disk size, raw duplicate/order compatibility, repeated compaction, queued writes and restart. `FileRaftStorageCompactionFailureTest` exercises real files with partial-write, force, rename, directory and reopen faults; acknowledgment ordering; source corruption; and four abruptly terminated child-JVM rewrite stages. Existing recovery-contract tests remain unchanged. No mocking framework is used.

Timestamped commands, failure output, test source identity, patches and subsequent verification are retained locally under Git-ignored `test-output/prefix-compaction/`. They are not part of the published artifact or repository documentation. Historical claims about a different `db59859` build are not evidence for this implementation.

Release validation on 2026-09-05: Windows/JDK 25 `clean install` ran 319 tests with zero failures/errors and three existing platform skips. A non-root Linux container with JDK 21 ran `clean verify`: all 319 tests passed with no skips, including actual directory force and child-process interruption. Quorus's five selected storage/snapshot/restart suites passed all 41 cases against the locally installed 1.2.0 artifact. This validates the integration boundary, not the entire Quorus reactor or deployment power-loss acceptance.
