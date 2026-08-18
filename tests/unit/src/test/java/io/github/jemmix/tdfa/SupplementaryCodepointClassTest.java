package io.github.jemmix.tdfa;

import io.github.jemmix.tdfa.core.CompiledRegex;
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

    private static String cps(int... cps) {
        StringBuilder sb = new StringBuilder();
        for (int cp : cps) sb.appendCodePoint(cp);
        return sb.toString();
    }

    /** Assigned Fraktur capitals only (A, B, D — skipping the 1D506 hole). */
    private static final String FRAKTUR_ABD = cps(0x1D504, 0x1D505, 0x1D507);
    private static final String FRAKTUR_5 = cps(0x1D504, 0x1D505, 0x1D507, 0x1D508, 0x1D509);
    private static final String GOTHIC_3 = cps(0x10330, 0x10331, 0x10332);

    @Test void distinctAssignedSupplementaryLuMatches() {
        for (String re : new String[]{"\\p{Lu}{1}", "\\p{Lu}{2}", "\\p{Lu}{3}",
                "\\p{Lu}+", "\\p{L}{3}", "[\\x{1D504}\\x{1D505}\\x{1D507}]{3}"}) {
            CompiledRegex r = CompiledRegex.compile(re);
            assertThat(r.matches(re.endsWith("{1}") || re.endsWith("{2}")
                    ? FRAKTUR_ABD.substring(0, re.endsWith("{1}") ? 2 : 4) : FRAKTUR_ABD))
                    .as("%s on assigned Fraktur capitals", re).isTrue();
        }
        assertThat(CompiledRegex.compile("\\p{Lu}{5}").matches(FRAKTUR_5)).isTrue();
    }

    @Test void rangedAndLazyQuantifiersOnSupplementary() {
        assertThat(CompiledRegex.compile("\\p{Lu}{2,4}")
                .matches(cps(0x1D504, 0x1D505, 0x1D507, 0x1D508))).isTrue();  // exactly 4
        assertThat(CompiledRegex.compile("\\p{Lu}{2,4}").matches(FRAKTUR_5)).isFalse(); // 5 > max
        assertThat(CompiledRegex.compile("\\p{Lu}{2,4}?").matches(cps(0x1D504, 0x1D505))).isTrue();
    }

    @Test void unassignedFrakturHoleCodepointsDoNotMatchL() {
        // 1D506 (Fraktur C slot) is unassigned — ℭ U+212D is canonical.
        // NB: inputs built via cps() — writing supplementary codepoints as
        // escaped surrogate literals in source is a trap: JLS §3.3 unicode
        // escape preprocessing fires on backslash-u-XXXX in raw source even
        // when it looks escaped inside a string (the escape starts at the
        // second backslash), mangling the literal into ASCII text. The same
        // applies to comments — this very note cannot spell the sequence.
        assertThat(CompiledRegex.compile("\\p{Lu}{3}").matches(cps(0x1D504, 0x1D505, 0x1D506))).isFalse();
        assertThat(CompiledRegex.compile("\\p{L}").find(cps(0x1D506))).isFalse();
        // ...while the canonical letterlike symbol is a letter
        assertThat(CompiledRegex.compile("\\p{Lu}").find(cps(0x212D))).isTrue();
    }

    @Test void gothicIsCaselessLoNotLu() {
        assertThat(CompiledRegex.compile("\\p{Lo}{3}").matches(GOTHIC_3)).isTrue();
        assertThat(CompiledRegex.compile("\\p{L}{3}").matches(GOTHIC_3)).isTrue();
        assertThat(CompiledRegex.compile("\\p{Lu}{3}").matches(GOTHIC_3)).isFalse();
    }

    @Test void extractIndicesAreUtf16OnSupplementary() {
        CompiledRegex r = CompiledRegex.compile("\\p{Lu}{2}");
        io.github.jemmix.tdfa.core.MatchResult m = r.match(cps(0x1D504, 0x1D507), 0);
        assertThat(m).isNotNull();
        assertThat(m.start(0)).isEqualTo(0);
        assertThat(m.end(0)).isEqualTo(4);   // 2 codepoints = 4 UTF-16 units
    }
}
