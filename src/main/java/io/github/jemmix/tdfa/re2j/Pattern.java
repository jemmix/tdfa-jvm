package io.github.jemmix.tdfa.re2j;

import io.github.jemmix.tdfa.EngineFactory;

import java.util.List;
import java.util.Map;

/**
 * Drop-in replacement for {@code com.google.re2j.Pattern}, mimicking the
 * {@code java.util.regex.Pattern} API surface.
 *
 * <p>Interface since the kernel refactor: {@link #compile} returns the shared
 * implementation for interpreter (VM) engines, and — when the
 * {@link EngineFactory} {@linkplain EngineFactory#generatesPerPattern()
 * generates per-pattern classes} — a generated implementation whose
 * Pattern/Matcher call chain devirtualizes and inlines end-to-end.
 *
 * <p>Compile with {@link #compile(String)} or {@link #compile(String, int)}.
 * Obtain a {@link Matcher} via {@link #matcher(CharSequence)}.
 *
 * <p>Supported flags: {@link #CASE_INSENSITIVE}, {@link #DOTALL}, {@link #MULTILINE}, {@link #LONGEST_MATCH},
 * {@link #DISABLE_UNICODE_GROUPS}, {@link #UNICODE_CHARACTER_CLASS}.
 */
public interface Pattern extends java.io.Serializable {

    /** Flag: case insensitive matching. */
    int CASE_INSENSITIVE = 1;

    /** Flag: dot ({@code .}) matches all characters, including newline. */
    int DOTALL = 2;

    /** Flag: multiline matching ({@code ^}/{@code $} at line boundaries). */
    int MULTILINE = 4;

    /** Flag: matches longest possible string (POSIX leftmost-longest). */
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

    /** Compile {@code regex} with default flags and the default engine (Perl leftmost-first semantics). */
    static Pattern compile(String regex) {
        if (regex == null) throw new NullPointerException("pattern is null");
        return compile(regex, 0);
    }

    /** Compile {@code regex} with the given {@code flags} (bitwise OR of the flag constants). */
    static Pattern compile(String regex, int flags) {
        return compile(regex, flags, EngineFactory.DEFAULT);
    }

    /**
     * Compile {@code regex} with the given {@code flags} and an explicit {@link EngineFactory}.
     * Use {@code EngineFactory.ASM} or {@code EngineFactory.VM} for the built-in backends,
     * or pass a lambda for a custom backend.
     */
    static Pattern compile(String regex, int flags, EngineFactory factory) {
        return compile(regex, flags, factory, io.github.jemmix.tdfa.unicode.UnicodeProviders.get());
    }

    /**
     * Compile {@code regex} with the given {@code flags}, {@link EngineFactory}, and an explicit
     * {@link io.github.jemmix.tdfa.unicode.UnicodeDataProvider UnicodeDataProvider} for resolving
     * {@code \p{...}} / {@code \P{...}} property classes. Use this to select a Unicode table version
     * (e.g. a re2j-exact provider) independently of the JVM-default {@link java.lang.Character} tables.
     */
    static Pattern compile(String regex, int flags, EngineFactory factory,
                           io.github.jemmix.tdfa.unicode.UnicodeDataProvider unicodeProvider) {
        if (regex == null) throw new NullPointerException("pattern is null");
        if (factory == null) throw new NullPointerException("factory is null");
        if (unicodeProvider == null) throw new NullPointerException("unicodeProvider is null");
        if ((flags & ~(CASE_INSENSITIVE | DOTALL | MULTILINE | DISABLE_UNICODE_GROUPS | LONGEST_MATCH | UNICODE_CHARACTER_CLASS)) != 0) {
            throw new IllegalArgumentException(
                    "Flags should only be a combination of MULTILINE, DOTALL, CASE_INSENSITIVE, DISABLE_UNICODE_GROUPS, LONGEST_MATCH, UNICODE_CHARACTER_CLASS");
        }
        // Per-pattern generation: under a generating factory, Pattern/Matcher
        // classes are emitted into the engine's classloader and call it
        // directly (devirtualized, inline end-to-end). Falls back to the
        // shared implementation internally on emission problems.
        if (factory instanceof io.github.jemmix.tdfa.asm.AsmEngineFactory a) {
            return GenPatternSupport.compile(regex, flags, a, unicodeProvider);
        }
        return VmPattern.compile(regex, flags, factory, unicodeProvider);
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

    /** Create a {@link Matcher} for this pattern against {@code input}. */
    Matcher matcher(CharSequence input);

    /** Create a {@link Matcher} for this pattern against UTF-8-decoded {@code input}. */
    Matcher matcher(byte[] input);

    /** Split {@code input} around matches of this pattern. Trailing empty strings are omitted. */
    String[] split(String input);

    /** Split {@code input} with a limit on the number of result strings. */
    String[] split(String input, int limit);

    /** Quote regexp metacharacters in {@code s}. */
    static String quote(String s) {
        return RE2.quoteMeta(s);
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

    /** Unmodifiable name→index map for named capturing groups. */
    Map<String, Integer> namedGroups();

    /** Package-private UTF-8 decode shared by the byte[] overloads (re2j's {@code MatcherInput.utf8}). */
    final class Utf8 {
        private Utf8() { }
        public static String decode(byte[] bytes) {
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
