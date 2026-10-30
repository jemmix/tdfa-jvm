package io.github.jemmix.tdfa.prefix;

import io.github.jemmix.tdfa.core.dfa.TdfaRunner;
import org.junit.jupiter.api.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Literal-prefix acceleration, differential tier: the same prefix-shape
 * catalog as PrefixScanTest, compared span-exact against java.util.regex
 * through the facade (both backends) — match/miss/dense/iteration shapes.
 * Lives in its own package so the two same-simple-named Pattern types can
 * coexist (java.util.regex.Pattern imported, the facade fully qualified —
 * the formatter's collision skip keeps it that way).
 */
class PrefixParityTest {

    private static final String[] PATS = {"ip=(\\d+\\.\\d+\\.\\d+\\.\\d+)", "user_id=(\\d+).*?status=(\\d+)",
        "path=(/[a-z0-9/]+)", "abcx|abcd", "ab?c", "x{2,}", "key=(?:val|other)", "a.*x", "aa[0-9]",
        // the Fowler basic.dat shape that caught the emitted ladder's
        // prefilter polarity: three dead hits before the live one
        "abaa|abbaa|abbbaa|abbbbaa",
        // v2 chains: fold steps before the case-stable run (needle at an
        // offset), explicit fold sets, and word-gated starts/middles —
        // incl. the dense-hit offset shape from re2-exhaustive.txt:2526228
        "(?i)ip=(\\d+\\.\\d+\\.\\d+\\.\\d+)", "(?i)ERROR: code=\\d+", "(?i)user_id=\\d+", "(?:a|A)bcd", "(?:(?:a|b)a)",
        "\\berror\\b", "\\bip=\\d+", "ab\\b!", "\\baa[0-9]"};

    private static final String[] INPUTS = {"", "ip=", "ip=1", "ip=1.2.3.4", "say ip=1.2.3.4 twice ip=5.6.7.8 ok",
        "nothing relevant here", "user_id=7 re status=404 tail", "user_id=7 x status=404", "path=/a/b/c and path=/x",
        "abc abcd abcx", "c ac abc abbc", "x xx xxx xxxx", "key=val key=other key=nope", "prefix ip=1.2.3.4 suffix",
        "  ip=255.255.255.255  ", "ab\uDBFF\uD800cd ip=1.2.3.4", "\uD800\uDC00ip=1.2.3.4\uD800\uDC00",
        "ip=xx ip=yy ip=zz ip=1.2.3.4", "ababbabbbabbbabbbbabbbbaa", "ababbabbbabbbabbbbabaa", "abaa", "abbbaa",
        "a".repeat(200), "a".repeat(2000), "a".repeat(500) + "b", "a".repeat(2000) + "x9",
        // v2: folded and gated variants of the same shapes
        "IP=1.2.3.4 iP=5.6.7.8", "ERROR: code=42 error: code=7 Error: CODE=9", "user_id=9 USER_ID=8 User_Id=7",
        "errors error errorist error xerror", "xabcd Abcd aBcd abCd abcd", "xab! ab! ab!ab ab", "aa9" + "a".repeat(97),
        "b" + "a".repeat(200) + "9", "a".repeat(64) + "9" + "aa9",
        // the exhaustive-corpus inputs: needle hits denser than the offset
        "a☺aa", "☺☺aa", "☺aaa", "aaaa", "ba", "aba"};

    @Test
    void facadeParityWithJavaUtilRegex() {
        for (String pat : PATS) {
            Pattern jur = Pattern.compile(pat);
            var vm = io.github.jemmix.tdfa.Pattern.compile(pat, 0, TdfaRunner::new);
            var asm = io.github.jemmix.tdfa.Pattern.compile(pat);
            for (String in : INPUTS) {
                String ctx = pat + " / len=" + in.length();
                var r = jur.matcher(in);
                var m1 = vm.matcher(in);
                var m2 = asm.matcher(in);
                int n = 0;
                while (r.find()) {
                    n++;
                    assertThat(m1.find()).as("%s: vm find #%d", ctx, n).isTrue();
                    assertThat(m2.find()).as("%s: asm find #%d", ctx, n).isTrue();
                    for (int g = 0; g <= r.groupCount(); g++) {
                        assertThat(m1.group(g)).as("%s: vm group %d", ctx, g).isEqualTo(r.group(g));
                        assertThat(m2.group(g)).as("%s: asm group %d", ctx, g).isEqualTo(r.group(g));
                    }
                    assertThat(m1.start()).isEqualTo(r.start());
                    assertThat(m2.end()).isEqualTo(r.end());
                }
                assertThat(m1.find()).as("%s: vm exhausted", ctx).isFalse();
                assertThat(m2.find()).as("%s: asm exhausted", ctx).isFalse();
                assertThat(vm.matcher(in).matches()).as("%s: vm matches()", ctx).isEqualTo(r.matches());
                assertThat(asm.matcher(in).matches()).as("%s: asm matches()", ctx).isEqualTo(r.matches());
            }
        }
    }

    /** find(int) sweep across a supplementary-wrapped double occurrence. */
    @Test
    void explicitFromParity() {
        String in = "zz \uD800\uDC00 ip=1.2.3.4 \uD800\uDC00 ip=5.6.7.8";
        Pattern jur = Pattern.compile("ip=(\\d+\\.\\d+\\.\\d+\\.\\d+)");
        var vm = io.github.jemmix.tdfa.Pattern.compile("ip=(\\d+\\.\\d+\\.\\d+\\.\\d+)", 0, TdfaRunner::new);
        var asm = io.github.jemmix.tdfa.Pattern.compile("ip=(\\d+\\.\\d+\\.\\d+\\.\\d+)");
        for (int from = 0; from <= in.length(); from++) {
            var r = jur.matcher(in);
            var m1 = vm.matcher(in);
            var m2 = asm.matcher(in);
            boolean e1 = r.find(from);
            assertThat(m1.find(from)).as("from=%d: vm", from).isEqualTo(e1);
            assertThat(m2.find(from)).as("from=%d: asm", from).isEqualTo(e1);
            if (e1) {
                assertThat(m1.group()).isEqualTo(r.group());
                assertThat(m2.group(1)).isEqualTo(r.group(1));
            }
        }
    }

    /**
     * Dense needle hits vs the offset: the exhaustive corpus shape that
     * caught the resume-floor bug (re2-exhaustive.txt:2526228 — needle "a"
     * at offset 1: after the pair-interior candidate "a☺|aa|", the next
     * hit sits at idx + 1, NOT candidate + 1 + offset; a candidate-floor
     * resume skipped it and the scan reported no match). Exhaustive sweep
     * over a small alphabet incl. a supplementary char, both tiers,
     * iterative find() so every resume position is crossed.
     */
    @Test
    void denseNeedleHitsEnumerateEveryCandidate() {
        String[] pats = {"(?:(?:a|b)a)", "(?:(?:(?:a|b)a))$", "(?i)ab=", "(a|b)b?b"};
        char[] sigma = {'a', 'b', '\u263A', '='};
        StringBuilder[] words = {new StringBuilder()};
        for (int len = 0; len < 4; len++) {
            StringBuilder[] next = new StringBuilder[words.length * sigma.length];
            int k = 0;
            for (StringBuilder w : words) {
                for (char c : sigma) {
                    next[k++] = new StringBuilder(w).append(c);
                }
            }
            words = next;
            for (String pat : pats) {
                for (StringBuilder w : words) {
                    String in = w.toString();
                    var m1 = io.github.jemmix.tdfa.Pattern.compile(pat, 0, TdfaRunner::new).matcher(in);
                    var m2 = io.github.jemmix.tdfa.Pattern.compile(pat).matcher(in);
                    Matcher m3 = Pattern.compile(pat).matcher(in);
                    int n = 0;
                    while (m3.find()) {
                        n++;
                        assertThat(m1.find()).as("%s / %s: vm find #%d", pat, in, n).isTrue();
                        assertThat(m2.find()).as("%s / %s: asm find #%d", pat, in, n).isTrue();
                        assertThat(m1.group()).as("%s / %s: vm span #%d", pat, in, n).isEqualTo(m3.group());
                        assertThat(m1.group()).isEqualTo(m2.group());
                    }
                    assertThat(m1.find()).as("%s / %s: vm exhausted", pat, in).isFalse();
                    assertThat(m2.find()).as("%s / %s: asm exhausted", pat, in).isFalse();
                }
            }
        }
    }
}
