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
 * <p><b>{@code (?u)} boundaries — {@code java.util.regex} parity.</b>
 * {@code (?u)} is a tdfa extension (re2j has no {@code u} flag), defined to
 * mirror {@code java.util.regex} with {@code UNICODE_CHARACTER_CLASS}; the live
 * JDK is therefore the oracle, run in the same JVM (same Unicode version) on
 * tdfa's default (JDK-derived) universe. The word set is
 * {@code [\p{Alpha}\p{M}\p{Nd}\p{Pc}\p{IsJoin_Control}]} — the JDK's
 * Alphabetic-based definition — so parity is exact by construction
 * ({@code Character.isAlphabetic} on our side) and pinned differentially:
 * on the agreeing categories, on every category representative, and on the
 * codepoints where the previous category-based set
 * ({@code L N Mn Me Pc Sc Sk}) diverged from the JDK — {@code Sc}/{@code Sk}/
 * {@code No} (formerly word for us only) and {@code Mc}, Join_Control,
 * Other_Alphabetic (word for the JDK only).
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

    // ---- (?u) word-set exactness: one representative per general category,
    // plus the delta codepoints of the old category-based set ----

    /**
     * One BMP representative per two-letter general category (Cs skipped:
     * lone surrogates are their own divergence surface, not a word-set
     * question). If the word-set formula drifts from the JDK's on ANY
     * category, one of these lines flips.
     */
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void unicodeWordMembershipPerCategoryMatchesJur(RegexEngineFactory factory) {
        String[] reps = {"A", // Lu
            "a", // Ll
            "\u01c5", // Lt titlecase Ǆ
            "\u02b0", // Lm modifier letter ʰ
            "\u6f22", // Lo
            "\u0591", // Mn accent
            "\u0488", // Me combining enclosing
            "\u0903", // Mc spacing mark
            "5", // Nd
            "\u2164", // Nl roman numeral Ⅴ
            "\u00b2", // No superscript two
            "_", // Pc
            "-", // Pd
            "(", // Ps
            ")", // Pe
            "\u00ab", // Pi
            "\u00bb", // Pf
            ".", // Po
            "\u00a5", // Sc yen
            "^", // Sk
            "+", // Sm
            "\u24b6", // So circled A (Other_Alphabetic!)
            " ", // Zs
            "\u2028", // Zl
            "\u2029", // Zp
            "\u0001", // Cc
            "\u00ad", // Cf soft hyphen
            "\ue000", // Co private use
            "\u0378", // Cn unassigned
        };
        for (String s : reps) {
            assertSameAsJur("(?u)\\w", s, factory);
            assertSameAsJur("(?u)\\W", s, factory);
            assertSameAsJur("(?u)\\b.", s, factory);
        }
    }

    /** The codepoints where the old category-based set diverged from the
     *  JDK — now pinned differentially in both directions. */
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void formerlyDivergingCodepointsMatchJurExactly(RegexEngineFactory factory) {
        // Sc/Sk/No: formerly word for us only — now non-word like the JDK.
        assertSameAsJur("(?u)\\w", "\u00a5", factory); // ¥ Sc
        assertSameAsJur("(?u)\\w", "\u00b2", factory); // ² No
        assertSameAsJur("(?u)\\W", "\u00a5", factory);
        assertSameAsJur("(?u)[^\\w]", "\u00a5", factory);
        assertSameAsJur("(?u)\\b.", "\u00a5", factory); // boundary fires around ¥
        assertSameAsJur("(?u)\\b\\w\\b", "_\u00a5", factory); // boundary _ | ¥
        // Mc / Join_Control / Other_Alphabetic: formerly non-word for us —
        // now word like the JDK.
        assertSameAsJur("(?u)\\w", "\u0903", factory); // ः Mc
        assertSameAsJur("(?u)\\b\\w", "\u0903\u0903", factory);
        assertSameAsJur("(?u)\\w", "\u200c", factory); // ZWNJ
        assertSameAsJur("(?u)\\w", "\u200d", factory); // ZWJ
        assertSameAsJur("(?u)\\b\\w\\b", "a\u200db", factory); // no boundary a | ZWJ
        assertSameAsJur("(?u)\\w", "\u24b6", factory); // Ⓐ Other_Alphabetic
        assertSameAsJur("(?u)\\b.", "\u24b6b", factory); // Ⓐ itself matches now
    }
}
