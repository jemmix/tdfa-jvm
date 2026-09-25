package io.github.jemmix.tdfa.core;

import io.github.jemmix.tdfa.tdfa.Budgets;
import io.github.jemmix.tdfa.tdfa.Tdfa;
import io.github.jemmix.tdfa.tdfa.TdfaRunner;
import io.github.jemmix.tdfa.tdfa.WorkMeter;
import io.github.jemmix.tdfa.tnfa.Tnfa;
import io.github.jemmix.tdfa.unicode.UnicodeDataProvider;
import io.github.jemmix.tdfa.unicode.UnicodeProviders;

/**
 * Core-tier compiled pattern: compile once, match many, interpreter-only.
 *
 * <p>Holds the single find engine over the compiled TDFA. The facade tier
 * ({@code Pattern.compile}) builds the same shape on top of generated or
 * custom engines and layers the stateful {@code Matcher} (find iteration,
 * group access, replacement, whole-input {@code matches()}); this class is
 * the evergreen, zero-dependency variant with the find surface
 * ({@link #find}, {@link #match}, {@link #findAll}) and tag-level capture
 * access via {@link MatchResult}.
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
        if (pattern == null) {
            throw new NullPointerException("pattern is null");
        }
        UnicodeDataProvider provider =
            options.unicodeProvider() != null ? options.unicodeProvider() : UnicodeProviders.get();
        CompileObserver obs = options.observer() != null ? options.observer() : CompileObserver.NONE;
        try {
            WorkMeter ledger = new WorkMeter(Budgets.compileComputeTicks());
            Tnfa nfa = Tnfa.compile(pattern, options.isDisableUnicodeGroups(), false, provider, obs, ledger);
            Tdfa find = Tdfa.compile(nfa, options.isLongestMatch(), obs, ledger.fork(0));
            long t0 = System.nanoTime();
            RegexEngine engine = new TdfaRunner(find, Budgets.runtimeMemoryBytes());
            obs.stage(CompileObserver.Stage.ENGINE, System.nanoTime() - t0, 0);
            obs.note("engine", "interpreter");
            return new CompiledRegex(pattern, engine);
        } catch (PatternSyntaxException e) {
            throw e;
        } catch (RuntimeException e) {
            throw PatternSyntaxException.translate(e, pattern);
        }
    }

    public boolean find(CharSequence input) {
        return engine.find(input);
    }

    public MatchResult match(CharSequence input, int from) {
        return engine.match(input, from, null);
    }

    public Iterable<MatchResult> findAll(CharSequence input) {
        return engine.findAll(input);
    }

    public String pattern() {
        return pattern;
    }
}
