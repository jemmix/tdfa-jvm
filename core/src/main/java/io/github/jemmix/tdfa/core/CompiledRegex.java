package io.github.jemmix.tdfa.core;

import io.github.jemmix.tdfa.tdfa.Tdfa;
import io.github.jemmix.tdfa.tnfa.Tnfa;
import io.github.jemmix.tdfa.unicode.UnicodeDataProvider;
import io.github.jemmix.tdfa.unicode.UnicodeProviders;

/**
 * Core-tier compiled pattern: compile once, match many, interpreter-only.
 *
 * <p>Holds the engine (a {@link RegexEngine} over the compiled TDFA) and
 * the pattern string. The facade tier ({@code Pattern.compile}) builds the
 * same shape on top of generated or custom engines, and layers the stateful
 * {@code Matcher} (find iteration, group access, replacement) plus a
 * capture-extracting anchored engine over it; this class is the evergreen,
 * zero-dependency variant with the match/iterate surface and tag-level
 * capture access via {@link MatchResult}.
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

    private CompiledRegex(String pattern, RegexEngine engine) {
        this.pattern = pattern;
        this.engine = engine;
    }

    /** Compile with default options (leftmost-first, JDK-default Unicode tables). */
    public static CompiledRegex compile(String pattern) {
        return compile(pattern, CompileOptions.of());
    }

    /** Compile with explicit options. Throws {@link PatternSyntaxException} on malformed patterns. */
    public static CompiledRegex compile(String pattern, CompileOptions options) {
        if (pattern == null) throw new NullPointerException("pattern is null");
        return new CompiledRegex(pattern, pipeline(pattern, options, false));
    }

    static RegexEngine pipeline(String pattern, CompileOptions options, boolean anchorBoth) {
        try {
            UnicodeDataProvider provider = options.unicodeProvider() != null
                    ? options.unicodeProvider() : UnicodeProviders.get();
            CompileObserver obs = options.observer() != null ? options.observer() : CompileObserver.NONE;
            Tnfa nfa = Tnfa.compile(pattern, options.isDisableUnicodeGroups(), anchorBoth, provider, obs);
            Tdfa tdfa = Tdfa.compile(nfa, options.isLongestMatch(), obs);
            long t0 = System.nanoTime();
            RegexEngine engine = new io.github.jemmix.tdfa.tdfa.TdfaRunner(tdfa);
            obs.stage(CompileObserver.Stage.ENGINE, System.nanoTime() - t0, 0);
            obs.note("engine", "interpreter");
            return engine;
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

    public boolean matches(CharSequence input) { return engine.matches(input); }

    public boolean find(CharSequence input) { return engine.find(input); }

    public MatchResult match(CharSequence input, int from) { return engine.match(input, from); }

    public Iterable<MatchResult> findAll(CharSequence input) { return engine.findAll(input); }

    public String pattern() { return pattern; }
}
