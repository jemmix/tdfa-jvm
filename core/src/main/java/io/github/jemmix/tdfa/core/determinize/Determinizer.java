package io.github.jemmix.tdfa.core.determinize;

import io.github.jemmix.tdfa.core.budget.Budgets;
import io.github.jemmix.tdfa.core.budget.WorkMeter;
import io.github.jemmix.tdfa.core.dfa.Tdfa;
import io.github.jemmix.tdfa.core.report.CompileObserver;
import io.github.jemmix.tdfa.core.tnfa.Tnfa;

/**
 * The compile pipeline seam: the artifact's construction entries. Each
 * entry runs determinization in {@link TdfaCompiler} (which owns the
 * kernels, the interning index and the closure scratch for exactly its
 * phase), then the register optimization / materialization / minimization
 * stages in {@link TdfaMaterializer} over the determinized value — one
 * meter spanning both halves. Lives here, one layer above the artifact
 * ({@code core.dfa}), so {@link Tdfa} stays a pure data model with no
 * upward edges into the pipeline that builds it.
 */
public final class Determinizer {

    private Determinizer() {
    }

    /**
     * Compile with Perl leftmost-first semantics (the ecosystem default).
     */
    public static Tdfa compile(Tnfa nfa) {
        return compile(nfa, false);
    }

    /**
     * Compiles a TNFA to a TDFA.
     *
     * @param longestMatch true for leftmost-longest, false for leftmost-first.
     */
    public static Tdfa compile(Tnfa nfa, boolean longestMatch) {
        return compile(nfa, longestMatch, null, new WorkMeter(Budgets.compileComputeTicks()));
    }

    /**
     * Compile with a transparency hook receiving stage timings/decisions (may be {@code null}).
     */
    public static Tdfa compile(Tnfa nfa, boolean longestMatch, CompileObserver observer) {
        return compile(nfa, longestMatch, observer, new WorkMeter(Budgets.compileComputeTicks()));
    }

    /**
     * Ledger variant of {@link #compile(Tnfa, boolean, CompileObserver)}:
     * the meter comes from the caller's compile ledger ({@link
     * WorkMeter#fork(long)}) — its budget is the per-attempt cap and its
     * ticks debit the shared pool, so a compile's determinization attempts
     * stay within one compile CPU budget.
     */
    public static Tdfa compile(Tnfa nfa, boolean longestMatch, CompileObserver observer, WorkMeter sharedMeter) {
        return compileWithMeter(nfa, longestMatch ? CompileMode.POSIX : CompileMode.PERL_PRUNED, observer, sharedMeter);
    }

    /**
     * Compile WITHOUT the Perl pike cut: transitions follow every alive
     * config, including lower-priority continuations past an accept, so the
     * artifact supports whole-input walks ({@code matchWhole}) — an accept
     * config alive at end-of-input is a full match even when a
     * higher-priority alternative accepted earlier (e.g. {@code (a|ab)} on
     * {@code "ab"}).
     *
     * <p>Use {@link Tdfa#pikeCutMatters()} on the PRUNED artifact of the same
     * NFA to decide whether this cut-free form is needed: false means the
     * pruned artifact is identical to this build and may serve whole
     * matching; true means whole matching needs this form.
     */
    public static Tdfa compileUnpruned(Tnfa nfa, boolean longestMatch, CompileObserver observer) {
        return compileUnpruned(nfa, longestMatch, observer, new WorkMeter(Budgets.compileComputeTicks()));
    }

    /**
     * Ledger variant of {@link #compileUnpruned(Tnfa, boolean, CompileObserver)}
     * (see {@link #compile(Tnfa, boolean, CompileObserver, WorkMeter)}).
     */
    public static Tdfa compileUnpruned(Tnfa nfa, boolean longestMatch, CompileObserver observer,
        WorkMeter sharedMeter) {
        // POSIX never pike-cuts (the cut is a leftmost-first concept), so an
        // unpruned POSIX compile steps identically to the pruned one —
        // both spell POSIX here.
        return compileWithMeter(nfa, longestMatch ? CompileMode.POSIX : CompileMode.UNPRUNED, observer, sharedMeter);
    }

    /**
     * Ledger variant of {@link #compile(Tnfa, boolean, CompileObserver)}
     * that ALSO records the partial-whole side table during the pruned
     * determinization: every pike-cut context contributes its UNCUT
     * transitions to the artifact's whole relation (see {@link Tdfa#wholeRanges()}),
     * so the ONE artifact serves find() and whole-input walks —
     * {@link Tdfa#wholeWalkExact()} then reports whether the side completed.
     * For find-only consumers ({@code io.github.jemmix.tdfa.core.compile.CompiledRegex}) the plain
     * compile is cheaper — the side table has no reader there.
     *
     * <p>The side exploration is bounded (child meter + the compile RAM
     * charge); on exhaustion it is abandoned cleanly and this returns a
     * plain pruned artifact with {@link Tdfa#wholeWalkExact()} == false — the
     * facade then REJECTS the compile (the whole surface did not build;
     * {@link #compileUnpruned} remains available to callers building
     * whole artifacts by hand, e.g. as test oracles).
     *
     * <p>With {@code longestMatch == true} this compiles
     * {@link CompileMode#POSIX} and records no side — not a loss: the pike
     * cut never fires under leftmost-longest, so whole walks on the
     * artifact are exact by construction ({@link Tdfa#wholeWalkExact()} is
     * unconditionally true). The old boolean-triplet encoding silently
     * rewrote the side away here; the mode is explicit now.
     */
    public static Tdfa compileWithWholeSide(Tnfa nfa, boolean longestMatch, CompileObserver observer,
        WorkMeter sharedMeter) {
        return compileWithMeter(nfa, longestMatch ? CompileMode.POSIX : CompileMode.PERL_WHOLE, observer, sharedMeter);
    }

    /**
     * The compile pipeline seam: determinization in {@link TdfaCompiler}
     * (which owns the kernels, the interning index and the closure scratch
     * for exactly its phase), then the register optimization /
     * materialization / minimization stages in {@link TdfaMaterializer}
     * over the determinized value. One meter spans both halves.
     */
    private static Tdfa compileWithMeter(Tnfa nfa, CompileMode mode, CompileObserver observer, WorkMeter meter) {
        // The public overloads accept a null observer ("no hook"); resolve
        // it to NONE once here so both pipeline halves below take non-null.
        CompileObserver obs = CompileObserver.effective(observer);
        DeterminizedDfa det = new TdfaCompiler(nfa, mode, meter).compile(obs);
        return TdfaMaterializer.finish(det, nfa, mode == CompileMode.POSIX, meter, obs);
    }

}
