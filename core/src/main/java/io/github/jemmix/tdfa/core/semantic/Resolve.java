package io.github.jemmix.tdfa.core.semantic;

import io.github.jemmix.tdfa.core.ast.Ast;
import io.github.jemmix.tdfa.core.ast.CharClass;
import io.github.jemmix.tdfa.core.dialect.Terminators;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/**
 * The meaning-resolution pass: the one place a compile's interpretation
 * policy is applied to the parse output's still-symbolic parts. The parser
 * produces a syntax-faithful tree — the bare dot stays a symbolic
 * {@link Ast.Dot} carrying its scoped {@code (?s)} state, and every
 * quantifier carries the structural fact of what it directly quantifies
 * ({@link Ast.Repeat#quantifiedCapture}) — and this pass resolves it
 * against the policy's own vocabulary ({@code Terminators} from the
 * dialect package, the EMPTY_ITERATION_SPANS protocol bit), so no
 * parse-side axis mirrors a meaning the pipeline already carries (see
 * {@code docs/engine-compat-surface.md}):
 *
 * <ul>
 * <li>the dot expands to its {@link CharClass}: everything under
 *     dotall, the compile's terminator-set complement otherwise (the
 *     stock sets keep the precomputed constants, so stock-lane output
 *     is bit-identical to the pre-symbolic parser's);</li>
 * <li>the family-6 marks ({@code Tag.dissolved}/{@code Tag.surfaceFinal})
 *     and {@code Repeat.zeroWidthExit} are placed from the
 *     EMPTY_ITERATION_SPANS policy — on the axis-set reading (the RE2
 *     lineage) nothing is marked and those artifacts are bit-identical
 *     to an unmarked parse.</li>
 * </ul>
 *
 * <p>Everything else the parser emits is already meaning-shaped (ranges,
 * anchors, tags) and passes through by reference: the transform reuses
 * every subtree that did not change, so annotations set later in the
 * pipeline ({@code FixedTags}) and the parser's own Tag objects are the
 * instances the rest of the compile sees.
 *
 * <p>The pass is also the IR entry's gate-keeper shape for producers
 * that bypass the stock parser ({@code Tnfa.compile(ParseResult, ...)}):
 * it walks the whole tree and expands every symbolic node it finds, so
 * no {@code Ast.Dot} can reach the TNFA builder regardless of who built
 * the tree.
 *
 * <p>Iterative throughout — one explicit frame stack, results on a value
 * stack — because AST depth scales with pattern nesting: no stage of the
 * front-end may consume JVM stack proportional to it (the parser and the
 * fixed-tags pass are iterative for the same reason).
 */
public final class Resolve {

    // ---- the stock dot classes (moved from the parser; the arrays are
    //      the historical constants, so stock-lane output is unchanged) ----

    /** Dot over the {@code \n}-only terminator set ({@code Terminators.NL_ONLY}). */
    static final CharClass DOT = new CharClass(new int[]{0, '\n' - 1, '\n' + 1, 0x10FFFF}, false);

    /** Dot under the full java.util.regex terminator set: excludes
     *  {@code \n}, {@code \r}, U+0085, U+2028 and U+2029 ({@code \r\n} needs
     *  no extra case — both halves are excluded as units). */
    static final CharClass DOT_JUR =
        new CharClass(new int[]{0, '\n' - 1, 0x0B, '\r' - 1, '\r' + 1, 0x84, 0x86, 0x2027, 0x202A, 0x10FFFF}, false);

    /** Dot under the scoped {@code (?s)} state: every codepoint. */
    static final CharClass DOTALL = new CharClass(new int[]{0, 0x10FFFF}, false);

    private Resolve() {
    }

    /**
     * Resolve {@code root} in place-by-rebuild (unchanged subtrees are
     * reused, changed containers re-assembled) and place the policy marks.
     *
     * @param root the parse output (may contain symbolic {@link Ast.Dot}
     *            leaves; every other node passes through)
     * @param terminators the compile's terminator set (the dot complement)
     * @param emptyIterationSpans the EMPTY_ITERATION_SPANS policy: set =
     *            the maximal-iteration protocol — no family-6 marks, no
     *            zero-width-exit splits; unset = java.util.regex's reading
     */
    public static Ast apply(Ast root, Terminators terminators, boolean emptyIterationSpans) {
        if (root == null) {
            throw new NullPointerException("root");
        }
        if (terminators == null) {
            throw new NullPointerException("terminators");
        }
        ArrayDeque<Object> stack = new ArrayDeque<>();
        ArrayDeque<Ast> values = new ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty()) {
            Object top = stack.pop();
            if (top instanceof Frame) {
                values.push(((Frame) top).resume(values, terminators, emptyIterationSpans));
                continue;
            }
            Ast e = (Ast) top;
            if (e instanceof Ast.Dot) {
                values.push(dotClass(((Ast.Dot) e).dotall, terminators));
            } else if (e instanceof Ast.Concat) {
                stack.push(new Frame(e, ((Ast.Concat) e).children));
                pushChildren(stack, ((Ast.Concat) e).children);
            } else if (e instanceof Ast.Alt) {
                stack.push(new Frame(e, ((Ast.Alt) e).children));
                pushChildren(stack, ((Ast.Alt) e).children);
            } else if (e instanceof Ast.Repeat) {
                // A one-child frame in Java-8 shape: singletonList keeps the
                // resume path uniform with the containers.
                stack.push(new Frame(e, Collections.singletonList(((Ast.Repeat) e).body)));
                stack.push(((Ast.Repeat) e).body);
            } else {
                values.push(e); // leaf: anchors, classes, symbols, tags, Empty
            }
        }
        return values.pop();
    }

    /** Push the children in reverse so they resolve in source order. */
    private static void pushChildren(Deque<Object> stack, List<Ast> children) {
        for (int i = children.size() - 1; i >= 0; i--) {
            stack.push(children.get(i));
        }
    }

    /**
     * The resolved dot: everything under dotall; the stock sets keep their
     * historical constants (bit-identical artifacts); a custom set builds
     * its complement from the value.
     */
    private static CharClass dotClass(boolean dotall, Terminators terminators) {
        if (dotall) {
            return DOTALL;
        }
        if (terminators.equals(Terminators.NL_ONLY)) {
            return DOT;
        }
        return terminators.equals(Terminators.JDK) ? DOT_JUR : new CharClass(terminators.dotRanges(), false);
    }

    /** One container in progress: the original node and its children.
     *  When the last child lands, the frame re-assembles (reusing the
     *  original node when nothing changed) and, for a Repeat, places the
     *  policy marks — the resolved body is what the shape tests must read.
     *  Children resolve in source order (pushed in reverse below), so the
     *  frame's own pop is the resume point. */
    private static final class Frame {
        final Ast node;
        final List<Ast> children;

        Frame(Ast node, List<Ast> children) {
            this.node = node;
            this.children = children;
        }

        Ast resume(ArrayDeque<Ast> values, Terminators terminators, boolean emptyIterationSpans) {
            int n = children.size();
            Ast[] resolved = new Ast[n];
            boolean changed = false;
            for (int i = n - 1; i >= 0; i--) {
                resolved[i] = values.pop();
                changed |= resolved[i] != children.get(i);
            }
            Ast out = node;
            if (changed) {
                List<Ast> ch = new ArrayList<>(n);
                Collections.addAll(ch, resolved);
                out = node instanceof Ast.Concat ? new Ast.Concat(ch)
                    : node instanceof Ast.Alt ? new Ast.Alt(ch) : new Ast.Repeat(resolved[0], ((Ast.Repeat) node).min,
                        ((Ast.Repeat) node).max, ((Ast.Repeat) node).greedy);
                if (out instanceof Ast.Repeat) {
                    // carry the parser's structural fact onto the rebuild
                    // (a Repeat is rebuilt only when its body changed — a
                    // body with a dot is a consumer, so the marks below
                    // stay off either way, but the fact must not rot).
                    ((Ast.Repeat) out).quantifiedCapture = ((Ast.Repeat) node).quantifiedCapture;
                }
            }
            if (out instanceof Ast.Repeat) {
                markRepeat((Ast.Repeat) out, emptyIterationSpans);
            }
            return out;
        }
    }

    // ====== the family-6 / zero-width-exit marks (moved from the parser) ======

    /**
     * Place the protocol marks on one resolved {@link Ast.Repeat}: the
     * zero-width-exit split mark (finding 2 + its unbounded mirror) and,
     * when the quantifier directly rides a CAPTURE (the parser's
     * {@code quantifiedCapture} structural fact), the family-6 marks.
     * Both are no-ops on the maximal-iteration reading (the axis set —
     * those parses and their NFA/TDFA artifacts stay bit-identical).
     */
    private static void markRepeat(Ast.Repeat r, boolean emptyIterationSpans) {
        // Finding 2 (campaign 2026-10-16) + its unbounded mirror: JUR
        // reading only — mark the shapes whose body can match empty through
        // a choice point (java's Prolog+Loop compile; the Loop guard exits
        // after any zero-width iteration, carrying the exiting iteration's
        // spans at the branch's own priority). Bounded {n,m} max >= 2 and
        // {n,} min >= 2 desugar structural copies that need the
        // zero-width-exit split; the unbounded {0,}/{1,} plus/star bodies
        // need it against the loop hub (the ε re-entry must exit, not die
        // at the closure dedup). {0,1} builds no iteration and stays
        // unmarked.
        boolean loopCopies = r.max == Integer.MAX_VALUE || r.max >= 2;
        r.zeroWidthExit = loopCopies && !emptyIterationSpans && nullableBody(r.body) && !dissolvableBody(r.body);
        if (!r.quantifiedCapture) {
            return;
        }
        markFamily6Groups(r, emptyIterationSpans);
    }

    /**
     * Family 6 (the EMPTY_ITERATION_SPANS axis, unset = the
     * java.util.regex reading) — both sub-families, marked on the repeat
     * the parser recorded as directly quantifying a CAPTURING group's
     * {@code )} product (not a transparent {@code (?:...)} wrapper —
     * {@code (?:(\b))*} keeps the span: the capture is not the quantified
     * atom).
     *
     * <p><b>A — dissolve</b>: a greedy min-0 LOOP quantifier
     * ({@code cmax >= 2} or unbounded — {@code ?}/{@code {0,1}} keep the
     * span, and {@code min >= 1} keeps the forced iteration; both
     * JDK-verified) directly on a capture whose body is all-zero-width
     * with no choice points: java's {@code GroupCurly} rolls the
     * zero-width iteration back to the pre-curly bounds, so the pair
     * reports NIL at every accept (the determinizer's φ override). The
     * capture inside a dissolved pair ({@code ((\b))*} — inner keeps its
     * span) fails the body test and stays unmarked.
     *
     * <p><b>B — surface</b>: an UNBOUNDED greedy quantifier
     * ({@code *}, {@code +}, {@code {n,}} — the bounded desugars are
     * structural and agree with the JDK already) directly on a capture
     * whose body can match empty through a choice point (nullable and
     * NOT dissolvable): java's {@code Prolog}+{@code Loop} reports the
     * final zero-width iteration, which the determinizer's closure
     * subsumption cuts — the accepting kernels where the cut fires
     * surface the pair's writes as a φ SET_POS at the accept position.
     * Lazy quantifiers never mark (java's lazy loops prefer exiting —
     * both engines agree).
     *
     * <p>No-op on the axis-set reading
     * ({@code emptyIterationSpans}), so those parses — and their
     * NFA/TDFA artifacts — stay bit-identical.
     */
    private static void markFamily6Groups(Ast.Repeat r, boolean emptyIterationSpans) {
        if (emptyIterationSpans || !r.greedy || !(r.body instanceof Ast.Concat)) {
            return;
        }
        List<Ast> ch = ((Ast.Concat) r.body).children;
        if (ch.size() != 3 || !(ch.get(0) instanceof Ast.Tag) || !(ch.get(2) instanceof Ast.Tag)) {
            return;
        }
        Ast body = ch.get(1);
        boolean aShape = r.min == 0 && r.max >= 2 && dissolvableBody(body);
        boolean bShape = r.max == Integer.MAX_VALUE && nullableBody(body) && !dissolvableBody(body);
        if (!aShape && !bShape) {
            return;
        }
        ((Ast.Tag) ch.get(0)).dissolved = aShape;
        ((Ast.Tag) ch.get(0)).surfaceFinal = bShape;
        ((Ast.Tag) ch.get(2)).dissolved = aShape;
        ((Ast.Tag) ch.get(2)).surfaceFinal = bShape;
        if (bShape) {
            // Containment: every capture INSIDE the B-group's body
            // participates in the surfaced final zero-width iteration
            // (((a*))* on "aa": outer AND inner report 2..2 — the whole
            // traversal is zero-width, so the dying re-entry's writes
            // cover the inner pair too). JDK-verified; the determinizer
            // surfaces any marked tag the cut path mentions.
            markSubtreeSurface(body);
        }
    }

    /** Set {@link Ast.Tag#surfaceFinal} on every Tag under {@code e} (see the B-mark). */
    private static void markSubtreeSurface(Ast e) {
        Deque<Ast> work = new ArrayDeque<>();
        work.push(e);
        while (!work.isEmpty()) {
            Ast a = work.pop();
            if (a instanceof Ast.Tag) {
                ((Ast.Tag) a).surfaceFinal = true;
            } else if (a instanceof Ast.Concat) {
                work.addAll(((Ast.Concat) a).children);
            } else if (a instanceof Ast.Alt) {
                work.addAll(((Ast.Alt) a).children);
            } else if (a instanceof Ast.Repeat) {
                work.push(((Ast.Repeat) a).body);
            }
        }
    }

    /**
     * Whether {@code e} can ONLY match zero-width WITHOUT choice points:
     * assertion chains, {@code ()}, and nested groups of those (the
     * {@code study()}-deterministic zero-width bodies java compiles as
     * {@code GroupCurly}). Consuming atoms, alternation and any repeat
     * fail — the latter two make the body non-deterministic (sub-family
     * B even when empty-capable). Iterative worklist: nesting depth must
     * cost heap, never JVM stack (the parser itself is iterative for the
     * same reason).
     */
    private static boolean dissolvableBody(Ast e) {
        Deque<Ast> work = new ArrayDeque<>();
        work.push(e);
        while (!work.isEmpty()) {
            Ast a = work.pop();
            if (a instanceof Ast.Concat) {
                work.addAll(((Ast.Concat) a).children);
            } else if (!(a instanceof Ast.Tag || a instanceof Ast.Empty || a instanceof Ast.StartAnchor
                || a instanceof Ast.EndAnchor || a instanceof Ast.WordBoundary || a instanceof Ast.NoWordBoundary)) {
                return false; // Symbol/CharClass consume; Alt/Repeat choose
            }
        }
        return true;
    }

    /**
     * Whether {@code e} can match the empty string (nullable) — the
     * sub-family B body test (with {@link #dissolvableBody} false:
     * emptiness reachable only THROUGH a choice point). The syntactic
     * twin of the Builder's {@code isNullable}: Concat needs every child,
     * Alt any branch, a Repeat with {@code min == 0} skips, and one with
     * {@code min >= 1} inherits the body's nullability. Iterative
     * post-order AND/OR evaluation — one frame per container, leaf
     * verdicts inline — so nesting depth costs heap, never JVM stack
     * (the parser itself is iterative for the same reason).
     */
    private static boolean nullableBody(Ast e) {
        Deque<NFrame> stack = new ArrayDeque<>();
        NFrame root = new NFrame(e);
        stack.push(root);
        while (!stack.isEmpty()) {
            NFrame f = stack.peek();
            if (f.children == null) {
                Ast n = f.node;
                if (n instanceof Ast.Concat) {
                    f.children = ((Ast.Concat) n).children;
                } else if (n instanceof Ast.Alt) {
                    f.children = ((Ast.Alt) n).children;
                } else if (n instanceof Ast.Repeat) {
                    if (((Ast.Repeat) n).min == 0) {
                        f.finish(true); // can skip the body entirely
                    } else {
                        f.children = Collections.singletonList(((Ast.Repeat) n).body);
                    }
                } else if (n instanceof Ast.Tag || n instanceof Ast.Empty || n instanceof Ast.StartAnchor
                    || n instanceof Ast.EndAnchor || n instanceof Ast.WordBoundary || n instanceof Ast.NoWordBoundary) {
                    f.finish(true); // zero-width
                } else {
                    f.finish(false); // Symbol/CharClass consume
                }
            }
            if (f.children != null && f.idx < f.children.size()) {
                stack.push(new NFrame(f.children.get(f.idx++)));
                continue;
            }
            if (f.children != null && !f.leaf) {
                // container fold: Concat/Repeat{min>0} need every child, Alt any
                f.result = f.node instanceof Ast.Alt ? f.trues > 0 : f.falses == 0;
            }
            stack.pop();
            if (!stack.isEmpty()) {
                stack.peek().tally(f.result);
            }
        }
        return root.result;
    }

    /** One nullableBody evaluation frame (see there). */
    private static final class NFrame {
        final Ast node;
        List<Ast> children;
        int idx;
        int trues;
        int falses;
        boolean result;
        boolean leaf;

        NFrame(Ast node) {
            this.node = node;
        }

        void finish(boolean verdict) {
            children = Collections.emptyList();
            leaf = true;
            result = verdict;
        }

        void tally(boolean childResult) {
            if (childResult) {
                trues++;
            } else {
                falses++;
            }
        }
    }
}
