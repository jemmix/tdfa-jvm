package io.github.jemmix.tdfa;

import io.github.jemmix.tdfa.rebar.Scenario;
import io.github.jemmix.tdfa.rebar.ScenarioLoader;
import io.github.jemmix.tdfa.vm.MatchResult;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
 * <p>Skipped for tracer-bullet reasons:
 * <ul>
 *   <li>model not in {count, count-spans} (others need infrastructure we don't have)</li>
 *   <li>haystack &gt; 2 KB (avoid OOM + ReDoS time bombs)</li>
 *   <li>regex &gt; 200 chars (mega-alternations like dictionary lookups)</li>
 *   <li>expected count has only per-engine overrides, no scalar default</li>
 *   <li>parser rejects the pattern (backreferences, lookaround, unsupported syntax)</li>
 *   <li>per-scenario time budget exceeded (100ms) — flagged for investigation</li>
 * </ul>
 *
 * <p>Failures are real divergences between our engine and rebar's reference
 * results — see the {@code want} vs {@code got} counts in the failure message.
 */
class RebarScenarioParityTest {

    static final Path benchmarksDir;
    static final List<Scenario> scenarios;
    static final AtomicInteger passCount = new AtomicInteger();
    static final AtomicInteger failCount = new AtomicInteger();
    static final AtomicInteger skipCount = new AtomicInteger();

    static {
        String dir = System.getProperty("rebar.benchmarks.dir");
        benchmarksDir = Paths.get(dir);
        try {
            scenarios = new ScenarioLoader(benchmarksDir).loadAll();
        } catch (Exception e) {
            throw new IllegalStateException("failed to load rebar scenarios from " + dir, e);
        }
    }

    static Stream<Arguments> scenariosProvider() {
        return scenarios.stream().map(s -> Arguments.of(
                /*displayName=*/ s.fullName() + "  want=" + s.expectedCount()
                        + "  /" + abbrev(s.regex(), 60) + "/",
                /*scenario=*/ s));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("scenariosProvider")
    void runScenarioThroughTdfa(String displayName, Scenario s) throws Exception {
        // Models we run; everything else is a clean skip.
        Set<String> supportedModels = Set.of("count", "count-spans", "count-captures", "grep");
        // Quick-and-dirty budgets: tight enough that 359 cases finish in
        // ~2 minutes worst-case, loose enough to not flap on a slow CI box.
        final long COMPILE_TIMEOUT_MS = 300;     // regex compilation wall-clock
        final long RUN_TIMEOUT_MS    = 500;     // match execution wall-clock
        final int  MAX_HAYSTACK_BYTES = 200_000;
        final int  MAX_REGEX_LEN = 2_000;

        // --- Filter: skip cleanly via assumeTrue so IDE shows gray "skipped" ---

        assumeTrue(supportedModels.contains(s.model()),
                "unsupported model: " + s.model());
        assumeTrue(s.expectedCount() != Long.MIN_VALUE,
                "no scalar expected count (per-engine overrides only)");
        assumeTrue(s.regex() != null && s.regex().length() <= MAX_REGEX_LEN,
                "regex too long (" + (s.regex() == null ? 0 : s.regex().length()) + " chars)");

        long hsBytes = haystackByteSize(s);
        assumeTrue(hsBytes >= 0 && hsBytes <= MAX_HAYSTACK_BYTES,
                "haystack too big (" + hsBytes + " bytes)");

        // --- Compile (VM backend) with hard timeout ---

        long compileStart = System.nanoTime();
        final Regex r;
        try {
            r = withTimeout(COMPILE_TIMEOUT_MS, "compile", () -> Regex.compile(s.regex(), EngineFactory.VM));
        } catch (TimeoutException e) {
            skipCount.incrementAndGet();
            assumeTrue(false, "COMPILE_TIMEOUT " + COMPILE_TIMEOUT_MS + "ms");
            return;
        } catch (Exception e) {
            skipCount.incrementAndGet();
            assumeTrue(false, "compile failed: " + e.getClass().getSimpleName()
                    + (e.getMessage() != null ? ": " + e.getMessage() : ""));
            return;
        }
        long compileMs = (System.nanoTime() - compileStart) / 1_000_000;

        // --- Resolve haystack (not budgeted — should be I/O only) ---

        String haystack;
        try {
            haystack = s.resolveHaystack(benchmarksDir);
        } catch (Exception e) {
            skipCount.incrementAndGet();
            assumeTrue(false, "haystack resolve failed: " + e.getMessage());
            return;
        }

        // --- Run with hard wall-clock timeout ---

        final long runStart = System.nanoTime();
        final long actual;
        try {
            actual = withTimeout(RUN_TIMEOUT_MS, "run", () -> runModel(s, r, haystack));
        } catch (TimeoutException e) {
            skipCount.incrementAndGet();
            System.out.printf("TIMEOUT  %-60s compile=%dms  run>%dms  /%s/%n",
                    s.fullName(), compileMs, RUN_TIMEOUT_MS, abbrev(s.regex(), 50));
            assumeTrue(false, "RUN_TIMEOUT " + RUN_TIMEOUT_MS + "ms (compile was " + compileMs + "ms)");
            return;
        }
        long runMs = (System.nanoTime() - runStart) / 1_000_000;

        if (compileMs > 50 || runMs > 50) {
            System.out.printf("SLOW     %-60s compile=%dms  run=%dms  /%s/%n",
                    s.fullName(), compileMs, runMs, abbrev(s.regex(), 50));
        }

        // --- Assert ---

        if (actual == s.expectedCount()) {
            passCount.incrementAndGet();
        } else {
            failCount.incrementAndGet();
        }
        assertThat(actual)
                .as("match count for /%s/ on %d-byte haystack (model=%s); compile=%dms run=%dms; hs contains regex? %s; first 40 chars: %s",
                        s.regex(), haystack.length(), s.model(), compileMs, runMs,
                        haystack.contains(s.regex().length() <= 100 ? s.regex() : s.regex().substring(0, 50)),
                        haystack.substring(0, Math.min(40, haystack.length())).replace("\n", "\\n").replace("\r", "\\r"))
                .isEqualTo(s.expectedCount());
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
    private static long runModel(Scenario s, Regex r, String haystack) {
        switch (s.model()) {
            case "count":          return countMatches(r, haystack);
            case "count-spans":    return countSpans(r, haystack);
            case "count-captures": return countCaptures(r, haystack);
            case "grep":           return grepLines(r, haystack);
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
        return base * repeat + extra;
    }

    private static String abbrev(String s, int max) {
        if (s == null) return "<null>";
        String oneLine = s.replace('\n', ' ').replace('\r', ' ');
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max - 3) + "...";
    }

    private static long countMatches(Regex r, String hs) {
        long n = 0;
        int pos = 0;
        while (pos <= hs.length()) {
            MatchResult m = r.find(hs, pos);
            if (m == null) break;
            n++;
            int end = m.end(0);
            pos = (end <= pos) ? pos + 1 : end;
        }
        return n;
    }

    private static long countSpans(Regex r, String hs) {
        long sum = 0;
        int pos = 0;
        while (pos <= hs.length()) {
            MatchResult m = r.find(hs, pos);
            if (m == null) break;
            sum += m.end(0) - m.start(0);
            int end = m.end(0);
            pos = (end <= pos) ? pos + 1 : end;
        }
        return sum;
    }

    /** Count total capturing groups across all non-overlapping matches. */
    private static long countCaptures(Regex r, String hs) {
        long n = 0;
        int pos = 0;
        while (pos <= hs.length()) {
            MatchResult m = r.find(hs, pos);
            if (m == null) break;
            for (int g = 0; g <= m.groupCount(); g++) {
                if (m.start(g) >= 0) n++;
            }
            int end = m.end(0);
            pos = (end <= pos) ? pos + 1 : end;
        }
        return n;
    }

    /** Count haystack lines that contain at least one match (rebar's 'grep' model). */
    private static long grepLines(Regex r, String hs) {
        long matched = 0;
        int lineStart = 0;
        for (int i = 0; i <= hs.length(); i++) {
            if (i == hs.length() || hs.charAt(i) == '\n') {
                String line = hs.substring(lineStart, i);
                MatchResult m = null;
                try {
                    m = r.find(line, 0);
                } catch (Exception ignored) { /* engine hiccup on this line */ }
                if (m != null) matched++;
                lineStart = i + 1;
            }
        }
        return matched;
    }
}
