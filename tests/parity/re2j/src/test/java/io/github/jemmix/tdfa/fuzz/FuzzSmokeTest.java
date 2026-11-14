package io.github.jemmix.tdfa.fuzz;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The fuzzer's own gate: a fixed-seed slice must be clean, so the generator,
 * the comparison protocol, and the engine contract cannot rot silently
 * between soak runs. A failure here is either a real divergence (great news
 * for a fuzzer, bad news for the engine) or broken harness plumbing — the
 * ndjson in the temp dir distinguishes them.
 *
 * <p>The multi-valued lane (BT22 §3.1) runs by default; the lane-count
 * asserts keep its wiring honest — a lane that silently stopped executing
 * (or whose PikeSim reference stopped riding RE2J flags=0 batches) must
 * fail the gate, not report a clean-but-empty soak.
 */
class FuzzSmokeTest {

    @Test
    void fixedSeedSliceIsClean(@TempDir Path tmp) throws Exception {
        DifferentialFuzzer.Results r = DifferentialFuzzer.run(0xC0FFEE, 1, 500, tmp);
        assertThat(r.cases).as("cases executed").isEqualTo(500);
        assertThat(r.failures)
            .as("failures (inspect %s/failures.ndjson; reproduce any line with -Dfuzz.one=<caseSeed>)", tmp).isZero();
        assertThat(r.mvtCases).as("multi-valued lane cases (wiring guard)").isPositive();
        assertThat(r.mvtSimCases).as("multi-valued sim-oracle cases (wiring guard)").isPositive();
        assertThat(r.regionCases).as("region/bounds axis cases (wiring guard)").isPositive();
        assertThat(r.regionOracleCases).as("region/bounds jur-oracle cases (wiring guard)").isPositive();
        assertThat(r.famBatches).as("fact-gated family batches (wiring guard)").containsKey("grapheme");
    }

    /**
     * Generation-coverage pin: CI batches draw supplementary and lone
     * surrogate codepoints into patterns — the un-avoidance of 12d9921
     * (full-Unicode {@code (?i)} folding), without which the soak silently
     * stops comparing the CI x supplementary region. Pure generator sweep,
     * no engine involved.
     */
    @Test
    void ciBatchesGenerateSupplementaryAndLoneSurrogates() {
        int ciBatches = 0;
        boolean supp = false;
        boolean lone = false;
        for (long batch = 0; batch < 5000 && !(supp && lone); batch++) {
            if ((DifferentialFuzzer.genFlags(batch) & DifferentialFuzzer.FLAG_CI) == 0) {
                continue;
            }
            ciBatches++;
            String p = DifferentialFuzzer.genPattern(batch);
            for (int i = 0; i < p.length(); i++) {
                char c = p.charAt(i);
                if (c >= 0xD800 && c <= 0xDFFF) {
                    if (c <= 0xDBFF && i + 1 < p.length() && p.charAt(i + 1) >= 0xDC00 && p.charAt(i + 1) <= 0xDFFF) {
                        supp = true; // adjacent high+low: one supplementary codepoint
                        i++;
                    } else {
                        lone = true;
                    }
                }
            }
        }
        assertThat(ciBatches).as("CI batches scanned").isPositive();
        assertThat(supp).as("supplementary codepoints generated under CI (12d9921 un-avoidance)").isTrue();
        assertThat(lone).as("lone surrogates generated under CI (12d9921 un-avoidance)").isTrue();
    }
}
