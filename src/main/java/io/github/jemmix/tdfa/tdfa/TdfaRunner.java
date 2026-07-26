package io.github.jemmix.tdfa.tdfa;

import io.github.jemmix.tdfa.Regex;
import io.github.jemmix.tdfa.tnfa.Tnfa;
import io.github.jemmix.tdfa.vm.MatchResult;

import java.util.Arrays;

/**
 * Executes a compiled {@link Tdfa} against an input char sequence using the flat packed arrays.
 *
 * Zero-width assertions ( ^ $ \A \z \b \B ) are encoded per-state (entryMask, acceptMask) and
 * per-transition (requiredMask in {@link Tdfa#ranges}). At every position we compute the set of
 * flags that hold (BEGIN_TEXT, END_TEXT, WORD_BOUNDARY, NO_WORD_BOUNDARY) and consult these
 * masks:
 *   - on entering a state, {@code stateEntryMask[state]} must be a subset of positionFlags;
 *   - on taking a transition, the range's {@code requiredMask} must be a subset of positionFlags;
 *   - on declaring a match, {@code stateAcceptMask[state]} must be a subset of positionFlags.
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
    private final int[] stateEntryMask;
    private final int[] stateAcceptMask;
    private final int[] ranges;
    private final int[] ops;
    private final int regSize;
    private final int startState;
    private final int startStateEntryMask;

    public TdfaRunner(Tnfa nfa) {
        this.tdfa = Tdfa.compile(nfa);
        this.stateMeta = tdfa.stateMeta;
        this.stateFinalOpsOff = tdfa.stateFinalOpsOff;
        this.stateEntryMask = tdfa.stateEntryMask;
        this.stateAcceptMask = tdfa.stateAcceptMask;
        this.ranges = tdfa.ranges;
        this.ops = tdfa.ops;
        this.regSize = tdfa.registerCount;
        this.startState = tdfa.startState;
        this.startStateEntryMask = tdfa.startStateEntryMask;
    }

    public Tdfa tdfa() { return tdfa; }

    @Override public boolean matches(CharSequence input) {
        if (input instanceof String) return runStringAnchored((String) input) >= 0;
        return runGeneric(input, 0, input.length(), true) != null;
    }

    @Override public boolean find(CharSequence input) {
        if (input instanceof String) {
            String s = (String) input;
            int len = s.length();
            // If the start state requires BEGIN_TEXT, only position 0 can match.
            int maxStart = ((startStateEntryMask & Tnfa.BEGIN_TEXT) != 0) ? 0 : len;
            for (int from = 0; from <= maxStart; from++) {
                int res = runStringMatchFrom(s, from, len);
                if (res >= 0) return true;
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

    /** String find with anchor enforcement and register extraction. */
    private MatchHolder runStringExtract(String input, int from, int to) {
        final int[] sm = this.stateMeta;
        final int[] rg = this.ranges;
        final int[] op = this.ops;
        final int[] sfo = this.stateFinalOpsOff;
        final int[] sem = this.stateEntryMask;
        final int[] sam = this.stateAcceptMask;
        int maxStart = ((startStateEntryMask & Tnfa.BEGIN_TEXT) != 0) ? 0 : to;
        for (int startSearch = from; startSearch <= maxStart; startSearch++) {
            final int[] regs = regSize == 0 ? null : new int[regSize];
            if (regs != null) Arrays.fill(regs, -1);
            int state = startState;
            int lastAcceptPos = -1, lastAcceptState = -1;
            boolean haveAccept = false;
            int pos = startSearch;

            // Check start state's entryMask before declaring any match at this position.
            if (!entryMaskOk(sem, state, input, pos, to)) {
                // start state itself can't be entered; no transitions, no accept.
                continue;
            }

            loop:
            for (; ; pos++) {
                int meta = sm[state];
                if ((meta & 1) != 0) {
                    // Accept state — check acceptMask at this position.
                    if ((positionFlags(input, pos, to) & sam[state]) == sam[state]) {
                        lastAcceptPos = pos; lastAcceptState = state; haveAccept = true;
                    }
                }
                if (pos >= to) break;
                char c = input.charAt(pos);
                int base = meta >>> 9;
                int count = (meta >>> 1) & 0xFF;
                int posFlags = positionFlags(input, pos, to);
                for (int i = 0; i < count; i++) {
                    int o = (base + i) * 5;
                    if (c >= rg[o] && c <= rg[o + 1]) {
                        int target = rg[o + 2];
                        if (target < 0) break loop;
                        int requiredMask = rg[o + 4];
                        if ((posFlags & requiredMask) != requiredMask) continue;  // assertion fails, try next range
                        if (regs != null) {
                            int opsOff = rg[o + 3];
                            if (opsOff != 0) applyOps(op, opsOff, regs, pos);
                        }
                        state = target;
                        // Destination entryMask will be checked at the top of the next iteration.
                        if (!entryMaskOk(sem, state, input, pos + 1, to)) {
                            // Destination cannot be entered — treat as dead.
                            break loop;
                        }
                        continue loop;
                    }
                }
                break;
            }
            if (haveAccept) {
                int[] r = regs == null ? new int[0] : regs.clone();
                int foff = sfo[lastAcceptState];
                if (foff != 0 && regs != null) applyOps(op, foff, r, lastAcceptPos);
                return new MatchHolder(startSearch, lastAcceptPos, r);
            }
        }
        return null;
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
        final int[] sem = this.stateEntryMask;
        final int[] sam = this.stateAcceptMask;
        final int to = input.length();
        final int[] regs = regSize == 0 ? null : new int[regSize];
        if (regs != null) Arrays.fill(regs, -1);

        int state = startState;
        int lastAcceptPos = -1;

        if (!entryMaskOk(sem, state, input, 0, to)) return -1;

        for (int pos = 0; pos <= to; pos++) {
            int meta = sm[state];
            if ((meta & 1) != 0) {
                if ((positionFlags(input, pos, to) & sam[state]) == sam[state]) {
                    lastAcceptPos = pos;
                }
            }
            if (pos == to) break;
            char c = input.charAt(pos);
            int base = meta >>> 9;
            int count = (meta >>> 1) & 0xFF;
            int posFlags = positionFlags(input, pos, to);
            boolean matched = false;
            for (int i = 0; i < count; i++) {
                int o = (base + i) * 5;
                if (c >= rg[o] && c <= rg[o + 1]) {
                    int target = rg[o + 2];
                    if (target < 0) break;  // dead
                    int requiredMask = rg[o + 4];
                    if ((posFlags & requiredMask) != requiredMask) continue;
                    if (regs != null) {
                        int opsOff = rg[o + 3];
                        if (opsOff != 0) applyOps(op, opsOff, regs, pos);
                    }
                    state = target;
                    if (!entryMaskOk(sem, state, input, pos + 1, to)) {
                        // destination dead — stop.
                        return lastAcceptPos == to ? lastAcceptPos : -1;
                    }
                    matched = true;
                    break;
                }
            }
            if (!matched) break;
        }
        return lastAcceptPos == to ? lastAcceptPos : -1;
    }

    /** String match from position, returns lastAcceptPos or -1. No allocation. */
    private int runStringMatchFrom(String input, int from, int to) {
        final int[] sm = this.stateMeta;
        final int[] rg = this.ranges;
        final int[] sem = this.stateEntryMask;
        final int[] sam = this.stateAcceptMask;
        int state = startState;
        int lastAcceptPos = -1;
        boolean haveAccept = false;
        int pos = from;

        if (!entryMaskOk(sem, state, input, pos, to)) return -1;

        for (; ; pos++) {
            int meta = sm[state];
            if ((meta & 1) != 0) {
                if ((positionFlags(input, pos, to) & sam[state]) == sam[state]) {
                    haveAccept = true; lastAcceptPos = pos;
                }
            }
            if (pos >= to) break;
            char c = input.charAt(pos);
            int base = meta >>> 9;
            int count = (meta >>> 1) & 0xFF;
            int posFlags = positionFlags(input, pos, to);
            boolean matched = false;
            for (int i = 0; i < count; i++) {
                int o = (base + i) * 5;
                if (c >= rg[o] && c <= rg[o + 1]) {
                    int target = rg[o + 2];
                    if (target < 0) return haveAccept ? lastAcceptPos : -1;
                    int requiredMask = rg[o + 4];
                    if ((posFlags & requiredMask) != requiredMask) continue;
                    state = target;
                    if (!entryMaskOk(sem, state, input, pos + 1, to)) {
                        return haveAccept ? lastAcceptPos : -1;
                    }
                    matched = true;
                    break;
                }
            }
            if (!matched) break;
        }
        return haveAccept ? lastAcceptPos : -1;
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

            if (!entryMaskOkCharSeq(stateEntryMask, state, input, pos, to)) {
                if (anchored) return null;
                if ((startStateEntryMask & Tnfa.BEGIN_TEXT) != 0) return null;
                startSearch++;
                if (startSearch > to) return null;
                continue;
            }

            loop:
            for (; ; pos++) {
                int meta = stateMeta[state];
                if ((meta & 1) != 0) {
                    if ((positionFlagsCS(input, pos, to) & stateAcceptMask[state]) == stateAcceptMask[state]) {
                        lastAcceptPos = pos; lastAcceptState = state; haveAccept = true;
                    }
                }
                if (pos >= to) break;
                char c = input.charAt(pos);
                int base = meta >>> 9;
                int count = (meta >>> 1) & 0xFF;
                int posFlags = positionFlagsCS(input, pos, to);
                for (int i = 0; i < count; i++) {
                    int o = (base + i) * 5;
                    if (c >= ranges[o] && c <= ranges[o + 1]) {
                        int target = ranges[o + 2];
                        if (target < 0) break loop;
                        int requiredMask = ranges[o + 4];
                        if ((posFlags & requiredMask) != requiredMask) continue;
                        if (regs != null) {
                            int opsOff = ranges[o + 3];
                            if (opsOff != 0) applyOps(ops, opsOff, regs, pos);
                        }
                        state = target;
                        if (!entryMaskOkCharSeq(stateEntryMask, state, input, pos + 1, to)) {
                            break loop;
                        }
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
            if ((startStateEntryMask & Tnfa.BEGIN_TEXT) != 0) return null;
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

    // ===== Zero-width assertion position-flag computation =====

    /** Compute the position-flags for `pos` in a String. Inline-friendly. */
    private static int positionFlags(String s, int pos, int len) {
        int flags = 0;
        if (pos == 0) flags |= Tnfa.BEGIN_TEXT;
        if (pos == len) flags |= Tnfa.END_TEXT;
        boolean prevWord = pos > 0 && isWordChar(s.charAt(pos - 1));
        boolean currWord = pos < len && isWordChar(s.charAt(pos));
        if (prevWord != currWord) flags |= Tnfa.WORD_BOUNDARY;
        else flags |= Tnfa.NO_WORD_BOUNDARY;
        return flags;
    }

    /** Same for a generic CharSequence. */
    private static int positionFlagsCS(CharSequence s, int pos, int len) {
        int flags = 0;
        if (pos == 0) flags |= Tnfa.BEGIN_TEXT;
        if (pos == len) flags |= Tnfa.END_TEXT;
        boolean prevWord = pos > 0 && isWordChar(s.charAt(pos - 1));
        boolean currWord = pos < len && isWordChar(s.charAt(pos));
        if (prevWord != currWord) flags |= Tnfa.WORD_BOUNDARY;
        else flags |= Tnfa.NO_WORD_BOUNDARY;
        return flags;
    }

    /** True iff stateEntryMask[state] is satisfied at `pos` in `input[0..len)`. */
    private static boolean entryMaskOk(int[] sem, int state, String input, int pos, int len) {
        int required = sem[state];
        if (required == 0) return true;
        return (positionFlags(input, pos, len) & required) == required;
    }

    private static boolean entryMaskOkCharSeq(int[] sem, int state, CharSequence input, int pos, int len) {
        int required = sem[state];
        if (required == 0) return true;
        return (positionFlagsCS(input, pos, len) & required) == required;
    }

    /** RE2's isWordRune: ASCII word chars [_0-9A-Za-z]. */
    private static boolean isWordChar(char c) {
        return c == '_' || (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }
}
