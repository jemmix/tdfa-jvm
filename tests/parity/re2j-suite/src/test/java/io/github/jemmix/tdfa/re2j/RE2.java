package io.github.jemmix.tdfa.re2j;

import io.github.jemmix.tdfa.Pattern;

import java.util.ArrayList;
import java.util.List;

/**
 * Test-fixture re-implementation of re2j's package-private {@code RE2} harness
 * API, wrapping the public {@link Pattern}/{@link io.github.jemmix.tdfa.core.Matcher}
 * surface. Exists solely so re2j's vendored ExecTest (and its corpus drivers)
 * compile and run unchanged against this engine; it is not shipped API.
 *
 * <p>Upstream re2j keeps this class package-private precisely as test-harness
 * surface ("Legacy Go-style interface; preserved (package-private) for better
 * test coverage"); we mirror that placement in the test suite rather than main.
 */
final class RE2 {

    // ---- Parser/re2j flag constants (mirrors re2j's RE2 class) -----------
    static final int FOLD_CASE       = 0x01;
    static final int LITERAL         = 0x02;
    static final int CLASS_NL        = 0x04;
    static final int DOT_NL          = 0x08;
    static final int ONE_LINE        = 0x10;
    static final int NON_GREEDY      = 0x20;
    static final int PERL_X          = 0x40;
    static final int UNICODE_GROUPS  = 0x80;
    static final int WAS_DOLLAR      = 0x100;
    static final int MATCH_NL        = CLASS_NL | DOT_NL;
    static final int PERL            = CLASS_NL | ONE_LINE | PERL_X | UNICODE_GROUPS;
    static final int POSIX           = 0;

    /** Compile a pattern using Perl (leftmost-first) semantics, matching re2j's default. */
    static RE2 compile(String pattern) {
        return compileImpl(pattern, PERL, false);
    }

    /** Compile using POSIX (leftmost-longest) semantics, matching re2j's RE2.compilePOSIX. */
    static RE2 compilePOSIX(String pattern) {
        return compileImpl(pattern, POSIX, true);
    }

    /**
     * Compile with explicit flags: translate the re2j parser flags this engine
     * understands into pattern rewrites (LITERAL &rarr; quoteMeta, FOLD_CASE
     * &rarr; {@code (?i)} prefix, DOT_NL &rarr; {@code (?s)} prefix). Flags
     * pending parity implementation (CLASS_NL, ONE_LINE, NON_GREEDY, PERL_X,
     * UNICODE_GROUPS, WAS_DOLLAR) are no-ops — they affect features that are
     * either always-on here or not yet wired through.
     */
    static RE2 compileImpl(String pattern, int flags, boolean posix) {
        if ((flags & LITERAL) != 0) {
            pattern = quoteMeta(pattern);
        }
        if ((flags & FOLD_CASE) != 0) {
            pattern = "(?i)" + pattern;
        }
        if ((flags & DOT_NL) != 0) {
            pattern = "(?s)" + pattern;
        }
        return new RE2(pattern, posix);
    }

    /** Quote regexp metacharacters in {@code pattern}; matches re2j's RE2.quoteMeta. */
    static String quoteMeta(String pattern) {
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
    // Both semantics variants compiled lazily: only the initially-selected
    // one is built by the constructor; the other waits for a `longest` flip.
    // Racy single-check (compile is deterministic — either racer's result is
    // correct; the volatile write publishes the Pattern safely).
    private transient volatile Pattern perlPat;
    private transient volatile Pattern posixPat;

    /**
     * Mutable flag selecting leftmost-longest semantics on subsequent
     * {@link #findSubmatchIndex(String)} / {@link #match(String)} calls.
     * Mirrors re2j's {@code regexp.longest = ...} mutation API.
     */
    boolean longest = false;

    private RE2(String pattern, boolean posix) {
        this.pattern = pattern;
        this.longest = posix;
        if (posix) this.posixPat = Pattern.compile(pattern, Pattern.LONGEST_MATCH);
        else this.perlPat = Pattern.compile(pattern);
    }

    private Pattern pat() {
        if (longest) {
            Pattern p = posixPat;
            if (p == null) { p = Pattern.compile(pattern, Pattern.LONGEST_MATCH); posixPat = p; }
            return p;
        }
        Pattern p = perlPat;
        if (p == null) { p = Pattern.compile(pattern); perlPat = p; }
        return p;
    }

    /**
     * Returns {@code [start, end, g1_start, g1_end, ...]} in UTF-16 indices,
     * or {@code null} if no match. Unmatched groups report {@code -1, -1}.
     * Mirrors re2j's {@code RE2.findSubmatchIndex(String)}.
     */
    int[] findSubmatchIndex(String text) {
        io.github.jemmix.tdfa.core.Matcher m = pat().matcher(text);
        if (!m.find()) return null;
        int gc = m.groupCount();
        int[] out = new int[2 + 2 * gc];
        out[0] = m.start();
        out[1] = m.end();
        for (int g = 1; g <= gc; g++) {
            out[2 * g]     = m.start(g);
            out[2 * g + 1] = m.end(g);
        }
        return out;
    }

    /** Equivalent to {@code findSubmatchIndex(text) != null}. */
    boolean match(String text) {
        return pat().matcher(text).find();
    }

    /** Find up to {@code cap} successive matches, each as its matched substring. */
    List<String> findAll(String text, int cap) {
        List<String> out = new ArrayList<>();
        io.github.jemmix.tdfa.core.Matcher m = pat().matcher(text);
        while (m.find()) {
            out.add(m.group());
            if (out.size() == cap) break;
        }
        return out;
    }

    /** Find up to {@code cap} successive matches; each as full match + submatch substrings. */
    List<String[]> findAllSubmatch(String text, int cap) {
        List<String[]> out = new ArrayList<>();
        io.github.jemmix.tdfa.core.Matcher m = pat().matcher(text);
        while (m.find()) {
            int gc = m.groupCount();
            String[] groups = new String[gc + 1];
            for (int g = 0; g <= gc; g++) {
                groups[g] = m.group(g);
            }
            out.add(groups);
            if (out.size() == cap) break;
        }
        return out;
    }

    /** Returns the pattern source string. */
    @Override public String toString() { return pattern; }
}
