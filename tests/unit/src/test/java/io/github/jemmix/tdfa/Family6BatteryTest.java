package io.github.jemmix.tdfa;

import io.github.jemmix.tdfa.asm.TdfaAsmBackend;
import io.github.jemmix.tdfa.core.budget.Budgets;
import io.github.jemmix.tdfa.core.budget.WorkMeter;
import io.github.jemmix.tdfa.core.compile.CompileOptions;
import io.github.jemmix.tdfa.core.determinize.Determinizer;
import io.github.jemmix.tdfa.core.dfa.Tdfa;
import io.github.jemmix.tdfa.core.dfa.TdfaRunner;
import io.github.jemmix.tdfa.core.engine.MatchResult;
import io.github.jemmix.tdfa.core.engine.RegexEngine;
import io.github.jemmix.tdfa.core.engine.WholeEngine;
import io.github.jemmix.tdfa.core.report.CompileObserver;
import io.github.jemmix.tdfa.core.tnfa.Semantics;
import io.github.jemmix.tdfa.core.tnfa.Tnfa;
import io.github.jemmix.tdfa.core.unicode.UnicodeProviders;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Family 6 (EMPTY_ITERATION_SPANS) spec + corpus — WBS item 4a. The
 * battery in {@code family6-battery.tsv} is the replayable expected-value
 * data: both sub-families &times; greedy/lazy/{@code ?}/{@code +}/
 * {@code {m,n}} &times; named groups &times; nested composition
 * ({@code ((a|\b)*)*}), each row carrying the JUR protocol (recorded from
 * a live JDK) and the RE2 protocol (recorded from the shipped engine, both
 * tiers agreeing). Three contracts:
 *
 * <ul>
 * <li><b>The data is the live JDK.</b> The recorded JUR column is
 *     re-verified against a live {@code java.util.regex} on every run —
 *     the spec source is the oracle itself, never a transcription.</li>
 * <li><b>The RE2 lane is pinned.</b> The shipped default answers exactly
 *     the recorded RE2 column on BOTH tiers (and CharSequence wrappers)
 *     — the RE2-lane-unchanged contract the 4b/4c fixes must keep.</li>
 * <li><b>The JUR-lane assertions are LIVE</b> for both sub-families
 *     (WBS 4b: the parse mark + SET_NIL finals suppression for A; 4c:
 *     the determinizer cut detection + SET_POS-at-accept surfacing for
 *     B) — every row on both tiers against the recorded JUR
 *     column.</li>
 * </ul>
 */
class Family6BatteryTest {

    /** WBS 4b: LIVED — the sub-family A suppression (parse mark + SET_NIL φ). */
    private static final boolean SUB_FAMILY_A_LIVE = true;

    /** WBS 4c: LIVED — the determinizer cut detection + SET_POS-at-accept override. */
    private static final boolean SUB_FAMILY_B_LIVE = true;

    record Row(String id, boolean subA, String pattern, String input, String jur, String re2) {
    }

    private static final Semantics RE2 = Semantics.of().unixLines().unicodeCase().codepointBoundaries().emptyLastLine()
        .endOfTextOnly().emptyIterationSpans().ungreedyU();

    private static final Semantics JUR = Semantics.of();

    static Stream<Row> rows() {
        return battery().stream();
    }

    static Stream<Row> rowsA() {
        return battery().stream().filter(r -> r.subA);
    }

    static Stream<Row> rowsB() {
        return battery().stream().filter(r -> !r.subA);
    }

    private static List<Row> battery() {
        List<Row> out = new ArrayList<>();
        try (InputStream is = Family6BatteryTest.class.getResourceAsStream("/family6-battery.tsv");
            BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isEmpty() || line.charAt(0) == '#' || line.startsWith("id\t")) {
                    continue;
                }
                String[] f = line.split("\t", -1);
                assertThat(f.length).as("battery row fields: %s", line).isEqualTo(6);
                assertThat(f[1]).as("sub tag of %s", f[0]).isIn("A", "B");
                out.add(
                    new Row(f[0], f[1].equals("A"), f[2], f[3].replace("\\s", " ").replace("\\n", "\n"), f[4], f[5]));
            }
        } catch (IOException e) {
            throw new IllegalStateException("family6-battery.tsv unreadable", e);
        }
        assertThat(out).as("battery rows").isNotEmpty();
        return out;
    }

    // ===== 1. the data is the live JDK =====

    @ParameterizedTest
    @MethodSource("rows")
    void recordedJurColumnMatchesTheLiveJdk(Row r) {
        assertThat(javaProto(r.pattern, r.input)).as("%s: live JDK vs recorded JUR column /%s/", r.id, r.pattern)
            .isEqualTo(r.jur);
    }

    // ===== 2. the RE2 lane is pinned on both tiers =====

    @ParameterizedTest
    @MethodSource("rows")
    void re2LanePinsTheRecordedProtocol(Row r) {
        // the shipped facade IS the RE2 lane pre-flip; ASM (default) and
        // VM engine selections, plus the CharSequence wrappers
        io.github.jemmix.tdfa.Pattern asm = io.github.jemmix.tdfa.Pattern.compile(r.pattern);
        io.github.jemmix.tdfa.Pattern vm = io.github.jemmix.tdfa.Pattern.compile(r.pattern, 0, TdfaRunner::new);
        assertThat(facadeProto(asm, r.input)).as("%s: RE2 ASM tier vs recorded RE2 column /%s/", r.id, r.pattern)
            .isEqualTo(r.re2);
        assertThat(facadeProto(vm, r.input)).as("%s: RE2 VM tier vs recorded RE2 column /%s/", r.id, r.pattern)
            .isEqualTo(r.re2);
        assertThat(facadeProto(vm, new StringBuilder(r.input))).as("%s: RE2 VM StringBuilder /%s/", r.id, r.pattern)
            .isEqualTo(r.re2);
    }

    // ===== 3. the JUR-lane assertions — A live (4b), B dark until 4c =====

    @ParameterizedTest
    @MethodSource("rowsA")
    void jurLaneSubFamilyA(Row r) {
        if (!SUB_FAMILY_A_LIVE) {
            return; // WBS 4b: the SET_NIL suppression for dissolved groups
        }
        assertThat(laneProto(r.pattern, r.input, JUR)).as("%s: JUR lane (A) vs JDK /%s/", r.id, r.pattern)
            .isEqualTo(r.jur);
    }

    @ParameterizedTest
    @MethodSource("rowsB")
    void jurLaneSubFamilyB(Row r) {
        if (!SUB_FAMILY_B_LIVE) {
            return; // WBS 4c: surface the final zero-width iteration
        }
        assertThat(laneProto(r.pattern, r.input, JUR)).as("%s: JUR lane (B) vs JDK /%s/", r.id, r.pattern)
            .isEqualTo(r.jur);
    }

    // ===== the 4a interactions, sub-family A part (no JDK oracle exists:
    // these pin the DECIDED composition) =====

    @org.junit.jupiter.api.Test
    void subFamilyAUnderMultiValuedTagsAppendsNoParticipation() {
        // A dissolved group records NO participation (the single (-1,-1)
        // pair) on the JUR lane; the RE2 lane keeps today's protocol, and
        // an INNER capture of the dissolved pair keeps its participations.
        io.github.jemmix.tdfa.Pattern jur = io.github.jemmix.tdfa.Pattern.compile("(\\b)*",
            CompileOptions.of().semantics(Semantics.of()).multiValuedTags());
        PatternMatcher m = jur.matcher("a");
        assertThat(m.find()).as("JUR multi find").isTrue();
        assertThat(m.groupSpans(1)).as("JUR multi: dissolved appends nothing").containsExactly(-1, -1);
        io.github.jemmix.tdfa.Pattern re2 =
            io.github.jemmix.tdfa.Pattern.compile("(\\b)*", CompileOptions.of().multiValuedTags());
        PatternMatcher m2 = re2.matcher("a");
        assertThat(m2.find()).as("RE2 multi find").isTrue();
        assertThat(m2.groupSpans(1)).as("RE2 multi: the sole zero-width span participates").containsExactly(0, 0);
        io.github.jemmix.tdfa.Pattern nested = io.github.jemmix.tdfa.Pattern.compile("((\\b))*",
            CompileOptions.of().semantics(Semantics.of()).multiValuedTags());
        PatternMatcher m3 = nested.matcher("a");
        assertThat(m3.find()).as("JUR multi nested find").isTrue();
        assertThat(m3.groupSpans(1)).as("JUR multi: outer dissolved").containsExactly(-1, -1);
        assertThat(m3.groupSpans(2)).as("JUR multi: inner capture keeps its span").containsExactly(0, 0);
    }

    @org.junit.jupiter.api.Test
    void subFamilyAUnderLongestMatchComposes() {
        // The protocol applies to the reported accept: LONGEST_MATCH picks
        // WHICH accept wins, the dissolved pair still reports NIL.
        io.github.jemmix.tdfa.Pattern p = io.github.jemmix.tdfa.Pattern.compile("a(\\b)*",
            CompileOptions.of().semantics(Semantics.of()).longestMatch());
        PatternMatcher m = p.matcher("a");
        assertThat(m.matches()).as("JUR longest whole").isTrue();
        assertThat(m.start(1)).as("JUR longest: dissolved pair reports NIL").isEqualTo(-1);
        assertThat(m.end(1)).isEqualTo(-1);
        io.github.jemmix.tdfa.Pattern f = io.github.jemmix.tdfa.Pattern.compile("(\\b)*",
            CompileOptions.of().semantics(Semantics.of()).longestMatch());
        PatternMatcher fm = f.matcher("a");
        assertThat(fm.find()).isTrue();
        assertThat(fm.start(1)).as("JUR longest find: dissolved pair reports NIL").isEqualTo(-1);
    }

    @org.junit.jupiter.api.Test
    void subFamilyBUnderMultiValuedTagsAppendsTheParticipation() {
        // 4a decision: the surfaced final zero-width iteration appends a
        // participation — (a*)* on "aa" keeps BOTH spans, the single-value
        // read is the last pair; the RE2 lane keeps the maximal-iteration
        // protocol (one span).
        io.github.jemmix.tdfa.Pattern jur = io.github.jemmix.tdfa.Pattern.compile("(a*)*",
            CompileOptions.of().semantics(Semantics.of()).multiValuedTags());
        PatternMatcher m = jur.matcher("aa");
        assertThat(m.matches()).as("JUR multi whole").isTrue();
        assertThat(m.groupSpans(1)).as("JUR multi: both iterations participate").containsExactly(0, 2, 2, 2);
        assertThat(m.start(1)).as("JUR multi: single value = last pair").isEqualTo(2);
        io.github.jemmix.tdfa.Pattern re2 =
            io.github.jemmix.tdfa.Pattern.compile("(a*)*", CompileOptions.of().multiValuedTags());
        PatternMatcher m2 = re2.matcher("aa");
        assertThat(m2.matches()).as("RE2 multi whole").isTrue();
        assertThat(m2.groupSpans(1)).as("RE2 multi: today's protocol unchanged").containsExactly(0, 2);
    }

    @org.junit.jupiter.api.Test
    void subFamilyBUnderLongestMatchComposes() {
        // Same composition as sub-family A: the surfaced iteration is the
        // one at the WINNING (longest) accept's end position.
        io.github.jemmix.tdfa.Pattern p = io.github.jemmix.tdfa.Pattern.compile("(a*)*",
            CompileOptions.of().semantics(Semantics.of()).longestMatch());
        PatternMatcher m = p.matcher("aa");
        assertThat(m.matches()).as("JUR longest whole").isTrue();
        assertThat(m.start(1)).as("JUR longest: surfaced iteration at the accept").isEqualTo(2);
        assertThat(m.end(1)).isEqualTo(2);
        PatternMatcher f = p.matcher("aab");
        assertThat(f.find()).isTrue();
        assertThat(f.group()).as("JUR longest find: leftmost-longest").isEqualTo("aa");
        assertThat(f.start(1)).isEqualTo(2);
        assertThat(f.end(1)).isEqualTo(2);
    }

    // ===== protocol helpers (mirror the battery recording format) =====

    private static String javaProto(String pattern, String input) {
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(pattern);
        Matcher mm = p.matcher(input);
        String m = mm.matches() ? spans(mm) : "NONE";
        Matcher f = p.matcher(input);
        String fs = f.find(0) ? spans(f) : "NONE";
        Matcher rr = p.matcher(input);
        String rs = rr.find(input.length() / 2) ? spans(rr) : "NONE";
        return "M=" + m + "|F=" + fs + "|R=" + rs;
    }

    private static String spans(Matcher m) {
        StringBuilder sb = new StringBuilder().append(m.start()).append(',').append(m.end());
        for (int g = 1; g <= m.groupCount(); g++) {
            sb.append(';').append(m.start(g)).append(',').append(m.end(g));
        }
        return sb.toString();
    }

    private static String facadeProto(io.github.jemmix.tdfa.Pattern p, CharSequence input) {
        PatternMatcher mm = p.matcher(input);
        String m = mm.matches() ? spans(mm) : "NONE";
        PatternMatcher f = p.matcher(input);
        String fs = f.find(0) ? spans(f) : "NONE";
        PatternMatcher rr = p.matcher(input);
        String rs = rr.find(input.length() / 2) ? spans(rr) : "NONE";
        return "M=" + m + "|F=" + fs + "|R=" + rs;
    }

    private static String spans(PatternMatcher m) {
        StringBuilder sb = new StringBuilder().append(m.start()).append(',').append(m.end());
        for (int g = 1; g <= m.groupCount(); g++) {
            sb.append(';').append(m.start(g)).append(',').append(m.end(g));
        }
        return sb.toString();
    }

    private static String laneProto(String pattern, String input, Semantics lane) {
        WorkMeter ledger = new WorkMeter(Budgets.compileComputeTicks());
        Tnfa nfa =
            Tnfa.compile(pattern, false, false, false, lane, UnicodeProviders.get(), CompileObserver.NONE, ledger);
        Tdfa tdfa = Determinizer.compileWithWholeSide(nfa, false, CompileObserver.NONE, ledger.fork(0));
        RegexEngine vm = new TdfaRunner(tdfa, 1 << 20);
        RegexEngine asm = TdfaAsmBackend.generate(tdfa, 1 << 20);
        String vmStr = engineProto(vm, input);
        assertThat(engineProto(asm, input)).as("ASM == VM (lane %s) /%s/", lane, pattern).isEqualTo(vmStr);
        return vmStr;
    }

    private static String engineProto(RegexEngine eng, CharSequence input) {
        String m;
        if (eng instanceof WholeEngine we) {
            MatchResult w = we.matchWhole(input, null);
            m = w == null ? "NONE" : spans(w);
        } else {
            m = eng.matches(input) ? spans(eng.match(input, 0, null)) : "NONE";
        }
        MatchResult f = eng.match(input, 0, null);
        String fs = f == null ? "NONE" : spans(f);
        MatchResult rr = eng.match(input, input.length() / 2, null);
        String rs = rr == null ? "NONE" : spans(rr);
        return "M=" + m + "|F=" + fs + "|R=" + rs;
    }

    private static String spans(MatchResult m) {
        StringBuilder sb = new StringBuilder().append(m.start(0)).append(',').append(m.end(0));
        for (int g = 1; g <= m.groupCount(); g++) {
            sb.append(';').append(m.start(g)).append(',').append(m.end(g));
        }
        return sb.toString();
    }
}
