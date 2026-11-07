package io.github.jemmix.tdfa.core.compile;

import io.github.jemmix.tdfa.core.engine.RegexEngine;
import io.github.jemmix.tdfa.core.engine.WholeEngine;
import io.github.jemmix.tdfa.core.tnfa.Semantics;
import io.github.jemmix.tdfa.core.unicode.UnicodeDataProvider;

/**
 * Shell emission spec, carried from the compile machinery to the
 * {@link EngineBackend#emit} implementor. All core types — the emitted
 * shell is machinery-only (it extends {@code TDFAPattern} and implements
 * no API interface); the facade that triggered the compile wraps the
 * result into its own API type afterwards, on its own.
 *
 * <p>Components: {@code pattern} is the regex source text (recompiled by
 * the serialization proxy); {@code flags} the facade's flag bits;
 * {@code programSize} the states in the compiled DFA (cost estimate);
 * {@code engine} the per-pattern engine instance; {@code wholeEngine}
 * the whole-match engine for matches() (the engine itself when the
 * artifacts are shared, else a runner over the cut-free artifact);
 * {@code engineInternalName} the slashed internal name of a per-pattern
 * generated engine class for concrete-type wiring, or {@code null} to
 * type the shell's engine field as {@link RegexEngine};
 * {@code provider} the pinned Unicode tables ({@code null} = process
 * default); {@code semantics} the compile's resolved lane (rides the
 * shell's constructor into the base, where it is the equality axis and
 * serialization payload).
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

    private final Semantics semantics;

    ShellSpec(String pattern, int flags, int programSize, RegexEngine engine, WholeEngine wholeEngine,
        String engineInternalName, UnicodeDataProvider provider, Semantics semantics) {
        this.pattern = pattern;
        this.flags = flags;
        this.programSize = programSize;
        this.engine = engine;
        this.wholeEngine = wholeEngine;
        this.engineInternalName = engineInternalName;
        this.provider = provider;
        this.semantics = semantics;
    }

    /**
     * A spec for a compile's shell, deriving the engine wiring from how
     * the engine was sourced: a per-pattern generated engine
     * ({@code factory == null}) is wired by its concrete class — its
     * internal name is the {@code engineInternalName}; a bring-your-own
     * factory engine is wired as {@link RegexEngine} ({@code null} name),
     * because a factory may hand back an anonymous or lambda class no
     * emitted bytecode can name. The one place that decision lives.
     */
    public static ShellSpec of(String pattern, int flags, int programSize, RegexEngine engine, WholeEngine wholeEngine,
        RegexEngineFactory factory, UnicodeDataProvider provider, Semantics semantics) {
        String engineInternalName = factory == null ? engine.getClass().getName().replace('.', '/') : null;
        return new ShellSpec(pattern, flags, programSize, engine, wholeEngine, engineInternalName, provider, semantics);
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

    /** The compile's resolved lane (rides the constructor into the base class). */
    public Semantics semantics() {
        return semantics;
    }
}
