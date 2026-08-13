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
 * Compilation robustness: tests for fixes that prevented compile-time crashes
 * on large/complex patterns surfaced by the rebar parity suite.
 *
 * <p>1. <b>StateBase overflow</b> — the {@code stateMeta} packing used only
 *    15 bits for the range-base field (max 32767). {@code \p{L}} has ~1369
 *    ranges per state; at state 24 the base overflowed (24×1369×2 > 32767).
 *    Fixed by splitting into a separate {@code stateBase[]} array (full 32-bit).
 *
 * <p>2. <b>ASM DELEGATE mode</b> — DFAs too large for INLINED bytecode
 *    (estimate &gt; 30 KB) previously threw {@code MethodTooLargeException}.
 *    Fixed by {@code pickMode} selecting DELEGATE (forward to embedded
 *    {@code TdfaRunner}), keeping the ASM class emittable at any DFA size.
 *
 * <p>Each test runs on both ASM and VM backends.
 */
class CompilationRobustnessTest {

    private static Stream<EngineFactory> factories() {
        return Stream.of(EngineFactory.ASM, EngineFactory.VM);
    }

    // ----------------- StateBase overflow -----------------

    /**
     * {@code \p{L}{25}} generates ~25 DFA states, each with ~1369 Unicode
     * letter ranges. The range-base for state 25 exceeds 15 bits (the old
     * packing limit). Without the fix, this throws
     * {@code ArrayIndexOutOfBoundsException} or produces corrupt transitions.
     */
    @ParameterizedTest
    @MethodSource("factories")
    void unicodeLetterClassRepeatCompiles(EngineFactory factory) {
        String regex = "\\p{L}{25}";
        assertThatCode(() -> Regex.compile(regex, factory, Disambiguation.PERL))
                .as("\\p{L}{25} should compile without overflow on %s", factory)
                .doesNotThrowAnyException();
    }

    /** Same pattern actually matches a 25-letter string correctly. */
    @ParameterizedTest
    @MethodSource("factories")
    void unicodeLetterClassRepeatMatches(EngineFactory factory) {
        Regex r = Regex.compile("\\p{L}{25}", factory, Disambiguation.PERL);
        String input = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"; // 26 letters
        MatchResult m = r.find(input, 0);
        assertThat(m).as("should match first 25 letters").isNotNull();
        assertThat(m.start(0)).isEqualTo(0);
        assertThat(m.end(0)).isEqualTo(25);
    }

    /** Higher count: {@code \p{L}{50}} — deeper into overflow territory. */
    @ParameterizedTest
    @MethodSource("factories")
    void unicodeLetterClassDeepRepeatCompiles(EngineFactory factory) {
        assertThatCode(() -> Regex.compile("\\p{L}{50}", factory, Disambiguation.PERL))
                .as("\\p{L}{50} should compile without overflow on %s", factory)
                .doesNotThrowAnyException();

        Regex r = Regex.compile("\\p{L}{50}", factory, Disambiguation.PERL);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 50; i++) sb.append('a');
        MatchResult m = r.find(sb.toString(), 0);
        assertThat(m).isNotNull();
        assertThat(m.end(0)).isEqualTo(50);
    }

    /**
     * Mismatch case: {@code \p{L}{25}} on non-letter input should not match.
     */
    @ParameterizedTest
    @MethodSource("factories")
    void unicodeLetterClassRepeatNoMatch(EngineFactory factory) {
        Regex r = Regex.compile("\\p{L}{25}", factory, Disambiguation.PERL);
        assertThat(r.find("1234567890123456789012345", 0)).isNull();
    }

    // ----------------- ASM DELEGATE mode -----------------

    /**
     * A pattern that generates a DFA too large for INLINED bytecode
     * (estimate > 30 KB) should still compile on ASM via DELEGATE mode.
     * {@code \p{L}+} has ~1369 ranges per state × 2 states × ~25 B/range
     * ≈ 68 KB of estimated bytecode, exceeding the 30 KB inline budget.
     */
    @Test
    void asmDelegateModeCompilesLargeDfa() {
        EngineFactory factory = EngineFactory.ASM;
        assertThatCode(() -> Regex.compile("\\p{L}+", factory, Disambiguation.PERL))
                .as("\\p{L}+ should compile on ASM via DELEGATE mode")
                .doesNotThrowAnyException();
    }

    /** The DELEGATE-mode compiled pattern matches correctly. */
    @Test
    void asmDelegateModeMatchesCorrectly() {
        Regex r = Regex.compile("\\p{L}+", EngineFactory.ASM, Disambiguation.PERL);
        MatchResult m = r.find("hello world", 0);
        assertThat(m).isNotNull();
        assertThat(m.start(0)).isEqualTo(0);
        assertThat(m.end(0)).isEqualTo(5);
    }

    /**
     * Large alternation (many branches) should compile on ASM regardless of
     * dispatch mode. This exercises the full pickMode → emit pipeline.
     */
    @Test
    void asmLargeAlternationCompilesAndMatches() {
        // 20-branch alternation — enough to generate a non-trivial DFA
        String[] branches = {
                "(cat)", "(dog)", "(bird)", "(fish)", "(frog)",
                "(bear)", "(wolf)", "(deer)", "(lion)", "(tiger)",
                "(eagle)", "(shark)", "(whale)", "(snake)", "(turtle)",
                "(duck)", "(goose)", "(horse)", "(mouse)", "(rabbit)"
        };
        String regex = String.join("|", branches);
        Regex r = Regex.compile(regex, EngineFactory.ASM, Disambiguation.PERL);
        MatchResult m = r.find("have a tiger here", 0);
        assertThat(m).as("should find 'tiger'").isNotNull();
        assertThat(m.groupCount()).isEqualTo(20);
        assertThat(m.start(0)).isEqualTo(7);
        assertThat(m.end(0)).isEqualTo(12);
        // Group 10 (tiger) should participate, all others should not.
        assertThat(m.start(10)).isEqualTo(7);
        assertThat(m.end(10)).isEqualTo(12);
        for (int g = 1; g <= 20; g++) {
            if (g == 10) continue;
            assertThat(m.start(g))
                    .as("non-participating group %d should be -1", g)
                    .isEqualTo(-1);
        }
    }
}
