package io.github.jemmix.tdfa;

import io.github.jemmix.tdfa.core.compile.CompileOptions;
import io.github.jemmix.tdfa.core.compile.RegexEngineFactory;
import io.github.jemmix.tdfa.core.emit.EmittedSurface;
import io.github.jemmix.tdfa.core.unicode.UnicodeDataProvider;

import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * A compiled regular expression — the user-facing facade mirroring the
 * {@code com.google.re2j.Pattern} / {@code java.util.regex.Pattern} API
 * surface, executed by the TDFA engine.
 *
 * <p>Compile with {@link #compile(String)} or {@link #compile(String, int)};
 * obtain a {@link PatternMatcher} via {@link #matcher(CharSequence)}.
 * Default semantics are leftmost-first (Perl/PCRE/re2j-compatible);
 * {@link #LONGEST_MATCH} selects leftmost-longest. Malformed syntax fails
 * with {@link io.github.jemmix.tdfa.core.parser.PatternSyntaxException}; a pattern
 * that parses but exceeds a resource budget fails with
 * {@link io.github.jemmix.tdfa.core.budget.PatternTooLargeException} (the
 * {@code "pattern too large"} family — raise the named {@code -D} property
 * if you legitimately need bigger).
 *
 * <p><b>Engines.</b> By default each pattern is backed by a dedicated
 * generated class (ASM tier): the whole Matcher.find() &rarr; engine ladder
 * &rarr; walk-leaf chain devirtualizes and inlines end-to-end. Supplying a
 * {@link RegexEngineFactory} swaps in a custom engine (tracer, experimental
 * backend, alternative generator) — the facade then emits a shell around it,
 * preserving the monomorphic call chain. {@code -Dtdfa.engine=VM} forces the
 * shared interpreter implementation everywhere (no code generation at all).
 *
 * <p><b>Thread safety.</b> Patterns are immutable and safe for concurrent
 * use from multiple threads; the {@link PatternMatcher matchers} they
 * produce are NOT — create one per thread. The input {@link CharSequence}
 * must not be mutated during matching: matchers cache {@code length()} and
 * may re-read indices at any point during a call, so a concurrent mutation
 * produces unspecified results (the String/byte[] overloads are immune).
 *
 * <p><b>Serialization.</b> Patterns serialize as pattern + flags + pinned
 * provider identity and recompile on read (generated classes cannot cross
 * processes). A pattern compiled against a pinned {@code UnicodeDataProvider}
 * keeps it across serialization — see that interface's serialization
 * convention; a pattern compiled against the process default recompiles
 * against the READER's default tables.
 */
public interface Pattern extends Serializable {

    /**
     * Flag: case insensitive matching.
     */
    int CASE_INSENSITIVE = 1;

    /**
     * Flag: dot ({@code .}) matches all characters, including newline.
     */
    int DOTALL = 2;

    /**
     * Flag: multiline matching ({@code ^}/{@code $} at line boundaries).
     */
    int MULTILINE = 4;

    /**
     * Flag: matches longest possible string (leftmost-longest).
     */
    int LONGEST_MATCH = 16;

    /**
     * Flag: enables Unicode-aware versions of the predefined character classes
     * {@code \w}, {@code \d}, {@code \s} and the word-boundary assertion
     * {@code \b}, matching {@code java.util.regex.Pattern.UNICODE_CHARACTER_CLASS}.
     *
     * <p>When set, {@code \w} matches
     * {@code [\p{Alpha}\p{gc=M}\p{Nd}\p{gc=Pc}\p{IsJoin_Control}]},
     * {@code \d} matches {@code \p{Nd}}, {@code \s} matches the Unicode
     * {@code White_Space} property, and {@code \b} uses the Unicode-aware
     * word-character predicate — the JDK's Alphabetic-based word set, so
     * {@code Mc}, Join_Control (U+200C/U+200D) and Other_Alphabetic
     * codepoints (e.g. U+24B6) are word chars while {@code Sc}/{@code Sk}/
     * {@code No} are not. Exact membership is universe-dependent: the
     * default (JDK-derived) universe equals a same-JVM
     * {@code java.util.regex} oracle by construction; a pinned snapshot
     * universe freezes the same definition at its Unicode version. The
     * exact set is differentially pinned in {@code UnicodeBoundaryParityTest}.
     */
    int UNICODE_CHARACTER_CLASS = 32;

    /**
     * Flag: compile for find-shaped matching only — the whole-match machinery
     * is skipped entirely (see below). The explicit spellings are
     * {@link #compileFind(String)} / {@link #compileFind(String, int)};
     * the bit may also be OR-ed into any {@code compile(regex, flags, ...)}.
     *
     * <p><b>What it buys you.</b> By default one artifact serves both
     * {@code find()} and whole-input {@code matches()}: during determinization
     * the compiler records a side table of un-pruned continuations wherever
     * leftmost-first pruning would discard a path a full-length match needs
     * (the {@code (a|ab)}-on-{@code "ab"} divergence class). That bounded side
     * exploration is part of the compile budget, and when a pattern's
     * divergence explodes it, the whole compile fails with "pattern too
     * large" — even though the find artifact itself is fine. With
     * {@code FIND_ONLY} no whole machinery is attempted at all: the compile
     * produces the plain find artifact and accepts exactly the patterns a
     * find-only consumer can run.
     *
     * <p><b>The contract.</b> Whole-input methods on the result throw
     * {@link UnsupportedOperationException} — always, even for patterns whose
     * whole side would have built trivially (the flag is a consumer contract,
     * not an internal artifact switch): {@link #matches(String)},
     * {@link #matches(byte[])} and {@link PatternMatcher#matches()
     * matcher(...).matches()}. Everything find-shaped works unchanged:
     * {@code find()}, {@code lookingAt()}, {@code split()},
     * {@code replaceAll}/{@code replaceFirst}, groups.
     *
     * <p>The flag round-trips serialization and participates in
     * {@code equals}/{@code hashCode}: a find-only pattern is not equal to
     * the full compile of the same regex (different capabilities). The core
     * tier's {@link io.github.jemmix.tdfa.core.compile.CompiledRegex core.CompiledRegex}
     * is find-only in the same sense, by construction.
     */
    int FIND_ONLY = 64;

    /**
     * Flag: disable Unicode groups ({@code \p{...}} / {@code \P{...}} rejected at compile time, like re2j).
     */
    int DISABLE_UNICODE_GROUPS = 8;

    /**
     * Flag: multi-valued tags (BT22 &sect;3.1) — every capture group keeps
     * its whole offset sequence under repetition. Without the flag a group
     * inside {@code (...)*}/{@code +}/{@code {n,m}} reports only its LAST
     * iteration's span (j.u.r semantics); with it,
     * {@link PatternMatcher#groupSpans(int) matcher(...).groupSpans(g)}
     * returns every iteration's span in match order, one pair per
     * iteration, {@code (-1,-1)} where the group was bypassed. All
     * single-value results ({@code group}/{@code start}/{@code end}) are
     * IDENTICAL to the plain compile of the same pattern — the flag only
     * adds the per-iteration readout.
     *
     * <p>The compile trades the fixed-tag, register-renaming and register
     * optimizations (BT22 &sect;6.4 / &sect;3.3 map / &sect;6.3) for the
     * offset lists — multi-valued artifacts can be larger — and every step
     * of a match appends one node per repeated tag (linear, but a constant
     * factor over the plain walk). POSIX {@link #LONGEST_MATCH} composes
     * with this flag.
     */
    int MULTI_VALUED_TAGS = 128;

    /**
     * Compile {@code regex} with default flags (leftmost-first, generated engine).
     */
    static Pattern compile(String regex) {
        if (regex == null) {
            throw new NullPointerException("pattern is null");
        }
        return compile(regex, 0);
    }

    /**
     * Compile {@code regex} with the given {@code flags} (bitwise OR of the flag constants).
     */
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
     * {@link io.github.jemmix.tdfa.core.unicode.UnicodeDataProvider UnicodeDataProvider}
     * for resolving {@code \p{...}} / {@code \P{...}} property classes — e.g. a
     * pinned-Unicode-version provider for reproducible matching across JVMs.
     */
    static Pattern compile(String regex, int flags, RegexEngineFactory factory, UnicodeDataProvider unicodeProvider) {
        return PatternCompiler.compile(regex, flags, factory, unicodeProvider);
    }

    /**
     * Compile with explicit options (semantics, tables, observer).
     */
    static Pattern compile(String regex, CompileOptions options) {
        if (options == null) {
            throw new NullPointerException("options is null");
        }
        int flags = 0;
        if (options.isLongestMatch()) {
            flags |= LONGEST_MATCH;
        }
        if (options.isMultiValuedTags()) {
            flags |= MULTI_VALUED_TAGS;
        }
        if (options.isDisableUnicodeGroups()) {
            flags |= DISABLE_UNICODE_GROUPS;
        }
        return PatternCompiler.compile(regex, flags, null, options.unicodeProvider(), options.observer());
    }

    /**
     * Compile {@code regex} for find-shaped matching only: no whole-match
     * machinery is attempted (patterns whose whole divergence would reject a
     * full compile are accepted here), and every whole-input method on the
     * result throws {@link UnsupportedOperationException} — see
     * {@link #FIND_ONLY}. The explicit spelling of
     * {@code compile(regex, flags | FIND_ONLY)}.
     */
    static Pattern compileFind(String regex) {
        if (regex == null) {
            throw new NullPointerException("pattern is null");
        }
        return compileFind(regex, 0);
    }

    /**
     * Compile {@code regex} with the given {@code flags} OR-ed with
     * {@link #FIND_ONLY} — find-shaped matching only, whole-input methods
     * throw {@link UnsupportedOperationException} (see {@link #FIND_ONLY}).
     */
    static Pattern compileFind(String regex, int flags) {
        if (regex == null) {
            throw new NullPointerException("pattern is null");
        }
        return PatternCompiler.compile(regex, flags | FIND_ONLY, null, null);
    }

    /**
     * Convenience: compile and match the entire input.
     */
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

    /**
     * Quote regexp metacharacters in {@code s}.
     */
    static String quote(String s) {
        if (s.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(s.length() << 1);
        for (int i = 0; i < s.length();) {
            int c = s.codePointAt(i);
            i += Character.charCount(c);
            if ("\\.+*?()|[]{}^$".indexOf(c) >= 0) {
                out.append('\\');
            }
            out.appendCodePoint(c);
        }
        return out.toString();
    }

    /**
     * Match the entire input against this pattern. Throws
     * {@link UnsupportedOperationException} on patterns compiled with
     * {@link #FIND_ONLY} (their whole-match machinery was never built).
     */
    boolean matches(String input);

    /**
     * Match the entire input against this pattern (UTF-8 bytes decoded to a String).
     * Throws {@link UnsupportedOperationException} on patterns compiled with
     * {@link #FIND_ONLY} (their whole-match machinery was never built).
     */
    boolean matches(byte[] input);

    /**
     * Create a {@link PatternMatcher} for this pattern against {@code input}.
     */
    PatternMatcher matcher(CharSequence input);

    /**
     * Create a {@link PatternMatcher} for this pattern against UTF-8-decoded {@code input}.
     */
    PatternMatcher matcher(byte[] input);

    /**
     * Split {@code input} around matches of this pattern. Trailing empty strings are omitted.
     */
    String[] split(String input);

    /**
     * Split {@code input} with a limit on the number of result strings.
     */
    String[] split(String input, int limit);

    /**
     * Releases internal caches (no-op for this engine).
     */
    void reset();

    /**
     * Cost estimate for the compiled pattern: the number of states in the tagged DFA.
     * <p><b>Not comparable to {@code com.google.re2j.Pattern.programSize()}</b> — that returns
     * an NFA-instruction count (a different compilation model). {@code java.util.regex.Pattern}
     * has no equivalent method. Larger numbers indicate more expensive patterns.
     */
    int programSize();

    /**
     * Returns the pattern string.
     */
    String pattern();

    /**
     * Returns the flags.
     */
    int flags();

    /**
     * Number of capturing groups (excluding group 0).
     */
    int groupCount();

    /**
     * Unmodifiable name&rarr;index map for named capturing groups.
     */
    Map<String, Integer> namedGroups();

    /**
     * UTF-8 decode shared by the byte[] overloads (re2j's {@code MatcherInput.utf8}).
     */
    @EmittedSurface
    final class Utf8 {
        private Utf8() {
        }

        public static String decode(byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }
}
