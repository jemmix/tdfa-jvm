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
    private final int[] stateBase;
    private final int[] stateFinalOpsOff;
    private final int[] stateEntryMask;
    private final int[] stateAcceptMask;
    private final int[] ranges;
    private final int[] ops;
    private final int regSize;
    private final int startState;
    private final int startStateEntryMask;
    private final boolean perlMode;
    private final boolean multiline;
    private final int[] stopOnAcceptMask;
    private final boolean rangesDisjoint;
    private final int[] asciiTarget;    // flat: [state * 128 + c] → target state (-1 = dead)
    private final int[] asciiRangeFlat; // flat: [state * 128 + c] → range index (-1 = dead)
    private final boolean fastPath;     // true = no masks + disjoint + not multiline
    private final int stateCount;
    private final int stateWords;       // # of 32-bit words in state bitsets
    private final int[] acceptBits;     // bitset of accepting states (over-approximate)
    private final boolean unicodeWordBoundary;
    private final int[] wordRanges;     // Unicode \w ranges for \b when unicodeWordBoundary is true

    public TdfaRunner(Tnfa nfa) {
        this(Tdfa.compile(nfa));
    }

    public TdfaRunner(Tdfa tdfa) {
        this.tdfa = tdfa;
        this.stateMeta = tdfa.stateMeta;
        this.stateBase = tdfa.stateBase;
        this.stateFinalOpsOff = tdfa.stateFinalOpsOff;
        this.stateEntryMask = tdfa.stateEntryMask;
        this.stateAcceptMask = tdfa.stateAcceptMask;
        this.ranges = tdfa.ranges;
        this.ops = tdfa.ops;
        this.regSize = tdfa.registerCount;
        this.startState = tdfa.startState;
        this.startStateEntryMask = tdfa.startStateEntryMask;
        this.rangesDisjoint = checkRangesDisjoint(tdfa);
        if (rangesDisjoint) {
            this.asciiRangeFlat = buildAsciiRangeFlat(tdfa);
            this.asciiTarget = buildAsciiTarget(tdfa);
        } else {
            this.asciiRangeFlat = null;
            this.asciiTarget = null;
        }
        this.fastPath = computeFastPath(tdfa);
        this.perlMode = tdfa.perlMode;
        this.multiline = tdfa.multiline;
        this.stopOnAcceptMask = tdfa.stopOnAcceptMask;
        this.stateCount = tdfa.stateCount;
        this.stateWords = (tdfa.stateCount + 31) >>> 5;
        this.acceptBits = buildAcceptBits(tdfa);
        this.unicodeWordBoundary = tdfa.unicodeWordBoundary;
        this.wordRanges = tdfa.wordRanges;
    }

    public Tdfa tdfa() { return tdfa; }

    /**
     * Fast no-match pre-check: returns {@code true} if the DFA could match
     * starting at any position in {@code [from, input.length())}. Sound
     * over-approximation (ignores masks). Used by the ASM backend to avoid its
     * O(n²) outer-loop scan on non-matching haystacks — see {@link #multiStateAnyMatch}.
     */
    public final boolean anyMatch(CharSequence input, int from) {
        return multiStateAnyMatch(input, from, input.length());
    }

    @Override public boolean matches(CharSequence input) {
        if (input instanceof String) {
            String s = (String) input;
            if (fastPath) return runStringAnchoredFast(s);
            return runStringAnchored(s) >= 0;
        }
        return runGeneric(input, 0, input.length(), true) != null;
    }

    @Override public boolean find(CharSequence input) {
        if (input instanceof String) {
            String s = (String) input;
            int len = s.length();
            if (fastPath) return runStringFindFast(s, len);
            int maxStart = (!multiline && (startStateEntryMask & Tnfa.BEGIN_TEXT) != 0) ? 0 : len;
            if (maxStart > 0 && !multiStateAnyMatch(s, 0, len)) return false;
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
            String s = (String) input;
            h = fastPath ? runStringExtractFast(s, from, s.length()) : runStringExtract(s, from, s.length());
        } else {
            h = runGeneric(input, from, input.length(), false);
        }
        if (h == null) return null;
        if (tdfa.fixedBase != null) {
            MatchResult.reconstructFixed(h.regs, tdfa.finalRegBase, tdfa.fixedBase, tdfa.fixedOffset);
        }
        return new MatchResult(h.regs, tdfa.finalRegBase, tdfa.groupCount, h.matchStart, h.matchEnd);
    }

    /** String find with anchor enforcement and register extraction. */
    private MatchHolder runStringExtract(String input, int from, int to) {
        final int[] sm = this.stateMeta;
        final int[] rg = this.ranges;
        final int[] op = this.ops;
        final int[] sfo = this.stateFinalOpsOff;
        final int[] sem = this.stateEntryMask;
        final int[] sam = this.stateAcceptMask;
        int maxStart = (!multiline && (startStateEntryMask & Tnfa.BEGIN_TEXT) != 0) ? 0 : to;
        // Multi-state no-match pre-check (over-approximate masks). Only helps
        // the unanchored case — anchored regexes have maxStart == 0.
        if (maxStart > 0 && !multiStateAnyMatch(input, from, to)) return null;
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
                            int stopMask = stopOnAcceptMask[state * 64 + posFlags];
                            if (stopOnAccept(stopMask, posFlags)) break loop;
                        }
                    } else {
                        if (posFlags < 0) posFlags = positionFlags(input, pos, to);
                        if ((posFlags & acceptMask) == acceptMask) {
                            lastAcceptPos = pos; lastAcceptState = state; haveAccept = true;
                            int stopMask = stopOnAcceptMask[state * 64 + posFlags];
                            if (perlMode && stopOnAccept(stopMask, posFlags)) break loop;
                        }
                    }
                }
                if (pos >= to) break;
                char c0 = input.charAt(pos); int c = c0;
                if (c0 >= 0xD800 && c0 <= 0xDBFF && pos + 1 < to
                        && input.charAt(pos + 1) >= 0xDC00 && input.charAt(pos + 1) <= 0xDFFF)
                    c = ((c0 - 0xD800) << 10) + (input.charAt(pos + 1) - 0xDC00) + 0x10000;
                int base = stateBase[state];
                int count = (meta >>> 1) & 0xFFFF;
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
                        if (c > 0xFFFF) pos++;
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

    // ===== Multi-state parallel simulation (unanchored search) =====

    /**
     * Multi-state parallel simulation for unanchored search. Maintains the set
     * of all DFA states reachable from some start position in {@code [from, pos]},
     * checking for any accepting state at each position. Returns {@code true} as
     * soon as any accepting state enters the live set.
     *
     * <p>This is O(n × |states|) per call — a single forward pass — instead of
     * the O(n²) outer-loop restart used by the single-state extract paths. It
     * replaces the boolean {@code find()} path and serves as a fast no-match
     * pre-check for the extract paths: if this returns {@code false}, the
     * extract short-circuits to {@code null} without the O(n²) scan.
     *
     * <p>The implicit {@code .*?} prefix (unanchored search can start anywhere)
     * is modelled by re-adding the start state to the live set at every position.
     *
     * <p>For the generic path (masks / non-disjoint ranges) this is a sound
     * over-approximation: entry/accept/required masks are ignored and all
     * matching range targets are followed. A {@code false} result is definitive;
     * a {@code true} result means "might match" and the caller re-runs the exact
     * single-state path for registers / PERL priority.
     */
    private boolean multiStateAnyMatch(CharSequence input, int from, int to) {
        final int nwords = stateWords;
        if (nwords == 0) return false;
        final int[] sm = stateMeta;
        final int[] rg = ranges;
        final int[] at = asciiTarget;
        final int[] ab = acceptBits;
        final int ss = startState;
        final boolean disjoint = rangesDisjoint;

        int[] live = new int[nwords];
        int[] next = new int[nwords];
        live[ss >>> 5] |= 1 << (ss & 31);

        for (int pos = from; pos <= to; pos++) {
            for (int w = 0; w < nwords; w++) {
                if ((live[w] & ab[w]) != 0) return true;
            }
            if (pos == to) break;

            char c0 = input.charAt(pos);
            int c = c0;
            int adv = 1;
            if (c0 >= 0xD800 && c0 <= 0xDBFF && pos + 1 < to) {
                char c1 = input.charAt(pos + 1);
                if (c1 >= 0xDC00 && c1 <= 0xDFFF) {
                    c = ((c0 - 0xD800) << 10) + (c1 - 0xDC00) + 0x10000;
                    adv = 2;
                }
            }

            Arrays.fill(next, 0);
            next[ss >>> 5] |= 1 << (ss & 31);

            if (at != null && c < 128) {
                for (int w = 0; w < nwords; w++) {
                    int bits = live[w];
                    while (bits != 0) {
                        int bit = Integer.numberOfTrailingZeros(bits);
                        bits &= bits - 1;
                        int s = (w << 5) + bit;
                        int target = at[s * 128 + c];
                        if (target >= 0) {
                            next[target >>> 5] |= 1 << (target & 31);
                        }
                    }
                }
            } else {
                for (int w = 0; w < nwords; w++) {
                    int bits = live[w];
                    while (bits != 0) {
                        int bit = Integer.numberOfTrailingZeros(bits);
                        bits &= bits - 1;
                        int s = (w << 5) + bit;
                        int meta = sm[s];
                        int base = stateBase[s];
                        int count = (meta >>> 1) & 0xFFFF;
                        if (disjoint) {
                            int rlo = 0, rhi = count - 1;
                            while (rlo <= rhi) {
                                int mid = (rlo + rhi) >>> 1;
                                int mo = (base + mid) * 5;
                                if (c < rg[mo]) { rhi = mid - 1; continue; }
                                if (c > rg[mo + 1]) { rlo = mid + 1; continue; }
                                int target = rg[mo + 2];
                                if (target >= 0) next[target >>> 5] |= 1 << (target & 31);
                                break;
                            }
                        } else {
                            for (int i = 0; i < count; i++) {
                                int mo = (base + i) * 5;
                                if (c >= rg[mo] && c <= rg[mo + 1]) {
                                    int target = rg[mo + 2];
                                    if (target >= 0) next[target >>> 5] |= 1 << (target & 31);
                                }
                            }
                        }
                    }
                }
            }

            int[] tmp = live; live = next; next = tmp;
            if (adv == 2) pos++;
        }
        return false;
    }

    // ===== Fast paths: no masks, disjoint ranges, ASCII-only =====

    /**
     * Ultra-tight anchored match for DFAs with no masks and disjoint ranges.
     * Uses a flat precomputed target table: one array load per char.
     * Falls back to {@link #runStringAnchored} on non-ASCII input.
     */
    private boolean runStringAnchoredFast(String input) {
        final int to = input.length();
        final int[] sm = this.stateMeta;
        final int[] at = this.asciiTarget;
        int state = startState;
        for (int pos = 0; pos < to; pos++) {
            char c = input.charAt(pos);
            if (c >= 128) return runStringAnchored(input) >= 0;
            state = at[state * 128 + c];
            if (state < 0) return false;
        }
        return (sm[state] & 1) != 0;
    }

    /** Unanchored boolean search via single-pass multi-state simulation. O(n × |states|). */
    private boolean runStringFindFast(String input, int to) {
        return multiStateAnyMatch(input, 0, to);
    }

    /** Fast extract with register updates. */
    private MatchHolder runStringExtractFast(String input, int from, int to) {
        // Multi-state no-match pre-check: avoids the O(n²) outer-loop scan when
        // the regex doesn't match anywhere in the haystack. O(n × |states|).
        if (!multiStateAnyMatch(input, from, to)) return null;
        final int[] sm = this.stateMeta;
        final int[] arf = this.asciiRangeFlat;
        final int[] rg = this.ranges;
        final int[] op = this.ops;
        final int[] sfo = this.stateFinalOpsOff;
        // PERL stopOnAccept: pre-load the mask table when in Perl mode so the
        // inner loop can short-circuit on first accepting state (matching the
        // slow path's leftmost-first semantics). Without this, the inner loop
        // walks the DFA all the way to `to` tracking lastAcceptPos — O(n) per
        // find × O(n) finds = O(n²) on dense-match regexes like [a-zA-Z]+ing.
        // See docs/REBAR-SPEEDUP-PLAN.md §Tier-2 #3.
        final boolean pm = this.perlMode;
        final int[] soa = this.stopOnAcceptMask;
        for (int startSearch = from; startSearch <= to; startSearch++) {
            final int[] regs = regSize == 0 ? null : new int[regSize];
            if (regs != null) Arrays.fill(regs, -1);
            int state = startState;
            int lastAcceptPos = -1, lastAcceptState = -1;
            boolean haveAccept = false;
            int posFlags = -1; // lazy: -1 means not yet computed for current pos
            for (int pos = startSearch; pos <= to; pos++) {
                int meta = sm[state];
                if ((meta & 1) != 0) {
                    haveAccept = true; lastAcceptPos = pos; lastAcceptState = state;
                    if (pm) {
                        posFlags = positionFlags(input, pos, to);
                        int stopMask = soa[state * 64 + posFlags];
                        if (stopOnAccept(stopMask, posFlags)) break;
                    }
                }
                if (pos == to) break;
                char c = input.charAt(pos);
                if (c >= 128) return runStringExtract(input, from, to);
                int ri = arf[state * 128 + c];
                if (ri < 0) break;
                int mo = (stateBase[state] + ri) * 5;
                int target = rg[mo + 2];
                if (target < 0) break;
                if (regs != null) {
                    int opsOff = rg[mo + 3];
                    if (opsOff != 0) applyOps(op, opsOff, regs, pos);
                }
                state = target;
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
            char c0 = input.charAt(pos); int c = c0;
            if (c0 >= 0xD800 && c0 <= 0xDBFF && pos + 1 < to
                    && input.charAt(pos + 1) >= 0xDC00 && input.charAt(pos + 1) <= 0xDFFF)
                c = ((c0 - 0xD800) << 10) + (input.charAt(pos + 1) - 0xDC00) + 0x10000;
            int base = stateBase[state];
            int count = (meta >>> 1) & 0xFFFF;
            boolean matched = false;
            if (rangesDisjoint) {
                // ASCII fast path: direct table lookup
                int ri = c < 128 ? asciiRangeFlat[state * 128 + c] : -2;
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
                            if (c > 0xFFFF) pos++;
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
                        if (c > 0xFFFF) pos++;
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
                    if (c > 0xFFFF) pos++;
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
                        int stopMask = stopOnAcceptMask[state * 64 + posFlags];
                        if (stopOnAccept(stopMask, posFlags)) break;
                    }
                } else {
                    if (posFlags < 0) posFlags = positionFlags(input, pos, to);
                    if ((posFlags & acceptMask) == acceptMask) {
                        haveAccept = true; lastAcceptPos = pos;
                        int stopMask = stopOnAcceptMask[state * 64 + posFlags];
                        if (perlMode && stopOnAccept(stopMask, posFlags)) break;
                    }
                }
            }
            if (pos >= to) break;
            char c0 = input.charAt(pos); int c = c0;
            if (c0 >= 0xD800 && c0 <= 0xDBFF && pos + 1 < to
                    && input.charAt(pos + 1) >= 0xDC00 && input.charAt(pos + 1) <= 0xDFFF)
                c = ((c0 - 0xD800) << 10) + (input.charAt(pos + 1) - 0xDC00) + 0x10000;
            int base = stateBase[state];
            int count = (meta >>> 1) & 0xFFFF;
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
                    if (c > 0xFFFF) pos++;
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
                    if (c > 0xFFFF) pos++;
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
                    if (!multiline && (startStateEntryMask & Tnfa.BEGIN_TEXT) != 0) return null;
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
                            int stopMask = stopOnAcceptMask[state * 64 + posFlags];
                            if (stopOnAccept(stopMask, posFlags)) break loop;
                        }
                    } else {
                        if (posFlags < 0) posFlags = positionFlagsCS(input, pos, to);
                        if ((posFlags & acceptMask) == acceptMask) {
                            lastAcceptPos = pos; lastAcceptState = state; haveAccept = true;
                            int stopMask = stopOnAcceptMask[state * 64 + posFlags];
                            if (perlMode && stopOnAccept(stopMask, posFlags)) break loop;
                        }
                    }
                }
                if (pos >= to) break;
                char c0 = input.charAt(pos); int c = c0;
                if (c0 >= 0xD800 && c0 <= 0xDBFF && pos + 1 < to
                        && input.charAt(pos + 1) >= 0xDC00 && input.charAt(pos + 1) <= 0xDFFF)
                    c = ((c0 - 0xD800) << 10) + (input.charAt(pos + 1) - 0xDC00) + 0x10000;
                int base = stateBase[state];
                int count = (meta >>> 1) & 0xFFFF;
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
                        if (c > 0xFFFF) pos++;
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
            if (!multiline && (startStateEntryMask & Tnfa.BEGIN_TEXT) != 0) return null;
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

    /** Compute the position-flags for `pos` in a String. */
    private int positionFlags(String s, int pos, int len) {
        int flags = 0;
        if (pos == 0 || (multiline && pos > 0 && s.charAt(pos - 1) == '\n')) flags |= Tnfa.BEGIN_TEXT;
        if (pos == len || (multiline && pos < len && s.charAt(pos) == '\n')) flags |= Tnfa.END_TEXT;
        if (pos == 0) flags |= Tnfa.ABS_BEGIN;   // \A: absolute start, never affected by (?m)
        if (pos == len) flags |= Tnfa.ABS_END;    // \z: absolute end, never affected by (?m)
        boolean prevWord = pos > 0 && isWordChar(s.charAt(pos - 1));
        boolean currWord = pos < len && isWordChar(s.charAt(pos));
        if (prevWord != currWord) flags |= Tnfa.WORD_BOUNDARY;
        else flags |= Tnfa.NO_WORD_BOUNDARY;
        return flags;
    }

    /** Same for a generic CharSequence. */
    private int positionFlagsCS(CharSequence s, int pos, int len) {
        int flags = 0;
        if (pos == 0 || (multiline && pos > 0 && s.charAt(pos - 1) == '\n')) flags |= Tnfa.BEGIN_TEXT;
        if (pos == len || (multiline && pos < len && s.charAt(pos) == '\n')) flags |= Tnfa.END_TEXT;
        if (pos == 0) flags |= Tnfa.ABS_BEGIN;
        if (pos == len) flags |= Tnfa.ABS_END;
        boolean prevWord = pos > 0 && isWordChar(s.charAt(pos - 1));
        boolean currWord = pos < len && isWordChar(s.charAt(pos));
        if (prevWord != currWord) flags |= Tnfa.WORD_BOUNDARY;
        else flags |= Tnfa.NO_WORD_BOUNDARY;
        return flags;
    }

    /** True iff stateEntryMask[state] is satisfied at `pos` in `input[0..len)`. */
    private boolean entryMaskOk(int[] sem, int state, String input, int pos, int len) {
        int required = sem[state];
        if (required == 0) return true;
        return (positionFlags(input, pos, len) & required) == required;
    }

    private boolean entryMaskOkCharSeq(int[] sem, int state, CharSequence input, int pos, int len) {
        int required = sem[state];
        if (required == 0) return true;
        return (positionFlagsCS(input, pos, len) & required) == required;
    }

    /** Check if all states have pairwise-disjoint ranges (no overlapping ranges). */
    private static boolean checkRangesDisjoint(Tdfa tdfa) {
        int[] sm = tdfa.stateMeta, rg = tdfa.ranges;
        for (int s = 0; s < tdfa.stateCount; s++) {
            int meta = sm[s];
            int base = tdfa.stateBase[s], cnt = (meta >>> 1) & 0xFFFF;
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

    /**
     * Bitset of states with accept capability (stateMeta bit 0 set). This is an
     * over-approximation for the generic path — a state may be only conditionally
     * accepting (non-zero acceptMask), but for the multi-state no-match pre-check
     * we want to err on the side of "might accept" so we never skip a real match.
     */
    private static int[] buildAcceptBits(Tdfa tdfa) {
        int words = (tdfa.stateCount + 31) >>> 5;
        int[] bits = new int[words];
        for (int s = 0; s < tdfa.stateCount; s++) {
            if ((tdfa.stateMeta[s] & 1) != 0) {
                bits[s >>> 5] |= 1 << (s & 31);
            }
        }
        return bits;
    }

    /** Build flat per-state ASCII target lookup: [state * 128 + c] → target state (-1 = dead). */
    private static int[] buildAsciiTarget(Tdfa tdfa) {
        int[] sm = tdfa.stateMeta, rg = tdfa.ranges;
        int[] flat = new int[tdfa.stateCount * 128];
        java.util.Arrays.fill(flat, -1);
        for (int s = 0; s < tdfa.stateCount; s++) {
            int meta = sm[s];
            int base = tdfa.stateBase[s], cnt = (meta >>> 1) & 0xFFFF;
            for (int i = 0; i < cnt; i++) {
                int o = (base + i) * 5;
                int lo = Math.max(rg[o], 0);
                int hi = Math.min(rg[o + 1], 127);
                int target = rg[o + 2];
                for (int c = lo; c <= hi; c++) flat[s * 128 + c] = target;
            }
        }
        return flat;
    }

    /** Build flat per-state ASCII range-index lookup: [state * 128 + c] → range index (-1 = dead). */
    private static int[] buildAsciiRangeFlat(Tdfa tdfa) {
        int[] sm = tdfa.stateMeta, rg = tdfa.ranges;
        int[] flat = new int[tdfa.stateCount * 128];
        java.util.Arrays.fill(flat, -1);
        for (int s = 0; s < tdfa.stateCount; s++) {
            int meta = sm[s];
            int base = tdfa.stateBase[s], cnt = (meta >>> 1) & 0xFFFF;
            for (int i = 0; i < cnt; i++) {
                int o = (base + i) * 5;
                int lo = Math.max(rg[o], 0);
                int hi = Math.min(rg[o + 1], 127);
                for (int c = lo; c <= hi; c++) flat[s * 128 + c] = i;
            }
        }
        return flat;
    }

    /** True if the DFA qualifies for the no-masks fast path. */
    private boolean computeFastPath(Tdfa tdfa) {
        if (!rangesDisjoint || multiline) return false;
        for (int mask : tdfa.stateEntryMask) if (mask != 0) return false;
        for (int mask : tdfa.stateAcceptMask) if (mask != 0) return false;
        for (int i = 4; i < tdfa.ranges.length; i += 5) if (tdfa.ranges[i] != 0) return false;
        return true;
    }

    /**
     * RE2's isWordRune: ASCII word chars [_0-9A-Za-z].
     * When {@link #unicodeWordBoundary} is true, checks the Unicode {@code \w}
     * ranges (matching {@code java.util.regex} with {@code UNICODE_CHARACTER_CLASS}).
     */
    private boolean isWordChar(char c) {
        if (!unicodeWordBoundary) {
            return c == '_' || (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
        }
        return isUnicodeWordChar(c);
    }

    /** Binary-search the Unicode {@code \w} ranges in {@link #wordRanges}. */
    private boolean isUnicodeWordChar(char c) {
        int[] wr = wordRanges;
        if (wr == null) return false;
        int lo = 0, hi = wr.length / 2 - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int rLo = wr[2 * mid], rHi = wr[2 * mid + 1];
            if (c < rLo) hi = mid - 1;
            else if (c > rHi) lo = mid + 1;
            else return true;
        }
        return false;
    }
}
