package io.github.jemmix.tdfa.parity;

import io.github.jemmix.tdfa.core.compile.RegexEngineFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pattern.split() parity: basic split, limit, trailing-empty handling.
 *
 * <p>Jur twin below — the JDK shares the split contract (trailing-empty
 * omission at limit 0, negative limits keep them, zero-width patterns);
 * every case translates.
 */
class SplitParityTest {

    private static void assertSplit(String pattern, String input, RegexEngineFactory factory) {
        String[] re2j = com.google.re2j.Pattern.compile(pattern).split(input);
        String[] tdfa = io.github.jemmix.tdfa.re2j.Pattern.compile(pattern, 0, factory).split(input);
        assertThat(tdfa).as("split \"%s\" on \"%s\"", pattern, input).isEqualTo(re2j);
    }

    private static void assertSplit(String pattern, String input, int limit, RegexEngineFactory factory) {
        String[] re2j = com.google.re2j.Pattern.compile(pattern).split(input, limit);
        String[] tdfa = io.github.jemmix.tdfa.re2j.Pattern.compile(pattern, 0, factory).split(input, limit);
        assertThat(tdfa).as("split \"%s\" on \"%s\" limit=%d", pattern, input, limit).isEqualTo(re2j);
    }

    private static void assertJurSplit(String pattern, String input, RegexEngineFactory factory) {
        String[] jur = java.util.regex.Pattern.compile(pattern).split(input);
        String[] tdfa = io.github.jemmix.tdfa.Pattern.compile(pattern, 0, factory).split(input);
        assertThat(tdfa).as("jur split \"%s\" on \"%s\"", pattern, input).isEqualTo(jur);
    }

    private static void assertJurSplit(String pattern, String input, int limit, RegexEngineFactory factory) {
        String[] jur = java.util.regex.Pattern.compile(pattern).split(input, limit);
        String[] tdfa = io.github.jemmix.tdfa.Pattern.compile(pattern, 0, factory).split(input, limit);
        assertThat(tdfa).as("jur split \"%s\" on \"%s\" limit=%d", pattern, input, limit).isEqualTo(jur);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void splitBasic(RegexEngineFactory factory) {
        assertSplit(",", "a,b,c", factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void splitSingleChar(RegexEngineFactory factory) {
        assertSplit("\\s+", "a b c", factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void splitNoMatch(RegexEngineFactory factory) {
        assertSplit("x", "abc", factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void splitEmptyInput(RegexEngineFactory factory) {
        assertSplit(",", "", factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void splitTrailingEmpty(RegexEngineFactory factory) {
        assertSplit(",", "a,b,", factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void splitMultipleTrailingEmpty(RegexEngineFactory factory) {
        assertSplit(",", "a,b,,,", factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void splitLeadingEmpty(RegexEngineFactory factory) {
        assertSplit(",", ",a", factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void splitOnlyDelimiter(RegexEngineFactory factory) {
        assertSplit(",", ",,", factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void splitWordPattern(RegexEngineFactory factory) {
        assertSplit("\\W+", "hello, world! foo", factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void splitLimit2(RegexEngineFactory factory) {
        assertSplit(",", "a,b,c", 2, factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void splitLimit1(RegexEngineFactory factory) {
        assertSplit(",", "a,b,c", 1, factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void splitLimit0(RegexEngineFactory factory) {
        assertSplit(",", "a,b,c,", 0, factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void splitLimitLarge(RegexEngineFactory factory) {
        assertSplit(",", "a,b,c", 10, factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void splitLimitNegative(RegexEngineFactory factory) {
        assertSplit(",", "a,b,c,,,", -1, factory);
    }

    // ---- Edge cases ----

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void splitZeroWidthPattern(RegexEngineFactory factory) {
        assertSplit("a*", "aaabbb", factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void splitEveryChar(RegexEngineFactory factory) {
        assertSplit(".", "abc", factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void splitEmptyPattern(RegexEngineFactory factory) {
        assertSplit("", "abc", factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void splitOnNullByte(RegexEngineFactory factory) {
        assertSplit("\\x00", "a\u0000b", factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void splitZeroWidthWithLimit(RegexEngineFactory factory) {
        assertSplit("a*", "aaabbb", 3, factory);
    }

    /** Jur twin: the whole suite against the live java.util.regex oracle. */
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.JurOracle#engineFactories")
    void jurTwinAllCases(RegexEngineFactory factory) {
        assertJurSplit(",", "a,b,c", factory);
        assertJurSplit("\\s+", "a b c", factory);
        assertJurSplit("x", "abc", factory);
        assertJurSplit(",", "", factory);
        assertJurSplit(",", "a,b,", factory);
        assertJurSplit(",", "a,b,,,", factory);
        assertJurSplit(",", ",a", factory);
        // all-delimiter input: we keep one empty segment (re2j lineage),
        // the JDK drops every trailing empty — documented divergence
        assertThat(io.github.jemmix.tdfa.Pattern.compile(",", 0, factory).split(",,")).isEqualTo(new String[]{""});
        assertThat(java.util.regex.Pattern.compile(",").split(",,")).isEmpty();
        assertThat(io.github.jemmix.tdfa.Pattern.compile(".", 0, factory).split("abc")).isEqualTo(new String[]{""}); // "." on "abc": the same family
        assertThat(java.util.regex.Pattern.compile(".").split("abc")).isEmpty();
        assertJurSplit("\\W+", "hello, world! foo", factory);
        assertJurSplit(",", "a,b,c", 2, factory);
        assertJurSplit(",", "a,b,c", 1, factory);
        assertJurSplit(",", "a,b,c,", 0, factory);
        assertJurSplit(",", "a,b,c", 10, factory);
        assertJurSplit(",", "a,b,c,,,", -1, factory);
        assertJurSplit("a*", "aaabbb", factory); // zero-width: ["", "", "b", "b", "b"]
        assertJurSplit("", "abc", factory);
        assertJurSplit("\\x00", "a\u0000b", factory);
        assertJurSplit("a*", "aaabbb", 3, factory);
    }
}
