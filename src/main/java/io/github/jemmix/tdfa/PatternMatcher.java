package io.github.jemmix.tdfa;

/**
 * The stateful matcher a {@link Pattern} produces: find-iteration, groups,
 * replacement. Extends the core-tier {@link io.github.jemmix.tdfa.core.Matcher}
 * (which carries the full mirror surface and the generated-shell contract)
 * with the pattern back-reference {@link #pattern()}.
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
