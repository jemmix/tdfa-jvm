package io.github.jemmix.tdfa.core;

import io.github.jemmix.tdfa.tdfa.Tdfa;

/**
 * Supplies matching engines for compiled patterns: create one engine per
 * {@link Tdfa} handed to it.
 *
 * <p>Bring-your-own-engine hook. Implementations must be stateless (or
 * externally synchronized): a single factory may be asked to create engines
 * for many patterns, and — for one pattern — up to two engines (the plain
 * engine and a second, {@code \A(?:...)\z}-anchored engine backing
 * {@code matches()}); consult the calling tier's documentation for the exact
 * contract.
 *
 * <pre>
 *   Pattern p = Pattern.compile(regex, flags, TdfaRunner::new);
 * </pre>
 */
@FunctionalInterface
public interface RegexEngineFactory {

    /**
     * Create an engine executing {@code tdfa}. Called at most a handful of
     * times per compiled pattern; the returned engine must be effectively
     * immutable and thread-safe.
     */
    RegexEngine create(Tdfa tdfa);
}
