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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The checker that guards refusal tests must itself notice every kind of change. */
class DurableStateTest {
    @TempDir Path dir;

    private void seed() throws Exception {
        Files.write(dir.resolve("raft.log"), new byte[]{1, 2, 3, 4});
        Files.write(dir.resolve("meta.dat"), new byte[]{9, 9});
        Files.createDirectories(dir.resolve("nested"));
        Files.write(dir.resolve("nested/raft.log"), new byte[]{5});
    }

    private interface Change { void apply() throws Exception; }

    private void assertDetected(Change change) throws Exception {
        seed();
        DurableState watch = DurableState.expectUnchanged(dir);
        change.apply();
        assertThrows(AssertionError.class, watch::close);
    }

    @Test void untouchedDirectoryPasses() throws Exception {
        seed();
        DurableState watch = DurableState.expectUnchanged(dir);
        assertDoesNotThrow(watch::close);
    }

    @Test void appendedBytesAreDetected() throws Exception {
        assertDetected(() -> Files.write(dir.resolve("raft.log"), new byte[]{7}, StandardOpenOption.APPEND));
    }

    @Test void sameSizeContentChangeIsDetected() throws Exception {
        assertDetected(() -> Files.write(dir.resolve("raft.log"), new byte[]{1, 2, 3, 5}));
    }

    @Test void truncationIsDetected() throws Exception {
        assertDetected(() -> Files.write(dir.resolve("raft.log"), new byte[]{1, 2}));
    }

    @Test void newFileIsDetectedIncludingLeftoverStagingFiles() throws Exception {
        assertDetected(() -> Files.write(dir.resolve("meta.dat.tmp"), new byte[]{0}));
    }

    @Test void removedFileIsDetected() throws Exception {
        assertDetected(() -> Files.delete(dir.resolve("meta.dat")));
    }

    @Test void changeInANestedDataDirectoryIsDetected() throws Exception {
        assertDetected(() -> Files.write(dir.resolve("nested/raft.log"), new byte[]{6}));
    }

    @Test void emptyDirectoryGainingAFileIsDetected() throws Exception {
        DurableState watch = DurableState.expectUnchanged(dir);
        Files.write(dir.resolve("raft.log"), new byte[]{1});
        assertThrows(AssertionError.class, watch::close);
    }

    @Test void lockFileIsNotDurableStateAndIsIgnored() throws Exception {
        seed();
        DurableState watch = DurableState.expectUnchanged(dir);
        Files.write(dir.resolve("raft.lock"), new byte[]{1});
        assertDoesNotThrow(watch::close);
    }
}
