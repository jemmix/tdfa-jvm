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
 *   <li>{@code regex = { path = "..." }} (regex loaded from file) is supported
 *       together with {@code per-line}, {@code literal}, {@code prepend},
 *       {@code append} per rebar's {@code WireRegexOptions::transform_from_file}.</li>
 *   <li>{@code haystack = { path = "..." }} is resolved against
 *       {@code benchmarks/haystacks/}.</li>
 *   <li>Per-engine {@code count} overrides pick the entry matching our
 *       {@code "re2"} identity first, then the {@code .*} catch-all.</li>
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
        long count = resolveExpectedCount(b.get("count"), unicode);
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

    /**
     * Resolve a {@code regex} field into a single pattern string, faithfully
     * following rebar's {@code WireRegexOptions::transform_from_file} /
     * {@code transform_from_inline} (FORMAT.md §regex). Multi-pattern inputs
     * (inline array, {@code per-line = "pattern"}) are folded into one
     * alternation — rebar searches them as a combined leftmost scan, which is
     * exactly Perl-mode {@code A|B|C}.
     *
     * <p>Supported shapes:
     * <ul>
     *   <li>string → as-is</li>
     *   <li>array of strings → {@code (?:p1)|(?:p2)|...}</li>
     *   <li>table with {@code patterns = ...} (string or array)</li>
     *   <li>table with {@code path = "..."} + optional
     *       {@code per-line = "alternate"|"pattern"}, {@code literal},
     *       {@code prepend}, {@code append}</li>
     * </ul>
     *
     * <p>Returns {@code null} when no pattern can be derived.
     */
    private String resolveRegex(Object regexValue) {
        List<String> patterns;
        if (regexValue == null) {
            return null;
        }
        if (regexValue instanceof String s) {
            patterns = List.of(s);
        } else if (regexValue instanceof TomlArray arr) {
            patterns = new ArrayList<>(arr.size());
            for (int i = 0; i < arr.size(); i++) {
                String p = arr.getString(i);
                if (p != null) patterns.add(p);
            }
        } else if (regexValue instanceof TomlTable t) {
            boolean literal = boolOr(t.getBoolean("literal"), false);
            String prepend = t.getString("prepend");
            String append  = t.getString("append");
            String perLine = t.getString("per-line");

            if (t.isString("patterns")) {
                patterns = transform(List.of(t.getString("patterns")),
                        literal, prepend, append);
            } else if (t.isArray("patterns")) {
                TomlArray arr = t.getArray("patterns");
                List<String> ps = new ArrayList<>(arr.size());
                for (int i = 0; i < arr.size(); i++) {
                    ps.add(arr.getString(i));
                }
                patterns = transform(ps, literal, prepend, append);
            } else if (t.isString("path")) {
                String raw = resolveRegexPath(t.getString("path"));
                if (raw == null) return null;
                if ("alternate".equals(perLine)) {
                    // rebar wraps each line in (?:...) and joins with |.
                    List<String> lines = raw.lines().toList();
                    List<String> transformed = transform(lines, literal, prepend, append);
                    return joinAlternation(transformed);
                }
                if ("pattern".equals(perLine)) {
                    // Multi-pattern → fold to one alternation (preserving each
                    // line's internal groups so capture-based models still
                    // count correctly).
                    List<String> lines = raw.lines().toList();
                    List<String> transformed = transform(lines, literal, prepend, append);
                    return joinAlternation(transformed);
                }
                patterns = transform(List.of(raw.strip()), literal, prepend, append);
            } else {
                return null;
            }
        } else {
            return null;
        }
        if (patterns.isEmpty()) return null;
        return joinAlternation(patterns);
    }

    /**
     * Mirror of rebar's {@code WireRegexOptions::transform}: apply
     * {@code literal} (regex-escape), {@code prepend}, {@code append} to each
     * pattern.
     */
    private static List<String> transform(List<String> patterns,
                                          boolean literal, String prepend, String append) {
        List<String> out = new ArrayList<>(patterns.size());
        for (String p : patterns) {
            if (p == null) continue;
            if (literal) p = regexEscape(p);
            if (prepend != null) p = prepend + p;
            if (append  != null) p = p + append;
            out.add(p);
        }
        return out;
    }

    /**
     * Join patterns into a single Perl-style alternation. A singleton is
     * returned as-is. Multiple patterns are wrapped in non-capturing groups
     * so any {@code |} inside an individual pattern doesn't bleed across the
     * join. Capture groups inside each pattern are preserved.
     */
    private static String joinAlternation(List<String> patterns) {
        if (patterns.size() == 1) {
            return patterns.get(0);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < patterns.size(); i++) {
            if (i > 0) sb.append('|');
            sb.append("(?:").append(patterns.get(i)).append(')');
        }
        return sb.toString();
    }

    /**
     * Escape regex metacharacters in {@code s} so it matches literally.
     * Matches {@code regex_lite::escape} / {@code regex::escape}: every
     * ASCII byte outside {@code [A-Za-z0-9]} is backslash-escaped.
     */
    private static String regexEscape(String s) {
        StringBuilder sb = new StringBuilder(s.length() * 2);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) {
                sb.append(c);
            } else {
                sb.append('\\').append(c);
            }
        }
        return sb.toString();
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
     * Resolve the expected count. Per-engine overrides come as an array of
     * {@code {engine = "<regex>", count = N}} tables; per FORMAT.md the first
     * regex to match the engine name (in order) wins.
     *
     * <p>Our engine is a drop-in re2j replacement, so we present ourselves as
     * {@code "re2"} (re2j's upstream) for ASCII-class scenarios. When
     * {@code unicode = true}, the parity test enables
     * {@code UNICODE_CHARACTER_CLASS}, so our {@code \w}/{@code \d}/{@code \s}
     * behavior matches {@code java.util.regex} — we therefore resolve as
     * {@code "java/hotspot"} first to pick up Java-specific counts.
     */
    private long resolveExpectedCount(Object countValue, boolean unicode) {
        if (countValue == null) {
            return Long.MIN_VALUE;
        }
        if (countValue instanceof Long l) {
            return l;
        }
        if (countValue instanceof TomlArray arr) {
            // rebar anchors each engine regex with ^...$ and matches in order.
            // Our engine is a re2j drop-in replacement but is codepoint-oriented
            // and (with UNICODE_CHARACTER_CLASS) matches java.util.regex for
            // \w/\d/\s/\b. We always resolve as "java/hotspot" first: upstream
            // TOMLs have java/.* entries for most architectural divergences
            // (codepoint vs byte spans, etc.). A few scenarios where re2j
            // intentionally diverges from j.u.r (e.g. . matches \r, $ doesn't
            // match before final line terminator) are patched in vendor/patches.
            String[] identities = new String[]{"java/hotspot", "re2", ".*"};
            for (String identity : identities) {
                for (int i = 0; i < arr.size(); i++) {
                    TomlTable t = arr.getTable(i);
                    if (t == null) continue;
                    String engRegex = t.getString("engine");
                    if (engRegex == null) continue;
                    java.util.regex.Pattern r;
                    try {
                        r = java.util.regex.Pattern.compile("^(" + engRegex + ")$");
                    } catch (Exception e) {
                        continue;
                    }
                    if (r.matcher(identity).matches()) {
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
