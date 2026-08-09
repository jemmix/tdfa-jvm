package io.github.jemmix.tdfa.rebar;

import java.util.List;

/**
 * A single rebar benchmark scenario, parsed from a TOML definition under
 * {@code benchmarks/definitions/}. Captures the fields we need to run the
 * scenario against our own engine; ignores engine-specific orchestration
 * (we are the only engine under test).
 *
 * <p><b>Lazy haystack:</b> {@link #haystackSpec()} captures the raw spec
 * (inline string or path reference). Use {@link #resolveHaystack(Path)} to
 * materialize on demand — avoids loading 100+ MB of haystacks at parse time.
 *
 * @see ScenarioLoader
 */
public record Scenario(
        String fullName,
        String group,
        String name,
        String model,
        String regex,
        boolean caseInsensitive,
        boolean unicode,
        HaystackSpec haystackSpec,
        long expectedCount,
        List<String> engines
) {
    /** Inline string haystack or path-reference; resolved against benchmarksDir. */
    public sealed interface HaystackSpec permits HaystackSpec.Inline, HaystackSpec.FromPath {
        record Inline(String contents, Long repeat, String prepend, String append) implements HaystackSpec {
            public Inline {
                if (contents == null) throw new NullPointerException("contents");
            }
        }
        record FromPath(String path, boolean trim, Long repeat,
                        String prepend, String append,
                        Integer lineStart, Integer lineEnd) implements HaystackSpec {
            public FromPath {
                if (path == null) throw new NullPointerException("path");
            }
        }
    }

    /**
     * Materialize the haystack content, resolving path references against the dir.
     * Applies {@code trim}, {@code repeat}, {@code prepend}, {@code append},
     * {@code line-start}, {@code line-end} transformations per rebar's FORMAT.md.
     */
    public String resolveHaystack(java.nio.file.Path benchmarksDir) throws java.io.IOException {
        if (haystackSpec instanceof HaystackSpec.Inline i) {
            return applyTransforms(i.contents(), false, i.repeat(), i.prepend(), i.append(), null, null);
        } else if (haystackSpec instanceof HaystackSpec.FromPath p) {
            var file = benchmarksDir.resolve("haystacks").resolve(p.path());
            String raw = java.nio.file.Files.readString(file);
            return applyTransforms(raw, p.trim(), p.repeat(), p.prepend(), p.append(),
                    p.lineStart(), p.lineEnd());
        } else {
            throw new IllegalStateException("unknown haystack spec: " + haystackSpec);
        }
    }

    private static String applyTransforms(String base, boolean trim, Long repeat,
                                          String prepend, String append,
                                          Integer lineStart, Integer lineEnd) {
        if (trim) base = base.trim();
        if (lineStart != null || lineEnd != null) {
            base = sliceLines(base, lineStart, lineEnd);
        }
        StringBuilder sb = new StringBuilder();
        if (prepend != null) sb.append(prepend);
        long reps = repeat == null ? 1 : repeat;
        for (long r = 0; r < reps; r++) sb.append(base);
        if (append != null) sb.append(append);
        return sb.toString();
    }

    /** 1-based line slicing, matching rebar's line-start/line-end semantics. */
    private static String sliceLines(String s, Integer start, Integer end) {
        String[] lines = s.split("\n", -1);
        int from = start == null ? 0 : Math.max(0, start - 1);
        int to = end == null ? lines.length : Math.min(lines.length, end);
        if (from >= to) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < to; i++) {
            if (i > from) sb.append('\n');
            sb.append(lines[i]);
        }
        return sb.toString();
    }
}
