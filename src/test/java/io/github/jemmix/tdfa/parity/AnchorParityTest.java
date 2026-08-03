package io.github.jemmix.tdfa.parity;

import org.junit.jupiter.api.Test;

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

    @Test void multilineStartAnchor() { assertSameFind("(?m)^abc", "def\nabc"); }
    @Test void multilineEndAnchor() { assertSameFind("(?m)abc$", "abc\ndef"); }
    @Test void multilineMultipleLines() { assertSameAllMatches("(?m)^.", "ab\ncd\nef"); }
    @Test void multilineEndAnchorNewline() { assertSameFind("(?m)abc$", "abc\ndef"); }

    // ---- Edge cases ----

    @Test void dollarBeforeTrailingNewline() { assertSameFind("abc$", "abc\n"); }
    @Test void dollarBeforeTrailingNewlineNone() { assertSameFind("abc$", "abc"); }
    @Test void multilineCRLF() { assertSameFind("(?m)^abc", "def\r\nabc"); }
    @Test void multilineCRLFEnd() { assertSameFind("(?m)abc$", "abc\r\ndef"); }
    @Test void anchoredAUnaffectedByMultiline() { assertSameFind("(?m)\\Aabc", "def\nabc"); }
    @Test void anchoredZUnaffectedByMultiline() { assertSameFind("(?m)abc\\z", "abc\ndef"); }
    @Test void findAllMultilineLines() { assertSameAllMatches("(?m)^\\w+", "ab\ncd\nef"); }

    // ---- \A/\z positive matches under (?m) ----

    @Test void multilineAnchoredAMatches() { assertSameFind("(?m)\\Aabc", "abc"); }
    @Test void multilineAnchoredZMatches() { assertSameFind("(?m)abc\\z", "abc"); }
    @Test void multilineAnchoredASingleLine() { assertSameFind("(?m)\\Aabc", "abc\ndef"); }
    @Test void multilineAnchoredZSingleLine() { assertSameFind("(?m)abc\\z", "def\nabc"); }

    // ---- Anchor-only patterns ----

    @Test void emptyStringMatch() { assertSameFind("^$", ""); }
    @Test void absoluteAnchorsEmpty() { assertSameFind("\\A\\z", ""); }
    @Test void absoluteAnchorsNonEmpty() { assertSameFind("\\A\\z", "a"); }
    @Test void startEndAnchorOnNewline() { assertSameFind("^$", "\n"); }
}
