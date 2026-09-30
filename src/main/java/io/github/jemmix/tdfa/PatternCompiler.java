package io.github.jemmix.tdfa;

import io.github.jemmix.tdfa.asm.ShellEmitter;
import io.github.jemmix.tdfa.asm.TdfaAsmBackend;
import io.github.jemmix.tdfa.core.CompileObserver;
import io.github.jemmix.tdfa.core.PatternSyntaxException;
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
 * Everything builds inside {@code compile()}; any budget rejection fails
 * the compile as a translated
 * {@link io.github.jemmix.tdfa.core.PatternSyntaxException} — including
 * the end-of-compile execution-RAM check (retained artifact tables,
 * side table included, must fit {@code tdfa.budget.runtime.memory}; the
 * lazy-memo allowances draw from the residual — "if it compiles, it
 * will execute within budget", see {@link Budgets}).
 */
final class PatternCompiler {

    private static final int VALID_FLAGS = Pattern.CASE_INSENSITIVE | Pattern.DOTALL | Pattern.MULTILINE
        | Pattern.DISABLE_UNICODE_GROUPS | Pattern.LONGEST_MATCH | Pattern.UNICODE_CHARACTER_CLASS;

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
                "Flags should only be a combination of MULTILINE, DOTALL, CASE_INSENSITIVE, DISABLE_UNICODE_GROUPS, LONGEST_MATCH, UNICODE_CHARACTER_CLASS");
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
        UnicodeDataProvider prov = provider != null ? provider : UnicodeProviders.get();
        CompileObserver obs = observer != null ? observer : CompileObserver.NONE;
        try {
            // One CPU ledger for the whole compile: the front-end and the
            // find determinization with its partial-whole side table (when
            // the pike cut bites) all debit the same pool.
            WorkMeter ledger = new WorkMeter(Budgets.compileComputeTicks());
            Tnfa nfa = Tnfa.compile(fl, disableUnicodeGroups, false, prov, obs, ledger);
            Tdfa find = Tdfa.compileWithWholeSide(nfa, longest, obs, ledger.fork(0));
            // The side was requested: not exact means it was abandoned —
            // the whole surface did not build. Accept-only-what-ships
            // (2026-09-15 contract): reject here rather than attempting a
            // doomed cut-free second determinization (it would redo the
            // primary prefix PLUS the divergence that just exhausted the
            // side's half of the remaining ticks — it can never fit).
            if (!find.wholeWalkExact()) {
                throw new IllegalStateException("pattern too large: whole-match divergence exceeds the compile"
                    + " budget (partial-whole side abandoned) — raise -D" + Budgets.COMPILE_COMPUTE_PROP + " / -D"
                    + Budgets.COMPILE_MEMORY_PROP + " if you need this pattern");
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
                throw new IllegalStateException("pattern too large: retained execution RAM (flat artifact tables "
                    + retained + " B) exceeds the runtime memory budget (" + budget + " B) — raise -D"
                    + Budgets.RUNTIME_MEMORY_PROP);
            }
            obs.note("runtimeFootprint", "retained " + retained + " B, memo allowance " + (budget - retained) + " B"
                + ", budget " + budget + " B");
            long memoBudget = Math.max(1, Budgets.runtimeMemoAllowance(retained));

            // One engine translation of the one artifact, whole-capable
            // through the same engine (a native runner or generated class
            // over the artifact; a foreign factory engine that isn't
            // whole-capable falls back to a runner, so matches() never
            // depends on a third-party whole walk).
            long t0 = System.nanoTime();
            RegexEngine eng = engineOf(find, factory, memoBudget);
            WholeEngine wholeEng = wholeOf(eng, find, memoBudget);
            obs.stage(CompileObserver.Stage.ENGINE, System.nanoTime() - t0, 0);

            if (vmSwitched()) {
                obs.note("engine", "shared-interpreter (tdfa.engine=VM)");
                return new TDFAPattern(regex, flags, ps, eng, wholeEng, provider);
            }

            // Shell around the engines — concrete-typed for generated ones
            // (the engine's own class names the shell's field type and
            // classes). A shell emission problem degrades to the shared
            // Pattern implementation with the same engines.
            try {
                String owner = factory == null ? eng.getClass().getName().replace('.', '/') : null;
                Pattern p = (Pattern) ShellEmitter
                    .emit(new ShellEmitter.Spec(regex, flags, ps, eng, wholeEng, owner, provider));
                obs.note("engine", factory == null ? "generated" : "byo-shell");
                return p;
            } catch (RuntimeException ex) {
                if (Boolean.getBoolean("tdfa.gen.debug")) {
                    ex.printStackTrace();
                }
                obs.note("engine",
                    factory == null ? "shared (shell emission failed)" : "shared (byo-shell emission failed)");
                return new TDFAPattern(regex, flags, ps, eng, wholeEng, provider);
            }
        } catch (RuntimeException e) {
            throw PatternSyntaxException.translate(e, regex);
        }
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
     * the shared interpreter under {@code -Dtdfa.engine=VM}, the custom
     * factory's engine, or a generated per-pattern class. Emission failures
     * (IllegalStateException, LinkageError from broken generated bytecode)
     * propagate — they are bugs, not shapes to route around.
     */
    private static RegexEngine engineOf(Tdfa tdfa, RegexEngineFactory factory, long memoBudget) {
        if (vmSwitched()) {
            return new TdfaRunner(tdfa, memoBudget);
        }
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
