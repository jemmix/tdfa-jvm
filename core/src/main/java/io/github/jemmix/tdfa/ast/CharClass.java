package io.github.jemmix.tdfa.ast;

import java.util.Arrays;

/**
 * Character class: lo/hi ranges (flattened, normalized to sorted disjoint
 * ascending order by the constructor). Negated if {@code negated}.
 * Matches one char per evaluation.
 */
public final class CharClass extends Ast {
    public final int[] ranges; // flattened lo0,hi0,lo1,hi1,...; hi inclusive; sorted, disjoint
    public final boolean negated;

    public CharClass(int[] ranges, boolean negated) {
        this.ranges = normalize(ranges); this.negated = negated;
    }

    /**
     * Sort and merge into sorted disjoint ranges. Membership semantics are
     * unchanged (boolean containment over a union); normalization enables the
     * binary search in {@link #matches} — determinization calls it per config
     * per breakpoint, and Unicode classes like {@code \p{L}} carry ~1369 ranges
     * where the old linear scan dominated compile time. Already-normalized
     * input (the common case: single literals, small hand-built classes)
     * returns the array untouched after one verification pass.
     */
    private static int[] normalize(int[] ranges) {
        int n = ranges.length / 2;
        if (n <= 1) return ranges;
        boolean sortedDisjoint = true;
        for (int i = 1; i < n; i++) {
            if (ranges[2 * i] <= ranges[2 * (i - 1) + 1]) { sortedDisjoint = false; break; }
        }
        if (sortedDisjoint) return ranges;
        long[] pairs = new long[n];
        for (int i = 0; i < n; i++) {
            pairs[i] = ((long) ranges[2 * i] << 32) | (ranges[2 * i + 1] & 0xFFFFFFFFL);
        }
        Arrays.sort(pairs);
        int[] out = new int[ranges.length];
        int w = 0;
        int lo = (int) (pairs[0] >>> 32);
        int hi = (int) pairs[0];
        for (int i = 1; i < n; i++) {
            int nlo = (int) (pairs[i] >>> 32);
            int nhi = (int) pairs[i];
            if (nlo <= hi + 1) {           // overlapping or adjacent: extend
                if (nhi > hi) hi = nhi;
            } else {
                out[w++] = lo; out[w++] = hi;
                lo = nlo; hi = nhi;
            }
        }
        out[w++] = lo; out[w++] = hi;
        return w == ranges.length ? out : Arrays.copyOf(out, w);
    }

    public boolean matches(int c) {
        if (ranges.length >= 8) {
            // binary search over sorted disjoint ranges: find last lo <= c
            int lo = 0, hi = ranges.length / 2 - 1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                if (ranges[2 * mid] <= c) lo = mid + 1; else hi = mid - 1;
            }
            // hi = index of last range with lo <= c (or -1)
            boolean in = hi >= 0 && c <= ranges[2 * hi + 1];
            return in != negated;
        }
        for (int i = 0; i < ranges.length; i += 2) {
            if (c >= ranges[i] && c <= ranges[i + 1]) return !negated;
        }
        return negated;
    }

    /**
     * Fixed UTF-16 width of every codepoint this class can match: 1 when all
     * members are at or below the BMP, 2 when all are supplementary; -1 when
     * members mix widths (or the class is empty). Fixed-tag distance
     * arithmetic runs in UTF-16 units, so it must poison on -1.
     */
    public int fixedUtf16Width() {
        if (ranges.length == 0) return -1;
        if (negated) return -1;  // match set is the complement: spans BMP and supplementary
        if (ranges[ranges.length - 1] <= 0xFFFF) return 1;
        if (ranges[0] >= 0x10000) return 2;
        return -1;
    }

    @Override public String toString() {
        StringBuilder sb = new StringBuilder(negated ? "[^" : "[");
        for (int i = 0; i < ranges.length; i += 2) {
            if (i > 0) sb.append(',');
            sb.append((char) ranges[i]).append('-').append((char) ranges[i + 1]);
        }
        return sb.append(']').toString();
    }
}
