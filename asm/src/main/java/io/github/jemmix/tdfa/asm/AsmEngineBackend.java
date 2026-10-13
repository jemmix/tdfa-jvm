package io.github.jemmix.tdfa.asm;

import io.github.jemmix.tdfa.core.compile.EngineBackend;
import io.github.jemmix.tdfa.core.compile.ShellSpec;
import io.github.jemmix.tdfa.core.dfa.Tdfa;
import io.github.jemmix.tdfa.core.engine.RegexEngine;

/**
 * The {@link EngineBackend} SPI implemented by this module: per-pattern
 * engine generation ({@link TdfaAsmBackend}) and facade-shell emission
 * ({@link ShellEmitter}). Each surface wires {@link #INSTANCE} into its
 * {@code PatternSurface} — the one line where a surface touches the
 * bytecode backend; the compile machinery itself never does.
 */
public final class AsmEngineBackend implements EngineBackend {

    public static final AsmEngineBackend INSTANCE = new AsmEngineBackend();

    private AsmEngineBackend() {
    }

    @Override
    public RegexEngine generate(Tdfa artifact, long memoBudgetBytes) {
        return TdfaAsmBackend.generate(artifact, memoBudgetBytes);
    }

    @Override
    public Object emit(ShellSpec spec) {
        return ShellEmitter.emit(spec);
    }
}
