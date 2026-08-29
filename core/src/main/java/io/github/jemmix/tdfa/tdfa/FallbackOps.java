package io.github.jemmix.tdfa.tdfa;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * BT22 §6.2 fallback operations: adds backup + restore ops to support POSIX
 * longest-match semantics on TDFAs with capturing groups.
 *
 * <p><b>The problem.</b> When a TDFA reaches a final state S, accepts, then
 * continues stepping in hopes of finding a longer match, the transitions it
 * takes out of S may <emph>clobber</emph> registers that held the value S
 * needed for its final-regops {@code φ(S)}. If the longer-match attempt fails
 * and the runner falls back to S, applying {@code φ(S)} to the (now-clobbered)
 * register file produces wrong capture values.
 *
 * <p><b>The fix (paper Algorithm {@code fallback_regops}, Figure 6).</b>
 * <ol>
 *   <li>A final state S is a <emph>fallback state</emph> iff there's a
 *       non-accepting path out of it — equivalently, a transition to a
 *       non-final state S' from which the synthetic "default" (dead-end / EOF)
 *       is reachable without crossing another final state.</li>
 *   <li>For each fallback state S, DFS through non-final successors to find
 *       the <emph>clobbered set</emph>: every register written by some op on
 *       a non-accepting path from S.</li>
 *   <li>Walk {@code φ(S)}; for every COPY {@code final[i] ← working[j]} whose
 *       {@code working[j]} is clobbered, allocate a dedicated backup slot R
 *       (a new register above the regopt range, immune to coalescing) and:
 *       <ul>
 *         <li>Add backup COPY {@code R ← working[j]} to each transition out
 *             of S to a non-final state (so R holds the pre-clobber value).</li>
 *         <li>In {@code ψ(S)}, replace {@code final[i] ← working[j]} with
 *             {@code final[i] ← R} (restore from the preserved value).</li>
 *       </ul>
 *   <li>Other ops propagate to {@code ψ(S)} unchanged.</li>
 * </ol>
 *
 * <p><b>Dedicated backup slots.</b> The paper reuses {@code final[i]} itself
 * as the backup slot, but this only works when final and working registers
 * are distinct — which they aren't after BT22 §6.3 register optimizations
 * (coalescing collapses {@code final[i] ← working[j]} into the same slot,
 * making the backup a no-op). We instead allocate fresh registers above the
 * regopt range, so each clobbered COPY gets a slot that no subsequent op
 * (regopt or runtime) can touch.
 *
 * <p><b>Runtime.</b> When accepting at fallback state S, if the runner took
 * any transition since the last accept (i.e. {@code pos > lastAcceptPos}),
 * it applies {@code ψ(S)} instead of {@code φ(S)}: the backups have already
 * stashed the pre-clobber values into the dedicated slots, and {@code ψ(S)}
 * reads them back into the final slots. If the runner did not take any
 * transition (direct accept at S, no clobbering), it applies {@code φ(S)}
 * as usual.
 *
 * <p>Single-valued tags only — APPEND case in the paper is stubbed.
 */
final class FallbackOps {

    /** Result of {@link #add}. Immutable view over the rebuilt arrays. */
    static final class Result {
        final int[] flatOps;
        final int[] ranges;
        final boolean[] stateIsFallback;
        final int[] stateFallbackOpsOff;
        final int registerCount;
        final int fallbackStateCount;
        final int backupTransitionCount;
        final int backupSlotCount;

        Result(int[] flatOps, int[] ranges, boolean[] stateIsFallback, int[] stateFallbackOpsOff,
               int registerCount, int fallbackStateCount, int backupTransitionCount, int backupSlotCount) {
            this.flatOps = flatOps;
            this.ranges = ranges;
            this.stateIsFallback = stateIsFallback;
            this.stateFallbackOpsOff = stateFallbackOpsOff;
            this.registerCount = registerCount;
            this.fallbackStateCount = fallbackStateCount;
            this.backupTransitionCount = backupTransitionCount;
            this.backupSlotCount = backupSlotCount;
        }
    }

    /**
     * Run the §6.2 fallback analysis and op generation on a materialized DFA.
     * Returns updated {@code flatOps}/{@code ranges} (extended with ψ blocks
     * and backup-prefixed transition blocks) and new per-state fallback
     * annotations.
     *
     * @param registerCount current register count; new backup slots are
     *                      allocated above this index and the result's
     *                      {@link Result#registerCount} reflects the new total.
     */
    static Result add(int n, int[] stateMeta, int[] stateBase, int[] ranges, int[] flatOps,
                      int[] stateFinalOpsOff, int registerCount, WorkMeter meter) {
        // Phase 1: classify states.
        boolean[] isFinal = new boolean[n];
        for (int s = 0; s < n; s++) isFinal[s] = (stateMeta[s] & 1) != 0;
        boolean[] canReachDefault = computeCanReachDefault(n, stateMeta, stateBase, ranges, isFinal, meter);
        boolean[] stateIsFallback = new boolean[n];
        for (int s = 0; s < n; s++) stateIsFallback[s] = isFinal[s] && canReachDefault[s];

        // Phase 2: for each fallback state, generate ψ + per-transition backups.
        // Per fallback state: ψ ops list, and a list of (orig_dst, orig_src, backup_slot) triples
        // describing the clobbered COPYs and their assigned backup slots.
        int[] stateFallbackOpsOff = new int[n];
        int[][] psiPerState = new int[n][];
        // Per-(state, range) backup ops (long key = state << 32 | rangeIdx).
        Map<Long, int[]> backupPerTransition = new HashMap<>();
        int nextBackupSlot = registerCount;

        for (int s = 0; s < n; s++) {
            meter.tick();
            if (!stateIsFallback[s]) continue;
            BitSet clobbered = accumulateClobbered(s, n, stateMeta, stateBase, ranges, flatOps, isFinal);

            int foff = stateFinalOpsOff[s];
            if (foff == 0) continue;  // no φ ops to back up

            List<Integer> psiList = new ArrayList<>();
            // For each clobbered COPY in φ: assign a backup slot.
            // backupAssignments: triples of (orig_src, backup_slot, orig_dst).
            List<int[]> backupAssignments = new ArrayList<>();
            for (int j = foff; ; j += 3) {
                int op = flatOps[j];
                if (op == Tdfa.OP_END) break;
                int dst = flatOps[j + 1];
                int src = flatOps[j + 2];
                if (op == Tdfa.OP_COPY && clobbered != null && clobbered.get(src)) {
                    int backupSlot = nextBackupSlot++;
                    backupAssignments.add(new int[]{src, backupSlot, dst});
                    // ψ restores from backup slot into the original dst.
                    psiList.add(Tdfa.OP_COPY);
                    psiList.add(dst);
                    psiList.add(backupSlot);
                } else {
                    psiList.add(op);
                    psiList.add(dst);
                    psiList.add(src);
                }
            }

            // ALWAYS materialize ψ for a fallback state with φ ops. If no COPY
            // source is clobbered, ψ degenerates to φ — but it must exist:
            // pickFinalOpsOff (VM) and emitFinalOps (ASM) select ψ whenever the
            // runner advanced past the accept (pos > lastAcceptPos), and a
            // missing ψ block (offset 0) made them skip the final ops entirely,
            // silently reading every capture as NIL (e.g. (?:(a){2})* on "aaa").
            int[] psiArr = new int[psiList.size() + 1];
            for (int k = 0; k < psiList.size(); k++) psiArr[k] = psiList.get(k);
            psiArr[psiArr.length - 1] = Tdfa.OP_END;
            psiPerState[s] = psiArr;

            if (backupAssignments.isEmpty()) continue;  // ψ == φ; no backup transitions needed

            // Build the backup ops list: for each clobbered COPY, COPY backup_slot ← src.
            // (Multiple clobbered COPYs may share src; each gets its own backup slot.)
            int[] backupArr = new int[backupAssignments.size() * 3];
            for (int k = 0; k < backupAssignments.size(); k++) {
                int[] a = backupAssignments.get(k);
                backupArr[k * 3]     = Tdfa.OP_COPY;
                backupArr[k * 3 + 1] = a[1];  // backup_slot
                backupArr[k * 3 + 2] = a[0];  // original src
            }

            int sb = stateBase[s];
            int sc = Tdfa.rangeCount(stateMeta[s]);
            for (int i = 0; i < sc; i++) {
                int o = (sb + i) * 5;
                int t = ranges[o + 2];
                if (t < 0 || t >= n) continue;
                if (isFinal[t]) continue;
                long key = ((long) s << 32) | i;
                backupPerTransition.put(key, backupArr);
            }
        }

        int fallbackCnt = 0;
        for (int s = 0; s < n; s++) if (psiPerState[s] != null) fallbackCnt++;
        int newRegisterCount = nextBackupSlot;
        int backupSlotCount = nextBackupSlot - registerCount;

        if (fallbackCnt == 0 && backupPerTransition.isEmpty()) {
            return new Result(flatOps, ranges, stateIsFallback, stateFallbackOpsOff,
                    registerCount, 0, 0, 0);
        }

        // Phase 3: rebuild flatOps + ranges with new ψ blocks and backup-prefixed transitions.
        int additional = 0;
        for (int s = 0; s < n; s++) if (psiPerState[s] != null) additional += psiPerState[s].length;
        for (Map.Entry<Long, int[]> e : backupPerTransition.entrySet()) {
            long key = e.getKey();
            int s = (int) (key >>> 32);
            int i = (int) (key & 0xFFFFFFFFL);
            int o = (stateBase[s] + i) * 5;
            int origOff = ranges[o + 3];
            int origLen = blockLen(flatOps, origOff);
            additional += e.getValue().length + origLen + 1;  // backup + orig + OP_END
        }

        int newLen = flatOps.length + additional;
        int[] newFlatOps = Arrays.copyOf(flatOps, newLen);
        int[] newRanges = ranges.clone();
        int head = flatOps.length;

        // Write ψ blocks.
        for (int s = 0; s < n; s++) {
            if (psiPerState[s] == null) continue;
            stateFallbackOpsOff[s] = head;
            int[] arr = psiPerState[s];
            System.arraycopy(arr, 0, newFlatOps, head, arr.length);
            head += arr.length;
        }

        // Write backup-prefixed transition blocks.
        for (Map.Entry<Long, int[]> e : backupPerTransition.entrySet()) {
            long key = e.getKey();
            int s = (int) (key >>> 32);
            int i = (int) (key & 0xFFFFFFFFL);
            int o = (stateBase[s] + i) * 5;
            int origOff = ranges[o + 3];
            int origLen = blockLen(flatOps, origOff);
            int[] backupArr = e.getValue();

            int newOff = head;
            System.arraycopy(backupArr, 0, newFlatOps, head, backupArr.length);
            head += backupArr.length;
            if (origLen > 0) {
                System.arraycopy(flatOps, origOff, newFlatOps, head, origLen);
                head += origLen;
            }
            newFlatOps[head++] = Tdfa.OP_END;
            newRanges[o + 3] = newOff;
        }

        return new Result(newFlatOps, newRanges, stateIsFallback, stateFallbackOpsOff,
                newRegisterCount, fallbackCnt, backupPerTransition.size(), backupSlotCount);
    }

    /**
     * Compute {@code canReachDefault[s]} = true iff a path exists from s to the
     * synthetic "default" (dead-end / EOF) without crossing another final state.
     *
     * <p>Non-final states trivially satisfy this (EOF can happen at any state).
     * Final states propagate: {@code canReachDefault[s] = OR over (s → s')}
     * of {@code canReachDefault[s']}, iterated to fixpoint.
     */
    private static boolean[] computeCanReachDefault(int n, int[] stateMeta, int[] stateBase,
                                                    int[] ranges, boolean[] isFinal, WorkMeter meter) {
        boolean[] canReachDefault = new boolean[n];
        for (int s = 0; s < n; s++) canReachDefault[s] = !isFinal[s];
        boolean changed = true;
        while (changed) {
            meter.tick();
            changed = false;
            for (int s = 0; s < n; s++) {
                meter.tick();
                if (!isFinal[s] || canReachDefault[s]) continue;
                int base = stateBase[s];
                int cnt = Tdfa.rangeCount(stateMeta[s]);
                for (int i = 0; i < cnt; i++) {
                    int o = (base + i) * 5;
                    int t = ranges[o + 2];
                    if (t < 0) continue;
                    if (t < n && canReachDefault[t]) {
                        canReachDefault[s] = true;
                        changed = true;
                        break;
                    }
                }
            }
        }
        return canReachDefault;
    }

    /**
     * DFS from fallback state {@code start} through non-final successors,
     * accumulating the LHS of every transition op encountered. The result is
     * the set of registers that may be clobbered on a non-accepting path from
     * {@code start}, which {@code φ(start)} can then route through backups.
     *
     * <p>Returns null if no transitions out of {@code start} reach non-final
     * states (i.e., {@code start} is final but not actually a fallback).
     */
    private static BitSet accumulateClobbered(int start, int n, int[] stateMeta, int[] stateBase,
                                              int[] ranges, int[] flatOps, boolean[] isFinal) {
        BitSet clobbered = new BitSet();
        BitSet visited = new BitSet();
        Deque<Integer> stack = new ArrayDeque<>();
        visited.set(start);
        stack.push(start);
        boolean anyNonFinalSucc = false;
        while (!stack.isEmpty()) {
            int u = stack.pop();
            int ub = stateBase[u];
            int uc = Tdfa.rangeCount(stateMeta[u]);
            for (int i = 0; i < uc; i++) {
                int o = (ub + i) * 5;
                int t = ranges[o + 2];
                if (t < 0) continue;
                if (t < n && isFinal[t]) continue;  // accepting path: skip
                anyNonFinalSucc = true;
                int opsOff = ranges[o + 3];
                if (opsOff != 0) {
                    for (int j = opsOff; ; j += 3) {
                        int op = flatOps[j];
                        if (op == Tdfa.OP_END) break;
                        clobbered.set(flatOps[j + 1]);
                    }
                }
                if (t >= 0 && t < n && !visited.get(t)) {
                    visited.set(t);
                    stack.push(t);
                }
            }
        }
        return anyNonFinalSucc ? clobbered : null;
    }

    /** Length (in ints) of an op block at offset {@code off} in {@code flatOps}, excluding the OP_END terminator. */
    private static int blockLen(int[] flatOps, int off) {
        if (off == 0) return 0;  // sentinel "empty"
        int len = 0;
        while (flatOps[off + len] != Tdfa.OP_END) len += 3;
        return len;
    }

    private FallbackOps() {}
}
