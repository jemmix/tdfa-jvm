package io.github.jemmix.tdfa.parity;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The GroupCurly stale-iteration write — the DECIDED domain exclusion of
 * fuzz campaign 2026-10-24 finding 3, pinned both ways.
 *
 * <p>java.util.regex compiles a greedy quantifier on a group whose body is
 * {@code study()}-deterministic and contains another, unquantified capture
 * as {@code GroupCurly} with the inner capture's {@code GroupTail} inside
 * the atom. {@code GroupTail} commits its span as soon as the atom's own
 * continuation succeeds — but {@code GroupCurly.match0}'s backoff
 * ({@code while (j > min)}) rewrites ONLY the directly-quantified group's
 * registers; the nested write is never restored. When the greedy loop
 * unwinds below its deepest iteration, the DISCARDED iteration's span
 * reports through the accepted match ({@code (?:(b))*b} on {@code "xb y"}:
 * match {@code 1..2} after backing off to zero iterations, g1 {@code 1..2}
 * — the text the tail then re-consumed). The same write also survives a
 * FAILED find() restart — {@code Matcher.search} resets the group array
 * once per find(), not between attempts ({@code (?:.(9)){0,}\d} on
 * {@code "x9"}: the attempt at 0 writes g1 {@code 1..2}, fails, and the
 * accepted attempt at 1 reports it; the strictly-outside manifestations of
 * that non-reset are the restart-tag-leak exclusion).
 *
 * <p><b>Decision: exclude, don't reproduce.</b> The surviving value is a
 * function of the greedy SATURATION FRONTIER — of a previous find()
 * attempt, for the restart variant — not of the accepted path: the
 * accepting path never visits the stale span, so no tagged-NFA edit can
 * express it (#87's sticky split clones write disciplines along TAKEN
 * paths; this value lives on an untaken one). Faithful reproduction would
 * need determinizer-level cross-config register transfers plus
 * matcher-global carry across find() restarts — emulating the backtracking
 * discipline the engine exists to replace (and the runner cluster #98
 * froze just deleted the mutable surfaces such carry would need). The
 * finding's "Prolog/Loop bodies" attribution is corrected here: a TRUE
 * Prolog/Loop shape (non-deterministic body) restores nested writes
 * through GroupTail's save/restore and never diverges — pinned as a
 * control below. {@code CampaignFuzzer}'s
 * {@code SKIP_GROUPCURLY_STALE_WRITE} classifies the exact recorded
 * signature (skeleton-equal, the GroupCurly-nested-capture shape, an
 * oracle span the engines report as none INSIDE the match); this test
 * pins the boundary visibly so the exclusion can never go silent.
 *
 * <p>The DISPLACED twin — the oracle reporting the deepest discarded
 * iteration where the engine reports the live participating one — is a
 * standing divergence the skip deliberately does NOT cover (a span the
 * engine does report is hard evidence); it is pinned below as its own
 * tier.
 */
class GroupCurlyStaleWriteParityTest {

    /** pattern, input, stale oracle spans, clean engine spans. */
    private record Case(String pattern, String input, int[] jurSpans, int[] tdfaSpans) {
    }

    private static Stream<Case> staleWrites() {
        return Stream.of(
            // the campaign's canonical shape (seed 7340427838858965240):
            // backoff to ZERO iterations, tail re-consumes the space
            new Case("((\\s))*\\s", "ab x", new int[]{2, 3, -1, -1, 2, 3}, new int[]{2, 3, -1, -1, -1, -1}),
            // the anonymous wrapper (seed 2135724555999141656's shape): g1
            // itself is the nested capture
            new Case("(?:(b))*b", "xb y", new int[]{1, 2, 1, 2}, new int[]{1, 2, -1, -1}),
            // the supplementary twin (seed 1606260156672107336's shape):
            // one code point, two units
            new Case("((\ud800\udc00))*\ud800\udc00", "x\ud800\udc00y", new int[]{1, 3, -1, -1, 1, 3},
                new int[]{1, 3, -1, -1, -1, -1}),
            // the bounded {0,} spelling (seed 179386094230408747's shape)
            // AND the failed-restart variant: the write came from the
            // attempt at 0 (its `.` matched `x`, its `9` wrote g1 1..2),
            // the accepted attempt at 1 never rewrote it
            new Case("(?:.(9)){0,}\\d", "x9", new int[]{1, 2, 1, 2}, new int[]{1, 2, -1, -1}),
            // capture-in-capture directly quantified, {0,} (the triage
            // record's shape, seed 2858084537373459748 minimized): g2 is
            // the directly-quantified group (java restores it to nil on
            // the exhaust path), g3 the nested stale write
            new Case("((a)){0,}a", "ab", new int[]{0, 1, -1, -1, 0, 1}, new int[]{0, 1, -1, -1, -1, -1}));
    }

    /** The oracle keeps the stale write AND the engine keeps the clean
     *  accepted-path reading. */
    @ParameterizedTest
    @MethodSource("staleWrites")
    void staleWritePinnedBothWays(Case c) {
        assertThat(JurOracle.jurFind(c.pattern(), c.input()))
            .as("live oracle must keep the stale write: %s", c.pattern()).isEqualTo(c.jurSpans());
        for (var factory : JurOracle.engineFactories().toList()) {
            assertThat(JurOracle.tdfaFind(c.pattern(), c.input(), factory))
                .as("engine keeps the accepted path's own reading: %s [%s]", c.pattern(), factory)
                .isEqualTo(c.tdfaSpans());
        }
    }

    /** The displaced twin: the oracle reports the deepest DISCARDED
     *  iteration's span, the engine the live participating iteration's —
     *  a standing divergence the skip does not swallow (our side reports
     *  a span, not none; it stays hard evidence for future campaigns). */
    @ParameterizedTest
    @MethodSource("displacedWrites")
    void displacedWritePinnedBothWays(Case c) {
        assertThat(JurOracle.jurFind(c.pattern(), c.input()))
            .as("live oracle keeps the deepest discarded write: %s", c.pattern()).isEqualTo(c.jurSpans());
        for (var factory : JurOracle.engineFactories().toList()) {
            assertThat(JurOracle.tdfaFind(c.pattern(), c.input(), factory))
                .as("engine keeps the participating iteration's own write: %s [%s]", c.pattern(), factory)
                .isEqualTo(c.tdfaSpans());
        }
    }

    private static Stream<Case> displacedWrites() {
        return Stream.of(
            // seed 1900010144022828840's shape, two iterations drawn: the
            // loop ran twice (g1 1..2, then 2..3), backed off to one, the
            // tail re-consumed the second space — java keeps iteration 2's
            // discarded 2..3, the engine the participating 1..2
            new Case("(?:( ))* ", "x  y", new int[]{1, 3, 2, 3}, new int[]{1, 3, 1, 2}),
            // the bounded {1,3} spelling: same match0 backoff from cmin
            new Case("(?:(b)){1,3}b", "xbb y", new int[]{1, 3, 2, 3}, new int[]{1, 3, 1, 2}));
    }

    /** Shapes whose nested writes DO unwind — both engines agree, no
     *  stale write: the hard evidence the exclusion must not swallow. */
    @ParameterizedTest
    @MethodSource("controls")
    void controlShapesAgree(Case c) {
        for (var factory : JurOracle.engineFactories().toList()) {
            JurOracle.assertSameFind(c.pattern(), c.input(), factory);
        }
    }

    private static Stream<Case> controls() {
        return Stream.of(
            // a LIVE iteration participates (no backoff below it): the
            // tail's own char differs, the loop's last iteration stands
            new Case("(?:(b))*c", "bc", new int[]{0, 2, 0, 1}, new int[]{0, 2, 0, 1}),
            // a TRUE Prolog/Loop shape — the alternation makes the body
            // non-deterministic, so java restores the nested write on the
            // unwind; no divergence to exclude
            new Case("(?:(?:|b)(9))*9", "x9y", new int[]{1, 2, -1, -1}, new int[]{1, 2, -1, -1}),
            // the #87 sticky shape: the inner capture is itself quantified
            // — the sticky split made these compare HARD (they agree)
            new Case("((a)*b)*", "abab", new int[]{0, 4, 0, 4, 0, 1}, new int[]{0, 4, 0, 4, 0, 1}));
    }
}
