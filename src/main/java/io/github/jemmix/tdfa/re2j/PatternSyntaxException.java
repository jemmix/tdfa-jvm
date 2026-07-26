package io.github.jemmix.tdfa.re2j;

/**
 * Drop-in replacement for {@code com.google.re2j.PatternSyntaxException}.
 *
 * Mirrors re2j's public API surface so that code written against re2j can be
 * migrated by changing only the import package (com.google.re2j → io.github.jemmix.tdfa.re2j).
 *
 * Like re2j's, extends {@link RuntimeException} (matching java.util.regex's
 * hierarchy is intentionally avoided so the type is not accidentally caught
 * by code expecting the JDK flavour).
 */
public class PatternSyntaxException extends RuntimeException {
    private final String description;
    private final String pattern;

    public PatternSyntaxException(String description, String pattern) {
        super("error parsing regexp: " + description + ": `" + pattern + "`");
        this.description = description;
        this.pattern = pattern;
    }

    public String getDescription() { return description; }
    public String getPattern() { return pattern; }
    public int getIndex() { return -1; }
}

