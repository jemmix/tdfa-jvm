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
 * {@link MatchResult}, not in the engine).
 */
public interface RegexEngine {

    /** Match the entire input (anchored both ends). */
    boolean matches(CharSequence input);

    /** Whether any match exists anywhere in the input. */
    boolean find(CharSequence input);

    /**
     * Find the leftmost match starting at or after {@code from}, returning
     * its capture registers, or {@code null} if none.
     */
    MatchResult match(CharSequence input, int from);

    /** Number of capturing groups (excluding group 0). */
    int groupCount();

    /** Unmodifiable name&rarr;index map for named capturing groups. */
    Map<String, Integer> namedGroups();

    /** Cost estimate: the number of states in the compiled DFA. */
    int programSize();

    /**
     * Iterate all non-overlapping matches, advancing past each; zero-width
     * matches advance by one position. The returned iterable is lazy and
     * single-use per {@code iterator()} call; each element is an independent
     * {@link MatchResult} snapshot.
     */
    default Iterable<MatchResult> findAll(CharSequence input) {
        return () -> new Iterator<>() {
            private int from = 0;
            private MatchResult next = advance();

            private MatchResult advance() {
                if (from > input.length()) return null;
                MatchResult m = match(input, from);
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
