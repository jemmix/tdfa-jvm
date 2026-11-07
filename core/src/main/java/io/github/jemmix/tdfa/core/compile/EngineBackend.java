package io.github.jemmix.tdfa.core.compile;

import io.github.jemmix.tdfa.core.dfa.Tdfa;
import io.github.jemmix.tdfa.core.engine.RegexEngine;

/**
 * The two-method engine SPI the compile machinery calls into: generate a
 * per-pattern engine from an artifact, and emit the core shell around
 * the translated engines (a {@link TDFAPattern} subclass carrying the
 * concrete engine field — no API interface, that wrap is the calling
 * facade's own business). Implemented by the bytecode backend
 * ({@code :asm}); each facade passes the implementation it ships into
 * {@code PatternCompiler.compile} — the machinery itself never links a
 * backend, so {@code :asm → :core} stays the only module edge.
 *
 * <p>Public-but-internal: this type lives in the machinery tier
 * ({@code core.compile}), is referenced by the thin facades and the ASM
 * backend only, and is not shipped API.
 */
public interface EngineBackend {

    /**
     * Generate a dedicated engine for {@code artifact} (the per-pattern
     * generated-class tier).
     */
    RegexEngine generate(Tdfa artifact);

    /**
     * Emit and instantiate the per-pattern core shell described by
     * {@code spec}: a {@link TDFAPattern} whose class carries the
     * concrete engine field. Throws {@link IllegalStateException} on
     * emission problems; failures propagate (they are bugs, not shapes
     * to route around).
     */
    TDFAPattern emit(ShellSpec spec);
}
