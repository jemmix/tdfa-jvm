package io.github.jemmix.tdfa.parity;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import io.github.jemmix.tdfa.core.RegexEngineFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Final-ops correctness: eager φ at accept-record time, no ops from
 * mask-failing transitions, and the position-aware final-ops table
 * (per-(state, posFlags) accept-config winner). Regression coverage for the
 * 2026-08-28 fuzz round: the "skipped group reports empty instead of null"
 * family, the start&gt;end group-span crashes, and the {@code .+\b.}
 * undershoot before a supplementary pair. All were fixed by construction
 * (BT22 match-declaration semantics; the register file is a function of the
 * accepted path only) — these tests are the tripwire, not the fix.
 *
 * <p>The comparison protocol includes per-group NULL-ness — re2j reports a
 * non-participating group as {@code null}, an empty-span group as {@code ""}.
 */
class FinalOpsParityTest {

    /** "true <group> <groupCount> [g1]..." with null groups skipped — re2j side. */
    private static String re2jProtocol(String pattern, String input) {
        var m = com.google.re2j.Pattern.compile(pattern).matcher(input);
        StringBuilder sb = new StringBuilder();
        boolean found = m.find();
        sb.append(found ? "true " + m.group() : "false").append(' ').append(m.groupCount());
        if (found)
            for (int i = 1; i <= m.groupCount(); i++) {
                String g = m.group(i);
                if (g != null) sb.append(" <").append(g).append('>');
            }
        return sb.toString();
    }

    private static String tdfaProtocol(String pattern, String input, RegexEngineFactory factory) {
        var m = io.github.jemmix.tdfa.Pattern.compile(pattern, 0, factory, com.google.re2j.Re2jUnicodeProvider.INSTANCE).matcher(input);
        StringBuilder sb = new StringBuilder();
        boolean found = m.find();
        sb.append(found ? "true " + m.group() : "false").append(' ').append(m.groupCount());
        if (found)
            for (int i = 1; i <= m.groupCount(); i++) {
                String g = m.group(i);
                if (g != null) sb.append(" <").append(g).append('>');
            }
        return sb.toString();
    }

    private static void assertSameGroups(String pattern, String input, RegexEngineFactory factory) {
        assertThat(tdfaProtocol(pattern, input, factory))
                .as("pattern=\"%s\" input-encoded=\"%s\" [%s]", pattern, escape(input), factory)
                .isEqualTo(re2jProtocol(pattern, input));
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c > 0x7E) sb.append(String.format("\\u%04x", (int) c));
            else sb.append(c);
        }
        return sb.toString();
    }

    // ---- the skipped-group family: zero-width content under a quantifier ----

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void skippedOptionalBoundaryGroupIsNull(RegexEngineFactory factory) {
        // Before the fix: g1 = "" instead of null (dead-transition tag leak).
        assertSameGroups("((?s:\\b))?", "\udc00\ud800\r", factory);
        assertSameGroups("(\\b)?", " ", factory);
        assertSameGroups("(\\b)?", "x", factory);   // boundary holds: group participates
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void zeroIterationStarGroupIsNull(RegexEngineFactory factory) {
        assertSameGroups("(\\z)*", "b", factory);
        assertSameGroups("(a\\z)*", "b", factory);
        assertSameGroups("(b\\z)*", "b", factory);  // one real iteration
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void emptyIterationUnderBoundedRepeat(RegexEngineFactory factory) {
        // Fuzz crash family: partial tag writes inverted group spans
        // (StringIndexOutOfBoundsException start > end).
        assertSameGroups("(\\W?){3,}", "€一x", factory);
        assertSameGroups("(.?){3,}a", "zza", factory);
        assertSameGroups("(.{0,5}){3,}", "-~x", factory);
        assertSameGroups("(\\w(\\b)?)+", "ab cd", factory);
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void anchorsUnderQuantifiers(RegexEngineFactory factory) {
        assertSameGroups("($)?", "x", factory);
        assertSameGroups("(\\A)?.+", "ab", factory);
        assertSameGroups("(\\A)?", "ab", factory);
        assertSameGroups("(\\z)?", "ab", factory);
        assertSameGroups("(\\b)*", " ", factory);
        assertSameGroups("(\\b){0,1}", " ", factory);
        assertSameGroups("(\\A)??.+", "ab", factory);
    }

    // ---- stop-or-extend: greedy/lazy exits through assertions ----

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void stopOrExtendThroughAssertions(RegexEngineFactory factory) {
        // greedy class-continue must outrank a \b\W exit at a live boundary,
        // then extend through the boundary (was: stopped at the first accept)
        assertSameGroups("[^0\ud801\udc21\u3042]+\\b\\W", "\ud807\udc07\ud807\udc07Z\udc21z\ud800\udc00", factory);
        assertSameGroups("[^0x]+\\b\\W", "abz c", factory);
        // lazy +? exit through \s*\B: stop where the higher-priority greedy
        // \s-consume dies (pike-cut + dead marker; was: extended one char too far)
        assertSameGroups("\\D+?\\s*\\B", "a\u00df#", factory);
        // lazy inner {3,5}? with \B iteration gates
        assertSameGroups("(?:..{3,5}?\\B)+\\S", "ab\u00df#", factory);
        // .+ \b . before supplementary pairs (two candidate boundaries)
        assertSameGroups(".+\\b.", "\u03a99\ud800\udfff", factory);
        assertSameGroups(".+\\b.", "9\ud800\udfff", factory);
        assertSameGroups(".+\\b\\D.", "\u03a99\ud800\udc00x", factory);
    }

    // ---- group-scoped (?m:...) anchor flavors ----

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void groupScopedMultilineAnchors(RegexEngineFactory factory) {
        assertSameGroups("(?m:\\S.$)", "ab\ncd", factory);
        assertSameGroups("\\S(?m:$)", "ab\ncd", factory);
        assertSameGroups("(?m:$)", "ab\ncd", factory);
        assertSameGroups("\\D(?m:\\S.$)", "ab\ncd", factory);
        assertSameGroups("(?m:^\\w)", "ab\ncd", factory);
        // bare ^/$ stay absolute without (?m) — incl. inside a (?m:...) sibling
        assertSameGroups("$x(?m:$)", "x\ny", factory);
    }

    // ---- long inputs: the ASM tier's emitted ladder (>64 chars, no
    //      short-input delegation) must agree with the VM tier ----

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void longInputEmittedLadderAgrees(RegexEngineFactory factory) {
        String pad = "lorem ipsum dolor sit amet consectetur adipiscing elit sed do ";
        assertSameGroups("((?s:\\b))?", pad + "\udc00\ud800\r", factory);
        assertSameGroups("(\\W?){3,}", pad + "€一x", factory);
        assertSameGroups("x(\\b)?(\\z)?", pad + "x", factory);
        assertSameGroups("(a(\\b)?)+", pad + "ab cd", factory);
    }

    // NOT yet gated (stop-or-extend priority family, TODO.md "zero-width
    // assertions are second-class automaton citizens"): `.+\b.` undershoot
    // before a supplementary pair — re2j matches the full `Ω9𐏿` where we
    // stop at `Ω9`. Covered by ZeroWidthExhaustiveTest (tdfa.pending-gated)
    // until the alphabet promotion lands.
}
