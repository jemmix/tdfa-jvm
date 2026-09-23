package io.github.jemmix.tdfa.tdfa;

import io.github.jemmix.tdfa.tnfa.Tnfa;

/**
 * Borsotti-Trofimovich 2022 TDFA(1): lookahead-TDFA with register indirection.
 * <p>
 * Faithful implementation of paper Algorithm 3 (determinization):
 * - epsilon_closure(B): DFS over ε-paths in priority order, recording tag sequences in l.
 * - step_on_symbol(s, a): follows symbol transitions; old l becomes new h.
 * - transition_regops: allocates one register per (tag, RHS) and emits SET_POS / SET_NIL.
 * - add_state: dedupe by (NFA states, lookahead tags, register vectors). {@code map}+topo_sort
 * is the paper's optimization for further state reduction; deferred.
 * - final_regops: emits final-register SET/COPY ops for the accepting quasi-transition.
 * <p>
 * Single-valued tags only (sufficient for j.u.r-style capturing groups).
 * <p>
 * Alphabet: equivalence-class partitioned. Each DFA state stores sorted (lo, hi, target, ops)
 * ranges; runtime does binary search. Collapses 65K chars to a handful of ranges per state
 * (RE2-style byte-class partitioning).
 */
public final class Tdfa {
    /**
     * Sentinel for "don't stop on accept" — distinct from 0 (= stop).
     */
    public static final int NEVER_STOP = 0x40; // sentinel bit above all real assertion bits (1|2|4|8|16|32)
    public static final int OP_SET_POS = 1;
    public static final int OP_SET_NIL = 2;
    public static final int OP_COPY = 3;
    public static final int OP_END = 0; // terminator for op blocks
    final int tagCount;
    final int groupCount;
    /**
     * Unmodifiable name&rarr;index map for named capturing groups (from the source pattern).
     */
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
     * bit 1 = BEGIN_TEXT, bit 2 = END_TEXT, bit 4 = WORD_BOUNDARY, bit 8 = NO_WORD_BOUNDARY,
     * bit 16 = ABS_BEGIN (\A), bit 32 = ABS_END (\z)
     */
    final int[] stateEntryMask;
    /**
     * Bit mask required to declare a match in this (accepting) state. Subset of
     * {@link #stateEntryMask}. Replaces the old pattern-level {@code hasEndAnchor} flag.
     */
    final int[] stateAcceptMask;
    /**
     * Mask required to take the start state at all — used to limit find() start positions.
     */
    final int startStateEntryMask;
    /**
     * True iff this TDFA was compiled for leftmost-longest semantics
     * (re2j {@code LONGEST_MATCH}): the runner keeps stepping past accepts to
     * find the longest match. False means Perl leftmost-first — the runner
     * stops at the first accept on the highest-priority path.
     */
    final boolean longestMatch;
    /**
     * Pike-cut hazard flag, meaningful only for pruned Perl-mode compiles
     * ({@link #compile(Tnfa, boolean)}): true iff the compile-time pike
     * cut deleted a steppable continuation below an alive accept under
     * some position-flags — the exact condition under which a whole-input
     * walk on this artifact could miss accepts ((a|ab) on {@code "ab"}:
     * the {@code b}-continuation sits below the accept and is cut). When
     * false, every cut removed only non-steppable configs and this
     * artifact is identical to the cut-free build, so whole walks on it
     * are exact. Always false for POSIX and cut-free
     * ({@linkplain #compileUnpruned unpruned}) compiles.
     */
    final boolean pikeCutMatters;
    final boolean multiline;
    /**
     * True iff the DFA was compiled with Unicode-aware shorthand ({@code (?u)}),
     * so {@code \b}/{@code \B} word-boundary checks must use the Unicode
     * word-character ranges in {@link #wordRanges} instead of ASCII-only.
     */
    final boolean unicodeWordBoundary;
    /**
     * Unicode {@code \w} ranges for runtime {@code \b} when {@link #unicodeWordBoundary} is true; null otherwise.
     */
    final int[] wordRanges;
    /**
     * Fixed-tag annotations (BT22 §6.4), forwarded from {@link Tnfa}. Null if no
     * tags were fixed. Otherwise 1-indexed: {@code fixedBase[t] != 0} means tag
     * {@code t} was omitted from the NFA and should be reconstructed at match time.
     */
    final int[] fixedBase;
    final int[] fixedOffset;
    /**
     * Position-aware Perl-mode stop-on-accept decision table.
     * Indexed as {@code stopOnAcceptMask[state * 64 + posFlags]} where {@code posFlags}
     * <p>
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

    // === Flat packed arrays (4 arrays total; per-match regs adds a 5th at runtime) ===
    /**
     * [state] -> finalOpsOff (offset into `ops`), 0 if none. Read only once per match.
     */
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
     * lo1, hi1, target1, opsOff1, requiredMask1, ...].
     * {@code requiredMask} is the assertion mask that must hold at the source position
     * for this transition to be live (intersection of source configs' masks).
     */
    final int[] ranges;
    /**
     * Per-entry running max of hi within each state, index-aligned with ranges
     * entries (entry i of state s at stateBase[s]+i). Enables lo-binary-search +
     * prefix-max-terminated backward walk — O(log cnt + overlap) range lookup.
     */
    final int[] entryHiPrefix;
    /**
     * Flat ops: [op, dst, src, ...] blocks terminated by OP_END=0. Transition ops + final ops share this array.
     */
    final int[] ops;
    /**
     * Lazily-materialized 2D expansion of {@link #stopMaskUniform}. Volatile so
     * the filled array is safely published to racing readers (a plain field
     * could expose default-value cells under the JMM); duplicate
     * materialization by racing threads is benign, mutation is never intended.
     */
    private volatile int[] stopMaskTableCache;
    /**
     * Lazily-computed {@link #posFlagDeps()} (benign race; -1 = not computed).
     */
    private int posFlagDepsCache = -1;
    Tdfa(int tagCount, int groupCount, java.util.Map<String, Integer> namedGroups, int registerCount, int finalRegBase, int startState,
                    int stateCount,
                    int[] stateMeta, int[] stateBase, int[] stateFinalOpsOff, int[] stateFinalOpsByMask, int[] ranges, int[] ops,
                    int[] entryHiPrefix,
                    int[] stateEntryMask, int[] stateAcceptMask, boolean longestMatch, int[] stopOnAcceptMask, byte[] stopMaskUniform,
                    boolean multiline,
                    boolean unicodeWordBoundary, int[] wordRanges, int[] fixedBase, int[] fixedOffset) {
        this(tagCount, groupCount, namedGroups, registerCount, finalRegBase, startState, stateCount,
                        stateMeta, stateBase, stateFinalOpsOff, stateFinalOpsByMask, ranges, ops, entryHiPrefix,
                        stateEntryMask, stateAcceptMask, longestMatch, stopOnAcceptMask, stopMaskUniform, multiline,
                        unicodeWordBoundary, wordRanges, fixedBase, fixedOffset, false);
    }

    Tdfa(int tagCount, int groupCount, java.util.Map<String, Integer> namedGroups, int registerCount, int finalRegBase, int startState,
                    int stateCount,
                    int[] stateMeta, int[] stateBase, int[] stateFinalOpsOff, int[] stateFinalOpsByMask, int[] ranges, int[] ops,
                    int[] entryHiPrefix,
                    int[] stateEntryMask, int[] stateAcceptMask, boolean longestMatch, int[] stopOnAcceptMask, byte[] stopMaskUniform,
                    boolean multiline,
                    boolean unicodeWordBoundary, int[] wordRanges, int[] fixedBase, int[] fixedOffset,
                    boolean pikeCutMatters) {
        this.tagCount = tagCount;
        this.groupCount = groupCount;
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
        this.longestMatch = longestMatch;
        this.pikeCutMatters = pikeCutMatters;
        this.stopOnAcceptMask = stopOnAcceptMask;
        this.stopMaskUniform = stopMaskUniform;
        this.multiline = multiline;
        this.unicodeWordBoundary = unicodeWordBoundary;
        this.wordRanges = wordRanges;
        this.fixedBase = fixedBase;
        this.fixedOffset = fixedOffset;
        // Well-formedness gate: every consumer (VM runner, search-DFA memo,
        // ASM emitter, minimizer) trusts these arrays. Violations must surface
        // here, at construction — not as a wrong match 2,000 lines away. Runs
        // BEFORE any array-indexing field read so every corruption (short
        // arrays, bad startState) reports as this gate's ISE, never a raw
        // AIOOBE out of the constructor.
        validate(startState, stateCount, stateMeta, stateBase, stateFinalOpsOff, stateFinalOpsByMask,
                        ranges, entryHiPrefix, ops, stateEntryMask, stateAcceptMask, registerCount, finalRegBase, tagCount);
        this.startStateEntryMask = stateEntryMask[startState];
    }

    /**
     * Bits whose flip changes any cell of {@code t} ([state*64 + posFlags]); null-safe.
     */
    private static int tableDeps(int[] t) {
        if (t == null) {
            return 0;
        }
        int deps = 0;
        int n = t.length / 64;
        for (int b = 1; b < 64; b <<= 1) {
            if ((deps & b) != 0) {
                continue;
            }
            for (int s = 0; s < n; s++) {
                int row = s * 64;
                for (int m = 0; m < 64; m++) {
                    if ((m & b) != 0) {
                        continue;
                    }
                    if (t[row + m] != t[row + (m | b)]) {
                        deps |= b;
                        break;
                    }
                }
                if ((deps & b) != 0) {
                    break;
                }
            }
        }
        return deps;
    }

    /**
     * Construction-time invariants of the packed flat arrays. O(states +
     * entries + ops) once per compile; never on the match path.
     *
     * <ul>
     *   <li>startState inside the state space; per-state arrays exactly
     *       {@code stateCount} long; hi-prefix table exactly one cell per
     *       range entry</li>
     *   <li>per-state range entries: in bounds, codepoint domain, lo ascending
     *       (the runners' binary search depends on it), prefix-max consistent</li>
     *   <li>transition targets within the state space (dead marker is exactly
     *       {@code -1}); ops offsets within ops and blocks OP_END-terminated</li>
     *   <li>assertion masks limited to the six defined bits; accept ⊆ entry</li>
     *   <li>final-register block {@code [finalRegBase, finalRegBase+tagCount)}
     *       fits the register file (dedicated final slots — coalescing finals
     *       with working registers corrupts the MatchResult readout)</li>
     * </ul>
     */
    private static void validate(int startState, int stateCount, int[] stateMeta, int[] stateBase, int[] stateFinalOpsOff,
                    int[] stateFinalOpsByMask,
                    int[] ranges, int[] entryHiPrefix, int[] ops,
                    int[] stateEntryMask, int[] stateAcceptMask,
                    int registerCount, int finalRegBase, int tagCount) {
        int entries = ranges.length / 5;
        if (startState < 0 || startState >= stateCount) {
            throw new IllegalStateException("tdfa: startState " + startState
                            + " outside state space [0," + stateCount + ")");
        }
        if (stateAcceptMask.length != stateCount) {
            throw new IllegalStateException("tdfa: stateAcceptMask length " + stateAcceptMask.length
                            + " != stateCount " + stateCount);
        }
        if (stateMeta.length != stateCount) {
            throw new IllegalStateException("tdfa: stateMeta length " + stateMeta.length
                            + " != stateCount " + stateCount);
        }
        if (stateBase.length != stateCount) {
            throw new IllegalStateException("tdfa: stateBase length " + stateBase.length
                            + " != stateCount " + stateCount);
        }
        if (stateFinalOpsOff.length != stateCount) {
            throw new IllegalStateException("tdfa: stateFinalOpsOff length " + stateFinalOpsOff.length
                            + " != stateCount " + stateCount);
        }
        if (stateEntryMask.length != stateCount) {
            throw new IllegalStateException("tdfa: stateEntryMask length " + stateEntryMask.length
                            + " != stateCount " + stateCount);
        }
        if (entryHiPrefix.length != entries) {
            throw new IllegalStateException("tdfa: entryHiPrefix length " + entryHiPrefix.length
                            + " != range entries " + entries);
        }
        for (int s = 0; s < stateCount; s++) {
            int meta = stateMeta[s];
            int cnt = rangeCount(meta);
            int base = stateBase[s];
            if (base < 0 || base + cnt > entries) {
                throw new IllegalStateException("tdfa: state " + s + " range base/count out of bounds"
                                + " (base=" + base + ", cnt=" + cnt + ", entries=" + entries + ")");
            }
            int prevLo = -1;
            int prefixHi = -1;
            for (int i = 0; i < cnt; i++) {
                int o = (base + i) * 5;
                int lo = ranges[o], hi = ranges[o + 1], target = ranges[o + 2], opsOff = ranges[o + 3], mask = ranges[o + 4];
                if (lo < 0 || hi > 0x10FFFF || lo > hi) {
                    throw new IllegalStateException("tdfa: state " + s + " entry " + i
                                    + " outside codepoint domain [" + lo + "," + hi + "]");
                }
                if (lo < prevLo) {
                    throw new IllegalStateException("tdfa: state " + s + " entries not lo-ascending at " + i);
                }
                prevLo = lo;
                if (target >= stateCount) {
                    throw new IllegalStateException("tdfa: state " + s + " entry " + i
                                    + " target " + target + " beyond state count " + stateCount);
                }
                if (target < -1) {
                    throw new IllegalStateException("tdfa: state " + s + " entry " + i
                                    + " target " + target + " < -1 (dead marker is exactly -1)");
                }
                if (opsOff != 0) {
                    checkOpsBlock(s, i, opsOff, ops, false, finalRegBase, tagCount);
                }
                if ((mask & ~0x3F) != 0) {
                    throw new IllegalStateException("tdfa: state " + s + " entry " + i + " unknown assertion-mask bits");
                }
                prefixHi = Math.max(prefixHi, hi);
                if (entryHiPrefix[base + i] != prefixHi) {
                    throw new IllegalStateException("tdfa: state " + s + " entry " + i + " prefix-max invariant broken");
                }
            }
            if ((stateEntryMask[s] & ~0x3F) != 0) {
                throw new IllegalStateException("tdfa: state " + s + " entry mask has unknown bits");
            }
            int fops = stateFinalOpsOff[s];
            if (fops != 0 && (fops < 0 || fops >= ops.length)) {
                throw new IllegalStateException("tdfa: state " + s + " final-ops offset out of bounds");
            }
            if (fops != 0) {
                checkOpsBlock(s, -1, fops, ops, true, finalRegBase, tagCount);
            }
        }
        if (stateFinalOpsByMask != null) {
            if (stateFinalOpsByMask.length != stateCount * 64) {
                throw new IllegalStateException("tdfa: final-ops-by-mask table must be stateCount*64");
            }
            for (int i = 0; i < stateFinalOpsByMask.length; i++) {
                int cell = stateFinalOpsByMask[i];
                // -1 = no accept under that posFlags; otherwise an ops offset
                // (0 = the reserved empty block: accept fires, no ops).
                if (cell < -1 || cell >= ops.length) {
                    throw new IllegalStateException("tdfa: final-ops-by-mask cell out of bounds: " + cell);
                }
            }
        }
        if (tagCount > 0 && (finalRegBase < 0 || finalRegBase + tagCount > registerCount)) {
            throw new IllegalStateException("tdfa: final-register block [" + finalRegBase
                            + "," + (finalRegBase + tagCount) + ") exceeds register file of " + registerCount);
        }
    }

    /**
     * Structural check of one ops block at {@code opsOff}: in bounds and
     * OP_END-terminated on its stride-3 grid (an unterminated block would
     * otherwise run off {@code ops} as a bare AIOOBE far from the corruption).
     * For transition blocks ({@code isFinal == false}) with tags: finals are
     * final-ops-only — transition ops writing the final block would let dead
     * paths clobber accept-time values (runners apply φ eagerly at
     * accept-record).
     */
    private static void checkOpsBlock(int s, int i, int opsOff, int[] ops,
                    boolean isFinal, int finalRegBase, int tagCount) {
        if (opsOff < 0 || opsOff >= ops.length) {
            throw new IllegalStateException("tdfa: state " + s + (isFinal ? " final-ops" : " entry " + i)
                            + " ops offset out of bounds");
        }
        int j = opsOff;
        while (true) {
            if (j >= ops.length) {
                throw new IllegalStateException("tdfa: state " + s + (isFinal ? " final-ops" : " entry " + i)
                                + " ops block at " + opsOff + " not OP_END-terminated within ops");
            }
            if (ops[j] == OP_END) {
                break;
            }
            if (j + 2 >= ops.length) {
                throw new IllegalStateException("tdfa: state " + s + (isFinal ? " final-ops" : " entry " + i)
                                + " ops block at " + opsOff + " not OP_END-terminated within ops");
            }
            int dst = ops[j + 1];
            if (!isFinal && tagCount > 0 && dst >= finalRegBase && dst < finalRegBase + tagCount) {
                throw new IllegalStateException("tdfa: state " + s + " entry " + i
                                + " transition op writes final register " + dst
                                + " — final block is final-ops-only");
            }
            j += 3;
        }
    }

    /**
     * Unpack range count from packed stateMeta.
     */
    public static int rangeCount(int meta) {
        return (meta >>> 1) & 0xFFFF;
    }

    /**
     * Compile with Perl leftmost-first semantics (the ecosystem default).
     */
    public static Tdfa compile(Tnfa nfa) {
        return compile(nfa, false);
    }

    /**
     * Compiles a TNFA to a TDFA.
     *
     * @param longestMatch true for leftmost-longest, false for leftmost-first.
     */
    public static Tdfa compile(Tnfa nfa, boolean longestMatch) {
        return new TdfaCompiler(nfa, longestMatch).compile();
    }

    /**
     * Compile with a transparency hook receiving stage timings/decisions (may be {@code null}).
     */
    public static Tdfa compile(Tnfa nfa, boolean longestMatch,
                    io.github.jemmix.tdfa.core.CompileObserver observer) {
        return new TdfaCompiler(nfa, longestMatch, false).compile(observer);
    }

    /**
     * Ledger variant of {@link #compile(Tnfa, boolean, CompileObserver)}:
     * the meter comes from the caller's compile ledger ({@link
     * WorkMeter#fork(long)}) — its budget is the per-attempt cap and its
     * ticks debit the shared pool, so a compile's determinization attempts
     * stay within one compile CPU budget.
     */
    public static Tdfa compile(Tnfa nfa, boolean longestMatch,
                    io.github.jemmix.tdfa.core.CompileObserver observer,
                    WorkMeter sharedMeter) {
        return new TdfaCompiler(nfa, longestMatch, false, sharedMeter).compile(observer);
    }

    /**
     * Compile WITHOUT the Perl pike cut: transitions follow every alive
     * config, including lower-priority continuations past an accept, so the
     * artifact supports whole-input walks ({@code matchWhole}) — an accept
     * config alive at end-of-input is a full match even when a
     * higher-priority alternative accepted earlier (e.g. {@code (a|ab)} on
     * {@code "ab"}).
     *
     * <p>Use {@link #pikeCutMatters()} on the PRUNED artifact of the same
     * NFA to decide whether this cut-free form is needed: false means the
     * pruned artifact is identical to this build and may serve whole
     * matching; true means whole matching needs this form.
     */
    public static Tdfa compileUnpruned(Tnfa nfa, boolean longestMatch,
                    io.github.jemmix.tdfa.core.CompileObserver observer) {
        return compileUnpruned(nfa, longestMatch, observer,
                        new WorkMeter(Budgets.compileComputeTicks()));
    }

    /**
     * Ledger variant of {@link #compileUnpruned(Tnfa, boolean, CompileObserver)}
     * (see {@link #compile(Tnfa, boolean, CompileObserver, WorkMeter)}).
     */
    public static Tdfa compileUnpruned(Tnfa nfa, boolean longestMatch,
                    io.github.jemmix.tdfa.core.CompileObserver observer,
                    WorkMeter sharedMeter) {
        return new TdfaCompiler(nfa, longestMatch, true, sharedMeter).compile(observer);
    }

    // ===== public read accessors (fields are package-private; asm generation
    // and external consumers read through these) =====
    //
    // Defensive-copy policy (immutability, 2026-09): every array accessor
    // returns a CLONE. The artifact is shared across threads and its flat
    // arrays are its entire semantics; a caller mutating a returned array
    // would corrupt every engine built on this Tdfa. All in-package
    // consumers (TdfaRunner, DfaMinimizer, the compiler) read the fields
    // directly and are unaffected; external consumers pay one copy per
    // accessor call — engine emission and construction are the only callers
    // and each runs once per compile. The accessors marked
    // {@code @EmittedSurface} are additionally invoked by name from
    // generated engine <init>s (see EmittedSurfaceConformanceTest).

    /**
     * Full 2D stop table for external consumers. Materializes (once) from the
     * uniform tier if needed; returns null in POSIX mode (no reader may call).
     * Defensive copy: the materialized table is cached internally; callers
     * get their own array (the cache stays pristine for the next caller).
     */
    @io.github.jemmix.tdfa.core.EmittedSurface
    public int[] stopOnAcceptMask() {
        if (stopOnAcceptMask != null) {
            return stopOnAcceptMask.clone();
        }
        byte[] u = stopMaskUniform;
        if (u == null) {
            return null;
        }
        int[] cache = stopMaskTableCache;
        if (cache == null) {
            cache = new int[u.length * 64];
            for (int s = 0; s < u.length; s++) {
                java.util.Arrays.fill(cache, s * 64, s * 64 + 64, u[s] != 0 ? NEVER_STOP : 0);
            }
            stopMaskTableCache = cache;
        }
        return cache.clone();
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
        if (deps >= 0) {
            return deps;
        }
        deps = 0;
        for (int m : stateEntryMask) {
            deps |= m;
        }
        for (int m : stateAcceptMask) {
            deps |= m;
        }
        for (int i = 4; i < ranges.length; i += 5) {
            deps |= ranges[i];
        }
        deps |= tableDeps(stopOnAcceptMask());
        deps |= tableDeps(stateFinalOpsByMask());
        posFlagDepsCache = deps;
        return deps;
    }

    /**
     * Position-aware final-ops table ({@code [state*64+posFlags]} → offset, -1 = accept
     * suppressed), or null when every accepting state is mask-uniform. Defensive copy.
     */
    public int[] stateFinalOpsByMask() {
        return stateFinalOpsByMask == null ? null : stateFinalOpsByMask.clone();
    }

    /**
     * Number of capture tags (2 per group, 1-indexed).
     */
    public int tagCount() {
        return tagCount;
    }

    /**
     * Number of capturing groups (excluding group 0).
     */
    public int groupCount() {
        return groupCount;
    }

    /**
     * Unmodifiable name&rarr;index map for named capturing groups.
     */
    public java.util.Map<String, Integer> namedGroups() {
        return namedGroups;
    }

    /**
     * Total register count (working + final blocks).
     */
    public int registerCount() {
        return registerCount;
    }

    /**
     * Offset of the final-register block within the runtime register file.
     */
    public int finalRegBase() {
        return finalRegBase;
    }

    /**
     * Number of DFA states.
     */
    public int stateCount() {
        return stateCount;
    }

    /**
     * Per-state packed metadata: accept bit + range count (see {@link #rangeCount}). Defensive copy.
     */
    @io.github.jemmix.tdfa.core.EmittedSurface
    public int[] stateMeta() {
        return stateMeta.clone();
    }

    /**
     * Per-state base index into {@link #ranges()}. Defensive copy.
     */
    @io.github.jemmix.tdfa.core.EmittedSurface
    public int[] stateBase() {
        return stateBase.clone();
    }

    /**
     * Per-state final-ops offset into {@link #ops()}, 0 if none. Defensive copy.
     */
    public int[] stateFinalOpsOff() {
        return stateFinalOpsOff.clone();
    }

    /**
     * Flat transition ranges: [lo, hi, target, opsOff, requiredMask] quintets. Defensive copy.
     */
    @io.github.jemmix.tdfa.core.EmittedSurface
    public int[] ranges() {
        return ranges.clone();
    }

    /**
     * Flat register ops: [op, dst, src] triplets, blocks terminated by {@link #OP_END}. Defensive copy.
     */
    public int[] ops() {
        return ops.clone();
    }

    /**
     * Per-state entry assertion masks (BEGIN_TEXT/END_TEXT/WORD_BOUNDARY/...), or null. Defensive copy.
     */
    @io.github.jemmix.tdfa.core.EmittedSurface
    public int[] stateEntryMask() {
        return stateEntryMask == null ? null : stateEntryMask.clone();
    }

    /**
     * Per-state accept assertion masks (subset of {@link #stateEntryMask()}), or null. Defensive copy.
     */
    @io.github.jemmix.tdfa.core.EmittedSurface
    public int[] stateAcceptMask() {
        return stateAcceptMask == null ? null : stateAcceptMask.clone();
    }

    /**
     * True iff compiled for leftmost-longest (LONGEST_MATCH) semantics.
     */
    public boolean longestMatch() {
        return longestMatch;
    }

    /**
     * {@code (?m)} — {@code ^}/{@code $} at line boundaries.
     */
    public boolean multiline() {
        return multiline;
    }

    /**
     * Unicode-aware word boundary ({@code (?u)}).
     */
    public boolean unicodeWordBoundary() {
        return unicodeWordBoundary;
    }

    /**
     * Word-character ranges for Unicode-aware {@code \b}, or {@code null}. Defensive copy.
     */
    @io.github.jemmix.tdfa.core.EmittedSurface
    public int[] wordRanges() {
        return wordRanges == null ? null : wordRanges.clone();
    }

    /**
     * Fixed-tag base annotations (BT22 §6.4), or {@code null} when none fixed. Defensive copy.
     */
    @io.github.jemmix.tdfa.core.EmittedSurface
    public int[] fixedBase() {
        return fixedBase == null ? null : fixedBase.clone();
    }

    /**
     * Fixed-tag offset annotations (BT22 §6.4), or null. Defensive copy.
     */
    @io.github.jemmix.tdfa.core.EmittedSurface
    public int[] fixedOffset() {
        return fixedOffset == null ? null : fixedOffset.clone();
    }

    /**
     * For pruned Perl-mode compiles: whether the pike cut deleted a
     * steppable continuation (see {@link Tdfa#pikeCutMatters}); false
     * means whole-input walks on this artifact are exact. Constant false
     * otherwise.
     */
    public boolean pikeCutMatters() {
        return pikeCutMatters;
    }

    // ===== compile-knob policy =====
    //
    // Every tdfa.* knob that steers COMPILATION is read once per
    // compilation (at the pipeline stage that consumes it), never frozen in
    // a static initializer: setting a property takes effect on the next
    // compile in the same JVM, and tests can vary knobs without forking.
    // Knobs and their sites:
    //   tdfa.budget.compile.memory / .compute               (budgets, Budgets —
    //   tdfa.budget.runtime.memory                            all caps derive)
    //   tdfa.nominimize, tdfa.minimize.max, tdfa.noregopt, tdfa.regopt.max,
    //   tdfa.debug, tdfa.debug.closure, tdfa.debug.finals          (compile)
    //   tdfa.engine, tdfa.gen.debug                                 (facade, per compile)
    // The derived determinization caps (states/kernels/closure/cfg-edges/
    // norm-cells, the search-DFA memo caps) are
    // all linear functions of the two compile budgets / the runtime budget
    // through the weight model (BudgetWeights) — the former direct cap
    // properties (tdfa.max.*) are gone. The only frozen reads left are
    // RUNTIME diagnostics on hot loops (TdfaRunner.WTRACE) and the
    // tdfa.asm.dump emission switch — see those sites. tdfa.debug
    // previously had THREE readers at TWO different timings (frozen in
    // Tdfa, frozen again in TdfaCompiler.Builder, fresh in Tnfa) — the
    // split produced partial debug output whenever the property was set
    // after class init; all three now read fresh, once per compile.

}