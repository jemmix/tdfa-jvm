package io.github.jemmix.tdfa.fuzz;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The GroupCurly stale-iteration skip predicate
 * ({@link CampaignFuzzer#staleIterationSpan} +
 * {@link CampaignFuzzer#STALE_ITERATION_SHAPE}): java.util.regex's
 * {@code GroupCurly.match0} backoff rewrites only the
 * directly-quantified group's registers, so a NESTED capture's
 * {@code GroupTail} write survives the unwind — a DISCARDED greedy
 * iteration's span reporting through the accepted match, INSIDE it (the
 * strictly-outside twin is the restart-tag leak, checked first). The
 * campaign seeds' records (2026-10-24 finding 3, all six — including the
 * triage record whose overall-span half no longer reproduces) must trip
 * predicate + shape; the leak family, the displaced twin (our side
 * carries the live participating span) and the #87 sticky shapes must
 * not — they stay hard evidence.
 */
class StaleIterationPredicateTest {

    @Test
    void campaignSeedsTrip() {
        // seed 7340427838858965240 — `((\s))*\s`, F= stale span equals the
        // one-unit match, R= the same shape at the tab
        assertThat(stale("((\\s))*\\s",
            "F=true 4..5 (- 4..5) I=[4..5 (- 4..5)15..16 (- 15..16)] M=false L=false" + " R=true 15..16 (- 15..16)",
            "F=true 4..5 (- -) I=[4..5 (- -)15..16 (- -)] M=false L=false" + " R=true 15..16 (- -)"))
            .as("seed 7340427838858965240: oracle g2 4..5 in-span, ours none").isTrue();
        // seed 2135724555999141656 — `(?:(b))*b`
        assertThat(
            stale("(?:(b))*b", "F=true 18..19 (18..19) I=[18..19 (18..19)] M=false L=false R=true 18..19 (18..19)",
                "F=true 18..19 (-) I=[18..19 (-)] M=false L=false R=true 18..19 (-)"))
            .as("seed 2135724555999141656").isTrue();
        // seed 1900010144022828840 — `(?:( ))* ` (flags 2 at draw time)
        assertThat(stale("(?:( ))* ", "F=true 4..5 (4..5) I=[4..5 (4..5)] M=false L=false R=false",
            "F=true 4..5 (-) I=[4..5 (-)] M=false L=false R=false")).as("seed 1900010144022828840").isTrue();
        // seed 1606260156672107336 — the supplementary twin, L= clause
        assertThat(stale("((\ud800\udc00))*\ud800\udc00",
            "F=true 0..2 (- 0..2) I=[0..2 (- 0..2)] M=false L=true 0..2 (- 0..2)" + " R=false",
            "F=true 0..2 (- -) I=[0..2 (- -)] M=false L=true 0..2 (- -) R=false")).as("seed 1606260156672107336")
            .isTrue();
        // seed 179386094230408747 — the failed-restart variant: the write
        // came from the attempt at 3, the accepted attempt at 4 reports it
        assertThat(stale("(?:.(9)){0,}\\d", "F=true 4..5 (4..5) I=[4..5 (4..5)] M=false L=false R=false",
            "F=true 4..5 (-) I=[4..5 (-)] M=false L=false R=false")).as("seed 179386094230408747").isTrue();
        // the triage record 2858084537373459748 — skeleton agrees at HEAD,
        // the residual innermost g3 3..5 is this family
        assertThat(stale("((((\ud800\udfff)){0,}\\D)(\udc00))x?",
            "F=true 3..6 (3..5 - 3..5 5..6) I=[3..6 (3..5 - 3..5 5..6)] M=false L=false R=false",
            "F=true 3..6 (3..5 - - 5..6) I=[3..6 (3..5 - - 5..6)] M=false L=false R=false"))
            .as("triage seed 2858084537373459748: g3 3..5 in-span, ours none").isTrue();
    }

    @Test
    void leakFamilyStaysWithTheLeakPredicate() {
        // the strictly-outside twin belongs to leakedRestartSpan (checked
        // first); staleIterationSpan must not also claim in-match-only
        // differences that carry no engine-side absence
        assertThat(stale("((\\s))*\\s", "F=true 2..3 (0..0 2..3) M=false", "F=true 2..3 (- 2..3) M=false"))
            .as("g1 0..0 strictly before the match: the leak, not this family").isFalse();
    }

    @Test
    void displacedTwinStaysHard() {
        // our side reports the LIVE participating iteration's span — a
        // span the engine does report is hard evidence, the skip must not
        // swallow it (pinned in GroupCurlyStaleWriteParityTest)
        assertThat(stale("(?:( ))* ", "F=true 1..3 (2..3) M=false", "F=true 1..3 (1..2) M=false"))
            .as("both sides carry a span: the displaced twin stays hard").isFalse();
    }

    @Test
    void stickyShapesStayHard() {
        // the #87 sticky family (inner capture itself quantified) compares
        // hard since the sticky split — the shape gate must not match
        assertThat(CampaignFuzzer.STALE_ITERATION_SHAPE.matcher("((a)*b)*").find()).isFalse();
        assertThat(CampaignFuzzer.STALE_ITERATION_SHAPE.matcher("(?:(a)*b)*").find()).isFalse();
        assertThat(CampaignFuzzer.STALE_ITERATION_SHAPE.matcher("((a)?b)*").find()).isFalse();
        // deeper nestings are deliberately outside the gate (conservative)
        assertThat(CampaignFuzzer.STALE_ITERATION_SHAPE.matcher("(((x)))*x").find()).isFalse();
        assertThat(CampaignFuzzer.STALE_ITERATION_SHAPE.matcher("(?:(?:ab)(9))*x").find()).isFalse();
    }

    @Test
    void shapeGateMatchesTheFamilySpellings() {
        for (String p : new String[]{"((\\s))*\\s", "(?:(b))*b", "(?:( ))* ", "((\ud800\udc00))*\ud800\udc00",
            "(?:.(9)){0,}\\d", "((((\ud800\udfff)){0,}\\D)(\udc00))x?", "(?:(b)){1,3}b", "(?:x(9)y)*z", "((a)){0,}a"}) {
            assertThat(CampaignFuzzer.STALE_ITERATION_SHAPE.matcher(p).find())
                .as("the family spelling must gate: %s", p).isTrue();
        }
    }

    @Test
    void grouplessProtocolsCannotCarryIt() {
        assertThat(stale("((\\s))*\\s", "F=true 2..3 M=false", "F=true 2..3 M=false")).isFalse();
        assertThat(stale("((\\s))*\\s", "F=false M=false", "F=false M=false")).isFalse();
    }

    @Test
    void classifierClaimsTheCampaignSeeds() {
        // end-to-end: the seeds' recorded protocols run through the real
        // domain classifier (the batch path's reclassification), each
        // landing as the documented skip
        claim("((\\s))*\\s",
            "F=true 4..5 (- 4..5) I=[4..5 (- 4..5)15..16 (- 15..16)] M=false L=false" + " R=true 15..16 (- 15..16)",
            "F=true 4..5 (- -) I=[4..5 (- -)15..16 (- -)] M=false L=false" + " R=true 15..16 (- -)");
        claim("(?:(b))*b", "F=true 18..19 (18..19) I=[18..19 (18..19)] M=false L=false R=true 18..19 (18..19)",
            "F=true 18..19 (-) I=[18..19 (-)] M=false L=false R=true 18..19 (-)");
        claim("(?:( ))* ", "F=true 4..5 (4..5) I=[4..5 (4..5)] M=false L=false R=false",
            "F=true 4..5 (-) I=[4..5 (-)] M=false L=false R=false");
        claim("((\ud800\udc00))*\ud800\udc00",
            "F=true 0..2 (- 0..2) I=[0..2 (- 0..2)] M=false L=true 0..2 (- 0..2)" + " R=false",
            "F=true 0..2 (- -) I=[0..2 (- -)] M=false L=true 0..2 (- -) R=false");
        claim("(?:.(9)){0,}\\d", "F=true 4..5 (4..5) I=[4..5 (4..5)] M=false L=false R=false",
            "F=true 4..5 (-) I=[4..5 (-)] M=false L=false R=false");
        // the triage record: skeleton agrees at HEAD, only g3 3..5 differs
        claim("((((\ud800\udfff)){0,}\\D)(\udc00))x?",
            "F=true 3..6 (3..5 - 3..5 5..6) I=[3..6 (3..5 - 3..5 5..6)] M=false L=false R=false",
            "F=true 3..6 (3..5 - - 5..6) I=[3..6 (3..5 - - 5..6)] M=false L=false R=false");
    }

    private static void claim(String pattern, String oracle, String vm) {
        DifferentialFuzzer.Outcome o =
            new DifferentialFuzzer.Outcome(new DifferentialFuzzer.Case(pattern, "", 0, DifferentialFuzzer.Lane.JUR));
        o.oracle = oracle;
        o.vm = vm;
        assertThat(DifferentialFuzzer.Results.jurLaneDomain(o)).as("the classifier must claim %s", pattern)
            .isEqualTo("groupcurly-stale-write");
    }

    /** The skip's full gate: skeleton + shape + signature. */
    private static boolean stale(String pattern, String oracle, String vm) {
        return CampaignFuzzer.sameSkeleton(oracle, vm) && CampaignFuzzer.STALE_ITERATION_SHAPE.matcher(pattern).find()
            && CampaignFuzzer.staleIterationSpan(oracle, vm);
    }
}
