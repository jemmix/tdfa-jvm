package io.github.jemmix.tdfa.core.unicode;

/**
 * The re2j surface's default Unicode source: property-resolved tables (the
 * {@value UnicodeProviders#PROPERTY_NAME} selection, lazily) under the
 * built-in <b>RE2-lineage fold universe</b> ({@link CaseFoldTable#foldRanges}
 * — the Turkic İ/ı pair fold-inert, the re2j/Go reading the surface's oracle
 * answers in). The jur surface's default is the plain property resolution
 * (the JDK provider, whose fold universe is the {@code java.util.regex}
 * reading) — post-split the two surfaces' fold orbits follow their oracle,
 * so an un-pinned default would make {@code (?i)i} answer differently on the
 * two surfaces for İ/ı inputs.
 *
 * <p>An explicitly passed provider is never wrapped: parity tiers that pin
 * a snapshot or bridge a live oracle ({@code Re2jUnicodeProvider}, the
 * {@code tdfa-unicode-*} jars) keep supplying their own folds.
 */
public final class Re2jUniverseProvider implements UnicodeDataProvider {

    static final Re2jUniverseProvider INSTANCE = new Re2jUniverseProvider();

    private Re2jUniverseProvider() {
    }

    /** Supplier shape for {@code PatternSurface.register}. */
    public static Re2jUniverseProvider instance() {
        return INSTANCE;
    }

    @Override
    public int[] tableFor(String name) {
        return UnicodeProviders.get().tableFor(name);
    }

    @Override
    public boolean suppliesFoldUniverse() {
        return true;
    }

    @Override
    public int[] foldCounterparts(int cp) {
        return CaseFoldTable.foldRanges(cp);
    }

    @Override
    public int[] foldTableFor(String name) {
        int[] table = tableFor(name);
        return table == null ? null : JdkUnicodeDataProvider.buildFoldTable(table, CaseFoldTable::foldRanges);
    }
}
