package io.github.jemmix.tdfa.parity;

import io.github.jemmix.tdfa.core.compile.PatternSurface;
import io.github.jemmix.tdfa.core.compile.RegexEngineFactory;
import io.github.jemmix.tdfa.core.parser.PatternSyntaxException;
import io.github.jemmix.tdfa.core.tnfa.Semantics;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static io.github.jemmix.tdfa.parity.Re2jOracle.assertSameAllMatches;
import static io.github.jemmix.tdfa.parity.Re2jOracle.assertSameFind;
import static io.github.jemmix.tdfa.parity.Re2jOracle.assertSameFindPosix;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Flag interaction parity: combined flags, unknown flag rejection,
 * DISABLE_UNICODE_GROUPS.
 *
 * <p>Jur twins below — the combined-flag cells run through
 * {@link JurOracle#jdkFlags(int)}; the machinery axes
 * ({@code UNIX_LINES}/{@code UNICODE_CASE}, no facade bits) ride the
 * {@code CompileOptions.semantics} route. RE2-lane-pinned (no jur
 * reading, per the WBS item-7 map): {@code LONGEST_MATCH} (POSIX
 * leftmost-longest — the JDK has no such flag) and
 * {@code DISABLE_UNICODE_GROUPS} (re2j dial; the JDK has none — the
 * facade-only rejection stays pinned above), plus {@code FIND_ONLY} and
 * {@code MULTI_VALUED_TAGS} (engine capabilities, not readings).
 */
class FlagInteractionParityTest {

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void caseInsensitiveAndDotAll(RegexEngineFactory factory) {
        assertSameFind("(?is)A.", "a\n", factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void caseInsensitiveAndMultiline(RegexEngineFactory factory) {
        assertSameAllMatches("(?im)^a", "Ab\ncD\nae", factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void dotAllAndMultiline(RegexEngineFactory factory) {
        assertSameAllMatches("(?sm)^.$", "a\nb", factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void allThreeFlagsCombined(RegexEngineFactory factory) {
        assertSameFind("(?ims)A.", "a\n", factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void longestMatchCombinedWithCaseInsensitive(RegexEngineFactory factory) {
        assertSameFindPosix("(?i)(a|ab)", "AB", factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void unknownFlagRejects(RegexEngineFactory factory) {
        // 0x10000: unknown-flag parity needs a bit neither engine defines
        // (re2j tops out below 64K; the re2j surface's roster is the
        // re2j dial set).
        assertThatThrownBy(() -> io.github.jemmix.tdfa.Pattern.compile("abc", 0x10000, factory))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> com.google.re2j.Pattern.compile("abc", 0x10000))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void disableUnicodeGroupsBehavior(RegexEngineFactory factory) {
        // Both re2j and our shim reject \p{L} when DISABLE_UNICODE_GROUPS is set.
        assertThatThrownBy(
            () -> com.google.re2j.Pattern.compile("\\p{L}", com.google.re2j.Pattern.DISABLE_UNICODE_GROUPS))
            .isInstanceOf(Exception.class);
        assertThatThrownBy(() -> io.github.jemmix.tdfa.Pattern.compile("\\p{L}",
            io.github.jemmix.tdfa.Pattern.DISABLE_UNICODE_GROUPS, factory)).isInstanceOf(PatternSyntaxException.class);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void disableUnicodeGroupsRejectsProperty(RegexEngineFactory factory) {
        assertThatThrownBy(() -> io.github.jemmix.tdfa.Pattern.compile("\\p{L}",
            io.github.jemmix.tdfa.Pattern.DISABLE_UNICODE_GROUPS, factory)).isInstanceOf(PatternSyntaxException.class);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void flagRoundTrip(RegexEngineFactory factory) {
        int flags = io.github.jemmix.tdfa.Pattern.CASE_INSENSITIVE | io.github.jemmix.tdfa.Pattern.MULTILINE
            | io.github.jemmix.tdfa.Pattern.DOTALL;
        var p = io.github.jemmix.tdfa.Pattern.compile("abc", flags, factory);
        assertThat(p.flags()).isEqualTo(flags);
    }

    /**
     * Jur twin: combined flags through the bit&harr;JDK-flag translation,
     * unknown-flag rejection parity, and the machinery axes' semantics
     * route ({@code UNIX_LINES}/{@code UNICODE_CASE} map to the JDK flags
     * of the same names; ASM-only route, see {@link JurOracle}).
     */
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.JurOracle#engineFactories")
    void jurTwin(RegexEngineFactory factory) {
        JurOracle.assertSameFind("(?is)A.", "a\n", factory);
        JurOracle.assertSameAllMatches("(?im)^a", "Ab\ncD\nae", factory);
        JurOracle.assertSameAllMatches("(?sm)^.$", "a\nb", factory);
        JurOracle.assertSameFind("(?ims)A.", "a\n", factory);
        // the flag bits themselves, through the translation
        JurOracle.assertSameFind("A.", "a\n",
            io.github.jemmix.tdfa.Pattern.CASE_INSENSITIVE | io.github.jemmix.tdfa.Pattern.DOTALL, factory);
        JurOracle.assertSameAllMatches("^a", "Ab\ncD\nae", io.github.jemmix.tdfa.Pattern.MULTILINE, factory);
        JurOracle.assertSameFind(".", "\u00E9", io.github.jemmix.tdfa.Pattern.UNICODE_CHARACTER_CLASS, factory);
        // unknown flag bits: both oracles reject
        assertThatThrownBy(() -> java.util.regex.Pattern.compile("abc", 0x10000))
            .isInstanceOf(IllegalArgumentException.class);
        // the semantics route (no facade bits for these axes)
        JurOracle.assertSemanticsSameFind("(?m)^a$", "a\rb", Semantics.of().unixLines(),
            JurOracle.jdkFlags(PatternSurface.UNIX_LINES)); // \r not a line edge
        JurOracle.assertSameFind("(?m)^a$", "a\rb", factory); // default lane: \r IS a terminator
        JurOracle.assertSemanticsSameFind("(?i)k", "\u212a", Semantics.of().unicodeCase(),
            JurOracle.jdkFlags(PatternSurface.UNICODE_CASE)); // kelvin folds
        JurOracle.assertSameFind("(?i)k", "\u212a", factory); // default lane: ASCII fold only
    }
}
