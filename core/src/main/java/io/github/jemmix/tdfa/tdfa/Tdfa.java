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

    private Tdfa(int tagCount, int groupCount, java.util.Map<String, Integer> namedGroups, int registerCount, int finalRegBase, int startState, int stateCount,
                 int[] stateMeta, int[] stateBase, int[] stateFinalOpsOff, int[] stateFinalOpsByMask, int[] ranges, int[] ops,
                 int[] entryHiPrefix,
                 int[] stateEntryMask, int[] stateAcceptMask, boolean longestMatch, int[] stopOnAcceptMask, byte[] stopMaskUniform, boolean multiline,
                 boolean unicodeWordBoundary, int[] wordRanges, int[] fixedBase, int[] fixedOffset,
                 boolean[] stateIsFallback, int[] stateFallbackOpsOff) {
        this.tagCount = tagCount; this.groupCount = groupCount;
        this.namedGroups = namedGroups != null ? java.util.Collections.unmodifiableMap(namedGroups) : java.util.Map.of();
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

    /** Leftmost-first stop-on-accept decision table ([state*64 + posFlags]), longest-match mode only. */

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

    /** @param longestMatch true for leftmost-longest, false for leftmost-first. */
    public static Tdfa compile(Tnfa nfa, boolean longestMatch) {
        return new Compiler(nfa, longestMatch).compile();
    }

    /** Compile with a transparency hook receiving stage timings/decisions (may be {@code null}). */
    public static Tdfa compile(Tnfa nfa, boolean longestMatch,
                               io.github.jemmix.tdfa.core.CompileObserver observer) {
        return new Compiler(nfa, longestMatch).compile(observer);
    }

    /** Toggle post-determinization minimization (Moore's algorithm). Default on; disable with -Dtdfa.nominimize. */
    private static final boolean MINIMIZE_ENABLED = !Boolean.getBoolean("tdfa.nominimize");
    /**
     * Skip minimization for DFAs above this state count. Moore's algorithm is O(n²) worst-case
     * and provides no benefit when the DFA is already minimal (which subset construction with
     * construction-time {@code map} deduping tends to produce). For pathological cases like
     * dictionary alternations, skipping saves ~30s of pure overhead. Override with -Dtdfa.minimize.max=N.
     */
    private static final int MINIMIZE_MAX_STATES = Integer.getInteger("tdfa.minimize.max", 20000);
    /**
     * Toggle BT22 §6.3 register optimizations on the post-determinization CFG. Default on;
     * disable with {@code -Dtdfa.noregopt=true}. Currently runs Stage 1 (compaction) only;
     * Stages 2-4 (liveness, DCE, interference, allocation, normalization) land incrementally.
     */
    private static final boolean REGOPT_ENABLED = !Boolean.getBoolean("tdfa.noregopt");
    /**
     * Skip CFG-based register optimizations for DFAs above this state count. The full
     * §6.3 pipeline (compaction + 2× (liveness + DCE + interference + allocation +
     * normalization)) has O(n² · ops-per-block) cost on the interference matrix and
     * copy-coalescing passes. The benefit on huge DFAs is small (most have 0 tags
     * anyway), so skip above the cap. Override with {@code -Dtdfa.regopt.max=N}.
     */
    private static final int REGOPT_MAX_STATES = Integer.getInteger("tdfa.regopt.max", 2000);
    /**
     * Toggle BT22 §6.2 fallback operations (backup COPYs on transitions out of
     * fallback states, ψ quasi-transitions). Default on; disable with
     * {@code -Dtdfa.nofallback=true}.
     */
    private static final boolean FALLBACK_ENABLED = !Boolean.getBoolean("tdfa.nofallback");
    static final boolean DEBUG = Boolean.getBoolean("tdfa.debug");

    private static final class Compiler {
        /** Hash-consed tag-history table backing Config.h/.l ids. */
        final HistTable hist = new HistTable();
        final Tnfa nfa;
        final int tags;
        /** Compile work budget: every unbounded loop ticks it (fuzzer-found
         *  nested-quantifier bombs churn fixpoints without growing output —
         *  the state/kernel caps never trip). */
        final WorkMeter meter = new WorkMeter(Long.getLong("tdfa.max.work", 1L << 32));
        int[][] epsOut;
        int[][] symOut;
        /** Per-state popped-mask bitsets for the closure's subsumption cut (see epsilonClosure). */
        long[] maskBitset;
        int[] maskEpoch;
        int epochCtr;
        final int[] initialRegisters;
        final int[] finalRegisters;
        /** Equivalence-class breakpoints across the BMP. */
        final int[] breakpoints;
        /** If true, leftmost-longest semantics (keep stepping past accepts); if false, Perl leftmost-first (suppress lower-priority paths past an accept). */
        final boolean longest;

        /** Master switch for the dormant BT19 §7 prectable machinery (see compile()). */
        private static final boolean COMPUTE_PRECTABLES = false;

        /**
         * Multimap from DFA-state shape key to the list of DFA-state IDs that
         * share that shape. The paper's {@code map}+{@code topological_sort}
         * dedup collapses states with identical (NFA-state-set, lookahead-tag,
         * emptyMask, pri) signature; register-renaming via {@link #tryMap}
         * handles the case where the same shape is reached with different
         * register assignments.
         *
         * <p>Storing ALL same-shape state IDs (not just the first one) keeps
         * {@link #addState} expected-O(1) per call. With a single-entry map
         * (the prior design) addState had to fall back to an O(n²) scan over
         * all known states whenever the hash-bucket primary candidate failed
         * tryMap — or, worse, whenever the shape was brand-new (hash-miss),
         * because the fallback couldn't tell there was nothing to find. On
         * the 2 663-branch dictionary alternation that fallback fired
         * 227 M times (every call, never matching) and dominated compile
         * wall time (~13 s of ~14 s).
         */
        Map<DfaStateKey, StateBucket> stateIndex = new HashMap<>();
        List<List<Config>> states = new ArrayList<>();
        /**
         * Tagless compiles only: after a state is processed, its closure is
         * packed here as arrival-ordered (state, emptyMask) pairs (2 ints per
         * config) and the boxed {@link #states} slot is nulled. The retained
         * boxed form cost ~48 B × configs (3.18 GB on the 234 K-state bomb);
         * the packed form is 8 B/config. Consumers that read a state's closure
         * after processing (tryMap order check, entry/accept masks,
         * stopOnAccept) branch on which form is present. Tagged compiles never
         * pack — regopt/fallback/POSIX machinery consumes the boxed closures.
         */
        List<int[]> packedKernels = new ArrayList<>();
            /**
         * Seed configs (pre-closure) for each DFA state, used to compute per-state
         * DFS order (stopOnAccept). Stored ONLY for accepting Perl-mode states:
         * as {@code int[]} of states (arrival order) on tagless compiles, as
         * {@code List<Config>} otherwise. Null for the rest.
         */
        List<Object> stateSeeds = new ArrayList<>();
        BitSet accept = new BitSet();
        BitSet processed = new BitSet();
        List<DfaStateBuilder> builders = new ArrayList<>();
        Deque<Integer> work = new ArrayDeque<>();
        /** Global register allocator counter; bumped monotonically across all states. */
        int nextReg;

        /**
         * Determinization budget — re2c's design verbatim (its src/dfa/determinization.cc:
         * "Abort if TDFA grows too fast (either in the number of states, or in the total
         * size of all state kernels which may have many TNFA substates)"; constants.h:
         * {@code MAX_DFA_STATES = 100*1000}, {@code MAX_DFA_SIZE = 50*1000*1000}). A
         * pattern whose DFA exceeds the budget fails compilation with a clean
         * "pattern too large" error instead of burning unbounded time/heap (the
         * reference implementation rejects e.g. two-site {@code [^]{0,16}x[^]{0,16}}
         * outright; ours determinizes that family compactly but still caps the
         * intrinsically-huge cross-products like rebar's
         * {@code [\s\S]{0,100}Result[\s\S]{0,100}} — a 200 K+-state minimal DFA).
         * Read per-compile (not cached) so tests can override; defaults leave the
         * largest legit in-corpus pattern (dictionary, 19.6 K pre-min states) 5x headroom.
         */
        final int maxStates = Integer.getInteger("tdfa.max.states", 100_000);
        /** Memory-bound: each kernel config is a live Config (~80 B boxed all-
         *  in: lists, intern table, builders). Measured: 6.4 M kernels peaks
         *  under 1 GB, 18 M exceeds it. 10 M keeps every measured legit shape
         *  (e.g. (a{1,50}){1,50} at 6.4 M) and clean-rejects nested-counted
         *  bombs on default heaps instead of OOM-ing. Raise via
         *  -Dtdfa.max.kernels (with heap) for heavier legitimate use. */
        final int maxKernelsTotal = Integer.getInteger("tdfa.max.kernels", 10_000_000);
        /** Per-kernel spike bound — the totals cap only counts AFTER addState,
         *  so one closure of a nested-counted bomb could exhaust the heap on
         *  its own. Checked while the closure is built. */
        final int maxClosure = Integer.getInteger("tdfa.max.closure", 100_000);
        /** Running sum of closure (kernel) sizes — re2c's kernels_total. */
        long kernelsTotal = 0;

        Compiler(Tnfa nfa) { this(nfa, false); }

        Compiler(Tnfa nfa, boolean longestMatch) {
            this.nfa = nfa;
            this.tags = nfa.tagCount;
            this.epsOut = sortedOutgoing(nfa.epsFrom, nfa.epsPri);
            this.symOut = plainOutgoing(nfa.symFrom);
            this.maskBitset = new long[nfa.stateCount];
            this.maskEpoch = new int[nfa.stateCount];
            this.initialRegisters = new int[tags];
            this.finalRegisters = new int[tags];
            for (int t = 0; t < tags; t++) initialRegisters[t] = t;
            for (int t = 0; t < tags; t++) finalRegisters[t] = tags + t;
            this.breakpoints = computeBreakpoints();
            this.longest = longestMatch;
            // Tag heights: group g → tags 2g-1, 2g → height g.
            // Index 0 unused (tags are 1-based). ntags (-tag) use height of |tag|.
            this.tagHeights = new int[tags + 1];
            for (int t = 1; t <= tags; t++) {
                tagHeights[t] = (t + 1) / 2;
            }
            // Per-cell active symbol-edge sets (see rangeActiveEdges). Each class range
            // [lo, hi] covers a contiguous run of breakpoint cells: lo and hi+1 are
            // themselves breakpoints (they are boundaries of this very class), so the
            // run is exactly [bpIdx(lo), bpIdx(hi+1)-1] — hence one cc.matches probe
            // per cell representative suffices here. Identical sets are interned to a
            // shared id (activeSetId) so the determinize sweep can cache results per
            // distinct set instead of per adjacent cell: the sets interleave along the
            // codepoint line (letter / space / other cells), so adjacency-only reuse
            // would never fire.
            int cells = breakpoints.length - 1;
            int edgeCount = nfa.symClass.length;
            int words = (edgeCount + 63) >> 6;
            this.rangeActiveEdges = new long[cells][];
            this.rangeSameEdges = new boolean[cells];
            this.activeSetId = new int[cells];
            this.cellCount = cells;
            long[] prevBits = null;
            java.util.ArrayList<long[]> distinctSets = new java.util.ArrayList<>();
            for (int bi = 0; bi < cells; bi++) {
                long[] bits = new long[words];
                for (int idx = 0; idx < edgeCount; idx++) {
                    CharClass cc = nfa.symClass[idx];
                    if (cc != null && cc.matches(breakpoints[bi])) bits[idx >> 6] |= 1L << (idx & 63);
                }
                rangeActiveEdges[bi] = bits;
                if (bi > 0) rangeSameEdges[bi] = java.util.Arrays.equals(bits, prevBits);
                int id = -1;
                for (int d = 0; d < distinctSets.size(); d++) {
                    if (java.util.Arrays.equals(bits, distinctSets.get(d))) { id = d; break; }
                }
                if (id < 0) { id = distinctSets.size(); distinctSets.add(bits); }
                activeSetId[bi] = id;
                prevBits = bits;
            }
            this.activeSetCount = distinctSets.size();
        }

        /** Number of breakpoint cells (cells = equivalence classes between adjacent breakpoints). */
        final int cellCount;
        /** Per breakpoint cell: bitset of active symbol-edge ids (edges whose class matches the cell's representative). */
        long[][] rangeActiveEdges;
        /** rangeSameEdges[bi] == true iff cell bi's active edge set equals cell bi-1's. */
        final boolean[] rangeSameEdges;
        /** Interned id of cell bi's active edge set (equal sets share the id). */
        int[] activeSetId;
        /** Number of distinct active edge sets. */
        final int activeSetCount;

        int[][] sortedOutgoing(int[] fromArr, int[] pri) {
            int[][] out = plainOutgoing(fromArr);
            for (int[] arr : out) {
                for (int a = 1; a < arr.length; a++) {
                    int key = arr[a]; int kp = pri[key]; int b = a - 1;
                    while (b >= 0 && pri[arr[b]] > kp) { arr[b + 1] = arr[b]; b--; }
                    arr[b + 1] = key;
                }
            }
            return out;
        }

        int[][] plainOutgoing(int[] fromArr) {
            int n = nfa.stateCount;
            int[] counts = new int[n];
            for (int f : fromArr) counts[f]++;
            int[][] out = new int[n][];
            for (int s = 0; s < n; s++) out[s] = new int[counts[s]];
            int[] idx = new int[n];
            for (int i = 0; i < fromArr.length; i++) out[fromArr[i]][idx[fromArr[i]]++] = i;
            return out;
        }

        /** Compute breakpoints: every codepoint where some NFA CharClass boundary occurs. */
        int[] computeBreakpoints() {
            TreeSet<Integer> bps = new TreeSet<>();
            bps.add(0);
            bps.add(0x110000); // sentinel upper bound (exclusive)
            for (CharClass cc : nfa.symClass) {
                if (cc == null) continue;
                for (int r = 0; r < cc.ranges.length; r += 2) {
                    int lo = cc.ranges[r], hi = cc.ranges[r + 1];
                    bps.add(lo);
                    int after = hi + 1;
                    if (after <= 0x10FFFF) bps.add(after);
                }
            }
            int[] arr = new int[bps.size()];
            int i = 0;
            for (int b : bps) arr[i++] = b;
            return arr;
        }

        Tdfa compile() { return compile(null); }

        Tdfa compile(io.github.jemmix.tdfa.core.CompileObserver observer) {
            final io.github.jemmix.tdfa.core.CompileObserver obs =
                    observer != null ? observer : io.github.jemmix.tdfa.core.CompileObserver.NONE;
            long tDet = System.nanoTime();
            nextReg = 2 * tags;
            if (debug) System.err.println("[tdfa] tags=" + tags + " breakpoints=" + breakpoints.length);
            List<Config> initSeed = List.of(
                    new Config(nfa.start, initialRegisters, HistTable.EMPTY_ID, HistTable.EMPTY_ID, 0));
            List<Config> initClosure = longest
                    ? closureGtop(initSeed, null, 0)
                    : epsilonClosure(initSeed);
            int startId = addState(initClosure, null, initSeed).targetId;
            // Prectables are O(closure²) per state and currently UNUSED: closureGtop
            // delegates to epsilonClosure (heuristic DFS order) and compareExisting
            // is a TEMP no-op — the BT19 §7 closure_gtop activation (TODO "Feature
            // parity") is what will consume them. Skipping saves real compile time on
            // longest-match DFAs; flip COMPUTE_PRECTABLES on when activating.
            if (longest && COMPUTE_PRECTABLES) statePrectables.add(computePrectable(initClosure, null, 0));
            else statePrectables.add(null);
            work.push(startId);

            int[] requiredMaskOut = new int[1];
            while (!work.isEmpty()) {
                meter.tick();
                int sid = work.pop();
                if (processed.get(sid)) continue;
                processed.set(sid);
                List<Config> cur = states.get(sid);
                if (debug) {
                    System.err.println("[tdfa] processing state " + sid + " configs:");
                    for (Config c : cur) System.err.println("    state=" + c.state + " l=" + Arrays.toString(hist.content(c.l)) + " regs=" + Arrays.toString(c.regs) + " mask=" + c.emptyMask);
                }
                // Assertion-context split (assertions into the alphabet, by
                // construction). The runtime posFlags M decides which closure
                // configs are alive (emptyMask ⊆ M). We step per DISTINCT
                // live-set — the closure filtered to alive configs, kept in
                // true closure-priority order — so every target state is both
                // liveness-complete (no continuation silently dropped) and
                // priority-correct (the kernel order feeds the stop-on-accept
                // table and final-ops variants). The context's OR-mask rides
                // its ranges; contexts are emitted most-specific first and the
                // runner's lowest-index-first scan among mask-satisfied
                // entries resolves overlaps.
                //
                // This replaces the former own/subset mask-group split whose
                // targets were lopsided: subset-appended configs landed AFTER
                // the own group in the kernel (priority inversion — the greedy
                // class-continue lost to a lower-priority \b\W exit and the
                // stop table stopped early) while less-specific groups dropped
                // gated continuations (the a*(^a) band-aid that the mask
                // specificity sort papered over at runtime).
                List<Integer> ctxMasks = null;   // distinct nonzero masks when >1 relevant
                for (Config c : cur) {
                    if (c.emptyMask != 0) {
                        if (ctxMasks == null) ctxMasks = new ArrayList<>(4);
                        boolean found = false;
                        for (int m : ctxMasks) if (m == c.emptyMask) { found = true; break; }
                        if (!found) ctxMasks.add(c.emptyMask);
                    }
                }
                List<int[]> ctxList = new ArrayList<>(4);      // {orMask, coverage} per context
                List<List<Config>> ctxInputs = new ArrayList<>(4);
                if (ctxMasks == null || ctxMasks.isEmpty()
                        || (ctxMasks.size() == 1 && ctxMasks.contains(0))) {
                    ctxList.add(new int[]{0, 0});
                    ctxInputs.add(pruneBelowAccept(cur));      // uniform context: whole closure (pike-pruned)
                } else {
                    // Dedup live-sets by their alive-mask pattern over the 64 runtime
                    // M values. Mask-0 configs are alive under every M (their bit is
                    // folded into the pattern directly); a pattern with no configs at
                    // all is unreachable and skipped.
                    int k = ctxMasks.size();
                    boolean anyZero = false;
                    for (Config c : cur) if (c.emptyMask == 0) { anyZero = true; break; }
                    java.util.HashMap<Integer, Integer> patIdx = new java.util.HashMap<>(8);
                    for (int M = 0; M < 64; M++) {
                        int pat = anyZero ? 1 : 0, r = 0;
                        for (int i = 0; i < k; i++) {
                            int mi = ctxMasks.get(i);
                            if ((mi & ~M) == 0) { pat |= 2 << i; r |= mi; }
                        }
                        if (pat == 0 || patIdx.containsKey(pat)) continue;
                        patIdx.put(pat, ctxInputs.size());
                        List<Config> live = new ArrayList<>(cur.size());
                        for (Config c : cur) {
                            if (c.emptyMask == 0) { if (anyZero) live.add(c); continue; }
                            for (int i = 0; i < k; i++) {
                                if ((pat & (2 << i)) != 0 && c.emptyMask == ctxMasks.get(i)) { live.add(c); break; }
                            }
                        }
                        pruneBelowAcceptInPlace(live);
                        ctxList.add(new int[]{r, Integer.bitCount(pat)});
                        ctxInputs.add(live);
                    }
                    // Emit most coverage first (superset live-sets precede their
                    // subsets; incomparable patterns have disjoint M sets).
                    Integer[] order = new Integer[ctxList.size()];
                    for (int i = 0; i < order.length; i++) order[i] = i;
                    final List<int[]> cl = ctxList;
                    java.util.Arrays.sort(order, (x, y) -> Integer.compare(cl.get(y)[1], cl.get(x)[1]));
                    List<int[]> sortedCtx = new ArrayList<>(order.length);
                    List<List<Config>> sortedIn = new ArrayList<>(order.length);
                    for (int o : order) { sortedCtx.add(ctxList.get(o)); sortedIn.add(ctxInputs.get(o)); }
                    ctxList = sortedCtx; ctxInputs = sortedIn;
                }
                // Context-major, range-inner sweep with per-active-set result caching.
                // The stepped configs are a pure function of (stepInput, active edge
                // set), and the active edge set is a pure function of the breakpoint
                // cell — so cells sharing an interned active-set id (activeSetId[bi])
                // yield identical stepped lists, ε-closures, shape keys and addState
                // results. The expensive closure/key/addState pipeline runs at most
                // once per distinct active set per context, and the builder's
                // coalesce() later merges the same-target ranges. On wide-class
                // patterns ([\s\S]{0,100} etc.) this skips the large majority of
                // per-cell work; on narrow patterns every cell is distinct and the
                // cache degenerates to one entry per cell.
                // Context results per active set: res[ctx][set] = target state id,
                // -1 = stepped empty, 0 = not yet computed. Contexts run most-
                // specific first; live ranges emit immediately. A context that
                // steps EMPTY emits a DEAD marker (target -1, its ctxMask) for
                // cells where any LESS-specific context is live: at runtime M ⊇
                // ctxMask that context OWNS the position — its lack of a
                // transition means the walk dies there, and the marker blocks
                // the less-specific (wrong-context) range from firing.
                int nCtx2 = ctxInputs.size();
                int[][] ctxSetRes = new int[nCtx2][];
                for (int ci = 0; ci < nCtx2; ci++) {
                    List<Config> stepInput = ctxInputs.get(ci);
                    int ctxMask = ctxList.get(ci)[0];
                    int ownCount = stepInput.size();   // true-order list: every config is a priority competitor
                    int[] setRes = new int[activeSetCount];
                    java.util.Arrays.fill(setRes, 0);
                    ctxSetRes[ci] = setRes;
                    AddResult[] perSet = new AddResult[activeSetCount];
                    boolean[] perSetDone = new boolean[activeSetCount];
                    for (int bi = 0; bi < cellCount; bi++) {
                        int rangeLo = breakpoints[bi];
                        int rangeHi = breakpoints[bi + 1] - 1;
                        int setId = activeSetId[bi];
                        if (perSetDone[setId]) {
                            AddResult ar = perSet[setId];
                            if (ar != null) {
                                builders.get(sid).addRange(rangeLo, rangeHi, ar.targetId, ar.ops, ctxMask);
                            }
                            continue;
                        }
                        perSetDone[setId] = true;
                        List<Config> stepped = stepOnSymbol(stepInput, rangeActiveEdges[bi], requiredMaskOut, ownCount, ctxMask);
                        if (stepped.isEmpty()) { perSet[setId] = null; setRes[setId] = -1; continue; }
                        List<Config> closed;
                        int[] newPrectable;
                        if (!longest) {
                            closed = epsilonClosure(stepped);
                            newPrectable = null;
                        } else {
                            int[] parentPrectable = COMPUTE_PRECTABLES ? statePrectables.get(sid) : null;
                            closed = closureGtop(stepped, parentPrectable, cur.size());
                            newPrectable = COMPUTE_PRECTABLES ? computePrectable(closed, parentPrectable, cur.size()) : null;
                        }
                        if (debug && closed.size() > 100) System.err.println("[tdfa] state " + sid + " range " + rangeLo + ".." + rangeHi + " closure=" + closed.size());
                        int[] ops = transitionRegops(closed, sid);
                        AddResult ar = addState(closed, ops, stepped);
                        if (longest) {
                            // Ensure prectable slot exists for the target state.
                            while (statePrectables.size() <= ar.targetId) statePrectables.add(null);
                            if (statePrectables.get(ar.targetId) == null) {
                                statePrectables.set(ar.targetId, newPrectable);
                            }
                        }
                        if (debug) System.err.println("[tdfa] state " + sid + " on '" + (char) rangeLo + "' (" + rangeLo + ") -> " + ar.targetId + " ops.len=" + ops.length + " mask=" + ctxMask);
                        builders.get(sid).addRange(rangeLo, rangeHi, ar.targetId, ar.ops, ctxMask);
                        if (!processed.get(ar.targetId)) work.push(ar.targetId);
                        perSet[setId] = ar;
                        setRes[setId] = ar.targetId;
                    }
                }
                // Dead markers: only when overlaps exist (nCtx2 > 1) — a single
                // context owns every cell unambiguously.
                if (nCtx2 > 1) {
                    for (int bi = 0; bi < cellCount; bi++) {
                        int rangeLo = breakpoints[bi];
                        int rangeHi = breakpoints[bi + 1] - 1;
                        int setId = activeSetId[bi];
                        // for each EMPTY context: marker iff some LATER (less specific) context is live
                        for (int ci = 0; ci < nCtx2; ci++) {
                            if (ctxSetRes[ci][setId] != -1) continue;
                            for (int cj = ci + 1; cj < nCtx2; cj++) {
                                if (ctxSetRes[cj][setId] > 0) {
                                    builders.get(sid).addRange(rangeLo, rangeHi, -1, null, ctxList.get(ci)[0]);
                                    if (debug) System.err.println("[tdfa] state " + sid + " cell " + rangeLo + ".." + rangeHi + " DEAD marker mask=" + Integer.toBinaryString(ctxList.get(ci)[0]));
                                    break;
                                }
                            }
                        }
                    }
                }
                // Tagless compiles: release the boxed closure of the just-processed
                // state — everything downstream reads the packed form (see tryMap and
                // the materialization pass). This is where the GBs go home.
                if (tags == 0) {
                    int[] packed = new int[cur.size() * 2];
                    for (int i = 0; i < cur.size(); i++) {
                        Config c = cur.get(i);
                        packed[i * 2] = c.state;
                        packed[i * 2 + 1] = c.emptyMask;
                    }
                    packedKernels.set(sid, packed);
                    states.set(sid, null);
                }
            }
            if (debug) System.err.println("[tdfa] total states=" + states.size() + " accept=" + accept.cardinality());

            int n = states.size();
            // Compute per-state entry/accept masks.
            int[] stateEntryMask = new int[n];
            int[] stateAcceptMask = new int[n];
            // Position-aware stopOnAcceptMask: int[state * 64 + posFlags] encodes
            // 0 (stop) or NEVER_STOP (don't stop). Position-aware because re2j's
            // densePcs priority depends on which assertion edges are live at the
            // current cursor position — see computePerStateOrder(seed, posMask).
            // 64 = 2^6 position-flag bits (BEGIN/END_TEXT, WORD/NO_WORD, ABS_BEGIN/ABS_END).
            int[] stateStopOnAcceptMask = new int[n * 64];
            java.util.Arrays.fill(stateStopOnAcceptMask, NEVER_STOP);
            int ALL_BITS = Tnfa.BEGIN_TEXT | Tnfa.END_TEXT | Tnfa.WORD_BOUNDARY | Tnfa.NO_WORD_BOUNDARY
                    | Tnfa.ABS_BEGIN | Tnfa.ABS_END;
            for (int s = 0; s < n; s++) {
                List<Config> cfgs = states.get(s);
                int[] pk = tags == 0 ? packedKernels.get(s) : null;   // packed form (tagless: cfgs == null)
                int cnt = pk != null ? pk.length >> 1 : cfgs.size();
                int entryIntersect = ALL_BITS;
                for (int i = 0; i < cnt; i++) entryIntersect &= pk != null ? pk[i * 2 + 1] : cfgs.get(i).emptyMask;
                stateEntryMask[s] = entryIntersect;
                int acceptIntersect = ALL_BITS;
                boolean anyAccept = false;
                for (int i = 0; i < cnt; i++) {
                    int st = pk != null ? pk[i * 2] : cfgs.get(i).state;
                    if (st == nfa.accept) {
                        acceptIntersect &= pk != null ? pk[i * 2 + 1] : cfgs.get(i).emptyMask;
                        anyAccept = true;
                    }
                }
                stateAcceptMask[s] = anyAccept ? acceptIntersect : 0;
                // Perl leftmost-first: for each (state, posFlags) pair, decide
                // whether the runner should break the match loop on accept. The
                // decision is position-aware because re2j's runtime closure
                // evaluates each assertion against the current cursor's cond and
                // kills failing threads before they can claim a densePcs slot —
                // so the same DFA state can have different "highest-priority
                // outcome" at different positions. Example: for ^((?:$)|.)* at
                // pos 0 of "a", $ fails, so the .-branch outranks the skip-exit
                // MATCH and we extend; at pos 1 (EOF), $ holds, the $-loop-back
                // MATCH outranks . and we stop.
                //
                // For each of the 64 possible posFlags values M, compute the
                // perStateOrder DFS skipping assertion edges whose requirements
                // aren't subset of M, then check whether any sym-bearing config
                // outranks accept in that order. If yes, NEVER_STOP (extend);
                // else 0 (stop). Accept-unreachable-under-M also gets NEVER_STOP
                // (no accept to stop on; runner's sam check filters anyway).
                if (!longest && anyAccept) {
                    Object seed = stateSeeds.get(s);
                    if (debug) {
                        System.err.println("[stop] state " + s + " cnt=" + cnt);
                        for (int i = 0; i < cnt; i++) {
                            int st = pk != null ? pk[i * 2] : cfgs.get(i).state;
                            int em = pk != null ? pk[i * 2 + 1] : cfgs.get(i).emptyMask;
                            System.err.println("[stop]   cfg[" + i + "] nfa=" + st + (st == nfa.accept ? " ACCEPT" : "")
                                    + " mask=" + Integer.toBinaryString(em) + " symEdges=" + symOut[st].length);
                        }
                    }
                    for (int M = 0; M < 64; M++) {
                        int[] perStateOrder = seed instanceof int[] ss
                                ? computePerStateOrder(ss, M) : computePerStateOrder((List<Config>) seed, M);
                        int acceptOrder = perStateOrder[nfa.accept];
                        if (acceptOrder == -1) {
                            // Accept unreachable under M; sam check will fail too.
                            stateStopOnAcceptMask[s * 64 + M] = NEVER_STOP;
                            continue;
                        }
                        boolean higherPriSym = false;
                        for (int i = 0; i < cnt; i++) {
                            int st = pk != null ? pk[i * 2] : cfgs.get(i).state;
                            if (st == nfa.accept) continue;
                            if (symOut[st].length == 0) continue;
                            int o = perStateOrder[st];
                            if (o != -1 && o < acceptOrder) {
                                higherPriSym = true;
                                break;
                            }
                        }
                        stateStopOnAcceptMask[s * 64 + M] = higherPriSym ? NEVER_STOP : 0;
                    }
                }
            }
            // Pre-pass: compute finalRegops for each accepting state up front, so the
            // CFG optimization (BT22 §6.3) can see them along with transition ops.
            for (int s = 0; s < n; s++) {
                if (accept.get(s)) {
                    List<Config> cfgs = states.get(s);
                    if (cfgs != null) {
                        builders.get(s).finalOpsArr = finalRegops(cfgs);
                        computeFinalVariants(builders.get(s), cfgs);
                    } else {
                        // Tagless accept state: boxed closure released, packed
                        // kernel only. Still compute the per-M variants — an
                        // accept state whose configs carry DIFFERENT emptyMasks
                        // is an OR of assertion-gated accepts, which the
                        // conjunctive stateAcceptMask (intersection) collapses
                        // to "always alive" (fuzz round 10: Z(?:\A|\B) matched
                        // at pos 1 where \A and \B both fail). finalRegopsOf
                        // returns empty ops when tags==0, so variants only
                        // encode aliveness — the byMask cell sign.
                        builders.get(s).finalOpsArr = null;
                        computeFinalVariantsPacked(builders.get(s), packedKernels.get(s));
                    }
                }
            }
            // Determinize-lifetime data is dead from here: the stateIndex sigs,
            // (packed) kernels, seeds, eps/sym adjacency and scratch are all
            if (Boolean.getBoolean("tdfa.debug.closure")) {
                System.err.println("[det] states=" + states.size() + " kernelsTotal=" + kernelsTotal);
            }
            // downstream-unused, but as Compiler fields they would stay live
            // through materialization/minimization — the heap peak on giant
            // DFAs. Release ~0.8 GB (bomb) before the flat-array phase.
            stateIndex = null; states = null; packedKernels = null; stateSeeds = null;
            work = null; epsOut = null; symOut = null;
            rangeActiveEdges = null; activeSetId = null; processed = null;

            obs.stage(io.github.jemmix.tdfa.core.CompileObserver.Stage.DETERMINIZE,
                    System.nanoTime() - tDet, n);

            // === BT22 §6.3 register optimizations ===
            int finalRegBase = tags;  // default: working [0..T-1], final [T..2T-1]
            long tReg = System.nanoTime();
            if (REGOPT_ENABLED && tags > 0 && n > 1 && n <= REGOPT_MAX_STATES) {
                io.github.jemmix.tdfa.regopt.Cfg cfg = buildCfg(builders, accept, states, tags, nfa.groupCount, nextReg);
                io.github.jemmix.tdfa.regopt.Optimize.optimize(cfg, meter);
                cfgWriteBack(cfg, builders);
                finalRegBase = cfg.finalRegBase;
                if (debug) System.err.println("[tdfa] regopt: regs " + cfg.initialRegCount + " -> " + cfg.regCount
                        + " (finalRegBase=" + finalRegBase + ")"
                        + (cfg.dceRemovedOps > 0 ? " DCE removed " + cfg.dceRemovedOps + " ops" : ""));
                obs.stage(io.github.jemmix.tdfa.core.CompileObserver.Stage.REGOPT,
                        System.nanoTime() - tReg, cfg.regCount);
                obs.note("regopt", "regs " + cfg.initialRegCount + "->" + cfg.regCount);
            } else {
                obs.stage(io.github.jemmix.tdfa.core.CompileObserver.Stage.REGOPT,
                        System.nanoTime() - tReg, 2 * tags);
                obs.note("regopt", REGOPT_ENABLED ? "skipped (bounds)" : "disabled");
            }

            // First pass: coalesce + mask-specificity sort on every state's ranges,
            // compute totals. No gap filling: dead (target=-1) entries between live
            // ranges are semantically unnecessary — every consumer treats "no entry
            // matches" as death — and for wide Unicode classes they would double the
            // entry count (~700 live + ~700 gap fillers for \w under (?u)), halving
            // scan speed. sortByMaskSpecificity keeps ranges sorted by lo (mask bits
            // only break ties), so downstream sorted-order assumptions still hold.
            int totalRanges = 0;
            int totalOpsSlots = 1;  // reserve ops[0] = OP_END for the "no ops" case (opsOff=0 means empty)
            for (int s = 0; s < n; s++) {
                DfaStateBuilder sb = builders.get(s);
                sb.coalesce();
                sb.sortByMaskSpecificity();
                totalRanges += sb.ranges.size();
                for (Range r : sb.ranges) {
                    if (r.ops != null && r.ops.length > 0) totalOpsSlots += r.ops.length + 1;  // +1 for OP_END
                }
                if (accept.get(s)) {
                    int[] f = sb.finalOpsArr;  // populated in pre-pass above (possibly optimized by CFG)
                    if (f != null && f.length > 0) totalOpsSlots += f.length + 1;
                    if (sb.finalOpsVariants != null)
                        for (int[] v : sb.finalOpsVariants)
                            if (v != null && v.length > 0) totalOpsSlots += v.length + 1;
                }
            }

            // Second pass: allocate flat arrays and populate.
            int[] stateFinalOpsByMask = null;
            boolean[] finalVariantState = new boolean[n];
            int[] stateMeta = new int[n];
            int[] stateBase = new int[n];
            int[] stateFinalOpsOff = new int[n];
            int[] flatRanges = new int[totalRanges * 5];
            int[] flatOps = new int[totalOpsSlots];
            flatOps[0] = OP_END;  // opsOff=0 means "empty block"
            int opsHead = 1;       // next free slot in flatOps (slot 0 reserved)
            int rangesHead = 0;    // next free slot in flatRanges (in units of 5 ints)
            int globalMaxReg = 2 * tags;  // at least r0 + R_f
            for (int s = 0; s < n; s++) {
                DfaStateBuilder sb = builders.get(s);
                int k = sb.ranges.size();
                int rangeBase = rangesHead;
                for (int i = 0; i < k; i++) {
                    Range r = sb.ranges.get(i);
                    int o = rangesHead * 5;
                    flatRanges[o]     = r.lo;
                    flatRanges[o + 1] = r.hi;
                    flatRanges[o + 2] = r.target;
                    int opsOff;
                    if (r.ops == null || r.ops.length == 0) {
                        opsOff = 0;  // shared "empty" sentinel at ops[0]
                    } else {
                        opsOff = opsHead;
                        for (int j = 0; j < r.ops.length; j += 3) {
                            flatOps[opsHead]     = r.ops[j];
                            flatOps[opsHead + 1] = r.ops[j + 1];
                            flatOps[opsHead + 2] = r.ops[j + 2];
                            globalMaxReg = Math.max(globalMaxReg, r.ops[j + 1] + 1);
                            if (r.ops[j] == OP_COPY) globalMaxReg = Math.max(globalMaxReg, r.ops[j + 2] + 1);
                            opsHead += 3;
                        }
                        flatOps[opsHead++] = OP_END;
                    }
                    flatRanges[o + 3] = opsOff;
                    flatRanges[o + 4] = r.requiredMask;
                    rangesHead++;
                }
                int finalOpsOff = 0;
                if (sb.finalOpsArr != null && sb.finalOpsArr.length > 0) {
                    finalOpsOff = opsHead;
                    int[] f = sb.finalOpsArr;
                    for (int j = 0; j < f.length; j += 3) {
                        flatOps[opsHead]     = f[j];
                        flatOps[opsHead + 1] = f[j + 1];
                        flatOps[opsHead + 2] = f[j + 2];
                        globalMaxReg = Math.max(globalMaxReg, f[j + 1] + 1);
                        if (f[j] == OP_COPY) globalMaxReg = Math.max(globalMaxReg, f[j + 2] + 1);
                        opsHead += 3;
                    }
                    flatOps[opsHead++] = OP_END;
                }
                boolean isAccept = accept.get(s);
                stateBase[s] = rangeBase;
                stateMeta[s] = ((k & 0xFFFF) << 1) | (isAccept ? 1 : 0);
                stateFinalOpsOff[s] = finalOpsOff;
                if (sb.finalOpsVariants != null && isAccept) {
                    finalVariantState[s] = true;
                    if (stateFinalOpsByMask == null) stateFinalOpsByMask = new int[n * 64];
                    int[] variantOff = new int[sb.finalOpsVariants.length];
                    for (int v = 0; v < variantOff.length; v++) {
                        int[] f = sb.finalOpsVariants[v];
                        variantOff[v] = 0;  // empty ops: accept fires, no ops
                        if (f != null && f.length > 0) {
                            variantOff[v] = opsHead;
                            for (int j = 0; j < f.length; j += 3) {
                                flatOps[opsHead]     = f[j];
                                flatOps[opsHead + 1] = f[j + 1];
                                flatOps[opsHead + 2] = f[j + 2];
                                globalMaxReg = Math.max(globalMaxReg, f[j + 1] + 1);
                                if (f[j] == OP_COPY) globalMaxReg = Math.max(globalMaxReg, f[j + 2] + 1);
                                opsHead += 3;
                            }
                            flatOps[opsHead++] = OP_END;
                        }
                    }
                    for (int M = 0; M < 64; M++) {
                        int v = sb.finalMaskVariant[M];
                        stateFinalOpsByMask[s * 64 + M] = v < 0 ? -1 : variantOff[v];
                    }
                    if (stateFinalOpsOff[s] == 0 && variantOff.length > 0)
                        stateFinalOpsOff[s] = variantOff[0];  // sane default for non-runtime consumers
                }
            }
            // Builders (3.2 M Range objects on the bomb) are dead once the flat
            // arrays are populated; minimize/fallback only read the flat forms.
            builders = null; accept = null;
            if (stateFinalOpsByMask != null) {
                // The table is authoritative for every state when present:
                // uniform accepting states point all 64 cells at their φ;
                // non-accepting states stay all -1 (never read).
                for (int s = 0; s < n; s++) {
                    if (!finalVariantState[s]) {
                        int off = (stateMeta[s] & 1) != 0 ? stateFinalOpsOff[s] : -1;
                        java.util.Arrays.fill(stateFinalOpsByMask, s * 64, s * 64 + 64, off);
                    }
                }
            }

            // === Minimize via register-aware Moore's algorithm (paper §6.2.2 Minimization) ===
            // Treat transitions on the same symbol but with different register ops as different
            // transitions. Op sequences are interned to unique numeric IDs for O(1) comparison
            // (paper: "operation sequences are inserted into a hash map and represented with
            // unique numeric identifiers"). Comparison may have false negatives (non-identical
            // but semantically equivalent op lists), but that only yields a suboptimal — not
            // incorrect — minimization. Apply after register optimizations for best results.
            int stateCount = n;
            int[] minMeta = stateMeta, minBase = stateBase, minFinalOpsOff = stateFinalOpsOff,
                    minRanges = flatRanges, minEntryMask = stateEntryMask,
                    minAcceptMask = stateAcceptMask, minStopMask = stateStopOnAcceptMask,
                    minFinalOpsByMask = stateFinalOpsByMask;
            long tMin = System.nanoTime();
            if (MINIMIZE_ENABLED && n > 1 && n <= MINIMIZE_MAX_STATES) {
                DfaMinimizer m = new DfaMinimizer(n, stateMeta, stateBase, stateFinalOpsOff,
                        flatRanges, flatOps, stateEntryMask, stateAcceptMask,
                        stateStopOnAcceptMask, stateFinalOpsByMask, longest);
                int[] partition = m.computePartition();
                int newN = 0;
                for (int p : partition) newN = Math.max(newN, p + 1);
                if (newN < n) {
                    // Renumber so the start state's partition becomes state 0 (preserves invariant).
                    int[] renum = new int[newN];
                    java.util.Arrays.fill(renum, -1);
                    int nextId = 0;
                    for (int s = 0; s < n; s++) {
                        int p = partition[s];
                        if (renum[p] == -1) renum[p] = nextId++;
                    }
                    newN = nextId;
                    int[] rep = new int[newN];
                    java.util.Arrays.fill(rep, -1);
                    for (int s = 0; s < n; s++) {
                        int g = renum[partition[s]];
                        partition[s] = g;
                        if (rep[g] == -1) rep[g] = s;
                    }
                    int newTotalRanges = 0;
                    for (int g = 0; g < newN; g++) newTotalRanges += rangeCount(stateMeta[rep[g]]);
                    minMeta = new int[newN];
                    minBase = new int[newN];
                    minFinalOpsOff = new int[newN];
                    minEntryMask = new int[newN];
                    minAcceptMask = new int[newN];
                    minStopMask = new int[newN * 64];
                    minRanges = new int[newTotalRanges * 5];
                    if (stateFinalOpsByMask != null) minFinalOpsByMask = new int[newN * 64];
                    if (longest) java.util.Arrays.fill(minStopMask, NEVER_STOP);
                    int minRangesHead = 0;
                    for (int g = 0; g < newN; g++) {
                        int r = rep[g];
                        minMeta[g] = stateMeta[r];
                        minBase[g] = minRangesHead;
                        minFinalOpsOff[g] = stateFinalOpsOff[r];
                        minEntryMask[g] = stateEntryMask[r];
                        minAcceptMask[g] = stateAcceptMask[r];
                        if (!longest) {
                            System.arraycopy(stateStopOnAcceptMask, r * 64, minStopMask, g * 64, 64);
                        }
                        if (minFinalOpsByMask != null) {
                            System.arraycopy(stateFinalOpsByMask, r * 64, minFinalOpsByMask, g * 64, 64);
                        }
                        int base = stateBase[r];
                        int count = rangeCount(stateMeta[r]);
                        for (int i = 0; i < count; i++) {
                            int o = (base + i) * 5;
                            int no = minRangesHead * 5;
                            minRanges[no]     = flatRanges[o];
                            minRanges[no + 1] = flatRanges[o + 1];
                            int t = flatRanges[o + 2];
                            minRanges[no + 2] = (t == -1) ? -1 : partition[t];
                            minRanges[no + 3] = flatRanges[o + 3];
                            minRanges[no + 4] = flatRanges[o + 4];
                            minRangesHead++;
                        }
                    }
                    if (Tdfa.DEBUG) System.err.println("[tdfa] minimized: " + n + " -> " + newN + " states");
                    stateCount = newN;
                }
            }
            // === BT22 §6.2 fallback operations ===
            // Add backup COPY ops on transitions out of fallback states (those final
            // states with non-accepting paths), and generate ψ quasi-transitions
            // that route through the backups. Closes a latent POSIX capture bug
            // where stepping past an accept then falling back clobbers registers.
            obs.stage(io.github.jemmix.tdfa.core.CompileObserver.Stage.MINIMIZE,
                    System.nanoTime() - tMin, stateCount);

            boolean[] stateIsFallback = new boolean[stateCount];
            int[] stateFallbackOpsOff = new int[stateCount];
            long tFb = System.nanoTime();
            int fallbackStates = 0;
            if (FALLBACK_ENABLED && tags > 0) {
                FallbackOps.Result fr = FallbackOps.add(stateCount, minMeta, minBase, minRanges, flatOps,
                        minFinalOpsOff, globalMaxReg, meter);
                flatOps = fr.flatOps;
                minRanges = fr.ranges;
                stateIsFallback = fr.stateIsFallback;
                stateFallbackOpsOff = fr.stateFallbackOpsOff;
                globalMaxReg = fr.registerCount;
                fallbackStates = fr.fallbackStateCount;
                if (debug && fr.fallbackStateCount > 0) {
                    System.err.println("[tdfa] fallback: " + fr.fallbackStateCount + " states, "
                            + fr.backupTransitionCount + " backup transitions, "
                            + fr.backupSlotCount + " backup slots");
                }
            }
            obs.stage(io.github.jemmix.tdfa.core.CompileObserver.Stage.FALLBACK,
                    System.nanoTime() - tFb, fallbackStates);
            // Ensure per-state entries are sorted by lo (stable: equal-lo groups
            // keep their mask-specificity order). The builders emit sorted, but
            // the minimizer / regopt rewrite can reorder within a state; the
            // runtime's binary search over lo requires it.
            for (int s = 0; s < stateCount; s++) {
                int cnt = (minMeta[s] >>> 1) & 0xFFFF, b = minBase[s];
                boolean sorted = true;
                for (int i = 1; i < cnt; i++) {
                    if (minRanges[(b + i) * 5] < minRanges[(b + i - 1) * 5]) { sorted = false; break; }
                }
                if (sorted) continue;
                // Pack (lo << 32) | original index for a stable sort by lo, then
                // permute the 5-int entry groups in place.
                long[] keys = new long[cnt];
                for (int i = 0; i < cnt; i++) keys[i] = ((long) minRanges[(b + i) * 5] << 32) | i;
                java.util.Arrays.sort(keys);
                int[] tmp = new int[cnt * 5];
                for (int i = 0; i < cnt; i++) {
                    int src = (int) (keys[i] & 0xFFFFFFFFL) * 5;
                    System.arraycopy(minRanges, (b + src) * 5, tmp, i * 5, 5);
                }
                System.arraycopy(tmp, 0, minRanges, b * 5, cnt * 5);
            }
            // Rebuild the per-entry hi-prefix over the final (possibly remapped) arrays.
            int[] minHiPrefix = new int[minRanges.length / 5];
            for (int s = 0; s < stateCount; s++) {
                int cnt = (minMeta[s] >>> 1) & 0xFFFF, b = minBase[s], maxHi = Integer.MIN_VALUE;
                for (int i = 0; i < cnt; i++) {
                    int hi = minRanges[(b + i) * 5 + 1];
                    if (hi > maxHi) maxHi = hi;
                    minHiPrefix[b + i] = maxHi;
                }
            }
            // Materialization facts for memory attribution (observable via a
            // CompileObserver "tables" note). Byte sizes are the flat-array
            // payloads actually retained by the Tdfa (4 B per int slot).
            boolean perStateUniform = true;   // all 64 posFlags cells identical within each state
            boolean globalUniform = true;     // ... and identical across states
            {
                int acceptCnt = 0;
                for (int s = 0; s < stateCount; s++) if ((minMeta[s] & 1) != 0) acceptCnt++;
                int globalVal = minStopMask.length > 0 ? minStopMask[0] : 0;
                for (int s = 0; s < stateCount && perStateUniform; s++) {
                    int v0 = minStopMask[s * 64];
                    for (int m = 1; m < 64; m++) {
                        if (minStopMask[s * 64 + m] != v0) { perStateUniform = false; globalUniform = false; break; }
                    }
                    if (v0 != globalVal) globalUniform = false;
                }
                // Storage tier: POSIX -> neither (readers gate on Perl mode);
                // Perl + per-state-uniform -> byte[n]; general Perl -> int[n*64].
                byte[] uniformStop = null;
                int[] finalStop = null;
                if (!longest) {
                    if (perStateUniform) {
                        uniformStop = new byte[stateCount];
                        for (int s = 0; s < stateCount; s++) uniformStop[s] = minStopMask[s * 64] != 0 ? (byte) 1 : 0;
                    } else {
                        finalStop = minStopMask;
                    }
                }
                obs.note("tables", "states=" + stateCount + " ranges=" + (minRanges.length / 5)
                        + " accept=" + acceptCnt
                        + " bytes{ranges=" + (minRanges.length * 4L)
                        + ",stopMask=" + (uniformStop != null ? uniformStop.length
                        : finalStop != null ? finalStop.length * 4L : 0)
                        + ",entryMask=" + (minEntryMask.length * 4L)
                        + ",acceptMask=" + (minAcceptMask.length * 4L)
                        + ",ops=" + (flatOps.length * 4L)
                        + ",hiPrefix=" + (minHiPrefix.length * 4L)
                        + ",scalars=" + ((minMeta.length + minBase.length + minFinalOpsOff.length
                        + stateIsFallback.length + stateFallbackOpsOff.length) * 4L + stateCount) + "}"
                        + " stopMaskUniform=" + (perStateUniform ? (globalUniform ? "global" : "perState") : "no"));
                return new Tdfa(tags, nfa.groupCount, nfa.namedGroups, globalMaxReg, finalRegBase, 0, stateCount,
                        minMeta, minBase, minFinalOpsOff, minFinalOpsByMask, minRanges, flatOps, minHiPrefix,
                        minEntryMask, minAcceptMask, longest, finalStop, uniformStop, nfa.multiline,
                        nfa.unicodeWordBoundary, nfa.wordRanges,
                        hasFixed(nfa.fixedBase) ? nfa.fixedBase : null,
                        hasFixed(nfa.fixedBase) ? nfa.fixedOffset : null,
                        stateIsFallback, stateFallbackOpsOff);
            }
        }

        private static boolean hasFixed(int[] fixedBase) {
            if (fixedBase == null) return false;
            for (int i = 1; i < fixedBase.length; i++) if (fixedBase[i] != 0) return true;
            return false;
        }

        // ============================ CFG construction (BT22 §6.3) ============================

        /**
         * Build a {@link io.github.jemmix.tdfa.regopt.Cfg} from the post-determinization
         * {@code DfaStateBuilder} list. Each {@code (state, range-with-ops)} pair becomes
         * a BASIC block; each accepting state with non-empty {@code finalOpsArr} becomes
         * a FINAL block. Arcs skip zero-op transitions.
         *
         * <p>Lives inline in {@code Tdfa.Compiler} so it has direct access to the
         * package-private nested {@code DfaStateBuilder}/{@code Range} types
         * (avoiding reflection on synthetic nested-class field names).
         */
        io.github.jemmix.tdfa.regopt.Cfg buildCfg(List<DfaStateBuilder> builders, BitSet accept,
                                               @SuppressWarnings("unused") List<List<Config>> states,
                                               int tagCount, int groupCount, int initialRegCount) {
            io.github.jemmix.tdfa.regopt.Cfg cfg = new io.github.jemmix.tdfa.regopt.Cfg(tagCount, groupCount, initialRegCount);
            int n = builders.size();
            // First pass: create blocks.
            int[][] rangeBlockIds = new int[n][];
            List<Integer>[] basicLeaving = new List[n];
            int[] finalBlockAt = new int[n];
            @SuppressWarnings("unchecked")
            List<Integer>[] finalVariantBlocks = new List[n];
            for (int s = 0; s < n; s++) finalVariantBlocks[s] = new ArrayList<>();
            java.util.Arrays.fill(finalBlockAt, -1);
            for (int s = 0; s < n; s++) basicLeaving[s] = new ArrayList<>();
            for (int s = 0; s < n; s++) {
                DfaStateBuilder sb = builders.get(s);
                rangeBlockIds[s] = new int[sb.ranges.size()];
                java.util.Arrays.fill(rangeBlockIds[s], -1);
                for (int r = 0; r < sb.ranges.size(); r++) {
                    Range range = sb.ranges.get(r);
                    if (range.ops == null || range.ops.length == 0) continue;
                    io.github.jemmix.tdfa.regopt.Cfg.Block blk = cfg.newBlock(io.github.jemmix.tdfa.regopt.Cfg.BLOCK_BASIC, s, r);
                    decodeOps(range.ops, blk.ops);
                    rangeBlockIds[s][r] = cfg.blocks.size() - 1;
                    basicLeaving[s].add(rangeBlockIds[s][r]);
                }
                if (accept.get(s)) {
                    if (sb.finalOpsVariants != null) {
                        // Position-aware state: one FINAL block per φ variant
                        // (rangeIndex = variant index). No default block — the
                        // runtime selects per posFlags and never uses
                        // stateFinalOpsOff for this state.
                        for (int v = 0; v < sb.finalOpsVariants.length; v++) {
                            io.github.jemmix.tdfa.regopt.Cfg.Block vb = cfg.newBlock(io.github.jemmix.tdfa.regopt.Cfg.BLOCK_FINAL, s, v);
                            if (sb.finalOpsVariants[v] != null) decodeOps(sb.finalOpsVariants[v], vb.ops);
                            finalVariantBlocks[s].add(cfg.blocks.size() - 1);
                        }
                    } else {
                        io.github.jemmix.tdfa.regopt.Cfg.Block fb = cfg.newBlock(io.github.jemmix.tdfa.regopt.Cfg.BLOCK_FINAL, s, -1);
                        if (sb.finalOpsArr != null) decodeOps(sb.finalOpsArr, fb.ops);
                        finalBlockAt[s] = cfg.blocks.size() - 1;
                    }
                }
            }
            // Second pass: successor arcs. BASIC block at state s with range.target s' ->
            // all blocks (BASIC + FINAL) reachable from s' through zero-op transitions.
            for (io.github.jemmix.tdfa.regopt.Cfg.Block blk : cfg.blocks) {
                if (blk.kind != io.github.jemmix.tdfa.regopt.Cfg.BLOCK_BASIC) continue;
                int target = builders.get(blk.stateId).ranges.get(blk.rangeIndex).target;
                BitSet visited = new BitSet();
                List<Integer> frontier = new ArrayList<>();
                frontier.add(target);
                visited.set(target);
                while (!frontier.isEmpty()) {
                    int t = frontier.remove(frontier.size() - 1);
                    blk.successors.addAll(basicLeaving[t]);
                    if (finalBlockAt[t] != -1) blk.successors.add(finalBlockAt[t]);
                    blk.successors.addAll(finalVariantBlocks[t]);
                    DfaStateBuilder tb = builders.get(t);
                    for (int r = 0; r < tb.ranges.size(); r++) {
                        Range tr = tb.ranges.get(r);
                        if (tr.ops != null && tr.ops.length > 0) continue;  // op-bearing: not skipped
                        if (tr.target < 0) continue;
                        if (!visited.get(tr.target)) {
                            visited.set(tr.target);
                            frontier.add(tr.target);
                        }
                    }
                }
            }
            return cfg;
        }

        private static void decodeOps(int[] flat, List<io.github.jemmix.tdfa.regopt.Cfg.Op> out) {
            for (int i = 0; i < flat.length; i += 3) {
                int op = flat[i], dst = flat[i + 1], src = flat[i + 2];
                if (op == OP_END) break;
                switch (op) {
                    case OP_SET_POS: out.add(io.github.jemmix.tdfa.regopt.Cfg.Op.setPos(dst)); break;
                    case OP_SET_NIL: out.add(io.github.jemmix.tdfa.regopt.Cfg.Op.setNil(dst)); break;
                    case OP_COPY:    out.add(io.github.jemmix.tdfa.regopt.Cfg.Op.copy(dst, src)); break;
                    default: throw new IllegalStateException("bad op: " + op);
                }
            }
        }

        /** Flush optimized CFG ops back into the builders' Range/finalOpsArr slots. */
        void cfgWriteBack(io.github.jemmix.tdfa.regopt.Cfg cfg, List<DfaStateBuilder> builders) {
            for (io.github.jemmix.tdfa.regopt.Cfg.Block blk : cfg.blocks) {
                int[] encoded = encodeOps(blk.ops);
                DfaStateBuilder sb = builders.get(blk.stateId);
                if (blk.kind == io.github.jemmix.tdfa.regopt.Cfg.BLOCK_BASIC) {
                    sb.ranges.get(blk.rangeIndex).ops = encoded;
                } else if (blk.kind == io.github.jemmix.tdfa.regopt.Cfg.BLOCK_FINAL) {
                    if (blk.rangeIndex >= 0) sb.finalOpsVariants[blk.rangeIndex] = encoded;
                    else sb.finalOpsArr = encoded;
                }
            }
        }

        private static int[] encodeOps(List<io.github.jemmix.tdfa.regopt.Cfg.Op> ops) {
            if (ops.isEmpty()) return null;
            int[] flat = new int[ops.size() * 3];
            for (int i = 0; i < ops.size(); i++) {
                io.github.jemmix.tdfa.regopt.Cfg.Op op = ops.get(i);
                switch (op.kind) {
                    case io.github.jemmix.tdfa.regopt.Cfg.KIND_SET:
                        flat[i * 3] = op.value == io.github.jemmix.tdfa.regopt.Cfg.VAL_POS ? OP_SET_POS : OP_SET_NIL;
                        flat[i * 3 + 1] = op.dst;
                        flat[i * 3 + 2] = 0;
                        break;
                    case io.github.jemmix.tdfa.regopt.Cfg.KIND_COPY:
                        flat[i * 3] = OP_COPY;
                        flat[i * 3 + 1] = op.dst;
                        flat[i * 3 + 2] = op.src;
                        break;
                    default: throw new IllegalStateException("cannot encode op kind " + op.kind);
                }
            }
            return flat;
        }

        static final boolean debug = Boolean.getBoolean("tdfa.debug");

        int maxReg(List<Config> configs) {
            int m = 2 * tags - 1;
            for (Config c : configs) for (int r : c.regs) if (r > m) m = r;
            return m;
        }

        // ---------------- Algorithm 3 building blocks ----------------

        /**
         * ε-closure via DFS with priority-ordered exploration (paper Algorithm 3).
         * Uses a stack (LIFO): children pushed in REVERSE priority order so the
         * highest-priority child is on top and popped first. This ensures the
         * leftmost-greedy preferred path is explored all the way down before
         * lower-priority alternatives.
         *
         * When the accept state is reached, remaining configs on the stack are
         * suppressed (they're all lower-priority — DFS has already explored
         * higher-priority paths). This prevents the DFA from having transitions
         * that follow lower-priority alternatives past an accept.
         */
        /** True iff {@code popped} (bitset of popped mask values, bit m = mask m) has any submask of {@code m} set. */
        private static boolean submaskPopped(long popped, int m) {
            for (int sub = m; sub != 0; sub = (sub - 1) & m) {
                if ((popped & (1L << sub)) != 0) return true;
            }
            return (popped & 1L) != 0;   // the empty submask (mask 0) closes the loop
        }

        List<Config> epsilonClosure(List<Config> seed) {
            List<Config> out = new ArrayList<>(seed.size() * 2);
            // For deterministic exploration we visit (state, mask) pairs — same NFA state
            // can appear with different assertion masks (e.g. loop entered 0 vs 1 times).
            // A visited set keyed only on state would wrongly suppress the second path.
            // Open-addressing primitive (state<<32|mask) set: the boxed HashSet<Long> this
            // replaces allocated a Long per visited config per closure call — the #1
            // allocation hotspot on wide-class determinization. Initial size seed*2 (not
            // seed*8): closures grow it geometrically on demand, and the smaller initial
            // table saves ~2/3 of the per-call fill cost on the 1.4 M closure calls of
            // large determinizations.
            long[] visitedSM = new long[Math.max(16, Integer.highestOneBit(seed.size() * 2 - 1) << 1)];
            int visitedMask = visitedSM.length - 1;
            int visitedCount = 0;
            ArrayDeque<Config> stack = new ArrayDeque<>();
            // Push seed configs in reverse so the first seed config is on top (popped first).
            // A pre-push membership check skips seeds whose (state,mask) was already
            // POPPED (marked visited) — they would die at the pop check anyway. Seeds
            // still un-popped are pushed normally: the first POP of a key wins (DFS
            // priority order), so marking at push time would reverse the winner among
            // co-resident duplicates — see the child-loop comment below.
            for (int i = seed.size() - 1; i >= 0; i--) {
                Config c = seed.get(i);
                long key = (((long) c.state) << 32) | (c.emptyMask & 0xFFFFFFFFL);
                if (containsKey(visitedSM, visitedMask, key)) continue;
                stack.push(c);
            }
            if (epochCtr == Integer.MAX_VALUE) { java.util.Arrays.fill(maskEpoch, 0); epochCtr = 1; }
            final int epoch = ++epochCtr;
            while (!stack.isEmpty()) {
                meter.tick();
                Config c = stack.pop();
                long key = (((long) c.state) << 32) | (c.emptyMask & 0xFFFFFFFFL);
                int slot = (int) (mix(key) & visitedMask);
                while (visitedSM[slot] != 0) {
                    if (visitedSM[slot] == key) { slot = -1; break; }
                    slot = (slot + 1) & visitedMask;
                }
                if (slot < 0) continue;
                visitedSM[slot] = key;
                if (++visitedCount * 2 > visitedMask) { visitedSM = growVisited(visitedSM); visitedMask = visitedSM.length - 1; }
                if (maskEpoch[c.state] != epoch) { maskEpoch[c.state] = epoch; maskBitset[c.state] = 0L; }
                maskBitset[c.state] |= 1L << c.emptyMask;
                out.add(c);
                // Per-kernel spike bound: kernelsTotal is only counted after
                // addState, so a single closure of a nested-counted bomb could
                // otherwise exhaust the heap on its own.
                if (out.size() > maxClosure) {
                    throw new IllegalStateException("pattern too large: TDFA ε-closure exceeds "
                            + maxClosure + " configs (" + c.state + " reached; raise -Dtdfa.max.closure)");
                }
                // Push children in REVERSE priority order. Same contract as the seeds:
                // the pre-push check skips only already-POPPED keys; co-resident
                // duplicates are all pushed (each needs its own tag history — the
                // first pop decides which survives) and resolved at pop time.
                int[] eps = epsOut[c.state];
                for (int i = eps.length - 1; i >= 0; i--) {
                    int idx = eps[i];
                    int to = nfa.epsTo[idx];
                    // Subsumption cut — the exact form of the empty-iteration cut.
                    // A re-arrival (to, newMask) is SUBSUMED when an earlier,
                    // higher-priority variant (to, m') with m' ⊆ newMask was already
                    // popped: at every position where the re-arrival's path is alive
                    // (newMask ⊆ posFlags), the earlier variant is alive too, and from
                    // the same NFA state produces the same continuations — re2j's pike
                    // VM realizes this by per-position pc dedup (its threads carry no
                    // deferred masks, so the FIRST alive thread to reach a pc wins).
                    // For nullable loop bodies the re-entry around the ε-cycle carries
                    // the cycle's accumulated assertion bits — a superset of the entry
                    // variant's — so empty iterations die here ((?:.*?9{0,}\\b){1,} on
                    // "99x" matches [0,0) like the refs, not [0,3)). Incomparable-mask
                    // re-arrivals survive ((?:^|$)+ needs both the BEGIN and END
                    // junction variants); that is the difference from a blanket
                    // state-only dedup, and it is what the position-aware tables
                    // downstream rely on. Masks are 6 bits: exact submask check over a
                    // per-state popped-mask bitset.
                    int edgeEmpty = nfa.epsEmptyMask[idx];
                    int newMask = c.emptyMask | edgeEmpty;
                    if (maskEpoch[to] == epoch && submaskPopped(maskBitset[to], newMask)) continue;
                    long childKey = (((long) to) << 32) | (newMask & 0xFFFFFFFFL);
                    if (containsKey(visitedSM, visitedMask, childKey)) continue;
                    int tag = nfa.epsTag[idx];
                    int newL;
                    if (tag == Tnfa.NO_TAG || tag < 0) {
                        newL = c.l;
                    } else {
                        newL = hist.intern(appendTag(hist.content(c.l), tag));
                    }
                    // In longest-match mode, extend UTree path with ALL non-zero tags
                    // (incl. ntags) — consumed only by the (dormant) BT19 §7 compare,
                    // so gated behind COMPUTE_PRECTABLES to skip dead work.
                    int newPath = c.path;
                    if (longest && COMPUTE_PRECTABLES && tag != Tnfa.NO_TAG) {
                        newPath = utree.extend(c.path, tag);
                    }
                    int childPri = !longest ? Math.max(c.pri, nfa.epsPri[idx]) : 0;
                    stack.push(new Config(to, c.regs, c.h, newL, newMask, childPri, newPath, c.origin));
                }
            }
            return out;
        }

        /** Membership test against an open-addressing primitive long set. */
        private static boolean containsKey(long[] table, int mask, long key) {
            int slot = (int) (mix(key) & mask);
            while (table[slot] != 0) {
                if (table[slot] == key) return true;
                slot = (slot + 1) & mask;
            }
            return false;
        }

        /** Double an open-addressing long set, rehashing all live keys. */
        private static long[] growVisited(long[] table) {
            long[] grown = new long[table.length << 1];
            int gMask = grown.length - 1;
            for (long k : table) {
                if (k == 0) continue;
                int s2 = (int) (mix(k) & gMask);
                while (grown[s2] != 0) s2 = (s2 + 1) & gMask;
                grown[s2] = k;
            }
            return grown;
        }

        /** 64-bit finalizer for hash-set slots (splitmix-style). */
        static long mix(long key) {
            key ^= key >>> 33;
            key *= 0xff51afd7ed558ccdL;
            key ^= key >>> 33;
            return key;
        }

        // ---------------- BT19 §7 longest-match closure (closure_gtop) ----------------

        /** Shared UTree across the entire DFA construction (BT19 §6). */
        UTree utree = new UTree();
        /** Tag heights: height[t] = nesting depth of tag t's group.
         *  Group g → tags 2g-1, 2g → height g. */
        final int[] tagHeights;
        /** Per-DFA-state prectable (flat int[n*n], packed via GtopCompare.packCell). */
        final List<int[]> statePrectables = new ArrayList<>();

        /**
         * BT19 POSIX ε-closure with compare()-based winner selection (§7 closure_gtop).
         *
         * <p>Uses a worklist: pop a config, explore its ε-edges. For each target
         * (state, mask), if new, add it; if existing, compare paths — replace if
         * the new path wins (and re-explore children).
         *
         * <p>Dual-path encoding: {@code l} (regular tags only, for regops/history)
         * and {@code path} (UTree node, all tags including ntags, for compare()).
         * ntags NEVER enter {@code l} — this prevents regops from generating
         * spurious SET_NIL ops.
         *
         * @param seed              stepped configs from stepOnSymbol
         * @param oldPrectable       parent DFA state's prectable (null for initial)
         * @param parentClosureSize  size of parent closure (for indexing oldPrectable)
         * @return the closure configs in priority order
         */
        List<Config> closureGtop(List<Config> seed, int[] oldPrectable, int parentClosureSize) {
            return epsilonClosure(seed);
        }

        /**
         * Compare an existing config (at index {@code existingIdx}) against a
         * challenger. Returns {@code l} from GtopCompare: {@code <0} = existing
         * wins, {@code >0} = challenger wins, {@code 0} = tie.
         *
         * When heights are equal (h1 == h2), returns 0 (defer to DFS order) —
         * leftprec alone is insufficient for cross-alternative comparisons
         * where ε-edge priority should decide.
         */
        int compareExisting(int existingIdx, Config challenger,
                                 List<Integer> originList, List<Integer> pathList,
                                 int[] oldPrectable, int parentClosureSize) {
            // TEMP: never replace — same as epsilonClosure DFS order.
            return 0;
        }

        /** Compute the prectable for this closure (O(n²) comparisons). */
        int[] computePrectable(List<Config> closure, int[] oldPrectable, int parentClosureSize) {
            int n = closure.size();
            int[] tbl = new int[n * n];
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) {
                    if (i == j) {
                        tbl[i * n + j] = GtopCompare.packCell(GtopCompare.MAX_RHO, 0);
                    } else {
                        long cmp = GtopCompare.compare(
                                closure.get(i).path, closure.get(j).path,
                                closure.get(i).origin, closure.get(j).origin,
                                utree, tagHeights, oldPrectable, parentClosureSize);
                        int h1 = GtopCompare.h1(cmp);
                        int h2 = GtopCompare.h2(cmp);
                        int l = (h1 == h2) ? 0 : GtopCompare.l(cmp);
                        tbl[i * n + j] = GtopCompare.packCell(GtopCompare.h1(cmp), l);
                    }
                }
            }
            return tbl;
        }

        /**
         * Compute per-state DFS arrival order (re2j's densePcs semantics) for the
         * closure rooted at {@code seed}, assuming the cursor's position-flags
         * are exactly {@code posMask}. Assertion ε-edges whose required bits
         * aren't subset of {@code posMask} are skipped — mirroring re2j's
         * runtime closure, which kills threads failing EMPTY_WIDTH before they
         * can claim a densePcs slot.
         *
         * <p>Unlike {@link #epsilonClosure} which tracks (state, mask) pairs to
         * preserve assertion-mask info, this does strict per-state dedup: each
         * NFA state is visited at most once, the first time any of its masks
         * would be popped.
         *
         * <p>The resulting order matches re2j's recursive DFS — a state's entire
         * subtree is fully explored before any of its lower-priority siblings.
         * Without this, patterns like ((^|.)* ) get the wrong priority: alt (^|.)
         * is re-visited with mask=BEGIN_TEXT via the loop-back, and dotState
         * ends up "before" accept in (state,mask) arrival order even though
         * re2j (which visits alt once) places it after.
         *
         * @param posMask runtime position-flags (subset of
         *        {@code BEGIN_TEXT|END_TEXT|WORD_BOUNDARY|NO_WORD_BOUNDARY});
         *        0xF ("all assertions hold") recovers the pre-position-aware
         *        behavior.
         * @return int[] indexed by NFA state; value = arrival index (0-based),
         *         or -1 for unreachable states (incl. states only reachable via
         *         assertion edges whose requirements aren't in posMask).
         */
        /** Scratch for computePerStateOrder: reused across the 64-mask loop and states. */
        private int[] psoOrder;
        private boolean[] psoVisited;
        private int[] psoStack;

        /**
         * Pike post-match thread pruning, determinized (Perl mode only): the
         * moment a live set contains an ACCEPT config, every config ranked
         * BELOW the first (highest-priority) alive accept is dead — any match
         * those threads reach is discarded by leftmost-first (re2j records
         * only the first Match), and non-matching continuations of them are
         * irrelevant. Without the prune, the walk extends past a recorded
         * accept via a lower-priority body and the runner's unconditional
         * lastAccept overwrite turns the result leftmost-LONGEST for that
         * window — fuzz round 11: {@code .+?\b[^\d]*} on "ß9" reported
         * [0,2) where re2j/sim/jdk report [0,1) (the lazy {@code .} body
         * matched '9' although ranked below the \b-gated accept at pos 1).
         *
         * <p>Configs ranked ABOVE the accept are kept: their later match
         * legitimately replaces the recorded one (the stop table's
         * higherPriSym NEVER_STOP exists for exactly them). Greedy shapes
         * are unaffected in practice — the body outranks the accept there.
         * The KERNEL itself is not pruned (state identity and the stop/final
         * tables see the full closure); only this live set's stepping input.
         */
        List<Config> pruneBelowAccept(List<Config> live) {
            if (longest) return live;
            int cut = -1;
            for (int i = 0; i < live.size(); i++) {
                if (live.get(i).state == nfa.accept) { cut = i; break; }
            }
            if (cut < 0 || cut == live.size() - 1) return live;   // nothing below the accept
            List<Config> pruned = new ArrayList<>(live.subList(0, cut + 1));
            return pruned;
        }

        /** In-place variant for freshly-built live lists. */
        void pruneBelowAcceptInPlace(List<Config> live) {
            if (longest) return;
            int cut = -1;
            for (int i = 0; i < live.size(); i++) {
                if (live.get(i).state == nfa.accept) { cut = i; break; }
            }
            if (cut >= 0 && cut < live.size() - 1) live.subList(cut + 1, live.size()).clear();
        }

        int[] computePerStateOrder(int[] seedStates, int posMask) { return computePerStateOrderDfs(seedStates, posMask); }

        int[] computePerStateOrder(List<Config> seed, int posMask) {
            int[] seedStates = new int[seed.size()];
            for (int i = 0; i < seed.size(); i++) seedStates[i] = seed.get(i).state;
            return computePerStateOrderDfs(seedStates, posMask);
        }

        private int[] computePerStateOrderDfs(int[] seedStates, int posMask) {
            if (psoOrder == null || psoOrder.length < nfa.stateCount) {
                psoOrder = new int[nfa.stateCount];
                psoVisited = new boolean[nfa.stateCount];
                psoStack = new int[Math.max(nfa.stateCount * 2, 64)];
            }
            int[] order = psoOrder;
            boolean[] visited = psoVisited;
            int[] stackArr = psoStack;
            java.util.Arrays.fill(order, 0, nfa.stateCount, -1);
            java.util.Arrays.fill(visited, 0, nfa.stateCount, false);
            int sp = 0;
            for (int i = seedStates.length - 1; i >= 0; i--) {
                int s = seedStates[i];
                if (!visited[s]) stackArr[sp++] = s;
            }
            int counter = 0;
            while (sp > 0) {
                int s = stackArr[--sp];
                if (visited[s]) continue;
                visited[s] = true;
                order[s] = counter++;
                int[] eps = epsOut[s];
                for (int i = eps.length - 1; i >= 0; i--) {
                    int idx = eps[i];
                    int required = nfa.epsEmptyMask[idx];
                    if ((required & ~posMask) != 0) continue;  // assertion fails at this position
                    int to = nfa.epsTo[idx];
                    if (!visited[to]) {
                        if (sp == stackArr.length) {
                            stackArr = java.util.Arrays.copyOf(stackArr, sp * 2);
                            psoStack = stackArr;
                        }
                        stackArr[sp++] = to;
                    }
                }
            }
            return order;
        }

        /**
         * Step every config in {@code configs} that has an outgoing symbol transition matching {@code a}.
         * Returns the stepped configs (with emptyMask reset to 0) and stores the intersection of
         * contributing source config masks into {@code requiredMaskOut[0]}.
         *
         * <p>{@code ownCount} is the number of leading configs belonging to the mask group being
         * stepped (the rest are subset-mask configs appended for DFA liveness by the caller —
         * see the subset-inclusion comment in {@code compile()}). Appended configs are NOT
         * priority competitors of the group's own configs: their true priority position is
         * elsewhere in the closure. They must therefore neither veto accept-suppression nor
         * be suppressed by it. Without this boundary, a pattern like
         * {@code ^(?:x*|y)} loses Perl leftmost-first semantics: the (ungated, mask-0) start
         * config is appended after the (BEGIN_TEXT-gated) accept config, the superset safety
         * check fails, and the lower-priority {@code y} branch survives to extend the match
         * ([0,1] instead of the correct [0,0] — the empty {@code x*} alternative accepts first).
         */
        List<Config> stepOnSymbol(List<Config> configs, long[] activeEdges, int[] requiredMaskOut, int ownCount, int ctxMask) {
            // Perl leftmost-first: the closure's configs are in priority-ordered DFS arrival order.
            // If any config has reached the accept state, find the FIRST (best-priority) such config
            // and consider suppressing transitions from configs added AFTER it.
            //
            // Suppression is safe only if every post-accept OWN config's emptyMask is a SUPERSET of the
            // accept config's emptyMask — meaning those lower-priority paths are gated by (at least)
            // the same assertions as the accept. Then wherever the accept fires (assertions hold),
            // the lower-priority transitions could also fire (so we MUST suppress to keep Perl
            // first-match); and wherever the accept doesn't fire (assertions don't hold), neither
            // can the lower-priority transitions (so suppression costs us nothing). When the rule
            // doesn't hold (e.g. accept requires `$` but a lower-priority alternative is ungated),
            // we must keep the lower-priority paths as fallback.
            int firstAcceptIdx = -1;
            int acceptEmptyMask = 0;
            boolean suppress = false;
            if (!longest) {
                for (int i = 0; i < ownCount; i++) {
                    Config c = configs.get(i);
                    if (c.state == nfa.accept) {
                        firstAcceptIdx = i;
                        acceptEmptyMask = c.emptyMask;
                        break;
                    }
                }
                // Pike-cut (context-scoped): the config list is a live-set for ONE
                // assertion context (ctxMask); when the accept config is alive in
                // THIS context, every lower-priority config is cut exactly like a
                // pike VM cuts threads below a match-recording thread — they can
                // never produce the answer. Contexts where the accept is dead
                // (acceptEmptyMask ⊄ ctxMask) keep the fallbacks: no accept fired
                // there, so nothing was cut.
                if (firstAcceptIdx >= 0 && (acceptEmptyMask & ~ctxMask) == 0) {
                    suppress = true;
                    if (debug) System.err.println("[step] PIKE-CUT accept@" + firstAcceptIdx + " mask=" + Integer.toBinaryString(acceptEmptyMask) + " ctx=" + Integer.toBinaryString(ctxMask));
                }
            }
            List<Config> out = new ArrayList<>();
            int intersection = Tnfa.BEGIN_TEXT | Tnfa.END_TEXT | Tnfa.WORD_BOUNDARY | Tnfa.NO_WORD_BOUNDARY
                    | Tnfa.ABS_BEGIN | Tnfa.ABS_END;
            boolean any = false;
            for (int ci = 0; ci < configs.size(); ci++) {
                if (suppress && ci > firstAcceptIdx) {
                    continue;  // pike-cut: lower-priority paths past the first live accept
                               // can never win once that accept fires in this context
                }
                Config c = configs.get(ci);
                for (int idx : symOut[c.state]) {
                    if ((activeEdges[idx >> 6] & (1L << (idx & 63))) != 0) {
                        // emptyMask resets on step — assertions are position-bound, gated via requiredMask.
                    out.add(new Config(nfa.symTo[idx], c.regs, c.l, HistTable.EMPTY_ID, 0, c.pri, c.path, ci));
                    intersection &= c.emptyMask;
                        any = true;
                    }
                }
            }
            requiredMaskOut[0] = any ? intersection : 0;
            return out;
        }

        /**
         * Allocate registers and emit ops for the transition. {@code vmaps} is per-source-state
         * to allow sharing registers across transitions out of the same state with identical RHS.
         * {@code nextReg} is bumped globally so registers are unique across states.
         */
        /** Shared per-addState "tag has history" bitsets: computed once for
         *  the incoming closure, reused across all tryMap candidates of that
         *  attempt (the per-candidate recompute zeroed and refilled every
         *  config's bits — 8% of cliff-compile time in JFR). */
        private long[][] hasHistShared;
        /** Primitive register bijection scratch for tryMap (replaces the boxed
         *  HashMap pair): mappings stamped with per-attempt epochs. */
        private int[] mapNewToOld, mapOldToNew, epochNew, epochOld;
        private int[] stampedRegs;
        private int stamp;
        /** Per-state class signatures (null for tagless), parallel to states. */
        private final List<int[]> stateClassIds = new ArrayList<>();
        /** Class signature of the pending closure (shared scratch — copied on append). */
        private int[] pendingClass;
        private long pendingClassHash;
        private int[] classScratch;
        private int pendingCanonLen;
        private final HashMap<Integer, Integer> classIdMap = new HashMap<>();

        /** Grown-on-demand scratch for transitionRegops' per-tag last-sign. */
        int[] transitionRegops(List<Config> configs, int sourceStateId) {
            meter.tick();
            // Tagless patterns (count-model usage, the giant bounded-repeat DFAs):
            // no registers exist, so transitions carry no ops — nothing to do.
            if (tags == 0) return EMPTY;
            // vmap is keyed (tag, sign) — a flat int[2*tags] per source state,
            // shared across that state's symbol transitions (register
            // assignments are stable per source). The former boxed
            // HashMap<Long,Integer> was a top profile entry after the
            // interning rework.
            while (sourceVmaps.size() <= sourceStateId) sourceVmaps.add(null);
            int[] vmap = sourceVmaps.get(sourceStateId);
            if (vmap == null) { vmap = new int[2 * tags]; sourceVmaps.set(sourceStateId, vmap); }
            List<int[]> opList = new ArrayList<>();
            // Per-tag LAST history sign: cached per hash-consed history id
            // (HistTable.lastSign) — formerly a rescan of each config's
            // sequence content, the transition-regop hot spot.
            for (int ci = 0; ci < configs.size(); ci++) {
                Config c = configs.get(ci);
                if (c.h == HistTable.EMPTY_ID) continue;
                int[] last = hist.lastSign(c.h, tags);
                int[] newRegs = c.regs.clone();
                for (int t = 1; t <= tags; t++) {
                    int l = last[t - 1];
                    if (l == 0) continue;   // tag has no history entry
                    int slot = 2 * (t - 1) + (l == TAG_POS ? 0 : 1);
                    int reg = vmap[slot];
                    if (reg == 0) reg = vmap[slot] = nextReg++;
                    // Per-transition dedup (paper "if op not in O"): opList is
                    // bounded by 2*tags distinct (reg, sign) ops — linear scan
                    // beats the former boxed HashSet.
                    boolean dup = false;
                    for (int[] o : opList) {
                        if (o[1] == reg && o[0] == (l == TAG_POS ? OP_SET_POS : OP_SET_NIL)) { dup = true; break; }
                    }
                    if (!dup) {
                        if (l == TAG_POS) opList.add(new int[]{OP_SET_POS, reg, 0});
                        else opList.add(new int[]{OP_SET_NIL, reg, 0});
                    }
                    newRegs[t - 1] = reg;
                }
                configs.set(ci, new Config(c.state, newRegs, c.h, c.l, c.emptyMask, c.pri, c.path, c.origin));
            }
            return flatten(opList);
        }

        /** Per-source-state (tag, sign) → register, flat int[2*tags]; null until first use. */
        final List<int[]> sourceVmaps = new ArrayList<>();

        int[] finalRegops(List<Config> configs) {
            if (tags == 0) return EMPTY;
            for (Config c : configs) {
                if (c.state == nfa.accept) return finalRegopsOf(c);
            }
            return EMPTY;
        }

        /**
         * Position-aware φ variants for one accepting state. A DFA state may
         * merge several accept configs of different priority whose zero-width
         * assertions differ; the tag-value winner is the highest-priority
         * accept config ALIVE under the runtime posFlags. When the winner is
         * the same config for all 64 masks the state is uniform (the common
         * case — the first accept config is unconditional) and nothing is
         * stored. Otherwise the per-mask winners' op lists (deduped) land in
         * {@code finalOpsVariants} with {@code finalMaskVariant} as the
         * [64] selector; materialization turns them into
         * {@code stateFinalOpsByMask}.
         */
        void computeFinalVariants(DfaStateBuilder sb, List<Config> cfgs) {
            int n = cfgs.size();
            int[] st = new int[n], mk = new int[n];
            for (int i = 0; i < n; i++) { st[i] = cfgs.get(i).state; mk[i] = cfgs.get(i).emptyMask; }
            computeFinalVariants(sb, st, mk, cfgs::get);
        }

        /** Packed (tagless) form: boxed closures are released, only
         *  (state, emptyMask) pairs remain — all the aliveness computation
         *  needs (finalRegopsOf returns empty ops when tags==0). */
        void computeFinalVariantsPacked(DfaStateBuilder sb, int[] pk) {
            int n = pk.length >> 1;
            int[] st = new int[n], mk = new int[n];
            for (int i = 0; i < n; i++) { st[i] = pk[i * 2]; mk[i] = pk[i * 2 + 1]; }
            computeFinalVariants(sb, st, mk, i -> null);
        }

        void computeFinalVariants(DfaStateBuilder sb, int[] st, int[] mk, java.util.function.IntFunction<Config> at) {
            int[] winner = new int[64];
            boolean uniform = true;
            for (int M = 0; M < 64; M++) {
                int w = -1;
                for (int i = 0; i < st.length; i++) {
                    if (st[i] != nfa.accept) continue;
                    if ((mk[i] & ~M) == 0) { w = i; break; }
                }
                winner[M] = w;
                if (M > 0 && w != winner[0]) uniform = false;
            }
            if (Boolean.getBoolean("tdfa.debug.finals") && tags > 0) {
                for (int i = 0; i < st.length; i++) {
                    if (st[i] != nfa.accept) continue;
                    Config c = at.apply(i);
                    if (c == null) continue;   // packed (tagless) kernel
                    StringBuilder h = new StringBuilder("cfg[" + i + "] mask=" + c.emptyMask + " l:");
                    int[] last = hist.lastSign(c.l, tags);
                    for (int t = 1; t <= tags; t++) {
                        h.append(" t").append(t).append(last[t - 1] == 0 ? "Ø" : (last[t - 1] == TAG_POS ? "P" : "N"));
                    }
                    System.err.println("  [finals] " + h + "  winner(M63)=" + winner[63] + " winner(M0)=" + winner[0]);
                }
            }
            if (uniform) return;
            List<int[]> variants = new ArrayList<>();
            int[] maskVariant = new int[64];
            for (int M = 0; M < 64; M++) {
                int w = winner[M];
                if (w < 0) { maskVariant[M] = -1; continue; }
                int[] opsArr = finalRegopsOf(at.apply(w));
                int v = -1;
                for (int k = 0; k < variants.size(); k++)
                    if (Arrays.equals(variants.get(k), opsArr)) { v = k; break; }
                if (v < 0) { variants.add(opsArr); v = variants.size() - 1; }
                maskVariant[M] = v;
            }
            sb.finalOpsVariants = variants.toArray(new int[0][]);
            sb.finalMaskVariant = maskVariant;
        }

        /** φ ops for ONE accept config: per tag, COPY its working register, or
         *  SET_POS/SET_NIL from its tag history. */
        int[] finalRegopsOf(Config c) {
            if (tags == 0) return EMPTY;
            List<int[]> opList = new ArrayList<>();
            int[] lastSign = hist.lastSign(c.l, tags);
            for (int t = 1; t <= tags; t++) {
                int dst = finalRegisters[t - 1];
                if (lastSign[t - 1] == 0) {
                    opList.add(new int[]{OP_COPY, dst, c.regs[t - 1]});
                } else {
                    int last = lastSign[t - 1];
                    if (last == TAG_POS) opList.add(new int[]{OP_SET_POS, dst, 0});
                    else opList.add(new int[]{OP_SET_NIL, dst, 0});
                }
            }
            return flatten(opList);
        }

        int[] flatten(List<int[]> opList) {
            int[] flat = new int[opList.size() * 3];
            for (int i = 0; i < opList.size(); i++) {
                int[] op = opList.get(i);
                flat[i * 3] = op[0]; flat[i * 3 + 1] = op[1]; flat[i * 3 + 2] = op[2];
            }
            return flat;
        }

        static final class AddResult { final int targetId; final int[] ops; AddResult(int t, int[] o) { targetId=t; ops=o; } }

        /**
         * Build the canonical (state-sorted, stable) key signature on this compiler's
         * scratch buffers. Counting sort by NFA state — O(n + stateCount) — replaces
         * the former ArrayList copy + TimSort, a per-call hotspot on large closures.
         */
        /** Reusable lookup key for {@link #stateIndex}: sig/hash reassigned per probe.
         *  Used ONLY for {@code get()} against the immutable stored {@link DfaStateKey}s —
         *  never inserted — so single-threaded reassignment is safe. Saves the
         *  ~2 KB sig-array + key allocation on every HIT (the dominant case on
         *  large determinizations: ~1.4 M lookups on the 234 K-state bomb). */
        private final ProbeKey probe = new ProbeKey();
        /** Scratch sig storage behind {@link #probe}; grown as needed, reused across calls. */
        private int[] probeSig = new int[64];

        /** Mutable lookup twin of {@link DfaStateKey}; equals() accepts stored keys. */
        private static final class ProbeKey {
            int[] sig;
            int len;
            int hash;
            @Override public boolean equals(Object o) {
                if (!(o instanceof DfaStateKey)) return false;
                DfaStateKey k = (DfaStateKey) o;
                return k.sig.length == len && Arrays.equals(sig, 0, len, k.sig, 0, len);
            }
            @Override public int hashCode() { return hash; }
        }

        /** Fill the reusable probe key with the ORDER-EXACT signature of {@code configs}.
         *  tryMap only ever merges closures with identical ordered (state, l)
         *  sequences (its first phase compares element i to element i), so the
         *  index key must discriminate by arrival order: the former canonical
         *  (state-sorted) key admitted every permutation of the same multiset,
         *  and tryMap linearly rejected them — the dominant compile cliff
         *  (nested counted repetitions produce many arrival orders of one
         *  multiset; buckets grew into the hundreds and every addState
         *  rescanned them with Arrays.equals over each l). Order-exact keys
         *  admit exactly the candidates that can pass tryMap's first phase;
         *  buckets hold only genuine register-permutation variants. */
        private void fillKeySig(List<Config> configs) {
            int n = configs.size();
            int total = 0;
            if (tags == 0) {
                // dense tagless sig: (state, emptyMask[, pri]) — l is always empty
                for (int i = 0; i < n; i++) total += 2 + (longest ? 1 : 0);
            } else {
                // l enters the signature as its HASH-CONSED ID — one int per
                // config instead of the full history content (interning makes
                // id equality exact content equality).
                for (int i = 0; i < n; i++) total += 3 + (longest ? 1 : 0);
            }
            if (probeSig.length < total) probeSig = new int[Math.max(total, probeSig.length * 2)];
            int j = 0;
            for (int i = 0; i < n; i++) {
                Config c = configs.get(i);
                probeSig[j++] = c.state;
                if (tags != 0) probeSig[j++] = c.l;
                probeSig[j++] = c.emptyMask;
                if (longest) probeSig[j++] = c.pri;
            }
            probe.sig = probeSig;
            probe.len = total;
            int h = 1;
            for (int i = 0; i < total; i++) h = 31 * h + probeSig[i];
            probe.hash = h;
            // Work meter: sig fill/copy/hash is O(sum |l|) real work — the
            // dominant cost on history-bloated compiles. Ticking per 64 ints
            // keeps the tick rate proportional to that work so the work budget
            // still bounds adversarial wall time (it was calibrated when the
            // interning scans dominated; those are gone).
            meter.tick(total >>> 6);
        }

        /** Same-sequence DFA states. Tagged buckets partition members by
         *  register-slice class signature (see addState); tagless buckets keep
         *  a flat list — every member is merge-equivalent. */
        static final class StateBucket {
            int[] members;                       // tagless
            final HashMap<Long, int[]> byClass = new HashMap<>();  // tagged
        }

        /** Canonical flat signature: the register value at every history-free
         *  (config, tag) position, renumbered by first appearance (config-major,
         *  tag ascending). A bijection M with M(rn_p) = ro_p for all positions
         *  exists IFF the two closures' equality patterns over positions match
         *  (rn_p == rn_q ⟺ ro_p == ro_q) — which is exactly equality of these
         *  canonical arrays. So hash-bucketing by this form is an EXACT
         *  compatibility filter: no viable candidate is ever skipped, and any
         *  hash-matched candidate passes tryMap's bijection phase by
         *  construction (only its ops-rewrite can still fail). */
        private int[] canonSignature(List<Config> configs, long[][] hasHist) {
            int n = configs.size();
            int max = n * tags;
            if (classScratch == null || classScratch.length < max) classScratch = new int[Math.max(max, 32)];
            classIdMap.clear();
            int next = 0, k = 0;
            for (int i = 0; i < n; i++) {
                Config c = configs.get(i);
                long[] bits = hasHist[i];
                for (int t = 0; t < tags; t++) {
                    if ((bits[t >>> 6] >>> (t & 63) & 1L) != 0) continue;
                    int r = c.regs[t];
                    Integer cid = classIdMap.get(r);
                    if (cid == null) { cid = next++; classIdMap.put(r, cid); }
                    classScratch[k++] = cid;
                }
            }
            pendingCanonLen = k;
            return classScratch;
        }

        private static long foldClass(int[] ids, int len) {
            long h = 1;
            for (int i = 0; i < len; i++) h = h * 0x100000001B3L + ids[i];
            return h;
        }

        AddResult addState(List<Config> configs, int[] ops, List<Config> seed) {
            meter.tick();
            fillKeySig(configs);
            StateBucket candidates = stateIndex.get(probe);
            if (candidates != null) {
                // Compute the shared has-history bitsets once for this closure.
                int words = (tags + 63) >>> 6;
                if (hasHistShared == null || hasHistShared.length < configs.size()
                        || hasHistShared[0].length < words) {
                    hasHistShared = new long[Math.max(configs.size(), 16)][Math.max(words, 1)];
                }
                for (int i = 0; i < configs.size(); i++) {
                    // Per-history-id cached bitsets (HistTable.bits): no fill,
                    // no content rescan.
                    hasHistShared[i] = hist.bits(configs.get(i).l, words);
                }
                // Order-exact signature: candidates have the identical ordered
                // (state, l) sequence; only their register assignment can differ.
                // Tagged buckets are further partitioned by CLASS SIGNATURE:
                // each config's regs slice over history-free tags, canonically
                // numbered by first appearance. A bijection can only exist when
                // slice-equality aligns (slice_i == slice_j ⟺ oslice_i ==
                // oslice_j for all i,j — otherwise the pair map is ill-defined
                // or non-injective), so the attempt visits only class-compatible
                // members instead of rescanning the whole bucket — that rescan
                // was the dominant compile cliff on permutation-heavy patterns
                // (78% of wall time in JFR).
                StateBucket bucket = (StateBucket) candidates;
                if (tags == 0) {
                    for (int cand : bucket.members) {
                        int[] mapped = tryMap(configs, states.get(cand), packedKernels.get(cand), ops);
                        if (mapped != null) return new AddResult(cand, mapped);
                    }
                } else {
                    int[] attemptCanon = canonSignature(configs, hasHistShared);
                    int canonLen = pendingCanonLen;
                    long ch = mix(foldClass(attemptCanon, canonLen));
                    int[] compatibles = bucket.byClass.get(ch);
                    if (compatibles != null && compatibles.length > 0) {
                        // Canon-equal members are interchangeable: the bijection
                        // succeeds by construction, and ops-rewrite coverage
                        // depends only on the ATTEMPT's registers — so success
                        // or failure (and the merged-into choice) is identical
                        // for every member. One probe suffices; scanning all
                        // canon-equal members was the residual quadratic.
                        int cand = compatibles[0];
                        int[] stored = stateClassIds.get(cand);
                        if (stored.length == canonLen && Arrays.equals(attemptCanon, 0, canonLen, stored, 0, canonLen)) {
                            int[] mapped = tryMap(configs, states.get(cand), packedKernels.get(cand), ops);
                            if (mapped != null) return new AddResult(cand, mapped);
                            // ops-rewrite failed: outcome is member-independent,
                            // fall through to append a new state.
                        }
                    }
                    pendingClass = Arrays.copyOf(attemptCanon, canonLen);
                    pendingClassHash = ch;
                }
                // All same-sequence states failed the register bijection: this
                // closure genuinely needs a new DFA state. Fall through.
            }
            int id = states.size();
            states.add(configs);
            packedKernels.add(null);
            // Seeds are consumed ONLY by the Perl-mode stopOnAccept computation,
            // and only for ACCEPTING states (compile() line ~663 gates on
            // anyAccept). Retaining them for all states cost ~22 M extra Config
            // objects on the 234 K-state bounded-repeat determinization — a
            // third of all live Configs — for zero readers. Null for the rest.
            boolean isAccept = false;
            for (Config c : configs) {
                if (c.state == nfa.accept) { isAccept = true; break; }
            }
            if (!longest && isAccept) {
                if (tags == 0) {
                    // tagless: consumers only read the seed STATES — pack them
                    int[] seedStates = new int[seed.size()];
                    for (int i = 0; i < seed.size(); i++) seedStates[i] = seed.get(i).state;
                    stateSeeds.add(seedStates);
                } else {
                    stateSeeds.add(seed);
                }
            } else {
                stateSeeds.add(null);
            }
            stateClassIds.add(pendingClass);
            if (candidates == null) {
                StateBucket fresh = new StateBucket();
                if (tags == 0) fresh.members = new int[]{id};
                else fresh.byClass.put(pendingClassHash, new int[]{id});
                stateIndex.put(new DfaStateKey(Arrays.copyOf(probe.sig, probe.len)), fresh);
            } else if (tags == 0) {
                ((StateBucket) candidates).members = appendInt(((StateBucket) candidates).members, id);
            } else {
                StateBucket b = (StateBucket) candidates;
                // Only the first member of a canon-equal class is ever probed
                // (see above) — don't grow the list.
                b.byClass.putIfAbsent(pendingClassHash, new int[]{id});
            }
            builders.add(new DfaStateBuilder(id));
            if (isAccept) accept.set(id);
            kernelsTotal += configs.size();
            if (states.size() > maxStates || kernelsTotal > maxKernelsTotal) {
                throw new IllegalStateException("pattern too large: TDFA determinization budget exceeded ("
                        + states.size() + " states, kernel total " + kernelsTotal
                        + "; caps " + maxStates + " states / " + maxKernelsTotal
                        + " — raise -Dtdfa.max.states / -Dtdfa.max.kernels)");
            }
            return new AddResult(id, ops);
        }

        static int[] appendInt(int[] arr, int v) {
            int[] out = new int[arr.length + 1];
            System.arraycopy(arr, 0, out, 0, arr.length);
            out[arr.length] = v;
            return out;
        }

        /**
         * Attempt to map a candidate closure to an existing state's closure by registering
         * a bijection on their register vectors. Returns rewritten ops if mapping succeeds,
         * null otherwise. Implements paper §3 {@code map} function.
         *
         * <p>Callers index closures by the ORDER-EXACT (state, l, emptyMask)
         * signature, so every candidate here already has the identical ordered
         * sequence — the former element-wise state/Arrays.equals(l) phase is
         * implied by DfaStateKey.equals and has been deleted (it cost a full
         * l-comparison sweep per candidate on permutation-heavy patterns).
         * The tagless branch still checks element-wise states only because
         * its callers may pass closures from unindexed paths.
         */
        int[] tryMap(List<Config> newConfigs, List<Config> oldConfigs, int[] oldPacked, int[] ops) {
            int size = newConfigs.size();
            if (oldPacked != null ? oldPacked.length != size * 2 : oldConfigs.size() != size) return null;
            // Tagless patterns: no registers exist, so the (empty) bijection is
            // the identity and ops rewrite is a no-op — but the element-wise
            // ORDER check below is still semantically load-bearing: two closures
            // with the same shape-key (canonical state-sorted multiset) can have
            // DIFFERENT DFS arrival orders, and in Perl mode arrival order IS
            // priority (stepOnSymbol suppression). Refusing to merge those is
            // what the pre-fast-path code did; keep it.
            if (tags == 0) {
                for (int i = 0; i < size; i++) {
                    int oldState = oldPacked != null ? oldPacked[i * 2] : oldConfigs.get(i).state;
                    if (newConfigs.get(i).state != oldState) return null;
                }
                return ops;
            }
            // Build register bijection M: newReg -> oldReg, M': oldReg -> newReg.
            // Primitive arrays with epoch stamps replace the former boxed
            // HashMaps — this loop was the dominant compile cost on
            // permutation-heavy patterns (32% getNode + 7% putVal in JFR).
            // Register values come from the GLOBAL register allocator (nextReg
            // grows during compilation; configs' regs arrays only cover the
            // tags they carry) — size scratch by the current universe.
            int numRegs = nextReg;
            if (mapNewToOld == null || mapNewToOld.length < numRegs) {
                mapNewToOld = new int[numRegs];
                mapOldToNew = new int[numRegs];
                epochNew = new int[numRegs];
                epochOld = new int[numRegs];
                stamp = 0;
            }
            stamp++;      // fresh epoch for this attempt
            int[] m = mapNewToOld, mp = mapOldToNew;
            int[] eN = epochNew, eO = epochOld;
            // Stamped new-side registers, for the O(pairs) remaining-pairs scan
            // below — the register universe is global and grows with the DFA
            // (tens of thousands), so scanning it per attempt was a cliff.
            // Each (config, tag) pair contributes at most one register.
            int maxPairs = size * tags;
            if (stampedRegs == null || stampedRegs.length < maxPairs) stampedRegs = new int[Math.max(maxPairs, 8)];
            int stamped = 0;
            // "Tag has transition-op history" bitsets: one pass over each
            // config's history sequence replaces the former tags × full-
            // sequence history() rescans per (config, tag) — the top
            // compile-time hot spot (410 of 678 overnight hang records were
            // this loop; the full history ARRAY was built to test only its
            // existence). Computed by the CALLER once per addState — shared
            // across all candidates of this attempt.
            long[][] hasHist = hasHistShared;
            for (int i = 0; i < size; i++) {
                if (meter != null) meter.tick();
                Config cn = newConfigs.get(i), co = oldConfigs.get(i);
                long[] bits = hasHist[i];
                for (int t = 0; t < tags; t++) {
                    if ((bits[t >>> 6] >>> (t & 63) & 1L) != 0) continue; // tag is set by transition op
                    int rn = cn.regs[t], ro = co.regs[t];
                    // A register may be new-side of one tag and old-side of
                    // another, so the two sides carry separate epoch arrays.
                    boolean mn = eN[rn] == stamp, mo = eO[ro] == stamp;
                    if (!mn && !mo) {
                        m[rn] = ro; eN[rn] = stamp;
                        mp[ro] = rn; eO[ro] = stamp;
                        stampedRegs[stamped++] = rn;
                    } else if (!mn || !mo || m[rn] != ro || mp[ro] != rn) {
                        return null;
                    }
                }
            }
            // Rewrite ops: replace each op's dst with M[dst]. Each consumed
            // pair is unstamped (the HashMap remove), so a second op hitting
            // the same dst fails — bijection violations, as before.
            List<int[]> rewritten = new ArrayList<>();
            for (int i = 0; i < ops.length; i += 3) {
                int op = ops[i], dst = ops[i + 1], src = ops[i + 2];
                if (eN[dst] != stamp) return null;
                int mapped = m[dst];
                if (eO[mapped] != stamp || mp[mapped] != dst) return null;
                rewritten.add(new int[]{op, mapped, src});
                eN[dst] = 0;
                eO[mapped] = 0;
            }
            // Prepend copy ops for remaining bijection pairs (stamped order —
            // first-stamp ascending; the pairs are mutually commutative, order
            // only affects the emitted op sequence deterministically).
            // M maps newReg -> oldReg. Existing state expects tag values in its oldReg slots;
            // the new state's transition just wrote them into newReg slots. Copy oldReg <- newReg.
            for (int p = 0; p < stamped; p++) {
                int newReg = stampedRegs[p];
                if (eN[newReg] != stamp) continue;
                int oldReg = m[newReg];
                if (eO[oldReg] != stamp) continue;
                if (newReg != oldReg) rewritten.add(0, new int[]{OP_COPY, oldReg, newReg});
            }
            // Topological sort: copy ops must come before any op that reads their src.
            topologicalSort(rewritten);
            return flatten(rewritten);
        }

        /** Stabilize copy chains so reads happen before writes clobber their source.
         *  COPYs that read from a register must execute before any op (COPY or POS/NIL)
         *  that writes to that register. */
        void topologicalSort(List<int[]> ops) {
            boolean changed = true;
            int guard = 0;
            while (changed && guard++ < ops.size() * ops.size()) {
                changed = false;
                for (int i = 0; i < ops.size(); i++) {
                    int[] op = ops.get(i);
                    if (op[0] != OP_COPY) continue;
                    int src = op[2];
                    // Check if any EARLIER op writes to src — if so, the COPY must
                    // move before it (to read the OLD value before it's clobbered).
                    for (int j = 0; j < i; j++) {
                        int[] earlier = ops.get(j);
                        if (earlier[1] == src) {
                            // Move COPY to position j, shift everything else right.
                            for (int k = i; k > j; k--) ops.set(k, ops.get(k - 1));
                            ops.set(j, op);
                            changed = true;
                            break;
                        }
                    }
                }
            }
        }

        int[] history(int[] seq, int t) {
            meter.tick();
            if (seq == null || seq.length == 0) return null;
            int count = 0;
            for (int v : seq) if (Math.abs(v) == t) count++;
            if (count == 0) return null;
            int[] out = new int[count];
            int j = 0;
            for (int v : seq) if (Math.abs(v) == t) out[j++] = (v > 0) ? TAG_POS : TAG_NIL;
            return out;
        }

        int[] appendTag(int[] seq, int tag) {
            if (seq == EMPTY || seq.length == 0) return new int[]{tag};
            int[] out = new int[seq.length + 1];
            System.arraycopy(seq, 0, out, 0, seq.length);
            out[seq.length] = tag;
            return out;
        }

        static final int[] EMPTY = new int[0];
        static final int TAG_POS = 1;
        static final int TAG_NIL = -1;
    }

    static final class Config {
        final int state;
        final int[] regs;
        /** Hash-consed history ids (HistTable): h = parent closure's l,
         *  carried across the symbol step; l = this closure's accumulated
         *  ε-history. Id 0 = empty sequence. */
        final int h;
        final int l;
        /** Zero-width assertion mask accumulated during the ε-closure that produced this config.
         *  Reset to 0 by {@link Compiler#stepOnSymbol}. */
        int emptyMask;
        /** Worst (highest) priority ε-edge taken to reach this config in the closure.
         *  Seed configs carry pri=0 (no edges taken). Always 0 in POSIX mode (unused).
         *  In Perl mode, used to suppress lower-priority paths past an accepting config. */
        final int pri;
        /** UTree node index — the tag-path prefix for POSIX comparison (BT19 §6).
         *  Carries ALL tags (including ntags) for compare(). Unused in Perl mode. */
        int path;
        /** Index of the parent config (in the parent DFA state's closure) that led to
         *  this config via stepOnSymbol. Used by compare() for cross-origin resolution.
         *  Unused in Perl mode. */
        int origin;
        Config(int state, int[] regs, int h, int l, int emptyMask) {
            this(state, regs, h, l, emptyMask, 0);
        }
        Config(int state, int[] regs, int h, int l, int emptyMask, int pri) {
            this(state, regs, h, l, emptyMask, pri, 0, 0);
        }
        Config(int state, int[] regs, int h, int l, int emptyMask, int pri, int path, int origin) {
            this.state = state; this.regs = regs; this.h = h; this.l = l; this.emptyMask = emptyMask;
            this.pri = pri; this.path = path; this.origin = origin;
        }
    }

    /** Canonical DFA state key: state ids + per-config (lookahead tags, emptyMask, pri).
     *  Two states with same key are candidates for {@code map} (register bijection).
     *  In Perl mode {@code includePri} adds per-config pri to the signature so that closures
     *  whose suppression behaviour would differ are not merged. */
         static final class DfaStateKey {
             final int[] sig;
             final int hash;
             DfaStateKey(int[] sig) { this.sig = sig; this.hash = Arrays.hashCode(sig); }
             @Override public boolean equals(Object o) {
                 return o instanceof DfaStateKey && Arrays.equals(sig, ((DfaStateKey) o).sig);
             }
         @Override public int hashCode() { return hash; }
     }

    static final class Range {
        final int lo, hi, target;
        int[] ops;  // non-final: rewritten in place by CFG optimization (BT22 §6.3)
        final int requiredMask;
        Range(int lo, int hi, int target, int[] ops, int requiredMask) {
            this.lo = lo; this.hi = hi; this.target = target; this.ops = ops; this.requiredMask = requiredMask;
        }
    }

    static final class DfaStateBuilder {
        final int id;
        final List<Range> ranges = new ArrayList<>();
        int[] finalOpsArr;  // populated during materialization
        /** Position-aware φ variants (deduped op lists); null = mask-uniform. */
        int[][] finalOpsVariants;
        /** [64] posFlags → variant index, or -1 (no accept config alive). */
        int[] finalMaskVariant;
        DfaStateBuilder(int id) { this.id = id; }
        void addRange(int lo, int hi, int target, int[] ops, int requiredMask) {
            ranges.add(new Range(lo, hi, target, ops, requiredMask));
        }
        void coalesce() {
            ranges.sort(Comparator.comparingInt(r -> r.lo));
            if (ranges.size() <= 1) return;
            List<Range> out = new ArrayList<>();
            Range cur = ranges.get(0);
            for (int i = 1; i < ranges.size(); i++) {
                Range next = ranges.get(i);
                if (next.lo == cur.hi + 1 && next.target == cur.target
                        && Arrays.equals(next.ops, cur.ops)
                        && next.requiredMask == cur.requiredMask) {
                    cur = new Range(cur.lo, next.hi, cur.target, cur.ops, cur.requiredMask);
                } else {
                    out.add(cur); cur = next;
                }
            }
            out.add(cur);
            ranges.clear();
            ranges.addAll(out);
        }
        /**
         * Sort ranges so that for the same lo, ranges with more assertion bits in requiredMask
         * come first. This ensures the runner tries assertion-gated transitions before ungated
         * ones — e.g. for a*(^a) at pos 0, the BEGIN_TEXT-gated transition (leading to accept)
         * must be tried before the mask=0 loop transition (which would skip past the accept).
         */
        void sortByMaskSpecificity() {
            ranges.sort((a, b) -> {
                int cmp = Integer.compare(a.lo, b.lo);
                if (cmp != 0) return cmp;
                int bc = Integer.compare(Integer.bitCount(b.requiredMask), Integer.bitCount(a.requiredMask));
                if (bc != 0) return bc;
                // dead markers precede live ranges at equal specificity: a
                // more-specific context's DEAD must block a less-specific
                // context's live range for the same symbol cell.
                return Integer.compare(a.target >= 0 ? 1 : 0, b.target >= 0 ? 1 : 0);
            });
        }
    }

    /**
     * Register-aware Moore's algorithm for tagged-DFA minimization (paper §6.2.2).
     *
     * <p>Two states are equivalent iff:
     * <ol>
     *   <li>Same accept bit, entry mask, accept mask, Perl stop-on-accept mask
     *       (behavioral attributes that affect runtime control flow);</li>
     *   <li>Same final-ops content (capture effects on accept);</li>
     *   <li>For every input range, transitions go to equivalent states with
     *       bit-identical transition-ops content.</li>
     * </ol>
     *
     * <p>Op sequences are interned to unique numeric IDs (paper's recommended
     * O(1) comparison strategy). Comparison may have false negatives —
     * non-identical but semantically equivalent op lists are treated as
     * different — but this only yields a suboptimal minimization, never an
     * incorrect one. Best results require applying after register optimizations
     * (not yet implemented), which can normalize op lists.
     *
     * <p><b>Range normalization:</b> states whose transitions have the same
     * per-codepoint behavior but different range boundaries (e.g. one state
     * has [(0..9), (10..MAX)] and another has [(0..99), (100..MAX)] with the
     * same targets) should still merge. We compute global breakpoints (the
     * union of every state's range boundaries) and rebuild each state's
     * transition signature on the global partition. This is the difference
     * between minimization working for large literal-alternation DFAs
     * (dictionary, lexer) vs. not working at all. If any state has overlapping
     * ranges (assertion-gated transitions), we conservatively fall back to the
     * unnormalized form for the whole DFA.
     *
     * <p>Complexity: O((n + R) · I) where R = total ranges and I = iterations
     * to fixpoint (bounded by n in the worst case, typically O(log n)).
     */
    static final class DfaMinimizer {
        final int n;
        final int[] stateMeta, stateBase, stateFinalOpsOff, ranges, ops;
        final int[] stateEntryMask, stateAcceptMask, stateStopOnAcceptMask;
        final int[] stateFinalOpsByMask;
        final boolean longest;
        /** Op-sequence interning: maps the byte content of an OP_END-terminated block to a unique int id. */
        final Map<OpSeq, Integer> opSeqIds = new HashMap<>();
        /** Cached op-sequence id per ops[] offset (lazily computed). -1 = not computed. */
        final int[] opsIdAt;

        /** Global breakpoints partitioning the codepoint space; sorted ascending, includes 0 and 0x110000 sentinel. */
        int[] globalBps;
        /** Per (state, global-bp-index): the state-range index covering that global range, or -1 if no range. */
        int[] stateRangeAt;
        /** True iff every state's ranges are non-overlapping (so per-bp lookup is well-defined). */
        boolean useNormalized;

        DfaMinimizer(int n, int[] stateMeta, int[] stateBase, int[] stateFinalOpsOff,
                     int[] ranges, int[] ops, int[] stateEntryMask, int[] stateAcceptMask,
                     int[] stateStopOnAcceptMask, int[] stateFinalOpsByMask, boolean longest) {
            this.n = n;
            this.stateMeta = stateMeta;
            this.stateBase = stateBase;
            this.stateFinalOpsOff = stateFinalOpsOff;
            this.ranges = ranges;
            this.ops = ops;
            this.stateEntryMask = stateEntryMask;
            this.stateAcceptMask = stateAcceptMask;
            this.stateStopOnAcceptMask = stateStopOnAcceptMask;
            this.stateFinalOpsByMask = stateFinalOpsByMask;
            this.longest = longest;
            this.opsIdAt = new int[ops.length];
            java.util.Arrays.fill(this.opsIdAt, -1);
            detectOverlapsAndInit();
        }

        /** Detect overlapping ranges; if any state has them, disable normalization (conservative fallback). */
        private void detectOverlapsAndInit() {
            useNormalized = true;
            outer:
            for (int s = 0; s < n; s++) {
                int base = stateBase[s];
                int count = rangeCount(stateMeta[s]);
                int prevHi = -1;
                for (int r = 0; r < count; r++) {
                    int o = (base + r) * 5;
                    int lo = ranges[o];
                    if (lo <= prevHi) { useNormalized = false; break outer; }
                    prevHi = ranges[o + 1];
                }
            }
            if (!useNormalized) return;
            computeGlobalBreakpoints();
            computeStateRangeMapping();
        }

        private void computeGlobalBreakpoints() {
            java.util.TreeSet<Integer> bps = new java.util.TreeSet<>();
            bps.add(0);
            bps.add(0x110000);  // sentinel upper bound (exclusive)
            for (int s = 0; s < n; s++) {
                int base = stateBase[s];
                int count = rangeCount(stateMeta[s]);
                for (int r = 0; r < count; r++) {
                    int o = (base + r) * 5;
                    bps.add(ranges[o]);
                    int hi = ranges[o + 1];
                    if (hi < 0x10FFFF) bps.add(hi + 1);
                }
            }
            globalBps = new int[bps.size()];
            int i = 0;
            for (int b : bps) globalBps[i++] = b;
        }

        /** Per state, per global bp, find the state-range index covering it. Linear merge scan. */
        private void computeStateRangeMapping() {
            int K = globalBps.length;
            stateRangeAt = new int[n * K];
            for (int s = 0; s < n; s++) {
                int base = stateBase[s];
                int count = rangeCount(stateMeta[s]);
                int rangeIdx = 0;
                int rowBase = s * K;
                for (int k = 0; k < K; k++) {
                    int cp = globalBps[k];
                    if (cp >= 0x110000) { stateRangeAt[rowBase + k] = -1; continue; }
                    while (rangeIdx < count && ranges[(base + rangeIdx) * 5 + 1] < cp) rangeIdx++;
                    if (rangeIdx < count) {
                        int o = (base + rangeIdx) * 5;
                        stateRangeAt[rowBase + k] = (ranges[o] <= cp) ? rangeIdx : -1;
                    } else {
                        stateRangeAt[rowBase + k] = -1;
                    }
                }
            }
        }

        /**
         * Return the unique numeric id for the OP_END-terminated op block starting at {@code off}.
         * Two blocks with bit-identical content return the same id (paper's O(1) comparison).
         */
        int opSeqId(int off) {
            if (off < 0 || off >= opsIdAt.length) return 0;
            int cached = opsIdAt[off];
            if (cached != -1) return cached;
            int p = off;
            while (p < ops.length && ops[p] != OP_END) p += 3;
            OpSeq key = new OpSeq(ops, off, p);
            Integer id = opSeqIds.get(key);
            if (id == null) { id = opSeqIds.size() + 1; opSeqIds.put(key, id); }
            opsIdAt[off] = id;
            return id;
        }

        /** Compute the partition (mapping old state id -> new state id) via Moore's algorithm. */
        int[] computePartition() {
            int[] partition = initialPartition();
            int groups = 0;
            for (int p : partition) groups = Math.max(groups, p + 1);
            if (groups == n) return partition;  // every state already unique; no merging possible

            boolean changed = true;
            int iter = 0;
            while (changed && iter < n + 5) {
                changed = false;
                Map<SigKey, Integer> newGroupMap = new HashMap<>();
                int[] newPartition = new int[n];
                int nextGroup = 0;
                for (int s = 0; s < n; s++) {
                    SigKey key = transSig(s, partition);
                    Integer g = newGroupMap.get(key);
                    if (g == null) { g = nextGroup++; newGroupMap.put(key, g); }
                    newPartition[s] = g;
                }
                if (!java.util.Arrays.equals(partition, newPartition)) {
                    changed = true;
                    partition = newPartition;
                }
                iter++;
            }
            return partition;
        }

        /** Initial partition: group states by per-state attributes (accept, final-ops, masks). */
        private int[] initialPartition() {
            int[] partition = new int[n];
            Map<SigKey, Integer> groupMap = new HashMap<>();
            int nextGroup = 0;
            for (int s = 0; s < n; s++) {
                SigKey key = attrSig(s);
                Integer g = groupMap.get(key);
                if (g == null) { g = nextGroup++; groupMap.put(key, g); }
                partition[s] = g;
            }
            return partition;
        }

        /** Per-state attribute signature: accept bit, final-ops id, masks. */
        SigKey attrSig(int s) {
            int extra = (!longest ? 1 : 0) + (stateFinalOpsByMask != null ? 1 : 0);
            int[] sig = new int[5 + extra];
            fillAttrs(sig, s, 0);
            return new SigKey(sig);
        }

        /** Fill the per-state attribute prefix into sig starting at index i. Returns new index. */
        int fillAttrs(int[] sig, int s, int i) {
            sig[i++] = stateMeta[s] & 1;
            sig[i++] = opSeqId(stateFinalOpsOff[s]);
            sig[i++] = stateEntryMask[s];
            sig[i++] = stateAcceptMask[s];
            sig[i++] = (stateMeta[s] >>> 1) & 0xFFFF;  // range count (structural disambiguator)
            if (!longest) {
                int h = 0;
                int baseSM = s * 64;
                for (int j = 0; j < 64; j++) h = h * 31 + stateStopOnAcceptMask[baseSM + j];
                sig[i++] = h;
            }
            if (stateFinalOpsByMask != null) {
                // Variant rows: states with different per-mask φ selections
                // (or different accept suppression) must never merge.
                int h = 0;
                int baseFM = s * 64;
                for (int j = 0; j < 64; j++) h = h * 31 + stateFinalOpsByMask[baseFM + j];
                sig[i++] = h;
            }
            return i;
        }

        /** Transition signature, normalized on global breakpoints when possible. */
        SigKey transSig(int s, int[] partition) {
            int extra = (!longest ? 1 : 0) + (stateFinalOpsByMask != null ? 1 : 0);
            int base = stateBase[s];
            int count = rangeCount(stateMeta[s]);
            int[] sig;
            int i;
            if (useNormalized) {
                int K = globalBps.length - 1;  // # of codepoint-covering ranges
                sig = new int[5 + extra + K * 3];
                i = fillAttrs(sig, s, 0);
                int rowBase = s * globalBps.length;
                for (int k = 0; k < K; k++) {
                    int rIdx = stateRangeAt[rowBase + k];
                    if (rIdx < 0) {
                        sig[i++] = -1; sig[i++] = 0; sig[i++] = 0;
                    } else {
                        int o = (base + rIdx) * 5;
                        int t = ranges[o + 2];
                        sig[i++] = (t == -1) ? -1 : partition[t];
                        sig[i++] = opSeqId(ranges[o + 3]);
                        sig[i++] = ranges[o + 4];
                    }
                }
            } else {
                // Unnormalized fallback: per-range (lo, hi, target_partition, opSeqId, requiredMask).
                sig = new int[5 + extra + count * 5];
                i = fillAttrs(sig, s, 0);
                for (int r = 0; r < count; r++) {
                    int o = (base + r) * 5;
                    int t = ranges[o + 2];
                    sig[i++] = ranges[o];                                  // lo
                    sig[i++] = ranges[o + 1];                              // hi
                    sig[i++] = (t == -1) ? -1 : partition[t];              // target's current partition
                    sig[i++] = opSeqId(ranges[o + 3]);                     // transition-ops content id
                    sig[i++] = ranges[o + 4];                              // requiredMask
                }
            }
            return new SigKey(sig);
        }
    }

    /** Wrapper around a slice of an int[] for use as a HashMap key with value equality. */
    static final class OpSeq {
        final int[] arr;
        final int off;
        final int end;  // exclusive
        final int hash;
        OpSeq(int[] arr, int off, int end) {
            this.arr = arr; this.off = off; this.end = end;
            int h = 1;
            for (int i = off; i < end; i++) h = h * 31 + arr[i];
            this.hash = h;
        }
        @Override public boolean equals(Object o) {
            if (!(o instanceof OpSeq)) return false;
            OpSeq that = (OpSeq) o;
            int len = end - off;
            if (len != that.end - that.off) return false;
            for (int i = 0; i < len; i++) if (arr[off + i] != that.arr[that.off + i]) return false;
            return true;
        }
        @Override public int hashCode() { return hash; }
    }

    /** int[] wrapper for HashMap keys with value equality (avoids storing Strings). */
    static final class SigKey {
        final int[] sig;
        final int hash;
        SigKey(int[] sig) { this.sig = sig; this.hash = java.util.Arrays.hashCode(sig); }
        @Override public boolean equals(Object o) {
            return o instanceof SigKey && java.util.Arrays.equals(sig, ((SigKey) o).sig);
        }
        @Override public int hashCode() { return hash; }
    }
}
