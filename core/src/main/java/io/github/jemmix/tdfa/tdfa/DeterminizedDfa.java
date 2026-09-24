package io.github.jemmix.tdfa.tdfa;

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
     * [state * 64 + posFlags] → 0 (stop on accept) or {@link Tdfa#NEVER_STOP}
     * (extend). Perl mode only; null in POSIX (no reader exists there).
     */
    final int[] stopOnAcceptMask;
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

    DeterminizedDfa(int stateCount, List<DfaStateBuilder> builders, BitSet accept, int[] entryMask, int[] acceptMask,
        int[] stopOnAcceptMask, boolean pikeCutMatters, int registerCount) {
        this.stateCount = stateCount;
        this.builders = builders;
        this.accept = accept;
        this.entryMask = entryMask;
        this.acceptMask = acceptMask;
        this.stopOnAcceptMask = stopOnAcceptMask;
        this.pikeCutMatters = pikeCutMatters;
        this.registerCount = registerCount;
    }
}
