package io.github.jemmix.tdfa.parity;

import io.github.jemmix.tdfa.core.RegexEngineFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static io.github.jemmix.tdfa.parity.Re2jOracle.assertSameFind;

/**
 * Assertion-context conformance for zero-width anchors under optional /
 * repeated constructs, minimized to the cases below.
 *
 * <p>Three determinizer defects, all in how assertion contexts interact at
 * one position:
 * <ol>
 *   <li><b>Dead-marker scan order</b> — the lowest-index mask-satisfied
 *       entry owns the step, dead or live. Breaking the walk on ANY
 *       satisfied dead marker before reaching lower-index (more specific)
 *       satisfied LIVE entries mis-walks ({@code (\b)?^[\d]} on
 *       {@code "0"} would die on the dead WB-context and never see the
 *       live WB|BEGIN context that owns the step); dually, SKIPPING dead
 *       markers — as the ladder scans must not — falls through to
 *       contexts not alive under the posFlags.</li>
 *   <li><b>OR of assertion-gated accepts, tagless</b> — a conjunctive
 *       accept mask (intersection of config emptyMasks) collapses a
 *       disjunction like {@code Z(?:\A|\B)} to 0 = unconditional, accepting
 *       at pos 1 where both arms fail. The byMask final-ops table expresses
 *       exactly this per-posFlags aliveness and must be built for tagless
 *       accept kernels too (variants degenerate to empty ops — the cell
 *       sign alone is the aliveness).</li>
 *   <li><b>Literal-needle shortcut past position-dependent accepts</b> —
 *       {@code Z(?:\A|\B)}-shaped patterns can also match via the indexOf
 *       fast path, which cannot evaluate posFlags at all.
 *       detectLiteralNeedle declines any final state with a byMask row.</li>
 * </ol>
 * PikeSim (over the same Tnfa) agrees with re2j on every case here — these
 * pins are determinizer-side; a PikeSim disagreement would relocate the bug
 * to the Tnfa/parser.
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
        assertSameFind("Z(?:\\A|\\B)", "Z", factory); // over-match via needle AND accept mask
        assertSameFind("(?:(?:^|\\z))\\b", "\u03a9z", factory);
        assertSameFind("(?m:\\A.|(^))\\S", "\u00e9", factory);
        assertSameFind("(?m:(\\A.|(^)))", " ", factory); // alternation priority among zero-width arms
    }

    /**
     * Lazy quantifier + {@code \b}/{@code \B} + optional tail — the walk
     * must not extend past a recorded accept via a kernel config ranked
     * BELOW it (a leftmost-longest window in a leftmost-first engine).
     * Pike post-match thread pruning, determinized: a live set that
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
        assertSameFind(".+?\\b[^\\d]*", "\u00df9", factory); // [0,1) not [0,2)
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
