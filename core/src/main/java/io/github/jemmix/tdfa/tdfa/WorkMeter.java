package io.github.jemmix.tdfa.tdfa;

/**
 * Compile-pipeline work budget: a step counter threaded through every
 * unbounded/fixpoint/quadratic loop in the pipeline — determinization closure,
 * tryMap, regopt (CFG build, decode, liveness/propagation, DCE, interference,
 * allocation, normalization/topo-sort), the final-variant enumeration, the
 * TNFA builder, the parser's fold-range scan, the active-set/breakpoint
 * precompute, the materialization sweeps, and the minimizer fixpoint.
 * The RAM-derived caps bound the OUTPUT; this bounds the WORK — exponential
 * closure churn that never materializes states (nested-quantifier bombs found
 * by the fuzzer) loops forever under output-only caps.
 *
 * <p>Exhaustion fails compilation with the same clean "pattern too large"
 * {@link IllegalStateException} the caps use (as the typed
 * {@link Exhausted} subclass, so degradation sites — the minimizer — can
 * catch exactly budget exhaustion). The budget is
 * {@link Budgets#compileComputeTicks()} ({@code tdfa.budget.compile.compute},
 * default 5 s &times; {@link BudgetWeights#TICKS_PER_SECOND}); override with
 * that property. Not thread-safe: compilation is single-threaded.
 *
 * <p><b>Ledger (one budget per compile).</b> Every meter carries a shared
 * {@link #fork(long)} ledger seeded with its budget: {@link #fork(long)}
 * derives a child meter for one determinization attempt with a per-attempt
 * cap of {@code min(cap, the ledger's remaining ticks)} ({@code cap <= 0}
 * = the whole remaining ledger), and every tick on any family member
 * debits the ledger too. A compile's shipped work — front-end parse/TNFA,
 * the find determinization, and (when the pike cut bit) the cut-free
 * whole determinization — runs on the one ledger, so a single compile
 * cannot exceed the CPU budget.
 */
public final class WorkMeter {
    private final long budget;
    /**
     * Shared cross-attempt pool; every family member holds the same instance.
     */
    private final Ledger ledger;
    private long spent;

    public WorkMeter(long budget) {
        this.budget = budget;
        this.ledger = new Ledger();
        this.ledger.remaining = budget;
    }

    private WorkMeter(long budget, Ledger ledger) {
        this.budget = budget;
        this.ledger = ledger;
    }

    /**
     * Derive a child meter for one compile attempt: its own budget is
     * {@code min(cap, ledger-remaining ticks)} ({@code cap <= 0} = the
     * whole remaining ledger). Ticks debit both the child's cap and the
     * shared ledger; exhausting either throws the standard rejection.
     */
    public WorkMeter fork(long cap) {
        long rem = Math.max(0, ledger.remaining);
        return new WorkMeter(cap > 0 ? Math.min(cap, rem) : rem, this.ledger);
    }

    /**
     * The remaining ticks under this meter's own budget (never negative).
     */
    public long remaining() {
        return Math.max(0, budget - spent);
    }

    public long spent() {
        return spent;
    }

    /**
     * Count one unit of work; throw when the budget is exhausted.
     */
    public void tick() {
        if (++spent > budget) {
            throw new Exhausted("pattern too large: TDFA compile work budget exceeded ("
                            + spent + "/" + budget + " ticks — raise -D" + Budgets.COMPILE_COMPUTE_PROP + ")");
        }
        if (--ledger.remaining < 0) {
            throw new Exhausted("pattern too large: TDFA compile work budget exceeded (total across compile attempts — raise -D"
                            + Budgets.COMPILE_COMPUTE_PROP + ")");
        }
    }

    /**
     * Count {@code n} units at once (bulk loops whose trip count is known).
     */
    public void tick(long n) {
        if ((spent += n) > budget) {
            throw new Exhausted("pattern too large: TDFA compile work budget exceeded ("
                            + spent + "/" + budget + " ticks — raise -D" + Budgets.COMPILE_COMPUTE_PROP + ")");
        }
        if ((ledger.remaining -= n) < 0) {
            throw new Exhausted("pattern too large: TDFA compile work budget exceeded (total across compile attempts — raise -D"
                            + Budgets.COMPILE_COMPUTE_PROP + ")");
        }
    }

    private static final class Ledger {
        long remaining;
    }

    /**
     * Budget exhaustion, as a distinct type so optional passes (the Moore
     * minimizer) can catch exactly this and degrade — skip themselves —
     * instead of failing a compile whose main artifact is fine. Same
     * "pattern too large" message family as every other budget rejection.
     */
    public static final class Exhausted extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        Exhausted(String message) {
            super(message);
        }
    }
}
