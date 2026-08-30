package io.github.jemmix.tdfa.parity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The comparator's own acceptance test — retrodiction. Two legs:
 *
 * <ol>
 *   <li><b>Synthetic verdict table</b>: {@link LayeredComparator#classify}
 *       fed hand-built column quartets — the classification must be total and
 *       exact, or the tool lies about localization.</li>
 *   <li><b>Live retrodiction</b>: real historical bug families, asserted to
 *       classify correctly NOW — every fuzz-round 3–6 repro is PASS (the
 *       fixes landed), the ſ-folding repros were PARSER before 12d9921, and
 *       the lone-surrogate boundary divergence (deliberate semantics: we do
 *       codepoint boundaries, released re2j's literal-prefix fast path lands
 *       on pair interiors) is PARSER — our whole stack, sim included,
 *       self-consistent against the oracle.</li>
 * </ol>
 */
class LayeredComparatorTest {

    private static final LayeredComparator C =
            new LayeredComparator(com.google.re2j.Re2jUnicodeProvider.INSTANCE);

    @Test
    void verdictTableIsExact() {
        // r, s, v, a -> expected layer
        assertThat(LayeredComparator.classify(new String[]{"1", "1", "1", "1"})).isEqualTo(LayeredComparator.Layer.PASS);
        assertThat(LayeredComparator.classify(new String[]{"1", "1", "2", "1"})).isEqualTo(LayeredComparator.Layer.TIER);          // v != a
        assertThat(LayeredComparator.classify(new String[]{"1", "1", "2", "3"})).isEqualTo(LayeredComparator.Layer.TIER);          // v != a, both != r/s
        assertThat(LayeredComparator.classify(new String[]{"2", "2", "1", "1"})).isEqualTo(LayeredComparator.Layer.CONSTRUCTION);  // v==a != s; s==r
        assertThat(LayeredComparator.classify(new String[]{"1", "3", "1", "1"})).isEqualTo(LayeredComparator.Layer.SIM_SUSPECT);   // v==r != s
        assertThat(LayeredComparator.classify(new String[]{"2", "1", "1", "1"})).isEqualTo(LayeredComparator.Layer.PARSER);        // v==a==s != r
        assertThat(LayeredComparator.classify(new String[]{"3", "2", "1", "1"})).isEqualTo(LayeredComparator.Layer.CHAOS);         // three answers
        assertThat(LayeredComparator.classify(new String[]{"1", "2", "3", "3"})).isEqualTo(LayeredComparator.Layer.CHAOS);
    }

    @Test
    void historicalBugFamiliesArePassNow() {
        // round 3: eager φ / mask-before-ops / byMask winners
        assertThat(C.compare("((?s:\\b))?", "\udc00\ud800\r").layer()).isEqualTo(LayeredComparator.Layer.PASS);
        assertThat(C.compare("(\\b)?", "x").layer()).isEqualTo(LayeredComparator.Layer.PASS);
        // round 4: context split / anchor flavors / pike-cut / dead markers
        assertThat(C.compare("[^0x]+\\b\\W", "abz c").layer()).isEqualTo(LayeredComparator.Layer.PASS);
        assertThat(C.compare("\\D+?\\s*\\B", "a\u00df#").layer()).isEqualTo(LayeredComparator.Layer.PASS);
        assertThat(C.compare("\\D(?m:\\S.$)", "ab\ncd").layer()).isEqualTo(LayeredComparator.Layer.PASS);
        assertThat(C.compare(".+\\b.", "\u03a99\ud800\udfff").layer()).isEqualTo(LayeredComparator.Layer.PASS);
        // round 5: subsumption cut + folding (ſ was PARSER before 12d9921)
        assertThat(C.compare("(?:.*?9{0,}\\b){1,}", "99x").layer()).isEqualTo(LayeredComparator.Layer.PASS);
        assertThat(C.compare("(?:^|$)+$", "a").layer()).isEqualTo(LayeredComparator.Layer.PASS);
        assertThat(C.compare("(?i)s", "\u017f").layer()).isEqualTo(LayeredComparator.Layer.PASS);
        assertThat(C.compare("(?i)\\w+", "a\u017fb").layer()).isEqualTo(LayeredComparator.Layer.PASS);
        // round 6: word-flag trim
        assertThat(C.compare("(\\B)*\\z", "!").layer()).isEqualTo(LayeredComparator.Layer.PASS);
    }

    @Test
    void loneSurrogateBoundariesClassifyAsParser() {
        // deliberate semantic divergence: re2j's literal-prefix fast path
        // matches a lone-low pattern into pair interiors; our NFA (and thus
        // sim, vm, asm in agreement) keeps codepoint boundaries.
        String loneLow = String.valueOf((char) 0xDC21);
        LayeredComparator.Report r = C.compare("(" + loneLow + ")", "a\ud800\udc21zz");
        assertThat(r.layer()).isEqualTo(LayeredComparator.Layer.PARSER);
        assertThat(r.sim()).isEqualTo(r.vm());
        assertThat(r.vm()).isEqualTo(r.asm());
    }

    @Test
    void compileRejectionsNormalize() {
        // three different exception classes, one verdict
        LayeredComparator.Report r = C.compare("(?P<n", "x");
        assertThat(r.re2j()).isEqualTo("<reject>");
        assertThat(r.sim()).isEqualTo("<reject>");
        assertThat(r.vm()).isEqualTo("<reject>");
        assertThat(r.layer()).isEqualTo(LayeredComparator.Layer.PASS);
    }

    @Test
    void loneSurrogateNeedleAdjacencyIsPassAfterFix() {
        // fuzz v3 first-soak finding (2026-08-30): the literal needle built
        // from two LONE surrogate symbols re-encoded as adjacent units matched
        // a well-formed input pair (CONSTRUCTION: vm+asm yes, sim/re2j no).
        // detectLiteralNeedle now declines such needles; all four agree.
        // This also pins the sim's own fix: find() on lone-high input used to
        // throw StringIndexOutOfBoundsException (charAt(len) in the interior
        // skip), which poisoned the sim column for the whole class of inputs.
        String pair = "\ud800\udfff";                     // U+103FF
        String loneHighInput = String.valueOf(new char[]{'\ud83f'});
        assertThat(C.compare("(?i:\ud800)\udfff", pair).layer()).isEqualTo(LayeredComparator.Layer.PASS);
        assertThat(C.compare("(?i:\ud800)", loneHighInput).layer()).isEqualTo(LayeredComparator.Layer.PASS);
    }
}
