package io.github.jemmix.tdfa.parity;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import io.github.jemmix.tdfa.EngineFactory;

import static org.assertj.core.api.Assertions.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Replacement API parity: replaceAll, replaceFirst, appendReplacement,
 * appendTail, quoteReplacement with $N backreferences.
 */
class ReplacementParityTest {

    private static String re2jReplaceAll(String pattern, String input, String repl) {
        com.google.re2j.Pattern p = com.google.re2j.Pattern.compile(pattern);
        com.google.re2j.Matcher m = p.matcher(input);
        return m.replaceAll(repl);
    }

    private static String tdfaReplaceAll(String pattern, String input, String repl, EngineFactory factory) {
        io.github.jemmix.tdfa.re2j.Pattern p = io.github.jemmix.tdfa.re2j.Pattern.compile(pattern, 0, factory);
        io.github.jemmix.tdfa.re2j.Matcher m = p.matcher(input);
        return m.replaceAll(repl);
    }

    private static String re2jReplaceFirst(String pattern, String input, String repl) {
        return com.google.re2j.Pattern.compile(pattern).matcher(input).replaceFirst(repl);
    }

    private static String tdfaReplaceFirst(String pattern, String input, String repl, EngineFactory factory) {
        io.github.jemmix.tdfa.re2j.Pattern p = io.github.jemmix.tdfa.re2j.Pattern.compile(pattern, 0, factory);
        return p.matcher(input).replaceFirst(repl);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void replaceAllLiteral(EngineFactory factory) {
        String p = "a", in = "banana", repl = "X";
        assertThat(tdfaReplaceAll(p, in, repl, factory)).isEqualTo(re2jReplaceAll(p, in, repl));
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void replaceAllWithGroup(EngineFactory factory) {
        String p = "(\\w+)", in = "hello world", repl = "[$1]";
        assertThat(tdfaReplaceAll(p, in, repl, factory)).isEqualTo(re2jReplaceAll(p, in, repl));
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void replaceAllMultipleMatches(EngineFactory factory) {
        String p = "\\d", in = "a1b2c3", repl = "#";
        assertThat(tdfaReplaceAll(p, in, repl, factory)).isEqualTo(re2jReplaceAll(p, in, repl));
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void replaceAllNoMatch(EngineFactory factory) {
        String p = "xyz", in = "abc", repl = "Y";
        assertThat(tdfaReplaceAll(p, in, repl, factory)).isEqualTo(re2jReplaceAll(p, in, repl));
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void replaceAllWithDollarSign(EngineFactory factory) {
        String p = "x", in = "axb", repl = "\\$";
        assertThat(tdfaReplaceAll(p, in, repl, factory)).isEqualTo(re2jReplaceAll(p, in, repl));
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void replaceFirstLiteral(EngineFactory factory) {
        String p = "a", in = "banana", repl = "X";
        assertThat(tdfaReplaceFirst(p, in, repl, factory)).isEqualTo(re2jReplaceFirst(p, in, repl));
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void replaceFirstWithGroup(EngineFactory factory) {
        String p = "(\\w)(\\w)", in = "abcd", repl = "$2$1";
        assertThat(tdfaReplaceFirst(p, in, repl, factory)).isEqualTo(re2jReplaceFirst(p, in, repl));
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void replaceFirstNoMatch(EngineFactory factory) {
        String p = "xyz", in = "abc", repl = "Y";
        assertThat(tdfaReplaceFirst(p, in, repl, factory)).isEqualTo(re2jReplaceFirst(p, in, repl));
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void appendReplacementAndTail(EngineFactory factory) {
        String p = "(\\w+)", in = "hello world", repl = "[$1]";
        var re2jSb = new StringBuilder();
        var re2jM = com.google.re2j.Pattern.compile(p).matcher(in);
        while (re2jM.find()) re2jM.appendReplacement(re2jSb, repl);
        re2jM.appendTail(re2jSb);

        var tdfaSb = new StringBuilder();
        var tdfaM = io.github.jemmix.tdfa.re2j.Pattern.compile(p, 0, factory).matcher(in);
        while (tdfaM.find()) tdfaM.appendReplacement(tdfaSb, repl);
        tdfaM.appendTail(tdfaSb);

        assertThat(tdfaSb.toString()).isEqualTo(re2jSb.toString());
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void quoteReplacementStatic(EngineFactory factory) {
        assertThat(io.github.jemmix.tdfa.re2j.Matcher.quoteReplacement("$1\\2"))
                .isEqualTo(com.google.re2j.Matcher.quoteReplacement("$1\\2"));
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void replaceAllZeroDollar(EngineFactory factory) {
        String p = "(\\w+)", in = "test", repl = "<$0>";
        assertThat(tdfaReplaceAll(p, in, repl, factory)).isEqualTo(re2jReplaceAll(p, in, repl));
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void replaceAllEscapedBackslash(EngineFactory factory) {
        String p = "a", in = "aaa", repl = "\\\\";
        assertThat(tdfaReplaceAll(p, in, repl, factory)).isEqualTo(re2jReplaceAll(p, in, repl));
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void replaceAllNamedGroupRef(EngineFactory factory) {
        String p = "(?<word>\\w+)", in = "hello world", repl = "[${word}]";
        assertThat(tdfaReplaceAll(p, in, repl, factory)).isEqualTo(re2jReplaceAll(p, in, repl));
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void replaceAllMultiDigitGroupRef(EngineFactory factory) {
        String p = "(a)(b)(c)(d)(e)(f)(g)(h)(i)(j)";
        String in = "abcdefghij", repl = "$10";
        assertThat(tdfaReplaceAll(p, in, repl, factory)).isEqualTo(re2jReplaceAll(p, in, repl));
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void replaceAllDollarAtEnd(EngineFactory factory) {
        String p = "a", in = "banana", repl = "x$";
        assertThat(tdfaReplaceAll(p, in, repl, factory)).isEqualTo(re2jReplaceAll(p, in, repl));
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void replaceAllNonParticipatingGroup(EngineFactory factory) {
        String p = "(a)|(b)", in = "b", repl = "$1-$2";
        assertThat(tdfaReplaceAll(p, in, repl, factory)).isEqualTo(re2jReplaceAll(p, in, repl));
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void replaceAllGroupRefOutOfRange(EngineFactory factory) {
        assertThatThrownBy(() -> tdfaReplaceAll("(a)", "a", "$2", factory))
                .isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> re2jReplaceAll("(a)", "a", "$2"))
                .isInstanceOf(IndexOutOfBoundsException.class);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void stringBufferAppendReplacement(EngineFactory factory) {
        String p = "(\\w+)", in = "hello world", repl = "[$1]";
        var rSb = new StringBuffer();
        var rM = com.google.re2j.Pattern.compile(p).matcher(in);
        while (rM.find()) rM.appendReplacement(rSb, repl);
        rM.appendTail(rSb);

        var tSb = new StringBuffer();
        var tM = io.github.jemmix.tdfa.re2j.Pattern.compile(p, 0, factory).matcher(in);
        while (tM.find()) tM.appendReplacement(tSb, repl);
        tM.appendTail(tSb);

        assertThat(tSb.toString()).isEqualTo(rSb.toString());
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void replaceAllEmptyInput(EngineFactory factory) {
        assertThat(tdfaReplaceAll("a", "", "X", factory))
                .isEqualTo(re2jReplaceAll("a", "", "X"));
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void replaceAllNoMatchKeepsInput(EngineFactory factory) {
        assertThat(tdfaReplaceAll("xyz", "abc", "Y", factory))
                .isEqualTo(re2jReplaceAll("xyz", "abc", "Y"));
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void replaceAllDollarNonDigit(EngineFactory factory) {
        String p = "a", in = "banana", repl = "x$y";
        assertThat(tdfaReplaceAll(p, in, repl, factory)).isEqualTo(re2jReplaceAll(p, in, repl));
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void replaceAllBackslashZero(EngineFactory factory) {
        String p = "a", in = "banana", repl = "\\0";
        assertThat(tdfaReplaceAll(p, in, repl, factory)).isEqualTo(re2jReplaceAll(p, in, repl));
    }
}
