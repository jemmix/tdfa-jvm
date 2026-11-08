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
import java.util.function.Supplier;

/**
 * Pattern.compile() cost head-to-head vs SafeRE — the known tdfa-jvm trade:
 * eager TDFA construction (+ bytecode emission on the ASM tier) against
 * SafeRE's RE2-style compile. Single-shot, one compile per invocation; the
 * compiled Pattern is consumed, never matched.
 */
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3)
@Measurement(iterations = 10)
@Fork(1)
public class SafeReCompileBench {

    @State(Scope.Benchmark)
    public static class BenchState {
        @Param({"jur", "re2j", "safere", "vm", "asm"})
        public String engine;

        @Param({"alt", "two", "ip", "caseiLit", "dates"})
        public String slug;

        Supplier<Object> compile;

        @Setup(Level.Trial)
        public void setUp() {
            String regex = switch (slug) {
                case "alt" -> "(a|b)*c";
                case "two" -> "(\\w+)\\s+(\\w+)";
                case "ip" -> "(\\d+)\\.(\\d+)\\.(\\d+)\\.(\\d+)";
                case "caseiLit" -> "(?i)sherlock";
                case "dates" -> "\\d{2}/\\d{2}/\\d{4}";
                default -> throw new UnsupportedOperationException(slug);
            };
            compile = switch (engine) {
                case "jur" -> () -> java.util.regex.Pattern.compile(regex);
                case "re2j" -> () -> com.google.re2j.Pattern.compile(regex);
                case "safere" -> () -> org.safere.Pattern.compile(regex);
                case "vm" -> () -> io.github.jemmix.tdfa.Pattern.compile(regex, 0, TdfaRunner::new);
                case "asm" -> () -> io.github.jemmix.tdfa.Pattern.compile(regex);
                default -> throw new UnsupportedOperationException(engine);
            };
        }
    }

    @Benchmark
    public Object compile(BenchState bs) {
        return bs.compile.get();
    }
}
