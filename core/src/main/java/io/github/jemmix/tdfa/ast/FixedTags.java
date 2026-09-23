package io.github.jemmix.tdfa.ast;

import io.github.jemmix.tdfa.tdfa.FrameBudget;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * BT22 §6.4 "Fixed tags": mark tags whose match-time position can be reconstructed
 * from another tag at a fixed character-distance, so the NFA builder can omit them
 * and the runtime can recompute them.
 *
 * <p>Implements {@code alg_fixed_tags} (paper Figure 9, p.28). Linear-time
 * top-down pass over the AST — the paper's recursion evaluated iteratively
 * over an explicit frame stack (see {@link Walker}), so AST depth costs heap
 * (charged to the transient compile RAM budget, io.github.jemmix.tdfa.tdfa.FrameBudget), not
 * JVM stack. Each visit yields a 3-tuple, propagated bottom-up:
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
 * each {@link Ast.Tag} is visited exactly once.
 */
public final class FixedTags {
    /** Sentinel "no inherited base tag". Tags explored with this value cannot fix
     *  on an outer tag; they may become a new base themselves. */
    private static final int NO_BASE = -1;
    /** Sentinel "unknown distance". Propagates through arithmetic. Distinct from any
     *  real value (which is always {@code >= 0}). */
    private static final int NAN = Integer.MIN_VALUE;

    private FixedTags() {
    }

    /** Walk {@code root}, annotating each fixable {@link Ast.Tag} in place. */
    public static void apply(Ast root) {
        new Walker().walk(root);
    }

    /**
     * Iterative evaluator of the paper's top-down recursion. One {@link Frame}
     * per container node (concat/alt/repeat) carries the inherited attributes
     * down and folds each child's synthesized triple back up, visiting nodes
     * in the order the paper's recursion prescribes: concat children right-to-left
     * with the threading triple, alt branches in order under
     * {@code (NO_BASE, NAN, 0)}, repeat bodies likewise. Leaves are evaluated
     * inline — nothing accumulates. The synthesized triple of the last
     * completed sub-walk lives in the {@code r*} registers — the value a
     * recursive formulation would return.
     *
     * <p>Note on desugaring: bounded reps with shared body references (e.g.
     * {@code (a){3}} desugared to three aliased sub-ASTs) would break the
     * annotation because the same {@code Ast.Tag} would be visited multiple
     * times with conflicting values. That is why {@link #apply} runs BEFORE
     * {@code Tnfa.Builder}'s desugaring pass, so each Tag node is visited
     * exactly once.
     */
    private static final class Walker {
        private final Deque<Frame> stack = new ArrayDeque<>();
        private final FrameBudget frames = FrameBudget.create();
        /** Registers: synthesized {baseTag, dist, levelDist} of the last
         *  completed sub-walk (what a recursive formulation would return). */
        private int rb = NO_BASE, rd = NAN, rl = NAN;

        private static final class Frame {
            Ast node;
            int inB, inD, inL; // inherited attributes
            boolean resumed; // false = entering, true = a child just completed
            // Concat scratch: children right-to-left with the threading triple
            List<Ast> ch;
            int idx;
            int bt, d, ld;
            // Alt scratch: level-length agreement across branches
            int agreed = NAN;
            boolean allAgree = true;
        }

        void walk(Ast root) {
            visit(root, NO_BASE, NAN, NAN);
            while (!stack.isEmpty()) {
                Frame f = stack.peek();
                Ast e = f.node;
                if (!f.resumed) {
                    if (e instanceof Ast.Concat) {
                        List<Ast> ch = ((Ast.Concat) e).children;
                        f.ch = ch;
                        f.bt = f.inB;
                        f.d = f.inD;
                        f.ld = f.inL;
                        f.idx = ch.size() - 1;
                        f.resumed = true;
                        if (f.idx >= 0) {
                            visit(ch.get(f.idx), f.bt, f.d, f.ld);
                        } else {
                            complete(f.bt, f.d, f.ld);
                        }
                    } else if (e instanceof Ast.Alt) {
                        List<Ast> ch = ((Ast.Alt) e).children;
                        f.ch = ch;
                        f.idx = 0;
                        f.resumed = true;
                        if (!ch.isEmpty()) {
                            visit(ch.get(0), NO_BASE, NAN, 0);
                        } else {
                            complete(f.inB, add(f.inD, f.agreed), add(f.inL, f.agreed));
                        }
                    } else if (e instanceof Ast.Repeat) {
                        f.resumed = true;
                        visit(((Ast.Repeat) e).body, NO_BASE, NAN, 0);
                    } else {
                        throw new IllegalStateException("unknown ast: " + e);
                    }
                } else if (e instanceof Ast.Concat) {
                    f.bt = rb;
                    f.d = rd;
                    f.ld = rl;
                    f.idx--;
                    if (f.idx >= 0) {
                        visit(f.ch.get(f.idx), f.bt, f.d, f.ld);
                    } else {
                        complete(f.bt, f.d, f.ld);
                    }
                } else if (e instanceof Ast.Alt) {
                    // Branch agreement uses the paper's eq semantics: NaN != NaN and NaN != k.
                    // A branch whose levelDist is NaN (e.g. it contains an unbounded repeat —
                    // the construct can consume a VARIABLE number of chars, including zero)
                    // must poison the agreement. Without this, (a*|b) would claim fixed
                    // length 1 (both branches "agree" after the nullable a*'s NaN is
                    // swallowed), the group's open tag would wrongly fix onto the close tag
                    // at offset 1, and empty-iteration matches like (a*|b)* on "" would
                    // reconstruct start = end - 1 = -1 instead of 0.
                    int k = rl;
                    if (f.allAgree) {
                        if (k == NAN) {
                            f.allAgree = false;
                        } else if (f.agreed == NAN) {
                            f.agreed = k;
                        } else if (f.agreed != k) {
                            f.allAgree = false;
                        }
                    }
                    f.idx++;
                    if (f.idx < f.ch.size()) {
                        visit(f.ch.get(f.idx), NO_BASE, NAN, 0);
                    } else if (f.allAgree) {
                        complete(f.inB, add(f.inD, f.agreed), add(f.inL, f.agreed));
                    } else {
                        complete(f.inB, NAN, NAN);
                    }
                } else if (e instanceof Ast.Repeat) {
                    Ast.Repeat r = (Ast.Repeat) e;
                    int k1 = rl;
                    if (r.min == r.max && k1 != NAN) {
                        int delta = mul(r.min, k1);
                        complete(f.inB, add(f.inD, delta), add(f.inL, delta));
                    } else {
                        complete(f.inB, NAN, NAN);
                    }
                } else {
                    throw new IllegalStateException("unknown ast: " + e);
                }
            }
        }

        /** Stack container nodes; evaluate leaves inline — leaves have no
         *  children to thread through, so they allocate nothing and must not
         *  accumulate on the frame stack. */
        private void visit(Ast e, int baseTag, int dist, int levelDist) {
            if (e instanceof Ast.Concat || e instanceof Ast.Alt || e instanceof Ast.Repeat) {
                Frame f = new Frame();
                f.node = e;
                f.inB = baseTag;
                f.inD = dist;
                f.inL = levelDist;
                frames.push();
                stack.push(f);
                return;
            }
            if (e instanceof Ast.Symbol) {
                rb = baseTag;
                rd = add(dist, 1);
                rl = add(levelDist, 1);
                return;
            }
            if (e instanceof CharClass) {
                // Width in UTF-16 units: a supplementary-only class consumes two
                // units per match, a mixed-width class (e.g. `.`) has no fixed
                // width at all — both must poison rather than assume 1, or tag
                // reconstruction lands one unit off on supplementary matches.
                int w = ((CharClass) e).fixedUtf16Width();
                int d = w < 0 ? NAN : w;
                rb = baseTag;
                rd = add(dist, d);
                rl = add(levelDist, d);
                return;
            }
            if (e instanceof Ast.Tag) {
                Ast.Tag t = (Ast.Tag) e;
                if (baseTag != NO_BASE && dist != NAN && baseTag != t.tag) {
                    t.fixedOn = baseTag;
                    t.fixedOffset = dist;
                    rb = baseTag;
                    rd = dist;
                    rl = levelDist;
                    return;
                }
                rb = t.tag;
                rd = 0;
                rl = levelDist;
                return;
            }
            if (e instanceof Ast.Empty || e instanceof Ast.StartAnchor || e instanceof Ast.EndAnchor
                            || e instanceof Ast.WordBoundary || e instanceof Ast.NoWordBoundary) {
                rb = baseTag;
                rd = dist;
                rl = levelDist;
                return;
            }
            throw new IllegalStateException("unknown ast: " + e);
        }

        /** Complete the current frame with its synthesized triple: publish
         *  the registers (the parent's resume reads them) and pop. */
        private void complete(int b, int d, int l) {
            rb = b;
            rd = d;
            rl = l;
            stack.pop();
            frames.pop();
        }
    }

    // Overflow-poisoned like NAN: a wrapped fixed offset is garbage that would
    // silently mis-derive capture positions (large {n} × multi-char bodies).
    // Poisoning only forgoes the fixed-tag optimization for that tag — sound.
    private static int add(int a, int b) {
        if (a == NAN || b == NAN) {
            return NAN;
        }
        int r = a + b;
        if (((a ^ r) & (b ^ r)) < 0) {
            return NAN;
        }
        return r;
    }

    private static int mul(int n, int k) {
        if (k == NAN) {
            return NAN;
        }
        long r = (long) n * k;
        if (r != (int) r) {
            return NAN;
        }
        return (int) r;
    }
}
