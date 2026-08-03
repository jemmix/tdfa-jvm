package io.github.jemmix.tdfa;

import io.github.jemmix.tdfa.tdfa.Disambiguation;
import io.github.jemmix.tdfa.tdfa.TdfaRunner;
import io.github.jemmix.tdfa.tnfa.Tnfa;
import io.github.jemmix.tdfa.vm.MatchResult;

import java.util.Map;

/**
 * Public entry point. Compile once, match many.
 *
 *   Regex r = Regex.compile("(\\w+)@(\\w+)");
 *   if (r.find("hello world user@example.com")) {
 *       MatchResult m = r.find(input, 0);
 *   }
 *
 * Engine: Borsotti-Trofimovich 2022 TDFA(1) compiled AOT (paper Algorithm 3).
 *
 * Disambiguation: POSIX (default) yields leftmost-longest matches; PERL yields
 * leftmost-first matches (compatible with re2j/RE2/PCRE/Perl). Both run in O(n).
 */
public final class Regex {
    private final Engine engine;
    private final int groupCount;
    private final Map<String, Integer> namedGroups;

    public interface Engine {
        boolean matches(CharSequence input);
        boolean find(CharSequence input);
        MatchResult match(CharSequence input, int from);
    }

    public static Regex compile(String pattern) {
        return compile(pattern, EngineFactory.DEFAULT, Disambiguation.POSIX);
    }

    public static Regex compile(String pattern, EngineFactory factory) {
        return compile(pattern, factory, Disambiguation.POSIX);
    }

    public static Regex compile(String pattern, EngineFactory factory, Disambiguation disamb) {
        Tnfa nfa = Tnfa.compile(pattern);
        io.github.jemmix.tdfa.tdfa.Tdfa tdfa = io.github.jemmix.tdfa.tdfa.Tdfa.compile(nfa, disamb);
        Engine engine = factory.create(tdfa);
        return new Regex(engine, nfa.groupCount, nfa.namedGroups);
    }

    private Regex(Engine engine, int groupCount, Map<String, Integer> namedGroups) {
        this.engine = engine; this.groupCount = groupCount;
        this.namedGroups = namedGroups != null ? namedGroups : Map.of();
    }

    public boolean matches(CharSequence input) { return engine.matches(input); }
    public boolean find(CharSequence input) { return engine.find(input); }
    public MatchResult find(CharSequence input, int from) { return engine.match(input, from); }

    public int groupCount() { return groupCount; }
    public Map<String, Integer> namedGroups() { return namedGroups; }
}
