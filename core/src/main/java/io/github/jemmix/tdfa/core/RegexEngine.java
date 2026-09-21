package io.github.jemmix.tdfa.core;

import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * A compiled, immutable matching engine: compile once, match many.
 *
 * <p>This is the core-tier interface of the TDFA pipeline — a tagged DFA
 * (Borsotti–Trofimovich 2022 TDFA(1), paper Algorithm 3) fronted by an
 * execution strategy. The reference implementation is the table interpreter
 * ({@code TdfaRunner}); the ASM module generates a dedicated class per
 * pattern implementing this interface. Third parties may supply their own
 * implementations via {@link RegexEngineFactory}.
 *
 * <p>Implementations must be effectively immutable and safe for concurrent
 * use from multiple threads (per-match state lives in the returned
 * {@link MatchResult} and the caller's {@link MatchScratch} carrier, not in
 * the engine).
 */
public interface RegexEngine {

    /**
     * Match the entire input (anchored both ends).
     *
     * <p><b>Artifact contract.</b> Exact over artifacts whose transitions
     * keep whole-match continuations: both-ends-anchored artifacts (what the
     * facade hands out on the over-budget corner), cut-free (unpruned)
     * artifacts, and any pruned artifact whose compile-time pike cut never
     * fired. Over a pruned UNANCHORED artifact where the cut deleted
     * continuations (e.g. find compiles of {@code (a|ab)}-class patterns),
     * this may reject an input the pattern whole-matches — the boolean walk
     * shares {@link #matchWhole}'s artifact requirement; use the facade's
     * {@code Pattern.matcher().matches()}, which always carries a
     * whole-exact engine.
     */
    boolean matches(CharSequence input);

    /** Whether any match exists anywhere in the input. */
    boolean find(CharSequence input);

    /**
     * Find the leftmost match starting at or after {@code from}, returning
     * its capture registers, or {@code null} if none.
     *
     * <p>Contract: {@code from} must lie in {@code [0, input.length()]} and
     * {@code input} must be non-null — otherwise implementations throw
     * {@link IndexOutOfBoundsException} / {@link NullPointerException}
     * respectively. The interpreter enforces the bounds check; the facade's
     * matchers validate before dispatching, and the code-generated tier
     * inherits the guarantee through them (calling a generated engine's
     * {@code match} directly with an out-of-range {@code from} surfaces the
     * walk's own {@code IndexOutOfBoundsException} — same class, less
     * polite message). The input must not be mutated during the call.
     *
     * <p>The caller's {@link MatchScratch} holds the reusable per-match
     * buffers (register file, simulation sets), so a {@link Matcher}
     * iterating {@code find()} over one input pools them across calls
     * instead of allocating per call. {@code null} is allowed — engines
     * allocate a carrier on demand at the (cold) points that consume one,
     * so carrier-free engines ({@link #wantsScratch()} {@code false}) pay
     * nothing on their hot paths. A non-null carrier is never retained
     * beyond the call, and is a pure reuse hint — never semantic.
     */
    MatchResult match(CharSequence input, int from, MatchScratch scratch);

    /**
     * Match the ENTIRE input, returning capture registers, or {@code null} if
     * the input is not a whole match. Unlike {@link #match(CharSequence, int, MatchScratch)}
     * a mid-input accept never satisfies this — the walk runs to end-of-input
     * and only an accept alive exactly at EOF counts (so {@code (a|ab)} whole-
     * matches {@code "ab"} even though leftmost-first find stops after
     * {@code "a"}).
     *
     * <p>The default {@code match(input, 0, scratch)} is whole-exact only for
     * engines compiled anchored at both ends (what the facade hands custom
     * {@code RegexEngineFactory}s for whole matching). Engines over unanchored
     * or cut-free artifacts must override — {@code TdfaRunner} and the
     * generated classes do (a single cut-free walk; see {@code Tdfa.compileUnpruned}).
     * The overridden walk is exact over unpruned and anchored artifacts
     * alike: an anchored build's accepts are all end-of-input-gated, so its
     * compile-time pike cut never fires mid-walk (same artifact contract as
     * {@link #matches}).
     */
    default MatchResult matchWhole(CharSequence input, MatchScratch scratch) {
        return match(input, 0, scratch);
    }

    /** Number of capturing groups (excluding group 0). */
    int groupCount();

    /** Unmodifiable name&rarr;index map for named capturing groups. */
    Map<String, Integer> namedGroups();

    /** Cost estimate: the number of states in the compiled DFA. */
    int programSize();

    /**
     * Whether this engine's hot paths consume the caller's {@link MatchScratch}
     * carrier. Used by {@link Matcher} to skip carrier allocation entirely for
     * engines that don't want one (the ASM tier's stack-register and
     * zero-register leaves borrow nothing on their hot paths — only cold
     * fallback strategies need a carrier, and those allocate on demand).
     * A pure hint for allocation: the carrier is never semantic either way.
     */
    default boolean wantsScratch() {
        return true;
    }

    /**
     * Iterate all non-overlapping matches, advancing past each; zero-width
     * matches advance by one position. The returned iterable is lazy and
     * single-use per {@code iterator()} call; each element is an independent
     * {@link MatchResult} snapshot.
     */
    default Iterable<MatchResult> findAll(CharSequence input) {
        return () -> new Iterator<MatchResult>() {
            private int from = 0;
            private MatchResult next = advance();

            private MatchResult advance() {
                if (from > input.length()) return null;
                MatchResult m = match(input, from, null);
                if (m == null) return null;
                from = (m.end(0) == m.start(0)) ? m.end(0) + 1 : m.end(0);
                return m;
            }

            @Override public boolean hasNext() { return next != null; }

            @Override public MatchResult next() {
                MatchResult r = next;
                if (r == null) throw new NoSuchElementException();
                next = advance();
                return r;
            }
        };
    }
}
