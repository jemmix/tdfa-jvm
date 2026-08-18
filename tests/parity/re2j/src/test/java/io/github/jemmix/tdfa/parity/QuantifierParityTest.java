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
}
