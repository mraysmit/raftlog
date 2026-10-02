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

import dev.mars.raftlog.storage.FileRaftStorage.StorageException;
import dev.mars.raftlog.storage.RaftStorage.LogEntryData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Behaviour that depends on POSIX permissions and symbolic links, which Windows cannot express.
 * <p>
 * The whole class is skipped on other platforms, so a Windows build reports three skipped tests.
 * They must run before a release: on Linux, as an unprivileged user, because root ignores
 * read-only permissions and the permission test then skips itself.
 */
@EnabledOnOs(OS.LINUX)
class FileRaftStorageLinuxTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Read-only parent directory prevents WAL creation")
    void testReadOnlyParentBlocksDirectoryCreation() throws Exception {
        Path readOnlyDir = Files.createTempDirectory("raftlog-readonly");
        readOnlyDir.toFile().setWritable(false);
        try {
            // Skip if running as root — root bypasses POSIX permission enforcement
            assumeFalse(readOnlyDir.toFile().canWrite(),
                    "Skipped: read-only enforcement not available (running as root?)");

            FileRaftStorage storage = new FileRaftStorage(true);
            Path target = readOnlyDir.resolve("subdir");

            ExecutionException ex = assertThrows(ExecutionException.class,
                    () -> storage.open(target).get(5, TimeUnit.SECONDS));

            assertTrue(ex.getCause() instanceof StorageException);
            storage.close();
        } finally {
            readOnlyDir.toFile().setWritable(true);
            Files.deleteIfExists(readOnlyDir);
        }
    }

    @Test
    @DisplayName("raft.lock is not world-writable or group-writable after open")
    void testLockFileIsNotWorldWritable() throws Exception {
        FileRaftStorage storage = new FileRaftStorage(true);
        storage.open(tempDir).get(5, TimeUnit.SECONDS);
        try {
            Path lockFile = tempDir.resolve("raft.lock");
            assertTrue(Files.exists(lockFile), "raft.lock should exist after open");

            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(lockFile);
            assertFalse(perms.contains(PosixFilePermission.OTHERS_WRITE),
                    "raft.lock must not be world-writable");
            assertFalse(perms.contains(PosixFilePermission.GROUP_WRITE),
                    "raft.lock must not be group-writable");
        } finally {
            storage.close();
        }
    }

    @Test
    @DisplayName("WAL opens and replays correctly via a symlink to the data directory")
    void testSymlinkDataDirectory() throws Exception {
        Path realDir = Files.createTempDirectory("raftlog-real");
        Path symlinkParent = Files.createTempDirectory("raftlog-symlink-parent");
        Path symlink = symlinkParent.resolve("link");
        Files.createSymbolicLink(symlink, realDir);
        try {
            FileRaftStorage storage = new FileRaftStorage(true);
            storage.open(symlink).get(5, TimeUnit.SECONDS);

            List<LogEntryData> entries = List.of(new LogEntryData(1, 1, "data".getBytes()));
            storage.appendEntries(entries).get(5, TimeUnit.SECONDS);
            storage.sync().get(5, TimeUnit.SECONDS);

            List<LogEntryData> replayed = storage.replayLog().get(5, TimeUnit.SECONDS);
            assertEquals(1, replayed.size());
            storage.close();
        } finally {
            Files.deleteIfExists(symlink);
            Files.deleteIfExists(symlinkParent);
            Files.deleteIfExists(realDir.resolve("raft.lock"));
            Files.deleteIfExists(realDir.resolve("raft.log"));
            Files.deleteIfExists(realDir.resolve("meta.dat"));
            Files.deleteIfExists(realDir);
        }
    }
}
