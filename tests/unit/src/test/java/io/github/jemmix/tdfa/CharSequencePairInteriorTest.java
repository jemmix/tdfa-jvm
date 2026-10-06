package io.github.jemmix.tdfa;

import io.github.jemmix.tdfa.core.dfa.TdfaRunner;
import org.junit.jupiter.api.Test;

import java.nio.CharBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Finding 1 of the 2026-10-03 fuzz campaign
 * ({@code docs/fuzz-campaigns-2026-10-03.md}): {@code runGeneric} — the
 * non-String CharSequence fallback, shared by both engine tiers — advanced
 * its restart scan without the surrogate-pair-interior skip the String
 * ladders and {@code PikeSim} apply, so matches could START inside
 * well-formed pairs (lone-low runes matching the low half; empty-capable
 * patterns matching interior unit positions; first-match shifts). Pinned
 * here:
 * <ul>
 *   <li>the minimal repro: a lone-low pattern never matches the low half
 *       of a well-formed pair under any wrapper;</li>
 *   <li>the campaign's boundary-iteration record: {@code \B} on
 *       {@code 𑰇Y𝔄@} iterates exactly as the String path and the spec
 *       do (interior {@code 4} skipped; {@code 1..1} is the documented
 *       empty-match-resume {@code find(int)} parity, not a scan start);</li>
 *   <li>protocol equality: the full find() iteration of every wrapper
 *       equals the String path's, per engine tier, across a battery of
 *       pair-heavy inputs and start-anywhere patterns;</li>
 *   <li>the explicit-{@code from} carve-out: {@code find(int)} landing
 *       mid-pair is honored as-is on wrappers exactly as on Strings —
 *       only the scan advance skips.</li>
 * </ul>
 */
class CharSequencePairInteriorTest {

    /** Lone-low minimal repro (finding 1): no match may start at the pair's second unit. */
    @Test
    void loneLowRuneNeverStartsInsidePair() {
        String input = "a\uD801\uDC21zz";
        for (boolean vm : new boolean[]{false, true}) {
            String tier = vm ? "interpreter" : "generated";
            Pattern p = compile("\uDC21", vm);
            assertThat(p.matcher(input).find()).as("{} tier: String", tier).isFalse();
            for (CharSequence w : wrappers(input)) {
                assertThat(p.matcher(w).find()).as("{} tier: {} wrapper", tier, w.getClass().getSimpleName()).isFalse();
            }
        }
    }

    /** The campaign's second manifestation: \B iteration skips the pair interior at 4, not the resume at 1. */
    @Test
    void boundaryIterationMatchesStringProtocol() {
        String input = "\uD807\uDC07Y\uD835\uDD04@"; // 𑰇(0,1) Y(2) 𝔄(3,4) @(5)
        List<String> expected = List.of("0..0", "1..1", "5..5", "6..6");
        for (boolean vm : new boolean[]{false, true}) {
            String tier = vm ? "interpreter" : "generated";
            Pattern p = compile("\\B", vm);
            assertThat(iterate(p, input)).as("{} tier: String", tier).isEqualTo(expected);
            for (CharSequence w : wrappers(input)) {
                assertThat(iterate(p, w)).as("{} tier: {} wrapper", tier, w.getClass().getSimpleName())
                    .isEqualTo(expected);
            }
        }
    }

    /**
     * Protocol equality per tier: the wrapper's whole find() scan equals
     * the String ladder's — the invariant the campaign's (a) leg asserts
     * — across pair-heavy inputs and start-anywhere shapes (lone
     * surrogates, boundary assertions, multiline anchors, empty-capable).
     */
    @Test
    void wrapperIterationEqualsStringPath() {
        String[] patterns = {"\uDC21", "\uD801", "\\B", "\\b", ".", "x*", "(?m)^", "(?m)$", "\uDC21|zz", "[^z]"};
        String[] inputs = {"a\uD801\uDC21zz", "\uD801\uDC21\uD802\uDC22", "\uDC21\uD801\uDC21", "\uD800x",
            "\uD807\uDC07Y\uD835\uDD04@", ""};
        for (String regex : patterns) {
            for (String input : inputs) {
                for (boolean vm : new boolean[]{false, true}) {
                    Pattern p = compile(regex, vm);
                    List<String> expected = iterate(p, input);
                    for (CharSequence w : wrappers(input)) {
                        assertThat(iterate(p, w)).as("{} tier: /{}/ on [{}] via {}", vm ? "interpreter" : "generated",
                            regex, escape(input), w.getClass().getSimpleName()).isEqualTo(expected);
                    }
                }
            }
        }
    }

    /** First-match parity: the scan never shifts an interior match ahead of the genuine one. */
    @Test
    void firstMatchNeverStartsAtInterior() {
        for (boolean vm : new boolean[]{false, true}) {
            String tier = vm ? "interpreter" : "generated";
            Pattern dot = compile(".", vm);
            PatternMatcher md = dot.matcher(new StringBuilder("a\uD801\uDC21zz"));
            assertThat(md.find()).as("{} tier", tier).isTrue();
            assertThat(md.start() + ".." + md.end()).as("{} tier: first match", tier).isEqualTo("0..1");
            Pattern pair = compile(".", vm);
            PatternMatcher mp = pair.matcher(new StringBuilder("\uD801\uDC21zz"));
            assertThat(mp.find()).as("{} tier", tier).isTrue();
            assertThat(mp.start() + ".." + mp.end()).as("{} tier: pair as one codepoint", tier).isEqualTo("0..2");
            Pattern tail = compile("z+", vm);
            PatternMatcher mt = tail.matcher(new StringBuilder("\uD801\uDC21zz"));
            assertThat(mt.find()).as("{} tier", tier).isTrue();
            assertThat(mt.start() + ".." + mt.end()).as("{} tier", tier).isEqualTo("2..4");
        }
    }

    /**
     * The explicit-from carve-out (finding 1's fix note): match(input, from)
     * keeps honoring a mid-pair from as-is — parity with find(int) on
     * Strings — only the scan advance skips interiors.
     */
    @Test
    void explicitFromStaysHonoredMidPair() {
        String input = "a\uD801\uDC21zz"; // pair interior at 2
        for (boolean vm : new boolean[]{false, true}) {
            String tier = vm ? "interpreter" : "generated";
            Pattern p = compile("\uDC21", vm);
            PatternMatcher str = p.matcher(input);
            assertThat(str.find(2)).as("{} tier: String find(2)", tier).isTrue();
            assertThat(str.start() + ".." + str.end()).as("{} tier: String span", tier).isEqualTo("2..3");
            for (CharSequence w : wrappers(input)) {
                PatternMatcher m = p.matcher(w);
                assertThat(m.find(2)).as("{} tier: {} find(2)", tier, w.getClass().getSimpleName()).isTrue();
                assertThat(m.start() + ".." + m.end()).as("{} tier: {} span", tier, w.getClass().getSimpleName())
                    .isEqualTo("2..3");
                assertThat(p.matcher(w).find(1)).as("{} tier: {} find(1)", tier, w.getClass().getSimpleName())
                    .isFalse();
            }
            assertThat(p.matcher(input).find(1)).as("{} tier: String find(1)", tier).isFalse();
        }
    }

    private static Pattern compile(String regex, boolean vm) {
        return Pattern.compile(regex, 0, vm ? TdfaRunner::new : null);
    }

    private static CharSequence[] wrappers(String s) {
        return new CharSequence[]{new StringBuilder(s), CharBuffer.wrap(s), new CharArraySeq(s)};
    }

    private static List<String> iterate(Pattern p, CharSequence in) {
        List<String> out = new ArrayList<>();
        PatternMatcher m = p.matcher(in);
        while (m.find()) {
            out.add(m.start() + ".." + m.end());
        }
        return out;
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c > 0x7e) {
                sb.append(String.format("\\u%04X", (int) c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** The campaign's third wrapper shape: a char[] view, neither String nor StringBuilder. */
    private static final class CharArraySeq implements CharSequence {
        private final char[] a;

        CharArraySeq(CharSequence s) {
            a = new char[s.length()];
            for (int i = 0; i < a.length; i++) {
                a[i] = s.charAt(i);
            }
        }

        @Override
        public int length() {
            return a.length;
        }

        @Override
        public char charAt(int index) {
            return a[index];
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new String(a, start, end - start);
        }

        @Override
        public String toString() {
            return new String(a);
        }
    }
}
