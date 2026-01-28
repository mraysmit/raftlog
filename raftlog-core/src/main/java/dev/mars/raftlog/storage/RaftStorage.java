/*
 * Copyright 2026 Mark Andrew Ray-Smith
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.mars.raftlog.storage;

import java.io.Closeable;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Generic Raft Storage Interface.
 * <p>
 * This interface abstracts the persistence layer for Raft consensus,
 * allowing implementations to switch between a custom WAL (FileRaftStorage)
 * and high-performance backends like RocksDB.
 * <p>
 * The RaftNode depends solely on this interface, not on concrete implementations.
 * <p>
 * <b>Critical Contract:</b> All methods that modify state must ensure durability
 * (fsync) before the returned Future completes successfully.
 *
 * @see FileRaftStorage
 */
public interface RaftStorage extends Closeable {

    /**
     * Opens the storage engine. Idempotent.
     *
     * @param dataDir the directory where WAL files will be stored
     * @return a Future that completes when storage is ready
     */
    CompletableFuture<Void> open(Path dataDir);

    // ========================================================================
    // Metadata (Term & Vote)
    // ========================================================================

    /**
     * Atomically persists the current term and vote.
     * <p>
     * Implementation MUST ensure durability (fsync) before returning.
     * This is critical for preventing double-voting after crash/restart.
     *
     * @param currentTerm the current Raft term
     * @param votedFor    the candidate ID voted for (empty if no vote cast)
     * @return a Future that completes when metadata is durable
     */
    CompletableFuture<Void> updateMetadata(long currentTerm, Optional<String> votedFor);

    /**
     * Loads metadata on startup.
     *
     * @return the persisted metadata, or (0, empty) if no state exists
     */
    CompletableFuture<PersistentMeta> loadMetadata();

    /**
     * Persistent Raft metadata: currentTerm and votedFor.
     *
     * @param currentTerm the persisted term
     * @param votedFor    the candidate voted for in currentTerm (empty if none)
     */
    record PersistentMeta(long currentTerm, Optional<String> votedFor) {
        public static final PersistentMeta EMPTY = new PersistentMeta(0L, Optional.empty());
    }

    // ========================================================================
    // Log Operations
    // ========================================================================

    /**
     * Appends a batch of entries to the log.
     * <p>
     * NOT required to fsync immediately - use {@link #sync()} for that.
     * This allows batching multiple appends before a single fsync.
     *
     * @param entries the log entries to append
     * @return a Future that completes when entries are written (but not necessarily synced)
     */
    CompletableFuture<Void> appendEntries(List<LogEntryData> entries);

    /**
     * A single Raft log entry.
     *
     * @param index   the log index (1-based in Raft)
     * @param term    the term when the entry was created
     * @param payload the command payload (opaque bytes)
     */
    record LogEntryData(long index, long term, byte[] payload) {
    }

    /**
     * Deletes all log entries with index >= fromIndex.
     * <p>
     * Used to resolve conflicts when a follower diverges from the leader.
     * <p>
     * <b>WARNING: Truncation is NOT durable until {@link #sync()} is called.</b>
     * <p>
     * This method must NEVER be called standalone in RaftNode. Always use the pattern:
     * <pre>{@code
     * // Correct usage (via AppendPlan):
     * wal.truncateSuffix(plan.truncateFromIndex())
     *    .thenCompose(v -> wal.appendEntries(plan.entriesToAppend()))
     *    .thenCompose(v -> wal.sync())  // DURABILITY BARRIER
     *    .thenAccept(v -> plan.applyTo(memoryLog));
     * }</pre>
     *
     * @param fromIndex the first index to delete (inclusive)
     * @return a Future that completes when the truncation record is written (but NOT synced)
     */
    CompletableFuture<Void> truncateSuffix(long fromIndex);

    /**
     * Universal Durability Barrier.
     * <p>
     * Forces all pending appends/truncations to physical disk.
     * <b>MUST be called before acknowledging AppendEntries RPCs.</b>
     * <p>
     * This is the critical "persist-before-response" barrier that ensures
     * Raft safety.
     *
     * @return a Future that completes when all data is durable
     */
    CompletableFuture<Void> sync();

    /**
     * Replays the entire log from disk on startup.
     * <p>
     * For FileRaftStorage: Scans the append-only file sequentially.
     * For RocksDB: Scans keys {@code log:1} to {@code log:N}.
     * <p>
     * This method also truncates any corrupt/partial records at the tail.
     *
     * @return a Future containing all valid log entries in order
     */
    CompletableFuture<List<LogEntryData>> replayLog();

    /**
     * Closes the storage, releasing all resources.
     * <p>
     * After close, no other methods should be called.
     */
    @Override
    void close();
}
