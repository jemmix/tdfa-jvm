package io.github.jemmix.tdfa.tdfa;

/**
 * Disambiguation policy for the TDFA(1) determinisation.
 *
 * <p>The TNFA construction is identical for both modes (Thompson-style with
 * priority-ordered ε-edges). The two modes differ only in how the DFA collects
 * symbol transitions out of each state's ε-closure:
 *
 * <ul>
 *   <li>{@link #POSIX} — every config in the closure contributes its symbol
 *       transitions; the runner reports the latest accept, yielding the
 *       leftmost-<em>longest</em> match (POSIX). Matches re2j's column 3.</li>
 *   <li>{@link #PERL} — once a config in the closure has reached the accept
 *       state, configs at lower priority (higher {@code pri}) are suppressed,
 *       so the DFA cannot extend the match past the first-alternative accept.
 *       Yields leftmost-<em>first</em> semantics (Perl / PCRE / re2j column 1).</li>
 * </ul>
 *
 * <p>Both modes run in O(n) at match time; the difference is compile-time only.
 */
public enum Disambiguation {
    POSIX,
    PERL,
}
