package io.github.jemmix.tdfa.parity;

import io.github.jemmix.tdfa.core.compile.RegexEngineFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Object method parity: toString(), equals(), hashCode(), pattern(),
 * flags(), quote().
 *
 * <p>Jur twin below, where the JDK oracle has a comparable cell:
 * toString()/pattern()/null-compile. The rest of the suite stays
 * facade-vs-re2j: {@code flags()} numerics deliberately do NOT mirror the
 * JDK's (documented — MULTILINE is 4 here, 8 there), {@code quote()}
 * escapes per-meta re2j-style where the JDK wraps in {@code \Q...\E}, and
 * equals/hashCode are facade-internal.
 */
class ObjectMethodsParityTest {

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void toStringMatches(RegexEngineFactory factory) {
        assertThat(io.github.jemmix.tdfa.Pattern.compile("abc", 0, factory).toString())
            .isEqualTo(com.google.re2j.Pattern.compile("abc").toString());
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void equalsSamePattern(RegexEngineFactory factory) {
        var t1 = io.github.jemmix.tdfa.Pattern.compile("abc", 0, factory);
        var t2 = io.github.jemmix.tdfa.Pattern.compile("abc", 0, factory);
        assertThat(t1.equals(t2)).isTrue();
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void equalsDifferentPattern(RegexEngineFactory factory) {
        var t1 = io.github.jemmix.tdfa.Pattern.compile("abc", 0, factory);
        var t2 = io.github.jemmix.tdfa.Pattern.compile("def", 0, factory);
        assertThat(t1.equals(t2)).isFalse();
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void equalsDifferentFlags(RegexEngineFactory factory) {
        var t1 = io.github.jemmix.tdfa.Pattern.compile("abc", io.github.jemmix.tdfa.Pattern.CASE_INSENSITIVE, factory);
        var t2 = io.github.jemmix.tdfa.Pattern.compile("abc", 0, factory);
        assertThat(t1.equals(t2)).isFalse();
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void equalsNull(RegexEngineFactory factory) {
        assertThat(io.github.jemmix.tdfa.Pattern.compile("abc", 0, factory).equals(null)).isFalse();
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void hashCodeConsistent(RegexEngineFactory factory) {
        var t1 = io.github.jemmix.tdfa.Pattern.compile("abc", 0, factory);
        var t2 = io.github.jemmix.tdfa.Pattern.compile("abc", 0, factory);
        assertThat(t1.hashCode()).isEqualTo(t2.hashCode());
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void patternAccessor(RegexEngineFactory factory) {
        assertThat(io.github.jemmix.tdfa.Pattern.compile("a(b)c", 0, factory).pattern()).isEqualTo("a(b)c");
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void flagsAccessor(RegexEngineFactory factory) {
        assertThat(io.github.jemmix.tdfa.Pattern.compile("abc",
            io.github.jemmix.tdfa.Pattern.CASE_INSENSITIVE | io.github.jemmix.tdfa.Pattern.DOTALL, factory).flags())
            .isEqualTo(io.github.jemmix.tdfa.Pattern.CASE_INSENSITIVE | io.github.jemmix.tdfa.Pattern.DOTALL);
    }

    @Test
    void quoteStatic() {
        assertThat(io.github.jemmix.tdfa.Pattern.quote("a.b*c")).isEqualTo(com.google.re2j.Pattern.quote("a.b*c"));
    }

    @Test
    void quoteEmpty() {
        assertThat(io.github.jemmix.tdfa.Pattern.quote("")).isEqualTo(com.google.re2j.Pattern.quote(""));
    }

    @Test
    void quoteNoMeta() {
        assertThat(io.github.jemmix.tdfa.Pattern.quote("abc")).isEqualTo(com.google.re2j.Pattern.quote("abc"));
    }

    @Test
    void quoteAllMeta() {
        String meta = "\\.+*?()|[]{}^$";
        assertThat(io.github.jemmix.tdfa.Pattern.quote(meta)).isEqualTo(com.google.re2j.Pattern.quote(meta));
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void patternResetNoOp(RegexEngineFactory factory) {
        var p = io.github.jemmix.tdfa.Pattern.compile("abc", 0, factory);
        p.reset(); // should not throw
    }

    // ---- quote with non-ASCII ----

    @Test
    void quoteNonAscii() {
        assertThat(io.github.jemmix.tdfa.Pattern.quote("caf\u00E9."))
            .isEqualTo(com.google.re2j.Pattern.quote("caf\u00E9."));
    }

    @Test
    void quoteSurrogate() {
        String s = new String(Character.toChars(0x10000)) + ".";
        assertThat(io.github.jemmix.tdfa.Pattern.quote(s)).isEqualTo(com.google.re2j.Pattern.quote(s));
    }

    // ---- null pattern ----

    @Test
    void compileNullRejects() {
        assertThatThrownBy(() -> io.github.jemmix.tdfa.Pattern.compile(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> com.google.re2j.Pattern.compile(null)).isInstanceOf(NullPointerException.class);
    }

    // ---- programSize: cost estimate (DFA state count; NOT equal to re2j's NFA-instruction count) ----

    @Test
    void patternProgramSize() {
        // A meaningful, positive cost metric; same value on repeat compile (deterministic).
        int tdfaSize = io.github.jemmix.tdfa.Pattern.compile("abc").programSize();
        assertThat(tdfaSize).isPositive();
        assertThat(io.github.jemmix.tdfa.Pattern.compile("abc").programSize())
            .isEqualTo(io.github.jemmix.tdfa.Pattern.compile("abc").programSize());
        // More complex patterns cost more than simple ones.
        assertThat(io.github.jemmix.tdfa.Pattern.compile("(a|b)*c(d|e)+f").programSize()).isGreaterThan(tdfaSize);
    }

    @Test
    void matcherProgramSize() {
        // Matcher.programSize() reflects its pattern's program.
        int p = io.github.jemmix.tdfa.Pattern.compile("abc").programSize();
        int m = io.github.jemmix.tdfa.Pattern.compile("abc").matcher("abc").programSize();
        assertThat(m).isEqualTo(p);
    }

    // ---- byte[] input (UTF-8 decoded) ----

    @Test
    void staticMatchesByteArray() {
        boolean re2jResult = com.google.re2j.Pattern.matches("a", new byte[]{65});
        boolean tdfaResult = io.github.jemmix.tdfa.Pattern.matches("a", new byte[]{65});
        assertThat(tdfaResult).isEqualTo(re2jResult);
    }

    @Test
    void instanceMatchesByteArray() {
        boolean re2jResult = com.google.re2j.Pattern.compile("a").matches(new byte[]{65});
        boolean tdfaResult = io.github.jemmix.tdfa.Pattern.compile("a").matches(new byte[]{65});
        assertThat(tdfaResult).isEqualTo(re2jResult);
    }

    @Test
    void matcherByteArray() {
        boolean re2jResult = com.google.re2j.Pattern.compile("a").matcher(new byte[]{65}).matches();
        boolean tdfaResult = io.github.jemmix.tdfa.Pattern.compile("a").matcher(new byte[]{65}).matches();
        assertThat(tdfaResult).isEqualTo(re2jResult);
    }

    @Test
    void resetByteArray() {
        var re2jMatcher = com.google.re2j.Pattern.compile("a").matcher("a");
        re2jMatcher.reset(new byte[]{65});
        var tdfaMatcher = io.github.jemmix.tdfa.Pattern.compile("a").matcher("a");
        tdfaMatcher.reset(new byte[]{65});
        assertThat(tdfaMatcher.matches()).isEqualTo(re2jMatcher.matches());
    }

    /**
     * The byte[] path decodes UTF-8, so its match spans are UTF-16 offsets
     * of the decoded text (the surface's documented contract) and its match
     * set is the oracle's STRING-path answer — java's too.
     *
     * <p>The oracle's own byte[] path disagrees with its String path in one
     * known place: {@code UTF8Input.context}'s backward scan never checks
     * whether the byte just before {@code pos} is a lead byte, so at an
     * interior position right after one it decodes the PREVIOUS character,
     * and a zero-width {@code (?m)^} finds a line start inside a multibyte
     * character (the empty-match nudge is what lands there). An oracle bug
     * (TODO.md), not a surface divergence — in released 1.8 and in the
     * snapshot alike: pinned below, so the day upstream fixes it the
     * extra-span assertion fails and the pin retires.
     */
    @Test
    void byteInputAnswersLikeTheOraclesStringPath() {
        String text = "\né";
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        List<Integer> onText = new ArrayList<>();
        for (com.google.re2j.Matcher m = com.google.re2j.Pattern.compile("(?m)^").matcher(text); m.find();) {
            onText.add(m.start());
        }
        List<Integer> onJava = new ArrayList<>();
        for (java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?m)^").matcher(text); m.find();) {
            onJava.add(m.start());
        }
        List<Integer> onBytes = new ArrayList<>();
        for (com.google.re2j.Matcher m = com.google.re2j.Pattern.compile("(?m)^").matcher(bytes); m.find();) {
            onBytes.add(m.start());
        }
        List<Integer> onTdfa = new ArrayList<>();
        for (io.github.jemmix.tdfa.core.engine.Matcher m =
            io.github.jemmix.tdfa.re2j.Pattern.compile("(?m)^").matcher(bytes); m.find();) {
            onTdfa.add(m.start());
        }
        assertThat(onText).as("the oracle on the text").containsExactly(0, 1);
        assertThat(onJava).as("java on the text").isEqualTo(onText);
        // released 1.8 carries a second byte-path bug on top (the one #193
        // fixed): a line start after the multibyte character as well
        List<Integer> expectedOnBytes = new ArrayList<>(onText);
        expectedOnBytes.add(2);
        if (Re2jOracle.releasedOracle()) {
            expectedOnBytes.add(3);
        }
        assertThat(onBytes).as("the oracle on the bytes: line starts inside é").isEqualTo(expectedOnBytes);
        assertThat(onTdfa).as("the surface on the bytes").isEqualTo(onText);
    }

    // ---- Serializable ----

    @Test
    void patternSerializable() {
        var p = io.github.jemmix.tdfa.Pattern.compile("abc");
        assertThat(p).isInstanceOf(Serializable.class);
    }

    /** Jur twin: the JDK-comparable object-method cells. */
    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.JurOracle#engineFactories")
    void jurTwin(RegexEngineFactory factory) {
        assertThat(io.github.jemmix.tdfa.Pattern.compile("a(b)c", 0, factory).toString())
            .isEqualTo(java.util.regex.Pattern.compile("a(b)c").toString()); // both = pattern()
        assertThat(io.github.jemmix.tdfa.Pattern.compile("a(b)c", 0, factory).pattern())
            .isEqualTo(java.util.regex.Pattern.compile("a(b)c").pattern());
        assertThatThrownBy(() -> java.util.regex.Pattern.compile(null)).isInstanceOf(NullPointerException.class); // matches our compileNullRejects above
        assertThat(io.github.jemmix.tdfa.Pattern.matches("abc", "abc"))
            .isEqualTo(java.util.regex.Pattern.matches("abc", "abc"));
    }
}
