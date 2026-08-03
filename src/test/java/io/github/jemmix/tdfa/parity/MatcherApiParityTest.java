package io.github.jemmix.tdfa.parity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import io.github.jemmix.tdfa.EngineFactory;

import static org.assertj.core.api.Assertions.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Matcher API parity: matches(), lookingAt(), find(), find(int), group(int),
 * reset(), stateful iteration, zero-width advance.
 */
class MatcherApiParityTest {

    private static com.google.re2j.Matcher re2jM(String p, String in) {
        return com.google.re2j.Pattern.compile(p).matcher(in);
    }

    private static io.github.jemmix.tdfa.re2j.Matcher tdfaM(String p, String in, EngineFactory factory) {
        return io.github.jemmix.tdfa.re2j.Pattern.compile(p, 0, factory).matcher(in);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void matchesTrue(EngineFactory factory) {
        assertThat(tdfaM("abc", "abc", factory).matches()).isEqualTo(re2jM("abc", "abc").matches());
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void matchesFalse(EngineFactory factory) {
        assertThat(tdfaM("abc", "abcd", factory).matches()).isEqualTo(re2jM("abc", "abcd").matches());
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void matchesPartial(EngineFactory factory) {
        assertThat(tdfaM("abc", "xabc", factory).matches()).isEqualTo(re2jM("abc", "xabc").matches());
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void lookingAtTrue(EngineFactory factory) {
        assertThat(tdfaM("abc", "abcdef", factory).lookingAt())
                .isEqualTo(re2jM("abc", "abcdef").lookingAt());
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void lookingAtFalse(EngineFactory factory) {
        assertThat(tdfaM("abc", "xabc", factory).lookingAt())
                .isEqualTo(re2jM("abc", "xabc").lookingAt());
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void findFirst(EngineFactory factory) {
        var r = re2jM("\\d+", "abc123def456");
        var t = tdfaM("\\d+", "abc123def456", factory);
        assertThat(t.find()).isEqualTo(r.find());
        assertThat(t.start()).isEqualTo(r.start());
        assertThat(t.end()).isEqualTo(r.end());
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void findIterate(EngineFactory factory) {
        var r = re2jM("\\d+", "a1b2c3");
        var t = tdfaM("\\d+", "a1b2c3", factory);
        while (r.find() && t.find()) {
            assertThat(t.group()).as("group").isEqualTo(r.group());
            assertThat(t.start()).as("start").isEqualTo(r.start());
        }
        assertThat(r.find()).isEqualTo(t.find());
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void findFromPosition(EngineFactory factory) {
        var r = re2jM("\\d+", "12ab34");
        var t = tdfaM("\\d+", "12ab34", factory);
        r.find(4);
        t.find(4);
        assertThat(t.group()).isEqualTo(r.group());
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void groupExtraction(EngineFactory factory) {
        var r = re2jM("(\\w+)@(\\w+)", "user@host");
        var t = tdfaM("(\\w+)@(\\w+)", "user@host", factory);
        r.find(); t.find();
        assertThat(t.group(1)).isEqualTo(r.group(1));
        assertThat(t.group(2)).isEqualTo(r.group(2));
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void groupCount(EngineFactory factory) {
        assertThat(tdfaM("(a)(b)(c)", "abc", factory).groupCount())
                .isEqualTo(re2jM("(a)(b)(c)", "abc").groupCount());
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void resetClearsState(EngineFactory factory) {
        var t = tdfaM("\\d", "a1b2", factory);
        t.find();
        t.reset();
        var r = re2jM("\\d", "a1b2");
        assertThat(t.find()).isEqualTo(r.find());
        assertThat(t.start()).isEqualTo(r.start());
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void resetWithNewInput(EngineFactory factory) {
        var t = tdfaM("\\d", "a1b2", factory);
        t.find();
        t.reset("x3y4");
        var r = re2jM("\\d", "x3y4");
        assertThat(t.find()).isEqualTo(r.find());
        assertThat(t.group()).isEqualTo(r.group());
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void zeroWidthFindAdvance(EngineFactory factory) {
        var r = re2jM("a*", "aaabbb");
        var t = tdfaM("a*", "aaabbb", factory);
        java.util.List<String> rMatches = new java.util.ArrayList<>();
        java.util.List<String> tMatches = new java.util.ArrayList<>();
        while (r.find()) rMatches.add(r.group());
        while (t.find()) tMatches.add(t.group());
        assertThat(tMatches).isEqualTo(rMatches);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void findNoMatch(EngineFactory factory) {
        assertThat(tdfaM("xyz", "abc", factory).find()).isEqualTo(re2jM("xyz", "abc").find());
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void optionalGroupNull(EngineFactory factory) {
        var r = re2jM("a(b)?c", "ac");
        var t = tdfaM("a(b)?c", "ac", factory);
        r.find(); t.find();
        assertThat(t.group(1)).isEqualTo(r.group(1));
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void optionalGroupPresent(EngineFactory factory) {
        var r = re2jM("a(b)?c", "abc");
        var t = tdfaM("a(b)?c", "abc", factory);
        r.find(); t.find();
        assertThat(t.group(1)).isEqualTo(r.group(1));
    }

    // ---- static / instance convenience ----

    @Test void staticMatchesTrue() {
        assertThat(io.github.jemmix.tdfa.re2j.Pattern.matches("abc", "abc"))
                .isEqualTo(com.google.re2j.Pattern.matches("abc", "abc"));
    }

    @Test void staticMatchesFalse() {
        assertThat(io.github.jemmix.tdfa.re2j.Pattern.matches("abc", "abcd"))
                .isEqualTo(com.google.re2j.Pattern.matches("abc", "abcd"));
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void instanceMatchesTrue(EngineFactory factory) {
        assertThat(io.github.jemmix.tdfa.re2j.Pattern.compile("abc", 0, factory).matches("abc"))
                .isEqualTo(com.google.re2j.Pattern.compile("abc").matches("abc"));
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void patternAccessor(EngineFactory factory) {
        assertThat(io.github.jemmix.tdfa.re2j.Pattern.compile("a(b)c", 0, factory).pattern())
                .isEqualTo(com.google.re2j.Pattern.compile("a(b)c").pattern());
    }

    // ---- runtime exceptions ----

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void findNegativeStart(EngineFactory factory) {
        assertThatThrownBy(() -> tdfaM("a", "abc", factory).find(-1))
                .isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> re2jM("a", "abc").find(-1))
                .isInstanceOf(IndexOutOfBoundsException.class);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void findTooLargeStart(EngineFactory factory) {
        assertThatThrownBy(() -> tdfaM("a", "abc", factory).find(10))
                .isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> re2jM("a", "abc").find(10))
                .isInstanceOf(IndexOutOfBoundsException.class);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void startBeforeMatch(EngineFactory factory) {
        assertThatThrownBy(() -> tdfaM("a", "abc", factory).start())
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> re2jM("a", "abc").start())
                .isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void groupBeforeMatch(EngineFactory factory) {
        assertThatThrownBy(() -> tdfaM("a", "abc", factory).group())
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> re2jM("a", "abc").group())
                .isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void groupIndexTooHigh(EngineFactory factory) {
        assertThatThrownBy(() -> { var m = tdfaM("(a)", "a", factory); m.find(); m.group(99); })
                .isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> { var m = re2jM("(a)", "a"); m.find(); m.group(99); })
                .isInstanceOf(IndexOutOfBoundsException.class);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void groupUnknownName(EngineFactory factory) {
        assertThatThrownBy(() -> { var m = tdfaM("(?<x>a)", "a", factory); m.find(); m.group("y"); })
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> { var m = re2jM("(?<x>a)", "a"); m.find(); m.group("y"); })
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- stateful interactions ----

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void lookingAtThenFind(EngineFactory factory) {
        var r = re2jM("\\w+", "hello world");
        var t = tdfaM("\\w+", "hello world", factory);
        r.lookingAt(); t.lookingAt();
        assertThat(t.group()).isEqualTo(r.group());
        r.find(); t.find();
        assertThat(t.group()).isEqualTo(r.group());
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void matchFailThenFind(EngineFactory factory) {
        var r = re2jM("^xyz$", "abc xyz");
        var t = tdfaM("^xyz$", "abc xyz", factory);
        assertThat(t.matches()).isEqualTo(r.matches());
        assertThat(t.find()).isEqualTo(r.find());
        if (r.find() || t.find()) {
            assertThat(t.group()).isEqualTo(r.group());
        }
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void findFromThenFind(EngineFactory factory) {
        var r = re2jM("\\w", "abcd");
        var t = tdfaM("\\w", "abcd", factory);
        r.find(2); t.find(2);
        assertThat(t.group()).isEqualTo(r.group());
        r.find(); t.find();
        assertThat(t.group()).isEqualTo(r.group());
    }

    // ---- edge cases ----

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void tenGroups(EngineFactory factory) {
        String pat = "(a)(b)(c)(d)(e)(f)(g)(h)(i)(j)";
        var r = re2jM(pat, "abcdefghij");
        var t = tdfaM(pat, "abcdefghij", factory);
        r.find(); t.find();
        for (int i = 0; i <= 10; i++)
            assertThat(t.group(i)).as("group " + i).isEqualTo(r.group(i));
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void emptyInputFind(EngineFactory factory) {
        Re2jOracle.assertSameFind("a*", "", factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void emptyPattern(EngineFactory factory) {
        Re2jOracle.assertSameFind("", "abc", factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void nullByteInInput(EngineFactory factory) {
        Re2jOracle.assertSameFind("a.b", "a\u0000b", factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void resetTwice(EngineFactory factory) {
        var t = tdfaM("\\d", "a1b2", factory);
        t.find();
        t.reset();
        t.reset();
        var r = re2jM("\\d", "a1b2");
        assertThat(t.find()).isEqualTo(r.find());
        assertThat(t.start()).isEqualTo(r.start());
    }

    // ---- find() boundary ----

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void findAtInputLength(EngineFactory factory) {
        var r = re2jM("a", "xyz");
        var t = tdfaM("a", "xyz", factory);
        assertThat(t.find(3)).isEqualTo(r.find(3));
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void findPastInputLength(EngineFactory factory) {
        assertThatThrownBy(() -> tdfaM("a", "xyz", factory).find(4))
                .isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> re2jM("a", "xyz").find(4))
                .isInstanceOf(IndexOutOfBoundsException.class);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void instanceMatchesFalse(EngineFactory factory) {
        assertThat(io.github.jemmix.tdfa.re2j.Pattern.compile("abc", 0, factory).matches("abcd"))
                .isEqualTo(com.google.re2j.Pattern.compile("abc").matches("abcd"));
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void matchesThenGroup(EngineFactory factory) {
        var r = re2jM("(a)(b)(c)", "abc");
        var t = tdfaM("(a)(b)(c)", "abc", factory);
        r.matches(); t.matches();
        assertThat(t.group(0)).isEqualTo(r.group(0));
        assertThat(t.group(1)).isEqualTo(r.group(1));
        assertThat(t.group(3)).isEqualTo(r.group(3));
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void appendReplFailedFindTail(EngineFactory factory) {
        var rSb = new StringBuilder();
        var rM = re2jM("xyz", "abc");
        rM.find();
        rM.appendTail(rSb);

        var tSb = new StringBuilder();
        var tM = tdfaM("xyz", "abc", factory);
        tM.find();
        tM.appendTail(tSb);

        assertThat(tSb.toString()).isEqualTo(rSb.toString());
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void emptyInputMatches(EngineFactory factory) {
        assertThat(tdfaM("a*", "", factory).matches())
                .isEqualTo(re2jM("a*", "").matches());
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void emptyInputLookingAt(EngineFactory factory) {
        assertThat(tdfaM("a*", "", factory).lookingAt())
                .isEqualTo(re2jM("a*", "").lookingAt());
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void deeplyNestedGroups(EngineFactory factory) {
        StringBuilder pat = new StringBuilder("a");
        for (int i = 0; i < 50; i++) pat.insert(0, "(").append(")");
        String p = pat.toString();
        var r = re2jM(p, "a");
        var t = tdfaM(p, "a", factory);
        r.find(); t.find();
        assertThat(t.group(0)).isEqualTo(r.group(0));
        assertThat(t.groupCount()).isEqualTo(r.groupCount());
    }

    // ---- matches() with alternation correctness ----

    @EnabledIfSystemProperty(named = "tdfa.pending", matches = "true") // PENDING: matches() anchored-both extract returns wrong groups")
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void matchesWithAlternationGroups(EngineFactory factory) {
        var r = re2jM("(a|ab)", "ab");
        var t = tdfaM("(a|ab)", "ab", factory);
        assertThat(t.matches()).isEqualTo(r.matches());
        assertThat(t.group(0)).isEqualTo(r.group(0));
        assertThat(t.group(1)).isEqualTo(r.group(1));
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void matchesThenFind(EngineFactory factory) {
        var r = re2jM("\\w+", "ab");
        var t = tdfaM("\\w+", "ab", factory);
        assertThat(t.matches()).isEqualTo(r.matches());
        assertThat(t.group()).isEqualTo(r.group());
        assertThat(t.group()).isEqualTo("ab");
        r.reset(); t.reset();
        assertThat(t.find()).isEqualTo(r.find());
        assertThat(t.group()).isEqualTo(r.group());
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void lookingAtWithAlternation(EngineFactory factory) {
        var r = re2jM("(a|ab)", "ab");
        var t = tdfaM("(a|ab)", "ab", factory);
        r.lookingAt(); t.lookingAt();
        assertThat(t.group(0)).isEqualTo(r.group(0));
        assertThat(t.group(1)).isEqualTo(r.group(1));
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void groupNegativeIndex(EngineFactory factory) {
        assertThatThrownBy(() -> { var m = tdfaM("(a)", "a", factory); m.find(); m.group(-1); })
                .isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> { var m = re2jM("(a)", "a"); m.find(); m.group(-1); })
                .isInstanceOf(IndexOutOfBoundsException.class);
    }
}
