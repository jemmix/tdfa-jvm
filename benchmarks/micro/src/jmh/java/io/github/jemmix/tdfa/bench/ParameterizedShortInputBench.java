package io.github.jemmix.tdfa.bench;

import io.github.jemmix.tdfa.core.RegexEngine;
import io.github.jemmix.tdfa.core.RegexEngineFactory;
import io.github.jemmix.tdfa.tdfa.TdfaRunner;
import io.github.jemmix.tdfa.Pattern;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Tight-loop short-input benchmark. Uses @OperationsPerInvocation(10_000) so JMH reports
 * per-single-match time, not per-batch. This eliminates per-call harness overhead from the
 * measurement, isolating the regex machinery cost.
 */
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 5)
@Measurement(iterations = 10)
@Fork(2)
@OperationsPerInvocation(ParameterizedShortInputBench.ITERS)
public class ParameterizedShortInputBench {

    static final int ITERS = 50_000_000;

    @State(Scope.Benchmark)
    public static class BenchState {
        @Param({"tdfa", "asmc", "jur", "re2j", "reggie"})
        public String engine;

        @Param({"alt", "two", "ip", "lit", "redos"})
        public String regexSlug;

        public String in;
        public Function<? super String, Boolean> matches;

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
                case "tdfa" -> matches = Pattern.compile(regex, 0, TdfaRunner::new)::matches;
                case "asmc" -> matches = Pattern.compile(regex)::matches;
                case "jur" -> {
                    var pattern = Pattern.compile(regex);
                    matches = s -> pattern.matcher(s).matches();
                }
                case "re2j" -> {
                    var pattern = com.google.re2j.Pattern.compile(regex);
                    matches = s -> pattern.matcher(s).matches();
                }
                case "reggie" -> matches = com.datadoghq.reggie.Reggie.compile(regex)::matches;
                default -> throw new UnsupportedOperationException("unknown engine: " + engine);
            }
        }
    }

    @Benchmark public void bench(BenchState bs, Blackhole bh) {
        for (int i = 0; i < ITERS; i++) {
            bh.consume(bs.matches.apply(bs.in));
        }
    }
}
