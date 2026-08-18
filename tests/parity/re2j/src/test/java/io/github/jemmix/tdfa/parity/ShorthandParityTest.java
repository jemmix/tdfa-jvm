package io.github.jemmix.tdfa.parity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import io.github.jemmix.tdfa.core.Matcher;
import io.github.jemmix.tdfa.core.PatternSyntaxException;
import io.github.jemmix.tdfa.core.RegexEngineFactory;

import static io.github.jemmix.tdfa.parity.Re2jOracle.*;

/**
 * Shorthand class parity: \d \D \w \W \s \S inside and outside character classes,
 * as atoms and under quantifiers.
 */
class ShorthandParityTest {

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void digitAtom(RegexEngineFactory factory) { assertSameFind("\\d", "5", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void digitNoMatch(RegexEngineFactory factory) { assertSameFind("\\d", "x", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void notDigitAtom(RegexEngineFactory factory) { assertSameFind("\\D", "x", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void notDigitNoMatch(RegexEngineFactory factory) { assertSameFind("\\D", "5", factory); }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void wordAtom(RegexEngineFactory factory) { assertSameFind("\\w", "a", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void wordUnderscore(RegexEngineFactory factory) { assertSameFind("\\w", "_", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void wordNoMatch(RegexEngineFactory factory) { assertSameFind("\\w", "!", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void notWordAtom(RegexEngineFactory factory) { assertSameFind("\\W", "!", factory); }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void spaceAtom(RegexEngineFactory factory) { assertSameFind("\\s", " ", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void spaceTab(RegexEngineFactory factory) { assertSameFind("\\s", "\t", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void spaceNewline(RegexEngineFactory factory) { assertSameFind("\\s", "\n", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void notSpaceAtom(RegexEngineFactory factory) { assertSameFind("\\S", "x", factory); }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void digitInClass(RegexEngineFactory factory) { assertSameFind("[\\d]", "7", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void wordInClass(RegexEngineFactory factory) { assertSameFind("[\\w]", "z", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void spaceInClass(RegexEngineFactory factory) { assertSameFind("[\\s]", "\t", factory); }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void mixedClassWithShorthand(RegexEngineFactory factory) { assertSameFind("[\\w\\s]", " ", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void mixedClassDigitWord(RegexEngineFactory factory) { assertSameFind("[\\d\\w]", "a", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void negatedClassWithShorthand(RegexEngineFactory factory) { assertSameFind("[^\\d]", "a", factory); }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void digitPlus(RegexEngineFactory factory) { assertSameFind("\\d+", "12345", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void wordStar(RegexEngineFactory factory) { assertSameFind("\\w*", "hello_world", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void spacePlus(RegexEngineFactory factory) { assertSameFind("\\s+", "   ", factory); }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void findAllDigits(RegexEngineFactory factory) { assertSameAllMatches("\\d", "a1b2c3", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void findAllWords(RegexEngineFactory factory) { assertSameAllMatches("\\w+", "hello world foo", factory); }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void complexPattern(RegexEngineFactory factory) { assertSameFind("(\\w+)@(\\w+)\\.(\\w+)", "user@host.com", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void ipPattern(RegexEngineFactory factory) { assertSameFind("(\\d+)\\.(\\d+)\\.(\\d+)\\.(\\d+)", "192.168.1.1", factory); }

    // ---- Full whitespace coverage ----

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void spaceCarriageReturn(RegexEngineFactory factory) { assertSameFind("\\s", "\r", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void spaceFormFeed(RegexEngineFactory factory) { assertSameFind("\\s", "\f", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void spaceVerticalTab(RegexEngineFactory factory) { assertSameFind("\\s", "\u000B", factory); }

    // ---- Non-ASCII divergence checks (re2j \w \d are ASCII-only) ----

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void wordNonAsciiLetter(RegexEngineFactory factory) { assertSameFind("\\w", "\u00E9", factory); }     // é
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void wordNonAsciiDigit(RegexEngineFactory factory) { assertSameFind("\\w", "\u0660", factory); }      // Arabic-Indic zero
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void digitNonAscii(RegexEngineFactory factory) { assertSameFind("\\d", "\u0660", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void notWordNonAscii(RegexEngineFactory factory) { assertSameFind("\\W", "\u00E9", factory); }

    // ---- Unicode whitespace divergence check ----
    // re2j \s is ASCII-only: [\t\n\f\r ] — Unicode whitespace like
    // U+00A0, U+2028 etc. should NOT match \s in either engine.

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void spaceNbsp(RegexEngineFactory factory) { assertSameFind("\\s", "\u00A0", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void spaceLineSep(RegexEngineFactory factory) { assertSameFind("\\s", "\u2028", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void spaceParaSep(RegexEngineFactory factory) { assertSameFind("\\s", "\u2029", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void spaceNextLine(RegexEngineFactory factory) { assertSameFind("\\s", "\u0085", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void spaceBom(RegexEngineFactory factory) { assertSameFind("\\s", "\uFEFF", factory); }
}
