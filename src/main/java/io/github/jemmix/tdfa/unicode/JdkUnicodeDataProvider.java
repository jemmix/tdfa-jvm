package io.github.jemmix.tdfa.unicode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

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
 */
final class JdkUnicodeDataProvider implements UnicodeDataProvider {
    static final JdkUnicodeDataProvider INSTANCE = new JdkUnicodeDataProvider();

    private volatile Map<String, int[]> scripts;
    private volatile Map<String, int[]> categories;

    private JdkUnicodeDataProvider() {}

    @Override
    public int[] tableFor(String name) {
        if ("Any".equals(name)) return ANY_TABLE;
        // Try scripts first to match re2j lookup order? Actually re2j tries
        // CATEGORIES first then SCRIPTS. We follow the same order.
        Map<String, int[]> cats = categories();
        int[] t = cats.get(name);
        if (t != null) return t;
        Map<String, int[]> scr = scripts();
        return scr.get(name);
    }

    @Override
    public int[] foldTableFor(String name) {
        // Fold tables would require scanning 0..0x10FFFF with
        // Character.toLowerCase(int)/toUpperCase(int) and partitioning by the
        // fold target's category/script. Expensive and rarely used in real
        // patterns; return null (no extra codepoints from folding) for now.
        // Case-insensitive \p{X} therefore matches the same set as \p{X};
        // this is a known divergence from re2j but acceptable until the
        // re2j-compat tables jar is plugged in.
        return null;
    }

    private static final int[] ANY_TABLE = new int[]{0, Character.MAX_CODE_POINT};

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

    /** Maps each {@code byte} returned by {@link Character#getType(int)} to its
     *  two-letter Unicode general-category code; index is the byte value. */
    private static final String[] CATEGORY_NAMES = new String[31];
    static {
        CATEGORY_NAMES[Character.UPPERCASE_LETTER]            = "Lu";
        CATEGORY_NAMES[Character.LOWERCASE_LETTER]            = "Ll";
        CATEGORY_NAMES[Character.TITLECASE_LETTER]            = "Lt";
        CATEGORY_NAMES[Character.MODIFIER_LETTER]             = "Lm";
        CATEGORY_NAMES[Character.OTHER_LETTER]                = "Lo";
        CATEGORY_NAMES[Character.NON_SPACING_MARK]            = "Mn";
        CATEGORY_NAMES[Character.ENCLOSING_MARK]              = "Me";
        CATEGORY_NAMES[Character.COMBINING_SPACING_MARK]      = "Mc";
        CATEGORY_NAMES[Character.DECIMAL_DIGIT_NUMBER]        = "Nd";
        CATEGORY_NAMES[Character.LETTER_NUMBER]               = "Nl";
        CATEGORY_NAMES[Character.OTHER_NUMBER]                = "No";
        CATEGORY_NAMES[Character.SPACE_SEPARATOR]             = "Zs";
        CATEGORY_NAMES[Character.LINE_SEPARATOR]              = "Zl";
        CATEGORY_NAMES[Character.PARAGRAPH_SEPARATOR]         = "Zp";
        CATEGORY_NAMES[Character.CONTROL]                     = "Cc";
        CATEGORY_NAMES[Character.FORMAT]                      = "Cf";
        CATEGORY_NAMES[Character.PRIVATE_USE]                 = "Co";
        CATEGORY_NAMES[Character.SURROGATE]                   = "Cs";
        CATEGORY_NAMES[Character.DASH_PUNCTUATION]            = "Pd";
        CATEGORY_NAMES[Character.START_PUNCTUATION]           = "Ps";
        CATEGORY_NAMES[Character.END_PUNCTUATION]             = "Pe";
        CATEGORY_NAMES[Character.CONNECTOR_PUNCTUATION]       = "Pc";
        CATEGORY_NAMES[Character.OTHER_PUNCTUATION]           = "Po";
        CATEGORY_NAMES[Character.MATH_SYMBOL]                 = "Sm";
        CATEGORY_NAMES[Character.CURRENCY_SYMBOL]             = "Sc";
        CATEGORY_NAMES[Character.MODIFIER_SYMBOL]             = "Sk";
        CATEGORY_NAMES[Character.OTHER_SYMBOL]                = "So";
        CATEGORY_NAMES[Character.INITIAL_QUOTE_PUNCTUATION]   = "Pi";
        CATEGORY_NAMES[Character.FINAL_QUOTE_PUNCTUATION]     = "Pf";
        CATEGORY_NAMES[Character.UNASSIGNED]                  = "Cn";
    }

    /** Container categories (one-letter forms). Each maps to its sub-categories. */
    private static final Map<String, String[]> CONTAINERS = new HashMap<>();
    static {
        CONTAINERS.put("L", new String[]{"Lu","Ll","Lt","Lm","Lo"});
        CONTAINERS.put("M", new String[]{"Mn","Me","Mc"});
        CONTAINERS.put("N", new String[]{"Nd","Nl","No"});
        CONTAINERS.put("P", new String[]{"Pc","Pd","Ps","Pe","Pi","Pf","Po"});
        CONTAINERS.put("S", new String[]{"Sm","Sc","Sk","So"});
        CONTAINERS.put("C", new String[]{"Cc","Cf","Co","Cs","Cn"});
        CONTAINERS.put("Z", new String[]{"Zs","Zl","Zp"});
    }

    private static Map<String, int[]> buildCategoryTables() {
        // Bucket codepoints by category.
        Map<Byte, ArrayList<int[]>> byType = new HashMap<>();
        byte prevType = -1;
        int start = -1;
        for (int cp = 0; cp <= Character.MAX_CODE_POINT; cp++) {
            byte t = (byte) Character.getType(cp);
            if (t == prevType) continue;
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
        for (var e : byType.entrySet()) {
            String name = CATEGORY_NAMES[e.getKey()];
            if (name == null) continue; // unused type byte
            out.put(name, flatten(e.getValue()));
        }
        // Build containers by merging sub-category ranges.
        for (var e : CONTAINERS.entrySet()) {
            ArrayList<int[]> merged = new ArrayList<>();
            for (String sub : e.getValue()) {
                int[] r = out.get(sub);
                if (r == null) continue;
                for (int i = 0; i < r.length; i += 2) merged.add(new int[]{r[i], r[i + 1]});
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
            if (s == prev) continue;
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
        for (var e : byScript.entrySet()) {
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
            if (c == '_') { sb.append('_'); atWordStart = true; continue; }
            if (atWordStart) { sb.append(c); atWordStart = false; }
            else sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }

    // ---- helpers ----

    private static int[] flatten(ArrayList<int[]> ranges) {
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
            flat[2 * i]     = merged.get(i)[0];
            flat[2 * i + 1] = merged.get(i)[1];
        }
        return flat;
    }
}
