package io.github.jemmix.tdfa.core.parser;

/**
 * The parser-side pivot knobs — the projection of a compile's
 * interpretation policy onto the parser's axes. The projection itself
 * lives with the policy carrier ({@code Tnfa.compile} maps its
 * {@code Semantics} onto this type), keeping the parser package below
 * {@code tnfa} in the core layer DAG while the parser's behavior stays
 * selectable per compile.
 *
 * <p>Immutable value class. Today: the {@code DOT} terminator set (the
 * UNIX_LINES axis — {@code \n} only, or the full java.util.regex
 * terminator set). The fold universe (UNICODE_CASE) and the {@code (?U)}
 * meaning (UNGREEDY_U) are parser-side axes of the same design and join
 * here at their own work-breakdown items.
 */
public final class ParseOptions {

    private static final ParseOptions RE2_LANE = new ParseOptions(true);

    private final boolean dotNlOnly;

    private ParseOptions(boolean dotNlOnly) {
        this.dotNlOnly = dotNlOnly;
    }

    /**
     * The pre-flip default: {@code .} skips {@code \n} only (the
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
     */
    public static ParseOptions dotNlOnly(boolean dotNlOnly) {
        return new ParseOptions(dotNlOnly);
    }

    /** Does the dot skip {@code \n} only (vs the full terminator set)? */
    public boolean isDotNlOnly() {
        return dotNlOnly;
    }
}
