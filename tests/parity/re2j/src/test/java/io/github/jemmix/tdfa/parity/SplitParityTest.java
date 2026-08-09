package io.github.jemmix.tdfa.parity;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import io.github.jemmix.tdfa.EngineFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pattern.split() parity: basic split, limit, trailing-empty handling.
 */
class SplitParityTest {

    private static void assertSplit(String pattern, String input, EngineFactory factory) {
        String[] re2j = com.google.re2j.Pattern.compile(pattern).split(input);
        String[] tdfa = io.github.jemmix.tdfa.re2j.Pattern.compile(pattern, 0, factory).split(input);
        assertThat(tdfa).as("split \"%s\" on \"%s\"", pattern, input).isEqualTo(re2j);
    }

    private static void assertSplit(String pattern, String input, int limit, EngineFactory factory) {
        String[] re2j = com.google.re2j.Pattern.compile(pattern).split(input, limit);
        String[] tdfa = io.github.jemmix.tdfa.re2j.Pattern.compile(pattern, 0, factory).split(input, limit);
        assertThat(tdfa).as("split \"%s\" on \"%s\" limit=%d", pattern, input, limit)
                .isEqualTo(re2j);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void splitBasic(EngineFactory factory) { assertSplit(",", "a,b,c", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void splitSingleChar(EngineFactory factory) { assertSplit("\\s+", "a b c", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void splitNoMatch(EngineFactory factory) { assertSplit("x", "abc", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void splitEmptyInput(EngineFactory factory) { assertSplit(",", "", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void splitTrailingEmpty(EngineFactory factory) { assertSplit(",", "a,b,", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void splitMultipleTrailingEmpty(EngineFactory factory) { assertSplit(",", "a,b,,,", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void splitLeadingEmpty(EngineFactory factory) { assertSplit(",", ",a", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void splitOnlyDelimiter(EngineFactory factory) { assertSplit(",", ",,", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void splitWordPattern(EngineFactory factory) { assertSplit("\\W+", "hello, world! foo", factory); }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void splitLimit2(EngineFactory factory) { assertSplit(",", "a,b,c", 2, factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void splitLimit1(EngineFactory factory) { assertSplit(",", "a,b,c", 1, factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void splitLimit0(EngineFactory factory) { assertSplit(",", "a,b,c,", 0, factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void splitLimitLarge(EngineFactory factory) { assertSplit(",", "a,b,c", 10, factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void splitLimitNegative(EngineFactory factory) { assertSplit(",", "a,b,c,,,", -1, factory); }

    // ---- Edge cases ----

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void splitZeroWidthPattern(EngineFactory factory) { assertSplit("a*", "aaabbb", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void splitEveryChar(EngineFactory factory) { assertSplit(".", "abc", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void splitEmptyPattern(EngineFactory factory) { assertSplit("", "abc", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void splitOnNullByte(EngineFactory factory) { assertSplit("\\x00", "a\u0000b", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void splitZeroWidthWithLimit(EngineFactory factory) { assertSplit("a*", "aaabbb", 3, factory); }
}
