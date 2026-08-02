package io.github.jemmix.tdfa.parity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Disabled;

import static org.assertj.core.api.Assertions.assertThat;
import static io.github.jemmix.tdfa.parity.Re2jOracle.*;

/**
 * Group syntax parity: non-capturing, named groups, atomic groups,
 * lookarounds, possessive quantifiers, nested captures.
 *
 * Some constructs are not in re2j either (lookarounds, backreferences,
 * possessive) — those are marked RE2J_MISSING.
 * Named groups are in re2j but not in our impl — marked TDFA_MISSING.
 */
class GroupSyntaxParityTest {

    @Test void nonCapturingGroup() { assertSameFind("(?:abc)", "abc"); }
    @Test void nonCapturingWithQuantifier() { assertSameFind("(?:ab)+", "ababab"); }
    @Test void capturingGroup() { assertSameFind("(abc)", "abc"); }
    @Test void multipleGroups() {
        int[] r = re2jFind("(a)(b)(c)", "abc");
        int[] t = tdfaFind("(a)(b)(c)", "abc");
        assertThat(t).isEqualTo(r);
    }
    @Test void nestedGroups() {
        int[] r = re2jFind("(a(b)c)", "abc");
        int[] t = tdfaFind("(a(b)c)", "abc");
        assertThat(t).isEqualTo(r);
    }
    @Test void groupUnderStar() {
        int[] r = re2jFind("(a|b)*c", "ababc");
        int[] t = tdfaFind("(a|b)*c", "ababc");
        assertThat(t).isEqualTo(r);
    }
    @Test void repeatedGroupCapture() {
        int[] r = re2jFind("(\\w)(\\w)", "ab");
        int[] t = tdfaFind("(\\w)(\\w)", "ab");
        assertThat(t).isEqualTo(r);
    }
    @Test void groupWithAlternation() {
        int[] r = re2jFind("(cat|dog|bird)", "dog");
        int[] t = tdfaFind("(cat|dog|bird)", "dog");
        assertThat(t).isEqualTo(r);
    }
    @Test void nestedCaptureUnderRepetition() {
        int[] r = re2jFind("((a)(b))*", "abab");
        int[] t = tdfaFind("((a)(b))*", "abab");
        assertThat(t).isEqualTo(r);
    }
    @Test void deeplyNestedGroups() {
        int[] r = re2jFind("(a(b(c)d)e)", "abcde");
        int[] t = tdfaFind("(a(b(c)d)e)", "abcde");
        assertThat(t).isEqualTo(r);
    }
    @Test void alternationWithGroups() {
        int[] r = re2jFind("(a)|(b)", "b");
        int[] t = tdfaFind("(a)|(b)", "b");
        assertThat(t).isEqualTo(r);
    }
    @Test void nonParticipatingGroup() {
        int[] r = re2jFind("(a)|(b)", "b");
        int[] t = tdfaFind("(a)|(b)", "b");
        assertThat(t).isEqualTo(r);
    }

    // ---- Named groups (re2j supports, we don't) ----

    @Test
    @Disabled("TDFA_MISSING: named groups (?P<name>...) not supported")
    void namedGroupPStyle() { assertSameFind("(?P<word>\\w+)", "hello"); }

    @Test
    @Disabled("TDFA_MISSING: named groups (?<name>...) not supported")
    void namedGroupAngleStyle() { assertSameFind("(?<word>\\w+)", "hello"); }

    // ---- DFA-incompatible group syntax (both re2j and TDFA reject) ----

    @Test void lookaheadRejects() { assertSameCompileReject("(?=abc)abc"); }
    @Test void negativeLookaheadRejects() { assertSameCompileReject("a(?!b)c"); }
    @Test void lookbehindRejects() { assertSameCompileReject("(?<=a)b"); }
    @Test void negativeLookbehindRejects() { assertSameCompileReject("(?<!a)b"); }
    @Test void atomicGroupRejects() { assertSameCompileReject("(?>a+)"); }
    @Test void possessiveStarRejects() { assertSameCompileReject("a*+"); }
    @Test void backreferenceRejects() { assertSameCompileReject("(a)\\1"); }
}
