import io.github.jemmix.tdfa.Pattern;
import io.github.jemmix.tdfa.tdfa.TdfaRunner;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JDK 8 runtime smoke test for the Java 8 bytecode floor (CI job
 * java8-floor; docs/REVIEW-2026-09.md §1b). Compiled AND run on a real JDK 8
 * against the shipped jars. Exercises every shipped module: facade, core
 * (interpreter via TdfaRunner::new), the ASM tier (default compile path —
 * generated classes must load and run on 8; they are emitted V1_8), pikesim,
 * and the pinned Unicode 6.0 data module. Exits non-zero on any mismatch.
 *
 * Deliberately plain Java 8: no var, no records, no factory methods.
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

        // --- ASM tier (default): generated per-pattern class on JDK 8 ---
        Pattern p = Pattern.compile("(\\w+)-(\\d+)\\b");
        io.github.jemmix.tdfa.PatternMatcher pm = p.matcher("order-77!");
        expect("asm find", pm.find(), true);
        expect("asm group", "order".equals(pm.group(1)) && "77".equals(pm.group(2)), true);

        // --- interpreter tier (core) ---
        Pattern ip = Pattern.compile("(\\w+)-(\\d+)", 0, TdfaRunner::new);
        expect("interpreter find", ip.matcher("id-42!").find(), true);

        // --- pikesim (PikeVM reference engine; its own matcher surface) ---
        io.github.jemmix.tdfa.sim.PikeSim.PikeMatcher sim =
                io.github.jemmix.tdfa.sim.PikeSim.compile("a(b*)c").matcher("abbbc");
        expect("pikesim matches", sim.find() && "abbbc".equals(sim.group()), true);

        // --- pinned Unicode 6.0 module + property classes ---
        Pattern up = Pattern.compile("\\p{L}+", 0, null,
                io.github.jemmix.tdfa.unicode.v6_0.Unicode6_0.provider());
        expect("unicode6 L+", up.matcher("Gr\u00fc\u00dfe").matches(), true);
        expect("unicode6 digit", up.matcher("123").matches(), false);

        // --- named groups ---
        Pattern np = Pattern.compile("(?<year>\\d{4})-(?<month>\\d{2})");
        io.github.jemmix.tdfa.PatternMatcher nm = np.matcher("2026-09-05 and 1999-12-31");
        Map<String, String> got = new LinkedHashMap<String, String>();
        while (nm.find()) {
            got.put(nm.group("year"), nm.group("month"));
        }
        expect("findAll count", got.size(), 2);
        expect("named group", "09".equals(got.get("2026")) && "12".equals(got.get("1999")), true);

        // --- case-insensitive + DOTALL flags ---
        Pattern ci = Pattern.compile("start.*end", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        expect("ci+dotall", ci.matcher("START\nmiddle\nEND").matches(), true);

        if (failures > 0) {
            System.err.println("SMOKE8 FAILURES: " + failures);
            System.exit(1);
        }
        System.out.println("SMOKE8 OK");
    }

    private static void expect(String what, Object actual, Object expected) {
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        if (!ok) {
            failures++;
            System.err.println("FAIL " + what + ": expected " + expected + ", got " + actual);
        }
    }
}
