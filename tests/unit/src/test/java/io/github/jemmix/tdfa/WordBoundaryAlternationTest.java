package io.github.jemmix.tdfa;

import io.github.jemmix.tdfa.tdfa.Disambiguation;
import io.github.jemmix.tdfa.vm.MatchResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Word boundary {@code \b} in alternation: the §B fix for dead-end DFA paths.
 *
 * <p>Before the fix, a regex like {@code (\bas\b)|([a-z]+)} produced two
 * separate DFA states for "after a letter at a \b" (keyword path) and
 * "after a letter, no \b" (identifier path). The \b-guarded path only had
 * transitions for keyword continuations, so non-keyword identifiers
 * dead-ended and were never reported as matches via the identifier branch.
 *
 * <p>The fix: when processing mask group M, also step configs from subset-mask
 * groups (mask ⊆ M), so the \b-guarded transition's target includes both
 * keyword AND identifier continuations.
 *
 * <p>Each test runs on both ASM and VM backends.
 */
class WordBoundaryAlternationTest {

    private static Stream<EngineFactory> factories() {
        return Stream.of(EngineFactory.ASM, EngineFactory.VM);
    }

    private static MatchResult find(String pattern, String input, EngineFactory factory) {
        Regex r = Regex.compile(pattern, factory, Disambiguation.PERL);
        return r.find(input, 0);
    }

    /**
     * Minimal repro: after 'a' at a \b, the keyword path dead-ends but the
     * identifier path should match. Before the fix, "a" was not matched at all.
     */
    @ParameterizedTest
    @MethodSource("factories")
    void keywordGuardedDeadEndMatchesAsIdentifier(EngineFactory factory) {
        // (\bas\b)|(a) on "a": 'a' doesn't complete the keyword 'as', but
        // the identifier branch (literal 'a') should match.
        MatchResult m = find("(\\bas\\b)|(a)", "a", factory);
        assertThat(m).as("should match 'a' via identifier branch").isNotNull();
        assertThat(m.start(0)).isEqualTo(0);
        assertThat(m.end(0)).isEqualTo(1);
    }

    /**
     * The same regex on "as" should match via the keyword branch (group 1).
     */
    @ParameterizedTest
    @MethodSource("factories")
    void keywordBranchMatchesWhenComplete(EngineFactory factory) {
        MatchResult m = find("(\\bas\\b)|(a)", "as", factory);
        assertThat(m).as("should match 'as' via keyword branch").isNotNull();
        assertThat(m.start(0)).isEqualTo(0);
        assertThat(m.end(0)).isEqualTo(2);
    }

    /**
     * Lexer-style: (\bkeyword\b)|(identifier) — a char that starts a keyword
     * prefix but isn't the full keyword must still match as an identifier.
     */
    @ParameterizedTest
    @MethodSource("factories")
    void lexerStylePrefixOfKeywordMatchesAsIdentifier(EngineFactory factory) {
        // "alwa" starts "always" but isn't the keyword — must match as identifier.
        Regex r = Regex.compile("(\\balways\\b)|([a-zA-Z_][a-zA-Z0-9_]*)",
                factory, Disambiguation.PERL);
        MatchResult m = r.find("alwa", 0);
        assertThat(m).as("'alwa' should match as identifier").isNotNull();
        assertThat(m.start(0)).isEqualTo(0);
        assertThat(m.end(0)).isEqualTo(4);
        assertThat(m.start(1)).isEqualTo(-1); // keyword didn't match
        assertThat(m.start(2)).isEqualTo(0);  // identifier matched
        assertThat(m.end(2)).isEqualTo(4);
    }

    /**
     * Full keyword match: "always" should match via the keyword branch.
     */
    @ParameterizedTest
    @MethodSource("factories")
    void lexerStyleKeywordMatchesViaKeywordBranch(EngineFactory factory) {
        Regex r = Regex.compile("(\\balways\\b)|([a-zA-Z_][a-zA-Z0-9_]*)",
                factory, Disambiguation.PERL);
        MatchResult m = r.find("always", 0);
        assertThat(m).as("should match 'always'").isNotNull();
        assertThat(m.start(0)).isEqualTo(0);
        assertThat(m.end(0)).isEqualTo(6);
        assertThat(m.start(1)).isEqualTo(0);
        assertThat(m.end(1)).isEqualTo(6);
    }

    /**
     * Keyword at word boundary: "always " (trailing space provides \b).
     */
    @ParameterizedTest
    @MethodSource("factories")
    void lexerStyleKeywordAtBoundary(EngineFactory factory) {
        Regex r = Regex.compile("(\\balways\\b)|([a-zA-Z_][a-zA-Z0-9_]*)",
                factory, Disambiguation.PERL);
        MatchResult m = r.find("always ", 0);
        assertThat(m).as("should match 'always' at word boundary").isNotNull();
        assertThat(m.end(0)).isEqualTo(6);
        assertThat(m.start(1)).isEqualTo(0);
    }

    /**
     * Multiple keywords in a stream: each should match correctly.
     * "a as always b" — "a" is identifier, "as" is keyword, etc.
     */
    @ParameterizedTest
    @MethodSource("factories")
    void lexerStyleMultipleTokensInStream(EngineFactory factory) {
        Regex r = Regex.compile("(\\bas\\b)|(\\balways\\b)|([a-zA-Z_]+)",
                factory, Disambiguation.PERL);
        String input = "a as always b";
        int[] expectedEnds = {1, 4, 11, 13};
        int pos = 0;
        int idx = 0;
        while (pos <= input.length()) {
            MatchResult m = r.find(input, pos);
            if (m == null) break;
            assertThat(m.end(0))
                    .as("token %d ('%s') end", idx, input.substring(m.start(0), m.end(0)))
                    .isEqualTo(expectedEnds[idx]);
            pos = m.end(0);
            idx++;
        }
        assertThat(idx).as("should find 4 tokens").isEqualTo(4);
    }

    /**
     * Many short identifiers that are keyword prefixes: stress test for the
     * subset-mask inclusion. Each single char should match as identifier.
     */
    @ParameterizedTest
    @MethodSource("factories")
    void manyKeywordPrefixCharsAllMatch(EngineFactory factory) {
        Regex r = Regex.compile("(\\bcat\\b)|(\\bdog\\b)|(\\bbird\\b)|([a-z]+)",
                factory, Disambiguation.PERL);
        // "c" starts "cat" but isn't "cat" — must match as identifier.
        // "d" starts "dog" — same. "b" starts "bird" — same.
        for (String input : new String[]{"c", "d", "b", "ca", "do", "bi"}) {
            MatchResult m = r.find(input, 0);
            assertThat(m).as("'%s' should match as identifier", input).isNotNull();
            assertThat(m.start(0)).isEqualTo(0);
            assertThat(m.end(0)).isEqualTo(input.length());
            assertThat(m.start(1)).isEqualTo(-1); // no keyword matched
            assertThat(m.start(2)).isEqualTo(-1);
            assertThat(m.start(3)).isEqualTo(-1);
            assertThat(m.start(4)).isEqualTo(0);  // identifier branch
        }
    }

    /**
     * Non-keyword that starts with keyword: "catalog" — \bcat\b should NOT
     * match (no boundary after "cat"), so the identifier branch matches all.
     */
    @ParameterizedTest
    @MethodSource("factories")
    void keywordPrefixInLongerWordDoesNotTriggerKeywordBranch(EngineFactory factory) {
        Regex r = Regex.compile("(\\bcat\\b)|([a-z]+)", factory, Disambiguation.PERL);
        MatchResult m = r.find("catalog", 0);
        assertThat(m).as("should match all of 'catalog'").isNotNull();
        assertThat(m.start(0)).isEqualTo(0);
        assertThat(m.end(0)).isEqualTo(7);
        assertThat(m.start(1)).isEqualTo(-1); // \bcat\b didn't match (no trailing \b)
    }

    /**
     * \b at start of input (boundary between nothing and word char).
     */
    @ParameterizedTest
    @MethodSource("factories")
    void wordBoundaryAtStartOfInput(EngineFactory factory) {
        // (\bas)|(x) on "as" — \b holds at position 0 (start of text → word char).
        MatchResult m = find("(\\bas)|(x)", "as", factory);
        assertThat(m).as("should match 'as' via \\bas").isNotNull();
        assertThat(m.end(0)).isEqualTo(2);
    }
}
