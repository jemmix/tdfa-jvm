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
 * per match operation): the safety net for the search-ladder shapes that
 * REMAIN degenerate after the root fixes — the finding-3 {@code \A}/`^`
 * class collapsed by the abs-begin ladder bound, and the plain {@code
 * .+tail} / multiline classes bounded by the unit-semantics trigger; the
 * residue is the boundary-gated-alternation family whose accepting
 * states look live to the mask-blind simulation (an early trigger
 * return), leaving the restart ladder's long walks unbounded. A wall
 * bound rather than a step count because the diseases are per-step COST
 * explosions on step counts legit work also reaches: the spins and the
 * conformance suite's heaviest healthy churn both land at ~3–10 M walk
 * steps while their wall times differ by three orders. Healthy
 * operations (microseconds to ~60 ms) must never trip the default.
 */
class MatchBudgetTest {

    /** A still-degenerate shape: alternation of {@code .+} bodies with
     *  boundary-gated accepts — the trigger's acceptBits over-approximation
     *  reports an accept live at the scan's first step (the accepting
     *  states' \b masks are invisible to the simulation), so the window
     *  bound buys nothing and the restart ladder's walks scan to the end.
     *  The narrow residue the unit-semantics trigger bound left behind
     *  (see UnitTriggerBoundTest for the classes it did fix). */
    private static final String RESTART_QUADRATIC = "(.+$\\D|.+ x\\D+)\\b";

    /** The campaign's finding-3 {@code \A}-anchor shape — root-fixed by
     *  the abs-begin ladder bound: kept here as the healthy canary. */
    private static final String ANCHOR_COLLAPSED = ".*(?:\\A\\W )h";

    private static String bigNoMatch() {
        return "ab".repeat(2200); // 4400 chars — every restart walk scans to the end and dies
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
            assertThat(Pattern.compile(ANCHOR_COLLAPSED).matcher("! h").find())
                .as("the \\A-anchored branch matches at 0").isTrue();
            System.setProperty("tdfa.budget.match.ms", "-1");
            assertThat(Pattern.compile(RESTART_QUADRATIC).matcher(bigNoMatch()).find())
                .as("no viable multiline tail, unbounded").isFalse();
        } finally {
            System.clearProperty("tdfa.budget.match.ms");
        }
    }

    @Test
    void healthyScansStayFarUnderTheDefault() {
        // Linear scans and die-fast restart scans: microseconds to a few
        // milliseconds against the 2 000 ms default.
        assertThatCode(() -> {
            // the abs-begin-bound shapes: collapsed to one exact walk
            // (the campaign's 39 s anchor spin — the root-fix canary)
            for (String p : new String[]{ANCHOR_COLLAPSED, ".+^\\W", ".{1,}(?:\\A\\w)\\w"}) {
                assertThat(Pattern.compile(p).matcher("ab".repeat(2200)).find()).isFalse();
            }
            // the unit-trigger-bounded shapes: one linear raw scan proves
            // no-match (the `.+tail` family — was n restarts × O(n) walks)
            for (String p : new String[]{".+zz", "\\S{4,}\\.", ".*(((?m:$\\d)b)\\s){0,4}n"}) {
                assertThat(Pattern.compile(p).matcher("ab".repeat(2200)).find()).isFalse();
            }
            assertThat(Pattern.compile(ANCHOR_COLLAPSED).matcher("! h").find()).isTrue();
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
