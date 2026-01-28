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
/**
 * Raft Storage Layer - Write-Ahead Log (WAL) implementation.
 * <p>
 * This package provides the persistence layer for Raft consensus:
 * <ul>
 *   <li>{@link dev.mars.raftlog.storage.RaftStorage} - The storage interface</li>
 *   <li>{@link dev.mars.raftlog.storage.FileRaftStorage} - File-based WAL implementation</li>
 *   <li>{@link dev.mars.raftlog.storage.AppendPlan} - Append operation planner</li>
 * </ul>
 * <p>
 * <b>Key Design Principles:</b>
 * <ul>
 *   <li><b>Persist-before-response:</b> All mutations are durable before RPC acknowledgment</li>
 *   <li><b>Crash safety:</b> WAL survives process crashes and power failures</li>
 *   <li><b>Sequential replay:</b> Log can be fully reconstructed on startup</li>
 * </ul>
 * <p>
 * <b>File Layout:</b>
 * <pre>
 * data/
 *  ├─ meta.dat     // currentTerm + votedFor (atomic replace)
 *  └─ raft.log     // append-only WAL with TRUNCATE and APPEND records
 * </pre>
 *
 * @see dev.mars.raftlog.storage.RaftStorage
 */
package dev.mars.raftlog.storage;
