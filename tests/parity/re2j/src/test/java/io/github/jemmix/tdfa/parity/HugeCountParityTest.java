package io.github.jemmix.tdfa.parity;

import io.github.jemmix.tdfa.Pattern;
import io.github.jemmix.tdfa.core.budget.PatternTooLargeException;
import io.github.jemmix.tdfa.core.compile.RegexEngineFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static io.github.jemmix.tdfa.parity.Re2jOracle.assertSameFind;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Huge-count parity: repeat counts at re2j's 1000 cap and — beyond the cap —
 * the effective counts reachable by NESTING counted groups, where the two
 * engines diverge by design.
 *
 * <p><b>Match parity.</b> Nested products of exact counts (pure
 * concatenation shapes) compile and match identically in both engines for
 * effective counts well past 1000 — spans, off-by-one boundaries and
 * group-tail values included.
 *
 * <p><b>Budget rejections (by design).</b> Products with alternation-shaped
 * bodies — {@code (a{2,3}){1000}} and friends — multiply the TNFA /
 * determinization state space combinatorially. re2j happily expands them
 * (its program is a flat thread list); we reject with the standard clean
 * {@code "pattern too large"} budget error rather than exhausting time and
 * memory — the documented contract (README "pattern too large"). The oracle
 * is deliberately not run on those shapes: the divergence is the budget
 * class, not a result mismatch; re2j is invoked only where parity is the
 * claim.
 */
class HugeCountParityTest {

    private static final String A999 = "a".repeat(999);

    private static final String A1000 = "a".repeat(1000);

    private static final String A1001 = "a".repeat(1001);

    // ---- match parity at the 1000 cap ----

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void exactCapMatches(RegexEngineFactory factory) {
        assertSameFind("a{1000}", A1000, factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void exactCapOneShortFails(RegexEngineFactory factory) {
        assertSameFind("a{1000}", A999, factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void nestedProductMatchesAtCap(RegexEngineFactory factory) {
        assertSameFind("(a{500}){2}", A1000, factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void nestedProductOneShortFails(RegexEngineFactory factory) {
        assertSameFind("(a{500}){2}", A999, factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void nestedProductOverCapMatches(RegexEngineFactory factory) {
        assertSameFind("(a{500}){3}", "a".repeat(1500), factory); // effective 1500 > cap
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void nestedProductOffByOneFails(RegexEngineFactory factory) {
        assertSameFind("(a{750}){2}", "a".repeat(1499), factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void nestedProductJustOverCapFails(RegexEngineFactory factory) {
        assertSameFind("(a{1000}){2}", A1001, factory); // needs 2000 a's
    }

    // ---- match parity: group spans under nested counts ----

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void nestedProductGroupTailIsLastIteration(RegexEngineFactory factory) {
        assertSameFind("(a{10}){100}", A1000, factory); // g1 = last "aaaaaaaaaa"
        assertSameFind("(a{3}){999}", "a".repeat(2997), factory); // g1 = last "aaa"
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void doublyNestedProductSpans(RegexEngineFactory factory) {
        assertSameFind("((a{10}){10}){10}", A1000, factory);
        assertSameFind("((a{5}){2}){100}", A1000, factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void nestedRangeProductMatches(RegexEngineFactory factory) {
        assertSameFind("(a{3,5}){100}", "a".repeat(400), factory); // 100 × greedy 3 + ...
    }

    // ---- budget rejections: nested products re2j accepts, we refuse ----
    // (determinization/TNFA budget classes — the clean "pattern too large"
    // family; see class javadoc for why the oracle is not run here)

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void millionLetterBombRejectsCleanly(RegexEngineFactory factory) {
        assertThatThrownBy(() -> Pattern.compile("(a{1000}){1000}", 0, factory, null))
            .isInstanceOf(PatternTooLargeException.class).hasMessageContaining("pattern too large");
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void rangeBodyBombRejectsCleanly(RegexEngineFactory factory) {
        assertThatThrownBy(() -> Pattern.compile("(a{2,3}){1000}", 0, factory, null))
            .isInstanceOf(PatternTooLargeException.class).hasMessageContaining("pattern too large");
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void nullableBodyBombRejectsCleanly(RegexEngineFactory factory) {
        assertThatThrownBy(() -> Pattern.compile("(a{0,1000}){1000}", 0, factory, null))
            .isInstanceOf(PatternTooLargeException.class).hasMessageContaining("pattern too large");
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void cubedCountBombRejectsCleanly(RegexEngineFactory factory) {
        assertThatThrownBy(() -> Pattern.compile("((a{300}){300}){300}", 0, factory, null))
            .isInstanceOf(PatternTooLargeException.class).hasMessageContaining("pattern too large");
    }
}
