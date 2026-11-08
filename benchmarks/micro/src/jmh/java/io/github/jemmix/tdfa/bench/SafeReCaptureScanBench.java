package io.github.jemmix.tdfa.bench;

import io.github.jemmix.tdfa.core.dfa.TdfaRunner;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;
import java.util.function.ToIntFunction;

/**
 * Find-all WITH capture consumption — the log-extraction idiom
 * (LogExtractMacro at 64 KB scale). Companion to {@code SafeReScanBench}:
 * SafeRE resolves captures lazily (its counting rows never spin up an exact
 * engine), while tdfa-jvm updates tags inline in the walk — so the delta
 * between this bench and the counting one isolates what a group-reading
 * consumer costs each engine.
 *
 * <p>One op = one full pass: loop {@code find()}, consume {@code group()}
 * (and a real group for the capture slugs), sum lengths. Setup verifies the
 * summed lengths and counts against java.util.regex.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 2)
@Measurement(iterations = 5)
@Fork(1)
public class SafeReCaptureScanBench {

    @State(Scope.Benchmark)
    public static class BenchState {
        @Param({"jur", "re2j", "safere", "vm", "asm"})
        public String engine;

        @Param({"wordsCaps", "datesCaps", "ruCaps"})
        public String slug;

        String input;
        ToIntFunction<CharSequence> scan;

        @Setup(Level.Trial)
        public void setUp() {
            boolean russian = "ruCaps".equals(slug);
            input = russian ? SafeReScanBench.buildRussian() : SafeReScanBench.buildEnglish();
            String regex = switch (slug) {
                case "wordsCaps" -> "([A-Za-z]{4,})";
                case "datesCaps" -> "(\\d{2})/(\\d{2})/(\\d{4})";
                case "ruCaps" -> "([а-яА-ЯёЁ]{4,})";
                default -> throw new UnsupportedOperationException(slug);
            };
            switch (engine) {
                case "jur" -> {
                    var p = java.util.regex.Pattern.compile(regex);
                    scan = s -> {
                        int n = 0;
                        var m = p.matcher(s);
                        while (m.find()) {
                            n += m.group(1).length();
                        }
                        return n;
                    };
                }
                case "re2j" -> {
                    var p = com.google.re2j.Pattern.compile(regex);
                    scan = s -> {
                        int n = 0;
                        var m = p.matcher(s);
                        while (m.find()) {
                            n += m.group(1).length();
                        }
                        return n;
                    };
                }
                case "safere" -> {
                    var p = org.safere.Pattern.compile(regex);
                    scan = s -> {
                        int n = 0;
                        var m = p.matcher(s);
                        while (m.find()) {
                            n += m.group(1).length();
                        }
                        return n;
                    };
                }
                case "vm" -> {
                    var p = io.github.jemmix.tdfa.Pattern.compile(regex, 0, TdfaRunner::new);
                    scan = s -> {
                        int n = 0;
                        var m = p.matcher(s);
                        while (m.find()) {
                            n += m.group(1).length();
                        }
                        return n;
                    };
                }
                case "asm" -> {
                    var p = io.github.jemmix.tdfa.Pattern.compile(regex);
                    scan = s -> {
                        int n = 0;
                        var m = p.matcher(s);
                        while (m.find()) {
                            n += m.group(1).length();
                        }
                        return n;
                    };
                }
                default -> throw new UnsupportedOperationException(engine);
            }
            var oracle = java.util.regex.Pattern.compile(regex).matcher(input);
            int expected = 0;
            while (oracle.find()) {
                expected += oracle.group(1).length();
            }
            int got = scan.applyAsInt(input);
            if (got != expected) {
                throw new IllegalStateException(
                    "span-sum mismatch for " + engine + "/" + slug + ": " + got + " != " + expected);
            }
        }
    }

    @Benchmark
    public int scan(BenchState bs) {
        return bs.scan.applyAsInt(bs.input);
    }
}
