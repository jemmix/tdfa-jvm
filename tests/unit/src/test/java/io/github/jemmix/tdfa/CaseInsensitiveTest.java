package io.github.jemmix.tdfa;

import io.github.jemmix.tdfa.tdfa.Disambiguation;
import io.github.jemmix.tdfa.unicode.CaseFoldTable;
import io.github.jemmix.tdfa.vm.MatchResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Case-insensitive matching: literal char folding, character-class folding,
 * and range folding under {@code (?i)} / {@code (?iu)}.
 *
 * <p>Problem areas:
 * <ul>
 *   <li><b>Literal fold</b> — {@code (?iu)s} matches {@code ſ} (U+017F) via
 *       {@link CaseFoldTable}. Without {@code (?u)}, folding is ASCII-only.</li>
 *   <li><b>Char-class range fold</b> — {@code (?iu)[r-t]} includes
 *       {@code ſ} (fold-equivalent of {@code s} which is in the range) via
 *       {@code CaseFoldTable} expansion of every member codepoint.</li>
 *   <li><b>Negated class fold</b> — {@code (?iu)[^s]} excludes
 *       {@code ſ} (fold members are added to the positive set before
 *       negation applies).</li>
 * </ul>
 */
class CaseInsensitiveTest {

    private static Stream<EngineFactory> factories() {
        return Stream.of(EngineFactory.ASM, EngineFactory.VM);
    }

    private static MatchResult match(String pattern, String input, EngineFactory f) {
        return Regex.compile(pattern, f, Disambiguation.PERL).find(input, 0);
    }

    // ===== CaseFoldTable unit tests =====

    @Test void foldTableGroupForS() {
        int[] r = CaseFoldTable.foldRanges('s');
        assertThat(r).isNotNull();
        assertThat(containsCp(r, 's')).isTrue();
        assertThat(containsCp(r, 'S')).isTrue();
        assertThat(containsCp(r, 0x017F)).as("ſ (U+017F)").isTrue();
    }

    @Test void foldTableGroupForK() {
        int[] r = CaseFoldTable.foldRanges('k');
        assertThat(r).isNotNull();
        assertThat(containsCp(r, 0x212A)).as("K (U+212A Kelvin)").isTrue();
    }

    @Test void foldTableGroupForOmega() {
        int[] r = CaseFoldTable.foldRanges(0x03A9);
        assertThat(r).isNotNull();
        assertThat(containsCp(r, 0x03C9)).as("ω").isTrue();
        assertThat(containsCp(r, 0x2126)).as("Ω (U+2126 Ohm)").isTrue();
    }

    @Test void foldTableNullForNonLetter() {
        assertThat(CaseFoldTable.foldRanges('5')).isNull();
        assertThat(CaseFoldTable.foldRanges('!')).isNull();
    }

    @Test void foldTableSymmetric() {
        int[] a = CaseFoldTable.foldRanges('s');
        assertThat(a).isEqualTo(CaseFoldTable.foldRanges('S'));
        assertThat(a).isEqualTo(CaseFoldTable.foldRanges(0x017F));
    }

    private static boolean containsCp(int[] ranges, int cp) {
        for (int i = 0; i + 1 < ranges.length; i += 2)
            if (cp >= ranges[i] && cp <= ranges[i + 1]) return true;
        return false;
    }

    // ===== (?iu) literal fold =====

    @ParameterizedTest @MethodSource("factories")
    void unicodeFoldLiteralS(EngineFactory f) {
        assertThat(match("(?iu)s", "\u017F", f)).as("(?iu)s → ſ").isNotNull();
        assertThat(match("(?iu)S", "\u017F", f)).as("(?iu)S → ſ").isNotNull();
    }

    @ParameterizedTest @MethodSource("factories")
    void unicodeFoldLiteralK(EngineFactory f) {
        assertThat(match("(?iu)k", "\u212A", f)).as("(?iu)k → K").isNotNull();
    }

    @ParameterizedTest @MethodSource("factories")
    void unicodeFoldLiteralOmega(EngineFactory f) {
        assertThat(match("(?iu)\u03A9", "\u03C9", f)).as("(?iu)Ω → ω").isNotNull();
    }

    /** Without (?u), folding is ASCII-only: (?i)s must NOT match ſ. */
    @ParameterizedTest @MethodSource("factories")
    void asciiFoldDoesNotMatchLongS(EngineFactory f) {
        assertThat(match("(?i)s", "\u017F", f)).isNull();
    }

    @ParameterizedTest @MethodSource("factories")
    void unicodeFoldMultiCharLiteral(EngineFactory f) {
        assertThat(match("(?iu)ss", "\u017Fs", f)).isNotNull();
        assertThat(match("(?iu)ss", "s\u017F", f)).isNotNull();
        assertThat(match("(?iu)ss", "\u017F\u017F", f)).isNotNull();
    }

    @ParameterizedTest @MethodSource("factories")
    void unicodeFoldQuotedLiteral(EngineFactory f) {
        assertThat(match("(?iu)\\Qs\\E", "\u017F", f)).isNotNull();
    }

    @ParameterizedTest @MethodSource("factories")
    void unicodeFoldWithCapture(EngineFactory f) {
        MatchResult m = match("(?iu)(s)", "\u017F", f);
        assertThat(m).isNotNull();
        assertThat(m.start(1)).isEqualTo(0);
        assertThat(m.end(1)).isEqualTo(1);
    }

    @ParameterizedTest @MethodSource("factories")
    void unicodeFoldInAlternation(EngineFactory f) {
        MatchResult m = match("(?iu)(s|t)", "\u017F", f);
        assertThat(m).isNotNull();
        assertThat(m.start(1)).isEqualTo(0);
    }

    @ParameterizedTest @MethodSource("factories")
    void unicodeFoldNoFalseMatch(EngineFactory f) {
        assertThat(match("(?iu)s", "x", f)).isNull();
        assertThat(match("(?iu)s", "5", f)).isNull();
    }

    @ParameterizedTest @MethodSource("factories")
    void unicodeFoldRepetition(EngineFactory f) {
        MatchResult m = match("(?iu)(s+)", "\u017F\u017F", f);
        assertThat(m).isNotNull();
        assertThat(m.start(1)).isEqualTo(0);
        assertThat(m.end(1)).isEqualTo(2);
    }

    @ParameterizedTest @MethodSource("factories")
    void foldToggleOffMidPattern(EngineFactory f) {
        assertThat(match("(?iu)s(?-i)s", "\u017Fs", f)).isNotNull();
        assertThat(match("(?iu)s(?-i)s", "\u017FS", f)).isNull();
    }

    @ParameterizedTest @MethodSource("factories")
    void foldWithDotall(EngineFactory f) {
        MatchResult m = match("(?isu)s.", "\u017F\nx", f);
        assertThat(m).isNotNull();
        assertThat(m.end(0)).isEqualTo(2);
    }

    // ===== Char-class range fold =====

    /**
     * {@code (?iu)[r-t]} includes {@code ſ} (U+017F) because
     * {@code s} is in the range [r,t] and {@code ſ} is fold-equivalent to
     * {@code s}. Currently {@code parseClass} only adds ASCII a-z/A-z
     * counterparts. java.util.regex matches ſ here; our engine does not.
     */
    @ParameterizedTest @MethodSource("factories")
    void rangeClassShouldIncludeFoldEquivalent(EngineFactory f) {
        MatchResult m = match("(?iu)[r-t]", "\u017F", f);
        assertThat(m)
                .as("(?iu)[r-t] should match ſ (fold-equiv of s in range) — BUG: returns null")
                .isNotNull();
    }

    @ParameterizedTest @MethodSource("factories")
    void rangeClassAsciiFold(EngineFactory f) {
        assertThat(match("(?iu)[a-z]", "G", f)).isNotNull();
        assertThat(match("(?iu)[A-Z]", "g", f)).isNotNull();
    }

    // ===== Negated class fold =====

    /**
     * {@code (?iu)[^s]} must NOT match {@code ſ} (U+017F) because
     * {@code ſ} is fold-equivalent to {@code s}. The negated class should
     * exclude all fold-equivalents. java.util.regex returns null (no match);
     * our engine incorrectly matches.
     */
    @ParameterizedTest @MethodSource("factories")
    void negatedClassShouldExcludeFoldEquivalent(EngineFactory f) {
        MatchResult m = match("(?iu)[^s]", "\u017F", f);
        assertThat(m)
                .as("(?iu)[^s] should NOT match ſ — BUG: returns non-null")
                .isNull();
    }

    @ParameterizedTest @MethodSource("factories")
    void negatedClassExcludesAsciiFold(EngineFactory f) {
        // (?i)[^s] on S — should NOT match (S is fold-equiv of s)
        assertThat(match("(?i)[^s]", "S", f)).isNull();
        // (?i)[^s] on x — SHOULD match
        assertThat(match("(?i)[^s]", "x", f)).isNotNull();
    }

    // ===== ASCII char-class fold (works correctly) =====

    @ParameterizedTest @MethodSource("factories")
    void asciiCharClassFold(EngineFactory f) {
        assertThat(match("(?iu)[sx]", "S", f)).isNotNull();
    }

    @ParameterizedTest @MethodSource("factories")
    void foldClassSingleChar(EngineFactory f) {
        assertThat(match("(?i)a", "A", f)).isNotNull();
    }

    @ParameterizedTest @MethodSource("factories")
    void foldLiteralInConcat(EngineFactory f) {
        assertThat(match("(?i)hello", "HeLLo", f)).isNotNull();
    }

    @ParameterizedTest @MethodSource("factories")
    void scopedCaseInsensitiveNoLeak(EngineFactory f) {
        assertThat(match("a(?i:bc)d", "aBCd", f)).isNotNull();
        assertThat(match("a(?i:bc)d", "abcd", f)).isNotNull();
    }

    @ParameterizedTest @MethodSource("factories")
    void negatedClassUnderCi(EngineFactory f) {
        assertThat(match("(?i)[^a-z]", "A", f)).isNull();
        assertThat(match("(?i)[^a-z]", "5", f)).isNotNull();
    }

    // PENDING: JdkUnicodeDataProvider.foldTableFor("Ll") returns null (only Lu
    // direction implemented; Greek/Cyrillic/L partial). (?i)\p{Ll} on 'A' fails with
    // the default JDK provider. java.util.regex matches. (Parity suite misses this:
    // it uses Re2jUnicodeProvider which has complete fold tables.)
    @EnabledIfSystemProperty(named = "tdfa.pending", matches = "true")
    @ParameterizedTest @MethodSource("factories")
    void unicodePropertyFold(EngineFactory f) {
        assertThat(match("(?i)\\p{Ll}", "A", f)).isNotNull();
        assertThat(match("(?i)\\p{Lu}", "a", f)).isNotNull();
        assertThat(match("(?i)\\p{Greek}", "\u0391", f)).isNotNull();
    }

    @ParameterizedTest @MethodSource("factories")
    void findAllFoldEquivalentsInStream(EngineFactory f) {
        Regex r = Regex.compile("(?iu)s", f, Disambiguation.PERL);
        String input = "s S \u017F s";
        int count = 0, pos = 0;
        while (pos <= input.length()) {
            MatchResult m = r.find(input, pos);
            if (m == null) break;
            count++;
            pos = m.end(0) <= m.start(0) ? m.end(0) + 1 : m.end(0);
        }
        assertThat(count).isEqualTo(4);
    }
}
