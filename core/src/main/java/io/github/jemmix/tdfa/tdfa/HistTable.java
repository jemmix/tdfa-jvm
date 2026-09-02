package io.github.jemmix.tdfa.tdfa;

import java.util.Arrays;

/**
 * Hash-consed tag-history sequences for the determinizer. Configs reference
 * histories by id: identical sequences share one id (and one backing array),
 * and per-sequence derived data — the has-history bitset and the per-tag last
 * sign — is computed once and cached by id.
 *
 * <p>This replaced per-Config {@code int[]} histories: closures derive their
 * sequences from common ancestors, so the copies dominated determinizer memory
 * on history-heavy patterns, and every consumer (signature fill, has-history
 * fill, last-sign scan, final-regop generation) rescanned sequence content per
 * config per state — O(sum |l|) per state, the residual compile cliff after
 * the interning rework. With ids, signatures hash one int per config and
 * consumers read cached tables.
 *
 * <p>Not thread-safe: compilation is single-threaded.
 */
final class HistTable {
    /** id 0 is the canonical empty sequence. */
    static final int EMPTY_ID = 0;

    private int[][] contents = new int[16][];
    /** Primitive-chained hash: per-id content hashes + slot head/next chains —
     *  one boxed HashMap<Long,int[]> per intern became a top profile entry on
     *  append-heavy compiles. */
    private long[] idHash = new long[16];
    private int[] hFirst = new int[32];
    private int[] hNext = new int[16];
    private int hMask = 31;
    private int next = 1;
    /** Per-id caches, null until first requested. */
    private long[][] bitsCache;
    private int[][] lastSignCache;
    private int cachedWords = -1;
    private int cachedTags = -1;

    HistTable() {
        contents[0] = new int[0];
    }

    /** Intern {@code seq} (not retained — copied on first sighting). */
    int intern(int[] seq) {
        if (seq == null || seq.length == 0) return EMPTY_ID;
        long h = 0x9E3779B97F4A7C15L;
        for (int v : seq) h = h * 1000003L + v;
        h ^= h >>> 29;
        int slot = (int) (mix64(h) & hMask);
        for (int id = hFirst[slot]; id != 0; id = hNext[id]) {
            if (Arrays.equals(contents[id], seq)) return id;
        }
        int id = next++;
        if (id >= contents.length) contents = Arrays.copyOf(contents, Math.max(id + 1, contents.length * 2));
        if (id >= idHash.length) idHash = Arrays.copyOf(idHash, Math.max(id + 1, idHash.length * 2));
        if (id >= hNext.length) hNext = Arrays.copyOf(hNext, Math.max(id + 1, hNext.length * 2));
        contents[id] = seq.clone();
        idHash[id] = h;
        hNext[id] = hFirst[slot];
        hFirst[slot] = id;
        if ((id + 1) * 4 > hFirst.length * 3) rehash();
        return id;
    }

    private void rehash() {
        int[] nFirst = new int[hFirst.length * 2];
        int nMask = nFirst.length - 1;
        for (int id = 1; id < next; id++) {
            int ns = (int) (mix64(idHash[id]) & nMask);
            hNext[id] = nFirst[ns];
            nFirst[ns] = id;
        }
        hFirst = nFirst;
        hMask = nMask;
    }

    private static long mix64(long key) {
        key ^= key >>> 33;
        key *= 0xff51afd7ed558ccdL;
        key ^= key >>> 33;
        return key;
    }

    /** The interned content of {@code id} (read-only — callers must not mutate). */
    int[] content(int id) {
        return contents[id];
    }

    /** Bitset of tags appearing in {@code id}'s sequence, {@code words} words.
     *  All requests in one compile use the same word count ({@code (tags+63)/64}). */
    long[] bits(int id, int words) {
        if (cachedWords != words) {
            bitsCache = null;
            cachedWords = words;
        }
        if (bitsCache == null || bitsCache.length < contents.length) {
            int newLen = Math.max(contents.length,
                    (bitsCache == null ? 16 : bitsCache.length) * 2);
            bitsCache = Arrays.copyOf(
                    bitsCache == null ? new long[16][] : bitsCache, newLen);
        }
        long[] bits = bitsCache[id];
        if (bits == null) {
            bits = new long[words];
            int[] seq = contents[id];
            for (int v : seq) {
                int t = Math.abs(v) - 1;
                bits[t >>> 6] |= 1L << t;
            }
            bitsCache[id] = bits;
        }
        return bits;
    }

    /** Per-tag LAST sign in {@code id}'s sequence (0 absent, +1 POS, -1 NIL).
     *  All requests in one compile use the same tag count. */
    int[] lastSign(int id, int tags) {
        if (cachedTags != tags) {
            lastSignCache = null;
            cachedTags = tags;
        }
        if (lastSignCache == null || lastSignCache.length < contents.length) {
            int newLen = Math.max(contents.length,
                    (lastSignCache == null ? 16 : lastSignCache.length) * 2);
            lastSignCache = Arrays.copyOf(
                    lastSignCache == null ? new int[16][] : lastSignCache, newLen);
        }
        int[] last = lastSignCache[id];
        if (last == null) {
            last = new int[tags];
            int[] seq = contents[id];
            for (int v : seq) last[Math.abs(v) - 1] = v > 0 ? 1 : -1;
            lastSignCache[id] = last;
        }
        return last;
    }
}
