package io.github.jemmix.tdfa.core.dialect;

import java.io.Serializable;
import java.util.Arrays;

/**
 * A set of line-terminator codepoints — the data behind the
 * {@code .}/{@code ^}/{@code $} terminator axis, carried by a compile's
 * {@code Semantics} and consumed by the parser (the DOT complement),
 * the runner and the ASM emitter (the line predicates). A value is a
 * list of single codepoints plus at most one <b>ordered pair</b> whose
 * interior is not a line boundary: the JDK's set is five units with the
 * {@code \r\n} pair; {@code \n}-only (the JDK {@code UNIX_LINES}
 * reading, shared with the RE2 lineage) is one unit and no pair.
 *
 * <p>Stock presets may be named after the engine whose set they quote
 * ({@link #JDK}) or the reading they select ({@link #NL_ONLY}); a
 * custom set is data like any other — the vocabulary is lineage-free,
 * only the preset names remember a lineage
 * (see {@code docs/dialect-agnostic-core.md}).
 *
 * <p>Immutable value class — field equality over units and pair. The
 * membership probe is a linear scan over at most {@value #MAX_UNITS}
 * units (the compare chain the hand-written predicates emitted);
 * {@link #dotRanges()} is precomputed at construction. Serializable as
 * its parts ({@link #of}/{@link #ofPair} rebuild deterministically;
 * the wire form is the proxies' payload, not this object).
 */
public final class Terminators implements Serializable {

    private static final long serialVersionUID = 1L;

    /** The full java.util.regex set: {@code \n \r} U+0085 U+2028 U+2029,
     *  with the {@code \r\n} pair (two units of one terminator — the
     *  pair's interior is not a line boundary). */
    public static final Terminators JDK = ofPair('\r', '\n', '\n', '\r', 0x85, 0x2028, 0x2029);

    /** {@code \n} only, no pair — the JDK {@code UNIX_LINES} reading,
     *  which the RE2 lineage also answers in. */
    public static final Terminators NL_ONLY = of('\n');

    /** Upper bound on units — keeps the probe flat and the ASM emission
     *  a bounded compare chain; terminator sets are small by nature. */
    public static final int MAX_UNITS = 16;

    private static final int MAX_CP = 0x10FFFF;

    private final int[] units;

    private final int pairLo;

    private final int pairHi;

    private final int[] dotRanges;

    private Terminators(int[] units, int pairLo, int pairHi, int[] dotRanges) {
        this.units = units;
        this.pairLo = pairLo;
        this.pairHi = pairHi;
        this.dotRanges = dotRanges;
    }

    /**
     * A set of single-unit terminators, no pair. Units are given in any
     * order (sorted here); duplicates and out-of-range codepoints
     * reject — a malformed set is a construction bug, not a reading.
     */
    public static Terminators of(int... units) {
        return build(units, 0, 0);
    }

    /**
     * A set with one ordered pair: {@code lo} followed by {@code hi} is
     * a single two-unit terminator whose interior is not a line
     * boundary (the JDK's {@code \r\n}). Both pair halves must be among
     * {@code units}.
     */
    public static Terminators ofPair(int lo, int hi, int... units) {
        return build(units, lo, hi);
    }

    private static Terminators build(int[] given, int pairLo, int pairHi) {
        if (given == null || given.length == 0) {
            throw new IllegalArgumentException("terminator set is empty");
        }
        if (given.length > MAX_UNITS) {
            throw new IllegalArgumentException("terminator set exceeds " + MAX_UNITS + " units");
        }
        int[] units = given.clone();
        Arrays.sort(units);
        for (int i = 0; i < units.length; i++) {
            if (units[i] < 0 || units[i] > MAX_CP) {
                throw new IllegalArgumentException("terminator unit out of range: " + units[i]);
            }
            if (i > 0 && units[i] == units[i - 1]) {
                throw new IllegalArgumentException("duplicate terminator unit: " + units[i]);
            }
        }
        if ((pairLo == 0) != (pairHi == 0)) {
            throw new IllegalArgumentException("pair is both halves or neither: " + pairLo + ".." + pairHi);
        }
        if (pairLo != 0) {
            if (pairLo == pairHi) {
                throw new IllegalArgumentException("pair halves must differ: " + pairLo);
            }
            requireMember(units, pairLo);
            requireMember(units, pairHi);
        }
        return new Terminators(units, pairLo, pairHi, complement(units));
    }

    private static void requireMember(int[] units, int cp) {
        for (int u : units) {
            if (u == cp) {
                return;
            }
        }
        throw new IllegalArgumentException("pair half is not a set member: " + cp);
    }

    /**
     * The complement of the unit set over the codepoint space, as
     * ascending lo/hi ranges — the {@code .} class (both pair halves
     * are units, so the pair needs no case of its own).
     */
    private static int[] complement(int[] units) {
        int[] out = new int[(units.length + 1) * 2];
        int n = 0;
        int lo = 0;
        for (int u : units) {
            if (u > lo) {
                out[n++] = lo;
                out[n++] = u - 1;
            }
            lo = u + 1;
        }
        if (lo <= MAX_CP) {
            out[n++] = lo;
            out[n++] = MAX_CP;
        }
        return Arrays.copyOf(out, n);
    }

    /**
     * Membership in the terminator set (single units; a pair is two
     * units of one terminator, so pair halves answer true).
     */
    public boolean isUnit(int c) {
        for (int u : units) {
            if (u == c) {
                return true;
            }
        }
        return false;
    }

    /** The number of single units (bounded by {@value #MAX_UNITS}). */
    public int unitCount() {
        return units.length;
    }

    /** Is there a two-unit terminator pair? (See {@link #ofPair}.) */
    public boolean hasPair() {
        return pairLo != 0;
    }

    /** The pair's first unit, or {@code 0} when {@link #hasPair()} is false. */
    public int pairLo() {
        return pairLo;
    }

    /** The pair's second unit, or {@code 0} when {@link #hasPair()} is false. */
    public int pairHi() {
        return pairHi;
    }

    /** The sorted single units, ascending — a fresh copy (the hot path
     *  is {@link #isUnit}; this serves emission and the wire). */
    public int[] units() {
        return units.clone();
    }

    /**
     * The {@code .} class for this set: ascending lo/hi ranges — the
     * complement of the units over the codepoint space. A fresh copy
     * per call (consumed once per compile, by the parser).
     */
    public int[] dotRanges() {
        return dotRanges.clone();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Terminators)) {
            return false;
        }
        Terminators t = (Terminators) o;
        return pairLo == t.pairLo && pairHi == t.pairHi && Arrays.equals(units, t.units);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * Arrays.hashCode(units) + pairLo) + pairHi;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("Terminators[");
        for (int i = 0; i < units.length; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(String.format("U+%04X", units[i]));
        }
        if (pairLo != 0) {
            sb.append(String.format(" pair(U+%04X,U+%04X)", pairLo, pairHi));
        }
        return sb.append(']').toString();
    }
}
