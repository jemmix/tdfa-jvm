package io.github.jemmix.tdfa;

/**
 * The stateful matcher a {@link Pattern} produces: find-iteration, groups,
 * replacement. Extends the core-tier {@link io.github.jemmix.tdfa.core.Matcher}
 * (which carries the full mirror surface and the generated-shell contract)
 * with the pattern back-reference {@link #pattern()}.
 *
 * <p><b>Thread safety:</b> NOT thread-safe — a matcher carries match
 * iteration state (current match, append position); create one per thread
 * (or {@link #reset()} between sequential uses). The input
 * {@link CharSequence} must not be mutated while the matcher is in use:
 * {@code length()} is cached at construction and indices are re-read during
 * matching, so concurrent mutation produces unspecified results.
 *
 * <p>Public because generated per-pattern shells (defined in a child
 * classloader) extend this class and their matchers call its constructor.
 */
public class PatternMatcher extends io.github.jemmix.tdfa.core.Matcher {

    private final Pattern pattern;

    public PatternMatcher(TDFAPattern pattern, CharSequence input) {
        super(pattern.engine(), pattern::wholeEngine, input);
        this.pattern = pattern;
    }

    /** Returns this matcher's {@link Pattern}. */
    public Pattern pattern() { return pattern; }
}
