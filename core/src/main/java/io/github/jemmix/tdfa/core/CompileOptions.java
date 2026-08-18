package io.github.jemmix.tdfa.core;

import io.github.jemmix.tdfa.unicode.UnicodeDataProvider;

/**
 * Immutable compilation options for the TDFA pipeline. Builder-style:
 * every wither returns a new instance.
 *
 * <pre>
 *   CompileOptions o = CompileOptions.of().longestMatch().unicode(provider);
 *   RegexEngine e = RegexEngine.compile(pattern, o);
 * </pre>
 */
public final class CompileOptions {

    private final boolean longestMatch;
    private final boolean disableUnicodeGroups;
    private final UnicodeDataProvider unicodeProvider;
    private final RegexEngineFactory engineFactory;
    private final CompileObserver observer;

    private CompileOptions(boolean longestMatch, boolean disableUnicodeGroups,
                           UnicodeDataProvider unicodeProvider, RegexEngineFactory engineFactory,
                           CompileObserver observer) {
        this.longestMatch = longestMatch;
        this.disableUnicodeGroups = disableUnicodeGroups;
        this.unicodeProvider = unicodeProvider;
        this.engineFactory = engineFactory;
        this.observer = observer;
    }

    /** Default options: leftmost-first (Perl) semantics, JDK-default Unicode tables. */
    public static CompileOptions of() {
        return new CompileOptions(false, false, null, null, null);
    }

    /** POSIX leftmost-longest match semantics (re2j {@code LONGEST_MATCH}). */
    public CompileOptions longestMatch() {
        return new CompileOptions(true, disableUnicodeGroups, unicodeProvider, engineFactory, observer);
    }

    /** Reject {@code \p{...}} / {@code \P{...}} at compile time (re2j {@code DISABLE_UNICODE_GROUPS}). */
    public CompileOptions disableUnicodeGroups() {
        return new CompileOptions(longestMatch, true, unicodeProvider, engineFactory, observer);
    }

    /** Resolve {@code \p{...}} property classes against the given tables instead of the JDK default. */
    public CompileOptions unicode(UnicodeDataProvider provider) {
        return new CompileOptions(longestMatch, disableUnicodeGroups, provider, engineFactory, observer);
    }

    /**
     * Engine factory for code-generating or custom engine tiers. Core-module
     * compiles ({@code RegexEngine.compile}) are interpreter-only and ignore
     * this setting; the facade ({@code Pattern.compile}) honors it.
     */
    public CompileOptions engineFactory(RegexEngineFactory factory) {
        return new CompileOptions(longestMatch, disableUnicodeGroups, unicodeProvider, factory, observer);
    }

    public boolean isLongestMatch() { return longestMatch; }

    public boolean isDisableUnicodeGroups() { return disableUnicodeGroups; }

    /** Configured provider, or {@code null} for the default resolution. */
    public UnicodeDataProvider unicodeProvider() { return unicodeProvider; }

    /** Configured factory, or {@code null} for the tier default. */
    public RegexEngineFactory engineFactory() { return engineFactory; }

    /** Attach a compilation transparency hook (stage timings, decisions). */
    public CompileOptions observer(CompileObserver obs) {
        return new CompileOptions(longestMatch, disableUnicodeGroups, unicodeProvider, engineFactory, obs);
    }

    /** Configured observer, or {@code null} for none. */
    public CompileObserver observer() { return observer; }
}
