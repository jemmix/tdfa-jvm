package io.github.jemmix.tdfa.opt;

import io.github.jemmix.tdfa.ast.Ast;
import io.github.jemmix.tdfa.ast.CharClass;

import java.util.List;

/**
 * BT22 §6.4 "Fixed tags": mark tags whose match-time position can be reconstructed
 * from another tag at a fixed character-distance, so the NFA builder can omit them
 * and the runtime can recompute them.
 *
 * <p>Implements {@code alg_fixed_tags} (paper Figure 9, p.28). Linear-time top-down
 * recursion over the AST. Each call returns a 3-tuple, propagated bottom-up:
 * <ul>
 *   <li><b>{@code baseTag}</b> — the tag we're currently fixing onto (or {@link #NO_BASE}),
 *       inherited only through concat (and bounded repeat with {@code n == m});</li>
 *   <li><b>{@code dist}</b> — character-distance from {@code baseTag}'s position
 *       (number of input chars consumed between the base tag and the current point);</li>
 *   <li><b>{@code levelDist}</b> — character-distance since the last level boundary.
 *       A new level is introduced by each alt branch and each repeat body, blocking
 *       {@code baseTag} inheritance into them but still letting the parent track
 *       fixed-length contributions for tags <em>after</em> the construct.</li>
 * </ul>
 *
 * <p>{@link #NAN} is the sentinel for "unknown distance". It propagates: any arithmetic
 * with NAN yields NAN; equality with NAN is false. This encodes the paper's NaN semantics.
 *
 * <p>Side effect: annotates {@link Ast.Tag} nodes with {@code fixedOn} / {@code fixedOffset}
 * when the tag can be reconstructed.
 *
 * <p>Runs BEFORE {@code Tnfa.Builder} desugaring of bounded reps (e.g. {@code e{3}} →
 * {@code eee}). The desugaring shares the same AST subtree reference for each iteration,
 * which would break the algorithm if applied after; running on the un-desugared tree means
 * each {@code Ast.Tag} is visited exactly once.
 */
public final class FixedTags {
    /** Sentinel "no inherited base tag". Tags explored with this value cannot fix
     *  on an outer tag; they may become a new base themselves. */
    private static final int NO_BASE = -1;
    /** Sentinel "unknown distance". Propagates through arithmetic. Distinct from any
     *  real value (which is always {@code >= 0}). */
    private static final int NAN = Integer.MIN_VALUE;

    private FixedTags() {}

    /** Walk {@code root}, annotating each fixable {@link Ast.Tag} in place. */
    public static void apply(Ast root) {
        walk(root, NO_BASE, NAN, NAN);
    }

    /**
     * Single AST-node dispatch. Returns {@code {baseTag, dist, levelDist}}.
     *
     * <p>Note on desugaring: bounded reps with shared body references (e.g.
     * {@code (a){3}} desugared to three aliased sub-ASTs) would break this algorithm
     * because the same {@code Ast.Tag} would be visited multiple times with conflicting
     * annotations. We avoid this by running BEFORE {@code Tnfa.Builder}'s desugaring
     * pass, so each Tag node is visited exactly once.
     */
    private static int[] walk(Ast e, int baseTag, int dist, int levelDist) {
        if (e instanceof Ast.Empty) {
            return new int[]{baseTag, dist, levelDist};
        }
        if (e instanceof Ast.Symbol) {
            return new int[]{baseTag, add(dist, 1), add(levelDist, 1)};
        }
        if (e instanceof CharClass) {
            return new int[]{baseTag, add(dist, 1), add(levelDist, 1)};
        }
        if (e instanceof Ast.StartAnchor || e instanceof Ast.EndAnchor
                || e instanceof Ast.WordBoundary || e instanceof Ast.NoWordBoundary) {
            return new int[]{baseTag, dist, levelDist};
        }
        if (e instanceof Ast.Tag) {
            Ast.Tag t = (Ast.Tag) e;
            if (baseTag != NO_BASE && dist != NAN && baseTag != t.tag) {
                t.fixedOn = baseTag;
                t.fixedOffset = dist;
                return new int[]{baseTag, dist, levelDist};
            }
            return new int[]{t.tag, 0, levelDist};
        }
        if (e instanceof Ast.Concat) {
            List<Ast> ch = ((Ast.Concat) e).children;
            int bt = baseTag, d = dist, ld = levelDist;
            for (int i = ch.size() - 1; i >= 0; i--) {
                int[] r = walk(ch.get(i), bt, d, ld);
                bt = r[0]; d = r[1]; ld = r[2];
            }
            return new int[]{bt, d, ld};
        }
        if (e instanceof Ast.Alt) {
            int agreed = NAN;
            boolean allAgree = true;
            for (Ast child : ((Ast.Alt) e).children) {
                int[] r = walk(child, NO_BASE, NAN, 0);
                int k = r[2];
                if (allAgree) {
                    if (agreed == NAN) agreed = k;
                    else if (agreed != k) allAgree = false;
                }
            }
            if (allAgree && agreed != NAN) {
                return new int[]{baseTag, add(dist, agreed), add(levelDist, agreed)};
            }
            return new int[]{baseTag, NAN, NAN};
        }
        if (e instanceof Ast.Repeat) {
            Ast.Repeat r = (Ast.Repeat) e;
            int[] bodyResult = walk(r.body, NO_BASE, NAN, 0);
            int k1 = bodyResult[2];
            if (r.min == r.max && k1 != NAN) {
                int delta = mul(r.min, k1);
                return new int[]{baseTag, add(dist, delta), add(levelDist, delta)};
            }
            return new int[]{baseTag, NAN, NAN};
        }
        throw new IllegalStateException("unknown ast: " + e);
    }

    private static int add(int a, int b) {
        if (a == NAN || b == NAN) return NAN;
        return a + b;
    }

    private static int mul(int n, int k) {
        if (k == NAN) return NAN;
        return n * k;
    }
}
