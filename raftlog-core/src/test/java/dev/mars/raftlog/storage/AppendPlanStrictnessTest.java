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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AppendPlan is not lenient. It is a pure function over data the node already holds, so any
 * inconsistency in its arguments is a bug in the caller, and a plan built from inconsistent
 * arguments would be refused by the storage later, further from the cause. It fails here instead.
 * <p>
 * What stays legal is what Raft itself permits: a heartbeat with no entries, entries the follower
 * already has, entries already covered by its snapshot, and a conflicting tail.
 */
class AppendPlanStrictnessTest {
    private static LogEntryData e(long index, long term) {
        return new LogEntryData(index, term, ("p" + index + "@" + term).getBytes());
    }

    private static List<LogEntryData> log(long... indexThenTerm) {
        List<LogEntryData> entries = new ArrayList<>();
        for (int i = 0; i < indexThenTerm.length; i += 2) entries.add(e(indexThenTerm[i], indexThenTerm[i + 1]));
        return entries;
    }

    private static void assertRefused(String expectedMessagePart, org.junit.jupiter.api.function.Executable call) {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, call);
        assertTrue(refused.getMessage().contains(expectedMessagePart),
                "message should mention '" + expectedMessagePart + "' but was: " + refused.getMessage());
    }

    private static List<String> view(List<LogEntryData> entries) {
        return entries.stream().map(x -> x.index() + "@" + x.term()).toList();
    }

    @Nested class Arguments {
        @Test void nullIncomingEntriesAreRefused() {
            assertThrows(NullPointerException.class, () -> AppendPlan.from(1, null, log()));
        }

        @Test void nullCurrentLogIsRefused() {
            assertThrows(NullPointerException.class, () -> AppendPlan.from(1, log(1, 1), null));
        }

        @Test void nullElementInIncomingEntriesIsRefused() {
            assertThrows(NullPointerException.class, () -> AppendPlan.from(1, Arrays.asList(e(1, 1), null), log()));
        }

        @Test void nullElementInCurrentLogIsRefused() {
            assertThrows(NullPointerException.class, () -> AppendPlan.from(3, log(3, 1), Arrays.asList(e(1, 1), null)));
        }

        @ParameterizedTest
        @ValueSource(longs = {0, -1, Long.MIN_VALUE})
        void startIndexBelowOneIsRefused(long startIndex) {
            assertRefused("startIndex", () -> AppendPlan.from(startIndex, log(), log()));
        }

        @Test void negativeCompactionBoundaryIsRefused() {
            assertRefused("compactionBoundary", () -> AppendPlan.from(1, log(1, 1), log(), -1));
        }
    }

    @Nested class IncomingEntries {
        @Test void entriesWhoseOwnIndexDisagreesWithTheStartIndexAreRefused() {
            assertRefused("startIndex", () -> AppendPlan.from(2, log(1, 1, 2, 1), log(1, 1)));
            assertRefused("startIndex", () -> AppendPlan.from(1, log(2, 1), log()));
        }

        @Test void gapInsideTheIncomingEntriesIsRefused() {
            assertRefused("contiguous", () -> AppendPlan.from(1, log(1, 1, 3, 1), log()));
        }

        @Test void duplicateIndexInsideTheIncomingEntriesIsRefused() {
            assertRefused("contiguous", () -> AppendPlan.from(1, log(1, 1, 1, 1), log()));
        }

        @Test void decreasingTermInsideTheIncomingEntriesIsRefused() {
            assertRefused("term", () -> AppendPlan.from(1, log(1, 3, 2, 2), log()));
        }

        @ParameterizedTest
        @ValueSource(longs = {0, -1, Long.MIN_VALUE})
        void entryIndexBelowOneIsRefusedWhereverItAppears(long index) {
            // Reached through the record constructor, where no start index screens it first.
            assertRefused("start at 1", () -> new AppendPlan(null, List.of(e(index, 1))));
            // And through the current log, which has no start index at all.
            assertRefused("start at 1", () -> AppendPlan.from(1, log(), List.of(e(index, 1))));
        }

        @Test void negativeTermIsRefused() {
            assertRefused("term", () -> AppendPlan.from(1, log(1, -1), log()));
        }

        @Test void indicesThatWrapPastTheTopOfTheIndexSpaceAreRefused() {
            // Long.MAX_VALUE + 1 wraps to Long.MIN_VALUE. That is overflow, not the next index.
            List<LogEntryData> wrapping = log(Long.MAX_VALUE, 1, Long.MIN_VALUE, 1);
            assertRefused("contiguous", () -> AppendPlan.from(Long.MAX_VALUE, wrapping, log(), Long.MAX_VALUE - 1));
        }

        @Test void lastPossibleIndexIsStillPlannable() {
            AppendPlan plan = AppendPlan.from(Long.MAX_VALUE, log(Long.MAX_VALUE, 1), log(), Long.MAX_VALUE - 1);
            assertEquals(List.of(Long.MAX_VALUE + "@1"), view(plan.entriesToAppend()));
        }
    }

    @Nested class CurrentLog {
        @Test void gapInsideTheCurrentLogIsRefused() {
            assertRefused("currentLog", () -> AppendPlan.from(4, log(4, 1), log(1, 1, 3, 1)));
        }

        @Test void decreasingTermInsideTheCurrentLogIsRefused() {
            assertRefused("currentLog", () -> AppendPlan.from(3, log(3, 5), log(1, 5, 2, 3)));
        }

        @Test void logThatStartsAboveOneWithoutABoundaryIsRefused() {
            // A log beginning at 7 was compacted through 6. The caller must say so: without the
            // boundary the plan cannot tell snapshot entries from missing ones.
            assertRefused("compactionBoundary", () -> AppendPlan.from(9, log(9, 1), log(7, 1, 8, 1)));
            assertRefused("compactionBoundary", () -> AppendPlan.from(9, log(9, 1), log(7, 1, 8, 1), 5));
        }

        @Test void logThatStartsAtOneCannotHaveABoundary() {
            assertRefused("compactionBoundary", () -> AppendPlan.from(3, log(3, 1), log(1, 1, 2, 1), 4));
        }

        @Test void logThatStartsRightAfterItsBoundaryIsAccepted() {
            AppendPlan plan = AppendPlan.from(9, log(9, 1), log(7, 1, 8, 1), 6);
            assertEquals(List.of("9@1"), view(plan.entriesToAppend()));
            assertFalse(plan.requiresTruncation());
        }
    }

    @Nested class Continuity {
        @Test void entriesThatWouldLeaveAGapAfterTheLogAreRefused() {
            assertRefused("gap", () -> AppendPlan.from(4, log(4, 1), log(1, 1, 2, 1)));
        }

        @Test void entriesThatWouldLeaveAGapInAFreshLogAreRefused() {
            assertRefused("gap", () -> AppendPlan.from(2, log(2, 1), log()));
        }

        @Test void entriesThatWouldLeaveAGapAfterTheSnapshotAreRefused() {
            assertRefused("gap", () -> AppendPlan.from(7, log(7, 1), log(), 5));
            assertEquals(List.of("6@1"), view(AppendPlan.from(6, log(6, 1), log(), 5).entriesToAppend()));
        }

        @Test void heartbeatIsLegalButItsPositionIsStillChecked() {
            assertFalse(AppendPlan.from(3, log(), log(1, 1, 2, 1)).requiresPersistence());
            assertFalse(AppendPlan.from(1, log(), log()).requiresPersistence());
            assertRefused("gap", () -> AppendPlan.from(9, log(), log(1, 1, 2, 1)));
        }

        @Test void newEntryWithALowerTermThanTheEntryItFollowsIsRefused() {
            assertRefused("term", () -> AppendPlan.from(2, log(2, 3), log(1, 5)));
        }

        @Test void replacementWithALowerTermThanTheRetainedEntryBeforeItIsRefused() {
            // 2@5 conflicts with 2@3 and would be truncated, but 1@4 stays, and 3 cannot follow 4.
            assertRefused("term", () -> AppendPlan.from(2, log(2, 3), log(1, 4, 2, 5)));
        }

        @Test void conflictingTailIsStillReplaced() {
            AppendPlan plan = AppendPlan.from(2, log(2, 3, 3, 3), log(1, 1, 2, 2, 3, 2));
            assertEquals(2L, plan.truncateFromIndex());
            assertEquals(List.of("2@3", "3@3"), view(plan.entriesToAppend()));
        }

        @Test void entriesAlreadyHeldProduceAnEmptyPlan() {
            assertFalse(AppendPlan.from(1, log(1, 1, 2, 1), log(1, 1, 2, 1, 3, 1)).requiresPersistence());
        }

        @Test void entriesCoveredByTheSnapshotAreSkippedNotRefused() {
            AppendPlan plan = AppendPlan.from(4, log(4, 1, 5, 1, 6, 1, 7, 1), log(6, 1), 5);
            assertFalse(plan.requiresTruncation());
            assertEquals(List.of("7@1"), view(plan.entriesToAppend()));
            assertFalse(AppendPlan.from(2, log(2, 1, 3, 1), log(), 5).requiresPersistence());
        }
    }

    @Nested class LogMatching {
        @Test void sameIndexAndTermWithADifferentPayloadMeansTheLogsHaveDivergedAndIsRefused() {
            // Raft guarantees one entry per index per term. Skipping this entry as "already held"
            // would hide the divergence; truncating would be wrong because the terms agree.
            List<LogEntryData> held = List.of(new LogEntryData(1, 1, "SET x 1".getBytes()));
            List<LogEntryData> sent = List.of(new LogEntryData(1, 1, "SET x 2".getBytes()));
            assertRefused("payload", () -> AppendPlan.from(1, sent, held));
        }

        @Test void nullAndEmptyPayloadAreTheSameEntry() {
            List<LogEntryData> held = List.of(new LogEntryData(1, 1, null));
            List<LogEntryData> sent = List.of(new LogEntryData(1, 1, new byte[0]));
            assertFalse(AppendPlan.from(1, sent, held).requiresPersistence());
        }
    }

    @Nested class Constructor {
        @Test void nullEntriesAreRefused() {
            assertThrows(NullPointerException.class, () -> new AppendPlan(null, null));
        }

        @Test void nullElementIsRefused() {
            assertThrows(NullPointerException.class, () -> new AppendPlan(null, Arrays.asList(e(1, 1), null)));
        }

        @ParameterizedTest
        @ValueSource(longs = {0, -1, Long.MIN_VALUE})
        void truncationIndexBelowOneIsRefused(long from) {
            assertRefused("truncateFromIndex", () -> new AppendPlan(from, List.of()));
        }

        @Test void nonContiguousEntriesAreRefused() {
            assertRefused("contiguous", () -> new AppendPlan(null, log(1, 1, 3, 1)));
        }

        @Test void decreasingTermsAreRefused() {
            assertRefused("term", () -> new AppendPlan(null, log(1, 2, 2, 1)));
        }

        @Test void replacementThatDoesNotStartAtTheTruncationIndexIsRefused() {
            // Truncating from 5 and appending from 7 leaves a hole at 5 and 6.
            assertRefused("truncateFromIndex", () -> new AppendPlan(5L, log(7, 1)));
            assertRefused("truncateFromIndex", () -> new AppendPlan(5L, log(3, 1)));
        }

        @Test void truncationOnlyAppendOnlyAndBothAreLegal() {
            assertTrue(new AppendPlan(5L, List.of()).requiresTruncation());
            assertTrue(new AppendPlan(null, log(1, 1)).hasEntriesToAppend());
            assertTrue(new AppendPlan(5L, log(5, 2, 6, 2)).requiresPersistence());
            assertFalse(AppendPlan.empty().requiresPersistence());
        }
    }

    @Nested class ApplyTo {
        @Test void nullLogIsRefused() {
            assertThrows(NullPointerException.class, () -> new AppendPlan(null, log(1, 1)).applyTo(null));
        }

        @Test void truncationBeyondTheEndOfTheLogIsRefusedAndTheLogIsUntouched() {
            List<LogEntryData> memory = log(1, 1, 2, 1);
            assertRefused("truncateFromIndex", () -> new AppendPlan(9L, List.of()).applyTo(memory));
            assertEquals(List.of("1@1", "2@1"), view(memory));
        }

        @Test void truncationBelowTheFirstEntryOfTheLogIsRefusedAndTheLogIsUntouched() {
            List<LogEntryData> memory = log(7, 1, 8, 1);
            assertRefused("truncateFromIndex", () -> new AppendPlan(3L, log(3, 1)).applyTo(memory));
            assertEquals(List.of("7@1", "8@1"), view(memory));
        }

        @Test void entriesThatDoNotContinueTheLogAreRefusedAndTheLogIsUntouched() {
            List<LogEntryData> memory = log(1, 1, 2, 1);
            assertRefused("continue", () -> new AppendPlan(null, log(4, 1)).applyTo(memory));
            assertRefused("continue", () -> new AppendPlan(null, log(2, 1)).applyTo(memory));
            assertEquals(List.of("1@1", "2@1"), view(memory));
        }

        @Test void truncateThenAppendAppliesAtomically() {
            List<LogEntryData> memory = log(1, 1, 2, 1, 3, 1);
            new AppendPlan(2L, log(2, 2, 3, 2, 4, 2)).applyTo(memory);
            assertEquals(List.of("1@1", "2@2", "3@2", "4@2"), view(memory));
        }

        @Test void truncatingExactlyAtTheEndAndAppendingIsLegal() {
            List<LogEntryData> memory = log(1, 1, 2, 1);
            new AppendPlan(3L, log(3, 1)).applyTo(memory);
            assertEquals(List.of("1@1", "2@1", "3@1"), view(memory));
        }

        @Test void anEmptyLogAcceptsThePlanAsItsBeginning() {
            List<LogEntryData> memory = new ArrayList<>();
            new AppendPlan(6L, log(6, 2)).applyTo(memory);
            assertEquals(List.of("6@2"), view(memory));
        }
    }
}
