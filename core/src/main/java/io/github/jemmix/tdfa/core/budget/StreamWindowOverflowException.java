package io.github.jemmix.tdfa.core.budget;

/**
 * The streaming window budget tripped: the matcher's retained input
 * window — everything from the leftmost undecided match start to the
 * frontier — exceeded {@value Budgets#STREAM_WINDOW_PROP} UTF-16 units.
 * This is the runtime-side sibling of the "pattern too large" family: a
 * typed, loud rejection instead of a silent spill or a truncated answer.
 *
 * <p>It means the pattern's undecided search spans more input than the
 * budget allows — a live-tail shape ({@code .*x} with {@code x} never
 * arriving) or a distant match over a huge frontier. Raise
 * {@code -Dtdfa.budget.stream.window} (UTF-16 units; default
 * {@link Budgets#DEFAULT_STREAM_WINDOW} units = 16 MiB) if you
 * legitimately need more, or consume the stream with a tighter pattern.
 * See {@code docs/streaming-input.md} for the retention-floor analysis.
 */
public final class StreamWindowOverflowException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    /**
     * @param needed units the window must retain
     * @param budget the tripped budget, in the same units
     */
    public StreamWindowOverflowException(long needed, long budget) {
        super("stream window exceeded: the undecided search needs " + needed + " UTF-16 units, budget is " + budget
            + " — raise -D" + Budgets.STREAM_WINDOW_PROP + " if you legitimately need more"
            + " (retention floor = leftmost undecided match start; see docs/streaming-input.md)");
    }
}
