package io.github.jemmix.tdfa;

import io.github.jemmix.tdfa.tdfa.Disambiguation;
import io.github.jemmix.tdfa.core.MatchResult;
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

    private static Stream<EngineFactory> factories() {
        return Stream.of(EngineFactory.ASM, EngineFactory.VM);
    }

    private static MatchResult find(String pattern, String input, EngineFactory f, Disambiguation d) {
        return Regex.compile(pattern, f, d).find(input, 0);
    }

    private static int participating(MatchResult m) {
        int c = 0;
        for (int g = 1; g <= m.groupCount(); g++) if (m.start(g) >= 0) c++;
        return c;
    }

    // ===== Adjacent greedy groups (fixed: final-register dedicated-slot invariant) =====

    @ParameterizedTest @MethodSource("factories")
    void adjacentStarGroups(EngineFactory f) {
        assertThatCode(() -> {
            MatchResult m = find("(a*)(a*)", "aaa", f, Disambiguation.PERL);
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
    void adjacentPlusStarGroups(EngineFactory f) {
        assertThatCode(() -> find("(a+)(a*)", "aaa", f, Disambiguation.PERL))
                .as("(a+)(a*) should not crash")
                .doesNotThrowAnyException();
    }

    /** Same crash with (.*)(.*). */
    @ParameterizedTest @MethodSource("factories")
    void adjacentDotStarGroups(EngineFactory f) {
        assertThatCode(() -> find("(.*)(.*)", "abc", f, Disambiguation.PERL))
                .as("(.*)(.*) should not crash")
                .doesNotThrowAnyException();
    }

    /** Same crash with (a*)(a?): optional second group can be empty. */
    @ParameterizedTest @MethodSource("factories")
    void adjacentStarOptionalGroups(EngineFactory f) {
        assertThatCode(() -> find("(a*)(a?)", "aaa", f, Disambiguation.PERL))
                .as("(a*)(a?) should not crash")
                .doesNotThrowAnyException();
    }

    /** Three adjacent groups: (a*)(a*)(a*). */
    @ParameterizedTest @MethodSource("factories")
    void threeAdjacentStarGroups(EngineFactory f) {
        assertThatCode(() -> find("(a*)(a*)(a*)", "aaa", f, Disambiguation.PERL))
                .as("(a*)(a*)(a*) should not crash")
                .doesNotThrowAnyException();
    }

    /** The crash also affects POSIX mode. */
    @ParameterizedTest @MethodSource("factories")
    void adjacentStarGroupsPosix(EngineFactory f) {
        assertThatCode(() -> find("(a*)(a*)", "aaa", f, Disambiguation.POSIX))
                .as("POSIX (a*)(a*) should not crash")
                .doesNotThrowAnyException();
    }

    /** Patterns that DON'T crash (second group requires ≥1 char). */
    @ParameterizedTest @MethodSource("factories")
    void nonAdjacentGreedyGroupsWork(EngineFactory f) {
        MatchResult m1 = find("(a*)(a+)", "aaa", f, Disambiguation.PERL);
        assertThat(m1).isNotNull();
        assertThat(m1.end(1)).isEqualTo(2);
        assertThat(m1.start(2)).isEqualTo(2);

        MatchResult m2 = find("(a?)(a*)", "aaa", f, Disambiguation.PERL);
        assertThat(m2).isNotNull();
        assertThat(m2.end(1)).isEqualTo(1);
        assertThat(m2.start(2)).isEqualTo(1);
    }

    // ===== Alternation aliasing (§A area) =====

    @ParameterizedTest @MethodSource("factories")
    void fiveWayAlternationOneGroup(EngineFactory f) {
        Regex r = Regex.compile("(a)|(b)|(c)|(d)|(e)", f, Disambiguation.PERL);
        String[] inputs = {"a", "b", "c", "d", "e"};
        for (int i = 0; i < inputs.length; i++) {
            MatchResult m = r.find(inputs[i], 0);
            assertThat(m).as("match '%s'", inputs[i]).isNotNull();
            assertThat(participating(m)).as("exactly 1 group").isEqualTo(1);
            assertThat(m.start(i + 1)).isEqualTo(0);
            assertThat(m.end(i + 1)).isEqualTo(1);
        }
    }

    @ParameterizedTest @MethodSource("factories")
    void tenWayAlternationVariableLength(EngineFactory f) {
        Regex r = Regex.compile("(aa)|(bb)|(cc)|(dd)|(ee)|(ff)|(gg)|(hh)|(ii)|(jj)",
                f, Disambiguation.PERL);
        String[] inputs = {"aa", "bb", "cc", "dd", "ee", "ff", "gg", "hh", "ii", "jj"};
        for (int i = 0; i < inputs.length; i++) {
            MatchResult m = r.find(inputs[i], 0);
            assertThat(participating(m)).isEqualTo(1);
            assertThat(m.start(i + 1)).isEqualTo(0);
            assertThat(m.end(i + 1)).isEqualTo(2);
        }
    }

    @ParameterizedTest @MethodSource("factories")
    void sharedPrefixAlternation(EngineFactory f) {
        Regex r = Regex.compile("(abc)|(abd)|(abe)", f, Disambiguation.PERL);
        String[] inputs = {"abc", "abd", "abe"};
        for (int i = 0; i < inputs.length; i++) {
            MatchResult m = r.find(inputs[i], 0);
            assertThat(participating(m)).isEqualTo(1);
            assertThat(m.start(i + 1)).isEqualTo(0);
        }
    }

    @ParameterizedTest @MethodSource("factories")
    void miniLexerOneTokenPerMatch(EngineFactory f) {
        Regex r = Regex.compile(
                "([ \\t]+)|(//[^\\n]*)|([0-9]+)|([a-zA-Z_][a-zA-Z0-9_]*)|(.)",
                f, Disambiguation.PERL);
        String input = "foo = 42 // bar";
        int pos = 0, n = 0;
        while (pos <= input.length()) {
            MatchResult m = r.find(input, pos);
            if (m == null) break;
            assertThat(participating(m))
                    .as("match %d ('%s')", n, input.substring(m.start(0), m.end(0)))
                    .isEqualTo(1);
            pos = m.end(0) <= m.start(0) ? m.end(0) + 1 : m.end(0);
            n++;
        }
        assertThat(n).isGreaterThan(3);
    }

    @ParameterizedTest @MethodSource("factories")
    void alternationDifferentLengthsPosix(EngineFactory f) {
        Regex r = Regex.compile("(a)|(abcde)|(xy)", f, Disambiguation.POSIX);
        MatchResult m2 = r.find("abcde", 0);
        assertThat(participating(m2)).isEqualTo(1);
        assertThat(m2.start(2)).isEqualTo(0);
        assertThat(m2.end(2)).isEqualTo(5);
    }

    @ParameterizedTest @MethodSource("factories")
    void manySequentialMatches(EngineFactory f) {
        Regex r = Regex.compile("(cat)|(dog)|(bird)|(fish)", f, Disambiguation.PERL);
        String[] inputs = {"cat", "dog", "bird", "fish", "cat", "dog", "bird", "fish"};
        int[] groups = {1, 2, 3, 4, 1, 2, 3, 4};
        for (int i = 0; i < inputs.length; i++) {
            MatchResult m = r.find(inputs[i], 0);
            assertThat(participating(m)).as("iteration %d", i).isEqualTo(1);
            assertThat(m.start(groups[i])).isEqualTo(0);
        }
    }

    // ===== COPY-chain and nested groups (6691e97 area) =====

    @ParameterizedTest @MethodSource("factories")
    void fourSequentialGroupsCopyChain(EngineFactory f) {
        MatchResult m = find("(a)(b)(c)(d)", "abcd", f, Disambiguation.PERL);
        assertThat(m.start(1)).isEqualTo(0); assertThat(m.end(1)).isEqualTo(1);
        assertThat(m.start(2)).isEqualTo(1); assertThat(m.end(2)).isEqualTo(2);
        assertThat(m.start(3)).isEqualTo(2); assertThat(m.end(3)).isEqualTo(3);
        assertThat(m.start(4)).isEqualTo(3); assertThat(m.end(4)).isEqualTo(4);
    }

    @ParameterizedTest @MethodSource("factories")
    void optionalGroupMiddleCopyChain(EngineFactory f) {
        MatchResult m1 = find("(a)(b)?(c)", "ac", f, Disambiguation.PERL);
        assertThat(m1.start(1)).isEqualTo(0); assertThat(m1.end(1)).isEqualTo(1);
        assertThat(m1.start(2)).isEqualTo(-1);
        assertThat(m1.start(3)).isEqualTo(1); assertThat(m1.end(3)).isEqualTo(2);

        MatchResult m2 = find("(a)(b)?(c)", "abc", f, Disambiguation.PERL);
        assertThat(m2.start(2)).isEqualTo(1); assertThat(m2.end(2)).isEqualTo(2);
    }

    @ParameterizedTest @MethodSource("factories")
    void nestedGroupsCopyChain(EngineFactory f) {
        MatchResult m = find("((a)(b))(c)", "abc", f, Disambiguation.PERL);
        assertThat(m.start(1)).isEqualTo(0); assertThat(m.end(1)).isEqualTo(2);
        assertThat(m.start(2)).isEqualTo(0); assertThat(m.end(2)).isEqualTo(1);
        assertThat(m.start(3)).isEqualTo(1); assertThat(m.end(3)).isEqualTo(2);
        assertThat(m.start(4)).isEqualTo(2); assertThat(m.end(4)).isEqualTo(3);
    }

    @ParameterizedTest @MethodSource("factories")
    void alternationDifferentGroupCounts(EngineFactory f) {
        // (abc(def))|(abc) — branch 1 has 2 groups, branch 2 has 1
        MatchResult m = find("(abc(def))|(abc)", "abcdef", f, Disambiguation.PERL);
        assertThat(m).isNotNull();
        assertThat(m.start(1)).isEqualTo(0);
        assertThat(m.start(2)).isEqualTo(3);
        assertThat(m.start(3)).isEqualTo(-1);
    }

    @ParameterizedTest @MethodSource("factories")
    void emptyAlternationBranch(EngineFactory f) {
        MatchResult m = find("(a)|()|(b)", "x", f, Disambiguation.PERL);
        assertThat(m).isNotNull();
        // PERL: first branch 'a' fails, second '' matches at pos 0
        assertThat(m.start(1)).isEqualTo(-1);
        assertThat(m.start(2)).isEqualTo(0);
        assertThat(m.end(2)).isEqualTo(0);
    }

    // ===== Bounded repeat capture =====

    @ParameterizedTest @MethodSource("factories")
    void boundedRepeatCapture(EngineFactory f) {
        MatchResult m = find("(a){2,4}", "aaaa", f, Disambiguation.PERL);
        assertThat(m).isNotNull();
        assertThat(m.end(0)).isEqualTo(4);
        assertThat(m.start(1)).isEqualTo(3); // last iteration
        assertThat(m.end(1)).isEqualTo(4);
    }

    @ParameterizedTest @MethodSource("factories")
    void nestedAlternationInRepetition(EngineFactory f) {
        MatchResult m = find("((a|b)(c|d))+", "abcd", f, Disambiguation.PERL);
        assertThat(m).isNotNull();
        // Only one iteration matches at [1,3): b then c
        assertThat(m.start(1)).isEqualTo(1); assertThat(m.end(1)).isEqualTo(3);
    }

    // ===== Fallback states (§6.2 area) =====

    @ParameterizedTest @MethodSource("factories")
    void fallbackPreservesCapture(EngineFactory f) {
        Regex r = Regex.compile("([A-Z][a-z]+)+(,)?", f, Disambiguation.POSIX);
        MatchResult m = r.find("HelloW", 0);
        assertThat(m).isNotNull();
        assertThat(m.end(0)).isEqualTo(5);
        assertThat(m.start(1)).isEqualTo(0);
        assertThat(m.end(1)).isEqualTo(5);
    }

    @ParameterizedTest @MethodSource("factories")
    void optionalGroupFallback(EngineFactory f) {
        Regex r = Regex.compile("(a)(b)?", f, Disambiguation.POSIX);
        MatchResult m = r.find("ax", 0);
        assertThat(m).isNotNull();
        assertThat(m.end(0)).isEqualTo(1);
        assertThat(m.start(1)).isEqualTo(0);
        assertThat(m.end(1)).isEqualTo(1);
        assertThat(m.start(2)).isEqualTo(-1);
    }

    @ParameterizedTest @MethodSource("factories")
    void quantifiedGroupFallback(EngineFactory f) {
        MatchResult m = find("(\\d+\\.)+\\d+", "1.2.x", f, Disambiguation.POSIX);
        assertThat(m).isNotNull();
        assertThat(m.end(0)).isEqualTo(3); // "1.2"
    }
}
