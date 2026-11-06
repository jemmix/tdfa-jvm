package io.github.jemmix.tdfa.core.dfa;

import io.github.jemmix.tdfa.core.ast.Alphabet;
import io.github.jemmix.tdfa.core.emit.EmittedSurface;
import io.github.jemmix.tdfa.core.engine.ChunkSource;
import io.github.jemmix.tdfa.core.engine.MatchResult;
import io.github.jemmix.tdfa.core.engine.MatchScratch;
import io.github.jemmix.tdfa.core.engine.RegexEngine;
import io.github.jemmix.tdfa.core.engine.TagTree;
import io.github.jemmix.tdfa.core.engine.WholeEngine;
import io.github.jemmix.tdfa.core.tnfa.Semantics;
import io.github.jemmix.tdfa.core.tnfa.Tnfa;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Executes a compiled {@link Tdfa} against an input char sequence using the flat packed arrays.
 * <p>
 * Zero-width assertions ( ^ $ \A \z \b \B ) are encoded per-state (entryMask, acceptMask) and
 * per-transition (requiredMask in {@link Tdfa#ranges}). At every position we compute the set of
 * flags that hold (BEGIN_TEXT, END_TEXT, WORD_BOUNDARY, NO_WORD_BOUNDARY) and consult these
 * masks:
 * - on entering a state, {@code stateEntryMask[state]} must be a subset of positionFlags;
 * - on taking a transition, the range's {@code requiredMask} must be a subset of positionFlags;
 * - on declaring a match, {@code stateAcceptMask[state]} must be a subset of positionFlags.
 * <p>
 * Tier A optimizations + JIT-friendly shape (post-disassembly analysis):
 * - Single load per char for accept+dispatch (stateMeta packs all three)
 * - Skip int[] regs alloc when registerCount == 0
 * - Lazy accept snapshot
 * - String specialization
 */
public final class TdfaRunner implements RegexEngine, WholeEngine, StreamEngine {
    /**
     * After this many failed extract walks the candidate loop switches to a
     * boolean pre-filter per candidate (cheaper to reject, same answer).
     * Single source of truth: the ASM-emitted candidate loop bakes the same
     * value into its compare — read this constant at emit time
     * ({@code TdfaAsmBackend}), never hard-code it.
     */
    @EmittedSurface
    public static final int ADAPTIVE_PREFILTER_AFTER = 3;
    /**
     * Failed-walk budget for the literal-prefix candidate scan before it
     * falls back to the origin-sim/trigger ladder (complete search): bounds
     * dense-hit adversarial shapes (needle {@code a} on {@code aaaa…} with a
     * long tail) to a constant number of walks, keeping the ladder linear.
     * Real prefix queries (ip=-shaped) walk a handful of hits per call.
     * Single source of truth: the ASM-emitted prefix loop bakes the same
     * value at emit time — read this constant, never hard-code it.
     */
    @EmittedSurface
    public static final int PREFIX_WALK_BUDGET = 16;
    /** Literal-prefix scan outcomes: a walk hit; no further hits (no match
     *  can exist); or the walk budget spent (fall to the next ladder — a
     *  complete search). */
    private static final int PREFIX_HIT = 1;
    private static final int PREFIX_DONE = 0;
    private static final int PREFIX_EXHAUSTED = -1;
    /** Sentinel from {@link #prefixScanExtract}: the walk budget is spent —
     *  keep climbing the ladder (a complete search), do not answer null. */
    private static final MatchHolder PREFIX_BUDGET_OUT = new MatchHolder(-1, -1, null);
    /**
     * Budget-exceeded sentinel for {@link #multiStateLeftmostStart}.
     */
    public static final int LSS_BUDGET = -2;
    /**
     * Runtime walk tracing ({@code -Dtdfa.trace}). Frozen at class init
     * ON PURPOSE: it is read in the per-character walk loop, where a
     * volatile/property read would pollute the hot path — set it before
     * first use. (Compile knobs, by contrast, are read once per compile;
     * see the policy note in {@link Tdfa}.)
     */
    private static final boolean WTRACE = Boolean.getBoolean("tdfa.trace");
    /**
     * DFAs at or below this state count get 256-entry (Latin-1) lookup tables; larger stay 128.
     */
    private static final int LATIN1_MAX_STATES = 8192;
    /**
     * DFAs above this state count skip the EAGER per-state x latinLimit ASCII
     * dispatch tables (asciiTarget + asciiRangeFlat ≈ 1 KB/state at the 128-wide
     * tier) and use the binary search instead. A 234 K-state
     * DFA would otherwise retain ~228 MB of dispatch tables — more than ALL of
     * its Tdfa tables combined; every normal post-minimize DFA (e.g. dictionary:
     * 6.8 K states) stays far below the cap and keeps the direct-dispatch fast
     * paths.
     */
    private static final int ASCII_TABLE_MAX_STATES = 16_384;

    private static final ThreadLocal<ArrayList<Strategy>> TRACE_BUF = ThreadLocal.withInitial(ArrayList::new);
    // The search-DFA memo that used to live here was DELETED (see
    // docs/search-dfa-memo-tradeoffs.md): on the measured regime it bought
    // 2-7x only for codepoint-lane wide-class scans of multi-KB inputs,
    // never engaged on the default (unit-semantics) surface's bounded-ASCII
    // patterns, and the unmemoized kill-point-aware scan it fell back to
    // was already competitive with re2j everywhere on the board. The
    // trigger rung now serves every scan via that raw simulation.
    /**
     * Origin-sim budget before falling back to the trigger/restart ladder
     * below it.
     */
    private static final int LSS_BUDGET_CHARS = 4096;
    /**
     * Inputs at or below this length use the first-char-set candidate scan
     * (bit test per char + exact walk per candidate) instead of the
     * multi-state simulation. Worst case is O(len²) walk steps (dense
     * candidates, long failing walks) — 64² = 4K steps bounds it while the
     * sim stays the better shape for haystack-scale inputs.
     */
    private static final int CAND_SCAN_MAX = 64;
    /**
     * Enabled by -Dtdfa.trace.strategy=true at startup, or at runtime via
     * {@link #setTracing(boolean)} (the strategy-conformance test). Volatile
     * load + never-taken branch is ~free on the hot paths.
     */
    private static volatile boolean TRACE = Boolean.getBoolean("tdfa.trace.strategy");
    /** Forced-strategy hook state (fuzz cross-rung differential; see
     *  {@link #setForcedStrategy}). */
    private static volatile boolean anyForced;
    private static final ThreadLocal<Strategy> FORCED = new ThreadLocal<>();
    // Streaming-driver outcomes (see the streaming section below).
    private static final int SW_CONFIRMED = 0;
    private static final int SW_DIED = 1;
    private static final int SW_PENDING = 2;
    /** Tri-state mask answer for an open frontier. */
    private static final int SME_NO = 0;
    private static final int SME_YES = 1;
    private static final int SME_PEND = 2;
    /** Sentinel for an undecidable position-flags word. */
    private static final int SPF_PEND = Integer.MIN_VALUE;

    final int[] stateMeta;
    final int[] stateBase;
    final int[] ranges;
    final int[] ops;
    final int startState;
    final boolean rangesDisjoint;
    final int[] rhp; // tdfa.entryHiPrefix — prefix-max-hi per entry
    /**
     * Partial-whole side table (null = none; see Tdfa.wholeRanges): the
     * whole walk dispatches on these entries for the states in
     * {@link #wholeBase}, and on {@link #ranges} elsewhere.
     */
    final int[] wholeRanges;
    final int[] wholeBase;
    final int[] wholeCount;
    final int[] wholeRhp;

    final int stateCount;
    final int stateWords; // # of 32-bit words in state bitsets
    final int[] acceptBits; // bitset of accepting states (over-approximate)
    private final Tdfa tdfa;
    private final int[] stateFinalOpsOff;
    private final int[] stateEntryMask;
    private final int[] stateAcceptMask;
    /**
     * Position-aware final-ops table (null = uniform; see Tdfa.stateFinalOpsByMask).
     */
    private final int[] finalOpsByMask;

    private final int regSize;
    private final int startStateEntryMask;
    private final boolean longestMatch;
    private final int[] stopOnAcceptMask;
    /**
     * Uniform tier of the stop table (1 B/state) — see Tdfa.stopMaskUniform; exclusive with the above.
     */
    private final byte[] stopMaskUniform;

    /**
     * Tight 128-entry table for the SIMULATIONS: constant stride keeps the
     * hot loop's machine code compact (a 256-stride table measurably slows
     * pure-ASCII scans ~15%); codepoints >= 128 take the binary-search
     * branch.
     */
    private final int[] asciiTarget;
    /**
     * Wide Latin-1 (256-entry) table for the WALK paths (extract/matches),
     * where the doubled span buys direct dispatch on accented text. Same
     * object as asciiTarget (128) when the DFA is too large for wide tables.
     */
    private final int[] latinTarget;

    private final int[] asciiRangeFlat; // flat: [state * latinLimit + c] → range index (-1 = dead)
    /**
     * Table span in codepoints: 256 (Latin-1) normally, 128 when stateCount is
     * large enough that the doubled tables cost real memory (2 tables x
     * limit ints per state; 21K-state dictionary DFAs would pay ~42 MB at 256).
     */
    private final int latinLimit;

    private final boolean fastPath;
    /** Multi-valued artifact (BT22 §3.1): walks carry a TagTree for append ops. */
    private final boolean multi; // true = no masks + disjoint + not multiline
    /**
     * The compile's {@link Semantics}, copied from the artifact — the
     * match-time axes (terminator set, anchor EOL rules, boundary
     * discipline) belong to this tier. See {@link Semantics} for the
     * axis set, polarity, and membership rule.
     */
    private final Semantics semantics;
    /**
     * Terminator set of the UNIX_LINES axis: true = {@code \n} only (the
     * RE2-lineage side); false = the full java.util.regex set
     * ({@code \n}, {@code \r}, {@code \r\n}, U+0085, U+2028, U+2029).
     * Selects the BEGIN_TEXT/END_TEXT/FINAL_END computations.
     */
    private final boolean nlOnlyTerminators;
    /**
     * EMPTY_LAST_LINE axis (set = RE2 side): a multiline {@code ^} may
     * match the empty last line after a trailing terminator; unset
     * (java.util.regex) never does — BEGIN_TEXT dies at {@code pos == len}.
     */
    private final boolean emptyLastLine;
    /**
     * CODEPOINT_BOUNDARIES axis (set = RE2 side) OR java's
     * hasSupplementary(pattern) (frozen on the artifact): candidate scans
     * and restart loops skip surrogate-pair interiors — exactly java's
     * StartS discipline, which the RE2 lineage applies unconditionally
     * and java.util.regex applies precisely when the pattern flipped the
     * fact (any surrogate unit in the source, a supplementary/lone or
     * full-universe-case-folded single-char literal, any complement, a
     * non-BMP written range, most {@code \p{...}}). The walks themselves
     * decode codepoints either way; the find()'s own {@code from} is
     * always tried as-is (java's StartS tries the first candidate even
     * interior) — this gates only the start-position enumeration after it.
     */
    private final boolean scanSkipsPairs;
    /**
     * Whether any mask / stop-table cell consults FINAL_END (the JUR
     * plain-{@code $} predicate) — when true, positionFlags computes it.
     */
    private final boolean needsFinalEnd;
    /**
     * Derived (see {@link Tdfa#absBeginBound()}): every accepting path
     * crosses an {@code \A} gate, so no walk started past position 0 can
     * accept — the restart ladders collapse to the exact walk at the
     * origin (the root fix for the campaign 2026-10-16 finding-3
     * {@code .*}-anchor restart quadratic; subsumes the old
     * start-state-gated collapse).
     */
    private final boolean absBeginBound;
    /**
     * Cells per state in the posFlags-indexed tables (see
     * {@link Tdfa#posFlagCells()}); runtime posFlags values stay below it.
     */
    private final int posCells;
    private final boolean unicodeWordBoundary;
    private final int[] wordRanges; // Unicode \w ranges for \b when unicodeWordBoundary is true
    /**
     * Whether any mask / stop-table cell actually consults the word-boundary
     * flags — when false, positionFlags skips both word-class checks.
     */
    private final boolean needsWordFlags;
    /**
     * Word-class bitset over UTF-16 units (BMP): replaces the ASCII branch
     * chain / Unicode range binary search with one array load. Supplementary
     * codepoints still use the range search.
     */
    private final long[] wordBits;
    /**
     * First-character candidate set over UTF-16 units: a consuming match can
     * only start at p when input[p] is set (over-approximation when entries
     * carry required masks — the exact walk confirms). Built only when the
     * start state is NOT accepting (an accepting start admits zero-length
     * matches anywhere, which the candidate scan cannot see).
     */
    private final long[] startBits;
    /**
     * Accepting states whose acceptance is position-gated, packed flat as
     * {@code [state, mask, ...]} — null when no accepting state is gated
     * (the plain acceptBits intersection is then already exact). Non-null
     * switches the simulations' accept-liveness probes to the gate-aware
     * path ({@link #acceptLive}): a gated state's report is suppressed
     * while its gate cannot hold at the probed position. Two gate flavors
     * share the table: an accept mask (the walks' {@code sam} gate,
     * evaluated with the mask-directed predicate) on φ-free artifacts,
     * and the position-aware φ table's cells on artifacts that have one
     * (mask {@code -1}: the cell at the step's posFlags decides, exactly
     * as the walk's fm branch does). This is what lets the trigger's
     * products survive boundary-gated alternations ({@code
     * (.+$\D|.+ x\D+)\b}): the gate-blind sim reported those accepts
     * live at the scan's first step, and the restart ladder walked n
     * starts × O(n) walks behind it.
     */
    private final int[] maskedAcceptStates;
    /**
     * Whether any range entry carries a non-zero required mask — switches
     * the raw scan's transition stepping to the gate-aware path (a
     * candidate whose required mask cannot hold at the consumed position
     * is dead, exactly as the walks' own candidate loops treat it).
     * Mask-free artifacts keep the blind fast path.
     */
    private final boolean rangeMaskGates;
    /**
     * Eager ASCII dispatch tables present (rangesDisjoint ∧ small enough).
     */
    private final boolean asciiTables;
    /**
     * The wide-class dispatch index (see {@link WideDispatch}): O(1)
     * codepoint→range-entry lookup for wide Unicode classes, serving every
     * walk / simulation / raw-scan dispatch site the Latin-1 tables cannot
     * (codepoints at or above the Latin-1 table limit). Null when the
     * artifact does not qualify — sites then keep the binary search.
     */
    private final WideDispatch wide;

    // ===== strategy trace (conformance instrument) =====
    //
    // Records WHICH branch of the search ladder served each public entry
    // call. The interpreter records at its decision points; the ASM backend's
    // generator emits recordStrategy calls at the same points of its emitted
    // ladder (single template). The strategy-conformance test asserts both
    // backends produce identical sequences over a shape x length sweep — the
    // structural guard against the two ladders drifting (identical results
    // can hide a different algorithm).
    // Zero cost when disabled: TRACE is static final, branches prune.
    // TODO: revisit as first-class internals access (observer/event API) —
    // see TODO.md "internals access".
    /**
     * Exact-literal needle when the whole regex is a plain literal string
     * (single char-chain DFA, no captures/ops/masks): find()/leftmost-start
     * then use String.indexOf — the JIT's intrinsified, vectorized scan —
     * instead of DFA stepping. ~0.2 vs ~6.5 ns/char on ASCII haystacks.
     */
    private final String literalNeedle;
    /**
     * Required literal chain (see RunnerTables.detectPrefixChain): every
     * match must START by consuming the chain's steps, and the longest
     * case-stable run inside it is the needle — String.indexOf, the JIT's
     * intrinsified vectorized scan, enumerates exactly the possible match
     * starts (at a fixed offset when fold/class steps precede the run);
     * each hit gets an exact walk (masks, gates, tags and stop-on-accept
     * all evaluated by the walk). Null when the DFA has no usable chain,
     * or when the whole regex is a plain literal (literalNeedle already
     * serves that shape).
     */
    private final String prefixNeedle;
    /**
     * Units from the match start to {@link #prefixNeedle}'s first unit —
     * the fold/class steps ahead of the needle run, each one unit wide.
     * Zero on the v1 shape (the whole chain case-stable).
     */
    private final int prefixOffset;

    @EmittedSurface
    public TdfaRunner(Tdfa tdfa) {
        this.tdfa = tdfa;
        this.stateMeta = tdfa.stateMeta;
        this.stateBase = tdfa.stateBase;
        this.stateFinalOpsOff = tdfa.stateFinalOpsOff;
        this.finalOpsByMask = tdfa.stateFinalOpsByMask();
        this.stateEntryMask = tdfa.stateEntryMask;
        this.stateAcceptMask = tdfa.stateAcceptMask;
        this.ranges = tdfa.ranges;
        this.ops = tdfa.ops;
        this.wholeRanges = tdfa.wholeRanges;
        this.wholeBase = tdfa.wholeBase;
        this.wholeCount = tdfa.wholeCount;
        this.wholeRhp = tdfa.wholeHiPrefix;
        this.regSize = tdfa.registerCount;
        this.startState = tdfa.startState;
        this.startStateEntryMask = tdfa.startStateEntryMask;
        this.rangesDisjoint = RunnerTables.checkRangesDisjoint(tdfa);
        this.rhp = tdfa.entryHiPrefix;
        this.latinLimit = tdfa.stateCount <= LATIN1_MAX_STATES ? 256 : 128;
        this.asciiTables = rangesDisjoint && tdfa.stateCount <= ASCII_TABLE_MAX_STATES;
        if (asciiTables) {
            this.asciiRangeFlat = RunnerTables.buildAsciiRangeFlat(tdfa, latinLimit);
            this.latinTarget = RunnerTables.buildAsciiTarget(tdfa, latinLimit);
            this.asciiTarget = latinLimit == 128 ? latinTarget : RunnerTables.buildAsciiTarget(tdfa, 128);
        } else {
            this.asciiRangeFlat = null;
            this.asciiTarget = null;
            this.latinTarget = null;
        }
        this.fastPath = computeFastPath(tdfa);
        this.wide = WideDispatch.build(tdfa, rangesDisjoint);
        this.longestMatch = tdfa.longestMatch;
        this.multi = tdfa.multiValued;
        this.semantics = tdfa.semantics();
        // Frozen pivot selections (see fields): one read per axis here, none
        // in the walks.
        this.nlOnlyTerminators = semantics.isUnixLines();
        this.emptyLastLine = semantics.isEmptyLastLine();
        this.scanSkipsPairs = semantics.isCodepointBoundaries() || tdfa.hasSupplementaryStarts;
        this.posCells = tdfa.posFlagCells();
        this.stopOnAcceptMask = tdfa.stopOnAcceptMask;
        this.stopMaskUniform = tdfa.stopMaskUniform;
        this.stateCount = tdfa.stateCount;
        this.stateWords = (tdfa.stateCount + 31) >>> 5;
        this.acceptBits = RunnerTables.buildAcceptBits(tdfa);
        this.literalNeedle = RunnerTables.detectLiteralNeedle(tdfa);
        TdfaRunner.PrefixChain chain = this.literalNeedle == null ? RunnerTables.detectPrefixChain(tdfa) : null;
        this.prefixNeedle = chain == null ? null : chain.needle;
        this.prefixOffset = chain == null ? 0 : chain.offset;
        this.unicodeWordBoundary = tdfa.unicodeWordBoundary;
        this.wordRanges = tdfa.wordRanges;
        // Derived, not inferred: the tables themselves declare which posFlag bits
        // they distinguish (see Tdfa.posFlagDeps) — no per-consumer model to keep in sync.
        this.needsWordFlags = (tdfa.posFlagDeps() & (Tnfa.WORD_BOUNDARY | Tnfa.NO_WORD_BOUNDARY)) != 0;
        this.needsFinalEnd = (tdfa.posFlagDeps() & Tnfa.FINAL_END) != 0;
        // Derived (see Tdfa.absBeginBound): every accepting path crosses an
        // \A gate — restart enumeration past the origin is provably fruitless.
        this.absBeginBound = tdfa.absBeginBound();
        this.wordBits = RunnerTables.buildWordBits(tdfa.unicodeWordBoundary ? tdfa.wordRanges : null);
        this.startBits =
            (literalNeedle == null && (tdfa.stateMeta[tdfa.startState] & 1) == 0) ? buildStartBits() : null;
        this.maskedAcceptStates = buildMaskedAcceptStates();
        this.rangeMaskGates = rangeMaskGates(tdfa);
    }

    /**
     * The gated-accepting table for the simulations (see
     * {@link #maskedAcceptStates}): on φ-free artifacts every accepting
     * state whose accept mask is non-zero carries that mask; on artifacts
     * with the position-aware φ table EVERY accepting state carries
     * {@code -1} (the cell at the step's posFlags is authoritative for
     * all of them). The reads mirror the walks' own accept branches, so
     * the simulation's suppression and the walk's acceptance stay one
     * formula.
     */
    private int[] buildMaskedAcceptStates() {
        final int[] fm = this.finalOpsByMask;
        int n = 0;
        if (fm != null) {
            for (int s = 0; s < stateCount; s++) {
                if ((stateMeta[s] & 1) != 0) {
                    n++;
                }
            }
        } else if (stateAcceptMask != null) {
            for (int s = 0; s < stateCount; s++) {
                if ((stateMeta[s] & 1) != 0 && stateAcceptMask[s] != 0) {
                    n++;
                }
            }
        }
        if (n == 0) {
            return null;
        }
        int[] out = new int[n * 2];
        int i = 0;
        for (int s = 0; s < stateCount; s++) {
            if ((stateMeta[s] & 1) == 0) {
                continue;
            }
            int gate;
            if (fm != null) {
                gate = -1;
            } else if (stateAcceptMask != null && stateAcceptMask[s] != 0) {
                gate = stateAcceptMask[s];
            } else {
                continue;
            }
            out[i++] = s;
            out[i++] = gate;
        }
        return out;
    }

    /** Whether any range entry (find side — the only table the raw scans
     *  step) carries a required mask — the raw scan then gates its
     *  transitions on it (see {@link #rangeMaskGates}). */
    private static boolean rangeMaskGates(Tdfa tdfa) {
        int[] rg = tdfa.ranges;
        for (int i = 4; i < rg.length; i += 5) {
            if (rg[i] != 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Public stable hook for the ASM backend's delegate-mode decision
     * (implementation lives in RunnerTables).
     */
    public static String detectLiteralNeedle(Tdfa tdfa) {
        return RunnerTables.detectLiteralNeedle(tdfa);
    }

    /**
     * Required literal chain of the compiled DFA — its case-stable needle
     * run plus the run's offset from the match start — or null
     * (implementation lives in RunnerTables). Public stable hook: the ASM
     * backend asks at emit time so INLINED classes bake needle + offset in
     * as class constants — the emitted prefix loop and the VM's use the
     * same pair by construction.
     */
    public static PrefixChain detectPrefixChain(Tdfa tdfa) {
        return RunnerTables.detectPrefixChain(tdfa);
    }

    /**
     * End guard of an indexOf needle hit at {@code idx}: the hit must not
     * end on the high half of a surrogate pair — the walk decodes the whole
     * pair there and cannot match the needle's last unit as a lone symbol.
     * The start guard (codepoint-boundary semantics: the WALK start, which
     * sits {@code offset} units before the hit on fold-prefixed chains, is
     * not a pair interior) is evaluated separately — by the scan loops here
     * and inline in the ASM-emitted prefix loop via {@code
     * Alphabet.pairInterior}, exactly like the candidate scan's guard.
     * Public static: the emitted prefix loop binds it by name+descriptor at
     * emit time.
     */
    @EmittedSurface
    public static boolean prefixHitUsableUnit(String s, int idx, int needleLen) {
        return !RunnerTables.needleEndOverlapsPair(s, idx, needleLen);
    }

    // ===== shared candidate-scan loops =====
    //
    // Both scans enumerate exactly the positions a leftmost match can start
    // at and run an exact walk per candidate. The boolean and extract
    // ladders share them; they differ only in the walk run per candidate —
    // selected here by the runner's fastPath, since each ladder site is
    // reached in exactly one mode.

    /** Boolean single-start walk for the scan loops, mode-selected. */
    private boolean boolWalkAt(String input, int start, int to) {
        return fastPath ? matchFromFast(input, start, to) : runStringMatchFrom(input, start, to) >= 0;
    }

    /** Extract single-start walk for the scan loops, mode-selected. */
    private MatchHolder extractWalkAt(String input, int start, int to, MatchScratch sc) {
        return fastPath ? tryStartFast(input, start, to, sc) : extractFrom(input, start, to, sc);
    }

    /**
     * Literal-chain candidate scan with boolean walks: every match must
     * contain the needle at {@link #prefixOffset} units from its start, so
     * indexOf enumerates exactly the possible starts (candidate = hit −
     * offset, always ≥ searchFrom by the loop's own arithmetic) — no hits
     * means no match. The resume floor is the HIT floor (idx + 1): the
     * next candidate's hit may sit as early as idx + 1 when needle hits
     * are denser than the offset (needle "a" at offset 1 on "a☺aa"). The
     * end guard is evaluated at the hit, the start guard at the candidate;
     * word gates ride the exact walk, which evaluates them at the
     * candidate position. The walk budget bounds dense-hit adversarial
     * shapes; on exhaustion the caller falls through to a complete-search
     * ladder.
     */
    private int prefixScanBool(String input, int searchFrom, int to) {
        final String needle = this.prefixNeedle;
        final int nlen = needle.length();
        final int off = this.prefixOffset;
        int hitFloor = searchFrom + off;
        int walks = 0;
        while (true) {
            int idx = input.indexOf(needle, hitFloor);
            if (idx < 0) {
                return PREFIX_DONE;
            }
            hitFloor = idx + 1;
            int cand = idx - off;
            if (RunnerTables.needleEndOverlapsPair(input, idx, nlen)) {
                continue;
            }
            if (scanSkipsPairs && Alphabet.pairInterior(input, cand)) {
                continue;
            }
            if (boolWalkAt(input, cand, to)) {
                return PREFIX_HIT;
            }
            if (++walks > PREFIX_WALK_BUDGET) {
                return PREFIX_EXHAUSTED;
            }
        }
    }

    /**
     * Literal-chain candidate scan with extract walks: indexOf enumerates
     * exactly the possible starts (candidate = hit − offset, hit-floor
     * resume as in {@link #prefixScanBool}); after {@link
     * #ADAPTIVE_PREFILTER_AFTER} failed extract walks each usable hit gets
     * the no-allocation boolean prefilter first, then the exact extract
     * walk. Returns the match on a hit, null when no hits remain (no match
     * can exist), or {@link #PREFIX_BUDGET_OUT} when the walk budget is
     * spent.
     */
    private MatchHolder prefixScanExtract(String input, int searchFrom, int to, MatchScratch sc) {
        final String needle = this.prefixNeedle;
        final int nlen = needle.length();
        final int off = this.prefixOffset;
        int hitFloor = searchFrom + off;
        int walks = 0;
        while (true) {
            int idx = input.indexOf(needle, hitFloor);
            if (idx < 0) {
                return null;
            }
            hitFloor = idx + 1;
            int cand = idx - off;
            if (RunnerTables.needleEndOverlapsPair(input, idx, nlen)) {
                continue;
            }
            if (scanSkipsPairs && Alphabet.pairInterior(input, cand)) {
                continue;
            }
            if (walks >= ADAPTIVE_PREFILTER_AFTER && !boolWalkAt(input, cand, to)) {
                if (++walks > PREFIX_WALK_BUDGET) {
                    break;
                }
                continue;
            }
            MatchHolder h = extractWalkAt(input, cand, to, sc);
            if (h != null) {
                return h;
            }
            if (++walks > PREFIX_WALK_BUDGET) {
                break;
            }
        }
        return PREFIX_BUDGET_OUT;
    }

    /**
     * First-char-set candidate scan with boolean walks (short inputs): one
     * bit test per char, exact boolean walk per candidate. Coverage is
     * complete — startBits is only built when the start state cannot
     * accept, so every match consumes a first char carrying its bit.
     */
    private boolean candScanBool(String input, int from, int to) {
        final long[] sb = this.startBits;
        for (int p = from; p < to; p++) {
            char c = input.charAt(p);
            if ((sb[c >>> 6] >>> (c & 63) & 1L) != 0L
                && (c < 0xDC00 || !scanSkipsPairs || !Alphabet.pairInterior(input, p)) && boolWalkAt(input, p, to)) {
                return true;
            }
        }
        return false;
    }

    /**
     * First-char-set candidate scan with extract walks (short inputs);
     * after {@link #ADAPTIVE_PREFILTER_AFTER} failed extract walks the
     * no-allocation boolean walk pre-filters the remaining candidates
     * (dense-candidate no-match shapes).
     */
    private MatchHolder candScanExtract(String input, int from, int to, MatchScratch sc) {
        final long[] sb = this.startBits;
        int fails = 0;
        for (int p = from; p < to; p++) {
            char c = input.charAt(p);
            if ((sb[c >>> 6] >>> (c & 63) & 1L) == 0L) {
                continue;
            }
            if (scanSkipsPairs && c >= 0xDC00 && Alphabet.pairInterior(input, p)) {
                continue;
            }
            if (fails >= ADAPTIVE_PREFILTER_AFTER && !boolWalkAt(input, p, to)) {
                continue;
            }
            MatchHolder h = extractWalkAt(input, p, to, sc);
            if (h != null) {
                return h;
            }
            fails++;
        }
        return null;
    }

    /**
     * Record a strategy decision point (no-op unless tracing). Public: the
     * ASM backend's emitted ladder calls it from generated classes.
     */
    @EmittedSurface
    public static void trace(Strategy s) {
        if (TRACE) {
            TRACE_BUF.get().add(s);
        }
    }

    /**
     * Enable/disable strategy tracing at runtime (conformance-test hook).
     */
    public static void setTracing(boolean on) {
        TRACE = on;
    }

    /**
     * Snapshot and clear this thread's recorded strategy sequence.
     */
    public static List<Strategy> traceSnapshot() {
        ArrayList<Strategy> buf = TRACE_BUF.get();
        List<Strategy> out = Collections.unmodifiableList(new ArrayList<>(buf));
        buf.clear();
        return out;
    }

    // ===== forced-strategy hook (fuzz cross-rung differential) =====
    //
    // Test-only: starts the VM search ladder AT a given rung instead of at
    // the top — every rung at or above the forced one is skipped, rungs
    // below it still run (their natural fall-through, budgets included).
    // Only the three complete-search rungs are forceable: any of them can
    // answer any find() alone, so a forced run must return exactly what the
    // natural ladder returns — the invariant the fuzzer checks per case.
    // ORIGIN_SIM is valid only on fastPath DFAs (mask-free simulation) and
    // degrades to the trigger scan otherwise. The ASM tier ignores the hook
    // entirely: its emitted ladder is frozen at compile time, and parity is
    // preserved because both ladders must agree with the oracle anyway.
    // Zero cost when unused: one volatile read per entry call.
    /**
     * Force this thread's VM search entries to start at {@code s} (null =
     * natural ladder). Not consulted by the ASM backend or the anchored
     * entries ({@code matches()}/{@code lookingAt()}); callers must clear
     * it in a {@code finally}.
     */
    public static void setForcedStrategy(Strategy s) {
        if (s != null) {
            anyForced = true;
        }
        FORCED.set(s);
    }

    static Strategy forcedStrategy() {
        return anyForced ? FORCED.get() : null;
    }

    /**
     * Pooled register file for walk leaves: grow-only reuse of
     * {@code MatchScratch.regs} — the same carrier the interpreter's walks
     * use — shared with the ASM-generated tier, whose {@code extractOne}/
     * {@code wholeOne} leaves call this hook by name (INVOKESTATIC, no
     * receiver: monomorphic by construction). One carrier per
     * {@link io.github.jemmix.tdfa.core.engine.Matcher} (the caller owns the
     * lifetime).
     *
     * <p>Correctness contract: contents are undefined on take — callers fill
     * {@code [0, n)} before reading — and callers must clone before the
     * array escapes (walks clone into their result holder on success). The
     * returned array may be longer than {@code n} (grown by a
     * capture-heavier pattern on this carrier); only {@code [0, n)} is
     * meaningful, and downstream consumers (MatchHolder/MatchResult) index
     * within the pattern's register count, never the array length.
     */
    @EmittedSurface
    public static int[] takeRegs(int n, MatchScratch sc) {
        // sc may be null from carrier-free engines' direct callers
        // (CompiledRegex/findAll shape); allocate a fresh carrier per call.
        return (sc != null ? sc : new MatchScratch()).takeRegs(n);
    }

    /**
     * Multi-valued tag tree carrier hook: the tree the walk's append ops
     * allocate in, reset for this walk ({@link MatchScratch#takeTree()}).
     * Linked by name from generated engines ({@code extractOne}/
     * {@code wholeOne} leaves); only multi-valued artifacts call it.
     */
    @EmittedSurface
    public static TagTree takeTree(MatchScratch sc) {
        return (sc != null ? sc : new MatchScratch()).takeTree();
    }

    /**
     * One multi-valued append ({@link Tdfa#OP_APPEND_POS}):
     * {@code tree.append(head, value)} as a static hook the generated
     * engines link by name.
     */
    @EmittedSurface
    public static int appendVal(int head, int value, TagTree tree) {
        return tree.append(head, value);
    }

    /**
     * Set state {@code s} in {@code next} (if absent) with origin {@code o} in
     * {@code originNext}; min-merge if already present. {@code originNext} is
     * only read for states whose bit is set in {@code next} (implying a
     * this-step write), so stale values are never observed.
     */
    private static void setOrigin(int[] next, int[] originNext, int s, int o) {
        int w = s >>> 5, b = 1 << (s & 31);
        if ((next[w] & b) == 0) {
            next[w] |= b;
            originNext[s] = o;
        } else if (o < originNext[s]) {
            originNext[s] = o;
        }
    }

    private static void applyOps(int[] ops, int opsOff, int[] regs, int pos, TagTree tree) {
        for (int j = opsOff;; j += 3) {
            int op = ops[j];
            if (op == Tdfa.OP_END) {
                return;
            }
            int dst = ops[j + 1];
            if (op == Tdfa.OP_SET_POS) {
                regs[dst] = pos;
            } else if (op == Tdfa.OP_COPY) {
                regs[dst] = regs[ops[j + 2]];
            } else if (op == Tdfa.OP_SET_NIL) {
                regs[dst] = -1;
            } else if (op == Tdfa.OP_APPEND_POS) {
                // multi-valued artifacts only (validated at construction);
                // tree is non-null exactly for those walks
                regs[dst] = tree.append(regs[ops[j + 2]], pos);
            } else {
                regs[dst] = -1;
            }
        }
    }

    /**
     * Wide-class dispatch (see {@link #wide}): the range-entry index for
     * {@code cp} in {@code state} — {@code -1} dead, {@link
     * Integer#MIN_VALUE} state not indexed (caller keeps its binary
     * search). Exact wherever built: disjoint ranges admit at most one
     * containing entry, so this resolves precisely the entry the binary
     * search finds; masks, dead markers, and ops stay in the entry.
     */
    private int wideRangeIndex(int state, int cp) {
        return wide.rangeIndex(state, cp);
    }

    /**
     * The containing entry's target for {@code cp} in state {@code s} by
     * binary search ({@code -1} dead or absent) — the disjoint artifacts'
     * dispatch, shared by the simulations' non-indexed states and the
     * paths with no wide index (see {@link WideDispatch}). Fast-path
     * callers guarantee pairwise-disjoint ranges, so the one entry the
     * search finds is the whole answer.
     */
    private int disjointTarget(int s, int c) {
        final int[] rg = this.ranges;
        int base = stateBase[s];
        int rlo = 0, rhi = ((stateMeta[s] >>> 1) & 0xFFFF) - 1;
        while (rlo <= rhi) {
            int mid = (rlo + rhi) >>> 1;
            int mo = (base + mid) * 5;
            if (c < rg[mo]) {
                rhi = mid - 1;
            } else if (c > rg[mo + 1]) {
                rlo = mid + 1;
            } else {
                return rg[mo + 2];
            }
        }
        return -1;
    }

    @EmittedSurface
    @Override
    public int groupCount() {
        return tdfa.groupCount;
    }

    @EmittedSurface
    @Override
    public Map<String, Integer> namedGroups() {
        return tdfa.namedGroups;
    }

    // ===== Lazy search-DFA (trigger scan with kill-point windows) =====

    @EmittedSurface
    @Override
    public int programSize() {
        return tdfa.stateCount;
    }

    @EmittedSurface
    @Override
    public boolean matches(CharSequence input) {
        if (input instanceof String) {
            String s = (String) input;
            if (fastPath) {
                trace(Strategy.ANCHORED_FAST);
                return runStringAnchoredFast(s);
            }
            trace(Strategy.ANCHORED);
            return runStringAnchored(s) >= 0;
        }
        trace(Strategy.GENERIC);
        return runGeneric(input, 0, input.length(), true, new MatchScratch()) != null;
    }

    @EmittedSurface
    @Override
    public boolean find(CharSequence input) {
        if (input instanceof String) {
            String s = (String) input;
            Strategy force = forcedStrategy();
            if (force != null) {
                return forcedSearch(s, 0, s.length(), new MatchScratch(), force) != null;
            }
            int len = s.length();
            if (literalNeedle != null) {
                trace(Strategy.LITERAL);
                return RunnerTables.literalIndexOf(s, literalNeedle, 0, scanSkipsPairs) >= 0;
            }
            MatchScratch sc = new MatchScratch();
            if (fastPath) {
                return runStringFindFast(s, len, sc);
            }
            int maxStart = absBeginBound ? 0 : len;
            if (maxStart > 0) {
                // One exact walk from 0 first: for prefix-chain DFAs (e.g.
                // \p{L}{256}) a match at/near 0 answers in O(len) while the
                // trigger's raw-scan pre-check is O(len^2) in live-set size.
                trace(Strategy.EXACT_FROM);
                if (runStringMatchFrom(s, 0, len) >= 0) {
                    return true;
                }
                // Literal-prefix candidate scan (see prefixScanBool): no
                // hits ⇒ no match. Budget exhaustion falls through to the
                // trigger scan + restart loop below.
                if (prefixNeedle != null) {
                    trace(Strategy.PREFIX);
                    int pr = prefixScanBool(s, 1, len);
                    if (pr == PREFIX_HIT) {
                        return true;
                    }
                    if (pr == PREFIX_DONE) {
                        return false;
                    }
                }
                // Short inputs: first-char-set candidate scan with exact
                // (mask-aware) walks instead of the raw-scan simulation.
                if (startBits != null && len <= CAND_SCAN_MAX) {
                    trace(Strategy.CAND_SCAN);
                    return candScanBool(s, 1, len);
                }
                // The trigger scan and the origin sim step CODEPOINTS; under
                // unit semantics (CODEPOINT_BOUNDARIES unset) the unit twin
                // of the raw scan carries the interior-unit starts and
                // bounds the complete restart loop the same way.
                int w = scanSkipsPairs ? triggerScan(s, 0, len, sc) : unitTrigger(s, 0, len, sc);
                if (w < 0) {
                    return false;
                }
                trace(Strategy.WALK_RESTART);
                for (int from = Math.max(w, 1); from <= maxStart; from++) {
                    if (scanSkipsPairs && Alphabet.pairInterior(s, from)) {
                        continue;
                    }
                    int res = runStringMatchFrom(s, from, len);
                    if (res >= 0) {
                        return true;
                    }
                }
                return false;
            }
            trace(Strategy.EXACT_FROM);
            return runStringMatchFrom(s, 0, len) >= 0;
        }
        trace(Strategy.GENERIC);
        return runGeneric(input, 0, input.length(), false, new MatchScratch()) != null;
    }

    /**
     * Carrier-aware match: {@code sc} holds this call's reusable buffers
     * (see {@link MatchScratch}); a {@link io.github.jemmix.tdfa.core.engine.Matcher}
     * passes its own carrier so iteration pools across calls, carrier-less
     * callers may pass null (a fresh carrier is allocated on demand).
     */
    @EmittedSurface
    @Override
    public MatchResult match(CharSequence input, int from, MatchScratch sc) {
        // Interface contract (see RegexEngine.match): clean bounds failure,
        // never the walk's raw StringIndexOutOfBoundsException.
        if (from < 0 || from > input.length()) {
            throw fromOutOfBounds(from, input.length());
        }
        if (sc == null) {
            sc = new MatchScratch();
        }
        MatchHolder h;
        if (input instanceof String) {
            String s = (String) input;
            Strategy force = forcedStrategy();
            h = force != null ? forcedSearch(s, from, s.length(), sc, force)
                : fastPath ? runStringExtractFast(s, from, s.length(), sc) : runStringExtract(s, from, s.length(), sc);
        } else {
            trace(Strategy.GENERIC);
            h = runGeneric(input, from, input.length(), false, sc);
        }
        if (h == null) {
            return null;
        }
        if (tdfa.fixedBase != null) {
            MatchResult.reconstructFixed(h.regs, tdfa.finalRegBase, tdfa.fixedBase, tdfa.fixedOffset);
        }
        return new MatchResult(h.regs, tdfa.finalRegBase, tdfa.groupCount, h.matchStart, h.matchEnd, h.tree);
    }

    /**
     * The shared out-of-range {@code from} failure: the interpreter checks
     * inline, and the generated INLINED match ladders link this hook on their
     * cold branch so both tiers throw the identical polite message instead
     * of the raw walk failure (a beyond-length {@code from} would otherwise
     * surface as a corrupt match or a wrong {@code null}).
     */
    @EmittedSurface
    public static IndexOutOfBoundsException fromOutOfBounds(int from, int len) {
        return new IndexOutOfBoundsException("from: " + from + ", length: " + len);
    }

    /**
     * Whole-input match ({@link WholeEngine#matchWhole}): anchored at 0, runs
     * to end-of-input, and succeeds iff an accept config is ALIVE exactly at
     * EOF — mid-walk accepts (multiline {@code $}, unanchored prefixes) are
     * stepped past, never recorded. Requires transitions that keep
     * full-match continuations past an earlier higher-priority accept
     * ({@code (a|ab)} on {@code "ab"}): either a cut-free artifact
     * ({@link io.github.jemmix.tdfa.core.determinize.Determinizer#compileUnpruned}), a pruned artifact whose cut never
     * fired, or a pruned artifact with its partial-whole side table — the
     * walk then dispatches on the side entries of the states whose cut
     * deleted continuations ({@link Tdfa#wholeRanges}).
     *
     * <p>Transition machinery is a verbatim transplant of {@link #extractFrom}'s
     * (flat dispatch / binary search / most-specific-mask ownership with dead
     * markers, entry masks checked before ops run); only the accept protocol
     * differs: no stop table, one gate + φ application at EOF.
     *
     * <p>Carrier-aware whole match — see {@link #match(CharSequence, int, MatchScratch)}
     * for the reuse contract.
     */
    @EmittedSurface
    @Override
    public MatchResult matchWhole(CharSequence input, MatchScratch sc) {
        trace(input instanceof String ? Strategy.ANCHORED : Strategy.GENERIC);
        if (sc == null) {
            sc = new MatchScratch();
        }
        MatchHolder h = wholeWalk(input, 0, input.length(), sc);
        if (h == null) {
            return null;
        }
        if (tdfa.fixedBase != null) {
            MatchResult.reconstructFixed(h.regs, tdfa.finalRegBase, tdfa.fixedBase, tdfa.fixedOffset);
        }
        return new MatchResult(h.regs, tdfa.finalRegBase, tdfa.groupCount, h.matchStart, h.matchEnd, h.tree);
    }

    /**
     * Whole-walk (String and generic CharSequence); null = not a full match. See {@link #matchWhole}.
     */
    private MatchHolder wholeWalk(CharSequence input, int from, int to, MatchScratch sc) {
        final int[] sm = this.stateMeta;
        final int[] rg = this.ranges;
        final int[] op = this.ops;
        final int[] sem = this.stateEntryMask;
        final int[] sam = this.stateAcceptMask;
        final int[] arf = this.asciiRangeFlat; // non-null iff rangesDisjoint
        final int limit = this.latinLimit;
        final int[] regs;
        final TagTree tree = multi ? takeTree(sc) : null;
        if (regSize == 0) {
            regs = null;
        } else {
            regs = takeRegs(regSize, sc);
            Arrays.fill(regs, 0, regSize, -1);
        }
        int state = startState;
        int pos = from;

        // Entry check for start state — inline
        {
            int entryReq = sem[state];
            if (entryReq != 0 && !maskSatisfiedCS(input, pos, to, entryReq)) {
                return null;
            }
        }

        int posFlags = -1;
        final int[] wrg = this.wholeRanges;
        final int[] wBase = this.wholeBase;
        final int[] wCount = this.wholeCount;
        final int[] wRhp = this.wholeRhp;
        while (pos < to) {
            int c = Alphabet.decode(input, pos, to);
            // Partial-whole side table: states whose pike cut deleted
            // continuations dispatch on their UNCUT whole relation; every
            // other state's whole relation IS the pruned one.
            final int[] table;
            final int[] tableRhp;
            int base, count;
            boolean wholeState = wrg != null && wBase[state] >= 0;
            if (wholeState) {
                table = wrg;
                tableRhp = wRhp;
                base = wBase[state];
                count = wCount[state];
            } else {
                table = rg;
                tableRhp = rhp;
                base = stateBase[state];
                count = (sm[state] >>> 1) & 0xFFFF;
            }
            int chosen = -1, chosenTarget = 0;
            int ri;
            if (wholeState) {
                ri = Integer.MIN_VALUE; // flat/walk tables index the pruned layout
            } else if (arf != null && c < limit) {
                // Disjoint ranges: at most one entry contains c, so entry
                // priority is moot and the flat table is exact.
                ri = arf[state * limit + c];
            } else if (wide != null) {
                // Same exactness contract as the flat table above (see
                // WideDispatch); MIN_VALUE = not indexed → binary search.
                ri = wideRangeIndex(state, c);
            } else {
                ri = Integer.MIN_VALUE;
            }
            if (ri == Integer.MIN_VALUE) {
                // Binary search rightmost entry with lo <= c, then walk back
                // while the per-state prefix-max-hi still reaches c; the MOST
                // SPECIFIC satisfied mask owns the step (see extractFrom).
                int rlo = 0, rhi = count - 1, anchor = -1;
                while (rlo <= rhi) {
                    int mid = (rlo + rhi) >>> 1;
                    if (table[(base + mid) * 5] <= c) {
                        anchor = mid;
                        rlo = mid + 1;
                    } else {
                        rhi = mid - 1;
                    }
                }
                int best = -1, bestSpec = -1;
                for (int i = anchor; i >= 0 && tableRhp[base + i] >= c; i--) {
                    int o = (base + i) * 5;
                    if (c <= table[o + 1]) {
                        int requiredMask = table[o + 4];
                        if (requiredMask != 0) {
                            if (posFlags < 0) {
                                posFlags = positionFlagsCS(input, pos, to);
                            }
                            if ((posFlags & requiredMask) != requiredMask) {
                                continue;
                            }
                        }
                        int spec = Integer.bitCount(requiredMask);
                        if (spec >= bestSpec) {
                            best = i;
                            bestSpec = spec;
                        } // >= : lower index wins ties
                    }
                }
                if (best >= 0) {
                    int o = (base + best) * 5;
                    int target = table[o + 2];
                    if (target < 0) {
                        return null;
                    } // dead marker of the owning context
                    chosen = o;
                    chosenTarget = target;
                }
            } else if (ri >= 0) {
                int o = (base + ri) * 5;
                int target = table[o + 2];
                if (target >= 0) {
                    int requiredMask = table[o + 4];
                    boolean ok = requiredMask == 0;
                    if (!ok) {
                        if (posFlags < 0) {
                            posFlags = positionFlagsCS(input, pos, to);
                        }
                        ok = (posFlags & requiredMask) == requiredMask;
                    }
                    if (ok) {
                        chosen = o;
                        chosenTarget = target;
                    }
                }
            }
            if (chosen < 0) {
                return null;
            } // dead: no full match through this prefix
              // Target entry mask is a position predicate, evaluated BEFORE the
              // transition's ops run (see extractFrom).
            int width = c > 0xFFFF ? 2 : 1;
            int entryReqNext = sem[chosenTarget];
            if (entryReqNext != 0 && !maskSatisfiedCS(input, pos + width, to, entryReqNext)) {
                return null;
            }
            if (regs != null) {
                int opsOff = table[chosen + 3];
                if (opsOff != 0) {
                    applyOps(op, opsOff, regs, pos, tree);
                }
            }
            state = chosenTarget;
            if (width == 2) {
                pos++;
            }
            pos++;
            posFlags = -1;
        }
        // EOF: an alive accept config here consumed exactly [from, to) — a
        // full match. Gate and apply the winner's φ reading EOF-time registers.
        if ((sm[state] & 1) == 0) {
            return null;
        }
        int eofFlags = positionFlagsCS(input, to, to);
        final int[] fm = this.finalOpsByMask;
        if (fm != null) {
            int cell = fm[state * posCells + eofFlags];
            if (cell < 0) {
                return null;
            } // no accept config alive under these posFlags
            if (regs != null && cell != 0) {
                applyOps(op, cell, regs, to, tree);
            }
        } else {
            int acceptMask = sam[state];
            if (acceptMask != 0 && (eofFlags & acceptMask) != acceptMask) {
                return null;
            }
            if (regs != null) {
                applyFinalOps(state, regs, to, tree);
            }
        }
        return new MatchHolder(from, to, regs == null ? new int[0] : regs.clone(), multi ? tree.snapshot() : null);
    }

    /**
     * String find with anchor enforcement and register extraction.
     */
    private MatchHolder runStringExtract(String input, int from, int to, MatchScratch sc) {
        int maxStart = absBeginBound ? 0 : to;
        // One exact walk from `from` first (match at/near from is the common
        // case and answers in O(len) — cheaper than any pre-check; see find()).
        {
            trace(Strategy.EXACT_FROM);
            MatchHolder direct = extractFrom(input, from, to, sc);
            if (direct != null) {
                return direct;
            }
        }
        // Literal-prefix candidate scan (see prefixScanExtract): no hits ⇒
        // no match. Budget exhaustion falls through to the trigger scan,
        // which is a complete search.
        if (maxStart > 0 && prefixNeedle != null) {
            trace(Strategy.PREFIX);
            MatchHolder hp = prefixScanExtract(input, from + 1, to, sc);
            if (hp != PREFIX_BUDGET_OUT) {
                return hp; // the hit, or no hits at all — either way decided
            }
        }
        // Short inputs: first-char-set candidate scan with exact (mask-aware)
        // walks instead of the trigger scan + restart loop. Zero-length
        // matches are impossible here (startBits is only built when the start
        // state cannot accept), so bit coverage is complete.
        if (maxStart > 0 && startBits != null && to - from <= CAND_SCAN_MAX) {
            trace(Strategy.CAND_SCAN);
            return candScanExtract(input, from + 1, to, sc);
        }
        // Trigger scan: multi-state pass that both proves no-match and
        // bounds the restart loop to the kill-point window (no configuration
        // alive before W can produce a match). Under unit semantics the
        // codepoint-stepped proof would miss interior-unit starts, so the
        // unit twin of the raw scan serves instead — same two products
        // (no-match proof, kill-point window) with the interior seeds
        // folded in (see unitRawScan).
        if (maxStart > 0) {
            int w = scanSkipsPairs ? triggerScan(input, from, to, sc) : unitTrigger(input, from, to, sc);
            if (w < 0) {
                return null;
            }
            if (w > from + 1) {
                from = w - 1;
            }
        }
        trace(Strategy.WALK_RESTART);
        for (int startSearch = from + 1; startSearch <= maxStart; startSearch++) {
            if (scanSkipsPairs && Alphabet.pairInterior(input, startSearch)) {
                continue;
            }
            if (WTRACE) {
                System.err.println("[walk] === start " + startSearch);
            }
            MatchHolder h = extractFrom(input, startSearch, to, sc);
            if (h != null) {
                return h;
            }
        }
        return null;
    }

    /**
     * Forced-strategy search (fuzz cross-rung differential, see
     * {@link #setForcedStrategy}): answers the same question as the two
     * ladders above but ENTERS at the forced rung, keeping the natural
     * fall-through below it. Every path must return exactly what the
     * natural ladder returns — that equivalence is the invariant the fuzzer
     * asserts per case.
     */
    private MatchHolder forcedSearch(String input, int from, int to, MatchScratch sc, Strategy force) {
        if (sc == null) {
            sc = new MatchScratch();
        }
        switch (force) {
            case ORIGIN_SIM :
                if (fastPath && scanSkipsPairs) {
                    // Verbatim rung 2 of runStringExtractFast: budgeted sim
                    // with the live-set handoff to the trigger on exhaustion
                    // (one pass, see originSimRungTop), exact walk, defensive
                    // restart. (The prefix/candidate scans above it are
                    // skipped — the sim is a complete search on its own.)
                    trace(Strategy.ORIGIN_SIM);
                    int leftmost = originSimRungTop(input, from, to, LSS_BUDGET_CHARS, sc);
                    if (leftmost < 0) {
                        return null;
                    }
                    MatchHolder h = tryStartFast(input, leftmost, to, sc);
                    if (h != null) {
                        return h;
                    }
                    trace(Strategy.WALK_RESTART);
                    return restartExtract(input, leftmost + 1, to, from, sc);
                }
                // The mask-free sim is fastPath-only; degrade to the trigger
                // scan (the generic ladder's complete search).
                // fall through
            case RAW_SCAN : {
                // The lane's own trigger rung: the codepoint memo under
                // CODEPOINT_BOUNDARIES, the unit raw scan (interior seeds,
                // see unitTrigger) under unit semantics — both bound the
                // restart loop to the kill-point window below, mirroring
                // the natural ladder's routing.
                int w = scanSkipsPairs ? triggerScan(input, from, to, sc) : unitTrigger(input, from, to, sc);
                if (w < 0) {
                    return null;
                }
                // The caller's `from` gets the same unguarded exact walk the
                // natural ladders give it first (mid-pair starts included —
                // find(int) can land there); the restart loop then resumes at
                // max(from+1, w), mirroring the generic ladder's
                // `from = w-1; loop from from+1` — which never walks w-1
                // itself, a potential pair interior.
                trace(Strategy.EXACT_FROM);
                MatchHolder h = fastPath ? tryStartFast(input, from, to, sc) : extractFrom(input, from, to, sc);
                if (h != null) {
                    return h;
                }
                trace(Strategy.WALK_RESTART);
                int maxStart = absBeginBound ? from : to;
                for (int s = Math.max(from + 1, w); s <= maxStart; s++) {
                    if (scanSkipsPairs && Alphabet.pairInterior(input, s)) {
                        continue;
                    }
                    h = fastPath ? tryStartFast(input, s, to, sc) : extractFrom(input, s, to, sc);
                    if (h != null) {
                        return h;
                    }
                }
                return null;
            }
            case WALK_RESTART :
            default :
                return forcedRestart(input, from, to, sc);
        }
    }

    /**
     * The restart floor under a forced entry: an exact walk at every viable
     * start. {@code from} itself is walked even when it sits inside a
     * surrogate pair, mirroring the EXACT_FROM-at-from call every natural
     * ladder makes; later starts keep the pair-interior guard. Viable is
     * decided by the kill-point window — the same scan every natural
     * restart site consults ({@link #triggerScan}/{@link #unitTrigger},
     * the #76/#77 bound): no accept live anywhere proves no match at all,
     * and every surviving configuration started at or after {@code W}, so
     * starts below {@code W} are provably dead walks. Without it the floor
     * is n restarts x O(n) walks — the campaign 2026-10-24 finding-4
     * family ($/\B/^ gates after unbounded class-plus on stretched
     * no-match inputs) spent 20–36 s here per find, tripping the 10 s
     * watchdog as HANG_ENGINE; the windowed floor answers in one scan
     * plus the surviving window's walks.
     */
    private MatchHolder forcedRestart(String input, int from, int to, MatchScratch sc) {
        trace(Strategy.WALK_RESTART);
        MatchHolder h = fastPath ? tryStartFast(input, from, to, sc) : extractFrom(input, from, to, sc);
        int maxStart = absBeginBound ? from : to;
        if (h != null || maxStart == from) {
            return h;
        }
        int w = scanSkipsPairs ? triggerScan(input, from, to, sc) : unitTrigger(input, from, to, sc);
        if (w < 0) {
            return null;
        }
        for (int s = Math.max(from + 1, w); s <= maxStart; s++) {
            if (scanSkipsPairs && Alphabet.pairInterior(input, s)) {
                continue;
            }
            h = fastPath ? tryStartFast(input, s, to, sc) : extractFrom(input, s, to, sc);
            if (h != null) {
                return h;
            }
        }
        return null;
    }

    /**
     * One exact single-start walk; null = no match starting at startSearch.
     */
    private MatchHolder extractFrom(String input, int startSearch, int to, MatchScratch sc) {
        final int[] sm = this.stateMeta;
        final int[] rg = this.ranges;
        final int[] op = this.ops;
        final int[] sem = this.stateEntryMask;
        final int[] sam = this.stateAcceptMask;
        final int[] arf = this.asciiRangeFlat; // non-null iff rangesDisjoint
        final int limit = this.latinLimit;
        // Pooled regs (per-call carrier): the candidate-scan loops call this
        // per candidate and most walks fail — no allocation on that path. The
        // success path clones (line below) before returning, so the pool is
        // never handed out.
        final int[] regs;
        final TagTree tree = multi ? takeTree(sc) : null;
        if (regSize == 0) {
            regs = null;
        } else {
            regs = takeRegs(regSize, sc);
            Arrays.fill(regs, 0, regSize, -1);
        }
        int state = startState;
        int lastAcceptPos = -1;
        boolean haveAccept = false;
        int pos = startSearch;

        // Entry check for start state — inline
        {
            int entryReq = sem[state];
            if (entryReq != 0 && !maskSatisfied(input, pos, to, entryReq)) {
                return null;
            }
        }

        int posFlags = -1;
        for (;; pos++) {
            int meta = sm[state];
            if (WTRACE) {
                System.err.println("[walk] pos=" + pos + " state=" + state + " accept=" + ((meta & 1) != 0));
            }
            if ((meta & 1) != 0) {
                final int[] fm = this.finalOpsByMask;
                if (fm != null) {
                    // Position-aware table is authoritative: cell >= 0 = an
                    // accept config is alive under these posFlags (the gate
                    // the sam-intersection only approximates), cell = its φ.
                    if (posFlags < 0) {
                        posFlags = positionFlags(input, pos, to);
                    }
                    int cell = fm[state * posCells + posFlags];
                    if (WTRACE) {
                        System.err.println("[walk]   fmCell=" + cell + " M=" + Integer.toBinaryString(posFlags));
                    }
                    if (cell >= 0) {
                        lastAcceptPos = pos;
                        haveAccept = true;
                        if (regs != null && cell != 0) {
                            applyOps(op, cell, regs, pos, tree);
                        }
                        if (!longestMatch && stopNow(state, posFlags)) {
                            break;
                        }
                    }
                } else {
                    int acceptMask = sam[state];
                    if (acceptMask == 0) {
                        lastAcceptPos = pos;
                        haveAccept = true;
                        if (regs != null) {
                            applyFinalOps(state, regs, pos, tree);
                        }
                        if (!longestMatch) {
                            // stopNow ignores posFlags when the stop table is
                            // uniform (assertion-free) — skip the 2×charAt +
                            // word lookups; other readers recompute lazily.
                            if (posFlags < 0 && stopMaskUniform == null) {
                                posFlags = positionFlags(input, pos, to);
                            }
                            if (stopNow(state, posFlags)) {
                                break;
                            }
                        }
                    } else {
                        if (posFlags < 0) {
                            posFlags = positionFlags(input, pos, to);
                        }
                        if ((posFlags & acceptMask) == acceptMask) {
                            if (WTRACE) {
                                System.err.println("[walk]   sam accept M=" + Integer.toBinaryString(posFlags)
                                    + " stop=" + stopNow(state, posFlags));
                            }
                            lastAcceptPos = pos;
                            haveAccept = true;
                            if (regs != null) {
                                applyFinalOps(state, regs, pos, tree);
                            }
                            if (!longestMatch && stopNow(state, posFlags)) {
                                break;
                            }
                        }
                    }
                }
            }
            if (pos >= to) {
                break;
            }
            int c = Alphabet.decode(input, pos, to);
            int base = stateBase[state];
            int count = (meta >>> 1) & 0xFFFF;
            int chosen = -1, chosenTarget = 0;
            int ri;
            if (arf != null && c < limit) {
                // Disjoint ranges: at most one entry contains c, so entry
                // priority is moot and the flat table is exact.
                ri = arf[state * limit + c];
            } else if (wide != null) {
                // Wide dispatch: exact under the same disjointness contract
                // (see WideDispatch); MIN_VALUE = state not indexed → the
                // binary search below (overlapping masked artifacts).
                ri = wideRangeIndex(state, c);
            } else {
                ri = Integer.MIN_VALUE;
            }
            if (ri == Integer.MIN_VALUE) {
                // Binary search rightmost entry with lo <= c, then walk back
                // while the per-state prefix-max-hi still reaches c: visits
                // exactly the entries that can contain c, in
                // lowest-index-first priority order.
                int rlo = 0, rhi = count - 1, anchor = -1;
                while (rlo <= rhi) {
                    int mid = (rlo + rhi) >>> 1;
                    if (rg[(base + mid) * 5] <= c) {
                        anchor = mid;
                        rlo = mid + 1;
                    } else {
                        rhi = mid - 1;
                    }
                }
                // Walk back over containing entries. Ownership: the MOST
                // SPECIFIC satisfied mask wins (popcount of requiredMask);
                // ties fall to the lowest index (determinizer order). Pure
                // "lowest index" is wrong once overlapping contexts' ranges
                // differ in lo — a more-specific (e.g. dead-marker) entry at
                // a HIGHER lo/index gets shadowed by a broad mask-0 range
                // (e.g. .+?\b[^\d]* extending past its \b-gated accept
                // through the lazy body's '.' entry).
                int best = -1, bestSpec = -1;
                for (int i = anchor; i >= 0 && rhp[base + i] >= c; i--) {
                    int o = (base + i) * 5;
                    if (c <= rg[o + 1]) {
                        int requiredMask = rg[o + 4];
                        if (requiredMask != 0) {
                            if (posFlags < 0) {
                                posFlags = positionFlags(input, pos, to);
                            }
                            if ((posFlags & requiredMask) != requiredMask) {
                                continue;
                            }
                        }
                        int spec = Integer.bitCount(requiredMask);
                        if (spec >= bestSpec) {
                            best = i;
                            bestSpec = spec;
                        } // >= : lower index wins ties
                    }
                }
                if (best >= 0) {
                    int o = (base + best) * 5;
                    int target = rg[o + 2];
                    if (target < 0) {
                        // Dead marker of the OWNING context (lowest satisfied):
                        // no continuation exists under this posFlags — lower-
                        // specificity ranges belong to contexts not alive here.
                        if (WTRACE) {
                            System.err.println("[walk]   c=" + Integer.toHexString(c) + " DEAD idx " + best + " mask="
                                + Integer.toBinaryString(rg[o + 4]) + " (M=" + Integer.toBinaryString(posFlags) + ")");
                        }
                        break;
                    }
                    if (WTRACE) {
                        System.err.println("[walk]   c=" + Integer.toHexString(c) + " pick idx " + best + " lo="
                            + Integer.toHexString(rg[o]) + " mask=" + Integer.toBinaryString(rg[o + 4]) + " -> "
                            + target + " (M=" + Integer.toBinaryString(posFlags) + ")");
                    }
                    chosen = o;
                    chosenTarget = target;
                }
            } else if (ri >= 0) {
                int o = (base + ri) * 5;
                int target = rg[o + 2];
                if (target >= 0) {
                    int requiredMask = rg[o + 4];
                    boolean ok = requiredMask == 0;
                    if (!ok) {
                        if (posFlags < 0) {
                            posFlags = positionFlags(input, pos, to);
                        }
                        ok = (posFlags & requiredMask) == requiredMask;
                    }
                    if (ok) {
                        chosen = o;
                        chosenTarget = target;
                    }
                }
            }
            if (chosen < 0) {
                break;
            }
            // Target entry mask is a position predicate, evaluated BEFORE the
            // transition's ops run: a mask-failing transition is never taken,
            // so its tag writes must not contaminate the register file (a
            // later-recorded accept would read them — e.g. a skipped group
            // reporting empty instead of null).
            int width = c > 0xFFFF ? 2 : 1;
            int entryReqNext = sem[chosenTarget];
            if (entryReqNext != 0 && !maskSatisfied(input, pos + width, to, entryReqNext)) {
                break;
            }
            if (regs != null) {
                int opsOff = rg[chosen + 3];
                if (opsOff != 0) {
                    applyOps(op, opsOff, regs, pos, tree);
                }
            }
            state = chosenTarget;
            if (width == 2) {
                pos++;
            }
            posFlags = -1;
        }
        if (!haveAccept) {
            return null;
        }
        int[] r = regs == null ? new int[0] : regs.clone();
        // Final ops already applied eagerly at accept-record time (BT22's
        // declaration semantics): they read the accept-time register values.
        // A lazy replay here would read end-of-walk values — any transition
        // taken between the accept and the break clobbers working registers
        // and inverts group spans (group start > end).
        return new MatchHolder(startSearch, lastAcceptPos, r, multi ? tree.snapshot() : null);
    }

    /**
     * Build the first-char candidate bitset from the start state's outgoing
     * ranges (dead targets excluded; mask-gated entries included — sound
     * over-approximation, the exact walk confirms). Ranges above the BMP OR
     * in the high-surrogate block: a supplementary first char begins with a
     * high surrogate unit, so those positions stay candidates.
     */
    private long[] buildStartBits() {
        final int[] sm = this.stateMeta, rg = this.ranges;
        int meta = sm[startState];
        int base = stateBase[startState], cnt = (meta >>> 1) & 0xFFFF;
        long[] bits = new long[1024];
        boolean any = false;
        for (int i = 0; i < cnt; i++) {
            int o = (base + i) * 5;
            if (rg[o + 2] < 0) {
                continue;
            } // dead: never a first char
            int lo = Math.max(rg[o], 0), hi = Math.min(rg[o + 1], 0xFFFF);
            for (int c = lo; c <= hi; c++) {
                bits[c >>> 6] |= 1L << (c & 63);
            }
            if (rg[o + 1] > 0xFFFF) {
                for (int c = 0xD800; c <= 0xDBFF; c++) {
                    bits[c >>> 6] |= 1L << (c & 63);
                }
            }
            any = true;
        }
        return any ? bits : null;
    }

    /**
     * Accept liveness of a simulation state set at {@code pos}: plain
     * acceptBits intersection when no accepting state is gated (the
     * exact fast path), else per-accepting-state — an ungated accept
     * reports immediately; a gated one only when its gate holds at
     * {@code pos} (accept mask via the mask-directed predicate; φ cell
     * via the step's posFlags — the walks' own branches, see
     * maskedAcceptStates). Suppression only ever DELAYS the trigger's
     * products (a later window W, or the -1 no-match proof); an accept
     * that can hold is still reported, so the restart ladder's
     * enumeration stays complete. {@code flags} is the caller's
     * one-element posFlags cache for the position — the φ lookups share
     * it (computed at most once per position, and only when a gated
     * accept is actually live).
     */
    private boolean acceptLive(String input, int pos, int to, int[] live, int[] flags) {
        final int[] ab = acceptBits;
        if (maskedAcceptStates == null) {
            for (int w = 0; w < stateWords; w++) {
                if ((live[w] & ab[w]) != 0) {
                    return true;
                }
            }
            return false;
        }
        for (int w = 0; w < stateWords; w++) {
            int both = live[w] & ab[w];
            while (both != 0) {
                int bit = Integer.numberOfTrailingZeros(both);
                both &= both - 1;
                if (acceptStateLive(input, pos, to, (w << 5) + bit, flags)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The per-state gate probe: 0-mask accepts hold, φ cells decide by
     *  the step's posFlags, masked ones by the mask-directed predicate. */
    private boolean acceptStateLive(String input, int pos, int to, int s, int[] flags) {
        final int[] sam = stateAcceptMask;
        final int[] fm = this.finalOpsByMask;
        // gate: -1 = φ cell decides, 0 = ungated, else the accept mask
        int gate = fm != null ? -1 : (sam == null ? 0 : sam[s]);
        if (gate == 0) {
            return true; // ungated accept
        }
        if (gate < 0) {
            if (flags[0] < 0) {
                flags[0] = positionFlags(input, pos, to);
            }
            return fm[s * posCells + flags[0]] >= 0;
        }
        return maskSatisfied(input, pos, to, gate);
    }

    /**
     * The unit-semantics trigger entry (see {@link #unitRawScan}): raw —
     * the interior seeds break the memo's pure-row algebra — traced
     * RAW_SCAN like the codepoint lane's raw fallback. Same contract as
     * {@link #triggerScan}: kill-point window start or -1 = no match.
     */
    private int unitTrigger(String input, int from, int to, MatchScratch sc) {
        trace(Strategy.RAW_SCAN);
        return unitRawScan(input, from, to, from, null, sc);
    }

    /**
     * The trigger rung: the kill-point-aware unmemoized multi-state scan.
     * (The lazily determinized search-DFA memo that used to serve this
     * rung above 2 048 chars was deleted — the priced tradeoff and the
     * restoration criteria live in docs/search-dfa-memo-tradeoffs.md.)
     * Returns the kill-point window start W — every configuration alive
     * before W is dead, so the restart loop may start there — or -1 =
     * no match at all. If the memo is ever restored the trigger rung
     * re-diverges from RAW_SCAN — a two-line revert.
     */
    private int triggerScan(String input, int from, int to, MatchScratch sc) {
        trace(Strategy.RAW_SCAN);
        return rawScan(input, from, to, from, null, sc);
    }

    /**
     * Uncapped fallback: the unmemoized multi-state simulation with kill-point
     * tracking (kill = the pre-seed step set is empty). Returns W or -1.
     * {@code unitSeeds} adds the unit-semantics interior seeds (see
     * {@link #unitRawScan}).
     */
    private int rawScan(String input, int from, int to, int wIn, int[] liveIn, MatchScratch sc) {
        return rawScan(input, from, to, wIn, liveIn, sc, false);
    }

    /**
     * The unit-semantics trigger bound: {@link #rawScan} with the
     * interior-unit seeds on. The codepoint-stepped simulation re-seeds
     * only at codepoint boundaries; under unit semantics a match may also
     * start at the INTERIOR unit of a surrogate pair, consuming the low
     * surrogate as a lone BMP char and rejoining the codepoint-aligned
     * stream one unit later — so at every {@code adv == 2} step the scan
     * additionally merges {@code delta(startState, lone low unit)} into
     * the next live set. With the interior seeds included, the two
     * trigger products are sound under unit semantics exactly as under
     * codepoint semantics: no accept live anywhere means no match at all
     * ({@code -1}), and a live accept means every surviving configuration
     * started at or after {@code W} (an interior config's origin is the
     * pair's low position, which participates in the emptiness test and
     * so never lets W advance past it). This is the bound the
     * unit-semantics restart ladder was missing — the campaign
     * 2026-10-16 finding-3 residue ({@code .+zz}-style tails, multiline
     * gates) turns from n restarts x O(n) walks into one linear pass
     * plus the restarts the window admits.
     */
    private int unitRawScan(String input, int from, int to, int wIn, int[] liveIn, MatchScratch sc) {
        return rawScan(input, from, to, wIn, liveIn, sc, true);
    }

    /**
     * One state's transition step for the raw scan: OR every target the
     * state's entries admit for {@code c} into {@code next} (the wakeup
     * semantics — ALL containing entries, masked artifacts included),
     * returning whether anything fired. Wide-indexed states dispatch in
     * one lookup (disjoint ⇒ the single containing entry); the rest keep
     * the anchor search + prefix-max walk-back.
     */
    private boolean scanStep(int s, int c, String input, int pos, int to, int[] next) {
        final int[] rg = this.ranges;
        final WideDispatch wd = this.wide;
        int ri = wd != null ? wd.rangeIndex(s, c) : WideDispatch.NONE;
        if (ri != WideDispatch.NONE) {
            if (ri < 0) {
                return false;
            }
            int mo = (stateBase[s] + ri) * 5;
            int t = rg[mo + 2];
            if (t >= 0 && (!rangeMaskGates || maskSatisfied(input, pos, to, rg[mo + 4]))) {
                next[t >>> 5] |= 1 << (t & 31);
                return true;
            }
            return false;
        }
        boolean fired = false;
        int base = stateBase[s];
        int count = (stateMeta[s] >>> 1) & 0xFFFF;
        int rlo = 0, rhi = count - 1, anchor = -1;
        while (rlo <= rhi) {
            int mid = (rlo + rhi) >>> 1;
            if (rg[(base + mid) * 5] <= c) {
                anchor = mid;
                rlo = mid + 1;
            } else {
                rhi = mid - 1;
            }
        }
        for (int i = anchor; i >= 0 && rhp[base + i] >= c; i--) {
            int mo = (base + i) * 5;
            if (c <= rg[mo + 1]) {
                int t = rg[mo + 2];
                if (t >= 0 && (!rangeMaskGates || maskSatisfied(input, pos, to, rg[mo + 4]))) {
                    next[t >>> 5] |= 1 << (t & 31);
                    fired = true;
                }
            }
        }
        return fired;
    }

    private int rawScan(String input, int from, int to, int wIn, int[] liveIn, MatchScratch sc, boolean unitSeeds) {
        final int nwords = stateWords;
        int[] live = sc.takeLive(nwords);
        int[] next = sc.takeNext(nwords);
        if (liveIn != null) {
            System.arraycopy(liveIn, 0, live, 0, nwords); // exact continuation
        } else {
            Arrays.fill(live, 0, nwords, 0);
            live[startState >>> 5] |= 1 << (startState & 31);
        }
        final int ss = startState;
        final int ssMask = 1 << (ss & 31);
        // Seed-only regime: the live set is exactly the re-seeded start
        // state — the shape of every sparse-candidate scan between kills.
        // The step is then ONE dispatch instead of the bitset iteration
        // (same products: targets ∪ reseed, kill ⇒ W advance). Single-word
        // DFAs only — wider ones re-derive the flag from `next` anyway.
        boolean seedOnly = nwords == 1 && live[0] == ssMask;
        int W = wIn;
        int[] pf = {-1}; // per-position posFlags cache (φ-gated probes)
        for (int pos = from; pos <= to; pos++) {
            pf[0] = -1;
            if (acceptLive(input, pos, to, live, pf)) {
                return W;
            }
            if (pos == to) {
                break;
            }
            int c = Alphabet.decode(input, pos, to);
            int adv = Alphabet.width(c);
            Arrays.fill(next, 0, nwords, 0);
            boolean empty = true;
            if (seedOnly) {
                if (scanStep(ss, c, input, pos, to, next)) {
                    empty = false;
                }
            } else {
                for (int w = 0; w < nwords; w++) {
                    int bits = live[w];
                    while (bits != 0) {
                        int bit = Integer.numberOfTrailingZeros(bits);
                        bits &= bits - 1;
                        if (scanStep((w << 5) + bit, c, input, pos, to, next)) {
                            empty = false;
                        }
                    }
                }
            }
            if (unitSeeds && adv == 2) {
                // Interior-unit ORIGIN at pos+1 (a real pair), empty-match
                // case first: the start state accepting at pos+1 (nothing
                // consumed) is a match starting and ending at the low unit
                // — probed here because the scan's accept checks only run
                // at codepoint positions.
                if ((stateMeta[startState] & 1) != 0) {
                    pf[0] = -1; // the probe's position is pos+1, not pos
                    if (acceptStateLive(input, pos + 1, to, startState, pf)) {
                        return W;
                    }
                }
                // Interior-unit start at pos+1 (a real pair): the low unit
                // consumed as a lone BMP char rejoins the aligned stream at
                // pos+2 — merge its start-state targets into the next set,
                // gated on required masks like every other transition.
                if (scanStep(startState, input.charAt(pos + 1), input, pos + 1, to, next)) {
                    empty = false;
                }
            }
            // Always re-seed: after a kill the live set must be the pure seed
            // (match may start at pos+1), not empty — an empty live set would
            // kill every subsequent step too and mask real matches.
            next[ss >>> 5] |= ssMask;
            if (empty) {
                W = pos + adv;
            }
            if (nwords == 1) {
                seedOnly = next[0] == ssMask;
            }
            int[] tmp = live;
            live = next;
            next = tmp;
            if (adv == 2) {
                pos++;
            }
        }
        return -1;
    }

    // ===== ASM ladder hooks: strategy pieces the generated ladder calls.
    // TdfaRunner is final, so these invokevirtual sites are monomorphic and
    // inline wherever emitted. The generated ladder mirrors
    // runStringExtractFast exactly; the strategy-conformance test asserts
    // trace equality between backends. =====

    /**
     * Defensive restart walk (sim and walk disagreed on a fast-path DFA):
     * per-unit scan from {@code fromStart} with the pair-interior guard and
     * an exact extract at each position. Public: the ASM-emitted ladder
     * delegates here instead of emitting its own loop — one definition.
     * {@code sc} may be null (carrier-free engines' cold fallback).
     */
    @EmittedSurface
    public MatchHolder restartExtract(String input, int fromStart, int to, int from0, MatchScratch sc) {
        if (sc == null) {
            sc = new MatchScratch();
        }
        // Abs-begin-bound artifact: every walk from fromStart >= 1 crosses
        // an \A gate at pos > 0 and dies (see Tdfa.absBeginBound) — the
        // loop is provably fruitless. All callers pass fromStart >= 1.
        if (absBeginBound) {
            return null;
        }
        for (int s = fromStart; s <= to; s++) {
            if (scanSkipsPairs && Alphabet.pairInterior(input, s)) {
                continue;
            }
            MatchHolder h = tryStartFast(input, s, to, sc);
            if (h != null) {
                return h;
            }
        }
        return null;
    }

    /**
     * First-char candidate bitset, or null when the start state accepts.
     */
    @EmittedSurface
    public long[] startBits() {
        return startBits;
    }

    /**
     * Max input length for the candidate scan.
     */
    @EmittedSurface
    public int candScanMax() {
        return CAND_SCAN_MAX;
    }

    /**
     * Char budget for the origin sim before the trigger fallback.
     */
    @EmittedSurface
    public int originSimBudget() {
        return LSS_BUDGET_CHARS;
    }

    /**
     * Origin-sim leftmost start; {@link #LSS_BUDGET} = budget exhausted.
     * {@code sc} may be null (carrier-free engines' cold fallback).
     */
    @EmittedSurface
    public int originSimLeftmost(CharSequence input, int from, int to, int budget, MatchScratch sc) {
        return multiStateLeftmostStart(input, from, to, budget, sc != null ? sc : new MatchScratch());
    }

    /**
     * Kill-point trigger scan (see {@link #triggerScan}): window start W, or -1 = no match.
     * {@code sc} may be null (carrier-free engines' cold fallback).
     */
    @EmittedSurface
    public int triggerScanTop(String input, int from, int to, MatchScratch sc) {
        return triggerScan(input, from, to, sc != null ? sc : new MatchScratch());
    }

    /**
     * Rung 2's sim/trigger core in one hook (see {@code runStringExtractFast}):
     * the budgeted origin sim and — on budget exhaustion — the trigger raw
     * scan CONTINUING from the sim's live set, position, and kill-point
     * window instead of re-scanning from {@code from}. Before the handoff
     * the same 4 096+ budget chars were simulated twice per restart window
     * (the wide-dispatch fix's measured ~2.1× residue on wide-class scans);
     * now the pair scans once. Answers are identical to the fresh-scan
     * composition it replaces ({@link #originSimLeftmost} →
     * {@link #triggerScanTop} → {@code originSimLeftmost} over the window):
     * fastPath guarantees the sim's disjoint-target step resolves the same
     * single entry the raw scan's wakeup step ORs, its plain accept-bits
     * check is the raw scan's ungated one, and the parked window follows
     * {@link #rawScan}'s kill rule step for step — so the continuation is
     * state-identical to a fresh trigger scan at that position, and the
     * windowed re-sim that follows sees the same W. Returns the leftmost
     * match start in {@code [from, to]}, or -1 = no match (the
     * {@link #LSS_BUDGET} sentinel never escapes; it is consumed by the
     * handoff). Traces {@code RAW_SCAN} exactly when the trigger continues,
     * where the fresh-scan ladder traced it. Valid where the sim is:
     * fastPath artifacts on the codepoint lane.
     * {@code sc} may be null (carrier-free engines' cold fallback).
     */
    @EmittedSurface
    public int originSimRungTop(String input, int from, int to, int budget, MatchScratch sc) {
        sc = sc != null ? sc : new MatchScratch();
        final SimHandoff handoff = new SimHandoff();
        int leftmost = multiStateLeftmostStart(input, from, to, budget, sc, handoff);
        if (leftmost != LSS_BUDGET) {
            return leftmost;
        }
        trace(Strategy.RAW_SCAN);
        int w = rawScan(input, handoff.pos, to, handoff.w, handoff.live, sc);
        if (w < 0) {
            return -1;
        }
        return multiStateLeftmostStart(input, w, to, sc);
    }

    /**
     * The unit-semantics trigger bound (see {@link #unitTrigger}): window
     * start W, or -1 = no match. Emitted-ladder hook — the ASM tier
     * delegates so both ladders run the ONE scan definition.
     * {@code sc} may be null (carrier-free engines' cold fallback).
     */
    @EmittedSurface
    public int unitTriggerTop(String input, int from, int to, MatchScratch sc) {
        return unitTrigger(input, from, to, sc != null ? sc : new MatchScratch());
    }

    /**
     * Boolean single-start walk (fastPath only): does a match start at from?
     */
    @EmittedSurface
    public boolean booleanMatchFrom(String input, int from, int to) {
        return matchFromFast(input, from, to);
    }

    /**
     * Multi-state simulation with per-state origin tracking; returns the
     * leftmost start position of any match in {@code [from, to]}, or -1.
     *
     * <p>Only called when {@link #fastPath} holds (disjoint ranges, no entry/
     * accept/required masks, not multiline) — otherwise the mask-free
     * transition-following would over-approximate. {@link #stopOnAcceptMask}
     * (Perl early-stop) only shortens matches, never removes them, so it is
     * safely ignored here: accept-live at p with origin o implies a match
     * starting at o exists.
     *
     * <p>{@code origin[s]} = smallest seed position from which s is live.
     * The start state is re-seeded at every position (unanchored search), so
     * its origin is always {@code from}; state bits and origins move in lockstep:
     * {@code next} is zeroed each step, so "bit already set in next" exactly
     * identifies re-reachable states (origin = min) vs first-arrival (origin = set).
     */
    private int multiStateLeftmostStart(CharSequence input, int from, int to, MatchScratch sc) {
        return multiStateLeftmostStart(input, from, to, -1, sc);
    }

    /**
     * Budgeted variant: aborts with {@link #LSS_BUDGET} after {@code budget}
     * chars without an accept (dense matches early-stop far inside; a distant
     * or absent match is better served by the trigger raw scan, so the
     * caller falls back to it instead of bitset-scanning the whole tail).
     */
    private int multiStateLeftmostStart(CharSequence input, int from, int to, int budget, MatchScratch sc) {
        return multiStateLeftmostStart(input, from, to, budget, sc, null);
    }

    /**
     * The sim→trigger handoff (see {@link #originSimRungTop} and
     * {@link #rawScan(String, int, int, int, int[], MatchScratch)}): what the
     * budgeted sim holds at its {@link #LSS_BUDGET} bail — the position its
     * live set describes (the first boundary past the budget limit; the
     * accept check there has NOT run), the kill-point window a raw scan
     * started at {@code from} would have tracked to that position, and the
     * live-set array itself. Allocated by the rung caller, written only at
     * the bail, consumed by the trigger continuation within the same
     * operation.
     */
    private static final class SimHandoff {
        int pos;
        int w;
        int[] live;
    }

    /**
     * Handoff-carrying twin of the budgeted variant: with {@code handoff}
     * non-null, the sim additionally tracks the raw scan's kill-point window
     * ({@code w}: advanced to {@code pos + adv} on every step where no
     * target fired, exactly {@link #rawScan}'s {@code empty} rule) and parks
     * its live set, position, and window at the budget bail, so the trigger
     * continuation starts state-identical to a fresh scan from
     * {@code from} — one pass over the budget window, not two.
     */
    private int multiStateLeftmostStart(CharSequence input, int from, int to, int budget, MatchScratch sc,
        SimHandoff handoff) {
        final int nwords = stateWords;
        if (nwords == 0) {
            return -1;
        }
        final int[] rg = ranges;
        final int[] at = asciiTarget;
        final WideDispatch wd = this.wide;
        final int[] ab = acceptBits;
        final int ss = startState;

        int[] live = sc.takeLive(nwords);
        int[] next = sc.takeNext(nwords);
        int[] origin = sc.takeOrigin(stateCount);
        int[] originNext = sc.takeOriginNext(stateCount);
        // Origins are DOUBLE-BUFFERED with the state sets: origin[] pairs with
        // live[], originNext[] with next[]. All arrivals in a step write to
        // originNext (bit test against next), while old-live origins in origin[]
        // stay readable for the whole step — a single buffer would corrupt a
        // state's own origin mid-step when another path's arrival (or the fresh
        // re-seed) targets a still-live state before its self-loop reads it
        // (e.g. +1/step origin drift on (\d+)\.(\d+)... over "ip=192.168.1.77").
        // Stale originNext values are never read: a bit present in next implies
        // a this-step write (first arrival sets, later arrivals min-merge).
        Arrays.fill(live, 0, nwords, 0);
        live[ss >>> 5] |= 1 << (ss & 31);
        origin[ss] = from;
        // Seed-only regime (see the transition step below): single-word DFAs
        // track "live == {ss}" in one compare per step.
        final int ssBit = 1 << (ss & 31);
        boolean seedOnly = nwords == 1;

        int best = -1;
        int killW = from; // the raw scan's kill-point window (SimHandoff twin)
        final int limit = budget < 0 ? Integer.MAX_VALUE : from + budget;
        for (int pos = from; pos <= to; pos++) {
            if (best < 0 && pos > limit) {
                if (handoff != null) {
                    handoff.pos = pos;
                    handoff.w = killW;
                    handoff.live = live;
                }
                return LSS_BUDGET;
            }
            // accept check with origin tracking
            for (int w = 0; w < nwords; w++) {
                int bits = live[w] & ab[w];
                while (bits != 0) {
                    int bit = Integer.numberOfTrailingZeros(bits);
                    bits &= bits - 1;
                    int s = (w << 5) + bit;
                    if (best < 0 || origin[s] < best) {
                        best = origin[s];
                    }
                }
            }
            if (best >= 0) {
                // Can any future accept beat `best`? Future accept origins are
                // either origins of currently-live states or seeds > pos. The
                // scan may stop early only once that is decided: a partial min
                // below `best` (no return is possible), or one at `from` — the
                // origin floor, nothing can be smaller. A partial min merely
                // EQUAL to `best` proves nothing: a smaller origin can sit in
                // an unscanned word, so the scan must continue.
                int minLive = Integer.MAX_VALUE;
                for (int w = 0; w < nwords && minLive >= best && minLive > from; w++) {
                    int bits = live[w];
                    while (bits != 0) {
                        int bit = Integer.numberOfTrailingZeros(bits);
                        bits &= bits - 1;
                        int s = (w << 5) + bit;
                        if (origin[s] < minLive) {
                            minLive = origin[s];
                        }
                    }
                }
                if (best <= pos && best <= minLive) {
                    return best;
                }
            }
            if (pos == to) {
                break;
            }

            int c = Alphabet.decode(input, pos, to);
            int adv = Alphabet.width(c);

            Arrays.fill(next, 0, nwords, 0); // grown carrier: zero only our prefix
            boolean fired = false; // rawScan's `empty` twin: no target ⇒ kill
            if (seedOnly) {
                // Seed-only regime (see rawScan): the live set is exactly the
                // re-seeded start state — the sparse-scan shape between
                // kills. One dispatch instead of the bitset iteration; the
                // origin carried to the target is origin[ss], exactly what
                // the general loop below would read for the lone live state.
                int ri = wd != null ? wd.rangeIndex(ss, c) : WideDispatch.NONE;
                int target;
                if (ri == WideDispatch.NONE) {
                    target = disjointTarget(ss, c);
                } else if (ri >= 0) {
                    target = rg[(stateBase[ss] + ri) * 5 + 2];
                } else {
                    target = -1;
                }
                if (target >= 0) {
                    setOrigin(next, originNext, target, origin[ss]);
                    fired = true;
                }
            } else if (at != null && c < 128) {
                for (int w = 0; w < nwords; w++) {
                    int bits = live[w];
                    while (bits != 0) {
                        int bit = Integer.numberOfTrailingZeros(bits);
                        bits &= bits - 1;
                        int s = (w << 5) + bit;
                        int target = at[s * 128 + c];
                        if (target >= 0) {
                            setOrigin(next, originNext, target, origin[s]);
                            fired = true;
                        }
                    }
                }
            } else if (wd != null && c >= 128) {
                // Wide classes past Latin-1: one index lookup per live state
                // (see WideDispatch) — the sim's per-char cost on unicode
                // text drops from a range search to a couple of loads.
                // Narrow states under the index threshold keep the search.
                for (int w = 0; w < nwords; w++) {
                    int bits = live[w];
                    while (bits != 0) {
                        int bit = Integer.numberOfTrailingZeros(bits);
                        bits &= bits - 1;
                        int s = (w << 5) + bit;
                        int ri = wd.rangeIndex(s, c);
                        int target;
                        if (ri == WideDispatch.NONE) {
                            target = disjointTarget(s, c);
                        } else if (ri >= 0) {
                            target = rg[(stateBase[s] + ri) * 5 + 2];
                        } else {
                            target = -1;
                        }
                        if (target >= 0) {
                            setOrigin(next, originNext, target, origin[s]);
                            fired = true;
                        }
                    }
                }
            } else {
                for (int w = 0; w < nwords; w++) {
                    int bits = live[w];
                    while (bits != 0) {
                        int bit = Integer.numberOfTrailingZeros(bits);
                        bits &= bits - 1;
                        int s = (w << 5) + bit;
                        // rangesDisjoint == true on the fast path — binary search
                        int target = disjointTarget(s, c);
                        if (target >= 0) {
                            setOrigin(next, originNext, target, origin[s]);
                            fired = true;
                        }
                    }
                }
            }
            setOrigin(next, originNext, ss, pos + adv); // unanchored re-seed (min-merge if already re-added)
            if (!fired) {
                killW = pos + adv; // kill: nothing alive before pos + adv (rawScan's rule)
            }
            if (nwords == 1) {
                seedOnly = next[0] == ssBit;
            }

            int[] tmp = live;
            live = next;
            next = tmp;
            int[] to2 = origin;
            origin = originNext;
            originNext = to2;
            if (adv == 2) {
                pos++;
            }
        }
        return best;
    }

    /**
     * Ultra-tight anchored match for DFAs with no masks and disjoint ranges.
     * Uses a flat precomputed target table: one array load per char.
     * Falls back to {@link #runStringAnchored} on non-ASCII input.
     */
    private boolean runStringAnchoredFast(String input) {
        final int to = input.length();
        final int[] sm = this.stateMeta;
        final int[] at = this.latinTarget;
        int state = startState;
        final int limit = this.latinLimit;
        for (int pos = 0; pos < to; pos++) {
            char c = input.charAt(pos);
            if (c >= limit) {
                return runStringAnchored(input) >= 0;
            }
            state = at[state * limit + c];
            if (state < 0) {
                return false;
            }
        }
        return (sm[state] & 1) != 0;
    }

    // ===== Fast paths: no masks, disjoint ranges, ASCII-only =====

    /**
     * Unanchored boolean search via single-pass multi-state simulation. O(n × |states|).
     */
    private boolean runStringFindFast(String input, int to, MatchScratch sc) {
        // Literal-prefix candidate scan first (see prefixScanBool): no hits ⇒
        // no match. Budget exhaustion falls through to the ladders below
        // (complete searches).
        if (prefixNeedle != null) {
            trace(Strategy.PREFIX);
            int pr = prefixScanBool(input, 0, to);
            if (pr == PREFIX_HIT) {
                return true;
            }
            if (pr == PREFIX_DONE) {
                return false;
            }
        }
        // Short inputs: first-char-set candidate scan (one bit test per char,
        // exact walk per candidate) beats the raw-scan live-set simulation.
        if (startBits != null && to <= CAND_SCAN_MAX) {
            trace(Strategy.CAND_SCAN);
            return candScanBool(input, 0, to);
        }
        // Unit semantics: the unit raw-scan trigger (interior seeds, see
        // unitTrigger) — no match anywhere answers false in one pass; a
        // window bounds the per-unit walk floor below it.
        if (!scanSkipsPairs) {
            int w = unitTrigger(input, 0, to, sc);
            if (w < 0) {
                return false;
            }
            trace(Strategy.WALK_RESTART);
            for (int from = Math.max(1, w); from <= to; from++) {
                if (matchFromFast(input, from, to)) {
                    return true;
                }
            }
            return false;
        }
        return triggerScan(input, 0, to, sc) >= 0;
    }

    /**
     * Tight boolean walk for fastPath DFAs: does some match start exactly at
     * {@code from}? Flat range-index dispatch below {@code latinLimit},
     * disjoint binary search above; no regs, no masks (fastPath guarantees
     * all zero), no PERL stop logic — any accept is a match for boolean
     * purposes, so the first accepting state returns true. Never called when
     * the start state accepts (startBits is null then), so no empty-match
     * check is needed before the first step.
     */
    private boolean matchFromFast(String input, int from, int to) {
        final int[] sm = this.stateMeta;
        final int[] arf = this.asciiRangeFlat;
        final int[] rg = this.ranges;
        final int limit = this.latinLimit;
        int state = startState;
        int pos = from;
        while (pos < to) {
            int c = Alphabet.decode(input, pos, to);
            int adv = Alphabet.width(c);
            int ri;
            if (c < limit) {
                ri = arf[state * limit + c];
            } else if (wide != null) {
                ri = wideRangeIndex(state, c);
            } else {
                ri = Integer.MIN_VALUE;
            }
            if (ri == Integer.MIN_VALUE) {
                int base = stateBase[state], cnt = (sm[state] >>> 1) & 0xFFFF;
                int rlo = 0, rhi = cnt - 1;
                ri = -1;
                while (rlo <= rhi) {
                    int mid = (rlo + rhi) >>> 1;
                    int mo = (base + mid) * 5;
                    if (c < rg[mo]) {
                        rhi = mid - 1;
                        continue;
                    }
                    if (c > rg[mo + 1]) {
                        rlo = mid + 1;
                        continue;
                    }
                    ri = mid;
                    break;
                }
            }
            if (ri < 0) {
                return false;
            }
            int target = rg[(stateBase[state] + ri) * 5 + 2];
            if (target < 0) {
                return false;
            }
            state = target;
            pos += adv;
            if ((sm[state] & 1) != 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Fast extract with register updates.
     */
    private MatchHolder runStringExtractFast(String input, int from, int to, MatchScratch sc) {
        if (literalNeedle != null) {
            trace(Strategy.LITERAL);
            int idx = RunnerTables.literalIndexOf(input, literalNeedle, from, scanSkipsPairs);
            return idx < 0 ? null : new MatchHolder(idx, idx + literalNeedle.length(), new int[0]);
        }
        // 1) Try ONE single-start walk from `from` — the common short-input case
        //    (match at/near the start) never needs the simulation at all.
        trace(Strategy.EXACT_FROM);
        MatchHolder h = tryStartFast(input, from, to, sc);
        if (h != null) {
            return h;
        }
        // 1a) Literal-prefix candidate scan (see prefixScanExtract): the
        //     intrinsified vectorized indexOf beats the per-char ladders
        //     below on any needle-prefixed shape; no hits ⇒ no match at all
        //     (the exact walk above ruled out a start at `from`). On budget
        //     exhaustion fall through to the sim/trigger ladder, a complete
        //     search from `from`.
        if (prefixNeedle != null) {
            trace(Strategy.PREFIX);
            h = prefixScanExtract(input, from + 1, to, sc);
            if (h != PREFIX_BUDGET_OUT) {
                return h; // the hit, or no hits at all — either way decided
            }
        }
        // 1b) Short inputs: first-char-set candidate scan. Coverage is exact
        //     (start state not accepting — else startBits is null — so every
        //     match consumes a first char carrying its bit); each candidate
        //     gets an exact walk, so the first hit is the true leftmost match.
        if (startBits != null && to - from <= CAND_SCAN_MAX) {
            trace(Strategy.CAND_SCAN);
            return candScanExtract(input, from + 1, to, sc);
        }
        // 2) No match starting at `from`: budgeted origin-tracking sim. Dense
        //    matches early-stop inside the budget and never touch the trigger
        //    (an unconditional pre-scan would double the work on dense findAll
        //    scans). A distant/absent match exhausts the budget and hands the
        //    sim's live set + position to the trigger raw scan (one pass over
        //    the budget window, not two — see originSimRungTop), which bounds
        //    the window to [W, to] via kill points; the sim then finishes over
        //    just that window. Retrying every failed start with a full walk
        //    instead costs O(n) restarts × O(n) walk = O(n²) on dense-match
        //    regexes like [a-zA-Z]+ing.
        //    Unit semantics (CODEPOINT_BOUNDARIES unset): the sim/trigger
        //    steps codepoints, so the unit raw-scan trigger serves (interior
        //    seeds, see unitTrigger) — same window bound, one linear pass
        //    before the restart floor.
        if (!scanSkipsPairs) {
            int w = unitTrigger(input, from, to, sc);
            if (w < 0) {
                return null;
            }
            trace(Strategy.WALK_RESTART);
            return restartExtract(input, Math.max(from + 1, w), to, from, sc);
        }
        trace(Strategy.ORIGIN_SIM);
        int leftmost = originSimRungTop(input, from, to, LSS_BUDGET_CHARS, sc);
        if (leftmost < 0) {
            return null;
        }
        h = tryStartFast(input, leftmost, to, sc);
        if (h != null) {
            return h;
        }
        // 3) Defensive: the sim and the walk must agree on fast-path DFAs; if
        //    they ever don't, fall back to the plain per-start restart loop
        //    rather than return a wrong null.
        trace(Strategy.WALK_RESTART);
        return restartExtract(input, leftmost + 1, to, from, sc);
    }

    /**
     * One single-start extract walk (no restart loop); null if no match starts
     * exactly at {@code start}. Non-Latin-1 codepoints dispatch through the
     * wide index where the artifact has one (keeping the walk inline, pair
     * advance and all); otherwise they fall back to the generic exact walk
     * from the SAME start (single-start semantics — callers treat null as
     * "no match here", not "no match anywhere").
     */
    private MatchHolder tryStartFast(String input, int start, int to, MatchScratch sc) {
        final int[] sm = this.stateMeta;
        final int[] arf = this.asciiRangeFlat;
        final int[] rg = this.ranges;
        final int[] op = this.ops;
        // Leftmost-first stopOnAccept: pre-load the mask table when not in
        // longest-match mode so the inner loop can short-circuit on first
        // accepting state (matching the slow path).
        final boolean pm = !this.longestMatch;
        // Pooled regs (caller's carrier): no allocation on the (frequent)
        // failed-walk path. On success the array is cloned into the MatchHolder
        // before returning, so the pool is never handed out. NOTE: the
        // non-ASCII fallback below re-enters the generic extract path, which
        // refills the same carrier — safe: it never aliases a live caller's
        // [0, regSize) window (each taker refills before use).
        final int[] regs;
        final TagTree tree = multi ? takeTree(sc) : null;
        if (regSize == 0) {
            regs = null;
        } else {
            regs = takeRegs(regSize, sc);
            Arrays.fill(regs, 0, regSize, -1);
        }
        int state = startState;
        int lastAcceptPos = -1;
        boolean haveAccept = false;
        int posFlags = -1; // lazy: -1 means not yet computed for current pos
        int pos = start;
        for (; pos <= to; pos++) {
            int meta = sm[state];
            if ((meta & 1) != 0) {
                final int[] fm = this.finalOpsByMask;
                if (fm != null) {
                    if (posFlags < 0) {
                        posFlags = positionFlags(input, pos, to);
                    }
                    int cell = fm[state * posCells + posFlags];
                    // cell < 0 (position-suppressed accept): do NOT record and
                    // do NOT stop — fall through to the transition, exactly
                    // like extractFrom (a `continue` here would skip the
                    // transition and freeze the walk state while pos advanced).
                    if (cell >= 0) {
                        haveAccept = true;
                        lastAcceptPos = pos;
                        if (regs != null && cell != 0) {
                            applyOps(op, cell, regs, pos, tree);
                        }
                        if (pm && stopNow(state, posFlags)) {
                            break;
                        }
                    }
                } else {
                    haveAccept = true;
                    lastAcceptPos = pos;
                    if (regs != null) {
                        applyFinalOps(state, regs, pos, tree);
                    }
                    if (pm) {
                        if (stopMaskUniform == null) {
                            posFlags = positionFlags(input, pos, to);
                        }
                        if (stopNow(state, posFlags)) {
                            break;
                        }
                    }
                }
            }
            if (pos == to) {
                break;
            }
            char cu = input.charAt(pos);
            int ri;
            int width = 1;
            if (cu < latinLimit) {
                ri = arf[state * latinLimit + cu];
            } else if (wide != null) {
                // Wide dispatch covers the non-Latin-1 span in place — the
                // walk keeps its registers/accept state instead of bailing
                // to extractFrom. MIN_VALUE (state not indexed) still bails.
                int cp = Alphabet.decode(input, pos, to);
                ri = wideRangeIndex(state, cp);
                if (ri != Integer.MIN_VALUE) {
                    width = cp > 0xFFFF ? 2 : 1;
                }
            } else {
                ri = Integer.MIN_VALUE;
            }
            if (ri == Integer.MIN_VALUE) {
                return extractFrom(input, start, to, sc);
            }
            if (ri < 0) {
                break;
            }
            int mo = (stateBase[state] + ri) * 5;
            int target = rg[mo + 2];
            if (target < 0) {
                break;
            }
            if (regs != null) {
                int opsOff = rg[mo + 3];
                if (opsOff != 0) {
                    applyOps(op, opsOff, regs, pos, tree);
                }
            }
            state = target;
            if (width == 2) {
                pos++;
            }
            // posFlags belongs to the OLD position — invalidate (extractFrom /
            // matchFrom / anchored / generic all reset here; skipping the
            // reset feeds stale flags to the next accept / stopNow probe).
            posFlags = -1;
        }
        if (haveAccept) {
            // Eager finals at accept-record time (see extractFrom).
            return new MatchHolder(start, lastAcceptPos, regs == null ? new int[0] : regs.clone(),
                multi ? tree.snapshot() : null);
        }
        return null;
    }

    /**
     * Anchored String match. Returns lastAcceptPos (>=0) on match, -1 on no match. No allocation.
     */
    private int runStringAnchored(String input) {
        final int[] sm = this.stateMeta;
        final int[] rg = this.ranges;
        final int[] sem = this.stateEntryMask;
        final int[] sam = this.stateAcceptMask;
        final int to = input.length();

        int state = startState;
        int lastAcceptPos = -1;

        // Entry check for start state — inline
        {
            int entryReq = sem[state];
            if (entryReq != 0 && !maskSatisfied(input, 0, to, entryReq)) {
                return -1;
            }
        }

        int posFlags = -1; // lazy: -1 means not yet computed for current pos
        for (int pos = 0; pos <= to; pos++) {
            int meta = sm[state];
            if ((meta & 1) != 0) {
                int acceptMask = sam[state];
                if (acceptMask == 0) {
                    lastAcceptPos = pos;
                } else {
                    if (posFlags < 0) {
                        posFlags = positionFlags(input, pos, to);
                    }
                    if ((posFlags & acceptMask) == acceptMask) {
                        lastAcceptPos = pos;
                    }
                }
            }
            if (pos == to) {
                break;
            }
            int c = Alphabet.decode(input, pos, to);
            int base = stateBase[state];
            int count = (meta >>> 1) & 0xFFFF;
            boolean matched = false;
            if (asciiTables) {
                // ASCII fast path: direct table lookup; wide classes keep it
                // past Latin-1 via the wide dispatch index (exact under the
                // same disjointness; MIN_VALUE → binary search below).
                int ri;
                if (c < latinLimit) {
                    ri = asciiRangeFlat[state * latinLimit + c];
                } else if (wide != null) {
                    ri = wideRangeIndex(state, c);
                } else {
                    ri = -2;
                }
                if (ri >= 0) {
                    int mo = (base + ri) * 5;
                    int target = rg[mo + 2];
                    if (target >= 0) {
                        int requiredMask = rg[mo + 4];
                        boolean maskOk = requiredMask == 0;
                        if (!maskOk) {
                            if (posFlags < 0) {
                                posFlags = positionFlags(input, pos, to);
                            }
                            maskOk = (posFlags & requiredMask) == requiredMask;
                        }
                        if (maskOk) {
                            state = target;
                            if (c > 0xFFFF) {
                                pos++;
                            }
                            int entryReq = sem[state];
                            if (entryReq == 0 || maskSatisfied(input, pos + 1, to, entryReq)) {
                                matched = true;
                            } else {
                                return lastAcceptPos == to ? lastAcceptPos : -1;
                            }
                        }
                    }
                } else if (ri == -1) {
                    break; // dead ASCII char
                } else {
                    // Non-ASCII: binary search
                    int rlo = 0, rhi = count - 1;
                    while (rlo <= rhi) {
                        int mid = (rlo + rhi) >>> 1;
                        int mo = (base + mid) * 5;
                        if (c < rg[mo]) {
                            rhi = mid - 1;
                            continue;
                        }
                        if (c > rg[mo + 1]) {
                            rlo = mid + 1;
                            continue;
                        }
                        int target = rg[mo + 2];
                        if (target < 0) {
                            break;
                        }
                        int requiredMask = rg[mo + 4];
                        if (requiredMask != 0) {
                            if (posFlags < 0) {
                                posFlags = positionFlags(input, pos, to);
                            }
                            if ((posFlags & requiredMask) != requiredMask) {
                                break;
                            }
                        }
                        state = target;
                        if (c > 0xFFFF) {
                            pos++;
                        }
                        int entryReq = sem[state];
                        if (entryReq != 0) {
                            if (!maskSatisfied(input, pos + 1, to, entryReq)) {
                                return lastAcceptPos == to ? lastAcceptPos : -1;
                            }
                        }
                        matched = true;
                        break;
                    }
                }
            } else {
                // Non-disjoint state: binary search + prefix-max walk. Ownership
                // protocol (same as extractFrom / runStringMatchFrom /
                // runGeneric): the MOST SPECIFIC satisfied mask wins (popcount of
                // requiredMask), ties to the lowest index; a dead marker of the
                // OWNING entry kills the walk — no fallthrough to lower-specificity
                // entries, whose contexts are not alive here. A pure
                // lowest-index + transparent-dead rule would let matches()
                // return true where match()/find() find nothing on
                // overlapping-mask-context patterns.
                int rlo = 0, rhi = count - 1, anchor = -1;
                while (rlo <= rhi) {
                    int mid = (rlo + rhi) >>> 1;
                    if (rg[(base + mid) * 5] <= c) {
                        anchor = mid;
                        rlo = mid + 1;
                    } else {
                        rhi = mid - 1;
                    }
                }
                int chosen = -1, chosenTarget = 0;
                int best = -1, bestSpec = -1;
                for (int i = anchor; i >= 0 && rhp[base + i] >= c; i--) {
                    int o = (base + i) * 5;
                    if (c <= rg[o + 1]) {
                        int requiredMask = rg[o + 4];
                        if (requiredMask != 0) {
                            if (posFlags < 0) {
                                posFlags = positionFlags(input, pos, to);
                            }
                            if ((posFlags & requiredMask) != requiredMask) {
                                continue;
                            }
                        }
                        int spec = Integer.bitCount(requiredMask);
                        if (spec >= bestSpec) {
                            best = i;
                            bestSpec = spec;
                        } // >= : lower index wins ties
                    }
                }
                if (best >= 0) {
                    int o = (base + best) * 5;
                    int target = rg[o + 2];
                    if (target >= 0) {
                        chosen = o;
                        chosenTarget = target;
                    }
                    // target < 0: dead marker of the owning context — leave
                    // chosen < 0; the walk ends (matched=false) and the final
                    // lastAcceptPos check decides, exactly like extractFrom's
                    // loop break.
                }
                if (chosen >= 0) {
                    state = chosenTarget;
                    if (c > 0xFFFF) {
                        pos++;
                    }
                    int entryReq = sem[state];
                    if (entryReq != 0) {
                        if (!maskSatisfied(input, pos + 1, to, entryReq)) {
                            return lastAcceptPos == to ? lastAcceptPos : -1;
                        }
                    }
                    matched = true;
                }
            }
            if (!matched) {
                break;
            }
            posFlags = -1;
        }
        return lastAcceptPos == to ? lastAcceptPos : -1;
    }

    /**
     * String match from position, returns lastAcceptPos or -1. No allocation.
     */
    private int runStringMatchFrom(String input, int from, int to) {
        final int[] sm = this.stateMeta;
        final int[] rg = this.ranges;
        final int[] sem = this.stateEntryMask;
        final int[] sam = this.stateAcceptMask;
        final int[] arf = this.asciiRangeFlat; // non-null iff rangesDisjoint
        final int limit = this.latinLimit;
        int state = startState;
        int lastAcceptPos = -1;
        boolean haveAccept = false;
        int pos = from;

        // Entry check for start state — inline
        {
            int entryReq = sem[state];
            if (entryReq != 0 && !maskSatisfied(input, pos, to, entryReq)) {
                return -1;
            }
        }

        int posFlags = -1;
        for (;; pos++) {
            int meta = sm[state];
            if ((meta & 1) != 0) {
                int acceptMask = sam[state];
                if (acceptMask == 0) {
                    haveAccept = true;
                    lastAcceptPos = pos;
                    if (!longestMatch) {
                        if (posFlags < 0 && stopMaskUniform == null) {
                            posFlags = positionFlags(input, pos, to);
                        }
                        if (stopNow(state, posFlags)) {
                            break;
                        }
                    }
                } else {
                    if (posFlags < 0) {
                        posFlags = positionFlags(input, pos, to);
                    }
                    if ((posFlags & acceptMask) == acceptMask) {
                        haveAccept = true;
                        lastAcceptPos = pos;
                        if (!longestMatch && stopNow(state, posFlags)) {
                            break;
                        }
                    }
                }
            }
            if (pos >= to) {
                break;
            }
            int c = Alphabet.decode(input, pos, to);
            int base = stateBase[state];
            int count = (meta >>> 1) & 0xFFFF;
            boolean matched = false;
            int riFlat;
            if (arf != null && c < limit) {
                riFlat = arf[state * limit + c];
            } else if (wide != null) {
                // Wide dispatch (exact under disjointness — see WideDispatch);
                // MIN_VALUE = state not indexed → the binary search below.
                riFlat = wideRangeIndex(state, c);
            } else {
                riFlat = Integer.MIN_VALUE;
            }
            if (riFlat != Integer.MIN_VALUE) {
                // disjoint flat lookup: exact (priority moot, single entry)
                if (riFlat >= 0) {
                    int mo = (base + riFlat) * 5;
                    int target = rg[mo + 2];
                    if (target < 0) {
                        return haveAccept ? lastAcceptPos : -1;
                    }
                    int requiredMask = rg[mo + 4];
                    if (requiredMask != 0) {
                        if (posFlags < 0) {
                            posFlags = positionFlags(input, pos, to);
                        }
                        if ((posFlags & requiredMask) != requiredMask) {
                            return haveAccept ? lastAcceptPos : -1;
                        }
                    }
                    state = target;
                    if (c > 0xFFFF) {
                        pos++;
                    }
                    int entryReq = sem[state];
                    if (entryReq != 0 && !maskSatisfied(input, pos + 1, to, entryReq)) {
                        return haveAccept ? lastAcceptPos : -1;
                    }
                    matched = true;
                }
            } else if (rangesDisjoint) {
                int rlo = 0, rhi = count - 1;
                while (rlo <= rhi) {
                    int mid = (rlo + rhi) >>> 1;
                    int mo = (base + mid) * 5;
                    if (c < rg[mo]) {
                        rhi = mid - 1;
                        continue;
                    }
                    if (c > rg[mo + 1]) {
                        rlo = mid + 1;
                        continue;
                    }
                    int target = rg[mo + 2];
                    if (target < 0) {
                        return haveAccept ? lastAcceptPos : -1;
                    }
                    int requiredMask = rg[mo + 4];
                    if (requiredMask != 0) {
                        if (posFlags < 0) {
                            posFlags = positionFlags(input, pos, to);
                        }
                        if ((posFlags & requiredMask) != requiredMask) {
                            return haveAccept ? lastAcceptPos : -1;
                        }
                    }
                    state = target;
                    if (c > 0xFFFF) {
                        pos++;
                    }
                    int entryReq = sem[state];
                    if (entryReq != 0 && !maskSatisfied(input, pos + 1, to, entryReq)) {
                        return haveAccept ? lastAcceptPos : -1;
                    }
                    matched = true;
                    break;
                }
            } else {
                // Non-disjoint state: binary search + prefix-max walk.
                int rlo = 0, rhi = count - 1, anchor = -1;
                while (rlo <= rhi) {
                    int mid = (rlo + rhi) >>> 1;
                    if (rg[(base + mid) * 5] <= c) {
                        anchor = mid;
                        rlo = mid + 1;
                    } else {
                        rhi = mid - 1;
                    }
                }
                int chosen = -1, chosenTarget = 0;
                // Most-specific satisfied mask owns the step (popcount, ties
                // to lowest index) — see extractFrom for the protocol; no
                // fallthrough past a dead owning context, no shadowing of
                // specific entries by broad mask-0 ranges either.
                int bestSpec = -1;
                for (int i = anchor; i >= 0 && rhp[base + i] >= c; i--) {
                    int o = (base + i) * 5;
                    if (c <= rg[o + 1]) {
                        int requiredMask = rg[o + 4];
                        if (requiredMask != 0) {
                            if (posFlags < 0) {
                                posFlags = positionFlags(input, pos, to);
                            }
                            if ((posFlags & requiredMask) != requiredMask) {
                                continue;
                            }
                        }
                        int spec = Integer.bitCount(requiredMask);
                        if (spec >= bestSpec) {
                            chosen = o;
                            chosenTarget = rg[o + 2];
                            bestSpec = spec;
                        }
                    }
                }
                if (chosen < 0 || chosenTarget < 0) {
                    return haveAccept ? lastAcceptPos : -1; // owning context dead: no fallthrough
                }
                {
                    state = chosenTarget;
                    if (c > 0xFFFF) {
                        pos++;
                    }
                    int entryReq = sem[state];
                    if (entryReq != 0 && !maskSatisfied(input, pos + 1, to, entryReq)) {
                        return haveAccept ? lastAcceptPos : -1;
                    }
                    matched = true;
                }
            }
            if (!matched) {
                break;
            }
            posFlags = -1;
        }
        return haveAccept ? lastAcceptPos : -1;
    }

    private MatchHolder runGeneric(CharSequence input, int from, int to, boolean anchored, MatchScratch sc) {
        // Carrier-regs reuse across restarts (the wholeWalk idiom): failed
        // restarts pay only the -1 refill, not a fresh allocation per start;
        // successful escapes clone into the MatchHolder, so reuse is safe.
        int startSearch = from;
        while (true) {
            final int[] regs;
            final TagTree tree = multi ? takeTree(sc) : null;
            if (regSize == 0) {
                regs = null;
            } else {
                regs = takeRegs(regSize, sc);
                Arrays.fill(regs, 0, regSize, -1);
            }
            int state = startState;
            int lastAcceptPos = -1;
            boolean haveAccept = false;
            int pos = startSearch;

            // Entry check for start state — inline
            {
                int entryReq = stateEntryMask[state];
                if (entryReq != 0 && !maskSatisfiedCS(input, pos, to, entryReq)) {
                    if (anchored) {
                        return null;
                    }
                    if (absBeginBound) {
                        return null;
                    }
                    startSearch++;
                    if (scanSkipsPairs && Alphabet.pairInterior(input, startSearch)) {
                        startSearch++;
                    }
                    if (startSearch > to) {
                        return null;
                    }
                    continue;
                }
            }

            int posFlags = -1;
            for (;; pos++) {
                int meta = stateMeta[state];
                if ((meta & 1) != 0) {
                    final int[] fm = this.finalOpsByMask;
                    if (fm != null) {
                        if (posFlags < 0) {
                            posFlags = positionFlagsCS(input, pos, to);
                        }
                        int cell = fm[state * posCells + posFlags];
                        if (cell >= 0) {
                            lastAcceptPos = pos;
                            haveAccept = true;
                            if (regs != null && cell != 0) {
                                applyOps(ops, cell, regs, pos, tree);
                            }
                            if (!anchored && !longestMatch && stopNow(state, posFlags)) {
                                break;
                            }
                        }
                    } else {
                        int acceptMask = stateAcceptMask[state];
                        if (acceptMask == 0) {
                            lastAcceptPos = pos;
                            haveAccept = true;
                            if (regs != null) {
                                applyFinalOps(state, regs, pos, tree);
                            }
                            if (!anchored && !longestMatch) {
                                if (posFlags < 0) {
                                    posFlags = positionFlagsCS(input, pos, to);
                                }
                                if (stopNow(state, posFlags)) {
                                    break;
                                }
                            }
                        } else {
                            if (posFlags < 0) {
                                posFlags = positionFlagsCS(input, pos, to);
                            }
                            if ((posFlags & acceptMask) == acceptMask) {
                                lastAcceptPos = pos;
                                haveAccept = true;
                                if (regs != null) {
                                    applyFinalOps(state, regs, pos, tree);
                                }
                                if (!anchored && !longestMatch && stopNow(state, posFlags)) {
                                    break;
                                }
                            }
                        }
                    }
                }
                if (pos >= to) {
                    break;
                }
                int c = Alphabet.decode(input, pos, to);
                int base = stateBase[state];
                int count = (meta >>> 1) & 0xFFFF;
                int chosen = -1, chosenTarget = 0;
                int riW = wide != null ? wideRangeIndex(state, c) : Integer.MIN_VALUE;
                if (riW != Integer.MIN_VALUE) {
                    // Wide dispatch (see WideDispatch): the one containing
                    // entry, exact under disjointness. A dead marker or an
                    // unsatisfied required mask ends the walk — no other
                    // entry contains c, so there is nothing to fall to.
                    if (riW >= 0) {
                        int o = (base + riW) * 5;
                        chosen = o;
                        chosenTarget = ranges[o + 2];
                        int requiredMask = ranges[o + 4];
                        if (requiredMask != 0) {
                            if (posFlags < 0) {
                                posFlags = positionFlagsCS(input, pos, to);
                            }
                            if ((posFlags & requiredMask) != requiredMask) {
                                chosen = -1;
                            }
                        }
                    }
                } else {
                    // Binary search + prefix-max walk (CharSequence variant).
                    int rlo = 0, rhi = count - 1, anchor = -1;
                    while (rlo <= rhi) {
                        int mid = (rlo + rhi) >>> 1;
                        if (ranges[(base + mid) * 5] <= c) {
                            anchor = mid;
                            rlo = mid + 1;
                        } else {
                            rhi = mid - 1;
                        }
                    }
                    // Most-specific satisfied mask owns the step (popcount, ties
                    // to lowest index) — see extractFrom for the protocol.
                    int bestSpec = -1;
                    for (int i = anchor; i >= 0 && rhp[base + i] >= c; i--) {
                        int o = (base + i) * 5;
                        if (c <= ranges[o + 1]) {
                            int requiredMask = ranges[o + 4];
                            if (requiredMask != 0) {
                                if (posFlags < 0) {
                                    posFlags = positionFlagsCS(input, pos, to);
                                }
                                if ((posFlags & requiredMask) != requiredMask) {
                                    continue;
                                }
                            }
                            int spec = Integer.bitCount(requiredMask);
                            if (spec >= bestSpec) {
                                chosen = o;
                                chosenTarget = ranges[o + 2];
                                bestSpec = spec;
                            }
                        }
                    }
                }
                if (chosen < 0 || chosenTarget < 0) {
                    break;
                }
                // Mask before ops — see extractFrom.
                int width = c > 0xFFFF ? 2 : 1;
                int entryReqNext = stateEntryMask[chosenTarget];
                if (entryReqNext != 0 && !maskSatisfiedCS(input, pos + width, to, entryReqNext)) {
                    break;
                }
                if (regs != null) {
                    int opsOff = ranges[chosen + 3];
                    if (opsOff != 0) {
                        applyOps(ops, opsOff, regs, pos, tree);
                    }
                }
                state = chosenTarget;
                if (width == 2) {
                    pos++;
                }
                posFlags = -1;
            }
            if (haveAccept) {
                if (anchored && lastAcceptPos != to) {
                    return null;
                }
                // Eager finals at accept-record time (see extractFrom).
                return new MatchHolder(startSearch, lastAcceptPos, regs == null ? new int[0] : regs.clone(),
                    multi ? tree.snapshot() : null);
            }
            if (anchored) {
                return null;
            }
            if (absBeginBound) {
                return null;
            }
            startSearch++;
            if (scanSkipsPairs && Alphabet.pairInterior(input, startSearch)) {
                startSearch++;
            }
            if (startSearch > to) {
                return null;
            }
        }
    }

    /**
     * Apply an accepting state's φ final ops into {@code regs} at the moment
     * the accept is recorded (BT22's match-declaration semantics). φ reads the
     * accept config's WORKING registers, which hold the correct values only at
     * accept time — any transition taken afterwards may clobber them. Later
     * accepts overwrite earlier ones (last write wins), so the register file at
     * walk end already carries the last accept's finals. Both tiers apply φ
     * eagerly — a lazy ψ replay at walk end would be unsound; with eager
     * application {@code pos == lastAcceptPos} always holds and φ is the
     * correct choice.
     */
    private void applyFinalOps(int state, int[] regs, int pos, TagTree tree) {
        int foff = stateFinalOpsOff[state];
        if (foff != 0) {
            applyOps(ops, foff, regs, pos, tree);
        }
    }

    /**
     * Determine whether to break the match loop on accept, based on the
     * position-aware per-(state, posFlags) cell of {@link Tdfa#stopOnAcceptMask}:
     * - {@link Tdfa#NEVER_STOP}: don't stop (sym-bearing config outranks accept
     * under this posFlags, or accept unreachable).
     * - 0: stop (accept is the highest-priority live outcome under this posFlags).
     * The {@code posFlags} argument is unused beyond the array index computed
     * by the caller; kept for signature parity.
     */
    private boolean stopNow(int state, int posFlags) {
        byte[] u = stopMaskUniform;
        if (u != null) {
            return u[state] == 0;
        }
        return stopOnAcceptMask[state * posCells + posFlags] != Tdfa.NEVER_STOP;
    }

    /**
     * Mask-directed subset test: {@code (positionFlags(s, pos, len) & mask)
     * == mask} evaluated bit-by-bit, cheapest bits first, WITHOUT
     * materialising the bits the mask never reads (the campaign 2026-10-16
     * finding-3 per-step cost: an {@code \A}-gated ladder step pays one
     * comparison here instead of the full line/word predicate battery).
     * Used at the single-shot consult sites — the start-state entry check
     * (once per walk) and the post-transition entry check (once per step,
     * for the position AFTER the consumed char, which no other probe of
     * the step reads) — while the candidate loops and accept/stop probes,
     * which may consult the same position's bits repeatedly within one
     * step, keep the one-shot cached {@link #positionFlags} computation
     * (a per-candidate mask-directed call recomputes the word pair per
     * candidate and measured 40% slower on the \b-heavy campaign seed).
     * Each bit's formula is the exact twin of the one in {@code
     * positionFlags}; the word pair is computed once when either boundary
     * bit is requested. A mask containing both {@code WORD_BOUNDARY} and
     * {@code NO_WORD_BOUNDARY} is unsatisfiable by construction — the
     * computed flags are exclusive, so the subset test can never hold.
     */
    private boolean maskSatisfied(String s, int pos, int len, int mask) {
        if ((mask & Tnfa.ABS_BEGIN) != 0 && pos != 0) {
            return false;
        }
        if ((mask & Tnfa.ABS_END) != 0 && pos != len) {
            return false;
        }
        if ((mask & Tnfa.BEGIN_TEXT) != 0 && !((emptyLastLine || pos < len) && (pos == 0 || lineBegin(s, pos, len)))) {
            return false;
        }
        if ((mask & Tnfa.END_TEXT) != 0 && !(pos == len || lineEnd(s, pos, len))) {
            return false;
        }
        if ((mask & Tnfa.FINAL_END) != 0 && !finalEnd(s, pos, len)) {
            return false;
        }
        int wb = mask & (Tnfa.WORD_BOUNDARY | Tnfa.NO_WORD_BOUNDARY);
        if (wb == (Tnfa.WORD_BOUNDARY | Tnfa.NO_WORD_BOUNDARY)) {
            return false;
        }
        if (wb != 0 && (isWordBefore(s, pos) != isWordAt(s, pos, len)) != (wb == Tnfa.WORD_BOUNDARY)) {
            return false;
        }
        return true;
    }

    /** CharSequence twin of {@link #maskSatisfied(String, int, int, int)}. */
    private boolean maskSatisfiedCS(CharSequence s, int pos, int len, int mask) {
        if ((mask & Tnfa.ABS_BEGIN) != 0 && pos != 0) {
            return false;
        }
        if ((mask & Tnfa.ABS_END) != 0 && pos != len) {
            return false;
        }
        if ((mask & Tnfa.BEGIN_TEXT) != 0 && !((emptyLastLine || pos < len) && (pos == 0 || lineBegin(s, pos, len)))) {
            return false;
        }
        if ((mask & Tnfa.END_TEXT) != 0 && !(pos == len || lineEnd(s, pos, len))) {
            return false;
        }
        if ((mask & Tnfa.FINAL_END) != 0 && !finalEnd(s, pos, len)) {
            return false;
        }
        int wb = mask & (Tnfa.WORD_BOUNDARY | Tnfa.NO_WORD_BOUNDARY);
        if (wb == (Tnfa.WORD_BOUNDARY | Tnfa.NO_WORD_BOUNDARY)) {
            return false;
        }
        if (wb != 0 && (isWordBefore(s, pos) != isWordAt(s, pos, len)) != (wb == Tnfa.WORD_BOUNDARY)) {
            return false;
        }
        return true;
    }

    /**
     * Compute the position-flags for `pos` in a String.
     */
    private int positionFlags(String s, int pos, int len) {
        int flags = 0;
        // (emptyLastLine || pos < len): java's (?m)^ never matches at end
        // of input — not even at position 0 of an EMPTY input ("Perl does
        // not match ^ at end of input even after newline"); the RE2 side
        // (axis set) keeps today's unconditional position-0 begin.
        if ((emptyLastLine || pos < len) && (pos == 0 || lineBegin(s, pos, len))) {
            flags |= Tnfa.BEGIN_TEXT;
        }
        if (pos == len || lineEnd(s, pos, len)) {
            flags |= Tnfa.END_TEXT;
        }
        if (pos == 0) {
            flags |= Tnfa.ABS_BEGIN;
        } // \A: absolute start, never affected by (?m)
        if (pos == len) {
            flags |= Tnfa.ABS_END;
        } // \z: absolute end, never affected by (?m)
        if (needsFinalEnd && finalEnd(s, pos, len)) {
            flags |= Tnfa.FINAL_END;
        } // plain $ (END_OF_TEXT_ONLY unset): end, or before the final terminator run
        if (needsWordFlags) {
            boolean prevWord = isWordBefore(s, pos);
            boolean currWord = isWordAt(s, pos, len);
            if (prevWord != currWord) {
                flags |= Tnfa.WORD_BOUNDARY;
            } else {
                flags |= Tnfa.NO_WORD_BOUNDARY;
            }
        }
        return flags;
    }

    // ===== Zero-width assertion position-flag computation =====

    /**
     * Same for a generic CharSequence.
     */
    private int positionFlagsCS(CharSequence s, int pos, int len) {
        int flags = 0;
        // (emptyLastLine || pos < len): java's (?m)^ never matches at end
        // of input — not even at position 0 of an EMPTY input ("Perl does
        // not match ^ at end of input even after newline"); the RE2 side
        // (axis set) keeps today's unconditional position-0 begin.
        if ((emptyLastLine || pos < len) && (pos == 0 || lineBegin(s, pos, len))) {
            flags |= Tnfa.BEGIN_TEXT;
        }
        if (pos == len || lineEnd(s, pos, len)) {
            flags |= Tnfa.END_TEXT;
        }
        if (pos == 0) {
            flags |= Tnfa.ABS_BEGIN;
        }
        if (pos == len) {
            flags |= Tnfa.ABS_END;
        }
        if (needsFinalEnd && finalEnd(s, pos, len)) {
            flags |= Tnfa.FINAL_END;
        }
        if (needsWordFlags) {
            boolean prevWord = isWordBefore(s, pos);
            boolean currWord = isWordAt(s, pos, len);
            if (prevWord != currWord) {
                flags |= Tnfa.WORD_BOUNDARY;
            } else {
                flags |= Tnfa.NO_WORD_BOUNDARY;
            }
        }
        return flags;
    }

    /**
     * Line-begin predicate for BEGIN_TEXT ({@code ^} under (?m)), the pos &gt; 0
     * half — {@code pos == 0} is handled by the callers. Axis-selected:
     * <ul>
     * <li>{@code \n}-only (UNIX_LINES set, the RE2 side): after any
     * {@code \n}, including the trailing one (subject to EMPTY_LAST_LINE).
     * <li>full java.util.regex set: after any terminator of the set, never
     * inside a {@code \r\n} pair, and — unless EMPTY_LAST_LINE is set —
     * never after the trailing terminator (JDK: {@code (?m)^} on
     * {@code "a\n"} matches at 0 only; on {@code "a\r\nb"} at 0 and 3).
     * </ul>
     */
    private boolean lineBegin(String s, int pos, int len) {
        char prev = s.charAt(pos - 1);
        if (nlOnlyTerminators) {
            return prev == '\n' && (emptyLastLine || pos < len);
        }
        if (!isJurTerminator(prev)) {
            return false;
        }
        if (prev == '\r' && pos < len && s.charAt(pos) == '\n') {
            return false; // interior of a \r\n
        }
        return emptyLastLine || pos < len;
    }

    /** CharSequence twin of {@link #lineBegin(String, int, int)}. */
    private boolean lineBegin(CharSequence s, int pos, int len) {
        char prev = s.charAt(pos - 1);
        if (nlOnlyTerminators) {
            return prev == '\n' && (emptyLastLine || pos < len);
        }
        if (!isJurTerminator(prev)) {
            return false;
        }
        if (prev == '\r' && pos < len && s.charAt(pos) == '\n') {
            return false; // interior of a \r\n
        }
        return emptyLastLine || pos < len;
    }

    /**
     * Line-end predicate for END_TEXT ({@code $} under (?m)), the pos &lt; len
     * half — {@code pos == len} is handled by the callers. Axis-selected:
     * before any {@code \n} (UNIX_LINES) or any terminator of the full set
     * minus {@code \r\n} interiors (JDK: {@code (?m)$} on {@code "a\r\nb"}
     * matches at 1 and 4, never at 2).
     */
    private boolean lineEnd(String s, int pos, int len) {
        char c = s.charAt(pos);
        if (nlOnlyTerminators) {
            return c == '\n';
        }
        if (!isJurTerminator(c)) {
            return false;
        }
        return !(c == '\n' && pos > 0 && s.charAt(pos - 1) == '\r');
    }

    /** CharSequence twin of {@link #lineEnd(String, int, int)}. */
    private boolean lineEnd(CharSequence s, int pos, int len) {
        char c = s.charAt(pos);
        if (nlOnlyTerminators) {
            return c == '\n';
        }
        if (!isJurTerminator(c)) {
            return false;
        }
        return !(c == '\n' && pos > 0 && s.charAt(pos - 1) == '\r');
    }

    /**
     * FINAL_END predicate (plain {@code $}, END_OF_TEXT_ONLY unset):
     * end of input, or immediately before the FINAL terminator run —
     * where a run is a lone terminator or one {@code \r\n} (JDK-exact:
     * {@code $} on {@code "a\n\n"} matches at 2 and 3; on
     * {@code "a\r\n"} at 1 and 3; under UNIX_LINES on {@code "a\r\n"}
     * at 2 and 3 — {@code \r} is then a plain char, so the interior
     * exclusion is off).
     */
    private boolean finalEnd(String s, int pos, int len) {
        if (pos == len) {
            return true;
        }
        if (pos > len) {
            return false;
        }
        char c = s.charAt(pos);
        if (nlOnlyTerminators) {
            return c == '\n' && pos + 1 == len;
        }
        if (!isJurTerminator(c)) {
            return false;
        }
        if (c == '\n' && pos > 0 && s.charAt(pos - 1) == '\r') {
            return false; // interior of the final \r\n — the \r position owns it
        }
        if (c == '\r') {
            if (pos + 1 == len) {
                return true;
            }
            return s.charAt(pos + 1) == '\n' && pos + 2 == len;
        }
        return pos + 1 == len; // \n, U+0085, U+2028, U+2029
    }

    /** CharSequence twin of {@link #finalEnd(String, int, int)}. */
    private boolean finalEnd(CharSequence s, int pos, int len) {
        if (pos == len) {
            return true;
        }
        if (pos > len) {
            return false;
        }
        char c = s.charAt(pos);
        if (nlOnlyTerminators) {
            return c == '\n' && pos + 1 == len;
        }
        if (!isJurTerminator(c)) {
            return false;
        }
        if (c == '\n' && pos > 0 && s.charAt(pos - 1) == '\r') {
            return false; // interior of the final \r\n — the \r position owns it
        }
        if (c == '\r') {
            if (pos + 1 == len) {
                return true;
            }
            return s.charAt(pos + 1) == '\n' && pos + 2 == len;
        }
        return pos + 1 == len; // \n, U+0085, U+2028, U+2029
    }

    /** Membership in the full java.util.regex line-terminator set
     *  (character view — {@code \r\n} is two units of one terminator). */
    private static boolean isJurTerminator(char c) {
        return c == '\n' || c == '\r' || c == '\u0085' || c == '\u2028' || c == '\u2029';
    }

    /**
     * Fast-path eligibility of an artifact — the ONE predicate both engine
     * tiers consult so their fast paths cannot drift: not multiline,
     * pairwise-disjoint ranges, and no entry / accept / required mask
     * anywhere. The VM's instance {@link #fastPath} additionally requires
     * the eager ASCII dispatch tables; the ASM tier additionally applies
     * its inline-size budget (pickMode).
     *
     * <p>NOTE: per-mask final variants (stateFinalOpsByMask != null) do NOT
     * disqualify — the fast walks' fm branches handle them and match the
     * mask-aware walks exactly (suppressed-accept fall-through + posFlags
     * reset). A belt-and-braces exclusion here costs ~10x on anchored
     * matches() for φ-variant patterns (quick-bench info.anchored.asm).
     */
    public static boolean fastPathEligible(Tdfa tdfa) {
        if (tdfa.multiline || !RunnerTables.checkRangesDisjoint(tdfa)) {
            return false;
        }
        for (int mask : tdfa.stateEntryMask) {
            if (mask != 0) {
                return false;
            }
        }
        for (int mask : tdfa.stateAcceptMask) {
            if (mask != 0) {
                return false;
            }
        }
        for (int i = 4; i < tdfa.ranges.length; i += 5) {
            if (tdfa.ranges[i] != 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * True if the DFA qualifies for the no-masks fast path: eligible (see
     * {@link #fastPathEligible}) AND small enough for the eager ASCII
     * dispatch tables, which the fastPath methods dereference
     * unconditionally.
     */
    private boolean computeFastPath(Tdfa tdfa) {
        // Multi-valued walks carry a TagTree for their append ops; the fast
        // path's flat-table loop has no tree plumbing — the generic walks
        // serve multi-valued artifacts.
        return asciiTables && fastPathEligible(tdfa) && !tdfa.multiValued();
    }

    /**
     * RE2's isWordRune: ASCII word chars [_0-9A-Za-z].
     * When {@link #unicodeWordBoundary} is true, checks the Unicode {@code \w}
     * ranges (matching {@code java.util.regex} with {@code UNICODE_CHARACTER_CLASS}).
     * Both via the {@link #wordBits} BMP bitset — one array load.
     */
    private boolean isWordChar(char c) {
        return (wordBits[c >>> 6] >>> (c & 63) & 1L) != 0L;
    }

    /**
     * Binary-search the Unicode {@code \w} ranges by CODEPOINT. The ranges include
     * supplementary codepoints, so decoding a surrogate pair before calling this
     * recognises supplementary word characters (e.g. U+1D504 MATHEMATICAL FRAKTUR A).
     */
    private boolean isUnicodeWordCodepoint(int cp) {
        int[] wr = wordRanges;
        if (wr == null) {
            return false;
        }
        int lo = 0, hi = wr.length / 2 - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int rLo = wr[2 * mid], rHi = wr[2 * mid + 1];
            if (cp < rLo) {
                hi = mid - 1;
            } else if (cp > rHi) {
                lo = mid + 1;
            } else {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether the character immediately BEFORE {@code pos} is a word character.
     * Under {@code (?u)} a supplementary letter's UTF-16 low surrogate at
     * {@code pos-1} is decoded with its high surrogate at {@code pos-2} first,
     * so boundaries adjacent to supplementary word chars are computed on the
     * full codepoint. In ASCII mode surrogate halves are simply non-word.
     */
    private boolean isWordBefore(CharSequence s, int pos) {
        if (pos <= 0) {
            return false;
        }
        char c = s.charAt(pos - 1);
        if (unicodeWordBoundary && c >= Character.MIN_LOW_SURROGATE && c <= Character.MAX_LOW_SURROGATE && pos >= 2) {
            char h = s.charAt(pos - 2);
            if (h >= Character.MIN_HIGH_SURROGATE && h <= Character.MAX_HIGH_SURROGATE) {
                return isUnicodeWordCodepoint(((h - 0xD800) << 10) + (c - 0xDC00) + 0x10000);
            }
        }
        return isWordChar(c);
    }

    /**
     * Whether the character AT {@code pos} is a word character; a high surrogate
     * at {@code pos} paired with a low surrogate at {@code pos+1} is decoded to
     * the full codepoint under {@code (?u)}.
     */
    private boolean isWordAt(CharSequence s, int pos, int len) {
        if (pos >= len) {
            return false;
        }
        char c = s.charAt(pos);
        if (unicodeWordBoundary && c >= Character.MIN_HIGH_SURROGATE && c <= Character.MAX_HIGH_SURROGATE
            && pos + 1 < len) {
            char l = s.charAt(pos + 1);
            if (l >= Character.MIN_LOW_SURROGATE && l <= Character.MAX_LOW_SURROGATE) {
                return isUnicodeWordCodepoint(((c - 0xD800) << 10) + (l - 0xDC00) + 0x10000);
            }
        }
        return isWordChar(c);
    }

    /**
     * Which branch of the search ladder served one public entry call.
     */
    public enum Strategy {
        LITERAL, // literalNeedle -> String.indexOf
        PREFIX, // required literal chain -> indexOf candidate walks
        CAND_SCAN, // first-char-set bit scan + exact walks (short input)
        EXACT_FROM, // one exact walk from the requested start
        ORIGIN_SIM, // budgeted origin-tracking multi-state simulation
        RAW_SCAN, // unmemoized live-set simulation (the former TRIGGER rung's kill-point scan; its memo is deleted)
        WALK_RESTART, // defensive per-start restart loop
        ANCHORED_FAST, // flat-table anchored loop (fastPath)
        ANCHORED, // generic anchored walk
        GENERIC // CharSequence (non-String) fallback
    }

    // ===== streaming input (docs/streaming-input.md) =====
    //
    // The streaming driver: leftmost-first search over a chunk-fed
    // window, carried across pulls, without materializing the input.
    // The protocol is the String ladder's definitive semantics made
    // resumable — the walk (extractFrom's machine) runs until it
    // confirms, dies, or pends at the open frontier; all mutable state
    // rides the StreamMatcher carrier (the per-call carrier of
    // docs/runner-immutability.md — the runner stays frozen). The
    // pending rule is the whole soundness argument: every position
    // predicate this engine evaluates reads at most the units around
    // `pos` and compares `pos` with the end (see positionFlags /
    // maskSatisfied), so at pos < frontier the window-local answers are
    // provably identical to the true-EOF answers, and only
    // frontier-touching evaluations pend (streamMaskSatisfied /
    // streamPositionFlags encode exactly that; a lone high surrogate at
    // the frontier pends because its pair-completing unit decides both
    // the decode and the word flags). The literal-needle rung and the
    // first-char-set candidate gate serve their shapes; the kill-point
    // simulation runs once per epoch at EOF — mid-stream the leftmost
    // pending walk blocks candidate enumeration, so no bound is needed
    // before then. Answers are identical to the String surface over the
    // growing prefix; the chunk-split differential battery pins it.

    /**
     * A streaming matcher over {@code source} (see
     * {@code docs/streaming-input.md}): leftmost-first search over
     * chunked input, absolute stream offsets, one matcher per stream.
     * Linked by name from the generated classes' {@code StreamEngine}
     * implementation — both tiers serve streaming through this one
     * driver, so their answers are identical by construction.
     */
    @EmittedSurface
    @Override
    public StreamMatcher streamingMatcher(ChunkSource source) {
        return new StreamMatcher(this, source);
    }

    /** Advance the stream's search to the next confirmed match (the driver). */
    boolean streamAdvance(StreamMatcher m) {
        if (m.done) {
            return false;
        }
        if (literalNeedle != null) {
            return streamLiteralAdvance(m);
        }
        for (;;) {
            if (m.walkActive) {
                int st = streamWalkRun(m);
                if (st == SW_PENDING) {
                    m.fill();
                    continue;
                }
                MatchResult r = st == SW_CONFIRMED ? streamResult(m) : null;
                if (r != null) {
                    m.report(r);
                    return true;
                }
                m.walkActive = false;
                m.from = Math.max(m.from, m.walkStart + 1);
                continue;
            }
            int f = m.length();
            if (m.atEof()) {
                if (m.from > f) {
                    m.done = true;
                    return false;
                }
                if (!m.eofScanned) {
                    // EOF endgame: the kill-point simulation bounds the
                    // restart enumeration (its String-ladder role) and
                    // answers the no-match verdict in one linear pass.
                    m.eofScanned = true;
                    int w = streamEofScan(m);
                    if (w < 0) {
                        m.done = true;
                        return false;
                    }
                    if (w > m.from) {
                        m.from = w;
                    }
                }
            } else if (m.from > f) {
                m.fill(); // zero-width advance past the frontier: wait for the next position
                continue;
            }
            if (absBeginBound && m.from > 0) {
                // Every walk past 0 crosses an \A gate and dies —
                // restartExtract's bound, one check instead of n walks.
                m.done = true;
                return false;
            }
            int cand = streamCandidate(m);
            if (cand < 0) {
                if (m.atEof()) {
                    m.done = true;
                    return false;
                }
                m.fill();
                continue;
            }
            int entryReq = stateEntryMask[startState];
            if (entryReq != 0) {
                int r = streamMaskSatisfied(m, cand, entryReq);
                if (r == SME_PEND) {
                    m.fill();
                    continue;
                }
                if (r == SME_NO) {
                    m.from = cand + 1;
                    continue;
                }
            }
            m.startWalk(cand, regSize, multi);
        }
    }

    /**
     * Next candidate start: the epoch's origin is tried as-is (the find()
     * from-position discipline); enumeration past it takes the
     * first-char-set gate (when the start state cannot accept) and the
     * surrogate-pair interior skip. The frontier itself is always a
     * candidate — the empty-match potential at end of input.
     */
    private int streamCandidate(StreamMatcher m) {
        final int f = m.length();
        int p = m.from;
        if (p > f) {
            return -1;
        }
        if (p == m.epochFrom) {
            return p;
        }
        final long[] sb = this.startBits;
        while (p < f) {
            char c = m.charAt(p);
            if (sb != null && (sb[c >>> 6] >>> (c & 63) & 1L) == 0L) {
                p++;
                continue;
            }
            if (scanSkipsPairs && Alphabet.pairInterior(m, p)) {
                p++;
                continue;
            }
            break;
        }
        return p;
    }

    /**
     * Run the active walk to an outcome — extractFrom's transition
     * machinery (flat dispatch / wide index / binary search with
     * most-specific-mask ownership, eager final ops, the stop table)
     * over the window, resumable: every suspension point is a pending
     * rule violation, and {@code walkAcceptDone} carries the one record
     * that can complete before a pending stop decision, so a resumed
     * walk skips the record and re-decides the stop. Transition phases
     * suspend only before any mutation (ops, state, position), so
     * re-entry recomputes them deterministically.
     */
    private int streamWalkRun(StreamMatcher m) {
        final int[] sm = this.stateMeta;
        final int[] rg = this.ranges;
        final int[] op = this.ops;
        for (;;) {
            final int f = m.length();
            final boolean eof = m.atEof();
            final int pos = m.walkPos;
            final int state = m.walkState;
            final int[] regs = m.walkRegs; // null iff regSize == 0
            final TagTree tree = m.walkTree; // non-null iff multi

            if (!m.walkAcceptDone) {
                m.walkAcceptDone = true;
                if ((sm[state] & 1) != 0) {
                    final int[] fm = this.finalOpsByMask;
                    if (fm != null) {
                        if (m.walkPosFlags < 0) {
                            int pf = streamPositionFlags(m, pos);
                            if (pf == SPF_PEND) {
                                m.walkAcceptDone = false;
                                return SW_PENDING;
                            }
                            m.walkPosFlags = pf;
                        }
                        int cell = fm[state * posCells + m.walkPosFlags];
                        if (cell >= 0) {
                            m.walkLastAccept = pos;
                            m.walkHaveAccept = true;
                            if (regs != null && cell != 0) {
                                applyOps(op, cell, regs, pos, tree);
                            }
                        }
                    } else if (stateAcceptMask[state] == 0) {
                        m.walkLastAccept = pos;
                        m.walkHaveAccept = true;
                        if (regs != null) {
                            applyFinalOps(state, regs, pos, tree);
                        }
                    } else {
                        int acceptMask = stateAcceptMask[state];
                        if (m.walkPosFlags < 0) {
                            int pf = streamPositionFlags(m, pos);
                            if (pf == SPF_PEND) {
                                m.walkAcceptDone = false;
                                return SW_PENDING;
                            }
                            m.walkPosFlags = pf;
                        }
                        if ((m.walkPosFlags & acceptMask) == acceptMask) {
                            m.walkLastAccept = pos;
                            m.walkHaveAccept = true;
                            if (regs != null) {
                                applyFinalOps(state, regs, pos, tree);
                            }
                        }
                    }
                }
            }
            // Stop-on-accept for a record at THIS position: uniform tables
            // need no flags; the lazy flags here may pend (resume skips the
            // record above — walkAcceptDone — and re-decides the stop).
            if (m.walkHaveAccept && m.walkLastAccept == pos && !longestMatch) {
                if (stopMaskUniform == null && m.walkPosFlags < 0) {
                    int pf = streamPositionFlags(m, pos);
                    if (pf == SPF_PEND) {
                        return SW_PENDING;
                    }
                    m.walkPosFlags = pf;
                }
                if (stopNow(state, m.walkPosFlags)) {
                    return SW_CONFIRMED;
                }
            }
            if (pos >= f) {
                if (eof) {
                    return m.walkHaveAccept ? SW_CONFIRMED : SW_DIED;
                }
                return SW_PENDING;
            }
            char cu = m.charAt(pos);
            if (!eof && cu >= Character.MIN_HIGH_SURROGATE && cu <= Character.MAX_HIGH_SURROGATE && pos + 1 >= f) {
                return SW_PENDING; // pair-completing unit at the frontier
            }
            int c = Alphabet.decode(m, pos, f);
            int base = stateBase[state];
            int count = (sm[state] >>> 1) & 0xFFFF;
            int chosen = -1, chosenTarget = 0;
            int ri;
            if (asciiRangeFlat != null && c < latinLimit) {
                // Disjoint ranges: the flat table is exact (see extractFrom).
                ri = asciiRangeFlat[state * latinLimit + c];
            } else if (wide != null) {
                ri = wideRangeIndex(state, c);
            } else {
                ri = Integer.MIN_VALUE;
            }
            if (ri == Integer.MIN_VALUE) {
                // Binary search rightmost entry with lo <= c, walk back over
                // containing entries; the MOST SPECIFIC satisfied mask owns
                // the step (see extractFrom).
                int rlo = 0, rhi = count - 1, anchor = -1;
                while (rlo <= rhi) {
                    int mid = (rlo + rhi) >>> 1;
                    if (rg[(base + mid) * 5] <= c) {
                        anchor = mid;
                        rlo = mid + 1;
                    } else {
                        rhi = mid - 1;
                    }
                }
                int best = -1, bestSpec = -1;
                for (int i = anchor; i >= 0 && rhp[base + i] >= c; i--) {
                    int o = (base + i) * 5;
                    if (c <= rg[o + 1]) {
                        int requiredMask = rg[o + 4];
                        if (requiredMask != 0) {
                            if (m.walkPosFlags < 0) {
                                int pf = streamPositionFlags(m, pos);
                                if (pf == SPF_PEND) {
                                    return SW_PENDING;
                                }
                                m.walkPosFlags = pf;
                            }
                            if ((m.walkPosFlags & requiredMask) != requiredMask) {
                                continue;
                            }
                        }
                        int spec = Integer.bitCount(requiredMask);
                        if (spec >= bestSpec) {
                            best = i;
                            bestSpec = spec;
                        } // >= : lower index wins ties
                    }
                }
                if (best >= 0) {
                    int o = (base + best) * 5;
                    int target = rg[o + 2];
                    if (target < 0) {
                        // Dead marker of the owning context: no continuation
                        // under this posFlags — a recorded accept still reports.
                        return m.walkHaveAccept ? SW_CONFIRMED : SW_DIED;
                    }
                    chosen = o;
                    chosenTarget = target;
                }
            } else if (ri >= 0) {
                int o = (base + ri) * 5;
                int target = rg[o + 2];
                if (target >= 0) {
                    int requiredMask = rg[o + 4];
                    boolean ok = requiredMask == 0;
                    if (!ok) {
                        if (m.walkPosFlags < 0) {
                            int pf = streamPositionFlags(m, pos);
                            if (pf == SPF_PEND) {
                                return SW_PENDING;
                            }
                            m.walkPosFlags = pf;
                        }
                        ok = (m.walkPosFlags & requiredMask) == requiredMask;
                    }
                    if (ok) {
                        chosen = o;
                        chosenTarget = target;
                    }
                }
            }
            if (chosen < 0) {
                return m.walkHaveAccept ? SW_CONFIRMED : SW_DIED;
            }
            // Target entry mask is evaluated before the transition's ops run
            // (a mask-failing transition is never taken — see extractFrom).
            int width = c > 0xFFFF ? 2 : 1;
            int entryReqNext = stateEntryMask[chosenTarget];
            if (entryReqNext != 0) {
                int r = streamMaskSatisfied(m, pos + width, entryReqNext);
                if (r == SME_PEND) {
                    return SW_PENDING;
                }
                if (r == SME_NO) {
                    return m.walkHaveAccept ? SW_CONFIRMED : SW_DIED;
                }
            }
            if (regs != null) {
                int opsOff = rg[chosen + 3];
                if (opsOff != 0) {
                    applyOps(op, opsOff, regs, pos, tree);
                }
            }
            m.walkState = chosenTarget;
            m.walkPos = pos + width;
            m.walkAcceptDone = false;
            m.walkPosFlags = -1;
        }
    }

    /** Build the confirmed match's result snapshot (the match() shape). */
    private MatchResult streamResult(StreamMatcher m) {
        int[] r = m.walkRegs == null ? new int[0] : m.walkRegs.clone();
        if (tdfa.fixedBase != null) {
            MatchResult.reconstructFixed(r, tdfa.finalRegBase, tdfa.fixedBase, tdfa.fixedOffset);
        }
        return new MatchResult(r, tdfa.finalRegBase, tdfa.groupCount, m.walkStart, m.walkLastAccept,
            multi ? m.walkTree.snapshot() : null);
    }

    /**
     * The literal-needle rung: every match is one whole-needle occurrence
     * (no captures, no masks — see RunnerTables.detectLiteralNeedle), so
     * the scan keeps only the needle-length tail at the frontier. Guards
     * mirror {@link RunnerTables#literalIndexOf}: a hit whose last unit is
     * a high surrogate ending exactly at the frontier is HELD (the
     * completing unit decides it), a hit ending on a pair's high half is
     * rejected, and codepoint-boundary lanes skip pair-interior starts
     * past the epoch origin.
     */
    private boolean streamLiteralAdvance(StreamMatcher m) {
        final String needle = this.literalNeedle;
        final int nlen = needle.length();
        for (;;) {
            final int f = m.length();
            while (m.scanPos + nlen <= f) {
                int p = m.scanPos;
                if (windowMatches(m, p, needle)) {
                    char last = m.charAt(p + nlen - 1);
                    if (last >= Character.MIN_HIGH_SURROGATE && last <= Character.MAX_HIGH_SURROGATE && p + nlen == f
                        && !m.atEof()) {
                        break; // held: the completing unit is at the frontier
                    }
                    boolean pairHalf = false;
                    if (p + nlen < f) {
                        char after = m.charAt(p + nlen);
                        pairHalf = last >= Character.MIN_HIGH_SURROGATE && last <= Character.MAX_HIGH_SURROGATE
                            && after >= Character.MIN_LOW_SURROGATE && after <= Character.MAX_LOW_SURROGATE;
                    }
                    if (!pairHalf && !(scanSkipsPairs && p > m.epochFrom && Alphabet.pairInterior(m, p))) {
                        int end = p + nlen;
                        m.scanPos = end;
                        m.report(new MatchResult(new int[0], tdfa.finalRegBase, tdfa.groupCount, p, end));
                        return true;
                    }
                }
                m.scanPos = p + 1;
            }
            if (m.atEof()) {
                m.done = true;
                return false;
            }
            m.compactTo(Math.max(m.scanPos - 1, 0)); // the scan's tail: bounded window, needle-length at most
            m.fill();
        }
    }

    private static boolean windowMatches(StreamMatcher m, int p, String needle) {
        for (int i = 0; i < needle.length(); i++) {
            if (m.charAt(p + i) != needle.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    /**
     * The EOF endgame bound: rawScan's kill-point simulation over the
     * final window (unit-semantics seeds when the lane steps units).
     * At EOF every position predicate is exact, so this is the String
     * ladder's trigger rung verbatim: window start W — every
     * configuration alive before W is dead — or -1 = no match at all.
     */
    private int streamEofScan(StreamMatcher m) {
        final int nwords = stateWords;
        final MatchScratch sc = m.scratch;
        int[] live = sc.takeLive(nwords);
        int[] next = sc.takeNext(nwords);
        Arrays.fill(live, 0, nwords, 0);
        live[startState >>> 5] |= 1 << (startState & 31);
        final int ss = startState;
        final int ssMask = 1 << (ss & 31);
        final boolean unitSeeds = !scanSkipsPairs;
        final int from = m.from;
        final int to = m.length();
        int w = from;
        int[] pf = {-1};
        for (int pos = from; pos <= to; pos++) {
            pf[0] = -1;
            if (streamAcceptLive(m, pos, to, live, pf)) {
                return w;
            }
            if (pos == to) {
                break;
            }
            int c = Alphabet.decode(m, pos, to);
            int adv = Alphabet.width(c);
            Arrays.fill(next, 0, nwords, 0);
            boolean empty = true;
            for (int wd = 0; wd < nwords; wd++) {
                int bits = live[wd];
                while (bits != 0) {
                    int bit = Integer.numberOfTrailingZeros(bits);
                    bits &= bits - 1;
                    if (streamScanStep(m, (wd << 5) + bit, c, pos, to, next)) {
                        empty = false;
                    }
                }
            }
            if (unitSeeds && adv == 2) {
                // Interior-unit seeds (see unitRawScan): the empty-match probe
                // at the pair's low unit, then its start-state targets merged.
                if ((stateMeta[startState] & 1) != 0) {
                    pf[0] = -1;
                    if (streamAcceptStateLive(m, pos + 1, to, startState, pf)) {
                        return w;
                    }
                }
                if (streamScanStep(m, startState, m.charAt(pos + 1), pos + 1, to, next)) {
                    empty = false;
                }
            }
            next[ss >>> 5] |= ssMask;
            if (empty) {
                w = pos + adv;
            }
            int[] tmp = live;
            live = next;
            next = tmp;
            if (adv == 2) {
                pos++;
            }
        }
        return -1;
    }

    /** CharSequence twin of {@link #acceptLive} over the streaming window. */
    private boolean streamAcceptLive(StreamMatcher m, int pos, int to, int[] live, int[] flags) {
        final int[] ab = acceptBits;
        if (maskedAcceptStates == null) {
            for (int wd = 0; wd < stateWords; wd++) {
                if ((live[wd] & ab[wd]) != 0) {
                    return true;
                }
            }
            return false;
        }
        for (int wd = 0; wd < stateWords; wd++) {
            int both = live[wd] & ab[wd];
            while (both != 0) {
                int bit = Integer.numberOfTrailingZeros(both);
                both &= both - 1;
                if (streamAcceptStateLive(m, pos, to, (wd << 5) + bit, flags)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** CharSequence twin of {@link #acceptStateLive} (the EOF scan's gates are exact). */
    private boolean streamAcceptStateLive(StreamMatcher m, int pos, int to, int s, int[] flags) {
        final int[] sam = stateAcceptMask;
        final int[] fm = this.finalOpsByMask;
        int gate = fm != null ? -1 : (sam == null ? 0 : sam[s]);
        if (gate == 0) {
            return true;
        }
        if (gate < 0) {
            if (flags[0] < 0) {
                flags[0] = positionFlagsCS(m, pos, to);
            }
            return fm[s * posCells + flags[0]] >= 0;
        }
        return maskSatisfiedCS(m, pos, to, gate);
    }

    /** CharSequence twin of {@link #scanStep} over the streaming window. */
    private boolean streamScanStep(StreamMatcher m, int s, int c, int pos, int to, int[] next) {
        final int[] rg = this.ranges;
        final WideDispatch wd = this.wide;
        int ri = wd != null ? wd.rangeIndex(s, c) : WideDispatch.NONE;
        if (ri != WideDispatch.NONE) {
            if (ri < 0) {
                return false;
            }
            int mo = (stateBase[s] + ri) * 5;
            int t = rg[mo + 2];
            if (t >= 0 && (!rangeMaskGates || maskSatisfiedCS(m, pos, to, rg[mo + 4]))) {
                next[t >>> 5] |= 1 << (t & 31);
                return true;
            }
            return false;
        }
        boolean fired = false;
        int base = stateBase[s];
        int count = (stateMeta[s] >>> 1) & 0xFFFF;
        int rlo = 0, rhi = count - 1, anchor = -1;
        while (rlo <= rhi) {
            int mid = (rlo + rhi) >>> 1;
            if (rg[(base + mid) * 5] <= c) {
                anchor = mid;
                rlo = mid + 1;
            } else {
                rhi = mid - 1;
            }
        }
        for (int i = anchor; i >= 0 && rhp[base + i] >= c; i--) {
            int mo = (base + i) * 5;
            if (c <= rg[mo + 1]) {
                int t = rg[mo + 2];
                if (t >= 0 && (!rangeMaskGates || maskSatisfiedCS(m, pos, to, rg[mo + 4]))) {
                    next[t >>> 5] |= 1 << (t & 31);
                    fired = true;
                }
            }
        }
        return fired;
    }

    /**
     * {@link #maskSatisfiedCS} for an open frontier: {@link #SME_PEND}
     * when the answer could change with the next chunk — an evaluation at
     * the frontier itself, a final-terminator run touching the frontier
     * (locally true, really maybe-false), or a word probe at a high
     * surrogate whose completing unit is unknown. Everything else is
     * decided: the predicates read at most the units around {@code pos},
     * all inside the window.
     */
    private int streamMaskSatisfied(StreamMatcher m, int pos, int mask) {
        if (!m.atEof()) {
            int f = m.length();
            if (pos >= f) {
                return SME_PEND;
            }
            if ((mask & Tnfa.FINAL_END) != 0 && finalEnd(m, pos, f)) {
                return SME_PEND;
            }
            if (unicodeWordBoundary && (mask & (Tnfa.WORD_BOUNDARY | Tnfa.NO_WORD_BOUNDARY)) != 0 && pos == f - 1) {
                char c = m.charAt(pos);
                if (c >= Character.MIN_HIGH_SURROGATE && c <= Character.MAX_HIGH_SURROGATE) {
                    return SME_PEND;
                }
            }
        }
        return maskSatisfiedCS(m, pos, m.length(), mask) ? SME_YES : SME_NO;
    }

    /**
     * {@link #positionFlagsCS} for an open frontier: {@link #SPF_PEND}
     * when any axis the artifact's tables consult is undecidable — the
     * same three cases as {@link #streamMaskSatisfied}, restricted to
     * {@link #needsFinalEnd} / {@link #needsWordFlags}.
     */
    private int streamPositionFlags(StreamMatcher m, int pos) {
        if (!m.atEof()) {
            int f = m.length();
            if (pos >= f) {
                return SPF_PEND;
            }
            if (needsFinalEnd && finalEnd(m, pos, f)) {
                return SPF_PEND;
            }
            if (needsWordFlags && unicodeWordBoundary && pos == f - 1) {
                char c = m.charAt(pos);
                if (c >= Character.MIN_HIGH_SURROGATE && c <= Character.MAX_HIGH_SURROGATE) {
                    return SPF_PEND;
                }
            }
        }
        return positionFlagsCS(m, pos, m.length());
    }

    /**
     * A required literal chain's search needle (see {@link
     * RunnerTables#detectPrefixChain}): every match of the compiled DFA
     * contains {@link #needle} at exactly {@link #offset} UTF-16 units
     * from the match start, so {@code String.indexOf(needle)} enumerates
     * exactly the possible match starts — each hit walks at {@code hit -
     * offset} under the exact walk. {@code offset} is non-zero only on
     * fold-aware chains, where case-fold or class steps (one unit wide
     * each) precede the case-stable needle run.
     */
    public static final class PrefixChain {

        /** Needle text, exactly as it must occur in every match; never empty. */
        public final String needle;

        /** Units between the match start and the needle's first unit. */
        public final int offset;

        PrefixChain(String needle, int offset) {
            this.needle = needle;
            this.offset = offset;
        }
    }

    // ===== per-call scratch (hot-path allocation removal) =====
    //
    // Scratch buffers live on the caller's MatchScratch carrier — the re2j /
    // java.util.regex shape: the stateful Matcher owns its buffers for its
    // lifetime and passes them down the ladder, so a runner stays immutable
    // and shareable across threads (re2j semantics: Pattern thread-safe,
    // Matcher not) with no match-path ThreadLocal: nothing is retained past
    // the last live Matcher, and virtual threads don't pay a Scratch +
    // ThreadLocal entry each. Callers without a Matcher (direct engine use)
    // get a fresh carrier per top-level call — within-call pooling is
    // preserved either way. Sizes are re-validated on every take; one
    // carrier grows to the largest need seen and is collected with its
    // matcher. This eliminates the 4 sim allocations per find()/match()
    // (O(stateWords + stateCount) each — significant for dictionary-scale
    // DFAs) and the regs[] allocation on failed single-start walks;
    // successful walks still clone regs into the returned MatchHolder (it
    // escapes the runner). regs is also the pool behind the ASM tier's walk
    // leaves ({@link #takeRegs}) — removing the pool measured ~43 % slower
    // on the dense extract-restart scan (asmFindAllDense), so it stays.
    //
    // No nested aliasing: a holder of pooled regs never calls another taker
    // while reading its own [0, regSize) window — the one re-entry
    // (tryStartFast → extractFrom on a non-ASCII char) returns the callee's
    // result immediately, and every taker refills [0, n) before use.
    //
    // The one remaining static, TRACE_BUF (strategy tracing), is only
    // populated when -Dtdfa.trace.strategy is enabled (a test instrument):
    // its ThreadLocal entry allocates on the first traced call, never on
    // normal matching paths.
}
