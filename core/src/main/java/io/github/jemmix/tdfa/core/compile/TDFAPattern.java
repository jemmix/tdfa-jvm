package io.github.jemmix.tdfa.core.compile;

import io.github.jemmix.tdfa.core.emit.EmittedSurface;
import io.github.jemmix.tdfa.core.engine.RegexEngine;
import io.github.jemmix.tdfa.core.engine.WholeEngine;
import io.github.jemmix.tdfa.core.unicode.UnicodeDataProvider;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Shared {@code Pattern} machinery implementation: holds the compiled
 * engines and the pattern/flags state of every surface's patterns. Used
 * directly on the interpreter path (wrapped by each surface's
 * not-generated carrier) and as the emission-failure fallback base;
 * generated per-pattern shells ({@code GenNNNPattern}) extend it,
 * implement their surface's {@code Pattern} interface, and declare a
 * public final engine field of the concrete (or {@link RegexEngine})
 * type that their matchers call directly.
 *
 * <p>Public because generated shells are defined in a child classloader
 * (a different runtime package) and must subclass and call into it.
 *
 * <p>Public-but-internal: machinery tier ({@code core.compile}) — the
 * surfaces' {@code Pattern} interfaces are the shipped API, this class
 * is their shared brain and is not itself shipped API.
 *
 * <p><b>Thread safety:</b> instances are immutable after construction
 * and safe for concurrent use from multiple threads. The
 * {@link PatternMatcher matchers} they produce are NOT thread-safe —
 * one matcher per thread.
 */
public class TDFAPattern implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String pattern;
    private final int flags;
    private final int programSize;
    private transient RegexEngine engine;
    // The whole-match engine for matches(), compiled EAGERLY alongside the
    // main engine: the SAME engine whenever the artifact is whole-exact —
    // hazard-free compiles (the pike cut never fired) and, for the
    // divergence class ((a|ab) on "ab" — leftmost-first prunes the `ab`
    // continuation), the find artifact carrying its partial-whole side
    // table (the uncut continuations recorded beside the pruned
    // transitions — one artifact, one engine; a side over budget fails
    // the compile). matchWhole walks to EOF; an accept config alive at
    // end-of-input is a full match. FIND_ONLY compiles carry the refusing
    // non-engine instead (no whole machinery was attempted).
    private transient WholeEngine wholeEngine;
    /**
     * The Unicode tables this pattern was compiled against ({@code null} =
     * the process default). Retained only for serialization round-trips —
     * recompiles inside this process go through the engines. Transient:
     * providers need not be Serializable; the proxy carries the class name
     * and resolves per the UnicodeDataProvider serialization convention.
     */
    private transient UnicodeDataProvider provider;
    /** The surface this pattern compiled through — the lane identity
     *  (serialization target, equality axis, shell interface). */
    private final PatternSurface surface;

    @EmittedSurface // shells super-ctor call: signature feeds ShellEmitter descriptor
    public TDFAPattern(String pattern, int flags, int programSize, RegexEngine engine, WholeEngine wholeEngine,
        UnicodeDataProvider provider, PatternSurface surface) {
        this.pattern = pattern;
        this.flags = flags;
        this.programSize = programSize;
        this.engine = engine;
        this.wholeEngine = wholeEngine;
        this.provider = provider;
        this.surface = surface;
    }

    /**
     * The main (unanchored) engine.
     */
    public RegexEngine engine() {
        return engine;
    }

    /**
     * The pinned Unicode tables this pattern compiles against ({@code null} = process default).
     */
    public UnicodeDataProvider unicodeProvider() {
        return provider;
    }

    /**
     * The surface this pattern compiled through ({@code PatternSurface}
     * machinery identity: lane, roster, recompile target).
     */
    public PatternSurface surface() {
        return surface;
    }

    /**
     * Engine for {@code matches()}: whole-exact over the one artifact
     * (its side table when the pike cut bit), compiled eagerly — or, on
     * {@code FIND_ONLY} compiles, the refusing non-engine whose
     * {@code matchWhole} throws {@link UnsupportedOperationException}
     * (nothing whole was built; see {@link FindOnlyWholeEngine}).
     */
    @EmittedSurface
    public WholeEngine wholeEngine() {
        return wholeEngine;
    }

    public PatternMatcher matcher(CharSequence input) {
        return new PatternMatcher(this, input);
    }

    public PatternMatcher matcher(byte[] input) {
        return new PatternMatcher(this, decodeUtf8(input));
    }

    /**
     * UTF-8 decode shared by the {@code byte[]} overloads (re2j's
     * {@code MatcherInput.utf8}) — linked by name from the emitted
     * shells' {@code matcher(byte[])} overrides.
     */
    @EmittedSurface
    public static String decodeUtf8(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public boolean matches(String input) {
        return matcher(input).matches();
    }

    public boolean matches(byte[] input) {
        return matches(decodeUtf8(input));
    }

    public String[] split(String input) {
        return split(input, 0);
    }

    public String[] split(String input, int limit) {
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
            if (limit > 0 && result.size() == limit - 1) {
                break;
            }
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

    public void reset() {
    }

    /**
     * Serialize as the {@link SerialProxy} — surface + pattern + flags +
     * provider identity, recompiled on read. Generated subclasses'
     * classes live in child loaders that won't exist in the reading
     * process, so the state proxy is the only stable form. The pinned
     * provider (if any) round-trips as its class name, resolved per the
     * UnicodeDataProvider serialization convention; a pattern compiled
     * against the process default serializes without a provider and
     * recompiles against the READER's default.
     */
    // PUBLIC, not private: Java serialization only inherits writeReplace
    // across package boundaries when it is non-private — generated shells
    // live in io.github.jemmix.tdfa.gen (child classloader) and rely on
    // inheriting this exact method. With a private writeReplace the shells'
    // raw fields (including the non-serializable engine) hit the wire and
    // serialization throws NotSerializableException.
    public Object writeReplace() {
        return new SerialProxy(surface.id(), surface.triggerClass(), pattern, flags,
            provider == null ? null : provider.getClass().getName());
    }

    /**
     * Recompile the (transient) engines after deserialization, from
     * {@code pattern}+{@code flags} through this pattern's surface.
     */
    private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
        in.defaultReadObject();
        TDFAPattern tmp = surface.recompile(pattern, flags, provider);
        this.engine = tmp.engine;
        this.wholeEngine = tmp.wholeEngine;
        this.provider = tmp.provider;
    }

    public int programSize() {
        return programSize;
    }

    public String pattern() {
        return pattern;
    }

    public int flags() {
        return flags;
    }

    public int groupCount() {
        return engine.groupCount();
    }

    public Map<String, Integer> namedGroups() {
        return engine.namedGroups();
    }

    @Override
    public String toString() {
        return pattern;
    }

    @Override
    public boolean equals(Object o) {
        // State-based equality across implementations (shared, wrapped and
        // generated): re2j semantics — same pattern string + same flags —
        // AND the same surface: a jur-surface and a re2j-surface compile
        // of the same regex+flags answer in different lanes and are never
        // equal.
        if (this == o) {
            return true;
        }
        if (!(o instanceof TDFAPattern)) {
            return false;
        }
        TDFAPattern p = (TDFAPattern) o;
        return surface == p.surface && flags == p.flags && pattern.equals(p.pattern);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * pattern.hashCode() + flags) + surface.id().hashCode();
    }
}
