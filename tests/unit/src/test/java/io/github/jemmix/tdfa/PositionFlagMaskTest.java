package io.github.jemmix.tdfa;

import io.github.jemmix.tdfa.core.compile.RegexEngineFactory;
import io.github.jemmix.tdfa.core.dfa.TdfaRunner;
import io.github.jemmix.tdfa.core.engine.Matcher;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mask-directed position-flag checks (campaign 2026-10-16 finding 3 cost
 * work): the start-state entry probes and the per-step post-transition
 * entry probes answer via the mask-directed predicate
 * ({@code maskSatisfied}/{@code maskSatisfiedCS} in the runner, the
 * {@code posFlagDeps()}-specialized {@code positionFlagsC} in emitted
 * classes) instead of materialising the full flag battery. Each posFlag
 * bit's entry/entryNext formula must stay EXACT — this test differentials
 * the probe-heavy shapes against live {@code java.util.regex} (the default
 * lane's parity oracle) on both tiers: find/matches/lookingAt protocol
 * plus find-to-exhaustion spans, the fuzzer's probe protocol in
 * miniature.
 *
 * <p>Shapes are chosen so the entry probes fire per STEP (the anchor or
 * boundary sits behind a consuming loop, gating a mid-walk state), not
 * merely once at the start — the sites the mask-directed path serves.
 */
class PositionFlagMaskTest {

    private static Stream<RegexEngineFactory> factories() {
        return Stream.<RegexEngineFactory>of(null, TdfaRunner::new);
    }

    private static final String SUP = new String(Character.toChars(0x1D504)); // 𝔉 Fraktur A, a (?u) word char
    private static final String KELVIN = new String(Character.toChars(0x212A)); // KELVIN SIGN, folds to k

    /** One case: the tdfa pattern (a leading {@code (?u)} maps to the
     *  oracle's UNICODE_CHARACTER_CLASS) and the input. */
    private static List<String[]> cases() {
        List<String[]> cs = new ArrayList<>();
        // ABS_BEGIN behind a consuming loop: the \A-gated state's entry
        // probe runs per step; a match can only cross it at position 0.
        cs.add(new String[]{".*(?:\\A\\W )h", "ab cd h"});
        cs.add(new String[]{".*(?:\\A\\W )h", "! h"});
        cs.add(new String[]{".*(?:\\A\\W )h", "ab! h"});
        cs.add(new String[]{".+\\A.", "ab"});
        // ABS_END behind a loop: entry probe at pos == len only.
        cs.add(new String[]{"[a-z]+d\\z", "abcd"});
        cs.add(new String[]{"[a-z]+d\\z", "abcde"});
        cs.add(new String[]{".+\\z.", "ab"});
        // BEGIN_TEXT under (?m): after any terminator, never at end of
        // input ("Perl does not match ^ at end of input even after
        // newline"); never inside a \r\n pair.
        cs.add(new String[]{"(?m)a*^b", "a\nb"});
        cs.add(new String[]{"(?m)a*^b", "ab"});
        cs.add(new String[]{"(?m)x^b", "x\nb"});
        cs.add(new String[]{"(?m)^b", "a\r\nb"});
        // END_TEXT under (?m): before any terminator minus \r\n interiors
        // (JDK: (?m)$ on "a\r\nb" matches at 1 and 4, never at 2).
        cs.add(new String[]{"(?m)a$", "a\r\nb"});
        cs.add(new String[]{"(?m)a$.x", "a\r\nb"});
        // FINAL_END (plain $): "a\n\n" matches at 2 and 3; on "a\r\n" at 1
        // and 3 — the \r position owns the pair.
        cs.add(new String[]{"a$", "a\n\n"});
        cs.add(new String[]{"\\n$a", "a\n\na"});
        cs.add(new String[]{"a$", "a\r\n"});
        // WORD_BOUNDARY behind a loop near supplementary letters: the pair
        // decodes to the full codepoint under (?u).
        cs.add(new String[]{"(?u)x+\\b.", "xx" + SUP});
        cs.add(new String[]{"(?u)x+\\B.", "xx\ud835"});
        cs.add(new String[]{"(?u)\\w+\\b", "ab" + SUP + "cd"});
        cs.add(new String[]{"(?u)\\b\\w+\\b", "ab cd " + SUP});
        // NO_WORD_BOUNDARY mid-word and at supplementary adjacency
        cs.add(new String[]{"(?u)ab\\Bcd", "abcd"});
        cs.add(new String[]{"(?u)ab\\Bcd", "ab cd"});
        cs.add(new String[]{"(?u)" + SUP + "\\B" + SUP, SUP + SUP});
        // CI fold-mask interplay (non-disjoint candidate loop + entryNext):
        // the K/k fold orbit under (?i) carries required masks on entries.
        cs.add(new String[]{"(?i).+[k]x", "abkx"});
        cs.add(new String[]{"(?i).+[k]x", "ab" + KELVIN + "x"});
        cs.add(new String[]{"(?i)(?:\\Ak|q)+", "kqk"});
        // Mixed bits on one ladder: anchor + boundary branches
        cs.add(new String[]{"(?:\\Ax|\\By)+", "x y x"});
        cs.add(new String[]{"(?:\\Ax|\\By)+", "y y"});
        return cs;
    }

    @ParameterizedTest
    @MethodSource("factories")
    void entryProbesMatchTheOracle(RegexEngineFactory f) {
        for (String[] c : cases()) {
            String pattern = c[0], input = c[1];
            String oracle = probeJur(oraclePattern(pattern), oracleFlags(pattern), input);
            String actual = probe(Pattern.compile(pattern, 0, f), input);
            assertThat(actual).as("/%s/ on %s (tier %s)", pattern, escaped(input), tier(f)).isEqualTo(oracle);
        }
    }

    @ParameterizedTest
    @MethodSource("factories")
    void exhaustiveFindAgreesAcrossTiersAndOracle(RegexEngineFactory f) {
        for (String[] c : cases()) {
            String pattern = c[0], input = c[1];
            java.util.regex.Matcher jm =
                java.util.regex.Pattern.compile(oraclePattern(pattern), oracleFlags(pattern)).matcher(input);
            List<String> oracleSpans = new ArrayList<>();
            while (jm.find()) {
                oracleSpans.add(jm.start() + ".." + jm.end());
            }
            Pattern q = Pattern.compile(pattern, 0, f);
            Pattern other = Pattern.compile(pattern, 0, f == null ? TdfaRunner::new : null);
            List<String> spans = new ArrayList<>(), otherSpans = new ArrayList<>();
            for (Matcher m = q.matcher(input); m.find();) {
                spans.add(m.start() + ".." + m.end());
            }
            for (Matcher m = other.matcher(input); m.find();) {
                otherSpans.add(m.start() + ".." + m.end());
            }
            assertThat(spans).as("exhaustive find /%s/ on %s tier %s vs other tier", pattern, escaped(input), tier(f))
                .isEqualTo(otherSpans);
            assertThat(spans).as("exhaustive find /%s/ on %s tier %s vs oracle", pattern, escaped(input), tier(f))
                .isEqualTo(oracleSpans);
        }
    }

    private static String oraclePattern(String pattern) {
        return pattern.startsWith("(?u)") ? pattern.substring(4) : pattern;
    }

    private static int oracleFlags(String pattern) {
        return pattern.startsWith("(?u)") ? java.util.regex.Pattern.UNICODE_CHARACTER_CLASS : 0;
    }

    /** The fuzzer's probe protocol in miniature: F (span) + M + L. */
    private static String probe(Pattern p, String input) {
        Matcher m = p.matcher(input);
        String f = m.find() ? m.start() + ".." + m.end() : "false";
        return "F=" + f + " M=" + p.matcher(input).matches() + " L=" + p.matcher(input).lookingAt();
    }

    private static String probeJur(String pattern, int flags, String input) {
        java.util.regex.Pattern jp = java.util.regex.Pattern.compile(pattern, flags);
        java.util.regex.Matcher m = jp.matcher(input);
        String f = m.find() ? m.start() + ".." + m.end() : "false";
        return "F=" + f + " M=" + jp.matcher(input).matches() + " L=" + jp.matcher(input).lookingAt();
    }

    private static String tier(RegexEngineFactory f) {
        return f == null ? "asm" : "vm";
    }

    private static String escaped(String s) {
        return s.replace("\r", "\\r").replace("\n", "\\n");
    }
}
