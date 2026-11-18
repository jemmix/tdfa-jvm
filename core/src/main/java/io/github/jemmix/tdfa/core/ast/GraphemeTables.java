package io.github.jemmix.tdfa.core.ast;

import java.util.Arrays;

import java.util.Arrays;

/**
 * The match-time grapheme-break inputs (UAX #29): the {@code gcb.*} /
 * {@code gcb.ExtPict} / {@code incb.*} range tables a {@code \b{g}}
 * artifact needs, resolved once at compile from the compile's
 * {@link UnicodeDataProvider} and carried on the artifact like the word
 * table. The one consumer is the runner's cluster scanner
 * ({@code TdfaRunner.nextGraphemeBoundary}).
 *
 * <p>Optional tables may be absent (a 6.0 snapshot has no ZWJ, no
 * ExtPict, no InCB) — the rules degrade exactly with them.
 */
public final class GraphemeTables {

    /** CR ∪ LF ∪ Control (GB3/4/5 collapse into one family). */
    public final int[] control;
    public final int[] extend;
    /** Null when the snapshot carries no ZWJ class. */
    public final int[] zwj;
    public final int[] spacingMark;
    public final int[] l;
    public final int[] v;
    public final int[] lv;
    public final int[] lvt;
    public final int[] t;
    public final int[] ri;
    public final int[] prepend;
    /** Null when the snapshot carries no Extended_Pictographic. */
    public final int[] extPict;
    /** Null when the snapshot carries no InCB (pre-15.1). */
    public final int[] incbExtend;
    public final int[] linker;
    public final int[] linkingConsonant;

    /** Public-but-internal: built by the parser's provider resolution. */
    public GraphemeTables(int[] control, int[] extend, int[] zwj, int[] spacingMark, int[] l, int[] v, int[] lv,
        int[] lvt, int[] t, int[] ri, int[] prepend, int[] extPict, int[] incbExtend, int[] linker,
        int[] linkingConsonant) {
        this.control = control;
        this.extend = extend;
        this.zwj = zwj;
        this.spacingMark = spacingMark;
        this.l = l;
        this.v = v;
        this.lv = lv;
        this.lvt = lvt;
        this.t = t;
        this.ri = ri;
        this.prepend = prepend;
        this.extPict = extPict;
        this.incbExtend = incbExtend;
        this.linker = linker;
        this.linkingConsonant = linkingConsonant;
    }

    private static int[] mergeSorted(int[] ranges) {
        if (ranges.length < 4) {
            return ranges;
        }
        // sort pairs by lo, then merge overlaps/adjacents
        int n = ranges.length / 2;
        long[] keys = new long[n];
        for (int i = 0; i < n; i++) {
            keys[i] = ((long) ranges[2 * i] << 32) | ranges[2 * i + 1];
        }
        Arrays.sort(keys);
        int[] out = new int[ranges.length];
        int k = 0;
        for (int i = 0; i < n; i++) {
            int lo = (int) (keys[i] >> 32);
            int hi = (int) keys[i];
            if (k > 0 && lo <= out[k - 1] + 1) {
                if (hi > out[k - 1]) {
                    out[k - 1] = hi;
                }
            } else {
                out[k++] = lo;
                out[k++] = hi;
            }
        }
        return k == out.length ? out : Arrays.copyOf(out, k);
    }

    /** Membership in flat [lo,hi] ranges (binary search). */
    public static boolean in(int[] ranges, int cp) {
        if (ranges == null || ranges.length == 0) {
            return false;
        }
        int lo = 0, hi = ranges.length / 2 - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (cp < ranges[2 * mid]) {
                hi = mid - 1;
            } else if (cp > ranges[2 * mid + 1]) {
                lo = mid + 1;
            } else {
                return true;
            }
        }
        return false;
    }
}
