package io.github.jemmix.tdfa.parity;

import org.junit.jupiter.api.Test;

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

    private static io.github.jemmix.tdfa.re2j.Matcher tdfaM(String p, String in) {
        return io.github.jemmix.tdfa.re2j.Pattern.compile(p).matcher(in);
    }

    @Test void matchesTrue() {
        assertThat(tdfaM("abc", "abc").matches()).isEqualTo(re2jM("abc", "abc").matches());
    }

    @Test void matchesFalse() {
        assertThat(tdfaM("abc", "abcd").matches()).isEqualTo(re2jM("abc", "abcd").matches());
    }

    @Test void matchesPartial() {
        assertThat(tdfaM("abc", "xabc").matches()).isEqualTo(re2jM("abc", "xabc").matches());
    }

    @Test void lookingAtTrue() {
        assertThat(tdfaM("abc", "abcdef").lookingAt())
                .isEqualTo(re2jM("abc", "abcdef").lookingAt());
    }

    @Test void lookingAtFalse() {
        assertThat(tdfaM("abc", "xabc").lookingAt())
                .isEqualTo(re2jM("abc", "xabc").lookingAt());
    }

    @Test void findFirst() {
        var r = re2jM("\\d+", "abc123def456");
        var t = tdfaM("\\d+", "abc123def456");
        assertThat(t.find()).isEqualTo(r.find());
        assertThat(t.start()).isEqualTo(r.start());
        assertThat(t.end()).isEqualTo(r.end());
    }

    @Test void findIterate() {
        var r = re2jM("\\d+", "a1b2c3");
        var t = tdfaM("\\d+", "a1b2c3");
        while (r.find() && t.find()) {
            assertThat(t.group()).as("group").isEqualTo(r.group());
            assertThat(t.start()).as("start").isEqualTo(r.start());
        }
        assertThat(r.find()).isEqualTo(t.find());
    }

    @Test void findFromPosition() {
        var r = re2jM("\\d+", "12ab34");
        var t = tdfaM("\\d+", "12ab34");
        r.find(4);
        t.find(4);
        assertThat(t.group()).isEqualTo(r.group());
    }

    @Test void groupExtraction() {
        var r = re2jM("(\\w+)@(\\w+)", "user@host");
        var t = tdfaM("(\\w+)@(\\w+)", "user@host");
        r.find(); t.find();
        assertThat(t.group(1)).isEqualTo(r.group(1));
        assertThat(t.group(2)).isEqualTo(r.group(2));
    }

    @Test void groupCount() {
        assertThat(tdfaM("(a)(b)(c)", "abc").groupCount())
                .isEqualTo(re2jM("(a)(b)(c)", "abc").groupCount());
    }

    @Test void resetClearsState() {
        var t = tdfaM("\\d", "a1b2");
        t.find();
        t.reset();
        var r = re2jM("\\d", "a1b2");
        assertThat(t.find()).isEqualTo(r.find());
        assertThat(t.start()).isEqualTo(r.start());
    }

    @Test void resetWithNewInput() {
        var t = tdfaM("\\d", "a1b2");
        t.find();
        t.reset("x3y4");
        var r = re2jM("\\d", "x3y4");
        assertThat(t.find()).isEqualTo(r.find());
        assertThat(t.group()).isEqualTo(r.group());
    }

    @Test void zeroWidthFindAdvance() {
        var r = re2jM("a*", "aaabbb");
        var t = tdfaM("a*", "aaabbb");
        java.util.List<String> rMatches = new java.util.ArrayList<>();
        java.util.List<String> tMatches = new java.util.ArrayList<>();
        while (r.find()) rMatches.add(r.group());
        while (t.find()) tMatches.add(t.group());
        assertThat(tMatches).isEqualTo(rMatches);
    }

    @Test void findNoMatch() {
        assertThat(tdfaM("xyz", "abc").find()).isEqualTo(re2jM("xyz", "abc").find());
    }

    @Test void optionalGroupNull() {
        var r = re2jM("a(b)?c", "ac");
        var t = tdfaM("a(b)?c", "ac");
        r.find(); t.find();
        assertThat(t.group(1)).isEqualTo(r.group(1));
    }

    @Test void optionalGroupPresent() {
        var r = re2jM("a(b)?c", "abc");
        var t = tdfaM("a(b)?c", "abc");
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

    @Test void instanceMatchesTrue() {
        assertThat(io.github.jemmix.tdfa.re2j.Pattern.compile("abc").matches("abc"))
                .isEqualTo(com.google.re2j.Pattern.compile("abc").matches("abc"));
    }

    @Test void patternAccessor() {
        assertThat(io.github.jemmix.tdfa.re2j.Pattern.compile("a(b)c").pattern())
                .isEqualTo(com.google.re2j.Pattern.compile("a(b)c").pattern());
    }

    // ---- runtime exceptions ----

    @Test void findNegativeStart() {
        assertThatThrownBy(() -> tdfaM("a", "abc").find(-1))
                .isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> re2jM("a", "abc").find(-1))
                .isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test void findTooLargeStart() {
        assertThatThrownBy(() -> tdfaM("a", "abc").find(10))
                .isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> re2jM("a", "abc").find(10))
                .isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test void startBeforeMatch() {
        assertThatThrownBy(() -> tdfaM("a", "abc").start())
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> re2jM("a", "abc").start())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test void groupBeforeMatch() {
        assertThatThrownBy(() -> tdfaM("a", "abc").group())
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> re2jM("a", "abc").group())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test void groupIndexTooHigh() {
        assertThatThrownBy(() -> { var m = tdfaM("(a)", "a"); m.find(); m.group(99); })
                .isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> { var m = re2jM("(a)", "a"); m.find(); m.group(99); })
                .isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test void groupUnknownName() {
        assertThatThrownBy(() -> { var m = tdfaM("(?<x>a)", "a"); m.find(); m.group("y"); })
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> { var m = re2jM("(?<x>a)", "a"); m.find(); m.group("y"); })
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- stateful interactions ----

    @Test void lookingAtThenFind() {
        var r = re2jM("\\w+", "hello world");
        var t = tdfaM("\\w+", "hello world");
        r.lookingAt(); t.lookingAt();
        assertThat(t.group()).isEqualTo(r.group());
        r.find(); t.find();
        assertThat(t.group()).isEqualTo(r.group());
    }

    @Test void matchFailThenFind() {
        var r = re2jM("^xyz$", "abc xyz");
        var t = tdfaM("^xyz$", "abc xyz");
        assertThat(t.matches()).isEqualTo(r.matches());
        assertThat(t.find()).isEqualTo(r.find());
        if (r.find() || t.find()) {
            assertThat(t.group()).isEqualTo(r.group());
        }
    }

    @Test void findFromThenFind() {
        var r = re2jM("\\w", "abcd");
        var t = tdfaM("\\w", "abcd");
        r.find(2); t.find(2);
        assertThat(t.group()).isEqualTo(r.group());
        r.find(); t.find();
        assertThat(t.group()).isEqualTo(r.group());
    }

    // ---- edge cases ----

    @Test void tenGroups() {
        String pat = "(a)(b)(c)(d)(e)(f)(g)(h)(i)(j)";
        var r = re2jM(pat, "abcdefghij");
        var t = tdfaM(pat, "abcdefghij");
        r.find(); t.find();
        for (int i = 0; i <= 10; i++)
            assertThat(t.group(i)).as("group " + i).isEqualTo(r.group(i));
    }

    @Test void emptyInputFind() {
        Re2jOracle.assertSameFind("a*", "");
    }

    @Test void emptyPattern() {
        Re2jOracle.assertSameFind("", "abc");
    }

    @Test void nullByteInInput() {
        Re2jOracle.assertSameFind("a.b", "a\u0000b");
    }

    @Test void resetTwice() {
        var t = tdfaM("\\d", "a1b2");
        t.find();
        t.reset();
        t.reset();
        var r = re2jM("\\d", "a1b2");
        assertThat(t.find()).isEqualTo(r.find());
        assertThat(t.start()).isEqualTo(r.start());
    }

    // ---- find() boundary ----

    @Test void findAtInputLength() {
        var r = re2jM("a", "xyz");
        var t = tdfaM("a", "xyz");
        assertThat(t.find(3)).isEqualTo(r.find(3));
    }

    @Test void findPastInputLength() {
        assertThatThrownBy(() -> tdfaM("a", "xyz").find(4))
                .isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> re2jM("a", "xyz").find(4))
                .isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test void instanceMatchesFalse() {
        assertThat(io.github.jemmix.tdfa.re2j.Pattern.compile("abc").matches("abcd"))
                .isEqualTo(com.google.re2j.Pattern.compile("abc").matches("abcd"));
    }

    @Test void matchesThenGroup() {
        var r = re2jM("(a)(b)(c)", "abc");
        var t = tdfaM("(a)(b)(c)", "abc");
        r.matches(); t.matches();
        assertThat(t.group(0)).isEqualTo(r.group(0));
        assertThat(t.group(1)).isEqualTo(r.group(1));
        assertThat(t.group(3)).isEqualTo(r.group(3));
    }

    @Test void appendReplFailedFindTail() {
        var rSb = new StringBuilder();
        var rM = re2jM("xyz", "abc");
        rM.find();
        rM.appendTail(rSb);

        var tSb = new StringBuilder();
        var tM = tdfaM("xyz", "abc");
        tM.find();
        tM.appendTail(tSb);

        assertThat(tSb.toString()).isEqualTo(rSb.toString());
    }

    @Test void emptyInputMatches() {
        assertThat(tdfaM("a*", "").matches())
                .isEqualTo(re2jM("a*", "").matches());
    }

    @Test void emptyInputLookingAt() {
        assertThat(tdfaM("a*", "").lookingAt())
                .isEqualTo(re2jM("a*", "").lookingAt());
    }

    @Test void deeplyNestedGroups() {
        StringBuilder pat = new StringBuilder("a");
        for (int i = 0; i < 50; i++) pat.insert(0, "(").append(")");
        String p = pat.toString();
        var r = re2jM(p, "a");
        var t = tdfaM(p, "a");
        r.find(); t.find();
        assertThat(t.group(0)).isEqualTo(r.group(0));
        assertThat(t.groupCount()).isEqualTo(r.groupCount());
    }
}
