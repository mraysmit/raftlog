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
package dev.mars.raftlog.demo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Runs the chaos suite as part of the build.
 * <p>
 * WalChaos was only ever a program somebody had to remember to launch, so the build could be
 * green while every chaos scenario was failing. Its scenarios are tests, and tests that the build
 * does not run are not protecting anything. The expected count of each category is pinned, so a
 * scenario that is deleted, or silently stops being registered, fails the build too.
 */
class WalChaosTest {
    @ParameterizedTest(name = "{0}: {1} scenarios")
    @CsvSource({"concurrent, 6", "corruption, 8", "boundary, 9", "stress, 5", "nasty, 10"})
    void everyScenarioInTheCategoryPasses(String category, int expectedScenarios) throws Exception {
        WalChaos.Summary summary = WalChaos.run(category);
        assertEquals(0, summary.failed(), category + " scenarios failed; see the log above for which");
        assertEquals(expectedScenarios, summary.passed(), "number of " + category + " scenarios");
    }

    @Test
    void theWholeSuitePasses() throws Exception {
        WalChaos.Summary summary = WalChaos.run("all");
        assertEquals(0, summary.failed());
        assertEquals(38, summary.passed(), "all is every category: 6 + 8 + 9 + 5 + 10");
    }

    @Test
    void unknownCategoryIsRefusedRatherThanRunningNothingAndReportingSuccess() {
        assertThrows(IllegalArgumentException.class, () -> WalChaos.run("everything"));
        assertThrows(IllegalArgumentException.class, () -> WalChaos.run(""));
    }
}
