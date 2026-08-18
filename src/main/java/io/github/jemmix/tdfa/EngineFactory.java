package io.github.jemmix.tdfa;

import io.github.jemmix.tdfa.asm.TdfaAsmBackend;
import io.github.jemmix.tdfa.core.RegexEngine;
import io.github.jemmix.tdfa.tdfa.Tdfa;
import io.github.jemmix.tdfa.tdfa.TdfaRunner;

/**
 * Strategy for selecting and instantiating a matching backend from a compiled
 * {@link Tdfa}. Built-in singletons {@link #ASM} and {@link #VM} cover the two
 * shipped engines; callers may supply their own lambda or class to inject a
 * custom backend (tracing, debugging, experimental).
 *
 * <p>The default used when no factory is specified is {@link #DEFAULT}, resolved
 * once at class-initialisation time from the {@code tdfa.engine} system property
 * (values: {@code ASM}, {@code VM}). This avoids per-compile property lookups on
 * the hot path.
 *
 * <pre>
 *   Pattern p = Pattern.compile("a+", 0, EngineFactory.VM);
 *   Pattern p = Pattern.compile("a+", 0, tdfa -> new MyTracer(tdfa));
 * </pre>
 */
@FunctionalInterface
public interface EngineFactory {

    RegexEngine create(Tdfa tdfa);

    /**
     * Whether this factory generates dedicated per-pattern classes. When true,
     * API layers above (e.g. the re2j-compat {@code Pattern.compile}) may
     * generate Pattern/Matcher/Regex implementations that devirtualize and
     * inline end-to-end; when false (interpreter backends, custom tracing
     * engines), shared implementations are used — generating classes would
     * buy no dispatch and only cost classload/metaspace/warmup.
     */
    default boolean generatesPerPattern() { return false; }

    /** ASM bytecode backend: generates a dedicated hidden class per pattern. Fastest at match time. */
    EngineFactory ASM = io.github.jemmix.tdfa.asm.AsmEngineFactory.INSTANCE;

    /** Interpreted VM backend: walks the TDFA tables directly. No code generation. */
    EngineFactory VM = TdfaRunner::new;

    /**
     * The default factory, resolved once at class init from {@code -Dtdfa.engine=ASM|VM}.
     * Falls back to {@link #ASM} when the property is unset or unrecognised.
     */
    EngineFactory DEFAULT = resolveDefault();

    private static EngineFactory resolveDefault() {
        String prop = System.getProperty("tdfa.engine");
        if (prop != null) {
            return switch (prop.toUpperCase()) {
                case "VM"  -> VM;
                case "ASM" -> ASM;
                default    -> ASM;
            };
        }
        return ASM;
    }
}
