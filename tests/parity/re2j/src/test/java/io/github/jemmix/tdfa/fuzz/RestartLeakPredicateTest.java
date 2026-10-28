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
 * {@code g1 0..0} inside a {@code 2..3} match). Every probe runs its own
 * failed starts, so the predicate reads the F= first match, EVERY I=
 * iteration entry, and the R= restart clause (2026-10-24 finding 2: the I/R
 * leaks landed as RESULT_MISMATCH). The seeds' records must trip it; in-match
 * group differences (the engine families) must not.
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

    @Test
    void finding2IterationLeaksTrip() {
        // the 2026-10-24 finding-2 shapes: the F= clause agrees, the leak
        // rides an I= iteration entry (find() continuation restarts)
        assertThat(CampaignFuzzer.leakedRestartSpan( // seed 5354081794006039706
            "F=true 0..1 (- -) I=[0..1 (- -)1..2 (- -)10..11 (- 4..5)31..32 (- -)32..33 (- -)]"
                + " M=false L=true 0..1 (- -) R=true 31..32 (- -)",
            "F=true 0..1 (- -) I=[0..1 (- -)1..2 (- -)10..11 (- -)31..32 (- -)32..33 (- -)]"
                + " M=false L=true 0..1 (- -) R=true 31..32 (- -)"))
            .as("I entry 10..11 (- 4..5): g2 strictly before the entry's span").isTrue();
        assertThat(CampaignFuzzer.leakedRestartSpan( // seed 6211629843725923612
            "F=true 3..4 (-) I=[3..4 (-)6..7 (4..5)] M=false L=false R=true 6..7 (-)",
            "F=true 3..4 (-) I=[3..4 (-)6..7 (-)] M=false L=false R=true 6..7 (-)"))
            .as("I entry 6..7 (4..5): g1 strictly before the entry's span").isTrue();
        assertThat(CampaignFuzzer.leakedRestartSpan( // seed 4633556065707114785
            "F=true 0..1 (-) I=[0..1 (-)1..3 (-)3..4 (4..5)4..5 (-)5..6 (-)8..10 (-)10..12 (-)] M=false L=true 0..1 (-)"
                + " R=true 8..10 (-)",
            "F=true 0..1 (-) I=[0..1 (-)1..3 (-)3..4 (-)4..5 (-)5..6 (-)8..10 (-)10..12 (-)] M=false L=true 0..1 (-)"
                + " R=true 8..10 (-)"))
            .as("I entry 3..4 (4..5): g1 strictly past the entry's span").isTrue();
    }

    @Test
    void restartProbeLeakTrips() {
        // the R= probe (find(len/2) on a fresh matcher) runs failed starts
        // of its own — the leaked span can land outside ITS accepted match
        assertThat(CampaignFuzzer.leakedRestartSpan(
            "F=true 0..1 (- -) I=[0..1 (- -)] M=false L=true 0..1 (- -) R=true 31..32 (- 4..5)",
            "F=true 0..1 (- -) I=[0..1 (- -)] M=false L=true 0..1 (- -) R=true 31..32 (- -)"))
            .as("R clause 31..32 (- 4..5): g2 strictly outside the restart match").isTrue();
    }

    @Test
    void inMatchIterationDifferenceStaysHard() {
        // the finding-3 participation family: the oracle's group sits INSIDE
        // the entry's overall span (the stale-write leak never does) — an
        // engine question, not the leak
        assertThat(CampaignFuzzer.leakedRestartSpan("F=true 0..1 (- -) I=[2..3 (- 2..3)] M=false L=false R=false",
            "F=true 0..1 (- -) I=[2..3 (- -)] M=false L=false R=false"))
            .as("I entry group inside its span: the (X)*tail family, stays hard").isFalse();
        // a group-count disagreement between the sides is a divergence, not
        // the decided leak shape
        assertThat(CampaignFuzzer.leakedRestartSpan("F=true 0..1 (- -) I=[0..1 (- 4..5)] M=false L=false R=false",
            "F=true 0..1 (- -) I=[0..1 (-)] M=false L=false R=false")).as("entry group counts differ: not the leak")
            .isFalse();
    }
}
