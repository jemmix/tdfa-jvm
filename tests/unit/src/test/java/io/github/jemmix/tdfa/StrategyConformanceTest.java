package io.github.jemmix.tdfa;

import io.github.jemmix.tdfa.asm.TdfaAsmBackend;
import io.github.jemmix.tdfa.core.determinize.Determinizer;
import io.github.jemmix.tdfa.core.dfa.Tdfa;
import io.github.jemmix.tdfa.core.dfa.TdfaRunner;
import io.github.jemmix.tdfa.core.engine.MatchResult;
import io.github.jemmix.tdfa.core.engine.MatchScratch;
import io.github.jemmix.tdfa.core.engine.RegexEngine;
import io.github.jemmix.tdfa.core.engine.WholeEngine;
import io.github.jemmix.tdfa.core.tnfa.Tnfa;
import io.github.jemmix.tdfa.core.unicode.UnicodeProviders;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
 * CAND_SCAN_MAX=64, Latin-1 128/256, and the trigger window 2048), at the
 * engine tier ({@link RegexEngine}) and through the facade
 * ({@code Pattern.matcher}), with the trace hook enabled via
 * {@link TdfaRunner#setTracing(boolean)}.
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
        {"(\\d+)\\.(\\d+)\\.(\\d+)\\.(\\d+)", "connecting from ip=192.168.1.77 port 443 ok"}, {"(a|b)*c", "aabbaabbc"},
        {"\\w+@\\w+\\.(com|org|net)", "no addresses anywhere in this particular line at all"},
        {"[a-z]+qrst", "the quick brown fox jumps over the lazy dog qrstxx"},
        {"(?m)^line", "first\nline two\nline three\nline four\nline five"},
        // φ-variant accepting states (stateFinalOpsByMask on a fastPath
        // DFA): wholeOne's EOF gate/φ selection is compared span-exact
        // against wholeWalk via matchWhole.
        {"(?:.)((?:\\B)?)", "\ud800\udfff"}, {"(\\b)?", "word words"}, {".(?<n0>(\\z)*)", "_"},});

    private static final int[] LENGTHS =
        {1, 2, 3, 15, 40, 63, 64, 65, 100, 127, 128, 129, 255, 256, 257, 300, 2047, 2048, 2049, 4100};

    @BeforeAll
    static void enableTracing() {
        TdfaRunner.setTracing(true);
    }

    @AfterAll
    static void disableTracing() {
        TdfaRunner.setTracing(false);
    }

    /** Interpreter engine over the compiled DFA. */
    private static RegexEngine vm(String pattern) {
        return new TdfaRunner(tdfa(pattern));
    }

    /** Generated engine over the same DFA. */
    private static RegexEngine asm(String pattern) {
        return TdfaAsmBackend.generate(tdfa(pattern));
    }

    private static Tdfa tdfa(String pattern) {
        Tnfa nfa = Tnfa.compile(pattern, false, false, UnicodeProviders.get());
        return Determinizer.compile(nfa, false);
    }

    @Test
    void engineTierIdenticalStrategiesAndResults() {
        int checks = 0;
        for (String[] shape : SHAPES) {
            RegexEngine vm = vm(shape[0]);
            RegexEngine asm = asm(shape[0]);
            for (int len : LENGTHS) {
                String in = padTo(shape[1], len);
                checks += compare(vm, asm, in, "core " + shape[0] + " len=" + len);
            }
        }
        assertThat(checks).isGreaterThan(100);
    }

    @Test
    void facadeIdenticalStrategiesAndResults() {
        int checks = 0;
        for (String[] shape : SHAPES) {
            var vm = Pattern.compile(shape[0], 0, TdfaRunner::new);
            var asm = Pattern.compile(shape[0]);
            for (int len : LENGTHS) {
                String in = padTo(shape[1], len);
                checks += compareFacade(vm, asm, in, "facade " + shape[0] + " len=" + len);
            }
        }
        assertThat(checks).isGreaterThan(100);
    }

    /** Generated-pattern sanity: the default compile returns generated
     *  Pattern/Matcher classes (Gen* in a child loader) and they behave
     *  exactly like the BYO-interpreter composition. */
    @Test
    void generatedPatternClassShapeAndBehavior() {
        var p = Pattern.compile("[a-z]+\\d+");
        assertThat(p.getClass().getSimpleName()).startsWith("Gen").endsWith("Pattern");
        var m = p.matcher("abc123 rest");
        assertThat(m.getClass().getSimpleName()).startsWith("Gen").endsWith("Matcher");
        assertThat(m.find()).isTrue();
        assertThat(m.group()).isEqualTo("abc123");
        // serialization proxy round-trip: pattern+flags, not generated classes
        assertThat(p).hasToString("[a-z]+\\d+");
        var p2 = Pattern.compile("[a-z]+\\d+", 0, TdfaRunner::new);
        assertThat(p).isEqualTo(p2); // state-based equality across impls
    }

    /** RegexEngine.match's bounds contract at the engine tier: an
     *  out-of-range {@code from} throws the clean IndexOutOfBoundsException
     *  with the runner's message, in BOTH tiers. The INLINED ladder walks
     *  raw without the emitted check — a beyond-length from used to surface
     *  as a corrupt Match[0]=from,from (empty-match patterns) or a wrong
     *  null (everything else) instead of the throw. INLINED shapes
     *  (a*, (a|b)*c, \d+) and one DELEGATE shape (the literal needle) —
     *  the delegate path was always clean via the embedded runner. */
    @Test
    void engineTierFromBoundsContract() {
        for (String p : new String[]{"a*", "(a|b)*c", "\\d+", "abc"}) {
            RegexEngine vm = vm(p);
            RegexEngine asm = asm(p);
            for (int from : new int[]{-1, 4, 99}) {
                assertThatThrownBy(() -> asm.match("abc", from, null)).as("asm %s from=%d", p, from)
                    .isInstanceOf(IndexOutOfBoundsException.class).hasMessage("from: " + from + ", length: 3");
                assertThatThrownBy(() -> vm.match("abc", from, null)).as("vm %s from=%d", p, from)
                    .isInstanceOf(IndexOutOfBoundsException.class).hasMessage("from: " + from + ", length: 3");
            }
            // from == len stays legal and agrees across tiers:
            MatchResult a = asm.match("abc", 3, null);
            MatchResult v = vm.match("abc", 3, null);
            assertThat(a == null).as("%s from=len nullity", p).isEqualTo(v == null);
            if (v != null) {
                assertThat(a.start(0)).isEqualTo(v.start(0));
                assertThat(a.end(0)).isEqualTo(v.end(0));
            }
        }
    }

    private static String padTo(String base, int len) {
        if (base.length() > len) {
            return base.substring(0, len);
        }
        if (base.length() < len) {
            return base + "x".repeat(len - base.length());
        }
        return base;
    }

    private static int compare(RegexEngine vm, RegexEngine asm, String in, String ctx) {
        // find()
        TdfaRunner.traceSnapshot();
        boolean f1 = vm.find(in);
        List<TdfaRunner.Strategy> t1 = TdfaRunner.traceSnapshot();
        boolean f2 = asm.find(in);
        List<TdfaRunner.Strategy> t2 = TdfaRunner.traceSnapshot();
        assertThat(f2).as("%s: find result", ctx).isEqualTo(f1);
        assertThat(t2).as("%s: find strategy trace (vm=%s)", ctx, t1).isEqualTo(t1);
        // match(in, 0)
        TdfaRunner.traceSnapshot();
        MatchResult m1 = vm.match(in, 0, new MatchScratch());
        t1 = TdfaRunner.traceSnapshot();
        MatchResult m2 = asm.match(in, 0, new MatchScratch());
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
        // matchWhole() — the generated wholeOne leaf vs the runner's wholeWalk
        TdfaRunner.traceSnapshot();
        MatchResult w1 = ((WholeEngine) vm).matchWhole(in, new MatchScratch());
        t1 = TdfaRunner.traceSnapshot();
        MatchResult w2 = ((WholeEngine) asm).matchWhole(in, new MatchScratch());
        t2 = TdfaRunner.traceSnapshot();
        assertSameResult(w1, w2, ctx + " [matchWhole]");
        assertThat(t2).as("%s: matchWhole strategy trace (vm=%s)", ctx, t1).isEqualTo(t1);
        // CharSequence input: both must take the GENERIC delegation path
        CharSequence cs = new StringBuilder(in);
        assertThat(((WholeEngine) asm).matchWhole(cs, new MatchScratch()) == null)
            .as("%s: matchWhole(CharSequence) nullity", ctx)
            .isEqualTo(((WholeEngine) vm).matchWhole(cs, new MatchScratch()) == null);
        return 9;
    }

    private static int compareFacade(Pattern vm, Pattern asm, String in, String ctx) {
        // Matcher iteration: find() until exhausted, then matches() on a fresh matcher
        var m1 = vm.matcher(in);
        var m2 = asm.matcher(in);
        int n1 = 0, n2 = 0;
        while (m1.find()) {
            n1++;
            assertThat(m2.find()).as("%s: facade find #%d", ctx, n1).isTrue();
            assertThat(m2.group()).as("%s: facade group #%d", ctx, n1).isEqualTo(m1.group());
            n2++;
        }
        while (m2.find()) {
            n2++;
        }
        assertThat(n2).as("%s: facade match count", ctx).isEqualTo(n1);
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
