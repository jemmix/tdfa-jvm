package io.github.jemmix.tdfa;

import io.github.jemmix.tdfa.core.CompilationReport;
import io.github.jemmix.tdfa.core.CompileOptions;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The partial-whole side table: ONE artifact serves find() AND matches().
 * During the pruned determinization's secondary sweep, every pike-cut
 * context records its UNCUT transitions beside the pruned ones, so the
 * whole walk on the find artifact is exact — no second, cut-free
 * determinization. Pinned here:
 * <ul>
 *   <li>span parity of the facade's whole engine against BOTH reference
 *       forms — the cut-free ({@code compileUnpruned}) artifact and the
 *       both-ends-anchored artifact — over a divergence-class catalog and
 *       a seeded random sweep (the randomized
 *       anchored-vs-partial-whole parity the design round demanded);</li>
 *   <li>find() untouched: identical find results through the facade
 *       (side recorded) and the core tier (plain compile, no side);</li>
 *   <li>the abandon path: a cut-matters whole-bomb records the abandon
 *       and the compile REJECTS with the standard family — no doomed
 *       cut-free second build is attempted (the ledger arithmetic can
 *       never fit it), and the find contract is never starved (the
 *       primary phase runs before the side).</li>
 * </ul>
 */
class PartialWholeTest {

    private static final String[] CATALOG = {"(a|ab)", "ab|a|ac", "ax?|a.y", "(a)(b|bc)", "(a)|(ab)", "(ab|a)+",
        "(a|ab)+", "(a??b??)*", "(a{1,3}?)b", "(?:ab|a)(?:c|bcd)", "((a)|b)+", "a*?b", "(a|ab)(c|bcd)?", "(x{0,2}?)x?",
        "(a+?)(a*)", "(|a)b?", "((?:a|ab)??c?){1,2}", "a(?:b|bc)*", "(\\b)?", "(a|ab)\\b", "(a|ab)?c", "(?i)(A|AB)",
        "(a|ab)(?:$)?", "^(a|ab)c?$"};

    private static final String[] INPUTS = {"", "a", "ab", "abc", "ac", "b", "bc", "aab", "abab", "ababc", "aaaa",
        "aaab", "acb", "abcd", "x", "A", "AB", "abc\n", "aac"};

    @AfterEach
    void clearKnobs() {
        System.clearProperty("tdfa.engine");
        System.clearProperty("tdfa.nominimize");
    }

    /**
     * The facade's whole engine (side-table walk on the find artifact)
     * answers span-identically to the cut-free artifact's walk and the
     * anchored artifact's walk (booleans included). java.util.regex
     * booleans over the same facade path are pinned by
     * {@code WholeMatchTest.anchoredArtifactWholeWalkIsExact}, which runs
     * the identical {@code wholeEngine()} calls.
     */
    @Test
    void sideTableWholeWalkMatchesUnprunedAndAnchoredArtifacts() {
        for (String p : CATALOG) {
            WholeEngine facadeWhole = ((TDFAPattern) Pattern.compile(p)).wholeEngine();
            TdfaRunner unpruned;
            TdfaRunner anchored;
            try {
                Tnfa nfa = Tnfa.compile(p, false, false, UnicodeProviders.get());
                unpruned = new TdfaRunner(Tdfa.compileUnpruned(nfa, false, null));
                Tnfa an = Tnfa.compile(p, false, true, UnicodeProviders.get());
                anchored = new TdfaRunner(Tdfa.compile(an, false));
            } catch (RuntimeException e) {
                continue; // catalog rows the front-end rejects (none expected)
            }
            for (String s : INPUTS) {
                MatchResult partial = facadeWhole.matchWhole(s, new MatchScratch());
                MatchResult free = unpruned.matchWhole(s, new MatchScratch());
                MatchResult anch = anchored.matchWhole(s, new MatchScratch());
                assertThat(span(partial)).as("partial vs cut-free spans: %s on %s", p, s).isEqualTo(span(free));
                assertThat(span(partial)).as("partial vs anchored spans: %s on %s", p, s).isEqualTo(span(anch));
                assertThat(partial != null).as("partial vs anchored boolean: %s on %s", p, s).isEqualTo(anch != null);
            }
        }
    }

    /**
     * Randomized partial-vs-unpruned parity (seeded, reproducible): small
     * alternation/lazy/group shapes over a tiny alphabet, the exact
     * divergence class where the pike cut bites.
     */
    @Test
    void randomizedSideTableParity() {
        Random rnd = new Random(20260928L);
        int checked = 0;
        for (int i = 0; i < 900; i++) {
            String p = randomPattern(rnd);
            WholeEngine facadeWhole;
            TdfaRunner unpruned;
            try {
                facadeWhole = ((TDFAPattern) Pattern.compile(p)).wholeEngine();
                Tnfa nfa = Tnfa.compile(p, false, false, UnicodeProviders.get());
                unpruned = new TdfaRunner(Tdfa.compileUnpruned(nfa, false, null));
            } catch (RuntimeException e) {
                continue; // malformed/unfolding-rejected rows
            }
            for (String s : randomInputs(rnd)) {
                MatchResult partial = facadeWhole.matchWhole(s, new MatchScratch());
                MatchResult free = unpruned.matchWhole(s, new MatchScratch());
                assertThat(span(partial)).as("random partial vs cut-free spans: %s on %s", p, s).isEqualTo(span(free));
                checked++;
            }
        }
        assertThat(checked).as("randomized rows actually compared").isGreaterThan(3000);
    }

    /**
     * The side table never touches find(): leftmost-first results through
     * the facade (side recorded) are identical to the core tier's plain
     * compile (no side recorded), on both engine tiers. The whole-surface
     * counterpart — facade whole vs the explicitly built cut-free artifact
     * — is pinned by the two oracle tests above.
     */
    @Test
    void findUntouchedBySideTable() {
        for (boolean vm : new boolean[]{false, true}) {
            if (vm) {
                System.setProperty("tdfa.engine", "VM");
            }
            for (String p : CATALOG) {
                List<String> findFacade = findResults(p, null);
                List<String> findPlain = coreFindResults(p);
                assertThat(findFacade).as("find unchanged (vm=%s): %s", vm, p).isEqualTo(findPlain);
            }
        }
    }

    /**
     * Minimization and register optimization must respect the side table:
     * A/B with the minimizer off (and the side on) — identical answers.
     */
    @Test
    void minimizerRespectsSideTable() {
        for (String p : CATALOG) {
            List<String> wholeOn = wholeResults(p, null);
            System.setProperty("tdfa.nominimize", "true");
            List<String> wholeNoMin;
            try {
                wholeNoMin = wholeResults(p, null);
            } finally {
                System.clearProperty("tdfa.nominimize");
            }
            assertThat(wholeOn).as("whole spans stable without minimization: %s", p).isEqualTo(wholeNoMin);
        }
    }

    /**
     * The bounded side exploration abandons cleanly: a nested-counted
     * bomb whose find artifact fits records the abandon (observer note)
     * and the compile REJECTS with the standard "pattern too large"
     * family — no second determinization is attempted (the old cut-free
     * fallback is gone: no {@code pikeCut} note can fire). The core tier
     * — find-only, no side — still ships the find artifact.
     */
    @Test
    void wholeBombAbandonsSideAndRejectsCompile() {
        String bomb = "(a{1,100}){1,100}";
        CompilationReport r = new CompilationReport();
        long t0 = System.nanoTime();
        try {
            Pattern.compile(bomb, CompileOptions.of().observer(r));
            throw new AssertionError("bomb must not compile");
        } catch (PatternSyntaxException e) {
            assertThat(e).hasMessageContaining("pattern too large");
        }
        assertThat(r.notes().get("partialWhole")).as("side abandonment is recorded").startsWith("abandoned");
        assertThat(r.notes()).as("no cut-free second build is attempted").doesNotContainKey("pikeCut");
        assertThat((System.nanoTime() - t0) / 1_000_000).as("wall to the abandon rejection (no doomed rebuild)")
            .isLessThan(30_000);
        // find-only tier unaffected: the primary phase never runs the side.
        assertThat(CompiledRegex.compile(bomb).find("a")).isTrue();
    }

    /**
     * Seeded random pattern generator over the divergence class:
     * alternations with early accepts, lazy quantifiers, optionals and
     * capture groups over {a,b,c}.
     */
    private static String randomPattern(Random rnd) {
        int depth = 1 + rnd.nextInt(3);
        String p = randomAtom(rnd);
        for (int d = 0; d < depth; d++) {
            switch (rnd.nextInt(6)) {
                case 0 :
                    p = "(" + p + "|" + randomAtom(rnd) + ")";
                    break;
                case 1 :
                    p = "(" + p + "??)";
                    break;
                case 2 :
                    p = "(" + p + "){1," + (1 + rnd.nextInt(3)) + "?}";
                    break;
                case 3 :
                    p = "(" + p + ")?";
                    break;
                case 4 :
                    p = "(" + p + randomAtom(rnd) + ")";
                    break;
                default :
                    p = p + randomAtom(rnd);
                    break;
            }
        }
        return p;
    }

    private static String randomAtom(Random rnd) {
        switch (rnd.nextInt(6)) {
            case 0 :
                return "a";
            case 1 :
                return "b";
            case 2 :
                return "c";
            case 3 :
                return "(a|ab)";
            case 4 :
                return "(b|bc)";
            default :
                return "(a?)";
        }
    }

    private static String[] randomInputs(Random rnd) {
        String[] out = new String[6];
        for (int i = 0; i < out.length; i++) {
            int len = rnd.nextInt(7);
            StringBuilder sb = new StringBuilder();
            for (int j = 0; j < len; j++) {
                sb.append((char) ('a' + rnd.nextInt(3)));
            }
            out[i] = sb.toString();
        }
        return out;
    }

    private static List<String> findResults(String p, Void unused) {
        List<String> out = new ArrayList<>();
        for (String s : INPUTS) {
            PatternMatcher m = Pattern.compile(p).matcher(s);
            StringBuilder row = new StringBuilder();
            while (m.find()) {
                row.append('[').append(m.start()).append(',').append(m.end()).append(')');
                for (int g = 1; g <= m.groupCount(); g++) {
                    row.append(';').append(m.start(g) < 0 ? "-" : m.start(g) + "," + m.end(g));
                }
                row.append(' ');
            }
            out.add(row.toString());
        }
        return out;
    }

    /** The same rows through the core tier (plain compile — no side recorded). */
    private static List<String> coreFindResults(String p) {
        List<String> out = new ArrayList<>();
        CompiledRegex r = CompiledRegex.compile(p);
        for (String s : INPUTS) {
            StringBuilder row = new StringBuilder();
            for (MatchResult m : r.findAll(s)) {
                row.append('[').append(m.start(0)).append(',').append(m.end(0)).append(')');
                for (int g = 1; g <= m.groupCount(); g++) {
                    row.append(';').append(m.start(g) < 0 ? "-" : m.start(g) + "," + m.end(g));
                }
                row.append(' ');
            }
            out.add(row.toString());
        }
        return out;
    }

    private static List<String> wholeResults(String p, Void unused) {
        List<String> out = new ArrayList<>();
        WholeEngine w = ((TDFAPattern) Pattern.compile(p)).wholeEngine();
        for (String s : INPUTS) {
            out.add(span(w.matchWhole(s, new MatchScratch())));
        }
        return out;
    }

    private static String span(MatchResult m) {
        if (m == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder("[").append(m.start(0)).append(',').append(m.end(0)).append(')');
        for (int g = 1; g <= m.groupCount(); g++) {
            sb.append(';').append(m.start(g) < 0 ? "null" : m.start(g) + "," + m.end(g));
        }
        return sb.toString();
    }
}
