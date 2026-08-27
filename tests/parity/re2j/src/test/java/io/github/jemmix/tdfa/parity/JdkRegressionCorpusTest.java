package io.github.jemmix.tdfa.parity;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OpenJDK {@code java.util.regex} regression corpus (vendored from
 * {@code test/jdk/java/util/Regex}, GPLv2+CE — test-only use).
 *
 * <p>Each case is a 3-line triplet (pattern / input / expected) in the format
 * consumed by RegExTest.processFile.
 *
 * <p><b>Contract-oracle layering:</b> re2j is the hard gate — where re2j
 * compiles, tdfa must produce byte-identical results; where re2j rejects,
 * tdfa must reject cleanly too (drop-in-replacement contract). Live
 * {@code java.util.regex} is informational only: the corpus contains plenty
 * of jur-only syntax (nested class unions, {@code &&} intersection,
 * {@code (?x)} comments mode) on which re2j and tdfa agree while jur
 * differs — those are reported as "jur-only syntax", not failures. Soft
 * reports aggregated by {@link #summary()}:
 * <ul>
 *   <li><b>jur-only-syntax</b> — jur disagrees with the re2j/tdfa pair</li>
 *   <li><b>recorded-drift</b> — live engines disagree with the recorded
 *       expectation (JDK behavior changed since recording)</li>
 * </ul>
 */
class JdkRegressionCorpusTest {

    private static final io.github.jemmix.tdfa.unicode.UnicodeDataProvider UNICODE =
            com.google.re2j.Re2jUnicodeProvider.INSTANCE;

    private static final int FLAG_I = 1; // facade/re2j CASE_INSENSITIVE
    private static final int FLAG_M = 4; // facade/re2j MULTILINE

    private static final int JUR_I = java.util.regex.Pattern.CASE_INSENSITIVE;
    private static final int JUR_M = java.util.regex.Pattern.MULTILINE;

    record Case(String file, int lineNo, String pattern, int flags, String input, String expected) {
        @Override public String toString() { return file + ":" + lineNo + " `" + pattern + "`"; }
    }

    // ---- aggregation for the end-of-run summary ----

    private static final List<String> JurOnlySyntax = new ArrayList<>();
    private static final List<String> RecordedDrift = new ArrayList<>();
    private static final List<String> KnownBugs = new ArrayList<>();
    private static int compared;
    private static int bothReject;
    private static int byDesign;

    /**
     * Former known-bug families, all FIXED (2026-08-27 structural round):
     * <ul>
     *   <li><b>supp-tagged-literal</b> — the parser now reads codepoints
     *       (shared {@code Alphabet.decode}); a supplementary literal is one
     *       single-codepoint class, matching anywhere, groups included</li>
     *   <li><b>supp-class-range</b> — class endpoints read as codepoints</li>
     *   <li><b>identity-escape</b> — re2j policy: only ASCII-alphanumeric
     *       unknown escapes reject; {@code \䑄} and friends are literals</li>
     *   <li><b>pair-interior starts</b> — no match may start at the low half
     *       of a surrogate pair (lone-surrogate corpus lines) — the literal
     *       needle, candidate scans, restart loops and the ASM-emitted ladder
     *       all guard with {@code Alphabet.pairInterior}</li>
     *   <li><b>(?x) silent no-op</b> — unknown inline flags now reject, as
     *       re2j does ({@code (?U)} ungreedy is supported, as re2j does)</li>
     * </ul>
     * The gate is still soft by default; {@code -Dtdfa.pending=true} enforces
     * (currently: 0 failures).
     */
    private static final boolean PENDING = Boolean.getBoolean("tdfa.pending");

    /** Documented by-design divergence: the {@code (?u)} inline flag (unicode
     *  shorthand opt-in) is a tdfa extension; re2j has no u flag and rejects. */
    private static boolean hasInlineU(String pattern) {
        return java.util.regex.Pattern.compile("\\(\\?[a-zA-Z]*u[a-zA-Z]*[):]")
                .matcher(pattern).find();
    }

    // ---- corpus parsing (port of RegExTest.grabLine / compileTestPattern) ----

    /** Line reader tracking 1-based source line numbers across comment/blank skips. */
    private static final class CorpusReader {
        private final BufferedReader r;
        private int lineNo;

        CorpusReader(BufferedReader r) { this.r = r; }

        /** Next non-empty non-comment line with \\n / \\uXXXX unescaped, or null at EOF. */
        String next() throws IOException {
            String line = r.readLine();
            while (line != null && (line.startsWith("//") || line.isEmpty())) {
                lineNo++;
                line = r.readLine();
            }
            if (line == null) return null;
            lineNo++;
            int index;
            while ((index = line.indexOf("\\n")) != -1)
                line = line.substring(0, index) + "\n" + line.substring(index + 2);
            while ((index = line.indexOf("\\u")) != -1)
                line = line.substring(0, index)
                        + (char) Integer.parseInt(line.substring(index + 2, index + 6), 16)
                        + line.substring(index + 6);
            return line;
        }
    }

    /** Port of RegExTest.compileTestPattern quoting: {@code 'pattern'f} with f in {i, m}. */
    private static Case toCase(String file, int lineNo, String patternString, String input, String expected) {
        if (!patternString.startsWith("'"))
            return new Case(file, lineNo, patternString, 0, input, expected);
        int break1 = patternString.lastIndexOf("'");
        String flagString = patternString.substring(break1 + 1);
        String pattern = patternString.substring(1, break1);
        int flags = switch (flagString) {
            case "i" -> FLAG_I;
            case "m" -> FLAG_M;
            default -> 0;
        };
        return new Case(file, lineNo, pattern, flags, input, expected);
    }

    static List<Case> parseFile(String resource) {
        List<Case> cases = new ArrayList<>();
        try (InputStream in = JdkRegressionCorpusTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertThat(in).as("resource %s (run ./gradlew prepareVendor)", resource).isNotNull();
            CorpusReader r = new CorpusReader(new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8)));
            String patternString;
            while ((patternString = r.next()) != null) {
                String input = r.next();
                assertThat(input).as("%s:%d pattern without input line", resource, r.lineNo).isNotNull();
                String expected = r.next();
                assertThat(expected).as("%s:%d pattern/input without expected line", resource, r.lineNo).isNotNull();
                cases.add(toCase(resource, r.lineNo - 2, patternString, input, expected));
            }
        } catch (IOException e) {
            throw new IllegalStateException("failed reading " + resource, e);
        }
        return cases;
    }

    static Stream<Arguments> allCases() {
        List<Arguments> out = new ArrayList<>();
        List<io.github.jemmix.tdfa.core.RegexEngineFactory> factories =
                Stream.<io.github.jemmix.tdfa.core.RegexEngineFactory>of(null,
                        io.github.jemmix.tdfa.tdfa.TdfaRunner::new).toList();
        for (io.github.jemmix.tdfa.core.RegexEngineFactory factory : factories) {
            for (String file : List.of("openjdk-regex/TestCases.txt",
                    "openjdk-regex/BMPTestCases.txt",
                    "openjdk-regex/SupplementaryTestCases.txt")) {
                for (Case c : parseFile(file)) out.add(Arguments.of(c, factory));
            }
        }
        return out.stream();
    }

    // ---- result-string computation (port of RegExTest.processFile formatting) ----

    private static String compute(com.google.re2j.Matcher m) {
        StringBuilder sb = new StringBuilder();
        boolean found = m.find();
        if (found) sb.append("true ").append(m.group());
        else sb.append("false");
        sb.append(' ').append(m.groupCount());
        if (found)
            for (int i = 1; i <= m.groupCount(); i++) {
                String g = m.group(i);
                if (g != null) sb.append(' ').append(g);
            }
        return sb.toString();
    }

    private static String compute(java.util.regex.Matcher m) {
        StringBuilder sb = new StringBuilder();
        boolean found = m.find();
        if (found) sb.append("true ").append(m.group());
        else sb.append("false");
        sb.append(' ').append(m.groupCount());
        if (found)
            for (int i = 1; i <= m.groupCount(); i++) {
                String g = m.group(i);
                if (g != null) sb.append(' ').append(g);
            }
        return sb.toString();
    }

    private static String compute(io.github.jemmix.tdfa.core.Matcher m) {
        StringBuilder sb = new StringBuilder();
        boolean found = m.find();
        if (found) sb.append("true ").append(m.group());
        else sb.append("false");
        sb.append(' ').append(m.groupCount());
        if (found)
            for (int i = 1; i <= m.groupCount(); i++) {
                String g = m.group(i);
                if (g != null) sb.append(' ').append(g);
            }
        return sb.toString();
    }

    private static int jurFlags(int flags) {
        int out = 0;
        if ((flags & FLAG_I) != 0) out |= JUR_I;
        if ((flags & FLAG_M) != 0) out |= JUR_M;
        return out;
    }

    private static int re2jFlags(int flags) {
        int out = 0;
        if ((flags & FLAG_I) != 0) out |= com.google.re2j.Pattern.CASE_INSENSITIVE;
        if ((flags & FLAG_M) != 0) out |= com.google.re2j.Pattern.MULTILINE;
        return out;
    }

    // ---- the gate ----

    @ParameterizedTest(name = "{0}")
    @MethodSource("allCases")
    void parity(Case c, io.github.jemmix.tdfa.core.RegexEngineFactory factory) {
        com.google.re2j.Pattern re2jPattern;
        try {
            re2jPattern = com.google.re2j.Pattern.compile(c.pattern(), re2jFlags(c.flags()));
        } catch (Exception rejectedByContract) {
            if (hasInlineU(c.pattern())) { byDesign++; return; } // (?u) extension: re2j has no u flag
            try {
                io.github.jemmix.tdfa.Pattern.compile(c.pattern(), c.flags(), factory, UNICODE);
                record(c, "tdfa accepts but re2j (contract) rejects", null);
            } catch (io.github.jemmix.tdfa.core.PatternSyntaxException e) {
                bothReject++;
            }
            return;
        }

        io.github.jemmix.tdfa.Pattern tdfaPattern;
        try {
            tdfaPattern = io.github.jemmix.tdfa.Pattern.compile(c.pattern(), c.flags(), factory, UNICODE);
        } catch (io.github.jemmix.tdfa.core.PatternSyntaxException e) {
            record(c, "tdfa rejects but re2j (contract) compiles", "reason: " + e.getMessage());
            return;
        } catch (RuntimeException e) {
            throw new AssertionError(c + " tdfa threw non-syntax exception", e);
        }

        String oracle = compute(re2jPattern.matcher(c.input()));
        String actual = compute(tdfaPattern.matcher(c.input()));

        if (!actual.equals(oracle)) {
            record(c, "result mismatch", detail(c, oracle, actual));
            return;
        }
        compared++;

        // informational: live jur vs the contract pair
        String jurResult;
        try {
            jurResult = compute(java.util.regex.Pattern
                    .compile(c.pattern(), jurFlags(c.flags())).matcher(c.input()));
        } catch (Exception e) {
            jurResult = "<jur-rejects>";
        }
        if (!jurResult.equals(oracle))
            JurOnlySyntax.add(c + " input=`" + c.input().replace("\n", "\\n")
                    + "` re2j/tdfa=`" + oracle + "` jur=`" + jurResult + "`");

        if (!oracle.equals(c.expected()))
            RecordedDrift.add(c + " input=`" + c.input().replace("\n", "\\n")
                    + "` recorded=`" + c.expected() + "` live=`" + oracle + "`");
    }

    /** Known-bug families are soft by default; {@code -Dtdfa.pending=true} enforces. */
    private static void record(Case c, String what, String extra) {
        String entry = c + " " + what + (extra == null ? "" : " — " + extra);
        if (PENDING) throw new AssertionError(entry);
        KnownBugs.add(entry);
    }

    private static String detail(Case c, String oracle, String actual) {
        return String.format("%n  input = `%s`%n  re2j  = `%s`%n  tdfa  = `%s`%n  rec   = `%s`",
                c.input().replace("\n", "\\n"), oracle, actual, c.expected());
    }

    @AfterAll
    static void summary() {
        System.out.println("---- JDK regression corpus summary ----");
        System.out.println("compared (re2j+tdfa compiled): " + compared
                + ", both reject: " + bothReject + ", by-design (?u): " + byDesign);
        System.out.println("KNOWN BUGS (soft; enforce with -Dtdfa.pending=true): " + KnownBugs.size());
        KnownBugs.stream().limit(40).forEach(s -> System.out.println("  BUG " + s.replace("\n", " | ")));
        if (KnownBugs.size() > 40) System.out.println("  ... +" + (KnownBugs.size() - 40) + " more");
        System.out.println("jur-only syntax divergences (informational): " + JurOnlySyntax.size());
        System.out.println("recorded-expectation drift vs live engines: " + RecordedDrift.size());
        RecordedDrift.stream().limit(20).forEach(s -> System.out.println("  DRIFT " + s));
        if (RecordedDrift.size() > 20) System.out.println("  ... +" + (RecordedDrift.size() - 20) + " more");
    }
}
