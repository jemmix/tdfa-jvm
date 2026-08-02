package io.github.jemmix.tdfa.parser;

import io.github.jemmix.tdfa.ast.Ast;
import io.github.jemmix.tdfa.ast.CharClass;
import io.github.jemmix.tdfa.unicode.UnicodeProviders;

import java.util.ArrayList;
import java.util.List;

/**
 * Hand-rolled recursive-descent parser for a PCRE-ish subset:
 * - literals, escape sequences (\n \t \r \f \a \v \d \D \w \W \s \S)
 * - hex escapes (\xNN, \x{N+}), octal escapes (\NNN, 1–3 digits capped at 0xFF)
 * - char classes [abc], [a-z], [^...], with POSIX classes [:alpha:] etc.
 * - Unicode property classes \p{X} \P{X} (BMP only)
 * - quantifiers: * + ? {n} {n,} {n,m} (greedy and lazy)
 * - alternation |
 * - capturing groups (...), non-capturing (?:...)
 * - inline flags (?i) (?s) (?-i) (?-s) (?i:...) (?is:...)
 * - anchors ^ $ \A \z \b \B
 * - dot . (excludes \n unless (?s))
 *
 * NOT supported (rejected at parse time):
 * - backreferences (\1), lookarounds (?=...), atomic groups (?>...)
 * - possessive quantifiers (*+ ++ ?+)
 * - \C (any byte), \Q...\E (literal quoting)
 * - (?m) multiline mode (accepted but no-op)
 * - named groups (?P<name>...) (?<name>...)
 * - non-BMP codepoints in \x{...} and \p{...}
 */
public final class Parser {
    private final String src;
    private int pos = 0;
    private int nextTag = 1;
    private int groupCount = 0;
    boolean caseInsensitive = false;
    boolean dotall = false;

    private Parser(String src) { this.src = src; }

    private Ast lastAst;

    public static Ast parse(String src) {
        Parser p = new Parser(src);
        Ast e = p.parseAlt();
        if (p.pos != p.src.length()) throw fail(p, "unexpected '" + p.cur() + "'");
        return e;
    }

    /** Side-effect: parses, leaves tag/group counters accessible. */
    public static Parser capture(String src) {
        Parser p = new Parser(src);
        p.lastAst = p.parseAlt();
        if (p.pos != p.src.length()) throw fail(p, "unexpected '" + p.cur() + "'");
        return p;
    }

    public Ast lastAst() { return lastAst; }

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

        boolean greedy = true;
        if (pos < src.length() && peek() == '?') { pos++; greedy = false; }
        else if (pos < src.length() && peek() == '+') {
            throw new UnsupportedOperationException("possessive quantifiers not supported (non-regular)");
        }
        return new Ast.Repeat(atom, min, max, greedy);
    }

    /** atom := group | class | dot | anchor | escape | literal */
    private Ast parseAtom() {
        char c = cur();
        if (c == '(') return parseGroup();
        if (c == '[') { pos++; return parseClass(); }
        if (c == '.') { pos++; return dotall ? DOTALL : DOT; }
        if (c == '^') { pos++; return new Ast.StartAnchor(); }
        if (c == '$') { pos++; return new Ast.EndAnchor(); }
        if (c == '\\') { pos++; return parseEscape(); }
        if (c == ')' || c == '|') throw fail(this, "unexpected '" + c + "'");
        pos++;
        if (caseInsensitive) {
            char lo = Character.toLowerCase(c);
            char hi = Character.toUpperCase(c);
            if (lo != hi) return new CharClass(new int[]{lo, lo, hi, hi}, false);
        }
        return new Ast.Symbol(c);
    }

    /** group := '(' ('?:')? alt ')' | '(' '?flags' ')' | '(' '?flags:' alt ')' */
    private Ast parseGroup() {
        expect('(');
        boolean capturing = true;
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
                throw fail(this, "named groups not supported");
            }
            // Parse inline flags: (?i) (?s) (?m) (?-s) (?i:...) (?is:...)
            boolean ci = false, ds = false;
            boolean neg = false;
            while (pos < src.length() && peek() != ':' && peek() != ')') {
                char f = peek();
                if (f == '-') { neg = true; pos++; continue; }
                switch (f) {
                    case 'i': ci = !neg; break;
                    case 's': ds = !neg; break;
                }
                neg = false;
                pos++;
            }
            if (peek() == ':') {
                pos++; // consume ':'
                capturing = false;
                this.caseInsensitive |= ci;
                this.dotall |= ds;
            } else {
                expect(')');
                this.caseInsensitive |= ci;
                this.dotall |= ds;
                return new Ast.Empty(); // flag-only group, continue
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
                int[] sr = shorthandClassRanges(next);
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
            char lo = parseClassChar();
            char hi = lo;
            if (peek() == '-' && pos + 1 < src.length() && src.charAt(pos + 1) != ']') {
                pos++; hi = parseClassChar();
                if (hi < lo) throw fail(this, "inverted range in class");
            }
            ranges.add((int) lo); ranges.add((int) hi);
        }
        expect(']');
        int[] arr = ranges.stream().mapToInt(Integer::intValue).toArray();
        if (caseInsensitive) {
            // For each user range [lo,hi], add the case-folded counterparts of
            // any ASCII letter sub-range. The previous implementation computed
            // toLowerCase(hi)/toUpperCase(hi) on the ENDPOINTS, which produced
            // nonsense spans like [@-a] for [@-A] (covering all of [\]^_` in
            // between). The correct semantics: for any sub-range overlapping
            // A-Z, add the lowercase equivalent; for any overlapping a-z, add
            // the uppercase equivalent. Non-letter chars (including _ @ ` etc.)
            // have no case fold and contribute nothing.
            //
            // Limited to ASCII for now; full Unicode case folding (Greek, etc.)
            // would require per-codepoint expansion or UnicodeCaseFold tables.
            List<Integer> exp = new ArrayList<>();
            for (int i = 0; i < arr.length; i += 2) {
                int lo = arr[i], hi = arr[i + 1];
                exp.add(lo); exp.add(hi);
                int aStart = Math.max(lo, 'A'), aEnd = Math.min(hi, 'Z');
                if (aStart <= aEnd) { exp.add(aStart + 32); exp.add(aEnd + 32); }  // A-Z → a-z
                int laStart = Math.max(lo, 'a'), laEnd = Math.min(hi, 'z');
                if (laStart <= laEnd) { exp.add(laStart - 32); exp.add(laEnd - 32); }  // a-z → A-Z
            }
            arr = exp.stream().mapToInt(Integer::intValue).toArray();
        }
        return new CharClass(arr, negated);
    }

    /** Returns ranges for shorthand escapes inside char classes, or null if not a shorthand. */
    private static int[] shorthandClassRanges(char c) {
        return switch (c) {
            case 'd' -> R_DIGIT;
            case 'D' -> R_NOT_DIGIT;
            case 'w' -> R_WORD;
            case 'W' -> R_NOT_WORD;
            case 's' -> R_SPACE;
            case 'S' -> R_NOT_SPACE;
            default -> null;
        };
    }

    /**
     * Parses a POSIX character class {@code [:name:]} (called only when {@code peek()=='['}
     * and {@code peekAhead()==':'). Returns the corresponding ASCII range table matching
     * re2j's POSIX semantics, or throws on malformed syntax or unknown names.
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
        int[] r = posixClassRanges(name);
        if (r == null) throw fail(this, "unknown POSIX class: [:" + name + ":]");
        return r;
    }

    /** ASCII-only POSIX class ranges, matching re2j's CharClass tables. */
    private static int[] posixClassRanges(String name) {
        return switch (name) {
            case "alnum"  -> R_POSIX_ALNUM;
            case "alpha"  -> R_POSIX_ALPHA;
            case "blank"  -> R_POSIX_BLANK;
            case "cntrl"  -> R_POSIX_CNTRL;
            case "digit"  -> R_POSIX_DIGIT;
            case "graph"  -> R_POSIX_GRAPH;
            case "lower"  -> R_POSIX_LOWER;
            case "print"  -> R_POSIX_PRINT;
            case "punct"  -> R_POSIX_PUNCT;
            case "space"  -> R_POSIX_SPACE;  // POSIX [:space:] INCLUDES \v (unlike Perl \s)
            case "upper"  -> R_POSIX_UPPER;
            case "xdigit" -> R_POSIX_XDIGIT;
            default -> null;
        };
    }

    private char parseClassChar() {
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
                return (char) val;
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
                default -> e;  // any other escaped char is literal
            };
        }
        pos++;
        return c;
    }

    /** Like {@link #parseHexEscape()} but returns a {@code char} for class membership. */
    private char parseHexChar() {
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
            val = Integer.parseInt(hex, 16);
            if (val > 0xFFFF) {
                throw fail(this, "non-BMP hex escape \\x{" + hex + "} not yet supported");
            }
        } else {
            if (pos + 1 >= src.length() || !isHex(src.charAt(pos)) || !isHex(src.charAt(pos + 1))) {
                throw fail(this, "invalid hex escape: expected exactly 2 hex digits after \\x");
            }
            val = (hexVal(src.charAt(pos)) << 4) | hexVal(src.charAt(pos + 1));
            pos += 2;
        }
        return (char) val;
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
            case 'd' -> DIGIT;
            case 'D' -> NOT_DIGIT;
            case 'w' -> WORD;
            case 'W' -> NOT_WORD;
            case 's' -> WHITESPACE;
            case 'S' -> NOT_WHITESPACE;
            // RE2 (and re2j) reject \C as "any byte" — see re2j Parser.java:913.
            // We don't support it either; reject at parse time so silent misparse
            // (treating \C as literal C) doesn't yield wrong matches.
            case 'C' -> throw new IllegalArgumentException("invalid escape sequence: \\C");
            // Zero-width assertions — RE2/re2j implement these fully.
            case 'A' -> new Ast.StartAnchor();          // \A = start of text (== ^ in default mode)
            case 'z' -> new Ast.EndAnchor();            // \z = end of text (no before-\n special case)
            case 'b' -> new Ast.WordBoundary();         // \b = word boundary
            case 'B' -> new Ast.NoWordBoundary();       // \B = not a word boundary
            // Unicode property classes \p{X} \pX \P{X} \PX \p{^X}.
            // Outside a char class, build a CharClass directly (the table's
            // own negation flag carries the \P sign; no complement materialisation).
            case 'p' -> parseUnicodeEscape(true);
            case 'P' -> parseUnicodeEscape(false);
            default -> new Ast.Symbol(c);
        };
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
        String name = parseUnicodeName();
        boolean innerNeg = false;
        if (name.startsWith("^")) { innerNeg = true; name = name.substring(1); }
        // Truth table:
        //   \p{X}  → (T, F) → negated=F
        //   \p{^X} → (T, T) → negated=T
        //   \P{X}  → (F, F) → negated=T
        //   \P{^X} → (F, T) → negated=F
        boolean negated = (positive == innerNeg);

        int[] t = UnicodeProviders.get().tableFor(name);
        if (t == null) throw fail(this, "unknown character class name: " + name);
        int[] fold = caseInsensitive ? UnicodeProviders.get().foldTableFor(name) : null;
        if (fold != null && fold.length > 0) t = mergeRanges(t, fold);
        t = clampToBmp(t);
        return new CharClass(t, negated);
    }

    /** Append Unicode property ranges into a class's accumulator. The caller
     *  has already consumed {@code \p} / {@code \P}; we read the name and
     *  materialise the ranges (with complement if {@code \P}) into {@code out}.
     *  The class's own {@code [^...]} negation is applied at the end via the
     *  existing CharClass path; here we only handle the escape's own sign. */
    private void appendUnicodeToRanges(List<Integer> out, boolean positive, boolean unused) {
        String name = parseUnicodeName();
        boolean innerNeg = false;
        if (name.startsWith("^")) { innerNeg = true; name = name.substring(1); }
        boolean negate = (positive == innerNeg);  // see parseUnicodeEscape truth table
        int[] t = UnicodeProviders.get().tableFor(name);
        if (t == null) throw fail(this, "unknown character class name: " + name);
        int[] fold = caseInsensitive ? UnicodeProviders.get().foldTableFor(name) : null;
        if (fold != null && fold.length > 0) t = mergeRanges(t, fold);
        t = clampToBmp(t);
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

    /** Compute the complement of {@code ranges} within [0, 0xFFFF]. */
    private static int[] complementRanges(int[] ranges) {
        List<Integer> out = new ArrayList<>();
        int prev = 0;
        for (int i = 0; i < ranges.length; i += 2) {
            int lo = ranges[i], hi = ranges[i + 1];
            if (lo > prev) { out.add(prev); out.add(lo - 1); }
            prev = Math.max(prev, hi + 1);
            if (prev > 0xFFFF) break;
        }
        if (prev <= 0xFFFF) { out.add(prev); out.add(0xFFFF); }
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

    /** Truncate ranges to the BMP (0..0xFFFF); ranges entirely above 0xFFFF
     *  are dropped, ranges spanning the boundary are clamped. Necessary
     *  because CharClass is currently {@code char}-based. */
    private static int[] clampToBmp(int[] ranges) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < ranges.length; i += 2) {
            int lo = ranges[i], hi = ranges[i + 1];
            if (lo > 0xFFFF) continue;
            out.add(lo);
            out.add(Math.min(hi, 0xFFFF));
        }
        return out.stream().mapToInt(Integer::intValue).toArray();
    }

    /**
     * Parse a hex escape after the leading {@code \x} (caller has consumed both).
     * Supports both forms (re2j-compatible):
     * <ul>
     *   <li>{@code \xNN} — exactly 2 hex digits, value 0x00-0xFF.</li>
     *   <li>{@code \x{N+}} — 1+ hex digits enclosed in braces, value 0-0xFFFF.
     *       Values above 0xFFFF (non-BMP codepoints) are rejected until full
     *       codepoint-aware CharClass support lands.</li>
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
            val = Integer.parseInt(hex, 16);
            if (val > 0xFFFF) {
                throw fail(this, "non-BMP hex escape \\x{" + hex + "} not yet supported");
            }
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

    private static int hexVal(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        return c - 'A' + 10;
    }

    private int[] parseBraces() {
        int save = pos;
        pos++; // consume '{'
        int start = pos;
        while (pos < src.length() && Character.isDigit(src.charAt(pos))) pos++;
        if (pos == start) { pos = save; return null; } // not a quantifier; treat as literal '{'
        int min = Integer.parseInt(src.substring(start, pos));
        int max = min;
        if (peek() == ',') {
            pos++;
            int mStart = pos;
            while (pos < src.length() && Character.isDigit(src.charAt(pos))) pos++;
            max = (mStart == pos) ? Integer.MAX_VALUE : Integer.parseInt(src.substring(mStart, pos));
        }
        if (peek() != '}') { pos = save; return null; }
        pos++; // consume '}'
        if (max < min) throw fail(this, "min > max in {" + min + "," + max + "}");
        return new int[]{min, max};
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

    private static IllegalArgumentException fail(Parser p, String msg) {
        return new IllegalArgumentException("Parse error at index " + p.pos + ": " + msg + " (in \"" + p.src + "\")");
    }

    // ---- predefined classes ----
    private static final int[] R_DIGIT = {'0', '9'};
    private static final int[] R_NOT_DIGIT = {0, '/', ':', 0xFFFF};
    private static final int[] R_WORD = {'a','z','A','Z','0','9','_','_'};
    private static final int[] R_NOT_WORD = {0, '/', ':', '@', '[', '^', '`', '`', '{', 0xFFFF};
    // re2j's \s = [\t\n\f\r ] — note: no \v (U+000B), unlike POSIX [:space:].
    private static final int[] R_SPACE = {'\t', '\n', '\f', '\r', ' ', ' '};
    private static final int[] R_NOT_SPACE = {0, '\t' - 1, 0x0B, 0x0B, '\r' + 1, ' ' - 1, ' ' + 1, 0xFFFF};

    // POSIX character classes — ASCII-only, matching re2j's CharClass tables.
    // Note: [:space:] INCLUDES \v (U+000B) per POSIX, unlike Perl's \s.
    private static final int[] R_POSIX_ALNUM  = {'0', '9', 'A', 'Z', 'a', 'z'};
    private static final int[] R_POSIX_ALPHA  = {'A', 'Z', 'a', 'z'};
    private static final int[] R_POSIX_BLANK  = {'\t', '\t', ' ', ' '};
    private static final int[] R_POSIX_CNTRL  = {0, 0x1F, 0x7F, 0x7F};
    private static final int[] R_POSIX_DIGIT  = {'0', '9'};
    private static final int[] R_POSIX_GRAPH  = {0x21, 0x7E};
    private static final int[] R_POSIX_LOWER  = {'a', 'z'};
    private static final int[] R_POSIX_PRINT  = {' ', 0x7E};
    private static final int[] R_POSIX_PUNCT  = {0x21, '/', ':', '@', '[', '`', '{', '~'};
    private static final int[] R_POSIX_SPACE  = {'\t', '\r', ' ', ' '};  // \t\n\v\f\r and space
    private static final int[] R_POSIX_UPPER  = {'A', 'Z'};
    private static final int[] R_POSIX_XDIGIT = {'0', '9', 'A', 'F', 'a', 'f'};

    static final CharClass DOT = new CharClass(new int[]{0, '\n' - 1, '\n' + 1, 0xFFFF}, false);
    static final CharClass DOTALL = new CharClass(new int[]{0, 0xFFFF}, false);
    static final CharClass DIGIT = new CharClass(R_DIGIT, false);
    static final CharClass NOT_DIGIT = new CharClass(R_DIGIT, true);
    static final CharClass WORD = new CharClass(R_WORD, false);
    static final CharClass NOT_WORD = new CharClass(R_WORD, true);
    static final CharClass WHITESPACE = new CharClass(R_SPACE, false);
    static final CharClass NOT_WHITESPACE = new CharClass(R_SPACE, true);
}
