# RaftLog Non-Goals, Missing Features, and Trade-Offs

RaftLog is intentionally minimal. This document separates the current safety baseline from features that are not part of the core implementation and records the practical trade-offs of adding them.

## Scope note

RaftLog focuses on:

- Caller-enforced persist-before-response WAL durability: append and suffix-truncation writes require an explicit `sync()` before acknowledgment, while metadata updates and prefix compaction provide their own durability barriers
- Two authoritative state files (`meta.dat` and `raft.log`) for a small in-memory log, plus lock and temporary publication files
- Sequential replay on startup
- Explicit caller-owned snapshot-driven prefix compaction

The following capabilities are not implemented today. Some are deliberate non-goals; others are candidates for future hardening.

## Current safeguards (not missing features)

- Public configuration cannot disable fsync. The unsafe no-fsync seam is package-private and exists only for deterministic durability-boundary tests.
- Replay truncates only structurally incomplete EOF fragments. Complete bad CRCs, malformed headers, arbitrary garbage, and corruption followed by valid records are preserved, reported as ambiguous corruption, and fence the storage instance.
- Metadata publication and WAL compaction use explicit file and directory durability boundaries where the platform supports directory fsync.
- Operational logging bounds long node identifiers, escapes control characters to prevent forged multiline records, and uses explicit ambiguous-corruption wording. These behaviors have focused edge-case tests.
- The core module depends only on the SLF4J API at runtime; applications retain control of the logging implementation and configuration.

## Missing features with pros and cons

### 1. Multi-segment WAL + segment rotation

RaftLog currently uses a single log file model (`raft.log`) with compaction rewrites.

Pros:

- Bounded segment sizes improve operational readability and controlled cleanup. Example: at 256 MB, the runtime can rotate from `raft.log.003` to `raft.log.004`, allowing tooling to inspect or archive a closed segment without reopening the active one. Deletion would still require an atomically recorded durable snapshot boundary proving that the segment is no longer needed.
- Lower long-term recovery cost when old segments can be dropped. Example: after compaction reaches index 2,000,000, startup can ignore closed segments entirely for the first phase of replay and begin from the next candidate segment.
- Better control over disk growth when combined with snapshot-driven compaction. Example: a cluster can alert when the WAL approaches a configured budget and request a snapshot; segmentation alone cannot safely enforce a byte or segment-count limit.

Cons:

- Adds segment metadata complexity (IDs, base index, seals, and replacement state). Example: the implementation now tracks `segment_id`, `base_index`, and `sealed=true/false` transitions, and each transition must be persisted correctly before the next append.
- Increases crash-recovery edge cases around partial segment publication. Example: if a process dies after writing bytes for segment N but before the seal, recovery must detect partial writes, repair the tail, and avoid replaying garbage.
- Raises verification and test surface significantly. Example: we must add fault-injection cases for `ACTIVE -> CLOSED` crashes, stale metadata, and stale replacement pointers during restart.

### 2. On-disk index / random seek support

RaftLog currently replays sequentially and does not maintain a separate index.

Pros:

- Faster random lookups for tooling and diagnostics. Example: an operator command to inspect entry 1,200,000 can jump directly through the index instead of replaying every earlier entry.
- Potentially quicker startup if a validated checkpoint and a random-read API are added. Example: a component that only needs `lastLogIndex` and `lastTerm` could seek to the indexed tail rather than decode every earlier payload.
- Useful for recovery tooling that needs jump/seek behavior. Example: disaster recovery can resume from “post-snapshot offset” rather than replaying historical entries that are known safe.

Cons:

- An authoritative index would require index + WAL consistency under one durability protocol. A rebuildable derived index could safely lag the WAL, but readers would need validation and fallback behavior for missing or stale entries.
- Extra write and CPU overhead remains even when the index is disposable. Persisting the index with every entry may increase commit latency; batching it or rebuilding it trades write cost for recovery time.
- Adds corruption handling and API surface. A stale or torn index can point to the wrong WAL offset, so startup must validate or rebuild it, and `RaftStorage` would need lookup methods beyond its current full-log `replayLog()` contract.

### 3. Durable synced-offset checkpoint

RaftLog forces the WAL during `sync()`, but it does not persist a separately validated offset identifying the last byte covered by a completed durability barrier.

Why it may be valuable:

- It would make recovery decisions evidence-based rather than purely structural. If publishing the checkpoint is part of `sync()` completion, bytes after the last durable offset could be treated as an unacknowledged tail, while damage at or before that offset would always be preserved and fenced.
- It would close an unavoidable ambiguity in the current policy: a bit flip in the payload-length field of the final acknowledged record can resemble a structurally incomplete EOF write.
- It could support stronger diagnostics such as “durable through WAL byte 18,442 and log index 517” without treating a Raft commit index as storage-owned state.

Costs and risks:

- The checkpoint introduces another crash-ordering protocol. The WAL must be forced before the checkpoint advances, and checkpoint replacement plus directory durability must be handled correctly.
- A stale, ahead-of-WAL, corrupt, or partially published checkpoint needs deterministic reconciliation rules that never discard acknowledged data.
- Prefix compaction must atomically translate or replace the checkpoint when publishing a rewritten WAL generation.
- Proving the protocol requires exhaustive crash injection around WAL force, checkpoint write, checkpoint force, rename, directory force, and restart.

### 4. Rich segment lifecycle metadata

RaftLog does not track segment IDs, sealed flags, generations, or replacement state.

Pros:

- Better handling of segment lifecycle edge cases. Sealed-versus-active state can narrow recovery decisions after rotation, although sealing alone does not prove that entries are committed or covered by a durable snapshot.
- More explicit recovery state for advanced restart workflows. Example: after restart, a node can validate a segment checksum and a caller-published durable snapshot boundary before choosing the first segment to replay.
- Better introspection for operators. Example: CLI output can show “segment 17 sealed at index 1,050,000” and separately show the durable snapshot boundary. Only the latter establishes which prefix is eligible for removal.

Cons:

- Larger mutable metadata state increases corruption failure modes. Example: a partial update to `checkpointed_index` can cause truncation decisions that skip recoverable entries or keep too much history.
- More startup validation and reconciliation logic. Example: restart now needs to compare metadata checkpoints against actual segment boundaries before allowing writes, which can delay startup under heavy corruption.
- More difficult to keep implementation small and auditable. Example: each metadata transition becomes another branch that must be reasoned about during injection tests and incident reviews.

### 5. Advanced durability mode tuning

RaftLog supports caller-controlled batching: one `appendEntries()` call can contain multiple entries, and multiple writes can share a later `sync()`. It does not ship adaptive group commit, sync-on-append, or background flush policies. Public configuration rejects `syncEnabled=false`; a package-private factory provides the explicit unsafe seam needed by durability-boundary tests.

Pros:

- Throughput tuning options for high-write clusters. Example: a bounded group-commit window can combine concurrent acknowledgments behind fewer fsync calls while preserving append order.
- Explicit latency/IO trade-offs. Durability-sensitive deployments could use one barrier per acknowledgment group, while higher-throughput deployments could use bounded group commit without acknowledging any request before its barrier completes.
- Better fit for heterogeneous deployment SLAs. Example: the same codebase can run with SSD-friendly settings in one region and HDD-optimized settings in another without forks.

Cons:

- Easier to accidentally weaken persist-before-response guarantees. Example: if `background-flush=true` becomes the default, a bug can return success before durability is guaranteed in timeout races.
- More subtle shutdown and retry semantics. Example: process exit must wait for pending background flush tasks or explicitly fail in-flight writes; otherwise clients may see acknowledged operations that were not persisted.
- Larger operational risk if defaults are misconfigured. Example: a cluster may pass functional tests with optimistic batching, then lose durability under a real power-loss event.

### 6. Automatic/background compaction and snapshot orchestration

RaftLog only supports explicit prefix compaction through `truncatePrefix()`, and the caller must supply a boundary already covered by a durable application snapshot.

Pros:

- Lower integration and operational burden. Example: a coordinator can schedule compaction in maintenance windows after receiving a confirmed durable snapshot boundary.
- More predictable disk footprint without unsafe time-based deletion. Size or age thresholds can request snapshot creation, but log removal must still stop at the last confirmed durable snapshot index.
- Better behavior under sustained log growth. Background scheduling can compact newly snapshotted prefixes before disk pressure becomes an incident.

Cons:

- Snapshot boundary ownership would need stronger contracts between WAL and state machine. Example: compaction cannot advance beyond the last durable snapshot index or state replay can diverge after restart.
- More background I/O can interfere with replication latency. Example: compaction fsync cycles may coincide with leader append bursts and increase p99 append latency.
- Harder to preserve deterministic behavior under crash windows. Example: if crash occurs during compaction, restart must deterministically reconstruct whether each prefix was compacted and from which snapshot boundary.

### 7. Optional alternate backends (RocksDB, LMDB, etc.)

The interface is abstract, but the project currently ships only the single-WAL-file `FileRaftStorage` backend.

Pros:

- Deployment flexibility per environment. Example: a team already operating RocksDB may prefer to reuse its backup, diagnostics, and storage-management practices while a small embedded deployment stays on the file backend.
- Potential performance gains in specialist workloads, subject to workload-specific benchmarks and an equivalent durability contract.
- Easier migration paths for teams with existing datastore standards. Example: teams already operating RocksDB operational tooling can keep a single backup/monitoring workflow.

Cons:

- Divergent durability semantics across backends. Example: if one adapter advertises per-transaction durability and another per-batch durability, recovery expectations and durability proofs change by adapter.
- Bigger compatibility/test matrix. Example: each adapter version must be validated for restart, metadata durability, prefix compaction, corruption recovery, and the same fencing contract.
- More migration and rollback complexity. Example: rollback during incident response may require understanding both file-based and RocksDB checkpoint formats before restart.

### 8. Explicit metadata resource limits

Log-entry payloads have a configured maximum, but `votedFor` metadata and `meta.dat` do not have an independent fixed size limit. Logging is now bounded, yet an extremely large caller value or externally enlarged metadata file can still cause large allocations before validation completes.

Pros:

- A byte limit on persisted node identifiers would prevent accidental memory and disk amplification during `updateMetadata()`.
- Checking `meta.dat` size before `Files.readAllBytes()` would reject oversized or hostile files without allocating their full contents.
- A documented bound would make adapter behavior and capacity planning predictable.

Cons:

- Choosing a limit becomes a compatibility contract; existing consumers with unusually long identifiers may need migration guidance.
- Limits must be defined in encoded UTF-8 bytes, not Java character count, and tested with multibyte Unicode and boundary values.
- A configurable limit adds another operational setting, while a fixed format limit requires a format/version decision if it ever changes.

### 9. Metrics, tracing, compression, and encryption

RaftLog emits bounded operational logs through SLF4J but does not expose stable metrics, tracing hooks, structured event IDs, or an audit-event contract. It also does not provide compression or application-level encryption at rest.

Pros:

- Better production visibility and alerting fidelity. Example: exporting `append_latency_p99` and `fsync_failures` catches WAL pressure before follower catch-up lag becomes visible to clients.
- Compliance support for storage controls. Example: encryption-at-rest and audit logs can provide evidence for internal or regulatory controls in sensitive environments.
- Potential storage cost optimization and operational auditability. Example: compression-ratio and WAL-age metrics can inform snapshot cadence and capacity planning. Moving data to another storage tier would require a separate segmented-storage design and must preserve recovery availability.

Cons:

- New dependencies and operational key-management burden. Example: encryption requires key rotation, IAM policy, HSM/KMS integration, and documented key-recovery playbooks.
- Extra CPU/IO overhead on hot write paths. Example: enabling encryption+compression can reduce write throughput and raise tail latency exactly when consensus traffic is highest.
- Risk of configuration mistakes that create unreadable data or misleading signals. Example: a key-rotation or compression-format error can make an otherwise intact WAL unavailable during recovery.

### 10. Protocol-specific helpers in storage layer

RaftLog stores bytes and metadata only; protocol flows like snapshot framing and membership helpers are not built in.

Arguments for keeping these helpers out of the storage layer:

- Clean separation between consensus protocol and persistence. Example: `RaftLog` stores opaque bytes and metadata only, so snapshot formats and membership schemas remain in the protocol layer.
- Easier to integrate into different Raft implementations. Example: two services with different RPC stacks can share the same storage layer and keep transport differences in their own adapters.
- Keeps core easier to reason about and verify. Example: persistence correctness bugs are isolated from election, timing, and client-routing logic, reducing cross-module failure coupling.

Costs of keeping these helpers out:

- More logic must be implemented in each consuming Raft layer. Example: every integrator still writes their own snapshot framing, index conventions, and truncation policy.
- Less “turn-key” experience for new adopters. Example: a new team can’t get a runnable Raft node in one binary import; they need extra protocol glue first.
- Duplicate implementations across consumers if not standardized. Example: two teams may encode membership or snapshots differently and later discover snapshots cannot be replayed interchangeably.

## Practical priority view

Low-risk additions:

- Stable observability hooks and optional metrics adapters
- Documentation-level recommendations for durable operation profiles
- Optional convenience tooling around manual compaction cadence

Medium-risk additions:

- Explicit UTF-8 byte and metadata-file size limits
- A rebuildable, non-authoritative on-disk index
- Background scheduling that receives an already-validated durable snapshot boundary

Higher-risk additions:

- A durable synced-offset checkpoint and its compaction/recovery protocol
- Multi-segment storage model
- Authoritative indexing or rich segment lifecycle metadata
- Automatic snapshot creation and boundary selection
- Alternate backend adapters
- Advanced durability tuning and adaptive flush policies
- Compression or encryption that changes the persisted record format

## Recommendation

For Raft correctness first, keep the current minimal core. If stronger proof is required when distinguishing a torn EOF write from later corruption, prioritize the durable synced-offset checkpoint before segmentation, indexing, or durability tuning.

Add future features only with explicit acceptance criteria:

- No weakening of persist-before-response semantics
- Fencing and recovery behavior must remain deterministic
- Every feature must begin with failing edge-case tests and retain explicit torn-write, corruption, boundary-value, and control-character coverage where applicable
- Format or backend changes must define compatibility, migration, and rollback behavior
- Optional capabilities must not create untested durability combinations
- Logs produced by new paths must remain bounded, single-line for untrusted values, and reviewable independently of test-runner status
