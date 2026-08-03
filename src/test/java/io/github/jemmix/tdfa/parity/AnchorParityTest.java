package io.github.jemmix.tdfa.parity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import io.github.jemmix.tdfa.EngineFactory;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static io.github.jemmix.tdfa.parity.Re2jOracle.*;

/**
 * Anchor parity: ^ $ \A \z \b \B, multiline behavior.
 */
class AnchorParityTest {

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void startAnchor(EngineFactory factory) { assertSameFind("^abc", "abc", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void startAnchorNoMatch(EngineFactory factory) { assertSameFind("^abc", "xabc", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void endAnchor(EngineFactory factory) { assertSameFind("abc$", "abc", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void endAnchorNoMatch(EngineFactory factory) { assertSameFind("abc$", "abcd", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void bothAnchors(EngineFactory factory) { assertSameFind("^abc$", "abc", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void bothAnchorsNoMatch(EngineFactory factory) { assertSameFind("^abc$", "abcd", factory); }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void startAnchorLiteral(EngineFactory factory) { assertSameFind("\\Aabc", "abc", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void endAnchorLiteral(EngineFactory factory) { assertSameFind("abc\\z", "abc", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void startAnchorInGroup(EngineFactory factory) { assertSameFind("(^abc)", "abc", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void anchorUnderStar(EngineFactory factory) { assertSameFind("(^)?abc", "abc", factory); }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void wordBoundary(EngineFactory factory) { assertSameFind("\\bword\\b", "a word here", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void wordBoundaryStart(EngineFactory factory) { assertSameFind("\\bword", "word here", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void wordBoundaryEnd(EngineFactory factory) { assertSameFind("word\\b", "the word", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void wordBoundaryNoMatch(EngineFactory factory) { assertSameFind("\\bword\\b", "xwordx", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void noWordBoundary(EngineFactory factory) { assertSameFind("\\Bword", "xword", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void noWordBoundaryNoMatch(EngineFactory factory) { assertSameFind("\\Bword", " word", factory); }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void findAllAnchored(EngineFactory factory) { assertSameAllMatches("^a", "aaa", factory); }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void multilineStartAnchor(EngineFactory factory) { assertSameFind("(?m)^abc", "def\nabc", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void multilineEndAnchor(EngineFactory factory) { assertSameFind("(?m)abc$", "abc\ndef", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void multilineMultipleLines(EngineFactory factory) { assertSameAllMatches("(?m)^.", "ab\ncd\nef", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void multilineEndAnchorNewline(EngineFactory factory) { assertSameFind("(?m)abc$", "abc\ndef", factory); }

    // ---- Edge cases ----

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void dollarBeforeTrailingNewline(EngineFactory factory) { assertSameFind("abc$", "abc\n", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void dollarBeforeTrailingNewlineNone(EngineFactory factory) { assertSameFind("abc$", "abc", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void multilineCRLF(EngineFactory factory) { assertSameFind("(?m)^abc", "def\r\nabc", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void multilineCRLFEnd(EngineFactory factory) { assertSameFind("(?m)abc$", "abc\r\ndef", factory); }
    @EnabledIfSystemProperty(named = "tdfa.pending", matches = "true") // PENDING: A and z must not be affected by (?m)
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void anchoredAUnaffectedByMultiline(EngineFactory factory) { assertSameFind("(?m)\\Aabc", "def\nabc", factory); }
    @EnabledIfSystemProperty(named = "tdfa.pending", matches = "true") // PENDING: A and z must not be affected by (?m)
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void anchoredZUnaffectedByMultiline(EngineFactory factory) { assertSameFind("(?m)abc\\z", "abc\ndef", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void findAllMultilineLines(EngineFactory factory) { assertSameAllMatches("(?m)^\\w+", "ab\ncd\nef", factory); }

    // ---- \A/\z positive matches under (?m) ----

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void multilineAnchoredAMatches(EngineFactory factory) { assertSameFind("(?m)\\Aabc", "abc", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void multilineAnchoredZMatches(EngineFactory factory) { assertSameFind("(?m)abc\\z", "abc", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void multilineAnchoredASingleLine(EngineFactory factory) { assertSameFind("(?m)\\Aabc", "abc\ndef", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void multilineAnchoredZSingleLine(EngineFactory factory) { assertSameFind("(?m)abc\\z", "def\nabc", factory); }

    // ---- Anchor-only patterns ----

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void emptyStringMatch(EngineFactory factory) { assertSameFind("^$", "", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void absoluteAnchorsEmpty(EngineFactory factory) { assertSameFind("\\A\\z", "", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void absoluteAnchorsNonEmpty(EngineFactory factory) { assertSameFind("\\A\\z", "a", factory); }
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void startEndAnchorOnNewline(EngineFactory factory) { assertSameFind("^$", "\n", factory); }
}
