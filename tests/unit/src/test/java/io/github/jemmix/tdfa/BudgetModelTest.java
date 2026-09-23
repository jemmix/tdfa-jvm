package io.github.jemmix.tdfa;

import io.github.jemmix.tdfa.core.CompileObserver;
import io.github.jemmix.tdfa.core.PatternSyntaxException;
import io.github.jemmix.tdfa.tdfa.BudgetWeights;
import io.github.jemmix.tdfa.tdfa.Budgets;
import io.github.jemmix.tdfa.tdfa.Tdfa;
import io.github.jemmix.tdfa.tnfa.Tnfa;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The budget model itself: the three {@code tdfa.budget.*} properties, the
 * hardcoded weight model ({@link BudgetWeights}) that translates them into
 * the internal caps, and the review-r10 budget holes the model closes —
 * the pre-determinization surface (TNFA builder RAM+CPU, P0-1; the
 * parser's fold-range scan, P1-1) and the Moore fixpoint (P1-4, which
 * degrades instead of rejecting because the unminimized DFA is correct).
 */
class BudgetModelTest {

    @AfterEach
    void cleanup() {
        System.clearProperty(Budgets.COMPILE_MEMORY_PROP);
        System.clearProperty(Budgets.COMPILE_COMPUTE_PROP);
        System.clearProperty(Budgets.RUNTIME_MEMORY_PROP);
    }

    /** Defaults: 128 MiB compile RAM, 500 M compile ticks (5 s at the
     *  assumed 100 M ticks/s), 16 MiB runtime RAM per pattern. */
    @Test
    void budgetDefaults() {
        assertThat(Budgets.compileMemoryBytes()).isEqualTo(128L << 20);
        assertThat(Budgets.compileComputeTicks()).isEqualTo(500_000_000L);
        assertThat(Budgets.runtimeMemoryBytes()).isEqualTo(16L << 20);
    }

    /** The weight model, pinned: every derived default cap is a budget
     *  divided by a weight, and the numbers below are the contract.
     *  (128 MiB / 256 B = 524 288 states — Perl mode / 512 B = 262 144,
     *  / 80 B = 1 677 721 kernels, / 16 / 80 B = 104 857 closure configs,
     *  / 32 B = 4 194 304 CFG edges, / 4 B = 33 554 432 norm cells;
     *  16 MiB partitioned 4/8 rows, 3/8 search blocks, 1/8 walk memo —
     *  see BudgetWeights.) */
    @Test
    void derivedCapsPinTheWeightModel() {
        assertThat(BudgetWeights.TNFA_BUILD_ACTION_TICKS).isEqualTo(5);
        assertThat(BudgetWeights.TNFA_EPS_EDGE_BYTES).isEqualTo(64);
        assertThat(BudgetWeights.KERNEL_CONFIG_BYTES).isEqualTo(80);
        assertThat(Budgets.maxDfaStates()).isEqualTo(524_288);
        // Perl mode adds the stop-table surcharge (int[n*64] = 256 B/state):
        assertThat(Budgets.maxDfaStates(BudgetWeights.STOP_TABLE_STATE_BYTES)).isEqualTo(262_144);
        assertThat(Budgets.maxKernelConfigs()).isEqualTo(1_677_721L);
        assertThat(Budgets.maxClosureConfigs()).isEqualTo(104_857);
        assertThat(Budgets.maxCfgEdges()).isEqualTo(4_194_304L);
        assertThat(Budgets.maxMinimizeNormCells()).isEqualTo(33_554_432L);
        // 8 MiB row share (4/8) in weighted rows: fixed 640 B + 8 B/state-word,
        // floored.
        assertThat(Budgets.sdfaMaxRows(1)).isEqualTo(12_945);
        assertThat(Budgets.sdfaMaxRows(3125)).isEqualTo(327);
        // 6 MiB block share (3/8): the walk memo takes the last eighth.
        assertThat(Budgets.sdfaMaxBlocks()).isEqualTo(2_891);
        // walk memo: 1/8 = 2 MiB, floored at the 64-block minimum.
        assertThat(Budgets.walkMaxBytes()).isEqualTo(2_097_152L);
        // per-engine split: the budget-parameterized variants see half:
        assertThat(Budgets.sdfaMaxRows(1, 8_388_608L)).isEqualTo(6_472);
        assertThat(Budgets.sdfaMaxBlocks(8_388_608L)).isEqualTo(1_445);
        assertThat(Budgets.walkMaxBytes(8_388_608L)).isEqualTo(1_048_576L);
    }

    /** Properties override the defaults and take effect on the next call —
     *  budgets are fresh per compile / per runner, never class-frozen. */
    @Test
    void propertiesOverrideAndAreReadFresh() {
        System.setProperty(Budgets.COMPILE_MEMORY_PROP, "4096"); // 16 states
        assertThat(Budgets.maxDfaStates()).isEqualTo(16);
        System.setProperty(Budgets.COMPILE_COMPUTE_PROP, "7777");
        assertThat(Budgets.compileComputeTicks()).isEqualTo(7_777L);
        System.setProperty(Budgets.RUNTIME_MEMORY_PROP, "217600"); // ~37 blocks
        assertThat(Budgets.sdfaMaxBlocks()).isEqualTo(37);
        // and the pipeline sees it on the very next compile:
        assertThatCode(() -> Pattern.compile("ab|cd|ef|gh|ij"))
                        .isInstanceOf(PatternSyntaxException.class)
                        .hasMessageContaining("pattern too large")
                        .hasMessageContaining(Budgets.COMPILE_MEMORY_PROP);
    }

    /** Review r10 P0-1: nested counted repeats used to OOM the JVM in
     *  Tnfa$Builder.buildRepeat before any determinization cap could fire
     *  (27 M states / -Xmx2g). The builder's weighted RAM accounting and
     *  per-action ticks now reject the same 19-char bomb cleanly, fast,
     *  through the facade's translated PatternSyntaxException. */
    @Test
    void nestedRepeatBombRejectsBeforeDeterminization() {
        long t0 = System.nanoTime();
        assertThatCode(() -> Pattern.compile("((a{300}){300}){300}"))
                        .isInstanceOf(PatternSyntaxException.class)
                        .hasMessageContaining("pattern too large")
                        .hasMessageContaining("TNFA construction")
                        .hasMessageContaining(Budgets.COMPILE_MEMORY_PROP);
        assertThat((System.nanoTime() - t0) / 1_000_000)
                        .as("wall to the front-end rejection").isLessThan(10_000);
    }

    /** Review r10 P1-1: the parser's O(universe) fold-range scan under
     *  {@code (?i)} is metered CPU work — a full-universe class cannot
     *  burn scan time invisible to the compute budget. */
    @Test
    void foldRangeScanIsBudgetVisible() {
        System.setProperty(Budgets.COMPILE_COMPUTE_PROP, "100000");
        assertThatCode(() -> Pattern.compile("(?i)[\\x{0}-\\x{10FFFF}]"))
                        .isInstanceOf(PatternSyntaxException.class)
                        .hasMessageContaining("pattern too large")
                        .hasMessageContaining(Budgets.COMPILE_COMPUTE_PROP);
    }

    /** Review r10 P1-4: the Moore fixpoint is metered, and because the
     *  unminimized DFA is still correct, exhaustion DEGRADES (the pass is
     *  skipped, noted in the observer) instead of failing the compile.
     *  The suffix-chain DFA over {@code a?×900 b} (flat concatenation —
     *  the equivalent {@code a{0,900}b} desugars into ~900 AST levels and
     *  overflows shallower CI stacks) determinizes in ~2.2 M ticks but
     *  peels one Moore group per round (~900 rounds × 902 states, needing
     *  ~3.5 M): a wide budget window, and tick counts are deterministic,
     *  so 2.75 M sits centrally in it on every machine. */
    @Test
    void minimizerFixpointDegradesInsteadOfRejecting() {
        StringBuilder chain = new StringBuilder();
        for (int i = 0; i < 900; i++) {
            chain.append("a?");
        }
        chain.append('b');
        String suffixChain = chain.toString();
        Map<String, String> notes = new HashMap<>();
        CompileObserver rec = new CompileObserver() {
            @Override
            public void note(String key, String value) {
                notes.put(key, value);
            }
        };
        System.setProperty(Budgets.COMPILE_COMPUTE_PROP, "2750000");
        Tdfa t = Tdfa.compile(Tnfa.compile(suffixChain), false, rec);
        assertThat(notes.get("minimize")).isEqualTo("skipped (compute budget)");
        assertThat(t.stateCount()).isEqualTo(902);
        // with budget to spare, the same pattern minimizes normally:
        System.setProperty(Budgets.COMPILE_COMPUTE_PROP, "8000000");
        notes.clear();
        Tdfa t2 = Tdfa.compile(Tnfa.compile(suffixChain), false, rec);
        assertThat(notes.get("minimize")).isNull();
        assertThat(t2.stateCount()).isEqualTo(902); // chain is already minimal
        // and the artifact is correct through the full facade, at the
        // default budgets (the budgeted legs above stay on the Tdfa API
        // where the scoped caps don't interfere):
        System.clearProperty(Budgets.COMPILE_COMPUTE_PROP);
        io.github.jemmix.tdfa.Pattern p = Pattern.compile(suffixChain);
        assertThat(p.matcher("a".repeat(900) + "b").find()).isTrue();
        assertThat(p.matcher("a".repeat(901) + "b").find()).isTrue(); // unanchored: matches from index 1
        assertThat(p.matcher("a".repeat(901) + "c").find()).isFalse();
    }

    // ===== budget-accounting pins (round r11) =====

    /** Active-set precompute (TdfaCompiler ctor): O(cells × edges)
     *  cc.matches probes plus one long[words] per cell must be visible to
     *  BOTH budgets — a many-distinct-single-char alternation asks for
     *  megabytes of arrays and 10^10 probes, and both the RAM charge and
     *  the work ticks must reject it cleanly. */
    @Test
    void activeSetPrecomputeIsBudgetVisible() {
        // ~2600 disjoint single-char classes: 5200+ cells × 41 words × 8 B
        // ≈ 1.7 MB of active-set arrays — far over a 512 KB RAM budget.
        StringBuilder p = new StringBuilder();
        for (int i = 0; i < 2600; i++) {
            if (i > 0) {
                p.append('|');
            }
            p.append('\\').append('x').append('{').append(Integer.toHexString(0x2000 + i)).append('}');
        }
        System.setProperty(Budgets.COMPILE_MEMORY_PROP, "524288");
        try {
            long t0 = System.nanoTime();
            assertThatCode(() -> Pattern.compile(p.toString()))
                            .isInstanceOf(PatternSyntaxException.class)
                            .hasMessageContaining("pattern too large")
                            .hasMessageContaining("active-set");
            assertThat((System.nanoTime() - t0) / 1_000_000).as("wall to RAM rejection").isLessThan(10_000);
        } finally {
            System.clearProperty(Budgets.COMPILE_MEMORY_PROP);
        }
        // Same shape under a tiny WORK budget: the probe scan's ticks reject.
        System.setProperty(Budgets.COMPILE_COMPUTE_PROP, "100000");
        try {
            assertThatCode(() -> Pattern.compile(p.toString()))
                            .isInstanceOf(PatternSyntaxException.class)
                            .hasMessageContaining("pattern too large")
                            .hasMessageContaining(Budgets.COMPILE_COMPUTE_PROP);
        } finally {
            System.clearProperty(Budgets.COMPILE_COMPUTE_PROP);
        }
        // and at the DEFAULT budgets the same shape compiles fine (its DFA
        // is a trivial chain): the caps see only the pathological scale.
        assertThatCode(() -> Pattern.compile(p.toString())).doesNotThrowAnyException();
    }

    /** Kernel configs carry int[tags] register slices: the per-config
     *  weight is tag-aware (80 B + 4 B/tag), so a capture-heavy closure
     *  bomb rejects on the RAM budget, not on the heap. */
    @Test
    void kernelWeightsAreTagAware() {
        // 40 groups × optional nesting: closures multiply configs, each
        // carrying an int[80] regs slice on top of the 80 B base weight.
        // Scoped to 25 KB the weighted spike cap fires.
        StringBuilder p = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            p.append("((a?)");
        }
        for (int i = 0; i < 20; i++) {
            p.append(")");
        }
        System.setProperty(Budgets.COMPILE_MEMORY_PROP, "25600");
        try {
            assertThatCode(() -> Pattern.compile(p.toString()))
                            .isInstanceOf(PatternSyntaxException.class)
                            .hasMessageContaining("pattern too large")
                            .hasMessageContaining(Budgets.COMPILE_MEMORY_PROP);
        } finally {
            System.clearProperty(Budgets.COMPILE_MEMORY_PROP);
        }
    }

    /** Interned tag histories and their derived caches are charged against
     *  the compile RAM budget. Deep nesting grows histories quadratically
     *  (every prefix length interned) while the TNFA itself stays linear —
     *  the history charge must fire first. */
    @Test
    void tagHistoriesAreCharged() {
        System.setProperty(Budgets.COMPILE_MEMORY_PROP, "2097152");
        try {
            StringBuilder p = new StringBuilder();
            for (int i = 0; i < 1000; i++) {
                p.append("((a)");
            }
            for (int i = 0; i < 1000; i++) {
                p.append(")");
            }
            assertThatCode(() -> Pattern.compile(p.toString()))
                            .isInstanceOf(PatternSyntaxException.class)
                            .hasMessageContaining("pattern too large")
                            .hasMessageContaining(Budgets.COMPILE_MEMORY_PROP);
        } finally {
            System.clearProperty(Budgets.COMPILE_MEMORY_PROP);
        }
    }

    /** The walk-block memo (wide-codepoint dispatch) is budget-bounded:
     *  per-state id tables and 512-int blocks are charged against the
     *  runner's walk share; over it, dispatch falls back to binary search
     *  and the match answer is unchanged. */
    @Test
    void walkMemoIsBudgetBoundedAndCorrect() {
        System.setProperty(Budgets.RUNTIME_MEMORY_PROP, "20480"); // 2 KB walk share -> floor
        try {
            io.github.jemmix.tdfa.Pattern p = Pattern.compile("[\\x{400}-\\x{600}]{2,}");
            // Wide-codepoint scans exercise WalkIndex; the capped memo must
            // still answer exactly (binary-search fallback).
            assertThat(p.matcher("\u0451\u04FF\u0500x").find()).isTrue();
            assertThat(p.matcher("x\u0451x").find()).isFalse();
            assertThat(p.matcher("\u05FF\u0400\u0401").find()).isTrue();
        } finally {
            System.clearProperty(Budgets.RUNTIME_MEMORY_PROP);
        }
    }

    /** One compile's work stays within ONE compile CPU budget: the
     *  attempts (front-end, find determinization, cut-free whole
     *  determinization) share a ledger, so a bomb whose whole artifact
     *  overruns it rejects after at most the scoped budget of work. */
    @Test
    void compileTotalIsOneCpuBudget() {
        System.setProperty(Budgets.COMPILE_COMPUTE_PROP, "6000000");
        try {
            long t0 = System.nanoTime();
            assertThatCode(() -> Pattern.compile(
                            "(?:(?m:\u00e9)(?:\\w[^\u03a9z\\-]{0,}|\ud835\udd04\udfff){1,4}){1,5}"))
                            .isInstanceOf(PatternSyntaxException.class)
                            .hasMessageContaining("pattern too large");
            // 6 M ticks ≈ tens of ms of work; the ledger keeps the total
            // near the scoped budget. Generous upper bound for CI variance.
            assertThat((System.nanoTime() - t0) / 1_000_000)
                            .as("wall of the fully-ledgered compile").isLessThan(15_000);
        } finally {
            System.clearProperty(Budgets.COMPILE_COMPUTE_PROP);
        }
    }
}
