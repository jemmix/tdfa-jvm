package io.github.jemmix.tdfa.ast;

/**
 * The engine's alphabet, defined once. A symbol is a Unicode codepoint in
 * [0, 0x10FFFF]; text is its UTF-16 encoding. Pattern-side character reading
 * and match-side stepping both go through {@link #decode}, so the two ends
 * cannot disagree about what one transition consumes: exactly one codepoint.
 * A well-formed surrogate pair decodes to its supplementary codepoint; a lone
 * surrogate unit is itself a codepoint.
 */
public final class Alphabet {
    private Alphabet() {}

    /**
     * Codepoint at {@code pos} within {@code [pos, end)}. A high surrogate
     * followed by a low surrogate (both inside the range) decodes to the
     * supplementary codepoint; anything else yields the unit at {@code pos}.
     */
    public static int decode(CharSequence s, int pos, int end) {
        char c0 = s.charAt(pos);
        if (c0 >= 0xD800 && c0 <= 0xDBFF && pos + 1 < end) {
            char c1 = s.charAt(pos + 1);
            if (c1 >= 0xDC00 && c1 <= 0xDFFF)
                return ((c0 - 0xD800) << 10) + (c1 - 0xDC00) + 0x10000;
        }
        return c0;
    }

    /** UTF-16 unit count of a decoded codepoint. */
    public static int width(int cp) {
        return cp > 0xFFFF ? 2 : 1;
    }

    /**
     * True when {@code pos} is the low half of a well-formed pair — never a
     * valid codepoint boundary. No match may start, restart, or re-seed at
     * such a position: the pair is one codepoint and its second unit belongs
     * to it.
     */
    public static boolean pairInterior(CharSequence s, int pos) {
        return pos > 0 && pos < s.length()
                && s.charAt(pos) >= 0xDC00 && s.charAt(pos) <= 0xDFFF
                && s.charAt(pos - 1) >= 0xD800 && s.charAt(pos - 1) <= 0xDBFF;
    }
}
