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

    @Test void quoteLiteral() { assertSameFind("\\Qa.b*c\\E", "a.b*c"); }
    @Test void quoteLiteralNoClose() { assertSameFind("\\Qabc", "abc"); }
    @Test void quoteLiteralPartial() { assertSameFind("x\\Qa.b\\Ey", "xa.by"); }
    @Test void quoteLiteralWithSpecial() { assertSameFind("\\Q[()]\\E", "[()]"); }

    @Test
    void octalOneDigit() { assertSameCompileReject("\\1"); }

    // ---- Edge cases ----

    @Test void octalNull() { assertSameFind("\\0", "\u0000"); }
    @Test void octalLeadingZero() { assertSameFind("\\012", "\n"); }
    @Test void hexEmpty() { assertSameCompileReject("\\x{}"); }
    @Test void hexNoDigits() { assertSameCompileReject("\\x"); }
    @Test void hexOverflow() { assertSameCompileReject("\\x{FFFFFFFF}"); }
    @Test void quoteEmpty() { assertSameFind("\\Q\\E", ""); }
    @Test void quoteFollowedByQuote() { assertSameFind("\\Qa\\E\\Qb\\E", "ab"); }

    // ---- Unknown alphanumeric escape rejection (re2j rejects) ----

    @Test void standaloneERejects() { assertSameCompileReject("\\E"); }
    @Test void escapeKRejects() { assertSameCompileReject("\\K"); }
    @Test void escapeRRejects() { assertSameCompileReject("\\R"); }
    @Test void escapeERejects() { assertSameCompileReject("\\e"); }
}
