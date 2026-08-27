package io.github.jemmix.tdfa.parity;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import io.github.jemmix.tdfa.core.Matcher;
import io.github.jemmix.tdfa.core.RegexEngineFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Glenn Fowler's testregex corpus (vendored, ISC-style AT&T license — see
 * {@code testregex/LICENSE} resource) as a POSIX-submatch differential:
 * <b>hard gate = our engine equals re2j 1.8 in {@code LONGEST_MATCH} mode on
 * every runnable ERE spec; the corpus' own POSIX expectations are reported
 * (soft) for insight</b> — our contract is re2j drop-in parity, and Fowler's
 * X/Open leftmost-longest submatch rules are stricter than RE2-family
 * behavior in documented places (see {@code docs/re-interpretation.html} in
 * the vendored tree).
 *
 * <p>Files: {@code basic}, {@code forcedassoc}, {@code leftassoc},
 * {@code nullsubexpr}, {@code repetition} (spec files).
 * {@code rightassoc} is EXCLUDED — it is the categorization complement
 * ("left-assoc:pass-all right-assoc:pass-none" per its own NOTE): a
 * left-associative-conformant engine must NOT match its expectations.
 * {@code categorize} contributes only probe lines (control-prefixed), which
 * the parser skips.
 *
 * <p>Supported spec surface (per the harness INPUT FORMAT, testregex.c):
 * ERE mode {@code E}; suffix flags {@code a l r y} (implicit anchors),
 * {@code i} (icase), {@code z} (null subexpressions ok), {@code $} (expand C
 * escapes in pattern/subject), digit after E (nmatch limit — group count
 * cap), {@code SAME} pattern reuse, {@code NULL}/NIL subject, RE_DUP_MAX
 * expansion (=255), outcomes as span tuples {@code (m,n)} with {@code ?}
 * endpoints (unmatched group), {@code NOMATCH}, or POSIX error names.
 * Skipped: BRE/SRE/KRE/ARE/LRE modes, unsupported suffix flags, probe and
 * control lines.
 */
class TestregexFowlerTest {

    /** RE_DUP_MAX substitution (X/Open minimum 255; re2j repeat cap 1000). */
    private static final int RE_DUP_MAX = 255;

    private static final io.github.jemmix.tdfa.unicode.UnicodeDataProvider UNICODE =
            com.google.re2j.Re2jUnicodeProvider.INSTANCE;

    // ---- corpus ----

    record Spec(String file, int lineNo, String flags, String regex, String subject,
                String outcome, String note) { }

    static List<Spec> corpus() {
        List<Spec> specs = new ArrayList<>();
        String prevRegex = null;
        for (String file : List.of("testregex/basic.dat", "testregex/forcedassoc.dat",
                "testregex/leftassoc.dat", "testregex/nullsubexpr.dat", "testregex/repetition.dat")) {
            try (InputStream in = TestregexFowlerTest.class.getClassLoader().getResourceAsStream(file)) {
                assertThat(in).as("resource %s (run ./gradlew prepareVendor)", file).isNotNull();
                BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                String line;
                int lineNo = 0;
                while ((line = r.readLine()) != null) {
                    lineNo++;
                    String trimmed = line.trim();
                    if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("NOTE")
                            || trimmed.startsWith(":")) continue;
                    String[] f = line.split("\t+");
                    String flags = f[0];
                    // specs only: mode letter first, optional suffixes; no control prefixes
                    if (flags.isEmpty() || !isSpecFlags(flags)) continue;
                    String regex = f.length > 1 ? f[1] : "";
                    if (regex.equals("SAME")) {
                        if (prevRegex == null) continue;
                        regex = prevRegex;
                    } else {
                        prevRegex = regex;
                    }
                    String subject = f.length > 2 ? f[2] : "";
                    String outcome = f.length > 3 ? f[3] : "";
                    String note = f.length > 4 ? f[4] : "";
                    if (subject.equals("NIL") || outcome.isEmpty()) continue;
                    if (regex.contains("RE_DUP_MAX") || subject.contains("RE_DUP_MAX")) {
                        regex = regex.replace("RE_DUP_MAX", String.valueOf(RE_DUP_MAX));
                        subject = subject.replace("RE_DUP_MAX", String.valueOf(RE_DUP_MAX));
                    }
                    specs.add(new Spec(file, lineNo, flags, regex, subject, outcome, note));
                }
            } catch (IOException e) {
                throw new IllegalStateException("failed reading " + file, e);
            }
        }
        return specs;
    }

    /** E-mode with supported suffixes only: {@code E [a l r y i z $]* [digit]*}. */
    static boolean isSpecFlags(String flags) {
        if (flags.charAt(0) != 'E') return false;
        String rest = flags.substring(1).replaceAll("[0-9]", "");
        return rest.matches("[alryiz$]*");
    }

    // ---- running ----

    /** One parsed expectation; span[k] = {start,end} with -1 for '?'. */
    record Expectation(boolean match, int[][] spans, boolean isError) {
        static Expectation noMatch() { return new Expectation(false, null, false); }
        static Expectation error() { return new Expectation(false, null, true); }
    }

    static Expectation parseOutcome(String outcome) {
        if (outcome.equals("NOMATCH") || outcome.equals("NULL")) return Expectation.noMatch();
        if (!outcome.startsWith("(")) {
            // POSIX error name (BADBR, ECOLLATE, ...) — possibly with |variants
            return Expectation.error();
        }
        List<int[]> spans = new ArrayList<>();
        int i = 0;
        while (i < outcome.length() && outcome.charAt(i) == '(') {
            int close = outcome.indexOf(')', i);
            if (close < 0) return null;
            String body = outcome.substring(i + 1, close);
            String[] parts = body.split(",");
            if (parts.length != 2) return null;
            int[] span = new int[2];
            for (int k = 0; k < 2; k++) {
                if (parts[k].equals("?")) span[k] = -1;
                else {
                    try { span[k] = Integer.parseInt(parts[k].trim()); }
                    catch (NumberFormatException e) { return null; }
                }
            }
            spans.add(span);
            i = close + 1;
        }
        return new Expectation(true, spans.toArray(new int[0][]), false);
    }

    /** Expand C escapes in pattern/subject when the '$' flag is present. */
    static String expandEscapes(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char e = s.charAt(++i);
                switch (e) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case 'f' -> sb.append('\f');
                    case 'v' -> sb.append('\u000B');
                    case 'a' -> sb.append('\u0007');
                    case 'b' -> sb.append('\b');
                    case '\\' -> sb.append('\\');
                    default -> { return null; } // unsupported escape — skip the line
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Apply implicit-anchor and icase flags by wrapping/flagging the regex. */
    static String applyFlags(String flags, String regex, StringBuilder re2jFlagsOut) {
        if (flags.contains("i")) re2jFlagsOut.append('i');
        String wrapped = regex;
        // a = both, l/y = left, r = right
        boolean left = flags.contains("a") || flags.contains("l") || flags.contains("y");
        boolean right = flags.contains("a") || flags.contains("r");
        if (left) wrapped = "^(?:" + wrapped + ")";
        if (right) wrapped = "(?:" + wrapped + ")$";
        if (flags.contains("i")) wrapped = "(?i)" + wrapped;
        return wrapped;
    }

    static int groupCap(String flags) {
        String digits = flags.replaceAll("[^0-9]", "");
        return digits.isEmpty() ? Integer.MAX_VALUE : Integer.parseInt(digits);
    }

    /** Full span array from our engine (LONGEST_MATCH), or null on no-match. */
    static int[][] tdfaSpans(String regex, String subject, int cap, RegexEngineFactory factory) {
        io.github.jemmix.tdfa.Pattern p = io.github.jemmix.tdfa.Pattern.compile(
                regex, io.github.jemmix.tdfa.Pattern.LONGEST_MATCH, factory, UNICODE);
        Matcher m = p.matcher(subject);
        if (!m.find()) return null;
        int gc = Math.min(m.groupCount(), cap);
        int[][] out = new int[gc + 1][];
        for (int g = 0; g <= gc; g++) {
            int s = m.start(g), e = m.end(g);
            out[g] = s < 0 ? new int[]{-1, -1} : new int[]{s, e};
        }
        return out;
    }

    /** Full span array from re2j (LONGEST_MATCH), or null on no-match. */
    static int[][] re2jSpans(String regex, String subject, int cap, boolean icase) {
        int flags = com.google.re2j.Pattern.LONGEST_MATCH
                | (icase ? com.google.re2j.Pattern.CASE_INSENSITIVE : 0);
        com.google.re2j.Matcher m = com.google.re2j.Pattern.compile(regex, flags).matcher(subject);
        if (!m.find()) return null;
        int gc = Math.min(m.groupCount(), cap);
        int[][] out = new int[gc + 1][];
        for (int g = 0; g <= gc; g++) {
            int s, e;
            try { s = m.start(g); e = m.end(g); }
            catch (IllegalStateException ex) { s = -1; e = -1; }
            out[g] = s < 0 ? new int[]{-1, -1} : new int[]{s, e};
        }
        return out;
    }

    /** Soft DAT-vs-ours divergence stats, printed once per JVM. */
    static final Map<String, Integer> DAT_DIVERGENCE = new ConcurrentHashMap<>();
    static volatile boolean reported = false;

    @ParameterizedTest(name = "{0}:{1}")
    @MethodSource("specsProvider")
    void fowlerSpec(String file, int lineNo, RegexEngineFactory factory, Spec spec) {
        boolean icase = spec.flags().contains("i");
        String regex = spec.regex();
        String subject = spec.subject().equals("NULL") ? "" : spec.subject();
        if (spec.flags().contains("$")) {
            String r = expandEscapes(regex);
            String s = expandEscapes(subject);
            if (r == null || s == null) return;
            regex = r; subject = s;
        }
        StringBuilder re2FlagStr = new StringBuilder();
        String wrapped = applyFlags(spec.flags(), regex, re2FlagStr);
        int cap = groupCap(spec.flags());
        Expectation exp = parseOutcome(spec.outcome());

        // --- compile both; rejection parity is a hard gate ---
        boolean re2jThrows, oursThrows;
        try {
            int flags = com.google.re2j.Pattern.LONGEST_MATCH
                    | (icase ? com.google.re2j.Pattern.CASE_INSENSITIVE : 0);
            com.google.re2j.Pattern.compile(wrapped, flags);
            re2jThrows = false;
        } catch (RuntimeException e) { re2jThrows = true; }
        try {
            io.github.jemmix.tdfa.Pattern.compile(wrapped,
                    io.github.jemmix.tdfa.Pattern.LONGEST_MATCH, factory, UNICODE);
            oursThrows = false;
        } catch (RuntimeException e) { oursThrows = true; }
        assertThat(oursThrows)
                .as("%s:%d re2j-throws=%s regex=\"%s\"", file, lineNo, re2jThrows, wrapped)
                .isEqualTo(re2jThrows);
        if (re2jThrows) return; // both reject — parity holds; DAT error-code exactness is POSIX scope

        // --- match both; span parity is the hard gate ---
        int[][] expected = re2jSpans(wrapped, subject, cap, icase);
        int[][] actual = tdfaSpans(wrapped, subject, cap, factory);
        assertThat(actual)
                .as("%s:%d regex=\"%s\" subject=\"%s\" re2j=%s", file, lineNo, wrapped, subject,
                        Arrays.deepToString(expected))
                .isEqualTo(expected);

        // --- soft: compare against Fowler's own POSIX expectation ---
        if (exp != null) {
            boolean datAgrees;
            if (exp.isError()) datAgrees = oursThrows;
            else if (!exp.match()) datAgrees = actual == null;
            else if (actual == null) datAgrees = false;
            else datAgrees = spansMatchDat(actual, exp.spans());
            if (!datAgrees) {
                DAT_DIVERGENCE.merge(spec.file() + " " + (exp.isError() ? "error" :
                        exp.match() ? "spans" : "nomatch"), 1, Integer::sum);
            }
        }
    }

    /** Compare our spans to DAT tuples ('?' endpoint = -1 anywhere matches -1). */
    static boolean spansMatchDat(int[][] actual, int[][] dat) {
        int n = Math.min(actual.length, dat.length);
        for (int g = 0; g < n; g++) {
            int[] a = actual[g], d = dat[g];
            if (d[0] == -1 || d[1] == -1) {
                if (a[0] != -1 || a[1] != -1) {
                    // DAT says unmatched; only a full -1,-1 agrees
                    return false;
                }
            } else if (a[0] != d[0] || a[1] != d[1]) {
                return false;
            }
        }
        return true;
    }

    @SuppressWarnings("unused")
    static void printDivergenceSummary() {
        if (reported) return;
        reported = true;
        if (!DAT_DIVERGENCE.isEmpty()) {
            System.out.println("[fowler] DAT-vs-ours divergences (informational; hard gate is re2j parity):");
            DAT_DIVERGENCE.entrySet().stream()
                    .sorted(Map.Entry.comparingByValue())
                    .forEach(e -> System.out.println("  " + e.getKey() + ": " + e.getValue()));
        }
    }

    record SpecArg(String file, int lineNo, RegexEngineFactory factory, Spec spec) { }

    static Stream<Object[]> specsProvider() {
        List<Spec> specs = corpus();
        List<Object[]> out = new ArrayList<>();
        for (Spec s : specs) {
            for (RegexEngineFactory f : Re2jOracle.engineFactories().toList()) {
                out.add(new Object[]{s.file(), s.lineNo(), f, s});
            }
        }
        return out.stream();
    }
}
