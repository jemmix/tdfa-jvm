package io.github.jemmix.tdfa.tdfa;

public class UTreeTest {
    public static void main(String[] args) {
        UTree U = new UTree();
        int n1 = U.extend(U.root(), UTree.packInfo(1, false));
        int n2 = U.extend(n1, UTree.packInfo(2, false));
        int n3 = U.extend(U.root(), UTree.packInfo(1, false));
        int n4 = U.extend(n1, UTree.packInfo(3, false));
        if (n3 != n1) { System.err.println("FAIL: dedup didn't work; n3=" + n3 + " n1=" + n1); return; }
        if (n2 == n4) { System.err.println("FAIL: n2 and n4 should differ"); return; }
        System.err.println("n1=" + n1 + " n2=" + n2 + " n4=" + n4);

        int[] p2 = U.path(n2);
        System.err.print("path(n2)=[");
        for (int i = 0; i < p2.length; i++) { if (i > 0) System.err.print(","); System.err.print(p2[i]); }
        System.err.println("]  (expect [1,2])");

        if (U.pred(n2) != n1) { System.err.println("FAIL: pred(n2)=" + U.pred(n2) + " want " + n1); return; }
        if (U.pred(n1) != 0) { System.err.println("FAIL: pred(n1)=" + U.pred(n1) + " want 0"); return; }
        System.err.println("pred chain OK");

        int[] height = {0, 1, 1, 2};
        long r1 = GtopCompare.compare(n2, n2, 0, 0, U, height, null, 0);
        if (GtopCompare.l(r1) != 0) { System.err.println("FAIL: same path l=" + GtopCompare.l(r1)); return; }
        System.err.println("compare(n2,n2) l=0 OK");

        // n2 path: [1, 2] (open g1, close g1). n4 path: [1, 3] (open g1, open g2).
        // Common ancestor n1 (tag 1, height 1). Divergent: tag 2 (h=1) vs tag 3 (h=2).
        // After common-ancestor contribution: h1 = min(1,1) = 1, h2 = min(2,1) = 1.
        // Equal heights ⇒ leftprec tiebreak. tag 2 (close) wins over tag 3 (open).
        // l = -1 (c1 wins).
        long r2 = GtopCompare.compare(n2, n4, 0, 0, U, height, null, 0);
        System.err.println("compare(n2,n4): h1=" + GtopCompare.h1(r2) + " h2=" + GtopCompare.h2(r2) + " l=" + GtopCompare.l(r2));
        if (GtopCompare.h1(r2) != 1) { System.err.println("FAIL: h1 expected 1, got " + GtopCompare.h1(r2)); return; }
        if (GtopCompare.h2(r2) != 1) { System.err.println("FAIL: h2 expected 1, got " + GtopCompare.h2(r2)); return; }
        if (GtopCompare.l(r2) != -1) { System.err.println("FAIL: l expected -1 (close wins over open), got " + GtopCompare.l(r2)); return; }

        // Test leftprec indirectly: n5 (close g1) vs n6 (open g1 then open g1 again).
        // Common ancestor: root. Divergent: tag 2 (close, h=1) vs tag 1 (open, h=1).
        // h1 = 1, h2 = 1 (after common = 1, 1). Equal. leftprec: close beats open. l = -1.
        int n5 = U.extend(U.root(), UTree.packInfo(2, false));
        int n6 = U.extend(U.extend(U.root(), UTree.packInfo(1, false)), UTree.packInfo(1, false));
        long r3 = GtopCompare.compare(n5, n6, 0, 0, U, height, null, 0);
        System.err.println("compare(n5,n6): h1=" + GtopCompare.h1(r3) + " h2=" + GtopCompare.h2(r3) + " l=" + GtopCompare.l(r3));
        if (GtopCompare.l(r3) != -1) { System.err.println("FAIL: l expected -1 (close wins), got " + GtopCompare.l(r3)); return; }

        System.err.println("All UTree sanity checks passed.");
    }
}
