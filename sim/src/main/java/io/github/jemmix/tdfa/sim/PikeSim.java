package io.github.jemmix.tdfa.sim;

import io.github.jemmix.tdfa.tnfa.Tnfa;
import io.github.jemmix.tdfa.unicode.UnicodeDataProvider;
import io.github.jemmix.tdfa.unicode.UnicodeProviders;

import java.util.ArrayList;
import java.util.List;

/**
 * Reference pike-VM interpreter running DIRECTLY over the compiled
 * {@link Tnfa} — no determinization, no tables, no register allocation,
 * nothing deferred. This is the engine's specification-of-record for
 * everything downstream of the parser:
 *
 * <ul>
 *   <li><b>End users</b> get a debugging oracle: when the compiled engine's
 *       answer looks wrong, {@code PikeSim} answers "is the DFA wrong, or is
 *       the pattern's semantics what you think they are?" in one run.</li>
 *   <li><b>The test harness</b> gets layer attribution: sim-vs-DFA
 *       disagreement is a determinizer bug; sim-vs-DFA agreement with both
 *       diverging from re2j is a parser/semantics issue.</li>
 * </ul>
 *
 * <h2>Semantics (deliberately direct)</h2>
 * <ul>
 *   <li>Thread list in priority order; the ε-closure follows edges by
 *       priority, deduplicated by NFA state per input position — the pike
 *       queue marking, shared across ALL threads adding at that position
 *       (first arrival claims the state, with its caps). The empty-iteration
 *       cut falls out of this dedup for free: a nullable loop body's re-entry
 *       arrives at an already-claimed state and dies.</li>
 *   <li>Assertion ε-edges are checked AT THE CROSSING against directly
 *       computed position truth — no posFlags bits, no masks, no deferred
 *       checks. BEGIN/END are line-flavored; each edge carries exactly the
 *       bits it requires.</li>
 *   <li>Capture writes are COPY-ON-WRITE: each write forks the cap array
 *       for its downstream subtree (path isolation between siblings). This
 *       is the engine's functional tag-history semantics and the equivalent
 *       of re2j's scoped write/restore given the shared NFA shape — nullable
 *       {@code X*} compiles as {@code quest(plus(X))}, so loop-exit
 *       continuations explore inside the capture scopes and park while the
 *       writes are live ({@code (\B)*\z} on "!" reports {@code g1=""}).</li>
 *   <li>Pike record rule: a thread reaching the accept state records the
 *       match and cuts every lower-priority thread from that position's
 *       remaining adds; threads above it keep stepping, and a later record
 *       by one of them OVERWRITES (it is necessarily higher priority —
 *       everything below each recorder was cut). The answer is the last
 *       record.</li>
 *   <li>Unanchored search: try each start position left to right, skipping
 *       surrogate-pair interiors; the first start with a record wins.</li>
 * </ul>
 *
 * <p>Not fast, and not intended to be: it exists to be OBVIOUS. Keep it
 * that way.
 */
public final class PikeSim {

    private final Tnfa nfa;
    private final String pattern;
    /** Per state: outgoing ε-edge indices sorted by priority (low pri value first). */
    private final int[][] epsByState;
    /** Per state: outgoing symbol-edge indices, in construction order. */
    private final int[][] symByState;

    private PikeSim(String pattern, Tnfa nfa) {
        this.pattern = pattern;
        this.nfa = nfa;
        this.epsByState = epsOutgoing(nfa);
        this.symByState = symOutgoing(nfa);
    }

    private static int[][] epsOutgoing(Tnfa nfa) {
        int n = nfa.stateCount;
        int[] count = new int[n], fill = new int[n];
        for (int f : nfa.epsFrom) count[f]++;
        int[][] idx = new int[n][];
        for (int s = 0; s < n; s++) idx[s] = new int[count[s]];
        for (int i = 0; i < nfa.epsFrom.length; i++) idx[nfa.epsFrom[i]][fill[nfa.epsFrom[i]]++] = i;
        for (int s = 0; s < n; s++) {
            int[] a = idx[s];
            for (int i = 1; i < a.length; i++) {
                int v = a[i], j = i - 1;
                while (j >= 0 && nfa.epsPri[a[j]] > nfa.epsPri[v]) { a[j + 1] = a[j]; j--; }
                a[j + 1] = v;
            }
        }
        return idx;
    }

    private static int[][] symOutgoing(Tnfa nfa) {
        int n = nfa.stateCount;
        int[] count = new int[n], fill = new int[n];
        for (int f : nfa.symFrom) count[f]++;
        int[][] idx = new int[n][];
        for (int s = 0; s < n; s++) idx[s] = new int[count[s]];
        for (int i = 0; i < nfa.symFrom.length; i++) idx[nfa.symFrom[i]][fill[nfa.symFrom[i]]++] = i;
        return idx;
    }

    /** The underlying automaton (for deeper debugging). */
    public Tnfa nfa() { return nfa; }

    public String pattern() { return pattern; }

    public static PikeSim compile(String pattern) {
        return compile(pattern, UnicodeProviders.get());
    }

    public static PikeSim compile(String pattern, UnicodeDataProvider provider) {
        return new PikeSim(pattern, Tnfa.compile(pattern, false, false, provider, null));
    }

    public PikeMatcher matcher(CharSequence input) {
        return new PikeMatcher(this, input);
    }

    /**
     * One match-attempt machinery. {@link #find()} performs the unanchored
     * leftmost search; accessors expose the recorded result with the
     * null-group vs empty-group distinction the engine protocols rely on.
     */
    public static final class PikeMatcher {
        private final PikeSim sim;
        private final CharSequence input;
        private final int len;
        private int matchStart = -1, matchEnd = -1;
        private int[] matchCap;
        private boolean found;

        PikeMatcher(PikeSim sim, CharSequence input) {
            this.sim = sim;
            this.input = input;
            this.len = input.length();
        }

        public boolean find() {
            for (int s = 0; s <= len; s++) {
                if (s > 0 && isHigh(input.charAt(s - 1)) && isLow(input.charAt(s))) continue;  // pair interior
                if (runFrom(s)) { found = true; return true; }
            }
            found = false;
            return false;
        }

        public int start() { require(); return matchStart; }
        public int end() { require(); return matchEnd; }
        public int groupCount() { return sim.nfa.groupCount; }
        public String group() { return group0(0); }
        /** Group text, {@code null} if the group did not participate. */
        public String group(int g) { require(); return group0(g); }
        public boolean participating(int g) {
            require();
            int[] cap = caps();
            return cap[2 * g - 1] >= 0 && cap[2 * g] >= 0;
        }
        /** Raw tag values after fixed-tag reconstruction (1-based; -1 = unset). */
        public int[] tags() { require(); return caps(); }

        private String group0(int g) {
            if (g == 0) return input.subSequence(matchStart, matchEnd).toString();   // whole match: no tags
            int[] cap = caps();
            int open = cap[2 * g - 1], close = cap[2 * g];
            if (open < 0 || close < 0) return null;
            return input.subSequence(open, close).toString();
        }

        private int[] caps() {
            int[] cap = matchCap.clone();
            // BT22 §6.4 fixed-tag reconstruction — deliberately its own
            // implementation, NOT the engine's MatchResult.reconstructFixed
            // (the reference shares no code under test).
            int[] fb = sim.nfa.fixedBase, fo = sim.nfa.fixedOffset;
            if (fb != null) {
                for (int t = 1; t < fb.length; t++) {
                    int base = fb[t];
                    if (base != 0) cap[t] = cap[base] >= 0 ? cap[base] - fo[t] : -1;
                }
            }
            return cap;
        }

        private void require() {
            if (!found) throw new IllegalStateException("no match — call find() first");
        }

        private int start0() { return matchStart >= 0 ? matchStart : -1; }

        // ---- the machine ----

        private boolean recorded;
        private int recEnd = -1;
        private int[] recCap;
        /** Pike matched-break: once a thread records this round, every lower-priority
         *  park and add dies (they can never win — everything below each recorder
         *  is cut; a later record necessarily comes from a thread above). */
        private boolean roundCut;

        private static final boolean TRACE = Boolean.getBoolean("pikesim.trace");

        private boolean runFrom(int start) {
            recorded = false;
            recEnd = -1;
            recCap = null;
            roundCut = false;
            List<Parked> queue = new ArrayList<>();
            int[] visited = new int[sim.nfa.stateCount];
            if (TRACE) System.err.println("[sim] === start " + start);
            add(queue, sim.nfa.start, start, freshCaps(), visited);
            int pos = start;
            while (!queue.isEmpty()) {
                if (pos >= len) break;
                int cp = decode(pos);
                int width = cp > 0xFFFF ? 2 : 1;
                int roundPos = pos + width;
                List<Parked> next = new ArrayList<>();
                int[] seen = new int[sim.nfa.stateCount];
                roundCut = false;
                for (Parked t : queue) {
                    if (roundCut) break;   // pike cut: threads below the recorder die
                    for (int e : sim.symByState[t.state]) {
                        if (sim.nfa.symClass[e].matches(cp)) {
                            add(next, sim.nfa.symTo[e], roundPos, t.cap.clone(), seen);
                            if (roundCut) break;
                        }
                    }
                }
                queue = next;
                pos = roundPos;
            }
            if (recorded) {
                matchStart = start;
                matchEnd = recEnd;
                matchCap = recCap;
                return true;
            }
            return false;
        }

        private int[] freshCaps() {
            int[] cap = new int[sim.nfa.tagCount + 1];
            java.util.Arrays.fill(cap, -1);
            return cap;
        }

        /**
         * ε-closure from {@code state} at {@code pos}, parking symbol threads
         * into {@code queue}. {@code visited} is shared across ALL adds at
         * this position (pike queue marking — first arrival claims the state
         * with its caps).
         */
        private void add(List<Parked> queue, int state, int pos, int[] cap, int[] visited) {
            if (roundCut || visited[state] != 0) return;
            visited[state] = 1;
            if (TRACE) System.err.println("[sim] add state=" + state + " pos=" + pos);
            if (state == sim.nfa.accept) {
                // overwrites are necessarily higher priority (the cut rule
                // guarantees everything below each recorder died)
                recorded = true;
                recEnd = pos;
                recCap = cap.clone();
                roundCut = true;
                if (TRACE) System.err.println("[sim] RECORD " + start0() + ".." + pos);
                return;
            }
            for (int e : sim.epsByState[state]) {
                int req = sim.nfa.epsEmptyMask[e];
                if (req != 0 && !holds(req, pos)) continue;   // assertion: direct truth, at the crossing
                int tag = sim.nfa.epsTag[e];
                if (tag != 0) {
                    // capture = copy-on-write path state: each write forks the
                    // array for its downstream subtree, so sibling branches are
                    // isolated. This is the engine's functional l-history
                    // semantics, and the exact equivalent of re2j's imperative
                    // scoped write/restore (write; recurse; restore) GIVEN the
                    // shared NFA shape: nullable X* compiles as quest(plus(X)),
                    // so the loop's exit continuation explores INSIDE the
                    // capture scopes and parks while the writes are live —
                    // which is why (\B)*\z reports g1="".
                    int[] c2 = cap.clone();
                    if (tag > 0) c2[tag] = pos; else c2[-tag] = -1;
                    cap = c2;
                }
                add(queue, sim.nfa.epsTo[e], pos, cap, visited);
            }
            if (sim.symByState[state].length > 0) {
                queue.add(new Parked(state, cap));   // arrays are immutable after fork
                if (TRACE) System.err.println("[sim] park state=" + state + " pos=" + pos);
            }
        }

        /** Position truth for one assertion mask — the whole posFlags story, direct. */
        private boolean holds(int mask, int pos) {
            if ((mask & 1) != 0 && !(pos == 0 || input.charAt(pos - 1) == '\n')) return false;   // BEGIN_TEXT (line)
            if ((mask & 2) != 0 && !(pos == len || input.charAt(pos) == '\n')) return false;     // END_TEXT (line)
            if ((mask & 16) != 0 && pos != 0) return false;                                       // ABS_BEGIN
            if ((mask & 32) != 0 && pos != len) return false;                                     // ABS_END
            if ((mask & 12) != 0) {
                boolean boundary = wordBefore(pos) != wordAt(pos);
                if ((mask & 4) != 0 && !boundary) return false;                                   // \b
                if ((mask & 8) != 0 && boundary) return false;                                    // \B
            }
            return true;
        }

        private boolean wordBefore(int pos) {
            if (pos <= 0) return false;
            char c = input.charAt(pos - 1);
            if (sim.nfa.unicodeWordBoundary && isLow(c) && pos >= 2) {
                char h = input.charAt(pos - 2);
                if (isHigh(h)) return isWordCodepoint(((h - 0xD800) << 10) + (c - 0xDC00) + 0x10000);
            }
            return isWordCodepoint(c);
        }

        private boolean wordAt(int pos) {
            if (pos >= len) return false;
            char c = input.charAt(pos);
            if (sim.nfa.unicodeWordBoundary && isHigh(c) && pos + 1 < len) {
                char l = input.charAt(pos + 1);
                if (isLow(l)) return isWordCodepoint(((c - 0xD800) << 10) + (l - 0xDC00) + 0x10000);
            }
            return isWordCodepoint(c);
        }

        private boolean isWordCodepoint(int cp) {
            int[] wr = sim.nfa.wordRanges;
            if (wr == null) {
                // plain mode: \w is ASCII [0-9A-Za-z_] (mirrors the engine's
                // null-ranges fallback, implemented independently)
                return cp == '_' || (cp >= '0' && cp <= '9')
                        || (cp >= 'a' && cp <= 'z') || (cp >= 'A' && cp <= 'Z');
            }
            int lo = 0, hi = wr.length / 2 - 1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                if (cp < wr[2 * mid]) hi = mid - 1;
                else if (cp > wr[2 * mid + 1]) lo = mid + 1;
                else return true;
            }
            return false;
        }

        private int decode(int pos) {
            char c = input.charAt(pos);
            if (isHigh(c) && pos + 1 < len && isLow(input.charAt(pos + 1))) {
                return ((c - 0xD800) << 10) + (input.charAt(pos + 1) - 0xDC00) + 0x10000;
            }
            return c;
        }

        private static boolean isHigh(char c) { return c >= 0xD800 && c <= 0xDBFF; }
        private static boolean isLow(char c) { return c >= 0xDC00 && c <= 0xDFFF; }
    }

    private static final class Parked {
        final int state;
        final int[] cap;
        Parked(int state, int[] cap) { this.state = state; this.cap = cap; }
    }

    /**
     * CLI: {@code PikeSim '<pattern>' [input]} — without input, reads lines
     * from stdin (empty line = empty input). Prints {@code start..end} plus
     * groups ({@code null} or quoted text), for end-user debugging of what
     * the pattern's NFA semantics actually are.
     */
    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: PikeSim <pattern> [input]   (no input: read lines from stdin)");
            System.exit(2);
        }
        PikeSim sim = compile(args[0]);
        if (args.length >= 2) {
            runLine(sim, args[1]);
            return;
        }
        java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(System.in));
        String line;
        while ((line = r.readLine()) != null) runLine(sim, line);
    }

    private static void runLine(PikeSim sim, String in) {
        PikeMatcher m = sim.matcher(in);
        if (!m.find()) {
            System.out.println("no");
            return;
        }
        StringBuilder sb = new StringBuilder(m.start() + ".." + m.end());
        for (int g = 1; g <= m.groupCount(); g++) {
            String g1 = m.group(g);
            sb.append(" g").append(g).append('=').append(g1 == null ? "null" : "'" + g1 + "'");
        }
        System.out.println(sb);
    }
}
