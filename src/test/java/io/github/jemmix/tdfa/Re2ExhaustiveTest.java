package io.github.jemmix.tdfa;

import io.github.jemmix.tdfa.vm.MatchResult;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Harness that runs Google's RE2 exhaustive test corpus
 * (testdata/re2-exhaustive.txt.gz from google/re2j) against the VM backend.
 *
 * File format (per re2j ExecTest.testRE2):
 *   - Stanza: an optional Name line (Capitalised), then "strings", a list of
 *     Go-quoted strings, then "regexps", then per regexp: N result lines (one
 *     per string in the stanza's strings list). Each result line is
 *     "full-nonlongest;partial-nonlongest;full-longest;partial-longest" where
 *     each field is "-" or space-separated "lo-hi" pairs (group 0 first).
 *
 * We compare overall match span against column 1 (partial/non-longest = re2j's
 * default RE2.compile find semantics) and use column 3 (partial/longest) as a
 * secondary check to distinguish POSIX/longest behaviour from real bugs.
 *
 * Diagnostic only: prints a triage breakdown; never fails the build.
 */
class Re2ExhaustiveTest {

    /** Per-category triage buckets. */
    static final class Triage {
        final TreeMap<String, Integer> counts = new TreeMap<>();
        final Map<String, List<String>> examples = new HashMap<>();
        final Map<String, Map<String, Integer>> topPatterns = new HashMap<>();

        void add(String category, String example) {
            counts.merge(category, 1, Integer::sum);
            examples.computeIfAbsent(category, k -> new ArrayList<>());
            List<String> ex = examples.get(category);
            if (ex.size() < 4) ex.add(example);
        }

        /** Track that `pattern` caused a failure in `category` (for top-offenders rollup). */
        void notePattern(String category, String pattern) {
            topPatterns.computeIfAbsent(category, k -> new HashMap<>())
                    .merge(pattern, 1, Integer::sum);
        }

        int total() { return counts.values().stream().mapToInt(Integer::intValue).sum(); }
    }

    @Test
    void runRe2ExhaustiveVm() throws IOException {
        InputStream raw = Re2ExhaustiveTest.class.getResourceAsStream("/re2-exhaustive.txt.gz");
        assertThat(raw).as("re2-exhaustive.txt.gz on test classpath").isNotNull();
        BufferedReader r = new BufferedReader(new InputStreamReader(new GZIPInputStream(raw), StandardCharsets.UTF_8));

        Triage triage = new Triage();
        Map<String, Regex> compiledCache = new HashMap<>();
        Map<String, String> compileErrorCache = new HashMap<>();

        List<String> strings = new ArrayList<>();
        boolean inStrings = false;
        String currentPattern = null;
        Regex currentRegex = null;
        String currentCompileError = null;
        int inputIdx = 0;
        String stanza = "(header)";
        boolean stanzaIsUtf8 = false;  // these stanzas use UTF-8 byte indices we can't compare against UTF-16
        int totalCases = 0;
        int skippedUtf8 = 0;
        int skippedCompileError = 0;  // result rows for patterns that didn't compile
        int compileErrAgree = 0;      // we reject & re2j rejects (e.g. \C) — counts as pass
        int spanPass = 0;
        int spanPassPosixOnly = 0;

        String line;
        long start = System.currentTimeMillis();
        long lastReport = start;

        while ((line = r.readLine()) != null) {
            if (line.isEmpty()) continue;
            char first = line.charAt(0);
            if (first == '#') continue;
            if (first >= 'A' && first <= 'Z' && !line.startsWith("strings") && !line.startsWith("regexps")) {
                // Stanza name like "Repetition.Simple"
                stanza = line;
                stanzaIsUtf8 = stanza.contains("UTF8") || stanza.contains("Utf8");
                continue;
            }
            if (line.equals("strings")) {
                strings.clear();
                inStrings = true;
            } else if (line.equals("regexps")) {
                inStrings = false;
            } else if (first == '"') {
                String q = goUnquote(line);
                if (inStrings) {
                    strings.add(q);
                    continue;
                }
                // new regexp
                currentPattern = q;
                if (compileErrorCache.containsKey(currentPattern)) {
                    currentCompileError = compileErrorCache.get(currentPattern);
                    currentRegex = null;
                } else if (compiledCache.containsKey(currentPattern)) {
                    currentRegex = compiledCache.get(currentPattern);
                    currentCompileError = null;
                } else {
                    try {
                        currentRegex = Regex.compileVm(currentPattern);
                        compiledCache.put(currentPattern, currentRegex);
                        currentCompileError = null;
                    } catch (Throwable e) {
                        currentCompileError = e.getClass().getSimpleName() + ": " + safeMsg(e);
                        compileErrorCache.put(currentPattern, currentCompileError);
                        currentRegex = null;
                    }
                }
                if (currentCompileError != null && !currentCompileError.contains("\\C")) {
                    triage.add(categoryForCompileError(currentPattern, currentCompileError),
                            "[" + stanza + "] /" + currentPattern + "/ -> " + currentCompileError);
                }
                inputIdx = 0;
            } else if (first == '-' || (first >= '0' && first <= '9')) {
                // Result line: full-nonlongest;partial-nonlongest;full-longest;partial-longest
                totalCases++;
                if (currentCompileError != null || currentRegex == null) {
                    // Compile-error. If re2j also rejects (e.g. \C), count as a pass.
                    if (currentCompileError != null && currentCompileError.contains("\\C")) {
                        compileErrAgree++;
                    } else {
                        skippedCompileError++;
                    }
                    continue;
                }
                if (inputIdx >= strings.size()) continue;
                String text = strings.get(inputIdx++);
                if (stanzaIsUtf8 || (hasMultibyte(text) && (currentPattern.contains("\\B") || currentPattern.contains("\\C")))) {
                    skippedUtf8++;
                    continue;
                }

                String[] cols = line.split(";");
                if (cols.length != 4) continue;
                int[] wantCol1 = parseResult(cols[1]);  // partial/non-longest (re2j default find)
                int[] wantCol3 = parseResult(cols[3]);  // partial/longest (POSIX)

                int[] got = null;
                try {
                    MatchResult m = currentRegex.find(text, 0);
                    if (m != null) {
                        got = new int[]{m.start(0), m.end(0)};
                    }
                } catch (Throwable e) {
                    triage.add("RUNTIME_EXCEPTION",
                            "[" + stanza + "] /" + currentPattern + "/ on \"" + esc(text) + "\" -> " +
                                    e.getClass().getSimpleName() + ": " + safeMsg(e));
                    continue;
                }

                boolean passCol1 = sameSpan(wantCol1, got);
                boolean passCol3 = sameSpan(wantCol3, got);

                if (passCol1) {
                    spanPass++;
                    // Verify submatch positions when expected (want has more than group 0)
                    if (wantCol1 != null && wantCol1.length > 2) {
                        String sub = checkSubmatches(currentRegex, text, wantCol1);
                        if (sub != null) {
                            triage.add("SUBMATCH_WRONG",
                                    "[" + stanza + "] /" + currentPattern + "/ on \"" + esc(text) + "\" " +
                                            "span ok but " + sub);
                        }
                    }
                } else if (passCol3) {
                    spanPassPosixOnly++;
                    triage.add("ALT_AMBIGUITY_LONGEST",
                            "[" + stanza + "] /" + currentPattern + "/ on \"" + esc(text) + "\" " +
                                    "want(perl)=" + Arrays.toString(wantCol1) + " got=" + Arrays.toString(got) +
                                    " (matches POSIX longest col3=" + Arrays.toString(wantCol3) + ")");
                } else {
                    // Span mismatch — print want's full submatch layout for context
                    String cat = categoryForMatchFailure(currentPattern, text, wantCol1, wantCol3, got);
                    triage.add(cat,
                            "[" + stanza + "] /" + currentPattern + "/ on \"" + esc(text) + "\" " +
                                    "want=" + Arrays.toString(wantCol1) + " got=" + Arrays.toString(got));
                    triage.notePattern(cat, currentPattern);
                }

                if (totalCases % 50000 == 0) {
                    long now = System.currentTimeMillis();
                    if (now - lastReport > 2000) {
                        System.err.printf("  progress: %,d cases, %,d pass, %,d posix-only, %,d fail [%s] [%,d ms]%n",
                                totalCases, spanPass, spanPassPosixOnly, triage.total() - spanPassPosixOnly,
                                stanza, now - start);
                        lastReport = now;
                    }
                }
            }
        }
        r.close();

        long elapsed = System.currentTimeMillis() - start;
        int spanFail = triage.total() - spanPassPosixOnly;
        int runnable = totalCases - skippedUtf8 - skippedCompileError - compileErrAgree;
        // Note: triage.total() includes submatch-wrong, runtime, alt-ambiguity, plus all match-failure buckets.

        // Build report
        StringBuilder out = new StringBuilder();
        out.append("\n================ RE2 EXHAUSTIVE — VM TRIAGE ================\n");
        out.append(String.format("Elapsed:            %,d ms%n", elapsed));
        out.append(String.format("Total result rows:  %,d%n", totalCases));
        out.append(String.format("Skipped UTF-8 idx:  %,d  (UTF-8 byte-index stanzas; not comparable to UTF-16 engine)%n", skippedUtf8));
        out.append(String.format("Skipped compile-err:%,d  (patterns we reject that re2j accepts — bugs to fix)%n", skippedCompileError));
        out.append(String.format("Compile-err agree:  %,d  (we reject & re2j rejects, e.g. \\C — parity)%n", compileErrAgree));
        out.append(String.format("Unique patterns:    %,d (compiled ok: %,d, compile-err: %,d)%n",
                compiledCache.size() + compileErrorCache.size(), compiledCache.size(), compileErrorCache.size()));
        out.append(String.format("Runnable cases:     %,d%n", runnable));
        out.append(String.format("SPAN pass (col1):   %,d  (%.1f%% of runnable)%n",
                spanPass, 100.0 * spanPass / Math.max(1, runnable)));
        out.append(String.format("SPAN posix-only:    %,d  (%.1f%%)  [match col3 but not col1]%n",
                spanPassPosixOnly, 100.0 * spanPassPosixOnly / Math.max(1, runnable)));
        out.append(String.format("Total fail rows:    %,d  (%.1f%% of runnable)%n",
                spanFail, 100.0 * spanFail / Math.max(1, runnable)));
        out.append("\n--- Triage buckets (descending) ---\n");
        triage.counts.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .forEach(e -> out.append(String.format("  %-28s %,8d  (%.2f%% of runnable)%n",
                        e.getKey(), e.getValue(), 100.0 * e.getValue() / Math.max(1, runnable))));
        out.append("\n--- Sample failures per bucket ---\n");
        triage.counts.keySet().forEach(cat -> {
            out.append("[").append(cat).append("]\n");
            for (String ex : triage.examples.getOrDefault(cat, List.of())) {
                out.append("    ").append(ex).append('\n');
            }
        });
        out.append("\n--- Top offending patterns per bucket (count = # input strings that failed) ---\n");
        triage.counts.keySet().forEach(cat -> {
            Map<String, Integer> tops = triage.topPatterns.get(cat);
            if (tops == null || tops.isEmpty()) return;
            out.append("[").append(cat).append("] — ").append(tops.size()).append(" distinct patterns\n");
            tops.entrySet().stream()
                    .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                    .limit(8)
                    .forEach(e -> out.append(String.format("    %6d  /%s/%n", e.getValue(), e.getKey())));
        });
        out.append("\n--- Bucket definitions ---\n");
        out.append(bucketDefs());

        String report = out.toString();
        System.err.println(report);
        Path reportFile = Paths.get("build/reports/re2-exhaustive-vm.txt");
        Files.createDirectories(reportFile.getParent());
        Files.writeString(reportFile, report);

        // Sanity: ensure we actually exercised a meaningful number of cases.
        assertThat(totalCases).as("re2-exhaustive rows processed").isGreaterThan(100_000);
    }

    /** Compare only the overall match span (group 0). */
    private static boolean sameSpan(int[] want, int[] got) {
        if (want == null) return got == null;
        if (got == null) return false;
        return want[0] == got[0] && want[1] == got[1];
    }

    private static String checkSubmatches(Regex r, String text, int[] wantCol1) {
        MatchResult m = r.find(text, 0);
        if (m == null) return "no rematch?";
        int gc = wantCol1.length / 2;
        for (int g = 1; g < gc; g++) {
            int ws = wantCol1[g * 2];
            int we = wantCol1[g * 2 + 1];
            int as = m.start(g);
            int ae = m.end(g);
            // re2j uses -1 for unmatched; we use -1 too
            if (ws != as || we != ae) {
                return "group " + g + " want=[" + ws + "," + we + ") got=[" + as + "," + ae + ")";
            }
        }
        return null;
    }

    /** Categorize compile-error by sniffing the pattern + message. */
    private static String categoryForCompileError(String pat, String err) {
        if (err.contains("backslash-C") || pat.contains("\\C")) return "COMPILE.\\C";
        if (pat.contains("\\A") || pat.contains("\\z")) return "COMPILE.\\A\\z";
        if (pat.contains("(?P<") || pat.contains("(?<")) return "COMPILE.named-group";
        if (pat.contains("[:")) return "COMPILE.POSIX-class";
        if (pat.contains("\\p{") || pat.contains("\\P{")) return "COMPILE.unicode-class";
        if (pat.contains("\\b") || pat.contains("\\B")) return "COMPILE.\\b\\B";
        if (pat.contains("(?")) return "COMPILE.inline-flag";
        if (err.contains("Possessive")) return "COMPILE.possessive";
        if (err.contains("Backref") || pat.matches(".*\\\\[1-9].*")) return "COMPILE.backref";
        return "COMPILE.other";
    }

    /** Categorize match-span mismatches. Order matters: check silent-misparses first. */
    private static String categoryForMatchFailure(String pat, String text, int[] want1, int[] want3, int[] got) {
        // Silent-misparses: parser accepts but assigns wrong meaning. These dominate.
        if (containsEscape(pat, 'C')) return "FAIL.silent-misparse.\\C";      // RE2: any byte; we: literal 'C'
        if (containsEscape(pat, 'b') || containsEscape(pat, 'B')) return "FAIL.silent-misparse.\\b";  // word boundary; we: literal 'b'
        if (containsEscape(pat, 'A') || containsEscape(pat, 'z')) return "FAIL.silent-misparse.\\A\\z";  // string anchors; we: literal

        // Class parsing: leading `]` or `[` inside `[...]` is literal in RE2/POSIX.
        if (pat.contains("[]") || pat.contains("[^]")) return "FAIL.class-leading-]";

        // Real anchors: zero-width ^ / $ inside groups/repeats aren't enforced.
        if (pat.contains("^") || pat.contains("$")) return "FAIL.anchors";

        // Inline flag groups: (?i) (?s) — note (?: is non-capturing, not a flag.
        if (hasInlineFlag(pat)) return "FAIL.flags";

        // Alternation correctness (not ambiguity — that's caught as ALT_AMBIGUITY_LONGEST).
        if (pat.contains("|")) return "FAIL.alternation";

        // Empty alternation branches (a|) — known TDFA issue
        if (pat.contains("()") || pat.contains("|)")) return "FAIL.empty-alt-branch";

        // Capture groups with quantifiers
        if (pat.contains("(") && (pat.contains("*") || pat.contains("+") || pat.contains("?"))) {
            return "FAIL.tag-timing";
        }

        // Empty pattern / Empty branch
        if (pat.isEmpty() || pat.endsWith("|") || pat.startsWith("|")) return "FAIL.empty-pattern";
        return "FAIL.other";
    }

    /** True iff regex source pat contains the escape sequence \<c> (backslash followed by c). */
    private static boolean containsEscape(String pat, char c) {
        for (int i = 0; i + 1 < pat.length(); i++) {
            if (pat.charAt(i) == '\\' && pat.charAt(i + 1) == c) return true;
        }
        return false;
    }

    /** True if pattern has inline flag groups like (?i) (?s) (?-i) — but not non-capturing (?:...). */
    private static boolean hasInlineFlag(String pat) {
        for (int i = 0; i + 2 < pat.length(); i++) {
            if (pat.charAt(i) == '(' && pat.charAt(i + 1) == '?') {
                char c = pat.charAt(i + 2);
                // (?: is non-capturing; (?< (?P are named groups; anything else is flags
                if (c != ':' && c != '<' && c != 'P') return true;
            }
        }
        return false;
    }

    private static boolean hasMultibyte(String s) {
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) >= 0x80) return true;
        return false;
    }

    private static String safeMsg(Throwable e) {
        String m = e.getMessage();
        if (m == null) return "";
        // truncate long messages
        int nl = m.indexOf('\n');
        if (nl > 0) m = m.substring(0, nl);
        return m.length() > 160 ? m.substring(0, 160) + "..." : m;
    }

    private static String esc(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 4);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\n' -> sb.append("\\n");
                case '\t' -> sb.append("\\t");
                case '\r' -> sb.append("\\r");
                default -> {
                    if (c < 0x20 || c > 0x7e) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.toString();
    }

    /** Parse one column result field: "-" => null, else space-separated "lo-hi" pairs (or "-" for unmatched). */
    private static int[] parseResult(String res) {
        if (res.equals("-")) return null;
        String[] parts = res.split(" ");
        int[] out = new int[parts.length * 2];
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i];
            if (p.equals("-")) {
                out[i * 2] = -1;
                out[i * 2 + 1] = -1;
            } else {
                int k = p.indexOf('-');
                out[i * 2] = Integer.parseInt(p.substring(0, k));
                out[i * 2 + 1] = Integer.parseInt(p.substring(k + 1));
            }
        }
        return out;
    }

    /** Subset of Go's strconv.Unquote for double-quoted strings. */
    private static String goUnquote(String s) {
        if (!s.startsWith("\"") || !s.endsWith("\"") || s.length() < 2) return s;
        String body = s.substring(1, s.length() - 1);
        StringBuilder out = new StringBuilder(body.length());
        int i = 0;
        while (i < body.length()) {
            char c = body.charAt(i++);
            if (c != '\\') { out.append(c); continue; }
            if (i >= body.length()) break;
            char e = body.charAt(i++);
            switch (e) {
                case 'a' -> out.append((char) 7);
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'v' -> out.append((char) 11);
                case '\\' -> out.append('\\');
                case '"' -> out.append('"');
                case 'x' -> {
                    if (i + 1 < body.length()) {
                        out.append((char) Integer.parseInt(body.substring(i, i + 2), 16));
                        i += 2;
                    }
                }
                case '0', '1', '2', '3', '4', '5', '6', '7' -> {
                    int digits = 1;
                    while (digits < 3 && i < body.length() && body.charAt(i) >= '0' && body.charAt(i) <= '7') {
                        digits++; i++;
                    }
                    out.append((char) Integer.parseInt(body.substring(i - digits, i), 8));
                }
                default -> out.append(e);
            }
        }
        return out.toString();
    }

    private static String bucketDefs() {
        return String.join("\n",
                "  COMPILE.*            parser rejects pattern (feature not yet supported)",
                "  ALT_AMBIGUITY_LONGEST  our engine is leftmost-longest; Perl(leftmost-first) differs",
                "                       on alternation overlap like (a|ab). Not a bug — a semantic choice.",
                "  FAIL.anchors         ^ / $ not enforced at match time",
                "  FAIL.negated-class   [^...] blows up alphabet enumeration",
                "  FAIL.word-boundary-silent  \\b parsed as literal 'b' instead of being rejected",
                "  FAIL.flags           (?i) (?s) inline-flag handling differs from re2j",
                "  FAIL.alternation     alternation correctness bug (not ambiguity)",
                "  FAIL.tag-timing      capture-group offsets wrong under repetition (TDFA(1) issue)",
                "  FAIL.empty-alt-branch  empty branch (a|) mishandled",
                "  FAIL.other           uncategorized — investigate"
        );
    }
}
