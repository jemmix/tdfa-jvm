package io.github.jemmix.tdfa.core.determinize;

import java.util.Arrays;

/**
 * Subset-construction config: TNFA state + registers + history ids + assertion/priority context.
 */
final class Config {
    final int state;
    final int[] regs;
    /**
     * Hash-consed history ids (HistTable): h = parent closure's l,
     * carried across the symbol step; l = this closure's accumulated
     * ε-history. Id 0 = empty sequence.
     */
    final int h;

    final int l;
    /**
     * Worst (highest) priority ε-edge taken to reach this config in the closure.
     * Seed configs carry pri=0 (no edges taken). Always 0 in POSIX mode (unused).
     * In Perl mode, used to suppress lower-priority paths past an accepting config.
     */
    final int pri;
    /**
     * Zero-width assertion mask accumulated during the ε-closure that produced this config.
     * Reset to 0 by {@link TdfaCompiler#stepOnSymbol}.
     */
    int emptyMask;
    /**
     * Possessive-fork arrival bits (docs/possessive-quantifiers.md): bit
     * {@code role} set when the closure traversed a fork ε-edge with that
     * role ((forkId << 1) | rank — BODY entry vs EXIT). Lives exactly one
     * closure: reset to 0 by the symbol step (the fork choice resolves at
     * the choice kernel; downstream kernels are survivors only).
     */
    int forkArr;

    Config(int state, int[] regs, int h, int l, int emptyMask) {
        this(state, regs, h, l, emptyMask, 0);
    }

    Config(int state, int[] regs, int h, int l, int emptyMask, int pri) {
        this.state = state;
        this.regs = regs;
        this.h = h;
        this.l = l;
        this.emptyMask = emptyMask;
        this.pri = pri;
    }

    Config(int state, int[] regs, int h, int l, int emptyMask, int pri, int forkArr) {
        this(state, regs, h, l, emptyMask, pri);
        this.forkArr = forkArr;
    }
}

/**
 * Canonical DFA state key: state ids + per-config (lookahead tags, emptyMask, pri).
 * Two states with same key are candidates for {@code map} (register bijection).
 * In Perl mode {@code includePri} adds per-config pri to the signature so that closures
 * whose suppression behaviour would differ are not merged.
 */
final class DfaStateKey {
    final int[] sig;
    final int hash;

    DfaStateKey(int[] sig) {
        this.sig = sig;
        this.hash = Arrays.hashCode(sig);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof DfaStateKey && Arrays.equals(sig, ((DfaStateKey) o).sig);
    }

    @Override
    public int hashCode() {
        return hash;
    }
}
