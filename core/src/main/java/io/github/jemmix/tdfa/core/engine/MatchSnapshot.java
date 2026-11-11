package io.github.jemmix.tdfa.core.engine;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * An immutable snapshot of one match — the machinery's
 * {@link java.util.regex.MatchResult} implementation: whole-match and
 * capture bounds plus group text, cut from the input at snapshot time
 * (the matcher may iterate on; the snapshot does not move). Built by
 * {@link Matcher#toMatchResult()} and handed to the function-based
 * replacements ({@code replaceAll(Function<MatchResult, String>)}), the
 * shape {@code java.util.regex.Matcher} exposes the same way.
 *
 * <p>Extends the JDK interface with the tdfa readout
 * {@link #groupSpans(int)} (the multi-valued tag view, BT22 §3.1) —
 * additive, like every extension the shared matcher carries. The JDK's
 * named-group defaults ride the engine's {@link #namedGroups()} map.
 */
public final class MatchSnapshot implements java.util.regex.MatchResult {

    private final CharSequence input;

    private final MatchResult match;

    private final Map<String, Integer> namedGroups;

    private MatchSnapshot(CharSequence input, MatchResult match, Map<String, Integer> namedGroups) {
        this.input = input;
        this.match = match;
        this.namedGroups = namedGroups == null ? Collections.emptyMap() : Collections.unmodifiableMap(namedGroups);
    }

    /** Snapshot {@code match} against {@code input} (the matcher's current result). */
    static MatchSnapshot of(CharSequence input, MatchResult match, Map<String, Integer> namedGroups) {
        return new MatchSnapshot(input, match, namedGroups);
    }

    /** The empty snapshot a failed or un-attempted match yields (every
     * access reads {@code -1}/{@code null}, the JDK's failed-find shape). */
    static MatchSnapshot empty(CharSequence input, int groupCount) {
        int[] regs = new int[2 * (groupCount + 1)];
        Arrays.fill(regs, -1);
        return new MatchSnapshot(input, new MatchResult(regs, 0, groupCount, -1, -1), Collections.emptyMap());
    }

    /** JDK 20+ default override (the interface predates it): whether a
     *  match is recorded on this snapshot. */
    public boolean hasMatch() {
        return match.start(0) >= 0;
    }

    @Override
    public int start() {
        return start(0);
    }

    @Override
    public int start(int group) {
        return match.start(group);
    }

    @Override
    public int end() {
        return end(0);
    }

    @Override
    public int end(int group) {
        return match.end(group);
    }

    @Override
    public String group() {
        return group(0);
    }

    @Override
    public String group(int group) {
        int s = start(group);
        int e = end(group);
        if (s < 0 || e < 0) {
            return null;
        }
        return input.subSequence(s, e).toString();
    }

    @Override
    public int groupCount() {
        return match.groupCount();
    }

    /** JDK 20+ default override (the interface predates it): the engine's
     *  name&rarr;index map, which makes the interface's named-group
     *  defaults ({@code start(String)}/{@code end(String)}/{@code
     *  group(String)}) work. */
    public Map<String, Integer> namedGroups() {
        return namedGroups;
    }

    /** JDK 20+ interface default override (see {@link #hasMatch()}). */
    public boolean hasGroup(int group) {
        return group >= 0 && group <= groupCount();
    }

    /**
     * Every participating iteration's span of {@code group} (the
     * multi-valued readout, BT22 §3.1 — see {@link MatchResult#groupSpans}).
     */
    public int[] groupSpans(int group) {
        return match.groupSpans(group);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof MatchSnapshot)) {
            return false;
        }
        MatchSnapshot s = (MatchSnapshot) o;
        return Objects.equals(match, s.match);
    }

    @Override
    public int hashCode() {
        return 31 * match.hashCode();
    }

    @Override
    public String toString() {
        return match.toString();
    }
}
