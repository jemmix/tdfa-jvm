package io.github.jemmix.tdfa.parity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pattern.split() parity: basic split, limit, trailing-empty handling.
 */
class SplitParityTest {

    private static void assertSplit(String pattern, String input) {
        String[] re2j = com.google.re2j.Pattern.compile(pattern).split(input);
        String[] tdfa = io.github.jemmix.tdfa.re2j.Pattern.compile(pattern).split(input);
        assertThat(tdfa).as("split \"%s\" on \"%s\"", pattern, input).isEqualTo(re2j);
    }

    private static void assertSplit(String pattern, String input, int limit) {
        String[] re2j = com.google.re2j.Pattern.compile(pattern).split(input, limit);
        String[] tdfa = io.github.jemmix.tdfa.re2j.Pattern.compile(pattern).split(input, limit);
        assertThat(tdfa).as("split \"%s\" on \"%s\" limit=%d", pattern, input, limit)
                .isEqualTo(re2j);
    }

    @Test void splitBasic() { assertSplit(",", "a,b,c"); }
    @Test void splitSingleChar() { assertSplit("\\s+", "a b c"); }
    @Test void splitNoMatch() { assertSplit("x", "abc"); }
    @Test void splitEmptyInput() { assertSplit(",", ""); }
    @Test void splitTrailingEmpty() { assertSplit(",", "a,b,"); }
    @Test void splitMultipleTrailingEmpty() { assertSplit(",", "a,b,,,"); }
    @Test void splitLeadingEmpty() { assertSplit(",", ",a"); }
    @Test void splitOnlyDelimiter() { assertSplit(",", ",,"); }
    @Test void splitWordPattern() { assertSplit("\\W+", "hello, world! foo"); }

    @Test void splitLimit2() { assertSplit(",", "a,b,c", 2); }
    @Test void splitLimit1() { assertSplit(",", "a,b,c", 1); }
    @Test void splitLimit0() { assertSplit(",", "a,b,c,", 0); }
    @Test void splitLimitLarge() { assertSplit(",", "a,b,c", 10); }
    @Test void splitLimitNegative() { assertSplit(",", "a,b,c,,,", -1); }

    // ---- Edge cases ----

    @Test void splitZeroWidthPattern() { assertSplit("a*", "aaabbb"); }
    @Test void splitEveryChar() { assertSplit(".", "abc"); }
    @Test void splitEmptyPattern() { assertSplit("", "abc"); }
    @Test void splitOnNullByte() { assertSplit("\\x00", "a\u0000b"); }
}
