package io.github.jemmix.tdfa.fuzz;

import com.google.re2j.Re2jUnicodeProvider;
import io.github.jemmix.tdfa.asm.TdfaAsmBackend;
import io.github.jemmix.tdfa.core.budget.Budgets;
import io.github.jemmix.tdfa.core.budget.PatternTooLargeException;
import io.github.jemmix.tdfa.core.budget.WorkMeter;
import io.github.jemmix.tdfa.core.compile.TDFAPattern;
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
import java.util.Set;
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
 *       untranslatable); inline {@code (?i:...)} + UCC reaches here. A
 *       missed oracle span starting at a non-ASCII char is that family.
 *       JUR-lane probe parses {@code (?U} itself (v1: {@code (?U:...)}
 *       shapes reject by design and count soft; a top-level point-forward
 *       {@code (?U)} translates to the oracle's scoped {@code (?U:...)}
 *       wrap of the remainder). Under CI the jur
 *       answer is computed under both plain CASE_INSENSITIVE and
 *       CASE_INSENSITIVE|UNICODE_CASE; disagreement ("fold ambiguous" —
 *       non-ASCII folds involved, the universes legitimately differ) skips
 *       the case rather than guessing a side; the JUR lane folds ASCII
 *       under bare CI exactly like the oracle, so its probe compares
 *       under plain CASE_INSENSITIVE with no fold skip. Since the
 *       evidence re-base (WBS item 6) the JUR-lane probe compares HARD
 *       against the oracle on everything — the İ/ı full-fold residual is
 *       FIXED (the jur surface's default fold universe is the
 *       java.util.regex reading) and the two remaining residuals are
 *       conscious domain exclusions (SKIP kinds whose set
 *       {@code CampaignSmokeTest} asserts): the family-6 tail slice and
 *       the surrogate-unit-semantics family.</li>
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
 *       anchored-fast"). The SHARED family generator (the soak's
 *       generator-v4 family axis, {@code DifferentialFuzzer.genFam} —
 *       compositional quoted-needle literal, needle+divergence-tail
 *       prefix, set-atom cand-scan over a <= 64-char input, mask-free
 *       anchored-fast shapes; the old fixed cand/fast pattern pools are
 *       retired) is checked against the re2j oracle on both engines, and
 *       the VM strategy trace records whether the family's target rung
 *       actually served the call (LITERAL/PREFIX/CAND_SCAN on find(),
 *       ANCHORED_FAST on matches()) — gate misses are coverage findings
 *       (the detector failed to fire), recorded with the routing
 *       histogram.</li>
 *   <li><b>{@code budget}</b> — budget-exhaustion paths with shrunk budget
 *       knobs (TODO: "Fuzz budget-exhaustion paths with shrunk budget
 *       knobs"). Each case (the soak generator, both lanes) draws one
 *       scenario from a shrunk-knob ladder across ALL THREE {@code -D
 *       tdfa.budget.*} properties — compute ticks shrunk 40&times;/400&times;
 *       (the 2026-10-03 R2/R3 classes), compile RAM shrunk to 2 MiB/512 KiB,
 *       runtime RAM shrunk to 1 MiB/256 KiB, and a combined shrunk draw —
 *       compiles BOTH engines under it, and holds the documented contract:
 *       every rejection is the typed clean "pattern too large"
 *       {@code PatternTooLargeException} naming the knob that tripped,
 *       deterministic across a recompile, both tiers agreeing; every
 *       scoped ACCEPT answers the five-probe protocol identical to the
 *       lane's live oracle. Rejections get a raised-knob replay (sampled
 *       1-in-2, wall-guarded): a library-budget accept must answer the
 *       protocol byte-identical to the oracle, and a still-rejecting shape
 *       gets one family-matched raise (2&times; the tripped memory knob)
 *       before the documented genuinely-too-large classification. The knob
 *       properties are JVM-global, so the mode runs STANDALONE
 *       ({@code -Pcamp.mode=budget}) — never inside {@code all}'s parallel
 *       mode threads — and a prop-leak tripwire asserts the knobs restore
 *       to the entry baseline after every case (the 2026-10-24 draft-
 *       harness leak lesson, now permanent).</li>
 * </ul>
 *
 * <p>Run via the {@code camp} Gradle task (a soak, not a unit test —
 * {@code CampaignSmokeTest} gates the harness in CI):
 * <pre>
 *   ./gradlew :tests:parity:re2j:camp                       # the four parallel modes, 600 s each
 *   ./gradlew :tests:parity:re2j:camp -Pcamp.mode=budget    # budget-exhaustion (standalone only)
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

    /** The pattern shape of the GroupCurly stale-iteration write (fuzz
     *  campaign 2026-10-24, finding 3): a greedy {@code *}/{@code +}/
     *  {@code {n,m}} quantifier directly on a group — capturing or not —
     *  whose body contains another, UNQUANTIFIED capture (the
     *  innermost-close lookahead). That is the body java.util.regex
     *  compiles as {@code GroupCurly} with the inner capture's
     *  {@code GroupTail} inside the atom, where its write survives the
     *  backoff unwound. The #87 sticky shapes (the inner capture itself
     *  quantified, {@code ((a)*b)*}) deliberately do NOT match: the
     *  sticky split made those hard evidence. Deeper nestings
     *  ({@code (((x)))*x}, {@code (?:(?:ab)(9))*x}) also miss — the skip
     *  stays conservative, they compare hard. */
    static final java.util.regex.Pattern STALE_ITERATION_SHAPE =
        java.util.regex.Pattern.compile("\\((?:\\?:|\\?<\\w+>)?[^()]*\\([^()]*\\)(?![*+?{])[^()]*\\)[*+{]");

    // ---- families mode: the fact-gated family generator (shared with the soak) ----

    static FamCase genFamilies(long caseSeed) {
        SplittableRandom r = new SplittableRandom(caseSeed ^ 0xFA17L);
        DifferentialFuzzer.FamPattern fam = DifferentialFuzzer.genFam(r);
        // Embed transform roll across the whole index spectrum (the soak's
        // per-index contract, drawn here since the campaign is per-case),
        // then the CI roll — family shapes are ASCII-only, so CI is
        // oracle-safe and exercises the documented gate-miss fall-through.
        String input = DifferentialFuzzer.famInput(r, fam, r.nextInt(DifferentialFuzzer.BATCH_K));
        boolean ci = r.nextInt(4) == 0;
        int flags = ci ? DifferentialFuzzer.FLAG_CI : 0;
        return new FamCase(fam.family(), fam.pattern(), input, flags, "anchored-fast".equals(fam.family()));
    }

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
                Thread t =
                    new Thread(() -> box.addAndGet(runMode(e.getKey(), e.getValue(), seconds, maxCases, out).hard),
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
            hard = runMode(mode, seed, seconds, maxCases, out).hard;
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
            case "budget" -> replayBudget(caseSeed);
            default -> throw new IllegalArgumentException("unknown camp.mode " + mode);
        }
    }

    /** Scoped compile budget, same contract as the soak: budget monsters
     *  reject in seconds instead of burning the campaign. Returns the
     *  run's {@link Counts} — the smoke gate reads the soft-kind set to
     *  assert the enumerated domain boundary. */
    static Counts runMode(String mode, long seed, long seconds, long maxCases, Path outRoot) {
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

    private static Counts runModeScoped(String mode, long seed, long seconds, long maxCases, Path outRoot) {
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
                        case "budget" -> budgetCase(caseSeed, camp, counts);
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
        return counts;
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
            // re2j-surface lane, which reads (?U:...) as ungreedy;
            // java reads it as UNICODE_CHARACTER_CLASS — untranslatable.
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
     *  WBS: the campaign probes BOTH lanes — the JUR lane is the facade
     *  default since the flip, the RE2 lane the re2j surface. The JUR
     *  lane (core tier, {@code Semantics.of()}) must agree
     *  with the java oracle on every axis the pivots own — terminator set,
     *  EMPTY_LAST_LINE, END_OF_TEXT_ONLY (the lineterm/caretEol/dollarEol
     *  families), the fold universe (bare CI folds ASCII in both, so the
     *  oracle compares under plain CASE_INSENSITIVE; full-fold lanes —
     *  (?u)/(?U)/UCC — fold the java.util.regex reading incl. the merged
     *  İ/ı orbit since the evidence re-base, so those compare HARD too)
     *  and the (?U) reading (SKIP_UNGREEDY retired: (?U:...)/nested
     *  spellings reject by design, counted soft at the compile; a
     *  top-level point-forward (?U) translates to the oracle's scoped
     *  (?U:...) wrap of the remainder) — and since
     *  family 6 landed (WBS 4b suppression + 4c surfacing), the GROUP
     *  clauses compare HARD too — both protocols carry them, no more
     *  skeleton stripping. The evidence-re-base residuals are conscious
     *  domain exclusions (SKIP kinds, asserted by CampaignSmokeTest):
     *  the family-6 TAIL slice and the surrogate-unit-semantics family —
     *  see the reclassification block inside. */
    static void jurLaneCase(long caseSeed, Camp camp, Counts counts) {
        JurCase c = genJur(caseSeed);
        // SKIP_UNGREEDY retired (fold + U bits): the JUR lane parses (?U
        // itself. A top-level point-forward (?U) — leading or mid-pattern —
        // is the scoped fold upgrade; the oracle's spelling wraps the
        // remainder in java's scoped (?U:...) (inline flags extend to the
        // end of the enclosing group, so prefix + (?U:remainder:) is the
        // same reading — non-capturing, group numbering unchanged);
        // (?U:...)/nested forms reject at the compile and are counted
        // there.
        // JUR-lane compile: same flag→inline-prefix mapping as the facade,
        // Semantics.of() — the facade default since the flip.
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
            return; // other compile parity is the RE2-lane probe's question
            // (the v1 (?U) top-level-only limit is retired: scoped
            // spellings compile under the JUR reading and compare below)
        } catch (PatternTooLargeException | StackOverflowError e) {
            counts.soft("JURLANE_BUDGET");
            return;
        } catch (RuntimeException e) {
            // The evidence-re-base flip: a non-budget RuntimeException at
            // the lane compile is an engine/harness BUG, not a budget
            // rejection — the crash class the old catch-all soft used to
            // hide (the cutOverrideByState AIOOBE lived here until the
            // jur-lane matrix surfaced it).
            counts.hard("JURLANE_COMPILE_EXCEPTION");
            camp.rec(caseSeed, "JURLANE_COMPILE_EXCEPTION", "pattern", c.pattern(), "input", c.input(), "flags",
                c.tdfaFlags(), "detail", e + " @ " + firstFrame(e));
            camp.log("HARD JURLANE_COMPILE_EXCEPTION seed=%d pat=%s%n  %s", caseSeed,
                DifferentialFuzzer.escape(c.pattern()), e);
            return;
        }
        String jurLaneVm;
        String jurLaneAsm;
        try {
            jurLaneVm = engineProtocol(new TdfaRunner(find, 1 << 20), c.input());
            jurLaneAsm = engineProtocol(TdfaAsmBackend.generate(find, 1 << 20), c.input());
        } catch (RuntimeException | StackOverflowError e) {
            // The compile catch-all's twin, flipped hard by the same
            // re-base philosophy: a match-time engine exception is an
            // engine bug, not a budget rejection — the engine is
            // non-recursive, so StackOverflowError too. The oracle-side
            // JUR_ORACLE_THREW stays soft (that is the oracle bombing,
            // which the generation guard mostly prevents).
            counts.hard("JURLANE_EXCEPTION");
            camp.rec(caseSeed, "JURLANE_EXCEPTION", "pattern", c.pattern(), "input", c.input(), "flags", c.tdfaFlags(),
                "detail", e + " @ " + firstFrame(e));
            camp.log("HARD JURLANE_EXCEPTION seed=%d pat=%s in=%s%n  %s", caseSeed,
                DifferentialFuzzer.escape(c.pattern()), DifferentialFuzzer.escape(c.input()), e);
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
        // oracle: java.util.regex under the lane's reading — the u/U
        // letters swap to java's spelling (engine (?U) is java's (?u),
        // engine (?u) is java's (?U)) in every spelling, scoped
        // (?U:...) included since v2; shapes the lane rejects never
        // reach here.
        String oraclePattern = DifferentialFuzzer.jurOraclePattern(c.pattern());
        int jurFlags = c.jurFlags();
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
        // Full-protocol comparison, GROUP CLAUSES INCLUDED (WBS 4d: the
        // family-6 sub-families landed — 4b suppresses the dissolved
        // pairs, 4c surfaces the cut final iterations — so group
        // participation is hard evidence, not a stripped skeleton).
        if (jur.equals(jurLaneVm)) {
            counts.ok();
            return;
        }
        // The evidence re-base reclassifications (WBS item 6: "fixed or
        // consciously reclassified, none left soft by default"). The
        // residual below is a DOCUMENTED domain exclusion — a SKIP kind,
        // not a KNOWN divergence: a skip means "this shape is outside the
        // compared domain", a known-family pass would swallow divergence.
        // The skip set is asserted (CampaignSmokeTest pins the soft-kind
        // allowlist), so a new soft kind cannot appear silently.
        //
        // (1, retired) The family-6 TAIL residual — an unbounded greedy
        // quantified capture followed by a consuming tail — was this
        // skip's family: the unbounded zero-width-exit split (finding 2's
        // mirror) now carries the exiting iteration's writes on a normal
        // ε-edge at the loop's REST position, so `(a*)*b` on "aab"
        // compares hard and the skip retired.
        //
        // (1c) java's restart-tag leak: Matcher.search does NOT reset group
        // writes between a find()'s failed restart attempts — a group span
        // recorded during a FAILED start (here \A's ε iteration at 0)
        // survives into the accepted match's protocol, landing OUTSIDE the
        // overall match span (g1 0..0 inside a 2..3 match — no legitimate
        // write can produce that; our lane resets per restart, the clean
        // reading, and java's is arguably a bug its docs disown). The leak
        // signature is exact: skeleton-equal + an oracle group span strictly
        // outside the reported match. DECIDED (excluded, not reproduced):
        // the leaked state is a backtracker artifact — atomic spans
        // committed at GroupTail/GroupCurly points a determinized walk
        // cannot mirror without emulating the backtracking itself. Pinned
        // both ways in RestartTagLeakParityTest.
        if (sameSkeleton(jur, jurLaneVm) && leakedRestartSpan(jur, jurLaneVm)) {
            counts.soft("SKIP_RESTART_TAG_LEAK");
            camp.rec(caseSeed, "JURLANE_SKIP (restart-tag-leak)", "pattern", c.pattern(), "input", c.input(), "flags",
                c.tdfaFlags(), "jur", jur, "jurLaneVm", jurLaneVm);
            return;
        }
        // (1d) F3 (campaign 2026-10-24): the GroupCurly stale-iteration
        // write — java's GroupCurly.match0 backoff rewrites ONLY the
        // directly-quantified group's registers; a NESTED capture's
        // GroupTail write (committed once the atom's own continuation
        // succeeds) is never restored, so the DEEPEST greedy iteration's
        // span survives the unwind and reports through the accepted match
        // (the `(X)*tail` in-span participation family, ~180 records; the
        // failed-restart variant of the same write lands here too —
        // `(?:.(9)){0,}\d` on "x9" reports g1 1..2 from the attempt at 0).
        // DECIDED (excluded, not reproduced): the surviving value is a
        // function of the greedy saturation frontier — of a previous find()
        // attempt, for the restart variant — not of the accepted path, so
        // no tagged-NFA edit can express it (#87's split clones write
        // disciplines along taken paths; this value lives on an untaken
        // one). Faithful reproduction needs determinizer cross-config
        // register transfers plus matcher-global carry across find()
        // restarts — backtracker emulation the frozen runner cluster (#98)
        // deleted the surfaces for. The JDK-side node truth: these bodies
        // compile to GroupCurly, never Prolog/Loop — a non-deterministic
        // body restores nested writes through GroupTail's save/restore and
        // never diverges (control in GroupCurlyStaleWriteParityTest).
        // Pinned both ways there.
        if (sameSkeleton(jur, jurLaneVm) && STALE_ITERATION_SHAPE.matcher(c.pattern()).find()
            && staleIterationSpan(jur, jurLaneVm)) {
            counts.soft("SKIP_GROUPCURLY_STALE_WRITE");
            camp.rec(caseSeed, "JURLANE_SKIP (groupcurly-stale-write)", "pattern", c.pattern(), "input", c.input(),
                "flags", c.tdfaFlags(), "jur", jur, "jurLaneVm", jurLaneVm);
            return;
        }
        // (1b, retired) The nested-loop inner-capture quirk — java's
        // GroupCurly backoff rewrites the pair's span on every unwind,
        // outermost frame last, so the FIRST enclosing-loop iteration that
        // matches the capture wins (`((a)*b)*` on "abab" -> g2 0..1).
        // CLOSED by the sticky split (Tnfa.Builder#stickySplit): those
        // shapes compare hard, pinned in NestedQuantifierParityTest.
        // (2, retired) The lone-surrogate family — the JUR lane's unit-level
        // candidate scans vs java's StartS/plain-Start discipline — closed
        // by the pair-aware scan pivot (Parser#hasSupplementaryStarts frozen
        // on the artifact, OR-ed into the runner's scanSkipsPairs): lone
        // surrogates, surrogate-covering classes and the \b/\B wordness
        // twin now compare HARD against the live oracle.
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
                spanEngine(sb, m);
                sb.append(' ');
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

    /** Per-match group clauses in {@link #spanJur}'s exact format — the
     *  engine side of the family-6 comparison (WBS 4d: no more skeleton
     *  stripping; group participation is hard evidence). */
    static void spanEngine(StringBuilder sb, MatchResult m) {
        sb.append(m.start(0)).append("..").append(m.end(0));
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
     *  universe (provider null — the (?u)-parity construction), rejection/
     *  exception tagged like the soak. Compiles the RE2 lane
     *  (the re2j surface): this probe pins the RE2-lineage behavior
     *  against the jur oracle with the soft known families — the facade
     *  default is the jur lane itself since the flip (probed separately
     *  by jurLaneCase). */
    static String runTdfaJdk(JurCase c, boolean vm) {
        // The re2j surface's roster has no UNICODE_CHARACTER_CLASS bit (the
        // lane's class sets are the re2j reading) — spell the jur matrix's
        // UCC draw as the inline (?u) prefix the facade bit used to expand
        // to, and drop the bit before the compile.
        String pattern = (c.tdfaFlags() & UCC) != 0 ? "(?u)" + c.pattern() : c.pattern();
        int flags = c.tdfaFlags() & ~UCC;
        try {
            TDFAPattern p =
                (TDFAPattern) (vm ? io.github.jemmix.tdfa.re2j.Pattern.compile(pattern, flags, TdfaRunner::new, null)
                    : io.github.jemmix.tdfa.re2j.Pattern.compile(pattern, flags, null, null));
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
        List<int[]> ls = spansOf(stripGroups(lane));
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
     * at the M probe; both sides' group clauses are stripped by the
     * caller — the engine protocol carries them since family 6 landed).
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

    /** The restart-tag-leak signature (SKIP_RESTART_TAG_LEAK's predicate):
     *  the two protocols agree on everything but the group clauses, AND the
     *  oracle carries a group span STRICTLY OUTSIDE the reported overall
     *  match — only java's failed-restart write leak can produce that (a
     *  legitimate write is always within the match region). The leaked span
     *  displaces whatever the accepted path wrote or did not: the lane's
     *  own clause is irrelevant — an oracle span outside the oracle's own
     *  match is the leak no matter what our side reports (fuzz-found
     *  variants where BOTH sides carry a span, e.g.
     *  {@code (?:(.).){3,}Z} shapes whose failed earlier starts leave the
     *  loop's last atomic span standing past the accepted match). Checked
     *  on every clause the leak can surface on: the F= probe's first match,
     *  every I= iteration entry, and the R= restart clause — each probe
     *  runs its own failed starts, so each can carry the stale write
     *  (2026-10-24 finding 2: the I/R leaks escaped the F-only read and
     *  landed as RESULT_MISMATCH). */
    static boolean leakedRestartSpan(String oracle, String vm) {
        if (clauseLeaks(firstFindClause(oracle), firstFindClause(vm))) {
            return true;
        }
        List<String> oEntries = iEntries(oracle);
        List<String> vEntries = iEntries(vm);
        for (int i = 0; i < Math.min(oEntries.size(), vEntries.size()); i++) {
            if (clauseLeaks(oEntries.get(i), vEntries.get(i))) {
                return true;
            }
        }
        return clauseLeaks(rClause(oracle), rClause(vm));
    }

    /** The GroupCurly stale-iteration signature
     *  (SKIP_GROUPCURLY_STALE_WRITE's predicate): the protocols agree on
     *  everything but the group clauses, AND some clause — the F probe's
     *  first match, an I entry, or the R restart clause — reports an
     *  oracle group span where the engines report none, the span INSIDE
     *  the oracle's own overall match (the strictly-outside twin is
     *  {@link #leakedRestartSpan}, checked first; the pattern shape gate
     *  is {@link #STALE_ITERATION_SHAPE}). Only java's never-restored
     *  {@code GroupTail} write can leave a span the accepted path never
     *  reported; the shape gate keeps plain missing-write bugs out. */
    static boolean staleIterationSpan(String oracle, String vm) {
        if (clauseStale(firstFindClause(oracle), firstFindClause(vm))) {
            return true;
        }
        List<String> oEntries = iEntries(oracle);
        List<String> vEntries = iEntries(vm);
        for (int i = 0; i < Math.min(oEntries.size(), vEntries.size()); i++) {
            if (clauseStale(oEntries.get(i), vEntries.get(i))) {
                return true;
            }
        }
        return clauseStale(rClause(oracle), rClause(vm));
    }

    /** One bare span clause per side, group counts agreeing: an oracle
     *  group span the engine reports as none, within the oracle's own
     *  overall span, is the discarded iteration's write. */
    private static boolean clauseStale(String oClause, String vClause) {
        if (oClause == null || vClause == null) {
            return false;
        }
        int op = oClause.indexOf('(');
        int vp = vClause.indexOf('(');
        if (op < 0 || vp < 0) {
            return false; // a groupless clause cannot carry the signature
        }
        int[] overall = spanOf(oClause.substring(0, op).trim());
        if (overall == null) {
            return false;
        }
        String[] oGroups = oClause.substring(op + 1, oClause.lastIndexOf(')')).split(" ");
        String[] vGroups = vClause.substring(vp + 1, vClause.lastIndexOf(')')).split(" ");
        if (oGroups.length != vGroups.length) {
            return false;
        }
        for (int i = 0; i < oGroups.length; i++) {
            int[] span = spanOf(oGroups[i]);
            // a span the engine does not report, inside the reported match:
            // the stale iteration write
            if (span != null && "-".equals(vGroups[i]) && span[0] >= overall[0] && span[1] <= overall[1]) {
                return true;
            }
        }
        return false;
    }

    /** One bare span clause per side ({@code s..e (g1 g2 ...)}), group
     *  counts agreeing: an oracle group span strictly outside the oracle's
     *  own overall span is the leak. */
    private static boolean clauseLeaks(String oClause, String vClause) {
        if (oClause == null || vClause == null) {
            return false;
        }
        int op = oClause.indexOf('(');
        int vp = vClause.indexOf('(');
        if (op < 0 || vp < 0) {
            return false; // a groupless clause cannot leak
        }
        int[] overall = spanOf(oClause.substring(0, op).trim());
        if (overall == null) {
            return false;
        }
        String[] oGroups = oClause.substring(op + 1, oClause.lastIndexOf(')')).split(" ");
        String[] vGroups = vClause.substring(vp + 1, vClause.lastIndexOf(')')).split(" ");
        if (oGroups.length != vGroups.length) {
            return false;
        }
        for (String oGroup : oGroups) {
            int[] span = spanOf(oGroup);
            // strictly outside the reported match: the leak signature
            if (span != null && (span[1] <= overall[0] || span[0] >= overall[1])) {
                return true;
            }
        }
        return false;
    }

    /** "F=true 2..3 (0..0 2..3) I=[..." -> "2..3 (0..0 2..3)", or null when
     *  the first find failed. */
    private static String firstFindClause(String proto) {
        if (!proto.startsWith("F=true ")) {
            return null;
        }
        int end = proto.indexOf(" I=[");
        return (end < 0 ? proto : proto.substring(0, end)).substring("F=true ".length());
    }

    /** The I= section's entries, in order. Entries are concatenated with no
     *  separator ("2..3 (0..0 2..3)6..7 (- 6..7)"), but each consumes its
     *  overall span plus its own group clause, so the tokenizer never
     *  mistakes a group span for an entry. */
    private static List<String> iEntries(String proto) {
        List<String> out = new ArrayList<>();
        int open = proto.indexOf("I=[");
        if (open < 0) {
            return out;
        }
        int close = proto.indexOf(']', open);
        if (close < 0) {
            return out;
        }
        java.util.regex.Matcher m =
            java.util.regex.Pattern.compile("\\d+\\.\\.\\d+( \\([^()]*\\))?").matcher(proto.substring(open + 3, close));
        while (m.find()) {
            out.add(m.group());
        }
        return out;
    }

    /** The R= restart clause ("R=true 6..7 (- 6..7)" -> "6..7 (- 6..7)");
     *  null for {@code R=false} (nothing matched, nothing to leak). */
    private static String rClause(String proto) {
        int at = proto.indexOf(" R=true ");
        return at < 0 ? null : proto.substring(at + " R=true ".length());
    }

    private static int[] spanOf(String s) {
        int d = s.indexOf("..");
        return d < 0 ? null : new int[]{Integer.parseInt(s.substring(0, d)), Integer.parseInt(s.substring(d + 2))};
    }

    // ================ mode: stretch — oracle + ASM on stretched inputs ================

    static void stretchCase(long caseSeed, Camp camp, Counts counts) {
        // RE2J-pinned generation: this mode's oracle is re2j (the soak's
        // stretched-input probes), so the flags are the re2j-lane draw
        // (LONGEST, never UCC) regardless of the batch's lane draw.
        DifferentialFuzzer.Case c = DifferentialFuzzer.generate(caseSeed, DifferentialFuzzer.Lane.RE2J);
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
    static String findIterate(TDFAPattern p, CharSequence in) {
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

    /** One family case: the SHARED generator (DifferentialFuzzer.genFam —
     *  the soak's generator-v4 family axis draws the same shapes per batch;
     *  the campaign mode is the deep-dive twin: every case family-biased,
     *  routing traced, per-family histogram + gate-miss coverage counts).
     *  See {@link #genFamilies}. */
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
        // Both-reject parity (oracle rejects the shape, both engines reject
        // it too): clean compile parity, but there is NO artifact to trace —
        // routing is a property of a compiled runner. Counted ok and done;
        // the family contract is valid syntax (see famQuant/famBody), so
        // this branch is rare and worth its own breadcrumb when it recurs.
        if (pr.vm == null || pr.vmTag != null || pr.oracle == null) {
            counts.ok();
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

    // ================ mode: budget — exhaustion paths under shrunk knobs ================

    /** One shrunk-knob scenario: which of the three {@code tdfa.budget.*}
     *  properties is shrunk, and to what (null = leave at the entry
     *  baseline). Pure function of the caseSeed's own stream. */
    record Scenario(String name, Long compute, Long memory, Long runtime) {

        void apply() {
            if (compute != null) {
                System.setProperty(Budgets.COMPILE_COMPUTE_PROP, String.valueOf(compute));
            }
            if (memory != null) {
                System.setProperty(Budgets.COMPILE_MEMORY_PROP, String.valueOf(memory));
            }
            if (runtime != null) {
                System.setProperty(Budgets.RUNTIME_MEMORY_PROP, String.valueOf(runtime));
            }
        }
    }

    /** The shrunk-knob ladder (see the class doc): the two compute classes
     *  the 2026-10-03 R2/R3 runs pinned (40x/400x below the fuzz scope),
     *  two compile-RAM shrinks, two runtime-RAM shrinks, one combined draw,
     *  and the plain 8 M fuzz scope (the soak's standing class — the
     *  baseline the others are drawn against). */
    static Scenario genScenario(long caseSeed) {
        return switch (new SplittableRandom(caseSeed ^ 0xB0D9L).nextInt(8)) {
            case 0 -> new Scenario("scoped8m", DifferentialFuzzer.fuzzWorkBudget(), null, null);
            case 1 -> new Scenario("compute40x", 200_000L, null, null);
            case 2 -> new Scenario("compute400x", 20_000L, null, null);
            case 3 -> new Scenario("mem2m", null, 2L << 20, null);
            case 4 -> new Scenario("mem512k", null, 512L << 10, null);
            case 5 -> new Scenario("runtime1m", null, null, 1L << 20);
            case 6 -> new Scenario("runtime256k", null, null, 256L << 10);
            default -> new Scenario("combined200k", 200_000L, 512L << 10, 1L << 20);
        };
    }

    /** Snapshot one budget property (null = unset). */
    static Long knob(String prop) {
        String v = System.getProperty(prop);
        return v == null ? null : Long.valueOf(v);
    }

    /** Read the three knobs for a record's "seen" line. */
    static String readKnobs() {
        return knob(Budgets.COMPILE_COMPUTE_PROP) + "/" + knob(Budgets.COMPILE_MEMORY_PROP) + "/"
            + knob(Budgets.RUNTIME_MEMORY_PROP);
    }

    /** Restore the three knobs to the given snapshot; false when the values
     *  seen on entry differ from the snapshot (the leak tripwire's
     *  comparison — a scenario block must always leave the baseline in
     *  place when it finishes). */
    static boolean restoreKnobs(Long c, Long m, Long r) {
        boolean clean = restoreKnob(Budgets.COMPILE_COMPUTE_PROP, c);
        clean &= restoreKnob(Budgets.COMPILE_MEMORY_PROP, m);
        clean &= restoreKnob(Budgets.RUNTIME_MEMORY_PROP, r);
        return clean;
    }

    private static boolean restoreKnob(String prop, Long v) {
        long now = Long.parseLong(System.getProperty(prop, "-1"));
        if (v != null) {
            System.setProperty(prop, String.valueOf(v));
        } else {
            System.clearProperty(prop);
        }
        return now == (v == null ? -1 : v);
    }

    static void budgetCase(long caseSeed, Camp camp, Counts counts) {
        // The lane is the case's own batch draw (replay-stable through
        // generate); jur oracle-bomb batches are skipped whole — their
        // accepts would need the bomb oracle.
        DifferentialFuzzer.Case c = DifferentialFuzzer.generate(caseSeed);
        if (c.lane() == DifferentialFuzzer.Lane.JUR && DifferentialFuzzer.jurOracleBomb(c.pattern())) {
            counts.soft("BUDGET_GUARD");
            return;
        }
        Scenario sc = genScenario(caseSeed);
        Long baseC = knob(Budgets.COMPILE_COMPUTE_PROP);
        Long baseM = knob(Budgets.COMPILE_MEMORY_PROP);
        Long baseR = knob(Budgets.RUNTIME_MEMORY_PROP);
        boolean ok;
        try {
            ok = budgetCaseScoped(caseSeed, c, sc, camp, counts, baseC, baseM, baseR);
        } finally {
            // The prop-leak tripwire: every phase of the case must leave the
            // knobs at the mode's entry baseline (the 2026-10-24
            // draft-harness leak lesson, now a permanent harness invariant).
            String seen = readKnobs();
            if (!restoreKnobs(baseC, baseM, baseR)) {
                counts.hard("BUDGET_PROP_LEAK");
                camp.rec(caseSeed, "BUDGET_PROP_LEAK", "pattern", c.pattern(), "scenario", sc.name(), "baseline",
                    baseC + "/" + baseM + "/" + baseR, "seen", seen);
            }
        }
        if (!ok) {
            return;
        }
        counts.route("knob:" + sc.name());
    }

    /** The case body: the scenario scope first (compile + protocol +
     *  rejection contract), then — for sampled rejections — the raised-knob
     *  replay at library budgets. Every phase restores the baseline before
     *  returning; false = a hard finding was recorded. */
    private static boolean budgetCaseScoped(long caseSeed, DifferentialFuzzer.Case c, Scenario sc, Camp camp,
        Counts counts, Long baseC, Long baseM, Long baseR) {
        long t0 = System.nanoTime();
        String oracle;
        DifferentialFuzzer.Prepared pr;
        try {
            sc.apply();
            pr = DifferentialFuzzer.prepare(c.pattern(), c.flags(), c.lane());
            oracle = oracleProtocol(pr, c);
        } catch (RuntimeException | StackOverflowError e) {
            counts.hard("BUDGET_HARNESS_EXCEPTION");
            camp.rec(caseSeed, "BUDGET_HARNESS_EXCEPTION", "pattern", c.pattern(), "scenario", sc.name(), "detail",
                e + " @ " + firstFrame(e));
            return false;
        } finally {
            restoreKnobs(baseC, baseM, baseR);
        }
        if (pr.asmTag != null != (pr.vmTag != null)) {
            counts.hard("BUDGET_TIER_SPLIT");
            camp.rec(caseSeed, "BUDGET_TIER_SPLIT", "pattern", c.pattern(), "scenario", sc.name(), "asm", pr.asmTag,
                "vm", pr.vmTag);
            return false;
        }
        if (pr.asmTag == null) {
            return budgetAcceptPath(caseSeed, c, sc, pr, oracle, camp, counts, baseC, baseM, baseR);
        }
        return budgetRejectPath(caseSeed, c, sc, pr, oracle, camp, counts, t0, baseC, baseM, baseR);
    }

    /** Scoped ACCEPT: the whole five-probe protocol must equal the lane's
     *  oracle answer (a shrunk budget that fits must not change semantics).
     *  A protocol string carrying "pattern too large" is the documented
     *  deferred-anchored class — soft. */
    private static boolean budgetAcceptPath(long caseSeed, DifferentialFuzzer.Case c, Scenario sc,
        DifferentialFuzzer.Prepared pr, String oracle, Camp camp, Counts counts, Long baseC, Long baseM, Long baseR) {
        String asm, vm;
        try {
            sc.apply(); // same runtime-RAM scope the artifact compiled under
            asm = guarded(() -> DifferentialFuzzer.compute(pr.asm, c.input()));
            vm = guarded(() -> DifferentialFuzzer.compute(pr.vm, c.input()));
        } finally {
            restoreKnobs(baseC, baseM, baseR);
        }
        if (asm == null || vm == null) {
            counts.hard("BUDGET_EXCEPTION");
            camp.rec(caseSeed, "BUDGET_EXCEPTION", "pattern", c.pattern(), "scenario", sc.name(), "asm", asm, "vm", vm);
            return false;
        }
        if (asm.contains("pattern too large") || vm.contains("pattern too large")) {
            counts.soft("BUDGET_DEFERRED");
            camp.rec(caseSeed, "BUDGET_DEFERRED", "pattern", c.pattern(), "scenario", sc.name(), "asm", asm, "vm", vm);
            return true;
        }
        if (oracle.startsWith("<")) {
            if (!asm.startsWith("<reject") || !vm.startsWith("<reject")) {
                counts.hard("BUDGET_COMPILE_PARITY (oracle rejects, tdfa accepts)");
                camp.rec(caseSeed, "BUDGET_COMPILE_PARITY", "pattern", c.pattern(), "scenario", sc.name(), "oracle",
                    oracle, "asm", asm, "vm", vm);
                return false;
            }
            counts.ok();
            return true;
        }
        if (!asm.equals(oracle) || !vm.equals(oracle)) {
            counts.hard("BUDGET_MISMATCH (scoped accept)");
            camp.rec(caseSeed, "BUDGET_MISMATCH", "pattern", c.pattern(), "input", c.input(), "flags", c.flags(),
                "scenario", sc.name(), "oracle", oracle, "asm", asm, "vm", vm);
            camp.log("HARD BUDGET_MISMATCH seed=%d scenario=%s pat=%s", caseSeed, sc.name(),
                DifferentialFuzzer.escape(c.pattern()));
            return false;
        }
        counts.ok();
        return true;
    }

    /** Scoped REJECTION: the typed/deterministic/clean contract, then the
     *  sampled raised-knob replay (library budgets, then one family-matched
     *  memory raise; a replayed accept must answer the protocol identical
     *  to the oracle). */
    private static boolean budgetRejectPath(long caseSeed, DifferentialFuzzer.Case c, Scenario sc,
        DifferentialFuzzer.Prepared pr, String oracle, Camp camp, Counts counts, long t0, Long baseC, Long baseM,
        Long baseR) {
        if (!pr.asmTag.startsWith("<reject:pattern too large")) {
            // A parse/other compile rejection, not a budget class: the soak's
            // compile-parity family. Both tiers rejecting what the oracle
            // also rejects is clean parity; an oracle accept is a finding.
            if (oracle.startsWith("<")) {
                counts.ok();
                return true;
            }
            counts.hard("BUDGET_COMPILE_PARITY (tdfa rejects, oracle accepts)");
            camp.rec(caseSeed, "BUDGET_COMPILE_PARITY", "pattern", c.pattern(), "scenario", sc.name(), "oracle", oracle,
                "asm", pr.asmTag, "vm", pr.vmTag);
            return false;
        }
        for (String tag : new String[]{pr.asmTag, pr.vmTag}) {
            if (!tag.startsWith("<reject:pattern too large")) {
                counts.hard("BUDGET_NOT_TYPED");
                camp.rec(caseSeed, "BUDGET_NOT_TYPED", "pattern", c.pattern(), "scenario", sc.name(), "tag", tag);
                return false;
            }
        }
        try {
            sc.apply();
            DifferentialFuzzer.Prepared again = DifferentialFuzzer.prepare(c.pattern(), c.flags(), c.lane());
            if (!pr.asmTag.equals(again.asmTag) || !pr.vmTag.equals(again.vmTag)) {
                counts.hard("BUDGET_NONDETERMINISTIC");
                camp.rec(caseSeed, "BUDGET_NONDETERMINISTIC", "pattern", c.pattern(), "scenario", sc.name(), "asm1",
                    pr.asmTag, "asm2", again.asmTag, "vm1", pr.vmTag, "vm2", again.vmTag);
                return false;
            }
        } catch (RuntimeException e) {
            counts.hard("BUDGET_HARNESS_EXCEPTION");
            camp.rec(caseSeed, "BUDGET_HARNESS_EXCEPTION (determinism recompile)", "pattern", c.pattern(), "scenario",
                sc.name(), "detail", e + " @ " + firstFrame(e));
            return false;
        } finally {
            restoreKnobs(baseC, baseM, baseR);
        }
        counts.route("reject:" + sc.name());
        // Raised-knob replay, sampled 1-in-2 and wall-guarded: a case that
        // already burned half the slow budget skips it (the 500 M monster
        // compiles live here — SLOW is informational, never a failure).
        if (Math.floorMod(caseSeed, 2) != 0 || (System.nanoTime() - t0) / 1_000_000 > CASE_SLOW_MS / 2) {
            counts.route("noreplay");
            return true;
        }
        try {
            // Library budgets: all three knobs at their defaults.
            System.clearProperty(Budgets.COMPILE_COMPUTE_PROP);
            System.clearProperty(Budgets.COMPILE_MEMORY_PROP);
            System.clearProperty(Budgets.RUNTIME_MEMORY_PROP);
            DifferentialFuzzer.Prepared raised = DifferentialFuzzer.prepare(c.pattern(), c.flags(), c.lane());
            if (raised.asmTag == null) {
                return replayCompare(caseSeed, c, sc, raised, "raise:library:accept", camp, counts, t0);
            }
            String raisedTag = raised.asmTag;
            // Compute-class rejections at 500 M are the documented burn-out
            // monsters — the 500 M compile itself is the evidence, no
            // further raise. Memory-class get one family-matched 2x raise.
            String raiseProp = raisedTag.contains(Budgets.COMPILE_MEMORY_PROP) ? Budgets.COMPILE_MEMORY_PROP
                : raisedTag.contains(Budgets.RUNTIME_MEMORY_PROP) ? Budgets.RUNTIME_MEMORY_PROP : null;
            if (raiseProp == null) {
                counts.soft("TOO_LARGE_ANY");
                camp.rec(caseSeed, "TOO_LARGE_ANY (compute burn-out at library budget)", "pattern", c.pattern(),
                    "scenario", sc.name(), "tag", raisedTag);
                return true;
            }
            System.setProperty(raiseProp, String.valueOf(2 * (raiseProp.equals(Budgets.COMPILE_MEMORY_PROP)
                ? Budgets.DEFAULT_COMPILE_MEMORY : Budgets.DEFAULT_RUNTIME_MEMORY)));
            try {
                DifferentialFuzzer.Prepared raised2 = DifferentialFuzzer.prepare(c.pattern(), c.flags(), c.lane());
                if (raised2.asmTag != null) {
                    counts.soft("TOO_LARGE_ANY");
                    camp.rec(caseSeed, "TOO_LARGE_ANY (rejects at any raised budget)", "pattern", c.pattern(),
                        "scenario", sc.name(), "tag1", raisedTag, "tag2", raised2.asmTag);
                    return true;
                }
                return replayCompare(caseSeed, c, sc, raised2,
                    "raise:2x" + (raiseProp.endsWith("compile.memory") ? "compile" : "runtime") + ":accept", camp,
                    counts, t0);
            } finally {
                System.clearProperty(raiseProp);
            }
        } finally {
            restoreKnobs(baseC, baseM, baseR);
        }
    }

    /** A raised-knob accept must answer the five-probe protocol identical to
     *  the oracle; the wall guard classifies the slow (monster-compile)
     *  replays without failing them. */
    private static boolean replayCompare(long caseSeed, DifferentialFuzzer.Case c, Scenario sc,
        DifferentialFuzzer.Prepared raised, String route, Camp camp, Counts counts, long t0) {
        String o = oracleProtocol(raised, c);
        String asm = guarded(() -> DifferentialFuzzer.compute(raised.asm, c.input()));
        String vm = guarded(() -> DifferentialFuzzer.compute(raised.vm, c.input()));
        if (asm == null || vm == null || o.startsWith("<") || !asm.equals(o) || !vm.equals(o)) {
            counts.hard("BUDGET_MISMATCH (raised-knob replay)");
            camp.rec(caseSeed, "BUDGET_RAISED_MISMATCH", "pattern", c.pattern(), "input", c.input(), "flags", c.flags(),
                "scenario", sc.name(), "oracle", o, "asm", asm, "vm", vm);
            camp.log("HARD BUDGET_RAISED_MISMATCH seed=%d scenario=%s pat=%s", caseSeed, sc.name(),
                DifferentialFuzzer.escape(c.pattern()));
            return false;
        }
        if ((System.nanoTime() - t0) / 1_000_000 > CASE_SLOW_MS) {
            counts.soft("BUDGET_SLOW");
        }
        counts.route(route);
        return true;
    }

    /** The lane's oracle five-probe answer (rejections as {@code <reject>},
     *  throws as {@code <exception:…>} — never null). */
    static String oracleProtocol(DifferentialFuzzer.Prepared pr, DifferentialFuzzer.Case c) {
        if (pr.lane == DifferentialFuzzer.Lane.JUR) {
            if (pr.jurOracle == null) {
                return pr.oracleTag;
            }
            String o = guarded(() -> DifferentialFuzzer.compute(pr.jurOracle, c.input()));
            return o != null ? o : "<exception:jurOracle>";
        }
        if (pr.oracle == null) {
            return pr.oracleTag;
        }
        String o = guarded(() -> DifferentialFuzzer.compute(pr.oracle, c.input()));
        return o != null ? o : "<exception:oracle>";
    }

    static void replayBudget(long caseSeed) {
        DifferentialFuzzer.Case c = DifferentialFuzzer.generate(caseSeed);
        Scenario sc = genScenario(caseSeed);
        System.out.println("pattern:  " + DifferentialFuzzer.escape(c.pattern()));
        System.out.println("input:    " + DifferentialFuzzer.escape(c.input()));
        System.out.println("flags:    " + c.flags() + "  lane: " + c.lane() + "  scenario: " + sc.name() + " [compute="
            + sc.compute() + " memory=" + sc.memory() + " runtime=" + sc.runtime() + "]");
        Long baseC = knob(Budgets.COMPILE_COMPUTE_PROP);
        Long baseM = knob(Budgets.COMPILE_MEMORY_PROP);
        Long baseR = knob(Budgets.RUNTIME_MEMORY_PROP);
        try {
            sc.apply();
            DifferentialFuzzer.Prepared scoped = DifferentialFuzzer.prepare(c.pattern(), c.flags(), c.lane());
            System.out.println("scoped:   asm=" + scoped.asmTag + "  vm=" + scoped.vmTag);
        } finally {
            restoreKnobs(baseC, baseM, baseR);
        }
        System.clearProperty(Budgets.COMPILE_COMPUTE_PROP);
        System.clearProperty(Budgets.COMPILE_MEMORY_PROP);
        System.clearProperty(Budgets.RUNTIME_MEMORY_PROP);
        try {
            DifferentialFuzzer.Prepared lib = DifferentialFuzzer.prepare(c.pattern(), c.flags(), c.lane());
            System.out.println("library:  asm=" + lib.asmTag + "  vm=" + lib.vmTag);
            System.out.println("oracle:   " + oracleProtocol(lib, c));
        } finally {
            restoreKnobs(baseC, baseM, baseR);
        }
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
            System.out.println("vm:      " + DifferentialFuzzer.compute((TDFAPattern) io.github.jemmix.tdfa.re2j.Pattern
                .compile(c.pattern(), c.tdfaFlags(), TdfaRunner::new, null), c.input()));
        } catch (RuntimeException e) {
            System.out.println("vm:      <reject> " + DifferentialFuzzer.firstLine(e.getMessage()));
        }
        try {
            System.out.println("asm:     " + DifferentialFuzzer.compute(
                (TDFAPattern) io.github.jemmix.tdfa.re2j.Pattern.compile(c.pattern(), c.tdfaFlags(), null, null),
                c.input()));
        } catch (RuntimeException e) {
            System.out.println("asm:     <reject> " + DifferentialFuzzer.firstLine(e.getMessage()));
        }
    }

    static void replayStretch(long caseSeed) {
        DifferentialFuzzer.Case c = DifferentialFuzzer.generate(caseSeed, DifferentialFuzzer.Lane.RE2J);
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
        TDFAPattern vm = (TDFAPattern) io.github.jemmix.tdfa.re2j.Pattern.compile(pattern, 0, TdfaRunner::new,
            Re2jUnicodeProvider.INSTANCE);
        System.out.println("vm(String):      " + findIterate(vm, input));
        System.out.println("pike(wrapper):   " + pikeIterate(sim, new StringBuilder(input)));
        System.out.println("vm(wrapper):     " + findIterate(vm, new StringBuilder(input)));
        System.out.println("asm(wrapper):    " + findIterate(
            (TDFAPattern) io.github.jemmix.tdfa.re2j.Pattern.compile(pattern, 0, null, Re2jUnicodeProvider.INSTANCE),
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

        /** The soft-kind set of the run — asserted against the enumerated
         *  domain boundary by {@code CampaignSmokeTest} (the evidence
         *  re-base flip: no soft kind may appear that is not consciously
         *  enumerated here). */
        Set<String> softKinds() {
            return soft.keySet();
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
                sb.append(String.format("histogram (per-mode: routing/scenario outcomes):%n"));
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
