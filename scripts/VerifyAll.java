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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Runs EVERYTHING. Not a selection, not what seems relevant to the change: every test in every
 * module, every program the project ships, the model soak, the mutation gate, on this platform
 * and then again on Linux as an unprivileged user.
 *
 * <pre>
 *   java scripts/VerifyAll.java
 * </pre>
 *
 * It exists because choosing which checks a change "needs" is a judgement, and that judgement was
 * wrong more than once: a soak that ran no seeds, a mutation check skipped because the guards
 * "had not changed", programs whose unit tests passed but which were never actually run, a
 * packaged jar that was never built. None of that is left to judgement here. A step that cannot
 * run is a FAILURE, never a skip, and the exit code is non-zero unless every step passed.
 * <p>
 * Steps, in order:
 * <ol>
 *   <li>nothing under a source directory or scripts is ignored by Git;</li>
 *   <li>clean build of the whole reactor with the coverage gate: every test of every module,
 *       packaging, and the 99% line and branch requirement on the storage package;</li>
 *   <li>no test skipped, other than the Linux-only tests when not on Linux;</li>
 *   <li>every shipped program, run from the packaged jar the README tells users to run, twice
 *       against the same directory so the restart and replay path is exercised;</li>
 *   <li>the chaos program, all categories;</li>
 *   <li>the model soak over 2000 seeds, with proof in its output that the seeds ran;</li>
 *   <li>the mutation gate: its self-test, then every mutant;</li>
 *   <li>all of the above again inside a Linux container as an unprivileged user, built from
 *       exactly the files Git would commit and not from the working directory.</li>
 * </ol>
 * Run from the repository root. Expect well over an hour. Requires Docker for the last step.
 */
public class VerifyAll {
    record Result(String step, boolean passed, Duration took, String detail) { }

    static final List<Result> RESULTS = new ArrayList<>();
    static final boolean WINDOWS = System.getProperty("os.name").startsWith("Windows");
    static final boolean INSIDE_LINUX = System.getenv("RAFTLOG_VERIFY_INSIDE_LINUX") != null;
    static final String JAVA = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    static final int SOAK_SEEDS = 2000;
    static final String IMAGE = "maven:3.9-eclipse-temurin-25";

    public static void main(String[] args) throws Exception {
        if (!Files.exists(Path.of("pom.xml")) || !Files.exists(Path.of("scripts/MutationCheck.java"))) {
            System.err.println("Run from the repository root.");
            System.exit(2);
        }
        String platform = System.getProperty("os.name") + (INSIDE_LINUX ? " (container, uid " + run(List.of("id", "-u")).strip() + ")" : "");
        System.out.println("=== VerifyAll on " + platform);

        String version = projectVersion();
        if (Files.exists(Path.of(".git"))) {
            step("nothing under a source directory or scripts is ignored by Git", null, ignored -> {
                // An ignored fixture passes every local run and is missing from every checkout.
                List<String> dropped = run(List.of("git", "status", "--short", "--ignored", "--",
                        "raftlog-core/src", "raftlog-demo/src", "scripts")).lines().filter(l -> l.startsWith("!!")).toList();
                dropped.forEach(l -> System.out.println("    ignored by Git: " + l.substring(3)));
                return dropped.isEmpty();
            });
        }
        step("clean build, all modules, all tests, coverage gate",
                mvn("-Pcoverage", "clean", "install"),
                out -> out.contains("BUILD SUCCESS") && out.contains("All coverage checks have been met")
                        && !out.contains("Rule violated") && allTestsPassed(out));

        step("no test skipped" + (WINDOWS ? " except the Linux-only tests" : ""), null, ignored -> {
            List<String> skipped = skippedTests();
            skipped.removeIf(name -> !isLinux() && name.contains("LinuxSpecificTests"));
            if (!skipped.isEmpty()) System.out.println("    skipped: " + skipped);
            return skipped.isEmpty();
        });

        Path jar = Path.of("raftlog-demo", "target", "raftlog-demo-" + version + ".jar");
        step("packaged demo jar exists for version " + version, null, ignored -> Files.exists(jar));

        Path data = Files.createTempDirectory("raftlog-verify-all");
        for (int run = 1; run <= 2; run++) {
            String which = run == 1 ? "first run, fresh directory" : "second run, restart and replay";
            step("WalDemo from the packaged jar, " + which,
                    List.of(JAVA, "-jar", jar.toString(), data.resolve("wal-demo").toString()), out -> !out.contains("Exception in thread"));
            step("KeyValueExample from the packaged jar, " + which,
                    List.of(JAVA, "-cp", jar.toString(), "dev.mars.raftlog.demo.KeyValueExample", data.resolve("kv").toString()),
                    out -> !out.contains("Exception in thread"));
        }
        step("WalChaos from the packaged jar, all categories",
                List.of(JAVA, "-cp", jar.toString(), "dev.mars.raftlog.demo.WalChaos"),
                out -> out.contains(" passed, 0 failed") && !out.contains("[FAIL]"));

        step("model soak, " + SOAK_SEEDS + " seeds",
                mvn("-pl", "raftlog-core", "test",
                        "-Dtest=FileRaftStorageInvariantEdgeCaseTest#soakWritePathAndReplayPathAgainstTheModel",
                        "-Draftlog.model.soakSeeds=" + SOAK_SEEDS),
                out -> out.contains("MODEL SOAK: ran " + SOAK_SEEDS + " seeds") && out.contains("BUILD SUCCESS"));

        step("mutation gate self-test (a harmless change must survive)",
                List.of(JAVA, "scripts/MutationCheck.java", "--self-test"), out -> out.contains("SURVIVED (correct)"));
        step("mutation gate, every mutant",
                List.of(JAVA, "scripts/MutationCheck.java"), out -> out.contains("GATE PASSED") && !out.contains("not killed\n  "));

        if (!INSIDE_LINUX && !isLinux()) linux();

        System.out.println();
        System.out.println("=== VerifyAll summary, " + platform);
        boolean allPassed = true;
        for (Result r : RESULTS) {
            System.out.printf("  %-6s %6ds  %s%s%n", r.passed() ? "PASS" : "FAIL", r.took().toSeconds(), r.step(),
                    r.detail().isEmpty() ? "" : "  [" + r.detail() + "]");
            allPassed &= r.passed();
        }
        System.out.println(allPassed ? "=== EVERYTHING PASSED" : "=== VERIFICATION FAILED");
        System.exit(allPassed ? 0 : 1);
    }

    /** The same program, run inside a Linux container as uid 1000, against a private copy of the tree. */
    static void linux() throws Exception {
        Instant start = Instant.now();
        String docker = run(List.of("docker", "version", "--format", "{{.Server.Os}}")).strip();
        if (!docker.equals("linux")) {
            RESULTS.add(new Result("Linux, unprivileged user: EVERYTHING above again", false, Duration.ZERO,
                    "Docker with a Linux engine is required and was not available. This is a failure, not a skip."));
            return;
        }
        run(List.of("docker", "run", "--rm", "-v", "raftlog-m2u:/repo", IMAGE, "bash", "-c", "chown -R 1000:1000 /repo"));
        Path committed = whatGitWouldCommit();
        String inside = String.join(" && ",
                "export HOME=/tmp/home MAVEN_ARGS=-Dmaven.repo.local=/repo RAFTLOG_VERIFY_INSIDE_LINUX=1",
                "mkdir -p $HOME/.m2 /tmp/work && cd /src",
                "tar --exclude=./target --exclude='./*/target' --exclude=./.git --exclude=./.history --exclude=./logs -cf - . | tar -xf - -C /tmp/work",
                "printf '<toolchains><toolchain><type>jdk</type><provides><version>25</version></provides>"
                        + "<configuration><jdkHome>%s</jdkHome></configuration></toolchain></toolchains>' \"$JAVA_HOME\" > $HOME/.m2/toolchains.xml",
                "cd /tmp/work && java scripts/VerifyAll.java");
        List<String> command = List.of("docker", "run", "--rm", "--user", "1000:1000",
                "-v", committed.toAbsolutePath() + ":/src:ro", "-v", "raftlog-m2u:/repo", IMAGE, "bash", "-c", inside);
        Path log = Files.createTempFile("verify-all-linux-", ".log");
        Process container = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        int exit = container.waitFor();
        String out = Files.readString(log, StandardCharsets.ISO_8859_1);
        int summary = out.lastIndexOf("=== VerifyAll summary");
        System.out.println(summary >= 0 ? out.substring(summary).indent(4) : out.lines().skip(Math.max(0, out.lines().count() - 40)).toList().toString());
        boolean passed = exit == 0 && out.contains("=== EVERYTHING PASSED");
        RESULTS.add(new Result("Linux, unprivileged user: EVERYTHING above again", passed, Duration.between(start, Instant.now()),
                passed ? "" : "see " + log));
    }

    /**
     * A copy of exactly what a commit of this tree would contain: tracked files, plus untracked
     * files that are not ignored. Linux builds from this, not from the working directory, so a
     * file the build needs but Git ignores is missing there, as it would be for everyone else.
     */
    static Path whatGitWouldCommit() throws IOException {
        Path staging = Files.createTempDirectory("raftlog-verify-src");
        String listing = run(List.of("git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"));
        int copied = 0;
        for (String name : listing.split(" ")) {
            if (name.isBlank() || name.startsWith(".history/")) continue;
            Path source = Path.of(name);
            if (!Files.isRegularFile(source)) continue;              // deleted in the working tree
            Path target = staging.resolve(name);
            Files.createDirectories(target.getParent() == null ? staging : target.getParent());
            Files.copy(source, target);
            copied++;
        }
        if (copied == 0) throw new IOException("git ls-files listed nothing; is this a Git working tree?");
        System.out.println("    Linux builds from " + copied + " files, exactly what Git would commit");
        return staging;
    }

    static void step(String name, List<String> command, Predicate<String> passed) throws Exception {
        System.out.println("--- " + name);
        Instant start = Instant.now();
        String out = "";
        int exit = 0;
        Path log = null;
        if (command != null) {
            log = Files.createTempFile("verify-all-", ".log");
            Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
            exit = process.waitFor();
            out = Files.readString(log, StandardCharsets.ISO_8859_1);
        }
        boolean ok = exit == 0 && passed.test(out);
        RESULTS.add(new Result(name, ok, Duration.between(start, Instant.now()), ok ? testCount(out) : "exit " + exit + (log == null ? "" : ", see " + log)));
        System.out.println("    " + (ok ? "PASS" : "FAIL") + (ok && !testCount(out).isEmpty() ? "  " + testCount(out) : ""));
    }

    static List<String> mvn(String... arguments) {
        List<String> command = new ArrayList<>(WINDOWS ? List.of("cmd", "/c", "mvn", "-B") : List.of("mvn", "-B"));
        command.addAll(List.of(arguments));
        return command;
    }

    static boolean allTestsPassed(String mavenOutput) {
        List<String> totals = mavenOutput.lines().filter(l -> l.matches(".*Tests run: \\d+, Failures: \\d+, Errors: \\d+, Skipped: \\d+$")).toList();
        return !totals.isEmpty() && totals.stream().allMatch(l -> l.contains("Failures: 0, Errors: 0"));
    }

    /** "core 608 tests, demo 8 tests" style detail from the per-module totals. */
    static String testCount(String mavenOutput) {
        return String.join(", ", mavenOutput.lines()
                .filter(l -> l.matches(".*Tests run: \\d+, Failures: \\d+, Errors: \\d+, Skipped: \\d+$"))
                .map(l -> l.substring(l.indexOf("Tests run:"))).toList());
    }

    static List<String> skippedTests() {
        try {
            return readSkippedTests();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    static List<String> readSkippedTests() throws IOException {
        List<String> skipped = new ArrayList<>();
        for (String module : List.of("raftlog-core", "raftlog-demo")) {
            Path reports = Path.of(module, "target", "surefire-reports");
            if (!Files.isDirectory(reports)) continue;
            try (Stream<Path> files = Files.list(reports)) {
                for (Path report : files.filter(f -> f.getFileName().toString().matches("TEST-.*\\.xml")).toList()) {
                    String xml = Files.readString(report, StandardCharsets.ISO_8859_1);
                    java.util.regex.Matcher m = java.util.regex.Pattern
                            .compile("<testcase name=\"([^\"]*)\" classname=\"([^\"]*)\"[^>]*>\\s*<skipped").matcher(xml);
                    while (m.find()) skipped.add(m.group(2).substring(m.group(2).lastIndexOf('.') + 1) + "#" + m.group(1));
                }
            }
        }
        return skipped;
    }

    static boolean isLinux() { return System.getProperty("os.name").toLowerCase().contains("linux"); }

    static String projectVersion() throws IOException {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("<version>([^<]+)</version>").matcher(Files.readString(Path.of("pom.xml")));
        if (!m.find()) throw new IllegalStateException("no version in pom.xml");
        return m.group(1);
    }

    static String run(List<String> command) {
        try {
            Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
            p.waitFor();
            return out;
        } catch (IOException | InterruptedException e) {
            return "";
        }
    }
}
