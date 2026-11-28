package io.github.jemmix.tdfa.parity;

import io.github.jemmix.tdfa.Pattern;
import io.github.jemmix.tdfa.core.compile.PatternMatcher;
import io.github.jemmix.tdfa.core.compile.RegexEngineFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.SplittableRandom;
import java.util.regex.Matcher;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Seeded random differential over the grapheme-cluster inputs against the
 * live JDK oracle, both engine tiers: {@code \X} (the cluster sequence)
 * and {@code \b{g}} (the boundary positions) on short strings drawn from
 * every class family the break rules name.
 *
 * <p>The hand-picked cells of {@link JurApiParityTest} cannot cover the
 * rule interactions densely. This differential exists because they missed
 * three defects while staying green: a {@code \b{g}} scanner whose GB9c
 * run state was never armed (every Indic conjunct split one consonant
 * early — a bare consonant-linker-consonant puts the spurious boundary
 * exactly where the real one is), the same scanner reading an unsorted
 * control table (NUL through TAB classed {@code Other}), and an {@code \X}
 * translation without the {@code T+} alternative (two jongseong split).
 *
 * <p>The alphabet carries the roles, not just the classes: an InCB
 * consonant, a linker (also {@code gcb=Extend}), an InCB extend, the
 * {@code gcb=Extend} marks outside InCB (ZWNJ ends the GB9c bracket, ZWJ
 * — an InCB extend — keeps it), spacing marks, Prepend, controls, CR/LF,
 * Hangul in every syllable type, regional indicators, an emoji modifier —
 * and a representative of every branch the JDK's category-derived
 * classifier takes.
 *
 * <p>Two families the stock JDK reads differently from UAX #29 stay out of
 * the alphabet, because this oracle is the stock JDK: its dead GB11 state
 * update (JDK-8393247 — no Extended_Pictographic here), and its
 * category-derived classes for lone surrogates and unassigned code points.
 */
class GraphemeClusterDifferentialTest {

    /**
     * One alphabet symbol: a BMP char or a supplementary code point. A
     * unicode escape takes exactly four hex digits, so five-digit code
     * points are numeric literals.
     */
    private static final int[] ALPHABET = {'a', ' ', '.', '5', '\u4E00', // Other: letter, space, punctuation, digit, CJK
        '\u0000', '\t', '\u0085', '\r', '\n', 0x2028, 0x2029, // Cc, Zl, Zp
        '\u00AD', '\u200B', '\u200E', '\u2060', '\uFEFF', // Cf that are Control
        '\u0301', '\u0327', '\uFE0F', '\u20E3', 0x1AB0, // gcb Extend, InCB extend
        0xE0020, // tag: a Cf the JDK classes Extend
        '\u0915', '\u0916', '\u0905', // Devanagari consonants, an independent vowel
        '\u094D', // virama: InCB linker, gcb Extend
        '\u093C', // nukta: InCB extend
        '\u093E', '\u093F', '\u0E33', '\u09BE', // spacing marks (U+09BE: Extend in the data, Mc to the JDK)
        '\u102B', // a spacing mark both exclude: Other
        '\u200C', // ZWNJ: gcb Extend, outside InCB
        '\u200D', // ZWJ: gcb ZWJ, InCB extend
        '\u1100', '\uA960', '\u1161', '\uD7B0', '\u11A8', '\uAC00', '\uAC01', // Hangul L, V, T, LV, LVT
        0x1F1FA, 0x1F1F8, // regional indicators
        0x1F3FD, // emoji modifier: gcb Extend
        '\u0600', '\u06DD', 0x110BD, '\u0D4E', // Prepend
    };

    private static final int STRINGS = 50_000;

    /** The slice a neighborhood draw covers. */
    private static final int NEIGHBORHOOD = 12;

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.JurOracle#engineFactories")
    void clusterAtomAndBoundaryMirrorTheJdk(RegexEngineFactory f) {
        java.util.regex.Pattern jx = java.util.regex.Pattern.compile("\\X");
        java.util.regex.Pattern jb = java.util.regex.Pattern.compile("\\b{g}");
        Pattern ox = Pattern.compile("\\X", 0, f);
        Pattern oy = Pattern.compile("\\b{g}", 0, f);
        SplittableRandom rnd = new SplittableRandom(0x4E39636BL); // "N9c"
        for (int t = 0; t < STRINGS; t++) {
            // Half the strings draw from a slice of the alphabet: the
            // families sit contiguously, so the interactions inside one
            // (an Indic chain, a Hangul run, a run of format characters)
            // occur far more often than a uniform draw over all symbols.
            int base = rnd.nextInt(ALPHABET.length);
            int span = rnd.nextBoolean() ? NEIGHBORHOOD : ALPHABET.length;
            StringBuilder sb = new StringBuilder();
            int len = 1 + rnd.nextInt(10);
            for (int i = 0; i < len; i++) {
                sb.appendCodePoint(ALPHABET[(base + rnd.nextInt(span)) % ALPHABET.length]);
            }
            String in = sb.toString();
            assertThat(spans(ox.matcher(in))).as("\\X cluster sequence on %s", describe(in))
                .isEqualTo(spans(jx.matcher(in)));
            assertThat(spans(oy.matcher(in))).as("\\b{g} boundary positions on %s", describe(in))
                .isEqualTo(spans(jb.matcher(in)));
        }
    }

    /** The string's UTF-16 code units — a failure has to be reproducible
     *  from the report, and these inputs do not print. */
    private static String describe(String in) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < in.length(); i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(String.format("%04X", (int) in.charAt(i)));
        }
        return sb.append('"').toString();
    }

    /** Every match as {@code start..end}, space-separated — the whole
     *  find-iteration (continuation and empty-match advance included). */
    private static String spans(Matcher m) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        while (m.find() && n++ < 256) {
            sb.append(m.start()).append("..").append(m.end()).append(' ');
        }
        return sb.toString();
    }

    private static String spans(PatternMatcher m) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        while (m.find() && n++ < 256) {
            sb.append(m.start()).append("..").append(m.end()).append(' ');
        }
        return sb.toString();
    }
}
