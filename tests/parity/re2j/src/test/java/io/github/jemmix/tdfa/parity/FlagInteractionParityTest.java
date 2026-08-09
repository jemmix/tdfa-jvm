package io.github.jemmix.tdfa.parity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import io.github.jemmix.tdfa.EngineFactory;

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
    void disableUnicodeGroupsBehavior(EngineFactory factory) {
        // Both re2j and our shim reject \p{L} when DISABLE_UNICODE_GROUPS is set.
        assertThatThrownBy(() -> com.google.re2j.Pattern.compile("\\p{L}", com.google.re2j.Pattern.DISABLE_UNICODE_GROUPS))
                .isInstanceOf(Exception.class);
        assertThatThrownBy(() -> io.github.jemmix.tdfa.re2j.Pattern.compile("\\p{L}",
                io.github.jemmix.tdfa.re2j.Pattern.DISABLE_UNICODE_GROUPS, factory))
                .isInstanceOf(io.github.jemmix.tdfa.re2j.PatternSyntaxException.class);
    }

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
