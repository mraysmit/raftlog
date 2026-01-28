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
import dev.mars.raftlog.storage.RaftStorage;
import dev.mars.raftlog.storage.RaftStorage.LogEntryData;
import dev.mars.raftlog.storage.RaftStorage.PersistentMeta;
import dev.mars.raftlog.storage.RaftStorageConfig;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

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

    public static void main(String[] args) throws Exception {
        System.out.println("+---------------------------------------+");
        System.out.println("|           Raft WAL Demo               |");
        System.out.println("+---------------------------------------+");
        System.out.println();

        // Build configuration with CLI override if provided
        RaftStorageConfig config = args.length > 0 && !args[0].isBlank()
                ? RaftStorageConfig.builder().dataDir(args[0]).build()
                : RaftStorageConfig.load();

        System.out.println("Configuration: " + config);
        System.out.println();

        try (FileRaftStorage storage = new FileRaftStorage(config)) {
            // Open storage using config's data directory
            storage.open().join();
            System.out.println("[OK] Storage opened at: " + config.dataDir().toAbsolutePath());

            // Load existing metadata
            PersistentMeta meta = storage.loadMetadata().join();
            System.out.println("[OK] Loaded metadata: term=" + meta.currentTerm() +
                    ", votedFor=" + meta.votedFor().orElse("(none)"));

            // Update metadata (simulate term increment)
            long newTerm = meta.currentTerm() + 1;
            storage.updateMetadata(newTerm, Optional.of("node-1")).join();
            System.out.println("[OK] Updated metadata: term=" + newTerm + ", votedFor=node-1");

            // Replay existing log
            List<LogEntryData> existingEntries = storage.replayLog().join();
            System.out.println("[OK] Replayed " + existingEntries.size() + " existing entries");

            // Show last few entries if any exist
            if (!existingEntries.isEmpty()) {
                System.out.println("\n  Last entries in log:");
                int start = Math.max(0, existingEntries.size() - 3);
                for (int i = start; i < existingEntries.size(); i++) {
                    LogEntryData entry = existingEntries.get(i);
                    String payload = new String(entry.payload(), StandardCharsets.UTF_8);
                    System.out.printf("    [%d] term=%d: %s%n", 
                            entry.index(), entry.term(), payload);
                }
            }

            // Append new entries
            long nextIndex = existingEntries.isEmpty() ? 1 : 
                    existingEntries.get(existingEntries.size() - 1).index() + 1;

            List<LogEntryData> newEntries = List.of(
                    new LogEntryData(nextIndex, newTerm, 
                            ("SET key" + nextIndex + " value" + nextIndex).getBytes(StandardCharsets.UTF_8)),
                    new LogEntryData(nextIndex + 1, newTerm, 
                            ("SET key" + (nextIndex + 1) + " value" + (nextIndex + 1)).getBytes(StandardCharsets.UTF_8))
            );

            storage.appendEntries(newEntries).join();
            storage.sync().join(); // Durability barrier
            System.out.println("\n[OK] Appended " + newEntries.size() + " entries (indices " + 
                    nextIndex + "-" + (nextIndex + 1) + ")");

            // Show what was appended
            System.out.println("\n  New entries appended:");
            for (LogEntryData entry : newEntries) {
                String payload = new String(entry.payload(), StandardCharsets.UTF_8);
                System.out.printf("    [%d] term=%d: %s%n", 
                        entry.index(), entry.term(), payload);
            }

            System.out.println("\n+---------------------------------------+");
            System.out.println("|  WAL demo complete!                   |");
            System.out.println("|  Run again to see entries replayed.   |");
            System.out.println("+---------------------------------------+");
        }
    }
}
