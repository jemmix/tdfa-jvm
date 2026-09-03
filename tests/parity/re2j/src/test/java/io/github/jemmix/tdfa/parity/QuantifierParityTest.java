package io.github.jemmix.tdfa.parity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import io.github.jemmix.tdfa.core.Matcher;
import io.github.jemmix.tdfa.core.PatternSyntaxException;
import io.github.jemmix.tdfa.core.RegexEngineFactory;

import static io.github.jemmix.tdfa.parity.Re2jOracle.*;

/**
 * Quantifier parity: greedy, lazy, bounded repetition, alternation priority.
 */
class QuantifierParityTest {

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void starGreedy(RegexEngineFactory factory) { assertSameFind("a*", "aaa", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void starGreedyNone(RegexEngineFactory factory) { assertSameFind("a*", "bbb", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void plusGreedy(RegexEngineFactory factory) { assertSameFind("a+", "aaa", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void plusGreedyNone(RegexEngineFactory factory) { assertSameFind("a+", "bbb", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void question(RegexEngineFactory factory) { assertSameFind("ab?", "ab", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void questionNoMatch(RegexEngineFactory factory) { assertSameFind("ab?", "ac", factory); }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void starLazy(RegexEngineFactory factory) { assertSameFind("a*?", "aaa", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void plusLazy(RegexEngineFactory factory) { assertSameFind("a+?", "aaa", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void questionLazy(RegexEngineFactory factory) { assertSameFind("ab??", "ab", factory); }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void boundedExact(RegexEngineFactory factory) { assertSameFind("a{3}", "aaaa", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void boundedExactFail(RegexEngineFactory factory) { assertSameFind("a{3}", "aa", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void boundedMin(RegexEngineFactory factory) { assertSameFind("a{2,}", "aaaaa", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void boundedRange(RegexEngineFactory factory) { assertSameFind("a{2,4}", "aaaaaa", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void boundedRangeGreedy(RegexEngineFactory factory) { assertSameFind("a{2,4}a", "aaaaaa", factory); }

    // ---- open counted repetition on a NULLABLE body: final-capture parity ----
    // re2j's Simplify desugars x{n,} as x{n-1}x+ (not x{n}x*): a plus tail
    // guarantees one real iteration and the nullable body's empty
    // RE-iteration is cut by pike pc-dedup, so the last NON-EMPTY capture
    // survives. Our former x* tail let the greedy empty iteration write an
    // empty capture (g1=""), diverging on every (X?){n,} shape (fuzz round
    // 9: 25 records). These pin the family across both engines.

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void openCountedNullableBodyLastCapture(RegexEngineFactory factory) {
        assertSameFind("(a?){2,}", "aa", factory);          // g1="a", not ""
        assertSameFind("(a?){2,}", "aab", factory);
        assertSameFind("(.?){2,}", "xy", factory);          // g1="y"
        assertSameFind("(.{0,2}){2,}", "abc", factory);     // g1="c"
        assertSameFind("(a?){3,}", "aaaa", factory);
        assertSameFind("((a)?){2,}", "aa", factory);        // nested group
        assertSameFind("(a?){2,}b", "aab", factory);        // trailing context
        assertSameFind("x(a?){2,}", "xaa", factory);        // leading context
        assertSameFind("(a{0,}){2,}", "aa", factory);       // star body: g1="" in BOTH
        assertSameFind("(a??){2,}", "aa", factory);
        assertSameFind("(a?){2,}?", "aab", factory);        // lazy open counted
        assertSameFind("(.{0,2}){2,}?", "abcd", factory);
        assertSameFind("(\\W?){2,}", "!!", factory);        // class body: g1="!" (last non-empty)
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void openCountedNullableBodyNoInputMatch(RegexEngineFactory factory) {
        assertSameFind("(a?){2,}", "b", factory);           // empty match, g1="" in both
    }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void boundedRangeLazy(RegexEngineFactory factory) { assertSameFind("a{2,4}?a", "aaaaaa", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void zeroQuantifier(RegexEngineFactory factory) { assertSameFind("a{0}", "aaa", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void boundedOnGroup(RegexEngineFactory factory) { assertSameFind("(ab){2}", "abab", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void boundedOnGroupPartial(RegexEngineFactory factory) { assertSameFind("(ab){2}", "aba", factory); }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void greedyVsLazyWithSuffix(RegexEngineFactory factory) { assertSameFind("a.*b", "aXbYb", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void greedyVsLazyWithSuffix2(RegexEngineFactory factory) { assertSameFind("a.*?b", "aXbYb", factory); }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void alternationGreedy(RegexEngineFactory factory) { assertSameFind("(a|ab)", "ab", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void alternationGreedy2(RegexEngineFactory factory) { assertSameFind("(ab|a)", "ab", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void alternationPosix(RegexEngineFactory factory) { assertSameFindPosix("(a|ab)", "ab", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void alternationPosixLongest(RegexEngineFactory factory) { assertSameFindPosix("(ab|a)", "ab", factory); }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void nestedStarPlus(RegexEngineFactory factory) { assertSameFind("(a*)+", "aaa", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void quantifiedAlternation(RegexEngineFactory factory) { assertSameFind("(cat|dog)+", "catdogcat", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void starOnClass(RegexEngineFactory factory) { assertSameFind("[0-9]+", "abc123def", factory); }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void lazyStarFindAll(RegexEngineFactory factory) { assertSameAllMatches("a+?", "aaa", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void greedyStarFindAll(RegexEngineFactory factory) { assertSameAllMatches("a+", "aaa", factory); }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void alternationEmptyFirst(RegexEngineFactory factory) { assertSameFind("(|a)", "a", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void alternationEmptySecond(RegexEngineFactory factory) { assertSameFind("(a|)", "a", factory); }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void repeatWithAnchor(RegexEngineFactory factory) { assertSameFind("^a*", "aaa", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void complexBacktrack(RegexEngineFactory factory) { assertSameFind("(a+)+b", "aaab", factory); }

    // ---- Pending parity / edge cases ----

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void commaShorthandBounded(RegexEngineFactory factory) { assertSameFind("a{,3}", "aaaa", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void zeroZeroQuantifier(RegexEngineFactory factory) { assertSameFind("a{0,0}", "aaa", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void largeCount(RegexEngineFactory factory) { assertSameFind("a{5}", "aaaaa", factory); }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void possessivePlusRejects(RegexEngineFactory factory) { assertSameCompileReject("a++", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void possessiveQuestionRejects(RegexEngineFactory factory) { assertSameCompileReject("a?+", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void possessiveBoundedRejects(RegexEngineFactory factory) { assertSameCompileReject("a{2,3}+", factory); }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void invertedRangeRejects(RegexEngineFactory factory) { assertSameCompileReject("a{3,2}", factory); }

    // ---- round 18: {n,m} right-nested suffix + capture persistence ----

    /** Fuzz round 18 (overnight seed 645958308): the flat B?B?B? tail for
     *  lazy {n,m} resolved "enter next optional copy" vs "extend current
     *  copy's inner lazy body" opposite to re2j/JDK — group 2 reported the
     *  extended span instead of the next copy's. Fixed by mirroring re2j
     *  Simplify's right-nested (x(x(x)?)?)? suffix. */
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void nestedLazyCountedPrefersNextCopyOverExtension(RegexEngineFactory factory) {
        assertSameFind("((a{1,2}?c?){0,5}?)d", "aad", factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void nestedLazyCountedOriginalOvernightShape(RegexEngineFactory factory) {
        assertSameFind("((.{1,5}?[ \n\ud807\udc07\\s]?){0,5}?)z", "\u6f22\u03a9z", factory);
    }

    /** re2j's pike prog never writes empty captures on skip edges: a group's
     *  value persists once set across later iterations that skip it. The sim
     *  applied ntags destructively and cleared earlier captures (same round). */
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void capturePersistsAcrossSkippedIterations(RegexEngineFactory factory) {
        assertSameFind("((a)?x){2}", "axax", factory);
        assertSameFind("((a)|b)+", "ab", factory);
    }
}
