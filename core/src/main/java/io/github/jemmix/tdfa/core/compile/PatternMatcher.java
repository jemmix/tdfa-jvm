package io.github.jemmix.tdfa.core.compile;

import io.github.jemmix.tdfa.core.emit.EmittedSurface;
import io.github.jemmix.tdfa.core.engine.Matcher;

/**
 * The stateful matcher every surface's {@code Pattern} produces:
 * find-iteration, groups, replacement. Extends the core-tier
 * {@link Matcher} (which carries the full mirror surface and the
 * generated-shell contract) with the pattern back-reference
 * {@link #pattern()}.
 *
 * <p><b>Thread safety:</b> NOT thread-safe — a matcher carries match
 * iteration state (current match, append position); create one per thread
 * (or {@code reset()} between sequential uses). The input
 * {@link CharSequence} must not be mutated while the matcher is in use:
 * {@code length()} is cached at construction and indices are re-read during
 * matching, so concurrent mutation produces unspecified results.
 *
 * <p>Public because generated per-pattern shells (defined in a child
 * classloader) extend this class and their matchers call its constructor.
 *
 * <p>Public-but-internal: machinery tier ({@code core.compile}), shared
 * by both surfaces — the surfaces' {@code Pattern} interfaces are the
 * shipped API.
 */
public class PatternMatcher extends Matcher {

    /** Mutable ONLY through {@link #usePattern} (machinery-tier matchers). */
    private TDFAPattern pattern;

    @EmittedSurface // emitted shells super-ctor call
    public PatternMatcher(TDFAPattern pattern, CharSequence input) {
        super(pattern.engine(), pattern.wholeEngine(), input);
        this.pattern = pattern;
    }

    /**
     * Returns this matcher's {@link TDFAPattern} (the machinery-level
     * pattern view — the compiled engines, the surface, the state the
     * matchers were built from).
     */
    public TDFAPattern pattern() {
        return pattern;
    }

    /**
     * Re-target this matcher at {@code newPattern} — the
     * {@code java.util.regex.Matcher.usePattern} contract: the input,
     * the search position and the append position are kept; the group
     * information of the last match is lost (group access fails until
     * the next match; the JDK behaves identically).
     *
     * <p><b>Tier constraint.</b> Only the machinery matcher tier can
     * re-target — the ASM tier's generated matchers bake their engine's
     * concrete type into the class, and those refuse with
     * {@link UnsupportedOperationException} (build a fresh matcher via
     * {@code newPattern.matcher(...)} — or run {@code -Dtdfa.engine=VM}
     * — when re-targeting is the workflow). Every facade
     * {@code Pattern} IS a {@link TDFAPattern} at runtime (the minted
     * shells extend it), so the shared machinery's one spelling takes
     * the machinery type.
     */
    public PatternMatcher usePattern(TDFAPattern newPattern) {
        if (newPattern == null) {
            throw new IllegalArgumentException("pattern cannot be null");
        }
        if (getClass() != PatternMatcher.class) {
            throw new UnsupportedOperationException("usePattern on a generated per-pattern matcher: the engine tier "
                + "is baked into this matcher's class — build a fresh matcher (newPattern.matcher(...)) instead, or "
                + "run -Dtdfa.engine=VM");
        }
        this.pattern = newPattern;
        rebindEngines(newPattern.engine(), newPattern.wholeEngine());
        match = null;
        hasMatch = false;
        // Position continuation: java's next find() starts at the last
        // match's end (bumped past an empty match) — the un-regioned find()
        // resumes from appendPos once the match state is cleared, so the
        // continuation position lands there (bake the bump now; find()'s
        // own bump only fires while a match is live).
        if (lastMatchStart >= 0) {
            appendPos = lastMatchStart == lastMatchEnd ? lastMatchEnd + 1 : lastMatchEnd;
        }
        return this;
    }
}
