package io.github.jemmix.tdfa.core.compile;

import io.github.jemmix.tdfa.core.report.CompileObserver;
import io.github.jemmix.tdfa.core.tnfa.Semantics;
import io.github.jemmix.tdfa.core.unicode.UnicodeDataProvider;

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
    private final boolean multiValuedTags;
    private final boolean disableUnicodeGroups;
    private final UnicodeDataProvider unicodeProvider;
    private final CompileObserver observer;
    private final Semantics semantics;

    private CompileOptions(boolean longestMatch, boolean multiValuedTags, boolean disableUnicodeGroups,
        UnicodeDataProvider unicodeProvider, CompileObserver observer, Semantics semantics) {
        this.longestMatch = longestMatch;
        this.multiValuedTags = multiValuedTags;
        this.disableUnicodeGroups = disableUnicodeGroups;
        this.unicodeProvider = unicodeProvider;
        this.observer = observer;
        this.semantics = semantics;
    }

    /** Default options: leftmost-first (Perl) semantics, JDK-default Unicode tables. */
    public static CompileOptions of() {
        return new CompileOptions(false, false, false, null, null, null);
    }

    /** POSIX leftmost-longest match semantics (re2j {@code LONGEST_MATCH}). */
    public CompileOptions longestMatch() {
        return new CompileOptions(true, multiValuedTags, disableUnicodeGroups, unicodeProvider, observer, semantics);
    }

    /**
     * Multi-valued tags (BT22 &sect;3.1): every capture tag keeps its whole
     * offset sequence under repetition (append ops, offset-list registers,
     * {@code MatchResult.groupSpans} readout). Single-value results are
     * unchanged; the compile trades the fixed-tag/map/regopt optimizations
     * for the per-iteration offsets.
     */
    public CompileOptions multiValuedTags() {
        return new CompileOptions(longestMatch, true, disableUnicodeGroups, unicodeProvider, observer, semantics);
    }

    /** Reject {@code \p{...}} / {@code \P{...}} at compile time (re2j {@code DISABLE_UNICODE_GROUPS}). */
    public CompileOptions disableUnicodeGroups() {
        return new CompileOptions(longestMatch, multiValuedTags, true, unicodeProvider, observer, semantics);
    }

    /** Resolve {@code \p{...}} property classes against the given tables instead of the JDK default. */
    public CompileOptions unicode(UnicodeDataProvider provider) {
        return new CompileOptions(longestMatch, multiValuedTags, disableUnicodeGroups, provider, observer, semantics);
    }

    /**
     * The compile's interpretation policy — its {@link Semantics} (the
     * JUR-compat axes: {@code UNIX_LINES}, {@code UNICODE_CASE},
     * {@code CODEPOINT_BOUNDARIES}, {@code EMPTY_LAST_LINE},
     * {@code END_OF_TEXT_ONLY}, {@code EMPTY_ITERATION_SPANS},
     * {@code UNGREEDY_U}). {@code null} (the default) keeps the default
     * lane — every axis unset, the {@code java.util.regex}-parity
     * reading; the withers compose any per-axis selection, and every
     * axis set restores the RE2-lineage side. This is the core-tier
     * spelling of the lane selection; the facade's int-flag mapping of
     * the axes is the other.
     */
    public CompileOptions semantics(Semantics semantics) {
        return new CompileOptions(longestMatch, multiValuedTags, disableUnicodeGroups, unicodeProvider, observer,
            semantics);
    }

    public boolean isLongestMatch() {
        return longestMatch;
    }

    /** Multi-valued tags requested (see {@link #multiValuedTags()}). */
    public boolean isMultiValuedTags() {
        return multiValuedTags;
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
        return new CompileOptions(longestMatch, multiValuedTags, disableUnicodeGroups, unicodeProvider, obs, semantics);
    }

    /** Configured provider, or {@code null} for the default resolution. */
    public CompileObserver observer() {
        return observer;
    }

    /** Configured interpretation policy, or {@code null} for the default lane (see {@link #semantics(Semantics)}). */
    public Semantics semantics() {
        return semantics;
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
        return longestMatch == c.longestMatch && multiValuedTags == c.multiValuedTags
            && disableUnicodeGroups == c.disableUnicodeGroups && Objects.equals(unicodeProvider, c.unicodeProvider)
            && Objects.equals(semantics, c.semantics)
            // Identity is intentional: the observer is a push hook, not a
            // value — two different hook instances with equal state are
            // still different options (they observe different people).
            && observer == c.observer;
    }

    @Override
    public int hashCode() {
        int h = (longestMatch ? 1 : 0) * 31 + (multiValuedTags ? 1 : 0);
        h = h * 31 + (disableUnicodeGroups ? 1 : 0);
        h = h * 31 + (unicodeProvider == null ? 0 : unicodeProvider.hashCode());
        h = h * 31 + (semantics == null ? 0 : semantics.hashCode());
        return h * 31 + System.identityHashCode(observer);
    }
}
