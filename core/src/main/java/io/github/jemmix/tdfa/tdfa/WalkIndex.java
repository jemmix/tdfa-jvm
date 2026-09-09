package io.github.jemmix.tdfa.tdfa;

import java.util.concurrent.atomic.AtomicReferenceArray;

/** Lazy per-state walk-block memo (codepoints past the flat Latin-1 table):
 *  extracted verbatim from TdfaRunner (2026-09 god-file split). The monitor
 *  is this index instance (it was the runner's; the runner has no other
 *  synchronized methods, so the lock domain is unchanged in effect). */
final class WalkIndex {
    private final TdfaRunner r;

    /** Lazy per-state 512-codepoint walk blocks for codepoints >= latinLimit:
     *  cell = the (unique, disjoint-only) containing range's index, or -1.
     *  turns wide-class walks (\p{L}{2,} on Cyrillic: ~600-range binary
     *  searches per char) into one array load. Published via a volatile
     *  snapshot reference (copy-on-grow; build is synchronized
     *  and double-checked, so races only cost a redundant lock). */
    private volatile int[][] walkBlocksArr = EMPTY_BLOCKS;
    private int walkBlockCount;                       // guarded by this
    /** Lazy per-state block-id tables, one volatile cell per state (see
     *  {@link #walkRangeIndex}): the cell write publishes the fully-built,
     *  -1-filled id array, so a racing reader sees either null (builds its
     *  own, benignly duplicated) or a fully-initialized array — never a
     *  default-0 cell misread as block id 0. */
    private final java.util.concurrent.atomic.AtomicReferenceArray<int[]> walkBlockIdx;
    private static final int[][] EMPTY_BLOCKS = {};
    /** Cap on walk blocks (512 ints each): past it, dispatch falls back to
     *  binary search (dictionary-scale DFAs must not grow unbounded memos). */
    private static final int WALK_MAX_BLOCKS = 64;

    WalkIndex(TdfaRunner r) {
        this.r = r;
        this.walkBlockIdx = r.rangesDisjoint
                ? new AtomicReferenceArray<>(r.stateCount) : null;
    }

    /**
     * Range index for codepoint {@code c} (BMP, disjoint DFA) via lazy walk
     * blocks: -1 = dead entry, -2 = block cap exceeded (caller falls back to
     * binary search). See {@link #walkBlocksArr} for the publication scheme.
     */
    int walkRangeIndex(int state, int c) {
        int[] idx = walkBlockIdx.get(state);
        if (idx == null) {
            idx = new int[128];
            java.util.Arrays.fill(idx, -1);
            walkBlockIdx.set(state, idx);      // volatile publish of the filled array
            idx = walkBlockIdx.get(state);     // adopt the winner if we lost the race
        }
        int b = c >>> 9;
        int id = idx[b];
        if (id == -1) id = buildWalkBlock(state, b);
        if (id < 0) return id;
        int[][] arr = walkBlocksArr;
        if (id < arr.length) {
            int ri = arr[id][c & 511];
            return ri;   // -1 cell = dead entry
        }
        return -2;       // stale id vs a fresh snapshot: treat as capped (rare, safe)
    }

    /** Build one 512-cp block for `state` (lowest entry index per cell — for
     *  disjoint DFAs the containing entry is unique). Synchronized + double-checked
     *  against the PUBLISHED id table (two threads racing a first-visit of the
     *  same state may carry private idx copies; the block id itself must be
     *  canonical). Cell values: -1 unbuilt, -2 capped, >=0 block id; cells only
     *  ever transition from -1 under the lock, so a racing plain read observes
     *  either -1 (re-checks here) or the final id — ints are atomically written. */
    private synchronized int buildWalkBlock(int state, int b) {
        int[] pub = walkBlockIdx.get(state);
        int e = pub != null ? pub[b] : -1;
        if (e != -1) return e;
        if (walkBlockCount >= WALK_MAX_BLOCKS) {
            if (pub != null) pub[b] = -2;
            return -2;
        }
        int[] cells = new int[512];
        java.util.Arrays.fill(cells, -1);
        int lo = b << 9, hi = lo + 511;
        int base = r.stateBase[state], cnt = (r.stateMeta[state] >>> 1) & 0xFFFF;
        final int[] rg = r.ranges;
        for (int i = 0; i < cnt; i++) {
            int o = (base + i) * 5;
            int eLo = Math.max(rg[o], lo), eHi = Math.min(rg[o + 1], hi);
            for (int cp = eLo; cp <= eHi; cp++) cells[cp - lo] = i;
        }
        int n = walkBlockCount++;
        int[][] next = java.util.Arrays.copyOf(walkBlocksArr, n + 1);
        next[n] = cells;
        walkBlocksArr = next;    // volatile publish: cells contents visible to readers
        if (pub != null) pub[b] = n;
        return n;
    }
}
