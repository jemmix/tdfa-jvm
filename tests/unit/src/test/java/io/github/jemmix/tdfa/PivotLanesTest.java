package io.github.jemmix.tdfa;

import io.github.jemmix.tdfa.asm.TdfaAsmBackend;
import io.github.jemmix.tdfa.core.budget.Budgets;
import io.github.jemmix.tdfa.core.budget.WorkMeter;
import io.github.jemmix.tdfa.core.compile.CompileOptions;
import io.github.jemmix.tdfa.core.compile.TDFAPattern;
import io.github.jemmix.tdfa.core.determinize.Determinizer;
import io.github.jemmix.tdfa.core.dfa.Tdfa;
import io.github.jemmix.tdfa.core.dfa.TdfaRunner;
import io.github.jemmix.tdfa.core.engine.RegexEngine;
import io.github.jemmix.tdfa.core.engine.WholeEngine;
import io.github.jemmix.tdfa.core.parser.PatternSyntaxException;
import io.github.jemmix.tdfa.core.report.CompileObserver;
import io.github.jemmix.tdfa.core.tnfa.Semantics;
import io.github.jemmix.tdfa.core.tnfa.Tnfa;
import io.github.jemmix.tdfa.core.unicode.UnicodeProviders;
import org.junit.jupiter.api.Test;

import java.nio.CharBuffer;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WBS items "Parameterize the pivots" + "Fold + U bits" — the lane audit.
 * Every pivot (terminator set, EMPTY_LAST_LINE, END_OF_TEXT_ONLY,
 * CODEPOINT_BOUNDARIES, fold universe, {@code (?U)} meaning) is pinned in
 * BOTH selections across every rung × tier × input type:
 *
 * <ul>
 * <li><b>JUR lane</b> ({@link Semantics#of()}, every axis unset) against
 *     the LIVE {@code java.util.regex} oracle — the parity the lane
 *     promises, verified by construction rather than by transcribed
 *     expectations (the anchor rules themselves were re-verified against a
 *     live JDK when the design was written); bare-CI fold compares under
 *     plain java CASE_INSENSITIVE (both universes fold the 26 ASCII letter
 *     pairs and nothing else — İ/ı inert on both sides), and the
 *     {@code (?U)} reading against java's own spelling of scoped
 *     UNICODE_CASE (inline {@code (?u)});</li>
 * <li><b>RE2 lane</b> (every axis set — the re2j surface's
 *     lane since the flip) against the facade's own engines on the same
 *     battery: the explicit-lane core compile must answer exactly what
 *     re2j-surface compile answers;</li>
 * <li>axis-single hybrid lanes (UNIX_LINES added, EMPTY_LAST_LINE added,
 *     UNICODE_CASE added) for the separable effects the split bits exist
 *     for — the single-axis fold lane against java
 *     CASE_INSENSITIVE|UNICODE_CASE (İ/ı included: the jur surface's
 *     default fold universe is the java.util.regex reading since the
 *     evidence re-base; the re2j surface keeps the pair inert — pinned
 *     in jurFoldUniverseMergesTheTurkicIPairLikeJava); the unit-semantics
 *     contract hand-pinned for the same reason (java refuses surrogate
 *     units inside well-formed pairs entirely);</li>
 * <li>every case runs on the interpreter AND the ASM tier (identical
 *     protocol), over String and non-String CharSequence (StringBuilder /
 *     CharBuffer — the GENERIC rung), and the VM tier re-runs under every
 *     forced search rung (the fuzz campaign's cross-rung differential,
 *     compressed to the pivot battery).</li>
 * </ul>
 *
 * <p>The group-participation family (EMPTY_ITERATION_SPANS) is pinned in
 * {@code Family6BatteryTest} — the family-6 battery (both sub-families,
 * both tiers, the same rung × wrapper audit) since WBS items 4b/4c
 * landed. The facade's {@code CompileOptions.semantics} route — the
 * core-tier spelling of the lane selection — is pinned at the bottom
 * (fold, terminator set, and the (?U) v1 rejection through it).
 */
class PivotLanesTest {

    private static final Semantics RE2 = Semantics.of().unixLines().unicodeCase().codepointBoundaries().emptyLastLine()
        .endOfTextOnly().emptyIterationSpans().ungreedyU().spaceRe2();
    private static final Semantics JUR = Semantics.of();

    private static final String[] TERM_INPUTS = {"", "\n", "a\n", "a\r", "a\r\n", "a\u0085", "a\u2028", "a\u2029",
        "a\n\n", "a\r\r", "a\r\n\r\n", "a\r\nb", "a\rb", "a\nb", "ab\ncd\r\n"};

    private static final CharSequence[] TERM_WRAP(String[] ins) {
        return ins;
    }

    // ===== compile + protocol helpers =====

    private static Tdfa compileTdfa(String pattern, Semantics semantics) {
        WorkMeter ledger = new WorkMeter(Budgets.compileComputeTicks());
        Tnfa nfa =
            Tnfa.compile(pattern, false, false, false, semantics, UnicodeProviders.get(), CompileObserver.NONE, ledger);
        return Determinizer.compileWithWholeSide(nfa, false, CompileObserver.NONE, ledger.fork(0));
    }

    /**
     * F+I+M+R protocol string: from-managed find-iterate spans (the
     * manual-advance loop, identical on both engines), whole matches(),
     * and the mid-input restart probe.
     */
    private static String probe(RegexEngine eng, CharSequence in) {
        StringBuilder sb = new StringBuilder(48);
        int from = 0;
        boolean found = false;
        int n = 0;
        while (from <= in.length()) {
            var m = eng.match(in, from, null);
            if (m == null) {
                break;
            }
            if (!found) {
                sb.append("F=true ");
                found = true;
            }
            if (n < 8) {
                sb.append('[').append(m.start(0)).append("..").append(m.end(0)).append(')');
            }
            n++;
            from = m.end(0) == m.start(0) ? m.end(0) + 1 : m.end(0);
        }
        if (!found) {
            sb.append("F=false");
        }
        if (n > 8) {
            sb.append("(…x").append(n).append(')');
        }
        // M probe: the whole-exact surface (the facade's matches()); the
        // engine-level boolean matches() is documented-approximate over
        // pike-cut artifacts.
        boolean m;
        if (eng instanceof WholeEngine we) {
            m = we.matchWhole(in, null) != null;
        } else {
            m = eng.matches(in);
        }
        sb.append(m ? " M=true" : " M=false");
        var r = eng.match(in, in.length() / 2, null);
        sb.append(" R=").append(r == null ? "-" : "[" + r.start(0) + ".." + r.end(0) + ")");
        return sb.toString();
    }

    /** The same protocol driven over {@code java.util.regex}. */
    private static String javaProbe(Pattern p, CharSequence in) {
        StringBuilder sb = new StringBuilder(48);
        int from = 0;
        boolean found = false;
        int n = 0;
        while (from <= in.length()) {
            var m = p.matcher(in);
            if (!m.find(from)) {
                break;
            }
            if (!found) {
                sb.append("F=true ");
                found = true;
            }
            if (n < 8) {
                sb.append('[').append(m.start()).append("..").append(m.end()).append(')');
            }
            n++;
            from = m.end() == m.start() ? m.end() + 1 : m.end();
        }
        if (!found) {
            sb.append("F=false");
        }
        if (n > 8) {
            sb.append("(…x").append(n).append(')');
        }
        sb.append(p.matcher(in).matches() ? " M=true" : " M=false");
        var m2 = p.matcher(in);
        sb.append(" R=").append(m2.find(in.length() / 2) ? "[" + m2.start() + ".." + m2.end() + ")" : "-");
        return sb.toString();
    }

    /** The facade's own engines (a flags lane) on the same protocol. */
    private static String facadeProbe(String pattern, int flags, CharSequence in) {
        return patternProbe((TDFAPattern) io.github.jemmix.tdfa.Pattern.compile(pattern, flags), in);
    }

    /** The re2j surface's own engines (the lane baked in) on the same protocol. */
    private static String re2jProbe(String pattern, CharSequence in) {
        return patternProbe((TDFAPattern) io.github.jemmix.tdfa.re2j.Pattern.compile(pattern), in);
    }

    private static String patternProbe(TDFAPattern p, CharSequence in) {
        StringBuilder sb = new StringBuilder(48);
        int from = 0;
        boolean found = false;
        int n = 0;
        while (from <= in.length()) {
            var m = p.matcher(in);
            if (!m.find(from)) {
                break;
            }
            if (!found) {
                sb.append("F=true ");
                found = true;
            }
            if (n < 8) {
                sb.append('[').append(m.start()).append("..").append(m.end()).append(')');
            }
            n++;
            from = m.end() == m.start() ? m.end() + 1 : m.end();
        }
        if (!found) {
            sb.append("F=false");
        }
        if (n > 8) {
            sb.append("(\u2026x").append(n).append(')');
        }
        sb.append(p.matcher(in).matches() ? " M=true" : " M=false");
        var m2 = p.matcher(in);
        sb.append(" R=").append(m2.find(in.length() / 2) ? "[" + m2.start() + ".." + m2.end() + ")" : "-");
        return sb.toString();
    }

    /** Cross tier × wrapper × rung agreement, then the expected protocol. */
    private static void assertLane(String what, String pattern, Semantics semantics, String[] inputs,
        String[] expected) {
        assertThat(inputs.length).as("%s: battery sizes", what).isEqualTo(expected.length);
        Tdfa tdfa = compileTdfa(pattern, semantics);
        RegexEngine vm = new TdfaRunner(tdfa, 1 << 20);
        RegexEngine asm = TdfaAsmBackend.generate(tdfa, 1 << 20);
        for (int i = 0; i < inputs.length; i++) {
            String in = inputs[i];
            String vmStr = probe(vm, in);
            assertThat(probe(asm, in)).as("%s: ASM == VM on %s / %s", what, pattern, esc(in)).isEqualTo(vmStr);
            assertThat(vmStr).as("%s: %s on %s", what, pattern, esc(in)).isEqualTo(expected[i]);
            CharSequence sbW = new StringBuilder(in);
            CharSequence cbW = CharBuffer.wrap(in);
            assertThat(probe(vm, sbW)).as("%s: StringBuilder path on %s / %s", what, pattern, esc(in)).isEqualTo(vmStr);
            assertThat(probe(vm, cbW)).as("%s: CharBuffer path on %s / %s", what, pattern, esc(in)).isEqualTo(vmStr);
            for (TdfaRunner.Strategy f : new TdfaRunner.Strategy[]{TdfaRunner.Strategy.ORIGIN_SIM,
                TdfaRunner.Strategy.TRIGGER, TdfaRunner.Strategy.RAW_SCAN, TdfaRunner.Strategy.WALK_RESTART}) {
                String forced;
                try {
                    TdfaRunner.setForcedStrategy(f);
                    forced = probe(vm, in);
                } finally {
                    TdfaRunner.setForcedStrategy(null);
                }
                assertThat(forced).as("%s: forced %s on %s / %s", what, f, pattern, esc(in)).isEqualTo(vmStr);
            }
        }
    }

    /** Lane parity: same battery against the live java.util.regex oracle. */
    private static void assertJurParity(String what, String pattern, int jurFlags, String[] inputs) {
        assertOracleParity(what, pattern, jurFlags, JUR, inputs);
    }

    private static void assertOracleParity(String what, String pattern, int jurFlags, Semantics lane, String[] inputs) {
        assertDualOracleParity(what, pattern, pattern, jurFlags, lane, inputs);
    }

    /**
     * Dual-pattern lane parity: the lane pattern and the oracle pattern
     * spell the SAME reading in the two dialects — used for {@code (?U)}
     * (scoped Unicode-case in the lane; java's spelling of that reading is
     * inline {@code (?u)} or the UNICODE_CASE flag on the remainder).
     */
    private static void assertDualOracleParity(String what, String lanePattern, String oraclePattern, int jurFlags,
        Semantics lane, String[] inputs) {
        Tdfa tdfa = compileTdfa(lanePattern, lane);
        RegexEngine vm = new TdfaRunner(tdfa, 1 << 20);
        RegexEngine asm = TdfaAsmBackend.generate(tdfa, 1 << 20);
        Pattern oracle = Pattern.compile(oraclePattern, jurFlags);
        for (String in : inputs) {
            String vmStr = probe(vm, in);
            assertThat(probe(asm, in)).as("%s: ASM == VM on %s / %s", what, lanePattern, esc(in)).isEqualTo(vmStr);
            CharSequence sbW = new StringBuilder(in);
            CharSequence cbW = CharBuffer.wrap(in);
            assertThat(probe(vm, sbW)).as("%s: StringBuilder path on %s / %s", what, lanePattern, esc(in))
                .isEqualTo(vmStr);
            assertThat(probe(vm, cbW)).as("%s: CharBuffer path on %s / %s", what, lanePattern, esc(in))
                .isEqualTo(vmStr);
            for (TdfaRunner.Strategy f : new TdfaRunner.Strategy[]{TdfaRunner.Strategy.ORIGIN_SIM,
                TdfaRunner.Strategy.TRIGGER, TdfaRunner.Strategy.RAW_SCAN, TdfaRunner.Strategy.WALK_RESTART}) {
                String forced;
                try {
                    TdfaRunner.setForcedStrategy(f);
                    forced = probe(vm, in);
                } finally {
                    TdfaRunner.setForcedStrategy(null);
                }
                assertThat(forced).as("%s: forced %s on %s / %s", what, f, lanePattern, esc(in)).isEqualTo(vmStr);
            }
            assertThat(vmStr).as("%s: lane %s vs java.util.regex on %s / %s", what, lane, lanePattern, esc(in))
                .isEqualTo(javaProbe(oracle, in));
        }
    }

    private static String esc(String s) {
        return s.replace("\r", "\\r").replace("\n", "\\n").replace("\u0085", "\\N").replace("\u2028", "\\L")
            .replace("\u2029", "\\P");
    }

    // ===== anchors: JUR lane == java.util.regex, live oracle =====

    @Test
    void jurMultilineCaretMatchesJavaUtilRegex() {
        assertJurParity("JUR (?m)^", "(?m)^", Pattern.MULTILINE, TERM_INPUTS);
    }

    @Test
    void jurMultilineDollarMatchesJavaUtilRegex() {
        assertJurParity("JUR (?m)$", "(?m)$", Pattern.MULTILINE, TERM_INPUTS);
    }

    @Test
    void jurPlainDollarMatchesJavaUtilRegex() {
        assertJurParity("JUR $", "$", 0, TERM_INPUTS);
    }

    @Test
    void jurPlainCaretMatchesJavaUtilRegex() {
        assertJurParity("JUR ^", "^", 0, TERM_INPUTS);
    }

    @Test
    void jurConsumingAnchorShapesMatchJavaUtilRegex() {
        String[] shapes = {"a$", "a\\z", "(?m)a$", "a$b", "^a", "(?m)^a", "(?m)a$", "x*$", ".\\$$", "\\A.", "(?m).+^"};
        String[] inputs =
            {"", "\n", "a\n", "a\r", "a\r\n", "a\u0085", "a\n\n", "a\r\nb", "ab\n", "ab\ncd\r\n", "b\na", "a$b"};
        for (String shape : shapes) {
            int fl = shape.startsWith("(?m)") ? Pattern.MULTILINE : 0;
            assertJurParity("JUR " + shape, shape, fl, inputs);
        }
    }

    @Test
    void jurFinalEndComposesUnderAlternation() {
        // FINAL_END under alternation/loops exercises the 128-cell stop and
        // φ tables (mask-split accepting states).
        String[] shapes = {"a$|ab", "(?:$|a)*", "(?m)^|$", "(a$)?b", "a(?:$|\\n)"};
        String[] inputs = {"", "a\n", "ab", "ab\n", "a\r\n", "b", "\n"};
        for (String shape : shapes) {
            int fl = shape.startsWith("(?m)") ? Pattern.MULTILINE : 0;
            assertJurParity("JUR " + shape, shape, fl, inputs);
        }
    }

    // ===== UNIX_LINES axis: JUR lane + the bit == java + UNIX_LINES =====

    @Test
    void unixLinesAxisMatchesJavaUtilRegexUnixLines() {
        String[] pats = {"(?m)^", "(?m)$", "$", ".", "^", "a$", "(?m)."};
        String[] inputs =
            {"", "\n", "a\n", "a\r", "a\r\n", "a\r\n\r\n", "a\r\nb", "a\rb", "\r", "a\u0085", "ab\ncd\r\n"};
        for (String pat : pats) {
            int fl = Pattern.UNIX_LINES | (pat.startsWith("(?m)") ? Pattern.MULTILINE : 0);
            assertOracleParity("JUR+UNIX_LINES " + pat, pat, fl, JUR.unixLines(), inputs);
        }
    }

    // ===== DOT: the parser-side pivot, oracle-driven =====

    @Test
    void jurDotMatchesJavaUtilRegex() {
        String[] inputs = {"", "\n", "\r", "\r\n", "\u0085", "\u2028", "\u2029", "a", "a\rb", "a\r\nb", "\r\nx",
            "\uD83D\uDE00", "\uD83D\uDE00a", "a\uD83D\uDE00"};
        assertJurParity("JUR .", ".", 0, inputs);
        assertJurParity("JUR (?s).", "(?s).", Pattern.DOTALL, inputs);
    }

    // ===== \b wordness: discipline unchanged per lane (oracle per lane) =====

    @Test
    void wordnessMatchesJavaUtilRegexInBothLanes() {
        String mathX = "\uD835\uDD4F"; // U+1D54F: a Unicode word char under (?u)
        String emoji = "\uD83D\uDE00"; // not a word char
        String lone = "a\uD800b"; // a lone high surrogate
        String[] inputs = {mathX + "a", "a" + mathX, emoji + "a", "a" + emoji, mathX + mathX, lone, "ab", "a b"};
        // plain \b: per-unit wordness — both lanes agree with java's default
        for (Semantics lane : new Semantics[]{JUR, RE2}) {
            assertOracleParity(lane + " \\b", "\\ba|a\\b|\\Bb", 0, lane, inputs);
        }
        // (?u) \b: codepoint-decoded wordness — UNICODE_CHARACTER_CLASS
        assertOracleParity("JUR (?u)\\b", "(?u)\\ba|(?u)a\\b|(?u)\\Bb", Pattern.UNICODE_CHARACTER_CLASS, JUR, inputs);
        assertOracleParity("RE2 (?u)\\b", "(?u)\\ba|(?u)a\\b|(?u)\\Bb", Pattern.UNICODE_CHARACTER_CLASS, RE2, inputs);
    }

    // ===== fold universe: JUR lane == java.util.regex, live oracle =====

    @Test
    void jurBareCiFoldsAsciiLikeJavaUtilRegex() {
        // Bare CI, the JUR-lane reading: ASCII-only fold — the 26 letter
        // pairs and nothing else. ſ, K(U+212A), İ and ı are all inert
        // (İ/ı SAFE in this battery: neither universe folds them without
        // UNICODE_CASE, so even the Turkic pair agrees lane vs oracle).
        String[] inputs = {"s", "S", "\u017F", "k", "K", "\u212A", "i", "I", "\u0130", "\u0131", "ab", "AB", "Ab",
            "Stra\u00dfe", "STRASSE", "\u00e9", "\u00c9", "ss"};
        for (String pat : new String[]{"(?i)s", "(?i)k", "(?i)i", "(?i)[a-z]+", "(?i)[^s]", "(?i)[^k]", "(?i)\\w",
            "(?i)Stra\u00dfe", "(?i)[\u00e0-\u00ff]+"}) {
            assertJurParity("JUR " + pat, pat, 0, inputs);
        }
    }

    @Test
    void unicodeCaseAxisFoldsTheFullUniverseLikeJavaCiUnicodeCase() {
        // The single-axis fold lane (everything else JUR): CI folds the
        // full simple-fold universe, java's CASE_INSENSITIVE|UNICODE_CASE.
        // İ/ı INCLUDED since the evidence re-base: the jur surface's
        // default fold universe is the java.util.regex reading (the pair
        // merged into the i-orbit) while the re2j surface keeps the
        // RE2-lineage reading (inert) — pinned surface-split in
        // jurFoldUniverseMergesTheTurkicIPairLikeJava below.
        Semantics lane = JUR.unicodeCase();
        String[] inputs = {"s", "S", "\u017F", "k", "K", "\u212A", "i", "I", "\u0130", "\u0131", "\u03c3", "\u03c2",
            "\u03a3", "\u0442", "\u0422", "\u1c85", "\u00e9", "\u00c9", "ab", "AB", "Stra\u00dfe", "STRASSE"};
        for (String pat : new String[]{"(?i)s", "(?i)k", "(?i)i", "(?i)[a-z]+", "(?i)[^s]", "(?i)[^k]", "(?i)[^i]",
            "(?i)Stra\u00dfe", "(?i)\u03c3", "(?i)\u0442", "(?i)[\u00e0-\u00ff]+", "(?i)[\u03b1-\u03c9]",
            "(?i)[i\u0131]"}) {
            assertOracleParity("JUR+UNICODE_CASE " + pat, pat, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE, lane,
                inputs);
        }
    }

    @Test
    void jurUccWidensTheFoldUniverseLikeJavaUcc() {
        // JDK-verified (the campaign found it): UNICODE_CHARACTER_CLASS
        // IMPLIES Unicode-aware CI — java under CI|UCC folds the full
        // universe exactly like CI|UNICODE_CASE, so tdfa's (?u) widens
        // the fold universe in the JUR lane too. İ/ı included as above
        // (the java-reading default fold universe).
        String[] inputs = {"s", "S", "\u017F", "k", "K", "\u212A", "i", "I", "\u0130", "\u0131", "\u03c3", "\u03c2",
            "\u03a3", "\u0442", "\u0422", "\u1c85", "\u1c84", "\u00e9", "\u00c9", "ab", "AB"};
        for (String pat : new String[]{"(?u)(?i)k", "(?u)(?i)s", "(?u)(?i)i", "(?u)(?i)[a-z]+", "(?u)(?i)[^s]",
            "(?u)(?i)[^i]", "(?i)(?u)ks", "(?u)(?i)\u0442", "(?u)(?i)[\u03b1-\u03c9]", "(?u)((?i)k)"}) {
            assertOracleParity("JUR (?u)CI " + pat, pat, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS,
                JUR, inputs);
        }
        // scoped the other way: CI outside, (?u) inside a group — the
        // upgrade is group-scoped, the outer atoms fold ASCII
        String[] midInputs = {"kK", "K\u017F", "k\u017F", "\u212Ak", "ks", "\u017Fs"};
        assertOracleParity("JUR (?i)((?u)k)s", "(?i)((?u)k)s", 0, JUR, midInputs);
    }

    // ===== the İ/ı fold split: each surface its oracle's orbit =====

    @Test
    void jurFoldUniverseMergesTheTurkicIPairLikeJava() {
        // The evidence re-base İ/ı fix: the JUR lane's full fold universe
        // is the java.util.regex reading (İ/ı merged into the i-orbit
        // under UNICODE_CASE / UCC / a leading (?U) upgrade), the re2j
        // surface's the RE2-lineage reading (inert — what its live oracle
        // answers). Bare CI stays ASCII in both (İ/ı inert everywhere).
        String[] uccInputs = {"\u0130", "\u0131", "i", "I"};
        assertOracleParity("JUR (?iu)i", "(?iu)i", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS, JUR,
            uccInputs);
        assertOracleParity("JUR (?iu)\\u0130", "(?iu)\u0130", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE,
            JUR.unicodeCase(), uccInputs);
        assertDualOracleParity("JUR (?U)(?i)i", "(?U)(?i)i", "i", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE, JUR,
            uccInputs);
        // the facade's own default compile folds the same way
        io.github.jemmix.tdfa.Pattern jurUcc = io.github.jemmix.tdfa.Pattern.compile("i",
            io.github.jemmix.tdfa.Pattern.CASE_INSENSITIVE | io.github.jemmix.tdfa.Pattern.UNICODE_CHARACTER_CLASS);
        assertThat(jurUcc.matcher("\u0130").find()).as("facade CI|UCC folds İ into the i-orbit").isTrue();
        assertThat(jurUcc.matcher("\u0131").find()).as("facade CI|UCC folds ı into the i-orbit").isTrue();
        // ... and the re2j surface keeps the pair inert under its full fold
        io.github.jemmix.tdfa.re2j.Pattern re2Ci = io.github.jemmix.tdfa.re2j.Pattern.compile("(?i)i");
        assertThat(re2Ci.matcher("\u0130").find()).as("re2j surface plain (?i) keeps İ inert").isFalse();
        assertThat(re2Ci.matcher("\u0131").find()).as("re2j surface plain (?i) keeps ı inert").isFalse();
    }

    @Test
    void jurCiRangesFoldByTheJavaImageTestNotOrbitClosure() {
        // The campaign 2026-10-16 finding-1 fix: java.util.regex folds
        // WRITTEN ranges by the two-sided simple-case image test — the
        // input's images {self, toLower, toUpper, toLower∘toUpper} against
        // the RAW range, plus the member-side mirror (c ∈ IMG(m) for a
        // member m) — NOT by closing every member's fold orbit (which is
        // what single members and literals use; live-JDK probed). The
        // asymmetric-mapping families discriminate: İ satisfies [h-k]
        // input-side (toLower(İ)=i) but NOT [H-K], and the degenerate
        // [I-I] is a RANGE and rejects İ while the single [I] admits it.
        // ı satisfies both sides (toUpper(ı)=I, toLower∘toUpper(ı)=i); the
        // Kelvin sign mirrors İ ([j-l] yes, [J-L] no); ſ and the OHM sign
        // satisfy both sides; final sigma reaches [Σ-Σ] through
        // toLower∘toUpper; and i matches [İ-İ9] member-side (i ∈ IMG(İ)).
        // JDK 26 adds the CLOSING-characters clause (its internal
        // CaseFolding table): a member with an EXPANDED case relation
        // admits the case-relatives of its expansion target DIRECTIONALLY
        // — OHM SIGN inside a range closes CAPITAL OMEGA in though
        // neither image of Ω lands ([1c84-dbff] matches Ω), while [Ω-Ω]
        // does NOT admit OHM back. The provider discovers the entry table
        // from the live oracle once (probing every fold-orbit candidate
        // the image test would miss), so the cells below pin the whole
        // clause — the OHM/Kelvin/Angstrom/micro/Historic-Cyrillic
        // families and the fuzz-found shape included.
        String[] inputs = {"\u0130", "\u0131", "i", "I", "\u017F", "s", "S", "\u212A", "k", "K", "\u2126", "\u03C9",
            "\u03A3", "\u03C2", "\u03C3", "\u03A9", "\u00B5", "\u03BC", "\u1C80", "\u1C85", "\u0432", "\u0442"};
        for (String pat : new String[]{"(?iu)[h-k]", "(?iu)[H-K]", "(?iu)[g-j]", "(?iu)[G-J]", "(?iu)[i]", "(?iu)[I]",
            "(?iu)[I-I]", "(?iu)[i-i]", "(?iu)[j-l]", "(?iu)[J-L]", "(?iu)[r-t]", "(?iu)[R-T]", "(?iu)[A-z]",
            "(?iu)[\u03B1-\u03C9]", "(?iu)[\u0391-\u03A9]", "(?iu)[\u03A3-\u03A3]", "(?iu)[\u03C3-\u03C3]",
            "(?iu)[\u0130-\u0139]", "(?iu)[\u0120-\u0131]", "(?iu)[^h-k]", "(?iu)[^H-K]", "(?iu)[^i]", "(?iu)[a-z]",
            "(?iu)[A-Z]", "(?iu)[\u2126-\u2126]", "(?iu)[\u03A9-\u03A9]", "(?iu)[\u03C9-\u03C9]",
            "(?iu)[\u1C84-\uDBFF]", "(?iu)[\u1C80-\u1C88]", "(?iu)[\u0412-\u0432]", "(?iu)[k-\u212A]",
            "(?iu)[\u00B5-\u00B5]", "(?iu)[\u0386-\u03CE]"}) {
            assertOracleParity("JUR " + pat, pat, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE, JUR, inputs);
        }
        // the UCC-widened reading (the fuzz lane's flag combo; (?u) is the
        // lane's spelling of the compile flag — see the battery above)
        for (String pat : new String[]{"(?u)(?i)[h-k]", "(?u)(?i)[H-K]", "(?u)(?i)[I-I]", "(?u)(?i)[i]"}) {
            assertOracleParity("JUR CI|UCC " + pat, pat, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS,
                JUR, inputs);
        }
        // ... and the facade's own compile-flag spelling of the same combo
        int ciUcc =
            io.github.jemmix.tdfa.Pattern.CASE_INSENSITIVE | io.github.jemmix.tdfa.Pattern.UNICODE_CHARACTER_CLASS;
        io.github.jemmix.tdfa.Pattern lowRange = io.github.jemmix.tdfa.Pattern.compile("[h-k]", ciUcc);
        assertThat(lowRange.matcher("\u0130").find()).as("facade CI|UCC [h-k] matches İ (toLower image)").isTrue();
        io.github.jemmix.tdfa.Pattern upRange = io.github.jemmix.tdfa.Pattern.compile("[H-K]", ciUcc);
        assertThat(upRange.matcher("\u0130").find()).as("facade CI|UCC [H-K] rejects İ (no image / no member IMG)")
            .isFalse();
        io.github.jemmix.tdfa.Pattern degenerate = io.github.jemmix.tdfa.Pattern.compile("[I-I]", ciUcc);
        assertThat(degenerate.matcher("\u0130").find()).as("facade CI|UCC [I-I] rejects İ (degenerate = range)")
            .isFalse();
        // the re2j surface keeps the orbit closure for ranges (its oracle's
        // reading; İ fold-inert under the patched fork — the surface split
        // this battery pins the other side of)
        io.github.jemmix.tdfa.re2j.Pattern re2Range = io.github.jemmix.tdfa.re2j.Pattern.compile("(?i)[H-K]");
        assertThat(re2Range.matcher("\u0130").find()).as("re2j surface (?i)[H-K] keeps İ inert").isFalse();
        assertThat(re2Range.matcher("\u0131").find()).as("re2j surface (?i)[H-K]: ı inert too").isFalse();
        io.github.jemmix.tdfa.re2j.Pattern re2RangeLow = io.github.jemmix.tdfa.re2j.Pattern.compile("(?i)[h-k]");
        assertThat(re2RangeLow.matcher("\u0130").find()).as("re2j surface (?i)[h-k] keeps İ inert").isFalse();
    }

    // ===== (?U): the java.util.regex reading — scoped Unicode-case =====

    @Test
    void jurScopedUUpgradesTheFoldUniverse() {
        // java's own spelling of scoped UNICODE_CASE is inline (?u) (and
        // the flag for a whole-pattern prefix) — dual-pattern oracle.
        String[] inputs = {"k", "K", "\u212A", "aK", "ab", "ks", "Ks", "kS", "\u017F", "k\u017F", "\u212Ak", "s\u017F"};
        assertDualOracleParity("JUR (?U)(?i)k...", "(?U)(?i)ks", "(?u)(?i)ks", 0, JUR, inputs);
        assertDualOracleParity("JUR (?U) prefix via flags", "(?U)(?i)ks", "ks",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE, JUR, inputs);
        // point-forward scoping: atoms BEFORE the upgrade keep the ASCII
        // universe, atoms after it fold full
        String[] midInputs = {"ks", "Ks", "kS", "k\u017F", "\u017Fs", "KS", "kk"};
        assertDualOracleParity("JUR (?i)k(?U)s", "(?i)k(?U)s", "(?i)k(?u)s", 0, JUR, midInputs);
        // degenerate no-op: (?U) without CI folds nothing
        assertDualOracleParity("JUR (?U)k no CI", "(?U)k", "k", 0, JUR, midInputs);
    }

    @Test
    void v1ScopedURejectionsUnderTheJurReading() {
        // v1: top-level positive flag-only (?U) alone. The colon form, a
        // negated (?-U), and any use inside a group (including inside
        // another flag group's body) reject with PatternSyntaxException.
        for (String pat : new String[]{"(?U:a)", "(?U:a+)", "((?U)a)", "(a(?U)b)", "(?i:(?U)a)", "(?i-U)a", "(?-U)a",
            "((?U:a))"}) {
            assertThatThrownBy(() -> compileTdfa(pat, JUR)).as("JUR lane rejects /%s/", pat)
                .isInstanceOf(PatternSyntaxException.class);
        }
        // the legal spellings compile under the JUR reading ...
        for (String pat : new String[]{"(?U)a", "(?U)(?i)a", "a|(?U)b", "(?U-s)a", "(?Ui)a", "(?U)a|b"}) {
            assertThatCode(() -> compileTdfa(pat, JUR)).as("JUR lane accepts /%s/", pat).doesNotThrowAnyException();
        }
        // ... and the RE2 reading (the re2j surface lane) keeps every
        // spelling as scoped ungreedy
        for (String pat : new String[]{"(?U:a)", "((?U)a)", "(?i-U)a", "(?U)a+"}) {
            assertThatCode(() -> compileTdfa(pat, RE2)).as("RE2 lane accepts /%s/", pat).doesNotThrowAnyException();
        }
    }

    // ===== hybrid lanes no JDK flag can express: hand-pinned =====

    @Test
    void emptyLastLineAxisAloneRestoresTheRe2Caret() {
        // JUR + EMPTY_LAST_LINE: full terminator set, but (?m)^ MAY match the
        // empty last line after a trailing terminator. No JDK equivalent
        // (java never matches there under any flag); re2j = \n-only + ELL.
        assertLane("JUR+ELL (?m)^", "(?m)^", JUR.emptyLastLine(),
            new String[]{"a\n", "a\r", "a\r\n", "a\n\n", "a\r\n\r\n", "a\r\nb", "a\rb"},
            new String[]{"F=true [0..0)[2..2) M=false R=[2..2)", // pos 2 after final \n: ELL admits it
                "F=true [0..0)[2..2) M=false R=[2..2)", // after final \r
                "F=true [0..0)[3..3) M=false R=[3..3)", // after the final \r\n (interior 2 still excluded)
                "F=true [0..0)[2..2)[3..3) M=false R=[2..2)", "F=true [0..0)[3..3)[5..5) M=false R=[3..3)",
                "F=true [0..0)[3..3) M=false R=[3..3)", "F=true [0..0)[2..2) M=false R=[2..2)"});
    }

    @Test
    void jurFlippedPatternsScanCodepointAligned() {
        // The scan pivot: a JUR-lane pattern that flipped java's
        // hasSupplementary (any surrogate unit in the source — the classes
        // and literals below) enumerates candidate starts codepoint-aligned
        // exactly like java's StartS: pair interiors are never candidates,
        // so no match starts inside a well-formed pair (live-JDK pinned;
        // the retired unit-semantics family). An unflipped pattern (the
        // plain BMP literal) keeps unit enumeration — irrelevant for it,
        // its matches sit on codepoint boundaries anyway.
        String pair = "\uD83D\uDE00";
        assertLane("JUR [\\uD800-\\uDFFF]", "[\uD800-\uDFFF]", JUR, new String[]{pair, "a" + pair, pair + "b"},
            new String[]{"F=false M=false R=[1..2)", "F=false M=false R=-", "F=false M=false R=[1..2)"});
        assertLane("RE2 [\\uD800-\\uDFFF]", "[\uD800-\uDFFF]", RE2, new String[]{pair, "a" + pair},
            new String[]{"F=false M=false R=[1..2)", "F=false M=false R=-"});
        // literal needle path (the LITERAL strategy's indexOf): the lone-low
        // hit inside the pair is refused — the pattern flipped, interiors
        // are never candidates (the RE2 lane agrees, unconditionally)
        assertLane("JUR \\uDE00", "\uDE00", JUR, new String[]{pair, "a" + pair},
            new String[]{"F=false M=false R=[1..2)", "F=false M=false R=-"});
        assertLane("RE2 \\uDE00", "\uDE00", RE2, new String[]{pair, "a" + pair},
            new String[]{"F=false M=false R=[1..2)", "F=false M=false R=-"});
        // consuming shape anchored by a later char: the interior start is
        // never a candidate under the flipped scan
        assertLane("JUR [\\uDC00-\\uDFFF]b", "[\uDC00-\uDFFF]b", JUR, new String[]{pair + "b"},
            new String[]{"F=false M=false R=[1..3)"});
        assertLane("RE2 [\\uDC00-\\uDFFF]b", "[\uDC00-\uDFFF]b", RE2, new String[]{pair + "b"},
            new String[]{"F=false M=false R=[1..3)"});
        // literal prefix scan path (the PREFIX strategy) over an unflipped
        // pattern: unchanged — the hit sits on a codepoint boundary
        assertLane("JUR abx", "abx", JUR, new String[]{"\uD83D\uDE00abx", "abx"},
            new String[]{"F=true [2..5) M=false R=[2..5)", "F=true [0..3) M=true R=-"});
        assertLane("RE2 abx", "abx", RE2, new String[]{"\uD83D\uDE00abx"},
            new String[]{"F=true [2..5) M=false R=[2..5)"});
    }

    // ===== RE2 lane: the re2j surface's preset is untouched =====

    @Test
    void re2ExplicitLaneMatchesTheRe2jSurface() {
        // Post-split the re2j surface bakes the lane in, so an explicit
        // RE2-lane core compile must answer exactly what the surface
        // compile answers on the pivot battery (the re2j parity suites
        // pin the same lane against live re2j). Fold and (?U) included:
        // plain (?i) folds the full universe, (?U) spells ungreedy — the
        // lane-unchanged contract on the two parser-side axes.
        String[] pats = {"(?m)^", "(?m)$", "$", "^", ".", "a$", "a\\z", "(?m)a$", "x*$", "a$|ab", "\\ba", "(?u)\\ba",
            "[\uD800-\uDFFF]", "\uDE00", "(?i)k", "(?i)[^s]", "(?i)Stra\u00dfe", "(?i)[a-z]+", "(?U)a+", "(?U:a+)b",
            "(?U)a+?b", "((?U)a)+"};
        String[] inputs = {"", "\n", "a\n", "a\r", "a\r\n", "a\u0085", "a\n\n", "a\r\n\r\n", "a\r\nb", "a\rb", "a\nb",
            "ab\ncd\r\n", "\uD83D\uDE00", "a\uD83D\uDE00b", "\uD835\uDD4Fa", "k", "K", "\u212A", "s", "\u017F",
            "a\u212A", "s\u017F", "Stra\u00dfe", "STRASSE", "aaa", "aab", "ab"};
        for (String pat : pats) {
            Tdfa tdfa = compileTdfa(pat, RE2);
            RegexEngine vm = new TdfaRunner(tdfa, 1 << 20);
            RegexEngine asm = TdfaAsmBackend.generate(tdfa, 1 << 20);
            for (String in : inputs) {
                String want = re2jProbe(pat, in);
                assertThat(probe(vm, in)).as("RE2 core == facade: %s / %s", pat, esc(in)).isEqualTo(want);
                assertThat(probe(asm, in)).as("RE2 ASM == facade: %s / %s", pat, esc(in)).isEqualTo(want);
            }
        }
    }

    // ===== the core tier's lane defaults survive the split =====

    @Test
    void coreLegacyOverloadsKeepTheRe2LaneDefault() {
        // The core pipeline's legacy overloads default to the RE2 lane
        // (the lineage the engine descends from); the surfaces map or
        // bake their lane instead, and Semantics is a value class.
        Tnfa nfa = Tnfa.compile("(?i)a+");
        assertThat(nfa.semantics).isEqualTo(RE2);
        assertThat(Determinizer.compile(nfa).semantics()).isEqualTo(RE2);
        Tnfa explicit = Tnfa.compile("(?i)a+", false, false, false, RE2, UnicodeProviders.get(), null,
            new WorkMeter(Budgets.compileComputeTicks()));
        assertThat(explicit.semantics).isEqualTo(RE2);
        assertThat(explicit.semantics.isUnixLines()).isTrue();
        assertThat(Semantics.of()).isNotEqualTo(RE2);
        Semantics once = Semantics.of().unixLines();
        assertThat(once.unixLines()).isSameAs(once);
        assertThat(RE2.toString()).contains("UNIX_LINES").contains("UNGREEDY_U");
        assertThat(Semantics.of().toString()).isEqualTo("Semantics[]");
    }

    // ===== the facade options route: the core-tier lane spelling =====

    @Test
    void compileOptionsSemanticsSelectsTheLane() {
        // CompileOptions.semantics(Semantics) — the core-tier spelling,
        // honored by the facade options route. The fold axis:
        io.github.jemmix.tdfa.Pattern jur =
            io.github.jemmix.tdfa.Pattern.compile("(?i)k", CompileOptions.of().semantics(Semantics.of()));
        assertThat(jur.matcher("\u212A").find()).as("JUR-lane facade folds ASCII only").isFalse();
        assertThat(jur.matcher("K").find()).as("JUR-lane facade folds the ASCII pair").isTrue();
        io.github.jemmix.tdfa.Pattern ucc =
            io.github.jemmix.tdfa.Pattern.compile("(?i)k", CompileOptions.of().semantics(Semantics.of().unicodeCase()));
        assertThat(ucc.matcher("\u212A").find()).as("single-axis fold lane folds full").isTrue();
        io.github.jemmix.tdfa.Pattern deft = io.github.jemmix.tdfa.Pattern.compile("(?i)k", CompileOptions.of());
        assertThat(deft.matcher("\u212A").find()).as("default options are the JUR lane (ASCII fold)").isFalse();
        io.github.jemmix.tdfa.Pattern re2 = io.github.jemmix.tdfa.Pattern.compile("(?i)k",
            CompileOptions.of().semantics(Semantics.of().unixLines().unicodeCase().codepointBoundaries().emptyLastLine()
                .endOfTextOnly().emptyIterationSpans().ungreedyU().spaceRe2()));
        assertThat(re2.matcher("\u212A").find()).as("all-axes options fold full").isTrue();
        // UNIX_LINES through the same route activates the item-2
        // terminator-set pivot — the bit's flags spelling composes the
        // same way.
        io.github.jemmix.tdfa.Pattern nl =
            io.github.jemmix.tdfa.Pattern.compile(".", CompileOptions.of().semantics(Semantics.of().unixLines()));
        assertThat(nl.matcher("\r").find()).as("unixLines lane: dot skips \\n only").isTrue();
        io.github.jemmix.tdfa.Pattern jurDot =
            io.github.jemmix.tdfa.Pattern.compile(".", CompileOptions.of().semantics(Semantics.of()));
        assertThat(jurDot.matcher("\r").find()).as("JUR lane: dot skips the full terminator set").isFalse();
        // the (?U) reading through the same route: v1 rejection and the
        // scoped fold upgrade
        assertThatThrownBy(
            () -> io.github.jemmix.tdfa.Pattern.compile("(?U:a)", CompileOptions.of().semantics(Semantics.of())))
            .as("facade options route: (?U:...) rejects under the JUR reading")
            .isInstanceOf(PatternSyntaxException.class);
        io.github.jemmix.tdfa.Pattern up =
            io.github.jemmix.tdfa.Pattern.compile("(?U)(?i)k", CompileOptions.of().semantics(Semantics.of()));
        assertThat(up.matcher("\u212A").find()).as("facade options route: (?U) upgrades the fold universe").isTrue();
        assertThat(up.matcher("\u017F").find()).as("facade options route: (?U) upgrade is literal-scoped").isFalse();
    }
}
