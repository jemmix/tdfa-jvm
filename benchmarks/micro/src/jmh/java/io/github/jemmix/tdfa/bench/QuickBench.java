package io.github.jemmix.tdfa.bench;

import io.github.jemmix.tdfa.core.RegexEngine;
import io.github.jemmix.tdfa.core.RegexEngineFactory;
import io.github.jemmix.tdfa.tdfa.TdfaRunner;
import io.github.jemmix.tdfa.Pattern;
import io.github.jemmix.tdfa.core.MatchResult;

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
        // warm the control (its own JIT + the thread) so the FIRST op's
        // normalization isn't skewed by a cold ~4x-slower control readout
        for (int i = 0; i < 3; i++) measureControl();
        // TWO passes over all ops, keeping the per-op minimum of
        // (raw / control-before-op): sustained-load frequency drift moves op and
        // control together (the ratio is stable where raw ns/op swings 30-60% on
        // a thermally-throttling laptop), and the two passes cancel per-op wobble
        // (JIT deopt of one op's batch, a stray GC, control outlier). The control
        // is a branch-free char-sum loop over the haystack — engine-independent.
        double[] norm = new double[ops.size()];
        double[] rawBest = new double[ops.size()];
        java.util.Arrays.fill(norm, Double.MAX_VALUE);
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < ops.size(); i++) {
                Op op = ops.get(i);
                double control = measureControl();
                double nsPerOp = measure(op.fn(), op.name(), expected.get(op.name()));
                double n = nsPerOp * 1000.0 / control;  // x1000: keep %.3f resolution
                if (n < norm[i]) { norm[i] = n; rawBest[i] = nsPerOp; }
            }
        }
        long sink = 0;
        for (int i = 0; i < ops.size(); i++) {
            scores.add(new double[]{i, norm[i]});
            sink ^= (long) rawBest[i];
            System.err.printf(java.util.Locale.ROOT, "%-34s raw %10.1f ns  norm %8.2f%n",
                    ops.get(i).name(), rawBest[i], norm[i]);
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
        java.nio.file.Path outPath = java.nio.file.Path.of(out);
        if (outPath.getParent() != null) java.nio.file.Files.createDirectories(outPath.getParent());
        java.nio.file.Files.write(outPath, json.toString().getBytes());
        System.err.println("written: " + out);
    }

    // ===== ops (mirror RegressionBench families) =====

    static List<Op> buildOps() {
        Pattern vmTwo = Pattern.compile("(\\w+)\\s+(\\w+)", 0, TdfaRunner::new);
        Pattern asmTwo = Pattern.compile("(\\w+)\\s+(\\w+)");
        String inTwo = "hello brave new world 42";

        Pattern vmIp = Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+)\\.(\\d+)", 0, TdfaRunner::new);
        Pattern asmIp = Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+)\\.(\\d+)");
        String inIp = "ip=192.168.1.77 rest";

        Pattern vmScan = Pattern.compile("[a-z]+qrst", 0, TdfaRunner::new);
        Pattern asmScan = Pattern.compile("[a-z]+qrst");

        Pattern vmDense = Pattern.compile("[a-z]+ing", 0, TdfaRunner::new);
        Pattern asmDense = Pattern.compile("[a-z]+ing");

        Pattern vmSparse = Pattern.compile("z[0-9]{3}q", 0, TdfaRunner::new);
        Pattern asmSparse = Pattern.compile("z[0-9]{3}q");

        Pattern vmLatin = Pattern.compile("[a-zA-Z\u00e0-\u00ff]+ement", 0, TdfaRunner::new);
        Pattern asmLatin = Pattern.compile("[a-zA-Z\u00e0-\u00ff]+ement");

        String noMatch = rep("the quick brown fox jumps over lazy dogs 0123 ", 1 << 14) + "tail";
        String dense = DENSE_INPUT;
        String sparse = SPARSE_INPUT;
        String latin1 = LATIN1_INPUT;

        String compileRe = "(\\w+)@(\\w+)\\.(com|org|net)|#([0-9a-f]{6})|\\bword\\b";

        List<Op> ops = new ArrayList<>();
        ops.add(new Op("info.anchored.vm", () -> vmTwo.matches(inTwo) ? 1 : 0));
        ops.add(new Op("info.anchored.asm", () -> asmTwo.matches(inTwo) ? 1 : 0));
        ops.add(new Op("info.extract.vm", () -> vmIp.matcher(inIp).find() ? 1 : 0));
        ops.add(new Op("info.extract.asm", () -> asmIp.matcher(inIp).find() ? 1 : 0));
        ops.add(new Op("scanNoMatch.vm", () -> vmScan.matcher(noMatch).find() ? 1 : 0));
        ops.add(new Op("scanNoMatch.asm", () -> asmScan.matcher(noMatch).find() ? 1 : 0));
        ops.add(new Op("findAllDense.vm", () -> findAll(vmDense, dense)));
        ops.add(new Op("findAllDense.asm", () -> findAll(asmDense, dense)));
        ops.add(new Op("findAllSparse.vm", () -> findAll(vmSparse, sparse)));
        ops.add(new Op("findAllSparse.asm", () -> findAll(asmSparse, sparse)));
        ops.add(new Op("findAllLatin1.vm", () -> findAll(vmLatin, latin1)));
        ops.add(new Op("findAllLatin1.asm", () -> findAll(asmLatin, latin1)));
        ops.add(new Op("compile.vm", () -> System.identityHashCode(Pattern.compile(compileRe, 0, TdfaRunner::new))));
        ops.add(new Op("compile.asm", () -> System.identityHashCode(Pattern.compile(compileRe))));
        // re2j shim compile: eager engine + (previously eager, now lazy) anchored-both engine
        ops.add(new Op("compile.re2j", () -> System.identityHashCode(
                io.github.jemmix.tdfa.Pattern.compile(compileRe))));
        return ops;
    }

    static int findAll(Pattern r, String in) {
        int n = 0;
        for (io.github.jemmix.tdfa.core.Matcher m = r.matcher(in); m.find(); ) n++;
        return n;
    }

    static final String DENSE_INPUT = rep("running singing hopping jumping coding ", 1 << 14);
    static final String SPARSE_INPUT = rep("lorem ipsum dolor sit z123q amet consec z987q tetur elit ", 1 << 14);
    static final String LATIN1_INPUT = rep("d\u00e9veloppement \u00e9tablissement \u00e9v\u00e9nement diff\u00e9rent \u00e0 c\u00f4t\u00e9 engagement ", 1 << 14);

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
        // batches must be >= 0.5 ms so a timer-granularity misread can't zero
        // a micro-batch and poison the min (seen on a 17 ns op: raw "0.0")
        while (iters > 1 && iters * single < 500_000.0) iters *= 2;
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
            if (perBatch < 100_000) continue;  // sub-0.1ms batch: timer noise, skip
            best = Math.min(best, perBatch / iters);
            long check = op.getAsLong();
            if (expected != null && check != expected) {
                throw new IllegalStateException("WRONG RESULT for " + name + " during measurement: " + check + " != " + expected);
            }
        }
        if (sink == 42) System.err.print(""); // keep sink alive
        return best;
    }

    /**
     * Control workload: sum chars of the haystack in batches sized to ~1-2 ms.
     * JIT-stable, branch-free, engine-independent — used to normalize op scores
     * against machine-frequency drift (see main).
     */
    static double measureControl() {
        String data = SPARSE_INPUT;
        // calibrate batch count for ~1 ms
        long t0 = System.nanoTime();
        long sink = sumChars(data, 8);
        double per8 = System.nanoTime() - t0;
        int batches = (int) Math.max(4, Math.min(2048, 1_000_000.0 / Math.max(per8 / 8, 1) / 8));
        for (int i = 0; i < 20; i++) sink ^= sumChars(data, batches); // warm
        double best = Double.MAX_VALUE;
        for (int r = 0; r < 5; r++) {
            long t1 = System.nanoTime();
            sink ^= sumChars(data, batches);
            double per = (System.nanoTime() - t1) / (double) batches;
            best = Math.min(best, per);
        }
        if (sink == 42) System.err.print("");
        return best; // ns per one haystack pass
    }

    static long sumChars(String s, int batches) {
        long sum = 0;
        for (int b = 0; b < batches; b++) {
            for (int i = 0; i < s.length(); i++) sum += s.charAt(i);
        }
        return sum;
    }

    /** Expected op results (match counts etc.) computed with java.util.regex as the oracle. */
    static java.util.Map<String, Long> expectedCounts() {
        java.util.Map<String, Long> m = new java.util.HashMap<>();
        java.util.regex.Matcher two = java.util.regex.Pattern.compile("(\\w+)\\s+(\\w+)").matcher("hello brave new world 42");
        m.put("info.anchored.vm", two.matches() ? 1L : 0L);
        m.put("info.anchored.asm", two.matches() ? 1L : 0L);
        m.put("info.extract.vm", jurFind("(\\d+)\\.(\\d+)\\.(\\d+)\\.(\\d+)", "ip=192.168.1.77 rest"));
        m.put("info.extract.asm", jurFind("(\\d+)\\.(\\d+)\\.(\\d+)\\.(\\d+)", "ip=192.168.1.77 rest"));
        m.put("scanNoMatch.vm", 0L);
        m.put("scanNoMatch.asm", 0L);
        m.put("findAllDense.vm", jurFindAll("[a-z]+ing", DENSE_INPUT));
        m.put("findAllDense.asm", jurFindAll("[a-z]+ing", DENSE_INPUT));
        m.put("findAllSparse.vm", jurFindAll("z[0-9]{3}q", SPARSE_INPUT));
        m.put("findAllSparse.asm", jurFindAll("z[0-9]{3}q", SPARSE_INPUT));
        m.put("findAllLatin1.vm", jurFindAll("[a-zA-Z\u00e0-\u00ff]+ement", LATIN1_INPUT));
        m.put("findAllLatin1.asm", jurFindAll("[a-zA-Z\u00e0-\u00ff]+ement", LATIN1_INPUT));
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
