package io.github.jemmix.tdfa;

import io.github.jemmix.tdfa.asm.TdfaAsmBackend;
import io.github.jemmix.tdfa.core.MatchResult;
import io.github.jemmix.tdfa.core.MatchScratch;
import io.github.jemmix.tdfa.core.RegexEngine;
import io.github.jemmix.tdfa.tdfa.Tdfa;
import io.github.jemmix.tdfa.tdfa.TdfaRunner;
import io.github.jemmix.tdfa.tnfa.Tnfa;
import io.github.jemmix.tdfa.unicode.UnicodeProviders;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Literal-prefix acceleration, engine tier: patterns whose TDFA starts with
 * a required literal chain (ip=-shaped log queries) must search via
 * String.indexOf candidate walks — and stay exactly correct while doing it.
 * Pins (a) the detector's shape decisions, (b) the PREFIX strategy firing
 * with identical trace sequences on both backends, and (c) the walk-budget
 * fallback on dense-hit adversarial shapes (still correct, still linear).
 * The java.util.regex differential sweep lives in PrefixParityTest (its own
 * package — the two Pattern types cannot share a compile unit here).
 */
class PrefixScanTest {

    @BeforeAll
    static void enableTracing() {
        TdfaRunner.setTracing(true);
    }

    @AfterAll
    static void disableTracing() {
        TdfaRunner.setTracing(false);
    }

    private static Tdfa tdfa(String pattern) {
        Tnfa nfa = Tnfa.compile(pattern, false, false, UnicodeProviders.get());
        return Tdfa.compile(nfa, false);
    }

    @Test
    void detectorPinsShapeDecisions() {
        assertThat(TdfaRunner.detectPrefixNeedle(tdfa("ip=(\\d+\\.\\d+\\.\\d+\\.\\d+)"))).isEqualTo("ip=");
        assertThat(TdfaRunner.detectPrefixNeedle(tdfa("user_id=(\\d+).*?status=(\\d+)"))).isEqualTo("user_id=");
        assertThat(TdfaRunner.detectPrefixNeedle(tdfa("path=(/[a-z0-9/]+)"))).isEqualTo("path=/");
        assertThat(TdfaRunner.detectPrefixNeedle(tdfa("(ip)=\\d+"))).isEqualTo("ip=");
        // fork after a shared chain: the chain prefix is still required
        assertThat(TdfaRunner.detectPrefixNeedle(tdfa("abcx|abcd"))).isEqualTo("abc");
        assertThat(TdfaRunner.detectPrefixNeedle(tdfa("ab?c"))).isEqualTo("a");
        assertThat(TdfaRunner.detectPrefixNeedle(tdfa("x{2,}"))).isEqualTo("xx");
        // no required first char at all
        assertThat(TdfaRunner.detectPrefixNeedle(tdfa("(a|b)*c"))).isNull();
        assertThat(TdfaRunner.detectPrefixNeedle(tdfa("(?:ip|host)="))).isNull();
        assertThat(TdfaRunner.detectPrefixNeedle(tdfa("\\w+=\\d+"))).isNull();
        assertThat(TdfaRunner.detectPrefixNeedle(tdfa("[\\s\\S]{0,60}x[\\s\\S]{0,60}"))).isNull();
        // fold: 'i' and 'I' both lead onward — not a single-char chain
        assertThat(TdfaRunner.detectPrefixNeedle(tdfa("(?i)ip=\\d+"))).isNull();
        // mask-gated exit (\b): v1 keeps the old ladder
        assertThat(TdfaRunner.detectPrefixNeedle(tdfa("\\bip=\\d+"))).isNull();
        // entry-masked start (^): the restart ladder owns it
        assertThat(TdfaRunner.detectPrefixNeedle(tdfa("^ip=\\d+"))).isNull();
        // detection caps at PREFIX_NEEDLE_MAX even on long chains
        String longNeedle = "b".repeat(40);
        assertThat(TdfaRunner.detectPrefixNeedle(tdfa(longNeedle + "\\d+"))).hasSize(32);
    }

    /** Whole-regex literals keep their own (indexOf, no-walk) path. */
    @Test
    void literalNeedleTakesPriorityOverPrefix() {
        RegexEngine vm = new TdfaRunner(tdfa("ip="));
        TdfaRunner.traceSnapshot();
        assertThat(vm.find("xx ip= yy")).isTrue();
        List<TdfaRunner.Strategy> t = TdfaRunner.traceSnapshot();
        assertThat(t).containsExactly(TdfaRunner.Strategy.LITERAL);
    }

    @Test
    void prefixStrategyFiresIdenticallyOnBothTiers() {
        for (String pat : new String[]{"ip=(\\d+\\.\\d+\\.\\d+\\.\\d+)", "user_id=(\\d+).*?status=(\\d+)",
            "path=(/[a-z0-9/]+)"}) {
            RegexEngine vm = new TdfaRunner(tdfa(pat));
            RegexEngine asm = TdfaAsmBackend.generate(tdfa(pat));
            for (String in : new String[]{"no queries in this line at all",
                "connecting from ip=192.168.1.77 port 443 ok",
                "user_id=42 and then user_id=7 status=500 while ip=10.0.0.1 path=/a/b status=200",
                "ip=1.2.3.4ip=5.6.7.8 tail", "level=info ip=", "x".repeat(300) + " ip=9.9.9.9",
                // several non-matching occurrences before the real one: the
                // adaptive boolean prefilter activates mid-scan (walks >= 3)
                // and must still hand CONFIRMED hits to the extract walk
                "ip=xx ip=yy ip=zz ip=1.2.3.4", "user_id=a user_id=b user_id=c user_id=7 status=5",
                "path=/X path=/Y path=/Z path=/ok1"}) {
                // boolean find()
                TdfaRunner.traceSnapshot();
                boolean f1 = vm.find(in);
                List<TdfaRunner.Strategy> t1 = TdfaRunner.traceSnapshot();
                boolean f2 = asm.find(in);
                List<TdfaRunner.Strategy> t2 = TdfaRunner.traceSnapshot();
                assertThat(f2).as("%s / %s: find", pat, in).isEqualTo(f1);
                assertThat(t2).as("%s / %s: find trace (vm=%s)", pat, in, t1).isEqualTo(t1);
                assertThat(t1).as("%s / %s: PREFIX used", pat, in).contains(TdfaRunner.Strategy.PREFIX);
                // extract via match()
                TdfaRunner.traceSnapshot();
                MatchResult m1 = vm.match(in, 0, new MatchScratch());
                t1 = TdfaRunner.traceSnapshot();
                MatchResult m2 = asm.match(in, 0, new MatchScratch());
                t2 = TdfaRunner.traceSnapshot();
                assertSame(m1, m2, pat + " / " + in);
                assertThat(t2).as("%s / %s: extract trace (vm=%s)", pat, in, t1).isEqualTo(t1);
                // PREFIX serves every extract whose match does not start at 0
                // (a match at `from` is answered by EXACT_FROM alone).
                if (m1 == null || m1.start(0) != 0) {
                    assertThat(t1).as("%s / %s: PREFIX used", pat, in).contains(TdfaRunner.Strategy.PREFIX);
                }
            }
        }
    }

    /**
     * Dense-hit shapes that exhaust PREFIX_WALK_BUDGET must fall back to the
     * complete ladder and still answer correctly (and actually terminate —
     * the budget is what keeps these linear). Expectations hardcoded: the
     * independent java.util.regex sweep is PrefixParityTest's job.
     */
    @Test
    void denseHitBudgetFallbackStaysCorrect() {
        String[] pats = {"a.*x", "aa[0-9]"};
        String[] inputs = {"a".repeat(200), "a".repeat(2000), "a".repeat(500) + "b", "a".repeat(2000) + "x9",
            "a".repeat(64) + "b", "a".repeat(63) + "9a"};
        for (String pat : pats) {
            var vm = Pattern.compile(pat, 0, TdfaRunner::new);
            var asm = Pattern.compile(pat);
            for (String in : inputs) {
                boolean exp = pat.equals("a.*x") ? in.indexOf('x') >= 0 : endsWithDigitAfterRun(in);
                assertThat(vm.matcher(in).find()).as("%s / len=%d: vm", pat, in.length()).isEqualTo(exp);
                assertThat(asm.matcher(in).find()).as("%s / len=%d: asm", pat, in.length()).isEqualTo(exp);
                assertThat(vm.matcher(in).find()).isEqualTo(asm.matcher(in).find());
            }
        }
    }

    private static boolean endsWithDigitAfterRun(String s) {
        for (int i = 0; i + 2 < s.length(); i++) {
            if (s.charAt(i) == 'a' && s.charAt(i + 1) == 'a' && s.charAt(i + 2) >= '0' && s.charAt(i + 2) <= '9') {
                return true;
            }
        }
        return false;
    }

    /** find(int) from inside a needle occurrence and across supplementary text. */
    @Test
    void explicitFromAndSupplementaryGuards() {
        String in = "zz \uD800\uDC00 ip=1.2.3.4 \uD800\uDC00 ip=5.6.7.8";
        var vm = Pattern.compile("ip=(\\d+\\.\\d+\\.\\d+\\.\\d+)", 0, TdfaRunner::new);
        var asm = Pattern.compile("ip=(\\d+\\.\\d+\\.\\d+\\.\\d+)");
        for (int from = 0; from <= in.length(); from++) {
            var m1 = vm.matcher(in);
            var m2 = asm.matcher(in);
            // the first ip= occurrence starts at or after `from`, else the second
            int firstIp = in.indexOf("ip=");
            int secondIp = in.indexOf("ip=", firstIp + 1);
            boolean exp = (from <= firstIp) || (from <= secondIp);
            assertThat(m1.find(from)).as("from=%d: vm", from).isEqualTo(exp);
            assertThat(m2.find(from)).as("from=%d: asm", from).isEqualTo(exp);
            if (exp) {
                assertThat(m1.group()).isEqualTo(m2.group());
                assertThat(m1.group(1)).isEqualTo(m2.group(1));
            }
        }
        assertThat(vm.matcher("\uD800ip=1.2.3.4").find()).isTrue();
        assertThat(asm.matcher("ip=1.2.3.4\uDFFF").find()).isTrue();
    }

    private static void assertSame(MatchResult a, MatchResult b, String ctx) {
        if (a == null) {
            assertThat(b).as("%s: extract null", ctx).isNull();
        } else {
            assertThat(b).as("%s: extract non-null", ctx).isNotNull();
            assertThat(b.start(0)).isEqualTo(a.start(0));
            assertThat(b.end(0)).isEqualTo(a.end(0));
        }
    }
}
