package io.github.jemmix.tdfa.core.tnfa;

import io.github.jemmix.tdfa.core.ast.Ast;
import io.github.jemmix.tdfa.core.ast.CharClass;
import io.github.jemmix.tdfa.core.ast.FixedTags;
import io.github.jemmix.tdfa.core.budget.BudgetWeights;
import io.github.jemmix.tdfa.core.budget.Budgets;
import io.github.jemmix.tdfa.core.budget.FrameBudget;
import io.github.jemmix.tdfa.core.budget.PatternTooLargeException;
import io.github.jemmix.tdfa.core.budget.WorkMeter;
import io.github.jemmix.tdfa.core.parser.ParseOptions;
import io.github.jemmix.tdfa.core.parser.ParseResult;
import io.github.jemmix.tdfa.core.parser.Parser;
import io.github.jemmix.tdfa.core.report.CompileObserver;
import io.github.jemmix.tdfa.core.unicode.UnicodeDataProvider;
import io.github.jemmix.tdfa.core.unicode.UnicodeProviders;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.IdentityHashMap;
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
 * paths is included; repetition is handled structurally.
 *
 * Construction is iterative (re2j's Compiler is too): an explicit
 * {@link BuildFrame} worklist — one frame per AST node in progress — drives
 * the minting of states and edges. The minting ORDER is part of the
 * contract (pre-body fresh() for repeats, concat children right-to-left,
 * alt branches left-to-right followed by their ntag chains), because it
 * fixes state numbering and thus the built NFA bit-for-bit. Nesting depth
 * (and the multiplicative {n,m} desugaring depth) costs heap, charged to
 * the transient compile RAM budget ({@link FrameBudget}), never JVM stack.
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
    /**
     * True iff this NFA was built for a multi-valued compile (BT22 §3.1):
     * the fixed-tag pass was skipped (its single-value reconstruction
     * cannot serve per-iteration offset sequences) and the determinizer
     * keeps full occurrence histories, emitting append ops. The flag rides
     * the NFA — not a separate option plumbed through every determinizer
     * entry — because it changes the NFA build itself and everything
     * downstream (determinizer, materializer, artifact) reads one source.
     */
    public final boolean multiValuedTags;
    /**
     * The compile's {@link Semantics} — its interpretation policy —
     * riding the NFA beside {@link #multiValuedTags} as the single
     * source every downstream stage projects from, so no stage needs
     * a per-axis parameter at its entry. See {@link Semantics} for
     * the axis set, polarity, and membership rule.
     */
    public final Semantics semantics;
    public final boolean unicodeWordBoundary;
    public final int[] wordRanges;
    public final Map<String, Integer> namedGroups;
    /**
     * Fixed-tag annotations per tag id (1-based; index 0 unused). {@code fixedBase[t] != 0}
     * means tag {@code t} was omitted from the NFA and its match-time value should be
     * reconstructed as {@code tag[fixedBase[t]] - fixedOffset[t]} (or NIL if the base is NIL).
     * Built by {@link io.github.jemmix.tdfa.core.ast.FixedTags} (BT22 §6.4).
     */
    public final int[] fixedBase;

    public final int[] fixedOffset;
    /**
     * Tags of dissolved groups (family 6 sub-family A, the
     * EMPTY_ITERATION_SPANS axis): a greedy min-0 loop quantifier
     * ({@code cmax >= 2} or unbounded) directly on a capture whose body
     * is all-zero-width and deterministic — java.util.regex's
     * {@code GroupCurly} rolls the zero-width iteration back to the
     * pre-curly bounds, so the pair reports NIL at every accept on the
     * JUR lane (the determinizer's final-φ override). Parser-marked
     * (1-indexed, like {@link #fixedBase}); the fixed-tags pass keeps
     * these tags un-fixed and base-free (their final value is
     * protocol-NIL, not a reconstructable distance). {@code null} when
     * no group dissolved — every RE2-lane compile, where the axis keeps
     * the maximal-iteration spans and the artifacts are bit-identical to
     * pre-family-6 builds.
     */
    public final boolean[] dissolvedTags;
    /**
     * Tags of surfaced-final groups (family 6 sub-family B): a capture
     * under an UNBOUNDED greedy quantifier ({@code *}, {@code +},
     * {@code {n,}}) whose body can match empty through a choice point —
     * java.util.regex's {@code Prolog}+{@code Loop} reports the final
     * zero-width iteration, which the determinizer's same-position
     * closure subsumption cuts. The accepting kernels where the cut
     * fired surface the pair's writes as a final-φ SET_POS at the accept
     * position (mask-gated by the cut path's assertions). Parser-marked
     * (1-indexed); {@code null} on the RE2 lane (no cut recording, no
     * overrides — bit-identical artifacts).
     */
    public final boolean[] surfaceFinalTags;

    /**
     * java.util.regex's {@code hasSupplementary(pattern)} (the StartS/Start
     * selection — see {@code Parser#hasSupplementaryStarts} for the exact
     * flip rules), carried from the parse and frozen onto the
     * {@code Tdfa}: the runner ORs it into its candidate-scan pivot beside
     * the CODEPOINT_BOUNDARIES axis (a JUR-lane pattern with supplementary
     * content scans codepoint-aligned starts exactly like java's StartS).
     */
    public final boolean hasSupplementaryStarts;

    // Zero-width assertion bits. BEGIN_TEXT/END_TEXT are LINE boundaries
    // (position 0 / end-of-input, plus after/before a terminator of the
    // compile's set — the (?m) flavor lives in which bit each ^/$ edge
    // requires); ABS_BEGIN/ABS_END are absolute (position 0 / end-of-input
    // only). FINAL_END is the plain-$ end rule: end of input, or immediately
    // before the final terminator run (java.util.regex $/\Z); it only ever
    // gates an edge when END_OF_TEXT_ONLY is unset (the RE2-lineage side
    // lowers plain $ to ABS_END, so the bit never appears there and the
    // position-flag tables keep their 64-cell stride).
    public static final int BEGIN_TEXT = 1;
    public static final int END_TEXT = 2;
    public static final int WORD_BOUNDARY = 4;
    public static final int NO_WORD_BOUNDARY = 8;
    public static final int ABS_BEGIN = 16;
    public static final int ABS_END = 32;
    public static final int FINAL_END = 64;

    /** Every axis set (the RE2-lineage reading): the legacy-overload default. */
    private static final Semantics ALL_AXES = Semantics.of().unixLines().unicodeCase().codepointBoundaries()
        .emptyLastLine().endOfTextOnly().emptyIterationSpans().ungreedyU().spaceRe2();

    public Tnfa(int stateCount, int[] epsFrom, int[] epsTo, int[] epsPri, int[] epsTag, int[] epsEmptyMask,
        int[] symFrom, int[] symTo, CharClass[] symClass, int start, int accept, int tagCount, int groupCount,
        boolean multiline, boolean unicodeWordBoundary, int[] wordRanges, Map<String, Integer> namedGroups,
        int[] fixedBase, int[] fixedOffset) {
        this(stateCount, epsFrom, epsTo, epsPri, epsTag, epsEmptyMask, symFrom, symTo, symClass, start, accept,
            tagCount, groupCount, multiline, unicodeWordBoundary, wordRanges, namedGroups, fixedBase, fixedOffset, null,
            null, false, ALL_AXES);
    }

    /** Legacy convenience (no family-6 marks — the RE2-lane reading). */
    public Tnfa(int stateCount, int[] epsFrom, int[] epsTo, int[] epsPri, int[] epsTag, int[] epsEmptyMask,
        int[] symFrom, int[] symTo, CharClass[] symClass, int start, int accept, int tagCount, int groupCount,
        boolean multiline, boolean unicodeWordBoundary, int[] wordRanges, Map<String, Integer> namedGroups,
        int[] fixedBase, int[] fixedOffset, boolean[] dissolvedTags) {
        this(stateCount, epsFrom, epsTo, epsPri, epsTag, epsEmptyMask, symFrom, symTo, symClass, start, accept,
            tagCount, groupCount, multiline, unicodeWordBoundary, wordRanges, namedGroups, fixedBase, fixedOffset,
            dissolvedTags, null, false, ALL_AXES);
    }

    /** Legacy convenience (no family-6 marks — the RE2-lane reading). */
    public Tnfa(int stateCount, int[] epsFrom, int[] epsTo, int[] epsPri, int[] epsTag, int[] epsEmptyMask,
        int[] symFrom, int[] symTo, CharClass[] symClass, int start, int accept, int tagCount, int groupCount,
        boolean multiline, boolean unicodeWordBoundary, int[] wordRanges, Map<String, Integer> namedGroups,
        int[] fixedBase, int[] fixedOffset, boolean[] dissolvedTags, boolean[] surfaceFinalTags) {
        this(stateCount, epsFrom, epsTo, epsPri, epsTag, epsEmptyMask, symFrom, symTo, symClass, start, accept,
            tagCount, groupCount, multiline, unicodeWordBoundary, wordRanges, namedGroups, fixedBase, fixedOffset,
            dissolvedTags, surfaceFinalTags, false, ALL_AXES);
    }

    public Tnfa(int stateCount, int[] epsFrom, int[] epsTo, int[] epsPri, int[] epsTag, int[] epsEmptyMask,
        int[] symFrom, int[] symTo, CharClass[] symClass, int start, int accept, int tagCount, int groupCount,
        boolean multiline, boolean unicodeWordBoundary, int[] wordRanges, Map<String, Integer> namedGroups,
        int[] fixedBase, int[] fixedOffset, boolean multiValuedTags) {
        this(stateCount, epsFrom, epsTo, epsPri, epsTag, epsEmptyMask, symFrom, symTo, symClass, start, accept,
            tagCount, groupCount, multiline, unicodeWordBoundary, wordRanges, namedGroups, fixedBase, fixedOffset, null,
            null, multiValuedTags, ALL_AXES);
    }

    public Tnfa(int stateCount, int[] epsFrom, int[] epsTo, int[] epsPri, int[] epsTag, int[] epsEmptyMask,
        int[] symFrom, int[] symTo, CharClass[] symClass, int start, int accept, int tagCount, int groupCount,
        boolean multiline, boolean unicodeWordBoundary, int[] wordRanges, Map<String, Integer> namedGroups,
        int[] fixedBase, int[] fixedOffset, boolean[] dissolvedTags, boolean[] surfaceFinalTags,
        boolean multiValuedTags, Semantics semantics) {
        this(stateCount, epsFrom, epsTo, epsPri, epsTag, epsEmptyMask, symFrom, symTo, symClass, start, accept,
            tagCount, groupCount, multiline, unicodeWordBoundary, wordRanges, namedGroups, fixedBase, fixedOffset,
            dissolvedTags, surfaceFinalTags, multiValuedTags, semantics, false);
    }

    /** Fullest entry: the StartS-selection fact (see
     *  {@link #hasSupplementaryStarts}) rides beside the policy. */
    public Tnfa(int stateCount, int[] epsFrom, int[] epsTo, int[] epsPri, int[] epsTag, int[] epsEmptyMask,
        int[] symFrom, int[] symTo, CharClass[] symClass, int start, int accept, int tagCount, int groupCount,
        boolean multiline, boolean unicodeWordBoundary, int[] wordRanges, Map<String, Integer> namedGroups,
        int[] fixedBase, int[] fixedOffset, boolean[] dissolvedTags, boolean[] surfaceFinalTags,
        boolean multiValuedTags, Semantics semantics, boolean hasSupplementaryStarts) {
        this.stateCount = stateCount;
        this.epsFrom = epsFrom;
        this.epsTo = epsTo;
        this.epsPri = epsPri;
        this.epsTag = epsTag;
        this.epsEmptyMask = epsEmptyMask;
        this.symFrom = symFrom;
        this.symTo = symTo;
        this.symClass = symClass;
        this.start = start;
        this.accept = accept;
        this.tagCount = tagCount;
        this.groupCount = groupCount;
        this.multiline = multiline;
        this.multiValuedTags = multiValuedTags;
        this.semantics = semantics;
        this.unicodeWordBoundary = unicodeWordBoundary;
        this.wordRanges = wordRanges;
        this.namedGroups = namedGroups;
        this.fixedBase = fixedBase;
        this.fixedOffset = fixedOffset;
        this.dissolvedTags = dissolvedTags;
        this.surfaceFinalTags = surfaceFinalTags;
        this.hasSupplementaryStarts = hasSupplementaryStarts;
    }

    // ====== Builder / construction ======

    public static Tnfa compile(String pattern) {
        return compile(pattern, false, false, UnicodeProviders.get());
    }

    public static Tnfa compile(String pattern, boolean disableUnicodeGroups, boolean anchorBoth,
        UnicodeDataProvider provider) {
        return compile(pattern, disableUnicodeGroups, anchorBoth, provider, null);
    }

    public static Tnfa compile(String pattern, boolean disableUnicodeGroups, boolean anchorBoth,
        UnicodeDataProvider provider, CompileObserver observer) {
        return compile(pattern, disableUnicodeGroups, anchorBoth, provider, observer,
            new WorkMeter(Budgets.compileComputeTicks()));
    }

    /** Multi-valued twin of the plain entry (BT22 §3.1; see {@link #multiValuedTags}). */
    public static Tnfa compileMulti(String pattern, boolean disableUnicodeGroups, UnicodeDataProvider provider,
        CompileObserver observer, WorkMeter meter) {
        return compile(pattern, disableUnicodeGroups, false, true, ALL_AXES, provider, observer, meter);
    }

    /** Multi-valued twin with an explicit {@link Semantics} (see {@link #semantics}). */
    public static Tnfa compileMulti(String pattern, boolean disableUnicodeGroups, Semantics semantics,
        UnicodeDataProvider provider, CompileObserver observer, WorkMeter meter) {
        return compile(pattern, disableUnicodeGroups, false, true, semantics, provider, observer, meter);
    }

    /**
     * Ledger variant: parse + TNFA build run on the CALLER's work meter
     * (typically forked from the compile's root ledger — see {@code
     * WorkMeter#fork}), so front-end work is debited against the same CPU
     * budget as every determinization attempt of the same compile.
     */
    public static Tnfa compile(String pattern, boolean disableUnicodeGroups, boolean anchorBoth,
        UnicodeDataProvider provider, CompileObserver observer, WorkMeter meter) {
        return compile(pattern, disableUnicodeGroups, anchorBoth, false, provider, observer, meter);
    }

    public static Tnfa compile(String pattern, boolean disableUnicodeGroups, boolean anchorBoth,
        boolean multiValuedTags, UnicodeDataProvider provider, CompileObserver observer, WorkMeter meter) {
        return compile(pattern, disableUnicodeGroups, anchorBoth, multiValuedTags, ALL_AXES, provider, observer, meter);
    }

    /**
     * Fullest entry: every pipeline knob, the compile's
     * {@link Semantics} included (see {@link #semantics}); the
     * legacy overloads above pass every axis set (the RE2-lineage
     * reading).
     */
    public static Tnfa compile(String pattern, boolean disableUnicodeGroups, boolean anchorBoth,
        boolean multiValuedTags, Semantics semantics, UnicodeDataProvider provider, CompileObserver observer,
        WorkMeter meter) {
        long t0 = System.nanoTime();
        // Front-end budget: ONE work meter (CPU, ticks) spans parse + TNFA
        // build so the pre-determinization surface is bounded too — the
        // parser's O(universe) fold-range scan ticks it, and the Builder's
        // state/edge creation ticks it AND accumulates weighted bytes
        // against the compile RAM budget (nested counted repeats can OOM
        // the JVM here before any determinization cap fires — e.g.
        // ((a{300}){300}){300} is rejected as a clean "pattern too large").
        // Determinization constructs its own meter per attempt (TdfaCompiler).
        // Parser-side projection of the compile's Semantics (the carrier
        // stays here; the parser package sits below tnfa in the layer DAG):
        // the DOT terminator set (UNIX_LINES), the fold universe
        // (UNICODE_CASE) and the (?U) meaning (UNGREEDY_U).
        ParseResult parsed = Parser.parseResult(pattern, disableUnicodeGroups, anchorBoth,
            ParseOptions.dotNlOnly(semantics.isUnixLines()).unicodeCase(semantics.isUnicodeCase())
                .ungreedyU(semantics.isUngreedyU()).emptyIterationSpans(semantics.isEmptyIterationSpans())
                .spaceRe2(semantics.isSpaceRe2()),
            provider, meter);
        if (observer != null) {
            observer.stage(CompileObserver.Stage.PARSE, System.nanoTime() - t0, parsed.tagCount());
        }
        long t1 = System.nanoTime();
        Ast ast = parsed.ast();
        // The nested-loop inner-capture quirk (JUR lane): mark the greedy
        // captures quantified directly by a GroupCurly-shape quantifier
        // nested inside another quantified construct BEFORE the fixed-tags
        // pass — a sticky pair must keep its own register (see Ast.Tag).
        List<StickyCap> stickyCaps =
            multiValuedTags || semantics.isEmptyIterationSpans() ? Collections.emptyList() : markStickyCaptures(ast);
        // Multi-valued compiles keep the fixed-tag pass OFF: §6.4 drops the
        // fixed tags from the NFA and reconstructs ONE offset per tag at
        // match time — meaningless for a tag whose whole point is its
        // per-iteration offset SEQUENCE (and a fixed tag whose base is
        // multi-valued has no single value to derive from).
        int[] fixedBase;
        int[] fixedOffset;
        if (multiValuedTags) {
            fixedBase = new int[parsed.tagCount() + 1];
            fixedOffset = new int[parsed.tagCount() + 1];
        } else {
            FixedTags.apply(ast);
            fixedBase = new int[parsed.tagCount() + 1];
            fixedOffset = new int[parsed.tagCount() + 1];
            collectFixedAnnotations(ast, fixedBase, fixedOffset);
        }
        int tagCount = parsed.tagCount();
        boolean[] dissolved = collectMarked(ast, tagCount, false);
        boolean[] surfaced = collectMarked(ast, tagCount, true);
        if (Boolean.getBoolean("tdfa.debug")) {
            int n = 0;
            for (int t = 1; t <= tagCount; t++) {
                if (fixedBase[t] != 0) {
                    n++;
                }
            }
            if (n > 0) {
                System.err.println("[tdfa] fixed-tags: dropped " + n + "/" + tagCount);
            }
        }
        Builder b = new Builder(meter, semantics);
        int accept = b.fresh();
        int start = b.build(ast, accept);
        b.stickySplit(stickyCaps);
        Tnfa nfa = b.build(start, accept, tagCount, parsed.groupCount(), parsed.multiline(), parsed.unicodeShorthand(),
            parsed.unicodeWordRanges(), parsed.namedGroups(), fixedBase, fixedOffset, dissolved, surfaced,
            multiValuedTags, semantics, parsed.hasSupplementaryStarts());
        if (observer != null) {
            observer.stage(CompileObserver.Stage.TNFA, System.nanoTime() - t1, nfa.stateCount);
        }
        return nfa;
    }

    /** Collect {@link Ast.Tag#fixedOn} / {@link Ast.Tag#fixedOffset} annotations into
     *  1-indexed arrays for forwarding to the TDFA compiler. Iterative
     *  worklist — the front-end puts no bound on AST depth, so no walk here
     *  may consume program stack proportional to it. */
    private static void collectFixedAnnotations(Ast root, int[] fixedBase, int[] fixedOffset) {
        ArrayDeque<Ast> work = new ArrayDeque<>();
        work.push(root);
        while (!work.isEmpty()) {
            Ast e = work.pop();
            if (e instanceof Ast.Tag) {
                Ast.Tag t = (Ast.Tag) e;
                if (t.fixedOn != 0) {
                    fixedBase[t.tag] = t.fixedOn;
                    fixedOffset[t.tag] = t.fixedOffset;
                }
            } else if (e instanceof Ast.Concat) {
                work.addAll(((Ast.Concat) e).children);
            } else if (e instanceof Ast.Alt) {
                work.addAll(((Ast.Alt) e).children);
            } else if (e instanceof Ast.Repeat) {
                work.push(((Ast.Repeat) e).body);
            }
        }
    }

    /** Collect the parser's family-6 marks — sub-family A
     *  ({@link Ast.Tag#dissolved}) or B ({@link Ast.Tag#surfaceFinal})
     *  per the flag — into a 1-indexed array for the determinizer's
     *  final-φ overrides; {@code null} when nothing marked (every
     *  RE2-lane compile — the field stays absent, not empty, so identity
     *  checks are one reference test). Iterative worklist like
     *  {@link #collectFixedAnnotations}: pre-desugar AST, each Tag
     *  visited exactly once, no stack recursion.
     */
    private static boolean[] collectMarked(Ast root, int tagCount, boolean surface) {
        boolean[] marked = null;
        ArrayDeque<Ast> work = new ArrayDeque<>();
        work.push(root);
        while (!work.isEmpty()) {
            Ast e = work.pop();
            if (e instanceof Ast.Tag) {
                if (surface ? ((Ast.Tag) e).surfaceFinal : ((Ast.Tag) e).dissolved) {
                    if (marked == null) {
                        marked = new boolean[tagCount + 1];
                    }
                    marked[((Ast.Tag) e).tag] = true;
                }
            } else if (e instanceof Ast.Concat) {
                work.addAll(((Ast.Concat) e).children);
            } else if (e instanceof Ast.Alt) {
                work.addAll(((Ast.Alt) e).children);
            } else if (e instanceof Ast.Repeat) {
                work.push(((Ast.Repeat) e).body);
            }
        }
        return marked;
    }

    /** One sticky-capture finding of {@link #markStickyCaptures}: the
     *  capture's tag pair and the innermost/outermost enclosing repeats
     *  with iteration machinery ({@code max} unbounded or {@code >= 2} —
     *  {@code {0,1}}/{@code {1,1}} never re-invoke the capture). */
    private static final class StickyCap {
        final int openTag;
        final int closeTag;
        final Ast.Repeat inner;
        final Ast.Repeat outer;

        StickyCap(int openTag, int closeTag, Ast.Repeat inner, Ast.Repeat outer) {
            this.openTag = openTag;
            this.closeTag = closeTag;
            this.inner = inner;
            this.outer = outer;
        }
    }

    /**
     * The nested-loop inner-capture quirk (JUR lane). java.util.regex
     * compiles a greedy quantifier DIRECTLY on a capture whose body is
     * {@code study()}-deterministic (chars/classes/sequences — no choice
     * points, no nested quantifier) as {@code GroupCurly}; when that
     * construct sits inside another quantified construct (a
     * {@code Prolog}+{@code Loop}), {@code GroupCurly.match0}'s backoff
     * rewrites the capture's span on EVERY unwind and the unwinding runs
     * innermost-first — the outermost rewriting frame lands LAST. The
     * surviving span is therefore the FIRST enclosing-loop iteration that
     * matches the capture at least once (its LAST inner iteration;
     * {@code ((a)*b)*} on {@code "abab"} &rarr; g2 {@code 0..1},
     * {@code ((a)*b)*} on {@code "babab"} &rarr; g2 {@code 1..2} — the
     * first <em>writing</em> iteration), not the last iteration's
     * ({@code 2..3}/{@code 3..4}, the plain last-write-wins reading).
     * {@code ((a)?b)*} keeps the loop lineage ({@code Ques} commits no
     * rewrite frame), lazy inners likewise, and a top-level
     * {@code (a)*} runs one invocation only — none of those mark.
     *
     * <p>Marks the pair's {@link Ast.Tag}s (the fixed-tags pass then keeps
     * their registers) and records the enclosing repeats for
     * {@code Builder#stickySplit}. Family-6 pairs ({@code dissolved} /
     * {@code surfaceFinal}) never mark — their protocol overrides already
     * decide the pair's final value.
     */
    private static List<StickyCap> markStickyCaptures(Ast root) {
        List<StickyCap> caps = new ArrayList<>();
        // iterative DFS with an explicit ancestor list; Ast nodes have no
        // parent pointers and nesting depth costs heap, never JVM stack
        ArrayDeque<Object> work = new ArrayDeque<>();
        work.push(root);
        ArrayList<Ast.Repeat> ancestors = new ArrayList<>();
        ArrayDeque<Integer> depths = new ArrayDeque<>();
        depths.push(0);
        while (!work.isEmpty()) {
            Object o = work.pop();
            int depth = depths.pop();
            while (ancestors.size() > depth) {
                ancestors.remove(ancestors.size() - 1);
            }
            if (!(o instanceof Ast)) {
                continue; // a marker: unwound already via depth
            }
            Ast e = (Ast) o;
            if (e instanceof Ast.Repeat) {
                Ast.Repeat r = (Ast.Repeat) e;
                if (isStickyCap(r) && !ancestors.isEmpty()) {
                    Ast.Tag open = (Ast.Tag) ((Ast.Concat) r.body).children.get(0);
                    Ast.Tag close = (Ast.Tag) ((Ast.Concat) r.body).children.get(2);
                    open.sticky = true;
                    close.sticky = true;
                    caps.add(new StickyCap(open.tag, close.tag, ancestors.get(ancestors.size() - 1), ancestors.get(0)));
                }
                if (r.max == Integer.MAX_VALUE || r.max >= 2) {
                    ancestors.add(r); // re-invokes its body: an enclosing loop
                }
                descend(work, depths, r.body, ancestors.size());
            } else if (e instanceof Ast.Concat || e instanceof Ast.Alt) {
                descend(work, depths, e, ancestors.size());
            } // leaves: nothing below
        }
        return caps;
    }

    /** Push {@code e}'s children for the marks walk (a container), or
     *  {@code e} itself (a leaf or Repeat, handled at its own pop), each
     *  tagged with the ancestor depth it sees. */
    private static void descend(ArrayDeque<Object> work, ArrayDeque<Integer> depths, Ast e, int depth) {
        if (e instanceof Ast.Concat || e instanceof Ast.Alt) {
            List<Ast> ch = e instanceof Ast.Concat ? ((Ast.Concat) e).children : ((Ast.Alt) e).children;
            for (int i = ch.size() - 1; i >= 0; i--) {
                work.push(ch.get(i));
                depths.push(depth);
            }
        } else {
            work.push(e);
            depths.push(depth);
        }
    }

    /** The GroupCurly-capture shape: greedy, {@code max >= 2} or
     *  unbounded, directly on a capture whose inner body is
     *  deterministic (no Alt, no nested Repeat — the study()-deterministic
     *  bodies java compiles as GroupCurly) and not family-6 marked. */
    private static boolean isStickyCap(Ast.Repeat r) {
        if (!r.greedy || !(r.max == Integer.MAX_VALUE || r.max >= 2)) {
            return false;
        }
        if (!(r.body instanceof Ast.Concat)) {
            return false;
        }
        List<Ast> ch = ((Ast.Concat) r.body).children;
        if (ch.size() != 3 || !(ch.get(0) instanceof Ast.Tag) || !(ch.get(2) instanceof Ast.Tag)) {
            return false;
        }
        Ast.Tag open = (Ast.Tag) ch.get(0);
        Ast.Tag close = (Ast.Tag) ch.get(2);
        if (close.tag != open.tag + 1 || open.dissolved || open.surfaceFinal) {
            return false;
        }
        return deterministicForGroupCurly(ch.get(1));
    }

    /** No choice points, no nested quantifiers (java's Curly/GroupCurly
     *  eligibility); consuming atoms and zero-width assertions are fine. */
    private static boolean deterministicForGroupCurly(Ast e) {
        ArrayDeque<Ast> work = new ArrayDeque<>();
        work.push(e);
        while (!work.isEmpty()) {
            Ast a = work.pop();
            if (a instanceof Ast.Concat) {
                work.addAll(((Ast.Concat) a).children);
            } else if (a instanceof Ast.Alt || a instanceof Ast.Repeat) {
                return false;
            }
        }
        return true;
    }

    private static final class Builder {
        final List<int[]> eps = new ArrayList<>(); // [from, to, pri, tag, emptyMask]
        final List<int[]> syms = new ArrayList<>(); // [from, to]
        final List<CharClass> symClasses = new ArrayList<>();
        int counter = 0;
        /** Shared with the parser (see Tnfa.compile): one CPU budget for
         *  the whole front-end; every builder action ticks it. */
        final WorkMeter meter;
        /** The compile's {@link Semantics}: the plain-$ lowering pivots on
         *  END_OF_TEXT_ONLY here (ABS_END vs FINAL_END, see buildLeaf). */
        final Semantics semantics;
        /** Weighted bytes of everything minted so far (states + edges,
         *  through BudgetWeights) against the compile RAM budget. */
        long weightedBytes = 0;

        final long memBudget;

        /** Per-repeat build bookkeeping for the sticky split: the state
         *  span start (mint id when the construct began) and the
         *  iteration-entry states (the states an iteration's re-entry
         *  edge targets — the hub iterate target for loop shapes, each
         *  chained unit's entry for the zero-width-exit copies). */
        final IdentityHashMap<Ast.Repeat, RepInfo> repInfo = new IdentityHashMap<>();

        static final class RepInfo {
            int lo = -1;
            final List<Integer> entries = new ArrayList<>();
        }

        Builder(WorkMeter meter, Semantics semantics) {
            this.meter = meter;
            this.semantics = semantics;
            this.memBudget = Budgets.compileMemoryBytes();
        }

        private void charge(int bytes) {
            if ((weightedBytes += bytes) > memBudget) {
                throw new PatternTooLargeException(
                    "pattern too large: TNFA construction exceeds the compile memory budget (" + weightedBytes
                        + " weighted bytes for " + counter + " states — raise -D" + Budgets.COMPILE_MEMORY_PROP + ")");
            }
        }

        int fresh() {
            meter.tick(BudgetWeights.TNFA_BUILD_ACTION_TICKS);
            charge(BudgetWeights.TNFA_STATE_BYTES);
            return counter++;
        }

        void eps(int from, int to, int pri) {
            meter.tick(BudgetWeights.TNFA_BUILD_ACTION_TICKS);
            charge(BudgetWeights.TNFA_EPS_EDGE_BYTES);
            eps.add(new int[]{from, to, pri, NO_TAG, 0});
        }

        void taggedEps(int from, int to, int pri, int tag) {
            meter.tick(BudgetWeights.TNFA_BUILD_ACTION_TICKS);
            charge(BudgetWeights.TNFA_EPS_EDGE_BYTES);
            eps.add(new int[]{from, to, pri, tag, 0});
        }

        void anchorEps(int from, int to, int pri, int emptyMask) {
            meter.tick(BudgetWeights.TNFA_BUILD_ACTION_TICKS);
            charge(BudgetWeights.TNFA_EPS_EDGE_BYTES);
            eps.add(new int[]{from, to, pri, NO_TAG, emptyMask});
        }

        void sym(int from, int to, CharClass cc) {
            meter.tick(BudgetWeights.TNFA_BUILD_ACTION_TICKS);
            charge(BudgetWeights.TNFA_SYM_EDGE_BYTES);
            syms.add(new int[]{from, to});
            symClasses.add(cc);
        }

        /** One node in progress on the explicit build stack. DISPATCH frames
         *  carry a node to enter; the other kinds are continuations that
         *  resume when the child they spawned completes. */
        private static final class BuildFrame {
            static final int DISPATCH = 0; // entering a node
            static final int CONCAT = 1; // threading children right-to-left
            static final int ALT = 2; // branches left-to-right, ntag prefixes
            static final int QUEST = 3; // e? awaiting body entry
            static final int STAR_NULLABLE = 4; // e* with nullable body: (e+)? shape
            static final int STAR_PLAIN = 5; // e* with non-nullable body: hub loop
            static final int PLUS = 6; // e+
            static final int BOUNDED_ZWE = 7; // marked {n,m}/{n,}: zero-width-exit split copies

            int kind = DISPATCH;
            Ast node; // DISPATCH/CONCAT/ALT: the node; repeat kinds: the body
            int entryTo;
            // repeat priorities (greedy/lazy) and the state freshened pre-body
            int bodyPri, skipPri;
            int s; // QUEST: s; STAR_NULLABLE: hub; STAR_PLAIN: s0; PLUS: loopBack
            int hub; // STAR_PLAIN: the loop hub
            // the Repeat this frame builds (sticky-split bookkeeping:
            // iteration-entry states, via Builder.repInfo)
            Ast.Repeat rep;
            // CONCAT scratch: children, next index (descending), threaded entry;
            // recEntries: the desugared-bounded shape — completed children's
            // entries are iteration boundaries (sticky split)
            List<Ast> ch;
            int idx;
            int cur;
            boolean recEntries;
            // ALT scratch
            int newStart;
            List<BitSet> branchGroups;
            BitSet union;
            // BOUNDED_ZWE scratch (finding 2): the unit plan and threading
            int zweIn; // chain source awaiting an edge to the current unit's entry (-1 = none)
            int zweExit; // current unit's copy-exit node X
            int zweCtl = -1; // optional unit's controller C (enter edge pending); -1 = mandatory
            int zweEntry = -1; // the quantifier's overall entry (first unit's entry)
            int zweLo; // node-range start of the current unit's body fragment
            int zweDone; // completed units
            int zweUnits; // total units (mandatory + optional + the {n,} plus tail)
            int zweMandatory; // mandatory split units
            boolean zweTail; // {n,}: the last unit is the plus tail (no split)
            // finding 2's unbounded mirror: PLUS/STAR_NULLABLE bodies over
            // nullable-with-choice fragments split their pure-ε completions
            // to the quantifier exit (the loop guard), at the body's own
            // node-range start
            boolean zweSplit;
        }

        /** Returns entry state of sub-NFA that flows into {@code entryTo}.
         *  Explicit-frame worklist: a DISPATCH frame enters a node; each
         *  container/repeat converts to a continuation frame that resumes
         *  when the child it spawned completes. The order in which
         *  fresh()/eps()/sym()/anchorEps()/taggedEps() fire is fixed by the
         *  per-shape dispatch and resume steps below and is part of the
         *  construction contract (it determines state numbering).
         *  Continuation frames are charged to the transient compile RAM
         *  budget while they live on the stack. */
        int build(Ast root, int entryToRoot) {
            FrameBudget frames = FrameBudget.create();
            ArrayDeque<BuildFrame> stack = new ArrayDeque<>();
            int result = 0;
            pushNode(stack, root, entryToRoot);
            while (!stack.isEmpty()) {
                BuildFrame f = stack.pop();
                if (f.kind == BuildFrame.DISPATCH) {
                    Ast e = f.node;
                    int leaf = buildLeaf(e, f.entryTo);
                    if (leaf >= 0) {
                        result = leaf;
                        continue;
                    }
                    if (e instanceof Ast.Concat) {
                        List<Ast> ch = ((Ast.Concat) e).children;
                        if (ch.isEmpty()) {
                            result = f.entryTo;
                            continue;
                        }
                        f.kind = BuildFrame.CONCAT;
                        f.ch = ch;
                        f.idx = ch.size() - 1;
                        f.cur = f.entryTo;
                        frames.push();
                        stack.push(f);
                        pushNode(stack, ch.get(f.idx), f.cur);
                        continue;
                    }
                    if (e instanceof Ast.Alt) {
                        // Build all alternatives flowing into entryTo with descending priority.
                        // For POSIX (BT19 §7.3): prepend ntag (negative-tag) sub-automata to each
                        // branch for groups that exist in OTHER branches but not this one. This
                        // guarantees the U-tree prefix property and encodes "no match" structurally
                        // so POSIX comparison can pick the correct alternative.
                        List<Ast> ch = ((Ast.Alt) e).children;
                        int newStart = fresh();
                        // Compute groups per branch and union.
                        List<BitSet> branchGroups = new ArrayList<>();
                        BitSet union = new BitSet();
                        for (Ast child : ch) {
                            BitSet g = new BitSet();
                            collectGroups(child, g);
                            branchGroups.add(g);
                            union.or(g);
                        }
                        if (ch.isEmpty()) {
                            result = newStart;
                            continue;
                        }
                        f.kind = BuildFrame.ALT;
                        f.ch = ch;
                        f.idx = 0;
                        f.newStart = newStart;
                        f.branchGroups = branchGroups;
                        f.union = union;
                        frames.push();
                        stack.push(f);
                        pushNode(stack, ch.get(0), f.entryTo);
                        continue;
                    }
                    // Ast.Repeat — dispatch by shape (see the per-kind resume steps).
                    Ast.Repeat r = (Ast.Repeat) e;
                    Ast body = r.body;
                    f.rep = r;
                    RepInfo ri = repInfo.get(r);
                    if (ri == null) { // aliased synthetic repeats keep the original's span
                        ri = new RepInfo();
                        ri.lo = counter; // the construct's mint-span start
                        repInfo.put(r, ri);
                    }
                    int min = r.min, max = r.max;
                    // Greedy: prefer BODY/REPEAT (pri 1) over SKIP/EXIT (pri 2).
                    // Lazy:   prefer SKIP/EXIT   (pri 1) over BODY/REPEAT (pri 2).
                    boolean lazy = !r.greedy;
                    int bodyPri = lazy ? 2 : 1;
                    int skipPri = lazy ? 1 : 2;
                    if (min == 0 && max == 1) {
                        // e? : newStart -(pri bodyPri)-> body -> entryTo ; newStart -(pri skipPri)-> [ntags] -> entryTo
                        f.kind = BuildFrame.QUEST;
                        f.node = body;
                        f.bodyPri = bodyPri;
                        f.skipPri = skipPri;
                        f.s = fresh();
                        frames.push();
                        stack.push(f);
                        pushNode(stack, body, f.entryTo);
                        continue;
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
                        //     (re2j Prog loop()). A SPLIT entry/loop variant would let an
                        //     OUTER repeat's re-entry path (which crosses the group's
                        //     open-tag edge and arrives at this star's ENTRY node) find it
                        //     unvisited and steal the ε-closure slot, so the surviving
                        //     continuation would carry a RE-OPENED group tag — reporting the
                        //     last iteration's span for shapes like (a*?)*? on "aaa"
                        //     (g1=[2,3) where re2j reports [0,3)). The shared hub kills the
                        //     outer re-entry at an already-visited node — re2j's observable
                        //     priority — and the plain continuation wins.
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
                            f.kind = BuildFrame.STAR_NULLABLE;
                            f.node = body;
                            f.bodyPri = bodyPri;
                            f.skipPri = skipPri;
                            f.s = fresh(); // plus loop hub (at body exit)
                            f.zweSplit = r.zeroWidthExit; // finding 2's unbounded mirror
                            f.zweLo = counter;
                            frames.push();
                            stack.push(f);
                            pushNode(stack, body, f.s);
                        } else {
                            f.kind = BuildFrame.STAR_PLAIN;
                            f.node = body;
                            f.bodyPri = bodyPri;
                            f.skipPri = skipPri;
                            f.s = fresh(); // pre-loop decision: initial skip vs enter
                            f.hub = fresh(); // loop hub: iterate vs exit
                            frames.push();
                            stack.push(f);
                            pushNode(stack, body, f.hub);
                        }
                        continue;
                    }
                    if (min == 1 && max == Integer.MAX_VALUE) {
                        // e+ : body followed by e* (the e* uses the lazy/greedy preference)
                        f.kind = BuildFrame.PLUS;
                        f.node = body;
                        f.bodyPri = bodyPri;
                        f.skipPri = skipPri;
                        f.s = fresh();
                        f.zweSplit = r.zeroWidthExit; // finding 2's unbounded mirror
                        f.zweLo = counter;
                        frames.push();
                        stack.push(f);
                        pushNode(stack, body, f.s);
                        continue;
                    }
                    // Finding 2 (campaign 2026-10-16): a marked bounded repeat
                    // over a nullable-with-choice body builds through the
                    // zero-width-exit split instead of the plain structural
                    // desugar — java's Prolog+Loop guard exits after any
                    // zero-width iteration, the plain copies permit [ε, consume]
                    // paths java never explores (see BOUNDED_ZWE).
                    if (r.zeroWidthExit) {
                        f.kind = BuildFrame.BOUNDED_ZWE;
                        f.node = body;
                        f.bodyPri = bodyPri;
                        f.skipPri = skipPri;
                        f.zweTail = max == Integer.MAX_VALUE;
                        f.zweMandatory = f.zweTail ? min - 1 : min;
                        f.zweUnits = f.zweMandatory + (f.zweTail ? 1 : max - min);
                        f.zweIn = -1;
                        f.zweDone = 0;
                        frames.push();
                        stack.push(f);
                        zweNextUnit(f, stack);
                        continue;
                    }
                    // Bounded repetitions {n}, {n,}, {n,m} — desugar via concatenation + tail.
                    // (We desugar rather than implementing the paper's bounded-rep construction literally;
                    //  tags are duplicated, which is acceptable for our subset.)
                    // {0,0} falls through as bare Empty: neither the body's tags nor
                    // its ntags are emitted. Sound because a group's tags are
                    // syntactically unique (allocated at its '(' alone), so no other
                    // construction can ever write them — there is no stale value to
                    // kill, and the group simply reports NIL. The asymmetry with the
                    // quest case (which meticulously emits ntags) is deliberate: there
                    // is nothing below an empty body to poison.
                    if (min == 0 && max == 0) {
                        pushNode(stack, new Ast.Empty(), f.entryTo);
                        continue;
                    }
                    Ast.Repeat tail = null;
                    if (max == Integer.MAX_VALUE) {
                        // {n,} = (n-1) copies followed by body+  — NOT body*.
                        // re2j's Simplify general case ("x{4,} is xxxx+"): a PLUS tail
                        // guarantees one real iteration, and a nullable body's empty
                        // RE-iteration is cut by the pike pc-dedup (the plus entry pc
                        // is revisited), so the last NON-EMPTY capture survives —
                        // (a?){2,} on "aa" reports g1="a". A STAR tail instead lets
                        // the greedy first-iteration-empty write an empty capture
                        // (g1=""), diverging from re2j.
                        // min >= 2 here ({0,} star and {1,} plus have their own cases).
                        tail = new Ast.Repeat(body, 1, Integer.MAX_VALUE, r.greedy);
                    } else if (max > min) {
                        // {n,m} = mandatory + RIGHT-NESTED optional suffix (x(x(x)?)?)?,
                        // exactly re2j Simplify's shape ("x{2,5} = xx(x(x(x)?)?)?").
                        // A FLAT tail (B?B?B?) is match-equivalent but resolves
                        // the priority tie "enter the next optional copy" vs "extend the
                        // current copy's inner lazy body" the OPPOSITE way: nested-lazy
                        // captures then report the extended span while re2j/JDK report
                        // the next copy's (e.g. ((a{1,2}?c?){0,5}?)d on "aad": re2j/JDK
                        // report g2="a" — two outer iterations — a flat tail reports
                        // g2="aa").
                        tail = new Ast.Repeat(body, 0, 1, r.greedy);
                        for (int i = min + 1; i < max; i++) {
                            tail =
                                new Ast.Repeat(new Ast.Concat(Collections.unmodifiableList(Arrays.asList(body, tail))),
                                    0, 1, r.greedy);
                        }
                    }
                    // The synthetic tail re-loops the same body — the same
                    // construct's iterations for the sticky split's frontier.
                    if (tail != null) {
                        repInfo.put(tail, repInfo.get(r));
                    }
                    // Build the copies FLAT through a recording concat: every
                    // completed child except the leftmost copy is a copy ENTRY
                    // — an iteration boundary (entering copy i+1 is iteration
                    // i+1). {n,} carries min-1 copies (the plus tail is the
                    // n-th iteration and beyond); {n,m} carries min copies and
                    // the optional suffix. Pass-through otherwise: no
                    // continuation frame for the Repeat itself.
                    int copies = max == Integer.MAX_VALUE ? min - 1 : min;
                    BuildFrame cf = new BuildFrame();
                    cf.kind = BuildFrame.CONCAT;
                    cf.rep = r;
                    cf.recEntries = true;
                    cf.ch = new ArrayList<>(copies + 1);
                    for (int i = 0; i < copies; i++) {
                        cf.ch.add(body);
                    }
                    if (tail != null) {
                        cf.ch.add(tail);
                    }
                    cf.idx = cf.ch.size() - 1;
                    cf.entryTo = f.entryTo;
                    frames.push();
                    stack.push(cf);
                    pushNode(stack, cf.ch.get(cf.idx), f.entryTo);
                    continue;
                }
                if (f.kind == BuildFrame.CONCAT) {
                    // build right-to-left: the completed child's entry becomes the
                    // next (leftward) child's exit
                    if (f.recEntries && f.idx > 0) {
                        stickyEntry(f, result); // a copy/tail ENTRY: iteration boundary
                    }
                    f.cur = result;
                    f.idx--;
                    if (f.idx >= 0) {
                        stack.push(f);
                        pushNode(stack, f.ch.get(f.idx), f.cur);
                    } else {
                        result = f.cur;
                        frames.pop();
                    }
                    continue;
                }
                if (f.kind == BuildFrame.ALT) {
                    int altStart = result;
                    // Prepend ntags for missing groups (in union but not in this branch).
                    BitSet missing = (BitSet) f.union.clone();
                    missing.andNot(f.branchGroups.get(f.idx));
                    for (int g = missing.length(); (g = missing.previousSetBit(g - 1)) >= 0;) {
                        int ntagState = fresh();
                        int closeTag = 2 * g; // close tag of group g (positive number)
                        taggedEps(ntagState, altStart, 1, -closeTag); // negative = nil
                        altStart = ntagState;
                    }
                    eps(f.newStart, altStart, f.idx + 1);
                    f.idx++;
                    if (f.idx < f.ch.size()) {
                        stack.push(f);
                        pushNode(stack, f.ch.get(f.idx), f.entryTo);
                    } else {
                        result = f.newStart;
                        frames.pop();
                    }
                    continue;
                }
                if (f.kind == BuildFrame.QUEST) {
                    int bodyStart = result;
                    stickyEntry(f, bodyStart);
                    eps(f.s, bodyStart, f.bodyPri);
                    // Initial skip path: prepend ntags for body groups (0 iterations ⇒ no match).
                    int skipTarget = ntagChain(f.node, f.entryTo);
                    eps(f.s, skipTarget, f.skipPri);
                    result = f.s;
                    frames.pop();
                    continue;
                }
                if (f.kind == BuildFrame.STAR_NULLABLE) {
                    int bodyStart = result;
                    if (f.zweSplit) {
                        // finding 2's unbounded mirror: a zero-width iteration
                        // exits the star (java's Loop guard) carrying its tag
                        // writes at the body's own branch priority
                        bodyStart = zweSplit(result, f.s, f.entryTo, f.zweLo);
                    }
                    int hub = f.s;
                    eps(hub, bodyStart, f.bodyPri); // iterate (no ntag; group already matched)
                    eps(hub, f.entryTo, f.skipPri); // or exit (no ntag)
                    stickyEntry(f, bodyStart);
                    int s0 = fresh(); // quest: skip vs enter the plus
                    eps(s0, bodyStart, f.bodyPri);
                    int skipFromStart = ntagChain(f.node, f.entryTo);
                    eps(s0, skipFromStart, f.skipPri);
                    result = s0;
                    frames.pop();
                    continue;
                }
                if (f.kind == BuildFrame.STAR_PLAIN) {
                    int bodyStart = result;
                    eps(f.s, f.hub, f.bodyPri); // enter the loop (greedy) / skip (lazy)
                    int skipFromStart = ntagChain(f.node, f.entryTo);
                    eps(f.s, skipFromStart, f.skipPri);
                    eps(f.hub, bodyStart, f.bodyPri); // iterate (no ntag; group already matched)
                    eps(f.hub, f.entryTo, f.skipPri); // or exit
                    stickyEntry(f, bodyStart);
                    result = f.s;
                    frames.pop();
                    continue;
                }
                if (f.kind == BuildFrame.PLUS) {
                    int bodyStart = result;
                    if (f.zweSplit) {
                        // finding 2's unbounded mirror: a zero-width iteration
                        // exits the plus (java's Loop guard) carrying its tag
                        // writes at the body's own branch priority
                        bodyStart = zweSplit(result, f.s, f.entryTo, f.zweLo);
                    }
                    eps(f.s, bodyStart, f.bodyPri);
                    eps(f.s, f.entryTo, f.skipPri);
                    stickyEntry(f, bodyStart);
                    result = bodyStart;
                    frames.pop();
                    continue;
                }
                if (f.kind == BuildFrame.BOUNDED_ZWE) {
                    boolean tailUnit = f.zweTail && f.zweDone == f.zweUnits - 1;
                    if (tailUnit) {
                        // the {n,} plus tail's fragment entry — its own loop
                        // machinery (same-position re-entry dedup) models the
                        // guard for the unbounded iterations
                        if (f.zweIn >= 0) {
                            eps(f.zweIn, result, 1);
                            f.zweIn = -1;
                        }
                        stickyEntry(f, result); // the tail re-iterates its own entry
                        result = f.zweEntry;
                        frames.pop();
                        continue;
                    }
                    // split the just-built copy: pure-ε completions re-route to
                    // the quantifier exit, consuming paths keep the copy exit
                    int pure = zweSplit(result, f.zweExit, f.entryTo, f.zweLo);
                    if (f.zweDone > 0) {
                        stickyEntry(f, pure); // chained unit: entering it is a new iteration
                    }
                    if (f.zweCtl >= 0) {
                        eps(f.zweCtl, pure, f.bodyPri);
                        f.zweCtl = -1;
                    }
                    if (f.zweIn >= 0) {
                        eps(f.zweIn, pure, 1);
                        f.zweIn = -1;
                    } else if (f.zweEntry < 0) {
                        f.zweEntry = pure;
                    }
                    f.zweDone++;
                    if (f.zweDone >= f.zweUnits) {
                        eps(f.zweExit, f.entryTo, 1); // the last copy's exit joins the quantifier exit
                        result = f.zweEntry;
                        frames.pop();
                        continue;
                    }
                    f.zweIn = f.zweExit;
                    stack.push(f);
                    zweNextUnit(f, stack);
                    continue;
                }
                throw new IllegalStateException("unknown build frame kind: " + f.kind);
            }
            return result;
        }

        /** Leaf nodes (nothing to build below them): emit their state/edge and
         *  return the entry, or -1 when {@code e} is a container
         *  (Concat/Alt/Repeat) the caller must frame. */
        private int buildLeaf(Ast e, int entryTo) {
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
            Ast.StartAnchor sa; // ^ or \A
            if (e instanceof Ast.StartAnchor && (sa = (Ast.StartAnchor) e) != null) {
                int s = fresh();
                // Anchor flavor is per-edge (parse-time (?m), group-scoped flags
                // included): m-^ needs BEGIN_TEXT (line begin, terminator-set-
                // aware at match time); plain ^ and \A are position-0 only
                // (ABS_BEGIN).
                anchorEps(s, entryTo, 1, sa.absolute || !sa.multiline ? ABS_BEGIN : BEGIN_TEXT);
                return s;
            }
            Ast.EndAnchor ea; // $ or \z
            if (e instanceof Ast.EndAnchor && (ea = (Ast.EndAnchor) e) != null) {
                int s = fresh();
                // Anchor flavor is per-edge (parse-time (?m), group-scoped flags
                // included): m-$ needs END_TEXT (line end, terminator-set-aware);
                // \z is end-of-input only (ABS_END). Plain $ is the pivot: the
                // RE2-lineage side (END_OF_TEXT_ONLY set) reads it as \z
                // (ABS_END, end-of-input only); the java.util.regex side reads
                // it as \Z — end of input OR immediately before the final
                // terminator run — which is its own predicate (FINAL_END), not
                // expressible over the other bits.
                int bit =
                    ea.absolute ? ABS_END : ea.multiline ? END_TEXT : semantics.isEndOfTextOnly() ? ABS_END : FINAL_END;
                anchorEps(s, entryTo, 1, bit);
                return s;
            }
            if (e instanceof Ast.WordBoundary) { // \b
                int s = fresh();
                anchorEps(s, entryTo, 1, WORD_BOUNDARY);
                return s;
            }
            if (e instanceof Ast.NoWordBoundary) { // \B
                int s = fresh();
                anchorEps(s, entryTo, 1, NO_WORD_BOUNDARY);
                return s;
            }
            return -1;
        }

        /** Push a node to enter (leaf or container — decided at dispatch). */
        private static void pushNode(ArrayDeque<BuildFrame> stack, Ast e, int entryTo) {
            BuildFrame f = new BuildFrame();
            f.node = e;
            f.entryTo = entryTo;
            stack.push(f);
        }

        /** Prepend ntag (negative-tag) states for every group in {@code body}
         *  ahead of {@code target} — descending group number, so a group's
         *  no-match marker sits closer to the body it belongs to — and
         *  return the new chain head. */
        private int ntagChain(Ast body, int target) {
            BitSet bodyGroups = new BitSet();
            collectGroups(body, bodyGroups);
            for (int g = bodyGroups.length(); (g = bodyGroups.previousSetBit(g - 1)) >= 0;) {
                int ntagState = fresh();
                taggedEps(ntagState, target, 1, -(2 * g));
                target = ntagState;
            }
            return target;
        }

        /**
         * Start the next BOUNDED_ZWE unit. Mandatory units ({@code {n}}'s n
         * copies, {@code {n,}}'s first n-1) build the body into a fresh copy
         * exit X; optional units ({@code {n,m}}'s m-n suffix levels) first
         * mint a controller C with the skip edge (ntag-prefixed, like the
         * QUEST's initial skip) and chain into it. The {n,} plus tail is
         * pushed whole — its hub dedup already models the guard for the
         * unbounded iterations.
         */
        private void zweNextUnit(BuildFrame f, ArrayDeque<BuildFrame> stack) {
            boolean tailUnit = f.zweTail && f.zweDone == f.zweUnits - 1;
            if (tailUnit) {
                boolean lazy = f.bodyPri != 1;
                Ast.Repeat plus = new Ast.Repeat(f.node, 1, Integer.MAX_VALUE, !lazy);
                plus.zeroWidthExit = true; // the body qualified the outer mark; the tail re-loops it
                pushNode(stack, plus, f.entryTo);
                return;
            }
            boolean optional = f.zweDone >= f.zweMandatory;
            if (optional) {
                int c = fresh();
                eps(c, ntagChain(f.node, f.entryTo), f.skipPri);
                if (f.zweIn >= 0) {
                    eps(f.zweIn, c, 1);
                    f.zweIn = -1;
                } else if (f.zweEntry < 0) {
                    f.zweEntry = c;
                }
                f.zweCtl = c;
            }
            f.zweExit = fresh();
            f.zweLo = counter;
            pushNode(stack, f.node, f.zweExit);
        }

        /**
         * Finding 2 (campaign 2026-10-16) — the zero-width-exit split of one
         * desugared copy. java.util.regex compiles a bounded quantifier over
         * a body that can match empty through a choice point as
         * {@code Prolog}+{@code Loop}; the Loop guard {@code i >
         * locals[begin]} forces the loop to EXIT after any zero-width
         * iteration (even under cmin — the count check sits inside the
         * guard, so the exit is legal with fewer iterations too). The plain
         * structural desugar's concatenated copies admit {@code [ε, consume]}
         * paths java's backtracker never explores — the campaign's
         * {@code (\A|.?o*.){3}} matches() divergences.
         *
         * <p>The split partitions each copy's paths by zero-width
         * provenance since the copy's entry: the ε-reachable subgraph R is
         * DUPLICATED (tags and assertion masks carried — java reports the
         * exiting iteration's group spans); the duplicates' arrivals at the
         * copy exit re-route to the QUANTIFIER's exit, their consuming
         * edges rejoin the original nodes (a consumed iteration continues
         * to the next copy). The original entry is left orphaned — every
         * path starts pure at the duplicate and switches to the original
         * side on its first consuming edge.
         *
         * @param entry the copy fragment's entry (from the body build)
         * @param exit the copy's exit node X (fragment flows into it)
         * @param qExit the quantifier's exit (the Repeat's entryTo)
         * @param lo node id captured before the body build; the fragment
         *        minted [{@code lo}, {@code counter}-1] and its exit's
         *        only outside target is {@code exit}
         * @return the copy's PURE entry (the entry's duplicate), or
         *         {@code entry} when no pure-ε path reaches the exit
         *         (nothing to redirect — the guard cannot fire)
         */
        private int zweSplit(int entry, int exit, int qExit, int lo) {
            int hi = counter - 1;
            if (entry < lo || entry > hi) {
                return entry; // pass-through leaf: no interior to split
            }
            // zero-width adjacency of the fragment (all ε flavors — plain,
            // tagged, assertion-gated — are zero-width; symbol edges consume)
            int span = hi - lo + 1;
            List<int[]>[] adj = new List[span];
            int epsSnapshot = eps.size();
            for (int i = 0; i < epsSnapshot; i++) {
                meter.tick(BudgetWeights.TNFA_BUILD_ACTION_TICKS);
                int[] e = eps.get(i);
                int u = e[0];
                if (u >= lo && u <= hi) {
                    List<int[]> bucket = adj[u - lo];
                    if (bucket == null) {
                        bucket = new ArrayList<>();
                        adj[u - lo] = bucket;
                    }
                    bucket.add(e);
                }
            }
            BitSet reach = new BitSet(span);
            ArrayDeque<Integer> work = new ArrayDeque<>();
            reach.set(entry - lo);
            work.add(entry);
            boolean reachesExit = false;
            while (!work.isEmpty()) {
                int u = work.poll();
                List<int[]> edges = adj[u - lo];
                if (edges == null) {
                    continue;
                }
                for (int[] e : edges) {
                    int v = e[1];
                    if (v == exit) {
                        reachesExit = true;
                    } else if (v >= lo && v <= hi && !reach.get(v - lo)) {
                        reach.set(v - lo);
                        work.add(v);
                    } else if (v < lo || v > hi) {
                        throw new IllegalStateException("zero-width-exit split: edge leaves the copy fragment");
                    }
                }
            }
            if (!reachesExit) {
                return entry; // every copy completion consumes — guard never fires
            }
            int[] dup = new int[span];
            for (int u = reach.nextSetBit(0); u >= 0; u = reach.nextSetBit(u + 1)) {
                dup[u] = fresh();
            }
            for (int i = 0; i < epsSnapshot; i++) {
                int[] e = eps.get(i);
                int u = e[0];
                if (u < lo || u > hi || !reach.get(u - lo)) {
                    continue;
                }
                int v = e[1];
                int tv;
                if (v == exit) {
                    tv = qExit; // the redirect: a zero-width copy exits the quantifier
                } else if (v >= lo && v <= hi && reach.get(v - lo)) {
                    tv = dup[v - lo];
                } else {
                    throw new IllegalStateException("zero-width-exit split: edge leaves the copy fragment");
                }
                zwEps(dup[u - lo], tv, e[2], e[3], e[4]);
            }
            int symSnapshot = syms.size();
            for (int i = 0; i < symSnapshot; i++) {
                int u = syms.get(i)[0];
                if (u >= lo && u <= hi && reach.get(u - lo)) {
                    sym(dup[u - lo], syms.get(i)[1], symClasses.get(i));
                }
            }
            return dup[entry - lo];
        }

        /** Raw ε-edge emitter for the split's duplicates (tag + assertion
         *  mask both preserved from the original edge). */
        private void zwEps(int from, int to, int pri, int tag, int emptyMask) {
            meter.tick(BudgetWeights.TNFA_BUILD_ACTION_TICKS);
            charge(BudgetWeights.TNFA_EPS_EDGE_BYTES);
            eps.add(new int[]{from, to, pri, tag, emptyMask});
        }

        /** Record an iteration-entry state for the frame's repeat (the
         *  sticky split's invocation frontier). */
        private void stickyEntry(BuildFrame f, int entry) {
            RepInfo info = repInfo.get(f.rep);
            if (info != null) {
                info.entries.add(entry);
            }
        }

        /**
         * The nested-loop inner-capture sticky split (JUR lane; see
         * {@link #markStickyCaptures}). java's {@code GroupCurly} backoff
         * rewrites the capture's span on every unwind, outermost frame
         * last: the surviving span is the one the FIRST enclosing-loop
         * iteration that matches the capture wrote — its last inner
         * iteration — while later iterations' writes are lost to the
         * rewrite order. Last-write-wins cannot express that (the
         * first <em>writing</em> iteration is data-dependent:
         * {@code ((a)*b)*} on {@code "babab"} reports g2 {@code 1..2}),
         * so the discipline rides the automaton as a sticky bit:
         * <ul>
         * <li><b>N</b> — the graph as built. Nothing has written the pair
         *     yet; the capture's write edges fire normally and CROSS (see
         *     below). Iterations that match nothing stay here.</li>
         * <li><b>W-current</b> — clone of everything reachable from the
         *     write edges' targets within the outermost enclosing
         *     construct: the rest of the first writing invocation, writes
         *     kept (its later inner iterations still update the pair —
         *     last-write-wins WITHIN the invocation).</li>
         * <li><b>W-later</b> — clone of everything reachable from the
         *     innermost enclosing repeat's iteration entries (the
         *     invocation frontier): every later invocation, the pair's
         *     writes (and nil-clears) stripped — a later invocation can
         *     never rewrite the span.</li>
         * </ul>
         * The crossing: each original write edge keeps its tag but its
         * target becomes the W-current clone (the write fires, the path
         * continues where writes still count). W-current's edges into the
         * frontier retarget to W-later's clones; everything past the
         * outermost construct (its exit, the suffix, accept) rejoins the
         * originals — register values flow through shared states, so the
         * sticky value survives to the accept. Clones append (the
         * minting-order contract for pre-existing states is untouched),
         * and each pass reads the whole current graph — earlier passes'
         * clones included — so several sticky captures in one body
         * compose (independently; the product is charged to the compile
         * RAM budget). No-op without marks: the RE2 lane's artifacts stay
         * bit-identical.
         */
        void stickySplit(List<StickyCap> caps) {
            for (StickyCap cap : caps) {
                boolean dbg = Boolean.getBoolean("tdfa.debug");
                RepInfo in = repInfo.get(cap.inner);
                RepInfo out = repInfo.get(cap.outer);
                if (in == null || out == null || out.lo < 0 || in.entries.isEmpty()) {
                    continue;
                }
                int lo = out.lo;
                int hi = counter; // the construct + earlier passes' clones
                int nEps = eps.size();
                int nSym = syms.size();
                BitSet frontier = new BitSet();
                for (int e : in.entries) {
                    frontier.set(e);
                }
                // W-later: from the frontier, writes suppressed
                BitSet c2 = new BitSet();
                ArrayDeque<Integer> work = new ArrayDeque<>();
                for (int e = frontier.nextSetBit(lo); e >= 0 && e < hi; e = frontier.nextSetBit(e + 1)) {
                    c2.set(e);
                    work.add(e);
                }
                bfs(work, c2, lo, hi, null);
                // W-current: from the write edges' targets, stopping at the frontier
                BitSet w0 = new BitSet();
                for (int i = 0; i < nEps; i++) {
                    int[] e = eps.get(i);
                    if (e[3] == cap.openTag || e[3] == cap.closeTag) {
                        int v = e[1];
                        if (v >= lo && v < hi) {
                            w0.set(v);
                        }
                    }
                }
                BitSet c1 = new BitSet();
                work.clear();
                for (int v = w0.nextSetBit(lo); v >= 0 && v < hi; v = w0.nextSetBit(v + 1)) {
                    if (!frontier.get(v)) {
                        c1.set(v);
                        work.add(v);
                    }
                }
                bfs(work, c1, lo, hi, frontier);
                if (dbg) {
                    System.err.printf("[tdfa] sticky t%d/%d lo=%d hi=%d frontier=%s c2=%d c1=%d%n", cap.openTag,
                        cap.closeTag, lo, hi, in.entries, c2.cardinality(), c1.cardinality());
                }
                if (c1.isEmpty()) {
                    continue;
                }
                int[] dup2 = new int[hi - lo];
                int[] dup1 = new int[hi - lo];
                Arrays.fill(dup2, -1);
                Arrays.fill(dup1, -1);
                for (int u = c2.nextSetBit(lo); u >= 0; u = c2.nextSetBit(u + 1)) {
                    dup2[u - lo] = fresh();
                }
                for (int u = c1.nextSetBit(lo); u >= 0; u = c1.nextSetBit(u + 1)) {
                    dup1[u - lo] = fresh();
                }
                for (int i = 0; i < nEps; i++) {
                    int[] e = eps.get(i);
                    int u = e[0];
                    if (c2.get(u)) {
                        int v = e[1];
                        int tag = e[3];
                        if (tag == cap.openTag || tag == cap.closeTag || tag == -cap.openTag || tag == -cap.closeTag) {
                            tag = NO_TAG; // a later invocation never rewrites the pair
                        }
                        zwEps(dup2[u - lo], c2.get(v) ? dup2[v - lo] : v, e[2], tag, e[4]);
                    }
                    if (c1.get(u)) {
                        int v = e[1];
                        int tv = c1.get(v) ? dup1[v - lo] : frontier.get(v) ? dup2[v - lo] : v;
                        zwEps(dup1[u - lo], tv, e[2], e[3], e[4]);
                    }
                }
                for (int i = 0; i < nSym; i++) {
                    int[] e = syms.get(i);
                    CharClass cc = symClasses.get(i);
                    int u = e[0];
                    if (c2.get(u)) {
                        int v = e[1];
                        sym(dup2[u - lo], c2.get(v) ? dup2[v - lo] : v, cc);
                    }
                    if (c1.get(u)) {
                        int v = e[1];
                        int tv = c1.get(v) ? dup1[v - lo] : frontier.get(v) ? dup2[v - lo] : v;
                        sym(dup1[u - lo], tv, cc);
                    }
                }
                // the crossing: the original write edges jump into W-current
                for (int i = 0; i < nEps; i++) {
                    int[] e = eps.get(i);
                    if (e[3] == cap.openTag || e[3] == cap.closeTag) {
                        int v = e[1];
                        if (c1.get(v)) {
                            e[1] = dup1[v - lo];
                        }
                    }
                }
            }
        }

        /** Forward BFS over both edge flavors restricted to {@code [lo, hi)},
         *  optionally stopping at {@code stop} states (recorded per-edge by
         *  the caller's remapping, never entered). Adjacency is scanned
         *  inline: the lists are pattern-sized and this runs once per mark. */
        private void bfs(ArrayDeque<Integer> work, BitSet seen, int lo, int hi, BitSet stop) {
            int nEps = eps.size();
            int nSym = syms.size();
            while (!work.isEmpty()) {
                int u = work.poll();
                for (int i = 0; i < nEps; i++) {
                    int[] e = eps.get(i);
                    if (e[0] != u) {
                        continue;
                    }
                    int v = e[1];
                    if (v >= lo && v < hi && !seen.get(v) && (stop == null || !stop.get(v))) {
                        seen.set(v);
                        work.add(v);
                    }
                }
                for (int i = 0; i < nSym; i++) {
                    int[] e = syms.get(i);
                    if (e[0] != u) {
                        continue;
                    }
                    int v = e[1];
                    if (v >= lo && v < hi && !seen.get(v) && (stop == null || !stop.get(v))) {
                        seen.set(v);
                        work.add(v);
                    }
                }
            }
        }

        /** Collect group numbers (1-based) used anywhere in the AST subtree.
         *  Iterative worklist — visitation order is irrelevant (set union). */
        private static void collectGroups(Ast root, BitSet out) {
            ArrayDeque<Ast> work = new ArrayDeque<>();
            work.push(root);
            while (!work.isEmpty()) {
                Ast e = work.pop();
                if (e instanceof Ast.Tag) {
                    Ast.Tag t = (Ast.Tag) e;
                    int g = (t.tag + 1) / 2;
                    out.set(g);
                } else if (e instanceof Ast.Concat) {
                    work.addAll(((Ast.Concat) e).children);
                } else if (e instanceof Ast.Alt) {
                    work.addAll(((Ast.Alt) e).children);
                } else if (e instanceof Ast.Repeat) {
                    work.push(((Ast.Repeat) e).body);
                }
            }
        }

        /** One node in the iterative nullable fold. */
        private static final class NFrame {
            Ast node;
            boolean resumed;
            List<Ast> ch;
            int idx;
        }

        /**
         * Whether {@code e} can match the empty string (the syntactic analogue
         * of re2j's {@code Frag.nullable}, which drives its
         * {@code x* → (x+)?} star compilation for nullable bodies). Anchors and
         * word boundaries are zero-width, hence nullable; symbol-bearing nodes
         *  are not. Iterative fold: concat is an AND over children,
         *  alternation an OR, and a repeat with {@code min > 0} passes its
         *  body's answer through ({@code min == 0} is nullable without
         *  visiting the body); the AND/OR folds short-circuit, as the
         *  semantics permit.
         */
        private static boolean isNullable(Ast root) {
            ArrayDeque<NFrame> stack = new ArrayDeque<>();
            boolean result = true;
            NFrame f0 = new NFrame();
            f0.node = root;
            stack.push(f0);
            while (!stack.isEmpty()) {
                NFrame f = stack.pop();
                Ast e = f.node;
                if (!f.resumed) {
                    if (e instanceof Ast.Symbol || e instanceof CharClass) {
                        result = false;
                        continue;
                    }
                    if (e instanceof Ast.Repeat) {
                        if (((Ast.Repeat) e).min == 0) {
                            result = true;
                            continue;
                        }
                        f.resumed = true; // pass-through: result = body's
                        stack.push(f);
                        NFrame c = new NFrame();
                        c.node = ((Ast.Repeat) e).body;
                        stack.push(c);
                        continue;
                    }
                    if (e instanceof Ast.Concat || e instanceof Ast.Alt) {
                        boolean isConcat = e instanceof Ast.Concat;
                        f.ch = isConcat ? ((Ast.Concat) e).children : ((Ast.Alt) e).children;
                        f.idx = 0;
                        f.resumed = true;
                        if (f.ch.isEmpty()) {
                            result = isConcat;
                            continue;
                        }
                        stack.push(f);
                        NFrame c = new NFrame();
                        c.node = f.ch.get(0);
                        stack.push(c);
                        continue;
                    }
                    result = true; // Empty, Tag, anchors, word boundaries
                    continue;
                }
                if (e instanceof Ast.Concat) {
                    if (!result) {
                        continue;
                    } // AND: one non-nullable child settles it
                    f.idx++;
                    if (f.idx < f.ch.size()) {
                        stack.push(f);
                        NFrame c = new NFrame();
                        c.node = f.ch.get(f.idx);
                        stack.push(c);
                    }
                    continue; // result stays true (child was nullable)
                }
                if (e instanceof Ast.Alt) {
                    if (result) {
                        continue;
                    } // OR: one nullable child settles it
                    f.idx++;
                    if (f.idx < f.ch.size()) {
                        stack.push(f);
                        NFrame c = new NFrame();
                        c.node = f.ch.get(f.idx);
                        stack.push(c);
                    }
                    // else: result stays false (child was non-nullable)
                }
                // Repeat pass-through: result already holds the body's answer
            }
            return result;
        }

        Tnfa build(int start, int accept, int tagCount, int groupCount, boolean multiline, boolean unicodeWordBoundary,
            int[] wordRanges, Map<String, Integer> namedGroups, int[] fixedBase, int[] fixedOffset,
            boolean[] dissolvedTags, boolean[] surfaceFinalTags, boolean multiValuedTags, Semantics semantics,
            boolean hasSupplementaryStarts) {
            int n = eps.size();
            int[] eFrom = new int[n], eTo = new int[n], ePri = new int[n], eTag = new int[n], eEmpty = new int[n];
            for (int i = 0; i < n; i++) {
                int[] e = eps.get(i);
                eFrom[i] = e[0];
                eTo[i] = e[1];
                ePri[i] = e[2];
                eTag[i] = e[3];
                eEmpty[i] = e[4];
            }
            int[] sFrom = syms.stream().mapToInt(a -> a[0]).toArray();
            int[] sTo = syms.stream().mapToInt(a -> a[1]).toArray();
            CharClass[] sClass = symClasses.toArray(new CharClass[0]);
            return new Tnfa(counter, eFrom, eTo, ePri, eTag, eEmpty, sFrom, sTo, sClass, start, accept, tagCount,
                groupCount, multiline, unicodeWordBoundary, wordRanges, namedGroups, fixedBase, fixedOffset,
                dissolvedTags, surfaceFinalTags, multiValuedTags, semantics, hasSupplementaryStarts);
        }
    }
}
