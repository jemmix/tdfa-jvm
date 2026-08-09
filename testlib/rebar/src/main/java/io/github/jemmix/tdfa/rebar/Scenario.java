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
        record Inline(String contents) implements HaystackSpec {
            public Inline {
                if (contents == null) throw new NullPointerException("contents");
            }
        }
        record FromPath(String path) implements HaystackSpec {
            public FromPath {
                if (path == null) throw new NullPointerException("path");
            }
        }
    }

    /** Materialize the haystack content, resolving path references against the dir. */
    public String resolveHaystack(java.nio.file.Path benchmarksDir) throws java.io.IOException {
        if (haystackSpec instanceof HaystackSpec.Inline i) {
            return i.contents();
        } else if (haystackSpec instanceof HaystackSpec.FromPath p) {
            var file = benchmarksDir.resolve("haystacks").resolve(p.path());
            return java.nio.file.Files.readString(file);
        } else {
            throw new IllegalStateException("unknown haystack spec: " + haystackSpec);
        }
    }
}
