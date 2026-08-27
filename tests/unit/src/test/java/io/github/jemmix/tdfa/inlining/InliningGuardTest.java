package io.github.jemmix.tdfa.inlining;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JIT-level devirtualization guard for the ASM-generated engine tier — the
 * runtime complement of {@code EmittedBytecodePolicyTest}: the policy test
 * proves every call CAN be monomorphic; this test asks HotSpot whether it
 * actually inlined them.
 *
 * <p>Forks a child JVM with {@code -XX:+UnlockDiagnosticVMOptions
 * -XX:+PrintInlining -XX:-BackgroundCompilation}, runs {@link InliningDriver}
 * (all emitted-ladder shapes hot), and parses the inlining log:
 * <ul>
 *   <li>any {@code ...morphic...} failure inside a generated-class method is
 *       a hard failure — that is devirtualization lost;</li>
 *   <li>other inline failures inside generated code ({@code too big},
 *       {@code inlining too deep}, ...) are reported as warnings in the log;
 *       they cost speed but not dispatch safety;</li>
 *   <li>if fewer than 3 generated-class methods appear in the log, the run
 *       did not exercise enough code — retry, then fail with the log path.</li>
 * </ul>
 *
 * <p>Lives in its own Gradle action ({@code :tests:unit:inliningGuard}),
 * never in the default {@code test} task: it forks JVMs, depends on HotSpot
 * diagnostic flags and JIT timing, and would flake exactly like the
 * compile-latency guard does on a loaded machine. The full inlining log is
 * written to {@code build/reports/inlining-guard/} for review.
 */
class InliningGuardTest {

    private static final int ATTEMPTS = 3;
    private static final int MIN_GEN_METHODS = 3;

    @Test
    void generatedTierCallSitesAreInlined() throws Exception {
        Path reportDir = Path.of("build", "reports", "inlining-guard");
        Files.createDirectories(reportDir);

        String lastFailure = null;
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            ForkResult r = forkDriver();
            Path log = reportDir.resolve("inlining-" + LocalDateTime.now()
                    .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + "-attempt" + attempt + ".log");
            Files.writeString(log, r.output());

            if (r.flagsRejected()) {
                Assumptions.abort("JVM rejected PrintInlining flags; cannot verify (log: " + log + ")");
                return;
            }
            List<String> morphic = new ArrayList<>();
            List<String> warnings = new ArrayList<>();
            int genMethods = parse(r.output(), morphic, warnings);
            String verdict = "attempt " + attempt + ": genMethods=" + genMethods
                    + " morphicFailures=" + morphic.size() + " warnings=" + warnings.size() + " log=" + log;

            if (genMethods >= MIN_GEN_METHODS && morphic.isEmpty()) {
                System.out.println("[inlining-guard] CLEAN — " + verdict);
                warnings.forEach(w -> System.out.println("[inlining-guard]   warn: " + w));
                return;
            }
            lastFailure = verdict + (morphic.isEmpty() ? "" : "\n  morphic failures:\n"
                    + String.join("\n  ", morphic));
            System.out.println("[inlining-guard] retry — " + lastFailure);
        }
        assertThat(lastFailure).as("generated tier must devirtualize (3 attempts)").doesNotContain("morphic failures");
        throw new AssertionError("inlining guard could not get a clean reading: " + lastFailure);
    }

    private record ForkResult(int exit, String output) {
        boolean flagsRejected() {
            return exit != 0 && (output.contains("Unrecognized VM option")
                    || output.contains("Could not create the Java Virtual Machine"));
        }
    }

    private static ForkResult forkDriver() throws Exception {
        String javaBin = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java").toString();
        ProcessBuilder pb = new ProcessBuilder(javaBin,
                "-XX:+UnlockDiagnosticVMOptions", "-XX:+PrintInlining", "-XX:+PrintCompilation",
                "-XX:-BackgroundCompilation",
                "-Xss4m",
                "-cp", System.getProperty("java.class.path"),
                InliningDriver.class.getName());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        StringBuilder out = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) out.append(line).append('\n');
        }
        p.waitFor();
        return new ForkResult(p.exitValue(), out.toString());
    }

    /**
     * Walk the compilation/inlining log. On current HotSpot, PrintInlining
     * emits only call-site lines ({@code @ N owner::method (n bytes) ...},
     * optionally prefixed by tier markers like {@code !m}); the compiled
     * method is identified by the preceding PrintCompilation event line
     * ({@code <id> % 4 3  owner::method (n bytes)}). We track the most recent
     * compilation event as the current method; call sites that follow it and
     * precede the next event belong to it. Returns the number of compilation
     * events for generated-class methods; appends morphic failures and other
     * inline failures found within those methods.
     */
    static int parse(String output, List<String> morphic, List<String> warnings) {
        int genMethods = 0;
        boolean inGen = false;
        String header = null;
        for (String raw : output.split("\n")) {
            String line = raw.stripLeading();
            boolean isCallSite = line.contains("@");
            if (!isCallSite) {
                // compilation event (or noise); a header names a method
                int c1 = line.indexOf("::");
                if (c1 > 0 && line.matches(".*::\\S+\\s+\\(\\d+ bytes\\).*")) {
                    int ownerStart = line.lastIndexOf(' ', c1) + 1;   // strip the compile-id prefix
                    int end = line.indexOf(' ', c1);
                    header = line.substring(ownerStart, end);
                    inGen = header.startsWith("io.github.jemmix.tdfa.gen.");
                    if (inGen) genMethods++;
                }
                continue;
            }
            if (!inGen) continue;
            // strip tier markers before the '@'
            int at = line.indexOf('@');
            String call = line.substring(at).stripLeading();
            if (call.contains("morphic")) morphic.add(header + " -> " + call);
            else if (call.contains("failed to inline") || call.contains("too big")
                    || call.contains("too large") || call.contains("inlining too deep")
                    || call.contains("not enough data"))
                warnings.add(header + " -> " + call);
        }
        return genMethods;
    }
}
