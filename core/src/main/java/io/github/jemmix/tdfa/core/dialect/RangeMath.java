package io.github.jemmix.tdfa.core.dialect;

import java.util.Arrays;
import java.util.List;

/**
 * The pure range arithmetic over the flattened lo/hi convention
 * ({@code lo0, hi0, lo1, hi1, ...}, hi inclusive, the convention the
 * parser's accumulators, the {@code ClassTable} values and the
 * {@code CharClass} ranges all share): complement, merge and interval
 * normalization. A leaf in the dialect vocabulary — pure functions over
 * data, referenced by the parser (the materialized grammar tables and
 * property classes) and the meaning-resolution pass (the class fold and
 * the shorthand expansion) alike.
 */
public final class RangeMath {

    private RangeMath() {
    }

    /** Compute the complement of {@code ranges} within [0, 0x10FFFF].
     *  Order-independent: the pairs are sorted first (callers may carry
     *  hand-written unsorted tables — the parser's ASCII word table spells
     *  its pairs alphabetically, not by codepoint). */
    public static int[] complement(int[] ranges) {
        int n = ranges.length / 2;
        long[] pairs = new long[n];
        for (int i = 0; i < n; i++) {
            pairs[i] = ((long) ranges[2 * i] << 32) | (ranges[2 * i + 1] & 0xFFFFFFFFL);
        }
        Arrays.sort(pairs);
        int[] out = new int[2 * n + 2];
        int w = 0;
        int prev = 0;
        for (int i = 0; i < n; i++) {
            int lo = (int) (pairs[i] >>> 32);
            int hi = (int) pairs[i];
            if (lo > prev) {
                out[w++] = prev;
                out[w++] = lo - 1;
            }
            prev = Math.max(prev, hi + 1);
            if (prev > 0x10FFFF) {
                break;
            }
        }
        if (prev <= 0x10FFFF) {
            out[w++] = prev;
            out[w++] = 0x10FFFF;
        }
        return w == out.length ? out : Arrays.copyOf(out, w);
    }

    /** Merge two sorted range arrays into a sorted, merged result. */
    public static int[] merge(int[] a, int[] b) {
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

    /** Sort intervals by lo, merge overlapping/adjacent, flatten. */
    public static int[] mergeIntervals(List<int[]> ivs) {
        ivs.sort((a, b) -> Integer.compare(a[0], b[0]));
        int[] out = new int[ivs.size() * 2];
        int w = 0;
        int lo = ivs.get(0)[0], hi = ivs.get(0)[1];
        for (int[] iv : ivs.subList(1, ivs.size())) {
            if (iv[0] <= hi + 1) {
                hi = Math.max(hi, iv[1]);
            } else {
                out[w++] = lo;
                out[w++] = hi;
                lo = iv[0];
                hi = iv[1];
            }
        }
        out[w++] = lo;
        out[w++] = hi;
        return w == out.length ? out : Arrays.copyOf(out, w);
    }
}
