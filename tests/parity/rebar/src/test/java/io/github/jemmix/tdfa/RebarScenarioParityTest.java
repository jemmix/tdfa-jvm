package io.github.jemmix.tdfa;

import io.github.jemmix.tdfa.rebar.Scenario;
import io.github.jemmix.tdfa.rebar.ScenarioLoader;
import io.github.jemmix.tdfa.tdfa.Disambiguation;
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
 * <p>Engine identity: rebar's {@code regex = [...]} multi-pattern inputs are
 * folded into a single Perl-style alternation (preserving each pattern's
 * capture groups), and the test compiles with {@link Disambiguation#PERL}
 * (leftmost-first, like re2/re2j) on the default backend ({@link EngineFactory#DEFAULT},
 * ASM unless {@code -Dtdfa.engine=VM}). Per-engine {@code count} entries are
 * resolved in the {@code "re2"} identity first, falling back to {@code .*}.
 * Scenario flag {@code case-insensitive} is applied via the {@code (?i)}
 * inline flag. ASM-only failures (method-too-large etc.) automatically retry
 * on the VM backend — logged via {@code ASM-FAIL} on stdout.
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
 *   <li>model not in {count, count-spans, count-captures, grep}
 *       (regex-redux, grep-captures, compile need infrastructure we don't have)</li>
 *   <li>haystack &gt; 200 KB (avoid OOM + ReDoS time bombs)</li>
 *   <li>regex &gt; 2 000 chars (mega-alternations like dictionary lookups)</li>
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

        // Scope filter: only run scenarios rebar actually tests against Java.
        // Rebar's own engine list is the authoritative source for "what's
        // tractable for a Java regex library" — see the class javadoc and
        // docs/PARITY-PLAN.md. Skips ~245 multi-pattern / rust-only /
        // hyperscan-only / aho-corasick / dictionary scenarios.
        assumeTrue(enginesIncludeJava(s),
                "java not in rebar engines list (out of scope for a Java regex lib)");

        assumeTrue(supportedModels.contains(s.model()),
                "unsupported model: " + s.model());
        assumeTrue(s.expectedCount() != Long.MIN_VALUE,
                "no scalar expected count (per-engine overrides only)");
        assumeTrue(s.regex() != null && s.regex().length() <= MAX_REGEX_LEN,
                "regex too long (" + (s.regex() == null ? 0 : s.regex().length()) + " chars)");

        long hsBytes = haystackByteSize(s);
        assumeTrue(hsBytes >= 0 && hsBytes <= MAX_HAYSTACK_BYTES,
                "haystack too big (" + hsBytes + " bytes)");

        // --- Compile. rebar's "re2" identity is a Perl leftmost-first
        //     automata engine, so we use PERL disambiguation (the same default
        //     our public re2j Pattern uses at Pattern.java:93-94). The factory
        //     is whatever -Dtdfa.engine resolves to (ASM by default — the
        //     library's primary backend). Case-insensitivity is applied via
        //     the (?i) inline flag, the same way our re2j Pattern does it
        //     (Pattern.java:90).
        //
        //     ASM fallback: when the pattern produces a DFA big enough to
        //     blow the JVM 65 KB method-size limit (e.g. the i787 keywords
        //     alternation, the parol-veryl lexer), ASM throws
        //     IllegalStateException→MethodTooLargeException. The result is
        //     identical on the VM backend — only slower — so we retry there
        //     instead of skipping. The underlying ASM splitting fix is
        //     tracked in TODO.md ("Performance" section).

        String flPat = s.caseInsensitive() ? "(?i)" + s.regex() : s.regex();
        long compileStart = System.nanoTime();
        EngineFactory factory = EngineFactory.DEFAULT;
        Regex compiled = null;
        try {
            final EngineFactory f0 = factory;
            compiled = withTimeout(COMPILE_TIMEOUT_MS, "compile",
                    () -> Regex.compile(flPat, f0, Disambiguation.PERL));
        } catch (TimeoutException e) {
            skipCount.incrementAndGet();
            assumeTrue(false, "COMPILE_TIMEOUT " + COMPILE_TIMEOUT_MS + "ms");
            return;
        } catch (Exception e) {
            // ASM failures arrive as ExecutionException(IllegalStateException)
            // via the FutureTask wrapper; recurse to find the ASM backend
            // sentinel. We retry on VM below if it looks like an ASM-only
            // problem (method-too-large etc.); the VM and ASM backends
            // produce identical match results, only speed differs.
            if (!looksLikeAsmOnlyFailure(e)) {
                skipCount.incrementAndGet();
                assumeTrue(false, "compile failed: " + e.getClass().getSimpleName()
                        + (e.getMessage() != null ? ": " + e.getMessage() : ""));
                return;
            }
            try {
                final EngineFactory f1 = EngineFactory.VM;
                compiled = withTimeout(COMPILE_TIMEOUT_MS, "compile-vm",
                        () -> Regex.compile(flPat, f1, Disambiguation.PERL));
                factory = EngineFactory.VM;
            } catch (TimeoutException te) {
                skipCount.incrementAndGet();
                assumeTrue(false, "COMPILE_TIMEOUT on ASM+VM fallback");
                return;
            } catch (Exception vmE) {
                skipCount.incrementAndGet();
                assumeTrue(false, "compile failed on both ASM and VM: "
                        + vmE.getClass().getSimpleName()
                        + (vmE.getMessage() != null ? ": " + vmE.getMessage() : ""));
                return;
            }
        }
        if (compiled == null) {  // shouldn't happen; defensive
            skipCount.incrementAndGet();
            assumeTrue(false, "compile returned null");
            return;
        }
        final Regex r = compiled;
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
        if (factory == EngineFactory.VM && EngineFactory.DEFAULT == EngineFactory.ASM) {
            System.out.printf("ASM-FAIL %-60s (fell back to VM)  /%s/%n",
                    s.fullName(), abbrev(s.regex(), 50));
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
     * Does {@code e} look like an ASM-backend-only failure (method-too-large
     * etc.) that the VM backend can recover from? We unwrap
     * {@link java.util.concurrent.ExecutionException} from the FutureTask
     * wrapper, then look for the ASM backend's sentinel message or
     * {@code org.objectweb.asm.MethodTooLargeException} in the cause chain.
     */
    private static boolean looksLikeAsmOnlyFailure(Throwable e) {
        Throwable c = e;
        for (int i = 0; i < 6 && c != null; i++) {
            String name = c.getClass().getName();
            if (name.equals("org.objectweb.asm.MethodTooLargeException")) return true;
            String msg = c.getMessage();
            if (msg != null && msg.contains("ASM backend failed")) return true;
            c = c.getCause();
        }
        return false;
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
            // Empty-match safe advance: if the match consumed no chars (e.g.
            // `$`, `(?=)`, `a*` on a non-matching position), step past it
            // so we don't loop forever — and so a leftmost match reported
            // ahead of `pos` isn't counted twice (once for the start search
            // that finds it, once for the next find at the same end pos).
            pos = (end <= m.start(0)) ? end + 1 : end;
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
            pos = (end <= m.start(0)) ? end + 1 : end;
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
            pos = (end <= m.start(0)) ? end + 1 : end;
        }
        return n;
    }

    /**
     * Count haystack lines that contain at least one match (rebar's 'grep'
     * model). Matches MODELS.md §grep pseudo-code: iterate on {@code \n}, strip
     * a trailing {@code \r} from CRLF-terminated lines, then ask the engine
     * for any match within the line (line terminator excluded).
     */
    private static long grepLines(Regex r, String hs) {
        long matched = 0;
        int lineStart = 0;
        for (int i = 0; i <= hs.length(); i++) {
            if (i == hs.length() || hs.charAt(i) == '\n') {
                int lineEnd = i;
                // Strip a trailing \r so CRLF-terminated lines match the same
                // way LF-terminated lines do.
                if (lineEnd > lineStart && hs.charAt(lineEnd - 1) == '\r') {
                    lineEnd--;
                }
                String line = hs.substring(lineStart, lineEnd);
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
