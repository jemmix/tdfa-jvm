package io.github.jemmix.tdfa.re2j;

import io.github.jemmix.tdfa.core.unicode.CaseFoldTable;
import io.github.jemmix.tdfa.core.unicode.UnicodeDataProvider;
import io.github.jemmix.tdfa.core.unicode.UnicodeProviders;

/**
 * The re2j facade's default Unicode source: property-resolved tables (the
 * {@value UnicodeProviders#PROPERTY_NAME} selection, lazily) under the
 * built-in <b>RE2/Go fold universe</b> ({@link CaseFoldTable#foldRanges}
 * — the Turkic İ/ı pair fold-inert, the reading the re2j oracle answers
 * in). The jur facade's default is the plain property resolution (the
 * JDK provider, whose fold universe is the {@code java.util.regex}
 * reading) — post-split the two facades' fold orbits follow their
 * oracle, so an un-pinned default would make {@code (?i)i} answer
 * differently on the two facades for İ/ı inputs. Facade-owned on
 * purpose: the fold universe a surface defaults to is dialect data,
 * not core machinery (see {@code docs/dialect-agnostic-core.md}).
 *
 * <p>An explicitly passed provider is never wrapped: parity tiers that
 * pin a snapshot or bridge a live oracle ({@code Re2jUnicodeProvider},
 * the {@code tdfa-unicode-*} jars) keep supplying their own folds.
 */
public final class Re2FoldUniverse implements UnicodeDataProvider {

    private static final Re2FoldUniverse INSTANCE = new Re2FoldUniverse();

    private Re2FoldUniverse() {
    }

    /** Supplier shape for the facade's default-provider pass-through. */
    public static Re2FoldUniverse instance() {
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
        return table == null ? null : CaseFoldTable.expandByFold(table, CaseFoldTable::foldRanges);
    }
}
