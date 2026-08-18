package io.github.jemmix.tdfa.unicode;

/**
 * Pluggable source of Unicode property/script tables for {@code \p{...}} and
 * {@code \P{...}} regex syntax. Implementations return flattened {@code int[]}
 * ranges (lo0, hi0, lo1, hi1, ...; hi inclusive) for a given property name,
 * or {@code null} if the name is unknown.
 *
 * <h2>Supported names</h2>
 * re2j (and our compat target) accepts:
 * <ul>
 *   <li>{@code "Any"} — all codepoints (0..U+10FFFF)</li>
 *   <li>General categories: two-letter ({@code Lu}, {@code Nd}, {@code Ps}, ...)
 *       and one-letter containers ({@code L}, {@code N}, {@code M}, {@code P},
 *       {@code S}, {@code C}, {@code Z})</li>
 *   <li>Scripts: {@code Latin}, {@code Greek}, {@code Han}, {@code Old_South_Arabian}, ...</li>
 * </ul>
 *
 * <h2>Selection</h2>
 * Resolved at first use via {@link UnicodeProviders#get()} based on the
 * system property {@value UnicodeProviders#PROPERTY_NAME}. Built-in values:
 * {@code "jdk"} (default; backed by {@link java.lang.Character}) and
 * {@code "no-unicode"} (disables {@code \p{}} entirely). Any other value is
 * treated as a fully-qualified class name implementing this interface.
 *
 * <h2>Thread-safety</h2>
 * Implementations must be safe for concurrent use after initialisation; the
 * framework caches a single instance once resolved.
 */
public interface UnicodeDataProvider {
    /**
     * Flattened lo/hi ranges for the named property, or {@code null} if the
     * name is unknown. The name is matched as-is (case-sensitive, no
     * normalisation) to mirror re2j semantics — e.g. {@code "Greek"} but not
     * {@code "greek"} or {@code "grek"}.
     */
    int[] tableFor(String name);

    /**
     * Case-fold counterpart ranges for the named property — codepoints that
     * fold (via simple case folding) into a codepoint of the named property.
     * Used to implement case-insensitive {@code \p{X}}. May return
     * {@code null} to indicate "no fold table; treat as identical to
     * {@link #tableFor(String)}" — i.e. no extra codepoints gained by
     * case folding.
     */
    int[] foldTableFor(String name);
}
