package io.github.jemmix.tdfa.core.budget;

/**
 * Match-time work budget exhaustion: one match operation
 * ({@code find()} / {@code matches()} / {@code lookingAt()}) ran past the
 * wall bound ({@value Budgets#MATCH_TIME_PROP}). The walks are linear per
 * start, but the search ladder's known degenerate shapes (the
 * unit-semantics restart quadratic on {@code .*}-anchor patterns, the
 * mask-heavy per-step costs) spend unbounded wall on medium inputs — this
 * failure bounds the damage instead of spinning; the match-time twin of
 * the compile-side {@link PatternTooLargeException}.
 *
 * <p>Raise {@value Budgets#MATCH_TIME_PROP} to allow more, or set it
 * {@code <= 0} to disable the bound entirely (unbounded work returns).
 * Not deterministic by design: a damage bound, not a semantic contract.
 */
public final class MatchBudgetExceededException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    /**
     * @param millis the operation's wall bound, in milliseconds
     * @param length the input length
     */
    public MatchBudgetExceededException(long millis, int length) {
        super("match work budget exceeded: the search ladder ran past " + millis + " ms on a " + length
            + "-char input (a degenerate ladder shape — raise -D" + Budgets.MATCH_TIME_PROP
            + ", or report the pattern)");
    }
}
