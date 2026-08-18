package io.github.jemmix.tdfa.tdfa;

/**
 * BT19 §8 — POSIX precedence comparison via two-fingers fork finding.
 *
 * <p>Compares two configurations (path + origin) and returns the precedence
 * decision. The comparison walks both paths upward through the {@link UTree}
 * to their fork, taking the running minimum of tag heights (= the "trace").
 *
 * <p>Longest precedence: at the divergence, the path whose minimal height is
 * <em>larger</em> wins (in BT19's sign convention, larger height = more
 * enclosing context = preferred).
 *
 * <p>Leftmost precedence: tiebreak at a fresh fork, comparing the two tags
 * right after the fork with PE order {@code ⊥ < ) < ( < nil}.
 *
 * <p>Sign convention throughout: {@code l < 0} means {@code c1} wins (POSIX
 * preferred); {@code l > 0} means {@code c2} wins; {@code l == 0} means
 * equivalent.
 */
final class PosixCompare {

    /** "Infinity" for heights: no divergence found. Lower 30 bits, signed. */
    static final int MAX_RHO = 0x1FFFFFFF;

    /**
     * Compare two configurations.
     *
     * @param u1    path of c1 (UTree node index)
     * @param u2    path of c2
     * @param orig1 origin state of c1 (index into the previous step's prectable)
     * @param orig2 origin state of c2
     * @param U     the U-tree
     * @param height tag-height lookup (group number → height)
     * @param oldPrectable packed H+P table from previous step; null if no previous step
     * @param prevClosureSize size of previous closure (for indexing oldPrectable)
     * @return packed {@code (h1, h2, l)}: use {@link #h1(long)}, {@link #h2(long)}, {@link #l(long)}.
     */
    static long compare(int u1, int u2, int orig1, int orig2,
                        UTree U, int[] height,
                        int[] oldPrectable, int prevClosureSize) {
        if (orig1 == orig2 && u1 == u2) return pack(MAX_RHO, MAX_RHO, 0);

        boolean fork = (orig1 == orig2);
        int h1, h2;
        if (fork) {
            h1 = h2 = MAX_RHO;
        } else if (oldPrectable != null) {
            int cell1 = oldPrectable[orig1 * prevClosureSize + orig2];
            int cell2 = oldPrectable[orig2 * prevClosureSize + orig1];
            h1 = unpackH(cell1);
            h2 = unpackH(cell2);
        } else {
            h1 = h2 = MAX_RHO;
        }

        // Two-fingers: walk u1 and u2 up to their common ancestor.
        // parent index < child index invariant: replace the larger index.
        int u1Prime = -1, u2Prime = -1;  // first diverging nodes (right after the fork)
        while (u1 != u2) {
            if (u1 > u2) {
                int info = U.info(u1);
                if (info != 0) {
                    int t = UTree.tagOf(info);
                    int hh = height[t];
                    if (hh < h1) h1 = hh;
                }
                u1Prime = u1;
                u1 = U.pred(u1);
            } else {
                int info = U.info(u2);
                if (info != 0) {
                    int t = UTree.tagOf(info);
                    int hh = height[t];
                    if (hh < h2) h2 = hh;
                }
                u2Prime = u2;
                u2 = U.pred(u2);
            }
        }
        // Common suffix above the fork contributes equally to both.
        if (u1 != 0) {
            int info = U.info(u1);
            if (info != 0) {
                int t = UTree.tagOf(info);
                int hh = height[t];
                if (hh < h1) h1 = hh;
                if (hh < h2) h2 = hh;
            }
        }

        int l;
        if (h1 > h2) l = -1;
        else if (h1 < h2) l = 1;
        else if (!fork) {
            // Defer to old P table.
            if (oldPrectable != null) {
                int cell = oldPrectable[orig1 * prevClosureSize + orig2];
                l = unpackP(cell);
            } else {
                l = 0;
            }
        } else {
            // Fresh fork: decide by diverging tags.
            l = leftprec(u1Prime, u2Prime, U);
        }
        return pack(h1, h2, l);
    }

    /**
     * Leftmost tiebreak at a fresh fork (BT19 §8 leftprec).
     *
     * <p>PE order: {@code ⊥ < ) < ( < nil} (end-of-path < closing < opening < nil).
     *
     * @param u1 first diverging node of c1; -1 if path ended at fork
     * @param u2 first diverging node of c2; -1 if path ended at fork
     */
    static int leftprec(int u1, int u2, UTree U) {
        if (u1 == u2) return 0;
        if (u1 == -1) return -1;     // c1's path ended ⇒ shorter ⇒ leftmost loses
        if (u2 == -1) return 1;
        int info1 = U.info(u1);
        int info2 = U.info(u2);
        boolean neg1 = UTree.isNegative(info1);
        boolean neg2 = UTree.isNegative(info2);
        int t1 = UTree.tagOf(info1);
        int t2 = UTree.tagOf(info2);

        // Positive (match) wins over negative (nil).
        if (!neg1 && neg2) return -1;
        if (neg1 && !neg2) return 1;

        // Both positive: closing (odd tag number — tag 2i = close of group i)
        // wins over opening (even — tag 2i-1 = open of group i).
        //
        // Tag numbering convention: group i has open=2i-1, close=2i.
        // So open is ODD, close is EVEN... wait that conflicts with the
        // comment in BT19. Let me re-derive.
        //
        // In re2c, tag numbering: 1=open(g1), 2=close(g1), 3=open(g2), ...
        // So ODD tags are opens; EVEN tags are closes.
        // PE order: closing > opening, so EVEN > ODD.
        // In our convention: c1 wins (return -1) when its tag has higher PE order.
        boolean even1 = (t1 % 2 == 0);
        boolean even2 = (t2 % 2 == 0);
        if (even1 && !even2) return -1;  // c1 is closing, c2 is opening: c1 wins
        if (!even1 && even2) return 1;   // c2 is closing, c1 is opening: c2 wins
        return 0;
    }

    // ---- packing ----

    /** Pack (h1, h2, l) into a long for return. */
    static long pack(int h1, int h2, int l) {
        return ((long) (h1 & 0x3FFFFFFF)) |
               (((long) (h2 & 0x3FFFFFFF)) << 30) |
               (((long) (l & 0x3)) << 60);
    }

    static int h1(long packed) { return signExtend30((int) (packed & 0x3FFFFFFF)); }
    static int h2(long packed) { return signExtend30((int) ((packed >>> 30) & 0x3FFFFFFF)); }
    static int l(long packed) {
        int raw = (int) ((packed >>> 60) & 0x3);
        // sign-extend 2-bit value to interpret 0b11 as -1
        if (raw >= 2) return raw - 4;
        return raw;
    }

    /** Pack (h, p) for the prectable. h is 30 bits signed, p is 2 bits. */
    static int packCell(int h, int p) {
        return (h & 0x3FFFFFFF) | ((p & 0x3) << 30);
    }

    static int unpackH(int cell) { return signExtend30(cell & 0x3FFFFFFF); }
    static int unpackP(int cell) {
        int raw = (cell >>> 30) & 0x3;
        if (raw >= 2) return raw - 4;
        return raw;
    }

    private static int signExtend30(int v) {
        // If bit 29 set, sign-extend.
        if ((v & 0x20000000) != 0) return v | 0xC0000000;
        return v;
    }
}
