package io.github.jemmix.tdfa.core;

import io.github.jemmix.tdfa.tdfa.Tdfa;
import io.github.jemmix.tdfa.tnfa.Tnfa;
import io.github.jemmix.tdfa.unicode.UnicodeDataProvider;
import io.github.jemmix.tdfa.unicode.UnicodeProviders;

/**
 * Core-tier compiled pattern: compile once, match many, interpreter-only.
 *
 * <p>Holds the find engine (a {@link RegexEngine} over the compiled TDFA)
 * and, by the single-compile ladder ({@link SingleCompile}), the eagerly
 * compiled whole-match engine backing {@link #matches} — one parse, at most
 * two artifacts, everything built inside {@code compile()}. The facade tier
 * ({@code Pattern.compile}) builds the same shape on top of generated or
 * custom engines and layers the stateful {@code Matcher} (find iteration,
 * group access, replacement); this class is the evergreen, zero-dependency
 * variant with the match/iterate surface and tag-level capture access via
 * {@link MatchResult}.
 *
 * <p><b>Thread safety:</b> safe for concurrent use — instances are
 * immutable; the input CharSequence must not be mutated during matching.
 *
 * <pre>
 *   CompiledRegex r = CompiledRegex.compile("(\\w+)@(\\w+)");
 *   if (r.find("hello user@example.com")) {
 *       MatchResult m = r.match("hello user@example.com", 0);
 *   }
 *   for (MatchResult m : r.findAll(text)) { ... }
 * </pre>
 */
public final class CompiledRegex {

    private final String pattern;
    private final RegexEngine engine;
    private final RegexEngine wholeEngine;

    private CompiledRegex(String pattern, RegexEngine engine, RegexEngine wholeEngine) {
        this.pattern = pattern;
        this.engine = engine;
        this.wholeEngine = wholeEngine;
    }

    /** Compile with default options (leftmost-first, JDK-default Unicode tables). */
    public static CompiledRegex compile(String pattern) {
        return compile(pattern, CompileOptions.of());
    }

    /** Compile with explicit options. Throws {@link PatternSyntaxException} on malformed patterns. */
    public static CompiledRegex compile(String pattern, CompileOptions options) {
        if (pattern == null) throw new NullPointerException("pattern is null");
        UnicodeDataProvider provider = options.unicodeProvider() != null
                ? options.unicodeProvider() : UnicodeProviders.get();
        CompileObserver obs = options.observer() != null ? options.observer() : CompileObserver.NONE;
        try {
            // One CPU ledger for the whole compile (front-end + every
            // shipped ladder attempt), and the per-engine runtime-memo
            // split when the pattern keeps a dedicated whole runner —
            // see PatternCompiler for the same wiring.
            io.github.jemmix.tdfa.tdfa.WorkMeter ledger = new io.github.jemmix.tdfa.tdfa.WorkMeter(
                    io.github.jemmix.tdfa.tdfa.Budgets.compileComputeTicks());
            Tnfa nfa = Tnfa.compile(pattern, options.isDisableUnicodeGroups(), false, provider, obs, ledger);
            SingleCompile.Artifacts a = SingleCompile.artifacts(nfa, options.isLongestMatch(), obs, ledger);
            long t0 = System.nanoTime();
            long memoBudget = io.github.jemmix.tdfa.tdfa.Budgets.runtimeMemoryBytes()
                    / (a.shared() ? 1 : 2);
            RegexEngine engine = new io.github.jemmix.tdfa.tdfa.TdfaRunner(a.find, memoBudget);
            RegexEngine whole = SingleCompile.wholeEngine(a, engine, pattern, pattern,
                    options.isDisableUnicodeGroups(), options.isLongestMatch(),
                    options.isDeferWholeRejection(), provider, ledger);
            obs.stage(CompileObserver.Stage.ENGINE, System.nanoTime() - t0, 0);
            obs.note("engine", "interpreter");
            return new CompiledRegex(pattern, engine, whole);
        } catch (PatternSyntaxException e) {
            throw e;
        } catch (RuntimeException e) {
            throw translate(e, pattern);
        }
    }

    /**
     * Translate internal parser exceptions into {@link PatternSyntaxException}
     * with re2j's exact message format (including the special-cased
     * {@code \C} escape message re2j's test suite exact-matches on).
     */
    public static PatternSyntaxException translate(RuntimeException e, String pattern) {
        String msg = e.getMessage();
        PatternSyntaxException pse;
        if (msg != null) {
            if (msg.equals("invalid escape sequence: \\C")) {
                pse = new PatternSyntaxException("invalid escape sequence", "\\C");
            } else {
                pse = new PatternSyntaxException(msg, pattern);
            }
        } else {
            pse = new PatternSyntaxException("internal error", pattern);
        }
        pse.initCause(e);
        return pse;
    }

    /** Whole-input match ({@code matches()} semantics) through the eagerly
     *  compiled whole engine — exact for whole-match continuations the
     *  leftmost-first find artifact would have pruned (e.g. {@code (a|ab)}
     *  whole-matches {@code "ab"}). For both-builds-over-budget patterns
     *  compiled with {@link CompileOptions#deferWholeRejection()}, rethrows
     *  the compile-time-recorded rejection (without the option, such
     *  patterns fail {@code compile()} instead). The null carrier lets
     *  carrier-free engines run without a scratch allocation; the
     *  interpreter allocates on demand at its entry. */
    public boolean matches(CharSequence input) { return wholeEngine.matchWhole(input, null) != null; }

    public boolean find(CharSequence input) { return engine.find(input); }

    public MatchResult match(CharSequence input, int from) { return engine.match(input, from, null); }

    public Iterable<MatchResult> findAll(CharSequence input) { return engine.findAll(input); }

    public String pattern() { return pattern; }
}
