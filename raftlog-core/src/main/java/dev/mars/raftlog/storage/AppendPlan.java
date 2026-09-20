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

import dev.mars.raftlog.storage.RaftStorage.LogEntryData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Calculates the delta between the current in-memory log and an incoming AppendEntries request.
 * <p>
 * This is a <b>pure, side-effect-free</b> calculator. It determines exactly what needs to
 * happen to the log before any mutation or persistence occurs.
 * <p>
 * The plan ensures we only persist and apply what is actually necessary:
 * <ul>
 *   <li>If entries already exist and match, they are skipped</li>
 *   <li>If entries conflict (same index, different term), truncation is required</li>
 *   <li>New entries beyond our log are appended</li>
 * </ul>
 * <p>
 * <b>It is not lenient.</b> Everything it is given is data the node already holds, so an
 * inconsistency is a bug in the caller. A plan built from inconsistent arguments would be refused
 * by the storage later, further from the cause, so it is refused here with an
 * {@link IllegalArgumentException} (or a {@link NullPointerException} for a null argument or
 * element) and nothing is returned. What remains legal is what Raft itself permits: a heartbeat
 * with no entries, entries the follower already holds, entries already covered by its snapshot,
 * and a conflicting tail.
 * <p>
 * <b>Usage Pattern (Prepare → Persist → Apply):</b>
 * <pre>{@code
 * // 1. Calculate the plan (no mutations)
 * AppendPlan plan = AppendPlan.from(startIndex, incomingEntries, currentLog, compactionBoundary);
 *
 * // 2. Persist to WAL
 * wal.truncateSuffix(plan.truncateFromIndex());  // if needed
 * wal.appendEntries(plan.entriesToAppend());
 * wal.sync();  // DURABILITY BARRIER
 *
 * // 3. Apply to in-memory log (only after sync succeeds)
 * plan.applyTo(currentLog);
 * }</pre>
 *
 * @param truncateFromIndex the index from which to truncate (null if no truncation needed)
 * @param entriesToAppend   the entries to append after any truncation
 */
public record AppendPlan(
        Long truncateFromIndex,
        List<LogEntryData> entriesToAppend
) {
    private static final Logger LOG = LoggerFactory.getLogger(AppendPlan.class);

    /**
     * Validates the plan and takes a defensive copy of the entries.
     *
     * @throws NullPointerException     if the entry list or one of its elements is null
     * @throws IllegalArgumentException if the truncation index is below 1, the entries are not a
     *                                  contiguous run with non-decreasing terms, or a replacement
     *                                  does not begin at the truncation index
     */
    public AppendPlan {
        Objects.requireNonNull(entriesToAppend, "entriesToAppend must not be null; use an empty list");
        entriesToAppend = List.copyOf(entriesToAppend);             // also rejects null elements
        if (truncateFromIndex != null && truncateFromIndex < 1) {
            throw new IllegalArgumentException("truncateFromIndex must be at least 1, got " + truncateFromIndex);
        }
        requireWellFormedRun("entriesToAppend", entriesToAppend);
        if (truncateFromIndex != null && !entriesToAppend.isEmpty()
                && entriesToAppend.getFirst().index() != truncateFromIndex) {
            throw new IllegalArgumentException("a replacement must begin at truncateFromIndex " + truncateFromIndex
                    + " but begins at " + entriesToAppend.getFirst().index() + ", which would leave a hole or an overlap");
        }
    }

    /**
     * Creates an empty plan (no truncation, no appends).
     */
    public static AppendPlan empty() {
        LOG.debug("No-op append plan requested");
        return new AppendPlan(null, List.of());
    }

    /**
     * Calculates the plan for a log that has never been prefix compacted, which therefore is
     * empty or begins at index 1. For a compacted log use
     * {@link #from(long, List, List, long)}; this overload refuses one.
     *
     * @param startIndex      the log index where incoming entries begin (prevLogIndex + 1)
     * @param incomingEntries the entries from the Leader's AppendEntries RPC; empty for a heartbeat
     * @param currentLog      the current in-memory log
     * @return the calculated plan
     */
    public static AppendPlan from(long startIndex,
                                   List<LogEntryData> incomingEntries,
                                   List<LogEntryData> currentLog) {
        return from(startIndex, incomingEntries, currentLog, 0L);
    }

    /**
     * Calculates the append plan by comparing incoming entries against the current log.
     * <p>
     * Algorithm:
     * <ol>
     *   <li>Walk through incoming entries starting at startIndex</li>
     *   <li>An entry at or below the compaction boundary is in the snapshot: skip it</li>
     *   <li>If we have an entry at this position with matching term, skip it</li>
     *   <li>If we have an entry with different term, mark for truncation from this point</li>
     *   <li>If we don't have an entry, everything from here is new</li>
     * </ol>
     *
     * @param startIndex         the log index where incoming entries begin (prevLogIndex + 1)
     * @param incomingEntries    the entries from the Leader's AppendEntries RPC; empty for a heartbeat
     * @param currentLog         the current in-memory log, which must begin at
     *                           {@code compactionBoundary + 1} when it is not empty
     * @param compactionBoundary inclusive prefix compaction boundary, or 0 if never compacted; see
     *                           {@code FileRaftStorage.compactionBoundary()}
     * @return the calculated plan
     * @throws NullPointerException     if a list or one of its elements is null
     * @throws IllegalArgumentException if the arguments are inconsistent with each other or with
     *                                  the rules of a Raft log; the message says which
     */
    public static AppendPlan from(long startIndex,
                                   List<LogEntryData> incomingEntries,
                                   List<LogEntryData> currentLog,
                                   long compactionBoundary) {
        Objects.requireNonNull(incomingEntries, "incomingEntries must not be null; pass an empty list for a heartbeat");
        Objects.requireNonNull(currentLog, "currentLog must not be null");
        if (startIndex < 1) {
            throw new IllegalArgumentException("startIndex must be at least 1, got " + startIndex);
        }
        if (compactionBoundary < 0) {
            throw new IllegalArgumentException("compactionBoundary must not be negative, got " + compactionBoundary);
        }
        requireWellFormedRun("currentLog", currentLog);
        requireWellFormedRun("incomingEntries", incomingEntries);

        if (!currentLog.isEmpty() && currentLog.getFirst().index() - 1 != compactionBoundary) {
            throw new IllegalArgumentException("currentLog begins at index " + currentLog.getFirst().index()
                    + ", so it was compacted through " + (currentLog.getFirst().index() - 1)
                    + ", but compactionBoundary is " + compactionBoundary);
        }
        if (!incomingEntries.isEmpty() && incomingEntries.getFirst().index() != startIndex) {
            throw new IllegalArgumentException("startIndex is " + startIndex + " but the first incoming entry is at index "
                    + incomingEntries.getFirst().index());
        }
        long lastIndex = currentLog.isEmpty() ? compactionBoundary : currentLog.getLast().index();
        // startIndex is at least 1, so startIndex - 1 cannot underflow; lastIndex + 1 could overflow.
        if (startIndex - 1 > lastIndex) {
            throw new IllegalArgumentException("entries beginning at " + startIndex + " would leave a gap after index "
                    + lastIndex + "; the previous-entry check should have refused this request");
        }

        LOG.debug("Calculating append plan: startIndex={}, incomingEntries={}, currentLog={}, compactionBoundary={}",
                startIndex, incomingEntries.size(), currentLog.size(), compactionBoundary);

        Long truncateAt = null;
        int firstNew = incomingEntries.size();                      // until shown otherwise, nothing is new
        for (int i = 0; i < incomingEntries.size(); i++) {
            LogEntryData incoming = incomingEntries.get(i);
            if (incoming.index() <= compactionBoundary) continue;   // already covered by the snapshot

            long position = incoming.index() - compactionBoundary - 1;
            if (position >= currentLog.size()) {                    // beyond what we hold: new from here on
                firstNew = i;
                break;
            }
            LogEntryData held = currentLog.get((int) position);
            if (held.term() != incoming.term()) {                   // conflict: replace from here on
                truncateAt = incoming.index();
                firstNew = i;
                LOG.debug("Conflict detected at index {}: existingTerm={}, incomingTerm={}; truncating from {}",
                        incoming.index(), held.term(), incoming.term(), truncateAt);
                break;
            }
            if (!Arrays.equals(payloadOf(held), payloadOf(incoming))) {
                // Raft creates at most one entry per index per term. Treating this as "already
                // held" would hide a divergence; truncating would be wrong because the terms agree.
                throw new IllegalArgumentException("entry " + incoming.index() + " has term " + incoming.term()
                        + " in both logs but a different payload; the logs have diverged");
            }
        }

        List<LogEntryData> toAppend = incomingEntries.subList(firstNew, incomingEntries.size());
        if (!toAppend.isEmpty()) {
            long precedingIndex = toAppend.getFirst().index() - 1;
            if (precedingIndex > compactionBoundary) {
                long precedingTerm = currentLog.get((int) (precedingIndex - compactionBoundary - 1)).term();
                if (toAppend.getFirst().term() < precedingTerm) {
                    throw new IllegalArgumentException("entry " + toAppend.getFirst().index() + " has term "
                            + toAppend.getFirst().term() + " but would follow entry " + precedingIndex
                            + " with the higher term " + precedingTerm);
                }
            }
        }

        LOG.debug("Append plan resolved: truncateFromIndex={}, entriesToAppend={}", truncateAt, toAppend.size());
        return new AppendPlan(truncateAt, toAppend);
    }

    /**
     * Applies this plan to the in-memory log.
     * <p>
     * <b>CALL THIS ONLY AFTER WAL PERSISTENCE IS SUCCESSFUL.</b>
     * <p>
     * The log is checked against the plan before anything is changed, so a refused plan leaves it
     * exactly as it was. An empty log accepts the plan as its beginning.
     *
     * @param memoryLog the in-memory log to mutate
     * @throws NullPointerException     if the log is null
     * @throws IllegalArgumentException if the truncation index lies outside the log, or the entries
     *                                  would not continue it
     */
    public void applyTo(List<LogEntryData> memoryLog) {
        Objects.requireNonNull(memoryLog, "memoryLog must not be null");
        int keep = memoryLog.size();
        if (truncateFromIndex != null && !memoryLog.isEmpty()) {
            long position = truncateFromIndex - memoryLog.getFirst().index();
            if (position < 0 || position > memoryLog.size()) {
                throw new IllegalArgumentException("truncateFromIndex " + truncateFromIndex + " lies outside the log, which holds "
                        + memoryLog.getFirst().index() + " to " + memoryLog.getLast().index());
            }
            keep = (int) position;
        }
        if (!entriesToAppend.isEmpty() && keep > 0) {
            long lastKept = memoryLog.get(keep - 1).index();
            // lastKept + 1 is not computed: it could overflow at the top of the index space.
            if (entriesToAppend.getFirst().index() - 1 != lastKept) {
                throw new IllegalArgumentException("entries beginning at " + entriesToAppend.getFirst().index()
                        + " do not continue the log, which would end at " + lastKept);
            }
        }

        if (keep < memoryLog.size()) {
            LOG.debug("Applying append plan: truncating in-memory log from index {}", truncateFromIndex);
            memoryLog.subList(keep, memoryLog.size()).clear();
        }
        LOG.debug("Applying append plan: appending {} entries", entriesToAppend.size());
        memoryLog.addAll(entriesToAppend);
    }

    /**
     * @return true if this plan requires a truncation operation
     */
    public boolean requiresTruncation() {
        return truncateFromIndex != null;
    }

    /**
     * @return true if this plan has entries to append
     */
    public boolean hasEntriesToAppend() {
        return !entriesToAppend.isEmpty();
    }

    /**
     * @return true if this plan requires any WAL operations
     */
    public boolean requiresPersistence() {
        return requiresTruncation() || hasEntriesToAppend();
    }

    /** A null payload and an empty one are the same entry; the storage writes both as empty. */
    private static byte[] payloadOf(LogEntryData entry) {
        return entry.payload() == null ? new byte[0] : entry.payload();
    }

    /** Indices at least 1 and contiguous, terms non-negative and non-decreasing, no null element. */
    private static void requireWellFormedRun(String name, List<LogEntryData> entries) {
        LogEntryData previous = null;
        for (LogEntryData entry : entries) {
            Objects.requireNonNull(entry, name + " must not contain null");
            // Contiguity first, so an index that wrapped past Long.MAX_VALUE is reported as what
            // it is. previous.index() + 1 is never computed, because that is the wrap.
            if (previous != null && (previous.index() == Long.MAX_VALUE || entry.index() - 1 != previous.index())) {
                throw new IllegalArgumentException(name + " is not contiguous: index " + entry.index()
                        + " cannot follow " + previous.index());
            }
            if (entry.index() < 1) {
                throw new IllegalArgumentException(name + " holds index " + entry.index() + "; log indices start at 1");
            }
            if (entry.term() < 0) {
                throw new IllegalArgumentException(name + " entry " + entry.index() + " has the negative term " + entry.term());
            }
            if (previous != null) {
                if (entry.term() < previous.term()) {
                    throw new IllegalArgumentException(name + " entry " + entry.index() + " has term " + entry.term()
                            + " below the term " + previous.term() + " of the entry before it");
                }
            }
            previous = entry;
        }
    }
}
