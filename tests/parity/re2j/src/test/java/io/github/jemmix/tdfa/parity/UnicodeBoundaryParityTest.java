package io.github.jemmix.tdfa.parity;

import io.github.jemmix.tdfa.core.Matcher;
import io.github.jemmix.tdfa.core.RegexEngineFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static io.github.jemmix.tdfa.parity.Re2jOracle.assertSameFind;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unicode word-boundary parity, two contracts:
 *
 * <p><b>Default (ASCII) {@code \b} next to non-ASCII neighbors — re2j
 * parity.</b> Under ASCII semantics every non-ASCII codepoint is a non-word
 * char, so boundaries fire around Greek/CJK/emoji text; the engine must
 * evaluate them with codepoint (not code-unit) awareness and never start or
 * end inside a surrogate pair.
 *
 * <p><b>{@code (?u)} Unicode boundaries — {@code java.util.regex} parity on
 * the agreeing categories.</b> {@code (?u)} is a tdfa extension (re2j has no
 * {@code u} flag), defined to mirror
 * {@code java.util.regex} with {@code UNICODE_CHARACTER_CLASS}; the live JDK
 * is therefore the oracle, run in the same JVM (same Unicode version) on
 * tdfa's default (JDK-derived) universe. Word chars where both engines
 * agree — {@code L}, {@code Nd}, {@code Mn}, {@code Me}, {@code Nl},
 * {@code Pc}, including supplementary letters — are pinned differentially.
 *
 * <p><b>Known divergence, pinned as contract below:</b> tdfa's Unicode word
 * set is category-based ({@code L N Mn Me Pc Sc Sk}) while the JDK's is
 * {@code [\p{Alpha}\p{M}\p{Nd}\p{Pc}\p{IsJoin_Control}]} — Alphabetic- and
 * Join_Control-based. They disagree on {@code Sc}/{@code Sk}/{@code No}
 * (word for us, not for the JDK), and on {@code Mc}, Join_Control and
 * Other_Alphabetic symbols like U+24B6 (word for the JDK, not for us).
 * Matching the JDK exactly needs Alphabetic/Join_Control tables no provider
 * supplies today (open work, TODO.md); until then the deltas are pinned as
 * fixed expectations so any move flips these lines deliberately.
 */
class UnicodeBoundaryParityTest {

    private static final String OMEGA = "\u03a9"; // Ω, L

    private static final String HAN = "\u6f22\u5b57"; // 漢字, Lo

    private static final String FRAKTUR_B = "\ud835\udd05"; // 𝔅, supplementary Lo

    // ---- ASCII \b next to non-ASCII: re2j parity ----

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void boundaryFiresBetweenNonAsciiNonWordAndAsciiWord(RegexEngineFactory factory) {
        assertSameFind("\\bx", OMEGA + "x", factory); // Ω non-word: boundary before x
        assertSameFind("\\b" + OMEGA, "a" + OMEGA + "b", factory); // after word a
        assertSameFind("x\\b", "x" + HAN, factory);
        assertSameFind(".\\b.", "a" + HAN, factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void asciiWordExtractionInNonAsciiText(RegexEngineFactory factory) {
        assertSameFind("\\b\\w+\\b", HAN + "abc", factory); // abc only
        assertSameFind("\\b\\w+\\b", "a\ud835\udd04b", factory); // stops before 𝔄
        assertSameFind("\\bx\\b", OMEGA + "x" + OMEGA, factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void noBoundaryBetweenTwoNonAsciiNonWords(RegexEngineFactory factory) {
        assertSameFind("\\b.", OMEGA + OMEGA, factory); // no word char anywhere
        assertSameFind("\\b" + OMEGA + "\\b", OMEGA, factory);
        assertSameFind("\\B.", OMEGA + OMEGA, factory); // \B fires everywhere instead
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void boundaryBeforeCombiningMarkInAsciiMode(RegexEngineFactory factory) {
        assertSameFind("a\\b", "a\u0301", factory); // U+0301 non-word in ASCII mode
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void boundariesAroundSurrogatePairs(RegexEngineFactory factory) {
        assertSameFind("\\bx", "\ud83d\ude00x", factory); // emoji = one non-word codepoint
        assertSameFind("x\\b", "x\ud83d\ude00", factory);
        assertSameFind(".\\b.", "a\ud83d\ude00", factory);
    }

    // ---- (?u) boundaries: java.util.regex parity on agreeing categories ----

    /** "true <group> <groupCount> [g1]..." with null groups skipped. */
    private static String protocol(boolean found, String whole, int gc, int[] spans, String input) {
        StringBuilder sb = new StringBuilder();
        sb.append(found ? "true " + whole : "false").append(' ').append(gc);
        if (found && spans != null) {
            for (int g = 1; g <= gc; g++) {
                if (spans[2 * g] >= 0) {
                    sb.append(" <").append(input, spans[2 * g], spans[2 * g + 1]).append('>');
                }
            }
        }
        return sb.toString();
    }

    private static String jurProtocol(String pattern, String input) {
        java.util.regex.Matcher m =
            java.util.regex.Pattern.compile(pattern, java.util.regex.Pattern.UNICODE_CHARACTER_CLASS).matcher(input);
        boolean found = m.find();
        int[] spans = new int[2 + 2 * m.groupCount()];
        if (found) {
            spans[0] = m.start();
            spans[1] = m.end();
            for (int g = 1; g <= m.groupCount(); g++) {
                spans[2 * g] = m.start(g);
                spans[2 * g + 1] = m.end(g);
            }
        }
        return protocol(found, found ? m.group() : "", m.groupCount(), found ? spans : null, input);
    }

    /** Default (JDK-derived) universe — same Unicode version as the live jur oracle. */
    private static String tdfaProtocol(String pattern, String input, RegexEngineFactory factory) {
        Matcher m = io.github.jemmix.tdfa.Pattern.compile(pattern, 0, factory, null).matcher(input);
        boolean found = m.find();
        int[] spans = new int[2 + 2 * m.groupCount()];
        if (found) {
            spans[0] = m.start();
            spans[1] = m.end();
            for (int g = 1; g <= m.groupCount(); g++) {
                try {
                    spans[2 * g] = m.start(g);
                    spans[2 * g + 1] = m.end(g);
                } catch (IllegalStateException e) {
                    spans[2 * g] = -1;
                    spans[2 * g + 1] = -1;
                }
            }
        }
        return protocol(found, found ? m.group() : "", m.groupCount(), found ? spans : null, input);
    }

    private static void assertSameAsJur(String pattern, String input, RegexEngineFactory factory) {
        assertThat(tdfaProtocol(pattern, input, factory))
            .as("(?u) pattern=\"%s\" input=\"%s\" [%s]", pattern, input, factory)
            .isEqualTo(jurProtocol(pattern, input));
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void unicodeBoundaryAtBmpLetterEdges(RegexEngineFactory factory) {
        assertSameAsJur("(?u)\\b\\w", OMEGA, factory);
        assertSameAsJur("(?u)\\w\\b", OMEGA, factory);
        assertSameAsJur("(?u)\\b\\w+\\b", "\u0430\u0431\u0432", factory); // Cyrillic
        assertSameAsJur("(?u)\\b\\w+\\b", "\u0663\u0664\u0665", factory); // Arabic-Indic digits (Nd)
        assertSameAsJur("(?u)\\b\\p{L}+\\b", HAN, factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void noBoundaryBetweenWordAndCombiningMark(RegexEngineFactory factory) {
        assertSameAsJur("(?u).\\b.", "a\u0301", factory); // L|Mn: both word
        assertSameAsJur("(?u)x\\by", "x\u0301y", factory);
        assertSameAsJur("(?u)\\B\\w", "x\u0301a", factory); // \B between x and Mn
        assertSameAsJur("(?u)\\b\\w+\\b", "a\u0301b", factory); // mark inside the word
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void unicodeBoundaryAtSupplementaryLetter(RegexEngineFactory factory) {
        assertSameAsJur("(?u)\\b\\w\\b", FRAKTUR_B, factory);
        assertSameAsJur("(?u)\\b\\w+", "\ud835\udd04\ud835\udd05", factory);
        assertSameAsJur("(?u).\\b.", "a\ud835\udd04", factory); // both word: no boundary
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void unicodeWordDelimitedByNonWord(RegexEngineFactory factory) {
        assertSameAsJur("(?u)\\b.", "!!!", factory); // no boundary before non-word
        assertSameAsJur("(?u)\\b.", "\u2014a", factory); // boundary after em dash
        assertSameAsJur("(?u)\\w+\\b[!]", "a!", factory);
        assertSameAsJur("(?u)\\w+\\b\\W+", "a\u0301 ", factory);
        assertSameAsJur("(?u)\\b\\w{2,}\\b", "caf\u00e9 na\u00efve", factory);
        assertSameAsJur("(?u)(\\b\\w+\\b)", "\u30d1\u30fc\u30b9", factory); // Katakana + group span
    }

    // ---- (?u) word-set deltas vs the JDK: pinned tdfa contract ----
    // (category-based word set — see class javadoc; flip deliberately when
    //  the Alphabetic/Join_Control-based set lands)

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void currencyAndModifierSymbolsAreWordCharsForUs(RegexEngineFactory factory) {
        assertThat(findDefault("(?u)\\w", "\u00a5", factory)).isTrue(); // ¥ Sc: JDK says non-word
        assertThat(findDefault("(?u)\\w", "\u00b2", factory)).isTrue(); // ² No
        assertThat(findDefault("(?u)\\W", "\u00a5", factory)).isFalse();
        assertThat(findDefault("(?u)[^\\w]", "\u00a5", factory)).isFalse();
        assertThat(findDefault("(?u)\\b.", "\u00a5", factory)).isTrue(); // boundaries fire around ¥
        assertThat(findDefault("(?u)\\b\\w\\b", "_\u00a5", factory)).isFalse(); // no boundary _ | ¥
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void spacingMarksJoinControlAndCircledAreNotWordCharsForUs(RegexEngineFactory factory) {
        assertThat(findDefault("(?u)\\w", "\u0903", factory)).isFalse(); // ः Mc: JDK says word
        assertThat(findDefault("(?u)\\b\\w", "\u0903\u0903", factory)).isFalse();
        assertThat(findDefault("(?u)\\w", "\u200d", factory)).isFalse(); // ZWJ Join_Control
        assertThat(findDefault("(?u)\\w", "\u24b6", factory)).isFalse(); // Ⓐ Other_Alphabetic
        // boundary fires between Ⓐ (non-word for us) and b — JDK matched Ⓐ itself
        Matcher m = io.github.jemmix.tdfa.Pattern.compile("(?u)\\b.", 0, factory, null).matcher("\u24b6b");
        assertThat(m.find()).isTrue();
        assertThat(m.group()).isEqualTo("b");
    }

    private static boolean findDefault(String pattern, String input, RegexEngineFactory factory) {
        return io.github.jemmix.tdfa.Pattern.compile(pattern, 0, factory, null).matcher(input).find();
    }
}
