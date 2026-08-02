package io.github.jemmix.tdfa.parity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Disabled;

import static io.github.jemmix.tdfa.parity.Re2jOracle.*;

/**
 * Anchor parity: ^ $ \A \z \b \B, multiline behavior.
 */
class AnchorParityTest {

    @Test void startAnchor() { assertSameFind("^abc", "abc"); }
    @Test void startAnchorNoMatch() { assertSameFind("^abc", "xabc"); }
    @Test void endAnchor() { assertSameFind("abc$", "abc"); }
    @Test void endAnchorNoMatch() { assertSameFind("abc$", "abcd"); }
    @Test void bothAnchors() { assertSameFind("^abc$", "abc"); }
    @Test void bothAnchorsNoMatch() { assertSameFind("^abc$", "abcd"); }

    @Test void startAnchorLiteral() { assertSameFind("\\Aabc", "abc"); }
    @Test void endAnchorLiteral() { assertSameFind("abc\\z", "abc"); }
    @Test void startAnchorInGroup() { assertSameFind("(^abc)", "abc"); }
    @Test void anchorUnderStar() { assertSameFind("(^)?abc", "abc"); }

    @Test void wordBoundary() { assertSameFind("\\bword\\b", "a word here"); }
    @Test void wordBoundaryStart() { assertSameFind("\\bword", "word here"); }
    @Test void wordBoundaryEnd() { assertSameFind("word\\b", "the word"); }
    @Test void wordBoundaryNoMatch() { assertSameFind("\\bword\\b", "xwordx"); }
    @Test void noWordBoundary() { assertSameFind("\\Bword", "xword"); }
    @Test void noWordBoundaryNoMatch() { assertSameFind("\\Bword", " word"); }

    @Test void findAllAnchored() { assertSameAllMatches("^a", "aaa"); }

    @Test
    @Disabled("TDFA_MISSING: (?m) multiline mode not implemented — ^/$ match text start/end only")
    void multilineStartAnchor() {
        assertSameFind("(?m)^abc", "def\nabc");
    }
    @Test
    @Disabled("TDFA_MISSING: (?m) multiline mode not implemented")
    void multilineEndAnchor() {
        assertSameFind("(?m)abc$", "abc\ndef");
    }
    @Test
    @Disabled("TDFA_MISSING: (?m) multiline mode not implemented")
    void multilineMultipleLines() {
        assertSameAllMatches("(?m)^.", "ab\ncd\nef");
    }
    @Test
    @Disabled("TDFA_MISSING: (?m) multiline mode not implemented")
    void multilineEndAnchorNewline() {
        assertSameFind("(?m)abc$", "abc\ndef");
    }
}
