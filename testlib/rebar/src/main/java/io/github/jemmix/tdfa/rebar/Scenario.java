package io.github.jemmix.tdfa.rebar;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A single rebar benchmark scenario, parsed from a TOML definition under
 * {@code benchmarks/definitions/}. Captures the fields we need to run the
 * scenario against our own engine; ignores engine-specific orchestration
 * (we are the only engine under test).
 *
 * <p><b>Regex:</b> rebar's {@code regex} field may expand to multiple
 * patterns (inline array, or {@code per-line = "pattern"} from a file).
 * Per rebar's FORMAT.md those patterns are searched <em>together</em> — a
 * single combined scan reporting the leftmost match across all patterns.
 * That's exactly Perl alternation semantics, so we collapse multi-pattern
 * specs into one regex by joining with {@code |}. Capture-group counts in
 * each original pattern are preserved.
 *
 * <p><b>Lazy haystack:</b> {@link #haystackSpec()} captures the raw spec
 * (inline string or path reference). Use {@link #resolveHaystack(Path)} to
 * materialize on demand — avoids loading 100+ MB of haystacks at parse time.
 *
 * @see ScenarioLoader
 */
public record Scenario(String fullName, String group, String name, String model, String regex, boolean caseInsensitive,
    boolean unicode, HaystackSpec haystackSpec, long expectedCount, List<String> engines) {

    /**
     * Per-process haystack-file cache. Many rebar scenarios share the same
     * haystack file (e.g. {@code imported/leipzig-3200.txt} is used by 26
     * scenarios; {@code sherlock.txt} by 51). Without this cache each
     * scenario re-reads + re-decodes the file, costing ~200–500 ms per
     * 15 MB file. With cache, only the first scenario pays that cost.
     *
     * <p>Keyed by {@code (absolute path, utf8Lossy)} because the same file
     * may be referenced both with and without {@code utf8-lossy} (e.g.
     * {@code wild/cpython-226484e4.py}). Files larger than 50 MB aren't
     * cached to avoid hogging heap for the rest of the run.
     *
     * <p>Thread-safe: {@link java.util.concurrent.ConcurrentHashMap} plus
     * {@code String} (immutable). Worst case under a race is two reads of
     * the same file, with one result discarded.
     */
    private static final ConcurrentHashMap<CacheKey, String> FILE_CACHE = new ConcurrentHashMap<>();
    /** Inline string haystack or path-reference; resolved against benchmarksDir. */
    public sealed interface HaystackSpec permits HaystackSpec.Inline, HaystackSpec.FromPath {
        record Inline(String contents, Long repeat, String prepend, String append) implements HaystackSpec {
            public Inline {
                if (contents == null) {
                    throw new NullPointerException("contents");
                }
            }
        }

        record FromPath(String path, boolean trim, boolean utf8Lossy, Long repeat, String prepend, String append,
            Integer lineStart, Integer lineEnd) implements HaystackSpec {
            public FromPath {
                if (path == null) {
                    throw new NullPointerException("path");
                }
            }
        }
    }

    /**
     * Materialize the haystack content, resolving path references against the dir.
     * Applies {@code trim}, {@code repeat}, {@code prepend}, {@code append},
     * {@code line-start}, {@code line-end} transformations per rebar's FORMAT.md.
     *
     * <p>When {@code utf8-lossy = true} was set on a path-reference haystack,
     * the file is decoded with U+FFFD replacement for invalid UTF-8 byte
     * sequences (matching rebar's {@code bstr::decode_bytes_lossy} semantics).
     * Files marked lossy in the in-scope corpus: {@code wild/cpython-226484e4.py},
     * {@code imported/lh3lh3-reb-howto.txt}.
     */
    public String resolveHaystack(Path benchmarksDir) throws IOException {
        if (haystackSpec instanceof HaystackSpec.Inline i) {
            return applyTransforms(i.contents(), false, i.repeat(), i.prepend(), i.append(), null, null);
        } else if (haystackSpec instanceof HaystackSpec.FromPath p) {
            var file = benchmarksDir.resolve("haystacks").resolve(p.path());
            String raw = readHaystackFile(file, p.utf8Lossy());
            return applyTransforms(raw, p.trim(), p.repeat(), p.prepend(), p.append(), p.lineStart(), p.lineEnd());
        } else {
            throw new IllegalStateException("unknown haystack spec: " + haystackSpec);
        }
    }

    private record CacheKey(Path path, boolean utf8Lossy) {
    }

    private static String readHaystackFile(Path file, boolean utf8Lossy) throws IOException {
        CacheKey key = new CacheKey(file, utf8Lossy);
        String cached = FILE_CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        String raw = utf8Lossy ? readStringLossy(file) : Files.readString(file);
        if (raw.length() <= 50_000_000) {
            FILE_CACHE.putIfAbsent(key, raw);
        }
        return raw;
    }

    /**
     * Read {@code file} as UTF-8 with invalid byte sequences replaced by
     * U+FFFD (matching rebar's {@code utf8-lossy = true} semantics — see
     * FORMAT.md §Haystacks). Used for in-scope corpus files like
     * {@code wild/cpython-226484e4.py} and {@code imported/lh3lh3-reb-howto.txt}
     * that contain legacy Latin-1 / ISO-8859 octets in comments.
     */
    private static String readStringLossy(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        // CharsetDecoder is not thread-safe; create a fresh one per call.
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE);
        try {
            return decoder.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            // CodingErrorAction.REPLACE makes this practically unreachable,
            // but CharacterCodingException is checked so we must handle it.
            throw new IOException("lossy UTF-8 decode failed for " + file, e);
        }
    }

    private static String applyTransforms(String base, boolean trim, Long repeat, String prepend, String append,
        Integer lineStart, Integer lineEnd) {
        if (trim) {
            base = base.trim();
        }
        if (lineStart != null || lineEnd != null) {
            base = sliceLines(base, lineStart, lineEnd);
        }
        StringBuilder sb = new StringBuilder();
        if (prepend != null) {
            sb.append(prepend);
        }
        long reps = repeat == null ? 1 : repeat;
        for (long r = 0; r < reps; r++) {
            sb.append(base);
        }
        if (append != null) {
            sb.append(append);
        }
        return sb.toString();
    }

    /** 1-based line slicing, matching rebar's line-start/line-end semantics. */
    private static String sliceLines(String s, Integer start, Integer end) {
        String[] lines = s.split("\n", -1);
        int from = start == null ? 0 : Math.max(0, start - 1);
        int to = end == null ? lines.length : Math.min(lines.length, end);
        if (from >= to) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < to; i++) {
            if (i > from) {
                sb.append('\n');
            }
            sb.append(lines[i]);
        }
        return sb.toString();
    }
}
