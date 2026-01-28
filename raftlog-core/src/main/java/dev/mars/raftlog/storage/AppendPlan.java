package dev.mars.raftlog.storage;

import dev.mars.raftlog.storage.RaftStorage.LogEntryData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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
 * <b>Usage Pattern (Prepare → Persist → Apply):</b>
 * <pre>{@code
 * // 1. Calculate the plan (no mutations)
 * AppendPlan plan = AppendPlan.from(startIndex, incomingEntries, currentLog);
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

    /**
     * Creates an AppendPlan with defensive copy of entries.
     */
    public AppendPlan {
        entriesToAppend = entriesToAppend == null
                ? Collections.emptyList()
                : List.copyOf(entriesToAppend);
    }

    /**
     * Creates an empty plan (no truncation, no appends).
     */
    public static AppendPlan empty() {
        return new AppendPlan(null, Collections.emptyList());
    }

    /**
     * Calculates the append plan by comparing incoming entries against the current log.
     * <p>
     * Algorithm:
     * <ol>
     *   <li>Walk through incoming entries starting at startIndex</li>
     *   <li>If we have an entry at this position with matching term, skip it</li>
     *   <li>If we have an entry with different term, mark for truncation from this point</li>
     *   <li>If we don't have an entry, everything from here is new</li>
     * </ol>
     *
     * @param startIndex      the log index where incoming entries begin (prevLogIndex + 1)
     * @param incomingEntries the entries from the Leader's AppendEntries RPC
     * @param currentLog      the current in-memory log (index 0 = log entry at index 1)
     * @return the calculated plan
     */
    public static AppendPlan from(long startIndex,
                                   List<LogEntryData> incomingEntries,
                                   List<LogEntryData> currentLog) {
        if (incomingEntries == null || incomingEntries.isEmpty()) {
            return empty();
        }

        Long truncateAt = null;
        int firstNewEntryIdx = 0;

        // Walk through incoming entries to find divergence point
        for (int i = 0; i < incomingEntries.size(); i++) {
            long logIndex = startIndex + i;
            int logPos = (int) (logIndex - 1); // Convert to 0-based array index

            if (logPos < 0) {
                // Invalid index, treat everything as new
                firstNewEntryIdx = i;
                break;
            }

            if (logPos < currentLog.size()) {
                // We have an entry at this position - check for conflict
                LogEntryData existing = currentLog.get(logPos);
                LogEntryData incoming = incomingEntries.get(i);

                if (existing.term() != incoming.term()) {
                    // Conflict! Truncate from here and append the rest
                    truncateAt = logIndex;
                    firstNewEntryIdx = i;
                    break;
                }
                // Terms match - this entry already exists, skip it
                firstNewEntryIdx = i + 1;
            } else {
                // We don't have this entry - everything from here is new
                firstNewEntryIdx = i;
                break;
            }
        }

        // Build the list of entries to append
        List<LogEntryData> toAppend;
        if (firstNewEntryIdx >= incomingEntries.size()) {
            // All entries already exist
            toAppend = Collections.emptyList();
        } else {
            toAppend = new ArrayList<>(incomingEntries.subList(firstNewEntryIdx, incomingEntries.size()));
        }

        return new AppendPlan(truncateAt, toAppend);
    }

    /**
     * Applies this plan to the in-memory log.
     * <p>
     * <b>CALL THIS ONLY AFTER WAL PERSISTENCE IS SUCCESSFUL.</b>
     * <p>
     * This method mutates the provided list by:
     * <ol>
     *   <li>Truncating entries from truncateFromIndex (if set)</li>
     *   <li>Appending all new entries</li>
     * </ol>
     *
     * @param memoryLog the in-memory log to mutate
     */
    public void applyTo(List<LogEntryData> memoryLog) {
        // Step 1: Truncate if needed
        if (truncateFromIndex != null) {
            int fromPos = (int) (truncateFromIndex - 1); // Convert to 0-based
            if (fromPos >= 0 && fromPos < memoryLog.size()) {
                memoryLog.subList(fromPos, memoryLog.size()).clear();
            }
        }

        // Step 2: Append new entries
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
}
