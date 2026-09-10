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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Package-private filesystem seam for the durability-critical calls made by
 * {@link FileRaftStorage}. Tests subclass it to inject failures at real
 * filesystem boundaries without a mocking framework.
 * <p>
 * {@link #force(FileChannel)} is used only for the compaction output file.
 * {@link #forceChannel(FileChannel)} is used for the live WAL, the metadata
 * staging file and read-after-write verification, so that a test can fail
 * one path without failing the other.
 */
class CompactionIo {
    private static final Logger LOG = LoggerFactory.getLogger(CompactionIo.class);

    void write(FileChannel channel, ByteBuffer bytes) throws IOException {
        LOG.trace("Writing WAL bytes: remaining={}", bytes.remaining());
        while (bytes.hasRemaining()) channel.write(bytes);
        LOG.trace("Completed write to channel {}", channel);
    }

    /** Forces the compaction output file. */
    void force(FileChannel channel) throws IOException {
        LOG.trace("Forcing compaction output channel {}", channel);
        channel.force(true);
    }

    /** Forces the live WAL or the metadata staging file. */
    void forceChannel(FileChannel channel) throws IOException {
        LOG.trace("Forcing channel {}", channel);
        channel.force(true);
    }

    void replace(Path source, Path target) throws IOException {
        LOG.trace("Atomically replacing {} with {}", source, target);
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        LOG.trace("Replace completed: {} -> {}", source, target);
    }

    void forceDirectory(Path directory) throws IOException {
        // Java's Windows provider cannot open directories for force. File force and
        // atomic replacement still apply; Linux requires directory force to succeed.
        if (System.getProperty("os.name").startsWith("Windows")) {
            LOG.trace("Skipping directory force on Windows for {}", directory);
            return;
        }
        LOG.trace("Forcing directory {}", directory);
        try (var channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
            LOG.trace("Directory force complete for {}", directory);
        }
    }

    FileChannel reopen(Path path) throws IOException {
        LOG.trace("Reopening channel for {}", path);
        return FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE);
    }
}
