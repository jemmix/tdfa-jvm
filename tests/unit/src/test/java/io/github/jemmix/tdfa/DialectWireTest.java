package io.github.jemmix.tdfa;

import io.github.jemmix.tdfa.core.compile.CompileOptions;
import io.github.jemmix.tdfa.core.compile.PatternMatcher;
import io.github.jemmix.tdfa.core.compile.SerialProxy;
import io.github.jemmix.tdfa.core.compile.TDFAPattern;
import io.github.jemmix.tdfa.core.dialect.ClassTable;
import io.github.jemmix.tdfa.core.dialect.EscapeAlphabet;
import io.github.jemmix.tdfa.core.dialect.FlagBindings;
import io.github.jemmix.tdfa.core.dialect.Terminators;
import io.github.jemmix.tdfa.core.tnfa.Semantics;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InvalidObjectException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The dialect wire form (see {@code docs/dialect-agnostic-core.md}):
 * v1 bits translate (each retired bit spells its stock component), the
 * v2 component payloads restore customs exactly, and garbage fails
 * loudly — never a silent fallback to the stock dialect the bits
 * approximate to.
 */
class DialectWireTest {

    /** The retired v1 bits, all four, plus every policy bit. */
    private static final int V1_ALL = 1 | 64 | 128 | 512 | 2 | 4 | 8 | 16 | 32 | 256;

    @Test
    void v1BitsTranslateToStockComponents() {
        Semantics fromBits = Semantics.ofBits(V1_ALL);
        Semantics expected = Semantics.of().unicodeCase().codepointBoundaries().emptyLastLine().endOfTextOnly()
            .emptyIterationSpans().shorthandFold().terminators(Terminators.NL_ONLY).spaceTable(ClassTable.SPACE_RE2)
            .escapes(EscapeAlphabet.RE2_MINIMAL).flagBindings(FlagBindings.RE2);
        assertThat(fromBits).isEqualTo(expected);
        // and the bit encoding is the fixed point: bits → policy → bits
        assertThat(fromBits.bits()).isEqualTo(V1_ALL);
        assertThat(Semantics.ofBits(0)).isEqualTo(Semantics.of());
        assertThatThrownBy(() -> Semantics.ofBits(1 << 20)).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("unknown semantics axis bits");
    }

    @Test
    void customPolicyRoundTripsTheWire() throws Exception {
        // A custom everything: \r-only terminators, a VT-less \s table,
        // the RE2 alphabet — the terminators and table have no v1 bit.
        Semantics custom = Semantics.of().terminators(Terminators.of('\r'))
            .spaceTable(ClassTable.ofRanges('\t', '\n', ' ', ' ')).flagBindings(FlagBindings.RE2);
        assertThat(custom.bits()).isEqualTo(64); // only the RE2 alphabet's v1 bit
        Pattern p = Pattern.compile("(?m)^b", CompileOptions.of().semantics(custom));
        // \r is a terminator here, \n is not: ^ fires after \r, never after \n
        PatternMatcher afterCr = p.matcher("a\rb");
        assertThat(afterCr.find()).isTrue();
        assertThat(afterCr.start()).isEqualTo(2);
        assertThat(p.matcher("\nb").find()).as("^ never fires after \\n under the custom set").isFalse();

        Pattern back = roundTrip(p);
        assertThat(back).isEqualTo(p); // TDFAPattern equality — policy included
        PatternMatcher m = back.matcher("a\rb");
        assertThat(m.find()).isTrue();
        assertThat(m.start()).isEqualTo(2);
        assertThat(back.matcher("\nb").find()).as("the custom set survived the wire").isFalse();
    }

    @Test
    void stockPoliciesRoundTripTheWire() throws Exception {
        Pattern jur = roundTrip(Pattern.compile("\\sa", CompileOptions.of()));
        assertThat(jur).isEqualTo(Pattern.compile("\\sa", CompileOptions.of()));
        // the stock JDK table: U+000B IS whitespace
        assertThat(jur.matcher("\u000B").find()).isFalse(); // "\sa" needs the a

        io.github.jemmix.tdfa.re2j.Pattern re2 = roundTrip(io.github.jemmix.tdfa.re2j.Pattern.compile("\\s"));
        Semantics re2Sem = ((TDFAPattern) re2).semantics();
        assertThat(re2Sem.spaceTable()).isEqualTo(ClassTable.SPACE_RE2);
        assertThat(re2Sem.terminators()).isEqualTo(Terminators.NL_ONLY);
        assertThat(re2.matcher("\u000B").find()).as("the RE2 \\s excludes VT").isFalse();
        assertThat(re2.matcher(" ").find()).isTrue();
    }

    @Test
    void garbagePayloadsFailLoudly() {
        // Unknown escape alphabet name
        assertThatThrownBy(
            () -> SerialProxy.semantics(0, new SerialProxy.Components(null, 0, 0, null, "NOPE", null, null)))
            .isInstanceOf(InvalidObjectException.class).hasMessageContaining("dialect policy");
        // Malformed terminator set (duplicate unit)
        assertThatThrownBy(() -> SerialProxy.semantics(0,
            new SerialProxy.Components(new int[]{'\n', '\n'}, 0, 0, null, null, null, null)))
            .isInstanceOf(InvalidObjectException.class);
        // Malformed space table (overlapping ranges)
        assertThatThrownBy(() -> SerialProxy.semantics(0,
            new SerialProxy.Components(null, 0, 0, new int[]{'a', 'z', 'b', 'c'}, null, null, null)))
            .isInstanceOf(InvalidObjectException.class);
        // Unknown action name
        assertThatThrownBy(() -> SerialProxy.semantics(0,
            new SerialProxy.Components(null, 0, 0, null, null, new char[]{'i'}, new String[]{"NOT_AN_ACTION"})))
            .isInstanceOf(InvalidObjectException.class);
        // Unknown bits still reject before any payload is consulted
        assertThatThrownBy(() -> SerialProxy.semantics(1 << 25, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @SuppressWarnings("unchecked")
    private static <T> T roundTrip(T p) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bos)) {
            out.writeObject(p);
        }
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bos.toByteArray()))) {
            return (T) in.readObject();
        }
    }
}
