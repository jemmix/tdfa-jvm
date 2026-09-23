package io.github.jemmix.tdfa.tdfa;

/**
 * Hardcoded weight model backing the resource budgets ({@link Budgets}):
 * assumed costs of engine structures and actions, used to translate the
 * user-facing budgets (bytes of RAM; CPU expressed in abstract
 * <em>ticks</em>) into the internal caps the pipeline checks.
 *
 * <p>Every constant here is an <b>assumption</b>, not a measurement the
 * runtime performs: e.g. a TNFA epsilon edge "weighs" 64 bytes, a TNFA
 * builder action "takes" 5 ticks, a determinization kernel config weighs
 * 80 bytes (the one figure that IS measured — see
 * {@code TdfaCompiler.maxKernelsTotal}'s history). They are deliberately
 * hardcoded constants until a configuration story exists; consumers reason
 * about the <b>budgets</b> ({@code tdfa.budget.*}), not these numbers.
 *
 * <p><b>What a tick is.</b> One tick = one unit of abstract single-threaded
 * compile work (one closure push, one signature probe, one CFG arc). Ticks
 * are machine-<b>independent</b> and deterministic — the same pattern burns
 * the same ticks everywhere — which is why the CPU budget is expressed in
 * ticks rather than seconds. The translation to wall time uses
 * {@link #TICKS_PER_SECOND}: an assumed average rate, calibrated so that a
 * tick is a few nanoseconds of modern-core work. The default compile CPU
 * budget is {@link #DEFAULT_COMPILE_COMPUTE_SECONDS} seconds &times; that
 * rate; "on this machine" precision is exactly what the weight model
 * gives up for determinism.
 *
 * <p>Memory weights model the <b>transient compile-time footprint</b> of a
 * structure (boxed forms, builder scratch, hash-consing), not just its
 * retained flat-array form — the budget bounds the peak, and the peak is
 * mid-compile, not post-compile.
 */
public final class BudgetWeights {

    /**
     * Assumed tick rate: one tick &asymp; 10 ns of single-core work on a
     * contemporary core. Used ONLY to translate the default CPU budget
     * ("5 seconds") into ticks; tick counts themselves are deterministic
     * and machine-independent.
     */
    public static final long TICKS_PER_SECOND = 100_000_000L;

    // ===== compute weights (ticks) =====
    /**
     * Default compile CPU budget, expressed in seconds before weighting.
     */
    public static final long DEFAULT_COMPILE_COMPUTE_SECONDS = 5;
    /**
     * One TNFA-builder action (state mint, epsilon edge, symbol edge) is
     * assumed to take this many ticks. Keeps the pre-determinization
     * surface CPU-bounded: nested counted repeats multiply builder actions
     * geometrically (review r10 P0-1: {@code ((a{300}){300}){300}} used to
     * OOM the JVM before any budget fired).
     */
    public static final int TNFA_BUILD_ACTION_TICKS = 5;
    /**
     * One codepoint scanned by the parser's O(universe) case-fold range
     * expansion ({@code (?i)} classes) is assumed to take this many ticks
     * (review r10 P1-1: the scan was unmetered linear work inside
     * {@code Pattern.compile}).
     */
    public static final int FOLD_SCAN_CODEPOINT_TICKS = 2;
    /**
     * A minted TNFA state is assumed to weigh this much (per-state slices
     * of the builder's adjacency arrays and the determinizer's masks).
     */
    public static final int TNFA_STATE_BYTES = 32;

    // ===== memory weights (bytes) =====
    /**
     * A TNFA epsilon edge is assumed to weigh 64 bytes (boxed int[5] in
     * the builder's edge list, plus list overhead).
     */
    public static final int TNFA_EPS_EDGE_BYTES = 64;
    /**
     * A TNFA symbol edge is assumed to weigh 64 bytes (boxed int[2], list
     * slot, and the per-copy CharClass reference).
     */
    public static final int TNFA_SYM_EDGE_BYTES = 64;
    /**
     * A determinization kernel config weighs 80 bytes boxed — the measured
     * all-in figure (lists, intern table, builders; 6.4 M kernels peaked
     * under 1 GB) — <em>plus</em> {@link #KERNEL_REG_TAG_BYTES} per tag:
     * every Config carries an {@code int[tags]} register slice (cloned per
     * transition op allocation), so the per-config footprint scales with
     * the capture count and the flat 80 B only holds for near-tagless
     * patterns.
     */
    public static final int KERNEL_CONFIG_BYTES = 80;
    /**
     * Variable part of one kernel config, per tag (the regs int share).
     */
    public static final int KERNEL_REG_TAG_BYTES = 4;
    /**
     * A determinized DFA state is assumed to weigh 256 bytes all-in
     * (flat-table share plus its builder/sig scratch during compile) —
     * chain DFAs run lighter, wide class DFAs heavier; the average is the
     * assumption. Perl-mode compiles additionally materialize the
     * position-aware stop table ({@code int[n*64]}) — {@link
     * #STOP_TABLE_STATE_BYTES} per state, charged on top through {@link
     * Budgets#maxDfaStates(int)}.
     */
    public static final int DFA_STATE_BYTES = 256;
    /**
     * The Perl-mode position-aware stop-on-accept table, per DFA state
     * (64 int cells): charged in addition to {@link #DFA_STATE_BYTES}
     * because it is a distinct dense allocation outside the 256 B
     * average. POSIX (longest) compiles never allocate it.
     */
    public static final int STOP_TABLE_STATE_BYTES = 256;
    /**
     * One live boxed {@code Range} in a DfaStateBuilder during
     * determinization (object header, five fields, list slot): charged
     * per NEW live range — addRange coalesces adjacent same-target
     * appends inline, so the live count tracks the post-coalesce total,
     * not the per-cell emission count.
     */
    public static final int RANGE_BOXED_BYTES = 48;
    /**
     * One breakpoint cell of the determinizer's active-edge precompute:
     * the {@code long[words]} bitset share is counted separately at 8 B
     * per word; this is the per-cell auxiliary share (same-as-previous
     * flag, interned set id, array slack).
     */
    public static final int ACTIVE_CELL_AUX_BYTES = 8;
    /**
     * Fixed overhead of one interned tag-history sequence (array header,
     * hash-chain slot, contents-table share); content ints are charged at
     * 4 B each on top, as are the per-id derived caches (bits row: 8 B per
     * word; lastSign row: 4 B per tag).
     */
    public static final int HIST_FIXED_BYTES = 40;
    /**
     * A materialized CFG successor arc is assumed to weigh 32 bytes
     * (adjacency slot plus intern overhead in the transitive-closure
     * materialization).
     */
    public static final int CFG_EDGE_BYTES = 32;
    /**
     * A minimizer range-normalization cell (one int in the n&times;K row
     * table) weighs 4 bytes.
     */
    public static final int MINIMIZE_CELL_BYTES = 4;
    /**
     * One live explicit-stack frame (parser group frame, fixed-tags walk
     * frame, or TNFA build frame) is assumed to weigh 64 bytes. The
     * compile pipeline is iterative end-to-end, so nesting depth costs
     * heap, not JVM stack; the frame stacks are charged against the
     * compile RAM budget <em>while the frames are live</em> (released on
     * pop), through this weight. This is what bounds parenthesis soup
     * and the multiplicative {@code {n,m}} desugaring depth in the TNFA
     * builder — depth-driven shapes that per-structure caps alone cannot
     * see, because their totals stay small while their depth explodes.
     */
    public static final int NESTING_FRAME_BYTES = 64;
    /**
     * The per-kernel &epsilon;-closure spike (one closure, before any
     * totals cap can count it) is bounded to 1/16 of the compile RAM
     * budget. At the 128 MiB default: 8 MiB / 80 B = ~100 K configs — the
     * historical standalone cap.
     */
    public static final int CLOSURE_SPIKE_DIVISOR = 16;
    /**
     * Fixed part of one memoized search-DFA row: the 128 block-id cells
     * (512 B), the interning map entry, and snapshot-copy amortization.
     * The variable part (live-set words, cloned for interning) is
     * {@link #RUNTIME_ROW_STATE_BYTES} per DFA state.
     */
    public static final int RUNTIME_ROW_FIXED_BYTES = 640;

    // ===== runtime memory weights (bytes) =====
    /**
     * Variable part of one search-DFA row, per DFA state (bitset word +
     * its clone in the intern key).
     */
    public static final int RUNTIME_ROW_STATE_BYTES = 8;
    /**
     * One memoized 512-codepoint transition block (int[512] + interning
     * overhead) is assumed to weigh 2176 bytes.
     */
    public static final int RUNTIME_BLOCK_BYTES = 2176;
    /**
     * Floor caps so a tiny runtime budget still yields a usable memo.
     */
    public static final int RUNTIME_MIN_ROWS = 16;
    /**
     * Floor cap for search-DFA blocks, so a tiny runtime budget still
     * yields a usable memo.
     */
    public static final int RUNTIME_MIN_BLOCKS = 16;
    /**
     * The runtime RAM budget is partitioned across the per-pattern lazy
     * match-time memos in eighths: search-DFA rows 4/8, search-DFA
     * transition blocks 3/8, and the walk-block memo (wide-codepoint
     * dispatch, {@code WalkIndex}) 1/8. Every lazy structure a runner
     * retains draws from one of the three shares.
     */
    public static final int RUNTIME_WALK_DIVISOR = 8;

    // ===== runtime memo partition =====
    /**
     * One per-state walk-block id table ({@code int[128]} + the
     * AtomicReferenceArray slot), the walk memo's per-state share.
     */
    public static final int WALK_STATE_TABLE_BYTES = 516;
    /**
     * One memoized 512-codepoint walk block (int[512] + overhead) — the
     * same shape as {@link #RUNTIME_BLOCK_BYTES}.
     */
    public static final int WALK_BLOCK_BYTES = 2176;
    /**
     * Floor for walk blocks so a tiny runtime budget keeps the memo
     * usable.
     */
    public static final int WALK_MIN_BLOCKS = 64;

    private BudgetWeights() {}
}
