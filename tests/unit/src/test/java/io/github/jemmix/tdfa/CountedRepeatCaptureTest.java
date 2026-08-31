package io.github.jemmix.tdfa;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Final-capture semantics of open counted repetition on a nullable body —
 * the fuzz round-9 family (25 records, 16 patterns, all PARSER-layer: our
 * own NFA built the wrong shape).
 *
 * <p>Contract (re2j's Simplify, mirrored by design in
 * {@code Tnfa.Builder}): {@code x{n,} = x{n-1}x+}. The plus tail guarantees
 * one real iteration; the nullable body's empty <b>re</b>-iteration is cut
 * by the pike pc-dedup, so the reported group is the <b>last non-empty</b>
 * iteration's capture. The former {@code x{n}x*} desugaring let the greedy
 * star's first-iteration-empty write an empty capture — {@code (a?){2,}}
 * on {@code "aa"} reported {@code g1=""} where re2j reports {@code "a"}.
 *
 * <p>Values here are the oracle-verified ones (java.util.regex differs on
 * this family — it reports {@code ""} — so these are pinned as literals,
 * not against jur).
 */
class CountedRepeatCaptureTest {

    private static String g1(String pattern, String input) {
        var m = io.github.jemmix.tdfa.Pattern.compile(pattern).matcher(input);
        assertThat(m.find()).as("find(%s on %s)", pattern, input).isTrue();
        return m.group(1);
    }

    @Test
    void nullableBodyOpenCountedReportsLastNonEmptyCapture() {
        assertThat(g1("(a?){2,}", "aa")).isEqualTo("a");
        assertThat(g1("(.?){2,}", "xy")).isEqualTo("y");
        assertThat(g1("(.{0,2}){2,}", "abc")).isEqualTo("c");
        assertThat(g1("(a?){3,}", "aaaa")).isEqualTo("a");
        assertThat(g1("((a)?){2,}", "aa")).isEqualTo("a");       // nested group
        assertThat(g1("(\\W?){2,}", "!!")).isEqualTo("!");       // class body
        assertThat(g1("x(a?){2,}", "xaa")).isEqualTo("a");       // leading context
        assertThat(g1("(a?){2,}b", "aab")).isEqualTo("a");       // trailing context
    }

    @Test
    void lazyAndEmptyBodyVariants() {
        assertThat(g1("(a?){2,}?", "aab")).isEqualTo("a");       // lazy open counted
        assertThat(g1("(.{0,2}){2,}?", "abcd")).isEqualTo("cd");
        assertThat(g1("(a{0,}){2,}", "aa")).isEmpty();           // star body: "" in re2j too
        assertThat(g1("(a??){2,}", "aa")).isEmpty();             // lazy-null body: empty overall match
        assertThat(g1("(a?){2,}", "b")).isEmpty();               // zero-width overall match, g1=""
    }

    @Test
    void bothEngineTiersAgree() {
        // The desugaring lives in Tnfa construction, so both tiers inherit
        // it — but pin the interpreter explicitly; the ASM tier is the
        // facade default used by g1() above.
        String pat = "(a?){2,}";
        var interp = io.github.jemmix.tdfa.Pattern.compile(
                pat, 0, io.github.jemmix.tdfa.tdfa.TdfaRunner::new);
        var m = interp.matcher("aa");
        assertThat(m.find()).isTrue();
        assertThat(m.group(1)).isEqualTo("a");
    }
}
