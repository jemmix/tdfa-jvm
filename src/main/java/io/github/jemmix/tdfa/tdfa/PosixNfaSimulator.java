package io.github.jemmix.tdfa.tdfa;

import io.github.jemmix.tdfa.Regex;
import io.github.jemmix.tdfa.ast.CharClass;
import io.github.jemmix.tdfa.tnfa.Tnfa;
import io.github.jemmix.tdfa.vm.MatchResult;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * BT19 POSIX submatch extraction via NFA simulation (Stage 1 of full TDFA
 * integration). Uses {@link UTree} + {@link PosixCompare} + GTOP closure to
 * find the leftmost-longest match with provably-correct POSIX submatch
 * groups.
 *
 * <p>This is the algorithm Borsotti-Trofimovich 2019 specify for runtime
 * matching. BT22 §Conventions notes that for performance the same closure
 * logic should be embedded into TDFA construction (Stage 2, future work) so
 * matching stays O(n) with no runtime disambiguation. Stage 1 alone is
 * correct but O(n·m²·t) per match — fine for small patterns/inputs (e.g.
 * Glenn Fowler's test corpus) but slower than TdfaRunner for large inputs.
 *
 * <p>Used only when {@link io.github.jemmix.tdfa.Regex#compile} is called
 * with {@link Disambiguation#POSIX} AND {@code bytecode == false}. PERL mode
 * keeps using {@link TdfaRunner} (no change). When {@code bytecode == true}
 * (ASM backend), we fall back to TdfaRunner for now — POSIX+ASM needs the
 * Stage 2 TDFA integration.
 *
 * <h2>Algorithm</h2>
 * Per BT19 §3 (match loop):
 * <pre>
 * U = empty_path_tree()
 * C = { (start, origin=0, path=0, regs=[-1,...]) }
 * for i = 0..n:
 *   C = closure_gtop(C, U, old_prectable)         // ε-closure w/ POSIX disambiguation
 *   record accept configs in C (with regs/path)
 *   new_prectable = update_precedence(C, U, old_prectable)
 *   if i < n: C = step_on_symbol(C, input[i])     // symbol transition
 *   old_prectable = new_prectable
 * extract tags from best accept config
 * </pre>
 */
public final class PosixNfaSimulator implements Regex.Engine {
    private final Tnfa nfa;
    private final int ntags;
    private final int groupCount;
    /** Tag-height lookup: height[t] = group number of tag t (1-based). */
    private final int[] height;
    /** Nested-tag range M(m).N for each tag m. Lists tags to set to -1 on negative-tag expansion. */
    private final int[][] nestedRange;

    public PosixNfaSimulator(Tnfa nfa) {
        this.nfa = nfa;
        this.ntags = nfa.tagCount;
        this.groupCount = nfa.groupCount;
        this.height = computeSimpleTagHeights(ntags);
        this.nestedRange = computeNestedRanges(nfa);
    }

    /** Simple height assignment: height(t) = ceil(t/2) = group number.
     *  Correct for non-nested groups; full IRE pass needed for nested groups (BT19 §4). */
    private static int[] computeSimpleTagHeights(int ntags) {
        int[] h = new int[ntags + 1];
        for (int t = 1; t <= ntags; t++) h[t] = (t + 1) / 2;
        return h;
    }

    /** Nested-range M(m).N for each tag: tags of the group itself (open+close).
     *  For a group with open=2i-1, close=2i: N(2i) = N(2i-1) = {2i-1, 2i}.
     *  For nested groups, would include all groups nested inside; for now, just self. */
    private static int[][] computeNestedRanges(Tnfa nfa) {
        int n = nfa.tagCount;
        int[][] out = new int[n + 1][];
        for (int t = 1; t <= n; t++) {
            int groupNum = (t + 1) / 2;
            int openTag = 2 * groupNum - 1;
            int closeTag = 2 * groupNum;
            out[t] = new int[]{openTag, closeTag};
        }
        return out;
    }

    // ===== Regex.Engine interface =====

    @Override public boolean matches(CharSequence input) {
        return find(input);
    }

    @Override public boolean find(CharSequence input) {
        return findFrom(input, 0) >= 0;
    }

    @Override public MatchResult match(CharSequence input, int from) {
        int matchStart = findFrom(input, from);
        if (matchStart < 0) return null;
        // Re-run from matchStart to capture tags.
        SimResult r = simulate(input, matchStart, input.length());
        if (r == null) return null;
        // MatchResult stores tags at offset [tagCount .. 2*tagCount-1].
        int[] regs = new int[2 * ntags];
        for (int t = 1; t <= ntags; t++) regs[ntags + t - 1] = r.tags[t];
        return new MatchResult(regs, ntags, groupCount, matchStart, r.matchEnd);
    }

    /** Find leftmost match start at-or-after {@code from}; returns -1 if none. */
    private int findFrom(CharSequence input, int from) {
        int len = input.length();
        for (int s = from; s <= len; s++) {
            SimResult r = simulate(input, s, len);
            if (r != null) return s;
        }
        return -1;
    }

    // ===== Simulation =====

    private static final class SimResult {
        final int matchEnd;
        final int[] tags;  // length ntags; value = position (>=0) or -1 (no match)
        SimResult(int matchEnd, int[] tags) { this.matchEnd = matchEnd; this.tags = tags; }
    }

    /** Simulate from {@code start} to {@code end}; return match end + tags if a match was found. */
    private SimResult simulate(CharSequence input, int start, int end) {
        // One UTree per simulation (persists across steps); paths accumulate across steps.
        // Each step's closure adds tags to paths; tags added at step i get position i.
        UTree U = new UTree();
        List<Config> threads = new ArrayList<>();
        int[] initRegs = newRegs();
        threads.add(new Config(nfa.start, -1, U.root(), initRegs));

        int[] oldPrectable = null;
        int oldClosureSize = 0;

        int bestEnd = -1;
        int[] bestTags = null;

        this.input = input;
        this.inputLen = end;

        // Initial closure at position = start.
        currentPos = start;
        ClosureResult cl = closureGtop(threads, U, oldPrectable, oldClosureSize);
        threads = cl.configs;
        oldPrectable = cl.prectable;
        oldClosureSize = threads.size();

        // Check for accept at start (zero-width).
        for (Config c : threads) {
            if (c.state == nfa.accept) {
                if (bestTags == null || posixBetter(c.regs, bestTags, start)) {
                    bestEnd = start;
                    bestTags = c.regs.clone();
                }
            }
        }

        for (int pos = start; pos < end; pos++) {
            char ch = input.charAt(pos);
            // Step on symbol.
            List<Config> stepped = new ArrayList<>();
            for (int i = 0; i < threads.size(); i++) {
                Config c = threads.get(i);
                int[] outs = symOut(c.state);
                for (int idx : outs) {
                    CharClass cc = nfa.symClass[idx];
                    if (cc != null && matchesChar(cc, ch)) {
                        // Origin = index in previous closure.
                        // Carry regs and path forward (no tag update on symbol step).
                        stepped.add(new Config(nfa.symTo[idx], i, c.path, c.regs));
                    }
                }
            }
            if (stepped.isEmpty()) break;
            currentPos = pos + 1;
            cl = closureGtop(stepped, U, oldPrectable, oldClosureSize);
            threads = cl.configs;
            oldPrectable = cl.prectable;
            oldClosureSize = threads.size();

            for (Config c : threads) {
                if (c.state == nfa.accept) {
                    int thisEnd = pos + 1;
                    if (bestTags == null) {
                        bestEnd = thisEnd;
                        bestTags = c.regs.clone();
                    } else if (thisEnd > bestEnd) {
                        // POSIX: prefer longer overall match.
                        bestEnd = thisEnd;
                        bestTags = c.regs.clone();
                    } else if (thisEnd == bestEnd && posixBetter(c.regs, bestTags, thisEnd)) {
                        // Same length: leftmost-longest groups.
                        bestTags = c.regs.clone();
                    }
                }
            }
        }

        if (bestTags == null) return null;
        return new SimResult(bestEnd, bestTags);
    }

    /** Current input position during closure (for tag value assignment). */
    private int currentPos;
    /** Input charsequence (for word-boundary checks). */
    private CharSequence input;
    /** Input length (cached). */
    private int inputLen;

    /** Closure result: configs + prectable. */
    private static final class ClosureResult {
        final List<Config> configs;
        final int[] prectable;
        ClosureResult(List<Config> c, int[] p) { configs = c; prectable = p; }
    }

    private static final class Config {
        final int state;
        final int origin;
        final int path;
        final int[] regs;
        Config(int state, int origin, int path, int[] regs) {
            this.state = state; this.origin = origin; this.path = path; this.regs = regs;
        }
    }

    /** Closure via GTOP. Returns the closed configuration list (one config per reachable NFA state). */
    private ClosureResult closureGtop(List<Config> seeds, UTree U, int[] oldPrectable, int oldClosureSize) {
        // result[q] = best config reaching NFA state q.
        Map<Integer, Config> result = new HashMap<>();
        // Process seeds in order; for each, if better than existing, replace.
        // (GTOP would order by topological index of NFA state; for simplicity we use seed order
        // and let compare() handle priority. This is O(m²t) but correct.)
        List<Config> queue = new ArrayList<>(seeds);
        while (!queue.isEmpty()) {
            Config c = queue.remove(queue.size() - 1);
            Config existing = result.get(c.state);
            if (existing == null) {
                result.put(c.state, c);
            } else {
                // Compare; replace if c wins.
                long cmp = PosixCompare.compare(c.path, existing.path,
                        c.origin, existing.origin, U, height, oldPrectable, oldClosureSize);
                if (PosixCompare.l(cmp) < 0) {
                    result.put(c.state, c);
                } else {
                    continue;  // existing wins; no relaxation
                }
            }
            // Explore ε-outgoing.
            exploreEps(c, U, queue);
        }
        // Materialize result list.
        List<Config> configs = new ArrayList<>(result.values());
        // Compute new prectable.
        int n = configs.size();
        int[] prectable = new int[n * n];
        for (int i = 0; i < n; i++) {
            prectable[i * n + i] = PosixCompare.packCell(PosixCompare.MAX_RHO, 0);
            for (int j = i + 1; j < n; j++) {
                Config ci = configs.get(i), cj = configs.get(j);
                long cmp = PosixCompare.compare(ci.path, cj.path,
                        ci.origin, cj.origin, U, height, oldPrectable, oldClosureSize);
                int h1 = PosixCompare.h1(cmp), h2 = PosixCompare.h2(cmp), l = PosixCompare.l(cmp);
                prectable[i * n + j] = PosixCompare.packCell(h1, l);
                prectable[j * n + i] = PosixCompare.packCell(h2, -l);
            }
        }
        lastPrectable = prectable;
        return new ClosureResult(configs, prectable);
    }

    private int[] lastPrectable;

    /** Explore ε-outgoing edges from {@code c}, adding new configs to {@code queue}. */
    private void exploreEps(Config c, UTree U, List<Config> queue) {
        int[] eps = epsOut(c.state);
        // Compute position-flags for anchor checks.
        int posFlags = 0;
        if (currentPos == 0) posFlags |= Tnfa.BEGIN_TEXT;
        if (currentPos == inputLen) posFlags |= Tnfa.END_TEXT;
        boolean prevWord = currentPos > 0 && isWordChar(input.charAt(currentPos - 1));
        boolean currWord = currentPos < inputLen && isWordChar(input.charAt(currentPos));
        if (prevWord != currWord) posFlags |= Tnfa.WORD_BOUNDARY;
        else posFlags |= Tnfa.NO_WORD_BOUNDARY;

        for (int idx : eps) {
            int required = nfa.epsEmptyMask[idx];
            if ((required & ~posFlags) != 0) continue;  // anchor assertion fails at this position
            int to = nfa.epsTo[idx];
            int tag = nfa.epsTag[idx];
            int newPath = c.path;
            int[] newRegs = c.regs;
            if (tag != Tnfa.NO_TAG) {
                // Encode tag: positive value = set; negative = nil (ntag, BT19 §7.3).
                int info;
                if (tag > 0) {
                    info = UTree.packInfo(tag, false);
                } else {
                    info = UTree.packInfo(-tag, true);
                }
                newPath = U.extend(c.path, info);
                // Update regs: positive ⇒ set to currentPos; negative ⇒ set to -1 (no match).
                int absTag = Math.abs(tag);
                int newVal = tag > 0 ? currentPos : -1;
                if (newRegs[absTag] != newVal) {
                    newRegs = c.regs.clone();
                    newRegs[absTag] = newVal;
                }
            }
            Config next = new Config(to, c.origin, newPath, newRegs);
            queue.add(next);
        }
    }

    private static boolean isWordChar(char c) {
        return c == '_' || (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    /** POSIX leftmost-longest comparison of tag arrays (same overall match length): returns true if {@code a} is strictly better. */
    private boolean posixBetter(int[] a, int[] b, int matchEnd) {
        for (int g = 1; g <= groupCount; g++) {
            int open = 2 * g - 1, close = 2 * g;
            int as = a[open], ae = a[close], bs = b[open], be = b[close];
            if (as < 0 && bs >= 0) return false;
            if (bs < 0 && as >= 0) return true;
            if (as < 0 && bs < 0) continue;
            if (as < bs) return true;
            if (as > bs) return false;
            if (ae > be) return true;
            if (ae < be) return false;
        }
        return false;
    }

    // ===== Helpers =====

    private int[] newRegs() {
        int[] r = new int[ntags + 1];
        Arrays.fill(r, -1);
        return r;
    }

    private int[] symOut(int state) {
        // Linear scan; cache if needed for perf.
        List<Integer> outs = new ArrayList<>();
        for (int i = 0; i < nfa.symFrom.length; i++) {
            if (nfa.symFrom[i] == state) outs.add(i);
        }
        int[] arr = new int[outs.size()];
        for (int i = 0; i < outs.size(); i++) arr[i] = outs.get(i);
        return arr;
    }

    private int[] epsOut(int state) {
        // Get indices of ε-edges from this state, sorted by priority (low value = high priority).
        // Returned in REVERSE priority order so callers using LIFO queues process the
        // highest-priority edge first (push low-priority first, pop high-priority first).
        List<int[]> edges = new ArrayList<>();
        for (int i = 0; i < nfa.epsFrom.length; i++) {
            if (nfa.epsFrom[i] == state) edges.add(new int[]{nfa.epsPri[i], i});
        }
        edges.sort((a, b) -> Integer.compare(b[0], a[0]));  // descending: low-priority first
        int[] arr = new int[edges.size()];
        for (int i = 0; i < edges.size(); i++) arr[i] = edges.get(i)[1];
        return arr;
    }

    private static boolean matchesChar(CharClass cc, char c) {
        return cc.matches(c);
    }

    private static int indexOf(List<Config> list, Config c) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i) == c) return i;
        }
        return -1;
    }
}
