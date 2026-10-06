package io.github.jemmix.tdfa.core.tnfa;

/**
 * A compile's interpretation policy: one side of each behavioral
 * axis. The engine accepts one syntax; the axes are the respects in
 * which that syntax admits two readings — the RE2 lineage this
 * library descends from, and java.util.regex parity — carried as one
 * immutable value instead of one boolean per axis threaded through
 * every pipeline entry. Names mirror the JDK flag an axis snaps onto
 * where one exists ({@code UNIX_LINES}, {@code UNICODE_CASE}); bit
 * values are this class's own.
 *
 * <p>Contract:
 * <ul>
 * <li><b>Compile-global, one source.</b> One value per compile: it
 *   rides the {@link Tnfa}, freezes onto the {@code Tdfa}
 *   ({@code semantics()}), and the runner copies it from the
 *   artifact. Settings that are scoped parser state (inline
 *   {@code (?i)}/{@code (?m)}/{@code (?s)}), facade capabilities
 *   ({@code FIND_ONLY}), or single-stage protocol knobs
 *   ({@code LONGEST_MATCH}, {@code MULTI_VALUED_TAGS}) are not axes
 *   and never ride here — an axis has consults at more than one
 *   stage or at match time.
 * <li><b>Consumed by projection.</b> Each axis belongs to the
 *   stage(s) that consume it: the fold universe, {@code (?U)}
 *   meaning and {@code DOT} set belong to the parser; the
 *   final-iteration span protocol to the determinizer; the
 *   terminator set, anchor end-of-line rules and boundary
 *   discipline to the runner and the ASM emitter.
 * <li><b>Polarity.</b> An axis set selects the RE2-lineage side;
 *   unset selects the java.util.regex-parity side. {@link #RE2} is
 *   every axis set; {@link #of()} every axis unset.
 * </ul>
 *
 * <p>Immutable value class — field equality; a wither returns the
 * same instance when its axis is already set. The seven axes below
 * are the JUR-compat design's
 * ({@code docs/jur-compat-default.md}); new axes join by the
 * membership rule above, not by that document.
 */
public final class Semantics {

    private static final int UNIX_LINES_BIT = 1;

    private static final int UNICODE_CASE_BIT = 2;

    private static final int CODEPOINT_BOUNDARIES_BIT = 4;

    private static final int EMPTY_LAST_LINE_BIT = 8;

    private static final int END_OF_TEXT_ONLY_BIT = 16;

    private static final int EMPTY_ITERATION_SPANS_BIT = 32;

    private static final int UNGREEDY_U_BIT = 64;

    private static final int ALL = 127;

    /**
     * Every axis set: the RE2-lineage side throughout — this
     * library's historical behavior (re2j parity).
     */
    public static final Semantics RE2 = new Semantics(ALL);

    private final int bits;

    private Semantics(int bits) {
        this.bits = bits;
    }

    /**
     * Every axis unset — the java.util.regex-parity side throughout.
     * Build the RE2 side by chaining the axis withers.
     */
    public static Semantics of() {
        return new Semantics(0);
    }

    /**
     * Axis: {@code .}/{@code ^}/{@code $} recognize only {@code \n} as a
     * line terminator (JDK {@code UNIX_LINES}); unset is the full
     * java.util.regex terminator set.
     */
    public Semantics unixLines() {
        return with(UNIX_LINES_BIT);
    }

    /**
     * Axis: {@code CASE_INSENSITIVE} folds the full Unicode simple-fold
     * universe (JDK {@code UNICODE_CASE}); unset folds ASCII only.
     */
    public Semantics unicodeCase() {
        return with(UNICODE_CASE_BIT);
    }

    /**
     * Axis: codepoint discipline — scans skip surrogate-pair interiors,
     * boundaries fall on codepoint edges; unset is UTF-16 unit semantics.
     */
    public Semantics codepointBoundaries() {
        return with(CODEPOINT_BOUNDARIES_BIT);
    }

    /**
     * Axis: a multiline {@code ^} may match the empty last line after a
     * trailing terminator; unset never matches there (java.util.regex).
     */
    public Semantics emptyLastLine() {
        return with(EMPTY_LAST_LINE_BIT);
    }

    /**
     * Axis: {@code $} matches only at end of input ({@code \z}); unset
     * may also match before a final terminator (java.util.regex).
     */
    public Semantics endOfTextOnly() {
        return with(END_OF_TEXT_ONLY_BIT);
    }

    /**
     * Axis: a zero-width final loop iteration reports its group's span
     * ({@code (\z)*} on {@code ""} &rarr; g1 {@code 0..0}); unset leaves
     * the group non-participating (java.util.regex &rarr; g1 {@code -1}).
     */
    public Semantics emptyIterationSpans() {
        return with(EMPTY_ITERATION_SPANS_BIT);
    }

    /**
     * Axis: {@code (?U)} means ungreedy (PCRE/RE2, re2j today); unset
     * means scoped {@code UNICODE_CASE} (java.util.regex).
     */
    public Semantics ungreedyU() {
        return with(UNGREEDY_U_BIT);
    }

    private Semantics with(int bit) {
        return (bits & bit) != 0 ? this : new Semantics(bits | bit);
    }

    /** Axis set? ({@code .}/{@code ^}/{@code $} terminators: {@code \n} only.) */
    public boolean isUnixLines() {
        return (bits & UNIX_LINES_BIT) != 0;
    }

    /** Axis set? (Case fold universe: full Unicode simple fold.) */
    public boolean isUnicodeCase() {
        return (bits & UNICODE_CASE_BIT) != 0;
    }

    /** Axis set? (Surrogate pairs: codepoint discipline, pair interiors skipped.) */
    public boolean isCodepointBoundaries() {
        return (bits & CODEPOINT_BOUNDARIES_BIT) != 0;
    }

    /** Axis set? (Multiline {@code ^} may match the empty last line.) */
    public boolean isEmptyLastLine() {
        return (bits & EMPTY_LAST_LINE_BIT) != 0;
    }

    /** Axis set? ({@code $} matches only at end of input, exactly {@code \z}.) */
    public boolean isEndOfTextOnly() {
        return (bits & END_OF_TEXT_ONLY_BIT) != 0;
    }

    /** Axis set? (Zero-width final loop iterations report their group spans.) */
    public boolean isEmptyIterationSpans() {
        return (bits & EMPTY_ITERATION_SPANS_BIT) != 0;
    }

    /** Axis set? ({@code (?U)} = ungreedy, the PCRE/RE2 meaning.) */
    public boolean isUngreedyU() {
        return (bits & UNGREEDY_U_BIT) != 0;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Semantics)) {
            return false;
        }
        return bits == ((Semantics) o).bits;
    }

    @Override
    public int hashCode() {
        return 31 * bits;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("Semantics[");
        String[] names = {"UNIX_LINES", "UNICODE_CASE", "CODEPOINT_BOUNDARIES", "EMPTY_LAST_LINE", "END_OF_TEXT_ONLY",
            "EMPTY_ITERATION_SPANS", "UNGREEDY_U"};
        int[] axisBits = {UNIX_LINES_BIT, UNICODE_CASE_BIT, CODEPOINT_BOUNDARIES_BIT, EMPTY_LAST_LINE_BIT,
            END_OF_TEXT_ONLY_BIT, EMPTY_ITERATION_SPANS_BIT, UNGREEDY_U_BIT};
        boolean first = true;
        for (int i = 0; i < names.length; i++) {
            if ((bits & axisBits[i]) != 0) {
                if (!first) {
                    sb.append('|');
                }
                sb.append(names[i]);
                first = false;
            }
        }
        return sb.append(']').toString();
    }
}
