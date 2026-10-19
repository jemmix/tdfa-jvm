package io.github.jemmix.tdfa;

import io.github.jemmix.tdfa.core.compile.RegexEngineFactory;
import io.github.jemmix.tdfa.core.dfa.TdfaRunner;
import io.github.jemmix.tdfa.core.engine.Matcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The unit-semantics trigger bound ({@code unitRawScan}): the raw scan's
 * kill-point proof extended over java.util.regex's any-unit start
 * positions — at every surrogate pair the scan additionally seeds the
 * start state's transitions on the lone low unit (a unit-semantics match
 * may start there, consuming it as a lone BMP char, and rejoin the
 * aligned stream one unit later). With the interior seeds the two
 * trigger products are sound under unit semantics: no accept live means
 * no match at all, and a live accept bounds every surviving
 * configuration's origin by the kill point W — the bound the restart
 * ladder was missing (the campaign 2026-10-16 finding-3 residue: the
 * {@code .+tail} family turned from n restarts × O(n) walks into one
 * linear pass).
 *
 * <p>The oracle differential runs against live java.util.regex on
 * surrogate-rich inputs — the JUR lane's domain, where interior-unit
 * starts are actually exercisable.
 */
class UnitTriggerBoundTest {

    private static final String PAIR = new String(Character.toChars(0x1D504)); // 𝔉
    private static final String UNPAIRED_HIGH = "\ud835";
    private static final String UNPAIRED_LOW = "\udd04";

    private static Stream<RegexEngineFactory> tiers() {
        return Stream.<RegexEngineFactory>of(null, TdfaRunner::new);
    }

    /** Surrogate-rich inputs: real pairs, unpaired halves, interiors. */
    private static String[] inputs() {
        return new String[]{"ab" + PAIR + "cd", PAIR + PAIR + "zz", UNPAIRED_HIGH + "x" + UNPAIRED_LOW,
            "a" + UNPAIRED_LOW + "b" + PAIR, "zz" + PAIR + UNPAIRED_LOW + "zz", PAIR + "z" + PAIR, "ab", "",};
    }

    /** The shapes the bound exists for, plus mask-y neighbors. */
    private static String[] patterns() {
        return new String[]{".+zz", // the plain .+tail: no-match inputs were quadratic
            "\\S{4,}\\.", ".+(" + UNPAIRED_LOW + ")", ".+(?:\\b|x)q", ".*(((?m:$\\d)b)\\s){0,4}n", "(\\A|.)" + PAIR,
            "[a-z]+" + PAIR + "+q", ".+" + PAIR + "h",};
    }

    @ParameterizedTest
    @MethodSource("tiers")
    void surrogateRichInputsMatchTheOracle(RegexEngineFactory tier) {
        for (String pattern : patterns()) {
            for (String input : inputs()) {
                String oracle = probeJur(pattern, input);
                String actual = probe(Pattern.compile(pattern, 0, tier), input);
                assertThat(actual).as("/%s/ on %s tier %s", pattern, escaped(input), name(tier)).isEqualTo(oracle);

                List<String> spans = new ArrayList<>(), oracleSpans = new ArrayList<>();
                for (Matcher m = Pattern.compile(pattern, 0, tier).matcher(input); m.find();) {
                    spans.add(m.start() + ".." + m.end());
                }
                java.util.regex.Matcher jm = java.util.regex.Pattern.compile(pattern).matcher(input);
                while (jm.find()) {
                    oracleSpans.add(jm.start() + ".." + jm.end());
                }
                assertThat(spans).as("exhaustive /%s/ on %s tier %s", pattern, escaped(input), name(tier))
                    .isEqualTo(oracleSpans);
            }
        }
    }

    /**
     * Perf canaries: the {@code .+tail} no-match family answers a
     * 4 400-char input in one linear pass — milliseconds, both tiers
     * (the pre-bound ladder measured 200–460 ms here; stretched fuzz
     * inputs 12–55 s).
     */
    @Test
    void plainTailFamilyIsLinear() {
        String input = "ab".repeat(2200); // 4400 chars, no match
        for (RegexEngineFactory tier : new RegexEngineFactory[]{null, TdfaRunner::new}) {
            for (String p : new String[]{".+zz", "\\S{4,}\\.", ".+(" + UNPAIRED_LOW + ")"}) {
                long t0 = System.nanoTime();
                assertThat(Pattern.compile(p, 0, tier).matcher(input).find()).as("/%s/ no match", p).isFalse();
                long ms = (System.nanoTime() - t0) / 1_000_000;
                assertThat(ms).as("4400-char /%s/ wall, tier %s", p, name(tier)).isLessThan(250);
            }
        }
    }

    private static String name(RegexEngineFactory f) {
        return f == null ? "asm" : "vm";
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

    private static String escaped(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c >= 0xD800 && c <= 0xDFFF) {
                sb.append(String.format("\\u%04X", (int) c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
