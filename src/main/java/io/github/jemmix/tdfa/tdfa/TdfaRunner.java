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
    private final boolean perlMode;
    private final int[] stopOnAcceptMask;
    private final boolean rangesDisjoint;
    private final int[][] asciiRangeIdx;

    public TdfaRunner(Tnfa nfa) {
        this(Tdfa.compile(nfa));
    }

    public TdfaRunner(Tdfa tdfa) {
        this.tdfa = tdfa;
        this.stateMeta = tdfa.stateMeta;
        this.stateFinalOpsOff = tdfa.stateFinalOpsOff;
        this.stateEntryMask = tdfa.stateEntryMask;
        this.stateAcceptMask = tdfa.stateAcceptMask;
        this.ranges = tdfa.ranges;
        this.ops = tdfa.ops;
        this.regSize = tdfa.registerCount;
        this.startState = tdfa.startState;
        this.startStateEntryMask = tdfa.startStateEntryMask;
        this.rangesDisjoint = checkRangesDisjoint(tdfa);
        this.asciiRangeIdx = rangesDisjoint ? buildAsciiRangeIdx(tdfa) : null;
        this.perlMode = tdfa.perlMode;
        this.stopOnAcceptMask = tdfa.stopOnAcceptMask;
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

            // Entry check for start state — inline
            {
                int entryReq = sem[state];
                if (entryReq != 0 && (positionFlags(input, pos, to) & entryReq) != entryReq) continue;
            }

            int posFlags = -1;
            loop:
            for (; ; pos++) {
                int meta = sm[state];
                if ((meta & 1) != 0) {
                    int acceptMask = sam[state];
                    if (acceptMask == 0) {
                        lastAcceptPos = pos; lastAcceptState = state; haveAccept = true;
                        if (perlMode) {
                            if (posFlags < 0) posFlags = positionFlags(input, pos, to);
                            int stopMask = stopOnAcceptMask[state * 16 + posFlags];
                            if (stopOnAccept(stopMask, posFlags)) break loop;
                        }
                    } else {
                        if (posFlags < 0) posFlags = positionFlags(input, pos, to);
                        if ((posFlags & acceptMask) == acceptMask) {
                            lastAcceptPos = pos; lastAcceptState = state; haveAccept = true;
                            int stopMask = stopOnAcceptMask[state * 16 + posFlags];
                            if (perlMode && stopOnAccept(stopMask, posFlags)) break loop;
                        }
                    }
                }
                if (pos >= to) break;
                char c = input.charAt(pos);
                int base = meta >>> 9;
                int count = (meta >>> 1) & 0xFF;
                for (int i = 0; i < count; i++) {
                    int o = (base + i) * 5;
                    if (c >= rg[o] && c <= rg[o + 1]) {
                        int target = rg[o + 2];
                        if (target < 0) break loop;
                        int requiredMask = rg[o + 4];
                        if (requiredMask != 0) {
                            if (posFlags < 0) posFlags = positionFlags(input, pos, to);
                            if ((posFlags & requiredMask) != requiredMask) continue;
                        }
                        if (regs != null) {
                            int opsOff = rg[o + 3];
                            if (opsOff != 0) applyOps(op, opsOff, regs, pos);
                        }
                        state = target;
                        if (c >= 0xD800 && c <= 0xDBFF && pos + 1 < to
                                && input.charAt(pos + 1) >= 0xDC00 && input.charAt(pos + 1) <= 0xDFFF) {
                            pos++;
                        }
                        int entryReq = sem[state];
                        if (entryReq != 0) {
                            if ((positionFlags(input, pos + 1, to) & entryReq) != entryReq) break loop;
                        }
                        posFlags = -1;
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
        final int[] sem = this.stateEntryMask;
        final int[] sam = this.stateAcceptMask;
        final int to = input.length();

        int state = startState;
        int lastAcceptPos = -1;

        // Entry check for start state — inline
        {
            int entryReq = sem[state];
            if (entryReq != 0 && (positionFlags(input, 0, to) & entryReq) != entryReq) return -1;
        }

        int posFlags = -1; // lazy: -1 means not yet computed for current pos
        for (int pos = 0; pos <= to; pos++) {
            int meta = sm[state];
            if ((meta & 1) != 0) {
                int acceptMask = sam[state];
                if (acceptMask == 0) {
                    lastAcceptPos = pos;
                } else {
                    if (posFlags < 0) posFlags = positionFlags(input, pos, to);
                    if ((posFlags & acceptMask) == acceptMask) lastAcceptPos = pos;
                }
            }
            if (pos == to) break;
            char c = input.charAt(pos);
            int base = meta >>> 9;
            int count = (meta >>> 1) & 0xFF;
            boolean matched = false;
            if (rangesDisjoint) {
                // ASCII fast path: direct table lookup
                int ri = c < 128 ? asciiRangeIdx[state][c] : -2;
                if (ri >= 0) {
                    int mo = (base + ri) * 5;
                    int target = rg[mo + 2];
                    if (target >= 0) {
                        int requiredMask = rg[mo + 4];
                        boolean maskOk = true;
                        if (requiredMask != 0) {
                            if (posFlags < 0) posFlags = positionFlags(input, pos, to);
                            maskOk = (posFlags & requiredMask) == requiredMask;
                        }
                        if (maskOk) {
                            state = target;
                            if (c >= 0xD800 && c <= 0xDBFF && pos + 1 < to
                                    && input.charAt(pos + 1) >= 0xDC00 && input.charAt(pos + 1) <= 0xDFFF) {
                                pos++;
                            }
                            int entryReq = sem[state];
                            if (entryReq == 0 || (positionFlags(input, pos + 1, to) & entryReq) == entryReq) {
                                matched = true;
                            } else {
                                return lastAcceptPos == to ? lastAcceptPos : -1;
                            }
                        }
                    }
                } else if (ri == -1) {
                    break; // dead ASCII char
                } else {
                    // Non-ASCII: binary search
                    int rlo = 0, rhi = count - 1;
                    while (rlo <= rhi) {
                        int mid = (rlo + rhi) >>> 1;
                        int mo = (base + mid) * 5;
                        if (c < rg[mo]) { rhi = mid - 1; continue; }
                        if (c > rg[mo + 1]) { rlo = mid + 1; continue; }
                        int target = rg[mo + 2];
                        if (target < 0) break;
                        int requiredMask = rg[mo + 4];
                        if (requiredMask != 0) {
                            if (posFlags < 0) posFlags = positionFlags(input, pos, to);
                            if ((posFlags & requiredMask) != requiredMask) break;
                        }
                        state = target;
                        if (c >= 0xD800 && c <= 0xDBFF && pos + 1 < to
                                && input.charAt(pos + 1) >= 0xDC00 && input.charAt(pos + 1) <= 0xDFFF) {
                            pos++;
                        }
                        int entryReq = sem[state];
                        if (entryReq != 0) {
                            if ((positionFlags(input, pos + 1, to) & entryReq) != entryReq) {
                                return lastAcceptPos == to ? lastAcceptPos : -1;
                            }
                        }
                        matched = true;
                        break;
                    }
                }
            } else {
            for (int i = 0; i < count; i++) {
                int o = (base + i) * 5;
                if (c >= rg[o] && c <= rg[o + 1]) {
                    int target = rg[o + 2];
                    if (target < 0) break;  // dead
                    int requiredMask = rg[o + 4];
                    if (requiredMask != 0) {
                        if (posFlags < 0) posFlags = positionFlags(input, pos, to);
                        if ((posFlags & requiredMask) != requiredMask) continue;
                    }
                    state = target;
                    if (c >= 0xD800 && c <= 0xDBFF && pos + 1 < to
                            && input.charAt(pos + 1) >= 0xDC00 && input.charAt(pos + 1) <= 0xDFFF) {
                        pos++;
                    }
                    // Entry check for target at pos+1 — inline
                    int entryReq = sem[state];
                    if (entryReq != 0) {
                        if ((positionFlags(input, pos + 1, to) & entryReq) != entryReq) {
                            return lastAcceptPos == to ? lastAcceptPos : -1;
                        }
                    }
                    matched = true;
                    break;
                }
            }
            }
            if (!matched) break;
            posFlags = -1;
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

        // Entry check for start state — inline
        {
            int entryReq = sem[state];
            if (entryReq != 0 && (positionFlags(input, pos, to) & entryReq) != entryReq) return -1;
        }

        int posFlags = -1;
        for (; ; pos++) {
            int meta = sm[state];
            if ((meta & 1) != 0) {
                int acceptMask = sam[state];
                if (acceptMask == 0) {
                    haveAccept = true; lastAcceptPos = pos;
                    if (perlMode) {
                        if (posFlags < 0) posFlags = positionFlags(input, pos, to);
                        int stopMask = stopOnAcceptMask[state * 16 + posFlags];
                        if (stopOnAccept(stopMask, posFlags)) break;
                    }
                } else {
                    if (posFlags < 0) posFlags = positionFlags(input, pos, to);
                    if ((posFlags & acceptMask) == acceptMask) {
                        haveAccept = true; lastAcceptPos = pos;
                        int stopMask = stopOnAcceptMask[state * 16 + posFlags];
                        if (perlMode && stopOnAccept(stopMask, posFlags)) break;
                    }
                }
            }
            if (pos >= to) break;
            char c = input.charAt(pos);
            int base = meta >>> 9;
            int count = (meta >>> 1) & 0xFF;
            boolean matched = false;
            if (rangesDisjoint) {
                int rlo = 0, rhi = count - 1;
                while (rlo <= rhi) {
                    int mid = (rlo + rhi) >>> 1;
                    int mo = (base + mid) * 5;
                    if (c < rg[mo]) { rhi = mid - 1; continue; }
                    if (c > rg[mo + 1]) { rlo = mid + 1; continue; }
                    int target = rg[mo + 2];
                    if (target < 0) return haveAccept ? lastAcceptPos : -1;
                    int requiredMask = rg[mo + 4];
                    if (requiredMask != 0) {
                        if (posFlags < 0) posFlags = positionFlags(input, pos, to);
                        if ((posFlags & requiredMask) != requiredMask) return haveAccept ? lastAcceptPos : -1;
                    }
                    state = target;
                    if (c >= 0xD800 && c <= 0xDBFF && pos + 1 < to
                            && input.charAt(pos + 1) >= 0xDC00 && input.charAt(pos + 1) <= 0xDFFF) {
                        pos++;
                    }
                    int entryReq = sem[state];
                    if (entryReq != 0) {
                        if ((positionFlags(input, pos + 1, to) & entryReq) != entryReq) {
                            return haveAccept ? lastAcceptPos : -1;
                        }
                    }
                    matched = true;
                    break;
                }
            } else {
            for (int i = 0; i < count; i++) {
                int o = (base + i) * 5;
                if (c >= rg[o] && c <= rg[o + 1]) {
                    int target = rg[o + 2];
                    if (target < 0) return haveAccept ? lastAcceptPos : -1;
                    int requiredMask = rg[o + 4];
                    if (requiredMask != 0) {
                        if (posFlags < 0) posFlags = positionFlags(input, pos, to);
                        if ((posFlags & requiredMask) != requiredMask) continue;
                    }
                    state = target;
                    if (c >= 0xD800 && c <= 0xDBFF && pos + 1 < to
                            && input.charAt(pos + 1) >= 0xDC00 && input.charAt(pos + 1) <= 0xDFFF) {
                        pos++;
                    }
                    int entryReq = sem[state];
                    if (entryReq != 0) {
                        if ((positionFlags(input, pos + 1, to) & entryReq) != entryReq) {
                            return haveAccept ? lastAcceptPos : -1;
                        }
                    }
                    matched = true;
                    break;
                }
            }
            }
            if (!matched) break;
            posFlags = -1;
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

            // Entry check for start state — inline
            {
                int entryReq = stateEntryMask[state];
                if (entryReq != 0 && (positionFlagsCS(input, pos, to) & entryReq) != entryReq) {
                    if (anchored) return null;
                    if ((startStateEntryMask & Tnfa.BEGIN_TEXT) != 0) return null;
                    startSearch++;
                    if (startSearch > to) return null;
                    continue;
                }
            }

            int posFlags = -1;
            loop:
            for (; ; pos++) {
                int meta = stateMeta[state];
                if ((meta & 1) != 0) {
                    int acceptMask = stateAcceptMask[state];
                    if (acceptMask == 0) {
                        lastAcceptPos = pos; lastAcceptState = state; haveAccept = true;
                        if (perlMode) {
                            if (posFlags < 0) posFlags = positionFlagsCS(input, pos, to);
                            int stopMask = stopOnAcceptMask[state * 16 + posFlags];
                            if (stopOnAccept(stopMask, posFlags)) break loop;
                        }
                    } else {
                        if (posFlags < 0) posFlags = positionFlagsCS(input, pos, to);
                        if ((posFlags & acceptMask) == acceptMask) {
                            lastAcceptPos = pos; lastAcceptState = state; haveAccept = true;
                            int stopMask = stopOnAcceptMask[state * 16 + posFlags];
                            if (perlMode && stopOnAccept(stopMask, posFlags)) break loop;
                        }
                    }
                }
                if (pos >= to) break;
                char c = input.charAt(pos);
                int base = meta >>> 9;
                int count = (meta >>> 1) & 0xFF;
                for (int i = 0; i < count; i++) {
                    int o = (base + i) * 5;
                    if (c >= ranges[o] && c <= ranges[o + 1]) {
                        int target = ranges[o + 2];
                        if (target < 0) break loop;
                        int requiredMask = ranges[o + 4];
                        if (requiredMask != 0) {
                            if (posFlags < 0) posFlags = positionFlagsCS(input, pos, to);
                            if ((posFlags & requiredMask) != requiredMask) continue;
                        }
                        if (regs != null) {
                            int opsOff = ranges[o + 3];
                            if (opsOff != 0) applyOps(ops, opsOff, regs, pos);
                        }
                        state = target;
                        if (c >= 0xD800 && c <= 0xDBFF && pos + 1 < to
                                && input.charAt(pos + 1) >= 0xDC00 && input.charAt(pos + 1) <= 0xDFFF) {
                            pos++;
                        }
                        int entryReq = stateEntryMask[state];
                        if (entryReq != 0) {
                            if ((positionFlagsCS(input, pos + 1, to) & entryReq) != entryReq) break loop;
                        }
                        posFlags = -1;
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

    /**
     * Determine whether to break the match loop on accept, based on the
     * position-aware per-(state, posFlags) cell of {@link Tdfa#stopOnAcceptMask}:
     * - {@link Tdfa#NEVER_STOP}: don't stop (sym-bearing config outranks accept
     *   under this posFlags, or accept unreachable).
     * - 0: stop (accept is the highest-priority live outcome under this posFlags).
     * The {@code posFlags} argument is unused beyond the array index computed
     * by the caller; kept for signature parity.
     */
    private static boolean stopOnAccept(int stopMask, int posFlags) {
        return stopMask != Tdfa.NEVER_STOP;
    }

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

    /** Check if all states have pairwise-disjoint ranges (no overlapping ranges). */
    private static boolean checkRangesDisjoint(Tdfa tdfa) {
        int[] sm = tdfa.stateMeta, rg = tdfa.ranges;
        for (int s = 0; s < tdfa.stateCount; s++) {
            int meta = sm[s];
            int base = meta >>> 9, cnt = (meta >>> 1) & 0xFF;
            for (int i = 0; i < cnt; i++) {
                int o1 = (base + i) * 5;
                int lo1 = rg[o1], hi1 = rg[o1 + 1];
                for (int j = i + 1; j < cnt; j++) {
                    int o2 = (base + j) * 5;
                    int lo2 = rg[o2], hi2 = rg[o2 + 1];
                    if (lo1 <= hi2 && lo2 <= hi1) return false;
                }
            }
        }
        return true;
    }

    /** Build per-state ASCII range-index lookup tables (128 entries per state). */
    private static int[][] buildAsciiRangeIdx(Tdfa tdfa) {
        int[] sm = tdfa.stateMeta, rg = tdfa.ranges;
        int[][] result = new int[tdfa.stateCount][];
        for (int s = 0; s < tdfa.stateCount; s++) {
            int meta = sm[s];
            int base = meta >>> 9, cnt = (meta >>> 1) & 0xFF;
            int[] table = new int[128];
            java.util.Arrays.fill(table, -1);
            for (int i = 0; i < cnt; i++) {
                int o = (base + i) * 5;
                int lo = Math.max(rg[o], 0);
                int hi = Math.min(rg[o + 1], 127);
                for (int c = lo; c <= hi; c++) table[c] = i;
            }
            result[s] = table;
        }
        return result;
    }

    /** RE2's isWordRune: ASCII word chars [_0-9A-Za-z]. */
    private static boolean isWordChar(char c) {
        return c == '_' || (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }
}
