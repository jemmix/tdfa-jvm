package io.github.jemmix.tdfa.parity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import io.github.jemmix.tdfa.core.Matcher;
import io.github.jemmix.tdfa.core.PatternSyntaxException;
import io.github.jemmix.tdfa.core.RegexEngineFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static io.github.jemmix.tdfa.parity.Re2jOracle.*;

/**
 * Dotall parity: (?s) flag, DOTALL mode flag, dot matching newline.
 */
class DotAllParityTest {

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void dotNoNewline(RegexEngineFactory factory) { assertSameFind("a.c", "abc", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void dotNoMatchNewline(RegexEngineFactory factory) { assertSameFind("a.c", "a\nc", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void dotAllInline(RegexEngineFactory factory) { assertSameFind("(?s)a.c", "a\nc", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void dotAllInlineMultiple(RegexEngineFactory factory) { assertSameFind("(?s).+", "a\nb\nc", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void dotAllFlag(RegexEngineFactory factory) {
        com.google.re2j.Matcher rm = com.google.re2j.Pattern.compile(
                "(?s).", com.google.re2j.Pattern.DOTALL).matcher("\n");
        io.github.jemmix.tdfa.core.Matcher tm = io.github.jemmix.tdfa.Pattern.compile(
                "(?s).", io.github.jemmix.tdfa.Pattern.DOTALL, factory).matcher("\n");
        assertThat(tm.find()).isEqualTo(rm.find());
        assertThat(tm.group()).isEqualTo(rm.group());
    }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void dotAllScoped(RegexEngineFactory factory) { assertSameFind("a(?s:.)c", "a\nc", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void dotAllNoLeak(RegexEngineFactory factory) { assertSameFind("(?s)a.c|def", "a\nc", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void toggleDotAllOff(RegexEngineFactory factory) { assertSameFind("(?s)a(?-s).c", "a\nc", factory); }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void findAllDotAll(RegexEngineFactory factory) { assertSameAllMatches("(?s).", "ab\ncd", factory); }

    // ---- Edge cases ----

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void dotAllWithCarriageReturn(RegexEngineFactory factory) { assertSameFind("(?s)a.c", "a\rc", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void dotAllMultiline(RegexEngineFactory factory) { assertSameFind("(?ms)^.$", "a\nb", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void dotAllScopedMultiline(RegexEngineFactory factory) { assertSameFind("(?m)^(?s:.)$", "a\nb", factory); }
}
