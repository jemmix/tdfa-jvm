package io.github.jemmix.tdfa.parity;

import io.github.jemmix.tdfa.core.RegexEngineFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static io.github.jemmix.tdfa.parity.Re2jOracle.assertSameFind;

/**
 * Assertion-context conformance for zero-width anchors under optional /
 * repeated constructs — the fuzz round-10 CONSTRUCTION family (39 records,
 * 15 patterns, minimized to the cases below).
 *
 * <p>Three determinizer defects, all in how assertion contexts interact at
 * one position:
 * <ol>
 *   <li><b>Dead-marker scan order</b> — the binary-search transition scan
 *       broke the walk on ANY satisfied dead marker before reaching
 *       lower-index (more specific) satisfied LIVE entries
 *       ({@code (\b)?^[\d]} on {@code "0"} died on the dead WB-context and
 *       never saw the live WB|BEGIN context that owned the step). The dual
 *       flaw existed in the ladder scans: they SKIPPED dead markers,
 *       falling through to contexts not alive under the posFlags. Rule now:
 *       the lowest-index mask-satisfied entry owns the step, dead or live.</li>
 *   <li><b>OR of assertion-gated accepts, tagless</b> — {@code Z(?:\A|\B)}
 *       accepted at pos 1 where both arms fail: the conjunctive accept mask
 *       (intersection of config emptyMasks) collapsed the disjunction to 0
 *       = unconditional. The byMask final-ops table expresses exactly this
 *       per-posFlags aliveness but was only built when tags &gt; 0; it is now
 *       built for tagless accept kernels too (variants degenerate to empty
 *       ops — the cell sign alone is the aliveness).</li>
 *   <li><b>Literal-needle shortcut past position-dependent accepts</b> —
 *       the same {@code Z(?:\A|\B)} also matched via the indexOf fast path,
 *       which cannot evaluate posFlags at all. detectLiteralNeedle now
 *       declines any final state with a byMask row.</li>
 * </ol>
 * PikeSim (over the same Tnfa) agreed with re2j on every case — the whole
 * family was determinizer-side, which is what the layered audit said.
 */
class AnchorContextParityTest {

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void deadMarkerMustNotShadowMoreSpecificLiveContext(RegexEngineFactory factory) {
        assertSameFind("(\\b)?^[\\d]", "0", factory);
        assertSameFind("(\\A|\\B\u6f22)\u017f", "\u017f", factory);
        assertSameFind("((\\B){0,}\\A) ", " ", factory);
        assertSameFind("((?m:^))?^.", "\u00e9", factory);
        assertSameFind("(\\B)?\\A.", "\udc00", factory);
        assertSameFind("(\\B\udc21|\\A)\u3042", "\u3042", factory);
        assertSameFind("((\\B\udc00|\\A|w)).", "\ud800", factory);
        assertSameFind("(\\Bm|(?:\\A\\D))", " ", factory);
        assertSameFind("(^|\\b\u6f22)_", "_", factory);
        assertSameFind("((((\\b))))*^.", "b", factory);
        assertSameFind("(?:\\b(j)|^z)", "z", factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void disjunctiveAssertionGatedAccepts(RegexEngineFactory factory) {
        assertSameFind("Z(?:\\A|\\B)", "Z", factory);          // over-match via needle AND accept mask
        assertSameFind("(?:(?:^|\\z))\\b", "\u03a9z", factory);
        assertSameFind("(?m:\\A.|(^))\\S", "\u00e9", factory);
        assertSameFind("(?m:(\\A.|(^)))", " ", factory);       // alternation priority among zero-width arms
    }

    /**
     * Round 11 (2026-09-02): lazy quantifier + {@code \b}/{@code \B} + optional
     * tail — the walk extended past a recorded accept via a kernel config
     * ranked BELOW it (leftmost-longest window in a leftmost-first engine).
     * Fixed by pike post-match thread pruning determinized: a live set that
     * contains an ACCEPT config is truncated below the first alive accept
     * (anything those threads reach is discarded by leftmost-first); the
     * emptied contexts emit their dead markers; and overlap ownership across
     * contexts is by MOST-SPECIFIC satisfied mask (popcount, then index) at
     * every scan site — lo-sorted tables could place a broad mask-0 range
     * before the specific dead marker that must shadow it.
     */
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void lazyQuantifierWordBoundaryStopsAtFirstAccept(RegexEngineFactory factory) {
        assertSameFind(".+?\\b[^\\d]*", "\u00df9", factory);          // [0,1) not [0,2)
        assertSameFind(".{0,}?\\B\\S?", " ", factory);
        assertSameFind("\\D??(?:\\b)]?", "b", factory);
        assertSameFind("\\S+?\\bW?", "\udc00_", factory);
        assertSameFind("\\W??(\\B)[\udfff]*", "\ud800\udfff", factory);
        assertSameFind("\\D*?\\B(?:\udfff)?", "\ud800\udc21", factory);
        assertSameFind("\\s??\\B(.)?", "\\n", factory);
        assertSameFind(".*?\\B]?", "\udc02", factory);
        assertSameFind("(\\A(\udc21){0,}|.){1}", "\udca9", factory);
        assertSameFind("((\\B|.))~*", "\udc07", factory);
        assertSameFind(".+?\\B(9){0,}", "\\t~", factory);
        assertSameFind("\\S{2,}?(\\B)~{0,}", "\udc07\udc21\udfff", factory);
        assertSameFind("(.??)\\B(s)*", "\udfff", factory);
        assertSameFind("[d-\ud835\udd04]??\\B\ud800?", "\udc21", factory);
        assertSameFind("[0-\ud83d\udca9]{0,}?\\B\udc07*", "\udfff", factory);
        assertSameFind("(.{1,}?\\b]{0,})", "\udc00b", factory);
}
}
