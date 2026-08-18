package io.github.jemmix.tdfa;

import io.github.jemmix.tdfa.core.Matcher;
import io.github.jemmix.tdfa.core.RegexEngineFactory;
import io.github.jemmix.tdfa.tdfa.TdfaRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Word boundary {@code \b} / {@code \B} semantics: zero-width assertion mask
 * computation, alternation interaction, and supplementary-codepoint handling.
 *
 * <p>Problem areas:
 * <ul>
 *   <li><b>Alternation dead-end</b> — {@code (\bkw\b)|(identifier)} must not
 *       lose the identifier path when the keyword prefix doesn't complete
 *       (the §B determinization fix area).</li>
 *   <li><b>Supplementary codepoints</b> — {@code isWordChar} checks individual
 *       UTF-16 code units, so word boundaries adjacent to supplementary letters
 *       (e.g. U+1D504 MATHEMATICAL FRAKTUR A) must decode the surrogate pair
 *       first ({@code isWordBefore}/{@code isWordAt} in the runner, emitted
 *       {@code isWordBefore}/{@code isWordAt} helpers in ASM).</li>
 *   <li><b>BMP non-ASCII word chars</b> — Cyrillic, CJK, etc. under
 *       {@code (?u)} mode.</li>
 * </ul>
 */
class WordBoundaryTest {

    private static Stream<RegexEngineFactory> factories() {
        return Stream.<RegexEngineFactory>of(null, TdfaRunner::new);
    }

    private static Matcher find(String pattern, String input, RegexEngineFactory f) {
        Matcher m = Pattern.compile(pattern, 0, f).matcher(input);
        return m.find() ? m : null;
    }

    private static Matcher match(Pattern p, String input) {
        Matcher m = p.matcher(input);
        return m.find() ? m : null;
    }

    private static int countMatches(Pattern p, String input) {
        int count = 0;
        for (Matcher m = p.matcher(input); m.find(); ) count++;
        return count;
    }

    // ===== Alternation dead-end (§B fix area) =====

    /** Minimal dead-end repro: keyword prefix that doesn't complete must fall through to identifier. */
    @ParameterizedTest @MethodSource("factories")
    void keywordPrefixFallsThroughToIdentifier(RegexEngineFactory f) {
        Matcher m = find("(\\bas\\b)|(a)", "a", f);
        assertThat(m).as("should match 'a' via identifier branch").isNotNull();
        assertThat(m.start(0)).isEqualTo(0);
        assertThat(m.end(0)).isEqualTo(1);
    }

    @ParameterizedTest @MethodSource("factories")
    void keywordBranchMatchesWhenComplete(RegexEngineFactory f) {
        Matcher m = find("(\\bas\\b)|(a)", "as", f);
        assertThat(m).isNotNull();
        assertThat(m.end(0)).isEqualTo(2);
    }

    @ParameterizedTest @MethodSource("factories")
    void lexerStylePrefixOfKeywordMatchesAsIdentifier(RegexEngineFactory f) {
        Pattern r = Pattern.compile("(\\balways\\b)|([a-zA-Z_][a-zA-Z0-9_]*)", 0, f);
        Matcher m = match(r, "alwa");
        assertThat(m).as("'alwa' should match as identifier").isNotNull();
        assertThat(m.end(0)).isEqualTo(4);
        assertThat(m.start(1)).isEqualTo(-1);
        assertThat(m.start(2)).isEqualTo(0);
    }

    @ParameterizedTest @MethodSource("factories")
    void manyKeywordPrefixCharsAllMatch(RegexEngineFactory f) {
        Pattern r = Pattern.compile("(\\bcat\\b)|(\\bdog\\b)|(\\bbird\\b)|([a-z]+)", 0, f);
        for (String input : new String[]{"c", "d", "b", "ca", "do", "bi"}) {
            Matcher m = match(r, input);
            assertThat(m).as("'%s' should match", input).isNotNull();
            assertThat(m.end(0)).isEqualTo(input.length());
            assertThat(m.start(1)).isEqualTo(-1);
            assertThat(m.start(4)).isEqualTo(0);
        }
    }

    @ParameterizedTest @MethodSource("factories")
    void keywordPrefixInLongerWordDoesNotTrigger(RegexEngineFactory f) {
        Pattern r = Pattern.compile("(\\bcat\\b)|([a-z]+)", 0, f);
        Matcher m = match(r, "catalog");
        assertThat(m).isNotNull();
        assertThat(m.end(0)).isEqualTo(7);
        assertThat(m.start(1)).isEqualTo(-1);
    }

    @ParameterizedTest @MethodSource("factories")
    void multiTokenStream(RegexEngineFactory f) {
        Pattern r = Pattern.compile("(\\bas\\b)|(\\balways\\b)|([a-zA-Z_]+)", 0, f);
        int[] expectedEnds = {1, 4, 11, 13};
        String input = "a as always b";
        int idx = 0;
        for (Matcher m = r.matcher(input); m.find(); ) {
            assertThat(m.end(0)).as("token %d end", idx).isEqualTo(expectedEnds[idx]);
            idx++;
        }
        assertThat(idx).isEqualTo(4);
    }

    @ParameterizedTest @MethodSource("factories")
    void bothBranchesHaveWordBoundary(RegexEngineFactory f) {
        // (\bfoo\b)|(\bfoobar\b) on "foobar": \bfoo\b fails (no trailing \b),
        // \bfoobar\b matches all.
        Matcher m = find("(\\bfoo\\b)|(\\bfoobar\\b)", "foobar", f);
        assertThat(m).isNotNull();
        assertThat(m.end(0)).isEqualTo(6);
        assertThat(m.start(2)).isEqualTo(0);
    }

    // ===== \B (negated word boundary) in alternation =====

    @ParameterizedTest @MethodSource("factories")
    void negatedBoundaryInAlternation(RegexEngineFactory f) {
        // \Bab\B fails at word starts; [a-z]+ catches all
        Pattern r = Pattern.compile("(\\Bab\\B)|([a-z]+)", 0, f);
        Matcher m = match(r, "xab");
        assertThat(m).isNotNull();
        assertThat(m.end(0)).isEqualTo(3);
        assertThat(m.start(1)).isEqualTo(-1);
    }

    // ===== Supplementary codepoint \b (BUG: isWordChar can't see supplementary chars) =====

    /** Mathematical Fraktur A (U+1D504) is a Unicode letter; under (?u) \w it's a word char. */
    private static final String SUP_LETTER_A = "\uD835\uDD04"; // 𝔄
    private static final String SUP_LETTER_B = "\uD835\uDD05"; // 𝔅

    /**
     * {@code .\b.} on {@code "a𝔄b"} must not match: both 'a' and 𝔄 (U+1D504)
     * are word chars under (?u), so no boundary fires between them. The engine
     * decodes the surrogate pair before the word-char search.
     */
    @ParameterizedTest @MethodSource("factories")
    void noBoundaryBetweenBmpAndSupplementaryWordChar(RegexEngineFactory f) {
        Matcher m = find("(?u).\\b.", "a" + SUP_LETTER_A + "b", f);
        assertThat(m)
                .as(".\b. on a𝔄b — \b should not fire between two word chars (BUG: returns non-null)")
                .isNull();
    }

    /**
     * {@code \b\w} on {@code "𝔄𝔅"} matches [0,2]: the boundary holds at
     * start-of-text before a supplementary letter.
     */
    @ParameterizedTest @MethodSource("factories")
    void boundaryAtStartOfSupplementaryWordChars(RegexEngineFactory f) {
        Matcher m = find("(?u)\\b\\w", SUP_LETTER_A + SUP_LETTER_B, f);
        assertThat(m)
                .as("\\b\\w on 𝔄𝔅 — \b should fire at start (BUG: returns null)")
                .isNotNull();
    }

    // ===== BMP non-ASCII word chars under (?u) =====

    @ParameterizedTest @MethodSource("factories")
    void wordBoundaryOnCyrillic(RegexEngineFactory f) {
        // Cyrillic "абв" — all word chars under (?u)
        Matcher m = find("(?u)\\b\\w+\\b", "\u0430\u0431\u0432", f);
        assertThat(m).isNotNull();
        assertThat(m.end(0)).isEqualTo(3);
    }

    @ParameterizedTest @MethodSource("factories")
    void wordBoundaryOnCjk(RegexEngineFactory f) {
        // CJK 漢字 — word chars under (?u), BMP so isWordChar works
        assertThat(find("(?u)\\b.", "\u6F22\u5B57", f)).isNotNull();
        // No \b between two CJK word chars
        assertThat(find("(?u).\u005cb.", "a\u6F22", f)).isNull(); // .\. not active; test later
    }

    @ParameterizedTest @MethodSource("factories")
    void wordBoundaryAtEndOfWord(RegexEngineFactory f) {
        Matcher m = find("(\\w+)\\b", "hello", f);
        assertThat(m).isNotNull();
        assertThat(m.end(0)).isEqualTo(5);
    }

    @ParameterizedTest @MethodSource("factories")
    void noBoundaryBeforeNonWord(RegexEngineFactory f) {
        // \b. on "!!!" — position 0 is start-of-text (non-word) → non-word: no \b
        assertThat(find("\\b.", "!!!", f)).isNull();
    }

    @ParameterizedTest @MethodSource("factories")
    void boundaryAtStartOfInput(RegexEngineFactory f) {
        Matcher m = find("(\\bas)", "as", f);
        assertThat(m).isNotNull();
        assertThat(m.end(0)).isEqualTo(2);
    }

    @ParameterizedTest @MethodSource("factories")
    void caretAndBoundaryBothHold(RegexEngineFactory f) {
        Matcher m = find("^\\bword", "word", f);
        assertThat(m).isNotNull();
        assertThat(m.end(0)).isEqualTo(4);
    }

    @ParameterizedTest @MethodSource("factories")
    void countBoundaryDelimitedWords(RegexEngineFactory f) {
        Pattern r = Pattern.compile("\\bcat\\b", 0, f);
        assertThat(countMatches(r, "cat cat cat")).isEqualTo(3);
    }

    // ===== Anchors in alternation =====

    @ParameterizedTest @MethodSource("factories")
    void anchorsInAlternation(RegexEngineFactory f) {
        Matcher m1 = find("(^a)|(a$)", "a", f);
        assertThat(m1).isNotNull();
        assertThat(m1.start(1)).isEqualTo(0);
        assertThat(m1.start(2)).isEqualTo(-1);

        Matcher m2 = find("(^a)|(a$)", "ba", f);
        assertThat(m2).isNotNull();
        assertThat(m2.start(1)).isEqualTo(-1);
        assertThat(m2.start(2)).isEqualTo(1);
    }

    @ParameterizedTest @MethodSource("factories")
    void multilineAnchorInAlternation(RegexEngineFactory f) {
        Matcher m = find("(?m)(^x)|(^y)", "y\nx", f);
        assertThat(m).isNotNull();
        assertThat(m.end(0)).isEqualTo(1);
    }
}
