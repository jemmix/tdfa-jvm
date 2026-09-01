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
}
