package io.github.jemmix.tdfa.parity;

import io.github.jemmix.tdfa.core.compile.RegexEngineFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static io.github.jemmix.tdfa.parity.Re2jOracle.assertSameAllMatches;
import static io.github.jemmix.tdfa.parity.Re2jOracle.assertSameFind;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Dotall parity: (?s) flag, DOTALL mode flag, dot matching newline.
 *
 * <p>Jur twin below — every case translates (incl. the DOTALL flag bit via
 * {@link JurOracle#jdkFlags(int)}).
 */
class DotAllParityTest {

    /** Every route to a parser group frame, each with the dot after its ')'. */
    private static final String[] GROUP_THEN_DOT =
        {"(a).", "(?:a).", "(?<g>a).", "(?:(?:a)).", "(?:).", "(?:a|).", "(?s:a).", "(?-s:a).", "(a)?.", "(?:a)*."};

    /** No frame between the flag and the dot — the controls. */
    private static final String[] NO_FRAME = {"a.", "[a].", "\\Qa\\E."};

    private static final String[] NEWLINE_INPUTS = {"a\n", "\na", "a\nb", "\n", "ab", "\n\n"};

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void dotNoNewline(RegexEngineFactory factory) {
        assertSameFind("a.c", "abc", factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void dotNoMatchNewline(RegexEngineFactory factory) {
        assertSameFind("a.c", "a\nc", factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void dotAllInline(RegexEngineFactory factory) {
        assertSameFind("(?s)a.c", "a\nc", factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void dotAllInlineMultiple(RegexEngineFactory factory) {
        assertSameFind("(?s).+", "a\nb\nc", factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void dotAllFlag(RegexEngineFactory factory) {
        com.google.re2j.Matcher rm =
            com.google.re2j.Pattern.compile("(?s).", com.google.re2j.Pattern.DOTALL).matcher("\n");
        io.github.jemmix.tdfa.core.engine.Matcher tm =
            io.github.jemmix.tdfa.Pattern.compile("(?s).", io.github.jemmix.tdfa.Pattern.DOTALL, factory).matcher("\n");
        assertThat(tm.find()).isEqualTo(rm.find());
        assertThat(tm.group()).isEqualTo(rm.group());
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void dotAllScoped(RegexEngineFactory factory) {
        assertSameFind("a(?s:.)c", "a\nc", factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void dotAllNoLeak(RegexEngineFactory factory) {
        assertSameFind("(?s)a.c|def", "a\nc", factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void toggleDotAllOff(RegexEngineFactory factory) {
        assertSameFind("(?s)a(?-s).c", "a\nc", factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void findAllDotAll(RegexEngineFactory factory) {
        assertSameAllMatches("(?s).", "ab\ncd", factory);
    }

    // ---- Edge cases ----

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void dotAllWithCarriageReturn(RegexEngineFactory factory) {
        assertSameFind("(?s)a.c", "a\rc", factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void dotAllMultiline(RegexEngineFactory factory) {
        assertSameFind("(?ms)^.$", "a\nb", factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void dotAllScopedMultiline(RegexEngineFactory factory) {
        assertSameFind("(?m)^(?s:.)$", "a\nb", factory);
    }

    /** Jur twin: the whole suite against the live java.util.regex oracle. */
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.JurOracle#engineFactories")
    void jurTwinAllCases(RegexEngineFactory factory) {
        JurOracle.assertSameFind("a.c", "abc", factory);
        JurOracle.assertSameFind("a.c", "a\nc", factory);
        JurOracle.assertSameFind("(?s)a.c", "a\nc", factory);
        JurOracle.assertSameFind("(?s).+", "a\nb\nc", factory);
        JurOracle.assertSameFind("(?s).", "\n", io.github.jemmix.tdfa.Pattern.DOTALL, factory);
        JurOracle.assertSameFind("a(?s:.)c", "a\nc", factory);
        JurOracle.assertSameFind("(?s)a.c|def", "a\nc", factory);
        JurOracle.assertSameFind("(?s)a(?-s).c", "a\nc", factory);
        JurOracle.assertSameAllMatches("(?s).", "ab\ncd", factory);
        JurOracle.assertSameFind("(?s)a.c", "a\rc", factory);
        JurOracle.assertSameFind("(?ms)^.$", "a\nb", factory);
        JurOracle.assertSameFind("(?m)^(?s:.)$", "a\nb", factory);
    }

    /** Every match as {@code start..end} spans — the oracle's iteration. */
    private static String re2jSpans(String p, int flags, String in) {
        StringBuilder sb = new StringBuilder();
        com.google.re2j.Matcher m = com.google.re2j.Pattern.compile(p, flags).matcher(in);
        while (m.find()) {
            sb.append(m.start()).append("..").append(m.end()).append(' ');
        }
        return sb.toString();
    }

    /** Every match as {@code start..end} spans — the re2j surface's iteration. */
    private static String surfaceSpans(String p, int flags, String in, RegexEngineFactory factory) {
        StringBuilder sb = new StringBuilder();
        io.github.jemmix.tdfa.core.engine.Matcher m =
            io.github.jemmix.tdfa.re2j.Pattern.compile(p, flags, factory).matcher(in);
        while (m.find()) {
            sb.append(m.start()).append("..").append(m.end()).append(' ');
        }
        return sb.toString();
    }

    /**
     * The dot after a closed group reads the enclosing scope's DOTALL: the
     * parser's group frame saves every flag on entry and restores it on
     * ')'. A regression once dropped the frame's dotall save, so every
     * group close reset DOTALL to false and the dot stopped crossing
     * newlines for the rest of the pattern — both facades, both tiers
     * ({@code (a).} on {@code "a\n"}). Both routes to the outer flag are
     * walked: the compile bit and an inline {@code (?s)}.
     */
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void dotAllSurvivesGroupClose(RegexEngineFactory factory) {
        for (String[] shapes : new String[][]{GROUP_THEN_DOT, NO_FRAME}) {
            for (String p : shapes) {
                for (String in : NEWLINE_INPUTS) {
                    assertThat(surfaceSpans(p, io.github.jemmix.tdfa.re2j.Pattern.DOTALL, in, factory))
                        .as("flag route: \"%s\" on \"%s\"", p, in)
                        .isEqualTo(re2jSpans(p, com.google.re2j.Pattern.DOTALL, in));
                    assertThat(surfaceSpans("(?s)" + p, 0, in, factory)).as("inline route: \"(?s)%s\" on \"%s\"", p, in)
                        .isEqualTo(re2jSpans("(?s)" + p, 0, in));
                }
            }
        }
        // not vacuous: the dot crosses the newline right after the group
        assertThat(surfaceSpans("(a).", io.github.jemmix.tdfa.re2j.Pattern.DOTALL, "a\n", factory)).isEqualTo("0..2 ");
    }

    /** Jur twin of {@link #dotAllSurvivesGroupClose}: the live JDK's answers. */
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.JurOracle#engineFactories")
    void jurTwinDotAllSurvivesGroupClose(RegexEngineFactory factory) {
        for (String[] shapes : new String[][]{GROUP_THEN_DOT, NO_FRAME}) {
            for (String p : shapes) {
                for (String in : NEWLINE_INPUTS) {
                    JurOracle.assertSameAllMatches(p, in, io.github.jemmix.tdfa.Pattern.DOTALL, factory);
                    JurOracle.assertSameAllMatches("(?s)" + p, in, 0, factory);
                }
            }
        }
    }
}
