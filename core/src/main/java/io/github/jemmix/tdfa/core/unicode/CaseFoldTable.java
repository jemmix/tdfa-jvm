package io.github.jemmix.tdfa.core.unicode;

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
 * themselves — that is the {@link #foldRanges} (RE2-lineage) reading.
 * {@code java.util.regex} under CASE_INSENSITIVE|UNICODE_CASE folds the
 * JDK way (İ/ı merged into the i-orbit — live-JDK verified), which is the
 * {@link #foldRangesJava} reading the jur surface's default provider
 * supplies; the two surfaces pick their oracle's orbit (see
 * {@code Re2jUniverseProvider}).
 *
 * <p>Both indexes are built lazily on first use (one pass over 0..0x10FFFF
 * each) and cached for the JVM lifetime.
 */
public final class CaseFoldTable {

    /** Published index (built once; volatile for safe lazy publication). */
    private static volatile FoldIndex index;

    /** The java-reading twin (İ/ı merged; see {@link #foldRangesJava}). */
    private static volatile FoldIndex javaIndex;

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
     * BMP and supplementary — that are case-fold-equivalent to {@code ch} under
     * the <b>RE2-lineage reading</b> (the Turkic İ/ı pair fold-inert — re2j
     * and Go parity), or {@code null} if {@code ch} has no case-fold
     * equivalents beyond itself (digits, punctuation, etc.).
     *
     * <p><b>Provenance:</b> the table is derived at runtime from the running
     * JDK's {@link Character} simple case mappings, so fold orbits can vary
     * with the JVM's Unicode version — unlike the pinnable
     * {@code UnicodeDataProvider} tables used for {@code \p{...}} classes.
     * The İ/ı pin is the one deliberate divergence from those mappings.
     */
    public static int[] foldRanges(int ch) {
        FoldIndex idx = index;
        return (idx == null ? publish(false) : idx).get(foldKey(ch, false));
    }

    /**
     * The {@code java.util.regex} reading of the same universe: what a live
     * JDK answers under CASE_INSENSITIVE|UNICODE_CASE, where the Turkic
     * İ/ı pair merges into the i-orbit (the JDK's locale-independent case
     * mappings — live-verified: {@code (?iu)i} matches İ and ı). This is
     * the reading the jur surface's default fold universe supplies; same
     * shape contract as {@link #foldRanges(int)}.
     */
    public static int[] foldRangesJava(int ch) {
        FoldIndex idx = javaIndex;
        return (idx == null ? publish(true) : idx).get(foldKey(ch, true));
    }

    /** Double-checked lazy publication of the requested reading's index. */
    private static FoldIndex publish(boolean javaReading) {
        synchronized (CaseFoldTable.class) {
            FoldIndex idx = javaReading ? javaIndex : index;
            if (idx == null) {
                idx = new FoldIndex(buildFolded(javaReading));
                if (javaReading) {
                    javaIndex = idx;
                } else {
                    index = idx;
                }
            }
            return idx;
        }
    }

    /**
     * The ASCII fold universe — JDK {@code CASE_INSENSITIVE} without
     * {@code UNICODE_CASE} (JUR bare CI): the 26 letter pairs
     * {@code a-z}/{@code A-Z} and nothing else. The Kelvin sign, the long
     * s and the Turkic İ/ı pair are all fold-inert here by construction
     * (verified against a live java.util.regex). Same shape contract as
     * {@link #foldRanges(int)}: flattened sorted ranges covering the whole
     * orbit — including {@code ch} itself — or {@code null} when {@code ch}
     * has no ASCII case-fold counterparts.
     */
    public static int[] asciiFoldRanges(int ch) {
        if (ch >= 'A' && ch <= 'Z') {
            return new int[]{ch, ch, ch + 32, ch + 32};
        }
        if (ch >= 'a' && ch <= 'z') {
            return new int[]{ch - 32, ch - 32, ch, ch};
        }
        return null;
    }

    private static int foldKey(int cp, boolean javaReading) {
        // Simple case folding keeps the Turkic İ/ı pair out of the i-orbit
        // (their cross mappings are locale rules, not unconditional ones);
        // JDK case mapping is locale-independent and links ı→I, which would
        // over-merge {I, i, İ, ı} into one fold orbit. re2j and Go both keep
        // the pair fold-inert — the RE2 reading pins both to inert singleton
        // orbits; the java reading lets the JDK mappings speak (what
        // java.util.regex answers under CI|UNICODE_CASE).
        if (!javaReading && (cp == 0x130 || cp == 0x131)) {
            return cp;
        }
        return Character.toUpperCase(Character.toLowerCase(cp));
    }

    /** Grouped, merged fold orbits (multi-member keys only) — input to {@link FoldIndex}. */
    private static Map<Integer, int[]> buildFolded(boolean javaReading) {
        Map<Integer, ArrayList<Integer>> groups = new HashMap<>();
        for (int cp = 0; cp <= 0x10FFFF; cp++) {
            int fk = foldKey(cp, javaReading);
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
