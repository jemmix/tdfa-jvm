package io.github.jemmix.tdfa.parity;

import io.github.jemmix.tdfa.core.RegexEngineFactory;
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
 *       fork patch 0003 declines the asymmetric mappings, so the letters
 *       are fold-inert there — shape-consistent and hang-free, but not
 *       modern. Parity tests therefore pin the family two ways:
 *       agreement with the oracle when tdfa is compiled with the
 *       {@code Re2jUnicodeProvider} bridge (folding follows the oracle's
 *       own universe by construction), and modern-orbit behavior under the
 *       default universe (engine truth, JDK parity).
 * </ul>
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
     *  patched: letters fold-inert). Agreement must hold for every
     *  pattern shape and both directions; this is what the fuzzer relies
     *  on (fold divergence = real bug, no known-divergence coat). */
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void historicCyrillicAgreesWithOracleUniverse(RegexEngineFactory factory) {
        for (String p : new String[]{"(?i)\u0442", "(?i)\u0422", "(?i)\u1C84", "(?i)\u1C85", // {Т, т, Ꚅ, ꚅ}
            "(?i)\u0432", "(?i)\u0412", "(?i)\u1C80", // {В, в, Ꚁ}
            "(?i)\u1C88", "(?i)\uA64A", "(?i)\uA64B", // {Ԫ, ԫ, Ꚉ}
            "(?i)[\u0422\u0442]", "(?i)[\u1C80]", "(?i)[^t]"}) {
            // NB: patched-oracle-only shapes: the released oracle's
            // unbounded walk hangs on the letter literals, so those
            // patterns are skipped there (letter RUNES in the PATTERN are
            // the hazard; letter inputs are safe).
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
     *  patterns fold WITHOUT the historic letters. The patched oracle's
     *  guard makes the letters fold-inert there — also not modern, and
     *  also pinned by the same shape (agreement via the bridge above). */
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
}
