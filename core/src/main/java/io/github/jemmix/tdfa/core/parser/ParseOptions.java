package io.github.jemmix.tdfa.core.parser;

import io.github.jemmix.tdfa.core.dialect.ClassTable;
import io.github.jemmix.tdfa.core.dialect.EscapeAlphabet;
import io.github.jemmix.tdfa.core.dialect.FlagBindings;

/**
 * The parser-side knobs — the projection of a compile's interpretation
 * policy onto the parser's <b>grammar-shaped</b> axes only. The projection
 * itself lives with the policy carrier ({@code Tnfa.compile} maps its
 * {@code Semantics} onto this type), keeping the parser package below
 * {@code tnfa} in the core layer DAG while the parser's behavior stays
 * selectable per compile.
 *
 * <p>Immutable value class. The axes:
 * <ul>
 * <li>the fold universe (the UNICODE_CASE policy — full Unicode simple
 *     folding, or ASCII-only, the java.util.regex bare-CI reading);</li>
 * <li>the ASCII {@code \s} table ({@link ClassTable} — with or without
 *     U+000B, or a custom table);</li>
 * <li>the shorthand fold (the SHORTHAND_FOLD policy — fold the
 *     {@code \w}/{@code \W} class tables under CI, or never);</li>
 * <li>the escape readings ({@link EscapeAlphabet} — the full
 *     java.util.regex alphabet, or the RE2 family's minimal one);</li>
 * <li>the inline-flag letter {@link FlagBindings} — which behavior
 *     each letter activates ({@code U} = ungreedy / scoped Unicode
 *     case are the two stock spellings; a custom alphabet is the
 *     custom-surface seam).</li>
 * </ul>
 * The meaning-shaped axes the parser once mirrored — the
 * {@code DOT}/line-predicate terminator set (the dot is a symbolic
 * {@code Ast.Dot} node now) and the zero-width-iteration span protocol
 * (the family-6 marks are placed downstream) — are applied by the
 * meaning-resolution pass ({@code core.semantic.Resolve}) from the
 * compile's {@code Semantics} directly; see
 * {@code docs/engine-compat-surface.md}.
 *
 * <p>There is no lane sugar here: a bundle is named where it is selected
 * ({@link #re2Lineage()} is the one stock preset — the pre-flip default
 * the legacy parse entries answer in), and every axis is set
 * individually.
 */
public final class ParseOptions {

    /** The RE2-lineage preset: every grammar axis at its RE2-side value
     *  (the pre-flip default the legacy parse entries answer in). */
    private static final ParseOptions RE2_LINEAGE =
        new ParseOptions(true, FlagBindings.RE2, ClassTable.SPACE_RE2, true, EscapeAlphabet.RE2_MINIMAL);

    private final boolean unicodeCase;

    private final FlagBindings flagBindings;

    private final ClassTable spaceTable;

    private final boolean shorthandFold;

    private final EscapeAlphabet escapes;

    private ParseOptions(boolean unicodeCase, FlagBindings flagBindings, ClassTable spaceTable, boolean shorthandFold,
        EscapeAlphabet escapes) {
        this.unicodeCase = unicodeCase;
        this.flagBindings = flagBindings;
        this.spaceTable = spaceTable;
        this.shorthandFold = shorthandFold;
        this.escapes = escapes;
    }

    /**
     * The all-defaults options: every stock component at its JDK value
     * and every policy unset — the projection target {@code
     * Tnfa.compile} applies a {@code Semantics} onto.
     */
    public static ParseOptions of() {
        return new ParseOptions(false, FlagBindings.JDK, ClassTable.SPACE_JDK, false, EscapeAlphabet.JDK);
    }

    /**
     * The RE2-lineage preset — the full Unicode bare fold, the RE2
     * letter alphabet with ungreedy {@code (?U)}, the RE2 {@code \s}
     * table without U+000B, shorthand folding and the minimal escape
     * alphabet. The pre-flip default the legacy parse entries answer
     * in; named here because it is selected, not because the parser
     * branches on it.
     */
    public static ParseOptions re2Lineage() {
        return RE2_LINEAGE;
    }

    /**
     * Select the fold universe: {@code true} = {@code CASE_INSENSITIVE}
     * folds the full Unicode simple-fold universe (JDK
     * {@code UNICODE_CASE}; the provider's own universe when it supplies
     * one); {@code false} = ASCII only until widened — the
     * java.util.regex bare-CI reading (the 26 letter pairs and nothing
     * else; {@code (?u)} widens it, JDK {@code UNICODE_CHARACTER_CLASS}
     * implying Unicode-aware CI — and the scoped {@code (?U)} does too,
     * per scope, since v2).
     */
    public ParseOptions unicodeCase(boolean unicodeCase) {
        return new ParseOptions(unicodeCase, flagBindings, spaceTable, shorthandFold, escapes);
    }

    /**
     * Replace the inline-flag letter bindings outright — the
     * custom-surface seam. The stock alphabets are {@code U} = ungreedy
     * ({@link FlagBindings#RE2}) and {@code U} = scoped Unicode case
     * ({@link FlagBindings#JDK}); a custom surface binds any subset or
     * renaming of the closed {@code FlagAction} vocabulary, and every
     * letter it does not bind rejects at the parse.
     */
    public ParseOptions flagBindings(FlagBindings flagBindings) {
        return new ParseOptions(unicodeCase, flagBindings, spaceTable, shorthandFold, escapes);
    }

    /**
     * Select the ASCII {@code \s}/{@code \S} table. The stock values
     * are {@link ClassTable#SPACE_JDK} ({@code [ \t\n\x0B\f\r]} —
     * U+000B IS whitespace) and {@link ClassTable#SPACE_RE2}
     * ({@code [\t\n\f\r ]} — U+000B is NOT whitespace).
     */
    public ParseOptions spaceTable(ClassTable spaceTable) {
        return new ParseOptions(unicodeCase, flagBindings, spaceTable, shorthandFold, escapes);
    }

    /**
     * Select the shorthand fold: {@code true} = the predefined
     * shorthands ({@code \w \W \d \D \s \S} and their in-class twins)
     * case-fold under {@code CASE_INSENSITIVE}; {@code false} = never
     * fold a shorthand ({@code java.util.regex}: the predefined
     * classes keep their plain tables under every fold universe — the
     * scoped {@code (?U:...)} shapes included).
     */
    public ParseOptions shorthandFold(boolean shorthandFold) {
        return new ParseOptions(unicodeCase, flagBindings, spaceTable, shorthandFold, escapes);
    }

    /**
     * Select the escape readings: {@link EscapeAlphabet#JDK} spells
     * the full java.util.regex alphabet ({@code \v}/{@code \V}/{@code
     * \h}/{@code \H} class tables, {@code \e}/{@code \R}/{@code
     * \cX}/{@code \N{...}}/{@code \Z}); {@link
     * EscapeAlphabet#RE2_MINIMAL} rejects the java-only escapes at the
     * parse and reads {@code \v} as the single unit U+000B.
     */
    public ParseOptions escapes(EscapeAlphabet escapes) {
        return new ParseOptions(unicodeCase, flagBindings, spaceTable, shorthandFold, escapes);
    }

    /** Does CI fold the full Unicode universe (vs ASCII only)? */
    public boolean isUnicodeCase() {
        return unicodeCase;
    }

    /** The inline-flag letter bindings (which letter activates which behavior). */
    public FlagBindings flagBindings() {
        return flagBindings;
    }

    /** The ASCII {@code \s} table (see {@link #spaceTable(ClassTable)}). */
    public ClassTable spaceTable() {
        return spaceTable;
    }

    /** Do the shorthands fold under CI? */
    public boolean isShorthandFold() {
        return shorthandFold;
    }

    /** The escape readings (see {@link #escapes(EscapeAlphabet)}). */
    public EscapeAlphabet escapes() {
        return escapes;
    }
}
