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
 * interpreter) per case, under a per-batch random compile-flag matrix
 * (CASE_INSENSITIVE / DOTALL / MULTILINE / LONGEST_MATCH — same bit values
 * in both libraries; 40% of batches stay at flags=0 for continuity with the
 * ~300M-case flags=0 history). The contract is re2j's observable behavior:
 * compile-accept/reject parity plus a five-probe span protocol compared for
 * exact equality — first {@code find()} with overall and per-group spans
 * (spans strictly subsume group texts), full {@code find()} iteration on the
 * same matcher (continuation + empty-match advance), {@code matches()},
 * {@code lookingAt()}, and {@code find(len/2)} restart.
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
            // Replay under the SAME scoped budget the soak uses — without
            // this, a one-case replay runs at the library budget (2^32) and
            // budget monsters replay in tens of seconds, misrepresenting
            // their in-soak behavior (round 24: a 57 s replay got labeled
            // "clean"). -Dfuzz.max.work=0 restores the library budget.
            long fuzzWork = fuzzWorkBudget();
            String prevWork = fuzzWork > 0
                    ? System.setProperty("tdfa.max.work", Long.toString(fuzzWork)) : null;
            try {
                Case c = generate(one);
                System.out.println("pattern: " + escape(c.pattern()));
                System.out.println("input:   " + escape(c.input()));
                System.out.println("flags:   " + c.flags());
                Outcome o = runOne(c);
                System.out.println("oracle:  " + o.oracle);
                System.out.println("asm:     " + o.asm);
                System.out.println("vm:      " + o.vm);
                if (!o.exceptions.isEmpty()) o.exceptions.forEach(System.out::println);
            } finally {
                if (fuzzWork > 0) {
                    if (prevWork != null) System.setProperty("tdfa.max.work", prevWork);
                    else System.clearProperty("tdfa.max.work");
                }
            }
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

    /** Layered comparator (re2j/sim/vm/asm vote) for failure attribution. */
    private static final io.github.jemmix.tdfa.parity.LayeredComparator LAYERED =
            new io.github.jemmix.tdfa.parity.LayeredComparator(com.google.re2j.Re2jUnicodeProvider.INSTANCE);

    /** Which re2j is on the classpath — released 1.8, or our patched fork
     *  (vendor/re2j-jemmix, -Pfuzz.patchedOracle=true)? Probed, not declared:
     *  the lone-low-interior behavior is the discriminator (same probe as
     *  LayeredComparatorTest). Under the patched oracle the lone-surrogate
     *  "known divergence" family is FIXED — any divergence there is a real
     *  finding, so the known-divergence classifier runs only when released. */
    static final boolean RELEASED_ORACLE = releasedOracle();

    static boolean releasedOracle() {
        try {
            return com.google.re2j.Pattern.compile("\uDC21").matcher("a\uD801\uDC21zz").find();
        } catch (RuntimeException e) {
            return true;   // cannot happen for this constant; fail loud in the soak instead of silently
        }
    }

    /** One batch with a watchdog: compile once, then K inputs, each guard-tracked
     *  in {@code prog} (-1 = compiling, i = about to run input i). On timeout the
     *  worker thread is sacrificed as before; outcomes already written (indices
     *  < prog) are volatile-ordered before the prog store that revealed them, so
     *  the main thread records them normally and attributes the hang to the exact
     *  caseSeed (batch*K + prog). Returns false on timeout; the sacrificed worker
     *  is handed back via {@code workerOut} for the post-mortem stack. */
    static boolean runBatchWatched(String pattern, int flags, String[] inputs, Outcome[] os,
                                    java.util.concurrent.atomic.AtomicInteger prog, Thread[] workerOut) throws InterruptedException {
        Thread worker = new Thread(() -> {
            Prepared pr = prepare(pattern, flags);
            for (int i = 0; i < inputs.length; i++) {
                prog.set(i);
                os[i] = matchCase(pr, new Case(pattern, inputs[i], flags));
            }
        }, "fuzz-case");
        worker.setDaemon(true);
        workerOut[0] = worker;
        java.lang.management.ThreadMXBean tmx =
                java.lang.management.ManagementFactory.getThreadMXBean();
        worker.start();
        worker.join(CASE_TIMEOUT_MS);
        if (!worker.isAlive()) return true;
        // Tiered watchdog (round 21/25): at 10 s, probe the worker's CPU.
        // A SPIN (cpu ~ elapsed) is a finding — sacrifice and record now. A
        // STALL (cpu far below wall — GC pause, scheduler starvation from a
        // co-tenant build) gets grace to bound the false-hang class: the
        // batch often completes normally and no record is written at all.
        // fuzz.graceMs=0 restores the old single-shot behavior.
        long grace = Long.getLong("fuzz.graceMs", 50_000);
        if (grace <= 0) return false;
        long cpu1 = tmx.getThreadCpuTime(worker.getId()) / 1_000_000;
        if (cpu1 >= CASE_TIMEOUT_MS / 2) return false;   // burning CPU: real spin
        worker.join(grace);
        return !worker.isAlive();
    }

    /** Worker threads for batch execution. Default: cores-1 (min 2, cap 8) —
     *  compile is the dominant per-batch cost and parallelizes cleanly.
     *  1 restores the sequential executor. Case-generation order and the
     *  ndjson record order are thread-count-invariant: batches are drawn,
     *  generated and RECORDED on the main thread; only prepare+match runs
     *  on the pool. */
    static int threads() {
        long def = Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors() - 1));
        return (int) Math.max(1, Long.getLong("fuzz.threads", def));
    }

    /** One in-flight batch. */
    private record BatchJob(long batch, int flags, String pattern, String[] inputs, Outcome[] os,
                            java.util.concurrent.atomic.AtomicInteger prog, Thread[] worker,
                            java.util.concurrent.Future<Boolean> done) {}

    /** Fuzz-scoped compile work budget (ticks, wall-clock-free: the same
     *  pattern rejects after the same tick count however the machine is
     *  throttled). The generator's nested-counted bombs burn 36-100M ticks
     *  walking into the state/kernel caps (10-30 s wall) and the
     *  fallback-DFS family even more — far past the 10 s case watchdog, so
     *  the worker thread got sacrificed and SPUN until it hit a cap. An 8M
     *  tick budget rejects those compiles in ~1-3 s, under the watchdog:
     *  no sacrificed threads, no background spin, and the divergence is
     *  recorded as BUDGET_REJECT instead of a hang (same known class).
     *  Legit fuzz patterns measure <<1M ticks, so the slice is unaffected.
     *  Set -Dfuzz.max.work=0 to fuzz at the library default (2^32). */
    static long fuzzWorkBudget() {
        return Long.getLong("fuzz.max.work", 8L << 20);
    }

    /** Core entry reusable from the smoke test. */
    public static Results run(long masterSeed, long minutes, long maxCases, Path outDir) throws IOException {
        Files.createDirectories(outDir);
        long fuzzWork = fuzzWorkBudget();
        String prevWork = fuzzWork > 0
                ? System.setProperty("tdfa.max.work", Long.toString(fuzzWork)) : null;
        try (Logs logs = new Logs(outDir)) {
            SplittableRandom master = new SplittableRandom(masterSeed);
            long deadline = System.nanoTime() + minutes * 60_000_000_000L;
            long start = System.nanoTime();
            long lastProgress = start;
            Results r = new Results(masterSeed);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> { r.writeSummary(logs); logs.flush(); }));
            int threads = threads();
            java.util.concurrent.ExecutorService pool =
                    threads > 1 ? java.util.concurrent.Executors.newFixedThreadPool(threads) : null;
            java.util.ArrayDeque<BatchJob> inFlight = new java.util.ArrayDeque<>();
            try {
                while ((maxCases <= 0 || r.cases < maxCases) && System.nanoTime() < deadline) {
                    // Fill the in-flight window (main-thread generation keeps
                    // the case sequence deterministic regardless of threads).
                    while (pool != null && inFlight.size() < threads * 2
                            && (maxCases <= 0 || r.cases + countQueued(inFlight) < maxCases)
                            && System.nanoTime() < deadline) {
                        BatchJob job = submit(pool, master, r);
                        if (job == null) break;
                        inFlight.add(job);
                    }
                    BatchJob job;
                    if (pool != null) {
                        if (inFlight.isEmpty()) break;
                        job = inFlight.poll();
                        boolean done;
                        try {
                            done = job.done().get();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        } catch (java.util.concurrent.ExecutionException e) {
                            throw new IllegalStateException("fuzz batch failed", e.getCause());
                        }
                        handleBatch(job, done, r, logs, maxCases);
                    } else {
                        job = submit(null, master, r);
                        if (job == null) break;
                        boolean done;
                        try {
                            done = runBatchWatched(job.pattern(), job.flags(), job.inputs(), job.os(), job.prog(), job.worker());
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                        handleBatch(job, done, r, logs, maxCases);
                    }
                    long now = System.nanoTime();
                    if (now - lastProgress > 15_000_000_000L) {
                        double mins = (now - start) / 60_000_000_000.0;
                        r.casesPerMinute = mins > 0 ? r.cases / mins : 0;
                        logs.progress(r, mins);
                        r.writeSummary(logs);
                        lastProgress = now;
                    }
                }
            } finally {
                if (pool != null) {
                    pool.shutdownNow();
                    // Drained (possibly hung) workers are daemon threads: the
                    // chunked-JVM soak discipline still bounds any sacrifice.
                }
            }
            double mins = (System.nanoTime() - start) / 60_000_000_000.0;
            r.casesPerMinute = mins > 0 ? r.cases / mins : 0;
            logs.progress(r, mins);
            r.writeSummary(logs);
            return r;
        } finally {
            if (prevWork != null) System.setProperty("tdfa.max.work", prevWork);
            else if (fuzzWork > 0) System.clearProperty("tdfa.max.work");
        }
    }

    private static long countQueued(java.util.ArrayDeque<BatchJob> q) {
        return q.size() * BATCH_K;
    }

    /** Draw one batch from the master stream, generate on THIS thread, and
     *  either submit to the pool or return the job for sequential execution. */
    private static BatchJob submit(java.util.concurrent.ExecutorService pool, SplittableRandom master, Results r) {
        // >>> 4 keeps batch*8+i < 2^63 (>>> 3 was wrong: batches ≥ 2^60
        // wrapped negative — bijective and replayable, but confusing in logs).
        long batch = master.nextLong() >>> 4;
        int flags = genFlags(batch);
        String pattern = genPattern(batch);
        // pattern-level generation guards fold once per batch
        r.ciSuppAvoidedTotal += ciSuppAvoided;
        r.ciRangeAvoidedTotal += ciRangeAvoided;
        String[] inputs = new String[BATCH_K];
        for (int i = 0; i < BATCH_K; i++) inputs[i] = genInput(batch, i, flags);
        Outcome[] os = new Outcome[BATCH_K];
        java.util.concurrent.atomic.AtomicInteger prog = new java.util.concurrent.atomic.AtomicInteger(-1);
        Thread[] worker = new Thread[1];
        if (pool != null) {
            java.util.concurrent.Future<Boolean> fut = pool.submit(
                    () -> runBatchWatched(pattern, flags, inputs, os, prog, worker));
            return new BatchJob(batch, flags, pattern, inputs, os, prog, worker, fut);
        }
        return new BatchJob(batch, flags, pattern, inputs, os, prog, worker, null);
    }

    /** Record a finished (or hung) batch: prefix outcomes, hang attribution
     *  via the batch's own worker thread (post-mortem stack), exact case cap. */
    private static void handleBatch(BatchJob job, boolean done, Results r, Logs logs, long maxCases) {
        long batch = job.batch();
        int k = done ? BATCH_K : job.prog().get();   // on hang: outcomes < k are real, k is the victim
        for (int i = 0; i < k && (maxCases <= 0 || r.cases < maxCases); i++) {
            r.record(batch * BATCH_K + i, job.os()[i], logs);
            r.cases++;
        }
        if (!done) {
            int victim = k < 0 ? 0 : k;   // -1 = compile hang: replay head case (fuzz.one recompiles)
            if (r.cases < maxCases || maxCases <= 0) {
                r.hangs++;
                logs.hang(batch * BATCH_K + victim, new Case(job.pattern(), job.inputs()[victim], job.flags()), r, job.worker()[0]);
                r.cases++;
            }
        }
    }

    // ---- one case ----

    record Case(String pattern, String input, int flags) {}

    /** Engines compiled once per batch. A non-null tag means the compile
     *  path produced that protocol string for EVERY input (rejection, or a
     *  compile-time runtime exception); exc carries the exception detail
     *  lines to attach to each Outcome, matching the old per-case strings. */
    static final class Prepared {
        String pattern;
        int flags;
        com.google.re2j.Pattern oracle;      String oracleTag;
        io.github.jemmix.tdfa.Pattern asm;   String asmTag;   String asmExc;
        io.github.jemmix.tdfa.Pattern vm;    String vmTag;    String vmExc;
    }

    static Prepared prepare(String pattern, int flags) {
        Prepared p = new Prepared();
        p.pattern = pattern;
        p.flags = flags;
        try {
            p.oracle = com.google.re2j.Pattern.compile(pattern, flags);
        } catch (RuntimeException e) {
            p.oracleTag = "<reject>";
        }
        try {
            p.asm = io.github.jemmix.tdfa.Pattern.compile(pattern, flags, null, Re2jUnicodeProvider.INSTANCE);
        } catch (io.github.jemmix.tdfa.core.PatternSyntaxException e) {
            p.asmTag = "<reject:" + firstLine(e.getMessage()) + ">";
        } catch (RuntimeException e) {
            p.asmTag = "<exception:" + e.getClass().getSimpleName() + ">";
            p.asmExc = "asm " + e.getClass().getSimpleName() + ": " + firstLine(e.getMessage());
        }
        try {
            p.vm = io.github.jemmix.tdfa.Pattern.compile(pattern, flags, io.github.jemmix.tdfa.tdfa.TdfaRunner::new, Re2jUnicodeProvider.INSTANCE);
        } catch (io.github.jemmix.tdfa.core.PatternSyntaxException e) {
            p.vmTag = "<reject:" + firstLine(e.getMessage()) + ">";
        } catch (RuntimeException e) {
            p.vmTag = "<exception:" + e.getClass().getSimpleName() + ">";
            p.vmExc = "vm " + e.getClass().getSimpleName() + ": " + firstLine(e.getMessage());
        }
        return p;
    }

    /** One (pattern, input) case against prepared engines. Protocol strings
     *  identical to the former per-case compile path. */
    static Outcome matchCase(Prepared pr, Case c) {
        Outcome o = new Outcome(c);
        if (pr.oracle != null) {
            try {
                o.oracle = compute(pr.oracle, c.input());
            } catch (RuntimeException e) {
                o.oracle = "<reject>";
            }
        } else {
            o.oracle = pr.oracleTag;
        }
        o.asm = runEngine(pr.asm, pr.asmTag, pr.asmExc, "asm", c, o);
        o.vm = runEngine(pr.vm, pr.vmTag, pr.vmExc, "vm", c, o);
        return o;
    }

    static String runEngine(io.github.jemmix.tdfa.Pattern p, String tag, String exc, String engTag, Case c, Outcome o) {
        if (tag != null) {
            if (exc != null) o.exceptions.add(exc);
            return tag;
        }
        try {
            return compute(p, c.input());
        } catch (RuntimeException e) {
            o.exceptions.add(engTag + " " + e.getClass().getSimpleName() + ": " + firstLine(e.getMessage()));
            return "<exception:" + e.getClass().getSimpleName() + ">";
        }
    }

    static Outcome runOne(Case c) {
        return matchCase(prepare(c.pattern(), c.flags()), c);
    }

    // ---- match protocol ----

    /** Iteration cap: bounds a broken empty-match advance (an engine that
     *  never advances yields one span per call forever); 64 ≫ any match
     *  count a ≤30-char input can produce, so a healthy case never clips. */
    static final int MAX_MATCHES = 64;

    /** Five probes per case, one string, compared for exact equality:
     *  <ul>
     *   <li>{@code F} — first {@code find()} with overall and every group's
     *       span. Spans strictly subsume the old text protocol: identical
     *       spans on the same input ARE identical texts, and null-vs-empty
     *       groups become {@code -} vs {@code s..s} (the old protocol
     *       skipped nulls — indistinguishable). The old protocol also never
     *       compared positions at all: same-text-different-span passed.</li>
     *   <li>{@code I} — {@code find()} iteration to exhaustion on the SAME
     *       matcher: the continuation and empty-match-advance paths, spans
     *       per match. {@code ]$} = cap hit (spin-bound, never healthy).</li>
     *   <li>{@code M} — {@code matches()}: the separately-anchored
     *       whole-input engine (zero prior fuzz coverage).</li>
     *   <li>{@code L} — {@code lookingAt()}: prefix match.</li>
     *   <li>{@code R} — {@code find(len/2)} on a fresh matcher: the
     *       reset-and-restart path; deliberately can land inside a
     *       surrogate pair.</li>
     *  </ul>
     *  Fresh matchers for M/L/R keep the probes independent (attribution
     *  reads off the diverged probe tag). No free text in the format —
     *  spans only — so probe tags are unambiguous in diff reporting. */
    static String compute(io.github.jemmix.tdfa.Pattern p, CharSequence in) {
        StringBuilder sb = new StringBuilder(96);
        io.github.jemmix.tdfa.core.Matcher m = p.matcher(in);
        boolean found = m.find();
        if (found) spanTdfa(sb.append("F=true "), m); else sb.append("F=false");
        sb.append(" I=[");
        int n = 0;
        if (found) {
            spanTdfa(sb, m);
            while (++n < MAX_MATCHES && m.find()) spanTdfa(sb, m);
        }
        sb.append(n == MAX_MATCHES ? "]+$" : "]");
        io.github.jemmix.tdfa.core.Matcher mm = p.matcher(in);
        if (mm.matches()) spanTdfa(sb.append(" M=true "), mm); else sb.append(" M=false");
        io.github.jemmix.tdfa.core.Matcher ml = p.matcher(in);
        if (ml.lookingAt()) spanTdfa(sb.append(" L=true "), ml); else sb.append(" L=false");
        io.github.jemmix.tdfa.core.Matcher mr = p.matcher(in);
        if (mr.find(in.length() / 2)) spanTdfa(sb.append(" R=true "), mr); else sb.append(" R=false");
        return sb.toString();
    }

    static String compute(com.google.re2j.Pattern p, CharSequence in) {
        StringBuilder sb = new StringBuilder(96);
        com.google.re2j.Matcher m = p.matcher(in);
        boolean found = m.find();
        if (found) spanRe2j(sb.append("F=true "), m); else sb.append("F=false");
        sb.append(" I=[");
        int n = 0;
        if (found) {
            spanRe2j(sb, m);
            while (++n < MAX_MATCHES && m.find()) spanRe2j(sb, m);
        }
        sb.append(n == MAX_MATCHES ? "]+$" : "]");
        com.google.re2j.Matcher mm = p.matcher(in);
        if (mm.matches()) spanRe2j(sb.append(" M=true "), mm); else sb.append(" M=false");
        com.google.re2j.Matcher ml = p.matcher(in);
        if (ml.lookingAt()) spanRe2j(sb.append(" L=true "), ml); else sb.append(" L=false");
        com.google.re2j.Matcher mr = p.matcher(in);
        if (mr.find(in.length() / 2)) spanRe2j(sb.append(" R=true "), mr); else sb.append(" R=false");
        return sb.toString();
    }

    /** {@code s..e (g1s..g1e g2s..g2e ...)}; non-participating group = {@code -}. */
    static void spanTdfa(StringBuilder sb, io.github.jemmix.tdfa.core.Matcher m) {
        sb.append(m.start()).append("..").append(m.end());
        int gc = m.groupCount();
        if (gc > 0) {
            sb.append(" (");
            for (int i = 1; i <= gc; i++) {
                if (i > 1) sb.append(' ');
                int s = m.start(i);
                sb.append(s < 0 ? "-" : s + ".." + m.end(i));
            }
            sb.append(')');
        }
    }

    static void spanRe2j(StringBuilder sb, com.google.re2j.Matcher m) {
        sb.append(m.start()).append("..").append(m.end());
        int gc = m.groupCount();
        if (gc > 0) {
            sb.append(" (");
            for (int i = 1; i <= gc; i++) {
                if (i > 1) sb.append(' ');
                int s = m.start(i);
                sb.append(s < 0 ? "-" : s + ".." + m.end(i));
            }
            sb.append(')');
        }
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

    // ---- compile-flag matrix ----

    /** Matrix bits (identical values in tdfa and re2j 1.8, verified against
     *  both Pattern classes — the drop-in contract extends to flag values).
     *  Excluded: {@code UNICODE_CHARACTER_CLASS} (tdfa-only, no re2j oracle)
     *  and {@code DISABLE_UNICODE_GROUPS} (inert until \p{} generation lands:
     *  both engines only differ on \p{} acceptance, which the generator never
     *  emits — dead entropy today, revisit with the \p{} generator round). */
    static final int FLAG_CI = io.github.jemmix.tdfa.Pattern.CASE_INSENSITIVE;
    static final int FLAG_DOTALL = io.github.jemmix.tdfa.Pattern.DOTALL;
    static final int FLAG_MULTILINE = io.github.jemmix.tdfa.Pattern.MULTILINE;
    static final int FLAG_LONGEST = io.github.jemmix.tdfa.Pattern.LONGEST_MATCH;

    /** Per-batch compile flags, drawn from a stream DECOUPLED from the
     *  pattern's ({@code SplittableRandom(batch)}): a given batch produces
     *  the bit-identical pattern it always did, so historical caseSeeds keep
     *  their patterns; only the flags (and, under MULTILINE, the inputs)
     *  move. 40% of batches stay at flags=0 — regression continuity with the
     *  ~300M-case flags=0 history — otherwise each of CI/DOTALL/MULTILINE/
     *  LONGEST is drawn independently (p=½): every flag in ~30% of batches,
     *  all-four in ~3.75%. */
    static int genFlags(long batch) {
        SplittableRandom rnd = new SplittableRandom(batch ^ 0x6D69786C6F6E676DL);
        if (rnd.nextInt(10) < 4) return 0;
        int f = 0;
        if (rnd.nextBoolean()) f |= FLAG_CI;
        if (rnd.nextBoolean()) f |= FLAG_DOTALL;
        if (rnd.nextBoolean()) f |= FLAG_MULTILINE;
        if (rnd.nextBoolean()) f |= FLAG_LONGEST;
        return f;
    }

    /** Batched generation (generator v3): caseSeed → batch = floorDiv(s, K),
     *  index = floorMod(s, K). The pattern is a pure function of the batch,
     *  the input a pure function of (batch, index) with a deterministic
     *  boundary bias per index — so every batch caseSeed is independently
     *  replayable via {@code fuzz.one}. One compile per batch serves all K
     *  inputs: compile+codegen is ~45% of per-case cost, matching is µs.
     *  Generator version bump — pre-v3 caseSeeds are dead (as in rounds
     *  5/6). */
    static final int BATCH_K = 8;

    static Case generate(long caseSeed) {
        long batch = Math.floorDiv(caseSeed, BATCH_K);
        int idx = (int) Math.floorMod(caseSeed, BATCH_K);
        int flags = genFlags(batch);
        return new Case(genPattern(batch), genInput(batch, idx, flags), flags);
    }

    static String genPattern(long batch) {
        ciSuppAvoided = 0;
        ciRangeAvoided = 0;
        return expr(new SplittableRandom(batch), 0, false);
    }

    /** Input = pure fn(batch, index). Base draw as before (pool mix), then a
     *  deterministic per-index boundary transform: the historical bug
     *  families were input-position-sensitive (word/anchor boundaries,
     *  surrogate-pair interiors, $ vs \z), which one random haystack per
     *  pattern systematically misses. Under MULTILINE flags, 1-2 interior
     *  '\n' are inserted so ^/$ actually see lines (the per-index transforms
     *  provide at most one trailing '\n'). */
    static String genInput(long batch, int idx, int flags) {
        SplittableRandom rnd = new SplittableRandom(batch * 0x9E3779B97F4A7C15L ^ (idx + 1) * 0xBF58476D1CE4E5B9L);
        int inLen = rnd.nextInt(0, 25);
        StringBuilder in = new StringBuilder(inLen * 2);
        for (int i = 0; i < inLen; i++) in.appendCodePoint(pickInputCp(rnd));
        switch (idx) {
            case 1 -> in.append('\n');            // $ / (?m)$ / \z divergence axis
            case 2 -> { in.insert(0, ' '); in.append(' '); }   // \b at both ends
            case 3 -> in.insert(in.length() / 2, (char) POOL_LONE[rnd.nextInt(POOL_LONE.length)]);  // lone surrogate mid-string
            case 4 -> in.appendCodePoint(POOL_SUPP[rnd.nextInt(POOL_SUPP.length)])
                        .append((char) POOL_ASCII[rnd.nextInt(POOL_ASCII.length)]);   // pair adjacent to ASCII
            case 5 -> in.setLength(rnd.nextInt(0, 4));            // near-empty (may split a pair — deliberate)
            case 6 -> { for (int[] pool : new int[][]{POOL_ASCII, POOL_EDGE, POOL_UNICODE, POOL_SUPP, POOL_LONE})
                            in.appendCodePoint(pool[rnd.nextInt(pool.length)]); }     // one of every pool
            default -> {}                                          // idx 0, 7: plain random
        }
        if ((flags & FLAG_MULTILINE) != 0) {
            int extra = 1 + rnd.nextInt(2);
            for (int i = 0; i < extra; i++) in.insert(rnd.nextInt(in.length() + 1), '\n');
        }
        return in.toString();
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
        long cases, failures, bothReject, flagged, ciSuppAvoidedTotal, ciRangeAvoidedTotal, knownDivergence;
        final java.util.Map<io.github.jemmix.tdfa.parity.LayeredComparator.Layer, Integer> layerCounts = new java.util.EnumMap<>(io.github.jemmix.tdfa.parity.LayeredComparator.Layer.class);
        long hangs, hangsOurs, hangsOracle;
        double casesPerMinute;
        final Map<String, Sig> signatures = new LinkedHashMap<>();
        Results(long masterSeed) { this.masterSeed = masterSeed; }

        void record(long caseSeed, Outcome o, Logs logs) {
            // (generation-guard counters fold once per batch in run(), not here)
            if (o.c.flags() != 0) flagged++;
            if (o.failed()) {
                // Known-divergence classification is RELEASED-oracle-only: the
                // documented re2j lone-surrogate bugs are what it knows, and
                // the patched fork exists precisely to make that family
                // visible again (round 26c: the flags≠0 asm==vm shortcut
                // swallowed a real fork-overcorrection finding for exactly
                // this reason — it had no PARSER cross-check and is gone).
                String known = RELEASED_ORACLE ? knownDivergence(o) : null;
                // Layered attribution (failure path only — zero soak cost):
                // re2j/sim/vm/asm vote; the verdict names the failing layer.
                // Runs at flags=0 only: its four columns have no flag
                // plumbing, and attributing a flags≠0 case at flags=0 would
                // name a layer under different semantics — worse than no
                // attribution. Those records carry layer=FLAGS; fuzz.one
                // replay shows the full picture for triage.
                String layerStr = "FLAGS";
                if (o.c.flags() == 0) {
                    var report = LAYERED.compare(o.c.pattern(), o.c.input());
                    layerStr = report.layer().name();
                    layerCounts.merge(report.layer(), 1, Integer::sum);
                    if (known != null && report.layer() == io.github.jemmix.tdfa.parity.LayeredComparator.Layer.PARSER) {
                        // The known divergence is a PARSER-boundary semantics
                        // difference (whole stack self-consistent, oracle alone
                        // differs). Any other layer with a lone-surrogate pattern
                        // is a REAL finding wearing the same coat — v3's first
                        // soak proved it: a CONSTRUCTION-layer needle bug was
                        // swallowed here as "known" for a whole night's run.
                        knownDivergence++;
                        logs.failure(caseSeed, o, "KNOWN_DIVERGENCE (" + known + ")", layerStr);
                        return;
                    }
                }
                failures++;
                String kind = kindOf(o);
                String sig = kind + " | shape~" + shape(o.c.pattern());
                Sig s = signatures.computeIfAbsent(sig, k -> new Sig(kind));
                s.total++;
                if (s.recorded < 8) {
                    s.recorded++;
                    logs.failure(caseSeed, o, kind, layerStr);
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
         *   <li><b>lone-low surrogate patterns</b> — re2j's literal-prefix
         *       fast path lands on pair interiors; we keep
         *       codepoint-boundary semantics either way. Applies when both
         *       our engines agree with each other and the pattern contains
         *       a lone low surrogate. (Plain {@code (?i)} folds full Unicode
         *       simple folding exactly like re2j since 12d9921 — any fold
         *       divergence is a real bug, not a known one.)</li>
         * </ul>
         */
        static String knownDivergence(Outcome o) {
            String p = o.c.pattern();
            for (int i = 0; i < p.length(); i++) {
                char c = p.charAt(i);
                if (c >= 0xD800 && c <= 0xDBFF) { i++; continue; }  // well-formed pair: interior low is not a lone low
                if (c >= 0xDC00 && c <= 0xDFFF && o.asm.equals(o.vm) && !o.asm.equals(o.oracle))
                    return "re2j matches lone-low pattern at/into pair interior; JDK agrees with us";            }
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
            if (!o.asm.equals(o.oracle) && !o.vm.equals(o.oracle))
                return "RESULT_MISMATCH (both engines, probe " + probeDiff(o.oracle, o.asm) + ")";
            if (!o.asm.equals(o.oracle))
                return "RESULT_MISMATCH (asm only, probe " + probeDiff(o.oracle, o.asm) + ")";
            return "RESULT_MISMATCH (vm only, probe " + probeDiff(o.oracle, o.vm) + ")";
        }

        /** Which probe diverged: walk to the first differing char, then back
         *  to the nearest probe tag. Sound because the protocol carries no
         *  free text (spans only) — an F/I/M/L/R char IS a tag. */
        static char probeDiff(String a, String b) {
            int min = Math.min(a.length(), b.length());
            int i = 0;
            while (i < min && a.charAt(i) == b.charAt(i)) i++;
            for (int j = Math.min(i, a.length()) - 1; j >= 0; j--) {
                char c = a.charAt(j);
                if (c == 'F' || c == 'I' || c == 'M' || c == 'L' || c == 'R') return c;
            }
            return '?';
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
        /** Continuous low-overhead JFR ("default" settings, in-memory
         *  circular, ~1%) dumped to out/hang-<caseSeed>.jfr when a hang is
         *  recorded — the sacrificed thread's method-sample profile ships
         *  WITH the record, no re-run needed. -Dfuzz.jfr=false opts out. */
        private final jdk.jfr.Recording hangWatch;
        private static final java.lang.management.RuntimeMXBean RUNTIME_MX =
                java.lang.management.ManagementFactory.getRuntimeMXBean();
        private static final java.time.format.DateTimeFormatter TS_FMT = java.time.format.DateTimeFormatter
                .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS").withZone(java.time.ZoneId.systemDefault());

        Logs(Path dir) throws IOException {
            this.dir = dir;
            jdk.jfr.Recording watch = null;
            if (Boolean.parseBoolean(System.getProperty("fuzz.jfr", "true"))) {
                try {
                    watch = new jdk.jfr.Recording(jdk.jfr.Configuration.getConfiguration("default"));
                    watch.setName("fuzz-hang-watch");
                    watch.setMaxSize(64L << 20);   // circular, in-memory
                    watch.start();
                } catch (Throwable t) {
                    watch = null;   // never let diagnostics kill the soak
                }
            }
            this.hangWatch = watch;
            // fuzz.append: keep failures.ndjson/progress.log across chunked
            // soak runs (scripts/fuzz-soak.sh); summary.txt always reflects
            // the latest chunk.
            StandardOpenOption[] opts = Boolean.getBoolean("fuzz.append")
                    ? new StandardOpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.APPEND}
                    : new StandardOpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING};
            failures = new PrintWriter(Files.newBufferedWriter(dir.resolve("failures.ndjson"), opts), false);
            progress = new PrintWriter(Files.newBufferedWriter(dir.resolve("progress.log"), opts), true);
        }

        void failure(long caseSeed, Outcome o, String kind, String layer) {
            // ts/upMs on every record: uptime correlates with gc-*.log
            // ([574,404s] prefixes) and makes intra-chunk clustering (the
            // humongous-GC hang signature) visible without progress.log
            // cross-referencing.
            failures.println("{\"caseSeed\":" + caseSeed + ",\"ts\":\"" + TS_FMT.format(java.time.Instant.now())
                    + "\",\"upMs\":" + RUNTIME_MX.getUptime()
                    + ",\"flags\":" + o.c.flags()
                    + ",\"kind\":\"" + kind.replace('"', '\'')
                    + "\",\"layer\":\"" + layer + "\""
                    + ",\"pattern\":\"" + escape(o.c.pattern()) + "\",\"input\":\"" + escape(o.c.input())
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
        void hang(long caseSeed, Case c, Results r, Thread w) {
            StringBuilder st = new StringBuilder();
            if (w != null) for (StackTraceElement e : w.getStackTrace()) st.append(e).append(" | ");
            boolean ours = isEngineStack(st);
            if (ours) r.hangsOurs++; else r.hangsOracle++;
            // Spin vs stall, recorded at the source: a worker that BURNED CPU
            // for the whole watchdog window really hung (finding); one whose
            // thread-CPU is far below wall was stalled — GC pause/compaction
            // or scheduler starvation (overnight 491362528: every HANG record
            // was this kind; the same seeds replay in milliseconds). Post-mortem
            // triage reads cpuMs vs the 10 s watchdog, no re-run needed.
            long cpuMs = -1;
            if (w != null) {
                try {
                    cpuMs = java.lang.management.ManagementFactory.getThreadMXBean()
                            .getThreadCpuTime(w.getId()) / 1_000_000;
                } catch (Throwable ignore) { /* thread died already */ }
            }
            String verdict = cpuMs < 0 ? "unknown"
                    : cpuMs >= CASE_TIMEOUT_MS / 2 ? "spin" : "stalled";
            if (hangWatch != null) {
                try {
                    hangWatch.dump(dir.resolve("hang-" + caseSeed + ".jfr"));
                } catch (Throwable ignore) { }
            }
            failures.println("{\"caseSeed\":" + caseSeed + ",\"ts\":\"" + TS_FMT.format(java.time.Instant.now())
                    + "\",\"upMs\":" + RUNTIME_MX.getUptime()
                    + ",\"flags\":" + c.flags()
                    + ",\"kind\":\"HANG_" + (ours ? "ENGINE" : "ORACLE")
                    + "\",\"cpuMs\":" + cpuMs + ",\"verdict\":\"" + verdict + "\""
                    + ",\"pattern\":\"" + escape(c.pattern()) + "\",\"input\":\"" + escape(c.input())
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


        void summary(Results r) {
            try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(dir.resolve("summary.txt")))) {
                w.println("masterSeed: " + r.masterSeed);
                w.println("oracle: " + (RELEASED_ORACLE ? "re2j 1.8 released" : "re2j 1.8 patched fork (known-divergence classifier off)"));
                w.println("cases: " + r.cases + "  failures: " + r.failures + "  bothReject: " + r.bothReject
                        + "  flagged: " + r.flagged + "  knownDivergence: " + r.knownDivergence
                        + "  hangsEngine: " + r.hangsOurs + "  hangsOracle: " + r.hangsOracle);
                w.printf("rate: %.1f cases/min%n", r.casesPerMinute);
                w.println("ciSuppAvoided (known-gap constructs not generated): " + r.ciSuppAvoidedTotal
                        + "  ciWideRangeAvoided (oracle-hang guard): " + r.ciRangeAvoidedTotal);
                if (!r.layerCounts.isEmpty()) {
                    w.println();
                    w.println("failure layers (attributed):");
                    r.layerCounts.forEach((layer, n) -> w.printf("  %6d  %s%n", n, layer));
                }
                w.println();
                w.println("failure signatures (deduped):");
                r.signatures.forEach((sig, s) -> w.printf("  %6d  %s%n", s.total, sig));
            } catch (IOException ignored) { }
        }

        void progress(Results r, double mins) {
            progress.printf("t=%6.1fmin cases=%d failures=%d known=%d flagged=%d hangE=%d hangO=%d sigs=%d%n",
                    mins, r.cases, r.failures, r.knownDivergence, r.flagged, r.hangsOurs, r.hangsOracle, r.signatures.size());
        }

        void flush() { failures.flush(); progress.flush(); }

        @Override public void close() {
            failures.close(); progress.close();
            if (hangWatch != null) { try { hangWatch.close(); } catch (Throwable ignore) { } }
        }
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
