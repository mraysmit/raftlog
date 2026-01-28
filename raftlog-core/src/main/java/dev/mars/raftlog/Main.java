package dev.mars.raftlog;

import dev.mars.raftlog.storage.FileRaftStorage;
import dev.mars.raftlog.storage.RaftStorage;
import dev.mars.raftlog.storage.RaftStorage.LogEntryData;
import dev.mars.raftlog.storage.RaftStorage.PersistentMeta;

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

    public static void main(String[] args) throws Exception {
        System.out.println("Raft WAL Demo");
        System.out.println("=============\n");

        Path dataDir = Path.of("data/raft");

        try (RaftStorage storage = new FileRaftStorage()) {
            // Open storage
            storage.open(dataDir).join();
            System.out.println("✓ Storage opened at: " + dataDir.toAbsolutePath());

            // Load existing metadata
            PersistentMeta meta = storage.loadMetadata().join();
            System.out.println("✓ Loaded metadata: term=" + meta.currentTerm() +
                    ", votedFor=" + meta.votedFor().orElse("(none)"));

            // Update metadata
            long newTerm = meta.currentTerm() + 1;
            storage.updateMetadata(newTerm, Optional.of("node-1")).join();
            System.out.println("✓ Updated metadata: term=" + newTerm + ", votedFor=node-1");

            // Replay existing log
            List<LogEntryData> existingEntries = storage.replayLog().join();
            System.out.println("✓ Replayed " + existingEntries.size() + " existing entries");

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
            System.out.println("✓ Appended " + newEntries.size() + " entries (indices " + 
                    nextIndex + "-" + (nextIndex + 1) + ")");

            System.out.println("\n✓ WAL demo complete!");
        }
    }
}