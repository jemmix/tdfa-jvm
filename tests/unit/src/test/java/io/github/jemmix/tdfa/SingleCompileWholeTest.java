package io.github.jemmix.tdfa;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Single-compile whole/find semantics: one parse, at most two artifacts.
 * matches() runs a cut-free walk to EOF (an accept alive at end-of-input is
 * a full match) while find() keeps leftmost-first — the two answer the same
 * input differently by design ({@code (a|ab)} whole-matches "ab" while find
 * stops at "a"), and the divergence-class alternations ({@code ab|a|ac},
 * where the pike cut is load-bearing for find) keep both answers correct via
 * the pruned find artifact. Pinned on BOTH tiers (default ASM shells and
 * {@code -Dtdfa.engine=VM}).
 */
class SingleCompileWholeTest {

    @AfterEach
    void clearVmSwitch() {
        System.clearProperty("tdfa.engine");
    }

    private void expectCommon() {
        // Whole needs the un-pruned continuation; find is leftmost-first.
        Pattern p = Pattern.compile("(a|ab)");
        PatternMatcher m = p.matcher("ab");
        assertThat(m.matches()).isTrue();
        assertThat(m.matches()).isTrue();              // repeat: eager engine, no lazy state
        assertThat(m.group()).isEqualTo("ab");
        assertThat(p.matcher("ac").matches()).isFalse();
        PatternMatcher mf = p.matcher("ab");
        assertThat(mf.find()).isTrue();
        assertThat(mf.group()).isEqualTo("a");   // leftmost-first

        // Divergence class: the pike cut matters (find gets its own pruned
        // artifact); whole and find must both stay exact.
        Pattern q = Pattern.compile("ab|a|ac");
        PatternMatcher mq = q.matcher("ac");
        assertThat(mq.matches()).isTrue();
        assertThat(mq.group()).isEqualTo("ac");
        assertThat(q.matcher("ab").matches()).isTrue();
        assertThat(q.matcher("ad").matches()).isFalse();
        PatternMatcher mqf = q.matcher("xac");
        assertThat(mqf.find()).isTrue();
        assertThat(mqf.group()).isEqualTo("a");   // leftmost-first, NOT "ac"

        Pattern r = Pattern.compile("ax?|a.y");
        PatternMatcher mrf = r.matcher("a.y");
        assertThat(mrf.find()).isTrue();
        assertThat(mrf.group()).isEqualTo("a");
        assertThat(r.matcher("a.y").matches()).isTrue();

        // Groups through the whole walk's EOF phi.
        Pattern g = Pattern.compile("(a)(b|bc)");
        PatternMatcher mg = g.matcher("abc");
        assertThat(mg.matches()).isTrue();
        assertThat(mg.group(1)).isEqualTo("a");
        assertThat(mg.group(2)).isEqualTo("bc");
        assertThat(g.matcher("abd").matches()).isFalse();

        // Zero-length and empty input.
        assertThat(Pattern.compile("a*").matcher("").matches()).isTrue();
        assertThat(Pattern.compile("a+").matcher("").matches()).isFalse();
        assertThat(Pattern.compile("a*").matcher("aaa").matches()).isTrue();
        assertThat(Pattern.compile("a*").matcher("aab").matches()).isFalse();

        // Multiline $: a mid-input line-end accept must not satisfy whole.
        assertThat(Pattern.compile("a$", Pattern.MULTILINE).matcher("a").matches()).isTrue();
        assertThat(Pattern.compile("a$", Pattern.MULTILINE).matcher("a\nb").matches()).isFalse();
        assertThat(Pattern.compile("a$", Pattern.MULTILINE).matcher("a\n").matches()).isFalse();
        assertThat(Pattern.compile("a$").matcher("a\n").matches()).isFalse();

        // Non-String CharSequence path (runGeneric anchored walk).
        assertThat(Pattern.compile("(a|ab)").matcher(new StringBuilder("ab")).matches()).isTrue();
        assertThat(Pattern.compile("a+").matcher(new StringBuilder("ab")).matches()).isFalse();

        // LONGEST_MATCH stays exact on the shared artifact.
        Pattern lm = Pattern.compile("(a|ab)", Pattern.LONGEST_MATCH);
        assertThat(lm.matcher("ab").matches()).isTrue();
        PatternMatcher mlm = lm.matcher("xxab");
        assertThat(mlm.find()).isTrue();
        assertThat(mlm.group()).isEqualTo("ab");   // leftmost-longest

        // Pattern-level conveniences route through the same walk.
        assertThat(Pattern.matches("(a|ab)", "ab")).isTrue();
        assertThat(Pattern.matches("(a|ab)", "abc")).isFalse();
        assertThat(Pattern.compile("(a|ab)").matches("ab".getBytes(java.nio.charset.StandardCharsets.UTF_8))).isTrue();

        // Serialization round-trip recompiles eagerly (both artifacts).
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        assertThatCode(() -> {
            try (java.io.ObjectOutputStream oos = new java.io.ObjectOutputStream(bos)) {
                oos.writeObject(Pattern.compile("(a|ab)"));
            }
        }).doesNotThrowAnyException();
    }

    @Test
    void asmTier() {
        assertThat(System.getProperty("tdfa.engine")).isNull();
        expectCommon();
    }

    @Test
    void vmTier() {
        System.setProperty("tdfa.engine", "VM");
        expectCommon();
    }

    @Test
    void interpreterFactory() {
        // BYO factory: whole is the anchored artifact (matchWhole default =
        // match(input,0) over anchored tables); find is the factory engine.
        Pattern p = Pattern.compile("(a|ab)", 0, io.github.jemmix.tdfa.tdfa.TdfaRunner::new);
        PatternMatcher m = p.matcher("ab");
        assertThat(m.matches()).isTrue();
        assertThat(m.group()).isEqualTo("ab");
        assertThat(p.matcher("ac").matches()).isFalse();
        PatternMatcher mbf = p.matcher("ab");
        assertThat(mbf.find()).isTrue();
        assertThat(mbf.group()).isEqualTo("a");
    }

    @Test
    void bombOverBudgetKeepsHistoricalContract() {
        // Nested-counted bomb: the whole builds (unpruned AND anchored) exceed
        // the determinization caps, so compile() succeeds on the find
        // artifact's acceptance and matches() surfaces the rejection on first
        // use — the pre-single-compile observable behavior, now pinned.
        Pattern p = Pattern.compile("(a{1,100}){1,100}");
        assertThat(p.matcher("a".repeat(120)).find()).isTrue();
        assertThatThrownBy(() -> p.matcher("a".repeat(50)).matches())
                .isInstanceOf(io.github.jemmix.tdfa.core.PatternSyntaxException.class);
    }
}
