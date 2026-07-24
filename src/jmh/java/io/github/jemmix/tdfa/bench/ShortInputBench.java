package io.github.jemmix.tdfa.bench;

import io.github.jemmix.tdfa.Regex;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Tight-loop short-input benchmark. Uses @OperationsPerInvocation(10_000) so JMH reports
 * per-single-match time, not per-batch. This eliminates per-call harness overhead from the
 * measurement, isolating the regex machinery cost.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(2)
@OperationsPerInvocation(10_000)
public class ShortInputBench {

    static final int ITERS = 10_000;

    // (a|b)*c on "aabbc" — small alternation-under-repetition
    static final Regex TDFA_ALT = Regex.compileVm("(a|b)*c");
    static final Regex ASMC_ALT = Regex.compileAsm("(a|b)*c");
    static final Pattern JUR_ALT = Pattern.compile("(a|b)*c");
    static final com.google.re2j.Pattern RE2J_ALT = com.google.re2j.Pattern.compile("(a|b)*c");
    static final com.datadoghq.reggie.runtime.ReggieMatcher REG_ALT = com.datadoghq.reggie.Reggie.compile("(a|b)*c");
    static final String IN_ALT = "aabbc";

    // (\w+)\s+(\w+) on "hello world" — realistic two-group
    static final Regex TDFA_TWO = Regex.compileVm("(\\w+)\\s+(\\w+)");
    static final Regex ASMC_TWO = Regex.compileAsm("(\\w+)\\s+(\\w+)");
    static final Pattern JUR_TWO = Pattern.compile("(\\w+)\\s+(\\w+)");
    static final com.google.re2j.Pattern RE2J_TWO = com.google.re2j.Pattern.compile("(\\w+)\\s+(\\w+)");
    static final com.datadoghq.reggie.runtime.ReggieMatcher REG_TWO = com.datadoghq.reggie.Reggie.compile("(\\w+)\\s+(\\w+)");
    static final String IN_TWO = "hello world";

    // IPv4 — four capture groups
    static final Regex TDFA_IP = Regex.compileVm("(\\d+)\\.(\\d+)\\.(\\d+)\\.(\\d+)");
    static final Regex ASMC_IP = Regex.compileAsm("(\\d+)\\.(\\d+)\\.(\\d+)\\.(\\d+)");
    static final Pattern JUR_IP = Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+)\\.(\\d+)");
    static final com.google.re2j.Pattern RE2J_IP = com.google.re2j.Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+)\\.(\\d+)");
    static final com.datadoghq.reggie.runtime.ReggieMatcher REG_IP = com.datadoghq.reggie.Reggie.compile("(\\d+)\\.(\\d+)\\.(\\d+)\\.(\\d+)");
    static final String IN_IP = "192.168.1.1";

    // abc literal — baseline
    static final Regex TDFA_LIT = Regex.compileVm("abc");
    static final Regex ASMC_LIT = Regex.compileAsm("abc");
    static final Pattern JUR_LIT = Pattern.compile("abc");
    static final com.google.re2j.Pattern RE2J_LIT = com.google.re2j.Pattern.compile("abc");
    static final com.datadoghq.reggie.runtime.ReggieMatcher REG_LIT = com.datadoghq.reggie.Reggie.compile("abc");
    static final String IN_LIT = "abc";

    // ============ literal baseline ============
    @Benchmark public void tdfaLit(Blackhole bh) { for (int i = 0; i < ITERS; i++) bh.consume(TDFA_LIT.matches(IN_LIT)); }
    @Benchmark public void asmcLit(Blackhole bh) { for (int i = 0; i < ITERS; i++) bh.consume(ASMC_LIT.matches(IN_LIT)); }
    @Benchmark public void jurLit(Blackhole bh) { for (int i = 0; i < ITERS; i++) bh.consume(JUR_LIT.matcher(IN_LIT).matches()); }
    @Benchmark public void re2jLit(Blackhole bh) { for (int i = 0; i < ITERS; i++) bh.consume(RE2J_LIT.matcher(IN_LIT).matches()); }
    @Benchmark public void regLit(Blackhole bh) { for (int i = 0; i < ITERS; i++) bh.consume(REG_LIT.matches(IN_LIT)); }

    // ============ (a|b)*c ============
    @Benchmark public void tdfaAlt(Blackhole bh) { for (int i = 0; i < ITERS; i++) bh.consume(TDFA_ALT.matches(IN_ALT)); }
    @Benchmark public void asmcAlt(Blackhole bh) { for (int i = 0; i < ITERS; i++) bh.consume(ASMC_ALT.matches(IN_ALT)); }
    @Benchmark public void jurAlt(Blackhole bh) { for (int i = 0; i < ITERS; i++) bh.consume(JUR_ALT.matcher(IN_ALT).matches()); }
    @Benchmark public void re2jAlt(Blackhole bh) { for (int i = 0; i < ITERS; i++) bh.consume(RE2J_ALT.matcher(IN_ALT).matches()); }
    @Benchmark public void regAlt(Blackhole bh) { for (int i = 0; i < ITERS; i++) bh.consume(REG_ALT.matches(IN_ALT)); }

    // ============ (\w+)\s+(\w+) ============
    @Benchmark public void tdfaTwo(Blackhole bh) { for (int i = 0; i < ITERS; i++) bh.consume(TDFA_TWO.matches(IN_TWO)); }
    @Benchmark public void asmcTwo(Blackhole bh) { for (int i = 0; i < ITERS; i++) bh.consume(ASMC_TWO.matches(IN_TWO)); }
    @Benchmark public void jurTwo(Blackhole bh) { for (int i = 0; i < ITERS; i++) bh.consume(JUR_TWO.matcher(IN_TWO).matches()); }
    @Benchmark public void re2jTwo(Blackhole bh) { for (int i = 0; i < ITERS; i++) bh.consume(RE2J_TWO.matcher(IN_TWO).matches()); }
    @Benchmark public void regTwo(Blackhole bh) { for (int i = 0; i < ITERS; i++) bh.consume(REG_TWO.matches(IN_TWO)); }

    // ============ IPv4 ============
    @Benchmark public void tdfaIp(Blackhole bh) { for (int i = 0; i < ITERS; i++) bh.consume(TDFA_IP.matches(IN_IP)); }
    @Benchmark public void asmcIp(Blackhole bh) { for (int i = 0; i < ITERS; i++) bh.consume(ASMC_IP.matches(IN_IP)); }
    @Benchmark public void jurIp(Blackhole bh) { for (int i = 0; i < ITERS; i++) bh.consume(JUR_IP.matcher(IN_IP).matches()); }
    @Benchmark public void re2jIp(Blackhole bh) { for (int i = 0; i < ITERS; i++) bh.consume(RE2J_IP.matcher(IN_IP).matches()); }
    @Benchmark public void regIp(Blackhole bh) { for (int i = 0; i < ITERS; i++) bh.consume(REG_IP.matches(IN_IP)); }
}
