package io.github.jemmix.tdfa.core.determinize;

/**
 * The determinization mode: the pruning policy plus the whole-match
 * surface, one value per real combination. The boolean triplet this
 * replaces (longestMatch / unpruned / wholeSide) could spell eight
 * configured worlds, four of which were fiction: a whole side on a
 * POSIX or unpruned compile was silently rewritten to "no side" inside
 * the compiler instead of being unrepresentable here.
 *
 * <p>POSIX needs no side and no unpruned variant of its own: the pike
 * cut is a leftmost-first concept (every continuation stays alive under
 * leftmost-longest), so whole-input walks on a POSIX artifact are exact
 * by construction and an unpruned POSIX compile would step identically
 * to the pruned one.
 */
enum CompileMode {
    /** Perl leftmost-first, pike-cut pruned — the find-only artifact. */
    PERL_PRUNED,
    /**
     * Perl leftmost-first, pruned, plus the partial-whole side table
     * (the facade's one-artifact whole-match build; see
     * {@code Determinizer#compileWithWholeSide}).
     */
    PERL_WHOLE,
    /** POSIX leftmost-longest, pruned (no pike cut ever fires). */
    POSIX,
    /**
     * Cut-free determinization for whole-input walks: stepping follows
     * every alive config, never cutting below an accept.
     */
    UNPRUNED
}
