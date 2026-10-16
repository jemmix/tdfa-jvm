package io.github.jemmix.tdfa.fuzz;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The jur-lane oracle-bomb guard predicate ({@link DifferentialFuzzer#jurOracleBomb}):
 * the three campaign-2026-10-16 finding-4 spins must trip it, the engine-side
 * finding-3 slow shapes and ordinary patterns must pass through, and the
 * lexical skips (\Q..\E, class interiors, malformed braces) must hold.
 */
class JurOracleBombTest {

    @Test
    void campaignBombShapesTrip() {
        assertThat(DifferentialFuzzer.jurOracleBomb("(?:\\D+[^\\ds-z#\uD802\uDC02]*?){2,}?")).isTrue();
        assertThat(DifferentialFuzzer.jurOracleBomb("(?:(?:(?:.{0,}(\\w{3})*){0,}(?:Z([b]+あ))*?)){2,}?")).isTrue();
        assertThat(DifferentialFuzzer.jurOracleBomb("((?s:(?U:Ya?)$|([^nt\\w\\n]?.*?a*?))){0,}?(?:\uD807\uDD07?)"))
            .isTrue();
    }

    @Test
    void engineSlowShapesPass() {
        // finding 3: our engine's restart/flag cost, the jur oracle answers
        // these in microseconds — guarding them would hide engine findings.
        assertThat(DifferentialFuzzer.jurOracleBomb(".*(?:\\A\\W )h")).isFalse();
        assertThat(DifferentialFuzzer.jurOracleBomb(".+^\\W")).isFalse();
        assertThat(DifferentialFuzzer.jurOracleBomb(".{1,}(?:\\A\\w(?m:?\\-))\\w")).isFalse();
        assertThat(DifferentialFuzzer.jurOracleBomb("(?i:((9@^|\\s{0,2}?.{2,})?\\D)\\b?)")).isFalse();
        assertThat(DifferentialFuzzer.jurOracleBomb("(.+$\\D|.+ ?\\D+|(\\#)(?:.{0,})(?m:\\.))\\b")).isFalse();
        assertThat(DifferentialFuzzer.jurOracleBomb(".*(((?m:$\\d)b)\\s){0,4}n")).isFalse();
    }

    @Test
    void alternationOrderSeedsPass() {
        assertThat(DifferentialFuzzer.jurOracleBomb("(\\A|.?o*.){3}")).isFalse();
    }

    @Test
    void boundedOuterCountsStay() {
        assertThat(DifferentialFuzzer.jurOracleBomb("(?:\\D+){3}")).isFalse();
        assertThat(DifferentialFuzzer.jurOracleBomb("(?:\\D+){0,4}")).isFalse();
        assertThat(DifferentialFuzzer.jurOracleBomb("(?:\\D+x){6}")).isFalse();
    }

    @Test
    void benignShapesPass() {
        assertThat(DifferentialFuzzer.jurOracleBomb("abc")).isFalse();
        assertThat(DifferentialFuzzer.jurOracleBomb("(a|b)*c")).isFalse();
        assertThat(DifferentialFuzzer.jurOracleBomb("[a-z]+")).isFalse();
        assertThat(DifferentialFuzzer.jurOracleBomb("(?:ab)+")).isFalse();
        assertThat(DifferentialFuzzer.jurOracleBomb("\\w{3}\\s?")).isFalse();
        assertThat(DifferentialFuzzer.jurOracleBomb("")).isFalse();
    }

    @Test
    void lexicalRegionsAreSkipped() {
        // the bomb lookalikes live in \Q..\E or a class: literals, not structure
        assertThat(DifferentialFuzzer.jurOracleBomb("\\Q(?:\\D+)*\\E")).isFalse();
        assertThat(DifferentialFuzzer.jurOracleBomb("[()]*+")).isFalse();
        assertThat(DifferentialFuzzer.jurOracleBomb("[^\\]]*")).isFalse();
        // real bomb after a quote still trips
        assertThat(DifferentialFuzzer.jurOracleBomb("\\Qx\\E(?:\\W+)*")).isTrue();
    }

    @Test
    void malformedBracesAreLiteral() {
        assertThat(DifferentialFuzzer.jurOracleBomb("a{,}\\D")).isFalse();
        assertThat(DifferentialFuzzer.jurOracleBomb("(?:\\D+){2")).isFalse(); // {2} exact: bounded
    }
}
