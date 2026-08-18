package io.github.jemmix.tdfa;

import io.github.jemmix.tdfa.core.RegexEngineFactory;
import io.github.jemmix.tdfa.tdfa.TdfaRunner;
import io.github.jemmix.tdfa.core.Matcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Capture-group register allocation: interference analysis, COPY-chain
 * ordering in final-ops, and the BT22 §6.3 optimization pipeline.
 *
 * <p>Problem areas:
 * <ul>
 *   <li><b>Adjacent greedy groups</b> — patterns like {@code (a*)(a*)} where
 *       a group matches empty adjacent to another greedy group. These used to
 *       crash at compile time ({@code ArrayIndexOutOfBoundsException} in
 *       {@code Optimize.findFinalRegBase}) because allocation coalesced final
 *       registers; fixed by keeping finals in dedicated consecutive slots.</li>
 *   <li><b>Alternation aliasing</b> — many-branch alternation with one
 *       capture group per branch must report exactly one participating
 *       group per match (the §A interference analysis area).</li>
 *   <li><b>COPY-chain ordering</b> — final-ops with chained COPYs
 *       ({@code [i←j, j←k]}) must execute in the right order (the 6691e97
 *       topoSortCopy fix area).</li>
 * </ul>
 */
class CaptureGroupAllocationTest {

    private static Stream<RegexEngineFactory> factories() {
        return Stream.<RegexEngineFactory>of(null, TdfaRunner::new);
    }

    private static Matcher find(String pattern, String input, int flags, RegexEngineFactory f) {
        Matcher m = Pattern.compile(pattern, flags, f).matcher(input);
        return m.find() ? m : null;
    }

    private static Matcher match(Pattern p, String input) {
        Matcher m = p.matcher(input);
        return m.find() ? m : null;
    }

    private static int participating(Matcher m) {
        int c = 0;
        for (int g = 1; g <= m.groupCount(); g++) if (m.start(g) >= 0) c++;
        return c;
    }

    // ===== Adjacent greedy groups (fixed: final-register dedicated-slot invariant) =====

    @ParameterizedTest @MethodSource("factories")
    void adjacentStarGroups(RegexEngineFactory f) {
        assertThatCode(() -> {
            Matcher m = find("(a*)(a*)", "aaa", 0, f);
            assertThat(m).isNotNull();
            assertThat(m.start(1)).isEqualTo(0);
            assertThat(m.end(1)).isEqualTo(3);
            assertThat(m.start(2)).isEqualTo(3);
            assertThat(m.end(2)).isEqualTo(3);
        })
                .as("(a*)(a*) should not crash — BUG: ArrayIndexOutOfBoundsException in findFinalRegBase")
                .doesNotThrowAnyException();
    }

    /** Same crash with (a+)(a*): a+ eats everything, a* gets nothing. */
    @ParameterizedTest @MethodSource("factories")
    void adjacentPlusStarGroups(RegexEngineFactory f) {
        assertThatCode(() -> find("(a+)(a*)", "aaa", 0, f))
                .as("(a+)(a*) should not crash")
                .doesNotThrowAnyException();
    }

    /** Same crash with (.*)(.*). */
    @ParameterizedTest @MethodSource("factories")
    void adjacentDotStarGroups(RegexEngineFactory f) {
        assertThatCode(() -> find("(.*)(.*)", "abc", 0, f))
                .as("(.*)(.*) should not crash")
                .doesNotThrowAnyException();
    }

    /** Same crash with (a*)(a?): optional second group can be empty. */
    @ParameterizedTest @MethodSource("factories")
    void adjacentStarOptionalGroups(RegexEngineFactory f) {
        assertThatCode(() -> find("(a*)(a?)", "aaa", 0, f))
                .as("(a*)(a?) should not crash")
                .doesNotThrowAnyException();
    }

    /** Three adjacent groups: (a*)(a*)(a*). */
    @ParameterizedTest @MethodSource("factories")
    void threeAdjacentStarGroups(RegexEngineFactory f) {
        assertThatCode(() -> find("(a*)(a*)(a*)", "aaa", 0, f))
                .as("(a*)(a*)(a*) should not crash")
                .doesNotThrowAnyException();
    }

    /** The crash also affects POSIX mode. */
    @ParameterizedTest @MethodSource("factories")
    void adjacentStarGroupsPosix(RegexEngineFactory f) {
        assertThatCode(() -> find("(a*)(a*)", "aaa", Pattern.LONGEST_MATCH, f))
                .as("POSIX (a*)(a*) should not crash")
                .doesNotThrowAnyException();
    }

    /** Patterns that DON'T crash (second group requires ≥1 char). */
    @ParameterizedTest @MethodSource("factories")
    void nonAdjacentGreedyGroupsWork(RegexEngineFactory f) {
        Matcher m1 = find("(a*)(a+)", "aaa", 0, f);
        assertThat(m1).isNotNull();
        assertThat(m1.end(1)).isEqualTo(2);
        assertThat(m1.start(2)).isEqualTo(2);

        Matcher m2 = find("(a?)(a*)", "aaa", 0, f);
        assertThat(m2).isNotNull();
        assertThat(m2.end(1)).isEqualTo(1);
        assertThat(m2.start(2)).isEqualTo(1);
    }

    // ===== Alternation aliasing (§A area) =====

    @ParameterizedTest @MethodSource("factories")
    void fiveWayAlternationOneGroup(RegexEngineFactory f) {
        Pattern r = Pattern.compile("(a)|(b)|(c)|(d)|(e)", 0, f);
        String[] inputs = {"a", "b", "c", "d", "e"};
        for (int i = 0; i < inputs.length; i++) {
            Matcher m = match(r, inputs[i]);
            assertThat(m).as("match '%s'", inputs[i]).isNotNull();
            assertThat(participating(m)).as("exactly 1 group").isEqualTo(1);
            assertThat(m.start(i + 1)).isEqualTo(0);
            assertThat(m.end(i + 1)).isEqualTo(1);
        }
    }

    @ParameterizedTest @MethodSource("factories")
    void tenWayAlternationVariableLength(RegexEngineFactory f) {
        Pattern r = Pattern.compile("(aa)|(bb)|(cc)|(dd)|(ee)|(ff)|(gg)|(hh)|(ii)|(jj)",
                0, f);
        String[] inputs = {"aa", "bb", "cc", "dd", "ee", "ff", "gg", "hh", "ii", "jj"};
        for (int i = 0; i < inputs.length; i++) {
            Matcher m = match(r, inputs[i]);
            assertThat(participating(m)).isEqualTo(1);
            assertThat(m.start(i + 1)).isEqualTo(0);
            assertThat(m.end(i + 1)).isEqualTo(2);
        }
    }

    @ParameterizedTest @MethodSource("factories")
    void sharedPrefixAlternation(RegexEngineFactory f) {
        Pattern r = Pattern.compile("(abc)|(abd)|(abe)", 0, f);
        String[] inputs = {"abc", "abd", "abe"};
        for (int i = 0; i < inputs.length; i++) {
            Matcher m = match(r, inputs[i]);
            assertThat(participating(m)).isEqualTo(1);
            assertThat(m.start(i + 1)).isEqualTo(0);
        }
    }

    @ParameterizedTest @MethodSource("factories")
    void miniLexerOneTokenPerMatch(RegexEngineFactory f) {
        Pattern r = Pattern.compile(
                "([ \\t]+)|(//[^\\n]*)|([0-9]+)|([a-zA-Z_][a-zA-Z0-9_]*)|(.)",
                0, f);
        String input = "foo = 42 // bar";
        int n = 0;
        for (Matcher m = r.matcher(input); m.find(); ) {
            assertThat(participating(m))
                    .as("match %d ('%s')", n, input.substring(m.start(0), m.end(0)))
                    .isEqualTo(1);
            n++;
        }
        assertThat(n).isGreaterThan(3);
    }

    @ParameterizedTest @MethodSource("factories")
    void alternationDifferentLengthsPosix(RegexEngineFactory f) {
        Pattern r = Pattern.compile("(a)|(abcde)|(xy)", Pattern.LONGEST_MATCH, f);
        Matcher m2 = match(r, "abcde");
        assertThat(participating(m2)).isEqualTo(1);
        assertThat(m2.start(2)).isEqualTo(0);
        assertThat(m2.end(2)).isEqualTo(5);
    }

    @ParameterizedTest @MethodSource("factories")
    void manySequentialMatches(RegexEngineFactory f) {
        Pattern r = Pattern.compile("(cat)|(dog)|(bird)|(fish)", 0, f);
        String[] inputs = {"cat", "dog", "bird", "fish", "cat", "dog", "bird", "fish"};
        int[] groups = {1, 2, 3, 4, 1, 2, 3, 4};
        for (int i = 0; i < inputs.length; i++) {
            Matcher m = match(r, inputs[i]);
            assertThat(participating(m)).as("iteration %d", i).isEqualTo(1);
            assertThat(m.start(groups[i])).isEqualTo(0);
        }
    }

    // ===== COPY-chain and nested groups (6691e97 area) =====

    @ParameterizedTest @MethodSource("factories")
    void fourSequentialGroupsCopyChain(RegexEngineFactory f) {
        Matcher m = find("(a)(b)(c)(d)", "abcd", 0, f);
        assertThat(m.start(1)).isEqualTo(0); assertThat(m.end(1)).isEqualTo(1);
        assertThat(m.start(2)).isEqualTo(1); assertThat(m.end(2)).isEqualTo(2);
        assertThat(m.start(3)).isEqualTo(2); assertThat(m.end(3)).isEqualTo(3);
        assertThat(m.start(4)).isEqualTo(3); assertThat(m.end(4)).isEqualTo(4);
    }

    @ParameterizedTest @MethodSource("factories")
    void optionalGroupMiddleCopyChain(RegexEngineFactory f) {
        Matcher m1 = find("(a)(b)?(c)", "ac", 0, f);
        assertThat(m1.start(1)).isEqualTo(0); assertThat(m1.end(1)).isEqualTo(1);
        assertThat(m1.start(2)).isEqualTo(-1);
        assertThat(m1.start(3)).isEqualTo(1); assertThat(m1.end(3)).isEqualTo(2);

        Matcher m2 = find("(a)(b)?(c)", "abc", 0, f);
        assertThat(m2.start(2)).isEqualTo(1); assertThat(m2.end(2)).isEqualTo(2);
    }

    @ParameterizedTest @MethodSource("factories")
    void nestedGroupsCopyChain(RegexEngineFactory f) {
        Matcher m = find("((a)(b))(c)", "abc", 0, f);
        assertThat(m.start(1)).isEqualTo(0); assertThat(m.end(1)).isEqualTo(2);
        assertThat(m.start(2)).isEqualTo(0); assertThat(m.end(2)).isEqualTo(1);
        assertThat(m.start(3)).isEqualTo(1); assertThat(m.end(3)).isEqualTo(2);
        assertThat(m.start(4)).isEqualTo(2); assertThat(m.end(4)).isEqualTo(3);
    }

    @ParameterizedTest @MethodSource("factories")
    void alternationDifferentGroupCounts(RegexEngineFactory f) {
        // (abc(def))|(abc) — branch 1 has 2 groups, branch 2 has 1
        Matcher m = find("(abc(def))|(abc)", "abcdef", 0, f);
        assertThat(m).isNotNull();
        assertThat(m.start(1)).isEqualTo(0);
        assertThat(m.start(2)).isEqualTo(3);
        assertThat(m.start(3)).isEqualTo(-1);
    }

    @ParameterizedTest @MethodSource("factories")
    void emptyAlternationBranch(RegexEngineFactory f) {
        Matcher m = find("(a)|()|(b)", "x", 0, f);
        assertThat(m).isNotNull();
        // PERL: first branch 'a' fails, second '' matches at pos 0
        assertThat(m.start(1)).isEqualTo(-1);
        assertThat(m.start(2)).isEqualTo(0);
        assertThat(m.end(2)).isEqualTo(0);
    }

    // ===== Bounded repeat capture =====

    @ParameterizedTest @MethodSource("factories")
    void boundedRepeatCapture(RegexEngineFactory f) {
        Matcher m = find("(a){2,4}", "aaaa", 0, f);
        assertThat(m).isNotNull();
        assertThat(m.end(0)).isEqualTo(4);
        assertThat(m.start(1)).isEqualTo(3); // last iteration
        assertThat(m.end(1)).isEqualTo(4);
    }

    @ParameterizedTest @MethodSource("factories")
    void nestedAlternationInRepetition(RegexEngineFactory f) {
        Matcher m = find("((a|b)(c|d))+", "abcd", 0, f);
        assertThat(m).isNotNull();
        // Only one iteration matches at [1,3): b then c
        assertThat(m.start(1)).isEqualTo(1); assertThat(m.end(1)).isEqualTo(3);
    }

    // ===== Fallback states (§6.2 area) =====

    @ParameterizedTest @MethodSource("factories")
    void fallbackPreservesCapture(RegexEngineFactory f) {
        Pattern r = Pattern.compile("([A-Z][a-z]+)+(,)?", Pattern.LONGEST_MATCH, f);
        Matcher m = match(r, "HelloW");
        assertThat(m).isNotNull();
        assertThat(m.end(0)).isEqualTo(5);
        assertThat(m.start(1)).isEqualTo(0);
        assertThat(m.end(1)).isEqualTo(5);
    }

    @ParameterizedTest @MethodSource("factories")
    void optionalGroupFallback(RegexEngineFactory f) {
        Pattern r = Pattern.compile("(a)(b)?", Pattern.LONGEST_MATCH, f);
        Matcher m = match(r, "ax");
        assertThat(m).isNotNull();
        assertThat(m.end(0)).isEqualTo(1);
        assertThat(m.start(1)).isEqualTo(0);
        assertThat(m.end(1)).isEqualTo(1);
        assertThat(m.start(2)).isEqualTo(-1);
    }

    @ParameterizedTest @MethodSource("factories")
    void quantifiedGroupFallback(RegexEngineFactory f) {
        Matcher m = find("(\\d+\\.)+\\d+", "1.2.x", Pattern.LONGEST_MATCH, f);
        assertThat(m).isNotNull();
        assertThat(m.end(0)).isEqualTo(3); // "1.2"
    }
}
