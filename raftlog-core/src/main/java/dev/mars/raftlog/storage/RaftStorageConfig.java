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

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Configuration for Raft storage.
 * <p>
 * Configuration is resolved with the following priority (highest first):
 * <ol>
 *   <li>Programmatic values set via {@link Builder}</li>
 *   <li>System properties (e.g., {@code -Draftlog.dataDir=/path})</li>
 *   <li>Environment variables (e.g., {@code RAFTLOG_DATA_DIR})</li>
 *   <li>Properties file ({@code raftlog.properties} on classpath or in working directory)</li>
 *   <li>Default values</li>
 * </ol>
 * 
 * <h2>Configuration Properties</h2>
 * <table border="1">
 *   <tr><th>Property</th><th>System Property</th><th>Env Variable</th><th>Default</th></tr>
 *   <tr><td>dataDir</td><td>raftlog.dataDir</td><td>RAFTLOG_DATA_DIR</td><td>~/.raftlog/data</td></tr>
 *   <tr><td>syncEnabled</td><td>raftlog.syncEnabled</td><td>RAFTLOG_SYNC_ENABLED</td><td>true</td></tr>
 *   <tr><td>verifyWrites</td><td>raftlog.verifyWrites</td><td>RAFTLOG_VERIFY_WRITES</td><td>false</td></tr>
 *   <tr><td>minFreeSpaceMb</td><td>raftlog.minFreeSpaceMb</td><td>RAFTLOG_MIN_FREE_SPACE_MB</td><td>64</td></tr>
 *   <tr><td>maxPayloadSizeMb</td><td>raftlog.maxPayloadSizeMb</td><td>RAFTLOG_MAX_PAYLOAD_SIZE_MB</td><td>16</td></tr>
 * </table>
 * 
 * <h2>Example Properties File</h2>
 * <pre>
 * # raftlog.properties
 * raftlog.dataDir=/var/lib/raftlog/data
 * raftlog.syncEnabled=true
 * raftlog.verifyWrites=false
 * raftlog.minFreeSpaceMb=64
 * raftlog.maxPayloadSizeMb=16
 * </pre>
 * 
 * <h2>Programmatic Configuration</h2>
 * <pre>
 * RaftStorageConfig config = RaftStorageConfig.builder()
 *     .dataDir(Path.of("/var/lib/raftlog"))
 *     .syncEnabled(true)
 *     .verifyWrites(true)
 *     .build();
 * 
 * RaftStorage storage = new FileRaftStorage(config);
 * storage.open().join();
 * </pre>
 */
public final class RaftStorageConfig {

    private static final String PROPERTIES_FILE = "raftlog.properties";
    
    // Property keys
    private static final String PROP_DATA_DIR = "raftlog.dataDir";
    private static final String PROP_SYNC_ENABLED = "raftlog.syncEnabled";
    private static final String PROP_VERIFY_WRITES = "raftlog.verifyWrites";
    private static final String PROP_MIN_FREE_SPACE_MB = "raftlog.minFreeSpaceMb";
    private static final String PROP_MAX_PAYLOAD_SIZE_MB = "raftlog.maxPayloadSizeMb";
    
    // Environment variable keys
    private static final String ENV_DATA_DIR = "RAFTLOG_DATA_DIR";
    private static final String ENV_SYNC_ENABLED = "RAFTLOG_SYNC_ENABLED";
    private static final String ENV_VERIFY_WRITES = "RAFTLOG_VERIFY_WRITES";
    private static final String ENV_MIN_FREE_SPACE_MB = "RAFTLOG_MIN_FREE_SPACE_MB";
    private static final String ENV_MAX_PAYLOAD_SIZE_MB = "RAFTLOG_MAX_PAYLOAD_SIZE_MB";
    
    // Defaults
    private static final Path DEFAULT_DATA_DIR = Path.of(System.getProperty("user.home"), ".raftlog", "data");
    private static final boolean DEFAULT_SYNC_ENABLED = true;
    private static final boolean DEFAULT_VERIFY_WRITES = false;
    private static final int DEFAULT_MIN_FREE_SPACE_MB = 64;
    private static final int DEFAULT_MAX_PAYLOAD_SIZE_MB = 16;

    private final Path dataDir;
    private final boolean syncEnabled;
    private final boolean verifyWrites;
    private final int minFreeSpaceMb;
    private final int maxPayloadSizeMb;

    private RaftStorageConfig(Builder builder) {
        this.dataDir = builder.dataDir;
        this.syncEnabled = builder.syncEnabled;
        this.verifyWrites = builder.verifyWrites;
        this.minFreeSpaceMb = builder.minFreeSpaceMb;
        this.maxPayloadSizeMb = builder.maxPayloadSizeMb;
    }

    /** Data directory for WAL and metadata files. */
    public Path dataDir() {
        return dataDir;
    }

    /** Whether fsync is enabled (should be true in production). */
    public boolean syncEnabled() {
        return syncEnabled;
    }

    /** Whether to verify writes by reading back and checking CRC. */
    public boolean verifyWrites() {
        return verifyWrites;
    }

    /** Minimum free disk space in MB required before writes. */
    public int minFreeSpaceMb() {
        return minFreeSpaceMb;
    }

    /** Maximum payload size in MB per log entry. */
    public int maxPayloadSizeMb() {
        return maxPayloadSizeMb;
    }

    /** Minimum free disk space in bytes. */
    public long minFreeSpaceBytes() {
        return (long) minFreeSpaceMb * 1024 * 1024;
    }

    /** Maximum payload size in bytes. */
    public int maxPayloadSizeBytes() {
        return maxPayloadSizeMb * 1024 * 1024;
    }

    @Override
    public String toString() {
        return "RaftStorageConfig{" +
                "dataDir=" + dataDir +
                ", syncEnabled=" + syncEnabled +
                ", verifyWrites=" + verifyWrites +
                ", minFreeSpaceMb=" + minFreeSpaceMb +
                ", maxPayloadSizeMb=" + maxPayloadSizeMb +
                '}';
    }

    /**
     * Creates a new builder with defaults resolved from system properties,
     * environment variables, and properties file.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Loads configuration from all sources with default priority.
     * Shorthand for {@code RaftStorageConfig.builder().build()}.
     */
    public static RaftStorageConfig load() {
        return builder().build();
    }

    /**
     * Builder for {@link RaftStorageConfig}.
     * <p>
     * Values not explicitly set will be resolved from system properties,
     * environment variables, properties file, or defaults (in that order).
     */
    public static final class Builder {
        private Path dataDir;
        private Boolean syncEnabled;
        private Boolean verifyWrites;
        private Integer minFreeSpaceMb;
        private Integer maxPayloadSizeMb;
        
        private Properties fileProperties;

        private Builder() {
            // Load properties file once
            this.fileProperties = loadPropertiesFile();
        }

        /** Sets the data directory. */
        public Builder dataDir(Path dataDir) {
            this.dataDir = dataDir;
            return this;
        }

        /** Sets the data directory from a string path. */
        public Builder dataDir(String dataDir) {
            this.dataDir = Path.of(dataDir);
            return this;
        }

        /** Enables or disables fsync (default: true). */
        public Builder syncEnabled(boolean syncEnabled) {
            this.syncEnabled = syncEnabled;
            return this;
        }

        /** Enables or disables write verification (default: false). */
        public Builder verifyWrites(boolean verifyWrites) {
            this.verifyWrites = verifyWrites;
            return this;
        }

        /** Sets minimum free disk space in MB (default: 64). */
        public Builder minFreeSpaceMb(int minFreeSpaceMb) {
            this.minFreeSpaceMb = minFreeSpaceMb;
            return this;
        }

        /** Sets maximum payload size in MB (default: 16). */
        public Builder maxPayloadSizeMb(int maxPayloadSizeMb) {
            this.maxPayloadSizeMb = maxPayloadSizeMb;
            return this;
        }

        /**
         * Builds the configuration, resolving unset values from
         * system properties, environment variables, properties file, or defaults.
         */
        public RaftStorageConfig build() {
            // Resolve each value with priority: programmatic > sysprop > env > file > default
            if (dataDir == null) {
                dataDir = resolvePath(PROP_DATA_DIR, ENV_DATA_DIR, DEFAULT_DATA_DIR);
            }
            if (syncEnabled == null) {
                syncEnabled = resolveBoolean(PROP_SYNC_ENABLED, ENV_SYNC_ENABLED, DEFAULT_SYNC_ENABLED);
            }
            if (verifyWrites == null) {
                verifyWrites = resolveBoolean(PROP_VERIFY_WRITES, ENV_VERIFY_WRITES, DEFAULT_VERIFY_WRITES);
            }
            if (minFreeSpaceMb == null) {
                minFreeSpaceMb = resolveInt(PROP_MIN_FREE_SPACE_MB, ENV_MIN_FREE_SPACE_MB, DEFAULT_MIN_FREE_SPACE_MB);
            }
            if (maxPayloadSizeMb == null) {
                maxPayloadSizeMb = resolveInt(PROP_MAX_PAYLOAD_SIZE_MB, ENV_MAX_PAYLOAD_SIZE_MB, DEFAULT_MAX_PAYLOAD_SIZE_MB);
            }
            
            return new RaftStorageConfig(this);
        }

        private Path resolvePath(String sysProp, String envVar, Path defaultValue) {
            // 1. System property
            String value = System.getProperty(sysProp);
            if (value != null && !value.isBlank()) {
                return Path.of(value);
            }
            
            // 2. Environment variable
            value = System.getenv(envVar);
            if (value != null && !value.isBlank()) {
                return Path.of(value);
            }
            
            // 3. Properties file
            value = fileProperties.getProperty(sysProp);
            if (value != null && !value.isBlank()) {
                return Path.of(value);
            }
            
            // 4. Default
            return defaultValue;
        }

        private boolean resolveBoolean(String sysProp, String envVar, boolean defaultValue) {
            // 1. System property
            String value = System.getProperty(sysProp);
            if (value != null && !value.isBlank()) {
                return Boolean.parseBoolean(value);
            }
            
            // 2. Environment variable
            value = System.getenv(envVar);
            if (value != null && !value.isBlank()) {
                return Boolean.parseBoolean(value);
            }
            
            // 3. Properties file
            value = fileProperties.getProperty(sysProp);
            if (value != null && !value.isBlank()) {
                return Boolean.parseBoolean(value);
            }
            
            // 4. Default
            return defaultValue;
        }

        private int resolveInt(String sysProp, String envVar, int defaultValue) {
            // 1. System property
            String value = System.getProperty(sysProp);
            if (value != null && !value.isBlank()) {
                try {
                    return Integer.parseInt(value);
                } catch (NumberFormatException ignored) {}
            }
            
            // 2. Environment variable
            value = System.getenv(envVar);
            if (value != null && !value.isBlank()) {
                try {
                    return Integer.parseInt(value);
                } catch (NumberFormatException ignored) {}
            }
            
            // 3. Properties file
            value = fileProperties.getProperty(sysProp);
            if (value != null && !value.isBlank()) {
                try {
                    return Integer.parseInt(value);
                } catch (NumberFormatException ignored) {}
            }
            
            // 4. Default
            return defaultValue;
        }

        private static Properties loadPropertiesFile() {
            Properties props = new Properties();
            
            // Try classpath first
            try (InputStream is = RaftStorageConfig.class.getClassLoader()
                    .getResourceAsStream(PROPERTIES_FILE)) {
                if (is != null) {
                    props.load(is);
                    return props;
                }
            } catch (IOException ignored) {}
            
            // Try working directory
            Path localFile = Path.of(PROPERTIES_FILE);
            if (Files.exists(localFile)) {
                try (InputStream is = Files.newInputStream(localFile)) {
                    props.load(is);
                } catch (IOException ignored) {}
            }
            
            return props;
        }
    }
}
