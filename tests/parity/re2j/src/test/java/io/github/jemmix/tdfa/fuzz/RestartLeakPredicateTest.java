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
    void grouplessProtocolsCannotLeak() {
        assertThat(CampaignFuzzer.leakedRestartSpan("F=true 2..3 M=false", "F=true 2..3 M=false")).isFalse();
        assertThat(CampaignFuzzer.leakedRestartSpan("F=false M=false", "F=false M=false")).isFalse();
    }
}
