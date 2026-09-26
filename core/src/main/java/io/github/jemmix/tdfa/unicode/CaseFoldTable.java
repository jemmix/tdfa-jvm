package io.github.jemmix.tdfa.unicode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * Reverse Unicode case-fold table: maps each canonical fold key to the
 * ranges of all codepoints that fold to that key.
 *
 * <p>Used by {@code Parser} to expand literal chars under case-insensitive
 * + Unicode mode to include Unicode simple case folds (e.g., {@code s ↔ ſ}
 * U+017F) that {@link Character#toLowerCase} / {@link Character#toUpperCase}
 * alone miss.
 *
 * <p>The canonical fold key is {@code Character.toUpperCase(Character.toLowerCase(cp))}.
 * The JDK's {@code toLowerCase}/{@code toUpperCase} implement the Unicode
 * simple case mappings, so two codepoints are treated as case-fold-equivalent
 * when they share the same fold key (e.g., {@code s/S/ſ}, {@code k/K/K},
 * {@code Ω/ω/Ω}). This composition approximates — and has been probed
 * exhaustively against re2j's fold orbits, but is not formally identical to —
 * Unicode simple case folding; with one pinned exception: the Turkic
 * dotted/dotless I pair. Simple case folding keeps U+0130 (İ) and U+0131 (ı)
 * out of the i-orbit (their cross mappings are Turkic-locale rules, not
 * unconditional mappings), but the JDK's locale-independent
 * {@code toUpperCase('ı') = 'I'} would merge {I, i, İ, ı} into one orbit.
 * re2j (CASE_ORBIT self-entries) and Go's {@code unicode.SimpleFold} both
 * keep the pair fold-inert, so {@link #foldKey} pins both codepoints to
 * themselves.
 *
 * <p>The table is built lazily on first use (one pass over 0..0x10FFFF) and
 * cached for the JVM lifetime.
 */
public final class CaseFoldTable {

    /** Published index (built once; volatile for safe lazy publication). */
    private static volatile FoldIndex index;

    private CaseFoldTable() {
    }

    /**
     * Primitive open-addressed fold-key → ranges index (linear probing,
     * power-of-two capacity), not a boxed map: the class-fold path calls
     * this once per codepoint of every range under (?i) — for
     * {@code (?i)[\W]}-shaped classes that is ~1.1M lookups per compile,
     * and a boxed get costs an autobox, a hashCode, and a probe per
     * lookup. Keys stored as {@code foldKey + 1} so slot 0 can mean empty.
     */
    private static final class FoldIndex {
        final int[] keys;
        final int[][] values;

        FoldIndex(Map<Integer, int[]> folded) {
            int cap = 1 << 15; // ~4K multi-member orbits → load ≤ ~0.13
            while (cap < folded.size() * 4) {
                cap <<= 1;
            }
            keys = new int[cap];
            values = new int[cap][];
            for (Map.Entry<Integer, int[]> e : folded.entrySet()) {
                int k = e.getKey() + 1;
                int i = spread(e.getKey()) & (cap - 1);
                while (keys[i] != 0) {
                    i = (i + 1) & (cap - 1);
                }
                keys[i] = k;
                values[i] = e.getValue();
            }
        }

        private static int spread(int fk) {
            return fk * 0x9E3779B9; // fold keys cluster low; spread high bits
        }

        /** Ranges for {@code fk}, or null when the orbit is a singleton. */
        int[] get(int fk) {
            int mask = keys.length - 1;
            int i = spread(fk) & mask;
            while (true) {
                int k = keys[i];
                if (k == 0) {
                    return null;
                }
                if (k == fk + 1) {
                    return values[i];
                }
                i = (i + 1) & mask;
            }
        }
    }

    /**
     * Returns flattened ranges (lo0, hi0, lo1, hi1, ...) of ALL codepoints —
     * BMP and supplementary — that are case-fold-equivalent to {@code ch}, or
     * {@code null} if {@code ch} has no case-fold equivalents beyond itself
     * (digits, punctuation, etc.).
     *
     * <p><b>Provenance:</b> the table is derived at runtime from the running
     * JDK's {@link Character} simple case mappings, so fold orbits can vary
     * with the JVM's Unicode version — unlike the pinnable
     * {@code UnicodeDataProvider} tables used for {@code \p{...}} classes.
     * The İ/ı pin below is the one deliberate divergence.
     */
    public static int[] foldRanges(int ch) {
        FoldIndex idx = index;
        if (idx == null) {
            synchronized (CaseFoldTable.class) {
                idx = index;
                if (idx == null) {
                    idx = new FoldIndex(buildFolded());
                    index = idx;
                }
            }
        }
        return idx.get(foldKey(ch));
    }

    private static int foldKey(int cp) {
        // Simple case folding keeps the Turkic İ/ı pair out of the i-orbit
        // (their cross mappings are locale rules, not unconditional ones);
        // JDK case mapping is locale-independent and links ı→I, which would
        // over-merge {I, i, İ, ı} into one fold orbit. re2j and Go both keep
        // the pair fold-inert — pin both to inert singleton orbits.
        if (cp == 0x130 || cp == 0x131) {
            return cp;
        }
        return Character.toUpperCase(Character.toLowerCase(cp));
    }

    /** Grouped, merged fold orbits (multi-member keys only) — input to {@link FoldIndex}. */
    private static Map<Integer, int[]> buildFolded() {
        Map<Integer, ArrayList<Integer>> groups = new HashMap<>();
        for (int cp = 0; cp <= 0x10FFFF; cp++) {
            int fk = foldKey(cp);
            groups.computeIfAbsent(fk, k -> new ArrayList<>()).add(cp);
        }
        Map<Integer, int[]> out = new HashMap<>();
        for (Map.Entry<Integer, ArrayList<Integer>> e : groups.entrySet()) {
            ArrayList<Integer> cps = e.getValue();
            if (cps.size() <= 1) {
                continue;
            }
            cps.sort(Integer::compare);
            ArrayList<int[]> merged = new ArrayList<>();
            for (int cp : cps) {
                if (!merged.isEmpty()) {
                    int[] last = merged.get(merged.size() - 1);
                    if (cp <= last[1] + 1) {
                        last[1] = cp;
                        continue;
                    }
                }
                merged.add(new int[]{cp, cp});
            }
            int[] flat = new int[merged.size() * 2];
            for (int i = 0; i < merged.size(); i++) {
                flat[2 * i] = merged.get(i)[0];
                flat[2 * i + 1] = merged.get(i)[1];
            }
            out.put(e.getKey(), flat);
        }
        return out;
    }
}
