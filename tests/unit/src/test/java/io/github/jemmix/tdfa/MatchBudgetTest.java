package io.github.jemmix.tdfa;

import io.github.jemmix.tdfa.core.budget.MatchBudgetExceededException;
import io.github.jemmix.tdfa.core.determinize.Determinizer;
import io.github.jemmix.tdfa.core.dfa.Tdfa;
import io.github.jemmix.tdfa.core.dfa.TdfaRunner;
import io.github.jemmix.tdfa.core.engine.MatchScratch;
import io.github.jemmix.tdfa.core.tnfa.Tnfa;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Match-time work bound ({@code tdfa.budget.match.ms}, wall milliseconds
 * per match operation): the safety net that bounds the search ladder's
 * known degenerate shapes — the unit-semantics restart loop without a
 * trigger bound under {@code .*}-anchor patterns, whose mask-heavy
 * per-step costs measured 12–55 s on the campaign's seeds (finding 3).
 * A wall bound rather than a step count because the diseases are per-step
 * COST explosions on step counts legit work also reaches: the spins and
 * the conformance suite's heaviest healthy churn both land at ~3–10 M
 * walk steps while their wall times differ by three orders. Healthy
 * operations (microseconds to ~60 ms) must never trip the default.
 */
class MatchBudgetTest {

    /** The campaign's finding-3 shape: {@code .*} before an anchor — every
     *  restart walk from every unit position runs the full remaining span
     *  under unit semantics (no trigger bound), with mask-heavy steps. */
    private static final String RESTART_QUADRATIC = ".*(?:\\A\\W )h";

    private static String bigNoMatch() {
        return "ab".repeat(2200); // 4400 chars, no 'h' — the walk never accepts
    }

    @Test
    void degenerateRestartLadderTripsFast() {
        System.setProperty("tdfa.budget.match.ms", "100");
        try {
            Pattern p = Pattern.compile(RESTART_QUADRATIC);
            long t0 = System.nanoTime();
            assertThatThrownBy(() -> p.matcher(bigNoMatch()).find()).isInstanceOf(MatchBudgetExceededException.class)
                .hasMessageContaining("match work budget exceeded").hasMessageContaining("tdfa.budget.match.ms");
            long ms = (System.nanoTime() - t0) / 1_000_000;
            assertThat(ms).as("wall to the bounded failure").isLessThan(5_000);
        } finally {
            System.clearProperty("tdfa.budget.match.ms");
        }
    }

    @Test
    void nonpositiveDisablesAndAnswerStaysCorrect() {
        System.setProperty("tdfa.budget.match.ms", "0");
        try {
            Pattern p = Pattern.compile(RESTART_QUADRATIC);
            assertThat(p.matcher("! h").find()).as("the \\A-anchored branch matches at 0").isTrue();
            System.setProperty("tdfa.budget.match.ms", "-1");
            assertThat(p.matcher(bigNoMatch()).find()).as("no 'h' in the input, unbounded").isFalse();
        } finally {
            System.clearProperty("tdfa.budget.match.ms");
        }
    }

    @Test
    void healthyScansStayFarUnderTheDefault() {
        // Linear scans and die-fast restart scans: microseconds to a few
        // milliseconds against the 2 000 ms default.
        assertThatCode(() -> {
            Pattern p = Pattern.compile(RESTART_QUADRATIC);
            assertThat(p.matcher("ab".repeat(200)).find()).isFalse();
            assertThat(p.matcher("! h").find()).isTrue();
            Pattern q = Pattern.compile("zz$");
            assertThat(q.matcher("ab".repeat(5000)).find()).isFalse(); // die-fast restarts: linear
            assertThat(Pattern.compile("\\w+ing").matcher(("spr" + "int ").repeat(3000) + "spring").find()).isTrue();
            // The conformance suite's heaviest legit shape: [a-z]+qrst over
            // long lowercase runs — quadratic in step count but ~60 ms wall.
            assertThat(Pattern.compile("[a-z]+qrst")
                .matcher("the quick brown fox jumps over the lazy dog qrstxx" + "x".repeat(4047)).find()).isFalse(); // no [a-z] run abuts qrst: pure churn, no match
        }).doesNotThrowAnyException();
    }

    @Test
    void sharedRestartTailIsMetered() {
        // The defensive restart tail is the ONE definition the ASM-emitted
        // ladders delegate to (TdfaRunner.restartExtract, EmittedSurface);
        // the meter must live where both tiers share it. Exercised on the
        // core engine API, as CompileBudgetTest does.
        System.setProperty("tdfa.budget.match.ms", "50");
        try {
            Tdfa find = Determinizer.compile(Tnfa.compile(".*zz"), false);
            var r = new TdfaRunner(find);
            r.beginMatchMeter(12000);
            assertThatThrownBy(() -> r.restartExtract("ab".repeat(6000), 1, 12000, 0, new MatchScratch()))
                .isInstanceOf(MatchBudgetExceededException.class);
        } finally {
            System.clearProperty("tdfa.budget.match.ms");
        }
    }
}
