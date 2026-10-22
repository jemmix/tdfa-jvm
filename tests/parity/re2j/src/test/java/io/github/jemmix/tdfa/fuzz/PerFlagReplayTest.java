package io.github.jemmix.tdfa.fuzz;

import io.github.jemmix.tdfa.core.budget.PatternTooLargeException;
import io.github.jemmix.tdfa.core.compile.TDFAPattern;
import io.github.jemmix.tdfa.core.dfa.TdfaRunner;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.stream.Stream;

import static io.github.jemmix.tdfa.fuzz.DifferentialFuzzer.FLAG_CI;
import static io.github.jemmix.tdfa.fuzz.DifferentialFuzzer.FLAG_DOTALL;
import static io.github.jemmix.tdfa.fuzz.DifferentialFuzzer.FLAG_MULTILINE;
import static io.github.jemmix.tdfa.fuzz.DifferentialFuzzer.FLAG_UCC;
import static io.github.jemmix.tdfa.fuzz.DifferentialFuzzer.JUR_I;
import static io.github.jemmix.tdfa.fuzz.DifferentialFuzzer.JUR_M;
import static io.github.jemmix.tdfa.fuzz.DifferentialFuzzer.JUR_S;
import static io.github.jemmix.tdfa.fuzz.DifferentialFuzzer.JUR_UCC;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * WBS item 6, first box — "per-flag unit tests from the campaign replay
 * seeds". The 2026-10-03 campaign's finding-2 representative seeds
 * ({@code docs/fuzz-campaigns-2026-10-03.md}: one per known family, each
 * replayable via {@code -Pcamp.mode=jur -Pcamp.one=<seed>}) plus a
 * deterministic per-axis sweep, run under EVERY flag setting — bare, each
 * axis alone, the CI pairs, all four — against the LIVE
 * {@code java.util.regex} oracle through the jur FACADE (both tiers):
 *
 * <ul>
 *   <li><b>hard</b>: the full five-probe protocol (F/I/M/L/R, GROUP
 *       clauses included) must equal the oracle under the campaign's flag
 *       translation — post-flip the lane's pivots own terminator set,
 *       anchors, fold universe (İ/ı merged since the re-base fix) and
 *       family-6 groups on every axis combination;</li>
 *   <li><b>compile parity</b>: the facade accepts iff the oracle accepts,
 *       the v1 {@code (?U} limit excepted;</li>
 *   <li><b>the asserted domain boundary</b>: a divergence is allowed only
 *       when one of the reclassified evidence-re-base exclusions explains
 *       it (family-6 tail: skeleton-equal + quantified capture;
 *       surrogate-unit semantics: lone surrogates or the pair-predicates).
 *       Anything else fails — the classifiers are assertions here, not
 *       soft passes.</li>
 * </ul>
 */
class PerFlagReplayTest {

    /** The campaign's recorded finding-2 representatives (one per family). */
    private static final long[] REPLAY_SEEDS = {263695807358897136L, // \B on Z💩💩𐐂a — unit-boundary-positions
        349139821284141069L, // ^(?m) on "\n-İa\n" — caret-after-final-newline
        553574611371856328L, // .$ on '€ ᲅ漢т𐐡\n' — dollar-before-final-newline
        964326838431220311L, // (\z)* on "" — group-participation-lineage
        308827754503170982L, // seq-mode pair-interior (finding 1, fixed)
    };

    /** Every flag setting the per-flag battery runs: bare, each axis
     *  alone, CI-pairs, and all four (no LONGEST — jur has no reading). */
    private static final int[] FLAG_SETTINGS = {0, FLAG_CI, FLAG_DOTALL, FLAG_MULTILINE, FLAG_UCC, FLAG_CI | FLAG_UCC,
        FLAG_CI | FLAG_DOTALL | FLAG_MULTILINE | FLAG_UCC};

    static Stream<String> cases() {
        List<String> out = new ArrayList<>();
        for (long seed : REPLAY_SEEDS) {
            for (int flags : FLAG_SETTINGS) {
                out.add(seed + "/" + flags);
            }
        }
        SplittableRandom master = new SplittableRandom(0x5EED5EEDL);
        for (int i = 0; i < 48; i++) {
            long seed = master.nextLong() >>> 4;
            for (int flags : FLAG_SETTINGS) {
                out.add(seed + "/" + flags);
            }
        }
        return out.stream();
    }

    private static long seedOf(String id) {
        return Long.parseLong(id.substring(0, id.indexOf('/')));
    }

    private static int flagsOf(String id) {
        return Integer.parseInt(id.substring(id.indexOf('/') + 1));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void jurLaneMatchesTheOraclePerFlag(String id) {
        long seed = seedOf(id);
        int flags = flagsOf(id);
        CampaignFuzzer.JurCase base = CampaignFuzzer.genJur(seed);
        String pattern = base.pattern();
        String input = base.input();
        // the v1 (?U) limit: (?U:...)/nested spellings reject at the
        // compile by design — outside the domain. A point-forward
        // top-level (?U) translates (the scoped wrap) and compares.
        if (pattern.contains("(?U:") || pattern.contains("(?U-")) {
            return;
        }
        String oraclePattern = DifferentialFuzzer.jurOraclePattern(pattern);
        int jurFlags = (flags & FLAG_CI) != 0 ? JUR_I : 0;
        jurFlags |= (flags & FLAG_DOTALL) != 0 ? JUR_S : 0;
        jurFlags |= (flags & FLAG_MULTILINE) != 0 ? JUR_M : 0;
        jurFlags |= (flags & FLAG_UCC) != 0 ? JUR_UCC : 0;

        String oracle;
        java.util.regex.Pattern jp;
        try {
            jp = java.util.regex.Pattern.compile(oraclePattern, jurFlags);
            oracle = DifferentialFuzzer.compute(jp, input);
        } catch (RuntimeException e) {
            jp = null;
            oracle = "<reject>";
        }
        String vm;
        String asm;
        try {
            TDFAPattern v = (TDFAPattern) io.github.jemmix.tdfa.Pattern.compile(pattern, flags, TdfaRunner::new, null);
            vm = DifferentialFuzzer.compute(v, input);
        } catch (RuntimeException | StackOverflowError e) {
            vm = tag(e);
        }
        try {
            TDFAPattern a = (TDFAPattern) io.github.jemmix.tdfa.Pattern.compile(pattern, flags, null, null);
            asm = DifferentialFuzzer.compute(a, input);
        } catch (RuntimeException | StackOverflowError e) {
            asm = tag(e);
        }
        assertThat(asm).as("seed %d flags %d: ASM == VM /%s/", seed, flags, pattern).isEqualTo(vm);
        if (oracle.startsWith("<reject")) {
            assertThat(vm).as("seed %d flags %d: oracle rejects, facade follows /%s/", seed, flags, pattern)
                .startsWith("<reject");
            return;
        }
        if (vm.equals(oracle)) {
            return; // the compared domain: hard clean
        }
        // a divergence must be EXPLAINED by an enumerated domain exclusion
        String why = classifiedDomain(pattern, input, oracle, vm);
        assertThat(why).as("seed %d flags %d: unexplained divergence /%s/ on %s%n oracle=%s%n    vm=%s", seed, flags,
            DifferentialFuzzer.escape(pattern), DifferentialFuzzer.escape(input), oracle, vm).isNotNull();
    }

    /** The asserted domain boundary — the reclassified evidence-re-base
     *  exclusions ({@code CampaignFuzzer.jurLaneCase} /
     *  {@code DifferentialFuzzer.jurLaneDomain} own the same predicates).
     *  Null = the divergence is unexplained (a failure). */
    private static String classifiedDomain(String pattern, String input, String oracle, String vm) {
        if (vm.startsWith("<reject")) {
            return "budget-reject (fuzz-scoped compile budget)";
        }
        if (oracle.startsWith("<exception")) {
            return "oracle-threw";
        }
        if (CampaignFuzzer.sameSkeleton(oracle, vm) && pattern.matches(".*\\(.*\\(.*\\)[*+{].*\\)[*+{].*")) {
            return "nested-loop-capture";
        }
        if (CampaignFuzzer.hasLoneSurrogate(pattern) || CampaignFuzzer.hasLoneSurrogate(input)
            || CampaignFuzzer.laneExtrasAllPairInteriors(oracle, vm, input)
            || CampaignFuzzer.negatedClassSurrogateResidual(pattern, input)
            || CampaignFuzzer.boundarySurrogateResidual(pattern, input)) {
            return "surrogate-unit-semantics";
        }
        return null;
    }

    private static String tag(Throwable e) {
        return e instanceof PatternTooLargeException ? "<reject:pattern too large>"
            : "<exception:" + e.getClass().getSimpleName() + ">";
    }
}
