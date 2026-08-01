package io.github.jemmix.tdfa.tdfa;

import io.github.jemmix.tdfa.ast.CharClass;
import io.github.jemmix.tdfa.tnfa.Tnfa;

import java.util.*;

/**
 * Borsotti-Trofimovich 2022 TDFA(1): lookahead-TDFA with register indirection.
 *
 * Faithful implementation of paper Algorithm 3 (determinization):
 *   - epsilon_closure(B): DFS over ε-paths in priority order, recording tag sequences in l.
 *   - step_on_symbol(s, a): follows symbol transitions; old l becomes new h.
 *   - transition_regops: allocates one register per (tag, RHS) and emits SET_POS / SET_NIL.
 *   - add_state: dedupe by (NFA states, lookahead tags, register vectors). {@code map}+topo_sort
 *     is the paper's optimization for further state reduction; deferred.
 *   - final_regops: emits final-register SET/COPY ops for the accepting quasi-transition.
 *
 * Single-valued tags only (sufficient for j.u.r-style capturing groups).
 *
 * Alphabet: equivalence-class partitioned. Each DFA state stores sorted (lo, hi, target, ops)
 * ranges; runtime does binary search. Collapses 65K chars to a handful of ranges per state
 * (RE2-style byte-class partitioning).
 */
public final class Tdfa {
    public final int tagCount;
    public final int groupCount;
    public final int registerCount;
    public final int startState;
    public final int stateCount;
    /**
     * Bit mask of zero-width assertions required to ENTER this state. Checked at the
     * position where the state is entered. Replaces the old pattern-level
     * {@code hasStartAnchor} flag — now per-state and precise.
     *   bit 1 = BEGIN_TEXT, bit 2 = END_TEXT, bit 4 = WORD_BOUNDARY, bit 8 = NO_WORD_BOUNDARY
     */
    public final int[] stateEntryMask;
    /**
     * Bit mask required to declare a match in this (accepting) state. Subset of
     * {@link #stateEntryMask}. Replaces the old pattern-level {@code hasEndAnchor} flag.
     */
    public final int[] stateAcceptMask;
    /** Mask required to take the start state at all — used to limit find() start positions. */
    public final int startStateEntryMask;

    /**
     * True iff this TDFA was compiled for Perl (leftmost-first) disambiguation.
     * In Perl mode the runner stops at the first accept (highest-priority path);
     * in POSIX mode it continues stepping to find the longest match.
     */
    public final boolean perlMode;
    /**
     * Position-aware Perl-mode stop-on-accept decision table.
     * Indexed as {@code stopOnAcceptMask[state * 16 + posFlags]} where {@code posFlags}
     * is the runtime position-flags bitmask ({@code BEGIN_TEXT|END_TEXT|WORD_BOUNDARY|NO_WORD_BOUNDARY},
     * 4 bits, 16 possible values). Each cell encodes:
     * <ul>
     *   <li>{@code 0} — stop the match loop on accept (accept is the highest-priority
     *       live outcome under this posFlags);</li>
     *   <li>{@link #NEVER_STOP} — don't stop (a sym-bearing config outranks accept
     *       under this posFlags, or accept is unreachable).</li>
     * </ul>
     * Position-awareness is required because re2j's densePcs priority depends on
     * which assertion edges are live at the current cursor — e.g. for
     * {@code ^((?:$)|.)*} at pos 0 of "a", {@code $} fails so the {@code .}-branch
     * outranks the skip-exit MATCH (extend); at pos 1 (EOF), {@code $} holds and
     * the {@code $}-loop-back MATCH outranks {@code .} (stop).
     * Unused in POSIX mode (all cells stay {@link #NEVER_STOP}).
     */
    public final int[] stopOnAcceptMask;
    /** Sentinel for "don't stop on accept" — distinct from 0 (= stop). */
    public static final int NEVER_STOP = 0x10;  // bit above all real assertion bits (1|2|4|8)

    // === Flat packed arrays (4 arrays total; per-match regs adds a 5th at runtime) ===
    /**
     * [state] -> packed (rangeBase << 9) | (rangeCount << 1) | acceptBit.
     * One load per char gives accept + rangeBase + rangeCount.
     */
    public final int[] stateMeta;
    /** [state] -> finalOpsOff (offset into `ops`), 0 if none. Read only once per match. */
    public final int[] stateFinalOpsOff;
    /**
     * Flat ranges: [lo0, hi0, target0, opsOff0, requiredMask0,
     *               lo1, hi1, target1, opsOff1, requiredMask1, ...].
     * {@code requiredMask} is the assertion mask that must hold at the source position
     * for this transition to be live (intersection of source configs' masks).
     */
    public final int[] ranges;
    /** Flat ops: [op, dst, src, ...] blocks terminated by OP_END=0. Transition ops + final ops share this array. */
    public final int[] ops;

    public static final int OP_SET_POS = 1;
    public static final int OP_SET_NIL = 2;
    public static final int OP_COPY    = 3;
    public static final int OP_END     = 0;  // terminator for op blocks

    private Tdfa(int tagCount, int groupCount, int registerCount, int startState, int stateCount,
                 int[] stateMeta, int[] stateFinalOpsOff, int[] ranges, int[] ops,
                 int[] stateEntryMask, int[] stateAcceptMask, boolean perlMode, int[] stopOnAcceptMask) {
        this.tagCount = tagCount; this.groupCount = groupCount;
        this.registerCount = registerCount;
        this.startState = startState;
        this.stateCount = stateCount;
        this.stateMeta = stateMeta;
        this.stateFinalOpsOff = stateFinalOpsOff;
        this.ranges = ranges;
        this.ops = ops;
        this.stateEntryMask = stateEntryMask;
        this.stateAcceptMask = stateAcceptMask;
        this.startStateEntryMask = stateEntryMask[startState];
        this.perlMode = perlMode;
        this.stopOnAcceptMask = stopOnAcceptMask;
    }

    public boolean isAccept(int state) { return (stateMeta[state] & 1) != 0; }
    public int finalOpsOffset(int state) { return stateFinalOpsOff[state]; }
    /** Unpack range base from packed stateMeta. */
    public static int rangeBase(int meta) { return meta >>> 9; }
    /** Unpack range count from packed stateMeta. */
    public static int rangeCount(int meta) { return (meta >>> 1) & 0xFF; }
    /** Accept bit. */
    public static boolean accept(int meta) { return (meta & 1) != 0; }

    /** True if start state's entry mask requires {@link Tnfa#BEGIN_TEXT} (limits find() to pos 0). */
    public boolean startRequiresBeginText() { return (startStateEntryMask & Tnfa.BEGIN_TEXT) != 0; }

    public static Tdfa compile(Tnfa nfa) { return compile(nfa, Disambiguation.POSIX); }

    public static Tdfa compile(Tnfa nfa, Disambiguation disamb) {
        return new Compiler(nfa, disamb).compile();
    }

    private static final class Compiler {
        final Tnfa nfa;
        final int tags;
        final int[][] epsOut;
        final int[][] symOut;
        final int[] initialRegisters;
        final int[] finalRegisters;
        /** Equivalence-class breakpoints across the BMP. */
        final int[] breakpoints;
        /** If true, suppress lower-priority paths past an accept (Perl leftmost-first). */
        final boolean perl;

        final Map<DfaStateKey, Integer> stateIndex = new HashMap<>();
        final List<List<Config>> states = new ArrayList<>();
        /** Seed configs (pre-closure) for each DFA state, used to compute per-state DFS order. */
        final List<List<Config>> stateSeeds = new ArrayList<>();
        final BitSet accept = new BitSet();
        final BitSet processed = new BitSet();
        final List<DfaStateBuilder> builders = new ArrayList<>();
        final Deque<Integer> work = new ArrayDeque<>();
        /** Global register allocator counter; bumped monotonically across all states. */
        int nextReg;

        Compiler(Tnfa nfa) { this(nfa, Disambiguation.POSIX); }

        Compiler(Tnfa nfa, Disambiguation disamb) {
            this.nfa = nfa;
            this.tags = nfa.tagCount;
            this.epsOut = sortedOutgoing(nfa.epsFrom, nfa.epsPri);
            this.symOut = plainOutgoing(nfa.symFrom);
            this.initialRegisters = new int[tags];
            this.finalRegisters = new int[tags];
            for (int t = 0; t < tags; t++) initialRegisters[t] = t;
            for (int t = 0; t < tags; t++) finalRegisters[t] = tags + t;
            this.breakpoints = computeBreakpoints();
            this.perl = (disamb == Disambiguation.PERL);
            // Tag heights: group g → tags 2g-1, 2g → height g.
            // Index 0 unused (tags are 1-based). ntags (-tag) use height of |tag|.
            this.tagHeights = new int[tags + 1];
            for (int t = 1; t <= tags; t++) {
                tagHeights[t] = (t + 1) / 2;
            }
        }

        int[][] sortedOutgoing(int[] fromArr, int[] pri) {
            int[][] out = plainOutgoing(fromArr);
            for (int[] arr : out) {
                for (int a = 1; a < arr.length; a++) {
                    int key = arr[a]; int kp = pri[key]; int b = a - 1;
                    while (b >= 0 && pri[arr[b]] > kp) { arr[b + 1] = arr[b]; b--; }
                    arr[b + 1] = key;
                }
            }
            return out;
        }

        int[][] plainOutgoing(int[] fromArr) {
            int n = nfa.stateCount;
            int[] counts = new int[n];
            for (int f : fromArr) counts[f]++;
            int[][] out = new int[n][];
            for (int s = 0; s < n; s++) out[s] = new int[counts[s]];
            int[] idx = new int[n];
            for (int i = 0; i < fromArr.length; i++) out[fromArr[i]][idx[fromArr[i]]++] = i;
            return out;
        }

        /** Compute breakpoints: every char where some NFA CharClass boundary occurs. */
        int[] computeBreakpoints() {
            TreeSet<Integer> bps = new TreeSet<>();
            bps.add(0);
            bps.add(0x10000); // sentinel upper bound (exclusive)
            for (CharClass cc : nfa.symClass) {
                if (cc == null) continue;
                for (int r = 0; r < cc.ranges.length; r += 2) {
                    int lo = cc.ranges[r], hi = cc.ranges[r + 1];
                    if (lo > 0xFFFF) continue;
                    bps.add(lo);
                    int after = Math.min(hi, 0xFFFF) + 1;
                    if (after <= 0xFFFF) bps.add(after);
                }
            }
            int[] arr = new int[bps.size()];
            int i = 0;
            for (int b : bps) arr[i++] = b;
            return arr;
        }

        Tdfa compile() {
            nextReg = 2 * tags;
            if (debug) System.err.println("[tdfa] tags=" + tags + " breakpoints=" + breakpoints.length);
            List<Config> initSeed = List.of(
                    new Config(nfa.start, initialRegisters, EMPTY, EMPTY, 0));
            List<Config> initClosure = perl
                    ? epsilonClosure(initSeed)
                    : closurePosix(initSeed, null, 0);
            int startId = addState(initClosure, null, initSeed).targetId;
            if (!perl) statePrectables.add(computePrectable(initClosure, null, 0));
            else statePrectables.add(null);
            work.push(startId);

            int[] requiredMaskOut = new int[1];
            while (!work.isEmpty()) {
                int sid = work.pop();
                if (processed.get(sid)) continue;
                processed.set(sid);
                List<Config> cur = states.get(sid);
                if (debug) {
                    System.err.println("[tdfa] processing state " + sid + " configs:");
                    for (Config c : cur) System.err.println("    state=" + c.state + " l=" + Arrays.toString(c.l) + " regs=" + Arrays.toString(c.regs) + " mask=" + c.emptyMask);
                }
                // For each equivalence range, compute one transition (representative char = range.lo)
                for (int bi = 0; bi < breakpoints.length - 1; bi++) {
                    int rangeLo = breakpoints[bi];
                    if (rangeLo >= 0x10000) break;
                    int rangeHi = breakpoints[bi + 1] - 1;
                    char repr = (char) rangeLo;
                    List<Config> stepped = stepOnSymbol(cur, repr, requiredMaskOut);
                    if (stepped.isEmpty()) continue;
                    int requiredMask = requiredMaskOut[0];
                    List<Config> closed;
                    int[] newPrectable;
                    if (perl) {
                        closed = epsilonClosure(stepped);
                        newPrectable = null;
                    } else {
                        int[] parentPrectable = statePrectables.get(sid);
                        closed = closurePosix(stepped, parentPrectable, cur.size());
                        newPrectable = computePrectable(closed, parentPrectable, cur.size());
                    }
                    if (debug && closed.size() > 100) System.err.println("[tdfa] state " + sid + " range " + rangeLo + ".." + rangeHi + " closure=" + closed.size());
                    int[] ops = transitionRegops(closed, sid);
                    AddResult ar = addState(closed, ops, stepped);
                    if (!perl) {
                        // Ensure prectable slot exists for the target state.
                        while (statePrectables.size() <= ar.targetId) statePrectables.add(null);
                        if (statePrectables.get(ar.targetId) == null) {
                            statePrectables.set(ar.targetId, newPrectable);
                        }
                    }
                    if (debug) System.err.println("[tdfa] state " + sid + " on '" + (char) rangeLo + "' (" + rangeLo + ") -> " + ar.targetId + " ops.len=" + ar.ops.length + " mask=" + requiredMask);
                    builders.get(sid).addRange(rangeLo, rangeHi, ar.targetId, ar.ops, requiredMask);
                    if (!processed.get(ar.targetId)) work.push(ar.targetId);
                }
            }
            if (debug) System.err.println("[tdfa] total states=" + states.size() + " accept=" + accept.cardinality());

            int n = states.size();
            // Compute per-state entry/accept masks.
            int[] stateEntryMask = new int[n];
            int[] stateAcceptMask = new int[n];
            // Position-aware stopOnAcceptMask: int[state * 16 + posFlags] encodes
            // 0 (stop) or NEVER_STOP (don't stop). Position-aware because re2j's
            // densePcs priority depends on which assertion edges are live at the
            // current cursor position — see computePerStateOrder(seed, posMask).
            int[] stateStopOnAcceptMask = new int[n * 16];
            java.util.Arrays.fill(stateStopOnAcceptMask, NEVER_STOP);
            int ALL_BITS = Tnfa.BEGIN_TEXT | Tnfa.END_TEXT | Tnfa.WORD_BOUNDARY | Tnfa.NO_WORD_BOUNDARY;
            for (int s = 0; s < n; s++) {
                List<Config> cfgs = states.get(s);
                int entryIntersect = ALL_BITS;
                for (Config c : cfgs) entryIntersect &= c.emptyMask;
                stateEntryMask[s] = entryIntersect;
                int acceptIntersect = ALL_BITS;
                boolean anyAccept = false;
                for (int i = 0; i < cfgs.size(); i++) {
                    Config c = cfgs.get(i);
                    if (c.state == nfa.accept) {
                        acceptIntersect &= c.emptyMask;
                        anyAccept = true;
                    }
                }
                stateAcceptMask[s] = anyAccept ? acceptIntersect : 0;
                // Perl leftmost-first: for each (state, posFlags) pair, decide
                // whether the runner should break the match loop on accept. The
                // decision is position-aware because re2j's runtime closure
                // evaluates each assertion against the current cursor's cond and
                // kills failing threads before they can claim a densePcs slot —
                // so the same DFA state can have different "highest-priority
                // outcome" at different positions. Example: for ^((?:$)|.)* at
                // pos 0 of "a", $ fails, so the .-branch outranks the skip-exit
                // MATCH and we extend; at pos 1 (EOF), $ holds, the $-loop-back
                // MATCH outranks . and we stop.
                //
                // For each of the 16 possible posFlags values M, compute the
                // perStateOrder DFS skipping assertion edges whose requirements
                // aren't subset of M, then check whether any sym-bearing config
                // outranks accept in that order. If yes, NEVER_STOP (extend);
                // else 0 (stop). Accept-unreachable-under-M also gets NEVER_STOP
                // (no accept to stop on; runner's sam check filters anyway).
                if (perl && anyAccept) {
                    List<Config> seed = stateSeeds.get(s);
                    for (int M = 0; M < 16; M++) {
                        int[] perStateOrder = computePerStateOrder(seed, M);
                        int acceptOrder = perStateOrder[nfa.accept];
                        if (acceptOrder == -1) {
                            // Accept unreachable under M; sam check will fail too.
                            stateStopOnAcceptMask[s * 16 + M] = NEVER_STOP;
                            continue;
                        }
                        boolean higherPriSym = false;
                        for (int i = 0; i < cfgs.size(); i++) {
                            Config c = cfgs.get(i);
                            if (c.state == nfa.accept) continue;
                            if (symOut[c.state].length == 0) continue;
                            int o = perStateOrder[c.state];
                            if (o != -1 && o < acceptOrder) {
                                higherPriSym = true;
                                break;
                            }
                        }
                        stateStopOnAcceptMask[s * 16 + M] = higherPriSym ? NEVER_STOP : 0;
                    }
                }
            }
            // First pass: coalesce + fillGaps on every state's ranges, compute totals.
            int totalRanges = 0;
            int totalOpsSlots = 1;  // reserve ops[0] = OP_END for the "no ops" case (opsOff=0 means empty)
            for (int s = 0; s < n; s++) {
                DfaStateBuilder sb = builders.get(s);
                sb.coalesce();
                sb.fillGaps();
                totalRanges += sb.ranges.size();
                for (Range r : sb.ranges) {
                    if (r.ops != null && r.ops.length > 0) totalOpsSlots += r.ops.length + 1;  // +1 for OP_END
                }
                if (accept.get(s)) {
                    int[] f = finalRegops(states.get(s));
                    sb.finalOpsArr = f;
                    if (f != null && f.length > 0) totalOpsSlots += f.length + 1;
                }
            }

            // Second pass: allocate flat arrays and populate.
            int[] stateMeta = new int[n];
            int[] stateFinalOpsOff = new int[n];
            int[] flatRanges = new int[totalRanges * 5];
            int[] flatOps = new int[totalOpsSlots];
            flatOps[0] = OP_END;  // opsOff=0 means "empty block"
            int opsHead = 1;       // next free slot in flatOps (slot 0 reserved)
            int rangesHead = 0;    // next free slot in flatRanges (in units of 5 ints)
            int globalMaxReg = 2 * tags;  // at least r0 + R_f
            for (int s = 0; s < n; s++) {
                DfaStateBuilder sb = builders.get(s);
                int k = sb.ranges.size();
                int rangeBase = rangesHead;
                for (int i = 0; i < k; i++) {
                    Range r = sb.ranges.get(i);
                    int o = rangesHead * 5;
                    flatRanges[o]     = r.lo;
                    flatRanges[o + 1] = r.hi;
                    flatRanges[o + 2] = r.target;
                    int opsOff;
                    if (r.ops == null || r.ops.length == 0) {
                        opsOff = 0;  // shared "empty" sentinel at ops[0]
                    } else {
                        opsOff = opsHead;
                        for (int j = 0; j < r.ops.length; j += 3) {
                            flatOps[opsHead]     = r.ops[j];
                            flatOps[opsHead + 1] = r.ops[j + 1];
                            flatOps[opsHead + 2] = r.ops[j + 2];
                            globalMaxReg = Math.max(globalMaxReg, r.ops[j + 1] + 1);
                            if (r.ops[j] == OP_COPY) globalMaxReg = Math.max(globalMaxReg, r.ops[j + 2] + 1);
                            opsHead += 3;
                        }
                        flatOps[opsHead++] = OP_END;
                    }
                    flatRanges[o + 3] = opsOff;
                    flatRanges[o + 4] = r.requiredMask;
                    rangesHead++;
                }
                int finalOpsOff = 0;
                if (sb.finalOpsArr != null && sb.finalOpsArr.length > 0) {
                    finalOpsOff = opsHead;
                    int[] f = sb.finalOpsArr;
                    for (int j = 0; j < f.length; j += 3) {
                        flatOps[opsHead]     = f[j];
                        flatOps[opsHead + 1] = f[j + 1];
                        flatOps[opsHead + 2] = f[j + 2];
                        globalMaxReg = Math.max(globalMaxReg, f[j + 1] + 1);
                        if (f[j] == OP_COPY) globalMaxReg = Math.max(globalMaxReg, f[j + 2] + 1);
                        opsHead += 3;
                    }
                    flatOps[opsHead++] = OP_END;
                }
                boolean isAccept = accept.get(s);
                // Pack: (rangeBase << 9) | (rangeCount << 1) | acceptBit
                stateMeta[s] = (rangeBase << 9) | ((k & 0xFF) << 1) | (isAccept ? 1 : 0);
                stateFinalOpsOff[s] = finalOpsOff;
            }
            return new Tdfa(tags, nfa.groupCount, globalMaxReg, 0, n,
                    stateMeta, stateFinalOpsOff, flatRanges, flatOps,
                    stateEntryMask, stateAcceptMask, perl, stateStopOnAcceptMask);
        }

        static final boolean debug = Boolean.getBoolean("tdfa.debug");

        int maxReg(List<Config> configs) {
            int m = 2 * tags - 1;
            for (Config c : configs) for (int r : c.regs) if (r > m) m = r;
            return m;
        }

        // ---------------- Algorithm 3 building blocks ----------------

        /**
         * ε-closure via DFS with priority-ordered exploration (paper Algorithm 3).
         * Uses a stack (LIFO): children pushed in REVERSE priority order so the
         * highest-priority child is on top and popped first. This ensures the
         * leftmost-greedy preferred path is explored all the way down before
         * lower-priority alternatives.
         *
         * When the accept state is reached, remaining configs on the stack are
         * suppressed (they're all lower-priority — DFS has already explored
         * higher-priority paths). This prevents the DFA from having transitions
         * that follow lower-priority alternatives past an accept.
         */
        List<Config> epsilonClosure(List<Config> seed) {
            List<Config> out = new ArrayList<>();
            // For deterministic exploration we visit (state, mask) pairs — same NFA state
            // can appear with different assertion masks (e.g. loop entered 0 vs 1 times).
            // The visited set keyed only on state would wrongly suppress the second path.
            // Use a LongSet of (state << 32) | mask to dedupe within a closure.
            java.util.HashSet<Long> visitedSM = new java.util.HashSet<>();
            ArrayDeque<Config> stack = new ArrayDeque<>();
            // Push seed configs in reverse so the first seed config is on top (popped first)
            for (int i = seed.size() - 1; i >= 0; i--) {
                stack.push(seed.get(i));
            }
            while (!stack.isEmpty()) {
                Config c = stack.pop();
                long key = (((long) c.state) << 32) | (c.emptyMask & 0xFFFFFFFFL);
                if (!visitedSM.add(key)) continue;
                out.add(c);
                // Push children in REVERSE priority order.
                int[] eps = epsOut[c.state];
                for (int i = eps.length - 1; i >= 0; i--) {
                    int idx = eps[i];
                    int to = nfa.epsTo[idx];
                    int tag = nfa.epsTag[idx];
                    int edgeEmpty = nfa.epsEmptyMask[idx];
                    int newMask = c.emptyMask | edgeEmpty;
                    int[] newL;
                    if (tag == Tnfa.NO_TAG || tag < 0) {
                        newL = c.l;
                    } else {
                        newL = appendTag(c.l, tag);
                    }
                    // In POSIX mode, extend UTree path with ALL non-zero tags (incl. ntags).
                    int newPath = c.path;
                    if (!perl && tag != Tnfa.NO_TAG) {
                        newPath = posixUTree.extend(c.path, tag);
                    }
                    int childPri = perl ? Math.max(c.pri, nfa.epsPri[idx]) : 0;
                    stack.push(new Config(to, c.regs, c.h, newL, newMask, childPri, newPath, c.origin));
                }
            }
            return out;
        }

        // ---------------- BT19 POSIX closure ----------------

        /** Shared UTree across the entire DFA construction (BT19 §6). */
        UTree posixUTree = new UTree();
        /** Tag heights: height[t] = nesting depth of tag t's group.
         *  Group g → tags 2g-1, 2g → height g. */
        final int[] tagHeights;
        /** Per-DFA-state prectable (flat int[n*n], packed via PosixCompare.packCell). */
        final List<int[]> statePrectables = new ArrayList<>();

        /**
         * BT19 POSIX ε-closure with compare()-based winner selection (§7 closure_gtop).
         *
         * <p>Uses a worklist: pop a config, explore its ε-edges. For each target
         * (state, mask), if new, add it; if existing, compare paths — replace if
         * the new path wins (and re-explore children).
         *
         * <p>Dual-path encoding: {@code l} (regular tags only, for regops/history)
         * and {@code path} (UTree node, all tags including ntags, for compare()).
         * ntags NEVER enter {@code l} — this prevents regops from generating
         * spurious SET_NIL ops.
         *
         * @param seed              stepped configs from stepOnSymbol
         * @param oldPrectable       parent DFA state's prectable (null for initial)
         * @param parentClosureSize  size of parent closure (for indexing oldPrectable)
         * @return the closure configs in priority order
         */
        List<Config> closurePosix(List<Config> seed, int[] oldPrectable, int parentClosureSize) {
            return epsilonClosure(seed);
        }

        /**
         * Compare an existing config (at index {@code existingIdx}) against a
         * challenger. Returns {@code l} from PosixCompare: {@code <0} = existing
         * wins, {@code >0} = challenger wins, {@code 0} = tie.
         *
         * When heights are equal (h1 == h2), returns 0 (defer to DFS order) —
         * leftprec alone is insufficient for cross-alternative comparisons
         * where ε-edge priority should decide.
         */
        int posixCompareExisting(int existingIdx, Config challenger,
                                 List<Integer> originList, List<Integer> pathList,
                                 int[] oldPrectable, int parentClosureSize) {
            // TEMP: never replace — same as epsilonClosure DFS order.
            return 0;
        }

        /** Compute the prectable for this closure (O(n²) comparisons). */
        int[] computePrectable(List<Config> closure, int[] oldPrectable, int parentClosureSize) {
            int n = closure.size();
            int[] tbl = new int[n * n];
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) {
                    if (i == j) {
                        tbl[i * n + j] = PosixCompare.packCell(PosixCompare.MAX_RHO, 0);
                    } else {
                        long cmp = PosixCompare.compare(
                                closure.get(i).path, closure.get(j).path,
                                closure.get(i).origin, closure.get(j).origin,
                                posixUTree, tagHeights, oldPrectable, parentClosureSize);
                        int h1 = PosixCompare.h1(cmp);
                        int h2 = PosixCompare.h2(cmp);
                        int l = (h1 == h2) ? 0 : PosixCompare.l(cmp);
                        tbl[i * n + j] = PosixCompare.packCell(PosixCompare.h1(cmp), l);
                    }
                }
            }
            return tbl;
        }

        /**
         * Compute per-state DFS arrival order (re2j's densePcs semantics) for the
         * closure rooted at {@code seed}, assuming the cursor's position-flags
         * are exactly {@code posMask}. Assertion ε-edges whose required bits
         * aren't subset of {@code posMask} are skipped — mirroring re2j's
         * runtime closure, which kills threads failing EMPTY_WIDTH before they
         * can claim a densePcs slot.
         *
         * <p>Unlike {@link #epsilonClosure} which tracks (state, mask) pairs to
         * preserve assertion-mask info, this does strict per-state dedup: each
         * NFA state is visited at most once, the first time any of its masks
         * would be popped.
         *
         * <p>The resulting order matches re2j's recursive DFS — a state's entire
         * subtree is fully explored before any of its lower-priority siblings.
         * Without this, patterns like ((^|.)* ) get the wrong priority: alt (^|.)
         * is re-visited with mask=BEGIN_TEXT via the loop-back, and dotState
         * ends up "before" accept in (state,mask) arrival order even though
         * re2j (which visits alt once) places it after.
         *
         * @param posMask runtime position-flags (subset of
         *        {@code BEGIN_TEXT|END_TEXT|WORD_BOUNDARY|NO_WORD_BOUNDARY});
         *        0xF ("all assertions hold") recovers the pre-position-aware
         *        behavior.
         * @return int[] indexed by NFA state; value = arrival index (0-based),
         *         or -1 for unreachable states (incl. states only reachable via
         *         assertion edges whose requirements aren't in posMask).
         */
        int[] computePerStateOrder(List<Config> seed, int posMask) {
            int[] order = new int[nfa.stateCount];
            java.util.Arrays.fill(order, -1);
            boolean[] visited = new boolean[nfa.stateCount];
            ArrayDeque<Integer> stack = new ArrayDeque<>();
            for (int i = seed.size() - 1; i >= 0; i--) {
                stack.push(seed.get(i).state);
            }
            int counter = 0;
            while (!stack.isEmpty()) {
                int s = stack.pop();
                if (visited[s]) continue;
                visited[s] = true;
                order[s] = counter++;
                int[] eps = epsOut[s];
                for (int i = eps.length - 1; i >= 0; i--) {
                    int idx = eps[i];
                    int required = nfa.epsEmptyMask[idx];
                    if ((required & ~posMask) != 0) continue;  // assertion fails at this position
                    int to = nfa.epsTo[idx];
                    if (!visited[to]) stack.push(to);
                }
            }
            return order;
        }

        /**
         * Step every config in {@code configs} that has an outgoing symbol transition matching {@code a}.
         * Returns the stepped configs (with emptyMask reset to 0) and stores the intersection of
         * contributing source config masks into {@code requiredMaskOut[0]}.
         */
        List<Config> stepOnSymbol(List<Config> configs, char a, int[] requiredMaskOut) {
            // Perl leftmost-first: the closure's configs are in priority-ordered DFS arrival order.
            // If any config has reached the accept state, find the FIRST (best-priority) such config
            // and consider suppressing transitions from configs added AFTER it.
            //
            // Suppression is safe only if every post-accept config's emptyMask is a SUPERSET of the
            // accept config's emptyMask — meaning those lower-priority paths are gated by (at least)
            // the same assertions as the accept. Then wherever the accept fires (assertions hold),
            // the lower-priority transitions could also fire (so we MUST suppress to keep Perl
            // first-match); and wherever the accept doesn't fire (assertions don't hold), neither
            // can the lower-priority transitions (so suppression costs us nothing). When the rule
            // doesn't hold (e.g. accept requires `$` but a lower-priority alternative is ungated),
            // we must keep the lower-priority paths as fallback.
            int firstAcceptIdx = -1;
            int acceptEmptyMask = 0;
            boolean suppress = false;
            if (perl) {
                for (int i = 0; i < configs.size(); i++) {
                    Config c = configs.get(i);
                    if (c.state == nfa.accept) {
                        firstAcceptIdx = i;
                        acceptEmptyMask = c.emptyMask;
                        break;
                    }
                }
                if (firstAcceptIdx >= 0) {
                    suppress = true;
                    for (int i = firstAcceptIdx + 1; i < configs.size(); i++) {
                        Config c = configs.get(i);
                        if ((c.emptyMask & acceptEmptyMask) != acceptEmptyMask) {
                            suppress = false;
                            break;
                        }
                    }
                }
            }
            List<Config> out = new ArrayList<>();
            int intersection = Tnfa.BEGIN_TEXT | Tnfa.END_TEXT | Tnfa.WORD_BOUNDARY | Tnfa.NO_WORD_BOUNDARY;
            boolean any = false;
            for (int ci = 0; ci < configs.size(); ci++) {
                if (suppress && ci > firstAcceptIdx) {
                    break;  // suppress lower-priority paths past the first accept (Perl mode)
                }
                Config c = configs.get(ci);
                for (int idx : symOut[c.state]) {
                    CharClass cc = nfa.symClass[idx];
                    if (cc != null && cc.matches(a)) {
                        // emptyMask resets on step — assertions are position-bound, gated via requiredMask.
                    out.add(new Config(nfa.symTo[idx], c.regs, c.l, EMPTY, 0, c.pri, c.path, ci));
                    intersection &= c.emptyMask;
                        any = true;
                    }
                }
            }
            requiredMaskOut[0] = any ? intersection : 0;
            return out;
        }

        /**
         * Allocate registers and emit ops for the transition. {@code vmaps} is per-source-state
         * to allow sharing registers across transitions out of the same state with identical RHS.
         * {@code nextReg} is bumped globally so registers are unique across states.
         */
        int[] transitionRegops(List<Config> configs, int sourceStateId) {
            Map<Long, Integer> vmap = sourceVmaps.computeIfAbsent(sourceStateId, k -> new HashMap<>());
            List<int[]> opList = new ArrayList<>();
            // Track ops already emitted in THIS call (per-transition dedup, paper "if op not in O").
            Set<Long> emitted = new HashSet<>();
            for (int ci = 0; ci < configs.size(); ci++) {
                Config c = configs.get(ci);
                if (c.h == EMPTY || c.h.length == 0) continue;
                int[] newRegs = c.regs.clone();
                for (int t = 1; t <= tags; t++) {
                    int[] hist = history(c.h, t);
                    if (hist == null || hist.length == 0) continue;
                    int last = hist[hist.length - 1];
                    long key = (((long) t) << 32) | (last & 0xFFFFFFFFL);
                    Integer reg = vmap.get(key);
                    if (reg == null) {
                        reg = nextReg++;
                        vmap.put(key, reg);
                    }
                    long opKey = (((long) reg) << 32) | (last & 0xFFFFFFFFL);
                    if (!emitted.contains(opKey)) {
                        emitted.add(opKey);
                        if (last == TAG_POS) opList.add(new int[]{OP_SET_POS, reg, 0});
                        else opList.add(new int[]{OP_SET_NIL, reg, 0});
                    }
                    newRegs[t - 1] = reg;
                }
                configs.set(ci, new Config(c.state, newRegs, c.h, c.l, c.emptyMask, c.pri, c.path, c.origin));
            }
            return flatten(opList);
        }

        final Map<Integer, Map<Long, Integer>> sourceVmaps = new HashMap<>();

        int[] finalRegops(List<Config> configs) {
            List<int[]> opList = new ArrayList<>();
            for (Config c : configs) {
                if (c.state != nfa.accept) continue;
                for (int t = 1; t <= tags; t++) {
                    int[] hist = history(c.l, t);
                    int dst = finalRegisters[t - 1];
                    if (hist == null || hist.length == 0) {
                        opList.add(new int[]{OP_COPY, dst, c.regs[t - 1]});
                    } else {
                        int last = hist[hist.length - 1];
                        if (last == TAG_POS) opList.add(new int[]{OP_SET_POS, dst, 0});
                        else opList.add(new int[]{OP_SET_NIL, dst, 0});
                    }
                }
                break;
            }
            return flatten(opList);
        }

        int[] flatten(List<int[]> opList) {
            int[] flat = new int[opList.size() * 3];
            for (int i = 0; i < opList.size(); i++) {
                int[] op = opList.get(i);
                flat[i * 3] = op[0]; flat[i * 3 + 1] = op[1]; flat[i * 3 + 2] = op[2];
            }
            return flat;
        }

        static final class AddResult { final int targetId; final int[] ops; AddResult(int t, int[] o) { targetId=t; ops=o; } }

        AddResult addState(List<Config> configs, int[] ops, List<Config> seed) {
            DfaStateKey key = new DfaStateKey(configs, perl);
            Integer existing = stateIndex.get(key);
            if (existing != null) {
                // Identity on (states, lookahead). Registers may differ — translate via tryMap.
                int[] mapped = tryMap(configs, states.get(existing), ops);
                if (mapped != null) return new AddResult(existing, mapped);
                // Bijection failed (rare). Fall through to create a new state.
            }
            // Try mapping against every existing state with same key shape (different registers).
            for (int sid = 0; sid < states.size(); sid++) {
                if (sid == (existing == null ? -1 : existing)) continue;
                if (!sameKey(states.get(sid), configs)) continue;
                int[] mapped = tryMap(configs, states.get(sid), ops);
                if (mapped != null) {
                    return new AddResult(sid, mapped);
                }
            }
            int id = states.size();
            states.add(configs);
            stateSeeds.add(seed);
            stateIndex.put(key, id);
            builders.add(new DfaStateBuilder(id));
            for (Config c : configs) {
                if (c.state == nfa.accept) { accept.set(id); break; }
            }
            return new AddResult(id, ops);
        }

        /**
         * Attempt to map a candidate closure to an existing state's closure by registering
         * a bijection on their register vectors. Returns rewritten ops if mapping succeeds,
         * null otherwise. Implements paper §3 {@code map} function.
         */
        int[] tryMap(List<Config> newConfigs, List<Config> oldConfigs, int[] ops) {
            if (newConfigs.size() != oldConfigs.size()) return null;
            // Same NFA states + lookahead tags? (already checked via sameKey but double-check)
            for (int i = 0; i < newConfigs.size(); i++) {
                if (newConfigs.get(i).state != oldConfigs.get(i).state) return null;
                if (!Arrays.equals(newConfigs.get(i).l, oldConfigs.get(i).l)) return null;
            }
            // Build register bijection M: newReg -> oldReg, M': oldReg -> newReg
            Map<Integer, Integer> m = new HashMap<>(), mprime = new HashMap<>();
            for (int i = 0; i < newConfigs.size(); i++) {
                Config cn = newConfigs.get(i), co = oldConfigs.get(i);
                for (int t = 0; t < tags; t++) {
                    int[] hist = history(cn.l, t + 1);
                    if (hist != null && hist.length > 0) continue; // tag is set by transition op
                    int rn = cn.regs[t], ro = co.regs[t];
                    Integer mn = m.get(rn), mo = mprime.get(ro);
                    if (mn == null && mo == null) {
                        m.put(rn, ro); mprime.put(ro, rn);
                    } else if (mn == null || mo == null || mn != ro || mo != rn) {
                        return null;
                    }
                }
            }
            // Rewrite ops: replace each op's dst with M[dst]
            List<int[]> rewritten = new ArrayList<>();
            for (int i = 0; i < ops.length; i += 3) {
                int op = ops[i], dst = ops[i + 1], src = ops[i + 2];
                Integer mapped = m.get(dst);
                if (mapped == null) return null;
                rewritten.add(new int[]{op, mapped, src});
                m.remove(dst);
                mprime.remove(mapped);
            }
            // Prepend copy ops for remaining bijection pairs.
            // M maps newReg -> oldReg. Existing state expects tag values in its oldReg slots;
            // the new state's transition just wrote them into newReg slots. Copy oldReg <- newReg.
            for (Map.Entry<Integer, Integer> e : m.entrySet()) {
                int newReg = e.getKey(), oldReg = e.getValue();
                if (newReg != oldReg) rewritten.add(0, new int[]{OP_COPY, oldReg, newReg});
            }
            // Topological sort: copy ops must come before any op that reads their src.
            topologicalSort(rewritten);
            return flatten(rewritten);
        }

        boolean sameKey(List<Config> a, List<Config> b) {
            return new DfaStateKey(a, perl).equals(new DfaStateKey(b, perl));
        }

        /** Stabilize copy chains so reads happen before writes clobber their source.
         *  COPYs that read from a register must execute before any op (COPY or POS/NIL)
         *  that writes to that register. */
        void topologicalSort(List<int[]> ops) {
            boolean changed = true;
            int guard = 0;
            while (changed && guard++ < ops.size() * ops.size()) {
                changed = false;
                for (int i = 0; i < ops.size(); i++) {
                    int[] op = ops.get(i);
                    if (op[0] != OP_COPY) continue;
                    int src = op[2];
                    // Check if any EARLIER op writes to src — if so, the COPY must
                    // move before it (to read the OLD value before it's clobbered).
                    for (int j = 0; j < i; j++) {
                        int[] earlier = ops.get(j);
                        if (earlier[1] == src) {
                            // Move COPY to position j, shift everything else right.
                            for (int k = i; k > j; k--) ops.set(k, ops.get(k - 1));
                            ops.set(j, op);
                            changed = true;
                            break;
                        }
                    }
                }
            }
        }

        int[] history(int[] seq, int t) {
            if (seq == null || seq.length == 0) return null;
            int count = 0;
            for (int v : seq) if (Math.abs(v) == t) count++;
            if (count == 0) return null;
            int[] out = new int[count];
            int j = 0;
            for (int v : seq) if (Math.abs(v) == t) out[j++] = (v > 0) ? TAG_POS : TAG_NIL;
            return out;
        }

        int[] appendTag(int[] seq, int tag) {
            if (seq == EMPTY || seq.length == 0) return new int[]{tag};
            int[] out = new int[seq.length + 1];
            System.arraycopy(seq, 0, out, 0, seq.length);
            out[seq.length] = tag;
            return out;
        }

        static final int[] EMPTY = new int[0];
        static final int TAG_POS = 1;
        static final int TAG_NIL = -1;
    }

    static final class Config {
        final int state;
        final int[] regs;
        final int[] h;
        final int[] l;
        /** Zero-width assertion mask accumulated during the ε-closure that produced this config.
         *  Reset to 0 by {@link Compiler#stepOnSymbol}. */
        int emptyMask;
        /** Worst (highest) priority ε-edge taken to reach this config in the closure.
         *  Seed configs carry pri=0 (no edges taken). Always 0 in POSIX mode (unused).
         *  In Perl mode, used to suppress lower-priority paths past an accepting config. */
        final int pri;
        /** UTree node index — the tag-path prefix for POSIX comparison (BT19 §6).
         *  Carries ALL tags (including ntags) for compare(). Unused in Perl mode. */
        int path;
        /** Index of the parent config (in the parent DFA state's closure) that led to
         *  this config via stepOnSymbol. Used by compare() for cross-origin resolution.
         *  Unused in Perl mode. */
        int origin;
        Config(int state, int[] regs, int[] h, int[] l, int emptyMask) {
            this(state, regs, h, l, emptyMask, 0);
        }
        Config(int state, int[] regs, int[] h, int[] l, int emptyMask, int pri) {
            this(state, regs, h, l, emptyMask, pri, 0, 0);
        }
        Config(int state, int[] regs, int[] h, int[] l, int emptyMask, int pri, int path, int origin) {
            this.state = state; this.regs = regs; this.h = h; this.l = l; this.emptyMask = emptyMask;
            this.pri = pri; this.path = path; this.origin = origin;
        }
    }

    /** Canonical DFA state key: state ids + per-config (lookahead tags, emptyMask, pri).
     *  Two states with same key are candidates for {@code map} (register bijection).
     *  In Perl mode {@code includePri} adds per-config pri to the signature so that closures
     *  whose suppression behaviour would differ are not merged. */
    static final class DfaStateKey {
        final int[] sig;
        final int hash;
        DfaStateKey(List<Config> configs) { this(configs, false); }
        DfaStateKey(List<Config> configs, boolean includePri) {
            // Sort by state for canonical comparison (configs may be in DFS order)
            List<Config> sorted = new ArrayList<>(configs);
            sorted.sort(Comparator.comparingInt(c -> c.state));
            int total = 0;
            for (Config c : sorted) total += 3 + c.l.length + (includePri ? 1 : 0);
            int[] arr = new int[total];
            int i = 0;
            for (Config c : sorted) {
                arr[i++] = c.state;
                arr[i++] = c.l.length;
                for (int v : c.l) arr[i++] = v;
                arr[i++] = c.emptyMask;
                if (includePri) arr[i++] = c.pri;
            }
            this.sig = arr;
            this.hash = Arrays.hashCode(arr);
        }
        @Override public boolean equals(Object o) {
            return o instanceof DfaStateKey && Arrays.equals(sig, ((DfaStateKey) o).sig);
        }
        @Override public int hashCode() { return hash; }
    }

    static final class Range {
        final int lo, hi, target;
        final int[] ops;
        final int requiredMask;
        Range(int lo, int hi, int target, int[] ops, int requiredMask) {
            this.lo = lo; this.hi = hi; this.target = target; this.ops = ops; this.requiredMask = requiredMask;
        }
    }

    static final class DfaStateBuilder {
        final int id;
        final List<Range> ranges = new ArrayList<>();
        int[] finalOpsArr;  // populated during materialization
        DfaStateBuilder(int id) { this.id = id; }
        void addRange(int lo, int hi, int target, int[] ops, int requiredMask) {
            ranges.add(new Range(lo, hi, target, ops, requiredMask));
        }
        void coalesce() {
            ranges.sort(Comparator.comparingInt(r -> r.lo));
            if (ranges.size() <= 1) return;
            List<Range> out = new ArrayList<>();
            Range cur = ranges.get(0);
            for (int i = 1; i < ranges.size(); i++) {
                Range next = ranges.get(i);
                if (next.lo == cur.hi + 1 && next.target == cur.target
                        && Arrays.equals(next.ops, cur.ops)
                        && next.requiredMask == cur.requiredMask) {
                    cur = new Range(cur.lo, next.hi, cur.target, cur.ops, cur.requiredMask);
                } else {
                    out.add(cur); cur = next;
                }
            }
            out.add(cur);
            ranges.clear();
            ranges.addAll(out);
        }
        /** Insert target=-1 ranges in any gap so the ranges tile [0, 0xFFFF] contiguously. */
        void fillGaps() {
            ranges.sort(Comparator.comparingInt(r -> r.lo));
            List<Range> out = new ArrayList<>();
            int expected = 0;
            for (Range r : ranges) {
                if (r.lo > expected) {
                    out.add(new Range(expected, r.lo - 1, -1, null, 0));
                }
                out.add(r);
                expected = r.hi + 1;
            }
            if (expected <= 0xFFFF) {
                out.add(new Range(expected, 0xFFFF, -1, null, 0));
            }
            ranges.clear();
            ranges.addAll(out);
        }
    }
}
