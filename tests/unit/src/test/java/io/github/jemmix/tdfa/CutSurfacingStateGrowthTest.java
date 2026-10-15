package io.github.jemmix.tdfa;

import io.github.jemmix.tdfa.core.dfa.TdfaRunner;
import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Evidence-re-base regression (found by the {@code DifferentialFuzzer} jur
 * lane, caseSeed 7174669054956816474): the family-6 cut-surfacing path
 * indexed {@code cutOverrideByState} past its tail. {@code bindCuts} sizes
 * the array to the kernel count <i>at bind time</i>; kernels discovered
 * after the last bind sat beyond the array and crashed
 * {@code solveFinalOps} with an ArrayIndexOutOfBoundsException — reachable
 * only on the JUR lane (the surfacing machinery the RE2 lane leaves off),
 * and previously swallowed by the campaign's compile-exception soft, which
 * is exactly why the lane matrix had to make that path hard.
 */
class CutSurfacingStateGrowthTest {

    private static final String PATTERN =
        "(?:((\uDBFF\uDC21|\uD801\uDC02Ya??9{1}?)*)*(?s:_a(?:\u6f22\u017f.{0,})))?\\A\u20ac";

    @Test
    void lateKernelsBeyondTheCutArrayCompileAndMatch() {
        // both tiers compile clean and answer the java.util.regex oracle
        String input = "\n \u1c80\uD800\uDFFF ";
        boolean jur = java.util.regex.Pattern.compile(PATTERN).matcher(input).find();
        io.github.jemmix.tdfa.Pattern asm = io.github.jemmix.tdfa.Pattern.compile(PATTERN);
        io.github.jemmix.tdfa.Pattern vm = io.github.jemmix.tdfa.Pattern.compile(PATTERN, 0, TdfaRunner::new);
        assertThat(asm.matcher(input).find()).as("ASM tier vs java.util.regex").isEqualTo(jur);
        assertThat(vm.matcher(input).find()).as("VM tier vs java.util.regex").isEqualTo(jur);
    }

    @Test
    void theShapeStaysPinnedAcrossInputs() {
        // the crash was compile-time: every input over the shape answers
        // the oracle once the compile stands
        java.util.regex.Pattern oracle = Pattern.compile(PATTERN);
        io.github.jemmix.tdfa.Pattern vm = io.github.jemmix.tdfa.Pattern.compile(PATTERN, 0, TdfaRunner::new);
        for (String in : new String[]{"", "\n", "\u1c80", "Ya9", "漢ſ_a", "\uD801\uDC02Ya??9"}) {
            assertThat(vm.matcher(in).find()).as("input %s", in).isEqualTo(oracle.matcher(in).find());
        }
    }
}
