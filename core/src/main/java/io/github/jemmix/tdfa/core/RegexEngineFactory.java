package io.github.jemmix.tdfa.core;

import io.github.jemmix.tdfa.tdfa.Tdfa;

/**
 * Supplies matching engines for compiled patterns: create one engine per
 * {@link Tdfa} handed to it.
 *
 * <p>Bring-your-own-engine hook. Implementations must be stateless (or
 * externally synchronized): a single factory may be asked to create engines
 * for many patterns. Per pattern the facade calls it once per compiled
 * artifact — for the find TDFA, and (when the compile's pike cut deleted
 * continuations) again for the cut-free whole TDFA. Whole-input matching is
 * never a factory engine's obligation: the facade dispatches it through
 * {@link WholeEngine}, using the returned engine when it natively
 * implements that seam and a plain {@code TdfaRunner} over the same
 * artifact otherwise.
 *
 * <pre>
 *   Pattern p = Pattern.compile(regex, flags, TdfaRunner::new);
 * </pre>
 */
@FunctionalInterface
public interface RegexEngineFactory {

    /**
     * Create an engine executing {@code tdfa}. Called once per compiled
     * artifact (find, and — when the pike cut bit — whole); the returned
     * engine must be effectively immutable and thread-safe.
     *
     * <p>Representation note: {@link Tdfa}'s array accessors return defensive
     * copies — an engine built through them cannot be corrupted by a later
     * caller, and the flat-array layout is an implementation detail that may
     * change without this interface changing. In-package engines
     * ({@code TdfaRunner}, the ASM tier) read the artifact's fields directly
     * and share its (immutable) arrays.
     */
    RegexEngine create(Tdfa tdfa);
}
