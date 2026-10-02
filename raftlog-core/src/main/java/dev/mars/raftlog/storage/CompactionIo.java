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
        LOG.atDebug().addKeyValue("event", "io.write.started")
                .log("Writing WAL bytes: remaining={}", bytes.remaining());
        while (bytes.hasRemaining()) channel.write(bytes);
        LOG.atDebug().addKeyValue("event", "io.write.completed")
                .log("Completed write to channel {}", channel);
    }

    /** Writes one record to the live WAL. A seam so tests can tear a record at a real write boundary. */
    void writeRecord(FileChannel channel, ByteBuffer record) throws IOException {
        LOG.atDebug().addKeyValue("event", "io.record.write.started")
                .addKeyValue("recordBytes", record.remaining())
                .log("Writing one live WAL record");
        while (record.hasRemaining()) channel.write(record);
        LOG.atDebug().addKeyValue("event", "io.record.write.completed")
                .log("Live WAL record write completed");
    }

    /**
     * Opens the WAL a second time for replay, which reads it and may truncate a torn tail.
     * Kept apart from {@link #reopen(Path)}, which is the reopen that follows publication of a
     * compacted WAL, so that failing one in a test never disturbs the other.
     */
    FileChannel openForReplay(Path path) throws IOException {
        FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE);
        LOG.atDebug().addKeyValue("event", "io.replay.opened")
                .log("Opened WAL for replay at {}", path);
        return channel;
    }

    /**
     * One positional read of the WAL during replay. A seam so tests can make a read come back
     * short, as it does when the file shrinks underneath the reader, or make no progress.
     */
    int read(FileChannel channel, ByteBuffer buffer, long position) throws IOException {
        return channel.read(buffer, position);
    }

    /** Opens the live WAL, creating it if needed. A seam so tests can make open fail after the channel exists. */
    FileChannel openLog(Path path) throws IOException {
        FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        LOG.atDebug().addKeyValue("event", "io.wal.opened")
                .log("Opened live WAL at {}", path);
        return channel;
    }

    /** Removes an unpublished compaction output. A seam so tests can make cleanup fail. */
    void discard(Path path) throws IOException {
        boolean deleted = Files.deleteIfExists(path);
        LOG.atDebug().addKeyValue("event", "io.staging.discarded")
                .addKeyValue("deleted", deleted)
                .log("Discarded unpublished WAL staging file at {}", path);
    }

    /** Closes a channel held by the storage. A seam so tests can make resource release fail. */
    void closeChannel(FileChannel channel) throws IOException {
        channel.close();
        LOG.atDebug().addKeyValue("event", "io.channel.closed")
                .log("Closed storage channel");
    }

    /** Releases the directory lock. A seam so tests can make resource release fail. */
    void releaseLock(java.nio.channels.FileLock lock) throws IOException {
        lock.release();
        LOG.atDebug().addKeyValue("event", "io.lock.released")
                .log("Released WAL directory lock");
    }

    /** Forces the compaction output file. */
    void force(FileChannel channel) throws IOException {
        LOG.atDebug().addKeyValue("event", "io.compaction.force.started")
                .log("Forcing compaction output channel {}", channel);
        channel.force(true);
        LOG.atDebug().addKeyValue("event", "io.compaction.force.completed")
                .log("Compaction output forced to disk");
    }

    /** Forces the live WAL or the metadata staging file. */
    void forceChannel(FileChannel channel) throws IOException {
        LOG.atDebug().addKeyValue("event", "io.channel.force.started")
                .log("Forcing channel {}", channel);
        channel.force(true);
        LOG.atDebug().addKeyValue("event", "io.channel.force.completed")
                .log("Channel forced to disk");
    }

    void replace(Path source, Path target) throws IOException {
        LOG.atDebug().addKeyValue("event", "io.replace.started")
                .log("Atomically replacing {} with {}", source, target);
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        LOG.atDebug().addKeyValue("event", "io.replace.completed")
                .log("Replace completed: {} -> {}", source, target);
    }

    void forceDirectory(Path directory) throws IOException {
        // Java's Windows provider cannot open directories for force. File force and
        // atomic replacement still apply; Linux requires directory force to succeed.
        if (System.getProperty("os.name").startsWith("Windows")) {
            LOG.atDebug().addKeyValue("event", "io.directory.force.skipped")
                    .log("Skipping directory force on Windows for {}", directory);
            return;
        }
        LOG.atDebug().addKeyValue("event", "io.directory.force.started")
                .log("Forcing directory {}", directory);
        try (var channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
            LOG.atDebug().addKeyValue("event", "io.directory.force.completed")
                    .log("Directory force complete for {}", directory);
        }
    }

    /** Usable bytes on the store holding {@code directory}. A seam so tests can simulate a filling disk. */
    long usableSpace(Path directory) throws IOException {
        return Files.getFileStore(directory).getUsableSpace();
    }

    FileChannel reopen(Path path) throws IOException {
        LOG.atDebug().addKeyValue("event", "io.channel.reopening")
                .log("Reopening channel for {}", path);
        return FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE);
    }
}
