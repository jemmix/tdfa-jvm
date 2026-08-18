package io.github.jemmix.tdfa.parity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import io.github.jemmix.tdfa.core.Matcher;
import io.github.jemmix.tdfa.core.PatternSyntaxException;
import io.github.jemmix.tdfa.core.RegexEngineFactory;

import static io.github.jemmix.tdfa.parity.Re2jOracle.*;

/**
 * Anchor parity: ^ $ \A \z \b \B, multiline behavior.
 */
class AnchorParityTest {

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void startAnchor(RegexEngineFactory factory) { assertSameFind("^abc", "abc", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void startAnchorNoMatch(RegexEngineFactory factory) { assertSameFind("^abc", "xabc", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void endAnchor(RegexEngineFactory factory) { assertSameFind("abc$", "abc", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void endAnchorNoMatch(RegexEngineFactory factory) { assertSameFind("abc$", "abcd", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void bothAnchors(RegexEngineFactory factory) { assertSameFind("^abc$", "abc", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void bothAnchorsNoMatch(RegexEngineFactory factory) { assertSameFind("^abc$", "abcd", factory); }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void startAnchorLiteral(RegexEngineFactory factory) { assertSameFind("\\Aabc", "abc", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void endAnchorLiteral(RegexEngineFactory factory) { assertSameFind("abc\\z", "abc", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void startAnchorInGroup(RegexEngineFactory factory) { assertSameFind("(^abc)", "abc", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void anchorUnderStar(RegexEngineFactory factory) { assertSameFind("(^)?abc", "abc", factory); }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void wordBoundary(RegexEngineFactory factory) { assertSameFind("\\bword\\b", "a word here", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void wordBoundaryStart(RegexEngineFactory factory) { assertSameFind("\\bword", "word here", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void wordBoundaryEnd(RegexEngineFactory factory) { assertSameFind("word\\b", "the word", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void wordBoundaryNoMatch(RegexEngineFactory factory) { assertSameFind("\\bword\\b", "xwordx", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void noWordBoundary(RegexEngineFactory factory) { assertSameFind("\\Bword", "xword", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void noWordBoundaryNoMatch(RegexEngineFactory factory) { assertSameFind("\\Bword", " word", factory); }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void findAllAnchored(RegexEngineFactory factory) { assertSameAllMatches("^a", "aaa", factory); }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void multilineStartAnchor(RegexEngineFactory factory) { assertSameFind("(?m)^abc", "def\nabc", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void multilineEndAnchor(RegexEngineFactory factory) { assertSameFind("(?m)abc$", "abc\ndef", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void multilineMultipleLines(RegexEngineFactory factory) { assertSameAllMatches("(?m)^.", "ab\ncd\nef", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void multilineEndAnchorNewline(RegexEngineFactory factory) { assertSameFind("(?m)abc$", "abc\ndef", factory); }

    // ---- Edge cases ----

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void dollarBeforeTrailingNewline(RegexEngineFactory factory) { assertSameFind("abc$", "abc\n", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void dollarBeforeTrailingNewlineNone(RegexEngineFactory factory) { assertSameFind("abc$", "abc", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void multilineCRLF(RegexEngineFactory factory) { assertSameFind("(?m)^abc", "def\r\nabc", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void multilineCRLFEnd(RegexEngineFactory factory) { assertSameFind("(?m)abc$", "abc\r\ndef", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void anchoredAUnaffectedByMultiline(RegexEngineFactory factory) { assertSameFind("(?m)\\Aabc", "def\nabc", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void anchoredZUnaffectedByMultiline(RegexEngineFactory factory) { assertSameFind("(?m)abc\\z", "abc\ndef", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void findAllMultilineLines(RegexEngineFactory factory) { assertSameAllMatches("(?m)^\\w+", "ab\ncd\nef", factory); }

    // ---- \A/\z positive matches under (?m) ----

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void multilineAnchoredAMatches(RegexEngineFactory factory) { assertSameFind("(?m)\\Aabc", "abc", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void multilineAnchoredZMatches(RegexEngineFactory factory) { assertSameFind("(?m)abc\\z", "abc", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void multilineAnchoredASingleLine(RegexEngineFactory factory) { assertSameFind("(?m)\\Aabc", "abc\ndef", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void multilineAnchoredZSingleLine(RegexEngineFactory factory) { assertSameFind("(?m)abc\\z", "def\nabc", factory); }

    // ---- Anchor-only patterns ----

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void emptyStringMatch(RegexEngineFactory factory) { assertSameFind("^$", "", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void absoluteAnchorsEmpty(RegexEngineFactory factory) { assertSameFind("\\A\\z", "", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void absoluteAnchorsNonEmpty(RegexEngineFactory factory) { assertSameFind("\\A\\z", "a", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void startEndAnchorOnNewline(RegexEngineFactory factory) { assertSameFind("^$", "\n", factory); }
}
