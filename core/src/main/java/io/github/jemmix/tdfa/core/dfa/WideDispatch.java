package io.github.jemmix.tdfa.core.dfa;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The wide-class dispatch index: an eager, immutable two-level map from
 * {@code (state, codepoint) → range-entry index} that gives wide Unicode
 * classes ({@code \p{L}} ≈ 680 ranges, {@code (?u)\w} ≈ 800) the O(1)
 * dispatch the Latin-1 tables already give ASCII — instead of the ~11-step
 * binary search every walk, simulation, and raw scan pays per consumed
 * codepoint today (the measured root of the README's unicode wide-class
 * scan gap: jur's per-char bitmap versus our per-char range search).
 *
 * <p><b>Shape.</b> First level: one {@code BLOCKS}-entry row per indexed
 * state, addressed {@code rows[state] → l1[(row · BLOCKS) + (cp >>> 10)]}
 * — 1024-codepoint blocks over the whole codepoint space (BMP and
 * supplementary alike; 1088 blocks cover {@code [0, 0x10FFFF]}). An L1
 * cell is either <b>uniform</b> (every codepoint in the block resolves to
 * the same entry — the overwhelming majority: whole scripts sit inside one
 * range) or a pointer to a <b>boundary block</b>: a tiny sorted slice list
 * {@code [n, start₀, ri₀, …]} locating the ≤ handful of boundaries inside
 * that block. Uniform cells cost one load; boundary cells one small probe
 * (in practice 1–2 compares). Blocks are content-deduplicated across
 * states — the near-identical class rows of a wide-class DFA ({@code
 * \p{L}{2,}} has three states with the same 684-range partition) collapse
 * to one shared set.
 *
 * <p><b>Exactness gate.</b> Built only when the artifact's ranges are
 * pairwise disjoint (the same property the flat {@code asciiRangeFlat}
 * tables rely on): then at most one entry contains any codepoint and the
 * index resolves exactly the entry the binary search would find —
 * required masks, dead markers, and ops stay in the range entries and are
 * read by the caller exactly as before. Overlapping (mask-priority)
 * artifacts keep the binary search.
 *
 * <p><b>Not a memo.</b> Nothing here is lazy, mutable, shared-mutable,
 * capped-at-runtime, or invalidated: it is a pure function of the artifact
 * computed once at runner construction, frozen for the runner's lifetime
 * (the immutability invariant the memo deletions reached — see {@code
 * docs/search-dfa-memo-tradeoffs.md} for what was deleted and why). Per
 * the r11 scope decision it is an engine-tier construction-time table
 * charged against disclosed size caps below, not the runtime budget.
 *
 * <p><b>Caps</b> (all-or-nothing: an artifact whose index would exceed
 * them simply keeps the binary search):
 * <ul>
 * <li>{@value #MIN_RANGES} entries before a state is worth indexing —
 *     below that the binary search is ≤ 3 compares and the index buys
 *     nothing;</li>
 * <li>{@value #MAX_STATES} indexed states and a {@value #MAX_ROW_STATES}
 *     state ceiling for the row map — wide classes live on small DFAs
 *     (measured: 2–5 states), and giant DFAs (dictionary: 6.8 K narrow
 *     states) index nothing anyway;</li>
 * <li>{@value #MAX_BYTES} bytes for the whole structure — the widest
 *     realistic artifact ({@code [\p{L}\p{N}]{3,}:}, 5 wide states ≈ 800
 *     ranges each) lands near 40 KB; twice that already covers anything
 *     sensible the benchmark corpus contains.</li>
 * </ul>
 */
final class WideDispatch {

    /** L1 block size: 1024 codepoints (a supplementary block per high
     *  surrogate — the whole space is 1088 blocks). */
    static final int BLOCK_SHIFT = 10;
    /** Blocks over the codepoint space: {@code 0x110000 >>> 10}. */
    static final int BLOCKS = 0x110000 >>> BLOCK_SHIFT;
    /** Entries before a state's binary search is worth indexing. */
    static final int MIN_RANGES = 8;
    /** Indexed-state ceiling (L1 rows). */
    static final int MAX_STATES = 64;
    /** State-count ceiling for the row map (4 bytes/state). */
    static final int MAX_ROW_STATES = 65_536;
    /** Whole-structure byte ceiling (rows + L1 + block pool). */
    static final int MAX_BYTES = 512 * 1024;

    /** {@link #rangeIndex} sentinel: state not indexed — the caller keeps
     *  its binary search. Distinct from the in-range {@code -1 = dead}. */
    static final int NONE = Integer.MIN_VALUE;

    /** {@code state → L1 row} ({@code -1} = not indexed). */
    final int[] rows;
    /** L1 cells, {@code rowCount} rows of {@link #BLOCKS}: {@code v ≥ 0}
     *  uniform with {@code ri = v - 1} (0 = dead block); {@code v < 0}
     *  boundary block at pool offset {@code -v - 1}. */
    final int[] l1;
    /** Boundary-block pool: concatenated {@code [n, start₀, ri₀, …]}
     *  slice lists, starts ascending, absolute codepoints. */
    final int[] blocks;

    private WideDispatch(int[] rows, int[] l1, int[] blocks) {
        this.rows = rows;
        this.l1 = l1;
        this.blocks = blocks;
    }

    /**
     * Range-entry index for {@code cp} in {@code state}; {@code -1} = no
     * entry contains it (dead); {@link #NONE} = state not indexed.
     */
    int rangeIndex(int state, int cp) {
        int row = rows[state];
        if (row < 0) {
            return NONE;
        }
        int v = l1[row * BLOCKS + (cp >>> BLOCK_SHIFT)];
        if (v >= 0) {
            return v - 1;
        }
        int off = -v - 1;
        // Boundary block: rightmost slice whose start ≤ cp (starts ascend).
        int n = blocks[off];
        int lo = 0, hi = n - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (blocks[off + 1 + (mid << 1)] <= cp) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return blocks[off + 2 + (lo << 1)];
    }

    /**
     * Build the index for an artifact, or {@code null} when it does not
     * apply (non-disjoint ranges, no wide-enough states, over the caps).
     */
    static WideDispatch build(Tdfa tdfa, boolean rangesDisjoint) {
        if (!rangesDisjoint || tdfa.stateCount > MAX_ROW_STATES) {
            return null;
        }
        final int[] sm = tdfa.stateMeta;
        final int[] sb = tdfa.stateBase;
        final int[] rg = tdfa.ranges;
        // Wide states, in state order (row order is irrelevant to lookup).
        int wideStates = 0;
        long estPoolInts = 0;
        for (int s = 0; s < tdfa.stateCount; s++) {
            int cnt = (sm[s] >>> 1) & 0xFFFF;
            if (cnt >= MIN_RANGES) {
                wideStates++;
                // Bound: each entry can start at most one slice per block it
                // spans, plus one dead slice and one header per boundary
                // block; block count itself is the other bound.
                estPoolInts += 2 * ((long) cnt + BLOCKS) + 2;
            }
        }
        if (wideStates == 0 || wideStates > MAX_STATES
            || 4L * (tdfa.stateCount + (long) wideStates * BLOCKS + estPoolInts) > MAX_BYTES) {
            return null;
        }
        final int[] rows = new int[tdfa.stateCount];
        Arrays.fill(rows, -1);
        final int[] l1 = new int[wideStates * BLOCKS];
        // Boundary-block dedup: identical slice lists share one pool copy
        // (the same class partition appears in every state that consumes it).
        final Map<List<Integer>, Integer> blockAt = new HashMap<>();
        final List<Integer> pool = new ArrayList<>();
        int row = 0;
        for (int s = 0; s < tdfa.stateCount; s++) {
            int cnt = (sm[s] >>> 1) & 0xFFFF;
            if (cnt < MIN_RANGES) {
                continue;
            }
            rows[s] = row;
            // Entries sorted by lo (disjoint ⇒ los distinct, his ascending).
            // The stored order can differ (mask-specificity reordering), so
            // the sort here keys on lo and carries the ORIGINAL entry index.
            long[] sorted = new long[cnt];
            for (int i = 0; i < cnt; i++) {
                sorted[i] = ((long) rg[(sb[s] + i) * 5] << 32) | i;
            }
            Arrays.sort(sorted);
            int e = 0; // first entry whose hi ≥ block start (his ascend)
            for (int b = 0; b < BLOCKS; b++) {
                int bs = b << BLOCK_SHIFT;
                int be = Math.min(bs + (1 << BLOCK_SHIFT) - 1, 0x10FFFF);
                while (e < cnt && rg[(sb[s] + (int) sorted[e]) * 5 + 1] < bs) {
                    e++;
                }
                int lo = bs;
                List<Integer> slices = null;
                int uniformRi = -1;
                int p = e;
                while (p < cnt && rg[(sb[s] + (int) sorted[p]) * 5] <= be) {
                    int ri = (int) sorted[p];
                    int entryLo = Math.max(rg[(sb[s] + ri) * 5], bs);
                    int entryHi = Math.min(rg[(sb[s] + ri) * 5 + 1], be);
                    if (slices == null && entryLo == bs && entryHi == be) {
                        // Candidate uniform: this entry spans the whole
                        // block. Under disjointness no other entry can also
                        // overlap it — but a LATER partial check is cheap:
                        // spanning the block means the next sorted entry's
                        // lo > be, so the loop exits and uniform holds.
                        uniformRi = ri;
                        p++;
                        continue;
                    }
                    if (lo < entryLo) {
                        // Dead head of the block, or the gap between this
                        // entry and the previous one — a slice of its own.
                        if (slices == null) {
                            slices = new ArrayList<>(4);
                        }
                        slices.add(lo);
                        slices.add(-1);
                    } else if (lo > entryLo) {
                        throw new IllegalStateException("wide dispatch: overlapping entries on a disjoint artifact");
                    }
                    if (slices == null) {
                        slices = new ArrayList<>(4);
                    }
                    slices.add(entryLo);
                    slices.add(ri);
                    lo = entryHi + 1;
                    p++;
                    // An entry ending before be with no successor overlapping
                    // be gets its dead tail below.
                }
                if (slices == null) {
                    // Uniform (possibly dead: uniformRi stayed -1).
                    l1[row * BLOCKS + b] = uniformRi + 1;
                } else {
                    if (lo <= be) {
                        slices.add(lo);
                        slices.add(-1);
                    }
                    Integer at = blockAt.get(slices);
                    if (at == null) {
                        at = pool.size();
                        pool.add(slices.size() / 2);
                        pool.addAll(slices);
                        blockAt.put(slices, at);
                    }
                    l1[row * BLOCKS + b] = -at - 1;
                }
            }
            row++;
        }
        final int[] blockPool = new int[pool.size()];
        for (int i = 0; i < blockPool.length; i++) {
            blockPool[i] = pool.get(i);
        }
        if (4L * (rows.length + l1.length + blockPool.length) > MAX_BYTES) {
            return null; // estimate undercounted a pathological overlay
        }
        return new WideDispatch(rows, l1, blockPool);
    }
}
