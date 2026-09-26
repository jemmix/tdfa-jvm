package io.github.jemmix.tdfa;

import io.github.jemmix.tdfa.core.CompiledRegex;
import io.github.jemmix.tdfa.core.MatchResult;
import io.github.jemmix.tdfa.core.MatchScratch;
import io.github.jemmix.tdfa.core.PatternSyntaxException;
import io.github.jemmix.tdfa.core.WholeEngine;
import io.github.jemmix.tdfa.tdfa.Tdfa;
import io.github.jemmix.tdfa.tdfa.TdfaRunner;
import io.github.jemmix.tdfa.tnfa.Tnfa;
import io.github.jemmix.tdfa.unicode.UnicodeProviders;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Whole/find semantics: matches() runs a cut-free walk to EOF (an accept
 * alive at end-of-input is a full match) while find() keeps leftmost-first
 * — the two answer the same input differently by design ({@code (a|ab)}
 * whole-matches "ab" while find stops at "a"). When the compile's pike cut
 * deleted continuations, a second cut-free artifact backs matches();
 * otherwise both run on the one find artifact. A pattern whose cut-free
 * whole artifact exceeds the compile budget fails {@code compile()} with
 * the standard "pattern too large" rejection. Pinned on BOTH tiers
 * (default ASM shells and {@code -Dtdfa.engine=VM}).
 */
class WholeMatchTest {

    @AfterEach
    void clearVmSwitch() {
        System.clearProperty("tdfa.engine");
    }

    private void expectCommon() {
        // Whole needs the un-pruned continuation; find is leftmost-first.
        Pattern p = Pattern.compile("(a|ab)");
        PatternMatcher m = p.matcher("ab");
        assertThat(m.matches()).isTrue();
        assertThat(m.matches()).isTrue(); // repeat: eager engine, no lazy state
        assertThat(m.group()).isEqualTo("ab");
        assertThat(p.matcher("ac").matches()).isFalse();
        PatternMatcher mf = p.matcher("ab");
        assertThat(mf.find()).isTrue();
        assertThat(mf.group()).isEqualTo("a"); // leftmost-first

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
        assertThat(mqf.group()).isEqualTo("a"); // leftmost-first, NOT "ac"

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
        assertThat(mlm.group()).isEqualTo("ab"); // leftmost-longest

        // Pattern-level conveniences route through the same walk.
        assertThat(Pattern.matches("(a|ab)", "ab")).isTrue();
        assertThat(Pattern.matches("(a|ab)", "abc")).isFalse();
        assertThat(Pattern.compile("(a|ab)").matches("ab".getBytes(StandardCharsets.UTF_8))).isTrue();

        // Serialization round-trip recompiles eagerly (both artifacts).
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        assertThatCode(() -> {
            try (ObjectOutputStream oos = new ObjectOutputStream(bos)) {
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
     * pipeline minus the whole-match surface: find() leftmost-first, and a
     * bomb whose cut-free whole artifact exceeds the budget fails
     * compile(). Whole-input probing is the facade's job now; the core-tier
     * idiom is a full-span check on {@code match(input, 0)}.
     */
    @Test
    void evergreenTier() {
        CompiledRegex p = CompiledRegex.compile("(a|ab)");
        assertThat(p.find("xxab")).isTrue();
        CompiledRegex q = CompiledRegex.compile("ab|a|ac");
        assertThat(q.find("xac")).isTrue();
        // Full-span check on the find surface is the whole-match idiom for
        // cut-irrelevant patterns (the artifact is identical to the
        // cut-free build). The (a|ab) divergence class has NO core-tier
        // whole answer — find stays leftmost-first there by design.
        assertThat(span0(CompiledRegex.compile("a$"), "a")).isTrue();
        assertThat(span0(CompiledRegex.compile("a$"), "a\nb")).isFalse();
        assertThat(span0(CompiledRegex.compile("\\d+"), "123")).isTrue();
        assertThat(span0(CompiledRegex.compile("\\d+"), "12a")).isFalse();
        // Budget corner, core tier: no whole artifact is compiled anymore,
        // so a bomb whose FIND artifact fits ships — the whole-bomb
        // rejection is the facade's alone (wholeBombFailsCompile below).
        CompiledRegex bomb = CompiledRegex.compile("(a{1,100}){1,100}");
        assertThat(bomb.find("a")).isTrue();
    }

    private static boolean span0(CompiledRegex r, String in) {
        MatchResult m = r.match(in, 0);
        return m != null && m.start(0) == 0 && m.end(0) == in.length();
    }

    @Test
    void interpreterFactory() {
        // BYO factory: one engine per artifact through the same translation.
        Pattern p = Pattern.compile("(a|ab)", 0, TdfaRunner::new);
        PatternMatcher m = p.matcher("ab");
        assertThat(m.matches()).isTrue();
        assertThat(m.group()).isEqualTo("ab");
        assertThat(p.matcher("ac").matches()).isFalse();
        PatternMatcher mbf = p.matcher("ab");
        assertThat(mbf.find()).isTrue();
        assertThat(mbf.group()).isEqualTo("a");
    }

    /**
     * One engine source serves EVERY artifact of a compile: a factory is
     * called once per artifact (twice when the pike cut bit — find plus
     * cut-free whole — once when the artifacts are shared), and on the
     * default tier the whole engine is generated just like the find engine
     * (its native {@code wholeOne} walk backs {@code matches()}).
     */
    @Test
    void engineSourceServesEveryArtifact() {
        List<Tdfa> seen = new ArrayList<>();
        Pattern p = Pattern.compile("(a|ab)", 0, t -> {
            seen.add(t);
            return new TdfaRunner(t);
        });
        assertThat(seen).as("hazardous pattern: find + whole artifacts").hasSize(2);
        assertThat(p.matcher("ab").matches()).isTrue();

        List<Tdfa> seenOnce = new ArrayList<>();
        Pattern q = Pattern.compile("a*", 0, t -> {
            seenOnce.add(t);
            return new TdfaRunner(t);
        });
        assertThat(seenOnce).as("hazard-free pattern: one shared artifact").hasSize(1);
        assertThat(q.matcher("aaa").matches()).isTrue();

        Pattern asm = Pattern.compile("(a|ab)");
        assertThat(((TDFAPattern) asm).wholeEngine().getClass().getSimpleName())
            .as("default tier whole engine is generated, not the interpreter").startsWith("Gen");
    }

    /**
     * Anchored builds answer whole matches through the same cut-free walk:
     * every accept in a both-ends-anchored build is end-of-input-gated, so
     * the compile-time pike cut never fires mid-walk. Pinned as full
     * equivalence with the facade's whole engine (whole span AND group
     * spans), plus whole-boolean agreement with java.util.regex (whose
     * nested-star capture spans intentionally differ from our re2j charter
     * — e.g. {@code (a*)*} — so only the boolean is compared there).
     */
    @Test
    void anchoredArtifactWholeWalkIsExact() {
        String[] pats = {"(a|ab)", "ab|a|ac", "ax?|a.y", "(a)(b|bc)", "a*", "a+", "(a*)*", "(a?){2,}", "(^|$)+", "a$",
            "^a", "(?m)^a$", "(?m)a$", "\\Aab\\z", "a\\z", "(?i)AbC", "(ab|a)+", "(a|ab)+", "x.*y", "x.+?y",
            "\\bword\\b", "(?:ab|a)(?:c|bcd)", "(a??b??)*", "((a)|b)+", "(a{1,3}?)b", "(\\w+)\\s+(\\w+)", "(a)|(ab)",};
        String[] inputs = {"", "a", "ab", "abc", "ac", "ad", "ax", "a.y", "b", "abab", "ababc", "aaab", "a\n", "a\nb",
            "xay", "xabcy", "xxy", "xy", "word", " word ", "a b", "hello brave new world", "AbC", "abcd", "zz", "aab",
            "aaaa", "aaaaab", "\n",};
        for (String p : pats) {
            TdfaRunner anchored;
            java.util.regex.Pattern jur;
            try {
                Tnfa an = Tnfa.compile(p, false, true, UnicodeProviders.get());
                anchored = new TdfaRunner(Tdfa.compile(an, false));
                jur = java.util.regex.Pattern.compile(p);
            } catch (RuntimeException e) {
                continue;
            }
            WholeEngine facadeWhole = ((TDFAPattern) Pattern.compile(p)).wholeEngine();
            for (String s : inputs) {
                MatchResult am = anchored.matchWhole(s, new MatchScratch());
                MatchResult fm = facadeWhole.matchWhole(s, new MatchScratch());
                String a = am == null ? "null" : span(am);
                String f = fm == null ? "null" : span(fm);
                assertThat(a).as("anchored whole of %s on %s (spans)", p, s.replace("\n", "\\n")).isEqualTo(f);
                assertThat(am != null).as("anchored whole boolean of %s on %s vs jur", p, s.replace("\n", "\\n"))
                    .isEqualTo(jur.matcher(s).matches());
            }
        }
    }

    private static String span(MatchResult m) {
        StringBuilder sb = new StringBuilder("[").append(m.start(0)).append(',').append(m.end(0)).append(')');
        for (int g = 1; g <= m.groupCount(); g++) {
            sb.append(';').append(m.start(g) < 0 ? "null" : m.start(g) + "," + m.end(g));
        }
        return sb.toString();
    }

    /**
     * Accepting states whose configs carry DIFFERENT zero-width assertions
     * (\b/\B/\z after a group) compile to a {@code stateFinalOpsByMask}
     * table even on fastPath DFAs;
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
            PatternMatcher vm = Pattern.compile("(?:.)((?:\\B)?)", 0, TdfaRunner::new).matcher(in);
            assertThat(vm.matches()).isTrue();
            assertThat(vm.start(1)).isEqualTo(2);
            assertThat(vm.end(1)).isEqualTo(2);
        }
        // (\b)? on "": \b dead at EOF of empty input → whole matches via the
        // empty branch, group 1 non-participating (NOT the [0,0) the default
        // φ produced). Same answer at flags=0 and LONGEST_MATCH.
        for (int flags : new int[]{0, Pattern.LONGEST_MATCH}) {
            PatternMatcher asm = Pattern.compile("(\\b)?", flags).matcher("");
            assertThat(asm.matches()).as("flags=%d", flags).isTrue();
            assertThat(asm.start(1)).as("flags=%d", flags).isEqualTo(-1);
            PatternMatcher vm = Pattern.compile("(\\b)?", flags, TdfaRunner::new).matcher("");
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
            PatternMatcher vm = Pattern.compile(".(?<n0>(\\z)*)", 0, TdfaRunner::new).matcher("_");
            assertThat(vm.matches()).isTrue();
            assertThat(vm.start(1)).isEqualTo(1);
            assertThat(vm.end(1)).isEqualTo(1);
        }
        // CharSequence inputs route the generic (runner) whole walk — exact too.
        assertThat(Pattern.compile("(\\b)?").matcher(new StringBuilder("")).matches()).isTrue();
    }

    /**
     * Nested-counted bomb: the find artifact compiles, the pike cut bit, and
     * the cut-free whole artifact exceeds the determinization caps — so the
     * FACADE's compile() FAILS with the clean "pattern too large" rejection
     * (the same compile-time budget contract the find artifact has always
     * had; nothing materializes at match time). The core tier ships the find
     * artifact only and compiles (see evergreenTier).
     */
    @Test
    void wholeBombFailsCompile() {
        String bomb = "(a{1,100}){1,100}";
        assertThatThrownBy(() -> Pattern.compile(bomb)).isInstanceOf(PatternSyntaxException.class)
            .hasMessageContaining("pattern too large");
        assertThatThrownBy(() -> Pattern.compile(bomb, 0, TdfaRunner::new)).isInstanceOf(PatternSyntaxException.class)
            .hasMessageContaining("pattern too large");
    }
}
