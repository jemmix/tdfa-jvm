package io.github.jemmix.tdfa.core.dfa;

import io.github.jemmix.tdfa.core.ast.Alphabet;
import io.github.jemmix.tdfa.core.tnfa.Tnfa;

import java.util.Arrays;

/**
 * Construction-time table builders + literal-needle analysis for
 * TdfaRunner (statics, no instance state).
 */
final class RunnerTables {
    /**
     * Longest prefix chain detected ({@link #detectPrefixChain}): the walk
     * is bounded by state count, so detection stays O(32) even on giant
     * chain DFAs; every real log-shape needle (ip=, user_id=, path=) is far
     * shorter. Single source of truth — {@link TdfaRunner#detectPrefixChain}
     * exposes it to the ASM backend's emit-time mirror. Caps chain STEPS
     * (fold/class steps included), not needle length.
     */
    static final int PREFIX_NEEDLE_MAX = 32;

    /**
     * The only zero-width gates a prefix chain may carry: word-boundary
     * bits, on the start state's entry mask or on chain exits' required
     * masks. The candidate walks evaluate them at the candidate position,
     * so the needle stays a complete candidate enumeration. Every other
     * gate (^, \A, $-family) restricts where matches may begin in ways the
     * restart ladder owns — those still end the chain.
     */
    static final int WORD_GATE = Tnfa.WORD_BOUNDARY | Tnfa.NO_WORD_BOUNDARY;

    private RunnerTables() {
    }

    /**
     * Build flat per-state range-index lookup: {@code [state * limit + c] → range index} (-1 = dead).
     */
    static int[] buildAsciiRangeFlat(Tdfa tdfa, int limit) {
        int[] sm = tdfa.stateMeta, rg = tdfa.ranges;
        int[] flat = new int[tdfa.stateCount * limit];
        Arrays.fill(flat, -1);
        for (int s = 0; s < tdfa.stateCount; s++) {
            int meta = sm[s];
            int base = tdfa.stateBase[s], cnt = (meta >>> 1) & 0xFFFF;
            for (int i = 0; i < cnt; i++) {
                int o = (base + i) * 5;
                int lo = Math.max(rg[o], 0);
                int hi = Math.min(rg[o + 1], limit - 1);
                for (int c = lo; c <= hi; c++) {
                    flat[s * limit + c] = i;
                }
            }
        }
        return flat;
    }

    /**
     * Build flat per-state target lookup: {@code [state * limit + c] → target state}
     * (-1 = dead). {@code limit} is 256 (Latin-1) for DFAs under
     * {@code LATIN1_MAX_STATES} states, else 128. Codepoints 128..255 are single
     * UTF-16 units and never surrogate halves, so indexing them directly is exact.
     */
    static int[] buildAsciiTarget(Tdfa tdfa, int limit) {
        int[] sm = tdfa.stateMeta, rg = tdfa.ranges;
        int[] flat = new int[tdfa.stateCount * limit];
        Arrays.fill(flat, -1);
        for (int s = 0; s < tdfa.stateCount; s++) {
            int meta = sm[s];
            int base = tdfa.stateBase[s], cnt = (meta >>> 1) & 0xFFFF;
            for (int i = 0; i < cnt; i++) {
                int o = (base + i) * 5;
                int lo = Math.max(rg[o], 0);
                int hi = Math.min(rg[o + 1], limit - 1);
                int target = rg[o + 2];
                for (int c = lo; c <= hi; c++) {
                    flat[s * limit + c] = target;
                }
            }
        }
        return flat;
    }

    /**
     * Bitset of states with accept capability (stateMeta bit 0 set). This is an
     * over-approximation for the generic path — a state may be only conditionally
     * accepting (non-zero acceptMask), but for the multi-state no-match pre-check
     * we want to err on the side of "might accept" so we never skip a real match.
     */
    static int[] buildAcceptBits(Tdfa tdfa) {
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
     * Check if all states have pairwise-disjoint ranges (no overlapping ranges).
     */
    static boolean checkRangesDisjoint(Tdfa tdfa) {
        int[] sm = tdfa.stateMeta, rg = tdfa.ranges;
        long[] sortBuf = null;
        for (int s = 0; s < tdfa.stateCount; s++) {
            int meta = sm[s];
            int base = tdfa.stateBase[s], cnt = (meta >>> 1) & 0xFFFF;
            if (cnt < 2) {
                continue;
            }
            // Fast path: ranges are emitted sorted by lo at materialization
            // (sortByMaskSpecificity is the only reorderer) — one O(cnt) scan.
            boolean sortedByLo = true;
            for (int i = 1; i < cnt; i++) {
                if (rg[(base + i) * 5] < rg[(base + i - 1) * 5]) {
                    sortedByLo = false;
                    break;
                }
            }
            if (!sortedByLo) {
                // Pack (lo << 32)|hi and sort — O(cnt log cnt); a pairwise
                // O(cnt²) check would be significant for wide Unicode
                // classes (~1369 ranges).
                if (sortBuf == null || sortBuf.length < cnt) {
                    sortBuf = new long[Math.max(cnt, 64)];
                }
                for (int i = 0; i < cnt; i++) {
                    int o = (base + i) * 5;
                    sortBuf[i] = ((long) rg[o] << 32) | (rg[o + 1] & 0xFFFFFFFFL);
                }
                Arrays.sort(sortBuf, 0, cnt);
                int maxHi = (int) sortBuf[0];
                for (int i = 1; i < cnt; i++) {
                    int lo = (int) (sortBuf[i] >>> 32);
                    if (lo <= maxHi) {
                        return false;
                    } // overlaps the interval holding maxHi
                    int hi = (int) sortBuf[i];
                    if (hi > maxHi) {
                        maxHi = hi;
                    }
                }
                continue;
            }
            // Sorted by lo: adjacent scan with running max-hi (a long early range
            // can overlap several later ones, so plain prev-pair checks aren't enough).
            int maxHi = rg[base * 5 + 1];
            for (int i = 1; i < cnt; i++) {
                int o = (base + i) * 5;
                if (rg[o] <= maxHi) {
                    return false;
                }
                if (rg[o + 1] > maxHi) {
                    maxHi = rg[o + 1];
                }
            }
        }
        return true;
    }

    /**
     * indexOf for the literal needle that respects the alphabet: a hit is
     * real only if it starts at a codepoint boundary (not the low half of a
     * pair) and does not end on the high half of a pair. Raw indexOf sees
     * UTF-16 units and would otherwise accept unit sequences that overlap
     * pair halves — e.g. needle "a\uD800" on input "a\uD800\uDFFF".
     *
     * <p>The explicitly given {@code from} is honored as-is even when it is
     * itself a pair interior: JDK, re2j and the general walk all match a
     * lone-low needle AT a start the caller hands them (the walk decodes
     * the lone low unit and matches), and a needle-shaped pattern must
     * answer exactly like its non-needle equivalent. End-overlap rejection
     * still applies at every hit including {@code from}: the walk decodes
     * FORWARD, so a needle ending on a high half paired with the next unit
     * never matches from any start.
     */
    static int literalIndexOf(String s, String needle, int from, boolean codepointBoundaries) {
        int idx = s.indexOf(needle, from);
        while (idx >= 0 && (needleEndOverlapsPair(s, idx, needle.length())
            || (codepointBoundaries && idx > from && Alphabet.pairInterior(s, idx)))) {
            idx = s.indexOf(needle, idx + 1);
        }
        return idx;
    }

    /**
     * True when a needle hit ending at unit {@code idx + needleLen - 1}
     * swallows the high half of a surrogate pair: the last needle unit is a
     * high surrogate that pairs with the next input unit, so the raw-unit
     * indexOf hit is not a codepoint-sequence match. Public static: the
     * ASM-emitted literal path calls it for the same guard.
     */
    static boolean needleEndOverlapsPair(String s, int idx, int needleLen) {
        int last = s.charAt(idx + needleLen - 1);
        if (last < 0xD800 || last > 0xDBFF) {
            return false;
        }
        int end = idx + needleLen;
        return end < s.length() && s.charAt(end) >= 0xDC00 && s.charAt(end) <= 0xDFFF;
    }

    /**
     * Detect a REQUIRED literal chain from the start state and pick its
     * search needle (null when no usable one exists). Every match must
     * begin by consuming exactly one char per chain step, in order: the
     * chain EXTENDS one step at a time while the current state is
     * non-accepting and all of its live (non-dead) exits share one target
     * and consume exactly one BMP unit each — a case-fold class (the
     * {@code (?i)i} group {I, i, İ..ı}), a class range, or a plain char
     * (duplicate entries on the same char+target dedup to one). Each exit
     * may carry word-boundary gate bits ({@link #WORD_GATE}); anything
     * else — a supplementary member (step width not fixed), a non-word
     * gate, a second target, a dead-end-only state, an accepting state —
     * ENDS the chain there: every step recorded so far is still required
     * (each step had exactly one onward target, so every accepting path
     * consumed it), while the divergence happens after the chain. The
     * start state's entry mask may carry word-gate bits only.
     *
     * <p>The needle is the longest contiguous run of SINGLETON steps
     * (case-stable chars — one live exit covering one codepoint) inside
     * the chain, at its fixed unit offset from the match start: fold and
     * class steps are one unit wide each, so every match contains the run
     * at exactly that offset. A whole-chain run is the v1 shape (offset
     * 0); a chain that folds all the way through (e.g. {@code (?i)kelvin})
     * has no run and yields no needle. Ties go to the LAST longest run —
     * the run nearest the divergence, the most selective position in
     * separator-and-value log shapes.
     *
     * <p>Transition OPS and word gates are allowed on chain exits: the
     * prefix scan still runs the exact walk at every hit, so tag writes
     * and gate evaluation belong to the walk, never to the scan. Public
     * static via {@link TdfaRunner#detectPrefixChain(Tdfa)}: the ASM
     * backend asks at emit time and bakes needle + offset in as class
     * constants.
     */
    static TdfaRunner.PrefixChain detectPrefixChain(Tdfa tdfa) {
        int n = tdfa.stateCount;
        if (n < 2 || (tdfa.startStateEntryMask & ~WORD_GATE) != 0) {
            return null;
        }
        int s = tdfa.startState;
        if ((tdfa.stateMeta[s] & 1) != 0) {
            return null; // zero-length matches possible anywhere
        }
        int cap = Math.min(n - 1, PREFIX_NEEDLE_MAX);
        // One char per step: the singleton's codepoint when the step has a
        // case-stable char, 0 when it is a fold class / class range.
        char[] step = new char[cap];
        int steps = 0;
        while (steps < cap) {
            int meta = tdfa.stateMeta[s];
            if ((meta & 1) != 0) {
                break; // a match may end here: later steps are not required
            }
            int base = tdfa.stateBase[s], cnt = (meta >>> 1) & 0xFFFF;
            // Unique extendable target, if one exists. Every live exit must
            // be BMP-only (fixed one-unit width) and word-gated at most; the
            // step is singleton-stable iff all live exits dedup to ONE char.
            int target = -1, ch = -1;
            boolean stop = false, singleton = true;
            for (int i = 0; i < cnt && !stop; i++) {
                int o = (base + i) * 5;
                if (tdfa.ranges[o + 2] < 0) {
                    continue; // dead marker: this exit never leads onward
                }
                if (tdfa.ranges[o + 1] > 0xFFFF || (tdfa.ranges[o + 4] & ~WORD_GATE) != 0) {
                    stop = true;
                } else if (target < 0) {
                    target = tdfa.ranges[o + 2];
                    ch = tdfa.ranges[o];
                    singleton = tdfa.ranges[o] == tdfa.ranges[o + 1];
                } else if (tdfa.ranges[o + 2] != target) {
                    stop = true; // fork: the chain cannot continue linearly
                } else if (tdfa.ranges[o] != ch || tdfa.ranges[o] != tdfa.ranges[o + 1]) {
                    singleton = false; // fold class / class range member
                }
            }
            if (stop || target < 0 || tdfa.stateEntryMask[target] != 0) {
                break; // chain end: divergence, dead-end, gate, or mask
            }
            step[steps++] = singleton ? (char) ch : 0;
            s = target;
        }
        // Longest stable run, ties to the last one (see javadoc).
        int bestLen = 0, bestOff = 0, run = 0;
        for (int i = 0; i < steps; i++) {
            if (step[i] != 0) {
                run++;
                if (run >= bestLen) {
                    bestLen = run;
                    bestOff = i + 1 - run;
                }
            } else {
                run = 0;
            }
        }
        if (bestLen == 0) {
            return null;
        }
        // Same lone-surrogate adjacency bail as the literal needle: two
        // adjacent LONE symbols re-encoded as a well-formed pair can never
        // be matched by the codepoint-decoding walk, so the needle would
        // only produce doomed candidates. Not a usable prefix.
        for (int i = bestOff; i < bestOff + bestLen - 1; i++) {
            char c0 = step[i], c1 = step[i + 1];
            if (c0 >= 0xD800 && c0 <= 0xDBFF && c1 >= 0xDC00 && c1 <= 0xDFFF) {
                return null;
            }
        }
        return new TdfaRunner.PrefixChain(new String(step, bestOff, bestLen), bestOff);
    }

    static void setBit(long[] bits, int c) {
        bits[c >>> 6] |= 1L << (c & 63);
    }

    /**
     * Word-class bitset over BMP UTF-16 units. ASCII mode (null ranges):
     * the 63-char [_0-9A-Za-z] set; unicode mode: wordRanges clipped to the BMP.
     */
    static long[] buildWordBits(int[] ranges) {
        long[] bits = new long[1024];
        if (ranges == null) {
            setBit(bits, '_');
            for (int c = '0'; c <= '9'; c++) {
                setBit(bits, c);
            }
            for (int c = 'a'; c <= 'z'; c++) {
                setBit(bits, c);
            }
            for (int c = 'A'; c <= 'Z'; c++) {
                setBit(bits, c);
            }
            return bits;
        }
        for (int i = 0; i + 1 < ranges.length; i += 2) {
            int lo = Math.max(ranges[i], 0), hi = Math.min(ranges[i + 1], 0xFFFF);
            for (int c = lo; c <= hi; c++) {
                bits[c >>> 6] |= 1L << (c & 63);
            }
        }
        return bits;
    }

    /**
     * Detect the literal-chain shape; null otherwise. Public static: the
     * ASM backend asks at emit time so literal DFAs get the fully-delegated
     * generated class (its indexOf short-circuit beats the generated walk
     * at every input length).
     */
    static String detectLiteralNeedle(Tdfa tdfa) {
        if (tdfa.groupCount != 0 || tdfa.tagCount != 0) {
            return null;
        }
        int n = tdfa.stateCount;
        if (n < 2) {
            return null;
        } // single-state: empty/anchor-only regex
        StringBuilder sb = new StringBuilder(n - 1);
        int s = tdfa.startState;
        for (int step = 0; step < n - 1; step++) {
            int meta = tdfa.stateMeta[s];
            if ((meta & 1) != 0) {
                return null;
            } // accepting mid-chain
            int cnt = (meta >>> 1) & 0xFFFF;
            if (cnt != 1) {
                return null;
            } // must be exactly one char
            int o = tdfa.stateBase[s] * 5;
            int lo = tdfa.ranges[o], hi = tdfa.ranges[o + 1];
            if (lo != hi || lo > 0xFFFF) {
                return null;
            } // single BMP codepoint
            if (tdfa.ranges[o + 2] < 0) {
                return null;
            } // dead
            if (tdfa.ranges[o + 3] != 0) {
                return null;
            } // transition ops
            if (tdfa.ranges[o + 4] != 0) {
                return null;
            } // required mask
            if (tdfa.stateEntryMask[tdfa.ranges[o + 2]] != 0) {
                return null;
            }
            sb.append((char) lo);
            s = tdfa.ranges[o + 2];
        }
        // final state: accepting, no mask, no fallback, no final ops, and
        // NO live outgoing transition (a live self-loop means the regex is
        // unbounded — e.g. a+ would be misdetected as literal "a",
        // returning [0,1) for find("a+","aaa") instead of [0,3)).
        if ((tdfa.stateMeta[s] & 1) == 0) {
            return null;
        }
        if (tdfa.stateAcceptMask[s] != 0) {
            return null;
        }
        // Position-dependent accept (byMask variants): the accept fires
        // only under some posFlags — the indexOf shortcut can't evaluate
        // that (e.g. Z(?:\A|\B) would match "Z" via the needle even
        // though \A and \B both fail at pos 1). Not a literal.
        {
            int[] fm = tdfa.stateFinalOpsByMask();
            if (fm != null) {
                int cells = tdfa.posFlagCells();
                for (int M = 0; M < cells; M++) {
                    if (fm[s * cells + M] < 0) {
                        return null;
                    }
                }
            }
        }
        if (tdfa.stateFinalOpsOff[s] != 0) {
            return null;
        }
        if (tdfa.stateEntryMask[s] != 0) {
            return null;
        }
        {
            int meta = tdfa.stateMeta[s];
            int base = tdfa.stateBase[s];
            for (int i = 0; i < ((meta >>> 1) & 0xFFFF); i++) {
                if (tdfa.ranges[(base + i) * 5 + 2] >= 0) {
                    return null;
                }
            }
        }
        // Lone-surrogate adjacency: the needle is built from single BMP
        // symbols, each appended as its raw unit. Two adjacent LONE
        // symbols (high then low) re-encode as a well-formed surrogate
        // PAIR — the same unit text as the pair codepoint they are not.
        // Unit-wise indexOf then matches input pairs against what the
        // alphabet defines as two lone codepoints (e.g.
        // (?i:\uD800)\uDFFF would match 𐏿 = \uD800\uDFFF whole).
        // Rejected here; the DFA walk handles the shape correctly (it
        // decodes).
        for (int i = 0; i < sb.length() - 1; i++) {
            char c0 = sb.charAt(i), c1 = sb.charAt(i + 1);
            if (c0 >= 0xD800 && c0 <= 0xDBFF && c1 >= 0xDC00 && c1 <= 0xDFFF) {
                return null;
            }
        }
        return sb.length() > 0 ? sb.toString() : null;
    }
}
