package io.github.jemmix.tdfa.fuzz;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Property shrinker for minimal fuzz repros: delta-debugging over a failing
 * {@link DifferentialFuzzer.Case}. Driven by an abstract property predicate
 * ("this case still fails the same way"), so the machinery is testable
 * without a live divergence; {@link DifferentialFuzzer#shrinkCase} supplies
 * the real one (same finding kind, cold solo replay).
 *
 * <p>Two axes, interleaved to a fixpoint:
 * <ul>
 *   <li><b>pattern</b> — six structural passes over the pattern text, each a
 *       pure rewrite whose candidates the property must accept: drop an
 *       alternation branch, unwrap a group ({@code (X)} / {@code (?:X)} /
 *       {@code (?i:X)} → {@code X}; a quantified one re-scopes onto a sole
 *       bare content atom, {@code (?:a)*} → {@code a*}), edit a quantifier
 *       (delete, or soften a counted form to {@code *}), delete an atom
 *       (with its quantifier), drop one class member (a range or single
 *       rune), and simplify a literal atom to {@code a}. Rewrites that
 *       break syntax, change compile parity, or change the failure kind are
 *       discarded by the property itself — no validity logic here beyond
 *       escape/class/quote-aware span scanning.</li>
 *   <li><b>input</b> — char-level deletion, blocks from the whole string
 *       down to single chars (a lone-surrogate or supplementary-codepoint
 *       case shrinks exactly as well as an ASCII one: the property decides
 *       what must survive, char deletion never rewrites what it keeps).</li>
 * </ul>
 *
 * <p>Greedy 1-minimality with hard bounds: every accepted state is deduped,
 * a round cap, an evaluation-step cap and a wall-clock cap bound the search
 * (each evaluation compiles pattern + engines, so bounds matter). Each
 * evaluation runs on its own daemon worker under the soak's case timeout;
 * a candidate that HANGS aborts the shrink (the thread is sacrificed, at
 * most one per run — hang-class findings shrink no further and say so in
 * the report).
 */
final class CaseShrinker {

    /** Per-evaluation wall bound: the soak's own case watchdog. A candidate
     *  over it is a hang-class repro — abort rather than stack more. */
    private static final long EVAL_TIMEOUT_MS = DifferentialFuzzer.CASE_TIMEOUT_MS;

    /** Fixpoint round cap: every accepted move is new (deduped) and steps
     *  are capped, so this is belt-and-braces against pathological churn. */
    private static final int MAX_ROUNDS = 8;

    // pass order: biggest structural cuts first, cosmetics last
    private static final int PASS_ALTERNATION = 0;

    private static final int PASS_UNWRAP = 1;

    private static final int PASS_QUANTIFIER = 2;

    private static final int PASS_ATOM = 3;

    private static final int PASS_CLASS_MEMBER = 4;

    private static final int PASS_LITERAL = 5;

    private static final int PASSES = 6;

    private final Predicate<DifferentialFuzzer.Case> stillFails;

    private final long deadlineNanos;

    private final int maxSteps;

    private final Set<String> accepted = new HashSet<>();

    private int steps;

    private boolean capped;

    private String abort;

    /** Bounds: {@code maxMs} wall clock, {@code maxSteps} property evaluations. */
    CaseShrinker(Predicate<DifferentialFuzzer.Case> stillFails, long maxMs, int maxSteps) {
        this.stillFails = stillFails;
        this.deadlineNanos = System.nanoTime() + maxMs * 1_000_000L;
        this.maxSteps = maxSteps;
    }

    /**
     * Shrink to a minimal same-failure case, or null when the case does not
     * fail the property at all (clean case, or a finding that will not
     * reproduce under this predicate — e.g. warmth-dependent records on a
     * cold replay). The returned report's minimal case may equal the input
     * (already minimal).
     */
    ShrinkReport shrink(DifferentialFuzzer.Case c) {
        if (!eval(c)) {
            return null;
        }
        String pattern = c.pattern();
        String input = c.input();
        boolean round;
        int rounds = 0;
        do {
            round = false;
            rounds++;
            // One accepted move per pass per round — passes interleave, so
            // no single pass runs to its own fixpoint and starves a smarter
            // move from a later pass (ATOM hollowing (?:a)* to (?:)* before
            // UNWRAP can re-scope it to a*).
            for (int pass = 0; pass < PASSES; pass++) {
                String next = runPass(pass, pattern, input, c);
                if (next != null) {
                    pattern = next;
                    round = true;
                }
            }
            String in;
            while ((in = shrinkInputStep(pattern, input, c)) != null) {
                input = in;
                round = true;
            }
        } while (round && rounds < MAX_ROUNDS && !stopped());
        return new ShrinkReport(c, new DifferentialFuzzer.Case(pattern, input, c.flags(), c.lane()), steps, capped,
            abort);
    }

    private boolean stopped() {
        if (abort != null) {
            return true;
        }
        if (steps >= maxSteps || System.nanoTime() >= deadlineNanos) {
            capped = true; // the report must admit it may not be minimal
            return true;
        }
        return false;
    }

    /** One pass application: the first accepted candidate rewrite, or null. */
    private String runPass(int pass, String pattern, String input, DifferentialFuzzer.Case proto) {
        for (String cand : candidates(pass, pattern)) {
            if (!cand.equals(pattern) && eval(new DifferentialFuzzer.Case(cand, input, proto.flags(), proto.lane()))) {
                return cand;
            }
        }
        return null;
    }

    /** One accepted input deletion (block sizes whole → 1, offsets left to
     *  right), or null when no deletion keeps the failure. */
    private String shrinkInputStep(String pattern, String input, DifferentialFuzzer.Case proto) {
        for (int size = input.length(); size >= 1; size--) {
            for (int off = 0; off + size <= input.length(); off++) {
                String cand = input.substring(0, off) + input.substring(off + size);
                if (eval(new DifferentialFuzzer.Case(pattern, cand, proto.flags(), proto.lane()))) {
                    return cand;
                }
            }
        }
        return null;
    }

    // ---- property evaluation (budgeted, timeout-guarded) ----

    private boolean eval(DifferentialFuzzer.Case c) {
        if (abort != null) {
            return false;
        }
        if (!accepted.add(state(c.pattern(), c.input()))) {
            return false; // already visited — never re-accept a prior state
        }
        if (steps >= maxSteps) {
            capped = true;
            return false;
        }
        if (System.nanoTime() >= deadlineNanos) {
            capped = true;
            return false;
        }
        steps++;
        boolean[] res = {false};
        Thread worker = new Thread(() -> res[0] = safeTest(c), "case-shrink");
        worker.setDaemon(true);
        worker.start();
        try {
            worker.join(EVAL_TIMEOUT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            abort = "interrupted";
            return false;
        }
        if (worker.isAlive()) {
            // hang-class candidate: sacrifice the daemon worker (bounded to
            // one leak per shrink) and stop — deeper shrinking of a hang
            // needs JFR triage, not more candidates
            abort = "candidate hung";
            return false;
        }
        return res[0];
    }

    private boolean safeTest(DifferentialFuzzer.Case c) {
        try {
            return stillFails.test(c);
        } catch (RuntimeException | StackOverflowError e) {
            return false;
        }
    }

    private static String state(String pattern, String input) {
        return pattern + '\u0000' + input;
    }

    // ---- pattern candidate generation ----

    private static List<String> candidates(int pass, String p) {
        Scan s = scan(p);
        return switch (pass) {
            case PASS_ALTERNATION -> dropAlternationBranch(p, s);
            case PASS_UNWRAP -> unwrapGroup(p, s);
            case PASS_QUANTIFIER -> editQuantifier(p, s);
            case PASS_ATOM -> deleteAtom(p, s);
            case PASS_CLASS_MEMBER -> dropClassMember(p, s);
            default -> simplifyLiteral(p, s);
        };
    }

    /** For every alternation (any group with a direct {@code |}, the whole
     *  pattern included): drop one branch and one adjacent separator. */
    private static List<String> dropAlternationBranch(String p, Scan s) {
        List<String> out = new ArrayList<>();
        for (Alt alt : s.alternations) {
            int k = alt.seps().size();
            int prev = alt.contentStart();
            int end = alt.contentEnd();
            for (int i = 0; i <= k; i++) {
                int branchStart = i == 0 ? prev : alt.seps().get(i - 1) + 1;
                int branchEnd = i == k ? end : alt.seps().get(i);
                int from = i < k ? branchStart : alt.seps().get(i - 1);
                int to = i < k ? branchEnd + 1 : branchEnd;
                out.add(p.substring(0, from) + p.substring(to));
            }
        }
        return out;
    }

    /** {@code (X)} / {@code (?:X)} / {@code (?i:X)} / {@code (?<n>X)} →
     *  {@code X} for unquantified groups (a quantified one would need its
     *  quantifier re-scoped onto the content — not a simplification);
     *  plus the exactly-equivalent re-scope when the content is one bare
     *  atom: {@code (?:a)*} → {@code a*} (a capture loses its group — the
     *  property discards it when that changes the failure). */
    private static List<String> unwrapGroup(String p, Scan s) {
        List<String> out = new ArrayList<>();
        for (Tok t : s.atoms) {
            if (t.kind() != Tok.GROUP) {
                continue;
            }
            if (t.quantStart() < 0) {
                out.add(p.substring(0, t.start()) + p.substring(t.contentStart(), t.contentEnd())
                    + p.substring(t.atomEnd()));
                continue;
            }
            Tok sole = soleTopLevelAtom(p.substring(t.contentStart(), t.contentEnd()));
            if (sole != null && sole.quantStart() < 0) {
                out.add(p.substring(0, t.start()) + p.substring(t.contentStart(), t.contentEnd())
                    + p.substring(t.quantStart(), t.end()));
            }
        }
        return out;
    }

    /** The single top-level atom of a pattern, or null when the content is
     *  empty, several atoms, or one quantified atom (re-scoping would put
     *  two quantifiers in a row). */
    private static Tok soleTopLevelAtom(String content) {
        if (content.isEmpty()) {
            return null;
        }
        Scan inner = scan(content);
        Tok found = null;
        for (Tok t : inner.atoms) {
            boolean nested = inner.atoms.stream().anyMatch(o -> o != t && o.start() <= t.start() && t.end() <= o.end());
            if (nested) {
                continue;
            }
            if (found != null) {
                return null; // more than one top-level atom
            }
            found = t;
        }
        return found;
    }

    /** Delete a quantifier, or soften a counted form to {@code *} when that
     *  shortens the text (equal-length swaps are churn, not simplification). */
    private static List<String> editQuantifier(String p, Scan s) {
        List<String> out = new ArrayList<>();
        for (Tok t : s.atoms) {
            if (t.quantStart() < 0) {
                continue;
            }
            out.add(p.substring(0, t.quantStart()) + p.substring(t.end()));
            if (t.end() - t.quantStart() > 1) {
                out.add(p.substring(0, t.quantStart()) + "*" + p.substring(t.end()));
            }
        }
        return out;
    }

    /** Delete an atom together with its quantifier. */
    private static List<String> deleteAtom(String p, Scan s) {
        List<String> out = new ArrayList<>();
        for (Tok t : s.atoms) {
            out.add(p.substring(0, t.start()) + p.substring(t.end()));
        }
        return out;
    }

    /** Drop one class member (a single rune, an escape shorthand, or a
     *  range — never the only member: {@code []} cannot compile, the
     *  property discards it anyway). */
    private static List<String> dropClassMember(String p, Scan s) {
        List<String> out = new ArrayList<>();
        for (Tok t : s.atoms) {
            if (t.kind() != Tok.CLASS) {
                continue;
            }
            int j = t.contentStart();
            if (j < t.contentEnd() && p.charAt(j) == '^') {
                j++;
            }
            while (j < t.contentEnd()) {
                int start = j;
                j += p.charAt(j) == '\\' && j + 1 < t.contentEnd() ? 2 : 1;
                if (j < t.contentEnd() && p.charAt(j) == '-' && j + 1 < t.contentEnd() && p.charAt(j + 1) != ']') {
                    j++; // range dash
                    j += p.charAt(j) == '\\' && j + 1 < t.contentEnd() ? 2 : 1;
                }
                out.add(p.substring(0, start) + p.substring(j));
            }
        }
        return out;
    }

    /** Replace a literal atom (before its quantifier) with {@code a}. */
    private static List<String> simplifyLiteral(String p, Scan s) {
        List<String> out = new ArrayList<>();
        for (Tok t : s.atoms) {
            if (t.kind() == Tok.LITERAL && !"a".equals(p.substring(t.start(), t.atomEnd()))) {
                out.add(p.substring(0, t.start()) + "a" + p.substring(t.atomEnd()));
            }
        }
        return out;
    }

    // ---- pattern span scanner ----

    /** Escape-, class- and {@code \Q..\E}-aware scan of the pattern into
     *  atoms (literals, classes, groups with their content spans and
     *  trailing quantifiers) plus every direct alternation (any group with
     *  a {@code |} child, the whole pattern included). Structural text the
     *  grammar never emits (bare {@code (?i)} flag groups, lookarounds) is
     *  scanned leniently: a bad rewrite candidate just fails to compile,
     *  which the property discards. */
    private static Scan scan(String p) {
        Scan s = new Scan();
        int n = p.length();
        ArrayDeque<int[]> open = new ArrayDeque<>(); // {groupStart, contentStart, sepListIndexBase}
        List<List<Integer>> sepStack = new ArrayList<>();
        List<Integer> rootSeps = new ArrayList<>();
        sepStack.add(rootSeps);
        int i = 0;
        while (i < n) {
            char c = p.charAt(i);
            int start = i;
            if (c == '\\' && i + 1 < n) {
                if (p.charAt(i + 1) == 'Q') {
                    int e = p.indexOf("\\E", i + 2);
                    i = e < 0 ? n : e + 2;
                } else {
                    i += 2;
                }
                s.atoms.add(literal(p, start, i));
                continue;
            }
            if (c == '[') {
                int j = i + 1;
                if (j < n && p.charAt(j) == '^') {
                    j++;
                }
                if (j < n && p.charAt(j) == ']') {
                    j++; // literal ] first in class
                }
                while (j < n && p.charAt(j) != ']') {
                    j += p.charAt(j) == '\\' && j + 1 < n ? 2 : 1;
                }
                int atomEnd = Math.min(n, j + 1);
                s.atoms.add(tok(p, start, atomEnd, start + 1, j, Tok.CLASS));
                i = atomEnd;
                continue;
            }
            if (c == '(') {
                open.push(new int[]{start, contentStart(p, start)});
                sepStack.add(new ArrayList<>());
                i = contentStart(p, start);
                continue;
            }
            if (c == ')') {
                if (open.isEmpty()) {
                    i++;
                    s.atoms.add(literal(p, start, i)); // unbalanced — literal
                    continue;
                }
                int[] g = open.pop();
                List<Integer> seps = sepStack.remove(sepStack.size() - 1);
                s.atoms.add(tok(p, g[0], i + 1, g[1], i, Tok.GROUP));
                if (!seps.isEmpty()) {
                    s.alternations.add(new Alt(g[1], i, seps));
                }
                i = i + 1;
                continue;
            }
            if (c == '|') {
                sepStack.get(sepStack.size() - 1).add(i);
                i++;
                continue;
            }
            i++;
            s.atoms.add(literal(p, start, i));
        }
        if (!rootSeps.isEmpty()) {
            s.alternations.add(new Alt(0, n, rootSeps));
        }
        // atoms were recorded in close order (groups after their children);
        // candidate order should read left to right
        s.atoms.sort((a, b) -> a.start() - b.start());
        return s;
    }

    /** Where a group's content starts: past the prefix — {@code (} plain,
     *  {@code (?:}, {@code (?i:}-style flag scopes, {@code (?<n>} names.
     *  Lenient on shapes the grammar never emits (lookarounds, bare flag
     *  groups): the content start only shapes candidates the property
     *  discards when they are wrong. */
    private static int contentStart(String p, int open) {
        int n = p.length();
        if (open + 1 >= n || p.charAt(open + 1) != '?') {
            return open + 1;
        }
        int i = open + 2;
        if (i < n && p.charAt(i) == '<') {
            int gt = p.indexOf('>', i);
            return gt < 0 ? n : gt + 1;
        }
        while (i < n && (Character.isLetter(p.charAt(i)) || p.charAt(i) == '-')) {
            i++;
        }
        if (i < n && p.charAt(i) == ':') {
            return i + 1;
        }
        return open + 2; // bare (?letters) or (?=...)/(?!...): near enough
    }

    /** A literal atom ending at {@code atomEnd}, plus its quantifier. */
    private static Tok literal(String p, int start, int atomEnd) {
        return tok(p, start, atomEnd, start, atomEnd, Tok.LITERAL);
    }

    private static Tok tok(String p, int start, int atomEnd, int contentStart, int contentEnd, int kind) {
        int[] q = quantifier(p, atomEnd);
        return q == null ? new Tok(start, atomEnd, kind, contentStart, contentEnd, -1, atomEnd)
            : new Tok(start, atomEnd, kind, contentStart, contentEnd, q[0], q[1]);
    }

    /** The quantifier starting at {@code from}, if any: {@code * + ? {n}
     *  {n,} {n,m}} plus a lazy {@code ?} marker. A {@code {...}} that does
     *  not close is a literal brace (the caller then leaves it for the next
     *  atom). */
    private static int[] quantifier(String p, int from) {
        int n = p.length();
        if (from >= n) {
            return null;
        }
        char c = p.charAt(from);
        int end;
        if (c == '*' || c == '+' || c == '?') {
            end = from + 1;
        } else if (c == '{') {
            int j = from + 1;
            boolean digit = false;
            while (j < n && "0123456789,".indexOf(p.charAt(j)) >= 0) {
                digit |= p.charAt(j) != ',';
                j++;
            }
            if (j >= n || p.charAt(j) != '}' || !digit) {
                return null; // literal '{'
            }
            end = j + 1;
        } else {
            return null;
        }
        if (end < n && p.charAt(end) == '?') {
            end++;
        }
        return new int[]{from, end};
    }

    // ---- nested types (last per house style) ----

    /** Outcome of a shrink run. {@code abort} is non-null when a candidate
     *  hung (or the run was interrupted): the minimal case is the best
     *  state reached before that, honestly labeled. */
    record ShrinkReport(DifferentialFuzzer.Case original, DifferentialFuzzer.Case minimal, int evals, boolean capped,
        String abort) {

        boolean changed() {
            return !minimal.pattern().equals(original.pattern()) || !minimal.input().equals(original.input());
        }
    }

    /** One scanned atom: {@code [start, atomEnd)} is the atom itself,
     *  {@code [start, end)} includes its quantifier, content spans are
     *  class/group interiors. */
    record Tok(int start, int atomEnd, int kind, int contentStart, int contentEnd, int quantStart, int end) {

        static final int LITERAL = 0;

        static final int CLASS = 1;

        static final int GROUP = 2;
    }

    /** One direct alternation: branch boundaries inside
     *  {@code [contentStart, contentEnd)} at the separator positions. */
    record Alt(int contentStart, int contentEnd, List<Integer> seps) {
    }

    /** Scan results. */
    private static final class Scan {

        final List<Tok> atoms = new ArrayList<>();

        final List<Alt> alternations = new ArrayList<>();
    }
}
