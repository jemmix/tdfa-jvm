package io.github.jemmix.tdfa.core.dialect;

/**
 * The escape-alphabet axis — which backslash escape readings a compile
 * admits, carried by a compile's {@code Semantics} and consumed at the
 * parse (an escape either materializes or rejects; nothing reaches
 * match time). A closed vocabulary: adding a reading family is a new
 * constant, and a dialect selects one — the widening seam
 * (see {@code docs/dialect-agnostic-core.md}).
 */
public enum EscapeAlphabet {

    /** The full java.util.regex alphabet: {@code \v}/{@code \V} the
     *  vertical and {@code \h}/{@code \H} the horizontal whitespace
     *  class tables, {@code \e} ESC, {@code \R} any Unicode linebreak
     *  sequence, {@code \cX} a control char, {@code \N{NAME}} the named
     *  codepoint, {@code \Z} the before-final-terminator end anchor. */
    JDK,

    /** The RE2 family's minimal alphabet: {@code \e \R \N{name} \cX \h
     *  \H \V \Z} reject at the parse, and {@code \v} is the single
     *  unit U+000B. (The octal family is shared by both readings.) */
    RE2_MINIMAL
}
