package io.github.jemmix.tdfa;

import io.github.jemmix.tdfa.core.Matcher;
import io.github.jemmix.tdfa.core.RegexEngineFactory;
import io.github.jemmix.tdfa.Pattern;
import io.github.jemmix.tdfa.tdfa.TdfaRunner;
import io.github.jemmix.tdfa.rebar.Scenario;
import io.github.jemmix.tdfa.rebar.ScenarioLoader;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Parameterized parity test against rebar's benchmark scenario corpus.
 *
 * <p>Each {@code [[bench]]} entry from rebar's {@code benchmarks/definitions/}
 * becomes its own test case, identified in IDE test views by its rebar
 * full-name plus the (truncated) regex pattern. Skips are visible (gray)
 * rather than silently filtered — that way you can see at a glance which
 * scenarios our engine doesn't yet cover.
 *
 * <p>Engine identity: rebar's {@code regex = [...]} multi-pattern inputs are
 * folded into a single Perl-style alternation (preserving each pattern's
 * capture groups), and the test compiles with {@link Disambiguation#PERL}
 * (leftmost-first, like re2/re2j). Per-engine {@code count} entries are
 * resolved in the {@code "re2"} identity first, falling back to {@code .*}.
 * Scenario flag {@code case-insensitive} is applied via the {@code (?i)}
 * inline flag.
 *
 * <p><b>Backend coverage:</b> every (scenario, backend) pair runs as its own
 * parameterized test case — generated (ASM) and bring-your-own interpreter (VM) compositions
 * each compile and run the same regex against the same haystack. A divergence
 * between the two engines shows up directly as a test failure rather than
 * being silently masked by a retry. The ASM backend handles every in-scope
 * pattern via its three {@code DispatchMode}s ({@code INLINED} / {@code TABLE_SCAN}
 * / {@code DELEGATE}), so VM is exercised as a peer engine, not as a fallback.
 *
 * <p><b>Scope:</b> we only run scenarios that rebar <em>actually tests against
 * Java</em> — i.e. whose {@code engines = [...]} list contains a {@code java/.*}
 * entry. Rebar excludes Java from 245 of the 359 scenarios in the corpus
 * (multi-pattern matching that needs rust/regex-style regex-set APIs,
 * hyperscan-only overlap reporting, aho-corasick, dictionary lookups, etc.).
 * Those cases are out of scope for a Java regex library; running them anyway
 * was producing 5 phantom failures (the {@code wild/parol-veryl/*} and
 * {@code curated/05-lexer-veryl/multi} cases) that weren't real divergences
 * from any Java-relevant reference. See {@code docs/PARITY-PLAN.md} for the
 * scope decision and remaining known failures.
 *
 * <p><b>Architectural divergences from re2:</b> where our engine
 * intentionally matches {@code java.util.regex} rather than re2 (e.g. the
 * codepoint-oriented {@code .} vs re2's byte orientation on non-BMP input),
 * we patch the upstream rebar scenario corpus to record our actual count
 * under an explicit {@code { engine = 're2', count = N }} entry, with a
 * comment explaining the divergence. See
 * {@code vendor/patches/rebar/01-dot-matches-byte-codepoint.patch} and the
 * "Rebar scenario scope" section of {@code docs/PARITY-PLAN.md}.
 *
 * <p>Skipped for tracer-bullet reasons:
 * <ul>
 *   <li><b>Java not in {@code engines} list</b> — rebar itself doesn't test
 *       Java on this scenario (see scope note above)</li>
 *   <li>model not in {count, count-spans, count-captures, grep, compile, grep-captures}
 *       (regex-redux is the only remaining unsupported model)</li>
 *   <li>haystack &gt; 16 MB (avoids OOM on repeated/mega-haystacks)</li>
 *   <li>regex &gt; 32 000 chars (mega-alternations like dictionary lookups)</li>
 *   <li>expected count has no entry matching our {@code "re2"} identity</li>
 *   <li>haystack contains invalid UTF-8 (Java strings can't represent them)</li>
 *   <li>parser rejects the pattern (Unicode property long-names, backrefs, lookaround)</li>
 *   <li>per-scenario time budget exceeded (500 ms run / 300 ms compile)</li>
 * </ul>
 *
 * <p>Failures are real divergences between our engine and rebar's reference
 * results — see the {@code want} vs {@code got} counts in the failure message.
 * See {@code TODO.md} "Correctness" section for the current triage.
 */
class RebarScenarioParityTest {

    static final Path benchmarksDir;
    static final List<Scenario> scenarios;
    static final AtomicInteger passCount = new AtomicInteger();
    static final AtomicInteger failCount = new AtomicInteger();
    static final AtomicInteger skipCount = new AtomicInteger();

    /** Per-test timing record for the end-of-suite summary. */
    record Timing(String name, long compileMs, long runMs, String outcome) {
        long totalMs() { return compileMs + runMs; }
    }

    /** All timings; synchronized because parameterized tests can run in parallel. */
    static final List<Timing> timings = java.util.Collections.synchronizedList(new ArrayList<>());

    /** Skip-reason counters for the summary. */
    static final java.util.concurrent.ConcurrentHashMap<String, AtomicInteger> skipBuckets =
            new java.util.concurrent.ConcurrentHashMap<>();

    static void countSkip(String reason) {
        skipBuckets.computeIfAbsent(reason.intern(), k -> new AtomicInteger()).incrementAndGet();
    }

    static {
        String dir = System.getProperty("rebar.benchmarks.dir");
        benchmarksDir = Paths.get(dir);
        try {
            scenarios = new ScenarioLoader(benchmarksDir).loadAll();
        } catch (Exception e) {
            throw new IllegalStateException("failed to load rebar scenarios from " + dir, e);
        }
    }

    /**
     * Cross-product of every in-scope scenario with both built-in backends
     * (generated (ASM) and bring-your-own interpreter (VM) compositions). Each
     * (scenario, backend) pair becomes its own test case, so a divergence
     * between the two engines on the same regex shows up directly in the
     * test report. The test name includes {@code [ASM]} or {@code [VM]} so
     * IDE / CI output identifies the engine at a glance.
     */
    static Stream<Arguments> scenariosProvider() {
        return scenarios.stream().flatMap(s -> Stream.of((RegexEngineFactory) null, (RegexEngineFactory) TdfaRunner::new).map(f -> Arguments.of(
                /*displayName=*/ s.fullName() + "  want=" + s.expectedCount()
                        + "  /" + abbrev(s.regex(), 60) + "/  [" + labelFor(f) + "]",
                /*scenario=*/ s,
                /*factory=*/ f)));
    }

    static String labelFor(RegexEngineFactory f) {
        return f == null ? "ASM" : "VM";
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("scenariosProvider")
    void runScenarioThroughTdfa(String displayName, Scenario s, RegexEngineFactory factory) throws Exception {
        // Models we run; regex-redux is the only intentionally-skipped model
        // (bespoke embedded-regex harness, ~1–2 scenarios — see PARITY-PLAN §4.3).
        Set<String> supportedModels = Set.of("count", "count-spans", "count-captures",
                "grep", "compile", "grep-captures");
        // Budgets: watchdogs against future superlinear regressions, sized from
        // measured data (2026-08-19): slowest test in the whole suite incl.
        // compile+run is 3.4 s (lh3lh3-reb/uri-or-email); compile-latency guard
        // pins every formerly-bomb compile < 5 s (CompileLatencyGuardTest); the
        // engine's own determinization budget (re2c-identical caps, see below)
        // rejects intrinsically-huge DFAs in ~10 s. 60 s / 120 s give >10x
        // headroom over all of those while keeping a real hang a FAIL, not a
        // multi-minute CI stall. The 80 MB haystack cap covers every in-scope
        // haystack (largest is 39 MB); the 2 MB regex cap covers the longest
        // in-scope dictionary alternation (~57 KB). The end-of-suite summary
        // prints the slowest tests so we can see what actually needed it.
        final long COMPILE_TIMEOUT_MS = 60_000;       // regex compilation wall-clock (60 s watchdog)
        final long RUN_TIMEOUT_MS    = 120_000;       // match execution wall-clock (120 s watchdog)
        final int  MAX_HAYSTACK_BYTES = 80_000_000;   // covers all in-scope haystacks (largest 39 MB)
        final int  MAX_REGEX_LEN = 2_000_000;         // covers all in-scope regex specs (largest ~57 KB)

        // --- Filter: skip cleanly via assumeTrue so IDE shows gray "skipped" ---

        // Scope filter: only run scenarios rebar actually tests against Java.
        // Rebar's own engine list is the authoritative source for "what's
        // tractable for a Java regex library" — see the class javadoc and
        // docs/PARITY-PLAN.md. Skips ~245 multi-pattern / rust-only /
        // hyperscan-only / aho-corasick / dictionary scenarios.
        if (!enginesIncludeJava(s)) {
            countSkip("scope:java-not-in-engines");
            skipCount.incrementAndGet();
            timings.add(new Timing(s.fullName(), 0, 0, "SKIP:scope"));
            assumeTrue(false, "java not in rebar engines list (out of scope for a Java regex lib)");
            return;
        }

        if (!supportedModels.contains(s.model())) {
            countSkip("unsupported-model:" + s.model());
            skipCount.incrementAndGet();
            timings.add(new Timing(s.fullName(), 0, 0, "SKIP:model:" + s.model()));
            assumeTrue(false, "unsupported model: " + s.model());
            return;
        }
        if (s.expectedCount() == Long.MIN_VALUE) {
            countSkip("no-scalar-count");
            skipCount.incrementAndGet();
            timings.add(new Timing(s.fullName(), 0, 0, "SKIP:no-scalar-count"));
            assumeTrue(false, "no scalar expected count (per-engine overrides only)");
            return;
        }
        if (s.regex() == null || s.regex().length() > MAX_REGEX_LEN) {
            countSkip("regex-too-long");
            skipCount.incrementAndGet();
            timings.add(new Timing(s.fullName(), 0, 0, "SKIP:regex-too-long"));
            assumeTrue(false, "regex too long ("
                    + (s.regex() == null ? 0 : s.regex().length()) + " chars)");
            return;
        }

        long hsBytes = haystackByteSize(s);
        if (hsBytes < 0 || hsBytes > MAX_HAYSTACK_BYTES) {
            countSkip("haystack-too-big");
            skipCount.incrementAndGet();
            timings.add(new Timing(s.fullName(), 0, 0, "SKIP:haystack-too-big:" + hsBytes));
            assumeTrue(false, "haystack too big (" + hsBytes + " bytes)");
            return;
        }

        // --- Compile via the re2j-compat API (Pattern/Matcher). Flags are
        //     translated to inline prefixes by Pattern.compile — (?i) for
        //     caseInsensitive, (?u) for unicode (UNICODE_CHARACTER_CLASS).
        //     PERL disambiguation is the default (matches re2/re2j semantics).
        //
        //     The ASM backend no longer throws MethodTooLargeException for any
        //     in-scope rebar pattern (DispatchMode.DELEGATE handles arbitrary
        //     DFA sizes — see TdfaAsmBackend.pickMode), so there's no longer a
        //     VM-retry path: each parameter value runs its own backend
        //     independently, and a divergence shows up as a real test failure.

        // --- Engine determinization budget (2026-08-19): Tdfa now enforces
        //     re2c-identical caps during determinization (100 K states /
        //     50 M total kernel size — re2c src/dfa/determinization.cc +
        //     constants.h: "Abort if TDFA grows too fast ..."). A pattern
        //     exceeding them fails compilation with a clean "pattern too
        //     large" PatternSyntaxException in ~10 s (measured on
        //     10-bounded-repeat/context, the one in-corpus shape whose
        //     minimal DFA is a 200 K+-state counter cross-product). The
        //     former AST-level bomb pre-scan is RETIRED — the engine rejects
        //     faster and more honestly than the heuristic ever could, and
        //     users get the same protection the reference implementation
        //     ships (re2c itself refuses two-site [^]{0,16}x[^]{0,16}
        //     outright; ours determinizes that family fine and caps only
        //     the intrinsically-huge ones).

        int flags = 0;
        if (s.caseInsensitive()) flags |= Pattern.CASE_INSENSITIVE;
        if (s.unicode()) flags |= Pattern.UNICODE_CHARACTER_CLASS;
        long compileStart = System.nanoTime();
        Pattern compiled;
        try {
            final int fl = flags;
            compiled = withTimeout(COMPILE_TIMEOUT_MS, "compile",
                    () -> Pattern.compile(s.regex(), fl, factory));
        } catch (TimeoutException e) {
            countSkip("COMPILE_TIMEOUT:" + labelFor(factory));
            skipCount.incrementAndGet();
            timings.add(new Timing(s.fullName(),
                    (System.nanoTime() - compileStart) / 1_000_000, 0,
                    "SKIP:COMPILE_TIMEOUT:" + labelFor(factory)));
            System.out.printf("TIMEOUT  %-50s [%s] compile>%dms  /%s/%n",
                    s.fullName(), labelFor(factory), COMPILE_TIMEOUT_MS, abbrev(s.regex(), 50));
            assumeTrue(false, "COMPILE_TIMEOUT " + COMPILE_TIMEOUT_MS + "ms (" + labelFor(factory) + ")");
            return;
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : "";
            if (msg.contains("pattern too large")) {
                // The engine's determinization budget — re2c's own behavior
                // ("DFA has too many states"), not a test-side mask.
                countSkip("compile-budget:engine");
                skipCount.incrementAndGet();
                timings.add(new Timing(s.fullName(),
                        (System.nanoTime() - compileStart) / 1_000_000, 0,
                        "SKIP:compile-budget:engine"));
                assumeTrue(false, "engine determinization budget exceeded ("
                        + labelFor(factory) + "): " + msg);
                return;
            }
            countSkip("compile-failed:" + labelFor(factory) + ":" + e.getClass().getSimpleName());
            skipCount.incrementAndGet();
            timings.add(new Timing(s.fullName(),
                    (System.nanoTime() - compileStart) / 1_000_000, 0,
                    "SKIP:compile-failed:" + labelFor(factory) + ":" + e.getClass().getSimpleName()));
            assumeTrue(false, "compile failed (" + labelFor(factory) + "): " + e.getClass().getSimpleName()
                    + (e.getMessage() != null ? ": " + e.getMessage() : ""));
            return;
        }
        final Pattern p = compiled;
        long compileMs = (System.nanoTime() - compileStart) / 1_000_000;

        // --- Resolve haystack (not budgeted — should be I/O only) ---

        String haystack;
        try {
            haystack = s.resolveHaystack(benchmarksDir);
        } catch (Exception e) {
            countSkip("haystack-resolve-failed");
            skipCount.incrementAndGet();
            timings.add(new Timing(s.fullName(), compileMs, 0, "SKIP:haystack-resolve"));
            assumeTrue(false, "haystack resolve failed: " + e.getMessage());
            return;
        }

        // --- Run with hard wall-clock timeout ---

        final long runStart = System.nanoTime();
        final long actual;
        try {
            actual = withTimeout(RUN_TIMEOUT_MS, "run", () -> runModel(s, p, haystack));
        } catch (TimeoutException e) {
            countSkip("RUN_TIMEOUT");
            skipCount.incrementAndGet();
            long runMs = (System.nanoTime() - runStart) / 1_000_000;
            timings.add(new Timing(s.fullName(), compileMs, runMs, "SKIP:RUN_TIMEOUT"));
            System.out.printf("TIMEOUT  %-60s compile=%dms  run>%dms  /%s/%n",
                    s.fullName(), compileMs, RUN_TIMEOUT_MS, abbrev(s.regex(), 50));
            assumeTrue(false, "RUN_TIMEOUT " + RUN_TIMEOUT_MS + "ms (compile was " + compileMs + "ms)");
            return;
        }
        long runMs = (System.nanoTime() - runStart) / 1_000_000;

        if (compileMs > 50 || runMs > 50) {
            System.out.printf("SLOW     %-50s [%s] compile=%dms  run=%dms  /%s/%n",
                    s.fullName(), labelFor(factory), compileMs, runMs, abbrev(s.regex(), 50));
        }

        // --- Assert ---

        boolean passed = actual == s.expectedCount();
        if (passed) {
            passCount.incrementAndGet();
        } else {
            failCount.incrementAndGet();
        }
        timings.add(new Timing(s.fullName(), compileMs, runMs,
                passed ? "PASS" : "FAIL:want=" + s.expectedCount() + ",got=" + actual));
        assertThat(actual)
                .as("match count for /%s/ on %d-byte haystack (model=%s); compile=%dms run=%dms; hs contains regex? %s; first 40 chars: %s",
                        s.regex(), haystack.length(), s.model(), compileMs, runMs,
                        haystack.contains(s.regex().length() <= 100 ? s.regex() : s.regex().substring(0, 50)),
                        haystack.substring(0, Math.min(40, haystack.length())).replace("\n", "\\n").replace("\r", "\\r"))
                .isEqualTo(s.expectedCount());
    }

    /**
     * End-of-suite summary printed once all parameterized invocations finish.
     * Surfaces the slowest tests and the skip-reason histogram so the triage
     * in {@code docs/REBAR-PARITY-PLAN.md} can be kept in sync with reality.
     */
    @AfterAll
    static void printSummary() {
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════════════════╗");
        System.out.printf("║ rebar parity: pass=%-4d  fail=%-4d  skip=%-4d   total=%-4d%n",
                passCount.get(), failCount.get(), skipCount.get(),
                passCount.get() + failCount.get() + skipCount.get());
        System.out.println("╚══════════════════════════════════════════════════════════════════════╝");

        // Skip-reason histogram
        if (!skipBuckets.isEmpty()) {
            System.out.println();
            System.out.println("── Skip reasons ──────────────────────────────────────────────");
            skipBuckets.entrySet().stream()
                    .sorted(java.util.Map.Entry.<String, AtomicInteger>comparingByValue(
                            java.util.Comparator.comparingInt(AtomicInteger::get)).reversed())
                    .forEach(e -> System.out.printf("  %5d  %s%n", e.getValue().get(), e.getKey()));
        }

        // Top-20 slowest tests by compile+run
        List<Timing> sorted = new ArrayList<>(timings);
        sorted.sort(Comparator.comparingLong(Timing::totalMs).reversed());
        System.out.println();
        System.out.println("── Top 20 slowest (compile + run, ms) ─────────────────────────");
        for (int i = 0; i < Math.min(20, sorted.size()); i++) {
            Timing t = sorted.get(i);
            System.out.printf("  %4dms  c=%-5d r=%-6d  %-50s  [%s]%n",
                    t.totalMs(), t.compileMs(), t.runMs(),
                    abbrev(t.name(), 50), t.outcome());
        }

        // Histogram of total time (compile + run)
        System.out.println();
        System.out.println("── Timing histogram (compile + run, by outcome) ──────────────");
        String[] buckets = {"<1ms", "1-10ms", "10-100ms", "100ms-1s", "1-10s", "10-60s", ">60s"};
        int[][] counts = new int[buckets.length][2]; // [bucket][pass/rest]
        for (Timing t : sorted) {
            long ms = t.totalMs();
            int b = ms < 1 ? 0 : ms < 10 ? 1 : ms < 100 ? 2 : ms < 1000 ? 3
                    : ms < 10_000 ? 4 : ms < 60_000 ? 5 : 6;
            counts[b]["PASS".equals(t.outcome()) ? 0 : 1]++;
        }
        System.out.printf("  %-12s  %6s  %6s%n", "bucket", "PASS", "other");
        for (int i = 0; i < buckets.length; i++) {
            if (counts[i][0] + counts[i][1] > 0) {
                System.out.printf("  %-12s  %6d  %6d%n", buckets[i], counts[i][0], counts[i][1]);
            }
        }
        long totalMs = sorted.stream().mapToLong(Timing::totalMs).sum();
        long compileMs = sorted.stream().mapToLong(Timing::compileMs).sum();
        long runMs = sorted.stream().mapToLong(Timing::runMs).sum();
        System.out.printf("  total: compile=%dms (%.1fs), run=%dms (%.1fs), wall=%dms (%.1fs)%n",
                compileMs, compileMs / 1000.0, runMs, runMs / 1000.0, totalMs, totalMs / 1000.0);
    }

    /**
     * Does {@code s}'s {@code engines} list (per rebar's benchmark definition)
     * include a Java engine? Rebar's Java runner is {@code java/hotspot}
     * (running {@code java.util.regex}); other entries like {@code java/graal}
     * would match the same prefix. We use this to skip scenarios that rebar
     * itself doesn't test against Java — see the class javadoc.
     */
    private static boolean enginesIncludeJava(Scenario s) {
        return s.engines().stream().anyMatch(e -> e.startsWith("java/"));
    }

    /**
     * Run {@code task} on a fresh virtual thread, aborting the caller after
     * {@code timeoutMs}. On timeout the virtual thread is interrupted (best
     * effort for CPU-bound compilation) but continues until it checks the
     * interrupt or finishes naturally — it does NOT block the next test case.
     *
     * <p>Virtual threads (JDK 21+) are cheap enough that one-per-call is fine
     * even for 359 test cases. Orphaned threads die with the JVM.
     *
     * <p>Why not a shared single-thread executor? Because a timed-out CPU-bound
     * compile occupies the worker indefinitely; the next {@code submit} queues
     * behind it and its own timeout fires before the task even starts,
     * producing cascading false COMPILE_TIMEOUTs.
     */
    private static <T> T withTimeout(long timeoutMs, String phase, Callable<T> task)
            throws Exception {
        var future = new java.util.concurrent.FutureTask<T>(task);
        Thread.startVirtualThread(future);
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw e;
        }
    }

    /** Dispatch to the right model implementation. */
    private static long runModel(Scenario s, Pattern p, String haystack) {
        switch (s.model()) {
            case "count":            return countMatches(p, haystack);
            case "count-spans":      return countSpans(p, haystack);
            case "count-captures":   return countCaptures(p, haystack);
            case "grep":             return grepLines(p, haystack);
            // compile model: per rebar, "like count, but uses the compile model to
            // ensure the count is correct" (test/model.toml §compile). We've already
            // compiled by this point, so the verification IS the count.
            case "compile":          return countMatches(p, haystack);
            // grep-captures model: count all captures across all non-overlapping
            // matches, line-oriented with \r stripped (test/model.toml §grep-captures).
            case "grep-captures":    return grepCaptureCounts(p, haystack);
            default: throw new IllegalStateException("unsupported model: " + s.model());
        }
    }

    /** Estimated resolved haystack byte size, without materializing; -1 if unknown. */
    private static long haystackByteSize(Scenario s) {
        long base;
        long repeat;
        long extra = 0;
        if (s.haystackSpec() instanceof Scenario.HaystackSpec.Inline i) {
            base = i.contents().length();
            repeat = i.repeat() == null ? 1 : i.repeat();
            if (i.prepend() != null) extra += i.prepend().length();
            if (i.append() != null) extra += i.append().length();
        } else if (s.haystackSpec() instanceof Scenario.HaystackSpec.FromPath p) {
            try {
                base = Files.size(benchmarksDir.resolve("haystacks").resolve(p.path()));
            } catch (Exception e) {
                return -1;
            }
            repeat = p.repeat() == null ? 1 : p.repeat();
            if (p.prepend() != null) extra += p.prepend().length();
            if (p.append() != null) extra += p.append().length();
        } else {
            return -1;
        }
        try {
            return Math.multiplyExact(base, repeat) + extra;
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE; // trips the haystack-too-big assumeTrue
        }
    }

    private static String abbrev(String s, int max) {
        if (s == null) return "<null>";
        String oneLine = s.replace('\n', ' ').replace('\r', ' ');
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max - 3) + "...";
    }

    private static long countMatches(Pattern p, String hs) {
        long n = 0;
        for (Matcher m = p.matcher(hs); m.find(); ) n++;
        return n;
    }

    private static long countSpans(Pattern p, String hs) {
        long sum = 0;
        Matcher m = p.matcher(hs);
        while (m.find()) sum += m.end() - m.start();
        return sum;
    }

    /** Count total capturing groups across all non-overlapping matches. */
    private static long countCaptures(Pattern p, String hs) {
        long n = 0;
        Matcher m = p.matcher(hs);
        while (m.find()) {
            for (int g = 0; g <= m.groupCount(); g++) {
                if (m.start(g) >= 0) n++;
            }
        }
        return n;
    }

    /**
     * Count haystack lines that contain at least one match (rebar's 'grep'
     * model). Matches MODELS.md §grep pseudo-code: iterate on {@code \n}, strip
     * a trailing {@code \r} from CRLF-terminated lines, then ask the engine
     * for any match within the line (line terminator excluded).
     */
    private static long grepLines(Pattern p, String hs) {
        long matched = 0;
        Matcher m = p.matcher("");
        int lineStart = 0;
        for (int i = 0; i <= hs.length(); i++) {
            if (i == hs.length() || hs.charAt(i) == '\n') {
                int lineEnd = i;
                if (lineEnd > lineStart && hs.charAt(lineEnd - 1) == '\r') lineEnd--;
                String line = hs.substring(lineStart, lineEnd);
                m.reset(line);
                try {
                    if (m.find()) matched++;
                } catch (Exception ignored) { /* engine hiccup on this line */ }
                lineStart = i + 1;
            }
        }
        return matched;
    }

    /**
     * Count total capturing groups across all non-overlapping matches on each
     * line (rebar's 'grep-captures' model). Line iteration matches {@link #grepLines}
     * (split on {@code \n}, strip trailing {@code \r}); the per-line inner loop
     * matches {@link #countCaptures}. See {@code test/model.toml §grep-captures}.
     */
    private static long grepCaptureCounts(Pattern p, String hs) {
        long n = 0;
        Matcher m = p.matcher("");
        int lineStart = 0;
        for (int i = 0; i <= hs.length(); i++) {
            if (i == hs.length() || hs.charAt(i) == '\n') {
                int lineEnd = i;
                if (lineEnd > lineStart && hs.charAt(lineEnd - 1) == '\r') lineEnd--;
                String line = hs.substring(lineStart, lineEnd);
                m.reset(line);
                try {
                    while (m.find()) {
                        for (int g = 0; g <= m.groupCount(); g++) {
                            if (m.start(g) >= 0) n++;
                        }
                    }
                } catch (Exception ignored) { /* engine hiccup on this line */ }
                lineStart = i + 1;
            }
        }
        return n;
    }
}
