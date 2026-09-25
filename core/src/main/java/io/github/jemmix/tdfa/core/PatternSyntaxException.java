package io.github.jemmix.tdfa.core;

/**
 * Thrown when a regular-expression pattern is malformed.
 *
 * <p>Message format matches re2j's {@code PatternSyntaxException}
 * ({@code error parsing regexp: <description>: `<pattern>`}) so parity test
 * suites (and user code matching on messages) behave identically.
 *
 * <p>Like re2j's, extends {@link RuntimeException} (matching
 * java.util.regex's checked hierarchy is intentionally avoided so the type
 * is not accidentally caught by code expecting the JDK flavour).
 */
public class PatternSyntaxException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final String description;
    private final String pattern;

    public PatternSyntaxException(String description, String pattern) {
        super("error parsing regexp: " + description + ": `" + pattern + "`");
        this.description = description;
        this.pattern = pattern;
    }

    public PatternSyntaxException(String description) {
        super("error parsing regexp: " + description);
        this.description = description;
        this.pattern = "";
    }

    public String getDescription() {
        return description;
    }

    public String getPattern() {
        return pattern;
    }

    public int getIndex() {
        return -1;
    }

    /**
     * Present a compile-pipeline {@link RuntimeException} as a
     * {@link PatternSyntaxException}: syntax errors are thrown as
     * {@code PatternSyntaxException} where they are detected (the parser)
     * and pass through unwrapped; budget rejections ("pattern too
     * large" ...) and anything else keep their message (or report as an
     * internal error), with the original chained as the cause.
     */
    public static PatternSyntaxException translate(RuntimeException e, String pattern) {
        if (e instanceof PatternSyntaxException) {
            return (PatternSyntaxException) e;
        }
        String msg = e.getMessage();
        PatternSyntaxException pse = new PatternSyntaxException(msg != null ? msg : "internal error", pattern);
        pse.initCause(e);
        return pse;
    }
}
