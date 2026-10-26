package io.github.jemmix.tdfa.core.dfa;

import io.github.jemmix.tdfa.core.budget.BudgetWeights;
import io.github.jemmix.tdfa.core.budget.Budgets;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * The honest walk-block memo (runner-immutability W3): lazily
 * materialized 512-codepoint dispatch blocks for codepoints past the
 * flat Latin-1 table, published as FROZEN arrays behind lock-free cell
 * reads — nothing is mutated after publication, no monitor, no ids.
 *
 * <p><b>Shape.</b> Per visited state, one lazily allocated 128-cell
 * table (a cell per 512-codepoint block); a published cell IS the
 * immutable {@code int[512]} block (cell = the containing range's
 * index, or {@code -1} = dead), or the shared cap marker when the
 * block could not charge its share. The old shape — per-state id
 * tables into a shared growable volatile-published block array, with
 * in-place cell writes under a monitor and a stale-id fallback — is
 * gone entirely. Reads are one volatile cell load plus one array load.
 *
 * <p><b>Purity.</b> A block is a pure function of (runner tables,
 * state, block index); racing duplicate builds publish equal content
 * (the CAS loser's equal block is garbage and its charge refunded), so
 * {@code warm == cold == concurrent}, byte-for-byte, forever.
 *
 * <p><b>Budget.</b> The same allowance as before ({@link
 * Budgets#walkMaxBytes}: one eighth of the runner's memo budget,
 * floored), charged atomically per published table and block,
 * charge-before-publish. Over budget the cell — or the whole state, if
 * even the table cannot charge — pins the cap marker and every lookup
 * takes the binary-search fallback: correct, slower, identical answers.
 * Deliberately no eviction (same decision as {@link SearchDfa}).
 */
final class WalkIndex {
    /** Per-(state, block) cap marker: this cell permanently binary-searches. */
    private static final Object CAP_CELL = new Object();

    /** Per-state cap marker: this state permanently binary-searches (dummy instance, identity-compared). */
    private static final AtomicReferenceArray<Object> TABLE_CAP = new AtomicReferenceArray<>(1);

    private final TdfaRunner r;

    /**
     * Lazily allocated per-state 128-cell tables ({@code null} =
     * allocate on first visit, {@link #TABLE_CAP} = capped). Final
     * container; cells publish fully built immutable blocks.
     */
    private final AtomicReferenceArray<AtomicReferenceArray<Object>> tables;

    private final AtomicLong charge = new AtomicLong();

    private final long maxBytes;

    WalkIndex(TdfaRunner r, long memoBudgetBytes) {
        this.r = r;
        this.tables = r.rangesDisjoint ? new AtomicReferenceArray<>(r.stateCount) : null;
        this.maxBytes = Budgets.walkMaxBytes(memoBudgetBytes);
    }

    /**
     * Range index for codepoint {@code c} (BMP, disjoint DFA) via the
     * lazy walk blocks: the (unique, disjoint-only) containing entry's
     * index, {@code -1} = dead entry, {@code -2} = cap exceeded (caller
     * falls back to binary search). Turns wide-class walks
     * ({@code \p{L}{2,}} on Cyrillic: ~600-range binary searches per
     * char) into one array load.
     */
    @SuppressWarnings("ReferenceEquality")
    int walkRangeIndex(int state, int c) {
        AtomicReferenceArray<Object> t = tables.get(state);
        if (t == null) {
            t = buildTable(state);
        }
        if (t == TABLE_CAP) {
            return -2;
        }
        int b = c >>> 9;
        Object cell = t.get(b);
        if (cell == null) {
            cell = buildBlock(state, b, t);
        }
        if (cell == CAP_CELL) {
            return -2;
        }
        return ((int[]) cell)[c & 511]; // -1 cell = dead entry
    }

    /**
     * The state's 128-cell table, charged like every other memo
     * structure; over the allowance the state permanently falls back to
     * binary search (cell pinned to the shared marker).
     */
    private AtomicReferenceArray<Object> buildTable(int state) {
        if (!tryCharge(BudgetWeights.WALK_STATE_TABLE_BYTES)) {
            tables.compareAndSet(state, null, TABLE_CAP);
            return TABLE_CAP;
        }
        AtomicReferenceArray<Object> t = new AtomicReferenceArray<>(128);
        if (tables.compareAndSet(state, null, t)) {
            return t;
        }
        charge.addAndGet(-BudgetWeights.WALK_STATE_TABLE_BYTES); // lost the race: refund, adopt the winner
        return tables.get(state);
    }

    /**
     * One frozen 512-codepoint block for the state: {@code cells[cp - lo]}
     * = the lowest containing entry's index (disjoint DFA: unique), or
     * {@code -1} = dead. Fully built, then CAS-published; a losing
     * racer's equal block is garbage and its charge is refunded.
     */
    private Object buildBlock(int state, int b, AtomicReferenceArray<Object> t) {
        if (!tryCharge(BudgetWeights.WALK_BLOCK_BYTES)) {
            t.set(b, CAP_CELL);
            return CAP_CELL;
        }
        int[] cells = new int[512];
        Arrays.fill(cells, -1);
        int lo = b << 9, hi = lo + 511;
        int base = r.stateBase[state], cnt = (r.stateMeta[state] >>> 1) & 0xFFFF;
        final int[] rg = r.ranges;
        for (int i = 0; i < cnt; i++) {
            int o = (base + i) * 5;
            int eLo = Math.max(rg[o], lo), eHi = Math.min(rg[o + 1], hi);
            for (int cp = eLo; cp <= eHi; cp++) {
                cells[cp - lo] = i;
            }
        }
        if (t.compareAndSet(b, null, cells)) {
            return cells;
        }
        charge.addAndGet(-BudgetWeights.WALK_BLOCK_BYTES); // someone published an equal block
        return t.get(b);
    }

    /** Charge {@code bytes} against the walk allowance; false when over (lock-free). */
    private boolean tryCharge(long bytes) {
        long now;
        do {
            now = charge.get();
            if (now + bytes > maxBytes) {
                return false;
            }
        } while (!charge.compareAndSet(now, now + bytes));
        return true;
    }
}
