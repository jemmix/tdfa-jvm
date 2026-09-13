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
 * compiles inside {@code compile()}; an artifact's death is decided there
 * (whole over budget &rarr; recorded rejection, any other failure &rarr;
 * compile fails) — the no-lazy-compiles design rule: no engine is ever
 * materialized on a match call.
 *
 * <p><b>Single compile, two artifacts at most.</b> The whole-match engine
 * ({@code matches()}) is an unpruned determinization
 * ({@link Tdfa#compileUnpruned}) of the SAME parse — not a second
 * parse/anchored wrap as before. find() shares that artifact whenever the
 * pike-cut predicate says the cut would change nothing
 * ({@code !wholeTdfa.pikeCutMatters()}); the rare alternation shapes where it
 * would (e.g. {@code ab|a|ac}) keep a pruned find compile for leftmost-first
 * exactness. One whole resolver serves every tier ({@link #whole}): the
 * find engine itself when the artifacts are shared, else a
 * {@link TdfaRunner} over the whole TDFA, else — the over-budget corner
 * only — a {@link TdfaRunner} over the eagerly compiled both-ends-anchored
 * TDFA (pruned determinization; every accept in an anchored build is
 * end-of-input-gated, so the pike cut is inert there and the cut-free whole
 * walk stays exact), else — both whole builds over budget — a holder that
 * rethrows the compile-time-recorded rejection on every whole call while
 * find() keeps working (acceptance follows the find artifact alone).
 * Nothing materializes at match time, ever.
 *
 * <p>Engine source resolution (provenance-based, no capability negotiation):
 * <ul>
 *   <li>{@code -Dtdfa.engine=VM} &rarr; shared implementation over the
 *       interpreter — no code generation anywhere;</li>
 *   <li>explicit {@link RegexEngineFactory} &rarr; shell emitted around the
 *       factory's FIND engine ({@code RegexEngine}-typed field — monomorphic
 *       per pattern); whole matching runs the facade's own whole engine —
 *       a custom engine's {@code matchWhole} is the interface default
 *       ({@code match(input, 0)}), whole-exact only over anchored
 *       artifacts, so the factory is never asked to execute whole
 *       matches; shared implementation as emission-failure fallback;</li>
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
            // compile() for seconds. On a bounded rejection whole() falls to
            // the eagerly compiled ANCHORED artifact — and if that build
            // rejects too, the rejection is RECORDED at compile time and
            // rethrown by every whole-match call (OverBudgetWhole): compile()
            // acceptance stays the find artifact's alone (the historical
            // contract — patterns whose whole DFA is intrinsically huge, e.g.
            // [\s\S]{0,60}x[\s\S]{0,60}'s counter cross-product, keep find()),
            // and NOTHING ever compiles at match time (no-lazy-compiles rule).
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
                obs.note("whole", "unpruned build over budget — anchored whole attempted eagerly");
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
                // One factory call (the find engine): whole matching runs the
                // facade's own whole engine — a custom engine's matchWhole is
                // the interface default (match(input,0)), whole-exact only
                // over anchored artifacts, so it cannot consume the shared
                // unpruned artifact. Over budget, whole() eagerly compiles
                // the anchored artifact; its rejection fails compile().
                RegexEngine whole = whole(fl, disableUnicodeGroups, longest, prov, regex,
                        findTdfa, wholeTdfa, eng);
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
     * Work budget (ticks) for the eager whole-match attempt: comfortably above
     * the worst legit in-corpus unpruned build measured (datefinder's
     * (?i)(?u) variant at ~115M ticks — tick counts are deterministic,
     * machine-independent) while rejecting cut-heavy shapes (aws-keys ~4G
     * ticks) in well under a second, so even a slow CI runner stays inside
     * the rebar compile-latency guard's 5 s budget (aws: ~1.4 s local, ~2 s
     * CI, including the bounded rejection, the pruned find compile and the
     * ASM generation).
     */
    private static final long WHOLE_WORK_CAP = 1L << 27;

    /**
     * Whole-match engine for EVERY tier: the find engine itself when the
     * artifacts are shared (one engine object, one generated class —
     * generated engines carry a native {@code matchWhole}), else a dedicated
     * interpreter over the whole TDFA, else — the over-budget corner — an
     * interpreter over the eagerly compiled anchored TDFA (pruned
     * determinization, but every accept in an anchored build is
     * end-of-input-gated, so the pike cut is inert there and the cut-free
     * whole walk is exact over it). If the anchored build ALSO rejects on
     * budget, the rejection is recorded and rethrown by every whole call
     * ({@link OverBudgetWhole}) — compile() acceptance follows the find
     * artifact alone. Everything is built inside {@code compile()}; no
     * engine materializes at match time.
     */
    private static RegexEngine whole(String fl, boolean disableUnicodeGroups, boolean longest,
                                     UnicodeDataProvider prov, String regex,
                                     Tdfa findTdfa, Tdfa wholeTdfa, RegexEngine findEngine) {
        if (wholeTdfa != null)
            return findTdfa == wholeTdfa ? findEngine : new TdfaRunner(wholeTdfa);
        try {
            return new TdfaRunner(
                    anchorTdfa(fl, disableUnicodeGroups, longest, prov, regex, WHOLE_WORK_CAP));
        } catch (RuntimeException over) {
            if (!budgetRejection(over)) throw over;
            return new OverBudgetWhole(findEngine, over);
        }
    }

    /**
     * Whole engine for the both-builds-over-budget corner: the compile-time
     * rejection IS the whole engine's permanent answer — {@code matchWhole}/
     * {@code matches} rethrow the recorded instance on every call
     * (deterministic; zero compile at match time — the no-lazy-compiles
     * rule). Find operations delegate to the find engine: compile()
     * acceptance follows the find artifact alone, the pre-eager facade's
     * contract (fuzz round 27's spin family — the lazy engine re-burned its
     * doomed anchored compile per matches() call — is dead by construction:
     * the failure is computed once, inside compile()).
     */
    private static final class OverBudgetWhole implements RegexEngine {
        private final RegexEngine find;
        private final RuntimeException rejection;
        OverBudgetWhole(RegexEngine find, RuntimeException rejection) {
            this.find = find;
            this.rejection = rejection;
        }

        @Override public boolean matches(CharSequence input) { throw rejection; }
        @Override public io.github.jemmix.tdfa.core.MatchResult matchWhole(CharSequence input) {
            throw rejection;
        }
        @Override public boolean find(CharSequence input) { return find.find(input); }
        @Override public io.github.jemmix.tdfa.core.MatchResult match(CharSequence input, int from) {
            return find.match(input, from);
        }
        @Override public int groupCount() { return find.groupCount(); }
        @Override public java.util.Map<String, Integer> namedGroups() { return find.namedGroups(); }
        @Override public int programSize() { return find.programSize(); }
    }

    /**
     * The determinization budget-rejection idiom ("pattern too large: ..."),
     * in either shape it reaches this class: the raw {@code IllegalStateException}
     * from {@code Tdfa.compile*}, or the translated {@code PatternSyntaxException}
     * from {@link #anchorTdfa} (which wraps rejections for whole()).
     */
    private static boolean budgetRejection(RuntimeException ex) {
        String m = ex.getMessage();
        return m != null && m.contains("pattern too large");
    }

    /** {@code -Dtdfa.engine=VM}: global no-codegen switch, read per compile. */
    private static boolean vmSwitched() {
        return "VM".equalsIgnoreCase(System.getProperty("tdfa.engine"));
    }

    /**
     * Anchored both-ends TDFA for the over-budget whole corner: every accept
     * in an anchored build is end-of-input-gated, so the pike cut is inert
     * and the cut-free whole walk is exact over the (pruned) artifact.
     * Compiled eagerly inside {@code compile()} under the same work cap;
     * rejections translate to the facade's {@code PatternSyntaxException}
     * (the caller records them or fails the compile).
     */
    private static Tdfa anchorTdfa(String flregex, boolean disableUnicodeGroups,
                                   boolean longest, UnicodeDataProvider prov, String regex,
                                   long workCap) {
        try {
            Tnfa an = Tnfa.compile(flregex, disableUnicodeGroups, true, prov);
            return Tdfa.compile(an, longest, null, workCap);
        } catch (RuntimeException e) {
            throw io.github.jemmix.tdfa.core.CompiledRegex.translate(e, regex);
        }
    }
}
