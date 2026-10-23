package io.github.jemmix.tdfa.parity;

import io.github.jemmix.tdfa.core.compile.PatternMatcher;
import io.github.jemmix.tdfa.core.compile.RegexEngineFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.regex.Matcher;

import static io.github.jemmix.tdfa.parity.JurOracle.assertSameAllMatches;
import static io.github.jemmix.tdfa.parity.JurOracle.assertSameFind;
import static io.github.jemmix.tdfa.parity.JurOracle.jurFind;
import static io.github.jemmix.tdfa.parity.JurOracle.tdfaFind;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The surrogate scan discipline — java.util.regex's {@code StartS}/plain
 * {@code Start} selection, pinned hard (the retired
 * {@code SKIP_SURROGATE_UNIT_SEMANTICS} family).
 *
 * <p>java's unanchored {@code find()} enumerates candidate starts by
 * CODEPOINT ({@code StartS} — interiors of well-formed surrogate pairs are
 * never tried after the first candidate) exactly when the pattern flipped
 * {@code hasSupplementary} (any surrogate unit in the source; a
 * supplementary/lone or full-universe-folded single-char literal; any
 * complement class; a non-BMP written range — or any written range under
 * case-insensitivity; most {@code \p{...}}), and by UTF-16 unit (plain
 * {@code Start} — interiors ARE tried) otherwise; the {@code from} of a
 * {@code find(from)} is always tried as-is. Classes consume codepoints
 * either way ({@code Character.codePointAt} semantics: pairs atomic, a
 * lone unit is its own codepoint), so classes never match halves of a
 * well-formed pair but do match unpaired units; {@code \b}/{@code \B}
 * wordness is codepoint-decoded at the boundary edges (at interior
 * positions the flanking lone units read non-word). The engine's
 * {@code Alphabet#decode}/{@code #pairInterior} already walk codepoints;
 * the pair-aware scan pivot ({@code Parser#hasSupplementaryStarts} frozen
 * on the artifact, OR-ed into the runner's candidate-scan pivot beside the
 * CODEPOINT_BOUNDARIES axis) supplies the selection — every cell below
 * compares BOTH engine factories against the live oracle in this JVM.
 *
 * <p>The RE2 lane is untouched by these pins (its CODEPOINT_BOUNDARIES
 * axis already scans codepoint-aligned unconditionally — the re2j reading).
 */
class SurrogateScanParityTest {

    private static final String HI = "\uD83D";
    private static final String LO = "\uDE00";
    private static final String PAIR = HI + LO; // U+1F600
    private static final String BOLD_A = "\uD835\uDC00"; // U+1D400, supplementary Lu
    private static final String SMILE = "\uD83D\uDE42"; // U+1F642, non-word
    private static final int UCC = io.github.jemmix.tdfa.Pattern.UNICODE_CHARACTER_CLASS;

    // ---- StartS shapes: interiors never tried after the first candidate ----

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.JurOracle#engineFactories")
    void propertyAndEscapeClassesNeverMatchPairHalves(RegexEngineFactory factory) {
        assertSameFind("\\p{Cs}", PAIR, factory); // \p{} flips: no interior start
        assertSameFind("\\p{Cs}", HI + "x", factory); // lone unit matches as itself
        assertSameFind("\\P{Cs}", PAIR, factory); // pair consumed atomically
        assertSameFind("\\uDE00", PAIR, factory); // u-escape flip, low half refused
        assertSameFind("[\\uD83D]", PAIR, factory);
        assertSameFind("[\\uD800-\\uE000]", PAIR, factory); // surrogate-touching range
        assertSameFind("[\\uD800-\\uE000]", HI + "x", factory); // lone high matches
        assertSameFind("\\uD83D", PAIR + "x", factory); // literal escape refused
        assertSameFind("\\uD83D", HI + "x", factory);
        assertSameFind("\\uD83D.", PAIR + "x", factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.JurOracle#engineFactories")
    void complementsAlwaysTakeTheCodepointScan(RegexEngineFactory factory) {
        assertSameFind("[^x]", PAIR, factory); // 0..2, pair atomic
        assertSameFind("[^x]", HI + PAIR, factory); // lone unit then pair
        assertSameFind("[^x]{2}", HI + PAIR, factory);
        assertSameAllMatches("[^\\n]", SMILE, factory);
        assertSameFind("\\B[^x]", BOLD_A, UCC, factory); // interior skipped: no match
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.JurOracle#engineFactories")
    void supplementaryAndFoldedSinglesFlip(RegexEngineFactory factory) {
        assertSameFind("\\x{1F600}", PAIR, factory);
        assertSameFind("[\\x{1F600}]", PAIR, factory);
        assertSameFind("(?i)" + BOLD_A, BOLD_A, factory);
        assertSameAllMatches("\\B\\W", SMILE, UCC, factory);
    }

    // ---- plain-Start shapes: interiors ARE candidates ----

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.JurOracle#engineFactories")
    void dotStaysUnitwise(RegexEngineFactory factory) {
        // the bare dot never flips: \B fails at 0 (word pair), the interior
        // candidate wins and the lone low unit matches as one codepoint
        assertSameFind("\\B.", BOLD_A, UCC, factory); // java: 1..2
        assertSameFind("(?s)\\B.", BOLD_A, UCC, factory); // DOTALL dot neither
        assertSameFind("\\b.", "a" + PAIR, factory);
        assertSameFind("\\b.", BOLD_A, UCC, factory);
        assertSameFind(".\\b", BOLD_A, UCC, factory);
        assertSameFind(".", PAIR, factory);
        assertSameFind(".", HI, factory); // lone unit
        assertSameFind(".", HI + "x" + PAIR, factory);
        assertSameFind("..", PAIR + "x", factory);
        assertSameFind("...", PAIR + "x", factory); // two codepoints only: no match
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.JurOracle#engineFactories")
    void bmpFamiliesKeepUnitCandidates(RegexEngineFactory factory) {
        assertSameFind("\\B\\d", BOLD_A, UCC, factory); // ASCII \d: no flip
        assertSameFind("\\B\\s", SMILE, factory);
    }

    // ---- find(from) tries its own from as-is (java's StartS does too) ----

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.JurOracle#engineFactories")
    void findFromInteriorIsTriedAsIs(RegexEngineFactory factory) {
        // [\uDE00] flips StartS, but find(1) still probes the interior
        Matcher jdk = java.util.regex.Pattern.compile("[\\uDE00]").matcher(PAIR);
        int[] expected = jdk.find(1) ? new int[]{jdk.start(), jdk.end()} : null;
        PatternMatcher m = io.github.jemmix.tdfa.Pattern.compile("[\\uDE00]", 0, factory).matcher(PAIR);
        int[] actual = m.find(1) ? new int[]{m.start(), m.end()} : null;
        assertThat(actual).as("find(1) on a pair probes the interior exactly like java's StartS").isEqualTo(expected);
    }

    // ---- the backslash-u escape (java parity; both surfaces, one syntax) ----

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.JurOracle#engineFactories")
    void unicodeEscapeMatchesJava(RegexEngineFactory factory) {
        assertSameFind("\\u0041", "xAy", factory);
        assertSameFind("a\\uDE00b", "a\uDE00b", factory); // lone low roundtrip
        assertSameFind("\\uD83D\\uDE00", PAIR, factory); // adjacent escapes combine
        assertSameFind("[\\u0061-\\u007A]+", "ab!", factory);
        assertSameFind("\\uD83D", HI + "x", factory);
    }

    /** The backslash-u escape compiles on both surfaces (the shared parser's one
     *  syntax); the JDK accepts it too, the re2j oracle does not know it —
     *  RE2-lane-pinned by construction (no re2j twin can spell it). */
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.JurOracle#engineFactories")
    void unicodeEscapeCompilesOnBothSurfaces(RegexEngineFactory factory) {
        JurOracle.assertSameCompileSuccess("\\uD83D\\uDE00x", factory);
        io.github.jemmix.tdfa.re2j.Pattern.compile("\\uD83D\\uDE00x", 0, factory);
    }
}
