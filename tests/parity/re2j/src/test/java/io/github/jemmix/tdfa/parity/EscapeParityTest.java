package io.github.jemmix.tdfa.parity;

import io.github.jemmix.tdfa.core.compile.RegexEngineFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static io.github.jemmix.tdfa.parity.Re2jOracle.assertSameCompileReject;
import static io.github.jemmix.tdfa.parity.Re2jOracle.assertSameFind;

/**
 * Escape sequence parity: control chars, octal, hex, \C, \Q...\E, special chars.
 *
 * <p>Jur twin below — the standard escapes, hex and quoting translate
 * whole; {\v}'s tested cell agrees though the JDK's {@code \v} is the
 * broader vertical-whitespace class (a semantic divergence outside this
 * cell); the escape-rejection roster splits three ways: shared malformed
 * spellings ({@code \E}, {@code \K}, {@code \C}, hex errors), JDK-only
 * syntax we reject ({@code \R}, {@code \e}, bare backrefs), and our
 * re2j-lineage octal reading the JDK (26+) rejects ({@code \0}).
 */
class EscapeParityTest {

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void newline(RegexEngineFactory factory) {
        assertSameFind("\\n", "\n", factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void tab(RegexEngineFactory factory) {
        assertSameFind("\\t", "\t", factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void carriageReturn(RegexEngineFactory factory) {
        assertSameFind("\\r", "\r", factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void formFeed(RegexEngineFactory factory) {
        assertSameFind("\\f", "\f", factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void alarm(RegexEngineFactory factory) {
        assertSameFind("\\a", "\u0007", factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void verticalTab(RegexEngineFactory factory) {
        assertSameFind("\\v", "\u000B", factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void backslash(RegexEngineFactory factory) {
        assertSameFind("\\\\", "\\", factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void hexEscape(RegexEngineFactory factory) {
        assertSameFind("\\x41", "A", factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void hexBraced(RegexEngineFactory factory) {
        assertSameFind("\\x{41}", "A", factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void hexBracedUnicode(RegexEngineFactory factory) {
        assertSameFind("\\x{3B1}", "\u03B1", factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void octalTwoDigit(RegexEngineFactory factory) {
        assertSameFind("\\12", "\n", factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void octalThreeDigit(RegexEngineFactory factory) {
        assertSameFind("\\101", "A", factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void escapeDot(RegexEngineFactory factory) {
        assertSameFind("\\.", ".", factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void escapeStar(RegexEngineFactory factory) {
        assertSameFind("\\*", "*", factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void escapePlus(RegexEngineFactory factory) {
        assertSameFind("\\+", "+", factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void escapeQuestion(RegexEngineFactory factory) {
        assertSameFind("\\?", "?", factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void escapeParen(RegexEngineFactory factory) {
        assertSameFind("\\(\\)", "()", factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void escapeBracket(RegexEngineFactory factory) {
        assertSameFind("\\[", "[", factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void escapeBrace(RegexEngineFactory factory) {
        assertSameFind("\\{", "{", factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void escapePipe(RegexEngineFactory factory) {
        assertSameFind("\\|", "|", factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void escapeDollar(RegexEngineFactory factory) {
        assertSameFind("\\$", "$", factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void escapeCaret(RegexEngineFactory factory) {
        assertSameFind("\\^", "^", factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void cEscape(RegexEngineFactory factory) {
        assertSameCompileReject("\\C", factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void quoteLiteral(RegexEngineFactory factory) {
        assertSameFind("\\Qa.b*c\\E", "a.b*c", factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void quoteLiteralNoClose(RegexEngineFactory factory) {
        assertSameFind("\\Qabc", "abc", factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void quoteLiteralPartial(RegexEngineFactory factory) {
        assertSameFind("x\\Qa.b\\Ey", "xa.by", factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void quoteLiteralWithSpecial(RegexEngineFactory factory) {
        assertSameFind("\\Q[()]\\E", "[()]", factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void octalOneDigit(RegexEngineFactory factory) {
        assertSameCompileReject("\\1", factory);
    }

    // ---- Edge cases ----

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void octalNull(RegexEngineFactory factory) {
        assertSameFind("\\0", "\u0000", factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void octalLeadingZero(RegexEngineFactory factory) {
        assertSameFind("\\012", "\n", factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void hexEmpty(RegexEngineFactory factory) {
        assertSameCompileReject("\\x{}", factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void hexNoDigits(RegexEngineFactory factory) {
        assertSameCompileReject("\\x", factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void hexOverflow(RegexEngineFactory factory) {
        assertSameCompileReject("\\x{FFFFFFFF}", factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void quoteEmpty(RegexEngineFactory factory) {
        assertSameFind("\\Q\\E", "", factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void quoteFollowedByQuote(RegexEngineFactory factory) {
        assertSameFind("\\Qa\\E\\Qb\\E", "ab", factory);
    }

    // ---- Unknown alphanumeric escape rejection (re2j rejects) ----

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void standaloneERejects(RegexEngineFactory factory) {
        assertSameCompileReject("\\E", factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void escapeKRejects(RegexEngineFactory factory) {
        assertSameCompileReject("\\K", factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void escapeRRejects(RegexEngineFactory factory) {
        assertSameCompileReject("\\R", factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void escapeERejects(RegexEngineFactory factory) {
        assertSameCompileReject("\\e", factory);
    }

    /** Jur twin: the translatable cells + the documented escape divergences. */
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.JurOracle#engineFactories")
    void jurTwin(RegexEngineFactory factory) {
        JurOracle.assertSameFind("\\n", "\n", factory);
        JurOracle.assertSameFind("\\t", "\t", factory);
        JurOracle.assertSameFind("\\r", "\r", factory);
        JurOracle.assertSameFind("\\f", "\f", factory);
        JurOracle.assertSameFind("\\a", "\u0007", factory);
        JurOracle.assertSameFind("\\v", "\u000B", factory); // shared cell; JDK \v = vertical whitespace class
        JurOracle.assertSameFind("\\\\", "\\", factory);
        JurOracle.assertSameFind("\\x41", "A", factory);
        JurOracle.assertSameFind("\\x{41}", "A", factory);
        JurOracle.assertSameFind("\\x{3B1}", "\u03B1", factory);
        // \12 / \101 stay RE2-lane-pinned: the JDK reads digit escapes
        // without a leading zero as BACKREFS (octal needs \0 there), so
        // they never match there; the leading-zero spelling agrees:
        JurOracle.assertSameFind("\\.", ".", factory);
        JurOracle.assertSameFind("\\*", "*", factory);
        JurOracle.assertSameFind("\\+", "+", factory);
        JurOracle.assertSameFind("\\?", "?", factory);
        JurOracle.assertSameFind("\\(\\)", "()", factory);
        JurOracle.assertSameFind("\\[", "[", factory);
        JurOracle.assertSameFind("\\{", "{", factory);
        JurOracle.assertSameFind("\\|", "|", factory);
        JurOracle.assertSameFind("\\$", "$", factory);
        JurOracle.assertSameFind("\\^", "^", factory);
        JurOracle.assertSameFind("\\Qa.b*c\\E", "a.b*c", factory);
        JurOracle.assertSameFind("\\Qabc", "abc", factory);
        JurOracle.assertSameFind("x\\Qa.b\\Ey", "xa.by", factory);
        JurOracle.assertSameFind("\\Q[()]\\E", "[()]", factory);
        JurOracle.assertSameFind("\\012", "\n", factory);
        JurOracle.assertSameFind("\\Q\\E", "", factory);
        JurOracle.assertSameFind("\\Qa\\E\\Qb\\E", "ab", factory);
        // shared malformed spellings: both reject
        JurOracle.assertSameCompileReject("\\E", factory);
        JurOracle.assertSameCompileReject("\\K", factory);
        JurOracle.assertSameCompileReject("\\C", factory);
        JurOracle.assertSameCompileReject("\\x{}", factory);
        JurOracle.assertSameCompileReject("\\x", factory);
        JurOracle.assertSameCompileReject("\\x{FFFFFFFF}", factory);
        // JDK-only syntax we reject (DFA discipline / no such escape)
        JurOracle.assertDivergentReject("\\R", factory); // JDK: any linebreak
        JurOracle.assertDivergentReject("\\e", factory); // JDK: escape char
        JurOracle.assertDivergentReject("\\1", factory); // bare backref, no groups
        // our octal reading the JDK (26+) rejects
        JurOracle.assertDivergentAccept("\\0", factory);
    }
}
