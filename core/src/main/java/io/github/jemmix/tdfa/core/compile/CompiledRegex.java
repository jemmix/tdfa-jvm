package io.github.jemmix.tdfa.core.compile;

import io.github.jemmix.tdfa.core.budget.Budgets;
import io.github.jemmix.tdfa.core.budget.WorkMeter;
import io.github.jemmix.tdfa.core.determinize.Determinizer;
import io.github.jemmix.tdfa.core.dfa.Tdfa;
import io.github.jemmix.tdfa.core.dfa.TdfaRunner;
import io.github.jemmix.tdfa.core.engine.MatchResult;
import io.github.jemmix.tdfa.core.engine.RegexEngine;
import io.github.jemmix.tdfa.core.report.CompileObserver;
import io.github.jemmix.tdfa.core.tnfa.Tnfa;
import io.github.jemmix.tdfa.core.unicode.UnicodeDataProvider;
import io.github.jemmix.tdfa.core.unicode.UnicodeProviders;

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
 * <p><b>Find-only by construction.</b> No whole-match machinery is ever
 * attempted here — the plain pruned determinization, no partial-whole
 * side table — so patterns whose whole-match divergence would reject a
 * facade {@code Pattern.compile} (the "pattern too large: whole-match
 * divergence" family) compile fine. There is no whole-input surface to
 * refuse. This is the core tier's standing find-only API; the facade's
 * equivalent (same skipped machinery, full {@code Matcher} surface,
 * whole methods throwing {@code UnsupportedOperationException}) is
 * {@code Pattern.compileFind} / {@code Pattern.FIND_ONLY}.
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

    /** Compile with explicit options. Syntax errors throw {@link PatternSyntaxException};
     *  budget rejections throw {@link PatternTooLargeException}. */
    public static CompiledRegex compile(String pattern, CompileOptions options) {
        if (pattern == null) {
            throw new NullPointerException("pattern is null");
        }
        UnicodeDataProvider provider =
            options.unicodeProvider() != null ? options.unicodeProvider() : UnicodeProviders.get();
        CompileObserver obs = options.observer() != null ? options.observer() : CompileObserver.NONE;
        WorkMeter ledger = new WorkMeter(Budgets.compileComputeTicks());
        Tnfa nfa = Tnfa.compile(pattern, options.isDisableUnicodeGroups(), false, provider, obs, ledger);
        Tdfa find = Determinizer.compile(nfa, options.isLongestMatch(), obs, ledger.fork(0));
        long t0 = System.nanoTime();
        RegexEngine engine = new TdfaRunner(find, Budgets.runtimeMemoryBytes());
        obs.stage(CompileObserver.Stage.ENGINE, System.nanoTime() - t0, 0);
        obs.note("engine", "interpreter");
        return new CompiledRegex(pattern, engine);
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
