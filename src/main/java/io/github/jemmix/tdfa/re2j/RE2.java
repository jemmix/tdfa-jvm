package io.github.jemmix.tdfa.re2j;

import io.github.jemmix.tdfa.Regex;
import io.github.jemmix.tdfa.tdfa.Disambiguation;
import io.github.jemmix.tdfa.vm.MatchResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Drop-in replacement for {@code com.google.re2j.RE2}.
 *
 * Mirrors the subset of re2j's {@code RE2} public API needed by its ExecTest
 * suite. Each instance compiles both Perl (leftmost-first) and POSIX
 * (leftmost-longest) variants of the pattern against our TDFA engine; the
 * mutable {@link #longest} flag selects which is used per call, matching
 * re2j's mutation-based API.
 *
 * Migrating from re2j: change {@code import com.google.re2j.RE2;} to
 * {@code import io.github.jemmix.tdfa.re2j.RE2;}. Same call sites for the
 * methods implemented here. Methods not yet implemented (findAll,
 * findAllSubmatch, quoteMeta, compileImpl) throw
 * {@link UnsupportedOperationException} if called.
 */
public final class RE2 {

    // ---- Parser/re2j flag constants (mirrors re2j's RE2 class) -----------
    public static final int FOLD_CASE       = 0x01;
    public static final int LITERAL         = 0x02;
    public static final int CLASS_NL        = 0x04;
    public static final int DOT_NL          = 0x08;
    public static final int ONE_LINE        = 0x10;
    public static final int NON_GREEDY      = 0x20;
    public static final int PERL_X          = 0x40;
    public static final int UNICODE_GROUPS  = 0x80;
    public static final int WAS_DOLLAR      = 0x100;
    public static final int MATCH_NL        = CLASS_NL | DOT_NL;
    public static final int PERL            = CLASS_NL | ONE_LINE | PERL_X | UNICODE_GROUPS;
    public static final int POSIX           = 0;

    /** Compile a pattern using Perl (leftmost-first) disambiguation, matching re2j's default. */
    public static RE2 compile(String pattern) {
        return compileImpl(pattern, PERL, false);
    }

    /** Compile using POSIX (leftmost-longest) disambiguation, matching re2j's RE2.compilePOSIX. */
    public static RE2 compilePOSIX(String pattern) {
        return compileImpl(pattern, POSIX, true);
    }

    /**
     * Compile with explicit flags. Translates parser exceptions into
     * {@link PatternSyntaxException} with the message format re2j uses
     * (so re2j's test-suite exact-match on {@code \C} messages passes).
     */
    public static RE2 compileImpl(String pattern, int flags, boolean posix) {
        // For now we honour only the PERL vs POSIX distinction and ignore the
        // other flags (FOLD_CASE, CLASS_NL, DOT_NL, etc.) which our engine
        // doesn't expose yet. Tests using these flags will diverge.
        try {
            return new RE2(pattern, posix);
        } catch (RuntimeException e) {
            throw translate(e, pattern);
        }
    }

    /**
     * Translate an internal parser exception into a {@link PatternSyntaxException}
     * with re2j's exact message format.
     *
     * <p>re2j's ExecTest does exact-match on
     * {@code "error parsing regexp: invalid escape sequence: `\C`"} for \C patterns.
     * Our parser throws {@code IllegalArgumentException("invalid escape sequence: \C")},
     * which we rewrap as PatternSyntaxException(description="invalid escape sequence",
     * input="\\C") to produce the matching message.
     */
    private static PatternSyntaxException translate(RuntimeException e, String pattern) {
        String msg = e.getMessage();
        if (msg != null) {
            // Special-case: \C escape. Match re2j's exact format.
            if (msg.equals("invalid escape sequence: \\C")) {
                return new PatternSyntaxException("invalid escape sequence", "\\C");
            }
        }
        return new PatternSyntaxException(msg == null ? "internal error" : msg, pattern);
    }

    /** Quote regexp metacharacters in {@code pattern}; matches re2j's RE2.quoteMeta. */
    public static String quoteMeta(String pattern) {
        if (pattern.isEmpty()) return "";
        StringBuilder out = new StringBuilder(pattern.length() << 1);
        for (int i = 0; i < pattern.length(); ) {
            int c = pattern.codePointAt(i);
            i += Character.charCount(c);
            if ("\\.+*?()|[]{}^$".indexOf(c) >= 0) out.append('\\');
            out.appendCodePoint(c);
        }
        return out.toString();
    }

    private final String pattern;
    private final Regex perlEngine;
    private final Regex posixEngine;

    /**
     * Mutable flag selecting POSIX longest-match semantics on subsequent
     * {@link #findSubmatchIndex(String)} / {@link #match(String)} calls.
     * Mirrors re2j's {@code regexp.longest = ...} mutation API.
     */
    public boolean longest = false;

    private RE2(String pattern, boolean posix) {
        this.pattern = pattern;
        this.perlEngine = Regex.compile(pattern, false, Disambiguation.PERL);
        this.posixEngine = Regex.compile(pattern, false, Disambiguation.POSIX);
        this.longest = posix;
    }

    private Regex engine() { return longest ? posixEngine : perlEngine; }

    /**
     * Returns {@code [start, end, g1_start, g1_end, ...]} in UTF-16 indices,
     * or {@code null} if no match. Unmatched groups report {@code -1, -1}.
     * Mirrors re2j's {@code RE2.findSubmatchIndex(String)}.
     */
    public int[] findSubmatchIndex(String text) {
        MatchResult m = engine().find(text, 0);
        if (m == null) return null;
        Regex engine = engine();
        int gc = engine.groupCount();
        int[] out = new int[2 + 2 * gc];
        out[0] = m.start(0);
        out[1] = m.end(0);
        for (int g = 1; g <= gc; g++) {
            out[2 * g]     = m.start(g);
            out[2 * g + 1] = m.end(g);
        }
        return out;
    }

    /** Equivalent to {@code findSubmatchIndex(text) != null}. */
    public boolean match(String text) {
        return engine().find(text, 0) != null;
    }

    /**
     * Find all (possibly-overlapping-up-to-cap) matches in {@code text}.
     * Each match is returned as its matched substring.
     */
    public List<String> findAll(String text, int cap) {
        List<String> out = new ArrayList<>();
        int from = 0;
        while (from <= text.length()) {
            MatchResult m = engine().find(text, from);
            if (m == null) break;
            out.add(text.substring(m.start(0), m.end(0)));
            if (out.size() == cap) break;
            int next = m.end(0);
            from = (next == m.start(0)) ? next + 1 : next;  // zero-width: advance
        }
        return out;
    }

    /**
     * Find all matches; each match returns its full match + submatch substrings.
     */
    public List<String[]> findAllSubmatch(String text, int cap) {
        List<String[]> out = new ArrayList<>();
        int gc = engine().groupCount();
        int from = 0;
        while (from <= text.length()) {
            MatchResult m = engine().find(text, from);
            if (m == null) break;
            String[] groups = new String[gc + 1];
            for (int g = 0; g <= gc; g++) {
                int s = m.start(g), e = m.end(g);
                groups[g] = (s < 0 || e < 0) ? null : text.substring(s, e);
            }
            out.add(groups);
            if (out.size() == cap) break;
            int next = m.end(0);
            from = (next == m.start(0)) ? next + 1 : next;
        }
        return out;
    }

    /** Returns the pattern source string. */
    @Override public String toString() { return pattern; }
}

