package io.github.jemmix.tdfa.fuzz;

import com.google.re2j.Re2jUnicodeProvider;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * Differential fuzzer: random patterns over the supported grammar, random
 * inputs biased toward the historically painful shapes (supplementary
 * codepoints, lone surrogates, boundaries), compared against the re2j oracle
 * through the public facade — BOTH engines (ASM generated tier and VM
 * interpreter) per case. The contract is re2j's observable behavior:
 * compile-accept/reject parity plus identical first-match results (match
 * text, group count, every group's text).
 *
 * <p>A soak, not a unit test. Run via the {@code fuzz} Gradle task:
 * <pre>
 *   ./gradlew :tests:parity:re2j:fuzz -Pfuzz.minutes=480 -Pfuzz.seed=1234
 * </pre>
 * Logs land in the out dir (default {@code build/fuzz/}):
 * <ul>
 *   <li>{@code failures.ndjson} — one line per recorded divergence: caseSeed,
 *       escaped pattern/input, oracle and both engines' results, kind. Every
 *       line is independently reproducible via {@code -Dfuzz.one=<caseSeed>}.</li>
     *   <li>{@code summary.txt} — counts, rate, deduped failure signatures.
     *       Rewritten periodically and at exit: killing the run keeps the data.</li>
     *   <li>{@code progress.log} — heartbeat every 15 s.</li>
     * </ul>
     *
     * <p>{@code -Dfuzz.append=true} (Gradle: {@code -Pfuzz.append=true}) appends
     * {@code failures.ndjson}/{@code progress.log} instead of truncating, so a
     * chunked soak ({@code scripts/fuzz-soak.sh}: many short JVMs, so threads
     * leaked by hang watchdogs die with their process) shares one out dir.
     *
     * <p>Known, documented divergences are avoided by construction rather than
     * filtered after the fact: case-insensitive + supplementary/lone-surrogate
     * combinations are not generated (tdfa has no supplementary simple-fold
     * table yet; re2j folds fully — TODO.md tracks the gap), and {@code (?i)}
     * class ranges are generated ASCII-narrow only (re2j's own parser expands
     * case folds over every codepoint in a range — wide {@code (?i)} ranges
     * hung the ORACLE in 44 of the first ~50 soak hangs). Everything else
     * must agree with re2j or it is a finding.
 */
public final class DifferentialFuzzer {

    // ---- knobs (system properties; the Gradle task forwards -Pfuzz.*) ----

    public static void main(String[] argv) throws Exception {
        long one = Long.getLong("fuzz.one", 0);
        if (one != 0) {
            Case c = generate(one);
            System.out.println("pattern: " + escape(c.pattern()));
            System.out.println("input:   " + escape(c.input()));
            Outcome o = runOne(c);
            System.out.println("oracle:  " + o.oracle);
            System.out.println("asm:     " + o.asm);
            System.out.println("vm:      " + o.vm);
            if (!o.exceptions.isEmpty()) o.exceptions.forEach(System.out::println);
            return;
        }
        long masterSeed = Long.getLong("fuzz.seed", 0) != 0
                ? Long.getLong("fuzz.seed", 0) : System.currentTimeMillis();
        long minutes = Long.getLong("fuzz.minutes", 5);
        long maxCases = Long.getLong("fuzz.cases", 0);
        Path outDir = Path.of(System.getProperty("fuzz.out", "build/fuzz"));
        Results r = run(masterSeed, minutes, maxCases, outDir);
        System.out.printf("%n==== fuzz done: %d cases, %d failures (%.1f/min) ====%n",
                r.cases, r.failures, r.casesPerMinute);
        if (r.failures > 0) System.out.println("see failures.ndjson / summary.txt in " + outDir);
        System.exit(r.failures > 0 ? 1 : 0);   // nonzero exit so overnight scripts can see it
    }

    static final long CASE_TIMEOUT_MS = Long.getLong("fuzz.caseTimeoutMs", 10_000);
    /** The worker of the case in flight (hang post-mortem must read THIS
     *  thread, not any previously-leaked hung worker). */
    private static volatile Thread currentWorker;

    /** One case with a watchdog: a hang (compile or match loop that never
     *  returns) is recorded and the soak CONTINUES — the worker thread is
     *  sacrificed as a daemon. The fuzzer found a real hang on its first
     *  20k-case run; overnight runs must survive their own discoveries.
     *  Returns the Outcome, or null on timeout (worker left running). */
    static Outcome runWatched(Case c) throws InterruptedException {
        final Outcome[] out = new Outcome[1];
        Thread worker = new Thread(() -> out[0] = runOne(c), "fuzz-case");
        worker.setDaemon(true);
        currentWorker = worker;
        worker.start();
        worker.join(CASE_TIMEOUT_MS);
        return out[0];
    }

    /** Core entry reusable from the smoke test. */
    public static Results run(long masterSeed, long minutes, long maxCases, Path outDir) throws IOException {
        Files.createDirectories(outDir);
        try (Logs logs = new Logs(outDir)) {
            SplittableRandom master = new SplittableRandom(masterSeed);
            long deadline = System.nanoTime() + minutes * 60_000_000_000L;
            long start = System.nanoTime();
            long lastProgress = start;
            Results r = new Results(masterSeed);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> { r.writeSummary(logs); logs.flush(); }));
            while ((maxCases <= 0 || r.cases < maxCases) && System.nanoTime() < deadline) {
                long caseSeed = master.nextLong();
                Case c = generate(caseSeed);
                Outcome o;
                try {
                    o = runWatched(c);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (o == null) {
                    r.cases++;
                    r.hangs++;
                    logs.hang(caseSeed, c, r);
                    continue;
                }
                r.record(caseSeed, o, logs);
                r.cases++;
                long now = System.nanoTime();
                if (now - lastProgress > 15_000_000_000L) {
                    double mins = (now - start) / 60_000_000_000.0;
                    r.casesPerMinute = mins > 0 ? r.cases / mins : 0;
                    logs.progress(r, mins);
                    r.writeSummary(logs);
                    lastProgress = now;
                }
            }
            double mins = (System.nanoTime() - start) / 60_000_000_000.0;
            r.casesPerMinute = mins > 0 ? r.cases / mins : 0;
            logs.progress(r, mins);
            r.writeSummary(logs);
            return r;
        }
    }

    // ---- one case ----

    record Case(String pattern, String input) {}

    static Outcome runOne(Case c) {
        Outcome o = new Outcome(c);
        try {
            com.google.re2j.Pattern p = com.google.re2j.Pattern.compile(c.pattern());
            o.oracle = compute(p.matcher(c.input()));
        } catch (RuntimeException e) {
            o.oracle = "<reject>";
        }
        o.asm = engine(c, null, o);
        o.vm = engine(c, io.github.jemmix.tdfa.tdfa.TdfaRunner::new, o);
        return o;
    }

    static String engine(Case c, io.github.jemmix.tdfa.core.RegexEngineFactory f, Outcome o) {
        String tag = f == null ? "asm" : "vm";
        try {
            var p = io.github.jemmix.tdfa.Pattern.compile(c.pattern(), 0, f, Re2jUnicodeProvider.INSTANCE);
            return compute(p.matcher(c.input()));
        } catch (io.github.jemmix.tdfa.core.PatternSyntaxException e) {
            return "<reject:" + firstLine(e.getMessage()) + ">";
        } catch (RuntimeException e) {
            o.exceptions.add(tag + " " + e.getClass().getSimpleName() + ": " + firstLine(e.getMessage()));
            return "<exception:" + e.getClass().getSimpleName() + ">";
        }
    }

    /** Corpus-test protocol: "true <group()> <groupCount> <g1> <g2>...". */
    static String compute(io.github.jemmix.tdfa.core.Matcher m) {
        StringBuilder sb = new StringBuilder();
        boolean found = m.find();
        sb.append(found ? "true " + m.group() : "false").append(' ').append(m.groupCount());
        if (found)
            for (int i = 1; i <= m.groupCount(); i++) {
                String g = m.group(i);
                if (g != null) sb.append(' ').append(g);
            }
        return sb.toString();
    }

    static String compute(com.google.re2j.Matcher m) {
        StringBuilder sb = new StringBuilder();
        boolean found = m.find();
        sb.append(found ? "true " + m.group() : "false").append(' ').append(m.groupCount());
        if (found)
            for (int i = 1; i <= m.groupCount(); i++) {
                String g = m.group(i);
                if (g != null) sb.append(' ').append(g);
            }
        return sb.toString();
    }

    // ---- pattern generator ----

    /** Char pools. Supplementary codepoints and lone surrogates are
     *  first-class citizens: they found every recent bug family. */
    static final int[] POOL_ASCII = "abz09ZY_-.#@ ~".chars().toArray();
    static final int[] POOL_EDGE = {'\n', '\t', '\r', ' ', '\u0000'};
    static final int[] POOL_UNICODE = {0xE9, 0xDF, 0x17F, 0x3042, 0x6F22, 0x4E00, 0x03A9, 0x20AC};
    static final int[] POOL_SUPP = {0x10421, 0x10402, 0x10000, 0x1F4A9, 0x1D504, 0x11C07, 0x103FF};
    static final int[] POOL_LONE = {0xD800, 0xDBFF, 0xDC00, 0xDC21, 0xDFFF};

    static final int MAX_DEPTH = 4;
    private static int ciSuppAvoided;   // informational; generation-side counters
    private static int ciRangeAvoided;  // (known-gap / oracle-hang constructs not generated)

    static Case generate(long caseSeed) {
        SplittableRandom rnd = new SplittableRandom(caseSeed);
        ciSuppAvoided = 0;
        ciRangeAvoided = 0;
        String pattern = expr(rnd, 0, false);
        int inLen = rnd.nextInt(0, 25);
        StringBuilder in = new StringBuilder(inLen * 2);
        for (int i = 0; i < inLen; i++) in.appendCodePoint(pickInputCp(rnd));
        return new Case(pattern, in.toString());
    }

    static int pickInputCp(SplittableRandom rnd) {
        return switch (rnd.nextInt(10)) {
            case 0, 1 -> POOL_ASCII[rnd.nextInt(POOL_ASCII.length)];
            case 2 -> POOL_EDGE[rnd.nextInt(POOL_EDGE.length)];
            case 3, 4 -> POOL_UNICODE[rnd.nextInt(POOL_UNICODE.length)];
            case 5, 6, 7 -> POOL_SUPP[rnd.nextInt(POOL_SUPP.length)];   // pairs in inputs
            case 8, 9 -> POOL_LONE[rnd.nextInt(POOL_LONE.length)];      // lone surrogates in inputs
            default -> 'a';
        };
    }

    static String expr(SplittableRandom rnd, int depth, boolean ci) {
        int parts = rnd.nextInt(1, 4);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts; i++) sb.append(atom(rnd, depth, ci));
        return sb.toString();
    }

    static String atom(SplittableRandom rnd, int depth, boolean ci) {
        int roll = rnd.nextInt(100);
        if (depth < MAX_DEPTH) {
            if (roll < 8) return "(" + expr(rnd, depth + 1, ci) + ")";
            if (roll < 12) return "(?:" + expr(rnd, depth + 1, ci) + ")";
            if (roll < 14) return "(?<n" + rnd.nextInt(3) + ">" + expr(rnd, depth + 1, ci) + ")";
            if (roll < 16) return "(?i:" + expr(rnd, depth + 1, true) + ")";
            if (roll < 18) return "(?s:" + expr(rnd, depth + 1, ci) + ")";
            if (roll < 20) return "(?m:" + expr(rnd, depth + 1, ci) + ")";
            if (roll < 22) return "(?U:" + expr(rnd, depth + 1, ci) + ")";
            if (roll < 25 && depth > 0) return expr(rnd, depth + 1, ci) + "|" + expr(rnd, depth + 1, ci);
        }
        if (roll < 32) return quant(rnd, depth, ci);
        if (roll < 36) return ".";
        if (roll < 40) return anchor(rnd);
        if (roll < 48) return shorthand(rnd);
        if (roll < 51) return "\\Q" + plainLiteral(rnd) + "\\E";
        return patternLiteral(rnd, ci);
    }

    static String quant(SplittableRandom rnd, int depth, boolean ci) {
        String body = quantable(rnd, depth, ci);
        String q = switch (rnd.nextInt(6)) {
            case 0 -> "*";
            case 1 -> "+";
            case 2 -> "?";
            case 3 -> "{" + rnd.nextInt(0, 4) + "," + Math.max(rnd.nextInt(0, 7), 1) + "}";
            case 4 -> "{" + rnd.nextInt(0, 5) + ",}";
            default -> "{" + rnd.nextInt(1, 4) + "}";
        };
        return body + q + (rnd.nextInt(4) == 0 ? "?" : "");
    }

    static String quantable(SplittableRandom rnd, int depth, boolean ci) {
        int roll = rnd.nextInt(100);
        if (roll < 25) return patternLiteral(rnd, ci);
        if (roll < 40) return shorthand(rnd);
        if (roll < 55) return ".";
        if (roll < 75) return "[" + classBody(rnd, ci) + "]";
        if (depth < MAX_DEPTH && roll < 88) return "(" + expr(rnd, depth + 1, ci) + ")";
        if (depth < MAX_DEPTH) return "(?:" + expr(rnd, depth + 1, ci) + ")";
        return "a";
    }

    static String shorthand(SplittableRandom rnd) {
        return switch (rnd.nextInt(8)) {
            case 0 -> "\\d"; case 1 -> "\\D"; case 2 -> "\\w"; case 3 -> "\\W";
            case 4 -> "\\s"; case 5 -> "\\S"; case 6 -> "\\n"; default -> "\\t";
        };
    }

    static String anchor(SplittableRandom rnd) {
        return switch (rnd.nextInt(6)) {
            case 0 -> "^"; case 1 -> "$"; case 2 -> "\\b"; case 3 -> "\\B"; case 4 -> "\\A"; default -> "\\z";
        };
    }

    static String classBody(SplittableRandom rnd, boolean ci) {
        StringBuilder sb = new StringBuilder();
        if (rnd.nextInt(6) == 0) sb.append('^');
        int members = rnd.nextInt(1, 5);
        for (int i = 0; i < members; i++) {
            int roll = rnd.nextInt(10);
            if (roll < 3) {
                int lo, hi;
                if (ci) {
                    // (?i) ranges: ASCII-narrow only. re2j's parser folds every
                    // cp in the range; wide ranges are an ORACLE hang (44 of the
                    // first ~50 soak hangs). We fold full-Unicode now too, but
                    // the oracle-side limitation keeps this guard.
                    ciRangeAvoided++;
                    lo = POOL_ASCII[rnd.nextInt(POOL_ASCII.length)];
                    hi = POOL_ASCII[rnd.nextInt(POOL_ASCII.length)];
                } else {
                    lo = pickClassCp(rnd, ci);
                    hi = pickClassCp(rnd, ci);
                }
                if (hi < lo) { int t = lo; lo = hi; hi = t; }   // keep ranges legal (re2j rejects inverted)
                sb.append(classMember(lo)).append('-').append(classMember(hi));
            } else if (roll < 5) {
                sb.append("\\").append(switch (rnd.nextInt(4)) { case 0 -> "d"; case 1 -> "w"; case 2 -> "s"; default -> "n"; });
            } else {
                sb.append(classMember(pickClassCp(rnd, ci)));
            }
        }
        return sb.toString();
    }

    static String classMember(int cp) {
        if (cp == '\\') return "\\\\";
        if (cp == ']' || cp == '[' || cp == '-' || cp == '^') return "\\" + (char) cp;
        return cp > 0xFFFF ? new StringBuilder().appendCodePoint(cp).toString() : String.valueOf((char) cp);
    }

    /** Class member/range endpoint. Under (?i): supplementary and lone
     *  surrogates avoided (tdfa lacks a supplementary fold table; re2j folds). */
    static int pickClassCp(SplittableRandom rnd, boolean ci) {
        return switch (rnd.nextInt(8)) {
            case 0, 1, 2 -> POOL_ASCII[rnd.nextInt(POOL_ASCII.length)];
            case 3 -> POOL_UNICODE[rnd.nextInt(POOL_UNICODE.length)];
            case 4 -> POOL_SUPP[rnd.nextInt(POOL_SUPP.length)];
            case 5 -> POOL_LONE[rnd.nextInt(POOL_LONE.length)];
            default -> 'a' + rnd.nextInt(26);
        };
    }

    /** Literal for pattern context, specials escaped. */
    static String patternLiteral(SplittableRandom rnd, boolean ci) {
        int cp = switch (rnd.nextInt(8)) {
            case 0, 1, 2, 3 -> POOL_ASCII[rnd.nextInt(POOL_ASCII.length)];
            case 4 -> POOL_UNICODE[rnd.nextInt(POOL_UNICODE.length)];
            case 5 -> POOL_SUPP[rnd.nextInt(POOL_SUPP.length)];
            case 6 -> POOL_LONE[rnd.nextInt(POOL_LONE.length)];
            default -> 'a' + rnd.nextInt(26);
        };
        StringBuilder sb = new StringBuilder();
        if (cp < 128 && ".[]()*+?{}|^$\\-#~".indexOf(cp) >= 0) sb.append('\\');
        sb.appendCodePoint(cp);
        return sb.toString();
    }

    /** Unescaped content for \Q..\E (the quote handles the escaping). */
    static String plainLiteral(SplittableRandom rnd) {
        StringBuilder sb = new StringBuilder();
        int n = rnd.nextInt(1, 4);
        for (int i = 0; i < n; i++) sb.appendCodePoint(POOL_ASCII[rnd.nextInt(POOL_ASCII.length)]);
        return sb.toString();
    }

    // ---- outcome bookkeeping ----

    static final class Outcome {
        final Case c;
        String oracle = "?", asm = "?", vm = "?";
        final java.util.List<String> exceptions = new java.util.ArrayList<>();
        Outcome(Case c) { this.c = c; }

        boolean failed() {
            if (!exceptions.isEmpty()) return true;
            if (oracle.startsWith("<"))    // re2j rejects: both engines must reject too
                return !asm.startsWith("<reject") || !vm.startsWith("<reject");
            return !asm.equals(oracle) || !vm.equals(oracle);
        }
    }

    static final class Results {
        final long masterSeed;
        long cases, failures, bothReject, ciSuppAvoidedTotal, ciRangeAvoidedTotal, knownDivergence;
        long hangs, hangsOurs, hangsOracle;
        double casesPerMinute;
        final Map<String, Sig> signatures = new LinkedHashMap<>();
        Results(long masterSeed) { this.masterSeed = masterSeed; }

        void record(long caseSeed, Outcome o, Logs logs) {
            ciSuppAvoidedTotal += ciSuppAvoided;
            ciRangeAvoidedTotal += ciRangeAvoided;
            if (o.failed()) {
                String known = knownDivergence(o);
                if (known != null) {
                    knownDivergence++;
                    logs.failure(caseSeed, o, "KNOWN_DIVERGENCE (" + known + ")");
                    return;
                }
                failures++;
                String kind = kindOf(o);
                String sig = kind + " | shape~" + shape(o.c.pattern());
                Sig s = signatures.computeIfAbsent(sig, k -> new Sig(kind));
                s.total++;
                if (s.recorded < 8) {
                    s.recorded++;
                    logs.failure(caseSeed, o, kind);
                }
            } else if (o.oracle.startsWith("<")) {
                bothReject++;
            }
        }

        /**
         * Documented re2j divergences (see TODO.md "Correctness"):
         * <ul>
         *   <li><b>lone-low interior starts</b> — re2j matches a lone-LOW
         *       surrogate pattern against the low half of a well-formed pair
         *       in the input (and thereby also finds EARLIER leftmost matches
         *       than a boundary-respecting engine). JDK agrees with us. This
         *       is a suspected re2j BUG (upstream issue drafted; fix on our
         *       fork: https://github.com/jemmix/re2j/tree/fix-surrogate-pair-interior-prefix):
         *       raw String.indexOf in the literal-prefix fast path lands on
         *       pair interiors, so only patterns that compile to a singleton
         *       literal prefix are affected — `\uDC21` matches where
         *       `\uDC21|\uDC22` (a strict superset!) and `[\uD800-\uDFFF]`
         *       do not, a monotonicity violation. The RANGE form avoids the
         *       fast path entirely (no single-rune prefix). We keep
         *       codepoint-boundary semantics either way.
         *       Applies when both our engines agree with each other and the
         *       pattern contains a lone low surrogate.</li>
         *   <li><b>plain (?i) full simple folding</b> — re2j folds class
         *       ranges with full Unicode simple folding even without a
         *       unicode flag, so {@code (?i)\w} matches ſ. We fold ASCII-only
         *       without {@code (?u)} by design; divergence appears when the
         *       oracle's match text contains a multi-member fold-group
         *       codepoint.</li>
         * </ul>
         */
        static String knownDivergence(Outcome o) {
            String p = o.c.pattern();
            for (int i = 0; i < p.length(); i++) {
                char c = p.charAt(i);
                if (c >= 0xD800 && c <= 0xDBFF) { i++; continue; }  // well-formed pair: interior low is not a lone low
                if (c >= 0xDC00 && c <= 0xDFFF && o.asm.equals(o.vm) && !o.asm.equals(o.oracle))
                    return "re2j matches lone-low pattern at/into pair interior; JDK agrees with us";
            }
            // NOTE: the former plain-(?i) full-folding entry is GONE — we now
            // fold full Unicode simple folding under plain (?i) exactly like
            // re2j (literals, explicit classes, and word shorthands; verified
            // against re2j 1.8), so any fold divergence is a real bug.
            return null;
        }

        static String kindOf(Outcome o) {
            if (!o.exceptions.isEmpty()) return "EXCEPTION";
            if (o.oracle.startsWith("<"))
                return "COMPILE_PARITY (re2j rejects, tdfa accepts)";
            if (o.asm.startsWith("<exception") || o.vm.startsWith("<exception")) return "EXCEPTION";
            if (o.asm.startsWith("<reject") || o.vm.startsWith("<reject"))
                return (o.asm.contains("budget") || o.vm.contains("budget"))
                        ? "BUDGET_REJECT" : "COMPILE_PARITY (tdfa rejects)";
            if (!o.asm.equals(o.oracle) && !o.vm.equals(o.oracle)) return "RESULT_MISMATCH (both engines)";
            if (!o.asm.equals(o.oracle)) return "RESULT_MISMATCH (asm only)";
            return "RESULT_MISMATCH (vm only)";
        }

        /** Coarse shape for signature dedup: structural chars only. */
        static String shape(String pattern) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < pattern.length(); i++) {
                char ch = pattern.charAt(i);
                if ("()[]|*+?{}".indexOf(ch) >= 0) sb.append(ch);
            }
            return sb.length() > 24 ? sb.substring(0, 24) : sb.toString();
        }

        void writeSummary(Logs logs) { logs.summary(this); }
    }

    static final class Sig {
        final String kind;
        int total, recorded;
        Sig(String kind) { this.kind = kind; }
    }

    // ---- logging ----

    static final class Logs implements AutoCloseable {
        private final Path dir;
        private final PrintWriter failures, progress;

        Logs(Path dir) throws IOException {
            this.dir = dir;
            // fuzz.append: keep failures.ndjson/progress.log across chunked
            // soak runs (scripts/fuzz-soak.sh); summary.txt always reflects
            // the latest chunk.
            StandardOpenOption[] opts = Boolean.getBoolean("fuzz.append")
                    ? new StandardOpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.APPEND}
                    : new StandardOpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING};
            failures = new PrintWriter(Files.newBufferedWriter(dir.resolve("failures.ndjson"), opts), false);
            progress = new PrintWriter(Files.newBufferedWriter(dir.resolve("progress.log"), opts), true);
        }

        void failure(long caseSeed, Outcome o, String kind) {
            failures.println("{\"caseSeed\":" + caseSeed + ",\"kind\":\"" + kind.replace('"', '\'')
                    + "\",\"pattern\":\"" + escape(o.c.pattern()) + "\",\"input\":\"" + escape(o.c.input())
                    + "\",\"oracle\":\"" + escape(o.oracle) + "\",\"asm\":\"" + escape(o.asm)
                    + "\",\"vm\":\"" + escape(o.vm) + "\""
                    + (o.exceptions.isEmpty() ? "" : ",\"ex\":" + o.exceptions.stream()
                        .map(e -> "\"" + escape(e) + "\"").toList())
                    + "}");
            failures.flush();
        }

        /** A case whose worker never returned within the watchdog: pattern,
         *  input and the worker's live stack, for post-mortem. Classified:
         *  a stack inside our engine packages is an ENGINE hang (a finding);
         *  anything else is oracle/system slowness (e.g. re2j's (?i) class
         *  fold expansion over wide ranges). NOTE: the harness frames
         *  ({@code io.github.jemmix.tdfa.fuzz.*}) wrap EVERY worker stack —
         *  oracle hangs included — so they must not count as "ours" (the
         *  first soak misattributed 33 re2j-parser hangs to the engine). */
        void hang(long caseSeed, Case c, Results r) {
            Thread w = findWorker();
            StringBuilder st = new StringBuilder();
            if (w != null) for (StackTraceElement e : w.getStackTrace()) st.append(e).append(" | ");
            boolean ours = isEngineStack(st);
            if (ours) r.hangsOurs++; else r.hangsOracle++;
            failures.println("{\"caseSeed\":" + caseSeed + ",\"kind\":\"HANG_" + (ours ? "ENGINE" : "ORACLE")
                    + "\",\"pattern\":\"" + escape(c.pattern()) + "\",\"input\":\"" + escape(c.input())
                    + "\",\"stack\":\"" + escape(st.toString()) + "\"}");
            failures.flush();
        }

        /** Engine-side verdict: any frame in our own packages, excluding the
         *  fuzz harness frames that always sit below the hung code. Oracle
         *  hangs show com.google.re2j frames there instead. */
        static boolean isEngineStack(CharSequence st) {
            for (String f : st.toString().split(" \\| "))
                if (f.startsWith("io.github.jemmix.tdfa.") && !f.contains(".fuzz.")) return true;
            return false;
        }

        private static Thread findWorker() {
            return currentWorker;
        }

        void summary(Results r) {
            try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(dir.resolve("summary.txt")))) {
                w.println("masterSeed: " + r.masterSeed);
                w.println("cases: " + r.cases + "  failures: " + r.failures + "  bothReject: " + r.bothReject
                        + "  knownDivergence: " + r.knownDivergence + "  hangsEngine: " + r.hangsOurs
                        + "  hangsOracle: " + r.hangsOracle);
                w.printf("rate: %.1f cases/min%n", r.casesPerMinute);
                w.println("ciSuppAvoided (known-gap constructs not generated): " + r.ciSuppAvoidedTotal
                        + "  ciWideRangeAvoided (oracle-hang guard): " + r.ciRangeAvoidedTotal);
                w.println();
                w.println("failure signatures (deduped):");
                r.signatures.forEach((sig, s) -> w.printf("  %6d  %s%n", s.total, sig));
            } catch (IOException ignored) { }
        }

        void progress(Results r, double mins) {
            progress.printf("t=%6.1fmin cases=%d failures=%d known=%d hangE=%d hangO=%d sigs=%d%n",
                    mins, r.cases, r.failures, r.knownDivergence, r.hangsOurs, r.hangsOracle, r.signatures.size());
        }

        void flush() { failures.flush(); progress.flush(); }

        @Override public void close() { failures.close(); progress.close(); }
    }

    static String firstLine(String s) {
        if (s == null) return "";
        int i = s.indexOf('\n');
        return i < 0 ? s : s.substring(0, i);
    }

    /** ASCII-safe \\uXXXX escaping so overnight logs are reviewable anywhere. */
    static String escape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c > 0x7E || c == '"' || c == '\\') sb.append(String.format("\\u%04x", (int) c));
            else sb.append(c);
        }
        return sb.toString();
    }
}
