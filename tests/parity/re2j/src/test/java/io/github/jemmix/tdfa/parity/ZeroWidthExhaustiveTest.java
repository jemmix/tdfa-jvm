package io.github.jemmix.tdfa.parity;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import io.github.jemmix.tdfa.core.RegexEngineFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exhaustive small-grammar enumeration of the zero-width corner that produced
 * the 2026-08 fuzz findings: {zero-width atoms} × {quantifiers} × {grouping} ×
 * {prefix/suffix consumption}. Random fuzzing rediscovered this finite space
 * at ~1/26k cases; enumeration covers it deterministically.
 *
 * <p><b>Status: gated.</b> The final-ops families (skipped-group null-ness,
 * span-inversion crashes, mask-dependent accept winners) are fixed and hard-
 * gated in {@link FinalOpsParityTest}. What remains open here is the
 * stop-or-extend priority family (e.g. {@code (\S*\B)*} extent shapes,
 * {@code (?:.*?9{0,}\b){1,}} empty-iteration preference — TODO.md "zero-width
 * assertions are second-class automaton citizens"). Run the full enumeration
 */
class ZeroWidthExhaustiveTest {

    static final String[] ATOMS = {"\\b", "\\B", "^", "$", "\\A", "\\z"};
    static final String[] QUANTS = {"", "?", "*", "+", "{0,1}", "{1,2}", "??", "*?", "+?"};
    static final String[] PREFIX = {"", ".", "a", "\\w", ".+"};
    static final String[] SUFFIX = {"", ".", "a", "x", "\\W"};

    static List<String> patterns() {
        List<String> out = new ArrayList<>();
        for (String atom : ATOMS)
            for (String q : QUANTS)
                for (String pre : PREFIX)
                    for (String suf : SUFFIX) {
                        out.add(pre + atom + q + suf);
                        out.add(pre + "(" + atom + ")" + q + suf);
                    }
        // Multiline variants (inline (?m:) scoping).
        for (String atom : new String[]{"^", "$"})
            for (String q : QUANTS)
                for (String pre : PREFIX)
                    for (String suf : SUFFIX)
                        out.add(pre + "(?m:" + atom + ")" + q + suf);
        return out;
    }

    static List<String> inputs() {
        return List.of(
                "ab",
                "a b",
                "  x  ",
                "a\nb",
                "\na\n",
                "Ω9\ud800\udfff",
                "ab\udc21\ud800x",
                "\udc00\ud800\r",
                "aaaa bbbb aaaa",
                "€一漢 x");
    }

    static record Case(String pattern, String input) {}

    static Stream<Arguments> cases() {
        List<Arguments> out = new ArrayList<>();
        List<RegexEngineFactory> factories = Re2jOracle.engineFactories().toList();
        for (String p : patterns())
            for (String i : inputs())
                for (RegexEngineFactory f : factories)
                    out.add(Arguments.of(new Case(p, i), f));
        return out.stream();
    }

    @ParameterizedTest
    @MethodSource("cases")
    void zeroWidthCornerMatchesRe2j(Case c, RegexEngineFactory factory) {
        String expected = re2jProtocol(c.pattern(), c.input());
        String actual = tdfaProtocol(c.pattern(), c.input(), factory);
        assertThat(actual)
                .as("pattern=\"%s\" input-encoded=\"%s\" [%s]", c.pattern(), escape(c.input()), factory)
                .isEqualTo(expected);
        // Layered audit: the PikeSim reference (over our Tnfa, no determinizer)
        // must also agree — sim-vs-DFA disagreement on any enumerated case is a
        // determinizer bug, pre-localized by construction.
        assertThat(simProtocol(c.pattern(), c.input()))
                .as("sim-vs-re2j pattern=\"%s\" input-encoded=\"%s\"", c.pattern(), escape(c.input()))
                .isEqualTo(expected);
    }

    /** PikeSim reference in this test's protocol — the audit's fourth column. */
    private static String simProtocol(String pattern, String input) {
        var m = io.github.jemmix.tdfa.sim.PikeSim.compile(pattern, com.google.re2j.Re2jUnicodeProvider.INSTANCE).matcher(input);
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

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c > 0x7E) sb.append(String.format("\\u%04x", (int) c));
            else sb.append(c);
        }
        return sb.toString();
    }
}
