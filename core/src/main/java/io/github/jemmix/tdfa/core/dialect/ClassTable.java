package io.github.jemmix.tdfa.core.dialect;

import java.io.Serializable;
import java.util.Arrays;

/**
 * A predefined character-class table — the data behind the
 * {@code \s}/{@code \S} axis, carried by a compile's {@code Semantics}
 * and consumed at the parse (materialized into {@code CharClass}
 * ranges, so it never reaches match time). Ascending lo/hi ranges, the
 * {@code int[]} convention the parser's tables and
 * {@code UnicodeDataProvider} already use.
 *
 * <p>Stock presets are named after the engine whose table they quote
 * ({@link #SPACE_RE2} keeps the RE2 family's ASCII {@code \s} without
 * U+000B; {@link #SPACE_JDK} is java.util.regex's with it); a custom
 * table is data like any other — the vocabulary is lineage-free, only
 * the preset names remember a lineage
 * (see {@code docs/dialect-agnostic-core.md}).
 *
 * <p>Immutable value class — field equality over the ranges. The
 * Unicode {@code IsWhite_Space} reading ({@code (?u)} {@code \s}) is
 * provider-supplied, not a preset: when {@code UNICODE_CHARACTER_CLASS}
 * is in scope the provider's table wins, as before.
 */
public final class ClassTable implements Serializable {

    private static final long serialVersionUID = 1L;

    /** java.util.regex's ASCII {@code \s}: {@code [ \t\n\x0B\f\r]} —
     *  U+000B IS whitespace. */
    public static final ClassTable SPACE_JDK = ofRanges('\t', '\r', ' ', ' ');

    /** The RE2 family's ASCII {@code \s}: {@code [\t\n\f\r ]} —
     *  U+000B is NOT whitespace (re2j's table). */
    public static final ClassTable SPACE_RE2 = ofRanges('\t', '\n', '\f', '\r', ' ', ' ');

    private static final int MAX_CP = 0x10FFFF;

    private final int[] ranges;

    private ClassTable(int[] ranges) {
        this.ranges = ranges;
    }

    /**
     * A table from ascending lo/hi pairs. Overlapping, unsorted or
     * out-of-range ranges reject — a malformed table is a construction
     * bug, not a reading. The input is copied.
     */
    public static ClassTable ofRanges(int... loHi) {
        if (loHi == null || loHi.length == 0 || (loHi.length & 1) != 0) {
            throw new IllegalArgumentException("class-table ranges are lo/hi pairs: " + Arrays.toString(loHi));
        }
        int[] ranges = loHi.clone();
        for (int i = 0; i < ranges.length; i += 2) {
            int lo = ranges[i];
            int hi = ranges[i + 1];
            if (lo > hi || lo < 0 || hi > MAX_CP) {
                throw new IllegalArgumentException("class-table range out of order or range: " + lo + ".." + hi);
            }
            if (i > 0 && lo <= ranges[i - 1]) {
                throw new IllegalArgumentException("class-table ranges overlap at " + lo + " (after "
                    + ranges[i - 1] + ")");
            }
        }
        return new ClassTable(ranges);
    }

    /** The ranges, ascending lo/hi pairs — a fresh copy (consumed at
     *  parse; the artifact carries its own CharClass from here). */
    public int[] ranges() {
        return ranges.clone();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ClassTable)) {
            return false;
        }
        return Arrays.equals(ranges, ((ClassTable) o).ranges);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(ranges) * 31 + ClassTable.class.hashCode();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("ClassTable[");
        for (int i = 0; i < ranges.length; i += 2) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(String.format("U+%04X", ranges[i]));
            if (ranges[i] != ranges[i + 1]) {
                sb.append(String.format("-U+%04X", ranges[i + 1]));
            }
        }
        return sb.append(']').toString();
    }
}
