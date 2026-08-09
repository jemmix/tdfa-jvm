package io.github.jemmix.tdfa.parity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import io.github.jemmix.tdfa.EngineFactory;

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

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void nonCapturingGroup(EngineFactory factory) { assertSameFind("(?:abc)", "abc", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void nonCapturingWithQuantifier(EngineFactory factory) { assertSameFind("(?:ab)+", "ababab", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void capturingGroup(EngineFactory factory) { assertSameFind("(abc)", "abc", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void multipleGroups(EngineFactory factory) {
        int[] r = re2jFind("(a)(b)(c)", "abc");
        int[] t = tdfaFind("(a)(b)(c)", "abc", factory);
        assertThat(t).isEqualTo(r);
    }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void nestedGroups(EngineFactory factory) {
        int[] r = re2jFind("(a(b)c)", "abc");
        int[] t = tdfaFind("(a(b)c)", "abc", factory);
        assertThat(t).isEqualTo(r);
    }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void groupUnderStar(EngineFactory factory) {
        int[] r = re2jFind("(a|b)*c", "ababc");
        int[] t = tdfaFind("(a|b)*c", "ababc", factory);
        assertThat(t).isEqualTo(r);
    }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void repeatedGroupCapture(EngineFactory factory) {
        int[] r = re2jFind("(\\w)(\\w)", "ab");
        int[] t = tdfaFind("(\\w)(\\w)", "ab", factory);
        assertThat(t).isEqualTo(r);
    }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void groupWithAlternation(EngineFactory factory) {
        int[] r = re2jFind("(cat|dog|bird)", "dog");
        int[] t = tdfaFind("(cat|dog|bird)", "dog", factory);
        assertThat(t).isEqualTo(r);
    }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void nestedCaptureUnderRepetition(EngineFactory factory) {
        int[] r = re2jFind("((a)(b))*", "abab");
        int[] t = tdfaFind("((a)(b))*", "abab", factory);
        assertThat(t).isEqualTo(r);
    }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void deeplyNestedGroups(EngineFactory factory) {
        int[] r = re2jFind("(a(b(c)d)e)", "abcde");
        int[] t = tdfaFind("(a(b(c)d)e)", "abcde", factory);
        assertThat(t).isEqualTo(r);
    }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void alternationWithGroups(EngineFactory factory) {
        int[] r = re2jFind("(a)|(b)", "b");
        int[] t = tdfaFind("(a)|(b)", "b", factory);
        assertThat(t).isEqualTo(r);
    }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void nonParticipatingGroup(EngineFactory factory) {
        int[] r = re2jFind("(a)|(b)", "b");
        int[] t = tdfaFind("(a)|(b)", "b", factory);
        assertThat(t).isEqualTo(r);
    }

    // ---- Named groups ----

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void namedGroupPStyle(EngineFactory factory) { assertSameFind("(?P<word>\\w+)", "hello", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void namedGroupAngleStyle(EngineFactory factory) { assertSameFind("(?<word>\\w+)", "hello", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void namedGroupWithOtherGroups(EngineFactory factory) {
        int[] r = re2jFind("(a)(?P<x>b)(c)", "abc");
        int[] t = tdfaFind("(a)(?P<x>b)(c)", "abc", factory);
        assertThat(t).isEqualTo(r);
    }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void namedGroupDuplicateRejects(EngineFactory factory) { assertSameCompileReject("(?P<x>a)(?P<x>b)", factory); }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void namedGroupQuery(EngineFactory factory) {
        var r = com.google.re2j.Pattern.compile("(?<word>\\w+)").matcher("hello");
        var t = io.github.jemmix.tdfa.re2j.Pattern.compile("(?<word>\\w+)", 0, factory).matcher("hello");
        r.find(); t.find();
        assertThat(t.group("word")).isEqualTo(r.group("word"));
        assertThat(t.start("word")).isEqualTo(r.start("word"));
        assertThat(t.end("word")).isEqualTo(r.end("word"));
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void namedGroupMixedWithNumbered(EngineFactory factory) {
        var r = com.google.re2j.Pattern.compile("(a)(?P<x>b)(c)").matcher("abc");
        var t = io.github.jemmix.tdfa.re2j.Pattern.compile("(a)(?P<x>b)(c)", 0, factory).matcher("abc");
        r.find(); t.find();
        assertThat(t.group("x")).isEqualTo(r.group("x"));
        assertThat(t.group(1)).isEqualTo(r.group(1));
        assertThat(t.group(3)).isEqualTo(r.group(3));
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void namedGroupsMap(EngineFactory factory) {
        var rp = com.google.re2j.Pattern.compile("(?<a>x)(?<b>y)");
        var tp = io.github.jemmix.tdfa.re2j.Pattern.compile("(?<a>x)(?<b>y)", 0, factory);
        assertThat(tp.namedGroups()).isEqualTo(rp.namedGroups());
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void namedGroupUnderStar(EngineFactory factory) {
        int[] r = re2jFind("(?P<g>a|b)*c", "ababc");
        int[] t = tdfaFind("(?P<g>a|b)*c", "ababc", factory);
        assertThat(t).isEqualTo(r);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void namedGroupNested(EngineFactory factory) {
        int[] r = re2jFind("(a(?P<inner>b)c)", "abc");
        int[] t = tdfaFind("(a(?P<inner>b)c)", "abc", factory);
        assertThat(t).isEqualTo(r);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void namedGroupNonParticipating(EngineFactory factory) {
        assertSameCompileReject("(?<x>a)|(?<x>b)", factory);
    }

    // ---- DFA-incompatible group syntax (both re2j and TDFA reject) ----

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void lookaheadRejects(EngineFactory factory) { assertSameCompileReject("(?=abc)abc", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void negativeLookaheadRejects(EngineFactory factory) { assertSameCompileReject("a(?!b)c", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void lookbehindRejects(EngineFactory factory) { assertSameCompileReject("(?<=a)b", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void negativeLookbehindRejects(EngineFactory factory) { assertSameCompileReject("(?<!a)b", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void atomicGroupRejects(EngineFactory factory) { assertSameCompileReject("(?>a+)", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void possessiveStarRejects(EngineFactory factory) { assertSameCompileReject("a*+", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void backreferenceRejects(EngineFactory factory) { assertSameCompileReject("(a)\\1", factory); }
}
