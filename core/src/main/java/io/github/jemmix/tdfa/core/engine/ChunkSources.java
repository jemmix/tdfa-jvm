package io.github.jemmix.tdfa.core.engine;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;

/**
 * Ready-made {@link ChunkSource} adapters (see
 * {@code docs/streaming-input.md} in the repository root): incremental
 * decoding of byte feeds into UTF-16 chunks. All of them carry partial
 * multi-byte sequences and surrogate pairs across chunk boundaries, and
 * use replacement semantics for malformed input — mirroring the
 * facade's {@code byte[]} overloads, which decode with
 * {@code new String(bytes, UTF_8)}.
 *
 * <p>Java 8 API only: decoding goes through {@link CharsetDecoder}
 * (Java 4), reading through {@link InputStream#read(byte[], int, int)}
 * and {@link Reader#read(char[], int, int)}. No {@code transferTo}, no
 * {@code readAllBytes} — the floored modules cannot rely on them.
 *
 * <p>Every adapter is single-threaded (the pulling thread) and closes
 * nothing: the underlying stream/reader's lifecycle stays with its
 * owner. Byte-side adapters require the decoder's malformed and
 * unmappable actions to be REPLACE or IGNORE (the incremental decode
 * protocol cannot carry a REPORT result across pulls) — rejected loudly
 * at construction.
 */
public final class ChunkSources {

    private static final int READ_BYTES = 4096;

    private ChunkSources() {
    }

    /** UTF-8 chunks from {@code in} (default 8 K chars per chunk). */
    public static ChunkSource utf8(InputStream in) {
        return utf8(in, 8 * 1024);
    }

    /** UTF-8 chunks from {@code in}, at most {@code chunkChars} chars per chunk. */
    public static ChunkSource utf8(InputStream in, int chunkChars) {
        return decoded(in, StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE), chunkChars);
    }

    /** Chunks from {@code in} decoded by {@code decoder} (default 8 K chars per chunk). */
    public static ChunkSource decoded(InputStream in, CharsetDecoder decoder) {
        return decoded(in, decoder, 8 * 1024);
    }

    /**
     * Chunks from {@code in} decoded by {@code decoder}, at most
     * {@code chunkChars} chars per chunk. Decoder state carries across
     * reads, so multi-byte sequences split across stream boundaries
     * decode correctly.
     */
    public static ChunkSource decoded(InputStream in, CharsetDecoder decoder, int chunkChars) {
        check(decoder, chunkChars);
        if (in == null) {
            throw new NullPointerException("stream must be non-null");
        }
        return new DecodingSource(in, decoder, chunkChars);
    }

    /** Chunks from {@code reader} (already-decoded chars; default 8 K per chunk). */
    public static ChunkSource reader(Reader reader) {
        return reader(reader, 8 * 1024);
    }

    /** Chunks from {@code reader}, at most {@code chunkChars} chars per chunk. */
    public static ChunkSource reader(Reader reader, int chunkChars) {
        if (reader == null) {
            throw new NullPointerException("reader must be non-null");
        }
        if (chunkChars < 1) {
            throw new IllegalArgumentException("chunkChars must be >= 1: " + chunkChars);
        }
        return new ReaderSource(reader, chunkChars);
    }

    /**
     * Chunks from a sequence of {@link ByteBuffer}s decoded by
     * {@code decoder} — the streaming-ByteBuffer shape: feed network
     * reads or file segments as they arrive. Buffers are consumed in
     * iteration order; a codepoint split across two buffers decodes
     * correctly (the decoder carries the partial sequence). At most
     * {@code chunkChars} chars per chunk.
     */
    public static ChunkSource byteBuffers(Iterator<ByteBuffer> buffers, CharsetDecoder decoder, int chunkChars) {
        check(decoder, chunkChars);
        if (buffers == null) {
            throw new NullPointerException("buffers must be non-null");
        }
        return new ByteBufferSource(buffers, decoder, chunkChars);
    }

    /** One already-decoded chunk, then end of input — the one-shot shape. */
    public static ChunkSource of(CharSequence chars) {
        if (chars == null) {
            throw new NullPointerException("chars must be non-null");
        }
        return new ChunkSource() {
            private boolean done;

            @Override
            public CharSequence next() {
                if (done || chars.length() == 0) {
                    done = true;
                    return null;
                }
                done = true;
                return chars;
            }
        };
    }

    private static void check(CharsetDecoder decoder, int chunkChars) {
        if (decoder == null) {
            throw new NullPointerException("decoder must be non-null");
        }
        if (chunkChars < 1) {
            throw new IllegalArgumentException("chunkChars must be >= 1: " + chunkChars);
        }
        if (CodingErrorAction.REPORT.equals(decoder.malformedInputAction())
            || CodingErrorAction.REPORT.equals(decoder.unmappableCharacterAction())) {
            throw new IllegalArgumentException(
                "decoder must use REPLACE or IGNORE error actions" + " (REPORT cannot carry across chunk pulls)");
        }
    }

    // ===== byte-feed adapters (shared incremental decode core) =====

    /**
     * One {@link CharsetDecoder} carried across pulls, a reused decode
     * buffer, and a pending-chunk accumulator. Decode passes always run
     * with the FULL buffer (at least two chars — a supplementary
     * codepoint emits both surrogates atomically and cannot make
     * progress into a one-char buffer); chunks are sliced from the
     * accumulator at the promised bound. {@link #nextBytes} feeds
     * ready-to-read byte buffers (their unconsumed tail is the
     * implementor's to retain); the decode runs with
     * {@code endOfInput=false} until the feed exhausts, then one final
     * pass — the adapter's leftover bytes decoded with
     * {@code endOfInput=true} under the REPLACE/IGNORE policy — plus
     * {@code flush} closes any dangling partial sequence.
     */
    private abstract static class ByteFeedSource implements ChunkSource {
        final CharsetDecoder decoder;
        /** Promised per-chunk char bound (>= 1). */
        private final int chunkMax;
        /** Decode buffer: at least two chars (supplementary quantum). */
        private final char[] out;
        private final StringBuilder pending = new StringBuilder();
        private boolean feedEof;

        ByteFeedSource(CharsetDecoder decoder, int chunkChars) {
            this.decoder = decoder;
            this.chunkMax = chunkChars;
            this.out = new char[Math.max(chunkChars, 2)];
        }

        /**
         * Next ready-to-read byte buffer, or null when the feed is
         * exhausted. The {@code finalPass} call happens exactly once
         * after exhaustion: the adapter hands back whatever it still
         * retains (a partial sequence's bytes), then never again.
         */
        abstract ByteBuffer nextBytes(boolean finalPass);

        @Override
        public CharSequence next() {
            for (;;) {
                if (feedEof) {
                    return pending.length() == 0 ? null : takeChunk(Math.min(pending.length(), chunkMax));
                }
                if (pending.length() >= chunkMax) {
                    return takeChunk(chunkMax);
                }
                ByteBuffer bytes = nextBytes(false);
                if (bytes == null) {
                    feedEof = true;
                    finalDecode(nextBytes(true));
                    continue;
                }
                decodeAll(bytes);
            }
        }

        private CharSequence takeChunk(int n) {
            String chunk = pending.substring(0, n);
            pending.delete(0, n);
            return chunk;
        }

        private void decodeAll(ByteBuffer in) {
            while (in.hasRemaining() && pending.length() < chunkMax) {
                CharBuffer cb = CharBuffer.wrap(out);
                decoder.decode(in, cb, false);
                pending.append(out, 0, cb.position());
                if (cb.position() == 0) {
                    return; // no progress this pass: input starved (decoder keeps state)
                }
            }
        }

        private void finalDecode(ByteBuffer rest) {
            ByteBuffer in = rest == null ? ByteBuffer.allocate(0) : rest;
            while (in.hasRemaining()) {
                CharBuffer cb = CharBuffer.wrap(out);
                decoder.decode(in, cb, true);
                pending.append(out, 0, cb.position());
                if (cb.position() == 0) {
                    break; // REPLACE/IGNORE guarantees progress on a final pass; guard anyway
                }
            }
            // Drain to END (flush is legal only there), then flush — both
            // with overflow loops: out can fill before the tail ends.
            CoderResult r;
            do {
                CharBuffer cb = CharBuffer.wrap(out);
                r = decoder.decode(ByteBuffer.allocate(0), cb, true);
                pending.append(out, 0, cb.position());
            } while (r.isOverflow());
            do {
                CharBuffer cb = CharBuffer.wrap(out);
                r = decoder.flush(cb);
                pending.append(out, 0, cb.position());
            } while (r.isOverflow());
        }
    }

    /** InputStream feed: a fixed read window with the unconsumed tail compacted forward. */
    private static final class DecodingSource extends ByteFeedSource {
        private final InputStream in;
        private final byte[] readBuf = new byte[READ_BYTES];
        /** Starts EMPTY (limit 0): [position, limit) is always exactly the unconsumed tail. */
        private final ByteBuffer window = ByteBuffer.wrap(readBuf, 0, 0);
        private boolean drained;

        DecodingSource(InputStream in, CharsetDecoder decoder, int chunkChars) {
            super(decoder, chunkChars);
            this.in = in;
        }

        @Override
        ByteBuffer nextBytes(boolean finalPass) {
            if (drained) {
                // Exhausted: nothing new will ever arrive — the leftover (a
                // dangling partial sequence, if any) goes out once, on the
                // final pass, decoded with endOfInput=true.
                return finalPass && window.hasRemaining() ? window : null;
            }
            int left = window.remaining();
            if (left > 0 && window.position() > 0) {
                System.arraycopy(readBuf, window.position(), readBuf, 0, left);
            }
            int n = 0;
            if (left < readBuf.length) {
                try {
                    n = in.read(readBuf, left, readBuf.length - left);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                if (n < 0) {
                    drained = true;
                    n = 0;
                }
            }
            window.position(0).limit(left + n);
            return window.hasRemaining() ? window : this.nextBytes(finalPass);
        }
    }

    /** ByteBuffer-iterator feed: the current buffer stays put until fully consumed. */
    private static final class ByteBufferSource extends ByteFeedSource {
        private final Iterator<ByteBuffer> buffers;
        private ByteBuffer current;

        ByteBufferSource(Iterator<ByteBuffer> buffers, CharsetDecoder decoder, int chunkChars) {
            super(decoder, chunkChars);
            this.buffers = buffers;
        }

        @Override
        ByteBuffer nextBytes(boolean finalPass) {
            if (current != null && current.hasRemaining()) {
                return current;
            }
            while (buffers.hasNext()) {
                ByteBuffer b = buffers.next();
                if (b.hasRemaining()) {
                    current = b;
                    return b;
                }
            }
            current = null;
            return null;
        }
    }

    /** Reader feed: chars straight into chunks (read blocks until one char or EOF). */
    private static final class ReaderSource implements ChunkSource {
        private final Reader reader;
        private final char[] buf;
        private boolean eof;

        ReaderSource(Reader reader, int chunkChars) {
            this.reader = reader;
            this.buf = new char[chunkChars];
        }

        @Override
        public CharSequence next() {
            if (eof) {
                return null;
            }
            int n;
            try {
                n = reader.read(buf, 0, buf.length);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            if (n < 0) {
                eof = true;
                return null;
            }
            return new String(buf, 0, n);
        }
    }
}
