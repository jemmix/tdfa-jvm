package io.github.jemmix.tdfa.core.budget;

/**
 * The user-facing resource budgets, as four system properties, and the
 * derived caps the pipeline actually checks. Compilation is bounded by a
 * <b>RAM budget</b> (bytes of transient compile-time structures, weighted
 * per structure via {@link BudgetWeights}) and a <b>CPU budget</b>
 * (abstract ticks of compile work, metered by {@link WorkMeter}); the
 * compiled pattern's retained artifact tables are bounded at
 * end-of-compile by a <b>RAM budget</b> — no
 * backtracking engine exists to burn CPU exponentially, and the search
 * ladder's degenerate shapes were root-fixed (see the 2026-10-16
 * campaign doc: the abs-begin ladder bound, the unit-semantics trigger,
 * the mask-aware simulation) rather than walled off behind a wall-clock
 * exception.
 *
 * <table border=1>
 *   <tr><th>Property</th><th>Meaning</th><th>Default</th></tr>
 *   <tr><td>{@code tdfa.budget.compile.memory}</td><td>compile RAM, bytes</td>
 *       <td>128 MiB</td></tr>
 *   <tr><td>{@code tdfa.budget.compile.compute}</td><td>compile CPU, ticks
 *       (one tick &asymp; 10 ns; see {@link BudgetWeights#TICKS_PER_SECOND})</td>
 *       <td>5 s &times; tick rate = 500 M ticks</td></tr>
 *   <tr><td>{@code tdfa.budget.runtime.memory}</td><td>execution RAM per
 *       compiled pattern: retained artifact tables, bytes</td>
 *       <td>16 MiB</td></tr>
 *   <tr><td>{@code tdfa.budget.stream.window}</td><td>streaming input:
 *       retained window (leftmost undecided start &rarr; frontier),
 *       UTF-16 units — see {@code docs/streaming-input.md}</td>
 *       <td>8 M units (16 MiB)</td></tr>
 * </table>
 *
 * <p>The derived caps (max DFA states, kernel totals, &epsilon;-closure
 * spike, active-set precompute, boxed transition ranges, CFG edges,
 * minimizer normalization cells, tag-history tables) are all linear
 * functions of the two compile budgets / the runtime budget through the
 * weight model (BudgetWeights) — one knob per resource, so the caps can
 * never drift out of sync with the budgets that derive them. Raise the
 * budget, not the cap.
 *
 * <p><b>Ledger.</b> The compile CPU budget bounds one whole compile: the
 * attempts (front-end, find determinization, and — when the pike cut bit
 * — the cut-free whole determinization) each draw from one shared ledger
 * ({@link WorkMeter#fork(long)}), so a single compile can never burn more
 * than the budget.
 *
 * <p><b>Per-pattern runtime split.</b> The runtime RAM budget bounds the
 * whole per-pattern execution footprint: the RETAINED artifact tables
 * (charged at {@link io.github.jemmix.tdfa.core.dfa.Tdfa#retainedTableBytes()} — an end-of-compile
 * check fails the compile when they alone exceed the budget, the
 * standard clean "pattern too large" rejection pointing at {@value
 * #RUNTIME_MEMORY_PROP}; the partial-whole side table counts as retained
 * bytes of the one artifact). (The lazy match-time memos that once drew
 * from the residual after the retained tables are deleted with #97/#98;
 * the "half each" per-engine split this paragraph long documented never
 * existed — the facade passes one identical allowance to every engine
 * and the runner ignores it — see C39 in the complexity review.)
 * Engine-tier construction-time tables (the
 * eager ascii/latin dispatch tiers, generated-class statics, derived
 * bitsets) remain disclosed constants outside the budget — the r11
 * scope decision: flooring them on it would flip the flagship
 * dictionary shapes off their fast paths. BYO-factory engines retain
 * unaccountable state by construction (documented at the facade); the
 * artifact tables and any native fallback runner still draw from the
 * same budget.
 *
 * <p>Knob policy (see the inventory note in {@link io.github.jemmix.tdfa.core.dfa.Tdfa}): every read is
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
     * System property: streaming-input window budget in UTF-16 units —
     * the retained input a streaming matcher may hold from the leftmost
     * undecided match start to the frontier
     * (see {@code docs/streaming-input.md}); exceeding it fails the
     * stream with {@link StreamWindowOverflowException}.
     */
    public static final String STREAM_WINDOW_PROP = "tdfa.budget.stream.window";
    /**
     * Default compile RAM budget: 128 MiB.
     */
    public static final long DEFAULT_COMPILE_MEMORY = 128L << 20;
    /**
     * Default match-time RAM budget (per pattern): 16 MiB.
     */
    public static final long DEFAULT_RUNTIME_MEMORY = 16L << 20;
    /**
     * Default streaming window budget: 8 M UTF-16 units = 16 MiB, the
     * same RAM as the match-time budget's default.
     */
    public static final long DEFAULT_STREAM_WINDOW = 8L << 20;
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

    /**
     * Streaming window budget, UTF-16 units ({@value #STREAM_WINDOW_PROP}).
     * Read fresh per streaming matcher — the knob policy above.
     */
    public static long streamWindowUnits() {
        return Long.getLong(STREAM_WINDOW_PROP, DEFAULT_STREAM_WINDOW);
    }

    // ===== compile RAM-derived caps =====

    /**
     * Cap on determinized DFA states with a per-state surcharge the caller
     * knows applies (Perl mode adds the position-aware stop table,
     * {@link BudgetWeights#STOP_TABLE_STATE_BYTES} per state — a dense
     * {@code int[n*64]} allocation the base weight never included).
     */
    public static int maxDfaStates(int extraPerStateBytes) {
        return clampInt(compileMemoryBytes() / (BudgetWeights.DFA_STATE_BYTES + extraPerStateBytes));
    }

    /**
     * Cap on total determinization kernel configs (re2c's kernels_total):
     * RAM budget / assumed bytes per config.
     */
    public static long maxKernelConfigs() {
        return compileMemoryBytes() / BudgetWeights.KERNEL_CONFIG_BYTES;
    }

    /**
     * Per-kernel &epsilon;-closure spike in WEIGHTED bytes (checked while a
     * closure is built, before any total can count it):
     * 1/{@link BudgetWeights#CLOSURE_SPIKE_DIVISOR} of the RAM budget —
     * the running closure's configs, at their tag-aware per-config weight,
     * must fit inside it.
     */
    public static long maxClosureSpikeBytes() {
        return compileMemoryBytes() / BudgetWeights.CLOSURE_SPIKE_DIVISOR;
    }

    /**
     * Cap on materialized CFG successor arcs: RAM budget / assumed bytes per arc.
     */
    public static long maxCfgEdges() {
        return compileMemoryBytes() / BudgetWeights.CFG_EDGE_BYTES;
    }

    // (maxMinimizeNormCells — the cap on minimizer range-normalization
    //  cells — was deleted with the normalization pass it bounded; see
    //  complexity-review C26. The compute-budget degrade for minimization
    //  itself remains, metered in DfaMinimizer.computePartition.)

    // ===== runtime RAM-derived caps =====
    // (The lazy-memo cap derivations that lived here — sdfaMaxRows,
    //  sdfaMaxBlocks, walkMaxBytes, the eighths partition, and the
    //  runtimeMemoAllowance residual split — were deleted with the memos
    //  they bounded; see docs/search-dfa-memo-tradeoffs.md.
    //  runtimeMemoryBytes stays as the compile-time execution-RAM bound:
    //  a compile whose retained tables alone overshoot the runtime budget
    //  still rejects.)

    private static int clampInt(long v) {
        return (int) Math.max(1, Math.min(v, Integer.MAX_VALUE));
    }
}
