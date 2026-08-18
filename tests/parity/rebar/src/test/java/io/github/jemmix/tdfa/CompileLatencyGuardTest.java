package io.github.jemmix.tdfa;

import io.github.jemmix.tdfa.rebar.Scenario;
import io.github.jemmix.tdfa.rebar.ScenarioLoader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Compile-latency regression guard: every formerly-exponential compile in the
 * in-scope corpus must stay under a fixed wall budget through the full facade
 * ({@link Pattern#compile(String, int)}), both backends.
 *
 * <p>Covered bombs (each was a {@code COMPILE_TIMEOUT} (&gt;2 min) or AST-budget
 * skip before the determinize fast-path landed 2026-08-18):
 * <ul>
 *   <li>{@code curated/03-date} — 6.3 KB datefinder alternation, (?i) and
 *       (?i)(?u) variants (was: AST bomb rule "massive non-literal
 *       alternation"; now ~1-3 s);</li>
 *   <li>{@code curated/09-aws-keys/full} — 191-char nested bounded/greedy
 *       alternation (was: AST bomb rule "variable bounded repeat of
 *       wide-unbounded repeat"; now ~0.4 s);</li>
 *   <li>{@code curated/12-dictionary/single} — 2 663-branch literal
 *       alternation, 45 KB regex (legitimately slow-but-finishing;
 *       19.5 K states, minimizes to 6.8 K; ~1.5 s — guards the stateIndex
 *       multimap fix that took it from ~14 s);</li>
 *   <li>{@code i1095 \p{L}{256}} — bounded-repeat over a Unicode class
 *       (~1.4 K ranges/state; guards the W1a/W1b range work).</li>
 * </ul>
 *
 * <p>NOT covered (still budget-skipped in the scenario suite):
 * {@code curated/10-bounded-repeat/context} — {@code [\s\S]{0,100}} × 2 whose
 * counter cross-product is an intrinsically huge DFA (measured
 * already-minimal: 60 604 → 60 603 states on the simplified analog; the real
 * regex compiles in ~49 s / 12 GB heap — see TODO.md "huge-DFA bounded
 * repeats").
 *
 * <p>Budget: 5 s per compile (generous CI headroom over the ~3 s worst
 * measured on a laptop; the point is catching superlinear regressions, not
 * micro-optimizing).
 */
class CompileLatencyGuardTest {

    /** Wall budget per compile, milliseconds. */
    private static final long BUDGET_MS = 5_000;

    static String benchmarksDir = System.getProperty("rebar.benchmarks.dir");

    private static List<Scenario> loaded;

    @BeforeAll
    static void loadCorpus() throws Exception {
        loaded = new ScenarioLoader(Path.of(benchmarksDir)).loadAll();
    }

    static Stream<Arguments> bombs() {
        return Stream.of(
                Arguments.of("datefinder-ascii", "curated/03-date", "ascii", io.github.jemmix.tdfa.Pattern.CASE_INSENSITIVE),
                Arguments.of("datefinder-unicode", "curated/03-date", "unicode",
                        io.github.jemmix.tdfa.Pattern.CASE_INSENSITIVE | io.github.jemmix.tdfa.Pattern.UNICODE_CHARACTER_CLASS),
                Arguments.of("aws-keys-full", "curated/09-aws-keys", "full", 0),
                Arguments.of("dictionary-single", "curated/12-dictionary", "single", 0));
    }

    private static String regexOf(String group, String name) {
        return loaded.stream()
                .filter(s -> s.fullName().equals(group + "/" + name))
                .findFirst().orElseThrow(() -> new IllegalStateException("scenario not found: " + group + "/" + name))
                .regex();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("bombs")
    void compilesWithinBudget(String label, String group, String name, int flags) {
        String regex = regexOf(group, name);
        long t0 = System.nanoTime();
        io.github.jemmix.tdfa.Pattern.compile(regex, flags);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertThat(ms).as("compile wall for %s (%d-char regex, flags=%d)", label, regex.length(), flags)
                .isLessThan(BUDGET_MS);
    }

    /** i1095 has no scenario group/name — inline variant. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("inlineBombs")
    void compilesInlineWithinBudget(String label, String regex) {
        long t0 = System.nanoTime();
        io.github.jemmix.tdfa.Pattern.compile(regex, 0);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertThat(ms).as("compile wall for %s", label).isLessThan(BUDGET_MS);
    }

    static Stream<Arguments> inlineBombs() {
        return Stream.of(Arguments.of("i1095-unicode-compile", "\\p{L}{256}"));
    }
}
