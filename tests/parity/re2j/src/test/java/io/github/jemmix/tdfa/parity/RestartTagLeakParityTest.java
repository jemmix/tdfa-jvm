package io.github.jemmix.tdfa.parity;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The restart-tag leak — the DECIDED domain exclusion, pinned both ways.
 *
 * <p>java.util.regex's {@code Matcher.search} resets the group array once
 * per {@code find()} call, not between the search loop's internal restart
 * attempts. The backtracker commits a capture as an ATOMIC span the moment
 * the capture's own continuation succeeds ({@code GroupTail} and the
 * {@code GroupCurly} iteration writes); when the attempt fails LATER — in
 * the quantifier's exit continuation, a frame the committed write never
 * unwinds through — the span stands. The next (accepted) attempt that never
 * rewrites the group reports it: a span from a FAILED restart attempt,
 * strictly outside the overall match ({@code (?:(\A))*(\n)} on
 * {@code "a\n"}: match {@code 1..2}, g1 {@code 0..0}; the lazy star leaks
 * identically; {@code (a(b))*c} on {@code "aba c"} leaks the INNER atom's
 * span {@code g2 1..2} while g1 itself is restored). Controls that do NOT
 * leak: a plain group whose tail continuation fails ({@code (x)*y} — the
 * unwind restores it) and the {@code Ques} shape ({@code (\A)?x*(a)}).
 *
 * <p><b>Decision: exclude, don't reproduce.</b> Reproducing java's reading
 * bit-for-bit means carrying a failed attempt's committed group state into
 * the accepted match — and that state is a backtracker artifact: it holds
 * only complete spans committed at backtracker-specific points, while a
 * determinized engine streams tag writes along its unit-step walk (a dying
 * walk's registers hold partial spans from the walk prefix, not java's
 * dirty-group state). Faithful reproduction would mean emulating the
 * backtracking discipline the engine exists to replace. Our per-restart
 * reset is the reading java's own documentation describes — group values
 * describe the match — and java's behavior here is a bug its docs disown.
 * {@code CampaignFuzzer}'s {@code SKIP_RESTART_TAG_LEAK} classifies the
 * exact signature (skeleton-equal, span strictly outside the match); this
 * test pins the boundary visibly so the exclusion can never go silent.
 */
class RestartTagLeakParityTest {

    /** pattern, input, leaked oracle spans, clean engine spans. */
    private record Case(String pattern, String input, int[] jurSpans, int[] tdfaSpans) {
    }

    private static Stream<Case> leaks() {
        return Stream.of(
            // the campaign's canonical shape (seed 2819403508500839673)
            new Case("(?:(\\A))*(\\n)", "a\n", new int[]{1, 2, 0, 0, 1, 2}, new int[]{1, 2, -1, -1, 1, 2}),
            // greediness is not the mechanism: the lazy star leaks the same span
            new Case("(?:(\\A))*?(\\n)", "a\n", new int[]{1, 2, 0, 0, 1, 2}, new int[]{1, 2, -1, -1, 1, 2}),
            // the inner atom commits while the outer group's own tail is restored
            new Case("(a(b))*c", "aba c", new int[]{4, 5, -1, -1, 1, 2}, new int[]{4, 5, -1, -1, -1, -1}),
            // leak with a non-capturing tail — only the committed span differs
            new Case("(?:(\\A))*(?:b)", "ab", new int[]{1, 2, 0, 0}, new int[]{1, 2, -1, -1}));
    }

    /** The oracle shows the leak AND the engine shows the clean reading. */
    @ParameterizedTest
    @MethodSource("leaks")
    void leakPinnedBothWays(Case c) {
        assertThat(JurOracle.jurFind(c.pattern(), c.input())).as("live oracle must still leak: %s", c.pattern())
            .isEqualTo(c.jurSpans());
        for (var factory : JurOracle.engineFactories().toList()) {
            assertThat(JurOracle.tdfaFind(c.pattern(), c.input(), factory))
                .as("engine keeps the per-restart reset: %s [%s]", c.pattern(), factory).isEqualTo(c.tdfaSpans());
        }
    }

    /** Shapes whose group writes DO unwind — both engines agree, no leak. */
    @ParameterizedTest
    @MethodSource("controls")
    void controlShapesAgree(Case c) {
        for (var factory : JurOracle.engineFactories().toList()) {
            JurOracle.assertSameFind(c.pattern(), c.input(), factory);
        }
    }

    private static Stream<Case> controls() {
        return Stream.of(
            // the tail continuation fails inside the group: java restores it
            new Case("(x)*y", "zxzy", new int[]{3, 4, -1, -1}, new int[]{3, 4, -1, -1}),
            // the Ques shape: no quantifier re-invocation, no committed write
            new Case("(\\A)?x*(a)", "zxa", new int[]{1, 3, -1, -1, 2, 3}, new int[]{1, 3, -1, -1, 2, 3}));
    }
}
