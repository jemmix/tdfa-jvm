package io.github.jemmix.tdfa.core;

import io.github.jemmix.tdfa.unicode.UnicodeDataProvider;

import java.util.Objects;

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

    private CompileOptions(boolean longestMatch, boolean disableUnicodeGroups, UnicodeDataProvider unicodeProvider,
                    CompileObserver observer) {
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

    public boolean isLongestMatch() {
        return longestMatch;
    }

    public boolean isDisableUnicodeGroups() {
        return disableUnicodeGroups;
    }

    /** Configured provider, or {@code null} for the default resolution. */
    public UnicodeDataProvider unicodeProvider() {
        return unicodeProvider;
    }

    /** Attach a compilation transparency hook (stage timings, decisions). */
    public CompileOptions observer(CompileObserver obs) {
        return new CompileOptions(longestMatch, disableUnicodeGroups, unicodeProvider, obs);
    }

    /** Configured provider, or {@code null} for the default resolution. */
    public CompileObserver observer() {
        return observer;
    }

    /** Value equality (wither classes get compared in tests; the observer is
     *  compared by identity — it is a hook, not a value). */
    @SuppressWarnings("ReferenceEquality") // observer: identity is the semantics (see below)
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CompileOptions)) {
            return false;
        }
        CompileOptions c = (CompileOptions) o;
        return longestMatch == c.longestMatch && disableUnicodeGroups == c.disableUnicodeGroups && Objects.equals(unicodeProvider, c.unicodeProvider)
        // Identity is intentional: the observer is a push hook, not a
        // value — two different hook instances with equal state are
        // still different options (they observe different people).
               && observer == c.observer;
    }

    @Override
    public int hashCode() {
        int h = (longestMatch ? 1 : 0) * 31 + (disableUnicodeGroups ? 1 : 0);
        h = h * 31 + (unicodeProvider == null ? 0 : unicodeProvider.hashCode());
        return h * 31 + System.identityHashCode(observer);
    }
}
