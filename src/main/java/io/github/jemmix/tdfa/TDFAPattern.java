package io.github.jemmix.tdfa;

import io.github.jemmix.tdfa.core.RegexEngine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Shared {@link Pattern} implementation: holds the compiled engines and the
 * pattern/flags state. Used directly on the interpreter path and as the
 * emission-failure fallback; generated per-pattern shells
 * ({@code GenNNNPattern}) extend it, declaring a public final engine field
 * of the concrete (or {@link RegexEngine}) type that their matchers call
 * directly.
 *
 * <p>Public because generated shells are defined in a child classloader
 * (a different runtime package) and must subclass and call into it.
 */
public class TDFAPattern implements Pattern {

    private static final long serialVersionUID = 1L;

    private final String pattern;
    private final int flags;
    private final int programSize;
    private transient RegexEngine engine;
    // A second engine for matches() (anchored both ends), compiled lazily.
    // anchorBoth injects start/end anchors at the AST level (not text — safe
    // against \Q..\E), and the trailing anchor supplies context that prevents
    // the leftmost-first DFA from pruning a longer alternative's continuation
    // once a shorter branch reaches accept (e.g. (a|ab) against "ab" must
    // retain the `ab` path). Same parse input and a subset of the
    // determinization work, so if the eager engine compiles, this one cannot
    // fail — deferring it can't move a compile error past Pattern.compile().
    private transient volatile RegexEngine wholeEngine;
    private transient Supplier<RegexEngine> wholeSupplier;

    public TDFAPattern(String pattern, int flags, int programSize,
                       RegexEngine engine, Supplier<RegexEngine> wholeSupplier) {
        this.pattern = pattern;
        this.flags = flags;
        this.programSize = programSize;
        this.engine = engine;
        this.wholeSupplier = wholeSupplier;
    }

    /** The main (unanchored) engine. */
    public RegexEngine engine() { return engine; }

    /** Engine for {@code matches()}: anchored both ends, compiled lazily on first use. */
    public RegexEngine wholeEngine() {
        RegexEngine w = wholeEngine;
        if (w == null) {
            w = wholeSupplier.get();
            wholeEngine = w;
        }
        return w;
    }

    @Override public PatternMatcher matcher(CharSequence input) {
        return new PatternMatcher(this, input);
    }

    @Override public PatternMatcher matcher(byte[] input) {
        return new PatternMatcher(this, Pattern.Utf8.decode(input));
    }

    @Override public boolean matches(String input) {
        return matcher(input).matches();
    }

    @Override public boolean matches(byte[] input) {
        return matches(Pattern.Utf8.decode(input));
    }

    @Override public String[] split(String input) {
        return split(input, 0);
    }

    @Override public String[] split(String input, int limit) {
        PatternMatcher m = matcher(input);
        List<String> result = new ArrayList<>();
        int emptiesSkipped = 0;
        int last = 0;

        while (m.find()) {
            if (last == 0 && m.end() == 0) {
                // Zero-width match at the beginning, skip (JDK8+ behavior).
                last = m.end();
                continue;
            }
            if (limit > 0 && result.size() == limit - 1) break;
            if (last == m.start()) {
                if (limit == 0) {
                    // Empty match, may or may not be trailing.
                    emptiesSkipped++;
                    last = m.end();
                    continue;
                }
            } else {
                // If emptiesSkipped > 0 then limit == 0 and we have non-trailing empty
                // matches to add before this non-empty match.
                while (emptiesSkipped > 0) {
                    result.add("");
                    emptiesSkipped--;
                }
            }
            result.add(input.substring(last, m.start()));
            last = m.end();
        }
        if (limit == 0 && last != input.length()) {
            while (emptiesSkipped > 0) {
                result.add("");
                emptiesSkipped--;
            }
            result.add(input.substring(last));
        }
        if (limit != 0 || result.isEmpty()) {
            result.add(input.substring(last));
        }
        return result.toArray(new String[0]);
    }

    @Override public void reset() { }

    /**
     * Serialize as the {@link SerialProxy} (pattern+flags; recompiles on read) —
     * generated subclasses' classes live in child loaders that won't exist in
     * the reading process, so the state proxy is the only stable form.
     */
    private Object writeReplace() {
        return new SerialProxy(pattern, flags);
    }

    /** Recompile the (transient) engines after deserialization, from {@code pattern}+{@code flags}. */
    private void readObject(java.io.ObjectInputStream in)
            throws java.io.IOException, ClassNotFoundException {
        in.defaultReadObject();
        TDFAPattern tmp = (TDFAPattern) Pattern.compile(pattern, flags);
        this.engine = tmp.engine;
        this.wholeEngine = null;
        this.wholeSupplier = tmp.wholeSupplier;
    }

    @Override public int programSize() { return programSize; }

    @Override public String pattern() { return pattern; }

    @Override public int flags() { return flags; }

    @Override public int groupCount() { return engine.groupCount(); }

    @Override public Map<String, Integer> namedGroups() { return engine.namedGroups(); }

    @Override public String toString() { return pattern; }

    @Override public boolean equals(Object o) {
        // State-based equality across implementations (shared and generated):
        // re2j semantics — same pattern string + same flags.
        if (this == o) return true;
        if (!(o instanceof Pattern p)) return false;
        return flags == p.flags() && pattern.equals(p.pattern());
    }

    @Override public int hashCode() {
        return 31 * pattern.hashCode() + flags;
    }
}
