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
import dev.mars.raftlog.storage.RaftStorageConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Writes string key/value pairs as opaque WAL payloads and rebuilds the latest
 * key/value state by replaying them in log order.
 * <p>
 * This is an encoding example, not a database API supplied by RaftLog. Each
 * payload contains a length-prefixed UTF-8 key followed by a length-prefixed
 * UTF-8 value. Repeated keys demonstrate last-write-wins state reconstruction.
 *
 * <h2>Usage</h2>
 * <pre>
 * mvn package -pl raftlog-demo -am
 * java -cp raftlog-demo/target/raftlog-demo-1.3.0.jar \
 *     dev.mars.raftlog.demo.KeyValueExample [data-directory]
 * </pre>
 */
public final class KeyValueExample {
    private static final Logger LOG = LoggerFactory.getLogger(KeyValueExample.class);
    private static final Path DEFAULT_DATA_DIR = Path.of("target", "key-value-example-data");

    private KeyValueExample() {
    }

    public static void main(String[] args) {
        Path dataDir = args.length > 0 && !args[0].isBlank()
                ? Path.of(args[0])
                : DEFAULT_DATA_DIR;

        RaftStorageConfig config = RaftStorageConfig.builder()
                .dataDir(dataDir)
                .build();

        List<KeyValue> writes = List.of(
                new KeyValue("user.name", "Alice"),
                new KeyValue("ui.theme", "dark"),
                new KeyValue("retry.count", "3"),
                new KeyValue("feature.audit.enabled", "true"),
                new KeyValue("welcome.message", "Hello, 世界 🌍"),
                new KeyValue("connection", "host=localhost;port=5432"),
                new KeyValue("empty.value", ""),
                new KeyValue("ui.theme", "light"));

        try (FileRaftStorage storage = new FileRaftStorage(config)) {
            storage.open().join();

            List<LogEntryData> existing = storage.replayLog().join();
            long nextIndex = existing.isEmpty()
                    ? 1
                    : Math.addExact(existing.get(existing.size() - 1).index(), 1);

            List<LogEntryData> records = new ArrayList<>(writes.size());
            for (KeyValue write : writes) {
                records.add(new LogEntryData(nextIndex++, 1, encode(write)));
                LOG.info("Writing {}={}", write.key(), write.value());
            }

            storage.appendEntries(records).join();
            storage.sync().join();
            LOG.info("Durably wrote {} key/value records to {}", records.size(), dataDir.toAbsolutePath());

            Map<String, String> currentState = materialize(storage.replayLog().join());
            LOG.info("Replayed {} current key/value pairs:", currentState.size());
            currentState.forEach((key, value) -> LOG.info("  {}={}", key, value));
        }
    }

    /** A single string key/value mutation stored in one WAL entry. */
    record KeyValue(String key, String value) {
        KeyValue {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(value, "value");
        }
    }

    static byte[] encode(KeyValue keyValue) {
        Objects.requireNonNull(keyValue, "keyValue");
        byte[] key = keyValue.key().getBytes(StandardCharsets.UTF_8);
        byte[] value = keyValue.value().getBytes(StandardCharsets.UTF_8);
        int size = Math.addExact(8, Math.addExact(key.length, value.length));

        return ByteBuffer.allocate(size)
                .putInt(key.length)
                .put(key)
                .putInt(value.length)
                .put(value)
                .array();
    }

    static KeyValue decode(byte[] payload) {
        Objects.requireNonNull(payload, "payload");
        ByteBuffer input = ByteBuffer.wrap(payload);
        if (input.remaining() < 8) throw malformedPayload();

        int keyLength = input.getInt();
        if (keyLength < 0 || keyLength > input.remaining() - Integer.BYTES) throw malformedPayload();
        byte[] key = new byte[keyLength];
        input.get(key);

        int valueLength = input.getInt();
        if (valueLength < 0 || valueLength != input.remaining()) throw malformedPayload();
        byte[] value = new byte[valueLength];
        input.get(value);

        return new KeyValue(decodeUtf8(key), decodeUtf8(value));
    }

    static Map<String, String> materialize(List<LogEntryData> entries) {
        Objects.requireNonNull(entries, "entries");
        Map<String, String> state = new LinkedHashMap<>();
        for (LogEntryData entry : entries) {
            KeyValue keyValue = decode(entry.payload());
            state.put(keyValue.key(), keyValue.value());
        }
        return Collections.unmodifiableMap(state);
    }

    private static String decodeUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("Malformed key/value WAL payload", e);
        }
    }

    private static IllegalArgumentException malformedPayload() {
        return new IllegalArgumentException("Malformed key/value WAL payload");
    }
}
