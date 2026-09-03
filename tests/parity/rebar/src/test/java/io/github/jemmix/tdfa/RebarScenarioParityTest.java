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

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
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
 * <p><b>Scope:</b> only scenarios rebar <em>actually tests against Java</em>
 * run at all — {@code engines = [...]} must contain a {@code java/.*} entry
 * (rebar excludes Java from 245 of the 359 scenarios: multi-pattern regex-set
 * APIs, hyperscan-only overlap reporting, aho-corasick, dictionary lookups,
 * etc. — out of scope for a Java regex library; see
 * {@code docs/PARITY-PLAN.md}). The filter is applied at parameter-build
 * time in {@link #scenariosProvider()}, so out-of-scope scenarios do not
 * appear as test cases at all. Visible (gray) skips remain only for the
 * named over-budget bombs (see {@link #BOMB_SCENARIOS}) and known per-scenario
 * gaps (model, no-count, parser limits).
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
 * <p>Skipped (visible, gray):
 * <ul>
 *   <li>named over-budget bombs (see {@link #BOMB_SCENARIOS}; gated by
 *       {@code -Dtdfa.test.rebar.skipBombs}, default true)</li>
 *   <li>model not in {count, count-spans, count-captures, grep, compile, grep-captures}
 *       (regex-redux is the only remaining unsupported model)</li>
 *   <li>expected count has no entry matching our {@code "re2"} identity</li>
 *   <li>haystack resolve failure</li>
 *   <li>parser rejects the pattern (Unicode property long-names, backrefs, lookaround)</li>
 * </ul>
 *
 * <p>A determinization-budget rejection ("pattern too large") on any scenario
 * NOT in {@link #BOMB_SCENARIOS} is a FAILURE, not a skip — an engine
 * limitation this suite must surface rather than silently absorb. There are
 * no numeric time/size gates: the engine's own budget is the only watchdog.
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
     * between the two engines on the same regex shows up directly in the test
     * report. The test name includes {@code [ASM]} or {@code [VM]} so
     * IDE / CI output identifies the engine at a glance.
     *
     * <p>Scope filtering happens HERE (build time), not as runtime skips:
     * scenarios whose {@code engines} list has no {@code java/.*} entry are
     * out of scope for a Java regex library and don't appear as test cases.
     */
    static Stream<Arguments> scenariosProvider() {
        return scenarios.stream()
                .filter(RebarScenarioParityTest::enginesIncludeJava)
                .flatMap(s -> Stream.of((RegexEngineFactory) null, (RegexEngineFactory) TdfaRunner::new).map(f -> Arguments.of(
                        /*displayName=*/ s.fullName() + "  corpus-want=" + s.expectedCount()
                                + (s.unicode() ? " (unicode: corpus stands)" : " (non-unicode: live re2j)")
                                + "  /" + abbrev(s.regex(), 60) + "/  [" + labelFor(f) + "]",
                        /*scenario=*/ s,
                        /*factory=*/ f)));
    }

    static String labelFor(RegexEngineFactory f) {
        return f == null ? "ASM" : "VM";
    }

    /**
     * In-scope scenarios whose minimal DFA exceeds the engine's determinization
     * budget <em>by design</em> — the suite skips them (visibly) unless
     * explicitly opted in. Rationale (2026-08-20): running these adds ~40 s of
     * rejected determinization per backend plus a multi-GB raised-budget retry
     * to every suite run, to verify one shape family that is already covered
     * by dedicated probes and documented candor notes. Opt in with
     * {@code -Dtdfa.test.rebar.skipBombs=false} — the test then does a PLAIN
     * compile at whatever caps the JVM provides: raise them explicitly (e.g.
     * {@code -Dtdfa.max.states=250000} and ≥1 GB heap) or expect the engine's
     * own clean "pattern too large" rejection.
     *
     * <ul>
     *   <li>{@code curated/10-bounded-repeat/context} — two-site
     *       {@code [\s\S]{0,100}} counter cross-product: 234 369-state minimal
     *       DFA (kernel total ~44 M, under the default 50 M — the STATE cap is
     *       the only binding one). Measured at the raised ceiling after the
     *       2026-08-20 memory work: ~21 s compile, fits -Xmx1g, ~82 MB
     *       retained, count=53 verified on both backends (TODO.md "budget").
     * </ul>
     */
    static final Set<String> BOMB_SCENARIOS = Set.of("curated/10-bounded-repeat/context");

    /** Default true; set {@code -Dtdfa.test.rebar.skipBombs=false} to run the bombs for real. */
    static final boolean SKIP_BOMBS =
            Boolean.parseBoolean(System.getProperty("tdfa.test.rebar.skipBombs", "true"));

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("scenariosProvider")
    void runScenarioThroughTdfa(String displayName, Scenario s, RegexEngineFactory factory) throws Exception {
        // Models we run; regex-redux is the only intentionally-skipped model
        // (bespoke embedded-regex harness, ~1–2 scenarios — see PARITY-PLAN §4.3).
        Set<String> supportedModels = Set.of("count", "count-spans", "count-captures",
                "grep", "compile", "grep-captures");
        //
        // No numeric time/size gates (2026-08-20): the engine's own
        // determinization budget (re2c-identical caps — 100 K states /
        // 50 M kernel-total) is the only watchdog. A compile rejected with
        // "pattern too large" on any scenario NOT in BOMB_SCENARIOS is a
        // FAILURE (surfaced, not skipped); the named bombs skip visibly and
        // are opt-in via -Dtdfa.test.rebar.skipBombs=false. Compile-latency
        // regressions are pinned separately by CompileLatencyGuardTest.

        // --- Named over-budget bombs: skip visibly unless opted in ---
        if (SKIP_BOMBS && BOMB_SCENARIOS.contains(s.fullName())) {
            countSkip("bomb:over-budget-by-design");
            skipCount.incrementAndGet();
            timings.add(new Timing(s.fullName(), 0, 0, "SKIP:bomb"));
            assumeTrue(false, "over-budget bomb (skipped by default; see BOMB_SCENARIOS javadoc). "
                    + "Run with -Dtdfa.test.rebar.skipBombs=false -Dtdfa.max.states=250000 "
                    + "(heap >= 1g) to verify it for real.");
            return;
        }

        // --- Filter: skip cleanly via assumeTrue so IDE shows gray "skipped" ---

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
        if (s.regex() == null) {
            countSkip("regex-null");
            skipCount.incrementAndGet();
            timings.add(new Timing(s.fullName(), 0, 0, "SKIP:regex-null"));
            assumeTrue(false, "no regex (unrepresentable input spec)");
            return;
        }

        // --- Compile via the re2j-compat API (Pattern/Matcher). Flags are
        //     translated to inline prefixes by Pattern.compile — (?i) for
        //     caseInsensitive, (?u) for unicode (UNICODE_CHARACTER_CLASS).
        //     PERL disambiguation is the default (matches re2/re2j semantics).
        //     The ASM backend handles every in-scope pattern (DispatchMode.
        //     DELEGATE for arbitrary DFA sizes — see TdfaAsmBackend.pickMode),
        //     so each parameter value runs its own backend independently and a
        //     divergence shows up as a real test failure.

        int flags = 0;
        if (s.caseInsensitive()) flags |= Pattern.CASE_INSENSITIVE;
        if (s.unicode()) flags |= Pattern.UNICODE_CHARACTER_CLASS;
        long compileStart = System.nanoTime();
        Pattern compiled;
        try {
            compiled = Pattern.compile(s.regex(), flags, factory);
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : "";
            // Budget rejection on a non-listed scenario is a FAILURE — the
            // engine must handle every in-scope shape within the default caps
            // (only BOMB_SCENARIOS are known over-budget, and those skip above).
            if (msg.contains("pattern too large")) {
                failCount.incrementAndGet();
                timings.add(new Timing(s.fullName(),
                        (System.nanoTime() - compileStart) / 1_000_000, 0,
                        "FAIL:budget-exceeded"));
                throw e;
            }
            countSkip("compile-failed:" + labelFor(factory) + ":" + e.getClass().getSimpleName());
            skipCount.incrementAndGet();
            timings.add(new Timing(s.fullName(),
                    (System.nanoTime() - compileStart) / 1_000_000, 0,
                    "SKIP:compile-failed:" + labelFor(factory) + ":" + e.getClass().getSimpleName()));
            assumeTrue(false, "compile failed (" + labelFor(factory) + "): " + e.getClass().getSimpleName()
                    + (msg.isEmpty() ? "" : ": " + msg));
            return;
        }
        final Pattern p = compiled;
        long compileMs = (System.nanoTime() - compileStart) / 1_000_000;

        // --- Resolve haystack (I/O only) ---

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

        // --- Run (no wall-clock gate; a hang shows up in the suite timeout) ---

        final long runStart = System.nanoTime();
        final long actual = runModel(s, p, haystack);
        long runMs = (System.nanoTime() - runStart) / 1_000_000;

        if (compileMs > 50 || runMs > 50) {
            System.out.printf("SLOW     %-50s [%s] compile=%dms  run=%dms  /%s/%n",
                    s.fullName(), labelFor(factory), compileMs, runMs, abbrev(s.regex(), 50));
        }

        // --- Resolve expected count: live patched-re2j oracle by default ---
        // The corpus's static per-engine counts were recorded by other
        // engines at other times (JDK Unicode-DB drift, java/hotspot's
        // ASCII-only (?i), UTF-8-vs-UTF-16 units) — every re2j-compat
        // divergence needed a hand-patched count. We are a re2j drop-in:
        // for scenarios whose flags re2j can represent (unicode=false —
        // re2j has no UNICODE_CHARACTER_CLASS; \w\d\s are ASCII there),
        // the vendored patched re2j (fix1/fix2) computes `want` LIVE with
        // the same model loops. Falls back to the corpus when re2j rejects
        // the regex (backrefs/lookaround: j.u.r runs them, re2j doesn't) or
        // on any oracle-side exception. -Dtdfa.test.rebar.oracle=corpus
        // restores the pure static resolution.
        long want;
        String wantSource;
        if (!CORPUS_ORACLE && !s.unicode()) {
            Long live = liveRe2jCount(s, haystack);
            if (live != null) {
                want = live;
                wantSource = "re2j-live";
            } else {
                want = s.expectedCount();
                wantSource = "corpus(re2j-unrunnable)";
            }
        } else {
            want = s.expectedCount();
            wantSource = s.unicode() ? "corpus(unicode=java-semantics)" : "corpus";
        }

        // --- Assert ---

        boolean passed = actual == want;
        if (passed) {
            passCount.incrementAndGet();
        } else {
            failCount.incrementAndGet();
        }
        timings.add(new Timing(s.fullName(), compileMs, runMs,
                passed ? "PASS" : "FAIL:want=" + want + ",got=" + actual));
        assertThat(actual)
                .as("match count for /%s/ on %d-byte haystack (model=%s, want=%s:%d); compile=%dms run=%dms; hs contains regex? %s; first 40 chars: %s",
                        s.regex(), haystack.length(), s.model(), wantSource, want, compileMs, runMs,
                        haystack.contains(s.regex().length() <= 100 ? s.regex() : s.regex().substring(0, 50)),
                        haystack.substring(0, Math.min(40, haystack.length())).replace("\n", "\\n").replace("\r", "\\r"))
                .isEqualTo(want);
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

    /** Live-oracle mode: patched re2j computes `want`. Null = fall back to
     *  corpus (re2j can't compile the regex, or hiccupped). */
    private static final boolean CORPUS_ORACLE =
            "corpus".equals(System.getProperty("tdfa.test.rebar.oracle"));

    private static Long liveRe2jCount(Scenario s, String haystack) {
        try {
            int rflags = 0;
            if (s.caseInsensitive()) rflags |= com.google.re2j.Pattern.CASE_INSENSITIVE;
            com.google.re2j.Pattern p = com.google.re2j.Pattern.compile(s.regex(), rflags);
            switch (s.model()) {
                case "count":
                case "compile":
                    return re2jCount(p, haystack);
                case "count-spans":
                    return re2jSpans(p, haystack);
                case "count-captures":
                    return re2jCaptures(p, haystack);
                case "grep":
                    return re2jGrep(p, haystack);
                case "grep-captures":
                    return re2jGrepCaptures(p, haystack);
                default:
                    return null;   // unsupported model: corpus value stands
            }
        } catch (Throwable t) {
            return null;
        }
    }

    private static long re2jCount(com.google.re2j.Pattern p, String hs) {
        long n = 0;
        for (com.google.re2j.Matcher m = p.matcher(hs); m.find(); ) n++;
        return n;
    }

    private static long re2jSpans(com.google.re2j.Pattern p, String hs) {
        long sum = 0;
        com.google.re2j.Matcher m = p.matcher(hs);
        while (m.find()) sum += m.end() - m.start();
        return sum;
    }

    private static long re2jCaptures(com.google.re2j.Pattern p, String hs) {
        long n = 0;
        com.google.re2j.Matcher m = p.matcher(hs);
        while (m.find()) {
            for (int g = 0; g <= m.groupCount(); g++) {
                if (m.start(g) >= 0) n++;
            }
        }
        return n;
    }

    private static long re2jGrep(com.google.re2j.Pattern p, String hs) {
        long matched = 0;
        com.google.re2j.Matcher m = p.matcher("");
        int lineStart = 0;
        for (int i = 0; i <= hs.length(); i++) {
            if (i == hs.length() || hs.charAt(i) == '\n') {
                int lineEnd = i;
                if (lineEnd > lineStart && hs.charAt(lineEnd - 1) == '\r') lineEnd--;
                String line = hs.substring(lineStart, lineEnd);
                m.reset(line);
                try {
                    if (m.find()) matched++;
                } catch (Exception ignored) { }
                lineStart = i + 1;
            }
        }
        return matched;
    }

    private static long re2jGrepCaptures(com.google.re2j.Pattern p, String hs) {
        long total = 0;
        com.google.re2j.Matcher m = p.matcher("");
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
                            if (m.start(g) >= 0) total++;
                        }
                    }
                } catch (Exception ignored) { }
                lineStart = i + 1;
            }
        }
        return total;
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
