package io.github.jemmix.tdfa.parity;

import io.github.jemmix.tdfa.core.compile.PatternMatcher;
import io.github.jemmix.tdfa.core.compile.RegexEngineFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.regex.Matcher;

import static io.github.jemmix.tdfa.parity.JurOracle.engineFactories;
import static io.github.jemmix.tdfa.parity.JurOracle.jdkFlags;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The nightly-fuzz 2026-10-30 fixings (#127) — two engine bugs the soak's
 * JUR lane caught, both live-oracle-pinned here:
 *
 * <ul>
 * <li><b>The scoped {@code (?U:...)} shorthand fold</b> (the 137-record
 *     family): {@code java.util.regex} NEVER case-folds a predefined
 *     shorthand — {@code \w}/{@code \W} keep their plain tables under
 *     {@code CASE_INSENSITIVE}, {@code UNICODE_CASE},
 *     {@code UNICODE_CHARACTER_CLASS} and every scoped spelling of either
 *     ({@code (?U:\w)}/{@code (?u:\w)}), in and out of classes. The
 *     engine folded them (re2j's reading — {@code (?i)\w} matches
 *     {@code ſ} via the s-orbit of {@code a-z}), so a scoped
 *     {@code (?U:...)} widened the fold universe and the ASCII
 *     {@code \w} started matching the orbit members İ/ı/ſ that the JDK's
 *     plain table refuses. Fixed as the {@code SHORTHAND_FOLD} axis:
 *     never on the JUR lane (pinned here against the live oracle), kept
 *     on the re2j surface (pinned in {@code CaseInsensitiveTest}).</li>
 * <li><b>The literal-run flush at group-open</b> (the
 *     {@code \B(v(x(r))){0,4}} family): java's {@code hasSupplementary}
 *     (the {@code StartS}/plain-{@code Start} scan selection — see
 *     {@code SurrogateScanParityTest}) ends a literal run at ANY
 *     non-literal, a {@code '('} included: in {@code a(a)} both
 *     {@code a}s are single-char atoms (each flips the flag under a
 *     full-universe fold, java's {@code SingleU}), never one two-char
 *     {@code Slice} (which never flips). The engine's run detector
 *     missed the flush at group-open, so {@code (?i)(?u)\B(a(a))*}
 *     scanned UTF-16-unit candidates and matched INSIDE a supplementary
 *     letter where the oracle's {@code StartS} never looks.</li>
 * </ul>
 *
 * <p>The engine's u/U letters are crossed vs the JDK (engine
 * {@code (?U:...)} is scoped UNICODE_CASE = java {@code (?u:...)},
 * engine {@code (?u)} is UCC = java {@code (?U)}), so every case here
 * spells both sides: the lane pattern through the jur facade, the
 * oracle pattern in java's own letters (the fuzzer's
 * {@code jurOraclePattern} translation, hand-spelled per case).
 */
class ShorthandFoldParityTest {

    private static final String LONG_S = "\u017F";

    private static final String DOTLESS_I = "\u0131";

    private static final String DOTTED_I = "\u0130";

    private static final String KELVIN = "\u212A";

    private static final String BOLD_A = "\uD835\uDC00"; // U+1D400, supplementary Lu

    /** The fold-orbit battery: every member the plain tables refuse. */
    private static final String[] ORBIT_INPUTS =
        {"s", "S", LONG_S, "k", "K", KELVIN, "i", "I", DOTLESS_I, DOTTED_I, "a", "5", "_", "!"};

    // ===== scoped (?U:...) = the JDK's (?u:...): no shorthand ever folds =====

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.JurOracle#engineFactories")
    void scopedUnicodeCaseKeepsShorthandPlain(RegexEngineFactory factory) {
        int ci = io.github.jemmix.tdfa.Pattern.CASE_INSENSITIVE;
        for (String in : ORBIT_INPUTS) {
            assertDualFind("(?i)(?U:\\w)", "(?u:\\w)", ci, in, factory);
            assertDualFind("(?i)(?U:\\W)", "(?u:\\W)", ci, in, factory);
            assertDualFind("(?U:(?i)\\w)", "(?u:(?i)\\w)", 0, in, factory);
            assertDualFind("(?i)(?U:\\w)x", "(?u:\\w)x", ci, in + "x", factory);
        }
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.JurOracle#engineFactories")
    void scopedUnicodeCaseKeepsInClassShorthandPlain(RegexEngineFactory factory) {
        int ci = io.github.jemmix.tdfa.Pattern.CASE_INSENSITIVE;
        for (String in : ORBIT_INPUTS) {
            assertDualFind("(?i)(?U:[\\w])", "(?u:[\\w])", ci, in, factory);
            assertDualFind("(?i)(?U:[\\W])", "(?u:[\\W])", ci, in, factory);
            assertDualFind("(?i)(?U:[a\\w])", "(?u:[a\\w])", ci, in, factory);
            // a written range in the SAME class still folds both sides
            assertDualFind("(?i)(?U:[a-z\\w])", "(?u:[a-z\\w])", ci, in, factory);
        }
    }

    // ===== the flags form: UCC widens \w itself but still never folds it =====

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.JurOracle#engineFactories")
    void uccWidensTheShorthandButNeverFoldsIt(RegexEngineFactory factory) {
        int ci = io.github.jemmix.tdfa.Pattern.CASE_INSENSITIVE;
        int ucc = io.github.jemmix.tdfa.Pattern.UNICODE_CHARACTER_CLASS;
        for (String in : ORBIT_INPUTS) {
            assertDualFind("(?u)(?i)\\w", "\\w", ci | ucc, in, factory);
            assertDualFind("(?u)(?i)\\W", "\\W", ci | ucc, in, factory);
            assertDualFind("(?u)(?i)[\\w]", "[\\w]", ci | ucc, in, factory);
            assertDualFind("(?u)(?i)[\\W]", "[\\W]", ci | ucc, in, factory);
            assertDualFind("(?u)(?i)[a\\w]", "[a\\w]", ci | ucc, in, factory);
        }
    }

    /** The scoped UCC spelling (engine {@code (?u:...)} = java
     *  {@code (?U:...)}): the wide table in scope, no fold either way. */
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.JurOracle#engineFactories")
    void scopedUccKeepsTheShorthandUnfolded(RegexEngineFactory factory) {
        for (String in : ORBIT_INPUTS) {
            assertDualFind("(?i)(?u:\\w)", "(?i)(?U:\\w)", 0, in, factory);
            assertDualFind("(?i)(?u:\\W)", "(?i)(?U:\\W)", 0, in, factory);
        }
    }

    /** Control: bare CI (the ASCII universe) agreed before the fix and
     *  must keep agreeing — the fold is a no-op on the ASCII orbits. */
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.JurOracle#engineFactories")
    void bareCiShorthandsAgree(RegexEngineFactory factory) {
        int ci = io.github.jemmix.tdfa.Pattern.CASE_INSENSITIVE;
        for (String in : ORBIT_INPUTS) {
            JurOracle.assertSameFind("\\w", in, ci, factory);
            JurOracle.assertSameFind("\\W", in, ci, factory);
            JurOracle.assertSameFind("[\\w]", in, ci, factory);
        }
    }

    // ===== the literal-run flush at group-open (the StartS selection) =====

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.JurOracle#engineFactories")
    void foldedLiteralRunsFlushAtGroupOpen(RegexEngineFactory factory) {
        // the CI|UCC folded 'a' singles flip hasSupplementary both sides:
        // StartS scans codepoint-aligned, the \B gate never holds on a
        // supplementary letter (word|low-half and word|end are boundaries,
        // the interior is never probed) — no match anywhere
        assertDualFind("(?i)(?u)\\B(a(a))*", "(?i)(?U)\\B(a(a))*", 0, BOLD_A, factory);
        assertDualFind("(?i)(?u)\\B(a)*", "(?i)(?U)\\B(a)*", 0, BOLD_A, factory);
        assertDualFind("(?i)(?u)a(a)", "(?i)(?U)a(a)", 0, BOLD_A + "a(a)", factory);
        assertDualFind("(?i)(?u)a", "(?i)(?U)a", 0, BOLD_A + "a", factory);
        // the ASCII universe keeps the plain Start (no flip, no fold):
        // unit scan, interior candidates — same spelling both sides
        JurOracle.assertSameFind("(?i)\\B(a(a))*", BOLD_A, factory);
    }

    // ===== the dual-spelling compare (the crossed u/U letters) =====

    /** Lane pattern through the jur facade vs the oracle pattern in java's
     *  own letters, {@link JurOracle#jdkFlags}-translated flags, full span
     *  arrays — {@code JurOracle.assertSameFind} for the crossed spellings. */
    private static void assertDualFind(String lanePattern, String oraclePattern, int flags, String input,
        RegexEngineFactory factory) {
        int[] expected = jurFind(oraclePattern, input, flags);
        int[] actual = tdfaFind(lanePattern, input, flags, factory);
        assertThat(actual).as("jur lane=\"%s\" oracle=\"%s\" input=\"%s\" flags=0x%x [%s]", lanePattern, oraclePattern,
            input, flags, factory).isEqualTo(expected);
    }

    private static int[] jurFind(String pattern, String input, int flags) {
        Matcher m = java.util.regex.Pattern.compile(pattern, jdkFlags(flags)).matcher(input);
        return m.find() ? spans(m) : null;
    }

    private static int[] tdfaFind(String pattern, String input, int flags, RegexEngineFactory factory) {
        PatternMatcher m = io.github.jemmix.tdfa.Pattern.compile(pattern, flags, factory).matcher(input);
        if (!m.find()) {
            return null;
        }
        int gc = m.groupCount();
        int[] out = new int[2 + 2 * gc];
        out[0] = m.start();
        out[1] = m.end();
        for (int g = 1; g <= gc; g++) {
            out[2 * g] = m.start(g);
            out[2 * g + 1] = m.end(g);
        }
        return out;
    }

    private static int[] spans(Matcher m) {
        int gc = m.groupCount();
        int[] out = new int[2 + 2 * gc];
        out[0] = m.start();
        out[1] = m.end();
        for (int g = 1; g <= gc; g++) {
            out[2 * g] = m.start(g);
            out[2 * g + 1] = m.end(g);
        }
        return out;
    }
}
