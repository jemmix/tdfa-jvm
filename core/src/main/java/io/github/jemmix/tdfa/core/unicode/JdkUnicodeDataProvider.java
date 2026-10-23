package io.github.jemmix.tdfa.core.unicode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;
import java.util.regex.Pattern;

/**
 * {@link UnicodeDataProvider} backed by the JDK's bundled Unicode database
 * via {@link java.lang.Character} ({@link Character#getType(int)} and
 * {@link Character.UnicodeScript#of(int)}). Tables are built lazily on first
 * access by scanning all codepoints 0..U+10FFFF; results are cached for the
 * lifetime of the JVM.
 *
 * <p><b>Caveat for strict re2j parity:</b> The JDK's Unicode version follows
 * the JVM and is typically much newer than re2j's frozen Unicode 6.0 tables.
 * This means script/category ranges will diverge from re2j for codepoints
 * assigned after Unicode 6.0 — codepoints that didn't exist then now exist,
 * scripts have been added/extended, etc. For bit-exact re2j compat, use the
 * separate {@code tdfa-unicode-re2j} jar that ports re2j's tables verbatim
 * (set the {@value UnicodeProviders#PROPERTY_NAME} property to that
 * provider's class name).
 *
 * <p><b>Fold universe:</b> this provider supplies its own — the
 * {@code java.util.regex} reading ({@link CaseFoldTable#foldRangesJava}:
 * the Turkic İ/ı pair merged into the i-orbit, what a same-JVM
 * {@code java.util.regex} answers under CASE_INSENSITIVE|UNICODE_CASE),
 * for literal/class folds and the {@code \p{...}} fold twin alike. The
 * jur surface's default lane resolves here; the re2j surface pins the
 * RE2-lineage reading instead (see {@link Re2jUniverseProvider}).
 */
final class JdkUnicodeDataProvider implements UnicodeDataProvider {
    static final JdkUnicodeDataProvider INSTANCE = new JdkUnicodeDataProvider();
    private static final int[] ANY_TABLE = new int[]{0, Character.MAX_CODE_POINT};
    /** Join_Control = U+200C ZERO WIDTH NON-JOINER + U+200D ZERO WIDTH JOINER,
     *  frozen to exactly this pair by Unicode stability policy — hardcoded the
     *  same way {@code java.util.regex}'s UCC word predicate hardcodes it. */
    private static final int[] JOIN_CONTROL_TABLE = new int[]{0x200C, 0x200D};

    /** Maps each {@code byte} returned by {@link Character#getType(int)} to its
     *  two-letter Unicode general-category code; index is the byte value. */
    private static final String[] CATEGORY_NAMES = new String[31];

    static {
        CATEGORY_NAMES[Character.UPPERCASE_LETTER] = "Lu";
        CATEGORY_NAMES[Character.LOWERCASE_LETTER] = "Ll";
        CATEGORY_NAMES[Character.TITLECASE_LETTER] = "Lt";
        CATEGORY_NAMES[Character.MODIFIER_LETTER] = "Lm";
        CATEGORY_NAMES[Character.OTHER_LETTER] = "Lo";
        CATEGORY_NAMES[Character.NON_SPACING_MARK] = "Mn";
        CATEGORY_NAMES[Character.ENCLOSING_MARK] = "Me";
        CATEGORY_NAMES[Character.COMBINING_SPACING_MARK] = "Mc";
        CATEGORY_NAMES[Character.DECIMAL_DIGIT_NUMBER] = "Nd";
        CATEGORY_NAMES[Character.LETTER_NUMBER] = "Nl";
        CATEGORY_NAMES[Character.OTHER_NUMBER] = "No";
        CATEGORY_NAMES[Character.SPACE_SEPARATOR] = "Zs";
        CATEGORY_NAMES[Character.LINE_SEPARATOR] = "Zl";
        CATEGORY_NAMES[Character.PARAGRAPH_SEPARATOR] = "Zp";
        CATEGORY_NAMES[Character.CONTROL] = "Cc";
        CATEGORY_NAMES[Character.FORMAT] = "Cf";
        CATEGORY_NAMES[Character.PRIVATE_USE] = "Co";
        CATEGORY_NAMES[Character.SURROGATE] = "Cs";
        CATEGORY_NAMES[Character.DASH_PUNCTUATION] = "Pd";
        CATEGORY_NAMES[Character.START_PUNCTUATION] = "Ps";
        CATEGORY_NAMES[Character.END_PUNCTUATION] = "Pe";
        CATEGORY_NAMES[Character.CONNECTOR_PUNCTUATION] = "Pc";
        CATEGORY_NAMES[Character.OTHER_PUNCTUATION] = "Po";
        CATEGORY_NAMES[Character.MATH_SYMBOL] = "Sm";
        CATEGORY_NAMES[Character.CURRENCY_SYMBOL] = "Sc";
        CATEGORY_NAMES[Character.MODIFIER_SYMBOL] = "Sk";
        CATEGORY_NAMES[Character.OTHER_SYMBOL] = "So";
        CATEGORY_NAMES[Character.INITIAL_QUOTE_PUNCTUATION] = "Pi";
        CATEGORY_NAMES[Character.FINAL_QUOTE_PUNCTUATION] = "Pf";
        CATEGORY_NAMES[Character.UNASSIGNED] = "Cn";
    }

    /** Container categories (one-letter forms). Each maps to its sub-categories. */
    private static final Map<String, String[]> CONTAINERS = new HashMap<>();

    static {
        CONTAINERS.put("L", new String[]{"Lu", "Ll", "Lt", "Lm", "Lo"});
        CONTAINERS.put("M", new String[]{"Mn", "Me", "Mc"});
        CONTAINERS.put("N", new String[]{"Nd", "Nl", "No"});
        CONTAINERS.put("P", new String[]{"Pc", "Pd", "Ps", "Pe", "Pi", "Pf", "Po"});
        CONTAINERS.put("S", new String[]{"Sm", "Sc", "Sk", "So"});
        CONTAINERS.put("C", new String[]{"Cc", "Cf", "Co", "Cs", "Cn"});
        CONTAINERS.put("Z", new String[]{"Zs", "Zl", "Zp"});
    }

    private volatile Map<String, int[]> scripts;
    private volatile Map<String, int[]> categories;
    private volatile Map<String, int[]> foldTables;
    private volatile int[] alphabetic;
    /** The fold-closing sources discovered from the live oracle (see
     *  {@link #RANGE_FOLD_CLOSURE}), sorted; their matched inputs in
     *  parallel. Lazy one-time discovery. */
    private volatile int[] closingSrcs;
    private volatile int[][] closingExtras;

    /** JDK 26's regex added the member-side image clause to CI ranges
     *  ({@code i} matching {@code [İ-İ9]}); older regexen test the input's
     *  images only. This provider BRIDGES the live oracle (its declared
     *  role), so the reading is probed once from THIS JVM's
     *  java.util.regex rather than hard-coded — the same-JVM contract the
     *  fold universe already follows. */
    private static final boolean RANGE_MEMBER_SIDE = probe("[\u0130-\u0139]", "i");

    /** JDK 26's regex also admits the {@code toLower(toUpper(c))} image
     *  (final sigma reaching {@code [σ-σ]}); older regexen stop at
     *  toUpper. Probed the same way. */
    private static final boolean RANGE_DOUBLE_MAP = probe("[\u03C3-\u03C3]", "\u03C2");

    /** JDK 26's regex closes CI ranges by their members' whole fold
     * ORBITS (the new CaseFolding.getClassRangeClosingCharacters clause):
     * CAPITAL OMEGA reaches a range containing OHM SIGN through the
     * Ω/ω/OHM fold group though neither image of Ω lands in it. Older
     * regexen test images only. Probed the same way. */
    private static final boolean RANGE_FOLD_CLOSURE = probe("[\u2126-\u2126]", "\u03A9");

    private JdkUnicodeDataProvider() {
    }

    private static boolean probe(String rangeClass, String input) {
        try {
            return Pattern.compile(rangeClass, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(input)
                .matches();
        } catch (RuntimeException e) {
            return false; // unprobeable shapes fall back to the classic reading
        }
    }

    @Override
    public int[] tableFor(String name) {
        if ("Any".equals(name)) {
            return ANY_TABLE;
        }
        if ("Join_Control".equals(name)) {
            return JOIN_CONTROL_TABLE;
        }
        if ("Alpha".equals(name)) {
            return alphabetic();
        }
        Map<String, int[]> cats = categories();
        int[] t = cats.get(name);
        if (t != null) {
            return t;
        }
        Map<String, int[]> scr = scripts();
        return scr.get(name);
    }

    // ---- Alphabetic (derived property, not a general category) ----

    /** Lazily-built Unicode {@code Alphabetic} table (letters + letter
     *  numbers + Other_Alphabetic contributions, e.g. U+24B6). */
    private int[] alphabetic() {
        int[] t = alphabetic;
        if (t == null) {
            synchronized (this) {
                t = alphabetic;
                if (t == null) {
                    t = buildAlphabeticTable();
                    alphabetic = t;
                }
            }
        }
        return t;
    }

    /** Contiguous runs of {@link Character#isAlphabetic(int)} — the JDK's
     *  spelling of the Unicode Alphabetic property, so the default universe's
     *  {@code (?u)} word set matches a same-JVM {@code java.util.regex} UCC
     *  oracle by construction. */
    private static int[] buildAlphabeticTable() {
        ArrayList<int[]> ranges = new ArrayList<>();
        int start = -1;
        for (int cp = 0; cp <= Character.MAX_CODE_POINT; cp++) {
            if (Character.isAlphabetic(cp)) {
                if (start < 0) {
                    start = cp;
                }
            } else if (start >= 0) {
                ranges.add(new int[]{start, cp - 1});
                start = -1;
            }
        }
        if (start >= 0) {
            ranges.add(new int[]{start, Character.MAX_CODE_POINT});
        }
        return flatten(ranges);
    }

    @Override
    public boolean suppliesFoldUniverse() {
        return true;
    }

    @Override
    public int[] foldCounterparts(int cp) {
        return CaseFoldTable.foldRangesJava(cp);
    }

    /**
     * The java.util.regex RANGE reading (see
     * {@link CaseFoldTable#javaRangeFoldExtras}): java's case-insensitive
     * ranges test the INPUT's simple-case images against the raw member
     * range — NOT the member orbit closure the single/literal path uses —
     * so e.g. {@code [H-K]} rejects İ while {@code [I]} admits it
     * (live-JDK probed; the asymmetry is java.util.regex's own).
     */
    @Override
    public boolean suppliesRangeFoldSemantics() {
        return true;
    }

    @Override
    public int[] rangeFoldCounterparts(int lo, int hi) {
        int[] base = CaseFoldTable.javaRangeFoldExtras(lo, hi, RANGE_MEMBER_SIDE, RANGE_DOUBLE_MAP);
        if (!RANGE_FOLD_CLOSURE) {
            return base;
        }
        int[] closing = rangeClosingExtras(lo, hi);
        if (closing.length == 0) {
            return base;
        }
        int[] out = new int[base.length + closing.length];
        System.arraycopy(base, 0, out, 0, base.length);
        System.arraycopy(closing, 0, out, base.length, closing.length);
        return out; // duplicates fine: the class range-set merges
    }

    @Override
    public int[] foldTableFor(String name) {
        int[] table = tableFor(name);
        if (table == null) {
            return null;
        }
        // Per-map lock discipline: the map reference is volatile (safe
        // publication); the map itself is only ever read/written under
        // synchronized(ft) — a dedicated monitor, finer than the provider
        // singleton. First-creation double-checks under synchronized(this).
        Map<String, int[]> ft = foldTables;
        if (ft == null) {
            synchronized (this) {
                ft = foldTables;
                if (ft == null) {
                    ft = new HashMap<>();
                    foldTables = ft;
                }
            }
        }
        synchronized (ft) {
            int[] cached = ft.get(name);
            if (cached != null) {
                return cached.length == 0 ? null : cached;
            }
            int[] result = buildFoldTable(table, CaseFoldTable::foldRangesJava);
            ft.put(name, result == null ? new int[0] : result);
            return result;
        }
    }

    /**
     * Build fold counterpart ranges for the given property table: codepoints NOT in
     * the table that are case-fold-equivalent (per {@code foldFn}'s reading)
     * to a codepoint that IS in the table. The fold relation is symmetric and
     * includes multi-member groups (s/S/ſ, k/K/K), so both directions are
     * covered: for {@code \p{Lu}} this adds the lowercase counterparts ('a'
     * for 'A'), for {@code \p{Ll}} the uppercase ones ('A' for 'a'), and for
     * either the extra group members (ſ for s/S).
     *
     * <p>BMP only, matching the other tables' fold scope.
     */
    static int[] buildFoldTable(int[] table, IntFunction<int[]> foldFn) {
        ArrayList<int[]> ranges = new ArrayList<>();
        int rangeStart = -1;
        for (int cp = 0; cp <= 0xFFFF; cp++) {
            if (!inRange(table, cp) && foldEquivalentInRange(cp, table, foldFn)) {
                if (rangeStart < 0) {
                    rangeStart = cp;
                }
                continue;
            }
            if (rangeStart >= 0) {
                ranges.add(new int[]{rangeStart, cp - 1});
                rangeStart = -1;
            }
        }
        if (rangeStart >= 0) {
            ranges.add(new int[]{rangeStart, 0xFFFF});
        }
        if (ranges.isEmpty()) {
            return null;
        }
        return flatten(ranges);
    }

    /** True iff any fold-group member of {@code cp} (other than itself) is in the table. */
    private static boolean foldEquivalentInRange(int cp, int[] table, IntFunction<int[]> foldFn) {
        int[] fr = foldFn.apply(cp);
        if (fr == null) {
            return false;
        }
        for (int i = 0; i + 1 < fr.length; i += 2) {
            for (int m = fr[i]; m <= fr[i + 1]; m++) {
                if (m != cp && inRange(table, m)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean inRange(int[] table, int cp) {
        if (cp > 0xFFFF) {
            return false;
        }
        int lo = 0, hi = table.length / 2 - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (cp < table[2 * mid]) {
                hi = mid - 1;
            } else if (cp > table[2 * mid + 1]) {
                lo = mid + 1;
            } else {
                return true;
            }
        }
        return false;
    }

    // ---- category tables ----

    private Map<String, int[]> categories() {
        Map<String, int[]> m = categories;
        if (m == null) {
            synchronized (this) {
                m = categories;
                if (m == null) {
                    m = buildCategoryTables();
                    categories = m;
                }
            }
        }
        return m;
    }

    private static Map<String, int[]> buildCategoryTables() {
        // Bucket codepoints by category.
        Map<Byte, ArrayList<int[]>> byType = new HashMap<>();
        byte prevType = -1;
        int start = -1;
        for (int cp = 0; cp <= Character.MAX_CODE_POINT; cp++) {
            byte t = (byte) Character.getType(cp);
            if (t == prevType) {
                continue;
            }
            if (prevType >= 0 && start >= 0) {
                byType.computeIfAbsent(prevType, k -> new ArrayList<>()).add(new int[]{start, cp - 1});
            }
            prevType = t;
            start = cp;
        }
        if (prevType >= 0) {
            byType.computeIfAbsent(prevType, k -> new ArrayList<>()).add(new int[]{start, Character.MAX_CODE_POINT});
        }
        // Flatten per-category.
        Map<String, int[]> out = new HashMap<>();
        for (Map.Entry<Byte, ArrayList<int[]>> e : byType.entrySet()) {
            String name = CATEGORY_NAMES[e.getKey()];
            if (name == null) {
                continue;
            } // unused type byte
            out.put(name, flatten(e.getValue()));
        }
        // Build containers by merging sub-category ranges.
        for (Map.Entry<String, String[]> e : CONTAINERS.entrySet()) {
            ArrayList<int[]> merged = new ArrayList<>();
            for (String sub : e.getValue()) {
                int[] r = out.get(sub);
                if (r == null) {
                    continue;
                }
                for (int i = 0; i < r.length; i += 2) {
                    merged.add(new int[]{r[i], r[i + 1]});
                }
            }
            out.put(e.getKey(), flatten(merged));
        }
        return out;
    }

    // ---- script tables ----

    private Map<String, int[]> scripts() {
        Map<String, int[]> m = scripts;
        if (m == null) {
            synchronized (this) {
                m = scripts;
                if (m == null) {
                    m = buildScriptTables();
                    scripts = m;
                }
            }
        }
        return m;
    }

    private static Map<String, int[]> buildScriptTables() {
        Map<Character.UnicodeScript, ArrayList<int[]>> byScript = new HashMap<>();
        Character.UnicodeScript prev = null;
        int start = -1;
        for (int cp = 0; cp <= Character.MAX_CODE_POINT; cp++) {
            Character.UnicodeScript s = Character.UnicodeScript.of(cp);
            if (s == prev) {
                continue;
            }
            if (prev != null && start >= 0) {
                byScript.computeIfAbsent(prev, k -> new ArrayList<>()).add(new int[]{start, cp - 1});
            }
            prev = s;
            start = cp;
        }
        if (prev != null) {
            byScript.computeIfAbsent(prev, k -> new ArrayList<>()).add(new int[]{start, Character.MAX_CODE_POINT});
        }
        Map<String, int[]> out = new HashMap<>();
        for (Map.Entry<Character.UnicodeScript, ArrayList<int[]>> e : byScript.entrySet()) {
            // JDK enum: OLD_SOUTH_ARABIAN → re2j: Old_South_Arabian
            out.put(toScriptName(e.getKey().name()), flatten(e.getValue()));
        }
        return out;
    }

    /** {@code OLD_SOUTH_ARABIAN → Old_South_Arabian}. */
    private static String toScriptName(String enumName) {
        StringBuilder sb = new StringBuilder(enumName.length());
        boolean atWordStart = true;
        for (int i = 0; i < enumName.length(); i++) {
            char c = enumName.charAt(i);
            if (c == '_') {
                sb.append('_');
                atWordStart = true;
                continue;
            }
            if (atWordStart) {
                sb.append(c);
                atWordStart = false;
            } else {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
    }

    // ---- helpers ----

    private static int[] flatten(List<int[]> ranges) {
        // Merge adjacent / overlapping ranges, then flatten.
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

    /**
     * JDK 26's closing-characters clause for one compile range: a member
     * with an EXPANDED case relation (OHM SIGN, ı, ς, the Cyrillic
     * historical letters, ...) admits the case-relatives of its expansion
     * target even when neither image of the input lands in the range —
     * and ONLY those: the relation is directional ({@code [Ω-Ω]} does NOT
     * admit OHM SIGN back). The JDK's entry table is internal data, so it
     * is DISCOVERED from the live oracle once per JVM: every codepoint
     * whose java-reading fold orbit contains a member the image test
     * would not match against the single-member range is probed, and the
     * oracle's verdict defines the extras — future JDK entries surface
     * automatically wherever the fold table knows the group.
     */
    private int[] rangeClosingExtras(int lo, int hi) {
        int[] srcs = closingSrcs;
        int[][] extras = closingExtras;
        if (srcs == null) {
            synchronized (this) {
                if (closingSrcs == null) {
                    discoverClosingEntries();
                }
                srcs = closingSrcs;
                extras = closingExtras;
            }
        }
        int n = 0;
        int[] out = new int[16];
        for (int m = lo; m <= hi; m++) {
            int i = Arrays.binarySearch(srcs, m);
            if (i < 0) {
                continue;
            }
            for (int v : extras[i]) {
                if (v < lo || v > hi) {
                    if (n == out.length) {
                        out = Arrays.copyOf(out, out.length * 2);
                    }
                    out[n++] = v;
                }
            }
        }
        return Arrays.copyOf(out, n);
    }

    private void discoverClosingEntries() {
        // candidate pass: every codepoint with a java-reading fold orbit
        // is examined locally; only sources with an orbit member the image
        // test would miss reach the live probe
        HashMap<Integer, IntList> found = new HashMap<>();
        for (int cp = 0; cp <= Character.MAX_CODE_POINT; cp++) {
            int[] orbit = CaseFoldTable.foldRangesJava(cp);
            if (orbit == null) {
                continue;
            }
            boolean special = false;
            for (int oi = 0; oi < orbit.length && !special; oi += 2) {
                for (int o = orbit[oi]; o <= orbit[oi + 1]; o++) {
                    if (o != cp && !imageMatch(o, cp, cp)) {
                        special = true;
                        break;
                    }
                }
            }
            if (!special) {
                continue;
            }
            IntList ex = null;
            for (int oi = 0; oi < orbit.length; oi += 2) {
                for (int o = orbit[oi]; o <= orbit[oi + 1]; o++) {
                    if (o == cp || imageMatch(o, cp, cp) || !probe(singleRange(cp), codepointOf(o))) {
                        continue;
                    }
                    if (ex == null) {
                        ex = new IntList();
                    }
                    ex.add(o);
                }
            }
            if (ex != null && ex.size > 0) {
                found.put(cp, ex);
            }
        }
        int[] srcs = new int[found.size()];
        int[][] extras = new int[found.size()][];
        int i = 0;
        for (Map.Entry<Integer, IntList> e : found.entrySet()) {
            srcs[i] = e.getKey();
            extras[i] = e.getValue().trimmed();
            i++;
        }
        Integer[] idx = new Integer[found.size()];
        for (int j = 0; j < idx.length; j++) {
            idx[j] = j;
        }
        Arrays.sort(idx, (x, y) -> Integer.compare(srcs[x], srcs[y]));
        int[] s2 = new int[srcs.length];
        int[][] e2 = new int[extras.length][];
        for (int j = 0; j < idx.length; j++) {
            s2[j] = srcs[idx[j]];
            e2[j] = extras[idx[j]];
        }
        closingSrcs = s2;
        closingExtras = e2;
    }

    /** java's CI range image test for input {@code o} against the
     *  single-member range {@code [r, r]}: toUpper or toLower∘toUpper in
     *  range (the doubleMap clause is the same composition). */
    private static boolean imageMatch(int o, int rLo, int rHi) {
        int up = Character.toUpperCase(o);
        int lo = Character.toLowerCase(up);
        return (up >= rLo && up <= rHi) || (lo >= rLo && lo <= rHi);
    }

    private static String singleRange(int cp) {
        StringBuilder sb = new StringBuilder(14);
        sb.append('[');
        appendCodepoint(sb, cp);
        sb.append('-');
        appendCodepoint(sb, cp);
        sb.append(']');
        return sb.toString();
    }

    private static String codepointOf(int cp) {
        StringBuilder sb = new StringBuilder(2);
        appendCodepoint(sb, cp);
        return sb.toString();
    }

    private static void appendCodepoint(StringBuilder sb, int cp) {
        if (Character.isBmpCodePoint(cp)) {
            sb.append((char) cp);
        } else {
            sb.append(Character.highSurrogate(cp));
            sb.append(Character.lowSurrogate(cp));
        }
    }

    /** Tiny primitive growable list (init-path only). */
    private static final class IntList {
        int[] a = new int[4];
        int size;

        void add(int v) {
            if (size == a.length) {
                a = Arrays.copyOf(a, a.length * 2);
            }
            a[size++] = v;
        }

        int[] trimmed() {
            return Arrays.copyOf(a, size);
        }
    }
}