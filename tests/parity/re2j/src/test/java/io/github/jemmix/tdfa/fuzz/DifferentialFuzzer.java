package io.github.jemmix.tdfa.fuzz;

import com.google.re2j.Re2jUnicodeProvider;
import io.github.jemmix.tdfa.core.budget.PatternTooLargeException;
import io.github.jemmix.tdfa.core.compile.TDFAPattern;
import io.github.jemmix.tdfa.core.dfa.TdfaRunner;
import io.github.jemmix.tdfa.core.parser.PatternSyntaxException;
import io.github.jemmix.tdfa.parity.LayeredComparator;
import io.github.jemmix.tdfa.parity.LayeredComparator.Layer;
import jdk.jfr.Configuration;
import jdk.jfr.Recording;

import java.io.IOException;
import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadMXBean;
import java.nio.CharBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

/**
 * Differential fuzzer: random patterns over the supported grammar, random
 * inputs biased toward the historically painful shapes (supplementary
 * codepoints, lone surrogates, boundaries), compared against a differential
 * oracle through the public surfaces — BOTH engines (ASM generated tier and
 * VM interpreter) per case, under a per-batch random compile-flag matrix
 * BESIDE a per-batch LANE draw (the evidence re-base matrix axis): the
 * <b>re2j lane</b> compiles the tdfa side through the re2j surface against
 * the re2j oracle (CASE_INSENSITIVE / DOTALL / MULTILINE / LONGEST_MATCH —
 * same bit values in both libraries; 40% of batches stay at flags=0 so the
 * unflagged regime keeps steady coverage), the <b>jur lane</b> compiles
 * through the jur facade (the default lane) against the live
 * {@code java.util.regex} oracle with the campaign's flag translation
 * (CASE_INSENSITIVE / DOTALL / MULTILINE / UNICODE_CHARACTER_CLASS — the
 * {@code (?u)} lane; no LONGEST, jur has no equivalent; the u/U letters
 * swap to the oracle's spelling — engine {@code (?U)}/{@code (?u)} are
 * java's {@code (?u)}/{@code (?U)} — in every spelling, the scoped
 * {@code (?U:...)} shapes included since v2). The contract
 * is each oracle's observable behavior: compile-accept/reject parity plus a
 * five-probe span protocol compared for exact equality — first
 * {@code find()} with overall and per-group spans (spans strictly subsume
 * group texts), full {@code find()} iteration on the same matcher
 * (continuation + empty-match advance), {@code matches()}, {@code
 * lookingAt()}, and {@code find(len/2)} restart.
 *
 * <p>On the jur lane the two evidence-re-base residuals are conscious
 * domain exclusions (mirroring {@code CampaignFuzzer.jurLaneCase}; WBS item
 * 6): the family-6 tail slice and the surrogate-unit-semantics family —
 * recorded as {@code JURLANE_SKIP} records and counted, not failed. The
 * İ/ı full-fold residual is FIXED (the jur surface's default fold universe
 * is the java.util.regex reading). Everything else on the lane compares
 * hard: compile parity, group clauses, terminator set, anchors, fold.
 *
 * <p>A soak, not a unit test. Run via the {@code fuzz} Gradle task:
 * <pre>
 *   ./gradlew :tests:parity:re2j:fuzz -Pfuzz.minutes=480 -Pfuzz.seed=1234
 * </pre>
 * Logs land in the out dir (default {@code build/fuzz/}):
 * <ul>
 *   <li>{@code failures.ndjson} — one line per recorded divergence: caseSeed,
 *       escaped pattern/input, oracle and both engines' results, kind. Every
 *       line is independently reproducible via {@code -Dfuzz.one=<caseSeed>}.</li>
 *   <li>{@code summary.txt} — counts, rate, deduped failure signatures.
 *       Rewritten periodically and at exit: killing the run keeps the data.</li>
 *   <li>{@code progress.log} — heartbeat every 15 s.</li>
 * </ul>
 *
 * <p>{@code -Dfuzz.append=true} (Gradle: {@code -Pfuzz.append=true}) appends
 * {@code failures.ndjson}/{@code progress.log} instead of truncating, so a
 * chunked soak ({@code scripts/fuzz-soak.sh}: many short JVMs, so threads
 * leaked by hang watchdogs die with their process) shares one out dir.
 *
 * <p>Known, documented divergences are avoided by construction rather than
 * filtered after the fact: case-insensitive + supplementary/lone-surrogate
 * combinations are not generated (tdfa has no supplementary simple-fold
 * table yet; re2j folds fully — TODO.md tracks the gap), and {@code (?i)}
 * class ranges are generated ASCII-narrow only (re2j's own parser expands
 * case folds over every codepoint in a range — wide {@code (?i)} ranges
 * can hang the ORACLE). Everything else
 * must agree with re2j or it is a finding.
 *
 * <p>On the jur lane the ORACLE itself is a backtracking engine, so one
 * more family is avoided rather than compared: nested-quantifier bombs
 * (a quantified wide atom — {@code .} / {@code \D} / {@code \W} /
 * {@code \S} / a class — textually inside a quantified group in one of
 * the shapes {@code java.util.regex} cannot memoize, e.g.
 * {@code (?:\D+[^\d]*?){2,}?}, {@code (.{0,6}?)*?} or
 * {@code (.*?.*?){2,6}}) are
 * catastrophic-backtracking shapes for {@code java.util.regex}
 * (Pattern$LazyLoop/GroupCurly spins; campaigns 2026-10-16 finding 4 and
 * 2026-10-24 finding 6) but
 * ordinary input for this engine. Those batches are skipped whole and
 * counted as {@code JURLANE_GUARD} records (the re2j lane still runs
 * them — its oracle is linear-time); the predicate is a pure function of
 * the pattern, so replay is lane-stable.
 *
 * <p>Property shrinking (minimal repros — {@link CaseShrinker}): a failing
 * case is delta-debugged down to a minimal same-kind failure. In
 * {@code fuzz.one} triage it is on by default ({@code -Pfuzz.shrink=0}
 * opts out) and prints the shrunk pattern/input plus a turnkey
 * {@code -Pfuzz.replay.*} command line; in the soak it is opt-in
 * ({@code -Pfuzz.shrink=1}) and pre-shrinks every recorded failure record,
 * so {@code failures.ndjson} carries {@code shrunkPattern}/{@code
 * shrunkInput} beside the reproducible {@code caseSeed} fields.
 *
 * <p>Cross-rung differential (on by default, {@code -Pfuzz.crossRung=0}
 * off): after the normal protocol, every case is re-run on the VM engine
 * with each complete-search ladder rung forced via
 * {@link TdfaRunner#setForcedStrategy} — ORIGIN_SIM, TRIGGER, RAW_SCAN,
 * WALK_RESTART. A forced rung must return the identical protocol string to
 * the natural ladder (any rung can serve any find() alone), so every case
 * doubles as a fast-rung-vs-complete-rung oracle the re2j comparison cannot
 * provide. Two findings types: CROSS_RUNG (forced answer differs) and
 * CROSS_ROUTING (forced entry served by unexpected rungs — the trace hook
 * must stay within the forced rung's fall-through family). A deterministic
 * 1-in-N subset also runs the probes on a stretched input (~4.3k chars,
 * whole-string repeats) so the memoized search-DFA path — unreachable on
 * the 0-24-char generator inputs — actually runs; stretched cases compare
 * forced runs against the natural VM run on the same stretched input, and
 * — the stretch family's standing coverage — the natural VM and ASM
 * answers against the re2j oracle on the stretched input (the full
 * three-way; the jur lane keeps the ASM-vs-VM cross-tier leg only — its
 * backtracking oracle is unbounded at stretched lengths, the same reason
 * the campaign's stretch mode RE2J-pins its generation).
 * ASM is not forced (its ladder is frozen at compile time); its coverage
 * comes from the layered vote on the natural protocol — and, on the
 * stretched sample, from the stretched oracle compare above.
 *
 * <p><b>Fact-gated family axis (generator v4).</b> The four fast rungs of
 * the search ladder — LITERAL, PREFIX, CAND_SCAN, ANCHORED_FAST — are gated
 * on compile-time facts of the pattern (a literal needle, a
 * required-literal prefix, a non-accepting start + short input, a mask-free
 * fastPath artifact), and the general grammar hits those facts only by
 * accident. 1-in-8 batches (a fresh stream, a pure function of the batch;
 * non-family batches keep their v3 pattern and inputs byte-identical) draw
 * from a family generator instead — a quoted literal needle with
 * near-miss/doubled/truncated input embeds, a needle+divergence-tail prefix
 * shape, a set-atom cand-scan shape over a <= 64-char input, or a mask-free
 * anchored-fast shape — and run the full per-case protocol (both lanes, the
 * whole flag matrix, cross-rung, stretch, the layer vote) on them like any
 * other case. The family generator is shared with the campaign harness's
 * families mode (the deep-dive rung-routing verifier,
 * {@code CampaignFuzzer.genFamilies}).
 *
 * <p><b>CharSequence wrapper axis.</b> Every ladder rung except GENERIC is
 * keyed on {@code input instanceof String}; any other CharSequence rides
 * the GENERIC rung — a second implementation of the same semantics that
 * the String-path probes never touch. A deterministic 1-in-N sample of
 * cases (pattern+input hash, replay-stable — the stretch probe's keying
 * twin) re-runs BOTH engines' five-probe protocol over three wrappers —
 * StringBuilder, CharBuffer, a plain char[] view — and requires
 * byte-identical answers to the String path (SEQ_PATH_MISMATCH), plus the
 * lane's oracle answering the wrapper identically to its own String run
 * (SEQ_ORACLE_WRAPPER — a harness sanity tripwire). The reference-sim
 * (PikeSim) comparison, the GENERIC routing trace, and the wider wrapper
 * zoo (position-carrying buffers) live in the campaign's seq mode — the
 * deep-dive twin of this axis. {@code -Pfuzz.seq=0} turns the axis off.
 */
public final class DifferentialFuzzer {

    // ---- knobs (system properties; the Gradle task forwards -Pfuzz.*) ----

    static final long CASE_TIMEOUT_MS = Long.getLong("fuzz.caseTimeoutMs", 10_000);

    /** Layered comparator (re2j/sim/vm/asm vote) for failure attribution. */
    private static final LayeredComparator LAYERED = new LayeredComparator(Re2jUnicodeProvider.INSTANCE);

    /** Which re2j is on the classpath — our patched fork (the DEFAULT
     *  oracle, vendor/re2j-jemmix/) or released 1.8
     *  (-Pfuzz.pristineOracle=true)? Probed, not declared: the
     *  lone-low-interior behavior is the discriminator (same probe as
     *  LayeredComparatorTest). Under the patched oracle the lone-surrogate
     *  "known divergence" family is FIXED — any divergence there is a real
     *  finding, so the known-divergence classifier runs only when released. */
    static final boolean RELEASED_ORACLE = releasedOracle();

    /** Iteration cap: bounds a broken empty-match advance (an engine that
     *  never advances yields one span per call forever); 64 ≫ any match
     *  count a ≤30-char input can produce, so a healthy case never clips. */
    static final int MAX_MATCHES = 64;

    /** Cross-rung differential on/off (Gradle: -Pfuzz.crossRung=0). */
    static final boolean CROSS_RUNG = Long.getLong("fuzz.crossRung", 1) != 0;

    /** CharSequence wrapper axis on/off; sampled 1-in-seqEvery cases
     *  (Gradle: -Pfuzz.seq=0, -Pfuzz.seqEvery=N). */
    static final boolean SEQ_WRAP = Long.getLong("fuzz.seq", 1) != 0;

    static final int SEQ_EVERY = (int) (long) Long.getLong("fuzz.seqEvery", 4);

    /**
     * Determinism double-run (runner-immutability tripwire 2): re-run
     * every engine probe on the warm artifact and byte-compare; any diff
     * records as {@code MEMO_STATE_MISMATCH}. Default on.
     */
    static final boolean DOUBLE_RUN = Long.getLong("fuzz.double", 1) != 0;

    /** Property shrinking (minimal repros) knobs: wall/step bounds for one
     *  shrink run. Where shrinking RUNS is tristate {@code fuzz.shrink}
     *  ({@link #shrinkWanted}): on by default in {@code fuzz.one}/replay
     *  triage, opt-in in the soak. */
    static final long SHRINK_MS = Long.getLong("fuzz.shrink.ms", 60_000);

    static final int SHRINK_STEPS = (int) (long) Long.getLong("fuzz.shrink.steps", 4096);

    /** Soak-side shrink-at-record (opt-in): {@code -Pfuzz.shrink=1}
     *  pre-shrinks every recorded failure record into the ndjson's
     *  {@code shrunkPattern}/{@code shrunkInput} fields. Read once at
     *  class init — the Gradle task sets the property before main runs. */
    static final boolean SHRINK_RECORD = shrinkWanted(false);

    /**
     * Warm replay for {@code fuzz.one} (runner-immutability tripwire 3):
     * replay the case's batch siblings on the same artifact before the
     * case, reproducing the soak's warm memo state. Opt-in.
     */
    static final boolean WARM_REPLAY = Long.getLong("fuzz.warm", 0) != 0;

    /** Stretched-input probes on/off; sampled 1-in-stretchEvery cases. */
    static final boolean CROSS_STRETCH = Long.getLong("fuzz.crossRung.stretch", 1) != 0;

    static final int STRETCH_EVERY = (int) (long) Long.getLong("fuzz.crossRung.stretchEvery", 8);

    /** The complete-search rungs (any can answer any find() alone). */
    static final TdfaRunner.Strategy[] FORCED_RUNGS = {TdfaRunner.Strategy.ORIGIN_SIM, TdfaRunner.Strategy.TRIGGER,
        TdfaRunner.Strategy.RAW_SCAN, TdfaRunner.Strategy.WALK_RESTART};

    /** Stretched-input floor: past twice the old search-DFA memo window
     *  (2048) so the long-scan paths — never reachable on 0-24-char
     *  inputs — run with real kill-point windows and budget interplay. */
    static final int STRETCH_MIN_CHARS = 4300;

    /** Char pools. Supplementary codepoints and lone surrogates are
     *  first-class citizens: the highest-yield divergence territory. The
     *  fold-orbit family members (Turkic İ/ı, Cyrillic historic letters
     *  and their partners) exercise the case-fold universes where engines
     *  disagree. */
    static final int[] POOL_ASCII = "abz09ZY_-.#@ ~".chars().toArray();

    static final int[] POOL_EDGE = {'\n', '\t', '\r', ' ', '\u0000'};
    static final int[] POOL_UNICODE = {0xE9, 0xDF, 0x17F, 0x3042, 0x6F22, 0x4E00, 0x03A9, 0x20AC, 0x130, 0x131, // Turkic İ/ı — fold-inert pair
        0x442, // т — plain partner of the historic orbits
        0x1C80, 0x1C84, 0x1C85, 0x1C88, // Cyrillic historic letters (Unicode 9.0)
        0xA64A}; // Ԫ — orbit partner beyond re2j 1.8's table range
    static final int[] POOL_SUPP = {0x10421, 0x10402, 0x10000, 0x1F4A9, 0x1D504, 0x11C07, 0x103FF};
    static final int[] POOL_LONE = {0xD800, 0xDBFF, 0xDC00, 0xDC21, 0xDFFF};

    /** POOL_UNICODE as drawn for pattern runes under (?i) on the released
     *  oracle (hang-prone members excluded); the full pool everywhere else. */
    static final int[] POOL_UNICODE_CI = releasedCiPool();

    // ---- fact-gated rung families: generator constants (see genFam) ----

    /** Family-batch draw rate: 1-in-8 batches take a family-biased pattern. */
    static final int FAM_EVERY = 8;

    /** The family stream's salt (generator v4). */
    static final long FAM_SALT = 0xFA17L;

    /** Needle/input alphabets — plain ASCII incl. the metachars that ride
     *  inside \Q..\E (the quote handles them). */
    static final String FAM_LIT_ALPHABET = "abz09ZYqw_.";

    static final String FAM_INPUT_ALPHABET = "abz09ZY qw_-.#~\n";

    /** Fixed half of the prefix-tail pool (the composed half rides beside it —
     *  the shapes the 2026-10-03 campaign pinned routing on, kept verbatim). */
    static final String[] FAM_TAILS =
        {"\\w+", "\\d+", "[a-z0-9]{2,}", "(a|b)+", "\\S{1,4}", ".*x", "(?:ab|ba)+", "\\w\\d", "(q|1){2,}"};

    /** The trailing anchor literal of a cand-scan shape: half drawn from the
     *  input body's alphabet (matches occur — the positive path), half from
     *  its complement (the scan-only path; position-0 walks never succeed). */
    static final String FAM_ANCHOR_PRESENT = "zq09Y._";

    static final String FAM_ANCHOR_ABSENT = "xjkcfghmnpr";

    static final int MAX_DEPTH = 4;
    private static int ciSuppAvoided; // informational; generation-side counters
    private static int ciRangeAvoided; // (known-gap / oracle-hang constructs not generated)

    // ---- compile-flag matrix + the lane axis ----

    /** Matrix bits (identical values in tdfa and re2j 1.8, verified against
     *  both Pattern classes — the drop-in contract extends to flag values).
     *  Excluded: {@code UNICODE_CHARACTER_CLASS} on the RE2J lane
     *  (tdfa-only, no re2j oracle; the jur lane draws it — its oracle
     *  translates it) and {@code DISABLE_UNICODE_GROUPS} (inert until
     *  \p{} generation lands: both engines only differ on \p{} acceptance,
     *  which the generator never emits — dead entropy today; revisit if
     *  the generator ever emits \p{}). */
    static final int FLAG_CI = io.github.jemmix.tdfa.Pattern.CASE_INSENSITIVE;

    static final int FLAG_DOTALL = io.github.jemmix.tdfa.Pattern.DOTALL;

    static final int FLAG_MULTILINE = io.github.jemmix.tdfa.Pattern.MULTILINE;

    static final int FLAG_LONGEST = io.github.jemmix.tdfa.Pattern.LONGEST_MATCH;

    /** The jur lane's UCC draw ({@code (?u)}) — a jur-facade bit whose
     *  oracle translation is java.util.regex's UNICODE_CHARACTER_CLASS
     *  (the TODO "Fuzz (?u) lane vs jur oracle" item joined this matrix
     *  with the evidence re-base). */
    static final int FLAG_UCC = io.github.jemmix.tdfa.Pattern.UNICODE_CHARACTER_CLASS;

    /** The campaign's jur flag translation (CampaignFuzzer.genJur). */
    static final int JUR_I = java.util.regex.Pattern.CASE_INSENSITIVE;

    static final int JUR_S = java.util.regex.Pattern.DOTALL;

    static final int JUR_M = java.util.regex.Pattern.MULTILINE;

    static final int JUR_UCC = java.util.regex.Pattern.UNICODE_CHARACTER_CLASS;

    static final int JUR_UCASE = java.util.regex.Pattern.UNICODE_CASE;

    /** Batched generation (generator v3): caseSeed → batch = floorDiv(s, K),
     *  index = floorMod(s, K). The pattern is a pure function of the batch,
     *  the input a pure function of (batch, index) with a deterministic
     *  boundary bias per index — so every batch caseSeed is independently
     *  replayable via {@code fuzz.one}. One compile per batch serves all K
     *  inputs: compile+codegen is ~45% of per-case cost, matching is µs.
     *  Generator version bump — pre-v3 caseSeeds are dead. */
    static final int BATCH_K = 8;

    /** The lane axis (WBS item 6): which surface the tdfa side compiles
     *  through and which oracle answers. Drawn per batch beside the flag
     *  bits from an independent stream (pure function of the batch, so
     *  replay holds), or pinned via {@code -Pfuzz.lane} for a lane-pure
     *  soak (re-running the re2j-pinned evidence, or a jur-only night). */
    enum Lane {
        RE2J, JUR
    }

    /** Lane draw: a fresh stream per batch (its own salt — the flag
     *  stream's draws are untouched, so pre-existing caseSeeds keep their
     *  patterns AND flags). p=½ per lane: one overnight soak evidences
     *  both lanes; summary.txt splits the counts. */
    static Lane genLane(long batch) {
        return new SplittableRandom(batch ^ 0x4C414E45L).nextBoolean() ? Lane.JUR : Lane.RE2J; // "LANE"
    }

    /** The lane a batch runs under: the {@code fuzz.lane} pin ({@code mix}
     *  default) over the per-batch draw. */
    static Lane laneOf(long batch) {
        String pin = System.getProperty("fuzz.lane", "mix");
        return "jur".equalsIgnoreCase(pin) ? Lane.JUR : "re2j".equalsIgnoreCase(pin) ? Lane.RE2J : genLane(batch);
    }

    public static void main(String[] argv) throws Exception {
        long one = Long.getLong("fuzz.one", 0);
        Case replay = replayCase();
        if (one != 0 || replay != null) {
            // Replay under the SAME scoped budget the soak uses — without
            // this, a one-case replay runs at the library budget (5 s of
            // ticks) and budget monsters replay in tens of seconds,
            // misrepresenting their in-soak behavior (a slow replay gets
            // labeled "clean"). -Dfuzz.max.work=0 restores the
            // library budget.
            long fuzzWork = fuzzWorkBudget();
            String prevWork =
                fuzzWork > 0 ? System.setProperty("tdfa.budget.compile.compute", Long.toString(fuzzWork)) : null;
            try {
                Case c = replay != null ? replay : generate(one);
                System.out.println("pattern: " + escape(c.pattern()));
                System.out.println("input:   " + escape(c.input()));
                System.out.println("flags:   " + c.flags() + "  lane: " + c.lane());
                if (c.lane() == Lane.JUR && jurOracleBomb(c.pattern())) {
                    System.out.println("guard:   jur oracle-bomb — case is JURLANE_GUARD in soak "
                        + "(java.util.regex would spin; run -Pfuzz.lane=re2j to replay the engines)");
                    return;
                }
                Outcome o;
                if (WARM_REPLAY && replay == null) {
                    o = warmReplay(one, c);
                    System.out.println("warm:    replayed " + (int) Math.floorMod(one, BATCH_K)
                        + " prior batch inputs on the same artifact");
                } else {
                    o = runOne(c);
                }
                System.out.println("oracle:  " + o.oracle);
                System.out.println("asm:     " + o.asm);
                System.out.println("vm:     " + o.vm);
                o.memo.forEach(m -> System.out.println("memo:    " + m));
                o.cross.forEach(x -> System.out.println("cross:   " + x));
                if (!o.exceptions.isEmpty()) {
                    o.exceptions.forEach(System.out::println);
                }
                if (shrinkWanted(true)) {
                    printShrink(c, o);
                }
            } finally {
                if (fuzzWork > 0) {
                    if (prevWork != null) {
                        System.setProperty("tdfa.budget.compile.compute", prevWork);
                    } else {
                        System.clearProperty("tdfa.budget.compile.compute");
                    }
                }
            }
            return;
        }
        long masterSeed = Long.getLong("fuzz.seed", 0) != 0 ? Long.getLong("fuzz.seed", 0) : System.currentTimeMillis();
        long minutes = Long.getLong("fuzz.minutes", 5);
        long maxCases = Long.getLong("fuzz.cases", 0);
        Path outDir = Path.of(System.getProperty("fuzz.out", "build/fuzz"));
        Results r = run(masterSeed, minutes, maxCases, outDir);
        System.out.printf("%n==== fuzz done: %d cases, %d failures (%.1f/min) ====%n", r.cases, r.failures,
            r.casesPerMinute);
        if (r.failures > 0) {
            System.out.println("see failures.ndjson / summary.txt in " + outDir);
        }
        System.exit(r.failures > 0 ? 1 : 0); // nonzero exit so overnight scripts can see it
    }

    static boolean releasedOracle() {
        try {
            return com.google.re2j.Pattern.compile("\uDC21").matcher("a\uD801\uDC21zz").find();
        } catch (RuntimeException e) {
            return true; // cannot happen for this constant; fail loud in the soak instead of silently
        }
    }

    /** One batch with a watchdog: compile once, then K inputs, each guard-tracked
     *  in {@code prog} (-1 = compiling, i = about to run input i). On timeout the
     *  worker thread is sacrificed; outcomes already written (indices
     *  < prog) are volatile-ordered before the prog store that revealed them, so
     *  the main thread records them normally and attributes the hang to the exact
     *  caseSeed (batch*K + prog). Returns false on timeout; the sacrificed worker
     *  is handed back via {@code workerOut} for the post-mortem stack. */
    static boolean runBatchWatched(String pattern, int flags, Lane lane, String[] inputs, Outcome[] os,
        AtomicInteger prog, Thread[] workerOut) throws InterruptedException {
        Thread worker = new Thread(() -> {
            Prepared pr = prepare(pattern, flags, lane);
            for (int i = 0; i < inputs.length; i++) {
                prog.set(i);
                os[i] = matchCase(pr, new Case(pattern, inputs[i], flags, lane));
            }
        }, "fuzz-case");
        worker.setDaemon(true);
        workerOut[0] = worker;
        ThreadMXBean tmx = ManagementFactory.getThreadMXBean();
        worker.start();
        worker.join(CASE_TIMEOUT_MS);
        if (!worker.isAlive()) {
            return true;
        }
        // Tiered watchdog: at 10 s, probe the worker's CPU.
        // A SPIN (cpu ~ elapsed) is a finding — sacrifice and record now. A
        // STALL (cpu far below wall — GC pause, scheduler starvation from a
        // co-tenant build) gets grace to bound the false-hang class: the
        // batch often completes normally and no record is written at all.
        // fuzz.graceMs=0 restores single-shot behavior.
        long grace = Long.getLong("fuzz.graceMs", 50_000);
        if (grace <= 0) {
            return false;
        }
        long cpu1 = tmx.getThreadCpuTime(worker.getId()) / 1_000_000;
        if (cpu1 >= CASE_TIMEOUT_MS / 2) {
            return false;
        } // burning CPU: real spin
        worker.join(grace);
        return !worker.isAlive();
    }

    /** Worker threads for batch execution. Default: cores-1 (min 2, cap 8) —
     *  compile is the dominant per-batch cost and parallelizes cleanly.
     *  1 restores the sequential executor. Case-generation order and the
     *  ndjson record order are thread-count-invariant: batches are drawn,
     *  generated and RECORDED on the main thread; only prepare+match runs
     *  on the pool. */
    static int threads() {
        long def = Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors() - 1));
        return (int) Math.max(1, Long.getLong("fuzz.threads", def));
    }

    /** One in-flight batch. {@code guarded} = the jur-lane oracle-bomb
     *  guard tripped: never executed, recorded as {@code JURLANE_GUARD}. */
    private record BatchJob(long batch, int flags, Lane lane, String pattern, String[] inputs, Outcome[] os,
        AtomicInteger prog, Thread[] worker, Future<Boolean> done, boolean guarded) {
    }

    /** Fuzz-scoped compile work budget (ticks, wall-clock-free: the same
     *  pattern rejects after the same tick count however the machine is
     *  throttled). The generator's nested-counted bombs burn 36-100M ticks
     *  walking into the state/kernel caps (10-30 s wall) and the
     *  fallback-DFS family even more — far past the 10 s case watchdog, so
     *  the worker thread got sacrificed and SPUN until it hit a cap. An 8M
     *  tick budget rejects those compiles in ~1-3 s, under the watchdog:
     *  no sacrificed threads, no background spin, and the divergence is
     *  recorded as BUDGET_REJECT instead of a hang (same known class).
     *  Legit fuzz patterns measure <<1M ticks, so the slice is unaffected.
     *  Set -Dfuzz.max.work=0 to fuzz at the library default (500 M ticks,
     *  i.e. 5 s at the assumed tick rate). */
    static long fuzzWorkBudget() {
        return Long.getLong("fuzz.max.work", 8L << 20);
    }

    /** Core entry reusable from the smoke test. */
    public static Results run(long masterSeed, long minutes, long maxCases, Path outDir) throws IOException {
        Files.createDirectories(outDir);
        long fuzzWork = fuzzWorkBudget();
        String prevWork =
            fuzzWork > 0 ? System.setProperty("tdfa.budget.compile.compute", Long.toString(fuzzWork)) : null;
        try (Logs logs = new Logs(outDir)) {
            SplittableRandom master = new SplittableRandom(masterSeed);
            long deadline = System.nanoTime() + minutes * 60_000_000_000L;
            long start = System.nanoTime();
            long lastProgress = start;
            Results r = new Results(masterSeed);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                r.writeSummary(logs);
                logs.flush();
            }));
            int threads = threads();
            ExecutorService pool = threads > 1 ? Executors.newFixedThreadPool(threads) : null;
            ArrayDeque<BatchJob> inFlight = new ArrayDeque<>();
            try {
                while ((maxCases <= 0 || r.cases < maxCases) && System.nanoTime() < deadline) {
                    // Fill the in-flight window (main-thread generation keeps
                    // the case sequence deterministic regardless of threads).
                    while (pool != null && inFlight.size() < threads * 2
                        && (maxCases <= 0 || r.cases + countQueued(inFlight) < maxCases)
                        && System.nanoTime() < deadline) {
                        BatchJob job = submit(pool, master, r);
                        if (job == null) {
                            break;
                        }
                        inFlight.add(job);
                    }
                    BatchJob job;
                    if (pool != null) {
                        if (inFlight.isEmpty()) {
                            break;
                        }
                        job = inFlight.poll();
                        boolean done;
                        try {
                            done = job.done().get();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        } catch (ExecutionException e) {
                            throw new IllegalStateException("fuzz batch failed", e.getCause());
                        }
                        handleBatch(job, done, r, logs, maxCases);
                    } else {
                        job = submit(null, master, r);
                        if (job == null) {
                            break;
                        }
                        boolean done;
                        try {
                            done = job.guarded() || runBatchWatched(job.pattern(), job.flags(), job.lane(),
                                job.inputs(), job.os(), job.prog(), job.worker());
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                        handleBatch(job, done, r, logs, maxCases);
                    }
                    long now = System.nanoTime();
                    if (now - lastProgress > 15_000_000_000L) {
                        double mins = (now - start) / 60_000_000_000.0;
                        r.casesPerMinute = mins > 0 ? r.cases / mins : 0;
                        logs.progress(r, mins);
                        r.writeSummary(logs);
                        lastProgress = now;
                    }
                }
            } finally {
                if (pool != null) {
                    pool.shutdownNow();
                    // Drained (possibly hung) workers are daemon threads: the
                    // chunked-JVM soak discipline still bounds any sacrifice.
                }
            }
            double mins = (System.nanoTime() - start) / 60_000_000_000.0;
            r.casesPerMinute = mins > 0 ? r.cases / mins : 0;
            logs.progress(r, mins);
            r.writeSummary(logs);
            return r;
        } finally {
            if (prevWork != null) {
                System.setProperty("tdfa.budget.compile.compute", prevWork);
            } else if (fuzzWork > 0) {
                System.clearProperty("tdfa.budget.compile.compute");
            }
        }
    }

    private static long countQueued(ArrayDeque<BatchJob> q) {
        return q.size() * BATCH_K;
    }

    /** Draw one batch from the master stream, generate on THIS thread, and
     *  either submit to the pool or return the job for sequential execution.
     *  A jur-lane batch whose pattern trips {@link #jurOracleBomb} is marked
     *  guarded: never executed (the oracle would spin), recorded as
     *  {@code JURLANE_GUARD} skips in {@link #handleBatch}. */
    private static BatchJob submit(ExecutorService pool, SplittableRandom master, Results r) {
        // >>> 4 keeps batch*8+i < 2^63 (>>> 3 was wrong: batches ≥ 2^60
        // wrapped negative — bijective and replayable, but confusing in logs).
        long batch = master.nextLong() >>> 4;
        Lane lane = laneOf(batch);
        int flags = genFlags(batch, lane);
        String pattern = genPattern(batch);
        FamPattern fam = genFamily(batch);
        if (fam != null) {
            r.famBatches.merge(fam.family(), 1L, Long::sum);
        }
        boolean guarded = lane == Lane.JUR && jurOracleBomb(pattern);
        // pattern-level generation guards fold once per batch
        r.ciSuppAvoidedTotal += ciSuppAvoided;
        r.ciRangeAvoidedTotal += ciRangeAvoided;
        String[] inputs = new String[BATCH_K];
        for (int i = 0; i < BATCH_K; i++) {
            inputs[i] = genInput(batch, i, flags);
        }
        Outcome[] os = new Outcome[BATCH_K];
        AtomicInteger prog = new AtomicInteger(-1);
        Thread[] worker = new Thread[1];
        if (pool != null) {
            Future<Boolean> fut = guarded ? CompletableFuture.completedFuture(true)
                : pool.submit(() -> runBatchWatched(pattern, flags, lane, inputs, os, prog, worker));
            return new BatchJob(batch, flags, lane, pattern, inputs, os, prog, worker, fut, guarded);
        }
        return new BatchJob(batch, flags, lane, pattern, inputs, os, prog, worker, null, guarded);
    }

    /** Record a finished (or hung) batch: prefix outcomes, hang attribution
     *  via the batch's own worker thread (post-mortem stack), exact case cap. */
    private static void handleBatch(BatchJob job, boolean done, Results r, Logs logs, long maxCases) {
        long batch = job.batch();
        if (job.guarded()) {
            r.recordGuarded(batch, job, logs, maxCases);
            return;
        }
        int k = done ? BATCH_K : job.prog().get(); // on hang: outcomes < k are real, k is the victim
        for (int i = 0; i < k && (maxCases <= 0 || r.cases < maxCases); i++) {
            r.record(batch * BATCH_K + i, job.os()[i], logs);
            r.cases++;
        }
        if (!done) {
            int victim = k < 0 ? 0 : k; // -1 = compile hang: replay head case (fuzz.one recompiles)
            if (r.cases < maxCases || maxCases <= 0) {
                r.hangs++;
                logs.hang(batch * BATCH_K + victim,
                    new Case(job.pattern(), job.inputs()[victim], job.flags(), job.lane()), r, job.worker()[0]);
                r.cases++;
            }
        }
    }

    // ---- one case ----

    record Case(String pattern, String input, int flags, Lane lane) {
    }

    /** Engines compiled once per batch. A non-null tag means the compile
     *  path produced that protocol string for EVERY input (rejection, or a
     *  compile-time runtime exception); exc carries the exception detail
     *  lines to attach to each Outcome — the same strings a per-case
     *  compile path would emit. The oracle column is the lane's: re2j on
     *  the RE2J lane, a compiled {@code java.util.regex} Pattern on the
     *  JUR lane ({@link #jurFlags} carries its translated flag bits). */
    static final class Prepared {
        String pattern;
        int flags;
        Lane lane = Lane.RE2J;
        com.google.re2j.Pattern oracle;
        java.util.regex.Pattern jurOracle;
        String oracleTag;
        TDFAPattern asm;
        String asmTag;
        String asmExc;
        TDFAPattern vm;
        String vmTag;
        String vmExc;
    }

    /** The oracle pattern a JUR-lane compile compares against: the
     *  engine's u/U letters are crossed vs the JDK — engine
     *  {@code (?U)} is scoped UNICODE_CASE (java's {@code (?u)}) and
     *  engine {@code (?u)} is UCC (java's {@code (?U)}) — so every
     *  inline-flag letter run swaps {@code u}<->{@code U}, spelling
     *  for spelling: bare, colon-form, negated and combined, at any
     *  depth (the scoped {@code (?U:...)} shapes compare hard since
     *  v2). The scan is escape-, character-class- and
     *  {@code \Q..\E}-aware so literal text never rewrites (a leading
     *  {@code ]} is a class member; a letter run without a
     *  {@code :}/{@code )} terminator is not a flag group). */
    static String jurOraclePattern(String pattern) {
        StringBuilder out = new StringBuilder(pattern.length());
        int n = pattern.length();
        boolean inClass = false;
        boolean quoted = false;
        for (int i = 0; i < n; i++) {
            char c = pattern.charAt(i);
            if (quoted) {
                out.append(c);
                if (c == '\\' && i + 1 < n) {
                    char nx = pattern.charAt(i + 1);
                    out.append(nx);
                    i++;
                    quoted = nx != 'E';
                }
                continue;
            }
            if (c == '\\' && i + 1 < n) {
                char nx = pattern.charAt(i + 1);
                if (nx == 'Q') {
                    quoted = true;
                }
                out.append(c).append(nx);
                i++;
                continue;
            }
            if (inClass) {
                out.append(c);
                if (c == ']') {
                    inClass = false;
                }
                continue;
            }
            if (c == '[') {
                inClass = true;
                out.append(c);
                // a leading '^' and then a ']' are literal members, not
                // the class terminator
                if (i + 1 < n && pattern.charAt(i + 1) == '^' && i + 2 < n && pattern.charAt(i + 2) == ']') {
                    out.append("^]");
                    i += 2;
                } else if (i + 1 < n && pattern.charAt(i + 1) == ']') {
                    out.append(']');
                    i++;
                }
                continue;
            }
            if (c == '(' && i + 1 < n && pattern.charAt(i + 1) == '?' && i + 2 < n
                && Character.isLetter(pattern.charAt(i + 2))) {
                // candidate inline-flag run: letters (and '-') until ':' or ')'
                int e = i + 2;
                while (e < n && pattern.charAt(e) != ':' && pattern.charAt(e) != ')'
                    && (Character.isLetter(pattern.charAt(e)) || pattern.charAt(e) == '-')) {
                    e++;
                }
                if (e < n && (pattern.charAt(e) == ':' || pattern.charAt(e) == ')')) {
                    out.append("(?");
                    for (int t = i + 2; t < e; t++) {
                        char f = pattern.charAt(t);
                        out.append(f == 'u' ? 'U' : f == 'U' ? 'u' : f);
                    }
                    i = e - 1;
                    continue;
                }
            }
            out.append(c);
        }
        return out.toString();
    }

    static int jurFlags(int flags, String pattern) {
        int jf = (flags & FLAG_CI) != 0 ? JUR_I : 0;
        jf |= (flags & FLAG_DOTALL) != 0 ? JUR_S : 0;
        jf |= (flags & FLAG_MULTILINE) != 0 ? JUR_M : 0;
        jf |= (flags & FLAG_UCC) != 0 ? JUR_UCC : 0;
        return jf;
    }

    static Prepared prepare(String pattern, int flags) {
        return prepare(pattern, flags, Lane.RE2J);
    }

    static Prepared prepare(String pattern, int flags, Lane lane) {
        Prepared p = new Prepared();
        p.pattern = pattern;
        p.flags = flags;
        p.lane = lane;
        if (lane == Lane.JUR) {
            // The jur lane: tdfa through the jur facade (the default lane,
            // provider-less — the facade's java-reading default fold
            // universe), oracle the live java.util.regex under the
            // campaign's flag translation.
            try {
                p.jurOracle = java.util.regex.Pattern.compile(jurOraclePattern(pattern), jurFlags(flags, pattern));
            } catch (RuntimeException e) {
                p.oracleTag = "<reject>";
            }
            try {
                p.asm = (TDFAPattern) io.github.jemmix.tdfa.Pattern.compile(pattern, flags, null, null);
            } catch (PatternSyntaxException | PatternTooLargeException e) {
                p.asmTag = "<reject:" + firstLine(e.getMessage()) + ">";
            } catch (RuntimeException e) {
                p.asmTag = "<exception:" + e.getClass().getSimpleName() + ">";
                p.asmExc = "asm " + e.getClass().getSimpleName() + ": " + firstLine(e.getMessage());
            }
            try {
                p.vm = (TDFAPattern) io.github.jemmix.tdfa.Pattern.compile(pattern, flags, TdfaRunner::new, null);
            } catch (PatternSyntaxException | PatternTooLargeException e) {
                p.vmTag = "<reject:" + firstLine(e.getMessage()) + ">";
            } catch (RuntimeException e) {
                p.vmTag = "<exception:" + e.getClass().getSimpleName() + ">";
                p.vmExc = "vm " + e.getClass().getSimpleName() + ": " + firstLine(e.getMessage());
            }
            return p;
        }
        // The re2j lane: the tdfa side compiles through the re2j surface
        // (the lane baked in; the facade default is java.util.regex parity)
        // and re2j is the oracle.
        try {
            p.oracle = com.google.re2j.Pattern.compile(pattern, flags);
        } catch (RuntimeException e) {
            p.oracleTag = "<reject>";
        }
        try {
            p.asm = (TDFAPattern) io.github.jemmix.tdfa.re2j.Pattern.compile(pattern, flags, null,
                Re2jUnicodeProvider.INSTANCE);
        } catch (PatternSyntaxException | PatternTooLargeException e) {
            p.asmTag = "<reject:" + firstLine(e.getMessage()) + ">";
        } catch (RuntimeException e) {
            p.asmTag = "<exception:" + e.getClass().getSimpleName() + ">";
            p.asmExc = "asm " + e.getClass().getSimpleName() + ": " + firstLine(e.getMessage());
        }
        try {
            p.vm = (TDFAPattern) io.github.jemmix.tdfa.re2j.Pattern.compile(pattern, flags, TdfaRunner::new,
                Re2jUnicodeProvider.INSTANCE);
        } catch (PatternSyntaxException | PatternTooLargeException e) {
            p.vmTag = "<reject:" + firstLine(e.getMessage()) + ">";
        } catch (RuntimeException e) {
            p.vmTag = "<exception:" + e.getClass().getSimpleName() + ">";
            p.vmExc = "vm " + e.getClass().getSimpleName() + ": " + firstLine(e.getMessage());
        }
        return p;
    }

    /** One (pattern, input) case against prepared engines. Protocol strings
     *  identical to what a per-case compile path produces. */
    static Outcome matchCase(Prepared pr, Case c) {
        Outcome o = new Outcome(c);
        if (pr.lane == Lane.JUR) {
            if (pr.jurOracle != null) {
                try {
                    o.oracle = compute(pr.jurOracle, c.input());
                } catch (RuntimeException | StackOverflowError e) {
                    o.oracle = "<exception:" + e.getClass().getSimpleName() + ">";
                }
            } else {
                o.oracle = pr.oracleTag;
            }
        } else if (pr.oracle != null) {
            try {
                o.oracle = compute(pr.oracle, c.input());
            } catch (RuntimeException e) {
                o.oracle = "<reject>";
            }
        } else {
            o.oracle = pr.oracleTag;
        }
        o.asm = runEngine(pr.asm, pr.asmTag, pr.asmExc, "asm", c, o);
        o.vm = runEngine(pr.vm, pr.vmTag, pr.vmExc, "vm", c, o);
        doubleRun(pr, c, o);
        if (CROSS_RUNG && pr.vm != null) {
            crossRung(pr, c, o);
        }
        if (SEQ_WRAP && pr.vm != null && seqSampled(c)) {
            seqProbes(pr, c, o);
        }
        return o;
    }

    // ---- CharSequence wrapper axis (the seq family's standing coverage) ----
    //
    // The ladder's String paths (indexOf needles, flat-table walks, the
    // fast extract ladders) are all keyed on `input instanceof String`;
    // every other CharSequence rides the GENERIC rung — a different
    // implementation of the same semantics. A deterministic 1-in-N sample
    // of cases (hash of pattern+input, replay-stable) re-runs BOTH engines'
    // full five-probe protocol over three wrappers — StringBuilder,
    // CharBuffer, a plain char[] view — and requires byte-identical
    // answers to the String path, plus the lane's oracle answering the
    // wrapper identically to its own String run. The reference-sim (PikeSim)
    // comparison, the GENERIC routing trace, and the wrapper zoo beyond
    // these three live in the campaign's seq mode (the deep-dive twin);
    // this axis is the standing nightly coverage on the full flag matrix.

    static void seqProbes(Prepared pr, Case c, Outcome o) {
        if (o.asm == null || o.vm == null || o.asm.startsWith("<") || o.vm.startsWith("<")) {
            return; // compile-rejected: no match-time surface to compare
        }
        CharSequence[] wrappers =
            {new StringBuilder(c.input()), CharBuffer.wrap(c.input()), new CharArraySeq(c.input())};
        for (CharSequence w : wrappers) {
            String wv;
            String wa;
            try {
                wv = compute(pr.vm, w);
                wa = compute(pr.asm, w);
            } catch (RuntimeException | StackOverflowError e) {
                o.cross.add("SEQ_PATH_MISMATCH " + w.getClass().getSimpleName() + " threw "
                    + e.getClass().getSimpleName() + ": " + firstLine(e.getMessage()));
                return;
            }
            if (!wv.equals(o.vm) || !wa.equals(o.asm)) {
                o.cross.add("SEQ_PATH_MISMATCH " + w.getClass().getSimpleName() + " vm[" + wv + "] string[" + o.vm
                    + "] asm[" + wa + "] string[" + o.asm + "]");
                return;
            }
        }
        // The lane's oracle must itself answer the wrapper identically to
        // its String run (a harness sanity tripwire: an oracle that treats
        // CharSequence differently would poison every comparison above).
        String oracleStr = o.oracle;
        if (oracleStr == null || oracleStr.startsWith("<")) {
            return;
        }
        CharSequence w = wrappers[0];
        String ow;
        try {
            ow = pr.lane == Lane.JUR ? compute(pr.jurOracle, w) : compute(pr.oracle, w);
        } catch (RuntimeException | StackOverflowError e) {
            o.cross.add("SEQ_ORACLE_WRAPPER threw " + e.getClass().getSimpleName() + ": " + firstLine(e.getMessage()));
            return;
        }
        if (!ow.equals(oracleStr)) {
            o.cross.add("SEQ_ORACLE_WRAPPER " + w.getClass().getSimpleName() + " wrapper[" + ow + "] string["
                + oracleStr + "]");
        }
    }

    // ---- determinism double-run (runner-immutability tripwire 2) ----

    /**
     * Re-run every engine probe on the SAME artifact right after the
     * normal protocol and byte-compare: a warm memo must answer exactly
     * what the cold one did — warm == cold is the tested property, not a
     * hope ({@code MEMO_STATE_MISMATCH}). On by default
     * ({@code -Dfuzz.double=0} opts out); skips tagged outcomes (rejects
     * and exceptions are compile-time, not memo state).
     */
    static void doubleRun(Prepared pr, Case c, Outcome o) {
        if (!DOUBLE_RUN) {
            return;
        }
        if (pr.asm != null && o.asm != null && !o.asm.startsWith("<")) {
            try {
                String again = compute(pr.asm, c.input());
                if (!again.equals(o.asm)) {
                    o.memo.add("MISMATCH asm warm [" + again + "] cold [" + o.asm + "]");
                }
            } catch (RuntimeException | StackOverflowError e) {
                o.memo.add("THREW asm double-run " + e.getClass().getSimpleName() + ": " + firstLine(e.getMessage()));
            }
        }
        if (pr.vm != null && o.vm != null && !o.vm.startsWith("<")) {
            try {
                String again = compute(pr.vm, c.input());
                if (!again.equals(o.vm)) {
                    o.memo.add("MISMATCH vm warm [" + again + "] cold [" + o.vm + "]");
                }
            } catch (RuntimeException | StackOverflowError e) {
                o.memo.add("THREW vm double-run " + e.getClass().getSimpleName() + ": " + firstLine(e.getMessage()));
            }
        }
    }

    // ---- cross-rung differential ----

    /**
     * Re-run each find()-carrying probe with every complete-search rung
     * forced; a forced run must reproduce the natural VM protocol exactly.
     * The baseline for the raw input is the oracle-checked {@code o.vm}
     * string itself; a stretched variant (deterministic 1-in-N sample) gets
     * its own natural baseline first. Runs on the calling (worker) thread —
     * the force hook is thread-local.
     */
    static void crossRung(Prepared pr, Case c, Outcome o) {
        if (pr.vmTag == null) {
            runForced(pr.vm, c.input(), o.vm, o, "");
        }
        if (CROSS_STRETCH && stretchSampled(c)) {
            CharSequence stretched = stretch(c.input());
            String base;
            try {
                base = compute(pr.vm, stretched);
            } catch (RuntimeException e) {
                o.cross
                    .add("THREW stretched baseline " + e.getClass().getSimpleName() + ": " + firstLine(e.getMessage()));
                return;
            }
            stretchOracleAsm(pr, c, stretched, base, o);
            runForced(pr.vm, stretched, base, o, "stretched ");
        }
    }

    /**
     * The stretched-input oracle + ASM compares (the stretch family's
     * standing coverage). RE2J lane: the natural VM answer on the stretched
     * input — the baseline every forced rung is compared against — and the
     * ASM tier's answer must BOTH equal the re2j oracle on the same
     * stretched input (the full three-way; re2j is linear-time, safe at any
     * length). JUR lane: the oracle leg is SKIPPED — java.util.regex is
     * unbounded on stretched inputs (the 2026-10-24 finding-4 family burns
     * 20-36 s there; the batch bomb guard stops the catastrophic shapes,
     * never the slow-linear ones — the same reason the campaign's stretch
     * mode RE2J-pins its generation), so the lane keeps the cross-tier leg:
     * ASM on the stretched input must equal the natural VM baseline. The
     * cross-rung-only stretch (forced-vs-natural VM, {@link #runForced})
     * stays exactly as it was, lane-agnostic.
     */
    static void stretchOracleAsm(Prepared pr, Case c, CharSequence stretched, String base, Outcome o) {
        String stOracle = null;
        if (pr.lane == Lane.RE2J && o.oracle != null && !o.oracle.startsWith("<") && stretchOracleSampled(c)) {
            try {
                stOracle = compute(pr.oracle, stretched);
            } catch (RuntimeException | StackOverflowError e) {
                stOracle = null; // oracle limit on the stretched input — tier legs still run
            }
        }
        if (stOracle != null && !base.equals(stOracle)) {
            o.cross.add("STRETCH_MISMATCH vm on " + stretched.length() + " chars: ladder [" + base + "] oracle ["
                + stOracle + "]");
            return;
        }
        if (pr.asm != null && pr.asmTag == null) {
            String asmSt;
            try {
                asmSt = compute(pr.asm, stretched);
            } catch (RuntimeException | StackOverflowError e) {
                o.cross.add("STRETCH_MISMATCH asm threw on " + stretched.length() + " chars: "
                    + e.getClass().getSimpleName() + ": " + firstLine(e.getMessage()));
                return;
            }
            if (stOracle != null ? !asmSt.equals(stOracle) : !asmSt.equals(base)) {
                o.cross.add("STRETCH_MISMATCH asm on " + stretched.length() + " chars: asm [" + asmSt + "] "
                    + (stOracle != null ? "oracle [" + stOracle + "]" : "ladder [" + base + "]"));
            }
        }
    }

    static void runForced(TDFAPattern vm, CharSequence in, String base, Outcome o, String tag) {
        // Idempotent-on; the per-case snapshots below (same worker thread as
        // the normal probes) bound every thread's trace buffer.
        TdfaRunner.setTracing(true);
        for (TdfaRunner.Strategy f : FORCED_RUNGS) {
            TdfaRunner.traceSnapshot(); // residue: this case's natural-probe traces
            TdfaRunner.setForcedStrategy(f);
            String forced;
            List<TdfaRunner.Strategy> routed;
            try {
                forced = compute(vm, in);
                routed = TdfaRunner.traceSnapshot();
            } catch (RuntimeException e) {
                TdfaRunner.traceSnapshot();
                o.cross.add("THREW " + tag + f + " " + e.getClass().getSimpleName() + ": " + firstLine(e.getMessage()));
                continue;
            } finally {
                TdfaRunner.setForcedStrategy(null);
            }
            if (!forced.equals(base)) {
                o.cross.add("MISMATCH " + tag + f + ": forced [" + forced + "] ladder [" + base + "]");
            } else if (!allowedUnder(f).containsAll(routed)) {
                // Result agreed but the ladder served it from an unexpected
                // rung family — the structural drift the trace hook exists
                // to catch (identical results, different algorithm).
                o.cross.add("ROUTED " + tag + f + " served by " + routed);
            }
        }
    }

    /**
     * Rungs a forced entry may legitimately serve with: the forced rung plus
     * its fall-through family (budget exhaustion, capped memo, defensive
     * restart) plus the anchored entries' own traces — matches()/lookingAt()
     * probes ignore the force hook by design and must stay identical.
     */
    static EnumSet<TdfaRunner.Strategy> allowedUnder(TdfaRunner.Strategy f) {
        EnumSet<TdfaRunner.Strategy> a = EnumSet.of(TdfaRunner.Strategy.EXACT_FROM, TdfaRunner.Strategy.TRIGGER,
            TdfaRunner.Strategy.RAW_SCAN, TdfaRunner.Strategy.WALK_RESTART, TdfaRunner.Strategy.ANCHORED,
            TdfaRunner.Strategy.ANCHORED_FAST, TdfaRunner.Strategy.GENERIC);
        a.add(f);
        return a;
    }

    /**
     * Long-input variant: whole-string repeats past {@link #STRETCH_MIN_CHARS}
     * — deterministic, surrogate-pair-preserving within each copy (new pairs
     * across copy boundaries affect both sides of the comparison equally).
     */
    static String stretch(String in) {
        if (in.isEmpty()) {
            return in;
        }
        return in.repeat(STRETCH_MIN_CHARS / in.length() + 1);
    }

    static boolean stretchSampled(Case c) {
        long h = c.pattern().hashCode() * 1000003L ^ c.input().hashCode();
        return Math.floorMod(h, STRETCH_EVERY) == 0;
    }

    /** The oracle leg of the stretched compares: 1-in-2 of the stretched
     *  slice (a second hash bit). The re2j oracle's five probes on a 4.3k
     *  char input are the slice's dominant cost; the full three-way at
     *  1-in-16 cases still runs the oracle millions of times a night, and
     *  the cross-tier leg keeps every stretched case covered. */
    static boolean stretchOracleSampled(Case c) {
        long h = c.pattern().hashCode() * 7L ^ c.input().hashCode();
        return Math.floorMod(h, 2) == 0;
    }

    /** The wrapper-axis sample draw (1-in-SEQ_EVERY cases; pattern+input
     *  hash, replay-stable — the stretch probe's keying twin). */
    static boolean seqSampled(Case c) {
        long h = c.pattern().hashCode() * 31L ^ c.input().hashCode() * 1000003L;
        return Math.floorMod(h, SEQ_EVERY) == 0;
    }

    static String runEngine(TDFAPattern p, String tag, String exc, String engTag, Case c, Outcome o) {
        if (tag != null) {
            if (exc != null) {
                o.exceptions.add(exc);
            }
            return tag;
        }
        try {
            return compute(p, c.input());
        } catch (RuntimeException e) {
            o.exceptions.add(engTag + " " + e.getClass().getSimpleName() + ": " + firstLine(e.getMessage()));
            return "<exception:" + e.getClass().getSimpleName() + ">";
        }
    }

    static Outcome runOne(Case c) {
        return matchCase(prepare(c.pattern(), c.flags(), c.lane()), c);
    }

    /**
     * Warm replay for {@code fuzz.one} (runner-immutability tripwire 3):
     * run the case's batch siblings on the SAME artifact first — the memo
     * warmth the soak had when it recorded — then the case itself. Solo
     * replay runs cold, which is exactly how warmth-dependent records
     * (2026-10-24 finding 1) stayed unreproduced.
     */
    static Outcome warmReplay(long caseSeed, Case c) {
        long batch = Math.floorDiv(caseSeed, BATCH_K);
        int idx = (int) Math.floorMod(caseSeed, BATCH_K);
        Prepared pr = prepare(c.pattern(), c.flags(), c.lane());
        for (int i = 0; i < idx; i++) {
            matchCase(pr, new Case(c.pattern(), genInput(batch, i, c.flags()), c.flags(), c.lane()));
        }
        return matchCase(pr, c);
    }

    // ---- property shrinking (minimal repros) ----

    /** Where shrinking runs. Tristate {@code fuzz.shrink} (Gradle forwards
     *  {@code -Pfuzz.shrink}): {@code auto} (default) takes the mode's own
     *  default — on for {@code fuzz.one}/replay triage, off for the soak —
     *  while {@code 1}/{@code true} and {@code 0}/{@code false} pin both
     *  (the fuzzer's numeric flag convention — plain Boolean parsing
     *  would read "1" as false). */
    static boolean shrinkWanted(boolean defaultOn) {
        String v = System.getProperty("fuzz.shrink", "auto");
        return "auto".equalsIgnoreCase(v) ? defaultOn : "1".equals(v) || Boolean.parseBoolean(v);
    }

    /** The recorded-failure kind of an outcome, or null when the case is
     *  NOT a finding: clean, both-reject, a jur-lane domain exclusion, or
     *  a released-oracle known divergence. Mirrors the record-time
     *  classification, so a shrunk case stays a repro of a FINDING and can
     *  never drift into a skip or known class. Cross findings keep their
     *  record split: ROUTED-first is CROSS_ROUTING, else CROSS_RUNG. */
    static String findingKind(Outcome o) {
        if (!o.cross.isEmpty()) {
            return o.cross.get(0).startsWith("ROUTED") ? "CROSS_ROUTING" : "CROSS_RUNG";
        }
        if (!o.failed()) {
            return null;
        }
        if (o.c.lane() == Lane.JUR && Results.jurLaneDomain(o) != null) {
            return null;
        }
        if (o.c.lane() == Lane.RE2J && RELEASED_ORACLE && Results.knownDivergence(o) != null && o.asm.equals(o.vm)) {
            return null;
        }
        return firstWord(Results.kindOf(o));
    }

    /** The shrink property: the case still fails COLD-SOLO with the same
     *  finding kind. Jur-lane oracle bombs are never evaluated (the live
     *  java.util.regex oracle would spin — the soak's own guard). Warmth-
     *  dependent records (MEMO_STATE_MISMATCH on a warm artifact) do not
     *  reproduce cold and simply do not shrink. */
    static Predicate<Case> sameFinding(String kind) {
        return c -> {
            if (c.lane() == Lane.JUR && jurOracleBomb(c.pattern())) {
                return false;
            }
            String k;
            try {
                k = findingKind(runOne(c));
            } catch (RuntimeException | StackOverflowError e) {
                return false;
            }
            return kind.equals(k);
        };
    }

    /** Shrink a failing case to its minimal same-kind repro; null when the
     *  kind is null (not a finding) or the case does not reproduce cold. */
    static CaseShrinker.ShrinkReport shrinkCase(Case c, String kind) {
        if (kind == null) {
            return null;
        }
        return new CaseShrinker(sameFinding(kind), SHRINK_MS, SHRINK_STEPS).shrink(c);
    }

    /** The {@code fuzz.one} shrink block: minimal repro + the turnkey
     *  replay command (replay properties are the ASCII-safe escaped form —
     *  quote them if your shell wants that). */
    private static void printShrink(Case c, Outcome o) {
        String kind = findingKind(o);
        if (kind == null) {
            System.out.println(
                "shrink:  n/a — " + (o.failed() ? "skip/known class, not a recorded finding" : "case is clean"));
            return;
        }
        CaseShrinker.ShrinkReport rep = shrinkCase(c, kind);
        if (rep == null) {
            System.out.println("shrink:  does not reproduce cold (warm-only record? add -Pfuzz.warm=1)");
        } else if (!rep.changed()) {
            System.out.println("shrink:  already minimal — " + rep.evals() + " evals"
                + (rep.abort() != null ? " — aborted: " + rep.abort() : ""));
        } else {
            Case m = rep.minimal();
            System.out.println("shrunk:  pattern: " + escape(m.pattern()) + "  input: " + escape(m.input()));
            System.out.println("shrunk:  " + c.pattern().length() + "→" + m.pattern().length() + " pattern chars, "
                + c.input().length() + "→" + m.input().length() + " input chars — " + rep.evals() + " evals"
                + (rep.abort() != null ? " — aborted: " + rep.abort()
                    : rep.capped() ? " — cap hit, may not be minimal" : ""));
            System.out.println("replay: ./gradlew :tests:parity:re2j:fuzz -Pfuzz.replay.pattern=" + escape(m.pattern())
                + " -Pfuzz.replay.input=" + escape(m.input()) + " -Pfuzz.replay.flags=" + m.flags()
                + " -Pfuzz.replay.lane=" + m.lane());
        }
    }

    /** An explicit case from {@code -Dfuzz.replay.*} — the shrink block's
     *  replay line. Pattern/input are in the ASCII-safe \\uXXXX
     *  {@link #escape} form so any codepoint round-trips through Gradle
     *  properties and shells unharmed. */
    static Case replayCase() {
        String pat = System.getProperty("fuzz.replay.pattern", "");
        if (pat.isEmpty()) {
            return null;
        }
        String in = System.getProperty("fuzz.replay.input", "");
        int flags = Integer.parseInt(System.getProperty("fuzz.replay.flags", "0"));
        Lane lane = Lane.valueOf(System.getProperty("fuzz.replay.lane", Lane.RE2J.name()));
        return new Case(unescape(pat), unescape(in), flags, lane);
    }

    /** Inverse of {@link #escape} for the replay properties. */
    static String unescape(String s) {
        if (s.indexOf("\\u") < 0) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 5 < s.length() && s.charAt(i + 1) == 'u') {
                int hex = 0;
                int k = 0;
                while (k < 4) {
                    int d = Character.digit(s.charAt(i + 2 + k), 16);
                    if (d < 0) {
                        break;
                    }
                    hex = hex << 4 | d;
                    k++;
                }
                if (k == 4) {
                    sb.append((char) hex);
                    i += 5;
                    continue;
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }

    // ---- match protocol ----

    /** Five probes per case, one string, compared for exact equality:
     *  <ul>
     *   <li>{@code F} — first {@code find()} with overall and every group's
     *       span. Spans strictly subsume group texts: identical spans on
     *       the same input ARE identical texts, and null-vs-empty groups
     *       are distinguishable ({@code -} vs {@code s..s}).</li>
     *   <li>{@code I} — {@code find()} iteration to exhaustion on the SAME
     *       matcher: the continuation and empty-match-advance paths, spans
     *       per match. {@code ]$} = cap hit (spin-bound, never healthy).</li>
     *   <li>{@code M} — {@code matches()}: the separately-anchored
     *       whole-input engine.</li>
     *   <li>{@code L} — {@code lookingAt()}: prefix match.</li>
     *   <li>{@code R} — {@code find(len/2)} on a fresh matcher: the
     *       reset-and-restart path; deliberately can land inside a
     *       surrogate pair.</li>
     *  </ul>
     *  Fresh matchers for M/L/R keep the probes independent (attribution
     *  reads off the diverged probe tag). No free text in the format —
     *  spans only — so probe tags are unambiguous in diff reporting. */
    static String compute(TDFAPattern p, CharSequence in) {
        StringBuilder sb = new StringBuilder(96);
        io.github.jemmix.tdfa.core.engine.Matcher m = p.matcher(in);
        boolean found = m.find();
        if (found) {
            spanTdfa(sb.append("F=true "), m);
        } else {
            sb.append("F=false");
        }
        sb.append(" I=[");
        int n = 0;
        if (found) {
            spanTdfa(sb, m);
            while (++n < MAX_MATCHES && m.find()) {
                spanTdfa(sb, m);
            }
        }
        sb.append(n == MAX_MATCHES ? "]+$" : "]");
        io.github.jemmix.tdfa.core.engine.Matcher mm = p.matcher(in);
        if (mm.matches()) {
            spanTdfa(sb.append(" M=true "), mm);
        } else {
            sb.append(" M=false");
        }
        io.github.jemmix.tdfa.core.engine.Matcher ml = p.matcher(in);
        if (ml.lookingAt()) {
            spanTdfa(sb.append(" L=true "), ml);
        } else {
            sb.append(" L=false");
        }
        io.github.jemmix.tdfa.core.engine.Matcher mr = p.matcher(in);
        if (mr.find(in.length() / 2)) {
            spanTdfa(sb.append(" R=true "), mr);
        } else {
            sb.append(" R=false");
        }
        return sb.toString();
    }

    static String compute(com.google.re2j.Pattern p, CharSequence in) {
        StringBuilder sb = new StringBuilder(96);
        com.google.re2j.Matcher m = p.matcher(in);
        boolean found = m.find();
        if (found) {
            spanRe2j(sb.append("F=true "), m);
        } else {
            sb.append("F=false");
        }
        sb.append(" I=[");
        int n = 0;
        if (found) {
            spanRe2j(sb, m);
            while (++n < MAX_MATCHES && m.find()) {
                spanRe2j(sb, m);
            }
        }
        sb.append(n == MAX_MATCHES ? "]+$" : "]");
        com.google.re2j.Matcher mm = p.matcher(in);
        if (mm.matches()) {
            spanRe2j(sb.append(" M=true "), mm);
        } else {
            sb.append(" M=false");
        }
        com.google.re2j.Matcher ml = p.matcher(in);
        if (ml.lookingAt()) {
            spanRe2j(sb.append(" L=true "), ml);
        } else {
            sb.append(" L=false");
        }
        com.google.re2j.Matcher mr = p.matcher(in);
        if (mr.find(in.length() / 2)) {
            spanRe2j(sb.append(" R=true "), mr);
        } else {
            sb.append(" R=false");
        }
        return sb.toString();
    }

    /** The five-probe protocol over the jur oracle — same span format as
     *  the engine and re2j protocols, so the strings compare directly
     *  ({@link CampaignFuzzer#javaProtocol} is the F+I+M+R engine-level
     *  twin). */
    static String compute(java.util.regex.Pattern p, CharSequence in) {
        StringBuilder sb = new StringBuilder(96);
        java.util.regex.Matcher m = p.matcher(in);
        boolean found = m.find();
        if (found) {
            CampaignFuzzer.spanJur(sb.append("F=true "), m);
        } else {
            sb.append("F=false");
        }
        sb.append(" I=[");
        int n = 0;
        if (found) {
            CampaignFuzzer.spanJur(sb, m);
            while (++n < MAX_MATCHES && m.find()) {
                CampaignFuzzer.spanJur(sb, m);
            }
        }
        sb.append(n == MAX_MATCHES ? "]+$" : "]");
        java.util.regex.Matcher mm = p.matcher(in);
        if (mm.matches()) {
            CampaignFuzzer.spanJur(sb.append(" M=true "), mm);
        } else {
            sb.append(" M=false");
        }
        java.util.regex.Matcher ml = p.matcher(in);
        if (ml.lookingAt()) {
            CampaignFuzzer.spanJur(sb.append(" L=true "), ml);
        } else {
            sb.append(" L=false");
        }
        java.util.regex.Matcher mr = p.matcher(in);
        if (mr.find(in.length() / 2)) {
            CampaignFuzzer.spanJur(sb.append(" R=true "), mr);
        } else {
            sb.append(" R=false");
        }
        return sb.toString();
    }

    /** {@code s..e (g1s..g1e g2s..g2e ...)}; non-participating group = {@code -}. */
    static void spanTdfa(StringBuilder sb, io.github.jemmix.tdfa.core.engine.Matcher m) {
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

    static void spanRe2j(StringBuilder sb, com.google.re2j.Matcher m) {
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

    // ---- pattern generator ----

    /** True for the Cyrillic historic letters U+1C80..U+1C88 as PATTERN
     *  runes under (?i) on the RELEASED oracle: its Unicode 6.0 CASE_ORBIT
     *  predates them, so simpleFold's fallback steps into the partner's
     *  symmetric orbit and never cycles back — the compile hangs. The
     *  patched oracle (fork patch 0003) declines the asymmetric mappings,
     *  so they compile freely there (fold-inert). Inputs carrying them
     *  are safe on any oracle (no compile-time walk), and fold semantics
     *  track the oracle by construction (see knownDivergence). */
    static boolean releasedFoldHang(int cp) {
        return cp >= 0x1C80 && cp <= 0x1C88;
    }

    /**
     * The jur-lane oracle-hang guard (see the class doc): true when the
     * pattern is a catastrophic-backtracking shape for
     * {@code java.util.regex} — always a WIDE atom (dot, the negated
     * shorthands, or a bracket class) textually inside a quantified
     * group, in one of three families:
     * <ol>
     * <li>an unbounded quantifier over the wide atom inside an
     * unbounded-quantified group — campaign 2026-10-16 finding 4
     * ({@code (?:\D+[^\ds-z#𐐂]*?){2,}?} and friends spin it >100 s on
     * ≤24-char inputs);</li>
     * <li>a bounded-range quantifier ({@code {n,m}}, m ≥ 2 — variable
     * width) over the wide atom inside a LAZY-unbounded-quantified group
     * — campaign 2026-10-24 finding 6 ({@code (.{0,6}?)*?}, 22 s): the
     * greedy {@code Loop} memoizes failed loop-entry positions, so the
     * eager nest above and the memo-less {@code LazyLoop} spins are
     * disjoint families;</li>
     * <li>two multi-quantified wides (unbounded, or a variable-width
     * range) inside a range-counted {@code {n,m}} group — the same
     * finding ({@code (.*?.*?){2,6}}, 15 s): the memo prunes loop
     * re-entry, never the width splits inside one body match.</li>
     * </ol>
     * NOT bombs on generator-sized inputs, so not guarded: bounded-count
     * outer quantifiers ({@code {3}}, {@code {0,4}} over one wide), a
     * single multi-quantified wide in a range-counted group
     * ({@code (.*?){2,6}}), and 0/1-width bodies ({@code (?:\D??){2,}?}
     * — Ques, no width fan-out; the finding-6 clean lookalikes). The
     * engine-side slow shapes of the earlier campaigns
     * ({@code .*}-anchor restart scans, unquantified nesting) have no
     * unbounded-quantified enclosing group and pass through untouched.
     *
     * <p>A pure-function structural scan: escapes, {@code \Q..\E} quotes
     * and class interiors are skipped (their metacharacters are literals),
     * and containment is textual, so nesting through inner groups counts.
     */
    static boolean jurOracleBomb(String p) {
        // Atom events {start, end, kind, quant}: kind 0 = wide atom,
        // 1 = anything else, 2 = group; quant 0 = none, '?' or exact
        // {n} (widths 0/1 or fixed), 1 = bounded range {n,m} with
        // m >= 2 (variable width), 2 = unbounded greedy (*, +, {n,}),
        // 3 = unbounded lazy (*?, +?, {n,}?).
        List<int[]> events = new ArrayList<>();
        ArrayDeque<int[]> groups = new ArrayDeque<>(); // open group starts
        int i = 0;
        while (i < p.length()) {
            char c = p.charAt(i);
            if (c == '\\' && i + 1 < p.length()) {
                char n = p.charAt(i + 1);
                if (n == 'Q') {
                    int e = p.indexOf("\\E", i + 2);
                    i = e < 0 ? p.length() : e + 2;
                    continue;
                }
                i = atomEnd(p, i, i + 2, n == 'D' || n == 'W' || n == 'S', events);
                continue;
            }
            if (c == '[') {
                int j = i + 1;
                if (j < p.length() && p.charAt(j) == '^') {
                    j++;
                }
                if (j < p.length() && p.charAt(j) == ']') {
                    j++; // literal ] first in class
                }
                while (j < p.length() && p.charAt(j) != ']') {
                    j += p.charAt(j) == '\\' && j + 1 < p.length() ? 2 : 1;
                }
                i = atomEnd(p, i, j + 1, true, events);
                continue;
            }
            if (c == '(') {
                groups.push(new int[]{i});
                i++;
                continue;
            }
            if (c == ')') {
                if (!groups.isEmpty()) {
                    int start = groups.pop()[0];
                    i = atomEnd(p, start, i + 1, false, events);
                    events.get(events.size() - 1)[2] = 2; // mark: group
                } else {
                    i++;
                }
                continue;
            }
            if (c == '.') {
                i = atomEnd(p, i, i + 1, true, events);
                continue;
            }
            i++; // literal char, anchor, or structural — not a wide atom
        }
        for (int[] g : events) {
            if (g[2] != 2) {
                continue; // not a group
            }
            int multis = 0; // multi-quantified wides inside g
            for (int[] w : events) {
                if (w[2] != 0 || w[0] <= g[0] || w[1] >= g[1]) {
                    continue; // not a wide atom inside g
                }
                if (g[3] >= 2 && w[3] >= 2) {
                    return true; // unbounded-wide inner inside an unbounded group
                }
                if (g[3] == 3 && w[3] == 1) {
                    return true; // variable-width wide inside a lazy group loop
                }
                if (g[3] == 1 && w[3] != 0) {
                    multis++;
                }
            }
            if (g[3] == 1 && multis >= 2) {
                return true; // two multi-quantified wides inside a range-counted group
            }
        }
        return false;
    }

    /** Close the atom ending at {@code end} (exclusive): read one trailing
     *  quantifier, record the event, return the scan position after it. */
    private static int atomEnd(String p, int start, int end, boolean wide, List<int[]> events) {
        int quant = 0;
        boolean lazy = false;
        int i = end;
        if (i < p.length()) {
            char c = p.charAt(i);
            if (c == '*' || c == '+') {
                quant = 2;
                i++;
            } else if (c == '?') {
                i++;
            } else if (c == '{') {
                int j = i + 1;
                int comma = -1;
                while (j < p.length() && "0123456789,".indexOf(p.charAt(j)) >= 0) {
                    if (p.charAt(j) == ',') {
                        comma = j;
                    }
                    j++;
                }
                if (j < p.length() && p.charAt(j) == '}' && j > i + 1) {
                    if (comma == j - 1) { // {n,} — unbounded
                        quant = 2;
                    } else if (comma > 0) { // {n,m} — range; variable width iff m >= 2
                        int m = 0;
                        for (int k = comma + 1; k < j; k++) {
                            m = Math.min(2, m * 10 + (p.charAt(k) - '0'));
                        }
                        quant = m >= 2 ? 1 : 0; // {0,1} compiles to Ques — width 0/1
                    } // else {n} exact: fixed width
                    i = j + 1;
                } // else: literal '{' — no quantifier
            }
            if (i < p.length() && p.charAt(i) == '?') { // lazy marker
                lazy = true;
                i++;
            }
        }
        events.add(new int[]{start, end, wide ? 0 : 1, lazy && quant == 2 ? 3 : quant});
        return i;
    }

    static int[] releasedCiPool() {
        if (!RELEASED_ORACLE) {
            return POOL_UNICODE;
        }
        int n = 0;
        for (int cp : POOL_UNICODE) {
            if (!releasedFoldHang(cp)) {
                n++;
            }
        }
        int[] out = new int[n];
        int i = 0;
        for (int cp : POOL_UNICODE) {
            if (!releasedFoldHang(cp)) {
                out[i++] = cp;
            }
        }
        return out;
    }

    /** POOL_UNICODE draw for pattern context. */
    static int unicodeCp(SplittableRandom rnd, boolean ci) {
        int[] pool = ci ? POOL_UNICODE_CI : POOL_UNICODE;
        return pool[rnd.nextInt(pool.length)];
    }

    /** Per-batch compile flags, drawn from a stream DECOUPLED from the
     *  pattern's ({@code SplittableRandom(batch)}); patterns depend on the
     *  flags only through the ci guard in {@link #genPattern}. 40% of
     *  batches stay at flags=0 — the unflagged regime keeps a steady share
     *  of coverage — otherwise each of CI/DOTALL/MULTILINE/LONGEST is drawn
     *  independently (p=½): every flag in ~30% of batches, all-four in
     *  ~3.75%. */
    static int genFlags(long batch) {
        return genFlags(batch, Lane.RE2J);
    }

    /** The lane-aware draw: the SAME stream per batch (the ci guard in
     *  {@link #genPattern} reads the lane-blind view, so a batch's pattern
     *  is lane-independent); the fourth bit is LONGEST on the re2j lane and
     *  UCC ({@code (?u)}) on the jur lane — jur has no longest reading,
     *  and UCC has no re2j oracle. */
    static int genFlags(long batch, Lane lane) {
        SplittableRandom rnd = new SplittableRandom(batch ^ 0x6D69786C6F6E676DL);
        if (rnd.nextInt(10) < 4) {
            return 0;
        }
        int f = 0;
        if (rnd.nextBoolean()) {
            f |= FLAG_CI;
        }
        if (rnd.nextBoolean()) {
            f |= FLAG_DOTALL;
        }
        if (rnd.nextBoolean()) {
            f |= FLAG_MULTILINE;
        }
        if (rnd.nextBoolean()) {
            f |= lane == Lane.JUR ? FLAG_UCC : FLAG_LONGEST;
        }
        return f;
    }

    /** Soak semantics: the lane is the batch's draw (or the {@code fuzz.lane}
     *  pin) — what {@code fuzz.one} replays. Campaign callers that compare
     *  against the re2j oracle pin {@link Lane#RE2J} instead. */
    static Case generate(long caseSeed) {
        long batch = Math.floorDiv(caseSeed, BATCH_K);
        return generate(caseSeed, laneOf(batch));
    }

    static Case generate(long caseSeed, Lane lane) {
        long batch = Math.floorDiv(caseSeed, BATCH_K);
        int idx = (int) Math.floorMod(caseSeed, BATCH_K);
        int flags = genFlags(batch, lane);
        return new Case(genPattern(batch), genInput(batch, idx, flags), flags, lane);
    }

    static String genPattern(long batch) {
        ciSuppAvoided = 0;
        ciRangeAvoided = 0;
        // Compile-level CASE_INSENSITIVE folds the WHOLE pattern in re2j's
        // parser, and re2j folds class ranges codepoint by codepoint — a
        // wide range is an oracle-side spin (the reason the ci guard
        // exists). CI batches must therefore generate under the guard
        // (ASCII-narrow ranges) like inline (?i:...) always has. Pure
        // function of the batch still (genFlags is), so replay holds.
        // Family batches (generator v4) skip the guard: their shapes are
        // ASCII-only by construction, so the fold-orbit/Range spin families
        // cannot arise — a CI family batch instead exercises the documented
        // gate-miss fall-through (folded literals lose their needles).
        FamPattern fam = genFamily(batch);
        if (fam != null) {
            return fam.pattern();
        }
        boolean ci = (genFlags(batch) & FLAG_CI) != 0;
        return expr(new SplittableRandom(batch), 0, ci);
    }

    /** Input = pure fn(batch, index). Base draw as before (pool mix), then a
     *  deterministic per-index boundary transform: the historical bug
     *  families were input-position-sensitive (word/anchor boundaries,
     *  surrogate-pair interiors, $ vs \z), which one random haystack per
     *  pattern systematically misses. Under MULTILINE flags, 1-2 interior
     *  '\n' are inserted so ^/$ actually see lines (the per-index transforms
     *  provide at most one trailing '\n'). */
    static String genInput(long batch, int idx, int flags) {
        // Family batches take the family-biased input (the needle embeds of
        // famInput, keyed by the same stream salt as the family draw, so
        // pattern+input change together on the family axis).
        FamPattern fam = genFamily(batch);
        if (fam != null) {
            return famInput(
                new SplittableRandom(batch * 0x9E3779B97F4A7C15L ^ (idx + 1) * 0xBF58476D1CE4E5B9L ^ FAM_SALT), fam,
                idx);
        }
        SplittableRandom rnd = new SplittableRandom(batch * 0x9E3779B97F4A7C15L ^ (idx + 1) * 0xBF58476D1CE4E5B9L);
        int inLen = rnd.nextInt(0, 25);
        StringBuilder in = new StringBuilder(inLen * 2);
        for (int i = 0; i < inLen; i++) {
            in.appendCodePoint(pickInputCp(rnd));
        }
        switch (idx) {
            case 1 -> in.append('\n'); // $ / (?m)$ / \z divergence axis
            case 2 -> {
                in.insert(0, ' ');
                in.append(' ');
            } // \b at both ends
            case 3 -> in.insert(in.length() / 2, (char) POOL_LONE[rnd.nextInt(POOL_LONE.length)]); // lone surrogate mid-string
            case 4 -> in.appendCodePoint(POOL_SUPP[rnd.nextInt(POOL_SUPP.length)])
                .append((char) POOL_ASCII[rnd.nextInt(POOL_ASCII.length)]); // pair adjacent to ASCII
            case 5 -> in.setLength(rnd.nextInt(0, 4)); // near-empty (may split a pair — deliberate)
            case 6 -> {
                for (int[] pool : new int[][]{POOL_ASCII, POOL_EDGE, POOL_UNICODE, POOL_SUPP, POOL_LONE}) {
                    in.appendCodePoint(pool[rnd.nextInt(pool.length)]);
                }
            } // one of every pool
            default -> {
            } // idx 0, 7: plain random
        }
        if ((flags & FLAG_MULTILINE) != 0) {
            int extra = 1 + rnd.nextInt(2);
            for (int i = 0; i < extra; i++) {
                in.insert(rnd.nextInt(in.length() + 1), '\n');
            }
        }
        return in.toString();
    }

    static int pickInputCp(SplittableRandom rnd) {
        return switch (rnd.nextInt(10)) {
            case 0, 1 -> POOL_ASCII[rnd.nextInt(POOL_ASCII.length)];
            case 2 -> POOL_EDGE[rnd.nextInt(POOL_EDGE.length)];
            case 3, 4 -> POOL_UNICODE[rnd.nextInt(POOL_UNICODE.length)];
            case 5, 6, 7 -> POOL_SUPP[rnd.nextInt(POOL_SUPP.length)]; // pairs in inputs
            case 8, 9 -> POOL_LONE[rnd.nextInt(POOL_LONE.length)]; // lone surrogates in inputs
            default -> 'a';
        };
    }

    static String expr(SplittableRandom rnd, int depth, boolean ci) {
        int parts = rnd.nextInt(1, 4);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts; i++) {
            sb.append(atom(rnd, depth, ci));
        }
        return sb.toString();
    }

    static String atom(SplittableRandom rnd, int depth, boolean ci) {
        int roll = rnd.nextInt(100);
        if (depth < MAX_DEPTH) {
            if (roll < 8) {
                return "(" + expr(rnd, depth + 1, ci) + ")";
            }
            if (roll < 12) {
                return "(?:" + expr(rnd, depth + 1, ci) + ")";
            }
            if (roll < 14) {
                return "(?<n" + rnd.nextInt(3) + ">" + expr(rnd, depth + 1, ci) + ")";
            }
            if (roll < 16) {
                return "(?i:" + expr(rnd, depth + 1, true) + ")";
            }
            if (roll < 18) {
                return "(?s:" + expr(rnd, depth + 1, ci) + ")";
            }
            if (roll < 20) {
                return "(?m:" + expr(rnd, depth + 1, ci) + ")";
            }
            if (roll < 22) {
                return "(?U:" + expr(rnd, depth + 1, ci) + ")";
            }
            if (roll < 25 && depth > 0) {
                return expr(rnd, depth + 1, ci) + "|" + expr(rnd, depth + 1, ci);
            }
        }
        if (roll < 32) {
            return quant(rnd, depth, ci);
        }
        if (roll < 36) {
            return ".";
        }
        if (roll < 40) {
            return anchor(rnd);
        }
        if (roll < 48) {
            return shorthand(rnd);
        }
        if (roll < 51) {
            return "\\Q" + plainLiteral(rnd) + "\\E";
        }
        return patternLiteral(rnd, ci);
    }

    static String quant(SplittableRandom rnd, int depth, boolean ci) {
        String body = quantable(rnd, depth, ci);
        String q = switch (rnd.nextInt(6)) {
            case 0 -> "*";
            case 1 -> "+";
            case 2 -> "?";
            case 3 -> "{" + rnd.nextInt(0, 4) + "," + Math.max(rnd.nextInt(0, 7), 1) + "}";
            case 4 -> "{" + rnd.nextInt(0, 5) + ",}";
            default -> "{" + rnd.nextInt(1, 4) + "}";
        };
        return body + q + (rnd.nextInt(4) == 0 ? "?" : "");
    }

    static String quantable(SplittableRandom rnd, int depth, boolean ci) {
        int roll = rnd.nextInt(100);
        if (roll < 25) {
            return patternLiteral(rnd, ci);
        }
        if (roll < 40) {
            return shorthand(rnd);
        }
        if (roll < 55) {
            return ".";
        }
        if (roll < 75) {
            return "[" + classBody(rnd, ci) + "]";
        }
        if (depth < MAX_DEPTH && roll < 88) {
            return "(" + expr(rnd, depth + 1, ci) + ")";
        }
        if (depth < MAX_DEPTH) {
            return "(?:" + expr(rnd, depth + 1, ci) + ")";
        }
        return "a";
    }

    static String shorthand(SplittableRandom rnd) {
        return switch (rnd.nextInt(8)) {
            case 0 -> "\\d";
            case 1 -> "\\D";
            case 2 -> "\\w";
            case 3 -> "\\W";
            case 4 -> "\\s";
            case 5 -> "\\S";
            case 6 -> "\\n";
            default -> "\\t";
        };
    }

    static String anchor(SplittableRandom rnd) {
        return switch (rnd.nextInt(6)) {
            case 0 -> "^";
            case 1 -> "$";
            case 2 -> "\\b";
            case 3 -> "\\B";
            case 4 -> "\\A";
            default -> "\\z";
        };
    }

    static String classBody(SplittableRandom rnd, boolean ci) {
        StringBuilder sb = new StringBuilder();
        if (rnd.nextInt(6) == 0) {
            sb.append('^');
        }
        int members = rnd.nextInt(1, 5);
        for (int i = 0; i < members; i++) {
            int roll = rnd.nextInt(10);
            if (roll < 3) {
                int lo, hi;
                if (ci && RELEASED_ORACLE) {
                    // (?i) ranges: ASCII-narrow only on the RELEASED oracle.
                    // Its parser folds every cp in the range with an
                    // unbounded orbit walk, and any range reaching
                    // U+1C80..U+1C88 never terminates (the walk steps into
                    // the partner's symmetric orbit and cannot cycle back).
                    // The patched oracle (fork patch 0003) declines the
                    // asymmetric mappings, so ranges generate full-width
                    // there.
                    ciRangeAvoided++;
                    lo = POOL_ASCII[rnd.nextInt(POOL_ASCII.length)];
                    hi = POOL_ASCII[rnd.nextInt(POOL_ASCII.length)];
                } else {
                    lo = pickClassCp(rnd, ci);
                    hi = pickClassCp(rnd, ci);
                }
                if (hi < lo) {
                    int t = lo;
                    lo = hi;
                    hi = t;
                } // keep ranges legal (re2j rejects inverted)
                sb.append(classMember(lo)).append('-').append(classMember(hi));
            } else if (roll < 5) {
                sb.append("\\").append(switch (rnd.nextInt(4)) {
                    case 0 -> "d";
                    case 1 -> "w";
                    case 2 -> "s";
                    default -> "n";
                });
            } else {
                sb.append(classMember(pickClassCp(rnd, ci)));
            }
        }
        return sb.toString();
    }

    static String classMember(int cp) {
        if (cp == '\\') {
            return "\\\\";
        }
        if (cp == ']' || cp == '[' || cp == '-' || cp == '^') {
            return "\\" + (char) cp;
        }
        return cp > 0xFFFF ? new StringBuilder().appendCodePoint(cp).toString() : String.valueOf((char) cp);
    }

    /** Class member/range endpoint. Under (?i): supplementary and lone
     *  surrogates avoided (tdfa lacks a supplementary fold table; re2j folds). */
    static int pickClassCp(SplittableRandom rnd, boolean ci) {
        return switch (rnd.nextInt(8)) {
            case 0, 1, 2 -> POOL_ASCII[rnd.nextInt(POOL_ASCII.length)];
            case 3 -> unicodeCp(rnd, ci);
            case 4 -> POOL_SUPP[rnd.nextInt(POOL_SUPP.length)];
            case 5 -> POOL_LONE[rnd.nextInt(POOL_LONE.length)];
            default -> 'a' + rnd.nextInt(26);
        };
    }

    /** Literal for pattern context, specials escaped. */
    static String patternLiteral(SplittableRandom rnd, boolean ci) {
        int cp = switch (rnd.nextInt(8)) {
            case 0, 1, 2, 3 -> POOL_ASCII[rnd.nextInt(POOL_ASCII.length)];
            case 4 -> unicodeCp(rnd, ci);
            case 5 -> POOL_SUPP[rnd.nextInt(POOL_SUPP.length)];
            case 6 -> POOL_LONE[rnd.nextInt(POOL_LONE.length)];
            default -> 'a' + rnd.nextInt(26);
        };
        StringBuilder sb = new StringBuilder();
        if (cp < 128 && ".[]()*+?{}|^$\\-#~".indexOf(cp) >= 0) {
            sb.append('\\');
        }
        sb.appendCodePoint(cp);
        return sb.toString();
    }

    /** Unescaped content for \Q..\E (the quote handles the escaping). */
    static String plainLiteral(SplittableRandom rnd) {
        StringBuilder sb = new StringBuilder();
        int n = rnd.nextInt(1, 4);
        for (int i = 0; i < n; i++) {
            sb.appendCodePoint(POOL_ASCII[rnd.nextInt(POOL_ASCII.length)]);
        }
        return sb.toString();
    }

    // ---- fact-gated rung families (generator v4: the per-batch family axis) ----
    //
    // The general generator above produces fact-gated rung coverage only by
    // accident: a batch whose parts all draw literals IS a literal needle,
    // but multi-char needles with embedded copies, needle+tail prefix
    // shapes and mask-free anchored shapes are vanishingly rare draws. The
    // four FAST rungs of the ladder — LITERAL (String.indexOf needle),
    // PREFIX (required-literal-prefix candidate walks), CAND_SCAN
    // (first-char-set bit scan, inputs <= 64 chars), ANCHORED_FAST
    // (engine-tier flat-table matches()) — each get a family-biased
    // generator below, and 1-in-8 batches (a fresh stream, a pure function
    // of the batch) generate from their family instead of the general
    // grammar. Family patterns are ASCII-only and wide-atom-safe by
    // construction, so the existing flag matrix (CI folds literals — those
    // batches exercise the documented gate-miss fall-through to
    // CAND_SCAN/EXACT_FROM), both lanes (the jur oracle-bomb guard passes
    // every family shape: no nested quantified wides), and the cross-rung
    // machinery apply unchanged. Non-family batches keep their v3 pattern
    // AND inputs byte-identical, so 7/8 of the caseSeed universe replays
    // stable; family batches change pattern+input together.

    /** One generated family case: family name, the pattern, and the needle
     *  the input transform embeds (null for the set-shaped families). */
    record FamPattern(String family, String pattern, String needle) {

        static FamPattern of(String family, String pattern, String needle) {
            return new FamPattern(family, pattern, needle);
        }
    }

    /** The family draw of a batch: null (not a family batch) or one of the
     *  four family cases. Pure function of the batch. */
    static FamPattern genFamily(long batch) {
        SplittableRandom r = new SplittableRandom(batch ^ FAM_SALT);
        if (r.nextInt(FAM_EVERY) != 0) {
            return null;
        }
        return genFam(r);
    }

    /** One family case from a seeded stream (the draw order is the contract:
     *  family roll, then the family's own shape draws — harnesses seed the
     *  stream with their own key and keep replay stable). */
    static FamPattern genFam(SplittableRandom r) {
        switch (r.nextInt(4)) {
            case 0 : {
                // LITERAL: a pure quoted needle, 1-8 chars — short needles
                // exercise the detector's below-threshold family, long ones
                // the indexOf rung itself.
                String needle = famNeedle(r, 1 + r.nextInt(8));
                return FamPattern.of("literal", "\\Q" + needle + "\\E", needle);
            }
            case 1 : {
                // PREFIX: a quoted 2-6-char needle + a divergence tail (the
                // tail makes the char after the needle non-singleton, so the
                // prefix chain detector stops there with the needle in hand).
                String needle = famNeedle(r, 2 + r.nextInt(5));
                return FamPattern.of("prefix", "\\Q" + needle + "\\E" + famTail(r), needle);
            }
            case 2 :
                // CAND_SCAN: a set-shaped first atom (class / alternation /
                // shorthand) + a short body + a trailing anchor literal.
                // Without the anchor a set-first shape usually matches AT
                // position 0 and the exact-from rung answers before the
                // bit scan runs; the anchor rides half present-in-body,
                // half complement chars (the positive and the scan-only
                // path). Inputs stay <= 64 chars (see famInput).
                return FamPattern.of("cand-scan", famSetAtom(r) + famBody(r, 0, 2) + famAnchor(r), null);
            default :
                // ANCHORED_FAST: a mask-free disjoint-range shape (no anchors,
                // no multiline flag in the harness's draws) for the
                // engine-tier flat-table matches() rung. 1-3 atoms, each
                // carrying its own quantifier — no trailing quantifier (a
                // second one on an already-quantified atom is invalid syntax).
                return FamPattern.of("anchored-fast", famBody(r, 1, 3), null);
        }
    }

    static String famNeedle(SplittableRandom r, int n) {
        StringBuilder b = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            b.append(FAM_LIT_ALPHABET.charAt(r.nextInt(FAM_LIT_ALPHABET.length())));
        }
        return b.toString();
    }

    /** The divergence tail after a prefix needle: half fixed pool (the
     *  pinned shapes), half composed — always carrying a set atom, so the
     *  tail actually diverges (a pure-literal tail would reduce the whole
     *  shape to the literal family and change the family's routing). */
    static String famTail(SplittableRandom r) {
        if (r.nextInt(2) == 0) {
            return FAM_TAILS[r.nextInt(FAM_TAILS.length)];
        }
        return r.nextInt(2) == 0 ? famSetAtom(r) + famBody(r, 0, 1) : famBody(r, 0, 1) + famSetAtom(r);
    }

    /** minAtoms..maxAtoms body atoms — set atoms and literals each carrying
     *  their own quantifier, occasional capture. Wide atoms appear bare
     *  (never inside a quantified group: the jur oracle-bomb guard would
     *  trip on the shape). */
    static String famBody(SplittableRandom r, int minAtoms, int maxAtoms) {
        StringBuilder b = new StringBuilder();
        for (int i = 0, n = minAtoms + r.nextInt(maxAtoms - minAtoms + 1); i < n; i++) {
            boolean capture = r.nextInt(6) == 0;
            String atom = r.nextInt(3) == 0 ? famSetAtom(r) : famLiteral(r) + famQuant(r, 2);
            b.append(capture ? "(" + atom + ")" : atom);
        }
        return b.toString();
    }

    /** A set-shaped atom — the first-char-set bit scan's trigger. */
    static String famSetAtom(SplittableRandom r) {
        int roll = r.nextInt(6);
        if (roll < 3) { // bracket class over 2-4 members (+ optional range)
            StringBuilder b = new StringBuilder("[");
            int n = 2 + r.nextInt(3);
            for (int i = 0; i < n; i++) {
                b.append(FAM_LIT_ALPHABET.charAt(r.nextInt(FAM_LIT_ALPHABET.length())));
            }
            b.append(']');
            return b + famQuant(r, 3);
        }
        if (roll == 3) { // alternation of 2-3 literals
            int n = 2 + r.nextInt(2);
            StringBuilder b = new StringBuilder("(");
            for (int i = 0; i < n; i++) {
                b.append(i > 0 ? "|" : "").append(famLiteral(r));
            }
            return b.append(')').toString() + famQuant(r, 3);
        }
        if (roll == 4) { // shorthand
            return switch (r.nextInt(6)) {
                case 0 -> "\\w";
                case 1 -> "\\d";
                case 2 -> "\\s";
                case 3 -> "\\W";
                case 4 -> "\\D";
                default -> "\\S";
            } + famQuant(r, 3);
        }
        return "." + famQuant(r, 2); // dot — wide but mask-free (DOTALL-safe)
    }

    static String famLiteral(SplittableRandom r) {
        char c = FAM_LIT_ALPHABET.charAt(r.nextInt(FAM_LIT_ALPHABET.length()));
        return c == '.' ? "\\." : String.valueOf(c);
    }

    static String famAnchor(SplittableRandom r) {
        String pool = r.nextInt(2) == 0 ? FAM_ANCHOR_PRESENT : FAM_ANCHOR_ABSENT;
        char c = pool.charAt(r.nextInt(pool.length()));
        return c == '.' ? "\\." : String.valueOf(c);
    }

    /** A quantifier roll (0 = none). Ranges draw max >= min+1 (the parser
     *  rejects inverted bounds — the family contract is valid syntax). */
    static String famQuant(SplittableRandom r, int noneWeight) {
        if (r.nextInt(noneWeight + 1) == 0) {
            return "";
        }
        return switch (r.nextInt(5)) {
            case 0 -> "+";
            case 1 -> "*";
            case 2 -> "?";
            case 3 -> { // {n,m}, m >= n+1
                int lo = r.nextInt(0, 3);
                yield "{" + lo + "," + (lo + 1 + r.nextInt(3)) + "}";
            }
            default -> "{" + r.nextInt(1, 3) + ",}";
        };
    }

    /** Family-biased input (the per-index transform is the contract):
     *  ASCII-dominant body + 10% unicode pool, then the family's embed —
     *  plain embed (idx 0/7), at start (1), at end (2), near miss (3 —
     *  needle + a snowman the tail cannot consume, the failed-walk path),
     *  doubled (4 — the I-probe iteration), truncated needle (5), one-char
     *  mutation (6). Set families draw a plain body; every family input
     *  stays <= 64 chars so the cand-scan rung keeps its window. */
    static String famInput(SplittableRandom r, FamPattern fam, int idx) {
        int len = 3 + r.nextInt(38);
        StringBuilder in = new StringBuilder(len + 16);
        for (int i = 0; i < len; i++) {
            if (r.nextInt(10) == 0) {
                in.appendCodePoint(POOL_UNICODE[r.nextInt(POOL_UNICODE.length)]);
            } else {
                in.append(FAM_INPUT_ALPHABET.charAt(r.nextInt(FAM_INPUT_ALPHABET.length())));
            }
        }
        String needle = fam.needle();
        if (needle == null) {
            return in.toString(); // set family: the plain body already carries candidates
        }
        switch (idx) {
            case 0, 7 -> in.insert(r.nextInt(in.length() + 1), needle); // plain embed
            case 1 -> in.insert(0, needle); // match at the start
            case 2 -> in.append(needle); // match at the end
            case 3 -> in.insert(r.nextInt(in.length() + 1), needle + '\u2603'); // near miss — ❄ consumes nothing
            case 4 -> in.insert(r.nextInt(in.length() + 1), needle).append(needle); // two matches — the I probe
            case 5 -> { // truncated needle — the prefix-miss path
                if (needle.length() > 1) {
                    in.insert(r.nextInt(in.length() + 1), needle.substring(0, needle.length() - 1));
                }
            }
            case 6 -> { // one-char mutation — the negative path
                StringBuilder mutant = new StringBuilder(needle);
                mutant.setCharAt(r.nextInt(needle.length()), '\u2603');
                in.insert(r.nextInt(in.length() + 1), mutant);
            }
            default -> in.insert(r.nextInt(in.length() + 1), needle);
        }
        return in.toString();
    }

    // ---- outcome bookkeeping ----

    static final class Outcome {
        final Case c;
        String oracle = "?", asm = "?", vm = "?";
        final List<String> exceptions = new ArrayList<>();
        /** Cross-rung findings (MISMATCH/ROUTED/THREW) — non-empty fails the
         *  case regardless of the oracle outcome. */
        final List<String> cross = new ArrayList<>();
        /** Determinism double-run findings (warm artifact vs itself,
         *  runner-immutability tripwire 2) — non-empty fails the case. */
        final List<String> memo = new ArrayList<>();
        /** Soak-side pre-shrink (opt-in {@code -Pfuzz.shrink=1}): the
         *  minimal same-kind repro, set on recorded failure records only. */
        String shrunkPattern, shrunkInput;

        Outcome(Case c) {
            this.c = c;
        }

        boolean failed() {
            if (!memo.isEmpty()) {
                return true;
            }
            if (!cross.isEmpty()) {
                return true;
            }
            if (!exceptions.isEmpty()) {
                return true;
            }
            if (oracle.startsWith("<")) { // re2j rejects: both engines must reject too
                return !asm.startsWith("<reject") || !vm.startsWith("<reject");
            }
            return !asm.equals(oracle) || !vm.equals(oracle);
        }
    }

    static final class Results {
        final long masterSeed;
        long cases, failures, bothReject, flagged, ciSuppAvoidedTotal, ciRangeAvoidedTotal, knownDivergence;
        long crossRung, crossRouting;
        long jurCases, jurFailures, jurSkipped;
        /** Generator v4's family axis: batches (not cases) drawn per family —
         *  the family-bias coverage line of summary.txt. */
        final Map<String, Long> famBatches = new LinkedHashMap<>();
        /** Jur-lane oracle-bomb guard hits: batches never executed (the
         *  counter keeps running; ndjson records cap at
         *  {@value #JUR_GUARD_RECORD_MAX} — the shape is one signature,
         *  every hit reproducible from its caseSeed). */
        long jurGuarded;
        int jurGuardRecorded;
        /** Recorded-records cap per JURLANE_SKIP reason (the count keeps
         *  running; overnight the skip kinds are frequent, and every
         *  skip is reproducible from its caseSeed once the shape is
         *  known; the v1 (?U) rejections that were ~7% of jur cases
         *  compare hard since the v2 scoped fold landed). */
        final Map<String, Integer> jurSkipRecorded = new LinkedHashMap<>();
        final Map<Layer, Integer> layerCounts = new EnumMap<>(Layer.class);
        long hangs, hangsOurs, hangsOracle;
        /** Failure records pre-shrunk into shrunkPattern/shrunkInput
         *  (soak opt-in -Pfuzz.shrink=1; see shrinkInto). */
        long shrunkRecords;
        double casesPerMinute;
        final Map<String, Sig> signatures = new LinkedHashMap<>();
        /** ndjson record cap for JURLANE_GUARD (one shape — breadcrumbs,
         *  not a log). */
        static final int JUR_GUARD_RECORD_MAX = 8;

        Results(long masterSeed) {
            this.masterSeed = masterSeed;
        }

        /** Count + (capped) record a guarded batch's cases. The cases stay
         *  caseSeed-addressable and replay-stable; a guard record documents
         *  WHY the case never ran (the live oracle would spin on it). */
        void recordGuarded(long batch, BatchJob job, Logs logs, long maxCases) {
            for (int i = 0; i < BATCH_K && (maxCases <= 0 || cases < maxCases); i++) {
                cases++;
                jurCases++;
                jurGuarded++;
                if (jurGuardRecorded < JUR_GUARD_RECORD_MAX) {
                    jurGuardRecorded++;
                    Outcome o = new Outcome(new Case(job.pattern(), job.inputs()[i], job.flags(), job.lane()));
                    o.oracle = "<guard: jur oracle-bomb>";
                    o.asm = "<not-run>";
                    o.vm = "<not-run>";
                    logs.failure(batch * BATCH_K + i, o, "JURLANE_GUARD (jur-oracle-bomb)", "JUR");
                }
            }
        }

        void record(long caseSeed, Outcome o, Logs logs) {
            // (generation-guard counters fold once per batch in run(), not here)
            if (o.c.flags() != 0) {
                flagged++;
            }
            if (o.c.lane() == Lane.JUR) {
                jurCases++;
            }
            if (!o.cross.isEmpty()) {
                // Cross-rung findings are oracle-independent (VM vs itself);
                // no known-divergence classification, no layered attribution.
                failures++;
                if (o.c.lane() == Lane.JUR) {
                    jurFailures++;
                }
                boolean routing = o.cross.get(0).startsWith("ROUTED");
                if (routing) {
                    crossRouting++;
                } else {
                    crossRung++;
                }
                // The kind rides the first word of the first cross entry (the
                // wrapper and stretched-input axes carry their own kinds in
                // the shared list); ROUTED and the forced-rung family keep
                // their cross names.
                String first = firstWord(o.cross.get(0));
                String kind = routing ? "CROSS_ROUTING"
                    : first.startsWith("MISMATCH") || first.startsWith("THREW") ? "CROSS_RUNG" : first;
                String sig = kind + " | shape~" + shape(o.c.pattern());
                Sig s = signatures.computeIfAbsent(sig, k -> new Sig(kind));
                s.total++;
                if (s.recorded < 8) {
                    s.recorded++;
                    shrinkInto(o, kind);
                    logs.failure(caseSeed, o, kind + " (" + firstWord(o.cross.get(0)) + ")", "CROSS");
                }
                return;
            }
            if (o.failed()) {
                // The jur lane first: the evidence-re-base domain exclusions
                // (CampaignFuzzer.jurLaneCase's reclassifications — WBS item
                // 6) and the jur-oracle's own limits. Everything else on the
                // lane is hard: no known-divergence swallow exists there.
                if (o.c.lane() == Lane.JUR) {
                    String skip = jurLaneDomain(o);
                    if (skip != null) {
                        jurSkipped++;
                        if (jurSkipRecorded.merge(skip, 1, Integer::sum) <= 8) {
                            logs.failure(caseSeed, o, "JURLANE_SKIP (" + skip + ")", "JUR");
                        }
                        return;
                    }
                    jurFailures++;
                }
                // Known-divergence classification is RELEASED-oracle-only: it
                // documents the released re2j lone-surrogate bugs, and the
                // patched fork exists to fix them — under it, every
                // divergence is a finding. The layer==PARSER cross-check
                // below is what keeps the classifier honest (whole stack
                // self-consistent, oracle alone differs); classification
                // without it would misattribute real findings whose pattern
                // merely contains a lone surrogate.
                String known = o.c.lane() == Lane.RE2J && RELEASED_ORACLE ? knownDivergence(o) : null;
                // Layered attribution (failure path only — zero soak cost):
                // re2j/sim/vm/asm vote; the verdict names the failing layer.
                // Runs at flags=0 on the RE2J lane only: its four columns
                // have no flag plumbing (and no jur reading — the comparator
                // compiles the re2j lineage), so attributing anything else
                // would name a layer under different semantics — worse than
                // no attribution. Those records carry layer=FLAGS (JUR);
                // fuzz.one replay shows the full picture for triage.
                String layerStr = "FLAGS";
                if (o.c.flags() == 0 && o.c.lane() == Lane.RE2J) {
                    var report = LAYERED.compare(o.c.pattern(), o.c.input());
                    layerStr = report.layer().name();
                    layerCounts.merge(report.layer(), 1, Integer::sum);
                    if (known != null && report.layer() == LayeredComparator.Layer.PARSER) {
                        // The known divergence is a PARSER-boundary semantics
                        // difference (whole stack self-consistent, oracle alone
                        // differs). Any other layer with a lone-surrogate pattern
                        // is a REAL finding wearing the same coat — e.g. a
                        // CONSTRUCTION-layer needle bug would otherwise be
                        // swallowed as "known".
                        knownDivergence++;
                        logs.failure(caseSeed, o, "KNOWN_DIVERGENCE (" + known + ")", layerStr);
                        return;
                    }
                }
                if (known != null && o.c.flags() != 0 && o.exceptions.isEmpty() && !o.oracle.startsWith("<")
                    && o.asm.equals(o.vm) && !o.asm.equals(o.oracle)) {
                    // flags≠0 carries no layer attribution (the comparator's
                    // four columns have no flag plumbing), so the PARSER
                    // standard degrades to stack self-consistency: both
                    // engines agree with each other, the oracle alone
                    // differs. Without this branch every CASE_INSENSITIVE
                    // stale-orbit case would surface as a failure signature.
                    knownDivergence++;
                    logs.failure(caseSeed, o, "KNOWN_DIVERGENCE (" + known + ")", layerStr);
                    return;
                }
                failures++;
                String kind = kindOf(o);
                String sig = kind + " | shape~" + shape(o.c.pattern());
                Sig s = signatures.computeIfAbsent(sig, k -> new Sig(kind));
                s.total++;
                if (s.recorded < 8) {
                    s.recorded++;
                    shrinkInto(o, kind);
                    logs.failure(caseSeed, o, kind, layerStr);
                }
            } else if (o.oracle.startsWith("<")) {
                bothReject++;
            }
        }

        /**
         * Soak-side shrink-at-record (opt-in {@code -Pfuzz.shrink=1}): a
         * failure about to be recorded is pre-shrunk, so the ndjson line
         * carries the minimal same-kind repro beside its reproducible
         * caseSeed fields — the overnight issue gate then files minimal
         * repros with zero triage. Runs on the recording (main) thread,
         * bounded by the shrink caps; a failure that does not reproduce
         * cold records unshrunk (its caseSeed still replays it warm).
         */
        void shrinkInto(Outcome o, String kind) {
            if (!SHRINK_RECORD) {
                return;
            }
            try {
                CaseShrinker.ShrinkReport rep = shrinkCase(o.c, firstWord(kind));
                if (rep != null && rep.changed()) {
                    o.shrunkPattern = rep.minimal().pattern();
                    o.shrunkInput = rep.minimal().input();
                    shrunkRecords++;
                }
            } catch (RuntimeException e) {
                // never let the shrink path corrupt the record being written
            }
        }

        /**
         * The jur lane's domain exclusions — the asserted reclassifications
         * of the evidence re-base (the same predicates
         * {@code CampaignFuzzer.jurLaneCase} applies; WBS item 6). A skip
         * means "outside the compared domain", never a divergence pass:
         * budget rejections and engine findings stay findings (identical
         * treatment to the re2j lane). Null = the divergence is real.
         * <ul>
         *   <li><b>oracle-threw</b> — java.util.regex blew up at match time
         *       (backtracker StackOverflowError): an oracle limit, not a
         *       divergence (the campaign's JUR_ORACLE_THREW soft).</li>
         *   <li><b>v1-ungreedy-scope — RETIRED</b>: the v2 scoped fold
         *       landed — {@code (?U:...)}/nested {@code (?U} spellings
         *       compile under the JUR reading and compare HARD through
         *       the u/U letter swap (a lane compile rejection is now a
         *       real finding, never classified here).</li>
         *   <li><b>group-participation-tail — RETIRED</b>: the family-6
         *       tail slice (the 4d audit's position-carrying ε-write gap)
         *       closed by the unbounded zero-width-exit split (finding 2's
         *       mirror) — the exiting iteration's writes ride a normal
         *       ε-edge at the loop's REST position and compare HARD.</li>
         *   <li><b>restart-tag-leak</b> — java's {@code Matcher.search}
         *       does not reset group writes between a find()'s failed
         *       restart attempts, so a stale span lands strictly OUTSIDE
         *       the accepted match (a legitimate write never does);
         *       skeleton-gated, checked on the F first match, every I
         *       entry and the R clause ({@link
         *       CampaignFuzzer#leakedRestartSpan}).</li>
         *   <li><b>nested-loop-capture</b> — java's backtracker restores
         *       the FIRST iteration's inner group for a quantified capture
         *       nested inside another quantified group ({@code ((a)*b)*}
         *       on {@code "abab"} &rarr; g2 0..1; the loop lineage reports
         *       the last). Skeleton-equal + the nested-quantified shape;
         *       the engine fix is the open TODO.</li>
         *   <li><b>groupcurly-stale-write</b> — java's
         *       {@code GroupCurly} backoff ({@code match0}) rewrites only
         *       the directly-quantified group's registers; a nested
         *       capture's {@code GroupTail} write is never restored, so a
         *       DISCARDED iteration's span reports through the accepted
         *       match (campaign 2026-10-24 finding 3, the {@code (X)*tail}
         *       in-span participation family; the strictly-outside twin is
         *       restart-tag-leak above, and the failed-restart variant of
         *       the same write lands here in-span). Skeleton-gated, shape-
         *       gated and signature-gated ({@link
         *       CampaignFuzzer#staleIterationSpan}); pinned both ways in
         *       {@code GroupCurlyStaleWriteParityTest}.</li>
         *   <li><b>surrogate-unit-semantics</b> — the lane's designed unit
         *       semantics vs java's pair-aware UTF-16 discipline (java's
         *       classes never match a surrogate unit inside a well-formed
         *       pair; a pair-aware JUR reading is the open engine work).</li>
         * </ul>
         */
        static String jurLaneDomain(Outcome o) {
            String p = o.c.pattern();
            if (o.oracle.startsWith("<exception")) {
                return "oracle-threw";
            }
            if (CampaignFuzzer.sameSkeleton(o.oracle, o.vm) && CampaignFuzzer.leakedRestartSpan(o.oracle, o.vm)) {
                return "restart-tag-leak";
            }
            if (CampaignFuzzer.sameSkeleton(o.oracle, o.vm) && CampaignFuzzer.STALE_ITERATION_SHAPE.matcher(p).find()
                && CampaignFuzzer.staleIterationSpan(o.oracle, o.vm)) {
                return "groupcurly-stale-write";
            }
            if (CampaignFuzzer.sameSkeleton(o.oracle, o.vm) && p.matches(".*\\(.*\\(.*\\)[*+{].*\\)[*+{].*")) {
                return "nested-loop-capture";
            }
            // the surrogate-unit family retired (pair-aware scans; see
            // CampaignFuzzer.jurLaneCase)
            return null;
        }

        /**
         * Documented re2j divergences (see TODO.md "Correctness"):
         * <ul>
         *   <li><b>lone-low interior starts</b> — re2j matches a lone-LOW
         *       surrogate pattern against the low half of a well-formed pair
         *       in the input (and thereby also finds EARLIER leftmost matches
         *       than a boundary-respecting engine). JDK agrees with us. This
         *       is a suspected re2j BUG (upstream issue drafted; fix on our
         *       fork: https://github.com/jemmix/re2j/tree/fix-surrogate-pair-interior-prefix):
         *       raw String.indexOf in the literal-prefix fast path lands on
         *       pair interiors, so only patterns that compile to a singleton
         *       literal prefix are affected — `\uDC21` matches where
         *       `\uDC21|\uDC22` (a strict superset!) and `[\uD800-\uDFFF]`
         *       do not, a monotonicity violation. The RANGE form avoids the
         *       fast path entirely (no single-rune prefix). We keep
         *       codepoint-boundary semantics either way.
         *   <li><b>lone-low surrogate patterns</b> — re2j's literal-prefix
         *       fast path lands on pair interiors; we keep
         *       codepoint-boundary semantics either way. Applies when both
         *       our engines agree with each other and the pattern contains
         *       a lone low surrogate.</li>
         * </ul>
         *
         * Fold divergences have NO entry — under any oracle: tdfa is
         * compiled with {@code Re2jUnicodeProvider}, whose fold universe
         * IS the oracle's own ({@code Unicode.simpleFold} walk, live), so
         * the two cannot disagree on folding. The patched oracle's guard
         * patch additionally makes the asymmetric-orbit family
         * fold-inert there; the released oracle still cannot compile
         * those literals — see {@code releasedFoldHang}.
         */
        static String knownDivergence(Outcome o) {
            String p = o.c.pattern();
            for (int i = 0; i < p.length(); i++) {
                char c = p.charAt(i);
                if (c >= 0xD800 && c <= 0xDBFF) {
                    i++;
                    continue;
                } // well-formed pair: interior low is not a lone low
                if (c >= 0xDC00 && c <= 0xDFFF && o.asm.equals(o.vm) && !o.asm.equals(o.oracle)) {
                    return "re2j matches lone-low pattern at/into pair interior; JDK agrees with us";
                }
            }
            // NOTE: plain (?i) applies full Unicode simple folding exactly
            // like re2j (literals, explicit classes, and word shorthands;
            // verified against re2j 1.8), so any fold divergence is a real
            // bug.
            return null;
        }

        static String kindOf(Outcome o) {
            // Budget rejections ("pattern too large: ...") are one class
            // wherever they surface: at compile time ("<reject:...>") or
            // deferred to the first matches() when only the lazily compiled
            // anchored TDFA trips the budget ("<exception:...>"). Both are
            // the documented fuzz-scoped budget contract, not divergences.
            for (String e : o.exceptions) {
                if (e.contains("pattern too large")) {
                    return "BUDGET_REJECT";
                }
            }
            if (!o.memo.isEmpty()) {
                return "MEMO_STATE_MISMATCH";
            }
            if (!o.exceptions.isEmpty()) {
                return "EXCEPTION";
            }
            if (o.oracle.startsWith("<")) {
                return "COMPILE_PARITY (re2j rejects, tdfa accepts)";
            }
            if (o.asm.startsWith("<exception") || o.vm.startsWith("<exception")) {
                return "EXCEPTION";
            }
            if (o.asm.contains("pattern too large") || o.vm.contains("pattern too large")) {
                return "BUDGET_REJECT";
            }
            if (o.asm.startsWith("<reject") || o.vm.startsWith("<reject")) {
                return "COMPILE_PARITY (tdfa rejects)";
            }
            if (!o.asm.equals(o.oracle) && !o.vm.equals(o.oracle)) {
                return "RESULT_MISMATCH (both engines, probe " + probeDiff(o.oracle, o.asm) + ")";
            }
            if (!o.asm.equals(o.oracle)) {
                return "RESULT_MISMATCH (asm only, probe " + probeDiff(o.oracle, o.asm) + ")";
            }
            return "RESULT_MISMATCH (vm only, probe " + probeDiff(o.oracle, o.vm) + ")";
        }

        /** Which probe diverged: walk to the first differing char, then back
         *  to the nearest probe tag. Sound because the protocol carries no
         *  free text (spans only) — an F/I/M/L/R char IS a tag. */
        static char probeDiff(String a, String b) {
            int min = Math.min(a.length(), b.length());
            int i = 0;
            while (i < min && a.charAt(i) == b.charAt(i)) {
                i++;
            }
            for (int j = Math.min(i, a.length()) - 1; j >= 0; j--) {
                char c = a.charAt(j);
                if (c == 'F' || c == 'I' || c == 'M' || c == 'L' || c == 'R') {
                    return c;
                }
            }
            return '?';
        }

        /** Coarse shape for signature dedup: structural chars only. */
        static String shape(String pattern) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < pattern.length(); i++) {
                char ch = pattern.charAt(i);
                if ("()[]|*+?{}".indexOf(ch) >= 0) {
                    sb.append(ch);
                }
            }
            return sb.length() > 24 ? sb.substring(0, 24) : sb.toString();
        }

        void writeSummary(Logs logs) {
            logs.summary(this);
        }
    }

    static final class Sig {
        final String kind;
        int total, recorded;

        Sig(String kind) {
            this.kind = kind;
        }
    }

    // ---- logging ----

    static final class Logs implements AutoCloseable {
        private final Path dir;
        private final PrintWriter failures, progress;
        /** Continuous low-overhead JFR ("default" settings, in-memory
         *  circular, ~1%) dumped to out/hang-<caseSeed>.jfr when a hang is
         *  recorded — the sacrificed thread's method-sample profile ships
         *  WITH the record, no re-run needed. -Dfuzz.jfr=false opts out. */
        private final Recording hangWatch;

        private static final RuntimeMXBean RUNTIME_MX = ManagementFactory.getRuntimeMXBean();
        private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

        Logs(Path dir) throws IOException {
            this.dir = dir;
            Recording watch = null;
            if (Boolean.parseBoolean(System.getProperty("fuzz.jfr", "true"))) {
                try {
                    watch = new Recording(Configuration.getConfiguration("default"));
                    watch.setName("fuzz-hang-watch");
                    watch.setMaxSize(64L << 20); // circular, in-memory
                    watch.start();
                } catch (Throwable t) {
                    watch = null; // never let diagnostics kill the soak
                }
            }
            this.hangWatch = watch;
            // fuzz.append: keep failures.ndjson/progress.log across chunked
            // soak runs (scripts/fuzz-soak.sh); summary.txt always reflects
            // the latest chunk.
            StandardOpenOption[] opts = Boolean.getBoolean("fuzz.append")
                ? new StandardOpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.APPEND}
                : new StandardOpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING};
            failures = new PrintWriter(Files.newBufferedWriter(dir.resolve("failures.ndjson"), opts), false);
            progress = new PrintWriter(Files.newBufferedWriter(dir.resolve("progress.log"), opts), true);
        }

        void failure(long caseSeed, Outcome o, String kind, String layer) {
            // ts/upMs on every record: uptime correlates with gc-*.log
            // ([574,404s] prefixes) and makes intra-chunk clustering (the
            // humongous-GC hang signature) visible without progress.log
            // cross-referencing.
            failures.println("{\"caseSeed\":" + caseSeed + ",\"ts\":\"" + TS_FMT.format(Instant.now()) + "\",\"upMs\":"
                + RUNTIME_MX.getUptime() + ",\"lane\":\"" + o.c.lane() + "\",\"flags\":" + o.c.flags() + ",\"kind\":\""
                + kind.replace('"', '\'') + "\",\"layer\":\"" + layer + "\"" + ",\"pattern\":\"" + escape(o.c.pattern())
                + "\",\"input\":\"" + escape(o.c.input()) + "\",\"oracle\":\"" + escape(o.oracle) + "\",\"asm\":\""
                + escape(o.asm) + "\",\"vm\":\"" + escape(o.vm) + "\""
                + (o.shrunkPattern == null ? ""
                    : ",\"shrunkPattern\":\"" + escape(o.shrunkPattern) + "\",\"shrunkInput\":\""
                        + escape(o.shrunkInput) + "\"")
                + (o.exceptions.isEmpty() ? ""
                    : ",\"ex\":" + o.exceptions.stream().map(e -> "\"" + escape(e) + "\"").toList())
                + (o.cross.isEmpty() ? "" : ",\"cross\":" + o.cross.stream().map(e -> "\"" + escape(e) + "\"").toList())
                + "}");
            failures.flush();
        }

        /** A case whose worker never returned within the watchdog: pattern,
         *  input and the worker's live stack, for post-mortem. Classified:
         *  a stack inside our engine packages is an ENGINE hang (a finding);
         *  anything else is oracle/system slowness (e.g. re2j's (?i) class
         *  fold expansion over wide ranges). NOTE: the harness frames
         *  ({@code io.github.jemmix.tdfa.fuzz.*}) wrap EVERY worker stack —
         *  oracle hangs included — so they must not count as "ours" (else
         *  oracle hangs get misattributed to the engine). */
        void hang(long caseSeed, Case c, Results r, Thread w) {
            StringBuilder st = new StringBuilder();
            if (w != null) {
                for (StackTraceElement e : w.getStackTrace()) {
                    st.append(e).append(" | ");
                }
            }
            boolean ours = isEngineStack(st);
            if (ours) {
                r.hangsOurs++;
            } else {
                r.hangsOracle++;
            }
            // Spin vs stall, recorded at the source: a worker that BURNED CPU
            // for the whole watchdog window really hung (finding); one whose
            // thread-CPU is far below wall was stalled — GC pause/compaction
            // or scheduler starvation (stalled records replay in
            // milliseconds). Post-mortem triage reads cpuMs vs the 10 s
            // watchdog, no re-run needed.
            long cpuMs = -1;
            if (w != null) {
                try {
                    cpuMs = ManagementFactory.getThreadMXBean().getThreadCpuTime(w.getId()) / 1_000_000;
                } catch (Throwable ignore) {
                    /* thread died already */ }
            }
            String verdict = cpuMs < 0 ? "unknown" : cpuMs >= CASE_TIMEOUT_MS / 2 ? "spin" : "stalled";
            if (hangWatch != null) {
                try {
                    hangWatch.dump(dir.resolve("hang-" + caseSeed + ".jfr"));
                } catch (Throwable ignore) {
                }
            }
            failures.println("{\"caseSeed\":" + caseSeed + ",\"ts\":\"" + TS_FMT.format(Instant.now()) + "\",\"upMs\":"
                + RUNTIME_MX.getUptime() + ",\"lane\":\"" + c.lane() + "\",\"flags\":" + c.flags() + ",\"kind\":\"HANG_"
                + (ours ? "ENGINE" : "ORACLE") + "\",\"cpuMs\":" + cpuMs + ",\"verdict\":\"" + verdict + "\""
                + ",\"pattern\":\"" + escape(c.pattern()) + "\",\"input\":\"" + escape(c.input()) + "\",\"stack\":\""
                + escape(st.toString()) + "\"}");
            failures.flush();
        }

        /** Engine-side verdict: any frame in our own packages, excluding the
         *  fuzz harness frames that always sit below the hung code. Oracle
         *  hangs show com.google.re2j frames there instead. */
        static boolean isEngineStack(CharSequence st) {
            for (String f : st.toString().split(" \\| ")) {
                if (f.startsWith("io.github.jemmix.tdfa.") && !f.contains(".fuzz.")) {
                    return true;
                }
            }
            return false;
        }

        void summary(Results r) {
            try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(dir.resolve("summary.txt")))) {
                w.println("masterSeed: " + r.masterSeed);
                w.println("lane pin: " + System.getProperty("fuzz.lane", "mix") + " (per-batch draw; jur = facade"
                    + " vs live java.util.regex, re2j = re2j surface vs re2j)");
                w.println("oracle: "
                    + (RELEASED_ORACLE ? "re2j 1.8 released"
                        : "re2j 1.8 patched fork (known-divergence classifier off)")
                    + " + live java.util.regex (jur" + " lane)");
                w.println("cases: " + r.cases + "  failures: " + r.failures + "  bothReject: " + r.bothReject
                    + "  flagged: " + r.flagged + "  knownDivergence: " + r.knownDivergence + "  hangsEngine: "
                    + r.hangsOurs + "  hangsOracle: " + r.hangsOracle + "  crossRung: " + r.crossRung
                    + "  crossRouting: " + r.crossRouting);
                w.println("jur lane: cases=" + r.jurCases + "  failures=" + r.jurFailures + "  domainSkips="
                    + r.jurSkipped + " (by reason: " + r.jurSkipRecorded + ")  guardSkips=" + r.jurGuarded
                    + " (jur-oracle-bomb)    re2j lane: cases=" + (r.cases - r.jurCases) + "  failures="
                    + (r.failures - r.jurFailures));
                if (!r.famBatches.isEmpty()) {
                    w.println("fact-gated family batches: " + r.famBatches);
                }
                w.printf("rate: %.1f cases/min%n", r.casesPerMinute);
                w.println("shrunk records: " + r.shrunkRecords
                    + (SHRINK_RECORD ? "" : "  (off — -Pfuzz.shrink=1 pre-shrinks recorded failures)"));
                w.println("ciSuppAvoided (known-gap constructs not generated): " + r.ciSuppAvoidedTotal
                    + "  ciWideRangeAvoided (oracle-hang guard): " + r.ciRangeAvoidedTotal);
                if (!r.layerCounts.isEmpty()) {
                    w.println();
                    w.println("failure layers (attributed):");
                    r.layerCounts.forEach((layer, n) -> w.printf("  %6d  %s%n", n, layer));
                }
                w.println();
                w.println("failure signatures (deduped):");
                r.signatures.forEach((sig, s) -> w.printf("  %6d  %s%n", s.total, sig));
            } catch (IOException ignored) {
            }
        }

        void progress(Results r, double mins) {
            progress.printf("t=%6.1fmin cases=%d failures=%d known=%d flagged=%d hangE=%d hangO=%d sigs=%d%n", mins,
                r.cases, r.failures, r.knownDivergence, r.flagged, r.hangsOurs, r.hangsOracle, r.signatures.size());
        }

        void flush() {
            failures.flush();
            progress.flush();
        }

        @Override
        public void close() {
            failures.close();
            progress.close();
            if (hangWatch != null) {
                try {
                    hangWatch.close();
                } catch (Throwable ignore) {
                }
            }
        }
    }

    static String firstLine(String s) {
        if (s == null) {
            return "";
        }
        int i = s.indexOf('\n');
        return i < 0 ? s : s.substring(0, i);
    }

    /** First whitespace-delimited token — keeps CROSS_* kinds signature-stable
     *  (MISMATCH/ROUTED/THREW) while the detail lives in the record. */
    static String firstWord(String s) {
        int i = 0;
        while (i < s.length() && !Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        return i == 0 ? "?" : s.substring(0, i);
    }

    /** ASCII-safe \\uXXXX escaping so overnight logs are reviewable anywhere. */
    static String escape(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c > 0x7E || c == '"' || c == '\\') {
                sb.append(String.format("\\u%04x", (int) c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Minimal non-String CharSequence: a plain char[] view (no StringBuilder
     *  internals, no Buffer position state — charAt is array indexing). The
     *  wrapper axis's third shape, shared with the campaign seq mode. */
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
}
