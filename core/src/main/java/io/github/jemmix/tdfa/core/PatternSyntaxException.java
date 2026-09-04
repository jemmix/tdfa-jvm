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

    public String getDescription() { return description; }
    public String getPattern() { return pattern; }
    public int getIndex() { return -1; }
}
