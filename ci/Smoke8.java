import io.github.jemmix.tdfa.Pattern;
import io.github.jemmix.tdfa.PatternMatcher;
import io.github.jemmix.tdfa.asm.TdfaAsmBackend;
import io.github.jemmix.tdfa.core.MatchResult;
import io.github.jemmix.tdfa.core.MatchScratch;
import io.github.jemmix.tdfa.core.RegexEngine;
import io.github.jemmix.tdfa.tdfa.Tdfa;
import io.github.jemmix.tdfa.tdfa.TdfaRunner;
import io.github.jemmix.tdfa.tnfa.Tnfa;

/**
 * JDK 8 runtime smoke for the Java 8 floor modules (CI job jars-and-tests;
 * docs/REVIEW-2026-09.md §1d). Compiled AND run on a real JDK 8 against the
 * core, asm, and facade jars — the three modules that ship Java 8 bytecode.
 * The facade's only runtime dependencies are core and asm (plus asm 9.9.1
 * itself, major 49), so the whole public API loads and runs on 8: the user
 * tier (Pattern/PatternMatcher — default engine path generates the
 * per-pattern ASM shell on the JDK 8 runtime, emitted V1_8) plus the
 * core-tier pipeline directly (parse/TNFA/TDFA, the table interpreter
 * TdfaRunner). pikesim and the unicode data modules ship Java 25 bytecode
 * and cannot load on 8 by design. Exits non-zero on any mismatch.
 *
 * <p>Deliberately plain Java 8: no var, no records, no factory methods.
 */
public class Smoke8 {

    private static int failures = 0;

    public static void main(String[] args) {
        System.out.println("java.version = " + System.getProperty("java.version"));
        // CI runs this on a real JDK 8; the guard makes any accidental
        // modern-JVM run LOUD. -Dsmoke8.relax=true is for local dry-runs only.
        boolean relaxed = Boolean.getBoolean("smoke8.relax");
        if (!relaxed && !System.getProperty("java.specification.version").startsWith("1.8")) {
            throw new IllegalStateException("this smoke must run on a JDK 8 runtime");
        }

        // Core-tier pipeline: parse -> TNFA -> TDFA (leftmost-first).
        Tnfa nfa = Tnfa.compile("(\\w+)-(\\d+)");
        Tdfa tdfa = Tdfa.compile(nfa);
        expect("groupCount", tdfa.groupCount(), 2);

        // --- interpreter tier (core) ---
        smoke(new TdfaRunner(tdfa), "interp");

        // --- ASM tier: generated per-pattern class on a JDK 8 runtime ---
        RegexEngine generated = TdfaAsmBackend.generate(tdfa);
        smoke(generated, "asm");

        // --- case folding path ((?i) inline-flag prefix, core tables) ---
        RegexEngine ci = new TdfaRunner(Tdfa.compile(Tnfa.compile("(?i)stra\u00dfe")));
        expect("ci real", ci.matches("STRA\u00dfE"), true);
        expect("ci neg", ci.matches("strasse"), false);

        // --- findAll iteration (default method over match()) ---
        int count = 0;
        for (MatchResult m : generated.findAll("a-1 b-22 c-333")) {
            count++;
        }
        expect("findAll count", count, 3);

        // --- facade tier: the public Pattern API on a JDK 8 runtime ---
        // Default compile path emits the per-pattern ASM shell HERE, inside
        // Pattern.compile() — so this also proves generated shells link and
        // run against the Java 8 facade classes.
        Pattern p = Pattern.compile("(\\w+)-(\\d+)");
        PatternMatcher m = p.matcher("order-77!");
        expect("facade find", m.find(), true);
        expect("facade g1", m.group(1), "order");
        expect("facade g2", m.group(2), "77");
        expect("facade matches", Pattern.matches("[a-z]+-\\d+", "abc-9"), true);
        expect("facade matches neg", Pattern.matches("[a-z]+-\\d+", "x abc-9"), false);
        expect("facade programSize", p.programSize() > 0, true);
        // Same output java.util.regex gives: the matches are consumed as
        // separators, so the segments are "", " ", " " (trailing "" dropped).
        String[] parts = p.split("a-1 b-22 c-333");
        expect("facade split len", parts.length, 3);
        expect("facade split [0]", parts.length > 0 ? parts[0] : null, "");
        expect("facade split [1]", parts.length > 1 ? parts[1] : null, " ");
        expect("facade split [2]", parts.length > 2 ? parts[2] : null, " ");

        if (failures > 0) {
            System.err.println("SMOKE8 FAILURES: " + failures);
            System.exit(1);
        }
        System.out.println("SMOKE8 OK");
    }

    /** Same battery against both engine implementations. */
    private static void smoke(RegexEngine e, String tag) {
        expect(tag + " find", e.find("order-77!"), true);
        expect(tag + " find neg", e.find("no dash here"), false);
        expect(tag + " matches", e.matches("abc-9"), true);
        expect(tag + " matches neg", e.matches("x abc-9"), false);
        MatchResult m = e.match("id-42!", 0, new MatchScratch());
        expect(tag + " match nonnull", m != null, true);
        if (m != null) {
            expect(tag + " g1", sub("id-42!", m.start(1), m.end(1)), "id");
            expect(tag + " g2", sub("id-42!", m.start(2), m.end(2)), "42");
        }
        expect(tag + " programSize", e.programSize() > 0, true);
    }

    private static String sub(String s, int start, int end) {
        return s.substring(start, end);
    }

    private static void expect(String what, Object actual, Object expected) {
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        if (!ok) {
            failures++;
            System.err.println("FAIL " + what + ": expected " + expected + ", got " + actual);
        }
    }
}
