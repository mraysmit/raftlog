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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The mutation gate. A required step of every release, and of any change that adds or alters a
 * refusal, a failure path or a safety rule.
 *
 * <pre>
 *   java scripts/MutationCheck.java            run every mutant
 *   java scripts/MutationCheck.java --verify   only check that every mutation still applies
 *   java scripts/MutationCheck.java M03 M09    run the named mutants
 *   java scripts/MutationCheck.java --self-test  prove the gate can report a survivor
 * </pre>
 *
 * Line coverage says a line ran. It says nothing about whether any test would notice if the line
 * were wrong. Each mutant below removes or inverts ONE safety rule. The tests named for it are
 * then run and MUST FAIL. If they pass, the rule is unguarded and the gate fails.
 * <p>
 * Three outcomes fail the gate, because each means the gate is no longer telling the truth:
 * <ul>
 *   <li>SURVIVED: the tests passed with the rule removed;</li>
 *   <li>STALE: the text to mutate is no longer in the source exactly once, so the mutant was
 *       silently not being applied. Update the mutation to match the code;</li>
 *   <li>INVALID: the mutant did not compile. A build that fails to compile also exits non-zero,
 *       and must never be mistaken for a mutant that was killed.</li>
 * </ul>
 * Sources are restored after every mutant, on any exit, and verified by checksum. Run from the
 * repository root. When a safety rule is added, add its mutant here in the same change.
 */
public class MutationCheck {
    record Mutant(String id, String rule, String file, String find, String replace, String tests) { }

    static final String STORAGE = "raftlog-core/src/main/java/dev/mars/raftlog/storage/FileRaftStorage.java";
    static final String PLAN = "raftlog-core/src/main/java/dev/mars/raftlog/storage/AppendPlan.java";
    static final String CONFIG = "raftlog-core/src/main/java/dev/mars/raftlog/storage/RaftStorageConfig.java";

    static final String REFUSALS = "FileRaftStorageAdversarialTest*,FileRaftStorageRecoveryContractTest,FileRaftStorageInvariantEdgeCaseTest";
    static final String FAILURES = "FileRaftStorageFailurePathTest";

    static final List<Mutant> MUTANTS = List.of(
        new Mutant("M01", "a refused append writes nothing", STORAGE,
            "            validateAppend(acceptedEntries);\n",
            "            try { writeRecord(TYPE_APPEND, 1L, 0L, new byte[0]); } catch (IOException mutation) { }\n"
                + "            validateAppend(acceptedEntries);\n",
            REFUSALS + ",ProtectionGuaranteeTest*"),
        new Mutant("M02", "a refused metadata update leaves no staging file", STORAGE,
            "            validateMetadataUpdate(currentTerm, votedFor);\n",
            "            try { Files.write(dataDir.resolve(META_TMP_FILE), new byte[]{1}); } catch (IOException mutation) { }\n"
                + "            validateMetadataUpdate(currentTerm, votedFor);\n",
            "FileRaftStorageRecoveryContractTest,ProtectionGuaranteeTest*,FileRaftStorageInvariantEdgeCaseTest"),
        new Mutant("M03", "appends are validated", STORAGE,
            "            validateAppend(acceptedEntries);\n", "", REFUSALS),
        new Mutant("M04", "suffix truncations are validated", STORAGE,
            "            validateSuffixTruncation(fromIndex);\n", "", REFUSALS),
        new Mutant("M05", "metadata updates are validated", STORAGE,
            "            validateMetadataUpdate(currentTerm, votedFor);\n", "",
            "FileRaftStorageRecoveryContractTest,FileRaftStorageInvariantEdgeCaseTest"),
        new Mutant("M06", "an existing log must be replayed before it is written to", STORAGE,
            "    private void validateAppend(List<LogEntryData> entries) {\n        requireKnownLogState();\n",
            "    private void validateAppend(List<LogEntryData> entries) {\n",
            REFUSALS + "," + FAILURES),
        new Mutant("M07", "the last term survives a suffix truncation", STORAGE,
            "                forgetTermsFrom(fromIndex);\n", "                termRuns.clear();\n",
            "FileRaftStorageInvariantEdgeCaseTest"),
        new Mutant("M08", "a failed append leaves the tail unknown", STORAGE,
            "                // Part of the batch may be on disk. The tail is unknown until replay.\n                logStateKnown = false;\n",
            "                // Part of the batch may be on disk. The tail is unknown until replay.\n",
            "FileRaftStorageInvariantEdgeCaseTest," + FAILURES),
        new Mutant("M09", "a read that ends early inside the file is an I/O failure, not a verdict", STORAGE,
            "                if (position + requested <= fileSize) {\n", "                if (false) {\n", FAILURES),
        new Mutant("M10", "a torn compaction boundary is reported, never repaired", STORAGE,
            "        if (type == TYPE_PREFIX) return false;\n", "", FAILURES),
        new Mutant("M11", "the boundary of a compacted log with no PREFIX record is inferred", STORAGE,
            "        prefixBoundary = entries.isEmpty() ? boundary : entries.getFirst().index() - 1;\n",
            "        prefixBoundary = boundary;\n", "GoldenFileCompatibilityTest"),
        new Mutant("M12", "replay refuses a log that is not a well-formed Raft log", STORAGE,
            "        validateReplayedLog(entries, boundary, logPath);\n", "",
            FAILURES + ",GoldenFileCompatibilityTest"),
        new Mutant("M13", "the write limit is not a read limit", STORAGE,
            "        if (payloadLen > fileSize - pos - HEADER_SIZE - CRC_SIZE) {\n",
            "        if (payloadLen > maxPayloadSize || payloadLen > fileSize - pos - HEADER_SIZE - CRC_SIZE) {\n", FAILURES),
        new Mutant("M14", "unreadable metadata refuses updates", STORAGE,
            "            metaUnreadable = true;\n", "            metaUnreadable = false;\n",
            "FileRaftStorageInvariantEdgeCaseTest," + FAILURES),
        new Mutant("M15", "the first fencing failure is kept", STORAGE,
            "        if (fatalFailure == null) fatalFailure = failure;\n", "        fatalFailure = failure;\n", FAILURES),
        new Mutant("M16", "disk space is checked before the first record of a batch", STORAGE,
            "            requireDiskSpaceFor(acceptedEntries);\n", "", "FileRaftStorageInvariantEdgeCaseTest," + FAILURES),
        new Mutant("M17", "a suffix truncation cannot reach into the compacted prefix", STORAGE,
            "        if (fromIndex <= prefixBoundary) {\n", "        if (false) {\n",
            "FileRaftStorageRecoveryContractTest,GoldenFileCompatibilityTest"),
        new Mutant("M18", "index arithmetic does not wrap at the top of the index space", STORAGE,
            "            if (previousIndex == Long.MAX_VALUE || entry.index() != previousIndex + 1) {\n",
            "            if (entry.index() != previousIndex + 1) {\n", "FileRaftStorageInvariantEdgeCaseTest"),
        new Mutant("M19", "a record from a newer format is unsupported, not corrupt", STORAGE,
            "        boolean newerFormat = version > MAX_SUPPORTED_VERSION;\n", "        boolean newerFormat = false;\n",
            "FileRaftStorageInvariantEdgeCaseTest," + FAILURES),
        new Mutant("M20", "the planner refuses logs that have diverged", PLAN,
            "            if (!Arrays.equals(payloadOf(held), payloadOf(incoming))) {\n", "            if (false) {\n",
            "AppendPlanStrictnessTest*"),
        new Mutant("M21", "the planner refuses a gap between the log and the incoming entries", PLAN,
            "        if (startIndex - 1 > lastIndex) {\n", "        if (false) {\n", "AppendPlanStrictnessTest*"),
        new Mutant("M22", "the planner requires the compaction boundary of a compacted log", PLAN,
            "        if (!currentLog.isEmpty() && currentLog.getFirst().index() - 1 != compactionBoundary) {\n",
            "        if (false) {\n", "AppendPlanStrictnessTest*"),
        new Mutant("M23", "a refused plan leaves the in-memory log untouched", PLAN,
            "            if (entriesToAppend.getFirst().index() - 1 != lastKept) {\n", "            if (false) {\n",
            "AppendPlanStrictnessTest*"),
        new Mutant("M24", "the payload limit is validated", CONFIG,
            "            if (maxPayloadSizeMb < 1 || maxPayloadSizeMb > MAX_PAYLOAD_SIZE_MB_LIMIT) {\n",
            "            if (false) {\n", "RaftStorageConfigSourcesTest,ConfigResolverTest*"),
        new Mutant("M25", "fsync cannot be disabled through the builder", CONFIG,
            "        public Builder syncEnabled(boolean syncEnabled) {\n            if (!syncEnabled) {\n",
            "        public Builder syncEnabled(boolean syncEnabled) {\n            if (false) {\n",
            "RaftStorageConfigSourcesTest,ConfigResolverTest*,HighCoverageTest*,CoverageBoostTest*"),
        new Mutant("M28", "fsync cannot be disabled through a property, the environment or the file", CONFIG,
            "            }\n            if (!syncEnabled) {\n", "            }\n            if (false) {\n",
            "RaftStorageConfigSourcesTest,ConfigResolverTest*"),
        new Mutant("M26", "a value that cannot be parsed is an error, not a reason to use the default", CONFIG,
            "                } catch (IllegalArgumentException e) {\n",
            "                } catch (IllegalArgumentException e) {\n                    if (true) continue;\n",
            "RaftStorageConfigSourcesTest,ConfigResolverTest*"),
        new Mutant("M27", "a properties file that cannot be read is an error", CONFIG,
            "                    throw new java.io.UncheckedIOException(\"Cannot read \" + localFile.toAbsolutePath(), e);\n",
            "                    LOG.warn(\"ignored\", e);\n", "RaftStorageConfigSourcesTest")
    );

    static final Pattern TESTS_FAILED = Pattern.compile("Tests run: \\d+, Failures: (\\d+), Errors: (\\d+)");

    public static void main(String[] args) throws Exception {
        if (!Files.exists(Path.of("pom.xml")) || !Files.exists(Path.of(STORAGE))) {
            System.err.println("Run from the repository root.");
            System.exit(2);
        }
        if (List.of(args).contains("--self-test")) {
            // A change that alters no behaviour must be reported as SURVIVED. If it is reported
            // as killed, this program cannot tell a guarded rule from an unguarded one.
            Mutant harmless = new Mutant("SELF", "a harmless change must survive", PLAN,
                    "    /** A null payload and an empty one are the same entry; the storage writes both as empty. */\n",
                    "    /** A null payload and an empty one are the same entry. */\n", "AppendPlanStrictnessTest*");
            Path source = Path.of(harmless.file());
            byte[] original = Files.readAllBytes(source);
            if (count(read(harmless.file()), harmless.find()) != 1) { System.out.println("SELF-TEST STALE"); System.exit(1); }
            String outcome;
            try {
                Files.writeString(source, read(harmless.file()).replace(harmless.find(), harmless.replace()));
                outcome = runTests(harmless);
            } finally {
                Files.write(source, original);
            }
            System.out.println("self-test outcome: " + outcome + (outcome.equals("SURVIVED") ? " (correct)" : " (WRONG: expected SURVIVED)"));
            System.exit(outcome.equals("SURVIVED") ? 0 : 1);
        }
        boolean verifyOnly = List.of(args).contains("--verify");
        List<String> wanted = List.of(args).stream().filter(a -> !a.startsWith("--")).toList();

        List<String> problems = new ArrayList<>();
        for (Mutant m : MUTANTS) {
            int occurrences = count(read(m.file()), m.find());
            if (occurrences != 1) problems.add(m.id() + " STALE    found " + occurrences + " times, expected once: " + m.rule());
        }
        if (verifyOnly || !problems.isEmpty()) {
            problems.forEach(System.out::println);
            System.out.println(problems.isEmpty() ? "All " + MUTANTS.size() + " mutations apply." : "GATE FAILED");
            System.exit(problems.isEmpty() ? 0 : 1);
        }

        int killed = 0;
        for (Mutant m : MUTANTS) {
            if (!wanted.isEmpty() && !wanted.contains(m.id())) continue;
            Path source = Path.of(m.file());
            byte[] original = Files.readAllBytes(source);
            String digest = sha256(original);
            Thread restoreOnAbort = new Thread(() -> { try { Files.write(source, original); } catch (IOException ignored) { } });
            Runtime.getRuntime().addShutdownHook(restoreOnAbort);
            String outcome;
            try {
                Files.writeString(source, read(m.file()).replace(m.find(), m.replace()));
                outcome = runTests(m);
            } finally {
                Files.write(source, original);
                Runtime.getRuntime().removeShutdownHook(restoreOnAbort);
            }
            if (!sha256(Files.readAllBytes(source)).equals(digest)) {
                System.err.println("FATAL: " + m.file() + " was not restored. Stop and check the working tree.");
                System.exit(3);
            }
            System.out.printf("%s %-8s %s%n", m.id(), outcome, m.rule());
            if (outcome.equals("KILLED")) killed++; else problems.add(m.id() + " " + outcome + " " + m.rule());
        }
        System.out.println();
        System.out.println(killed + " killed, " + problems.size() + " not killed");
        problems.forEach(p -> System.out.println("  " + p));
        System.out.println(problems.isEmpty() ? "GATE PASSED" : "GATE FAILED");
        System.exit(problems.isEmpty() ? 0 : 1);
    }

    static String runTests(Mutant m) throws Exception {
        List<String> command = new ArrayList<>();
        if (System.getProperty("os.name").startsWith("Windows")) command.addAll(List.of("cmd", "/c"));
        command.addAll(List.of("mvn", "-B", "-pl", "raftlog-core", "test", "-Dtest=" + m.tests(),
                "-Dsurefire.failIfNoSpecifiedTests=false"));
        Path log = Files.createTempFile("mutation-" + m.id() + "-", ".log");
        Process build = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        int exit = build.waitFor();
        String output = Files.readString(log, StandardCharsets.ISO_8859_1);
        if (output.contains("COMPILATION ERROR")) return "INVALID";
        boolean aTestFailed = TESTS_FAILED.matcher(output).results()
                .anyMatch(r -> !r.group(1).equals("0") || !r.group(2).equals("0"));
        if (exit != 0 && aTestFailed) { Files.deleteIfExists(log); return "KILLED"; }
        if (exit == 0) return "SURVIVED";
        return "INVALID";                       // failed, but not because a test failed: see the log
    }

    static String read(String file) throws IOException { return Files.readString(Path.of(file)); }

    static int count(String text, String part) {
        int n = 0;
        for (int i = text.indexOf(part); i >= 0; i = text.indexOf(part, i + 1)) n++;
        return n;
    }

    static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
