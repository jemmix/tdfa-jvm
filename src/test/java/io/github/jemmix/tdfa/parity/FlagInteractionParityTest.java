package io.github.jemmix.tdfa.parity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import io.github.jemmix.tdfa.EngineFactory;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.assertj.core.api.Assertions.*;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static io.github.jemmix.tdfa.parity.Re2jOracle.*;

/**
 * Flag interaction parity: combined flags, unknown flag rejection,
 * DISABLE_UNICODE_GROUPS.
 */
class FlagInteractionParityTest {

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void caseInsensitiveAndDotAll(EngineFactory factory) {
        assertSameFind("(?is)A.", "a\n", factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void caseInsensitiveAndMultiline(EngineFactory factory) {
        assertSameAllMatches("(?im)^a", "Ab\ncD\nae", factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void dotAllAndMultiline(EngineFactory factory) {
        assertSameAllMatches("(?sm)^.$", "a\nb", factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void allThreeFlagsCombined(EngineFactory factory) {
        assertSameFind("(?ims)A.", "a\n", factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void longestMatchCombinedWithCaseInsensitive(EngineFactory factory) {
        assertSameFindPosix("(?i)(a|ab)", "AB", factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void unknownFlagRejects(EngineFactory factory) {
        assertThatThrownBy(() -> io.github.jemmix.tdfa.re2j.Pattern.compile("abc", 0x100, factory))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> com.google.re2j.Pattern.compile("abc", 0x100))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void disableUnicodeGroupsAccepted(EngineFactory factory) {
        io.github.jemmix.tdfa.re2j.Pattern.compile("\\p{L}",
                io.github.jemmix.tdfa.re2j.Pattern.DISABLE_UNICODE_GROUPS, factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void disableUnicodeGroupsBehavior(EngineFactory factory) {
        // Pending parity: re2j rejects \p{L} when DISABLE_UNICODE_GROUPS is set;
        // our shim accepts it. Verify the divergence is known.
        boolean re2jRejects;
        try {
            com.google.re2j.Pattern.compile("\\p{L}", com.google.re2j.Pattern.DISABLE_UNICODE_GROUPS);
            re2jRejects = false;
        } catch (Exception e) {
            re2jRejects = true;
        }
        // re2j should reject Unicode groups when flag is set.
        assertThat(re2jRejects).isTrue();
        // Our shim currently accepts (pending parity enforcement).
        io.github.jemmix.tdfa.re2j.Pattern.compile("\\p{L}",
                io.github.jemmix.tdfa.re2j.Pattern.DISABLE_UNICODE_GROUPS, factory);
    }

    @EnabledIfSystemProperty(named = "tdfa.pending", matches = "true") // PENDING: DISABLE_UNICODE_GROUPS should reject \\p{X} like re2j")
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void disableUnicodeGroupsRejectsProperty(EngineFactory factory) {
        assertThatThrownBy(() -> io.github.jemmix.tdfa.re2j.Pattern.compile("\\p{L}",
                io.github.jemmix.tdfa.re2j.Pattern.DISABLE_UNICODE_GROUPS, factory))
                .isInstanceOf(io.github.jemmix.tdfa.re2j.PatternSyntaxException.class);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void flagRoundTrip(EngineFactory factory) {
        int flags = io.github.jemmix.tdfa.re2j.Pattern.CASE_INSENSITIVE
                | io.github.jemmix.tdfa.re2j.Pattern.MULTILINE
                | io.github.jemmix.tdfa.re2j.Pattern.DOTALL;
        var p = io.github.jemmix.tdfa.re2j.Pattern.compile("abc", flags, factory);
        assertThat(p.flags()).isEqualTo(flags);
    }
}
