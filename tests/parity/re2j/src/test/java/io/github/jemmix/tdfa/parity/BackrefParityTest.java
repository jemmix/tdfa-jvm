package io.github.jemmix.tdfa.parity;

import io.github.jemmix.tdfa.core.compile.RegexEngineFactory;
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
 *
 * <p>Jur twins: the JDK oracle ACCEPTS backreferences — the rejections pin
 * as the documented accept/reject divergence (DFA discipline,
 * {@link JurOracle#assertDivergentReject}), and the jur lane now applies
 * java's own digit-escape PRECEDENCE (live-JDK probed): every
 * {@code \1}-{\@code \9}-led escape is the backreference reading there
 * ({@code (a)\10} = backref-1 + literal {@code 0}; even dangling
 * {@code (a)\2}, {@code \8} compile), so all of them reject loudly as
 * unsupported — the octal fallback for the multi-digit spellings stays on
 * the :re2j facade only, and in a class the JDK rejects every digit
 * escape except the {@code \0}-led octal forms (bare {@code [\0]} is an
 * octal error there — mirrored).
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

    /**
     * Jur twin: the JDK accepts every backref spelling (divergence pinned);
     * the named-backref {@code \k<n>} spelling it shares with re2j — both
     * reject; the octal fallback agrees except the {@code (a)\10} reading
     * (the JDK prefers the backref-1 + literal interpretation there, so
     * those cells stay RE2-lane-pinned).
     */
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.JurOracle#engineFactories")
    void jurTwin(RegexEngineFactory factory) {
        JurOracle.assertDivergentReject("(a)\\1", factory); // JDK: backref; us: reject
        JurOracle.assertDivergentReject("(a)(b)\\2\\1", factory);
        JurOracle.assertDivergentReject("\\1(a)", factory); // JDK compiles (forward ref)
        JurOracle.assertDivergentReject("(a)\\1?", factory);
        JurOracle.assertDivergentReject("(?<n>a)\\k<n>", factory); // \k<n>: JDK rejects too — but as a
        JurOracle.assertSameCompileReject("\\k<n>", factory); // shared malformed spelling
        // ((a)\10 / (a)(b)\12: the JDK reads them as backrefs (backref-1 +
        // literal digit) — the jur lane now applies that precedence and
        // rejects loudly; the octal readings stay on the :re2j facade)
        JurOracle.assertDivergentReject("(a)\\10", factory); // JDK: backref-1 + '0'
        JurOracle.assertDivergentReject("(a)(b)\\12", factory); // JDK: backref-1 + '2'
        JurOracle.assertDivergentReject("\\101", factory); // JDK: backref-1 + "01"
        JurOracle.assertDivergentReject("\\8", factory); // JDK: backref-8; us: reject
        JurOracle.assertDivergentReject("\\9", factory);
        // bare \0: the JDK (26+) rejects it as an illegal octal escape —
        // and so does the jur lane since the ESCAPES_RE2 axis landed
        // (re2j's NUL reading stays on the :re2j facade)
        JurOracle.assertSameCompileReject("\\0", factory);
    }

    /** Jur class twins (live-JDK probed): java rejects every digit escape
     *  in a class except the {@code \0}-led octal forms — {@code [\1]},
     *  {@code [\10]}, {@code [\8]} and bare {@code [\0]} are all illegal
     *  there. The re2j facade keeps its octal/NUL readings (pinned above
     *  via the re2j oracle). */
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.JurOracle#engineFactories")
    void jurClassDigitEscapesMirrorTheJdk(RegexEngineFactory factory) {
        JurOracle.assertSameCompileReject("[\\1]", factory);
        JurOracle.assertSameCompileReject("[\\10]", factory);
        JurOracle.assertSameCompileReject("[\\77]", factory);
        JurOracle.assertSameCompileReject("[\\8]", factory);
        JurOracle.assertSameCompileReject("[\\0]", factory); // java: illegal octal escape
        JurOracle.assertSameFind("[\\05]", "\u0005", factory); // \0-led octal agrees
        JurOracle.assertSameFind("[\\012]", "\n", factory);
    }
}
