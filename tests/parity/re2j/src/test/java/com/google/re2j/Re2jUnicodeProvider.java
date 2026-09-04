package com.google.re2j;

import io.github.jemmix.tdfa.unicode.UnicodeDataProvider;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * Bridge {@link UnicodeDataProvider} that resolves {@code \p{...}} property tables from
 * re2j's own frozen Unicode tables ({@link UnicodeTables}) and case-fold counterparts via
 * re2j's {@link Unicode#simpleFold(int)}. Lives in the {@code com.google.re2j} package so it
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
    private static final int[] ANY = new int[]{0, MAX_RUNE};

    private final Map<String, int[][]> raw = new HashMap<>();
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
        if ("Any".equals(name)) return ANY;
        int[] cached = expanded.get(name);
        if (cached != null) return cached;
        int[][] triples = raw.get(name);
        if (triples == null) return null;
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
        if (fold == null) return null;
        int[] cached = expanded.get("fold:" + name);
        if (cached != null) return cached;
        int[] flat = expand(fold);
        expanded.put("fold:" + name, flat);
        return flat;
    }

    /** Expand re2j's {@code {lo,hi,stride}} triples to flat contiguous {@code [lo,hi]} pairs. */
    private static int[] expand(int[][] triples) {
        ArrayList<int[]> ranges = new ArrayList<>();
        for (int[] t : triples) {
            int lo = t[0], hi = t[1], stride = t[2];
            if (stride == 1) {
                ranges.add(new int[]{lo, hi});
            } else {
                for (int cp = lo; cp <= hi; cp += stride) ranges.add(new int[]{cp, cp});
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
            merged.add(new int[]{r[0], r[1]});
        }
        int[] flat = new int[merged.size() * 2];
        for (int i = 0; i < merged.size(); i++) {
            flat[2 * i] = merged.get(i)[0];
            flat[2 * i + 1] = merged.get(i)[1];
        }
        return flat;
    }
}
