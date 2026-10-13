package io.github.jemmix.tdfa.core.compile;

import io.github.jemmix.tdfa.core.dfa.Tdfa;
import io.github.jemmix.tdfa.core.engine.RegexEngine;

/**
 * The two-method engine SPI the compile machinery calls into: generate a
 * per-pattern engine from an artifact, and emit the facade shell around
 * the translated engines. Implemented by the bytecode backend
 * ({@code :asm}); each surface wires the implementation it wants into its
 * {@link PatternSurface} — the machinery itself never links a backend, so
 * {@code :asm → :core} stays the only module edge.
 *
 * <p>Public-but-internal: this type lives in the machinery tier
 * ({@code core.compile}), is referenced by the thin surfaces and the ASM
 * backend only, and is not shipped API.
 */
public interface EngineBackend {

    /**
     * Generate a dedicated engine for {@code artifact} (the per-pattern
     * generated-class tier), with an explicit lazy-memo budget.
     */
    RegexEngine generate(Tdfa artifact, long memoBudgetBytes);

    /**
     * Emit and instantiate the per-pattern shell described by
     * {@code spec}. Returns the generated shell instance as
     * {@link Object} — the calling surface casts it (it owns the type).
     * Throws {@link IllegalStateException} on emission problems; failures
     * propagate (they are bugs, not shapes to route around).
     */
    Object emit(ShellSpec spec);
}
