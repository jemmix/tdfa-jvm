package io.github.jemmix.tdfa.core.parser;

/**
 * The parser-side pivot knobs — the projection of a compile's
 * interpretation policy onto the parser's axes. The projection itself
 * lives with the policy carrier ({@code Tnfa.compile} maps its
 * {@code Semantics} onto this type), keeping the parser package below
 * {@code tnfa} in the core layer DAG while the parser's behavior stays
 * selectable per compile.
 *
 * <p>Immutable value class. The three axes (each names the behavior
 * {@code true} selects):
 * <ul>
 * <li>the {@code DOT} terminator set (the UNIX_LINES axis — {@code \n}
 *     only, or the full java.util.regex terminator set);
 * <li>the fold universe (the UNICODE_CASE axis — full Unicode simple
 *     folding, or ASCII-only, the java.util.regex bare-CI reading);
 * <li>the {@code (?U)} meaning (the UNGREEDY_U axis — PCRE/RE2 ungreedy,
 *     or scoped Unicode-case, the java.util.regex reading).
 * </ul>
 */
public final class ParseOptions {

    private static final ParseOptions RE2_LANE = new ParseOptions(true, true, true);

    private final boolean dotNlOnly;

    private final boolean unicodeCase;

    private final boolean ungreedyU;

    private ParseOptions(boolean dotNlOnly, boolean unicodeCase, boolean ungreedyU) {
        this.dotNlOnly = dotNlOnly;
        this.unicodeCase = unicodeCase;
        this.ungreedyU = ungreedyU;
    }

    /**
     * The pre-flip default: {@code .} skips {@code \n} only, CI folds the
     * full Unicode universe and {@code (?U)} means ungreedy (the
     * RE2-lineage reading — every legacy parse entry uses this).
     */
    public static ParseOptions re2Lane() {
        return RE2_LANE;
    }

    /**
     * Select the {@code DOT} set: {@code dotNlOnly} = the dot skips
     * {@code \n} only (JDK {@code UNIX_LINES} and the RE2 lineage agree);
     * {@code false} = the full java.util.regex terminator set
     * ({@code \n}, {@code \r}, {@code \r\n}, U+0085, U+2028, U+2029).
     * The other axes default to the RE2-side reading.
     */
    public static ParseOptions dotNlOnly(boolean dotNlOnly) {
        return new ParseOptions(dotNlOnly, true, true);
    }

    /**
     * Select the fold universe: {@code true} = {@code CASE_INSENSITIVE}
     * folds the full Unicode simple-fold universe (JDK
     * {@code UNICODE_CASE}; the provider's own universe when it supplies
     * one); {@code false} = ASCII only until widened — the
     * java.util.regex bare-CI reading (the 26 letter pairs and nothing
     * else; {@code (?u)} widens it, JDK {@code UNICODE_CHARACTER_CLASS}
     * implying Unicode-aware CI).
     */
    public ParseOptions unicodeCase(boolean unicodeCase) {
        return new ParseOptions(dotNlOnly, unicodeCase, ungreedyU);
    }

    /**
     * Select the {@code (?U)} meaning: {@code true} = ungreedy
     * (quantifiers default to lazy — the PCRE/RE2 reading); {@code false}
     * = scoped {@code UNICODE_CASE} (the java.util.regex reading; v1
     * accepts the positive top-level flag-only spelling alone — see
     * {@code Parser}).
     */
    public ParseOptions ungreedyU(boolean ungreedyU) {
        return new ParseOptions(dotNlOnly, unicodeCase, ungreedyU);
    }

    /** Does the dot skip {@code \n} only (vs the full terminator set)? */
    public boolean isDotNlOnly() {
        return dotNlOnly;
    }

    /** Folds CI the full Unicode universe (vs ASCII only)? */
    public boolean isUnicodeCase() {
        return unicodeCase;
    }

    /** Does {@code (?U)} mean ungreedy (vs scoped Unicode-case)? */
    public boolean isUngreedyU() {
        return ungreedyU;
    }
}
