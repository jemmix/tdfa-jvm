package com.google.re2j;

import io.github.jemmix.tdfa.unicode.UnicodeDataProvider;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Bridge {@link UnicodeDataProvider} that resolves {@code \p{...}} property tables from
 * re2j's own frozen Unicode tables ({@code UnicodeTables}) and case-fold counterparts (for
 * both case-insensitive {@code \p{X}} and literal/class folding) via re2j's
 * {@link Unicode#simpleFold(int)}. Lives in the {@code com.google.re2j} package so it
 * can read re2j's package-private static state directly — this makes it bit-exact with re2j
 * by construction (no transcription of the ~4000-line {@code UnicodeTables.java}).
 *
 * <p>Test-scope by design: it depends on {@code com.google.re2j} being on the
 * classpath, which makes it bit-exact with the live oracle by construction.
 * The shippable tiers are the pinned generated tables in the {@code :unicode}
 * modules (UCD 6.0.0 = re2j-parity tier, UCD 17.0.0 = modern tier); this
 * provider exists so parity tests exercise the oracle's own tables directly.
 *
 * <p>re2j stores each table as {@code int[][]} of {@code {lo, hi, stride}} triples (stride
 * may be > 1 for sparse entries); we expand to our flat {@code int[] {lo,hi,lo,hi,...}} pairs.
 */
public final class Re2jUnicodeProvider implements UnicodeDataProvider {

    public static final Re2jUnicodeProvider INSTANCE = new Re2jUnicodeProvider();

    private static final int MAX_RUNE = 0x10FFFF;
    private static final int[] ANY = new int[] {0, MAX_RUNE};

    private final Map<String, int[][]> raw = new HashMap<>();

    private final Map<Integer, int[]> orbits = new HashMap<>();
    private final Map<String, int[]> expanded = new HashMap<>();

    private Re2jUnicodeProvider() {
        for (Field f : UnicodeTables.class.getDeclaredFields()) {
            if (f.getType() == int[][].class && !f.getName().startsWith("fold")) {
                try {
                    f.setAccessible(true);
                    raw.put(f.getName(), (int[][]) f.get(null));
                } catch (IllegalAccessException e) {
                    throw new LinkageError("cannot read UnicodeTables." + f.getName(), e);
                }
            }
        }
    }

    @Override
    public int[] tableFor(String name) {
        if ("Any".equals(name)) {
            return ANY;
        }
        int[] cached = expanded.get(name);
        if (cached != null) {
            return cached;
        }
        int[][] triples = raw.get(name);
        if (triples == null) {
            return null;
        }
        int[] flat = expand(triples);
        expanded.put(name, flat);
        return flat;
    }

    @Override
    public int[] foldTableFor(String name) {
        // re2j precomputes fold-addition tables only for the four case-bearing categories
        // Lu/Ll/Lt/Mn (see UnicodeTables.FoldCategory); other classes get no fold additions.
        // NB: this faithfully matches re2j, which (notably) does NOT fold ASCII A-Z into \p{Ll}.
        int[][] fold = UnicodeTables.FOLD_CATEGORIES.get(name);
        if (fold == null) {
            return null;
        }
        int[] cached = expanded.get("fold:" + name);
        if (cached != null) {
            return cached;
        }
        int[] flat = expand(fold);
        expanded.put("fold:" + name, flat);
        return flat;
    }

    /**
     * Literal/class folding follows the oracle's own fold universe, so tdfa folds exactly like
     * the re2j on the classpath (released or patched) by construction — no transcription of its
     * behavior. The orbit is {@link Unicode#simpleFold}'s next-pointer walk; simple-fold orbits
     * have at most 4 members, so the walk is hop-bounded defensively: the bound only engages on
     * the released oracle's asymmetric-mapping runes (U+1C80..U+1C88, whose walks never cycle
     * back — the patched oracle declines those mappings instead), and a bounded walk yields
     * no orbit (fold-inert) rather than a partial one.
     */
    @Override
    public boolean suppliesFoldUniverse() {
        return true;
    }

    @Override
    public int[] foldCounterparts(int cp) {
        if (cp < 0 || cp > MAX_RUNE) {
            return null;
        }
        synchronized (orbits) {
            int[] cached = orbits.get(cp);
            if (cached != null) {
                return cached.length == 0 ? null : cached;
            }
        }
        int[] result = buildOrbit(cp);
        synchronized (orbits) {
            orbits.put(cp, result == null ? new int[0] : result);
        }
        return result;
    }

    /** Orbit of {@code cp} as merged ranges including {@code cp}, or {@code null} if it has
     *  no fold counterparts (or the walk failed to close — see {@link #foldCounterparts(int)}). */
    private static int[] buildOrbit(int cp) {
        int[] members = new int[5];
        int n = 0;
        members[n++] = cp;
        int f = Unicode.simpleFold(cp);
        for (int hops = 0; f != cp && hops < 4; hops++) {
            int i = 0;
            while (i < n && members[i] != f) {
                i++;
            }
            if (i == n) {
                if (n == members.length) {
                    return null;
                } // not closing: bail out inert
                members[n++] = f;
            }
            f = Unicode.simpleFold(f);
        }
        if (f != cp) {
            return null;
        } // bounded without cycling back: fold-inert
        if (n == 1) {
            return null;
        }
        Arrays.sort(members, 0, n);
        ArrayList<int[]> merged = new ArrayList<>();
        int lo = members[0], hi = members[0];
        for (int i = 1; i < n; i++) {
            if (members[i] <= hi + 1) {
                hi = members[i];
                continue;
            }
            merged.add(new int[] {lo, hi});
            lo = hi = members[i];
        }
        merged.add(new int[] {lo, hi});
        int[] flat = new int[merged.size() * 2];
        for (int i = 0; i < merged.size(); i++) {
            flat[2 * i] = merged.get(i)[0];
            flat[2 * i + 1] = merged.get(i)[1];
        }
        return flat;
    }

    /** Expand re2j's {@code {lo,hi,stride}} triples to flat contiguous {@code [lo,hi]} pairs. */
    private static int[] expand(int[][] triples) {
        ArrayList<int[]> ranges = new ArrayList<>();
        for (int[] t : triples) {
            int lo = t[0], hi = t[1], stride = t[2];
            if (stride == 1) {
                ranges.add(new int[] {lo, hi});
            } else {
                for (int cp = lo; cp <= hi; cp += stride) {
                    ranges.add(new int[] {cp, cp});
                }
            }
        }
        return flatten(ranges);
    }

    private static int[] flatten(ArrayList<int[]> ranges) {
        ranges.sort((a, b) -> Integer.compare(a[0], b[0]));
        ArrayList<int[]> merged = new ArrayList<>();
        for (int[] r : ranges) {
            if (!merged.isEmpty()) {
                int[] last = merged.get(merged.size() - 1);
                if (r[0] <= last[1] + 1) {
                    last[1] = Math.max(last[1], r[1]);
                    continue;
                }
            }
            merged.add(new int[] {r[0], r[1]});
        }
        int[] flat = new int[merged.size() * 2];
        for (int i = 0; i < merged.size(); i++) {
            flat[2 * i] = merged.get(i)[0];
            flat[2 * i + 1] = merged.get(i)[1];
        }
        return flat;
    }
}
