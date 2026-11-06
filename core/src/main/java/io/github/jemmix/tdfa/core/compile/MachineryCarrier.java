package io.github.jemmix.tdfa.core.compile;

/**
 * Implemented by the facade tier's generated wrappers: the machinery
 * pattern inside a facade {@code Pattern}. The wrappers composition-wrap
 * a {@link TDFAPattern} (they are not subclasses of it), so
 * machinery-adjacent code — tests, harnesses, tooling — reaches the
 * compiled core pattern through this view instead of reflection or
 * casts. One direction of knowledge: this interface lives in the
 * machinery and the wrappers implement it because the wrapper emitter
 * is machinery-tier; the facade APIs do not expose it.
 *
 * <p>Public-but-internal: machinery tier ({@code core.compile}) — not
 * shipped API.
 */
public interface MachineryCarrier {

    /** The machinery pattern this wrapper carries. */
    TDFAPattern machinery();
}
