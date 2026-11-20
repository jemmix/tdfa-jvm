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
import java.util.function.BiPredicate;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.stream.Collectors;

import static io.github.jemmix.tdfa.parity.Re2jOracle.assertSameCompileReject;
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

    /**
     * The JDK-8394894 grapheme boundary clauses (the ESCAPES_RE2 axis's
     * unset side): java reads a {@code {'} directly after
     * {@code \b}/{@code \B} as its boundary-type clause
     * ({@code \b{g}}, {@code \b{gc}=…}) or an illegal repetition — never
     * a literal brace — and this engine has no grapheme support, so the
     * jur reading rejects every non-quantifier brace there loudly
     * (before this landing the parser silently read the RE2-lineage
     * literal there). A digit quantifier ({@code \b{5}}) is legal java
     * in both readings and passes through.
     */
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void graphemeBoundaryClausesRejectLoudlyOnTheJurFacade(RegexEngineFactory f) {
        // \X LANDED (graphemeClusterAtomMirrorsTheJdk below): the atom
        // translates; a\\Xb and (?:\\X)+ ride it.
        assertThat(io.github.jemmix.tdfa.Pattern.compile("a\\Xb", 0, f).matcher("a\u0301b").find()).isTrue();
        assertThat(io.github.jemmix.tdfa.Pattern.compile("(?:\\X)+", 0, f).matcher("x").find()).isTrue();
        // the \b{...}/\B{...} property clauses still reject (the boundary
        // family beyond \b{g}); \B{g} does not exist in java either
        for (String p : new String[]{"\\b{gc=L}", "\\b{w}", "\\B{g}", "\\b{}"}) {
            assertThatThrownBy(() -> io.github.jemmix.tdfa.Pattern.compile(p, 0, f)).as("jur rejects %s", p)
                .isInstanceOf(io.github.jemmix.tdfa.core.parser.PatternSyntaxException.class)
                .hasMessageContaining("grapheme");
        }
        // \b{g} LANDED — the bare clause compiles (a zero-width assertion)
        io.github.jemmix.tdfa.Pattern.compile("\\b{g}", 0, f);
        // the quantifier spelling is legal java in both readings
        assertThat(io.github.jemmix.tdfa.Pattern.compile("\\b{2,3}", 0, f).matcher("xx").find()).isTrue();
    }

    /** {@code \X} — one extended grapheme cluster — against the live JDK
     *  oracle, both engine tiers: the full cluster-sequence comparison
     *  (every cluster's codepoint count, find-iteration order included).
     *  Cells span the rule families: combining marks (GB9), stacked and
     *  orphan leads, CR×LF (GB3) and lone controls (GB4/5), RI pairs
     *  (GB12/13 — including the odd trailing RI), skin tones and ZWJ
     *  chains (GB11/GB9), Hangul jamo sequences and precomposed syllables
     *  (GB6-8), Indic conjuncts (GB9c), and Prepend (GB9b). */
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.JurOracle#engineFactories")
    void graphemeClusterAtomMirrorsTheJdk(RegexEngineFactory f) {
        String[] cells = {"abc", "cafe\u0301", "e\u0301\u0308", "\u0301", "\r\n\r", "\r", "\n\n",
            "\uD83C\uDDFA\uD83C\uDDF8", "\uD83C\uDDFA\uD83C\uDDF8\uD83C\uDDEB\uD83C\uDDF7",
            "\uD83C\uDDFA\uD83C\uDDF8\uD83C\uDDEB\uD83C\uDDF7\uD83C\uDDFA", "\uD83D\uDC4D\uD83C\uDFFD",
            "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67",
            "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67\u200D\uD83D\uDC66", "\u1100\u1161\u11A8", "\uAC01",
            "\u1100\uAC01\u11A8", "\u1100\u1161", "a\uD83D\uDC4Db", "\u0905\u094D\u0915", "\u0C15\u0C4D\u0C15",
            "\u0600abc", "\u0600\u0600a", "x\u200Dy", "\u200D"};
        for (String in : cells) {
            StringBuilder js = new StringBuilder(), os = new StringBuilder();
            Matcher jm = jur("\\X", in, 0);
            while (jm.find()) {
                js.append('[').append(jm.group().codePointCount(0, jm.group().length())).append(']');
            }
            PatternMatcher om = io.github.jemmix.tdfa.Pattern.compile("\\X", 0, f).matcher(in);
            while (om.find()) {
                os.append('[').append(om.group().codePointCount(0, om.group().length())).append(']');
            }
            assertThat(os.toString()).as("\\X cluster sequence on \"%s\"", in).isEqualTo(js.toString());
        }
        // \X under a quantifier and mid-pattern, oracle-compared
        JurOracle.assertSameFind("(?:\\X)+", "cafe\u0301", f);
        JurOracle.assertSameFind("\\Xb", "a\u0301b", f);
        JurOracle.assertSameFind("a\\X", "a\u0301", f);
        // In a class \X is illegal in java too — both reject.
        JurOracle.assertSameCompileReject("[\\X]", f);
    }

    /** The re2j facade keeps its literal-brace reading (re2j's alphabet —
     *  pinned by the RE2J fuzz lane), and rejects {@code \X} like re2j. */
    @Test
    void re2jFacadeKeepsTheLiteralBraceReading() {
        // \b{g} reads as \b followed by the literal "{g}": on "{g}" the
        // \b fails (both sides non-word) but on "x{g}" it matches.
        assertThat(io.github.jemmix.tdfa.re2j.Pattern.compile("\\b{g}").matcher("x{g}").find()).isTrue();
        assertThat(io.github.jemmix.tdfa.re2j.Pattern.compile("\\b{g}").matcher("{g}").find()).isFalse();
        assertThatThrownBy(() -> io.github.jemmix.tdfa.re2j.Pattern.compile("\\X"))
            .isInstanceOf(io.github.jemmix.tdfa.core.parser.PatternSyntaxException.class);
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

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void hitEndAfterFailedAnchoredOpsMirrorsTheOracle(RegexEngineFactory f) {
        // java resets hitEnd/requireEnd per OPERATION: a failed
        // matches()/lookingAt() reports the anchored walk's own flags
        // (the matchRoot entry — no Start scan), never the failed-search
        // rule. The pre-region-fix probe reported the scan rule here.
        String[][] cells = {{"x\\b", "xa"}, {"abc", "xxab"}, {"a+", "b"}, {"a\\d", "xxab"}, {"a$", "xab"},
            {"\\bx", "ab"}, {"a*", "b"}, {"(a)|(b)", "cd"}, {"a|ab", "x"}, {"ab9", "ab\u0301c"}};
        for (String[] cell : cells) {
            for (String op : new String[]{"matches", "lookingAt"}) {
                Matcher jm = jur(cell[0], cell[1], 0);
                PatternMatcher om = ours(cell[0], cell[1], f, 0);
                boolean jf = "matches".equals(op) ? jm.matches() : jm.lookingAt();
                boolean of = "matches".equals(op) ? om.matches() : om.lookingAt();
                assertThat(of).as("%s %s on %s", op, cell[0], vis(cell[1])).isEqualTo(jf);
                assertThat(om.hitEnd()).as("%s hitEnd %s on %s", op, cell[0], vis(cell[1])).isEqualTo(jm.hitEnd());
                assertThat(om.requireEnd()).as("%s requireEnd %s on %s", op, cell[0], vis(cell[1]))
                    .isEqualTo(jm.requireEnd());
            }
        }
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void regionsCuttingSurrogatePairsMirrorTheOracle(RegexEngineFactory f) {
        // A region end that CUTS a pair, and a region start that SPLITS
        // one: java's nodes read full codepoints across the edges (the
        // consumption must fit within `to`), so the subSequence view —
        // which hands the engine genuinely-lone surrogates — would lie;
        // both regions serve through the seam's bounded walk.
        String in = "ab\uD835\uDD04cd";
        Matcher jm = jur(".", in, 0);
        PatternMatcher om = ours(".", in, f, 0);
        jm.region(2, 3);
        om.region(2, 3);
        assertThat(om.find()).as("cut pair: the high half alone never matches").isEqualTo(jm.find()).isFalse();
        assertThat(om.hitEnd()).isEqualTo(jm.hitEnd());
        Matcher jl = jur("[^x]+", in, 0);
        PatternMatcher ol = ours("[^x]+", in, f, 0);
        jl.region(2, 3);
        ol.region(2, 3);
        // java's Curly quirk: the quantifier's atom reads the LONE high
        // half as one unit (matcher.last bookkeeping) where the same atom
        // unquantified reads the full codepoint and dies — a backtracker
        // artifact the DFA's edges cannot see (the region fuzz axis
        // counts these cells as backtracker-internals skips).
        assertThat(jl.find()).as("java's Curly unit-read quirk").isTrue();
        assertThat(ol.find()).as("our bounded walk stays codepoint-exact").isFalse();
        Matcher jw = jur("\\b", in, 0);
        PatternMatcher ow = ours("\\b", in, f, 0);
        jw.region(3, 6); // splits the pair: position 3 is the low half
        ow.region(3, 6);
        assertThat(ow.find()).as("split start: the word pair reads across the edge").isEqualTo(jw.find());
        assertThat(ow.start()).isEqualTo(jw.start());
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void regionContinuationFindsKeepTheRegionHeadBases(RegexEngineFactory f) {
        // A continuation find's search starts past the region head, but
        // every position predicate still anchors at the HEAD (java's
        // matcher.from/to) — the pre-fix seam shifted the bases with the
        // search start, so the second (?m)a$ (anchored at the region END,
        // found from the continuation position 3) never matched.
        Matcher jm = jur("(?m)a$", "za\na", 0);
        PatternMatcher om = ours("(?m)a$", "za\na", f, 0);
        jm.region(1, 4);
        om.region(1, 4);
        assertThat(om.find()).as("first find").isEqualTo(jm.find());
        assertThat(om.group()).isEqualTo(jm.group());
        assertThat(om.find()).as("continuation find (bases at the region head)").isEqualTo(jm.find());
        assertThat(om.group()).as("continuation span").isEqualTo(jm.group());
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void usePatternKeepsPositionAndLosesGroups(RegexEngineFactory f) {
        // Full JDK contract on EVERY tier: position and append state kept,
        // group info lost. Generated shells rebind the machinery engines
        // and route through the machinery implementations (the retarget
        // bail); plain machinery matchers just rebind.
        Matcher jm = jur("ab", "xabqb", 0);
        PatternMatcher om = io.github.jemmix.tdfa.Pattern.compile("ab", 0, f).matcher("xabqb");
        assertThat(om.find()).isTrue();
        assertThat(jm.find()).isTrue();
        assertThat(om.group()).isEqualTo("ab");
        om.usePattern((TDFAPattern) io.github.jemmix.tdfa.Pattern.compile("(b)", 0, f));
        jm.usePattern(java.util.regex.Pattern.compile("(b)"));
        // Group info lost until the next match — the JDK's null group
        // shape (a previous match leaves `first` set: no throw).
        assertThat(om.group()).isNull();
        assertThat(jm.group()).isNull();
        // Position kept: the next find continues after the last match —
        // the b INSIDE it (index 2) is skipped, the one at 4 is found —
        // by the NEW pattern.
        assertThat(om.find()).isTrue();
        assertThat(jm.find()).isTrue();
        assertThat(om.group()).isEqualTo(jm.group()).isEqualTo("b");
        assertThat(om.start()).isEqualTo(jm.start()).isEqualTo(4);
        assertThat(om.group(1)).isEqualTo("b");
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void generatedTierShellsRetargetToTheMachineryRoute(RegexEngineFactory f) {
        // The generated shell's fast paths bake the compile-time artifact:
        // before the retarget they serve it; after usePattern the shell
        // serves the NEW pattern through the machinery implementations.
        PatternMatcher m = io.github.jemmix.tdfa.Pattern.compile("a+", 0, f).matcher("xaaxa");
        if (m.getClass() == PatternMatcher.class) {
            return; // plain machinery matcher — nothing shell-specific to pin
        }
        assertThat(m.find()).isTrue();
        assertThat(m.group()).isEqualTo("aa");
        m.usePattern((TDFAPattern) io.github.jemmix.tdfa.Pattern.compile("x", 0, f));
        assertThat(m.find()).isTrue();
        assertThat(m.group()).isEqualTo("x");
        assertThat(m.start()).isEqualTo(3); // continuation from the old match's end
        assertThat(m.matches()).isFalse(); // "xaaxa" does not wholly match "x"
    }

    // ---- \G: the previous-match-end anchor (java's oldLast protocol) ----

    /** The full \G protocol against the live JDK oracle, both engine
     *  tiers: the base is the op's start on a fresh matcher, the previous
     *  match's end after one (source-pinned from the vendored Matcher —
     *  {@code oldLast = last} at op end, success and failure alike; a
     *  failed search moves nothing), and the protocol edges (find(int),
     *  region, reset, usePattern, the empty-match bump) move it exactly
     *  like java's. */
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.JurOracle#engineFactories")
    void gAnchorMirrorsTheJdkProtocol(RegexEngineFactory f) {
        // The tokenizer loop: base follows each match's end.
        Matcher jm = jur("\\Gab", "ababxab", 0);
        PatternMatcher om = io.github.jemmix.tdfa.Pattern.compile("\\Gab", 0, f).matcher("ababxab");
        StringBuilder js = new StringBuilder(), os = new StringBuilder();
        while (jm.find()) {
            js.append(jm.group()).append('@').append(jm.start()).append(' ');
        }
        while (om.find()) {
            os.append(om.group()).append('@').append(om.start()).append(' ');
        }
        assertThat(os.toString()).isEqualTo(js.toString()).isEqualTo("ab@0 ab@2 ");

        // Fresh base is the op's start (0): \Gxx matches, \Gax cannot.
        assertThat(oursFind("\\Gxx", "xxax", f)).isTrue();
        assertThat(oursFind("\\Gax", "xxax", f)).isFalse();

        // A failed search keeps the base (m3 stays false forever).
        PatternMatcher m3 = io.github.jemmix.tdfa.Pattern.compile("\\Gab", 0, f).matcher("xxab");
        assertThat(m3.find()).isFalse();
        assertThat(m3.find()).isFalse();

        // find(int) resets the base to the given start.
        PatternMatcher m5 = io.github.jemmix.tdfa.Pattern.compile("\\Ga", 0, f).matcher("aabcaa");
        assertThat(m5.find(1)).isTrue();
        assertThat(m5.start()).isEqualTo(1);

        // A fresh region bases \G at the region head (absolute).
        PatternMatcher m6 = io.github.jemmix.tdfa.Pattern.compile("\\Gb+", 0, f).matcher("aaabbb");
        m6.region(3, 6);
        assertThat(m6.find()).isTrue();
        assertThat(m6.start()).isEqualTo(3);

        // Mid-pattern \G compares the walk position to the base.
        assertThat(oursFind("a\\Gb", "ab", f)).isFalse();

        // The anchored ops evaluate \G at 0.
        assertThat(io.github.jemmix.tdfa.Pattern.compile("\\Gam", 0, f).matcher("amp").lookingAt()).isTrue();
        assertThat(io.github.jemmix.tdfa.Pattern.compile("\\Gam.*", 0, f).matcher("amp").matches()).isTrue();
        assertThat(io.github.jemmix.tdfa.Pattern.compile("\\Gam", 0, f).matcher("amp").matches()).isFalse();

        // reset() restores the fresh base (no \Gab at 0 here).
        PatternMatcher m8 = io.github.jemmix.tdfa.Pattern.compile("\\Gab", 0, f).matcher("xxabxxab");
        assertThat(m8.find()).isFalse();
        m8.reset();
        assertThat(m8.find()).isFalse();

        // usePattern keeps the base: the ab AT the old match's end serves.
        PatternMatcher m9 = io.github.jemmix.tdfa.Pattern.compile("ab", 0, f).matcher("xxabab");
        assertThat(m9.find()).isTrue();
        assertThat(m9.start()).isEqualTo(2);
        m9.usePattern((TDFAPattern) io.github.jemmix.tdfa.Pattern.compile("\\Gab", 0, f));
        assertThat(m9.find()).isTrue();
        assertThat(m9.start()).isEqualTo(4);

        // The empty-match bump: after aa and the empty match at 2, the
        // base stays 2 while the scan bumps past — \G dies, exactly like
        // the JDK's oldLast.
        Matcher jm11 = jur("\\Ga*", "aab", 0);
        PatternMatcher m11 = io.github.jemmix.tdfa.Pattern.compile("\\Ga*", 0, f).matcher("aab");
        assertThat(jm11.find()).isTrue();
        assertThat(m11.find()).isTrue();
        assertThat(m11.group()).isEqualTo(jm11.group()).isEqualTo("aa");
        assertThat(jm11.find()).isTrue();
        assertThat(m11.find()).isTrue();
        assertThat(m11.group()).isEmpty();
        assertThat(m11.start()).isEqualTo(jm11.start()).isEqualTo(2);
        assertThat(jm11.find()).isFalse();
        assertThat(m11.find()).isFalse();
    }

    private static boolean oursFind(String p, String in, RegexEngineFactory f) {
        return io.github.jemmix.tdfa.Pattern.compile(p, 0, f).matcher(in).find();
    }

    /** The RE2 lineage has no \G: the re2j facade rejects the spelling
     *  like its oracle (the escape alphabet's closed set). */
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void re2jFacadeRejectsGAnchor(RegexEngineFactory factory) {
        assertSameCompileReject("\\Gab", factory);
        assertSameCompileReject("a\\Gb", factory);
    }

    // ---- possessive quantifiers (docs/possessive-quantifiers.md) ----

    /** The possessive family against the live JDK oracle, both engine
     *  tiers — the language shrinks wherever backtracking would give
     *  back, the tags stay greedy-coincident (the first-preference
     *  path), and the nested-repetition rejects keep java's shape. */
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.JurOracle#engineFactories")
    void possessiveQuantifiersMirrorTheJdk(RegexEngineFactory f) {
        // Never give back: the quantifier ate what the continuation needed.
        JurOracle.assertSameFind("a++a", "aa", f);
        JurOracle.assertSameFind("a*+a", "aa", f);
        JurOracle.assertSameFind("a*+a", "b", f);
        JurOracle.assertSameFind("a?+a", "a", f);
        JurOracle.assertSameFind("a{1,2}+a", "aa", f);
        // Pass-through: no give-back required, the possessive form agrees.
        JurOracle.assertSameFind("a*+b", "aaab", f);
        JurOracle.assertSameFind("a?+b", "b", f);
        JurOracle.assertSameFind("a{1,2}+b", "aab", f);
        JurOracle.assertSameFind("[ab]++c", "abc", f);
        // The per-iteration alternation commit + the loop that never re-tries.
        JurOracle.assertSameFind("(a|ab)++c", "abc", f);
        JurOracle.assertSameFind("(a|ab)+c", "abc", f); // the greedy twin still translates
        JurOracle.assertSameFind("(?:a|b)*+b", "ab", f);
        JurOracle.assertSameFind("(?:a|b)*b", "ab", f);
        // Tags are greedy-coincident: the first-preference path reports.
        Matcher jt = jur("(a++)b", "aab", 0);
        PatternMatcher ot = io.github.jemmix.tdfa.Pattern.compile("(a++)b", 0, f).matcher("aab");
        assertThat(ot.find()).isEqualTo(jt.find()).isTrue();
        assertThat(ot.group(1)).isEqualTo(jt.group(1)).isEqualTo("aa");
        Matcher jt2 = jur("(a|b)++", "ab", 0);
        PatternMatcher ot2 = io.github.jemmix.tdfa.Pattern.compile("(a|b)++", 0, f).matcher("ab");
        assertThat(ot2.find()).isEqualTo(jt2.find()).isTrue();
        assertThat(ot2.group()).isEqualTo(jt2.group()).isEqualTo("ab");
        assertThat(ot2.group(1)).isEqualTo(jt2.group(1)).isEqualTo("b"); // the LAST iteration
        // Nested-repetition rejects keep java's shape.
        JurOracle.assertSameCompileReject("a++?", f);
        JurOracle.assertSameCompileReject("a+++", f);
        JurOracle.assertSameCompileReject("a*++", f);
    }

    /** The RE2 lineage has no possessive quantifiers: the re2j facade
     *  keeps rejecting every spelling like its oracle. */
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void re2jFacadeRejectsPossessiveQuantifiers(RegexEngineFactory factory) {
        assertSameCompileReject("a++", factory);
        assertSameCompileReject("a*+", factory);
        assertSameCompileReject("a?+", factory);
        assertSameCompileReject("a{2,3}+", factory);
        assertSameCompileReject("(a|ab)++c", factory);
    }

    /** The {@code \b{g}} grapheme-cluster boundary against the live JDK
     *  oracle, both engine tiers — java's OPERATIONAL semantics mirrored
     *  through the identical find protocol: the boundary scans clusters
     *  forward from the previous match's end, the region start (and end)
     *  always hold, a surrogate straddle never does, and the position
     *  {@code last} itself reads FALSE (java's own quirk — visible as the
     *  skipped position in the iteration displays). Cells span the rule
     *  families: marks, RI pairs (odd trailing), ZWJ chains, Hangul,
     *  GB9c conjuncts, Prepend, controls. */
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.JurOracle#engineFactories")
    void graphemeBoundaryMirrorsTheJdk(RegexEngineFactory f) {
        String[] cells = {"cafe\u0301", "abc", "\uD83C\uDDFA\uD83C\uDDF8x",
            "\uD83C\uDDFA\uD83C\uDDF8\uD83C\uDDEB\uD83C\uDDF7\uD83C\uDDFA",
            "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67x", "\r\nx", "\u1100\u1161\u11A8x",
            "\uD83D\uDC4D\uD83C\uDFFDx", "\u0C15\u0C4D\u0C15x", "\u0600ax", "e\u0301\u0308", "\u0301x"};
        for (String in : cells) {
            StringBuilder js = new StringBuilder(), os = new StringBuilder();
            Matcher jm = jur("\\b{g}.", in, 0);
            int pos = 0;
            while (jm.find()) {
                while (pos < jm.start()) {
                    js.append('-');
                    pos++;
                }
                js.append('^');
                pos++;
            }
            PatternMatcher om = io.github.jemmix.tdfa.Pattern.compile("\\b{g}.", 0, f).matcher(in);
            pos = 0;
            while (om.find()) {
                while (pos < om.start()) {
                    os.append('-');
                    pos++;
                }
                os.append('^');
                pos++;
            }
            assertThat(os.toString()).as("\\b{g}. boundary positions on \"%s\"", in).isEqualTo(js.toString());
        }
        // mid-pattern shapes
        JurOracle.assertSameFind("x\\b{g}y", "xy", f);
        JurOracle.assertSameFind("a\\b{g}", "cafe\u0301", f);
        JurOracle.assertSameFind("\\b{g}a\\b{g}a", "aa", f);
        // The QUANTIFIED shape diverges by java's own implementation leak:
        // Curly's greedy loop uses matcher.last as its position scratch, so
        // under a quantifier java's boundary anchors at the ITERATION
        // position ((?:\b{g}a)+ on "aa" gives [0,1) where the unrolled
        // \b{g}a\b{g}a gives [0,2) — probed both). We serve the principled
        // (unrolled) semantics: the boundary is loop-context-free. Pinned
        // as our documented answer, the java quirk noted.
        PatternMatcher loop = io.github.jemmix.tdfa.Pattern.compile("(?:\\b{g}a)+", 0, f).matcher("aa");
        assertThat(loop.find()).isTrue();
        assertThat(loop.group()).isEqualTo("aa");
        // in a class the clause is illegal in java too — both reject
        JurOracle.assertSameCompileReject("[\\b{g}]", f);
        // the property clauses still reject loudly (the narrowed gate)
        JurOracle.assertSameCompileReject("\\b{gc=Lu}", f);
        JurOracle.assertSameCompileReject("\\b{sc=Latin}", f);
    }

    /** {@code CANON_EQ} — the pattern-side canonical-equivalence rewrite
     *  against the live JDK oracle, both engine tiers (live-JDK probed
     *  before design): every literal codepoint and class member expands to
     *  an alternation of its NFC and NFD spellings — a decomposed pattern
     *  matches the composed input and vice versa, the base alone matches
     *  neither, and order is significant. Negated classes, written ranges
     *  and the fold-literal interaction stay verbatim (the landed
     *  surface, docs/jur-api-parity.md). */
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.JurOracle#engineFactories")
    void canonEqMirrorsTheJdk(RegexEngineFactory f) {
        BiPredicate<String, String> jdk =
            (p, in) -> java.util.regex.Pattern.compile(p, java.util.regex.Pattern.CANON_EQ).matcher(in).find();
        BiPredicate<String, String> ours = (p, in) -> io.github.jemmix.tdfa.Pattern
            .compile(p, io.github.jemmix.tdfa.Pattern.CANON_EQ, f).matcher(in).find();
        String[][] cells = {{"e\u0301", "é"}, {"é", "e\u0301"}, {"[e\u0301]", "é"}, {"[é]", "e\u0301"}, {"e", "é"},
            {"\u0301e", "é"}, {"e\u0301x", "éx"}, {"e\u0301", "e\u0301"}, {"é", "é"}, {"a\u0308", "ä"},
            {"\u0308a", "ä"}, {"q\u0301", "qué"}};
        for (String[] c : cells) {
            assertThat(ours.test(c[0], c[1])).as("CANON_EQ \"%s\" on \"%s\"", c[0], c[1])
                .isEqualTo(jdk.test(c[0], c[1]));
        }
        // The flag OFF keeps the literal reading.
        io.github.jemmix.tdfa.Pattern noFlag = io.github.jemmix.tdfa.Pattern.compile("e\u0301", 0, f);
        assertThat(noFlag.matcher("é").find()).isFalse();
        assertThat(noFlag.matcher("e\u0301").find()).isTrue();
    }

    /** Atomic groups against the live JDK oracle, both engine tiers
     *  (docs/atomic-groups.md — the decision record's cells): the lock
     *  commits the body's standalone first-preference submatch — the
     *  alternation commit, the loop commit, mid-pattern commits, captures
     *  through the lock, empty-capable and bounded bodies, and nested
     *  forks. */
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.JurOracle#engineFactories")
    void atomicGroupsMirrorTheJdk(RegexEngineFactory f) {
        // The alternation commit and its greedy twin
        JurOracle.assertSameFind("(?>a|ab)c", "abc", f);
        JurOracle.assertSameFind("(a|ab)c", "abc", f);
        // The loop commit: the maximal run never gives back
        JurOracle.assertSameFind("(?>a+)a", "aa", f);
        JurOracle.assertSameFind("(?>a+)b", "aab", f);
        JurOracle.assertSameFind("(?>a{1,2})a", "aa", f);
        JurOracle.assertSameFind("(?>a{1,2})b", "aab", f);
        // Mid-pattern commit
        JurOracle.assertSameFind("a(?>b|bc)d", "abcd", f);
        JurOracle.assertSameFind("(?>a(?>b|bc)c|d)", "abcd", f);
        JurOracle.assertSameFind("(?>a(?>b|bc)c|d)x", "abcd", f);
        // Zero-width-capable body
        JurOracle.assertSameFind("(?>a*)b", "ab", f);
        JurOracle.assertSameFind("(?>a?)b", "b", f);
        JurOracle.assertSameFind("(?>a?)b", "ab", f);
        // Captures through the lock report normally
        Matcher jt = jur("(?>(a+))b", "aab", 0);
        PatternMatcher ot = io.github.jemmix.tdfa.Pattern.compile("(?>(a+))b", 0, f).matcher("aab");
        assertThat(ot.find()).isEqualTo(jt.find()).isTrue();
        assertThat(ot.group(1)).isEqualTo(jt.group(1)).isEqualTo("aa");
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void re2jFacadeRejectsAtomicGroups(RegexEngineFactory factory) {
        assertSameCompileReject("(?>a)", factory);
        assertSameCompileReject("a(?>b|bc)d", factory);
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
