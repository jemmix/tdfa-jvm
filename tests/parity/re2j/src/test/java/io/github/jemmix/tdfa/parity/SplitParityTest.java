package io.github.jemmix.tdfa.parity;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import io.github.jemmix.tdfa.core.Matcher;
import io.github.jemmix.tdfa.core.PatternSyntaxException;
import io.github.jemmix.tdfa.core.RegexEngineFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pattern.split() parity: basic split, limit, trailing-empty handling.
 */
class SplitParityTest {

    private static void assertSplit(String pattern, String input, RegexEngineFactory factory) {
        String[] re2j = com.google.re2j.Pattern.compile(pattern).split(input);
        String[] tdfa = io.github.jemmix.tdfa.Pattern.compile(pattern, 0, factory).split(input);
        assertThat(tdfa).as("split \"%s\" on \"%s\"", pattern, input).isEqualTo(re2j);
    }

    private static void assertSplit(String pattern, String input, int limit, RegexEngineFactory factory) {
        String[] re2j = com.google.re2j.Pattern.compile(pattern).split(input, limit);
        String[] tdfa = io.github.jemmix.tdfa.Pattern.compile(pattern, 0, factory).split(input, limit);
        assertThat(tdfa).as("split \"%s\" on \"%s\" limit=%d", pattern, input, limit)
                .isEqualTo(re2j);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void splitBasic(RegexEngineFactory factory) { assertSplit(",", "a,b,c", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void splitSingleChar(RegexEngineFactory factory) { assertSplit("\\s+", "a b c", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void splitNoMatch(RegexEngineFactory factory) { assertSplit("x", "abc", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void splitEmptyInput(RegexEngineFactory factory) { assertSplit(",", "", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void splitTrailingEmpty(RegexEngineFactory factory) { assertSplit(",", "a,b,", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void splitMultipleTrailingEmpty(RegexEngineFactory factory) { assertSplit(",", "a,b,,,", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void splitLeadingEmpty(RegexEngineFactory factory) { assertSplit(",", ",a", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void splitOnlyDelimiter(RegexEngineFactory factory) { assertSplit(",", ",,", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void splitWordPattern(RegexEngineFactory factory) { assertSplit("\\W+", "hello, world! foo", factory); }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void splitLimit2(RegexEngineFactory factory) { assertSplit(",", "a,b,c", 2, factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void splitLimit1(RegexEngineFactory factory) { assertSplit(",", "a,b,c", 1, factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void splitLimit0(RegexEngineFactory factory) { assertSplit(",", "a,b,c,", 0, factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void splitLimitLarge(RegexEngineFactory factory) { assertSplit(",", "a,b,c", 10, factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void splitLimitNegative(RegexEngineFactory factory) { assertSplit(",", "a,b,c,,,", -1, factory); }

    // ---- Edge cases ----

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void splitZeroWidthPattern(RegexEngineFactory factory) { assertSplit("a*", "aaabbb", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void splitEveryChar(RegexEngineFactory factory) { assertSplit(".", "abc", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void splitEmptyPattern(RegexEngineFactory factory) { assertSplit("", "abc", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void splitOnNullByte(RegexEngineFactory factory) { assertSplit("\\x00", "a\u0000b", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void splitZeroWidthWithLimit(RegexEngineFactory factory) { assertSplit("a*", "aaabbb", 3, factory); }
}
