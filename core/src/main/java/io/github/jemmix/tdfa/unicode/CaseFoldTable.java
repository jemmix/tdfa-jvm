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
 * simple case mappings, so two codepoints are case-fold-equivalent when they
 * share the same fold key (e.g., {@code s/S/ſ}, {@code k/K/K},
 * {@code Ω/ω/Ω}) — with one pinned exception: the Turkic dotted/dotless I
 * pair. Simple case folding keeps U+0130 (İ) and U+0131 (ı) out of the
 * i-orbit (their cross mappings are Turkic-locale rules, not unconditional
 * mappings), but the JDK's locale-independent {@code toUpperCase('ı') = 'I'}
 * would merge {I, i, İ, ı} into one orbit. re2j (CASE_ORBIT self-entries)
 * and Go's {@code unicode.SimpleFold} both keep the pair fold-inert, so
 * {@link #foldKey} pins both codepoints to themselves.
 *
 * <p>The table is built lazily on first use (one pass over 0..0x10FFFF) and
 * cached for the JVM lifetime.
 */
public final class CaseFoldTable {

    private static volatile Map<Integer, int[]> cache;

    private CaseFoldTable() {}

    /**
     * Returns flattened ranges (lo0, hi0, lo1, hi1, ...) of all BMP codepoints
     * that are case-fold-equivalent to {@code ch}, or {@code null} if {@code ch}
     * has no case-fold equivalents beyond itself (digits, punctuation, etc.).
     */
    public static int[] foldRanges(int ch) {
        Map<Integer, int[]> c = cache;
        if (c == null) {
            synchronized (CaseFoldTable.class) {
                c = cache;
                if (c == null) {
                    c = buildCache();
                    cache = c;
                }
            }
        }
        return c.get(foldKey(ch));
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

    private static Map<Integer, int[]> buildCache() {
        Map<Integer, ArrayList<Integer>> groups = new HashMap<>();
        for (int cp = 0; cp <= 0x10FFFF; cp++) {
            int fk = foldKey(cp);
            groups.computeIfAbsent(fk, k -> new ArrayList<>()).add(cp);
        }
        Map<Integer, int[]> out = new HashMap<>();
        for (Map.Entry<Integer, ArrayList<Integer>> e : groups.entrySet()) {
            ArrayList<Integer> cps = e.getValue();
            if (cps.size() <= 1) continue;
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
