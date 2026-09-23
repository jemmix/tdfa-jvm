package io.github.jemmix.tdfa;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jemmix.tdfa.core.Matcher;
import io.github.jemmix.tdfa.core.RegexEngineFactory;
import io.github.jemmix.tdfa.tdfa.TdfaRunner;
import io.github.jemmix.tdfa.unicode.CaseFoldTable;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

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

    private static Stream<RegexEngineFactory> factories() {
        return Stream.<RegexEngineFactory>of(null, TdfaRunner::new);
    }

    private static Matcher match(String pattern, String input, RegexEngineFactory f) {
        Matcher m = Pattern.compile(pattern, 0, f).matcher(input);
        return m.find() ? m : null;
    }

    // ===== CaseFoldTable unit tests =====

    @Test
    void foldTableGroupForS() {
        int[] r = CaseFoldTable.foldRanges('s');
        assertThat(r).isNotNull();
        assertThat(containsCp(r, 's')).isTrue();
        assertThat(containsCp(r, 'S')).isTrue();
        assertThat(containsCp(r, 0x017F)).as("ſ (U+017F)").isTrue();
    }

    @Test
    void foldTableGroupForK() {
        int[] r = CaseFoldTable.foldRanges('k');
        assertThat(r).isNotNull();
        assertThat(containsCp(r, 0x212A)).as("K (U+212A Kelvin)").isTrue();
    }

    @Test
    void foldTableGroupForOmega() {
        int[] r = CaseFoldTable.foldRanges(0x03A9);
        assertThat(r).isNotNull();
        assertThat(containsCp(r, 0x03C9)).as("ω").isTrue();
        assertThat(containsCp(r, 0x2126)).as("Ω (U+2126 Ohm)").isTrue();
    }

    @Test
    void foldTableNullForNonLetter() {
        assertThat(CaseFoldTable.foldRanges('5')).isNull();
        assertThat(CaseFoldTable.foldRanges('!')).isNull();
    }

    @Test
    void foldTableSymmetric() {
        int[] a = CaseFoldTable.foldRanges('s');
        assertThat(a).isEqualTo(CaseFoldTable.foldRanges('S'));
        assertThat(a).isEqualTo(CaseFoldTable.foldRanges(0x017F));
    }

    /** Simple case folding keeps the Turkic İ/ı pair out of the i-orbit;
     *  re2j and Go agree. JDK case mapping alone would merge
     *  {I, i, İ, ı} — the fold table pins the pair inert. */
    @Test
    void foldTableTurkicIPairInert() {
        assertThat(CaseFoldTable.foldRanges(0x0130)).as("İ (U+0130)").isNull();
        assertThat(CaseFoldTable.foldRanges(0x0131)).as("ı (U+0131)").isNull();
        int[] i = CaseFoldTable.foldRanges('i');
        assertThat(i).isNotNull();
        assertThat(containsCp(i, 'i')).isTrue();
        assertThat(containsCp(i, 'I')).isTrue();
        assertThat(containsCp(i, 0x0130)).as("i-orbit must not contain İ").isFalse();
        assertThat(containsCp(i, 0x0131)).as("i-orbit must not contain ı").isFalse();
    }

    /** Cyrillic historic letters (U+1C80..U+1C88, Unicode 9.0) fold onto
     *  their partner letters — full modern orbits, both directions. */
    @Test
    void foldTableHistoricCyrillicOrbits() {
        int[] ve = CaseFoldTable.foldRanges(0x1C80); // Ꚁ ↔ В/в
        assertThat(containsCp(ve, 0x0412)).as("В").isTrue();
        assertThat(containsCp(ve, 0x0432)).as("в").isTrue();
        assertThat(CaseFoldTable.foldRanges(0x0432)).isEqualTo(ve);
        int[] te = CaseFoldTable.foldRanges(0x0442); // т ↔ Т/Ꚅ/ꚅ (4-member)
        assertThat(containsCp(te, 0x0422)).as("Т").isTrue();
        assertThat(containsCp(te, 0x1C84)).as("Ꚅ").isTrue();
        assertThat(containsCp(te, 0x1C85)).as("ꚅ").isTrue();
        assertThat(CaseFoldTable.foldRanges(0x1C85)).isEqualTo(te);
    }

    private static boolean containsCp(int[] ranges, int cp) {
        for (int i = 0; i + 1 < ranges.length; i += 2) {
            if (cp >= ranges[i] && cp <= ranges[i + 1]) {
                return true;
            }
        }
        return false;
    }

    // ===== (?iu) literal fold =====

    @ParameterizedTest
    @MethodSource("factories")
    void unicodeFoldLiteralS(RegexEngineFactory f) {
        assertThat(match("(?iu)s", "\u017F", f)).as("(?iu)s → ſ").isNotNull();
        assertThat(match("(?iu)S", "\u017F", f)).as("(?iu)S → ſ").isNotNull();
    }

    @ParameterizedTest
    @MethodSource("factories")
    void unicodeFoldLiteralK(RegexEngineFactory f) {
        assertThat(match("(?iu)k", "\u212A", f)).as("(?iu)k → K").isNotNull();
    }

    @ParameterizedTest
    @MethodSource("factories")
    void unicodeFoldLiteralOmega(RegexEngineFactory f) {
        assertThat(match("(?iu)\u03A9", "\u03C9", f)).as("(?iu)Ω → ω").isNotNull();
    }

    /** Plain (?i) folds FULL Unicode simple folding (re2j parity — verified
     *  against re2j 1.8: (?i)s matches ſ, (?i)k matches K). */
    @ParameterizedTest
    @MethodSource("factories")
    void plainIFoldMatchesLongS(RegexEngineFactory f) {
        assertThat(match("(?i)s", "\u017F", f)).as("(?i)s → ſ").isNotNull();
    }

    /** Turkic İ/ı stay out of the i-orbit under plain (?i) — simple-fold
     *  semantics, re2j and Go parity (they match themselves, nothing else). */
    @ParameterizedTest
    @MethodSource("factories")
    void plainIFoldKeepsTurkicIPairInert(RegexEngineFactory f) {
        assertThat(match("(?i)i", "i", f)).isNotNull();
        assertThat(match("(?i)i", "I", f)).isNotNull();
        assertThat(match("(?i)i", "\u0130", f)).as("(?i)i must not match İ").isNull();
        assertThat(match("(?i)i", "\u0131", f)).as("(?i)i must not match ı").isNull();
        assertThat(match("(?i)\u0130", "i", f)).as("(?i)İ must not match i").isNull();
        assertThat(match("(?i)\u0130", "\u0131", f))
                .as("(?i)İ must not match ı")
                .isNull();
        assertThat(match("(?i)\u0131", "\u0131", f)).as("(?i)ı matches itself").isNotNull();
        assertThat(match("(?i)[i\u0131]", "\u0131", f)).isNotNull();
        assertThat(match("(?i)[i\u0131]", "\u0130", f))
                .as("class fold must not pull in İ")
                .isNull();
    }

    /** Cyrillic historic letters fold with their partners under plain (?i),
     *  both directions — the DEFAULT (JDK-derived, modern) fold universe,
     *  matching java.util.regex on contemporary JDKs. No oracle folds
     *  them: re2j 1.8's 6.0-era table predates them (stale from the
     *  partner side, hang from the letters; fork patch 0003 declines the
     *  asymmetric mappings, making them fold-inert there). Oracle-parity
     *  lane: FoldCaseParityTest (bridge folds with the oracle by
     *  construction). */
    @ParameterizedTest
    @MethodSource("factories")
    void plainIFoldHistoricCyrillic(RegexEngineFactory f) {
        assertThat(match("(?i)\u0442", "\u1C84", f)).as("(?i)т → Ꚅ").isNotNull();
        assertThat(match("(?i)\u0442", "\u1C85", f)).as("(?i)т → ꚅ").isNotNull();
        assertThat(match("(?i)\u1C85", "\u0442", f)).as("(?i)ꚅ → т").isNotNull();
        assertThat(match("(?i)\u1C80", "\u0432", f)).as("(?i)Ꚁ → в").isNotNull();
        assertThat(match("(?i)\u1C80", "\u0412", f)).as("(?i)Ꚁ → В").isNotNull();
        assertThat(match("(?i)\u0432", "\u1C80", f)).as("(?i)в → Ꚁ").isNotNull();
    }

    @ParameterizedTest
    @MethodSource("factories")
    void unicodeFoldMultiCharLiteral(RegexEngineFactory f) {
        assertThat(match("(?iu)ss", "\u017Fs", f)).isNotNull();
        assertThat(match("(?iu)ss", "s\u017F", f)).isNotNull();
        assertThat(match("(?iu)ss", "\u017F\u017F", f)).isNotNull();
    }

    @ParameterizedTest
    @MethodSource("factories")
    void unicodeFoldQuotedLiteral(RegexEngineFactory f) {
        assertThat(match("(?iu)\\Qs\\E", "\u017F", f)).isNotNull();
    }

    @ParameterizedTest
    @MethodSource("factories")
    void unicodeFoldWithCapture(RegexEngineFactory f) {
        Matcher m = match("(?iu)(s)", "\u017F", f);
        assertThat(m).isNotNull();
        assertThat(m.start(1)).isEqualTo(0);
        assertThat(m.end(1)).isEqualTo(1);
    }

    @ParameterizedTest
    @MethodSource("factories")
    void unicodeFoldInAlternation(RegexEngineFactory f) {
        Matcher m = match("(?iu)(s|t)", "\u017F", f);
        assertThat(m).isNotNull();
        assertThat(m.start(1)).isEqualTo(0);
    }

    @ParameterizedTest
    @MethodSource("factories")
    void unicodeFoldNoFalseMatch(RegexEngineFactory f) {
        assertThat(match("(?iu)s", "x", f)).isNull();
        assertThat(match("(?iu)s", "5", f)).isNull();
    }

    @ParameterizedTest
    @MethodSource("factories")
    void unicodeFoldRepetition(RegexEngineFactory f) {
        Matcher m = match("(?iu)(s+)", "\u017F\u017F", f);
        assertThat(m).isNotNull();
        assertThat(m.start(1)).isEqualTo(0);
        assertThat(m.end(1)).isEqualTo(2);
    }

    @ParameterizedTest
    @MethodSource("factories")
    void foldToggleOffMidPattern(RegexEngineFactory f) {
        assertThat(match("(?iu)s(?-i)s", "\u017Fs", f)).isNotNull();
        assertThat(match("(?iu)s(?-i)s", "\u017FS", f)).isNull();
    }

    @ParameterizedTest
    @MethodSource("factories")
    void foldWithDotall(RegexEngineFactory f) {
        Matcher m = match("(?isu)s.", "\u017F\nx", f);
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
    @ParameterizedTest
    @MethodSource("factories")
    void rangeClassShouldIncludeFoldEquivalent(RegexEngineFactory f) {
        Matcher m = match("(?iu)[r-t]", "\u017F", f);
        assertThat(m)
                .as("(?iu)[r-t] should match ſ (fold-equiv of s in range) — BUG: returns null")
                .isNotNull();
    }

    @ParameterizedTest
    @MethodSource("factories")
    void rangeClassAsciiFold(RegexEngineFactory f) {
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
    @ParameterizedTest
    @MethodSource("factories")
    void negatedClassShouldExcludeFoldEquivalent(RegexEngineFactory f) {
        Matcher m = match("(?iu)[^s]", "\u017F", f);
        assertThat(m).as("(?iu)[^s] should NOT match ſ — BUG: returns non-null").isNull();
    }

    @ParameterizedTest
    @MethodSource("factories")
    void negatedClassExcludesAsciiFold(RegexEngineFactory f) {
        // (?i)[^s] on S — should NOT match (S is fold-equiv of s)
        assertThat(match("(?i)[^s]", "S", f)).isNull();
        // (?i)[^s] on x — SHOULD match
        assertThat(match("(?i)[^s]", "x", f)).isNotNull();
    }

    // ===== ASCII char-class fold (works correctly) =====

    @ParameterizedTest
    @MethodSource("factories")
    void asciiCharClassFold(RegexEngineFactory f) {
        assertThat(match("(?iu)[sx]", "S", f)).isNotNull();
    }

    @ParameterizedTest
    @MethodSource("factories")
    void foldClassSingleChar(RegexEngineFactory f) {
        assertThat(match("(?i)a", "A", f)).isNotNull();
    }

    @ParameterizedTest
    @MethodSource("factories")
    void foldLiteralInConcat(RegexEngineFactory f) {
        assertThat(match("(?i)hello", "HeLLo", f)).isNotNull();
    }

    @ParameterizedTest
    @MethodSource("factories")
    void scopedCaseInsensitiveNoLeak(RegexEngineFactory f) {
        assertThat(match("a(?i:bc)d", "aBCd", f)).isNotNull();
        assertThat(match("a(?i:bc)d", "abcd", f)).isNotNull();
    }

    @ParameterizedTest
    @MethodSource("factories")
    void negatedClassUnderCi(RegexEngineFactory f) {
        assertThat(match("(?i)[^a-z]", "A", f)).isNull();
        assertThat(match("(?i)[^a-z]", "5", f)).isNotNull();
    }

    @ParameterizedTest
    @MethodSource("factories")
    void unicodePropertyFold(RegexEngineFactory f) {
        assertThat(match("(?i)\\p{Ll}", "A", f)).isNotNull();
        assertThat(match("(?i)\\p{Lu}", "a", f)).isNotNull();
        assertThat(match("(?i)\\p{Greek}", "\u0391", f)).isNotNull();
    }

    @ParameterizedTest
    @MethodSource("factories")
    void findAllFoldEquivalentsInStream(RegexEngineFactory f) {
        Pattern r = Pattern.compile("(?iu)s", 0, f);
        String input = "s S \u017F s";
        int count = 0;
        for (Matcher m = r.matcher(input); m.find(); ) {
            count++;
        }
        assertThat(count).isEqualTo(4);
    }
}
