package io.github.jemmix.tdfa.core;

import io.github.jemmix.tdfa.tdfa.Budgets;
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
 *       it to EOF. Work-bounded by {@link #wholeWorkCap()}: a cut-heavy
 *       pattern's cut-free build can churn orders of magnitude past its
 *       pruned cost before the output caps trip (aws-keys: ~4G ticks vs
 *       30M) and an eager attempt at the default budget would stall
 *       compile() for seconds.</li>
 *   <li>find() shares the whole artifact whenever the pike cut provably
 *       changes nothing ({@code !whole.pikeCutMatters()}); the rare
 *       alternation shapes where it would (e.g. {@code ab|a|ac}) keep a
 *       pruned find compile beside it for leftmost-first exactness.</li>
 *   <li>Over budget: the both-ends-ANCHORED artifact is attempted eagerly
 *       (under {@link #anchoredWorkCap()}, 2&times; the first cap — the
 *       last chance before compile failure). Every accept in an anchored
 *       build is end-of-input-gated, so the pike cut never fires mid-walk
 *       and the cut-free whole walk is exact over the (pruned) artifact —
 *       validated randomized at landing (18 K anchored-vs-unpruned pairs,
 *       both longest modes, 0 diffs).</li>
 *   <li>Both whole builds over budget: by DEFAULT the rejection fails
 *       {@code compile()} outright (a pattern is accepted only when every
 *       artifact it ships built — the compile-time budget contract matches
 *       the find artifact's). The opt-in switch
 *       ({@code Pattern.DEFER_WHOLE_REJECTION} /
 *       {@link CompileOptions#deferWholeRejection()}) keeps the historical
 *       lenient contract: the rejection is RECORDED at compile time and
 *       rethrown by every whole call ({@link OverBudgetWholeEngine}) —
 *       deterministic, zero compile at match time, find() keeps working
 *       (patterns whose whole DFA is intrinsically huge, e.g.
 *       {@code [\s\S]{0,60}x[\s\S]{0,60}}'s counter cross-product).</li>
 * </ul>
 */
public final class SingleCompile {
    private SingleCompile() { }

    /**
     * Work budget (ticks) for the eager UNPRUNED whole attempt:
     * {@link Budgets#wholeWorkCap()} — one third of the compile CPU budget
     * ({@code tdfa.budget.compile.compute}; 166 M ticks at the default
     * 500 M). Comfortably above the worst legit in-corpus unpruned build
     * measured (datefinder's {@code (?i)(?u)} variant at ~115 M ticks —
     * tick counts are deterministic, machine-independent) while rejecting
     * cut-heavy shapes (aws-keys ~4G ticks) in well under a second, so
     * even a slow CI runner stays inside the rebar compile-latency guard's
     * budget. Read per compile; a user-lowered compute budget tightens
     * this cap with it.
     */
    public static long wholeWorkCap() { return Budgets.wholeWorkCap(); }

    /**
     * Work budget (ticks) for the eager ANCHORED last-chance attempt —
     * {@link Budgets#anchoredWorkCap()}, 2&times; {@link #wholeWorkCap()}
     * (two thirds of the compile CPU budget; 333 M ticks at the default).
     * The anchored build is what stands between a budget rejection and
     * compile() failure, and legit-but-heavy shapes land just past the
     * first cap (fuzz round 18's overnight quantifier shape converges at
     * 134 219 263 ticks — 0.001% over the old 2^27 constant). Doubling
     * only THIS cap admits them at zero extra wall (a shape burning N
     * ticks burns N either way; under the doubled cap it finishes instead
     * of rejecting), while genuinely non-converging churn (aws-keys'
     * anchored build rejects at any cap — its "cap+1 ticks" report is
     * meter granularity, not a knife edge) pays at most what the doomed
     * unpruned attempt already spent.
     */
    public static long anchoredWorkCap() { return Budgets.anchoredWorkCap(); }

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
     *
     * <p>The {@code ledger} is the compile's CPU ledger. The SHIPPED work
     * (front-end, a succeeded whole, the pruned find, the anchored
     * re-parse + determinize) all debit it, so one {@code
     * Pattern.compile}'s shipped work stays within the single {@code
     * tdfa.budget.compile.compute} budget — previously each eager attempt
     * carried its own full/fractional budget and a compile could burn up
     * to 2&times; the user's. The unpruned whole attempt is a PROBE: it
     * runs on its own fraction-capped meter and is charged only on
     * success — a rejected probe's bounded churn (at most {@link
     * #wholeWorkCap()} ticks) is the documented price of trying
     * (adversarial review 2026-09).
     */
    public static Artifacts artifacts(Tnfa nfa, boolean longestMatch, CompileObserver obs,
                                      io.github.jemmix.tdfa.tdfa.WorkMeter ledger) {
        try {
            Tdfa whole = Tdfa.compileUnpruned(nfa, longestMatch, obs, wholeWorkCap());
            ledger.charge(whole.compileWorkTicks());
            if (whole.pikeCutMatters()) {
                obs.note("pikeCut", "find recompiled (pruned; whole kept unpruned)");
                return new Artifacts(Tdfa.compile(nfa, longestMatch, obs, ledger.fork(0)), whole);
            }
            return new Artifacts(whole, whole);
        } catch (RuntimeException overBudget) {
            if (!budgetRejection(overBudget)) throw overBudget;
            obs.note("whole", "unpruned build over budget — anchored whole attempted eagerly");
            return new Artifacts(Tdfa.compile(nfa, longestMatch, obs, ledger.fork(0)), null);
        }
    }

    /**
     * The whole-match engine for a resolved pair, used by every tier: the
     * find engine itself when the artifacts are shared (one engine object —
     * generated engines carry a native {@code matchWhole}), else a
     * dedicated interpreter over the whole TDFA, else — over-budget corner —
     * an interpreter over the eagerly compiled anchored TDFA. The anchored
     * build re-parses {@code pattern} with {@code anchorBoth}; on its budget
     * rejection the default FAILS the compile (the raw rejection rethrows,
     * for the caller to translate like any other failure), while
     * {@code deferRejection} records it (translated with
     * {@code patternForErrors}) for {@link OverBudgetWholeEngine}. Any other
     * failure always rethrows.
     *
     * <p>Every whole/anchored runner this method constructs is a SECOND
     * engine beside the pattern's find engine, so its lazy match-time memos
     * are capped at HALF the runtime RAM budget (per-pattern split — the
     * budget is per pattern, not per engine; adversarial review 2026-09).
     * The anchored parse and determinize fork the same CPU ledger as the
     * rest of the ladder.
     */
    public static RegexEngine wholeEngine(Artifacts a, RegexEngine findEngine,
                                          String pattern, String patternForErrors,
                                          boolean disableUnicodeGroups, boolean longestMatch,
                                          boolean deferRejection,
                                          UnicodeDataProvider provider,
                                          io.github.jemmix.tdfa.tdfa.WorkMeter ledger) {
        if (a.whole != null)
            return a.shared() ? findEngine : new TdfaRunner(a.whole, Budgets.runtimeMemoryBytes() / 2);
        try {
            Tnfa an = Tnfa.compile(pattern, disableUnicodeGroups, true, provider, null,
                    ledger.fork(0));
            return new TdfaRunner(Tdfa.compile(an, longestMatch, null,
                            ledger.fork(Budgets.anchoredWorkCap())),
                    Budgets.runtimeMemoryBytes() / 2);
        } catch (RuntimeException over) {
            if (!budgetRejection(over)) throw over;
            if (!deferRejection) throw over;
            return new OverBudgetWholeEngine(findEngine,
                    CompiledRegex.translate(over, patternForErrors));
        }
    }

    /**
     * Whole engine for the both-builds-over-budget corner under the opt-in
     * defer policy ({@link CompileOptions#deferWholeRejection()} /
     * {@code Pattern.DEFER_WHOLE_REJECTION}): the compile-time rejection IS
     * the whole engine's permanent answer — {@code matchWhole}/{@code matches}
     * rethrow the recorded instance on every call (deterministic; zero
     * compile at match time — the no-lazy-compiles rule). Find operations
     * delegate to the find engine: compile() acceptance follows the find
     * artifact alone.
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
