package io.github.jemmix.tdfa.core.dfa;

import io.github.jemmix.tdfa.core.budget.BudgetWeights;

import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * The honest search-DFA memo (runner-immutability W2): a lazily
 * determinized unanchored-search automaton ({@code .*?} + pattern) whose
 * entries are frozen objects published behind lock-free primitives —
 * nothing is mutated after publication, ever.
 *
 * <p><b>Shape.</b> A {@link Row} is the live-set bitset of one
 * simulation configuration class, interned by content in a
 * {@link ConcurrentHashMap} (the input only chooses which rows get
 * visited). Each row carries 128 lazily filled cells, one per
 * 512-codepoint block; a published cell is an immutable {@link Block}
 * holding the 512 target rows directly ({@code targets[k] == null} is
 * the kill sentinel: every configuration died, the next row is the pure
 * seed, and the caller may advance its match-window bound past this
 * position). No ids exist — blocks reference rows, rows are found by
 * content — so there is no id-stability protocol to reason about and no
 * snapshot-staleness path to re-check.
 *
 * <p><b>Purity.</b> Every entry is a pure function of (runner tables,
 * key): a row's words are a deterministic function of the row content
 * that produced them; a block's targets of (row content, block index).
 * Racing duplicate computation publishes equal content ({@code
 * putIfAbsent} keeps one; the loser's charge is refunded), so
 * {@code warm == cold == concurrent}, byte-for-byte, forever.
 *
 * <p><b>Budget — charge-bound, deliberately without eviction.</b> The
 * same eighths partition and the same weight constants as before (rows
 * {@code budget/2} at {@code RUNTIME_ROW_FIXED + nw*RUNTIME_ROW_STATE}
 * weighted bytes, blocks {@code 3*budget/8} at {@code RUNTIME_BLOCK_BYTES}),
 * now charged atomically charge-before-publish instead of counted
 * against append-only arrays with a sticky global cap. Over budget, the
 * row is not interned / the block is not built, the CELL pins a cap
 * marker, and {@link #step} answers {@link #CAP} — the scan continues
 * via the kill-point-aware raw simulation carrying the exact live set
 * (today's {@code v == -1} semantics, minus the permanent {@code capped}
 * flag: degradation is per-structure, and already-published warmth keeps
 * serving). Eviction was considered and deferred (the doc's alternatives
 * section): recency tracking needs mutable bits beside the frozen
 * entries — a side table that costs more than it reclaims at these
 * sizes — and eviction is only observable as warmth under budget churn,
 * a regime no soak has evidenced. The tripwires own that claim.
 *
 * <p><b>Soundness</b> — the same over-approximation the raw scan uses
 * ({@link TdfaRunner#multiStateLeftmostStart}): transition/entry masks
 * are ignored (every matching target followed), and {@code accept} is
 * any live state with the accept bit — so a trigger can fire without a
 * real match (the exact extract confirms or continues), but it can never
 * miss one.
 */
final class SearchDfa {
    /**
     * Sampled-recompute audit ({@code -Dtdfa.memo.audit=1}, frozen at
     * class init like {@code tdfa.trace}): on the memoized fast path,
     * 1-in-64 steps recompute the transition from the immutable row
     * words and compare against the published block — a corrupt warm row
     * fails instantly with the codepoint identified. Pure comparison of
     * immutable data (never interns, never locks); off by default.
     */
    static final boolean AUDIT = Boolean.getBoolean("tdfa.memo.audit");

    final TdfaRunner r;

    final int nw;

    /**
     * Budget-out sentinel from {@link #step}: the memo could not serve
     * this step (row/block over its weighted share); the caller continues
     * via the raw simulation with this row's exact live set. Distinct
     * from {@code null} (kill). Identity-compared only; its words are
     * never read.
     */
    static final Row CAP = new Row(new int[0]);

    /** Cell marker: this (row, block) permanently computes directly. */
    private static final Object CAP_CELL = new Object();

    /** Shared frozen all-kill block (replaces the old {@code -3} cell). */
    private static final Block ALL_KILL = new Block(new Row[512]);

    private final ConcurrentHashMap<RowKey, Row> rows = new ConcurrentHashMap<>();

    private final AtomicLong rowCharge = new AtomicLong();
    private final AtomicLong blockCharge = new AtomicLong();
    private final long rowLimit;
    private final long blockLimit;
    private final int rowWeight;

    SearchDfa(TdfaRunner r, long memoBudgetBytes) {
        this.r = r;
        this.nw = r.stateWords;
        this.rowWeight = BudgetWeights.RUNTIME_ROW_FIXED_BYTES + nw * BudgetWeights.RUNTIME_ROW_STATE_BYTES;
        this.rowLimit = Math.max((long) BudgetWeights.RUNTIME_MIN_ROWS * rowWeight, memoBudgetBytes / 2);
        this.blockLimit = Math.max((long) BudgetWeights.RUNTIME_MIN_BLOCKS * BudgetWeights.RUNTIME_BLOCK_BYTES,
            memoBudgetBytes * 3 / 8);
    }

    /**
     * The pure-seed row (start state only), interned like any other.
     * Race-safe: equal content interns to one row; racing duplicates
     * refund their charge.
     */
    Row seed() {
        int[] w = new int[nw];
        w[r.startState >>> 5] |= 1 << (r.startState & 31);
        return internRow(w);
    }

    /**
     * One memoized step: the row after consuming codepoint {@code c}.
     * Returns the target row, {@code null} (kill — the next row is the
     * pure seed; the caller may advance its window bound past this
     * position), or {@link #CAP} (budget — continue via the raw scan
     * with the exact live set). Lock-free on the memoized fast path:
     * one volatile cell load plus one array load, no monitor, no id
     * indirection.
     */
    @SuppressWarnings("ReferenceEquality")
    Row step(Row row, int c) {
        if (c >= 0x10000) {
            return target(row.words, c); // supplementary: computed per step, never cached (rare)
        }
        int b = c >>> 9;
        Object cell = row.cells.get(b);
        if (cell == null) {
            cell = buildBlock(row, b);
        }
        if (cell == CAP_CELL) {
            return CAP;
        }
        Row t = ((Block) cell).targets[c & 511];
        if (AUDIT && ((System.identityHashCode(row) + c) & 63) == 0) {
            auditStep(row, c, t);
        }
        return t;
    }

    // ---- the pure automaton ----

    /**
     * Pure step (no re-seed): all targets of live states on c, masks ignored.
     */
    private int[] delta(int[] words, int c) {
        int[] next = new int[nw];
        for (int w = 0; w < nw; w++) {
            int bits = words[w];
            while (bits != 0) {
                int bit = Integer.numberOfTrailingZeros(bits);
                bits &= bits - 1;
                int s = (w << 5) + bit;
                int meta = r.stateMeta[s];
                int base = r.stateBase[s];
                int count = (meta >>> 1) & 0xFFFF;
                int rlo = 0, rhi = count - 1, anchor = -1;
                while (rlo <= rhi) {
                    int mid = (rlo + rhi) >>> 1;
                    if (r.ranges[(base + mid) * 5] <= c) {
                        anchor = mid;
                        rlo = mid + 1;
                    } else {
                        rhi = mid - 1;
                    }
                }
                for (int i = anchor; i >= 0 && r.rhp[base + i] >= c; i--) {
                    int mo = (base + i) * 5;
                    if (c <= r.ranges[mo + 1]) {
                        int t = r.ranges[mo + 2];
                        if (t >= 0) {
                            next[t >>> 5] |= 1 << (t & 31);
                        }
                    }
                }
            }
        }
        return next;
    }

    /**
     * The target of (words, c): kill ({@code null}) or the interned row
     * for delta re-seeded with the start state. Budget-out interns
     * nothing and answers {@link #CAP}.
     */
    private Row target(int[] words, int c) {
        int[] d = delta(words, c);
        boolean empty = true;
        for (int i = 0; i < nw; i++) {
            if (d[i] != 0) {
                empty = false;
                break;
            }
        }
        if (empty) {
            return null; // kill: next row is the pure seed
        }
        d[r.startState >>> 5] |= 1 << (r.startState & 31);
        return internRow(d);
    }

    // ---- interning and charging ----

    /**
     * Intern the row with these words by content. Equal content answers
     * an equal row forever (answers never change). Charge-before-publish
     * against the row share; racing losers refund.
     */
    private Row internRow(int[] words) {
        RowKey key = new RowKey(words);
        Row existing = rows.get(key);
        if (existing != null) {
            return existing;
        }
        if (rowCharge.get() + rowWeight > rowLimit) {
            return CAP;
        }
        rowCharge.addAndGet(rowWeight);
        Row fresh = new Row(words.clone());
        Row winner = rows.putIfAbsent(key, fresh);
        if (winner != null) {
            rowCharge.addAndGet(-rowWeight); // lost the race: refund, adopt the winner
            return winner;
        }
        return fresh;
    }

    /**
     * Materialize block {@code b} of the row: 512 frozen target refs
     * (or the shared all-kill block when every target died), published
     * by CAS — a losing racer's equal block is garbage and its charge
     * is refunded. Mid-block row-budget-out abandons the block (charge
     * refunded) and pins the cell's cap marker: the block never existed.
     */
    private Object buildBlock(Row row, int b) {
        if (blockCharge.get() + BudgetWeights.RUNTIME_BLOCK_BYTES > blockLimit) {
            row.cells.set(b, CAP_CELL);
            return CAP_CELL;
        }
        blockCharge.addAndGet(BudgetWeights.RUNTIME_BLOCK_BYTES);
        Row[] targets = new Row[512];
        boolean allKill = true;
        int lo = b << 9;
        for (int k = 0; k < 512; k++) {
            Row t = target(row.words, lo + k);
            if (t == CAP) {
                blockCharge.addAndGet(-BudgetWeights.RUNTIME_BLOCK_BYTES);
                row.cells.set(b, CAP_CELL);
                return CAP_CELL;
            }
            if (t != null) {
                allKill = false;
                targets[k] = t;
            }
        }
        Object pub = allKill ? ALL_KILL : new Block(targets);
        if (row.cells.compareAndSet(b, null, pub)) {
            return pub;
        }
        blockCharge.addAndGet(-BudgetWeights.RUNTIME_BLOCK_BYTES); // someone published an equal block
        return row.cells.get(b);
    }

    // ---- audit ----

    /**
     * Audit hook ({@link #AUDIT}): recompute the transition for (row, c)
     * — without interning — and compare content against the published
     * target. Lock-free and mutation-free; both sides are immutable
     * after publication, so any mismatch is real corruption of a warm row.
     */
    private void auditStep(Row row, int c, Row published) {
        int[] d = delta(row.words, c);
        boolean empty = true;
        for (int i = 0; i < nw; i++) {
            if (d[i] != 0) {
                empty = false;
                break;
            }
        }
        if (!empty) {
            d[r.startState >>> 5] |= 1 << (r.startState & 31);
        }
        boolean ok = empty ? published == null : published != null && Arrays.equals(d, published.words);
        if (!ok) {
            throw new IllegalStateException("MEMO_CORRUPT: row words " + Arrays.toString(row.words) + " cp " + c
                + " published " + (published == null ? "KILL" : "row words " + Arrays.toString(published.words))
                + " differ from recomputation");
        }
    }

    // ---- frozen entry types ----

    /** Immutable content key: the row's live-set words. */
    private static final class RowKey {
        final int[] words;
        final int hash;

        RowKey(int[] words) {
            this.words = words;
            this.hash = Arrays.hashCode(words);
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof RowKey && Arrays.equals(words, ((RowKey) o).words);
        }
    }

    /**
     * A frozen simulation row: the live-set bitset and 128 lazily
     * published immutable block cells (null = unbuilt, {@link #CAP_CELL}
     * = permanent cap, {@link Block} = served). Every field final; safe
     * publication by construction (final-field freeze plus the volatile
     * cell write).
     */
    static final class Row {
        final int[] words;
        final AtomicReferenceArray<Object> cells = new AtomicReferenceArray<>(128);

        Row(int[] words) {
            this.words = words;
        }
    }

    /**
     * A frozen 512-codepoint dispatch block: {@code targets[k]} is the
     * next row, or {@code null} = kill. Every field final.
     */
    static final class Block {
        final Row[] targets;

        Block(Row[] targets) {
            this.targets = targets;
        }
    }
}
