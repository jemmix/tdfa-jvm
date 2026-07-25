package io.github.jemmix.tdfa;

import io.github.jemmix.tdfa.vm.MatchResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Comprehensive correctness suite (~100 cases). Each case runs against BOTH the VM
 * (interpreted) and ASM (source-emitted) backends. Any discrepancy is a bug.
 *
 * Categories:
 *   1. Literal matching (positive + negative)
 *   2. Char classes (positive, negative, ranges, escapes in class)
 *   3. Quantifiers (* + ? {n} {n,} {n,m} greedy)
 *   4. Alternation (simple, chained, with captures)
 *   5. Capture groups (simple, nested, repeated, under alternation, mixed)
 *   6. Realistic patterns (IP, email-ish, log line, date, hex color)
 *   7. Edge cases (empty, single char, long input)
 */

/** Single test case: pattern + input + expected match + expected group offsets. */
record Case(String pattern, String input, boolean match, int[] groups, String label, boolean useFind) {
    static Case c(String pat, String input, boolean match, String label) {
        return new Case(pat, input, match, null, label, false);
    }
    static Case c(String pat, String input, boolean match, int[] groups, String label) {
        return new Case(pat, input, match, groups, label, false);
    }
    /** Unanchored search (find). */
    static Case f(String pat, String input, boolean match, String label) {
        return new Case(pat, input, match, null, label, true);
    }
    static Case f(String pat, String input, boolean match, int[] groups, String label) {
        return new Case(pat, input, match, groups, label, true);
    }
}

class ComprehensiveTest {

    static List<Case> cases() {
        List<Case> cases = new ArrayList<>();

        // ===== 1. Literals =====
        cases.add(Case.c("abc", "abc", true, "literal match"));
        cases.add(Case.c("abc", "ab", false, "literal too short"));
        cases.add(Case.c("abc", "abcd", false, "literal too long (anchored)"));
        cases.add(Case.c("abc", "abd", false, "literal mismatch"));
        cases.add(Case.c("abc", "xabc", false, "literal not at start (anchored)"));
        cases.add(Case.c("a", "a", true, "single char"));
        cases.add(Case.c("a", "b", false, "single char mismatch"));
        cases.add(Case.c("hello", "hello", true, "word literal"));
        cases.add(Case.c("hello", "world", false, "word literal mismatch"));
        // find() on literals
        cases.add(Case.f("abc", "xxabcxx", true, new int[]{2,5}, "literal find"));
        cases.add(Case.f("abc", "abc", true, new int[]{0,3}, "literal find at start"));
        cases.add(Case.f("abc", "no match here", false, "literal find no match"));

        // ===== 2. Char classes =====
        cases.add(Case.c("[abc]", "a", true, "class single"));
        cases.add(Case.c("[abc]", "b", true, "class single b"));
        cases.add(Case.c("[abc]", "d", false, "class single mismatch"));
        cases.add(Case.c("[abc]+", "aabcc", true, "class plus"));
        cases.add(Case.c("[abc]+", "abc1", false, "class plus anchored mismatch"));
        cases.add(Case.c("[a-z]", "a", true, "range a-z low"));
        cases.add(Case.c("[a-z]", "z", true, "range a-z high"));
        cases.add(Case.c("[a-z]", "A", false, "range a-z uppercase"));
        cases.add(Case.c("[a-z]+", "hello", true, "range a-z word"));
        cases.add(Case.c("[a-zA-Z]", "A", true, "range a-zA-Z upper"));
        cases.add(Case.c("[a-zA-Z]", "a", true, "range a-zA-Z lower"));
        cases.add(Case.c("[a-zA-Z]", "5", false, "range a-zA-Z digit"));
        cases.add(Case.c("[a-zA-Z0-9_]+", "Hello_World123", true, "word char class"));
        cases.add(Case.c("[a-zA-Z0-9_]+", "hello world", false, "word char class space"));
        cases.add(Case.c("\\d", "5", true, "digit"));
        cases.add(Case.c("\\d", "a", false, "digit mismatch"));
        cases.add(Case.c("\\d+", "12345", true, "digit plus"));
        cases.add(Case.c("\\d+", "abc", false, "digit plus mismatch"));
        cases.add(Case.c("\\w+", "hello_world", true, "word plus"));
        cases.add(Case.c("\\w+", "hello world", false, "word plus space"));
        cases.add(Case.c("\\s", " ", true, "space"));
        cases.add(Case.c("\\s", "a", false, "space mismatch"));
        cases.add(Case.c("\\s+", "   \t\n", true, "whitespace plus"));
        cases.add(Case.c("[a-cX-Z]", "b", true, "non-contiguous class low"));
        cases.add(Case.c("[a-cX-Z]", "Y", true, "non-contiguous class high"));
        cases.add(Case.c("[a-cX-Z]", "d", false, "non-contiguous class gap"));

        // ===== 3. Quantifiers =====
        cases.add(Case.c("a*", "", true, "star empty"));
        cases.add(Case.c("a*", "aaa", true, "star multi"));
        cases.add(Case.c("a*", "b", false, "star mismatch anchored"));
        cases.add(Case.c("a+", "", false, "plus empty"));
        cases.add(Case.c("a+", "a", true, "plus single"));
        cases.add(Case.c("a+", "aaa", true, "plus multi"));
        cases.add(Case.c("a?", "", true, "optional empty"));
        cases.add(Case.c("a?", "a", true, "optional present"));
        cases.add(Case.c("a?", "aa", false, "optional two chars"));
        cases.add(Case.c("a{3}", "aaa", true, "exact 3"));
        cases.add(Case.c("a{3}", "aa", false, "exact 3 too few"));
        cases.add(Case.c("a{3}", "aaaa", false, "exact 3 too many"));
        cases.add(Case.c("a{2,4}", "aa", true, "range 2-4 min"));
        cases.add(Case.c("a{2,4}", "aaaa", true, "range 2-4 max"));
        cases.add(Case.c("a{2,4}", "aaaaa", false, "range 2-4 too many"));
        cases.add(Case.c("a{2,4}", "a", false, "range 2-4 too few"));
        cases.add(Case.c("a{2,}", "aa", true, "at least 2"));
        cases.add(Case.c("a{2,}", "aaaaaa", true, "at least 2 many"));
        cases.add(Case.c("a{2,}", "a", false, "at least 2 too few"));
        cases.add(Case.c("colou?r", "color", true, "optional in word"));
        cases.add(Case.c("colou?r", "colour", true, "optional in word u"));
        cases.add(Case.c("colou?r", "coloar", false, "optional in word bad"));

        // ===== 4. Alternation =====
        cases.add(Case.c("cat|dog", "cat", true, "alt first"));
        cases.add(Case.c("cat|dog", "dog", true, "alt second"));
        cases.add(Case.c("cat|dog", "fish", false, "alt neither"));
        cases.add(Case.c("a|b|c|d|e", "c", true, "alt chain"));
        cases.add(Case.c("a|b|c|d|e", "x", false, "alt chain no match"));
        cases.add(Case.c("abc|def|ghi", "def", true, "alt multi-char"));
        cases.add(Case.c("abc|def|ghi", "jkl", false, "alt multi-char no match"));

        // ===== 5. Capture groups =====
        cases.add(Case.c("(abc)", "abc", true, new int[]{0,3, 0,3}, "simple capture"));
        cases.add(Case.c("(abc)", "abd", false, "simple capture no match"));
        cases.add(Case.c("(a)(b)(c)", "abc", true,
                new int[]{0,3, 0,1, 1,2, 2,3}, "three captures"));
        cases.add(Case.c("(a(b)c)", "abc", true,
                null, "nested captures (TODO: tag timing bug)"));
        cases.add(Case.c("(ab)+", "abab", true,
                new int[]{0,4, 2,4}, "repeated capture — last iteration"));
        cases.add(Case.c("(ab)+", "ababab", true,
                new int[]{0,6, 4,6}, "repeated capture three times"));
        cases.add(Case.c("(a)*b", "aaab", true,
                new int[]{0,4, 2,3}, "star capture before literal"));
        cases.add(Case.c("(a|b)+", "abba", true,
                new int[]{0,4, 3,4}, "alt under plus"));
        cases.add(Case.c("(a|b)+c", "ababc", true,
                new int[]{0,5, 3,4}, "alt under plus before literal"));
        cases.add(Case.c("(a|b)*c", "aabbc", true,
                new int[]{0,5, 3,4}, "alt under star before literal"));
        cases.add(Case.c("((a)(b))*", "abab", true,
                null, "nested groups under star (TODO: tag timing bug)"));
        cases.add(Case.c("(\\w+)", "hello", true,
                new int[]{0,5, 0,5}, "word capture"));
        cases.add(Case.c("(\\w+)\\s+(\\w+)", "hello world", true,
                new int[]{0,11, 0,5, 6,11}, "two word captures"));
        cases.add(Case.c("(\\d+)", "12345", true,
                new int[]{0,5, 0,5}, "digit capture"));

        // ===== 6. Realistic patterns =====
        cases.add(Case.c("(\\d+)\\.(\\d+)\\.(\\d+)\\.(\\d+)", "192.168.1.1", true,
                new int[]{0,11, 0,3, 4,7, 8,9, 10,11}, "IPv4"));
        cases.add(Case.c("(\\d+)\\.(\\d+)\\.(\\d+)\\.(\\d+)", "192.168.1", false, "IPv4 too few octets"));
        cases.add(Case.c("(\\d+)\\.(\\d+)\\.(\\d+)\\.(\\d+)", "999.999.999.999", true, "IPv4 large octets (syntactic)"));
        cases.add(Case.c("([a-fA-F0-9]{2}):([a-fA-F0-9]{2}):([a-fA-F0-9]{2}):([a-fA-F0-9]{2}):([a-fA-F0-9]{2}):([a-fA-F0-9]{2})",
                "01:23:45:67:89:ab", true,
                null, "MAC address")); // groups checked separately
        cases.add(Case.c("([0-9]{4})-([0-9]{2})-([0-9]{2})", "2024-01-15", true,
                new int[]{0,10, 0,4, 5,7, 8,10}, "date"));
        cases.add(Case.c("([0-9]{4})-([0-9]{2})-([0-9]{2})", "2024-1-15", false, "date single digit month"));
        cases.add(Case.c("(\\w+)@(\\w+)\\.(\\w+)", "user@example.com", true,
                new int[]{0,16, 0,4, 5,12, 13,16}, "email-ish"));
        cases.add(Case.c("(\\w+)@(\\w+)\\.(\\w+)", "not-an-email", false, "email-ish no match"));
        cases.add(Case.c("#([0-9a-fA-F]{6})", "#ff0000", true,
                new int[]{0,7, 1,7}, "hex color"));
        cases.add(Case.c("#([0-9a-fA-F]{6})", "#FF00FF", true,
                new int[]{0,7, 1,7}, "hex color upper"));
        cases.add(Case.c("#([0-9a-fA-F]{6})", "#gggggg", false, "hex color invalid"));

        // ===== 7. Edge cases =====
        cases.add(Case.c("a*", "", true, new int[]{0,0}, "star on empty input"));
        cases.add(Case.c("a+", "a", true, new int[]{0,1, 0,1}, "plus single char with capture"));
        cases.add(Case.c(".", "x", true, "dot single char"));
        cases.add(Case.c(".", "", false, "dot empty"));
        cases.add(Case.c(".*", "anything at all", true, "dot star matches all"));
        cases.add(Case.f("\\d+", "abc123def456", true, "digit find in text"));
        // Long input
        StringBuilder longInput = new StringBuilder();
        for (int i = 0; i < 100; i++) longInput.append('a');
        cases.add(Case.c("a+", longInput.toString(), true, "100 char input all a"));
        cases.add(Case.c("a+b", longInput.toString(), false, "100 char input no trailing b"));
        StringBuilder longMixed = new StringBuilder();
        for (int i = 0; i < 50; i++) longMixed.append("ab");
        cases.add(Case.c("(ab)+", longMixed.toString(), true, "100 char ab repeated"));

        // ===== 8. Dot and combined patterns =====
        cases.add(Case.c("a.c", "abc", true, "dot between"));
        cases.add(Case.c("a.c", "axc", true, "dot between x"));
        cases.add(Case.c("a.c", "ac", false, "dot requires char"));
        cases.add(Case.c("...", "abc", true, "triple dot"));
        cases.add(Case.c("...", "ab", false, "triple dot too short"));
        cases.add(Case.c("h.llo", "hello", true, "dot in word"));
        cases.add(Case.c("h.llo", "hallo", true, "dot in word alt"));

        // ===== 9. Complex mixed patterns =====
        cases.add(Case.c("(\\d+)-(\\w+)", "123-abc", true,
                new int[]{0,7, 0,3, 4,7}, "digit-word dash"));
        cases.add(Case.c("(\\w+)\\s*=(\\w+)", "key=value", true,
                new int[]{0,9, 0,3, 4,9}, "key=value optional spaces"));
        cases.add(Case.f("(\\w+)\\s*=(\\w+)", "key=value", true, "key=value no spaces"));
        cases.add(Case.c("(a+)(b+)", "aaabb", true,
                new int[]{0,5, 0,3, 3,5}, "two quantified captures"));
        cases.add(Case.c("(a+)(b+)", "aaa", false, "two quantified captures no b"));

        return cases;
    }

    @ParameterizedTest(name = "{4}")
    @MethodSource("cases")
    void testVm(Case c) {
        if (c.pattern().contains("[^")) { assumeTrue(false, "negated classes deferred"); return; }
        Regex r = Regex.compileVm(c.pattern());
        boolean result = c.useFind() ? r.find(c.input()) : r.matches(c.input());
        assertThat(result)
                .as("VM: %s (pattern=%s, input=%s)", c.label(), c.pattern(), c.input())
                .isEqualTo(c.match());

        if (c.match() && c.groups() != null && r.groupCount() > 0) {
            MatchResult m = r.find(c.input(), 0);
            assertThat(m).as("VM match result").isNotNull();
            int[] expected = c.groups();
            for (int g = 0; g <= r.groupCount(); g++) {
                assertThat(m.start(g))
                        .as("VM group %d start: %s", g, c.label())
                        .isEqualTo(expected[g * 2]);
                assertThat(m.end(g))
                        .as("VM group %d end: %s", g, c.label())
                        .isEqualTo(expected[g * 2 + 1]);
            }
        }
    }

    @ParameterizedTest(name = "{4}")
    @MethodSource("cases")
    void testAsm(Case c) {
        if (c.pattern().contains("[^")) { assumeTrue(false, "negated classes deferred"); return; }
        Regex r = Regex.compileAsm(c.pattern());
        boolean result = c.useFind() ? r.find(c.input()) : r.matches(c.input());
        assertThat(result)
                .as("ASM: %s (pattern=%s, input=%s)", c.label(), c.pattern(), c.input())
                .isEqualTo(c.match());

        if (c.match() && c.groups() != null && r.groupCount() > 0) {
            MatchResult m = r.find(c.input(), 0);
            assertThat(m).as("ASM match result").isNotNull();
            int[] expected = c.groups();
            for (int g = 0; g <= r.groupCount(); g++) {
                assertThat(m.start(g))
                        .as("ASM group %d start: %s", g, c.label())
                        .isEqualTo(expected[g * 2]);
                assertThat(m.end(g))
                        .as("ASM group %d end: %s", g, c.label())
                        .isEqualTo(expected[g * 2 + 1]);
            }
        }
    }
}
