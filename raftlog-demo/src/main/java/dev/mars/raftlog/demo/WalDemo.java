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
import dev.mars.raftlog.storage.RaftStorage.PersistentMeta;
import dev.mars.raftlog.storage.RaftStorageConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Demo entry point for the Raft WAL implementation.
 * <p>
 * This demonstrates basic WAL operations:
 * <ul>
 *   <li>Opening storage</li>
 *   <li>Persisting metadata (term + vote)</li>
 *   <li>Appending log entries</li>
 *   <li>Replay on restart</li>
 * </ul>
 * 
 * <h2>Configuration</h2>
 * Configuration is handled by {@link RaftStorageConfig} with the following priority:
 * <ol>
 *   <li>Command-line argument (data directory only)</li>
 *   <li>System properties: {@code -Draftlog.dataDir=/path -Draftlog.syncEnabled=true ...}</li>
 *   <li>Environment variables: {@code RAFTLOG_DATA_DIR, RAFTLOG_SYNC_ENABLED, ...}</li>
 *   <li>Properties file: {@code raftlog.properties} on classpath or working directory</li>
 *   <li>Defaults</li>
 * </ol>
 * 
 * <h2>Usage</h2>
 * <pre>
 * # Build the demo JAR
 * mvn package -pl raftlog-demo -am
 * 
 * # Run with default configuration
 * java -jar raftlog-demo/target/raftlog-demo-1.0-SNAPSHOT.jar
 * 
 * # Run with CLI data directory override
 * java -jar raftlog-demo/target/raftlog-demo-1.0-SNAPSHOT.jar /path/to/data
 * 
 * # Run with system properties
 * java -Draftlog.dataDir=/path/to/data -Draftlog.verifyWrites=true -jar raftlog-demo/target/raftlog-demo-1.0-SNAPSHOT.jar
 * 
 * # Run with environment variables (PowerShell)
 * $env:RAFTLOG_DATA_DIR = "/path/to/data"
 * java -jar raftlog-demo/target/raftlog-demo-1.0-SNAPSHOT.jar
 * </pre>
 * 
 * @see RaftStorageConfig
 */
public class WalDemo {
    private static final Logger LOG = LoggerFactory.getLogger(WalDemo.class);
    private static final int PAYLOAD_PREVIEW_LIMIT = 120;

    public static void main(String[] args) throws Exception {
        long exampleStarted = System.nanoTime();
        LOG.info("+---------------------------------------+");
        LOG.info("|           Raft WAL Demo               |");
        LOG.info("+---------------------------------------+");
        LOG.info("");

        // Build configuration with CLI override if provided
        RaftStorageConfig config = args.length > 0 && !args[0].isBlank()
                ? RaftStorageConfig.builder().dataDir(args[0]).build()
                : RaftStorageConfig.load();

        LOG.info("Starting Raft WAL example: dataDir={}, syncEnabled={}, verifyWrites={}, "
                        + "minFreeSpaceMb={}, maxPayloadSizeMb={}",
                config.dataDir().toAbsolutePath(), config.syncEnabled(), config.verifyWrites(),
                config.minFreeSpaceMb(), config.maxPayloadSizeMb());
        LOG.info("");

        try (FileRaftStorage storage = new FileRaftStorage(config)) {
            // Open storage using config's data directory
            LOG.info("Step 1/5: Opening WAL storage and acquiring its exclusive lock");
            storage.open().join();
            LOG.info("[OK] Storage opened at: {}", config.dataDir().toAbsolutePath());

            // Load existing metadata
            LOG.info("Step 2/5: Loading persistent Raft metadata");
            PersistentMeta meta = storage.loadMetadata().join();
            LOG.info("[OK] Loaded metadata: term={}, votedFor={}", meta.currentTerm(),
                    meta.votedFor().orElse("(none)"));

            // Update metadata (simulate term increment)
            long newTerm = Math.addExact(meta.currentTerm(), 1);
            LOG.info("Step 3/5: Persisting metadata transition: term {} -> {}, votedFor {} -> node-1",
                    meta.currentTerm(), newTerm, meta.votedFor().orElse("(none)"));
            storage.updateMetadata(newTerm, Optional.of("node-1")).join();
            LOG.info("[OK] Updated metadata: term={}, votedFor=node-1", newTerm);

            // Replay existing log
            LOG.info("Step 4/5: Replaying WAL entries in index order");
            List<LogEntryData> existingEntries = storage.replayLog().join();
            logReplaySummary(existingEntries);

            // Show last few entries if any exist
            if (!existingEntries.isEmpty()) {
                LOG.info("");
                LOG.info("  Last entries in log:");
                int start = Math.max(0, existingEntries.size() - 3);
                for (int i = start; i < existingEntries.size(); i++) {
                    LogEntryData entry = existingEntries.get(i);
                    LOG.info("    Existing entry: index={}, term={}, payloadBytes={}, payloadPreview={}",
                            entry.index(), entry.term(), entry.payload().length, payloadPreview(entry));
                }
            }

            // Append new entries
            long nextIndex = existingEntries.isEmpty() ? 1 :
                    Math.addExact(existingEntries.get(existingEntries.size() - 1).index(), 1);

            List<LogEntryData> newEntries = List.of(
                    new LogEntryData(nextIndex, newTerm,
                            ("SET key" + nextIndex + " value" + nextIndex).getBytes(StandardCharsets.UTF_8)),
                    new LogEntryData(Math.addExact(nextIndex, 1), newTerm,
                            ("SET key" + Math.addExact(nextIndex, 1) + " value" + Math.addExact(nextIndex, 1))
                                    .getBytes(StandardCharsets.UTF_8))
            );

            LOG.info("Step 5/5: Appending {} entries: indexRange={}-{}, term={}",
                    newEntries.size(), nextIndex, newEntries.get(newEntries.size() - 1).index(), newTerm);
            for (LogEntryData entry : newEntries) {
                LOG.info("Prepared entry: index={}, term={}, payloadBytes={}, command={}",
                        entry.index(), entry.term(), entry.payload().length, payloadPreview(entry));
            }

            storage.appendEntries(newEntries).join();
            long syncStarted = System.nanoTime();
            storage.sync().join(); // Durability barrier
            long syncElapsedMs = elapsedMillis(syncStarted);
            LOG.info("");
            LOG.info("Durability barrier completed: entries={}, indexRange={}-{}, elapsedMs={}",
                    newEntries.size(), nextIndex, newEntries.get(newEntries.size() - 1).index(), syncElapsedMs);

            // Show what was appended
            LOG.info("");
            LOG.info("  New entries appended:");
            for (LogEntryData entry : newEntries) {
                LOG.info("    Appended entry: index={}, term={}, payloadBytes={}, payloadPreview={}",
                        entry.index(), entry.term(), entry.payload().length, payloadPreview(entry));
            }

            long resultingEntryCount = Math.addExact(existingEntries.size(), newEntries.size());
            LOG.info("WAL example completed: previousEntries={}, appendedEntries={}, resultingEntries={}, "
                            + "lastIndex={}, elapsedMs={}",
                    existingEntries.size(), newEntries.size(), resultingEntryCount,
                    newEntries.get(newEntries.size() - 1).index(), elapsedMillis(exampleStarted));
            LOG.info("");
            LOG.info("+---------------------------------------+");
            LOG.info("|  WAL demo complete!                   |");
            LOG.info("|  Run again to see entries replayed.   |");
            LOG.info("+---------------------------------------+");
        }
    }

    private static void logReplaySummary(List<LogEntryData> entries) {
        if (entries.isEmpty()) {
            LOG.info("Replay summary: entries=0, indexRange=(empty), termRange=(empty)");
            return;
        }

        long minTerm = entries.stream().mapToLong(LogEntryData::term).min().orElseThrow();
        long maxTerm = entries.stream().mapToLong(LogEntryData::term).max().orElseThrow();
        LOG.info("Replay summary: entries={}, indexRange={}-{}, termRange={}-{}",
                entries.size(), entries.get(0).index(), entries.get(entries.size() - 1).index(), minTerm, maxTerm);
    }

    private static String payloadPreview(LogEntryData entry) {
        String payload = new String(entry.payload(), StandardCharsets.UTF_8)
                .replace("\r", "\\r")
                .replace("\n", "\\n");
        if (payload.length() <= PAYLOAD_PREVIEW_LIMIT) return payload;
        return payload.substring(0, PAYLOAD_PREVIEW_LIMIT) + "…";
    }

    private static long elapsedMillis(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }
}
