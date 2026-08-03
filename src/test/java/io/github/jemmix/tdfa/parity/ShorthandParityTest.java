package io.github.jemmix.tdfa.parity;

import org.junit.jupiter.api.Test;

import static io.github.jemmix.tdfa.parity.Re2jOracle.*;

/**
 * Shorthand class parity: \d \D \w \W \s \S inside and outside character classes,
 * as atoms and under quantifiers.
 */
class ShorthandParityTest {

    @Test void digitAtom() { assertSameFind("\\d", "5"); }
    @Test void digitNoMatch() { assertSameFind("\\d", "x"); }
    @Test void notDigitAtom() { assertSameFind("\\D", "x"); }
    @Test void notDigitNoMatch() { assertSameFind("\\D", "5"); }

    @Test void wordAtom() { assertSameFind("\\w", "a"); }
    @Test void wordUnderscore() { assertSameFind("\\w", "_"); }
    @Test void wordNoMatch() { assertSameFind("\\w", "!"); }
    @Test void notWordAtom() { assertSameFind("\\W", "!"); }

    @Test void spaceAtom() { assertSameFind("\\s", " "); }
    @Test void spaceTab() { assertSameFind("\\s", "\t"); }
    @Test void spaceNewline() { assertSameFind("\\s", "\n"); }
    @Test void notSpaceAtom() { assertSameFind("\\S", "x"); }

    @Test void digitInClass() { assertSameFind("[\\d]", "7"); }
    @Test void wordInClass() { assertSameFind("[\\w]", "z"); }
    @Test void spaceInClass() { assertSameFind("[\\s]", "\t"); }

    @Test void mixedClassWithShorthand() { assertSameFind("[\\w\\s]", " "); }
    @Test void mixedClassDigitWord() { assertSameFind("[\\d\\w]", "a"); }
    @Test void negatedClassWithShorthand() { assertSameFind("[^\\d]", "a"); }

    @Test void digitPlus() { assertSameFind("\\d+", "12345"); }
    @Test void wordStar() { assertSameFind("\\w*", "hello_world"); }
    @Test void spacePlus() { assertSameFind("\\s+", "   "); }

    @Test void findAllDigits() { assertSameAllMatches("\\d", "a1b2c3"); }
    @Test void findAllWords() { assertSameAllMatches("\\w+", "hello world foo"); }

    @Test void complexPattern() { assertSameFind("(\\w+)@(\\w+)\\.(\\w+)", "user@host.com"); }
    @Test void ipPattern() { assertSameFind("(\\d+)\\.(\\d+)\\.(\\d+)\\.(\\d+)", "192.168.1.1"); }

    // ---- Full whitespace coverage ----

    @Test void spaceCarriageReturn() { assertSameFind("\\s", "\r"); }
    @Test void spaceFormFeed() { assertSameFind("\\s", "\f"); }
    @Test void spaceVerticalTab() { assertSameFind("\\s", "\u000B"); }

    // ---- Non-ASCII divergence checks (re2j \w \d are ASCII-only) ----

    @Test void wordNonAsciiLetter() { assertSameFind("\\w", "\u00E9"); }     // é
    @Test void wordNonAsciiDigit() { assertSameFind("\\w", "\u0660"); }      // Arabic-Indic zero
    @Test void digitNonAscii() { assertSameFind("\\d", "\u0660"); }
    @Test void notWordNonAscii() { assertSameFind("\\W", "\u00E9"); }

    // ---- Unicode whitespace divergence check ----
    // re2j \s is ASCII-only: [\t\n\f\r ] — Unicode whitespace like
    // U+00A0, U+2028 etc. should NOT match \s in either engine.

    @Test void spaceNbsp() { assertSameFind("\\s", "\u00A0"); }
    @Test void spaceLineSep() { assertSameFind("\\s", "\u2028"); }
    @Test void spaceParaSep() { assertSameFind("\\s", "\u2029"); }
    @Test void spaceNextLine() { assertSameFind("\\s", "\u0085"); }
    @Test void spaceBom() { assertSameFind("\\s", "\uFEFF"); }
}
