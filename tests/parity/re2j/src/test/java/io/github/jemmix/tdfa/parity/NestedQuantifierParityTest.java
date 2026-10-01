package io.github.jemmix.tdfa.parity;

import io.github.jemmix.tdfa.core.RegexEngineFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static io.github.jemmix.tdfa.parity.Re2jOracle.assertSameFind;

/**
 * Nested-quantifier parity: quantifiers over quantified bodies — the shapes
 * where a backtracking engine loops on empty iterations and a TDFA must
 * instead cut the empty re-entry while still reporting the LAST iteration's
 * capture span exactly like re2j's pike program. Group spans are compared
 * in full ({@code assertSameFind} includes every group, null-ness encoded
 * as -1), so the empty-iteration families pinned here are differential on
 * the capture semantics, not just the match extent.
 *
 * <p>Families: star-over-star {@code (a*)*}, star-over-plus {@code (a+)*},
 * star-over-optional {@code (a?)*} (last single-char iteration survives),
 * lazy bodies under outer quantifiers, alternation with an empty branch,
 * nested counted quantifiers, and inner capturing groups under outer loops.
 */
class NestedQuantifierParityTest {

    // ---- star over star: the empty re-entry cut ----

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void starOverStar(RegexEngineFactory factory) {
        assertSameFind("(a*)*", "aaa", factory); // g1 = "aaa", no empty re-iteration
        assertSameFind("(a*)*", "", factory); // g1 = "" via the single empty iteration
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void plusOverStar(RegexEngineFactory factory) {
        assertSameFind("(a*)+", "aaa", factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void starOverStarWithSuffix(RegexEngineFactory factory) {
        assertSameFind("((a*)*)*b", "aab", factory);
    }

    // ---- star over plus: last non-empty iteration's span survives ----

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void starOverPlus(RegexEngineFactory factory) {
        assertSameFind("(a+)*", "aaa", factory); // g1 = "aaa" (one iteration)
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void starOverPlusWithSuffix(RegexEngineFactory factory) {
        assertSameFind("(a+)*b", "aaab", factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void starOverLazyPlus(RegexEngineFactory factory) {
        assertSameFind("(a+?)*", "aaa", factory); // g1 = last single "a"
    }

    // ---- star over optional: per-iteration single chars ----

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void starOverOptional(RegexEngineFactory factory) {
        assertSameFind("(a?)*", "aa", factory); // g1 = "a" (last iteration)
        assertSameFind("(a?)+", "aa", factory);
        assertSameFind("(?:a?)*b", "aab", factory); // non-capturing body
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void starOverLazyStar(RegexEngineFactory factory) {
        assertSameFind("(a*?)*", "aa", factory); // outer greedy, inner lazy: empty match wins
        assertSameFind("(a*?)*b", "aab", factory); // forced to consume: g1 = "aa"
        assertSameFind("(a*)+?", "aa", factory); // outer lazy: g1 = "aa" in one iteration
    }

    // ---- star over bounded ----

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void starOverBounded(RegexEngineFactory factory) {
        assertSameFind("(a{0,2})*", "aaaa", factory); // g1 = "aa" then "aa"
        assertSameFind("(a{0,2})*", "aaa", factory); // g1 = final "a"
    }

    // ---- double nesting: outer and inner spans agree ----

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void doublyNestedStars(RegexEngineFactory factory) {
        assertSameFind("((a+)*)*", "aaa", factory); // g1 = g2 = "aaa"
        assertSameFind("((a)*)*", "aa", factory); // g1 = "aa", g2 = "a"
    }

    // ---- alternation with an empty branch under + ----

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void emptyBranchUnderPlus(RegexEngineFactory factory) {
        assertSameFind("(a|)+", "aa", factory); // g1 = "a" (last non-empty iteration)
        assertSameFind("(a|b|)*", "ab", factory); // g1 = "b"
        assertSameFind("((a|b)*)+", "abab", factory); // g1 = "abab", g2 = "b"
    }

    // ---- inner capturing groups under outer loops ----

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void innerGroupUnderStar(RegexEngineFactory factory) {
        assertSameFind("(a(a)*)*", "aaa", factory); // g1 = "aaa", g2 = "a"
        assertSameFind("(x(y)*)*z", "xyyxz", factory); // g1 = "x", g2 = "y" (2nd iteration)
        assertSameFind("(?:a(b*))*c", "abbc", factory); // g1 = "bb"
        assertSameFind("(?:((a))*)+", "aa", factory); // g1 = g2 = "a"
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void optionalGroupUnderStar(RegexEngineFactory factory) {
        assertSameFind("((a)?b)*", "abab", factory); // g1 = "ab", g2 = "a"
        assertSameFind("((a)*b)*", "abab", factory);
    }

    // ---- nested counted quantifiers ----

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void nestedCounted(RegexEngineFactory factory) {
        assertSameFind("((a{1,2}){2}){2}", "a".repeat(8), factory); // g1 = "aaaa", g2 = "aa"
        assertSameFind("(a{2,4}?){3}", "a".repeat(8), factory); // lazy body: 3×2
        assertSameFind("(?:a{2})?b", "aab", factory);
        assertSameFind("(a?){2,3}", "aa", factory); // g1 = "" (trailing empty iteration)
    }
}
