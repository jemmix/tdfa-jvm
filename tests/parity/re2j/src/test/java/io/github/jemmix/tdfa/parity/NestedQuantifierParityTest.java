package io.github.jemmix.tdfa.parity;

import io.github.jemmix.tdfa.core.compile.RegexEngineFactory;
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
 *
 * <p>Jur twin below — this suite IS the family-6 territory, so the twin
 * carries the group clauses to the live {@code java.util.regex} oracle in
 * full (the JUR lane's last-completed-iteration protocol). The
 * consuming-tail shapes {@code (a*)*b} and friends COMPARE HARD since the
 * unbounded zero-width-exit split (finding 2's mirror: the exiting ε
 * iteration's writes ride a normal ε-edge at the loop's REST position) —
 * the retired group-participation-TAIL exclusion. The one cell that
 * stays out is java's nested-loop inner-capture quirk
 * {@code ((a)*b)*} on {@code "abab"} (the JDK restores the FIRST
 * iteration's inner group, g2 0..1; the loop lineage reports the last) —
 * a third residual family, documented at the twin below.</p>
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

    /** Jur twin (group clauses carried in full; the TAIL residual excluded). */
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.JurOracle#engineFactories")
    void jurTwinFamilySixTerritory(RegexEngineFactory factory) {
        JurOracle.assertSameFind("(a*)*", "aaa", factory); // g1 = "aaa", no empty re-iteration
        JurOracle.assertSameFind("(a*)*", "", factory); // g1 = "" via the single empty iteration
        JurOracle.assertSameFind("(a*)+", "aaa", factory);
        // the consuming-TAIL residual family stays out of the hard twin —
        // see class javadoc: (a*)*b, ((a*)*)*b, (a*?)*b and (a?){2,}b on
        // "aab" (JDK reports the empty final iteration's g1 2..2; ours
        // reports the last consuming one); the doubly-nested stars
        // ((a+)*)* / ((a)*)* carry the same residual in g2
        JurOracle.assertSameFind("(a+)*", "aaa", factory); // g1 = "aaa" (one iteration)
        JurOracle.assertSameFind("(a+)*b", "aaab", factory); // plus bodies always consume: agrees
        JurOracle.assertSameFind("(a?)*", "aa", factory); // g1 = "a" (last iteration)
        JurOracle.assertSameFind("(a?)+", "aa", factory);
        JurOracle.assertSameFind("(?:a?)*b", "aab", factory); // non-capturing body
        JurOracle.assertSameFind("(a*?)*", "aa", factory); // outer greedy, inner lazy
        JurOracle.assertSameFind("(a*)+?", "aa", factory); // outer lazy: g1 = "aa" in one iteration
        JurOracle.assertSameFind("(a{0,2})*", "aaaa", factory); // g1 = "aa" then "aa"
        JurOracle.assertSameFind("(a{0,2})*", "aaa", factory); // g1 = final "a"
        JurOracle.assertSameFind("(a|)+", "aa", factory); // g1 = "a" (last non-empty iteration)
        JurOracle.assertSameFind("(a|b|)*", "ab", factory); // g1 = "b"
        JurOracle.assertSameFind("((a|b)*)+", "abab", factory); // g1 = "abab", g2 = "b"
        JurOracle.assertSameFind("(a(a)*)*", "aaa", factory); // g1 = "aaa", g2 = "a"
        JurOracle.assertSameFind("(x(y)*)*z", "xyyxz", factory); // g1 = "x", g2 = "y" (2nd iteration)
        JurOracle.assertSameFind("(?:a(b*))*c", "abbc", factory); // g1 = "bb"
        JurOracle.assertSameFind("(?:((a))*)+", "aa", factory); // g1 = g2 = "a"
        JurOracle.assertSameFind("((a{1,2}){2}){2}", "a".repeat(8), factory); // g1 = "aaaa", g2 = "aa"
        JurOracle.assertSameFind("(a{2,4}?){3}", "a".repeat(8), factory); // lazy body: 3×2
        JurOracle.assertSameFind("(?:a{2})?b", "aab", factory);
        JurOracle.assertSameFind("(a?){2,3}", "aa", factory); // g1 = "" (trailing empty iteration)
        // the consuming-TAIL family — HARD since the unbounded zero-width-exit
        // split: the exiting ε iteration's writes ride a normal ε-edge at the
        // loop's REST position, so the JDK's g1 2..2 compares clean (these
        // were the retired group-participation-tail exclusion)
        JurOracle.assertSameFind("(a*)*b", "aab", factory); // g1 = 2..2 (final empty iteration, rest position)
        JurOracle.assertSameFind("(a*?)*b", "aab", factory); // lazy inner: same rest-position write
        JurOracle.assertSameFind("(a?){2,}b", "aab", factory); // bounded prefix + plus tail: g1 2..2
        JurOracle.assertSameFind("(a?){2,3}b", "aab", factory); // bounded suffix edition
        JurOracle.assertSameFind("((a)?b)*", "abab", factory); // g2 = 2..3 (live JDK 26: the last iteration)
        // ((a)*b)* on "abab" stays out: the JDK backtracker's nested-loop
        // inner-capture quirk — it restores the FIRST iteration's inner
        // group (g2 0..1) where the loop lineage reports the last; the
        // remaining third residual family (class javadoc)
    }
}
