package io.github.jemmix.tdfa;

import io.github.jemmix.tdfa.core.compile.CompileOptions;
import io.github.jemmix.tdfa.core.compile.CompiledRegex;
import io.github.jemmix.tdfa.core.engine.MatchResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BT22 §3.3 non-trivial-cycle rejection in {@code map}: when tryMap's register
 * bijection yields COPY ops that form a cycle (dst != src), executing them
 * without a temporary register corrupts the register file — the pre-fix engine
 * emitted them anyway (bounded-stabilizer give-up), producing wrong or outright
 * invalid capture spans (e.g. start=0, end=-1). The fix makes
 * {@code topological_sort} return the paper's {@code nontrivial_cycle} flag and
 * {@code map} reject on it: determinization creates a fresh DFA state instead.
 *
 * <p>Pinned cases are real fuzzer findings (30k-pattern differential, old vs
 * new vs oracles): every behavioral difference was an old-engine capture bug;
 * expected values are oracle-verified (JDK java.util.regex for Perl mode,
 * patched re2j LONGEST_MATCH for POSIX mode). The minimal repro
 * {@code ((?:(?:(?:(?:b))|())|((?:(b|)|c)){2})){2}} is the shrunk pattern that
 * fires the POSIX-mode rejection at compile time.
 */
class MapCycleRejectionTest {

    private static final CompileOptions PERL = CompileOptions.of();
    private static final CompileOptions POSIX = CompileOptions.of().longestMatch();

    private static void assertGroups(CompileOptions opts, String pattern, String input, int[] expected) {
        CompiledRegex r = CompiledRegex.compile(pattern, opts);
        MatchResult m = r.match(input, 0);
        assertThat(m).as("match expected on <%s>", input).isNotNull();
        assertThat(m.groups()).as("groups on <%s>", input).isEqualTo(expected);
    }

    /**
     * The shrunk rejection repro. POSIX determinization hits the cyclic
     * register bijection (one {@code [map] rejected} event, 14 states) and
     * must still match re2j exactly. Perl output equals re2j default mode.
     */
    @Test
    void minimalReproMatchesOracles() {
        String pat = "((?:(?:(?:(?:b))|())|((?:(b|)|c)){2})){2}";
        // re2j default (leftmost-first) == our Perl mode, all inputs over {b,c}
        String[][] perlCases = {{"", "0,0,0,0,0,0,-1,-1,-1,-1"}, {"b", "0,1,1,1,1,1,-1,-1,-1,-1"},
            {"c", "0,0,0,0,0,0,-1,-1,-1,-1"}, {"bb", "0,2,1,2,-1,-1,-1,-1,-1,-1"}, {"bc", "0,1,1,1,1,1,-1,-1,-1,-1"},
            {"cb", "0,0,0,0,0,0,-1,-1,-1,-1"}, {"cc", "0,0,0,0,0,0,-1,-1,-1,-1"}, {"bbb", "0,2,1,2,-1,-1,-1,-1,-1,-1"},
            {"bbc", "0,2,1,2,-1,-1,-1,-1,-1,-1"}, {"bcb", "0,1,1,1,1,1,-1,-1,-1,-1"},
            {"bcc", "0,1,1,1,1,1,-1,-1,-1,-1"}, {"cbb", "0,0,0,0,0,0,-1,-1,-1,-1"}, {"cbc", "0,0,0,0,0,0,-1,-1,-1,-1"},
            {"ccb", "0,0,0,0,0,0,-1,-1,-1,-1"}, {"ccc", "0,0,0,0,0,0,-1,-1,-1,-1"},
            {"bbbb", "0,2,1,2,-1,-1,-1,-1,-1,-1"}, {"bbcb", "0,2,1,2,-1,-1,-1,-1,-1,-1"},
            {"bcbb", "0,1,1,1,1,1,-1,-1,-1,-1"}, {"cbbc", "0,0,0,0,0,0,-1,-1,-1,-1"},};
        for (String[] c : perlCases) {
            assertGroups(PERL, pat, c[0], parse(c[1]));
        }
        // re2j LONGEST_MATCH == our POSIX mode on every input, "bcc"
        // included: its old corrupt g5 span [0,-1] — the cyclic-shuffle
        // half-write surfacing through the {2}-repetition's empty-iteration
        // final ops — is gone; the rejection fixes it like every other
        // case (it reproduced one commit before the fix).
        String[][] posixCases = {{"", "0,0,0,0,0,0,-1,-1,-1,-1"}, {"b", "0,1,1,1,1,1,-1,-1,-1,-1"},
            {"c", "0,1,0,1,0,0,0,1,0,0"}, {"bb", "0,2,1,2,-1,-1,-1,-1,-1,-1"}, {"bc", "0,2,1,2,-1,-1,1,2,1,1"},
            {"cb", "0,2,0,2,0,0,1,2,1,2"}, {"cc", "0,2,0,2,0,0,1,2,-1,-1"}, {"bbb", "0,3,1,3,-1,-1,2,3,2,3"},
            {"bbc", "0,3,1,3,-1,-1,2,3,1,2"}, {"bcb", "0,3,1,3,-1,-1,2,3,2,3"}, {"bcc", "0,3,1,3,-1,-1,2,3,-1,-1"},
            {"cbb", "0,3,1,3,-1,-1,2,3,2,3"}, {"cbc", "0,3,1,3,-1,-1,2,3,1,2"}, {"ccb", "0,3,1,3,-1,-1,2,3,2,3"},
            {"ccc", "0,3,1,3,-1,-1,2,3,0,0"}, {"bbbb", "0,4,2,4,-1,-1,3,4,3,4"}, {"bbcb", "0,4,2,4,-1,-1,3,4,3,4"},
            {"bcbb", "0,4,2,4,-1,-1,3,4,3,4"}, {"cbbc", "0,4,2,4,-1,-1,3,4,2,3"},};
        for (String[] c : posixCases) {
            assertGroups(POSIX, pat, c[0], parse(c[1]));
        }
    }

    /**
     * Fuzzer-found capture corruptions, fixed by the rejection: the old engine
     * reported g2=[0,-1] (an impossible span) for the first pattern in BOTH
     * modes — the cyclic copy shuffle half-wrote the tag pair.
     */
    @Test
    void cyclicMergeCaptureCorruptionIsFixed() {
        String p1 = "((?:(?:(?:c[ab]|(c|.)))*|b)[ab])([ab])(a(?:a|b))";
        int[] p1Expected = {0, 4, 0, 1, -1, -1, 1, 2, 2, 4};
        for (String in : new String[]{"aaaa", "baaa", "abaa", "bbaa", "aaab", "baab", "abab", "bbab"}) {
            assertGroups(PERL, p1, in, p1Expected);
            assertGroups(POSIX, p1, in, p1Expected);
        }

        String p2 = "((((?:a)*cbc|.))+)(c)((c)*)";
        assertGroups(PERL, p2, "cbcc", new int[]{0, 4, 0, 3, 0, 3, 0, 3, 3, 4, 4, 4, -1, -1});
        assertGroups(POSIX, p2, "cbcc", new int[]{0, 4, 0, 3, 0, 3, 0, 3, 3, 4, 4, 4, -1, -1});

        String p3 = "(((?:(?:\\wb|\\d)|\\s)|(b(?:\\d)?)*)((bb|[ab])|(\\s|(?:\\d){2,4}))(a[ab]|\\w))";
        int[] p3Expected = {0, 4, 0, 4, 0, 2, -1, -1, 2, 3, 2, 3, -1, -1, 3, 4};
        assertGroups(PERL, p3, "bbaa", p3Expected);
        assertGroups(PERL, p3, "bbac", p3Expected);
        assertGroups(POSIX, p3, "bbaa", p3Expected);
        assertGroups(POSIX, p3, "bbac", p3Expected);
    }

    /**
     * More of the same family (counted repetition of group-bearing
     * alternations): old engine lost or invented group participations where
     * the cyclic shuffle clobbered one half of a tag pair.
     */
    @Test
    void countedRepeatCaptureShiftsAreFixed() {
        String p4 = "(((b(?:b|\\w)|(cb|([ab]|\\w)))){1,3}|a)((?:ab|\\d(?:(?:a)?|(b|b)))|"
            + "(((\\s|\\d)\\w[ab]|(bc)+)|((a)+){0,2}\\d.b))a";
        int[] p4Expected =
            {0, 4, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 1, 3, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1};
        assertGroups(PERL, p4, "aaba", p4Expected);
        assertGroups(POSIX, p4, "aaba", p4Expected);

        String p5 = "(c.|(?:((c|c)){1,3})?)c((c\\d)?|(cc|([ab])+))(((\\w|c))+){1,3}(?:c)?";
        assertGroups(PERL, p5, "ccca",
            new int[]{0, 4, 0, 2, -1, -1, -1, -1, 3, 3, -1, -1, -1, -1, -1, -1, 3, 4, 3, 4, 3, 4});
        assertGroups(PERL, p5, "ccaa",
            new int[]{0, 4, 0, 1, 0, 1, 0, 1, 2, 2, -1, -1, -1, -1, -1, -1, 2, 4, 3, 4, 3, 4});
        assertGroups(POSIX, p5, "ccca",
            new int[]{0, 4, 0, 2, -1, -1, -1, -1, 3, 3, -1, -1, -1, -1, -1, -1, 3, 4, 3, 4, 3, 4});
        assertGroups(POSIX, p5, "ccaa",
            new int[]{0, 4, 0, 1, 0, 1, 0, 1, 2, 2, -1, -1, -1, -1, -1, -1, 2, 4, 3, 4, 3, 4});

        String p6 = "((?:((\\w|[ab])(?:b)*(.c|(c)?)|a)){2,4})(a)";
        int[] p6Expected = {0, 4, 0, 3, 2, 3, 2, 3, 3, 3, 1, 2, 3, 4};
        for (String in : new String[]{"acaa", "bcaa", "ccaa", "acba", "bcba", "ccba"}) {
            assertGroups(PERL, p6, in, p6Expected);
        }
    }

    /** No (start >= 0, end = -1) or inverted spans anywhere on the fixed corpus. */
    @Test
    void noCorruptSpansOnRegressions() {
        String[] pats = {"((?:(?:(?:c[ab]|(c|.)))*|b)[ab])([ab])(a(?:a|b))", "((((?:a)*cbc|.))+)(c)((c)*)",
            "(((?:(?:\\wb|\\d)|\\s)|(b(?:\\d)?)*)((bb|[ab])|(\\s|(?:\\d){2,4}))(a[ab]|\\w))",
            "((?:((\\w|[ab])(?:b)*(.c|(c)?)|a)){2,4})(a)",};
        for (CompileOptions opts : new CompileOptions[]{PERL, POSIX}) {
            for (String p : pats) {
                CompiledRegex r = CompiledRegex.compile(p, opts);
                for (String in : new String[]{"aaaa", "baab", "abab", "bbab", "cbcc", "bbaa", "bbac", "acaa", "ccba"}) {
                    MatchResult m = r.match(in, 0);
                    if (m == null) {
                        continue;
                    }
                    int[] g = m.groups();
                    for (int k = 0; k + 1 < g.length; k += 2) {
                        assertThat(g[k]).as("pair start <%s> <%s>", p, in).isGreaterThanOrEqualTo(-1);
                        if (g[k] == -1) {
                            assertThat(g[k + 1]).as("null pair end <%s> <%s>", p, in).isEqualTo(-1);
                        } else {
                            assertThat(g[k + 1]).as("span end <%s> <%s>", p, in).isGreaterThanOrEqualTo(g[k]);
                        }
                    }
                }
            }
        }
    }

    private static int[] parse(String csv) {
        String[] parts = csv.split(",");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            out[i] = Integer.parseInt(parts[i]);
        }
        return out;
    }
}
