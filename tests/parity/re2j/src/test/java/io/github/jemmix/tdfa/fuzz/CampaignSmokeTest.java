package io.github.jemmix.tdfa.fuzz;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The campaign harness's own gate, mirroring {@link FuzzSmokeTest}: a
 * fixed-seed slice of every mode must be free of HARD findings, so the
 * generators, lane plumbing and classification cannot rot between campaign
 * runs. A failure here is either a real divergence or broken harness
 * plumbing; {@code -Pcamp.mode=<m> -Pcamp.one=<caseSeed>} replays the
 * recorded case.
 *
 * <p>Since the evidence re-base (WBS item 6) the SOFT kind set is asserted
 * too — the "classifiers flip from soft-known to hard assertions" gate:
 * every soft kind must be a member of the consciously enumerated domain
 * boundary below (skips the harness documents, budget rejections, oracle
 * limits, and the re2j surface's by-design divergence families on the
 * RE2-lane probe). The JUR-lane probe has NO known-divergence softs left:
 * the İ/ı full-fold residual is fixed (the jur surface's default fold
 * universe is the java.util.regex reading), and the family-6 tail +
 * surrogate-unit residuals are reclassified as SKIP domain exclusions. A
 * new soft kind fails this test until it is consciously classified and
 * enumerated here.
 */
class CampaignSmokeTest {

    /** The jur mode's enumerated soft kinds (the asserted domain boundary).
     * RE2-lane probe: the re2j surface's documented divergence families vs
     * the jur oracle (KNOWN_*), the fold-ambiguity skip, and the compile/
     * exception/budget guards. JUR-lane probe: SKIP_* domain exclusions
     * and guards only — no KNOWN_ kind may appear on the lane, and the
     * engine's own match-time exceptions are HARD (JURLANE_EXCEPTION;
     * the compile catch-all's twin — the re-base flipped that one first,
     * JUR_EXCEPTION was never actually soft). */
    private static final Set<String> JUR_SOFT = Set.of(
        // RE2-lane probe (jurCase): re2j reading vs jur oracle, by design
        "KNOWN_SURROGATE-CODE-UNIT", "KNOWN_LINE-TERMINATOR-SET", "KNOWN_UNIT-BOUNDARY-POSITIONS",
        "KNOWN_CARET-AFTER-FINAL-NEWLINE", "KNOWN_DOLLAR-BEFORE-FINAL-NEWLINE", "KNOWN_GROUP-PARTICIPATION-LINEAGE",
        "SKIP_UNGREEDY", "SKIP_CI_FOLD_AMBIGUOUS", "BUDGET", "JUR_ORACLE_THREW",
        // JUR-lane probe (jurLaneCase): domain exclusions + guards — the
        // family-6 TAIL skip retired (the unbounded zero-width-exit split
        // carries the exiting iteration's writes; `(a*)*b` compares hard),
        // and the surrogate-unit family retired with it (the pair-aware
        // scan pivot: Parser#hasSupplementaryStarts OR-ed into the runner's
        // candidate-scan pivot — lone surrogates and pair cases compare
        // hard); the nested-loop inner-capture quirk (`((a)*b)*`: java
        // restores the FIRST iteration's inner group) and java's
        // restart-tag leak (a group span from a FAILED restart attempt,
        // outside the match) are the remaining group-family skips,
        // skeleton-gated + exact
        "V1_UNGREEDY_SCOPE", "SKIP_NESTED_LOOP_CAPTURE", "SKIP_RESTART_TAG_LEAK", "JURLANE_BUDGET");

    private static final Map<String, Set<String>> MODE_SOFT =
        Map.of("jur", JUR_SOFT, "stretch", Set.of("SKIP_EMPTY", "THREW", "SLOW", "STRETCH_BUDGET"), "seq",
            Set.of("SIM_REJECT", "BUDGET", "SEQ_COMPILE"), "families", Set.of("FAMILY_BUDGET", "GATE_MISS_literal",
                "GATE_MISS_prefix", "GATE_MISS_cand-scan", "GATE_MISS_anchored-fast"));

    private static CampaignFuzzer.Counts clean(String mode, Path tmp) {
        CampaignFuzzer.Counts c = CampaignFuzzer.runMode(mode, 0xCAFEBABEL, 60, 400, tmp);
        assertThat(c.hard).as("hard findings (inspect %s/%s/failures.ndjson)", tmp, mode).isZero();
        assertThat(c.softKinds()).as("%s: soft kinds outside the enumerated domain boundary", mode)
            .isSubsetOf(MODE_SOFT.get(mode));
        return c;
    }

    @Test
    void jurSliceIsClean(@TempDir Path tmp) {
        clean("jur", tmp);
    }

    @Test
    void stretchSliceIsClean(@TempDir Path tmp) {
        clean("stretch", tmp);
    }

    @Test
    void seqSliceIsClean(@TempDir Path tmp) {
        clean("seq", tmp);
    }

    @Test
    void familiesSliceIsClean(@TempDir Path tmp) {
        clean("families", tmp);
    }
}
