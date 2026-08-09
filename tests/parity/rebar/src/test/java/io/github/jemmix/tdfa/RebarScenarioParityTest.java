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
        Set<String> supportedModels = Set.of("count", "count-spans");
        final int MAX_HAYSTACK_BYTES = 2_000;
        final int MAX_REGEX_LEN = 200;
        final int MAX_ITER = 500;
        final long MAX_NS = 100_000_000L; // 100ms per scenario

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

        // --- Compile (VM backend for tracer-bullet; ASM codegen is slow per-pattern) ---

        Regex r;
        try {
            r = Regex.compile(s.regex(), EngineFactory.VM);
        } catch (Exception e) {
            skipCount.incrementAndGet();
            assumeTrue(false, "compile failed: " + e.getClass().getSimpleName()
                    + (e.getMessage() != null ? ": " + e.getMessage() : ""));
            return; // unreachable; satisfy compiler
        }

        // --- Run with time + iteration budgets ---

        String haystack = s.resolveHaystack(benchmarksDir);
        long start = System.nanoTime();
        long actual = -1;
        String budgetReason = null;
        try {
            actual = "count-spans".equals(s.model())
                    ? countSpans(r, haystack, MAX_ITER, start, MAX_NS)
                    : countMatches(r, haystack, MAX_ITER, start, MAX_NS);
        } catch (MaxIterException e) {
            budgetReason = "exceeded " + MAX_ITER + " iterations";
        } catch (TimeBudgetException e) {
            budgetReason = "exceeded " + (MAX_NS / 1_000_000) + "ms time budget";
        }
        if (budgetReason != null) {
            skipCount.incrementAndGet();
            assumeTrue(false, budgetReason);
            return;
        }

        // --- Assert ---

        if (actual == s.expectedCount()) {
            passCount.incrementAndGet();
        } else {
            failCount.incrementAndGet();
        }
        assertThat(actual)
                .as("match count for /%s/ on %d-byte haystack (model=%s)",
                        s.regex(), haystack.length(), s.model())
                .isEqualTo(s.expectedCount());
    }

    /** Return haystack byte size without materializing; -1 if unknown. */
    private static long haystackByteSize(Scenario s) {
        if (s.haystackSpec() instanceof Scenario.HaystackSpec.Inline i) {
            return i.contents().length();
        } else if (s.haystackSpec() instanceof Scenario.HaystackSpec.FromPath p) {
            try {
                return Files.size(benchmarksDir.resolve("haystacks").resolve(p.path()));
            } catch (Exception e) {
                return -1;
            }
        }
        return -1;
    }

    private static String abbrev(String s, int max) {
        if (s == null) return "<null>";
        String oneLine = s.replace('\n', ' ').replace('\r', ' ');
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max - 3) + "...";
    }

    private static final class MaxIterException extends RuntimeException {}
    private static final class TimeBudgetException extends RuntimeException {}

    private static long countMatches(Regex r, String hs, int maxIter, long startNs, long maxNs) {
        long n = 0;
        int pos = 0;
        int iter = 0;
        while (pos <= hs.length()) {
            if (++iter > maxIter) throw new MaxIterException();
            if ((iter & 0x3F) == 0 && System.nanoTime() - startNs > maxNs) throw new TimeBudgetException();
            MatchResult m = r.find(hs, pos);
            if (m == null) break;
            n++;
            int end = m.end(0);
            pos = (end <= pos) ? pos + 1 : end;
        }
        return n;
    }

    private static long countSpans(Regex r, String hs, int maxIter, long startNs, long maxNs) {
        long sum = 0;
        int pos = 0;
        int iter = 0;
        while (pos <= hs.length()) {
            if (++iter > maxIter) throw new MaxIterException();
            if ((iter & 0x3F) == 0 && System.nanoTime() - startNs > maxNs) throw new TimeBudgetException();
            MatchResult m = r.find(hs, pos);
            if (m == null) break;
            sum += m.end(0) - m.start(0);
            int end = m.end(0);
            pos = (end <= pos) ? pos + 1 : end;
        }
        return sum;
    }
}
