package io.github.jemmix.tdfa.parity;

import org.junit.jupiter.api.Test;

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
}
