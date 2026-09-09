import io.github.jemmix.tdfa.asm.TdfaAsmBackend;
import io.github.jemmix.tdfa.core.MatchResult;
import io.github.jemmix.tdfa.core.RegexEngine;
import io.github.jemmix.tdfa.tdfa.Tdfa;
import io.github.jemmix.tdfa.tdfa.TdfaRunner;
import io.github.jemmix.tdfa.tnfa.Tnfa;

/**
 * JDK 8 runtime smoke for the Java 8 floor modules (CI job jars-and-tests;
 * docs/REVIEW-2026-09.md §1d). Compiled AND run on a real JDK 8 against the
 * core and asm jars — the two modules that ship Java 8 bytecode. The facade,
 * pikesim, and unicode data modules ship Java 25 bytecode and cannot load
 * on 8 by design; this smoke therefore drives the core-tier API directly:
 * parse/TNFA/TDFA, the table interpreter (TdfaRunner), and the ASM tier
 * (generated per-pattern classes must load and run on 8; they are emitted
 * V1_8). Exits non-zero on any mismatch.
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
        RegexEngine generated = TdfaAsmBackend.generate(tdfa).engine();
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
        MatchResult m = e.match("id-42!", 0);
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
