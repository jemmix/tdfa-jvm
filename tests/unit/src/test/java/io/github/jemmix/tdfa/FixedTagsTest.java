package io.github.jemmix.tdfa;

import io.github.jemmix.tdfa.core.CompileOptions;
import io.github.jemmix.tdfa.core.CompiledRegex;
import io.github.jemmix.tdfa.core.MatchResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BT22 §6.4 fixed-tags optimization: tags within a fixed character-distance are
 * dropped from the NFA and reconstructed at match time. These tests verify
 * (a) capture results are unchanged with the optimization on, and
 * (b) patterns known to benefit from the optimization actually trigger it.
 */
class FixedTagsTest {

    private static int[] groups(CompiledRegex r, String input) {
        MatchResult m = r.match(input, 0);
        assertThat(m).as("match expected").isNotNull();
        return m.groups();
    }

    /** Fixed-length group: close tag is fixed-on open tag (offset = body length).
     *  Open tag remains the base. Reconstruction: close = open + len. */
    @Test void fixedLengthGroupCloseReconstructs() {
        CompiledRegex r = CompiledRegex.compile("(abc)");
        int[] g = groups(r, "abc");
        assertThat(g[2]).isEqualTo(0);   // g1 open
        assertThat(g[3]).isEqualTo(3);   // g1 close = open + 3
    }

    /** Multiple fixed-length groups: each close fixes on its own open. */
    @Test void multipleFixedLengthGroups() {
        CompiledRegex r = CompiledRegex.compile("(ab)(cd)");
        int[] g = groups(r, "abcd");
        assertThat(g[2]).isEqualTo(0); assertThat(g[3]).isEqualTo(2);   // g1
        assertThat(g[4]).isEqualTo(2); assertThat(g[5]).isEqualTo(4);   // g2
    }

    /** Groups separated by fixed text: open of g2 fixes on close of g1 (offset 1). */
    @Test void groupsSeparatedByFixedText() {
        CompiledRegex r = CompiledRegex.compile("(a)x(b)");
        int[] g = groups(r, "axb");
        assertThat(g[2]).isEqualTo(0); assertThat(g[3]).isEqualTo(1);   // g1 = "a"
        assertThat(g[4]).isEqualTo(2); assertThat(g[5]).isEqualTo(3);   // g2 = "b"
    }

    /** Nested groups of fixed length: outer close fixes on inner close (or vice versa). */
    @Test void nestedFixedLengthGroups() {
        CompiledRegex r = CompiledRegex.compile("((ab))");
        int[] g = groups(r, "ab");
        assertThat(g[2]).isEqualTo(0); assertThat(g[3]).isEqualTo(2);   // g1
        assertThat(g[4]).isEqualTo(0); assertThat(g[5]).isEqualTo(2);   // g2
    }

    /** Bounded fixed-count repeat: tags inside fix on each other within one iteration. */
    @Test void boundedFixedCountRepeat() {
        CompiledRegex r = CompiledRegex.compile("(a){3}");
        int[] g = groups(r, "aaa");
        // last iteration captures positions 2..3
        assertThat(g[2]).isEqualTo(2); assertThat(g[3]).isEqualTo(3);
    }

    /** Adjacent fixed-length groups after a fixed-text prefix. */
    @Test void fixedPrefixThenGroups() {
        CompiledRegex r = CompiledRegex.compile("x(ab)y(cd)");
        int[] g = groups(r, "xabycd");
        assertThat(g[2]).isEqualTo(1); assertThat(g[3]).isEqualTo(3);
        assertThat(g[4]).isEqualTo(4); assertThat(g[5]).isEqualTo(6);
    }

    /** Variable-length body: tags should NOT fix on each other. */
    @Test void variableLengthGroupNoFalseFix() {
        // (a+) is variable length; open and close must NOT fix on each other.
        CompiledRegex r = CompiledRegex.compile("(a+)");
        int[] g = groups(r, "aaaa");
        assertThat(g[2]).isEqualTo(0); assertThat(g[3]).isEqualTo(4);
    }

    /** Alternation with same-length branches: tags before+after fix across the alt. */
    @Test void altSameLengthBranches() {
        CompiledRegex r = CompiledRegex.compile("(a|b)c");
        int[] g = groups(r, "bc");
        assertThat(g[2]).isEqualTo(0); assertThat(g[3]).isEqualTo(1);
    }

    /** Alternation with different-length branches: tags after do NOT fix across. */
    @Test void altDiffLengthBranches() {
        CompiledRegex r = CompiledRegex.compile("(a|bb)c");
        // g1 is variable-length; c is at variable distance from g1 open.
        int[] g1 = groups(r, "ac");
        assertThat(g1[2]).isEqualTo(0); assertThat(g1[3]).isEqualTo(1);
        int[] g2 = groups(r, "bbc");
        assertThat(g2[2]).isEqualTo(0); assertThat(g2[3]).isEqualTo(2);
    }

    /** Realistic IP pattern: every group+separator is fixed-length-relative. */
    @Test void ipPattern() {
        CompiledRegex r = CompiledRegex.compile("(\\d+)\\.(\\d+)\\.(\\d+)\\.(\\d+)");
        int[] g = groups(r, "192.168.1.1");
        assertThat(g[2]).isEqualTo(0); assertThat(g[3]).isEqualTo(3);
        assertThat(g[4]).isEqualTo(4); assertThat(g[5]).isEqualTo(7);
        assertThat(g[6]).isEqualTo(8); assertThat(g[7]).isEqualTo(9);
        assertThat(g[8]).isEqualTo(10); assertThat(g[9]).isEqualTo(11);
    }

    /** ASM backend path: reconstruction must run in emitted bytecode too. */
    @Test void asmBackendReconstruction() {
        CompiledRegex r = CompiledRegex.compile("(abc)", CompileOptions.of().longestMatch());
        int[] g = groups(r, "abc");
        assertThat(g[2]).isEqualTo(0); assertThat(g[3]).isEqualTo(3);
    }

    /** Non-participating group: the base tag is NIL, so fixed tags must be NIL too. */
    @Test void nonParticipatingGroupPropagatesNil() {
        // (a)|(b) — only one group matches. The other must report NIL (-1).
        CompiledRegex r = CompiledRegex.compile("(a)|(b)");
        MatchResult m1 = r.match("a", 0);
        assertThat(m1).isNotNull();
        assertThat(m1.start(1)).isEqualTo(0);
        assertThat(m1.end(1)).isEqualTo(1);
        assertThat(m1.start(2)).isEqualTo(-1);
        assertThat(m1.end(2)).isEqualTo(-1);

        MatchResult m2 = r.match("b", 0);
        assertThat(m2).isNotNull();
        assertThat(m2.start(1)).isEqualTo(-1);
        assertThat(m2.end(1)).isEqualTo(-1);
        assertThat(m2.start(2)).isEqualTo(0);
        assertThat(m2.end(2)).isEqualTo(1);
    }
}
