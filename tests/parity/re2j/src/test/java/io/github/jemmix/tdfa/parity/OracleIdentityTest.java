package io.github.jemmix.tdfa.parity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which re2j answered: the {@code com.google.re2j} on the test classpath is
 * the jar built from the committed upstream snapshot, and nothing else. A
 * second re2j on the classpath — a stray Maven artifact winning by
 * classpath order, a stale jar from an older snapshot — would silently aim
 * every parity suite and every soak at the wrong oracle; this pin fails
 * instead. The snapshot's commit rides its archive name into this JVM as
 * {@code tdfa.oracle.sha}, and the jar's manifest back out.
 */
class OracleIdentityTest {

    @Test
    void classpathOracleIsTheVendoredSnapshot() {
        if (Re2jOracle.releasedOracle()) {
            return; // -Pfuzz.releasedOracle=true: the released artifact, by choice
        }
        assertThat(Re2jOracle.implementationVersion())
            .as("the oracle jar's Implementation-Version against the vendored archive's commit")
            .isEqualTo(System.getProperty("tdfa.oracle.sha"));
    }
}
