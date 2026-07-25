package io.github.jemmix.tdfa.tnfa;

import io.github.jemmix.tdfa.ast.Ast;
import io.github.jemmix.tdfa.ast.CharClass;
import io.github.jemmix.tdfa.parser.Parser;

import java.util.ArrayList;
import java.util.List;

/**
 * Thompson-style NFA with tagged epsilon transitions.
 *
 * Transitions come in two flavors:
 *  - symbol transitions: (from, to, CharClass)
 *  - epsilon transitions: (from, to, priority, tag) where tag = 0 (none), +t (set tag t to current pos), -t (clear tag t)
 *
 * Lower priority = preferred (leftmost-greedy ordering).
 *
 * Anchors (^ $) become epsilon transitions gated by a predicate on input position.
 * For simplicity, predicates are stored alongside epsilon transitions as a kind enum.
 *
 * Based on paper Algorithm 2 (TNFA construction), simplified: ntags on alternation
 * paths is included; repetition is handled by structural recursion.
 */
public final class Tnfa {
    public static final int NO_TAG = 0;

    public final int stateCount;
    public final int[] epsFrom, epsTo, epsPri, epsTag;
    public final int[] symFrom, symTo;
    public final CharClass[] symClass;
    public final int start, accept;
    public final int tagCount;
    public final int groupCount;
    public final boolean hasStartAnchor;
    public final boolean hasEndAnchor;

    public Tnfa(int stateCount,
                int[] epsFrom, int[] epsTo, int[] epsPri, int[] epsTag,
                int[] symFrom, int[] symTo, CharClass[] symClass,
                int start, int accept, int tagCount, int groupCount,
                boolean hasStartAnchor, boolean hasEndAnchor) {
        this.stateCount = stateCount;
        this.epsFrom = epsFrom; this.epsTo = epsTo; this.epsPri = epsPri; this.epsTag = epsTag;
        this.symFrom = symFrom; this.symTo = symTo; this.symClass = symClass;
        this.start = start; this.accept = accept;
        this.tagCount = tagCount;
        this.groupCount = groupCount;
        this.hasStartAnchor = hasStartAnchor;
        this.hasEndAnchor = hasEndAnchor;
    }

    // ====== Builder / construction ======

    public static Tnfa compile(String pattern) {
        Parser parser = Parser.capture(pattern);
        Ast ast = parser.lastAst();
        Builder b = new Builder();
        int accept = b.fresh();
        int start = b.build(ast, accept);
        boolean[] anchors = detectAnchors(ast);
        return b.build(start, accept, parser.tagCount(), parser.groupCount(), anchors[0], anchors[1]);
    }

    private static boolean[] detectAnchors(Ast ast) {
        boolean[] result = new boolean[2]; // [hasStart, hasEnd]
        detectAnchors(ast, result);
        return result;
    }

    private static void detectAnchors(Ast ast, boolean[] result) {
        if (ast instanceof Ast.StartAnchor) result[0] = true;
        else if (ast instanceof Ast.EndAnchor) result[1] = true;
        else if (ast instanceof Ast.Concat) {
            for (Ast c : ((Ast.Concat) ast).children) detectAnchors(c, result);
        } else if (ast instanceof Ast.Alt) {
            for (Ast c : ((Ast.Alt) ast).children) detectAnchors(c, result);
        } else if (ast instanceof Ast.Repeat) {
            detectAnchors(((Ast.Repeat) ast).body, result);
        }
    }

    private static final class Builder {
        final List<int[]> eps = new ArrayList<>();        // [from, to, pri, tag]
        final List<int[]> syms = new ArrayList<>();       // [from, to]
        final List<CharClass> symClasses = new ArrayList<>();
        final List<int[]> anchors = new ArrayList<>();    // [from, to, pri, kind]  kind: 1=^ 2=$
        int counter = 0;

        int fresh() { return counter++; }

        void eps(int from, int to, int pri) { eps.add(new int[]{from, to, pri, NO_TAG}); }
        void taggedEps(int from, int to, int pri, int tag) { eps.add(new int[]{from, to, pri, tag}); }
        void anchor(int from, int to, int pri, int kind) { anchors.add(new int[]{from, to, pri, kind}); }
        void sym(int from, int to, CharClass cc) {
            syms.add(new int[]{from, to});
            symClasses.add(cc);
        }

        /** Returns entry state of sub-NFA that flows into `entryTo`. */
        int build(Ast e, int entryTo) {
            if (e instanceof Ast.Empty) {
                int s = fresh();
                eps(s, entryTo, 1);
                return s;
            }
            if (e instanceof Ast.Symbol) {
                int s = fresh();
                sym(s, entryTo, new CharClass(new int[]{((Ast.Symbol) e).c, ((Ast.Symbol) e).c}, false));
                return s;
            }
            if (e instanceof CharClass) {
                int s = fresh();
                sym(s, entryTo, (CharClass) e);
                return s;
            }
            if (e instanceof Ast.Tag) {
                int s = fresh();
                taggedEps(s, entryTo, 1, ((Ast.Tag) e).tag);
                return s;
            }
            if (e instanceof Ast.StartAnchor) {
                int s = fresh();
                anchor(s, entryTo, 1, 1);
                return s;
            }
            if (e instanceof Ast.EndAnchor) {
                int s = fresh();
                anchor(s, entryTo, 1, 2);
                return s;
            }
            if (e instanceof Ast.Concat) {
                int cur = entryTo;
                // build right-to-left
                List<Ast> ch = ((Ast.Concat) e).children;
                for (int i = ch.size() - 1; i >= 0; i--) cur = build(ch.get(i), cur);
                return cur;
            }
            if (e instanceof Ast.Alt) {
                // Build all alternatives flowing into entryTo with descending priority.
                int newStart = fresh();
                List<Ast> ch = ((Ast.Alt) e).children;
                for (int i = 0; i < ch.size(); i++) {
                    int altStart = build(ch.get(i), entryTo);
                    eps(newStart, altStart, i + 1);
                }
                return newStart;
            }
            if (e instanceof Ast.Repeat) {
                return buildRepeat((Ast.Repeat) e, entryTo);
            }
            throw new IllegalStateException("unknown ast: " + e);
        }

        private int buildRepeat(Ast.Repeat r, int entryTo) {
            int min = r.min, max = r.max;
            Ast body = r.body;
            if (min == 0 && max == 1) {
                // e? : newStart -(pri1)-> body -> entryTo ; newStart -(pri2)-> entryTo
                int s = fresh();
                int bodyStart = build(body, entryTo);
                eps(s, bodyStart, 1);
                eps(s, entryTo, 2);
                return s;
            }
            if (min == 0 && max == Integer.MAX_VALUE) {
                // e* : loop
                int s = fresh();
                int loopBack = fresh();
                int bodyStart = build(body, loopBack);
                eps(s, bodyStart, 1);                  // prefer to enter body
                eps(s, entryTo, 2);                    // or skip
                eps(loopBack, bodyStart, 1);           // loop back: prefer to repeat
                eps(loopBack, entryTo, 2);             // or exit
                return s;
            }
            if (min == 1 && max == Integer.MAX_VALUE) {
                // e+ : body followed by e*
                int loopBack = fresh();
                int bodyStart = build(body, loopBack);
                eps(loopBack, bodyStart, 1);
                eps(loopBack, entryTo, 2);
                return bodyStart;
            }
            // Bounded repetitions {n}, {n,}, {n,m} — desugar via concatenation + optional tail.
            // (We desugar rather than implementing the paper's bounded-rep construction literally;
            //  tags are duplicated, which is acceptable for our subset.)
            List<Ast> mandatory = new ArrayList<>();
            for (int i = 0; i < min; i++) mandatory.add(body);
            Ast mandatoryAst = mandatory.isEmpty() ? new Ast.Empty() :
                    (mandatory.size() == 1 ? mandatory.get(0) : new Ast.Concat(mandatory));
            Ast result = mandatoryAst;
            if (max == Integer.MAX_VALUE) {
                // {n,} = mandatory followed by body*
                result = new Ast.Concat(List.of(mandatoryAst, new Ast.Repeat(body, 0, Integer.MAX_VALUE, true)));
            } else if (max > min) {
                // {n,m} = mandatory followed by m-n optional copies
                List<Ast> tail = new ArrayList<>();
                for (int i = min; i < max; i++) tail.add(new Ast.Repeat(body, 0, 1, true));
                result = new Ast.Concat(List.of(mandatoryAst, new Ast.Concat(tail)));
            }
            // re-enter the builder with the desugared form, but DO NOT re-process via parser;
            // build it directly into entryTo.
            return build(result, entryTo);
        }

        Tnfa build(int start, int accept, int tagCount, int groupCount,
                   boolean hasStartAnchor, boolean hasEndAnchor) {
            // Merge eps + anchors into a single eps array (anchors carry tag values 100/200/.../special)
            int n = eps.size() + anchors.size();
            int[] eFrom = new int[n], eTo = new int[n], ePri = new int[n], eTag = new int[n];
            int i = 0;
            for (int[] e : eps) { eFrom[i] = e[0]; eTo[i] = e[1]; ePri[i] = e[2]; eTag[i] = e[3]; i++; }
            // Anchors share the eps arrays but their tag values are sentinel-encoded.
            // Anchor kinds 1=^ 2=$ are stored as tag = 100 (start) or 200 (end). Real tags are
            // strictly positive integers < 100 by parser construction.
            for (int[] a : anchors) {
                eFrom[i] = a[0]; eTo[i] = a[1]; ePri[i] = a[2];
                eTag[i] = a[3] == 1 ? ANCHOR_START : ANCHOR_END;
                i++;
            }
            int[] sFrom = syms.stream().mapToInt(a -> a[0]).toArray();
            int[] sTo = syms.stream().mapToInt(a -> a[1]).toArray();
            CharClass[] sClass = symClasses.toArray(new CharClass[0]);
            return new Tnfa(counter, eFrom, eTo, ePri, eTag, sFrom, sTo, sClass, start, accept, tagCount, groupCount,
                    hasStartAnchor, hasEndAnchor);
        }
    }

    public static final int ANCHOR_START = -1000;
    public static final int ANCHOR_END = -1001;
}
