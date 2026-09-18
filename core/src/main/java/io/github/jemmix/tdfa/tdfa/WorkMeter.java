package io.github.jemmix.tdfa.tdfa;

/**
 * Compile-pipeline work budget: a step counter threaded through every
 * unbounded/fixpoint/quadratic loop in the pipeline — determinization closure,
 * tryMap, regopt (CFG build, decode, liveness/propagation, DCE, interference,
 * allocation, normalization/topo-sort), the final-variant enumeration, the
 * TNFA builder, the parser's fold-range scan, and the minimizer fixpoint.
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
 */
public final class WorkMeter {
    private final long budget;
    private long spent;

    public WorkMeter(long budget) { this.budget = budget; }

    /** Count one unit of work; throw when the budget is exhausted. */
    public void tick() {
        if (++spent > budget) {
            throw new Exhausted("pattern too large: TDFA compile work budget exceeded ("
                    + spent + "/" + budget + " ticks — raise -D" + Budgets.COMPILE_COMPUTE_PROP + ")");
        }
    }

    /** Count {@code n} units at once (bulk loops whose trip count is known). */
    public void tick(long n) {
        if ((spent += n) > budget) {
            throw new Exhausted("pattern too large: TDFA compile work budget exceeded ("
                    + spent + "/" + budget + " ticks — raise -D" + Budgets.COMPILE_COMPUTE_PROP + ")");
        }
    }

    public long spent() { return spent; }

    /** Budget exhaustion, as a distinct type so optional passes (the Moore
     *  minimizer) can catch exactly this and degrade — skip themselves —
     *  instead of failing a compile whose main artifact is fine. Same
     *  "pattern too large" message family as every other budget rejection. */
    public static final class Exhausted extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        Exhausted(String message) { super(message); }
    }
}
