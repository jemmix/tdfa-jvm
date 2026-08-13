package io.github.jemmix.tdfa;

import io.github.jemmix.tdfa.tdfa.Disambiguation;
import io.github.jemmix.tdfa.vm.MatchResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Register-optimization interference: the §A fix for BT22 §6.3.
 *
 * <p>Before the fix, {@code Optimize.interferenceAnalysis} walked ops forward
 * instead of backward, causing working registers from different alternation
 * branches to be aliased with final registers. The symptom was phantom
 * captures: groups that didn't participate in the match reporting non-NIL
 * values, and wrong capture offsets for the participating group.
 *
 * <p>These tests use many-alternation regexes (the pattern shape that triggered
 * the original bug in the Veryl lexer) and verify that each match reports
 * exactly one participating group with correct offsets.
 *
 * <p>Each test runs on both ASM and VM backends.
 */
class RegisterOptimizationTest {

    private static Stream<EngineFactory> factories() {
        return Stream.of(EngineFactory.ASM, EngineFactory.VM);
    }

    /**
     * Count how many groups (1..gc) participated in the match (start >= 0).
     */
    private static int participatingGroups(MatchResult m) {
        int count = 0;
        for (int g = 1; g <= m.groupCount(); g++) {
            if (m.start(g) >= 0) count++;
        }
        return count;
    }

    /**
     * Assert that exactly one group participated and all others are NIL (-1,-1).
     */
    private static void assertExactlyOneGroup(MatchResult m, int expectedGroup,
                                              int start, int end, String context) {
        assertThat(participatingGroups(m))
                .as("%s: exactly 1 group should participate (got %d)",
                        context, participatingGroups(m))
                .isEqualTo(1);
        assertThat(m.start(expectedGroup))
                .as("%s: group %d start", context, expectedGroup).isEqualTo(start);
        assertThat(m.end(expectedGroup))
                .as("%s: group %d end", context, expectedGroup).isEqualTo(end);
        for (int g = 1; g <= m.groupCount(); g++) {
            if (g == expectedGroup) continue;
            assertThat(m.start(g))
                    .as("%s: non-participating group %d start should be -1", context, g)
                    .isEqualTo(-1);
            assertThat(m.end(g))
                    .as("%s: non-participating group %d end should be -1", context, g)
                    .isEqualTo(-1);
        }
    }

    /**
     * A 5-branch alternation where each branch is a capture group. Each input
     * should match exactly one branch. This is the minimal shape that exposed
     * the interference bug.
     */
    @ParameterizedTest
    @MethodSource("factories")
    void fiveWayAlternationExactlyOneGroup(EngineFactory factory) {
        Regex r = Regex.compile("(a)|(b)|(c)|(d)|(e)", factory, Disambiguation.PERL);
        String[] inputs = {"a", "b", "c", "d", "e"};
        for (int i = 0; i < inputs.length; i++) {
            MatchResult m = r.find(inputs[i], 0);
            assertThat(m).as("should match '%s'", inputs[i]).isNotNull();
            assertExactlyOneGroup(m, i + 1, 0, 1, "input='" + inputs[i] + "'");
        }
    }

    /**
     * 10-branch alternation with variable-length bodies. Tests that working
     * registers for each branch don't alias with final registers.
     */
    @ParameterizedTest
    @MethodSource("factories")
    void tenWayAlternationVariableLength(EngineFactory factory) {
        Regex r = Regex.compile(
                "(aa)|(bb)|(cc)|(dd)|(ee)|(ff)|(gg)|(hh)|(ii)|(jj)",
                factory, Disambiguation.PERL);
        String[] inputs = {"aa", "bb", "cc", "dd", "ee", "ff", "gg", "hh", "ii", "jj"};
        for (int i = 0; i < inputs.length; i++) {
            MatchResult m = r.find(inputs[i], 0);
            assertThat(m).as("should match '%s'", inputs[i]).isNotNull();
            assertExactlyOneGroup(m, i + 1, 0, 2, "input='" + inputs[i] + "'");
        }
    }

    /**
     * Alternation with shared prefix: (abc)|(abd)|(abe). Tests that the working
     * register for the branch selected doesn't get corrupted by the others.
     */
    @ParameterizedTest
    @MethodSource("factories")
    void sharedPrefixAlternation(EngineFactory factory) {
        Regex r = Regex.compile("(abc)|(abd)|(abe)", factory, Disambiguation.PERL);
        String[] inputs = {"abc", "abd", "abe"};
        for (int i = 0; i < inputs.length; i++) {
            MatchResult m = r.find(inputs[i], 0);
            assertThat(m).as("should match '%s'", inputs[i]).isNotNull();
            assertExactlyOneGroup(m, i + 1, 0, 3, "input='" + inputs[i] + "'");
        }
    }

    /**
     * A mini-lexer: each branch matches a different token type. This mirrors
     * the Veryl lexer shape (many branches, one capture group per branch) that
     * exposed the original interference bug.
     */
    @ParameterizedTest
    @MethodSource("factories")
    void miniLexerExactlyOneTokenPerMatch(EngineFactory factory) {
        Regex r = Regex.compile(
                "([ \\t]+)" +              // 1: whitespace
                "|(//[^\\n]*)" +           // 2: line comment
                "|([0-9]+)" +              // 3: number
                "|([a-zA-Z_][a-zA-Z0-9_]*)" + // 4: identifier
                "|(.)",                     // 5: any single char
                factory, Disambiguation.PERL);

        String input = "foo = 42 // bar";
        int pos = 0;
        int matchNum = 0;
        while (pos <= input.length()) {
            MatchResult m = r.find(input, pos);
            if (m == null) break;
            assertThat(participatingGroups(m))
                    .as("match %d ('%s'): exactly 1 group should participate",
                            matchNum, input.substring(m.start(0), m.end(0)))
                    .isEqualTo(1);
            pos = (m.end(0) <= m.start(0)) ? m.end(0) + 1 : m.end(0);
            matchNum++;
        }
        assertThat(matchNum).as("should find multiple tokens").isGreaterThan(3);
    }

    /**
     * Alternation with branches of widely different lengths. Uses POSIX mode
     * (leftmost-longest) so the longer alternative wins when they share a prefix.
     * Tests that the working register for the selected branch isn't corrupted.
     */
    @ParameterizedTest
    @MethodSource("factories")
    void alternationWithDifferentLengthBranches(EngineFactory factory) {
        Regex r = Regex.compile("(a)|(abcde)|(xy)", factory, Disambiguation.POSIX);
        // Match 'a' → group 1 (only 'a' matches)
        MatchResult m1 = r.find("a", 0);
        assertExactlyOneGroup(m1, 1, 0, 1, "short branch alone");

        // Match 'abcde' → group 2 (POSIX leftmost-longest picks the 5-char branch)
        MatchResult m2 = r.find("abcde", 0);
        assertExactlyOneGroup(m2, 2, 0, 5, "long branch (POSIX longest)");

        // Match 'xy' → group 3
        MatchResult m3 = r.find("xy", 0);
        assertExactlyOneGroup(m3, 3, 0, 2, "medium branch");
    }

    /**
     * Repeated alternation: ((a)|(b))+ — group 2 or 3 captures the last
     * iteration. Note: TDFA(1) does not clear tags from non-participating
     * branches in previous iterations, so group 3 retains its value from
     * the second iteration. We verify the last-iteration group is correct.
     */
    @ParameterizedTest
    @MethodSource("factories")
    void repeatedAlternationLastIteration(EngineFactory factory) {
        Regex r = Regex.compile("((a)|(b))+", factory, Disambiguation.PERL);
        MatchResult m = r.find("aba", 0);
        assertThat(m).isNotNull();
        // Last iteration matched 'a' → group 2 captures last iteration [2,3).
        assertThat(m.start(2)).as("g2 (inner 'a') start").isEqualTo(2);
        assertThat(m.end(2)).as("g2 (inner 'a') end").isEqualTo(3);
    }

    /**
     * Many sequential matches in a stream — the interference bug was
     * non-deterministic in which matches got corrupted, so testing many
     * inputs increases coverage.
     */
    @ParameterizedTest
    @MethodSource("factories")
    void manySequentialMatchesAllCorrect(EngineFactory factory) {
        Regex r = Regex.compile("(cat)|(dog)|(bird)|(fish)", factory, Disambiguation.PERL);
        String[] inputs = {"cat", "dog", "bird", "fish", "cat", "dog", "bird", "fish"};
        int[] expectedGroups = {1, 2, 3, 4, 1, 2, 3, 4};
        for (int i = 0; i < inputs.length; i++) {
            MatchResult m = r.find(inputs[i], 0);
            assertThat(m).as("iteration %d: should match '%s'", i, inputs[i]).isNotNull();
            assertExactlyOneGroup(m, expectedGroups[i], 0, inputs[i].length(),
                    "iteration " + i + " input='" + inputs[i] + "'");
        }
    }
}
