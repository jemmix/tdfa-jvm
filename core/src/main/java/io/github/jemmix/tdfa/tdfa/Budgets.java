package io.github.jemmix.tdfa.tdfa;

/**
 * The user-facing resource budgets, as three system properties, and the
 * derived caps the pipeline actually checks. Compilation is bounded by a
 * <b>RAM budget</b> (bytes of transient compile-time structures, weighted
 * per structure via {@link BudgetWeights}) and a <b>CPU budget</b>
 * (abstract ticks of compile work, metered by {@link WorkMeter}); the
 * match-time scan is bounded by a <b>RAM budget</b> alone (the linear-time
 * guarantee makes a runtime CPU budget meaningless — no backtracking
 * engine exists to burn one).
 *
 * <table border=1>
 *   <tr><th>Property</th><th>Meaning</th><th>Default</th></tr>
 *   <tr><td>{@code tdfa.budget.compile.memory}</td><td>compile RAM, bytes</td>
 *       <td>128 MiB</td></tr>
 *   <tr><td>{@code tdfa.budget.compile.compute}</td><td>compile CPU, ticks
 *       (one tick &asymp; 10 ns; see {@link BudgetWeights#TICKS_PER_SECOND})</td>
 *       <td>5 s &times; tick rate = 500 M ticks</td></tr>
 *   <tr><td>{@code tdfa.budget.runtime.memory}</td><td>match-time RAM per
 *       compiled pattern (lazy search-DFA memo), bytes</td>
 *       <td>16 MiB</td></tr>
 * </table>
 *
 * <p>The derived caps (max DFA states, kernel totals, &epsilon;-closure
 * spike, active-set precompute, boxed transition ranges, CFG edges,
 * minimizer normalization cells, tag-history tables, search-DFA memo
 * rows/blocks, walk-memo blocks/per-state tables) are all linear
 * functions of the two compile budgets / the runtime budget through the
 * weight model (BudgetWeights) — there is deliberately no way to set them
 * directly anymore. Raise the budget, not the cap.
 *
 * <p><b>Ledger.</b> The compile CPU budget bounds one whole compile: the
 * attempts (front-end, find determinization, and — when the pike cut bit
 * — the cut-free whole determinization) each draw from one shared ledger
 * ({@link WorkMeter#fork(long)}), so a single compile can never burn more
 * than the budget.
 *
 * <p><b>Per-pattern runtime split.</b> The runtime RAM budget bounds the
 * lazy match-time memos of the pattern's engines. A pattern retaining TWO
 * engines (find plus a dedicated whole/anchored runner — the non-shared
 * ladder corners) splits the budget in half per engine, so the pattern's
 * combined memos stay within one budget; a shared artifact (one engine)
 * gets the whole budget.
 *
 * <p>Knob policy (see the inventory note in {@link Tdfa}): every read is
 * fresh — budgets take effect on the next compile (or the next runner
 * constructed, for the runtime budget) in the same JVM, and tests can
 * vary them without forking. Budget exhaustion fails compilation with the
 * standard clean "pattern too large" rejection.
 */
public final class Budgets {

    /**
     * System property: compile RAM budget in bytes.
     */
    public static final String COMPILE_MEMORY_PROP = "tdfa.budget.compile.memory";
    /**
     * System property: compile CPU budget in ticks.
     */
    public static final String COMPILE_COMPUTE_PROP = "tdfa.budget.compile.compute";
    /**
     * System property: per-pattern match-time RAM budget in bytes.
     */
    public static final String RUNTIME_MEMORY_PROP = "tdfa.budget.runtime.memory";
    /**
     * Default compile RAM budget: 128 MiB.
     */
    public static final long DEFAULT_COMPILE_MEMORY = 128L << 20;
    /**
     * Default match-time RAM budget (per pattern): 16 MiB.
     */
    public static final long DEFAULT_RUNTIME_MEMORY = 16L << 20;
    /**
     * Default compile CPU budget: {@link BudgetWeights#DEFAULT_COMPILE_COMPUTE_SECONDS}
     * seconds at the assumed tick rate.
     */
    public static final long DEFAULT_COMPILE_COMPUTE =
        BudgetWeights.DEFAULT_COMPILE_COMPUTE_SECONDS * BudgetWeights.TICKS_PER_SECOND;
    private Budgets() {
    }

    // ===== raw budgets =====

    /**
     * Compile RAM budget, bytes ({@value #COMPILE_MEMORY_PROP}).
     */
    public static long compileMemoryBytes() {
        return Long.getLong(COMPILE_MEMORY_PROP, DEFAULT_COMPILE_MEMORY);
    }

    /**
     * Compile CPU budget, ticks ({@value #COMPILE_COMPUTE_PROP}).
     */
    public static long compileComputeTicks() {
        return Long.getLong(COMPILE_COMPUTE_PROP, DEFAULT_COMPILE_COMPUTE);
    }

    /**
     * Match-time RAM budget per pattern, bytes ({@value #RUNTIME_MEMORY_PROP}).
     */
    public static long runtimeMemoryBytes() {
        return Long.getLong(RUNTIME_MEMORY_PROP, DEFAULT_RUNTIME_MEMORY);
    }

    // ===== compile RAM-derived caps =====

    /**
     * Cap on determinized DFA states: RAM budget / assumed bytes per state.
     */
    public static int maxDfaStates() {
        return maxDfaStates(0);
    }

    /**
     * Cap on determinized DFA states with a per-state surcharge the caller
     * knows applies (Perl mode adds the position-aware stop table,
     * {@link BudgetWeights#STOP_TABLE_STATE_BYTES} per state — a dense
     * {@code int[n*64]} allocation the base weight never included).
     */
    public static int maxDfaStates(int extraPerStateBytes) {
        return clampInt(compileMemoryBytes()
            / (BudgetWeights.DFA_STATE_BYTES + extraPerStateBytes));
    }

    /**
     * Cap on total determinization kernel configs (re2c's kernels_total):
     * RAM budget / assumed bytes per config.
     */
    public static long maxKernelConfigs() {
        return compileMemoryBytes() / BudgetWeights.KERNEL_CONFIG_BYTES;
    }

    /**
     * Per-kernel &epsilon;-closure spike (checked while a closure is built,
     * before any total can count it): 1/{@link BudgetWeights#CLOSURE_SPIKE_DIVISOR}
     * of the RAM budget in configs.
     */
    public static int maxClosureConfigs() {
        return clampInt(compileMemoryBytes()
            / (BudgetWeights.CLOSURE_SPIKE_DIVISOR * BudgetWeights.KERNEL_CONFIG_BYTES));
    }

    /**
     * Cap on materialized CFG successor arcs: RAM budget / assumed bytes per arc.
     */
    public static long maxCfgEdges() {
        return compileMemoryBytes() / BudgetWeights.CFG_EDGE_BYTES;
    }

    /**
     * Cap on minimizer range-normalization cells (compile-time scratch):
     * RAM budget / bytes per cell. Over it, minimization degrades to the
     * unnormalized (correct, less-merging) path.
     */
    public static long maxMinimizeNormCells() {
        return compileMemoryBytes() / BudgetWeights.MINIMIZE_CELL_BYTES;
    }

    // ===== runtime RAM-derived caps (lazy per-pattern memos) =====

    /**
     * Memoized search-DFA row cap for a runner whose live-set bitsets are
     * {@code stateWords} ints wide: half the runtime RAM budget in
     * weighted rows at the default budget (the row share of the eighths
     * partition — rows 4/8, {@link #sdfaMaxBlocks(int)} 3/8, {@link
     * #walkMaxBytes(int)} 1/8).
     */
    public static int sdfaMaxRows(int stateWords) {
        return sdfaMaxRows(stateWords, runtimeMemoryBytes());
    }

    /**
     * Budget-parameterized variant of {@link #sdfaMaxRows(int)}: the
     * facade hands a pattern's second engine half the budget (per-pattern
     * runtime split, see the class doc).
     */
    public static int sdfaMaxRows(int stateWords, long budgetBytes) {
        long rowBytes = BudgetWeights.RUNTIME_ROW_FIXED_BYTES
            + (long) stateWords * BudgetWeights.RUNTIME_ROW_STATE_BYTES;
        long rows = (budgetBytes / 2) / rowBytes;
        return (int) Math.max(BudgetWeights.RUNTIME_MIN_ROWS, Math.min(rows, Integer.MAX_VALUE));
    }

    /**
     * Memoized search-DFA block cap: three eighths of the runtime RAM
     * budget in weighted 512-codepoint blocks (see the partition note in
     * the class doc).
     */
    public static int sdfaMaxBlocks() {
        return sdfaMaxBlocks(runtimeMemoryBytes());
    }

    /**
     * Budget-parameterized variant of {@link #sdfaMaxBlocks()} (see
     * {@link #sdfaMaxRows(int, long)}).
     */
    public static int sdfaMaxBlocks(long budgetBytes) {
        long blocks = (budgetBytes * 3 / 8) / BudgetWeights.RUNTIME_BLOCK_BYTES;
        return (int) Math.max(BudgetWeights.RUNTIME_MIN_BLOCKS, Math.min(blocks, Integer.MAX_VALUE));
    }

    /**
     * The walk-block memo's share of the runtime RAM budget: one eighth
     * (see the partition note in the class doc).
     */
    public static long walkMaxBytes() {
        return walkMaxBytes(runtimeMemoryBytes());
    }

    /**
     * Budget-parameterized variant of {@link #walkMaxBytes()} (see
     * {@link #sdfaMaxRows(int, long)}); floored so the minimum block set
     * always fits.
     */
    public static long walkMaxBytes(long budgetBytes) {
        return Math.max((long) BudgetWeights.WALK_MIN_BLOCKS * BudgetWeights.WALK_BLOCK_BYTES,
            budgetBytes / BudgetWeights.RUNTIME_WALK_DIVISOR);
    }

    private static int clampInt(long v) {
        return (int) Math.max(1, Math.min(v, Integer.MAX_VALUE));
    }
}
