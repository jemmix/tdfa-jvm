package io.github.jemmix.tdfa.core.ast;

/**
 * The engine's alphabet, defined once. A symbol is a Unicode codepoint in
 * [0, 0x10FFFF]; text is its UTF-16 encoding. Pattern-side character reading
 * and match-side stepping both go through {@link #decode}, so the two ends
 * cannot disagree about what one transition consumes: exactly one codepoint.
 * A well-formed surrogate pair decodes to its supplementary codepoint; a lone
 * surrogate unit is itself a codepoint.
 */
public final class Alphabet {
    private Alphabet() {
    }

    /**
     * Codepoint at {@code pos} within {@code [pos, end)}. A high surrogate
     * followed by a low surrogate (both inside the range) decodes to the
     * supplementary codepoint; anything else yields the unit at {@code pos}.
     */
    public static int decode(CharSequence s, int pos, int end) {
        char c0 = s.charAt(pos);
        if (c0 >= 0xD800 && c0 <= 0xDBFF && pos + 1 < end) {
            char c1 = s.charAt(pos + 1);
            if (c1 >= 0xDC00 && c1 <= 0xDFFF) {
                return ((c0 - 0xD800) << 10) + (c1 - 0xDC00) + 0x10000;
            }
        }
        return c0;
    }

    /** UTF-16 unit count of a decoded codepoint. */
    public static int width(int cp) {
        return cp > 0xFFFF ? 2 : 1;
    }

    /**
     * True when the unit at {@code pos} is a supplementary pair whose LOW
     * half sits at {@code end} — a pair CUT by a region end
     * ({@code end < s.length()} required: at the true text end the high is
     * genuinely lone and matches as a single unit). java's
     * {@code CharProperty.match} reads the full codepoint and requires the
     * consumption to fit within {@code matcher.to}: a cut pair never
     * matches and the node sets {@code hitEnd} (more input — the low half —
     * would complete it).
     */
    /**
     * True when the unit at {@code pos} is a supplementary pair whose LOW
     * half sits at {@code end} — a pair CUT by a region end
     * ({@code end < s.length()} required: at the true text end the high is
     * genuinely lone and matches as a single unit). java's
     * {@code CharProperty.match} reads the full codepoint and requires the
     * consumption to fit within {@code matcher.to}: a cut pair never
     * matches and the node sets {@code hitEnd} (more input — the low half —
     * would complete it).
     */
    public static boolean cutPairAt(CharSequence s, int pos, int end) {
        if (pos < 0 || end >= s.length() || pos + 1 != end) {
            return false;
        }
        char c0 = s.charAt(pos);
        return c0 >= 0xD800 && c0 <= 0xDBFF && s.charAt(end) >= 0xDC00 && s.charAt(end) <= 0xDFFF;
    }

    /**
     * True when a region START at {@code from} splits a surrogate pair (a
     * LOW half at {@code from} whose HIGH sits at {@code from-1}). java's
     * codepoint reads (the word pair's {@code codePointBefore}, a
     * consuming node at the region head) form the pair ACROSS the region
     * edge — a subSequence view of the region would hand the engine a
     * genuinely-lone low surrogate and every edge-adjacent read diverges.
     */
    public static boolean splitPairStart(CharSequence s, int from) {
        if (from <= 0 || from >= s.length()) {
            return false;
        }
        char c0 = s.charAt(from);
        return c0 >= 0xDC00 && c0 <= 0xDFFF && s.charAt(from - 1) >= 0xD800 && s.charAt(from - 1) <= 0xDBFF;
    }

    /**
     * True when {@code pos} is the low half of a well-formed pair — never a
     * valid codepoint boundary. No match may start, restart, or re-seed at
     * such a position: the pair is one codepoint and its second unit belongs
     * to it.
     */
    public static boolean pairInterior(CharSequence s, int pos) {
        return pos > 0 && pos < s.length() && s.charAt(pos) >= 0xDC00 && s.charAt(pos) <= 0xDFFF
            && s.charAt(pos - 1) >= 0xD800 && s.charAt(pos - 1) <= 0xDBFF;
    }
}
