package io.github.jemmix.tdfa.core.engine;

import io.github.jemmix.tdfa.core.emit.EmittedSurface;

import java.nio.charset.StandardCharsets;
import java.util.Spliterator;
import java.util.Spliterators.AbstractSpliterator;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Stateful matcher over one input: find-iteration, group access, and the
 * full replacement machinery ({@code replaceAll}/{@code replaceFirst}/
 * {@code appendReplacement}/{@code appendTail}), mirroring the re2j /
 * {@code java.util.regex.Matcher} surface on top of a {@link RegexEngine}
 * — the {@code java.util.regex} region/bounds family
 * ({@code region}/{@code useTransparentBounds}/
 * {@code useAnchoringBounds}/{@code hitEnd}/{@code requireEnd}) beside
 * it (both facades' matchers share this class; the jur facade is the
 * one that claims the family). Implements
 * {@link java.util.regex.MatchResult} so the matcher works wherever the
 * JDK interface is expected.
 *
 * <p><b>Shell contract.</b> This class is also the base for per-pattern
 * generated matchers (ASM tier): the bookkeeping state below is
 * {@code protected}, and the three hot entry points — {@link #find()},
 * {@link #matches()}, {@link #lookingAt()} — are overridable. A generated
 * subclass inlines the bookkeeping and calls its engine directly with a
 * statically-known receiver type, so the call chain devirtualizes
 * end-to-end; all cold machinery is inherited unchanged (one brain, thin
 * generated hot paths). This matcher also owns the {@link #scratch}
 * carrier the generated shells hand to their carrier-aware engine calls
 * (register/buffer reuse across find iterations, released with the
 * matcher).
 */
public class Matcher implements java.util.regex.MatchResult {

    /** Mutable ONLY through {@code PatternMatcher.usePattern} (machinery-tier matchers). */
    private RegexEngine engine;

    /** Mutable ONLY through {@code PatternMatcher.usePattern} (machinery-tier matchers). */
    private WholeEngine wholeEngine;

    @EmittedSurface // the 7 fields below are linked by name from generated shells
    protected CharSequence input;

    @EmittedSurface
    protected int inputLength;

    @EmittedSurface
    protected MatchResult match;

    @EmittedSurface
    protected boolean hasMatch;

    @EmittedSurface
    protected int lastMatchStart;

    @EmittedSurface
    protected int lastMatchEnd;

    @EmittedSurface
    protected int appendPos;

    /**
     * This matcher's scratch carrier (register file + simulation buffers),
     * reused across every match operation on this matcher and freed with it
     * — the re2j {@code Matcher} shape: the stateful matcher owns the
     * mutable buffers, the engine stays immutable and thread-safe. Allocated
     * only when one of this matcher's engines {@link RegexEngine#wantsScratch()
     * wants} a carrier; otherwise {@code null}, and the engines allocate on
     * demand at their (cold) carrier-consuming fallbacks — carrier-free
     * engines then run this matcher's whole ladder without a single scratch
     * allocation.
     */
    @EmittedSurface // emitted shells read this field (carrier-aware engine calls)
    protected MatchScratch scratch;

    // ---- region bounds (the java.util.regex Matcher family) ----

    /** Region start; {@code 0} while inactive (see {@link #regionTo}). */
    @EmittedSurface // emitted find()/matches()/lookingAt() bail to super when a region is active
    protected int regionFrom;

    /** Region end (exclusive); {@code -1} = no region (the full input). */
    @EmittedSurface
    protected int regionTo = -1;

    /** Whether {@code ^}/{@code $}/{@code \A}/{@code \Z}/{@code \z} anchor at the region edges (java's default). */
    @EmittedSurface
    protected boolean anchoringBounds = true;

    /** Whether {@code \b}/{@code \B} read their neighbors beyond the region edges (java's default: opaque). */
    @EmittedSurface
    protected boolean transparentBounds;

    public Matcher(RegexEngine engine, WholeEngine wholeEngine, CharSequence input) {
        this.engine = engine;
        this.wholeEngine = wholeEngine;
        this.input = input;
        this.inputLength = input.length();
        // The whole engine is always also a RegexEngine by construction
        // (runner or generated class); a carrier-free one says so through
        // that side of its type.
        boolean wholeWants = wholeEngine instanceof RegexEngine && ((RegexEngine) wholeEngine).wantsScratch();
        this.scratch = engine.wantsScratch() || wholeWants ? new MatchScratch() : null;
    }

    /**
     * Rebind the serving engines — {@code usePattern}'s one mutation,
     * confined to the machinery matcher tier (the generated per-pattern
     * matchers bake their engine's concrete type and refuse the
     * operation instead). Public-but-internal.
     */
    protected final void rebindEngines(RegexEngine engine, WholeEngine wholeEngine) {
        this.engine = engine;
        this.wholeEngine = wholeEngine;
    }

    public Matcher reset() {
        hasMatch = false;
        match = null;
        appendPos = 0;
        // java.util.regex: reset() returns the matcher to the DEFAULT
        // region (the whole input); the bounds flags are NOT reset.
        regionFrom = 0;
        regionTo = -1;
        return this;
    }

    /** JDK 20+ interface default override: whether the last match
     *  operation succeeded (a matcher's own view of the same flag the
     *  snapshot exposes). */
    public boolean hasMatch() {
        return hasMatch;
    }

    /** JDK 20+ interface default override: whether the pattern has a
     *  capture group at {@code group} (index {@code 0} is the whole
     *  match). */
    public boolean hasGroup(int group) {
        return group >= 0 && group <= engine.groupCount();
    }

    public Matcher reset(CharSequence input) {
        this.input = input;
        this.inputLength = input.length();
        return reset();
    }

    /** Reset with UTF-8-decoded bytes as the new input (re2j's {@code MatcherInput.utf8}). */
    public Matcher reset(byte[] bytes) {
        return reset(new String(bytes, StandardCharsets.UTF_8));
    }

    // ---- match operations ----

    public boolean matches() {
        if (regionTo >= 0) {
            return matchesInRegion();
        }
        MatchResult m = wholeEngine.matchWhole(input, scratch);
        hasMatch = m != null;
        if (hasMatch) {
            match = m;
            lastMatchStart = m.start(0);
            lastMatchEnd = m.end(0);
        }
        return hasMatch;
    }

    public boolean lookingAt() {
        if (regionTo >= 0) {
            return lookingAtInRegion();
        }
        MatchResult m = engine.match(input, 0, scratch);
        if (m != null && m.start(0) == 0) {
            match = m;
            hasMatch = true;
            lastMatchStart = m.start(0);
            lastMatchEnd = m.end(0);
            return true;
        }
        hasMatch = false;
        return false;
    }

    public boolean find() {
        if (regionTo >= 0) {
            return findInRegion();
        }
        int start;
        if (hasMatch) {
            start = lastMatchEnd;
            if (lastMatchStart == lastMatchEnd) {
                start++;
            }
        } else {
            start = appendPos;
        }
        if (start > inputLength) {
            hasMatch = false;
            return false;
        }
        MatchResult m = engine.match(input, start, scratch);
        if (m == null) {
            hasMatch = false;
            return false;
        }
        match = m;
        hasMatch = true;
        lastMatchStart = m.start(0);
        lastMatchEnd = m.end(0);
        return true;
    }

    public boolean find(int start) {
        if (start < 0 || start > inputLength) {
            throw new IndexOutOfBoundsException("start index out of bounds: " + start);
        }
        reset();
        appendPos = start;
        return find();
    }

    // ---- region bounds (the java.util.regex Matcher family) ----

    /**
     * Sets the limits of this matcher's region — the part of the input
     * that will be searched ({@code java.util.regex.Matcher.region}):
     * matching is confined to {@code [start, end)}, position predicates
     * evaluate per the bounds flags ({@link #useAnchoringBounds(boolean)}/
     * {@link #useTransparentBounds(boolean)}), reported positions stay
     * ABSOLUTE input positions, and — exactly like the JDK — invoking
     * this resets the matcher first.
     */
    public Matcher region(int start, int end) {
        if (start < 0 || start > inputLength) {
            throw new IndexOutOfBoundsException("start index out of bounds: " + start);
        }
        if (end < 0 || end > inputLength) {
            throw new IndexOutOfBoundsException("end index out of bounds: " + end);
        }
        if (start > end) {
            throw new IndexOutOfBoundsException("start > end: " + start + " > " + end);
        }
        reset();
        regionFrom = start;
        regionTo = end;
        return this;
    }

    /** The region's start index (0 while no region is active). */
    public int regionStart() {
        return regionTo >= 0 ? regionFrom : 0;
    }

    /** The region's end index (the input length while no region is active). */
    public int regionEnd() {
        return regionTo >= 0 ? regionTo : inputLength;
    }

    /**
     * Whether region boundaries are transparent to the boundary-matching
     * constructs ({@code \b}/{@code \B} see their true neighbors across
     * the region edge; the JDK default is {@code false} — the region
     * behaves as the whole text for them). Lookarounds do not exist in
     * this engine (rejected at compile), so the word pair is the whole
     * affected family.
     */
    public boolean hasTransparentBounds() {
        return transparentBounds;
    }

    /** See {@link #hasTransparentBounds()}; takes effect from the next match operation. */
    public Matcher useTransparentBounds(boolean b) {
        transparentBounds = b;
        return this;
    }

    /**
     * Whether the region's boundaries match the anchors
     * ({@code ^}/{@code $}/{@code \A}/{@code \Z}/{@code \z} anchor at the
     * region edges; the JDK default is {@code true} — {@code false} makes
     * them anchor at the TRUE text edges only).
     */
    public boolean hasAnchoringBounds() {
        return anchoringBounds;
    }

    /** See {@link #hasAnchoringBounds()}; takes effect from the next match operation. */
    public Matcher useAnchoringBounds(boolean b) {
        anchoringBounds = b;
        return this;
    }

    /**
     * Whether the end of the (region of) input was hit by the search
     * engine in the last match operation — the
     * {@code java.util.regex.Matcher.hitEnd} contract: when {@code true},
     * more input could change the result of the last search. Re-derived
     * cold (a tagless probe over the artifact), so the hot paths pay
     * nothing and this call is never stale.
     */
    public boolean hitEnd() {
        return (probeEndFlags() & RegexEngine.PROBE_HIT_END) != 0;
    }

    /**
     * Whether more input could UNDO the last result — the
     * {@code java.util.regex.Matcher.requireEnd} contract: when
     * {@code true} and a match was found, appending input could lose it
     * (an end-anchor or past-end word boundary carried the match).
     */
    public boolean requireEnd() {
        return (probeEndFlags() & RegexEngine.PROBE_REQUIRE_END) != 0;
    }

    /**
     * An immutable snapshot of the current match — the machinery's
     * {@code java.util.regex.Matcher.toMatchResult}: bounds, group text
     * and the named-group map stay readable after the matcher iterates
     * on; a failed or un-attempted match yields the JDK's empty shape
     * ({@code -1}/{@code null} reads, no throw).
     */
    public MatchSnapshot toMatchResult() {
        if (!hasMatch || match == null) {
            return MatchSnapshot.empty(input, engine.groupCount());
        }
        return MatchSnapshot.of(input, match, engine.namedGroups());
    }

    /**
     * Replaces every subsequence that matches the pattern with the
     * function's answer — the
     * {@code java.util.regex.Matcher.replaceAll(Function<MatchResult, String>)}
     * spelling; the function sees an immutable {@link #toMatchResult()}
     * snapshot per match, and a {@code null} answer fails with
     * {@link NullPointerException} exactly like the JDK's.
     */
    public String replaceAll(Function<java.util.regex.MatchResult, String> replacer) {
        reset();
        StringBuilder sb = new StringBuilder();
        while (find()) {
            appendReplacement(sb, replacer);
        }
        appendTail(sb);
        return sb.toString();
    }

    /**
     * Replaces the first subsequence that matches the pattern with the
     * function's answer (see {@link #replaceAll(java.util.function.Function)}).
     */
    public String replaceFirst(Function<java.util.regex.MatchResult, String> replacer) {
        reset();
        StringBuilder sb = new StringBuilder();
        if (find()) {
            appendReplacement(sb, replacer);
        }
        appendTail(sb);
        return sb.toString();
    }

    /**
     * The function-based twin of {@link #appendReplacement(StringBuilder, String)}:
     * appends the input since the last append position, then the
     * function's answer for the current match.
     */
    public Matcher appendReplacement(StringBuilder sb, Function<java.util.regex.MatchResult, String> replacer) {
        ensureMatch();
        if (appendPos < lastMatchStart) {
            sb.append(input, appendPos, lastMatchStart);
        }
        appendPos = lastMatchEnd;
        String r = replacer.apply(toMatchResult());
        if (r == null) {
            throw new NullPointerException("replacer function returned null");
        }
        sb.append(r);
        return this;
    }

    /**
     * The JDK 9+ stream spelling: successive non-overlapping matches as
     * immutable {@link java.util.regex.MatchResult} snapshots
     * ({@code java.util.regex.Matcher.results()} — the same find()
     * iteration, snapshot per element).
     */
    public Stream<java.util.regex.MatchResult> results() {
        return StreamSupport.stream(new AbstractSpliterator<java.util.regex.MatchResult>(Long.MAX_VALUE,
            Spliterator.ORDERED | Spliterator.NONNULL) {
            @Override
            public boolean tryAdvance(Consumer<? super java.util.regex.MatchResult> action) {
                if (!find()) {
                    return false;
                }
                action.accept(toMatchResult());
                return true;
            }
        }, false);
    }

    // ---- internal: region routing ----

    /** java.util.regex's find() protocol over an active region. */
    private boolean findInRegion() {
        int from = regionFrom;
        int to = regionTo;
        int next = hasMatch ? lastMatchEnd : appendPos;
        if (hasMatch && lastMatchStart == lastMatchEnd) {
            next++;
        }
        if (next < from) {
            next = from;
        }
        if (next > to) {
            hasMatch = false;
            match = null;
            return false;
        }
        MatchResult m = searchRegion(from, next, to);
        if (m == null) {
            hasMatch = false;
            return false;
        }
        match = m;
        hasMatch = true;
        lastMatchStart = m.start(0);
        lastMatchEnd = m.end(0);
        return true;
    }

    private boolean lookingAtInRegion() {
        int from = regionFrom;
        int to = regionTo;
        MatchResult m;
        if (anchoringBounds && !transparentBounds) {
            m = engine.match(input.subSequence(from, to), 0, scratch);
            if (m != null && m.start(0) == 0) {
                m = MatchResult.shifted(m, from);
            } else {
                m = null;
            }
        } else {
            m = engine.matchRegionLookingAt(input, from, to, anchoringBounds, transparentBounds, scratch);
        }
        if (m == null) {
            hasMatch = false;
            return false;
        }
        match = m;
        hasMatch = true;
        lastMatchStart = m.start(0);
        lastMatchEnd = m.end(0);
        return true;
    }

    private boolean matchesInRegion() {
        int from = regionFrom;
        int to = regionTo;
        MatchResult m;
        if ((anchoringBounds && !transparentBounds) || !engine.usesPositionPredicates()) {
            // The whole-region walk over a view: exact under the default
            // bounds combo (the region IS the text for every predicate),
            // and exact for predicate-free artifacts under any combo.
            m = wholeEngine.matchWhole(input.subSequence(from, to), scratch);
            if (m != null) {
                m = MatchResult.shifted(m, from);
            }
        } else {
            throw new UnsupportedOperationException("matches() over a region with non-default bounds needs "
                + "position predicates the whole engine cannot bound — use the default bounds, a predicate-free "
                + "pattern, or normalize the region (subSequence) first");
        }
        if (m == null) {
            hasMatch = false;
            return false;
        }
        match = m;
        hasMatch = true;
        lastMatchStart = m.start(0);
        lastMatchEnd = m.end(0);
        return true;
    }

    /**
     * One region search from {@code next}: under the DEFAULT bounds combo
     * (anchoring, opaque) a subSequence view is exact for every engine —
     * the region behaves as the whole text for every position predicate —
     * and the result rebases to absolute positions; the opt-out combos
     * route through the engine's region seam (full input, bounded walk,
     * predicate bases per the flags — absolute positions natively).
     */
    private MatchResult searchRegion(int from, int next, int to) {
        if (anchoringBounds && !transparentBounds) {
            MatchResult m = engine.match(input.subSequence(from, to), next - from, scratch);
            return m == null ? null : MatchResult.shifted(m, from);
        }
        return engine.matchRegion(input, next, to, anchoringBounds, transparentBounds, scratch);
    }

    /** The cold hitEnd/requireEnd derivation (see {@link #hitEnd()}). */
    private int probeEndFlags() {
        int from = regionTo >= 0 ? regionFrom : 0;
        int to = regionTo >= 0 ? regionTo : inputLength;
        return engine.probeEndFlags(input, from, to, regionTo >= 0 ? anchoringBounds : true,
            regionTo >= 0 ? transparentBounds : false, hasMatch ? lastMatchStart : 0, hasMatch ? lastMatchEnd : -1,
            hasMatch);
    }

    // ---- match results ----

    @Override
    public int start() {
        return start(0);
    }

    @Override
    public int end() {
        return end(0);
    }

    @Override
    public int start(int group) {
        ensureMatch();
        return match.start(group);
    }

    @Override
    public int end(int group) {
        ensureMatch();
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

    public String group(String name) {
        return group(groupIndex(name));
    }

    /**
     * Every iteration's span of {@code group} (multi-valued readout, BT22
     * §3.1): flat {@code [s0,e0,s1,e1,…]} in match order, {@code (-1,-1)}
     * for iterations that bypassed the group. On a single-valued compile
     * this is just the one reported span.
     */
    public int[] groupSpans(int group) {
        ensureMatch();
        return match.groupSpans(group);
    }

    public int start(String name) {
        return start(groupIndex(name));
    }

    public int end(String name) {
        return end(groupIndex(name));
    }

    @Override
    public int groupCount() {
        return engine.groupCount();
    }

    public int programSize() {
        return engine.programSize();
    }

    // ---- replacement ----

    public String replaceAll(String replacement) {
        reset();
        StringBuilder sb = new StringBuilder();
        while (find()) {
            appendReplacement(sb, replacement);
        }
        appendTail(sb);
        return sb.toString();
    }

    public String replaceFirst(String replacement) {
        reset();
        StringBuilder sb = new StringBuilder();
        if (find()) {
            appendReplacement(sb, replacement);
        }
        appendTail(sb);
        return sb.toString();
    }

    public Matcher appendReplacement(StringBuilder sb, String replacement) {
        ensureMatch();
        if (appendPos < lastMatchStart) {
            sb.append(input, appendPos, lastMatchStart);
        }
        appendPos = lastMatchEnd;
        appendReplacementInternal(sb, replacement);
        return this;
    }

    public Matcher appendReplacement(StringBuffer sb, String replacement) {
        ensureMatch();
        if (appendPos < lastMatchStart) {
            sb.append(input, appendPos, lastMatchStart);
        }
        appendPos = lastMatchEnd;
        StringBuilder tmp = new StringBuilder();
        appendReplacementInternal(tmp, replacement);
        sb.append(tmp);
        return this;
    }

    public StringBuilder appendTail(StringBuilder sb) {
        sb.append(input, appendPos, inputLength);
        return sb;
    }

    public StringBuffer appendTail(StringBuffer sb) {
        sb.append(input, appendPos, inputLength);
        return sb;
    }

    /** Quote {@code \} and {@code $} in {@code s} for use as a literal replacement. */
    public static String quoteReplacement(String s) {
        if (s.indexOf('\\') < 0 && s.indexOf('$') < 0) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s.length() * 2);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' || c == '$') {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }

    // ---- internal ----

    private void ensureMatch() {
        if (!hasMatch) {
            throw new IllegalStateException("perhaps no match attempted");
        }
    }

    private int groupIndex(String name) {
        Integer idx = engine.namedGroups().get(name);
        if (idx == null) {
            throw new IllegalArgumentException("group '" + name + "' not found");
        }
        return idx;
    }

    private void appendReplacementInternal(StringBuilder sb, String replacement) {
        int gc = groupCount();
        int last = 0;
        int i = 0;
        int m = replacement.length();
        for (; i < m - 1; i++) {
            char c = replacement.charAt(i);
            if (c == '\\') {
                if (last < i) {
                    sb.append(replacement, last, i);
                }
                i++;
                last = i;
                continue;
            }
            if (c == '$') {
                char c2 = replacement.charAt(i + 1);
                if (c2 >= '0' && c2 <= '9') {
                    int n = c2 - '0';
                    if (last < i) {
                        sb.append(replacement, last, i);
                    }
                    for (i += 2; i < m; i++) {
                        c2 = replacement.charAt(i);
                        if (c2 < '0' || c2 > '9' || n * 10 + c2 - '0' > gc) {
                            break;
                        }
                        n = n * 10 + c2 - '0';
                    }
                    if (n > gc) {
                        throw new IndexOutOfBoundsException("n > number of groups: " + n);
                    }
                    String g = group(n);
                    if (g != null) {
                        sb.append(g);
                    }
                    last = i;
                    i--;
                } else if (c2 == '{') {
                    if (last < i) {
                        sb.append(replacement, last, i);
                    }
                    i += 2;
                    int j = i;
                    while (j < m && replacement.charAt(j) != '}' && replacement.charAt(j) != ' ') {
                        j++;
                    }
                    if (j >= m || replacement.charAt(j) != '}') {
                        throw new IllegalArgumentException("named capture group is missing trailing '}'");
                    }
                    String gName = replacement.substring(i, j);
                    String gVal = group(gName);
                    if (gVal != null) {
                        sb.append(gVal);
                    }
                    last = j + 1;
                    i = j;
                }
            }
        }
        if (last < m) {
            sb.append(replacement, last, m);
        }
    }
}
