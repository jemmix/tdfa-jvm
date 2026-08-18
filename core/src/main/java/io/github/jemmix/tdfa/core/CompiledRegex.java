package io.github.jemmix.tdfa.core;

import io.github.jemmix.tdfa.tdfa.Tdfa;
import io.github.jemmix.tdfa.tnfa.Tnfa;
import io.github.jemmix.tdfa.unicode.UnicodeDataProvider;
import io.github.jemmix.tdfa.unicode.UnicodeProviders;

import java.util.Map;

/**
 * Core-tier compiled pattern: compile once, match many, interpreter-only.
 *
 * <p>Holds the pair of engines a stateful matching API needs — the plain
 * (unanchored) engine and the lazily-compiled
 * {@code \A(?:...)\z}-anchored engine backing {@code matches()} — plus the
 * pattern metadata (group count, named groups). The facade tier
 * ({@code Pattern.compile}) builds the same shape on top of generated or
 * custom engines; this class is the evergreen, zero-dependency variant.
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
    private final java.util.function.Supplier<RegexEngine> wholeSupplier;

    /** Racy single-check: compilation is deterministic, so under a race
     *  either instance is correct; the volatile write publishes it safely. */
    private volatile RegexEngine wholeEngine;

    private CompiledRegex(String pattern, RegexEngine engine,
                          java.util.function.Supplier<RegexEngine> wholeSupplier) {
        this.pattern = pattern;
        this.engine = engine;
        this.wholeSupplier = wholeSupplier;
    }

    /** Compile with default options (leftmost-first, JDK-default Unicode tables). */
    public static CompiledRegex compile(String pattern) {
        return compile(pattern, CompileOptions.of());
    }

    /** Compile with explicit options. Throws {@link PatternSyntaxException} on malformed patterns. */
    public static CompiledRegex compile(String pattern, CompileOptions options) {
        if (pattern == null) throw new NullPointerException("pattern is null");
        RegexEngine e = pipeline(pattern, options, false);
        java.util.function.Supplier<RegexEngine> whole =
                () -> pipeline(pattern, options, true);
        return new CompiledRegex(pattern, e, whole);
    }

    static RegexEngine pipeline(String pattern, CompileOptions options, boolean anchorBoth) {
        try {
            UnicodeDataProvider provider = options.unicodeProvider() != null
                    ? options.unicodeProvider() : UnicodeProviders.get();
            Tnfa nfa = Tnfa.compile(pattern, options.isDisableUnicodeGroups(), anchorBoth, provider);
            Tdfa tdfa = Tdfa.compile(nfa, options.isLongestMatch());
            return new io.github.jemmix.tdfa.tdfa.TdfaRunner(tdfa);
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

    /** The main (unanchored) engine. */
    public RegexEngine engine() { return engine; }

    /** Engine for {@code matches()}: pattern anchored both ends, compiled lazily on first use. */
    public RegexEngine wholeEngine() {
        RegexEngine w = wholeEngine;
        if (w == null) {
            w = wholeSupplier.get();
            wholeEngine = w;
        }
        return w;
    }

    /** A stateful {@link Matcher} over {@code input} (find iteration, groups, replacement). */
    public Matcher matcher(CharSequence input) {
        return new Matcher(engine, this::wholeEngine, input);
    }

    public boolean matches(CharSequence input) { return engine.matches(input); }

    public boolean find(CharSequence input) { return engine.find(input); }

    public MatchResult match(CharSequence input, int from) { return engine.match(input, from); }

    public Iterable<MatchResult> findAll(CharSequence input) { return engine.findAll(input); }

    public String pattern() { return pattern; }

    public int groupCount() { return engine.groupCount(); }

    public Map<String, Integer> namedGroups() { return engine.namedGroups(); }

    /** Cost estimate: number of states in the compiled DFA. */
    public int programSize() { return engine.programSize(); }
}
