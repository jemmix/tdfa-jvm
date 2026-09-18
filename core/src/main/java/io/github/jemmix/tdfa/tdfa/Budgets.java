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
 * spike, CFG edges, minimizer normalization cells, whole-ladder work
 * caps, search-DFA memo rows/blocks) are all linear functions of the two
 * compile budgets / the runtime budget through the weight model — there
 * is deliberately no way to set them directly anymore. Raise the budget,
 * not the cap.
 *
 * <p>Knob policy (see the inventory note in {@link Tdfa}): every read is
 * fresh — budgets take effect on the next compile (or the next runner
 * constructed, for the runtime budget) in the same JVM, and tests can
 * vary them without forking. Budget exhaustion fails compilation with the
 * standard clean "pattern too large" rejection.
 */
public final class Budgets {

    private Budgets() { }

    /** System property: compile RAM budget in bytes. */
    public static final String COMPILE_MEMORY_PROP = "tdfa.budget.compile.memory";
    /** System property: compile CPU budget in ticks. */
    public static final String COMPILE_COMPUTE_PROP = "tdfa.budget.compile.compute";
    /** System property: per-pattern match-time RAM budget in bytes. */
    public static final String RUNTIME_MEMORY_PROP = "tdfa.budget.runtime.memory";

    /** Default compile RAM budget: 128 MiB. */
    public static final long DEFAULT_COMPILE_MEMORY = 128L << 20;
    /** Default match-time RAM budget (per pattern): 16 MiB. */
    public static final long DEFAULT_RUNTIME_MEMORY = 16L << 20;
    /** Default compile CPU budget: {@link BudgetWeights#DEFAULT_COMPILE_COMPUTE_SECONDS}
     *  seconds at the assumed tick rate. */
    public static final long DEFAULT_COMPILE_COMPUTE =
            BudgetWeights.DEFAULT_COMPILE_COMPUTE_SECONDS * BudgetWeights.TICKS_PER_SECOND;

    // ===== raw budgets =====

    /** Compile RAM budget, bytes ({@value #COMPILE_MEMORY_PROP}). */
    public static long compileMemoryBytes() {
        return Long.getLong(COMPILE_MEMORY_PROP, DEFAULT_COMPILE_MEMORY);
    }

    /** Compile CPU budget, ticks ({@value #COMPILE_COMPUTE_PROP}). */
    public static long compileComputeTicks() {
        return Long.getLong(COMPILE_COMPUTE_PROP, DEFAULT_COMPILE_COMPUTE);
    }

    /** Match-time RAM budget per pattern, bytes ({@value #RUNTIME_MEMORY_PROP}). */
    public static long runtimeMemoryBytes() {
        return Long.getLong(RUNTIME_MEMORY_PROP, DEFAULT_RUNTIME_MEMORY);
    }

    // ===== compile RAM-derived caps =====

    /** Cap on determinized DFA states: RAM budget / assumed bytes per state. */
    public static int maxDfaStates() {
        return clampInt(compileMemoryBytes() / BudgetWeights.DFA_STATE_BYTES);
    }

    /** Cap on total determinization kernel configs (re2c's kernels_total):
     *  RAM budget / assumed bytes per config. */
    public static long maxKernelConfigs() {
        return compileMemoryBytes() / BudgetWeights.KERNEL_CONFIG_BYTES;
    }

    /** Per-closure spike cap (checked while a closure is built, before any
     *  total can count it): 1/{@link BudgetWeights#CLOSURE_SPIKE_DIVISOR}
     *  of the RAM budget in configs. */
    public static int maxClosureConfigs() {
        return clampInt(compileMemoryBytes()
                / (BudgetWeights.CLOSURE_SPIKE_DIVISOR * BudgetWeights.KERNEL_CONFIG_BYTES));
    }

    /** Cap on materialized CFG successor arcs: RAM budget / assumed bytes per arc. */
    public static long maxCfgEdges() {
        return compileMemoryBytes() / BudgetWeights.CFG_EDGE_BYTES;
    }

    /** Cap on minimizer range-normalization cells (compile-time scratch):
     *  RAM budget / bytes per cell. Over it, minimization degrades to the
     *  unnormalized (correct, less-merging) path. */
    public static long maxMinimizeNormCells() {
        return compileMemoryBytes() / BudgetWeights.MINIMIZE_CELL_BYTES;
    }

    // ===== compile CPU-derived caps =====

    /** Work cap for the eager unpruned whole-match attempt: 1/3 of the
     *  compile CPU budget. Only tightens — a user-lowered budget wins. */
    public static long wholeWorkCap() {
        return compileComputeTicks() / BudgetWeights.WHOLE_LADDER_DENOMINATOR;
    }

    /** Work cap for the eager anchored last-chance whole attempt: 2/3 of
     *  the compile CPU budget (2&times; {@link #wholeWorkCap()}). */
    public static long anchoredWorkCap() {
        return (2 * compileComputeTicks()) / BudgetWeights.WHOLE_LADDER_DENOMINATOR;
    }

    // ===== runtime RAM-derived caps (search-DFA memo) =====

    /** Memoized search-DFA row cap for a runner whose live-set bitsets are
     *  {@code stateWords} ints wide: half the runtime RAM budget in
     *  weighted rows (the other half is {@link #sdfaMaxBlocks()}). */
    public static int sdfaMaxRows(int stateWords) {
        long rowBytes = BudgetWeights.RUNTIME_ROW_FIXED_BYTES
                + (long) stateWords * BudgetWeights.RUNTIME_ROW_STATE_BYTES;
        long rows = (runtimeMemoryBytes() / 2) / rowBytes;
        return (int) Math.max(BudgetWeights.RUNTIME_MIN_ROWS, Math.min(rows, Integer.MAX_VALUE));
    }

    /** Memoized search-DFA block cap: half the runtime RAM budget in
     *  weighted 512-codepoint blocks. */
    public static int sdfaMaxBlocks() {
        long blocks = (runtimeMemoryBytes() / 2) / BudgetWeights.RUNTIME_BLOCK_BYTES;
        return (int) Math.max(BudgetWeights.RUNTIME_MIN_BLOCKS, Math.min(blocks, Integer.MAX_VALUE));
    }

    private static int clampInt(long v) {
        return (int) Math.max(1, Math.min(v, Integer.MAX_VALUE));
    }
}
