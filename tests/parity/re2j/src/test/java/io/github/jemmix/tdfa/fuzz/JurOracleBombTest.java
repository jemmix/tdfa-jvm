package io.github.jemmix.tdfa.fuzz;

import org.junit.jupiter.api.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The jur-lane oracle-bomb guard predicate ({@link DifferentialFuzzer#jurOracleBomb}):
 * the campaign-2026-10-16 finding-4 spins and the campaign-2026-10-24
 * finding-6 lazy nested-quantifier bombs must trip it (the latter pinned
 * against the LIVE oracle — the crafted shapes demonstrably spin
 * {@code java.util.regex}, whose thread is still running when the
 * watchdog gives up), the engine-side finding-3 slow shapes, the
 * finding-6 clean lookalikes and ordinary patterns must pass through,
 * and the lexical skips (\Q..\E, class interiors, malformed braces) must
 * hold.
 */
class JurOracleBombTest {

    /** Live-oracle watchdog: bombs spin minutes on these crafted inputs
     *  (measured >8 s on JDK 26), lookalikes answer in microseconds — a
     *  1.5 s cap separates them by >5×/10000×. */
    private static final long LIVE_ORACLE_CAP_MS = 1_500;

    @Test
    void campaignBombShapesTrip() {
        assertThat(DifferentialFuzzer.jurOracleBomb("(?:\\D+[^\\ds-z#\uD802\uDC02]*?){2,}?")).isTrue();
        assertThat(DifferentialFuzzer.jurOracleBomb("(?:(?:(?:.{0,}(\\w{3})*){0,}(?:Z([b]+あ))*?)){2,}?")).isTrue();
        assertThat(DifferentialFuzzer.jurOracleBomb("((?s:(?U:Ya?)$|([^nt\\w\\n]?.*?a*?))){0,}?(?:\uD807\uDD07?)"))
            .isTrue();
    }

    @Test
    void lazyNestedQuantifierBombsTrip() {
        // campaign 2026-10-24 finding 6: the two HANG_ORACLE seeds that
        // dodged the eager-only predicate — a lazy group loop over a
        // range-quantified wide, and a range-counted group over two
        // lazy-unbounded wides.
        assertThat(DifferentialFuzzer.jurOracleBomb("(.{0,6}?)*?\uD807\uDC07")).isTrue();
        assertThat(DifferentialFuzzer.jurOracleBomb("(.*?.*?){2,6}\t")).isTrue();
        // same LazyLoop node, inner laziness irrelevant: the loop quantifier is the trigger
        assertThat(DifferentialFuzzer.jurOracleBomb("(.{0,6})*?\uD807\uDC07")).isTrue();
        assertThat(DifferentialFuzzer.jurOracleBomb("(\\D{1,6}?)*?\uD807\uDC07")).isTrue();
        assertThat(DifferentialFuzzer.jurOracleBomb("(.{2,6}?)+?\uD807\uDC07")).isTrue();
        // the range-counted family does not need lazy inners either
        assertThat(DifferentialFuzzer.jurOracleBomb("(.*.*){2,6}\t")).isTrue();
        assertThat(DifferentialFuzzer.jurOracleBomb("(.{0,6}.{0,6}){2,6}\t")).isTrue();
    }

    @Test
    void lazyLookalikesPass() {
        // the finding-6 clean seeds: greedy loops sit under java's
        // failed-position memo, 0/1-width (Ques) bodies never fan out
        assertThat(DifferentialFuzzer.jurOracleBomb("((?:(?:.(?:\\D??){2,}?)*(?:\uDC21 (?:\uDC21))\uDC00){3,})"))
            .isFalse();
        assertThat(DifferentialFuzzer.jurOracleBomb("(?:(.{0,5}?(.))*)*\uD835\uDD04\n")).isFalse();
        assertThat(DifferentialFuzzer.jurOracleBomb("(.{0,6}?)*\uD807\uDC07")).isFalse(); // greedy loop: memoized
        assertThat(DifferentialFuzzer.jurOracleBomb("(.{0,1}?)*?\uD807\uDC07")).isFalse(); // Ques body: width 0/1
    }

    @Test
    void singleWideRangeGroupsStay() {
        // one multi-quantified wide in a range-counted group holds under
        // the memo (two are the {2,6} bomb); exact {n} counts are bounded
        assertThat(DifferentialFuzzer.jurOracleBomb("(.*?){2,6}\t")).isFalse();
        assertThat(DifferentialFuzzer.jurOracleBomb("(.{0,6}?){2,6}\t")).isFalse();
        assertThat(DifferentialFuzzer.jurOracleBomb("(.*?.*?){2}\t")).isFalse();
    }

    /**
     * The live-oracle pin for finding 6 (the seeds now replay clean on
     * main, so the regression is the crafted shapes): each bomb must
     * actually spin {@code java.util.regex} — its matcher thread is still
     * running when the watchdog gives up — and the guard must catch it.
     * The clean lookalikes must finish fast on the same oracle and stay
     * unguarded. The abandoned spin threads are daemons and die with the
     * test JVM (same trade as the fuzzer's own hang watchdogs).
     */
    @Test
    void craftedBombsSpinTheLiveOracle() throws InterruptedException {
        // lookalikes first: complete well inside the cap, stay compared
        for (String look : new String[]{"((?:(?:.(?:\\D??){2,}?)*(?:\uDC21 (?:\uDC21))\uDC00){3,})",
            "(?:(.{0,5}?(.))*)*\uD835\uDD04\n"}) {
            assertThat(DifferentialFuzzer.jurOracleBomb(look)).isFalse();
            assertThat(liveOracleDone(look, "abz\n")).as("lookalike %s completes", look).isTrue();
        }
        String[][] bombs = {{"(.{0,6}?)*?\uD807\uDC07", "a".repeat(32)}, // seed 7532091064100310438's shape
            {"(.*?.*?){2,6}\t", "a".repeat(24)}, // seed 1764815253230278528's shape
            {"(.*.*){2,6}\t", "a".repeat(24)}}; // the eager-inner variant of the same family
        for (String[] bomb : bombs) {
            assertThat(DifferentialFuzzer.jurOracleBomb(bomb[0])).as("guard catches %s", bomb[0]).isTrue();
            assertThat(liveOracleDone(bomb[0], bomb[1])).as("oracle spins on %s", bomb[0]).isFalse();
        }
    }

    /** Ran {@code p.find()} on the live oracle to completion within
     *  {@value #LIVE_ORACLE_CAP_MS} ms (false = still spinning: a daemon
     *  thread keeps burning past the watchdog — uninterruptible by
     *  design, which is exactly why the guard exists). */
    private static boolean liveOracleDone(String pattern, String input) throws InterruptedException {
        Matcher m = Pattern.compile(pattern).matcher(input);
        Thread t = new Thread(m::find);
        t.setDaemon(true);
        t.start();
        t.join(LIVE_ORACLE_CAP_MS);
        return !t.isAlive();
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
