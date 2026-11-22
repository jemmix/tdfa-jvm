package io.github.jemmix.tdfa.core.parser;

import io.github.jemmix.tdfa.core.ast.Alphabet;
import io.github.jemmix.tdfa.core.ast.Ast;
import io.github.jemmix.tdfa.core.ast.CharClass;
import io.github.jemmix.tdfa.core.ast.GraphemeTables;
import io.github.jemmix.tdfa.core.budget.BudgetWeights;
import io.github.jemmix.tdfa.core.budget.Budgets;
import io.github.jemmix.tdfa.core.budget.FrameBudget;
import io.github.jemmix.tdfa.core.budget.WorkMeter;
import io.github.jemmix.tdfa.core.dialect.ClassTable;
import io.github.jemmix.tdfa.core.dialect.EscapeAlphabet;
import io.github.jemmix.tdfa.core.dialect.FlagAction;
import io.github.jemmix.tdfa.core.dialect.FlagBindings;
import io.github.jemmix.tdfa.core.dialect.RangeMath;
import io.github.jemmix.tdfa.core.parser.PatternSyntaxException;
import io.github.jemmix.tdfa.core.unicode.CaseFoldTable;
import io.github.jemmix.tdfa.core.unicode.ShorthandTables;
import io.github.jemmix.tdfa.core.unicode.UnicodeDataProvider;
import io.github.jemmix.tdfa.core.unicode.UnicodeProviders;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.text.Normalizer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hand-rolled iterative-descent parser for a PCRE-ish subset:
 * - literals, escape sequences (\n \t \r \f \a \v \d \D \w \W \s \S)
 * - java.util.regex-only escapes, accepted when the ESCAPES_RE2 axis is
 *   unset (the JUR reading; re2j's alphabet rejects them): \e (ESC),
 *   \R (any Unicode linebreak), \cX (control char), \N{NAME} (named
 *   codepoint), \Z (end, before final terminator), and the \v/\V
 *   vertical- and \h/\H horizontal-whitespace class tables (under the
 *   axis \v is the single unit U+000B and the rest reject)
 * - hex escapes (\xNN, \x{N+}), octal escapes (\NNN, 1–3 digits capped at 0xFF),
 *   Unicode escapes (backslash-u-XXXX — BMP values incl. lone surrogates,
 *   java's adjacent high+low escape pairs combining to one supplementary
 *   codepoint)
 * - char classes [abc], [a-z], [^...], with POSIX classes [:alpha:] etc.
 * - Unicode property classes \p{X} \P{X} (full codepoint range, U+0000–U+10FFFF)
 * - quantifiers: * + ? {n} {n,} {n,m} (greedy and lazy)
 * - alternation |
 * - capturing groups (...), non-capturing (?:...), named groups (?P<name>...) (?<name>...)
 * - inline flags (?i) (?s) (?m) (?-i) (?-s) (?i:...) (?is:...)
 * - anchors ^ $ \A \z \b \B
 * - dot . (excludes \n unless (?s))
 * - literal quoting \Q...\E
 * - non-BMP codepoints in \x{...} and \p{...} (surrogate-pair aware)
 *
 * Rejected at parse time (DFA-incompatible, also rejected by re2j):
 * - backreferences (\1), lookarounds (?=...) (?!...) (?<=...) (?<!...)
 * - atomic groups (?>...), possessive quantifiers (*+ ++ ?+ {n,m}+)
 * - \C (any byte)
 *
 * Pending re2j parity (see parity test suite):
 * - full Unicode case folding for arbitrary class ranges under (?i)
 *
 * Iterative descent: atoms, classes, escapes, and quantifiers are parsed by
 * plain loops/leaf calls, and group nesting pushes a {@link GroupFrame} on
 * an explicit stack instead of recursing ('(' opens a frame, ')' closes
 * it). Nesting depth therefore costs heap, not JVM stack, and is charged
 * to the transient compile RAM budget
 * (io.github.jemmix.tdfa.core.budget.FrameBudget) while the frames are live —
 * re2j's parser is iterative the same way, which is why it needs no depth
 * cap.
 */
public final class Parser {
    /** re2j's repeat-count cap: {n,m} with n or m over 1000 is "invalid
     *  repeat count" (verified against re2j 1.8). Also the parse-time bound
     *  on Tnfa's eager {n,m} desugaring — without it, a{500000000} OOMs the
     *  Builder before any determinization budget can fire. */
    private static final int MAX_REPEAT_COUNT = 1000;

    // ---- predefined classes ----

    private static final int[] R_DIGIT = {'0', '9'};
    private static final int[] R_WORD = {'a', 'z', 'A', 'Z', '0', '9', '_', '_'};
    // re2j's \s = [\t\n\f\r ] — note: no \v (U+000B), unlike POSIX [:space:]
    // and unlike java.util.regex's ASCII \s = [ \t\n\x0B\f\r] (the JDK
    // includes VT). Table-selected (the compile's space-table component:
    // ClassTable.SPACE_RE2 vs SPACE_JDK — the dialect package).

    // java.util.regex's \v/\V (VertWS) and \h/\H (HorizWS) — the JDK's
    // CharPredicates tables, live-JDK verified: \v is the CLASS
    // [\n\x0B\f\r\x85\u2028\u2029] (not U+000B — that reading is re2j's,
    // the ESCAPES_RE2 axis), \h is
    // [ \t\xA0\u1680\u180E\u2000-\u200A\u202F\u205F\u2060\u3000]. All-BMP
    // sets — no hasSupplementaryStarts flip for the positives; the
    // complements flip unconditionally (java's negate() drops the Bmp
    // marker, the uppercase-shorthand rule).
    private static final int[] R_VERT_WS_JDK =
        {'\n', '\n', 0x0B, 0x0B, 0x0C, 0x0C, '\r', '\r', 0x85, 0x85, 0x2028, 0x2028, 0x2029, 0x2029};

    private static final int[] R_HORIZ_WS = {' ', ' ', '\t', '\t', 0xA0, 0xA0, 0x1680, 0x1680, 0x180E, 0x180E, 0x2000,
        0x200A, 0x202F, 0x202F, 0x205F, 0x205F, 0x2060, 0x2060, 0x3000, 0x3000};

    // POSIX character classes — ASCII-only, matching re2j's CharGroup tables.
    // Note: [:space:] INCLUDES \v (U+000B) per POSIX, unlike Perl's \s.
    private static final int[] R_POSIX_ALNUM = {'0', '9', 'A', 'Z', 'a', 'z'};
    private static final int[] R_POSIX_ALPHA = {'A', 'Z', 'a', 'z'};
    private static final int[] R_POSIX_ASCII = {0, 0x7F};
    private static final int[] R_POSIX_BLANK = {'\t', '\t', ' ', ' '};
    private static final int[] R_POSIX_CNTRL = {0, 0x1F, 0x7F, 0x7F};
    private static final int[] R_POSIX_DIGIT = {'0', '9'};
    private static final int[] R_POSIX_GRAPH = {0x21, 0x7E};
    private static final int[] R_POSIX_LOWER = {'a', 'z'};
    private static final int[] R_POSIX_PRINT = {' ', 0x7E};
    private static final int[] R_POSIX_PUNCT = {0x21, '/', ':', '@', '[', '`', '{', '~'};
    private static final int[] R_POSIX_SPACE = {'\t', '\r', ' ', ' '}; // \t\n\v\f\r and space
    private static final int[] R_POSIX_UPPER = {'A', 'Z'};
    private static final int[] R_POSIX_WORD = {'0', '9', 'A', 'Z', '_', '_', 'a', 'z'};
    private static final int[] R_POSIX_XDIGIT = {'0', '9', 'A', 'F', 'a', 'f'};

    // The shorthand/dot constants moved to the meaning-resolution pass
    // (core.semantic.Resolve) with their expansions; the parser keeps the
    // raw ASCII tables its grammar-owned materializations consume
    // (R_DIGIT/R_WORD for the \p fallbacks, R_VERT_WS_JDK/R_HORIZ_WS for
    // the JDK escape alphabet's class tables).

    /**
     * java.util.regex's {@code hasSupplementary(pattern)}: whether an
     * unanchored {@code find()} there scans candidate starts by codepoint
     * (the {@code StartS} node — interiors of well-formed surrogate pairs
     * are never tried after the first candidate) or by UTF-16 unit (plain
     * {@code Start}). Frozen onto the artifact and OR-ed into the runner's
     * scan pivot beside the CODEPOINT_BOUNDARIES axis. Mirrors the JDK's
     * exact flip rules (live-JDK verified):
     * <ul>
     * <li>any surrogate unit in the pattern SOURCE (a raw pair decodes to a
     *     supplementary codepoint, a lone unit is its own codepoint — the
     *     JDK's whole-pattern codepoint scan);</li>
     * <li>a SINGLE-CHAR literal atom (run of length one) whose codepoint is
     *     supplementary/lone (java's {@code SingleS}), or that case-folds
     *     under the full fold universe (java's {@code SingleU}) — a
     *     multi-char literal run compiles to a Slice, which never flips
     *     the flag (the slice quirk: {@code \x{1F600}} flips,
     *     {@code \x{1F600}x} does not);</li>
     * <li>any complement (user {@code [^...]}, {@code \W \D \S \P{...}},
     *     {@code [:^...:]}): java's negate() loses the Bmp marker — "too
     *     expensive to detect", unconditional;</li>
     * <li>a written class range {@code lo-hi} that is not provably BMP
     *     (java's {@code Range} Bmp test) — and ANY written range under
     *     case-insensitivity (java's {@code CIRange}/{@code CIRangeU} are
     *     full predicates);</li>
     * <li>any {@code \p{...}} family predicate except the ASCII-family
     *     names java builds as Bmp ({@code ASCII}, {@code L1} always; the
     *     POSIX names only without {@code (?u)}); a shorthand
     *     {@code \d \w \s} flips only under {@code (?u)} (the UCC variants
     *     are full predicates);</li>
     * <li>never the bare {@code .} (java builds it with a direct
     *     {@code new CharProperty(DOT())} that bypasses the flip).</li>
     * </ul>
     */
    boolean hasSupplementaryStarts;

    /** Literal-run detection for the slice quirk (see
     *  {@link #hasSupplementaryStarts}): java's atom() consumes a maximal
     *  run of literal chars into one Slice (which never flips the flag)
     *  and unwinds one char when a quantifier follows — so a literal's
     *  single-char flip applies only when neither a neighboring literal
     *  nor a quantifier makes it part of a longer unit. Tracked streaming:
     *  every concat part arrival feeds {@link #partArrived}. */
    private boolean runSolo;

    private boolean runSoloCand;

    private boolean runMulti;

    /** Set by the literal productions (parseAtom's literal branch,
     *  parseEscape's literal returns, parseQuotedLiteral) before the part
     *  returns to the concat loop: 0 = not a literal part, 1 = literal
     *  with no flip candidate, 2 = literal that flips when single. */
    private int literalKind;

    private final String src;
    private int pos = 0;
    private int nextTag = 1;
    private int groupCount = 0;
    private final Map<String, Integer> groupNames = new LinkedHashMap<>();
    boolean caseInsensitive = false;
    boolean dotall = false;
    boolean multiline = false;
    /** Scoped UNIX_LINES state ({@code (?d)}): the terminator set for
     *  ^/$/dot inside the current scope (docs/scoped-unix-lines.md). */
    boolean unixLines = false;
    /** Whether any {@code (?d)}/{@code (?-d)} letter was seen in the
     *  current scope — distinguishes an EXPLICIT off (the OTHER family
     *  under a unix-default compile) from the untouched default. */
    boolean dScoped = false;
    /** re2j's U flag: quantifiers default to lazy, a trailing ? makes them greedy. */
    boolean ungreedy = false;

    /** Free-spacing mode (the COMMENTS flag / {@code (?x)} letter, JUR
     *  lane): whitespace between tokens and {@code #}-to-end-of-line
     *  comments are skipped — java.util.regex's {@code ASCII.isSpace}
     *  set ({@code ' ' \t \n \x0B \f \r}), comments ending at any of
     *  {@code \n \r \u2028 \u2029} (java's {@code parsePastLine}).
     *  Scoped like every inline flag; escaped whitespace and
     *  {@code \Q...\E} bodies are immune (their readers never skip). */
    boolean comments = false;

    /** java's {@code matchRoot instanceof Begin} fact: whether the pattern
     *  opens with a BARE root begin anchor ({@code \A}, or {@code ^} parsed
     *  non-multiline) with nothing group-shaped before it and no root
     *  alternation — the exact shape java compiles WITHOUT its Start
     *  wrapper (a failed search then never reports the Start rule's
     *  hitEnd). Live-JDK pinned: {@code (?i)\Aa} keeps it (flag-only
     *  groups dissolve), while {@code (?:\A)a}/{@code (\A)a}/
     *  {@code \Aa|\Ab} lose it (any body-bearing group or a root Alt
     *  heads the sequence). Tri-state while parsing: 0 nothing seen at
     *  the root yet, 1 the root's first part was the bare anchor, 2
     *  something else; {@link #rootBeginAnchor} freezes the verdict. */
    private int rootBeginState;

    /** The frozen {@link #rootBeginState} verdict (see there): the root's
     *  first part was the bare begin anchor, no body-bearing group before
     *  it, no root alternation. */
    private boolean rootBeginAnchor;

    /** A '|' arrived at the root level — the root alternation heads the
     *  sequence (java wraps in Start whatever the branches pin). */
    private boolean rootBeginAlternated;

    /** The active fold universe at the {@link #foldUniverse} seam (the
     *  UNICODE_CASE axis): true folds the full Unicode simple-fold
     *  universe — the RE2-lineage reading, re2j folds full under plain
     *  (?i); false folds ASCII only until {@code (?u)} widens it (the
     *  java.util.regex bare-CI reading; JDK {@code UNICODE_CHARACTER_CLASS}
     *  implies Unicode-aware CI, so the UCC flag widens the fold universe
     *  exactly like UNICODE_CASE — see {@link #foldUniverse}). Initialized
     *  from the compile's axis projection; a {@code (?U)} under the
     *  java.util.regex reading upgrades it from that point on — scoped
     *  like every inline flag since v2 (saved/restored per
     *  {@link GroupFrame}, so {@code (?U:...)}/{@code ((?U)x)} restore at
     *  their ')' exactly like {@code (?i)} does). */
    boolean unicodeCase;

    boolean disableUnicodeGroups = false;
    boolean unicodeShorthand = false;
    UnicodeDataProvider provider;
    /** The compile's parser-side pivot knobs (see {@link ParseOptions}) —
     *  projected from the compile's Semantics by {@code Tnfa.compile}:
     *  the DOT terminator set (UNIX_LINES), the fold universe
     *  (UNICODE_CASE) and the inline-flag letter bindings (the
     *  {@code (?U)} meaning rides them). Legacy overloads
     *  parse the axis-set selection. */
    final ParseOptions options;
    /** Front-end work meter shared with the TNFA builder (see
     *  {@code Tnfa.compile}); meters the O(universe) fold-range scan. */
    final WorkMeter meter;
    /** {@link Character#codePointOf(String)} (@since 9), or {@code null}
     *  on a Java 8 runtime — see {@link #readNamedCodepoint()}. */
    private static final Method CODE_POINT_OF = lookupCodePointOf();

    // Lazily-merged Unicode class caches (see unicodeWordRanges()/unicodeDigitRanges()).
    private int[] cachedUnicodeWord;
    /** The grapheme-break tables, resolved lazily at the first \b{g}
     *  (rides ParseResult → Tnfa → Tdfa — the runner's cluster scanner). */
    private GraphemeTables graphemeTables;
    private int[] cachedUnicodeDigit;

    private Parser(String src, boolean disableUnicodeGroups, UnicodeDataProvider provider, WorkMeter meter,
        ParseOptions options) {
        this.src = src;
        this.disableUnicodeGroups = disableUnicodeGroups;
        this.provider = provider;
        this.meter = meter;
        this.options = options;
        this.unicodeCase = options.isUnicodeCase();
        // Rule (a): java scans the whole pattern source codepoint-wise; any
        // surrogate unit — a well-formed pair as its supplementary codepoint,
        // a lone unit as itself — flips the flag. A unit-level scan over the
        // source is equivalent (a pair contains a high unit).
        for (int i = 0; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c >= 0xD800 && c <= 0xDFFF) {
                hasSupplementaryStarts = true;
                break;
            }
        }
    }

    /** Parse to the SYNTAX tree — symbolic atoms ({@code Ast.Dot},
     *  {@code Ast.FoldLiteral}, {@code Ast.Shorthand}) appear as nodes and
     *  expand only at meaning resolution (the compile pipeline applies it;
     *  see {@code core.semantic.Resolve}). Consumers wanting resolved
     *  classes walk the compile's output, not this entry.
     */
    public static Ast parse(String src) {
        return parseResult(src, false, false, UnicodeProviders.get()).ast();
    }

    /** Parse and return the full result: AST plus tag/group counters, effective
     *  flags, and named-group metadata (the composable-pipeline entry point).
     *  Meters against a fresh compile CPU budget. */
    public static ParseResult parseResult(String src, boolean disableUnicodeGroups, boolean anchorBoth,
        UnicodeDataProvider provider) {
        return parseResult(src, disableUnicodeGroups, anchorBoth, provider,
            new WorkMeter(Budgets.compileComputeTicks()));
    }

    /** Metered variant: the caller (Tnfa.compile) shares one CPU budget
     *  across parse and TNFA construction. Legacy semantics: every axis set
     *  (the RE2-lineage reading). */
    public static ParseResult parseResult(String src, boolean disableUnicodeGroups, boolean anchorBoth,
        UnicodeDataProvider provider, WorkMeter meter) {
        return parseResult(src, disableUnicodeGroups, anchorBoth, ParseOptions.re2Lineage(), provider, meter);
    }

    /** Fullest entry: the parser-side pivot knobs included (see
     *  {@link ParseOptions}); {@code Tnfa.compile} projects the compile's
     *  Semantics onto them. */
    public static ParseResult parseResult(String src, boolean disableUnicodeGroups, boolean anchorBoth,
        ParseOptions options, UnicodeDataProvider provider, WorkMeter meter) {
        Parser p = new Parser(src, disableUnicodeGroups, provider, meter, options);
        Ast e = p.parseAlt();
        if (p.pos != p.src.length()) {
            throw fail(p, "unexpected '" + p.cur() + "'");
        }
        e = anchorBoth ? anchorBoth(e) : e;
        if (options.isCanonEq()) {
            e = rewriteCanonEq(e);
        }
        return new ParseResult(e, p.tagCount(), p.groupCount(), p.multiline(), p.unicodeShorthand(),
            p.unicodeWordRanges(), p.namedGroups(), p.hasSupplementaryStarts, p.rootBeginAnchor, p.graphemeTables);
    }

    /**
     * The canonical-equivalence rewrite (the jur facade's CANON_EQ flag —
     * java's own PATTERN-side mechanism, live-JDK probed): every literal
     * codepoint with a canonical decomposition, and every single-codepoint
     * class member, expands to an alternation of its NFC and NFD
     * spellings — a decomposed pattern matches the composed input and
     * vice versa, the base alone matches neither, and order is
     * significant. Negated classes, ranges with decomposable endpoints
     * (java's isrange family) and the fold-literal interaction stay
     * verbatim — battery-pinned as the landed surface.
     */
    private static Ast rewriteCanonEq(Ast e) {
        if (e instanceof Ast.Concat) {
            Ast.Concat c = (Ast.Concat) e;
            List<Ast> out = new ArrayList<>(c.children.size());
            boolean changed = false;
            int i = 0;
            while (i < c.children.size()) {
                // A canonical RUN: a symbol followed by combining marks
                // (Mn/Mc/Me approximation of ccc>0 — a run that does not
                // compose is kept verbatim, so over-grouping is inert).
                if (c.children.get(i) instanceof Ast.Symbol) {
                    int j = i + 1;
                    while (j < c.children.size() && c.children.get(j) instanceof Ast.Symbol
                        && isCombining(((Ast.Symbol) c.children.get(j)).c)) {
                        j++;
                    }
                    if (j > i + 1 || hasDecomposition(((Ast.Symbol) c.children.get(i)).c)) {
                        StringBuilder raw = new StringBuilder();
                        for (int t = i; t < j; t++) {
                            raw.append(((Ast.Symbol) c.children.get(t)).c);
                        }
                        Ast x = expandSequence(raw.toString());
                        if (x != null) {
                            out.add(x);
                            changed = true;
                            i = j;
                            continue;
                        }
                    }
                }
                Ast r = rewriteCanonEq(c.children.get(i));
                changed |= r != c.children.get(i);
                out.add(r);
                i++;
            }
            return changed ? new Ast.Concat(out) : e;
        }
        if (e instanceof Ast.Alt) {
            Ast.Alt a = (Ast.Alt) e;
            List<Ast> out = new ArrayList<>(a.children.size());
            boolean changed = false;
            for (Ast ch : a.children) {
                Ast r = rewriteCanonEq(ch);
                changed |= r != ch;
                out.add(r);
            }
            return changed ? new Ast.Alt(out) : e;
        }
        if (e instanceof Ast.Repeat) {
            Ast.Repeat r = (Ast.Repeat) e;
            Ast body = rewriteCanonEq(r.body);
            if (body == r.body) {
                return e;
            }
            Ast.Repeat nr = new Ast.Repeat(body, r.min, r.max, r.greedy);
            nr.possessive = r.possessive;
            nr.zeroWidthExit = r.zeroWidthExit;
            nr.quantifiedCapture = r.quantifiedCapture;
            return nr;
        }
        if (e instanceof Ast.ClassExpr) {
            Ast.ClassExpr ce = (Ast.ClassExpr) e;
            if (ce.negated) {
                return e;
            }
            List<Ast> alts = null;
            List<Ast.ClassExpr.Item> kept = null;
            for (int idx = 0; idx < ce.items.size(); idx++) {
                Ast.ClassExpr.Item item = ce.items.get(idx);
                if (!(item instanceof Ast.ClassExpr.Member)) {
                    continue;
                }
                Ast.ClassExpr.Member m = (Ast.ClassExpr.Member) item;
                if (m.lo != m.hi || m.written) {
                    continue;
                }
                // Adjacent single members forming a canonical RUN (base +
                // combining marks): java's class-content normalization —
                // the run's expansion joins the outer alternation, the
                // members leave the class.
                int j = idx + 1;
                while (j < ce.items.size() && ce.items.get(j) instanceof Ast.ClassExpr.Member) {
                    Ast.ClassExpr.Member m2 = (Ast.ClassExpr.Member) ce.items.get(j);
                    if (m2.lo != m2.hi || m2.written || !isCombining((char) m2.lo)) {
                        break;
                    }
                    j++;
                }
                StringBuilder raw = new StringBuilder();
                for (int t = idx; t < j; t++) {
                    raw.appendCodePoint(((Ast.ClassExpr.Member) ce.items.get(t)).lo);
                }
                Ast x = j > idx + 1 ? expandSequence(raw.toString()) : expandCp(m.lo, null);
                if (x != null) {
                    if (alts == null) {
                        alts = new ArrayList<>(2);
                        kept = new ArrayList<>(ce.items);
                    }
                    alts.add(x);
                    kept.subList(idx, j).clear();
                    idx = j - 1;
                }
            }
            if (alts == null) {
                return e;
            }
            if (!kept.isEmpty()) {
                alts.add(new Ast.ClassExpr(kept, false, ce.ci, ce.uni));
            }
            return alts.size() == 1 ? alts.get(0) : new Ast.Alt(alts);
        }
        if (e instanceof Ast.Symbol) {
            return expandCp(((Ast.Symbol) e).c, e);
        }
        if (e instanceof CharClass) {
            CharClass cc = (CharClass) e;
            if (cc.negated || cc.ranges.length == 0) {
                return e;
            }
            List<Ast> alts = null;
            int[] kept = new int[cc.ranges.length];
            int k = 0;
            for (int i = 0; i < cc.ranges.length; i += 2) {
                int lo = cc.ranges[i], hi = cc.ranges[i + 1];
                if (lo == hi) {
                    Ast x = expandCp(lo, null);
                    if (x != null) {
                        if (alts == null) {
                            alts = new ArrayList<>(2);
                        }
                        alts.add(x);
                        continue;
                    }
                }
                kept[k++] = lo;
                kept[k++] = hi;
            }
            if (alts == null) {
                return e;
            }
            if (k > 0) {
                alts.add(new CharClass(Arrays.copyOf(kept, k), false));
            }
            return alts.size() == 1 ? alts.get(0) : new Ast.Alt(alts);
        }
        return e;
    }

    /**
     * One codepoint's canonical expansion, or null when it has none (the
     * NFC and NFD spellings are identical). The alternation prefers the
     * composed spelling — the oracle's span on composed inputs.
     */
    private static Ast expandCp(int cp, Ast original) {
        if (!hasDecomposition(cp)) {
            return original;
        }
        return expandSequence(new String(Character.toChars(cp)));
    }

    private static boolean hasDecomposition(int cp) {
        String src = new String(Character.toChars(cp));
        return !Normalizer.isNormalized(src, Normalizer.Form.NFC) || !Normalizer.isNormalized(src, Normalizer.Form.NFD);
    }

    private static boolean isCombining(char c) {
        int t = Character.getType(c);
        return t == Character.NON_SPACING_MARK || t == Character.COMBINING_SPACING_MARK
            || t == Character.ENCLOSING_MARK;
    }

    /** A raw codepoint sequence's canonical expansion, or null when its
     *  NFC and NFD spellings are identical (nothing to alternate). */
    private static Ast expandSequence(String raw) {
        String nfc = Normalizer.normalize(raw, Normalizer.Form.NFC);
        String nfd = Normalizer.normalize(raw, Normalizer.Form.NFD);
        if (nfc.equals(nfd)) {
            return null;
        }
        return new Ast.Alt(Arrays.asList(spell(nfc), spell(nfd)));
    }

    /** A codepoint sequence as a concat of single-codepoint atoms. */
    private static Ast spell(String s) {
        List<Ast> parts = new ArrayList<>(s.codePointCount(0, s.length()));
        for (int i = 0; i < s.length();) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            parts.add(cp <= 0xFFFF ? new Ast.Symbol((char) cp) : new CharClass(new int[]{cp, cp}, false));
        }
        return parts.size() == 1 ? parts.get(0) : new Ast.Concat(parts);
    }

    /** Wrap a parsed body in start/end-text anchors (matches() = anchored both ends).
     *  Done at AST level so literal-quote ({@code \Q...\E}) in the body isn't disturbed,
     *  and the trailing anchor supplies context that prevents the Perl leftmost-first
     *  DFA from pruning a longer alternative's continuation. */
    private static Ast anchorBoth(Ast e) {
        return new Ast.Concat(Collections.unmodifiableList(Arrays.asList(
            new Ast.Assertion(Ast.AssertionKind.TEXT_START), e, new Ast.Assertion(Ast.AssertionKind.TEXT_END))));
    }

    /** alt := concat ('|' concat)* — the outer parse loop. The stack holds
     *  one {@link GroupFrame} per open group plus the root frame; '(' opens
     *  a group (openGroup), ')' closes and assembles it (closeGroup), '|'
     *  starts the next branch of the current frame's alternation. */
    private Ast parseAlt() {
        FrameBudget frames = FrameBudget.create();
        Deque<GroupFrame> stack = new ArrayDeque<>();
        stack.push(new GroupFrame());
        while (true) {
            GroupFrame f = stack.peek();
            skipComments();
            char c = peek();
            if (pos < src.length() && c != '|' && c != ')') {
                if (c == '(') {
                    Ast flagOnly = openGroup(stack, frames);
                    if (flagOnly != null) {
                        f.parts.add(applyQuantifier(flagOnly));
                        partArrived(0, false); // a flag-only group is never a literal
                        // flag-only groups dissolve in java's AST: (?i)\Aa
                        // keeps the root-Begin shape — no rootBeginState move.
                    }
                } else {
                    Ast part = parseRepeat();
                    f.parts.add(part);
                    partArrived(literalKind, part instanceof Ast.Repeat);
                    if (stack.size() == 1 && rootBeginState == 0) {
                        // The bare root begin anchor: \A, or a ^ parsed outside
                        // multiline — exactly the TEXT_START reading.
                        rootBeginState =
                            part instanceof Ast.Assertion && ((Ast.Assertion) part).kind == Ast.AssertionKind.TEXT_START
                                ? 1 : 2;
                    }
                }
                continue;
            }
            if (c == '|') {
                pos++;
                if (stack.size() == 1) {
                    rootBeginAlternated = true; // a root Alt heads the sequence — java Start-wraps
                }
                flushLiteralRun();
                f.alts.add(finishConcat(f.parts));
                f.parts = new ArrayList<>();
                continue;
            }
            if (c == ')') {
                // ')' with no open group is not this loop's to consume:
                // leave it — parseResult reports the trailing junk
                if (stack.size() == 1) {
                    break;
                }
                closeGroup(stack, frames);
                continue;
            }
            // end of input inside one or more groups: the innermost is unclosed
            if (stack.size() > 1) {
                expect(')');
            }
            break;
        }
        flushLiteralRun();
        GroupFrame root = stack.pop();
        root.alts.add(finishConcat(root.parts));
        rootBeginAnchor = rootBeginState == 1 && !rootBeginAlternated;
        return root.alts.size() == 1 ? root.alts.get(0) : new Ast.Alt(root.alts);
    }

    /**
     * One concat part arrived (source order): feed the literal-run
     * detector (the slice quirk, see {@link #hasSupplementaryStarts}).
     * {@code kind} is the literal production's {@link #literalKind}
     * (consumed here); {@code quantified} marks a quantifier on the part —
     * java's atom() unwinds the quantified char out of a literal run, so a
     * quantified literal is always a single-char atom (its candidate
     * applies) and it closes the run before it (that prefix keeps its own
     * slice/single verdict).
     */
    private void partArrived(int kind, boolean quantified) {
        boolean partLit = kind != 0;
        if (!partLit) {
            flushLiteralRun();
            return;
        }
        if (quantified) {
            flushLiteralRun();
            if (kind == 2) {
                hasSupplementaryStarts = true; // single-char atom (java's unwind)
            }
            return;
        }
        if (runMulti) {
            return; // the segment is already a slice: no member flips
        }
        if (runSolo) {
            runMulti = true; // second literal: java's Slice, no flip
            runSolo = false;
            return;
        }
        runSolo = true;
        runSoloCand = kind == 2;
    }

    /** The current literal segment ended: a run of exactly one member is a
     *  single-char atom in java (its flip candidate applies); a run of two
     *  or more compiled to a Slice and flips nothing. */
    private void flushLiteralRun() {
        if (runSolo && runSoloCand) {
            hasSupplementaryStarts = true;
        }
        runSolo = false;
        runMulti = false;
    }

    /** java's {@code isSupplementary}: a supplementary codepoint or a lone
     *  surrogate unit (both compile to non-Bmp predicates — SingleS). */
    private static boolean isSupplementaryCp(int cp) {
        return cp >= 0x10000 || (cp >= 0xD800 && cp <= 0xDFFF);
    }

    /**
     * java's {@code single()} flip verdict for one literal codepoint: a
     * supplementary/lone value always flips (SingleS); under
     * case-insensitivity with the full fold universe a codepoint with case
     * counterparts flips too (SingleU is a full predicate) — under the
     * ASCII universe java's SingleI stays Bmp and never flips.
     */
    private boolean singleLiteralFlips(int cp) {
        if (isSupplementaryCp(cp)) {
            return true;
        }
        return caseInsensitive && (unicodeCase || unicodeShorthand) && foldUniverse(cp) != null;
    }

    /** {@link #literalKind} for one literal codepoint (see
     *  {@link #singleLiteralFlips}). */
    private int literalKindOf(int cp) {
        return singleLiteralFlips(cp) ? 2 : 1;
    }

    /** concat := repeat* (no explicit separator) — the single-branch shape;
     *  branches in progress live in the current {@link GroupFrame}. */
    private static Ast finishConcat(List<Ast> parts) {
        return parts.isEmpty() ? new Ast.Empty() : (parts.size() == 1 ? parts.get(0) : new Ast.Concat(parts));
    }

    /** repeat := atom quantifier? */
    private Ast parseRepeat() {
        return applyQuantifier(parseAtom());
    }

    /** Quantifier tail of {@code repeat := atom quantifier?} — shared by
     *  atoms parsed inline and group bodies assembled when their ')' pops.
     *  For groups this runs after the ')' has restored the enclosing
     *  frame's flags, so a lazy '?' suffix reads the enclosing group's
     *  ungreedy state, not the body's. */
    private Ast applyQuantifier(Ast atom) {
        skipComments();
        if (pos >= src.length()) {
            return atom;
        }
        char c = peek();
        int min, max;
        if (c == '*') {
            pos++;
            min = 0;
            max = Integer.MAX_VALUE;
        } else if (c == '+') {
            pos++;
            min = 1;
            max = Integer.MAX_VALUE;
        } else if (c == '?') {
            pos++;
            min = 0;
            max = 1;
        } else if (c == '{') {
            int[] bounds = parseBraces();
            if (bounds == null) {
                return atom;
            }
            min = bounds[0];
            max = bounds[1];
        } else {
            return atom;
        }

        // Greedy by default; re2j's U flag inverts the default (a trailing ?
        // then selects the OTHER mode, per RE2 ungreedy semantics).
        boolean greedy = !ungreedy;
        if (pos < src.length() && peek() == '?') {
            pos++;
            greedy = ungreedy;
        }
        // Possessive suffix (the JUR reading only — re2j's alphabet has no
        // possessive quantifiers): maximal munch, never give back. Parsed
        // BEFORE the nested-repetition guard so a++ is the possessive plus
        // and a+++ / a++? still reject as nested (java's own shape).
        boolean possessive = false;
        if (pos < src.length() && peek() == '+' && options.escapes() == EscapeAlphabet.JDK) {
            pos++;
            possessive = true;
        }

        // A quantifier directly after a completed quantifier (greedy, lazy,
        // possessive, or a second {n,m}) is "invalid nested repetition
        // operator" in re2j — never a silent literal `{2}` append or a
        // separate "possessive" error. `a??`/`a*?` stay legal: the lazy `?`
        // was consumed above. Probing {..} must not consume on the error path
        // (it throws), and an INVALID brace form (e.g. `a*{`) stays a literal.
        if (pos < src.length()) {
            char n = peek();
            if (n == '*' || n == '+' || n == '?') {
                throw fail(this, "invalid nested repetition operator");
            }
            if (n == '{' && parseBraces() != null) {
                throw fail(this, "invalid nested repetition operator");
            }
        }
        // The zeroWidthExit mark (finding 2 + its unbounded mirror) moved to
        // the meaning-resolution pass (core.semantic.Resolve): the shape
        // tests (nullableBody/dissolvableBody) live beside it there, keyed on
        // the compile's EMPTY_ITERATION_SPANS policy instead of a parse-side
        // axis.
        Ast.Repeat r = new Ast.Repeat(atom, min, max, greedy);
        r.possessive = possessive;
        return r;
    }

    /**
     * Expands a literal codepoint under case-insensitive mode into a CharClass,
     * or returns {@code null} if the codepoint should remain a plain literal
     * (no case-fold equivalents). Simple folding via the active fold universe
     * (see {@link #foldUniverse(int)}), supplementary codepoints included;
     * under the full universe the Turkic İ/ı pair is fold-inert by
     * simple-fold semantics (see {@link CaseFoldTable}).
     *
     * <p>Since the compat-surface rung-2 split this lives in the
     * meaning-resolution pass ({@code core.semantic.Resolve}, the
     * {@code Ast.FoldLiteral} expansion); the parse emits the symbolic node
     * whenever the scoped {@code (?i)} is active at a folding site.
     */

    /** Fold ranges for {@code cp} under the active fold universe — the
     *  UNICODE_CASE seam. ASCII-only when neither the axis nor
     *  {@code (?u)} selects the full universe: the java.util.regex
     *  bare-CI reading (JDK-exact: the 26 letter pairs, nothing else —
     *  and JDK-verified that {@code UNICODE_CHARACTER_CLASS} widens CI
     *  to Unicode-aware folding exactly like {@code UNICODE_CASE}, so
     *  {@code (?u)} widens this universe too). Otherwise the full
     *  universe — the provider's own when it supplies one (parity tiers
     *  pin folding to their Unicode snapshot, the oracle bridge to the
     *  live oracle), else the built-in JDK-derived universe. The
     *  {@code \p{...}} fold twin ({@code foldTableFor}) is a different
     *  seam and stays universe-full in both lanes: the JDK folds
     *  property classes under bare CI already (verified against a live
     *  java.util.regex: {@code (?i)\p{Lu}} matches {@code é} without
     *  UNICODE_CASE). */
    private int[] foldUniverse(int cp) {
        return CaseFoldTable.universeFold(cp, unicodeCase || unicodeShorthand, provider);
    }

    /** Atom for one literal codepoint. BMP stays {@link Ast.Symbol}; a
     *  supplementary codepoint becomes a single-codepoint {@link CharClass},
     *  the same shape {@code \x{...}} produces, because the engine's alphabet
     *  is codepoints — one transition consumes exactly one. */
    private static Ast literalAtom(int cp) {
        return cp <= 0xFFFF ? new Ast.Symbol((char) cp) : new CharClass(new int[]{cp, cp}, false);
    }

    /** atom := class | dot | anchor | escape | literal — groups are handled
     *  by the caller ({@link #parseAlt()}'s loop) so nesting never recurses. */
    private Ast parseAtom() {
        literalKind = 0; // the literal branches below raise it (run detector)
        char c = cur();
        if (c == '[') {
            pos++;
            return parseClass();
        }
        if (c == '.') {
            pos++;
            // The dot stays SYMBOLIC at this tier — the scoped (?s) state
            // rides the node — and the meaning-resolution pass
            // (core.semantic.Resolve) expands it against the compile's
            // terminator set: everything under dotall, the set's
            // complement otherwise (the UNIX_LINES axis). Either way the
            // bare dot never flips hasSupplementaryStarts (java's direct
            // CharProperty construction bypasses the flip).
            return new Ast.Dot(dotall, unixLines, dScoped && !unixLines && options.isCompileUnixDefault());
        }
        if (c == '^') {
            pos++;
            return new Ast.Assertion(this.multiline
                ? (this.unixLines ? Ast.AssertionKind.LINE_START_UNIX : (this.dScoped && options.isCompileUnixDefault()
                    ? Ast.AssertionKind.LINE_START_OTHER : Ast.AssertionKind.LINE_START))
                : Ast.AssertionKind.TEXT_START);
        }
        if (c == '$') {
            pos++;
            return new Ast.Assertion(this.multiline
                ? (this.unixLines ? Ast.AssertionKind.LINE_END_UNIX
                    : (this.dScoped && options.isCompileUnixDefault() ? Ast.AssertionKind.LINE_END_OTHER
                        : Ast.AssertionKind.LINE_END))
                : (this.unixLines ? Ast.AssertionKind.DOLLAR_UNIX : (this.dScoped && options.isCompileUnixDefault()
                    ? Ast.AssertionKind.DOLLAR_OTHER : Ast.AssertionKind.DOLLAR)));
        }
        if (c == '\\') {
            pos++;
            return parseEscape();
        }
        if (c == ')' || c == '|') {
            throw fail(this, "unexpected '" + c + "'");
        }
        // A quantifier at atom position (start of pattern, after '(' or '|')
        // has nothing to repeat: re2j fails "missing argument to repetition
        // operator". An INVALID brace form ({,2}) is still a literal '{'.
        if (c == '*' || c == '+' || c == '?') {
            throw fail(this, "missing argument to repetition operator");
        }
        if (c == '{') {
            if (parseBraces() != null) {
                throw fail(this, "missing argument to repetition operator");
            }
            // not a quantifier: fall through, '{' is a literal
        }
        int cp = Alphabet.decode(src, pos, src.length());
        pos += Alphabet.width(cp);
        literalKind = literalKindOf(cp);
        if (caseInsensitive) {
            // Symbolic under (?i): the meaning-resolution pass expands the
            // orbit (or demotes to the plain literal when the scoped fold
            // universe has no counterpart for the codepoint).
            return new Ast.FoldLiteral(cp, unicodeCase || unicodeShorthand);
        }
        return literalAtom(cp);
    }

    /** One open group on the explicit descent stack: the concat parts and
     *  completed alternation branches of its body, the tag pair allocated at
     *  its '(' (open-paren order), and the flags to restore at its ')'. */
    private static final class GroupFrame {
        List<Ast> parts = new ArrayList<>();
        List<Ast> alts = new ArrayList<>();
        boolean capturing;
        int open = -1, close = -1;
        boolean savedCi, savedDs, savedMl, savedUs, savedUc, savedUg, savedCx, savedDl, savedDsc;
    }

    /** group := '(' ('?:')? alt ')' | '(' '?flags' ')' | '(' '?flags:' alt ')' | '(' '?<' name '>' alt ')' | '(' '?P<' name '>' alt ')'
     *
     * <p>Called at '(' by {@link #parseAlt()}'s loop (the caller has already
     * peeked it). Either pushes a {@link GroupFrame} for a body-bearing group
     * and returns null, or — for a flag-only group like {@code (?i)} — returns
     * the resulting {@link Ast.Empty} atom with no frame, its flags persisting
     * into the enclosing group.
     *
     * <p>Flags are scoped to the enclosing group (re2j semantics, verified
     * against re2j 1.8: `((?i)a)b` does NOT fold `b` — every ')' restores
     * the flag state saved at its '('). Flag-ONLY groups `(?i)` are the
     * exception: they apply until the end of the ENCLOSING group, so they
     * never save/restore. unicodeShorthand and unicodeCase restore too:
     * \w/\d/\s class nodes materialize ranges and literals/classes fold
     * AT PARSE TIME (correctly scoped); the pattern-global runtime \b
     * word table reflects the final top-level flag state. */
    private Ast openGroup(Deque<GroupFrame> stack, FrameBudget frames) {
        expect('(');
        // java's atom() ends a literal run at ANY non-literal — a '(' is a
        // run boundary exactly like '|' or a non-literal part, so the
        // pending single-char candidate must flush here: in `a(a)` both
        // 'a's are single-char atoms (each flips under a fold lane),
        // never one two-char Slice (which never flips).
        flushLiteralRun();
        boolean savedCi = caseInsensitive, savedDs = dotall, savedMl = multiline;
        boolean savedUs = unicodeShorthand, savedUc = unicodeCase, savedUg = ungreedy, savedCx = comments,
            savedDl = unixLines, savedDq = dScoped;
        boolean capturing = true;
        String groupName = null;
        if (pos + 1 < src.length() && src.charAt(pos) == '?' && src.charAt(pos + 1) == ':') {
            pos += 2;
            capturing = false;
        } else if (pos < src.length() && peek() == '?') {
            pos++; // consume '?'
            // Reject DFA-incompatible group syntax
            char afterQ = peek();
            if (afterQ == '=' || afterQ == '!') {
                throw fail(this, "lookahead not supported");
            }
            if (afterQ == '>') {
                if (options.escapes() == EscapeAlphabet.RE2_MINIMAL) {
                    throw fail(this, "atomic groups not supported");
                }
                // (?>X): the atomic group — the fork-rule lowering over the
                // possessive machinery (docs/atomic-groups.md). One
                // construct, body-first/exit-second like a greedy QUEST,
                // the two edges carrying the fork's BODY/EXIT roles.
                pos++; // consume '>'
                int gOpen = pos;
                Ast body = parseAlt();
                expect(')');
                return new Ast.Atomic(body);
            }
            if (afterQ == '<') {
                char next = pos + 1 < src.length() ? src.charAt(pos + 1) : '\0';
                if (next == '=' || next == '!') {
                    throw fail(this, "lookbehind not supported");
                }
                // Named group (?<name>...)
                pos++; // consume '<'
                groupName = readGroupName();
            } else if (afterQ == 'P' && pos + 1 < src.length() && src.charAt(pos + 1) == '<') {
                // Named group (?P<name>...)
                pos += 2; // consume 'P<'
                groupName = readGroupName();
            } else {
                // Parse inline flags: (?i) (?s) (?m) (?u) (?U) (?-s) (?i:...) (?ism:...)
                // The letter→behavior activation is the compile's
                // FlagBindings (the closed FlagAction vocabulary); a
                // letter the surface does not bind is an error (re2j:
                // "invalid or unsupported Perl syntax"), never a silent
                // no-op. Every action targets scoped state, applied via
                // applyFlagAction — under the JUR reading the U binding
                // (scoped UNICODE_CASE) scopes like every other flag
                // since v2: colon form, in-group and negated spellings
                // all fold per java.util.regex.
                EnumMap<FlagAction, Boolean> pending = new EnumMap<>(FlagAction.class);
                boolean neg = false;
                boolean pendingNeg = false;
                while (pos < src.length() && peek() != ':' && peek() != ')') {
                    char f = peek();
                    if (f == '-') {
                        neg = true;
                        pendingNeg = true;
                        pos++;
                        continue;
                    }
                    FlagAction action = options.flagBindings().actionFor(f);
                    if (action == null) {
                        throw fail(this, "invalid or unsupported Perl syntax: (?+" + f);
                    }
                    pending.put(action, !neg); // a later letter for the same behavior wins
                    neg = false;
                    pendingNeg = false;
                    pos++;
                }
                if (pendingNeg) {
                    throw fail(this, "invalid or unsupported Perl syntax: (?-)");
                }
                if (peek() == ':') {
                    pos++; // consume ':'
                    capturing = false;
                    for (Map.Entry<FlagAction, Boolean> e : pending.entrySet()) {
                        applyFlagAction(e.getKey(), e.getValue());
                    }
                } else {
                    expect(')');
                    for (Map.Entry<FlagAction, Boolean> e : pending.entrySet()) {
                        applyFlagAction(e.getKey(), e.getValue());
                    }
                    return new Ast.Empty(); // flag-only group, continue — no frame, no restore
                }
            }
        }
        // Allocate the tag pair BEFORE the body is parsed so group numbers
        // follow open-paren position (standard regex convention): outer
        // groups get LOWER numbers than their inner groups. Allocating at
        // ')' would assign numbers in close-paren order, which is inside-out.
        int open = -1, close = -1;
        if (capturing) {
            open = nextTag++;
            close = nextTag++;
            groupCount++;
            if (groupName != null) {
                if (groupNames.containsKey(groupName)) {
                    throw fail(this, "duplicate capture group name: `" + groupName + "`");
                }
                groupNames.put(groupName, groupCount);
            }
        }
        GroupFrame f = new GroupFrame();
        f.capturing = capturing;
        f.open = open;
        f.close = close;
        f.savedCi = savedCi;
        f.savedMl = savedMl;
        f.savedUs = savedUs;
        f.savedUc = savedUc;
        f.savedUg = savedUg;
        f.savedCx = savedCx;
        f.savedDl = savedDl;
        f.savedDsc = savedDq;
        frames.push();
        stack.push(f);
        return null;
    }

    /** Apply one inline-flag behavior to the parser's scoped state
     *  (see {@link FlagAction} — the fields the parse-time consumers
     *  read: the fold sites, the class tables, the anchor flavor, the
     *  quantifier preference). Scoped spellings restore at the
     *  enclosing ')' via the {@link GroupFrame} snapshot; flag-only
     *  groups like {@code (?i)} persist into the remainder of the
     *  enclosing group (openGroup's flag-only return path).
     *
     *  <p>Since v2 this covers the JUR {@code (?U)} in every spelling:
     *  {@code (?U:...)}, {@code ((?U)x)} and {@code (?-U)} fold per
     *  java.util.regex (verified there live — java's own spelling of
     *  the scoped reading is inline {@code (?u)}); the v1
     *  top-level-only rejection is retired. */
    private void applyFlagAction(FlagAction action, boolean on) {
        switch (action) {
            case CASE_INSENSITIVE :
                this.caseInsensitive = on;
                break;
            case DOTALL :
                this.dotall = on;
                break;
            case MULTILINE :
                this.multiline = on;
                break;
            case UNICODE_CHARACTER_CLASS :
                this.unicodeShorthand = on;
                break;
            case UNICODE_CASE :
                this.unicodeCase = on;
                break;
            case UNGREEDY :
                this.ungreedy = on;
                break;
            case COMMENTS :
                this.comments = on;
                break;
            case UNIX_LINES :
                this.unixLines = on;
                this.dScoped = true;
                break;
            default :
                throw new IllegalStateException("unhandled flag action: " + action);
        }
    }

    /** Pop the innermost group at its ')': assemble its alternation, restore
     *  the flags saved at its '(', wrap in the tag pair if capturing, and
     *  append the result — with any trailing quantifier — to the parent's
     *  concat parts. */
    private void closeGroup(Deque<GroupFrame> stack, FrameBudget frames) {
        expect(')'); // the caller peeked it; the group owns its consumption
        GroupFrame f = stack.pop();
        frames.pop();
        flushLiteralRun(); // the body's concat ends here (run detector)
        f.alts.add(finishConcat(f.parts));
        Ast body = f.alts.size() == 1 ? f.alts.get(0) : new Ast.Alt(f.alts);
        // Restore the flag state saved at '(' (flag-only groups never pushed
        // a frame). Restored BEFORE the quantifier is applied: a trailing
        // lazy '?' after ')' must read the ENCLOSING group's ungreedy state.
        caseInsensitive = f.savedCi;
        dotall = f.savedDs;
        multiline = f.savedMl;
        unixLines = f.savedDl;
        dScoped = f.savedDsc;
        unicodeShorthand = f.savedUs;
        unicodeCase = f.savedUc;
        ungreedy = f.savedUg;
        comments = f.savedCx;
        Ast group =
            f.capturing
                ? new Ast.Concat(
                    Collections.unmodifiableList(Arrays.asList(new Ast.Tag(f.open), body, new Ast.Tag(f.close))))
                : body;
        Ast quantified = applyQuantifier(group);
        if (quantified instanceof Ast.Repeat) {
            // Structural fact for the meaning-resolution pass: the directly
            // quantified atom was this CAPTURE's tag-pair product — the
            // family-6 marks key on it ((\b)* dissolves; (?:(\b))* keeps
            // the span: the same tree shape, a different quantified atom).
            ((Ast.Repeat) quantified).quantifiedCapture = f.capturing;
        }
        boolean intoRoot = stack.size() == 1;
        stack.peek().parts.add(quantified);
        partArrived(0, false); // a group is never a literal part
        if (intoRoot && rootBeginState == 0) {
            // Any body-bearing group part heads the sequence in java's
            // AST — (?:\A)a Start-wraps exactly like (\A)a does.
            rootBeginState = 2;
        }
    }

    /** class := '^'? class-item+ ']' — the structured-item accumulation:
     *  the items carry their markers and scoped state, and the
     *  meaning-resolution pass flattens + folds the class against the
     *  compile's policy (the \s table, the provider twins, the
     *  SHORTHAND_FOLD-gated class fold). The scan-fact flips (the caret,
     *  written ranges, members, shorthand twins) stay here — they are
     *  source-shape facts, read where the source is. */
    private Ast parseClass() {
        skipComments();
        boolean negated = false;
        if (peek() == '^') {
            pos++;
            negated = true;
            // Rule (d): any complement flips unconditionally — java's
            // negate() drops the Bmp marker ("too expensive to detect").
            hasSupplementaryStarts = true;
        }
        List<Ast.ClassExpr.Item> items = new ArrayList<>();
        // POSIX/RE2: a `]` as the FIRST char in the class (after an optional `^`)
        // is a literal class member, not the terminator. So `[]]` matches a single `]`,
        // `[]a]` matches `]` or `a`, etc.
        if (peek() == ']') {
            items.add(new Ast.ClassExpr.Member(']', ']', false));
            memberFlip(']');
            pos++;
        }
        while (true) {
            skipComments();
            if (pos >= src.length() || peek() == ']') {
                break;
            }
            // Check for POSIX class [:name:] — a grammar table, materialized.
            if (peek() == '[' && peekAhead() == ':') {
                items.add(new Ast.ClassExpr.Table(parsePosixClass(), false));
                continue;
            }
            // Check for shorthand escapes (\s \S \d \D \w \W) and the JDK
            // \v/\h tables.
            if (peek() == '\\' && pos + 1 < src.length()) {
                char next = src.charAt(pos + 1);
                // java's range() look-ahead: an escape directly followed
                // by '-' is read as the range START, and \v's range reading
                // is the single unit U+000B (isrange) — skip the table
                // pre-check for it so parseClassChar + the dash below form
                // [\v-z] = [U+000B-'z'] (live-JDK verified; the other
                // class-table escapes have no isrange reading and never
                // form ranges, like java's predicate returns).
                boolean vRangeStart = next == 'v' && options.escapes() == EscapeAlphabet.JDK && pos + 2 < src.length()
                    && src.charAt(pos + 2) == '-';
                if (!vRangeStart) {
                    // The JDK escape alphabet's class tables (\v/\V/\h/\H)
                    // are GRAMMAR data — materialized here, marked
                    // shorthand so the class fold skips them on the
                    // non-folding lane like every shorthand twin.
                    int[] grammar = grammarClassTable(next);
                    if (grammar != null) {
                        // In-class shorthand flip: complements always, the
                        // positive families only under (?u) (see shorthandFlip).
                        shorthandFlip(next);
                        pos += 2;
                        items.add(new Ast.ClassExpr.Table(grammar, true));
                        continue;
                    }
                    if (isShorthandLetter(next)) {
                        // In-class shorthand flip: complements always, the
                        // positive families only under (?u) (see shorthandFlip).
                        shorthandFlip(next);
                        pos += 2;
                        items.add(new Ast.ClassExpr.Shorthand(Character.toLowerCase(next), Character.isUpperCase(next),
                            unicodeShorthand, caseInsensitive, unicodeCase || unicodeShorthand));
                        continue;
                    }
                }
                // Unicode property classes \p{X} \pX \P{X} \PX \p{^X} — a
                // provider lookup is a compile input, not a policy
                // application: materialized at the parse.
                if (next == 'p' || next == 'P') {
                    pos += 2; // consume '\' and 'p'/'P'
                    items.add(new Ast.ClassExpr.Table(parseUnicodeTable(next == 'p'), false));
                    continue;
                }
            }
            int lo = parseClassChar();
            int hi = lo;
            boolean dashed = false;
            skipComments();
            if (peek() == '-' && pos + 1 < src.length()) {
                int save = pos;
                pos++; // past '-'
                skipComments(); // (?x): [a - z] is [a-z] (java reads endpoints via its skipping read())
                if (pos < src.length() && peek() != ']') {
                    hi = parseClassChar();
                    dashed = true;
                    if (hi < lo) {
                        throw fail(this, "inverted range in class");
                    }
                } else {
                    pos = save; // a trailing dash is a literal member ([a-])
                }
            }
            if (dashed) {
                rangeFlip(lo, hi);
            } else {
                memberFlip(lo);
            }
            items.add(new Ast.ClassExpr.Member(lo, hi, dashed));
        }
        expect(']');
        return new Ast.ClassExpr(items, negated, caseInsensitive, unicodeCase || unicodeShorthand);
    }

    /** The JDK escape alphabet's in-class tables ({@code \v/\V} the
     *  vertical, {@code \h/\H} the horizontal whitespace ranges — null on
     *  the minimal alphabet, which rejects them in class context like
     *  re2j's). Grammar data, materialized at the parse. */
    private int[] grammarClassTable(char c) {
        if (options.escapes() != EscapeAlphabet.JDK) {
            return null;
        }
        switch (c) {
            case 'v' :
                return R_VERT_WS_JDK;
            case 'V' :
                return RangeMath.complement(R_VERT_WS_JDK);
            case 'h' :
                return R_HORIZ_WS;
            case 'H' :
                return RangeMath.complement(R_HORIZ_WS);
            default :
                return null;
        }
    }

    /** The in-class shorthand letters both alphabets share. */
    private static boolean isShorthandLetter(char c) {
        char base = Character.toLowerCase(c);
        return (base == 'd' || base == 'w' || base == 's') && (Character.isLowerCase(c) || Character.isUpperCase(c));
    }

    /**
     * A written class range's contribution to {@link #hasSupplementaryStarts}
     * (rule c): java's {@code Range} predicate is Bmp only when the range is
     * provably below the surrogate block or strictly between it and the
     * supplementary plane — and under case-insensitivity java builds
     * {@code CIRange}/{@code CIRangeU}, full predicates regardless of bounds.
     */
    private void rangeFlip(int lo, int hi) {
        boolean bmp = hi < 0xD800 || (lo > 0xDFFF && hi < 0x10000);
        if (caseInsensitive || !bmp) {
            hasSupplementaryStarts = true;
        }
    }

    /**
     * A single class member's contribution (rule b, class form): a
     * supplementary/lone member always flips (java's single() → SingleS —
     * bits cannot hold it); under CI with the full fold universe a member
     * that reaches single() with case counterparts flips too (SingleU) —
     * java keeps members below 0x100 in its BitClass except the
     * case-folding specials, so a plain BMP letter member stays Bmp.
     */
    private void memberFlip(int cp) {
        if (isSupplementaryCp(cp)) {
            hasSupplementaryStarts = true;
            return;
        }
        if (!caseInsensitive || !(unicodeCase || unicodeShorthand)) {
            return;
        }
        boolean reachesSingle = cp >= 0x100 || "\u00FF\u00B5IiSsKk\u00C5\u00E5\u00DF".indexOf(cp) >= 0;
        if (reachesSingle && foldUniverse(cp) != null) {
            hasSupplementaryStarts = true;
        }
    }

    /**
     * The grapheme/property boundary-clause gate (the ESCAPES_RE2 axis,
     * unset side): java reads a {@code {'} directly after
     * {@code \b}/{@code \B} as its boundary-type clause —
     * {@code \b{g}}, {@code \b{gc=…}} (JDK 21+ grapheme and property
     * boundaries) — or, for any other body, an "Illegal repetition"
     * error; it is NEVER a literal brace (the literal spelling is
     * {@code \b\{}). {@code \b{g}} LANDED (the grapheme boundary —
     * graphemeBoundaryClause lowers it to the GRAPH_BOUNDARY assertion
     * with the tables riding the parse); the property clauses and every
     * other body still reject loudly instead of silently reading the
     * RE2-lineage literal. A digit quantifier ({@code \b{5}}) is legal
     * java in both readings and passes through untouched. The RE2
     * reading (axis set) keeps the literal brace — re2j's alphabet,
     * pinned by the RE2J fuzz lane.
     */
    private void rejectBoundaryClause() {
        if (options.escapes() == EscapeAlphabet.RE2_MINIMAL || peek() != '{') {
            return;
        }
        int save = pos;
        if (parseBraces() == null) {
            throw fail(this, "grapheme/property boundary clauses (\\b{gc=...}) are not supported"
                + " — only \\b{g} translates; the literal brace needs escaping (\\b\\{)");
        }
        pos = save; // a legal quantifier — the repeat tail consumes it
    }

    /** The {@code \b{g}} clause after a boundary escape: EXACTLY this
     *  spelling (case-sensitive, live-JDK-verified) lowers to the
     *  GRAPH_BOUNDARY assertion with the tables riding the parse; any
     *  other brace body takes the clause gate. Called with the cursor on
     *  the {@code '{}.
     */
    private Ast graphemeBoundaryClause(Ast plain) {
        if (options.escapes() == EscapeAlphabet.RE2_MINIMAL || peek() != '{' || pos + 1 >= src.length()
            || src.charAt(pos + 1) != 'g' || pos + 2 >= src.length() || src.charAt(pos + 2) != '}') {
            rejectBoundaryClause();
            return plain;
        }
        pos += 3;
        if (graphemeTables == null) {
            graphemeTables = resolveGraphemeTables();
        }
        return new Ast.Assertion(Ast.AssertionKind.GRAPH_BOUNDARY);
    }

    /** Build a shorthand escape atom (called from {@link #parseEscape}).
     *  Symbolic since the compat-surface rung-2 split: the scoped state
     *  the expansion needs rides the node, and the meaning-resolution pass
     *  materializes it — the {@code \s} table from the compile's space-table
     *  component, the Unicode twins from the provider, the word orbit under
     *  the SHORTHAND_FOLD policy (see core.semantic.Resolve). */
    private Ast shorthandEscape(char c) {
        boolean negated = Character.isUpperCase(c);
        char base = Character.toLowerCase(c);
        return new Ast.Shorthand(base, negated, unicodeShorthand, caseInsensitive, unicodeCase || unicodeShorthand);
    }

    /** Unicode {@code \d} ranges = {@code \p{Nd}} (decimal digit number).
     *  Delegates to the single copy (core.unicode.ShorthandTables); the
     *  parser-side cache serves this parse's repeated queries. */
    private int[] unicodeDigitRanges() {
        if (cachedUnicodeDigit == null) {
            cachedUnicodeDigit = ShorthandTables.digitRanges(provider, R_DIGIT);
        }
        return cachedUnicodeDigit;
    }

    /**
     * Unicode {@code \w} ranges = {@code \p{Alpha} + \p{M} + \p{Nd} + \p{Pc}
     * + \p{IsJoin_Control}} — matching {@code java.util.regex} with
     * {@code UNICODE_CHARACTER_CLASS} exactly on the default (JDK-derived)
     * universe; see {@link ShorthandTables} (the single copy). Cached for
     * reuse by {@code \b} runtime.
     */
    private int[] computeUnicodeWordRanges() {
        if (cachedUnicodeWord == null) {
            cachedUnicodeWord = ShorthandTables.wordRanges(provider, R_WORD);
        }
        return cachedUnicodeWord;
    }

    /**
     * Parses a POSIX character class ("[:name:]") or its negated form
     * ("[:^name:]") — called only when peek()=='[' and peekAhead()==':'.
     * Returns the corresponding ASCII range table matching re2j's POSIX
     * semantics (complemented within [0, 0x10FFFF] when the leading caret is
     * present), or throws on malformed syntax or unknown names.
     */
    private int[] parsePosixClass() {
        // Caller guarantees [: — consume both.
        pos += 2;
        int start = pos;
        while (pos < src.length() && src.charAt(pos) != ':' && src.charAt(pos) != ']') {
            pos++;
        }
        if (pos >= src.length() || src.charAt(pos) != ':') {
            throw fail(this, "invalid POSIX class: missing ':]'");
        }
        String name = src.substring(start, pos);
        pos++; // consume ':'
        if (pos >= src.length() || src.charAt(pos) != ']') {
            throw fail(this, "invalid POSIX class: missing closing ']'");
        }
        pos++; // consume ']'
        boolean negated = false;
        if (name.startsWith("^")) {
            negated = true;
            name = name.substring(1);
        }
        int[] r = posixClassRanges(name);
        if (r == null) {
            throw fail(this, "unknown POSIX class: [:" + (negated ? "^" : "") + name + ":]");
        }
        if (negated) {
            // The complement form flips hasSupplementaryStarts (rule d) —
            // the house extension mirrors java's negate() discipline for the
            // spelled complement.
            hasSupplementaryStarts = true;
            return RangeMath.complement(r);
        }
        return r;
    }

    /** ASCII-only POSIX class ranges, matching re2j's CharClass tables. */
    private static int[] posixClassRanges(String name) {
        switch (name) {
            case "alnum" :
                return R_POSIX_ALNUM;
            case "alpha" :
                return R_POSIX_ALPHA;
            case "ascii" :
                return R_POSIX_ASCII;
            case "blank" :
                return R_POSIX_BLANK;
            case "cntrl" :
                return R_POSIX_CNTRL;
            case "digit" :
                return R_POSIX_DIGIT;
            case "graph" :
                return R_POSIX_GRAPH;
            case "lower" :
                return R_POSIX_LOWER;
            case "print" :
                return R_POSIX_PRINT;
            case "punct" :
                return R_POSIX_PUNCT;
            case "space" :
                return R_POSIX_SPACE; // POSIX [:space:] INCLUDES \v (unlike Perl \s)
            case "upper" :
                return R_POSIX_UPPER;
            case "word" :
                return R_POSIX_WORD;
            case "xdigit" :
                return R_POSIX_XDIGIT;
            default :
                return null;
        }
    }

    /** One class member or range endpoint, as a CODEPOINT (a raw supplementary
     *  character reads as one codepoint, not two surrogate units — the engine's
     *  alphabet is codepoints). Escaped members follow re2j: unknown
     *  ASCII-alphanumeric escapes are errors; any other escaped char (including
     *  non-ASCII letters like {@code \䑄}) is an identity escape. */
    private int parseClassChar() {
        char c = cur();
        if (c == '\\') {
            pos++;
            char e = cur();
            pos++;
            // Octal escape \NNN (1-3 octal digits, value capped at 0xFF).
            // \1-\9 single-digit rejected (reserved for backreference syntax).
            // The JUR class reading (live-JDK probed): java rejects every
            // digit escape in a class except the \0-led octal forms —
            // [\1], [\10], [\8] and bare [\0] are all illegal there; the
            // RE2 lineage keeps re2j's octal readings and bare-[\0]-is-NUL.
            if (e >= '1' && e <= '9' && options.escapes() == EscapeAlphabet.JDK) {
                throw fail(this, "invalid escape sequence: \\" + e);
            }
            if (e >= '0' && e <= '7') {
                int val = e - '0';
                if (pos < src.length() && src.charAt(pos) >= '0' && src.charAt(pos) <= '7') {
                    val = val * 8 + (src.charAt(pos) - '0');
                    pos++;
                    if (val < 32 && pos < src.length() && src.charAt(pos) >= '0' && src.charAt(pos) <= '7') {
                        val = val * 8 + (src.charAt(pos) - '0');
                        pos++;
                    }
                } else if (e != '0') {
                    throw fail(this, "invalid escape sequence: \\" + e);
                } else if (options.escapes() == EscapeAlphabet.JDK) {
                    // bare [\0]: java's "illegal octal escape" (the octal
                    // spelling needs a digit); re2j keeps its NUL reading.
                    throw fail(this, "illegal octal escape: \\0");
                }
                return val;
            }
            switch (e) {
                case 'n' :
                    return '\n';
                case 't' :
                    return '\t';
                case 'r' :
                    return '\r';
                case 'f' :
                    return '\f';
                case 'a' :
                    return (char) 7;
                case 'v' :
                    // Range endpoint under the JUR reading too (java's
                    // isrange rule: [\v-z] reads \v as U+000B); a
                    // standalone [\v] never reaches here on that lane —
                    // the class loop's table pre-check takes it.
                    return (char) 11;
                case 'e' :
                    if (options.escapes() == EscapeAlphabet.RE2_MINIMAL) {
                        throw fail(this, "invalid escape sequence: \\e");
                    }
                    return 0x1B;
                case 'c' :
                    if (options.escapes() == EscapeAlphabet.RE2_MINIMAL) {
                        throw fail(this, "invalid escape sequence: \\c");
                    }
                    if (pos >= src.length()) {
                        throw fail(this, "illegal control escape sequence");
                    }
                    int ctrl = src.charAt(pos) ^ 0x40;
                    pos++;
                    return ctrl;
                case 'N' :
                    if (options.escapes() == EscapeAlphabet.RE2_MINIMAL) {
                        throw fail(this, "invalid escape sequence: \\N");
                    }
                    return readNamedCodepoint();
                case '\\' :
                    return '\\';
                case 'x' :
                    return parseHexChar();
                case 'u' :
                    return parseUHex();
                default :
                    // re2j rejects unknown ASCII alphanumeric escapes in class
                    // context (notably [\b] — backspace is unsupported); a
                    // non-ASCII letter or digit is an identity escape there.
                    if (e < 128 && Character.isLetterOrDigit(e)) {
                        throw fail(this, "invalid escape sequence: \\" + e);
                    }
                    return e; // any other (non-alphanumeric) escaped char is literal
            }
        }
        int cp = Alphabet.decode(src, pos, src.length());
        pos += Alphabet.width(cp);
        return cp;
    }

    /** Like {@link #parseHexEscape()} but returns an int codepoint for class membership. */
    private int parseHexChar() {
        int val;
        if (pos < src.length() && src.charAt(pos) == '{') {
            pos++;
            int start = pos;
            while (pos < src.length() && isHex(src.charAt(pos))) {
                pos++;
            }
            if (pos >= src.length() || src.charAt(pos) != '}') {
                throw fail(this, "invalid hex escape: expected '}'");
            }
            String hex = src.substring(start, pos);
            pos++; // consume '}'
            if (hex.isEmpty()) {
                throw fail(this, "invalid hex escape: empty \\x{}");
            }
            val = parseHexValue(hex);
            if (val > 0x10FFFF) {
                throw fail(this, "invalid escape sequence");
            }
        } else {
            if (pos + 1 >= src.length() || !isHex(src.charAt(pos)) || !isHex(src.charAt(pos + 1))) {
                throw fail(this, "invalid hex escape: expected exactly 2 hex digits after \\x");
            }
            val = (hexVal(src.charAt(pos)) << 4) | hexVal(src.charAt(pos + 1));
            pos += 2;
        }
        return val;
    }

    private Ast parseEscape() {
        char c = cur();
        pos++;
        // The JUR escape reading (ESCAPES_RE2 unset): java's octal needs a
        // digit AFTER the leading \0 ("Illegal octal escape sequence" on a
        // bare \0 or \08, live-JDK verified); the RE2 lineage keeps re2j's
        // bare-\0-is-nul reading.
        // The JUR digit precedence (live-JDK probed): java reads EVERY
        // \1-\9-led escape as a BACKREFERENCE first — greedy digits while
        // the value stays <= the group count, a dangling first digit is
        // still the backref (\1(a), (a)\2, \8, (a)\10 all compile there;
        // (a)\10 MATCHES as backref-1 + literal '0'), never octal — octal
        // needs the leading \0. Backreferences are unsupported here (no
        // backtracking engine): reject loudly at exactly that precedence.
        // The RE2 lineage keeps re2j's readings (single digits reject,
        // multi-digit reads octal — the BackrefParityTest pins).
        if (c >= '1' && c <= '9' && options.escapes() == EscapeAlphabet.JDK) {
            throw fail(this, "backreferences are not supported: \\" + c);
        }
        if (c >= '0' && c <= '7') {
            int val = c - '0';
            if (pos < src.length() && src.charAt(pos) >= '0' && src.charAt(pos) <= '7') {
                val = val * 8 + (src.charAt(pos) - '0');
                pos++;
                if (val < 32 && pos < src.length() && src.charAt(pos) >= '0' && src.charAt(pos) <= '7') {
                    val = val * 8 + (src.charAt(pos) - '0');
                    pos++;
                }
            } else if (c != '0' || options.escapes() == EscapeAlphabet.JDK) {
                // \1-\7 always (backreference family); \0 requires a
                // following octal digit under the JUR reading only.
                throw fail(this, "invalid escape sequence: \\" + c);
            }
            literalKind = 1; // <= 0xFF: never supplementary, never folds
            return new Ast.Symbol((char) val);
        }
        // The java.util.regex-only escapes (ESCAPES_RE2 unset); under the
        // axis the letters fall to the default reject below — re2j's
        // alphabet ("invalid escape sequence", verified against re2j 1.8).
        boolean jur = options.escapes() == EscapeAlphabet.JDK;
        switch (c) {
            case 'n' :
                literalKind = 1;
                return new Ast.Symbol('\n');
            case 't' :
                literalKind = 1;
                return new Ast.Symbol('\t');
            case 'r' :
                literalKind = 1;
                return new Ast.Symbol('\r');
            case 'f' :
                literalKind = 1;
                return new Ast.Symbol('\f');
            case 'a' :
                literalKind = 1;
                return new Ast.Symbol((char) 7); // alarm/bell, like re2j
            case 'v' :
                // The \v axis: re2j's single unit U+000B (ESCAPES_RE2) vs
                // java.util.regex's VertWS class (the JDK's javadoc table,
                // live-JDK verified: \v matches \n, \r, \x85, U+2028/9).
                if (!jur) {
                    literalKind = 1;
                    return new Ast.Symbol((char) 11); // vertical tab, like re2j
                }
                return vertWsAtom(false);
            case 'V' :
                if (!jur) {
                    throw fail(this, "invalid escape sequence: \\V");
                }
                return vertWsAtom(true);
            case 'h' :
                if (!jur) {
                    throw fail(this, "invalid escape sequence: \\h");
                }
                return horizWsAtom(false);
            case 'H' :
                if (!jur) {
                    throw fail(this, "invalid escape sequence: \\H");
                }
                return horizWsAtom(true);
            case 'e' :
                if (!jur) {
                    throw fail(this, "invalid escape sequence: \\e");
                }
                literalKind = 1;
                return new Ast.Symbol((char) 0x1B); // ESC, java.util.regex's \e
            case 'R' :
                if (!jur) {
                    throw fail(this, "invalid escape sequence: \\R");
                }
                // java's LineEnding: CR LF | the VertWS single units, the
                // CRLF pair at leftmost-first priority.
                return new Ast.Alt(Collections.unmodifiableList(Arrays.asList(
                    new Ast.Concat(
                        Collections.unmodifiableList(Arrays.asList(new Ast.Symbol('\r'), new Ast.Symbol('\n')))),
                    new CharClass(R_VERT_WS_JDK, false))));
            case 'c' :
                if (!jur) {
                    throw fail(this, "invalid escape sequence: \\c");
                }
                return controlEscapeAtom();
            case 'N' :
                if (!jur) {
                    throw fail(this, "invalid escape sequence: \\N");
                }
                return namedCharAtom();
            case 'Z' :
                if (!jur) {
                    throw fail(this, "invalid escape sequence: \\Z");
                }
                // java's Dollar(false): end of input, or before the final
                // terminator — the plain-$ rule, immune to (?m) like \z.
                return new Ast.Assertion(Ast.AssertionKind.FINAL_END);
            case 'G' :
                if (!jur) {
                    throw fail(this, "invalid escape sequence: \\G");
                }
                // java's LastMatch: the previous match's end (the op's
                // start when none) — the matcher threads the base through
                // the scratch carrier; see AssertionKind.PREV_MATCH_END.
                return new Ast.Assertion(Ast.AssertionKind.PREV_MATCH_END);
            case 'X' :
                if (!jur) {
                    throw fail(this, "invalid escape sequence: \\X");
                }
                // One extended grapheme cluster (UAX #29): the TR29 rule
                // alternation over the provider's break tables — pure
                // syntax, no engine support.
                return graphemeClusterAtom();
            case '\\' :
                literalKind = 1;
                return new Ast.Symbol('\\');
            case 'x' :
                return parseHexEscape();
            case 'u' :
                return parseUEscape();
            case 'd' :
                shorthandFlip('d');
                return shorthandEscape('d');
            case 'D' :
                shorthandFlip('D');
                return shorthandEscape('D');
            case 'w' :
                shorthandFlip('w');
                return shorthandEscape('w');
            case 'W' :
                shorthandFlip('W');
                return shorthandEscape('W');
            case 's' :
                shorthandFlip('s');
                return shorthandEscape('s');
            case 'S' :
                shorthandFlip('S');
                return shorthandEscape('S');
            // RE2 (and re2j) reject \C as "any byte" — see re2j Parser.java:913.
            // We don't support it either; reject at parse time so silent misparse
            // (treating \C as literal C) doesn't yield wrong matches.
            case 'C' :
                throw new PatternSyntaxException("invalid escape sequence", "\\C");
            // Zero-width assertions — RE2/re2j implement these fully.
            case 'A' :
                // \A = absolute start of text (immune to (?m))
                return new Ast.Assertion(Ast.AssertionKind.TEXT_START);
            case 'z' :
                // \z = absolute end of text (immune to (?m))
                return new Ast.Assertion(Ast.AssertionKind.TEXT_END);
            case 'b' :
                // \b = word boundary — or the \b{g} grapheme boundary clause
                return graphemeBoundaryClause(new Ast.Assertion(Ast.AssertionKind.WORD_BOUNDARY));
            case 'B' :
                rejectBoundaryClause();
                // \B = not a word boundary (java has no \B{g} — pinned reject)
                return new Ast.Assertion(Ast.AssertionKind.NON_WORD_BOUNDARY);
            // Unicode property classes \p{X} \pX \P{X} \PX \p{^X}.
            // Outside a char class, build a CharClass directly (the table's
            // own negation flag carries the \P sign; no complement materialisation).
            case 'p' :
                return parseUnicodeEscape(true);
            case 'P' :
                return parseUnicodeEscape(false);
            case 'Q' :
                return parseQuotedLiteral();
            default : {
                // re2j rejects unknown ASCII alphanumeric escapes (\E, \K, \R,
                // \e, \N, ...). Non-ASCII letters/digits (\䑄, \Ω) and any
                // non-alphanumeric escape (\. \- \_ ...) are identity escapes.
                if (c < 128 && Character.isLetterOrDigit(c)) {
                    throw fail(this, "invalid escape sequence: \\" + c);
                }
                literalKind = literalKindOf(c); // java folds identity escapes via single()
                return new Ast.Symbol(c);
            }
        }
    }

    /**
     * A shorthand atom's contribution to {@link #hasSupplementaryStarts}:
     * the uppercase complements flip unconditionally (java's negate()
     * drops the Bmp marker), the positive families flip only under
     * {@code (?u)} (their UCC variants are full predicates — the ASCII
     * tables java builds otherwise are Bmp).
     */
    private void shorthandFlip(char c) {
        if (Character.isUpperCase(c) || unicodeShorthand) {
            hasSupplementaryStarts = true;
        }
    }

    /** The {@code \v}/{@code \V} atom (the JUR escape reading): the
     *  VertWS table, complemented for the uppercase form. */
    private Ast vertWsAtom(boolean negate) {
        if (negate) {
            hasSupplementaryStarts = true; // complements flip (java's negate())
        }
        return new CharClass(negate ? RangeMath.complement(R_VERT_WS_JDK) : R_VERT_WS_JDK, false);
    }

    /** The {@code \h}/{@code \H} atom (the JUR escape reading): the
     *  HorizWS table, complemented for the uppercase form. */
    private Ast horizWsAtom(boolean negate) {
        if (negate) {
            hasSupplementaryStarts = true;
        }
        return new CharClass(negate ? RangeMath.complement(R_HORIZ_WS) : R_HORIZ_WS, false);
    }

    /** The {@code \cX} control escape (the JUR reading): java's
     *  {@code c()} — the next char XOR 0x40, any char, a parse failure
     *  at end of pattern. */
    /**
     * {@code \X} — one extended grapheme cluster (UAX #29), as the rule
     * alternation over the provider's break tables (the TR29 consuming
     * form; java's own XGrapheme is a runtime scan over the same rules —
     * oracle-battery-pinned). Missing OPTIONAL tables degrade exactly the
     * way their rules do (a snapshot without ExtPict simply has no GB11;
     * 6.0 has no ZWJ class — its U+200D is Extend). The tables are read
     * RAW: cluster identity is codepoint-exact, never case-folded.
     */
    private Ast graphemeClusterAtom() {
        int[] control = tbl("gcb.Control");
        int[] cr = tbl("gcb.CR");
        int[] lf = tbl("gcb.LF");
        int[] extend = tbl("gcb.Extend");
        int[] zwj = tbl("gcb.ZWJ");
        int[] spacingMark = tbl("gcb.SpacingMark");
        if (control == null || cr == null || lf == null || extend == null || spacingMark == null) {
            throw fail(this, "the active Unicode provider carries no grapheme-break tables (\\X unsupported)");
        }
        // GB9/9a tail: (Extend | ZWJ | SpacingMark)*
        int[] tailClass = zwj == null ? RangeMath.merge(extend, spacingMark)
            : RangeMath.merge(RangeMath.merge(extend, zwj), spacingMark);
        Ast tail = star(cls(tailClass));

        List<Ast> alts = new ArrayList<>(7);
        // GB3: CR × LF is its own cluster core.
        Ast crlf = new Ast.Concat(Arrays.<Ast>asList(new Ast.Symbol('\r'), new Ast.Symbol('\n')));
        alts.add(crlf);
        // GB4/GB5: Control/CR/LF never join neighbours — a lone control.
        alts.add(new CharClass(RangeMath.merge(RangeMath.merge(control, cr), lf), false));

        // GB6-8 Hangul — every alternative consumes at least one char:
        // L+ (V+ T* | LV V* T* | LVT T*)?  |  V+ T*  |  LV V* T*  |  LVT T*
        Ast tStar = star(cls(tbl("gcb.T")));
        Ast afterL = new Ast.Repeat(
            new Ast.Alt(Arrays.<Ast>asList(new Ast.Concat(Arrays.<Ast>asList(plus(cls(tbl("gcb.V"))), tStar)),
                new Ast.Concat(Arrays.<Ast>asList(cls(tbl("gcb.LV")), star(cls(tbl("gcb.V"))), tStar)),
                new Ast.Concat(Arrays.<Ast>asList(cls(tbl("gcb.LVT")), tStar)))),
            0, 1, true);
        alts.add(new Ast.Concat(Arrays.<Ast>asList(plus(cls(tbl("gcb.L"))), afterL, tail)));
        alts.add(new Ast.Concat(Arrays.<Ast>asList(plus(cls(tbl("gcb.V"))), tStar, tail)));
        alts.add(new Ast.Concat(Arrays.<Ast>asList(cls(tbl("gcb.LV")), star(cls(tbl("gcb.V"))), tStar, tail)));
        alts.add(new Ast.Concat(Arrays.<Ast>asList(cls(tbl("gcb.LVT")), tStar, tail)));

        // GB12/13 RI pairs: (RI RI)+ | RI — a lone trailing RI starts the
        // NEXT cluster's pair.
        int[] riTbl = tbl("gcb.Regional_Indicator");
        if (riTbl != null) {
            Ast riOne = cls(riTbl);
            // EXACTLY one pair (GB12/13: an even count of RIs before a
            // break point breaks — pairs never chain), or a lone RI.
            Ast riPair = new Ast.Concat(Arrays.<Ast>asList(riOne, riOne));
            alts.add(new Ast.Concat(Arrays.<Ast>asList(new Ast.Alt(Arrays.<Ast>asList(riPair, riOne)), tail)));
        }

        // GB11: ExtPict (Extend* ZWJ ExtPict)*
        int[] ep = tbl("gcb.ExtPict");
        if (ep != null) {
            Ast epSeq = cls(ep);
            if (zwj != null) {
                Ast zwjJoin = new Ast.Concat(Arrays.<Ast>asList(star(cls(extend)), cls(zwj), cls(ep)));
                epSeq = new Ast.Concat(Arrays.<Ast>asList(epSeq, new Ast.Repeat(zwjJoin, 0, Integer.MAX_VALUE, true)));
            }
            alts.add(new Ast.Concat(Arrays.<Ast>asList(epSeq, tail)));
        }

        // GB9c: LC ((Extend|Linker)* Linker (Extend|Linker)* LC)*
        int[] lc = tbl("incb.LinkingConsonant");
        int[] linker = tbl("incb.Linker");
        int[] incbExtend = tbl("incb.Extend");
        if (lc != null && linker != null && incbExtend != null) {
            Ast inner = star(cls(RangeMath.merge(incbExtend, linker)));
            Ast linkJoin = new Ast.Concat(Arrays.<Ast>asList(inner, cls(linker), inner, cls(lc)));
            Ast linking =
                new Ast.Concat(Arrays.<Ast>asList(cls(lc), new Ast.Repeat(linkJoin, 0, Integer.MAX_VALUE, true), tail));
            alts.add(linking);
        }

        // The residual core: anything that is not a special lead (each has
        // its own alternative above). Prepend and the joining classes stay
        // residual: an orphan Prepend/Extend/SpacingMark at a boundary is
        // its own cluster (GB999), and the tail rides it.
        int[] residual = RangeMath.complement(
            unionAll(control, cr, lf, tbl("gcb.L"), tbl("gcb.V"), tbl("gcb.LV"), tbl("gcb.LVT"), riTbl, ep, lc));
        alts.add(new Ast.Concat(Arrays.<Ast>asList(new CharClass(residual, false), tail)));

        // GB9b: Prepend* joins the FOLLOWING cluster (GB4/5 override it
        // before Control — the control alternative stands unprefixed).
        int[] prepend = tbl("gcb.Prepend");
        List<Ast> prefixed = new ArrayList<>(alts.size());
        for (int i = 0; i < alts.size(); i++) {
            Ast a = alts.get(i);
            if (prepend != null && i >= 2) { // skip \r\n and the control lone
                a = new Ast.Concat(Arrays.<Ast>asList(star(cls(prepend)), a));
            }
            prefixed.add(a);
        }
        return new Ast.Alt(prefixed);
    }

    /** A gcb/incb table, or null when the provider does not carry the name. */
    private int[] tbl(String name) {
        return provider.tableFor(name);
    }

    /** Resolve the grapheme-break inputs from the compile's provider (the
     *  one site — the value type itself is a pure ast leaf). */
    private GraphemeTables resolveGraphemeTables() {
        return new GraphemeTables(merged("gcb.CR", "gcb.LF", "gcb.Control"), orEmpty(tbl("gcb.Extend")), tbl("gcb.ZWJ"),
            orEmpty(tbl("gcb.SpacingMark")), orEmpty(tbl("gcb.L")), orEmpty(tbl("gcb.V")), orEmpty(tbl("gcb.LV")),
            orEmpty(tbl("gcb.LVT")), orEmpty(tbl("gcb.T")), orEmpty(tbl("gcb.Regional_Indicator")),
            orEmpty(tbl("gcb.Prepend")), tbl("gcb.ExtPict"), tbl("incb.Extend"), tbl("incb.Linker"),
            tbl("incb.LinkingConsonant"));
    }

    private static int[] orEmpty(int[] t) {
        return t == null ? new int[0] : t;
    }

    private int[] merged(String... names) {
        int[] all = new int[0];
        for (String n : names) {
            int[] t = tbl(n);
            if (t != null && t.length > 0) {
                int[] m = new int[all.length + t.length];
                System.arraycopy(all, 0, m, 0, all.length);
                System.arraycopy(t, 0, m, all.length, t.length);
                all = m;
            }
        }
        // CR/LF/Control are disjoint ascending — plain concat is sorted
        return all;
    }

    private static Ast cls(int[] ranges) {
        return new CharClass(ranges, false);
    }

    private static Ast star(Ast body) {
        return new Ast.Repeat(body, 0, Integer.MAX_VALUE, true);
    }

    private static Ast plus(Ast body) {
        return new Ast.Repeat(body, 1, Integer.MAX_VALUE, true);
    }

    /** Union of present tables (null entries skipped; empty input = empty). */
    private static int[] unionAll(int[]... tables) {
        int[] all = new int[0];
        for (int[] t : tables) {
            if (t != null && t.length > 0) {
                all = RangeMath.merge(all, t);
            }
        }
        return all;
    }

    private Ast controlEscapeAtom() {
        if (pos >= src.length()) {
            throw fail(this, "illegal control escape sequence");
        }
        int cp = src.charAt(pos) ^ 0x40;
        pos++;
        literalKind = literalKindOf(cp);
        return literalAtom(cp);
    }

    /** The {@code \N{NAME}} named-character atom (the JUR reading):
     *  {@link Character#codePointOf(String)} — names only (java rejects
     *  the {@code U+XXXX} spelling, live-JDK verified). */
    private Ast namedCharAtom() {
        int cp = readNamedCodepoint();
        literalKind = literalKindOf(cp);
        return literalAtom(cp);
    }

    /** Read a {@code \N{NAME}} body (caller consumed {@code \N}):
     *  the braced Unicode character name, resolved to its codepoint. */
    private int readNamedCodepoint() {
        if (pos >= src.length() || src.charAt(pos) != '{') {
            throw fail(this, "illegal character name escape sequence");
        }
        pos++;
        int start = pos;
        while (pos < src.length() && src.charAt(pos) != '}') {
            pos++;
        }
        if (pos >= src.length()) {
            throw fail(this, "unclosed character name escape sequence");
        }
        String name = src.substring(start, pos);
        pos++; // consume '}'
        // Character.codePointOf is @since 9 — the Java 8 floor reaches it
        // reflectively, and a Java 8 runtime (no such method) rejects
        // \N{...} loudly rather than silently mis-reading it.
        if (CODE_POINT_OF == null) {
            throw fail(this, "unknown character name [" + name + "] (no Character.codePointOf on this JVM)");
        }
        try {
            return (Integer) CODE_POINT_OF.invoke(null, name);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("codePointOf unavailable", e);
        } catch (InvocationTargetException e) {
            throw fail(this, "unknown character name [" + name + "]");
        }
    }

    /**
     * Parse a {@code \Q...\E} literal quoting section (caller has consumed
     * {@code \Q}). Everything up to {@code \E} (or end of pattern) is treated
     * as literal characters. If no {@code \E} is found, the rest of the
     * pattern is literal (matching re2j/PCRE behavior).
     */
    private Ast parseQuotedLiteral() {
        int start = pos;
        boolean foundE = false;
        while (pos < src.length()) {
            if (pos + 1 < src.length() && src.charAt(pos) == '\\' && src.charAt(pos + 1) == 'E') {
                foundE = true;
                break;
            }
            pos++;
        }
        String literal = src.substring(start, pos);
        if (foundE) {
            pos += 2;
        } // consume \E

        if (literal.isEmpty()) {
            // \Q\E is transparent in java (RemoveQEQuoting deletes it): a
            // blank literal part that keeps the surrounding literals in one
            // run — kind 1 (literal, never a flip candidate).
            literalKind = 1;
            return new Ast.Empty();
        }
        // The whole body decoding to ONE codepoint (a lone unit, or a raw
        // pair) is java's single-char atom; anything longer is a Slice.
        int firstCp = Alphabet.decode(literal, 0, literal.length());
        if (literal.length() == Alphabet.width(firstCp)) {
            literalKind = literalKindOf(firstCp);
            if (caseInsensitive) {
                return new Ast.FoldLiteral(firstCp, unicodeCase || unicodeShorthand);
            }
            return literalAtom(firstCp);
        }
        List<Ast> parts = new ArrayList<>();
        for (int i = 0; i < literal.length();) {
            int cp = Alphabet.decode(literal, i, literal.length());
            i += Alphabet.width(cp);
            if (caseInsensitive) {
                parts.add(new Ast.FoldLiteral(cp, unicodeCase || unicodeShorthand));
                continue;
            }
            parts.add(literalAtom(cp));
        }
        // The whole quoted body is ONE literal run in java (>= 2 chars ⇒ a
        // Slice, which never flips); it still merges with neighboring
        // literal parts into that run.
        literalKind = 1;
        return new Ast.Concat(parts);
    }

    /**
     * Parse a Unicode property escape after the {@code \p} / {@code \P} prefix
     * (caller has consumed both characters). Supports all four re2j syntaxes:
     * {@code \p{X}}, {@code \pX}, {@code \P{X}}, {@code \PX}, plus internal
     * negation {@code \p{^X}} (equivalent to {@code \P{X}}).
     *
     * @param positive {@code true} for {@code \p}, {@code false} for {@code \P}
     */
    private Ast parseUnicodeEscape(boolean positive) {
        return new CharClass(parseUnicodeTable(positive), false);
    }

    /** Parse the body of a {@code \p}/{@code \P} escape (after the prefix,
     *  with the caller's {@code positive} sense) and resolve it to the
     *  property's EFFECTIVE range table: name read, ^-inner negation
     *  applied through the truth table, the named table looked up (unknown
     *  names fail the parse), and the case-folding twin merged when
     *  active. Shared by the atom path and the class-accumulator path. */
    private int[] parseUnicodeTable(boolean positive) {
        if (disableUnicodeGroups) {
            throw fail(this, "Unicode groups (\\p/\\P) disabled by DISABLE_UNICODE_GROUPS flag");
        }
        String name = parseUnicodeName();
        boolean innerNeg = name.startsWith("^");
        if (innerNeg) {
            name = name.substring(1);
        }
        // Truth table:
        //   \p{X}  → (T, F) → complement=F
        //   \p{^X} → (T, T) → complement=T
        //   \P{X}  → (F, F) → complement=T
        //   \P{^X} → (F, T) → complement=F
        int[] t = provider.tableFor(name);
        if (t == null) {
            throw fail(this, "unknown character class name: " + name);
        }
        propertyFlip(name, positive == innerNeg);
        int[] fold = caseInsensitive ? provider.foldTableFor(name) : null;
        if (fold != null && fold.length > 0) {
            t = RangeMath.merge(t, fold);
        }
        return (positive == innerNeg) ? RangeMath.complement(t) : t;
    }

    /**
     * A {@code \p{...}} family predicate's contribution to
     * {@link #hasSupplementaryStarts} (rule e, live-JDK verified): the
     * complement forms flip unconditionally; the positive forms flip
     * unless java builds the name as a Bmp predicate — {@code ASCII} and
     * {@code L1} are Bmp range tables under any flag, the POSIX ASCII
     * family names only without {@code (?u)} (with it java substitutes the
     * full Unicode predicates). Every other name (categories, scripts,
     * binary properties) is a full predicate and flips.
     */
    private void propertyFlip(String name, boolean complement) {
        if (complement) {
            hasSupplementaryStarts = true;
            return;
        }
        if ("ASCII".equals(name) || "L1".equals(name)) {
            return;
        }
        boolean posixAsciiFamily =
            "Alnum".equals(name) || "Alpha".equals(name) || "Blank".equals(name) || "Cntrl".equals(name)
                || "Digit".equals(name) || "Graph".equals(name) || "Lower".equals(name) || "Print".equals(name)
                || "Punct".equals(name) || "Space".equals(name) || "Upper".equals(name) || "XDigit".equals(name);
        if (!posixAsciiFamily || unicodeShorthand) {
            hasSupplementaryStarts = true;
        }
    }

    /** Read a Unicode property name after {@code \p}/{@code \P}: either
     *  braced {@code {name}} or a single letter {@code L}, {@code N}, etc. */
    private String parseUnicodeName() {
        if (pos < src.length() && src.charAt(pos) == '{') {
            pos++;
            int start = pos;
            while (pos < src.length() && src.charAt(pos) != '}') {
                pos++;
            }
            if (pos >= src.length()) {
                throw fail(this, "unclosed '\\p{' in pattern");
            }
            String name = src.substring(start, pos);
            pos++; // consume '}'
            if (name.isEmpty()) {
                throw fail(this, "empty property name in '\\p{}'");
            }
            return name;
        }
        if (pos >= src.length()) {
            throw fail(this, "incomplete '\\p' escape");
        }
        String name = String.valueOf(src.charAt(pos));
        pos++;
        return name;
    }

    /**
     * Parse a hex escape after the leading {@code \x} (caller has consumed both).
     * Supports both forms (re2j-compatible):
     * <ul>
     *   <li>{@code \xNN} — exactly 2 hex digits, value 0x00-0xFF.</li>
     *   <li>{@code \x{N+}} — 1+ hex digits enclosed in braces, value 0-U+10FFFF.
     *       Non-BMP codepoints (above U+FFFF) are returned as a CharClass
     *       and are surrogate-pair aware at match time.</li>
     * </ul>
     */
    private Ast parseHexEscape() {
        int cp = parseHexChar();
        literalKind = literalKindOf(cp);
        return literalAtom(cp);
    }

    /**
     * Parse a backslash-u-XXXX escape after the leading backslash-u (caller
     * has consumed it): exactly 4 hex digits, the BMP codepoint 0xXXXX.
     * java's pair-combining reading (live-JDK verified): when the value is
     * a high surrogate immediately followed by backslash-u + a low
     * surrogate, the two combine into ONE supplementary codepoint
     * ({@code a<high><low>} spells "a", 0x1F600, "b" via adjacent escapes);
     * a lone surrogate value stays lone and matches only unpaired units
     * (the alphabet decodes a well-formed pair as its supplementary
     * codepoint instead). re2j has no backslash-u escape — the
     * shared-syntax extension lands on both surfaces with the escape
     * suites' pinned accept-divergence.
     */
    private Ast parseUEscape() {
        int cp = parseUHex();
        literalKind = literalKindOf(cp);
        return literalAtom(cp);
    }

    /** Exactly 4 hex digits (java's {@code uxxxx()}), or a parse failure. */
    private int parseUHex() {
        if (pos + 4 > src.length()) {
            throw fail(this, "illegal Unicode escape sequence: expected 4 hex digits after \\u");
        }
        int val = 0;
        for (int i = 0; i < 4; i++) {
            char h = src.charAt(pos + i);
            if (!isHex(h)) {
                throw fail(this, "illegal Unicode escape sequence: expected 4 hex digits after \\u");
            }
            val = (val << 4) | hexVal(h);
        }
        pos += 4;
        // java's u(): a high surrogate immediately followed by backslash-u
        // + low surrogate combines into the supplementary codepoint
        if (val >= 0xD800 && val <= 0xDBFF && pos + 1 < src.length() && src.charAt(pos) == '\\'
            && src.charAt(pos + 1) == 'u') {
            int save = pos;
            pos += 2;
            int lo = pos + 4 <= src.length() && isHex(src.charAt(pos)) && isHex(src.charAt(pos + 1))
                && isHex(src.charAt(pos + 2)) && isHex(src.charAt(pos + 3)) ? parseUHex() : -1;
            if (lo >= 0xDC00 && lo <= 0xDFFF) {
                return ((val - 0xD800) << 10) + (lo - 0xDC00) + 0x10000;
            }
            pos = save; // not a pair: the high surrogate stays lone
        }
        return val;
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    /** Hex value of a \x{...} body, overflow-safe: after skipping leading
     *  zeros (RE2's own corpus uses zero-padded forms like \x{00000061}), 7+
     *  significant digits cannot be a codepoint — "invalid escape sequence"
     *  like re2j. The body is isHex-validated by the caller and at most 6
     *  significant digits remain, so the parse cannot fail. */
    private static int parseHexValue(String hex) {
        int i = 0;
        while (i < hex.length() - 1 && hex.charAt(i) == '0') {
            i++;
        }
        String s = hex.substring(i);
        if (s.length() > 6) {
            return Integer.MAX_VALUE;
        } // > 0xFFFFFF, over any codepoint
        return Integer.parseInt(s, 16);
    }

    private static int hexVal(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        return c - 'A' + 10;
    }

    /** Parse {@code {n}}, {@code {n,}}, {@code {n,m}}; null when the text is
     *  not a quantifier (caller treats the '{' as a literal). re2j parity
     *  (verified against re2j 1.8): ASCII digits only (a{٥} is a literal —
     *  Character.isDigit would silently accept Arabic-Indic digits and
     *  misparse), counts capped at 1000 with "invalid repeat count" (an
     *  uncapped count is a Builder OOM / int-overflow before any compile
     *  budget can fire), and overflow text gets the same message, never a
     *  raw NumberFormatException. */
    private int[] parseBraces() {
        int save = pos;
        pos++; // consume '{'
        int start = pos;
        while (pos < src.length() && src.charAt(pos) >= '0' && src.charAt(pos) <= '9') {
            pos++;
        }
        if (pos == start) {
            pos = save;
            return null;
        } // not a quantifier; treat as literal '{'
        int min = parseRepeatCount(src, start, pos);
        int max = min;
        if (peek() == ',') {
            pos++;
            int mStart = pos;
            while (pos < src.length() && src.charAt(pos) >= '0' && src.charAt(pos) <= '9') {
                pos++;
            }
            max = (mStart == pos) ? Integer.MAX_VALUE : parseRepeatCount(src, mStart, pos);
        }
        if (peek() != '}') {
            pos = save;
            return null;
        }
        pos++; // consume '}'
        if (max < min) {
            throw fail(this, "min > max in {" + min + "," + max + "}");
        }
        return new int[]{min, max};
    }

    /** Bounded repeat count: ASCII digits in [start, end), ≤ MAX_REPEAT_COUNT
     *  (re2j's cap), no overflow — anything longer/larger is "invalid repeat
     *  count" like re2j, not a NumberFormatException. */
    private static int parseRepeatCount(String s, int start, int end) {
        int val = 0;
        for (int i = start; i < end; i++) {
            val = val * 10 + (s.charAt(i) - '0');
            if (val > MAX_REPEAT_COUNT) {
                break;
            } // further digits cannot help
        }
        if (val > MAX_REPEAT_COUNT) {
            throw new PatternSyntaxException(
                "Parse error at index " + start + ": invalid repeat count (in \"" + s + "\")", s);
        }
        return val;
    }

    private char peek() {
        return pos < src.length() ? src.charAt(pos) : '\0';
    }

    /**
     * COMMENTS-mode token skip (java's {@code peekPastWhitespace}): past
     * ASCII whitespace ({@code ' ' \t \n \x0B \f \r}) and
     * {@code #}-to-line-end comments ({@code \n \r \u2028 \u2029} end a
     * comment — java's {@code parsePastLine}). Called at token
     * boundaries only (the atom/class loops, before quantifiers) — the
     * escape-body readers never skip, so escaped whitespace survives and
     * {@code \Q...\E} bodies stay immune, java's {@code readEscaped()}
     * discipline.
     */
    private void skipComments() {
        if (!comments) {
            return;
        }
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\u000B' || c == '\f' || c == '\r') {
                pos++;
                continue;
            }
            if (c == '#') {
                pos++;
                while (pos < src.length()) {
                    char t = src.charAt(pos);
                    if (t == '\n' || t == '\r' || t == '\u2028' || t == '\u2029') {
                        break;
                    }
                    pos++;
                }
                continue;
            }
            return;
        }
    }

    private char peekAhead() {
        return pos + 1 < src.length() ? src.charAt(pos + 1) : '\0';
    }

    private char cur() {
        if (pos >= src.length()) {
            throw fail(this, "unexpected end of input");
        }
        return src.charAt(pos);
    }

    private void expect(char c) {
        if (pos >= src.length() || src.charAt(pos) != c) {
            throw fail(this, "expected '" + c + "'");
        }
        pos++;
    }

    public int groupCount() {
        return groupCount;
    }

    public int tagCount() {
        return nextTag - 1;
    }

    public boolean multiline() {
        return multiline;
    }

    public boolean unicodeShorthand() {
        return unicodeShorthand;
    }

    /** Unicode {@code \w} ranges for runtime {@code \b} word-boundary checks, or null if Unicode shorthand not enabled. */
    public int[] unicodeWordRanges() {
        return unicodeShorthand ? computeUnicodeWordRanges() : null;
    }

    public Map<String, Integer> namedGroups() {
        return groupNames;
    }

    /** Read a group name between '<' and '>' (caller has consumed '<').
     *  re2j validity: word characters only ([A-Za-z0-9_], digits-first allowed
     *  — verified against re2j 1.8: (?<1a>x) compiles, (?<a b>)/(?<a-b>) are
     *  "invalid named capture"). Group names are API-visible via
     *  namedGroups(), so garbage must not slip through. */
    private String readGroupName() {
        int start = pos;
        while (pos < src.length() && peek() != '>') {
            pos++;
        }
        if (pos >= src.length()) {
            throw fail(this, "unclosed group name");
        }
        String name = src.substring(start, pos);
        pos++; // consume '>'
        if (name.isEmpty()) {
            throw fail(this, "invalid named capture");
        }
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            boolean word =
                (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9') || ch == '_';
            if (!word) {
                throw fail(this, "invalid named capture");
            }
        }
        return name;
    }

    private static Method lookupCodePointOf() {
        try {
            return Character.class.getMethod("codePointOf", String.class);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private static PatternSyntaxException fail(Parser p, String msg) {
        return new PatternSyntaxException("Parse error at index " + p.pos + ": " + msg + " (in \"" + p.src + "\")",
            p.src);
    }
}
