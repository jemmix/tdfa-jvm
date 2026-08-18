package io.github.jemmix.tdfa;

import io.github.jemmix.tdfa.core.CompileOptions;
import io.github.jemmix.tdfa.core.RegexEngineFactory;

import java.util.Map;

/**
 * A compiled regular expression — the user-facing facade mirroring the
 * {@code com.google.re2j.Pattern} / {@code java.util.regex.Pattern} API
 * surface, executed by the TDFA engine.
 *
 * <p>Compile with {@link #compile(String)} or {@link #compile(String, int)};
 * obtain a {@link PatternMatcher} via {@link #matcher(CharSequence)}.
 * Default semantics are leftmost-first (Perl/PCRE/re2j-compatible);
 * {@link #LONGEST_MATCH} selects leftmost-longest.
 *
 * <p><b>Engines.</b> By default each pattern is backed by a dedicated
 * generated class (ASM tier): the whole Matcher.find() &rarr; engine ladder
 * &rarr; walk-leaf chain devirtualizes and inlines end-to-end. Supplying a
 * {@link RegexEngineFactory} swaps in a custom engine (tracer, experimental
 * backend, alternative generator) — the facade then emits a shell around it,
 * preserving the monomorphic call chain. {@code -Dtdfa.engine=VM} forces the
 * shared interpreter implementation everywhere (no code generation at all).
 */
public interface Pattern extends java.io.Serializable {

    /** Flag: case insensitive matching. */
    int CASE_INSENSITIVE = 1;

    /** Flag: dot ({@code .}) matches all characters, including newline. */
    int DOTALL = 2;

    /** Flag: multiline matching ({@code ^}/{@code $} at line boundaries). */
    int MULTILINE = 4;

    /** Flag: matches longest possible string (leftmost-longest). */
    int LONGEST_MATCH = 16;

    /**
     * Flag: enables Unicode-aware versions of the predefined character classes
     * {@code \w}, {@code \d}, {@code \s} and the word-boundary assertion
     * {@code \b} — matching {@code java.util.regex.Pattern.UNICODE_CHARACTER_CLASS}.
     *
     * <p>When set, {@code \w} matches {@code [\p{L}\p{N}\p{Mn}\p{Me}\p{Pc}\p{Sc}\p{Sk}]},
     * {@code \d} matches {@code \p{Nd}}, {@code \s} matches the Unicode
     * {@code White_Space} property, and {@code \b} uses the Unicode-aware
     * word-character predicate.
     */
    int UNICODE_CHARACTER_CLASS = 32;

    /** Flag: disable Unicode groups ({@code \p{...}} / {@code \P{...}} rejected at compile time, like re2j). */
    int DISABLE_UNICODE_GROUPS = 8;

    /** Compile {@code regex} with default flags (leftmost-first, generated engine). */
    static Pattern compile(String regex) {
        if (regex == null) throw new NullPointerException("pattern is null");
        return compile(regex, 0);
    }

    /** Compile {@code regex} with the given {@code flags} (bitwise OR of the flag constants). */
    static Pattern compile(String regex, int flags) {
        return compile(regex, flags, null, null);
    }

    /**
     * Compile {@code regex} with the given {@code flags} and a custom engine
     * factory (bring-your-own-engine). Use e.g. {@code TdfaRunner::new} for the
     * interpreter, or a custom implementation; {@code null} selects the default
     * per-pattern generation.
     */
    static Pattern compile(String regex, int flags, RegexEngineFactory factory) {
        return compile(regex, flags, factory, null);
    }

    /**
     * Compile with explicit flags, engine factory, and a
     * {@link io.github.jemmix.tdfa.unicode.UnicodeDataProvider UnicodeDataProvider}
     * for resolving {@code \p{...}} / {@code \P{...}} property classes — e.g. a
     * pinned-Unicode-version provider for reproducible matching across JVMs.
     */
    static Pattern compile(String regex, int flags, RegexEngineFactory factory,
                           io.github.jemmix.tdfa.unicode.UnicodeDataProvider unicodeProvider) {
        return PatternCompiler.compile(regex, flags, factory, unicodeProvider);
    }

    /** Compile with explicit options (semantics, tables, engine factory). */
    static Pattern compile(String regex, CompileOptions options) {
        if (options == null) throw new NullPointerException("options is null");
        int flags = 0;
        if (options.isLongestMatch()) flags |= LONGEST_MATCH;
        if (options.isDisableUnicodeGroups()) flags |= DISABLE_UNICODE_GROUPS;
        return PatternCompiler.compile(regex, flags, options.engineFactory(), options.unicodeProvider());
    }

    /** Convenience: compile and match the entire input. */
    static boolean matches(String regex, CharSequence input) {
        return compile(regex).matcher(input).matches();
    }

    /**
     * Convenience: compile and match the entire input. The bytes are decoded as UTF-8;
     * match indices (where applicable) are therefore UTF-16 char offsets of the decoded
     * text, not raw byte offsets.
     */
    static boolean matches(String regex, byte[] input) {
        return matches(regex, Utf8.decode(input));
    }

    /** Match the entire input against this pattern. */
    boolean matches(String input);

    /** Match the entire input against this pattern (UTF-8 bytes decoded to a String). */
    boolean matches(byte[] input);

    /** Create a {@link PatternMatcher} for this pattern against {@code input}. */
    PatternMatcher matcher(CharSequence input);

    /** Create a {@link PatternMatcher} for this pattern against UTF-8-decoded {@code input}. */
    PatternMatcher matcher(byte[] input);

    /** Split {@code input} around matches of this pattern. Trailing empty strings are omitted. */
    String[] split(String input);

    /** Split {@code input} with a limit on the number of result strings. */
    String[] split(String input, int limit);

    /** Quote regexp metacharacters in {@code s}. */
    static String quote(String s) {
        if (s.isEmpty()) return "";
        StringBuilder out = new StringBuilder(s.length() << 1);
        for (int i = 0; i < s.length(); ) {
            int c = s.codePointAt(i);
            i += Character.charCount(c);
            if ("\\.+*?()|[]{}^$".indexOf(c) >= 0) out.append('\\');
            out.appendCodePoint(c);
        }
        return out.toString();
    }

    /** Releases internal caches (no-op for this engine). */
    void reset();

    /**
     * Cost estimate for the compiled pattern: the number of states in the tagged DFA.
     * <p><b>Not comparable to {@code com.google.re2j.Pattern.programSize()}</b> — that returns
     * an NFA-instruction count (a different compilation model). {@code java.util.regex.Pattern}
     * has no equivalent method. Larger numbers indicate more expensive patterns.
     */
    int programSize();

    /** Returns the pattern string. */
    String pattern();

    /** Returns the flags. */
    int flags();

    /** Number of capturing groups (excluding group 0). */
    int groupCount();

    /** Unmodifiable name&rarr;index map for named capturing groups. */
    Map<String, Integer> namedGroups();

    /** UTF-8 decode shared by the byte[] overloads (re2j's {@code MatcherInput.utf8}). */
    final class Utf8 {
        private Utf8() { }
        public static String decode(byte[] bytes) {
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
