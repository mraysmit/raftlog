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
import ch.qos.logback.classic.filter.ThresholdFilter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DemoInfoLoggingSafetyTest {

    @TempDir
    Path dir;

    @Test
    void keyValueInfoLogsRetainSummaryWithoutValues() throws Exception {
        List<String> messages = captureInfo(KeyValueExample.class,
                () -> KeyValueExample.main(new String[]{dir.resolve("key-value").toString()}));

        assertContains(messages, "Final replay summary: records=8");
        assertContains(messages, "Last-write-wins result computed for key=ui.theme");
        assertFalse(messages.stream().anyMatch(message -> message.contains("value=Alice")));
        assertFalse(messages.stream().anyMatch(message -> message.contains("value=light")));
        assertFalse(messages.stream().anyMatch(message -> message.contains("host=localhost")));
    }

    @Test
    void walInfoLogsRetainSummaryWithoutPayloadPreview() throws Exception {
        List<String> messages = captureInfo(WalDemo.class,
                () -> WalDemo.main(new String[]{dir.resolve("wal-demo").toString()}));

        assertContains(messages, "Durability barrier completed:");
        assertContains(messages, "WAL example completed:");
        assertFalse(messages.stream().anyMatch(message -> message.contains("payloadPreview=")));
        assertFalse(messages.stream().anyMatch(message -> message.contains("command=SET")));
    }

    private static List<String> captureInfo(Class<?> loggerOwner, ThrowingRunnable action) throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(loggerOwner);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        ThresholdFilter infoOnly = new ThresholdFilter();
        infoOnly.setLevel("INFO");
        infoOnly.start();
        appender.addFilter(infoOnly);
        appender.start();
        logger.addAppender(appender);
        try {
            action.run();
            return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
            infoOnly.stop();
        }
    }

    private static void assertContains(List<String> messages, String expectedFragment) {
        assertTrue(messages.stream().anyMatch(message -> message.contains(expectedFragment)),
                () -> "Expected log containing <" + expectedFragment + "> but got: " + messages);
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
