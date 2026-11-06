package io.github.jemmix.tdfa.core.determinize;

import io.github.jemmix.tdfa.core.budget.PatternTooLargeException;
import io.github.jemmix.tdfa.core.budget.WorkMeter;
import io.github.jemmix.tdfa.core.dfa.OpSeq;
import io.github.jemmix.tdfa.core.dfa.Tdfa;

import java.util.Arrays;
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
 * incorrect one. Applying after register optimization (as the pipeline
 * does) narrows the false negatives: coalesced states then carry
 * identical op lists.
 *
 * <p>Signatures are built per stored range row (lo, hi, target, ops,
 * requiredMask) — no range normalization. A normalized variant (rebuilding
 * signatures on the union of every state's breakpoints so states with the
 * same per-codepoint behavior but different boundaries could merge) was
 * retired by measurement (2026-11-06): with normalization compiled off,
 * final state counts matched ±0 on every CompileLatencyGuardTest bomb,
 * all 334 compiled rebar corpus scenarios, and the 58-pattern
 * determinism/partial-whole corpus — including the dictionary
 * alternation (19 986 → 7 087 states) the pass was built for — while
 * the normalize path's n×K row table cost 2-4× minimize wall on exactly
 * those shapes. See complexity-review C26.
 *
 * <p>Complexity: O((n + R) · I) where R = total ranges and I = iterations
 * to fixpoint (bounded by n in the worst case, typically O(log n)).
 */
final class DfaMinimizer {
    final int n;
    final int[] stateMeta, stateBase, stateFinalOpsOff, ranges, ops;
    final int[] stateEntryMask, stateAcceptMask, stateStopOnAcceptMask;
    final int[] stateFinalOpsByMask;
    /**
     * Partial-whole side table (null = none): wholeBase/wholeCount/
     * wholeRanges mirror the pruned arrays' layout for the whole-walk
     * relation. Two states may only merge when their WHOLE behavior
     * matches too — the whole walk is a reader of the merged state — so
     * the whole entries join both the attribute and transition
     * signatures (same per-range rows as the pruned transitions).
     */
    final int[] wholeBase, wholeCount, wholeRanges;
    final boolean longest;
    /**
     * Cells per state in the posFlags-indexed tables (stop rows, final-φ
     * rows) — the stride of {@link #stateStopOnAcceptMask} and
     * {@link #stateFinalOpsByMask}, from the determinized value.
     */
    final int posFlagCells;
    /**
     * Compile work meter (shared with determinization/regopt): the
     * Moore fixpoint is O((n+R)·I) — I rounds of n signature builds —
     * and without metering would be an unbounded loop inside
     * compile(): near-cap literal-chain DFAs can peel one group per
     * round ⇒ O(n²) sig builds, minutes of unmetered wall time.
     * Exhaustion propagates as {@link PatternTooLargeException}; the
     * CALLER degrades to the unminimized DFA instead of failing the
     * compile (minimization is an optional pass).
     */
    final WorkMeter meter;
    /**
     * Op-sequence interning: maps the byte content of an OP_END-terminated block to a unique int id.
     */
    final Map<OpSeq, Integer> opSeqIds = new HashMap<>();
    /**
     * Cached op-sequence id per ops[] offset (lazily computed). -1 = not computed.
     */
    final int[] opsIdAt;

    DfaMinimizer(int n, int[] stateMeta, int[] stateBase, int[] stateFinalOpsOff, int[] ranges, int[] ops,
        int[] stateEntryMask, int[] stateAcceptMask, int[] stateStopOnAcceptMask, int[] stateFinalOpsByMask,
        int[] wholeBase, int[] wholeCount, int[] wholeRanges, boolean longest, int posFlagCells, WorkMeter meter) {
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
        this.wholeBase = wholeRanges == null ? null : wholeBase;
        this.wholeCount = wholeRanges == null ? null : wholeCount;
        this.wholeRanges = wholeRanges;
        this.longest = longest;
        this.posFlagCells = posFlagCells;
        this.meter = meter;
        this.opsIdAt = new int[ops.length];
        Arrays.fill(this.opsIdAt, -1);
    }

    /**
     * Return the unique numeric id for the OP_END-terminated op block starting at {@code off}.
     * Two blocks with bit-identical content return the same id (paper's O(1) comparison).
     */
    int opSeqId(int off) {
        if (off < 0 || off >= opsIdAt.length) {
            return 0;
        }
        int cached = opsIdAt[off];
        if (cached != -1) {
            return cached;
        }
        int p = off;
        while (p < ops.length && ops[p] != Tdfa.OP_END) {
            p += 3;
        }
        OpSeq key = new OpSeq(ops, off, p);
        Integer id = opSeqIds.get(key);
        if (id == null) {
            id = opSeqIds.size() + 1;
            opSeqIds.put(key, id);
        }
        opsIdAt[off] = id;
        return id;
    }

    /**
     * Compute the partition (mapping old state id -> new state id) via Moore's algorithm.
     */
    int[] computePartition() {
        meter.tick(n); // initial partition: n attribute-signature builds
        int[] partition = initialPartition();
        int groups = 0;
        for (int p : partition) {
            groups = Math.max(groups, p + 1);
        }
        if (groups == n) {
            return partition;
        } // every state already unique; no merging possible

        boolean changed = true;
        int iter = 0;
        while (changed && iter < n + 5) {
            changed = false;
            Map<SigKey, Integer> newGroupMap = new HashMap<>();
            int[] newPartition = new int[n];
            int nextGroup = 0;
            for (int s = 0; s < n; s++) {
                meter.tick(); // one transition-signature build per state per round
                SigKey key = transSig(s, partition);
                Integer g = newGroupMap.get(key);
                if (g == null) {
                    g = nextGroup++;
                    newGroupMap.put(key, g);
                }
                newPartition[s] = g;
            }
            if (!Arrays.equals(partition, newPartition)) {
                changed = true;
                partition = newPartition;
            }
            iter++;
        }
        return partition;
    }

    /**
     * Initial partition: group states by per-state attributes (accept, final-ops, masks).
     */
    private int[] initialPartition() {
        int[] partition = new int[n];
        Map<SigKey, Integer> groupMap = new HashMap<>();
        int nextGroup = 0;
        for (int s = 0; s < n; s++) {
            SigKey key = attrSig(s);
            Integer g = groupMap.get(key);
            if (g == null) {
                g = nextGroup++;
                groupMap.put(key, g);
            }
            partition[s] = g;
        }
        return partition;
    }

    /**
     * Per-state attribute signature: accept bit, final-ops id, masks,
     * and — when the partial-whole side table exists — the whole-side
     * entry count (a state carrying a whole list must never merge with
     * one that doesn't, whatever its pruned behavior).
     */
    SigKey attrSig(int s) {
        int[] sig = new int[5 + attrExtra() + (wholeRanges != null ? 1 : 0)];
        fillAttrs(sig, s, 0);
        if (wholeRanges != null) {
            sig[sig.length - 1] = wholeBase != null && wholeBase[s] >= 0 ? wholeCount[s] : 0;
        }
        return new SigKey(sig);
    }

    /**
     * Extra sig slots occupied by the full table rows (never a summary:
     * two states whose rows differ must never share a Moore group — a
     * 32-bit rolling hash would admit birthday collisions (~2⁻³²/pair
     * over distinct rows) and silently merge semantically different
     * states. Exact cells close that hole by construction.)
     */
    int attrExtra() {
        return (!longest ? posFlagCells : 0) + (stateFinalOpsByMask != null ? posFlagCells : 0);
    }

    /**
     * Fill the per-state attribute prefix into sig starting at index i. Returns new index.
     */
    int fillAttrs(int[] sig, int s, int i) {
        sig[i++] = stateMeta[s] & 1;
        sig[i++] = opSeqId(stateFinalOpsOff[s]);
        sig[i++] = stateEntryMask[s];
        sig[i++] = stateAcceptMask[s];
        sig[i++] = (stateMeta[s] >>> 1) & 0xFFFF; // range count (structural disambiguator)
        if (!longest) {
            int baseSM = s * posFlagCells;
            for (int j = 0; j < posFlagCells; j++) {
                sig[i++] = stateStopOnAcceptMask[baseSM + j];
            }
        }
        if (stateFinalOpsByMask != null) {
            // Variant rows: states with different per-mask φ selections
            // (or different accept suppression) must never merge.
            int baseFM = s * posFlagCells;
            for (int j = 0; j < posFlagCells; j++) {
                sig[i++] = stateFinalOpsByMask[baseFM + j];
            }
        }
        return i;
    }

    /**
     * Transition signature, one row per stored range
     * (lo, hi, target_partition, opSeqId, requiredMask). Whole-side
     * entries (when present) are appended the same way — the whole
     * walk's dispatch must stay distinct per state, so any whole-row
     * difference blocks the merge (false negatives only cost merging
     * quality, never correctness).
     */
    SigKey transSig(int s, int[] partition) {
        int extra = attrExtra();
        int base = stateBase[s];
        int count = Tdfa.rangeCount(stateMeta[s]);
        int wCount = wholeRows(s);
        // Per-range rows: (lo, hi, target_partition, opSeqId, requiredMask).
        int[] sig = new int[5 + extra + (wholeRanges != null ? 1 : 0) + count * 5 + wCount * 5];
        int i = fillAttrs(sig, s, 0);
        if (wholeRanges != null) {
            sig[i++] = wholeBase != null && wholeBase[s] >= 0 ? wholeCount[s] : 0;
        }
        for (int r = 0; r < count; r++) {
            int o = (base + r) * 5;
            int t = ranges[o + 2];
            sig[i++] = ranges[o]; // lo
            sig[i++] = ranges[o + 1]; // hi
            sig[i++] = (t == -1) ? -1 : partition[t]; // target's current partition
            sig[i++] = opSeqId(ranges[o + 3]); // transition-ops content id
            sig[i++] = ranges[o + 4]; // requiredMask
        }
        if (wCount > 0) {
            int wbase = wholeBase[s];
            for (int r = 0; r < wCount; r++) {
                int o = (wbase + r) * 5;
                int t = wholeRanges[o + 2];
                sig[i++] = wholeRanges[o];
                sig[i++] = wholeRanges[o + 1];
                sig[i++] = (t == -1) ? -1 : partition[t];
                sig[i++] = opSeqId(wholeRanges[o + 3]);
                sig[i++] = wholeRanges[o + 4];
            }
        }
        return new SigKey(sig);
    }

    /**
     * Number of whole-side entries of state s (0 when no side table or
     * none for the state).
     */
    private int wholeRows(int s) {
        if (wholeRanges == null || wholeBase == null || wholeBase[s] < 0) {
            return 0;
        }
        return wholeCount[s];
    }
}
