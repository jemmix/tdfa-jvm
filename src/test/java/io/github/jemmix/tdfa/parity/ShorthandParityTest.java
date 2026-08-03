package io.github.jemmix.tdfa.parity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import io.github.jemmix.tdfa.EngineFactory;

import static io.github.jemmix.tdfa.parity.Re2jOracle.*;

/**
 * Shorthand class parity: \d \D \w \W \s \S inside and outside character classes,
 * as atoms and under quantifiers.
 */
class ShorthandParityTest {

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void digitAtom(EngineFactory factory) { assertSameFind("\\d", "5", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void digitNoMatch(EngineFactory factory) { assertSameFind("\\d", "x", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void notDigitAtom(EngineFactory factory) { assertSameFind("\\D", "x", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void notDigitNoMatch(EngineFactory factory) { assertSameFind("\\D", "5", factory); }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void wordAtom(EngineFactory factory) { assertSameFind("\\w", "a", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void wordUnderscore(EngineFactory factory) { assertSameFind("\\w", "_", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void wordNoMatch(EngineFactory factory) { assertSameFind("\\w", "!", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void notWordAtom(EngineFactory factory) { assertSameFind("\\W", "!", factory); }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void spaceAtom(EngineFactory factory) { assertSameFind("\\s", " ", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void spaceTab(EngineFactory factory) { assertSameFind("\\s", "\t", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void spaceNewline(EngineFactory factory) { assertSameFind("\\s", "\n", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void notSpaceAtom(EngineFactory factory) { assertSameFind("\\S", "x", factory); }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void digitInClass(EngineFactory factory) { assertSameFind("[\\d]", "7", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void wordInClass(EngineFactory factory) { assertSameFind("[\\w]", "z", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void spaceInClass(EngineFactory factory) { assertSameFind("[\\s]", "\t", factory); }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void mixedClassWithShorthand(EngineFactory factory) { assertSameFind("[\\w\\s]", " ", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void mixedClassDigitWord(EngineFactory factory) { assertSameFind("[\\d\\w]", "a", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void negatedClassWithShorthand(EngineFactory factory) { assertSameFind("[^\\d]", "a", factory); }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void digitPlus(EngineFactory factory) { assertSameFind("\\d+", "12345", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void wordStar(EngineFactory factory) { assertSameFind("\\w*", "hello_world", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void spacePlus(EngineFactory factory) { assertSameFind("\\s+", "   ", factory); }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void findAllDigits(EngineFactory factory) { assertSameAllMatches("\\d", "a1b2c3", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void findAllWords(EngineFactory factory) { assertSameAllMatches("\\w+", "hello world foo", factory); }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void complexPattern(EngineFactory factory) { assertSameFind("(\\w+)@(\\w+)\\.(\\w+)", "user@host.com", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void ipPattern(EngineFactory factory) { assertSameFind("(\\d+)\\.(\\d+)\\.(\\d+)\\.(\\d+)", "192.168.1.1", factory); }

    // ---- Full whitespace coverage ----

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void spaceCarriageReturn(EngineFactory factory) { assertSameFind("\\s", "\r", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void spaceFormFeed(EngineFactory factory) { assertSameFind("\\s", "\f", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void spaceVerticalTab(EngineFactory factory) { assertSameFind("\\s", "\u000B", factory); }

    // ---- Non-ASCII divergence checks (re2j \w \d are ASCII-only) ----

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void wordNonAsciiLetter(EngineFactory factory) { assertSameFind("\\w", "\u00E9", factory); }     // é
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void wordNonAsciiDigit(EngineFactory factory) { assertSameFind("\\w", "\u0660", factory); }      // Arabic-Indic zero
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void digitNonAscii(EngineFactory factory) { assertSameFind("\\d", "\u0660", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void notWordNonAscii(EngineFactory factory) { assertSameFind("\\W", "\u00E9", factory); }

    // ---- Unicode whitespace divergence check ----
    // re2j \s is ASCII-only: [\t\n\f\r ] — Unicode whitespace like
    // U+00A0, U+2028 etc. should NOT match \s in either engine.

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void spaceNbsp(EngineFactory factory) { assertSameFind("\\s", "\u00A0", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void spaceLineSep(EngineFactory factory) { assertSameFind("\\s", "\u2028", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void spaceParaSep(EngineFactory factory) { assertSameFind("\\s", "\u2029", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void spaceNextLine(EngineFactory factory) { assertSameFind("\\s", "\u0085", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void spaceBom(EngineFactory factory) { assertSameFind("\\s", "\uFEFF", factory); }
}
