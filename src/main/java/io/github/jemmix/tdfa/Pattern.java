package io.github.jemmix.tdfa;

import io.github.jemmix.tdfa.core.compile.CompileOptions;
import io.github.jemmix.tdfa.core.compile.PatternCompiler;
import io.github.jemmix.tdfa.core.compile.PatternMatcher;
import io.github.jemmix.tdfa.core.compile.RegexEngineFactory;
import io.github.jemmix.tdfa.core.compile.TDFAPattern;
import io.github.jemmix.tdfa.core.dfa.StreamMatcher;
import io.github.jemmix.tdfa.core.engine.ChunkSource;
import io.github.jemmix.tdfa.core.unicode.UnicodeDataProvider;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * A compiled regular expression — the user-facing facade mirroring the
 * {@code com.google.re2j.Pattern} / {@code java.util.regex.Pattern} API
 * surface, executed by the TDFA engine.
 *
 * <p>Compile with {@link #compile(String)} or {@link #compile(String, int)};
 * obtain a {@link PatternMatcher} via {@link #matcher(CharSequence)}.
 * The matcher surface carries the {@code java.util.regex.Matcher}
 * region/bounds family — {@code region}/{@code regionStart}/
 * {@code regionEnd}, {@code useTransparentBounds}/
 * {@code useAnchoringBounds}, {@code hitEnd}/{@code requireEnd} (the
 * JDK's flags re-derived by a cold probe over the artifact, live-oracle
 * battery-pinned), {@code toMatchResult} (a
 * {@code java.util.regex.MatchResult}-implementing snapshot),
 * {@code usePattern} and the function-based
 * {@code replaceAll}/{@code replaceFirst} — beside the re2j-shaped
 * roster both facades share; see the matcher classes' javadoc for the
 * one tier constraint ({@code usePattern}) and the docs page
 * {@code docs/jur-api-parity.md} for the full accounting. Default
 * semantics are leftmost-first (Perl/PCRE/re2j-compatible);
 * {@link #LONGEST_MATCH} selects leftmost-longest. The default
 * interpretation matches {@code java.util.regex} on every axis it
 * diverges from the RE2 lineage on (line terminators, bare-CI fold
 * universe, UTF-16 unit boundaries, anchor end-of-line rules,
 * zero-width iteration spans, {@code (?U)}); the RE2-lineage reading of
 * those axes ships as its own facade — the {@code :re2j} module's
 * {@code io.github.jemmix.tdfa.re2j.Pattern}, with the lane baked in —
 * and a per-axis mixed probe remains available through
 * {@link CompileOptions#semantics(io.github.jemmix.tdfa.core.tnfa.Semantics)}.
 * Malformed syntax fails
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
 * backend, alternative generator) — the machinery emits a shell around it
 * and the facade mints its own subclass around that, preserving the
 * monomorphic call chain.
 * {@code -Dtdfa.engine=VM} forces the
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
    int CASE_INSENSITIVE = PatternCompiler.CASE_INSENSITIVE;

    /**
     * Flag: dot ({@code .}) matches all characters, including newline.
     */
    int DOTALL = PatternCompiler.DOTALL;

    /**
     * Flag: multiline matching ({@code ^}/{@code $} at line boundaries).
     */
    int MULTILINE = PatternCompiler.MULTILINE;

    /**
     * Flag: matches longest possible string (leftmost-longest).
     */
    int LONGEST_MATCH = PatternCompiler.LONGEST_MATCH;

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
     * {@code No} are not. Like the JDK flag it mirrors, setting it also
     * makes {@link #CASE_INSENSITIVE} Unicode-aware (the JDK's
     * {@code UNICODE_CHARACTER_CLASS} implies {@code UNICODE_CASE} folding)
     * — {@code (?i)} folds the full Unicode simple-fold universe instead
     * of ASCII only. Exact membership is universe-dependent: the
     * default (JDK-derived) universe equals a same-JVM
     * {@code java.util.regex} oracle by construction; a pinned snapshot
     * universe freezes the same definition at its Unicode version. The
     * exact set is differentially pinned in {@code UnicodeBoundaryParityTest}.
     */
    int UNICODE_CHARACTER_CLASS = PatternCompiler.UNICODE_CHARACTER_CLASS;

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
    int FIND_ONLY = PatternCompiler.FIND_ONLY;

    /**
     * Flag: disable Unicode groups ({@code \p{...}} / {@code \P{...}} rejected at compile time, like re2j).
     */
    int DISABLE_UNICODE_GROUPS = PatternCompiler.DISABLE_UNICODE_GROUPS;

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
    int MULTI_VALUED_TAGS = PatternCompiler.MULTI_VALUED_TAGS;

    /**
     * Flag: {@code .}/{@code ^}/{@code $} recognize only {@code \n} as a
     * line terminator — JDK-exact name and semantics
     * ({@code java.util.regex.Pattern.UNIX_LINES}). The facade default is
     * the full java.util.regex terminator set
     * ({@code \n}, {@code \r}, {@code \r\n}, U+0085, U+2028, U+2029);
     * setting this bit restores the {@code \n}-only reading (the same
     * axis the {@code :re2j} facade bakes in).
     */
    int UNIX_LINES = PatternCompiler.UNIX_LINES;

    /**
     * Flag: {@code CASE_INSENSITIVE} folds the full Unicode simple-fold
     * universe — JDK-exact name and semantics
     * ({@code java.util.regex.Pattern.UNICODE_CASE}). The facade default
     * is java.util.regex's bare-CI reading (ASCII pairs only); setting
     * this bit widens the fold (the same axis the {@code :re2j} facade
     * bakes in, and what {@code (?U)} selects scoped).
     */
    int UNICODE_CASE = PatternCompiler.UNICODE_CASE;

    /**
     * Flag: the pattern is a literal string — every metacharacter
     * matches itself (JDK-exact name and semantics,
     * {@code java.util.regex.Pattern.LITERAL}). Composes with
     * {@link #CASE_INSENSITIVE} (the folded literals still fold,
     * JDK-verified); {@link #pattern()}, {@link #flags()} and
     * serialization round-trip the ORIGINAL pattern text and flag.
     */
    int LITERAL = PatternCompiler.LITERAL;

    /**
     * Flag: free-spacing mode — whitespace between tokens and
     * {@code #}-to-end-of-line comments are skipped at the parse
     * (JDK-exact name and semantics,
     * {@code java.util.regex.Pattern.COMMENTS}). Escaped whitespace
     * ({@code "\ "}) survives and {@code \Q...\E} bodies are immune;
     * the inline {@code (?x)} spelling selects the same behavior scoped
     * (this facade binds the {@code x} letter; the re2j alphabet has
     * none).
     */
    int COMMENTS = PatternCompiler.COMMENTS;

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
        return Lane.compile(regex, flags, factory, unicodeProvider, null, null);
    }

    /**
     * Compile with explicit options (semantics, tables, observer). An
     * options-carried {@link io.github.jemmix.tdfa.core.tnfa.Semantics
     * Semantics} selects the compile's interpretation policy (the JUR-compat
     * axes); absent, the default lane applies.
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
        return Lane.compile(regex, flags, null, options.unicodeProvider(), options.observer(), options.semantics());
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
        return Lane.compile(regex, flags | FIND_ONLY, null, null, null, null);
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
     * Create a {@link StreamMatcher} that matches this pattern over
     * chunked/streaming input — an {@code InputStream}, a
     * {@code Reader}, a {@code ByteBuffer} feed — without materializing
     * the whole input as a String. One matcher per stream,
     * single-threaded; match offsets are absolute UTF-16 unit positions
     * in the whole stream; matches are reported as soon as they are
     * decidable, byte-identical to the String surface over the growing
     * prefix. The retained window is budgeted
     * ({@code -Dtdfa.budget.stream.window}); group text is readable
     * until the next {@code next()}. A pattern compiled with a custom
     * engine factory that does not support streaming is rejected loudly
     * here. See {@code docs/streaming-input.md}.
     *
     * @param source the chunk feed (see {@code ChunkSources} for adapters)
     * @return a fresh streaming matcher owned by the caller
     */
    StreamMatcher streamingMatcher(ChunkSource source);

    /**
     * Split {@code input} around matches of this pattern. Trailing empty strings are omitted.
     */
    String[] split(String input);

    /**
     * Split {@code input} with a limit on the number of result strings.
     */
    String[] split(String input, int limit);

    /**
     * Split {@code input} around matches of this pattern — the
     * {@code java.util.regex.Pattern.split(CharSequence)} spelling: any
     * {@link CharSequence} input, answered identically to the
     * {@link #split(String)} twin over {@code input.toString()}.
     */
    default String[] split(CharSequence input) {
        return split(input.toString());
    }

    /**
     * Split {@code input} with a limit — the
     * {@code java.util.regex.Pattern.split(CharSequence, int)} spelling
     * (see {@link #split(CharSequence)}).
     */
    default String[] split(CharSequence input, int limit) {
        return split(input.toString(), limit);
    }

    /**
     * Creates a predicate which tests if this pattern is found in a
     * given input string — the
     * {@code java.util.regex.Pattern.asPredicate()} spelling
     * ({@code s -> matcher(s).find()}).
     */
    default Predicate<String> asPredicate() {
        return s -> matcher(s).find();
    }

    /**
     * Creates a lazy-ish stream over the substrings of {@code input}
     * split around this pattern — the
     * {@code java.util.regex.Pattern.splitAsStream(CharSequence)}
     * spelling. Trailing empty strings are discarded like
     * {@link #split(CharSequence)}, with the JDK's one observable
     * difference pinned live: an EMPTY input yields the one-element
     * stream {@code [""]} (where {@code split("")} is the empty array).
     */
    default Stream<String> splitAsStream(CharSequence input) {
        if (input.length() == 0) {
            return Stream.of("");
        }
        return Arrays.stream(split(input.toString()));
    }

    /**
     * Creates a predicate which tests if this pattern matches the whole
     * input — the {@code java.util.regex.Pattern.asMatchPredicate()}
     * spelling ({@code cs -> matcher(cs).matches()}, the JDK 21+ twin of
     * {@link #asPredicate()}).
     */
    default Predicate<CharSequence> asMatchPredicate() {
        return cs -> matcher(cs.toString()).matches();
    }

    /**
     * Splits around matches, keeping the delimiters — the
     * {@code java.util.regex.Pattern.splitWithDelimiters(CharSequence, int)}
     * spelling (JDK 21+): alternating text and delimiter pieces
     * {@code [text, delim, ..., text]}, the {@code limit} semantics of
     * {@link #split(CharSequence, int)} ({@code 0} trims the trailing
     * empty pieces, a positive bound applies the pattern at most
     * {@code limit - 1} times with the last text holding the remainder),
     * no-match inputs answer the whole input, empty inputs the empty
     * array. Live-oracle pinned.
     */
    default String[] splitWithDelimiters(CharSequence input, int limit) {
        if (input.length() == 0) {
            return new String[0];
        }
        int matchCount = 0;
        int index = 0;
        boolean matchLimited = limit > 0;
        List<String> matchList = new ArrayList<>();
        PatternMatcher m = matcher(input.toString());
        while (m.find()) {
            if (!matchLimited || matchCount < limit - 1) {
                if (index == 0 && m.start() == 0 && m.end() == 0) {
                    // no empty leading substring for a zero-width match at
                    // the very beginning (the JDK's own split rule)
                    continue;
                }
                matchList.add(input.subSequence(index, m.start()).toString());
                index = m.end();
                matchList.add(input.subSequence(m.start(), index).toString());
                matchCount++;
            } else if (matchCount == limit - 1) {
                matchList.add(input.subSequence(index, input.length()).toString());
                index = m.end();
                matchCount++;
            }
        }
        if (index == 0) {
            return new String[]{input.toString()};
        }
        if (!matchLimited || matchCount < limit) {
            matchList.add(input.subSequence(index, input.length()).toString());
        }
        int resultSize = matchList.size();
        if (limit == 0) {
            while (resultSize > 0 && matchList.get(resultSize - 1).isEmpty()) {
                resultSize--;
            }
        }
        return matchList.subList(0, resultSize).toArray(new String[0]);
    }

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
}
