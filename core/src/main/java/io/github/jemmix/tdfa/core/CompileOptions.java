package io.github.jemmix.tdfa.core;

import io.github.jemmix.tdfa.unicode.UnicodeDataProvider;

/**
 * Immutable compilation options for the TDFA pipeline. Builder-style:
 * every wither returns a new instance.
 *
 * <pre>
 *   CompileOptions o = CompileOptions.of().longestMatch().unicode(provider);
 *   CompiledRegex r = CompiledRegex.compile(pattern, o);
 * </pre>
 *
 * <p>Engine selection is NOT an option: bring-your-own engines pass a
 * {@link RegexEngineFactory} directly to the facade's
 * {@code Pattern.compile(regex, flags, factory)} — one route, not two.
 */
public final class CompileOptions {

    private final boolean longestMatch;
    private final boolean disableUnicodeGroups;
    private final UnicodeDataProvider unicodeProvider;
    private final CompileObserver observer;

    private CompileOptions(boolean longestMatch, boolean disableUnicodeGroups,
                           UnicodeDataProvider unicodeProvider, CompileObserver observer) {
        this.longestMatch = longestMatch;
        this.disableUnicodeGroups = disableUnicodeGroups;
        this.unicodeProvider = unicodeProvider;
        this.observer = observer;
    }

    /** Default options: leftmost-first (Perl) semantics, JDK-default Unicode tables. */
    public static CompileOptions of() {
        return new CompileOptions(false, false, null, null);
    }

    /** POSIX leftmost-longest match semantics (re2j {@code LONGEST_MATCH}). */
    public CompileOptions longestMatch() {
        return new CompileOptions(true, disableUnicodeGroups, unicodeProvider, observer);
    }

    /** Reject {@code \p{...}} / {@code \P{...}} at compile time (re2j {@code DISABLE_UNICODE_GROUPS}). */
    public CompileOptions disableUnicodeGroups() {
        return new CompileOptions(longestMatch, true, unicodeProvider, observer);
    }

    /** Resolve {@code \p{...}} property classes against the given tables instead of the JDK default. */
    public CompileOptions unicode(UnicodeDataProvider provider) {
        return new CompileOptions(longestMatch, disableUnicodeGroups, provider, observer);
    }

    public boolean isLongestMatch() { return longestMatch; }

    public boolean isDisableUnicodeGroups() { return disableUnicodeGroups; }

    /** Configured provider, or {@code null} for the default resolution. */
    public UnicodeDataProvider unicodeProvider() { return unicodeProvider; }

    /** Attach a compilation transparency hook (stage timings, decisions). */
    public CompileOptions observer(CompileObserver obs) {
        return new CompileOptions(longestMatch, disableUnicodeGroups, unicodeProvider, obs);
    }

    /** Configured observer, or {@code null} for none. */
    public CompileObserver observer() { return observer; }
}
