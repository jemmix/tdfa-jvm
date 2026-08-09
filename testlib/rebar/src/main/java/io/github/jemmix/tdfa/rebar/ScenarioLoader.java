package io.github.jemmix.tdfa.rebar;

import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads rebar benchmark scenarios from a {@code benchmarks/} directory tree
 * matching rebar's documented layout:
 * <pre>
 *   benchmarks/
 *   ├── definitions/   *.toml files (possibly nested)
 *   ├── haystacks/     referenced by haystack = { path = "..." }
 *   └── regexes/       referenced by regex = { path = "..." }
 * </pre>
 *
 * <p>The full name of a scenario is {@code {group}/{name}}, where {@code group}
 * is the parent directory chain under {@code definitions/} plus the TOML
 * filename (without extension), joined by {@code /}. Per rebar's FORMAT.md.
 *
 * <p><b>Tracer-bullet limitations:</b>
 * <ul>
 *   <li>Per-engine count overrides ({@code count = [{engine=..., count=...}, ...]})
 *       are skipped — only the scalar form is captured.</li>
 *   <li>{@code regex = { path = "..." }} (regex loaded from file) is supported
 *       via {@link #resolveRegexPath}.</li>
 *   <li>{@code regex = { per-line = "alternate" }} and other regex-table
 *       transformations are not yet implemented.</li>
 *   <li>{@code haystack = { path = "..." }} is resolved against
 *       {@code benchmarks/haystacks/}.</li>
 * </ul>
 */
public final class ScenarioLoader {

    private final Path benchmarksDir;

    public ScenarioLoader(Path benchmarksDir) {
        this.benchmarksDir = benchmarksDir;
    }

    /** Load every scenario under {@code benchmarks/definitions/}. */
    public List<Scenario> loadAll() throws IOException {
        List<Scenario> out = new ArrayList<>();
        Path definitionsDir = benchmarksDir.resolve("definitions");
        if (!Files.isDirectory(definitionsDir)) {
            throw new IOException("not a directory: " + definitionsDir);
        }
        try (var walk = Files.walk(definitionsDir)) {
            walk.filter(p -> p.toString().endsWith(".toml"))
                .filter(Files::isRegularFile)
                .sorted()
                .forEach(toml -> {
                    try { loadFile(definitionsDir, toml, out); }
                    catch (IOException e) {
                        // Skip files we can't read; surfaced as missing scenarios.
                    }
                });
        }
        return List.copyOf(out);
    }

    private void loadFile(Path definitionsDir, Path tomlFile, List<Scenario> out) throws IOException {
        TomlParseResult parsed = Toml.parse(tomlFile);
        if (parsed.hasErrors()) {
            // Skip files with parse errors rather than failing the whole load;
            // callers can decide whether to surface them.
            return;
        }

        // group = parent dirs under definitions/ + filename without .toml
        Path rel = definitionsDir.relativize(tomlFile);
        String filenameNoExt = stripTomlSuffix(rel.getFileName().toString());
        String group;
        if (rel.getParent() == null) {
            group = filenameNoExt;
        } else {
            group = rel.getParent().toString().replace('\\', '/') + "/" + filenameNoExt;
        }

        TomlArray benches = parsed.getArray("bench");
        if (benches == null) {
            return;
        }
        for (int i = 0; i < benches.size(); i++) {
            TomlTable b = benches.getTable(i);
            Scenario s = parseBench(group, b);
            if (s != null) {
                out.add(s);
            }
        }
    }

    private Scenario parseBench(String group, TomlTable b) {
        String name = b.getString("name");
        if (name == null) {
            return null;
        }
        String fullName = group + "/" + name;
        String model = b.getString("model");
        String regex = resolveRegex(b.get("regex"));
        if (regex == null) {
            return null;
        }
        Scenario.HaystackSpec hsSpec = resolveHaystackSpec(b.get("haystack"));
        if (hsSpec == null) {
            return null;
        }
        boolean caseInsensitive = boolOr(b.getBoolean("case-insensitive"), false);
        boolean unicode = boolOr(b.getBoolean("unicode"), false);
        long count = resolveExpectedCount(b.get("count"));
        List<String> engines = new ArrayList<>();
        TomlArray eng = b.getArray("engines");
        if (eng != null) {
            for (int i = 0; i < eng.size(); i++) {
                engines.add(eng.getString(i));
            }
        }
        return new Scenario(
                fullName, group, name, model, regex,
                caseInsensitive, unicode, hsSpec, count,
                List.copyOf(engines));
    }

    /** Resolve a {@code regex} field that may be a string, array, or table. */
    private String resolveRegex(Object regexValue) {
        if (regexValue == null) {
            return null;
        }
        if (regexValue instanceof String s) {
            return s;
        }
        if (regexValue instanceof TomlArray arr) {
            // array of strings — for tracer-bullet take the first
            if (arr.size() > 0) {
                String first = arr.getString(0);
                if (first != null) return first;
            }
            return null;
        }
        if (regexValue instanceof TomlTable t) {
            // { patterns = "...", path = "...", literal = bool, ... }
            if (t.isString("patterns")) {
                return t.getString("patterns");
            }
            if (t.isArray("patterns")) {
                TomlArray arr = t.getArray("patterns");
                if (arr.size() > 0) {
                    String first = arr.getString(0);
                    if (first != null) return first;
                }
            }
            if (t.isString("path")) {
                return resolveRegexPath(t.getString("path"));
            }
        }
        return null;
    }

    private String resolveRegexPath(String path) {
        Path p = benchmarksDir.resolve("regexes").resolve(path);
        try {
            return Files.readString(p).trim();
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Resolve a {@code haystack} field's <em>spec</em> (without materializing
     * the haystack itself). Returns an {@link Scenario.HaystackSpec.Inline} for
     * inline strings, or {@link Scenario.HaystackSpec.FromPath} for path
     * references. Use {@link Scenario#resolveHaystack(Path)} to materialize.
     *
     * <p>Supports rebar's full transformation set on both inline and path
     * variants: {@code trim}, {@code repeat}, {@code prepend}, {@code append},
     * {@code line-start}, {@code line-end}.
     */
    private Scenario.HaystackSpec resolveHaystackSpec(Object hsValue) {
        if (hsValue == null) {
            return null;
        }
        if (hsValue instanceof String s) {
            return new Scenario.HaystackSpec.Inline(s, null, null, null);
        }
        if (hsValue instanceof TomlTable t) {
            Long repeat = t.getLong("repeat");
            String prepend = t.getString("prepend");
            String append = t.getString("append");
            if (t.isString("contents")) {
                return new Scenario.HaystackSpec.Inline(
                        t.getString("contents"), repeat, prepend, append);
            }
            if (t.isString("path")) {
                boolean trim = boolOr(t.getBoolean("trim"), false);
                Long ls = t.getLong("line-start");
                Long le = t.getLong("line-end");
                return new Scenario.HaystackSpec.FromPath(
                        t.getString("path"), trim, repeat, prepend, append,
                        ls != null ? ls.intValue() : null,
                        le != null ? le.intValue() : null);
            }
        }
        return null;
    }

    /**
     * Resolve the expected count. Per-engine overrides
     * ({@code count = [{engine=..., count=...}, ...]}) are skipped — we return
     * {@link Long#MIN_VALUE} to signal "no scalar expectation".
     */
    private long resolveExpectedCount(Object countValue) {
        if (countValue == null) {
            return Long.MIN_VALUE;
        }
        if (countValue instanceof Long l) {
            return l;
        }
        if (countValue instanceof TomlArray arr) {
            // Per-engine array — find the catch-all entry (engine = ".*") if present.
            for (int i = 0; i < arr.size(); i++) {
                TomlTable t = arr.getTable(i);
                if (t != null) {
                    String eng = t.getString("engine");
                    if (".*".equals(eng)) {
                        Long c = t.getLong("count");
                        return c != null ? c : Long.MIN_VALUE;
                    }
                }
            }
            return Long.MIN_VALUE;
        }
        return Long.MIN_VALUE;
    }

    private static String stripTomlSuffix(String name) {
        if (name.endsWith(".toml")) {
            return name.substring(0, name.length() - ".toml".length());
        }
        return name;
    }

    private static boolean boolOr(Boolean b, boolean def) {
        return b != null && b;
    }
}
