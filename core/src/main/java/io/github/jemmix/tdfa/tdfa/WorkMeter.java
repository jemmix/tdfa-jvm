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
 * <p><b>Ledger (one budget per {@code Pattern.compile}).</b> Every meter
 * carries a shared {@link #fork(long)} ledger seeded with its budget:
 * {@link #fork(long)} derives a child meter for one ladder attempt with a
 * per-attempt cap of {@code min(cap, the ledger's remaining ticks)}
 * ({@code cap <= 0} = the whole remaining ledger, the {@code workCap}
 * convention), and every tick on any family member debits the ledger too.
 * The facade's SHIPPED work — front-end parse/TNFA, a succeeded unpruned
 * whole, the pruned find, the anchored re-parse + determinize — runs on
 * the one ledger, so a single {@code Pattern.compile}'s shipped work
 * cannot exceed the CPU budget. The eager unpruned WHOLE attempt is a
 * <em>probe</em>: it runs on its own fraction-capped meter and its spend
 * is {@link #charge(long) charged} to the ledger only on success — a
 * budget-REJECTED probe's bounded churn (at most {@link
 * Budgets#wholeWorkCap()} ticks) is the price of trying, and keeps the
 * ladder's mandatory attempts inside the one budget.
 */
public final class WorkMeter {
    private final long budget;
    private long spent;
    /** Shared cross-attempt pool; every family member holds the same instance. */
    private final Ledger ledger;

    private static final class Ledger { long remaining; }

    public WorkMeter(long budget) {
        this.budget = budget;
        this.ledger = new Ledger();
        this.ledger.remaining = budget;
    }

    private WorkMeter(long budget, Ledger ledger) { this.budget = budget; this.ledger = ledger; }

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
     * Debit {@code n} ticks from the shared ledger without ticking this
     * meter — settles a PROBE attempt's shipped spend (a probe runs on its
     * own fraction-capped meter; on success its real cost joins the
     * compile's ledger, on budget-rejection it is the documented probe
     * price and goes uncharged). A ledger driven negative fails the next
     * fork at its first tick, with the standard rejection.
     */
    public void charge(long n) {
        ledger.remaining -= n;
    }

    /** The remaining ticks under this meter's own budget (never negative). */
    public long remaining() { return Math.max(0, budget - spent); }

    public long spent() { return spent; }

    /** Count one unit of work; throw when the budget is exhausted. */
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

    /** Count {@code n} units at once (bulk loops whose trip count is known). */
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

    /** Budget exhaustion, as a distinct type so optional passes (the Moore
     *  minimizer) can catch exactly this and degrade — skip themselves —
     *  instead of failing a compile whose main artifact is fine. Same
     *  "pattern too large" message family as every other budget rejection. */
    public static final class Exhausted extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        Exhausted(String message) { super(message); }
    }
}
