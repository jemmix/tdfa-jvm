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
    public final boolean unicodeWordBoundary;
    public final int[] wordRanges;
    public final Map<String, Integer> namedGroups;
    /**
     * Fixed-tag annotations per tag id (1-based; index 0 unused). {@code fixedBase[t] != 0}
     * means tag {@code t} was omitted from the NFA and its match-time value should be
     * reconstructed as {@code tag[fixedBase[t]] - fixedOffset[t]} (or NIL if the base is NIL).
     * Built by {@link io.github.jemmix.tdfa.ast.FixedTags} (BT22 §6.4).
     */
    public final int[] fixedBase;
    public final int[] fixedOffset;

    // Zero-width assertion bits. BEGIN_TEXT/END_TEXT are LINE boundaries
    // (position 0 / end-of-input, plus after/before \n — unconditionally, the
    // (?m) flavor lives in which bit each ^/$ edge requires); ABS_BEGIN/ABS_END
    // are absolute (position 0 / end-of-input only).
    public static final int BEGIN_TEXT        = 1;
    public static final int END_TEXT          = 2;
    public static final int WORD_BOUNDARY     = 4;
    public static final int NO_WORD_BOUNDARY  = 8;
    public static final int ABS_BEGIN         = 16;
    public static final int ABS_END           = 32;

    public Tnfa(int stateCount,
                int[] epsFrom, int[] epsTo, int[] epsPri, int[] epsTag, int[] epsEmptyMask,
                int[] symFrom, int[] symTo, CharClass[] symClass,
                int start, int accept, int tagCount, int groupCount, boolean multiline,
                boolean unicodeWordBoundary, int[] wordRanges,
                Map<String, Integer> namedGroups, int[] fixedBase, int[] fixedOffset) {
        this.stateCount = stateCount;
        this.epsFrom = epsFrom; this.epsTo = epsTo; this.epsPri = epsPri; this.epsTag = epsTag;
        this.epsEmptyMask = epsEmptyMask;
        this.symFrom = symFrom; this.symTo = symTo; this.symClass = symClass;
        this.start = start; this.accept = accept;
        this.tagCount = tagCount;
        this.groupCount = groupCount;
        this.multiline = multiline;
        this.unicodeWordBoundary = unicodeWordBoundary;
        this.wordRanges = wordRanges;
        this.namedGroups = namedGroups;
        this.fixedBase = fixedBase;
        this.fixedOffset = fixedOffset;
    }

    // ====== Builder / construction ======

    public static Tnfa compile(String pattern) {
        return compile(pattern, false);
    }

    public static Tnfa compile(String pattern, boolean disableUnicodeGroups) {
        return compile(pattern, disableUnicodeGroups, false);
    }

    public static Tnfa compile(String pattern, boolean disableUnicodeGroups, boolean anchorBoth) {
        return compile(pattern, disableUnicodeGroups, anchorBoth, io.github.jemmix.tdfa.unicode.UnicodeProviders.get());
    }

    public static Tnfa compile(String pattern, boolean disableUnicodeGroups, boolean anchorBoth,
                               io.github.jemmix.tdfa.unicode.UnicodeDataProvider provider) {
        return compile(pattern, disableUnicodeGroups, anchorBoth, provider, null);
    }

    public static Tnfa compile(String pattern, boolean disableUnicodeGroups, boolean anchorBoth,
                               io.github.jemmix.tdfa.unicode.UnicodeDataProvider provider,
                               io.github.jemmix.tdfa.core.CompileObserver observer) {
        long t0 = System.nanoTime();
        io.github.jemmix.tdfa.parser.ParseResult parsed =
                Parser.parseResult(pattern, disableUnicodeGroups, anchorBoth, provider);
        if (observer != null) observer.stage(io.github.jemmix.tdfa.core.CompileObserver.Stage.PARSE,
                System.nanoTime() - t0, parsed.tagCount());
        long t1 = System.nanoTime();
        Ast ast = parsed.ast();
        io.github.jemmix.tdfa.ast.FixedTags.apply(ast);
        int tagCount = parsed.tagCount();
        int[] fixedBase = new int[tagCount + 1];
        int[] fixedOffset = new int[tagCount + 1];
        collectFixedAnnotations(ast, fixedBase, fixedOffset);
        if (Boolean.getBoolean("tdfa.debug")) {
            int n = 0;
            for (int t = 1; t <= tagCount; t++) if (fixedBase[t] != 0) n++;
            if (n > 0) System.err.println("[tdfa] fixed-tags: dropped " + n + "/" + tagCount);
        }
        Builder b = new Builder();
        int accept = b.fresh();
        int start = b.build(ast, accept);
        Tnfa nfa = b.build(start, accept, tagCount, parsed.groupCount(), parsed.multiline(),
                parsed.unicodeShorthand(), parsed.unicodeWordRanges(), parsed.namedGroups(),
                fixedBase, fixedOffset);
        if (observer != null) observer.stage(io.github.jemmix.tdfa.core.CompileObserver.Stage.TNFA,
                System.nanoTime() - t1, nfa.stateCount);
        return nfa;
    }

    /** Collect {@link Ast.Tag#fixedOn} / {@link Ast.Tag#fixedOffset} annotations into
     *  1-indexed arrays for forwarding to {@link Tdfa}. */
    private static void collectFixedAnnotations(Ast e, int[] fixedBase, int[] fixedOffset) {
        if (e instanceof Ast.Tag) {
            Ast.Tag t = (Ast.Tag) e;
            if (t.fixedOn != 0) {
                fixedBase[t.tag] = t.fixedOn;
                fixedOffset[t.tag] = t.fixedOffset;
            }
        } else if (e instanceof Ast.Concat) {
            for (Ast c : ((Ast.Concat) e).children) collectFixedAnnotations(c, fixedBase, fixedOffset);
        } else if (e instanceof Ast.Alt) {
            for (Ast c : ((Ast.Alt) e).children) collectFixedAnnotations(c, fixedBase, fixedOffset);
        } else if (e instanceof Ast.Repeat) {
            collectFixedAnnotations(((Ast.Repeat) e).body, fixedBase, fixedOffset);
        }
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
                Ast.Tag t = (Ast.Tag) e;
                int s = fresh();
                if (t.fixedOn != 0) {
                    // Fixed tag: omit from NFA. Position reconstructed at match time
                    // from tag[fixedOn] - fixedOffset (see MatchResult.reconstructFixed).
                    eps(s, entryTo, 1);
                } else {
                    taggedEps(s, entryTo, 1, t.tag);
                }
                return s;
            }
            if (e instanceof Ast.StartAnchor a) {      // ^ or \A
                int s = fresh();
                // Anchor flavor is per-edge (parse-time (?m), group-scoped flags
                // included): m-^ needs BEGIN_TEXT (line begin, always \n-aware);
                // plain ^ and \A are position-0 only (ABS_BEGIN).
                anchorEps(s, entryTo, 1, a.absolute || !a.multiline ? ABS_BEGIN : BEGIN_TEXT);
                return s;
            }
            if (e instanceof Ast.EndAnchor a) {        // $ or \z
                int s = fresh();
                // m-$ needs END_TEXT (line end, always \n-aware); plain $ and \z
                // are end-of-input only (ABS_END).
                anchorEps(s, entryTo, 1, a.absolute || !a.multiline ? ABS_END : END_TEXT);
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

        /**
         * Whether {@code e} can match the empty string (the syntactic analogue
         * of re2j's {@code Frag.nullable}, which drives its
         * {@code x* → (x+)?} star compilation for nullable bodies). Anchors and
         * word boundaries are zero-width, hence nullable; symbol-bearing nodes
         * are not.
         */
        private static boolean isNullable(Ast e) {
            if (e instanceof Ast.Symbol) return false;
            if (e instanceof CharClass) return false;
            if (e instanceof Ast.Repeat) {
                Ast.Repeat r = (Ast.Repeat) e;
                return r.min == 0 || isNullable(r.body);
            }
            if (e instanceof Ast.Concat) {
                for (Ast c : ((Ast.Concat) e).children) {
                    if (!isNullable(c)) return false;
                }
                return true;
            }
            if (e instanceof Ast.Alt) {
                for (Ast c : ((Ast.Alt) e).children) {
                    if (isNullable(c)) return true;
                }
                return false;
            }
            return true;  // Empty, Tag, anchors, word boundaries
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
                // exits from the loop hub do NOT re-emit ntags because the group already
                // matched in a prior iteration (BT19 §7.3 — ntag represents no-match).
                //
                // Topology mirrors re2j's Compiler.star(), which has two shapes:
                //
                // (a) NON-nullable body — one shared hub that both the body's exit
                //     and the initial entry pass through, deciding iterate-vs-exit
                //     (re2j Prog loop()). With the former split entry/loop nodes, an
                //     OUTER repeat's re-entry path (which crosses the group's
                //     open-tag edge and arrives at this star's ENTRY node) found it
                //     unvisited and stole the ε-closure slot, so the surviving
                //     continuation carried a RE-OPENED group tag — reporting the
                //     last iteration's span for shapes like (a*?)*? on "aaa"
                //     (g1=[2,3) where re2j reports [0,3)). With the shared hub, the
                //     outer re-entry dies at the already-visited entry/hub nodes —
                //     re2j's observable priority — and the plain continuation wins.
                //
                // (b) NULLABLE body — (f+)? (re2j: "When f1 can match an empty
                //     string, f1* must be implemented as (f1+)? to get the priority
                //     match order correct"): a quest whose body-side enters the
                //     plus's body DIRECTLY, with the iterate/exit hub only at the
                //     body's exit. This keeps the enter-body-ε-through-exit accept
                //     at the TOP of the priority order with the group's close tag
                //     traversed (re2j (a*?)* on "aaa" = [0,0) g1=[0,0)), which a
                //     plain hub loop cannot: the ε-pass collapses into the hub
                //     dedup and the surviving accept loses to the body's rune.
                if (isNullable(body)) {
                    int hub = fresh();          // plus loop hub (at body exit)
                    int bodyStart = build(body, hub);
                    eps(hub, bodyStart, bodyPri);               // iterate (no ntag; group already matched)
                    eps(hub, entryTo, skipPri);                 // or exit (no ntag)
                    int s0 = fresh();          // quest: skip vs enter the plus
                    eps(s0, bodyStart, bodyPri);
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
                    eps(s0, skipFromStart, skipPri);
                    return s0;
                }
                int s0 = fresh();      // pre-loop decision: initial skip vs enter
                int hub = fresh();     // loop hub: iterate vs exit
                int bodyStart = build(body, hub);
                eps(s0, hub, bodyPri);                             // enter the loop (greedy) / skip (lazy)
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
                eps(s0, skipFromStart, skipPri);
                eps(hub, bodyStart, bodyPri);                      // iterate (no ntag; group already matched)
                eps(hub, entryTo, skipPri);                        // or exit (no ntag)
                return s0;
            }
            if (min == 1 && max == Integer.MAX_VALUE) {
                // e+ : body followed by e* (the e* uses the lazy/greedy preference)
                int loopBack = fresh();
                int bodyStart = build(body, loopBack);
                eps(loopBack, bodyStart, bodyPri);
                eps(loopBack, entryTo, skipPri);
                return bodyStart;
            }
            // Bounded repetitions {n}, {n,}, {n,m} — desugar via concatenation + tail.
            // (We desugar rather than implementing the paper's bounded-rep construction literally;
            //  tags are duplicated, which is acceptable for our subset.)
            List<Ast> mandatory = new ArrayList<>();
            for (int i = 0; i < min; i++) mandatory.add(body);
            Ast mandatoryAst = mandatory.isEmpty() ? new Ast.Empty() :
                    (mandatory.size() == 1 ? mandatory.get(0) : new Ast.Concat(mandatory));
            Ast result = mandatoryAst;
            if (max == Integer.MAX_VALUE) {
                // {n,} = (n-1) copies followed by body+  — NOT body*.
                // re2j's Simplify general case ("x{4,} is xxxx+"): a PLUS tail
                // guarantees one real iteration, and a nullable body's empty
                // RE-iteration is cut by the pike pc-dedup (the plus entry pc
                // is revisited), so the last NON-EMPTY capture survives —
                // (a?){2,} on "aa" reports g1="a". A STAR tail instead lets
                // the greedy first-iteration-empty write an empty capture
                // (g1=""), diverging from re2j (fuzz round 9: 25 records).
                // min >= 2 here ({0,} star and {1,} plus have their own cases).
                List<Ast> copies = new ArrayList<>();
                for (int i = 0; i < min - 1; i++) copies.add(body);
                Ast copiesAst = copies.isEmpty() ? new Ast.Empty() :
                        (copies.size() == 1 ? copies.get(0) : new Ast.Concat(copies));
                result = new Ast.Concat(List.of(copiesAst, new Ast.Repeat(body, 1, Integer.MAX_VALUE, r.greedy)));
            } else if (max > min) {
                // {n,m} = mandatory + RIGHT-NESTED optional suffix (x(x(x)?)?)?,
                // exactly re2j Simplify's shape ("x{2,5} = xx(x(x(x)?)?)?").
                // The former FLAT tail (B?B?B?) is match-equivalent but resolves
                // the priority tie "enter the next optional copy" vs "extend the
                // current copy's inner lazy body" the OPPOSITE way: nested-lazy
                // captures then report the extended span while re2j/JDK report
                // the next copy's (fuzz round 18: ((a{1,2}?c?){0,5}?)d on "aad":
                // re2j/JDK g2="a" — two outer iterations — flat tail g2="aa").
                Ast suffix = new Ast.Repeat(body, 0, 1, r.greedy);
                for (int i = min + 1; i < max; i++) {
                    suffix = new Ast.Repeat(new Ast.Concat(List.of(body, suffix)), 0, 1, r.greedy);
                }
                result = new Ast.Concat(List.of(mandatoryAst, suffix));
            }
            // re-enter the builder with the desugared form, but DO NOT re-process via parser;
            // build it directly into entryTo.
            return build(result, entryTo);
        }

        Tnfa build(int start, int accept, int tagCount, int groupCount, boolean multiline,
                   boolean unicodeWordBoundary, int[] wordRanges, Map<String, Integer> namedGroups,
                   int[] fixedBase, int[] fixedOffset) {
            int n = eps.size();
            int[] eFrom = new int[n], eTo = new int[n], ePri = new int[n], eTag = new int[n], eEmpty = new int[n];
            for (int i = 0; i < n; i++) {
                int[] e = eps.get(i);
                eFrom[i] = e[0]; eTo[i] = e[1]; ePri[i] = e[2]; eTag[i] = e[3]; eEmpty[i] = e[4];
            }
            int[] sFrom = syms.stream().mapToInt(a -> a[0]).toArray();
            int[] sTo = syms.stream().mapToInt(a -> a[1]).toArray();
            CharClass[] sClass = symClasses.toArray(new CharClass[0]);
            return new Tnfa(counter, eFrom, eTo, ePri, eTag, eEmpty, sFrom, sTo, sClass, start, accept,
                    tagCount, groupCount, multiline, unicodeWordBoundary, wordRanges, namedGroups,
                    fixedBase, fixedOffset);
        }
    }
}
