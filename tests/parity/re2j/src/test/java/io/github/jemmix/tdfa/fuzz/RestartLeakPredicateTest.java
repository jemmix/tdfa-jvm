package io.github.jemmix.tdfa.fuzz;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The restart-tag-leak skip predicate ({@link CampaignFuzzer#leakedRestartSpan}):
 * java.util.regex's {@code Matcher.search} does not reset group writes between
 * a {@code find()}'s failed restart attempts, so a span recorded during a
 * FAILED start survives into the accepted match's protocol — landing strictly
 * OUTSIDE the overall match span, the exact signature no legitimate write can
 * produce ({@code (?:(\A))*(\n)} on the campaign seed 2819403508500839673:
 * {@code g1 0..0} inside a {@code 2..3} match). The seed's record must trip
 * it; in-match group differences (the engine families) must not.
 */
class RestartLeakPredicateTest {

    private static final String LEAK_ORACLE =
        "F=true 2..3 (0..0 2..3) I=[2..3 (0..0 2..3)6..7 (- 6..7)] M=false L=false R=true 6..7 (- 6..7)";
    private static final String LEAK_VM =
        "F=true 2..3 (- 2..3) I=[2..3 (- 2..3)6..7 (- 6..7)] M=false L=false R=true 6..7 (- 6..7)";

    @Test
    void campaignLeakSeedTrips() {
        assertThat(CampaignFuzzer.sameSkeleton(LEAK_ORACLE, LEAK_VM)).isTrue();
        assertThat(CampaignFuzzer.leakedRestartSpan(LEAK_ORACLE, LEAK_VM))
            .as("g1 0..0 strictly before the 2..3 match: the leak signature").isTrue();
    }

    @Test
    void inMatchGroupDifferenceStaysHard() {
        // a group-only divergence INSIDE the match region is an engine family
        // (the zero-width-exit/tail/quirk classes) — not the leak
        assertThat(CampaignFuzzer.leakedRestartSpan("F=true 0..3 (0..2) M=true", "F=true 0..3 (2..3) M=true"))
            .isFalse();
        assertThat(CampaignFuzzer.leakedRestartSpan("F=true 0..3 (1..2) M=true", "F=true 0..3 (-) M=true")).isFalse();
    }

    @Test
    void displacedWriteVariantTripsToo() {
        // the fuzz-found variant: BOTH sides carry a span, but the oracle's
        // sits strictly outside its own match — the leak displaces whatever
        // the accepted path wrote ((?:(.).){3,}Z shapes: a failed earlier
        // start leaves the loop's last atomic span standing past the
        // accepted match). Outside-the-match is the invariant, regardless
        // of our side's clause.
        assertThat(CampaignFuzzer.leakedRestartSpan("F=true 1..26 (34..35) M=false", "F=true 1..26 (22..23) M=false"))
            .as("oracle g1 34..35 past the 1..26 match: the leak, displaced variant").isTrue();
        assertThat(CampaignFuzzer.leakedRestartSpan("F=true 20..30 (20..29 32..33) M=false",
            "F=true 20..30 (20..29 28..29) M=false")).as("g2 32..33 past the 30 end: same").isTrue();
    }

    @Test
    void grouplessProtocolsCannotLeak() {
        assertThat(CampaignFuzzer.leakedRestartSpan("F=true 2..3 M=false", "F=true 2..3 M=false")).isFalse();
        assertThat(CampaignFuzzer.leakedRestartSpan("F=false M=false", "F=false M=false")).isFalse();
    }
}
