package io.github.jemmix.tdfa.core.determinize;

import io.github.jemmix.tdfa.core.dfa.DfaStateBuilder;
import io.github.jemmix.tdfa.core.tnfa.Tnfa;

import java.util.BitSet;
import java.util.List;

/**
 * Determinization output: the subset-constructed DFA before register
 * optimization, flat-array materialization and minimization (those run in
 * {@link TdfaMaterializer}).
 *
 * <p>Carries the per-state builder transition lists plus the per-state
 * tables derived from the kernels. The kernels themselves, the interning
 * index and the ε-closure scratch never leave {@link TdfaCompiler}: they
 * become unreachable together with that instance as soon as determinization
 * has produced this value.
 */
final class DeterminizedDfa {
    final int stateCount;
    final List<DfaStateBuilder> builders;
    /**
     * Ids of accepting states (bit s = state s accepts).
     */
    final BitSet accept;
    /**
     * [state] → intersection of the kernel configs' assertion masks: the
     * zero-width assertions that must hold at the position where the state
     * is entered.
     */
    final int[] entryMask;
    /**
     * [state] → intersection of the ACCEPT configs' assertion masks (0 if
     * none): the assertions that must hold for a match declared in this
     * state.
     */
    final int[] acceptMask;
    /**
     * [state * posFlagCells + posFlags] → 0 (stop on accept) or {@link Tdfa#NEVER_STOP}
     * (extend). Perl mode only; null in POSIX (no reader exists there).
     */
    final int[] stopOnAcceptMask;
    /**
     * Cells per state in the posFlags-indexed tables (64, or 128 when the
     * compile's NFA gates any edge on {@link Tnfa#FINAL_END} — the JUR-lane
     * plain-{@code $} bit). Derived once here; every downstream builder and
     * reader (materializer, minimizer, artifact, runner, ASM emitter) takes
     * it from the value chain so the stride is one decision, not many.
     */
    final int posFlagCells;
    /**
     * True iff the Perl pike cut deleted a steppable continuation below an
     * alive accept in some state — the condition under which whole-input
     * walks on this artifact can miss accepts (see Tdfa.pikeCutMatters()).
     * Always false for POSIX and cut-free compiles.
     */
    final boolean pikeCutMatters;
    /**
     * Size of the register universe at the end of determinization (the
     * global nextReg counter); the register optimization's initial count.
     */
    final int registerCount;
    /**
     * The partial-whole side table completed: every cut context's uncut
     * transitions were recorded (bounded exploration never exhausted),
     * so whole-input walks on THIS artifact are exact and the facade
     * must not determinize the cut-free second build. False when the
     * side was disabled/abandoned or never fired.
     */
    final boolean wholeSideComplete;

    DeterminizedDfa(int stateCount, List<DfaStateBuilder> builders, BitSet accept, int[] entryMask, int[] acceptMask,
        int[] stopOnAcceptMask, int posFlagCells, boolean pikeCutMatters, int registerCount,
        boolean wholeSideComplete) {
        this.stateCount = stateCount;
        this.builders = builders;
        this.accept = accept;
        this.entryMask = entryMask;
        this.acceptMask = acceptMask;
        this.stopOnAcceptMask = stopOnAcceptMask;
        this.posFlagCells = posFlagCells;
        this.pikeCutMatters = pikeCutMatters;
        this.registerCount = registerCount;
        this.wholeSideComplete = wholeSideComplete;
    }
}
