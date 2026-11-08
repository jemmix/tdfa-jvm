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
import java.util.function.Function;

/**
 * 64 KB scan head-to-head vs SafeRE — the regime where scan throughput (not
 * per-call overhead) dominates: literal hit, case-insensitive literal,
 * find-all word/date extraction, Unicode wide classes, and two no-match
 * scans (dense candidates, wide-class miss).
 *
 * <p>One op = one full pass over the input through the engine's String
 * Pattern/Matcher API (find-once or find-all as per slug). AverageTime in
 * microseconds. Setup verifies every engine reports the same match count as
 * java.util.regex (find-all counts, or presence for find-once slugs).
 *
 * <p>Inputs are deterministic (fixed LCG), built once per trial. English
 * corpus: lowercase word soup, a dd/mm/yyyy date every ~37 words, one
 * "Twain" needle inserted ~60% in, no '@' (the email slug is a no-match).
 * Russian corpus: Cyrillic word soup, same generator.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 2)
@Measurement(iterations = 5)
@Fork(1)
public class SafeReScanBench {

    static final int TARGET = 1 << 16;

    @State(Scope.Benchmark)
    public static class BenchState {
        @Param({"jur", "re2j", "safere", "vm", "asm"})
        public String engine;

        @Param({"litHit", "litNoMatch", "caseiLit", "wordsAll", "datesAll", "pLAll", "ruAll", "emailNoMatch",
            "luNoMatch"})
        public String slug;

        String input;
        Function<CharSequence, Integer> scan;

        @Setup(Level.Trial)
        public void setUp() {
            boolean russian = "ruAll".equals(slug);
            input = russian ? buildRussian() : buildEnglish();
            String regex = switch (slug) {
                case "litHit" -> "Twain";
                case "litNoMatch" -> "zzqqxv";
                case "caseiLit" -> "(?i)twain";
                case "wordsAll" -> "[A-Za-z]{4,}";
                case "datesAll" -> "\\d{2}/\\d{2}/\\d{4}";
                case "pLAll" -> "\\p{L}{2,}";
                case "ruAll" -> "[а-яА-ЯёЁ]{4,}";
                case "emailNoMatch" -> "\\w+@\\w+\\.(com|org|net)";
                case "luNoMatch" -> "[\\p{Lu}\\d]{5,}";
                default -> throw new UnsupportedOperationException(slug);
            };
            switch (engine) {
                case "jur" -> {
                    var p = java.util.regex.Pattern.compile(regex);
                    scan = s -> {
                        int n = 0;
                        var m = p.matcher(s);
                        while (m.find()) {
                            n++;
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
                            n++;
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
                            n++;
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
                            n++;
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
                            n++;
                        }
                        return n;
                    };
                }
                default -> throw new UnsupportedOperationException(engine);
            }
            // Count contract: every engine must agree with java.util.regex.
            var oracle = java.util.regex.Pattern.compile(regex).matcher(input);
            int expected = 0;
            while (oracle.find()) {
                expected++;
            }
            int got = scan.apply(input);
            if (got != expected) {
                throw new IllegalStateException(
                    "count mismatch for " + engine + "/" + slug + ": " + got + " != " + expected);
            }
        }
    }

    @Benchmark
    public void scan(BenchState bs, Blackhole bh) {
        bh.consume(bs.scan.apply(bs.input));
    }

    /** English 64 KB corpus: word soup + dd/mm/yyyy dates + one "Twain" needle, no '@'. */
    public static String buildEnglish() {
        String[] words = {"the", "quick", "brown", "fox", "jumps", "over", "lazy", "dog", "and", "then", "ran", "down",
            "by", "river", "morning", "after", "chapter", "steamboat", "Adventures", "Huckleberry", "tom", "sawyer",
            "night", "quietly", "away", "some"};
        StringBuilder sb = new StringBuilder(1 << 17);
        long seed = 0x5DEECE66DL;
        int sinceDate = 0;
        while (sb.length() < TARGET) {
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            String w = words[(int) ((seed >>> 33) % words.length)];
            sb.append(w);
            if (++sinceDate >= 37) {
                sinceDate = 0;
                int mo = (int) ((seed >>> 20) % 12) + 1;
                int da = (int) ((seed >>> 25) % 28) + 1;
                sb.append(' ').append(mo < 10 ? "0" : "").append(mo).append('/').append(da < 10 ? "0" : "").append(da)
                    .append("/2026 ");
            } else {
                sb.append(' ');
            }
        }
        sb.insert((int) (sb.length() * 0.6), "Twain ");
        return sb.toString();
    }

    /** Russian 64 KB corpus: Cyrillic word soup (BMP letters only). */
    public static String buildRussian() {
        String[] words = {"привет", "мир", "вот", "тестовое", "предложение", "короткое", "только", "слово", "машина",
            "река", "утро", "глава", "быстро", "затем", "очень", "далеко", "высокий"};
        StringBuilder sb = new StringBuilder(1 << 17);
        long seed = 0x5DEECE66DL;
        while (sb.length() < TARGET) {
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            String w = words[(int) ((seed >>> 33) % words.length)];
            sb.append(w).append(' ');
        }
        return sb.toString();
    }
}
