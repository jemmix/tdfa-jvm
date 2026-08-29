package io.github.jemmix.tdfa.parity;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import io.github.jemmix.tdfa.core.RegexEngineFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Semantics-contract matrices: curated (pattern, input) tables per semantic
 * area, each entry pinning our behavior to the re2j oracle. These encode the
 * probe-first discipline as a permanent gate — the ſ-folding round happened
 * because a comment claimed "ASCII-only folding = re2j semantics" without a
 * probe; a wrong empirical claim about the oracle now fails here immediately,
 * instead of surfacing as an unlocalizable fuzz mismatch months later.
 *
 * <p>Areas: case folding under (?i), anchor flavors and group-scoped flags,
 * and the loop/empty-iteration discipline. Known re2j-vs-JDK divergences
 * where we side with re2j (JDK disagrees on some entries by design — e.g. JDK
 * reports null for the empty-iteration capture family and matches {@code $}
 * before a final newline) are annotated at the matrix, not diluted from it:
 * our oracle is re2j.
 */
class SemanticsContractTest {

    private static String re2jProtocol(String p, String in) {
        var m = com.google.re2j.Pattern.compile(p).matcher(in);
        if (!m.find()) return "no";
        String g1;
        try { g1 = m.group(1) == null ? "null" : "'" + m.group(1) + "'"; } catch (RuntimeException e) { g1 = "-"; }
        return m.start() + ".." + m.end() + " g1=" + g1;
    }

    private static String tdfaProtocol(String p, String in, RegexEngineFactory f) {
        var m = io.github.jemmix.tdfa.Pattern.compile(p, 0, f, com.google.re2j.Re2jUnicodeProvider.INSTANCE).matcher(in);
        if (!m.find()) return "no";
        String g1;
        try { g1 = m.group(1) == null ? "null" : "'" + m.group(1) + "'"; } catch (RuntimeException e) { g1 = "-"; }
        return m.start() + ".." + m.end() + " g1=" + g1;
    }

    private static void assertMatchesRe2j(String pattern, String input, RegexEngineFactory factory) {
        assertThat(tdfaProtocol(pattern, input, factory))
                .as("pattern=\"%s\" input-encoded=\"%s\" [%s]", pattern, escape(input), factory)
                .isEqualTo(re2jProtocol(pattern, input));
    }

    private static String escape(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) b.append(String.format("\\u%04x", (int) s.charAt(i)));
        return b.append('"').toString();
    }

    // ---- area: full Unicode simple folding under plain (?i) ----
    // re2j folds full orbits (s↔S↔ſ, k↔K) with no (?u): literals, explicit
    // classes (into the positive set before negation), and word shorthands;
    // \d/\s have no orbits; \b's word table stays UNFOLDED.

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void foldingMatrix(RegexEngineFactory factory) {
        String[][] m = {
                {"(?i)s", "ſ"}, {"(?i)s", "S"}, {"(?i:S)", "aſb"}, {"(?i:ſ)", "s"}, {"(?i)k", "\u212a"},
                {"(?i)[s]", "ſ"}, {"(?i)[a-z]", "ſ"}, {"(?i)[a-z]", "\u212a"}, {"(?i)[^s]", "ſ"}, {"(?i)[^S]", "ſ"},
                {"(?i)\\w+", "aſb"}, {"(?i)\\w", "\u212a"}, {"(?i)\\W", "ſ"}, {"(?i:\\W+)", "aſb"},
                {"(?i)[\\W]", "ſ"}, {"(?i)[^\\w]", "ſ"},
                {"(?i)s\\b", "aſ"}, {"(?i)\\w\\b", "ſ"},          // \b wordness UNFOLDED
                {"(?i)\\d", "9"}, {"(?i)\\s", " "},                // no orbits: (?i) no-op
                {"(?i)\ud801\udc21", "\ud801\udc01"},              // supplementary letters fold
                {"\\w+", "aſb"}, {"\\W", "ſ"},                     // bare classes unchanged
        };
        for (String[] c : m) assertMatchesRe2j(c[0], c[1], factory);
    }

    // ---- area: anchor flavors and group-scoped flags ----
    // BEGIN_TEXT/END_TEXT are line-flavored bits; each ^/$ edge requires the
    // ABS bits (plain) or the LINE bits (parse-time (?m), group-scoped
    // included); \A/\z are the ABS bits alone. JDK's bare-$-before-final-\n
    // divergence is intentionally NOT represented (we follow re2j).

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void anchorMatrix(RegexEngineFactory factory) {
        String[][] m = {
                {"(?m:$)", "ab\ncd"}, {"(?m:\\S.$)", "ab\ncd"}, {"\\S(?m:$)", "ab\ncd"},
                {"\\D(?m:\\S.$)", "ab\ncd"}, {"(?m:^\\w)", "ab\ncd"}, {"(?m:^)", "ab\ncd"},
                {"^\\w", "ab\ncd"}, {"\\w$", "ab\ncd"},            // bare: absolute only
                {"\\A\\w", "ab"}, {"\\w\\z", "ab"},
                {"(?m:\\w$)", "ab\ncd"}, {"(?:(?m:$)|x)", "ab\nx"},
                {"(?:(?:^)|(?:$))+$", "a"}, {"(?:^|$)+$", "a"},    // multi-branch nullable loop + anchors
        };
        for (String[] c : m) assertMatchesRe2j(c[0], c[1], factory);
    }

    // ---- area: loop and empty-iteration discipline ----
    // Empty iterations are cut (subsumption); greedy empty iterations' capture
    // writes reach the loop-exit accept; lazy exit-first does not; sibling
    // symbol paths see no cross-branch writes. JDK reports null for the
    // g1='' family — documented divergence, we side with re2j.

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void loopDisciplineMatrix(RegexEngineFactory factory) {
        String[][] m = {
                {"(?:.*?9{0,}\\b){1,}", "99x"}, {"(?:.*?9*\\b){1,}", "99x"},
                {"(?:.*?\\b){1,}", "99x"}, {"(?:9*\\b){1,}", "99x"},
                {"(\\B)*\\z", "!"}, {"(\\B)*\\z", "a!"}, {"(\\b)*\\z", "a"},
                {"(\\B)*$", "!"}, {"((\\B))*\\z", "!"}, {"(\\B)*?", "!"},
                {"(\\B)*?\\z", "!"}, {"(\\B)(\\B)*\\z", "!"}, {"(\\B|)*\\z", "!"},
                {"(?:(\\B)x|y)\\z", "y"}, {"(?:(\\B)x|y)\\z", "yx"},
                {"(?:\\b)+$", "a b"}, {"(?:a{0,}){1,}b", "ab"}, {"(?:x?){1,}y", "y"},
        };
        for (String[] c : m) assertMatchesRe2j(c[0], c[1], factory);
    }
}
