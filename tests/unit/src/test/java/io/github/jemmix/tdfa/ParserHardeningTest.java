package io.github.jemmix.tdfa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jemmix.tdfa.core.CompiledRegex;
import io.github.jemmix.tdfa.core.MatchResult;
import io.github.jemmix.tdfa.core.PatternSyntaxException;
import org.junit.jupiter.api.Test;

/**
 * Crash-hardening guarantees of the parser (2026-09 pre-freeze review):
 * hostile patterns must fail with a clean {@link PatternSyntaxException},
 * never a raw {@link StackOverflowError}, {@link NumberFormatException},
 * {@link OutOfMemoryError} from eager desugaring, or a silent misparse.
 * re2j-parity of the SEMANTICS is covered by ParserHardeningParityTest;
 * these pins are about failure-mode hygiene on this side.
 */
class ParserHardeningTest {

    @Test
    void deepNestingIsCleanParseErrorNotStackOverflow() {
        // unclosed groups: the iterative parser walks all 2000 '(' without
        // touching the JVM stack and reports the EOF like any syntax error
        assertThatThrownBy(() -> CompiledRegex.compile("(".repeat(2000) + "a"))
                .isInstanceOf(PatternSyntaxException.class)
                .hasMessageContaining("expected ')'");
    }

    @Test
    void deepNestingCompilesAndMatches() {
        // nesting is first-class: bounded only by the compile RAM budget
        // (frames + builder bytes), like re2j's iterative parser — no
        // dedicated depth cap exists
        int depth = 1000;
        String re = "(".repeat(depth) + "a" + ")".repeat(depth);
        CompiledRegex r = CompiledRegex.compile(re);
        assertThat(r.find("a")).isTrue();
        // outermost group is group 1, innermost is group depth — open-paren order
        for (MatchResult m : r.findAll("a")) {
            assertThat(m.groupCount()).isEqualTo(depth);
            assertThat(m.tag(2 * depth - 1)).isEqualTo(0); // innermost open
            assertThat(m.tag(2 * depth)).isEqualTo(1); // innermost close
        }
    }

    @Test
    void deepNestingOverRamBudgetIsCleanBudgetError() {
        // nesting frames are weighted against the one compile RAM budget:
        // over it, the standard "pattern too large" error — never OOM/SOE.
        // 30 KB / 64 B per frame = ~470 live frames, so 600-deep rejects.
        System.setProperty("tdfa.budget.compile.memory", "30720");
        try {
            String re = "(".repeat(600) + "a" + ")".repeat(600);
            assertThatThrownBy(() -> CompiledRegex.compile(re))
                    .isInstanceOf(PatternSyntaxException.class)
                    .hasMessageContaining("pattern too large")
                    .hasMessageContaining("tdfa.budget.compile.memory");
        } finally {
            System.clearProperty("tdfa.budget.compile.memory");
        }
    }

    @Test
    void nestedBoundedRepeatDesugarBlowupIsCleanBudgetError() {
        // {2,1000} desugars to ~1000-deep nested optional suffixes in the
        // TNFA builder, and nested groups MULTIPLY that depth — a
        // depth-driven blowup whose totals stay small. Live frames and
        // metered builder states both weigh against the one compile RAM
        // budget, so it rejects cleanly. The tiny pattern compiles fine
        // under defaults.
        assertThatCode(() -> CompiledRegex.compile("(?:(?:a{2,1000}))")).doesNotThrowAnyException();
        System.setProperty("tdfa.budget.compile.memory", "100000");
        try {
            assertThatThrownBy(() -> CompiledRegex.compile("(?:(?:a{2,1000}))"))
                    .isInstanceOf(PatternSyntaxException.class)
                    .hasMessageContaining("pattern too large");
        } finally {
            System.clearProperty("tdfa.budget.compile.memory");
        }
    }

    @Test
    void deepEscapeFreeNestingStillCleanError() {
        // the per-group frame is pushed regardless of body shape
        assertThatThrownBy(() -> CompiledRegex.compile("(?:".repeat(2000) + "a"))
                .isInstanceOf(PatternSyntaxException.class);
    }

    @Test
    void repeatOverflowIsCleanParseError() {
        assertThatThrownBy(() -> CompiledRegex.compile("a{2147483648}"))
                .isInstanceOf(PatternSyntaxException.class)
                .hasMessageContaining("invalid repeat count");
    }

    @Test
    void repeatHugeCountRejectedNotOom() {
        // previously: eager {n} desugaring OOM'd the TNFA builder before any
        // determinization budget could fire
        assertThatThrownBy(() -> CompiledRegex.compile("a{500000000}"))
                .isInstanceOf(PatternSyntaxException.class)
                .hasMessageContaining("invalid repeat count");
    }

    @Test
    void repeatCapBoundary() {
        assertThatCode(() -> CompiledRegex.compile("a{1000}")).doesNotThrowAnyException();
        assertThatThrownBy(() -> CompiledRegex.compile("a{1001}"))
                .isInstanceOf(PatternSyntaxException.class)
                .hasMessageContaining("invalid repeat count");
        assertThatThrownBy(() -> CompiledRegex.compile("a{0,1001}"))
                .isInstanceOf(PatternSyntaxException.class)
                .hasMessageContaining("invalid repeat count");
    }

    @Test
    void hexOverflowIsCleanParseError() {
        assertThatThrownBy(() -> CompiledRegex.compile("\\x{1100000}")).isInstanceOf(PatternSyntaxException.class);
        assertThatThrownBy(() -> CompiledRegex.compile("\\x{110000}")).isInstanceOf(PatternSyntaxException.class);
        assertThatCode(() -> CompiledRegex.compile("\\x{10FFFF}")).doesNotThrowAnyException();
    }

    @Test
    void scopedFlagsDoNotLeak() {
        // re2j semantics: every ')' restores flags saved at its '('
        CompiledRegex r = CompiledRegex.compile("((?i)a)b");
        assertThat(r.find("Ab")).isTrue();
        assertThat(r.find("AB")).isFalse();
        assertThat(r.find("aB")).isFalse();
    }

    @Test
    void flagOnlyGroupPersistsOutside() {
        CompiledRegex r = CompiledRegex.compile("(?i)(a)b");
        assertThat(r.find("AB")).isTrue();
    }

    @Test
    void quantifierWithoutAtomIsErrorNotLiteral() {
        assertThatThrownBy(() -> CompiledRegex.compile("*a"))
                .isInstanceOf(PatternSyntaxException.class)
                .hasMessageContaining("missing argument");
        assertThatThrownBy(() -> CompiledRegex.compile("a*{2}"))
                .isInstanceOf(PatternSyntaxException.class)
                .hasMessageContaining("invalid nested repetition");
        // previously a*{2} parsed as a* + literal "{2}" and MATCHED "aa{2}"
        assertThat(CompiledRegex.compile("a*").find("aa{2}")).isTrue();
    }

    @Test
    void groupNameValidated() {
        assertThatThrownBy(() -> CompiledRegex.compile("(?<a b>x)"))
                .isInstanceOf(PatternSyntaxException.class)
                .hasMessageContaining("invalid named capture");
        assertThatCode(() -> CompiledRegex.compile("(?<a_1>x)y")).doesNotThrowAnyException();
    }
}
