package io.github.jemmix.tdfa.parity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import io.github.jemmix.tdfa.EngineFactory;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.Disabled;

import static io.github.jemmix.tdfa.parity.Re2jOracle.*;

/**
 * Escape sequence parity: control chars, octal, hex, \C, \Q...\E, special chars.
 */
class EscapeParityTest {

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void newline(EngineFactory factory) { assertSameFind("\\n", "\n", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void tab(EngineFactory factory) { assertSameFind("\\t", "\t", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void carriageReturn(EngineFactory factory) { assertSameFind("\\r", "\r", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void formFeed(EngineFactory factory) { assertSameFind("\\f", "\f", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void alarm(EngineFactory factory) { assertSameFind("\\a", "\u0007", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void verticalTab(EngineFactory factory) { assertSameFind("\\v", "\u000B", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void backslash(EngineFactory factory) { assertSameFind("\\\\", "\\", factory); }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void hexEscape(EngineFactory factory) { assertSameFind("\\x41", "A", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void hexBraced(EngineFactory factory) { assertSameFind("\\x{41}", "A", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void hexBracedUnicode(EngineFactory factory) { assertSameFind("\\x{3B1}", "\u03B1", factory); }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void octalTwoDigit(EngineFactory factory) { assertSameFind("\\12", "\n", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void octalThreeDigit(EngineFactory factory) { assertSameFind("\\101", "A", factory); }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void escapeDot(EngineFactory factory) { assertSameFind("\\.", ".", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void escapeStar(EngineFactory factory) { assertSameFind("\\*", "*", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void escapePlus(EngineFactory factory) { assertSameFind("\\+", "+", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void escapeQuestion(EngineFactory factory) { assertSameFind("\\?", "?", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void escapeParen(EngineFactory factory) { assertSameFind("\\(\\)", "()", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void escapeBracket(EngineFactory factory) { assertSameFind("\\[", "[", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void escapeBrace(EngineFactory factory) { assertSameFind("\\{", "{", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void escapePipe(EngineFactory factory) { assertSameFind("\\|", "|", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void escapeDollar(EngineFactory factory) { assertSameFind("\\$", "$", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void escapeCaret(EngineFactory factory) { assertSameFind("\\^", "^", factory); }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void cEscape(EngineFactory factory) {
        assertSameCompileReject("\\C", factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void quoteLiteral(EngineFactory factory) { assertSameFind("\\Qa.b*c\\E", "a.b*c", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void quoteLiteralNoClose(EngineFactory factory) { assertSameFind("\\Qabc", "abc", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void quoteLiteralPartial(EngineFactory factory) { assertSameFind("x\\Qa.b\\Ey", "xa.by", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void quoteLiteralWithSpecial(EngineFactory factory) { assertSameFind("\\Q[()]\\E", "[()]", factory); }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void octalOneDigit(EngineFactory factory) { assertSameCompileReject("\\1", factory); }

    // ---- Edge cases ----

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void octalNull(EngineFactory factory) { assertSameFind("\\0", "\u0000", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void octalLeadingZero(EngineFactory factory) { assertSameFind("\\012", "\n", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void hexEmpty(EngineFactory factory) { assertSameCompileReject("\\x{}", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void hexNoDigits(EngineFactory factory) { assertSameCompileReject("\\x", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void hexOverflow(EngineFactory factory) { assertSameCompileReject("\\x{FFFFFFFF}", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void quoteEmpty(EngineFactory factory) { assertSameFind("\\Q\\E", "", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void quoteFollowedByQuote(EngineFactory factory) { assertSameFind("\\Qa\\E\\Qb\\E", "ab", factory); }

    // ---- Unknown alphanumeric escape rejection (re2j rejects) ----

    @EnabledIfSystemProperty(named = "tdfa.pending", matches = "true") // PENDING: unknown alphanumeric escapes should be rejected
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void standaloneERejects(EngineFactory factory) { assertSameCompileReject("\\E", factory); }
    @EnabledIfSystemProperty(named = "tdfa.pending", matches = "true") // PENDING: unknown alphanumeric escapes should be rejected
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void escapeKRejects(EngineFactory factory) { assertSameCompileReject("\\K", factory); }
    @EnabledIfSystemProperty(named = "tdfa.pending", matches = "true") // PENDING: unknown alphanumeric escapes should be rejected
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void escapeRRejects(EngineFactory factory) { assertSameCompileReject("\\R", factory); }
    @EnabledIfSystemProperty(named = "tdfa.pending", matches = "true") // PENDING: unknown alphanumeric escapes should be rejected
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void escapeERejects(EngineFactory factory) { assertSameCompileReject("\\e", factory); }
}
