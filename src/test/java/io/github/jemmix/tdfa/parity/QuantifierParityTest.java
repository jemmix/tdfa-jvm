package io.github.jemmix.tdfa.parity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import io.github.jemmix.tdfa.EngineFactory;

import static io.github.jemmix.tdfa.parity.Re2jOracle.*;

/**
 * Quantifier parity: greedy, lazy, bounded repetition, alternation priority.
 */
class QuantifierParityTest {

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void starGreedy(EngineFactory factory) { assertSameFind("a*", "aaa", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void starGreedyNone(EngineFactory factory) { assertSameFind("a*", "bbb", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void plusGreedy(EngineFactory factory) { assertSameFind("a+", "aaa", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void plusGreedyNone(EngineFactory factory) { assertSameFind("a+", "bbb", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void question(EngineFactory factory) { assertSameFind("ab?", "ab", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void questionNoMatch(EngineFactory factory) { assertSameFind("ab?", "ac", factory); }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void starLazy(EngineFactory factory) { assertSameFind("a*?", "aaa", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void plusLazy(EngineFactory factory) { assertSameFind("a+?", "aaa", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void questionLazy(EngineFactory factory) { assertSameFind("ab??", "ab", factory); }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void boundedExact(EngineFactory factory) { assertSameFind("a{3}", "aaaa", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void boundedExactFail(EngineFactory factory) { assertSameFind("a{3}", "aa", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void boundedMin(EngineFactory factory) { assertSameFind("a{2,}", "aaaaa", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void boundedRange(EngineFactory factory) { assertSameFind("a{2,4}", "aaaaaa", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void boundedRangeGreedy(EngineFactory factory) { assertSameFind("a{2,4}a", "aaaaaa", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void boundedRangeLazy(EngineFactory factory) { assertSameFind("a{2,4}?a", "aaaaaa", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void zeroQuantifier(EngineFactory factory) { assertSameFind("a{0}", "aaa", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void boundedOnGroup(EngineFactory factory) { assertSameFind("(ab){2}", "abab", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void boundedOnGroupPartial(EngineFactory factory) { assertSameFind("(ab){2}", "aba", factory); }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void greedyVsLazyWithSuffix(EngineFactory factory) { assertSameFind("a.*b", "aXbYb", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void greedyVsLazyWithSuffix2(EngineFactory factory) { assertSameFind("a.*?b", "aXbYb", factory); }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void alternationGreedy(EngineFactory factory) { assertSameFind("(a|ab)", "ab", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void alternationGreedy2(EngineFactory factory) { assertSameFind("(ab|a)", "ab", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void alternationPosix(EngineFactory factory) { assertSameFindPosix("(a|ab)", "ab", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void alternationPosixLongest(EngineFactory factory) { assertSameFindPosix("(ab|a)", "ab", factory); }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void nestedStarPlus(EngineFactory factory) { assertSameFind("(a*)+", "aaa", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void quantifiedAlternation(EngineFactory factory) { assertSameFind("(cat|dog)+", "catdogcat", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void starOnClass(EngineFactory factory) { assertSameFind("[0-9]+", "abc123def", factory); }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void lazyStarFindAll(EngineFactory factory) { assertSameAllMatches("a+?", "aaa", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void greedyStarFindAll(EngineFactory factory) { assertSameAllMatches("a+", "aaa", factory); }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void alternationEmptyFirst(EngineFactory factory) { assertSameFind("(|a)", "a", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void alternationEmptySecond(EngineFactory factory) { assertSameFind("(a|)", "a", factory); }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void repeatWithAnchor(EngineFactory factory) { assertSameFind("^a*", "aaa", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void complexBacktrack(EngineFactory factory) { assertSameFind("(a+)+b", "aaab", factory); }

    // ---- Pending parity / edge cases ----

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void commaShorthandBounded(EngineFactory factory) { assertSameFind("a{,3}", "aaaa", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void zeroZeroQuantifier(EngineFactory factory) { assertSameFind("a{0,0}", "aaa", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void largeCount(EngineFactory factory) { assertSameFind("a{5}", "aaaaa", factory); }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void possessivePlusRejects(EngineFactory factory) { assertSameCompileReject("a++", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void possessiveQuestionRejects(EngineFactory factory) { assertSameCompileReject("a?+", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void possessiveBoundedRejects(EngineFactory factory) { assertSameCompileReject("a{2,3}+", factory); }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void invertedRangeRejects(EngineFactory factory) { assertSameCompileReject("a{3,2}", factory); }
}
