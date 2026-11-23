package io.github.jemmix.tdfa.parity;

import io.github.jemmix.tdfa.Pattern;
import io.github.jemmix.tdfa.core.compile.RegexEngineFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static io.github.jemmix.tdfa.parity.Re2jOracle.assertSameFind;
import static io.github.jemmix.tdfa.parity.Re2jOracle.re2jFind;
import static io.github.jemmix.tdfa.parity.Re2jOracle.releasedOracle;
import static io.github.jemmix.tdfa.parity.Re2jOracle.tdfaFind;
import static io.github.jemmix.tdfa.parity.Re2jOracle.tdfaFindDefaultUniverse;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Case-fold orbit parity: the two fold families where engine universes
 * historically disagreed, pinned by deterministic tests.
 *
 * <ul>
 *   <li><b>Turkic İ/ı</b> — simple case folding keeps U+0130/U+0131 out of
 *       the i-orbit (their cross mappings are Turkic-locale rules). re2j
 *       pins them inert via CASE_ORBIT self-entries; Go agrees; tdfa pins
 *       them in {@code CaseFoldTable.foldKey}. Oracle-independent.
 *   <li><b>Cyrillic historic letters U+1C80..U+1C88</b> (Unicode 9.0) —
 *       every oracle's tables predate the codepoints. tdfa's DEFAULT
 *       universe folds the full modern orbits, both directions. The oracle
 *       does not: released 1.8 folds the partner letters without the
 *       historic ones (and its unbounded walk hangs from the letters);
 *       upstream #213 (in the vendored snapshot) declines the asymmetric
 *       mappings, so the letters are fold-inert there — shape-consistent
 *       and hang-free, but not
 *       modern. Parity tests therefore pin the family two ways:
 *       agreement with the oracle when tdfa is compiled with the
 *       {@code Re2jUnicodeProvider} bridge (folding follows the oracle's
 *       own universe by construction), and modern-orbit behavior under the
 *       default universe (engine truth, JDK parity).
 *   <li><b>The pairs above re2j's {@code MAX_FOLD} (0x1044F)</b> — re2j
 *       #220 bounded the fold walk, so eight scripts' symmetric case
 *       pairs above the ceiling (534 codepoints: Adlam, Warang Citi,
 *       Old Hungarian, ...) are fold-inert in the snapshot's universe
 *       where the JDK and tdfa's default universe fold them. Same
 *       two-way pin as the historic family; pairs below the ceiling
 *       from the same post-6.0 stratum stay folded on every oracle.
 *   </ul>
 *
 * <p>Jur twins below — bare {@code (?i)} folds ASCII only on both sides,
 * so the Turkic İ/ı cells translate whole (the İ/ı residual is the
 * {@code (?iu)} full-fold family: the JDK folds İ/ı into the i-orbit
 * under {@code UNICODE_CASE} while our default universe keeps simple-fold
 * orbits — excluded per the WBS evidence-re-base residual list). The
 * historic-Cyrillic cells twin under the {@code UNICODE_CHARACTER_CLASS}
 * flag: the default (JDK-derived) universe folds the modern orbits
 * exactly like the live JDK.
 */
class FoldCaseParityTest {

    // ===== Turkic İ/ı: inert under every oracle =====

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void turkicIPairStaysOutOfTheIOrbit(RegexEngineFactory factory) {
        for (String p : new String[]{"(?i)i", "(?i)I", "(?i)\u0130", "(?i)\u0131", "(?i)[i\u0131]"}) {
            for (String in : new String[]{"i", "I", "\u0130", "\u0131", "x"}) {
                assertSameFind(p, in, factory);
            }
        }
    }

    // ===== Cyrillic historic letters: tdfa folds with the oracle (bridge), modern by default =====

    /** tdfa compiled with the bridge folds exactly like the oracle on the
     *  family — whatever the oracle does (released: stale partner orbits,
     *  the snapshot: letters fold-inert). Agreement must hold for every
     *  pattern shape and both directions; this is what the fuzzer relies
     *  on (fold divergence = real bug, no known-divergence coat). */
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void historicCyrillicAgreesWithOracleUniverse(RegexEngineFactory factory) {
        for (String p : new String[]{"(?i)\u0442", "(?i)\u0422", "(?i)\u1C84", "(?i)\u1C85", // {Т, т, Ꚅ, ꚅ}
            "(?i)\u0432", "(?i)\u0412", "(?i)\u1C80", // {В, в, Ꚁ}
            "(?i)\u1C88", "(?i)\uA64A", "(?i)\uA64B", // {Ԫ, ԫ, Ꚉ}
            "(?i)[\u0422\u0442]", "(?i)[\u1C80]", "(?i)[^t]"}) {
            // NB: shapes the released oracle cannot take: its unbounded
            // walk hangs on the letter literals, so those patterns are
            // skipped there (letter RUNES in the PATTERN are the hazard;
            // letter inputs are safe).
            if (releasedOracle() && containsHistoricLetter(p)) {
                continue;
            }
            for (String in : new String[]{"\u0442", "\u0422", "\u1C84", "\u1C85", "\u0432", "\u0412", "\u1C80",
                "\u1C88", "\uA64A", "\uA64B", "x"}) {
                assertSameFind(p, in, factory);
            }
        }
    }

    private static boolean containsHistoricLetter(String p) {
        for (int i = 0; i < p.length(); i++) {
            char c = p.charAt(i);
            if (c >= '\u1C80' && c <= '\u1C88') {
                return true;
            }
        }
        return false;
    }

    /** Engine truth, oracle-independent: under the default (JDK-derived)
     *  fold universe tdfa folds the full modern orbits, both directions —
     *  matching java.util.regex on contemporary JDKs. (Folding happens at
     *  parse time, so the engine tier is not a variable here.) */
    @Test
    void defaultUniverseFoldsModernOrbits() {
        // orbit members reach each other ...
        assertThat(tdfaFindDefaultUniverse("(?i)\u0442", "\u1C85")).as("default universe (?i)т → ꚅ (modern orbit)")
            .isNotNull();
        assertThat(tdfaFindDefaultUniverse("(?i)\u1C85", "\u0442"))
            .as("default universe (?i)ꚅ → т (modern orbit, both directions)").isNotNull();
        assertThat(tdfaFindDefaultUniverse("(?i)\u1C80", "\u0432")).as("default universe (?i)Ꚁ → в (modern orbit)")
            .isNotNull();
        // ... and self-match, as every fold universe must.
        assertThat(tdfaFindDefaultUniverse("(?i)\u1C80", "\u1C80")).as("default universe (?i)Ꚁ → Ꚁ").isNotNull();
    }

    /** Released 1.8 documents the oracle's stale side (kept as an explicit
     *  divergence pin so an oracle change fails here and gets re-triaged
     *  instead of surfacing as an unexplained fuzzer signature): partner
     *  patterns fold WITHOUT the historic letters. The upstream guard
     *  (#213) makes the letters fold-inert in the snapshot — also not
     *  modern, and also pinned by the same shape (agreement via the
     *  bridge above). */
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void oracleDoesNotFoldModernHistoricCyrillic(RegexEngineFactory factory) {
        // never compile the letter literals under (?i) on the released
        // oracle (hang); partner-side probes only.
        assertThat(re2jFind("(?i)\u0442", "\u1C85")).as("oracle (?i)т → ꚅ (post-6.0 orbits not folded)").isNull();
        // under the bridge tdfa agrees with the oracle on that cell ...
        assertThat(tdfaFind("(?i)\u0442", "\u1C85", factory)).as("bridge universe tdfa (?i)т → ꚅ agrees with oracle")
            .isNull();
        // ... while the default universe folds modern (see above) — the
        // documented, deliberate gap between the two lanes.
    }

    // ===== above MAX_FOLD: fold-inert in the snapshot, modern by default =====

    /**
     * The MAX_FOLD family (re2j #220): the snapshot's fold walk stops at
     * {@code 0x1044F}, so the symmetric case pairs above it are
     * fold-inert in the oracle's universe. The released build folded
     * them (its fallback walk was unbounded), so the oracle's side is
     * probed. A pair from the same post-6.0 stratum BELOW the ceiling
     * (U+A7CC/U+A7CD, Unicode 14.0) stays folded on every oracle: the
     * bound is a ceiling, not a Unicode-version cut.
     */
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void maxFoldFamily(RegexEngineFactory factory) {
        String adlamCap = "\uD83A\uDD00", adlamLow = "\uD83A\uDD22"; // U+1E900 / U+1E922
        int[] oracle = re2jFind("(?i)" + adlamCap, adlamLow);
        assertThat(oracle != null).as("oracle (?i)\uD83A\uDD00 → \uD83A\uDD22").isEqualTo(releasedOracle());
        // under the bridge tdfa answers with the oracle, folded or inert ...
        assertThat(tdfaFind("(?i)" + adlamCap, adlamLow, factory)).isEqualTo(oracle);
        assertThat(tdfaFind("(?i)" + adlamLow, adlamCap, factory)).isEqualTo(re2jFind("(?i)" + adlamLow, adlamCap));
        assertSameFind("(?i)" + adlamCap, adlamCap, factory); // the self-match holds everywhere
        // ... while the default universe folds modern — the same
        // deliberate gap as the historic-Cyrillic family
        assertThat(tdfaFindDefaultUniverse("(?i)" + adlamCap, adlamLow))
            .as("default universe (?i)\uD83A\uDD00 → \uD83A\uDD22").isNotNull();
        assertThat(tdfaFindDefaultUniverse("(?i)" + adlamLow, adlamCap)).isNotNull();
        // below the ceiling: a post-6.0 BMP pair, folded on every oracle
        assertSameFind("(?i)\uA7CC", "\uA7CD", factory);
        assertThat(re2jFind("(?i)\uA7CC", "\uA7CD")).as("oracle (?i)\uA7CC → \uA7CD").isNotNull();
    }

    /**
     * Jur twin, Turkic İ/ı under bare {@code (?i)} (ASCII fold — both
     * sides keep İ/ı out of the i-orbit), and the historic-Cyrillic
     * modern orbits under {@code (?u)} (the JDK folds them on a
     * contemporary JDK, and so does the JDK-derived default universe).
     * The İ/ı cells under {@code (?iu)} are the known full-fold residual
     * — excluded (class javadoc).
     */
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.JurOracle#engineFactories")
    void jurTwin(RegexEngineFactory factory) {
        for (String p : new String[]{"(?i)i", "(?i)I", "(?i)\u0130", "(?i)\u0131", "(?i)[i\u0131]"}) {
            for (String in : new String[]{"i", "I", "\u0130", "\u0131", "x"}) {
                JurOracle.assertSameFind(p, in, factory);
            }
        }
        int ucc = Pattern.UNICODE_CHARACTER_CLASS;
        JurOracle.assertSameFind("(?u)\u0442", "\u1C85", ucc, factory); // modern orbit, both directions
        JurOracle.assertSameFind("(?u)\u1C85", "\u0442", ucc, factory);
        JurOracle.assertSameFind("(?u)\u1C80", "\u0432", ucc, factory);
        JurOracle.assertSameFind("(?u)\u1C80", "\u1C80", ucc, factory); // self-match holds everywhere
        // the MAX_FOLD family twins the same way: the JDK folds Adlam under
        // (?u), and so does the JDK-derived default universe
        JurOracle.assertSameFind("(?u)\uD83A\uDD00", "\uD83A\uDD22", ucc, factory);
        JurOracle.assertSameFind("(?u)\uD83A\uDD22", "\uD83A\uDD00", ucc, factory);
        // NB: no inline-(?u) divergence pin — this JDK (26) compiles
        // (?u) too, so the spelling is not a clean either/or cell; the
        // flag-bit route above is the translatable twin
    }
}
