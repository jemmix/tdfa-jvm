package io.github.jemmix.tdfa.tdfa;

import java.util.HashMap;
import java.util.Map;

    /**
     * Register-aware Moore's algorithm for tagged-DFA minimization (paper §6.2.2).
     *
     * <p>Two states are equivalent iff:
     * <ol>
     *   <li>Same accept bit, entry mask, accept mask, Perl stop-on-accept mask
     *       (behavioral attributes that affect runtime control flow);</li>
     *   <li>Same final-ops content (capture effects on accept);</li>
     *   <li>For every input range, transitions go to equivalent states with
     *       bit-identical transition-ops content.</li>
     * </ol>
     *
     * <p>Op sequences are interned to unique numeric IDs (paper's recommended
     * O(1) comparison strategy). Comparison may have false negatives —
     * non-identical but semantically equivalent op lists are treated as
     * different — but this only yields a suboptimal minimization, never an
     * incorrect one. Best results require applying after register optimizations
     * (not yet implemented), which can normalize op lists.
     *
     * <p><b>Range normalization:</b> states whose transitions have the same
     * per-codepoint behavior but different range boundaries (e.g. one state
     * has [(0..9), (10..MAX)] and another has [(0..99), (100..MAX)] with the
     * same targets) should still merge. We compute global breakpoints (the
     * union of every state's range boundaries) and rebuild each state's
     * transition signature on the global partition. This is the difference
     * between minimization working for large literal-alternation DFAs
     * (dictionary, lexer) vs. not working at all. If any state has overlapping
     * ranges (assertion-gated transitions), we conservatively fall back to the
     * unnormalized form for the whole DFA.
     *
     * <p>Complexity: O((n + R) · I) where R = total ranges and I = iterations
     * to fixpoint (bounded by n in the worst case, typically O(log n)).
     */
    final class DfaMinimizer {
        final int n;
        final int[] stateMeta, stateBase, stateFinalOpsOff, ranges, ops;
        final int[] stateEntryMask, stateAcceptMask, stateStopOnAcceptMask;
        final int[] stateFinalOpsByMask;
        final boolean longest;
        /** Cap on n×K range-normalization cells (read per compile, like the
         *  other determinization budgets). Default 32 M cells = 128 MiB of
         *  compile-time scratch worst case. Override -Dtdfa.minimize.norm.cells. */
        final long maxNormCells;
        /** Op-sequence interning: maps the byte content of an OP_END-terminated block to a unique int id. */
        final Map<OpSeq, Integer> opSeqIds = new HashMap<>();
        /** Cached op-sequence id per ops[] offset (lazily computed). -1 = not computed. */
        final int[] opsIdAt;

        /** Global breakpoints partitioning the codepoint space; sorted ascending, includes 0 and 0x110000 sentinel. */
        int[] globalBps;
        /** Per (state, global-bp-index): the state-range index covering that global range, or -1 if no range. */
        int[] stateRangeAt;
        /** True iff every state's ranges are non-overlapping (so per-bp lookup is well-defined). */
        boolean useNormalized;

        DfaMinimizer(int n, int[] stateMeta, int[] stateBase, int[] stateFinalOpsOff,
                     int[] ranges, int[] ops, int[] stateEntryMask, int[] stateAcceptMask,
                     int[] stateStopOnAcceptMask, int[] stateFinalOpsByMask, boolean longest) {
            this.n = n;
            this.stateMeta = stateMeta;
            this.stateBase = stateBase;
            this.stateFinalOpsOff = stateFinalOpsOff;
            this.ranges = ranges;
            this.ops = ops;
            this.stateEntryMask = stateEntryMask;
            this.stateAcceptMask = stateAcceptMask;
            this.stateStopOnAcceptMask = stateStopOnAcceptMask;
            this.stateFinalOpsByMask = stateFinalOpsByMask;
            this.longest = longest;
            this.maxNormCells = Integer.getInteger("tdfa.minimize.norm.cells", 1 << 25);
            this.opsIdAt = new int[ops.length];
            java.util.Arrays.fill(this.opsIdAt, -1);
            detectOverlapsAndInit();
        }

        /** Detect overlapping ranges; if any state has them, disable normalization (conservative fallback). */
        private void detectOverlapsAndInit() {
            useNormalized = true;
            outer:
            for (int s = 0; s < n; s++) {
                int base = stateBase[s];
                int count = Tdfa.rangeCount(stateMeta[s]);
                int prevHi = -1;
                for (int r = 0; r < count; r++) {
                    int o = (base + r) * 5;
                    int lo = ranges[o];
                    if (lo <= prevHi) { useNormalized = false; break outer; }
                    prevHi = ranges[o + 1];
                }
            }
            if (!useNormalized) return;
            computeGlobalBreakpoints();
            computeStateRangeMapping();
        }

        private void computeGlobalBreakpoints() {
            java.util.TreeSet<Integer> bps = new java.util.TreeSet<>();
            bps.add(0);
            bps.add(0x110000);  // sentinel upper bound (exclusive)
            for (int s = 0; s < n; s++) {
                int base = stateBase[s];
                int count = Tdfa.rangeCount(stateMeta[s]);
                for (int r = 0; r < count; r++) {
                    int o = (base + r) * 5;
                    bps.add(ranges[o]);
                    int hi = ranges[o + 1];
                    if (hi < 0x10FFFF) bps.add(hi + 1);
                }
            }
            globalBps = new int[bps.size()];
            int i = 0;
            for (int b : bps) globalBps[i++] = b;
        }

        /** Per state, per global bp, find the state-range index covering it. Linear merge scan.
         *  Dimension guard [review P1 #3]: n×K is bounded by a cell budget read
         *  PER COMPILE (constructor stores it) — K is the union of every
         *  state's breakpoints and no other cap covers it, so without this a
         *  wide DFA under the 20 K-state minimize gate could overflow int
         *  (NegativeArraySizeException) or request a multi-GB row table. Over
         *  the budget, minimization degrades to the unnormalized (correct,
         *  less-merging) path — same fallback as overlapping ranges. */
        private void computeStateRangeMapping() {
            int K = globalBps.length;
            if ((long) n * (long) K > maxNormCells) {
                useNormalized = false;
                stateRangeAt = null;
                return;
            }
            stateRangeAt = new int[n * K];
            for (int s = 0; s < n; s++) {
                int base = stateBase[s];
                int count = Tdfa.rangeCount(stateMeta[s]);
                int rangeIdx = 0;
                int rowBase = s * K;
                for (int k = 0; k < K; k++) {
                    int cp = globalBps[k];
                    if (cp >= 0x110000) { stateRangeAt[rowBase + k] = -1; continue; }
                    while (rangeIdx < count && ranges[(base + rangeIdx) * 5 + 1] < cp) rangeIdx++;
                    if (rangeIdx < count) {
                        int o = (base + rangeIdx) * 5;
                        stateRangeAt[rowBase + k] = (ranges[o] <= cp) ? rangeIdx : -1;
                    } else {
                        stateRangeAt[rowBase + k] = -1;
                    }
                }
            }
        }

        /**
         * Return the unique numeric id for the OP_END-terminated op block starting at {@code off}.
         * Two blocks with bit-identical content return the same id (paper's O(1) comparison).
         */
        int opSeqId(int off) {
            if (off < 0 || off >= opsIdAt.length) return 0;
            int cached = opsIdAt[off];
            if (cached != -1) return cached;
            int p = off;
            while (p < ops.length && ops[p] != Tdfa.OP_END) p += 3;
            OpSeq key = new OpSeq(ops, off, p);
            Integer id = opSeqIds.get(key);
            if (id == null) { id = opSeqIds.size() + 1; opSeqIds.put(key, id); }
            opsIdAt[off] = id;
            return id;
        }

        /** Compute the partition (mapping old state id -> new state id) via Moore's algorithm. */
        int[] computePartition() {
            int[] partition = initialPartition();
            int groups = 0;
            for (int p : partition) groups = Math.max(groups, p + 1);
            if (groups == n) return partition;  // every state already unique; no merging possible

            boolean changed = true;
            int iter = 0;
            while (changed && iter < n + 5) {
                changed = false;
                Map<SigKey, Integer> newGroupMap = new HashMap<>();
                int[] newPartition = new int[n];
                int nextGroup = 0;
                for (int s = 0; s < n; s++) {
                    SigKey key = transSig(s, partition);
                    Integer g = newGroupMap.get(key);
                    if (g == null) { g = nextGroup++; newGroupMap.put(key, g); }
                    newPartition[s] = g;
                }
                if (!java.util.Arrays.equals(partition, newPartition)) {
                    changed = true;
                    partition = newPartition;
                }
                iter++;
            }
            return partition;
        }

        /** Initial partition: group states by per-state attributes (accept, final-ops, masks). */
        private int[] initialPartition() {
            int[] partition = new int[n];
            Map<SigKey, Integer> groupMap = new HashMap<>();
            int nextGroup = 0;
            for (int s = 0; s < n; s++) {
                SigKey key = attrSig(s);
                Integer g = groupMap.get(key);
                if (g == null) { g = nextGroup++; groupMap.put(key, g); }
                partition[s] = g;
            }
            return partition;
        }

    /** Per-state attribute signature: accept bit, final-ops id, masks. */
    SigKey attrSig(int s) {
        int[] sig = new int[5 + attrExtra()];
        fillAttrs(sig, s, 0);
        return new SigKey(sig);
    }

    /** Extra sig slots occupied by the full 64-cell rows (never a summary:
     *  two states whose rows differ must never share a Moore group — an
     *  earlier 32-bit rolling hash admitted birthday collisions (~2⁻³²/pair
     *  over distinct rows) that silently merged semantically different
     *  states. Exact cells close that hole by construction.) */
    int attrExtra() {
        return (!longest ? 64 : 0) + (stateFinalOpsByMask != null ? 64 : 0);
    }

    /** Fill the per-state attribute prefix into sig starting at index i. Returns new index. */
    int fillAttrs(int[] sig, int s, int i) {
        sig[i++] = stateMeta[s] & 1;
        sig[i++] = opSeqId(stateFinalOpsOff[s]);
        sig[i++] = stateEntryMask[s];
        sig[i++] = stateAcceptMask[s];
        sig[i++] = (stateMeta[s] >>> 1) & 0xFFFF;  // range count (structural disambiguator)
        if (!longest) {
            int baseSM = s * 64;
            for (int j = 0; j < 64; j++) sig[i++] = stateStopOnAcceptMask[baseSM + j];
        }
        if (stateFinalOpsByMask != null) {
            // Variant rows: states with different per-mask φ selections
            // (or different accept suppression) must never merge.
            int baseFM = s * 64;
            for (int j = 0; j < 64; j++) sig[i++] = stateFinalOpsByMask[baseFM + j];
        }
        return i;
    }

    /** Transition signature, normalized on global breakpoints when possible. */
    SigKey transSig(int s, int[] partition) {
        int extra = attrExtra();
            int base = stateBase[s];
            int count = Tdfa.rangeCount(stateMeta[s]);
            int[] sig;
            int i;
            if (useNormalized) {
                int K = globalBps.length - 1;  // # of codepoint-covering ranges
                sig = new int[5 + extra + K * 3];
                i = fillAttrs(sig, s, 0);
                int rowBase = s * globalBps.length;
                for (int k = 0; k < K; k++) {
                    int rIdx = stateRangeAt[rowBase + k];
                    if (rIdx < 0) {
                        sig[i++] = -1; sig[i++] = 0; sig[i++] = 0;
                    } else {
                        int o = (base + rIdx) * 5;
                        int t = ranges[o + 2];
                        sig[i++] = (t == -1) ? -1 : partition[t];
                        sig[i++] = opSeqId(ranges[o + 3]);
                        sig[i++] = ranges[o + 4];
                    }
                }
            } else {
                // Unnormalized fallback: per-range (lo, hi, target_partition, opSeqId, requiredMask).
                sig = new int[5 + extra + count * 5];
                i = fillAttrs(sig, s, 0);
                for (int r = 0; r < count; r++) {
                    int o = (base + r) * 5;
                    int t = ranges[o + 2];
                    sig[i++] = ranges[o];                                  // lo
                    sig[i++] = ranges[o + 1];                              // hi
                    sig[i++] = (t == -1) ? -1 : partition[t];              // target's current partition
                    sig[i++] = opSeqId(ranges[o + 3]);                     // transition-ops content id
                    sig[i++] = ranges[o + 4];                              // requiredMask
                }
            }
            return new SigKey(sig);
        }
    }
