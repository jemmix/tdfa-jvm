package io.github.jemmix.tdfa;

import io.github.jemmix.tdfa.core.determinize.Determinizer;
import io.github.jemmix.tdfa.core.dfa.Tdfa;
import io.github.jemmix.tdfa.core.dfa.TdfaRunner;
import io.github.jemmix.tdfa.core.tnfa.Tnfa;
import io.github.jemmix.tdfa.core.unicode.UnicodeProviders;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;
import java.util.regex.Matcher;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The sim→trigger live-set handoff (see {@code originSimRungTop} and
 * docs/wide-dispatch-index.md): when the budgeted origin sim exhausts its
 * char budget, the trigger raw scan CONTINUES from the sim's parked live
 * set + position + kill window instead of re-scanning from {@code from}.
 * One pass instead of two — and this test pins the "identical answers"
 * half of that contract: for every forced budget on every shape, the
 * handoff rung must return exactly what the fresh-scan composition
 * ({@code originSimLeftmost} → {@code LSS_BUDGET} → {@code triggerScanTop}
 * → windowed {@code originSimLeftmost}) returned before it — the old
 * ladder, rebuilt here from the stable public hooks. The budget sweep is
 * exhaustive on short inputs, so every possible bail position (the accept
 * exactly at the seam, the kill exactly at the last in-budget step, the
 * mid-pair skip past the limit) is compared against the old seam; long
 * inputs sweep the budget boundary around the production 4 096 constant.
 * The leftmost answers themselves are additionally pinned against the
 * live java.util.regex oracle (leftmost-start semantics), and the natural
 * VM/ASM ladders are compared end-to-end over the same shapes so the
 * emitted rung (which delegates to the same hook) cannot drift.
 */
class SimTriggerHandoffTest {

    private static final String NONBMP = "\uD801\uDC00\uD801\uDC01\uD801\uDC02\uD801\uDC03";
    private static final String SURROGATE_SOUP =
        "\udfff\ud807\udc07\udc00\u0442\ud807\udc07\ud835\udd04\ud835\udd04\u1c85Y\n\u3042\ud801\udc21\udc21";

    /** The pattern corpus: fastPath (disjoint, mask-free) codepoint-lane shapes. */
    private static final String[] PATTERNS =
        {"\\p{L}{2,}x", "[\\p{L}\\p{N}_]{2,}x", "[a-z]+qrst", "\\p{L}{2,}\\p{N}x", "(?:сл|во|за|те)(?:[а-я]){1,4}x",
            // multi-word live sets: 40+ states, nwords >= 2
            multiWordPattern(),
            // empty-match-capable start: the sim early-stops, the bail never fires
            "(?:\\p{L}x)?\\p{L}{2,}",};

    private static String multiWordPattern() {
        StringBuilder sb = new StringBuilder("(?:");
        for (char a = 'a'; a <= 'z'; a++) {
            for (char b = 'a'; b <= 'd' && sb.length() < 400; b++) {
                if (sb.length() > 3) {
                    sb.append('|');
                }
                sb.append(a).append(b);
            }
        }
        return sb.append(")x").toString();
    }

    private static TdfaRunner compile(String pattern) {
        Tnfa nfa = Tnfa.compile(pattern, false, false, UnicodeProviders.get());
        return new TdfaRunner(Determinizer.compile(nfa, false));
    }

    /** The handoff's documented domain: the rung is fastPath-gated, so the corpus must be too. */
    private static boolean rungEligible(String pattern) {
        Tnfa nfa = Tnfa.compile(pattern, false, false, UnicodeProviders.get());
        Tdfa tdfa = Determinizer.compile(nfa, false);
        return TdfaRunner.fastPathEligible(tdfa) && tdfa.stateCount() < 16_384;
    }

    /** The pre-handoff rung composition, rebuilt from the stable public hooks. */
    private static int oldRung(TdfaRunner r, String in, int from, int to, int budget) {
        int leftmost = r.originSimLeftmost(in, from, to, budget, null);
        if (leftmost == TdfaRunner.LSS_BUDGET) {
            int w = r.triggerScanTop(in, from, to, null);
            leftmost = w < 0 ? -1 : r.originSimLeftmost(in, w, to, -1, null);
        }
        return leftmost;
    }

    @Test
    void handoffMatchesFreshScanCompositionAcrossBudgetSweep() {
        int checks = 0;
        for (String pattern : PATTERNS) {
            assertThat(rungEligible(pattern)).as("corpus must stay fastPath: %s", pattern).isTrue();
            TdfaRunner r = compile(pattern);
            for (String in : inputsFor(pattern)) {
                for (int budget : budgetSweep(in.length())) {
                    int oldLeftmost = oldRung(r, in, 0, in.length(), budget);
                    int newLeftmost = r.originSimRungTop(in, 0, in.length(), budget, null);
                    assertThat(newLeftmost).as("pattern %s, len %d, budget %d", pattern, in.length(), budget)
                        .isEqualTo(oldLeftmost);
                    checks++;
                }
            }
        }
        assertThat(checks).isGreaterThan(500);
    }

    @Test
    void leftmostMatchesOracleAndLaddersOnLongShapes() {
        for (String pattern : PATTERNS) {
            TdfaRunner r = compile(pattern);
            java.util.regex.Pattern jur = java.util.regex.Pattern.compile(jurDialect(pattern));
            io.github.jemmix.tdfa.re2j.Pattern vm =
                io.github.jemmix.tdfa.re2j.Pattern.compile(pattern, 0, TdfaRunner::new);
            io.github.jemmix.tdfa.re2j.Pattern asm = io.github.jemmix.tdfa.re2j.Pattern.compile(pattern);
            for (String in : longInputsFor(pattern)) {
                int leftmost = r.originSimRungTop(in, 0, in.length(), r.originSimBudget(), null);
                Matcher jm = jur.matcher(in);
                assertThat(leftmost).as("leftmost vs jur: %s len %d", pattern, in.length())
                    .isEqualTo(jm.find() ? jm.start() : -1);
                // the natural ladders answer find() identically, VM vs ASM
                boolean expect = leftmost >= 0;
                assertThat(vm.matcher(in).find()).as("vm find: %s len %d", pattern, in.length()).isEqualTo(expect);
                assertThat(asm.matcher(in).find()).as("asm find: %s len %d", pattern, in.length()).isEqualTo(expect);
            }
        }
    }

    @Test
    void randomizedDifferentialOverRandomShapes() {
        RandomGenerator rnd = RandomGeneratorFactory.getDefault().create(20261106L);
        String[] atoms = {"\\p{L}", "[a-z]", "[\\p{L}\\p{N}]", "аб", "x", "\\p{N}", NONBMP};
        String[] quants = {"{2,}", "+", "{1,3}", "{3,}", ""};
        int checks = 0;
        for (int i = 0; i < 300; i++) {
            StringBuilder pattern = new StringBuilder();
            int parts = 1 + rnd.nextInt(3);
            for (int p = 0; p < parts; p++) {
                pattern.append(atoms[rnd.nextInt(atoms.length)]).append(quants[rnd.nextInt(quants.length)]);
            }
            String re = pattern.toString();
            if (!rungEligible(re)) {
                continue; // the rung (and thus the handoff) is fastPath-gated
            }
            TdfaRunner r = compile(re);
            for (int t = 0; t < 6; t++) {
                String in = randomInput(rnd);
                for (int budget : new int[]{0, 1, 2, 3, 5, 8, 64, 4096, -1}) {
                    int oldLeftmost = oldRung(r, in, 0, in.length(), budget);
                    int newLeftmost = r.originSimRungTop(in, 0, in.length(), budget, null);
                    assertThat(newLeftmost).as("random %s, len %d, budget %d", re, in.length(), budget)
                        .isEqualTo(oldLeftmost);
                    checks++;
                }
            }
        }
        assertThat(checks).isGreaterThan(5000);
    }

    // ===== inputs =====

    private static List<String> inputsFor(String pattern) {
        List<String> ins = new ArrayList<>();
        // short no-match and match shapes (exhaustive budget sweep below)
        ins.add("запас 1234 тест 5678");
        ins.add("запас 1234 тест 5678x");
        ins.add("aaqrst");
        ins.add("zqrst");
        ins.add(NONBMP + "zx");
        ins.add(SURROGATE_SOUP);
        ins.add("a" + NONBMP + "bx");
        ins.add("");
        ins.add("x");
        ins.add("запас слов тест");
        ins.add("aa ba ab qrst");
        ins.add("aaaaaaaaaaabqrst");
        ins.add(rep("слово 12 ", 11));
        return ins;
    }

    private static List<String> longInputsFor(String pattern) {
        List<String> ins = new ArrayList<>();
        ins.add(rep("слово 1234 запас 5678 ", 1 << 12)); // 8K no-match sparse
        ins.add(rep("слово 1234 запас 5678 ", 1 << 12) + "запасx"); // match at end
        ins.add(rep("словозапас", 1 << 12)); // dense no-match (no x)
        ins.add(rep(NONBMP, 1 << 11)); // dense non-BMP no-match
        ins.add(SURROGATE_SOUP.repeat(400)); // ~5.6K surrogate soup, no match
        // matches planted just around the production budget seam (4096)
        for (int off = -3; off <= 3; off++) {
            StringBuilder b = new StringBuilder(4200);
            String filler = "слово 12 ";
            while (b.length() < 4096 + off) {
                b.append(filler);
            }
            ins.add(b + "запасx");
        }
        return ins;
    }

    private static int[] budgetSweep(int len) {
        // exhaustive on short inputs; boundary-focused on long ones
        if (len <= 80) {
            int[] all = new int[len + 3];
            for (int i = 0; i <= len + 2; i++) {
                all[i] = i - 1; // -1 = unbudgeted, 0..len
            }
            return all;
        }
        return new int[]{0, 1, 2, 3, 5, 8, 13, 21, 34, 55, 89, 144, 233, 377, 610, 987, 1597, 2584, 4090, 4091, 4092,
            4093, 4094, 4095, 4096, 4097, 4098, 4099, 4100, 8192, 4096 + 4096 / 2, -1};
    }

    private static String randomInput(RandomGenerator rnd) {
        String[] units = {"a", "b", "z", "x", "1", " ", "аб", " ", NONBMP, "\u0442", "qr", "st"};
        int len = rnd.nextInt(1, 90);
        StringBuilder sb = new StringBuilder(len * 2);
        while (sb.length() < len) {
            sb.append(units[rnd.nextInt(units.length)]);
        }
        return sb.toString();
    }

    private static String rep(String unit, int len) {
        StringBuilder b = new StringBuilder(len + unit.length());
        while (b.length() < len) {
            b.append(unit);
        }
        return b.toString();
    }

    /** jur transcription of the corpus (identical syntax on this corpus). */
    private static String jurDialect(String pattern) {
        return pattern;
    }
}
