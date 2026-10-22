package io.github.jemmix.tdfa;

import io.github.jemmix.tdfa.asm.TdfaAsmBackend;
import io.github.jemmix.tdfa.core.budget.Budgets;
import io.github.jemmix.tdfa.core.budget.WorkMeter;
import io.github.jemmix.tdfa.core.compile.PatternMatcher;
import io.github.jemmix.tdfa.core.compile.TDFAPattern;
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
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Finding 2 (campaign 2026-10-16) spec + corpus — the bounded-quantifier
 * zero-width-exit rule. java.util.regex compiles {@code {n,m}} / {@code {n,}}
 * over a body that can match empty through a choice point as
 * {@code Prolog}+{@code Loop}; the Loop guard {@code i > locals[begin]}
 * forces the loop to EXIT after any zero-width iteration — even under
 * {@code cmin} (the count check sits inside the guard, so exiting with
 * fewer iterations is legal). The structural desugar's concatenated copies
 * admit {@code [ε, consume]} paths java's backtracker never explores —
 * the {@code (\A|.?o*.){3}} matches() divergences. The fix
 * ({@code Ast.Repeat.zeroWidthExit} → the NFA builder's BOUNDED_ZWE
 * split) re-routes each copy's pure-ε completions to the quantifier exit.
 * The battery in {@code zwexit-battery.tsv} is the replayable
 * expected-value data: ε-able branch first/last &times; {@code \b}-omni
 * &times; {@code {2}}/{@code {3}}/{@code {0,2}}/{@code {1,2}}/
 * {@code {2,3}}/{@code {2,4}} &times; lazy &times; non-capturing/nested
 * &times; consuming-body and all-zero-width-deterministic controls
 * (java's GroupCurly COMMITS those — no guard), each row carrying the
 * JUR protocol (recorded from a live JDK) and the RE2 protocol (recorded
 * from the re2j surface, both tiers agreeing). Contracts, mirroring
 * {@link Family6BatteryTest}:
 *
 * <ul>
 * <li><b>The data is the live JDK.</b> The recorded JUR column is
 *     re-verified against a live {@code java.util.regex} on every run.</li>
 * <li><b>Both facade lanes are pinned.</b> The shipped default answers
 *     exactly the recorded JUR column, the re2j surface exactly the
 *     recorded RE2 column — on BOTH tiers (and CharSequence wrappers).
 *     The RE2 column IS the lane-unchanged contract: the re2j surface's
 *     structural reading keeps the permissive copies, so its artifacts
 *     stay bit-identical through the fix.</li>
 * <li><b>The JUR lane holds on every rung.</b> Forced-strategy audit:
 *     every battery row through ORIGIN_SIM/TRIGGER/RAW_SCAN/WALK_RESTART.</li>
 * </ul>
 */
class ZeroWidthExitBatteryTest {

    record Row(String id, String pattern, String input, String jur, String re2) {
    }

    private static final Semantics JUR = Semantics.of();

    static Stream<Row> rows() {
        return battery().stream();
    }

    private static List<Row> battery() {
        List<Row> out = new ArrayList<>();
        try (InputStream is = ZeroWidthExitBatteryTest.class.getResourceAsStream("/zwexit-battery.tsv");
            BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isEmpty() || line.charAt(0) == '#' || line.startsWith("id\t")) {
                    continue;
                }
                String[] f = line.split("\t", -1);
                assertThat(f.length).as("battery row fields: %s", line).isEqualTo(5);
                out.add(new Row(f[0], f[1], f[2].replace("\\n", "\n"), f[3], f[4]));
            }
        } catch (IOException e) {
            throw new IllegalStateException("zwexit-battery.tsv unreadable", e);
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

    // ===== 2. both facade lanes are pinned on both tiers =====

    @ParameterizedTest
    @MethodSource("rows")
    void facadeDefaultPinsTheJurProtocol(Row r) {
        TDFAPattern asm = (TDFAPattern) io.github.jemmix.tdfa.Pattern.compile(r.pattern);
        TDFAPattern vm = (TDFAPattern) io.github.jemmix.tdfa.Pattern.compile(r.pattern, 0, TdfaRunner::new);
        assertThat(facadeProto(asm, r.input)).as("%s: default ASM tier vs recorded JUR column /%s/", r.id, r.pattern)
            .isEqualTo(r.jur);
        assertThat(facadeProto(vm, r.input)).as("%s: default VM tier vs recorded JUR column /%s/", r.id, r.pattern)
            .isEqualTo(r.jur);
        assertThat(facadeProto(vm, new StringBuilder(r.input))).as("%s: default VM StringBuilder /%s/", r.id, r.pattern)
            .isEqualTo(r.jur);
    }

    @ParameterizedTest
    @MethodSource("rows")
    void re2SurfacePinsTheRecordedProtocol(Row r) {
        TDFAPattern asm = (TDFAPattern) io.github.jemmix.tdfa.re2j.Pattern.compile(r.pattern);
        TDFAPattern vm = (TDFAPattern) io.github.jemmix.tdfa.re2j.Pattern.compile(r.pattern, 0, TdfaRunner::new);
        assertThat(facadeProto(asm, r.input)).as("%s: RE2 ASM tier vs recorded RE2 column /%s/", r.id, r.pattern)
            .isEqualTo(r.re2);
        assertThat(facadeProto(vm, r.input)).as("%s: RE2 VM tier vs recorded RE2 column /%s/", r.id, r.pattern)
            .isEqualTo(r.re2);
        assertThat(facadeProto(vm, new StringBuilder(r.input))).as("%s: RE2 VM StringBuilder /%s/", r.id, r.pattern)
            .isEqualTo(r.re2);
    }

    // ===== 3. the JUR lane holds on every rung =====

    @ParameterizedTest
    @MethodSource("rows")
    void jurLaneRungAndWrapperAudit(Row r) {
        WorkMeter ledger = new WorkMeter(Budgets.compileComputeTicks());
        Tnfa nfa =
            Tnfa.compile(r.pattern, false, false, false, JUR, UnicodeProviders.get(), CompileObserver.NONE, ledger);
        Tdfa tdfa = Determinizer.compileWithWholeSide(nfa, false, CompileObserver.NONE, ledger.fork(0));
        TdfaRunner vm = new TdfaRunner(tdfa, 1 << 20);
        String want = engineProto(vm, r.input);
        assertThat(engineProto(TdfaAsmBackend.generate(tdfa, 1 << 20), r.input))
            .as("%s: ASM == VM /%s/", r.id, r.pattern).isEqualTo(want);
        assertThat(engineProto(vm, new StringBuilder(r.input))).as("%s: StringBuilder /%s/", r.id, r.pattern)
            .isEqualTo(want);
        assertThat(engineProto(vm, CharBuffer.wrap(r.input))).as("%s: CharBuffer /%s/", r.id, r.pattern)
            .isEqualTo(want);
        for (TdfaRunner.Strategy f : new TdfaRunner.Strategy[]{TdfaRunner.Strategy.ORIGIN_SIM,
            TdfaRunner.Strategy.TRIGGER, TdfaRunner.Strategy.RAW_SCAN, TdfaRunner.Strategy.WALK_RESTART}) {
            String forced;
            try {
                TdfaRunner.setForcedStrategy(f);
                forced = engineProto(vm, r.input);
            } finally {
                TdfaRunner.setForcedStrategy(null);
            }
            assertThat(forced).as("%s: forced %s on /%s/", r.id, f, r.pattern).isEqualTo(want);
        }
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

    private static String facadeProto(TDFAPattern p, CharSequence input) {
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
