package io.github.jemmix.tdfa.parity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Disabled;

import static io.github.jemmix.tdfa.parity.Re2jOracle.*;

/**
 * Escape sequence parity: control chars, octal, hex, \C, \Q...\E, special chars.
 */
class EscapeParityTest {

    @Test void newline() { assertSameFind("\\n", "\n"); }
    @Test void tab() { assertSameFind("\\t", "\t"); }
    @Test void carriageReturn() { assertSameFind("\\r", "\r"); }
    @Test void formFeed() { assertSameFind("\\f", "\f"); }
    @Test void alarm() { assertSameFind("\\a", "\u0007"); }
    @Test void verticalTab() { assertSameFind("\\v", "\u000B"); }
    @Test void backslash() { assertSameFind("\\\\", "\\"); }

    @Test void hexEscape() { assertSameFind("\\x41", "A"); }
    @Test void hexBraced() { assertSameFind("\\x{41}", "A"); }
    @Test void hexBracedUnicode() { assertSameFind("\\x{3B1}", "\u03B1"); }

    @Test void octalTwoDigit() { assertSameFind("\\12", "\n"); }
    @Test void octalThreeDigit() { assertSameFind("\\101", "A"); }

    @Test void escapeDot() { assertSameFind("\\.", "."); }
    @Test void escapeStar() { assertSameFind("\\*", "*"); }
    @Test void escapePlus() { assertSameFind("\\+", "+"); }
    @Test void escapeQuestion() { assertSameFind("\\?", "?"); }
    @Test void escapeParen() { assertSameFind("\\(\\)", "()"); }
    @Test void escapeBracket() { assertSameFind("\\[", "["); }
    @Test void escapeBrace() { assertSameFind("\\{", "{"); }
    @Test void escapePipe() { assertSameFind("\\|", "|"); }
    @Test void escapeDollar() { assertSameFind("\\$", "$"); }
    @Test void escapeCaret() { assertSameFind("\\^", "^"); }

    @Test void cEscape() {
        assertSameCompileReject("\\C");
    }

    @Test
    @Disabled("TDFA_MISSING: \\Q...\\E literal quoting not supported")
    void quoteLiteral() { assertSameFind("\\Qa.b*c\\E", "a.b*c"); }
    @Test
    @Disabled("TDFA_MISSING: \\Q...\\E literal quoting not supported")
    void quoteLiteralNoClose() { assertSameFind("\\Qabc", "abc"); }
    @Test
    @Disabled("TDFA_MISSING: \\Q...\\E literal quoting not supported")
    void quoteLiteralPartial() { assertSameFind("x\\Qa.b\\Ey", "xa.by"); }
    @Test
    @Disabled("TDFA_MISSING: \\Q...\\E literal quoting not supported")
    void quoteLiteralWithSpecial() { assertSameFind("\\Q[()]\\E", "[()]"); }

    @Test
    @Disabled("TDFA_MISSING: \\1 accepted by us (octal), rejected by re2j (invalid escape — reserved for backref)")
    void octalOneDigit() { assertSameFind("\\1", "\u0001"); }
}
