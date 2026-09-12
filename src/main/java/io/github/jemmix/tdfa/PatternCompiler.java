package io.github.jemmix.tdfa;

import io.github.jemmix.tdfa.core.RegexEngine;
import io.github.jemmix.tdfa.core.RegexEngineFactory;
import io.github.jemmix.tdfa.tdfa.Tdfa;
import io.github.jemmix.tdfa.tdfa.TdfaRunner;
import io.github.jemmix.tdfa.tnfa.Tnfa;
import io.github.jemmix.tdfa.unicode.UnicodeDataProvider;
import io.github.jemmix.tdfa.unicode.UnicodeProviders;

/**
 * {@link Pattern} compilation orchestration: flags &rarr; inline-flag prefix,
 * pipeline (parse &rarr; TNFA &rarr; TDFA), engine-source resolution, and
 * shell-or-shared implementation selection. Fully eager — everything
 * compiles inside {@code compile()}.
 *
 * <p><b>Single compile, two artifacts at most.</b> The whole-match engine
 * ({@code matches()}) is an unpruned determinization
 * ({@link Tdfa#compileUnpruned}) of the SAME parse — not a second
 * parse/anchored wrap as before. find() shares that artifact whenever the
 * pike-cut predicate says the cut would change nothing
 * ({@code !wholeTdfa.pikeCutMatters()}); the rare alternation shapes where it
 * would (e.g. {@code ab|a|ac}) keep a pruned find compile for leftmost-first
 * exactness. The BYO-factory path still hands the factory an ANCHORED TDFA
 * for its whole engine (custom engines implement {@code matchWhole} via the
 * interface default, which is whole-exact only over anchored artifacts).
 *
 * <p>Engine source resolution (provenance-based, no capability negotiation):
 * <ul>
 *   <li>{@code -Dtdfa.engine=VM} &rarr; shared implementation over the
 *       interpreter — no code generation anywhere;</li>
 *   <li>explicit {@link RegexEngineFactory} &rarr; shell emitted around the
 *       factory's engines ({@code RegexEngine}-typed field — monomorphic
 *       per pattern), shared implementation as emission-failure fallback;</li>
 *   <li>default &rarr; ASM per-pattern engine generation with a
 *       concrete-typed shell, shared implementation as fallback.</li>
 * </ul>
 */
final class PatternCompiler {

    private PatternCompiler() { }

    static Pattern compile(String regex, int flags, RegexEngineFactory factory,
                           UnicodeDataProvider provider) {
        return compile(regex, flags, factory, provider, null);
    }

    static Pattern compile(String regex, int flags, RegexEngineFactory factory,
                           UnicodeDataProvider provider,
                           io.github.jemmix.tdfa.core.CompileObserver observer) {
        if (regex == null) throw new NullPointerException("pattern is null");
        if ((flags & ~VALID_FLAGS) != 0) {
            throw new IllegalArgumentException(
                    "Flags should only be a combination of MULTILINE, DOTALL, CASE_INSENSITIVE, DISABLE_UNICODE_GROUPS, LONGEST_MATCH, UNICODE_CHARACTER_CLASS");
        }
        String flregex = regex;
        if ((flags & Pattern.CASE_INSENSITIVE) != 0) flregex = "(?i)" + flregex;
        if ((flags & Pattern.DOTALL) != 0)          flregex = "(?s)" + flregex;
        if ((flags & Pattern.MULTILINE) != 0)       flregex = "(?m)" + flregex;
        if ((flags & Pattern.UNICODE_CHARACTER_CLASS) != 0) flregex = "(?u)" + flregex;
        boolean longest = (flags & Pattern.LONGEST_MATCH) != 0;
        boolean disableUnicodeGroups = (flags & Pattern.DISABLE_UNICODE_GROUPS) != 0;
        final UnicodeDataProvider prov = provider != null ? provider : UnicodeProviders.get();
        final String fl = flregex;
        final io.github.jemmix.tdfa.core.CompileObserver obs = observer != null
                ? observer : io.github.jemmix.tdfa.core.CompileObserver.NONE;
        try {
            Tnfa nfa = Tnfa.compile(fl, disableUnicodeGroups, false, prov, obs);

            // Whole-match artifact: cut-free transitions so an accept alive at
            // EOF is a full match (Tdfa.compileUnpruned). find() shares it
            // unless the pike cut provably matters; then find keeps the pruned
            // compile and whole runs on this one (two artifacts, one parse).
            //
            // The whole attempt is WORK-BOUNDED (WHOLE_WORK_CAP): a cut-heavy
            // pattern's cut-free build can churn orders of magnitude past its
            // pruned cost (aws-keys: ~4G ticks vs 30M) before the output caps
            // trip, and an eager attempt at the default budget would stall
            // compile() for seconds. A bounded rejection degrades whole to the
            // historical LAZY anchored engine — compile() acceptance stays
            // exactly the find artifact's, and matches() surfaces the
            // rejection on first use (the pre-eager observable behavior).
            Tdfa wholeTdfa;
            Tdfa findTdfa;
            try {
                wholeTdfa = Tdfa.compileUnpruned(nfa, longest, obs, WHOLE_WORK_CAP);
                if (wholeTdfa.pikeCutMatters()) {
                    obs.note("pikeCut", "find recompiled (pruned; whole kept unpruned)");
                    findTdfa = Tdfa.compile(nfa, longest, obs);
                } else {
                    findTdfa = wholeTdfa;
                }
            } catch (RuntimeException overBudget) {
                if (!budgetRejection(overBudget)) throw overBudget;
                obs.note("whole", "unpruned build over budget — lazy anchored whole;"
                        + " compile acceptance follows the find artifact");
                wholeTdfa = null;
                findTdfa = Tdfa.compile(nfa, longest, obs);
            }
            int ps = findTdfa.stateCount();

            if (vmSwitched()) {
                obs.note("engine", "shared-interpreter (tdfa.engine=VM)");
                RegexEngine eng = new TdfaRunner(findTdfa);
                return new TDFAPattern(regex, flags, ps, eng,
                        whole(fl, disableUnicodeGroups, longest, prov, regex, findTdfa, wholeTdfa, eng), provider);
            }

            if (factory != null) {
                long t0 = System.nanoTime();
                RegexEngine eng = factory.create(findTdfa);
                // BYO whole is ALWAYS the anchored artifact: a custom engine's
                // matchWhole is the interface default (match(input, 0)),
                // whole-exact only over an anchored TDFA — sharing the
                // unpruned find artifact is safe solely for engines with a
                // native matchWhole (TdfaRunner, generated classes). Over
                // budget, degrade to the historical lazy anchored engine.
                RegexEngine whole;
                try {
                    whole = factory.create(
                            anchorTdfa(fl, disableUnicodeGroups, longest, prov, regex));
                } catch (RuntimeException over) {
                    if (!budgetRejection(over)) throw over;
                    whole = new LazyEngine(() -> factory.create(
                            anchorTdfa(fl, disableUnicodeGroups, longest, prov, regex)));
                }
                obs.stage(io.github.jemmix.tdfa.core.CompileObserver.Stage.ENGINE,
                        System.nanoTime() - t0, 0);
                try {
                    Pattern p = (Pattern) io.github.jemmix.tdfa.asm.ShellEmitter.emit(
                            new io.github.jemmix.tdfa.asm.ShellEmitter.Spec(
                                    regex, flags, ps, eng, whole, null, provider));
                    obs.note("engine", "byo-shell");
                    return p;
                } catch (RuntimeException ex) {
                    if (Boolean.getBoolean("tdfa.gen.debug")) ex.printStackTrace();
                    obs.note("engine", "shared (byo-shell emission failed)");
                    return new TDFAPattern(regex, flags, ps, eng, whole, provider);
                }
            }

            // Default: ASM per-pattern engine + concrete-typed shell.
            // LinkageError is caught alongside RuntimeException: generated
            // bytecode failures (VerifyError from defineClass, NoSuchMethod/
            // NoSuchFieldError from lazy shell linkage) must degrade to the
            // interpreter fallback, not escape Pattern.compile as raw Errors —
            // this catch is the safety net for any post-thaw emitter bug.
            io.github.jemmix.tdfa.asm.TdfaAsmBackend.Generated gen;
            long t1 = System.nanoTime();
            try {
                gen = io.github.jemmix.tdfa.asm.TdfaAsmBackend.generate(findTdfa);
            } catch (RuntimeException | LinkageError genFailure) {
                if (Boolean.getBoolean("tdfa.gen.debug")) genFailure.printStackTrace();
                obs.note("engine", "shared-interpreter (engine emission failed)");
                RegexEngine eng = new TdfaRunner(findTdfa);
                return new TDFAPattern(regex, flags, ps, eng, whole(fl, disableUnicodeGroups, longest, prov, regex, findTdfa, wholeTdfa, eng), provider);
            }
            obs.stage(io.github.jemmix.tdfa.core.CompileObserver.Stage.ENGINE,
                    System.nanoTime() - t1, 0);
            try {
                Pattern p = (Pattern) io.github.jemmix.tdfa.asm.ShellEmitter.emit(
                        new io.github.jemmix.tdfa.asm.ShellEmitter.Spec(
                                regex, flags, ps, gen.engine(),
                                whole(fl, disableUnicodeGroups, longest, prov, regex,
                                        findTdfa, wholeTdfa, gen.engine()),
                                gen.owner(), provider));
                obs.note("engine", "generated");
                return p;
            } catch (RuntimeException | LinkageError ex) {
                if (Boolean.getBoolean("tdfa.gen.debug")) ex.printStackTrace();
                obs.note("engine", "shared-interpreter (shell emission failed)");
                RegexEngine eng = new TdfaRunner(findTdfa);
                return new TDFAPattern(regex, flags, ps, eng, whole(fl, disableUnicodeGroups, longest, prov, regex, findTdfa, wholeTdfa, eng), provider);
            }
        } catch (RuntimeException e) {
            throw io.github.jemmix.tdfa.core.CompiledRegex.translate(e, regex);
        }
    }

    private static final int VALID_FLAGS = Pattern.CASE_INSENSITIVE | Pattern.DOTALL
            | Pattern.MULTILINE | Pattern.DISABLE_UNICODE_GROUPS | Pattern.LONGEST_MATCH
            | Pattern.UNICODE_CHARACTER_CLASS;

    /**
     * Work budget (ticks) for the eager whole-match attempt: ~3x the worst
     * legit in-corpus unpruned build measured (datefinder at ~88M ticks /
     * ~0.7 s) while rejecting cut-heavy shapes (aws-keys ~4G ticks) in about
     * a second instead of stalling compile() for tens of seconds.
     */
    private static final long WHOLE_WORK_CAP = 1L << 28;

    /**
     * Whole-match engine for the facade's own tiers: the find engine itself
     * when the artifacts are shared (one engine object, one generated class —
     * generated engines carry a native {@code matchWhole}), else a dedicated
     * interpreter over the whole TDFA — or, on the over-budget bomb corner,
     * the historical lazy anchored engine.
     */
    private static RegexEngine whole(String fl, boolean disableUnicodeGroups, boolean longest,
                                     UnicodeDataProvider prov, String regex,
                                     Tdfa findTdfa, Tdfa wholeTdfa, RegexEngine findEngine) {
        if (wholeTdfa == null)
            return new LazyEngine(() -> new TdfaRunner(
                    anchorTdfa(fl, disableUnicodeGroups, longest, prov, regex)));
        return findTdfa == wholeTdfa ? findEngine : new TdfaRunner(wholeTdfa);
    }

    /**
     * The determinization budget-rejection idiom ("pattern too large: ..."),
     * in either shape it reaches this class: the raw {@code IllegalStateException}
     * from {@code Tdfa.compile*}, or the translated {@code PatternSyntaxException}
     * from {@link #anchorTdfa} (which wraps for its lazy callers).
     */
    private static boolean budgetRejection(RuntimeException ex) {
        String m = ex.getMessage();
        return m != null && m.contains("pattern too large");
    }

    /**
     * Budget-corner whole engine: compiles its delegate on first use — the
     * pre-eager facade's lazy behavior, kept solely for bomb patterns whose
     * whole builds exceed the determinization caps (see the compile ladder in
     * {@link #compile}). Benign race: redundant compiles discard all but one
     * engine. All hot entries delegate to the resolved engine.
     */
    private static final class LazyEngine implements RegexEngine {
        private final java.util.function.Supplier<RegexEngine> src;
        private volatile RegexEngine delegate;
        LazyEngine(java.util.function.Supplier<RegexEngine> src) { this.src = src; }

        private RegexEngine eng() {
            RegexEngine e = delegate;
            if (e == null) { e = src.get(); delegate = e; }
            return e;
        }

        @Override public boolean matches(CharSequence input) { return eng().matches(input); }
        @Override public boolean find(CharSequence input) { return eng().find(input); }
        @Override public io.github.jemmix.tdfa.core.MatchResult match(CharSequence input, int from) {
            return eng().match(input, from);
        }
        @Override public io.github.jemmix.tdfa.core.MatchResult matchWhole(CharSequence input) {
            return eng().matchWhole(input);
        }
        @Override public int groupCount() { return eng().groupCount(); }
        @Override public java.util.Map<String, Integer> namedGroups() { return eng().namedGroups(); }
        @Override public int programSize() { return eng().programSize(); }
    }

    /** {@code -Dtdfa.engine=VM}: global no-codegen switch, read per compile. */
    private static boolean vmSwitched() {
        return "VM".equalsIgnoreCase(System.getProperty("tdfa.engine"));
    }

    /**
     * Anchored both-ends TDFA for the BYO-factory whole engine: the factory's
     * engine answers {@code matchWhole} through the interface default
     * ({@code match(input, 0)}), which is whole-exact only over an anchored
     * artifact. Compiled eagerly inside {@code compile()}.
     */
    private static Tdfa anchorTdfa(String flregex, boolean disableUnicodeGroups,
                                   boolean longest, UnicodeDataProvider prov, String regex) {
        try {
            Tnfa an = Tnfa.compile(flregex, disableUnicodeGroups, true, prov);
            return Tdfa.compile(an, longest);
        } catch (RuntimeException e) {
            throw io.github.jemmix.tdfa.core.CompiledRegex.translate(e, regex);
        }
    }
}
