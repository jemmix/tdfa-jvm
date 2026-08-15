package io.github.jemmix.tdfa.bench;

import io.github.jemmix.tdfa.EngineFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

/**
 * Log-extraction macrobenchmark: Matcher.find + group extraction over a
 * synthetic log stream — the shape ASM's generated Pattern/Matcher target
 * (parse-dominated pipelines where regex work is a real fraction of wall
 * time). Complements ShortFindBench (per-call short-input floors) and
 * RebarBench (haystack scanning).
 *
 * <p>Plain main (JMH harness adds nothing at this granularity): 200k lines
 * (~90 chars each), interleaved engines, per-engine cold pass (first 10k
 * calls, no warmup) and warm min-of-5 x 100k lines. Count-verified vs
 * java.util.regex.
 *
 * <p>Run like RebarBench: {@code scripts/bench-rebar.sh} classpath, then
 * {@code java ...io.github.jemmix.tdfa.bench.LogExtractMacro}.
 */
public final class LogExtractMacro {

    static final int LINES = 200_000;
    static final int COLD = 10_000;
    static final int WARM_BATCH = 100_000;

    record Row(String name, String regex, int groupCount) { }

    static final List<Row> ROWS = List.of(
            new Row("ip", "ip=(\\d+\\.\\d+\\.\\d+\\.\\d+)", 1),
            new Row("user-status", "user_id=(\\d+).*?status=(\\d+)", 2),
            new Row("path", "path=(/[a-z0-9/]+)", 1),
            new Row("no-match", "[a-z]+@[a-z]+\\.[a-z]{3}", 1)
    );

    public static void main(String[] args) {
        List<String> lines = genLines(LINES);
        String[] engines = {"jur", "re2j", "vm", "asm"};
        System.out.println(LogExtractMacro.class.getSimpleName()
                + ": " + LINES + " lines, cold = first " + COLD + " calls, warm = min-of-5 x " + WARM_BATCH);
        System.out.printf("%-14s %-6s %10s %14s   %s%n", "row", "eng", "cold", "warm", "ns/line(warm)");
        for (Row row : ROWS) {
            long jurCount = 0;
            for (String eng : engines) {
                BiFunction<String, java.util.function.IntConsumer, Long> fn = mk(eng, row);
                // cold pass: COLD lines, first calls ever on this pattern
                java.util.function.IntConsumer sink = c -> { };
                long coldStart = System.nanoTime();
                long coldCount = 0;
                for (int i = 0; i < COLD; i++) coldCount += fn.apply(lines.get(i), sink);
                long coldNs = System.nanoTime() - coldStart;
                // warmup + min-of-5
                for (int i = 0; i < WARM_BATCH; i++) fn.apply(lines.get(i % LINES), sink);
                long best = Long.MAX_VALUE;
                long warmCount = 0;
                for (int r = 0; r < 5; r++) {
                    long t = System.nanoTime();
                    warmCount = 0;
                    for (int i = 0; i < WARM_BATCH; i++) warmCount += fn.apply(lines.get(i % LINES), sink);
                    best = Math.min(best, System.nanoTime() - t);
                }
                if (eng.equals("jur")) jurCount = warmCount;
                else if (warmCount != jurCount)
                    throw new AssertionError(row.name() + "/" + eng + ": count " + warmCount + " != jur " + jurCount);
                System.out.printf("%-14s %-6s %8.1f ms %12.1f ms   %8.1f%n",
                        row.name(), eng, coldNs / 1e6, best / 1e6, (double) best / WARM_BATCH);
            }
        }
    }

    interface LineFn {
        long apply(String line, java.util.function.IntConsumer sink);
    }

    static BiFunction<String, java.util.function.IntConsumer, Long> mk(String eng, Row row) {
        return switch (eng) {
            case "jur" -> {
                var p = java.util.regex.Pattern.compile(row.regex());
                yield (line, sink) -> {
                    var m = p.matcher(line);
                    long n = 0;
                    while (m.find()) {
                        for (int g = 1; g <= row.groupCount(); g++) sink.accept(m.start(g));
                        n++;
                    }
                    return n;
                };
            }
            case "re2j" -> {
                var p = com.google.re2j.Pattern.compile(row.regex());
                yield (line, sink) -> {
                    var m = p.matcher(line);
                    long n = 0;
                    while (m.find()) {
                        for (int g = 1; g <= row.groupCount(); g++) sink.accept(m.start(g));
                        n++;
                    }
                    return n;
                };
            }
            case "vm" -> {
                var p = io.github.jemmix.tdfa.re2j.Pattern.compile(row.regex(), 0, EngineFactory.VM);
                yield (line, sink) -> {
                    var m = p.matcher(line);
                    long n = 0;
                    while (m.find()) {
                        for (int g = 1; g <= row.groupCount(); g++) sink.accept(m.start(g));
                        n++;
                    }
                    return n;
                };
            }
            case "asm" -> {
                var p = io.github.jemmix.tdfa.re2j.Pattern.compile(row.regex(), 0, EngineFactory.ASM);
                yield (line, sink) -> {
                    var m = p.matcher(line);
                    long n = 0;
                    while (m.find()) {
                        for (int g = 1; g <= row.groupCount(); g++) sink.accept(m.start(g));
                        n++;
                    }
                    return n;
                };
            }
            default -> throw new IllegalArgumentException(eng);
        };
    }

    static List<String> genLines(int n) {
        List<String> out = new ArrayList<>(n);
        java.util.Random rnd = new java.util.Random(42);
        String[] levels = {"INFO", "WARN", "ERROR", "DEBUG"};
        for (int i = 0; i < n; i++) {
            out.add(String.format(
                    "2026-08-15T12:%02d:%02d.%03d %s [worker-%d] user_id=%d path=/api/v%d/items/list page=%d status=%d dur=%dms ip=192.168.%d.%d",
                    rnd.nextInt(60), rnd.nextInt(60), rnd.nextInt(1000),
                    levels[rnd.nextInt(levels.length)], rnd.nextInt(16),
                    1000 + rnd.nextInt(9000), 1 + rnd.nextInt(3), 1 + rnd.nextInt(50),
                    rnd.nextBoolean() ? 200 : rnd.nextBoolean() ? 404 : 500,
                    rnd.nextInt(500), rnd.nextInt(256), rnd.nextInt(256)));
        }
        return out;
    }

    private LogExtractMacro() { }
}
