package io.github.jemmix.tdfa.parity;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import io.github.jemmix.tdfa.core.RegexEngineFactory;

import static io.github.jemmix.tdfa.parity.Re2jOracle.*;

/**
 * Parser-hardening parity (2026-09 pre-freeze review): re2j arbitrates the
 * exact semantics of the four fix families —
 * 1. group-scoped inline flags (every ')' restores the flags saved at its
 *    '('; flag-only groups persist into the enclosing group),
 * 2. "missing argument to repetition operator" (quantifier at atom position),
 * 3. "invalid nested repetition operator" (quantifier after a quantifier —
 *    previously a*{2} silently parsed as a* followed by literal "{2}"),
 * 4. repeat-count cap / ASCII-digit braces / group-name validity / hex
 *    overflow messages (previously raw NumberFormatException or silent
 *    Arabic-Indic-digit misparse).
 * All expectations verified against vendored re2j 1.8 before landing.
 */
class ParserHardeningParityTest {

    // ---- 1. group-scoped inline flags ----

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void scopedIInsideGroupFoldsOnlyGroup(RegexEngineFactory factory) {
        assertSameFind("((?i)a)b", "Ab", factory);   // b NOT folded
    }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void scopedIInsideGroupDoesNotFoldAfter(RegexEngineFactory factory) {
        assertSameFind("((?i)a)b", "AB", factory);   // no match
    }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void scopedIInNonCapturingGroup(RegexEngineFactory factory) {
        assertSameFind("(?:(?i)a)b", "AB", factory);
    }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void flagFormColonStillScopes(RegexEngineFactory factory) {
        assertSameFind("(?i:a)b", "AB", factory);
    }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void nestedThreeDeep(RegexEngineFactory factory) {
        assertSameFind("(((?i)a))b", "AB", factory);
    }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void flagOutsideGroupPersists(RegexEngineFactory factory) {
        assertSameFind("(?i)(a)b", "AB", factory);   // set before group: applies after too
    }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void negatedFlagInsideGroupRestores(RegexEngineFactory factory) {
        assertSameFind("(?i)((?-i)a)b", "Ab", factory);
        assertSameFind("(?i)((?-i)a)b", "aB", factory);   // b still folded (outer i)
    }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void scopedMultilineDollar(RegexEngineFactory factory) {
        assertSameFind("((?m)a)b$", "ab\n", factory);     // $ absolute: no match
        assertSameFind("(?m:(a)b)$", "ab\ncd", factory);  // $ line-flavored inside
    }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void scopedDotall(RegexEngineFactory factory) {
        assertSameFind("((?s).)x", "\nx", factory);
        assertSameFind("((?s)\\w)x", "\nx", factory);     // \w unaffected by s; \n can't match
    }

    // ---- 2. missing argument to repetition operator ----

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void leadingStarRejected(RegexEngineFactory factory) { assertSameCompileReject("*a", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void leadingPlusRejected(RegexEngineFactory factory) { assertSameCompileReject("+a", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void leadingQuestionRejected(RegexEngineFactory factory) { assertSameCompileReject("?a", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void afterOpenParenRejected(RegexEngineFactory factory) { assertSameCompileReject("(+a)", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void afterAltRejected(RegexEngineFactory factory) { assertSameCompileReject("a|*b", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void leadingBraceQuantRejected(RegexEngineFactory factory) { assertSameCompileReject("{2}a", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void invalidBraceStaysLiteral(RegexEngineFactory factory) {
        assertSameFind("a{,2}", "a{,2}", factory);   // not a quantifier: literal
        assertSameFind("a{", "a{", factory);
    }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void emptyGroupStillQuantifiable(RegexEngineFactory factory) {
        assertSameFind("()*", "", factory);
        assertSameFind("(?:)*", "", factory);
    }

    // ---- 3. invalid nested repetition operator ----

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void doubleStarRejected(RegexEngineFactory factory) { assertSameCompileReject("a**", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void starBraceRejectedNotLiteral(RegexEngineFactory factory) { assertSameCompileReject("a*{2}", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void braceBraceRejected(RegexEngineFactory factory) { assertSameCompileReject("a{2}{3}", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void possessivePlusRejected(RegexEngineFactory factory) { assertSameCompileReject("a*+", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void doublePossessiveRejected(RegexEngineFactory factory) { assertSameCompileReject("a++", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void tripleQuestionRejected(RegexEngineFactory factory) { assertSameCompileReject("a???", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void quantThenStarRejected(RegexEngineFactory factory) { assertSameCompileReject("a?*", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void lazyStillLegal(RegexEngineFactory factory) {
        assertSameFind("ab??", "ab", factory);
        assertSameFind("a{2,4}?", "aaaa", factory);
    }

    // ---- 4. repeat-count cap, digit/brace/named-capture/hex hygiene ----

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void repeatCapAt1000Accepted(RegexEngineFactory factory) { assertSameCompileSuccess("a{1000}", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void repeatCapOpenUpperAccepted(RegexEngineFactory factory) { assertSameCompileSuccess("a{1000,}", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void repeatCap1001Rejected(RegexEngineFactory factory) { assertSameCompileReject("a{1001}", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void repeatCapMaxRejected(RegexEngineFactory factory) { assertSameCompileReject("a{0,1001}", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void repeatOverflowTextRejected(RegexEngineFactory factory) { assertSameCompileReject("a{2147483648}", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void nonAsciiDigitsAreLiteral(RegexEngineFactory factory) {
        // re2j: {٥} is not a quantifier (ASCII digits only) — matches literally
        assertSameFind("a{٥}", "a{٥}", factory);
        assertSameFind("a{٥}", "aaaaa", factory);
    }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void groupNameSpaceRejected(RegexEngineFactory factory) { assertSameCompileReject("(?<a b>x)", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void groupNameDashRejected(RegexEngineFactory factory) { assertSameCompileReject("(?<a-b>x)", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void groupNameEmptyRejected(RegexEngineFactory factory) { assertSameCompileReject("(?<>x)", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void groupNameDigitFirstAccepted(RegexEngineFactory factory) {
        assertSameFind("(?<1a>x)y", "xy", factory);
    }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void hexAboveCodepointRejected(RegexEngineFactory factory) { assertSameCompileReject("\\x{110000}", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void hexOverflowRejected(RegexEngineFactory factory) { assertSameCompileReject("\\x{1100000}", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void hexMaxCodepointAccepted(RegexEngineFactory factory) { assertSameCompileSuccess("\\x{10FFFF}", factory); }
}
