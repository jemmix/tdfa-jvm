package io.github.jemmix.tdfa.parity;

import io.github.jemmix.tdfa.Pattern;
import io.github.jemmix.tdfa.PatternMatcher;
import io.github.jemmix.tdfa.sim.PikeSim;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import static io.github.jemmix.tdfa.parity.Re2jOracle.re2jFind;
import static io.github.jemmix.tdfa.parity.Re2jOracle.re2jFindPosix;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Multi-valued tags (BT22 &sect;3.1) under the parity ladder:
 *
 * <ul>
 * <li><b>S column (reference sim)</b> — PikeSim compiled multi-valued keeps
 * the winning thread's full (tag, pos) write log; its per-iteration spans
 * must equal BOTH engine lanes' {@code groupSpans} exactly (Perl mode —
 * the reference implements leftmost-first priority).</li>
 * <li><b>R column (re2j)</b> — the multi-valued compile's SINGLE-value
 * results must equal re2j's, in default and LONGEST_MATCH modes: the
 * opt-in lane adds the offset lists without moving one reported span.</li>
 * </ul>
 */
class MultiValuedParityTest {

    static Stream<RegexEngineFactorySrc> engines() {
        return Re2jOracle.engineFactories().map(f -> new RegexEngineFactorySrc(f));
    }

    record RegexEngineFactorySrc(io.github.jemmix.tdfa.core.compile.RegexEngineFactory factory) {
        @Override
        public String toString() {
            return String.valueOf(factory);
        }
    }

    private static final String[] PATTERNS = {"(a)+", "(a|b)+", "(?:(a)|(b))+", "(a?)*", "(a){2,4}", "((a)(b))+",
        "(a*)*", "(x)?(a)+", "a(b)", "(a+)(b+)", "((a)|b|c)*", "(a(b?)*)+", "((b|)|c)*", "(?:x|((?:a|bb)+)y)+",
        "(b|){2,3}", "(ab|a)(b?)+", "(?:(a)|b|(c))*"};

    private static final String[] INPUTS = {"", "a", "b", "ab", "ba", "aa", "abab", "aab", "abb", "abcab", "abba",
        "abc", "cbb", "bcc", "cc", "bbbb", "bbbaa", "ababab", "abababab"};

    @ParameterizedTest
    @MethodSource("engines")
    void spansMatchTheReferenceSim(RegexEngineFactorySrc src) {
        for (String pat : PATTERNS) {
            PikeSim sim = PikeSim.compileMulti(pat, null);
            for (String in : INPUTS) {
                PikeSim.PikeMatcher pm = sim.matcher(in);
                PatternMatcher tm = Pattern.compile(pat, Pattern.MULTI_VALUED_TAGS, src.factory()).matcher(in);
                boolean simFound = pm.find();
                assertThat(tm.find()).as("find <%s> <%s>", pat, in).isEqualTo(simFound);
                if (!simFound) {
                    continue;
                }
                for (int g = 0; g <= pm.groupCount(); g++) {
                    if (g == 0) {
                        assertThat(tm.start(0)).as("sim whole start <%s> <%s>", pat, in).isEqualTo(pm.start());
                        assertThat(tm.end(0)).as("sim whole end <%s> <%s>", pat, in).isEqualTo(pm.end());
                        continue;
                    }
                    assertThat(Objects.requireNonNullElse(tm.group(g), "<null>"))
                        .as("sim group <%s> <%s> g%d", pat, in, g)
                        .isEqualTo(Objects.requireNonNullElse(pm.group(g), "<null>"));
                    assertThat(tm.groupSpans(g)).as("sim spans <%s> <%s> g%d", pat, in, g)
                        .containsExactly(pm.spansOf(g));
                }
            }
        }
    }

    @ParameterizedTest
    @MethodSource("engines")
    void singleValuesMatchRe2jBothSemantics(RegexEngineFactorySrc src) {
        for (String pat : PATTERNS) {
            for (String in : INPUTS) {
                // default (leftmost-first)
                PatternMatcher tm = Pattern.compile(pat, Pattern.MULTI_VALUED_TAGS, src.factory()).matcher(in);
                int[] oracle = re2jFind(pat, in);
                boolean found = tm.find();
                assertThat(found).as("re2j find <%s> <%s>", pat, in).isEqualTo(oracle != null);
                if (!found) {
                    continue;
                }
                assertSpans(pat, in, tm, oracle);
                // leftmost-longest
                PatternMatcher pos = Pattern.compile(pat, Pattern.MULTI_VALUED_TAGS | Pattern.LONGEST_MATCH,
                    src.factory()).matcher(in);
                int[] oraclePos = re2jFindPosix(pat, in);
                boolean foundPos = pos.find();
                assertThat(foundPos).as("re2j posix find <%s> <%s>", pat, in).isEqualTo(oraclePos != null);
                if (foundPos) {
                    assertSpans(pat, in, pos, oraclePos);
                }
            }
        }
    }

    private static void assertSpans(String pat, String in, PatternMatcher m, int[] oracle) {
        List<Integer> got = new ArrayList<>(2 + 2 * m.groupCount());
        got.add(m.start(0));
        got.add(m.end(0));
        for (int g = 1; g <= m.groupCount(); g++) {
            got.add(m.start(g));
            got.add(m.end(g));
        }
        int[] flat = new int[got.size()];
        for (int i = 0; i < flat.length; i++) {
            flat[i] = got.get(i);
        }
        assertThat(flat).as("spans <%s> <%s>", pat, in).containsExactly(oracle);
    }
}
