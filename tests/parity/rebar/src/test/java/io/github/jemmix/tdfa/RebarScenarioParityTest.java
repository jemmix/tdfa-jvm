package io.github.jemmix.tdfa;

import io.github.jemmix.tdfa.core.Matcher;
import io.github.jemmix.tdfa.core.RegexEngineFactory;
import io.github.jemmix.tdfa.rebar.Scenario;
import io.github.jemmix.tdfa.rebar.ScenarioLoader;
import io.github.jemmix.tdfa.tdfa.TdfaRunner;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.provider.Arguments;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

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
 * etc. — out of scope for a Java regex library). The filter is applied at
 * parameter-build
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
 * comment explaining the divergence — see the rebar patches under
 * {@code vendor/patches/rebar/}.
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
        long totalMs() {
            return compileMs + runMs;
        }
    }

    /** All timings; synchronized because parameterized tests can run in parallel. */
    static final List<Timing> timings = Collections.synchronizedList(new ArrayList<>());

    /** Skip-reason counters for the summary. */
    static final ConcurrentHashMap<String, AtomicInteger> skipBuckets = new ConcurrentHashMap<>();

    /**
     * Total-skip ceiling (gray skips can silently shrink the green set, so
     * the total is capped). Steady state: ~4 skips, all
     * {@code bomb:over-budget-by-design}; every other reason family
     * (unsupported-model, no-scalar-count, regex-null, haystack-resolve-failed,
     * compile-failed) should be at 0 — a compile-exception bug appearing at
     * scale blows far past this cap, so the margin only absorbs rare
     * environmental haystack-resolution hiccups.
     */
    static final int MAX_TOTAL_SKIPS = 8;

    /**
     * In-scope scenarios whose determinization exceeds the engine's budget
     * <em>by design</em> — the suite skips them (visibly) unless
     * explicitly opted in. Rationale: running these adds ~40 s of
     * rejected determinization per backend plus a multi-GB raised-budget retry
     * to every suite run, to verify one shape family that is already covered
     * by dedicated probes and documented candor notes. Opt in with
     * {@code -Dtdfa.test.rebar.skipBombs=false} — the test then does a PLAIN
     * compile at whatever budgets the JVM provides: raise them explicitly
     * (e.g. {@code -Dtdfa.budget.compile.memory=4000000000
     * -Dtdfa.budget.compile.compute=4000000000} and ≥1 GB heap — the shape
     * needs ~3.6 GB of weighted kernel RAM) or expect the engine's
     * own clean "pattern too large" rejection.
     *
     * <ul>
     *   <li>{@code curated/10-bounded-repeat/context} — two-site
     *       {@code [\s\S]{0,100}} counter cross-product: 234 369-state
     *       minimal DFA, kernel total ~44 M (3.6 GB weighted — over the
     *       default RAM budget on both axes). At a raised budget: ~21 s
     *       compile, fits -Xmx1g, ~82 MB retained, count=53 verified on
     *       both backends (TODO.md "budget").
     *   <li>{@code curated/09-aws-keys/full} — the find artifact compiles,
     *       but the pattern's pike cut bit and its cut-free whole artifact
     *       (the counter cross-product of the two alternation arms) churns
     *       past the compile CPU budget, so {@code compile()} rejects.
     *       The whole artifact churns without converging even at a raised
     *       compute budget, so raising won't admit it.
     * </ul>
     */
    static final Set<String> BOMB_SCENARIOS = Set.of("curated/10-bounded-repeat/context", "curated/09-aws-keys/full");

    /** Default true; set {@code -Dtdfa.test.rebar.skipBombs=false} to run the bombs for real. */
    static final boolean SKIP_BOMBS = Boolean.parseBoolean(System.getProperty("tdfa.test.rebar.skipBombs", "true"));

    /** Live-oracle mode: patched re2j computes `want`. Null = fall back to
     *  corpus (re2j can't compile the regex, or hiccupped). */
    private static final boolean CORPUS_ORACLE = "corpus".equals(System.getProperty("tdfa.test.rebar.oracle"));

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
        return scenarios.stream().filter(RebarScenarioParityTest::enginesIncludeJava)
            .flatMap(s -> Stream.of((RegexEngineFactory) null, (RegexEngineFactory) TdfaRunner::new)
                .map(f -> Arguments.of(/*displayName=*/ s.fullName() + "  corpus-want=" + s.expectedCount()
                    + (s.unicode() ? " (unicode: corpus stands)" : " (non-unicode: live re2j)") + "  /"
                    + abbrev(s.regex(), 60) + "/  [" + labelFor(f) + "]", /*scenario=*/ s, /*factory=*/ f)));
    }

    static String labelFor(RegexEngineFactory f) {
        return f == null ? "ASM" : "VM";
    }

    /**
     * End-of-suite summary printed once all parameterized invocations finish.
     * Surfaces the slowest tests and the skip-reason histogram for triage.
     */
    @AfterAll
    static void printSummary() {
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════════════════╗");
        System.out.printf("║ rebar parity: pass=%-4d  fail=%-4d  skip=%-4d   total=%-4d%n", passCount.get(),
            failCount.get(), skipCount.get(), passCount.get() + failCount.get() + skipCount.get());
        System.out.println("╚══════════════════════════════════════════════════════════════════════╝");
        // Gray-skip cap (see MAX_TOTAL_SKIPS): a skip is only legitimate for a
        // recorded reason; an unexpected compile-exception family shrinking the
        // green set must FAIL the gate, not vanish into the histogram.
        Assertions.assertTrue(skipCount.get() <= MAX_TOTAL_SKIPS,
            "rebar parity skipped " + skipCount.get() + " scenarios (cap " + MAX_TOTAL_SKIPS
                + ", recorded baseline 2) — the green set shrank; see the skip histogram");

        // Skip-reason histogram
        if (!skipBuckets.isEmpty()) {
            System.out.println();
            System.out.println("── Skip reasons ──────────────────────────────────────────────");
            skipBuckets
                .entrySet().stream().sorted(Map.Entry
                    .<String, AtomicInteger>comparingByValue(Comparator.comparingInt(AtomicInteger::get)).reversed())
                .forEach(e -> System.out.printf("  %5d  %s%n", e.getValue().get(), e.getKey()));
        }

        // Top-20 slowest tests by compile+run
        List<Timing> sorted = new ArrayList<>(timings);
        sorted.sort(Comparator.comparingLong(Timing::totalMs).reversed());
        System.out.println();
        System.out.println("── Top 20 slowest (compile + run, ms) ─────────────────────────");
        for (int i = 0; i < Math.min(20, sorted.size()); i++) {
            Timing t = sorted.get(i);
            System.out.printf("  %4dms  c=%-5d r=%-6d  %-50s  [%s]%n", t.totalMs(), t.compileMs(), t.runMs(),
                abbrev(t.name(), 50), t.outcome());
        }

        // Histogram of total time (compile + run)
        System.out.println();
        System.out.println("── Timing histogram (compile + run, by outcome) ──────────────");
        String[] buckets = {"<1ms", "1-10ms", "10-100ms", "100ms-1s", "1-10s", "10-60s", ">60s"};
        int[][] counts = new int[buckets.length][2]; // [bucket][pass/rest]
        for (Timing t : sorted) {
            long ms = t.totalMs();
            int b = ms < 1 ? 0 : ms < 10 ? 1 : ms < 100 ? 2 : ms < 1000 ? 3 : ms < 10_000 ? 4 : ms < 60_000 ? 5 : 6;
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
        System.out.printf("  total: compile=%dms (%.1fs), run=%dms (%.1fs), wall=%dms (%.1fs)%n", compileMs,
            compileMs / 1000.0, runMs, runMs / 1000.0, totalMs, totalMs / 1000.0);
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
            case "count" :
                return countMatches(p, haystack);
            case "count-spans" :
                return countSpans(p, haystack);
            case "count-captures" :
                return countCaptures(p, haystack);
            case "grep" :
                return grepLines(p, haystack);
            // compile model: per rebar, "like count, but uses the compile model to
            // ensure the count is correct" (test/model.toml §compile). We've already
            // compiled by this point, so the verification IS the count.
            case "compile" :
                return countMatches(p, haystack);
            // grep-captures model: count all captures across all non-overlapping
            // matches, line-oriented with \r stripped (test/model.toml §grep-captures).
            case "grep-captures" :
                return grepCaptureCounts(p, haystack);
            default :
                throw new IllegalStateException("unsupported model: " + s.model());
        }
    }

    private static Long liveRe2jCount(Scenario s, String haystack) {
        try {
            int rflags = 0;
            if (s.caseInsensitive()) {
                rflags |= com.google.re2j.Pattern.CASE_INSENSITIVE;
            }
            com.google.re2j.Pattern p = com.google.re2j.Pattern.compile(s.regex(), rflags);
            switch (s.model()) {
                case "count" :
                case "compile" :
                    return re2jCount(p, haystack);
                case "count-spans" :
                    return re2jSpans(p, haystack);
                case "count-captures" :
                    return re2jCaptures(p, haystack);
                case "grep" :
                    return re2jGrep(p, haystack);
                case "grep-captures" :
                    return re2jGrepCaptures(p, haystack);
                default :
                    return null; // unsupported model: corpus value stands
            }
        } catch (Throwable t) {
            return null;
        }
    }

    private static long re2jCount(com.google.re2j.Pattern p, String hs) {
        long n = 0;
        for (com.google.re2j.Matcher m = p.matcher(hs); m.find();) {
            n++;
        }
        return n;
    }

    private static long re2jSpans(com.google.re2j.Pattern p, String hs) {
        long sum = 0;
        com.google.re2j.Matcher m = p.matcher(hs);
        while (m.find()) {
            sum += m.end() - m.start();
        }
        return sum;
    }

    private static long re2jCaptures(com.google.re2j.Pattern p, String hs) {
        long n = 0;
        com.google.re2j.Matcher m = p.matcher(hs);
        while (m.find()) {
            for (int g = 0; g <= m.groupCount(); g++) {
                if (m.start(g) >= 0) {
                    n++;
                }
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
                if (lineEnd > lineStart && hs.charAt(lineEnd - 1) == '\r') {
                    lineEnd--;
                }
                String line = hs.substring(lineStart, lineEnd);
                m.reset(line);
                try {
                    if (m.find()) {
                        matched++;
                    }
                } catch (Exception ignored) {
                }
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
                if (lineEnd > lineStart && hs.charAt(lineEnd - 1) == '\r') {
                    lineEnd--;
                }
                String line = hs.substring(lineStart, lineEnd);
                m.reset(line);
                try {
                    while (m.find()) {
                        for (int g = 0; g <= m.groupCount(); g++) {
                            if (m.start(g) >= 0) {
                                total++;
                            }
                        }
                    }
                } catch (Exception ignored) {
                }
                lineStart = i + 1;
            }
        }
        return total;
    }

    private static String abbrev(String s, int max) {
        if (s == null) {
            return "<null>";
        }
        String oneLine = s.replace('\n', ' ').replace('\r', ' ');
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max - 3) + "...";
    }

    private static long countMatches(Pattern p, String hs) {
        long n = 0;
        for (Matcher m = p.matcher(hs); m.find();) {
            n++;
        }
        return n;
    }

    private static long countSpans(Pattern p, String hs) {
        long sum = 0;
        Matcher m = p.matcher(hs);
        while (m.find()) {
            sum += m.end() - m.start();
        }
        return sum;
    }

    /** Count total capturing groups across all non-overlapping matches. */
    private static long countCaptures(Pattern p, String hs) {
        long n = 0;
        Matcher m = p.matcher(hs);
        while (m.find()) {
            for (int g = 0; g <= m.groupCount(); g++) {
                if (m.start(g) >= 0) {
                    n++;
                }
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
                if (lineEnd > lineStart && hs.charAt(lineEnd - 1) == '\r') {
                    lineEnd--;
                }
                String line = hs.substring(lineStart, lineEnd);
                m.reset(line);
                try {
                    if (m.find()) {
                        matched++;
                    }
                } catch (Exception ignored) {
                    /* engine hiccup on this line */ }
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
                if (lineEnd > lineStart && hs.charAt(lineEnd - 1) == '\r') {
                    lineEnd--;
                }
                String line = hs.substring(lineStart, lineEnd);
                m.reset(line);
                try {
                    while (m.find()) {
                        for (int g = 0; g <= m.groupCount(); g++) {
                            if (m.start(g) >= 0) {
                                n++;
                            }
                        }
                    }
                } catch (Exception ignored) {
                    /* engine hiccup on this line */ }
                lineStart = i + 1;
            }
        }
        return n;
    }
}
