package io.github.jemmix.tdfa;

import io.github.jemmix.tdfa.core.engine.MatchResult;
import io.github.jemmix.tdfa.core.engine.MatchScratch;
import io.github.jemmix.tdfa.core.engine.WholeEngine;

/**
 * The whole-match non-engine of a {@link Pattern#FIND_ONLY} compile: no
 * whole machinery was attempted (the compile produced the plain find
 * artifact), so the whole surface refuses loudly instead of answering
 * wrong. Every whole-input entry point funnels here —
 * {@code Pattern.matches(...)} and {@code Matcher.matches()} on both engine
 * tiers (shared and generated shells call {@code wholeEngine().matchWhole}
 * by contract) — turning the disabled surface into one exception with one
 * message, at match time, never at compile time.
 *
 * <p>Per-pattern instance (not a singleton): the message names the pattern.
 * Not a {@link io.github.jemmix.tdfa.core.engine.RegexEngine}: it serves no find
 * surface and must not influence the matcher's scratch-carrier decision.
 */
final class FindOnlyWholeEngine implements WholeEngine {

    private final String pattern;

    FindOnlyWholeEngine(String pattern) {
        this.pattern = pattern;
    }

    @Override
    public MatchResult matchWhole(CharSequence input, MatchScratch scratch) {
        throw new UnsupportedOperationException("whole-input matching is disabled on this pattern (\"" + pattern
            + "\"): it was compiled with FIND_ONLY, so the whole-match machinery was not built —"
            + " recompile without FIND_ONLY (e.g. Pattern.compile) to enable matches()");
    }
}
