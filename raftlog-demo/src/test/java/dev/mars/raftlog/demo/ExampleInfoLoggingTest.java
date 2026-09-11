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

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExampleInfoLoggingTest {

    @TempDir
    Path dir;

    @Test
    void walDemoLogsConfigurationStepsEntryDetailsAndCompletionSummary() throws Exception {
        List<String> messages = capture(WalDemo.class,
                () -> WalDemo.main(new String[]{dir.resolve("wal-demo").toString()}));

        assertContains(messages, "Starting Raft WAL example:");
        assertContains(messages, "syncEnabled=true");
        assertContains(messages, "Step 1/5: Opening WAL storage");
        assertContains(messages, "Step 2/5: Loading persistent Raft metadata");
        assertContains(messages, "Step 3/5: Persisting metadata transition");
        assertContains(messages, "Step 4/5: Replaying WAL entries");
        assertContains(messages, "Replay summary: entries=0");
        assertContains(messages, "Step 5/5: Appending 2 entries");
        assertContains(messages, "Prepared entry: index=1, term=1, payloadBytes=");
        assertContains(messages, "Durability barrier completed:");
        assertContains(messages, "Durable entry: index=1, term=1, payloadBytes=15, payloadPreview=SET key1 value1");
        assertContains(messages, "Durable entry: index=2, term=1, payloadBytes=15, payloadPreview=SET key2 value2");
        assertMessageCount(messages, "Durable entry:", 2);
        assertContains(messages, "Read-back verified entry: index=1, term=1, payloadBytes=15, payloadPreview=SET key1 value1");
        assertContains(messages, "Read-back verified entry: index=2, term=1, payloadBytes=15, payloadPreview=SET key2 value2");
        assertMessageCount(messages, "Read-back verified entry:", 2);
        assertContains(messages, "WAL example completed:");
    }

    @Test
    void keyValueExampleLogsEncodingReplayAndLastWriteWinsDetails() throws Exception {
        List<String> messages = capture(KeyValueExample.class,
                () -> KeyValueExample.main(new String[]{dir.resolve("key-value").toString()}));

        assertContains(messages, "Starting key/value WAL example:");
        assertContains(messages, "Encoding: length-prefixed UTF-8 key and value");
        assertContains(messages, "Initial replay summary: records=0, currentKeys=0");
        assertContains(messages, "Prepared record: index=1, term=1, key=user.name");
        assertContains(messages, "Prepared record: index=8, term=1, key=ui.theme");
        assertContains(messages, "Durability barrier completed:");
        assertContains(messages, "Durable record: index=1, term=1, key=user.name, value=Alice, payloadBytes=22");
        assertContains(messages, "Durable record: index=8, term=1, key=ui.theme, value=light, payloadBytes=21");
        assertMessageCount(messages, "Durable record:", 8);
        assertContains(messages, "Read-back verified record: index=1, term=1, key=user.name, value=Alice, payloadBytes=22");
        assertContains(messages, "Read-back verified record: index=2, term=1, key=ui.theme, value=dark, payloadBytes=20");
        assertContains(messages, "Read-back verified record: index=8, term=1, key=ui.theme, value=light, payloadBytes=21");
        assertMessageCount(messages, "Read-back verified record:", 8);
        assertContains(messages, "Final replay summary: records=8, currentKeys=7, overwrittenKeys=1");
        assertContains(messages, "Last-write-wins result: key=ui.theme, value=light");
        assertContains(messages, "Key/value WAL example completed:");
    }

    private static List<String> capture(Class<?> loggerOwner, ThrowingRunnable action) throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(loggerOwner);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            action.run();
            return appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .toList();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    private static void assertContains(List<String> messages, String expectedFragment) {
        assertTrue(messages.stream().anyMatch(message -> message.contains(expectedFragment)),
                () -> "Expected log containing <" + expectedFragment + "> but got: " + messages);
    }

    private static void assertMessageCount(List<String> messages, String fragment, long expectedCount) {
        assertEquals(expectedCount, messages.stream().filter(message -> message.contains(fragment)).count(),
                () -> "Expected " + expectedCount + " logs containing <" + fragment + "> but got: " + messages);
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
