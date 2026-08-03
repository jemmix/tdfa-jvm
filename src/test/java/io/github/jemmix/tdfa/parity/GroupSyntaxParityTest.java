package io.github.jemmix.tdfa.parity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;
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

    // ---- Named groups ----

    @Test void namedGroupPStyle() { assertSameFind("(?P<word>\\w+)", "hello"); }
    @Test void namedGroupAngleStyle() { assertSameFind("(?<word>\\w+)", "hello"); }
    @Test void namedGroupWithOtherGroups() {
        int[] r = re2jFind("(a)(?P<x>b)(c)", "abc");
        int[] t = tdfaFind("(a)(?P<x>b)(c)", "abc");
        assertThat(t).isEqualTo(r);
    }
    @Test void namedGroupDuplicateRejects() { assertSameCompileReject("(?P<x>a)(?P<x>b)"); }

    @Test void namedGroupQuery() {
        var r = com.google.re2j.Pattern.compile("(?<word>\\w+)").matcher("hello");
        var t = io.github.jemmix.tdfa.re2j.Pattern.compile("(?<word>\\w+)").matcher("hello");
        r.find(); t.find();
        assertThat(t.group("word")).isEqualTo(r.group("word"));
        assertThat(t.start("word")).isEqualTo(r.start("word"));
        assertThat(t.end("word")).isEqualTo(r.end("word"));
    }

    @Test void namedGroupMixedWithNumbered() {
        var r = com.google.re2j.Pattern.compile("(a)(?P<x>b)(c)").matcher("abc");
        var t = io.github.jemmix.tdfa.re2j.Pattern.compile("(a)(?P<x>b)(c)").matcher("abc");
        r.find(); t.find();
        assertThat(t.group("x")).isEqualTo(r.group("x"));
        assertThat(t.group(1)).isEqualTo(r.group(1));
        assertThat(t.group(3)).isEqualTo(r.group(3));
    }

    @Test void namedGroupsMap() {
        var rp = com.google.re2j.Pattern.compile("(?<a>x)(?<b>y)");
        var tp = io.github.jemmix.tdfa.re2j.Pattern.compile("(?<a>x)(?<b>y)");
        assertThat(tp.namedGroups()).isEqualTo(rp.namedGroups());
    }

    @Test void namedGroupUnderStar() {
        int[] r = re2jFind("(?P<g>a|b)*c", "ababc");
        int[] t = tdfaFind("(?P<g>a|b)*c", "ababc");
        assertThat(t).isEqualTo(r);
    }

    @Test void namedGroupNested() {
        int[] r = re2jFind("(a(?P<inner>b)c)", "abc");
        int[] t = tdfaFind("(a(?P<inner>b)c)", "abc");
        assertThat(t).isEqualTo(r);
    }

    @Test void namedGroupNonParticipating() {
        assertSameCompileReject("(?<x>a)|(?<x>b)");
    }

    // ---- DFA-incompatible group syntax (both re2j and TDFA reject) ----

    @Test void lookaheadRejects() { assertSameCompileReject("(?=abc)abc"); }
    @Test void negativeLookaheadRejects() { assertSameCompileReject("a(?!b)c"); }
    @Test void lookbehindRejects() { assertSameCompileReject("(?<=a)b"); }
    @Test void negativeLookbehindRejects() { assertSameCompileReject("(?<!a)b"); }
    @Test void atomicGroupRejects() { assertSameCompileReject("(?>a+)"); }
    @Test void possessiveStarRejects() { assertSameCompileReject("a*+"); }
    @Test void backreferenceRejects() { assertSameCompileReject("(a)\\1"); }
}
