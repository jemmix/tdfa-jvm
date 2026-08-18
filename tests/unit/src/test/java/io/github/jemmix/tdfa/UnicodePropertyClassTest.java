package io.github.jemmix.tdfa;

import io.github.jemmix.tdfa.core.RegexEngineFactory;
import io.github.jemmix.tdfa.tdfa.TdfaRunner;
import io.github.jemmix.tdfa.core.Matcher;
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

    private static Stream<RegexEngineFactory> factories() {
        return Stream.<RegexEngineFactory>of(null, TdfaRunner::new);
    }

    private static Matcher match(Pattern p, String input) {
        Matcher m = p.matcher(input);
        return m.find() ? m : null;
    }

    // ===== stateBase overflow =====

    @ParameterizedTest @MethodSource("factories")
    void letterClassRepeat25Compiles(RegexEngineFactory f) {
        assertThatCode(() -> Pattern.compile("\\p{L}{25}", 0, f))
                .as("\\p{L}{25} should compile without stateBase overflow")
                .doesNotThrowAnyException();
    }

    @ParameterizedTest @MethodSource("factories")
    void letterClassRepeat25Matches(RegexEngineFactory f) {
        Pattern r = Pattern.compile("\\p{L}{25}", 0, f);
        Matcher m = match(r, "ABCDEFGHIJKLMNOPQRSTUVWXYZ");
        assertThat(m).isNotNull();
        assertThat(m.start(0)).isEqualTo(0);
        assertThat(m.end(0)).isEqualTo(25);
    }

    @ParameterizedTest @MethodSource("factories")
    void letterClassRepeat25NoMatch(RegexEngineFactory f) {
        Pattern r = Pattern.compile("\\p{L}{25}", 0, f);
        assertThat(match(r, "1234567890123456789012345")).isNull();
    }

    @ParameterizedTest @MethodSource("factories")
    void letterClassRepeat50(RegexEngineFactory f) {
        Pattern r = Pattern.compile("\\p{L}{50}", 0, f);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 50; i++) sb.append('a');
        Matcher m = match(r, sb.toString());
        assertThat(m).isNotNull();
        assertThat(m.end(0)).isEqualTo(50);
    }

    // ===== ASM DELEGATE mode =====

    @Test
    void asmDelegateModeCompilesWideDfa() {
        assertThatCode(() -> Pattern.compile("\\p{L}+", 0, null))
                .as("\\p{L}+ should compile on ASM (DELEGATE dispatch)")
                .doesNotThrowAnyException();
    }

    @Test
    void asmDelegateModeMatchesCorrectly() {
        Pattern r = Pattern.compile("\\p{L}+", 0, null);
        Matcher m = match(r, "hello world");
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
        Pattern r = Pattern.compile(regex, 0, null);
        Matcher m = match(r, "have a tiger here");
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
