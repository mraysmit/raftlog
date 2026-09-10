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
package dev.mars.raftlog;

import dev.mars.raftlog.storage.FileRaftStorage;
import dev.mars.raftlog.storage.RaftStorage;
import dev.mars.raftlog.storage.RaftStorage.LogEntryData;
import dev.mars.raftlog.storage.RaftStorage.PersistentMeta;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 */
public class Main {
    private static final Logger LOG = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) throws Exception {
        LOG.info("Raft WAL Demo");
        LOG.info("=============");

        Path dataDir = Path.of("data/raft");

        try (RaftStorage storage = new FileRaftStorage()) {
            // Open storage
            storage.open(dataDir).join();
            LOG.info("Storage opened at: {}", dataDir.toAbsolutePath());

            // Load existing metadata
            PersistentMeta meta = storage.loadMetadata().join();
            LOG.info("Loaded metadata: term={}, votedFor={}", meta.currentTerm(),
                    meta.votedFor().orElse("(none)"));

            // Update metadata
            long newTerm = meta.currentTerm() + 1;
            storage.updateMetadata(newTerm, Optional.of("node-1")).join();
            LOG.info("Updated metadata: term={}, votedFor=node-1", newTerm);

            // Replay existing log
            List<LogEntryData> existingEntries = storage.replayLog().join();
            LOG.info("Replayed {} existing entries", existingEntries.size());

            // Append new entries
            long nextIndex = existingEntries.isEmpty() ? 1 : 
                    existingEntries.get(existingEntries.size() - 1).index() + 1;

            List<LogEntryData> newEntries = List.of(
                    new LogEntryData(nextIndex, newTerm, 
                            ("command-" + nextIndex).getBytes(StandardCharsets.UTF_8)),
                    new LogEntryData(nextIndex + 1, newTerm, 
                            ("command-" + (nextIndex + 1)).getBytes(StandardCharsets.UTF_8))
            );

            storage.appendEntries(newEntries).join();
            storage.sync().join(); // Durability barrier
            LOG.info("Appended {} entries (indices {}-{})", newEntries.size(), nextIndex, nextIndex + 1);

            LOG.info("WAL demo complete!");
        }
    }
}
