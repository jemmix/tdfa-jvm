import io.github.jemmix.tdfa.re2j.Pattern;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.LongSupplier;

/**
 * The wide-dispatch-index "Numbers" protocol probe (docs/wide-dispatch-index.md):
 * 16 KB shapes, interleaved min-of-5, ns/char, jur vs tdfa VM vs tdfa ASM.
 * Compile through the :re2j surface (the codepoint lane that routes
 * ORIGIN_SIM / triggerScan). Plain main, QuickBench-shaped measurement:
 * per-op auto-calibrated batches, ~300 ms warmup, 5 reps ~300 ms, min batch;
 * ops interleaved round-robin so sustained-load drift moves together.
 *
 * Usage: WideScanProbe <label>
 */
public final class WideScanProbe {

    static final int LEN = 1 << 14; // 16 KB shapes, per the doc protocol

    // ---- shapes (no ASCII 'x' anywhere except the match-at-end tail) ----
    /** Sparse letters: never two adjacent — jur stays a pure first-char probe. */
    static final String SPARSE = rep("\u04301\u04312\u04323\u04334\u04345\u04366\u04377\u04388 ", LEN);
    /** Dense letters: pure letters, no separators (jur quadratic by design). */
    static final String DENSE = rep("\u0430\u0431\u0432\u0433\u0434\u0435\u0436", LEN);
    /** Dense words with spaces — the quick-gate re2jScanWideNoMatch shape. */
    static final String DENSE_WORDS =
        rep("\u0441\u043b\u043e\u0432\u043e \u0437\u0430\u043f\u0430\u0441 \uD801\uDC00\uD801\uDC01 \u043e\u0431\u0441\u0442\u043e\u044f\u0442\u0435\u043b\u044c\u043d\u043e ", LEN);
    /** Non-BMP: pure supplementary (Deseret) letters. */
    static final String NONBMP = rep("\uD801\uDC00\uD801\uDC01\uD801\uDC02", LEN);
    /** unicode-\w dense: word chars only, no 'x'. */
    static final String WDENSE = rep("\u0441\u043b\u043e\u0432\u043e123\u0437\u0430\u043f\u0430\u04414567", LEN);
    /** Match at end: leading non-letter (kill at 0), letters, then the lone 'x'. */
    static String matchAtEndUnit() {
        StringBuilder b = new StringBuilder(LEN + 4);
        b.append(' ');
        while (b.length() < LEN - 1) {
            b.append("\u0430\u0431\u0432\u0433\u0434\u0435\u0436");
        }
        b.setLength(LEN - 1);
        b.append('x');
        return b.toString();
    }
    static final String MATCH_END = matchAtEndUnit();

    record Row(String name, String re, String in) {
    }

    static List<Row> rows() {
        List<Row> r = new ArrayList<>();
        r.add(new Row("L{2,}x no-match sparse", "\\p{L}{2,}x", SPARSE));
        r.add(new Row("L{2,}x no-match dense", "\\p{L}{2,}x", DENSE));
        r.add(new Row("L{2,}x no-match dense words", "\\p{L}{2,}x", DENSE_WORDS));
        r.add(new Row("L{2,}x no-match non-BMP", "\\p{L}{2,}x", NONBMP));
        r.add(new Row("unicode-w no-match dense", "[\\p{L}\\p{M}\\p{N}\\p{Pc}]{2,}x", WDENSE));
        r.add(new Row("L{2,}x match-at-end", "\\p{L}{2,}x", MATCH_END));
        return r;
    }

    record Op(String name, LongSupplier fn, long expect) {
    }

    public static void main(String[] args) throws Exception {
        String label = args.length > 0 ? args[0] : "run";
        List<Op> ops = new ArrayList<>();
        for (Row row : rows()) {
            java.util.regex.Pattern jur = java.util.regex.Pattern.compile(row.re());
            Pattern vm = Pattern.compile(row.re(), 0, io.github.jemmix.tdfa.core.dfa.TdfaRunner::new);
            Pattern asm = Pattern.compile(row.re());
            long j = jur.matcher(row.in()).find() ? 1 : 0;
            long v = vm.matcher(row.in()).find() ? 1 : 0;
            long a = asm.matcher(row.in()).find() ? 1 : 0;
            if (j != v || v != a) {
                throw new IllegalStateException("RESULT MISMATCH on " + row.name() + ": jur=" + j + " vm=" + v
                    + " asm=" + a);
            }
            final Row fr = row;
            ops.add(new Op(row.name() + " / jur", () -> jurP(fr.re(), fr.in()), j));
            ops.add(new Op(row.name() + " / tdfa-vm", () -> vmP(vm, fr.in()), v));
            ops.add(new Op(row.name() + " / tdfa-asm", () -> vmP(asm, fr.in()), a));
        }
        double[] best = new double[ops.size()];
        java.util.Arrays.fill(best, Double.MAX_VALUE);
        // interleaved passes: 2 warmup + 5 measured, round-robin over ops
        for (int pass = 0; pass < 7; pass++) {
            boolean measure = pass >= 2;
            for (int i = 0; i < ops.size(); i++) {
                Op op = ops.get(i);
                double per = measure(op, !measure);
                if (measure && per < best[i]) {
                    best[i] = per;
                }
            }
        }
        StringBuilder out = new StringBuilder();
        System.out.println("== " + label + " (ns/char over " + LEN + " chars, interleaved min-of-5) ==");
        for (Row row : rows()) {
            String jur = null, vm = null, asm = null;
            for (int i = 0; i < ops.size(); i++) {
                if (ops.get(i).name().startsWith(row.name())) {
                    String col = ops.get(i).name().substring(row.name().length());
                    double nsChar = best[i] / LEN;
                    switch (col) {
                        case " / jur" -> jur = String.format(Locale.ROOT, "%8.1f", nsChar);
                        case " / tdfa-vm" -> vm = String.format(Locale.ROOT, "%8.1f", nsChar);
                        case " / tdfa-asm" -> asm = String.format(Locale.ROOT, "%8.1f", nsChar);
                        default -> {
                        }
                    }
                }
            }
            String line = String.format(Locale.ROOT, "| %s | %s | %s | %s |", pad(row.name(), 26), jur, vm, asm);
            System.out.println(line);
            out.append(line).append('\n');
        }
        Files.writeString(Path.of("wide-scan-probe-" + label + ".txt"), out.toString());
    }

    static String pad(String s, int n) {
        return s.length() >= n ? s : s + " ".repeat(n - s.length());
    }

    static long jurP(String re, String in) {
        return java.util.regex.Pattern.compile(re).matcher(in).find() ? 1 : 0;
    }

    static long vmP(Pattern p, String in) {
        return p.matcher(in).find() ? 1 : 0;
    }

    static String rep(String unit, int len) {
        StringBuilder b = new StringBuilder(len + unit.length());
        while (b.length() < len) {
            b.append(unit);
        }
        return b.toString();
    }

    static double measure(Op op, boolean warmupOnly) {
        long t0 = System.nanoTime();
        long first = op.fn().getAsLong();
        double single = System.nanoTime() - t0;
        if (first != op.expect()) {
            throw new IllegalStateException("WRONG RESULT for " + op.name() + ": " + first);
        }
        int iters = (int) Math.max(1, 2_000_000.0 / Math.max(single, 1));
        while (iters > 1 && iters * single < 500_000.0) {
            iters *= 2;
        }
        long budget = warmupOnly ? 150_000_000L : 300_000_000L;
        double bestBatch = Double.MAX_VALUE;
        int it = iters;
        long sink = 0;
        long start = System.nanoTime();
        while (System.nanoTime() - start < budget) {
            long b0 = System.nanoTime();
            for (int i = 0; i < it; i++) {
                sink ^= op.fn().getAsLong();
            }
            double perBatch = System.nanoTime() - b0;
            if (perBatch < 100_000) {
                it = (int) Math.min(1 << 20, it * 2L);
                continue;
            }
            bestBatch = Math.min(bestBatch, perBatch / it);
            if (op.fn().getAsLong() != op.expect()) {
                throw new IllegalStateException("WRONG RESULT for " + op.name() + " during run");
            }
        }
        if (sink == 42) {
            System.err.print("");
        }
        return bestBatch;
    }
}
