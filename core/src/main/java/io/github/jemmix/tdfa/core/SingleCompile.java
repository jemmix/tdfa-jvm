package io.github.jemmix.tdfa.core;

import io.github.jemmix.tdfa.tdfa.Tdfa;
import io.github.jemmix.tdfa.tdfa.TdfaRunner;
import io.github.jemmix.tdfa.tnfa.Tnfa;
import io.github.jemmix.tdfa.unicode.UnicodeDataProvider;

/**
 * The single-compile whole/find ladder, shared by every tier (facade
 * {@code Pattern.compile}, evergreen {@link CompiledRegex}): one parse
 * produces at most two artifacts, everything built eagerly inside
 * {@code compile()} — no engine ever materializes on a match call.
 *
 * <ul>
 *   <li>Whole artifact first: the cut-free determinization
 *       ({@link Tdfa#compileUnpruned}) of the SAME parse — an accept config
 *       alive at end-of-input is a full match, so {@code matchWhole} walks
 *       it to EOF. Work-bounded by {@link #WHOLE_WORK_CAP}: a cut-heavy
 *       pattern's cut-free build can churn orders of magnitude past its
 *       pruned cost before the output caps trip (aws-keys: ~4G ticks vs
 *       30M) and an eager attempt at the default budget would stall
 *       compile() for seconds.</li>
 *   <li>find() shares the whole artifact whenever the pike cut provably
 *       changes nothing ({@code !whole.pikeCutMatters()}); the rare
 *       alternation shapes where it would (e.g. {@code ab|a|ac}) keep a
 *       pruned find compile beside it for leftmost-first exactness.</li>
 *   <li>Over budget: the both-ends-ANCHORED artifact is attempted eagerly
 *       under the same cap. Every accept in an anchored build is
 *       end-of-input-gated, so the pike cut never fires mid-walk and the
 *       cut-free whole walk is exact over the (pruned) artifact —
 *       validated randomized at landing (18 K anchored-vs-unpruned pairs,
 *       both longest modes, 0 diffs).</li>
 *   <li>Both whole builds over budget: the rejection is RECORDED at
 *       compile time and rethrown by every whole call
 *       ({@link OverBudgetWholeEngine}) — deterministic, zero compile at
 *       match time. compile() acceptance follows the find artifact alone
 *       (the historical contract: patterns whose whole DFA is
 *       intrinsically huge, e.g. {@code [\s\S]{0,60}x[\s\S]{0,60}}'s
 *       counter cross-product, keep find()).</li>
 * </ul>
 */
public final class SingleCompile {
    private SingleCompile() { }

    /**
     * Work budget (ticks) for the eager whole-match attempts (unpruned and,
     * on rejection, anchored): comfortably above the worst legit in-corpus
     * unpruned build measured (datefinder's {@code (?i)(?u)} variant at
     * ~115 M ticks — tick counts are deterministic, machine-independent)
     * while rejecting cut-heavy shapes (aws-keys ~4G ticks) in well under a
     * second, so even a slow CI runner stays inside the rebar
     * compile-latency guard's budget.
     */
    public static final long WHOLE_WORK_CAP = 1L << 27;

    /** Resolved artifact pair: the find TDFA plus the whole TDFA (or the
     *  over-budget marker). Carrier class (Java 8 floor). */
    public static final class Artifacts {
        /** The find artifact — never null. The pruned compile, or the whole
         *  artifact itself when the pair is shared. */
        public final Tdfa find;
        /** The cut-free whole artifact; {@code null} iff the unpruned build
         *  rejected on budget (the anchored fallback applies then). */
        public final Tdfa whole;
        Artifacts(Tdfa find, Tdfa whole) { this.find = find; this.whole = whole; }
        /** find() may run directly on the whole artifact (cut inert). */
        public boolean shared() { return find == whole; }
    }

    /**
     * Run the artifact ladder over one parsed TNFA. Budget rejections of
     * the unpruned attempt degrade to the pruned find compile (plus the
     * anchored whole fallback inside {@link #wholeEngine}); any OTHER
     * failure rethrows for the caller to translate.
     */
    public static Artifacts artifacts(Tnfa nfa, boolean longestMatch, CompileObserver obs) {
        try {
            Tdfa whole = Tdfa.compileUnpruned(nfa, longestMatch, obs, WHOLE_WORK_CAP);
            if (whole.pikeCutMatters()) {
                obs.note("pikeCut", "find recompiled (pruned; whole kept unpruned)");
                return new Artifacts(Tdfa.compile(nfa, longestMatch, obs), whole);
            }
            return new Artifacts(whole, whole);
        } catch (RuntimeException overBudget) {
            if (!budgetRejection(overBudget)) throw overBudget;
            obs.note("whole", "unpruned build over budget — anchored whole attempted eagerly");
            return new Artifacts(Tdfa.compile(nfa, longestMatch, obs), null);
        }
    }

    /**
     * The whole-match engine for a resolved pair, used by every tier: the
     * find engine itself when the artifacts are shared (one engine object —
     * generated engines carry a native {@code matchWhole}), else a
     * dedicated interpreter over the whole TDFA, else — over-budget corner —
     * an interpreter over the eagerly compiled anchored TDFA, else the
     * recorded rejection. The anchored build re-parses {@code pattern} with
     * {@code anchorBoth}; its budget rejection is recorded (translated with
     * {@code patternForErrors}), any other failure rethrows.
     */
    public static RegexEngine wholeEngine(Artifacts a, RegexEngine findEngine,
                                          String pattern, String patternForErrors,
                                          boolean disableUnicodeGroups, boolean longestMatch,
                                          UnicodeDataProvider provider) {
        if (a.whole != null)
            return a.shared() ? findEngine : new TdfaRunner(a.whole);
        try {
            Tnfa an = Tnfa.compile(pattern, disableUnicodeGroups, true, provider);
            return new TdfaRunner(Tdfa.compile(an, longestMatch, null, WHOLE_WORK_CAP));
        } catch (RuntimeException over) {
            if (!budgetRejection(over)) throw over;
            return new OverBudgetWholeEngine(findEngine,
                    CompiledRegex.translate(over, patternForErrors));
        }
    }

    /**
     * Whole engine for the both-builds-over-budget corner: the
     * compile-time rejection IS the whole engine's permanent answer —
     * {@code matchWhole}/{@code matches} rethrow the recorded instance on
     * every call (deterministic; zero compile at match time — the
     * no-lazy-compiles rule). Find operations delegate to the find engine:
     * compile() acceptance follows the find artifact alone.
     */
    public static final class OverBudgetWholeEngine implements RegexEngine {
        private final RegexEngine find;
        private final RuntimeException rejection;
        OverBudgetWholeEngine(RegexEngine find, RuntimeException rejection) {
            this.find = find;
            this.rejection = rejection;
        }

        @Override public boolean matches(CharSequence input) { throw rejection; }
        @Override public MatchResult matchWhole(CharSequence input) { throw rejection; }
        @Override public boolean find(CharSequence input) { return find.find(input); }
        @Override public MatchResult match(CharSequence input, int from) {
            return find.match(input, from);
        }
        @Override public int groupCount() { return find.groupCount(); }
        @Override public java.util.Map<String, Integer> namedGroups() { return find.namedGroups(); }
        @Override public int programSize() { return find.programSize(); }
    }

    /**
     * The determinization budget-rejection idiom ("pattern too large: ..."),
     * in either shape it reaches this class: the raw {@code IllegalStateException}
     * from {@code Tdfa.compile*}, or its translated {@code PatternSyntaxException}.
     */
    static boolean budgetRejection(RuntimeException ex) {
        String m = ex.getMessage();
        return m != null && m.contains("pattern too large");
    }
}
