package io.github.jemmix.tdfa.parity;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import io.github.jemmix.tdfa.core.RegexEngineFactory;

import java.util.random.RandomGenerator;

import static io.github.jemmix.tdfa.parity.Re2jOracle.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Leftmost-longest ({@code LONGEST_MATCH}) CAPTURE parity vs re2j — the
 * boundaries-only coverage in QuantifierParityTest extended to group spans,
 * where POSIX submatch disambiguation (BT19 §7 closure_gtop winner selection)
 * is the hard part: with equal-length overall matches, WHICH alternative's
 * groups get the span.
 *
 * <p>Oracle: re2j 1.8 {@code Pattern.LONGEST_MATCH} (its POSIX mode is the
 * reference our API mirrors). Every case compares the full
 * {@code [start, end, g1s, g1e, ...]} array, both backends.
 *
 * <p>Plus a deterministic randomized differential sweep: small random
 * alternation/quantifier/group patterns over a 3-char alphabet, fixed seed —
 * regenerable failures.
 */
class LongestMatchParityTest {

    // ---- curated capture-ambiguous corpus ----

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void re2DocsSubmatch(RegexEngineFactory factory) { assertSameFindPosix("-|(a)", "aa", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void re2DocsSubmatch2(RegexEngineFactory factory) { assertSameFindPosix("(a)(-|b)", "ab", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void re2DocsSubmatch3(RegexEngineFactory factory) { assertSameFindPosix("-|(a)(b)", "ab", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void alternationEqualLength(RegexEngineFactory factory) { assertSameFindPosix("(a|ab)(c|bcd)", "abcd", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void alternationEqualLength2(RegexEngineFactory factory) { assertSameFindPosix("(a|ab)(c|bcd)(d*)", "abcd", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void alternationPrefixLonger(RegexEngineFactory factory) { assertSameFindPosix("(ab|a)(b?)", "ab", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void alternationPrefixShorter(RegexEngineFactory factory) { assertSameFindPosix("(a|ab)(b?)", "ab", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void nestedAltEqual(RegexEngineFactory factory) { assertSameFindPosix("((a)|(ab))", "ab", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void nestedAltEqual2(RegexEngineFactory factory) { assertSameFindPosix("((ab)|(a))", "ab", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void adjacentGroups(RegexEngineFactory factory) { assertSameFindPosix("(a*)(a*)", "aa", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void adjacentGroupsPlus(RegexEngineFactory factory) { assertSameFindPosix("(a+)(a*)", "aaa", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void adjacentGroupsMixed(RegexEngineFactory factory) { assertSameFindPosix("(a*)(ab)", "aab", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void loopAltGroups(RegexEngineFactory factory) { assertSameFindPosix("x(a|ab)*y", "xaaby", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void loopAltGroups2(RegexEngineFactory factory) { assertSameFindPosix("x(ab|a)*y", "xaaby", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void loopAltSingleIter(RegexEngineFactory factory) { assertSameFindPosix("x(a|ab)+y", "xaby", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void optionalParticipation(RegexEngineFactory factory) { assertSameFindPosix("(a)?(b)", "b", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void optionalParticipation2(RegexEngineFactory factory) { assertSameFindPosix("(a)?(b)?", "", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void optionalParticipation3(RegexEngineFactory factory) { assertSameFindPosix("(a)?(b)?", "a", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void altWithOptionalArm(RegexEngineFactory factory) { assertSameFindPosix("(ab?|a)(c?)", "abc", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void lazyUnderLongest(RegexEngineFactory factory) { assertSameFindPosix("(a+?)(a*)", "aaa", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void lazyUnderLongest2(RegexEngineFactory factory) { assertSameFindPosix("(a|ab)+?(b|c)", "abc", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void anchoredBoth(RegexEngineFactory factory) { assertSameFindPosix("^(a|ab)$", "ab", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void wordBoundaryGroups(RegexEngineFactory factory) { assertSameFindPosix("\\b(a|ab)\\b", "ab", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void wordGroups(RegexEngineFactory factory) { assertSameFindPosix("(\\w+)(\\w+)", "abcd", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void caseInsensitiveLongest(RegexEngineFactory factory) { assertSameFindPosix("(?i)(a|AB)(b|bc)", "abc", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void unicodeLiteralAlt(RegexEngineFactory factory) { assertSameFindPosix("(а|аб)(б|бвд)", "абвд", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void threeWayEqualLength(RegexEngineFactory factory) { assertSameFindPosix("(a|ab|abc)(x|xy)", "abcxy", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void midPatternAlt(RegexEngineFactory factory) { assertSameFindPosix("p(a|ab)q", "pabq", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void midPatternAltSuffixAlt(RegexEngineFactory factory) { assertSameFindPosix("p(a|ab)(q|qr)", "pabqr", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void boundedAlt(RegexEngineFactory factory) { assertSameFindPosix("(a|ab){1,2}", "abab", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void emptyAltArm(RegexEngineFactory factory) { assertSameFindPosix("(a|)(b)", "b", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void unmatchedInLongerAlt(RegexEngineFactory factory) { assertSameFindPosix("(x(a|ab)|(xa)b)", "xab", factory); }

    // ---- randomized differential sweep (fixed seed) ----

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void randomizedDifferential(RegexEngineFactory factory) {
        java.util.random.RandomGeneratorFactory<RandomGenerator> rf =
                java.util.random.RandomGeneratorFactory.of("L64X256MixRandom");
        RandomGenerator rnd = rf.create(20260818L);
        for (int i = 0; i < 3_000; i++) {
            String pattern = randomPattern(rnd);
            String input = randomInput(rnd);
            try {
                int[] expected = re2jFindPosix(pattern, input);
                int[] actual = tdfaFindPosix(pattern, input, factory);
                assertThat(actual)
                        .as("seed-case #%d pattern=\"%s\" input=\"%s\"", i, pattern, input)
                        .isEqualTo(expected);
            } catch (RuntimeException e) {
                // both engines must agree on rejection too; anything else is a bug
                try {
                    com.google.re2j.Pattern.compile(pattern, com.google.re2j.Pattern.LONGEST_MATCH);
                } catch (RuntimeException expectedToo) {
                    continue; // both reject — fine
                }
                throw new AssertionError("case #" + i + " pattern=\"" + pattern + "\" rejected by us only: " + e, e);
            }
        }
    }

    /** Small random pattern over {a,b,c} with alternations, groups, quantifiers —
     *  INCLUDING nested quantifiers ((x*)*, the Fowler nullsubexpr family). */
    static String randomPattern(RandomGenerator rnd) {
        StringBuilder sb = new StringBuilder();
        int parts = 1 + rnd.nextInt(3);
        for (int p = 0; p < parts; p++) {
            switch (rnd.nextInt(8)) {
                case 0 -> sb.append(atom(rnd));
                case 1 -> sb.append('(').append(atom(rnd)).append('|').append(atom(rnd)).append(')');
                case 2 -> sb.append('(').append(atom(rnd)).append(')').append(quant(rnd));
                case 3 -> sb.append('(').append(atom(rnd)).append('|').append(atom(rnd)).append(')').append(quant(rnd));
                case 4 -> sb.append(atom(rnd)).append(quant(rnd));
                case 5 -> sb.append('(').append(atom(rnd)).append(quant(rnd)).append(')');
                // nested quantifiers: quantified group whose body is itself quantified —
                // nullable-body stars, the (a*?)*? submatch-disambiguation family
                case 6 -> sb.append('(').append(atom(rnd)).append(quant(rnd)).append(')').append(quant(rnd));
                case 7 -> sb.append("((").append(atom(rnd)).append(quant(rnd)).append(")|(")
                        .append(atom(rnd)).append(quant(rnd)).append("))").append(quant(rnd));
            }
        }
        return sb.toString();
    }

    static String atom(RandomGenerator rnd) {
        return switch (rnd.nextInt(4)) {
            case 0 -> "a";
            case 1 -> "b";
            case 2 -> "ab";
            case 3 -> "bc";
            default -> "c";
        };
    }

    static String quant(RandomGenerator rnd) {
        return switch (rnd.nextInt(5)) {
            case 0 -> "*";
            case 1 -> "+";
            case 2 -> "?";
            case 3 -> "*?";
            default -> "+?";
        };
    }

    static String randomInput(RandomGenerator rnd) {
        int len = rnd.nextInt(7);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) sb.append("abc".charAt(rnd.nextInt(3)));
        return sb.toString();
    }
}
