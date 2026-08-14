package io.github.jemmix.tdfa.bench;

import io.github.jemmix.tdfa.EngineFactory;
import io.github.jemmix.tdfa.Regex;
import io.github.jemmix.tdfa.vm.MatchResult;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * Fast regression signal — no JMH. Plain main, ~15 s total.
 *
 * <p>Measures the same op families as {@link RegressionBench} (anchored, extract,
 * long-scan no-match, findAll dense/sparse, compile) on 16 KB haystacks. Each op:
 * auto-calibrated iteration count, ~300 ms warmup, then min-of-3 reps of ~200 ms
 * (min is robust against GC outliers). A checksum is accumulated and printed so
 * the JIT can't dead-code-eliminate the work.
 *
 * <p>Writes JMH-schema JSON ({@code [{"benchmark":..., "primaryMetric":{"score":...}}]},
 * score in ns/op) so {@code scripts/bench-compare.py} compares quick runs and full
 * JMH runs interchangeably — but never mix harnesses in one comparison: numbers
 * are only comparable within the same harness. Quick baselines are stored as
 * {@code <host>-quick.json} by {@code scripts/bench-regression.sh}.
 *
 * <p>Usage: {@code QuickBench <output.json>}
 */
public final class QuickBench {

    record Op(String name, LongSupplier fn) {}

    public static void main(String[] args) throws Exception {
        List<double[]> scores = new ArrayList<>(); // [index, score]
        List<Op> ops = buildOps();
        java.util.Map<String, Long> expected = expectedCounts();
        long sink = 0;
        for (int i = 0; i < ops.size(); i++) {
            Op op = ops.get(i);
            double nsPerOp = measure(op.fn(), op.name(), expected.get(op.name()));
            scores.add(new double[]{i, nsPerOp});
            sink ^= (long) nsPerOp;
            System.err.printf(java.util.Locale.ROOT, "%-34s %12.1f ns/op%n", op.name(), nsPerOp);
        }
        System.err.println("(sink " + sink + ")");

        StringBuilder json = new StringBuilder("[\n");
        for (int k = 0; k < scores.size(); k++) {
            Op op = ops.get((int) scores.get(k)[0]);
            json.append(String.format(java.util.Locale.ROOT,
                    "  {\"benchmark\":\"io.github.jemmix.tdfa.bench.QuickBench.%s\",\"primaryMetric\":{\"score\":%.3f}}%s%n",
                    op.name(), scores.get(k)[1], k < scores.size() - 1 ? "," : ""));
        }
        json.append("]");
        String out = args.length > 0 ? args[0] : "quickbench.json";
        java.nio.file.Files.write(java.nio.file.Path.of(out), json.toString().getBytes());
        System.err.println("written: " + out);
    }

    // ===== ops (mirror RegressionBench families) =====

    static List<Op> buildOps() {
        Regex vmTwo = Regex.compile("(\\w+)\\s+(\\w+)", EngineFactory.VM);
        Regex asmTwo = Regex.compile("(\\w+)\\s+(\\w+)", EngineFactory.ASM);
        String inTwo = "hello brave new world 42";

        Regex vmIp = Regex.compile("(\\d+)\\.(\\d+)\\.(\\d+)\\.(\\d+)", EngineFactory.VM);
        Regex asmIp = Regex.compile("(\\d+)\\.(\\d+)\\.(\\d+)\\.(\\d+)", EngineFactory.ASM);
        String inIp = "ip=192.168.1.77 rest";

        Regex vmScan = Regex.compile("[a-z]+qrst", EngineFactory.VM);
        Regex asmScan = Regex.compile("[a-z]+qrst", EngineFactory.ASM);

        Regex vmDense = Regex.compile("[a-z]+ing", EngineFactory.VM);
        Regex asmDense = Regex.compile("[a-z]+ing", EngineFactory.ASM);

        Regex vmSparse = Regex.compile("z[0-9]{3}q", EngineFactory.VM);
        Regex asmSparse = Regex.compile("z[0-9]{3}q", EngineFactory.ASM);

        String noMatch = rep("the quick brown fox jumps over lazy dogs 0123 ", 1 << 14) + "tail";
        String dense = DENSE_INPUT;
        String sparse = SPARSE_INPUT;

        String compileRe = "(\\w+)@(\\w+)\\.(com|org|net)|#([0-9a-f]{6})|\\bword\\b";

        List<Op> ops = new ArrayList<>();
        ops.add(new Op("anchored.vm", () -> vmTwo.matches(inTwo) ? 1 : 0));
        ops.add(new Op("anchored.asm", () -> asmTwo.matches(inTwo) ? 1 : 0));
        ops.add(new Op("extract.vm", () -> vmIp.find(inIp, 0) != null ? 1 : 0));
        ops.add(new Op("extract.asm", () -> asmIp.find(inIp, 0) != null ? 1 : 0));
        ops.add(new Op("scanNoMatch.vm", () -> vmScan.find(noMatch) ? 1 : 0));
        ops.add(new Op("scanNoMatch.asm", () -> asmScan.find(noMatch) ? 1 : 0));
        ops.add(new Op("findAllDense.vm", () -> findAll(vmDense, dense)));
        ops.add(new Op("findAllDense.asm", () -> findAll(asmDense, dense)));
        ops.add(new Op("findAllSparse.vm", () -> findAll(vmSparse, sparse)));
        ops.add(new Op("findAllSparse.asm", () -> findAll(asmSparse, sparse)));
        ops.add(new Op("compile.vm", () -> System.identityHashCode(Regex.compile(compileRe, EngineFactory.VM))));
        ops.add(new Op("compile.asm", () -> System.identityHashCode(Regex.compile(compileRe, EngineFactory.ASM))));
        return ops;
    }

    static int findAll(Regex r, String in) {
        int n = 0;
        int from = 0;
        MatchResult m;
        while ((m = r.find(in, from)) != null) {
            n++;
            from = m.end(0) > m.start(0) ? m.end(0) : m.end(0) + 1;
        }
        return n;
    }

    static final String DENSE_INPUT = rep("running singing hopping jumping coding ", 1 << 14);
    static final String SPARSE_INPUT = rep("lorem ipsum dolor sit z123q amet consec z987q tetur elit ", 1 << 14);

    static String rep(String unit, int len) {
        StringBuilder b = new StringBuilder(len + unit.length());
        while (b.length() < len) b.append(unit);
        return b.toString();
    }

    // ===== measurement =====

    static double measure(LongSupplier op, String name, Long expected) {
        // calibrate: one timed call
        long t0 = System.nanoTime();
        long first = op.getAsLong();
        double single = System.nanoTime() - t0;
        if (expected != null && first != expected) {
            throw new IllegalStateException("WRONG RESULT for " + name + ": " + first + " != expected " + expected);
        }
        int iters = (int) Math.max(1, 2_000_000.0 / Math.max(single, 1)); // ~2 ms worth
        // warmup ~300 ms; verify the count every batch
        runFor(op, iters, 300_000_000L, name, expected);
        // 5 reps ~300 ms, keep the best (lowest per-op time); slow ops get
        // longer batches for stability
        double best = Double.MAX_VALUE;
        for (int r = 0; r < 5; r++) {
            double per = runFor(op, iters, 300_000_000L, name, expected);
            best = Math.min(best, per);
        }
        return best;
    }

    /** Run batches of {@code iters} until {@code budgetNs} elapsed; returns ns/op of the fastest batch. */
    static double runFor(LongSupplier op, int iters, long budgetNs, String name, Long expected) {
        double best = Double.MAX_VALUE;
        long start = System.nanoTime();
        long sink = 0;
        while (System.nanoTime() - start < budgetNs) {
            long t0 = System.nanoTime();
            for (int i = 0; i < iters; i++) sink ^= op.getAsLong();
            double perBatch = System.nanoTime() - t0;
            if (expected != null && sink % iters != 0 && iters > 0) {
                // each batch must produce iters * expected (mod-XOR can't check that;
                // instead verify one explicit call per batch window)
            }
            best = Math.min(best, perBatch / iters);
            long check = op.getAsLong();
            if (expected != null && check != expected) {
                throw new IllegalStateException("WRONG RESULT for " + name + " during measurement: " + check + " != " + expected);
            }
        }
        if (sink == 42) System.err.print(""); // keep sink alive
        return best;
    }

    /** Expected op results (match counts etc.) computed with java.util.regex as the oracle. */
    static java.util.Map<String, Long> expectedCounts() {
        java.util.Map<String, Long> m = new java.util.HashMap<>();
        java.util.regex.Matcher two = java.util.regex.Pattern.compile("(\\w+)\\s+(\\w+)").matcher("hello brave new world 42");
        m.put("anchored.vm", two.matches() ? 1L : 0L);
        m.put("anchored.asm", two.matches() ? 1L : 0L);
        m.put("extract.vm", jurFind("(\\d+)\\.(\\d+)\\.(\\d+)\\.(\\d+)", "ip=192.168.1.77 rest"));
        m.put("extract.asm", jurFind("(\\d+)\\.(\\d+)\\.(\\d+)\\.(\\d+)", "ip=192.168.1.77 rest"));
        m.put("scanNoMatch.vm", 0L);
        m.put("scanNoMatch.asm", 0L);
        m.put("findAllDense.vm", jurFindAll("[a-z]+ing", DENSE_INPUT));
        m.put("findAllDense.asm", jurFindAll("[a-z]+ing", DENSE_INPUT));
        m.put("findAllSparse.vm", jurFindAll("z[0-9]{3}q", SPARSE_INPUT));
        m.put("findAllSparse.asm", jurFindAll("z[0-9]{3}q", SPARSE_INPUT));
        return m;
    }

    static long jurFind(String re, String in) {
        return java.util.regex.Pattern.compile(re).matcher(in).find() ? 1L : 0L;
    }

    static long jurFindAll(String re, String in) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(re).matcher(in);
        long n = 0;
        while (m.find()) n++;
        return n;
    }

    private QuickBench() {}
}
