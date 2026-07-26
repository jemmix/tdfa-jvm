package io.github.jemmix.tdfa.parser;

import io.github.jemmix.tdfa.ast.Ast;
import io.github.jemmix.tdfa.ast.CharClass;

import java.util.ArrayList;
import java.util.List;

/**
 * Hand-rolled recursive-descent parser for a PCRE-ish subset:
 * - literals, escape sequences (\n \t \r \\ \d \D \w \W \s \S)
 * - char classes [abc], [a-z], [^...]
 * - quantifiers: * + ? {n} {n,} {n,m} (greedy and lazy)
 * - alternation |
 * - capturing groups (...), non-capturing (?:...)
 * - anchors ^ $
 * - dot .
 *
 * Capturing groups become pairs of tags (1..n, 1..n) — see Compile.visitGroup.
 *
 * NOT supported (rejected): backreferences, lookarounds, backslash-octal/hex codepoints,
 * POSIX classes, Unicode properties, inline flags, atomic groups, possessive.
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
        Ast body = parseAlt();
        expect(')');
        if (!capturing) return body;
        int open = nextTag++;
        int close = nextTag++;
        groupCount++;
        return new Ast.Concat(List.of(new Ast.Tag(open), body, new Ast.Tag(close)));
    }

    /** class := '^'? class-item+ ']' */
    private Ast parseClass() {
        boolean negated = false;
        if (peek() == '^') { pos++; negated = true; }
        List<Integer> ranges = new ArrayList<>();
        while (pos < src.length() && peek() != ']') {
            // Check for shorthand escapes (\s \S \d \D \w \W)
            if (peek() == '\\' && pos + 1 < src.length()) {
                char next = src.charAt(pos + 1);
                int[] sr = shorthandClassRanges(next);
                if (sr != null) {
                    pos += 2;
                    for (int v : sr) ranges.add(v);
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
            List<Integer> exp = new ArrayList<>();
            for (int i = 0; i < arr.length; i += 2) {
                int lo = arr[i], hi = arr[i + 1];
                exp.add(lo); exp.add(hi);
                int clo = Character.toLowerCase((char) lo);
                int chi = Character.toLowerCase((char) hi);
                int ulo = Character.toUpperCase((char) lo);
                int uhi = Character.toUpperCase((char) hi);
                if (clo != lo || chi != hi) { exp.add(clo); exp.add(chi); }
                if (ulo != lo || uhi != hi) { exp.add(ulo); exp.add(uhi); }
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

    private char parseClassChar() {
        char c = cur();
        if (c == '\\') {
            pos++;
            char e = cur(); pos++;
            return switch (e) {
                case 'n' -> '\n';
                case 't' -> '\t';
                case 'r' -> '\r';
                case 'f' -> '\f';
                case '0' -> '\0';
                case 'a' -> (char) 7;
                case 'v' -> (char) 11;
                case '\\' -> '\\';
                default -> e;  // any other escaped char is literal
            };
        }
        pos++;
        return c;
    }

    private Ast parseEscape() {
        char c = cur(); pos++;
        return switch (c) {
            case 'n' -> new Ast.Symbol('\n');
            case 't' -> new Ast.Symbol('\t');
            case 'r' -> new Ast.Symbol('\r');
            case 'f' -> new Ast.Symbol('\f');
            case '0' -> new Ast.Symbol('\0');
            case '\\' -> new Ast.Symbol('\\');
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
            default -> new Ast.Symbol(c);
        };
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
    private static final int[] R_SPACE = {'\t', '\r', ' ', ' '};
    private static final int[] R_NOT_SPACE = {0, '\t'-1, '\r'+1, ' '-1, ' '+1, 0xFFFF};

    static final CharClass DOT = new CharClass(new int[]{0, '\n' - 1, '\n' + 1, 0xFFFF}, false);
    static final CharClass DOTALL = new CharClass(new int[]{0, 0xFFFF}, false);
    static final CharClass DIGIT = new CharClass(R_DIGIT, false);
    static final CharClass NOT_DIGIT = new CharClass(R_DIGIT, true);
    static final CharClass WORD = new CharClass(R_WORD, false);
    static final CharClass NOT_WORD = new CharClass(R_WORD, true);
    static final CharClass WHITESPACE = new CharClass(R_SPACE, false);
    static final CharClass NOT_WHITESPACE = new CharClass(R_SPACE, true);
}
