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
    public final BitSet acceptStates;
    /** Per-state sorted ranges: [lo, hi, targetState, opsRef] flat. -1 target = dead. */
    public final int[][] rangeBounds;       // [state] -> sorted {lo1, lo2, ...} for binary search
    public final int[][] rangeTargets;      // [state] -> {target1, target2, ...} aligned with bounds
    public final int[][][] rangeOps;        // [state] -> {ops1, ops2, ...} aligned with bounds
    public final int[][] finalOps;          // per accepting state

    private Tdfa(int tagCount, int groupCount, int registerCount, int startState,
                 BitSet acceptStates, int[][] rangeBounds, int[][] rangeTargets,
                 int[][][] rangeOps, int[][] finalOps) {
        this.tagCount = tagCount; this.groupCount = groupCount;
        this.registerCount = registerCount;
        this.startState = startState;
        this.acceptStates = acceptStates;
        this.rangeBounds = rangeBounds;
        this.rangeTargets = rangeTargets;
        this.rangeOps = rangeOps;
        this.finalOps = finalOps;
    }

    public static final int OP_SET_POS = 1;
    public static final int OP_SET_NIL = 2;
    public static final int OP_COPY    = 3;

    public static Tdfa compile(Tnfa nfa) { return new Compiler(nfa).compile(); }

    /** Look up transition target for (state, c). Returns -1 if no transition. */
    public int target(int state, char c) {
        int[] bounds = rangeBounds[state];
        int lo = 0, hi = bounds.length - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (c < bounds[mid]) {
                hi = mid - 1;
            } else if (mid + 1 < bounds.length && c >= bounds[mid + 1]) {
                lo = mid + 1;
            } else {
                return rangeTargets[state][mid];
            }
        }
        return -1;
    }

    public int[] ops(int state, char c) {
        int[] bounds = rangeBounds[state];
        int lo = 0, hi = bounds.length - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (c < bounds[mid]) {
                hi = mid - 1;
            } else if (mid + 1 < bounds.length && c >= bounds[mid + 1]) {
                lo = mid + 1;
            } else {
                return rangeOps[state][mid];
            }
        }
        return null;
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

        final Map<DfaStateKey, Integer> stateIndex = new HashMap<>();
        final List<List<Config>> states = new ArrayList<>();
        final BitSet accept = new BitSet();
        final BitSet processed = new BitSet();
        final List<DfaStateBuilder> builders = new ArrayList<>();
        final Deque<Integer> work = new ArrayDeque<>();
        /** Global register allocator counter; bumped monotonically across all states. */
        int nextReg;

        Compiler(Tnfa nfa) {
            this.nfa = nfa;
            this.tags = nfa.tagCount;
            this.epsOut = sortedOutgoing(nfa.epsFrom, nfa.epsPri);
            this.symOut = plainOutgoing(nfa.symFrom);
            this.initialRegisters = new int[tags];
            this.finalRegisters = new int[tags];
            for (int t = 0; t < tags; t++) initialRegisters[t] = t;
            for (int t = 0; t < tags; t++) finalRegisters[t] = tags + t;
            this.breakpoints = computeBreakpoints();
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
            List<Config> initClosure = epsilonClosure(List.of(
                    new Config(nfa.start, initialRegisters, EMPTY, EMPTY)));
            int startId = addState(initClosure, null).targetId;
            work.push(startId);

            while (!work.isEmpty()) {
                int sid = work.pop();
                if (processed.get(sid)) continue;
                processed.set(sid);
                List<Config> cur = states.get(sid);
                if (debug) {
                    System.err.println("[tdfa] processing state " + sid + " configs:");
                    for (Config c : cur) System.err.println("    state=" + c.state + " l=" + Arrays.toString(c.l) + " regs=" + Arrays.toString(c.regs));
                }
                // For each equivalence range, compute one transition (representative char = range.lo)
                for (int bi = 0; bi < breakpoints.length - 1; bi++) {
                    int rangeLo = breakpoints[bi];
                    if (rangeLo >= 0x10000) break;
                    int rangeHi = breakpoints[bi + 1] - 1;
                    char repr = (char) rangeLo;
                    List<Config> stepped = stepOnSymbol(cur, repr);
                    if (stepped.isEmpty()) continue;
                    List<Config> closed = epsilonClosure(stepped);
                    if (debug && closed.size() > 100) System.err.println("[tdfa] state " + sid + " range " + rangeLo + ".." + rangeHi + " closure=" + closed.size());
                    int[] ops = transitionRegops(closed, sid);
                    AddResult ar = addState(closed, ops);
                    if (debug) System.err.println("[tdfa] state " + sid + " on '" + (char) rangeLo + "' (" + rangeLo + ") -> " + ar.targetId + " ops.len=" + ar.ops.length);
                    builders.get(sid).addRange(rangeLo, rangeHi, ar.targetId, ar.ops);
                    if (!processed.get(ar.targetId)) work.push(ar.targetId);
                }
            }
            if (debug) System.err.println("[tdfa] total states=" + states.size() + " accept=" + accept.cardinality());

            int n = states.size();
            int[][] rangeBounds = new int[n][];
            int[][] rangeTargets = new int[n][];
            int[][][] rangeOps = new int[n][][];
            int[][] fops = new int[n][];
            // Register count: max of (nextReg, all referenced regs in any op or config).
            int globalMaxReg = nextReg;
            for (int s = 0; s < n; s++) {
                DfaStateBuilder sb = builders.get(s);
                sb.coalesce();
                sb.fillGaps();
                int k = sb.ranges.size();
                int[] bounds = new int[k];
                int[] targets = new int[k];
                int[][] opsArr = new int[k][];
                for (int i = 0; i < k; i++) {
                    Range r = sb.ranges.get(i);
                    bounds[i] = r.lo;
                    targets[i] = r.target;
                    opsArr[i] = r.ops;
                    if (r.ops != null) {
                        for (int j = 0; j < r.ops.length; j += 3) {
                            globalMaxReg = Math.max(globalMaxReg, r.ops[j + 1] + 1);
                            if (r.ops[j] == OP_COPY) globalMaxReg = Math.max(globalMaxReg, r.ops[j + 2] + 1);
                        }
                    }
                }
                rangeBounds[s] = bounds;
                rangeTargets[s] = targets;
                rangeOps[s] = opsArr;
                if (accept.get(s)) {
                    fops[s] = finalRegops(states.get(s));
                    if (fops[s] != null) {
                        for (int j = 0; j < fops[s].length; j += 3) {
                            globalMaxReg = Math.max(globalMaxReg, fops[s][j + 1] + 1);
                            if (fops[s][j] == OP_COPY) globalMaxReg = Math.max(globalMaxReg, fops[s][j + 2] + 1);
                        }
                    }
                }
            }
            return new Tdfa(tags, nfa.groupCount, globalMaxReg, 0, accept,
                    rangeBounds, rangeTargets, rangeOps, fops);
        }

        static final boolean debug = Boolean.getBoolean("tdfa.debug");

        int maxReg(List<Config> configs) {
            int m = 2 * tags - 1;
            for (Config c : configs) for (int r : c.regs) if (r > m) m = r;
            return m;
        }

        // ---------------- Algorithm 3 building blocks ----------------

        List<Config> epsilonClosure(List<Config> seed) {
            List<Config> out = new ArrayList<>();
            BitSet visited = new BitSet(nfa.stateCount);
            PriorityQueue<Config> pq = new PriorityQueue<>(Comparator.comparingInt(c -> c.order));
            int order = 0;
            for (int i = seed.size() - 1; i >= 0; i--) {
                Config c = seed.get(i);
                c.order = order++;
                pq.add(c);
            }
            while (!pq.isEmpty()) {
                Config c = pq.poll();
                if (visited.get(c.state)) continue;
                visited.set(c.state);
                out.add(c);
                if (out.size() > 10000 && !closureWarned) {
                    closureWarned = true;
                    System.err.println("[tdfa] closure grew to " + out.size());
                }
                for (int idx : epsOut[c.state]) {
                    int to = nfa.epsTo[idx];
                    if (visited.get(to)) continue;
                    int tag = nfa.epsTag[idx];
                    int[] newL;
                    if (tag == Tnfa.NO_TAG || tag == Tnfa.ANCHOR_START || tag == Tnfa.ANCHOR_END) {
                        newL = c.l;
                    } else {
                        newL = appendTag(c.l, tag);
                    }
                    Config n = new Config(to, c.regs, c.h, newL);
                    n.order = order++;
                    pq.add(n);
                }
            }
            // Keep all configs (no filter). The paper's add_state operates on the full closure C.
            // Filtering loses register-tracking information needed by map's bijection.
            List<Config> all = out;
            all.sort(Comparator.comparingInt(c -> c.state));
            return all;
        }

        List<Config> stepOnSymbol(List<Config> configs, char a) {
            List<Config> out = new ArrayList<>();
            for (Config c : configs) {
                for (int idx : symOut[c.state]) {
                    CharClass cc = nfa.symClass[idx];
                    if (cc != null && cc.matches(a)) {
                        out.add(new Config(nfa.symTo[idx], c.regs, c.l, EMPTY));
                    }
                }
            }
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
                configs.set(ci, new Config(c.state, newRegs, c.h, c.l));
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

        AddResult addState(List<Config> configs, int[] ops) {
            DfaStateKey key = new DfaStateKey(configs);
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
            return new DfaStateKey(a).equals(new DfaStateKey(b));
        }

        /** Stabilize copy chains so reads happen before writes clobber their source. */
        void topologicalSort(List<int[]> ops) {
            boolean changed = true;
            int guard = 0;
            while (changed && guard++ < ops.size() * ops.size()) {
                changed = false;
                for (int i = 0; i < ops.size(); i++) {
                    int[] op = ops.get(i);
                    if (op[0] != OP_COPY) continue;
                    int src = op[2];
                    for (int j = i + 1; j < ops.size(); j++) {
                        int[] later = ops.get(j);
                        if (later[1] == src) {
                            for (int k = j; k > i; k--) ops.set(k, ops.get(k - 1));
                            ops.set(i, later);
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
        boolean closureWarned = false;
    }

    static final class Config {
        final int state;
        final int[] regs;
        final int[] h;
        final int[] l;
        int order;
        Config(int state, int[] regs, int[] h, int[] l) {
            this.state = state; this.regs = regs; this.h = h; this.l = l;
        }
    }

    /** Canonical DFA state key: state ids + per-config lookahead tags (NOT registers).
     *  Two states with same key are candidates for {@code map} (register bijection). */
    static final class DfaStateKey {
        final int[] sig;
        final int hash;
        DfaStateKey(List<Config> configs) {
            int total = 0;
            for (Config c : configs) total += 2 + c.l.length;
            int[] arr = new int[total];
            int i = 0;
            for (Config c : configs) {
                arr[i++] = c.state;
                arr[i++] = c.l.length;
                for (int v : c.l) arr[i++] = v;
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
        Range(int lo, int hi, int target, int[] ops) {
            this.lo = lo; this.hi = hi; this.target = target; this.ops = ops;
        }
    }

    static final class DfaStateBuilder {
        final int id;
        final List<Range> ranges = new ArrayList<>();
        DfaStateBuilder(int id) { this.id = id; }
        void addRange(int lo, int hi, int target, int[] ops) { ranges.add(new Range(lo, hi, target, ops)); }
        void coalesce() {
            ranges.sort(Comparator.comparingInt(r -> r.lo));
            if (ranges.size() <= 1) return;
            List<Range> out = new ArrayList<>();
            Range cur = ranges.get(0);
            for (int i = 1; i < ranges.size(); i++) {
                Range next = ranges.get(i);
                if (next.lo == cur.hi + 1 && next.target == cur.target && Arrays.equals(next.ops, cur.ops)) {
                    cur = new Range(cur.lo, next.hi, cur.target, cur.ops);
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
                    out.add(new Range(expected, r.lo - 1, -1, null));
                }
                out.add(r);
                expected = r.hi + 1;
            }
            if (expected <= 0xFFFF) {
                out.add(new Range(expected, 0xFFFF, -1, null));
            }
            ranges.clear();
            ranges.addAll(out);
        }
    }
}
