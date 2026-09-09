package io.github.jemmix.tdfa.tdfa;

/** Construction-time table builders + literal-needle analysis for
 *  TdfaRunner — extracted verbatim (2026-09 god-file split; statics, no
 *  instance state). */
final class RunnerTables {
    private RunnerTables() {}

    /** Build flat per-state range-index lookup: {@code [state * limit + c] → range index} (-1 = dead). */
    static int[] buildAsciiRangeFlat(Tdfa tdfa, int limit) {
        int[] sm = tdfa.stateMeta, rg = tdfa.ranges;
        int[] flat = new int[tdfa.stateCount * limit];
        java.util.Arrays.fill(flat, -1);
        for (int s = 0; s < tdfa.stateCount; s++) {
            int meta = sm[s];
            int base = tdfa.stateBase[s], cnt = (meta >>> 1) & 0xFFFF;
            for (int i = 0; i < cnt; i++) {
                int o = (base + i) * 5;
                int lo = Math.max(rg[o], 0);
                int hi = Math.min(rg[o + 1], limit - 1);
                for (int c = lo; c <= hi; c++) flat[s * limit + c] = i;
            }
        }
        return flat;
    }

    /**
     * Build flat per-state target lookup: {@code [state * limit + c] → target state}
     * (-1 = dead). {@code limit} is 256 (Latin-1) for DFAs under
     * {@link TdfaRunner#LATIN1_MAX_STATES} states, else 128. Codepoints 128..255 are single
     * UTF-16 units and never surrogate halves, so indexing them directly is exact.
     */
    static int[] buildAsciiTarget(Tdfa tdfa, int limit) {
        int[] sm = tdfa.stateMeta, rg = tdfa.ranges;
        int[] flat = new int[tdfa.stateCount * limit];
        java.util.Arrays.fill(flat, -1);
        for (int s = 0; s < tdfa.stateCount; s++) {
            int meta = sm[s];
            int base = tdfa.stateBase[s], cnt = (meta >>> 1) & 0xFFFF;
            for (int i = 0; i < cnt; i++) {
                int o = (base + i) * 5;
                int lo = Math.max(rg[o], 0);
                int hi = Math.min(rg[o + 1], limit - 1);
                int target = rg[o + 2];
                for (int c = lo; c <= hi; c++) flat[s * limit + c] = target;
            }
        }
        return flat;
    }

    /**
     * Bitset of states with accept capability (stateMeta bit 0 set). This is an
     * over-approximation for the generic path — a state may be only conditionally
     * accepting (non-zero acceptMask), but for the multi-state no-match pre-check
     * we want to err on the side of "might accept" so we never skip a real match.
     */
    static int[] buildAcceptBits(Tdfa tdfa) {
        int words = (tdfa.stateCount + 31) >>> 5;
        int[] bits = new int[words];
        for (int s = 0; s < tdfa.stateCount; s++) {
            if ((tdfa.stateMeta[s] & 1) != 0) {
                bits[s >>> 5] |= 1 << (s & 31);
            }
        }
        return bits;
    }

    /** Check if all states have pairwise-disjoint ranges (no overlapping ranges). */
    static boolean checkRangesDisjoint(Tdfa tdfa) {
        int[] sm = tdfa.stateMeta, rg = tdfa.ranges;
        long[] sortBuf = null;
        for (int s = 0; s < tdfa.stateCount; s++) {
            int meta = sm[s];
            int base = tdfa.stateBase[s], cnt = (meta >>> 1) & 0xFFFF;
            if (cnt < 2) continue;
            // Fast path: ranges are emitted sorted by lo at materialization
            // (sortByMaskSpecificity is the only reorderer) — one O(cnt) scan.
            boolean sortedByLo = true;
            for (int i = 1; i < cnt; i++) {
                if (rg[(base + i) * 5] < rg[(base + i - 1) * 5]) { sortedByLo = false; break; }
            }
            if (!sortedByLo) {
                // Pack (lo << 32)|hi and sort — O(cnt log cnt) vs the old O(cnt²)
                // pairwise check (significant for wide Unicode classes, ~1369 ranges).
                if (sortBuf == null || sortBuf.length < cnt) sortBuf = new long[Math.max(cnt, 64)];
                for (int i = 0; i < cnt; i++) {
                    int o = (base + i) * 5;
                    sortBuf[i] = ((long) rg[o] << 32) | (rg[o + 1] & 0xFFFFFFFFL);
                }
                java.util.Arrays.sort(sortBuf, 0, cnt);
                int maxHi = (int) sortBuf[0];
                for (int i = 1; i < cnt; i++) {
                    int lo = (int) (sortBuf[i] >>> 32);
                    if (lo <= maxHi) return false;  // overlaps the interval holding maxHi
                    int hi = (int) sortBuf[i];
                    if (hi > maxHi) maxHi = hi;
                }
                continue;
            }
            // Sorted by lo: adjacent scan with running max-hi (a long early range
            // can overlap several later ones, so plain prev-pair checks aren't enough).
            int maxHi = rg[base * 5 + 1];
            for (int i = 1; i < cnt; i++) {
                int o = (base + i) * 5;
                if (rg[o] <= maxHi) return false;
                if (rg[o + 1] > maxHi) maxHi = rg[o + 1];
            }
        }
        return true;
    }

    /** indexOf for the literal needle that respects the alphabet: a hit is
     *  real only if it starts at a codepoint boundary (not the low half of a
     *  pair) and does not end on the high half of a pair. Raw indexOf sees
     *  UTF-16 units and would otherwise accept unit sequences that overlap
     *  pair halves — e.g. needle "a\uD800" on input "a\uD800\uDFFF". */
    static int literalIndexOf(String s, String needle, int from) {
        int idx = s.indexOf(needle, from);
        while (idx >= 0
                && (io.github.jemmix.tdfa.ast.Alphabet.pairInterior(s, idx) || needleEndOverlapsPair(s, idx, needle.length())))
            idx = s.indexOf(needle, idx + 1);
        return idx;
    }

    /** True when a needle hit ending at unit {@code idx + needleLen - 1}
     *  swallows the high half of a surrogate pair: the last needle unit is a
     *  high surrogate that pairs with the next input unit, so the raw-unit
     *  indexOf hit is not a codepoint-sequence match. Public static: the
     *  ASM-emitted literal path calls it for the same guard. */
    static boolean needleEndOverlapsPair(String s, int idx, int needleLen) {
        int last = s.charAt(idx + needleLen - 1);
        if (last < 0xD800 || last > 0xDBFF) return false;
        int end = idx + needleLen;
        return end < s.length()
                && s.charAt(end) >= 0xDC00 && s.charAt(end) <= 0xDFFF;
    }

    static void setBit(long[] bits, int c) { bits[c >>> 6] |= 1L << (c & 63); }

    /** Word-class bitset over BMP UTF-16 units. ASCII mode (null ranges):
     *  the 63-char [_0-9A-Za-z] set; unicode mode: wordRanges clipped to the BMP. */
    static long[] buildWordBits(int[] ranges) {
        long[] bits = new long[1024];
        if (ranges == null) {
            setBit(bits, '_');
            for (int c = '0'; c <= '9'; c++) setBit(bits, c);
            for (int c = 'a'; c <= 'z'; c++) setBit(bits, c);
            for (int c = 'A'; c <= 'Z'; c++) setBit(bits, c);
            return bits;
        }
        for (int i = 0; i + 1 < ranges.length; i += 2) {
            int lo = Math.max(ranges[i], 0), hi = Math.min(ranges[i + 1], 0xFFFF);
            for (int c = lo; c <= hi; c++) bits[c >>> 6] |= 1L << (c & 63);
        }
        return bits;
    }

    /** Detect the literal-chain shape; null otherwise. Public static: the
     *  ASM backend asks at emit time so literal DFAs get the fully-delegated
     *  generated class (its indexOf short-circuit beats the generated walk
     *  at every input length). */
    static String detectLiteralNeedle(Tdfa tdfa) {
        try {
            if (tdfa.groupCount != 0 || tdfa.tagCount != 0) return null;
            int n = tdfa.stateCount;
            if (n < 2) return null;   // single-state: empty/anchor-only regex
            StringBuilder sb = new StringBuilder(n - 1);
            int s = tdfa.startState;
            for (int step = 0; step < n - 1; step++) {
                int meta = tdfa.stateMeta[s];
                if ((meta & 1) != 0) return null;            // accepting mid-chain
                int cnt = (meta >>> 1) & 0xFFFF;
                if (cnt != 1) return null;                   // must be exactly one char
                int o = tdfa.stateBase[s] * 5;
                int lo = tdfa.ranges[o], hi = tdfa.ranges[o + 1];
                if (lo != hi || lo > 0xFFFF) return null;    // single BMP codepoint
                if (tdfa.ranges[o + 2] < 0) return null;     // dead
                if (tdfa.ranges[o + 3] != 0) return null;    // transition ops
                if (tdfa.ranges[o + 4] != 0) return null;    // required mask
                if (tdfa.stateEntryMask[tdfa.ranges[o + 2]] != 0) return null;
                sb.append((char) lo);
                s = tdfa.ranges[o + 2];
            }
            // final state: accepting, no mask, no fallback, no final ops, and
            // NO live outgoing transition (a live self-loop means the regex is
            // unbounded — a+ misdetected as literal "a" returned [0,1) for
            // find("a+","aaa") instead of [0,3)).
            if ((tdfa.stateMeta[s] & 1) == 0) return null;
            if (tdfa.stateAcceptMask[s] != 0) return null;
            // Position-dependent accept (byMask variants): the accept fires
            // only under some posFlags — the indexOf shortcut can't evaluate
            // that (fuzz round 10: Z(?:\A|\B) matched "Z" via the needle,
            // though \A and \B both fail at pos 1). Not a literal.
            {
                int[] fm = tdfa.stateFinalOpsByMask();
                if (fm != null) {
                    for (int M = 0; M < 64; M++) {
                        if (fm[s * 64 + M] < 0) return null;
                    }
                }
            }
            if (tdfa.stateFinalOpsOff[s] != 0) return null;
            if (tdfa.stateEntryMask[s] != 0) return null;
            {
                int meta = tdfa.stateMeta[s];
                int base = tdfa.stateBase[s];
                for (int i = 0; i < ((meta >>> 1) & 0xFFFF); i++) {
                    if (tdfa.ranges[(base + i) * 5 + 2] >= 0) return null;
                }
            }
            // Lone-surrogate adjacency: the needle is built from single BMP
            // symbols, each appended as its raw unit. Two adjacent LONE
            // symbols (high then low) re-encode as a well-formed surrogate
            // PAIR — the same unit text as the pair codepoint they are not.
            // Unit-wise indexOf then matches input pairs against what the
            // alphabet defines as two lone codepoints (fuzz repro:
            // (?i:\uD800)\uDFFF matched 𐏿 = \uD800\uDFFF whole). Rejected
            // here, the DFA walk handles the shape correctly (it decodes).
            for (int i = 0; i < sb.length() - 1; i++) {
                char c0 = sb.charAt(i), c1 = sb.charAt(i + 1);
                if (c0 >= 0xD800 && c0 <= 0xDBFF && c1 >= 0xDC00 && c1 <= 0xDFFF)
                    return null;
            }
            return sb.length() > 0 ? sb.toString() : null;
        } catch (RuntimeException e) {
            return null;   // any surprise shape: not a literal
        }
    }
}
