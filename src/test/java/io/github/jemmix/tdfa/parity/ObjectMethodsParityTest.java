package io.github.jemmix.tdfa.parity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.assertj.core.api.Assertions.*;

/**
 * Object method parity: toString(), equals(), hashCode(), pattern(),
 * flags(), quote().
 */
class ObjectMethodsParityTest {

    @Test void toStringMatches() {
        assertThat(io.github.jemmix.tdfa.re2j.Pattern.compile("abc").toString())
                .isEqualTo(com.google.re2j.Pattern.compile("abc").toString());
    }

    @Test void equalsSamePattern() {
        var t1 = io.github.jemmix.tdfa.re2j.Pattern.compile("abc");
        var t2 = io.github.jemmix.tdfa.re2j.Pattern.compile("abc");
        assertThat(t1.equals(t2)).isTrue();
    }

    @Test void equalsDifferentPattern() {
        var t1 = io.github.jemmix.tdfa.re2j.Pattern.compile("abc");
        var t2 = io.github.jemmix.tdfa.re2j.Pattern.compile("def");
        assertThat(t1.equals(t2)).isFalse();
    }

    @Test void equalsDifferentFlags() {
        var t1 = io.github.jemmix.tdfa.re2j.Pattern.compile("abc", io.github.jemmix.tdfa.re2j.Pattern.CASE_INSENSITIVE);
        var t2 = io.github.jemmix.tdfa.re2j.Pattern.compile("abc", 0);
        assertThat(t1.equals(t2)).isFalse();
    }

    @Test void equalsNull() {
        assertThat(io.github.jemmix.tdfa.re2j.Pattern.compile("abc").equals(null)).isFalse();
    }

    @Test void hashCodeConsistent() {
        var t1 = io.github.jemmix.tdfa.re2j.Pattern.compile("abc");
        var t2 = io.github.jemmix.tdfa.re2j.Pattern.compile("abc");
        assertThat(t1.hashCode()).isEqualTo(t2.hashCode());
    }

    @Test void patternAccessor() {
        assertThat(io.github.jemmix.tdfa.re2j.Pattern.compile("a(b)c").pattern())
                .isEqualTo("a(b)c");
    }

    @Test void flagsAccessor() {
        assertThat(io.github.jemmix.tdfa.re2j.Pattern.compile("abc",
                io.github.jemmix.tdfa.re2j.Pattern.CASE_INSENSITIVE | io.github.jemmix.tdfa.re2j.Pattern.DOTALL).flags())
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

    @Test void patternResetNoOp() {
        var p = io.github.jemmix.tdfa.re2j.Pattern.compile("abc");
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

    // ---- programSize UOE ----

    @Test void patternProgramSizeThrowsUOE() {
        assertThatThrownBy(() -> io.github.jemmix.tdfa.re2j.Pattern.compile("abc").programSize())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void matcherProgramSizeThrowsUOE() {
        assertThatThrownBy(() -> io.github.jemmix.tdfa.re2j.Pattern.compile("abc").matcher("abc").programSize())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // ---- byte[] UOE ----

    @Test void staticMatchesByteArrayThrowsUOE() {
        assertThatThrownBy(() -> io.github.jemmix.tdfa.re2j.Pattern.matches("a", new byte[]{65}))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void instanceMatchesByteArrayThrowsUOE() {
        assertThatThrownBy(() -> io.github.jemmix.tdfa.re2j.Pattern.compile("a").matches(new byte[]{65}))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void matcherByteArrayThrowsUOE() {
        assertThatThrownBy(() -> io.github.jemmix.tdfa.re2j.Pattern.compile("a").matcher(new byte[]{65}))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void resetByteArrayThrowsUOE() {
        assertThatThrownBy(() -> io.github.jemmix.tdfa.re2j.Pattern.compile("a").matcher("a").reset(new byte[]{65}))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // ---- @Ignore: pending features ----

    @EnabledIfSystemProperty(named = "tdfa.pending", matches = "true") // PENDING: Pattern implements Serializable")
    @Test void patternSerializable() {
        var p = io.github.jemmix.tdfa.re2j.Pattern.compile("abc");
        assertThat(p).isInstanceOf(java.io.Serializable.class);
    }
}
