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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Configuration arrives from four places: the builder, system properties, environment variables
 * and a properties file, in that order of precedence. Until this test only the first two were
 * exercised. Whatever the source, the resolved values are validated, because a bad limit is not a
 * cosmetic problem: the payload limit is converted to bytes in 32-bit arithmetic, and a value that
 * overflows it, or is zero or negative, would make every payload too large.
 */
class RaftStorageConfigSourcesTest {
    private static final List<String> PROPERTIES = List.of("raftlog.dataDir", "raftlog.syncEnabled",
            "raftlog.verifyWrites", "raftlog.minFreeSpaceMb", "raftlog.maxPayloadSizeMb");

    private final Map<String, String> env = new HashMap<>();
    private final Properties file = new Properties();
    private final Map<String, String> savedSystemProperties = new HashMap<>();
    private UnaryOperator<String> realEnvironment;
    private Supplier<Properties> realPropertiesFile;

    @BeforeEach void isolateEverySource() {
        realEnvironment = RaftStorageConfig.environment;
        realPropertiesFile = RaftStorageConfig.propertiesFile;
        RaftStorageConfig.environment = env::get;
        RaftStorageConfig.propertiesFile = () -> file;
        for (String name : PROPERTIES) {
            savedSystemProperties.put(name, System.getProperty(name));
            System.clearProperty(name);
        }
    }

    @AfterEach void restoreEverySource() {
        RaftStorageConfig.environment = realEnvironment;
        RaftStorageConfig.propertiesFile = realPropertiesFile;
        savedSystemProperties.forEach((name, value) -> {
            if (value == null) System.clearProperty(name); else System.setProperty(name, value);
        });
    }

    // ------------------------------------------------------------------ precedence

    @Test void environmentVariablesAreUsedWhenNothingElseIsSet() {
        env.put("RAFTLOG_DATA_DIR", "from-env");
        env.put("RAFTLOG_VERIFY_WRITES", "true");
        env.put("RAFTLOG_MIN_FREE_SPACE_MB", "7");
        env.put("RAFTLOG_MAX_PAYLOAD_SIZE_MB", "9");
        RaftStorageConfig config = RaftStorageConfig.load();
        assertEquals(Path.of("from-env"), config.dataDir());
        assertTrue(config.verifyWrites());
        assertEquals(7L * 1024 * 1024, config.minFreeSpaceBytes());
        assertEquals(9 * 1024 * 1024, config.maxPayloadSizeBytes());
    }

    @Test void propertiesFileIsUsedWhenNothingElseIsSet() {
        file.setProperty("raftlog.dataDir", "from-file");
        file.setProperty("raftlog.verifyWrites", "true");
        file.setProperty("raftlog.minFreeSpaceMb", "5");
        file.setProperty("raftlog.maxPayloadSizeMb", "3");
        RaftStorageConfig config = RaftStorageConfig.load();
        assertEquals(Path.of("from-file"), config.dataDir());
        assertTrue(config.verifyWrites());
        assertEquals(5L * 1024 * 1024, config.minFreeSpaceBytes());
        assertEquals(3 * 1024 * 1024, config.maxPayloadSizeBytes());
    }

    @Test void eachSourceOverridesTheOnesBelowIt() {
        file.setProperty("raftlog.maxPayloadSizeMb", "1");
        assertEquals(1 * 1024 * 1024, RaftStorageConfig.load().maxPayloadSizeBytes());
        env.put("RAFTLOG_MAX_PAYLOAD_SIZE_MB", "2");
        assertEquals(2 * 1024 * 1024, RaftStorageConfig.load().maxPayloadSizeBytes());
        System.setProperty("raftlog.maxPayloadSizeMb", "3");
        assertEquals(3 * 1024 * 1024, RaftStorageConfig.load().maxPayloadSizeBytes());
        assertEquals(4 * 1024 * 1024, RaftStorageConfig.builder().maxPayloadSizeMb(4).build().maxPayloadSizeBytes());
    }

    @Test void blankValuesAreTreatedAsAbsent() {
        env.put("RAFTLOG_DATA_DIR", "   ");
        env.put("RAFTLOG_MAX_PAYLOAD_SIZE_MB", "");
        env.put("RAFTLOG_VERIFY_WRITES", " ");
        file.setProperty("raftlog.dataDir", "from-file");
        file.setProperty("raftlog.maxPayloadSizeMb", "6");
        file.setProperty("raftlog.verifyWrites", "true");
        RaftStorageConfig config = RaftStorageConfig.load();
        assertEquals(Path.of("from-file"), config.dataDir());
        assertEquals(6 * 1024 * 1024, config.maxPayloadSizeBytes());
        assertTrue(config.verifyWrites());
    }

    @Test void blankSystemPropertiesAndBlankFileEntriesAreTreatedAsAbsentToo() {
        for (String name : PROPERTIES) System.setProperty(name, "  ");
        file.setProperty("raftlog.dataDir", " ");
        file.setProperty("raftlog.verifyWrites", "");
        file.setProperty("raftlog.minFreeSpaceMb", "   ");
        file.setProperty("raftlog.maxPayloadSizeMb", "");
        env.put("RAFTLOG_MIN_FREE_SPACE_MB", "12");
        RaftStorageConfig config = RaftStorageConfig.load();
        assertEquals(12, config.minFreeSpaceMb(), "the environment is consulted after a blank system property");
        assertEquals(16, config.maxPayloadSizeMb(), "and the default after a blank file entry");
        assertFalse(config.verifyWrites());
        assertTrue(config.syncEnabled());
    }

    // ------------------------------------------------------------------ a value that cannot be parsed is an error

    /** One way of supplying a value: which source, and how to put a value into it. */
    private enum Source { SYSTEM_PROPERTY, ENVIRONMENT_VARIABLE, PROPERTIES_FILE }

    private void supply(Source source, String property, String environmentVariable, String value) {
        switch (source) {
            case SYSTEM_PROPERTY -> System.setProperty(property, value);
            case ENVIRONMENT_VARIABLE -> env.put(environmentVariable, value);
            case PROPERTIES_FILE -> file.setProperty(property, value);
        }
    }

    private static void assertNames(IllegalArgumentException refused, String... expectedParts) {
        for (String part : expectedParts) {
            assertTrue(refused.getMessage().contains(part), "message should mention '" + part + "': " + refused.getMessage());
        }
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(Source.class)
    void integerThatCannotBeParsedIsRefusedAndTheMessageSaysWhereItCameFrom(Source source) {
        // An operator who writes 32MB must not silently get the default of 16.
        for (String bad : List.of("lots", "32MB", "1.5", "0x10", "9999999999999999999", "1 6")) {
            supply(source, "raftlog.maxPayloadSizeMb", "RAFTLOG_MAX_PAYLOAD_SIZE_MB", bad);
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, RaftStorageConfig::load, bad);
            assertNames(refused, "raftlog.maxPayloadSizeMb", "'" + bad + "'");
        }
        supply(source, "raftlog.maxPayloadSizeMb", "RAFTLOG_MAX_PAYLOAD_SIZE_MB", "16");
        supply(source, "raftlog.minFreeSpaceMb", "RAFTLOG_MIN_FREE_SPACE_MB", "plenty");
        assertNames(assertThrows(IllegalArgumentException.class, RaftStorageConfig::load), "raftlog.minFreeSpaceMb", "'plenty'");
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(Source.class)
    void booleanThatCannotBeParsedIsRefusedAndTheMessageSaysWhereItCameFrom(Source source) {
        // "ture" must not quietly leave write verification switched off.
        for (String bad : List.of("ture", "yes", "1", "on", "enabled", "t")) {
            supply(source, "raftlog.verifyWrites", "RAFTLOG_VERIFY_WRITES", bad);
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, RaftStorageConfig::load, bad);
            assertNames(refused, "raftlog.verifyWrites", "'" + bad + "'");
        }
        supply(source, "raftlog.verifyWrites", "RAFTLOG_VERIFY_WRITES", "true");
        supply(source, "raftlog.syncEnabled", "RAFTLOG_SYNC_ENABLED", "nope");
        assertNames(assertThrows(IllegalArgumentException.class, RaftStorageConfig::load), "raftlog.syncEnabled", "'nope'");
    }

    @Test void messageNamesTheExactSourceSoTheOperatorKnowsWhereToLook() {
        env.put("RAFTLOG_MAX_PAYLOAD_SIZE_MB", "lots");
        assertNames(assertThrows(IllegalArgumentException.class, RaftStorageConfig::load),
                "environment variable", "RAFTLOG_MAX_PAYLOAD_SIZE_MB");
        env.clear();
        file.setProperty("raftlog.maxPayloadSizeMb", "lots");
        assertNames(assertThrows(IllegalArgumentException.class, RaftStorageConfig::load), "properties file", "raftlog.properties");
        file.clear();
        System.setProperty("raftlog.maxPayloadSizeMb", "lots");
        assertNames(assertThrows(IllegalArgumentException.class, RaftStorageConfig::load), "system property");
    }

    @Test void badValueInALowerPrioritySourceIsRefusedEvenWhenAHigherOneOverridesIt() {
        // Otherwise it waits, unnoticed, for the day somebody removes the override.
        System.setProperty("raftlog.maxPayloadSizeMb", "8");
        env.put("RAFTLOG_MAX_PAYLOAD_SIZE_MB", "lots");
        assertNames(assertThrows(IllegalArgumentException.class, RaftStorageConfig::load), "RAFTLOG_MAX_PAYLOAD_SIZE_MB");
        env.clear();
        file.setProperty("raftlog.verifyWrites", "perhaps");
        System.setProperty("raftlog.verifyWrites", "true");
        assertNames(assertThrows(IllegalArgumentException.class, RaftStorageConfig::load), "properties file");
    }

    @Test void valueSetThroughTheBuilderIsNotResolvedFromAnySourceAtAll() {
        // The builder is code, not operator input: it wins outright and nothing else is read for it.
        env.put("RAFTLOG_MAX_PAYLOAD_SIZE_MB", "lots");
        assertEquals(4, RaftStorageConfig.builder().maxPayloadSizeMb(4).build().maxPayloadSizeMb());
    }

    @Test void surroundingWhitespaceAndLetterCaseAreNotErrors() {
        env.put("RAFTLOG_MAX_PAYLOAD_SIZE_MB", "  32 ");
        env.put("RAFTLOG_VERIFY_WRITES", " TRUE ");
        env.put("RAFTLOG_DATA_DIR", "  padded-dir  ");
        RaftStorageConfig config = RaftStorageConfig.load();
        assertEquals(32, config.maxPayloadSizeMb());
        assertTrue(config.verifyWrites());
        assertEquals(Path.of("padded-dir"), config.dataDir());
    }

    @Test void dataDirectoryThatIsNotAValidPathIsRefused() {
        env.put("RAFTLOG_DATA_DIR", "bad\u0000path");
        assertNames(assertThrows(IllegalArgumentException.class, RaftStorageConfig::load), "raftlog.dataDir", "RAFTLOG_DATA_DIR");
    }

    // ------------------------------------------------------------------ fsync can never be switched off

    @Test void syncCannotBeDisabledThroughTheEnvironment() {
        env.put("RAFTLOG_SYNC_ENABLED", "false");
        assertThrows(IllegalArgumentException.class, RaftStorageConfig::load);
    }

    @Test void syncCannotBeDisabledThroughThePropertiesFile() {
        file.setProperty("raftlog.syncEnabled", "false");
        assertThrows(IllegalArgumentException.class, RaftStorageConfig::load);
    }

    // ------------------------------------------------------------------ limits are validated, whatever the source

    @ParameterizedTest
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE, 2048, 4096, Integer.MAX_VALUE})
    void payloadLimitThatIsNotPositiveOrOverflowsItsByteSizeIsRefused(int megabytes) {
        // 2048 MB is 2^31 bytes, which as an int is negative; 4096 MB wraps to zero.
        assertThrows(IllegalArgumentException.class,
                () -> RaftStorageConfig.builder().maxPayloadSizeMb(megabytes).build(), "builder");
        env.put("RAFTLOG_MAX_PAYLOAD_SIZE_MB", String.valueOf(megabytes));
        assertThrows(IllegalArgumentException.class, RaftStorageConfig::load, "environment");
        env.clear();
        file.setProperty("raftlog.maxPayloadSizeMb", String.valueOf(megabytes));
        assertThrows(IllegalArgumentException.class, RaftStorageConfig::load, "properties file");
        file.clear();
        System.setProperty("raftlog.maxPayloadSizeMb", String.valueOf(megabytes));
        assertThrows(IllegalArgumentException.class, RaftStorageConfig::load, "system property");
    }

    @Test void largestPayloadLimitThatFitsIsAccepted() {
        RaftStorageConfig config = RaftStorageConfig.builder().maxPayloadSizeMb(2047).build();
        assertEquals(2047 * 1024 * 1024, config.maxPayloadSizeBytes());
        assertTrue(config.maxPayloadSizeBytes() > 0);
    }

    @Test void negativeFreeSpaceReserveIsRefusedAndZeroIsAllowed() {
        assertThrows(IllegalArgumentException.class, () -> RaftStorageConfig.builder().minFreeSpaceMb(-1).build());
        env.put("RAFTLOG_MIN_FREE_SPACE_MB", "-5");
        assertThrows(IllegalArgumentException.class, RaftStorageConfig::load);
        env.clear();
        assertEquals(0L, RaftStorageConfig.builder().minFreeSpaceMb(0).build().minFreeSpaceBytes());
        assertEquals((long) Integer.MAX_VALUE * 1024 * 1024,
                RaftStorageConfig.builder().minFreeSpaceMb(Integer.MAX_VALUE).build().minFreeSpaceBytes());
    }

    // ------------------------------------------------------------------ finding the properties file

    @Test void propertiesFileIsFoundOnTheClasspathBeforeTheWorkingDirectory(@TempDir Path root) throws Exception {
        Path classpath = Files.createDirectory(root.resolve("classpath"));
        Path workingDirectory = Files.createDirectory(root.resolve("cwd"));
        Files.writeString(classpath.resolve("raftlog.properties"), "raftlog.maxPayloadSizeMb=11");
        Files.writeString(workingDirectory.resolve("raftlog.properties"), "raftlog.maxPayloadSizeMb=22");
        try (URLClassLoader loader = new URLClassLoader(new URL[]{classpath.toUri().toURL()}, null)) {
            Properties found = RaftStorageConfig.Builder.loadPropertiesFile(loader, workingDirectory);
            assertEquals("11", found.getProperty("raftlog.maxPayloadSizeMb"));
        }
    }

    @Test void propertiesFileIsFoundInTheWorkingDirectoryWhenNotOnTheClasspath(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("raftlog.properties"), "raftlog.maxPayloadSizeMb=22");
        try (URLClassLoader empty = new URLClassLoader(new URL[0], null)) {
            assertEquals("22", RaftStorageConfig.Builder.loadPropertiesFile(empty, root).getProperty("raftlog.maxPayloadSizeMb"));
        }
    }

    @Test void classpathPropertiesFileThatCannotBeReadIsAnErrorNotAReasonToTryElsewhere(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("raftlog.properties"), "raftlog.maxPayloadSizeMb=22");
        ClassLoader failing = new ClassLoader(null) {
            @Override public java.io.InputStream getResourceAsStream(String name) {
                return new java.io.InputStream() {
                    @Override public int read() throws java.io.IOException { throw new java.io.IOException("Injected read failure"); }
                };
            }
        };
        // The operator put a file on the classpath. Starting on some other file, or on defaults,
        // because it could not be read would hide that from them.
        java.io.UncheckedIOException refused = assertThrows(java.io.UncheckedIOException.class,
                () -> RaftStorageConfig.Builder.loadPropertiesFile(failing, root));
        assertTrue(refused.getMessage().contains("raftlog.properties"), refused.getMessage());
    }

    @Test void workingDirectoryPropertiesFileThatCannotBeReadIsAnError(@TempDir Path root) throws Exception {
        Files.createDirectory(root.resolve("raftlog.properties"));             // present, but not a readable file
        try (URLClassLoader empty = new URLClassLoader(new URL[0], null)) {
            java.io.UncheckedIOException refused = assertThrows(java.io.UncheckedIOException.class,
                    () -> RaftStorageConfig.Builder.loadPropertiesFile(empty, root));
            assertTrue(refused.getMessage().contains("raftlog.properties"), refused.getMessage());
        }
    }

    @Test void missingPropertiesFileIsNotAnError(@TempDir Path root) throws Exception {
        try (URLClassLoader empty = new URLClassLoader(new URL[0], null)) {
            assertTrue(RaftStorageConfig.Builder.loadPropertiesFile(empty, root).isEmpty());
        }
    }

    @Test void malformedPropertiesFileIsAnError(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("raftlog.properties"), "raftlog.dataDir=C:\\\\data\\u00ZZ");   // a bad unicode escape
        try (URLClassLoader empty = new URLClassLoader(new URL[0], null)) {
            assertThrows(RuntimeException.class, () -> RaftStorageConfig.Builder.loadPropertiesFile(empty, root));
        }
    }
}
