package io.github.jemmix.tdfa.fuzz;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.SplittableRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The generator-v4 family axis's own contract: the family generator produces
 * shape-valid, ASCII-only, wide-atom-safe patterns (the properties the soak
 * assumes when it runs family batches through the ordinary per-case
 * protocol), the batch draw is pure and identically replayable through
 * {@link DifferentialFuzzer#generate}, and the per-index input transform
 * honors its embed contract (needle at start/end/middle/doubled, the near
 * miss, the truncation, the one-char mutation). Pure generator tests — no
 * engine compiles; the differential contract itself is the soak's and the
 * campaign families mode's job.
 */
class FamGeneratorTest {

    @Test
    void everyFamilyShapeIsValidAndContractBound() {
        Map<String, Integer> seen = new LinkedHashMap<>();
        SplittableRandom r = new SplittableRandom(0xFA17FA17L);
        for (int i = 0; i < 4000; i++) {
            DifferentialFuzzer.FamPattern fam = DifferentialFuzzer.genFam(r);
            seen.merge(fam.family(), 1, Integer::sum);
            String p = fam.pattern();
            assertThat(p.chars().allMatch(c -> c < 0x80)).as("%s pattern ASCII-only", fam.family()).isTrue();
            switch (fam.family()) {
                case "literal" -> {
                    assertThat(p).as("literal shape").matches("\\\\Q[a-zA-Z0-9_.]{1,8}\\\\E");
                    assertThat(fam.needle()).as("literal needle is the quoted body").isEqualTo(needleOf(p));
                }
                case "prefix" -> {
                    assertThat(p).as("prefix shape").matches("\\\\Q[a-zA-Z0-9_.]{2,6}\\\\E.+");
                    // the divergence tail must not reduce the shape to a pure literal
                    assertThat(p.length()).as("prefix tail present").isGreaterThan(fam.needle().length() + 4);
                }
                case "cand-scan" -> {
                    assertThat(p).as("cand-scan shape is short (<= 64-char window family)").hasSizeLessThan(33);
                    assertThat(fam.needle()).as("set family carries no needle").isNull();
                }
                default -> {
                    assertThat(fam.family()).isEqualTo("anchored-fast");
                    assertThat(p).as("anchored-fast has no anchors (masks kill the fast path)").doesNotContain("\\b",
                        "\\B", "^", "$", "\\A", "\\z", "\\Z");
                    assertThat(fam.needle()).as("set family carries no needle").isNull();
                }
            }
            if (fam.needle() != null) {
                // the family's jur-lane safety contract: no wide atom inside a
                // quantified group (the oracle-bomb guard passes the shape)
                assertThat(DifferentialFuzzer.jurOracleBomb(p)).as("%s shape is not an oracle bomb", fam.family())
                    .isFalse();
            }
        }
        // all four families draw (statistically deterministic given the fixed seed)
        seen.forEach((f, n) -> assertThat(n).as("family %s drew cases", f).isGreaterThan(500));
    }

    /** {@code "\Qabc\E"} → {@code "abc"} (needleOf — inverse of the quote). */
    private static String needleOf(String p) {
        return p.substring(2, p.indexOf("\\E"));
    }

    @Test
    void batchDrawIsPureAndPerIndexInputsHonorTheEmbedContract() {
        for (long batch = 0; batch < 2000; batch++) {
            DifferentialFuzzer.FamPattern fam = DifferentialFuzzer.genFamily(batch);
            if (fam == null) {
                continue;
            }
            String pattern = DifferentialFuzzer.genPattern(batch);
            assertThat(pattern).as("family batch's pattern is the family pattern").isEqualTo(fam.pattern());
            String prior = null;
            for (int idx = 0; idx < DifferentialFuzzer.BATCH_K; idx++) {
                String input = DifferentialFuzzer.genInput(batch, idx, 0);
                // replay purity: generate() must agree with the batch-keyed draw
                DifferentialFuzzer.Case c =
                    DifferentialFuzzer.generate(batch * DifferentialFuzzer.BATCH_K + idx, DifferentialFuzzer.Lane.RE2J);
                assertThat(c.pattern()).as("replay pattern (batch %d idx %d)", batch, idx).isEqualTo(pattern);
                assertThat(c.input()).as("replay input (batch %d idx %d)", batch, idx).isEqualTo(input);
                if (idx > 0) {
                    assertThat(input).as("idx shares the batch pattern").isNotEqualTo(prior);
                }
                prior = input;
                if (fam.needle() == null) {
                    assertThat(input.length()).as("family input stays inside the cand-scan window")
                        .isLessThanOrEqualTo(64);
                    continue;
                }
                String needle = fam.needle();
                switch (idx) {
                    case 0, 7 -> assertThat(input).as("plain embed contains the needle").contains(needle);
                    case 1 -> assertThat(input).as("match-at-start input starts with the needle").startsWith(needle);
                    case 2 -> assertThat(input).as("match-at-end input ends with the needle").endsWith(needle);
                    case 3 -> assertThat(input).as("near miss still contains the needle (the walk must fail)")
                        .contains(needle).contains("\u2603");
                    case 4 -> {
                        assertThat(input).as("doubled needle (the I-probe iteration)").contains(needle);
                        assertThat(input.indexOf(needle)).as("two occurrences").isNotEqualTo(input.lastIndexOf(needle));
                    }
                    // idx 5 (truncated needle) and idx 6 (one-char mutation)
                    // insert ordinary-alphabet chars the body may also carry
                    // by chance, so only the mutation's snowman marker is
                    // deterministically observable from the outside.
                    case 6 -> assertThat(input).as("the mutation marker rides in the input").contains("\u2603");
                    default -> {
                    }
                }
            }
        }
    }

    @Test
    void familyDrawRateIsOneInEight() {
        long fam = 0, total = 0;
        for (long batch = 1; batch <= 20000; batch++) {
            total++;
            if (DifferentialFuzzer.genFamily(batch) != null) {
                fam++;
            }
        }
        assertThat(fam).as("family batches over %d (expect ~%d)", total, total / DifferentialFuzzer.FAM_EVERY)
            .isBetween(total / 10, total / 7);
    }
}
