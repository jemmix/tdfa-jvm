package io.github.jemmix.tdfa.tnfa;

import io.github.jemmix.tdfa.ast.Ast;
import io.github.jemmix.tdfa.ast.CharClass;
import io.github.jemmix.tdfa.parser.Parser;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
    public final boolean multiline;
    public final Map<String, Integer> namedGroups;

    // Zero-width assertion bits.
    public static final int BEGIN_TEXT        = 1;
    public static final int END_TEXT          = 2;
    public static final int WORD_BOUNDARY     = 4;
    public static final int NO_WORD_BOUNDARY  = 8;

    public Tnfa(int stateCount,
                int[] epsFrom, int[] epsTo, int[] epsPri, int[] epsTag, int[] epsEmptyMask,
                int[] symFrom, int[] symTo, CharClass[] symClass,
                int start, int accept, int tagCount, int groupCount, boolean multiline,
                Map<String, Integer> namedGroups) {
        this.stateCount = stateCount;
        this.epsFrom = epsFrom; this.epsTo = epsTo; this.epsPri = epsPri; this.epsTag = epsTag;
        this.epsEmptyMask = epsEmptyMask;
        this.symFrom = symFrom; this.symTo = symTo; this.symClass = symClass;
        this.start = start; this.accept = accept;
        this.tagCount = tagCount;
        this.groupCount = groupCount;
        this.multiline = multiline;
        this.namedGroups = namedGroups;
    }

    // ====== Builder / construction ======

    public static Tnfa compile(String pattern) {
        Parser parser = Parser.capture(pattern);
        Ast ast = parser.lastAst();
        Builder b = new Builder();
        int accept = b.fresh();
        int start = b.build(ast, accept);
        return b.build(start, accept, parser.tagCount(), parser.groupCount(), parser.multiline(), parser.namedGroups());
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
                // For POSIX (BT19 §7.3): prepend ntag (negative-tag) sub-automata to each
                // branch for groups that exist in OTHER branches but not this one. This
                // guarantees the U-tree prefix property and encodes "no match" structurally
                // so POSIX comparison can pick the correct alternative.
                int newStart = fresh();
                List<Ast> ch = ((Ast.Alt) e).children;
                // Compute groups per branch and union.
                List<java.util.BitSet> branchGroups = new ArrayList<>();
                java.util.BitSet union = new java.util.BitSet();
                for (Ast child : ch) {
                    java.util.BitSet g = new java.util.BitSet();
                    collectGroups(child, g);
                    branchGroups.add(g);
                    union.or(g);
                }
                for (int i = 0; i < ch.size(); i++) {
                    int altStart = build(ch.get(i), entryTo);
                    // Prepend ntags for missing groups (in union but not in this branch).
                    java.util.BitSet missing = (java.util.BitSet) union.clone();
                    missing.andNot(branchGroups.get(i));
                    for (int g = missing.length(); (g = missing.previousSetBit(g - 1)) >= 0; ) {
                        int ntagState = fresh();
                        int closeTag = 2 * g;  // close tag of group g (positive number)
                        taggedEps(ntagState, altStart, 1, -closeTag);  // negative = nil
                        altStart = ntagState;
                    }
                    eps(newStart, altStart, i + 1);
                }
                return newStart;
            }
            if (e instanceof Ast.Repeat) {
                return buildRepeat((Ast.Repeat) e, entryTo);
            }
            throw new IllegalStateException("unknown ast: " + e);
        }

        /** Collect group numbers (1-based) used anywhere in the AST subtree. */
        private static void collectGroups(Ast e, java.util.BitSet out) {
            if (e instanceof Ast.Tag) {
                Ast.Tag t = (Ast.Tag) e;
                int g = (t.tag + 1) / 2;
                out.set(g);
            } else if (e instanceof Ast.Concat) {
                for (Ast c : ((Ast.Concat) e).children) collectGroups(c, out);
            } else if (e instanceof Ast.Alt) {
                for (Ast c : ((Ast.Alt) e).children) collectGroups(c, out);
            } else if (e instanceof Ast.Repeat) {
                collectGroups(((Ast.Repeat) e).body, out);
            }
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
                // e? : newStart -(pri bodyPri)-> body -> entryTo ; newStart -(pri skipPri)-> [ntags] -> entryTo
                int s = fresh();
                int bodyStart = build(body, entryTo);
                eps(s, bodyStart, bodyPri);
                int skipTarget = entryTo;
                // Prepend ntags for groups in body (they didn't match on skip path).
                java.util.BitSet bodyGroups = new java.util.BitSet();
                collectGroups(body, bodyGroups);
                for (int g = bodyGroups.length(); (g = bodyGroups.previousSetBit(g - 1)) >= 0; ) {
                    int ntagState = fresh();
                    taggedEps(ntagState, skipTarget, 1, -(2 * g));
                    skipTarget = ntagState;
                }
                eps(s, skipTarget, skipPri);
                return s;
            }
            if (min == 0 && max == Integer.MAX_VALUE) {
                // e* : loop. ntags only on the INITIAL skip (0 iterations); subsequent
                // exits from loopBack do NOT re-emit ntags because the group already
                // matched in a prior iteration (BT19 §7.3 — ntag represents no-match).
                int s = fresh();
                int loopBack = fresh();
                int bodyStart = build(body, loopBack);
                eps(s, bodyStart, bodyPri);                       // prefer to enter body (greedy) / skip (lazy)
                // Initial skip path: prepend ntags for body groups (0 iterations ⇒ no match).
                int skipFromStart = entryTo;
                {
                    java.util.BitSet bodyGroups = new java.util.BitSet();
                    collectGroups(body, bodyGroups);
                    for (int g = bodyGroups.length(); (g = bodyGroups.previousSetBit(g - 1)) >= 0; ) {
                        int ntagState = fresh();
                        taggedEps(ntagState, skipFromStart, 1, -(2 * g));
                        skipFromStart = ntagState;
                    }
                }
                eps(s, skipFromStart, skipPri);
                eps(loopBack, bodyStart, bodyPri);                // loop back (no ntag; group already matched)
                eps(loopBack, entryTo, skipPri);                 // or exit (no ntag)
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

        Tnfa build(int start, int accept, int tagCount, int groupCount, boolean multiline, Map<String, Integer> namedGroups) {
            int n = eps.size();
            int[] eFrom = new int[n], eTo = new int[n], ePri = new int[n], eTag = new int[n], eEmpty = new int[n];
            for (int i = 0; i < n; i++) {
                int[] e = eps.get(i);
                eFrom[i] = e[0]; eTo[i] = e[1]; ePri[i] = e[2]; eTag[i] = e[3]; eEmpty[i] = e[4];
            }
            int[] sFrom = syms.stream().mapToInt(a -> a[0]).toArray();
            int[] sTo = syms.stream().mapToInt(a -> a[1]).toArray();
            CharClass[] sClass = symClasses.toArray(new CharClass[0]);
            return new Tnfa(counter, eFrom, eTo, ePri, eTag, eEmpty, sFrom, sTo, sClass, start, accept, tagCount, groupCount, multiline, namedGroups);
        }
    }
}
