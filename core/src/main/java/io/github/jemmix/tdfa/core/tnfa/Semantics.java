package io.github.jemmix.tdfa.core.tnfa;

import io.github.jemmix.tdfa.core.dialect.ClassTable;
import io.github.jemmix.tdfa.core.dialect.EscapeAlphabet;
import io.github.jemmix.tdfa.core.dialect.FlagBindings;
import io.github.jemmix.tdfa.core.dialect.Terminators;

import java.io.Serializable;

/**
 * A compile's interpretation policy: the sides of every behavioral
 * axis. The engine accepts one syntax; the axes are the respects in
 * which that syntax admits different readings — carried as one
 * immutable value instead of one boolean per axis threaded through
 * every pipeline entry. An axis is one of two shapes:
 * <ul>
 * <li><b>Policy bits</b> — a protocol or discipline with exactly two
 *   readings ({@code UNICODE_CASE}, {@code CODEPOINT_BOUNDARIES},
 *   {@code EMPTY_LAST_LINE}, {@code END_OF_TEXT_ONLY},
 *   {@code EMPTY_ITERATION_SPANS}, {@code SHORTHAND_FOLD}), named for
 *   the JDK flag an axis snaps onto where one exists; a well-named
 *   boolean is the honest shape for each.</li>
 * <li><b>Components</b> — a set- or alphabet-shaped reading carried as
 *   its own value type: the terminator set
 *   ({@link Terminators}.{@link Terminators#JDK JDK} default), the
 *   ASCII {@code \s} table ({@link ClassTable}.{@link
 *   ClassTable#SPACE_JDK SPACE_JDK} default), the escape alphabet
 *   ({@link EscapeAlphabet}.{@link EscapeAlphabet#JDK JDK} default)
 *   and the inline-flag letter bindings ({@link FlagBindings}.{@link
 *   FlagBindings#JDK JDK} default — the alphabet a letter activates
 *   is data, the stock presets the two shipped spellings of
 *   {@code (?U)}).</li>
 * </ul>
 *
 * <p>Contract:
 * <ul>
 * <li><b>Compile-global, one source.</b> One value per compile: it
 *   rides the {@link Tnfa}, freezes onto the {@code Tdfa}
 *   ({@code semantics()}), and the runner copies it from the
 *   artifact. Membership takes two conditions. <b>Expressible</b>: an
 *   axis distinguishes readings of the one syntax (set/alphabet-shaped
 *   readings arrive as their value; policies as their bit).
 *   <b>Reach</b>: consults at more than one stage or at match time.
 *   Scoped parser state (inline {@code (?i)}/{@code (?m)}/{@code
 *   (?s)}) fails reach, consumed at parse; facade capabilities
 *   ({@code FIND_ONLY}) never enter core; engine regimes with no
 *   reading counterpart — {@code LONGEST_MATCH}, {@code
 *   MULTI_VALUED_TAGS} select an output protocol, not a reading of
 *   the syntax — fail however many stages consult them.</li>
 * <li><b>Consumed by projection.</b> Each axis belongs to the
 *   stage(s) that consume it: the fold universe, the letter
 *   alphabet, {@code DOT} set, the {@code \s} table, the escape
 *   readings and the family-6 shape marks (EMPTY_ITERATION_SPANS —
 *   the sub-family A dissolve pair and the sub-family B surface pair)
 *   belong to the parser; the final-iteration span protocol to the
 *   determinizer; the terminator set, anchor end-of-line rules and
 *   boundary discipline to the runner and the ASM emitter.</li>
 * <li><b>Presets are data.</b> The stock bundles that align with a
 *   named engine ({@code FlagBindings.RE2}, {@code
 *   ClassTable.SPACE_RE2}) are selectable values — nothing in the
 *   machinery branches on them. {@link #of()} is the all-defaults
 *   value (the java.util.regex-parity bundle); the withers compose
 *   any selection.</li>
 * </ul>
 *
 * <p>Immutable value class — field equality over the policy bits and
 * the four components; a wither returns the same instance when the
 * axis already carries that value. Serializable as its parts; the
 * <b>wire form</b> ({@link #bits()}/{@link #ofBits(int)}) is the
 * proxies' v1 encoding: the six policy bits, plus the four retired
 * v1 bits each spelled by a stock component (see {@link #bits()}).
 * An unknown bit fails loudly (a stream from a newer library must not
 * silently recompile in a lane the reader cannot name).
 */
public final class Semantics implements Serializable {

    private static final long serialVersionUID = 2L;

    // ---- policy bits (the live axes) ----

    private static final int UNICODE_CASE_BIT = 2;

    private static final int CODEPOINT_BOUNDARIES_BIT = 4;

    private static final int EMPTY_LAST_LINE_BIT = 8;

    private static final int END_OF_TEXT_ONLY_BIT = 16;

    private static final int EMPTY_ITERATION_SPANS_BIT = 32;

    private static final int SHORTHAND_FOLD_BIT = 256;

    private static final int POLICY_BITS = UNICODE_CASE_BIT | CODEPOINT_BOUNDARIES_BIT | EMPTY_LAST_LINE_BIT
        | END_OF_TEXT_ONLY_BIT | EMPTY_ITERATION_SPANS_BIT | SHORTHAND_FOLD_BIT;

    // ---- retired v1 bits (reserved; translated at the wire boundary) ----

    /** v1: {@code \n}-only terminators — now {@link #terminators(Terminators)}
     *  with {@link Terminators#NL_ONLY}. */
    static final int UNIX_LINES_BIT = 1;

    /** v1: {@code (?U)} = ungreedy — now {@link #flagBindings(FlagBindings)}
     *  with {@link FlagBindings#RE2}. */
    static final int UNGREEDY_U_BIT = 64;

    /** v1: the RE2 ASCII {@code \s} table — now
     *  {@link #spaceTable(ClassTable)} with {@link ClassTable#SPACE_RE2}. */
    static final int SPACE_RE2_BIT = 128;

    /** v1: the RE2 minimal escape alphabet — now
     *  {@link #escapes(EscapeAlphabet)} with {@link EscapeAlphabet#RE2_MINIMAL}. */
    static final int ESCAPES_RE2_BIT = 512;

    private static final int ALL_V1_BITS = POLICY_BITS | UNIX_LINES_BIT | UNGREEDY_U_BIT | SPACE_RE2_BIT
        | ESCAPES_RE2_BIT;

    private final int bits;

    private final Terminators terminators;

    private final ClassTable spaceTable;

    private final EscapeAlphabet escapes;

    private final FlagBindings flagBindings;

    private Semantics(int bits, Terminators terminators, ClassTable spaceTable, EscapeAlphabet escapes,
        FlagBindings flagBindings) {
        this.bits = bits;
        this.terminators = terminators;
        this.spaceTable = spaceTable;
        this.escapes = escapes;
        this.flagBindings = flagBindings;
    }

    /**
     * The all-defaults policy: no bit set, every stock component at
     * its JDK default ({@link Terminators#JDK}, {@link
     * ClassTable#SPACE_JDK}, {@link EscapeAlphabet#JDK}, {@link
     * FlagBindings#JDK}) — the java.util.regex-parity bundle. Compose
     * any other selection with the withers.
     */
    public static Semantics of() {
        return new Semantics(0, Terminators.JDK, ClassTable.SPACE_JDK, EscapeAlphabet.JDK, FlagBindings.JDK);
    }

    /**
     * The v1 wire read: the policy bits, plus the four retired bits
     * each translated to its stock component ({@code UNIX_LINES}
     * &rarr; {@link Terminators#NL_ONLY}, {@code UNGREEDY_U} &rarr;
     * {@link FlagBindings#RE2}, {@code SPACE_RE2} &rarr; {@link
     * ClassTable#SPACE_RE2}, {@code ESCAPES_RE2} &rarr; {@link
     * EscapeAlphabet#RE2_MINIMAL}). An unknown bit fails loudly — a
     * stream from a newer library must not silently recompile in a
     * lane the reader cannot name.
     */
    public static Semantics ofBits(int bits) {
        if ((bits & ~ALL_V1_BITS) != 0) {
            throw new IllegalArgumentException("unknown semantics axis bits: 0x" + Integer.toHexString(bits));
        }
        Semantics s = of();
        if ((bits & UNICODE_CASE_BIT) != 0) {
            s = s.unicodeCase();
        }
        if ((bits & CODEPOINT_BOUNDARIES_BIT) != 0) {
            s = s.codepointBoundaries();
        }
        if ((bits & EMPTY_LAST_LINE_BIT) != 0) {
            s = s.emptyLastLine();
        }
        if ((bits & END_OF_TEXT_ONLY_BIT) != 0) {
            s = s.endOfTextOnly();
        }
        if ((bits & EMPTY_ITERATION_SPANS_BIT) != 0) {
            s = s.emptyIterationSpans();
        }
        if ((bits & SHORTHAND_FOLD_BIT) != 0) {
            s = s.shorthandFold();
        }
        if ((bits & UNIX_LINES_BIT) != 0) {
            s = s.terminators(Terminators.NL_ONLY);
        }
        if ((bits & UNGREEDY_U_BIT) != 0) {
            s = s.flagBindings(FlagBindings.RE2);
        }
        if ((bits & SPACE_RE2_BIT) != 0) {
            s = s.spaceTable(ClassTable.SPACE_RE2);
        }
        if ((bits & ESCAPES_RE2_BIT) != 0) {
            s = s.escapes(EscapeAlphabet.RE2_MINIMAL);
        }
        return s;
    }

    /**
     * The v1 wire write: the six policy bits, plus the retired bits
     * spelled by any stock component (a custom component has no v1
     * bit — the component payloads are the proxies' v2 form).
     */
    public int bits() {
        int out = bits;
        if (terminators.equals(Terminators.NL_ONLY)) {
            out |= UNIX_LINES_BIT;
        }
        if (flagBindings.equals(FlagBindings.RE2)) {
            out |= UNGREEDY_U_BIT;
        }
        if (spaceTable.equals(ClassTable.SPACE_RE2)) {
            out |= SPACE_RE2_BIT;
        }
        if (escapes == EscapeAlphabet.RE2_MINIMAL) {
            out |= ESCAPES_RE2_BIT;
        }
        return out;
    }

    // ---- policy-bit withers (the JDK-flag names they snap onto) ----

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
     * Axis: the maximal-iteration span protocol; unset is
     * java.util.regex's, which is two sub-families of opposite polarity
     * — a greedy min-0 quantifier directly on a capture whose body is
     * all-zero-width leaves the group non-participating
     * ({@code (\z)*} on {@code ""} &rarr; g1 {@code -1}, the
     * {@code GroupCurly} rollback), while a greedy quantifier on an
     * empty-capable non-deterministic body reports the final
     * zero-width iteration's span ({@code (a*)*} on {@code "aa"} &rarr;
     * g1 {@code 2..2}). Set keeps the maximal-iteration protocol
     * throughout: a sole zero-width iteration reports
     * ({@code (\z)*} &rarr; g1 {@code 0..0}) and the last consuming
     * iteration wins ({@code (a*)*} &rarr; g1 {@code 0..2}).
     */
    public Semantics emptyIterationSpans() {
        return with(EMPTY_ITERATION_SPANS_BIT);
    }

    /**
     * Axis: the predefined shorthands case-fold under
     * {@code CASE_INSENSITIVE} — {@code (?i)\w} matches {@code ſ} via
     * the s-orbit of {@code a-z}; unset never folds a shorthand —
     * {@code java.util.regex} keeps {@code \w}/{@code \W} (and the
     * digit/space twins, which have no fold orbits in either reading)
     * at their plain tables under every fold universe
     * ({@code CASE_INSENSITIVE}, {@code UNICODE_CASE},
     * {@code UNICODE_CHARACTER_CLASS}, scoped {@code (?u:...)}/{@code (?U:...)}
     * included — live-JDK verified).
     */
    public Semantics shorthandFold() {
        return with(SHORTHAND_FOLD_BIT);
    }

    // ---- component withers (the set/alphabet axes) ----

    /**
     * Axis: the {@code .}/{@code ^}/{@code $} line-terminator set
     * (default {@link Terminators#JDK}, the full java.util.regex set
     * with the {@code \r\n} pair).
     */
    public Semantics terminators(Terminators terminators) {
        if (terminators == null) {
            throw new NullPointerException("terminators");
        }
        return this.terminators.equals(terminators) ? this
            : new Semantics(bits, terminators, spaceTable, escapes, flagBindings);
    }

    /**
     * The {@code UNIX_LINES} spelling of the terminator axis — sugar
     * for {@link #terminators(Terminators)} with {@link
     * Terminators#NL_ONLY} (the JDK flag both lineages answer to; the
     * facade composes this bit onto the lane).
     */
    public Semantics unixLines() {
        return terminators(Terminators.NL_ONLY);
    }

    /**
     * Axis: the ASCII {@code \s}/{@code \S} table (default {@link
     * ClassTable#SPACE_JDK}, U+000B IS whitespace; {@link
     * ClassTable#SPACE_RE2} excludes it). The Unicode ({@code (?u)})
     * {@code \s} table is lineage-shared ({@code IsWhite_Space}
     * includes U+0009-U+000D) and is unaffected.
     */
    public Semantics spaceTable(ClassTable spaceTable) {
        if (spaceTable == null) {
            throw new NullPointerException("spaceTable");
        }
        return this.spaceTable.equals(spaceTable) ? this : new Semantics(bits, terminators, spaceTable, escapes,
            flagBindings);
    }

    /**
     * Axis: the escape alphabet — which backslash escape readings the
     * compile admits (default {@link EscapeAlphabet#JDK}, the full
     * java.util.regex alphabet; {@link EscapeAlphabet#RE2_MINIMAL}
     * rejects the java-only escapes and reads {@code \v} as U+000B).
     */
    public Semantics escapes(EscapeAlphabet escapes) {
        if (escapes == null) {
            throw new NullPointerException("escapes");
        }
        return this.escapes == escapes ? this : new Semantics(bits, terminators, spaceTable, escapes, flagBindings);
    }

    /**
     * Axis: the inline-flag letter bindings — which behavior each
     * letter activates (default {@link FlagBindings#JDK}, the
     * java.util.regex alphabet with {@code (?U)} = scoped Unicode
     * case; {@link FlagBindings#RE2} spells {@code (?U)} = ungreedy
     * and binds no {@code x} letter). A custom alphabet is data: the
     * letters it does not bind reject at the parse.
     */
    public Semantics flagBindings(FlagBindings flagBindings) {
        if (flagBindings == null) {
            throw new NullPointerException("flagBindings");
        }
        return this.flagBindings.equals(flagBindings) ? this
            : new Semantics(bits, terminators, spaceTable, escapes, flagBindings);
    }

    private Semantics with(int bit) {
        return (bits & bit) != 0 ? this : new Semantics(bits | bit, terminators, spaceTable, escapes, flagBindings);
    }

    // ---- queries ----

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

    /** Axis set? (Shorthands fold under {@code CASE_INSENSITIVE}.) */
    public boolean isShorthandFold() {
        return (bits & SHORTHAND_FOLD_BIT) != 0;
    }

    /** The {@code \n}-only terminator set — the UNIX_LINES spelling of
     *  the terminator axis? */
    public boolean isUnixLines() {
        return terminators.equals(Terminators.NL_ONLY);
    }

    /** The terminator set (see {@link #terminators(Terminators)}). */
    public Terminators terminators() {
        return terminators;
    }

    /** The ASCII {@code \s} table (see {@link #spaceTable(ClassTable)}). */
    public ClassTable spaceTable() {
        return spaceTable;
    }

    /** The escape alphabet (see {@link #escapes(EscapeAlphabet)}). */
    public EscapeAlphabet escapes() {
        return escapes;
    }

    /** The inline-flag letter bindings (see {@link #flagBindings(FlagBindings)}). */
    public FlagBindings flagBindings() {
        return flagBindings;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Semantics)) {
            return false;
        }
        Semantics s = (Semantics) o;
        return bits == s.bits && terminators.equals(s.terminators) && spaceTable.equals(s.spaceTable)
            && escapes == s.escapes && flagBindings.equals(s.flagBindings);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * (31 * (31 * bits + terminators.hashCode()) + spaceTable.hashCode()) + escapes.hashCode())
            + flagBindings.hashCode();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("Semantics[");
        String[] names = {"UNICODE_CASE", "CODEPOINT_BOUNDARIES", "EMPTY_LAST_LINE", "END_OF_TEXT_ONLY",
            "EMPTY_ITERATION_SPANS", "SHORTHAND_FOLD"};
        int[] axisBits = {UNICODE_CASE_BIT, CODEPOINT_BOUNDARIES_BIT, EMPTY_LAST_LINE_BIT, END_OF_TEXT_ONLY_BIT,
            EMPTY_ITERATION_SPANS_BIT, SHORTHAND_FOLD_BIT};
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
        String[] comps = {terminators.toString(), spaceTable.toString(), escapes.toString(),
            flagBindings.toString()};
        for (String comp : comps) {
            if (!first) {
                sb.append('|');
            }
            sb.append(comp);
            first = false;
        }
        return sb.append(']').toString();
    }
}
