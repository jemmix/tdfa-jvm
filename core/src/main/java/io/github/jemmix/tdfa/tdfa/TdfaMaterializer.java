package io.github.jemmix.tdfa.tdfa;

import io.github.jemmix.tdfa.core.CompileObserver;
import io.github.jemmix.tdfa.regopt.Cfg;
import io.github.jemmix.tdfa.regopt.Cfg.Block;
import io.github.jemmix.tdfa.regopt.Cfg.Op;
import io.github.jemmix.tdfa.regopt.Optimize;
import io.github.jemmix.tdfa.tnfa.Tnfa;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;

import static io.github.jemmix.tdfa.tdfa.Tdfa.OP_COPY;
import static io.github.jemmix.tdfa.tdfa.Tdfa.OP_END;
import static io.github.jemmix.tdfa.tdfa.Tdfa.OP_SET_NIL;
import static io.github.jemmix.tdfa.tdfa.Tdfa.OP_SET_POS;
import static io.github.jemmix.tdfa.tdfa.Tdfa.rangeCount;

/**
 * Post-determinization pipeline: register optimization (BT22 §6.3),
 * flat-array materialization, minimization via register-aware Moore's
 * algorithm (paper §6.2.2), and final artifact assembly. Consumes the
 * {@link DeterminizedDfa} value; the builders and the determinization
 * data behind them leave scope as the pipeline advances, so the memory
 * peak of the flat-array phase is not stacked on top of the
 * determinization scratch.
 */
final class TdfaMaterializer {
    private final Tnfa nfa;
    private final boolean longest;
    private final WorkMeter meter;
    private final CompileObserver obs;
    private final boolean debug = Boolean.getBoolean("tdfa.debug");
    /**
     * Cap on materialized CFG successor arcs: buildCfg materializes TRANSITIVE zero-op
     * reachability as direct edges (liveness needs them), and φ-variant
     * finals can make that product explode — the pathological shape was a
     * 287-state DFA whose CFG had 22,637 blocks and 157,176,487 edges
     * (≈6,940 successors/block): liveness then burned ~60 s at library
     * budget. Sane shapes are orders of magnitude below the cap.
     */
    private final long maxCfgEdges = Budgets.maxCfgEdges();

    private TdfaMaterializer(Tnfa nfa, boolean longest, WorkMeter meter, CompileObserver obs) {
        this.nfa = nfa;
        this.longest = longest;
        this.meter = meter;
        this.obs = obs;
    }

    /**
     * Run the post-determinization stages over {@code det} and assemble the
     * final {@link Tdfa}. {@code meter} must be the same ledger the
     * determinization ticked, so one CPU budget covers the whole compile.
     */
    static Tdfa finish(DeterminizedDfa det, Tnfa nfa, boolean longest, WorkMeter meter, CompileObserver observer) {
        return new TdfaMaterializer(nfa, longest, meter, observer != null ? observer : CompileObserver.NONE).run(det);
    }

    private Tdfa run(DeterminizedDfa det) {
        int finalRegBase = regopt(det);
        FlatDfa flat = flatten(det, finalRegBase); // last reader of the builders/accept sets
        minimize(flat);
        ensureRangesSorted(flat);
        int[] hiPrefix = buildHiPrefix(flat);
        return assemble(flat, hiPrefix);
    }

    // ========================= register optimization (BT22 §6.3) =========================

    /**
     * Whole-DFA register optimization: build the op-level CFG over all
     * (state, range) transition blocks and accepting states' final blocks,
     * run the liveness/rename/DCE passes, and write the optimized op
     * sequences back into the builders. Skipped (reporting so) when
     * disabled by knob or out of the state bound — the unoptimized
     * register layout is always a correct fallback.
     *
     * @return the final-register block base after optimization (the
     *         default layout keeps working registers [0..T-1] and final
     *         registers [T..2T-1]).
     */
    private int regopt(DeterminizedDfa det) {
        // Compile knobs, read once per compilation (policy: Tdfa javadoc).
        final boolean regoptEnabled = !Boolean.getBoolean("tdfa.noregopt");
        final int regoptMaxStates = Integer.getInteger("tdfa.regopt.max", 2000);
        int finalRegBase = nfa.tagCount;
        long tReg = System.nanoTime();
        int n = det.stateCount;
        if (regoptEnabled && nfa.tagCount > 0 && n > 1 && n <= regoptMaxStates) {
            Cfg cfg = buildCfg(det.builders, det.accept, nfa.tagCount, nfa.groupCount, det.registerCount);
            Optimize.optimize(cfg, meter);
            cfgWriteBack(cfg, det.builders);
            finalRegBase = cfg.finalRegBase;
            if (debug) {
                System.err.println("[tdfa] regopt: regs " + cfg.initialRegCount + " -> " + cfg.regCount
                    + " (finalRegBase=" + finalRegBase + ")"
                    + (cfg.dceRemovedOps > 0 ? " DCE removed " + cfg.dceRemovedOps + " ops" : ""));
            }
            obs.stage(CompileObserver.Stage.REGOPT, System.nanoTime() - tReg, cfg.regCount);
            obs.note("regopt", "regs " + cfg.initialRegCount + "->" + cfg.regCount);
        } else {
            obs.stage(CompileObserver.Stage.REGOPT, System.nanoTime() - tReg, 2 * nfa.tagCount);
            obs.note("regopt", regoptEnabled ? "skipped (bounds)" : "disabled");
        }
        return finalRegBase;
    }

    /**
     * Build a {@link Cfg} from the post-determinization builder list. Each
     * {@code (state, range-with-ops)} pair becomes a BASIC block; each
     * accepting state with non-empty ops becomes a FINAL block
     * (position-aware states get one FINAL block per φ variant). Arcs skip
     * zero-op transitions: a BASIC block's successors are all blocks
     * reachable from its target state through TRANSITIVE zero-op paths —
     * that transitive closure is what register liveness needs.
     */
    private Cfg buildCfg(List<DfaStateBuilder> builders, BitSet accept, int tagCount, int groupCount,
        int initialRegCount) {
        Cfg cfg = new Cfg(tagCount, groupCount, initialRegCount);
        int n = builders.size();
        // First pass: create blocks.
        int[][] rangeBlockIds = new int[n][];
        @SuppressWarnings("unchecked")
        List<Integer>[] basicLeaving = new List[n];
        int[] finalBlockAt = new int[n];
        @SuppressWarnings("unchecked")
        List<Integer>[] finalVariantBlocks = new List[n];
        for (int s = 0; s < n; s++) {
            finalVariantBlocks[s] = new ArrayList<>();
        }
        Arrays.fill(finalBlockAt, -1);
        for (int s = 0; s < n; s++) {
            basicLeaving[s] = new ArrayList<>();
        }
        for (int s = 0; s < n; s++) {
            meter.tick();
            DfaStateBuilder sb = builders.get(s);
            rangeBlockIds[s] = new int[sb.ranges.size()];
            Arrays.fill(rangeBlockIds[s], -1);
            for (int r = 0; r < sb.ranges.size(); r++) {
                meter.tick(); // per (state, range): pass 1 is real work, budget-visible
                Range range = sb.ranges.get(r);
                if (range.ops == null || range.ops.length == 0) {
                    continue;
                }
                Block blk = cfg.newBlock(Cfg.BLOCK_BASIC, s, r);
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
                        Block vb = cfg.newBlock(Cfg.BLOCK_FINAL, s, v);
                        if (sb.finalOpsVariants[v] != null) {
                            decodeOps(sb.finalOpsVariants[v], vb.ops);
                        }
                        finalVariantBlocks[s].add(cfg.blocks.size() - 1);
                    }
                } else {
                    Block fb = cfg.newBlock(Cfg.BLOCK_FINAL, s, -1);
                    if (sb.finalOpsArr != null) {
                        decodeOps(sb.finalOpsArr, fb.ops);
                    }
                    finalBlockAt[s] = cfg.blocks.size() - 1;
                }
            }
        }
        // Second pass: successor arcs. BASIC block at state s with range.target s' ->
        // all blocks (BASIC + FINAL) reachable from s' through zero-op transitions.
        long cfgEdges = 0;
        for (Block blk : cfg.blocks) {
            if (blk.kind != Cfg.BLOCK_BASIC) {
                continue;
            }
            int target = builders.get(blk.stateId).ranges.get(blk.rangeIndex).target;
            BitSet visited = new BitSet();
            ArrayDeque<Integer> frontier = new ArrayDeque<>();
            frontier.push(target);
            visited.set(target);
            while (!frontier.isEmpty()) {
                meter.tick(); // per BFS node per block: the successor-arc pass
                int t = frontier.pop();
                int arcs = basicLeaving[t].size() + finalVariantBlocks[t].size();
                if (finalBlockAt[t] != -1) {
                    arcs++;
                }
                blk.successors.addAll(basicLeaving[t]);
                if (finalBlockAt[t] != -1) {
                    blk.successors.add(finalBlockAt[t]);
                }
                blk.successors.addAll(finalVariantBlocks[t]);
                // Per ARC, not per node: materializing the dense lists is
                // the work (see maxCfgEdges).
                meter.tick(arcs);
                cfgEdges += arcs;
                if (cfgEdges > maxCfgEdges) {
                    throw new IllegalStateException("pattern too large: TDFA CFG edge budget exceeded (" + cfgEdges
                        + " successor arcs at block " + cfg.blocks.size() + "; cap " + maxCfgEdges + " — raise -D"
                        + Budgets.COMPILE_MEMORY_PROP + " if you need denser graphs)");
                }
                DfaStateBuilder tb = builders.get(t);
                for (int r = 0; r < tb.ranges.size(); r++) {
                    Range tr = tb.ranges.get(r);
                    if (tr.ops != null && tr.ops.length > 0) {
                        continue;
                    } // op-bearing: not skipped
                    if (tr.target < 0) {
                        continue;
                    }
                    if (!visited.get(tr.target)) {
                        visited.set(tr.target);
                        frontier.push(tr.target);
                    }
                }
            }
        }
        return cfg;
    }

    /**
     * Decode a flat ops triple-stream into CFG op objects.
     */
    private void decodeOps(int[] flat, List<Op> out) {
        for (int i = 0; i < flat.length; i += 3) {
            meter.tick(); // per op: decode allocates the op objects
            int op = flat[i], dst = flat[i + 1], src = flat[i + 2];
            if (op == OP_END) {
                break;
            }
            switch (op) {
                case OP_SET_POS :
                    out.add(Cfg.Op.setPos(dst));
                    break;
                case OP_SET_NIL :
                    out.add(Cfg.Op.setNil(dst));
                    break;
                case OP_COPY :
                    out.add(Cfg.Op.copy(dst, src));
                    break;
                default :
                    throw new IllegalStateException("bad op: " + op);
            }
        }
    }

    /**
     * Encode CFG op objects back into the flat (opcode, dst, src) triple
     * stream; empty op lists encode to null (the "no ops" form).
     */
    private static int[] encodeOps(List<Op> ops) {
        if (ops.isEmpty()) {
            return null;
        }
        int[] flat = new int[ops.size() * 3];
        for (int i = 0; i < ops.size(); i++) {
            Op op = ops.get(i);
            switch (op.kind) {
                case Cfg.KIND_SET :
                    flat[i * 3] = op.value == Cfg.VAL_POS ? OP_SET_POS : OP_SET_NIL;
                    flat[i * 3 + 1] = op.dst;
                    flat[i * 3 + 2] = 0;
                    break;
                case Cfg.KIND_COPY :
                    flat[i * 3] = OP_COPY;
                    flat[i * 3 + 1] = op.dst;
                    flat[i * 3 + 2] = op.src;
                    break;
                default :
                    throw new IllegalStateException("cannot encode op kind " + op.kind);
            }
        }
        return flat;
    }

    /**
     * Flush optimized CFG ops back into the builders' Range/finalOpsArr slots.
     */
    private void cfgWriteBack(Cfg cfg, List<DfaStateBuilder> builders) {
        for (Block blk : cfg.blocks) {
            int[] encoded = encodeOps(blk.ops);
            DfaStateBuilder sb = builders.get(blk.stateId);
            if (blk.kind == Cfg.BLOCK_BASIC) {
                sb.ranges.get(blk.rangeIndex).ops = encoded;
            } else if (blk.kind == Cfg.BLOCK_FINAL) {
                if (blk.rangeIndex >= 0) {
                    sb.finalOpsVariants[blk.rangeIndex] = encoded;
                } else {
                    sb.finalOpsArr = encoded;
                }
            }
        }
    }

    // ========================= flat-array materialization =========================

    /**
     * Freeze the builders into the flat int[] tables the artifact (and the
     * minimizer) consume: two passes — coalesce/sort/count, then allocate
     * and populate. After this the boxed builders have no reader left.
     *
     * <p>No gap filling: dead (target=-1) entries between live ranges are
     * semantically unnecessary — every consumer treats "no entry matches"
     * as death — and for wide Unicode classes they would double the entry
     * count (~700 live + ~700 gap fillers for \w under (?u)), halving scan
     * speed. sortByMaskSpecificity keeps ranges sorted by lo (mask bits
     * only break ties), so downstream sorted-order assumptions hold.
     */
    private FlatDfa flatten(DeterminizedDfa det, int finalRegBase) {
        int n = det.stateCount;
        // First pass: coalesce + mask-specificity sort on every state's
        // ranges, compute totals.
        int totalRanges = 0;
        int totalOpsSlots = 1; // reserve ops[0] = OP_END for the "no ops" case (opsOff=0 means empty)
        for (int s = 0; s < n; s++) {
            meter.tick();
            DfaStateBuilder sb = det.builders.get(s);
            sb.coalesce();
            sb.sortByMaskSpecificity();
            totalRanges += sb.ranges.size();
            for (Range r : sb.ranges) {
                meter.tick();
                if (r.ops != null && r.ops.length > 0) {
                    totalOpsSlots += r.ops.length + 1;
                } // +1 for OP_END
            }
            if (det.accept.get(s)) {
                int[] f = sb.finalOpsArr;
                if (f != null && f.length > 0) {
                    totalOpsSlots += f.length + 1;
                }
                if (sb.finalOpsVariants != null) {
                    for (int[] v : sb.finalOpsVariants) {
                        if (v != null && v.length > 0) {
                            totalOpsSlots += v.length + 1;
                        }
                    }
                }
            }
        }

        // Second pass: allocate flat arrays and populate.
        FlatDfa flat = new FlatDfa(n, totalRanges, totalOpsSlots, det, nfa.tagCount, finalRegBase);
        int opsHead = 1; // next free slot in ops (slot 0 reserved)
        int rangesHead = 0; // next free slot in ranges (in units of 5 ints)
        boolean[] finalVariantState = new boolean[n];
        for (int s = 0; s < n; s++) {
            meter.tick();
            DfaStateBuilder sb = det.builders.get(s);
            int k = sb.ranges.size();
            int rangeBase = rangesHead;
            for (int i = 0; i < k; i++) {
                meter.tick();
                Range r = sb.ranges.get(i);
                int o = rangesHead * 5;
                flat.ranges[o] = r.lo;
                flat.ranges[o + 1] = r.hi;
                flat.ranges[o + 2] = r.target;
                int opsOff;
                if (r.ops == null || r.ops.length == 0) {
                    opsOff = 0; // shared "empty" sentinel at ops[0]
                } else {
                    opsOff = opsHead;
                    for (int j = 0; j < r.ops.length; j += 3) {
                        flat.ops[opsHead] = r.ops[j];
                        flat.ops[opsHead + 1] = r.ops[j + 1];
                        flat.ops[opsHead + 2] = r.ops[j + 2];
                        flat.globalMaxReg = Math.max(flat.globalMaxReg, r.ops[j + 1] + 1);
                        if (r.ops[j] == OP_COPY) {
                            flat.globalMaxReg = Math.max(flat.globalMaxReg, r.ops[j + 2] + 1);
                        }
                        opsHead += 3;
                    }
                    flat.ops[opsHead++] = OP_END;
                }
                flat.ranges[o + 3] = opsOff;
                flat.ranges[o + 4] = r.requiredMask;
                rangesHead++;
            }
            int finalOpsOff = 0;
            if (sb.finalOpsArr != null && sb.finalOpsArr.length > 0) {
                finalOpsOff = opsHead;
                int[] f = sb.finalOpsArr;
                for (int j = 0; j < f.length; j += 3) {
                    flat.ops[opsHead] = f[j];
                    flat.ops[opsHead + 1] = f[j + 1];
                    flat.ops[opsHead + 2] = f[j + 2];
                    flat.globalMaxReg = Math.max(flat.globalMaxReg, f[j + 1] + 1);
                    if (f[j] == OP_COPY) {
                        flat.globalMaxReg = Math.max(flat.globalMaxReg, f[j + 2] + 1);
                    }
                    opsHead += 3;
                }
                flat.ops[opsHead++] = OP_END;
            }
            boolean isAccept = det.accept.get(s);
            flat.base[s] = rangeBase;
            if (k > 0xFFFF) {
                // The 16-bit rangeCount pack in stateMeta would silently
                // wrap (validate cannot detect it post-pack — the count
                // reads back wrong-but-plausible). Fail the compile loudly.
                throw new IllegalStateException("tdfa: state " + s + " needs " + k
                    + " range entries — exceeds the 16-bit rangeCount packing (pattern too large)");
            }
            flat.meta[s] = ((k & 0xFFFF) << 1) | (isAccept ? 1 : 0);
            flat.finalOpsOff[s] = finalOpsOff;
            if (sb.finalOpsVariants != null && isAccept) {
                finalVariantState[s] = true;
                if (flat.finalOpsByMask == null) {
                    flat.finalOpsByMask = new int[n * 64];
                }
                int[] variantOff = new int[sb.finalOpsVariants.length];
                for (int v = 0; v < variantOff.length; v++) {
                    int[] f = sb.finalOpsVariants[v];
                    variantOff[v] = 0; // empty ops: accept fires, no ops
                    if (f != null && f.length > 0) {
                        variantOff[v] = opsHead;
                        for (int j = 0; j < f.length; j += 3) {
                            flat.ops[opsHead] = f[j];
                            flat.ops[opsHead + 1] = f[j + 1];
                            flat.ops[opsHead + 2] = f[j + 2];
                            flat.globalMaxReg = Math.max(flat.globalMaxReg, f[j + 1] + 1);
                            if (f[j] == OP_COPY) {
                                flat.globalMaxReg = Math.max(flat.globalMaxReg, f[j + 2] + 1);
                            }
                            opsHead += 3;
                        }
                        flat.ops[opsHead++] = OP_END;
                    }
                }
                for (int M = 0; M < 64; M++) {
                    int v = sb.finalMaskVariant[M];
                    flat.finalOpsByMask[s * 64 + M] = v < 0 ? -1 : variantOff[v];
                }
                if (flat.finalOpsOff[s] == 0 && variantOff.length > 0) {
                    flat.finalOpsOff[s] = variantOff[0];
                } // sane default for non-runtime consumers
            }
        }
        if (flat.finalOpsByMask != null) {
            // The table is authoritative for every state when present:
            // uniform accepting states point all 64 cells at their φ;
            // non-accepting states stay all -1 (never read).
            for (int s = 0; s < n; s++) {
                if (!finalVariantState[s]) {
                    int off = (flat.meta[s] & 1) != 0 ? flat.finalOpsOff[s] : -1;
                    Arrays.fill(flat.finalOpsByMask, s * 64, s * 64 + 64, off);
                }
            }
        }
        return flat;
    }

    // ========================= minimization (paper §6.2.2) =========================

    /**
     * Minimize via register-aware Moore's algorithm: transitions on the
     * same symbol but with different register ops count as different
     * transitions (op sequences interned to unique ids for O(1)
     * comparison — the paper's "unique numeric identifiers"). Comparison
     * may have false negatives (non-identical but semantically equivalent
     * op lists), which only yields a suboptimal — not incorrect —
     * minimization; running after register optimization gives the best
     * input. On success the partition is renumbered so the start state's
     * class becomes state 0 and the flat arrays are rebuilt over class
     * representatives.
     *
     * <p>Skipped above the {@code tdfa.minimize.max} bound (default
     * 20000) — Moore is O(n²) worst-case and subset construction with
     * map-dedup already tends to produce minimal DFAs (dictionary
     * alternations save ~30s of pure overhead by skipping). The fixpoint
     * is metered; because the unminimized DFA is still correct, budget
     * exhaustion here DEGRADES (skip the pass) rather than failing the
     * compile.
     */
    private void minimize(FlatDfa flat) {
        long tMin = System.nanoTime();
        // Toggle post-determinization minimization: default on
        // (-Dtdfa.nominimize disables). Knob policy: Tdfa javadoc.
        final boolean minimizeEnabled = !Boolean.getBoolean("tdfa.nominimize");
        final int minimizeMaxStates = Integer.getInteger("tdfa.minimize.max", 20000);
        int n = flat.stateCount;
        if (minimizeEnabled && n > 1 && n <= minimizeMaxStates) {
            int[] partition;
            try {
                DfaMinimizer m = new DfaMinimizer(n, flat.meta, flat.base, flat.finalOpsOff, flat.ranges, flat.ops,
                    flat.entryMask, flat.acceptMask, flat.stopOnAcceptMask, flat.finalOpsByMask, longest, meter);
                partition = m.computePartition();
            } catch (WorkMeter.Exhausted overBudget) {
                obs.note("minimize", "skipped (compute budget)");
                if (debug) {
                    System.err.println("[tdfa] minimize degraded: " + overBudget.getMessage());
                }
                partition = null;
            }
            if (partition != null) {
                applyPartition(flat, partition);
            }
        }
        obs.stage(CompileObserver.Stage.MINIMIZE, System.nanoTime() - tMin, flat.stateCount);
    }

    /**
     * Rewrite {@code flat} in place over the Moore partition: each class
     * keeps its representative (first state) arrays, range targets are
     * remapped through the partition, and shared tables (entry/accept
     * masks, stop table, per-M final ops) are copied per class. Ops
     * blocks are untouched — offsets, not contents, identify them.
     */
    private void applyPartition(FlatDfa flat, int[] partition) {
        int n = flat.stateCount;
        int newN = 0;
        for (int p : partition) {
            newN = Math.max(newN, p + 1);
        }
        if (newN >= n) {
            return;
        }
        // Renumber so the start state's partition becomes state 0 (preserves invariant).
        int[] renum = new int[newN];
        Arrays.fill(renum, -1);
        int nextId = 0;
        for (int s = 0; s < n; s++) {
            int p = partition[s];
            if (renum[p] == -1) {
                renum[p] = nextId++;
            }
        }
        newN = nextId;
        int[] rep = new int[newN];
        Arrays.fill(rep, -1);
        for (int s = 0; s < n; s++) {
            int g = renum[partition[s]];
            partition[s] = g;
            if (rep[g] == -1) {
                rep[g] = s;
            }
        }
        int newTotalRanges = 0;
        for (int g = 0; g < newN; g++) {
            newTotalRanges += rangeCount(flat.meta[rep[g]]);
        }
        int[] minMeta = new int[newN];
        int[] minBase = new int[newN];
        int[] minFinalOpsOff = new int[newN];
        int[] minEntryMask = new int[newN];
        int[] minAcceptMask = new int[newN];
        int[] minStopMask = flat.stopOnAcceptMask != null ? new int[newN * 64] : null;
        int[] minRanges = new int[newTotalRanges * 5];
        int[] minFinalOpsByMask = flat.finalOpsByMask != null ? new int[newN * 64] : null;
        int minRangesHead = 0;
        for (int g = 0; g < newN; g++) {
            int r = rep[g];
            minMeta[g] = flat.meta[r];
            minBase[g] = minRangesHead;
            minFinalOpsOff[g] = flat.finalOpsOff[r];
            minEntryMask[g] = flat.entryMask[r];
            minAcceptMask[g] = flat.acceptMask[r];
            if (minStopMask != null) {
                System.arraycopy(flat.stopOnAcceptMask, r * 64, minStopMask, g * 64, 64);
            }
            if (minFinalOpsByMask != null) {
                System.arraycopy(flat.finalOpsByMask, r * 64, minFinalOpsByMask, g * 64, 64);
            }
            int base = flat.base[r];
            int count = rangeCount(flat.meta[r]);
            for (int i = 0; i < count; i++) {
                int o = (base + i) * 5;
                int no = minRangesHead * 5;
                minRanges[no] = flat.ranges[o];
                minRanges[no + 1] = flat.ranges[o + 1];
                int t = flat.ranges[o + 2];
                minRanges[no + 2] = (t == -1) ? -1 : partition[t];
                minRanges[no + 3] = flat.ranges[o + 3];
                minRanges[no + 4] = flat.ranges[o + 4];
                minRangesHead++;
            }
        }
        if (debug) {
            System.err.println("[tdfa] minimized: " + n + " -> " + newN + " states");
        }
        flat.stateCount = newN;
        flat.meta = minMeta;
        flat.base = minBase;
        flat.finalOpsOff = minFinalOpsOff;
        flat.entryMask = minEntryMask;
        flat.acceptMask = minAcceptMask;
        flat.stopOnAcceptMask = minStopMask;
        flat.ranges = minRanges;
        flat.finalOpsByMask = minFinalOpsByMask;
    }

    // ========================= artifact assembly =========================

    /**
     * Ensure per-state range entries are sorted by lo (stable: equal-lo
     * groups keep their mask-specificity order). The builders emit
     * sorted, but the minimizer / regopt rewrite can reorder within a
     * state; the runtime's binary search over lo requires the order.
     */
    private void ensureRangesSorted(FlatDfa flat) {
        for (int s = 0; s < flat.stateCount; s++) {
            int cnt = (flat.meta[s] >>> 1) & 0xFFFF, b = flat.base[s];
            boolean sorted = true;
            for (int i = 1; i < cnt; i++) {
                if (flat.ranges[(b + i) * 5] < flat.ranges[(b + i - 1) * 5]) {
                    sorted = false;
                    break;
                }
            }
            if (sorted) {
                continue;
            }
            // Pack (lo << 32) | original index for a stable sort by lo, then
            // permute the 5-int entry groups in place.
            long[] keys = new long[cnt];
            for (int i = 0; i < cnt; i++) {
                keys[i] = ((long) flat.ranges[(b + i) * 5] << 32) | i;
            }
            Arrays.sort(keys);
            int[] tmp = new int[cnt * 5];
            for (int i = 0; i < cnt; i++) {
                int src = (int) (keys[i] & 0xFFFFFFFFL) * 5;
                System.arraycopy(flat.ranges, (b + src) * 5, tmp, i * 5, 5);
            }
            System.arraycopy(tmp, 0, flat.ranges, b * 5, cnt * 5);
        }
    }

    /**
     * Rebuild the per-entry hi-prefix (max hi of all earlier entries in
     * the state) over the final, possibly remapped arrays — the runner's
     * quick-reject scan over wide ranges.
     */
    private int[] buildHiPrefix(FlatDfa flat) {
        int[] hiPrefix = new int[flat.ranges.length / 5];
        for (int s = 0; s < flat.stateCount; s++) {
            int cnt = (flat.meta[s] >>> 1) & 0xFFFF, b = flat.base[s], maxHi = Integer.MIN_VALUE;
            for (int i = 0; i < cnt; i++) {
                int hi = flat.ranges[(b + i) * 5 + 1];
                if (hi > maxHi) {
                    maxHi = hi;
                }
                hiPrefix[b + i] = maxHi;
            }
        }
        return hiPrefix;
    }

    /**
     * Assemble the {@link Tdfa}: pick the stop-table storage tier (full
     * int[n*64] table vs 1 B/state uniform tier vs none in POSIX), report
     * the materialization facts (the "tables" note: state/range/byte
     * counts for memory attribution), and construct the frozen artifact.
     */
    private Tdfa assemble(FlatDfa flat, int[] hiPrefix) {
        int stateCount = flat.stateCount;
        // Uniformity facts for the stop-table tier: per-state (all 64
        // posFlags cells identical within each state) and global (... and
        // identical across states).
        boolean perStateUniform = true;
        boolean globalUniform = true;
        int acceptCnt = 0;
        for (int s = 0; s < stateCount; s++) {
            if ((flat.meta[s] & 1) != 0) {
                acceptCnt++;
            }
        }
        // POSIX has no stop table at all; report the same "uniform"
        // attribution it always had (the all-NEVER_STOP fill it would
        // trivially satisfy) without the O(n*64) scan.
        if (flat.stopOnAcceptMask != null) {
            int globalVal = flat.stopOnAcceptMask.length > 0 ? flat.stopOnAcceptMask[0] : 0;
            for (int s = 0; s < stateCount && perStateUniform; s++) {
                int v0 = flat.stopOnAcceptMask[s * 64];
                for (int m = 1; m < 64; m++) {
                    if (flat.stopOnAcceptMask[s * 64 + m] != v0) {
                        perStateUniform = false;
                        globalUniform = false;
                        break;
                    }
                }
                if (v0 != globalVal) {
                    globalUniform = false;
                }
            }
        }
        // Storage tier: POSIX -> neither (readers gate on Perl mode);
        // Perl + per-state-uniform -> byte[n]; general Perl -> int[n*64].
        byte[] uniformStop = null;
        int[] finalStop = null;
        if (!longest) {
            if (perStateUniform) {
                uniformStop = new byte[stateCount];
                for (int s = 0; s < stateCount; s++) {
                    uniformStop[s] = flat.stopOnAcceptMask[s * 64] != 0 ? (byte) 1 : 0;
                }
            } else {
                finalStop = flat.stopOnAcceptMask;
            }
        }
        obs.note("tables",
            "states=" + stateCount + " ranges=" + (flat.ranges.length / 5) + " accept=" + acceptCnt + " bytes{ranges="
                + (flat.ranges.length * 4L) + ",stopMask="
                + (uniformStop != null ? uniformStop.length : finalStop != null ? finalStop.length * 4L : 0)
                + ",entryMask=" + (flat.entryMask.length * 4L) + ",acceptMask=" + (flat.acceptMask.length * 4L)
                + ",ops=" + (flat.ops.length * 4L) + ",hiPrefix=" + (hiPrefix.length * 4L) + ",scalars="
                + ((flat.meta.length + flat.base.length + flat.finalOpsOff.length) * 4L + stateCount) + "}"
                + " stopMaskUniform=" + (perStateUniform ? (globalUniform ? "global" : "perState") : "no"));
        return new Tdfa(nfa.tagCount, nfa.groupCount, nfa.namedGroups, flat.globalMaxReg, flat.finalRegBase, 0,
            stateCount, flat.meta, flat.base, flat.finalOpsOff, flat.finalOpsByMask, flat.ranges, flat.ops, hiPrefix,
            flat.entryMask, flat.acceptMask, longest, finalStop, uniformStop, nfa.multiline, nfa.unicodeWordBoundary,
            nfa.wordRanges, hasFixed(nfa.fixedBase) ? nfa.fixedBase : null,
            hasFixed(nfa.fixedBase) ? nfa.fixedOffset : null, flat.pikeCutMatters);
    }

    /**
     * True iff the fixed-tag annotation table names any fixed tag.
     */
    private static boolean hasFixed(int[] fixedBase) {
        if (fixedBase == null) {
            return false;
        }
        for (int i = 1; i < fixedBase.length; i++) {
            if (fixedBase[i] != 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Flat-array form of the DFA: the builders' range/ops lists frozen
     * into int[] tables. Minimization may replace the per-state arrays
     * with renumbered copies (fields are mutable for that rewrite); the
     * shared ops stream is never rewritten — remapping only touches range
     * targets and per-state offsets.
     */
    private static final class FlatDfa {
        int stateCount;
        /**
         * [state] → packed (rangeCount << 1) | acceptBit.
         */
        int[] meta;
        /**
         * [state] → range base index into ranges.
         */
        int[] base;
        /**
         * [state] → final-ops offset into ops (0 = the shared empty block).
         */
        int[] finalOpsOff;
        /**
         * 5 ints per entry: lo, hi, target, opsOff, requiredMask.
         */
        int[] ranges;
        /**
         * Op stream, 3 ints per op + OP_END terminator; ops[0] = OP_END is
         * the shared "no ops" sentinel.
         */
        final int[] ops;
        int[] entryMask;
        int[] acceptMask;
        /**
         * [state * 64 + posFlags] stop decisions; null in POSIX.
         */
        int[] stopOnAcceptMask;
        /**
         * [state * 64 + posFlags] → final-ops offset or -1; null unless
         * some state has position-aware φ variants.
         */
        int[] finalOpsByMask;
        final boolean pikeCutMatters;
        int globalMaxReg;
        int finalRegBase;

        FlatDfa(int stateCount, int totalRanges, int totalOpsSlots, DeterminizedDfa det, int tagCount,
            int finalRegBase) {
            this.stateCount = stateCount;
            this.meta = new int[stateCount];
            this.base = new int[stateCount];
            this.finalOpsOff = new int[stateCount];
            this.ranges = new int[totalRanges * 5];
            this.ops = new int[totalOpsSlots];
            this.ops[0] = OP_END; // opsOff=0 means "empty block"
            this.entryMask = det.entryMask;
            this.acceptMask = det.acceptMask;
            this.stopOnAcceptMask = det.stopOnAcceptMask;
            this.pikeCutMatters = det.pikeCutMatters;
            this.globalMaxReg = 2 * tagCount; // at least the working + final register blocks
            this.finalRegBase = finalRegBase;
        }
    }
}
