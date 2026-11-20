package io.github.jemmix.tdfa.core.parser;

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
 * <li>the fold-universe seed (the UNICODE_CASE policy — the scoped state a
 *     parse's {@code (?i)} literals and class folds start from before the
 *     inline {@code (?u)}/{@code (?U)} letters upgrade it; the fold
 *     EXPANSION itself lives in the meaning-resolution pass);</li>
 * <li>the escape readings ({@link EscapeAlphabet} — the full
 *     java.util.regex alphabet, or the RE2 family's minimal one);</li>
 * <li>the inline-flag letter {@link FlagBindings} — which behavior
 *     each letter activates ({@code U} = ungreedy / scoped Unicode
 *     case are the two stock spellings; a custom alphabet is the
 *     custom-surface seam).</li>
 * </ul>
 * Every meaning-shaped axis — the {@code DOT}/line-predicate terminator
 * set (the dot is a symbolic node), the zero-width-iteration span protocol
 * (the family-6 marks), the {@code \s} table, the shorthand fold and the
 * shorthand/property tables — is applied by the meaning-resolution pass
 * ({@code core.semantic.Resolve}) from the compile's {@code Semantics}
 * directly; see {@code docs/engine-compat-surface.md}.
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
        new ParseOptions(true, FlagBindings.RE2, EscapeAlphabet.RE2_MINIMAL, false);

    private final boolean unicodeCase;

    private final FlagBindings flagBindings;

    private final EscapeAlphabet escapes;

    private final boolean canonEq;

    private ParseOptions(boolean unicodeCase, FlagBindings flagBindings, EscapeAlphabet escapes, boolean canonEq) {
        this.unicodeCase = unicodeCase;
        this.flagBindings = flagBindings;
        this.escapes = escapes;
        this.canonEq = canonEq;
    }

    /** Whether the parse applies the canonical-equivalence rewrite
     *  (the jur facade's {@code CANON_EQ} flag — java's pattern-side
     *  mechanism: every literal and class member expands to an
     *  alternation of its NFC and NFD spellings; docs/jur-api-parity.md). */
    public ParseOptions canonEq(boolean canonEq) {
        return canonEq == this.canonEq ? this : new ParseOptions(unicodeCase, flagBindings, escapes, canonEq);
    }

    /** @see #canonEq(boolean) */
    public boolean isCanonEq() {
        return canonEq;
    }

    /**
     * The all-defaults options: every stock component at its JDK value
     * and every policy unset — the projection target {@code
     * Tnfa.compile} applies a {@code Semantics} onto.
     */
    public static ParseOptions of() {
        return new ParseOptions(false, FlagBindings.JDK, EscapeAlphabet.JDK, false);
    }

    /**
     * The RE2-lineage preset — the full Unicode bare fold seed, the RE2
     * letter alphabet with ungreedy {@code (?U)}, and the minimal escape
     * alphabet. The pre-flip default the legacy parse entries answer in;
     * named here because it is selected, not because the parser branches
     * on it.
     */
    public static ParseOptions re2Lineage() {
        return RE2_LINEAGE;
    }

    /**
     * Select the fold-universe seed: {@code true} = the parse's scoped
     * fold state starts at the full Unicode simple-fold universe (JDK
     * {@code UNICODE_CASE}; the provider's own universe when it supplies
     * one); {@code false} = ASCII only until widened — the
     * java.util.regex bare-CI reading (the 26 letter pairs and nothing
     * else; {@code (?u)} widens it, JDK {@code UNICODE_CHARACTER_CLASS}
     * implying Unicode-aware CI — and the scoped {@code (?U)} does too,
     * per scope, since v2). The orbit EXPANSION happens in the
     * meaning-resolution pass, over the per-atom selection the parse
     * records.
     */
    public ParseOptions unicodeCase(boolean unicodeCase) {
        return new ParseOptions(unicodeCase, flagBindings, escapes, canonEq);
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
        return new ParseOptions(unicodeCase, flagBindings, escapes, canonEq);
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
        return new ParseOptions(unicodeCase, flagBindings, escapes, canonEq);
    }

    /** Does the scoped fold state start at the full Unicode universe (vs ASCII only)? */
    public boolean isUnicodeCase() {
        return unicodeCase;
    }

    /** The inline-flag letter bindings (which letter activates which behavior). */
    public FlagBindings flagBindings() {
        return flagBindings;
    }

    /** The escape readings (see {@link #escapes(EscapeAlphabet)}). */
    public EscapeAlphabet escapes() {
        return escapes;
    }
}
