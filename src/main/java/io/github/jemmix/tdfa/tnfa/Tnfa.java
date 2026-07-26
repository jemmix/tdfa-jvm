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
 *  - epsilon transitions: (from, to, priority, tag, emptyMask) where:
 *      tag = 0 (none), +t (set tag t to current pos), -t (clear tag t)
 *      emptyMask = bit mask of zero-width assertions that gate this ε-edge
 *
 * Lower priority = preferred (leftmost-greedy ordering).
 *
 * Assertion bits in emptyMask:
 *   BEGIN_TEXT        = 1   ( ^  or  \A )
 *   END_TEXT          = 2   ( $  or  \z )
 *   WORD_BOUNDARY     = 4   ( \b )
 *   NO_WORD_BOUNDARY  = 8   ( \B )
 *
 * Based on paper Algorithm 2 (TNFA construction), simplified: ntags on alternation
 * paths is included; repetition is handled by structural recursion.
 */
public final class Tnfa {
    public static final int NO_TAG = 0;

    public final int stateCount;
    public final int[] epsFrom, epsTo, epsPri, epsTag, epsEmptyMask;
    public final int[] symFrom, symTo;
    public final CharClass[] symClass;
    public final int start, accept;
    public final int tagCount;
    public final int groupCount;

    // Zero-width assertion bits.
    public static final int BEGIN_TEXT        = 1;
    public static final int END_TEXT          = 2;
    public static final int WORD_BOUNDARY     = 4;
    public static final int NO_WORD_BOUNDARY  = 8;

    public Tnfa(int stateCount,
                int[] epsFrom, int[] epsTo, int[] epsPri, int[] epsTag, int[] epsEmptyMask,
                int[] symFrom, int[] symTo, CharClass[] symClass,
                int start, int accept, int tagCount, int groupCount) {
        this.stateCount = stateCount;
        this.epsFrom = epsFrom; this.epsTo = epsTo; this.epsPri = epsPri; this.epsTag = epsTag;
        this.epsEmptyMask = epsEmptyMask;
        this.symFrom = symFrom; this.symTo = symTo; this.symClass = symClass;
        this.start = start; this.accept = accept;
        this.tagCount = tagCount;
        this.groupCount = groupCount;
    }

    // ====== Builder / construction ======

    public static Tnfa compile(String pattern) {
        Parser parser = Parser.capture(pattern);
        Ast ast = parser.lastAst();
        Builder b = new Builder();
        int accept = b.fresh();
        int start = b.build(ast, accept);
        return b.build(start, accept, parser.tagCount(), parser.groupCount());
    }

    private static final class Builder {
        final List<int[]> eps = new ArrayList<>();        // [from, to, pri, tag, emptyMask]
        final List<int[]> syms = new ArrayList<>();       // [from, to]
        final List<CharClass> symClasses = new ArrayList<>();
        int counter = 0;

        int fresh() { return counter++; }

        void eps(int from, int to, int pri) { eps.add(new int[]{from, to, pri, NO_TAG, 0}); }
        void taggedEps(int from, int to, int pri, int tag) { eps.add(new int[]{from, to, pri, tag, 0}); }
        void anchorEps(int from, int to, int pri, int emptyMask) { eps.add(new int[]{from, to, pri, NO_TAG, emptyMask}); }
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
            if (e instanceof Ast.StartAnchor) {      // ^ or \A
                int s = fresh();
                anchorEps(s, entryTo, 1, BEGIN_TEXT);
                return s;
            }
            if (e instanceof Ast.EndAnchor) {        // $ or \z
                int s = fresh();
                anchorEps(s, entryTo, 1, END_TEXT);
                return s;
            }
            if (e instanceof Ast.WordBoundary) {     // \b
                int s = fresh();
                anchorEps(s, entryTo, 1, WORD_BOUNDARY);
                return s;
            }
            if (e instanceof Ast.NoWordBoundary) {   // \B
                int s = fresh();
                anchorEps(s, entryTo, 1, NO_WORD_BOUNDARY);
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
            boolean lazy = !r.greedy;
            // Greedy: prefer BODY/REPEAT (pri 1) over SKIP/EXIT (pri 2).
            // Lazy:   prefer SKIP/EXIT   (pri 1) over BODY/REPEAT (pri 2).
            int bodyPri = lazy ? 2 : 1;
            int skipPri = lazy ? 1 : 2;
            if (min == 0 && max == 1) {
                // e? : newStart -(pri bodyPri)-> body -> entryTo ; newStart -(pri skipPri)-> entryTo
                int s = fresh();
                int bodyStart = build(body, entryTo);
                eps(s, bodyStart, bodyPri);
                eps(s, entryTo, skipPri);
                return s;
            }
            if (min == 0 && max == Integer.MAX_VALUE) {
                // e* : loop
                int s = fresh();
                int loopBack = fresh();
                int bodyStart = build(body, loopBack);
                eps(s, bodyStart, bodyPri);                  // prefer to enter body (greedy) / skip (lazy)
                eps(s, entryTo, skipPri);
                eps(loopBack, bodyStart, bodyPri);           // loop back
                eps(loopBack, entryTo, skipPri);             // or exit
                return s;
            }
            if (min == 1 && max == Integer.MAX_VALUE) {
                // e+ : body followed by e* (the e* uses the lazy/greedy preference)
                int loopBack = fresh();
                int bodyStart = build(body, loopBack);
                eps(loopBack, bodyStart, bodyPri);
                eps(loopBack, entryTo, skipPri);
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
                // {n,} = mandatory followed by body*  (preserve lazy/greedy)
                result = new Ast.Concat(List.of(mandatoryAst, new Ast.Repeat(body, 0, Integer.MAX_VALUE, r.greedy)));
            } else if (max > min) {
                // {n,m} = mandatory followed by m-n optional copies  (preserve lazy/greedy)
                List<Ast> tail = new ArrayList<>();
                for (int i = min; i < max; i++) tail.add(new Ast.Repeat(body, 0, 1, r.greedy));
                result = new Ast.Concat(List.of(mandatoryAst, new Ast.Concat(tail)));
            }
            // re-enter the builder with the desugared form, but DO NOT re-process via parser;
            // build it directly into entryTo.
            return build(result, entryTo);
        }

        Tnfa build(int start, int accept, int tagCount, int groupCount) {
            int n = eps.size();
            int[] eFrom = new int[n], eTo = new int[n], ePri = new int[n], eTag = new int[n], eEmpty = new int[n];
            for (int i = 0; i < n; i++) {
                int[] e = eps.get(i);
                eFrom[i] = e[0]; eTo[i] = e[1]; ePri[i] = e[2]; eTag[i] = e[3]; eEmpty[i] = e[4];
            }
            int[] sFrom = syms.stream().mapToInt(a -> a[0]).toArray();
            int[] sTo = syms.stream().mapToInt(a -> a[1]).toArray();
            CharClass[] sClass = symClasses.toArray(new CharClass[0]);
            return new Tnfa(counter, eFrom, eTo, ePri, eTag, eEmpty, sFrom, sTo, sClass, start, accept, tagCount, groupCount);
        }
    }
}
