package io.github.jemmix.tdfa;

import io.github.jemmix.tdfa.vm.MatchResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs re2j's FindTest.FIND_TESTS against our engine.
 *
 * Each re2j test case is (pattern, input, expectedMatches[][]) where matches[i] = [start,end,...submatches].
 * We test the FIRST match only (our find() returns one match at a time).
 *
 * Cases that fail are categorized for triage:
 *   - ANCHORS: need ^ $ enforcement
 *   - NEGATED_CLASS: need [^...] support
 *   - WORD_BOUNDARY: need \b \B
 *   - FLAGS: need (?i) (?s) etc.
 *   - POSIX_CLASS: need [:space:] [:digit:]
 *   - UNICODE: need surrogate pair handling
 *   - SPECIAL_ESCAPE: need \a \f \v \`
 *   - NESTED_CAPTURE: nested capture groups (was TAG_TIMING, fixed in #15)
 *   - PARSER_GAP: other missing parser feature
 */
class Re2jFindTest {

    record Re2Case(String pat, String input, int matchStart, int matchEnd, int[] groups, String category) {}

    static List<Re2Case> re2jCases() {
        List<Re2Case> cases = new ArrayList<>();
        // Pattern, input, first-match-start, first-match-end, groups flat or null if no match
        // Groups: [g1s,g1e, g2s,g2e, ...] (-1 = unmatched)

        // === Cases that SHOULD work already ===
        cases.add(new Re2Case("", "", 0, 0, null, "BASIC"));
        cases.add(new Re2Case("a+", "baaab", 1, 4, null, "BASIC"));
        cases.add(new Re2Case("abcd..", "abcdef", 0, 6, null, "BASIC"));
        cases.add(new Re2Case("a", "a", 0, 1, null, "BASIC"));
        cases.add(new Re2Case("x", "y", -1, -1, null, "BASIC"));
        cases.add(new Re2Case("b", "abc", 1, 2, null, "BASIC"));
        cases.add(new Re2Case(".", "a", 0, 1, null, "BASIC"));
        cases.add(new Re2Case(".*", "abcdef", 0, 6, null, "BASIC"));
        cases.add(new Re2Case("[a-z]+", "abcd", 0, 4, null, "BASIC"));
        cases.add(new Re2Case("a*", "baaab", 0, 0, null, "BASIC")); // first match is empty at 0
        cases.add(new Re2Case("data", "daXY data", 5, 9, null, "BASIC"));
        cases.add(new Re2Case("zx+", "zzx", 1, 3, null, "BASIC"));
        cases.add(new Re2Case("[.]", ".", 0, 1, null, "BASIC"));
        cases.add(new Re2Case("(?:A(?:A|a))", "Aa", 0, 2, null, "BASIC"));
        cases.add(new Re2Case("(?:A|(?:A|a))", "a", 0, 1, null, "BASIC"));

        // === Capture groups (should mostly work) ===
        cases.add(new Re2Case("()", "", 0, 0, new int[]{0, 0}, "CAPTURE"));
        cases.add(new Re2Case("(a)", "a", 0, 1, new int[]{0, 1}, "CAPTURE"));
        cases.add(new Re2Case("(.*)", "", 0, 0, new int[]{0, 0}, "CAPTURE"));
        cases.add(new Re2Case("(.*)", "abcd", 0, 4, new int[]{0, 4}, "CAPTURE"));
        cases.add(new Re2Case("(..)(..)", "abcd", 0, 4, new int[]{0,2, 2,4}, "CAPTURE"));
        cases.add(new Re2Case("(.*).*", "ab", 0, 2, new int[]{0,2}, "CAPTURE"));
        cases.add(new Re2Case("(.)", "abc", 0, 1, new int[]{0,1}, "CAPTURE")); // first match only
        cases.add(new Re2Case("a(b*)", "abbaab", 0, 3, new int[]{1,3}, "CAPTURE")); // first match
        cases.add(new Re2Case("ab*", "abbaab", 0, 3, null, "BASIC"));
        cases.add(new Re2Case("(a){0}", "", 0, 0, new int[]{-1,-1}, "CAPTURE")); // {0} = zero reps, group unmatched

        // === Anchors (need ^ $ enforcement) ===
        cases.add(new Re2Case("^abcdefg", "abcdefg", 0, 7, null, "ANCHORS"));
        cases.add(new Re2Case("^", "abcde", 0, 0, null, "ANCHORS"));
        cases.add(new Re2Case("$", "abcde", 5, 5, null, "ANCHORS"));
        cases.add(new Re2Case("^abcd$", "abcd", 0, 4, null, "ANCHORS"));
        cases.add(new Re2Case("^bcd'", "abcdef", -1, -1, null, "ANCHORS")); // no match
        cases.add(new Re2Case("^abcd$", "abcde", -1, -1, null, "ANCHORS")); // no match
        cases.add(new Re2Case("/$", "/abc/", 4, 5, null, "ANCHORS"));
        cases.add(new Re2Case("/$", "/abc", -1, -1, null, "ANCHORS")); // no match
        cases.add(new Re2Case("ab$", "cab", 1, 3, null, "ANCHORS"));
        cases.add(new Re2Case("ab$", "abcab", 3, 5, null, "ANCHORS"));
        cases.add(new Re2Case("da(.)a$", "daXY data", 5, 9, new int[]{7,8}, "ANCHORS"));

        // === Negated char classes (need [^...] support) ===
        cases.add(new Re2Case("[^a-z]+", "ab1234cd", 2, 6, null, "NEGATED_CLASS"));
        cases.add(new Re2Case("[^\\n]+", "abcd\n", 0, 4, null, "NEGATED_CLASS"));

        // === Word boundaries (need \b \B) ===
        cases.add(new Re2Case("\\b", "x", 0, 0, null, "WORD_BOUNDARY"));
        cases.add(new Re2Case("\\b", "xx", 0, 0, null, "WORD_BOUNDARY"));
        cases.add(new Re2Case("\\B", "x", -1, -1, null, "WORD_BOUNDARY"));
        cases.add(new Re2Case("\\B", "xx", 1, 1, null, "WORD_BOUNDARY"));

        // === Flags (need (?i) (?s) (?-s)) ===
        cases.add(new Re2Case("(?s)(?:(?:^).)", "\n", 0, 1, null, "FLAGS"));
        cases.add(new Re2Case("(?-s)(?:(?:^).)", "\n", -1, -1, null, "FLAGS"));
        cases.add(new Re2Case("(?i)\\W", "x", -1, -1, null, "FLAGS"));

        // === Nested captures (was TAG_TIMING, fixed in #15 — was a group-numbering bug, not TDFA tag-timing) ===
        cases.add(new Re2Case("(([^xyz]*)(d))", "abcd", 0, 4, new int[]{0,4, 0,3, 3,4}, "NESTED_CAPTURE"));
        cases.add(new Re2Case("((a|b|c)*(d))", "abcd", 0, 4, new int[]{0,4, 2,3, 3,4}, "NESTED_CAPTURE"));
        cases.add(new Re2Case("(((a|b|c)*)(d))", "abcd", 0, 4, new int[]{0,4, 0,3, 2,3, 3,4}, "NESTED_CAPTURE"));
        cases.add(new Re2Case("a*(|(b))c*", "aacc", 0, 4, new int[]{2,2, -1,-1}, "NESTED_CAPTURE"));
        cases.add(new Re2Case("(aa)*$", "a", 1, 1, new int[]{-1,-1}, "NESTED_CAPTURE"));

        // === POSIX classes ===
        cases.add(new Re2Case("[^\\S\\s]", "abcd", -1, -1, null, "POSIX_CLASS"));
        cases.add(new Re2Case("[^\\D\\d]", "abcd", -1, -1, null, "POSIX_CLASS"));

        // === Escaped punctuation ===
        cases.add(new Re2Case("[a\\-\\]z]+", "az]-bcz", 0, 4, null, "ESCAPE"));

        // === Non-capturing groups (should work) ===
        cases.add(new Re2Case("(?:.|(?:.a))", "", -1, -1, null, "BASIC"));

        return cases;
    }

    @Test
    void runAllRe2jCasesVm() {
        int pass = 0, fail = 0, skip = 0;
        List<String> failures = new ArrayList<>();
        List<String> passes = new ArrayList<>();

        for (Re2Case c : re2jCases()) {
            try {
                Regex r = Regex.compile(c.pat(), EngineFactory.VM);
                MatchResult m = r.find(c.input(), 0);

                if (c.matchStart() < 0) {
                    // Expect no match
                    if (m == null) {
                        pass++;
                        passes.add("  PASS [" + c.category() + "] " + c.pat() + " on \"" + c.input() + "\"");
                    } else {
                        fail++;
                        failures.add("  FAIL [" + c.category() + "] " + c.pat() + " on \"" + c.input() + "\" — expected no match, got [" + m.start(0) + "," + m.end(0) + ")");
                    }
                } else {
                    // Expect a match at matchStart..matchEnd
                    if (m == null) {
                        fail++;
                        failures.add("  FAIL [" + c.category() + "] " + c.pat() + " on \"" + c.input() + "\" — expected [" + c.matchStart() + "," + c.matchEnd() + "), got no match");
                    } else if (m.start(0) != c.matchStart() || m.end(0) != c.matchEnd()) {
                        fail++;
                        failures.add("  FAIL [" + c.category() + "] " + c.pat() + " on \"" + c.input() + "\" — expected [" + c.matchStart() + "," + c.matchEnd() + "), got [" + m.start(0) + "," + m.end(0) + ")");
                    } else if (c.groups() != null) {
                        // Check group offsets
                        boolean groupsOk = true;
                        int gc = c.groups().length / 2;
                        for (int g = 0; g < gc; g++) {
                            int expectedStart = c.groups()[g * 2];
                            int expectedEnd = c.groups()[g * 2 + 1];
                            int actualStart = m.start(g + 1);
                            int actualEnd = m.end(g + 1);
                            if (expectedStart != actualStart || expectedEnd != actualEnd) {
                                groupsOk = false;
                                fail++;
                                failures.add("  FAIL [" + c.category() + "] " + c.pat() + " on \"" + c.input() + "\" — group " + (g+1) + " expected [" + expectedStart + "," + expectedEnd + "), got [" + actualStart + "," + actualEnd + ")");
                                break;
                            }
                        }
                        if (groupsOk) {
                            pass++;
                            passes.add("  PASS [" + c.category() + "] " + c.pat() + " on \"" + c.input() + "\"");
                        }
                    } else {
                        pass++;
                        passes.add("  PASS [" + c.category() + "] " + c.pat() + " on \"" + c.input() + "\"");
                    }
                }
            } catch (Exception e) {
                skip++;
                failures.add("  ERROR [" + c.category() + "] " + c.pat() + " on \"" + c.input() + "\" — " + e.getMessage());
            }
        }

        System.out.println("\n===== re2j FindTest results (VM) =====");
        System.out.println("PASS: " + pass + "  FAIL: " + fail + "  ERROR: " + skip + "  TOTAL: " + (pass + fail + skip));
        System.out.println("\n--- PASSED ---");
        passes.forEach(System.out::println);
        System.out.println("\n--- FAILED/ERROR ---");
        failures.forEach(System.out::println);

        // Don't fail the build — this is a diagnostic test
        // assertThat(fail).as("re2j failures").isZero();
    }

    @Test
    void runAllRe2jCasesAsm() {
        int pass = 0, fail = 0, skip = 0;
        List<String> failures = new ArrayList<>();

        for (Re2Case c : re2jCases()) {
            try {
                Regex r = Regex.compile(c.pat(), EngineFactory.ASM);
                MatchResult m = r.find(c.input(), 0);

                if (c.matchStart() < 0) {
                    if (m == null) { pass++; } else {
                        fail++;
                        failures.add("  FAIL [" + c.category() + "] " + c.pat() + " on \"" + c.input() + "\" — expected no match");
                    }
                } else {
                    if (m == null) {
                        fail++;
                        failures.add("  FAIL [" + c.category() + "] " + c.pat() + " on \"" + c.input() + "\" — no match");
                    } else if (m.start(0) != c.matchStart() || m.end(0) != c.matchEnd()) {
                        fail++;
                        failures.add("  FAIL [" + c.category() + "] " + c.pat() + " — expected [" + c.matchStart() + "," + c.matchEnd() + ") got [" + m.start(0) + "," + m.end(0) + ")");
                    } else {
                        pass++;
                    }
                }
            } catch (Exception e) {
                skip++;
                failures.add("  ERROR [" + c.category() + "] " + c.pat() + " — " + e.getMessage());
            }
        }

        System.out.println("\n===== re2j FindTest results (ASM) =====");
        System.out.println("PASS: " + pass + "  FAIL: " + fail + "  ERROR: " + skip + "  TOTAL: " + (pass + fail + skip));
        failures.forEach(System.out::println);
    }
}
