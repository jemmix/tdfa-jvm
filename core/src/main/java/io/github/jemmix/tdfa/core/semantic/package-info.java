/**
 * The meaning-resolution tier: the one place a compile's interpretation
 * policy is applied to the parse output — the symbolic dot expands
 * against the compile's terminator set, and the span-protocol marks
 * (family 6, the zero-width-exit split) are placed from the
 * EMPTY_ITERATION_SPANS policy. The parser stays a syntax reader (its
 * knobs are grammar-shaped only); this tier speaks the dialect
 * vocabulary and the AST, sits between {@code parser} and {@code tnfa}
 * in the core layer DAG, and is where further meaning axes land as the
 * compat-surface ladder climbs (see
 * {@code docs/engine-compat-surface.md}).
 */
package io.github.jemmix.tdfa.core.semantic;
