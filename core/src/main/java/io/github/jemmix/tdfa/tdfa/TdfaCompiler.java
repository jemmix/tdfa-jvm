package io.github.jemmix.tdfa.tdfa;

import io.github.jemmix.tdfa.ast.CharClass;
import io.github.jemmix.tdfa.core.CompileObserver;
import io.github.jemmix.tdfa.tnfa.Tnfa;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.TreeSet;

import static io.github.jemmix.tdfa.tdfa.Tdfa.NEVER_STOP;
import static io.github.jemmix.tdfa.tdfa.Tdfa.OP_COPY;

/**
 * Determinization half of the TDFA compile (paper §3): subset construction
 * from the TNFA plus the per-state tables derived from the kernels —
 * entry/accept masks, the position-aware Perl stop-on-accept table, and the
 * final-φ variants of accepting states. Produces a {@link DeterminizedDfa};
 * register optimization, flat-array materialization and minimization run in
 * {@link TdfaMaterializer} on that value.
 *
 * <p>Lifetime: every field here is determinization-phase state (work list,
 * interning index, ε-closure scratch, kernels). The instance becomes
 * unreachable the moment {@link #compile} returns, so the memory-heavy
 * phase data is collectable before the flat-array phase begins — no
 * explicit release of anything, the phase boundary IS the lifetime
 * boundary.
 */
final class TdfaCompiler {
    static final int[] EMPTY = new int[0];
    static final int TAG_POS = 1;
    /**
     * Hash-consed tag-history table backing Config.h/.l ids.
     */
    final HistTable hist = new HistTable();

    final Tnfa nfa;
    final int tags;
    /**
     * Compile work budget: every unbounded loop ticks it (fuzzer-found
     * nested-quantifier bombs churn fixpoints without growing output —
     * the state/kernel caps never trip). The same meter also covers the
     * post-determinization stages (handed on by the compile entry points).
     */
    final WorkMeter meter;

    final int[] initialRegisters;
    final int[] finalRegisters;
    /**
     * Equivalence-class breakpoints across the BMP.
     */
    final int[] breakpoints;
    /**
     * If true, leftmost-longest semantics (keep stepping past accepts); if false, Perl leftmost-first (suppress lower-priority paths past an accept).
     */
    final boolean longest;
    /**
     * Pike-cut-free determinization (see {@link Tdfa#compileUnpruned}):
     * stepping follows every alive config, never cutting below an accept.
     * Used for the whole-match artifact; find() may share it iff the
     * {@link Tdfa#pikeCutMatters()} predicate comes out false.
     */
    final boolean unpruned;
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
     * addState had to fall back to an O(n²) scan over all known states
     * whenever the hash-bucket primary candidate failed tryMap — or, worse,
     * whenever the shape was brand-new (hash-miss), because the fallback
     * couldn't tell there was nothing to find. On the 2 663-branch
     * dictionary alternation that fallback fired 227 M times (every call,
     * never matching) and dominated compile wall time (~13 s of ~14 s).
     */
    final TdfaStateIndex index = new TdfaStateIndex(this);

    final TdfaFinalVariants variants = new TdfaFinalVariants(this);
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
     * All caps are DERIVED per compile from the RAM budget
     * ({@link Budgets#compileMemoryBytes()}) through {@link BudgetWeights}
     * — raise {@code -Dtdfa.budget.compile.memory} for heavier legitimate
     * use (with heap); defaults leave the largest legit in-corpus pattern
     * (dictionary, 19.6 K pre-min states) ample headroom. Perl-mode
     * compiles add the stop-table per-state weight (see {@link
     * Budgets#maxDfaStates(int)}); assigned in the constructor.
     */
    final int maxStates;
    /**
     * Memory-bound: each kernel config is a live Config (~80 B boxed all-
     * in: lists, intern table, builders — the measured weight behind
     * {@link BudgetWeights#KERNEL_CONFIG_BYTES}) PLUS its int[tags]
     * register slice (4 B/tag): a many-group pattern's configs scale
     * with the capture count. The cap keeps every measured legit shape
     * (e.g. (a{1,50}){1,50}'s family far below it) and clean-rejects
     * nested-counted bombs on the RAM budget instead of OOM-ing. The
     * check itself compares the WEIGHTED total against the compile RAM
     * budget (see kernelsWeighted).
     */
    final long maxKernelsTotal = Budgets.maxKernelConfigs();
    /**
     * Per-config weight this compile charges (see maxKernelsTotal).
     * Assigned in the constructor (needs the final tags count).
     */
    final int kernelConfigBytes;
    /**
     * Per-kernel spike bound in WEIGHTED bytes (see maxClosureConfigs):
     * the totals cap only counts AFTER addState, so one closure of a
     * nested-counted bomb could exhaust the heap on its own. Checked
     * while the closure is built.
     */
    final long maxClosureBytes;
    /**
     * Number of breakpoint cells (cells = equivalence classes between adjacent breakpoints).
     */
    final int cellCount;
    /**
     * Number of distinct active edge sets.
     */
    final int activeSetCount;
    /**
     * Per-compile read (knob policy: Tdfa javadoc).
     */
    final boolean debug = Boolean.getBoolean("tdfa.debug");
    int[][] epsOut;
    int[][] symOut;
    /**
     * Per-state popped-mask bitsets for the closure's subsumption cut (see epsilonClosure).
     */
    long[] maskBitset;

    int[] maskEpoch;
    int epochCtr;
    /**
     * One {@link Kernel} per DFA state: the subset-construction closure in
     * boxed form, or its (state, emptyMask) projection once a tagless
     * compile has finished the state.
     */
    final List<Kernel> kernels = new ArrayList<>();
    /**
     * Seed configs (pre-closure) for each DFA state, used to compute per-state
     * DFS order (stopOnAccept). Stored ONLY for accepting Perl-mode states:
     * as {@code int[]} of states (arrival order) on tagless compiles, as
     * {@code List<Config>} otherwise. Null for the rest — retaining seeds
     * for all states costs ~22 M extra Config objects on a 234 K-state
     * bounded-repeat determinization and nothing reads them.
     */
    final List<Object> stateSeeds = new ArrayList<>();

    final BitSet accept = new BitSet();
    final BitSet processed = new BitSet();
    final List<DfaStateBuilder> builders = new ArrayList<>();
    final Deque<Integer> work = new ArrayDeque<>();
    /**
     * Global register allocator counter; bumped monotonically across all states.
     */
    int nextReg;

    /**
     * Running sum of closure (kernel) sizes — re2c's kernels_total.
     */
    long kernelsTotal = 0;
    /**
     * Running sum of closure sizes in weighted bytes (per-config weight
     * is tag-aware; see kernelConfigBytes), against the compile RAM
     * budget. Tagless compiles: identical accounting to kernelsTotal.
     */
    long kernelsWeighted = 0;
    /**
     * Live boxed Range entries across all builders, in weighted bytes
     * (addRange coalesces inline, so this tracks the post-coalesce
     * live set), against the compile RAM budget.
     */
    long boxedRangeBytes = 0;
    /**
     * Per breakpoint cell: bitset of active symbol-edge ids (edges whose class matches the cell's representative).
     */
    long[][] rangeActiveEdges;
    /**
     * Interned id of cell bi's active edge set (equal sets share the id).
     */
    int[] activeSetId;
    /**
     * Scratch for computePerStateOrder: reused across the 64-mask loop and states.
     */
    private int[] psoOrder;

    private boolean[] psoVisited;
    private int[] psoStack;

    /**
     * @param sharedMeter the compile's work-budget ledger. One meter spans
     *        the whole compile: this class ticks it during determinization
     *        and hands the same instance on to the post-determinization
     *        stages, so a single CPU budget covers the entire pipeline.
     */
    TdfaCompiler(Tnfa nfa, boolean longestMatch, boolean unpruned, WorkMeter sharedMeter) {
        this.nfa = nfa;
        this.tags = nfa.tagCount;
        this.meter = sharedMeter;
        this.kernelConfigBytes = BudgetWeights.KERNEL_CONFIG_BYTES + BudgetWeights.KERNEL_REG_TAG_BYTES * this.tags;
        this.maxStates = Budgets.maxDfaStates(longestMatch ? 0 : BudgetWeights.STOP_TABLE_STATE_BYTES);
        this.epsOut = sortedOutgoing(nfa.epsFrom, nfa.epsPri);
        this.symOut = plainOutgoing(nfa.symFrom);
        this.maskBitset = new long[nfa.stateCount];
        this.maskEpoch = new int[nfa.stateCount];
        this.initialRegisters = new int[tags];
        this.finalRegisters = new int[tags];
        for (int t = 0; t < tags; t++) {
            initialRegisters[t] = t;
        }
        for (int t = 0; t < tags; t++) {
            finalRegisters[t] = tags + t;
        }
        this.breakpoints = computeBreakpoints();
        this.longest = longestMatch;
        this.unpruned = unpruned;
        this.maxClosureBytes = Budgets.compileMemoryBytes() / BudgetWeights.CLOSURE_SPIKE_DIVISOR;
        this.cellCount = breakpoints.length - 1;
        this.activeSetCount = precomputeActiveSets(cellCount);
    }

    /**
     * Per-cell active symbol-edge sets (see rangeActiveEdges). Each class range
     * [lo, hi] covers a contiguous run of breakpoint cells: lo and hi+1 are
     * themselves breakpoints (they are boundaries of this very class), so the
     * run is exactly [bpIdx(lo), bpIdx(hi+1)-1] — hence one cc.matches probe
     * per cell representative suffices here. Identical sets are interned to a
     * shared id (activeSetId) so the determinize sweep can cache results per
     * distinct set instead of per adjacent cell: the sets interleave along the
     * codepoint line (letter / space / other cells), so adjacency-only reuse
     * would never fire.
     *
     * <p>This precompute is O(cells × edges) cc.matches probes plus one
     * long[words] PER CELL — on class-heavy patterns (tens of
     * thousands of disjoint single-char alternations) that is
     * gigabytes of arrays and 10^10 probes. It is fully
     * budget-visible: the arrays are charged up front against the
     * compile RAM budget (before a single one is allocated), the
     * probe scan and set interning tick the work meter, and
     * interning is hash-based, so no quadratic rescans.
     *
     * @return the number of distinct active edge sets.
     */
    private int precomputeActiveSets(int cells) {
        int edgeCount = nfa.symClass.length;
        int words = (edgeCount + 63) >> 6;
        long activeSetBytes = (long) cells * ((long) words * 8L + BudgetWeights.ACTIVE_CELL_AUX_BYTES);
        long memBudget = Budgets.compileMemoryBytes();
        if (activeSetBytes > memBudget) {
            throw new IllegalStateException(
                "pattern too large: breakpoint active-set precompute exceeds the compile memory budget (" + cells
                    + " cells x " + words + " words = " + activeSetBytes + " weighted bytes — raise -D"
                    + Budgets.COMPILE_MEMORY_PROP + ")");
        }
        this.rangeActiveEdges = new long[cells][];
        this.activeSetId = new int[cells];
        HashMap<ActiveSetKey, Integer> distinctSets = new HashMap<>();
        for (int bi = 0; bi < cells; bi++) {
            meter.tick(edgeCount); // one cc.matches probe per edge per cell
            meter.tick(words); // set fill + hash + intern compare share
            long[] bits = new long[words];
            for (int idx = 0; idx < edgeCount; idx++) {
                CharClass cc = nfa.symClass[idx];
                if (cc != null && cc.matches(breakpoints[bi])) {
                    bits[idx >> 6] |= 1L << (idx & 63);
                }
            }
            rangeActiveEdges[bi] = bits;
            ActiveSetKey key = new ActiveSetKey(bits);
            Integer id = distinctSets.get(key);
            if (id == null) {
                id = distinctSets.size();
                distinctSets.put(key, id);
            }
            activeSetId[bi] = id;
        }
        return distinctSets.size();
    }

    /**
     * True iff {@code popped} (bitset of popped mask values, bit m = mask m) has any submask of {@code m} set.
     */
    private static boolean submaskPopped(long popped, int m) {
        for (int sub = m; sub != 0; sub = (sub - 1) & m) {
            if ((popped & (1L << sub)) != 0) {
                return true;
            }
        }
        return (popped & 1L) != 0; // the empty submask (mask 0) closes the loop
    }

    /**
     * Membership test against an open-addressing primitive long set.
     */
    private static boolean containsKey(long[] table, int mask, long key) {
        int slot = (int) (mix(key) & mask);
        while (table[slot] != 0) {
            if (table[slot] == key) {
                return true;
            }
            slot = (slot + 1) & mask;
        }
        return false;
    }

    /**
     * Visited-set key for (state, mask). The +1 on the state word keeps
     * every legal key nonzero: state 0 is Tnfa's ACCEPT (the first
     * fresh() id), so the raw (state<<32)|mask encoding made the legal
     * key (accept, 0) collide with the table's 0-as-EMPTY sentinel —
     * (accept, 0) could never be marked visited, and two such configs
     * pushed in the same expansion wave both survived into the kernel.
     */
    private static long visitKey(int state, int emptyMask) {
        return (((long) state + 1) << 32) | (emptyMask & 0xFFFFFFFFL);
    }

    /**
     * Double an open-addressing long set, rehashing all live keys.
     */
    private static long[] growVisited(long[] table) {
        long[] grown = new long[table.length << 1];
        int gMask = grown.length - 1;
        for (long k : table) {
            if (k == 0) {
                continue;
            }
            int s2 = (int) (mix(k) & gMask);
            while (grown[s2] != 0) {
                s2 = (s2 + 1) & gMask;
            }
            grown[s2] = k;
        }
        return grown;
    }

    /**
     * 64-bit finalizer for hash-set slots (splitmix-style).
     */
    static long mix(long key) {
        key ^= key >>> 33;
        key *= 0xff51afd7ed558ccdL;
        key ^= key >>> 33;
        return key;
    }

    /**
     * Charge one newly-live boxed Range (addRange returned true) against
     * the compile RAM budget.
     */
    void chargeRange() {
        if ((boxedRangeBytes += BudgetWeights.RANGE_BOXED_BYTES) > Budgets.compileMemoryBytes()) {
            throw new IllegalStateException(
                "pattern too large: transition range entries exceed the compile memory budget ("
                    + (boxedRangeBytes / BudgetWeights.RANGE_BOXED_BYTES) + " live entries, " + boxedRangeBytes
                    + " weighted bytes — raise -D" + Budgets.COMPILE_MEMORY_PROP + ")");
        }
    }

    /**
     * Outgoing ε-edge indices per NFA state, sorted by edge priority
     * (ascending) — the closure explores children in that order.
     */
    int[][] sortedOutgoing(int[] fromArr, int[] pri) {
        int[][] out = plainOutgoing(fromArr);
        for (int[] arr : out) {
            for (int a = 1; a < arr.length; a++) {
                int key = arr[a];
                int kp = pri[key];
                int b = a - 1;
                // Insertion sort is O(d²) in the out-degree d — a single
                // hub with a six-figure alternation fan-in makes this the
                // front of determinization. Every shift is a tick.
                while (b >= 0 && pri[arr[b]] > kp) {
                    meter.tick();
                    arr[b + 1] = arr[b];
                    b--;
                }
                arr[b + 1] = key;
            }
        }
        return out;
    }

    /**
     * Outgoing edge indices per NFA state, in edge-array order (no sort).
     */
    int[][] plainOutgoing(int[] fromArr) {
        int n = nfa.stateCount;
        int[] counts = new int[n];
        for (int f : fromArr) {
            counts[f]++;
        }
        int[][] out = new int[n][];
        for (int s = 0; s < n; s++) {
            out[s] = new int[counts[s]];
        }
        int[] idx = new int[n];
        for (int i = 0; i < fromArr.length; i++) {
            out[fromArr[i]][idx[fromArr[i]]++] = i;
        }
        return out;
    }

    /**
     * Compute breakpoints: every codepoint where some NFA CharClass boundary occurs.
     */
    int[] computeBreakpoints() {
        // The boxed TreeSet insert is O(log) per boundary and boundaries
        // scale with total class ranges — metered like the rest of the
        // front of determinization.
        TreeSet<Integer> bps = new TreeSet<>();
        bps.add(0);
        bps.add(0x110000); // sentinel upper bound (exclusive)
        for (CharClass cc : nfa.symClass) {
            if (cc == null) {
                continue;
            }
            meter.tick();
            for (int r = 0; r < cc.ranges.length; r += 2) {
                int lo = cc.ranges[r], hi = cc.ranges[r + 1];
                bps.add(lo);
                int after = hi + 1;
                if (after <= 0x10FFFF) {
                    bps.add(after);
                }
            }
        }
        int[] arr = new int[bps.size()];
        int i = 0;
        for (int b : bps) {
            arr[i++] = b;
        }
        return arr;
    }

    // ========================= compile pipeline =========================

    /**
     * Determinization phase of the compile. Runs the subset construction,
     * derives the per-state tables and solves the accepting states' final
     * φ ops, then reports the DETERMINIZE stage and returns the DFA shape.
     * When this returns, this compiler instance (and with it the kernels,
     * the interning index and the closure scratch) is unreachable.
     */
    DeterminizedDfa compile(CompileObserver observer) {
        final CompileObserver obs = observer != null ? observer : CompileObserver.NONE;
        long tDet = System.nanoTime();
        determinize();
        StateTables tables = computeStateTables();
        solveFinalOps();
        if (Boolean.getBoolean("tdfa.debug.closure")) {
            System.err.println(
                "[det] states=" + kernels.size() + " kernelsTotal=" + kernelsTotal + " ticks=" + meter.spent());
        }
        int n = kernels.size();
        obs.stage(CompileObserver.Stage.DETERMINIZE, System.nanoTime() - tDet, n);
        return new DeterminizedDfa(n, builders, accept, tables.entryMask, tables.acceptMask, tables.stopOnAcceptMask,
            tables.pikeCutMatters, nextReg);
    }

    /**
     * Subset-construction worklist (paper §3): pop an unprocessed state,
     * split its kernel into assertion live-sets and emit the transitions
     * of every live-set across all breakpoint cells; targets are interned
     * through the state index (the paper's {@code map}, extended by
     * register renaming in tryMap). LIFO order keeps target kernels
     * available in boxed form for tryMap until their turn comes.
     */
    private void determinize() {
        nextReg = 2 * tags;
        if (debug) {
            System.err.println("[tdfa] tags=" + tags + " breakpoints=" + breakpoints.length);
        }
        List<Config> initSeed = Collections.unmodifiableList(Collections
            .singletonList(new Config(nfa.start, initialRegisters, HistTable.EMPTY_ID, HistTable.EMPTY_ID, 0)));
        List<Config> initClosure = epsilonClosure(initSeed);
        int startId = index.addState(initClosure, null, initSeed).targetId;
        work.push(startId);
        while (!work.isEmpty()) {
            meter.tick();
            int sid = work.pop();
            if (processed.get(sid)) {
                continue;
            }
            processed.set(sid);
            processState(sid);
        }
        if (debug) {
            System.err.println("[tdfa] total states=" + kernels.size() + " accept=" + accept.cardinality());
        }
    }

    /**
     * Determinize one popped state: split the kernel into assertion
     * live-sets, emit every context's transitions (plus DEAD markers where
     * a more-specific context owns cells), then — on tagless compiles —
     * replace the boxed kernel with its packed projection (see
     * {@link Kernel#pack()}).
     */
    private void processState(int sid) {
        List<Config> cur = kernels.get(sid).boxed;
        if (debug) {
            System.err.println("[tdfa] processing state " + sid + " configs:");
            for (Config c : cur) {
                System.err.println("    state=" + c.state + " l=" + Arrays.toString(hist.content(c.l)) + " regs="
                    + Arrays.toString(c.regs) + " mask=" + c.emptyMask);
            }
        }
        List<LiveContext> ctxs = liveContexts(cur);
        int[][] ctxSetRes = emitTransitions(sid, ctxs);
        emitDeadMarkers(sid, ctxs, ctxSetRes);
        if (tags == 0) {
            kernels.get(sid).pack();
        }
    }

    /**
     * Split a closure into its distinct assertion live-sets. Zero-width
     * assertions enter the DFA as emptyMask bits on kernel configs; the
     * runtime posFlags M decide which configs are alive (emptyMask ⊆ M).
     * Stepping runs per DISTINCT live-set — the closure filtered to its
     * alive configs, kept in true closure-priority order — so every
     * target state is both liveness-complete (no continuation silently
     * dropped) and priority-correct (the kernel order feeds the
     * stop-on-accept table and final-ops variants). The context's OR-mask
     * rides its ranges; contexts are ordered most coverage first and the
     * runner's lowest-index-first scan among mask-satisfied entries
     * resolves overlaps.
     *
     * <p>Live-set dedup keys on the alive-mask PATTERN over the 64 runtime
     * M values (mask-0 configs are alive under every M and fold into the
     * pattern directly); a pattern with no configs at all is unreachable
     * and skipped. A closure without assertion-gated configs collapses
     * into the single whole-closure context (pike-pruned in Perl mode).
     */
    private List<LiveContext> liveContexts(List<Config> cur) {
        List<Integer> ctxMasks = null; // distinct nonzero masks when any relevant
        for (Config c : cur) {
            if (c.emptyMask != 0) {
                if (ctxMasks == null) {
                    ctxMasks = new ArrayList<>(4);
                }
                boolean found = false;
                for (int m : ctxMasks) {
                    if (m == c.emptyMask) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    ctxMasks.add(c.emptyMask);
                }
            }
        }
        List<LiveContext> ctxs = new ArrayList<>(4);
        if (ctxMasks == null || ctxMasks.isEmpty()) {
            ctxs.add(new LiveContext(0, 0, pruneBelowAccept(cur)));
            return ctxs;
        }
        int k = ctxMasks.size();
        boolean anyZero = false;
        for (Config c : cur) {
            if (c.emptyMask == 0) {
                anyZero = true;
                break;
            }
        }
        HashMap<Integer, Integer> patIdx = new HashMap<>(8);
        for (int M = 0; M < 64; M++) {
            int pat = anyZero ? 1 : 0, r = 0;
            for (int i = 0; i < k; i++) {
                int mi = ctxMasks.get(i);
                if ((mi & ~M) == 0) {
                    pat |= 2 << i;
                    r |= mi;
                }
            }
            if (pat == 0 || patIdx.containsKey(pat)) {
                continue;
            }
            patIdx.put(pat, ctxs.size());
            List<Config> live = new ArrayList<>(cur.size());
            for (Config c : cur) {
                if (c.emptyMask == 0) {
                    if (anyZero) {
                        live.add(c);
                    }
                    continue;
                }
                for (int i = 0; i < k; i++) {
                    if ((pat & (2 << i)) != 0 && c.emptyMask == ctxMasks.get(i)) {
                        live.add(c);
                        break;
                    }
                }
            }
            pruneBelowAcceptInPlace(live);
            ctxs.add(new LiveContext(r, Integer.bitCount(pat), live));
        }
        // Emit most coverage first: superset live-sets precede their
        // subsets; incomparable patterns have disjoint M sets.
        ctxs.sort((x, y) -> Integer.compare(y.coverage, x.coverage));
        return ctxs;
    }

    /**
     * Context-major, range-inner transition sweep with per-active-set
     * result caching. The stepped configs are a pure function of
     * (stepInput, active edge set) and the active edge set is a pure
     * function of the breakpoint cell — so cells sharing an interned
     * active-set id (activeSetId[bi]) yield identical stepped lists,
     * ε-closures, shape keys and addState results. The expensive
     * closure/regops/addState pipeline runs at most once per distinct
     * active set per context, and the builder's coalesce() later merges
     * the same-target ranges. On wide-class patterns
     * ([\s\S]{0,100} etc.) this skips the large majority of per-cell
     * work; on narrow patterns every cell is distinct and the cache
     * degenerates to one entry per cell.
     *
     * @return per-context results keyed by active-set id: target state
     *         id, -1 for stepped-empty, 0 for not-yet-computed. Contexts
     *         run most-specific first; live ranges emit immediately.
     */
    private int[][] emitTransitions(int sid, List<LiveContext> ctxs) {
        int nCtx = ctxs.size();
        int[][] ctxSetRes = new int[nCtx][];
        for (int ci = 0; ci < nCtx; ci++) {
            LiveContext ctx = ctxs.get(ci);
            int[] setRes = new int[activeSetCount];
            Arrays.fill(setRes, 0);
            ctxSetRes[ci] = setRes;
            TdfaStateIndex.AddResult[] perSet = new TdfaStateIndex.AddResult[activeSetCount];
            boolean[] perSetDone = new boolean[activeSetCount];
            for (int bi = 0; bi < cellCount; bi++) {
                // One tick per (context, cell) sweep step: the per-set
                // DEDUP fast path below still does real work per cell (an
                // addRange + charge), and the sweep is states × cells —
                // hundreds of thousands of states times tens of thousands
                // of cells must trip the budget, not just the slow path.
                meter.tick();
                int rangeLo = breakpoints[bi];
                int rangeHi = breakpoints[bi + 1] - 1;
                int setId = activeSetId[bi];
                if (perSetDone[setId]) {
                    TdfaStateIndex.AddResult ar = perSet[setId];
                    if (ar != null) {
                        if (builders.get(sid).addRange(rangeLo, rangeHi, ar.targetId, ar.ops, ctx.orMask)) {
                            chargeRange();
                        }
                    }
                    continue;
                }
                perSetDone[setId] = true;
                List<Config> stepped = stepOnSymbol(ctx.configs, rangeActiveEdges[bi], ctx.orMask);
                if (stepped.isEmpty()) {
                    perSet[setId] = null;
                    setRes[setId] = -1;
                    continue;
                }
                List<Config> closed = epsilonClosure(stepped);
                if (debug && closed.size() > 100) {
                    System.err.println(
                        "[tdfa] state " + sid + " range " + rangeLo + ".." + rangeHi + " closure=" + closed.size());
                }
                int[] ops = variants.transitionRegops(closed, sid);
                TdfaStateIndex.AddResult ar = index.addState(closed, ops, stepped);
                if (debug) {
                    System.err.println("[tdfa] state " + sid + " on '" + (char) rangeLo + "' (" + rangeLo + ") -> "
                        + ar.targetId + " ops.len=" + ops.length + " mask=" + ctx.orMask);
                }
                if (builders.get(sid).addRange(rangeLo, rangeHi, ar.targetId, ar.ops, ctx.orMask)) {
                    chargeRange();
                }
                if (!processed.get(ar.targetId)) {
                    work.push(ar.targetId);
                }
                perSet[setId] = ar;
                setRes[setId] = ar.targetId;
            }
        }
        return ctxSetRes;
    }

    /**
     * Emit DEAD markers for context overlaps. A context that steps EMPTY
     * on a cell emits a DEAD entry (target -1, its orMask) for cells where
     * any LESS-specific context is live: at runtime, when M ⊇ orMask that
     * context OWNS the position — its lack of a transition means the walk
     * dies there, and the marker blocks the less-specific
     * (wrong-context) range from firing. A single context owns every
     * cell unambiguously, so markers only exist when overlaps do
     * (nCtx &gt; 1).
     */
    private void emitDeadMarkers(int sid, List<LiveContext> ctxs, int[][] ctxSetRes) {
        int nCtx = ctxs.size();
        if (nCtx <= 1) {
            return;
        }
        for (int bi = 0; bi < cellCount; bi++) {
            meter.tick(); // cells × contexts marker scan — same sweep bound
            int rangeLo = breakpoints[bi];
            int rangeHi = breakpoints[bi + 1] - 1;
            int setId = activeSetId[bi];
            // for each EMPTY context: marker iff some LATER (less specific) context is live
            for (int ci = 0; ci < nCtx; ci++) {
                if (ctxSetRes[ci][setId] != -1) {
                    continue;
                }
                for (int cj = ci + 1; cj < nCtx; cj++) {
                    if (ctxSetRes[cj][setId] > 0) {
                        if (builders.get(sid).addRange(rangeLo, rangeHi, -1, null, ctxs.get(ci).orMask)) {
                            chargeRange();
                        }
                        if (debug) {
                            System.err.println("[tdfa] state " + sid + " cell " + rangeLo + ".." + rangeHi
                                + " DEAD marker mask=" + Integer.toBinaryString(ctxs.get(ci).orMask));
                        }
                        break;
                    }
                }
            }
        }
    }

    /**
     * Derive the per-state tables the runner reads off the artifact:
     * entryMask (assertions required to enter a state — the intersection
     * of its kernel's emptyMasks), acceptMask (assertions required to
     * declare a match — the intersection over the accept configs), and
     * the position-aware stop-on-accept table (Perl mode only; POSIX
     * keeps stepping past accepts and stores nothing). Also computes the
     * pike-cut hazard predicate.
     */
    private StateTables computeStateTables() {
        int n = kernels.size();
        int[] stateEntryMask = new int[n];
        int[] stateAcceptMask = new int[n];
        // int[state * 64 + posFlags] encodes 0 (stop) or NEVER_STOP (don't
        // stop), 64 = 2^6 position-flag bits (BEGIN/END_TEXT, WORD/NO_WORD,
        // ABS_BEGIN/ABS_END). POSIX (longest) mode never reads this table —
        // the artifact stores neither stop tier and every reader gates on
        // Perl mode — so the n*64 alloc/fill is pure churn there (~25 MB at
        // 100 K states) and is skipped entirely.
        int[] stateStopOnAcceptMask = longest ? null : new int[n * 64];
        if (stateStopOnAcceptMask != null) {
            Arrays.fill(stateStopOnAcceptMask, NEVER_STOP);
        }
        boolean pikeCutMatters = false;
        for (int s = 0; s < n; s++) {
            Kernel k = kernels.get(s);
            int cnt = k.size();
            int entryIntersect = Tnfa.BEGIN_TEXT | Tnfa.END_TEXT | Tnfa.WORD_BOUNDARY | Tnfa.NO_WORD_BOUNDARY
                | Tnfa.ABS_BEGIN | Tnfa.ABS_END;
            for (int i = 0; i < cnt; i++) {
                entryIntersect &= k.maskAt(i);
            }
            stateEntryMask[s] = entryIntersect;
            int acceptIntersect = Tnfa.BEGIN_TEXT | Tnfa.END_TEXT | Tnfa.WORD_BOUNDARY | Tnfa.NO_WORD_BOUNDARY
                | Tnfa.ABS_BEGIN | Tnfa.ABS_END;
            boolean anyAccept = false;
            for (int i = 0; i < cnt; i++) {
                if (k.stateAt(i) == nfa.accept) {
                    acceptIntersect &= k.maskAt(i);
                    anyAccept = true;
                }
            }
            stateAcceptMask[s] = anyAccept ? acceptIntersect : 0;
            if (!longest && anyAccept) {
                Object seed = stateSeeds.get(s);
                if (debug) {
                    System.err.println("[stop] state " + s + " cnt=" + cnt);
                    for (int i = 0; i < cnt; i++) {
                        int st = k.stateAt(i);
                        System.err.println("[stop]   cfg[" + i + "] nfa=" + st + (st == nfa.accept ? " ACCEPT" : "")
                            + " mask=" + Integer.toBinaryString(k.maskAt(i)) + " symEdges=" + symOut[st].length);
                    }
                }
                if (stateStopOnAcceptMask != null) {
                    System.arraycopy(stopRowFor(seed, k), 0, stateStopOnAcceptMask, s * 64, 64);
                }
                if (!unpruned && !pikeCutMatters) {
                    pikeCutMatters = pikeCutHazard(k);
                }
            }
        }
        return new StateTables(stateEntryMask, stateAcceptMask, stateStopOnAcceptMask, pikeCutMatters);
    }

    /**
     * Perl stop-on-accept decision for one accepting state, per posFlags
     * value M. The decision is position-aware because re2j's runtime
     * closure evaluates each assertion against the current cursor's cond
     * and kills failing threads before they can claim a densePcs slot —
     * so the same DFA state can have a different "highest-priority
     * outcome" at different positions. For each M, compute the kernel's
     * DFS arrival order (re2j's densePcs semantics) skipping assertion
     * edges whose requirements aren't subset of M, then check whether any
     * sym-bearing config outranks accept in that order: if yes the runner
     * should extend the match (NEVER_STOP); if no it should stop (0).
     * Accept-unreachable-under-M also yields NEVER_STOP (no accept to
     * stop on; the runner's accept-mask check filters anyway).
     *
     * <p>Example: for {@code ^((?:$)|.)*} at pos 0 of "a", $ fails, so
     * the .-branch outranks the skip-exit MATCH and we extend; at pos 1
     * (EOF), $ holds, the $-loop-back MATCH outranks . and we stop.
     */
    @SuppressWarnings("unchecked")
    private int[] stopRowFor(Object seed, Kernel k) {
        int[] row = new int[64];
        int cnt = k.size();
        for (int M = 0; M < 64; M++) {
            // seed is int[] (tagless) or List<Config> (tagged) — see stateSeeds
            int[] perStateOrder = seed instanceof int[] ? computePerStateOrder((int[]) seed, M)
                : computePerStateOrder((List<Config>) seed, M);
            int acceptOrder = perStateOrder[nfa.accept];
            if (acceptOrder == -1) {
                row[M] = NEVER_STOP;
                continue;
            }
            boolean higherPriSym = false;
            for (int i = 0; i < cnt; i++) {
                int st = k.stateAt(i);
                if (st == nfa.accept) {
                    continue;
                }
                if (symOut[st].length == 0) {
                    continue;
                }
                int o = perStateOrder[st];
                if (o != -1 && o < acceptOrder) {
                    higherPriSym = true;
                    break;
                }
            }
            row[M] = higherPriSym ? NEVER_STOP : 0;
        }
        return row;
    }

    /**
     * Pike-cut hazard predicate (feeds {@link Tdfa#pikeCutMatters()}):
     * true iff under some posFlags M an alive AND steppable config sits
     * below the first alive accept in this kernel. The cut deletes every
     * config below an accept alive in a stepping context, so such a
     * config means the cut deleted a real continuation — a whole-input
     * walk on this artifact could then miss accepts ((a|ab) on "ab": the
     * b-continuation sits below the accept and is cut). When no state
     * trips this, every cut removed only non-steppable configs, which
     * contribute nothing to target kernels, so the artifact is identical
     * to a cut-free build and whole walks on it are exact.
     *
     * <p>Conservative: kernel masks approximate the runner's fm/sam
     * record gates from above, so this flags a superset of the real
     * hazard positions.
     */
    private boolean pikeCutHazard(Kernel k) {
        int cnt = k.size();
        for (int M = 0; M < 64; M++) {
            int firstAliveAccept = -1;
            for (int i = 0; i < cnt; i++) {
                if ((k.maskAt(i) & ~M) != 0) {
                    continue;
                } // dead under M
                if (k.stateAt(i) == nfa.accept) {
                    firstAliveAccept = i;
                    break;
                }
            }
            if (firstAliveAccept < 0) {
                continue;
            }
            for (int i = firstAliveAccept + 1; i < cnt; i++) {
                if ((k.maskAt(i) & ~M) != 0) {
                    continue;
                } // dead under M
                if (symOut[k.stateAt(i)].length > 0) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Solve each accepting state's final register ops (φ) BEFORE the
     * register optimization, so the CFG pass sees final blocks along with
     * transition ops. Tagless accepts arrive here with their kernels
     * already packed: finalRegops yields empty ops (no registers), but
     * the per-M aliveness variants are still computed — an accept state
     * whose configs carry DIFFERENT emptyMasks is an OR of
     * assertion-gated accepts, which the conjunctive stateAcceptMask
     * (intersection) collapses to "always alive" (e.g.
     * {@code Z(?:\A|\B)} matching at pos 1 where \A and \B both fail).
     */
    private void solveFinalOps() {
        int n = kernels.size();
        for (int s = 0; s < n; s++) {
            if (!accept.get(s)) {
                continue;
            }
            DfaStateBuilder sb = builders.get(s);
            Kernel k = kernels.get(s);
            if (k.boxed != null) {
                sb.finalOpsArr = variants.finalRegops(k.boxed);
                variants.computeFinalVariants(sb, k.boxed);
            } else {
                sb.finalOpsArr = null;
                variants.computeFinalVariantsPacked(sb, k.packed);
            }
        }
    }

    // ---------------- Algorithm 3 building blocks ----------------

    /**
     * ε-closure via DFS with priority-ordered exploration (paper Algorithm 3).
     * Uses a stack (LIFO): children pushed in REVERSE priority order so the
     * highest-priority child is on top and popped first. This ensures the
     * leftmost-greedy preferred path is explored all the way down before
     * lower-priority alternatives.
     * <p>
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
        // Open-addressing primitive (state<<32|mask) set: a boxed HashSet<Long>
        // would allocate a Long per visited config per closure call — the #1
        // allocation site on wide-class determinization. Initial size seed*2 (not
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
            if (containsKey(visitedSM, visitedMask, key)) {
                continue;
            }
            stack.push(c);
        }
        if (epochCtr == Integer.MAX_VALUE) {
            Arrays.fill(maskEpoch, 0);
            epochCtr = 1;
        }
        final int epoch = ++epochCtr;
        while (!stack.isEmpty()) {
            meter.tick();
            Config c = stack.pop();
            long key = visitKey(c.state, c.emptyMask);
            int slot = (int) (mix(key) & visitedMask);
            while (visitedSM[slot] != 0) {
                if (visitedSM[slot] == key) {
                    slot = -1;
                    break;
                }
                slot = (slot + 1) & visitedMask;
            }
            if (slot < 0) {
                continue;
            }
            visitedSM[slot] = key;
            if (++visitedCount * 2 > visitedMask) {
                visitedSM = growVisited(visitedSM);
                visitedMask = visitedSM.length - 1;
            }
            if (maskEpoch[c.state] != epoch) {
                maskEpoch[c.state] = epoch;
                maskBitset[c.state] = 0L;
            }
            maskBitset[c.state] |= 1L << c.emptyMask;
            out.add(c);
            // Per-kernel spike bound (weighted bytes): kernelsTotal only
            // counts after addState, so a single closure of a
            // nested-counted bomb could otherwise exhaust the heap on
            // its own. Tag-aware: the config weight carries its regs.
            if ((out.size() + 1) * (long) kernelConfigBytes > maxClosureBytes) {
                throw new IllegalStateException("pattern too large: TDFA ε-closure exceeds the closure spike budget ("
                    + (out.size() + 1) + " configs x " + kernelConfigBytes + " weighted bytes, cap " + maxClosureBytes
                    + "; " + c.state + " reached — raise -D" + Budgets.COMPILE_MEMORY_PROP + ")");
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
                // variant's — so empty iterations die here ((?:.*?9{0,}\b){1,} on
                // "99x" matches [0,0) like the refs, not [0,3)). Incomparable-mask
                // re-arrivals survive ((?:^|$)+ needs both the BEGIN and END
                // junction variants); that is the difference from a blanket
                // state-only dedup, and it is what the position-aware tables
                // downstream rely on. Masks are 6 bits: exact submask check over a
                // per-state popped-mask bitset.
                int edgeEmpty = nfa.epsEmptyMask[idx];
                int newMask = c.emptyMask | edgeEmpty;
                if (maskEpoch[to] == epoch && submaskPopped(maskBitset[to], newMask)) {
                    continue;
                }
                long childKey = visitKey(to, newMask);
                if (containsKey(visitedSM, visitedMask, childKey)) {
                    continue;
                }
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

    /**
     * Pike post-match thread pruning, determinized (Perl mode only): the
     * moment a live set contains an ACCEPT config, every config ranked
     * BELOW the first (highest-priority) alive accept is dead — any match
     * those threads reach is discarded by leftmost-first (re2j records
     * only the first Match), and non-matching continuations of them are
     * irrelevant. Without the prune, the walk extends past a recorded
     * accept via a lower-priority body and the runner's unconditional
     * lastAccept overwrite turns the result leftmost-LONGEST for that
     * window ({@code .+?\b[^\d]*} on "ß9" reports [0,2) where
     * re2j/sim/jdk report [0,1): the lazy {@code .} body matched '9'
     * although ranked below the \b-gated accept at pos 1).
     *
     * <p>Configs ranked ABOVE the accept are kept: their later match
     * legitimately replaces the recorded one (the stop table's
     * higherPriSym NEVER_STOP exists for exactly them). Greedy shapes
     * are unaffected in practice — the body outranks the accept there.
     * The KERNEL itself is not pruned (state identity and the stop/final
     * tables see the full closure); only this live set's stepping input.
     */
    List<Config> pruneBelowAccept(List<Config> live) {
        if (longest || unpruned) {
            return live;
        }
        int cut = -1;
        for (int i = 0; i < live.size(); i++) {
            if (live.get(i).state == nfa.accept) {
                cut = i;
                break;
            }
        }
        if (cut < 0 || cut == live.size() - 1) {
            return live;
        } // nothing below the accept
        List<Config> pruned = new ArrayList<>(live.subList(0, cut + 1));
        return pruned;
    }

    /**
     * In-place variant for freshly-built live lists.
     */
    void pruneBelowAcceptInPlace(List<Config> live) {
        if (longest || unpruned) {
            return;
        }
        int cut = -1;
        for (int i = 0; i < live.size(); i++) {
            if (live.get(i).state == nfa.accept) {
                cut = i;
                break;
            }
        }
        if (cut >= 0 && cut < live.size() - 1) {
            live.subList(cut + 1, live.size()).clear();
        }
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
     *                {@code BEGIN_TEXT|END_TEXT|WORD_BOUNDARY|NO_WORD_BOUNDARY});
     *                0xF ("all assertions hold") recovers the pre-position-aware
     *                behavior.
     * @return int[] indexed by NFA state; value = arrival index (0-based),
     * or -1 for unreachable states (incl. states only reachable via
     * assertion edges whose requirements aren't in posMask).
     */
    int[] computePerStateOrder(int[] seedStates, int posMask) {
        return computePerStateOrderDfs(seedStates, posMask);
    }

    int[] computePerStateOrder(List<Config> seed, int posMask) {
        int[] seedStates = new int[seed.size()];
        for (int i = 0; i < seed.size(); i++) {
            seedStates[i] = seed.get(i).state;
        }
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
        Arrays.fill(order, 0, nfa.stateCount, -1);
        Arrays.fill(visited, 0, nfa.stateCount, false);
        int sp = 0;
        for (int i = seedStates.length - 1; i >= 0; i--) {
            int s = seedStates[i];
            if (!visited[s]) {
                stackArr[sp++] = s;
            }
        }
        int counter = 0;
        while (sp > 0) {
            meter.tick(); // per popped node: this DFS runs 64× per accepting state
            int s = stackArr[--sp];
            if (visited[s]) {
                continue;
            }
            visited[s] = true;
            order[s] = counter++;
            int[] eps = epsOut[s];
            for (int i = eps.length - 1; i >= 0; i--) {
                int idx = eps[i];
                int required = nfa.epsEmptyMask[idx];
                if ((required & ~posMask) != 0) {
                    continue;
                } // assertion fails at this position
                int to = nfa.epsTo[idx];
                if (!visited[to]) {
                    if (sp == stackArr.length) {
                        stackArr = Arrays.copyOf(stackArr, sp * 2);
                        psoStack = stackArr;
                    }
                    stackArr[sp++] = to;
                }
            }
        }
        return order;
    }

    /**
     * Step every config in {@code configs} that has an outgoing symbol
     * transition matching one of the active edges, preserving the list's
     * priority order (it is a live-set of ONE assertion context — every
     * config is a priority competitor).
     *
     * <p>Pike-cut (Perl mode): when the first accept config in the list is
     * alive in this context ({@code ctxMask ⊇ acceptEmptyMask}), every
     * config below it is cut exactly like a pike VM cuts threads below a
     * match-recording thread — they can never produce the answer.
     * Contexts where the accept is dead keep the fallbacks: no accept
     * fired there, so nothing was cut.
     *
     * <p>Stepping resets emptyMask to 0 — assertions are position-bound;
     * the context's OR-mask rides the emitted range as requiredMask.
     */
    List<Config> stepOnSymbol(List<Config> configs, long[] activeEdges, int ctxMask) {
        int firstAcceptIdx = -1;
        int acceptEmptyMask = 0;
        boolean suppress = false;
        if (!longest && !unpruned) {
            for (int i = 0; i < configs.size(); i++) {
                Config c = configs.get(i);
                if (c.state == nfa.accept) {
                    firstAcceptIdx = i;
                    acceptEmptyMask = c.emptyMask;
                    break;
                }
            }
            if (firstAcceptIdx >= 0 && (acceptEmptyMask & ~ctxMask) == 0) {
                suppress = true;
                if (debug) {
                    System.err.println("[step] PIKE-CUT accept@" + firstAcceptIdx + " mask="
                        + Integer.toBinaryString(acceptEmptyMask) + " ctx=" + Integer.toBinaryString(ctxMask));
                }
            }
        }
        List<Config> out = new ArrayList<>();
        for (int ci = 0; ci < configs.size(); ci++) {
            if (suppress && ci > firstAcceptIdx) {
                continue; // pike-cut: lower-priority paths past the first live accept
                // can never win once that accept fires in this context
            }
            Config c = configs.get(ci);
            for (int idx : symOut[c.state]) {
                meter.tick(); // per (config, symbol): step's cost is this loop
                if ((activeEdges[idx >> 6] & (1L << (idx & 63))) != 0) {
                    // emptyMask resets on step — assertions are position-bound, gated via requiredMask.
                    out.add(new Config(nfa.symTo[idx], c.regs, c.l, HistTable.EMPTY_ID, 0, c.pri));
                }
            }
        }
        return out;
    }

    /**
     * Stabilize copy chains so reads happen before writes clobber their source.
     * COPYs that read from a register must execute before any op (COPY or POS/NIL)
     * that writes to that register.
     */
    void topologicalSort(List<int[]> ops) {
        boolean changed = true;
        int guard = 0;
        while (changed && guard++ < ops.size() * ops.size()) {
            changed = false;
            for (int i = 0; i < ops.size(); i++) {
                meter.tick(); // O(n²)-guarded: without ticks this is a
                // work-budget blind spot (fuzz hang family)
                int[] op = ops.get(i);
                if (op[0] != OP_COPY) {
                    continue;
                }
                int src = op[2];
                // Check if any EARLIER op writes to src — if so, the COPY must
                // move before it (to read the OLD value before it's clobbered).
                for (int j = 0; j < i; j++) {
                    int[] earlier = ops.get(j);
                    if (earlier[1] == src) {
                        // Move COPY to position j, shift everything else right.
                        for (int k = i; k > j; k--) {
                            ops.set(k, ops.get(k - 1));
                        }
                        ops.set(j, op);
                        changed = true;
                        break;
                    }
                }
            }
        }
    }

    /**
     * Append one tag to a history sequence, returning a fresh array (the
     * shared EMPTY sentinel maps to a single-element sequence).
     */
    @SuppressWarnings("ReferenceEquality")
    int[] appendTag(int[] seq, int tag) {
        // Reference compare against the shared EMPTY sentinel is the point
        // (hash-consed histories share one array; value-equality would
        // rescan every empty history).
        if (seq == EMPTY || seq.length == 0) {
            return new int[]{tag};
        }
        int[] out = new int[seq.length + 1];
        System.arraycopy(seq, 0, out, 0, seq.length);
        out[seq.length] = tag;
        return out;
    }

    /**
     * Hashable intern key for one cell's active-edge bitset (deterministic
     * first-seen id assignment — no rescanning of earlier sets).
     */
    private static final class ActiveSetKey {
        final long[] bits;
        final int hash;

        ActiveSetKey(long[] bits) {
            this.bits = bits;
            this.hash = Arrays.hashCode(bits);
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof ActiveSetKey && Arrays.equals(bits, ((ActiveSetKey) o).bits);
        }
    }

    /**
     * One DFA state's kernel (the subset-construction closure) in one of
     * two forms:
     * <ul>
     * <li>{@code boxed} — the closure as Configs in arrival (priority)
     * order. Needed by every consumer that reads registers or tag
     * histories: tryMap's register bijection, the final-φ solver, the
     * transition-op allocator. Tagged compiles retain it for the state's
     * whole lifetime.</li>
     * <li>{@code packed} — the (state, emptyMask) projection, 2 ints per
     * config. Everything the mask/aliveness consumers need (entry/accept
     * masks, stop table, final-variant aliveness).</li>
     * </ul>
     *
     * <p>Tagless compiles (tags == 0) have no registers or histories, so
     * once a state is processed nothing can read its boxed configs: pack()
     * replaces them with the projection (~8 B/config vs ~48 B+ boxed; the
     * boxed kernels dominated the heap on six-figure-state
     * determinizations). Tagged compiles never pack. Both forms expose
     * the same accessors, so mask-only consumers are form-agnostic.
     */
    static final class Kernel {
        List<Config> boxed;
        int[] packed;

        Kernel(List<Config> boxed) {
            this.boxed = boxed;
        }

        /**
         * Replace the boxed closure with its (state, emptyMask)
         * projection. Tagless compiles only, after the state is fully
         * processed — the boxed form is the memory-dominant part of a
         * giant determinization and has no reader left at that point.
         */
        void pack() {
            int[] p = new int[boxed.size() * 2];
            for (int i = 0; i < boxed.size(); i++) {
                p[i * 2] = boxed.get(i).state;
                p[i * 2 + 1] = boxed.get(i).emptyMask;
            }
            packed = p;
            boxed = null;
        }

        int size() {
            return packed != null ? packed.length >> 1 : boxed.size();
        }

        int stateAt(int i) {
            return packed != null ? packed[i * 2] : boxed.get(i).state;
        }

        int maskAt(int i) {
            return packed != null ? packed[i * 2 + 1] : boxed.get(i).emptyMask;
        }
    }

    /**
     * One assertion context of a closure: the configs alive under one
     * alive-mask pattern over the runtime posFlags values, in
     * closure-priority order (pike-pruned in Perl mode), plus the OR of
     * their emptyMasks ({@code orMask} — rides the emitted ranges as
     * requiredMask) and the pattern's {@code coverage} (alive-config
     * count; contexts are emitted most-coverage first so superset
     * live-sets precede their subsets).
     */
    static final class LiveContext {
        final int orMask;
        final int coverage;
        final List<Config> configs;

        LiveContext(int orMask, int coverage, List<Config> configs) {
            this.orMask = orMask;
            this.coverage = coverage;
            this.configs = configs;
        }
    }

    /**
     * Per-state tables derived from the kernels: the carrier for what
     * {@link #computeStateTables()} hands to the {@link DeterminizedDfa}.
     */
    private static final class StateTables {
        final int[] entryMask;
        final int[] acceptMask;
        final int[] stopOnAcceptMask;
        final boolean pikeCutMatters;

        StateTables(int[] entryMask, int[] acceptMask, int[] stopOnAcceptMask, boolean pikeCutMatters) {
            this.entryMask = entryMask;
            this.acceptMask = acceptMask;
            this.stopOnAcceptMask = stopOnAcceptMask;
            this.pikeCutMatters = pikeCutMatters;
        }
    }
}
