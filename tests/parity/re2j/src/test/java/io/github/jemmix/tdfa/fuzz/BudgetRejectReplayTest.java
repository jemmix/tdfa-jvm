package io.github.jemmix.tdfa.fuzz;

import io.github.jemmix.tdfa.core.budget.Budgets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F7 of the 2026-10-24 jur campaign: the soak's BUDGET_REJECT rate rose
 * 13&times; (0.069% vs 0.005% on the 2026-10-16 jur leg) — verified here to
 * be genuine budget trips, not correctness trouble. The soak compiles under
 * a scoped 8 M-tick compute budget ({@code fuzz.max.work}, so budget
 * monsters reject in-watchdog instead of hanging); these seeds reproduce
 * that scoped rejection, then recompile with the knobs raised — every
 * raised-knob ACCEPT must answer the full five-probe protocol byte-identical
 * to the live {@code java.util.regex} oracle, and every still-rejecting
 * seed must keep rejecting cleanly (the CFG-blowup family expands past any
 * raised memory cap — genuinely too large, the budget doing its job).
 * Evidence trail incl. the memory-raised accepts and the 500 M-tick
 * burn-out monster: finding 7 of the 2026-10-24 campaign.
 */
@Timeout(300)
class BudgetRejectReplayTest {

    /** The soak's scoped compile budget (fuzz.max.work default, 8 M ticks). */
    private static final String FUZZ_SCOPED_TICKS = Long.toString(8L << 20);

    /** The library's own compile compute budget (5 s of ticks = 500 M). */
    private static final String LIBRARY_TICKS = Long.toString(Budgets.DEFAULT_COMPILE_COMPUTE);

    /** Raised-knob accept samples — six distinct shapes across both seed
     * windows (2026-10-28 fresh draw; 202610241338 the campaign's own first
     * chunk), each recorded as BUDGET_REJECT by the scoped soak. */
    private static final long[] ACCEPT_SEEDS = {4980790746082797768L, // (\w)+ CI+DOTALL — the "trivial pattern" surprise family
        3535361585978547224L, // \W+(.)
        4454343824950301576L, // \Q@@#\E(\w\D)+
        4093143974493173168L, // [^.\n]+(\w) — campaign chunk, non-trivial oracle spans
        5156517996765150184L, // ((?:\w){3,}j\t){1,}a — counted nesting
        3153916178798350840L, // {1,6}/{2,4} deep-nesting monster, flags=1
    };

    /** The stays-too-large family: unbounded atom under a counted loop —
     * its ε-subgraph duplication fills any raised memory budget. */
    private static final long CFG_BLOWUP_SEED = 7121637816971106064L; // (\w.{0,}){1,6}

    /** Finding 5's frontier monster, seed 2845852313826387552: post-#104
     * the zero-op frontier closure is metered per range, so the runaway
     * compile burns the WHOLE library compute budget instead of spinning
     * unmetered — clean rejection at any raise, the artifact unreachable
     * (at the campaign base 8910439 it compiled at 500 M ticks and
     * compared oracle-identical; see finding 7's coda). */
    private static final long F5_FRONTIER_SEED = 2845852313826387552L;

    @Test
    void raisedComputeBudgetAcceptsAndMatchesJurOracle() {
        try {
            raisedComputeAccepts();
        } finally {
            System.clearProperty("tdfa.budget.compile.compute");
        }
    }

    private static void raisedComputeAccepts() {
        for (long seed : ACCEPT_SEEDS) {
            DifferentialFuzzer.Case c = DifferentialFuzzer.generate(seed, DifferentialFuzzer.Lane.JUR);
            System.setProperty("tdfa.budget.compile.compute", FUZZ_SCOPED_TICKS);
            DifferentialFuzzer.Prepared scoped =
                DifferentialFuzzer.prepare(c.pattern(), c.flags(), DifferentialFuzzer.Lane.JUR);
            assertThat(scoped.asmTag).as("seed %s asm rejects at the soak's scoped budget", seed)
                .startsWith("<reject:pattern too large");
            assertThat(scoped.vmTag).as("seed %s vm rejects at the soak's scoped budget", seed)
                .startsWith("<reject:pattern too large");
            System.setProperty("tdfa.budget.compile.compute", LIBRARY_TICKS);
            DifferentialFuzzer.Prepared raised =
                DifferentialFuzzer.prepare(c.pattern(), c.flags(), DifferentialFuzzer.Lane.JUR);
            assertThat(raised.asmTag).as("seed %s asm compiles at the library budget", seed).isNull();
            assertThat(raised.vmTag).as("seed %s vm compiles at the library budget", seed).isNull();
            DifferentialFuzzer.Outcome o = DifferentialFuzzer.matchCase(raised, c);
            assertThat(o.exceptions).as("seed %s exceptions", seed).isEmpty();
            assertThat(o.memo).as("seed %s determinism double-run", seed).isEmpty();
            assertThat(o.cross).as("seed %s cross-rung", seed).isEmpty();
            assertThat(o.oracle).as("seed %s oracle answered", seed).doesNotStartWith("<");
            assertThat(o.asm).as("seed %s asm vs java.util.regex", seed).isEqualTo(o.oracle);
            assertThat(o.vm).as("seed %s vm vs java.util.regex", seed).isEqualTo(o.oracle);
        }
    }

    @Test
    void cfgBlowupStaysTooLargeAtRaisedMemory() {
        DifferentialFuzzer.Case c = DifferentialFuzzer.generate(CFG_BLOWUP_SEED, DifferentialFuzzer.Lane.JUR);
        DifferentialFuzzer.Prepared library =
            DifferentialFuzzer.prepare(c.pattern(), c.flags(), DifferentialFuzzer.Lane.JUR);
        assertThat(library.asmTag).as("asm rejects at the library budgets").startsWith("<reject:pattern too large")
            .contains("tdfa.budget.compile.memory");
        // 2x compile RAM: the closure expands PAST the doubled cap (4.19 M
        // successor arcs at 128 MiB, 33.5 M at 1 GiB — it fills whatever
        // budget it is given), still the clean documented rejection.
        System.setProperty("tdfa.budget.compile.memory", "268435456");
        try {
            DifferentialFuzzer.Prepared raised =
                DifferentialFuzzer.prepare(c.pattern(), c.flags(), DifferentialFuzzer.Lane.JUR);
            assertThat(raised.asmTag).as("asm still cleanly rejects at 2x compile RAM")
                .startsWith("<reject:pattern too large").contains("tdfa.budget.compile.memory");
            assertThat(raised.vmTag).as("vm still cleanly rejects at 2x compile RAM")
                .startsWith("<reject:pattern too large").contains("tdfa.budget.compile.memory");
        } finally {
            System.clearProperty("tdfa.budget.compile.memory");
        }
    }

    @Test
    void f5FrontierMonsterBurnsOutAtLibraryCompute() {
        System.setProperty("tdfa.budget.compile.compute", LIBRARY_TICKS);
        try {
            DifferentialFuzzer.Case c = DifferentialFuzzer.generate(F5_FRONTIER_SEED, DifferentialFuzzer.Lane.JUR);
            DifferentialFuzzer.Prepared raised =
                DifferentialFuzzer.prepare(c.pattern(), c.flags(), DifferentialFuzzer.Lane.JUR);
            assertThat(raised.asmTag).as("asm meters the frontier churn and rejects cleanly")
                .startsWith("<reject:pattern too large").contains("tdfa.budget.compile.compute");
            assertThat(raised.vmTag).as("vm meters the frontier churn and rejects cleanly")
                .startsWith("<reject:pattern too large").contains("tdfa.budget.compile.compute");
        } finally {
            System.clearProperty("tdfa.budget.compile.compute");
        }
    }
}
