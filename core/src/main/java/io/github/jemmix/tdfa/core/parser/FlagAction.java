package io.github.jemmix.tdfa.core.parser;

/**
 * The closed vocabulary of behaviors an inline-flag letter can
 * activate (see {@link FlagBindings} — the letter&rarr;behavior map a
 * compile carries). A letter can only ever resolve to "set or unset
 * one of these named behaviors"; anything a dialect wants beyond
 * them is a new core behavior first, a binding second — the
 * vocabulary is the parser's, only the binding is data.
 *
 * <p>Every action targets <b>scoped</b> parser state: set at the
 * letter's position, restored at the enclosing group's {@code ')'}
 * (a flag-only group like {@code (?i)} persists into the remainder of
 * the enclosing group — the re2j/Java reading, pinned in
 * {@code PivotLanesTest}). The consumers materialize at parse time —
 * literals and classes fold, class tables and anchor flavors bind as
 * the atoms are read — so scoping the state scopes the effect. The
 * fold universe ({@link #UNICODE_CASE}) is scoped the same way since
 * the v2 landing: every spelling of the JUR {@code (?U)} — colon
 * form, in-group, negated — folds per {@code java.util.regex}
 * (verified there live; java's own spelling of the reading is inline
 * {@code (?u)}).
 */
public enum FlagAction {
    /** Case-insensitive matching — the fold gate; the fold universe
     *  folded is the {@link #UNICODE_CASE} state. Engine spelling
     *  {@code (?i)}. */
    CASE_INSENSITIVE,

    /** Dot matches line terminators. Engine spelling {@code (?s)}. */
    DOTALL,

    /** {@code ^}/{@code $} match at line boundaries. Engine spelling
     *  {@code (?m)}. */
    MULTILINE,

    /** Unicode predefined classes: {@code \w} {@code \d} {@code \s}
     *  and the POSIX classes use the Unicode tables, and
     *  case-insensitive matching widens to the Unicode fold (JDK
     *  {@code UNICODE_CHARACTER_CLASS}; the JDK's own spelling is
     *  {@code (?U)}, the engine letter is {@code (?u)} — the u/U
     *  letters are crossed vs the JDK). */
    UNICODE_CHARACTER_CLASS,

    /** Scoped Unicode case folding: widens the fold universe from
     *  ASCII-only to the full Unicode simple fold for the letters and
     *  classes in scope (JDK {@code UNICODE_CASE}; the JDK's own
     *  spelling is {@code (?u)}, the engine letter under the JUR
     *  reading is {@code (?U)}). */
    UNICODE_CASE,

    /** Swap the quantifier preference — {@code x*} behaves like
     *  {@code x*?} and vice versa (PCRE/RE2's {@code (?U)}; the
     *  engine letter under the RE2 reading is {@code (?U)}). */
    UNGREEDY
}
