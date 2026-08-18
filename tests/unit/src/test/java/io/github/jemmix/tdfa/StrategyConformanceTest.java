package io.github.jemmix.tdfa;

import io.github.jemmix.tdfa.tdfa.TdfaRunner;
import io.github.jemmix.tdfa.core.MatchResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Strategy conformance: the interpreter and the ASM backend must not only
 * produce identical match results — they must pick the SAME search strategy
 * (literal / candidate scan / exact-from / origin sim / trigger / raw scan /
 * restart / anchored variant) for every call. This is the structural guard
 * against the two ladders drifting with identical results (the litFind bug:
 * ASM ran a different algorithm than VM at one length boundary, invisible to
 * output parity, visible only as a perf cliff).
 *
 * <p>Sweeps a shape catalog (fastPath, mask-bearing, literal, wide-unicode,
 * dense-candidate no-match, alternation) across length boundaries (around
 * CAND_SCAN_MAX=64, Latin-1 128/256, and the trigger window 2048) and both
 * API tiers (core {@link Regex}, re2j-compat {@code Pattern.matcher}), with
 * the trace hook enabled via {@link TdfaRunner#setTracing(boolean)}.
 */
class StrategyConformanceTest {

    private static final List<String[]> SHAPES = List.of(new String[][]{
            {"Twain", "The adventures of Tom Sawyer and Huckleberry Finn, by Mark Twain."},
            {"zzqqxv", "The adventures of Tom Sawyer and Huckleberry Finn, by Mark."},
            {"(?i)sherlock", "Mr Sherlock Holmes, the consulting detective, walked in."},
            {"\\bword\\b", "a short sentence with word inside the text here"},
            {"\\p{L}{2,}", "Привет мир, вот тестовое предложение короткое"},
            {"[а-яА-ЯёЁ]{4,}", "Привет мир, вот тестовое предложение короткое"},
            {"\"[^\"]{5,20}\"", "He said \"hello world\" today and left quite quietly"},
            {"(\\d+)\\.(\\d+)\\.(\\d+)\\.(\\d+)", "connecting from ip=192.168.1.77 port 443 ok"},
            {"(a|b)*c", "aabbaabbc"},
            {"\\w+@\\w+\\.(com|org|net)", "no addresses anywhere in this particular line at all"},
            {"[a-z]+qrst", "the quick brown fox jumps over the lazy dog qrstxx"},
            {"(?m)^line", "first\nline two\nline three\nline four\nline five"},
    });

    private static final int[] LENGTHS = {1, 2, 3, 15, 40, 63, 64, 65, 100, 127, 128, 129,
            255, 256, 257, 300, 2047, 2048, 2049, 4100};

    @BeforeAll
    static void enableTracing() {
        TdfaRunner.setTracing(true);
    }

    @AfterAll
    static void disableTracing() {
        TdfaRunner.setTracing(false);
    }

    @Test
    void coreRegexIdenticalStrategiesAndResults() {
        int checks = 0;
        for (String[] shape : SHAPES) {
            Regex vm = Regex.compile(shape[0], EngineFactory.VM);
            Regex asm = Regex.compile(shape[0], EngineFactory.ASM);
            for (int len : LENGTHS) {
                String in = padTo(shape[1], len);
                checks += compare(vm, asm, in, "core " + shape[0] + " len=" + len);
            }
        }
        assertThat(checks).isGreaterThan(100);
    }

    @Test
    void shimPatternIdenticalStrategiesAndResults() {
        int checks = 0;
        for (String[] shape : SHAPES) {
            var vm = io.github.jemmix.tdfa.re2j.Pattern.compile(shape[0], 0, EngineFactory.VM);
            var asm = io.github.jemmix.tdfa.re2j.Pattern.compile(shape[0], 0, EngineFactory.ASM);
            for (int len : LENGTHS) {
                String in = padTo(shape[1], len);
                checks += compareShim(vm, asm, in, "shim " + shape[0] + " len=" + len);
            }
        }
        assertThat(checks).isGreaterThan(100);
    }

    /** Generated-pattern sanity: the ASM shim returns generated Pattern/Matcher
     *  classes (Gen* in a child loader) and they behave exactly like the VM shim. */
    @Test
    void generatedPatternClassShapeAndBehavior() {
        var p = io.github.jemmix.tdfa.re2j.Pattern.compile("[a-z]+\\d+", 0, EngineFactory.ASM);
        assertThat(p.getClass().getSimpleName()).startsWith("Gen").endsWith("Pattern");
        var m = p.matcher("abc123 rest");
        assertThat(m.getClass().getSimpleName()).startsWith("Gen").endsWith("Matcher");
        assertThat(m.find()).isTrue();
        assertThat(m.group()).isEqualTo("abc123");
        // serialization proxy round-trip: pattern+flags, not generated classes
        assertThat(p).hasToString("[a-z]+\\d+");
        var p2 = io.github.jemmix.tdfa.re2j.Pattern.compile("[a-z]+\\d+", 0, EngineFactory.VM);
        assertThat(p).isEqualTo(p2);   // state-based equality across impls
    }

    private static String padTo(String base, int len) {
        if (base.length() > len) return base.substring(0, len);
        if (base.length() < len) return base + "x".repeat(len - base.length());
        return base;
    }

    private static int compare(Regex vm, Regex asm, String in, String ctx) {
        // find()
        TdfaRunner.traceSnapshot();
        boolean f1 = vm.find(in);
        List<TdfaRunner.Strategy> t1 = TdfaRunner.traceSnapshot();
        boolean f2 = asm.find(in);
        List<TdfaRunner.Strategy> t2 = TdfaRunner.traceSnapshot();
        assertThat(f2).as("%s: find result", ctx).isEqualTo(f1);
        assertThat(t2).as("%s: find strategy trace (vm=%s)", ctx, t1).isEqualTo(t1);
        // find(in, 0)
        TdfaRunner.traceSnapshot();
        MatchResult m1 = vm.find(in, 0);
        t1 = TdfaRunner.traceSnapshot();
        MatchResult m2 = asm.find(in, 0);
        t2 = TdfaRunner.traceSnapshot();
        assertSameResult(m1, m2, ctx);
        assertThat(t2).as("%s: extract strategy trace (vm=%s)", ctx, t1).isEqualTo(t1);
        // matches()
        TdfaRunner.traceSnapshot();
        boolean b1 = vm.matches(in);
        t1 = TdfaRunner.traceSnapshot();
        boolean b2 = asm.matches(in);
        t2 = TdfaRunner.traceSnapshot();
        assertThat(b2).as("%s: matches result", ctx).isEqualTo(b1);
        assertThat(t2).as("%s: matches strategy trace (vm=%s)", ctx, t1).isEqualTo(t1);
        return 9;
    }

    private static int compareShim(io.github.jemmix.tdfa.re2j.Pattern vm,
                                   io.github.jemmix.tdfa.re2j.Pattern asm,
                                   String in, String ctx) {
        // Matcher iteration: find() until exhausted, then matches() on a fresh matcher
        var m1 = vm.matcher(in);
        var m2 = asm.matcher(in);
        int n1 = 0, n2 = 0;
        while (m1.find()) {
            n1++;
            assertThat(m2.find()).as("%s: shim find #%d", ctx, n1).isTrue();
            assertThat(m2.group()).as("%s: shim group #%d", ctx, n1).isEqualTo(m1.group());
            n2++;
        }
        while (m2.find()) n2++;
        assertThat(n2).as("%s: shim match count", ctx).isEqualTo(n1);
        assertThat(vm.matcher(in).matches()).isEqualTo(asm.matcher(in).matches());
        return 1;
    }

    private static void assertSameResult(MatchResult a, MatchResult b, String ctx) {
        if (a == null) {
            assertThat(b).as("%s: extract null", ctx).isNull();
            return;
        }
        assertThat(b).as("%s: extract non-null", ctx).isNotNull();
        assertThat(b.start(0)).as("%s: start", ctx).isEqualTo(a.start(0));
        assertThat(b.end(0)).as("%s: end", ctx).isEqualTo(a.end(0));
        assertThat(b.groupCount()).as("%s: groupCount", ctx).isEqualTo(a.groupCount());
        for (int g = 1; g <= a.groupCount(); g++) {
            assertThat(b.start(g)).as("%s: group %d start", ctx, g).isEqualTo(a.start(g));
            assertThat(b.end(g)).as("%s: group %d end", ctx, g).isEqualTo(a.end(g));
        }
    }
}
