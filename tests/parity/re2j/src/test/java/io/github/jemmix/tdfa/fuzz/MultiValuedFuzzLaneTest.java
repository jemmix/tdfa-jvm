package io.github.jemmix.tdfa.fuzz;

import io.github.jemmix.tdfa.re2j.Pattern;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static io.github.jemmix.tdfa.fuzz.DifferentialFuzzer.Lane;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The multi-valued fuzzer lane's own gate (BT22 §3.1;
 * docs/fuzz-multi-valued-tags.md): the four oracles are pinned against the
 * parity corpus — the known-hard span families — so the lane's plumbing
 * (spans-suffixed protocol shape, self-consistency walk, tier equality,
 * sim reference, single-value identity, wiring conditions) cannot rot
 * silently between soak runs. The random-grammar coverage is the soak's
 * job; this is the fixed-seed-free contract end.
 */
class MultiValuedFuzzLaneTest {

    /** The parity suite's span corpus — the families the fixed 17×19 run
     *  already pins against re2j and the reference sim. */
    private static final String[] PATTERNS = {"(a)+", "(a|b)+", "(?:(a)|(b))+", "(a?)*", "(a){2,4}", "((a)(b))+",
        "(a*)*", "(x)?(a)+", "a(b)", "(a+)(b+)", "((a)|b|c)*", "(a(b?)*)+", "((b|)|c)*", "(?:x|((?:a|bb)+)y)+",
        "(b|){2,3}", "(ab|a)(b?)+", "(?:(a)|b|(c))*"};

    private static final String[] INPUTS = {"", "a", "b", "ab", "ba", "aa", "abab", "aab", "abb", "abcab", "abba",
        "abc", "cbb", "bcc", "cc", "bbbb", "bbbaa", "ababab", "abababab"};

    @Test
    void selfConsistencyAndTierEqualityHoldOnTheCorpus() {
        for (String pat : PATTERNS) {
            DifferentialFuzzer.Prepared pr = DifferentialFuzzer.prepare(pat, 0, Lane.RE2J);
            assertThat(pr.mvt).as("lane draws on (%s)", pat).isTrue();
            assertThat(pr.mvtAsm).as("mvt asm compiles (%s)", pat).isNotNull();
            assertThat(pr.mvtVm).as("mvt vm compiles (%s)", pat).isNotNull();
            for (String in : INPUTS) {
                List<String> findings = new ArrayList<>();
                String asmS = DifferentialFuzzer.computeMulti(pr.mvtAsm, in, "mvtAsm", findings);
                String vmS = DifferentialFuzzer.computeMulti(pr.mvtVm, in, "mvtVm", findings);
                assertThat(findings).as("self-consistency <%s> <%s>", pat, in).isEmpty();
                assertThat(asmS).as("tier equality <%s> <%s>", pat, in).isEqualTo(vmS);
            }
        }
    }

    @Test
    void latestEqualsPlainOnTheCorpusBothSemantics() {
        for (String pat : PATTERNS) {
            for (int flags : new int[]{0, Pattern.LONGEST_MATCH}) {
                DifferentialFuzzer.Prepared pr = DifferentialFuzzer.prepare(pat, flags, Lane.RE2J);
                for (String in : INPUTS) {
                    assertThat(DifferentialFuzzer.compute(pr.mvtAsm, in))
                        .as("mvt asm == plain asm <%s> <%s> f%d", pat, in, flags)
                        .isEqualTo(DifferentialFuzzer.compute(pr.asm, in));
                    assertThat(DifferentialFuzzer.compute(pr.mvtVm, in))
                        .as("mvt vm == plain vm <%s> <%s> f%d", pat, in, flags)
                        .isEqualTo(DifferentialFuzzer.compute(pr.vm, in));
                }
            }
        }
    }

    @Test
    void simOracleAgreesOnTheCorpus() {
        for (String pat : PATTERNS) {
            DifferentialFuzzer.Prepared pr = DifferentialFuzzer.prepare(pat, 0, Lane.RE2J);
            assertThat(pr.sim).as("reference rides RE2J flags=0 batches (%s)", pat).isNotNull();
            for (String in : INPUTS) {
                DifferentialFuzzer.Outcome o =
                    new DifferentialFuzzer.Outcome(new DifferentialFuzzer.Case(pat, in, 0, Lane.RE2J));
                DifferentialFuzzer.simOracle(pr.sim, pr.mvtVm, o.c, o);
                assertThat(o.mvt).as("sim write-log <%s> <%s>", pat, in).isEmpty();
            }
        }
    }

    @Test
    void spansSuffixCarriesEveryIteration() {
        DifferentialFuzzer.Prepared pr = DifferentialFuzzer.prepare("(?:(a)|(b))+", 0, Lane.RE2J);
        List<String> findings = new ArrayList<>();
        String s = DifferentialFuzzer.computeMulti(pr.mvtVm, "abab", "mvtVm", findings);
        // F: whole 0..4, g1 last 2..3 / all [0,1][2,3], g2 last 3..4 / all
        // [1,2][3,4] — the suffix is the multi-valued readout, the blob the
        // single-value report (which the last pair must equal).
        assertThat(s).startsWith("F=true 0..4 (2..3 3..4) <[0,1][2,3] [1,2][3,4]>").contains(" M=true ");
        assertThat(findings).isEmpty();
    }

    @Test
    void laneWiring() {
        // the reference rides RE2J flags=0 batches only: compile-level
        // flags change matching the bare-pattern sim cannot follow, and
        // the jur facade reads the java-parity axes.
        assertThat(DifferentialFuzzer.prepare("(a)+", 0, Lane.RE2J).sim).isNotNull();
        assertThat(DifferentialFuzzer.prepare("(a)+", Pattern.CASE_INSENSITIVE, Lane.RE2J).sim).isNull();
        assertThat(DifferentialFuzzer.prepare("(a)+", 0, Lane.JUR).sim).isNull();
        // the MVT compiles themselves run on every lane — the lane-internal
        // oracles (SELF/SINGLE/TIER/RUNG) need no reference.
        DifferentialFuzzer.Prepared jur = DifferentialFuzzer.prepare("(a)+", 0, Lane.JUR);
        assertThat(jur.mvtAsm).isNotNull();
        assertThat(jur.mvtVm).isNotNull();
    }

    @Test
    void budgetRejectClassification() {
        assertThat(DifferentialFuzzer.budgetReject("<reject:pattern too large: compute budget>")).isTrue();
        assertThat(DifferentialFuzzer.budgetReject("<reject:pattern too large: memory budget>")).isTrue();
        assertThat(DifferentialFuzzer.budgetReject("<reject:unclosed group>")).isFalse();
        assertThat(DifferentialFuzzer.budgetReject("<exception:Boom>")).isFalse();
        assertThat(DifferentialFuzzer.budgetReject(null)).isFalse();
    }
}
