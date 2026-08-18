package io.github.jemmix.tdfa.bench;

import io.github.jemmix.tdfa.core.RegexEngine;
import io.github.jemmix.tdfa.core.RegexEngineFactory;
import io.github.jemmix.tdfa.tdfa.TdfaRunner;
import io.github.jemmix.tdfa.Pattern;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * JMH: tdfa-jvm vs java.util.regex on capture-heavy patterns (the profit cases).
 *
 * Each benchmark pairs a pattern with its matching input. Three workload shapes:
 *   - short alternating-capture: (a|b)*c
 *   - realistic two-group:       (\w+)\s+(\w+)
 *   - realistic four-group IP:   (\d+)\.(\d+)\.(\d+)\.(\d+)
 *   - long-input alternation-under-repetition: (a+)+b
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
public class CaptureBench {

    // ---- short alternation-capture: (a|b)*c on "aabbc" ----
    static final Pattern TDFA_ALT_STAR = Pattern.compile("(a|b)*c", 0, TdfaRunner::new);
    static final Pattern ASMC_ALT_STAR = Pattern.compile("(a|b)*c");
    static final java.util.regex.Pattern JUR_ALT_STAR = java.util.regex.Pattern.compile("(a|b)*c");
    static final com.google.re2j.Pattern RE2J_ALT_STAR = com.google.re2j.Pattern.compile("(a|b)*c");
    static final com.datadoghq.reggie.runtime.ReggieMatcher REGGIE_ALT_STAR = com.datadoghq.reggie.Reggie.compile("(a|b)*c");
    static final String IN_ALT_STAR = "aabbc";

    // ---- two groups, single space ----
    static final Pattern TDFA_TWO = Pattern.compile("(\\w+)\\s+(\\w+)", 0, TdfaRunner::new);
    static final Pattern ASMC_TWO = Pattern.compile("(\\w+)\\s+(\\w+)");
    static final java.util.regex.Pattern JUR_TWO = java.util.regex.Pattern.compile("(\\w+)\\s+(\\w+)");
    static final com.google.re2j.Pattern RE2J_TWO = com.google.re2j.Pattern.compile("(\\w+)\\s+(\\w+)");
    static final com.datadoghq.reggie.runtime.ReggieMatcher REGGIE_TWO = com.datadoghq.reggie.Reggie.compile("(\\w+)\\s+(\\w+)");
    static final String IN_TWO = "hello world";

    // ---- IPv4 with 4 capture groups ----
    static final Pattern TDFA_IP = Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+)\\.(\\d+)", 0, TdfaRunner::new);
    static final Pattern ASMC_IP = Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+)\\.(\\d+)");
    static final java.util.regex.Pattern JUR_IP = java.util.regex.Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+)\\.(\\d+)");
    static final com.google.re2j.Pattern RE2J_IP = com.google.re2j.Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+)\\.(\\d+)");
    static final com.datadoghq.reggie.runtime.ReggieMatcher REGGIE_IP = com.datadoghq.reggie.Reggie.compile("(\\d+)\\.(\\d+)\\.(\\d+)\\.(\\d+)");
    static final String IN_IP = "192.168.1.1";

    // ---- (a+)+b on a long non-matching input (catastrophic for backtracking) ----
    static final Pattern TDFA_NESTED = Pattern.compile("(a+)+b", 0, TdfaRunner::new);
    static final Pattern ASMC_NESTED = Pattern.compile("(a+)+b");
    static final java.util.regex.Pattern JUR_NESTED = java.util.regex.Pattern.compile("(a+)+b");
    static final com.google.re2j.Pattern RE2J_NESTED = com.google.re2j.Pattern.compile("(a+)+b");
    static final com.datadoghq.reggie.runtime.ReggieMatcher REGGIE_NESTED = com.datadoghq.reggie.Reggie.compile("(a+)+b");
    static final String IN_NESTED = "aaaaaaaaaaaaaaaaaaaac"; // 20 'a's + 'c', no 'b'

    // ============ boolean match (no group extraction) ============
    @Benchmark public boolean tdfaNestedMatch() { return TDFA_NESTED.matches(IN_NESTED); }
    @Benchmark public boolean asmcNestedMatch() { return ASMC_NESTED.matches(IN_NESTED); }
    @Benchmark public boolean jurNestedMatch() { return JUR_NESTED.matcher(IN_NESTED).matches(); }
    @Benchmark public boolean re2jNestedMatch() { return RE2J_NESTED.matcher(IN_NESTED).matches(); }
    @Benchmark public boolean reggieNestedMatch() { return REGGIE_NESTED.matches(IN_NESTED); }

    @Benchmark public boolean tdfaAltStarMatch() { return TDFA_ALT_STAR.matches(IN_ALT_STAR); }
    @Benchmark public boolean asmcAltStarMatch() { return ASMC_ALT_STAR.matches(IN_ALT_STAR); }
    @Benchmark public boolean jurAltStarMatch() { return JUR_ALT_STAR.matcher(IN_ALT_STAR).matches(); }
    @Benchmark public boolean re2jAltStarMatch() { return RE2J_ALT_STAR.matcher(IN_ALT_STAR).matches(); }
    @Benchmark public boolean reggieAltStarMatch() { return REGGIE_ALT_STAR.matches(IN_ALT_STAR); }

    @Benchmark public boolean tdfaTwoMatch() { return TDFA_TWO.matches(IN_TWO); }
    @Benchmark public boolean asmcTwoMatch() { return ASMC_TWO.matches(IN_TWO); }
    @Benchmark public boolean jurTwoMatch() { return JUR_TWO.matcher(IN_TWO).matches(); }
    @Benchmark public boolean re2jTwoMatch() { return RE2J_TWO.matcher(IN_TWO).matches(); }
    @Benchmark public boolean reggieTwoMatch() { return REGGIE_TWO.matches(IN_TWO); }

    @Benchmark public boolean tdfaIpMatch() { return TDFA_IP.matches(IN_IP); }
    @Benchmark public boolean asmcIpMatch() { return ASMC_IP.matches(IN_IP); }
    @Benchmark public boolean jurIpMatch() { return JUR_IP.matcher(IN_IP).matches(); }
    @Benchmark public boolean re2jIpMatch() { return RE2J_IP.matcher(IN_IP).matches(); }
    @Benchmark public boolean reggieIpMatch() { return REGGIE_IP.matches(IN_IP); }

    // ============ long-input scaling (per-char cost dominates) ============
    // Input is all letters (no digit), pattern requires a digit in the middle.
    // Each restart scans ~10 chars before failing; total work scales with input length.
    static final String IN_LONG;
    static final Pattern TDFA_LONG;
    static final Pattern ASMC_LONG;
    static final java.util.regex.Pattern JUR_LONG;
    static final com.datadoghq.reggie.runtime.ReggieMatcher REGGIE_LONG;
    static {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) sb.append("abcdefghij");  // 1000 chars
        IN_LONG = sb.toString();
        String longPat = "(\\w+)(\\d+)(\\w+)";  // requires \d+ in middle; never matches all-letter input
        TDFA_LONG = Pattern.compile(longPat, 0, TdfaRunner::new);
        ASMC_LONG = Pattern.compile(longPat);
        JUR_LONG = java.util.regex.Pattern.compile(longPat);
        REGGIE_LONG = com.datadoghq.reggie.Reggie.compile(longPat);
    }

    /** find() on a 1000-char all-letter input where the pattern requires a digit. Both engines
     *  must scan every start position to give up. Per-char cost dominates. */
    @Benchmark public boolean tdfaLongFind() { return TDFA_LONG.matcher(IN_LONG).find(); }
    @Benchmark public boolean asmcLongFind() { return ASMC_LONG.matcher(IN_LONG).find(); }
    @Benchmark public boolean jurLongFind() { return JUR_LONG.matcher(IN_LONG).find(); }
    @Benchmark public boolean reggieLongFind() { return REGGIE_LONG.find(IN_LONG); }

    // ============ with group extraction ============
    @Benchmark
    public void tdfaAltStarGroups(Blackhole bh) {
        io.github.jemmix.tdfa.core.Matcher m = TDFA_ALT_STAR.matcher(IN_ALT_STAR); m.find();
        if (m != null) bh.consume(m.start(1));
    }

    @Benchmark
    public void jurAltStarGroups(Blackhole bh) {
        java.util.regex.Matcher m = JUR_ALT_STAR.matcher(IN_ALT_STAR);
        if (m.find()) bh.consume(m.start(1));
    }

    @Benchmark
    public void tdfaIpGroups(Blackhole bh) {
        io.github.jemmix.tdfa.core.Matcher m = TDFA_IP.matcher(IN_IP); m.find();
        if (m != null) {
            bh.consume(m.start(1));
            bh.consume(m.start(2));
            bh.consume(m.start(3));
            bh.consume(m.start(4));
        }
    }

    @Benchmark
    public void jurIpGroups(Blackhole bh) {
        java.util.regex.Matcher m = JUR_IP.matcher(IN_IP);
        if (m.find()) {
            bh.consume(m.start(1));
            bh.consume(m.start(2));
            bh.consume(m.start(3));
            bh.consume(m.start(4));
        }
    }
}
