package io.github.jemmix.tdfa.tdfa;

/**
 * Compile-pipeline work budget: a step counter threaded through every
 * unbounded/fixpoint loop in the pipeline (determinization closure, tryMap,
 * regopt liveness/propagation, §3.2 fallback accumulation). The state/kernel
 * caps bound the OUTPUT; this bounds the WORK — exponential closure churn
 * that never materializes states (nested-quantifier bombs found by the
 * fuzzer) loops forever under output-only caps.
 *
 * <p>Exhaustion fails compilation with the same clean "pattern too large"
 * {@link IllegalStateException} the state cap uses. Default budget is
 * generous ({@code 1<<32} ticks — comfortably above the legit under-cap
 * maximum measured, the 234 K-state context bomb at ~44 M kernels);
 * override with {@code -Dtdfa.max.work}. Not thread-safe: compilation is
 * single-threaded.
 */
public final class WorkMeter {
    private final long budget;
    private long spent;

    public WorkMeter(long budget) { this.budget = budget; }

    /** Count one unit of work; throw when the budget is exhausted. */
    public void tick() {
        if (++spent > budget) {
            throw new IllegalStateException("pattern too large: TDFA compile work budget exceeded ("
                    + budget + " ticks — raise -Dtdfa.max.work)");
        }
    }

    public long spent() { return spent; }
}
