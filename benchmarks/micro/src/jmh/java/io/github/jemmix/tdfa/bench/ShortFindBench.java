package io.github.jemmix.tdfa.bench;

import io.github.jemmix.tdfa.core.RegexEngine;
import io.github.jemmix.tdfa.core.RegexEngineFactory;
import io.github.jemmix.tdfa.tdfa.TdfaRunner;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * Short-input find() across 5 engines (JMH, ns/op per single call via
 * {@code @OperationsPerInvocation}). Complements ParameterizedShortInputBench
 * (which only measures anchored matches()): this covers the unanchored-search
 * per-call path on short strings — Matcher allocation + scan + extraction —
 * where fixed per-call overheads dominate and java.util.regex's lazy NFA
 * often wins against eager DFA engines.
 *
 * <p>Slugs target the measured rebar loss clusters vs java.util.regex:
 * case-insensitive literals, word boundaries, Unicode letter classes,
 * bounded-repeat spans, literal find, alternation, no-match scan.
 *
 * <p>All engines go through their Pattern/Matcher API (the drop-in shape a
 * user would call). Setup verifies every engine agrees on match presence.
 */
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3)
@Measurement(iterations = 8)
@Fork(1)
@OperationsPerInvocation(ShortFindBench.ITERS)
public class ShortFindBench {

    static final int ITERS = 2_000_000;

    @State(Scope.Benchmark)
    public static class BenchState {
        @Param({"jur", "re2j", "reggie", "vm", "asm"})
        public String engine;

        @Param({"litFind", "caseiLit", "wordB", "wordUnicodeCls", "lettersRu",
                "boundedSpan", "ipExtract", "alternation", "emailNoMatch", "litNoMatch"})
        public String slug;

        String input;
        Predicate<String> findOnce;

        @Setup(Level.Trial)
        public void setUp() {
            String regex = switch (slug) {
                case "litFind"       -> "Twain";
                case "caseiLit"      -> "(?i)sherlock";
                case "wordB"         -> "\\bword\\b";
                case "wordUnicodeCls"   -> "\\p{L}{2,}";
                case "lettersRu"     -> "[а-яА-ЯёЁ]{4,}";
                case "boundedSpan"   -> "\"[^\"]{5,20}\"";
                case "ipExtract"     -> "(\\d+)\\.(\\d+)\\.(\\d+)\\.(\\d+)";
                case "alternation"   -> "(a|b)*c";
                case "emailNoMatch"  -> "\\w+@\\w+\\.(com|org|net)";
                case "litNoMatch"    -> "zzqqxv";
                default -> throw new UnsupportedOperationException(slug);
            };
            input = switch (slug) {
                case "litFind"       -> "The adventures of Tom Sawyer and Huckleberry Finn, by Mark Twain.";
                case "caseiLit"      -> "Mr Sherlock Holmes, the consulting detective, walked in.";
                case "wordB"         -> "a short sentence with word inside the text here";
                case "wordUnicodeCls"   -> "Привет мир, вот тестовое предложение короткое";
                case "lettersRu"     -> "Привет мир, вот тестовое предложение короткое";
                case "boundedSpan"   -> "He said \"hello world\" today and left quite quietly";
                case "ipExtract"     -> "connecting from ip=192.168.1.77 port 443 ok";
                case "alternation"   -> "aabbaabbc";
                case "emailNoMatch"  -> "no addresses anywhere in this particular line at all";
                case "litNoMatch"    -> "The adventures of Tom Sawyer and Huckleberry Finn, by Mark.";
                default -> throw new UnsupportedOperationException(slug);
            };
            final String in = input;
            switch (engine) {
                case "jur" -> {
                    var p = java.util.regex.Pattern.compile(regex);
                    findOnce = s -> { var m = p.matcher(s); return m.find(); };
                }
                case "re2j" -> {
                    var p = com.google.re2j.Pattern.compile(regex);
                    findOnce = s -> { var m = p.matcher(s); return m.find(); };
                }
                case "reggie" -> {
                    var p = com.datadoghq.reggie.Reggie.compile(regex);
                    findOnce = s -> p.find(s);
                }
                case "vm" -> {
                    var p = io.github.jemmix.tdfa.Pattern.compile(regex, 0, TdfaRunner::new);
                    findOnce = s -> { var m = p.matcher(s); return m.find(); };
                }
                case "asm" -> {
                    var p = io.github.jemmix.tdfa.Pattern.compile(regex);
                    findOnce = s -> { var m = p.matcher(s); return m.find(); };
                }
                default -> throw new UnsupportedOperationException(engine);
            }
            boolean expect = switch (slug) {
                case "emailNoMatch", "litNoMatch" -> false;
                default -> true;
            };
            if (findOnce.test(in) != expect)
                throw new IllegalStateException("count mismatch for " + engine + "/" + slug);
        }
    }

    @Benchmark public void bench(BenchState bs, Blackhole bh) {
        for (int i = 0; i < ITERS; i++) {
            bh.consume(bs.findOnce.test(bs.input));
        }
    }
}
