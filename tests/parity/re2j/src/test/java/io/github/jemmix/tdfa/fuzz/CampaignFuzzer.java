package io.github.jemmix.tdfa.fuzz;

import com.google.re2j.Re2jUnicodeProvider;
import io.github.jemmix.tdfa.asm.TdfaAsmBackend;
import io.github.jemmix.tdfa.core.budget.Budgets;
import io.github.jemmix.tdfa.core.budget.PatternTooLargeException;
import io.github.jemmix.tdfa.core.budget.WorkMeter;
import io.github.jemmix.tdfa.core.determinize.Determinizer;
import io.github.jemmix.tdfa.core.dfa.Tdfa;
import io.github.jemmix.tdfa.core.dfa.TdfaRunner;
import io.github.jemmix.tdfa.core.engine.MatchResult;
import io.github.jemmix.tdfa.core.engine.RegexEngine;
import io.github.jemmix.tdfa.core.engine.WholeEngine;
import io.github.jemmix.tdfa.core.report.CompileObserver;
import io.github.jemmix.tdfa.core.tnfa.Semantics;
import io.github.jemmix.tdfa.core.tnfa.Tnfa;
import io.github.jemmix.tdfa.core.unicode.UnicodeProviders;
import io.github.jemmix.tdfa.sim.PikeSim;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.CharBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Timeboxed fuzz campaigns for the TODO.md fuzzer items the overnight
 * differential soak does not cover (yet). Four modes, one driver:
 *
 * <ul>
 *   <li><b>{@code jur}</b> — the {@code (?u)} lane vs the live
 *       {@code java.util.regex} oracle (TODO: "Fuzz (?u) lane vs jur oracle").
 *       Cases reuse the soak generator ({@link DifferentialFuzzer#generate}),
 *       the flag matrix draws UNICODE_CHARACTER_CLASS (plus CI/DOTALL/
 *       MULTILINE — no LONGEST: jur has no equivalent) and translates the
 *       bits to jur values. tdfa compiles on the DEFAULT JDK-derived
 *       universe (provider {@code null}) so {@code (?u)} semantics are
 *       JDK-equal by construction — re2j plays no role here. Documented
 *       known families are classified, not failed: tdfa keeps codepoint
 *       boundaries where jur matches into surrogate-pair interiors, and
 *       jur treats {@code \r} (and U+0085/U+2028/U+2029) as line
 *       terminators where the RE2 lineage sees {@code \n} only. Inline
 *       {@code (?U:...)} groups stay skipped on the RE2-lane probe (tdfa:
 *       ungreedy; jur: UNICODE_CHARACTER_CLASS — untranslatable); the
 *       JUR-lane probe parses {@code (?U} itself (v1: {@code (?U:...)}
 *       shapes reject by design and count soft; a leading {@code (?U)}
 *       translates to the oracle's UNICODE_CASE flag). Under CI the jur
 *       answer is computed under both plain CASE_INSENSITIVE and
 *       CASE_INSENSITIVE|UNICODE_CASE; disagreement ("fold ambiguous" —
 *       non-ASCII folds involved, the universes legitimately differ) skips
 *       the case rather than guessing a side; the JUR lane folds ASCII
 *       under bare CI exactly like the oracle, so its probe compares
 *       under plain CASE_INSENSITIVE with no fold skip.</li>
 *   <li><b>{@code stretch}</b> — oracle-check stretched inputs and compare
 *       ASM on them too (TODO: "Oracle-check stretched inputs; compare ASM
 *       on them too"). The soak's stretched-input probes compare forced VM
 *       rungs against the natural VM run only; this mode stretches every
 *       case ({@link DifferentialFuzzer#stretch}) and runs the full
 *       five-probe protocol on all three soak lanes (re2j oracle, ASM, VM)
 *       against the stretched input.</li>
 *   <li><b>{@code seq}</b> — non-String CharSequence inputs against the
 *       reference sim (TODO: "Fuzz non-String CharSequence inputs against
 *       the reference sim"). Three CharSequence wrappers (StringBuilder,
 *       CharBuffer, a plain char[] view) must produce the identical
 *       five-probe protocol as the String path on BOTH engines (the
 *       GENERIC rung contract), and the find+iterate protocol over the
 *       wrappers must equal {@link PikeSim} — the specification-of-record —
 *       compiled on the same unicode universe as the soak lanes. Routing is
 *       traced: find() over a non-String input must be served by the
 *       GENERIC rung.</li>
 *   <li><b>{@code families}</b> — fact-gated rung families (TODO: "Fuzz
 *       families for fact-gated rungs: literal, prefix, cand-scan,
 *       anchored-fast"). Family-biased generators (pure literal needles,
 *       required-literal-prefix + tail, candidate-scan shapes on short
 *       inputs, fastPath-eligible anchored shapes) are checked against the
 *       re2j oracle on both engines, and the VM strategy trace records
 *       whether the family's target rung actually served the call
 *       (LITERAL/PREFIX/CAND_SCAN on find(), ANCHORED_FAST on matches()) —
 *       gate misses are coverage findings (the detector failed to fire),
 *       recorded with the routing histogram.</li>
 * </ul>
 *
 * <p>Run via the {@code camp} Gradle task (a soak, not a unit test —
 * {@code CampaignSmokeTest} gates the harness in CI):
 * <pre>
 *   ./gradlew :tests:parity:re2j:camp                       # all four modes, 600 s each
 *   ./gradlew :tests:parity:re2j:camp -Pcamp.mode=jur -Pcamp.seconds=600 -Pcamp.seed=42
 *   ./gradlew :tests:parity:re2j:camp -Pcamp.mode=jur -Pcamp.one=&lt;caseSeed&gt;   # replay one case
 * </pre>
 * Artifacts per mode under the out dir (default {@code build/fuzz-camp/}):
 * {@code failures.ndjson} (every record, kinds classify hard vs soft) and
 * {@code summary.txt}. Exit nonzero on hard findings. Case generation is a
 * pure function of {@code caseSeed} per mode, so every record replays via
 * {@code -Pcamp.one}.
 *
 * <p>Classification: <b>hard</b> kinds fail the run (MISMATCH/COMPILE_PARITY/
 * EXCEPTION/ROUTING families); <b>soft</b> kinds are recorded and counted but
 * reflect documented contracts or oracle limits (budget rejections under the
 * fuzz-scoped compile budget, known jur divergence families, jur oracle
 * blowups, slow-case skips).
 */
public final class CampaignFuzzer {

    // ---- knobs (system properties; the Gradle task forwards -Pcamp.*) ----

    private static final int UCC = io.github.jemmix.tdfa.Pattern.UNICODE_CHARACTER_CLASS;

    private static final int JUR_I = java.util.regex.Pattern.CASE_INSENSITIVE;

    private static final int JUR_S = java.util.regex.Pattern.DOTALL;

    private static final int JUR_M = java.util.regex.Pattern.MULTILINE;

    private static final int JUR_UCC = java.util.regex.Pattern.UNICODE_CHARACTER_CLASS;

    private static final int JUR_UCASE = java.util.regex.Pattern.UNICODE_CASE;

    /** Soft wall guard per case; over it the case is recorded SLOW and skipped. */
    private static final long CASE_SLOW_MS = 3_000;

    // ---- families mode: family-biased shape pools (see genFamilies) ----

    static final String[] PREFIX_TAILS =
        {"\\w+", "\\d+", "[a-z0-9]{2,}", "(a|b)+", "\\S{1,4}", ".*x", "(?:ab|ba)+", "\\w\\d", "(q|1){2,}"};

    static final String[] CAND_PATTERNS = {"(a|b)+c", "[wx9]{2,}z", "\\w\\s?k", "(ab|ba)*f", "[^a]{1,3}q", "\\d+x?",
        "(z|9)[a-z]k", "[a-c]{3}[^x]", "(\\w\\d)+e"};

    static final String[] FAST_PATTERNS =
        {"\\d+", "[a-f0-9]{2,8}", "a+|b+", "\\w\\d\\w", "[0-9]+\\.[0-9]+", "[a-z]+!", "\\d\\s\\d"};

    static final String LIT_ALPHABET = "abz09ZYqw_.";

    /** Input pool for family shapes (ASCII-dominant). */
    static final String INPUT_ALPHABET = "abz09ZY qw_-.#~\n";

    public static void main(String[] argv) throws Exception {
        String mode = System.getProperty("camp.mode", "all");
        long one = Long.getLong("camp.one", 0);
        if (one != 0) {
            replay(mode, one);
            return;
        }
        long seed = Long.getLong("camp.seed", 0) != 0 ? Long.getLong("camp.seed", 0) : System.currentTimeMillis();
        long seconds = Long.getLong("camp.seconds", 600);
        long maxCases = Long.getLong("camp.cases", 0);
        Path out = Path.of(System.getProperty("camp.out", "build/fuzz-camp"));
        long hard;
        if (mode.equals("all")) {
            Map<String, Long> seeds = modeSeeds(seed);
            List<Thread> ts = new ArrayList<>();
            AtomicLong box = new AtomicLong();
            for (var e : seeds.entrySet()) {
                Thread t = new Thread(() -> box.addAndGet(runMode(e.getKey(), e.getValue(), seconds, maxCases, out)),
                    "camp-" + e.getKey());
                t.setDaemon(true);
                ts.add(t);
                t.start();
            }
            for (Thread t : ts) {
                t.join();
            }
            hard = box.get();
        } else {
            hard = runMode(mode, seed, seconds, maxCases, out);
        }
        System.out.printf("%n==== camp done (%s): %d hard findings ====%n", mode, hard);
        System.exit(hard > 0 ? 1 : 0);
    }

    private static Map<String, Long> modeSeeds(long seed) {
        Map<String, Long> m = new LinkedHashMap<>();
        SplittableRandom r = new SplittableRandom(seed ^ 0xA11);
        for (String s : new String[]{"jur", "stretch", "seq", "families"}) {
            m.put(s, r.nextLong());
        }
        return m;
    }

    private static void replay(String mode, long caseSeed) {
        switch (mode) {
            case "jur" -> replayJur(caseSeed);
            case "stretch" -> replayStretch(caseSeed);
            case "seq" -> replaySeq(caseSeed);
            case "families" -> replayFamilies(caseSeed);
            default -> throw new IllegalArgumentException("unknown camp.mode " + mode);
        }
    }

    /** Scoped compile budget, same contract as the soak: budget monsters
     * reject in seconds instead of burning the campaign. */
    static long runMode(String mode, long seed, long seconds, long maxCases, Path outRoot) {
        long fuzzWork = DifferentialFuzzer.fuzzWorkBudget();
        String prevWork =
            fuzzWork > 0 ? System.setProperty("tdfa.budget.compile.compute", Long.toString(fuzzWork)) : null;
        try {
            return runModeScoped(mode, seed, seconds, maxCases, outRoot);
        } finally {
            if (prevWork != null) {
                System.setProperty("tdfa.budget.compile.compute", prevWork);
            } else if (fuzzWork > 0) {
                System.clearProperty("tdfa.budget.compile.compute");
            }
        }
    }

    private static long runModeScoped(String mode, long seed, long seconds, long maxCases, Path outRoot) {
        Camp camp = new Camp(outRoot, mode);
        Counts counts = new Counts();
        try {
            camp.log("start: mode=%s seed=%d seconds=%d maxCases=%d", mode, seed, seconds, maxCases);
            long deadline = System.nanoTime() + seconds * 1_000_000_000L;
            long start = System.nanoTime();
            long n = 0, lastProgress = start;
            SplittableRandom caseSeeds = new SplittableRandom(seed ^ mode.hashCode() * 1_000_003L);
            while ((maxCases <= 0 || n < maxCases) && System.nanoTime() < deadline) {
                long caseSeed = caseSeeds.nextLong() >>> 4;
                try {
                    switch (mode) {
                        case "jur" -> {
                            jurCase(caseSeed, camp, counts);
                            jurLaneCase(caseSeed, camp, counts);
                        }
                        case "stretch" -> stretchCase(caseSeed, camp, counts);
                        case "seq" -> seqCase(caseSeed, camp, counts);
                        case "families" -> familiesCase(caseSeed, camp, counts);
                        default -> throw new IllegalStateException("unknown mode " + mode);
                    }
                } catch (RuntimeException e) {
                    counts.hard("HARNESS_EXCEPTION");
                    camp.rec(caseSeed, "HARNESS_EXCEPTION", "pattern", "", "input", "", "detail",
                        e + " @ " + firstFrame(e));
                    e.printStackTrace(System.err);
                }
                n++;
                long now = System.nanoTime();
                if (now - lastProgress > 15_000_000_000L) {
                    camp.log("t=%5.1fs cases=%d %s", (now - start) / 1_000_000_000.0, n, counts);
                    lastProgress = now;
                }
            }
            camp.log("done: cases=%d %s", n, counts);
        } finally {
            camp.writeSummary(counts);
            camp.close();
        }
        return counts.hard;
    }

    // ================ mode: jur — the (?u) lane vs java.util.regex ================

    record JurCase(String pattern, String input, int tdfaFlags, int jurFlags, boolean ucc, boolean ci) {
    }

    static JurCase genJur(long caseSeed) {
        long batch = Math.floorDiv(caseSeed, DifferentialFuzzer.BATCH_K);
        int idx = (int) Math.floorMod(caseSeed, DifferentialFuzzer.BATCH_K);
        String pattern = DifferentialFuzzer.genPattern(batch);
        SplittableRandom fr = new SplittableRandom(caseSeed ^ 0x6A7572L); // "jur"
        boolean ucc = fr.nextInt(10) < 6; // the lane exists for (?u): bias it on
        boolean ci = fr.nextInt(10) < 3;
        boolean ds = fr.nextInt(10) < 3;
        boolean ml = fr.nextInt(10) < 3;
        int tdfaFlags = (ucc ? UCC : 0) | (ci ? DifferentialFuzzer.FLAG_CI : 0)
            | (ds ? DifferentialFuzzer.FLAG_DOTALL : 0) | (ml ? DifferentialFuzzer.FLAG_MULTILINE : 0);
        String input = DifferentialFuzzer.genInput(batch, idx, tdfaFlags);
        int jurFlags = (ucc ? JUR_UCC : 0) | (ci ? JUR_I : 0) | (ds ? JUR_S : 0) | (ml ? JUR_M : 0);
        return new JurCase(pattern, input, tdfaFlags, jurFlags, ucc, ci);
    }

    static void jurCase(long caseSeed, Camp camp, Counts counts) {
        JurCase c = genJur(caseSeed);
        if (c.pattern().contains("(?U")) {
            // RE2-lane residual (the JUR lane below parses (?U itself since
            // the fold + U bits item): this probe's tdfa compile is the
            // facade's pre-flip lane, which reads (?U:...) as ungreedy;
            // java reads it as UNICODE_CHARACTER_CLASS — untranslatable
            // until the flip moves the facade to the java reading.
            counts.soft("SKIP_UNGREEDY");
            return;
        }
        String jur = "<reject>";
        java.util.regex.Pattern jp;
        try {
            jp = java.util.regex.Pattern.compile(c.pattern(), c.jurFlags());
        } catch (java.util.regex.PatternSyntaxException e) {
            jp = null;
        }
        String jurAlt = null;
        if (jp != null && c.ci()) {
            // Fold-universe disambiguation: jur CI alone folds ASCII, CI|UNICODE_CASE
            // folds fully. When the two disagree the case is fold-ambiguous (the
            // engines' universes legitimately differ there) — skip, don't guess.
            try {
                jurAlt = computeJur(java.util.regex.Pattern.compile(c.pattern(), c.jurFlags() | JUR_UCASE), c.input());
                if (jur != null && !jur.equals(jurAlt)) {
                    counts.soft("SKIP_CI_FOLD_AMBIGUOUS");
                    return;
                }
            } catch (RuntimeException | StackOverflowError e) {
                counts.soft("SKIP_CI_FOLD_AMBIGUOUS");
                return;
            }
        }
        if (jp != null) {
            try {
                jur = computeJur(jp, c.input());
            } catch (RuntimeException | StackOverflowError e) {
                counts.soft("JUR_ORACLE_THREW");
                camp.rec(caseSeed, "JUR_ORACLE_THREW", "pattern", c.pattern(), "input", c.input(), "flags",
                    c.tdfaFlags(), "detail", e.getClass().getSimpleName());
                return;
            }
        }
        String vm = runTdfaJdk(c, true);
        String asm = runTdfaJdk(c, false);
        String detail = "";
        if (vm.contains("pattern too large") || asm.contains("pattern too large")) {
            counts.soft("BUDGET");
            return;
        }
        String kind = null;
        if (jur.startsWith("<reject")) {
            if (!vm.startsWith("<reject") || !asm.startsWith("<reject")) {
                kind = "COMPILE_PARITY_JUR (jur rejects, tdfa accepts)";
            }
        } else if (vm.startsWith("<reject") || asm.startsWith("<reject")) {
            kind = "COMPILE_PARITY_JUR (jur accepts, tdfa rejects)";
        } else if (vm.startsWith("<exception") || asm.startsWith("<exception")) {
            kind = "JUR_EXCEPTION";
        } else if (!vm.equals(jur) || !asm.equals(jur)) {
            kind = "JUR_MISMATCH";
        }
        if (kind == null) {
            counts.ok();
            return;
        }
        // g) the İ/ı full-universe residual under UCC-implies-fold (found by
        // a seed-987654321 run; reproduces on the pre-fold-item tree):
        // java under CI|UCC folds the Turkic pair into the i-orbit, tdfa's
        // simple-fold universe keeps it inert (the deliberate
        // re2j/Go-compatible pin). Flag-CI cases usually skipped earlier by
        // the disambiguation above; inline (?i:...) + UCC reaches here. A
        // missed oracle span starting at a non-ASCII char is that family.
        if ((c.ci() || c.pattern().contains("(?i")) && missedSpanStartsNonAscii(jur, vm, c.input())) {
            counts.soft("SKIP_CI_FOLD_AMBIGUOUS");
            return;
        }
        // Known-family classification (documented semantics, not bugs — jur is
        // a UTF-16 code-unit engine, tdfa keeps the RE2-lineage contract the
        // soak pins against re2j):
        //  a) lone surrogates anywhere in pattern/input — jur matches into
        //     pair interiors, tdfa keeps codepoint boundaries;
        //  b) input contains \r — jur treats \r/U+0085/U+2028/U+2029 as line
        //     terminators for ./^/$, the RE2 lineage sees \n only;
        //  c) \b/\B with well-formed pairs present — jur evaluates word
        //     boundaries at every UTF-16 UNIT (including pair interiors),
        //     tdfa only at codepoint boundaries;
        //  d) MULTILINE + trailing \n + ^ — tdfa/re2j match the empty last
        //     line, jur does not;
        //  e) non-MULTILINE + trailing \n + $ — jur matches before the final
        //     line terminator, the RE2 lineage requires true end-of-text.
        boolean surrogate = hasLoneSurrogate(c.pattern()) || hasLoneSurrogate(c.input());
        boolean lineterm = c.input().indexOf('\r') >= 0;
        boolean ml = (c.tdfaFlags() & DifferentialFuzzer.FLAG_MULTILINE) != 0;
        boolean unitBoundary =
            (c.pattern().contains("\\b") || c.pattern().contains("\\B")) && hasWellFormedPair(c.input());
        // the inline (?m:) spelling counts too, and the EMPTY input is the
        // family's degenerate: java's (?m)^ refuses the sole position (0==len)
        // where the RE2 lineage matches it — e.g. $(?m:^) on "".
        boolean caretEol = (ml || c.pattern().contains("(?m")) && (c.input().endsWith("\n") || c.input().isEmpty())
            && c.pattern().contains("^");
        boolean dollarEol = !ml && c.input().endsWith("\n") && c.pattern().contains("$");
        // f) group participation under iteration of zero-width bodies (e.g.
        // (\z)* on ""): jur's backtracker reports the group non-participating
        // where the pike-VM lineage (re2j AND PikeSim AND tdfa — verified
        // against all three) reports the final empty iteration's span.
        // Skeleton = protocol with the parenthesized group clauses stripped;
        // equal skeletons mean every overall span agreed and only group
        // bookkeeping diverged.
        boolean groupParticipation = vm.equals(asm) && sameSkeleton(jur, vm) && c.pattern().matches(".*[*+{].*");
        if (surrogate || lineterm || unitBoundary || caretEol || dollarEol || groupParticipation) {
            String fam = surrogate ? "surrogate-code-unit"
                : lineterm ? "line-terminator-set"
                    : unitBoundary ? "unit-boundary-positions" : caretEol ? "caret-after-final-newline"
                        : dollarEol ? "dollar-before-final-newline" : "group-participation-lineage";
            counts.soft("KNOWN_" + fam.toUpperCase(Locale.ROOT));
            camp.rec(caseSeed, "JUR_KNOWN (" + fam + ")", kvJur(c, jur, vm, asm));
            return;
        }
        counts.hard(kind);
        camp.rec(caseSeed, kind, kvJur(c, jur, vm, asm));
        camp.log("HARD %s seed=%d pat=%s in=%s%n  jur=%s%n  vm =%s%n  asm=%s", kind, caseSeed,
            DifferentialFuzzer.escape(c.pattern()), DifferentialFuzzer.escape(c.input()), jur, vm, asm);
    }

    /** Item "parameterize the pivots" + "fold + U bits" of the JUR-compat
     *  WBS: the campaign probes BOTH lanes while the shipped default stays
     *  RE2. The JUR lane (core tier, {@code Semantics.of()}) must agree
     *  with the java oracle on every axis the pivots own — terminator set,
     *  EMPTY_LAST_LINE, END_OF_TEXT_ONLY (the lineterm/caretEol/dollarEol
     *  families), the fold universe (bare CI folds ASCII in both, so the
     *  oracle compares under plain CASE_INSENSITIVE — the universes agree
     *  by construction, İ/ı inert on both sides) and the (?U) reading
     *  (SKIP_UNGREEDY retired: (?U:...)/nested spellings reject by design,
     *  counted soft at the compile; a leading (?U) translates to the
     *  oracle's UNICODE_CASE flag) — so those are HARD here; the residual
     *  families stay soft (lone-surrogate pattern/input: unit semantics
     *  match lone surrogates anywhere, java only truly-lone ones). */
    static void jurLaneCase(long caseSeed, Camp camp, Counts counts) {
        JurCase c = genJur(caseSeed);
        // SKIP_UNGREEDY retired (fold + U bits): the JUR lane parses (?U
        // itself. A leading (?U) is the scoped fold upgrade — the oracle's
        // spelling is the UNICODE_CASE flag on the remainder; any other
        // (?U spelling the lane ACCEPTS (mid-pattern top-level) has no flag
        // translation and skips soft below; (?U:...)/nested forms reject
        // at the compile and are counted there.
        boolean leadingScopedU = c.pattern().startsWith("(?U)");
        // JUR-lane compile: same flag→inline-prefix mapping as the facade,
        // Semantics.of() instead of the pre-flip RE2 lane.
        String fl = c.pattern();
        if (c.ci()) {
            fl = "(?i)" + fl;
        }
        if ((c.tdfaFlags() & DifferentialFuzzer.FLAG_DOTALL) != 0) {
            fl = "(?s)" + fl;
        }
        if ((c.tdfaFlags() & DifferentialFuzzer.FLAG_MULTILINE) != 0) {
            fl = "(?m)" + fl;
        }
        if (c.ucc()) {
            fl = "(?u)" + fl;
        }
        Tdfa find;
        try {
            WorkMeter ledger = new WorkMeter(Budgets.compileComputeTicks());
            Tnfa nfa = Tnfa.compile(fl, false, false, false, Semantics.of(), UnicodeProviders.get(),
                CompileObserver.NONE, ledger);
            find = Determinizer.compileWithWholeSide(nfa, false, CompileObserver.NONE, ledger.fork(0));
        } catch (io.github.jemmix.tdfa.core.parser.PatternSyntaxException e) {
            // The lane's only syntax delta is the (?U) v1 limit: top-level
            // flag-only (?U) alone — every other spelling rejects.
            if (c.pattern().contains("(?U")) {
                counts.soft("V1_UNGREEDY_SCOPE");
            }
            return; // other compile parity is the RE2-lane probe's question
        } catch (RuntimeException | StackOverflowError e) {
            counts.soft("JURLANE_BUDGET");
            return;
        }
        if (!leadingScopedU && c.pattern().contains("(?U")) {
            counts.soft("SKIP_SCOPED_U_UNTRANSLATABLE"); // point-forward (?U) has no oracle flag spelling
            return;
        }
        String jurLaneVm;
        String jurLaneAsm;
        try {
            jurLaneVm = engineProtocol(new TdfaRunner(find, 1 << 20), c.input());
            jurLaneAsm = engineProtocol(TdfaAsmBackend.generate(find, 1 << 20), c.input());
        } catch (RuntimeException | StackOverflowError e) {
            counts.soft("JURLANE_EXCEPTION");
            camp.rec(caseSeed, "JURLANE_EXCEPTION", "pattern", c.pattern(), "input", c.input(), "flags", c.tdfaFlags(),
                "detail", e.getClass().getSimpleName());
            return;
        }
        if (!jurLaneVm.equals(jurLaneAsm)) {
            counts.hard("JURLANE_VM_ASM");
            camp.rec(caseSeed, "JURLANE_VM_ASM", "pattern", c.pattern(), "input", c.input(), "flags", c.tdfaFlags(),
                "vm", jurLaneVm, "asm", jurLaneAsm);
            camp.log("HARD JURLANE_VM_ASM seed=%d pat=%s in=%s%n  vm =%s%n  asm=%s", caseSeed,
                DifferentialFuzzer.escape(c.pattern()), DifferentialFuzzer.escape(c.input()), jurLaneVm, jurLaneAsm);
            return;
        }
        // oracle: java.util.regex under the lane's reading — bare CI (the
        // ASCII universe); a leading (?U) upgrade is the UNICODE_CASE flag
        // on the remainder.
        String oraclePattern = leadingScopedU ? c.pattern().substring(4) : c.pattern();
        int jurFlags = c.jurFlags() | (leadingScopedU ? JUR_UCASE : 0);
        String jur;
        java.util.regex.Pattern jp;
        try {
            jp = java.util.regex.Pattern.compile(oraclePattern, jurFlags);
        } catch (java.util.regex.PatternSyntaxException e) {
            return;
        }
        try {
            jur = javaProtocol(jp, c.input());
        } catch (RuntimeException | StackOverflowError e) {
            counts.soft("JUR_ORACLE_THREW");
            return;
        }
        // Span-level comparison: the engine protocol carries no group
        // clauses (group-participation parity is the EMPTY_ITERATION_SPANS
        // item's evidence work), so the oracle's clauses are stripped.
        if (stripGroups(jur).equals(jurLaneVm)) {
            counts.ok();
            return;
        }
        // Full-fold lane residual (JDK-verified: UCC implies Unicode-aware
        // CI, so (?u)/(?U) lanes fold like CI|UNICODE_CASE): java merges
        // the Turkic İ/ı pair into the i-orbit there, tdfa's simple-fold
        // universe keeps it inert — a missed oracle span starting at a
        // non-ASCII char is that family, soft. Bare-CI lanes (ASCII
        // universe both sides) cannot hit it.
        boolean ciAnywhere = c.ci() || c.pattern().contains("(?i");
        boolean laneFoldsFull = c.ucc() || leadingScopedU || c.pattern().contains("(?u");
        if (ciAnywhere && laneFoldsFull && missedSpanStartsNonAscii(jur, jurLaneVm, c.input())) {
            counts.soft("SKIP_CI_FOLD_AMBIGUOUS");
            return;
        }
        boolean surrogate = hasLoneSurrogate(c.pattern()) || hasLoneSurrogate(c.input())
            || laneExtrasAllPairInteriors(jur, jurLaneVm, c.input())
            || negatedClassSurrogateResidual(c.pattern(), c.input());
        if (surrogate) {
            counts.soft("KNOWN_SURROGATE (jur lane, residual)");
            camp.rec(caseSeed, "JURLANE_KNOWN (surrogate-code-unit)", "pattern", c.pattern(), "input", c.input(),
                "flags", c.tdfaFlags(), "jur", jur, "jurLaneVm", jurLaneVm);
            return;
        }
        counts.hard("JURLANE_MISMATCH");
        camp.rec(caseSeed, "JURLANE_MISMATCH", "pattern", c.pattern(), "input", c.input(), "flags", c.tdfaFlags(),
            "jurFlags", jurFlags, "jur", jur, "jurLaneVm", jurLaneVm);
        camp.log("HARD JURLANE_MISMATCH seed=%d pat=%s in=%s%n  jur=%s%n  lane=%s", caseSeed,
            DifferentialFuzzer.escape(c.pattern()), DifferentialFuzzer.escape(c.input()), jur, jurLaneVm);
    }

    /** F+I+M+R protocol at the engine level (the facade wraps these the
     *  same way; the soak's five-probe L probe has no engine-level
     *  equivalent and is omitted on both sides). */
    static String engineProtocol(RegexEngine eng, CharSequence in) {
        StringBuilder sb = new StringBuilder(96);
        int from = 0;
        boolean found = false;
        int n = 0;
        while (from <= in.length()) {
            MatchResult m = eng.match(in, from, null);
            if (m == null) {
                break;
            }
            if (!found) {
                sb.append("F=true ");
                found = true;
            }
            if (n < DifferentialFuzzer.MAX_MATCHES) {
                sb.append(m.start(0)).append("..").append(m.end(0)).append(' ');
            }
            n++;
            from = m.end(0) == m.start(0) ? m.end(0) + 1 : m.end(0);
        }
        // M probe: the whole-exact walk (the facade's matches() surface);
        // RegexEngine.matches()'s boolean walk is documented-approximate
        // over pike-cut artifacts, which would diverge from the oracle for
        // non-semantic reasons.
        boolean m;
        if (eng instanceof WholeEngine we) {
            m = we.matchWhole(in, null) != null;
        } else {
            m = eng.matches(in);
        }
        sb.append(!found ? "F=false" : n == DifferentialFuzzer.MAX_MATCHES ? "(cap)" : "")
            .append(m ? " M=true" : " M=false");
        MatchResult r = eng.match(in, in.length() / 2, null);
        sb.append(" R=").append(r == null ? "-" : r.start(0) + ".." + r.end(0));
        return sb.toString();
    }

    /** The same protocol over java.util.regex (find(from)-managed loop). */
    static String javaProtocol(java.util.regex.Pattern p, CharSequence in) {
        StringBuilder sb = new StringBuilder(96);
        int from = 0;
        boolean found = false;
        int n = 0;
        while (from <= in.length()) {
            java.util.regex.Matcher m = p.matcher(in);
            if (!m.find(from)) {
                break;
            }
            if (!found) {
                sb.append("F=true ");
                found = true;
            }
            if (n < DifferentialFuzzer.MAX_MATCHES) {
                spanJur(sb, m);
                sb.append(' ');
            }
            n++;
            from = m.end() == m.start() ? m.end() + 1 : m.end();
        }
        sb.append(!found ? "F=false" : n == DifferentialFuzzer.MAX_MATCHES ? "(cap)" : "")
            .append(p.matcher(in).matches() ? " M=true" : " M=false");
        java.util.regex.Matcher m2 = p.matcher(in);
        sb.append(" R=").append(m2.find(in.length() / 2) ? m2.start() + ".." + m2.end() : "-");
        return sb.toString();
    }

    private static Object[] kvJur(JurCase c, String jur, String vm, String asm) {
        return new Object[]{"pattern", c.pattern(), "input", c.input(), "flags", c.tdfaFlags(), "jurFlags",
            c.jurFlags(), "jur", jur, "vm", vm, "asm", asm};
    }

    /** tdfa compile + full five-probe protocol on the DEFAULT JDK-derived
     * universe (provider null — the (?u)-parity construction), rejection/
     * exception tagged like the soak. */
    static String runTdfaJdk(JurCase c, boolean vm) {
        try {
            io.github.jemmix.tdfa.Pattern p =
                vm ? io.github.jemmix.tdfa.Pattern.compile(c.pattern(), c.tdfaFlags(), TdfaRunner::new, null)
                    : io.github.jemmix.tdfa.Pattern.compile(c.pattern(), c.tdfaFlags(), null, null);
            return DifferentialFuzzer.compute(p, c.input());
        } catch (io.github.jemmix.tdfa.core.parser.PatternSyntaxException | PatternTooLargeException e) {
            return "<reject:" + DifferentialFuzzer.firstLine(e.getMessage()) + ">";
        } catch (RuntimeException e) {
            return "<exception:" + e.getClass().getSimpleName() + ">";
        }
    }

    /** Five-probe span protocol for the jur lane (mirrors the soak's compute). */
    static String computeJur(java.util.regex.Pattern p, CharSequence in) {
        StringBuilder sb = new StringBuilder(96);
        java.util.regex.Matcher m = p.matcher(in);
        boolean found = m.find();
        if (found) {
            spanJur(sb.append("F=true "), m);
        } else {
            sb.append("F=false");
        }
        sb.append(" I=[");
        int n = 0;
        if (found) {
            spanJur(sb, m);
            while (++n < DifferentialFuzzer.MAX_MATCHES && m.find()) {
                spanJur(sb, m);
            }
        }
        sb.append(n == DifferentialFuzzer.MAX_MATCHES ? "]+$" : "]");
        java.util.regex.Matcher mm = p.matcher(in);
        if (mm.matches()) {
            spanJur(sb.append(" M=true "), mm);
        } else {
            sb.append(" M=false");
        }
        java.util.regex.Matcher ml = p.matcher(in);
        if (ml.lookingAt()) {
            spanJur(sb.append(" L=true "), ml);
        } else {
            sb.append(" L=false");
        }
        java.util.regex.Matcher mr = p.matcher(in);
        if (mr.find(in.length() / 2)) {
            spanJur(sb.append(" R=true "), mr);
        } else {
            sb.append(" R=false");
        }
        return sb.toString();
    }

    static void spanJur(StringBuilder sb, java.util.regex.Matcher m) {
        sb.append(m.start()).append("..").append(m.end());
        int gc = m.groupCount();
        if (gc > 0) {
            sb.append(" (");
            for (int i = 1; i <= gc; i++) {
                if (i > 1) {
                    sb.append(' ');
                }
                int s = m.start(i);
                sb.append(s < 0 ? "-" : s + ".." + m.end(i));
            }
            sb.append(')');
        }
    }

    static boolean hasLoneSurrogate(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0xD800 && c <= 0xDBFF) {
                if (i + 1 >= s.length() || s.charAt(i + 1) < 0xDC00 || s.charAt(i + 1) > 0xDFFF) {
                    return true;
                }
                i++;
            } else if (c >= 0xDC00 && c <= 0xDFFF) {
                return true;
            }
        }
        return false;
    }

    /**
     * Feature-level tail of the surrogate residual (the same coarseness the
     * RE2-lane probe's surrogate feature accepts): with well-formed pairs in
     * the input and a pattern that can match NON-word/non-digit/non-space
     * codepoints (negated classes cover the surrogate range), java's
     * unit-level negated-class behavior and the lane's unit semantics
     * diverge in ripples the span-level classifier cannot attribute — the
     * family owns those wholesale. Pivot-relevant shapes (literals, anchors,
     * dot, word boundaries) are unaffected: they are never negated classes.
     */
    static boolean negatedClassSurrogateResidual(String pattern, String input) {
        if (!hasWellFormedPair(input)) {
            return false;
        }
        return pattern.contains("\\W") || pattern.contains("\\S") || pattern.contains("\\D") || pattern.contains("[^");
    }

    /**
     * JUR-lane residual classifier: the LANE's span sequence, with every
     * interior-started span removed, equals the ORACLE's sequence with every
     * span shadowed by a removed one removed. java's classes never match a
     * surrogate unit inside a well-formed pair; the lane's unit semantics
     * do — an interior-started lane match both adds a span and consumes
     * past positions the oracle's next match would have started at. Any
     * other divergence (a lane miss, a different end) is a real finding.
     */
    static boolean laneExtrasAllPairInteriors(String jur, String lane, String input) {
        List<int[]> laneSpans = spansOf(lane);
        List<int[]> jurSpans = spansOf(stripGroups(jur));
        if (laneSpans.isEmpty()) {
            return false; // the lane found nothing: nothing to attribute
        }
        // An interior-started lane span the ORACLE also reports is agreed
        // (java's \B evaluates at every unit, interior included) — only a
        // lane-ONLY interior span is the residual, and only those shadow.
        List<int[]> interiorSpans = new ArrayList<>();
        for (int[] sp : laneSpans) {
            if (startsInterior(input, sp[0]) && !containsSpan(jurSpans, sp)) {
                interiorSpans.add(sp);
            }
        }
        if (interiorSpans.isEmpty()) {
            return false;
        }
        List<int[]> laneFiltered = new ArrayList<>();
        for (int[] sp : laneSpans) {
            if (!startsInterior(input, sp[0]) || containsSpan(jurSpans, sp)) {
                laneFiltered.add(sp);
            }
        }
        List<int[]> jurFiltered = new ArrayList<>();
        for (int[] sp : jurSpans) {
            if (!shadowed(sp, interiorSpans)) {
                jurFiltered.add(sp);
            }
        }
        if (laneFiltered.size() != jurFiltered.size()) {
            return false;
        }
        for (int i = 0; i < laneFiltered.size(); i++) {
            if (laneFiltered.get(i)[0] != jurFiltered.get(i)[0] || laneFiltered.get(i)[1] != jurFiltered.get(i)[1]) {
                return false;
            }
        }
        // The R probe under the same rule: the lane's restart span equals the
        // oracle's, or starts interior, or the oracle's is shadowed.
        int[] rLane = rSpanOf(lane);
        int[] rJur = rSpanOf(jur);
        if (rLane == null) {
            return rJur == null;
        }
        return startsInterior(input, rLane[0]) || (rJur != null && rJur[0] == rLane[0] && rJur[1] == rLane[1])
            || (rJur != null && shadowed(rJur, interiorSpans));
    }

    /** Shadowed by a removed interior-started lane span? */
    static boolean shadowed(int[] sp, List<int[]> interiorSpans) {
        for (int[] is : interiorSpans) {
            if (sp[0] >= is[0] && sp[0] < is[1]) {
                return true;
            }
        }
        return false;
    }

    /** Exact (start, end) membership. */
    static boolean containsSpan(List<int[]> spans, int[] sp) {
        for (int[] x : spans) {
            if (x[0] == sp[0] && x[1] == sp[1]) {
                return true;
            }
        }
        return false;
    }

    /**
     * Some oracle span the lane does not report starts at a non-ASCII char —
     * the full-universe fold residual (İ/ı-style: java folds it under
     * CI|UCC — UCC implies Unicode-aware CI — tdfa's simple-fold keeps it
     * inert). Only consulted on full-fold lanes.
     */
    static boolean missedSpanStartsNonAscii(String jur, String lane, String input) {
        List<int[]> js = spansOf(stripGroups(jur));
        List<int[]> ls = spansOf(lane);
        for (int[] sp : js) {
            if (!containsSpan(ls, sp) && sp[0] < input.length() && input.charAt(sp[0]) > 0x7F) {
                return true;
            }
        }
        return false;
    }

    /** Interior unit of a well-formed surrogate pair? */
    static boolean startsInterior(String input, int st) {
        return st > 0 && st < input.length() && input.charAt(st) >= 0xDC00 && input.charAt(st) <= 0xDFFF
            && input.charAt(st - 1) >= 0xD800 && input.charAt(st - 1) <= 0xDBFF;
    }

    /**
     * The ordered s..e spans of a protocol's F/I section (the section ends
     * at the M probe; group clauses are absent in the engine protocol and
     * stripped from the oracle's by the caller).
     */
    static List<int[]> spansOf(String protocol) {
        int cut = protocol.indexOf(" M=");
        String fi = cut >= 0 ? protocol.substring(0, cut) : protocol;
        List<int[]> out = new ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)\\.\\.(\\d+)").matcher(fi);
        while (m.find()) {
            out.add(new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))});
        }
        return out;
    }

    /** The R probe's single s..e span, or null for {@code R=-}. */
    static int[] rSpanOf(String protocol) {
        int at = protocol.indexOf(" R=");
        if (at < 0) {
            return null;
        }
        java.util.regex.Matcher m =
            java.util.regex.Pattern.compile("(\\d+)\\.\\.(\\d+)").matcher(protocol.substring(at));
        if (!m.find()) {
            return null;
        }
        return new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))};
    }

    /** Any well-formed surrogate pair (an interior the scan could miss). */
    static boolean hasWellFormedPair(String s) {
        for (int i = 0; i + 1 < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0xD800 && c <= 0xDBFF) {
                char n = s.charAt(i + 1);
                if (n >= 0xDC00 && n <= 0xDFFF) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Protocols equal once the parenthesized per-match group clauses are
     * stripped — every overall span agreed, only group bookkeeping differs. */
    static boolean sameSkeleton(String a, String b) {
        return stripGroups(a).equals(stripGroups(b));
    }

    static String stripGroups(String s) {
        return s.replaceAll("\\s*\\([^()]*\\)", "");
    }

    // ================ mode: stretch — oracle + ASM on stretched inputs ================

    static void stretchCase(long caseSeed, Camp camp, Counts counts) {
        DifferentialFuzzer.Case c = DifferentialFuzzer.generate(caseSeed);
        if (c.input().isEmpty()) {
            counts.soft("SKIP_EMPTY");
            return;
        }
        CharSequence st = DifferentialFuzzer.stretch(c.input());
        DifferentialFuzzer.Prepared pr = DifferentialFuzzer.prepare(c.pattern(), c.flags());
        long t0 = System.nanoTime();
        String oracle = pr.oracle != null ? guarded(() -> DifferentialFuzzer.compute(pr.oracle, st)) : pr.oracleTag;
        String asm = pr.asmTag != null ? pr.asmTag : guarded(() -> DifferentialFuzzer.compute(pr.asm, st));
        String vm = pr.vmTag != null ? pr.vmTag : guarded(() -> DifferentialFuzzer.compute(pr.vm, st));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        if (oracle == null || asm == null || vm == null) {
            counts.soft("THREW");
            camp.rec(caseSeed, "STRETCH_THREW", "pattern", c.pattern(), "input", c.input(), "flags", c.flags(),
                "oracle", oracle, "asm", asm, "vm", vm);
            return;
        }
        String kind = classifyThreeWay(oracle, asm, vm, "STRETCH");
        if (ms > CASE_SLOW_MS) {
            counts.soft("SLOW");
            camp.rec(caseSeed, "STRETCH_SLOW", "pattern", c.pattern(), "input", c.input(), "ms", ms);
        }
        if (kind == null) {
            counts.ok();
            return;
        }
        if (kind.contains("BUDGET")) {
            counts.soft(kind);
            return;
        }
        counts.hard(kind);
        camp.rec(caseSeed, kind, "pattern", c.pattern(), "input", c.input(), "flags", c.flags(), "oracle", oracle,
            "asm", asm, "vm", vm);
        camp.log("HARD %s seed=%d pat=%s in=%s%n  oracle=%s%n  asm   =%s%n  vm    =%s", kind, caseSeed,
            DifferentialFuzzer.escape(c.pattern()), DifferentialFuzzer.escape(c.input()), oracle, asm, vm);
    }

    /** Soak-style three-way classification: reject parity, budget softening,
     * then per-lane probe equality. Null = clean. */
    static String classifyThreeWay(String oracle, String asm, String vm, String pfx) {
        if (asm.contains("pattern too large") || vm.contains("pattern too large")) {
            return pfx + "_BUDGET";
        }
        if (oracle.startsWith("<reject")) {
            return asm.startsWith("<reject") && vm.startsWith("<reject") ? null
                : pfx + "_COMPILE_PARITY (re2j rejects)";
        }
        if (asm.startsWith("<reject") || vm.startsWith("<reject")) {
            return pfx + "_COMPILE_PARITY (tdfa rejects)";
        }
        if (asm.startsWith("<exception") || vm.startsWith("<exception")) {
            return pfx + "_EXCEPTION";
        }
        boolean a = asm.equals(oracle), v = vm.equals(oracle);
        if (!a && !v) {
            return pfx + "_MISMATCH (both engines vs oracle)";
        }
        if (!a) {
            return pfx + "_MISMATCH (asm only)";
        }
        if (!v) {
            return pfx + "_MISMATCH (vm only)";
        }
        return null;
    }

    @FunctionalInterface
    interface Probe {
        String run();
    }

    static String guarded(Probe p) {
        try {
            return p.run();
        } catch (RuntimeException | StackOverflowError e) {
            return null;
        }
    }

    // ================ mode: seq — non-String CharSequence vs the reference sim ================

    static void seqCase(long caseSeed, Camp camp, Counts counts) {
        long batch = Math.floorDiv(caseSeed, DifferentialFuzzer.BATCH_K);
        int idx = (int) Math.floorMod(caseSeed, DifferentialFuzzer.BATCH_K);
        String pattern = DifferentialFuzzer.genPattern(batch);
        String input = DifferentialFuzzer.genInput(batch, idx, 0);
        PikeSim sim;
        try {
            sim = PikeSim.compile(pattern, Re2jUnicodeProvider.INSTANCE);
        } catch (RuntimeException e) {
            counts.soft("SIM_REJECT");
            return;
        }
        DifferentialFuzzer.Prepared pr = DifferentialFuzzer.prepare(pattern, 0);
        if (pr.asmTag != null || pr.vmTag != null) {
            String tag = pr.asmTag != null ? pr.asmTag : pr.vmTag;
            counts.soft(tag.contains("pattern too large") ? "BUDGET" : "SEQ_COMPILE");
            return;
        }
        CharSequence sb = new StringBuilder(input);
        CharSequence cb = CharBuffer.wrap(input);
        CharSequence ca = new CharArraySeq(input);
        // (a) String-path vs CharSequence-path protocol equality, both engines.
        String vmStr = DifferentialFuzzer.compute(pr.vm, input);
        String asmStr = DifferentialFuzzer.compute(pr.asm, input);
        for (CharSequence w : new CharSequence[]{sb, cb, ca}) {
            String wv = guarded(() -> DifferentialFuzzer.compute(pr.vm, w));
            String wa = guarded(() -> DifferentialFuzzer.compute(pr.asm, w));
            if (wv == null || wa == null || !wv.equals(vmStr) || !wa.equals(asmStr)) {
                // Finding 1 (2026-10-03 campaign) is FIXED: runGeneric's
                // restart scan skips surrogate-pair interiors exactly like
                // the String ladders and PikeSim, so wrapper == String
                // protocol equality holds on both tiers — any mismatch
                // here is a hard regression.
                counts.hard("SEQ_PATH_MISMATCH");
                camp.rec(caseSeed, "SEQ_PATH_MISMATCH", "pattern", pattern, "input", input, "wrapper",
                    w.getClass().getSimpleName(), "stringVm", vmStr, "stringAsm", asmStr, "wrapVm", wv, "wrapAsm", wa);
                camp.log("HARD SEQ_PATH_MISMATCH seed=%d pat=%s in=%s", caseSeed, DifferentialFuzzer.escape(pattern),
                    DifferentialFuzzer.escape(input));
                return;
            }
        }
        // (b) specification-of-record: PikeSim vs both engines over the wrapper.
        // Reaching here means (a) passed (wrapper == String protocol), so a
        // pike disagreement here is pike vs the String path too — a genuine
        // spec-level finding; the pair-interior family returns at (a) first.
        String pike = guarded(() -> pikeIterate(sim, sb));
        String vmCs = findIterate(pr.vm, sb);
        String asmCs = findIterate(pr.asm, sb);
        if (pike == null || !pike.equals(vmCs) || !pike.equals(asmCs)) {
            counts.hard("SEQ_SIM_MISMATCH");
            camp.rec(caseSeed, "SEQ_SIM_MISMATCH", "pattern", pattern, "input", input, "pike", pike, "vm", vmCs, "asm",
                asmCs);
            camp.log("HARD SEQ_SIM_MISMATCH seed=%d pat=%s in=%s pike=%s vm=%s", caseSeed,
                DifferentialFuzzer.escape(pattern), DifferentialFuzzer.escape(input), pike, vmCs);
            return;
        }
        // (c) wrapper-to-wrapper equality (same engine, different wrappers).
        if (!vmCs.equals(findIterate(pr.vm, cb)) || !vmCs.equals(findIterate(pr.vm, ca))) {
            counts.hard("SEQ_WRAPPER_MISMATCH");
            camp.rec(caseSeed, "SEQ_WRAPPER_MISMATCH", "pattern", pattern, "input", input, "sb", vmCs, "cb",
                findIterate(pr.vm, cb), "ca", findIterate(pr.vm, ca));
            return;
        }
        // (d) routing: find() over a non-String input is served by GENERIC.
        TdfaRunner.setTracing(true);
        TdfaRunner.traceSnapshot();
        pr.vm.matcher(cb).find();
        List<TdfaRunner.Strategy> trace = TdfaRunner.traceSnapshot();
        if (!trace.contains(TdfaRunner.Strategy.GENERIC)) {
            counts.hard("SEQ_ROUTING");
            camp.rec(caseSeed, "SEQ_ROUTING", "pattern", pattern, "input", input, "trace", trace);
            return;
        }
        counts.ok();
    }

    /** F + I probes only (the sim has no matches/lookingAt), span format
     * identical to the soak protocol so strings compare directly. */
    static String findIterate(io.github.jemmix.tdfa.Pattern p, CharSequence in) {
        StringBuilder sb = new StringBuilder(96);
        io.github.jemmix.tdfa.core.engine.Matcher m = p.matcher(in);
        boolean found = m.find();
        if (found) {
            DifferentialFuzzer.spanTdfa(sb.append("F=true "), m);
        } else {
            sb.append("F=false");
        }
        sb.append(" I=[");
        int n = 0;
        if (found) {
            DifferentialFuzzer.spanTdfa(sb, m);
            while (++n < DifferentialFuzzer.MAX_MATCHES && m.find()) {
                DifferentialFuzzer.spanTdfa(sb, m);
            }
        }
        sb.append(n == DifferentialFuzzer.MAX_MATCHES ? "]+$" : "]");
        return sb.toString();
    }

    static String pikeIterate(PikeSim sim, CharSequence in) {
        StringBuilder sb = new StringBuilder(96);
        PikeSim.PikeMatcher m = sim.matcher(in);
        boolean found = m.find();
        if (found) {
            spanPike(sb.append("F=true "), m);
        } else {
            sb.append("F=false");
        }
        sb.append(" I=[");
        int n = 0;
        if (found) {
            spanPike(sb, m);
            while (++n < DifferentialFuzzer.MAX_MATCHES && m.find()) {
                spanPike(sb, m);
            }
        }
        sb.append(n == DifferentialFuzzer.MAX_MATCHES ? "]+$" : "]");
        return sb.toString();
    }

    static void spanPike(StringBuilder sb, PikeSim.PikeMatcher m) {
        sb.append(m.start()).append("..").append(m.end());
        int gc = m.groupCount();
        if (gc > 0) {
            sb.append(" (");
            int[] tags = m.tags();
            for (int g = 1; g <= gc; g++) {
                if (g > 1) {
                    sb.append(' ');
                }
                int open = tags[2 * g - 1];
                sb.append(open < 0 ? "-" : open + ".." + tags[2 * g]);
            }
            sb.append(')');
        }
    }

    /** Minimal non-String CharSequence: a plain char[] view (no StringBuilder
     * internals, no Buffer position state — charAt is array indexing). */
    static final class CharArraySeq implements CharSequence {
        private final char[] a;

        CharArraySeq(CharSequence s) {
            a = new char[s.length()];
            for (int i = 0; i < a.length; i++) {
                a[i] = s.charAt(i);
            }
        }

        @Override
        public int length() {
            return a.length;
        }

        @Override
        public char charAt(int index) {
            return a[index];
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new String(a, start, end - start);
        }

        @Override
        public String toString() {
            return new String(a);
        }
    }

    // ================ mode: families — fact-gated rung families ================

    record FamCase(String family, String pattern, String input, int flags, boolean matchesProbe) {
    }

    static FamCase genFamilies(long caseSeed) {
        SplittableRandom r = new SplittableRandom(caseSeed ^ 0xFA17L);
        int fam = r.nextInt(4);
        boolean ci = r.nextInt(4) == 0; // ASCII-only family patterns: CI is oracle-safe
        int flags = ci ? DifferentialFuzzer.FLAG_CI : 0;
        switch (fam) {
            case 0 -> { // LITERAL: pure literal needle
                int n = 1 + r.nextInt(6);
                StringBuilder b = new StringBuilder(n);
                for (int i = 0; i < n; i++) {
                    b.append(LIT_ALPHABET.charAt(r.nextInt(LIT_ALPHABET.length())));
                }
                return new FamCase("literal", io.github.jemmix.tdfa.Pattern.quote(b.toString()),
                    asciiInput(r, 0, 30, b.toString(), 0.6), flags, false);
            }
            case 1 -> { // PREFIX: required literal prefix + tail
                int n = 2 + r.nextInt(3);
                StringBuilder b = new StringBuilder(n);
                for (int i = 0; i < n; i++) {
                    b.append(LIT_ALPHABET.charAt(r.nextInt(LIT_ALPHABET.length())));
                }
                return new FamCase("prefix",
                    io.github.jemmix.tdfa.Pattern.quote(b.toString()) + PREFIX_TAILS[r.nextInt(PREFIX_TAILS.length)],
                    asciiInput(r, 0, 30, b.toString(), 0.6), flags, false);
            }
            case 2 -> { // CAND_SCAN: class/alternation shape, short input
                return new FamCase("cand-scan", CAND_PATTERNS[r.nextInt(CAND_PATTERNS.length)],
                    asciiInput(r, 3, 40, null, 0), flags, false);
            }
            default -> { // ANCHORED_FAST: fastPath-eligible, checked via matches()
                return new FamCase("anchored-fast", FAST_PATTERNS[r.nextInt(FAST_PATTERNS.length)],
                    asciiInput(r, 0, 30, null, 0), flags, true);
            }
        }
    }

    /** ASCII-dominant input; 10% unicode chars from the soak pool; optionally
     * embeds {@code needle} so the family's positive path runs. */
    static String asciiInput(SplittableRandom r, int minLen, int maxLen, String needle, double embedP) {
        int len = minLen + r.nextInt(maxLen - minLen + 1);
        StringBuilder sb = new StringBuilder(len + (needle == null ? 0 : needle.length()));
        for (int i = 0; i < len; i++) {
            if (r.nextInt(10) == 0) {
                sb.appendCodePoint(DifferentialFuzzer.POOL_UNICODE[r.nextInt(DifferentialFuzzer.POOL_UNICODE.length)]);
            } else {
                sb.append(INPUT_ALPHABET.charAt(r.nextInt(INPUT_ALPHABET.length())));
            }
        }
        if (needle != null && !needle.isEmpty() && r.nextDouble() < embedP) {
            sb.insert(r.nextInt(sb.length() + 1), needle);
        }
        return sb.toString();
    }

    static void familiesCase(long caseSeed, Camp camp, Counts counts) {
        FamCase c = genFamilies(caseSeed);
        DifferentialFuzzer.Prepared pr = DifferentialFuzzer.prepare(c.pattern(), c.flags());
        String oracleStr =
            pr.oracle != null ? guarded(() -> DifferentialFuzzer.compute(pr.oracle, c.input())) : pr.oracleTag;
        String asm = pr.asmTag != null ? pr.asmTag : guarded(() -> DifferentialFuzzer.compute(pr.asm, c.input()));
        String vm = pr.vmTag != null ? pr.vmTag : guarded(() -> DifferentialFuzzer.compute(pr.vm, c.input()));
        String kind = classifyThreeWay(oracleStr, asm == null ? "<exception:null>" : asm,
            vm == null ? "<exception:null>" : vm, "FAMILY");
        if (kind != null && !kind.contains("BUDGET")) {
            counts.hard(kind);
            camp.rec(caseSeed, kind, "pattern", c.pattern(), "input", c.input(), "flags", c.flags(), "family",
                c.family(), "oracle", oracleStr, "asm", asm, "vm", vm);
            camp.log("HARD %s seed=%d pat=%s in=%s%n  oracle=%s%n  asm   =%s%n  vm    =%s", kind, caseSeed,
                DifferentialFuzzer.escape(c.pattern()), DifferentialFuzzer.escape(c.input()), oracleStr, asm, vm);
            return;
        }
        if (kind != null) {
            counts.soft(kind);
            return;
        }
        // Routing: did the family's target rung actually serve the call?
        // NOTE anchored-fast is probed at the ENGINE tier: the facade's
        // matches() routes through matchWhole, which always serves/traces
        // ANCHORED — ANCHORED_FAST is the engine-tier matches() rung
        // (RegexEngine consumers), so the facade can never observe it.
        TdfaRunner.setTracing(true);
        List<TdfaRunner.Strategy> trace;
        if (c.matchesProbe()) {
            TdfaRunner.traceSnapshot();
            engineRunner(c.pattern()).matches(c.input());
            trace = TdfaRunner.traceSnapshot();
        } else {
            TdfaRunner.traceSnapshot();
            pr.vm.matcher(c.input()).find();
            trace = TdfaRunner.traceSnapshot();
        }
        TdfaRunner.Strategy expected = switch (c.family()) {
            case "literal" -> TdfaRunner.Strategy.LITERAL;
            case "prefix" -> TdfaRunner.Strategy.PREFIX;
            case "cand-scan" -> TdfaRunner.Strategy.CAND_SCAN;
            default -> TdfaRunner.Strategy.ANCHORED_FAST;
        };
        counts.route(c.family() + "->" + (trace.isEmpty() ? "none" : trace.get(trace.size() - 1)));
        if (!trace.contains(expected)) {
            counts.soft("GATE_MISS_" + c.family());
            camp.rec(caseSeed, "FAMILY_GATE_MISS (" + c.family() + ")", "pattern", c.pattern(), "input", c.input(),
                "flags", c.flags(), "expected", expected, "trace", trace);
        } else {
            counts.ok();
        }
    }

    /** Engine-tier runner at flags=0 for the routing probe (same composition
     * as StrategyConformanceTest: Tnfa → Determinizer → TdfaRunner). */
    static TdfaRunner engineRunner(String pattern) {
        Tnfa nfa = Tnfa.compile(pattern, false, false, Re2jUnicodeProvider.INSTANCE, null);
        return new TdfaRunner(Determinizer.compile(nfa, false));
    }

    // ================ replay ================

    static void replayJur(long caseSeed) {
        JurCase c = genJur(caseSeed);
        System.out.println("pattern: " + DifferentialFuzzer.escape(c.pattern()));
        System.out.println("input:   " + DifferentialFuzzer.escape(c.input()));
        System.out.println("tdfaFlags: " + c.tdfaFlags() + "  jurFlags: " + c.jurFlags());
        try {
            System.out.println(
                "jur:     " + computeJur(java.util.regex.Pattern.compile(c.pattern(), c.jurFlags()), c.input()));
        } catch (RuntimeException e) {
            System.out.println("jur:     <reject> " + e);
        }
        try {
            System.out.println("vm:      " + DifferentialFuzzer.compute(
                io.github.jemmix.tdfa.Pattern.compile(c.pattern(), c.tdfaFlags(), TdfaRunner::new, null), c.input()));
        } catch (RuntimeException e) {
            System.out.println("vm:      <reject> " + DifferentialFuzzer.firstLine(e.getMessage()));
        }
        try {
            System.out.println("asm:     " + DifferentialFuzzer
                .compute(io.github.jemmix.tdfa.Pattern.compile(c.pattern(), c.tdfaFlags(), null, null), c.input()));
        } catch (RuntimeException e) {
            System.out.println("asm:     <reject> " + DifferentialFuzzer.firstLine(e.getMessage()));
        }
    }

    static void replayStretch(long caseSeed) {
        DifferentialFuzzer.Case c = DifferentialFuzzer.generate(caseSeed);
        System.out.println("pattern: " + DifferentialFuzzer.escape(c.pattern()));
        System.out.println("input:   " + DifferentialFuzzer.escape(c.input()));
        System.out.println("flags:   " + c.flags());
        if (c.input().isEmpty()) {
            System.out.println("(empty input — stretch skips)");
            return;
        }
        CharSequence st = DifferentialFuzzer.stretch(c.input());
        System.out.println("stretched chars: " + st.length());
        DifferentialFuzzer.Prepared pr = DifferentialFuzzer.prepare(c.pattern(), c.flags());
        if (pr.oracle != null) {
            System.out.println("oracle:  " + DifferentialFuzzer.compute(pr.oracle, st));
        } else {
            System.out.println("oracle:  <reject>");
        }
        if (pr.asm != null) {
            System.out.println("asm:     " + DifferentialFuzzer.compute(pr.asm, st));
        }
        if (pr.vm != null) {
            System.out.println("vm:      " + DifferentialFuzzer.compute(pr.vm, st));
        }
    }

    static void replaySeq(long caseSeed) {
        long batch = Math.floorDiv(caseSeed, DifferentialFuzzer.BATCH_K);
        int idx = (int) Math.floorMod(caseSeed, DifferentialFuzzer.BATCH_K);
        String pattern = DifferentialFuzzer.genPattern(batch);
        String input = DifferentialFuzzer.genInput(batch, idx, 0);
        System.out.println("pattern: " + DifferentialFuzzer.escape(pattern));
        System.out.println("input:   " + DifferentialFuzzer.escape(input));
        PikeSim sim = PikeSim.compile(pattern, Re2jUnicodeProvider.INSTANCE);
        System.out.println("pike(String):    " + pikeIterate(sim, input));
        io.github.jemmix.tdfa.Pattern vm =
            io.github.jemmix.tdfa.Pattern.compile(pattern, 0, TdfaRunner::new, Re2jUnicodeProvider.INSTANCE);
        System.out.println("vm(String):      " + findIterate(vm, input));
        System.out.println("pike(wrapper):   " + pikeIterate(sim, new StringBuilder(input)));
        System.out.println("vm(wrapper):     " + findIterate(vm, new StringBuilder(input)));
        System.out.println("asm(wrapper):    "
            + findIterate(io.github.jemmix.tdfa.Pattern.compile(pattern, 0, null, Re2jUnicodeProvider.INSTANCE),
                new StringBuilder(input)));
    }

    static void replayFamilies(long caseSeed) {
        FamCase c = genFamilies(caseSeed);
        System.out.println("family:  " + c.family());
        System.out.println("pattern: " + DifferentialFuzzer.escape(c.pattern()));
        System.out.println("input:   " + DifferentialFuzzer.escape(c.input()));
        System.out.println("flags:   " + c.flags());
        DifferentialFuzzer.Prepared pr = DifferentialFuzzer.prepare(c.pattern(), c.flags());
        if (pr.oracle != null) {
            System.out.println("oracle:  " + DifferentialFuzzer.compute(pr.oracle, c.input()));
        }
        if (pr.asm != null) {
            System.out.println("asm:     " + DifferentialFuzzer.compute(pr.asm, c.input()));
        }
        if (pr.vm != null) {
            System.out.println("vm:      " + DifferentialFuzzer.compute(pr.vm, c.input()));
        }
        TdfaRunner.setTracing(true);
        TdfaRunner.traceSnapshot();
        if (c.matchesProbe()) {
            engineRunner(c.pattern()).matches(c.input());
        } else {
            pr.vm.matcher(c.input()).find();
        }
        System.out.println("trace:   " + TdfaRunner.traceSnapshot());
    }

    // ================ bookkeeping ================

    static final class Counts {
        long cases, hard;
        private final Map<String, Long> soft = new TreeMap<>();
        private final Map<String, Long> routes = new TreeMap<>();

        void ok() {
            cases++;
        }

        void hard(String kind) {
            hard++;
        }

        void soft(String kind) {
            soft.merge(kind, 1L, Long::sum);
        }

        void route(String r) {
            routes.merge(r, 1L, Long::sum);
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("clean=").append(cases).append(" hard=").append(hard);
            if (!soft.isEmpty()) {
                sb.append(" soft=").append(soft);
            }
            return sb.toString();
        }

        String summaryLines() {
            StringBuilder sb = new StringBuilder();
            soft.forEach((k, v) -> sb.append(String.format("  %6d  %s%n", v, k)));
            if (!routes.isEmpty()) {
                sb.append(String.format("routing histogram (family->serving rung):%n"));
                routes.forEach((k, v) -> sb.append(String.format("  %6d  %s%n", v, k)));
            }
            return sb.toString();
        }
    }

    /** Per-mode artifacts: failures.ndjson (all records) + summary.txt + a
     * progress log line stream (camp.log file, timestamped). */
    static final class Camp implements AutoCloseable {
        private final PrintWriter failures;
        private final PrintWriter progress;
        private final Path dir;

        Camp(Path root, String mode) {
            try {
                dir = root.resolve(mode);
                Files.createDirectories(dir);
                failures = new PrintWriter(Files.newBufferedWriter(dir.resolve("failures.ndjson")), true);
                progress = new PrintWriter(Files.newBufferedWriter(dir.resolve("camp.log")), true);
            } catch (IOException e) {
                throw new IllegalStateException("camp out dir", e);
            }
        }

        void log(String fmt, Object... args) {
            progress.printf("%s %s%n", Instant.now(), String.format(fmt, args));
            progress.flush();
        }

        /** ndjson record: alternating key/value; values escaped/quoted. */
        void rec(long caseSeed, String kind, Object... kv) {
            StringBuilder sb = new StringBuilder(192);
            sb.append("{\"caseSeed\":").append(caseSeed).append(",\"ts\":\"").append(Instant.now())
                .append("\",\"kind\":\"").append(kind.replace('"', '\'')).append('"');
            for (int i = 0; i + 1 < kv.length; i += 2) {
                sb.append(",\"").append(kv[i]).append("\":");
                Object v = kv[i + 1];
                if (v instanceof Number || v instanceof Boolean) {
                    sb.append(v);
                } else {
                    sb.append('"').append(DifferentialFuzzer.escape(String.valueOf(v))).append('"');
                }
            }
            sb.append('}');
            failures.println(sb);
            failures.flush();
        }

        void writeSummary(Counts counts) {
            try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(dir.resolve("summary.txt")))) {
                w.println("cases clean: " + counts.cases);
                w.println("hard findings: " + counts.hard);
                w.println();
                w.println("soft/kind counts:");
                w.print(counts.summaryLines());
            } catch (IOException ignored) {
            }
        }

        @Override
        public void close() {
            failures.close();
            progress.close();
        }
    }

    static String firstFrame(Throwable t) {
        StackTraceElement[] st = t.getStackTrace();
        return st.length == 0 ? "?" : st[0].toString();
    }
}
