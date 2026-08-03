package io.github.jemmix.tdfa.parity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Disabled;

import static org.assertj.core.api.Assertions.assertThat;
import static io.github.jemmix.tdfa.parity.Re2jOracle.*;

/**
 * Dotall parity: (?s) flag, DOTALL mode flag, dot matching newline.
 */
class DotAllParityTest {

    @Test void dotNoNewline() { assertSameFind("a.c", "abc"); }
    @Test void dotNoMatchNewline() { assertSameFind("a.c", "a\nc"); }
    @Test void dotAllInline() { assertSameFind("(?s)a.c", "a\nc"); }
    @Test void dotAllInlineMultiple() { assertSameFind("(?s).+", "a\nb\nc"); }
    @Test void dotAllFlag() {
        com.google.re2j.Matcher rm = com.google.re2j.Pattern.compile(
                "(?s).", com.google.re2j.Pattern.DOTALL).matcher("\n");
        io.github.jemmix.tdfa.re2j.Matcher tm = io.github.jemmix.tdfa.re2j.Pattern.compile(
                "(?s).", io.github.jemmix.tdfa.re2j.Pattern.DOTALL).matcher("\n");
        assertThat(tm.find()).isEqualTo(rm.find());
        assertThat(tm.group()).isEqualTo(rm.group());
    }
    @Test void dotAllScoped() { assertSameFind("a(?s:.)c", "a\nc"); }
    @Test void dotAllNoLeak() { assertSameFind("(?s)a.c|def", "a\nc"); }
    @Test void toggleDotAllOff() { assertSameFind("(?s)a(?-s).c", "a\nc"); }

    @Test void findAllDotAll() { assertSameAllMatches("(?s).", "ab\ncd"); }

    // ---- Edge cases ----

    @Test void dotAllWithCarriageReturn() { assertSameFind("(?s)a.c", "a\rc"); }
    @Test void dotAllMultiline() { assertSameFind("(?ms)^.$", "a\nb"); }
    @Test void dotAllScopedMultiline() { assertSameFind("(?m)^(?s:.)$", "a\nb"); }
}
