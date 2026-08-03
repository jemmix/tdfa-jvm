package io.github.jemmix.tdfa.parity;

import org.junit.jupiter.api.Test;

import static io.github.jemmix.tdfa.parity.Re2jOracle.*;

/**
 * Quantifier parity: greedy, lazy, bounded repetition, alternation priority.
 */
class QuantifierParityTest {

    @Test void starGreedy() { assertSameFind("a*", "aaa"); }
    @Test void starGreedyNone() { assertSameFind("a*", "bbb"); }
    @Test void plusGreedy() { assertSameFind("a+", "aaa"); }
    @Test void plusGreedyNone() { assertSameFind("a+", "bbb"); }
    @Test void question() { assertSameFind("ab?", "ab"); }
    @Test void questionNoMatch() { assertSameFind("ab?", "ac"); }

    @Test void starLazy() { assertSameFind("a*?", "aaa"); }
    @Test void plusLazy() { assertSameFind("a+?", "aaa"); }
    @Test void questionLazy() { assertSameFind("ab??", "ab"); }

    @Test void boundedExact() { assertSameFind("a{3}", "aaaa"); }
    @Test void boundedExactFail() { assertSameFind("a{3}", "aa"); }
    @Test void boundedMin() { assertSameFind("a{2,}", "aaaaa"); }
    @Test void boundedRange() { assertSameFind("a{2,4}", "aaaaaa"); }
    @Test void boundedRangeGreedy() { assertSameFind("a{2,4}a", "aaaaaa"); }
    @Test void boundedRangeLazy() { assertSameFind("a{2,4}?a", "aaaaaa"); }
    @Test void zeroQuantifier() { assertSameFind("a{0}", "aaa"); }
    @Test void boundedOnGroup() { assertSameFind("(ab){2}", "abab"); }
    @Test void boundedOnGroupPartial() { assertSameFind("(ab){2}", "aba"); }

    @Test void greedyVsLazyWithSuffix() { assertSameFind("a.*b", "aXbYb"); }
    @Test void greedyVsLazyWithSuffix2() { assertSameFind("a.*?b", "aXbYb"); }

    @Test void alternationGreedy() { assertSameFind("(a|ab)", "ab"); }
    @Test void alternationGreedy2() { assertSameFind("(ab|a)", "ab"); }
    @Test void alternationPosix() { assertSameFindPosix("(a|ab)", "ab"); }
    @Test void alternationPosixLongest() { assertSameFindPosix("(ab|a)", "ab"); }

    @Test void nestedStarPlus() { assertSameFind("(a*)+", "aaa"); }
    @Test void quantifiedAlternation() { assertSameFind("(cat|dog)+", "catdogcat"); }
    @Test void starOnClass() { assertSameFind("[0-9]+", "abc123def"); }

    @Test void lazyStarFindAll() { assertSameAllMatches("a+?", "aaa"); }
    @Test void greedyStarFindAll() { assertSameAllMatches("a+", "aaa"); }

    @Test void alternationEmptyFirst() { assertSameFind("(|a)", "a"); }
    @Test void alternationEmptySecond() { assertSameFind("(a|)", "a"); }

    @Test void repeatWithAnchor() { assertSameFind("^a*", "aaa"); }
    @Test void complexBacktrack() { assertSameFind("(a+)+b", "aaab"); }

    // ---- Pending parity / edge cases ----

    @Test void commaShorthandBounded() { assertSameFind("a{,3}", "aaaa"); }
    @Test void zeroZeroQuantifier() { assertSameFind("a{0,0}", "aaa"); }
    @Test void largeCount() { assertSameFind("a{5}", "aaaaa"); }

    @Test void possessivePlusRejects() { assertSameCompileReject("a++"); }
    @Test void possessiveQuestionRejects() { assertSameCompileReject("a?+"); }
    @Test void possessiveBoundedRejects() { assertSameCompileReject("a{2,3}+"); }

    @Test void invertedRangeRejects() { assertSameCompileReject("a{3,2}"); }
}
