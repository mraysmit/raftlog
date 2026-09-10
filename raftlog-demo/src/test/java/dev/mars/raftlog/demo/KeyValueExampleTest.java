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

import dev.mars.raftlog.storage.FileRaftStorage;
import dev.mars.raftlog.storage.RaftStorage.LogEntryData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class KeyValueExampleTest {

    @TempDir
    Path dir;

    @Test
    void codecPreservesEmptyDelimiterRichAndUnicodeValues() {
        List<KeyValueExample.KeyValue> values = List.of(
                new KeyValueExample.KeyValue("empty", ""),
                new KeyValueExample.KeyValue("connection", "host=localhost;port=5432"),
                new KeyValueExample.KeyValue("welcome", "Hello, 世界 🌍"));

        for (KeyValueExample.KeyValue value : values) {
            assertEquals(value, KeyValueExample.decode(KeyValueExample.encode(value)));
        }
    }

    @Test
    void replayUsesTheLatestValueForEachKey() {
        List<LogEntryData> entries = List.of(
                entry(1, "theme", "dark"),
                entry(2, "retry.count", "3"),
                entry(3, "theme", "light"));

        assertEquals(Map.of("theme", "light", "retry.count", "3"),
                KeyValueExample.materialize(entries));
    }

    @Test
    void decoderRejectsTruncatedAndTrailingData() {
        byte[] valid = KeyValueExample.encode(new KeyValueExample.KeyValue("key", "value"));
        byte[] truncated = java.util.Arrays.copyOf(valid, valid.length - 1);
        byte[] trailing = java.util.Arrays.copyOf(valid, valid.length + 1);

        assertThrows(IllegalArgumentException.class, () -> KeyValueExample.decode(truncated));
        assertThrows(IllegalArgumentException.class, () -> KeyValueExample.decode(trailing));
        assertThrows(IllegalArgumentException.class,
                () -> KeyValueExample.decode(ByteBuffer.allocate(8).putInt(-1).putInt(0).array()));
    }

    @Test
    void keyValuesRoundTripThroughTheRealWal() throws Exception {
        try (FileRaftStorage storage = new FileRaftStorage()) {
            storage.open(dir).get(10, TimeUnit.SECONDS);
            storage.appendEntries(List.of(
                    entry(1, "user.name", "Alice"),
                    entry(2, "feature.enabled", "true"),
                    entry(3, "user.name", "Bob")))
                    .get(10, TimeUnit.SECONDS);
            storage.sync().get(10, TimeUnit.SECONDS);

            assertEquals(Map.of("user.name", "Bob", "feature.enabled", "true"),
                    KeyValueExample.materialize(storage.replayLog().get(10, TimeUnit.SECONDS)));
        }
    }

    private static LogEntryData entry(long index, String key, String value) {
        return new LogEntryData(index, 1,
                KeyValueExample.encode(new KeyValueExample.KeyValue(key, value)));
    }
}
