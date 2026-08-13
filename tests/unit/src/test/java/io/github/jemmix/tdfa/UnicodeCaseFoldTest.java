package io.github.jemmix.tdfa;

import io.github.jemmix.tdfa.tdfa.Disambiguation;
import io.github.jemmix.tdfa.unicode.CaseFoldTable;
import io.github.jemmix.tdfa.vm.MatchResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unicode case-fold for literal chars under {@code (?iu)}: the §C fix that
 * added {@link CaseFoldTable}. Before the fix, the parser only used
 * {@link Character#toLowerCase} / {@link Character#toUpperCase}, which for
 * ASCII {@code s} gives only {@code [sS]} — missing the Unicode simple
 * case-fold {@code s ↔ ſ} (U+017F LATIN SMALL LETTER LONG S).
 *
 * <p>Each test runs on both ASM and VM backends.
 */
class UnicodeCaseFoldTest {

    private static Stream<EngineFactory> factories() {
        return Stream.of(EngineFactory.ASM, EngineFactory.VM);
    }

    private static MatchResult match(String pattern, String input, EngineFactory factory) {
        Regex r = Regex.compile(pattern, factory, Disambiguation.PERL);
        return r.find(input, 0);
    }

    // ----------------- CaseFoldTable unit tests -----------------

    /** s ↔ S ↔ ſ (U+017F) is the canonical fold group with >2 BMP members. */
    @Test void foldTableReturnsGroupForS() {
        int[] ranges = CaseFoldTable.foldRanges('s');
        assertThat(ranges).as("foldRanges('s') should not be null").isNotNull();
        assertThat(containsCodepoint(ranges, 's')).isTrue();
        assertThat(containsCodepoint(ranges, 'S')).isTrue();
        assertThat(containsCodepoint(ranges, 0x017F)).as("should include ſ (U+017F)").isTrue();
    }

    /** k ↔ K ↔ K (U+212A KELVIN SIGN). */
    @Test void foldTableReturnsGroupForK() {
        int[] ranges = CaseFoldTable.foldRanges('k');
        assertThat(ranges).isNotNull();
        assertThat(containsCodepoint(ranges, 'k')).isTrue();
        assertThat(containsCodepoint(ranges, 'K')).isTrue();
        assertThat(containsCodepoint(ranges, 0x212A)).as("should include K (U+212A Kelvin)").isTrue();
    }

    /** Ω ↔ ω ↔ Ω (U+2126 OHM SIGN). */
    @Test void foldTableReturnsGroupForOmega() {
        int[] ranges = CaseFoldTable.foldRanges(0x03A9); // Ω
        assertThat(ranges).isNotNull();
        assertThat(containsCodepoint(ranges, 0x03A9)).isTrue();  // Ω
        assertThat(containsCodepoint(ranges, 0x03C9)).isTrue();  // ω
        assertThat(containsCodepoint(ranges, 0x2126)).as("should include Ω (U+2126 Ohm)").isTrue();
    }

    /** Digits and punctuation have no case-fold equivalents. */
    @Test void foldTableReturnsNullForDigit() {
        assertThat(CaseFoldTable.foldRanges('5')).isNull();
        assertThat(CaseFoldTable.foldRanges('!')).isNull();
        assertThat(CaseFoldTable.foldRanges('=')).isNull();
    }

    /** foldRanges is symmetric: all members of a fold group return the same set. */
    @Test void foldTableIsSymmetric() {
        int[] fromLower = CaseFoldTable.foldRanges('s');
        int[] fromUpper = CaseFoldTable.foldRanges('S');
        int[] fromFold = CaseFoldTable.foldRanges(0x017F);
        assertThat(fromLower).isEqualTo(fromUpper);
        assertThat(fromLower).isEqualTo(fromFold);
    }

    private static boolean containsCodepoint(int[] ranges, int cp) {
        for (int i = 0; i + 1 < ranges.length; i += 2) {
            if (cp >= ranges[i] && cp <= ranges[i + 1]) return true;
        }
        return false;
    }

    // ----------------- Parser integration: (?iu) literal folding -----------------

    /** The core §C repro: (?iu)s matches ſ (U+017F). */
    @ParameterizedTest
    @MethodSource("factories")
    void unicodeCaseFoldSLongs(EngineFactory factory) {
        MatchResult m = match("(?iu)s", "\u017F", factory);
        assertThat(m).as("(?iu)s should match ſ").isNotNull();
        assertThat(m.start(0)).isEqualTo(0);
        assertThat(m.end(0)).isEqualTo(1);
    }

    /** (?iu)S also matches ſ (fold is symmetric). */
    @ParameterizedTest
    @MethodSource("factories")
    void unicodeCaseFoldUpperSMatchesLongS(EngineFactory factory) {
        MatchResult m = match("(?iu)S", "\u017F", factory);
        assertThat(m).as("(?iu)S should match ſ").isNotNull();
    }

    /** (?iu)k matches K (U+212A KELVIN SIGN). */
    @ParameterizedTest
    @MethodSource("factories")
    void unicodeCaseFoldKMatchesKelvin(EngineFactory factory) {
        MatchResult m = match("(?iu)k", "\u212A", factory);
        assertThat(m).as("(?iu)k should match K (Kelvin sign)").isNotNull();
    }

    /** (?iu)Ω matches ω (lowercase omega). */
    @ParameterizedTest
    @MethodSource("factories")
    void unicodeCaseFoldOmegaMatchesLower(EngineFactory factory) {
        MatchResult m = match("(?iu)\u03A9", "\u03C9", factory);
        assertThat(m).as("(?iu)Ω should match ω").isNotNull();
    }

    /**
     * Without (?u), case-insensitive mode is ASCII-only: (?i)s must NOT
     * match ſ. This pins the ASCII/Unicode boundary of the fix.
     */
    @ParameterizedTest
    @MethodSource("factories")
    void asciiCaseInsensitiveDoesNotMatchLongS(EngineFactory factory) {
        MatchResult m = match("(?i)s", "\u017F", factory);
        assertThat(m).as("(?i)s should NOT match ſ without (?u)").isNull();
    }

    /** Case fold in a multi-char literal: (?iu)ss matches ſs and sſ. */
    @ParameterizedTest
    @MethodSource("factories")
    void unicodeCaseFoldMultiCharLiteral(EngineFactory factory) {
        assertThat(match("(?iu)ss", "\u017Fs", factory)).as("(?iu)ss should match ſs").isNotNull();
        assertThat(match("(?iu)ss", "s\u017F", factory)).as("(?iu)ss should match sſ").isNotNull();
        assertThat(match("(?iu)ss", "\u017F\u017F", factory)).as("(?iu)ss should match ſſ").isNotNull();
    }

    /** Case fold in a quoted literal: (?iu)\Qs\E matches ſ. */
    @ParameterizedTest
    @MethodSource("factories")
    void unicodeCaseFoldQuotedLiteral(EngineFactory factory) {
        MatchResult m = match("(?iu)\\Qs\\E", "\u017F", factory);
        assertThat(m).as("(?iu)\\Qs\\E should match ſ").isNotNull();
    }

    /**
     * Case fold inside a char class (ASCII-only for now): (?iu)[sx] matches S
     * via ASCII fold. Full Unicode fold in char classes is not yet implemented
     * (parser's parseClass only does A-Z/a-z expansion).
     */
    @ParameterizedTest
    @MethodSource("factories")
    void asciiCaseFoldInCharClass(EngineFactory factory) {
        MatchResult m = match("(?iu)[sx]", "S", factory);
        assertThat(m).as("(?iu)[sx] should match S via ASCII fold").isNotNull();
    }

    /** Case fold with capture group: (?iu)(s) on ſ captures the match. */
    @ParameterizedTest
    @MethodSource("factories")
    void unicodeCaseFoldWithCapture(EngineFactory factory) {
        MatchResult m = match("(?iu)(s)", "\u017F", factory);
        assertThat(m).as("should match").isNotNull();
        assertThat(m.start(1)).as("g1 start").isEqualTo(0);
        assertThat(m.end(1)).as("g1 end").isEqualTo(1);
    }

    /** Case fold in alternation: (?iu)(s|t) on ſ matches group 1. */
    @ParameterizedTest
    @MethodSource("factories")
    void unicodeCaseFoldInAlternation(EngineFactory factory) {
        MatchResult m = match("(?iu)(s|t)", "\u017F", factory);
        assertThat(m).as("(?iu)(s|t) should match ſ").isNotNull();
        assertThat(m.start(1)).isEqualTo(0);
        assertThat(m.end(1)).isEqualTo(1);
    }

    /** No-match case: (?iu)s does NOT match unrelated chars. */
    @ParameterizedTest
    @MethodSource("factories")
    void unicodeCaseFoldNoFalseMatch(EngineFactory factory) {
        assertThat(match("(?iu)s", "x", factory)).isNull();
        assertThat(match("(?iu)s", "5", factory)).isNull();
        assertThat(match("(?iu)s", " ", factory)).isNull();
    }

    /** Find all fold-equivalent matches in a stream. */
    @ParameterizedTest
    @MethodSource("factories")
    void unicodeCaseFoldFindAllInStream(EngineFactory factory) {
        Regex r = Regex.compile("(?iu)s", factory, Disambiguation.PERL);
        String input = "s S \u017F s";
        int count = 0;
        int pos = 0;
        while (pos <= input.length()) {
            MatchResult m = r.find(input, pos);
            if (m == null) break;
            count++;
            pos = (m.end(0) <= m.start(0)) ? m.end(0) + 1 : m.end(0);
        }
        assertThat(count).as("should find all 3 fold-equivalent chars + the final s").isEqualTo(4);
    }
}
