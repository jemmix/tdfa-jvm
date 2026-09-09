package io.github.jemmix.tdfa.tdfa;

import io.github.jemmix.tdfa.ast.CharClass;
import io.github.jemmix.tdfa.tnfa.Tnfa;

import java.util.*;

/**
 * Borsotti-Trofimovich 2022 TDFA(1): lookahead-TDFA with register indirection.
 *
 * Faithful implementation of paper Algorithm 3 (determinization):
 *   - epsilon_closure(B): DFS over ε-paths in priority order, recording tag sequences in l.
 *   - step_on_symbol(s, a): follows symbol transitions; old l becomes new h.
 *   - transition_regops: allocates one register per (tag, RHS) and emits SET_POS / SET_NIL.
 *   - add_state: dedupe by (NFA states, lookahead tags, register vectors). {@code map}+topo_sort
 *     is the paper's optimization for further state reduction; deferred.
 *   - final_regops: emits final-register SET/COPY ops for the accepting quasi-transition.
 *
 * Single-valued tags only (sufficient for j.u.r-style capturing groups).
 *
 * Alphabet: equivalence-class partitioned. Each DFA state stores sorted (lo, hi, target, ops)
 * ranges; runtime does binary search. Collapses 65K chars to a handful of ranges per state
 * (RE2-style byte-class partitioning).
 */
public final class Tdfa {
    final int tagCount;
    final int groupCount;
    /** Unmodifiable name&rarr;index map for named capturing groups (from the source pattern). */
    final java.util.Map<String, Integer> namedGroups;
    final int registerCount;
    /**
     * Offset of the final-register block within the runtime register file. Working
     * registers occupy {@code [0..finalRegBase-1]}; final registers (one per tag,
     * holding the match-end tag offsets read by {@link io.github.jemmix.tdfa.core.MatchResult})
     * occupy {@code [finalRegBase..finalRegBase+tagCount-1]}. Defaults to
     * {@code tagCount} (the pre-optimization layout); may be smaller after BT22 §6.3
     * register optimizations consolidate the working space.
     */
    final int finalRegBase;
    final int startState;
    final int stateCount;
    /**
     * Bit mask of zero-width assertions required to ENTER this state. Checked at the
     * position where the state is entered. Replaces the old pattern-level
     * {@code hasStartAnchor} flag — now per-state and precise.
     *   bit 1 = BEGIN_TEXT, bit 2 = END_TEXT, bit 4 = WORD_BOUNDARY, bit 8 = NO_WORD_BOUNDARY,
     *   bit 16 = ABS_BEGIN (\A), bit 32 = ABS_END (\z)
     */
    final int[] stateEntryMask;
    /**
     * Bit mask required to declare a match in this (accepting) state. Subset of
     * {@link #stateEntryMask}. Replaces the old pattern-level {@code hasEndAnchor} flag.
     */
    final int[] stateAcceptMask;
    /** Mask required to take the start state at all — used to limit find() start positions. */
    final int startStateEntryMask;

    /**
     * True iff this TDFA was compiled for leftmost-longest semantics
     * (re2j {@code LONGEST_MATCH}): the runner keeps stepping past accepts to
     * find the longest match. False means Perl leftmost-first — the runner
     * stops at the first accept on the highest-priority path.
     */
    final boolean longestMatch;
    final boolean multiline;
    /**
     * True iff the DFA was compiled with Unicode-aware shorthand ({@code (?u)}),
     * so {@code \b}/{@code \B} word-boundary checks must use the Unicode
     * word-character ranges in {@link #wordRanges} instead of ASCII-only.
     */
    final boolean unicodeWordBoundary;
    /** Unicode {@code \w} ranges for runtime {@code \b} when {@link #unicodeWordBoundary} is true; null otherwise. */
    final int[] wordRanges;
    /**
     * Fixed-tag annotations (BT22 §6.4), forwarded from {@link Tnfa}. Null if no
     * tags were fixed. Otherwise 1-indexed: {@code fixedBase[t] != 0} means tag
     * {@code t} was omitted from the NFA and should be reconstructed at match time.
     */
    final int[] fixedBase;
    final int[] fixedOffset;
    /**
     * Per-state fallback annotation (BT22 §6.2). {@code true} iff state is
     * final with at least one non-accepting path out of it AND its
     * {@code φ(S)} contains a clobbered COPY that needed backup ops. Such
     * states have a separate {@link #stateFallbackOpsOff} slot (ψ); the runner
     * chooses ψ vs φ based on whether transitions were taken since the last
     * accept. Length = {@link #stateCount}; all-false if M3 disabled or no
     * fallback states needed processing.
     */
    final boolean[] stateIsFallback;
    /** Per-state ψ (fallback quasi-transition) ops offset into {@link #ops}; 0 if none. */
    final int[] stateFallbackOpsOff;
    /**
     * Position-aware Perl-mode stop-on-accept decision table.
     * Indexed as {@code stopOnAcceptMask[state * 64 + posFlags]} where {@code posFlags}
     * is the runtime position-flags bitmask ({@code BEGIN_TEXT|END_TEXT|WORD_BOUNDARY|NO_WORD_BOUNDARY|ABS_BEGIN|ABS_END},
     * 6 bits, 64 possible values). Each cell encodes:
     * <ul>
     *   <li>{@code 0} — stop the match loop on accept (accept is the highest-priority
     *       live outcome under this posFlags);</li>
     *   <li>{@link #NEVER_STOP} — don't stop (a sym-bearing config outranks accept
     *       under this posFlags, or accept is unreachable).</li>
     * </ul>
     * Position-awareness is required because re2j's densePcs priority depends on
     * which assertion edges are live at the current cursor — e.g. for
     * {@code ^((?:$)|.)*} at pos 0 of "a", {@code $} fails so the {@code .}-branch
     * outranks the skip-exit MATCH (extend); at pos 1 (EOF), {@code $} holds and
     * the {@code $}-loop-back MATCH outranks {@code .} (stop).
     * Unused in POSIX mode (all cells stay {@link #NEVER_STOP}).
     *
     * <p><b>Storage tiers</b> (2026-08-20): patterns without zero-width
     * assertions — the overwhelming majority, incl. every count-model giant —
     * have all 64 cells of every state IDENTICAL, so the 2D table (256 B/state;
     * 60 MB on the 234 K-state bounded-repeat DFA) is stored instead as the
     * 1 B/state {@link #stopMaskUniform}. POSIX mode stores neither (readers
     * gate on Perl mode). {@link #stopOnAcceptMask()} materializes the full 2D
     * form on demand for external consumers (the ASM backend's STOP_MASK).
     */
    final int[] stopOnAcceptMask;
    /**
     * Uniform tier of the stop table: {@code stopMaskUniform[state] != 0} means
     * don't-stop (same encoding as {@link #NEVER_STOP} cells); 0 means stop.
     * Non-null iff Perl mode and every state's 64 posFlags cells are identical;
     * mutually exclusive with {@link #stopOnAcceptMask}.
     */
    final byte[] stopMaskUniform;
    /** Lazily-materialized 2D expansion of {@link #stopMaskUniform} (benign race). */
    private int[] stopMaskTableCache;
    /** Lazily-computed {@link #posFlagDeps()} (benign race; -1 = not computed). */
    private int posFlagDepsCache = -1;
    /** Sentinel for "don't stop on accept" — distinct from 0 (= stop). */
    public static final int NEVER_STOP = 0x40;  // sentinel bit above all real assertion bits (1|2|4|8|16|32)

    /**
     * Full 2D stop table for external consumers. Materializes (once) from the
     * uniform tier if needed; returns null in POSIX mode (no reader may call).
     */
    public int[] stopOnAcceptMask() {
        if (stopOnAcceptMask != null) return stopOnAcceptMask;
        byte[] u = stopMaskUniform;
        if (u == null) return null;
        int[] cache = stopMaskTableCache;
        if (cache == null) {
            cache = new int[u.length * 64];
            for (int s = 0; s < u.length; s++) {
                java.util.Arrays.fill(cache, s * 64, s * 64 + 64, u[s] != 0 ? NEVER_STOP : 0);
            }
            stopMaskTableCache = cache;
        }
        return cache;
    }

    /**
     * The position-flag bits the compiled DFA actually DISTINGUISHES — the
     * single derived source of truth for what a tier's positionFlags() must
     * compute. A bit is a dependency iff (a) it appears in some consumed mask
     * (entry, accept, range-required), or (b) flipping it changes any cell of
     * an M-indexed table (stop-on-accept, final-ops-by-mask). Uniform tiers
     * contribute nothing by construction.
     *
     * <p>This replaces the former per-tier re-derivations — the VM's
     * computeNeedsWordFlags scan and the ASM's pfNeeded model — which answered
     * the same semantic question with three different hand-written models. A
     * model that misses one table (or, as in the round-6 bug, one bit
     * combination) silently selects wrong table cells: the trim question
     * "does anything depend on bit b" is answered here GENERICALLY from the
     * tables themselves, so a new M-indexed consumer is covered the moment it
     * reads a table that distinguishes b — no model to keep in sync.
     */
    public int posFlagDeps() {
        int deps = posFlagDepsCache;
        if (deps >= 0) return deps;
        deps = 0;
        for (int m : stateEntryMask) deps |= m;
        for (int m : stateAcceptMask) deps |= m;
        for (int i = 4; i < ranges.length; i += 5) deps |= ranges[i];
        deps |= tableDeps(stopOnAcceptMask());
        deps |= tableDeps(stateFinalOpsByMask());
        posFlagDepsCache = deps;
        return deps;
    }

    /** Bits whose flip changes any cell of {@code t} ([state*64 + posFlags]); null-safe. */
    private static int tableDeps(int[] t) {
        if (t == null) return 0;
        int deps = 0;
        int n = t.length / 64;
        for (int b = 1; b < 64; b <<= 1) {
            if ((deps & b) != 0) continue;
            for (int s = 0; s < n; s++) {
                int row = s * 64;
                for (int m = 0; m < 64; m++) {
                    if ((m & b) != 0) continue;
                    if (t[row + m] != t[row + (m | b)]) { deps |= b; break; }
                }
                if ((deps & b) != 0) break;
            }
        }
        return deps;
    }

    // === Flat packed arrays (4 arrays total; per-match regs adds a 5th at runtime) ===
    /**
     * [state] -> packed (rangeCount << 1) | acceptBit.
     * One load per char gives accept + rangeCount; the range base is in
     * {@link #stateBase} (split out so it isn't bit-width-limited — see below).
     */
    final int[] stateMeta;
    /**
     * [state] -> range base index into {@link #ranges}. Stored separately from
     * {@link #stateMeta} so it can use the full 32-bit range — the old packing
     * (base in bits 17-31 of stateMeta, 15 bits) overflowed at ~25 states for
     * wide Unicode classes like {@code \p{L}} (~1369 ranges per state).
     */
    final int[] stateBase;
    /** [state] -> finalOpsOff (offset into `ops`), 0 if none. Read only once per match. */
    final int[] stateFinalOpsOff;
    /**
     * Position-aware final-ops selection: {@code [state * 64 + posFlags]} →
     * φ ops offset into {@link #ops}, or {@code -1} when NO accept config is
     * alive under that posFlags (accept suppressed). Null when every
     * accepting state is mask-uniform ({@link #stateFinalOpsOff} alone is
     * then authoritative — the overwhelmingly common case).
     *
     * <p>Why: a DFA state may merge several accept configs of different
     * priority whose zero-width assertions differ. The highest-priority
     * accept config is the tag-value winner only while its assertions hold;
     * when the runtime position-flags kill it, priority falls to the next
     * alive accept config, whose tag outcome may differ (e.g. the skipped
     * branch of {@code (…)?} reports the group unset). A compile-time-static
     * φ picks the wrong winner for some positions; this table selects per
     * position, exactly as {@link #stopOnAcceptMask} does for the
     * stop-or-extend decision.
     */
    final int[] stateFinalOpsByMask;
    /**
     * Flat ranges: [lo0, hi0, target0, opsOff0, requiredMask0,
     *               lo1, hi1, target1, opsOff1, requiredMask1, ...].
     * {@code requiredMask} is the assertion mask that must hold at the source position
     * for this transition to be live (intersection of source configs' masks).
     */
    final int[] ranges;
    /** Per-entry running max of hi within each state, index-aligned with ranges
     *  entries (entry i of state s at stateBase[s]+i). Enables lo-binary-search +
     *  prefix-max-terminated backward walk — O(log cnt + overlap) range lookup. */
    final int[] entryHiPrefix;
    /** Flat ops: [op, dst, src, ...] blocks terminated by OP_END=0. Transition ops + final ops share this array. */
    final int[] ops;

    public static final int OP_SET_POS = 1;
    public static final int OP_SET_NIL = 2;
    public static final int OP_COPY    = 3;
    public static final int OP_END     = 0;  // terminator for op blocks

    Tdfa(int tagCount, int groupCount, java.util.Map<String, Integer> namedGroups, int registerCount, int finalRegBase, int startState, int stateCount,
                 int[] stateMeta, int[] stateBase, int[] stateFinalOpsOff, int[] stateFinalOpsByMask, int[] ranges, int[] ops,
                 int[] entryHiPrefix,
                 int[] stateEntryMask, int[] stateAcceptMask, boolean longestMatch, int[] stopOnAcceptMask, byte[] stopMaskUniform, boolean multiline,
                 boolean unicodeWordBoundary, int[] wordRanges, int[] fixedBase, int[] fixedOffset,
                 boolean[] stateIsFallback, int[] stateFallbackOpsOff) {
        this.tagCount = tagCount; this.groupCount = groupCount;
        this.namedGroups = namedGroups != null ? java.util.Collections.unmodifiableMap(namedGroups) : java.util.Collections.emptyMap();
        this.registerCount = registerCount;
        this.finalRegBase = finalRegBase;
        this.startState = startState;
        this.stateCount = stateCount;
        this.stateMeta = stateMeta;
        this.stateBase = stateBase;
        this.stateFinalOpsOff = stateFinalOpsOff;
        this.stateFinalOpsByMask = stateFinalOpsByMask;
        this.ranges = ranges;
        this.entryHiPrefix = entryHiPrefix;
        this.ops = ops;
        this.stateEntryMask = stateEntryMask;
        this.stateAcceptMask = stateAcceptMask;
        this.startStateEntryMask = stateEntryMask[startState];
        this.longestMatch = longestMatch;
        this.stopOnAcceptMask = stopOnAcceptMask;
        this.stopMaskUniform = stopMaskUniform;
        this.multiline = multiline;
        this.unicodeWordBoundary = unicodeWordBoundary;
        this.wordRanges = wordRanges;
        this.fixedBase = fixedBase;
        this.fixedOffset = fixedOffset;
        this.stateIsFallback = stateIsFallback;
        this.stateFallbackOpsOff = stateFallbackOpsOff;
        // Well-formedness gate: every consumer (VM runner, search-DFA memo,
        // ASM emitter, minimizer) trusts these arrays. Violations must surface
        // here, at construction — not as a wrong match 2,000 lines away.
        validate(stateCount, stateMeta, stateBase, stateFinalOpsOff, stateFinalOpsByMask, ranges, entryHiPrefix, ops,
                stateEntryMask, stateAcceptMask, registerCount, finalRegBase, tagCount);
    }

    /**
     * Construction-time invariants of the packed flat arrays. O(states +
     * entries + ops) once per compile; never on the match path.
     *
     * <ul>
     *   <li>per-state range entries: in bounds, codepoint domain, lo ascending
     *       (the runners' binary search depends on it), prefix-max consistent</li>
     *   <li>transition targets within the state space; ops offsets within ops</li>
     *   <li>assertion masks limited to the six defined bits; accept ⊆ entry</li>
     *   <li>final-register block {@code [finalRegBase, finalRegBase+tagCount)}
     *       fits the register file (dedicated final slots — coalescing finals
     *       with working registers corrupts the MatchResult readout)</li>
     * </ul>
     */
    private static void validate(int stateCount, int[] stateMeta, int[] stateBase, int[] stateFinalOpsOff,
                                 int[] stateFinalOpsByMask,
                                 int[] ranges, int[] entryHiPrefix, int[] ops,
                                 int[] stateEntryMask, int[] stateAcceptMask,
                                 int registerCount, int finalRegBase, int tagCount) {
        int entries = ranges.length / 5;
        if (stateAcceptMask.length != stateCount)
            throw new IllegalStateException("tdfa: stateAcceptMask length " + stateAcceptMask.length
                    + " != stateCount " + stateCount);
        for (int s = 0; s < stateCount; s++) {
            int meta = stateMeta[s];
            int cnt = rangeCount(meta);
            int base = stateBase[s];
            if (base < 0 || base + cnt > entries)
                throw new IllegalStateException("tdfa: state " + s + " range base/count out of bounds"
                        + " (base=" + base + ", cnt=" + cnt + ", entries=" + entries + ")");
            int prevLo = -1;
            int prefixHi = -1;
            for (int i = 0; i < cnt; i++) {
                int o = (base + i) * 5;
                int lo = ranges[o], hi = ranges[o + 1], target = ranges[o + 2], opsOff = ranges[o + 3], mask = ranges[o + 4];
                if (lo < 0 || hi > 0x10FFFF || lo > hi)
                    throw new IllegalStateException("tdfa: state " + s + " entry " + i
                            + " outside codepoint domain [" + lo + "," + hi + "]");
                if (lo < prevLo)
                    throw new IllegalStateException("tdfa: state " + s + " entries not lo-ascending at " + i);
                prevLo = lo;
                if (target >= stateCount)
                    throw new IllegalStateException("tdfa: state " + s + " entry " + i
                            + " target " + target + " beyond state count " + stateCount);
                if (opsOff != 0 && (opsOff < 0 || opsOff >= ops.length))
                    throw new IllegalStateException("tdfa: state " + s + " entry " + i + " ops offset out of bounds");
                if (opsOff != 0 && tagCount > 0) {
                    // Finals are final-ops-only: transition ops writing the
                    // final block would let dead paths clobber accept-time
                    // values (runners apply φ eagerly at accept-record).
                    for (int j = opsOff; ops[j] != OP_END; j += 3) {
                        int dst = ops[j + 1];
                        if (dst >= finalRegBase && dst < finalRegBase + tagCount)
                            throw new IllegalStateException("tdfa: state " + s + " entry " + i
                                    + " transition op writes final register " + dst
                                    + " — final block is final-ops-only");
                    }
                }
                if ((mask & ~0x3F) != 0)
                    throw new IllegalStateException("tdfa: state " + s + " entry " + i + " unknown assertion-mask bits");
                prefixHi = Math.max(prefixHi, hi);
                if (entryHiPrefix[base + i] != prefixHi)
                    throw new IllegalStateException("tdfa: state " + s + " entry " + i + " prefix-max invariant broken");
            }
            if ((stateEntryMask[s] & ~0x3F) != 0)
                throw new IllegalStateException("tdfa: state " + s + " entry mask has unknown bits");
            int fops = stateFinalOpsOff[s];
            if (fops != 0 && (fops < 0 || fops >= ops.length))
                throw new IllegalStateException("tdfa: state " + s + " final-ops offset out of bounds");
        }
        if (stateFinalOpsByMask != null) {
            if (stateFinalOpsByMask.length != stateCount * 64)
                throw new IllegalStateException("tdfa: final-ops-by-mask table must be stateCount*64");
            for (int i = 0; i < stateFinalOpsByMask.length; i++) {
                int cell = stateFinalOpsByMask[i];
                // -1 = no accept under that posFlags; otherwise an ops offset
                // (0 = the reserved empty block: accept fires, no ops).
                if (cell < -1 || cell >= ops.length)
                    throw new IllegalStateException("tdfa: final-ops-by-mask cell out of bounds: " + cell);
            }
        }
        if (tagCount > 0 && (finalRegBase < 0 || finalRegBase + tagCount > registerCount))
            throw new IllegalStateException("tdfa: final-register block [" + finalRegBase
                    + "," + (finalRegBase + tagCount) + ") exceeds register file of " + registerCount);
    }

    public boolean isAccept(int state) { return (stateMeta[state] & 1) != 0; }
    public int finalOpsOffset(int state) { return stateFinalOpsOff[state]; }

    /** Position-aware final-ops table ({@code [state*64+posFlags]} → offset, -1 = accept
     *  suppressed), or null when every accepting state is mask-uniform. */
    public int[] stateFinalOpsByMask() { return stateFinalOpsByMask; }
    /** Range base index into {@link #ranges} for the given state. */
    public int rangeBase(int state) { return stateBase[state]; }
    /** Unpack range count from packed stateMeta. */
    public static int rangeCount(int meta) { return (meta >>> 1) & 0xFFFF; }
    /** Accept bit. */
    public static boolean accept(int meta) { return (meta & 1) != 0; }

    /** True if start state's entry mask requires {@link Tnfa#BEGIN_TEXT} (limits find() to pos 0). */
    public boolean startRequiresBeginText() { return (startStateEntryMask & Tnfa.BEGIN_TEXT) != 0; }


    // ===== public read accessors (fields are package-private; asm generation
    // and external consumers read through these) =====

    /** Number of capture tags (2 per group, 1-indexed). */
    public int tagCount() { return tagCount; }

    /** Number of capturing groups (excluding group 0). */
    public int groupCount() { return groupCount; }

    /** Unmodifiable name&rarr;index map for named capturing groups. */
    public java.util.Map<String, Integer> namedGroups() { return namedGroups; }

    /** Total register count (working + final blocks). */
    public int registerCount() { return registerCount; }

    /** Offset of the final-register block within the runtime register file. */
    public int finalRegBase() { return finalRegBase; }

    /** Start state id. */
    public int startState() { return startState; }

    /** Number of DFA states. */
    public int stateCount() { return stateCount; }

    /** Per-state packed metadata: accept bit + range count (see {@link #accept}, {@link #rangeCount}). */
    public int[] stateMeta() { return stateMeta; }

    /** Per-state base index into {@link #ranges()}. */
    public int[] stateBase() { return stateBase; }

    /** Per-state final-ops offset into {@link #ops()}, 0 if none. */
    public int[] stateFinalOpsOff() { return stateFinalOpsOff; }

    /** Flat transition ranges: [lo, hi, target, opsOff, requiredMask] quintets. */
    public int[] ranges() { return ranges; }

    /** Per-entry prefix-max of hi within each state, index-aligned with {@link #ranges()}. */
    public int[] entryHiPrefix() { return entryHiPrefix; }

    /** Flat register ops: [op, dst, src] triplets, blocks terminated by {@link #OP_END}. */
    public int[] ops() { return ops; }

    /** Per-state entry assertion masks (BEGIN_TEXT/END_TEXT/WORD_BOUNDARY/...). */
    public int[] stateEntryMask() { return stateEntryMask; }

    /** Per-state accept assertion masks (subset of {@link #stateEntryMask()}). */
    public int[] stateAcceptMask() { return stateAcceptMask; }

    /** Mask required to take the start state (limits find() start positions). */
    public int startStateEntryMask() { return startStateEntryMask; }

    /** True iff compiled for leftmost-longest (LONGEST_MATCH) semantics. */
    public boolean longestMatch() { return longestMatch; }

    /** {@code (?m)} — {@code ^}/{@code $} at line boundaries. */
    public boolean multiline() { return multiline; }

    /** Unicode-aware word boundary ({@code (?u)}). */
    public boolean unicodeWordBoundary() { return unicodeWordBoundary; }

    /** Word-character ranges for Unicode-aware {@code \b}, or {@code null}. */
    public int[] wordRanges() { return wordRanges; }

    /** Fixed-tag base annotations (BT22 §6.4), or {@code null} when none fixed. */
    public int[] fixedBase() { return fixedBase; }

    /** Fixed-tag offset annotations (BT22 §6.4). */
    public int[] fixedOffset() { return fixedOffset; }

    /** Per-state fallback classification (BT22 §6.2). */
    public boolean[] stateIsFallback() { return stateIsFallback; }

    /** Per-state fallback-ops offset into {@link #ops()}, 0 if none. */
    public int[] stateFallbackOpsOff() { return stateFallbackOpsOff; }

    /** Compile with Perl leftmost-first semantics (the ecosystem default). */
    public static Tdfa compile(Tnfa nfa) { return compile(nfa, false); }

    /** Compiles a TNFA to a TDFA.
     *  @param longestMatch true for leftmost-longest, false for leftmost-first. */
    public static Tdfa compile(Tnfa nfa, boolean longestMatch) {
        return new TdfaCompiler(nfa, longestMatch).compile();
    }

    /** Compile with a transparency hook receiving stage timings/decisions (may be {@code null}). */
    public static Tdfa compile(Tnfa nfa, boolean longestMatch,
                               io.github.jemmix.tdfa.core.CompileObserver observer) {
        return new TdfaCompiler(nfa, longestMatch).compile(observer);
    }

    /** Toggle post-determinization minimization (Moore's algorithm). Default on; disable with -Dtdfa.nominimize. */
    static final boolean MINIMIZE_ENABLED = !Boolean.getBoolean("tdfa.nominimize");
    /**
     * Skip minimization for DFAs above this state count. Moore's algorithm is O(n²) worst-case
     * and provides no benefit when the DFA is already minimal (which subset construction with
     * construction-time {@code map} deduping tends to produce). For pathological cases like
     * dictionary alternations, skipping saves ~30s of pure overhead. Override with -Dtdfa.minimize.max=N.
     */
    static final int MINIMIZE_MAX_STATES = Integer.getInteger("tdfa.minimize.max", 20000);
    /**
     * Toggle BT22 §6.3 register optimizations on the post-determinization CFG. Default on;
     * disable with {@code -Dtdfa.noregopt=true}. Currently runs Stage 1 (compaction) only;
     * Stages 2-4 (liveness, DCE, interference, allocation, normalization) land incrementally.
     */
    static final boolean REGOPT_ENABLED = !Boolean.getBoolean("tdfa.noregopt");
    /**
     * Skip CFG-based register optimizations for DFAs above this state count. The full
     * §6.3 pipeline (compaction + 2× (liveness + DCE + interference + allocation +
     * normalization)) has O(n² · ops-per-block) cost on the interference matrix and
     * copy-coalescing passes. The benefit on huge DFAs is small (most have 0 tags
     * anyway), so skip above the cap. Override with {@code -Dtdfa.regopt.max=N}.
     */
    static final int REGOPT_MAX_STATES = Integer.getInteger("tdfa.regopt.max", 2000);
    /**
     * Toggle BT22 §6.2 fallback operations (backup COPYs on transitions out of
     * fallback states, ψ quasi-transitions). Default on; disable with
     * {@code -Dtdfa.nofallback=true}.
     */
    static final boolean FALLBACK_ENABLED = !Boolean.getBoolean("tdfa.nofallback");
    static final boolean DEBUG = Boolean.getBoolean("tdfa.debug");

}