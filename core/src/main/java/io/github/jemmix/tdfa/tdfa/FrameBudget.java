package io.github.jemmix.tdfa.tdfa;

/**
 * Transient RAM accounting for the compile pipeline's explicit-stack
 * frames (parser group frames, fixed-tags walk frames, TNFA build
 * frames).
 *
 * <p>The compile pipeline is iterative end-to-end: no phase consumes JVM
 * stack proportional to pattern nesting (re2j's parser is iterative the
 * same way, which is why it needs no nesting cap and accepts arbitrarily
 * deep patterns). Deep nesting costs heap instead — one small frame per
 * live nesting level — and that cost is charged here <em>while the
 * frames are live</em> and released as they pop, so sequential phases
 * reuse the same allowance rather than summing.
 *
 * <p>There is deliberately no separate knob: frames are just another
 * weighted structure in the one compile RAM budget
 * ({@link Budgets#compileMemoryBytes()}, one frame = {@link
 * BudgetWeights#NESTING_FRAME_BYTES}). A nesting blowup —
 * machine-generated parenthesis soup, or the multiplicative {@code
 * {n,m}} desugaring depth inside the TNFA builder, which multiplies
 * nesting depth by up to {@code MAX_REPEAT_COUNT} per level — fails with
 * the standard "pattern too large" budget error (the class the fuzzer
 * and {@code CompileBudgetTest} treat uniformly), never an {@link
 * OutOfMemoryError} or {@link StackOverflowError}.
 *
 * <p>Reads the budget once per pipeline phase (every read fresh, the
 * knob policy of {@link Budgets}). Not thread-safe: compilation is
 * single-threaded.
 */
public final class FrameBudget {
    private final long budget;
    private long liveBytes;

    FrameBudget(long budget) {
        this.budget = budget;
    }

    public static FrameBudget create() {
        return new FrameBudget(Budgets.compileMemoryBytes());
    }

    /**
     * Charge one live frame; over budget throws the standard budget error.
     */
    public void push() {
        if ((liveBytes += BudgetWeights.NESTING_FRAME_BYTES) > budget) {
            throw new IllegalStateException("pattern too large: nesting exceeds compile memory budget ("
                    + liveBytes / BudgetWeights.NESTING_FRAME_BYTES + " live frames, "
                    + liveBytes + " weighted bytes — raise -D" + Budgets.COMPILE_MEMORY_PROP + ")");
        }
    }

    /**
     * Release one frame that is no longer live.
     */
    public void pop() {
        liveBytes -= BudgetWeights.NESTING_FRAME_BYTES;
    }
}
