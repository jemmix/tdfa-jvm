package io.github.jemmix.tdfa;

import io.github.jemmix.tdfa.asm.TdfaAsmBackend;
import io.github.jemmix.tdfa.core.determinize.Determinizer;
import io.github.jemmix.tdfa.core.dfa.Tdfa;
import io.github.jemmix.tdfa.core.dfa.TdfaRunner;
import io.github.jemmix.tdfa.core.engine.MatchResult;
import io.github.jemmix.tdfa.core.engine.MatchScratch;
import io.github.jemmix.tdfa.core.engine.RegexEngine;
import io.github.jemmix.tdfa.core.tnfa.Tnfa;
import io.github.jemmix.tdfa.core.unicode.UnicodeProviders;
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
        return Determinizer.compile(nfa, false);
    }

    /** The chain's case-stable needle run and its offset from match start. */
    private static String needle(String pattern) {
        TdfaRunner.PrefixChain c = TdfaRunner.detectPrefixChain(tdfa(pattern));
        return c == null ? null : c.needle + " @" + c.offset;
    }

    @Test
    void detectorPinsShapeDecisions() {
        // v1 shapes: the whole chain case-stable, needle at offset 0
        assertThat(needle("ip=(\\d+\\.\\d+\\.\\d+\\.\\d+)")).isEqualTo("ip= @0");
        assertThat(needle("user_id=(\\d+).*?status=(\\d+)")).isEqualTo("user_id= @0");
        assertThat(needle("path=(/[a-z0-9/]+)")).isEqualTo("path=/ @0");
        assertThat(needle("(ip)=\\d+")).isEqualTo("ip= @0");
        // fork after a shared chain: the chain prefix is still required
        assertThat(needle("abcx|abcd")).isEqualTo("abc @0");
        assertThat(needle("ab?c")).isEqualTo("a @0");
        assertThat(needle("x{2,}")).isEqualTo("xx @0");
        // no required first char at all
        assertThat(needle("(a|b)*c")).isNull();
        assertThat(needle("(?:ip|host)=")).isNull();
        assertThat(needle("\\w+=\\d+")).isNull();
        assertThat(needle("[\\s\\S]{0,60}x[\\s\\S]{0,60}")).isNull();
        // fold: 'i'/'I' both lead onward as one-unit steps — the chain runs
        // THROUGH them and the needle is the case-stable run inside it
        assertThat(needle("(?i)ip=\\d+")).isEqualTo("= @2");
        assertThat(needle("(?i)user_id=\\d+")).isEqualTo("= @7"); // tie len 1: last run wins
        assertThat(needle("(?i)ERROR: code=\\d+")).isEqualTo(": " + " @5");
        assertThat(needle("(?:a|A)bcd")).isEqualTo("bcd @1");
        // fold all the way through: no case-stable run anywhere
        assertThat(needle("(?i)kelvin")).isNull();
        // word-gated chains (\b, \B — start or mid-chain): the walk
        // evaluates the gate at each candidate, so the needle stands
        assertThat(needle("\\bip=\\d+")).isEqualTo("ip= @0");
        assertThat(needle("\\Bip=\\d+")).isEqualTo("ip= @0");
        assertThat(needle("ab\\b!")).isEqualTo("ab! @0");
        // class-range steps are one unit wide but never needle text
        assertThat(needle("\\b\\w+x")).isNull();
        // non-word gates (^, \A, $-family): the restart ladder owns them
        assertThat(needle("^ip=\\d+")).isNull();
        // detection caps at PREFIX_NEEDLE_MAX even on long chains
        String longNeedle = "b".repeat(40);
        assertThat(needle(longNeedle + "\\d+")).isEqualTo("b".repeat(32) + " @0");
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
        // v2 rows: fold-offset chains (fastPath/INLINED — the emitted
        // ladder carries the offset) and word-gated chains (masked/DELEGATE
        // — the VM loop's walk evaluates the gate)
        for (String pat : new String[]{"ip=(\\d+\\.\\d+\\.\\d+\\.\\d+)", "user_id=(\\d+).*?status=(\\d+)",
            "path=(/[a-z0-9/]+)", "(?i)ERROR: code=\\d+", "(?i)user_id=\\d+", "\\berror\\b", "ab\\b!"}) {
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
                "path=/X path=/Y path=/Z path=/ok1",
                // v2: folded and word-gated variants of the same lines
                "ERROR: code=42 and error: code=7 tail", "error: nope ERROR: code=9 USER_ID=3",
                "user_id=9 USER_ID=8 user_id=7", "errors error errorist error xerror", "ab! xab! ab! ab",
                "Error: CODE=1 error: code=2 ERROR: CODE=3"}) {
                // boolean find()
                TdfaRunner.traceSnapshot();
                boolean f1 = vm.find(in);
                List<TdfaRunner.Strategy> t1 = TdfaRunner.traceSnapshot();
                boolean f2 = asm.find(in);
                List<TdfaRunner.Strategy> t2 = TdfaRunner.traceSnapshot();
                assertThat(f2).as("%s / %s: find", pat, in).isEqualTo(f1);
                assertThat(t2).as("%s / %s: find trace (vm=%s)", pat, in, t1).isEqualTo(t1);
                // PREFIX serves every find unless the one exact walk from 0
                // already answered (non-fastPath shapes with a match at 0)
                if (!t1.equals(List.of(TdfaRunner.Strategy.EXACT_FROM))) {
                    assertThat(t1).as("%s / %s: PREFIX used", pat, in).contains(TdfaRunner.Strategy.PREFIX);
                }
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
     * independent java.util.regex sweep is PrefixParityTest's job. The
     * gated row checks the same under a start \b: only position 0 passes
     * the gate, every later hit dies in the walk.
     */
    @Test
    void denseHitBudgetFallbackStaysCorrect() {
        String[] pats = {"a.*x", "aa[0-9]", "\\baa[0-9]"};
        String[] inputs = {"a".repeat(200), "a".repeat(2000), "a".repeat(500) + "b", "a".repeat(2000) + "x9",
            "a".repeat(64) + "b", "a".repeat(63) + "9a", "aa" + "a".repeat(198), "b" + "a".repeat(200) + "9",
            "a".repeat(64) + "9" + "aa9", "aa9" + "a".repeat(97)};
        for (String pat : pats) {
            var vm = Pattern.compile(pat, 0, TdfaRunner::new);
            var asm = Pattern.compile(pat);
            for (String in : inputs) {
                boolean exp = expected(pat, in);
                assertThat(vm.matcher(in).find()).as("%s / len=%d: vm", pat, in.length()).isEqualTo(exp);
                assertThat(asm.matcher(in).find()).as("%s / len=%d: asm", pat, in.length()).isEqualTo(exp);
                assertThat(vm.matcher(in).find()).isEqualTo(asm.matcher(in).find());
            }
        }
    }

    private static boolean expected(String pat, String in) {
        if (pat.equals("a.*x")) {
            return in.indexOf('x') >= 0;
        }
        // aa[0-9] and \baa[0-9]: the run "aa" followed by a digit. The
        // gated variant needs a word boundary at the aa start — position 0
        // or a non-word char before it.
        for (int i = 0; i + 2 < in.length(); i++) {
            if (in.charAt(i) == 'a' && in.charAt(i + 1) == 'a' && in.charAt(i + 2) >= '0' && in.charAt(i + 2) <= '9') {
                boolean bounded = i == 0 || !isWord(in.charAt(i - 1));
                if (pat.startsWith("\\b") && !bounded) {
                    continue;
                }
                return true;
            }
        }
        return false;
    }

    private static boolean isWord(char c) {
        return c == '_' || c >= '0' && c <= '9' || c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z';
    }

    /** find(int) from inside a needle occurrence and across supplementary
     *  text — plus the fold-offset twin, where the needle sits 2 units
     *  after the candidate and the start guard must follow the CANDIDATE
     *  across pair boundaries, not the hit. */
    @Test
    void explicitFromAndSupplementaryGuards() {
        String in = "zz \uD800\uDC00 ip=1.2.3.4 \uD800\uDC00 ip=5.6.7.8";
        for (String pat : new String[]{"ip=(\\d+\\.\\d+\\.\\d+\\.\\d+)", "(?i)IP=(\\d+\\.\\d+\\.\\d+\\.\\d+)"}) {
            var vm = Pattern.compile(pat, 0, TdfaRunner::new);
            var asm = Pattern.compile(pat);
            for (int from = 0; from <= in.length(); from++) {
                var m1 = vm.matcher(in);
                var m2 = asm.matcher(in);
                // the first ip= occurrence starts at or after `from`, else the second
                int firstIp = in.indexOf("ip=");
                int secondIp = in.indexOf("ip=", firstIp + 1);
                boolean exp = (from <= firstIp) || (from <= secondIp);
                assertThat(m1.find(from)).as("%s from=%d: vm", pat, from).isEqualTo(exp);
                assertThat(m2.find(from)).as("%s from=%d: asm", pat, from).isEqualTo(exp);
                if (exp) {
                    assertThat(m1.group()).isEqualTo(m2.group());
                    assertThat(m1.group(1)).isEqualTo(m2.group(1));
                }
            }
        }
        assertThat(vmFind("ip=(\\d+\\.\\d+\\.\\d+\\.\\d+)", "\uD800ip=1.2.3.4")).isTrue();
        assertThat(asmFind("ip=(\\d+\\.\\d+\\.\\d+\\.\\d+)", "ip=1.2.3.4\uDFFF")).isTrue();
        // fold-offset start guard: the '=' hit's candidate (hit - 2) lands
        // on a pair's low half — skipped on the codepoint lane, walked
        // nowhere else; no match either way.
        assertThat(vmFind("(?i)ab=", "\uD800\uD800\uDC00x=")).isFalse();
        assertThat(asmFind("(?i)ab=", "\uD800\uD800\uDC00x=")).isFalse();
        assertThat(vmFind("(?i)ab=", "ab=")).isTrue();
        assertThat(asmFind("(?i)ab=", "aB=")).isTrue();
    }

    private static boolean vmFind(String pat, String in) {
        return Pattern.compile(pat, 0, TdfaRunner::new).matcher(in).find();
    }

    private static boolean asmFind(String pat, String in) {
        return Pattern.compile(pat).matcher(in).find();
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
