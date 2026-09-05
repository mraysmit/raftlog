package dev.mars.raftlog.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static dev.mars.raftlog.storage.FileRaftStoragePrefixCompactionTest.*;
import static org.junit.jupiter.api.Assertions.*;

class FileRaftStorageCompactionFailureTest {
    @TempDir Path dir;

    static final List<RaftStorage.LogEntryData> ORIGINAL = List.of(entry(1, 1), entry(2, 1), entry(3, 2));
    static final List<RaftStorage.LogEntryData> RETAINED = ORIGINAL.subList(2, 3);

    private void seed() throws Exception {
        FileRaftStorage storage = open(dir);
        try { await(storage.appendEntries(ORIGINAL)); await(storage.sync()); }
        finally { close(storage, dir); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"write", "force", "beforeMove", "afterMove", "directory", "reopen"})
    void failedRewriteRetainsRecoverableWalAndFencesUncertainPublication(String failure) throws Exception {
        seed();
        AtomicBoolean injected = new AtomicBoolean();
        CompactionIo io = new CompactionIo() {
            void failAt(String stage) throws IOException {
                if (failure.equals(stage)) { injected.set(true); throw new IOException("Injected " + stage); }
            }
            @Override void write(FileChannel channel, ByteBuffer bytes) throws IOException {
                if (failure.equals("write")) {
                    channel.write(ByteBuffer.wrap(new byte[]{1, 2, 3}));
                    failAt("write");
                }
                super.write(channel, bytes);
            }
            @Override void force(FileChannel channel) throws IOException { failAt("force"); super.force(channel); }
            @Override void replace(Path source, Path target) throws IOException {
                failAt("beforeMove"); super.replace(source, target); failAt("afterMove");
            }
            @Override void forceDirectory(Path directory) throws IOException { failAt("directory"); super.forceDirectory(directory); }
            @Override FileChannel reopen(Path path) throws IOException { failAt("reopen"); return super.reopen(path); }
        };
        FileRaftStorage storage = new FileRaftStorage(RaftStorageConfig.builder().build(), io);
        await(storage.open(dir));
        boolean publicationAttempted = !List.of("write", "force").contains(failure);
        try {
            assertThrows(Exception.class, () -> storage.truncatePrefix(2).get(10, TimeUnit.SECONDS));
            assertTrue(injected.get(), "Must exercise the requested real filesystem boundary");
            if (publicationAttempted) {
                assertThrows(Exception.class, () -> storage.appendEntries(List.of(entry(4, 3))).get(10, TimeUnit.SECONDS));
                assertThrows(Exception.class, () -> storage.truncateSuffix(1).get(10, TimeUnit.SECONDS));
                assertThrows(Exception.class, () -> storage.updateMetadata(8, Optional.empty()).get(10, TimeUnit.SECONDS));
                assertThrows(Exception.class, () -> storage.sync().get(10, TimeUnit.SECONDS));
                assertThrows(Exception.class, () -> storage.replayLog().get(10, TimeUnit.SECONDS));
                assertThrows(Exception.class, () -> storage.truncatePrefix(0).get(10, TimeUnit.SECONDS));
            } else {
                assertEntries(ORIGINAL, await(storage.replayLog()));
                await(storage.appendEntries(List.of(entry(4, 3))));
                await(storage.sync());
            }
        } finally { close(storage, dir); }
        var expected = List.of("afterMove", "directory", "reopen").contains(failure) ? RETAINED
                : publicationAttempted ? ORIGINAL : List.of(entry(1, 1), entry(2, 1), entry(3, 2), entry(4, 3));
        assertReopened(dir, expected);
        assertFalse(Files.exists(dir.resolve("raft.log.tmp")), "Unpublished rewrite must be discarded on open");
    }

    @Test void futureAndQueuedAppendWaitForForcedPublicationEvenWithSyncDisabled() throws Exception {
        seed();
        CountDownLatch atDirectory = new CountDownLatch(1);
        CountDownLatch releaseDirectory = new CountDownLatch(1);
        List<String> events = new java.util.concurrent.CopyOnWriteArrayList<>();
        CompactionIo io = new CompactionIo() {
            @Override void force(FileChannel channel) throws IOException { super.force(channel); events.add("file"); }
            @Override void replace(Path source, Path target) throws IOException { super.replace(source, target); events.add("rename"); }
            @Override void forceDirectory(Path path) throws IOException {
                events.add("directory"); atDirectory.countDown();
                try { if (!releaseDirectory.await(10, TimeUnit.SECONDS)) throw new IOException("Timed out"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
                super.forceDirectory(path);
            }
            @Override FileChannel reopen(Path path) throws IOException { events.add("reopen"); return super.reopen(path); }
        };
        FileRaftStorage storage = new FileRaftStorage(RaftStorageConfig.builder().syncEnabled(false).build(), io);
        await(storage.open(dir));
        try {
            var compact = storage.truncatePrefix(2);
            assertTrue(atDirectory.await(3, TimeUnit.SECONDS), "Compaction must reach directory durability boundary");
            var append = storage.appendEntries(List.of(entry(4, 3)));
            assertFalse(compact.isDone()); assertFalse(append.isDone());
            releaseDirectory.countDown();
            await(compact); await(append);
            assertEquals(List.of("file", "rename", "directory", "reopen"), events);
        } finally { releaseDirectory.countDown(); close(storage, dir); }
        assertReopened(dir, List.of(entry(3, 2), entry(4, 3)));
    }

    @Test void interruptedTemporaryFileIsDiscardedWithoutReplacingOriginal() throws Exception {
        seed();
        Files.write(dir.resolve("raft.log.tmp"), new byte[]{1, 2, 3});
        assertReopened(dir, ORIGINAL);
        assertFalse(Files.exists(dir.resolve("raft.log.tmp")));
    }

    @Test void negativeBoundaryFailsWithoutChangingWal() throws Exception {
        seed();
        byte[] before = Files.readAllBytes(dir.resolve("raft.log"));
        FileRaftStorage storage = open(dir);
        try {
            var failure = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> storage.truncatePrefix(-1).get(10, TimeUnit.SECONDS));
            assertInstanceOf(IllegalArgumentException.class, failure.getCause());
            assertArrayEquals(before, Files.readAllBytes(dir.resolve("raft.log")));
        } finally { close(storage, dir); }
    }

    @Test void compactionRejectsCorruptSourceUntilExplicitReplayRepairsIt() throws Exception {
        seed();
        Files.write(dir.resolve("raft.log"), new byte[]{1, 2, 3}, java.nio.file.StandardOpenOption.APPEND);
        byte[] before = Files.readAllBytes(dir.resolve("raft.log"));
        FileRaftStorage storage = open(dir);
        try {
            var failure = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> storage.truncatePrefix(2).get(10, TimeUnit.SECONDS));
            assertInstanceOf(FileRaftStorage.StorageException.class, failure.getCause());
            assertArrayEquals(before, Files.readAllBytes(dir.resolve("raft.log")));
            assertEntries(ORIGINAL, await(storage.replayLog()));
            await(storage.truncatePrefix(2));
        } finally { close(storage, dir); }
        assertReopened(dir, RETAINED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"partial", "forced", "renamed", "directory"})
    void abruptlyTerminatedRewriteRecoversOldOrNewWalAndCanCompactAgain(String stage) throws Exception {
        seed();
        Path output = dir.resolve("child-output.txt");
        Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                FileRaftStorageCompactionFailureTest.class.getName(), dir.toString(), stage)
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertTrue(child.waitFor(15, TimeUnit.SECONDS), "Child must terminate at the rewrite boundary");
            assertEquals(73, child.exitValue(), () -> {
                try { return Files.readString(output); } catch (IOException e) { return e.toString(); }
            });
        } finally { if (child.isAlive()) { child.destroyForcibly(); child.waitFor(5, TimeUnit.SECONDS); } }
        assertReopened(dir, List.of("partial", "forced").contains(stage) ? ORIGINAL : RETAINED);
        assertFalse(Files.exists(dir.resolve("raft.log.tmp")));
        FileRaftStorage storage = open(dir);
        try {
            await(storage.truncatePrefix(2));
            await(storage.appendEntries(List.of(entry(4, 3))));
            await(storage.sync());
        } finally { close(storage, dir); }
        assertReopened(dir, List.of(entry(3, 2), entry(4, 3)));
    }

    public static void main(String[] args) throws Exception {
        String stage = args[1];
        CompactionIo io = new CompactionIo() {
            void haltAt(String point) { if (stage.equals(point)) Runtime.getRuntime().halt(73); }
            @Override void write(FileChannel channel, ByteBuffer bytes) throws IOException {
                if (stage.equals("partial")) { channel.write(ByteBuffer.wrap(new byte[]{1, 2, 3})); haltAt("partial"); }
                super.write(channel, bytes);
            }
            @Override void force(FileChannel channel) throws IOException { super.force(channel); haltAt("forced"); }
            @Override void replace(Path source, Path target) throws IOException { super.replace(source, target); haltAt("renamed"); }
            @Override void forceDirectory(Path path) throws IOException { super.forceDirectory(path); haltAt("directory"); }
        };
        FileRaftStorage storage = new FileRaftStorage(RaftStorageConfig.builder().build(), io);
        storage.open(Path.of(args[0])).get(10, TimeUnit.SECONDS);
        storage.truncatePrefix(2).get(10, TimeUnit.SECONDS);
        throw new AssertionError("Expected process termination checkpoint");
    }
}
