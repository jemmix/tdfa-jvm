package io.github.jemmix.tdfa;

import io.github.jemmix.tdfa.asm.TdfaAsmBackend;
import io.github.jemmix.tdfa.core.budget.Budgets;
import io.github.jemmix.tdfa.core.budget.WorkMeter;
import io.github.jemmix.tdfa.core.determinize.Determinizer;
import io.github.jemmix.tdfa.core.dfa.Tdfa;
import io.github.jemmix.tdfa.core.dfa.TdfaRunner;
import io.github.jemmix.tdfa.core.engine.MatchResult;
import io.github.jemmix.tdfa.core.engine.RegexEngine;
import io.github.jemmix.tdfa.core.report.CompileObserver;
import io.github.jemmix.tdfa.core.tnfa.Semantics;
import io.github.jemmix.tdfa.core.tnfa.Tnfa;
import io.github.jemmix.tdfa.core.unicode.UnicodeProviders;
import org.junit.jupiter.api.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pinned repro of finding 1, docs/fuzz-campaigns-2026-10-24.md (seed
 * {@code 8663892547704900790}): the origin sim's unbeatability scan
 * stopped as soon as its PARTIAL live-origin minimum reached the best
 * accept origin — a tie treated as proof no future accept could beat
 * {@code best}, while a smaller origin sat in an unscanned word. On the
 * minimal family below the sim answered leftmost 1 where leftmost 0 is
 * real; the natural ladder never noticed (its exact walk at {@code from}
 * answers 0 first), so only the forced rung exposed the miss — the
 * cross-rung differential exists for exactly this class.
 */
class OriginSimLeftmostStartTest {

    private static final String PATTERN = "((?s:(?m:(?:.{3}){3,}\\S))){3}?";

    /** The record's 18-unit input: unpaired surrogates framing
     *  well-formed pairs. Word order in the live set — a bigger state
     *  number carrying the smaller origin — is what tripped the exit. */
    private static final String BASE =
        "\udfff\ud807\udc07\udc00\u0442\ud807\udc07\ud835\udd04\ud835\udd04\u1c85Y\n\u3042\ud801\udc21\udc21";

    /** 3x is the minimal diverging family (18/36 units match nowhere);
     * 4x/5x diverged the same way and stay pinned with it. */
    private static final String[] EXPECTED = {"0..54 (40..54)", "0..71 (57..71)", "0..87 (73..87)"};

    private static final TdfaRunner.Strategy[] FORCED_RUNGS = {TdfaRunner.Strategy.ORIGIN_SIM,
        TdfaRunner.Strategy.TRIGGER, TdfaRunner.Strategy.RAW_SCAN, TdfaRunner.Strategy.WALK_RESTART};

    private static Tdfa compileTdfa(String pattern) {
        WorkMeter ledger = new WorkMeter(Budgets.compileComputeTicks());
        Tnfa nfa = Tnfa.compile(pattern, false, false, false, Semantics.of(), UnicodeProviders.get(),
            CompileObserver.NONE, ledger);
        return Determinizer.compileWithWholeSide(nfa, false, CompileObserver.NONE, ledger.fork(0));
    }

    /** First-find spans (overall + every group), the F record's shape. */
    private static String firstFind(RegexEngine eng, String in) {
        MatchResult m = eng.match(in, 0, null);
        return m == null ? "none" : spans(m.start(0), m.end(0), m.groupCount(), m::start, m::end);
    }

    private static String firstFind(Pattern oracle, String in) {
        Matcher m = oracle.matcher(in);
        return m.find() ? spans(m.start(), m.end(), m.groupCount(), m::start, m::end) : "none";
    }

    private static String spans(int start, int end, int groupCount, Span starts, Span ends) {
        StringBuilder sb = new StringBuilder(24).append(start).append("..").append(end);
        for (int g = 1; g <= groupCount; g++) {
            sb.append(" (").append(starts.at(g)).append("..").append(ends.at(g)).append(')');
        }
        return sb.toString();
    }

    @Test
    void minimalFamilyLeftmostZeroUnderEveryRung() {
        Tdfa tdfa = compileTdfa(PATTERN);
        RegexEngine vm = new TdfaRunner(tdfa, 1 << 20);
        RegexEngine asm = TdfaAsmBackend.generate(tdfa, 1 << 20);
        TdfaRunner sim = new TdfaRunner(tdfa, 1 << 20);
        Pattern oracle = Pattern.compile(PATTERN);
        for (int k = 3; k <= 5; k++) {
            String in = BASE.repeat(k);
            String ctx = k + "x len=" + in.length();
            String natural = firstFind(vm, in);
            assertThat(natural).as("%s: literal pin", ctx).isEqualTo(EXPECTED[k - 3]);
            assertThat(natural).as("%s: VM vs live oracle", ctx).isEqualTo(firstFind(oracle, in));
            assertThat(firstFind(asm, in)).as("%s: ASM == VM", ctx).isEqualTo(natural);
            assertThat(sim.originSimLeftmost(in, 0, in.length(), sim.originSimBudget(), null))
                .as("%s: sim leftmost", ctx).isZero();
            for (TdfaRunner.Strategy f : FORCED_RUNGS) {
                String forced;
                try {
                    TdfaRunner.setForcedStrategy(f);
                    forced = firstFind(vm, in);
                } finally {
                    TdfaRunner.setForcedStrategy(null);
                }
                assertThat(forced).as("%s: forced %s", ctx, f).isEqualTo(natural);
            }
        }
    }

    /** The campaign record itself: the soak's stretched shape (whole-string
     * repeats past ~4.3k chars) where the forced rung disagreed. */
    @Test
    void stretchedCampaignShapeAgreesAcrossRungs() {
        Tdfa tdfa = compileTdfa(PATTERN);
        RegexEngine vm = new TdfaRunner(tdfa, 1 << 20);
        RegexEngine asm = TdfaAsmBackend.generate(tdfa, 1 << 20);
        Pattern oracle = Pattern.compile(PATTERN);
        String in = BASE.repeat(4300 / BASE.length() + 1);
        String natural = firstFind(vm, in);
        assertThat(natural).as("stretched: leftmost is 0").startsWith("0..");
        assertThat(natural).as("stretched: VM vs live oracle").isEqualTo(firstFind(oracle, in));
        assertThat(firstFind(asm, in)).as("stretched: ASM == VM").isEqualTo(natural);
        for (TdfaRunner.Strategy f : FORCED_RUNGS) {
            String forced;
            try {
                TdfaRunner.setForcedStrategy(f);
                forced = firstFind(vm, in);
            } finally {
                TdfaRunner.setForcedStrategy(null);
            }
            assertThat(forced).as("stretched: forced %s", f).isEqualTo(natural);
        }
    }

    private interface Span {

        int at(int g);
    }
}
