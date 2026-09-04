package io.github.jemmix.tdfa.tdfa;

import io.github.jemmix.tdfa.ast.Alphabet;
import io.github.jemmix.tdfa.core.MatchResult;
import io.github.jemmix.tdfa.core.RegexEngine;
import io.github.jemmix.tdfa.tnfa.Tnfa;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Executes a compiled {@link Tdfa} against an input char sequence using the flat packed arrays.
 *
 * Zero-width assertions ( ^ $ \A \z \b \B ) are encoded per-state (entryMask, acceptMask) and
 * per-transition (requiredMask in {@link Tdfa#ranges}). At every position we compute the set of
 * flags that hold (BEGIN_TEXT, END_TEXT, WORD_BOUNDARY, NO_WORD_BOUNDARY) and consult these
 * masks:
 *   - on entering a state, {@code stateEntryMask[state]} must be a subset of positionFlags;
 *   - on taking a transition, the range's {@code requiredMask} must be a subset of positionFlags;
 *   - on declaring a match, {@code stateAcceptMask[state]} must be a subset of positionFlags.
 *
 * Tier A optimizations + JIT-friendly shape (post-disassembly analysis):
 *   - Single load per char for accept+dispatch (stateMeta packs all three)
 *   - Skip int[] regs alloc when registerCount == 0
 *   - Lazy accept snapshot
 *   - String specialization
 */
public final class TdfaRunner implements RegexEngine {
    private final Tdfa tdfa;
    private final int[] stateMeta;
    private final int[] stateBase;
    private final int[] stateFinalOpsOff;
    private final int[] stateEntryMask;
    private final int[] stateAcceptMask;
    /** Position-aware final-ops table (null = uniform; see Tdfa.stateFinalOpsByMask). */
    private final int[] finalOpsByMask;
    private final int[] ranges;
    private final int[] ops;
    private final int regSize;
    private final int startState;
    private final int startStateEntryMask;
    private final boolean longestMatch;
    private final boolean multiline;
    private final int[] stopOnAcceptMask;
    /** Uniform tier of the stop table (1 B/state) — see Tdfa.stopMaskUniform; exclusive with the above. */
    private final byte[] stopMaskUniform;
    private final boolean[] stateIsFallback;
    private final int[] stateFallbackOpsOff;
    private final boolean rangesDisjoint;
    private final int[] rhp;             // tdfa.entryHiPrefix — prefix-max-hi per entry
    /** Tight 128-entry table for the SIMULATIONS: constant stride keeps the
     *  hot loop's machine code identical to the pre-Latin-1 shape (a 256-stride
     *  table measurably slowed pure-ASCII scans ~15%); codepoints >= 128 take
     *  the binary-search branch. */
    private final int[] asciiTarget;
    /** Wide Latin-1 (256-entry) table for the WALK paths (extract/matches),
     *  where the doubled span buys direct dispatch on accented text. Same
     *  object as asciiTarget (128) when the DFA is too large for wide tables. */
    private final int[] latinTarget;
    private final int[] asciiRangeFlat; // flat: [state * latinLimit + c] → range index (-1 = dead)
    /** Table span in codepoints: 256 (Latin-1) normally, 128 when stateCount is
     *  large enough that the doubled tables cost real memory (2 tables x
     *  limit ints per state; 21K-state dictionary DFAs would pay ~42 MB at 256). */
    private final int latinLimit;
    private static final boolean WTRACE = Boolean.getBoolean("tdfa.trace");
    private final boolean fastPath;     // true = no masks + disjoint + not multiline
    private final int stateCount;
    private final int stateWords;       // # of 32-bit words in state bitsets
    private final int[] acceptBits;     // bitset of accepting states (over-approximate)
    private final boolean unicodeWordBoundary;
    private final int[] wordRanges;     // Unicode \w ranges for \b when unicodeWordBoundary is true
    /** Whether any mask / stop-table cell actually consults the word-boundary
     *  flags — when false, positionFlags skips both word-class checks. */
    private final boolean needsWordFlags;
    /** Word-class bitset over UTF-16 units (BMP): replaces the ASCII branch
     *  chain / Unicode range binary search with one array load. Supplementary
     *  codepoints still use the range search. */
    private final long[] wordBits;
    /** First-character candidate set over UTF-16 units: a consuming match can
     *  only start at p when input[p] is set (over-approximation when entries
     *  carry required masks — the exact walk confirms). Built only when the
     *  start state is NOT accepting (an accepting start admits zero-length
     *  matches anywhere, which the candidate scan cannot see). */
    private final long[] startBits;
    /** Lazy per-state 512-codepoint walk blocks for codepoints >= latinLimit:
     *  cell = the (unique, disjoint-only) containing range's index, or -1.
     *  turns wide-class walks (\p{L}{2,} on Cyrillic: ~600-range binary
     *  searches per char) into one array load. Published via the volatile
     *  {@link #walkBlocksArr} snapshot (copy-on-grow; build is synchronized
     *  and double-checked, so races only cost a redundant lock). */
    private volatile int[][] walkBlocksArr = EMPTY_BLOCKS;
    private int walkBlockCount;                       // guarded by this
    private final int[][] walkBlockIdx;               // [state] -> int[128] block ids (lazy)
    private static final int[][] EMPTY_BLOCKS = {};
    /** Cap on walk blocks (512 ints each): past it, dispatch falls back to
     *  binary search (dictionary-scale DFAs must not grow unbounded memos). */
    private static final int WALK_MAX_BLOCKS = 64;

    // ===== per-thread scratch (P2: hot-path allocation removal) =====
    //
    // A runner is shared when its Pattern is used from several threads (re2j
    // semantics: Pattern thread-safe, Matcher not), so scratch buffers are
    // ThreadLocal, not instance fields. Sizes are re-validated on every use —
    // a single Scratch object serves all runners on the thread, growing to the
    // largest DFA seen. Eliminates the 4 sim allocations per find()/match()
    // (O(stateWords + stateCount) each — significant for dictionary-scale DFAs)
    // and the regs[] allocation on failed single-start walks. Successful walks
    // still clone regs into the returned MatchHolder (it escapes the runner).
    private static final class Scratch {
        int[] regs;
        int[] live, next, origin, originNext;
    }
    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

    /** DFAs at or below this state count get 256-entry (Latin-1) lookup tables; larger stay 128. */
    private static final int LATIN1_MAX_STATES = 8192;

    /**
     * DFAs above this state count skip the EAGER per-state x latinLimit ASCII
     * dispatch tables (asciiTarget + asciiRangeFlat ≈ 1 KB/state at the 128-wide
     * tier) and use the lazy walk blocks / binary search instead. A 234 K-state
     * DFA would otherwise retain ~228 MB of dispatch tables — more than ALL of
     * its Tdfa tables combined (measured 2026-08-20, see TODO "tables" note);
     * every normal post-minimize DFA (e.g. dictionary: 6.8 K states) stays far
     * below the cap and keeps the direct-dispatch fast paths.
     */
    private static final int ASCII_TABLE_MAX_STATES = 16_384;

    /** Eager ASCII dispatch tables present (rangesDisjoint ∧ small enough). */
    private final boolean asciiTables;

    public TdfaRunner(Tnfa nfa) {
        this(Tdfa.compile(nfa));
    }

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
        this.regSize = tdfa.registerCount;
        this.startState = tdfa.startState;
        this.startStateEntryMask = tdfa.startStateEntryMask;
        this.rangesDisjoint = checkRangesDisjoint(tdfa);
        this.rhp = tdfa.entryHiPrefix;
        this.latinLimit = tdfa.stateCount <= LATIN1_MAX_STATES ? 256 : 128;
        this.asciiTables = rangesDisjoint && tdfa.stateCount <= ASCII_TABLE_MAX_STATES;
        if (asciiTables) {
            this.asciiRangeFlat = buildAsciiRangeFlat(tdfa, latinLimit);
            this.latinTarget = buildAsciiTarget(tdfa, latinLimit);
            this.asciiTarget = latinLimit == 128 ? latinTarget : buildAsciiTarget(tdfa, 128);
        } else {
            this.asciiRangeFlat = null;
            this.asciiTarget = null;
            this.latinTarget = null;
        }
        this.fastPath = computeFastPath(tdfa);
        this.longestMatch = tdfa.longestMatch;
        this.multiline = tdfa.multiline;
        this.stopOnAcceptMask = tdfa.stopOnAcceptMask;
        this.stopMaskUniform = tdfa.stopMaskUniform;
        this.stateIsFallback = tdfa.stateIsFallback;
        this.stateFallbackOpsOff = tdfa.stateFallbackOpsOff;
        this.stateCount = tdfa.stateCount;
        this.stateWords = (tdfa.stateCount + 31) >>> 5;
        this.acceptBits = buildAcceptBits(tdfa);
        this.searchDfa = new SearchDfa(this);   // after all table fields are assigned
        this.literalNeedle = detectLiteralNeedle(tdfa);
        this.unicodeWordBoundary = tdfa.unicodeWordBoundary;
        this.wordRanges = tdfa.wordRanges;
        // Derived, not inferred: the tables themselves declare which posFlag bits
        // they distinguish (see Tdfa.posFlagDeps) — no per-consumer model to keep in sync.
        this.needsWordFlags = (tdfa.posFlagDeps()
                & (Tnfa.WORD_BOUNDARY | Tnfa.NO_WORD_BOUNDARY)) != 0;
        this.wordBits = buildWordBits(tdfa.unicodeWordBoundary ? tdfa.wordRanges : null);
        this.startBits = (literalNeedle == null && (tdfa.stateMeta[tdfa.startState] & 1) == 0)
                ? buildStartBits() : null;
        this.walkBlockIdx = rangesDisjoint ? new int[tdfa.stateCount][] : null;
    }

    public Tdfa tdfa() { return tdfa; }

    @Override public int groupCount() { return tdfa.groupCount; }

    @Override public Map<String, Integer> namedGroups() { return tdfa.namedGroups; }

    @Override public int programSize() { return tdfa.stateCount; }

    // ===== strategy trace (conformance instrument) =====
    //
    // Records WHICH branch of the search ladder served each public entry
    // call. The interpreter records at its decision points; the ASM backend's
    // generator emits recordStrategy calls at the same points of its emitted
    // ladder (single template). The strategy-conformance test asserts both
    // backends produce identical sequences over a shape x length sweep — the
    // structural guard against the two ladders drifting (the litFind bug:
    // identical results, different algorithm).
    // Zero cost when disabled: TRACE is static final, branches prune.
    // TODO: revisit as first-class internals access (observer/event API) —
    // see TODO.md "internals access".

    /** Which branch of the search ladder served one public entry call. */
    public enum Strategy {
        LITERAL,        // literalNeedle -> String.indexOf
        CAND_SCAN,      // first-char-set bit scan + exact walks (short input)
        EXACT_FROM,     // one exact walk from the requested start
        ORIGIN_SIM,     // budgeted origin-tracking multi-state simulation
        TRIGGER,        // memoized search-DFA trigger scan
        RAW_SCAN,       // unmemoized live-set simulation (short window / cap)
        WALK_RESTART,   // defensive per-start restart loop
        ANCHORED_FAST,  // flat-table anchored loop (fastPath)
        ANCHORED,       // generic anchored walk
        GENERIC         // CharSequence (non-String) fallback
    }

    /** Enabled by -Dtdfa.trace.strategy=true at startup, or at runtime via
     *  {@link #setTracing(boolean)} (the strategy-conformance test). Volatile
     *  load + never-taken branch is ~free on the hot paths. */
    private static volatile boolean TRACE = Boolean.getBoolean("tdfa.trace.strategy");
    private static final ThreadLocal<ArrayList<Strategy>> TRACE_BUF =
            ThreadLocal.withInitial(ArrayList::new);

    /** Record a strategy decision point (no-op unless tracing). Public: the
     *  ASM backend's emitted ladder calls it from generated classes. */
    public static void trace(Strategy s) {
        if (TRACE) TRACE_BUF.get().add(s);
    }

    /** Enable/disable strategy tracing at runtime (conformance-test hook). */
    public static void setTracing(boolean on) { TRACE = on; }

    /** Snapshot and clear this thread's recorded strategy sequence. */
    public static List<Strategy> traceSnapshot() {
        ArrayList<Strategy> buf = TRACE_BUF.get();
        List<Strategy> out = List.copyOf(buf);
        buf.clear();
        return out;
    }

    /**
     * Fast no-match pre-check: returns {@code true} if the DFA could match
     * starting at any position in {@code [from, input.length())}. Sound
     * over-approximation (ignores masks). Used by the ASM backend to avoid its
     * O(n²) outer-loop scan on non-matching haystacks — see {@link #multiStateAnyMatch}.
     */
    public final boolean anyMatch(CharSequence input, int from) {
        return multiStateAnyMatch(input, from, input.length());
    }

    /**
     * Leftmost position in {@code [from, input.length())} at which a match
     * starts, or {@code -1} if there is no match anywhere.
     *
     * <p>For fast-path DFAs (disjoint ranges, no assertion masks — see
     * {@link #computeFastPath}) this runs the multi-state simulation with
     * per-state origin tracking: {@code origin[s]} is the smallest start
     * position from which {@code s} is currently reachable. The first
     * accept-live position yields the answer. Early-stops once the best
     * origin can no longer be beaten ({@code best <= pos} and
     * {@code best <= min live origin}): future seeds have origin {@code > pos}.
     *
     * <p>Otherwise (mask-bearing DFAs, where the sim over-approximates) it
     * degrades to a boolean answer — {@code from} if any match might exist,
     * {@code -1} if definitely none — so the caller's exact extract loop
     * keeps its existing restart behavior.
     */
    public final int leftmostStart(CharSequence input, int from) {
        int to = input.length();
        if (literalNeedle != null && input instanceof String) {
            trace(Strategy.LITERAL);
            return literalIndexOf((String) input, literalNeedle, from);
        }
        // Short-input candidate scan: bit-test per char, exact walk per
        // candidate. Cheaper than the origin sim's per-live-state dispatch
        // when the input is tiny; the walk verifies exactly, so the result
        // is the true leftmost start (no sim/walk agreement caveat).
        // Non-fastPath DFAs walk via runStringMatchFrom (mask-exact); the
        // sim's mask-ignoring over-approximation is not involved at all.
        if (input instanceof String && startBits != null && to - from <= CAND_SCAN_MAX) {
            trace(Strategy.CAND_SCAN);
            String s = (String) input;
            final long[] sb = this.startBits;
            // One loop, two walkers: fastPath DFAs take the no-regs/no-masks
            // boolean walk, everything else the mask-exact one. The choice is
            // invariant for a compiled pattern, so the JIT folds it — the
            // former two hand-copies of this loop are the drift we merged.
            final boolean fast = fastPath;
            for (int p = from; p < to; p++) {
                char c = s.charAt(p);
                if ((sb[c >>> 6] >>> (c & 63) & 1L) == 0L) continue;
                if (c >= 0xDC00 && Alphabet.pairInterior(s, p)) continue;
                if (fast ? matchFromFast(s, p, to) : runStringMatchFrom(s, p, to) >= 0) return p;
            }
            return -1;
        }
        if (fastPath) {
            trace(Strategy.ORIGIN_SIM);
            int l = multiStateLeftmostStart(input, from, to, LSS_BUDGET_CHARS);
            if (l == LSS_BUDGET) {
                int w = triggerScan(input.toString(), from, to);
                if (w < 0) return -1;
                l = multiStateLeftmostStart(input, w, to);
            }
            return l;
        }
        return triggerScan(input.toString(), from, to) >= 0 ? from : -1;
    }

    @Override public boolean matches(CharSequence input) {
        if (input instanceof String) {
            String s = (String) input;
            if (fastPath) { trace(Strategy.ANCHORED_FAST); return runStringAnchoredFast(s); }
            trace(Strategy.ANCHORED);
            return runStringAnchored(s) >= 0;
        }
        trace(Strategy.GENERIC);
        return runGeneric(input, 0, input.length(), true) != null;
    }

    @Override public boolean find(CharSequence input) {
        if (input instanceof String) {
            String s = (String) input;
            int len = s.length();
            if (literalNeedle != null) { trace(Strategy.LITERAL); return literalIndexOf(s, literalNeedle, 0) >= 0; }
            if (fastPath) return runStringFindFast(s, len);
            int maxStart = (startStateEntryMask & Tnfa.ABS_BEGIN) != 0 ? 0 : len;
            if (maxStart > 0) {
                // One exact walk from 0 first: for prefix-chain DFAs (e.g.
                // \p{L}{256}) a match at/near 0 answers in O(len) while the
                // trigger's raw-scan pre-check is O(len^2) in live-set size.
                trace(Strategy.EXACT_FROM);
                if (runStringMatchFrom(s, 0, len) >= 0) return true;
                // Short inputs: first-char-set candidate scan with exact
                // (mask-aware) walks instead of the raw-scan simulation.
                if (startBits != null && len <= CAND_SCAN_MAX) {
                    trace(Strategy.CAND_SCAN);
                    final long[] sb = this.startBits;
                    for (int p = 1; p < len; p++) {
                        char c = s.charAt(p);
                        if ((sb[c >>> 6] >>> (c & 63) & 1L) != 0L && (c < 0xDC00 || !Alphabet.pairInterior(s, p))
                                && runStringMatchFrom(s, p, len) >= 0) return true;
                    }
                    return false;
                }
                int w = triggerScan(s, 0, len);
                if (w < 0) return false;
                trace(Strategy.WALK_RESTART);
                for (int from = Math.max(w, 1); from <= maxStart; from++) {
                    if (Alphabet.pairInterior(s, from)) continue;
                    int res = runStringMatchFrom(s, from, len);
                    if (res >= 0) return true;
                }
                return false;
            }
            trace(Strategy.EXACT_FROM);
            return runStringMatchFrom(s, 0, len) >= 0;
        }
        trace(Strategy.GENERIC);
        return runGeneric(input, 0, input.length(), false) != null;
    }

    @Override public MatchResult match(CharSequence input, int from) {
        MatchHolder h;
        if (input instanceof String) {
            String s = (String) input;
            h = fastPath ? runStringExtractFast(s, from, s.length()) : runStringExtract(s, from, s.length());
        } else {
            trace(Strategy.GENERIC);
            h = runGeneric(input, from, input.length(), false);
        }
        if (h == null) return null;
        if (tdfa.fixedBase != null) {
            MatchResult.reconstructFixed(h.regs, tdfa.finalRegBase, tdfa.fixedBase, tdfa.fixedOffset);
        }
        return new MatchResult(h.regs, tdfa.finalRegBase, tdfa.groupCount, h.matchStart, h.matchEnd);
    }

    /** String find with anchor enforcement and register extraction. */
    private MatchHolder runStringExtract(String input, int from, int to) {
        final int[] sm = this.stateMeta;
        final int[] rg = this.ranges;
        final int[] op = this.ops;
        final int[] sfo = this.stateFinalOpsOff;
        final int[] sem = this.stateEntryMask;
        final int[] sam = this.stateAcceptMask;
        int maxStart = (startStateEntryMask & Tnfa.ABS_BEGIN) != 0 ? 0 : to;
        // One exact walk from `from` first (match at/near from is the common
        // case and answers in O(len) — cheaper than any pre-check; see find()).
        {
            trace(Strategy.EXACT_FROM);
            MatchHolder direct = extractFrom(input, from, to);
            if (direct != null) return direct;
        }
        // Short inputs: first-char-set candidate scan with exact (mask-aware)
        // walks instead of the trigger scan + restart loop. Zero-length
        // matches are impossible here (startBits is only built when the start
        // state cannot accept), so bit coverage is complete. After a few
        // failed register walks the no-allocation boolean walk filters the
        // remaining candidates (dense-candidate no-match shapes).
        if (maxStart > 0 && startBits != null && to - from <= CAND_SCAN_MAX) {
            trace(Strategy.CAND_SCAN);
            final long[] sb = this.startBits;
            int fails = 0;
            for (int p = from + 1; p < to; p++) {
                char c = input.charAt(p);
                if ((sb[c >>> 6] >>> (c & 63) & 1L) == 0L) continue;
                if (c >= 0xDC00 && Alphabet.pairInterior(input, p)) continue;
                if (fails >= 3 && runStringMatchFrom(input, p, to) < 0) continue;
                MatchHolder h = extractFrom(input, p, to);
                if (h != null) return h;
                fails++;
            }
            return null;
        }
        // Trigger scan: memoized search-DFA pass that both proves no-match and
        // bounds the restart loop to the kill-point window (no configuration
        // alive before W can produce a match — see SearchDfa).
        if (maxStart > 0) {
            int w = triggerScan(input, from, to);
            if (w < 0) return null;
            if (w > from + 1) from = w - 1;
        }
        trace(Strategy.WALK_RESTART);
        for (int startSearch = from + 1; startSearch <= maxStart; startSearch++) {
            if (Alphabet.pairInterior(input, startSearch)) continue;
            if (Boolean.getBoolean("tdfa.trace")) System.err.println("[walk] === start " + startSearch);
            MatchHolder h = extractFrom(input, startSearch, to);
            if (h != null) return h;
        }
        return null;
    }

    /** One exact single-start walk; null = no match starting at startSearch. */
    private MatchHolder extractFrom(String input, int startSearch, int to) {
        final int[] sm = this.stateMeta;
        final int[] rg = this.ranges;
        final int[] op = this.ops;
        final int[] sem = this.stateEntryMask;
        final int[] sam = this.stateAcceptMask;
        final int[] arf = this.asciiRangeFlat;   // non-null iff rangesDisjoint
        final int limit = this.latinLimit;
        // Pooled regs (per-thread scratch): the candidate-scan loops call this
        // per candidate and most walks fail — no allocation on that path. The
        // success path clones (line below) before returning, so the pool is
        // never handed out.
        Scratch sc = SCRATCH.get();
        final int[] regs;
        if (regSize == 0) {
            regs = null;
        } else if (sc.regs != null && sc.regs.length >= regSize) {
            regs = sc.regs;
            Arrays.fill(regs, 0, regSize, -1);
        } else {
            regs = new int[regSize];
            java.util.Arrays.fill(regs, -1);
            sc.regs = regs;
        }
        int state = startState;
        int lastAcceptPos = -1, lastAcceptState = -1;
        boolean haveAccept = false;
        int pos = startSearch;

        // Entry check for start state — inline
        {
            int entryReq = sem[state];
            if (entryReq != 0 && (positionFlags(input, pos, to) & entryReq) != entryReq) return null;
        }

        int posFlags = -1;
        loop:
        for (; ; pos++) {
            int meta = sm[state];
            if (WTRACE) System.err.println("[walk] pos=" + pos + " state=" + state + " accept=" + ((meta & 1) != 0));
            if ((meta & 1) != 0) {
                final int[] fm = this.finalOpsByMask;
                if (fm != null) {
                    // Position-aware table is authoritative: cell >= 0 = an
                    // accept config is alive under these posFlags (the gate
                    // the sam-intersection only approximated), cell = its φ.
                    if (posFlags < 0) posFlags = positionFlags(input, pos, to);
                    int cell = fm[state * 64 + posFlags];
                    if (WTRACE) System.err.println("[walk]   fmCell=" + cell + " M=" + Integer.toBinaryString(posFlags));
                    if (cell >= 0) {
                        lastAcceptPos = pos; lastAcceptState = state; haveAccept = true;
                        if (regs != null && cell != 0) applyOps(op, cell, regs, pos);
                        if (!longestMatch && stopNow(state, posFlags)) break loop;
                    }
                } else {
                    int acceptMask = sam[state];
                    if (acceptMask == 0) {
                        lastAcceptPos = pos; lastAcceptState = state; haveAccept = true;
                        if (regs != null) applyFinalOps(state, regs, pos);
                        if (!longestMatch) {
                            // stopNow ignores posFlags when the stop table is
                            // uniform (assertion-free) — skip the 2×charAt +
                            // word lookups; other readers recompute lazily.
                            if (posFlags < 0 && stopMaskUniform == null) posFlags = positionFlags(input, pos, to);
                            if (stopNow(state, posFlags)) break loop;
                        }
                    } else {
                        if (posFlags < 0) posFlags = positionFlags(input, pos, to);
                        if ((posFlags & acceptMask) == acceptMask) {
                            if (WTRACE) System.err.println("[walk]   sam accept M=" + Integer.toBinaryString(posFlags) + " stop=" + stopNow(state, posFlags));
                            lastAcceptPos = pos; lastAcceptState = state; haveAccept = true;
                            if (regs != null) applyFinalOps(state, regs, pos);
                            if (!longestMatch && stopNow(state, posFlags)) break loop;
                        }
                    }
                }
            }
            if (pos >= to) break;
            int c = Alphabet.decode(input, pos, to);
            int base = stateBase[state];
            int count = (meta >>> 1) & 0xFFFF;
            int chosen = -1, chosenTarget = 0;
            int ri;
            if (arf != null && c < limit) {
                // Disjoint ranges: at most one entry contains c, so entry
                // priority is moot and the flat table is exact.
                ri = arf[state * limit + c];
            } else if (rangesDisjoint && c < 0x10000) {
                // Beyond the flat table (or tableless giant DFA — dispatch
                // tables skipped above ASCII_TABLE_MAX_STATES): lazy walk
                // block (one array load) or, past the block cap, the binary
                // search below.
                ri = walkRangeIndex(state, c);
                if (ri == -2) ri = Integer.MIN_VALUE;
            } else {
                ri = Integer.MIN_VALUE;
            }
            if (ri == Integer.MIN_VALUE) {
                // Binary search rightmost entry with lo <= c, then walk back
                // while the per-state prefix-max-hi still reaches c: visits
                // exactly the entries that can contain c, in the same
                // lowest-index-first priority the linear scan used.
                int rlo = 0, rhi = count - 1, anchor = -1;
                while (rlo <= rhi) {
                    int mid = (rlo + rhi) >>> 1;
                    if (rg[(base + mid) * 5] <= c) { anchor = mid; rlo = mid + 1; }
                    else rhi = mid - 1;
                }
                // Walk back over containing entries. Ownership: the MOST
                // SPECIFIC satisfied mask wins (popcount of requiredMask);
                // ties fall to the lowest index (determinizer order). Pure
                // "lowest index" was wrong once overlapping contexts' ranges
                // differ in lo — a more-specific (e.g. dead-marker) entry at
                // a HIGHER lo/index was shadowed by a broad mask-0 range
                // (fuzz round 11: .+?\b[^\d]* extended past its \b-gated
                // accept through the lazy body's '.' entry).
                int best = -1, bestSpec = -1;
                for (int i = anchor; i >= 0 && rhp[base + i] >= c; i--) {
                    int o = (base + i) * 5;
                    if (c <= rg[o + 1]) {
                        int requiredMask = rg[o + 4];
                        if (requiredMask != 0) {
                            if (posFlags < 0) posFlags = positionFlags(input, pos, to);
                            if ((posFlags & requiredMask) != requiredMask) continue;
                        }
                        int spec = Integer.bitCount(requiredMask);
                        if (spec >= bestSpec) { best = i; bestSpec = spec; }   // >= : lower index wins ties
                    }
                }
                if (best >= 0) {
                    int o = (base + best) * 5;
                    int target = rg[o + 2];
                    if (target < 0) {
                        // Dead marker of the OWNING context (lowest satisfied):
                        // no continuation exists under this posFlags — lower-
                        // specificity ranges belong to contexts not alive here.
                        if (WTRACE) System.err.println("[walk]   c=" + Integer.toHexString(c) + " DEAD idx " + best + " mask=" + Integer.toBinaryString(rg[o + 4]) + " (M=" + Integer.toBinaryString(posFlags) + ")");
                        break loop;
                    }
                    if (WTRACE) System.err.println("[walk]   c=" + Integer.toHexString(c) + " pick idx " + best + " lo=" + Integer.toHexString(rg[o]) + " mask=" + Integer.toBinaryString(rg[o + 4]) + " -> " + target + " (M=" + Integer.toBinaryString(posFlags) + ")");
                    chosen = o; chosenTarget = target;
                }
            } else if (ri >= 0) {
                int o = (base + ri) * 5;
                int target = rg[o + 2];
                if (target >= 0) {
                    int requiredMask = rg[o + 4];
                    boolean ok = requiredMask == 0;
                    if (!ok) {
                        if (posFlags < 0) posFlags = positionFlags(input, pos, to);
                        ok = (posFlags & requiredMask) == requiredMask;
                    }
                    if (ok) { chosen = o; chosenTarget = target; }
                }
            }
            if (chosen < 0) break;
            // Target entry mask is a position predicate, evaluated BEFORE the
            // transition's ops run: a mask-failing transition is never taken,
            // so its tag writes must not contaminate the register file (a
            // later-recorded accept would read them — the fuzz-found "skipped
            // group reports empty instead of null" family).
            int width = c > 0xFFFF ? 2 : 1;
            int entryReqNext = sem[chosenTarget];
            if (entryReqNext != 0
                    && (positionFlags(input, pos + width, to) & entryReqNext) != entryReqNext) break;
            if (regs != null) {
                int opsOff = rg[chosen + 3];
                if (opsOff != 0) applyOps(op, opsOff, regs, pos);
            }
            state = chosenTarget;
            if (width == 2) pos++;
            posFlags = -1;
        }
        if (!haveAccept) return null;
        int[] r = regs == null ? new int[0] : regs.clone();
        // Final ops already applied eagerly at accept-record time (BT22's
        // declaration semantics): they read the accept-time register values.
        // A lazy replay here would read end-of-walk values — any transition
        // taken between the accept and the break clobbers working registers
        // and inverts group spans (the fuzz-found start>end crashes).
        return new MatchHolder(startSearch, lastAcceptPos, r);
    }

    public static final class MatchHolder {
        public final int matchStart, matchEnd;
        public final int[] regs;
        public MatchHolder(int s, int e, int[] r) { matchStart = s; matchEnd = e; regs = r; }
    }

    // ===== Lazy search-DFA (trigger scan with kill-point windows) =====

    /**
     * Memoized search DFA for unanchored find(): the states of the multi-state
     * simulation (live-set bitsets, start state re-seeded every position — the
     * implicit {@code .*?} of unanchored search) interned into flat rows so the
     * scan loop is ~3 array loads per char instead of a bitset simulation step.
     *
     * <p>Row transitions are materialized lazily as deduplicated 512-codepoint
     * blocks covering the whole BMP ({@code blocks[bits.blockIds[c >>> 9]][c & 511]});
     * supplementary codepoints are computed per-step without caching (rare).
     * A block cell is either the next row id, or the kill sentinel {@code KILL}
     * meaning "every configuration just died; the next row is the pure-seed row
     * and the caller may advance its match-window bound past this position"
     * (sound: nothing alive from an earlier start survives a kill).
     *
     * <p>Caps ({@link #MAX_ROWS}/{@link #MAX_BLOCKS}) bound memory; past the
     * caps the scan falls back to the unmemoized simulation (still tracking
     * kill points, so the extract window stays bounded either way).
     *
     * <p><b>Soundness</b> — identical over-approximation to
     * {@link #multiStateAnyMatch}: transition/entry masks are ignored (every
     * matching target followed), and {@code accept} is any live state with the
     * accept bit — so a trigger can fire without a real match (the exact
     * extract confirms or continues), but it can never miss one.
     */
    private static final int SDFA_KILL = -2;
    // Small re2-style lazy-DFA budgets: past the caps the scan degrades to the
    // unmemoized simulation (still kill-point aware). The bomb shape if these
    // are too high: live-set rows proliferate on .*-heavy patterns and each
    // runner (one per compiled Regex) keeps its own ThreadLocal memo — dozens
    // of live runners × MBs each OOMs the parity suites (seen: 27 live Tdfas).
    private static final int SDFA_MAX_ROWS = 512;      // rows: ~512B each + blockIds
    private static final int SDFA_MAX_BLOCKS = 1024;   // 1024 * 512 * 4B = 2 MB cap
    private static final int SDFA_MIN_WINDOW = 2048;   // below: unmemoized raw scan
    /** Origin-sim budget before falling back to the memoized trigger scan. */
    private static final int LSS_BUDGET_CHARS = 4096;
    /** Inputs at or below this length use the first-char-set candidate scan
     *  (bit test per char + exact walk per candidate) instead of the
     *  multi-state simulation. Worst case is O(len²) walk steps (dense
     *  candidates, long failing walks) — 64² = 4K steps bounds it while the
     *  sim stays the better shape for haystack-scale inputs. */
    private static final int CAND_SCAN_MAX = 64;

    private final SearchDfa searchDfa;
    /**
     * Exact-literal needle when the whole regex is a plain literal string
     * (single char-chain DFA, no captures/ops/masks): find()/leftmost-start
     * then use String.indexOf — the JIT's intrinsified, vectorized scan —
     * instead of DFA stepping. ~0.2 vs ~6.5 ns/char on ASCII haystacks.
     */
    private final String literalNeedle;

    /** Detect the literal-chain shape; null otherwise. Public static: the
     *  ASM backend asks at emit time so literal DFAs get the fully-delegated
     *  generated class (its indexOf short-circuit beats the generated walk
     *  at every input length). */
    public static String detectLiteralNeedle(Tdfa tdfa) {
        try {
            if (tdfa.groupCount != 0 || tdfa.tagCount != 0) return null;
            int n = tdfa.stateCount;
            if (n < 2) return null;   // single-state: empty/anchor-only regex
            StringBuilder sb = new StringBuilder(n - 1);
            int s = tdfa.startState;
            for (int step = 0; step < n - 1; step++) {
                int meta = tdfa.stateMeta[s];
                if ((meta & 1) != 0) return null;            // accepting mid-chain
                int cnt = (meta >>> 1) & 0xFFFF;
                if (cnt != 1) return null;                   // must be exactly one char
                int o = tdfa.stateBase[s] * 5;
                int lo = tdfa.ranges[o], hi = tdfa.ranges[o + 1];
                if (lo != hi || lo > 0xFFFF) return null;    // single BMP codepoint
                if (tdfa.ranges[o + 2] < 0) return null;     // dead
                if (tdfa.ranges[o + 3] != 0) return null;    // transition ops
                if (tdfa.ranges[o + 4] != 0) return null;    // required mask
                if (tdfa.stateEntryMask[tdfa.ranges[o + 2]] != 0) return null;
                sb.append((char) lo);
                s = tdfa.ranges[o + 2];
            }
            // final state: accepting, no mask, no fallback, no final ops, and
            // NO live outgoing transition (a live self-loop means the regex is
            // unbounded — a+ misdetected as literal "a" returned [0,1) for
            // find("a+","aaa") instead of [0,3)).
            if ((tdfa.stateMeta[s] & 1) == 0) return null;
            if (tdfa.stateAcceptMask[s] != 0) return null;
            // Position-dependent accept (byMask variants): the accept fires
            // only under some posFlags — the indexOf shortcut can't evaluate
            // that (fuzz round 10: Z(?:\A|\B) matched "Z" via the needle,
            // though \A and \B both fail at pos 1). Not a literal.
            {
                int[] fm = tdfa.stateFinalOpsByMask();
                if (fm != null) {
                    for (int M = 0; M < 64; M++) {
                        if (fm[s * 64 + M] < 0) return null;
                    }
                }
            }
            if (tdfa.stateFinalOpsOff[s] != 0) return null;
            if (tdfa.stateEntryMask[s] != 0) return null;
            {
                int meta = tdfa.stateMeta[s];
                int base = tdfa.stateBase[s];
                for (int i = 0; i < ((meta >>> 1) & 0xFFFF); i++) {
                    if (tdfa.ranges[(base + i) * 5 + 2] >= 0) return null;
                }
            }
            // Lone-surrogate adjacency: the needle is built from single BMP
            // symbols, each appended as its raw unit. Two adjacent LONE
            // symbols (high then low) re-encode as a well-formed surrogate
            // PAIR — the same unit text as the pair codepoint they are not.
            // Unit-wise indexOf then matches input pairs against what the
            // alphabet defines as two lone codepoints (fuzz repro:
            // (?i:\uD800)\uDFFF matched 𐏿 = \uD800\uDFFF whole). Rejected
            // here, the DFA walk handles the shape correctly (it decodes).
            for (int i = 0; i < sb.length() - 1; i++) {
                char c0 = sb.charAt(i), c1 = sb.charAt(i + 1);
                if (c0 >= 0xD800 && c0 <= 0xDBFF && c1 >= 0xDC00 && c1 <= 0xDFFF)
                    return null;
            }
            return sb.length() > 0 ? sb.toString() : null;
        } catch (RuntimeException e) {
            return null;   // any surprise shape: not a literal
        }
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
            if (rg[o + 2] < 0) continue;               // dead: never a first char
            int lo = Math.max(rg[o], 0), hi = Math.min(rg[o + 1], 0xFFFF);
            for (int c = lo; c <= hi; c++) bits[c >>> 6] |= 1L << (c & 63);
            if (rg[o + 1] > 0xFFFF)
                for (int c = 0xD800; c <= 0xDBFF; c++) bits[c >>> 6] |= 1L << (c & 63);
            any = true;
        }
        return any ? bits : null;
    }

    /** Word-class bitset over BMP UTF-16 units. ASCII mode (null ranges):
     *  the 63-char [_0-9A-Za-z] set; unicode mode: wordRanges clipped to the BMP. */
    private static long[] buildWordBits(int[] ranges) {
        long[] bits = new long[1024];
        if (ranges == null) {
            setBit(bits, '_');
            for (int c = '0'; c <= '9'; c++) setBit(bits, c);
            for (int c = 'a'; c <= 'z'; c++) setBit(bits, c);
            for (int c = 'A'; c <= 'Z'; c++) setBit(bits, c);
            return bits;
        }
        for (int i = 0; i + 1 < ranges.length; i += 2) {
            int lo = Math.max(ranges[i], 0), hi = Math.min(ranges[i + 1], 0xFFFF);
            for (int c = lo; c <= hi; c++) bits[c >>> 6] |= 1L << (c & 63);
        }
        return bits;
    }

    private static void setBit(long[] bits, int c) { bits[c >>> 6] |= 1L << (c & 63); }

    /**
     * Range index for codepoint {@code c} (BMP, disjoint DFA) via lazy walk
     * blocks: -1 = dead entry, -2 = block cap exceeded (caller falls back to
     * binary search). See {@link #walkBlocksArr} for the publication scheme.
     */
    private int walkRangeIndex(int state, int c) {
        int[] idx = walkBlockIdx[state];
        int b = c >>> 9;
        int id;
        if (idx == null) {
            idx = new int[128];
            java.util.Arrays.fill(idx, -1);
            walkBlockIdx[state] = idx;
            id = -1;
        } else {
            id = idx[b];
        }
        if (id == -1) id = buildWalkBlock(state, b, idx);
        if (id < 0) return id;
        int[][] arr = walkBlocksArr;
        if (id < arr.length) {
            int ri = arr[id][c & 511];
            return ri;   // -1 cell = dead entry
        }
        return -2;       // stale id vs a fresh snapshot: treat as capped (rare, safe)
    }

    /** Build one 512-cp block for `state` (lowest entry index per cell — for
     *  disjoint DFAs the containing entry is unique). Synchronized + double-checked. */
    private synchronized int buildWalkBlock(int state, int b, int[] idx) {
        int id = idx[b];
        if (id != -1) return id;
        if (walkBlockCount >= WALK_MAX_BLOCKS) { idx[b] = -2; return -2; }
        int[] cells = new int[512];
        java.util.Arrays.fill(cells, -1);
        int lo = b << 9, hi = lo + 511;
        int base = stateBase[state], cnt = (stateMeta[state] >>> 1) & 0xFFFF;
        final int[] rg = this.ranges;
        for (int i = 0; i < cnt; i++) {
            int o = (base + i) * 5;
            int eLo = Math.max(rg[o], lo), eHi = Math.min(rg[o + 1], hi);
            for (int cp = eLo; cp <= eHi; cp++) cells[cp - lo] = i;
        }
        int n = walkBlockCount++;
        int[][] next = java.util.Arrays.copyOf(walkBlocksArr, n + 1);
        next[n] = cells;
        walkBlocksArr = next;    // volatile publish: cells contents visible to readers
        idx[b] = n;
        return n;
    }

    /**
     * Whether the word-boundary flags are observable anywhere: an entry /
     * accept / required mask with a word bit, or a stop-table row whose cells
     * differ between the WB / NWB / no-word variants of the same base flags
     * (positionFlags only ever produces those three variants — both-set never
     * occurs — so cell equality across them makes the word bits irrelevant).
     */

    /** Static nested: shared per-Tdfa lifetime; references the runner's tables. */
    static final class SearchDfa {
        final TdfaRunner r;
        final int nw;
        SearchDfa(TdfaRunner r) { this.r = r; this.nw = r.stateWords; }
        final HashMap<Wrapper, Integer> rowById = new HashMap<>();    // bitset -> row id
        final ArrayList<int[]> rowWords = new ArrayList<>();          // row id -> bitset
        final ArrayList<int[]> rowBlockIds = new ArrayList<>();       // row id -> int[128] (lazy)
        final ArrayList<boolean[]> rowHasBlock = new ArrayList<>();   // row id -> which blocks materialized
        final HashMap<Wrapper, Integer> blockById = new HashMap<>();  // content -> block id
        final ArrayList<int[]> blocks = new ArrayList<>();            // block id -> int[512]
        boolean capped;

        /** Immutable-ish int[] key wrapper with cached hash. */
        private static final class Wrapper {
            final int[] a; final int hash;
            Wrapper(int[] a) { this.a = a; hash = java.util.Arrays.hashCode(a); }
            @Override public int hashCode() { return hash; }
            @Override public boolean equals(Object o) {
                return o instanceof Wrapper w && java.util.Arrays.equals(a, w.a);
            }
        }

        int internRow(int[] words) {
            Wrapper probe = new Wrapper(words);
            Integer id = rowById.get(probe);
            if (id != null) return id;
            if (rowWords.size() >= SDFA_MAX_ROWS || capped) { capped = true; return -1; }
            int[] key = words.clone();
            int nid = rowWords.size();
            rowById.put(new Wrapper(key), nid);
            rowWords.add(key);
            rowBlockIds.add(new int[128]);
            java.util.Arrays.fill(rowBlockIds.get(nid), -1);
            rowHasBlock.add(new boolean[128]);
            return nid;
        }

        boolean accept(int rowId) {
            int[] w = rowWords.get(rowId);
            for (int i = 0; i < nw; i++) if ((w[i] & r.acceptBits[i]) != 0) return true;
            return false;
        }

        /** Pure step (no re-seed): all targets of live states on c, masks ignored. */
        private int[] delta(int[] words, int c) {
            int[] next = new int[nw];
            for (int w = 0; w < nw; w++) {
                int bits = words[w];
                while (bits != 0) {
                    int bit = Integer.numberOfTrailingZeros(bits);
                    bits &= bits - 1;
                    int s = (w << 5) + bit;
                    int meta = r.stateMeta[s];
                    int base = r.stateBase[s];
                    int count = (meta >>> 1) & 0xFFFF;
                    int rlo = 0, rhi = count - 1, anchor = -1;
                    while (rlo <= rhi) {
                        int mid = (rlo + rhi) >>> 1;
                        if (r.ranges[(base + mid) * 5] <= c) { anchor = mid; rlo = mid + 1; }
                        else rhi = mid - 1;
                    }
                    for (int i = anchor; i >= 0 && r.rhp[base + i] >= c; i--) {
                        int mo = (base + i) * 5;
                        if (c <= r.ranges[mo + 1]) {
                            int t = r.ranges[mo + 2];
                            if (t >= 0) next[t >>> 5] |= 1 << (t & 31);
                        }
                    }
                }
            }
            return next;
        }

        /** Step with re-seed; returns the next bitset. */
        private int[] step(int[] words, int c) {
            int[] d = delta(words, c);
            boolean empty = true;
            for (int i = 0; i < nw; i++) if (d[i] != 0) { empty = false; break; }
            if (empty) {
                int[] seed = new int[nw];
                seed[r.startState >>> 5] |= 1 << (r.startState & 31);
                return seed;   // kill
            }
            d[r.startState >>> 5] |= 1 << (r.startState & 31);
            return d;
        }

        /** Encoded transition for row on c: row id, SDFA_KILL, or -1 (uncached-cap). */
        int transition(int rowId, int c) {
            int[] words = rowWords.get(rowId);
            int[] d = delta(words, c);
            boolean empty = true;
            for (int i = 0; i < nw; i++) if (d[i] != 0) { empty = false; break; }
            if (empty) return SDFA_KILL;   // next = pure row 0 + kill
            d[r.startState >>> 5] |= 1 << (r.startState & 31);
            return internRow(d);
        }

        /** Materialize block {@code b} of {@code rowId}: 512 encoded transitions. */
        private void buildBlock(int rowId, int b) {
            int[] cells = new int[512];
            int lo = b << 9;
            boolean allKill = true;
            for (int k = 0; k < 512; k++) {
                int t = transition(rowId, lo + k);
                if (t == -1) {
                    // capped mid-block: mark whole block unusable (-1 cells handled by caller)
                    rowBlockIds.get(rowId)[b] = -2;
                    rowHasBlock.get(rowId)[b] = true;
                    return;
                }
                if (t != SDFA_KILL) allKill = false;
                cells[k] = t;
            }
            int blockId;
            if (allKill) {
                blockId = -3;   // shared all-kill block
            } else {
                Wrapper key = new Wrapper(cells);
                Integer cached = blockById.get(key);
                if (cached != null) blockId = cached;
                else {
                    if (blocks.size() >= SDFA_MAX_BLOCKS) {
                        rowBlockIds.get(rowId)[b] = -2;
                        rowHasBlock.get(rowId)[b] = true;
                        return;
                    }
                    blocks.add(cells);
                    blockId = blocks.size() - 1;
                    blockById.put(key, blockId);
                }
            }
            rowBlockIds.get(rowId)[b] = blockId;
            rowHasBlock.get(rowId)[b] = true;
        }

        /** Encoded transition via blocks; builds lazily. c must be < 0x10000. */
        int bmpTransition(int rowId, int c) {
            int b = c >>> 9;
            if (!rowHasBlock.get(rowId)[b]) buildBlock(rowId, b);
            int blockId = rowBlockIds.get(rowId)[b];
            if (blockId == -2) return transition(rowId, c);   // capped: compute directly
            if (blockId == -3) return SDFA_KILL;              // all-kill block
            return blocks.get(blockId)[c & 511];
        }
    }

    /**
     * Trigger scan: advance the search DFA over {@code [from, to)}; on the first
     * position where an accepting state is (over-approximately) live, return the
     * latest kill-point window start {@code W} ({@code from} if none) — every
     * surviving configuration started at or after {@code W}, so the exact leftmost
     * match lies in {@code [W, to]}. Returns -1 when no accept can fire at all.
     */
    private int triggerScan(String input, int from, int to) {
        // Short scans never amortize the memo (block build = 512 interned steps);
        // short-lived runners would allocate-and-die fat instead. Raw scan keeps
        // the kill-point window either way.
        SearchDfa sd = searchDfa;
        if (to - from < SDFA_MIN_WINDOW || sd.capped) {
            trace(Strategy.RAW_SCAN);
            return rawScan(input, from, to, from, null);
        }
        trace(Strategy.TRIGGER);
        int cur = 0;   // pure-seed row: interned first by construction below
        if (sd.rowWords.isEmpty()) {
            int[] seed = new int[sd.nw];
            seed[startState >>> 5] |= 1 << (startState & 31);
            if (sd.internRow(seed) != 0) throw new IllegalStateException("first row must be id 0");
        }
        int W = from;
        for (int pos = from; pos < to; ) {
            if (sd.accept(cur)) return W;
            int c = Alphabet.decode(input, pos, to);
            int adv = Alphabet.width(c);
            int v = c < 0x10000 ? sd.bmpTransition(cur, c) : sd.transition(cur, c);
            if (v == -1) {
                // Cap: continue unmemoized from pos WITH the exact live set —
                // restarting from a bare seed would drop configurations started
                // in [W, pos) that are still alive (and may accept later),
                // masking real matches (seen as skipped leipzig matches).
                return rawScan(input, pos, to, W, sd.rowWords.get(cur)); }
            if (v == SDFA_KILL) { W = pos + adv; cur = 0; }
            else cur = v;
            pos += adv;
        }
        return sd.accept(cur) ? W : -1;
    }

    /**
     * Uncapped fallback: the original multi-state simulation with kill-point
     * tracking (kill = the pre-seed step set is empty). Returns W or -1.
     */
    private int rawScan(String input, int from, int to, int wIn, int[] liveIn) {
        final int nwords = stateWords;
        Scratch sc = SCRATCH.get();
        int[] live = sc.live != null && sc.live.length >= nwords ? sc.live : new int[nwords];
        int[] next = sc.next != null && sc.next.length >= nwords ? sc.next : new int[nwords];
        sc.live = live; sc.next = next;
        if (liveIn != null) {
            System.arraycopy(liveIn, 0, live, 0, nwords);   // exact continuation
        } else {
            Arrays.fill(live, 0, nwords, 0);
            live[startState >>> 5] |= 1 << (startState & 31);
        }
        final int[] sm = stateMeta, rg = ranges, ab = acceptBits;
        int W = wIn;
        for (int pos = from; pos <= to; pos++) {
            for (int w = 0; w < nwords; w++) {
                if ((live[w] & ab[w]) != 0) return W;
            }
            if (pos == to) break;
            int c = Alphabet.decode(input, pos, to);
            int adv = Alphabet.width(c);
            Arrays.fill(next, 0, nwords, 0);
            boolean empty = true;
            for (int w = 0; w < nwords; w++) {
                int bits = live[w];
                while (bits != 0) {
                    int bit = Integer.numberOfTrailingZeros(bits);
                    bits &= bits - 1;
                    int s = (w << 5) + bit;
                    int meta = sm[s];
                    int base = stateBase[s];
                    int count = (meta >>> 1) & 0xFFFF;
                    int rlo = 0, rhi = count - 1, anchor = -1;
                    while (rlo <= rhi) {
                        int mid = (rlo + rhi) >>> 1;
                        if (rg[(base + mid) * 5] <= c) { anchor = mid; rlo = mid + 1; }
                        else rhi = mid - 1;
                    }
                    for (int i = anchor; i >= 0 && rhp[base + i] >= c; i--) {
                        int mo = (base + i) * 5;
                        if (c <= rg[mo + 1]) {
                            int t = rg[mo + 2];
                            if (t >= 0) { next[t >>> 5] |= 1 << (t & 31); empty = false; }
                        }
                    }
                }
            }
            // Always re-seed: after a kill the live set must be the pure seed
            // (match may start at pos+1), not empty — an empty live set would
            // kill every subsequent step too and mask real matches.
            next[startState >>> 5] |= 1 << (startState & 31);
            if (empty) W = pos + adv;
            int[] tmp = live; live = next; next = tmp;
            if (adv == 2) pos++;
        }
        return -1;
    }

    // ===== Multi-state parallel simulation (unanchored search) =====

    /**
     * Multi-state parallel simulation for unanchored search. Maintains the set
     * of all DFA states reachable from some start position in {@code [from, pos]},
     * checking for any accepting state at each position. Returns {@code true} as
     * soon as any accepting state enters the live set.
     *
     * <p>This is O(n × |states|) per call — a single forward pass — instead of
     * the O(n²) outer-loop restart used by the single-state extract paths. It
     * replaces the boolean {@code find()} path and serves as a fast no-match
     * pre-check for the extract paths: if this returns {@code false}, the
     * extract short-circuits to {@code null} without the O(n²) scan.
     *
     * <p>The implicit {@code .*?} prefix (unanchored search can start anywhere)
     * is modelled by re-adding the start state to the live set at every position.
     *
     * <p>For the generic path (masks / non-disjoint ranges) this is a sound
     * over-approximation: entry/accept/required masks are ignored and all
     * matching range targets are followed. A {@code false} result is definitive;
     * a {@code true} result means "might match" and the caller re-runs the exact
     * single-state path for registers / PERL priority.
     */
    private boolean multiStateAnyMatch(CharSequence input, int from, int to) {
        final int nwords = stateWords;
        if (nwords == 0) return false;
        final int[] sm = stateMeta;
        final int[] rg = ranges;
        final int[] at = asciiTarget;
        final int[] ab = acceptBits;
        final int ss = startState;

        Scratch sc = SCRATCH.get();
        int[] live = sc.live != null && sc.live.length >= nwords ? sc.live : new int[nwords];
        int[] next = sc.next != null && sc.next.length >= nwords ? sc.next : new int[nwords];
        sc.live = live; sc.next = next;
        Arrays.fill(live, 0, nwords, 0);
        live[ss >>> 5] |= 1 << (ss & 31);

        for (int pos = from; pos <= to; pos++) {
            for (int w = 0; w < nwords; w++) {
                if ((live[w] & ab[w]) != 0) return true;
            }
            if (pos == to) break;

            int c = Alphabet.decode(input, pos, to);
            int adv = Alphabet.width(c);

            Arrays.fill(next, 0, nwords, 0);   // grown Scratch: zero only our prefix
            next[ss >>> 5] |= 1 << (ss & 31);

            if (at != null && c < 128) {
                for (int w = 0; w < nwords; w++) {
                    int bits = live[w];
                    while (bits != 0) {
                        int bit = Integer.numberOfTrailingZeros(bits);
                        bits &= bits - 1;
                        int s = (w << 5) + bit;
                        int target = at[s * 128 + c];
                        if (target >= 0) {
                            next[target >>> 5] |= 1 << (target & 31);
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
                        int meta = sm[s];
                        int base = stateBase[s];
                        int count = (meta >>> 1) & 0xFFFF;
                        // Binary search + prefix-max walk: all entries containing c
                        // (over-approximation ignores masks, same as before).
                        int rlo = 0, rhi = count - 1, anchor = -1;
                        while (rlo <= rhi) {
                            int mid = (rlo + rhi) >>> 1;
                            if (rg[(base + mid) * 5] <= c) { anchor = mid; rlo = mid + 1; }
                            else rhi = mid - 1;
                        }
                        for (int i = anchor; i >= 0 && rhp[base + i] >= c; i--) {
                            int mo = (base + i) * 5;
                            if (c <= rg[mo + 1]) {
                                int target = rg[mo + 2];
                                if (target >= 0) next[target >>> 5] |= 1 << (target & 31);
                            }
                        }
                    }
                }
            }

            int[] tmp = live; live = next; next = tmp;
            if (adv == 2) pos++;
        }
        return false;
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
    /** Budget-exceeded sentinel for {@link #multiStateLeftmostStart}. */
    public static final int LSS_BUDGET = -2;

    // ===== ASM ladder hooks: strategy pieces the generated ladder calls.
    // TdfaRunner is final, so these invokevirtual sites are monomorphic and
    // inline wherever emitted. The generated ladder mirrors
    // runStringExtractFast exactly; the strategy-conformance test asserts
    // trace equality between backends. =====

    /** The exact-literal needle, or null (see detectLiteralNeedle). */

    /** True when a needle hit ending at unit {@code idx + needleLen - 1}
     *  swallows the high half of a surrogate pair: the last needle unit is a
     *  high surrogate that pairs with the next input unit, so the raw-unit
     *  indexOf hit is not a codepoint-sequence match. Public static: the
     *  ASM-emitted literal path calls it for the same guard. */
    public static boolean needleEndOverlapsPair(String s, int idx, int needleLen) {
        int last = s.charAt(idx + needleLen - 1);
        if (last < 0xD800 || last > 0xDBFF) return false;
        int end = idx + needleLen;
        return end < s.length()
                && s.charAt(end) >= 0xDC00 && s.charAt(end) <= 0xDFFF;
    }

    /** indexOf for the literal needle that respects the alphabet: a hit is
     *  real only if it starts at a codepoint boundary (not the low half of a
     *  pair) and does not end on the high half of a pair. Raw indexOf sees
     *  UTF-16 units and would otherwise accept unit sequences that overlap
     *  pair halves — e.g. needle "a\uD800" on input "a\uD800\uDFFF". */
    public static int literalIndexOf(String s, String needle, int from) {
        int idx = s.indexOf(needle, from);
        while (idx >= 0
                && (Alphabet.pairInterior(s, idx) || needleEndOverlapsPair(s, idx, needle.length())))
            idx = s.indexOf(needle, idx + 1);
        return idx;
    }

    public String literalNeedle() { return literalNeedle; }

    /** Defensive restart walk (sim and walk disagreed on a fast-path DFA):
     *  per-unit scan from {@code fromStart} with the pair-interior guard and
     *  an exact extract at each position. Public: the ASM-emitted ladder
     *  delegates here instead of emitting its own loop — one definition. */
    public MatchHolder restartExtract(String input, int fromStart, int to, int from0) {
        for (int s = fromStart; s <= to; s++) {
            if (Alphabet.pairInterior(input, s)) continue;
            MatchHolder h = tryStartFast(input, s, to, from0);
            if (h != null) return h;
        }
        return null;
    }

    /** First-char candidate bitset, or null when the start state accepts. */
    public long[] startBits() { return startBits; }

    /** Max input length for the candidate scan. */
    public int candScanMax() { return CAND_SCAN_MAX; }

    /** True = no masks + disjoint ranges: the fast extract ladder applies. */
    public boolean fastPath() { return fastPath; }

    /** Char budget for the origin sim before the trigger fallback. */
    public int originSimBudget() { return LSS_BUDGET_CHARS; }

    /** Origin-sim leftmost start; {@link #LSS_BUDGET} = budget exhausted. */
    public int originSimLeftmost(CharSequence input, int from, int to, int budget) {
        return multiStateLeftmostStart(input, from, to, budget);
    }

    /** Memoized search-DFA trigger scan: window start W, or -1 = no match. */
    public int triggerScanTop(String input, int from, int to) {
        return triggerScan(input, from, to);
    }

    /** Boolean single-start walk (fastPath only): does a match start at from? */
    public boolean booleanMatchFrom(String input, int from, int to) {        return matchFromFast(input, from, to);
    }

    private int multiStateLeftmostStart(CharSequence input, int from, int to) {
        return multiStateLeftmostStart(input, from, to, -1);
    }

    /**
     * Budgeted variant: aborts with {@link #LSS_BUDGET} after {@code budget}
     * chars without an accept (dense matches early-stop far inside; a distant
     * or absent match is better served by the memoized trigger scan, so the
     * caller falls back to it instead of bitset-scanning the whole tail).
     */
    private int multiStateLeftmostStart(CharSequence input, int from, int to, int budget) {
        final int nwords = stateWords;
        if (nwords == 0) return -1;
        final int[] sm = stateMeta;
        final int[] rg = ranges;
        final int[] at = asciiTarget;
        final int[] ab = acceptBits;
        final int ss = startState;

        int[] live, next, origin, originNext;
        Scratch sc = SCRATCH.get();
        if (sc.live != null && sc.live.length >= nwords && sc.next.length >= nwords
                && sc.origin != null && sc.origin.length >= stateCount && sc.originNext.length >= stateCount) {
            live = sc.live; next = sc.next;
            origin = sc.origin; originNext = sc.originNext;
        } else {
            live = new int[nwords]; next = new int[nwords];
            origin = new int[stateCount]; originNext = new int[stateCount];
            sc.live = live; sc.next = next; sc.origin = origin; sc.originNext = originNext;
        }
        // Origins are DOUBLE-BUFFERED with the state sets: origin[] pairs with
        // live[], originNext[] with next[]. All arrivals in a step write to
        // originNext (bit test against next), while old-live origins in origin[]
        // stay readable for the whole step — a single buffer would corrupt a
        // state's own origin mid-step when another path's arrival (or the fresh
        // re-seed) targets a still-live state before its self-loop reads it
        // (seen as +1/step origin drift on (\d+)\.(\d+)... over "ip=192.168.1.77").
        // Stale originNext values are never read: a bit present in next implies
        // a this-step write (first arrival sets, later arrivals min-merge).
        Arrays.fill(live, 0, nwords, 0);
        live[ss >>> 5] |= 1 << (ss & 31);
        origin[ss] = from;

        int best = -1;
        final int limit = budget < 0 ? Integer.MAX_VALUE : from + budget;
        for (int pos = from; pos <= to; pos++) {
            if (best < 0 && pos > limit) return LSS_BUDGET;
            // accept check with origin tracking
            for (int w = 0; w < nwords; w++) {
                int bits = live[w] & ab[w];
                while (bits != 0) {
                    int bit = Integer.numberOfTrailingZeros(bits);
                    bits &= bits - 1;
                    int s = (w << 5) + bit;
                    if (best < 0 || origin[s] < best) best = origin[s];
                }
            }
            if (best >= 0) {
                // Can any future accept beat `best`? Future accept origins are
                // either origins of currently-live states or seeds > pos.
                // best==from is the common dense case and stops immediately.
                int minLive = Integer.MAX_VALUE;
                for (int w = 0; w < nwords && minLive > best; w++) {
                    int bits = live[w];
                    while (bits != 0) {
                        int bit = Integer.numberOfTrailingZeros(bits);
                        bits &= bits - 1;
                        int s = (w << 5) + bit;
                        if (origin[s] < minLive) minLive = origin[s];
                    }
                }
                if (best <= pos && best <= minLive) return best;
            }
            if (pos == to) break;

            int c = Alphabet.decode(input, pos, to);
            int adv = Alphabet.width(c);

            Arrays.fill(next, 0, nwords, 0);   // grown Scratch: zero only our prefix
            if (at != null && c < 128) {
                for (int w = 0; w < nwords; w++) {
                    int bits = live[w];
                    while (bits != 0) {
                        int bit = Integer.numberOfTrailingZeros(bits);
                        bits &= bits - 1;
                        int s = (w << 5) + bit;
                        int target = at[s * 128 + c];
                        if (target >= 0) setOrigin(next, originNext, target, origin[s]);
                    }
                }
            } else {
                for (int w = 0; w < nwords; w++) {
                    int bits = live[w];
                    while (bits != 0) {
                        int bit = Integer.numberOfTrailingZeros(bits);
                        bits &= bits - 1;
                        int s = (w << 5) + bit;
                        int meta = sm[s];
                        int base = stateBase[s];
                        int count = (meta >>> 1) & 0xFFFF;
                        // rangesDisjoint == true on the fast path — binary search
                        int rlo = 0, rhi = count - 1;
                        while (rlo <= rhi) {
                            int mid = (rlo + rhi) >>> 1;
                            int mo = (base + mid) * 5;
                            if (c < rg[mo]) { rhi = mid - 1; continue; }
                            if (c > rg[mo + 1]) { rlo = mid + 1; continue; }
                            int target = rg[mo + 2];
                            if (target >= 0) setOrigin(next, originNext, target, origin[s]);
                            break;
                        }
                    }
                }
            }
            setOrigin(next, originNext, ss, pos + adv);  // unanchored re-seed (min-merge if already re-added)

            int[] tmp = live; live = next; next = tmp;
            int[] to2 = origin; origin = originNext; originNext = to2;
            if (adv == 2) pos++;
        }
        return best;
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

    // ===== Fast paths: no masks, disjoint ranges, ASCII-only =====

    /**
     * Ultra-tight anchored match for DFAs with no masks and disjoint ranges.
     * Uses a flat precomputed target table: one array load per char.
     * Falls back to {@link #runStringAnchored} on non-ASCII input.
     */
    private boolean runStringAnchoredFast(String input) {
        final int to = input.length();
        final int[] sm = this.stateMeta;
        final int[] at = this.latinTarget;
        final int tblLimit = this.latinLimit;
        int state = startState;
        final int limit = this.latinLimit;
        for (int pos = 0; pos < to; pos++) {
            char c = input.charAt(pos);
            if (c >= limit) return runStringAnchored(input) >= 0;
            state = at[state * limit + c];
            if (state < 0) return false;
        }
        return (sm[state] & 1) != 0;
    }

    /** Unanchored boolean search via single-pass multi-state simulation. O(n × |states|). */
    private boolean runStringFindFast(String input, int to) {
        // Short inputs: first-char-set candidate scan (one bit test per char,
        // exact walk per candidate) beats the raw-scan live-set simulation.
        if (startBits != null && to <= CAND_SCAN_MAX) {
            trace(Strategy.CAND_SCAN);
            final long[] sb = this.startBits;
            for (int p = 0; p < to; p++) {
                char c = input.charAt(p);
                if ((sb[c >>> 6] >>> (c & 63) & 1L) != 0L && (c < 0xDC00 || !Alphabet.pairInterior(input, p))
                        && matchFromFast(input, p, to)) return true;
            }
            return false;
        }
        return triggerScan(input, 0, to) >= 0;
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
            } else if (c < 0x10000) {
                ri = walkRangeIndex(state, c);
                if (ri == -2) ri = Integer.MIN_VALUE;
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
                    if (c < rg[mo]) { rhi = mid - 1; continue; }
                    if (c > rg[mo + 1]) { rlo = mid + 1; continue; }
                    ri = mid;
                    break;
                }
            }
            if (ri < 0) return false;
            int target = rg[(stateBase[state] + ri) * 5 + 2];
            if (target < 0) return false;
            state = target;
            pos += adv;
            if ((sm[state] & 1) != 0) return true;
        }
        return false;
    }

    /** Fast extract with register updates. */
    private MatchHolder runStringExtractFast(String input, int from, int to) {
        if (literalNeedle != null) {
            trace(Strategy.LITERAL);
            int idx = literalIndexOf(input, literalNeedle, from);
            return idx < 0 ? null : new MatchHolder(idx, idx + literalNeedle.length(), new int[0]);
        }
        // 1) Try ONE single-start walk from `from` — the common short-input case
        //    (match at/near the start) never needs the simulation at all.
        trace(Strategy.EXACT_FROM);
        MatchHolder h = tryStartFast(input, from, to, from);
        if (h != null) return h;
        // 1b) Short inputs: first-char-set candidate scan. Coverage is exact
        //     (start state not accepting — else startBits is null — so every
        //     match consumes a first char carrying its bit); each candidate
        //     gets an exact walk, so the first hit is the true leftmost match.
        //     After a few failed extract walks (dense-candidate no-match
        //     shapes, e.g. \w+@... on prose — per-walk regs + applyOps cost),
        //     a no-regs boolean walk filters the remaining candidates first.
        if (startBits != null && to - from <= CAND_SCAN_MAX) {
            trace(Strategy.CAND_SCAN);
            final long[] sb = this.startBits;
            int fails = 0;
            for (int p = from + 1; p < to; p++) {
                char c = input.charAt(p);
                if ((sb[c >>> 6] >>> (c & 63) & 1L) == 0L) continue;
                if (c >= 0xDC00 && Alphabet.pairInterior(input, p)) continue;
                if (fails >= 3 && !matchFromFast(input, p, to)) continue;
                h = tryStartFast(input, p, to, from);
                if (h != null) return h;
                fails++;
            }
            return null;
        }
        // 2) No match starting at `from`: budgeted origin-tracking sim. Dense
        //    matches early-stop inside the budget and never touch the trigger
        //    (the pre-scan would double the work — the findAll regression). A
        //    distant/absent match exhausts the budget and hands off to the
        //    memoized trigger scan, which bounds the window to [W, to] via kill
        //    points; the sim then finishes over just that window. The old shape
        //    retried every failed start with a full walk: O(n) restarts × O(n)
        //    walk = O(n²) on dense-match regexes like [a-zA-Z]+ing.
        trace(Strategy.ORIGIN_SIM);
        int leftmost = multiStateLeftmostStart(input, from, to, LSS_BUDGET_CHARS);
        if (leftmost == LSS_BUDGET) {
            int w = triggerScan(input, from, to);
            if (w < 0) return null;
            leftmost = multiStateLeftmostStart(input, w, to);
        }
        if (leftmost < 0) return null;
        h = tryStartFast(input, leftmost, to, from);
        if (h != null) return h;
        // 3) Defensive: the sim and the walk must agree on fast-path DFAs; if
        //    they ever don't, fall back to the old restart shape rather than
        //    return a wrong null.
        trace(Strategy.WALK_RESTART);
        return restartExtract(input, leftmost + 1, to, from);
    }

    /**
     * One single-start extract walk (no restart loop); null if no match starts
     * exactly at {@code start}. Non-Latin-1 codepoints mid-walk fall back to
     * the generic exact walk from the SAME start (single-start semantics —
     * callers treat null as "no match here", not "no match anywhere").
     */
    private MatchHolder tryStartFast(String input, int start, int to, int originFrom) {
        final int[] sm = this.stateMeta;
        final int[] arf = this.asciiRangeFlat;
        final int[] rg = this.ranges;
        final int[] op = this.ops;
        // Leftmost-first stopOnAccept: pre-load the mask table when not in
        // longest-match mode so the inner loop can short-circuit on first
        // accepting state (matching the slow path).
        final boolean pm = !this.longestMatch;
        // Pooled regs (per-thread scratch): no allocation on the (frequent)
        // failed-walk path. On success the array is cloned into the MatchHolder
        // before returning, so the pool is never handed out. NOTE: the
        // non-ASCII fallback below re-enters the generic extract path, which
        // allocates its own regs — no pool aliasing.
        Scratch sc = SCRATCH.get();
        final int[] regs;
        if (regSize == 0) {
            regs = null;
        } else if (sc.regs != null && sc.regs.length >= regSize) {
            regs = sc.regs;
            Arrays.fill(regs, 0, regSize, -1);
        } else {
            regs = new int[regSize];
            java.util.Arrays.fill(regs, -1);
            sc.regs = regs;
        }
        int state = startState;
        int lastAcceptPos = -1, lastAcceptState = -1;
        boolean haveAccept = false;
        int posFlags = -1; // lazy: -1 means not yet computed for current pos
        int pos = start;
        for (; pos <= to; pos++) {
            int meta = sm[state];
            if ((meta & 1) != 0) {
                final int[] fm = this.finalOpsByMask;
                if (fm != null) {
                    if (posFlags < 0) posFlags = positionFlags(input, pos, to);
                    int cell = fm[state * 64 + posFlags];
                    if (cell < 0) continue;
                    haveAccept = true; lastAcceptPos = pos; lastAcceptState = state;
                    if (regs != null && cell != 0) applyOps(op, cell, regs, pos);
                    if (pm && stopNow(state, posFlags)) break;
                } else {
                    haveAccept = true; lastAcceptPos = pos; lastAcceptState = state;
                    if (regs != null) applyFinalOps(state, regs, pos);
                    if (pm) {
                        if (stopMaskUniform == null) posFlags = positionFlags(input, pos, to);
                        if (stopNow(state, posFlags)) break;
                    }
                }
            }
            if (pos == to) break;
            char c = input.charAt(pos);
            if (c >= latinLimit) return extractFrom(input, start, to);
            int ri = arf[state * latinLimit + c];
            if (ri < 0) break;
            int mo = (stateBase[state] + ri) * 5;
            int target = rg[mo + 2];
            if (target < 0) break;
            if (regs != null) {
                int opsOff = rg[mo + 3];
                if (opsOff != 0) applyOps(op, opsOff, regs, pos);
            }
            state = target;
        }
        if (haveAccept) {
            // Eager finals at accept-record time (see extractFrom).
            return new MatchHolder(start, lastAcceptPos, regs == null ? new int[0] : regs.clone());
        }
        return null;
    }

    /** Anchored String match. Returns lastAcceptPos (>=0) on match, -1 on no match. No allocation. */
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
            if (entryReq != 0 && (positionFlags(input, 0, to) & entryReq) != entryReq) return -1;
        }

        int posFlags = -1; // lazy: -1 means not yet computed for current pos
        for (int pos = 0; pos <= to; pos++) {
            int meta = sm[state];
            if ((meta & 1) != 0) {
                int acceptMask = sam[state];
                if (acceptMask == 0) {
                    lastAcceptPos = pos;
                } else {
                    if (posFlags < 0) posFlags = positionFlags(input, pos, to);
                    if ((posFlags & acceptMask) == acceptMask) lastAcceptPos = pos;
                }
            }
            if (pos == to) break;
            int c = Alphabet.decode(input, pos, to);
            int base = stateBase[state];
            int count = (meta >>> 1) & 0xFFFF;
            boolean matched = false;
            if (asciiTables) {
                // ASCII fast path: direct table lookup
                int ri = c < latinLimit ? asciiRangeFlat[state * latinLimit + c] : -2;
                if (ri >= 0) {
                    int mo = (base + ri) * 5;
                    int target = rg[mo + 2];
                    if (target >= 0) {
                        int requiredMask = rg[mo + 4];
                        boolean maskOk = true;
                        if (requiredMask != 0) {
                            if (posFlags < 0) posFlags = positionFlags(input, pos, to);
                            maskOk = (posFlags & requiredMask) == requiredMask;
                        }
                        if (maskOk) {
                            state = target;
                            if (c > 0xFFFF) pos++;
                            int entryReq = sem[state];
                            if (entryReq == 0 || (positionFlags(input, pos + 1, to) & entryReq) == entryReq) {
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
                        if (c < rg[mo]) { rhi = mid - 1; continue; }
                        if (c > rg[mo + 1]) { rlo = mid + 1; continue; }
                        int target = rg[mo + 2];
                        if (target < 0) break;
                        int requiredMask = rg[mo + 4];
                        if (requiredMask != 0) {
                            if (posFlags < 0) posFlags = positionFlags(input, pos, to);
                            if ((posFlags & requiredMask) != requiredMask) break;
                        }
                        state = target;
                        if (c > 0xFFFF) pos++;
                        int entryReq = sem[state];
                        if (entryReq != 0) {
                            if ((positionFlags(input, pos + 1, to) & entryReq) != entryReq) {
                                return lastAcceptPos == to ? lastAcceptPos : -1;
                            }
                        }
                        matched = true;
                        break;
                    }
                }
            } else {
                // Non-disjoint state: binary search + prefix-max walk, keeping the
                // lowest-index mask-satisfied entry (original priority order).
                int rlo = 0, rhi = count - 1, anchor = -1;
                while (rlo <= rhi) {
                    int mid = (rlo + rhi) >>> 1;
                    if (rg[(base + mid) * 5] <= c) { anchor = mid; rlo = mid + 1; }
                    else rhi = mid - 1;
                }
                int chosen = -1, chosenTarget = 0;
                for (int i = anchor; i >= 0 && rhp[base + i] >= c; i--) {
                    int o = (base + i) * 5;
                    if (c <= rg[o + 1]) {
                        int target = rg[o + 2];
                        if (target < 0) continue;
                        int requiredMask = rg[o + 4];
                        if (requiredMask != 0) {
                            if (posFlags < 0) posFlags = positionFlags(input, pos, to);
                            if ((posFlags & requiredMask) != requiredMask) continue;
                        }
                        chosen = o; chosenTarget = target;
                    }
                }
                if (chosen >= 0) {
                    state = chosenTarget;
                    if (c > 0xFFFF) pos++;
                    int entryReq = sem[state];
                    if (entryReq != 0) {
                        if ((positionFlags(input, pos + 1, to) & entryReq) != entryReq) {
                            return lastAcceptPos == to ? lastAcceptPos : -1;
                        }
                    }
                    matched = true;
                }
            }
            if (!matched) break;
            posFlags = -1;
        }
        return lastAcceptPos == to ? lastAcceptPos : -1;
    }

    /** String match from position, returns lastAcceptPos or -1. No allocation. */
    private int runStringMatchFrom(String input, int from, int to) {
        final int[] sm = this.stateMeta;
        final int[] rg = this.ranges;
        final int[] sem = this.stateEntryMask;
        final int[] sam = this.stateAcceptMask;
        final int[] arf = this.asciiRangeFlat;   // non-null iff rangesDisjoint
        final int limit = this.latinLimit;
        int state = startState;
        int lastAcceptPos = -1;
        boolean haveAccept = false;
        int pos = from;

        // Entry check for start state — inline
        {
            int entryReq = sem[state];
            if (entryReq != 0 && (positionFlags(input, pos, to) & entryReq) != entryReq) return -1;
        }

        int posFlags = -1;
        for (; ; pos++) {
            int meta = sm[state];
            if ((meta & 1) != 0) {
                int acceptMask = sam[state];
                if (acceptMask == 0) {
                    haveAccept = true; lastAcceptPos = pos;
                    if (!longestMatch) {
                        if (posFlags < 0 && stopMaskUniform == null) posFlags = positionFlags(input, pos, to);
                        if (stopNow(state, posFlags)) break;
                    }
                } else {
                    if (posFlags < 0) posFlags = positionFlags(input, pos, to);
                    if ((posFlags & acceptMask) == acceptMask) {
                        haveAccept = true; lastAcceptPos = pos;
                        if (!longestMatch && stopNow(state, posFlags)) break;
                    }
                }
            }
            if (pos >= to) break;
            int c = Alphabet.decode(input, pos, to);
            int base = stateBase[state];
            int count = (meta >>> 1) & 0xFFFF;
            boolean matched = false;
            int riFlat;
            if (arf != null && c < limit) {
                riFlat = arf[state * limit + c];
            } else if (rangesDisjoint && c < 0x10000) {
                // tableless giant DFA (see ASCII_TABLE_MAX_STATES): walk blocks
                riFlat = walkRangeIndex(state, c);
                if (riFlat == -2) riFlat = Integer.MIN_VALUE;   // block cap: binary search
            } else {
                riFlat = Integer.MIN_VALUE;
            }
            if (riFlat != Integer.MIN_VALUE) {
                // disjoint flat/block lookup: exact (priority moot, single entry)
                if (riFlat >= 0) {
                    int mo = (base + riFlat) * 5;
                    int target = rg[mo + 2];
                    if (target < 0) return haveAccept ? lastAcceptPos : -1;
                    int requiredMask = rg[mo + 4];
                    if (requiredMask != 0) {
                        if (posFlags < 0) posFlags = positionFlags(input, pos, to);
                        if ((posFlags & requiredMask) != requiredMask) return haveAccept ? lastAcceptPos : -1;
                    }
                    state = target;
                    if (c > 0xFFFF) pos++;
                    int entryReq = sem[state];
                    if (entryReq != 0) {
                        if ((positionFlags(input, pos + 1, to) & entryReq) != entryReq) {
                            return haveAccept ? lastAcceptPos : -1;
                        }
                    }
                    matched = true;
                }
            } else if (rangesDisjoint) {
                int rlo = 0, rhi = count - 1;
                while (rlo <= rhi) {
                    int mid = (rlo + rhi) >>> 1;
                    int mo = (base + mid) * 5;
                    if (c < rg[mo]) { rhi = mid - 1; continue; }
                    if (c > rg[mo + 1]) { rlo = mid + 1; continue; }
                    int target = rg[mo + 2];
                    if (target < 0) return haveAccept ? lastAcceptPos : -1;
                    int requiredMask = rg[mo + 4];
                    if (requiredMask != 0) {
                        if (posFlags < 0) posFlags = positionFlags(input, pos, to);
                        if ((posFlags & requiredMask) != requiredMask) return haveAccept ? lastAcceptPos : -1;
                    }
                    state = target;
                    if (c > 0xFFFF) pos++;
                    int entryReq = sem[state];
                    if (entryReq != 0) {
                        if ((positionFlags(input, pos + 1, to) & entryReq) != entryReq) {
                            return haveAccept ? lastAcceptPos : -1;
                        }
                    }
                    matched = true;
                    break;
                }
            } else {
                // Non-disjoint state: binary search + prefix-max walk, keeping the
                // lowest-index mask-satisfied entry (original priority order).
                int rlo = 0, rhi = count - 1, anchor = -1;
                while (rlo <= rhi) {
                    int mid = (rlo + rhi) >>> 1;
                    if (rg[(base + mid) * 5] <= c) { anchor = mid; rlo = mid + 1; }
                    else rhi = mid - 1;
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
                            if (posFlags < 0) posFlags = positionFlags(input, pos, to);
                            if ((posFlags & requiredMask) != requiredMask) continue;
                        }
                        int spec = Integer.bitCount(requiredMask);
                        if (spec >= bestSpec) { chosen = o; chosenTarget = rg[o + 2]; bestSpec = spec; }
                    }
                }
                if (chosen < 0 || chosenTarget < 0) {
                    return haveAccept ? lastAcceptPos : -1;   // owning context dead: no fallthrough
                }
                {
                    state = chosenTarget;
                    if (c > 0xFFFF) pos++;
                    int entryReq = sem[state];
                    if (entryReq != 0) {
                        if ((positionFlags(input, pos + 1, to) & entryReq) != entryReq) {
                            return haveAccept ? lastAcceptPos : -1;
                        }
                    }
                    matched = true;
                }
            }
            if (!matched) break;
            posFlags = -1;
        }
        return haveAccept ? lastAcceptPos : -1;
    }

    private MatchHolder runGeneric(CharSequence input, int from, int to, boolean anchored) {
        int startSearch = from;
        while (true) {
            final int[] regs = regSize == 0 ? null : new int[regSize];
            if (regs != null) Arrays.fill(regs, -1);
            int state = startState;
            int lastAcceptPos = -1, lastAcceptState = -1;
            boolean haveAccept = false;
            int pos = startSearch;

            // Entry check for start state — inline
            {
                int entryReq = stateEntryMask[state];
                if (entryReq != 0 && (positionFlagsCS(input, pos, to) & entryReq) != entryReq) {
                    if (anchored) return null;
                    if ((startStateEntryMask & Tnfa.ABS_BEGIN) != 0) return null;
                    startSearch++;
                    if (startSearch > to) return null;
                    continue;
                }
            }

            int posFlags = -1;
            loop:
            for (; ; pos++) {
                int meta = stateMeta[state];
                if ((meta & 1) != 0) {
                    final int[] fm = this.finalOpsByMask;
                    if (fm != null) {
                        if (posFlags < 0) posFlags = positionFlagsCS(input, pos, to);
                        int cell = fm[state * 64 + posFlags];
                        if (cell >= 0) {
                            lastAcceptPos = pos; lastAcceptState = state; haveAccept = true;
                            if (regs != null && cell != 0) applyOps(ops, cell, regs, pos);
                            if (!longestMatch && stopNow(state, posFlags)) break loop;
                        }
                    } else {
                        int acceptMask = stateAcceptMask[state];
                        if (acceptMask == 0) {
                            lastAcceptPos = pos; lastAcceptState = state; haveAccept = true;
                            if (regs != null) applyFinalOps(state, regs, pos);
                            if (!longestMatch) {
                                if (posFlags < 0) posFlags = positionFlagsCS(input, pos, to);
                                if (stopNow(state, posFlags)) break loop;
                            }
                        } else {
                            if (posFlags < 0) posFlags = positionFlagsCS(input, pos, to);
                            if ((posFlags & acceptMask) == acceptMask) {
                                lastAcceptPos = pos; lastAcceptState = state; haveAccept = true;
                                if (regs != null) applyFinalOps(state, regs, pos);
                                if (!longestMatch && stopNow(state, posFlags)) break loop;
                            }
                        }
                    }
                }
            if (pos >= to) break;
            int c = Alphabet.decode(input, pos, to);
            int base = stateBase[state];
                int count = (meta >>> 1) & 0xFFFF;
                // Binary search + prefix-max walk (CharSequence variant).
                int rlo = 0, rhi = count - 1, anchor = -1;
                while (rlo <= rhi) {
                    int mid = (rlo + rhi) >>> 1;
                    if (ranges[(base + mid) * 5] <= c) { anchor = mid; rlo = mid + 1; }
                    else rhi = mid - 1;
                }
                int chosen = -1, chosenTarget = 0;
                // Most-specific satisfied mask owns the step (popcount, ties
                // to lowest index) — see extractFrom for the protocol.
                int bestSpec = -1;
                for (int i = anchor; i >= 0 && rhp[base + i] >= c; i--) {
                    int o = (base + i) * 5;
                    if (c <= ranges[o + 1]) {
                        int requiredMask = ranges[o + 4];
                        if (requiredMask != 0) {
                            if (posFlags < 0) posFlags = positionFlagsCS(input, pos, to);
                            if ((posFlags & requiredMask) != requiredMask) continue;
                        }
                        int spec = Integer.bitCount(requiredMask);
                        if (spec >= bestSpec) { chosen = o; chosenTarget = ranges[o + 2]; bestSpec = spec; }
                    }
                }
                if (chosen < 0 || chosenTarget < 0) break;
                // Mask before ops — see extractFrom.
                int width = c > 0xFFFF ? 2 : 1;
                int entryReqNext = stateEntryMask[chosenTarget];
                if (entryReqNext != 0
                        && (positionFlagsCS(input, pos + width, to) & entryReqNext) != entryReqNext) break;
                if (regs != null) {
                    int opsOff = ranges[chosen + 3];
                    if (opsOff != 0) applyOps(ops, opsOff, regs, pos);
                }
                state = chosenTarget;
                if (width == 2) pos++;
                posFlags = -1;
            }
            if (haveAccept) {
                if (anchored && lastAcceptPos != to) return null;
                // Eager finals at accept-record time (see extractFrom).
                return new MatchHolder(startSearch, lastAcceptPos, regs == null ? new int[0] : regs.clone());
            }
            if (anchored) return null;
            if ((startStateEntryMask & Tnfa.ABS_BEGIN) != 0) return null;
            startSearch++;
            if (startSearch > to) return null;
        }
    }

    private static void applyOps(int[] ops, int opsOff, int[] regs, int pos) {
        for (int j = opsOff; ; j += 3) {
            int op = ops[j];
            if (op == Tdfa.OP_END) return;
            int dst = ops[j + 1];
            if (op == Tdfa.OP_SET_POS) regs[dst] = pos;
            else if (op == Tdfa.OP_COPY) regs[dst] = regs[ops[j + 2]];
            else regs[dst] = -1;
        }
    }

    /**
     * Pick the right final-ops offset for {@code lastAcceptState}: if it's a
     * fallback state AND the runner took at least one transition since the last
     * accept ({@code pos > lastAcceptPos}), use the §6.2 ψ quasi-transition
     * (whose clobbered COPYs were routed through backups on the way out).
     * Otherwise use the regular {@code φ}.
     */
    private int pickFinalOpsOff(int lastAcceptState, int lastAcceptPos, int pos) {
        if (stateIsFallback != null && stateIsFallback.length > lastAcceptState
                && stateIsFallback[lastAcceptState] && pos > lastAcceptPos) {
            return stateFallbackOpsOff[lastAcceptState];
        }
        return stateFinalOpsOff[lastAcceptState];
    }

    /**
     * Apply an accepting state's φ final ops into {@code regs} at the moment
     * the accept is recorded (BT22's match-declaration semantics). φ reads the
     * accept config's WORKING registers, which hold the correct values only at
     * accept time — any transition taken afterwards may clobber them. Later
     * accepts overwrite earlier ones (last write wins), so the register file at
     * walk end already carries the last accept's finals. Only the ASM-emitted
     * ladder's lazy path still consults {@link #pickFinalOpsOff} (ψ for
     * fallback states); with eager application {@code pos == lastAcceptPos}
     * always holds and φ is the correct choice.
     */
    private void applyFinalOps(int state, int[] regs, int pos) {
        int foff = stateFinalOpsOff[state];
        if (foff != 0) applyOps(ops, foff, regs, pos);
    }

    // ===== Zero-width assertion position-flag computation =====

    /**
     * Determine whether to break the match loop on accept, based on the
     * position-aware per-(state, posFlags) cell of {@link Tdfa#stopOnAcceptMask}:
     * - {@link Tdfa#NEVER_STOP}: don't stop (sym-bearing config outranks accept
     *   under this posFlags, or accept unreachable).
     * - 0: stop (accept is the highest-priority live outcome under this posFlags).
     * The {@code posFlags} argument is unused beyond the array index computed
     * by the caller; kept for signature parity.
     */
    /** Tiered stop lookup: true = report the match now (cell says stop).
     *  Uniform tier (assertion-free patterns): 1 B/state, posFlags irrelevant. */
    private boolean stopNow(int state, int posFlags) {
        byte[] u = stopMaskUniform;
        if (u != null) return u[state] == 0;
        return stopOnAcceptMask[state * 64 + posFlags] != Tdfa.NEVER_STOP;
    }

    /** Compute the position-flags for `pos` in a String. */
    private int positionFlags(String s, int pos, int len) {
        int flags = 0;
        if (pos == 0 || (pos > 0 && s.charAt(pos - 1) == '\n')) flags |= Tnfa.BEGIN_TEXT;
        if (pos == len || (pos < len && s.charAt(pos) == '\n')) flags |= Tnfa.END_TEXT;
        if (pos == 0) flags |= Tnfa.ABS_BEGIN;   // \A: absolute start, never affected by (?m)
        if (pos == len) flags |= Tnfa.ABS_END;    // \z: absolute end, never affected by (?m)
        if (needsWordFlags) {
            boolean prevWord = isWordBefore(s, pos);
            boolean currWord = isWordAt(s, pos, len);
            if (prevWord != currWord) flags |= Tnfa.WORD_BOUNDARY;
            else flags |= Tnfa.NO_WORD_BOUNDARY;
        }
        return flags;
    }

    /** Same for a generic CharSequence. */
    private int positionFlagsCS(CharSequence s, int pos, int len) {
        int flags = 0;
        if (pos == 0 || (pos > 0 && s.charAt(pos - 1) == '\n')) flags |= Tnfa.BEGIN_TEXT;
        if (pos == len || (pos < len && s.charAt(pos) == '\n')) flags |= Tnfa.END_TEXT;
        if (pos == 0) flags |= Tnfa.ABS_BEGIN;
        if (pos == len) flags |= Tnfa.ABS_END;
        if (needsWordFlags) {
            boolean prevWord = isWordBefore(s, pos);
            boolean currWord = isWordAt(s, pos, len);
            if (prevWord != currWord) flags |= Tnfa.WORD_BOUNDARY;
            else flags |= Tnfa.NO_WORD_BOUNDARY;
        }
        return flags;
    }

    /** Check if all states have pairwise-disjoint ranges (no overlapping ranges). */
    private static boolean checkRangesDisjoint(Tdfa tdfa) {
        int[] sm = tdfa.stateMeta, rg = tdfa.ranges;
        long[] sortBuf = null;
        for (int s = 0; s < tdfa.stateCount; s++) {
            int meta = sm[s];
            int base = tdfa.stateBase[s], cnt = (meta >>> 1) & 0xFFFF;
            if (cnt < 2) continue;
            // Fast path: ranges are emitted sorted by lo at materialization
            // (sortByMaskSpecificity is the only reorderer) — one O(cnt) scan.
            boolean sortedByLo = true;
            for (int i = 1; i < cnt; i++) {
                if (rg[(base + i) * 5] < rg[(base + i - 1) * 5]) { sortedByLo = false; break; }
            }
            if (!sortedByLo) {
                // Pack (lo << 32)|hi and sort — O(cnt log cnt) vs the old O(cnt²)
                // pairwise check (significant for wide Unicode classes, ~1369 ranges).
                if (sortBuf == null || sortBuf.length < cnt) sortBuf = new long[Math.max(cnt, 64)];
                for (int i = 0; i < cnt; i++) {
                    int o = (base + i) * 5;
                    sortBuf[i] = ((long) rg[o] << 32) | (rg[o + 1] & 0xFFFFFFFFL);
                }
                java.util.Arrays.sort(sortBuf, 0, cnt);
                int maxHi = (int) sortBuf[0];
                for (int i = 1; i < cnt; i++) {
                    int lo = (int) (sortBuf[i] >>> 32);
                    if (lo <= maxHi) return false;  // overlaps the interval holding maxHi
                    int hi = (int) sortBuf[i];
                    if (hi > maxHi) maxHi = hi;
                }
                continue;
            }
            // Sorted by lo: adjacent scan with running max-hi (a long early range
            // can overlap several later ones, so plain prev-pair checks aren't enough).
            int maxHi = rg[base * 5 + 1];
            for (int i = 1; i < cnt; i++) {
                int o = (base + i) * 5;
                if (rg[o] <= maxHi) return false;
                if (rg[o + 1] > maxHi) maxHi = rg[o + 1];
            }
        }
        return true;
    }

    /**
     * Bitset of states with accept capability (stateMeta bit 0 set). This is an
     * over-approximation for the generic path — a state may be only conditionally
     * accepting (non-zero acceptMask), but for the multi-state no-match pre-check
     * we want to err on the side of "might accept" so we never skip a real match.
     */
    private static int[] buildAcceptBits(Tdfa tdfa) {
        int words = (tdfa.stateCount + 31) >>> 5;
        int[] bits = new int[words];
        for (int s = 0; s < tdfa.stateCount; s++) {
            if ((tdfa.stateMeta[s] & 1) != 0) {
                bits[s >>> 5] |= 1 << (s & 31);
            }
        }
        return bits;
    }

    /**
     * Build flat per-state target lookup: {@code [state * limit + c] → target state}
     * (-1 = dead). {@code limit} is 256 (Latin-1) for DFAs under
     * {@link #LATIN1_MAX_STATES} states, else 128. Codepoints 128..255 are single
     * UTF-16 units and never surrogate halves, so indexing them directly is exact.
     */
    private static int[] buildAsciiTarget(Tdfa tdfa, int limit) {
        int[] sm = tdfa.stateMeta, rg = tdfa.ranges;
        int[] flat = new int[tdfa.stateCount * limit];
        java.util.Arrays.fill(flat, -1);
        for (int s = 0; s < tdfa.stateCount; s++) {
            int meta = sm[s];
            int base = tdfa.stateBase[s], cnt = (meta >>> 1) & 0xFFFF;
            for (int i = 0; i < cnt; i++) {
                int o = (base + i) * 5;
                int lo = Math.max(rg[o], 0);
                int hi = Math.min(rg[o + 1], limit - 1);
                int target = rg[o + 2];
                for (int c = lo; c <= hi; c++) flat[s * limit + c] = target;
            }
        }
        return flat;
    }

    /** Build flat per-state range-index lookup: {@code [state * limit + c] → range index} (-1 = dead). */
    private static int[] buildAsciiRangeFlat(Tdfa tdfa, int limit) {
        int[] sm = tdfa.stateMeta, rg = tdfa.ranges;
        int[] flat = new int[tdfa.stateCount * limit];
        java.util.Arrays.fill(flat, -1);
        for (int s = 0; s < tdfa.stateCount; s++) {
            int meta = sm[s];
            int base = tdfa.stateBase[s], cnt = (meta >>> 1) & 0xFFFF;
            for (int i = 0; i < cnt; i++) {
                int o = (base + i) * 5;
                int lo = Math.max(rg[o], 0);
                int hi = Math.min(rg[o + 1], limit - 1);
                for (int c = lo; c <= hi; c++) flat[s * limit + c] = i;
            }
        }
        return flat;
    }

    /** True if the DFA qualifies for the no-masks fast path. */
    private boolean computeFastPath(Tdfa tdfa) {
        // fastPath methods (tryStartFast/matchFromFast/runStringAnchoredFast)
        // dereference the eager ASCII dispatch tables unconditionally — they
        // require asciiTables (disjoint ∧ ≤ ASCII_TABLE_MAX_STATES).
        if (!asciiTables || multiline) return false;
        for (int mask : tdfa.stateEntryMask) if (mask != 0) return false;
        for (int mask : tdfa.stateAcceptMask) if (mask != 0) return false;
        for (int i = 4; i < tdfa.ranges.length; i += 5) if (tdfa.ranges[i] != 0) return false;
        return true;
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
        if (wr == null) return false;
        int lo = 0, hi = wr.length / 2 - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int rLo = wr[2 * mid], rHi = wr[2 * mid + 1];
            if (cp < rLo) hi = mid - 1;
            else if (cp > rHi) lo = mid + 1;
            else return true;
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
        if (pos <= 0) return false;
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
        if (pos >= len) return false;
        char c = s.charAt(pos);
        if (unicodeWordBoundary && c >= Character.MIN_HIGH_SURROGATE && c <= Character.MAX_HIGH_SURROGATE && pos + 1 < len) {
            char l = s.charAt(pos + 1);
            if (l >= Character.MIN_LOW_SURROGATE && l <= Character.MAX_LOW_SURROGATE) {
                return isUnicodeWordCodepoint(((c - 0xD800) << 10) + (l - 0xDC00) + 0x10000);
            }
        }
        return isWordChar(c);
    }
}
