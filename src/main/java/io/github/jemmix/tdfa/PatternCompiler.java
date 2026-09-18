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
 * (whole over budget &rarr; compile fails, or with
 * {@code DEFER_WHOLE_REJECTION} a recorded rejection thrown by whole calls;
 * any other failure &rarr; compile fails) — the no-lazy-compiles design
 * rule: no engine is ever materialized on a match call.
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
 * walk stays exact), else — both whole builds over budget — compile()
 * FAILS by default; opted in via {@code Pattern.DEFER_WHOLE_REJECTION}, a
 * holder that rethrows the compile-time-recorded rejection on every whole
 * call while find() keeps working (acceptance follows the find artifact
 * alone). Nothing materializes at match time, ever.
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
                    "Flags should only be a combination of MULTILINE, DOTALL, CASE_INSENSITIVE, DISABLE_UNICODE_GROUPS, LONGEST_MATCH, UNICODE_CHARACTER_CLASS, DEFER_WHOLE_REJECTION");
        }
        String flregex = regex;
        if ((flags & Pattern.CASE_INSENSITIVE) != 0) flregex = "(?i)" + flregex;
        if ((flags & Pattern.DOTALL) != 0)          flregex = "(?s)" + flregex;
        if ((flags & Pattern.MULTILINE) != 0)       flregex = "(?m)" + flregex;
        if ((flags & Pattern.UNICODE_CHARACTER_CLASS) != 0) flregex = "(?u)" + flregex;
        boolean longest = (flags & Pattern.LONGEST_MATCH) != 0;
        boolean disableUnicodeGroups = (flags & Pattern.DISABLE_UNICODE_GROUPS) != 0;
        boolean deferWhole = (flags & Pattern.DEFER_WHOLE_REJECTION) != 0;
        final UnicodeDataProvider prov = provider != null ? provider : UnicodeProviders.get();
        final String fl = flregex;
        final io.github.jemmix.tdfa.core.CompileObserver obs = observer != null
                ? observer : io.github.jemmix.tdfa.core.CompileObserver.NONE;
        try {
            // One CPU ledger for the WHOLE compile: the front-end parse/TNFA
            // build and every shipped ladder attempt (succeeded unpruned
            // whole, pruned find, anchored re-parse + determinize) debit
            // the same pool, so a single Pattern.compile's shipped work
            // stays within the one tdfa.budget.compile.compute budget.
            // (The unpruned whole attempt itself is a fraction-capped
            // probe, charged only on success — see SingleCompile.)
            io.github.jemmix.tdfa.tdfa.WorkMeter ledger =
                    new io.github.jemmix.tdfa.tdfa.WorkMeter(
                            io.github.jemmix.tdfa.tdfa.Budgets.compileComputeTicks());
            Tnfa nfa = Tnfa.compile(fl, disableUnicodeGroups, false, prov, obs, ledger);

            // Single-compile ladder (one brain, every tier — core.SingleCompile):
            // whole artifact first (cut-free, work-bounded), find shares it
            // unless the pike cut provably matters; over budget the anchored
            // artifact is attempted eagerly, and a second rejection FAILS
            // compile() by default — or, opted in via DEFER_WHOLE_REJECTION,
            // is RECORDED and rethrown by every whole call (compile()
            // acceptance then stays the find artifact's alone). NOTHING ever
            // compiles at match time (no-lazy-compiles rule).
            io.github.jemmix.tdfa.core.SingleCompile.Artifacts art =
                    io.github.jemmix.tdfa.core.SingleCompile.artifacts(nfa, longest, obs, ledger);
            Tdfa findTdfa = art.find;
            int ps = findTdfa.stateCount();
            // Per-pattern runtime RAM split: a pattern keeping a SECOND
            // engine (dedicated whole/anchored runner) halves each engine's
            // lazy-memo budget so the pattern's combined memos stay within
            // one tdfa.budget.runtime.memory.
            long findMemoBudget = art.shared()
                    ? io.github.jemmix.tdfa.tdfa.Budgets.runtimeMemoryBytes()
                    : io.github.jemmix.tdfa.tdfa.Budgets.runtimeMemoryBytes() / 2;

            if (vmSwitched()) {
                obs.note("engine", "shared-interpreter (tdfa.engine=VM)");
                RegexEngine eng = new TdfaRunner(findTdfa, findMemoBudget);
                return new TDFAPattern(regex, flags, ps, eng,
                        whole(fl, disableUnicodeGroups, longest, deferWhole, prov, regex, art, eng, ledger), provider);
            }

            if (factory != null) {
                long t0 = System.nanoTime();
                RegexEngine eng = factory.create(findTdfa);
                // One factory call (the find engine): whole matching runs the
                // facade's own whole engine — a custom engine's matchWhole is
                // the interface default (match(input,0)), whole-exact only
                // over anchored artifacts, so it cannot consume the shared
                // unpruned artifact. Over budget, whole() eagerly compiles
                // the anchored artifact, or fails compile() (records its
                // rejection under DEFER_WHOLE_REJECTION). A BYO engine's own
                // memory is outside the facade's runtime-budget accounting
                // (the memo split applies to the facade's own runners).
                RegexEngine whole = whole(fl, disableUnicodeGroups, longest, deferWhole, prov, regex, art, eng, ledger);
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
                gen = io.github.jemmix.tdfa.asm.TdfaAsmBackend.generate(findTdfa, findMemoBudget);
            } catch (RuntimeException | LinkageError genFailure) {
                if (Boolean.getBoolean("tdfa.gen.debug")) genFailure.printStackTrace();
                obs.note("engine", "shared-interpreter (engine emission failed)");
                RegexEngine eng = new TdfaRunner(findTdfa, findMemoBudget);
                return new TDFAPattern(regex, flags, ps, eng, whole(fl, disableUnicodeGroups, longest, deferWhole, prov, regex, art, eng, ledger), provider);
            }
            obs.stage(io.github.jemmix.tdfa.core.CompileObserver.Stage.ENGINE,
                    System.nanoTime() - t1, 0);
            try {
                Pattern p = (Pattern) io.github.jemmix.tdfa.asm.ShellEmitter.emit(
                        new io.github.jemmix.tdfa.asm.ShellEmitter.Spec(
                                regex, flags, ps, gen.engine(),
                                whole(fl, disableUnicodeGroups, longest, deferWhole,
                                        prov, regex, art, gen.engine(), ledger),
                                gen.owner(), provider));
                obs.note("engine", "generated");
                return p;
            } catch (RuntimeException | LinkageError ex) {
                if (Boolean.getBoolean("tdfa.gen.debug")) ex.printStackTrace();
                obs.note("engine", "shared-interpreter (shell emission failed)");
                RegexEngine eng = new TdfaRunner(findTdfa, findMemoBudget);
                return new TDFAPattern(regex, flags, ps, eng, whole(fl, disableUnicodeGroups, longest, deferWhole, prov, regex, art, eng, ledger), provider);
            }
        } catch (RuntimeException e) {
            throw io.github.jemmix.tdfa.core.CompiledRegex.translate(e, regex);
        }
    }

    private static final int VALID_FLAGS = Pattern.CASE_INSENSITIVE | Pattern.DOTALL
            | Pattern.MULTILINE | Pattern.DISABLE_UNICODE_GROUPS | Pattern.LONGEST_MATCH
            | Pattern.UNICODE_CHARACTER_CLASS | Pattern.DEFER_WHOLE_REJECTION;

    /**
     * Facade wrapper over the shared single-compile whole resolver (core
     * {@code SingleCompile} — one brain, every tier): the find engine itself
     * when the artifacts are shared, else a dedicated interpreter over the
     * whole TDFA, else the over-budget ladder (eagerly compiled anchored
     * artifact, or — opted in via {@code DEFER_WHOLE_REJECTION} only — the
     * recorded rejection; the default FAILS compile()). The anchored build
     * re-parses the FLAG-PREFIXED regex ({@code fl}); rejections translate
     * with the bare user regex for messages. Everything runs inside
     * {@code compile()}.
     */
    private static RegexEngine whole(String fl, boolean disableUnicodeGroups, boolean longest,
                                     boolean deferWhole, UnicodeDataProvider prov, String regex,
                                     io.github.jemmix.tdfa.core.SingleCompile.Artifacts art,
                                     RegexEngine findEngine,
                                     io.github.jemmix.tdfa.tdfa.WorkMeter ledger) {
        return io.github.jemmix.tdfa.core.SingleCompile.wholeEngine(
                art, findEngine, fl, regex, disableUnicodeGroups, longest, deferWhole, prov, ledger);
    }

    /** {@code -Dtdfa.engine=VM}: global no-codegen switch, read per compile. */
    private static boolean vmSwitched() {
        return "VM".equalsIgnoreCase(System.getProperty("tdfa.engine"));
    }
}
