# Missing RaftLog Features and Trade-Offs

RaftLog is intentionally minimal. This document captures features that are not currently part of the core implementation and the practical trade-offs if they were added.

## Scope note

RaftLog focuses on:
- Persist-before-respond WAL durability for Raft primitives
- Two-file storage model (`meta.dat` and `raft.log`) for a small in-memory log
- Sequential replay on startup
- Explicit caller-owned snapshot-driven prefix compaction

The following features are intentionally out-of-scope today and are listed here as potential future work.

## Missing features with pros and cons

### 1. Multi-segment WAL + segment rotation

RaftLog currently uses a single log file model (`raft.log`) with compaction rewrites.

Pros:
- Bounded segment sizes improve operational readability and cleanup. Example: at 256 MB, the runtime rotates from `raft.log.003` to `raft.log.004`, so on-call can remove or archive complete historical segments without reopening the active one.
- Lower long-term recovery cost when old segments can be dropped. Example: after compaction reaches index 2,000,000, startup can ignore closed segments entirely for the first phase of replay and begin from the next candidate segment.
- Better control over disk growth in long-lived clusters. Example: a 7-node cluster can set a policy of “10 GB total WAL, max 12 segments,” and alert the operator once segment creation would exceed that budget.

Cons:
- Adds segment metadata complexity (IDs, base index, seals, and replacement state). Example: the implementation now tracks `segment_id`, `base_index`, and `sealed=true/false` transitions, and each transition must be persisted correctly before the next append.
- Increases crash-recovery edge cases around partial segment publication. Example: if a process dies after writing bytes for segment N but before the seal, recovery must detect partial writes, repair the tail, and avoid replaying garbage.
- Raises verification and test surface significantly. Example: we must add fault-injection cases for `ACTIVE -> CLOSED` crashes, stale metadata, and stale replacement pointers during restart.

### 2. On-disk index / random seek support

RaftLog currently replays sequentially and does not maintain a separate index.

Pros:
- Faster random lookups for tooling and diagnostics. Example: an operator command to inspect entry 1,200,000 can jump directly through the index instead of replaying every earlier entry.
- Potentially quicker startup in systems that need sparse reads. Example: if a component only needs `lastLogIndex` and `lastTerm`, it can seek to those positions and avoid a full replay pass.
- Useful for recovery tooling that needs jump/seek behavior. Example: disaster recovery can resume from “post-snapshot offset” rather than replaying historical entries that are known safe.

Cons:
- Requires index + WAL consistency under the same durability ordering. Example: appending entry 5000 must not be visible in index unless the WAL entry has already been durably synced, otherwise readers can read stale or non-existent data.
- Extra writes and fsync pressure unless aggressively batched. Example: on HDD, per-entry index updates can double write calls and significantly increase commit latency.
- Additional corruption surface and recovery coordination. Example: a torn write in the index can point to a wrong segment offset, so startup needs a reconciliation pass comparing index checksums to WAL checksums.

### 3. Rich segment metadata and checkpoint state

RaftLog does not track segment IDs, sealed flags, or segment checkpoint metadata.

Pros:
- Better handling of tail/head truncation edge cases. Example: sealed-segment metadata lets the system tell a partial, unsealed tail apart from already committed history, so it can safely truncate only what is durable.
- More explicit recovery state for advanced restart workflows. Example: after restart, a node can validate `checkpoint_index + segment_checksum + last_durable_term` before deciding whether to begin appends from compacted or raw mode.
- Better introspection for operators. Example: CLI output can show “segment-17 sealed at index 1,050,000,” giving explicit evidence that prefix compaction is safe up to that index.

Cons:
- Larger mutable metadata state increases corruption failure modes. Example: a partial update to `checkpointed_index` can cause truncation decisions that skip recoverable entries or keep too much history.
- More startup validation and reconciliation logic. Example: restart now needs to compare metadata checkpoints against actual segment boundaries before allowing writes, which can delay startup under heavy corruption.
- More difficult to keep implementation small and auditable. Example: each metadata transition becomes another branch that must be reasoned about during injection tests and incident reviews.

### 4. Advanced durability mode tuning

RaftLog uses explicit `sync()` and does not ship adaptive batching or background flush policies.

Pros:
- Throughput tuning options for high-write clusters. Example: a 5 ms batching window can cut fsync calls by ~70% during burst traffic while keeping append order intact.
- Latency/IO trade-off control in a single config. Example: latency-critical environments can force sync-on-append, while background batching can be enabled for archival or analytics workloads.
- Better fit for heterogeneous deployment SLAs. Example: the same codebase can run with SSD-friendly settings in one region and HDD-optimized settings in another without forks.

Cons:
- Easier to accidentally weaken persist-before-response guarantees. Example: if `background-flush=true` becomes the default, a bug can return success before durability is guaranteed in timeout races.
- More subtle shutdown and retry semantics. Example: process exit must wait for pending background flush tasks or explicitly fail in-flight writes; otherwise clients may see acknowledged operations that were not persisted.
- Larger operational risk if defaults are misconfigured. Example: a cluster may pass functional tests with optimistic batching, then lose durability under a real power-loss event.

### 5. Automatic/background compaction and snapshot orchestration

RaftLog only supports explicit prefix compaction through `truncatePrefix()`.

Pros:
- Lower manual operational burden. Example: compaction can run in maintenance windows and trigger without waiting for operators to call `truncatePrefix()` correctly.
- More predictable disk footprint without manual workflows. Example: retention policies can enforce “keep only N hours of log,” preventing disks from silently filling under constant writes.
- Better behavior under sustained churn. Example: if membership changes produce many short-lived snapshots, background scheduling can continuously trim stale prefixes instead of reacting after alerts.

Cons:
- Snapshot boundary ownership would need stronger contracts between WAL and state machine. Example: compaction cannot advance beyond the last durable snapshot index or state replay can diverge after restart.
- More background I/O can interfere with replication latency. Example: compaction fsync cycles may coincide with leader append bursts and increase p99 append latency.
- Harder to preserve deterministic behavior under crash windows. Example: if crash occurs during compaction, restart must deterministically reconstruct whether each prefix was compacted and from which snapshot boundary.

### 6. Optional alternate backends (RocksDB, LMDB, etc.)

The interface is abstract, but default behavior is one-file-filed.

Pros:
- Deployment flexibility per environment. Example: a cloud-native service with strict local-disk policy can run RocksDB while a small embedded deployment stays on the file backend.
- Potential performance gains in specialist workloads. Example: LMDB may reduce write amplification for workloads that repeatedly write tiny consensus entries.
- Easier migration paths for teams with existing datastore standards. Example: teams already operating RocksDB operational tooling can keep a single backup/monitoring workflow.

Cons:
- Divergent durability semantics across backends. Example: if one adapter advertises per-transaction durability and another per-batch durability, recovery expectations and durability proofs change by adapter.
- Bigger compatibility/test matrix. Example: each adapter version must be validated for restart, snapshot reuse, and corruption recovery, which grows test burden rapidly.
- More migration and rollback complexity. Example: rollback during incident response may require understanding both file-based and RocksDB checkpoint formats before restart.

### 7. Built-in observability and security features

RaftLog does not include built-in metrics, encryption-at-rest, compression, or telemetry framing.

Pros:
- Better production visibility and alerting fidelity. Example: exporting `append_latency_p99` and `fsync_failures` catches WAL pressure before follower catch-up lag becomes visible to clients.
- Compliance support for storage controls. Example: encryption-at-rest and audit logs can provide evidence for internal or regulatory controls in sensitive environments.
- Potential storage cost optimization and operational auditability. Example: compression ratio and segment age metrics can inform moving older, low-value log segments to cheaper storage tiers.

Cons:
- New dependencies and operational key-management burden. Example: encryption requires key rotation, IAM policy, HSM/KMS integration, and documented key-recovery playbooks.
- Extra CPU/IO overhead on hot write paths. Example: enabling encryption+compression can reduce write throughput and raise tail latency exactly when consensus traffic is highest.
- Risk of configuration mistakes that hide deeper correctness issues. Example: an overly aggressive retention/compression policy can make logs smaller while still leaving silent durability violations undetected.

### 8. Protocol-specific helpers in storage layer

RaftLog stores bytes and metadata only; protocol flows like snapshot framing and membership helpers are not built in.

Pros:
- Clean separation between consensus protocol and persistence. Example: `RaftLog` stores opaque bytes and metadata only, so snapshot formats and membership schemas remain in the protocol layer.
- Easier to integrate into different Raft implementations. Example: two services with different RPC stacks can share the same storage layer and keep transport differences in their own adapters.
- Keeps core easier to reason about and verify. Example: persistence correctness bugs are isolated from election, timing, and client-routing logic, reducing cross-module failure coupling.

Cons:
- More logic must be implemented in each consuming Raft layer. Example: every integrator still writes their own snapshot framing, index conventions, and truncation policy.
- Less “turn-key” experience for new adopters. Example: a new team can’t get a runnable Raft node in one binary import; they need extra protocol glue first.
- Duplicate implementations across consumers if not standardized. Example: two teams may encode membership or snapshots differently and later discover snapshots cannot be replayed interchangeably.

## Practical priority view

Low-risk additions:
- Additional observability hooks and metrics
- Documentation-level recommendations for durable operation profiles
- Optional convenience tooling around manual compaction cadence

Medium-risk additions:
- On-disk indexing
- Background compaction orchestration

Higher-risk additions:
- Multi-segment storage model
- Alternate backend adapters
- Advanced durability tuning and adaptive flush policies

## Recommendation

For Raft correctness first, keep the current minimal core and add features only with explicit acceptance criteria:
- No weakening of persist-before-response semantics
- Fencing and recovery behavior must remain deterministic
- Every feature must have explicit torn-write and corruption coverage
- Each new capability should be opt-in to preserve RaftLog’s current simplicity
