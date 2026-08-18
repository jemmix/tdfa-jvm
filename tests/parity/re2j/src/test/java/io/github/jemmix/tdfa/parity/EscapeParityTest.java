package io.github.jemmix.tdfa.parity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import io.github.jemmix.tdfa.core.Matcher;
import io.github.jemmix.tdfa.core.PatternSyntaxException;
import io.github.jemmix.tdfa.core.RegexEngineFactory;

import static io.github.jemmix.tdfa.parity.Re2jOracle.*;

/**
 * Escape sequence parity: control chars, octal, hex, \C, \Q...\E, special chars.
 */
class EscapeParityTest {

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void newline(RegexEngineFactory factory) { assertSameFind("\\n", "\n", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void tab(RegexEngineFactory factory) { assertSameFind("\\t", "\t", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void carriageReturn(RegexEngineFactory factory) { assertSameFind("\\r", "\r", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void formFeed(RegexEngineFactory factory) { assertSameFind("\\f", "\f", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void alarm(RegexEngineFactory factory) { assertSameFind("\\a", "\u0007", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void verticalTab(RegexEngineFactory factory) { assertSameFind("\\v", "\u000B", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void backslash(RegexEngineFactory factory) { assertSameFind("\\\\", "\\", factory); }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void hexEscape(RegexEngineFactory factory) { assertSameFind("\\x41", "A", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void hexBraced(RegexEngineFactory factory) { assertSameFind("\\x{41}", "A", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void hexBracedUnicode(RegexEngineFactory factory) { assertSameFind("\\x{3B1}", "\u03B1", factory); }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void octalTwoDigit(RegexEngineFactory factory) { assertSameFind("\\12", "\n", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void octalThreeDigit(RegexEngineFactory factory) { assertSameFind("\\101", "A", factory); }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void escapeDot(RegexEngineFactory factory) { assertSameFind("\\.", ".", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void escapeStar(RegexEngineFactory factory) { assertSameFind("\\*", "*", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void escapePlus(RegexEngineFactory factory) { assertSameFind("\\+", "+", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void escapeQuestion(RegexEngineFactory factory) { assertSameFind("\\?", "?", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void escapeParen(RegexEngineFactory factory) { assertSameFind("\\(\\)", "()", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void escapeBracket(RegexEngineFactory factory) { assertSameFind("\\[", "[", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void escapeBrace(RegexEngineFactory factory) { assertSameFind("\\{", "{", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void escapePipe(RegexEngineFactory factory) { assertSameFind("\\|", "|", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void escapeDollar(RegexEngineFactory factory) { assertSameFind("\\$", "$", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void escapeCaret(RegexEngineFactory factory) { assertSameFind("\\^", "^", factory); }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void cEscape(RegexEngineFactory factory) {
        assertSameCompileReject("\\C", factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void quoteLiteral(RegexEngineFactory factory) { assertSameFind("\\Qa.b*c\\E", "a.b*c", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void quoteLiteralNoClose(RegexEngineFactory factory) { assertSameFind("\\Qabc", "abc", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void quoteLiteralPartial(RegexEngineFactory factory) { assertSameFind("x\\Qa.b\\Ey", "xa.by", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void quoteLiteralWithSpecial(RegexEngineFactory factory) { assertSameFind("\\Q[()]\\E", "[()]", factory); }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void octalOneDigit(RegexEngineFactory factory) { assertSameCompileReject("\\1", factory); }

    // ---- Edge cases ----

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void octalNull(RegexEngineFactory factory) { assertSameFind("\\0", "\u0000", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void octalLeadingZero(RegexEngineFactory factory) { assertSameFind("\\012", "\n", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void hexEmpty(RegexEngineFactory factory) { assertSameCompileReject("\\x{}", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void hexNoDigits(RegexEngineFactory factory) { assertSameCompileReject("\\x", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void hexOverflow(RegexEngineFactory factory) { assertSameCompileReject("\\x{FFFFFFFF}", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void quoteEmpty(RegexEngineFactory factory) { assertSameFind("\\Q\\E", "", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void quoteFollowedByQuote(RegexEngineFactory factory) { assertSameFind("\\Qa\\E\\Qb\\E", "ab", factory); }

    // ---- Unknown alphanumeric escape rejection (re2j rejects) ----

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void standaloneERejects(RegexEngineFactory factory) { assertSameCompileReject("\\E", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void escapeKRejects(RegexEngineFactory factory) { assertSameCompileReject("\\K", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void escapeRRejects(RegexEngineFactory factory) { assertSameCompileReject("\\R", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void escapeERejects(RegexEngineFactory factory) { assertSameCompileReject("\\e", factory); }
}
