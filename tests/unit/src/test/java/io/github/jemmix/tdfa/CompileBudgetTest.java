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
    private static final String CONTEXT_BOMB =
            "[A-Za-z]{10}\\s+[\\s\\S]{0,100}Result[\\s\\S]{0,100}\\s+[A-Za-z]{10}";

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
     *  10001 states). Its whole builds still reject (pinned in
     *  SingleCompileWholeTest), so the lenient flag carries the pin. */
    @Test
    void nestedCountedNowCompiles() {
        long t0 = System.nanoTime();
        io.github.jemmix.tdfa.Pattern p = Pattern.compile("(a{1,100}){1,100}",
                io.github.jemmix.tdfa.Pattern.DEFER_WHOLE_REJECTION);
        assertThat(p.matcher("a".repeat(120)).find()).isTrue();
        assertThat((System.nanoTime() - t0) / 1_000_000)
                .as("nested-counted compile wall").isLessThan(15_000);
    }

    /** Fuzz round 24 (caseSeed 727613823329836856): a 287-state DFA whose
     *  φ-variant finals made buildCfg materialize a 22,637-block /
     *  157,176,487-edge CFG — liveness burned ~60 s at library budget and
     *  >10 s past the fuzz watchdog per engine (cpuMs=7161, verdict=spin).
     *  Pinned with \x{...} escapes so the source stays pure ASCII; lone
     *  surrogates are exactly what the original carried. */
    private static final String CFG_EDGE_BOMB =
            "(?:(?s:\\-{3}\\x{3042}{0,4}v)(?s:s\\x{dbff}Y))(?U:(\\D)@|_(?:(?<n0>\\-[^.9q-\\x{d800}_]*?Y)\\-\\x{dc21})a"
            + "(?:(?U:z)(?:wa?.{4,}^|$\\x{3a9}?\\Q @_\\E$)*(\\b.)))(?i:q\\x{10402}\\x{11c07})";

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
     * 917334682215128318), under the no-lazy-compiles contract and the
     * opt-in defer policy ({@code DEFER_WHOLE_REJECTION}): a bomb whose
     * whole builds (unpruned AND anchored) exceed the budget runs each
     * doomed attempt exactly ONCE — inside compile() — and every matches()
     * call rethrows the recorded rejection; nothing recompiles at match
     * time (the former LazyEngine corner re-burned the 8 M-tick rejection
     * ~16× per batch and crossed the fuzz watchdog as a spin). Without the
     * flag, compile() itself fails with the same clean rejection (the
     * default since 2026-09-15 — pinned in SingleCompileWholeTest). The
     * bounded-gap family ([\s\S]{0,60}x[\s\S]{0,60}: find compiles, the
     * whole DFA is an intrinsically-huge counter cross-product) is
     * pinned below. Pinned with \x{...}/escapes per CFG_EDGE_BOMB above. */
    @Test
    void bombWholeOverBudgetRecordsRejectionOnceEagerly() {
        String bomb = "(?:(?m:\u00e9)(?:\\w[^\u03a9z\\-]{0,}|\ud835\udd04\udfff){1,4}){1,5}";
        System.setProperty("tdfa.budget.compile.compute", "8388608");
        try {
            long t0 = System.nanoTime();
            io.github.jemmix.tdfa.Pattern p = Pattern.compile(bomb,
                    io.github.jemmix.tdfa.Pattern.DEFER_WHOLE_REJECTION);   // find artifact accepted
            assertThat((System.nanoTime() - t0) / 1_000_000)
                    .as("wall of the eager ladder (two bounded doomed whole builds)")
                    .isLessThan(10_000);
            RuntimeException[] recorded = new RuntimeException[1];
            assertThatCode(() -> p.matcher("\u00e9zz").matches())
                    .isInstanceOf(PatternSyntaxException.class)
                    .hasMessageContaining("pattern too large")
                    .satisfies(ex -> recorded[0] = (RuntimeException) ex);
            long t1 = System.nanoTime();
            assertThatCode(() -> p.matcher("\u00e9zz").matches())
                    .isInstanceOf(PatternSyntaxException.class)
                    .satisfies(ex -> assertThat((RuntimeException) ex).isSameAs(recorded[0]));
            assertThat((System.nanoTime() - t1) / 1_000_000)
                    .as("wall of the recorded-rethrow matches()").isLessThan(5);
        } finally {
            System.clearProperty("tdfa.budget.compile.compute");
        }
    }

    @Test
    void boundedGapWholeBombKeepsFind() {
        // The corpus-impact decision made concrete — and REVERSED
        // (2026-09-15): find compiles (~28 K kernels under the RAM-derived
        // cap), both whole builds reject (anchored: 100 001+ states — the
        // counter cross-product is the MINIMAL whole DFA). Default:
        // compile() fails — shipping a Pattern whose matches() is
        // permanently broken behind a successful compile is the trap; the
        // lenient find-only acceptance (find() works, matches() rethrows
        // the recorded rejection) is the explicit opt-in.
        assertThatCode(() -> Pattern.compile("[\\s\\S]{0,60}x[\\s\\S]{0,60}"))
                .isInstanceOf(PatternSyntaxException.class)
                .hasMessageContaining("pattern too large");
        io.github.jemmix.tdfa.Pattern p = Pattern.compile(
                "[\\s\\S]{0,60}x[\\s\\S]{0,60}", io.github.jemmix.tdfa.Pattern.DEFER_WHOLE_REJECTION);
        assertThat(p.matcher("aaaxbbb").find()).isTrue();
        assertThat(p.matcher("nothing to find in this line at all").find()).isFalse();
        assertThatCode(() -> p.matcher("aaaxbbb").matches())
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
        // The budgets are per-compile reads of the system properties, so a
        // lowered RAM budget rejects patterns the default admits (and a
        // raised one admits more). 1.6 MB derives a ~20 K kernel cap (the
        // {0,60} × 2 shape totals ~28 K kernel entries across its states —
        // the right-nested suffix shrank the flat tail's 220 K). The DEFER
        // flag carries the find-only acceptance: the whole side rejects
        // regardless of the caps (see boundedGapWholeBombKeepsFind).
        System.setProperty("tdfa.budget.compile.memory", "1600000");
        try {
            assertThatCode(() -> Pattern.compile(
                    "[\\s\\S]{0,60}x[\\s\\S]{0,60}")).isInstanceOf(PatternSyntaxException.class);
        } finally {
            System.clearProperty("tdfa.budget.compile.memory");
        }
        assertThatCode(() -> Pattern.compile("[\\s\\S]{0,60}x[\\s\\S]{0,60}",
                io.github.jemmix.tdfa.Pattern.DEFER_WHOLE_REJECTION))
                .doesNotThrowAnyException();
    }
}
