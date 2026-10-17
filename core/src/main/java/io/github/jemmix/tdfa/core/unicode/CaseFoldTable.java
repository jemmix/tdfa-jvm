package io.github.jemmix.tdfa.core.unicode;

import java.util.ArrayList;
import java.util.Arrays;
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

    /** Cased-codepoint case images, lazily built once per JVM: for every
     *  codepoint with any simple case image beyond itself, the flattened
     *  quad {c, toLower(c), toUpper(c), toLower(toUpper(c))}. The set is
     *  small (~4 K entries against 1.1 M codepoints), so a per-range scan
     *  over it is the whole cost of a range's input-side fold extras. */
    private static volatile int[] caseImages;

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

    // ---- java.util.regex's CASE-INSENSITIVE RANGE semantics ----

    /**
     * The extra codepoints a {@code java.util.regex} case-insensitive
     * RANGE {@code [lo-hi]} matches beyond its own members — the
     * compile-time transpose of the oracle's per-input range test. That
     * test (live-JDK probed, including the degenerate {[I]} vs {[H-K]}
     * split) is input-side over the image set, plus the two clauses newer
     * JDKs added — both PROBED by the caller, because the reading is
     * whatever THIS JVM's java.util.regex answers (JDK 26 tests more
     * than 25 did):
     * <pre>
     *   satisfies(c, R) ⟺ IMG(c) ∩ R ≠ ∅                       (always)
     *                     ∨ ∃ m ∈ R: c ∈ IMG(m)                 (member-side, JDK 26+)
     *   with IMG(x) = {x, toLower(x), toUpper(x)}                (always)
     *               ∪ {toLower(toUpper(x))}                      (double-map, JDK 26+)
     * </pre>
     * The clauses matter because either alone mis-answers the
     * asymmetric-mapping families: İ satisfies {@code [h-k]} input-side
     * ({@code toLower(İ)=i}) but no image test of an uppercase-only range
     * admits it, so {@code [H-K]} rejects İ; ı satisfies both sides
     * ({@code toUpper(ı)=I}, double-map to {@code i}); final sigma
     * reaches {@code [Σ-Σ]} via {@code toUpper} and {@code [σ-σ]} only
     * via the double map; and {@code i} satisfies {@code [İ-İ9]} only
     * member-side. Symmetric orbits satisfy every reading alike, so this
     * diverges from the per-member orbit closure
     * ({@link #foldRangesJava}, which governs single members and
     * literals) exactly on the asymmetric families — İ/ı, ſ, the Kelvin
     * and OHM signs, final sigma.
     *
     * <p>Returns the union as individual codepoints (unsorted; callers
     * union into the positive set), or {@code null} when there are no
     * extras.
     */
    public static int[] javaRangeFoldExtras(int lo, int hi, boolean memberSide, boolean doubleMap) {
        int[] img = caseImages;
        if (img == null) {
            img = publishCaseImages();
        }
        int[] out = new int[img.length / 4 + (hi - lo + 1) * 3];
        int n = 0;
        // input side: cased c with an image inside the raw range
        for (int i = 0; i < img.length; i += 4) {
            int c = img[i];
            if (c >= lo && c <= hi) {
                continue;
            }
            if (inRange(img[i + 1], lo, hi) || inRange(img[i + 2], lo, hi)
                || (doubleMap && inRange(img[i + 3], lo, hi))) {
                out[n++] = c;
            }
        }
        // member side (JDK 26+ regex): c ∈ IMG(m) for a cased member m —
        // most members are uncased; the cased ones contribute up to three
        // off-range images
        if (memberSide) {
            for (int m = lo; m <= hi; m++) {
                int lower = Character.toLowerCase(m);
                int upper = Character.toUpperCase(m);
                if (lower == m && upper == m) {
                    continue;
                } // uncased member: IMG(m) = {m}, already in R
                n = addIfOut(out, n, lower, lo, hi);
                n = addIfOut(out, n, upper, lo, hi);
                if (doubleMap) {
                    n = addIfOut(out, n, Character.toLowerCase(upper), lo, hi);
                }
            }
        }
        return Arrays.copyOf(out, n); // possibly empty: applicable-but-no-extras
    }

    private static int addIfOut(int[] out, int n, int cp, int lo, int hi) {
        if (cp < lo || cp > hi) {
            out[n++] = cp;
        }
        return n;
    }

    private static boolean inRange(int cp, int lo, int hi) {
        return cp >= lo && cp <= hi;
    }

    private static int[] publishCaseImages() {
        synchronized (CaseFoldTable.class) {
            int[] img = caseImages;
            if (img == null) {
                ArrayList<int[]> quads = new ArrayList<>();
                for (int cp = 0; cp <= Character.MAX_CODE_POINT; cp++) {
                    int lower = Character.toLowerCase(cp);
                    int upper = Character.toUpperCase(cp);
                    if (lower == cp && upper == cp) {
                        continue;
                    } // uncased: no image beyond itself
                    int lowerOfUpper = Character.toLowerCase(upper);
                    quads.add(new int[]{cp, lower, upper, lowerOfUpper});
                }
                img = new int[quads.size() * 4];
                for (int i = 0; i < quads.size(); i++) {
                    int[] q = quads.get(i);
                    img[4 * i] = q[0];
                    img[4 * i + 1] = q[1];
                    img[4 * i + 2] = q[2];
                    img[4 * i + 3] = q[3];
                }
                caseImages = img;
            }
            return img;
        }
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
