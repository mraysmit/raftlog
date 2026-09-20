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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Node identifiers and paths come from outside and end up in log lines. A value that carries
 * a line break could forge a second log record, so every form of line break has to be escaped,
 * and truncation must not leave half a character behind.
 */
class LogSanitizationTest {
    private static String clean(String value, int limit) {
        return FileRaftStorage.boundedSingleLine(value, limit);
    }

    @Test void ordinaryTextIsUnchanged() {
        assertEquals("node-1", clean("node-1", 64));
        assertEquals("", clean("", 64));
    }

    @Test void carriageReturnLineFeedAndTabAreEscapedByName() {
        assertEquals("a\\rb\\nc\\td", clean("a\rb\nc\td", 64));
    }

    @Test void unicodeLineAndParagraphSeparatorsAreEscaped() {
        // Not ISO control characters, but many log viewers break the line on them.
        assertEquals("a\\u2028b\\u2029c", clean("a\u2028b\u2029c", 64));
    }

    @Test void otherControlCharactersAreEscapedAsFourHexDigits() {
        assertEquals("bell\\u0007nul\\u0000del\\u007f", clean("bell\u0007nul\u0000del\u007f", 64));
    }

    @Test void limitOfZeroKeepsNothingButStillReportsTheLength() {
        assertEquals("…[3 chars]", clean("abc", 0));
    }

    @Test void valueAtExactlyTheLimitIsNotMarkedAsTruncated() {
        assertEquals("abcd", clean("abcd", 4));
    }

    @Test void longValueIsCutAndReportsItsOriginalLength() {
        String result = clean("abcdefghij", 4);
        assertTrue(result.startsWith("abcd"), result);
        assertTrue(result.endsWith("[10 chars]"), result);
    }

    @Test void truncationNeverSplitsASurrogatePair() {
        String emoji = "\uD83D\uDE00";                       // one code point, two chars
        String result = clean("abc" + emoji + "tail", 4);       // the limit falls between the two halves
        assertTrue(result.startsWith("abc"), result);
        assertFalse(result.contains("\uD83D"), "a lone high surrogate must not be emitted: " + result);
        assertTrue(result.endsWith("[9 chars]"), result);
    }

    @Test void surrogatePairThatFitsIsKeptWhole() {
        String emoji = "\uD83D\uDE00";
        assertTrue(clean("abc" + emoji + "tail", 5).startsWith("abc" + emoji));
    }

    @Test void lineBreaksBeyondTheLimitCannotLeakThrough() {
        String result = clean("abcd\nFORGED", 4);
        assertFalse(result.contains("\n"), result);
        assertFalse(result.contains("FORGED"), result);
    }
}
