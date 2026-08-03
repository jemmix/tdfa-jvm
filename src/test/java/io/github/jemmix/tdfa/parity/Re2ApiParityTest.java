package io.github.jemmix.tdfa.parity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RE2 Go-style API: our shim exposes RE2 as public (upstream is package-private).
 * Tests our RE2 API directly since no upstream oracle is accessible.
 */
class Re2ApiParityTest {

    @Test void findSubmatchIndexBasic() {
        var t = io.github.jemmix.tdfa.re2j.RE2.compile("(a)(b)");
        assertThat(t.findSubmatchIndex("ab")).isEqualTo(new int[]{0, 2, 0, 1, 1, 2});
    }

    @Test void findSubmatchIndexNoMatch() {
        var t = io.github.jemmix.tdfa.re2j.RE2.compile("xyz");
        assertThat(t.findSubmatchIndex("abc")).isNull();
    }

    @Test void matchTrue() {
        var t = io.github.jemmix.tdfa.re2j.RE2.compile("a+");
        assertThat(t.match("aaa")).isTrue();
    }

    @Test void matchFalse() {
        var t = io.github.jemmix.tdfa.re2j.RE2.compile("xyz");
        assertThat(t.match("abc")).isFalse();
    }

    @Test void findAllBasic() {
        var t = io.github.jemmix.tdfa.re2j.RE2.compile("\\d+");
        assertThat(t.findAll("a1b2c3", 100)).containsExactly("1", "2", "3");
    }

    @Test void findAllCapped() {
        var t = io.github.jemmix.tdfa.re2j.RE2.compile("\\d");
        assertThat(t.findAll("a1b2c3d4", 2)).hasSize(2);
    }

    @Test void findAllSubmatchBasic() {
        var t = io.github.jemmix.tdfa.re2j.RE2.compile("(a)(b)");
        var result = t.findAllSubmatch("ab ab", 100);
        assertThat(result).hasSize(2);
        assertThat(result.get(0)).containsExactly("ab", "a", "b");
    }

    @Test void findAllSubmatchNoMatch() {
        var t = io.github.jemmix.tdfa.re2j.RE2.compile("xyz");
        assertThat(t.findAllSubmatch("abc", 100)).isEmpty();
    }

    @Test void compilePOSIXvsPERL() {
        var tPosix = io.github.jemmix.tdfa.re2j.RE2.compilePOSIX("(a|ab)");
        tPosix.longest = true;
        int[] posixResult = tPosix.findSubmatchIndex("ab");
        var tPerl = io.github.jemmix.tdfa.re2j.RE2.compile("(a|ab)");
        int[] perlResult = tPerl.findSubmatchIndex("ab");
        // POSIX should match "ab" (longest), PERL should match "a" (first)
        assertThat(posixResult[1] - posixResult[0]).isGreaterThanOrEqualTo(perlResult[1] - perlResult[0]);
    }

    @Test void longestMutation() {
        var t = io.github.jemmix.tdfa.re2j.RE2.compile("(a|ab)");
        t.longest = false;
        int[] perlResult = t.findSubmatchIndex("ab");
        t.longest = true;
        int[] posixResult = t.findSubmatchIndex("ab");
        assertThat(posixResult[1] - posixResult[0])
                .isGreaterThanOrEqualTo(perlResult[1] - perlResult[0]);
    }

    @Test void quoteMetaBasic() {
        assertThat(io.github.jemmix.tdfa.re2j.RE2.quoteMeta("a.b*c+d"))
                .isEqualTo("a\\.b\\*c\\+d");
    }

    @Test void quoteMetaAllSpecials() {
        String s = "\\.+*?()|[]{}^$";
        String quoted = io.github.jemmix.tdfa.re2j.RE2.quoteMeta(s);
        // Every metacharacter should be preceded by a backslash
        assertThat(io.github.jemmix.tdfa.re2j.Pattern.compile(quoted).matcher(s).matches()).isTrue();
    }

    @Test void toStringParity() {
        var t = io.github.jemmix.tdfa.re2j.RE2.compile("abc");
        assertThat(t.toString()).isEqualTo("abc");
    }
}
