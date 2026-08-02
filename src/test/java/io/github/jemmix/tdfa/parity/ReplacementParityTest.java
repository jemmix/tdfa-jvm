package io.github.jemmix.tdfa.parity;

import org.junit.jupiter.api.Test;

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

    private static String tdfaReplaceAll(String pattern, String input, String repl) {
        io.github.jemmix.tdfa.re2j.Pattern p = io.github.jemmix.tdfa.re2j.Pattern.compile(pattern);
        io.github.jemmix.tdfa.re2j.Matcher m = p.matcher(input);
        return m.replaceAll(repl);
    }

    private static String re2jReplaceFirst(String pattern, String input, String repl) {
        return com.google.re2j.Pattern.compile(pattern).matcher(input).replaceFirst(repl);
    }

    private static String tdfaReplaceFirst(String pattern, String input, String repl) {
        io.github.jemmix.tdfa.re2j.Pattern p = io.github.jemmix.tdfa.re2j.Pattern.compile(pattern);
        return p.matcher(input).replaceFirst(repl);
    }

    @Test void replaceAllLiteral() {
        String p = "a", in = "banana", repl = "X";
        assertThat(tdfaReplaceAll(p, in, repl)).isEqualTo(re2jReplaceAll(p, in, repl));
    }

    @Test void replaceAllWithGroup() {
        String p = "(\\w+)", in = "hello world", repl = "[$1]";
        assertThat(tdfaReplaceAll(p, in, repl)).isEqualTo(re2jReplaceAll(p, in, repl));
    }

    @Test void replaceAllMultipleMatches() {
        String p = "\\d", in = "a1b2c3", repl = "#";
        assertThat(tdfaReplaceAll(p, in, repl)).isEqualTo(re2jReplaceAll(p, in, repl));
    }

    @Test void replaceAllNoMatch() {
        String p = "xyz", in = "abc", repl = "Y";
        assertThat(tdfaReplaceAll(p, in, repl)).isEqualTo(re2jReplaceAll(p, in, repl));
    }

    @Test void replaceAllWithDollarSign() {
        String p = "x", in = "axb", repl = "\\$";
        assertThat(tdfaReplaceAll(p, in, repl)).isEqualTo(re2jReplaceAll(p, in, repl));
    }

    @Test void replaceFirstLiteral() {
        String p = "a", in = "banana", repl = "X";
        assertThat(tdfaReplaceFirst(p, in, repl)).isEqualTo(re2jReplaceFirst(p, in, repl));
    }

    @Test void replaceFirstWithGroup() {
        String p = "(\\w)(\\w)", in = "abcd", repl = "$2$1";
        assertThat(tdfaReplaceFirst(p, in, repl)).isEqualTo(re2jReplaceFirst(p, in, repl));
    }

    @Test void replaceFirstNoMatch() {
        String p = "xyz", in = "abc", repl = "Y";
        assertThat(tdfaReplaceFirst(p, in, repl)).isEqualTo(re2jReplaceFirst(p, in, repl));
    }

    @Test void appendReplacementAndTail() {
        String p = "(\\w+)", in = "hello world", repl = "[$1]";
        var re2jSb = new StringBuilder();
        var re2jM = com.google.re2j.Pattern.compile(p).matcher(in);
        while (re2jM.find()) re2jM.appendReplacement(re2jSb, repl);
        re2jM.appendTail(re2jSb);

        var tdfaSb = new StringBuilder();
        var tdfaM = io.github.jemmix.tdfa.re2j.Pattern.compile(p).matcher(in);
        while (tdfaM.find()) tdfaM.appendReplacement(tdfaSb, repl);
        tdfaM.appendTail(tdfaSb);

        assertThat(tdfaSb.toString()).isEqualTo(re2jSb.toString());
    }

    @Test void quoteReplacementStatic() {
        assertThat(io.github.jemmix.tdfa.re2j.Matcher.quoteReplacement("$1\\2"))
                .isEqualTo(com.google.re2j.Matcher.quoteReplacement("$1\\2"));
    }

    @Test void replaceAllZeroDollar() {
        String p = "(\\w+)", in = "test", repl = "<$0>";
        assertThat(tdfaReplaceAll(p, in, repl)).isEqualTo(re2jReplaceAll(p, in, repl));
    }

    @Test void replaceAllEscapedBackslash() {
        String p = "a", in = "aaa", repl = "\\\\";
        assertThat(tdfaReplaceAll(p, in, repl)).isEqualTo(re2jReplaceAll(p, in, repl));
    }
}
