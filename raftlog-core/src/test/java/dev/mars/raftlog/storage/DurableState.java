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

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test support for the second half of every refusal claim.
 * <p>
 * "The operation was refused" proves nothing about the disk. A refusal that still wrote a
 * record, left a staging file behind, or truncated something is a defect that only shows
 * up after a restart. This captures every file under a directory and asserts that a block
 * of code left all of them byte-for-byte unchanged, with none added or removed.
 * <pre>{@code
 * try (var untouched = DurableState.expectUnchanged(tempDir)) {
 *     assertRejected(storage.appendEntries(bad), INDEX_NOT_CONTIGUOUS);
 * }
 * }</pre>
 * The lock file is ignored: it is not durable state and is held open by the instance.
 */
final class DurableState implements AutoCloseable {
    private static final String LOCK_FILE = "raft.lock";

    private final Path root;
    private final Map<String, String> before;

    private DurableState(Path root) {
        this.root = root;
        this.before = snapshot(root);
    }

    /** Captures {@code root} now; {@link #close()} asserts nothing under it has changed. */
    static DurableState expectUnchanged(Path root) {
        return new DurableState(root);
    }

    @Override
    public void close() {
        assertEquals(describe(before), describe(snapshot(root)),
                "a refused operation must leave every durable file untouched");
    }

    /** Relative path to "size:sha256" for every regular file under root, except lock files. */
    static Map<String, String> snapshot(Path root) {
        Map<String, String> files = new TreeMap<>();
        if (!Files.exists(root)) return files;
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path file : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                if (file.getFileName().toString().equals(LOCK_FILE)) continue;
                files.put(root.relativize(file).toString().replace('\\', '/'),
                        Files.size(file) + ":" + sha256(file));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return files;
    }

    private static String describe(Map<String, String> files) {
        StringBuilder out = new StringBuilder();
        files.forEach((name, digest) -> out.append(name).append(" = ").append(digest).append('\n'));
        return out.toString();
    }

    private static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            for (int read; (read = in.read(buffer)) > 0; ) digest.update(buffer, 0, read);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Opens a fresh instance on {@code dataDir}, replays it, and checks that the result is a
     * well-formed Raft log. The caller must have closed any other instance on the directory.
     *
     * @return the replayed entries, for further assertions
     */
    static List<LogEntryData> replayAfterRestart(Path dataDir) throws Exception {
        FileRaftStorage fresh = new FileRaftStorage(RaftStorageConfig.builder().build());
        fresh.open(dataDir).get(10, TimeUnit.SECONDS);
        try {
            List<LogEntryData> entries = fresh.replayLog().get(10, TimeUnit.SECONDS);
            assertWellFormed(entries);
            return entries;
        } finally {
            fresh.closeAsync().get(10, TimeUnit.SECONDS);
        }
    }

    /**
     * The live instance and a fresh instance after a restart must see the same log. Closes
     * {@code live}. Use at the end of a refusal test: "refused" has to mean that nothing is
     * different after a reboot either.
     */
    static List<LogEntryData> assertRestartAgrees(FileRaftStorage live, Path dataDir) throws Exception {
        List<LogEntryData> liveView = live.replayLog().get(10, TimeUnit.SECONDS);
        assertWellFormed(liveView);
        live.closeAsync().get(10, TimeUnit.SECONDS);
        List<LogEntryData> restarted = replayAfterRestart(dataDir);
        assertEquals(fingerprint(liveView), fingerprint(restarted),
                "the log after a restart must match what the live instance replayed");
        return restarted;
    }

    private static List<String> fingerprint(List<LogEntryData> entries) {
        return entries.stream()
                .map(e -> e.index() + "@" + e.term() + "#" + java.util.Arrays.hashCode(e.payload()) + "/" + e.payload().length)
                .toList();
    }

    /** Contiguous indices and non-decreasing terms. */
    static void assertWellFormed(List<LogEntryData> entries) {
        for (int i = 1; i < entries.size(); i++) {
            LogEntryData previous = entries.get(i - 1);
            LogEntryData current = entries.get(i);
            assertEquals(previous.index() + 1, current.index(), "replayed log must be contiguous");
            assertTrue(current.term() >= previous.term(), "replayed terms must not decrease at index " + current.index());
        }
    }
}
