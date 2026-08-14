package io.github.jemmix.tdfa;

import io.github.jemmix.tdfa.tdfa.Disambiguation;
import io.github.jemmix.tdfa.vm.MatchResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Unicode property classes ({@code \p{L}}, {@code \P{N}}, etc.) at scale:
 * DFA state and range-table sizes that exceed internal packing limits.
 *
 * <p>Problem areas:
 * <ul>
 *   <li><b>stateBase overflow</b> — {@code \p{L}} has ~1369 Unicode ranges per
 *       DFA state. The old 15-bit range-base field overflowed at ~state 24.
 *       Fixed by splitting into a separate full-32-bit {@code stateBase[]}
 *       array.</li>
 *   <li><b>ASM method-size limit</b> — DFAs too large for INLINED bytecode
 *       (estimate &gt; 30 KB) require DELEGATE dispatch (forward to embedded
 *       {@code TdfaRunner}). Without it, ASM throws
 *       {@code MethodTooLargeException}.</li>
 * </ul>
 */
class UnicodePropertyClassTest {

    private static Stream<EngineFactory> factories() {
        return Stream.of(EngineFactory.ASM, EngineFactory.VM);
    }

    // ===== stateBase overflow =====

    @ParameterizedTest @MethodSource("factories")
    void letterClassRepeat25Compiles(EngineFactory f) {
        assertThatCode(() -> Regex.compile("\\p{L}{25}", f, Disambiguation.PERL))
                .as("\\p{L}{25} should compile without stateBase overflow")
                .doesNotThrowAnyException();
    }

    @ParameterizedTest @MethodSource("factories")
    void letterClassRepeat25Matches(EngineFactory f) {
        Regex r = Regex.compile("\\p{L}{25}", f, Disambiguation.PERL);
        MatchResult m = r.find("ABCDEFGHIJKLMNOPQRSTUVWXYZ", 0);
        assertThat(m).isNotNull();
        assertThat(m.start(0)).isEqualTo(0);
        assertThat(m.end(0)).isEqualTo(25);
    }

    @ParameterizedTest @MethodSource("factories")
    void letterClassRepeat25NoMatch(EngineFactory f) {
        Regex r = Regex.compile("\\p{L}{25}", f, Disambiguation.PERL);
        assertThat(r.find("1234567890123456789012345", 0)).isNull();
    }

    @ParameterizedTest @MethodSource("factories")
    void letterClassRepeat50(EngineFactory f) {
        Regex r = Regex.compile("\\p{L}{50}", f, Disambiguation.PERL);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 50; i++) sb.append('a');
        MatchResult m = r.find(sb.toString(), 0);
        assertThat(m).isNotNull();
        assertThat(m.end(0)).isEqualTo(50);
    }

    // ===== ASM DELEGATE mode =====

    @Test
    void asmDelegateModeCompilesWideDfa() {
        assertThatCode(() -> Regex.compile("\\p{L}+", EngineFactory.ASM, Disambiguation.PERL))
                .as("\\p{L}+ should compile on ASM (DELEGATE dispatch)")
                .doesNotThrowAnyException();
    }

    @Test
    void asmDelegateModeMatchesCorrectly() {
        Regex r = Regex.compile("\\p{L}+", EngineFactory.ASM, Disambiguation.PERL);
        MatchResult m = r.find("hello world", 0);
        assertThat(m).isNotNull();
        assertThat(m.start(0)).isEqualTo(0);
        assertThat(m.end(0)).isEqualTo(5);
    }

    @Test
    void asmLargeAlternationCompilesAndMatches() {
        String[] branches = {
                "(cat)", "(dog)", "(bird)", "(fish)", "(frog)",
                "(bear)", "(wolf)", "(deer)", "(lion)", "(tiger)",
                "(eagle)", "(shark)", "(whale)", "(snake)", "(turtle)",
                "(duck)", "(goose)", "(horse)", "(mouse)", "(rabbit)"
        };
        String regex = String.join("|", branches);
        Regex r = Regex.compile(regex, EngineFactory.ASM, Disambiguation.PERL);
        MatchResult m = r.find("have a tiger here", 0);
        assertThat(m).isNotNull();
        assertThat(m.groupCount()).isEqualTo(20);
        assertThat(m.start(0)).isEqualTo(7);
        assertThat(m.end(0)).isEqualTo(12);
        assertThat(m.start(10)).isEqualTo(7);
        for (int g = 1; g <= 20; g++) {
            if (g == 10) continue;
            assertThat(m.start(g)).isEqualTo(-1);
        }
    }
}
