package io.github.jemmix.tdfa.core.tnfa;

/**
 * The compile's semantic-mode selection: the seven JUR-compat axes of
 * {@code docs/jur-compat-default.md} ("the flags"), carried as one
 * immutable value instead of seven booleans threaded through every
 * pipeline entry. An axis SET selects the set-side (re2j-pinned)
 * behavior — today's only behavior on every axis; an axis UNSET selects
 * the JUR-parity side that the JUR-compat default flip will make the
 * shipped default. Names and semantics mirror the JDK flags where the
 * axis snapped onto one ({@code UNIX_LINES}, {@code UNICODE_CASE});
 * numerics are this class's own and do not mirror anything.
 *
 * <p>Like {@link Tnfa#multiValuedTags}, the selection rides the
 * {@link Tnfa} — one source, so parser pivots, the determinizer, the
 * materializer and the artifact all read it there — then freezes onto
 * the {@code Tdfa} ({@code semantics()}) and is copied into the
 * runner's final fields for the interpreter's pivot sites; the ASM tier
 * reads the same artifact getters at emit time. The axes are inert
 * until the pivots are parameterized to consult them: pre-flip every
 * compile runs {@link #RE2} regardless of the caller's opt-out bits,
 * and the flip commit is the one line in the facade that starts mapping
 * user bits onto this value.
 *
 * <p>Immutable; value equality (withers get compared in tests).
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
     * Every axis set: the pre-flip (re2j-pinned) behavior on all seven
     * axes — the engine's only behavior today, hence the effective
     * semantics of every compile until the flip.
     */
    public static final Semantics RE2 = new Semantics(ALL);

    private final int bits;

    private Semantics(int bits) {
        this.bits = bits;
    }

    /**
     * Every axis unset — the post-flip JUR-parity default. Build the
     * set side by chaining the axis withers.
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
