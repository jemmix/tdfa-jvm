package io.github.jemmix.tdfa.core.compile;

import io.github.jemmix.tdfa.core.emit.EmittedSurface;
import io.github.jemmix.tdfa.core.engine.MatchResult;
import io.github.jemmix.tdfa.core.engine.Matcher;

import java.util.Arrays;

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

    /**
     * Set when a GENERATED shell retargets: its inlined fast paths bake
     * the compile-time artifact, so every operation routes through the
     * machinery implementations from then on (the emitted retarget
     * bail — the region's never-taken-branch posture).
     */
    @EmittedSurface // emitted shells read the field (the retarget bail)
    protected boolean retargeted;

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
     * information of the last match is lost (the JDK's empty match view
     * until the next match; the JDK behaves identically).
     *
     * <p>Both tiers serve the full contract. A generated (ASM-tier)
     * matcher's inlined fast paths bake the artifact it was minted
     * with, so a retarget rebinds the machinery engines and the shell
     * serves through the machinery implementations from then on — one
     * predictable never-taken branch on the hot paths, the region's
     * posture. Every facade {@code Pattern} IS a {@link TDFAPattern}
     * at runtime (the minted shells extend it), so the shared
     * machinery's one spelling takes the machinery type.
     */
    public PatternMatcher usePattern(TDFAPattern newPattern) {
        if (newPattern == null) {
            throw new IllegalArgumentException("pattern cannot be null");
        }
        if (getClass() != PatternMatcher.class) {
            // Generated shell: its baked fast paths would keep serving
            // the old artifact — route everything through the machinery
            // implementations (the emitted retarget bail).
            retargeted = true;
        }
        this.pattern = newPattern;
        rebindEngines(newPattern.engine(), newPattern.wholeEngine());
        // The JDK's exact protocol. Java clears groups[] to -1 but keeps
        // `first` when a match had succeeded: group access serves the
        // EMPTY match view (start()/end() -1, group() null, hasMatch()
        // true) until the next match, and the next find() continues from
        // the last match's end exactly like the live-match continuation
        // (the empty-match bump included). With no prior success (java's
        // `first < 0`) group access keeps throwing — the never-attempted
        // state, which a failed search also restores.
        if (hasMatch) {
            int gc = newPattern.engine().groupCount();
            int[] regs = new int[2 * (gc + 1)];
            Arrays.fill(regs, -1);
            match = new MatchResult(regs, 0, gc, -1, -1);
        }
        return this;
    }
}
