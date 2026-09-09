package io.github.jemmix.tdfa.tdfa;

/** Immutable match result carrier (start/end/registers) — extracted verbatim
 *  from TdfaRunner (2026-09 god-file split). */
    public final class MatchHolder {
        public final int matchStart, matchEnd;
        public final int[] regs;
        public MatchHolder(int s, int e, int[] r) { matchStart = s; matchEnd = e; regs = r; }
    }
