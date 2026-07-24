package io.github.jemmix.tdfa.tdfa;

import io.github.jemmix.tdfa.Regex;
import io.github.jemmix.tdfa.tnfa.Tnfa;
import io.github.jemmix.tdfa.vm.MatchResult;

import java.util.Arrays;

/**
 * Executes a compiled {@link Tdfa} against an input char sequence using the flat packed arrays.
 *
 * Tier A optimizations + JIT-friendly shape (post-disassembly analysis):
 *   - Single load per char for accept+dispatch (stateMeta packs all three)
 *   - Skip int[] regs alloc when registerCount == 0
 *   - Lazy accept snapshot
 *   - String specialization
 */
public final class TdfaRunner implements Regex.Engine {
    private final Tdfa tdfa;
    private final int[] stateMeta;
    private final int[] stateFinalOpsOff;
    private final int[] ranges;
    private final int[] ops;
    private final int regSize;
    private final int startState;

    public TdfaRunner(Tnfa nfa) {
        this.tdfa = Tdfa.compile(nfa);
        this.stateMeta = tdfa.stateMeta;
        this.stateFinalOpsOff = tdfa.stateFinalOpsOff;
        this.ranges = tdfa.ranges;
        this.ops = tdfa.ops;
        this.regSize = tdfa.registerCount;
        this.startState = tdfa.startState;
    }

    public Tdfa tdfa() { return tdfa; }

    @Override public boolean matches(CharSequence input) {
        if (input instanceof String) return runStringAnchored((String) input) >= 0;
        return runGeneric(input, 0, input.length(), true) != null;
    }

    @Override public boolean find(CharSequence input) {
        if (input instanceof String) {
            int startSearch = 0;
            int to = input.length();
            while (startSearch <= to) {
                if (runStringMatchFrom((String) input, startSearch, to) >= 0) return true;
                startSearch++;
            }
            return false;
        }
        return runGeneric(input, 0, input.length(), false) != null;
    }

    @Override public MatchResult match(CharSequence input, int from) {
        MatchHolder h;
        if (input instanceof String) {
            h = runStringExtract((String) input, from, input.length());
        } else {
            h = runGeneric(input, from, input.length(), false);
        }
        return h == null ? null : new MatchResult(h.regs, tdfa.tagCount, tdfa.groupCount, h.matchStart, h.matchEnd);
    }

    public static final class MatchHolder {
        public final int matchStart, matchEnd;
        public final int[] regs;
        public MatchHolder(int s, int e, int[] r) { matchStart = s; matchEnd = e; regs = r; }
    }

    /** Anchored String match. Returns lastAcceptPos (>=0) on match, -1 on no match. No allocation. */
    private int runStringAnchored(String input) {
        final int[] sm = this.stateMeta;
        final int[] rg = this.ranges;
        final int[] op = this.ops;
        final int to = input.length();
        final int[] regs = regSize == 0 ? null : new int[regSize];
        if (regs != null) Arrays.fill(regs, -1);

        int state = startState;
        int lastAcceptPos = -1;
        boolean haveAccept = false;

        for (int pos = 0; pos <= to; pos++) {
            int meta = sm[state];
            if ((meta & 1) != 0) { haveAccept = true; lastAcceptPos = pos; }
            if (pos == to) break;
            char c = input.charAt(pos);
            int base = meta >>> 9;
            int count = (meta >>> 1) & 0xFF;
            boolean matched = false;
            for (int i = 0; i < count; i++) {
                int o = (base + i) << 2;
                if (c >= rg[o] && c <= rg[o + 1]) {
                    int target = rg[o + 2];
                    if (target < 0) break;  // dead
                    if (regs != null) {
                        int opsOff = rg[o + 3];
                        if (opsOff != 0) applyOps(op, opsOff, regs, pos);
                    }
                    state = target;
                    matched = true;
                    break;
                }
            }
            if (!matched) break;
        }
        return haveAccept && lastAcceptPos == to ? lastAcceptPos : -1;
    }

    /** String match from position, returns lastAcceptPos or -1. No allocation. */
    private int runStringMatchFrom(String input, int from, int to) {
        final int[] sm = this.stateMeta;
        final int[] rg = this.ranges;
        int state = startState;
        int lastAcceptPos = -1;
        boolean haveAccept = false;
        int pos = from;

        for (; ; pos++) {
            int meta = sm[state];
            if ((meta & 1) != 0) { haveAccept = true; lastAcceptPos = pos; }
            if (pos >= to) break;
            char c = input.charAt(pos);
            int base = meta >>> 9;
            int count = (meta >>> 1) & 0xFF;
            boolean matched = false;
            for (int i = 0; i < count; i++) {
                int o = (base + i) << 2;
                if (c >= rg[o] && c <= rg[o + 1]) {
                    int target = rg[o + 2];
                    if (target < 0) return haveAccept ? lastAcceptPos : -1;
                    state = target;
                    matched = true;
                    break;
                }
            }
            if (!matched) break;
        }
        return haveAccept ? lastAcceptPos : -1;
    }

    /** String match with register extraction (find + match path). */
    private MatchHolder runStringExtract(String input, int from, int to) {
        final int[] sm = this.stateMeta;
        final int[] rg = this.ranges;
        final int[] op = this.ops;
        final int[] sfo = this.stateFinalOpsOff;
        int startSearch = from;
        while (true) {
            final int[] regs = regSize == 0 ? null : new int[regSize];
            if (regs != null) Arrays.fill(regs, -1);
            int state = startState;
            int lastAcceptPos = -1, lastAcceptState = -1;
            boolean haveAccept = false;
            int pos = startSearch;

            loop:
            for (; ; pos++) {
                int meta = sm[state];
                if ((meta & 1) != 0) { lastAcceptPos = pos; lastAcceptState = state; haveAccept = true; }
                if (pos >= to) break;
                char c = input.charAt(pos);
                int base = meta >>> 9;
                int count = (meta >>> 1) & 0xFF;
                for (int i = 0; i < count; i++) {
                    int o = (base + i) << 2;
                    if (c >= rg[o] && c <= rg[o + 1]) {
                        int target = rg[o + 2];
                        if (target < 0) break loop;
                        if (regs != null) {
                            int opsOff = rg[o + 3];
                            if (opsOff != 0) applyOps(op, opsOff, regs, pos);
                        }
                        state = target;
                        continue loop;
                    }
                }
                break;  // dead
            }
            if (haveAccept) {
                int[] r = regs == null ? new int[0] : regs.clone();
                int foff = sfo[lastAcceptState];
                if (foff != 0 && regs != null) applyOps(op, foff, r, lastAcceptPos);
                return new MatchHolder(startSearch, lastAcceptPos, r);
            }
            startSearch++;
            if (startSearch > to) return null;
        }
    }

    private MatchHolder runGeneric(CharSequence input, int from, int to, boolean anchored) {
        int startSearch = from;
        while (true) {
            final int[] regs = regSize == 0 ? null : new int[regSize];
            if (regs != null) Arrays.fill(regs, -1);
            int state = startState;
            int lastAcceptPos = -1, lastAcceptState = -1;
            boolean haveAccept = false;
            int pos = startSearch;

            loop:
            for (; ; pos++) {
                int meta = stateMeta[state];
                if ((meta & 1) != 0) { lastAcceptPos = pos; lastAcceptState = state; haveAccept = true; }
                if (pos >= to) break;
                char c = input.charAt(pos);
                int base = meta >>> 9;
                int count = (meta >>> 1) & 0xFF;
                for (int i = 0; i < count; i++) {
                    int o = (base + i) << 2;
                    if (c >= ranges[o] && c <= ranges[o + 1]) {
                        int target = ranges[o + 2];
                        if (target < 0) break loop;
                        if (regs != null) {
                            int opsOff = ranges[o + 3];
                            if (opsOff != 0) applyOps(ops, opsOff, regs, pos);
                        }
                        state = target;
                        continue loop;
                    }
                }
                break;
            }
            if (haveAccept) {
                if (anchored && lastAcceptPos != to) return null;
                int[] r = regs == null ? new int[0] : regs.clone();
                int foff = stateFinalOpsOff[lastAcceptState];
                if (foff != 0 && regs != null) applyOps(ops, foff, r, lastAcceptPos);
                return new MatchHolder(startSearch, lastAcceptPos, r);
            }
            if (anchored) return null;
            startSearch++;
            if (startSearch > to) return null;
        }
    }

    private static void applyOps(int[] ops, int opsOff, int[] regs, int pos) {
        for (int j = opsOff; ; j += 3) {
            int op = ops[j];
            if (op == Tdfa.OP_END) return;
            int dst = ops[j + 1];
            if (op == Tdfa.OP_SET_POS) regs[dst] = pos;
            else if (op == Tdfa.OP_COPY) regs[dst] = regs[ops[j + 2]];
            else regs[dst] = -1;
        }
    }
}
