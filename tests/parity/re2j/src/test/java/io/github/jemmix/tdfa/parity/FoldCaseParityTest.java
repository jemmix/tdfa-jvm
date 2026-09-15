package io.github.jemmix.tdfa.parity;

import io.github.jemmix.tdfa.core.RegexEngineFactory;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static io.github.jemmix.tdfa.parity.Re2jOracle.assertSameFind;
import static io.github.jemmix.tdfa.parity.Re2jOracle.re2jFind;
import static io.github.jemmix.tdfa.parity.Re2jOracle.releasedOracle;
import static io.github.jemmix.tdfa.parity.Re2jOracle.foldsHistoricCyrillic;
import static io.github.jemmix.tdfa.parity.Re2jOracle.tdfaFind;
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
 *       tdfa folds the full modern orbits, both directions. Released re2j
 *       1.8's tables predate the codepoints (stale folding from the
 *       partner side, nonterminating fold walk from the letter itself);
 *       fork patch 0004 bounds the walks and 0005 overlays the complete
 *       orbits, making the patched oracle bit-identical to tdfa's fold
 *       universe (exhaustive orbit diff: 0 delta over all codepoints).
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

    // ===== Cyrillic historic letters: full-orbit parity under the patched oracle =====

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void historicCyrillicFoldsBothDirections(RegexEngineFactory factory) {
        Assumptions.assumeTrue(foldsHistoricCyrillic(),
                "oracle folds the post-6.0 orbits (fork fix5+); released 1.8 predates them");
        for (String p : new String[]{
                "(?i)\u0442", "(?i)\u0422", "(?i)\u1C84", "(?i)\u1C85",          // {Т, т, Ꚅ, ꚅ}
                "(?i)\u0432", "(?i)\u0412", "(?i)\u1C80",                        // {В, в, Ꚁ}
                "(?i)\u1C88", "(?i)\uA64A", "(?i)\uA64B",                        // {Ԫ, ԫ, Ꚉ}
                "(?i)[\u0422\u0442]", "(?i)[^t]"}) {
            for (String in : new String[]{
                    "\u0442", "\u0422", "\u1C84", "\u1C85",
                    "\u0432", "\u0412", "\u1C80",
                    "\u1C88", "\uA64A", "\uA64B", "x"}) {
                assertSameFind(p, in, factory);
            }
        }
    }

    /** Released 1.8 documents the stale side: partner patterns fold
     *  WITHOUT the historic letters. Cemented so a change on either side
     *  (oracle upgrade, fork default flip) fails here and gets re-triaged
     *  instead of surfacing as an unexplained fuzzer signature. */
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void releasedOracleFoldsHistoricCyrillicStale(RegexEngineFactory factory) {
        Assumptions.assumeTrue(releasedOracle(),
                "released re2j 1.8 folds U+1C80..U+1C88 stale (tables predate them)");
        // tdfa folds the modern orbit...
        assertThat(tdfaFind("(?i)\u0442", "\u1C85", factory))
                .as("tdfa (?i)т → ꚅ (modern orbit)").isNotNull();
        // ...released re2j does not
        assertThat(re2jFind("(?i)\u0442", "\u1C85"))
                .as("released re2j (?i)т → ꚅ (stale 6.0 orbit)").isNull();
    }
}
