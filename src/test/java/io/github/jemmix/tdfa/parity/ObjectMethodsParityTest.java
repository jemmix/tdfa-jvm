package io.github.jemmix.tdfa.parity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import io.github.jemmix.tdfa.EngineFactory;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.assertj.core.api.Assertions.*;

/**
 * Object method parity: toString(), equals(), hashCode(), pattern(),
 * flags(), quote().
 */
class ObjectMethodsParityTest {

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void toStringMatches(EngineFactory factory) {
        assertThat(io.github.jemmix.tdfa.re2j.Pattern.compile("abc", 0, factory).toString())
                .isEqualTo(com.google.re2j.Pattern.compile("abc").toString());
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void equalsSamePattern(EngineFactory factory) {
        var t1 = io.github.jemmix.tdfa.re2j.Pattern.compile("abc", 0, factory);
        var t2 = io.github.jemmix.tdfa.re2j.Pattern.compile("abc", 0, factory);
        assertThat(t1.equals(t2)).isTrue();
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void equalsDifferentPattern(EngineFactory factory) {
        var t1 = io.github.jemmix.tdfa.re2j.Pattern.compile("abc", 0, factory);
        var t2 = io.github.jemmix.tdfa.re2j.Pattern.compile("def", 0, factory);
        assertThat(t1.equals(t2)).isFalse();
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void equalsDifferentFlags(EngineFactory factory) {
        var t1 = io.github.jemmix.tdfa.re2j.Pattern.compile("abc", io.github.jemmix.tdfa.re2j.Pattern.CASE_INSENSITIVE, factory);
        var t2 = io.github.jemmix.tdfa.re2j.Pattern.compile("abc", 0, factory);
        assertThat(t1.equals(t2)).isFalse();
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void equalsNull(EngineFactory factory) {
        assertThat(io.github.jemmix.tdfa.re2j.Pattern.compile("abc", 0, factory).equals(null)).isFalse();
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void hashCodeConsistent(EngineFactory factory) {
        var t1 = io.github.jemmix.tdfa.re2j.Pattern.compile("abc", 0, factory);
        var t2 = io.github.jemmix.tdfa.re2j.Pattern.compile("abc", 0, factory);
        assertThat(t1.hashCode()).isEqualTo(t2.hashCode());
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void patternAccessor(EngineFactory factory) {
        assertThat(io.github.jemmix.tdfa.re2j.Pattern.compile("a(b)c", 0, factory).pattern())
                .isEqualTo("a(b)c");
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void flagsAccessor(EngineFactory factory) {
        assertThat(io.github.jemmix.tdfa.re2j.Pattern.compile("abc",
                io.github.jemmix.tdfa.re2j.Pattern.CASE_INSENSITIVE | io.github.jemmix.tdfa.re2j.Pattern.DOTALL, factory).flags())
                .isEqualTo(io.github.jemmix.tdfa.re2j.Pattern.CASE_INSENSITIVE | io.github.jemmix.tdfa.re2j.Pattern.DOTALL);
    }

    @Test void quoteStatic() {
        assertThat(io.github.jemmix.tdfa.re2j.Pattern.quote("a.b*c"))
                .isEqualTo(com.google.re2j.Pattern.quote("a.b*c"));
    }

    @Test void quoteEmpty() {
        assertThat(io.github.jemmix.tdfa.re2j.Pattern.quote(""))
                .isEqualTo(com.google.re2j.Pattern.quote(""));
    }

    @Test void quoteNoMeta() {
        assertThat(io.github.jemmix.tdfa.re2j.Pattern.quote("abc"))
                .isEqualTo(com.google.re2j.Pattern.quote("abc"));
    }

    @Test void quoteAllMeta() {
        String meta = "\\.+*?()|[]{}^$";
        assertThat(io.github.jemmix.tdfa.re2j.Pattern.quote(meta))
                .isEqualTo(com.google.re2j.Pattern.quote(meta));
    }

    @ParameterizedTest
    @MethodSource("io.github.jemmix.tdfa.parity.Re2jOracle#engineFactories")
    void patternResetNoOp(EngineFactory factory) {
        var p = io.github.jemmix.tdfa.re2j.Pattern.compile("abc", 0, factory);
        p.reset(); // should not throw
    }

    // ---- quote with non-ASCII ----

    @Test void quoteNonAscii() {
        assertThat(io.github.jemmix.tdfa.re2j.Pattern.quote("caf\u00E9."))
                .isEqualTo(com.google.re2j.Pattern.quote("caf\u00E9."));
    }

    @Test void quoteSurrogate() {
        String s = new String(Character.toChars(0x10000)) + ".";
        assertThat(io.github.jemmix.tdfa.re2j.Pattern.quote(s))
                .isEqualTo(com.google.re2j.Pattern.quote(s));
    }

    // ---- null pattern ----

    @Test void compileNullRejects() {
        assertThatThrownBy(() -> io.github.jemmix.tdfa.re2j.Pattern.compile(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> com.google.re2j.Pattern.compile(null))
                .isInstanceOf(NullPointerException.class);
    }

    // ---- programSize: cost estimate (DFA state count; NOT equal to re2j's NFA-instruction count) ----

    @Test void patternProgramSize() {
        // A meaningful, positive cost metric; same value on repeat compile (deterministic).
        int tdfaSize = io.github.jemmix.tdfa.re2j.Pattern.compile("abc").programSize();
        assertThat(tdfaSize).isPositive();
        assertThat(io.github.jemmix.tdfa.re2j.Pattern.compile("abc").programSize())
                .isEqualTo(io.github.jemmix.tdfa.re2j.Pattern.compile("abc").programSize());
        // More complex patterns cost more than simple ones.
        assertThat(io.github.jemmix.tdfa.re2j.Pattern.compile("(a|b)*c(d|e)+f").programSize())
                .isGreaterThan(tdfaSize);
    }

    @Test void matcherProgramSize() {
        // Matcher.programSize() reflects its pattern's program.
        int p = io.github.jemmix.tdfa.re2j.Pattern.compile("abc").programSize();
        int m = io.github.jemmix.tdfa.re2j.Pattern.compile("abc").matcher("abc").programSize();
        assertThat(m).isEqualTo(p);
    }

    // ---- byte[] input (UTF-8 decoded) ----

    @Test void staticMatchesByteArray() {
        boolean re2jResult = com.google.re2j.Pattern.matches("a", new byte[]{65});
        boolean tdfaResult = io.github.jemmix.tdfa.re2j.Pattern.matches("a", new byte[]{65});
        assertThat(tdfaResult).isEqualTo(re2jResult);
    }

    @Test void instanceMatchesByteArray() {
        boolean re2jResult = com.google.re2j.Pattern.compile("a").matches(new byte[]{65});
        boolean tdfaResult = io.github.jemmix.tdfa.re2j.Pattern.compile("a").matches(new byte[]{65});
        assertThat(tdfaResult).isEqualTo(re2jResult);
    }

    @Test void matcherByteArray() {
        boolean re2jResult = com.google.re2j.Pattern.compile("a").matcher(new byte[]{65}).matches();
        boolean tdfaResult = io.github.jemmix.tdfa.re2j.Pattern.compile("a").matcher(new byte[]{65}).matches();
        assertThat(tdfaResult).isEqualTo(re2jResult);
    }

    @Test void resetByteArray() {
        var re2jMatcher = com.google.re2j.Pattern.compile("a").matcher("a");
        re2jMatcher.reset(new byte[]{65});
        var tdfaMatcher = io.github.jemmix.tdfa.re2j.Pattern.compile("a").matcher("a");
        tdfaMatcher.reset(new byte[]{65});
        assertThat(tdfaMatcher.matches()).isEqualTo(re2jMatcher.matches());
    }

    // ---- @Ignore: pending features ----

    @EnabledIfSystemProperty(named = "tdfa.pending", matches = "true") // PENDING: Pattern implements Serializable")
    @Test void patternSerializable() {
        var p = io.github.jemmix.tdfa.re2j.Pattern.compile("abc");
        assertThat(p).isInstanceOf(java.io.Serializable.class);
    }
}
