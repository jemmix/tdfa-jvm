package io.github.jemmix.tdfa.tdfa;

import java.util.HashMap;

import static io.github.jemmix.tdfa.tdfa.TdfaRunner.SDFA_KILL;
import static io.github.jemmix.tdfa.tdfa.TdfaRunner.SDFA_MAX_BLOCKS;
import static io.github.jemmix.tdfa.tdfa.TdfaRunner.SDFA_MAX_ROWS;

/** Extracted verbatim from TdfaRunner (2026-09 god-file split); the
 *  {@code r} back-reference carries the shared runner tables. Docs moved
 *  with the code. Caps/sentinel stay on TdfaRunner (its scan paths use them
 *  too) and are static-imported here. */
    /** Static nested: shared per-Tdfa lifetime; references the runner's tables.
     *
     * Thread-safety (the RegexEngine contract requires concurrent-safe
     * engines): the mutation path — internRow / transition / buildBlock — is
     * confined under {@link #lock}. The per-codepoint READ path never touches
     * the intern maps: it goes through immutable, volatile-published
     * snapshots ({@link #rowWordsArr}, {@link #rowBlockIdsArr},
     * {@link #blocksArr}) whose entries are fully built before publication;
     * row-block cells only ever transition from -1 to their final value under
     * the lock (plain int writes are atomic, so a racing reader sees either
     * -1 — and re-checks under the lock — or the final value; a block id is
     * written to a cell only AFTER the block is published in
     * {@code blocksArr}, and readers length-check against the snapshot so a
     * stale snapshot degrades to the locked path, never to a wrong lookup).
     * Locking the read path itself would serialize concurrent scans of one
     * Pattern and put a monitor enter/exit on every scanned char — that is
     * why the snapshots exist. */
    final class SearchDfa {
        final TdfaRunner r;
        final int nw;
        final Object lock = new Object();
        SearchDfa(TdfaRunner r) { this.r = r; this.nw = r.stateWords; }

        // ---- writer-confined (all accesses under lock) ----
        private final HashMap<Wrapper, Integer> rowById = new HashMap<>();    // bitset -> row id
        private final HashMap<Wrapper, Integer> blockById = new HashMap<>();  // content -> block id

        // ---- immutable snapshots; volatile-published on growth (copy-on-write) ----
        /** row id -> live-set bitset; rows are interned (never mutated after publish). */
        private volatile int[][] rowWordsArr = {};
        /** row id -> int[128] block ids. Cell: -1 unbuilt, -2 capped/direct,
         *  -3 all-kill, >=0 block id in {@link #blocksArr}. Rows are
         *  copy-on-write (a new row replaces the old in a fresh snapshot on
         *  every cell write — see setRowCell). */
        private volatile int[][] rowBlockIdsArr = {};
        /** block id -> int[512] encoded transitions. */
        private volatile int[][] blocksArr = {};
        volatile boolean capped;

        /** Immutable-ish int[] key wrapper with cached hash. */
        private static final class Wrapper {
            final int[] a; final int hash;
            Wrapper(int[] a) { this.a = a; hash = java.util.Arrays.hashCode(a); }
            @Override public int hashCode() { return hash; }
            @Override public boolean equals(Object o) {
                return o instanceof Wrapper && java.util.Arrays.equals(a, ((Wrapper) o).a);
            }
        }

        /** Intern the pure-seed row as id 0. Idempotent and race-safe:
         *  the first caller past the lock publishes it, later callers see
         *  row 0 in the snapshot and return. */
        void ensureSeed() {
            if (rowWordsArr.length != 0) return;
            synchronized (lock) {
                if (rowWordsArr.length != 0) return;
                int[] seed = new int[nw];
                seed[r.startState >>> 5] |= 1 << (r.startState & 31);
                internRowLocked(seed);
            }
        }

        /** Must hold {@link #lock}. Interns {@code words}; -1 (and cap flag)
         *  when the row budget is exhausted. */
        private int internRowLocked(int[] words) {
            Wrapper probe = new Wrapper(words);
            Integer id = rowById.get(probe);
            if (id != null) return id;
            if (rowWordsArr.length >= SDFA_MAX_ROWS || capped) { capped = true; return -1; }
            int[] key = words.clone();
            int nid = rowWordsArr.length;
            rowById.put(new Wrapper(key), nid);
            int[][] rw = java.util.Arrays.copyOf(rowWordsArr, nid + 1);
            rw[nid] = key;
            rowWordsArr = rw;   // volatile publish
            int[][] rb = java.util.Arrays.copyOf(rowBlockIdsArr, nid + 1);
            int[] cells = new int[128];
            java.util.Arrays.fill(cells, -1);
            rb[nid] = cells;
            rowBlockIdsArr = rb;   // volatile publish (cells still all -1)
            return nid;
        }

        /** Live-set bitset of an interned row. Safe for lock-free readers:
         *  row arrays are immutable after publication. */
        int[] rowWordsOf(int rowId) { return rowWordsArr[rowId]; }

        boolean accept(int rowId) {
            int[] w = rowWordsArr[rowId];
            for (int i = 0; i < nw; i++) if ((w[i] & r.acceptBits[i]) != 0) return true;
            return false;
        }

        /** Pure step (no re-seed): all targets of live states on c, masks ignored. */
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
                        if (r.ranges[(base + mid) * 5] <= c) { anchor = mid; rlo = mid + 1; }
                        else rhi = mid - 1;
                    }
                    for (int i = anchor; i >= 0 && r.rhp[base + i] >= c; i--) {
                        int mo = (base + i) * 5;
                        if (c <= r.ranges[mo + 1]) {
                            int t = r.ranges[mo + 2];
                            if (t >= 0) next[t >>> 5] |= 1 << (t & 31);
                        }
                    }
                }
            }
            return next;
        }

        /** Encoded transition for row on c: row id, SDFA_KILL, or -1 (uncapped-cap).
         *  Locked: it computes and interns (mutation); the memoized read path
         *  is {@link #bmpTransition}, which only lands here on block misses. */
        int transition(int rowId, int c) {
            synchronized (lock) {
                int[] d = delta(rowWordsArr[rowId], c);
                boolean empty = true;
                for (int i = 0; i < nw; i++) if (d[i] != 0) { empty = false; break; }
                if (empty) return SDFA_KILL;   // next = pure row 0 + kill
                d[r.startState >>> 5] |= 1 << (r.startState & 31);
                return internRowLocked(d);
            }
        }

        /** Must hold {@link #lock}. Materialize block {@code b} of {@code rowId}:
         *  512 encoded transitions; returns the cell value for (rowId, b):
         *  block id, -3 (all-kill), or -2 (capped → caller computes directly). */
        private int buildBlockLocked(int rowId, int b) {
            int[] cells = new int[512];
            int lo = b << 9;
            boolean allKill = true;
            for (int k = 0; k < 512; k++) {
                int t = transitionLocked(rowId, lo + k);
                if (t == -1) {
                    // capped mid-block: whole block unusable (-2 cells handled by caller)
                    setRowCell(rowId, b, -2);
                    return -2;
                }
                if (t != SDFA_KILL) allKill = false;
                cells[k] = t;
            }
            int blockId;
            if (allKill) {
                blockId = -3;   // shared all-kill block
            } else {
                Wrapper key = new Wrapper(cells);
                Integer cached = blockById.get(key);
                if (cached != null) blockId = cached;
                else {
                    if (blocksArr.length >= SDFA_MAX_BLOCKS) {
                        setRowCell(rowId, b, -2);
                        return -2;
                    }
                    int n = blocksArr.length;
                    int[][] nb = java.util.Arrays.copyOf(blocksArr, n + 1);
                    nb[n] = cells;
                    blocksArr = nb;   // volatile publish BEFORE the cell can point at it
                    blockId = n;
                    blockById.put(key, blockId);
                }
            }
            setRowCell(rowId, b, blockId);
            return blockId;
        }

        /** Must hold {@link #lock}. */
        private int transitionLocked(int rowId, int c) {
            int[] d = delta(rowWordsArr[rowId], c);
            boolean empty = true;
            for (int i = 0; i < nw; i++) if (d[i] != 0) { empty = false; break; }
            if (empty) return SDFA_KILL;
            d[r.startState >>> 5] |= 1 << (r.startState & 31);
            return internRowLocked(d);
        }

        /** Must hold {@link #lock}. Publish the cell for (rowId, b): COW the
         *  rowBlockIdsArr row so an immutable-snapshot reader either sees -1
         *  or the final value — intermediate states are impossible because
         *  the fresh row copy is filled before the snapshot swap. */
        private void setRowCell(int rowId, int b, int value) {
            int[] row = rowBlockIdsArr[rowId];
            int[] fresh = java.util.Arrays.copyOf(row, 128);
            fresh[b] = value;
            int[][] rb = rowBlockIdsArr.clone();
            rb[rowId] = fresh;
            rowBlockIdsArr = rb;   // volatile publish
        }

        /** Encoded transition via blocks; builds lazily. c must be < 0x10000.
         *  Lock-free on the memoized fast path (snapshot reads only); takes
         *  the lock only on first visit of a (row, block) or on capped rows. */
        int bmpTransition(int rowId, int c) {
            int b = c >>> 9;
            int cell = rowBlockIdsArr[rowId][b];
            if (cell == -1) {
                synchronized (lock) {
                    cell = rowBlockIdsArr[rowId][b];   // re-read under lock: may have been built
                    if (cell == -1) cell = buildBlockLocked(rowId, b);
                }
            }
            if (cell == -2) return transition(rowId, c);   // capped: compute directly
            if (cell == -3) return SDFA_KILL;              // all-kill block
            int[][] arr = blocksArr;
            if (cell < arr.length) return arr[cell][c & 511];
            // Stale snapshot vs a fresh id (no happens-before edge between the
            // plain cell read and this volatile read): re-check under the lock.
            synchronized (lock) {
                cell = rowBlockIdsArr[rowId][b];
                if (cell >= 0 && cell < blocksArr.length) return blocksArr[cell][c & 511];
                return transitionLocked(rowId, c);
            }
        }
    }
