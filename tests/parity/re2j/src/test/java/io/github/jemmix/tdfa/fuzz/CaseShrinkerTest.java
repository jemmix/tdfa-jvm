package io.github.jemmix.tdfa.fuzz;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.function.Predicate;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shrinker's own gate ({@link CaseShrinker}): the delta-debugging
 * machinery is driven by synthetic properties so every pass is pinned
 * without needing a live divergence, and the REAL property wiring
 * (finding-kind classification, cold-solo replay) is pinned two ways —
 * clean engine cases do not shrink (null, fast), and a recorded
 * BUDGET_REJECT case from the 2026-10-24 campaign shrinks to a strictly
 * smaller pattern that still budget-rejects at the soak's scoped budget.
 */
@Timeout(300)
class CaseShrinkerTest {

    /** Input axis: ddmin deletion reaches the minimal string the property
     *  demands — including when what must survive is multi-unit. */
    @Test
    void inputShrinksToMinimal() {
        Predicate<DifferentialFuzzer.Case> prop = c -> c.input().contains("ab") && c.pattern().contains("x");
        DifferentialFuzzer.Case c = new DifferentialFuzzer.Case("x", "xxabxxabxx", 0, DifferentialFuzzer.Lane.RE2J);
        CaseShrinker.ShrinkReport r = new CaseShrinker(prop, 30_000, 500).shrink(c);
        assertThat(r.minimal().input()).isEqualTo("ab");
        assertThat(r.minimal().pattern()).isEqualTo("x");
    }

    /** A supplementary codepoint in the input is two UTF-16 units; char
     *  deletion never rewrites what it keeps, so the pair survives intact. */
    @Test
    void supplementaryPairSurvivesInputShrinking() {
        String pair = new String(new int[]{0x10421}, 0, 1);
        Predicate<DifferentialFuzzer.Case> prop = c -> c.input().contains(pair);
        DifferentialFuzzer.Case c = new DifferentialFuzzer.Case("p", "a" + pair + "b", 0, DifferentialFuzzer.Lane.RE2J);
        CaseShrinker.ShrinkReport r = new CaseShrinker(prop, 30_000, 500).shrink(c);
        assertThat(r.minimal().input()).isEqualTo(pair);
    }

    /** Pattern axis: alternation branches drop, atoms drop, literals
     *  simplify — and rewrite candidates that break syntax are discarded
     *  by the property (here: live java.util.regex compilation), never by
     *  validity logic in the shrinker. */
    @Test
    void patternShrinksStructurally() {
        Predicate<DifferentialFuzzer.Case> prop = c -> {
            if (!c.pattern().contains("*")) {
                return false;
            }
            try {
                return Pattern.compile(c.pattern()).matcher(c.input()).find();
            } catch (RuntimeException e) {
                return false;
            }
        };
        DifferentialFuzzer.Case c = new DifferentialFuzzer.Case("(?:xy|zz)*q", "aaq", 0, DifferentialFuzzer.Lane.RE2J);
        CaseShrinker.ShrinkReport r = new CaseShrinker(prop, 30_000, 500).shrink(c);
        assertThat(r.minimal().pattern()).isEqualTo("a*");
        assertThat(r.minimal().input()).isEmpty();
    }

    /** Class members drop one at a time; the member the property demands
     *  stays, the rest go. */
    @Test
    void classMembersShrink() {
        Predicate<DifferentialFuzzer.Case> prop = c -> c.pattern().contains("ab");
        DifferentialFuzzer.Case c = new DifferentialFuzzer.Case("[ab0-9]", "i", 0, DifferentialFuzzer.Lane.RE2J);
        CaseShrinker.ShrinkReport r = new CaseShrinker(prop, 30_000, 500).shrink(c);
        assertThat(r.minimal().pattern()).isEqualTo("[ab]");
    }

    /** A clean case (property false) is reported as such, not shrunk. */
    @Test
    void cleanCaseYieldsNull() {
        DifferentialFuzzer.Case c = new DifferentialFuzzer.Case("[ab]", "ab", 0, DifferentialFuzzer.Lane.RE2J);
        assertThat(new CaseShrinker(x -> false, 5_000, 10).shrink(c)).isNull();
    }

    /** The step cap bounds the search: an always-failing property cannot
     *  run past its budget, and the report says the result may not be
     *  minimal (capped) rather than lying about it. */
    @Test
    void stepCapStopsSearch() {
        DifferentialFuzzer.Case c = new DifferentialFuzzer.Case("[ab]", "ab", 0, DifferentialFuzzer.Lane.RE2J);
        CaseShrinker.ShrinkReport r = new CaseShrinker(x -> true, 30_000, 3).shrink(c);
        assertThat(r.evals()).isLessThanOrEqualTo(3);
        assertThat(r.capped()).isTrue();
    }

    /** Real wiring, clean side: fixed-seed engine cases are findings of no
     *  kind — shrinkCase returns null without burning a single candidate
     *  evaluation. The re2j lane is pinned: its oracle is linear-time. */
    @Test
    void cleanEngineCasesDoNotShrink() {
        for (long seed : new long[]{1, 2, 3}) {
            DifferentialFuzzer.Case c = DifferentialFuzzer.generate(seed, DifferentialFuzzer.Lane.RE2J);
            DifferentialFuzzer.Outcome o = DifferentialFuzzer.runOne(c);
            assertThat(DifferentialFuzzer.findingKind(o)).as("seed %s is clean", seed).isNull();
            assertThat(DifferentialFuzzer.shrinkCase(c, DifferentialFuzzer.findingKind(o))).isNull();
        }
    }

    /** Real wiring, failing side: the campaign's recorded BUDGET_REJECT
     *  seed (the "trivial pattern" CI family, BudgetRejectReplayTest) —
     *  under the soak's scoped 8 M-tick budget the shrink drives the case
     *  to a minimal same-kind failure (the input axis to the floor, the
     *  pattern as far as still-rejecting goes), and the original caseSeed
     *  keeps replaying the full-size repro either way. */
    @Test
    void budgetRejectShrinksToMinimalStillRejecting() {
        System.setProperty("tdfa.budget.compile.compute", Long.toString(8L << 20));
        try {
            // 2026-10-31 v4 re-record (the 2026-10-28 seed's batch draws a
            // family pattern under the generator-v4 family axis): \w*(?i:(\w))
            // is the same "trivial pattern" scoped-budget reject family.
            DifferentialFuzzer.Case c = DifferentialFuzzer.generate(14657L, DifferentialFuzzer.Lane.JUR);
            assertThat(DifferentialFuzzer.findingKind(DifferentialFuzzer.runOne(c))).isEqualTo("BUDGET_REJECT");
            CaseShrinker.ShrinkReport r =
                new CaseShrinker(DifferentialFuzzer.sameFinding("BUDGET_REJECT"), 60_000, 64).shrink(c);
            assertThat(r).as("shrinks under the scoped budget").isNotNull();
            assertThat(r.abort()).isNull();
            assertThat(r.minimal().input()).as("input axis minimized").isEmpty();
            assertThat(r.minimal().pattern().length() + r.minimal().input().length()).as("strictly smaller overall")
                .isLessThan(c.pattern().length() + c.input().length());
            assertThat(DifferentialFuzzer.findingKind(DifferentialFuzzer.runOne(r.minimal())))
                .as("minimal case still budget-rejects").isEqualTo("BUDGET_REJECT");
        } finally {
            System.clearProperty("tdfa.budget.compile.compute");
        }
    }

    /** The replay round-trip: escape() output (what the shrink block and
     *  ndjson carry) unescapes to the exact codepoints. */
    @Test
    void escapedReplayRoundTrips() {
        String s = "a\uD801\uDC21|*[]\n\\\"\u0000";
        assertThat(DifferentialFuzzer.unescape(DifferentialFuzzer.escape(s))).isEqualTo(s);
        assertThat(DifferentialFuzzer.unescape("plain")).isEqualTo("plain");
    }
}
