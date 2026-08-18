package io.github.jemmix.tdfa.unicode;

/**
 * The "no Unicode support" provider — used when the regex engine is
 * configured to disable {@code \p{...}} and {@code \P{...}} entirely.
 *
 * Both methods return {@code null}, which the parser interprets as
 * "unknown property name" — i.e. compiling any pattern containing a
 * Unicode-property escape throws a parse error.
 */
final class NoUnicodeProvider implements UnicodeDataProvider {
    static final NoUnicodeProvider INSTANCE = new NoUnicodeProvider();
    private NoUnicodeProvider() {}

    @Override public int[] tableFor(String name) { return null; }
    @Override public int[] foldTableFor(String name) { return null; }
}
