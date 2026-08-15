package io.github.jemmix.tdfa.asm;

import io.github.jemmix.tdfa.EngineFactory;
import io.github.jemmix.tdfa.Regex;
import io.github.jemmix.tdfa.tdfa.Tdfa;

/**
 * Named {@link EngineFactory} for the ASM bytecode backend.
 *
 * <p>A named class (not a lambda) so upper API layers can recognize it and
 * consult {@link #generatesPerPattern()} — under this factory,
 * {@code io.github.jemmix.tdfa.re2j.Pattern.compile} generates per-pattern
 * Pattern/Matcher classes whose call chains devirtualize and inline
 * end-to-end (see the kernel refactor notes in the backend).
 */
public final class AsmEngineFactory implements EngineFactory {

    public static final AsmEngineFactory INSTANCE = new AsmEngineFactory();

    private AsmEngineFactory() { }

    @Override public Regex.Engine create(Tdfa tdfa) {
        return TdfaAsmBackend.compile(tdfa);
    }

    @Override public boolean generatesPerPattern() {
        return true;
    }
}
