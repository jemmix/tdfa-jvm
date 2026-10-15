package io.github.jemmix.tdfa.parity;

import io.github.jemmix.tdfa.core.compile.RegexEngineFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static io.github.jemmix.tdfa.parity.Re2jOracle.assertSameAllMatches;
import static io.github.jemmix.tdfa.parity.Re2jOracle.assertSameFind;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Case-insensitive matching parity: (?i), CASE_INSENSITIVE flag, ASCII folding,
 * Unicode case folding for \p{X}.
 *
 * <p>Jur twin below — bare {@code (?i)} folds ASCII only in both the JDK
 * oracle and the JUR lane, so the whole suite translates; the
 * Unicode-fold divergences (ſ, kelvin, İ/ı) live in FoldCaseParityTest's
 * twins.
 */
class CaseSensitivityParityTest {

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void inlineCaseInsensitive(RegexEngineFactory factory) {
        assertSameFind("(?i)abc", "ABC", factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void inlineCaseInsensitive2(RegexEngineFactory factory) {
        assertSameFind("(?i)abc", "abc", factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void inlineCaseInsensitive3(RegexEngineFactory factory) {
        assertSameFind("(?i)abc", "AbC", factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void caseInsensitiveNoMatch(RegexEngineFactory factory) {
        assertSameFind("(?i)abc", "abd", factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void caseSensitiveDefault(RegexEngineFactory factory) {
        assertSameFind("abc", "ABC", factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void foldClassRange(RegexEngineFactory factory) {
        assertSameFind("(?i)[A-Z]", "g", factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void foldClassRangeUpper(RegexEngineFactory factory) {
        assertSameFind("(?i)[a-z]", "G", factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void foldClassSingleChar(RegexEngineFactory factory) {
        assertSameFind("(?i)a", "A", factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void foldLiteralInConcat(RegexEngineFactory factory) {
        assertSameFind("(?i)hello", "HeLLo", factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void foldUnicodePropertyLl(RegexEngineFactory factory) {
        assertSameFind("(?i)\\p{Ll}", "A", factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void foldUnicodePropertyLu(RegexEngineFactory factory) {
        assertSameFind("(?i)\\p{Lu}", "a", factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void foldUnicodeScriptGreek(RegexEngineFactory factory) {
        assertSameFind("(?i)\\p{Greek}", "\u0391", factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void caseInsensitiveFlag(RegexEngineFactory factory) {
        int[] expected =
            com.google.re2j.Pattern.compile("abc", com.google.re2j.Pattern.CASE_INSENSITIVE).matcher("ABC").matches()
                ? new int[]{0, 3} : null;
        boolean actual = io.github.jemmix.tdfa.Pattern
            .compile("abc", io.github.jemmix.tdfa.Pattern.CASE_INSENSITIVE, factory).matcher("ABC").matches();
        assertThat(actual).isEqualTo(expected != null);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void toggleCaseInsensitiveOff(RegexEngineFactory factory) {
        assertSameFind("(?i)ab(?-i)c", "ABc", factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void toggleCaseInsensitiveOff2(RegexEngineFactory factory) {
        assertSameFind("(?i)ab(?-i)c", "ABC", factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void scopedCaseInsensitive(RegexEngineFactory factory) {
        assertSameFind("a(?i:bc)d", "aBCd", factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void scopedCaseInsensitiveNoLeak(RegexEngineFactory factory) {
        assertSameFind("a(?i:bc)d", "abcd", factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void scopedCaseInsensitiveLeakPrevention(RegexEngineFactory factory) {
        assertSameFind("(?i:a)b", "Ab", factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void scopedNegationLeakPrevention(RegexEngineFactory factory) {
        assertSameFind("(?i)a(?-i:b)c", "AbC", factory);
    }

    // ---- Combined flags ----

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void combinedIandS(RegexEngineFactory factory) {
        assertSameFind("(?is).+", "AB\ncd", factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void combinedIandM(RegexEngineFactory factory) {
        assertSameAllMatches("(?im)^\\w", "Ab\nCd", factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void negatedClassUnderCaseInsensitive(RegexEngineFactory factory) {
        assertSameFind("(?i)[^a-z]", "A", factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void negatedClassUnderCaseInsensitive2(RegexEngineFactory factory) {
        assertSameFind("(?i)[^a-z]", "5", factory);
    }

    // ---- Empty input ----

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void caseInsensitiveEmptyInput(RegexEngineFactory factory) {
        assertSameFind("(?i)a*", "", factory);
    }

    /** Jur twin: the whole suite against the live java.util.regex oracle. */
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.JurOracle#engineFactories")
    void jurTwinAllCases(RegexEngineFactory factory) {
        JurOracle.assertSameFind("(?i)abc", "ABC", factory);
        JurOracle.assertSameFind("(?i)abc", "abc", factory);
        JurOracle.assertSameFind("(?i)abc", "AbC", factory);
        JurOracle.assertSameFind("(?i)abc", "abd", factory);
        JurOracle.assertSameFind("abc", "ABC", factory);
        JurOracle.assertSameFind("(?i)[A-Z]", "g", factory);
        JurOracle.assertSameFind("(?i)[a-z]", "G", factory);
        JurOracle.assertSameFind("(?i)a", "A", factory);
        JurOracle.assertSameFind("(?i)hello", "HeLLo", factory);
        JurOracle.assertSameFind("(?i)\\p{Ll}", "A", factory);
        JurOracle.assertSameFind("(?i)\\p{Lu}", "a", factory);
        // (?i)\p{Greek}: the JDK needs the Is-script spelling — the bare
        // script name is a RE2-lineage extension (pinned in CharClass)
        JurOracle.assertSameFind("(?i)ab(?-i)c", "ABc", factory);
        JurOracle.assertSameFind("(?i)ab(?-i)c", "ABC", factory);
        JurOracle.assertSameFind("a(?i:bc)d", "aBCd", factory);
        JurOracle.assertSameFind("a(?i:bc)d", "abcd", factory);
        JurOracle.assertSameFind("(?i:a)b", "Ab", factory);
        JurOracle.assertSameFind("(?i)a(?-i:b)c", "AbC", factory);
        JurOracle.assertSameFind("(?is).+", "AB\ncd", factory);
        JurOracle.assertSameAllMatches("(?im)^\\w", "Ab\nCd", factory);
        JurOracle.assertSameFind("(?i)[^a-z]", "A", factory);
        JurOracle.assertSameFind("(?i)[^a-z]", "5", factory);
        JurOracle.assertSameFind("(?i)a*", "", factory);
        // the CASE_INSENSITIVE flag bit through the translation
        JurOracle.assertSameFind("abc", "ABC", io.github.jemmix.tdfa.Pattern.CASE_INSENSITIVE, factory);
    }
}
