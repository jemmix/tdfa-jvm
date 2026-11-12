/**
 * The dialect vocabulary: the data a compile's interpretation policy is
 * made of — character tables ({@code ClassTable}), terminator sets
 * ({@code Terminators}), escape alphabets ({@code EscapeAlphabet}) and
 * the inline-flag letter bindings ({@code FlagAction}/{@code
 * FlagBindings}). A leaf: references nothing internal, referenced by
 * parser, tnfa, dfa and compile — every tier sees the vocabulary, none
 * of it is tier-local.
 *
 * <p>The vocabulary is lineage-free: members name the behavior or
 * table they select, and stock presets may carry a lineage's name only
 * as data (a preset is a selectable value, never a branch). See
 * {@code docs/dialect-agnostic-core.md}.
 */
package io.github.jemmix.tdfa.core.dialect;
