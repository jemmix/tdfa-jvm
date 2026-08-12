package io.github.jemmix.tdfa.cfg;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;

/**
 * Driver for the BT22 §6.3 register-optimization pipeline.
 *
 * <p>Stages (each committed separately):
 * <ol>
 *   <li><b>Compaction</b> — drop unused registers, renumber survivors contiguously.
 *       Land immediately after determinization; needed primarily so that liveness
 *       and interference matrices can be indexed by register id without wasting space.</li>
 *   <li><b>Liveness + DCE</b> — backward dataflow to find live registers per block;
 *       remove ops whose dst is dead.</li>
 *   <li><b>Interference + allocation + coalescing</b> — build interference graph,
 *       partition into equivalence classes, merge non-interfering copies.</li>
 *   <li><b>Normalization</b> — dedup + sort set ops; dedup + topo-sort copy ops.</li>
 * </ol>
 *
 * <p>The paper runs the post-compaction passes twice (N=2). Each iteration uses the
 * previous renaming and may find new coalescing opportunities.
 *
 * <p>Public entry point: {@link #optimize(Cfg)}.
 */
public final class Optimize {
    private Optimize() {}

    /** Run the full pipeline on {@code cfg} (in place). */
    public static void optimize(Cfg cfg) {
        // Stage 1: compaction (renumber survivors into a contiguous range).
        int[] vmap = compaction(cfg);
        rename(cfg, vmap);
        cfg.regCount = countUsed(vmap);
        cfg.finalRegBase = cfg.regCount - cfg.tagCount;

        // Stages 2-4: liveness, DCE, interference, allocation, normalization.
        // Paper runs this sub-pipeline N=2 times; each iteration can find new
        // coalescing opportunities revealed by the previous renaming.
        // Normalization runs INSIDE the loop (paper Figure 7: renaming; normalization).
        for (int iter = 0; iter < 2; iter++) {
            boolean[][] L = livenessAnalysis(cfg);
            deadCodeElimination(cfg, L);
            boolean[][] I = interferenceAnalysis(cfg, L);
            int[] V = registerAllocation(cfg, I);
            rename(cfg, V);
            normalization(cfg);
            cfg.regCount = countUsed(V);
            cfg.finalRegBase = findFinalRegBase(V, cfg.tagCount);
        }
    }

    private static int countOps(Cfg cfg) {
        int n = 0;
        for (Cfg.Block b : cfg.blocks) n += b.ops.size();
        return n;
    }

    /** After renaming, find the index where the T contiguous final registers landed. */
    private static int findFinalRegBase(int[] V, int tagCount) {
        // Final registers were the top tagCount entries before renaming. After renaming,
        // their new indices are V[oldFinalBase..oldFinalBase+tagCount-1]. The min of
        // those is the new finalRegBase (they're packed into a contiguous block at the
        // top by allocation's last loop).
        int n = V.length;
        int oldFinalBase = n - tagCount;
        int min = Integer.MAX_VALUE;
        for (int t = 0; t < tagCount; t++) {
            min = Math.min(min, V[oldFinalBase + t]);
        }
        return min;
    }

    // ==================== Stage 1: Compaction ====================

    /**
     * Find used registers, return a remapping {@code V[old] = new} where new is
     * a contiguous 1-based numbering (paper uses 1-based; we remap to 0-based at
     * the end). Unused registers map to 0 (sentinel — will be detected if ever
     * applied, but renaming shouldn't encounter them since they're unused).
     *
     * <p>Final-register invariant: indices {@code [tagCount .. 2*tagCount-1]} are
     * always marked used, and they are renumbered as a contiguous block at the
     * top of the new range so that {@link MatchResult}'s
     * {@code regs[finalRegBase + t - 1]} offset can be communicated once and
     * benefit from all subsequent passes.
     *
     * <p>Paper algorithm: Figure 7 ({@code compaction}).
     */
    static int[] compaction(Cfg cfg) {
        int n = cfg.initialRegCount;
        BitSet used = new BitSet();
        // Walk all ops, mark dst and src.
        for (Cfg.Block b : cfg.blocks) {
            for (Cfg.Op op : b.ops) {
                used.set(op.dst);
                if (op.kind == Cfg.KIND_COPY || op.kind == Cfg.KIND_APPEND) used.set(op.src);
            }
        }
        // Always-used: final register block (one per tag).
        int tagCount = cfg.tagCount;
        for (int t = 0; t < tagCount; t++) used.set(tagCount + t);

        // Two-pass renumbering: working registers first (lowest indices), then
        // final registers (highest). This keeps the final block contiguous at the top.
        int[] vmap = new int[n];
        java.util.Arrays.fill(vmap, -1);
        int nextWorking = 0;
        // Pass 1: working registers = used regs in [0..tagCount-1] and [2*tagCount..n-1].
        for (int i = 0; i < tagCount; i++) {
            if (used.get(i)) vmap[i] = nextWorking++;
        }
        for (int i = 2 * tagCount; i < n; i++) {
            if (used.get(i)) vmap[i] = nextWorking++;
        }
        // Pass 2: final registers go right after the working block.
        int finalBase = nextWorking;
        for (int t = 0; t < tagCount; t++) {
            vmap[tagCount + t] = finalBase + t;
        }
        return vmap;
    }

    /** Apply register renaming V[old] → new to every op in the CFG. Paper: {@code renaming}. */
    static void rename(Cfg cfg, int[] vmap) {
        for (Cfg.Block b : cfg.blocks) {
            for (Cfg.Op op : b.ops) {
                if (op.dst < vmap.length && vmap[op.dst] >= 0) op.dst = vmap[op.dst];
                if (op.kind == Cfg.KIND_COPY || op.kind == Cfg.KIND_APPEND) {
                    if (op.src < vmap.length && vmap[op.src] >= 0) op.src = vmap[op.src];
                }
            }
        }
    }

    private static int countUsed(int[] vmap) {
        int max = -1;
        for (int v : vmap) if (v > max) max = v;
        return max + 1;
    }

    // ==================== Stage 2: Liveness + DCE ====================

    /**
     * Backward dataflow liveness analysis. Computes {@code L[b][i]} = true iff
     * register {@code i} is live at the END of block {@code b} (i.e., its value
     * will be read by some downstream op before being overwritten, or it's a
     * final register consumed by {@link io.github.jemmix.tdfa.vm.MatchResult}).
     *
     * <p>Paper algorithm: Figure 7 ({@code liveness_analysis}).
     *
     * <p>Seeds: all final registers are live at the end of every final block.
     * Propagation: for each basic block, liveness-at-end = union over successors
     * of (liveness propagated backward through the successor's ops to its entry).
     * Fixpoint iteration until no row changes.
     *
     * <p>Fallback-block handling (last 4 lines of the paper's pseudocode) is
     * deferred until M3 (we have no fallback blocks yet).
     */
    static boolean[][] livenessAnalysis(Cfg cfg) {
        int nb = cfg.blocks.size();
        int nr = cfg.regCount;
        boolean[][] L = new boolean[nb][nr];
        int fb = cfg.finalRegBase;
        int T = cfg.tagCount;
        // Seed: all final registers live at end of every final block.
        for (int b = 0; b < nb; b++) {
            if (cfg.blocks.get(b).kind != Cfg.BLOCK_FINAL) continue;
            for (int t = 0; t < T; t++) {
                if (fb + t < nr) L[b][fb + t] = true;
            }
        }
        // Post-order traversal of basic blocks (children before parents) for fast convergence.
        int[] postOrder = computePostOrder(cfg);
        // Fixpoint.
        while (true) {
            boolean fixed = true;
            for (int bi : postOrder) {
                Cfg.Block b = cfg.blocks.get(bi);
                if (b.kind != Cfg.BLOCK_BASIC) continue;
                boolean[] Lb = L[bi].clone();
                for (int si : b.successors) {
                    Cfg.Block s = cfg.blocks.get(si);
                    boolean[] Ls = L[si].clone();
                    propagateBackward(Ls, s.ops, nr);
                    for (int i = 0; i < nr; i++) Lb[i] = Lb[i] || Ls[i];
                }
                if (!Arrays.equals(L[bi], Lb)) {
                    L[bi] = Lb;
                    fixed = false;
                }
            }
            if (fixed) break;
        }
        return L;
    }

    /**
     * Walk {@code ops} in reverse, transforming {@code live} from "at end of block"
     * to "at start of block". SET kills dst; COPY transfers liveness dst → src.
     */
    private static void propagateBackward(boolean[] live, List<Cfg.Op> ops, int nr) {
        for (int oi = ops.size() - 1; oi >= 0; oi--) {
            Cfg.Op op = ops.get(oi);
            if (op.dst >= nr) continue;  // out-of-range (shouldn't happen post-compaction)
            switch (op.kind) {
                case Cfg.KIND_SET:
                    live[op.dst] = false;
                    break;
                case Cfg.KIND_COPY:
                    if (live[op.dst]) {
                        live[op.dst] = false;
                        if (op.src < nr) live[op.src] = true;
                    }
                    break;
                default:
                    // KIND_APPEND: liveness transfers not modeled (multi-valued tags unsupported).
                    break;
            }
        }
    }

    /**
     * DFS-based post-order traversal of the basic blocks in the CFG. Returns a list
     * of block indices in post-order (each block appears after all its CFG successors).
     * Used by {@link #livenessAnalysis} for fast dataflow convergence.
     */
    private static int[] computePostOrder(Cfg cfg) {
        int nb = cfg.blocks.size();
        List<Integer> postOrder = new ArrayList<>();
        BitSet visited = new BitSet();
        // DFS from each unvisited BASIC block.
        for (int root = 0; root < nb; root++) {
            if (cfg.blocks.get(root).kind != Cfg.BLOCK_BASIC) continue;
            if (visited.get(root)) continue;
            dfsPostOrder(root, cfg, visited, postOrder);
        }
        int[] arr = new int[postOrder.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = postOrder.get(i);
        return arr;
    }

    private static void dfsPostOrder(int bi, Cfg cfg, BitSet visited, List<Integer> out) {
        if (visited.get(bi)) return;
        visited.set(bi);
        Cfg.Block b = cfg.blocks.get(bi);
        for (int succ : b.successors) {
            // Recurse into BASIC successors only (FINAL blocks have no successors to visit).
            if (cfg.blocks.get(succ).kind == Cfg.BLOCK_BASIC) dfsPostOrder(succ, cfg, visited, out);
        }
        out.add(bi);
    }

    /**
     * Remove operations whose dst is not live. Paper: {@code dead_code_elimination}.
     *
     * <p>Walks each BASIC block's ops in reverse, tracking liveness. Ops with dead dst
     * are removed; ops with live dst update the liveness (SET kills dst, COPY transfers
     * liveness dst → src). The liveness seed for each block is the just-computed
     * {@link #livenessAnalysis} matrix.
     *
     * <p>Final blocks are not processed: their ops write to final registers which are
     * always live, so nothing would be removed.
     */
    static void deadCodeElimination(Cfg cfg, boolean[][] L) {
        int nr = cfg.regCount;
        for (int bi = 0; bi < cfg.blocks.size(); bi++) {
            Cfg.Block b = cfg.blocks.get(bi);
            if (b.kind != Cfg.BLOCK_BASIC) continue;
            boolean[] Lb = L[bi].clone();
            boolean[] keep = new boolean[b.ops.size()];
            for (int oi = b.ops.size() - 1; oi >= 0; oi--) {
                Cfg.Op op = b.ops.get(oi);
                if (op.dst < nr && Lb[op.dst]) {
                    keep[oi] = true;
                    if (op.kind == Cfg.KIND_SET) {
                        Lb[op.dst] = false;
                    } else if (op.kind == Cfg.KIND_COPY) {
                        Lb[op.dst] = false;
                        if (op.src < nr) Lb[op.src] = true;
                    }
                } else {
                    keep[oi] = false;
                }
            }
            List<Cfg.Op> survivors = new ArrayList<>(b.ops.size());
            for (int oi = 0; oi < b.ops.size(); oi++) {
                if (keep[oi]) survivors.add(b.ops.get(oi));
            }
            b.ops.clear();
            b.ops.addAll(survivors);
        }
    }

    // ==================== Stage 3: Interference + Allocation ====================

    /**
     * Build the register interference graph. Paper: {@code interference_analysis}.
     *
     * <p>{@code I[i][j] = true} iff registers {@code i} and {@code j} cannot share a
     * physical slot (their lifetimes overlap, so one would clobber the other).
     *
     * <p>Per block, walk ops in execution order tracking the "value history" V[i] of
     * each register. When processing an op with dst {@code d}, every register live at
     * the end of the block (per L[b]) — except those currently holding the same value
     * as {@code d} — interferes with {@code d}.
     *
     * <p>APPEND-vs-non-APPEND cross-interference is skipped: we have no APPEND ops
     * (single-valued tags only).
     */
    static boolean[][] interferenceAnalysis(Cfg cfg, boolean[][] L) {
        int nr = cfg.regCount;
        boolean[][] I = new boolean[nr][nr];
        // Value-history sentinels: must be distinct from any register id (in [0, nr-1])
        // and from each other, so that two SETs to the same value can share a slot
        // (paper: "registers that have the same value" don't interfere).
        final int NO_VALUE = -1;
        final int POS_VALUE = -2;
        final int NIL_VALUE = -3;
        int[] V = new int[nr];
        java.util.Arrays.fill(V, NO_VALUE);
        for (int bi = 0; bi < cfg.blocks.size(); bi++) {
            Cfg.Block b = cfg.blocks.get(bi);
            // Reset V for this block: V[src] = src for each COPY/APPEND in b.
            for (Cfg.Op op : b.ops) {
                if (op.kind == Cfg.KIND_COPY || op.kind == Cfg.KIND_APPEND) {
                    if (op.src < nr) V[op.src] = op.src;
                }
            }
            boolean[] Lb = L[bi];
            // Walk ops in order; track V and mark interferences.
            for (Cfg.Op op : b.ops) {
                if (op.dst >= nr) continue;
                boolean[] Ib = Lb.clone();
                // Update V for this op per paper: V[i] = v for SET, V[i] = V[j] for COPY.
                switch (op.kind) {
                    case Cfg.KIND_SET:
                        V[op.dst] = (op.value == Cfg.VAL_POS) ? POS_VALUE : NIL_VALUE;
                        break;
                    case Cfg.KIND_COPY:
                        if (op.src < nr) {
                            V[op.dst] = V[op.src];
                            Ib[op.dst] = false;
                            Ib[op.src] = false;
                        }
                        break;
                    default: break;
                }
                Ib[op.dst] = false;
                // Mask out registers with the same value as op.dst (paper: same value → no interference).
                int vDst = V[op.dst];
                if (vDst != NO_VALUE) {
                    for (int k = 0; k < nr; k++) {
                        if (V[k] == vDst) Ib[k] = false;
                    }
                }
                // Mark interferences between op.dst and live regs.
                for (int k = 0; k < nr; k++) {
                    if (Ib[k]) {
                        I[op.dst][k] = true;
                        I[k][op.dst] = true;
                    }
                }
            }
        }
        return I;
    }

    /**
     * Chaitin-style register allocation with copy coalescing. Paper:
     * {@code register_allocation}.
     *
     * <p>Builds equivalence classes of registers that don't interfere and can share a
     * physical slot. Three phases:
     * <ol>
     *   <li>Walk COPY ops; try to put src+dst in the same class (kills the COPY).</li>
     *   <li>Merge pairs of non-interfering classes (transitive coalescing).</li>
     *   <li>Assign leftover registers to non-interfering classes (or new ones).</li>
     * </ol>
     *
     * @return V[old] = new register index (1-based; renaming converts to 0-based)
     */
    static int[] registerAllocation(Cfg cfg, boolean[][] I) {
        int nr = cfg.regCount;
        int[] B = new int[nr];
        List<BitSet> S = new ArrayList<>(nr);
        java.util.Arrays.fill(B, -1);
        for (int i = 0; i < nr; i++) S.add(new BitSet());

        // Phase 1: walk COPY ops; try to coalesce src+dst.
        for (Cfg.Block b : cfg.blocks) {
            for (Cfg.Op op : b.ops) {
                if (op.kind != Cfg.KIND_COPY && op.kind != Cfg.KIND_APPEND) continue;
                if (op.dst == op.src) continue;
                if (op.dst >= nr || op.src >= nr) continue;
                int i = op.dst, j = op.src;
                int x = B[i], y = B[j];
                if (x == -1 && y == -1) {
                    if (!I[i][j]) {
                        B[i] = B[j] = i;
                        S.get(i).set(i);
                        S.get(i).set(j);
                    }
                } else if (x != -1 && y == -1) {
                    if (noInterfere(S.get(x), j, I)) {
                        B[j] = x;
                        S.get(x).set(j);
                    }
                } else if (x == -1) {  // y != -1
                    if (noInterfere(S.get(y), i, I)) {
                        B[i] = y;
                        S.get(y).set(i);
                    }
                } else if (x != y) {
                    // Both in classes; merge if possible (paper omits this case).
                    if (noInterfereCross(S.get(x), S.get(y), I)) {
                        for (int m = S.get(y).nextSetBit(0); m >= 0; m = S.get(y).nextSetBit(m + 1)) {
                            B[m] = x;
                            S.get(x).set(m);
                        }
                        S.get(y).clear();
                    }
                }
            }
        }

        // Phase 2: merge pairs of non-interfering classes.
        for (int i = 0; i < nr; i++) {
            if (B[i] != i) continue;
            for (int j = i + 1; j < nr; j++) {
                if (B[j] != j) continue;
                if (noInterfereCross(S.get(i), S.get(j), I)) {
                    for (int m = S.get(j).nextSetBit(0); m >= 0; m = S.get(j).nextSetBit(m + 1)) {
                        B[m] = i;
                        S.get(i).set(m);
                    }
                    S.get(j).clear();
                }
            }
        }

        // Phase 3: assign leftover (B[i] == -1) to a non-interfering class or new class.
        for (int i = 0; i < nr; i++) {
            if (B[i] != -1) continue;
            int assigned = -1;
            for (int j = 0; j < nr; j++) {
                if (B[j] != j) continue;
                if (noInterfere(S.get(j), i, I)) {
                    assigned = j;
                    break;
                }
            }
            if (assigned == -1) {
                B[i] = i;
                S.get(i).set(i);
            } else {
                B[i] = assigned;
                S.get(assigned).set(i);
            }
        }

        // Final numbering: representatives get 1, 2, 3, ... in increasing index order;
        // class members get their representative's number.
        int[] V = new int[nr];
        java.util.Arrays.fill(V, -1);
        int n = 0;
        for (int i = 0; i < nr; i++) {
            if (B[i] == i) {
                n++;
                for (int m = S.get(i).nextSetBit(0); m >= 0; m = S.get(i).nextSetBit(m + 1)) {
                    V[m] = n;
                }
            }
        }
        // Convert 1-based to 0-based.
        for (int i = 0; i < nr; i++) V[i] = V[i] - 1;
        return V;
    }

    private static boolean noInterfere(BitSet cls, int j, boolean[][] I) {
        for (int k = cls.nextSetBit(0); k >= 0; k = cls.nextSetBit(k + 1)) {
            if (I[k][j]) return false;
        }
        return true;
    }

    private static boolean noInterfereCross(BitSet x, BitSet y, boolean[][] I) {
        for (int i = x.nextSetBit(0); i >= 0; i = x.nextSetBit(i + 1)) {
            for (int j = y.nextSetBit(0); j >= 0; j = y.nextSetBit(j + 1)) {
                if (I[i][j]) return false;
            }
        }
        return true;
    }

    // ==================== Stage 4: Normalization ====================

    /**
     * Per-block normalization: dedup + sort SET ops, dedup + topo-sort COPY ops.
     * Paper: {@code normalization}.
     *
     * <p>Operates on contiguous op-kind runs within each block. SET ops sort by dst;
     * COPY ops topo-sort by dependency (a COPY i ← j must come after any COPY that
     * writes i or j). Duplicates after normalization indicate redundant work and can
     * be removed.
     */
    static void normalization(Cfg cfg) {
        for (Cfg.Block b : cfg.blocks) {
            if (b.ops.isEmpty()) continue;
            List<Cfg.Op> normalized = new ArrayList<>(b.ops.size());
            int i = 0;
            while (i < b.ops.size()) {
                int kind = b.ops.get(i).kind;
                int j = i;
                while (j < b.ops.size() && b.ops.get(j).kind == kind) j++;
                List<Cfg.Op> run = new ArrayList<>(b.ops.subList(i, j));
                normalizeRun(run);
                normalized.addAll(run);
                i = j;
            }
            b.ops.clear();
            b.ops.addAll(normalized);
        }
    }

    private static void normalizeRun(List<Cfg.Op> run) {
        // Dedup.
        for (int a = run.size() - 1; a >= 0; a--) {
            for (int b2 = a - 1; b2 >= 0; b2--) {
                if (opsEqual(run.get(a), run.get(b2))) { run.remove(a); break; }
            }
        }
        int kind = run.isEmpty() ? -1 : run.get(0).kind;
        if (kind == Cfg.KIND_SET) {
            run.sort((x, y) -> Integer.compare(x.dst, y.dst));
        } else if (kind == Cfg.KIND_COPY) {
            topoSortCopy(run);
        }
    }

    private static boolean opsEqual(Cfg.Op a, Cfg.Op b) {
        return a.kind == b.kind && a.dst == b.dst && a.src == b.src && a.value == b.value;
    }

    /**
     * Topologically sort COPY ops per paper Figure 8 ({@code topological_sort}).
     *
     * <p>Paper algorithm: {@code I[r] = number of ops in O that read register r}.
     * Repeatedly remove ops with {@code I[dst] = 0} (no remaining readers of dst),
     * appending them to the result; when removing op {@code i←j}, decrement
     * {@code I[j]}. This handles both RAW and WAR hazards correctly: a writer
     * (op with dst = R) is gated on {@code I[R] = 0} — i.e., all readers of R
     * must have been processed first.
     *
     * <p>If a cycle remains, append the rest as-is. The paper tracks a
     * {@code nontrivial_cycle} flag (true if any non-self cycle exists); we
     * don't currently surface it.
     */
    private static void topoSortCopy(List<Cfg.Op> run) {
        int n = run.size();
        if (n < 2) return;
        // Find max register id to size the I[] array.
        int maxReg = 0;
        for (Cfg.Op op : run) maxReg = Math.max(maxReg, Math.max(op.dst, op.src));
        int[] I = new int[maxReg + 1];
        // I[r] = number of ops in O with src = r (i.e., reading register r).
        for (Cfg.Op op : run) I[op.src]++;

        List<Cfg.Op> Oprime = new ArrayList<>(n);
        boolean[] removed = new boolean[n];
        int remaining = n;
        while (remaining > 0) {
            boolean added = false;
            for (int i = 0; i < n; i++) {
                if (removed[i]) continue;
                Cfg.Op op = run.get(i);
                if (I[op.dst] == 0) {
                    Oprime.add(op);
                    removed[i] = true;
                    remaining--;
                    I[op.src]--;
                    added = true;
                }
            }
            if (!added) {
                // Cycle: append remaining ops as-is.
                for (int i = 0; i < n; i++) {
                    if (!removed[i]) {
                        Oprime.add(run.get(i));
                        removed[i] = true;
                        remaining--;
                    }
                }
            }
        }
        run.clear();
        run.addAll(Oprime);
    }
}

