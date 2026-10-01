package io.github.jemmix.tdfa;

import io.github.jemmix.tdfa.asm.ShellEmitter;
import io.github.jemmix.tdfa.asm.TdfaAsmBackend;
import io.github.jemmix.tdfa.core.CompileObserver;
import io.github.jemmix.tdfa.core.PatternTooLargeException;
import io.github.jemmix.tdfa.core.RegexEngine;
import io.github.jemmix.tdfa.core.RegexEngineFactory;
import io.github.jemmix.tdfa.core.WholeEngine;
import io.github.jemmix.tdfa.tdfa.Budgets;
import io.github.jemmix.tdfa.tdfa.Tdfa;
import io.github.jemmix.tdfa.tdfa.TdfaRunner;
import io.github.jemmix.tdfa.tdfa.WorkMeter;
import io.github.jemmix.tdfa.tnfa.Tnfa;
import io.github.jemmix.tdfa.unicode.UnicodeDataProvider;
import io.github.jemmix.tdfa.unicode.UnicodeProviders;

/**
 * {@link Pattern} compilation: fold the flags into an inline-flag prefix,
 * parse to a TNFA, determinize once, and translate the artifact to an
 * engine through one source ({@code -Dtdfa.engine=VM} interpreter, a
 * custom {@link RegexEngineFactory}, or per-pattern ASM generation).
 * The single artifact serves find() AND matches(): the pruned
 * determinization records a partial-whole side table at every pike-cut
 * point (the uncut continuations whole-input walks need). When that
 * bounded side exploration exhausts its budget, the compile FAILS with
 * the standard "pattern too large" family — accept-only-what-ships, the
 * same compile-time budget contract the find artifact always had; there
 * is no second cut-free determinization (its ledger arithmetic is doomed:
 * it would redo the primary prefix plus the divergence that just
 * exhausted the side's half of the remaining ticks, on the half left).
 *
 * <p>The find-only escape hatch ({@link Pattern#FIND_ONLY} /
 * {@link Pattern#compileFind}): consumers who never call whole-input
 * methods compile the SAME pipeline minus the whole machinery — the plain
 * pruned determinization, no side table recorded, no exactness gate, no
 * whole engine translation. Patterns whose whole divergence would reject
 * a full compile are accepted; the whole surface on the result refuses
 * with {@link UnsupportedOperationException} (see
 * {@link FindOnlyWholeEngine}).
 *
 * <p>Everything builds inside {@code compile()}; any budget rejection
 * fails the compile as a {@link PatternTooLargeException} — including
 * the end-of-compile execution-RAM check (retained artifact tables,
 * side table included, must fit {@code tdfa.budget.runtime.memory}; the
 * lazy-memo allowances draw from the residual — "if it compiles, it
 * will execute within budget", see {@link Budgets}). Malformed syntax
 * is a {@link io.github.jemmix.tdfa.core.PatternSyntaxException} raised
 * by the parser and passes through untouched; anything else is a bug
 * and propagates as itself.
 */
final class PatternCompiler {

    private static final int VALID_FLAGS = Pattern.CASE_INSENSITIVE | Pattern.DOTALL | Pattern.MULTILINE
        | Pattern.DISABLE_UNICODE_GROUPS | Pattern.LONGEST_MATCH | Pattern.UNICODE_CHARACTER_CLASS | Pattern.FIND_ONLY;

    private PatternCompiler() {
    }

    static Pattern compile(String regex, int flags, RegexEngineFactory factory, UnicodeDataProvider provider) {
        return compile(regex, flags, factory, provider, null);
    }

    static Pattern compile(String regex, int flags, RegexEngineFactory factory, UnicodeDataProvider provider,
        CompileObserver observer) {
        if (regex == null) {
            throw new NullPointerException("pattern is null");
        }
        if ((flags & ~VALID_FLAGS) != 0) {
            throw new IllegalArgumentException(
                "Flags should only be a combination of MULTILINE, DOTALL, CASE_INSENSITIVE, DISABLE_UNICODE_GROUPS,"
                    + " LONGEST_MATCH, UNICODE_CHARACTER_CLASS, FIND_ONLY");
        }
        String fl = regex;
        if ((flags & Pattern.CASE_INSENSITIVE) != 0) {
            fl = "(?i)" + fl;
        }
        if ((flags & Pattern.DOTALL) != 0) {
            fl = "(?s)" + fl;
        }
        if ((flags & Pattern.MULTILINE) != 0) {
            fl = "(?m)" + fl;
        }
        if ((flags & Pattern.UNICODE_CHARACTER_CLASS) != 0) {
            fl = "(?u)" + fl;
        }
        boolean longest = (flags & Pattern.LONGEST_MATCH) != 0;
        boolean disableUnicodeGroups = (flags & Pattern.DISABLE_UNICODE_GROUPS) != 0;
        boolean findOnly = (flags & Pattern.FIND_ONLY) != 0;
        UnicodeDataProvider prov = provider != null ? provider : UnicodeProviders.get();
        CompileObserver obs = observer != null ? observer : CompileObserver.NONE;
        // One CPU ledger for the whole compile. Full compiles: the
        // front-end and the find determinization with its partial-whole
        // side table (when the pike cut bites) all debit the same pool.
        // FIND_ONLY compiles: the plain pruned determinization — the
        // side table has no reader, so its exploration is not even
        // attempted and its budget is never drawn.
        WorkMeter ledger = new WorkMeter(Budgets.compileComputeTicks());
        Tnfa nfa = Tnfa.compile(fl, disableUnicodeGroups, false, prov, obs, ledger);
        Tdfa find = findOnly ? Tdfa.compile(nfa, longest, obs, ledger.fork(0))
            : Tdfa.compileWithWholeSide(nfa, longest, obs, ledger.fork(0));
        if (findOnly) {
            // No whole surface was requested: no exactness gate, no
            // whole engine — the find artifact is all this compile
            // ships and all it promised.
            obs.note("whole", "find-only (FIND_ONLY: no whole machinery attempted)");
        } else if (!find.wholeWalkExact()) {
            // The side was requested: not exact means it was abandoned —
            // the whole surface did not build. Accept-only-what-ships
            // (2026-09-15 contract): reject here rather than attempting
            // a doomed cut-free second determinization (it would redo
            // the primary prefix PLUS the divergence that just
            // exhausted the side's half of the remaining ticks — it
            // can never fit).
            throw new PatternTooLargeException("pattern too large: whole-match divergence exceeds the compile"
                + " budget (partial-whole side abandoned) — raise -D" + Budgets.COMPILE_COMPUTE_PROP + " / -D"
                + Budgets.COMPILE_MEMORY_PROP + " if you need this pattern, or compile find-shaped-only via"
                + " Pattern.compileFind / FIND_ONLY");
        }
        int ps = find.stateCount();

        // ===== end-of-compile execution-RAM check =====
        // The runtime RAM budget bounds the pattern's whole execution
        // footprint: retained artifact tables plus the lazy memo
        // allowances. The memos draw from the RESIDUAL after the
        // retained tables, so what the check itself must verify is
        // the retained side — tables beyond the budget fail the
        // compile with the standard clean "pattern too large"
        // rejection. Memo floors on a tiny residual are the
        // documented bounded carve-out (clamped, not rejected — see
        // Budgets.runtimeMemoAllowance). Engine-tier construction-time
        // tables (dispatch tiers, generated statics) stay outside the
        // budget per the r11 scope decision; BYO factory engines are
        // unaccountable by construction, but the artifact tables and
        // any native fallback runner still draw from this residual.
        long budget = Budgets.runtimeMemoryBytes();
        long retained = find.retainedTableBytes();
        if (retained >= budget) {
            throw new PatternTooLargeException("pattern too large: retained execution RAM (flat artifact tables "
                + retained + " B) exceeds the runtime memory budget (" + budget + " B) — raise -D"
                + Budgets.RUNTIME_MEMORY_PROP);
        }
        obs.note("runtimeFootprint",
            "retained " + retained + " B, memo allowance " + (budget - retained) + " B" + ", budget " + budget + " B");
        long memoBudget = Math.max(1, Budgets.runtimeMemoAllowance(retained));

        // One engine translation of the one artifact. Full compiles:
        // whole-capable through the same engine (a native runner or
        // generated class over the artifact; a foreign factory engine
        // that isn't whole-capable falls back to a runner, so matches()
        // never depends on a third-party whole walk). FIND_ONLY
        // compiles: the whole surface is the refusing non-engine — the
        // artifact carries no whole relation to walk.
        //
        // -Dtdfa.engine=VM bypasses the translation entirely (and the
        // shell below): the shared interpreter serves the find surface
        // (and the FIND_ONLY refusal, when set).
        if (vmSwitched()) {
            long t0 = System.nanoTime();
            TdfaRunner eng = new TdfaRunner(find, memoBudget);
            obs.stage(CompileObserver.Stage.ENGINE, System.nanoTime() - t0, 0);
            obs.note("engine", "shared-interpreter (tdfa.engine=VM)");
            WholeEngine wholeEng = findOnly ? new FindOnlyWholeEngine(regex) : eng;
            return new TDFAPattern(regex, flags, ps, eng, wholeEng, provider);
        }
        long t0 = System.nanoTime();
        RegexEngine eng = engineOf(find, factory, memoBudget);
        WholeEngine wholeEng = findOnly ? new FindOnlyWholeEngine(regex) : wholeOf(eng, find, memoBudget);
        obs.stage(CompileObserver.Stage.ENGINE, System.nanoTime() - t0, 0);

        // Shell around the engines — concrete-typed for generated ones
        // (the engine's own class names the shell's field type and
        // classes). Emission failures propagate like engine-emission
        // failures (see engineOf): they are bugs, not shapes to route
        // around.
        String owner = factory == null ? eng.getClass().getName().replace('.', '/') : null;
        Pattern p =
            (Pattern) ShellEmitter.emit(new ShellEmitter.Spec(regex, flags, ps, eng, wholeEng, owner, provider));
        obs.note("engine", factory == null ? "generated" : "byo-shell");
        return p;
    }

    /**
     * The whole-serving view of a translated artifact engine: used as-is
     * when the engine natively walks whole matches ({@code TdfaRunner},
     * generated classes), else a runner over the same artifact — the
     * whole-match walk is always a native one.
     */
    private static WholeEngine wholeOf(RegexEngine candidate, Tdfa artifact, long memoBudget) {
        return candidate instanceof WholeEngine ? (WholeEngine) candidate : new TdfaRunner(artifact, memoBudget);
    }

    /**
     * The compile's engine source, applied to every artifact of the compile:
     * the custom factory's engine, or a generated per-pattern class. Emission
     * failures (IllegalStateException, LinkageError from broken generated
     * bytecode) propagate — they are bugs, not shapes to route around.
     */
    private static RegexEngine engineOf(Tdfa tdfa, RegexEngineFactory factory, long memoBudget) {
        if (factory != null) {
            return factory.create(tdfa);
        }
        return TdfaAsmBackend.generate(tdfa, memoBudget);
    }

    /**
     * {@code -Dtdfa.engine=VM}: global no-codegen switch, read per compile.
     */
    private static boolean vmSwitched() {
        return "VM".equalsIgnoreCase(System.getProperty("tdfa.engine"));
    }
}
