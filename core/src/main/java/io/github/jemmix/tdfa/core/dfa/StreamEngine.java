package io.github.jemmix.tdfa.core.dfa;

import io.github.jemmix.tdfa.core.engine.ChunkSource;

/**
 * The streaming-input seam: an engine that matches over chunked input
 * without materializing it (see {@code docs/streaming-input.md} in the
 * repository root). {@link TdfaRunner} implements it directly; the
 * ASM-generated classes implement it by delegating to their embedded
 * runner — the same one-definition move the INLINED tier makes for
 * whole-input matching — so both engines answer identically by
 * construction. A bring-your-own engine that does not implement this
 * seam is rejected loudly by the facades
 * ({@code UnsupportedOperationException}), never silently degraded.
 *
 * <p>This seam sits beside its carrier ({@link StreamMatcher}) rather
 * than next to {@link io.github.jemmix.tdfa.core.engine.RegexEngine}:
 * its signature returns the dfa-tier carrier, and the core package DAG
 * keeps {@code engine} below {@code dfa}.
 */
public interface StreamEngine {

    /**
     * A streaming matcher over {@code source}: leftmost-first search
     * over chunked input, absolute stream offsets, one matcher per
     * stream (single-threaded).
     *
     * @param source the chunk feed, non-null
     * @return a fresh matcher owned by the caller
     */
    StreamMatcher streamingMatcher(ChunkSource source);
}
