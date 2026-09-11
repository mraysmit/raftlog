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

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** Edge-case coverage for bounded, unambiguous, single-line operational logs. */
class FileRaftStorageLoggingTest {

    @TempDir
    Path dir;

    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void captureStorageLogs() {
        logger = (Logger) LoggerFactory.getLogger(FileRaftStorage.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void stopCapturingStorageLogs() {
        logger.detachAppender(appender);
        appender.stop();
    }

    @Test
    void oversizedVotedForIsBoundedAndNotLoggedInFull() throws Exception {
        String votedFor = "node-" + "x".repeat(10_000);
        FileRaftStorage storage = new FileRaftStorage();
        await(storage.open(dir));
        try {
            await(storage.updateMetadata(7, Optional.of(votedFor)));
            await(storage.loadMetadata());
        } finally {
            closeAndAwait(storage);
        }

        List<String> messages = metadataVoteMessages();
        assertEquals(3, messages.size());
        for (String message : messages) {
            assertFalse(message.contains(votedFor), "Full node identifier leaked into log: " + message.length());
            assertTrue(message.length() <= 512, "Metadata log line is unbounded: " + message.length());
        }
    }

    @Test
    void controlCharactersInVotedForCannotForgeLogLines() throws Exception {
        String votedFor = "node-a\r\nFORGED ERROR\t\u0000end";
        FileRaftStorage storage = new FileRaftStorage();
        await(storage.open(dir));
        try {
            await(storage.updateMetadata(8, Optional.of(votedFor)));
            await(storage.loadMetadata());
        } finally {
            closeAndAwait(storage);
        }

        List<String> messages = metadataVoteMessages();
        assertEquals(3, messages.size());
        for (String message : messages) {
            assertFalse(message.contains("\r"), "Carriage return leaked into metadata log");
            assertFalse(message.contains("\n"), "Newline leaked into metadata log");
            assertFalse(message.contains("\t"), "Tab leaked into metadata log");
            assertFalse(message.contains("\u0000"), "NUL leaked into metadata log");
        }
    }

    @Test
    void ambiguousTailCorruptionHasAccurateSinglePunctuationMessage() throws Exception {
        FileRaftStorage writer = new FileRaftStorage();
        await(writer.open(dir));
        try {
            await(writer.appendEntries(List.of(
                    new RaftStorage.LogEntryData(1, 1, "payload".getBytes()))));
            await(writer.sync());
        } finally {
            closeAndAwait(writer);
        }

        Path logPath = dir.resolve("raft.log");
        byte[] bytes = Files.readAllBytes(logPath);
        bytes[bytes.length - 1] ^= 1;
        Files.write(logPath, bytes, StandardOpenOption.TRUNCATE_EXISTING);

        FileRaftStorage recovery = new FileRaftStorage();
        await(recovery.open(dir));
        try {
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> await(recovery.replayLog()));
            assertTrue(failure.getCause() instanceof FileRaftStorage.CorruptLogException);
        } finally {
            closeAndAwait(recovery);
        }

        String message = appender.list.stream()
                .filter(event -> "ERROR".equals(event.getLevel().levelStr))
                .map(ILoggingEvent::getFormattedMessage)
                .filter(text -> text.contains("Storage instance is now fenced"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected corruption fencing log"));

        assertTrue(message.contains("ambiguous corruption"), message);
        assertFalse(message.contains("inside the committed region"), message);
        assertFalse(message.contains(".. Storage"), message);
    }

    @Test
    void operationsCarryCorrelationAndStableEventFields() throws Exception {
        FileRaftStorage storage = new FileRaftStorage();
        await(storage.open(dir));
        await(storage.appendEntries(List.of(new RaftStorage.LogEntryData(1, 1, new byte[]{1}))));
        await(storage.sync());
        closeAndAwait(storage);

        ILoggingEvent opened = eventStartingWith("WAL opened successfully:");
        assertFalse(opened.getMDCPropertyMap().get("storageId").isBlank());
        assertTrue(opened.getMDCPropertyMap().get("operationId").startsWith("open-"));
        assertEquals(dir.toAbsolutePath().normalize().toString(), opened.getMDCPropertyMap().get("storagePath"));
        assertKeyValue(opened, "event", "storage.open.completed");

        ILoggingEvent appended = eventStartingWith("Appended 1 entries to WAL:");
        assertEquals(Level.DEBUG, appended.getLevel());
        assertTrue(appended.getMDCPropertyMap().get("operationId").startsWith("append-"));
        assertKeyValue(appended, "event", "wal.append.completed");

        ILoggingEvent closed = eventStartingWith("WAL storage closed:");
        assertTrue(closed.getFormattedMessage().contains("cleanupSucceeded=true"));
        assertTrue(closed.getMDCPropertyMap().get("operationId").startsWith("close-"));
        assertKeyValue(closed, "event", "storage.close.completed");
    }

    @Test
    void truncatedMetadataHasExplicitCorruptionLog() throws Exception {
        FileRaftStorage storage = new FileRaftStorage();
        await(storage.open(dir));
        Files.write(dir.resolve("meta.dat"), new byte[]{1, 2, 3});
        try {
            ExecutionException failure = assertThrows(ExecutionException.class, () -> await(storage.loadMetadata()));
            assertTrue(failure.getCause() instanceof FileRaftStorage.StorageException);
        } finally {
            closeAndAwait(storage);
        }

        ILoggingEvent corrupt = eventStartingWith("Corrupt metadata: file is 3 bytes");
        assertEquals(Level.ERROR, corrupt.getLevel());
        assertKeyValue(corrupt, "event", "metadata.corrupt");
    }

    private List<String> metadataVoteMessages() {
        return appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.startsWith("Updating metadata:")
                        || message.startsWith("Metadata updated:")
                        || message.startsWith("Metadata loaded:"))
                .toList();
    }

    private ILoggingEvent eventStartingWith(String prefix) {
        return appender.list.stream()
                .filter(event -> event.getFormattedMessage().startsWith(prefix))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected log starting with: " + prefix));
    }

    private static void assertKeyValue(ILoggingEvent event, String key, Object value) {
        assertTrue(event.getKeyValuePairs().stream()
                        .anyMatch(pair -> key.equals(pair.key) && value.equals(pair.value)),
                () -> "Expected " + key + "=" + value + " in " + event.getKeyValuePairs());
    }

    private static <T> T await(CompletableFuture<T> future) throws Exception {
        return future.get(10, TimeUnit.SECONDS);
    }

    private void closeAndAwait(FileRaftStorage storage) throws Exception {
        storage.close();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        try (FileChannel channel = FileChannel.open(dir.resolve("raft.lock"), StandardOpenOption.WRITE)) {
            while (System.nanoTime() < deadline) {
                try (var lock = channel.tryLock()) {
                    if (lock != null) return;
                } catch (OverlappingFileLockException pendingClose) {
                    // The executor is still closing the prior instance.
                }
                Thread.sleep(5);
            }
        }
        fail("Storage did not release its lock after close");
    }
}
