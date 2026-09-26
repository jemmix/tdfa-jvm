package io.github.jemmix.tdfa.core;

/**
 * The facade's whole-match seam: an engine that answers exact whole-input
 * matches with captures via a cut-free walk to end-of-input — an accept
 * config alive exactly at EOF is a full match, so {@code (a|ab)} whole-
 * matches {@code "ab"} even though leftmost-first find stops after
 * {@code "a"}.
 *
 * <p>Implemented by {@code TdfaRunner} and the ASM-generated engine
 * classes; {@code core.Matcher}'s {@code matches()} dispatches through it.
 * Deliberately NOT part of {@link RegexEngine}: third-party engines carry
 * no whole-match contract, and a compile's whole engine is always one of
 * the native ones (a runner over the cut-free artifact, or the find engine
 * itself when its pike cut never deleted a continuation — the artifact
 * is then identical to the cut-free build and the walk is exact on it).
 *
 * <p>The {@link MatchScratch} carrier follows the {@link RegexEngine#match
 * match} reuse contract: {@code null} allocates on demand, a caller-owned
 * carrier pools buffers across calls, never semantic.
 */
public interface WholeEngine {

    /**
     * Match the ENTIRE input, returning capture registers, or {@code null}
     * if the input is not a whole match. Unlike a find from 0 a mid-input
     * accept never satisfies this — the walk runs to end-of-input and only
     * an accept alive exactly at EOF counts.
     */
    MatchResult matchWhole(CharSequence input, MatchScratch scratch);
}
