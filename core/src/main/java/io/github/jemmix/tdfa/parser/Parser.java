package io.github.jemmix.tdfa.parser;

import io.github.jemmix.tdfa.ast.Alphabet;
import io.github.jemmix.tdfa.ast.Ast;
import io.github.jemmix.tdfa.ast.CharClass;
import io.github.jemmix.tdfa.unicode.CaseFoldTable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hand-rolled recursive-descent parser for a PCRE-ish subset:
 * - literals, escape sequences (\n \t \r \f \a \v \d \D \w \W \s \S)
 * - hex escapes (\xNN, \x{N+}), octal escapes (\NNN, 1–3 digits capped at 0xFF)
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
 */
public final class Parser {
    private final String src;
    private int pos = 0;
    private int nextTag = 1;
    private int groupCount = 0;
    private final Map<String, Integer> groupNames = new LinkedHashMap<>();
    /** Current group-nesting depth (recursion cap; see MAX_GROUP_DEPTH). */
    private int depth = 0;
    /** re2j's repeat-count cap: {n,m} with n or m over 1000 is "invalid
     *  repeat count" (verified against re2j 1.8). Also the parse-time bound
     *  on Tnfa's eager {n,m} desugaring — without it, a{500000000} OOMs the
     *  Builder before any determinization budget can fire. */
    private static final int MAX_REPEAT_COUNT = 1000;
    /** Group-nesting cap: the recursive-descent cycle parseAlt→parseConcat→
     *  parseRepeat→parseAtom→parseGroup costs JVM stack frames per level
     *  (re2j's parser is iterative and needs none); past ~20k levels the
     *  default JVM stack overflows with a raw StackOverflowError through
     *  Pattern.compile. 1000 is comfortably both sides. */
    private static final int MAX_GROUP_DEPTH = 1000;
    boolean caseInsensitive = false;
    boolean dotall = false;
    boolean multiline = false;
    /** re2j's U flag: quantifiers default to lazy, a trailing ? makes them greedy. */
    boolean ungreedy = false;
    boolean disableUnicodeGroups = false;
    boolean unicodeShorthand = false;
    io.github.jemmix.tdfa.unicode.UnicodeDataProvider provider;

    private Parser(String src, boolean disableUnicodeGroups, io.github.jemmix.tdfa.unicode.UnicodeDataProvider provider) {
        this.src = src;
        this.disableUnicodeGroups = disableUnicodeGroups;
        this.provider = provider;
    }

    public static Ast parse(String src) {
        return parse(src, false);
    }

    public static Ast parse(String src, boolean disableUnicodeGroups) {
        return parse(src, disableUnicodeGroups, false);
    }

    public static Ast parse(String src, boolean disableUnicodeGroups, boolean anchorBoth) {
        return parse(src, disableUnicodeGroups, anchorBoth, io.github.jemmix.tdfa.unicode.UnicodeProviders.get());
    }

    public static Ast parse(String src, boolean disableUnicodeGroups, boolean anchorBoth,
                            io.github.jemmix.tdfa.unicode.UnicodeDataProvider provider) {
        return parseResult(src, disableUnicodeGroups, anchorBoth, provider).ast();
    }

    /** Parse and return the full result: AST plus tag/group counters, effective
     *  flags, and named-group metadata (the composable-pipeline entry point). */
    public static ParseResult parseResult(String src, boolean disableUnicodeGroups, boolean anchorBoth,
                                          io.github.jemmix.tdfa.unicode.UnicodeDataProvider provider) {
        Parser p = new Parser(src, disableUnicodeGroups, provider);
        Ast e = p.parseAlt();
        if (p.pos != p.src.length()) throw fail(p, "unexpected '" + p.cur() + "'");
        e = anchorBoth ? anchorBoth(e) : e;
        return new ParseResult(e, p.tagCount(), p.groupCount(), p.multiline(),
                p.unicodeShorthand(), p.unicodeWordRanges(), p.namedGroups());
    }

    /** Wrap a parsed body in start/end-text anchors (matches() = anchored both ends).
     *  Done at AST level so literal-quote ({@code \Q...\E}) in the body isn't disturbed,
     *  and the trailing anchor supplies context that prevents the Perl leftmost-first
     *  DFA from pruning a longer alternative's continuation. */
    private static Ast anchorBoth(Ast e) {
        return new Ast.Concat(List.of(new Ast.StartAnchor(true), e, new Ast.EndAnchor(true)));
    }

    /** alt := concat ('|' concat)* */
    private Ast parseAlt() {
        List<Ast> alts = new ArrayList<>();
        alts.add(parseConcat());
        while (peek() == '|') { pos++; alts.add(parseConcat()); }
        return alts.size() == 1 ? alts.get(0) : new Ast.Alt(alts);
    }

    /** concat := repeat* (no explicit separator) */
    private Ast parseConcat() {
        List<Ast> parts = new ArrayList<>();
        while (pos < src.length() && peek() != '|' && peek() != ')') parts.add(parseRepeat());
        return parts.isEmpty() ? new Ast.Empty() : (parts.size() == 1 ? parts.get(0) : new Ast.Concat(parts));
    }

    /** repeat := atom quantifier? */
    private Ast parseRepeat() {
        Ast atom = parseAtom();
        if (pos >= src.length()) return atom;
        char c = peek();
        int min, max;
        if (c == '*') { pos++; min = 0; max = Integer.MAX_VALUE; }
        else if (c == '+') { pos++; min = 1; max = Integer.MAX_VALUE; }
        else if (c == '?') { pos++; min = 0; max = 1; }
        else if (c == '{') {
            int[] bounds = parseBraces();
            if (bounds == null) return atom;
            min = bounds[0]; max = bounds[1];
        } else return atom;

        // Greedy by default; re2j's U flag inverts the default (a trailing ?
        // then selects the OTHER mode, per RE2 ungreedy semantics).
        boolean greedy = !ungreedy;
        if (pos < src.length() && peek() == '?') { pos++; greedy = ungreedy; }

        // A quantifier directly after a completed quantifier (greedy, lazy,
        // possessive, or a second {n,m}) is "invalid nested repetition
        // operator" in re2j — never a silent literal `{2}` append or a
        // separate "possessive" error. `a??`/`a*?` stay legal: the lazy `?`
        // was consumed above. Probing {..} must not consume on the error path
        // (it throws), and an INVALID brace form (e.g. `a*{`) stays a literal.
        if (pos < src.length()) {
            char n = peek();
            if (n == '*' || n == '+' || n == '?')
                throw fail(this, "invalid nested repetition operator");
            if (n == '{' && parseBraces() != null)
                throw fail(this, "invalid nested repetition operator");
        }
        return new Ast.Repeat(atom, min, max, greedy);
    }

    /**
     * Expands a literal codepoint under case-insensitive mode into a CharClass,
     * or returns {@code null} if the codepoint should remain a plain literal
     * (no case-fold equivalents). Uses full Unicode case folding
     * (e.g., {@code s ↔ ſ}) when {@code unicodeShorthand} is enabled,
     * falling back to {@code toLowerCase}/{@code toUpperCase} for BMP
     * codepoints otherwise. Supplementary codepoints have no simple-fold
     * table yet and stay unexpanded.
     */
    private Ast caseFoldChar(int cp) {
        // re2j folds FULL Unicode simple folding under plain (?i) — no (?u)
        // needed (verified against re2j 1.8: (?i)s matches ſ, (?i)k matches K).
        int[] ranges = CaseFoldTable.foldRanges(cp);
        if (ranges != null) return new CharClass(ranges, false);
        int lo = Character.toLowerCase(cp);
        int hi = Character.toUpperCase(cp);
        if (lo != hi) return new CharClass(new int[]{lo, lo, hi, hi}, false);
        return null;
    }

    /** Atom for one literal codepoint. BMP stays {@link Ast.Symbol}; a
     *  supplementary codepoint becomes a single-codepoint {@link CharClass},
     *  the same shape {@code \x{...}} produces, because the engine's alphabet
     *  is codepoints — one transition consumes exactly one. */
    private static Ast literalAtom(int cp) {
        return cp <= 0xFFFF ? new Ast.Symbol((char) cp) : new CharClass(new int[]{cp, cp}, false);
    }

    /** atom := group | class | dot | anchor | escape | literal */
    private Ast parseAtom() {
        char c = cur();
        if (c == '(') return parseGroup();
        if (c == '[') { pos++; return parseClass(); }
        if (c == '.') { pos++; return dotall ? DOTALL : DOT; }
        if (c == '^') { pos++; return new Ast.StartAnchor(false, this.multiline); }
        if (c == '$') { pos++; return new Ast.EndAnchor(false, this.multiline); }
        if (c == '\\') { pos++; return parseEscape(); }
        if (c == ')' || c == '|') throw fail(this, "unexpected '" + c + "'");
        // A quantifier at atom position (start of pattern, after '(' or '|')
        // has nothing to repeat: re2j fails "missing argument to repetition
        // operator". An INVALID brace form ({,2}) is still a literal '{'.
        if (c == '*' || c == '+' || c == '?') throw fail(this, "missing argument to repetition operator");
        if (c == '{') {
            if (parseBraces() != null) throw fail(this, "missing argument to repetition operator");
            // not a quantifier: fall through, '{' is a literal
        }
        int cp = Alphabet.decode(src, pos, src.length());
        pos += Alphabet.width(cp);
        if (caseInsensitive) {
            Ast folded = caseFoldChar(cp);
            if (folded != null) return folded;
        }
        return literalAtom(cp);
    }

    /** group := '(' ('?:')? alt ')' | '(' '?flags' ')' | '(' '?flags:' alt ')' | '(' '?<' name '>' alt ')' | '(' '?P<' name '>' alt ')' */
    private Ast parseGroup() {
        expect('(');
        // Flags are scoped to the enclosing group (re2j semantics, verified
        // against re2j 1.8: `((?i)a)b` does NOT fold `b` — every ')' restores
        // the flag state saved at its '('). Flag-ONLY groups `(?i)` are the
        // exception: they apply until the end of the ENCLOSING group, so their
        // exit must not restore (flagOnly below). unicodeShorthand restores
        // too: \w/\d/\s class nodes materialize ranges at parse time
        // (correctly scoped); the pattern-global runtime \b word table
        // reflects the final top-level flag state.
        boolean savedCi = caseInsensitive, savedDs = dotall, savedMl = multiline;
        boolean savedUs = unicodeShorthand, savedUg = ungreedy;
        boolean[] flagOnly = {false};
        // Recursion depth cap: parseAlt→…→parseGroup is the only recursive
        // cycle, and it is stack-frame-per-paren (re2j parses iteratively and
        // needs no cap). 1000 is far beyond any sane pattern and well under
        // the default-stack SOE threshold; deeper input is a clean parse
        // error, never a StackOverflowError through Pattern.compile.
        if (++depth > MAX_GROUP_DEPTH)
            throw fail(this, "group nesting too deep (>" + MAX_GROUP_DEPTH + ")");
        try {
            return parseGroupBody(flagOnly);
        } finally {
            depth--;
            if (!flagOnly[0]) {
                caseInsensitive = savedCi;
                dotall = savedDs;
                multiline = savedMl;
                unicodeShorthand = savedUs;
                ungreedy = savedUg;
            }
        }
    }

    /** Group body after '(' — the caller saved flags, enforces the depth cap,
     *  and restores flags unless the body is a flag-only group (which marks
     *  {@code flagOnly[0]}; its flags must persist into the enclosing group). */
    private Ast parseGroupBody(boolean[] flagOnly) {
        boolean capturing = true;
        String groupName = null;
        if (pos + 1 < src.length() && src.charAt(pos) == '?' && src.charAt(pos + 1) == ':') {
            pos += 2; capturing = false;
        } else if (pos < src.length() && peek() == '?') {
            pos++; // consume '?'
            // Reject DFA-incompatible group syntax
            char afterQ = peek();
            if (afterQ == '=' || afterQ == '!') throw fail(this, "lookahead not supported");
            if (afterQ == '>') throw fail(this, "atomic groups not supported");
            if (afterQ == '<') {
                char next = pos + 1 < src.length() ? src.charAt(pos + 1) : '\0';
                if (next == '=' || next == '!') throw fail(this, "lookbehind not supported");
                // Named group (?<name>...)
                pos++; // consume '<'
                groupName = readGroupName();
            } else if (afterQ == 'P' && pos + 1 < src.length() && src.charAt(pos + 1) == '<') {
                // Named group (?P<name>...)
                pos += 2; // consume 'P<'
                groupName = readGroupName();
            } else {
                // Parse inline flags: (?i) (?s) (?m) (?U) (?u) (?-s) (?i:...) (?ism:...)
                // Unknown flag letters are an error (re2j: "invalid or
                // unsupported Perl syntax"), never a silent no-op.
                boolean ci = false, ds = false, ml = false, us = false, ug = false;
                boolean ciSet = false, dsSet = false, mlSet = false, usSet = false, ugSet = false;
                boolean neg = false;
                boolean pendingNeg = false;
                while (pos < src.length() && peek() != ':' && peek() != ')') {
                    char f = peek();
                    if (f == '-') { neg = true; pendingNeg = true; pos++; continue; }
                    switch (f) {
                        case 'i': ci = !neg; ciSet = true; break;
                        case 's': ds = !neg; dsSet = true; break;
                        case 'm': ml = !neg; mlSet = true; break;
                        case 'u': us = !neg; usSet = true; break;
                        case 'U': ug = !neg; ugSet = true; break;
                        default: throw fail(this, "invalid or unsupported Perl syntax: (?+" + f);
                    }
                    neg = false;
                    pendingNeg = false;
                    pos++;
                }
                if (pendingNeg) throw fail(this, "invalid or unsupported Perl syntax: (?-)");
                if (peek() == ':') {
                    pos++; // consume ':'
                    capturing = false;
                    if (ciSet) this.caseInsensitive = ci;
                    if (dsSet) this.dotall = ds;
                    if (mlSet) this.multiline = ml;
                    if (usSet) this.unicodeShorthand = us;
                    if (ugSet) this.ungreedy = ug;
                } else {
                    expect(')');
                    if (ciSet) this.caseInsensitive = ci;
                    if (dsSet) this.dotall = ds;
                    if (mlSet) this.multiline = ml;
                    if (usSet) this.unicodeShorthand = us;
                    if (ugSet) this.ungreedy = ug;
                    flagOnly[0] = true;   // flags persist into the enclosing group
                    return new Ast.Empty(); // flag-only group, continue
                }
            }
        }
        // Allocate tag pair BEFORE recursing into the body so group numbers
        // follow open-paren position (standard regex convention): outer groups
        // get LOWER numbers than their inner groups. Allocating after the
        // recursive parseAlt would assign numbers in close-paren order, which
        // is inside-out.
        int open = -1, close = -1;
        if (capturing) {
            open = nextTag++;
            close = nextTag++;
            groupCount++;
            if (groupName != null) {
                if (groupNames.containsKey(groupName))
                    throw fail(this, "duplicate capture group name: `" + groupName + "`");
                groupNames.put(groupName, groupCount);
            }
        }
        Ast body = parseAlt();
        expect(')');
        if (!capturing) return body;
        return new Ast.Concat(List.of(new Ast.Tag(open), body, new Ast.Tag(close)));
    }

    /** class := '^'? class-item+ ']' */
    private Ast parseClass() {
        boolean negated = false;
        if (peek() == '^') { pos++; negated = true; }
        List<Integer> ranges = new ArrayList<>();
        // POSIX/RE2: a `]` as the FIRST char in the class (after an optional `^`)
        // is a literal class member, not the terminator. So `[]]` matches a single `]`,
        // `[]a]` matches `]` or `a`, etc.
        if (peek() == ']') {
            ranges.add((int) ']'); ranges.add((int) ']');
            pos++;
        }
        while (pos < src.length() && peek() != ']') {
            // Check for POSIX class [:name:]
            if (peek() == '[' && peekAhead() == ':') {
                int[] posix = parsePosixClass();
                for (int v : posix) ranges.add(v);
                continue;
            }
            // Check for shorthand escapes (\s \S \d \D \w \W)
            if (peek() == '\\' && pos + 1 < src.length()) {
                char next = src.charAt(pos + 1);
                int[] sr = shorthandRanges(next);
                if (sr != null) {
                    pos += 2;
                    for (int v : sr) ranges.add(v);
                    continue;
                }
                // Unicode property classes \p{X} \pX \P{X} \PX \p{^X}
                if (next == 'p' || next == 'P') {
                    pos += 2;  // consume '\' and 'p'/'P'
                    appendUnicodeToRanges(ranges, next == 'p', false);
                    continue;
                }
            }
            int lo = parseClassChar();
            int hi = lo;
            if (peek() == '-' && pos + 1 < src.length() && src.charAt(pos + 1) != ']') {
                pos++; hi = parseClassChar();
                if (hi < lo) throw fail(this, "inverted range in class");
            }
            ranges.add(lo); ranges.add(hi);
        }
        expect(']');
        int[] arr = ranges.stream().mapToInt(Integer::intValue).toArray();
        if (caseInsensitive) {
            // Add the case-folded counterparts of every member of every range:
            // full Unicode simple folding under plain (?i) — re2j semantics
            // (verified: (?i)[a-z] matches ſ and K; (?i)[^s] does NOT match ſ).
            // Fold members join the POSITIVE set before CharClass negation, so
            // negated classes exclude fold equivalents automatically. In-class
            // shorthands (\w inside [...]) are ordinary ranges here and fold
            // through the same path.
            arr = foldExpandRanges(arr);
        }
        return new CharClass(arr, negated);
    }

    /** Union of {@code ranges} with every member's full simple-fold orbit (re2j's foldCase).
     *  Output is sorted by lo with overlapping ranges merged — complementRanges
     *  and downstream range walkers rely on that. */
    private int[] foldExpandRanges(int[] arr) {
        List<int[]> ivs = new ArrayList<>(arr.length);
        for (int i = 0; i < arr.length; i += 2) {
            int lo = arr[i], hi = arr[i + 1];
            ivs.add(new int[]{lo, hi});
            for (int cp = lo; cp <= hi; cp++) {
                int[] fr = CaseFoldTable.foldRanges(cp);
                if (fr != null) for (int v : fr) ivs.add(new int[]{v, v});
            }
        }
        ivs.sort((a, b) -> Integer.compare(a[0], b[0]));
        List<Integer> merged = new ArrayList<>(ivs.size() * 2);
        int lo = ivs.get(0)[0], hi = ivs.get(0)[1];
        for (int[] iv : ivs.subList(1, ivs.size())) {
            if (iv[0] <= hi + 1) hi = Math.max(hi, iv[1]);
            else { merged.add(lo); merged.add(hi); lo = iv[0]; hi = iv[1]; }
        }
        merged.add(lo); merged.add(hi);
        return merged.stream().mapToInt(Integer::intValue).toArray();
    }

    /**
     * Returns ranges for shorthand escapes inside char classes, or null if not a shorthand.
     * Returns explicit complement ranges for uppercase variants ({@code \D}, {@code \W}, {@code \S})
     * since they are merged into the class's own range list.
     * When {@link #unicodeShorthand} is true, returns Unicode-aware ranges (matching
     * {@code java.util.regex} with {@code UNICODE_CHARACTER_CLASS}).
     */
    private int[] shorthandRanges(char c) {
        // Under (?i), uppercase shorthands complement the FOLDED positive set
        // (re2j folds before negation: (?i)[\W] does not match ſ). The positive
        // lowercase forms fold later via the class-level foldExpandRanges.
        boolean ci = caseInsensitive;
        if (!unicodeShorthand) {
            return switch (c) {
                case 'd' -> R_DIGIT;
                case 'D' -> R_NOT_DIGIT;
                case 'w' -> R_WORD;
                case 'W' -> ci ? complementRanges(foldExpandRanges(R_WORD)) : R_NOT_WORD;
                case 's' -> R_SPACE;
                case 'S' -> R_NOT_SPACE;
                default -> null;
            };
        }
        return switch (c) {
            case 'd' -> unicodeDigitRanges();
            case 'D' -> complementRanges(unicodeDigitRanges());
            case 'w' -> computeUnicodeWordRanges();
            case 'W' -> complementRanges(ci ? foldExpandRanges(computeUnicodeWordRanges()) : computeUnicodeWordRanges());
            case 's' -> R_UNICODE_SPACE;
            case 'S' -> complementRanges(R_UNICODE_SPACE);
            default -> null;
        };
    }

    /** Build a shorthand escape atom (called from {@link #parseEscape}). */
    private Ast shorthandEscape(char c) {
        boolean negated = Character.isUpperCase(c);
        char base = Character.toLowerCase(c);
        // Under (?i) the word shorthands fold (re2j: (?i)\w matches ſ via the
        // s-orbit of a-z); digits/spaces have no fold orbits and stay as-is.
        // \W folds the POSITIVE set first, then complements.
        if (caseInsensitive && base == 'w') {
            int[] pos = unicodeShorthand ? foldExpandRanges(computeUnicodeWordRanges()) : foldExpandRanges(R_WORD);
            return new CharClass(negated ? complementRanges(pos) : pos, false);
        }
        if (!unicodeShorthand) {
            return switch (c) {
                case 'd' -> DIGIT;
                case 'D' -> NOT_DIGIT;
                case 'w' -> WORD;
                case 'W' -> NOT_WORD;
                case 's' -> WHITESPACE;
                case 'S' -> NOT_WHITESPACE;
                default -> throw new IllegalStateException("not a shorthand: \\" + c);
            };
        }
        int[] ranges;
        if (base == 'w') ranges = computeUnicodeWordRanges();
        else if (base == 'd') ranges = unicodeDigitRanges();
        else ranges = R_UNICODE_SPACE;
        return new CharClass(ranges, negated);
    }

    /** Unicode {@code \d} ranges = {@code \p{Nd}} (decimal digit number). */
    private int[] unicodeDigitRanges() {
        if (cachedUnicodeDigit == null) {
            int[] t = provider.tableFor("Nd");
            if (t == null) t = R_DIGIT; // fallback
            cachedUnicodeDigit = t;
        }
        return cachedUnicodeDigit;
    }

    /**
     * Unicode {@code \w} ranges = {@code L + N + Mn + Me + Pc + Sc + Sk},
     * matching {@code java.util.regex} with {@code UNICODE_CHARACTER_CLASS}.
     * Cached for reuse by {@code \b} runtime.
     */
    private int[] computeUnicodeWordRanges() {
        if (cachedUnicodeWord == null) {
            int[] merged = null;
            for (String cat : new String[]{"L", "N", "Mn", "Me", "Pc", "Sc", "Sk"}) {
                int[] t = provider.tableFor(cat);
                if (t != null) merged = (merged == null) ? t : mergeRanges(merged, t);
            }
            cachedUnicodeWord = merged != null ? merged : R_WORD; // fallback
        }
        return cachedUnicodeWord;
    }

    private int[] cachedUnicodeWord;
    private int[] cachedUnicodeDigit;

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
        while (pos < src.length() && src.charAt(pos) != ':' && src.charAt(pos) != ']') pos++;
        if (pos >= src.length() || src.charAt(pos) != ':') {
            throw fail(this, "invalid POSIX class: missing ':]'");
        }
        String name = src.substring(start, pos);
        pos++;  // consume ':'
        if (pos >= src.length() || src.charAt(pos) != ']') {
            throw fail(this, "invalid POSIX class: missing closing ']'");
        }
        pos++;  // consume ']'
        boolean negated = false;
        if (name.startsWith("^")) { negated = true; name = name.substring(1); }
        int[] r = posixClassRanges(name);
        if (r == null) throw fail(this, "unknown POSIX class: [:" + (negated ? "^" : "") + name + ":]");
        return negated ? complementRanges(r) : r;
    }

    /** ASCII-only POSIX class ranges, matching re2j's CharClass tables. */
    private static int[] posixClassRanges(String name) {
        return switch (name) {
            case "alnum"  -> R_POSIX_ALNUM;
            case "alpha"  -> R_POSIX_ALPHA;
            case "ascii"  -> R_POSIX_ASCII;
            case "blank"  -> R_POSIX_BLANK;
            case "cntrl"  -> R_POSIX_CNTRL;
            case "digit"  -> R_POSIX_DIGIT;
            case "graph"  -> R_POSIX_GRAPH;
            case "lower"  -> R_POSIX_LOWER;
            case "print"  -> R_POSIX_PRINT;
            case "punct"  -> R_POSIX_PUNCT;
            case "space"  -> R_POSIX_SPACE;  // POSIX [:space:] INCLUDES \v (unlike Perl \s)
            case "upper"  -> R_POSIX_UPPER;
            case "word"   -> R_POSIX_WORD;
            case "xdigit" -> R_POSIX_XDIGIT;
            default -> null;
        };
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
            char e = cur(); pos++;
            // Octal escape \NNN (1-3 octal digits, value capped at 0xFF).
            // \1-\9 single-digit rejected (reserved for backreference syntax).
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
                }
                return val;
            }
            return switch (e) {
                case 'n' -> '\n';
                case 't' -> '\t';
                case 'r' -> '\r';
                case 'f' -> '\f';
                case 'a' -> (char) 7;
                case 'v' -> (char) 11;
                case '\\' -> '\\';
                case 'x' -> parseHexChar();
                default -> {
                    // re2j rejects unknown ASCII alphanumeric escapes in class
                    // context (notably [\b] — backspace is unsupported); a
                    // non-ASCII letter or digit is an identity escape there.
                    if (e < 128 && Character.isLetterOrDigit(e)) throw fail(this, "invalid escape sequence: \\" + e);
                    yield e;  // any other (non-alphanumeric) escaped char is literal
                }
            };
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
            while (pos < src.length() && isHex(src.charAt(pos))) pos++;
            if (pos >= src.length() || src.charAt(pos) != '}') {
                throw fail(this, "invalid hex escape: expected '}'");
            }
            String hex = src.substring(start, pos);
            pos++; // consume '}'
            if (hex.isEmpty()) throw fail(this, "invalid hex escape: empty \\x{}");
            val = parseHexValue(hex);
            if (val > 0x10FFFF) throw fail(this, "invalid escape sequence");
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
        char c = cur(); pos++;
        // Octal escape \NNN (1-3 octal digits, value capped at 0xFF = 0377).
        // \0 is the null character; \1-\9 single-digit is rejected (reserved
        // for backreference syntax, which is DFA-incompatible). Multi-digit
        // octal like \12, \101 is accepted.
        if (c >= '0' && c <= '7') {
            int val = c - '0';
            if (pos < src.length() && src.charAt(pos) >= '0' && src.charAt(pos) <= '7') {
                val = val * 8 + (src.charAt(pos) - '0');
                pos++;
                if (val < 32 && pos < src.length() && src.charAt(pos) >= '0' && src.charAt(pos) <= '7') {
                    val = val * 8 + (src.charAt(pos) - '0');
                    pos++;
                }
            } else if (c != '0') {
                throw fail(this, "invalid escape sequence: \\" + c);
            }
            return new Ast.Symbol((char) val);
        }
        return switch (c) {
            case 'n' -> new Ast.Symbol('\n');
            case 't' -> new Ast.Symbol('\t');
            case 'r' -> new Ast.Symbol('\r');
            case 'f' -> new Ast.Symbol('\f');
            case 'a' -> new Ast.Symbol((char) 7);   // alarm/bell, like re2j
            case 'v' -> new Ast.Symbol((char) 11);  // vertical tab, like re2j
            case '\\' -> new Ast.Symbol('\\');
            case 'x' -> parseHexEscape();
            case 'd' -> shorthandEscape('d');
            case 'D' -> shorthandEscape('D');
            case 'w' -> shorthandEscape('w');
            case 'W' -> shorthandEscape('W');
            case 's' -> shorthandEscape('s');
            case 'S' -> shorthandEscape('S');
            // RE2 (and re2j) reject \C as "any byte" — see re2j Parser.java:913.
            // We don't support it either; reject at parse time so silent misparse
            // (treating \C as literal C) doesn't yield wrong matches.
            case 'C' -> throw new IllegalArgumentException("invalid escape sequence: \\C");
            // Zero-width assertions — RE2/re2j implement these fully.
            case 'A' -> new Ast.StartAnchor(true);    // \A = absolute start of text (immune to (?m))
            case 'z' -> new Ast.EndAnchor(true);      // \z = absolute end of text (immune to (?m))
            case 'b' -> new Ast.WordBoundary();         // \b = word boundary
            case 'B' -> new Ast.NoWordBoundary();       // \B = not a word boundary
            // Unicode property classes \p{X} \pX \P{X} \PX \p{^X}.
            // Outside a char class, build a CharClass directly (the table's
            // own negation flag carries the \P sign; no complement materialisation).
            case 'p' -> parseUnicodeEscape(true);
            case 'P' -> parseUnicodeEscape(false);
            case 'Q' -> parseQuotedLiteral();
            default -> {
                // re2j rejects unknown ASCII alphanumeric escapes (\E, \K, \R,
                // \e, \N, ...). Non-ASCII letters/digits (\䑄, \Ω) and any
                // non-alphanumeric escape (\. \- \_ ...) are identity escapes.
                if (c < 128 && Character.isLetterOrDigit(c)) throw fail(this, "invalid escape sequence: \\" + c);
                yield new Ast.Symbol(c);
            }
        };
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
        if (foundE) pos += 2;  // consume \E

        if (literal.isEmpty()) return new Ast.Empty();
        if (literal.length() == 1) {
            char ch = literal.charAt(0);
            if (caseInsensitive) {
                Ast folded = caseFoldChar(ch);
                if (folded != null) return folded;
            }
            return new Ast.Symbol(ch);
        }
        List<Ast> parts = new ArrayList<>();
        for (int i = 0; i < literal.length(); ) {
            int cp = Alphabet.decode(literal, i, literal.length());
            i += Alphabet.width(cp);
            if (caseInsensitive) {
                Ast folded = caseFoldChar(cp);
                if (folded != null) { parts.add(folded); continue; }
            }
            parts.add(literalAtom(cp));
        }
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
        if (disableUnicodeGroups)
            throw fail(this, "Unicode groups (\\p/\\P) disabled by DISABLE_UNICODE_GROUPS flag");
        String name = parseUnicodeName();
        boolean innerNeg = false;
        if (name.startsWith("^")) { innerNeg = true; name = name.substring(1); }
        // Truth table:
        //   \p{X}  → (T, F) → negated=F
        //   \p{^X} → (T, T) → negated=T
        //   \P{X}  → (F, F) → negated=T
        //   \P{^X} → (F, T) → negated=F
        boolean negated = (positive == innerNeg);

        int[] t = provider.tableFor(name);
        if (t == null) throw fail(this, "unknown character class name: " + name);
        int[] fold = caseInsensitive ? provider.foldTableFor(name) : null;
        if (fold != null && fold.length > 0) t = mergeRanges(t, fold);
        return new CharClass(t, negated);
    }

    /** Append Unicode property ranges into a class's accumulator. The caller
     *  has already consumed {@code \p} / {@code \P}; we read the name and
     *  materialise the ranges (with complement if {@code \P}) into {@code out}.
     *  The class's own {@code [^...]} negation is applied at the end via the
     *  existing CharClass path; here we only handle the escape's own sign. */
    private void appendUnicodeToRanges(List<Integer> out, boolean positive, boolean unused) {
        if (disableUnicodeGroups)
            throw fail(this, "Unicode groups (\\p/\\P) disabled by DISABLE_UNICODE_GROUPS flag");
        String name = parseUnicodeName();
        boolean innerNeg = false;
        if (name.startsWith("^")) { innerNeg = true; name = name.substring(1); }
        boolean negate = (positive == innerNeg);  // see parseUnicodeEscape truth table
        int[] t = provider.tableFor(name);
        if (t == null) throw fail(this, "unknown character class name: " + name);
        int[] fold = caseInsensitive ? provider.foldTableFor(name) : null;
        if (fold != null && fold.length > 0) t = mergeRanges(t, fold);
        if (negate) t = complementRanges(t);
        for (int v : t) out.add(v);
    }

    /** Read a Unicode property name after {@code \p}/{@code \P}: either
     *  braced {@code {name}} or a single letter {@code L}, {@code N}, etc. */
    private String parseUnicodeName() {
        if (pos < src.length() && src.charAt(pos) == '{') {
            pos++;
            int start = pos;
            while (pos < src.length() && src.charAt(pos) != '}') pos++;
            if (pos >= src.length()) throw fail(this, "unclosed '\\p{' in pattern");
            String name = src.substring(start, pos);
            pos++;  // consume '}'
            if (name.isEmpty()) throw fail(this, "empty property name in '\\p{}'");
            return name;
        }
        if (pos >= src.length()) throw fail(this, "incomplete '\\p' escape");
        String name = String.valueOf(src.charAt(pos));
        pos++;
        return name;
    }

    /** Compute the complement of {@code ranges} within [0, 0x10FFFF]. */
    private static int[] complementRanges(int[] ranges) {
        List<Integer> out = new ArrayList<>();
        int prev = 0;
        for (int i = 0; i < ranges.length; i += 2) {
            int lo = ranges[i], hi = ranges[i + 1];
            if (lo > prev) { out.add(prev); out.add(lo - 1); }
            prev = Math.max(prev, hi + 1);
            if (prev > 0x10FFFF) break;
        }
        if (prev <= 0x10FFFF) { out.add(prev); out.add(0x10FFFF); }
        return out.stream().mapToInt(Integer::intValue).toArray();
    }

    /** Merge two sorted range arrays into a sorted, merged result. */
    private static int[] mergeRanges(int[] a, int[] b) {
        List<int[]> all = new ArrayList<>();
        for (int i = 0; i < a.length; i += 2) all.add(new int[]{a[i], a[i + 1]});
        for (int i = 0; i < b.length; i += 2) all.add(new int[]{b[i], b[i + 1]});
        all.sort((x, y) -> Integer.compare(x[0], y[0]));
        List<int[]> merged = new ArrayList<>();
        for (int[] r : all) {
            if (!merged.isEmpty() && r[0] <= merged.get(merged.size() - 1)[1] + 1) {
                merged.get(merged.size() - 1)[1] = Math.max(merged.get(merged.size() - 1)[1], r[1]);
            } else {
                merged.add(new int[]{r[0], r[1]});
            }
        }
        int[] out = new int[merged.size() * 2];
        for (int i = 0; i < merged.size(); i++) {
            out[2 * i]     = merged.get(i)[0];
            out[2 * i + 1] = merged.get(i)[1];
        }
        return out;
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
        int val;
        if (pos < src.length() && src.charAt(pos) == '{') {
            pos++;
            int start = pos;
            while (pos < src.length() && isHex(src.charAt(pos))) pos++;
            if (pos >= src.length() || src.charAt(pos) != '}') {
                throw fail(this, "invalid hex escape: expected '}'");
            }
            String hex = src.substring(start, pos);
            pos++; // consume '}'
            if (hex.isEmpty()) throw fail(this, "invalid hex escape: empty \\x{}");
            val = parseHexValue(hex);
            if (val > 0x10FFFF) throw fail(this, "invalid escape sequence");
            if (val > 0xFFFF) return new CharClass(new int[]{val, val}, false);
        } else {
            if (pos + 1 >= src.length() || !isHex(src.charAt(pos)) || !isHex(src.charAt(pos + 1))) {
                throw fail(this, "invalid hex escape: expected exactly 2 hex digits after \\x");
            }
            val = (hexVal(src.charAt(pos)) << 4) | hexVal(src.charAt(pos + 1));
            pos += 2;
        }
        return new Ast.Symbol((char) val);
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    /** Hex value of a \x{...} body, overflow-safe: after skipping leading
     *  zeros (RE2's own corpus uses zero-padded forms like \x{00000061}), 7+
     *  significant digits cannot be a codepoint — "invalid escape sequence"
     *  like re2j, never a raw NumberFormatException. */
    private static int parseHexValue(String hex) {
        int i = 0;
        while (i < hex.length() - 1 && hex.charAt(i) == '0') i++;
        String s = hex.substring(i);
        if (s.length() > 6) return Integer.MAX_VALUE;   // > 0xFFFFFF, over any codepoint
        try {
            return Integer.parseInt(s, 16);
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    private static int hexVal(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
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
        while (pos < src.length() && src.charAt(pos) >= '0' && src.charAt(pos) <= '9') pos++;
        if (pos == start) { pos = save; return null; } // not a quantifier; treat as literal '{'
        int min = parseRepeatCount(src, start, pos);
        int max = min;
        if (peek() == ',') {
            pos++;
            int mStart = pos;
            while (pos < src.length() && src.charAt(pos) >= '0' && src.charAt(pos) <= '9') pos++;
            max = (mStart == pos) ? Integer.MAX_VALUE : parseRepeatCount(src, mStart, pos);
        }
        if (peek() != '}') { pos = save; return null; }
        pos++; // consume '}'
        if (max < min) throw fail(this, "min > max in {" + min + "," + max + "}");
        return new int[]{min, max};
    }

    /** Bounded repeat count: ASCII digits in [start, end), ≤ MAX_REPEAT_COUNT
     *  (re2j's cap), no overflow — anything longer/larger is "invalid repeat
     *  count" like re2j, not a NumberFormatException. */
    private static int parseRepeatCount(String s, int start, int end) {
        int val = 0;
        for (int i = start; i < end; i++) {
            val = val * 10 + (s.charAt(i) - '0');
            if (val > MAX_REPEAT_COUNT) break;   // further digits cannot help
        }
        if (val > MAX_REPEAT_COUNT)
            throw new IllegalArgumentException("Parse error at index " + start
                    + ": invalid repeat count (in \"" + s + "\")");
        return val;
    }

    private char peek() { return pos < src.length() ? src.charAt(pos) : '\0'; }
    private char peekAhead() { return pos + 1 < src.length() ? src.charAt(pos + 1) : '\0'; }
    private char cur() {
        if (pos >= src.length()) throw fail(this, "unexpected end of input");
        return src.charAt(pos);
    }
    private void expect(char c) {
        if (pos >= src.length() || src.charAt(pos) != c) throw fail(this, "expected '" + c + "'");
        pos++;
    }

    public int groupCount() { return groupCount; }
    public int tagCount() { return nextTag - 1; }
    public boolean multiline() { return multiline; }
    public boolean unicodeShorthand() { return unicodeShorthand; }
    /** Unicode {@code \w} ranges for runtime {@code \b} word-boundary checks, or null if Unicode shorthand not enabled. */
    public int[] unicodeWordRanges() { return unicodeShorthand ? computeUnicodeWordRanges() : null; }
    public Map<String, Integer> namedGroups() { return groupNames; }

    /** Read a group name between '<' and '>' (caller has consumed '<').
     *  re2j validity: word characters only ([A-Za-z0-9_], digits-first allowed
     *  — verified against re2j 1.8: (?<1a>x) compiles, (?<a b>)/(?<a-b>) are
     *  "invalid named capture"). Group names are API-visible via
     *  namedGroups(), so garbage must not slip through. */
    private String readGroupName() {
        int start = pos;
        while (pos < src.length() && peek() != '>') pos++;
        if (pos >= src.length()) throw fail(this, "unclosed group name");
        String name = src.substring(start, pos);
        pos++; // consume '>'
        if (name.isEmpty()) throw fail(this, "invalid named capture");
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            boolean word = (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')
                    || (ch >= '0' && ch <= '9') || ch == '_';
            if (!word) throw fail(this, "invalid named capture");
        }
        return name;
    }

    private static IllegalArgumentException fail(Parser p, String msg) {
        return new IllegalArgumentException("Parse error at index " + p.pos + ": " + msg + " (in \"" + p.src + "\")");
    }

    // ---- predefined classes ----

    private static final int[] R_DIGIT = {'0', '9'};
    private static final int[] R_NOT_DIGIT = {0, '/', ':', 0x10FFFF};
    private static final int[] R_WORD = {'a','z','A','Z','0','9','_','_'};
    private static final int[] R_NOT_WORD = {0, '/', ':', '@', '[', '^', '`', '`', '{', 0x10FFFF};
    // re2j's \s = [\t\n\f\r ] — note: no \v (U+000B), unlike POSIX [:space:].
    private static final int[] R_SPACE = {'\t', '\n', '\f', '\r', ' ', ' '};
    private static final int[] R_NOT_SPACE = {0, '\t' - 1, 0x0B, 0x0B, '\r' + 1, ' ' - 1, ' ' + 1, 0x10FFFF};

    // Unicode IsWhite_Space property (java.util.regex \s with UNICODE_CHARACTER_CLASS).
    private static final int[] R_UNICODE_SPACE = {
        '\t', '\r',           // U+0009-U+000D (HT, LF, VT, FF, CR)
        0x1C, 0x1F,           // FS, GS, RS, US
        ' ', ' ',             // Space
        0x85, 0x85,           // NEL
        0xA0, 0xA0,           // NBSP
        0x1680, 0x1680,       // Ogham Space
        0x2000, 0x200A,       // En–Hair Space
        0x2028, 0x2029,       // LS, PS
        0x202F, 0x202F,       // Narrow NBSP
        0x205F, 0x205F,       // Medium Math Space
        0x3000, 0x3000        // Ideographic Space
    };

    // POSIX character classes — ASCII-only, matching re2j's CharGroup tables.
    // Note: [:space:] INCLUDES \v (U+000B) per POSIX, unlike Perl's \s.
    private static final int[] R_POSIX_ALNUM  = {'0', '9', 'A', 'Z', 'a', 'z'};
    private static final int[] R_POSIX_ALPHA  = {'A', 'Z', 'a', 'z'};
    private static final int[] R_POSIX_ASCII  = {0, 0x7F};
    private static final int[] R_POSIX_BLANK  = {'\t', '\t', ' ', ' '};
    private static final int[] R_POSIX_CNTRL  = {0, 0x1F, 0x7F, 0x7F};
    private static final int[] R_POSIX_DIGIT  = {'0', '9'};
    private static final int[] R_POSIX_GRAPH  = {0x21, 0x7E};
    private static final int[] R_POSIX_LOWER  = {'a', 'z'};
    private static final int[] R_POSIX_PRINT  = {' ', 0x7E};
    private static final int[] R_POSIX_PUNCT  = {0x21, '/', ':', '@', '[', '`', '{', '~'};
    private static final int[] R_POSIX_SPACE  = {'\t', '\r', ' ', ' '};  // \t\n\v\f\r and space
    private static final int[] R_POSIX_UPPER  = {'A', 'Z'};
    private static final int[] R_POSIX_WORD   = {'0', '9', 'A', 'Z', '_', '_', 'a', 'z'};
    private static final int[] R_POSIX_XDIGIT = {'0', '9', 'A', 'F', 'a', 'f'};

    static final CharClass DOT = new CharClass(new int[]{0, '\n' - 1, '\n' + 1, 0x10FFFF}, false);
    static final CharClass DOTALL = new CharClass(new int[]{0, 0x10FFFF}, false);
    static final CharClass DIGIT = new CharClass(R_DIGIT, false);
    static final CharClass NOT_DIGIT = new CharClass(R_DIGIT, true);
    static final CharClass WORD = new CharClass(R_WORD, false);
    static final CharClass NOT_WORD = new CharClass(R_WORD, true);
    static final CharClass WHITESPACE = new CharClass(R_SPACE, false);
    static final CharClass NOT_WHITESPACE = new CharClass(R_SPACE, true);
}
