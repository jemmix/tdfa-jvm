package io.github.jemmix.tdfa.tdfa;

/** Subset-construction config: TNFA state + registers + history ids + assertion/priority context. */
    final class Config {
        final int state;
        final int[] regs;
        /** Hash-consed history ids (HistTable): h = parent closure's l,
         *  carried across the symbol step; l = this closure's accumulated
         *  ε-history. Id 0 = empty sequence. */
        final int h;
        final int l;
        /** Zero-width assertion mask accumulated during the ε-closure that produced this config.
         *  Reset to 0 by {@link TdfaCompiler#stepOnSymbol}. */
        int emptyMask;
        /** Worst (highest) priority ε-edge taken to reach this config in the closure.
         *  Seed configs carry pri=0 (no edges taken). Always 0 in POSIX mode (unused).
         *  In Perl mode, used to suppress lower-priority paths past an accepting config. */
        final int pri;
        Config(int state, int[] regs, int h, int l, int emptyMask) {
            this(state, regs, h, l, emptyMask, 0);
        }
        Config(int state, int[] regs, int h, int l, int emptyMask, int pri) {
            this.state = state; this.regs = regs; this.h = h; this.l = l; this.emptyMask = emptyMask;
            this.pri = pri;
        }
    }

    /** Canonical DFA state key: state ids + per-config (lookahead tags, emptyMask, pri).
     *  Two states with same key are candidates for {@code map} (register bijection).
     *  In Perl mode {@code includePri} adds per-config pri to the signature so that closures
     *  whose suppression behaviour would differ are not merged. */
         final class DfaStateKey {
             final int[] sig;
             final int hash;
             DfaStateKey(int[] sig) { this.sig = sig; this.hash = java.util.Arrays.hashCode(sig); }
             @Override public boolean equals(Object o) {
                 return o instanceof DfaStateKey && java.util.Arrays.equals(sig, ((DfaStateKey) o).sig);
             }
         @Override public int hashCode() { return hash; }
     }
