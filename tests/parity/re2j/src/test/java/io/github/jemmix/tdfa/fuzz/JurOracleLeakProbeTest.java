package io.github.jemmix.tdfa.fuzz;

import org.junit.jupiter.api.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The JUR-oracle leak probe ({@link DifferentialFuzzer#JUR_LEAKS}): the fuzz
 * fork runs the vendored exact-restore oracle (vendor/jdk-regex-jemmix/,
 * mounted via --patch-module) by default, and the leak-family soft classes
 * (SKIP_RESTART_TAG_LEAK / SKIP_GROUPCURLY_STALE_WRITE) are gated on the
 * probe — under a fixed oracle every such divergence is a hard finding. This
 * suite runs the STOCK JDK (the patch never touches the test JVM; the parity
 * pins depend on it), so the probe must report the leak present: the
 * discriminator case leaks g1 live, and the constant agrees. Both assertions
 * FAIL ON PURPOSE the day a stock JDK ships the JDK-8381696 fix — the
 * retirement signal, same contract as {@code RestartTagLeakParityTest} and
 * {@code GroupCurlyStaleWriteParityTest}.
 */
class JurOracleLeakProbeTest {

    @Test
    void stockSuiteJvmLeaks() {
        Matcher m = Pattern.compile("(?:(\\A))*(\\n)").matcher("a\n");
        assertThat(m.find()).isTrue();
        assertThat(m.group(1)).as("g1 must leak on the stock JDK (JDK-8381696 unfixed)").isNotNull();
    }

    @Test
    void probeAgreesWithLiveBehavior() {
        assertThat(DifferentialFuzzer.JUR_LEAKS)
            .as("the fuzz leak-family soft classes are active only under a leaking oracle").isTrue();
    }
}
