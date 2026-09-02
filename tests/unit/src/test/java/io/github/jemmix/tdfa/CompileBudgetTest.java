package io.github.jemmix.tdfa;

import io.github.jemmix.tdfa.core.PatternSyntaxException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Engine determinization budget (re2c-identical design; re2c
 * src/dfa/determinization.cc + constants.h: MAX_DFA_STATES = 100 K,
 * MAX_DFA_SIZE = 50 M kernel-total). A pattern whose TDFA construction
 * exceeds the caps must fail compilation with a clean
 * {@code "pattern too large"} {@link PatternSyntaxException} — quickly,
 * without exhausting memory — instead of burning unbounded time/heap.
 *
 * <p>Reference-implementation context: re2c 4.5.1 refuses two-site
 * {@code [^]{0,16}x[^]{0,16}} outright ("DFA has too many states"); our
 * construction is more compact on that family (10 K states at {0,100}) and
 * only caps the intrinsically-huge cross-products (the rebar
 * bounded-repeat/context shape: 200 K+ MINIMAL states, aborts at the
 * default cap in ~10 s on a default heap where the uncapped compile needs
 * 12 GB and ~49 s).
 */
class CompileBudgetTest {

    /** The rebar curated/10-bounded-repeat/context shape (both sites). */
    private static final String CONTEXT_BOMB =
            "[A-Za-z]{10}\\s+[\\s\\S]{0,100}Result[\\s\\S]{0,100}\\s+[A-Za-z]{10}";

    @Test
    void overBudgetPatternFailsFastWithCleanError() {
        System.setProperty("tdfa.max.states", "20000");
        try {
            long t0 = System.nanoTime();
            assertThatCode(() -> Pattern.compile(CONTEXT_BOMB))
                    .isInstanceOf(PatternSyntaxException.class)
                    .hasMessageContaining("pattern too large")
                    .hasMessageContaining("tdfa.max.states");
            long ms = (System.nanoTime() - t0) / 1_000_000;
            // ~2 s measured at the 20 K cap on laptop hardware; the point is
            // fail-FAST — the uncapped compile needs 12 GB and ~49 s.
            assertThat(ms).as("wall to rejection at 20 K state cap").isLessThan(15_000);
        } finally {
            System.clearProperty("tdfa.max.states");
        }
    }

    /**
     * The WORK budget (WorkMeter): nested-quantifier bombs whose closure
     * churns fixpoints without materializing states never trip the state/kernel
     * caps (fuzzer-found; e.g. the tryMap family). A tight budget must reject
     * them with the same clean error shape.
     */
    @Test
    void workBudgetRejectsClosureSpinners() {
        String spinner = "(kq)(?U:(\\n*?n)mZ)(?<n0>(?U:( )q)(?:(?:\\W\\z)(\\#.+\\~|\\n{1,1}éu(?<n2>s\\#)\\.)*?){4,}\\t)";
        System.setProperty("tdfa.max.work", "10000000");
        try {
            long t0 = System.nanoTime();
            assertThatCode(() -> Pattern.compile(spinner))
                    .isInstanceOf(PatternSyntaxException.class)
                    .hasMessageContaining("pattern too large")
                    .hasMessageContaining("tdfa.max.work");
            assertThat((System.nanoTime() - t0) / 1_000_000).as("wall to work-budget rejection").isLessThan(30_000);
        } finally {
            System.clearProperty("tdfa.max.work");
        }
    }

    /**
     * Nested-counted repetition bombs (fuzzer family + the classic
     * {@code (a{1,100}){1,100}}): the boxed determinization phase must
     * clean-reject at the memory-calibrated kernel cap instead of OOM-ing.
     * Calibration: 6.4 M kernels peaks < 1 GB, 18 M exceeds it — the 10 M
     * default keeps every measured legit shape (e.g. (a{1,50}){1,50} below)
     * and rejects the bombs on default heaps.
     */
    @Test
    void nestedCountedBombCleanRejectsAtDefaultCaps() {
        long t0 = System.nanoTime();
        assertThatCode(() -> Pattern.compile("(a{1,100}){1,100}"))
                .isInstanceOf(PatternSyntaxException.class)
                .hasMessageContaining("pattern too large")
                .hasMessageContaining("tdfa.max.kernels");
        assertThat((System.nanoTime() - t0) / 1_000_000)
                .as("wall to kernel-cap rejection").isLessThan(30_000);
    }

    /** The largest measured LEGIT nested-counted shape: 2501 states /
     *  6.4 M kernels — must stay under the default caps. */
    @Test
    void largestLegitNestedCountedStillCompiles() {
        io.github.jemmix.tdfa.Pattern p = Pattern.compile("(a{1,50}){1,50}");
        assertThat(p.matcher("aaaa").find()).isTrue();
    }

    /** Per-kernel spike bound: kernelsTotal only counts after addState, so a
     *  single closure can spike the heap on its own. */
    @Test
    void closureSpikeCapRejectsCleanly() {
        System.setProperty("tdfa.max.closure", "1000");
        try {
            assertThatCode(() -> Pattern.compile("(a{1,50}){1,50}"))
                    .isInstanceOf(PatternSyntaxException.class)
                    .hasMessageContaining("pattern too large")
                    .hasMessageContaining("tdfa.max.closure");
        } finally {
            System.clearProperty("tdfa.max.closure");
        }
    }

    @Test
    void legitPatternsCompileUnderDefaultBudget() {
        // Largest legit in-corpus shapes: dictionary-style literal alternation
        // (19.6 K pre-min states at 2 663 branches) and the datefinder
        // alternation — both far under the 100 K default cap.
        StringBuilder dict = new StringBuilder();
        for (int i = 0; i < 2000; i++) {
            if (i > 0) dict.append('|');
            dict.append("word").append(i);
        }
        assertThatCode(() -> {
            Pattern p = Pattern.compile(dict.toString());
            assertThat(p.matcher("xword1999y").find()).isTrue();
        }).doesNotThrowAnyException();
    }

    @Test
    void budgetOverrideRaisesTheCeiling() {
        // The caps are per-compile reads of the system properties, so a raised
        // budget admits patterns the default would reject. Uses the kernel-total
        // cap (re2c's MAX_DFA_SIZE analogue): {0,60} × 2 totals ~220 K kernel
        // entries across its ~3.7 K states — over a 100 K cap, under any heap.
        System.setProperty("tdfa.max.kernels", "100000");
        try {
            assertThatCode(() -> Pattern.compile(
                    "[\\s\\S]{0,60}x[\\s\\S]{0,60}")).isInstanceOf(PatternSyntaxException.class);
        } finally {
            System.clearProperty("tdfa.max.kernels");
        }
        assertThatCode(() -> Pattern.compile("[\\s\\S]{0,60}x[\\s\\S]{0,60}"))
                .doesNotThrowAnyException();
    }
}
