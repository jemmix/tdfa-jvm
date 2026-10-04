package io.github.jemmix.tdfa;

import io.github.jemmix.tdfa.core.compile.CompileOptions;
import io.github.jemmix.tdfa.core.compile.CompiledRegex;
import io.github.jemmix.tdfa.core.compile.RegexEngineFactory;
import io.github.jemmix.tdfa.core.dfa.TdfaRunner;
import io.github.jemmix.tdfa.core.engine.MatchResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Multi-valued tags (BT22 &sect;3.1): the opt-in compile lane where every
 * capture group keeps its full offset sequence under repetition. Three
 * contracts are pinned:
 *
 * <ul>
 * <li><b>Spans</b> — {@code groupSpans(g)} returns one pair per
 * participating iteration, in match order (bypassed iterations contribute
 * nothing: the engine's re2j capture contract keeps a value once set).</li>
 * <li><b>Single-value identity</b> — {@code start/end/group} of a
 * multi-valued compile are EXACTLY the plain compile's (the last pair of
 * every span list is the single-value report), over a corpus spanning the
 * known-hard families (counted repeats, empty iterations, alternation
 * bypasses, POSIX mode).</li>
 * <li><b>Tier identity</b> — the generated (default) and interpreter lanes
 * produce identical spans, whole-input matches included.</li>
 * </ul>
 */
class MultiValuedTagsTest {

    static Stream<RegexEngineFactory> engines() {
        return Stream.of(null, TdfaRunner::new); // null = default ASM tier
    }

    private static PatternMatcher m(RegexEngineFactory f, String pat, int flags, CharSequence in) {
        return Pattern.compile(pat, Pattern.MULTI_VALUED_TAGS | flags, f).matcher(in);
    }

    @ParameterizedTest
    @MethodSource("engines")
    void iterationSpans(RegexEngineFactory f) {
        assertThat(spans(f, "(a)+", "aaa", 1)).containsExactly(0, 1, 1, 2, 2, 3);
        assertThat(spans(f, "(a|b)+", "abba", 1)).containsExactly(0, 1, 1, 2, 2, 3, 3, 4);
        // per-branch participation: only the iterations that matched a/b
        assertThat(spans(f, "(?:(a)|(b))+", "abab", 1)).containsExactly(0, 1, 2, 3);
        assertThat(spans(f, "(?:(a)|(b))+", "abab", 2)).containsExactly(1, 2, 3, 4);
        // counted repetition desugars to structural copies — same tags
        assertThat(spans(f, "(a){2,4}", "aaaaa", 1)).containsExactly(0, 1, 1, 2, 2, 3, 3, 4);
        // nested groups under one loop
        assertThat(spans(f, "((a)(b))+", "abab", 1)).containsExactly(0, 2, 2, 4);
        assertThat(spans(f, "((a)(b))+", "abab", 2)).containsExactly(0, 1, 2, 3);
        assertThat(spans(f, "((a)(b))+", "abab", 3)).containsExactly(1, 2, 3, 4);
        // empty-iteration cut families keep the last NON-empty iteration
        assertThat(spans(f, "(a?)*", "aa", 1)).containsExactly(0, 1, 1, 2);
        // POSIX lane: spans of the leftmost-longest parse
        assertThat(spans(f, "((a)|(b)|c)*", "abcab", 2, Pattern.LONGEST_MATCH)).containsExactly(0, 1, 3, 4);
        assertThat(spans(f, "((a)|(b)|c)*", "abcab", 3, Pattern.LONGEST_MATCH)).containsExactly(1, 2, 4, 5);
    }

    @ParameterizedTest
    @MethodSource("engines")
    void neverMatchedAndWholeMatch(RegexEngineFactory f) {
        assertThat(spans(f, "(x)?(a)+", "aa", 1)).containsExactly(-1, -1);
        assertThat(spans(f, "(x)?(a)+", "aa", 2)).containsExactly(0, 1, 1, 2);
        PatternMatcher mm = m(f, "(a)+", 0, "aaa");
        assertThat(mm.find()).isTrue();
        assertThat(mm.groupSpans(0)).containsExactly(0, 3);
        assertThatThrownBy(() -> mm.groupSpans(2)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> mm.groupSpans(-1)).isInstanceOf(IndexOutOfBoundsException.class);
    }

    @ParameterizedTest
    @MethodSource("engines")
    void lastPairIsTheSingleValueReport(RegexEngineFactory f) {
        String[] pats = {"(a)+", "(a|b)+", "(?:(a)|(b))+", "(a?)*", "(a){2,4}", "((a)(b))+", "(a*)*", "(x)?(a)+",
            "(a+)(b+)", "((a)|b|c)*", "(a(b?)*)+", "((b|)|c)*", "(?:x|((?:a|bb)+)y)+", "(b|){2,3}"};
        String[] inputs = {"", "a", "b", "ab", "ba", "aa", "abab", "aab", "abb", "abcab", "abba", "abc", "cbb",
            "bcc", "cc", "bbbb", "bbbaa"};
        for (String pat : pats) {
            for (int flags : new int[]{0, Pattern.LONGEST_MATCH}) {
                PatternMatcher mm = m(f, pat, flags, "dummy");
                for (String in : inputs) {
                    PatternMatcher single = Pattern.compile(pat, flags, f).matcher(in);
                    PatternMatcher multi = m(f, pat, flags, in);
                    boolean fs = single.find();
                    boolean fm = multi.find();
                    assertThat(fm).as("find parity <%s> <%s>", pat, in).isEqualTo(fs);
                    if (!fm) {
                        continue;
                    }
                    for (int g = 0; g <= multi.groupCount(); g++) {
                        assertThat(multi.start(g)).as("start <%s> <%s> g%d", pat, in, g).isEqualTo(single.start(g));
                        assertThat(multi.end(g)).as("end <%s> <%s> g%d", pat, in, g).isEqualTo(single.end(g));
                        int[] sp = multi.groupSpans(g);
                        assertThat(sp.length).as("span pairs <%s> <%s> g%d", pat, in, g).isGreaterThanOrEqualTo(2);
                        if (g > 0 && !(sp.length == 2 && sp[0] == -1)) {
                            assertThat(sp[sp.length - 2]).as("last open <%s> <%s> g%d", pat, in, g)
                                .isEqualTo(single.start(g));
                            assertThat(sp[sp.length - 1]).as("last close <%s> <%s> g%d", pat, in, g)
                                .isEqualTo(single.end(g));
                        }
                    }
                }
                // silence unused warning for the helper matcher above
                assertThat(mm.groupCount()).isGreaterThanOrEqualTo(0);
            }
        }
    }

    /**
     * The fuzzer-found regression families of MapCycleRejectionTest: the
     * multi lane must agree with the plain compile (and the oracle-pinned
     * expectations there) on every single value.
     */
    @ParameterizedTest
    @MethodSource("engines")
    void mapCycleCorpusSingleValueIdentity(RegexEngineFactory f) {
        String[] pats = {"((?:(?:(?:(?:b))|())|((?:(b|)|c)){2})){2}",
            "((?:(?:(?:c[ab]|(c|.)))*|b)[ab])([ab])(a(?:a|b))", "((((?:a)*cbc|.))+)(c)((c)*)",
            "(((?:(?:\\wb|\\d)|\\s)|(b(?:\\d)?)*)((bb|[ab])|(\\s|(?:\\d){2,4}))(a[ab]|\\w))",
            "((?:((\\w|[ab])(?:b)*(.c|(c)?)|a)){2,4})(a)"};
        String[] inputs = {"", "b", "c", "bb", "bc", "bcc", "cb", "cc", "aaa", "aaaa", "baaa", "cbcc", "bbaa",
            "bbac", "acaa", "ccba"};
        for (String pat : pats) {
            for (int flags : new int[]{0, Pattern.LONGEST_MATCH}) {
                for (String in : inputs) {
                    PatternMatcher single = Pattern.compile(pat, flags, f).matcher(in);
                    PatternMatcher multi = m(f, pat, flags, in);
                    boolean fs = single.find();
                    boolean fm = multi.find();
                    assertThat(fm).as("find <%s> <%s>", pat, in).isEqualTo(fs);
                    if (!fs) {
                        continue;
                    }
                    for (int g = 0; g <= single.groupCount(); g++) {
                        assertThat(multi.start(g)).as("start <%s> <%s> g%d", pat, in, g).isEqualTo(single.start(g));
                        assertThat(multi.end(g)).as("end <%s> <%s> g%d", pat, in, g).isEqualTo(single.end(g));
                    }
                }
            }
        }
    }

    @ParameterizedTest
    @MethodSource("engines")
    void wholeMatchAndIterationReuseTheCarrier(RegexEngineFactory f) {
        // matches() (whole-input walk) produces spans too, and consecutive
        // find() iterations on ONE matcher keep the pooled tree correct
        PatternMatcher mm = m(f, "(a)+", 0, "a aa aaa");
        // (a)+ over "a", "aa", "aaa": one/two/three iteration spans
        int expectFrom = 0;
        int[][] expect = {{0, 1}, {2, 3, 3, 4}, {5, 6, 6, 7, 7, 8}};
        int[][] wholeSpan = {{0, 1}, {2, 4}, {5, 8}};
        for (int i = 0; i < expect.length; i++) {
            assertThat(mm.find(expectFrom)).as("find from %d", expectFrom).isTrue();
            assertThat(mm.groupSpans(1)).containsExactly(expect[i]);
            assertThat(mm.start(0)).isEqualTo(wholeSpan[i][0]);
            assertThat(mm.end(0)).isEqualTo(wholeSpan[i][1]);
            expectFrom = wholeSpan[i][1] + 1;
        }
        PatternMatcher whole = m(f, "(a)+", 0, "aaa");
        assertThat(whole.matches()).isTrue();
        assertThat(whole.groupSpans(1)).containsExactly(0, 1, 1, 2, 2, 3);
    }

    @Test
    void singleValuedCompileDegradesToOnePair() {
        CompiledRegex r = CompiledRegex.compile("(a)+");
        MatchResult res = r.match("aaa", 0);
        assertThat(res).isNotNull();
        assertThat(res.groupSpans(1)).containsExactly(2, 3);
        assertThat(res.groupSpans(0)).containsExactly(0, 3);
    }

    @Test
    void longInputSanity() {
        char[] cs = new char[2048];
        Arrays.fill(cs, 'a');
        PatternMatcher mm = m(null, "(a)+", 0, new String(cs));
        assertThat(mm.find()).isTrue();
        int[] sp = mm.groupSpans(1);
        assertThat(sp.length).isEqualTo(2 * 2048);
        for (int i = 0; i + 1 < sp.length; i += 2) {
            assertThat(sp[i]).isEqualTo(i / 2);
            assertThat(sp[i + 1]).isEqualTo(i / 2 + 1);
        }
    }

    @Test
    void optionsRoundTrip() {
        assertThat(CompileOptions.of().multiValuedTags().isMultiValuedTags()).isTrue();
        assertThat(CompileOptions.of().isMultiValuedTags()).isFalse();
        assertThat(CompileOptions.of().multiValuedTags().longestMatch().isLongestMatch()).isTrue();
        Pattern p = Pattern.compile("(a)+", CompileOptions.of().multiValuedTags());
        assertThat(p.flags() & Pattern.MULTI_VALUED_TAGS).isEqualTo(Pattern.MULTI_VALUED_TAGS);
        // and back through the flags route
        assertThat(Pattern.compile("(a)+", Pattern.MULTI_VALUED_TAGS).matcher("aa").find()).isTrue();
    }

    private static int[] spans(RegexEngineFactory f, String pat, CharSequence in, int group) {
        return spans(f, pat, in, group, 0);
    }

    private static int[] spans(RegexEngineFactory f, String pat, CharSequence in, int group, int flags) {
        PatternMatcher mm = m(f, pat, flags, in);
        assertThat(mm.find()).as("match <%s> on <%s>", pat, in).isTrue();
        return mm.groupSpans(group);
    }
}
