package io.github.jemmix.tdfa.core;

import io.github.jemmix.tdfa.tdfa.Tdfa;

/**
 * Supplies matching engines for compiled patterns: create one engine per
 * {@link Tdfa} handed to it.
 *
 * <p>Bring-your-own-engine hook. Implementations must be stateless (or
 * externally synchronized): a single factory may be asked to create engines
 * for many patterns. Per pattern the facade asks exactly ONCE — for the
 * find engine. When the compile's pike cut never bit, that same engine
 * also serves whole matching, and its {@code matchWhole}/{@code matches}
 * implementation defines the whole-match semantics (the interface default
 * {@code match(input, 0)} is whole-exact only over both-ends-anchored
 * artifacts); otherwise the facade pairs the custom find engine with its
 * own runner over the cut-free whole artifact.
 *
 * <pre>
 *   Pattern p = Pattern.compile(regex, flags, TdfaRunner::new);
 * </pre>
 */
@FunctionalInterface
public interface RegexEngineFactory {

    /**
     * Create an engine executing {@code tdfa}. Called at most a handful of
     * times per compiled pattern (once per pattern in the current facade —
     * the find engine); the returned engine must be effectively immutable
     * and thread-safe.
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
