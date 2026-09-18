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

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** The defaults that RaftStorage gives to implementations which do not override them. */
class RaftStorageDefaultsTest {
    /** The smallest possible implementation: everything optional is left to the interface. */
    private static class Minimal implements RaftStorage {
        final AtomicInteger closes = new AtomicInteger();
        RuntimeException closeFailure;
        @Override public CompletableFuture<Void> open(Path dataDir) { return CompletableFuture.completedFuture(null); }
        @Override public CompletableFuture<Void> updateMetadata(long term, Optional<String> vote) { return CompletableFuture.completedFuture(null); }
        @Override public CompletableFuture<PersistentMeta> loadMetadata() { return CompletableFuture.completedFuture(PersistentMeta.EMPTY); }
        @Override public CompletableFuture<Void> appendEntries(List<LogEntryData> entries) { return CompletableFuture.completedFuture(null); }
        @Override public CompletableFuture<Void> truncateSuffix(long fromIndex) { return CompletableFuture.completedFuture(null); }
        @Override public CompletableFuture<Void> sync() { return CompletableFuture.completedFuture(null); }
        @Override public CompletableFuture<List<LogEntryData>> replayLog() { return CompletableFuture.completedFuture(List.of()); }
        @Override public void close() {
            closes.incrementAndGet();
            if (closeFailure != null) throw closeFailure;
        }
    }

    @Test void prefixCompactionIsExplicitlyUnsupportedByDefaultRatherThanSilentlyIgnored() {
        CompletableFuture<Void> result = new Minimal().truncatePrefix(5);
        assertTrue(result.isCompletedExceptionally());
        Throwable cause = assertThrows(ExecutionException.class, result::get).getCause();
        assertInstanceOf(UnsupportedOperationException.class, cause);
    }

    @Test void defaultCloseAsyncClosesSynchronouslyAndCompletes() throws Exception {
        Minimal storage = new Minimal();
        CompletableFuture<Void> closed = storage.closeAsync();
        assertTrue(closed.isDone());
        assertNull(closed.get());
        assertEquals(1, storage.closes.get());
    }

    @Test void defaultCloseAsyncReportsAThrowingCloseThroughTheFuture() {
        Minimal storage = new Minimal();
        storage.closeFailure = new IllegalStateException("close failed");
        CompletableFuture<Void> closed = assertDoesNotThrow(storage::closeAsync);
        assertSame(storage.closeFailure, assertThrows(ExecutionException.class, closed::get).getCause());
    }

    @Test void emptyMetadataIsTermZeroWithNoVote() {
        assertEquals(0L, RaftStorage.PersistentMeta.EMPTY.currentTerm());
        assertEquals(Optional.empty(), RaftStorage.PersistentMeta.EMPTY.votedFor());
    }
}
