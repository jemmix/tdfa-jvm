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
    private final boolean[] stateIsFallback;
    private final int[] stateFallbackOpsOff;
    private final boolean rangesDisjoint;
    private final int[] asciiTarget;    // flat: [state * 128 + c] → target state (-1 = dead)
    private final int[] asciiRangeFlat; // flat: [state * 128 + c] → range index (-1 = dead)
    private final boolean fastPath;     // true = no masks + disjoint + not multiline
    private final int stateCount;
    private final int stateWords;       // # of 32-bit words in state bitsets
    private final int[] acceptBits;     // bitset of accepting states (over-approximate)
    private final boolean unicodeWordBoundary;
    private final int[] wordRanges;     // Unicode \w ranges for \b when unicodeWordBoundary is true

    // ===== per-thread scratch (P2: hot-path allocation removal) =====
    //
    // A runner is shared when its Pattern is used from several threads (re2j
    // semantics: Pattern thread-safe, Matcher not), so scratch buffers are
    // ThreadLocal, not instance fields. Sizes are re-validated on every use —
    // a single Scratch object serves all runners on the thread, growing to the
    // largest DFA seen. Eliminates the 4 sim allocations per find()/match()
    // (O(stateWords + stateCount) each — significant for dictionary-scale DFAs)
    // and the regs[] allocation on failed single-start walks. Successful walks
    // still clone regs into the returned MatchHolder (it escapes the runner).
    private static final class Scratch {
        int[] regs;
        int[] live, next, origin, originNext;
    }
    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

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
        this.stateIsFallback = tdfa.stateIsFallback;
        this.stateFallbackOpsOff = tdfa.stateFallbackOpsOff;
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

    /**
     * Leftmost position in {@code [from, input.length())} at which a match
     * starts, or {@code -1} if there is no match anywhere.
     *
     * <p>For fast-path DFAs (disjoint ranges, no assertion masks — see
     * {@link #computeFastPath}) this runs the multi-state simulation with
     * per-state origin tracking: {@code origin[s]} is the smallest start
     * position from which {@code s} is currently reachable. The first
     * accept-live position yields the answer. Early-stops once the best
     * origin can no longer be beaten ({@code best <= pos} and
     * {@code best <= min live origin}): future seeds have origin {@code > pos}.
     *
     * <p>Otherwise (mask-bearing DFAs, where the sim over-approximates) it
     * degrades to a boolean answer — {@code from} if any match might exist,
     * {@code -1} if definitely none — so the caller's exact extract loop
     * keeps its existing restart behavior.
     */
    public final int leftmostStart(CharSequence input, int from) {
        if (fastPath) {
            return multiStateLeftmostStart(input, from, input.length());
        }
        return multiStateAnyMatch(input, from, input.length()) ? from : -1;
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
                int foff = pickFinalOpsOff(lastAcceptState, lastAcceptPos, pos);
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

        Scratch sc = SCRATCH.get();
        int[] live = sc.live != null && sc.live.length >= nwords ? sc.live : new int[nwords];
        int[] next = sc.next != null && sc.next.length >= nwords ? sc.next : new int[nwords];
        sc.live = live; sc.next = next;
        Arrays.fill(live, 0, nwords, 0);
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

    /**
     * Multi-state simulation with per-state origin tracking; returns the
     * leftmost start position of any match in {@code [from, to]}, or -1.
     *
     * <p>Only called when {@link #fastPath} holds (disjoint ranges, no entry/
     * accept/required masks, not multiline) — otherwise the mask-free
     * transition-following would over-approximate. {@link #stopOnAcceptMask}
     * (Perl early-stop) only shortens matches, never removes them, so it is
     * safely ignored here: accept-live at p with origin o implies a match
     * starting at o exists.
     *
     * <p>{@code origin[s]} = smallest seed position from which s is live.
     * The start state is re-seeded at every position (unanchored search), so
     * its origin is always {@code from}; state bits and origins move in lockstep:
     * {@code next} is zeroed each step, so "bit already set in next" exactly
     * identifies re-reachable states (origin = min) vs first-arrival (origin = set).
     */
    private int multiStateLeftmostStart(CharSequence input, int from, int to) {
        final int nwords = stateWords;
        if (nwords == 0) return -1;
        final int[] sm = stateMeta;
        final int[] rg = ranges;
        final int[] at = asciiTarget;
        final int[] ab = acceptBits;
        final int ss = startState;

        int[] live, next, origin, originNext;
        Scratch sc = SCRATCH.get();
        if (sc.live != null && sc.live.length >= nwords && sc.next.length >= nwords
                && sc.origin != null && sc.origin.length >= stateCount && sc.originNext.length >= stateCount) {
            live = sc.live; next = sc.next;
            origin = sc.origin; originNext = sc.originNext;
        } else {
            live = new int[nwords]; next = new int[nwords];
            origin = new int[stateCount]; originNext = new int[stateCount];
            sc.live = live; sc.next = next; sc.origin = origin; sc.originNext = originNext;
        }
        // Origins are DOUBLE-BUFFERED with the state sets: origin[] pairs with
        // live[], originNext[] with next[]. All arrivals in a step write to
        // originNext (bit test against next), while old-live origins in origin[]
        // stay readable for the whole step — a single buffer would corrupt a
        // state's own origin mid-step when another path's arrival (or the fresh
        // re-seed) targets a still-live state before its self-loop reads it
        // (seen as +1/step origin drift on (\d+)\.(\d+)... over "ip=192.168.1.77").
        // Stale originNext values are never read: a bit present in next implies
        // a this-step write (first arrival sets, later arrivals min-merge).
        Arrays.fill(live, 0, nwords, 0);
        live[ss >>> 5] |= 1 << (ss & 31);
        origin[ss] = from;

        int best = -1;
        for (int pos = from; pos <= to; pos++) {
            // accept check with origin tracking
            for (int w = 0; w < nwords; w++) {
                int bits = live[w] & ab[w];
                while (bits != 0) {
                    int bit = Integer.numberOfTrailingZeros(bits);
                    bits &= bits - 1;
                    int s = (w << 5) + bit;
                    if (best < 0 || origin[s] < best) best = origin[s];
                }
            }
            if (best >= 0) {
                // Can any future accept beat `best`? Future accept origins are
                // either origins of currently-live states or seeds > pos.
                // best==from is the common dense case and stops immediately.
                int minLive = Integer.MAX_VALUE;
                for (int w = 0; w < nwords && minLive > best; w++) {
                    int bits = live[w];
                    while (bits != 0) {
                        int bit = Integer.numberOfTrailingZeros(bits);
                        bits &= bits - 1;
                        int s = (w << 5) + bit;
                        if (origin[s] < minLive) minLive = origin[s];
                    }
                }
                if (best <= pos && best <= minLive) return best;
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
            if (at != null && c < 128) {
                for (int w = 0; w < nwords; w++) {
                    int bits = live[w];
                    while (bits != 0) {
                        int bit = Integer.numberOfTrailingZeros(bits);
                        bits &= bits - 1;
                        int s = (w << 5) + bit;
                        int target = at[s * 128 + c];
                        if (target >= 0) setOrigin(next, originNext, target, origin[s]);
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
                        // rangesDisjoint == true on the fast path — binary search
                        int rlo = 0, rhi = count - 1;
                        while (rlo <= rhi) {
                            int mid = (rlo + rhi) >>> 1;
                            int mo = (base + mid) * 5;
                            if (c < rg[mo]) { rhi = mid - 1; continue; }
                            if (c > rg[mo + 1]) { rlo = mid + 1; continue; }
                            int target = rg[mo + 2];
                            if (target >= 0) setOrigin(next, originNext, target, origin[s]);
                            break;
                        }
                    }
                }
            }
            setOrigin(next, originNext, ss, pos + adv);  // unanchored re-seed (min-merge if already re-added)

            int[] tmp = live; live = next; next = tmp;
            int[] to2 = origin; origin = originNext; originNext = to2;
            if (adv == 2) pos++;
        }
        return best;
    }

    /**
     * Set state {@code s} in {@code next} (if absent) with origin {@code o} in
     * {@code originNext}; min-merge if already present. {@code originNext} is
     * only read for states whose bit is set in {@code next} (implying a
     * this-step write), so stale values are never observed.
     */
    private static void setOrigin(int[] next, int[] originNext, int s, int o) {
        int w = s >>> 5, b = 1 << (s & 31);
        if ((next[w] & b) == 0) {
            next[w] |= b;
            originNext[s] = o;
        } else if (o < originNext[s]) {
            originNext[s] = o;
        }
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
        // 1) Try ONE single-start walk from `from` — the common short-input case
        //    (match at/near the start) never needs the simulation at all.
        MatchHolder h = tryStartFast(input, from, to, from);
        if (h != null) return h;
        // 2) No match starting at `from`: find the leftmost start via the
        // origin-tracking multi-state simulation (O(n × |states|), early-stops
        // when the best origin can no longer be beaten — immediately for dense
        // matches). The old shape retried every failed start position with a
        // full walk: O(n) restarts × O(n) walk = O(n²) on dense-match regexes
        // like [a-zA-Z]+ing.
        int leftmost = multiStateLeftmostStart(input, from, to);
        if (leftmost < 0) return null;
        h = tryStartFast(input, leftmost, to, from);
        if (h != null) return h;
        // 3) Defensive: the sim and the walk must agree on fast-path DFAs; if
        // they ever don't, fall back to the old restart shape rather than
        // return a wrong null.
        for (int s = leftmost + 1; s <= to; s++) {
            h = tryStartFast(input, s, to, from);
            if (h != null) return h;
        }
        return null;
    }

    /**
     * One single-start extract walk (no restart loop); null if no match starts
     * exactly at {@code start}. {@code originFrom} is the caller's search
     * origin — only used to rerun the generic path when a non-ASCII char is
     * met mid-walk (the generic path re-does the search from there).
     */
    private MatchHolder tryStartFast(String input, int start, int to, int originFrom) {
        final int[] sm = this.stateMeta;
        final int[] arf = this.asciiRangeFlat;
        final int[] rg = this.ranges;
        final int[] op = this.ops;
        // PERL stopOnAccept: pre-load the mask table when in Perl mode so the
        // inner loop can short-circuit on first accepting state (matching the
        // slow path's leftmost-first semantics). See docs/REBAR-SPEEDUP-PLAN.md §Tier-2 #3.
        final boolean pm = this.perlMode;
        final int[] soa = this.stopOnAcceptMask;
        // Pooled regs (per-thread scratch): no allocation on the (frequent)
        // failed-walk path. On success the array is cloned into the MatchHolder
        // before returning, so the pool is never handed out. NOTE: the
        // non-ASCII fallback below re-enters the generic extract path, which
        // allocates its own regs — no pool aliasing.
        Scratch sc = SCRATCH.get();
        final int[] regs;
        if (regSize == 0) {
            regs = null;
        } else if (sc.regs != null && sc.regs.length >= regSize) {
            regs = sc.regs;
            Arrays.fill(regs, 0, regSize, -1);
        } else {
            regs = new int[regSize];
            java.util.Arrays.fill(regs, -1);
            sc.regs = regs;
        }
        int state = startState;
        int lastAcceptPos = -1, lastAcceptState = -1;
        boolean haveAccept = false;
        int posFlags = -1; // lazy: -1 means not yet computed for current pos
        int pos = start;
        for (; pos <= to; pos++) {
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
            if (c >= 128) return runStringExtract(input, originFrom, to);
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
            int foff = pickFinalOpsOff(lastAcceptState, lastAcceptPos, pos);
            if (foff != 0 && regs != null) applyOps(op, foff, r, lastAcceptPos);
            return new MatchHolder(start, lastAcceptPos, r);
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
                int foff = pickFinalOpsOff(lastAcceptState, lastAcceptPos, pos);
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

    /**
     * Pick the right final-ops offset for {@code lastAcceptState}: if it's a
     * fallback state AND the runner took at least one transition since the last
     * accept ({@code pos > lastAcceptPos}), use the §6.2 ψ quasi-transition
     * (whose clobbered COPYs were routed through backups on the way out).
     * Otherwise use the regular {@code φ}.
     */
    private int pickFinalOpsOff(int lastAcceptState, int lastAcceptPos, int pos) {
        if (stateIsFallback != null && stateIsFallback.length > lastAcceptState
                && stateIsFallback[lastAcceptState] && pos > lastAcceptPos) {
            return stateFallbackOpsOff[lastAcceptState];
        }
        return stateFinalOpsOff[lastAcceptState];
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
        boolean prevWord = isWordBefore(s, pos);
        boolean currWord = isWordAt(s, pos, len);
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
        boolean prevWord = isWordBefore(s, pos);
        boolean currWord = isWordAt(s, pos, len);
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
        return isUnicodeWordCodepoint(c);
    }

    /**
     * Binary-search the Unicode {@code \w} ranges by CODEPOINT. The ranges include
     * supplementary codepoints, so decoding a surrogate pair before calling this
     * recognises supplementary word characters (e.g. U+1D504 MATHEMATICAL FRAKTUR A).
     */
    private boolean isUnicodeWordCodepoint(int cp) {
        int[] wr = wordRanges;
        if (wr == null) return false;
        int lo = 0, hi = wr.length / 2 - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int rLo = wr[2 * mid], rHi = wr[2 * mid + 1];
            if (cp < rLo) hi = mid - 1;
            else if (cp > rHi) lo = mid + 1;
            else return true;
        }
        return false;
    }

    /**
     * Whether the character immediately BEFORE {@code pos} is a word character.
     * Under {@code (?u)} a supplementary letter's UTF-16 low surrogate at
     * {@code pos-1} is decoded with its high surrogate at {@code pos-2} first,
     * so boundaries adjacent to supplementary word chars are computed on the
     * full codepoint. In ASCII mode surrogate halves are simply non-word.
     */
    private boolean isWordBefore(CharSequence s, int pos) {
        if (pos <= 0) return false;
        char c = s.charAt(pos - 1);
        if (unicodeWordBoundary && c >= Character.MIN_LOW_SURROGATE && c <= Character.MAX_LOW_SURROGATE && pos >= 2) {
            char h = s.charAt(pos - 2);
            if (h >= Character.MIN_HIGH_SURROGATE && h <= Character.MAX_HIGH_SURROGATE) {
                return isUnicodeWordCodepoint(((h - 0xD800) << 10) + (c - 0xDC00) + 0x10000);
            }
        }
        return isWordChar(c);
    }

    /**
     * Whether the character AT {@code pos} is a word character; a high surrogate
     * at {@code pos} paired with a low surrogate at {@code pos+1} is decoded to
     * the full codepoint under {@code (?u)}.
     */
    private boolean isWordAt(CharSequence s, int pos, int len) {
        if (pos >= len) return false;
        char c = s.charAt(pos);
        if (unicodeWordBoundary && c >= Character.MIN_HIGH_SURROGATE && c <= Character.MAX_HIGH_SURROGATE && pos + 1 < len) {
            char l = s.charAt(pos + 1);
            if (l >= Character.MIN_LOW_SURROGATE && l <= Character.MAX_LOW_SURROGATE) {
                return isUnicodeWordCodepoint(((c - 0xD800) << 10) + (l - 0xDC00) + 0x10000);
            }
        }
        return isWordChar(c);
    }
}
