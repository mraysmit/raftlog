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
 * <b>Critical Contract:</b> Append and suffix-truncation operations require
 * {@link #sync()} before acknowledgment. Metadata updates and prefix compaction
 * provide their own durability barriers, subject to the configured filesystem.
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
     * <p>
     * A failure to force the staging file or, on non-Windows providers, the data
     * directory is a durability failure: the instance is fenced and every later
     * operation fails until it is closed and a fresh instance is opened.
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
     * Reclaims WAL records at indexes less than or equal to {@code toIndex}.
     * The caller must durably publish a covering application snapshot first.
     * Retained entries keep their indexes, terms, payloads and replay order.
     * Successful completion is a durability barrier for the replacement WAL;
     * no separate {@link #sync()} is required. Zero is a no-op; negatives fail.
     * FileRaftStorage always forces the replacement file, even when append sync
     * is disabled. Directory force is required on non-Windows providers; the
     * Java Windows provider supports only file force and atomic replacement.
     * A publication failure requires closing and opening a fresh storage instance.
     * This operation does not remember the boundary or prevent later appends at
     * removed indexes; the caller owns its snapshot index and term.
     * Implementations without compaction fail explicitly for compatibility.
     *
     * @param toIndex inclusive last index to remove
     * @return completion of durable compaction, subject to filesystem guarantees
     */
    default CompletableFuture<Void> truncatePrefix(long toIndex) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("Prefix compaction is not supported"));
    }

    /**
     * Universal Durability Barrier.
     * <p>
     * Forces all pending appends/truncations to physical disk.
     * <b>MUST be called before acknowledging AppendEntries RPCs.</b>
     * <p>
     * This is the critical "persist-before-response" barrier that ensures
     * Raft safety.
     * <p>
     * A failed force must not be retried: the operating system may already have
     * discarded the dirty pages, so a retry can succeed for data that is gone.
     * FileRaftStorage therefore fences the instance on failure; close it and open
     * a fresh instance, which replays from the last state known to be on disk.
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
     * Replay is destructive only for a structurally incomplete EOF fragment, which
     * is treated as a torn write and physically truncated. A complete record with a
     * bad CRC, malformed header, arbitrary garbage, or an invalid record followed by
     * a valid record may be acknowledged data damaged later; FileRaftStorage fails
     * with {@link FileRaftStorage.CorruptLogException}, leaves the file unchanged and
     * fences the instance. Such a node must be restored from its peers rather than
     * repaired by truncation.
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
