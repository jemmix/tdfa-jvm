package io.github.jemmix.tdfa.core.unicode;

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

    /** Merge two sorted range arrays into a sorted, merged result. */
    public static int[] mergeRanges(int[] a, int[] b) {
        int[] all = new int[a.length + b.length];
        System.arraycopy(a, 0, all, 0, a.length);
        System.arraycopy(b, 0, all, a.length, b.length);
        int n = all.length / 2;
        long[] pairs = new long[n];
        for (int i = 0; i < n; i++) {
            pairs[i] = ((long) all[2 * i] << 32) | (all[2 * i + 1] & 0xFFFFFFFFL);
        }
        Arrays.sort(pairs);
        int[] out = new int[all.length];
        int w = 0;
        int lo = (int) (pairs[0] >>> 32);
        int hi = (int) pairs[0];
        for (int i = 1; i < n; i++) {
            int nlo = (int) (pairs[i] >>> 32);
            int nhi = (int) pairs[i];
            if (nlo <= hi + 1) {
                if (nhi > hi) {
                    hi = nhi;
                }
            } else {
                out[w++] = lo;
                out[w++] = hi;
                lo = nlo;
                hi = nhi;
            }
        }
        out[w++] = lo;
        out[w++] = hi;
        return w == all.length ? out : Arrays.copyOf(out, w);
    }
}
