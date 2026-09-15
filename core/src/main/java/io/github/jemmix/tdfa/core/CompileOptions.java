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
    private final boolean deferWholeRejection;
    private final UnicodeDataProvider unicodeProvider;
    private final CompileObserver observer;

    private CompileOptions(boolean longestMatch, boolean disableUnicodeGroups,
                           boolean deferWholeRejection,
                           UnicodeDataProvider unicodeProvider, CompileObserver observer) {
        this.longestMatch = longestMatch;
        this.disableUnicodeGroups = disableUnicodeGroups;
        this.deferWholeRejection = deferWholeRejection;
        this.unicodeProvider = unicodeProvider;
        this.observer = observer;
    }

    /** Default options: leftmost-first (Perl) semantics, JDK-default Unicode tables. */
    public static CompileOptions of() {
        return new CompileOptions(false, false, false, null, null);
    }

    /** POSIX leftmost-longest match semantics (re2j {@code LONGEST_MATCH}). */
    public CompileOptions longestMatch() {
        return new CompileOptions(true, disableUnicodeGroups, deferWholeRejection,
                unicodeProvider, observer);
    }

    /** Reject {@code \p{...}} / {@code \P{...}} at compile time (re2j {@code DISABLE_UNICODE_GROUPS}). */
    public CompileOptions disableUnicodeGroups() {
        return new CompileOptions(longestMatch, true, deferWholeRejection,
                unicodeProvider, observer);
    }

    /**
     * Accept a pattern whose whole-match ({@code matches()}) artifact exceeds
     * the determinization budget: the rejection is recorded at compile time
     * and rethrown by every whole call, while find operations keep working.
     * Default OFF — such patterns fail {@code compile()} with the standard
     * {@code "pattern too large"} rejection instead (the facade's
     * {@code Pattern.DEFER_WHOLE_REJECTION} flag is the same switch).
     */
    public CompileOptions deferWholeRejection() {
        return new CompileOptions(longestMatch, disableUnicodeGroups, true,
                unicodeProvider, observer);
    }

    /** Resolve {@code \p{...}} property classes against the given tables instead of the JDK default. */
    public CompileOptions unicode(UnicodeDataProvider provider) {
        return new CompileOptions(longestMatch, disableUnicodeGroups, deferWholeRejection,
                provider, observer);
    }

    public boolean isLongestMatch() { return longestMatch; }

    public boolean isDisableUnicodeGroups() { return disableUnicodeGroups; }

    /** Whether both-builds-over-budget patterns are accepted with a recorded whole rejection. */
    public boolean isDeferWholeRejection() { return deferWholeRejection; }

    /** Configured provider, or {@code null} for the default resolution. */
    public UnicodeDataProvider unicodeProvider() { return unicodeProvider; }

    /** Attach a compilation transparency hook (stage timings, decisions). */
    public CompileOptions observer(CompileObserver obs) {
        return new CompileOptions(longestMatch, disableUnicodeGroups, deferWholeRejection,
                unicodeProvider, obs);
    }

    /** Configured observer, or {@code null} for none. */
    public CompileObserver observer() { return observer; }
}
