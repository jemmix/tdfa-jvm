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

    /**
     * The evergreen core tier ({@code CompiledRegex}) runs the same
     * single-compile ladder: matches() through the eagerly compiled whole
     * engine, find() leftmost-first, and the bomb corner records its
     * rejection at compile time (find keeps working).
     */
    @Test
    void evergreenTier() {
        io.github.jemmix.tdfa.core.CompiledRegex p =
                io.github.jemmix.tdfa.core.CompiledRegex.compile("(a|ab)");
        assertThat(p.matches("ab")).isTrue();
        assertThat(p.matches("ac")).isFalse();
        assertThat(p.find("xxab")).isTrue();
        io.github.jemmix.tdfa.core.CompiledRegex q =
                io.github.jemmix.tdfa.core.CompiledRegex.compile("ab|a|ac");
        assertThat(q.matches("ac")).isTrue();
        assertThat(q.find("xac")).isTrue();
        io.github.jemmix.tdfa.core.CompiledRegex m =
                io.github.jemmix.tdfa.core.CompiledRegex.compile("a$");
        assertThat(m.matches("a")).isTrue();
        assertThat(m.matches("a\nb")).isFalse();
        io.github.jemmix.tdfa.core.CompiledRegex bomb =
                io.github.jemmix.tdfa.core.CompiledRegex.compile("(a{1,100}){1,100}");
        assertThat(bomb.find("a".repeat(120))).isTrue();
        assertThatThrownBy(() -> bomb.matches("a".repeat(50)))
                .isInstanceOf(io.github.jemmix.tdfa.core.PatternSyntaxException.class)
                .hasMessageContaining("pattern too large");
    }

    @Test
    void interpreterFactory() {
        // BYO factory: whole is the facade's whole engine over the shared
        // unpruned artifact (native matchWhole); find is the factory engine.
        Pattern p = Pattern.compile("(a|ab)", 0, io.github.jemmix.tdfa.tdfa.TdfaRunner::new);
        PatternMatcher m = p.matcher("ab");
        assertThat(m.matches()).isTrue();
        assertThat(m.group()).isEqualTo("ab");
        assertThat(p.matcher("ac").matches()).isFalse();
        PatternMatcher mbf = p.matcher("ab");
        assertThat(mbf.find()).isTrue();
        assertThat(mbf.group()).isEqualTo("a");
    }

    /**
     * The over-budget corner's whole engine: a {@code TdfaRunner} over the
     * eagerly compiled BOTH-ENDS-ANCHORED artifact, answering through the
     * cut-free whole walk. Exactness argument: every accept in an anchored
     * build is end-of-input-gated, so the compile-time pike cut never fires
     * mid-walk and the cut-free whole walk is exact over the (pruned)
     * artifact. Pinned as full equivalence with the facade's whole engine
     * (the unpruned artifact — whole span AND group spans; the oracle-free
     * invariant), plus whole-boolean agreement with java.util.regex (whose
     * nested-star capture spans intentionally differ from our re2j charter
     * — e.g. {@code (a*)*} — so only the boolean is compared there).
     * Randomized at landing time: 18 k anchored-vs-unpruned pairs over a
     * generator battery, both longest modes, 0 diffs.
     */
    @Test
    void anchoredArtifactWholeWalkIsExact() {
        String[] pats = {
                "(a|ab)", "ab|a|ac", "ax?|a.y", "(a)(b|bc)", "a*", "a+", "(a*)*", "(a?){2,}",
                "(^|$)+", "a$", "^a", "(?m)^a$", "(?m)a$", "\\Aab\\z", "a\\z", "(?i)AbC",
                "(ab|a)+", "(a|ab)+", "x.*y", "x.+?y", "\\bword\\b", "(?:ab|a)(?:c|bcd)",
                "(a??b??)*", "((a)|b)+", "(a{1,3}?)b", "(\\w+)\\s+(\\w+)", "(a)|(ab)",
        };
        String[] inputs = {
                "", "a", "ab", "abc", "ac", "ad", "ax", "a.y", "b", "abab", "ababc", "aaab",
                "a\n", "a\nb", "xay", "xabcy", "xxy", "xy", "word", " word ", "a b",
                "hello brave new world", "AbC", "abcd", "zz", "aab", "aaaa", "aaaaab", "\n",
        };
        for (String p : pats) {
            io.github.jemmix.tdfa.tdfa.TdfaRunner anchored;
            java.util.regex.Pattern jur;
            try {
                io.github.jemmix.tdfa.tnfa.Tnfa an = io.github.jemmix.tdfa.tnfa.Tnfa.compile(
                        p, false, true, io.github.jemmix.tdfa.unicode.UnicodeProviders.get());
                anchored = new io.github.jemmix.tdfa.tdfa.TdfaRunner(
                        io.github.jemmix.tdfa.tdfa.Tdfa.compile(an, false));
                jur = java.util.regex.Pattern.compile(p);
            } catch (RuntimeException e) { continue; }
            io.github.jemmix.tdfa.core.RegexEngine facadeWhole =
                    ((TDFAPattern) Pattern.compile(p)).wholeEngine();
            for (String s : inputs) {
                io.github.jemmix.tdfa.core.MatchResult am = anchored.matchWhole(s);
                io.github.jemmix.tdfa.core.MatchResult fm = facadeWhole.matchWhole(s);
                String a = am == null ? "null" : span(am);
                String f = fm == null ? "null" : span(fm);
                assertThat(a)
                        .as("anchored whole of %s on %s (spans)", p, s.replace("\n", "\\n"))
                        .isEqualTo(f);
                assertThat(am != null)
                        .as("anchored whole boolean of %s on %s vs jur", p, s.replace("\n", "\\n"))
                        .isEqualTo(jur.matcher(s).matches());
            }
        }
    }

    private static String span(io.github.jemmix.tdfa.core.MatchResult m) {
        StringBuilder sb = new StringBuilder("[").append(m.start(0)).append(',').append(m.end(0)).append(')');
        for (int g = 1; g <= m.groupCount(); g++)
            sb.append(';').append(m.start(g) < 0 ? "null" : m.start(g) + "," + m.end(g));
        return sb.toString();
    }

    /**
     * Fuzz round 28 (asm-only probe-M mismatches): accepting states whose
     * configs carry DIFFERENT zero-width assertions (\b/\B/\z after a group)
     * compile to a {@code stateFinalOpsByMask} table even on fastPath DFAs;
     * the generated wholeOne leaf must gate and select the φ winner by the
     * EOF position flags exactly like the runner's wholeWalk — not apply the
     * state-keyed default φ. Pinned on both tiers with oracle spans: the
     * supplementary-input \B case, the empty-input \b case (LONGEST flag,
     * group must stay non-participating), and the trailing (\z)* case.
     */
    @Test
    void finalVariantGateAtEofMatchesOracleOnBothTiers() {
        // (?:.)((?:\B)?) on "\ud800\udfff": \B alive at EOF → group 1 = [2,2).
        {
            String in = "\ud800\udfff";
            PatternMatcher asm = Pattern.compile("(?:.)((?:\\B)?)").matcher(in);
            assertThat(asm.matches()).isTrue();
            assertThat(asm.start(1)).isEqualTo(2);
            assertThat(asm.end(1)).isEqualTo(2);
            PatternMatcher vm = Pattern.compile("(?:.)((?:\\B)?)", 0,
                    io.github.jemmix.tdfa.tdfa.TdfaRunner::new).matcher(in);
            assertThat(vm.matches()).isTrue();
            assertThat(vm.start(1)).isEqualTo(2);
            assertThat(vm.end(1)).isEqualTo(2);
        }
        // (\b)? on "": \b dead at EOF of empty input → whole matches via the
        // empty branch, group 1 non-participating (NOT the [0,0) the default
        // φ produced). Same answer at flags=0 and LONGEST_MATCH (fuzz case).
        for (int flags : new int[]{0, Pattern.LONGEST_MATCH}) {
            PatternMatcher asm = Pattern.compile("(\\b)?", flags).matcher("");
            assertThat(asm.matches()).as("flags=%d", flags).isTrue();
            assertThat(asm.start(1)).as("flags=%d", flags).isEqualTo(-1);
            PatternMatcher vm = Pattern.compile("(\\b)?", flags,
                    io.github.jemmix.tdfa.tdfa.TdfaRunner::new).matcher("");
            assertThat(vm.matches()).isTrue();
            assertThat(vm.start(1)).isEqualTo(-1);
        }
        // .(?<n0>(\z)*) on "_": \z alive at EOF → both groups = [1,1).
        {
            PatternMatcher asm = Pattern.compile(".(?<n0>(\\z)*)").matcher("_");
            assertThat(asm.matches()).isTrue();
            assertThat(asm.start(1)).isEqualTo(1);
            assertThat(asm.end(1)).isEqualTo(1);
            assertThat(asm.start("n0")).isEqualTo(1);
            assertThat(asm.end("n0")).isEqualTo(1);
            PatternMatcher vm = Pattern.compile(".(?<n0>(\\z)*)", 0,
                    io.github.jemmix.tdfa.tdfa.TdfaRunner::new).matcher("_");
            assertThat(vm.matches()).isTrue();
            assertThat(vm.start(1)).isEqualTo(1);
            assertThat(vm.end(1)).isEqualTo(1);
        }
        // CharSequence inputs route the generic (runner) whole walk — exact too.
        assertThat(Pattern.compile("(\\b)?").matcher(new StringBuilder("")).matches()).isTrue();
    }

    @Test
    void bombOverBudgetKeepsFindOnlyAcceptanceWithRecordedRejection() {
        // Nested-counted bomb: BOTH whole builds (unpruned and anchored)
        // exceed the determinization caps. compile() still accepts on the
        // find artifact's alone (the historical contract — a whole-match
        // bomb must not take find() away), and matches() rethrows the
        // rejection RECORDED AT COMPILE TIME — same instance on every call,
        // no recompile (the no-lazy-compiles rule: nothing materializes at
        // match time; the former LazyEngine corner re-burned its doomed
        // build per call — fuzz round 27's spins).
        Pattern p = Pattern.compile("(a{1,100}){1,100}");
        assertThat(p.matcher("a".repeat(120)).find()).isTrue();
        RuntimeException[] recorded = new RuntimeException[1];
        assertThatThrownBy(() -> p.matcher("a".repeat(50)).matches())
                .isInstanceOf(io.github.jemmix.tdfa.core.PatternSyntaxException.class)
                .hasMessageContaining("pattern too large")
                .satisfies(ex -> recorded[0] = (RuntimeException) ex);
        assertThatThrownBy(() -> p.matcher("a".repeat(50)).matches()).isSameAs(recorded[0]);
    }
}
