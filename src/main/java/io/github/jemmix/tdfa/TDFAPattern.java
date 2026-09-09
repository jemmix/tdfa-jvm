package io.github.jemmix.tdfa;

import io.github.jemmix.tdfa.core.EmittedSurface;
import io.github.jemmix.tdfa.core.RegexEngine;
import io.github.jemmix.tdfa.unicode.UnicodeDataProvider;

import java.util.ArrayList;
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
 *
 * <p><b>Thread safety:</b> instances are immutable after construction
 * (except the lazily-published second engine, see {@link #wholeEngine()})
 * and safe for concurrent use from multiple threads. The {@link
 * PatternMatcher matchers} they produce are NOT thread-safe — one matcher
 * per thread.
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
    /** The Unicode tables this pattern was compiled against ({@code null} =
     *  the process default). Retained only for serialization round-trips —
     *  recompiles inside this process go through the engines. Transient:
     *  providers need not be Serializable; the proxy carries the class name
     *  and resolves per the UnicodeDataProvider serialization convention. */
    private transient UnicodeDataProvider provider;

    @EmittedSurface  // shells super-ctor call: signature feeds ShellEmitter descriptor
public TDFAPattern(String pattern, int flags, int programSize,
                       RegexEngine engine, Supplier<RegexEngine> wholeSupplier,
                       UnicodeDataProvider provider) {
        this.pattern = pattern;
        this.flags = flags;
        this.programSize = programSize;
        this.engine = engine;
        this.wholeSupplier = wholeSupplier;
        this.provider = provider;
    }

    /** The main (unanchored) engine. */
    public RegexEngine engine() { return engine; }

    /** The pinned Unicode tables this pattern compiles against ({@code null} = process default). */
    public UnicodeDataProvider unicodeProvider() { return provider; }

    /** Engine for {@code matches()}: anchored both ends, compiled lazily on first use. */
    @EmittedSurface
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
     * Serialize as the {@link SerialProxy} — pattern+flags+provider identity,
     * recompiled on read. Generated subclasses' classes live in child loaders
     * that won't exist in the reading process, so the state proxy is the only
     * stable form. The pinned provider (if any) round-trips as its class
     * name, resolved per the UnicodeDataProvider serialization convention; a
     * pattern compiled against the process default serializes without a
     * provider and recompiles against the READER's default.
     */
    // PUBLIC, not private: Java serialization only inherits writeReplace
    // across package boundaries when it is non-private — generated shells
    // live in io.github.jemmix.tdfa.gen (child classloader) and rely on
    // inheriting this exact method. With a private writeReplace the shells'
    // raw fields (including the non-serializable engine) hit the wire and
    // serialization throws NotSerializableException — a latent bug until the
    // first round-trip test (PatternSerializationTest, 2026-09).
    public Object writeReplace() {
        return new SerialProxy(pattern, flags,
                provider == null ? null : provider.getClass().getName());
    }

    /** Recompile the (transient) engines after deserialization, from {@code pattern}+{@code flags}. */
    private void readObject(java.io.ObjectInputStream in)
            throws java.io.IOException, ClassNotFoundException {
        in.defaultReadObject();
        TDFAPattern tmp = (TDFAPattern) Pattern.compile(pattern, flags, null, provider);
        this.engine = tmp.engine;
        this.wholeEngine = null;
        this.wholeSupplier = tmp.wholeSupplier;
        this.provider = tmp.provider;
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
        if (!(o instanceof Pattern)) return false;
        Pattern p = (Pattern) o;
        return flags == p.flags() && pattern.equals(p.pattern());
    }

    @Override public int hashCode() {
        return 31 * pattern.hashCode() + flags;
    }
}
