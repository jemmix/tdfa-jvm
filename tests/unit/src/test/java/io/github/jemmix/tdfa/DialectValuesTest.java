package io.github.jemmix.tdfa;

import io.github.jemmix.tdfa.core.dialect.ClassTable;
import io.github.jemmix.tdfa.core.dialect.EscapeAlphabet;
import io.github.jemmix.tdfa.core.dialect.Terminators;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The {@code core.dialect} value types — the data a compile's
 * interpretation policy is made of (see
 * {@code docs/dialect-agnostic-core.md}). Presets pin the two stock
 * readings exactly (the JDK terminator set with its {@code \r\n} pair
 * and the {@code \n}-only set reproduce the parser's DOT constants
 * range-for-range); validation rejects malformed sets loudly — a
 * malformed value is a construction bug, not a reading.
 */
class DialectValuesTest {

    @Test
    void jdkTerminatorSet() {
        assertThat(Terminators.JDK.isUnit('\n')).isTrue();
        assertThat(Terminators.JDK.isUnit('\r')).isTrue();
        assertThat(Terminators.JDK.isUnit('\u0085')).isTrue();
        assertThat(Terminators.JDK.isUnit('\u2028')).isTrue();
        assertThat(Terminators.JDK.isUnit('\u2029')).isTrue();
        assertThat(Terminators.JDK.isUnit('\u000B')).isFalse();
        assertThat(Terminators.JDK.isUnit('a')).isFalse();
        assertThat(Terminators.JDK.hasPair()).isTrue();
        assertThat(Terminators.JDK.pairLo()).isEqualTo('\r');
        assertThat(Terminators.JDK.pairHi()).isEqualTo('\n');
        assertThat(Terminators.JDK.unitCount()).isEqualTo(5);
    }

    @Test
    void nlOnlyTerminatorSet() {
        assertThat(Terminators.NL_ONLY.isUnit('\n')).isTrue();
        assertThat(Terminators.NL_ONLY.isUnit('\r')).isFalse();
        assertThat(Terminators.NL_ONLY.hasPair()).isFalse();
        assertThat(Terminators.NL_ONLY.pairLo()).isEqualTo(0);
        assertThat(Terminators.NL_ONLY.pairHi()).isEqualTo(0);
    }

    /** The complements must equal the parser's DOT/DOT_JUR constants
     *  range-for-range — the stock-lane parse output is bit-identical
     *  only if the generated complement is the constant it replaces. */
    @Test
    void dotRangesPinTheParserConstants() {
        assertThat(Terminators.NL_ONLY.dotRanges()).containsExactly(0, '\n' - 1, '\n' + 1, 0x10FFFF);
        assertThat(Terminators.JDK.dotRanges()).containsExactly(0, '\n' - 1, 0x0B, '\r' - 1, '\r' + 1, 0x84, 0x86,
            0x2027, 0x202A, 0x10FFFF);
    }

    @Test
    void customAndValidation() {
        // Custom set, unsorted input, equality with the sorted spelling
        Terminators t = Terminators.of('\r', '\n');
        assertThat(t).isEqualTo(Terminators.of('\n', '\r'));
        assertThat(t).isNotEqualTo(Terminators.of('\n'));
        assertThat(t.hashCode()).isEqualTo(Terminators.of('\n', '\r').hashCode());
        assertThatThrownBy(Terminators::of).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Terminators.of('\n', '\n')).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("duplicate");
        assertThatThrownBy(() -> Terminators.of(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Terminators.of(0x110000)).isInstanceOf(IllegalArgumentException.class);
        int[] tooMany = new int[Terminators.MAX_UNITS + 1];
        for (int i = 0; i < tooMany.length; i++) {
            tooMany[i] = i + 1;
        }
        assertThatThrownBy(() -> Terminators.of(tooMany)).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("exceeds");
        assertThatThrownBy(() -> Terminators.ofPair('\r', '\n', '\n')).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("not a set member");
        assertThatThrownBy(() -> Terminators.ofPair('\n', '\n', '\n')).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("halves must differ");
        assertThatThrownBy(() -> Terminators.ofPair(0, '\n', '\n')).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void defensiveCopies() {
        int[] units = Terminators.JDK.units();
        units[0] = 'a';
        assertThat(Terminators.JDK.units()).doesNotContain('a');
        int[] dot = Terminators.JDK.dotRanges();
        dot[0] = -5;
        assertThat(Terminators.JDK.dotRanges()[0]).isZero();
        int[] ranges = ClassTable.SPACE_JDK.ranges();
        ranges[0] = -5;
        assertThat(ClassTable.SPACE_JDK.ranges()[0]).isEqualTo('\t');
    }

    /** SPACE_JDK includes U+000B, SPACE_RE2 excludes it — the one
     *  codepoint the two stock ASCII {@code \s} tables disagree on. */
    @Test
    void spaceTablePresets() {
        assertThat(ClassTable.SPACE_JDK.ranges()).containsExactly('\t', '\r', ' ', ' ');
        assertThat(ClassTable.SPACE_RE2.ranges()).containsExactly('\t', '\n', '\f', '\r', ' ', ' ');
        assertThat(ClassTable.SPACE_JDK).isNotEqualTo(ClassTable.SPACE_RE2);
        assertThatThrownBy(() -> ClassTable.ofRanges(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ClassTable.ofRanges('b', 'a')).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ClassTable.ofRanges('a', 'c', 'b', 'd')).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("overlap");
        assertThatThrownBy(() -> ClassTable.ofRanges('a', 0x110000)).isInstanceOf(IllegalArgumentException.class);
        assertThat(ClassTable.ofRanges('a', 'z')).isEqualTo(ClassTable.ofRanges('a', 'z'));
    }

    @Test
    void escapeAlphabetVocabulary() {
        assertThat(EscapeAlphabet.values()).containsExactly(EscapeAlphabet.JDK, EscapeAlphabet.RE2_MINIMAL);
    }

    @Test
    void honestPrinting() {
        assertThat(Terminators.NL_ONLY.toString()).contains("U+000A").doesNotContain("pair");
        assertThat(Terminators.JDK.toString()).contains("pair(U+000D,U+000A)");
        assertThat(ClassTable.SPACE_JDK.toString()).contains("U+0009").contains("U+000D").contains("U+0020");
    }
}
