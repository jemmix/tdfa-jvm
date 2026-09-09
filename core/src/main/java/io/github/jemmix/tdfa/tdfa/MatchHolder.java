package io.github.jemmix.tdfa.tdfa;

import io.github.jemmix.tdfa.core.EmittedSurface;

/** Immutable match result carrier (start/end/registers) — extracted verbatim
 *  from TdfaRunner (2026-09 god-file split). */
    @EmittedSurface
    public final class MatchHolder {
        public final int matchStart, matchEnd;
        public final int[] regs;
        @EmittedSurface
        public MatchHolder(int s, int e, int[] r) { matchStart = s; matchEnd = e; regs = r; }
    }
