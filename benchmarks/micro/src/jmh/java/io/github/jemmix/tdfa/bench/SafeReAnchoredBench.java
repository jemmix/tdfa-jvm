package io.github.jemmix.tdfa.bench;

import io.github.jemmix.tdfa.core.dfa.TdfaRunner;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Anchored short-input matches() head-to-head vs SafeRE: same shapes as
 * {@code ParameterizedShortInputBench} (alt / two-words / IPv4 / literal /
 * ReDoS probe), with the SafeRE lane added. Per-single-match time via
 * {@code @OperationsPerInvocation}. Setup verifies every engine agrees on
 * match presence — including the ReDoS row, where java.util.regex pays its
 * quadratic backtracking (~2 us) and the linear engines do not.
 */
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3)
@Measurement(iterations = 8)
@Fork(1)
@OperationsPerInvocation(SafeReAnchoredBench.ITERS)
public class SafeReAnchoredBench {

    static final int ITERS = 1_000_000;

    @State(Scope.Benchmark)
    public static class BenchState {
        @Param({"jur", "re2j", "safere", "vm", "asm"})
        public String engine;

        @Param({"alt", "two", "ip", "lit", "redos"})
        public String regexSlug;

        public String in;
        Function<? super String, Boolean> matches;

        @Setup(Level.Trial)
        public void setUp() {
            String regex = switch (regexSlug) {
                case "alt" -> "(a|b)*c";
                case "two" -> "(\\w+)\\s+(\\w+)";
                case "ip" -> "(\\d+)\\.(\\d+)\\.(\\d+)\\.(\\d+)";
                case "lit" -> "abc";
                case "redos" -> "(a+)+b";
                default -> throw new UnsupportedOperationException("unknown regexSlug: " + regexSlug);
            };

            in = switch (regexSlug) {
                case "alt" -> "aabbc";
                case "two" -> "hello world";
                case "ip" -> "192.168.1.1";
                case "lit" -> "abc";
                case "redos" -> "aaaaaaaaaaaaaaaaaaaac";
                default -> throw new UnsupportedOperationException("unknown regexSlug: " + regexSlug);
            };

            switch (engine) {
                case "jur" -> {
                    var pattern = java.util.regex.Pattern.compile(regex);
                    matches = s -> pattern.matcher(s).matches();
                }
                case "re2j" -> {
                    var pattern = com.google.re2j.Pattern.compile(regex);
                    matches = s -> pattern.matcher(s).matches();
                }
                case "safere" -> {
                    var pattern = org.safere.Pattern.compile(regex);
                    matches = s -> pattern.matcher(s).matches();
                }
                case "vm" -> matches = io.github.jemmix.tdfa.Pattern.compile(regex, 0, TdfaRunner::new)::matches;
                case "asm" -> matches = io.github.jemmix.tdfa.Pattern.compile(regex)::matches;
                default -> throw new UnsupportedOperationException("unknown engine: " + engine);
            }

            boolean expect = "redos".equals(regexSlug) ? false : true;
            if (matches.apply(in) != expect) {
                throw new IllegalStateException("presence mismatch for " + engine + "/" + regexSlug);
            }
        }
    }

    @Benchmark
    public void bench(BenchState bs, Blackhole bh) {
        for (int i = 0; i < ITERS; i++) {
            bh.consume(bs.matches.apply(bs.in));
        }
    }
}
