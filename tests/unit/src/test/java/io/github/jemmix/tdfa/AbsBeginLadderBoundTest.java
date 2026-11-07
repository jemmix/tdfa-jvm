package io.github.jemmix.tdfa;

import io.github.jemmix.tdfa.core.compile.RegexEngineFactory;
import io.github.jemmix.tdfa.core.compile.TDFAPattern;
import io.github.jemmix.tdfa.core.dfa.Tdfa;
import io.github.jemmix.tdfa.core.dfa.TdfaRunner;
import io.github.jemmix.tdfa.core.engine.Matcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The abs-begin ladder bound ({@link Tdfa#absBeginBound()}): an artifact
 * whose every accepting path crosses an {@code \A} gate — range
 * required-mask, target entry mask, or accept mask — can only match
 * starting at absolute position 0, so the unit-semantics restart ladder
 * (no trigger bound under java.util.regex semantics) collapses to the
 * exact walk at the origin. This is the root fix for the campaign
 * 2026-10-16 finding-3 {@code .*}-anchor restart quadratic: the
 * {@code .*(?:\A\W )h} / {@code .+^\W} shapes measured 12–55 s of ladder
 * spin; a non-multiline {@code ^} compiles to ABS_BEGIN and is covered
 * with it. Anchors that are avoidable ({@code (\A|.)x}) or multiline
 * ({@code (?m)^} — BEGIN_TEXT holds past 0) keep the ladder.
 */
class AbsBeginLadderBoundTest {

    private static Stream<Object[]> tiers() {
        return Stream.of(new Object[]{(RegexEngineFactory) null}, new Object[]{(RegexEngineFactory) TdfaRunner::new});
    }

    /** {pattern, flags, expectBound} — classification against the derived predicate. */
    private static Object[][] classified() {
        return new Object[][]{
            // \A behind a consuming loop: every accepting path gated
            {".*(?:\\A\\W )h", 0, true}, {".+\\A\\w", 0, true}, {".{1,}(?:\\A\\w)\\w", 35, true}, {".+^\\W", 0, true}, // non-multiline ^ == \A
            {".*\\A.W h", 0, true},
            // \A at the head: the classic start-gated collapse, folded in
            {"\\A.*", 0, true}, {"\\Aab", 0, true},
            // anchors avoidable or position-generic: ladder stays
            {"(\\A|.)x", 0, false}, {"(?:x|\\A)y", 0, false}, {"(?i)(?:\\Ak|q)+", 0, false}, {"(?m)^b", 0, false}, // BEGIN_TEXT holds at every line begin
            {"(?m).+^\\W", 0, false}, {".*(((?m:$\\d)b)\\s){0,4}n", 0, false}, // multiline $: no start gate at all
            {"abc", 0, false}, {"\\bword\\b", 0, false}, {".*\\z", 0, false}, // end anchor: constrains the end, not the start
        };
    }

    @Test
    void classificationMatchesThePredicate() throws Exception {
        Field f = TdfaRunner.class.getDeclaredField("tdfa");
        f.setAccessible(true);
        for (Object[] c : classified()) {
            String pattern = (String) c[0];
            int flags = (Integer) c[1];
            boolean expect = (Boolean) c[2];
            TDFAPattern p = (TDFAPattern) Pattern.compile(pattern, flags, TdfaRunner::new, null);
            Tdfa tdfa = (Tdfa) f.get(p.engine());
            assertThat(tdfa.absBeginBound()).as("absBeginBound /%s/ flags %d", pattern, flags).isEqualTo(expect);
        }
    }

    /** Behavior must be oracle-identical whether or not the ladder collapsed. */
    @ParameterizedTest
    @MethodSource("tiers")
    void boundShapesMatchTheOracle(RegexEngineFactory tier) {
        String[][] behavior = {{".*(?:\\A\\W )h", "ab cd h"}, {".*(?:\\A\\W )h", "! h"}, {".*(?:\\A\\W )h", "ab! h"},
            {".*(?:\\A\\W )h", ""}, {".+\\A\\w", "ab"}, {".+\\A\\w", "a"}, {".+^\\W", "ab-"}, {".+^\\W", "a-"},
            {"\\A.*", "abc"}, {"\\A.*", ""}, {"(\\A|.)x", "abx"}, {"(\\A|.)x", "ax"}, {"(\\A|.)x", "x"},
            {"(?:x|\\A)y", "xy"}, {"(?:x|\\A)y", "zy"}, {"(?i)(?:\\Ak|q)+", "kqk"}, {"(?i)(?:\\Ak|q)+", "zqk"},
            {"(?m)^b", "a\nb"}, {"(?m)^b", "b"}, {".*\\A.W h", "ab cd h"}, {".*\\A.W h", "W h"},};
        for (String[] c : behavior) {
            String pattern = c[0], input = c[1];
            String oracle = probeJur(pattern, input);
            String actual = probe(Pattern.compile(pattern, 0, tier), input);
            assertThat(actual).as("/%s/ on [%s] tier %s", pattern, input, tier).isEqualTo(oracle);
            // find-to-exhaustion too: the continuation after a match at 0
            // runs from the match end — the bound must not skip a viable
            // LATER match for unbound shapes, and must not invent one for
            // bound shapes.
            List<String> spans = new ArrayList<>(), oracleSpans = new ArrayList<>();
            for (Matcher m = Pattern.compile(pattern, 0, tier).matcher(input); m.find();) {
                spans.add(m.start() + ".." + m.end());
            }
            java.util.regex.Matcher jm = java.util.regex.Pattern.compile(pattern).matcher(input);
            while (jm.find()) {
                oracleSpans.add(jm.start() + ".." + jm.end());
            }
            assertThat(spans).as("exhaustive /%s/ on [%s] tier %s", pattern, input, tier).isEqualTo(oracleSpans);
        }
    }

    /**
     * The perf canary: the campaign's 39 s {@code .*}-anchor spin is a
     * linear walk now — the collapsed ladder answers a 4 400-char no-match
     * find in single-digit milliseconds.
     */
    @Test
    void anchorLadderIsLinearNotQuadratic() {
        String input = "ab".repeat(2200); // 4400 chars, no 'h'
        for (RegexEngineFactory tier : new RegexEngineFactory[]{null, TdfaRunner::new}) {
            Pattern p = Pattern.compile(".*(?:\\A\\W )h", 0, tier);
            long t0 = System.nanoTime();
            assertThat(p.matcher(input).find()).as("no 'h', no match").isFalse();
            long ms = (System.nanoTime() - t0) / 1_000_000;
            // 2-5 ms measured; the campaign's budget-contained replay
            // was 12-55 s and the first unbounded stretch quadratic —
            // 250 ms is two orders of margin against JIT/machine noise.
            assertThat(ms).as("4400-char collapsed ladder wall, tier %s", tier).isLessThan(250);
        }
    }

    private static String probe(Pattern p, String input) {
        Matcher m = p.matcher(input);
        String f = m.find() ? m.start() + ".." + m.end() : "false";
        return "F=" + f + " M=" + p.matcher(input).matches() + " L=" + p.matcher(input).lookingAt();
    }

    private static String probeJur(String pattern, String input) {
        java.util.regex.Pattern jp = java.util.regex.Pattern.compile(pattern);
        java.util.regex.Matcher m = jp.matcher(input);
        String f = m.find() ? m.start() + ".." + m.end() : "false";
        return "F=" + f + " M=" + jp.matcher(input).matches() + " L=" + jp.matcher(input).lookingAt();
    }
}
