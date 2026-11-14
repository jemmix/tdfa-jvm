package io.github.jemmix.tdfa.core.semantic;

import io.github.jemmix.tdfa.core.ast.Ast;
import io.github.jemmix.tdfa.core.ast.CharClass;
import io.github.jemmix.tdfa.core.budget.BudgetWeights;
import io.github.jemmix.tdfa.core.budget.WorkMeter;
import io.github.jemmix.tdfa.core.dialect.ClassTable;
import io.github.jemmix.tdfa.core.dialect.Terminators;
import io.github.jemmix.tdfa.core.unicode.CaseFoldTable;
import io.github.jemmix.tdfa.core.unicode.ShorthandTables;
import io.github.jemmix.tdfa.core.unicode.UnicodeDataProvider;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/**
 * The meaning-resolution pass: the one place a compile's interpretation
 * policy is applied to the parse output's still-symbolic parts. The parser
 * produces a syntax-faithful tree — the bare dot stays a symbolic
 * {@link Ast.Dot} carrying its scoped {@code (?s)} state, literals under an
 * active {@code (?i)} stay symbolic {@link Ast.FoldLiteral} nodes carrying
 * the scoped fold-universe selection, the shorthand atoms stay symbolic
 * {@link Ast.Shorthand} nodes carrying their scoped state, and every
 * quantifier carries the structural fact of what it directly quantifies
 * ({@link Ast.Repeat#quantifiedCapture}) — and this pass resolves them
 * against the policy's own vocabulary ({@code Terminators} and {@code
 * ClassTable} from the dialect package, the SHORTHAND_FOLD and
 * EMPTY_ITERATION_SPANS policy bits, the provider's tables), so no
 * parse-side axis mirrors a meaning the pipeline already carries (see
 * {@code docs/engine-compat-surface.md}):
 *
 * <ul>
 * <li>the dot expands to its {@link CharClass}: everything under
 *     dotall, the compile's terminator-set complement otherwise (the
 *     stock sets keep the precomputed constants, so stock-lane output
 *     is bit-identical to the pre-symbolic parser's);</li>
 * <li>the foldable literal expands to its orbit under the atom's fold
 *     universe (or demotes to the plain literal when the universe has no
 *     counterpart for the codepoint — the readings the parse-time
 *     expansion produced, moved whole);</li>
 * <li>the shorthand atom materializes against the compile's {@code \s}
 *     table, the provider's Unicode twins, and the SHORTHAND_FOLD policy
 *     (the word orbit under an active {@code (?i)} — folding lane only,
 *     the positive set first for the complement forms);</li>
 * <li>the family-6 marks ({@code Tag.dissolved}/{@code Tag.surfaceFinal})
 *     and {@code Repeat.zeroWidthExit} are placed from the
 *     EMPTY_ITERATION_SPANS policy — on the axis-set reading (the RE2
 *     lineage) nothing is marked and those artifacts are bit-identical
 *     to an unmarked parse.</li>
 * </ul>
 *
 * <p>Everything else the parser emits is already meaning-shaped (ranges,
 * anchors, tags, the property classes — a provider table lookup is a
 * compile input, not a policy application) and passes through by
 * reference: the transform reuses every subtree that did not change, so
 * annotations set later in the pipeline ({@code FixedTags}) and the
 * parser's own Tag objects are the instances the rest of the compile
 * sees.
 *
 * <p>The pass is also the IR entry's gate-keeper shape for producers
 * that bypass the stock parser ({@code Tnfa.compile(ParseResult, ...)}):
 * it walks the whole tree and expands every symbolic node it finds, so
 * no {@code Ast.Dot}/{@code FoldLiteral}/{@code Shorthand} can reach the
 * TNFA builder regardless of who built the tree.
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

    // ---- the stock shorthand classes (the ASCII tables the symbolic
    //      shorthand expands to; the arrays are the parser's historical
    //      constants, so stock-lane output is unchanged) ----

    private static final int[] R_DIGIT = {'0', '9'};
    private static final int[] R_WORD = {'a', 'z', 'A', 'Z', '0', '9', '_', '_'};

    private static final CharClass DIGIT = new CharClass(R_DIGIT, false);
    private static final CharClass NOT_DIGIT = new CharClass(R_DIGIT, true);
    private static final CharClass WORD = new CharClass(R_WORD, false);
    private static final CharClass NOT_WORD = new CharClass(R_WORD, true);

    // Unicode IsWhite_Space property (the unicode-?\s table).
    private static final int[] R_UNICODE_SPACE = {'\t', '\r', // U+0009-U+000D (HT, LF, VT, FF, CR)
        0x1C, 0x1F, // FS, GS, RS, US
        ' ', ' ', // Space
        0x85, 0x85, // NEL
        0xA0, 0xA0, // NBSP
        0x1680, 0x1680, // Ogham Space
        0x2000, 0x200A, // En–Hair Space
        0x2028, 0x2029, // LS, PS
        0x202F, 0x202F, // Narrow NBSP
        0x205F, 0x205F, // Medium Math Space
        0x3000, 0x3000 // Ideographic Space
    };

    private final Terminators terminators;
    private final ClassTable spaceTable;
    private final boolean shorthandFold;
    private final boolean emptyIterationSpans;
    private final UnicodeDataProvider provider;
    private final WorkMeter meter;
    // this pass's shorthand caches (the parser keeps its own for the \b
    // runtime fact and the in-class twins until the class rung lands)
    private int[] cachedUnicodeWord;
    private int[] cachedUnicodeDigit;

    private Resolve(Terminators terminators, ClassTable spaceTable, boolean shorthandFold, boolean emptyIterationSpans,
        UnicodeDataProvider provider, WorkMeter meter) {
        this.terminators = terminators;
        this.spaceTable = spaceTable;
        this.shorthandFold = shorthandFold;
        this.emptyIterationSpans = emptyIterationSpans;
        this.provider = provider;
        this.meter = meter;
    }

    /**
     * Resolve {@code root} in place-by-rebuild (unchanged subtrees are
     * reused, changed containers re-assembled) and place the policy marks.
     *
     * @param root the parse output (may contain symbolic {@link Ast.Dot},
     *            {@link Ast.FoldLiteral} and {@link Ast.Shorthand} leaves;
     *            every other node passes through)
     * @param terminators the compile's terminator set (the dot complement)
     * @param spaceTable the compile's ASCII {@code \s} table
     * @param shorthandFold the SHORTHAND_FOLD policy: fold the word
     *            shorthands under an active {@code (?i)}
     * @param emptyIterationSpans the EMPTY_ITERATION_SPANS policy: set =
     *            the maximal-iteration protocol — no family-6 marks, no
     *            zero-width-exit splits; unset = java.util.regex's reading
     * @param provider the compile's Unicode source (the {@code \w}/{@code \d}
     *            tables, the fold universe when it supplies one)
     * @param meter the compile's front-end work meter (the orbit scans tick
     *            it, exactly as the parse-time expansion they replaced)
     */
    public static Ast apply(Ast root, Terminators terminators, ClassTable spaceTable, boolean shorthandFold,
        boolean emptyIterationSpans, UnicodeDataProvider provider, WorkMeter meter) {
        if (root == null) {
            throw new NullPointerException("root");
        }
        if (terminators == null) {
            throw new NullPointerException("terminators");
        }
        if (spaceTable == null) {
            throw new NullPointerException("spaceTable");
        }
        if (meter == null) {
            throw new NullPointerException("meter");
        }
        // provider may be null — the same tolerance the parse-side fold seam
        // always had: null serves the built-in fold table, and a pattern
        // whose expansion needs the property tables fails on first consult
        // (the legacy entries' contract for a null provider).
        return new Resolve(terminators, spaceTable, shorthandFold, emptyIterationSpans, provider, meter).run(root);
    }

    private Ast run(Ast root) {
        ArrayDeque<Object> stack = new ArrayDeque<>();
        ArrayDeque<Ast> values = new ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty()) {
            Object top = stack.pop();
            if (top instanceof Frame) {
                values.push(((Frame) top).resume(values, this));
                continue;
            }
            Ast e = (Ast) top;
            if (e instanceof Ast.Dot) {
                values.push(dotClass(((Ast.Dot) e).dotall));
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
                values.push(expandLeaf(e)); // symbolic or plain leaf
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
    private CharClass dotClass(boolean dotall) {
        if (dotall) {
            return DOTALL;
        }
        if (terminators.equals(Terminators.NL_ONLY)) {
            return DOT;
        }
        return terminators.equals(Terminators.JDK) ? DOT_JUR : new CharClass(terminators.dotRanges(), false);
    }

    /**
     * Expand one leaf: the symbolic atoms the parser defers (
     * {@link Ast.FoldLiteral}, {@link Ast.Shorthand}) materialize here
     * against the compile's policy; every other leaf is already
     * meaning-shaped and passes through by reference.
     */
    private Ast expandLeaf(Ast e) {
        if (e instanceof Ast.FoldLiteral) {
            Ast.FoldLiteral f = (Ast.FoldLiteral) e;
            // re2j folds FULL Unicode simple folding under plain (?i) — no
            // (?u) needed (verified against re2j 1.8: (?i)s matches ſ,
            // (?i)k matches K); the ASCII universe folds the 26 letter
            // pairs and nothing else (the java.util.regex bare-CI reading).
            int[] ranges = CaseFoldTable.universeFold(f.cp, f.uni, provider);
            return ranges != null ? new CharClass(ranges, false) : literalAtom(f.cp);
        }
        if (e instanceof Ast.Shorthand) {
            return shorthandClass((Ast.Shorthand) e);
        }
        return e;
    }

    /**
     * The shorthand atom's expansion — the readings the parse once applied
     * inline, keyed on the node's scoped state and the compile's policy:
     * under {@code (?i)} the word shorthands fold on the folding lane only
     * (re2j: {@code (?i)\w} matches ſ via the s-orbit of a-z);
     * java.util.regex never folds a shorthand (the SHORTHAND_FOLD policy).
     * Digits/spaces have no fold orbits and stay as-is in both readings.
     * {@code \W} folds the POSITIVE set first, then complements.
     */
    private Ast shorthandClass(Ast.Shorthand s) {
        if (shorthandFold && s.ci && s.kind == 'w') {
            int[] positive = s.unicode ? foldExpandRanges(unicodeWordRanges(), s.uni) : foldExpandRanges(R_WORD, s.uni);
            return new CharClass(s.negated ? complementRanges(positive) : positive, false);
        }
        if (!s.unicode) {
            switch (s.kind) {
                case 'd' :
                    return s.negated ? NOT_DIGIT : DIGIT;
                case 'w' :
                    return s.negated ? NOT_WORD : WORD;
                case 's' :
                    return new CharClass(spaceTable.ranges(), s.negated);
                default :
                    throw new IllegalStateException("not a shorthand: \\" + s.kind);
            }
        }
        int[] ranges;
        if (s.kind == 'w') {
            ranges = unicodeWordRanges();
        } else if (s.kind == 'd') {
            ranges = unicodeDigitRanges();
        } else {
            ranges = R_UNICODE_SPACE;
        }
        return new CharClass(ranges, s.negated);
    }

    /** Atom for one literal codepoint: BMP stays {@link Ast.Symbol}; a
     *  supplementary codepoint becomes a single-codepoint {@link CharClass}
     *  (the engine's alphabet is codepoints — one transition consumes
     *  exactly one). The resolver twin of the parser's literalAtom. */
    private static Ast literalAtom(int cp) {
        return cp <= 0xFFFF ? new Ast.Symbol((char) cp) : new CharClass(new int[]{cp, cp}, false);
    }

    /** This pass's Unicode {@code \w}/{@code \d} tables (see
     *  {@link ShorthandTables} — the single copy of the materialization). */
    private int[] unicodeWordRanges() {
        if (cachedUnicodeWord == null) {
            cachedUnicodeWord = ShorthandTables.wordRanges(provider, R_WORD);
        }
        return cachedUnicodeWord;
    }

    private int[] unicodeDigitRanges() {
        if (cachedUnicodeDigit == null) {
            cachedUnicodeDigit = ShorthandTables.digitRanges(provider, R_DIGIT);
        }
        return cachedUnicodeDigit;
    }

    /** Union of {@code ranges} with every member's fold orbit under the
     *  atom's fold universe — the metered orbit closure (O(universe) per
     *  wide class under {@code (?i)}; the same ticks the parse-time
     *  expansion it replaced spent). Output sorted by lo with overlapping
     *  ranges merged. */
    private int[] foldExpandRanges(int[] arr, boolean uni) {
        List<int[]> ivs = new ArrayList<>(arr.length);
        for (int i = 0; i < arr.length; i += 2) {
            int lo = arr[i], hi = arr[i + 1];
            ivs.add(new int[]{lo, hi});
            for (int cp = lo; cp <= hi; cp++) {
                meter.tick(BudgetWeights.FOLD_SCAN_CODEPOINT_TICKS);
                int[] fr = CaseFoldTable.universeFold(cp, uni, provider);
                if (fr != null) {
                    for (int v : fr) {
                        ivs.add(new int[]{v, v});
                    }
                }
            }
        }
        return mergeIntervals(ivs);
    }

    /** Sort by lo, merge overlapping/adjacent intervals, flatten. */
    private static int[] mergeIntervals(List<int[]> ivs) {
        ivs.sort((a, b) -> Integer.compare(a[0], b[0]));
        List<Integer> merged = new ArrayList<>(ivs.size() * 2);
        int lo = ivs.get(0)[0], hi = ivs.get(0)[1];
        for (int[] iv : ivs.subList(1, ivs.size())) {
            if (iv[0] <= hi + 1) {
                hi = Math.max(hi, iv[1]);
            } else {
                merged.add(lo);
                merged.add(hi);
                lo = iv[0];
                hi = iv[1];
            }
        }
        merged.add(lo);
        merged.add(hi);
        int[] out = new int[merged.size()];
        for (int i = 0; i < merged.size(); i++) {
            out[i] = merged.get(i);
        }
        return out;
    }

    /** Compute the complement of {@code ranges} within [0, 0x10FFFF]. */
    private static int[] complementRanges(int[] ranges) {
        List<Integer> out = new ArrayList<>();
        int prev = 0;
        for (int i = 0; i < ranges.length; i += 2) {
            int lo = ranges[i], hi = ranges[i + 1];
            if (lo > prev) {
                out.add(prev);
                out.add(lo - 1);
            }
            prev = Math.max(prev, hi + 1);
            if (prev > 0x10FFFF) {
                break;
            }
        }
        if (prev <= 0x10FFFF) {
            out.add(prev);
            out.add(0x10FFFF);
        }
        int[] arr = new int[out.size()];
        for (int i = 0; i < out.size(); i++) {
            arr[i] = out.get(i);
        }
        return arr;
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

        Ast resume(ArrayDeque<Ast> values, Resolve ctx) {
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
                ctx.markRepeat((Ast.Repeat) out);
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
    private void markRepeat(Ast.Repeat r) {
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
        markFamily6Groups(r);
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
    private void markFamily6Groups(Ast.Repeat r) {
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
