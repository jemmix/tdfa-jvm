package io.github.jemmix.tdfa.parity;

import io.github.jemmix.tdfa.core.RegexEngineFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static io.github.jemmix.tdfa.parity.Re2jOracle.assertSameCompileReject;
import static io.github.jemmix.tdfa.parity.Re2jOracle.assertSameFind;

/**
 * Backreference parity: re2j has no backreferences (linear-time engine), and
 * neither do we — every backref spelling must reject in BOTH engines at
 * compile time, never parse as something else. The adjacent non-reject
 * surface is pinned too: multi-digit and octal escapes ({@code \10},
 * {@code \12}) stay octal character escapes regardless of how many groups
 * precede them, and {@code \8}/{@code \9} (never backrefs in any engine)
 * reject.
 */
class BackrefParityTest {

    // ---- every backref spelling rejects in both engines ----

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void plainBackrefRejects(RegexEngineFactory factory) {
        assertSameCompileReject("(a)\\1", factory);
        assertSameCompileReject("(a)\\1x", factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void backrefBeyondGroupCountRejects(RegexEngineFactory factory) {
        assertSameCompileReject("(a)\\2", factory);
        assertSameCompileReject("((a)\\2)", factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void forwardBackrefRejects(RegexEngineFactory factory) {
        assertSameCompileReject("\\1(a)", factory);
        assertSameCompileReject("(?:\\1a)*", factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void multipleBackrefsReject(RegexEngineFactory factory) {
        assertSameCompileReject("(a)(b)\\2\\1", factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void quantifiedBackrefRejects(RegexEngineFactory factory) {
        assertSameCompileReject("(a)\\1?", factory);
        assertSameCompileReject("(a)\\1*", factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void backrefInClassRejects(RegexEngineFactory factory) {
        assertSameCompileReject("[\\1]", factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void namedBackrefRejects(RegexEngineFactory factory) {
        assertSameCompileReject("(?<n>a)\\k<n>", factory);
        assertSameCompileReject("\\k<n>", factory);
        assertSameCompileReject("(a)\\k<n>", factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void gEscapeBackrefRejects(RegexEngineFactory factory) {
        assertSameCompileReject("\\g{1}", factory);
        assertSameCompileReject("(a)\\g{1}", factory);
    }

    // ---- the octal fallback next to capturing groups ----
    // \1..\7 always reject (above); \10 and up never become "backref 1
    // followed by literal" or "group 10" — they are octal character escapes
    // exactly when re2j says so, groups present or not.

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void multiDigitEscapeStaysOctal(RegexEngineFactory factory) {
        assertSameFind("(a)\\10", "a\b", factory); // \10 = octal 8 = backspace
        assertSameFind("(a)\\10", "a\n", factory); // not newline: no match
        assertSameFind("(a)(b)\\12", "ab\n", factory); // \12 = octal 10 = \n
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void eightAndNineReject(RegexEngineFactory factory) {
        assertSameCompileReject("\\8", factory); // no group, no octal: invalid
        assertSameCompileReject("\\9", factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void nulEscapeMatchesNul(RegexEngineFactory factory) {
        assertSameFind("\\0", "\0", factory);
    }
}
