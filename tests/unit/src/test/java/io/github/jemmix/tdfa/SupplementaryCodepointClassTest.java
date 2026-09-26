package io.github.jemmix.tdfa;

import io.github.jemmix.tdfa.core.CompiledRegex;
import io.github.jemmix.tdfa.core.MatchResult;
import io.github.jemmix.tdfa.tdfa.TdfaRunner;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Supplementary-codepoint (non-BMP) class matching — with UCD-verified inputs.
 *
 * <p>This guardian exists because of a refuted "bug candidate" (see TODO
 * post-mortem): test inputs built from the Math-Fraktur block kept "failing"
 * for reasons that were entirely the inputs' fault. The traps, recorded so
 * nobody re-trips on them:
 * <ul>
 *   <li>the Mathematical Alphanumeric block has <b>holes</b> at
 *       letterlike-symbol duplicates: 1D506/1D50B/1D50C/1D515/1D51D
 *       (Fraktur C/H/I/G/Z) are UNASSIGNED — ℭ ℌ ℑ ℊ ℨ are the canonical
 *       glyphs — so "three consecutive Fraktur codepoints" is not always
 *       three letters;</li>
 *   <li>Gothic (U+10330..) is caseless {@code Lo}, not {@code Lu};</li>
 *   <li>counted repetition against a longer input fails whole-match
 *       {@code matches()} by definition ({@code {2,4}} cannot eat 5).</li>
 * </ul>
 */
class SupplementaryCodepointClassTest {

    /** Assigned Fraktur capitals only (A, B, D — skipping the 1D506 hole). */
    private static final String FRAKTUR_ABD = cps(0x1D504, 0x1D505, 0x1D507);

    private static final String FRAKTUR_5 = cps(0x1D504, 0x1D505, 0x1D507, 0x1D508, 0x1D509);
    private static final String GOTHIC_3 = cps(0x10330, 0x10331, 0x10332);

    private static String cps(int... cps) {
        StringBuilder sb = new StringBuilder();
        for (int cp : cps) {
            sb.appendCodePoint(cp);
        }
        return sb.toString();
    }

    /** Whole-input probe on the find surface: a full-span match from 0
     *  (CompiledRegex carries no whole-match entry). */
    private static boolean whole(CompiledRegex r, CharSequence input) {
        MatchResult m = r.match(input, 0);
        return m != null && m.start(0) == 0 && m.end(0) == input.length();
    }

    @Test
    void distinctAssignedSupplementaryLuMatches() {
        for (String re : new String[]{"\\p{Lu}{1}", "\\p{Lu}{2}", "\\p{Lu}{3}", "\\p{Lu}+", "\\p{L}{3}",
            "[\\x{1D504}\\x{1D505}\\x{1D507}]{3}"}) {
            CompiledRegex r = CompiledRegex.compile(re);
            assertThat(whole(r, re.endsWith("{1}") || re.endsWith("{2}")
                ? FRAKTUR_ABD.substring(0, re.endsWith("{1}") ? 2 : 4) : FRAKTUR_ABD))
                .as("%s on assigned Fraktur capitals", re).isTrue();
        }
        assertThat(whole(CompiledRegex.compile("\\p{Lu}{5}"), FRAKTUR_5)).isTrue();
    }

    @Test
    void rangedAndLazyQuantifiersOnSupplementary() {
        assertThat(whole(CompiledRegex.compile("\\p{Lu}{2,4}"), cps(0x1D504, 0x1D505, 0x1D507, 0x1D508))).isTrue(); // exactly 4
        assertThat(whole(CompiledRegex.compile("\\p{Lu}{2,4}"), FRAKTUR_5)).isFalse(); // 5 > max
        assertThat(whole(CompiledRegex.compile("\\p{Lu}{2,4}?"), cps(0x1D504, 0x1D505))).isTrue();
    }

    @Test
    void unassignedFrakturHoleCodepointsDoNotMatchL() {
        // 1D506 (Fraktur C slot) is unassigned — ℭ U+212D is canonical.
        // NB: inputs built via cps() — writing supplementary codepoints as
        // escaped surrogate literals in source is a trap: JLS §3.3 unicode
        // escape preprocessing fires on backslash-u-XXXX in raw source even
        // when it looks escaped inside a string (the escape starts at the
        // second backslash), mangling the literal into ASCII text. The same
        // applies to comments — this very note cannot spell the sequence.
        assertThat(whole(CompiledRegex.compile("\\p{Lu}{3}"), cps(0x1D504, 0x1D505, 0x1D506))).isFalse();
        assertThat(CompiledRegex.compile("\\p{L}").find(cps(0x1D506))).isFalse();
        // ...while the canonical letterlike symbol is a letter
        assertThat(CompiledRegex.compile("\\p{Lu}").find(cps(0x212D))).isTrue();
    }

    @Test
    void gothicIsCaselessLoNotLu() {
        assertThat(whole(CompiledRegex.compile("\\p{Lo}{3}"), GOTHIC_3)).isTrue();
        assertThat(whole(CompiledRegex.compile("\\p{L}{3}"), GOTHIC_3)).isTrue();
        assertThat(whole(CompiledRegex.compile("\\p{Lu}{3}"), GOTHIC_3)).isFalse();
    }

    @Test
    void extractIndicesAreUtf16OnSupplementary() {
        CompiledRegex r = CompiledRegex.compile("\\p{Lu}{2}");
        MatchResult m = r.match(cps(0x1D504, 0x1D507), 0);
        assertThat(m).isNotNull();
        assertThat(m.start(0)).isEqualTo(0);
        assertThat(m.end(0)).isEqualTo(4); // 2 codepoints = 4 UTF-16 units
    }

    /**
     * Lone-symbol adjacency in the literal needle: a pattern of two LONE
     * surrogate symbols — high then low, kept apart by syntax so the
     * parser's pattern-decode does not coalesce them — re-encodes into the
     * same UTF-16 unit text as the pair codepoint they are not. A unit-wise
     * literal-needle scan would then match a well-formed input pair against
     * what the alphabet defines as two lone codepoints (e.g. the pattern
     * below matching the whole pair 0..2 where re2j, the JDK, and PikeSim
     * over our own Tnfa all report no match). detectLiteralNeedle therefore
     * declines needles containing adjacent high+low units; the DFA walk
     * (which decodes) handles the shape correctly.
     */
    @Test
    void loneSurrogateNeedleAdjacencyDoesNotMatchPairs() {
        String loneHighThenLow = cps(0xD800, 0xDFFF); // two LONE symbols
        String pair = cps(0x103FF); // ONE codepoint, same UTF-16 units
        String pat = "(?i:" + loneHighThenLow.charAt(0) + ")" + loneHighThenLow.charAt(1);
        assertThat(pat).isEqualTo("(?i:\uD800)\uDFFF"); // groups keep the symbols apart
        // ASM tier (facade default — exercises detectLiteralNeedle) and the
        // interpreter tier (the walk) must both decline to match the pair.
        assertThat(Pattern.compile(pat).matcher(pair).find()).isFalse();
        Pattern interp = Pattern.compile(pat, 0, TdfaRunner::new);
        assertThat(interp.matcher(pair).find()).isFalse();
        // The shape [lone high][lone low] is UNSATISFIABLE by the alphabet
        // contract: adjacent high+low units always decode as a pair, a lone
        // high is always followed by a non-low, a lone low always preceded by
        // a non-high. That unsatisfiability is what made the needle bug
        // subtle — the needle's unit text was matchable where the pattern
        // never could be. With separation the pattern IS satisfiable:
        String sep = "(?i:\uD800)q\uDFFF"; // lone high, 'q', lone low
        String trueLone = "x\uD800q\uDFFFy";
        assertThat(Pattern.compile(sep).matcher(trueLone).find()).isTrue();
        // and the equivalent real pair pattern still matches the pair (both tiers)
        assertThat(Pattern.compile(String.valueOf(cps(0x103FF))).matcher(pair).find()).isTrue();
    }
}
