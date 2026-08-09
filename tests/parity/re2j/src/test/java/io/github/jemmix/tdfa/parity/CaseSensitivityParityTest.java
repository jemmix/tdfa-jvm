package io.github.jemmix.tdfa.parity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import io.github.jemmix.tdfa.EngineFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static io.github.jemmix.tdfa.parity.Re2jOracle.*;

/**
 * Case-insensitive matching parity: (?i), CASE_INSENSITIVE flag, ASCII folding,
 * Unicode case folding for \p{X}.
 */
class CaseSensitivityParityTest {

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void inlineCaseInsensitive(EngineFactory factory) { assertSameFind("(?i)abc", "ABC", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void inlineCaseInsensitive2(EngineFactory factory) { assertSameFind("(?i)abc", "abc", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void inlineCaseInsensitive3(EngineFactory factory) { assertSameFind("(?i)abc", "AbC", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void caseInsensitiveNoMatch(EngineFactory factory) { assertSameFind("(?i)abc", "abd", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void caseSensitiveDefault(EngineFactory factory) { assertSameFind("abc", "ABC", factory); }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void foldClassRange(EngineFactory factory) { assertSameFind("(?i)[A-Z]", "g", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void foldClassRangeUpper(EngineFactory factory) { assertSameFind("(?i)[a-z]", "G", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void foldClassSingleChar(EngineFactory factory) { assertSameFind("(?i)a", "A", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void foldLiteralInConcat(EngineFactory factory) { assertSameFind("(?i)hello", "HeLLo", factory); }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void foldUnicodePropertyLl(EngineFactory factory) { assertSameFind("(?i)\\p{Ll}", "A", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void foldUnicodePropertyLu(EngineFactory factory) { assertSameFind("(?i)\\p{Lu}", "a", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void foldUnicodeScriptGreek(EngineFactory factory) { assertSameFind("(?i)\\p{Greek}", "\u0391", factory); }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void caseInsensitiveFlag(EngineFactory factory) {
        int[] expected = com.google.re2j.Pattern.compile("abc",
                com.google.re2j.Pattern.CASE_INSENSITIVE)
                .matcher("ABC").matches()
                ? new int[]{0, 3} : null;
        boolean actual = io.github.jemmix.tdfa.re2j.Pattern.compile("abc",
                io.github.jemmix.tdfa.re2j.Pattern.CASE_INSENSITIVE, factory)
                .matcher("ABC").matches();
        assertThat(actual).isEqualTo(expected != null);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void toggleCaseInsensitiveOff(EngineFactory factory) { assertSameFind("(?i)ab(?-i)c", "ABc", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void toggleCaseInsensitiveOff2(EngineFactory factory) { assertSameFind("(?i)ab(?-i)c", "ABC", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void scopedCaseInsensitive(EngineFactory factory) { assertSameFind("a(?i:bc)d", "aBCd", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void scopedCaseInsensitiveNoLeak(EngineFactory factory) { assertSameFind("a(?i:bc)d", "abcd", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void scopedCaseInsensitiveLeakPrevention(EngineFactory factory) { assertSameFind("(?i:a)b", "Ab", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void scopedNegationLeakPrevention(EngineFactory factory) { assertSameFind("(?i)a(?-i:b)c", "AbC", factory); }

    // ---- Combined flags ----

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void combinedIandS(EngineFactory factory) { assertSameFind("(?is).+", "AB\ncd", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void combinedIandM(EngineFactory factory) { assertSameAllMatches("(?im)^\\w", "Ab\nCd", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void negatedClassUnderCaseInsensitive(EngineFactory factory) { assertSameFind("(?i)[^a-z]", "A", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void negatedClassUnderCaseInsensitive2(EngineFactory factory) { assertSameFind("(?i)[^a-z]", "5", factory); }

    // ---- Empty input ----

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void caseInsensitiveEmptyInput(EngineFactory factory) { assertSameFind("(?i)a*", "", factory); }
}
