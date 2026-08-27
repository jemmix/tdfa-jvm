package io.github.jemmix.tdfa;

import io.github.jemmix.tdfa.core.Matcher;
import io.github.jemmix.tdfa.core.PatternSyntaxException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ReDoS adversarial battery: the classic catastrophic-backtracking corpus
 * (nested quantifiers, overlapping alternations, quantified optionals) run
 * against the engine's linear-time guarantee.
 *
 * <p>Three gates per case, on both backends:
 * <ol>
 *   <li><b>compile</b> — completes within {@link #COMPILE_BOUND_MS} or is
 *       rejected cleanly with {@link PatternSyntaxException} within
 *       {@link #REJECT_BOUND_MS} (determinization budget); any other
 *       exception fails.</li>
 *   <li><b>match</b> — a full find/matches pass over the adversarial input
 *       completes within {@link #MATCH_BOUND_MS}. A backtracking engine
 *       needs minutes-to-centuries on several of these; the TDFA walk is
 *       microseconds, so the bound has ~5 orders of magnitude of slack —
 *       it trips only on algorithmic blowup, never JIT noise.</li>
 *   <li><b>scaling</b> — for cases flagged {@code probe}, doubling the input
 *       at most doubles the walk time (ratio bounded by {@link #SCALE_RATIO_MAX}
 *       with an absolute floor to absorb noise).</li>
 * </ol>
 */
class RedosBatteryTest {

    private static final long COMPILE_BOUND_MS = 5_000;   // matches CompileLatencyGuardTest
    private static final long REJECT_BOUND_MS = 60_000;   // cap-crossing abort: measured 6–19 s on the shapes below
    private static final long MATCH_BOUND_MS = 2_000;
    private static final long SCALE_RATIO_MAX = 8;        // linear walk ⇒ ~2×; huge slack for noise
    private static final long SCALE_FLOOR_MS = 50;

    record Case(String name, String pattern, IntFunction<String> input, boolean scalingProbe) {
        @Override public String toString() { return name + " `" + pattern + "`"; }
    }

    private static String repeat(char c, int n) {
        return String.valueOf(c).repeat(n);
    }

    static Stream<Case> evilPatterns() {
        return Stream.of(
                new Case("nested-plus", "(a+)+b", n -> repeat('a', 40) + "X", false),
                new Case("nested-alt", "(a|aa)+b", n -> repeat('a', 40) + "X", false),
                new Case("optional-star-alt", "(a|a?)+b", n -> repeat('a', 40) + "X", false),
                new Case("star-of-star", "(a*)*b", n -> repeat('a', 40) + "X", false),
                new Case("plus-of-star", "(a+)*b", n -> repeat('a', 40) + "X", false),
                new Case("triple-nested", "((a+)*)+b", n -> repeat('a', 40) + "X", false),
                new Case("double-x", "(x+x+)+y", n -> repeat('x', 60) + "z", true),
                new Case("alt-group-repeat", "(a|b|ab)*c", n -> "ab".repeat(30) + "d", false),
                new Case("ab-star-nested", "(a*b*)*c", n -> "ab".repeat(30) + "d", true),
                new Case("famous-so", "^(([a-z])+.)+[A-Z]([a-z])+$",
                        n -> repeat('a', 30) + "!", false),
                new Case("quantified-optional", "(a?){100}a{100}", n -> repeat('a', 99) + "X", false),
                new Case("word-boundary-loop", "\\b(\\w+\\s?)+$", n -> "word ".repeat(30) + "!", false),
                new Case("unicode-letters", "(\\p{L}+)+\\d",
                        n -> "αβγδεζηθ".repeat(10) + "!", false),
                new Case("dot-star-groups", "(.*)(.*)(.*)(.*)a", n -> repeat('b', 100) + "c", true),
                new Case("overlapping-alt", "(a|b)*c", n -> repeat('a', 2000) + "d", true),
                new Case("group-optional-loop", "^([a-zA-Z0-9]+)*$",
                        n -> repeat('a', 60) + "-", false),
                new Case("suffix-fail-loop", "(a+){10}b", n -> repeat('a', 45) + "X", false),
                new Case("inner-star-suffix", "(a*)+b", n -> repeat('a', 50) + "X", false),
                // Post-M3 this two-site wide-class shape compiles fast (tagless kernels
                // collapse; measured ~0.9 s) — kept here as a regression guard for that win.
                new Case("two-site-wide-repeat",
                        "[\\s\\S]{0,100}x[\\s\\S]{0,100}",
                        n -> repeat('a', PROBE_N / 2) + "x" + repeat('b', PROBE_N / 2), true)
        );
    }

    /** Scaling-probe input sizes: n and 2n. */
    private static final int PROBE_N = 20_000;

    static Stream<Arguments> casesByEngine() {
        List<Arguments> out = new ArrayList<>();
        for (io.github.jemmix.tdfa.core.RegexEngineFactory factory : engineFactories().toList())
            for (Case c : evilPatterns().toList())
                out.add(Arguments.of(c, factory));
        return out.stream();
    }

    static Stream<io.github.jemmix.tdfa.core.RegexEngineFactory> engineFactories() {
        return Stream.<io.github.jemmix.tdfa.core.RegexEngineFactory>of(null,
                io.github.jemmix.tdfa.tdfa.TdfaRunner::new);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("casesByEngine")
    void linearTimeUnderAttack(Case c, io.github.jemmix.tdfa.core.RegexEngineFactory factory) {
        // Gate 1: compile promptly or reject cleanly.
        Pattern p;
        long t0 = System.nanoTime();
        try {
            p = Pattern.compile(c.pattern(), 0, factory);
        } catch (PatternSyntaxException e) {
            long ms = (System.nanoTime() - t0) / 1_000_000;
            assertThat(ms).as("%s: budget rejection must be prompt (%d ms)", c, ms)
                    .isLessThan(REJECT_BOUND_MS);
            return;
        } catch (RuntimeException e) {
            throw new AssertionError(c + " threw non-syntax exception at compile", e);
        }
        long compileMs = (System.nanoTime() - t0) / 1_000_000;
        assertThat(compileMs).as("%s: compile must stay bounded (%d ms)", c, compileMs)
                .isLessThan(COMPILE_BOUND_MS);

        // Gate 2: single adversarial pass completes.
        Matcher m = p.matcher(c.input().apply(0));
        t0 = System.nanoTime();
        m.find();
        long matchMs = (System.nanoTime() - t0) / 1_000_000;
        assertThat(matchMs).as("%s: adversarial match must stay bounded (%d ms)", c, matchMs)
                .isLessThan(MATCH_BOUND_MS);

        // Gate 3: linear scaling probe (best-of-3 at each size).
        if (c.scalingProbe()) {
            long tN = bestOf3(p, c.input().apply(PROBE_N));
            long t2N = bestOf3(p, c.input().apply(PROBE_N * 2));
            long floor = Math.max(tN, SCALE_FLOOR_MS);
            double ratio = (double) t2N / floor;
            assertThat(ratio)
                    .as("%s: doubling %d→%d chars must not blow up (t=%d→%d ms, ratio=%.1f)",
                            c, PROBE_N, PROBE_N * 2, tN, t2N, ratio)
                    .isLessThan(SCALE_RATIO_MAX);
        }
    }

    private static long bestOf3(Pattern p, String input) {
        long best = Long.MAX_VALUE;
        for (int i = 0; i < 3; i++) {
            Matcher m = p.matcher(input);
            long t0 = System.nanoTime();
            m.find();
            long ms = (System.nanoTime() - t0) / 1_000_000;
            best = Math.min(best, ms);
        }
        return best;
    }

    /** Patterns whose minimal DFA exceeds the determinization budget: clean, prompt rejection. */
    static Stream<Arguments> bombPatterns() {
        List<Arguments> out = new ArrayList<>();
        for (io.github.jemmix.tdfa.core.RegexEngineFactory factory : engineFactories().toList()) {
            // rebar curated/10-bounded-repeat/context: state-cap rejection (measured ~19 s at 100 001 states).
            out.add(Arguments.of("state-cap-context",
                    "[A-Za-z]{10}\\s+[\\s\\S]{0,100}Result[\\s\\S]{0,100}\\s+[A-Za-z]{10}", factory));
            // kernel-total-cap rejection (measured ~6–10 s at 62 334 states / 50 M kernels; needs ≥2 GB heap).
            out.add(Arguments.of("kernel-cap-two-site-word",
                    "\\w{0,400}y\\w{0,400}", factory));
        }
        return out.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("bombPatterns")
    void stateBombsRejectCleanly(String name, String pattern,
                                 io.github.jemmix.tdfa.core.RegexEngineFactory factory) {
        long t0 = System.nanoTime();
        try {
            Pattern.compile(pattern, 0, factory);
            throw new AssertionError(name + ": expected budget rejection for `" + pattern + "`");
        } catch (PatternSyntaxException e) {
            long ms = (System.nanoTime() - t0) / 1_000_000;
            assertThat(ms).as("%s: rejection must be prompt (%d ms)", name, ms)
                    .isLessThan(REJECT_BOUND_MS);
            assertThat(e.getMessage()).as(name + ": rejection must be the documented too-large error")
                    .containsAnyOf("too large", "too many");
        } catch (OutOfMemoryError | StackOverflowError e) {
            throw new AssertionError(name + ": bomb leaked past the budget as " + e, e);
        }
    }

    @AfterAll
    static void summary() {
        System.out.println("---- ReDoS battery: all gates green ----");
    }
}
