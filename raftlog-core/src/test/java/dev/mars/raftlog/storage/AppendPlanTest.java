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
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link AppendPlan}.
 * <p>
 * These tests verify the plan calculation logic for various scenarios:
 * <ul>
 *   <li>Appending to an empty log</li>
 *   <li>Appending new entries beyond current log</li>
 *   <li>Skipping already-existing entries</li>
 *   <li>Detecting and handling conflicts</li>
 * </ul>
 */
class AppendPlanTest {

    // ========================================================================
    // Empty / No-Op Cases
    // ========================================================================

    @Test
    void testFrom_NullEntries_ReturnsEmptyPlan() {
        List<LogEntryData> currentLog = new ArrayList<>();

        AppendPlan plan = AppendPlan.from(1, null, currentLog);

        assertFalse(plan.requiresTruncation());
        assertFalse(plan.hasEntriesToAppend());
        assertFalse(plan.requiresPersistence());
    }

    @Test
    void testFrom_EmptyEntries_ReturnsEmptyPlan() {
        List<LogEntryData> currentLog = new ArrayList<>();

        AppendPlan plan = AppendPlan.from(1, List.of(), currentLog);

        assertFalse(plan.requiresTruncation());
        assertFalse(plan.hasEntriesToAppend());
    }

    @Test
    void testEmpty_ReturnsEmptyPlan() {
        AppendPlan plan = AppendPlan.empty();

        assertNull(plan.truncateFromIndex());
        assertTrue(plan.entriesToAppend().isEmpty());
    }

    // ========================================================================
    // Append to Empty Log
    // ========================================================================

    @Test
    void testFrom_AppendToEmptyLog() {
        List<LogEntryData> currentLog = new ArrayList<>();
        List<LogEntryData> incoming = List.of(
                new LogEntryData(1, 1, "cmd-1".getBytes()),
                new LogEntryData(2, 1, "cmd-2".getBytes())
        );

        AppendPlan plan = AppendPlan.from(1, incoming, currentLog);

        assertNull(plan.truncateFromIndex());
        assertEquals(2, plan.entriesToAppend().size());
        assertEquals(1, plan.entriesToAppend().get(0).index());
        assertEquals(2, plan.entriesToAppend().get(1).index());
    }

    // ========================================================================
    // Append Beyond Current Log
    // ========================================================================

    @Test
    void testFrom_AppendBeyondCurrentLog() {
        List<LogEntryData> currentLog = new ArrayList<>();
        currentLog.add(new LogEntryData(1, 1, "existing-1".getBytes()));

        List<LogEntryData> incoming = List.of(
                new LogEntryData(2, 1, "new-2".getBytes()),
                new LogEntryData(3, 1, "new-3".getBytes())
        );

        AppendPlan plan = AppendPlan.from(2, incoming, currentLog);

        assertNull(plan.truncateFromIndex());
        assertEquals(2, plan.entriesToAppend().size());
        assertEquals(2, plan.entriesToAppend().get(0).index());
        assertEquals(3, plan.entriesToAppend().get(1).index());
    }

    // ========================================================================
    // Skip Already Existing Entries
    // ========================================================================

    @Test
    void testFrom_AllEntriesExist_NoOp() {
        List<LogEntryData> currentLog = new ArrayList<>();
        currentLog.add(new LogEntryData(1, 1, "cmd-1".getBytes()));
        currentLog.add(new LogEntryData(2, 1, "cmd-2".getBytes()));

        // Send the same entries again
        List<LogEntryData> incoming = List.of(
                new LogEntryData(1, 1, "cmd-1".getBytes()),
                new LogEntryData(2, 1, "cmd-2".getBytes())
        );

        AppendPlan plan = AppendPlan.from(1, incoming, currentLog);

        assertNull(plan.truncateFromIndex());
        assertTrue(plan.entriesToAppend().isEmpty());
        assertFalse(plan.requiresPersistence());
    }

    @Test
    void testFrom_SomeEntriesExist_AppendsOnlyNew() {
        List<LogEntryData> currentLog = new ArrayList<>();
        currentLog.add(new LogEntryData(1, 1, "cmd-1".getBytes()));

        List<LogEntryData> incoming = List.of(
                new LogEntryData(1, 1, "cmd-1".getBytes()), // exists
                new LogEntryData(2, 1, "cmd-2".getBytes()), // new
                new LogEntryData(3, 1, "cmd-3".getBytes())  // new
        );

        AppendPlan plan = AppendPlan.from(1, incoming, currentLog);

        assertNull(plan.truncateFromIndex());
        assertEquals(2, plan.entriesToAppend().size());
        assertEquals(2, plan.entriesToAppend().get(0).index());
        assertEquals(3, plan.entriesToAppend().get(1).index());
    }

    // ========================================================================
    // Conflict Detection (Term Mismatch)
    // ========================================================================

    @Test
    void testFrom_ConflictAtIndex_TruncatesAndAppends() {
        List<LogEntryData> currentLog = new ArrayList<>();
        currentLog.add(new LogEntryData(1, 1, "cmd-1".getBytes()));
        currentLog.add(new LogEntryData(2, 1, "old-cmd-2".getBytes())); // term 1
        currentLog.add(new LogEntryData(3, 1, "old-cmd-3".getBytes())); // term 1

        // Leader sends entries with different term at index 2
        List<LogEntryData> incoming = List.of(
                new LogEntryData(2, 2, "new-cmd-2".getBytes()), // term 2 - conflict!
                new LogEntryData(3, 2, "new-cmd-3".getBytes())
        );

        AppendPlan plan = AppendPlan.from(2, incoming, currentLog);

        assertEquals(Long.valueOf(2), plan.truncateFromIndex());
        assertEquals(2, plan.entriesToAppend().size());
        assertEquals(2, plan.entriesToAppend().get(0).term());
    }

    @Test
    void testFrom_ConflictAtFirstEntry() {
        List<LogEntryData> currentLog = new ArrayList<>();
        currentLog.add(new LogEntryData(1, 1, "old".getBytes())); // term 1

        // Leader has term 2 for index 1
        List<LogEntryData> incoming = List.of(
                new LogEntryData(1, 2, "new".getBytes())
        );

        AppendPlan plan = AppendPlan.from(1, incoming, currentLog);

        assertEquals(Long.valueOf(1), plan.truncateFromIndex());
        assertEquals(1, plan.entriesToAppend().size());
    }

    @Test
    void testFrom_ConflictAfterMatchingEntries() {
        List<LogEntryData> currentLog = new ArrayList<>();
        currentLog.add(new LogEntryData(1, 1, "cmd-1".getBytes()));
        currentLog.add(new LogEntryData(2, 1, "cmd-2".getBytes())); // term 1
        currentLog.add(new LogEntryData(3, 1, "old-3".getBytes())); // term 1 - conflict

        List<LogEntryData> incoming = List.of(
                new LogEntryData(2, 1, "cmd-2".getBytes()), // matches
                new LogEntryData(3, 2, "new-3".getBytes()), // term 2 - conflict
                new LogEntryData(4, 2, "new-4".getBytes())
        );

        AppendPlan plan = AppendPlan.from(2, incoming, currentLog);

        assertEquals(Long.valueOf(3), plan.truncateFromIndex());
        assertEquals(2, plan.entriesToAppend().size());
        assertEquals(3, plan.entriesToAppend().get(0).index());
        assertEquals(4, plan.entriesToAppend().get(1).index());
    }

    // ========================================================================
    // applyTo Tests
    // ========================================================================

    @Test
    void testApplyTo_AppendOnly() {
        List<LogEntryData> memoryLog = new ArrayList<>();
        memoryLog.add(new LogEntryData(1, 1, "a".getBytes()));

        AppendPlan plan = new AppendPlan(null, List.of(
                new LogEntryData(2, 1, "b".getBytes()),
                new LogEntryData(3, 1, "c".getBytes())
        ));

        plan.applyTo(memoryLog);

        assertEquals(3, memoryLog.size());
        assertEquals(1, memoryLog.get(0).index());
        assertEquals(2, memoryLog.get(1).index());
        assertEquals(3, memoryLog.get(2).index());
    }

    @Test
    void testApplyTo_TruncateAndAppend() {
        List<LogEntryData> memoryLog = new ArrayList<>();
        memoryLog.add(new LogEntryData(1, 1, "a".getBytes()));
        memoryLog.add(new LogEntryData(2, 1, "old-b".getBytes()));
        memoryLog.add(new LogEntryData(3, 1, "old-c".getBytes()));

        AppendPlan plan = new AppendPlan(2L, List.of(
                new LogEntryData(2, 2, "new-b".getBytes()),
                new LogEntryData(3, 2, "new-c".getBytes())
        ));

        plan.applyTo(memoryLog);

        assertEquals(3, memoryLog.size());
        assertEquals(1, memoryLog.get(0).term()); // unchanged
        assertEquals(2, memoryLog.get(1).term()); // replaced
        assertEquals(2, memoryLog.get(2).term()); // replaced
    }

    @Test
    void testApplyTo_TruncateOnly() {
        List<LogEntryData> memoryLog = new ArrayList<>();
        memoryLog.add(new LogEntryData(1, 1, "a".getBytes()));
        memoryLog.add(new LogEntryData(2, 1, "b".getBytes()));
        memoryLog.add(new LogEntryData(3, 1, "c".getBytes()));

        AppendPlan plan = new AppendPlan(2L, List.of());

        plan.applyTo(memoryLog);

        assertEquals(1, memoryLog.size());
        assertEquals(1, memoryLog.get(0).index());
    }

    @Test
    void testApplyTo_EmptyPlan_NoChanges() {
        List<LogEntryData> memoryLog = new ArrayList<>();
        memoryLog.add(new LogEntryData(1, 1, "a".getBytes()));

        AppendPlan plan = AppendPlan.empty();

        plan.applyTo(memoryLog);

        assertEquals(1, memoryLog.size());
    }

    // ========================================================================
    // Immutability Tests
    // ========================================================================

    @Test
    void testEntriesToAppend_IsImmutable() {
        List<LogEntryData> entries = new ArrayList<>();
        entries.add(new LogEntryData(1, 1, "a".getBytes()));

        AppendPlan plan = new AppendPlan(null, entries);

        // Modifying original list should not affect plan
        entries.add(new LogEntryData(2, 1, "b".getBytes()));

        assertEquals(1, plan.entriesToAppend().size());
    }
}
