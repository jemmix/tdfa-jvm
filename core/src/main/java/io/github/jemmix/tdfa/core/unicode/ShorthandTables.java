package io.github.jemmix.tdfa.core.unicode;

import io.github.jemmix.tdfa.core.dialect.RangeMath;

import java.util.Arrays;

/**
 * The Unicode shorthand tables — the single copy of the provider-derived
 * {@code \w}/{@code \d} range materialization. Two tiers consult it: the
 * parser (the runtime {@code \b} word table a {@code ParseResult} carries)
 * and the meaning-resolution pass (the symbolic {@code Ast.Shorthand}
 * expansion), each with its own cache over the same pure functions — the
 * provider is the source of truth, the results deterministic per provider.
 */
public final class ShorthandTables {

    private ShorthandTables() {
    }

    /**
     * Unicode {@code \w} ranges = {@code \p{Alpha} + \p{M} + \p{Nd} + \p{Pc}
     * + \p{IsJoin_Control}} — matching {@code java.util.regex} with
     * {@code UNICODE_CHARACTER_CLASS} exactly on the default (JDK-derived)
     * universe. Providers without the Alphabetic derived table (e.g. the
     * re2j-tables bridge) approximate it by its category-expressible subset
     * {@code L + Nl}; a missing Join_Control table is omitted (U+200C/200D).
     *
     * @param wordFallback the ASCII word table to fall back to when the
     *            provider supplies none of the properties (never used by the
     *            stock providers)
     */
    public static int[] wordRanges(UnicodeDataProvider provider, int[] wordFallback) {
        int[] merged = null;
        for (String prop : new String[]{"Alpha", "M", "Nd", "Pc", "Join_Control"}) {
            int[] t = provider.tableFor(prop);
            if (t == null && "Alpha".equals(prop)) {
                int[] l = provider.tableFor("L");
                int[] nl = provider.tableFor("Nl");
                t = (l == null) ? nl : (nl == null) ? l : mergeRanges(l, nl);
            }
            if (t != null) {
                merged = (merged == null) ? t : mergeRanges(merged, t);
            }
        }
        return merged != null ? merged : wordFallback;
    }

    /**
     * Unicode {@code \d} ranges = {@code \p{Nd}} (decimal digit number);
     * {@code digitFallback} when the provider lacks the table (never used by
     * the stock providers).
     */
    public static int[] digitRanges(UnicodeDataProvider provider, int[] digitFallback) {
        int[] t = provider.tableFor("Nd");
        return t != null ? t : digitFallback;
    }

    /** Merge two sorted range arrays into a sorted, merged result
     *  (delegates to the dialect leaf's single copy). */
    public static int[] mergeRanges(int[] a, int[] b) {
        return RangeMath.merge(a, b);
    }

}
