package io.github.jemmix.tdfa.tdfa;

import io.github.jemmix.tdfa.Regex;
import io.github.jemmix.tdfa.tnfa.Tnfa;
import io.github.jemmix.tdfa.vm.MatchResult;

import java.util.Arrays;

/**
 * Executes a compiled {@link Tdfa} against an input char sequence using the flat packed arrays
 * (stateRangeInfo, stateFinalInfo, ranges, ops).
 *
 * Tier A optimizations:
 *   - A1: merged target+ops lookup (single linear scan; no separate binary searches)
 *   - A2: lazy accept snapshot (no regs.clone() per accept-state visit)
 *   - A3: String specialization (fast path for input instanceof String)
 *   - A4: flat packed arrays (no per-state inner-array dereferences)
 *
 * Tier A.5 (post-JIT-diagnostic):
 *   - Split runString into smaller methods so HotSpot can inline the hot path
 *     (runString was 468 bytes — exceeded the ~325-byte inline budget).
 */
public final class TdfaRunner implements Regex.Engine {
    private final Tdfa tdfa;
    private final int[] stateRangeInfo;
    private final int[] stateFinalInfo;
    private final int[] ranges;
    private final int[] ops;
    private final int regSize;

    public TdfaRunner(Tnfa nfa) {
        this.tdfa = Tdfa.compile(nfa);
        this.stateRangeInfo = tdfa.stateRangeInfo;
        this.stateFinalInfo = tdfa.stateFinalInfo;
        this.ranges = tdfa.ranges;
        this.ops = tdfa.ops;
        this.regSize = tdfa.registerCount;
    }

    public Tdfa tdfa() { return tdfa; }

    // ============ public API ============

    @Override public boolean matches(CharSequence input) {
        if (input instanceof String) return runStringAnchored((String) input) >= 0;
        return runGeneric(input, 0, input.length(), true) != null;
    }

    @Override public boolean find(CharSequence input) {
        return runHolder(input, 0, input.length(), false) != null;
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

    // ============ anchored String fast path (smallest possible method) ============

    /** Returns lastAcceptPos on match, -1 on no match. No MatchHolder allocation. */
    private int runStringAnchored(String input) {
        final int[] sri = this.stateRangeInfo;
        final int[] sfi = this.stateFinalInfo;
        final int[] rg = this.ranges;
        final int[] op = this.ops;
        final int to = input.length();

        int[] regs = new int[regSize];
        Arrays.fill(regs, -1);
        int state = tdfa.startState;
        int lastAcceptPos = -1, lastAcceptState = -1;
        boolean haveAccept = false;
        int pos = 0;

        loop:
        for (; ; ) {
            if ((sfi[state] & 1) != 0) {
                lastAcceptPos = pos;
                lastAcceptState = state;
                haveAccept = true;
            }
            if (pos >= to) break;
            char c = input.charAt(pos);
            int meta = sri[state];
            int base = meta >>> 8;
            int count = meta & 0xFF;
            for (int i = 0; i < count; i++) {
                int o = (base + i) << 2;
                if (c >= rg[o] && c <= rg[o + 1]) {
                    int target = rg[o + 2];
                    if (target < 0) break loop;
                    int opsOff = rg[o + 3];
                    if (opsOff != 0) applyOpsInline(op, opsOff, regs, pos);
                    state = target;
                    pos++;
                    continue loop;
                }
            }
            break;
        }

        if (!haveAccept || lastAcceptPos != to) return -1;
        return lastAcceptPos;  // boolean caller doesn't need finalRegops
    }

    // ============ generic CharSequence path (used for find() and non-String) ============

    private MatchHolder runHolder(CharSequence input, int from, int to, boolean anchored) {
        if (input instanceof String) return runStringFind((String) input, from, to, anchored);
        return runGeneric(input, from, to, anchored);
    }

    private MatchHolder runStringFind(String input, int from, int to, boolean anchored) {
        int startSearch = from;
        while (true) {
            int res = runStringMatchFrom(input, startSearch, to);
            if (res >= 0) {
                // We need the registers too; re-run with extraction.
                return runAndExtract(input, startSearch, to, anchored);
            }
            if (anchored) return null;
            startSearch++;
            if (startSearch > to) return null;
        }
    }

    /** Returns lastAcceptPos on match, -1 on no match (no register extraction). */
    private int runStringMatchFrom(String input, int from, int to) {
        final int[] sri = this.stateRangeInfo;
        final int[] sfi = this.stateFinalInfo;
        int state = tdfa.startState;
        int lastAcceptPos = -1;
        boolean haveAccept = false;
        int pos = from;

        loop:
        for (; ; ) {
            if ((sfi[state] & 1) != 0) {
                lastAcceptPos = pos;
                haveAccept = true;
            }
            if (pos >= to) break;
            char c = input.charAt(pos);
            int meta = sri[state];
            int base = meta >>> 8;
            int count = meta & 0xFF;
            for (int i = 0; i < count; i++) {
                int o = (base + i) << 2;
                if (c >= ranges[o] && c <= ranges[o + 1]) {
                    int target = ranges[o + 2];
                    if (target < 0) break loop;
                    state = target;
                    pos++;
                    continue loop;
                }
            }
            break;
        }
        return haveAccept ? lastAcceptPos : -1;
    }

    /** Re-runs from `from` and extracts registers + applies finalOps. */
    private MatchHolder runAndExtract(String input, int from, int to, boolean anchored) {
        final int[] sri = this.stateRangeInfo;
        final int[] sfi = this.stateFinalInfo;
        int[] regs = new int[regSize];
        Arrays.fill(regs, -1);
        int state = tdfa.startState;
        int lastAcceptPos = -1, lastAcceptState = -1;
        boolean haveAccept = false;
        int pos = from;

        loop:
        for (; ; ) {
            if ((sfi[state] & 1) != 0) {
                lastAcceptPos = pos;
                lastAcceptState = state;
                haveAccept = true;
            }
            if (pos >= to) break;
            char c = input.charAt(pos);
            int meta = sri[state];
            int base = meta >>> 8;
            int count = meta & 0xFF;
            for (int i = 0; i < count; i++) {
                int o = (base + i) << 2;
                if (c >= ranges[o] && c <= ranges[o + 1]) {
                    int target = ranges[o + 2];
                    if (target < 0) break loop;
                    int opsOff = ranges[o + 3];
                    if (opsOff != 0) applyOpsInline(ops, opsOff, regs, pos);
                    state = target;
                    pos++;
                    continue loop;
                }
            }
            break;
        }
        if (!haveAccept) return null;
        if (anchored && lastAcceptPos != to) return null;
        int[] r = regs.clone();
        int opsOff = sfi[lastAcceptState] >>> 1;
        if (opsOff != 0) applyOpsInline(ops, opsOff, r, lastAcceptPos);
        return new MatchHolder(from, lastAcceptPos, r);
    }

    /** Slow path for arbitrary CharSequence (StringBuilder, etc.). */
    private MatchHolder runGeneric(CharSequence input, int from, int to, boolean anchored) {
        int startSearch = from;
        while (true) {
            int[] regs = new int[regSize];
            Arrays.fill(regs, -1);
            int state = tdfa.startState;
            int lastAcceptPos = -1, lastAcceptState = -1;
            boolean haveAccept = false;
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
                        if (opsOff != 0) applyOpsInline(ops, opsOff, regs, pos);
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
                if (opsOff != 0) applyOpsInline(ops, opsOff, r, lastAcceptPos);
                return new MatchHolder(startSearch, lastAcceptPos, r);
            }
            if (anchored) return null;
            startSearch++;
            if (startSearch > to) return null;
        }
    }

    /** Apply a flat-ops block. Small enough to be inlined by HotSpot. */
    private static void applyOpsInline(int[] ops, int opsOff, int[] regs, int pos) {
        for (int j = opsOff; ; j += 3) {
            int op = ops[j];
            if (op == Tdfa.OP_END) return;
            int dst = ops[j + 1];
            if (op == Tdfa.OP_SET_POS) regs[dst] = pos;
            else if (op == Tdfa.OP_COPY) regs[dst] = regs[ops[j + 2]];
            else regs[dst] = -1;  // OP_SET_NIL
        }
    }
}
