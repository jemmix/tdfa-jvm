package io.github.jemmix.tdfa.core.engine;

/**
 * A pull-based chunk source for streaming input (see
 * {@code docs/streaming-input.md} in the repository root): the matcher
 * draws UTF-16 chunks as it needs them and never materializes the whole
 * input as a String.
 *
 * <p><b>Contract.</b> {@link #next()} returns the next <b>non-empty</b>
 * chunk of UTF-16 units, or {@code null} at end of input. Chunks are
 * consumed immediately (copied into the matcher's window) — the source
 * may reuse its buffers. Exactly one thread pulls (the thread owning the
 * streaming matcher); the source itself needs no synchronization beyond
 * that. An empty chunk is a contract violation and fails loudly with
 * {@link IllegalArgumentException}.
 *
 * <p><b>Surrogate pairs may straddle chunk boundaries</b> — the matcher
 * carries the pending half-unit across {@code next()} calls, exactly as
 * the byte-side adapters carry partial multi-byte sequences
 * ({@link ChunkSources}). Match offsets are absolute UTF-16 unit
 * positions in the whole stream.
 *
 * <p>Ready-made adapters live in {@link ChunkSources}; implementing this
 * interface over your own transport (a message queue, a memory-mapped
 * segment reader, a test harness) is the intended custom path.
 */
public interface ChunkSource {

    /**
     * The next non-empty chunk of UTF-16 units, or {@code null} at end
     * of input. After a {@code null}, every further call must also
     * return {@code null}.
     *
     * @return a chunk with {@code length() >= 1}, or {@code null}
     */
    CharSequence next();
}
