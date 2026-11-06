package io.github.jemmix.tdfa.core.compile;

import io.github.jemmix.tdfa.core.budget.Budgets;
import io.github.jemmix.tdfa.core.budget.PatternTooLargeException;
import io.github.jemmix.tdfa.core.budget.WorkMeter;
import io.github.jemmix.tdfa.core.determinize.Determinizer;
import io.github.jemmix.tdfa.core.dfa.Tdfa;
import io.github.jemmix.tdfa.core.dfa.TdfaRunner;
import io.github.jemmix.tdfa.core.engine.RegexEngine;
import io.github.jemmix.tdfa.core.engine.WholeEngine;
import io.github.jemmix.tdfa.core.report.CompileObserver;
import io.github.jemmix.tdfa.core.tnfa.Semantics;
import io.github.jemmix.tdfa.core.tnfa.Tnfa;
import io.github.jemmix.tdfa.core.unicode.UnicodeDataProvider;

import java.util.function.Supplier;

/**
 * The compile machinery behind every {@code Pattern} facade: translate the
 * flag bits into an inline-flag prefix, parse to a TNFA, determinize once,
 * and translate the artifact to an engine through one source ({@code
 * -Dtdfa.engine=VM} interpreter, a custom {@link RegexEngineFactory}, or
 * per-pattern ASM generation via the passed {@link EngineBackend}). The
 * single artifact serves find() AND matches(): the pruned determinization
 * records a partial-whole side table at every pike-cut point (the uncut
 * continuations whole-input walks need). When that bounded side
 * exploration exhausts its budget, the compile FAILS with the standard
 * "pattern too large" family — accept-only-what-ships, the same
 * compile-time budget contract the find artifact always had; there is no
 * second cut-free determinization (its ledger arithmetic is doomed: it
 * would redo the primary prefix plus the divergence that just exhausted
 * the side's half of the remaining ticks, on the half left).
 *
 * <p>The find-only escape hatch ({@code FIND_ONLY} /
 * {@code compileFind}): consumers who never call whole-input methods
 * compile the SAME pipeline minus the whole machinery — the plain pruned
 * determinization, no side table recorded, no exactness gate, no whole
 * engine translation. Patterns whose whole divergence would reject a
 * full compile are accepted; the whole surface on the result refuses
 * with {@link UnsupportedOperationException} (see
 * {@link FindOnlyWholeEngine}).
 *
 * <p>Everything builds inside {@code compile()}; any budget rejection
 * fails the compile as a {@link PatternTooLargeException} — including
 * the end-of-compile execution-RAM check (retained artifact tables,
 * side table included, must fit {@code tdfa.budget.runtime.memory}; the
 * lazy-memo allowances draw from the residual — "if it compiles, it
 * will execute within budget", see {@link Budgets}). Malformed syntax
 * is a {@link io.github.jemmix.tdfa.core.parser.PatternSyntaxException}
 * raised by the parser and passes through untouched; anything else is
 * a bug and propagates as itself.
 *
 * <p>Public-but-internal: machinery tier ({@code core.compile}),
 * entered by the facades' {@code Lane} funnels only — not shipped API.
 */
public final class PatternCompiler {

    // ---- the shared flag-bit assignments ----
    // Each facade spells these constants publicly on its own Pattern
    // interface (with its own javadoc) and validates its own roster in
    // place before calling in; the machinery decodes by these values.
    // Bit values are this library's own — names and semantics mirror the
    // JDK flags they snapped onto, numerics do not. Both facades share
    // the assignments, so the same int round-trips both.

    /** Case insensitive matching (folds the facade's default universe). */
    public static final int CASE_INSENSITIVE = 1;

    /** Dot ({@code .}) matches all characters, including newline. */
    public static final int DOTALL = 2;

    /** Multiline matching ({@code ^}/{@code $} at line boundaries). */
    public static final int MULTILINE = 4;

    /** Reject {@code \p{...}} / {@code \P{...}} at compile time. */
    public static final int DISABLE_UNICODE_GROUPS = 8;

    /** Leftmost-longest (POSIX) matching. */
    public static final int LONGEST_MATCH = 16;

    /** Unicode-aware predefined classes (the jur reading; jur facade only). */
    public static final int UNICODE_CHARACTER_CLASS = 32;

    /** Compile for find-shaped matching only (whole-input methods refuse). */
    public static final int FIND_ONLY = 64;

    /** Multi-valued tags (BT22 &sect;3.1) — per-iteration group spans. */
    public static final int MULTI_VALUED_TAGS = 128;

    private PatternCompiler() {
    }

    /**
     * The one machinery entry. The facades' {@code Lane} funnels do the
     * facade-local work in place — flag-roster validation, the default
     * {@link Semantics} lane, the default Unicode source — and hand the
     * machinery resolved values: {@code semantics} is never {@code null}
     * (the funnel picked the facade's lane when the caller gave none),
     * {@code facade} is the calling facade's {@code Pattern} interface
     * (the interface generated shells implement, and the identity the
     * result carries for serialization and {@code equals}), and
     * {@code provider} may be {@code null} — {@code defaultProvider}
     * then serves the compile, and the {@code null} is what the result
     * retains, so a default-compiled pattern serializes without a
     * provider and recompiles against the READER's default (only an
     * explicitly pinned provider travels).
     */
    public static TDFAPattern compile(String regex, int flags, RegexEngineFactory factory, EngineBackend backend,
        Class<?> facade, UnicodeDataProvider provider, Supplier<UnicodeDataProvider> defaultProvider,
        CompileObserver observer, Semantics semantics) {
        if (regex == null) {
            throw new NullPointerException("pattern is null");
        }
        String fl = regex;
        if ((flags & CASE_INSENSITIVE) != 0) {
            fl = "(?i)" + fl;
        }
        if ((flags & DOTALL) != 0) {
            fl = "(?s)" + fl;
        }
        if ((flags & MULTILINE) != 0) {
            fl = "(?m)" + fl;
        }
        if ((flags & UNICODE_CHARACTER_CLASS) != 0) {
            fl = "(?u)" + fl;
        }
        boolean longest = (flags & LONGEST_MATCH) != 0;
        boolean disableUnicodeGroups = (flags & DISABLE_UNICODE_GROUPS) != 0;
        boolean findOnly = (flags & FIND_ONLY) != 0;
        boolean multiValued = (flags & MULTI_VALUED_TAGS) != 0;
        // Null knobs resolve to their defaults exactly once, here at the
        // single machinery entry: the facade's fold-universe tables (its
        // default-provider pass-through) and the no-op observer —
        // everything below takes both as non-null (the semantics and
        // roster were already resolved by the calling facade's funnel).
        UnicodeDataProvider prov = provider != null ? provider : defaultProvider.get();
        CompileObserver obs = CompileObserver.effective(observer);
        // One CPU ledger for the whole compile. Full compiles: the
        // front-end and the find determinization with its partial-whole
        // side table (when the pike cut bites) all debit the same pool.
        // FIND_ONLY compiles: the plain pruned determinization — the
        // side table has no reader, so its exploration is not even
        // attempted and its budget is never drawn.
        WorkMeter ledger = new WorkMeter(Budgets.compileComputeTicks());
        Tnfa nfa = multiValued ? Tnfa.compileMulti(fl, disableUnicodeGroups, semantics, prov, obs, ledger)
            : Tnfa.compile(fl, disableUnicodeGroups, false, false, semantics, prov, obs, ledger);
        Tdfa find = findOnly ? Determinizer.compile(nfa, longest, obs, ledger.fork(0))
            : Determinizer.compileWithWholeSide(nfa, longest, obs, ledger.fork(0));
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
            // exhausted the side's half of the remaining ticks — it can
            // never fit).
            throw new PatternTooLargeException("pattern too large: whole-match divergence exceeds the compile"
                + " budget (partial-whole side abandoned) — raise -D" + Budgets.COMPILE_COMPUTE_PROP + " / -D"
                + Budgets.COMPILE_MEMORY_PROP + " if you need this pattern, or compile find-shaped-only via"
                + " Pattern.compileFind / FIND_ONLY");
        }
        int ps = find.stateCount();

        checkRetainedFootprint(find, obs);

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
        // (and the FIND_ONLY refusal, when set). The calling facade
        // wraps the bare result into its own carrier.
        if (vmSwitched()) {
            long t0 = System.nanoTime();
            TdfaRunner eng = new TdfaRunner(find);
            obs.stage(CompileObserver.Stage.ENGINE, System.nanoTime() - t0, 0);
            obs.note("engine", "shared-interpreter (tdfa.engine=VM)");
            WholeEngine wholeEng = findOnly ? new FindOnlyWholeEngine(regex) : eng;
            return new TDFAPattern(regex, flags, ps, eng, wholeEng, provider, facade);
        }
        long t0 = System.nanoTime();
        RegexEngine eng = engineOf(find, factory, backend);
        WholeEngine wholeEng = findOnly ? new FindOnlyWholeEngine(regex) : wholeOf(eng, find);
        obs.stage(CompileObserver.Stage.ENGINE, System.nanoTime() - t0, 0);

        // Shell around the engines — concrete-typed for generated ones
        // (the engine's own class names the shell's field type and
        // classes), implementing the compile's facade interface.
        // Emission failures propagate like engine-emission
        // failures (see engineOf): they are bugs, not shapes to route
        // around). ShellSpec.of owns the generated-vs-byo wiring decision.
        TDFAPattern p =
            (TDFAPattern) backend.emit(ShellSpec.of(regex, flags, ps, eng, wholeEng, factory, provider, facade));
        obs.note("engine", factory == null ? "generated" : "byo-shell");
        return p;
    }

    /**
     * The whole-serving view of a translated artifact engine: used as-is
     * when the engine natively walks whole matches ({@code TdfaRunner},
     * generated classes), else a runner over the same artifact — the
     * whole-match walk is always a native one.
     */
    private static WholeEngine wholeOf(RegexEngine candidate, Tdfa artifact) {
        return candidate instanceof WholeEngine ? (WholeEngine) candidate : new TdfaRunner(artifact);
    }

    /**
     * The compile's engine source, applied to every artifact of the compile:
     * the custom factory's engine, or the passed backend's per-pattern
     * generated class (each facade wires the backend it ships — the ASM
     * one). Emission failures (IllegalStateException, LinkageError from
     * broken generated bytecode) propagate — they are bugs, not shapes
     * to route around.
     */
    private static RegexEngine engineOf(Tdfa tdfa, RegexEngineFactory factory, EngineBackend backend) {
        if (factory != null) {
            return factory.create(tdfa);
        }
        return backend.generate(tdfa);
    }

    /**
     * The end-of-compile execution-RAM check, shared by both public
     * compile tiers (the facades' {@code Pattern.compile} and the core
     * tier's {@link CompiledRegex#compile(String, CompileOptions)}): the
     * runtime RAM budget bounds the pattern's retained artifact tables —
     * tables alone beyond the budget fail the compile with the standard
     * clean "pattern too large" rejection. (The lazy memos that once drew
     * from the residual after the retained tables are deleted with
     * #97/#98; engine-tier construction-time tables (dispatch tiers,
     * generated statics) stay outside the budget per the r11 scope
     * decision; BYO factory engines are unaccountable by construction.)
     */
    static void checkRetainedFootprint(Tdfa find, CompileObserver obs) {
        long budget = Budgets.runtimeMemoryBytes();
        long retained = find.retainedTableBytes();
        if (retained >= budget) {
            throw new PatternTooLargeException("pattern too large: retained execution RAM (flat artifact tables "
                + retained + " B) exceeds the runtime memory budget (" + budget + " B) — raise -D"
                + Budgets.RUNTIME_MEMORY_PROP);
        }
        obs.note("runtimeFootprint", "retained " + retained + " B, budget " + budget + " B");
    }

    /**
     * {@code -Dtdfa.engine=VM}: global no-codegen switch, read per compile.
     */
    private static boolean vmSwitched() {
        return "VM".equalsIgnoreCase(System.getProperty("tdfa.engine"));
    }
}
