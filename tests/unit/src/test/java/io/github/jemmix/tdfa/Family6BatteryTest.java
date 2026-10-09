package io.github.jemmix.tdfa;

import io.github.jemmix.tdfa.asm.TdfaAsmBackend;
import io.github.jemmix.tdfa.core.budget.Budgets;
import io.github.jemmix.tdfa.core.budget.WorkMeter;
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
 * <li><b>The JUR-lane assertions are dark</b> until their sub-family's
 *     fix lands: {@link #jurLaneSubFamilyA} lights with WBS 4b (the
 *     SET_NIL suppression), {@link #jurLaneSubFamilyB} with 4c (the
 *     surfaced final iteration). Agree-shaped rows pass under either
 *     gate; the divergent rows are why the gates exist.</li>
 * </ul>
 */
class Family6BatteryTest {

    /** WBS 4b flips to true when the sub-family A suppression lands. */
    private static final boolean SUB_FAMILY_A_LIVE = false;

    /** WBS 4c flips to true when the sub-family B final-iteration surfacing lands. */
    private static final boolean SUB_FAMILY_B_LIVE = false;

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
                out.add(new Row(f[0], f[1].equals("A"), f[2], f[3].replace("\\s", " "), f[4], f[5]));
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

    // ===== 3. the JUR-lane assertions — dark until 4b/4c light them =====

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
