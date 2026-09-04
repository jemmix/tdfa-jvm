package io.github.jemmix.tdfa;

import io.github.jemmix.tdfa.core.CompiledRegex;
import io.github.jemmix.tdfa.core.PatternSyntaxException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Crash-hardening guarantees of the parser (2026-09 pre-freeze review):
 * hostile patterns must fail with a clean {@link PatternSyntaxException},
 * never a raw {@link StackOverflowError}, {@link NumberFormatException},
 * {@link OutOfMemoryError} from eager desugaring, or a silent misparse.
 * re2j-parity of the SEMANTICS is covered by ParserHardeningParityTest;
 * these pins are about failure-mode hygiene on this side.
 */
class ParserHardeningTest {

    @Test void deepNestingIsCleanParseErrorNotStackOverflow() {
        assertThatThrownBy(() -> CompiledRegex.compile("(".repeat(2000) + "a"))
                .isInstanceOf(PatternSyntaxException.class)
                .hasMessageContaining("group nesting too deep");
    }

    @Test void deepNestingAtLimitStillCompiles() {
        int depth = 1000;
        String re = "(".repeat(depth) + "a" + ")".repeat(depth);
        assertThatCode(() -> CompiledRegex.compile(re)).doesNotThrowAnyException();
    }

    @Test void deepEscapeFreeNestingAlsoBounded() {
        // the recursion is per-group regardless of body shape
        assertThatThrownBy(() -> CompiledRegex.compile("(?:".repeat(2000) + "a"))
                .isInstanceOf(PatternSyntaxException.class);
    }

    @Test void repeatOverflowIsCleanParseError() {
        assertThatThrownBy(() -> CompiledRegex.compile("a{2147483648}"))
                .isInstanceOf(PatternSyntaxException.class)
                .hasMessageContaining("invalid repeat count");
    }

    @Test void repeatHugeCountRejectedNotOom() {
        // previously: eager {n} desugaring OOM'd the TNFA builder before any
        // determinization budget could fire
        assertThatThrownBy(() -> CompiledRegex.compile("a{500000000}"))
                .isInstanceOf(PatternSyntaxException.class)
                .hasMessageContaining("invalid repeat count");
    }

    @Test void repeatCapBoundary() {
        assertThatCode(() -> CompiledRegex.compile("a{1000}")).doesNotThrowAnyException();
        assertThatThrownBy(() -> CompiledRegex.compile("a{1001}"))
                .isInstanceOf(PatternSyntaxException.class)
                .hasMessageContaining("invalid repeat count");
        assertThatThrownBy(() -> CompiledRegex.compile("a{0,1001}"))
                .isInstanceOf(PatternSyntaxException.class)
                .hasMessageContaining("invalid repeat count");
    }

    @Test void hexOverflowIsCleanParseError() {
        assertThatThrownBy(() -> CompiledRegex.compile("\\x{1100000}"))
                .isInstanceOf(PatternSyntaxException.class);
        assertThatThrownBy(() -> CompiledRegex.compile("\\x{110000}"))
                .isInstanceOf(PatternSyntaxException.class);
        assertThatCode(() -> CompiledRegex.compile("\\x{10FFFF}")).doesNotThrowAnyException();
    }

    @Test void scopedFlagsDoNotLeak() {
        // re2j semantics: every ')' restores flags saved at its '('
        CompiledRegex r = CompiledRegex.compile("((?i)a)b");
        assertThat(r.find("Ab")).isTrue();
        assertThat(r.find("AB")).isFalse();
        assertThat(r.find("aB")).isFalse();
    }

    @Test void flagOnlyGroupPersistsOutside() {
        CompiledRegex r = CompiledRegex.compile("(?i)(a)b");
        assertThat(r.find("AB")).isTrue();
    }

    @Test void quantifierWithoutAtomIsErrorNotLiteral() {
        assertThatThrownBy(() -> CompiledRegex.compile("*a"))
                .isInstanceOf(PatternSyntaxException.class)
                .hasMessageContaining("missing argument");
        assertThatThrownBy(() -> CompiledRegex.compile("a*{2}"))
                .isInstanceOf(PatternSyntaxException.class)
                .hasMessageContaining("invalid nested repetition");
        // previously a*{2} parsed as a* + literal "{2}" and MATCHED "aa{2}"
        assertThat(CompiledRegex.compile("a*").find("aa{2}")).isTrue();
    }

    @Test void groupNameValidated() {
        assertThatThrownBy(() -> CompiledRegex.compile("(?<a b>x)"))
                .isInstanceOf(PatternSyntaxException.class)
                .hasMessageContaining("invalid named capture");
        assertThatCode(() -> CompiledRegex.compile("(?<a_1>x)y")).doesNotThrowAnyException();
    }
}
