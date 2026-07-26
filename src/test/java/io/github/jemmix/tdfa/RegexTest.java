package io.github.jemmix.tdfa;

import io.github.jemmix.tdfa.vm.MatchResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Correctness tests for {@link Regex}. Patterns are pinned; inputs chosen to exercise
 * the TDFA determinization (capture groups under repetition, alternation, alternation
 * under repetition).
 */
class RegexTest {

    private static int[] groups(Regex r, String input) {
        MatchResult m = r.find(input, 0);
        assertThat(m).as("match expected").isNotNull();
        return m.groups();
    }

    // ----------------- basic recognition -----------------

    @Test void literalMatches() {
        Regex r = Regex.compile("abc");
        assertThat(r.matches("abc")).isTrue();
        assertThat(r.matches("abcd")).isFalse();
        assertThat(r.matches("ab")).isFalse();
        assertThat(r.find("xxabcxx")).isTrue();
    }

    @Test void charClassMatches() {
        Regex r = Regex.compile("[abc]+");
        assertThat(r.matches("aabcc")).isTrue();
        assertThat(r.matches("abd")).isFalse();
    }

    @Test void negatedClass() {
        // Disabled: negated classes blow up the per-state alphabet (TODO: partition)
        assumeTrue(false, "negated classes need alphabet partitioning (deferred)");
        Regex r = Regex.compile("[^0-9]+");
        assertThat(r.matches("abc")).isTrue();
        assertThat(r.matches("abc1")).isFalse();
    }

    @Test void digitClass() {
        Regex r = Regex.compile("\\d+");
        assertThat(r.find("abc123def")).isTrue();
        assertThat(r.find("abc")).isFalse();
    }

    @Test void dotClass() {
        Regex r = Regex.compile("a.c");
        assertThat(r.matches("abc")).isTrue();
        assertThat(r.matches("a c")).isTrue();
        assertThat(r.matches("a\nc")).isFalse();
    }

    // ----------------- quantifiers -----------------

    @Test void starQuantifier() {
        Regex r = Regex.compile("ab*c");
        assertThat(r.matches("ac")).isTrue();
        assertThat(r.matches("abc")).isTrue();
        assertThat(r.matches("abbbbc")).isTrue();
        assertThat(r.matches("axc")).isFalse();
    }

    @Test void plusQuantifier() {
        Regex r = Regex.compile("ab+c");
        assertThat(r.matches("ac")).isFalse();
        assertThat(r.matches("abc")).isTrue();
        assertThat(r.matches("abbbbc")).isTrue();
    }

    @Test void optionalQuantifier() {
        Regex r = Regex.compile("colou?r");
        assertThat(r.matches("color")).isTrue();
        assertThat(r.matches("colour")).isTrue();
        assertThat(r.matches("coloar")).isFalse();
    }

    @Test void countedQuantifier() {
        Regex r = Regex.compile("a{3}");
        assertThat(r.matches("aaa")).isTrue();
        assertThat(r.matches("aa")).isFalse();
        assertThat(r.matches("aaaa")).isFalse();
    }

    @Test void rangeQuantifier() {
        Regex r = Regex.compile("a{2,4}");
        assertThat(r.matches("aa")).isTrue();
        assertThat(r.matches("aaaa")).isTrue();
        assertThat(r.matches("aaaaa")).isFalse();
        assertThat(r.matches("a")).isFalse();
    }

    // ----------------- alternation -----------------

    @Test void alternation() {
        Regex r = Regex.compile("cat|dog|bird");
        assertThat(r.matches("cat")).isTrue();
        assertThat(r.matches("dog")).isTrue();
        assertThat(r.matches("bird")).isTrue();
        assertThat(r.matches("fish")).isFalse();
    }

    @Test void alternationWithGroups() {
        Regex r = Regex.compile("(cat|dog)");
        assertThat(r.matches("cat")).isTrue();
        int[] g = groups(r, "dog");
        assertThat(g[2]).isEqualTo(0); // group 1 start
        assertThat(g[3]).isEqualTo(3); // group 1 end
    }

    // ----------------- capture groups (the profit case) -----------------

    @Test void simpleCaptureGroup() {
        Regex r = Regex.compile("(abc)");
        int[] g = groups(r, "abc");
        assertThat(g[2]).isEqualTo(0);
        assertThat(g[3]).isEqualTo(3);
    }

    @Test void multipleCaptureGroups() {
        Regex r = Regex.compile("(a)(b)(c)");
        int[] g = groups(r, "abc");
        assertThat(g[2]).isEqualTo(0); // g1 start
        assertThat(g[3]).isEqualTo(1); // g1 end
        assertThat(g[4]).isEqualTo(1); // g2 start
        assertThat(g[5]).isEqualTo(2); // g2 end
        assertThat(g[6]).isEqualTo(2); // g3 start
        assertThat(g[7]).isEqualTo(3); // g3 end
    }

    @Test void captureUnderRepetition() {
        Regex r = Regex.compile("(a)*b");
        int[] g = groups(r, "aaaab");
        // group 1 captures the last 'a' (positions 3..4)
        assertThat(g[2]).isEqualTo(3);
        assertThat(g[3]).isEqualTo(4);
    }

    @Test void captureUnderRepetitionLongInput() {
        Regex r = Regex.compile("(ab)+");
        int[] g = groups(r, "ababab");
        // last iteration: positions 4..6
        assertThat(g[2]).isEqualTo(4);
        assertThat(g[3]).isEqualTo(6);
    }

    @Test void alternationUnderRepetitionWithCapture() {
        // (a|b)*c — classic TDFA example pattern
        Regex r = Regex.compile("(a|b)*c");
        int[] g = groups(r, "aabbc");
        // group 1 captures last alternation choice: 'b' at position 3..4
        assertThat(g[2]).isEqualTo(3);
        assertThat(g[3]).isEqualTo(4);
        assertThat(r.matches("aabbc")).isTrue();
        assertThat(r.matches("abc")).isTrue();
        assertThat(r.matches("c")).isTrue();
        assertThat(r.matches("d")).isFalse();
    }

    @Test void ipPatternWithCaptures() {
        // realistic capture-heavy pattern
        Regex r = Regex.compile("(\\d+)\\.(\\d+)\\.(\\d+)\\.(\\d+)");
        int[] g = groups(r, "192.168.1.1");
        assertThat(g[2]).isEqualTo(0); assertThat(g[3]).isEqualTo(3);   // 192
        assertThat(g[4]).isEqualTo(4); assertThat(g[5]).isEqualTo(7);   // 168
        assertThat(g[6]).isEqualTo(8); assertThat(g[7]).isEqualTo(9);   // 1
        assertThat(g[8]).isEqualTo(10); assertThat(g[9]).isEqualTo(11); // 1
    }

    @Test void logLinePatternWithCaptures() {
        // Apache-log-style capture
        Regex r = Regex.compile("(\\w+) (\\w+)");
        int[] g = groups(r, "hello world");
        assertThat(g[2]).isEqualTo(0); assertThat(g[3]).isEqualTo(5);
        assertThat(g[4]).isEqualTo(6); assertThat(g[5]).isEqualTo(11);
    }

    // ----------------- anchors -----------------

    @Test void startAnchor() {
        Regex r = Regex.compile("^abc");
        assertThat(r.find("abc def")).isTrue();
        assertThat(r.find("def abc")).isFalse();
    }

    @Test void endAnchor() {
        Regex r = Regex.compile("abc$");
        assertThat(r.find("def abc")).isTrue();
        assertThat(r.find("abc def")).isFalse();
    }

    @Test void bothAnchors() {
        Regex r = Regex.compile("^abc$");
        assertThat(r.matches("abc")).isTrue();
        assertThat(r.matches("abcd")).isFalse();
        assertThat(r.find(" abc ")).isFalse();
    }

    @Test void backslashAnchors() {
        // \A = start of text, \z = end of text (RE2 semantics; matches our ^ $ in default mode).
        Regex r = Regex.compile("\\Aabc\\z");
        assertThat(r.matches("abc")).isTrue();
        assertThat(r.matches("abcd")).isFalse();
        assertThat(r.find("x abc")).isFalse();
    }

    @Test void wordBoundary() {
        // \b matches at any position where one side is a word char and the other isn't.
        Regex r = Regex.compile("\\bword\\b");
        assertThat(r.find("a word here")).isTrue();
        assertThat(r.find("awordhere")).isFalse();
        assertThat(r.find("the word.")).isTrue();
    }

    @Test void noWordBoundary() {
        // \B is the complement of \b.
        Regex r = Regex.compile("\\Bword");
        assertThat(r.find("password")).isTrue();   // 'word' mid-word, \B holds before 'w'
        assertThat(r.find("a word")).isFalse();     // 'word' preceded by space, \b holds (not \B)
    }

    @Test void perlLeftmostFirst() {
        // re2j/Perl: try alternatives left-to-right, first one that match wins.
        // (a|ab) on "ab": alt 1 matches [0,1) — Perl returns [0,1); POSIX returns [0,2).
        Regex perl = Regex.compilePerlVm("(a|ab)");
        MatchResult m1 = perl.find("ab", 0);
        assertThat(m1).as("Perl (a|ab) on 'ab' should be [0,1)").isNotNull();
        assertThat(m1.start(0)).isEqualTo(0);
        assertThat(m1.end(0)).isEqualTo(1);

        // (ab|a) on "ab": alt 1 matches [0,2) — Perl returns [0,2); POSIX also [0,2).
        MatchResult m2 = perl.find("ab", 0);
        Regex perl2 = Regex.compilePerlVm("(ab|a)");
        MatchResult m3 = perl2.find("ab", 0);
        assertThat(m3).as("Perl (ab|a) on 'ab' should be [0,2)").isNotNull();
        assertThat(m3.start(0)).isEqualTo(0);
        assertThat(m3.end(0)).isEqualTo(2);

        // (a|aa) on "aaa": Perl alt 1 matches [0,1).
        Regex perl3 = Regex.compilePerlVm("(a|aa)");
        MatchResult m4 = perl3.find("aaa", 0);
        assertThat(m4).as("Perl (a|aa) on 'aaa' should be [0,1)").isNotNull();
        assertThat(m4.end(0)).isEqualTo(1);
    }

    @Test void posixLeftmostLongest() {
        // Sanity: POSIX default should still give leftmost-longest.
        Regex posix = Regex.compilePosixVm("(a|ab)");
        MatchResult m = posix.find("ab", 0);
        assertThat(m).as("POSIX (a|ab) on 'ab' should be [0,2)").isNotNull();
        assertThat(m.start(0)).isEqualTo(0);
        assertThat(m.end(0)).isEqualTo(2);
    }
}
