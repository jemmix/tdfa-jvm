package io.github.jemmix.tdfa.parity;

import io.github.jemmix.tdfa.core.compile.RegexEngineFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Replacement API parity: replaceAll, replaceFirst, appendReplacement,
 * appendTail, quoteReplacement with $N backreferences.
 *
 * <p>Jur twin below — the replacement grammar translates except the
 * lenient-dangling-{@code $} cells: the JDK THROWS on a trailing or
 * non-digit {@code $} where the re2j lineage passes it through literally
 * (documented divergence, pinned both ways).
 */
class ReplacementParityTest {

    private static String re2jReplaceAll(String pattern, String input, String repl) {
        com.google.re2j.Pattern p = com.google.re2j.Pattern.compile(pattern);
        com.google.re2j.Matcher m = p.matcher(input);
        return m.replaceAll(repl);
    }

    private static String tdfaReplaceAll(String pattern, String input, String repl, RegexEngineFactory factory) {
        io.github.jemmix.tdfa.Pattern p = io.github.jemmix.tdfa.Pattern.compile(pattern, 0, factory);
        io.github.jemmix.tdfa.core.engine.Matcher m = p.matcher(input);
        return m.replaceAll(repl);
    }

    private static String jurReplaceAll(String pattern, String input, String repl) {
        return java.util.regex.Pattern.compile(pattern).matcher(input).replaceAll(repl);
    }

    private static String facadeReplaceAll(String pattern, String input, String repl, RegexEngineFactory factory) {
        return io.github.jemmix.tdfa.Pattern.compile(pattern, 0, factory).matcher(input).replaceAll(repl);
    }

    private static String jurReplaceFirst(String pattern, String input, String repl) {
        return java.util.regex.Pattern.compile(pattern).matcher(input).replaceFirst(repl);
    }

    private static String facadeReplaceFirst(String pattern, String input, String repl, RegexEngineFactory factory) {
        return io.github.jemmix.tdfa.Pattern.compile(pattern, 0, factory).matcher(input).replaceFirst(repl);
    }

    private static String re2jReplaceFirst(String pattern, String input, String repl) {
        return com.google.re2j.Pattern.compile(pattern).matcher(input).replaceFirst(repl);
    }

    private static String tdfaReplaceFirst(String pattern, String input, String repl, RegexEngineFactory factory) {
        io.github.jemmix.tdfa.Pattern p = io.github.jemmix.tdfa.Pattern.compile(pattern, 0, factory);
        return p.matcher(input).replaceFirst(repl);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void replaceAllLiteral(RegexEngineFactory factory) {
        String p = "a", in = "banana", repl = "X";
        assertThat(tdfaReplaceAll(p, in, repl, factory)).isEqualTo(re2jReplaceAll(p, in, repl));
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void replaceAllWithGroup(RegexEngineFactory factory) {
        String p = "(\\w+)", in = "hello world", repl = "[$1]";
        assertThat(tdfaReplaceAll(p, in, repl, factory)).isEqualTo(re2jReplaceAll(p, in, repl));
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void replaceAllMultipleMatches(RegexEngineFactory factory) {
        String p = "\\d", in = "a1b2c3", repl = "#";
        assertThat(tdfaReplaceAll(p, in, repl, factory)).isEqualTo(re2jReplaceAll(p, in, repl));
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void replaceAllNoMatch(RegexEngineFactory factory) {
        String p = "xyz", in = "abc", repl = "Y";
        assertThat(tdfaReplaceAll(p, in, repl, factory)).isEqualTo(re2jReplaceAll(p, in, repl));
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void replaceAllWithDollarSign(RegexEngineFactory factory) {
        String p = "x", in = "axb", repl = "\\$";
        assertThat(tdfaReplaceAll(p, in, repl, factory)).isEqualTo(re2jReplaceAll(p, in, repl));
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void replaceFirstLiteral(RegexEngineFactory factory) {
        String p = "a", in = "banana", repl = "X";
        assertThat(tdfaReplaceFirst(p, in, repl, factory)).isEqualTo(re2jReplaceFirst(p, in, repl));
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void replaceFirstWithGroup(RegexEngineFactory factory) {
        String p = "(\\w)(\\w)", in = "abcd", repl = "$2$1";
        assertThat(tdfaReplaceFirst(p, in, repl, factory)).isEqualTo(re2jReplaceFirst(p, in, repl));
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void replaceFirstNoMatch(RegexEngineFactory factory) {
        String p = "xyz", in = "abc", repl = "Y";
        assertThat(tdfaReplaceFirst(p, in, repl, factory)).isEqualTo(re2jReplaceFirst(p, in, repl));
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void appendReplacementAndTail(RegexEngineFactory factory) {
        String p = "(\\w+)", in = "hello world", repl = "[$1]";
        var re2jSb = new StringBuilder();
        var re2jM = com.google.re2j.Pattern.compile(p).matcher(in);
        while (re2jM.find()) {
            re2jM.appendReplacement(re2jSb, repl);
        }
        re2jM.appendTail(re2jSb);

        var tdfaSb = new StringBuilder();
        var tdfaM = io.github.jemmix.tdfa.Pattern.compile(p, 0, factory).matcher(in);
        while (tdfaM.find()) {
            tdfaM.appendReplacement(tdfaSb, repl);
        }
        tdfaM.appendTail(tdfaSb);

        assertThat(tdfaSb.toString()).isEqualTo(re2jSb.toString());
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void quoteReplacementStatic(RegexEngineFactory factory) {
        assertThat(io.github.jemmix.tdfa.core.engine.Matcher.quoteReplacement("$1\\2"))
            .isEqualTo(com.google.re2j.Matcher.quoteReplacement("$1\\2"));
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void replaceAllZeroDollar(RegexEngineFactory factory) {
        String p = "(\\w+)", in = "test", repl = "<$0>";
        assertThat(tdfaReplaceAll(p, in, repl, factory)).isEqualTo(re2jReplaceAll(p, in, repl));
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void replaceAllEscapedBackslash(RegexEngineFactory factory) {
        String p = "a", in = "aaa", repl = "\\\\";
        assertThat(tdfaReplaceAll(p, in, repl, factory)).isEqualTo(re2jReplaceAll(p, in, repl));
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void replaceAllNamedGroupRef(RegexEngineFactory factory) {
        String p = "(?<word>\\w+)", in = "hello world", repl = "[${word}]";
        assertThat(tdfaReplaceAll(p, in, repl, factory)).isEqualTo(re2jReplaceAll(p, in, repl));
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void replaceAllMultiDigitGroupRef(RegexEngineFactory factory) {
        String p = "(a)(b)(c)(d)(e)(f)(g)(h)(i)(j)";
        String in = "abcdefghij", repl = "$10";
        assertThat(tdfaReplaceAll(p, in, repl, factory)).isEqualTo(re2jReplaceAll(p, in, repl));
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void replaceAllDollarAtEnd(RegexEngineFactory factory) {
        String p = "a", in = "banana", repl = "x$";
        assertThat(tdfaReplaceAll(p, in, repl, factory)).isEqualTo(re2jReplaceAll(p, in, repl));
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void replaceAllNonParticipatingGroup(RegexEngineFactory factory) {
        String p = "(a)|(b)", in = "b", repl = "$1-$2";
        assertThat(tdfaReplaceAll(p, in, repl, factory)).isEqualTo(re2jReplaceAll(p, in, repl));
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void replaceAllGroupRefOutOfRange(RegexEngineFactory factory) {
        assertThatThrownBy(() -> tdfaReplaceAll("(a)", "a", "$2", factory))
            .isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> re2jReplaceAll("(a)", "a", "$2")).isInstanceOf(IndexOutOfBoundsException.class);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void stringBufferAppendReplacement(RegexEngineFactory factory) {
        String p = "(\\w+)", in = "hello world", repl = "[$1]";
        var rSb = new StringBuffer();
        var rM = com.google.re2j.Pattern.compile(p).matcher(in);
        while (rM.find()) {
            rM.appendReplacement(rSb, repl);
        }
        rM.appendTail(rSb);

        var tSb = new StringBuffer();
        var tM = io.github.jemmix.tdfa.Pattern.compile(p, 0, factory).matcher(in);
        while (tM.find()) {
            tM.appendReplacement(tSb, repl);
        }
        tM.appendTail(tSb);

        assertThat(tSb.toString()).isEqualTo(rSb.toString());
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void replaceAllEmptyInput(RegexEngineFactory factory) {
        assertThat(tdfaReplaceAll("a", "", "X", factory)).isEqualTo(re2jReplaceAll("a", "", "X"));
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void replaceAllNoMatchKeepsInput(RegexEngineFactory factory) {
        assertThat(tdfaReplaceAll("xyz", "abc", "Y", factory)).isEqualTo(re2jReplaceAll("xyz", "abc", "Y"));
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void replaceAllDollarNonDigit(RegexEngineFactory factory) {
        String p = "a", in = "banana", repl = "x$y";
        assertThat(tdfaReplaceAll(p, in, repl, factory)).isEqualTo(re2jReplaceAll(p, in, repl));
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void replaceAllBackslashZero(RegexEngineFactory factory) {
        String p = "a", in = "banana", repl = "\\0";
        assertThat(tdfaReplaceAll(p, in, repl, factory)).isEqualTo(re2jReplaceAll(p, in, repl));
    }

    /** Jur twin: the shared replacement grammar against the live java.util.regex oracle. */
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.JurOracle#engineFactories")
    void jurTwin(RegexEngineFactory factory) {
        assertThat(facadeReplaceAll("a", "banana", "X", factory)).isEqualTo(jurReplaceAll("a", "banana", "X"));
        assertThat(facadeReplaceAll("(\\w+)", "hello world", "[$1]", factory))
            .isEqualTo(jurReplaceAll("(\\w+)", "hello world", "[$1]"));
        assertThat(facadeReplaceAll("\\d", "a1b2c3", "#", factory)).isEqualTo(jurReplaceAll("\\d", "a1b2c3", "#"));
        assertThat(facadeReplaceAll("xyz", "abc", "Y", factory)).isEqualTo(jurReplaceAll("xyz", "abc", "Y"));
        assertThat(facadeReplaceAll("x", "axb", "\\$", factory)).isEqualTo(jurReplaceAll("x", "axb", "\\$"));
        assertThat(facadeReplaceFirst("a", "banana", "X", factory)).isEqualTo(jurReplaceFirst("a", "banana", "X"));
        assertThat(facadeReplaceFirst("(\\w)(\\w)", "abcd", "$2$1", factory))
            .isEqualTo(jurReplaceFirst("(\\w)(\\w)", "abcd", "$2$1"));
        assertThat(facadeReplaceFirst("xyz", "abc", "Y", factory)).isEqualTo(jurReplaceFirst("xyz", "abc", "Y"));
        // appendReplacement/appendTail loop
        var jSb = new StringBuilder();
        var jM = java.util.regex.Pattern.compile("(\\w+)").matcher("hello world");
        while (jM.find()) {
            jM.appendReplacement(jSb, "[$1]");
        }
        jM.appendTail(jSb);
        var tSb = new StringBuilder();
        var tM = io.github.jemmix.tdfa.Pattern.compile("(\\w+)", 0, factory).matcher("hello world");
        while (tM.find()) {
            tM.appendReplacement(tSb, "[$1]");
        }
        tM.appendTail(tSb);
        assertThat(tSb.toString()).isEqualTo(jSb.toString());
        assertThat(io.github.jemmix.tdfa.core.engine.Matcher.quoteReplacement("$1\\2"))
            .isEqualTo(java.util.regex.Matcher.quoteReplacement("$1\\2"));
        assertThat(facadeReplaceAll("(\\w+)", "test", "<$0>", factory))
            .isEqualTo(jurReplaceAll("(\\w+)", "test", "<$0>"));
        assertThat(facadeReplaceAll("a", "aaa", "\\\\", factory)).isEqualTo(jurReplaceAll("a", "aaa", "\\\\"));
        assertThat(facadeReplaceAll("(?<word>\\w+)", "hello world", "[${word}]", factory))
            .isEqualTo(jurReplaceAll("(?<word>\\w+)", "hello world", "[${word}]"));
        assertThat(facadeReplaceAll("(a)(b)(c)(d)(e)(f)(g)(h)(i)(j)", "abcdefghij", "$10", factory))
            .isEqualTo(jurReplaceAll("(a)(b)(c)(d)(e)(f)(g)(h)(i)(j)", "abcdefghij", "$10"));
        assertThat(facadeReplaceAll("(a)|(b)", "b", "$1-$2", factory))
            .isEqualTo(jurReplaceAll("(a)|(b)", "b", "$1-$2"));
        assertThat(facadeReplaceAll("a", "", "X", factory)).isEqualTo(jurReplaceAll("a", "", "X"));
        assertThat(facadeReplaceAll("xyz", "abc", "Y", factory)).isEqualTo(jurReplaceAll("xyz", "abc", "Y"));
        assertThat(facadeReplaceAll("a", "banana", "\\0", factory)).isEqualTo(jurReplaceAll("a", "banana", "\\0"));
        // group-ref out of range: both throw
        assertThatThrownBy(() -> facadeReplaceAll("(a)", "a", "$2", factory))
            .isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> jurReplaceAll("(a)", "a", "$2")).isInstanceOf(IndexOutOfBoundsException.class);
        // the lenient dangling-$ divergence: we pass it through (re2j
        // lineage), the JDK throws IllegalArgumentException
        assertThat(facadeReplaceAll("a", "banana", "x$", factory)).isEqualTo("bx$nx$nx$");
        assertThatThrownBy(() -> jurReplaceAll("a", "banana", "x$")).isInstanceOf(IllegalArgumentException.class);
        assertThat(facadeReplaceAll("a", "banana", "x$y", factory)).isEqualTo("bx$ynx$ynx$y");
        assertThatThrownBy(() -> jurReplaceAll("a", "banana", "x$y")).isInstanceOf(IllegalArgumentException.class);
    }
}
