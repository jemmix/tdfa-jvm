package io.github.jemmix.tdfa.parity;

import io.github.jemmix.tdfa.core.compile.RegexEngineFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static io.github.jemmix.tdfa.parity.Re2jOracle.assertSameFind;
import static org.assertj.core.api.Assertions.assertThat;

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
 * the retired group-participation-TAIL exclusion. The cells that stay
 * out of the hard twin are the stock JDK's nested-loop inner-capture
 * quirk ({@code ((a)*b)*} on {@code "abab"}: the GroupCurly
 * rewrite-on-unwind leaves the FIRST writing iteration's g2 0..1) — the
 * dedicated battery below pins that quirk BOTH WAYS: the live oracle
 * keeps its first-writing span, the engine holds the last-completed
 * write, the exact-restore discipline the MVT tier and the patched jur
 * oracle (JDK-8381696 dress rehearsal) both report. #179 retired the
 * engine-side sticky emulation that used to reproduce the stock
 * quirk.</p>
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
        // (the consuming-TAIL family retired its exclusion with the
        // unbounded zero-width-exit split — pinned HARD further down; the
        // doubly-nested stars ((a+)*)* / ((a)*)* carry the same retired
        // residual in g2)
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
        // the nested-loop inner-capture quirk shapes stay OUT of the hard
        // twin — the stock JDK's first-writing spans diverge from the
        // engine's exact-restore discipline; the dedicated battery below
        // pins them both ways
    }

    /** java's nested-loop inner-capture quirk, pinned BOTH WAYS. java
     *  compiles a greedy quantifier directly on a capture with a
     *  deterministic body (its {@code GroupCurly}) and its {@code match0}
     *  backoff rewrites the pair's span on every unwind — outermost
     *  rewriting frame last — so when that construct sits inside another
     *  quantified construct, the STOCK JDK reports the span the FIRST
     *  enclosing-loop iteration that matched the capture wrote (its last
     *  inner iteration): {@code ((a)*b)*} on {@code "abab"} &rarr; g2
     *  {@code 0..1}. That rewrite is one of the GroupCurly behaviors the
     *  JDK-8381696 exact-restore fix removes (the vendored patched jur
     *  oracle — the fuzz default — reports 2..3), and it is the behavior
     *  the engine's own MVT tier has always reported. #179 retired the
     *  engine-side sticky emulation (Tnfa's sticky split) that reproduced
     *  the stock quirk: last-write-wins cannot express a first-WRITING
     *  iteration (data-dependent: on {@code "babab"} the first iteration
     *  matches nothing), and holding both disciplines in one engine split
     *  the plain and multi tiers apart on every such shape. The engine
     *  now keeps ONE discipline — the last-completed write, within the
     *  invocation last inner iteration ({@code ((a)+b)*} on
     *  {@code "aab"}: g2 {@code 1..2}) — and the stock JDK's quirk value
     *  is pinned beside it so the divergence can never go silent. Spellings
     *  covered: {@code *}, {@code {0,}}, {@code {1,}}, {@code {1,2}},
     *  bounded enclosing {@code {n,}}/{@code {n}}/{@code {n,m}}, the
     *  three-deep {@code (?:(X){0,}\S)+} (#179's seed family), and the
     *  {@code (\b(.){1,2})*} anchor-body edition. */
    private record QuirkCase(String pattern, String input, int[] jurSpans, int[] tdfaSpans) {
    }

    private static Stream<QuirkCase> nestedLoopQuirks() {
        return Stream.of(
            // the #87 canonical shapes: the stock oracle keeps the first
            // writing invocation's span, the engine the last's
            new QuirkCase("((a)*b)*", "abab", new int[]{0, 4, 2, 4, 0, 1}, new int[]{0, 4, 2, 4, 2, 3}),
            new QuirkCase("((a)*b)*", "babab", new int[]{0, 5, 3, 5, 1, 2}, new int[]{0, 5, 3, 5, 3, 4}),
            new QuirkCase("((ab)*c)*", "abcabc", new int[]{0, 6, 3, 6, 0, 2}, new int[]{0, 6, 3, 6, 3, 5}),
            new QuirkCase("((a)*cb*)*", "acbacbb", new int[]{0, 7, 3, 7, 0, 1}, new int[]{0, 7, 3, 7, 3, 4}),
            // the stock quirk is per-PAIR: stock keeps g2's first writing
            // and g3's last in the same match; the engine reports the
            // last write for both
            new QuirkCase("((a)*[cd](b)*)*", "adacb", new int[]{0, 5, 2, 5, 0, 1, 4, 5},
                new int[]{0, 5, 2, 5, 2, 3, 4, 5}),
            new QuirkCase("(x((a)*b)*y)*", "xababyababy", new int[]{0, 6, 0, 6, 3, 5, 1, 2},
                new int[]{0, 6, 0, 6, 3, 5, 3, 4}),
            new QuirkCase("(b(a)*)*", "baba", new int[]{0, 4, 2, 4, 1, 2}, new int[]{0, 4, 2, 4, 3, 4}),
            // bounded enclosing shapes: {n,}, {n}, {n,m} are all
            // invocation boundaries in both disciplines
            new QuirkCase("((a)*b){2,}", "ababab", new int[]{0, 6, 4, 6, 0, 1}, new int[]{0, 6, 4, 6, 4, 5}),
            new QuirkCase("((a)*b){2}", "abab", new int[]{0, 4, 2, 4, 0, 1}, new int[]{0, 4, 2, 4, 2, 3}),
            new QuirkCase("((a)*b){2,3}", "ababab", new int[]{0, 6, 4, 6, 0, 1}, new int[]{0, 6, 4, 6, 4, 5}),
            new QuirkCase("((a){1,2}b)*", "aabab", new int[]{0, 5, 3, 5, 1, 2}, new int[]{0, 5, 3, 5, 3, 4}),
            // the #179 seed family — the three-deep quantified-group
            // shapes, every spelling (the {0,} twin below is the same
            // construct as ((a)*b)* above with the outer group plain)
            new QuirkCase("(?:(a){0,}b)+", "abab", new int[]{0, 4, 0, 1}, new int[]{0, 4, 2, 3}),
            new QuirkCase("((a){0,}b)*", "abab", new int[]{0, 4, 2, 4, 0, 1}, new int[]{0, 4, 2, 4, 2, 3}),
            new QuirkCase("(?:(a){0,}\\S)+", "babab", new int[]{0, 5, 1, 2}, new int[]{0, 5, 3, 4}),
            new QuirkCase("(?:((a){0,})b)+", "aabab", new int[]{0, 5, 3, 4, 1, 2}, new int[]{0, 5, 3, 4, 3, 4}),
            new QuirkCase("(?:(a){1,}b)+", "aabab", new int[]{0, 5, 1, 2}, new int[]{0, 5, 3, 4}),
            new QuirkCase("(?:(a){0,2}b)+", "aabab", new int[]{0, 5, 1, 2}, new int[]{0, 5, 3, 4}),
            new QuirkCase("(?:(a){0,}b)*c", "ababc", new int[]{0, 5, 0, 1}, new int[]{0, 5, 2, 3}),
            // the #179 zero-width-anchor family's range spelling: {1,2}
            // has a backoff range (cmin < cmax), so the stock quirk
            // fires where the exact {2} (the agreeing battery below) does
            // not — java never backs off below cmin
            new QuirkCase("(\\b(.){1,2})*", "ab!cd", new int[]{0, 4, 2, 4, 1, 2}, new int[]{0, 4, 2, 4, 3, 4}));
    }

    /** The oracle keeps the stock quirk AND the engine keeps the
     *  exact-restore reading — one divergence, two pinned truths. */
    @ParameterizedTest
    @MethodSource("nestedLoopQuirks")
    void nestedLoopInnerCaptureQuirkPinnedBothWays(QuirkCase c) {
        assertThat(JurOracle.jurFind(c.pattern(), c.input()))
            .as("live oracle keeps the first-writing quirk: %s on %s", c.pattern(), c.input()).isEqualTo(c.jurSpans());
        for (var factory : JurOracle.engineFactories().toList()) {
            assertThat(JurOracle.tdfaFind(c.pattern(), c.input(), factory))
                .as("engine keeps the last-completed write: %s on %s [%s]", c.pattern(), c.input(), factory)
                .isEqualTo(c.tdfaSpans());
        }
    }

    /** The nested-loop shapes where the stock JDK has NO quirk to
     *  reproduce — exact-bounded {@code {n}} inners (java's GroupCurly
     *  never backs off below cmin), single-writing invocations, and the
     *  anchor-body {@code (\b(.){2})*} family (#179's second seed
     *  signature) — compare HARD against the live oracle in full. */
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.JurOracle#engineFactories")
    void nestedLoopInnerCaptureAgrees(RegexEngineFactory factory) {
        // single writing invocation: first-writing and last-writing
        // disciplines coincide
        JurOracle.assertSameFind("((a)*b)*", "ab", factory); // g2 = 0..1
        JurOracle.assertSameFind("((a)*b)*", "b", factory); // g2 = -1 (zero inner matches)
        JurOracle.assertSameFind("((a)+b)*", "aab", factory); // within-invocation last: g2 = 1..2
        JurOracle.assertSameFind("((x(a)*y)*)*", "xayxaxy", factory); // g3 = 1..2
        JurOracle.assertSameFind("((a)*)*", "a a", factory); // one writing invocation: g2 = 0..1
        JurOracle.assertSameFind("((a)*)*", "aa", factory); // degenerate: family-6 spans intact
        JurOracle.assertSameFind("(((a)*)*)*", "a", factory); // triple nesting agrees (one invocation)
        JurOracle.assertSameAllMatches("((a)*b|x)*", "abxb", factory); // through alternation: g2 = 0..1
        JurOracle.assertSameFind("((a){2,}b)*", "aab", factory); // {2,} inner, one invocation: g2 = 1..2
        // the exact-bounded {2} spellings: java's GroupCurly never
        // descends below cmin, so no rewrite fires — #179's second seed
        // family, both spellings of the anchor body plus the {2,2} twin
        JurOracle.assertSameFind("(\\b(.){2})*", "ab!cd", factory); // two writing iterations: g2 = 3..4 (last)
        JurOracle.assertSameFind("(\\b(.){2})*", "a!bc!de", factory); // g2 = 5..6
        JurOracle.assertSameFind("(\\b(.){2,2})*", "ab!cd", factory); // the {2,2} spelling: g2 = 3..4
        JurOracle.assertSameFind("((\\b)(.){2})*", "ab!cd", factory); // captured anchor: g2 = 2..2, g3 = 3..4
        JurOracle.assertSameFind("(\\b(.){2})*x", "ab!cdx", factory); // consuming tail: 3..6 (3..5 4..5)
        JurOracle.assertSameFind("(\\b(.){2,})*", "abcde", factory); // one unbounded invocation: g2 = 4..5
        JurOracle.assertSameFind("((\\b)(.){2})*x", "abx", factory); // g2 = 0..0, g3 = 1..2
        JurOracle.assertSameFind("((a){2}b)*", "aabaab", factory); // exact-bounded inner: g2 = 4..5 (last)
        JurOracle.assertSameFind("((a){2}b){2}", "aabaab", factory); // bounded enclosing twin
        JurOracle.assertSameFind("((ab){2}c)*", "ababcababc", factory); // multi-unit inner: g2 = 7..9
        // controls: shapes java never marks — the Ques inner keeps the
        // loop lineage (last iteration), the lazy inner never rewrites,
        // and a top-level single-invocation GroupCurly is plain last-write
        JurOracle.assertSameFind("((a)?b)*", "abab", factory); // g2 = 2..3 (last)
        JurOracle.assertSameFind("((a)*?b)*", "abab", factory); // lazy inner: g2 = 2..3
        JurOracle.assertSameFind("(a)*", "aaa", factory); // top level: g1 = "a" (last iteration)
        JurOracle.assertSameFind("(a|b)*", "ab", factory); // branch bodies never mark
    }
}
