package io.github.jemmix.tdfa.tdfa;

import io.github.jemmix.tdfa.Regex;
import io.github.jemmix.tdfa.tnfa.Tnfa;
import io.github.jemmix.tdfa.vm.MatchResult;

import java.util.Arrays;

/**
 * Executes a compiled {@link Tdfa} against an input char sequence.
 * Runtime register file sized to {@link Tdfa#registerCount}.
 *
 * Semantics: anchored full-string match (use {@code matches}) or unanchored search
 * ({@code find}). Greedy longest-match: keeps stepping while transitions exist, falls back
 * to the last accepting state on dead-end.
 */
public final class TdfaRunner implements Regex.Engine {
    private final Tdfa tdfa;

    public TdfaRunner(Tnfa nfa) { this.tdfa = Tdfa.compile(nfa); }
    public Tdfa tdfa() { return tdfa; }

    @Override public boolean matches(CharSequence input) {
        return run(input, 0, input.length(), true) != null;
    }

    @Override public boolean find(CharSequence input) {
        return run(input, 0, input.length(), false) != null;
    }

    @Override public MatchResult match(CharSequence input, int from) {
        MatchHolder h = runHolder(input, from, input.length(), false);
        return h == null ? null : new MatchResult(h.regs, tdfa.tagCount, tdfa.groupCount, h.matchStart, h.matchEnd);
    }

    public static final class MatchHolder { public final int matchStart, matchEnd; public final int[] regs; public MatchHolder(int s, int e, int[] r) { matchStart=s; matchEnd=e; regs=r; } }

    private MatchHolder runHolder(CharSequence input, int from, int to, boolean anchored) {
        final int regSize = tdfa.registerCount;
        final int[] baseRegs = new int[regSize];
        Arrays.fill(baseRegs, -1);
        if (Boolean.getBoolean("tdfa.debug.runner")) {
            System.err.println("[tdfa/run] start anchored=" + anchored + " to=" + to + " regSize=" + regSize + " startState=" + tdfa.startState + " acceptStates=" + tdfa.acceptStates);
        }

        int startSearch = from;
        while (true) {
            int[] regs = baseRegs.clone();
            int state = tdfa.startState;
            int[] lastAcceptRegs = null;
            int lastAcceptPos = -1;
            int lastAcceptState = -1;
            int matchStart = startSearch;

            int pos = startSearch;
            boolean deadEnd = false;
            for (; pos <= to; pos++) {
                if (tdfa.acceptStates.get(state)) {
                    lastAcceptRegs = regs.clone();
                    lastAcceptPos = pos;
                    lastAcceptState = state;
                }
                if (pos == to) break;
                char c = input.charAt(pos);
                int next = tdfa.target(state, c);
                if (next < 0) { deadEnd = true; break; }
                int[] ops = tdfa.ops(state, c);
                if (ops != null && ops.length > 0) applyOps(ops, regs, pos);
                state = next;
            }
            // If we naturally reached end-of-input in an accept state, record it.
            // (Don't update on deadEnd — the accept check at the top of the loop already captured
            // the last accepting position before the dead transition.)
            if (!deadEnd && pos > to && tdfa.acceptStates.get(state)) {
                lastAcceptRegs = regs;
                lastAcceptPos = pos;
                lastAcceptState = state;
            }
            if (lastAcceptRegs != null) {
                if (anchored && lastAcceptPos != to) {
                    if (Boolean.getBoolean("tdfa.debug.runner")) {
                        System.err.println("[tdfa/run] anchored reject: lastAcceptPos=" + lastAcceptPos + " to=" + to);
                    }
                    // Anchored full-match requires consuming all input.
                    return null;
                }
                int[] fops = (lastAcceptState >= 0) ? tdfa.finalOps[lastAcceptState] : null;
                if (fops != null) applyOps(fops, lastAcceptRegs, lastAcceptPos);
                return new MatchHolder(matchStart, lastAcceptPos, lastAcceptRegs);
            }
            if (anchored) return null;
            startSearch++;
            if (startSearch > to) return null;
        }
    }

    private int[] run(CharSequence input, int from, int to, boolean anchored) {
        MatchHolder h = runHolder(input, from, to, anchored);
        return h == null ? null : new int[]{1}; // non-null sentinel
    }

    private static void applyOps(int[] ops, int[] regs, int currentPos) {
        for (int i = 0; i < ops.length; i += 3) {
            int op = ops[i];
            int dst = ops[i + 1];
            int src = ops[i + 2];
            switch (op) {
                case Tdfa.OP_SET_POS: regs[dst] = currentPos; break;
                case Tdfa.OP_SET_NIL: regs[dst] = -1; break;
                case Tdfa.OP_COPY:    regs[dst] = regs[src]; break;
                default: throw new IllegalStateException("op " + op);
            }
        }
    }
}
