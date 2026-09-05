package dev.mars.raftlog.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/** Filesystem operations for WAL replacement; package scope permits deterministic I/O faults. */
class CompactionIo {
    void write(FileChannel channel, ByteBuffer bytes) throws IOException {
        while (bytes.hasRemaining()) channel.write(bytes);
    }

    void force(FileChannel channel) throws IOException { channel.force(true); }

    void replace(Path source, Path target) throws IOException {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    void forceDirectory(Path directory) throws IOException {
        // Java's Windows provider cannot open directories for force. File force and
        // atomic replacement still apply; Linux requires directory force to succeed.
        if (System.getProperty("os.name").startsWith("Windows")) return;
        try (var channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    FileChannel reopen(Path path) throws IOException {
        return FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE);
    }
}
