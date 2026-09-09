package io.github.jemmix.tdfa.tdfa;

import io.github.jemmix.tdfa.ast.CharClass;
import io.github.jemmix.tdfa.tnfa.Tnfa;
import java.util.*;

// Tdfa's opcode/flag constants, referenced unqualified throughout (the code
// was moved verbatim out of Tdfa's nested Compiler class).
import static io.github.jemmix.tdfa.tdfa.Tdfa.DEBUG;
import static io.github.jemmix.tdfa.tdfa.Tdfa.MINIMIZE_ENABLED;
import static io.github.jemmix.tdfa.tdfa.Tdfa.MINIMIZE_MAX_STATES;
import static io.github.jemmix.tdfa.tdfa.Tdfa.NEVER_STOP;
import static io.github.jemmix.tdfa.tdfa.Tdfa.OP_COPY;
import static io.github.jemmix.tdfa.tdfa.Tdfa.OP_END;
import static io.github.jemmix.tdfa.tdfa.Tdfa.OP_SET_NIL;
import static io.github.jemmix.tdfa.tdfa.Tdfa.OP_SET_POS;
import static io.github.jemmix.tdfa.tdfa.Tdfa.REGOPT_ENABLED;
import static io.github.jemmix.tdfa.tdfa.Tdfa.REGOPT_MAX_STATES;
import static io.github.jemmix.tdfa.tdfa.Tdfa.rangeCount;

/** Subset-construction compiler: TNFA in, Tdfa out (paper §3; regopt + minimization passes). */
final class TdfaCompiler {
            /** Hash-consed tag-history table backing Config.h/.l ids. */
        final HistTable hist = new HistTable();
        final Tnfa nfa;
        final int tags;
        /** Compile work budget: every unbounded loop ticks it (fuzzer-found
         *  nested-quantifier bombs churn fixpoints without growing output —
         *  the state/kernel caps never trip). */
        final WorkMeter meter = new WorkMeter(Long.getLong("tdfa.max.work", 1L << 32));
        int[][] epsOut;
        int[][] symOut;
        /** Per-state popped-mask bitsets for the closure's subsumption cut (see epsilonClosure). */
        long[] maskBitset;
        int[] maskEpoch;
        int epochCtr;
        final int[] initialRegisters;
        final int[] finalRegisters;
        /** Equivalence-class breakpoints across the BMP. */
        final int[] breakpoints;
        /** If true, leftmost-longest semantics (keep stepping past accepts); if false, Perl leftmost-first (suppress lower-priority paths past an accept). */
        final boolean longest;

        /**
         * Multimap from DFA-state shape key to the list of DFA-state IDs that
         * share that shape. The paper's {@code map}+{@code topological_sort}
         * dedup collapses states with identical (NFA-state-set, lookahead-tag,
         * emptyMask, pri) signature; register-renaming via
         * {@link TdfaStateIndex#tryMap}
         * handles the case where the same shape is reached with different
         * register assignments.
         *
         * <p>Storing ALL same-shape state IDs (not just the first one) keeps
         * {@link TdfaStateIndex#addState} expected-O(1) per call. With a single-entry map
         * (the prior design) addState had to fall back to an O(n²) scan over
         * all known states whenever the hash-bucket primary candidate failed
         * tryMap — or, worse, whenever the shape was brand-new (hash-miss),
         * because the fallback couldn't tell there was nothing to find. On
         * the 2 663-branch dictionary alternation that fallback fired
         * 227 M times (every call, never matching) and dominated compile
         * wall time (~13 s of ~14 s).
         */
        // God-file split (2026-09): state interning/dedup and final-variant
        // solving extracted verbatim into collaborators with an owner back-ref.
        final TdfaStateIndex index = new TdfaStateIndex(this);
        final TdfaFinalVariants variants = new TdfaFinalVariants(this);
        List<List<Config>> states = new ArrayList<>();
        /**
         * Tagless compiles only: after a state is processed, its closure is
         * packed here as arrival-ordered (state, emptyMask) pairs (2 ints per
         * config) and the boxed {@link #states} slot is nulled. The retained
         * boxed form cost ~48 B × configs (3.18 GB on the 234 K-state bomb);
         * the packed form is 8 B/config. Consumers that read a state's closure
         * after processing (tryMap order check, entry/accept masks,
         * stopOnAccept) branch on which form is present. Tagged compiles never
         * pack — regopt/fallback/POSIX machinery consumes the boxed closures.
         */
        List<int[]> packedKernels = new ArrayList<>();
            /**
         * Seed configs (pre-closure) for each DFA state, used to compute per-state
         * DFS order (stopOnAccept). Stored ONLY for accepting Perl-mode states:
         * as {@code int[]} of states (arrival order) on tagless compiles, as
         * {@code List<Config>} otherwise. Null for the rest.
         */
        List<Object> stateSeeds = new ArrayList<>();
        BitSet accept = new BitSet();
        BitSet processed = new BitSet();
        List<DfaStateBuilder> builders = new ArrayList<>();
        Deque<Integer> work = new ArrayDeque<>();
        /** Global register allocator counter; bumped monotonically across all states. */
        int nextReg;

        /**
         * Determinization budget — re2c's design verbatim (its src/dfa/determinization.cc:
         * "Abort if TDFA grows too fast (either in the number of states, or in the total
         * size of all state kernels which may have many TNFA substates)"; constants.h:
         * {@code MAX_DFA_STATES = 100*1000}, {@code MAX_DFA_SIZE = 50*1000*1000}). A
         * pattern whose DFA exceeds the budget fails compilation with a clean
         * "pattern too large" error instead of burning unbounded time/heap (the
         * reference implementation rejects e.g. two-site {@code [^]{0,16}x[^]{0,16}}
         * outright; ours determinizes that family compactly but still caps the
         * intrinsically-huge cross-products like rebar's
         * {@code [\s\S]{0,100}Result[\s\S]{0,100}} — a 200 K+-state minimal DFA).
         * Read per-compile (not cached) so tests can override; defaults leave the
         * largest legit in-corpus pattern (dictionary, 19.6 K pre-min states) 5x headroom.
         */
        final int maxStates = Integer.getInteger("tdfa.max.states", 100_000);
        /** Memory-bound: each kernel config is a live Config (~80 B boxed all-
         *  in: lists, intern table, builders). Measured: 6.4 M kernels peaks
         *  under 1 GB, 18 M exceeds it. 10 M keeps every measured legit shape
         *  (e.g. (a{1,50}){1,50} at 6.4 M) and clean-rejects nested-counted
         *  bombs on default heaps instead of OOM-ing. Raise via
         *  -Dtdfa.max.kernels (with heap) for heavier legitimate use. */
        final int maxKernelsTotal = Integer.getInteger("tdfa.max.kernels", 10_000_000);
        /** Per-kernel spike bound — the totals cap only counts AFTER addState,
         *  so one closure of a nested-counted bomb could exhaust the heap on
         *  its own. Checked while the closure is built. */
        final int maxClosure = Integer.getInteger("tdfa.max.closure", 100_000);
        /** Running sum of closure (kernel) sizes — re2c's kernels_total. */
        long kernelsTotal = 0;

        TdfaCompiler(Tnfa nfa, boolean longestMatch) {
            this.nfa = nfa;
            this.tags = nfa.tagCount;
            this.epsOut = sortedOutgoing(nfa.epsFrom, nfa.epsPri);
            this.symOut = plainOutgoing(nfa.symFrom);
            this.maskBitset = new long[nfa.stateCount];
            this.maskEpoch = new int[nfa.stateCount];
            this.initialRegisters = new int[tags];
            this.finalRegisters = new int[tags];
            for (int t = 0; t < tags; t++) initialRegisters[t] = t;
            for (int t = 0; t < tags; t++) finalRegisters[t] = tags + t;
            this.breakpoints = computeBreakpoints();
            this.longest = longestMatch;
            // Per-cell active symbol-edge sets (see rangeActiveEdges). Each class range
            // [lo, hi] covers a contiguous run of breakpoint cells: lo and hi+1 are
            // themselves breakpoints (they are boundaries of this very class), so the
            // run is exactly [bpIdx(lo), bpIdx(hi+1)-1] — hence one cc.matches probe
            // per cell representative suffices here. Identical sets are interned to a
            // shared id (activeSetId) so the determinize sweep can cache results per
            // distinct set instead of per adjacent cell: the sets interleave along the
            // codepoint line (letter / space / other cells), so adjacency-only reuse
            // would never fire.
            int cells = breakpoints.length - 1;
            int edgeCount = nfa.symClass.length;
            int words = (edgeCount + 63) >> 6;
            this.rangeActiveEdges = new long[cells][];
            this.rangeSameEdges = new boolean[cells];
            this.activeSetId = new int[cells];
            this.cellCount = cells;
            long[] prevBits = null;
            java.util.ArrayList<long[]> distinctSets = new java.util.ArrayList<>();
            for (int bi = 0; bi < cells; bi++) {
                long[] bits = new long[words];
                for (int idx = 0; idx < edgeCount; idx++) {
                    CharClass cc = nfa.symClass[idx];
                    if (cc != null && cc.matches(breakpoints[bi])) bits[idx >> 6] |= 1L << (idx & 63);
                }
                rangeActiveEdges[bi] = bits;
                if (bi > 0) rangeSameEdges[bi] = java.util.Arrays.equals(bits, prevBits);
                int id = -1;
                for (int d = 0; d < distinctSets.size(); d++) {
                    if (java.util.Arrays.equals(bits, distinctSets.get(d))) { id = d; break; }
                }
                if (id < 0) { id = distinctSets.size(); distinctSets.add(bits); }
                activeSetId[bi] = id;
                prevBits = bits;
            }
            this.activeSetCount = distinctSets.size();
        }

        /** Number of breakpoint cells (cells = equivalence classes between adjacent breakpoints). */
        final int cellCount;
        /** Per breakpoint cell: bitset of active symbol-edge ids (edges whose class matches the cell's representative). */
        long[][] rangeActiveEdges;
        /** rangeSameEdges[bi] == true iff cell bi's active edge set equals cell bi-1's. */
        final boolean[] rangeSameEdges;
        /** Interned id of cell bi's active edge set (equal sets share the id). */
        int[] activeSetId;
        /** Number of distinct active edge sets. */
        final int activeSetCount;

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

        /** Compute breakpoints: every codepoint where some NFA CharClass boundary occurs. */
        int[] computeBreakpoints() {
            TreeSet<Integer> bps = new TreeSet<>();
            bps.add(0);
            bps.add(0x110000); // sentinel upper bound (exclusive)
            for (CharClass cc : nfa.symClass) {
                if (cc == null) continue;
                for (int r = 0; r < cc.ranges.length; r += 2) {
                    int lo = cc.ranges[r], hi = cc.ranges[r + 1];
                    bps.add(lo);
                    int after = hi + 1;
                    if (after <= 0x10FFFF) bps.add(after);
                }
            }
            int[] arr = new int[bps.size()];
            int i = 0;
            for (int b : bps) arr[i++] = b;
            return arr;
        }

        Tdfa compile() { return compile(null); }

        Tdfa compile(io.github.jemmix.tdfa.core.CompileObserver observer) {
            final io.github.jemmix.tdfa.core.CompileObserver obs =
                    observer != null ? observer : io.github.jemmix.tdfa.core.CompileObserver.NONE;
            long tDet = System.nanoTime();
            nextReg = 2 * tags;
            if (debug) System.err.println("[tdfa] tags=" + tags + " breakpoints=" + breakpoints.length);
            List<Config> initSeed = java.util.Collections.unmodifiableList(java.util.Arrays.asList(
                    new Config(nfa.start, initialRegisters, HistTable.EMPTY_ID, HistTable.EMPTY_ID, 0)));
            List<Config> initClosure = epsilonClosure(initSeed);
            int startId = index.addState(initClosure, null, initSeed).targetId;
            work.push(startId);

            int[] requiredMaskOut = new int[1];
            while (!work.isEmpty()) {
                meter.tick();
                int sid = work.pop();
                if (processed.get(sid)) continue;
                processed.set(sid);
                List<Config> cur = states.get(sid);
                if (debug) {
                    System.err.println("[tdfa] processing state " + sid + " configs:");
                    for (Config c : cur) System.err.println("    state=" + c.state + " l=" + Arrays.toString(hist.content(c.l)) + " regs=" + Arrays.toString(c.regs) + " mask=" + c.emptyMask);
                }
                // Assertion-context split (assertions into the alphabet, by
                // construction). The runtime posFlags M decides which closure
                // configs are alive (emptyMask ⊆ M). We step per DISTINCT
                // live-set — the closure filtered to alive configs, kept in
                // true closure-priority order — so every target state is both
                // liveness-complete (no continuation silently dropped) and
                // priority-correct (the kernel order feeds the stop-on-accept
                // table and final-ops variants). The context's OR-mask rides
                // its ranges; contexts are emitted most-specific first and the
                // runner's lowest-index-first scan among mask-satisfied
                // entries resolves overlaps.
                //
                // This replaces the former own/subset mask-group split whose
                // targets were lopsided: subset-appended configs landed AFTER
                // the own group in the kernel (priority inversion — the greedy
                // class-continue lost to a lower-priority \b\W exit and the
                // stop table stopped early) while less-specific groups dropped
                // gated continuations (the a*(^a) band-aid that the mask
                // specificity sort papered over at runtime).
                List<Integer> ctxMasks = null;   // distinct nonzero masks when >1 relevant
                for (Config c : cur) {
                    if (c.emptyMask != 0) {
                        if (ctxMasks == null) ctxMasks = new ArrayList<>(4);
                        boolean found = false;
                        for (int m : ctxMasks) if (m == c.emptyMask) { found = true; break; }
                        if (!found) ctxMasks.add(c.emptyMask);
                    }
                }
                List<int[]> ctxList = new ArrayList<>(4);      // {orMask, coverage} per context
                List<List<Config>> ctxInputs = new ArrayList<>(4);
                if (ctxMasks == null || ctxMasks.isEmpty()
                        || (ctxMasks.size() == 1 && ctxMasks.contains(0))) {
                    ctxList.add(new int[]{0, 0});
                    ctxInputs.add(pruneBelowAccept(cur));      // uniform context: whole closure (pike-pruned)
                } else {
                    // Dedup live-sets by their alive-mask pattern over the 64 runtime
                    // M values. Mask-0 configs are alive under every M (their bit is
                    // folded into the pattern directly); a pattern with no configs at
                    // all is unreachable and skipped.
                    int k = ctxMasks.size();
                    boolean anyZero = false;
                    for (Config c : cur) if (c.emptyMask == 0) { anyZero = true; break; }
                    java.util.HashMap<Integer, Integer> patIdx = new java.util.HashMap<>(8);
                    for (int M = 0; M < 64; M++) {
                        int pat = anyZero ? 1 : 0, r = 0;
                        for (int i = 0; i < k; i++) {
                            int mi = ctxMasks.get(i);
                            if ((mi & ~M) == 0) { pat |= 2 << i; r |= mi; }
                        }
                        if (pat == 0 || patIdx.containsKey(pat)) continue;
                        patIdx.put(pat, ctxInputs.size());
                        List<Config> live = new ArrayList<>(cur.size());
                        for (Config c : cur) {
                            if (c.emptyMask == 0) { if (anyZero) live.add(c); continue; }
                            for (int i = 0; i < k; i++) {
                                if ((pat & (2 << i)) != 0 && c.emptyMask == ctxMasks.get(i)) { live.add(c); break; }
                            }
                        }
                        pruneBelowAcceptInPlace(live);
                        ctxList.add(new int[]{r, Integer.bitCount(pat)});
                        ctxInputs.add(live);
                    }
                    // Emit most coverage first (superset live-sets precede their
                    // subsets; incomparable patterns have disjoint M sets).
                    Integer[] order = new Integer[ctxList.size()];
                    for (int i = 0; i < order.length; i++) order[i] = i;
                    final List<int[]> cl = ctxList;
                    java.util.Arrays.sort(order, (x, y) -> Integer.compare(cl.get(y)[1], cl.get(x)[1]));
                    List<int[]> sortedCtx = new ArrayList<>(order.length);
                    List<List<Config>> sortedIn = new ArrayList<>(order.length);
                    for (int o : order) { sortedCtx.add(ctxList.get(o)); sortedIn.add(ctxInputs.get(o)); }
                    ctxList = sortedCtx; ctxInputs = sortedIn;
                }
                // Context-major, range-inner sweep with per-active-set result caching.
                // The stepped configs are a pure function of (stepInput, active edge
                // set), and the active edge set is a pure function of the breakpoint
                // cell — so cells sharing an interned active-set id (activeSetId[bi])
                // yield identical stepped lists, ε-closures, shape keys and addState
                // results. The expensive closure/key/addState pipeline runs at most
                // once per distinct active set per context, and the builder's
                // coalesce() later merges the same-target ranges. On wide-class
                // patterns ([\s\S]{0,100} etc.) this skips the large majority of
                // per-cell work; on narrow patterns every cell is distinct and the
                // cache degenerates to one entry per cell.
                // Context results per active set: res[ctx][set] = target state id,
                // -1 = stepped empty, 0 = not yet computed. Contexts run most-
                // specific first; live ranges emit immediately. A context that
                // steps EMPTY emits a DEAD marker (target -1, its ctxMask) for
                // cells where any LESS-specific context is live: at runtime M ⊇
                // ctxMask that context OWNS the position — its lack of a
                // transition means the walk dies there, and the marker blocks
                // the less-specific (wrong-context) range from firing.
                int nCtx2 = ctxInputs.size();
                int[][] ctxSetRes = new int[nCtx2][];
                for (int ci = 0; ci < nCtx2; ci++) {
                    List<Config> stepInput = ctxInputs.get(ci);
                    int ctxMask = ctxList.get(ci)[0];
                    int ownCount = stepInput.size();   // true-order list: every config is a priority competitor
                    int[] setRes = new int[activeSetCount];
                    java.util.Arrays.fill(setRes, 0);
                    ctxSetRes[ci] = setRes;
                    TdfaStateIndex.AddResult[] perSet = new TdfaStateIndex.AddResult[activeSetCount];
                    boolean[] perSetDone = new boolean[activeSetCount];
                    for (int bi = 0; bi < cellCount; bi++) {
                        int rangeLo = breakpoints[bi];
                        int rangeHi = breakpoints[bi + 1] - 1;
                        int setId = activeSetId[bi];
                        if (perSetDone[setId]) {
                            TdfaStateIndex.AddResult ar = perSet[setId];
                            if (ar != null) {
                                builders.get(sid).addRange(rangeLo, rangeHi, ar.targetId, ar.ops, ctxMask);
                            }
                            continue;
                        }
                        perSetDone[setId] = true;
                        List<Config> stepped = stepOnSymbol(stepInput, rangeActiveEdges[bi], requiredMaskOut, ownCount, ctxMask);
                        if (stepped.isEmpty()) { perSet[setId] = null; setRes[setId] = -1; continue; }
                        List<Config> closed = epsilonClosure(stepped);
                        if (debug && closed.size() > 100) System.err.println("[tdfa] state " + sid + " range " + rangeLo + ".." + rangeHi + " closure=" + closed.size());
                        int[] ops = variants.transitionRegops(closed, sid);
                        TdfaStateIndex.AddResult ar = index.addState(closed, ops, stepped);
                        if (debug) System.err.println("[tdfa] state " + sid + " on '" + (char) rangeLo + "' (" + rangeLo + ") -> " + ar.targetId + " ops.len=" + ops.length + " mask=" + ctxMask);
                        builders.get(sid).addRange(rangeLo, rangeHi, ar.targetId, ar.ops, ctxMask);
                        if (!processed.get(ar.targetId)) work.push(ar.targetId);
                        perSet[setId] = ar;
                        setRes[setId] = ar.targetId;
                    }
                }
                // Dead markers: only when overlaps exist (nCtx2 > 1) — a single
                // context owns every cell unambiguously.
                if (nCtx2 > 1) {
                    for (int bi = 0; bi < cellCount; bi++) {
                        int rangeLo = breakpoints[bi];
                        int rangeHi = breakpoints[bi + 1] - 1;
                        int setId = activeSetId[bi];
                        // for each EMPTY context: marker iff some LATER (less specific) context is live
                        for (int ci = 0; ci < nCtx2; ci++) {
                            if (ctxSetRes[ci][setId] != -1) continue;
                            for (int cj = ci + 1; cj < nCtx2; cj++) {
                                if (ctxSetRes[cj][setId] > 0) {
                                    builders.get(sid).addRange(rangeLo, rangeHi, -1, null, ctxList.get(ci)[0]);
                                    if (debug) System.err.println("[tdfa] state " + sid + " cell " + rangeLo + ".." + rangeHi + " DEAD marker mask=" + Integer.toBinaryString(ctxList.get(ci)[0]));
                                    break;
                                }
                            }
                        }
                    }
                }
                // Tagless compiles: release the boxed closure of the just-processed
                // state — everything downstream reads the packed form (see tryMap and
                // the materialization pass). This is where the GBs go home.
                if (tags == 0) {
                    int[] packed = new int[cur.size() * 2];
                    for (int i = 0; i < cur.size(); i++) {
                        Config c = cur.get(i);
                        packed[i * 2] = c.state;
                        packed[i * 2 + 1] = c.emptyMask;
                    }
                    packedKernels.set(sid, packed);
                    states.set(sid, null);
                }
            }
            if (debug) System.err.println("[tdfa] total states=" + states.size() + " accept=" + accept.cardinality());

            int n = states.size();
            // Compute per-state entry/accept masks.
            int[] stateEntryMask = new int[n];
            int[] stateAcceptMask = new int[n];
            // Position-aware stopOnAcceptMask: int[state * 64 + posFlags] encodes
            // 0 (stop) or NEVER_STOP (don't stop). Position-aware because re2j's
            // densePcs priority depends on which assertion edges are live at the
            // current cursor position — see computePerStateOrder(seed, posMask).
            // 64 = 2^6 position-flag bits (BEGIN/END_TEXT, WORD/NO_WORD, ABS_BEGIN/ABS_END).
            int[] stateStopOnAcceptMask = new int[n * 64];
            java.util.Arrays.fill(stateStopOnAcceptMask, NEVER_STOP);
            int ALL_BITS = Tnfa.BEGIN_TEXT | Tnfa.END_TEXT | Tnfa.WORD_BOUNDARY | Tnfa.NO_WORD_BOUNDARY
                    | Tnfa.ABS_BEGIN | Tnfa.ABS_END;
            for (int s = 0; s < n; s++) {
                List<Config> cfgs = states.get(s);
                int[] pk = tags == 0 ? packedKernels.get(s) : null;   // packed form (tagless: cfgs == null)
                int cnt = pk != null ? pk.length >> 1 : cfgs.size();
                int entryIntersect = ALL_BITS;
                for (int i = 0; i < cnt; i++) entryIntersect &= pk != null ? pk[i * 2 + 1] : cfgs.get(i).emptyMask;
                stateEntryMask[s] = entryIntersect;
                int acceptIntersect = ALL_BITS;
                boolean anyAccept = false;
                for (int i = 0; i < cnt; i++) {
                    int st = pk != null ? pk[i * 2] : cfgs.get(i).state;
                    if (st == nfa.accept) {
                        acceptIntersect &= pk != null ? pk[i * 2 + 1] : cfgs.get(i).emptyMask;
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
                // For each of the 64 possible posFlags values M, compute the
                // perStateOrder DFS skipping assertion edges whose requirements
                // aren't subset of M, then check whether any sym-bearing config
                // outranks accept in that order. If yes, NEVER_STOP (extend);
                // else 0 (stop). Accept-unreachable-under-M also gets NEVER_STOP
                // (no accept to stop on; runner's sam check filters anyway).
                if (!longest && anyAccept) {
                    Object seed = stateSeeds.get(s);
                    if (debug) {
                        System.err.println("[stop] state " + s + " cnt=" + cnt);
                        for (int i = 0; i < cnt; i++) {
                            int st = pk != null ? pk[i * 2] : cfgs.get(i).state;
                            int em = pk != null ? pk[i * 2 + 1] : cfgs.get(i).emptyMask;
                            System.err.println("[stop]   cfg[" + i + "] nfa=" + st + (st == nfa.accept ? " ACCEPT" : "")
                                    + " mask=" + Integer.toBinaryString(em) + " symEdges=" + symOut[st].length);
                        }
                    }
                    for (int M = 0; M < 64; M++) {
                        // seed is int[] or List<Config> by construction (see seed decl)
                        int[] perStateOrder = seed instanceof int[]
                                ? computePerStateOrder((int[]) seed, M)
                                : computePerStateOrder((List<Config>) seed, M);
                        int acceptOrder = perStateOrder[nfa.accept];
                        if (acceptOrder == -1) {
                            // Accept unreachable under M; sam check will fail too.
                            stateStopOnAcceptMask[s * 64 + M] = NEVER_STOP;
                            continue;
                        }
                        boolean higherPriSym = false;
                        for (int i = 0; i < cnt; i++) {
                            int st = pk != null ? pk[i * 2] : cfgs.get(i).state;
                            if (st == nfa.accept) continue;
                            if (symOut[st].length == 0) continue;
                            int o = perStateOrder[st];
                            if (o != -1 && o < acceptOrder) {
                                higherPriSym = true;
                                break;
                            }
                        }
                        stateStopOnAcceptMask[s * 64 + M] = higherPriSym ? NEVER_STOP : 0;
                    }
                }
            }
            // Pre-pass: compute finalRegops for each accepting state up front, so the
            // CFG optimization (BT22 §6.3) can see them along with transition ops.
            for (int s = 0; s < n; s++) {
                if (accept.get(s)) {
                    List<Config> cfgs = states.get(s);
                    if (cfgs != null) {
                        builders.get(s).finalOpsArr = variants.finalRegops(cfgs);
                        variants.computeFinalVariants(builders.get(s), cfgs);
                    } else {
                        // Tagless accept state: boxed closure released, packed
                        // kernel only. Still compute the per-M variants — an
                        // accept state whose configs carry DIFFERENT emptyMasks
                        // is an OR of assertion-gated accepts, which the
                        // conjunctive stateAcceptMask (intersection) collapses
                        // to "always alive" (fuzz round 10: Z(?:\A|\B) matched
                        // at pos 1 where \A and \B both fail). finalRegopsOf
                        // returns empty ops when tags==0, so variants only
                        // encode aliveness — the byMask cell sign.
                        builders.get(s).finalOpsArr = null;
                        variants.computeFinalVariantsPacked(builders.get(s), packedKernels.get(s));
                    }
                }
            }
            // Determinize-lifetime data is dead from here: the stateIndex sigs,
            // (packed) kernels, seeds, eps/sym adjacency and scratch are all
            if (Boolean.getBoolean("tdfa.debug.closure")) {
                System.err.println("[det] states=" + states.size() + " kernelsTotal=" + kernelsTotal
                        + " ticks=" + meter.spent());
            }
            // downstream-unused, but as Compiler fields they would stay live
            // through materialization/minimization — the heap peak on giant
            // DFAs. Release ~0.8 GB (bomb) before the flat-array phase.
            index.stateIndex = null; states = null; packedKernels = null; stateSeeds = null;
            work = null; epsOut = null; symOut = null;
            rangeActiveEdges = null; activeSetId = null; processed = null;

            obs.stage(io.github.jemmix.tdfa.core.CompileObserver.Stage.DETERMINIZE,
                    System.nanoTime() - tDet, n);

            // === BT22 §6.3 register optimizations ===
            int finalRegBase = tags;  // default: working [0..T-1], final [T..2T-1]
            long tReg = System.nanoTime();
            if (REGOPT_ENABLED && tags > 0 && n > 1 && n <= REGOPT_MAX_STATES) {
                io.github.jemmix.tdfa.regopt.Cfg cfg = buildCfg(builders, accept, states, tags, nfa.groupCount, nextReg);
                io.github.jemmix.tdfa.regopt.Optimize.optimize(cfg, meter);
                cfgWriteBack(cfg, builders);
                finalRegBase = cfg.finalRegBase;
                if (debug) System.err.println("[tdfa] regopt: regs " + cfg.initialRegCount + " -> " + cfg.regCount
                        + " (finalRegBase=" + finalRegBase + ")"
                        + (cfg.dceRemovedOps > 0 ? " DCE removed " + cfg.dceRemovedOps + " ops" : ""));
                obs.stage(io.github.jemmix.tdfa.core.CompileObserver.Stage.REGOPT,
                        System.nanoTime() - tReg, cfg.regCount);
                obs.note("regopt", "regs " + cfg.initialRegCount + "->" + cfg.regCount);
            } else {
                obs.stage(io.github.jemmix.tdfa.core.CompileObserver.Stage.REGOPT,
                        System.nanoTime() - tReg, 2 * tags);
                obs.note("regopt", REGOPT_ENABLED ? "skipped (bounds)" : "disabled");
            }

            // First pass: coalesce + mask-specificity sort on every state's ranges,
            // compute totals. No gap filling: dead (target=-1) entries between live
            // ranges are semantically unnecessary — every consumer treats "no entry
            // matches" as death — and for wide Unicode classes they would double the
            // entry count (~700 live + ~700 gap fillers for \w under (?u)), halving
            // scan speed. sortByMaskSpecificity keeps ranges sorted by lo (mask bits
            // only break ties), so downstream sorted-order assumptions still hold.
            int totalRanges = 0;
            int totalOpsSlots = 1;  // reserve ops[0] = OP_END for the "no ops" case (opsOff=0 means empty)
            for (int s = 0; s < n; s++) {
                DfaStateBuilder sb = builders.get(s);
                sb.coalesce();
                sb.sortByMaskSpecificity();
                totalRanges += sb.ranges.size();
                for (Range r : sb.ranges) {
                    if (r.ops != null && r.ops.length > 0) totalOpsSlots += r.ops.length + 1;  // +1 for OP_END
                }
                if (accept.get(s)) {
                    int[] f = sb.finalOpsArr;  // populated in pre-pass above (possibly optimized by CFG)
                    if (f != null && f.length > 0) totalOpsSlots += f.length + 1;
                    if (sb.finalOpsVariants != null)
                        for (int[] v : sb.finalOpsVariants)
                            if (v != null && v.length > 0) totalOpsSlots += v.length + 1;
                }
            }

            // Second pass: allocate flat arrays and populate.
            int[] stateFinalOpsByMask = null;
            boolean[] finalVariantState = new boolean[n];
            int[] stateMeta = new int[n];
            int[] stateBase = new int[n];
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
                stateBase[s] = rangeBase;
                stateMeta[s] = ((k & 0xFFFF) << 1) | (isAccept ? 1 : 0);
                stateFinalOpsOff[s] = finalOpsOff;
                if (sb.finalOpsVariants != null && isAccept) {
                    finalVariantState[s] = true;
                    if (stateFinalOpsByMask == null) stateFinalOpsByMask = new int[n * 64];
                    int[] variantOff = new int[sb.finalOpsVariants.length];
                    for (int v = 0; v < variantOff.length; v++) {
                        int[] f = sb.finalOpsVariants[v];
                        variantOff[v] = 0;  // empty ops: accept fires, no ops
                        if (f != null && f.length > 0) {
                            variantOff[v] = opsHead;
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
                    }
                    for (int M = 0; M < 64; M++) {
                        int v = sb.finalMaskVariant[M];
                        stateFinalOpsByMask[s * 64 + M] = v < 0 ? -1 : variantOff[v];
                    }
                    if (stateFinalOpsOff[s] == 0 && variantOff.length > 0)
                        stateFinalOpsOff[s] = variantOff[0];  // sane default for non-runtime consumers
                }
            }
            // Builders (3.2 M Range objects on the bomb) are dead once the flat
            // arrays are populated; minimize/fallback only read the flat forms.
            builders = null; accept = null;
            if (stateFinalOpsByMask != null) {
                // The table is authoritative for every state when present:
                // uniform accepting states point all 64 cells at their φ;
                // non-accepting states stay all -1 (never read).
                for (int s = 0; s < n; s++) {
                    if (!finalVariantState[s]) {
                        int off = (stateMeta[s] & 1) != 0 ? stateFinalOpsOff[s] : -1;
                        java.util.Arrays.fill(stateFinalOpsByMask, s * 64, s * 64 + 64, off);
                    }
                }
            }

            // === Minimize via register-aware Moore's algorithm (paper §6.2.2 Minimization) ===
            // Treat transitions on the same symbol but with different register ops as different
            // transitions. Op sequences are interned to unique numeric IDs for O(1) comparison
            // (paper: "operation sequences are inserted into a hash map and represented with
            // unique numeric identifiers"). Comparison may have false negatives (non-identical
            // but semantically equivalent op lists), but that only yields a suboptimal — not
            // incorrect — minimization. Apply after register optimizations for best results.
            int stateCount = n;
            int[] minMeta = stateMeta, minBase = stateBase, minFinalOpsOff = stateFinalOpsOff,
                    minRanges = flatRanges, minEntryMask = stateEntryMask,
                    minAcceptMask = stateAcceptMask, minStopMask = stateStopOnAcceptMask,
                    minFinalOpsByMask = stateFinalOpsByMask;
            long tMin = System.nanoTime();
            if (MINIMIZE_ENABLED && n > 1 && n <= MINIMIZE_MAX_STATES) {
                DfaMinimizer m = new DfaMinimizer(n, stateMeta, stateBase, stateFinalOpsOff,
                        flatRanges, flatOps, stateEntryMask, stateAcceptMask,
                        stateStopOnAcceptMask, stateFinalOpsByMask, longest);
                int[] partition = m.computePartition();
                int newN = 0;
                for (int p : partition) newN = Math.max(newN, p + 1);
                if (newN < n) {
                    // Renumber so the start state's partition becomes state 0 (preserves invariant).
                    int[] renum = new int[newN];
                    java.util.Arrays.fill(renum, -1);
                    int nextId = 0;
                    for (int s = 0; s < n; s++) {
                        int p = partition[s];
                        if (renum[p] == -1) renum[p] = nextId++;
                    }
                    newN = nextId;
                    int[] rep = new int[newN];
                    java.util.Arrays.fill(rep, -1);
                    for (int s = 0; s < n; s++) {
                        int g = renum[partition[s]];
                        partition[s] = g;
                        if (rep[g] == -1) rep[g] = s;
                    }
                    int newTotalRanges = 0;
                    for (int g = 0; g < newN; g++) newTotalRanges += rangeCount(stateMeta[rep[g]]);
                    minMeta = new int[newN];
                    minBase = new int[newN];
                    minFinalOpsOff = new int[newN];
                    minEntryMask = new int[newN];
                    minAcceptMask = new int[newN];
                    minStopMask = new int[newN * 64];
                    minRanges = new int[newTotalRanges * 5];
                    if (stateFinalOpsByMask != null) minFinalOpsByMask = new int[newN * 64];
                    if (longest) java.util.Arrays.fill(minStopMask, NEVER_STOP);
                    int minRangesHead = 0;
                    for (int g = 0; g < newN; g++) {
                        int r = rep[g];
                        minMeta[g] = stateMeta[r];
                        minBase[g] = minRangesHead;
                        minFinalOpsOff[g] = stateFinalOpsOff[r];
                        minEntryMask[g] = stateEntryMask[r];
                        minAcceptMask[g] = stateAcceptMask[r];
                        if (!longest) {
                            System.arraycopy(stateStopOnAcceptMask, r * 64, minStopMask, g * 64, 64);
                        }
                        if (minFinalOpsByMask != null) {
                            System.arraycopy(stateFinalOpsByMask, r * 64, minFinalOpsByMask, g * 64, 64);
                        }
                        int base = stateBase[r];
                        int count = rangeCount(stateMeta[r]);
                        for (int i = 0; i < count; i++) {
                            int o = (base + i) * 5;
                            int no = minRangesHead * 5;
                            minRanges[no]     = flatRanges[o];
                            minRanges[no + 1] = flatRanges[o + 1];
                            int t = flatRanges[o + 2];
                            minRanges[no + 2] = (t == -1) ? -1 : partition[t];
                            minRanges[no + 3] = flatRanges[o + 3];
                            minRanges[no + 4] = flatRanges[o + 4];
                            minRangesHead++;
                        }
                    }
                    if (Tdfa.DEBUG) System.err.println("[tdfa] minimized: " + n + " -> " + newN + " states");
                    stateCount = newN;
                }
            }
            // === BT22 §6.2 fallback operations ===
            // Add backup COPY ops on transitions out of fallback states (those final
            // states with non-accepting paths), and generate ψ quasi-transitions
            // that route through the backups. Closes a latent POSIX capture bug
            // where stepping past an accept then falling back clobbers registers.
            obs.stage(io.github.jemmix.tdfa.core.CompileObserver.Stage.MINIMIZE,
                    System.nanoTime() - tMin, stateCount);

            // (BT22 §6.2 ψ/backup machinery deleted 2026-09, review Phase B:
            // it was generated, executed, and metered here, but NOTHING read
            // it at runtime — the lazy ψ replay in the runner was unsound and
            // removed long before; the tables were dead weight that could
            // push a pattern over the "too large" budget for no effect.)

            // Ensure per-state entries are sorted by lo (stable: equal-lo groups
            // keep their mask-specificity order). The builders emit sorted, but
            // the minimizer / regopt rewrite can reorder within a state; the
            // runtime's binary search over lo requires it.
            for (int s = 0; s < stateCount; s++) {
                int cnt = (minMeta[s] >>> 1) & 0xFFFF, b = minBase[s];
                boolean sorted = true;
                for (int i = 1; i < cnt; i++) {
                    if (minRanges[(b + i) * 5] < minRanges[(b + i - 1) * 5]) { sorted = false; break; }
                }
                if (sorted) continue;
                // Pack (lo << 32) | original index for a stable sort by lo, then
                // permute the 5-int entry groups in place.
                long[] keys = new long[cnt];
                for (int i = 0; i < cnt; i++) keys[i] = ((long) minRanges[(b + i) * 5] << 32) | i;
                java.util.Arrays.sort(keys);
                int[] tmp = new int[cnt * 5];
                for (int i = 0; i < cnt; i++) {
                    int src = (int) (keys[i] & 0xFFFFFFFFL) * 5;
                    System.arraycopy(minRanges, (b + src) * 5, tmp, i * 5, 5);
                }
                System.arraycopy(tmp, 0, minRanges, b * 5, cnt * 5);
            }
            // Rebuild the per-entry hi-prefix over the final (possibly remapped) arrays.
            int[] minHiPrefix = new int[minRanges.length / 5];
            for (int s = 0; s < stateCount; s++) {
                int cnt = (minMeta[s] >>> 1) & 0xFFFF, b = minBase[s], maxHi = Integer.MIN_VALUE;
                for (int i = 0; i < cnt; i++) {
                    int hi = minRanges[(b + i) * 5 + 1];
                    if (hi > maxHi) maxHi = hi;
                    minHiPrefix[b + i] = maxHi;
                }
            }
            // Materialization facts for memory attribution (observable via a
            // CompileObserver "tables" note). Byte sizes are the flat-array
            // payloads actually retained by the Tdfa (4 B per int slot).
            boolean perStateUniform = true;   // all 64 posFlags cells identical within each state
            boolean globalUniform = true;     // ... and identical across states
            {
                int acceptCnt = 0;
                for (int s = 0; s < stateCount; s++) if ((minMeta[s] & 1) != 0) acceptCnt++;
                int globalVal = minStopMask.length > 0 ? minStopMask[0] : 0;
                for (int s = 0; s < stateCount && perStateUniform; s++) {
                    int v0 = minStopMask[s * 64];
                    for (int m = 1; m < 64; m++) {
                        if (minStopMask[s * 64 + m] != v0) { perStateUniform = false; globalUniform = false; break; }
                    }
                    if (v0 != globalVal) globalUniform = false;
                }
                // Storage tier: POSIX -> neither (readers gate on Perl mode);
                // Perl + per-state-uniform -> byte[n]; general Perl -> int[n*64].
                byte[] uniformStop = null;
                int[] finalStop = null;
                if (!longest) {
                    if (perStateUniform) {
                        uniformStop = new byte[stateCount];
                        for (int s = 0; s < stateCount; s++) uniformStop[s] = minStopMask[s * 64] != 0 ? (byte) 1 : 0;
                    } else {
                        finalStop = minStopMask;
                    }
                }
                obs.note("tables", "states=" + stateCount + " ranges=" + (minRanges.length / 5)
                        + " accept=" + acceptCnt
                        + " bytes{ranges=" + (minRanges.length * 4L)
                        + ",stopMask=" + (uniformStop != null ? uniformStop.length
                        : finalStop != null ? finalStop.length * 4L : 0)
                        + ",entryMask=" + (minEntryMask.length * 4L)
                        + ",acceptMask=" + (minAcceptMask.length * 4L)
                        + ",ops=" + (flatOps.length * 4L)
                        + ",hiPrefix=" + (minHiPrefix.length * 4L)
                        + ",scalars=" + ((minMeta.length + minBase.length + minFinalOpsOff.length) * 4L + stateCount) + "}"
                        + " stopMaskUniform=" + (perStateUniform ? (globalUniform ? "global" : "perState") : "no"));
                return new Tdfa(tags, nfa.groupCount, nfa.namedGroups, globalMaxReg, finalRegBase, 0, stateCount,
                        minMeta, minBase, minFinalOpsOff, minFinalOpsByMask, minRanges, flatOps, minHiPrefix,
                        minEntryMask, minAcceptMask, longest, finalStop, uniformStop, nfa.multiline,
                        nfa.unicodeWordBoundary, nfa.wordRanges,
                        hasFixed(nfa.fixedBase) ? nfa.fixedBase : null,
                        hasFixed(nfa.fixedBase) ? nfa.fixedOffset : null);
            }
        }

        private static boolean hasFixed(int[] fixedBase) {
            if (fixedBase == null) return false;
            for (int i = 1; i < fixedBase.length; i++) if (fixedBase[i] != 0) return true;
            return false;
        }

        // ============================ CFG construction (BT22 §6.3) ============================

        /**
         * Build a {@link io.github.jemmix.tdfa.regopt.Cfg} from the post-determinization
         * {@code DfaStateBuilder} list. Each {@code (state, range-with-ops)} pair becomes
         * a BASIC block; each accepting state with non-empty {@code finalOpsArr} becomes
         * a FINAL block. Arcs skip zero-op transitions.
         *
         * <p>Lives inline in {@code Tdfa.Compiler} so it has direct access to the
         * package-private nested {@code DfaStateBuilder}/{@code Range} types
         * (avoiding reflection on synthetic nested-class field names).
         */
        io.github.jemmix.tdfa.regopt.Cfg buildCfg(List<DfaStateBuilder> builders, BitSet accept,
                                               @SuppressWarnings("unused") List<List<Config>> states,
                                               int tagCount, int groupCount, int initialRegCount) {
            io.github.jemmix.tdfa.regopt.Cfg cfg = new io.github.jemmix.tdfa.regopt.Cfg(tagCount, groupCount, initialRegCount);
            int n = builders.size();
            // First pass: create blocks.
            int[][] rangeBlockIds = new int[n][];
            @SuppressWarnings("unchecked")
            List<Integer>[] basicLeaving = new List[n];
            int[] finalBlockAt = new int[n];
            @SuppressWarnings("unchecked")
            List<Integer>[] finalVariantBlocks = new List[n];
            for (int s = 0; s < n; s++) finalVariantBlocks[s] = new ArrayList<>();
            java.util.Arrays.fill(finalBlockAt, -1);
            for (int s = 0; s < n; s++) basicLeaving[s] = new ArrayList<>();
            for (int s = 0; s < n; s++) {
                meter.tick();
                DfaStateBuilder sb = builders.get(s);
                rangeBlockIds[s] = new int[sb.ranges.size()];
                java.util.Arrays.fill(rangeBlockIds[s], -1);
                for (int r = 0; r < sb.ranges.size(); r++) {
                    meter.tick();   // per (state, range): pass 1 is real work, budget-visible
                    Range range = sb.ranges.get(r);
                    if (range.ops == null || range.ops.length == 0) continue;
                    io.github.jemmix.tdfa.regopt.Cfg.Block blk = cfg.newBlock(io.github.jemmix.tdfa.regopt.Cfg.BLOCK_BASIC, s, r);
                    decodeOps(range.ops, blk.ops);
                    rangeBlockIds[s][r] = cfg.blocks.size() - 1;
                    basicLeaving[s].add(rangeBlockIds[s][r]);
                }
                if (accept.get(s)) {
                    if (sb.finalOpsVariants != null) {
                        // Position-aware state: one FINAL block per φ variant
                        // (rangeIndex = variant index). No default block — the
                        // runtime selects per posFlags and never uses
                        // stateFinalOpsOff for this state.
                        for (int v = 0; v < sb.finalOpsVariants.length; v++) {
                            io.github.jemmix.tdfa.regopt.Cfg.Block vb = cfg.newBlock(io.github.jemmix.tdfa.regopt.Cfg.BLOCK_FINAL, s, v);
                            if (sb.finalOpsVariants[v] != null) decodeOps(sb.finalOpsVariants[v], vb.ops);
                            finalVariantBlocks[s].add(cfg.blocks.size() - 1);
                        }
                    } else {
                        io.github.jemmix.tdfa.regopt.Cfg.Block fb = cfg.newBlock(io.github.jemmix.tdfa.regopt.Cfg.BLOCK_FINAL, s, -1);
                        if (sb.finalOpsArr != null) decodeOps(sb.finalOpsArr, fb.ops);
                        finalBlockAt[s] = cfg.blocks.size() - 1;
                    }
                }
            }
            // Second pass: successor arcs. BASIC block at state s with range.target s' ->
            // all blocks (BASIC + FINAL) reachable from s' through zero-op transitions.
            for (io.github.jemmix.tdfa.regopt.Cfg.Block blk : cfg.blocks) {
                if (blk.kind != io.github.jemmix.tdfa.regopt.Cfg.BLOCK_BASIC) continue;
                int target = builders.get(blk.stateId).ranges.get(blk.rangeIndex).target;
                BitSet visited = new BitSet();
                java.util.ArrayDeque<Integer> frontier = new java.util.ArrayDeque<>();
                frontier.push(target);
                visited.set(target);
                while (!frontier.isEmpty()) {
                    meter.tick();   // per BFS node per block: the successor-arc pass
                    int t = frontier.pop();
                    blk.successors.addAll(basicLeaving[t]);
                    if (finalBlockAt[t] != -1) blk.successors.add(finalBlockAt[t]);
                    blk.successors.addAll(finalVariantBlocks[t]);
                    DfaStateBuilder tb = builders.get(t);
                    for (int r = 0; r < tb.ranges.size(); r++) {
                        Range tr = tb.ranges.get(r);
                        if (tr.ops != null && tr.ops.length > 0) continue;  // op-bearing: not skipped
                        if (tr.target < 0) continue;
                        if (!visited.get(tr.target)) {
                            visited.set(tr.target);
                            frontier.push(tr.target);
                        }
                    }
                }
            }
            return cfg;
        }

        private void decodeOps(int[] flat, List<io.github.jemmix.tdfa.regopt.Cfg.Op> out) {
            for (int i = 0; i < flat.length; i += 3) {
                meter.tick();   // per op: decode allocates the op objects — the former unticked copyOf hotspot
                int op = flat[i], dst = flat[i + 1], src = flat[i + 2];
                if (op == OP_END) break;
                switch (op) {
                    case OP_SET_POS: out.add(io.github.jemmix.tdfa.regopt.Cfg.Op.setPos(dst)); break;
                    case OP_SET_NIL: out.add(io.github.jemmix.tdfa.regopt.Cfg.Op.setNil(dst)); break;
                    case OP_COPY:    out.add(io.github.jemmix.tdfa.regopt.Cfg.Op.copy(dst, src)); break;
                    default: throw new IllegalStateException("bad op: " + op);
                }
            }
        }

        /** Flush optimized CFG ops back into the builders' Range/finalOpsArr slots. */
        void cfgWriteBack(io.github.jemmix.tdfa.regopt.Cfg cfg, List<DfaStateBuilder> builders) {
            for (io.github.jemmix.tdfa.regopt.Cfg.Block blk : cfg.blocks) {
                int[] encoded = encodeOps(blk.ops);
                DfaStateBuilder sb = builders.get(blk.stateId);
                if (blk.kind == io.github.jemmix.tdfa.regopt.Cfg.BLOCK_BASIC) {
                    sb.ranges.get(blk.rangeIndex).ops = encoded;
                } else if (blk.kind == io.github.jemmix.tdfa.regopt.Cfg.BLOCK_FINAL) {
                    if (blk.rangeIndex >= 0) sb.finalOpsVariants[blk.rangeIndex] = encoded;
                    else sb.finalOpsArr = encoded;
                }
            }
        }

        private static int[] encodeOps(List<io.github.jemmix.tdfa.regopt.Cfg.Op> ops) {
            if (ops.isEmpty()) return null;
            int[] flat = new int[ops.size() * 3];
            for (int i = 0; i < ops.size(); i++) {
                io.github.jemmix.tdfa.regopt.Cfg.Op op = ops.get(i);
                switch (op.kind) {
                    case io.github.jemmix.tdfa.regopt.Cfg.KIND_SET:
                        flat[i * 3] = op.value == io.github.jemmix.tdfa.regopt.Cfg.VAL_POS ? OP_SET_POS : OP_SET_NIL;
                        flat[i * 3 + 1] = op.dst;
                        flat[i * 3 + 2] = 0;
                        break;
                    case io.github.jemmix.tdfa.regopt.Cfg.KIND_COPY:
                        flat[i * 3] = OP_COPY;
                        flat[i * 3 + 1] = op.dst;
                        flat[i * 3 + 2] = op.src;
                        break;
                    default: throw new IllegalStateException("cannot encode op kind " + op.kind);
                }
            }
            return flat;
        }

        static final boolean debug = Boolean.getBoolean("tdfa.debug");

        // ---------------- Algorithm 3 building blocks ----------------

        /** True iff {@code popped} (bitset of popped mask values, bit m = mask m) has any submask of {@code m} set. */
        private static boolean submaskPopped(long popped, int m) {
            for (int sub = m; sub != 0; sub = (sub - 1) & m) {
                if ((popped & (1L << sub)) != 0) return true;
            }
            return (popped & 1L) != 0;   // the empty submask (mask 0) closes the loop
        }

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
            List<Config> out = new ArrayList<>(seed.size() * 2);
            // For deterministic exploration we visit (state, mask) pairs — same NFA state
            // can appear with different assertion masks (e.g. loop entered 0 vs 1 times).
            // A visited set keyed only on state would wrongly suppress the second path.
            // Open-addressing primitive (state<<32|mask) set: the boxed HashSet<Long> this
            // replaces allocated a Long per visited config per closure call — the #1
            // allocation hotspot on wide-class determinization. Initial size seed*2 (not
            // seed*8): closures grow it geometrically on demand, and the smaller initial
            // table saves ~2/3 of the per-call fill cost on the 1.4 M closure calls of
            // large determinizations.
            long[] visitedSM = new long[Math.max(16, Integer.highestOneBit(seed.size() * 2 - 1) << 1)];
            int visitedMask = visitedSM.length - 1;
            int visitedCount = 0;
            ArrayDeque<Config> stack = new ArrayDeque<>();
            // Push seed configs in reverse so the first seed config is on top (popped first).
            // A pre-push membership check skips seeds whose (state,mask) was already
            // POPPED (marked visited) — they would die at the pop check anyway. Seeds
            // still un-popped are pushed normally: the first POP of a key wins (DFS
            // priority order), so marking at push time would reverse the winner among
            // co-resident duplicates — see the child-loop comment below.
            for (int i = seed.size() - 1; i >= 0; i--) {
                Config c = seed.get(i);
                long key = visitKey(c.state, c.emptyMask);
                if (containsKey(visitedSM, visitedMask, key)) continue;
                stack.push(c);
            }
            if (epochCtr == Integer.MAX_VALUE) { java.util.Arrays.fill(maskEpoch, 0); epochCtr = 1; }
            final int epoch = ++epochCtr;
            while (!stack.isEmpty()) {
                meter.tick();
                Config c = stack.pop();
                long key = visitKey(c.state, c.emptyMask);
                int slot = (int) (mix(key) & visitedMask);
                while (visitedSM[slot] != 0) {
                    if (visitedSM[slot] == key) { slot = -1; break; }
                    slot = (slot + 1) & visitedMask;
                }
                if (slot < 0) continue;
                visitedSM[slot] = key;
                if (++visitedCount * 2 > visitedMask) { visitedSM = growVisited(visitedSM); visitedMask = visitedSM.length - 1; }
                if (maskEpoch[c.state] != epoch) { maskEpoch[c.state] = epoch; maskBitset[c.state] = 0L; }
                maskBitset[c.state] |= 1L << c.emptyMask;
                out.add(c);
                // Per-kernel spike bound: kernelsTotal is only counted after
                // addState, so a single closure of a nested-counted bomb could
                // otherwise exhaust the heap on its own.
                if (out.size() > maxClosure) {
                    throw new IllegalStateException("pattern too large: TDFA ε-closure exceeds "
                            + maxClosure + " configs (" + c.state + " reached; raise -Dtdfa.max.closure)");
                }
                // Push children in REVERSE priority order. Same contract as the seeds:
                // the pre-push check skips only already-POPPED keys; co-resident
                // duplicates are all pushed (each needs its own tag history — the
                // first pop decides which survives) and resolved at pop time.
                int[] eps = epsOut[c.state];
                for (int i = eps.length - 1; i >= 0; i--) {
                    int idx = eps[i];
                    int to = nfa.epsTo[idx];
                    // Subsumption cut — the exact form of the empty-iteration cut.
                    // A re-arrival (to, newMask) is SUBSUMED when an earlier,
                    // higher-priority variant (to, m') with m' ⊆ newMask was already
                    // popped: at every position where the re-arrival's path is alive
                    // (newMask ⊆ posFlags), the earlier variant is alive too, and from
                    // the same NFA state produces the same continuations — re2j's pike
                    // VM realizes this by per-position pc dedup (its threads carry no
                    // deferred masks, so the FIRST alive thread to reach a pc wins).
                    // For nullable loop bodies the re-entry around the ε-cycle carries
                    // the cycle's accumulated assertion bits — a superset of the entry
                    // variant's — so empty iterations die here ((?:.*?9{0,}\\b){1,} on
                    // "99x" matches [0,0) like the refs, not [0,3)). Incomparable-mask
                    // re-arrivals survive ((?:^|$)+ needs both the BEGIN and END
                    // junction variants); that is the difference from a blanket
                    // state-only dedup, and it is what the position-aware tables
                    // downstream rely on. Masks are 6 bits: exact submask check over a
                    // per-state popped-mask bitset.
                    int edgeEmpty = nfa.epsEmptyMask[idx];
                    int newMask = c.emptyMask | edgeEmpty;
                    if (maskEpoch[to] == epoch && submaskPopped(maskBitset[to], newMask)) continue;
                    long childKey = visitKey(to, newMask);
                    if (containsKey(visitedSM, visitedMask, childKey)) continue;
                    int tag = nfa.epsTag[idx];
                    int newL;
                    if (tag == Tnfa.NO_TAG || tag < 0) {
                        newL = c.l;
                    } else {
                        newL = hist.intern(appendTag(hist.content(c.l), tag));
                    }
                    // In longest-match mode all NFA states of the closure survive
                    // (no priority suppression); pri stays 0 there.
                    int childPri = !longest ? Math.max(c.pri, nfa.epsPri[idx]) : 0;
                    stack.push(new Config(to, c.regs, c.h, newL, newMask, childPri));
                }
            }
            return out;
        }

        /** Membership test against an open-addressing primitive long set. */
        private static boolean containsKey(long[] table, int mask, long key) {
            int slot = (int) (mix(key) & mask);
            while (table[slot] != 0) {
                if (table[slot] == key) return true;
                slot = (slot + 1) & mask;
            }
            return false;
        }

        /** Visited-set key for (state, mask). The +1 on the state word keeps
         *  every legal key nonzero: state 0 is Tnfa's ACCEPT (the first
         *  fresh() id), so the raw (state<<32)|mask encoding made the legal
         *  key (accept, 0) collide with the table's 0-as-EMPTY sentinel —
         *  (accept, 0) could never be marked visited, and two such configs
         *  pushed in the same expansion wave both survived into the kernel
         *  [review P1 #2]. */
        private static long visitKey(int state, int emptyMask) {
            return (((long) state + 1) << 32) | (emptyMask & 0xFFFFFFFFL);
        }

        /** Double an open-addressing long set, rehashing all live keys. */
        private static long[] growVisited(long[] table) {
            long[] grown = new long[table.length << 1];
            int gMask = grown.length - 1;
            for (long k : table) {
                if (k == 0) continue;
                int s2 = (int) (mix(k) & gMask);
                while (grown[s2] != 0) s2 = (s2 + 1) & gMask;
                grown[s2] = k;
            }
            return grown;
        }

        /** 64-bit finalizer for hash-set slots (splitmix-style). */
        static long mix(long key) {
            key ^= key >>> 33;
            key *= 0xff51afd7ed558ccdL;
            key ^= key >>> 33;
            return key;
        }

        // ---------------- BT19 §7 longest-match closure (closure_gtop) ----------------
        // Removed 2026-09: the POSIX prectable winner-selection machinery
        // (UTree/GtopCompare/prectables) was dormant scaffolding, resolved as
        // NOT-NEEDED for the re2j-parity contract (TODO.md, 2026-08-18);
        // design recoverable from git history and the BT19 paper.

        /** Scratch for computePerStateOrder: reused across the 64-mask loop and states. */
        private int[] psoOrder;
        private boolean[] psoVisited;
        private int[] psoStack;

        /**
         * Pike post-match thread pruning, determinized (Perl mode only): the
         * moment a live set contains an ACCEPT config, every config ranked
         * BELOW the first (highest-priority) alive accept is dead — any match
         * those threads reach is discarded by leftmost-first (re2j records
         * only the first Match), and non-matching continuations of them are
         * irrelevant. Without the prune, the walk extends past a recorded
         * accept via a lower-priority body and the runner's unconditional
         * lastAccept overwrite turns the result leftmost-LONGEST for that
         * window — fuzz round 11: {@code .+?\b[^\d]*} on "ß9" reported
         * [0,2) where re2j/sim/jdk report [0,1) (the lazy {@code .} body
         * matched '9' although ranked below the \b-gated accept at pos 1).
         *
         * <p>Configs ranked ABOVE the accept are kept: their later match
         * legitimately replaces the recorded one (the stop table's
         * higherPriSym NEVER_STOP exists for exactly them). Greedy shapes
         * are unaffected in practice — the body outranks the accept there.
         * The KERNEL itself is not pruned (state identity and the stop/final
         * tables see the full closure); only this live set's stepping input.
         */
        List<Config> pruneBelowAccept(List<Config> live) {
            if (longest) return live;
            int cut = -1;
            for (int i = 0; i < live.size(); i++) {
                if (live.get(i).state == nfa.accept) { cut = i; break; }
            }
            if (cut < 0 || cut == live.size() - 1) return live;   // nothing below the accept
            List<Config> pruned = new ArrayList<>(live.subList(0, cut + 1));
            return pruned;
        }

        /** In-place variant for freshly-built live lists. */
        void pruneBelowAcceptInPlace(List<Config> live) {
            if (longest) return;
            int cut = -1;
            for (int i = 0; i < live.size(); i++) {
                if (live.get(i).state == nfa.accept) { cut = i; break; }
            }
            if (cut >= 0 && cut < live.size() - 1) live.subList(cut + 1, live.size()).clear();
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

        int[] computePerStateOrder(int[] seedStates, int posMask) { return computePerStateOrderDfs(seedStates, posMask); }

        int[] computePerStateOrder(List<Config> seed, int posMask) {
            int[] seedStates = new int[seed.size()];
            for (int i = 0; i < seed.size(); i++) seedStates[i] = seed.get(i).state;
            return computePerStateOrderDfs(seedStates, posMask);
        }

        private int[] computePerStateOrderDfs(int[] seedStates, int posMask) {
            if (psoOrder == null || psoOrder.length < nfa.stateCount) {
                psoOrder = new int[nfa.stateCount];
                psoVisited = new boolean[nfa.stateCount];
                psoStack = new int[Math.max(nfa.stateCount * 2, 64)];
            }
            int[] order = psoOrder;
            boolean[] visited = psoVisited;
            int[] stackArr = psoStack;
            java.util.Arrays.fill(order, 0, nfa.stateCount, -1);
            java.util.Arrays.fill(visited, 0, nfa.stateCount, false);
            int sp = 0;
            for (int i = seedStates.length - 1; i >= 0; i--) {
                int s = seedStates[i];
                if (!visited[s]) stackArr[sp++] = s;
            }
            int counter = 0;
            while (sp > 0) {
                int s = stackArr[--sp];
                if (visited[s]) continue;
                visited[s] = true;
                order[s] = counter++;
                int[] eps = epsOut[s];
                for (int i = eps.length - 1; i >= 0; i--) {
                    int idx = eps[i];
                    int required = nfa.epsEmptyMask[idx];
                    if ((required & ~posMask) != 0) continue;  // assertion fails at this position
                    int to = nfa.epsTo[idx];
                    if (!visited[to]) {
                        if (sp == stackArr.length) {
                            stackArr = java.util.Arrays.copyOf(stackArr, sp * 2);
                            psoStack = stackArr;
                        }
                        stackArr[sp++] = to;
                    }
                }
            }
            return order;
        }

        /**
         * Step every config in {@code configs} that has an outgoing symbol transition matching {@code a}.
         * Returns the stepped configs (with emptyMask reset to 0) and stores the intersection of
         * contributing source config masks into {@code requiredMaskOut[0]}.
         *
         * <p>{@code ownCount} is the number of leading configs belonging to the mask group being
         * stepped (the rest are subset-mask configs appended for DFA liveness by the caller —
         * see the subset-inclusion comment in {@code compile()}). Appended configs are NOT
         * priority competitors of the group's own configs: their true priority position is
         * elsewhere in the closure. They must therefore neither veto accept-suppression nor
         * be suppressed by it. Without this boundary, a pattern like
         * {@code ^(?:x*|y)} loses Perl leftmost-first semantics: the (ungated, mask-0) start
         * config is appended after the (BEGIN_TEXT-gated) accept config, the superset safety
         * check fails, and the lower-priority {@code y} branch survives to extend the match
         * ([0,1] instead of the correct [0,0] — the empty {@code x*} alternative accepts first).
         */
        List<Config> stepOnSymbol(List<Config> configs, long[] activeEdges, int[] requiredMaskOut, int ownCount, int ctxMask) {
            // Perl leftmost-first: the closure's configs are in priority-ordered DFS arrival order.
            // If any config has reached the accept state, find the FIRST (best-priority) such config
            // and consider suppressing transitions from configs added AFTER it.
            //
            // Suppression is safe only if every post-accept OWN config's emptyMask is a SUPERSET of the
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
            if (!longest) {
                for (int i = 0; i < ownCount; i++) {
                    Config c = configs.get(i);
                    if (c.state == nfa.accept) {
                        firstAcceptIdx = i;
                        acceptEmptyMask = c.emptyMask;
                        break;
                    }
                }
                // Pike-cut (context-scoped): the config list is a live-set for ONE
                // assertion context (ctxMask); when the accept config is alive in
                // THIS context, every lower-priority config is cut exactly like a
                // pike VM cuts threads below a match-recording thread — they can
                // never produce the answer. Contexts where the accept is dead
                // (acceptEmptyMask ⊄ ctxMask) keep the fallbacks: no accept fired
                // there, so nothing was cut.
                if (firstAcceptIdx >= 0 && (acceptEmptyMask & ~ctxMask) == 0) {
                    suppress = true;
                    if (debug) System.err.println("[step] PIKE-CUT accept@" + firstAcceptIdx + " mask=" + Integer.toBinaryString(acceptEmptyMask) + " ctx=" + Integer.toBinaryString(ctxMask));
                }
            }
            List<Config> out = new ArrayList<>();
            int intersection = Tnfa.BEGIN_TEXT | Tnfa.END_TEXT | Tnfa.WORD_BOUNDARY | Tnfa.NO_WORD_BOUNDARY
                    | Tnfa.ABS_BEGIN | Tnfa.ABS_END;
            boolean any = false;
            for (int ci = 0; ci < configs.size(); ci++) {
                if (suppress && ci > firstAcceptIdx) {
                    continue;  // pike-cut: lower-priority paths past the first live accept
                               // can never win once that accept fires in this context
                }
                Config c = configs.get(ci);
                for (int idx : symOut[c.state]) {
                    meter.tick();   // per (config, symbol): step's cost is this loop
                    if ((activeEdges[idx >> 6] & (1L << (idx & 63))) != 0) {
                        // emptyMask resets on step — assertions are position-bound, gated via requiredMask.
                    out.add(new Config(nfa.symTo[idx], c.regs, c.l, HistTable.EMPTY_ID, 0, c.pri));
                    intersection &= c.emptyMask;
                        any = true;
                    }
                }
            }
            requiredMaskOut[0] = any ? intersection : 0;
            return out;
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
                    meter.tick();   // O(n²)-guarded: without ticks this is a
                                    // work-budget blind spot (fuzz hang family)
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

        @SuppressWarnings("ReferenceEquality")
        int[] appendTag(int[] seq, int tag) {
            // Reference compare against the shared EMPTY sentinel is the point
            // (hash-consed histories share one array; value-equality would
            // rescan every empty history).
            if (seq == EMPTY || seq.length == 0) return new int[]{tag};
            int[] out = new int[seq.length + 1];
            System.arraycopy(seq, 0, out, 0, seq.length);
            out[seq.length] = tag;
            return out;
        }

        static final int[] EMPTY = new int[0];
        static final int TAG_POS = 1;
    }
