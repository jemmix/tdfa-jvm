package io.github.jemmix.tdfa.parity;

import io.github.jemmix.tdfa.core.compile.PatternMatcher;
import io.github.jemmix.tdfa.core.compile.RegexEngineFactory;
import io.github.jemmix.tdfa.core.compile.TDFAPattern;
import io.github.jemmix.tdfa.core.engine.MatchSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The jur facade's API-parity family, pinned cell-for-cell against the
 * LIVE {@code java.util.regex} oracle: the region/bounds family
 * ({@code region}/{@code useAnchoringBounds}/
 * {@code useTransparentBounds} over find/lookingAt/matches), the
 * {@code hitEnd}/{@code requireEnd} flags (the cold-probe re-derivation
 * — every cell here was probed on a live JDK first), the
 * java-only escape spellings (the ESCAPES_RE2 axis unset), the
 * JDK-mirroring compile flags (UNIX_LINES, UNICODE_CASE, LITERAL,
 * COMMENTS), {@code usePattern}, the snapshot and the function-based
 * replacements. The engine-factory parameter runs both tiers (VM and
 * ASM) — the region routing is machinery-side, the probes live on the
 * runner, and the generated matcher's inlined fast paths must agree.
 */
class JurApiParityTest {

    // JDK bit values (names and semantics mirror, numerics deliberately
    // do not — see the flags' javadoc).
    private static final int J_UNIX_LINES = 1;
    private static final int J_CI = 2;
    private static final int J_COMMENTS = 4;
    private static final int J_LITERAL = 16;
    private static final int J_UNICODE_CASE = 64;

    /** [pattern, input] cells, probed on the live JDK before landing. */
    private static final String[][] END_FLAG_CELLS =
        {{"abc", "xxabc"}, {"abc", "xxab"}, {"abc", "xxabcz"}, {"a*", "xaa"}, {"a*", "xaaz"}, {"a|ab", "xa"},
            {"a|ab", "xab"}, {"a$", "xa"}, {"a$", "xa\n"}, {"a\\z", "xa"}, {"a\\b", "xa"}, {"a\\b", "xa "},
            {"a\\B", "xa"}, {"(?:ab)*", "xxab"}, {"(?:ab)+", "xxab"}, {"[ab]+", "xxab"}, {"a{2,}", "xaa"},
            {"(?:a|b)+", "xxab"}, {"zzz", "abc"}, {"a\\d", "xxab"}, {"^abc", "xxabc"}, {"x\\bfoo", "xfoo"},
            {"(?m)a$", "ba"}, {"(?m)a$", "ba\nx"}, {"a\\Z", "ba"}, {"a\\Z", "ba\n"}, {"abc", ""}, {"\\bx", "ab"},};

    private static PatternMatcher ours(String p, String in, RegexEngineFactory f, int flags) {
        return io.github.jemmix.tdfa.Pattern.compile(p, flags, f).matcher(in);
    }

    private static Matcher jur(String p, String in, int flags) {
        return java.util.regex.Pattern.compile(p, flags).matcher(in);
    }

    interface Dual {
        void run(Matcher j, PatternMatcher o);
    }

    private void parity(String note, String p, String in, int ourFlags, int jurFlags, RegexEngineFactory f, Dual op) {
        Matcher jm = jur(p, in, jurFlags);
        PatternMatcher om = ours(p, in, f, ourFlags);
        op.run(jm, om);
        // assertJ note-annotated cells: assertJ lambdas can't capture, so
        // the op asserts directly; this wrapper exists for the note trail.
    }

    // ---- region / bounds ----

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void regionConfinesAndKeepsAbsoluteIndices(RegexEngineFactory f) {
        Matcher jm = jur("bc", "abcd", 0);
        PatternMatcher om = ours("bc", "abcd", f, 0);
        jm.region(1, 4);
        om.region(1, 4);
        assertThat(om.find()).isEqualTo(jm.find());
        assertThat(om.start()).isEqualTo(jm.start());
        assertThat(om.end()).isEqualTo(jm.end());
        assertThat(om.group()).isEqualTo(jm.group());
        assertThat(om.regionStart()).isEqualTo(jm.regionStart());
        assertThat(om.regionEnd()).isEqualTo(jm.regionEnd());
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void anchoringBoundsAnchorAtTheRegionEdges(RegexEngineFactory f) {
        Matcher jm = jur("^bc", "abcd", 0);
        PatternMatcher om = ours("^bc", "abcd", f, 0);
        jm.region(1, 4);
        om.region(1, 4);
        assertThat(om.find()).isTrue();
        assertThat(om.group()).isEqualTo("bc");
        // Non-anchoring: ^ anchors at the TRUE text edges only — no match.
        jm.reset().region(1, 4).useAnchoringBounds(false);
        om.reset().region(1, 4).useAnchoringBounds(false);
        assertThat(om.find()).isFalse();
        assertThat(jm.find()).isFalse();
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void multilineCaretReadsTheRealInputOutsideTheRegion(RegexEngineFactory f) {
        // (?m)^ with non-anchoring bounds still matches after a REAL
        // terminator that precedes the region start.
        Matcher jm = jur("(?m)^b", "a\nb", 0);
        PatternMatcher om = ours("(?m)^b", "a\nb", f, 0);
        jm.region(2, 3).useAnchoringBounds(false);
        om.region(2, 3).useAnchoringBounds(false);
        assertThat(om.find()).isEqualTo(jm.find());
        assertThat(om.group()).isEqualTo(jm.group());
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void absoluteAnchorsRespectAnchoringBounds(RegexEngineFactory f) {
        Matcher jm = jur("\\Ax", "abx", 0);
        PatternMatcher om = ours("\\Ax", "abx", f, 0);
        jm.region(1, 3);
        om.region(1, 3);
        assertThat(om.find()).isEqualTo(jm.find()); // \A at region start, 'b' ≠ x
        Matcher jm2 = jur("a$", "xab", 0);
        PatternMatcher om2 = ours("a$", "xab", f, 0);
        jm2.region(0, 2);
        om2.region(0, 2);
        assertThat(om2.find()).isEqualTo(jm2.find()); // $ at region end
        assertThat(om2.group()).isEqualTo(jm2.group());
        jm2.reset().region(0, 2).useAnchoringBounds(false);
        om2.reset().region(0, 2).useAnchoringBounds(false);
        assertThat(om2.find()).isEqualTo(jm2.find()); // $ at the TRUE end only
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void transparentBoundsLetWordBoundariesSeeBeyondTheRegion(RegexEngineFactory f) {
        Matcher jm = jur("x\\b", "xa", 0);
        PatternMatcher om = ours("x\\b", "xa", f, 0);
        jm.region(0, 1);
        om.region(0, 1);
        assertThat(om.find()).isTrue(); // opaque: region end is the text end
        assertThat(jm.find()).isTrue();
        jm.reset().region(0, 1).useTransparentBounds(true);
        om.reset().region(0, 1).useTransparentBounds(true);
        assertThat(om.find()).isFalse(); // sees the 'a': no boundary
        assertThat(jm.find()).isFalse();
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void resetClearsTheRegionAndKeepsTheBoundsFlags(RegexEngineFactory f) {
        PatternMatcher om = ours("b", "abc", f, 0);
        Matcher jm = jur("b", "abc", 0);
        om.region(1, 2);
        jm.region(1, 2);
        om.useTransparentBounds(true);
        jm.useTransparentBounds(true);
        om.reset();
        jm.reset();
        assertThat(om.regionStart()).isEqualTo(jm.regionStart());
        assertThat(om.regionEnd()).isEqualTo(jm.regionEnd());
        assertThat(om.hasTransparentBounds()).isEqualTo(jm.hasTransparentBounds());
        assertThat(om.hasAnchoringBounds()).isEqualTo(jm.hasAnchoringBounds());
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void findFromIndexResetsTheRegionLikeTheJdk(RegexEngineFactory f) {
        PatternMatcher om = ours("b", "abc", f, 0);
        Matcher jm = jur("b", "abc", 0);
        om.region(2, 3);
        jm.region(2, 3);
        om.find(1);
        jm.find(1);
        assertThat(om.regionEnd()).isEqualTo(jm.regionEnd());
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void wholeMatchAndLookingAtOverARegion(RegexEngineFactory f) {
        Matcher jm = jur("bc", "abcd", 0);
        PatternMatcher om = ours("bc", "abcd", f, 0);
        jm.region(1, 3);
        om.region(1, 3);
        assertThat(om.matches()).isEqualTo(jm.matches());
        assertThat(om.group()).isEqualTo(jm.group());
        Matcher jm2 = jur("b", "abc", 0);
        PatternMatcher om2 = ours("b", "abc", f, 0);
        jm2.region(1, 3);
        om2.region(1, 3);
        assertThat(om2.lookingAt()).isEqualTo(jm2.lookingAt());
        assertThat(om2.start()).isEqualTo(jm2.start());
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void findIterationInsideARegionIncludingEmptyMatchesAtTheEdge(RegexEngineFactory f) {
        Matcher jm = jur("a*", "bbb", 0);
        PatternMatcher om = ours("a*", "bbb", f, 0);
        jm.region(0, 2);
        om.region(0, 2);
        List<String> os = new ArrayList<>(), js = new ArrayList<>();
        while (om.find()) {
            os.add(om.start() + "," + om.end());
        }
        while (jm.find()) {
            js.add(jm.start() + "," + jm.end());
        }
        assertThat(os).containsExactlyElementsOf(js);
    }

    // ---- hitEnd / requireEnd (the live-JDK battery cells) ----

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void hitEndAndRequireEndMatchTheLiveOracle(RegexEngineFactory f) {
        for (String[] cell : END_FLAG_CELLS) {
            Matcher jm = jur(cell[0], cell[1], 0);
            PatternMatcher om = ours(cell[0], cell[1], f, 0);
            assertThat(om.find()).as("find %s on %s", cell[0], vis(cell[1])).isEqualTo(jm.find());
            assertThat(om.hitEnd()).as("hitEnd %s on %s", cell[0], vis(cell[1])).isEqualTo(jm.hitEnd());
            assertThat(om.requireEnd()).as("requireEnd %s on %s", cell[0], vis(cell[1])).isEqualTo(jm.requireEnd());
        }
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void hitEndUnderWholeMatchAndLookingAt(RegexEngineFactory f) {
        Matcher jm = jur("a*", "aa", 0);
        PatternMatcher om = ours("a*", "aa", f, 0);
        om.matches();
        jm.matches();
        assertThat(om.hitEnd()).isEqualTo(jm.hitEnd()); // greedy loop probes at end
        Matcher jm2 = jur("ab", "ab", 0);
        PatternMatcher om2 = ours("ab", "ab", f, 0);
        om2.matches();
        jm2.matches();
        assertThat(om2.hitEnd()).isEqualTo(jm2.hitEnd()); // quiet literal completion
        Matcher jm3 = jur("a+", "aa", 0);
        PatternMatcher om3 = ours("a+", "aa", f, 0);
        om3.lookingAt();
        jm3.lookingAt();
        assertThat(om3.hitEnd()).isEqualTo(jm3.hitEnd());
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void hitEndOverARegionUsesTheRegionEnd(RegexEngineFactory f) {
        Matcher jm = jur("ab", "xxabz", 0);
        PatternMatcher om = ours("ab", "xxabz", f, 0);
        jm.region(2, 4);
        om.region(2, 4);
        assertThat(om.find()).isEqualTo(jm.find());
        assertThat(om.hitEnd()).isEqualTo(jm.hitEnd()); // the walk ran to the region end
        assertThat(om.requireEnd()).isEqualTo(jm.requireEnd());
    }

    // ---- the java-only escapes (ESCAPES_RE2 unset) ----

    /** [pattern, input] escape cells; rejects assert compile failures on BOTH.
     *  The exotic members (NEL, OGHAM SPACE MARK) are computed — the
     *  formatter decodes source-level unicode escapes into raw characters,
     *  and a raw NEL does not survive lexing. */
    private static String[][] escapeCells() {
        String nel = String.valueOf((char) 0x85);
        String ogham = String.valueOf((char) 0x1680);
        return new String[][]{{"\\v", "a\nb"}, {"\\v", "a\u000Bb"}, {"\\V", "a\nb"}, {"[\\v]", "\n"},
            {"[\\v]", "\u000B"}, {"[\\v-z]", "\u000B"}, {"[\\v-z]", "\n"}, {"[\\v-]", "-"}, {"\\h", "a b"},
            {"\\h", ogham}, {"\\H", "x"}, {"[\\h]", " "}, {"\\e", "\u001B"}, {"[\\e]", "\u001B"}, {"\\R", "a\r\nb"},
            {"\\R", nel}, {"\\R", "\n"}, {"\\cI", "\t"}, {"\\cC", "\u0003"}, {"[\\cI]", "\t"},
            {"\\N{LATIN SMALL LETTER A}", "a"}, {"[\\N{LATIN SMALL LETTER A}]", "a"}, {"\\Z", "ab\n"}, {"\\Z", "ab"},
            {"a\\Z", "ab\n"}, {"(?m)a\\Z", "ab\n"}, {"\\00", "\u0000"}, {"\\010", "\b"}, {"\\012", "\n"},};
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void jurEscapesMatchTheLiveOracle(RegexEngineFactory f) {
        for (String[] cell : escapeCells()) {
            boolean jurFinds;
            try {
                jurFinds = jur(cell[0], cell[1], 0).find();
            } catch (java.util.regex.PatternSyntaxException e) {
                jurFinds = false;
            }
            boolean ourFinds;
            try {
                ourFinds = ours(cell[0], cell[1], f, 0).find();
            } catch (Exception e) {
                ourFinds = false;
            }
            assertThat(ourFinds).as("escape %s on %s", cell[0], vis(cell[1])).isEqualTo(jurFinds);
        }
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void jurEscapeRejectsMatchTheLiveOracle(RegexEngineFactory f) {
        for (String p : new String[]{"\\N", "\\N{}", "\\N{U+0041}", "\\N{NO SUCH NAME}", "\\c", "[\\R]", "\\0",
            "\\08"}) {
            boolean jurRejects = false;
            try {
                java.util.regex.Pattern.compile(p);
            } catch (java.util.regex.PatternSyntaxException e) {
                jurRejects = true;
            }
            assertThat(jurRejects).as("the oracle rejects %s", p).isTrue();
            assertThatThrownBy(() -> io.github.jemmix.tdfa.Pattern.compile(p, 0, f)).as("we reject %s", p)
                .isInstanceOf(io.github.jemmix.tdfa.core.parser.PatternSyntaxException.class);
        }
    }

    @Test
    void re2jFacadeKeepsRejectingTheJavaOnlyEscapes() {
        for (String p : new String[]{"\\e", "\\R", "\\N{LATIN SMALL LETTER A}", "\\cI", "\\h", "\\H", "\\V", "\\Z"}) {
            assertThatThrownBy(() -> io.github.jemmix.tdfa.re2j.Pattern.compile(p)).as("re2j rejects %s", p)
                .isInstanceOf(io.github.jemmix.tdfa.core.parser.PatternSyntaxException.class);
        }
        // re2j's \v stays the single unit U+000B (the ESCAPES_RE2 axis).
        assertThat(io.github.jemmix.tdfa.re2j.Pattern.compile("\\v").matcher("a\u000Bb").find()).isTrue();
        assertThat(io.github.jemmix.tdfa.re2j.Pattern.compile("\\v").matcher("a\nb").find()).isFalse();
        // and the (?x) letter stays unbound there.
        assertThatThrownBy(() -> io.github.jemmix.tdfa.re2j.Pattern.compile("(?x)a b"))
            .isInstanceOf(io.github.jemmix.tdfa.core.parser.PatternSyntaxException.class);
    }

    // ---- the JDK-mirroring compile flags ----

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void unixLinesAndUnicodeCaseFlagsMirrorTheJdk(RegexEngineFactory f) {
        assertThat(ours(".b", "a\rb", f, io.github.jemmix.tdfa.Pattern.UNIX_LINES).find())
            .isEqualTo(jur(".b", "a\rb", J_UNIX_LINES).find());
        assertThat(ours(".b", "a\rb", f, 0).find()).isEqualTo(jur(".b", "a\rb", 0).find());
        assertThat(ours("k", "\u212A", f,
            io.github.jemmix.tdfa.Pattern.CASE_INSENSITIVE | io.github.jemmix.tdfa.Pattern.UNICODE_CASE).find())
            .isEqualTo(jur("k", "\u212A", J_CI | J_UNICODE_CASE).find());
        assertThat(ours("k", "\u212A", f, io.github.jemmix.tdfa.Pattern.CASE_INSENSITIVE).find())
            .isEqualTo(jur("k", "\u212A", J_CI).find());
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void literalFlagMirrorsTheJdkIncludingTheQuirks(RegexEngineFactory f) {
        int lit = io.github.jemmix.tdfa.Pattern.LITERAL;
        String[][] cells =
            {{"a.c", "abc"}, {".${}", ".${}"}, {"a[b", "a[b"}, {"\\E", "E"}, {"\\E", "\\E"}, {"a\\Qb", "a\\Qb"}};
        for (String[] c : cells) {
            assertThat(ours(c[0], c[1], f, lit).find()).as("LITERAL %s on %s", c[0], vis(c[1]))
                .isEqualTo(jur(c[0], c[1], J_LITERAL).find());
        }
        // LITERAL | CASE_INSENSITIVE folds the quoted literals (JDK-verified).
        assertThat(ours("ab", "AB", f, lit | io.github.jemmix.tdfa.Pattern.CASE_INSENSITIVE).find())
            .isEqualTo(jur("ab", "AB", J_LITERAL | J_CI).find());
        // pattern()/flags() round-trip the ORIGINAL text and flag.
        io.github.jemmix.tdfa.Pattern p =
            io.github.jemmix.tdfa.Pattern.compile("a.b", lit | io.github.jemmix.tdfa.Pattern.CASE_INSENSITIVE, f);
        assertThat(p.pattern()).isEqualTo("a.b");
        assertThat(p.flags()).isEqualTo(lit | io.github.jemmix.tdfa.Pattern.CASE_INSENSITIVE);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void commentsFlagAndInlineXSkipTokensLikeTheJdk(RegexEngineFactory f) {
        int com = io.github.jemmix.tdfa.Pattern.COMMENTS;
        String[][] cells =
            {{"a b # c", "abd"}, {"a\\ b", "a b"}, {"[a b]", "b"}, {"[a b]", " "}, {"a *", "aa"}, {"a # z\nb", "ab"},
                {"[a - z]", "5"}, {"(?x)a b", "ab"}, {"(?x)a b(?-x)c d", "abc d"}, {"(?x:1 2)3", "123"}};
        for (String[] c : cells) {
            assertThat(ours(c[0], c[1], f, com).find()).as("COMMENTS %s on %s", c[0], vis(c[1]))
                .isEqualTo(jur(c[0], c[1], J_COMMENTS).find());
        }
        // The re2j-shaped lanes keep whitespace literal without the flag.
        assertThat(ours("a b", "ab", f, 0).find()).isEqualTo(jur("a b", "ab", 0).find());
    }

    // ---- usePattern / snapshot / function replace ----

    @Test
    void usePatternKeepsPositionAndLosesGroupsOnTheVmTier() {
        // The machinery matcher tier: -Dtdfa.engine=VM hands out plain
        // PatternMatchers (any factory route — even the interpreter —
        // rides the emitted shell and refuses usePattern by design).
        String prev = System.getProperty("tdfa.engine");
        try {
            System.setProperty("tdfa.engine", "VM");
            Matcher jm = jur("ab", "xabqb", 0);
            PatternMatcher om = io.github.jemmix.tdfa.Pattern.compile("ab").matcher("xabqb");
            assertThat(om.getClass()).isEqualTo(PatternMatcher.class);
            assertThat(om.find()).isTrue();
            assertThat(jm.find()).isTrue();
            assertThat(om.group()).isEqualTo("ab");
            om.usePattern((TDFAPattern) io.github.jemmix.tdfa.Pattern.compile("b"));
            jm.usePattern(java.util.regex.Pattern.compile("b"));
            // Position kept: the next find continues after the last match —
            // the b INSIDE it (index 2) is skipped, the one at 4 is found.
            assertThat(om.find()).isTrue();
            assertThat(jm.find()).isTrue();
            assertThat(om.start()).isEqualTo(jm.start()).isEqualTo(4);
        } finally {
            if (prev == null) {
                System.clearProperty("tdfa.engine");
            } else {
                System.setProperty("tdfa.engine", prev);
            }
        }
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void generatedTierMatchersRefuseUsePatternLoudly(RegexEngineFactory f) {
        PatternMatcher m = io.github.jemmix.tdfa.Pattern.compile("a", 0, f).matcher("a");
        if (m.getClass() != PatternMatcher.class) {
            assertThatThrownBy(() -> m.usePattern((TDFAPattern) io.github.jemmix.tdfa.Pattern.compile("b")))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("generated per-pattern matcher");
        }
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void toMatchResultIsAnImmutableSnapshot(RegexEngineFactory f) {
        PatternMatcher m = io.github.jemmix.tdfa.Pattern.compile("(a)(b)").matcher("xab");
        assertThat(m.find()).isTrue();
        MatchSnapshot snap = m.toMatchResult();
        assertThat(snap.start()).isEqualTo(1);
        assertThat(snap.group(2)).isEqualTo("b");
        // the JDK interface's named-group defaults ride the engine map
        assertThat(m.find()).isFalse(); // the matcher moves on; the snapshot does not
        assertThat(snap.group(2)).isEqualTo("b");
        assertThat(snap.groupCount()).isEqualTo(2);
        // a failed find's snapshot is the JDK's empty shape, not a throw
        MatchSnapshot empty = m.toMatchResult();
        assertThat(empty.start()).isEqualTo(-1);
        assertThat(empty.group()).isNull();
        assertThat(empty.hasMatch()).isFalse();
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void functionReplacementsMirrorTheJdk(RegexEngineFactory f) {
        io.github.jemmix.tdfa.Pattern p = io.github.jemmix.tdfa.Pattern.compile("(a)(b)", 0, f);
        assertThat(p.matcher("abab").replaceAll(r -> "<" + r.group(2) + ">")).isEqualTo("<b><b>");
        assertThat(p.matcher("abab").replaceFirst(r -> "[" + r.group(1) + "]")).isEqualTo("[a]ab");
        assertThatThrownBy(() -> io.github.jemmix.tdfa.Pattern.compile("a", 0, f).matcher("aa").replaceAll(x -> null))
            .isInstanceOf(NullPointerException.class);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void resultsStreamsTheMatches(RegexEngineFactory f) {
        List<String> all = io.github.jemmix.tdfa.Pattern.compile("\\d+", 0, f).matcher("a1b22c333").results()
            .map(MatchResult::group).collect(Collectors.toList());
        assertThat(all).containsExactly("1", "22", "333");
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void splitWithDelimitersAndTheStreamSpellingsMirrorTheJdk(RegexEngineFactory f) {
        io.github.jemmix.tdfa.Pattern p = io.github.jemmix.tdfa.Pattern.compile(":", 0, f);
        assertThat(p.splitWithDelimiters("a:b:c", 0)).containsExactly("a", ":", "b", ":", "c");
        assertThat(p.splitWithDelimiters("a:b:c", 2)).containsExactly("a", ":", "b:c");
        assertThat(p.splitWithDelimiters(":a:", 0)).containsExactly("", ":", "a", ":");
        assertThat(p.splitWithDelimiters("", 0)).isEmpty();
        assertThat(p.splitWithDelimiters("abc", 0)).containsExactly("abc");
        io.github.jemmix.tdfa.Pattern pc = io.github.jemmix.tdfa.Pattern.compile(",", 0, f);
        assertThat(pc.splitWithDelimiters("a,b,,", 0))
            .containsExactly(java.util.regex.Pattern.compile(",").splitWithDelimiters("a,b,,", 0));
        assertThat(p.asMatchPredicate().test(":")).isTrue();
        assertThat(p.asMatchPredicate().test("::")).isFalse();
        assertThat(p.asPredicate().test("a:b")).isTrue();
        // splitAsStream's empty-input cell (live-oracle pinned: one "",
        // where split("") is the empty array).
        assertThat(p.splitAsStream("")).containsExactly("");
        assertThat(p.splitAsStream("a:b")).containsExactly("a", "b");
    }

    private static String vis(String s) {
        return s.replace("\n", "\\n").replace("\r", "\\r").replace("\u000B", "\\v");
    }
}
