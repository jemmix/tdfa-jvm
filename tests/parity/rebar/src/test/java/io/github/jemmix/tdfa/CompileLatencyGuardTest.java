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
 * <p>NOT covered (rejected by the engine's DEFAULT budget; skipped by name in
 * the suite unless opted in — see {@code RebarScenarioParityTest#
 * BOMB_SCENARIOS}, verified by dedicated probes and documented in TODO.md):
 * {@code curated/10-bounded-repeat/context} —
 * {@code [\s\S]{0,100}} × 2 whose counter cross-product is an intrinsically
 * huge DFA (measured already-minimal on the simplified analog: 60 604 →
 * 60 603 states). At a raised ceiling it compiles to 234 369 states in
 * ~29-42 s with a ~5-6 GB transient working set and passes count verification
 * on both backends — far outside this guard's 5 s scope by design.
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
