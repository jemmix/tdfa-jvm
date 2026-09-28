package io.github.jemmix.tdfa.determinism;

import io.github.jemmix.tdfa.core.CompileObserver;
import io.github.jemmix.tdfa.tdfa.Tdfa;
import io.github.jemmix.tdfa.tnfa.Tnfa;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deterministic compilation gate (roadmap: "same regex → identical TDFA
 * across runs"). The compile pipeline hashes by content throughout — state
 * interning, class signatures, op-sequence interning and the Moore
 * partition are all keyed on array contents, budgets are property-derived
 * constants rather than wall-clock or heap probes, and no static mutable
 * state survives a compile — so the artifact must be a pure function of
 * (pattern, mode, knobs). This test makes that a machine-checked
 * invariant, two layers:
 *
 * <ul>
 *   <li><b>within one JVM</b> — every corpus entry compiled twice (second
 *       round with a CompileObserver attached, which must be observably
 *       inert) and in a different compile order must fingerprint
 *       identically;</li>
 *   <li><b>across JVM runs</b> — {@link DeterminismDriver} forked twice;
 *       both runs must print identical fingerprints, equal to the in-JVM
 *       ones (fresh statics, fresh hash seeds, fresh everything).</li>
 * </ul>
 *
 * <p>The fingerprint itself (see {@link ArtifactFingerprint}) covers every
 * artifact table, so "identical TDFA" here means bit-identical flat
 * arrays, not just equal state counts.
 */
class DeterministicCompilationTest {
    @Test
    void fingerprintsDiscriminateArtifacts() {
        String capture = ArtifactFingerprint.of(Tdfa.compile(Tnfa.compile("(a)"), false));
        String captureAb = ArtifactFingerprint.of(Tdfa.compile(Tnfa.compile("(ab)"), false));
        assertThat(capture).as("different patterns must fingerprint differently").isNotEqualTo(captureAb);
        String minimized = DeterminismCorpus.compileFingerprint(new DeterminismCorpus.Entry("probe", "(a|ab)(b|c)",
            DeterminismCorpus.Mode.PERL, DeterminismCorpus.Knob.NONE));
        String unminimized = DeterminismCorpus.compileFingerprint(new DeterminismCorpus.Entry("probe", "(a|ab)(b|c)",
            DeterminismCorpus.Mode.PERL, DeterminismCorpus.Knob.NOMINIMIZE));
        assertThat(minimized).as("a knob that changes the artifact must change the fingerprint")
            .isNotEqualTo(unminimized);
        String regopt = DeterminismCorpus.compileFingerprint(new DeterminismCorpus.Entry("probe", "(a{1,20}){1,20}",
            DeterminismCorpus.Mode.PERL, DeterminismCorpus.Knob.NONE));
        String noRegopt = DeterminismCorpus.compileFingerprint(new DeterminismCorpus.Entry("probe", "(a{1,20}){1,20}",
            DeterminismCorpus.Mode.PERL, DeterminismCorpus.Knob.NOREGOPT));
        assertThat(regopt).as("register optimization must be fingerprint-visible").isNotEqualTo(noRegopt);
    }

    @Test
    void repeatCompilesWithinJvmAreBitIdentical() {
        List<DeterminismCorpus.Entry> corpus = DeterminismCorpus.corpus();
        Map<String, String> round1 = new LinkedHashMap<>();
        for (DeterminismCorpus.Entry e : corpus) {
            round1.put(e.id(), DeterminismCorpus.compileFingerprint(e));
        }
        Map<String, String> round2 = new LinkedHashMap<>();
        List<DeterminismCorpus.Entry> swapped = new ArrayList<>(corpus);
        Collections.reverse(swapped);
        for (DeterminismCorpus.Entry e : swapped) {
            round2.put(e.id(), DeterminismCorpus.compileFingerprint(e));
        }
        for (Map.Entry<String, String> e : round1.entrySet()) {
            assertThat(e.getValue()).as("second compile of '%s' (reversed order)", e.getKey())
                .isEqualTo(round2.get(e.getKey()));
        }
    }

    @Test
    void observerDoesNotAffectArtifact() {
        String pattern = "(a{1,20}){1,20}";
        String plain = ArtifactFingerprint.of(Tdfa.compile(Tnfa.compile(pattern), false));
        int[] stageEvents = {0};
        String observed = ArtifactFingerprint.of(Tdfa.compile(Tnfa.compile(pattern), false, new CompileObserver() {
            @Override
            public void stage(Stage stage, long nanos, int detail) {
                stageEvents[0]++;
            }
        }));
        assertThat(stageEvents[0]).as("observer must actually have observed stages").isGreaterThan(0);
        assertThat(observed).as("CompileObserver presence must not change the artifact").isEqualTo(plain);
    }

    @Test
    void freshJvmRunsProduceIdenticalArtifacts() throws Exception {
        Map<String, String> inJvm = new LinkedHashMap<>();
        for (DeterminismCorpus.Entry e : DeterminismCorpus.corpus()) {
            inJvm.put(e.id(), DeterminismCorpus.compileFingerprint(e));
        }
        String run1 = forkDriver();
        String run2 = forkDriver();
        assertThat(run2).as("two fresh JVM runs must print identical fingerprints").isEqualTo(run1);
        for (Map.Entry<String, String> e : inJvm.entrySet()) {
            assertThat(run1).as("forked run must agree with the in-JVM fingerprint of '%s'", e.getKey())
                .contains(e.getKey() + "\t" + e.getValue() + "\n");
        }
    }

    private static String forkDriver() throws Exception {
        String javaBin = Path.of(System.getProperty("java.home"), "bin",
            System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java").toString();
        ProcessBuilder pb = new ProcessBuilder(javaBin, "-Xmx1g", "-cp", System.getProperty("java.class.path"),
            DeterminismDriver.class.getName());
        pb.redirectErrorStream(false);
        Process p = pb.start();
        StringBuilder out = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) {
                out.append(line).append('\n');
            }
        }
        StringBuilder err = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getErrorStream()))) {
            String line;
            while ((line = r.readLine()) != null) {
                err.append(line).append('\n');
            }
        }
        assertThat(p.waitFor(120, TimeUnit.SECONDS)).as("DeterminismDriver fork must exit").isTrue();
        assertThat(p.exitValue()).as("DeterminismDriver fork failed:\n" + err).isZero();
        return out.toString();
    }
}
