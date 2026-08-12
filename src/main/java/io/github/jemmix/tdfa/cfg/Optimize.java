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
        // For now, run liveness + DCE only (Stage 2). Paper runs the rest N=2 times.
        int opsBefore = countOps(cfg);
        boolean[][] L = livenessAnalysis(cfg);
        deadCodeElimination(cfg, L);
        int opsAfter = countOps(cfg);
        if (opsAfter < opsBefore) {
            cfg.dceRemovedOps = opsBefore - opsAfter;
        }
    }

    private static int countOps(Cfg cfg) {
        int n = 0;
        for (Cfg.Block b : cfg.blocks) n += b.ops.size();
        return n;
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
            // Walk in reverse; collect dead ops for removal.
            List<Cfg.Op> survivors = new ArrayList<>(b.ops.size());
            // First pass: walk forward building a "keep?" decision list, but the liveness
            // state must be propagated backward. So we walk in reverse, marking ops as
            // dead/alive, then reverse the decision list to match original op order.
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
            for (int oi = 0; oi < b.ops.size(); oi++) {
                if (keep[oi]) survivors.add(b.ops.get(oi));
            }
            b.ops.clear();
            b.ops.addAll(survivors);
        }
    }
}

