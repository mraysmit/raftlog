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

import dev.mars.raftlog.storage.RaftStorage.LogEntryData;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import java.util.zip.CRC32C;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Exact accounting of a WAL file for tests where many threads write at once.
 * <p>
 * Under concurrency a single refused operation cannot be checked byte-for-byte, because
 * other threads are legitimately writing at the same moment. What can be checked, and is
 * just as strict, is the end state: the WAL is append-only, so every byte in it must be
 * explained by an operation the storage accepted. A refusal that wrote anything leaves a
 * record nobody can account for.
 * <p>
 * The reader here is written independently of the production decoder, from the documented
 * record format, so that a bug in one is not mirrored in the other. It is strict: the whole
 * file must parse, every CRC must match, and nothing may trail the last record.
 */
final class WalRecords {
    private static final int MAGIC = 0x52414654;
    private static final int HEADER = 4 + 2 + 1 + 8 + 8 + 4;
    private static final int CRC = 4;
    static final byte TRUNCATE = 1;
    static final byte APPEND = 2;
    static final byte PREFIX = 3;

    /** One physical record. The payload is held as a hash and a length. */
    record Raw(byte type, long index, long term, int payloadHash, int payloadLength) implements Comparable<Raw> {
        @Override public int compareTo(Raw other) {
            int byIndex = Long.compare(index, other.index);
            if (byIndex != 0) return byIndex;
            int byType = Byte.compare(type, other.type);
            if (byType != 0) return byType;
            int byTerm = Long.compare(term, other.term);
            return byTerm != 0 ? byTerm : Integer.compare(payloadHash, other.payloadHash);
        }
    }

    private WalRecords() { }

    /** The record an accepted append of {@code entry} must have produced. */
    static Raw append(LogEntryData entry) {
        byte[] payload = entry.payload() == null ? new byte[0] : entry.payload();
        return new Raw(APPEND, entry.index(), entry.term(), Arrays.hashCode(payload), payload.length);
    }

    /** The record an accepted {@code truncateSuffix(fromIndex)} must have produced. */
    static Raw truncate(long fromIndex) {
        return new Raw(TRUNCATE, fromIndex, 0L, Arrays.hashCode(new byte[0]), 0);
    }

    /** Parses the whole file. Fails on a bad magic, a bad CRC, a short record or trailing bytes. */
    static List<Raw> read(Path walFile) throws IOException {
        List<Raw> records = new ArrayList<>();
        if (!Files.exists(walFile)) return records;
        ByteBuffer file = ByteBuffer.wrap(Files.readAllBytes(walFile));
        while (file.hasRemaining()) {
            int start = file.position();
            if (file.remaining() < HEADER + CRC) fail("WAL has " + file.remaining() + " unexplained bytes at offset " + start);
            if (file.getInt() != MAGIC) fail("WAL has no record at offset " + start);
            file.getShort();                                   // format version
            byte type = file.get();
            long index = file.getLong();
            long term = file.getLong();
            int length = file.getInt();
            if (length < 0 || file.remaining() < length + CRC) fail("WAL record at offset " + start + " runs past the end of the file");
            byte[] payload = new byte[length];
            file.get(payload);
            CRC32C crc = new CRC32C();
            crc.update(file.array(), start, HEADER + length);
            if ((int) crc.getValue() != file.getInt()) fail("WAL record at offset " + start + " has a bad CRC");
            records.add(new Raw(type, index, term, Arrays.hashCode(payload), length));
        }
        return records;
    }

    /**
     * The file must hold exactly the accepted records: none missing, none extra, none twice.
     * Order is not compared, since concurrent callers cannot know the order in which the
     * storage serialized them; the invariant checks make the order of appends follow the index.
     */
    static void assertExactly(Path walFile, Collection<Raw> accepted) throws IOException {
        List<Raw> expected = new ArrayList<>(accepted);
        List<Raw> actual = read(walFile);
        expected.sort(null);
        actual.sort(null);
        assertEquals(describe(expected), describe(actual),
                "every record in the WAL must come from an accepted operation, and every accepted operation must be in it");
    }

    /** A data directory may hold the WAL, the metadata file and the lock. Anything else is a leftover. */
    static void assertNoStrayFiles(Path dataDir) throws IOException {
        Set<String> names = new TreeSet<>();
        try (Stream<Path> files = Files.list(dataDir)) {
            files.forEach(f -> names.add(f.getFileName().toString()));
        }
        names.removeAll(Set.of("raft.log", "meta.dat", "raft.lock"));
        assertEquals(Set.of(), names, "staging files must not outlive the operation that created them");
    }

    private static String describe(List<Raw> records) {
        StringBuilder out = new StringBuilder(records.size() + " records\n");
        for (Raw r : records) {
            out.append(r.type() == APPEND ? "APPEND " : r.type() == TRUNCATE ? "TRUNCATE " : "PREFIX ")
                    .append(r.index()).append('@').append(r.term())
                    .append(" payload#").append(r.payloadHash()).append('/').append(r.payloadLength()).append('\n');
        }
        return out.toString();
    }
}
