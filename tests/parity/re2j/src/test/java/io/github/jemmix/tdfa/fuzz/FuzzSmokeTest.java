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
    }
}
