package io.github.jemmix.tdfa;

import io.github.jemmix.tdfa.core.PatternSyntaxException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Engine determinization budgets: compile RAM ({@code tdfa.budget.compile.memory})
 * bounds the OUTPUT structures through the weight model (states, kernel
 * totals, closure spikes, CFG edges — see {@code BudgetWeights}/{@code Budgets}),
 * compile CPU ({@code tdfa.budget.compile.compute}, ticks) bounds the WORK
 * ({@code WorkMeter}). A pattern whose TDFA construction exceeds either
 * must fail compilation with a clean {@code "pattern too large"}
 * {@link PatternSyntaxException} — quickly, without exhausting memory —
 * instead of burning unbounded time/heap.
 *
 * <p>Reference-implementation context: re2c 4.5.1 refuses two-site
 * {@code [^]{0,16}x[^]{0,16}} outright ("DFA has too many states"); our
 * construction is more compact on that family (10 K states at {0,100}) and
 * only caps the intrinsically-huge cross-products (the rebar
 * bounded-repeat/context shape: 200 K+ MINIMAL states, aborts at the
 * default budget in seconds on a default heap where the uncapped compile
 * needs 12 GB and ~49 s).
 */
class CompileBudgetTest {

    /** The rebar curated/10-bounded-repeat/context shape (both sites). */
    private static final String CONTEXT_BOMB = "[A-Za-z]{10}\\s+[\\s\\S]{0,100}Result[\\s\\S]{0,100}\\s+[A-Za-z]{10}";

    /** Compile-time edge bomb from the fuzz corpus (CFG blowup: a
     *  157,176,487-edge CFG — liveness burned ~60 s at library budget and
     *  >10 s past the fuzz watchdog per engine (cpuMs=7161, verdict=spin).
     *  Pinned with \x{...} escapes so the source stays pure ASCII; lone
     *  surrogates are exactly what the original carried. */
    private static final String CFG_EDGE_BOMB = "(?:(?s:\\-{3}\\x{3042}{0,4}v)(?s:s\\x{dbff}Y))(?U:(\\D)@|_(?:(?<n0>\\-[^.9q-\\x{d800}_]*?Y)\\-\\x{dc21})a"
                    + "(?:(?U:z)(?:wa?.{4,}^|$\\x{3a9}?\\Q @_\\E$)*(\\b.)))(?i:q\\x{10402}\\x{11c07})";

    /** Compile RAM budget that derives a ~20 K-state cap (the historical
     *  tight cap for the context bomb: 20 000 &times; 256 B/state). Also
     *  derives ~64 K kernels / ~4 K closure — far below this shape's
     *  needs on every axis. */
    private static final String MEM_20K_STATES = "5120000";

    @Test
    void overBudgetPatternFailsFastWithCleanError() {
        System.setProperty("tdfa.budget.compile.memory", MEM_20K_STATES);
        try {
            long t0 = System.nanoTime();
            assertThatCode(() -> Pattern.compile(CONTEXT_BOMB))
                            .isInstanceOf(PatternSyntaxException.class)
                            .hasMessageContaining("pattern too large")
                            .hasMessageContaining("tdfa.budget.compile.memory");
            long ms = (System.nanoTime() - t0) / 1_000_000;
            // ~2 s measured at the 20 K cap on laptop hardware; the point is
            // fail-FAST — the uncapped compile needs 12 GB and ~49 s.
            assertThat(ms).as("wall to rejection at the derived 20 K state cap").isLessThan(15_000);
        } finally {
            System.clearProperty("tdfa.budget.compile.memory");
        }
    }

    /**
     * The WORK budget (WorkMeter): nested-quantifier bombs whose closure
     * churns fixpoints without materializing states never trip the
     * state/kernel caps (fuzzer-found; e.g. the tryMap family). A tight
     * budget must reject them with the same clean error shape.
     */
    @Test
    void workBudgetRejectsClosureSpinners() {
        String spinner = "(kq)(?U:(\\n*?n)mZ)(?<n0>(?U:( )q)(?:(?:\\W\\z)(\\#.+\\~|\\n{1,1}éu(?<n2>s\\#)\\.)*?){4,}\\t)";
        System.setProperty("tdfa.budget.compile.compute", "10000000");
        try {
            long t0 = System.nanoTime();
            assertThatCode(() -> Pattern.compile(spinner))
                            .isInstanceOf(PatternSyntaxException.class)
                            .hasMessageContaining("pattern too large")
                            .hasMessageContaining("tdfa.budget.compile.compute");
            assertThat((System.nanoTime() - t0) / 1_000_000).as("wall to work-budget rejection").isLessThan(30_000);
        } finally {
            System.clearProperty("tdfa.budget.compile.compute");
        }
    }

    /** Alternation-in-counted-repetition bomb (fuzzer family): the cross
     *  product is genuinely huge — clean state-cap rejection. The former
     *  plain nested-counted bombs ((a{1,100}){1,100} etc.) now COMPILE since
     *  the {n,m} desugaring moved to re2j's right-nested suffix (round 18):
     *  (a{1,100}){1,50} compiles in ~1 s at 10001 states where the flat tail
     *  burned 19.6 M kernels — see nestedCountedNowCompiles below. */
    @Test
    void alternationCountedBombCleanRejects() {
        System.setProperty("tdfa.budget.compile.memory", MEM_20K_STATES);
        try {
            long t0 = System.nanoTime();
            assertThatCode(() -> Pattern.compile(
                            "(x{2,4}?z|\\D{1,6}?.+$|~|W(?U:9(\\.b~))\\-){4,}"))
                            .isInstanceOf(PatternSyntaxException.class)
                            .hasMessageContaining("pattern too large")
                            .hasMessageContaining("tdfa.budget.compile.memory");
            assertThat((System.nanoTime() - t0) / 1_000_000)
                            .as("wall to state-cap rejection").isLessThan(30_000);
        } finally {
            System.clearProperty("tdfa.budget.compile.memory");
        }
    }

    /** The classic nested-counted shape's FIND artifact now compiles under
     *  default budgets — the right-nested suffix collapsed the determinization
     *  ~90x in kernel total ((a{1,100}){1,100}: 19.6 M kernels -> 148 K,
     *  10001 states). Its cut-free whole artifact still rejects (pinned in
     *  WholeMatchTest), so the facade compile fails; the find-artifact pin
     *  runs on the core Tdfa API where the whole attempt doesn't interfere. */
    @Test
    void nestedCountedNowCompiles() {
        long t0 = System.nanoTime();
        io.github.jemmix.tdfa.tdfa.Tdfa find = io.github.jemmix.tdfa.tdfa.Tdfa.compile(
                        io.github.jemmix.tdfa.tnfa.Tnfa.compile("(a{1,100}){1,100}"), false);
        assertThat(new io.github.jemmix.tdfa.tdfa.TdfaRunner(find).find("a".repeat(120))).isTrue();
        assertThat((System.nanoTime() - t0) / 1_000_000)
                        .as("nested-counted find-artifact compile wall").isLessThan(15_000);
        assertThatCode(() -> Pattern.compile("(a{1,100}){1,100}"))
                        .isInstanceOf(PatternSyntaxException.class)
                        .hasMessageContaining("pattern too large");
    }

    /** Fuzz round 24 (caseSeed 727613823329836856): a 287-state DFA whose
     *  φ-variant finals made buildCfg materialize a 22,637-block /
     *  157,176,487-edge CFG — liveness burned ~60 s at library budget and
     *  >10 s past the fuzz watchdog per engine (cpuMs=7161, verdict=spin).
     *  Pinned with \x{...} escapes so the source stays pure ASCII; lone
    
    @Test
    void cfgEdgeExplosionCapRejectsCleanly() {
        long t0 = System.nanoTime();
        assertThatCode(() -> Pattern.compile(CFG_EDGE_BOMB))
                        .isInstanceOf(PatternSyntaxException.class)
                        .hasMessageContaining("pattern too large")
                        .hasMessageContaining("CFG edge budget")
                        .hasMessageContaining("tdfa.budget.compile.memory");
        // ~0.7 s measured (cap trips during the successor-arc BFS); the point
        // is fail-fast — uncapped, the compile took ~60 s per engine.
        assertThat((System.nanoTime() - t0) / 1_000_000)
                        .as("wall to CFG-edge rejection").isLessThan(10_000);
    }
    
    /** Per-kernel spike bound: kernelsTotal only counts after addState, so a
     *  single closure can spike the heap on its own. The wide-alternation
     *  bomb builds 4-figure closures. Derived closure cap = RAM budget /
     *  16 / 80 B: 12 800 bytes → 10 configs. */

    @Test
    void closureSpikeCapRejectsCleanly() {
        System.setProperty("tdfa.budget.compile.memory", "12800");
        try {
            // 13-arm alternation: initial closure is ~16 configs wide
            assertThatCode(() -> Pattern.compile(
                            "(ab|cd|ef|gh|ij|kl|mn|op|qr|st|uv|wx|yz){2}"))
                            .isInstanceOf(PatternSyntaxException.class)
                            .hasMessageContaining("pattern too large")
                            .hasMessageContaining("tdfa.budget.compile.memory");
        } finally {
            System.clearProperty("tdfa.budget.compile.memory");
        }
    }

    /** Fuzz round 27's spin family (caseSeeds 4496606199222982303,
     * 917334682215128318): a bomb whose whole artifact exceeds the budget
     * fails compile() exactly once, eagerly, with the clean rejection —
     * the find determinization and the one doomed cut-free attempt all run
     * inside compile() on the shared CPU ledger (nothing recompiles at
     * match time). Pinned with \x{...}/escapes per CFG_EDGE_BOMB above. */
    @Test
    void bombWholeOverBudgetFailsCompileEagerly() {
        String bomb = "(?:(?m:\u00e9)(?:\\w[^\u03a9z\\-]{0,}|\ud835\udd04\udfff){1,4}){1,5}";
        System.setProperty("tdfa.budget.compile.compute", "8388608");
        try {
            long t0 = System.nanoTime();
            assertThatCode(() -> Pattern.compile(bomb))
                            .isInstanceOf(PatternSyntaxException.class)
                            .hasMessageContaining("pattern too large");
            assertThat((System.nanoTime() - t0) / 1_000_000)
                            .as("wall of the budgeted compile (find + one doomed whole attempt)")
                            .isLessThan(10_000);
        } finally {
            System.clearProperty("tdfa.budget.compile.compute");
        }
    }

    @Test
    void boundedGapWholeBombFailsCompile() {
        // Find compiles (~28 K kernels under the RAM-derived cap); the
        // pike cut bit, and the cut-free whole DFA is an intrinsically
        // huge counter cross-product (100 001+ states minimal) — over the
        // budget, so compile() fails rather than shipping a Pattern whose
        // matches() would be broken.
        assertThatCode(() -> Pattern.compile("[\\s\\S]{0,60}x[\\s\\S]{0,60}"))
                        .isInstanceOf(PatternSyntaxException.class)
                        .hasMessageContaining("pattern too large");
    }

    @Test
    void legitPatternsCompileUnderDefaultBudget() {
        // Largest legit in-corpus shapes: dictionary-style literal alternation
        // (19.6 K pre-min states at 2 663 branches) and the datefinder
        // alternation — both far under the RAM-derived default caps.
        StringBuilder dict = new StringBuilder();
        for (int i = 0; i < 2000; i++) {
            if (i > 0) {
                dict.append('|');
            }
            dict.append("word").append(i);
        }
        assertThatCode(() -> {
            Pattern p = Pattern.compile(dict.toString());
            assertThat(p.matcher("xword1999y").find()).isTrue();
        }).doesNotThrowAnyException();
    }

    @Test
    void budgetOverrideRaisesTheCeiling() {
        // The budgets are per-compile reads of the system properties, so a
        // lowered RAM budget rejects patterns the default admits (and a
        // raised one admits more). 1.6 MB derives a ~20 K kernel cap; the
        // {0,10} × 2 bounded-gap shape fits the default caps but not that
        // one (its {0,60} sibling is over-budget at ANY cap — see
        // boundedGapWholeBombFailsCompile).
        System.setProperty("tdfa.budget.compile.memory", "1600000");
        try {
            assertThatCode(() -> Pattern.compile(
                            "[\\s\\S]{0,10}x[\\s\\S]{0,10}")).isInstanceOf(PatternSyntaxException.class);
        } finally {
            System.clearProperty("tdfa.budget.compile.memory");
        }
        assertThatCode(() -> Pattern.compile("[\\s\\S]{0,10}x[\\s\\S]{0,10}"))
                        .doesNotThrowAnyException();
    }
}
