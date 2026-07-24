package io.github.jemmix.tdfa.tdfa;

import io.github.jemmix.tdfa.Regex;
import io.github.jemmix.tdfa.tnfa.Tnfa;
import io.github.jemmix.tdfa.vm.MatchResult;

import java.util.Arrays;

/**
 * Executes a compiled {@link Tdfa} against an input char sequence using the flat packed
 * arrays (stateRangeInfo, stateFinalInfo, ranges, ops).
 *
 * Tier A optimizations applied:
 *   - A1: merged target+ops lookup (single linear scan; no separate binary searches)
 *   - A2: lazy accept snapshot (no regs.clone() per accept-state visit; clone only on
 *         dead-end or end-of-input when we actually need the snapshot)
 *   - A3: String specialization (fast path for input instanceof String)
 *   - A4: flat packed arrays (no per-state inner-array dereferences)
 *
 * Semantics: anchored full-string match (use matches) or unanchored search (find).
 * Greedy longest-match: keeps stepping while transitions exist, falls back to the last
 * accepting state on dead-end.
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

    public static final class MatchHolder {
        public final int matchStart, matchEnd;
        public final int[] regs;
        public MatchHolder(int s, int e, int[] r) { matchStart = s; matchEnd = e; regs = r; }
    }

    /** A3: String fast path. */
    private MatchHolder runHolder(CharSequence input, int from, int to, boolean anchored) {
        if (input instanceof String) return runString((String) input, from, to, anchored);
        return runGeneric(input, from, to, anchored);
    }

    private MatchHolder runString(String input, int from, int to, boolean anchored) {
        final Tdfa t = this.tdfa;
        final int[] stateRangeInfo = t.stateRangeInfo;
        final int[] stateFinalInfo = t.stateFinalInfo;
        final int[] ranges = t.ranges;
        final int[] ops = t.ops;
        final int regSize = t.registerCount;

        int startSearch = from;
        while (true) {
            int[] regs = new int[regSize];
            Arrays.fill(regs, -1);
            int state = t.startState;
            int lastAcceptPos = -1, lastAcceptState = -1;
            boolean haveAccept = false;
            int matchStart = startSearch;
            int pos = startSearch;

            loop:
            for (; ; ) {
                if ((stateFinalInfo[state] & 1) != 0) {  // A2: cheap accept record, no clone
                    lastAcceptPos = pos;
                    lastAcceptState = state;
                    haveAccept = true;
                }
                if (pos >= to) break;
                char c = input.charAt(pos);
                int meta = stateRangeInfo[state];
                int base = meta >>> 8;
                int count = meta & 0xFF;
                for (int i = 0; i < count; i++) {
                    int o = (base + i) << 2;
                    if (c >= ranges[o] && c <= ranges[o + 1]) {
                        int target = ranges[o + 2];
                        if (target < 0) break loop;  // dead
                        int opsOff = ranges[o + 3];
                        if (opsOff != 0) {
                            for (int j = opsOff; ops[j] != Tdfa.OP_END; j += 3) {
                                int op = ops[j];
                                int dst = ops[j + 1];
                                if (op == Tdfa.OP_SET_POS) regs[dst] = pos;
                                else if (op == Tdfa.OP_COPY) regs[dst] = regs[ops[j + 2]];
                                else regs[dst] = -1;
                            }
                        }
                        state = target;
                        pos++;
                        continue loop;
                    }
                }
                break;  // no range matched
            }

            if (haveAccept) {
                if (anchored && lastAcceptPos != to) return null;
                // A2: materialize snapshot now by cloning regs
                int[] r = regs.clone();
                int opsOff = stateFinalInfo[lastAcceptState] >>> 1;
                if (opsOff != 0) {
                    for (int j = opsOff; ops[j] != Tdfa.OP_END; j += 3) {
                        int op = ops[j];
                        int dst = ops[j + 1];
                        if (op == Tdfa.OP_SET_POS) r[dst] = lastAcceptPos;
                        else if (op == Tdfa.OP_COPY) r[dst] = r[ops[j + 2]];
                        else r[dst] = -1;
                    }
                }
                return new MatchHolder(matchStart, lastAcceptPos, r);
            }
            if (anchored) return null;
            startSearch++;
            if (startSearch > to) return null;
        }
    }

    /** Same as runString but for arbitrary CharSequence. */
    private MatchHolder runGeneric(CharSequence input, int from, int to, boolean anchored) {
        final Tdfa t = this.tdfa;
        final int[] stateRangeInfo = t.stateRangeInfo;
        final int[] stateFinalInfo = t.stateFinalInfo;
        final int[] ranges = t.ranges;
        final int[] ops = t.ops;
        final int regSize = t.registerCount;

        int startSearch = from;
        while (true) {
            int[] regs = new int[regSize];
            Arrays.fill(regs, -1);
            int state = t.startState;
            int lastAcceptPos = -1, lastAcceptState = -1;
            boolean haveAccept = false;
            int matchStart = startSearch;
            int pos = startSearch;

            loop:
            for (; ; ) {
                if ((stateFinalInfo[state] & 1) != 0) {
                    lastAcceptPos = pos;
                    lastAcceptState = state;
                    haveAccept = true;
                }
                if (pos >= to) break;
                char c = input.charAt(pos);
                int meta = stateRangeInfo[state];
                int base = meta >>> 8;
                int count = meta & 0xFF;
                for (int i = 0; i < count; i++) {
                    int o = (base + i) << 2;
                    if (c >= ranges[o] && c <= ranges[o + 1]) {
                        int target = ranges[o + 2];
                        if (target < 0) break loop;
                        int opsOff = ranges[o + 3];
                        if (opsOff != 0) {
                            for (int j = opsOff; ops[j] != Tdfa.OP_END; j += 3) {
                                int op = ops[j];
                                int dst = ops[j + 1];
                                if (op == Tdfa.OP_SET_POS) regs[dst] = pos;
                                else if (op == Tdfa.OP_COPY) regs[dst] = regs[ops[j + 2]];
                                else regs[dst] = -1;
                            }
                        }
                        state = target;
                        pos++;
                        continue loop;
                    }
                }
                break;
            }

            if (haveAccept) {
                if (anchored && lastAcceptPos != to) return null;
                int[] r = regs.clone();
                int opsOff = stateFinalInfo[lastAcceptState] >>> 1;
                if (opsOff != 0) {
                    for (int j = opsOff; ops[j] != Tdfa.OP_END; j += 3) {
                        int op = ops[j];
                        int dst = ops[j + 1];
                        if (op == Tdfa.OP_SET_POS) r[dst] = lastAcceptPos;
                        else if (op == Tdfa.OP_COPY) r[dst] = r[ops[j + 2]];
                        else r[dst] = -1;
                    }
                }
                return new MatchHolder(matchStart, lastAcceptPos, r);
            }
            if (anchored) return null;
            startSearch++;
            if (startSearch > to) return null;
        }
    }

    private int[] run(CharSequence input, int from, int to, boolean anchored) {
        MatchHolder h = runHolder(input, from, to, anchored);
        return h == null ? null : new int[]{1};
    }
}
