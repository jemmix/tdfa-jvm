package io.github.jemmix.tdfa.fuzz;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The campaign harness's own gate, mirroring {@link FuzzSmokeTest}: a
 * fixed-seed slice of every mode must be free of HARD findings, so the
 * generators, lane plumbing and classification cannot rot between campaign
 * runs. Soft kinds (known jur divergence families, budget rejections,
 * skips) are expected and unasserted — inspect the mode dir under
 * {@code build/fuzz-camp} for their counts. A failure here is either a real
 * divergence or broken harness plumbing; {@code -Pcamp.mode=<m>
 * -Pcamp.one=<caseSeed>} replays the recorded case.
 */
class CampaignSmokeTest {

    @Test
    void jurSliceIsClean(@TempDir Path tmp) {
        assertThat(CampaignFuzzer.runMode("jur", 0xCAFEBABEL, 60, 400, tmp))
            .as("hard findings (inspect %s/jur/failures.ndjson)", tmp).isZero();
    }

    @Test
    void stretchSliceIsClean(@TempDir Path tmp) {
        assertThat(CampaignFuzzer.runMode("stretch", 0xCAFEBABEL, 60, 400, tmp))
            .as("hard findings (inspect %s/stretch/failures.ndjson)", tmp).isZero();
    }

    @Test
    void seqSliceIsClean(@TempDir Path tmp) {
        assertThat(CampaignFuzzer.runMode("seq", 0xCAFEBABEL, 60, 400, tmp))
            .as("hard findings (inspect %s/seq/failures.ndjson)", tmp).isZero();
    }

    @Test
    void familiesSliceIsClean(@TempDir Path tmp) {
        assertThat(CampaignFuzzer.runMode("families", 0xCAFEBABEL, 60, 400, tmp))
            .as("hard findings (inspect %s/families/failures.ndjson)", tmp).isZero();
    }
}
