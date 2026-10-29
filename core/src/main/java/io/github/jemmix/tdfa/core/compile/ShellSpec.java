package io.github.jemmix.tdfa.core.compile;

import io.github.jemmix.tdfa.core.engine.RegexEngine;
import io.github.jemmix.tdfa.core.engine.WholeEngine;
import io.github.jemmix.tdfa.core.unicode.UnicodeDataProvider;

/**
 * Shell emission spec, carried from the compile machinery to the
 * {@link EngineBackend#emit} implementor. All core/reflection
 * types — the backend resolves the surface's {@link PatternSurface
 * pattern interface} by descriptor at class-load time.
 *
 * <p>Components: {@code pattern} is the regex source text (recompiled by
 * the serialization proxy); {@code flags} the surface's flag bits;
 * {@code programSize} the states in the compiled DFA (cost estimate);
 * {@code engine} the per-pattern engine instance; {@code wholeEngine}
 * the whole-match engine for matches() (the engine itself when the
 * artifacts are shared, else a runner over the cut-free artifact);
 * {@code engineInternalName} the slashed internal name of a per-pattern
 * generated engine class for concrete-type wiring, or {@code null} to
 * type the shell's engine field as {@link RegexEngine};
 * {@code provider} the pinned Unicode tables ({@code null} = process
 * default); {@code surface} the compile's surface (names the shell's
 * implemented {@code Pattern} interface and rides the constructor into
 * the machinery base class).
 *
 * <p>Public-but-internal machinery vocabulary — not shipped API.
 */
// Java 8 floor: records are 16+; plain carrier class with record-shaped accessors.
public final class ShellSpec {

    private final String pattern;
    private final int flags;
    private final int programSize;
    private final RegexEngine engine;
    private final WholeEngine wholeEngine;
    private final String engineInternalName;
    private final UnicodeDataProvider provider;
    private final PatternSurface surface;

    public ShellSpec(String pattern, int flags, int programSize, RegexEngine engine, WholeEngine wholeEngine,
        String engineInternalName, UnicodeDataProvider provider, PatternSurface surface) {
        this.pattern = pattern;
        this.flags = flags;
        this.programSize = programSize;
        this.engine = engine;
        this.wholeEngine = wholeEngine;
        this.engineInternalName = engineInternalName;
        this.provider = provider;
        this.surface = surface;
    }

    public String pattern() {
        return pattern;
    }

    public int flags() {
        return flags;
    }

    public int programSize() {
        return programSize;
    }

    public RegexEngine engine() {
        return engine;
    }

    public WholeEngine wholeEngine() {
        return wholeEngine;
    }

    public String engineInternalName() {
        return engineInternalName;
    }

    public UnicodeDataProvider provider() {
        return provider;
    }

    public PatternSurface surface() {
        return surface;
    }
}
