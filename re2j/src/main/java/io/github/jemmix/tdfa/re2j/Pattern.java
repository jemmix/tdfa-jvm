package io.github.jemmix.tdfa.re2j;

import io.github.jemmix.tdfa.core.compile.CompileOptions;
import io.github.jemmix.tdfa.core.compile.PatternMatcher;
import io.github.jemmix.tdfa.core.compile.PatternSurface;
import io.github.jemmix.tdfa.core.compile.RegexEngineFactory;
import io.github.jemmix.tdfa.core.compile.TDFAPattern;
import io.github.jemmix.tdfa.core.unicode.UnicodeDataProvider;

import java.io.Serializable;
import java.util.Map;

/**
 * A compiled regular expression — the re2j-compat surface, mirroring the
 * {@code com.google.re2j.Pattern} reading of the shared API shape,
 * executed by the TDFA engine. The re2j interpretation is <b>baked
 * in</b>: every axis on which the RE2 lineage and {@code java.util.regex}
 * diverge (line terminators, fold universe, UTF-16 vs codepoint
 * boundaries, anchor end-of-line rules, zero-width iteration spans,
 * {@code (?U)}) is simply this surface's default — there is no migration
 * OR, no per-axis opt-out ceremony; the lane is un-gettable-wrong. The
 * jur-parity reading of the same shape lives in the root facade's
 * {@code io.github.jemmix.tdfa.Pattern}.
 *
 * <p>Compile with {@link #compile(String)} or {@link #compile(String, int)};
 * obtain a {@link PatternMatcher} via {@link #matcher(CharSequence)}.
 * Default semantics are leftmost-first (Perl/PCRE/re2j-compatible);
 * {@link #LONGEST_MATCH} selects leftmost-longest. Malformed syntax fails
 * with {@link io.github.jemmix.tdfa.core.parser.PatternSyntaxException}; a
 * pattern that parses but exceeds a resource budget fails with
 * {@link io.github.jemmix.tdfa.core.budget.PatternTooLargeException} (the
 * {@code "pattern too large"} family — raise the named {@code -D}
 * property if you legitimately need bigger).
 *
 * <p><b>The per-axis escape hatch.</b> {@link #compile(String, CompileOptions)}
 * with an options-carried
 * {@link io.github.jemmix.tdfa.core.tnfa.Semantics Semantics} selects any
 * mixed-axis interpretation policy (a debugging tool, not a user
 * contract); absent it, this surface's default lane applies throughout.
 *
 * <p><b>Engines.</b> By default each pattern is backed by a dedicated
 * generated class (ASM tier): the whole Matcher.find() &rarr; engine ladder
 * &rarr; walk-leaf chain devirtualizes and inlines end-to-end. Supplying a
 * {@link RegexEngineFactory} swaps in a custom engine (tracer, experimental
 * backend, alternative generator) — the surface then emits a shell around
 * it, preserving the monomorphic call chain. {@code -Dtdfa.engine=VM}
 * forces the shared interpreter implementation everywhere (no code
 * generation at all).
 *
 * <p><b>Thread safety.</b> Patterns are immutable and safe for concurrent
 * use from multiple threads; the {@link PatternMatcher matchers} they
 * produce are NOT — create one per thread. The input {@link CharSequence}
 * must not be mutated during matching: matchers cache {@code length()} and
 * may re-read indices at any point during a call, so a concurrent mutation
 * produces unspecified results (the String/byte[] overloads are immune).
 *
 * <p><b>Serialization.</b> Patterns serialize as surface + pattern + flags
 * + pinned provider identity and recompile through this surface on read
 * (generated classes cannot cross processes). A pattern compiled against
 * a pinned {@code UnicodeDataProvider} keeps it across serialization —
 * see that interface's serialization convention; a pattern compiled
 * against the process default recompiles against the READER's default
 * tables.
 */
public interface Pattern extends Serializable {

    /**
     * Flag: case insensitive matching — folds the full Unicode simple-fold
     * universe (this surface's fold universe; there is no ASCII-only bare
     * variant and no {@code UNICODE_CASE} bit to OR in).
     */
    int CASE_INSENSITIVE = PatternSurface.CASE_INSENSITIVE;

    /**
     * Flag: dot ({@code .}) matches all characters, including newline.
     */
    int DOTALL = PatternSurface.DOTALL;

    /**
     * Flag: multiline matching ({@code ^}/{@code $} at line boundaries).
     */
    int MULTILINE = PatternSurface.MULTILINE;

    /**
     * Flag: matches longest possible string (leftmost-longest, POSIX).
     */
    int LONGEST_MATCH = PatternSurface.LONGEST_MATCH;

    /**
     * Flag: disable Unicode groups ({@code \p{...}} / {@code \P{...}}
     * rejected at compile time) — the re2j dial for property classes; this
     * surface has no {@code UNICODE_CHARACTER_CLASS} bit (the lane's class
     * sets are already the re2j reading).
     */
    int DISABLE_UNICODE_GROUPS = PatternSurface.DISABLE_UNICODE_GROUPS;

    /**
     * Flag: compile for find-shaped matching only — the whole-match
     * machinery is skipped entirely. The explicit spellings are
     * {@link #compileFind(String)} / {@link #compileFind(String, int)};
     * the bit may also be OR-ed into any {@code compile(regex, flags, ...)}.
     * Whole-input methods on the result ({@link #matches(String)},
     * {@link #matches(byte[])}, {@link PatternMatcher#matches()
     * matcher(...).matches()}) throw
     * {@link UnsupportedOperationException} — always, even for patterns
     * whose whole side would have built trivially (the flag is a consumer
     * contract, not an internal artifact switch). Everything find-shaped
     * works unchanged: {@code find()}, {@code lookingAt()},
     * {@code split()}, {@code replaceAll}/{@code replaceFirst}, groups.
     * The flag round-trips serialization and participates in
     * {@code equals}/{@code hashCode}.
     *
     * <p>A tdfa extension (re2j has no equivalent).
     */
    int FIND_ONLY = PatternSurface.FIND_ONLY;

    /**
     * Flag: multi-valued tags (BT22 &sect;3.1) — every capture group keeps
     * its whole offset sequence under repetition. Without the flag a group
     * inside {@code (...)*}/{@code +}/{@code {n,m}} reports only its LAST
     * iteration's span; with it,
     * {@link PatternMatcher#groupSpans(int) matcher(...).groupSpans(g)}
     * returns every iteration's span in match order, one pair per
     * iteration, {@code (-1,-1)} where the group was bypassed. All
     * single-value results ({@code group}/{@code start}/{@code end}) are
     * IDENTICAL to the plain compile of the same pattern — the flag only
     * adds the per-iteration readout.
     *
     * <p>A tdfa extension (re2j has no equivalent).
     */
    int MULTI_VALUED_TAGS = PatternSurface.MULTI_VALUED_TAGS;

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
     * {@link UnicodeDataProvider UnicodeDataProvider}
     * for resolving {@code \p{...}} / {@code \P{...}} property classes — e.g. a
     * pinned-Unicode-version provider for reproducible matching across JVMs.
     */
    static Pattern compile(String regex, int flags, RegexEngineFactory factory, UnicodeDataProvider unicodeProvider) {
        return (Pattern) Surfaces.RE2J.compile(regex, flags, factory, unicodeProvider, null, null);
    }

    /**
     * Compile with explicit options (semantics, tables, observer). An
     * options-carried {@link io.github.jemmix.tdfa.core.tnfa.Semantics
     * Semantics} selects the compile's interpretation policy — the
     * per-axis escape hatch; absent, this surface's re2j lane applies.
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
        return (Pattern) Surfaces.RE2J.compile(regex, flags, null, options.unicodeProvider(), options.observer(),
            options.semantics());
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
        return (Pattern) Surfaces.RE2J.compile(regex, flags | FIND_ONLY, null, null, null, null);
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
        return matches(regex, TDFAPattern.decodeUtf8(input));
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
     * an NFA-instruction count (a different compilation model). Larger numbers
     * indicate more expensive patterns.
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
}
