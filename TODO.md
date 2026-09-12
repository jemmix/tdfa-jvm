# tdfa-jvm — Roadmap

> 2026-08 restructure: 1.0 API shape landed — modules core/asm/facade,
> `io.github.jemmix.tdfa.Pattern` facade + `core.RegexEngine`/`CompiledRegex`
> evergreen tier, BYO-engine shells, PERL default + `longestMatch`, §7
> scaffolding under paper names (closureGtop/GtopCompare/utree). RE2 is now a
> test fixture in :tests:parity:re2j-suite.
>
> Follow-up rounds landed: ParseResult (capture side-channel killed), package
> homes (ast/regopt), Tdfa accessors (fields package-private; generated code
> reads via accessors), CompileObserver/CompilationReport, pinned-Unicode
> modules tdfa-unicode-6.0/17.0 (vendored UCD + deterministic generator),
> Automatic-Module-Names on all artifacts, dead-code sweep.
> 2026-08-18 correctness round: determinize fast-path (bombs: date/aws
> compile <3 s, latency guard <5 s), TNFA star topology mirrored to re2j's
> Prog (nested-quantifier submatch parity), Fowler testregex corpus
> vendored + longest-match capture parity suites (0 known divergences).
> Still pending (freeze-phase): japicmp baselines ×4, module-info for core,
> TdfaRunner static trace → per-engine (test instrument only), license
> headers, namespace move jemmix→tagmaton (gates the publish), budget-flag
> organization, first Maven publish.
>
> 2026-09-03 cleanup run: BT19 §7 scaffolding REMOVED (closureGtop/GtopCompare/
> utree/prectables — dormant since the 2026-08-18 NOT-NEEDED resolution; design
> recoverable from git history + the BT19 paper). All finished plan docs under
> docs/ deleted (PARITY-PLAN, REBAR-*, TDFA-OPTIMIZATIONS, PERF-BREAKDOWN, BT19
> — git history preserves them; historical log entries below that cite them are
> left verbatim). Vendored re2j extracted tree untracked — patched oracle jars
> now build on demand from the single committed archive
> (vendor/re2j-jemmix/build-patched.sh + :buildPatchedOracle). Dead tdfa.pending
> plumbing removed. rootProject.name set to 'tdfa'. The rebar suite's expected
> counts now resolve from the live patched-re2j oracle (dd3f3bc) — suite green
> with no hand-patched divergence counts. Benchmarks
> re-run and BENCHMARKS.md/README refreshed (2026-09-03): `scripts/bench-rebar.sh`
> classpath fixed for the module restructure (it silently broke Aug-18 — why the
> rebar artifacts had frozen at Aug-15), all artifacts recaptured, quick baseline
> re-captured post-cleanup; pre-restructure-era sub-10 ns headline tables retired
> to git history with a measurement-context note.

Everything between here and "done." When this list is empty, the library is
finished. See the [vision](README.md#vision).

## Execution plan (locked 2026-08-14)

Correctness-and-performance round, triaged by cost/benefit. Order is fixed;
each step commits separately with the full gate: unit + re2j parity + rebar
(220/220) + re2j-suite (≤ known failures) green; JMH-vs-baseline once P7 lands.

| Step | Item | Scope |
|---|---|---|
| 0 | Doc truth-sync + this plan | DONE in this commit |
| 1 | **C1** — zero-width-anchored alternation `[0,0]`-vs-`[0,1]` | last known correctness bug; clears the 2 remaining ExecTest failures; touches accept-priority logic P1 rebuilds, so it goes first |
| 2 | **P7** — JMH regression harness | ✅ `RegressionBench` (JMH, ~5–10 min) + `QuickBench` (plain main, ~15 s, same ops) + `scripts/bench-regression.sh [--quick|--jmh] [--capture]` + `scripts/bench-compare.py`; per-machine baselines in `benchmarks/baselines/`; quick threshold 15 %, JMH 10 % |
| 5 | **P1** — kill O(n²) dense-match extract | ✅ origin-tracking leftmost-start sim (`multiStateLeftmostStart`); extract walks directly from the leftmost start; leipzig findAll 2.2× |
| 6 | **P2** — hot-path allocation removal | ✅ per-thread scratch (sim buffers + regs pool); findAllDense.asm −17..−26% |
| 7 | **P4** — Latin-1 256-entry fast-path tables | ✅ walk paths use 256-entry tables (gated ≤8192 states), sims keep the tight 128-stride table (256-stride slowed ASCII scans 15-18%); findAllLatin1.vm −28%, .asm −17% |
| 8 | **P6** — compile-time package | ✅ CharClass normalize+binary search, sweep-line checkRangesDisjoint, prectables skipped (unused until BT19 §7), lazy second engines in re2j shim; compile.re2j −54% |
| 9 | **P5** — TABLE_SCAN viability | ❌ **NO-GO** (measured): dictionary scenarios are compile-bound 300:1 (`curated/12-dictionary/single`: c=17.2 s, r=47–56 ms — the run side TABLE_SCAN would optimize is noise; the 17 s needs DFA minimization, already a separate item); match-heavy scenarios (`lh3lh3-reb/*`, `i13-subset-regex/*`) compile in 2–18 ms → already INLINED mode; and the VM's flat `asciiTarget` (O(1) ASCII dispatch, which DELEGATE reuses) beats binary search on ASCII-heavy input — the documented reason `pickMode` never selects TABLE_SCAN. Revisit only if a workload appears that is simultaneously large-DFA AND match-time-bound. |

Deferred (explicitly out of this round): differential fuzzing (C2), POSIX
leftmost-longest activation (C4), literal prefilter (P3 — violates the
single-algorithm design goal), `map`+toposort cycle rejection, deterministic
compilation, multi-valued tags.

## rebar 5-engine benchmark round (locked 2026-08-14, after P5 no-go)

Goal: **beat re2j decisively, parity with java.util.regex** (reggie = reference).
Harness: `scripts/bench-rebar.sh fast|accurate` — `RebarBench` runs all 110
in-scope rebar scenarios under 5 engines (jur / re2j / reggie / vm / asm) with
their declared rebar models, count-verified vs jur, interleaved passes,
5-column tables (scan ms/MB + compile ms + geomeans + worst-10). Fast mode
(~2 min, 2M-char cap, min-of-3) = triage; accurate (overnight, full
haystacks, min-of-5) = decisions. Results: `benchmarks/results-rebar-fast.txt`.
Baseline geomean scan ratio: **vm 0.94–1.07x / asm 1.14–1.35x vs re2j;
vm 1.67–1.86x / asm 2.02–2.34x vs jur** — not decisive, not at parity.
Steady-state probe numbers (isolated JVM, min-of-4) are ground truth for
single-scenario claims; fast-mode rows run 2–5x hot vs probes on later
scenarios (JIT-cold + thermal — documented in the bench header).

Already winning: dictionary (758 vs jur 17628 ms/MB), redos-VM (8.5 vs jur
24036), quadratic-VM (4x re2j), dense scans (`ing` 14.7 vs re2j 32.9 ns/char),
lexer-veryl (10x re2j), i1095-ascii.

### Triage — four loss clusters, root causes confirmed by JFR + DFA dumps

| ID | Cluster | Evidence | Root cause (confirmed) |
|---|---|---|---|
| **W1** | Unicode wide-class scans: long-russian **7.2µs/char (267x jur)**, all-russian 0.9µs (32x), `\p{L}{256}` 3µs (15x; re2j equally bad), letters-ru 12x, i1095-unicode ~100x | JFR: 70% of samples in the linear range scan (`multiStateAnyMatch`/extract `for i<count`); dump: `(?u)\b\w{12,}\b` DFA has **avg 1939 / max 2800 range entries per state** | (a) Unicode classes materialize as ~1400–2800 unmerged codepoint ranges per DFA state (entries only mergeable when lo,hi,target,ops,mask ALL match — duplicated subset-union entries don't coalesce); (b) `rangesDisjoint` is a GLOBAL flag — one overlapping state poisons every state into the linear branch (JFR line 396); (c) Latin-1 table only covers c<256, so every Cyrillic/Greek/CJK char pays the full scan |
| **W2** | Literal search: `Twain` 6.6 ns/char vs re2j **0.26** (25x), CJK literal 18 vs 0.9 (20x); affects all sherlock/literal + some leipzig rows | probe: `zzqqxv` no-match = 7.8 ns/char — pure DFA stepping, no prefilter | No required-literal-prefix prefilter: every char pays a full DFA step while re2j/jur memchr-skip (revisits the deferred P3 decision — for the rebar goal, memchr-class hopping is table stakes, not a semantics change) |
| **W3** | ASM slower than VM on big-DFA scenarios: quadratic 1x/2x/10x asm 1074–1905 vs vm 356–547 (3x); redos simplified-long asm 155 vs vm 8.5 (10x) | bench table only (both backends equal on small DFAs) | Unknown — suspected DELEGATE-mode dispatch or INLINED extract path; needs its own profile |
| **W4** | Backref unicode (i1095 family): ~900 ns/char vs jur 8–10 (but 2x better than re2j) | probe | Fallback (backtracking) engine over wide classes — largely W1 in disguise; re-triage after W1 |

### Results (2026-08-15, all landed — see commits ad984c4..732a5c2)

| Item | Outcome |
|---|---|
| W1a drop dead gap-fillers | \p{L} 1369→684 entries max; \w 2800→1866 |
| W1b binary search + per-entry prefix-max-hi | all 5 scan sites O(log+overlap); long-russian 7164→117 ns/char (61x) |
| W2' lazy search-DFA trigger | memoized 512-cp BMP blocks, kill-point windows, per-Tdfa memo, caps+fallbacks; long-russian →16, zh-lit →9 |
| W2 exact-literal indexOf | literal-chain DFAs (self-loop check!): Twain 6.6→0.22 ns/char = re2j parity; README discloses |
| budgeted origin-sim | dense loops keep old speed (trigger only beyond 4096 chars); scanNoMatch −21..−25% |
| exact-walk-first (generic paths) | \p{L}{256} 1106→82 µs |
| W3 ASM-vs-VM | **artifact** — steady-state VM=ASM=0.7µs; fast-bench gaps are cold-JIT on µs-scale rows; accurate mode arbitrates |
| W1c BMP table for extract walks | **skipped** — measured unnecessary after W1b/W2' (long-russian beats jur at 16 vs 28 ns/char); the trigger's blocks already give O(1) BMP dispatch for scanning |

Fast-bench geomean (110 scenarios): **vm 0.57x / asm 0.82x vs re2j;
vm 0.94x / asm 1.35x vs jur** (from 1.07/1.35 and 1.86/2.34). Remaining
known gaps: µs-scale rows dominated by ASM cold-JIT (info-grade), i1095
\p{L}{256} walk cost (82µs vs jur 2µs — bounded-repeat DFA walking),
applyOps register cost (56% of redos profile — future regopt work).
Accurate overnight run = final arbiter.

### Kernel refactor round (2026-08-15, commits 858b2f6..bab3fde)

Goal: unify the search ladders (one brain in TdfaRunner), make ASM a
generated-transcription backend with own-loop leaves, generate the full
Pattern/Matcher/Regex tier under ASM ("we're not doing JIT, generate
everything"), and benchmark honestly. Scope locked with user: ASM stays
unless definite regressions; no default flip; results presented before
action.

| Phase | Outcome |
|---|---|
| P0 trace hook | TdfaRunner.Strategy enum recorded at ladder decision points; setTracing runtime toggle |
| P1 new emission | shared statics + private extractOne leaf (TABLESWITCH + inlined ops, String.charAt, no copy); deleted chars cache / SHORT_DELEGATE_LEN / O(n²) restart / TABLE_SCAN; non-fast → DELEGATE |
| P2 interfaces | Pattern/Matcher interfaces + VmPattern/VmMatcher + PatternSpi; generatesPerPattern capability; AsmEngineFactory named singleton |
| P3 generated tier | GenNRegex/GenNPattern/GenNMatcher per pattern in one classloader (unload together); writeReplace serialization proxy; public VmPattern/VmMatcher with protected state |
| P4 benchmarks | ShortFindBench asm/jur 0.77x geomean (vm 0.86x), asm/vm 0.89x; LogExtractMacro asm=vm warm, both 3-4x re2j; compile 301µs vs 52µs; metaspace unloads; baseline recaptured (old file had corrupted entries) |
| conformance | StrategyConformanceTest: 12 shapes × 20 boundary lengths × {core,shim} — identical results AND traces; caught the genMatches double-record pre-ship |

Known follow-ups: literal-prefix acceleration for medium inputs (jur wins
log-extract rows via ip=/path= Boyer-Moore shapes — same family as the
deferred P3/memchr item), us-scale test/* cold-JIT rows in rebar fast
(artifact), ASM compile latency (emission+classload; deterministic
compilation is the long-term lever).

### Short-input parity round (2026-08-15, commits d192462 + e949759)

JMH ShortFindBench (10 slugs × 5 engines, ≤64-char inputs) had vm/jur
geomean **1.48x** despite the haystack-scale parity — per-call machinery,
not scan throughput. Round outcome (results in
`benchmarks/results-shortfind-jmh.txt`):

| Item | Outcome |
|---|---|
| R1 first-char-set candidate scan (≤64 chars) | startBits bitset + exact walk in find/extract/leftmostStart; adaptive boolean pre-filter after 3 failed extract walks (emailNoMatch 634→1143 without it, 498 with); boundedSpan 310→82, caseiLit 176→59, ipExtract 492→132 |
| R5 word-flag trim | needsWordFlags gate (stop-table variant equality) + wordBits bitset; every PERL pattern without \b stops paying 2 word checks per posFlags |
| R3 ASM short-input delegation | genMatch → runner.match ≤64 chars; asm/vm anomaly (+25 ns constant) gone — alternation 299→72 |
| R2 shim matches() 51.6→9–64 ns | flat walk dispatch for disjoint DFAs in extractFrom/runStringMatchFrom + R5; residual vs jur (6–26) = MatchHolder/MatchResult + eager ops layers — declared VM corner |
| R4 lazy BMP walk blocks | per-state 512-cp blocks, volatile copy-on-grow publish, 64-block cap; wordUnicodeCls 135→83 |
| tryStartFast non-Latin bail | re-walks from the SAME start (extractFrom) instead of re-running the whole generic search |
| extractFrom pooled regs | candidate loops call it per candidate; failed walks no longer allocate |

JMH geomean: **vm/jur 0.85x / asm/jur 0.93x** (was 1.48x / ~2.0x);
vm/re2j 0.18x. Worst remaining rows: wordUnicodeCls 2.28x, boundedSpan
1.82x, emailNoMatch 1.61x (all also beat re2j 3–7x). CAND_SCAN_MAX=64
bounds the worst case at O(64²) walk steps; longer inputs keep the
budgeted-sim/trigger regime (unchanged, regression-gated).


### Bugs found & fixed during bring-up (regression tests came free)
- kill in rawScan left live set empty → masked ALL matches after first dead char (re2j-suite)
- capped-memo fallback restarted from bare seed → dropped in-flight configs, skipped real matches (leipzig 541 vs 543)
- a+ misdetected as literal "a" (self-loop check added; QuantifierParityTest)
- per-runner memo OOM'd the 1M-compile exhaustive suite (per-Tdfa + 2048-char window floor)

## Feature parity

- [x] Clear all pending parity tests — 0 remaining (was 41; all cleared: POSIX classes, escape rejection, byte[] overloads, split, DISABLE_UNICODE_GROUPS, matches() anchored groups, programSize, Serializable, `\A`/`\z` multiline invariance, re2j-exact Unicode provider). See `docs/PARITY-PLAN.md`.
- [ ] Add more parity tests — expand coverage to edge cases not yet exercised (backreference semantics, large repetition counts, nested quantifiers, Unicode line boundaries, canonical equivalents, etc.). Known-failing or not-yet-implemented cases are NOT committed behind gates: the suites hard-gate green, and open divergences live as checklist items here (the old `tdfa.pending` opt-in gate was removed with its last users).
- [x] Multiline mode `(?m)` — `^`/`$` at line boundaries (implemented `fa0e07d`; `\A`/`\z` immune via `8811166`)
- [x] **REFUTED bug candidate** (2026-08, post-mortem): "`\p{Lu}{3}`+ fails on
      Math-Fraktur" — engine was correct all along; the probe inputs were wrong.
      Three separate traps: (1) U+1D504 is Lu since Unicode 3.1 (first premise);
      (2) the Math Alphanumeric block has HOLES at letterlike-symbol duplicates —
      1D506/1D50B/1D50C/1D515/1D51D in the Fraktur caps run are UNASSIGNED
      (ℭ U+212D, ℌ U+210C, ℑ U+2111, ℊ U+210A, ℨ U+2128 are canonical), so
      "1D504 1D505 1D506" is not three letters; (3) Gothic (U+10330..) is
      caseless **Lo**, not Lu. Verified: distinct-assigned Fraktur Lu matches at
      {1}..{5}, {2,4}, lazy {2,4}?, Gothic matches `\p{Lo}{3}`, and all
      negatives hold — 12/12 with the JDK provider and pinned tables alike.
      Guardian test: `SupplementaryCodepointClassTest`.
- [x] ~~Full POSIX leftmost-longest — activate BT22 §7 `closure_gtop` winner selection~~
      — RESOLVED AS NOT-NEEDED for the API contract (2026-08-18, evidence-based):
      our contract is re2j drop-in parity, and re2j's own longest-mode submatch
      rule is "the match a backtracking search would have found first" (re2j
      RE2.java javadoc) — NOT POSIX greedy-left-to-right. Longest-mode capture
      parity now holds via 66 curated + 3K randomized in-suite cases
      (`LongestMatchParityTest`), 578 Fowler specs (`TestregexFowlerTest`), and
      a 200K-case both-modes soak: 0 disagreements, `closure_gtop` dormant
      throughout. The one real divergence found (nested same-greediness
      quantifiers, `(a*?)*?` — which iteration owns the group span) was a
      TNFA-topology issue, fixed by mirroring re2j's Prog star shapes
      (commit `0a88758`), not a disambiguation-rule gap. True POSIX submatch
      maximization remains tracked under the wishlist item
      "POSIX longest-leftmost capture groups".
- [x] Unicode case folding for literal chars — CaseFoldTable handles all BMP simple case folds (28 groups with >2 members including s↔ſ, k↔K, Ω↔ω). Class-range folding full-Unicode under `(?u)` (`5f22aee`).
- [x] `\b` / `\B` Unicode word boundary semantics for supplementary codepoints (`ba60194`)

## Correctness

### Design review: bug taxonomy of fuzz rounds 3-6 and prevention (2026-08-29)

Commits 72a95b4, 0236b6c, 12d9921, a58ea48 reviewed for CAUSAL patterns (not
case-by-case). Four classes, each with the prevention now in place or proposed:

1. **Deferred effects / collapsed runtime state** (eager φ, mask-before-ops,
   byMask winners): ops applied at a different time than the state they read
   was valid; per-position accept winners approximated by mask intersection.
   Prevention: BT22 declaration semantics — the register file is a function of
   the accepted path only — is now a stated invariant with hard gates
   (FinalOpsParityTest). Rule: every deferral-for-speed carries its equivalence.
2. **Approximation standing in for a semantic unit** (mask-groups approximating
   live-sets; specificity sort approximating priority; global multiline
   approximating per-edge flavor; scan-order conventions carrying semantics).
   Prevention: round 4 replaced them with the semantic objects (context
   live-sets, per-edge flavors, pike-cut, dead markers). House rule: an
   approximation's comment must state the EQUIVALENCE it claims (e.g. the
   subsumption cut's), not the local reason it was added — reviewers check the
   claim. `(^|$)+` regressed because the first cut was justified by graph
   shape, not semantics.
3. **Unprobed oracle claims** (ASCII-only folding labeled "re2j semantics").
   Prevention LANDED: `SemanticsContractTest` — curated (pattern, input)
   matrices per semantic area (folding, anchors, loop discipline) pinned to
   re2j. A wrong empirical claim about the oracle now fails a named test, not
   a soak. Probe-first is already the working habit; this makes it permanent.
4. **Hand-derived dependency models** (computeNeedsWordFlags vs ASM pfNeeded —
   three models of "which posFlag bits matter", each incomplete differently;
   the a58ea48 bug class recurs with every new M-indexed consumer).
   Prevention LANDED: `Tdfa.posFlagDeps()` — derived, not inferred: bits
   present in consumed masks ∪ bits whose flip changes any cell of any
   M-indexed table. Both tiers read the one number; the VM's scan and the
   ASM's model are deleted. A future M-indexed consumer is covered the moment
   it reads a distinguishing table — no model to keep in sync. (Side effect:
   the ASM's pfNeeded was over-broad — any Perl accepting state forced PF
   emission even with uniform stop tables; emitted code shrinks for such
   patterns.)

PERF (2026-08-30, post-audit tuning round): first full JMH baseline run
(vs jur/re2j/reggie) confirmed the audit's ranked wins; landed the top three:

- ASM regs pool: extractOne allocated a fresh int[regCount]+fill(-1) per
  candidate probe — top allocation cost on capture-heavy patterns (asm was
  SLOWER than our own interpreted VM on IPv4/\w+\s+\w+: 1301 vs 621ns).
  New final-class RegPool (core) + per-class static REGS_POOL: one
  monomorphic INVOKEVIRTUAL take(n) per probe, fill stays, success paths
  clone before returning (pool never escapes). EmittedBytecodePolicyTest
  enforced receiver finality — the first attempt (raw ThreadLocal in
  bytecode) was correctly rejected as a non-final receiver, and flushed out
  the test's own latent OPCODES[absolute-opcode] render bug.
- VM: stopNow-positionFlags guard — accept paths computed positionFlags
  (2 charAt + 2 word lookups) whose value the uniform stop table ignores
  (assertion-free case). Guarded at the three walk sites.
- VM sims: Arrays.fill(next) zeroed the whole grown Scratch buffer, not the
  nwords prefix (rawScan already did it right).
Result: asm capture-path ~8-9x faster (IPv4 1301→~150ns), vm scan/extract
~15-30x on contended-machine runs (allocation/GC-sensitivity removed —
post-fix numbers are tight across independent runs while pre-fix spread
3x between runs). Known remaining (not acted on): per-state sequential
range-compare chains in emitted dispatch (vs VM's flat table) — candidate
for a 128-entry ASCII switch arm; wide-class materialization (\p{L}{1,30}:
31ms, 20.5k transitions, states stay tiny) — bounded, acceptable.

FUZZ (2026-08-30, generator v3 — batched): one pattern per K=8 inputs.
Compile+codegen is ~45% of per-case cost (measured: 8.5ms/case full, 3.8ms
ours, 0.02ms re2j); amortizing it lifted sustained soak throughput from
~15-20k to ~140k cases/min (7-10x). The bigger win is coverage: per-index
deterministic boundary bias (trailing \n, space-padding, mid-string lone
surrogate, pair+ASCII adjacency, near-empty, all-pools) — the historical
bug families were input-position-sensitive and one random haystack per
pattern missed exactly those axes. caseSeed = batch*8+idx is bijective
(floorDiv/floorMod replay; fuzz.one unchanged); pre-v3 seeds are dead.
Two self-inflicted attribution bugs found on the first soak: batch*8+i must
not overflow Long (>>> 4 now — >>> 3 let batches ≥ 2^60 wrap negative; the
wrap is bijective so those seeds replay, but a shifted batch — recovered
this run by +2^61 arithmetic on the recorded value). v3's first findings:
(1) regopt liveness — paper's round-robin fixpoint with boolean[] rows cost
1.4s on an 11k-block CFG that converges in 3 rounds; rewritten as packed
long[] rows + predecessor worklist seeded in post-order (REGOPT stage
2.5s → 0.47s on the repro, total compile 4.5s → 0.85s). (2) OPEN,
deferred: determinizer compile-perf cliffs — tryMap×history tick volume
(Tdfa.java:2194 bijection loop; 4.3B-tick budget ≈ minutes at history()
granularity, far past the 10s fuzz watchdog), topologicalSort O(n^4) guard
(:2240), transitionRegops/fillKeySig volume. Not non-termination — the
WorkMeter eventually kills them with a clean "pattern too large". Repros:
fuzz.one=1287977190499177688 (transitionRegops… actually tryMap:2194),
9048506536898982008, 13826009915174294200, 11179075327814499928.

ROUND 9 (2026-08-31, overnight-soak parser family): nullable body under
open counted repetition reported the wrong final capture — (a?){2,} on
"aa" gave g1="" where re2j gives "a" (25 records, 16 patterns; whole stack
self-consistent = PARSER layer; star/plus/bounded {n,m} shapes were all
correct, only {n,} diverged).

ROOT CAUSE, by design not patch: our Tnfa desugared {n,} as x{n} followed
by x* — the star tail's greedy first-iteration-empty legitimately wins
priority and writes an empty capture. re2j's Simplify.java general case is
x{n-1} followed by x+ ("x{4,} is xxxx+"): the plus guarantees one real
iteration and the nullable body's empty RE-iteration is cut by the pike
pc-dedup, so the last NON-EMPTY capture survives. Fixed by mirroring the
expansion: (n-1) copies + Repeat(body,1,∞) — min>=2 only ({0,}=star and
{1,}=plus had their own cases). Probe matrix: 19 shapes (nested, lazy,
class bodies, leading/trailing context, star bodies) all agree with re2j
post-fix; jdk diverges from BOTH engines on this family (documented
oracle choice). Pinned: tests/unit CountedRepeatCaptureTest (literal
oracle-verified values, both tiers) + QuantifierParityTest rows.
All 8 recorded replay seeds: oracle==asm==vm.

Also from the overnight soak (deferred, evidence updated): 678 hangs +
576 budget-rejects in 123M cases — determinizer compile-perf cliffs,
dominated by tryMap×addState (410) and FallbackOps.accumulateClobbered
(91). Fresh minimal repro for the expansion side: the nested-counted bomb
((z.{0,1}.{3,5}?|.){1,4}?s){3} under (?:...){0,} — desugaring multiplies
bounded reps into budget rejection where re2j compiles fine. CONSTRUCTION
family (39 records: anchors under lazy/counted loops) still open.

ROUND 25 (2026-09-11): pre-overnight hardening — tiered watchdog +
faithful fuzz.one replays.
Open-item sweep before the next soak; two fixed, rest deferred:
- TIERED WATCHDOG (the round-21 proposal, now in): at 10 s probe the
  worker's thread-CPU. Spin (cpu >= wall/2) -> sacrifice + record
  immediately (real findings keep fast detection). Stall (cpu far
  below wall: GC pause, co-tenant load) -> grace to 60 s total
  (-Dfuzz.graceMs, 0 restores single-shot); the batch usually
  completes and NO record is written. Forced-stall smoke (5 ms
  timeout, 3 s grace, 40 cases): 2 records instead of ~5, verdicts
  split spin/stalled correctly.
- fuzz.one now applies the fuzz work budget (was: library 2^32 —
  round 24's seed replayed in 57 s and was mislabeled "clean";
  now 0.85 s with budget rejects). -Dfuzz.max.work=0 restores.
Deferred (documented, not blocking a soak):
- CFG successors deboxing (int[] vs List<Integer>, ~14% of the
  round-24 spin profile) — perf polish; the edge cap defused the
  pathological class, and the churn is freeze-bound risk.
- Shared Tdfa for asm+vm (halves engine compile churn) — engine API
  design change.
- ZGC-vs-G1 A/B — curiosity; allocation is down 63% and 0 Full GCs
  at 4 MB regions.
Gates green incl. rebar 226/0/2.

ROUND 24 (2026-09-11): first TRUE engine spin caught by the new
diagnostics — CFG successor-arc explosion; capped + ticked.
Overnight record caseSeed 727613823329836856: verdict=spin,
cpuMs=7161 (thread BURNED CPU, not stalled), upMs=295s (early
chunk — not the GC class; that chunk's gc log: 0 Full GCs), stack
in Optimize.livenessAnalysis. The shipped hang-*.jfr dump + solo
replay profile: liveness 40%, ArrayList$Itr.next 14% (boxed
successors), dfsPostOrder 10%, checkIndex 7%.
Shape (via new -Dtdfa.debug [cfg] print, kept as standing
diagnostics): 287-state DFA -> 22,637 CFG blocks (79/state:
φ-variant finals) -> **157,176,487 successor arcs** (~6,940 per
block). buildCfg materializes transitive zero-op reachability as
direct edges; liveness then does succ×word work per pop (~49K
ticks/pop -> the 8M scoped budget needs ~164 pops ≈ 7+s wall; at
library budget the whole compile ran ~60 s/engine). This is round
20's documented "successors explosion" open item — first real
specimen.
Fixes:
- buildCfg: per-ARC meter ticks (was per-BFS-node — materializing
  157M edges was nearly unticked) + absolute cap tdfa.max.cfg.edges
  (default 4M; sane shapes are orders below) throwing the standard
  "pattern too large ... budget exceeded" -> classified BUDGET_REJECT.
  Scoped 8M: rejects 0.75 s; library: 0.73 s (cap); the true seed
  replay: 0.69 s whole batch, both engines.
- CompileBudgetTest.cfgEdgeExplosionCapRejectsCleanly pins the
  shape (ASCII via \x{...} escapes, lone surrogates included).
- fuzz-soak.sh replay verdict no longer trusts exit 0 alone (this
  round's auto-replay said REPLAYS-CLEAN on a 57 s replay): wall
  <10 s = clean, 10-120 s = SLOW-BUT-COMPLETES (budget monster),
  else triage.
Gates green incl. rebar 226/0/2.
META: transcribing fuzz patterns by hand from \uXXXX JSON failed
TWICE (\db misread for \b; dash escaping flipped) — always
regenerate from the caseSeed (fuzz.one) or convert mechanically
(ASCII verbatim so regex escapes stay operators, \x{hex} only for
non-ASCII; escaping the introducers themselves silently changes
the language: \x{5c}D is literal-backslash-D, not \D).

ROUND 23 (2026-09-10): standing diagnostics — if the hang/GC-pressure
class ever recurs, the artifacts now answer it without a re-run.
- Every failure record (ndjson) carries ts + upMs: uptime lines up
  with gc-<pid>.log [NNNs] prefixes and makes intra-chunk clustering
  (the humongous-GC signature) visible directly from the records.
- -PfuzzGcLog=true (fuzz-soak passes it always): -Xlog:gc=info,
  safepoint=info into <outDir>/gc-<pid>.log, rotated 5x20m, per chunk
  JVM. NOTE the -Xlog syntax gotcha: output OPTIONS need their own
  segment after an EMPTY decorators segment
  (:file=...::filecount=5,filesize=20m) — with one colon the JVM
  parses them as decorators and refuses to start (caught live).
- HANG records dump an in-process JFR recording (default settings,
  64MB in-memory circular, ~1% overhead, -Dfuzz.jfr=false to
  disable) to <outDir>/hang-<caseSeed>.jfr: the sacrificed thread's
  method samples ship WITH the record. Smoke-tested with
  -Dfuzz.caseTimeoutMs=5 -Dfuzz.cases=30: 5 hangs, 5 dumps, records
  carry cpuMs/verdict/ts/upMs; dumps open with valid
  ExecutionSample events.
- verdict heuristic caveat: cpuMs >= CASE_TIMEOUT_MS/2 => "spin".
  At the real 10s watchdog that threshold is 5s — fine. Forcing
  tiny timeouts in smoke tests will label ~everything "spin";
  expected, the field is a hint not a gate.
Gates green incl. rebar 226/0/2.
META: jdk.jfr Recording.setSettings takes Map in JDK 26 — use
new Recording(Configuration.getConfiguration("default")).

ROUND 22 (2026-09-10): allocation root-cause — exact-size scratch
growth in TdfaStateIndex was 56% of all fuzzer GC pressure.
JFR ObjectAllocationSample on a 3-min slice (seed 491362533,
~102 GB sampled = ~34 GB/min): tryMap 37.9% + canonSignature 17.8%,
ALL int[]. Cause: closure sizes and the global register count
(nextReg) creep up ONE unit at a time during compilation, and the
scratch arrays were grown to EXACT size on every crossing — the
tryMap quartet (mapNewToOld/mapOldToNew/epochNew/epochOld), canon
classScratch, canonKeyStamp/Class, stampedRegs, hasHistShared —
O(N^2) bytes per compile, twice per case (asm + vm compiles).
Fixes (TdfaStateIndex only, semantics-neutral):
- all scratch growth now slack (doubling / next-pow2, floors 8-64);
- hasHistShared allocated RAGGED (new long[rows][]) — the old
  fully-zeroed long[rows][words] threw away every inner array
  because each row is immediately replaced by a hist-cache ref;
- canon class-signatures deduped per class (canonByClass map,
  read-only shared stored copies) instead of a detach
  Arrays.copyOf per addState.
Measured after: total sampled 37.8 GB (-63%), tryMap/canon gone
from the profile; residual leaders are legit per-compile work data
(Optimize arrays, Configs stored in states, closure walk tables).
3-min slice: 0 mismatches, 0 hangs, 256 BUDGET_REJECTs (known
class, same rate), throughput unchanged ~367k cases/min (box is
CPU-bound; the win is GC pressure headroom + the churn-cliff
removal on big compiles, which the watchdog budget then sees
earlier). Gates green incl. rebar 226/0/2.
Next structural lever (not taken): the fuzzer compiles every
pattern TWICE (asm + vm) through full Pattern.compile — one shared
Tdfa with two runners would halve the remaining engine-side churn,
but that is an engine-API design change, not a scratch fix.
META: jfr print stack frames have NO "at " prefix (JDK 26) and
fields carry leading spaces — two parser iterations died silently
before the profile landed; verify parse output non-empty before
believing "0 MB".

ROUND 21 (2026-09-10): overnight 491362528 hang triage — no spinning
regexes; GC-humongous stalls crossing the watchdog. Three-layer fix.
9 HANG_ENGINE + 2 HANG_ORACLE records, all in the last ~5 min of
30-min chunks. ALL 11 caseSeeds replay clean in milliseconds solo.
Stacks spread over 9 unrelated engine phases (stepOnSymbol, addState,
canonSignature x2, transitionRegops, buildCfg, epsilonClosure,
growVisited, tryMap) — random parking, i.e. stalls not spins. Two
records are ORACLE hangs (one inside re2j's own write()) — impossible
to blame our engine. Local 30-min repro attempt (same config, GC log
via jcmd VM.log): 0 hangs, but 4 Full GCs (0.7-0.9s) after t~24min,
first at ~1GB used with ~1GB free and 68M in humongous regions —
the bomb-family kernel/state arrays (0.4-1.2MB) are humongous at G1's
default 1-2MB regions; fragmentation forces compaction exactly when
the hangs cluster. Mechanism: a 4-6s bomb batch (two engine compiles
tripping the 8M budget + anchored + oracle) overlaps a Full-GC pause
plus compaction CPU steal -> >10s wall -> watchdog sacrifices the
worker and records a hang.
Fixes:
- fuzz JVM: -Xmx4g -XX:G1HeapRegionSize=4m (state-sized arrays become
  regular; 12-min same-seed validation: 0 Full GCs, 0 humongous vs
  1 Full + 68M before; hangE=0).
- HANG records now self-classify: cpuMs (sacrificed thread's CPU) and
  verdict=spin|stalled|unknown — a stall burns little CPU, a real
  spin burns ~wall. Triage no longer needs the replay to know.
- fuzz-soak.sh: post-soak verification pass replays every recorded
  HANG caseSeed solo (-Pfuzz.maxWork=8388608 mirrors the scoped
  budget; fuzz.maxWork passthrough added to the gradle task) and
  writes hang-replays.log verdicts.
Also: fuzzWorkBudget comment said 32M while the code says 8M — fixed.
META lessons: (a) GC-log pause times print with LOCALE DECIMAL COMMAS
(719,033ms is 719ms, not 719 seconds — do not panic-read); (b) macOS
/usr/bin/time has no -f (GNU-only) — a failing time -f silently runs
nothing and greps print empty; use -p or verify exit; (c) jcmd VM.log
reconfigures -Xlog on a LIVE JVM when you forget the flag at launch.

ROUND 20 (2026-09-09): overnight 858426163 hang triage — thresholds
worked, calibration didn't; regopt/CFG phases now ticked.
One HANG_ENGINE record (caseSeed 573664345500922672, pattern
(?:(?:\A(?:z(?<n2>vt\w).*9.*?|\B\s{2}.)+)){2}, stack in regopt
livenessAnalysis). Reproduced on the exact overnight tree (git worktree
at dd3f3bc): find-compile 18.6s @2^32 — determinization is CHEAP (1389
states, 3.65M ticks); the cost is post-det: buildCfg (unticked CFG
construction, JFR 35%) + liveness fixpoint (~50 passes: successors lists
are large — each block BFS-collects zero-op reachables) with per-op work
unticked. Wall-per-tick in those phases was ~40x the det phase, so the
8M fuzz budget took 6+s per engine to trip; a fuzz batch pays it TWICE
(asm + vm) -> 12s+ > the 10s watchdog: hang recorded mid-flight,
sacrificed threads ran to their thresholds. NOT a broken ticker — a
calibration gap, same class as round 16.
Fixes (current tree):
- propagateBackwardW: per-op ticks + caller-provided result buffer (no
  clone per call).
- livenessAnalysis: zero-allocation row stores (displaced rows recycle
  as the spare buffer) — the churn was ~25% of wall.
- buildCfg: per-state, per-(state,range), and per-BFS-node ticks;
  decodeOps per-op ticks (op-object allocation was the copyOf hotspot;
  method became instance to reach the meter).
Measured: 8M wall-to-threshold 6.6s -> 2.1s; both engines fit the 10s
watchdog -> this family stops producing hang records. Library-budget
compile stays ~15s (real work ~15M ticks; the successors explosion in
buildCfg is the open perf item if it matters in practice). Replay of the
recorded caseSeed clean on all tiers; 3-min slice on the overnight's
master seed: 0 hangs, 0 known, 0 result mismatches. Gates green
(incl. rebar 226/0/2).
META lessons: (a) the pb probe dir had been wiped by temp cleanup and
java -cp silently ClassNotFound-ed in 0.07s — two "fast" measurements
were artifacts until cross-checked; probes now live in pb2 and outputs
are validated for the expected marker line. (b) The other session's
Sep-9 refactor (god-file split, b7267c1) shifted all line numbers — hang
stacks must be mapped against the COMMIT THE RUN USED, not HEAD.

ROUND 19 (2026-09-03): rebar parity on the live patched-re2j oracle.
Found the rebar suite RED (my gate set never included :tests:parity:rebar):
test/unicode/case/ascii-only wanted 0 (java/hotspot corpus entry: ASCII-only
(?i)) while we match re2j (full Unicode simple folding folds U+017F) -> 1.
Root issue: the suite compared a re2j-semantics engine against STATIC corpus
counts recorded by other engines at other times — every re2j-compat
divergence needed a hand-patched count, and the patches had already rotted
(docs referenced vendor/patches/rebar/01-dot-matches-byte-codepoint.patch
which no longer exists — consolidated away at 6e3b63f).
Fix: `want` now comes from the VENDORED PATCHED re2j (fix1/fix2 jar) LIVE,
for every non-unicode scenario re2j can compile — same model loops against
com.google.re2j.Pattern (count/spans/captures/grep/grep-captures twins).
unicode=true scenarios keep corpus/java-hotspot resolution (re2j has no
UNICODE_CHARACTER_CLASS); re2j-unrunnable regexes (backrefs/lookaround)
fall back to corpus; -Dtdfa.test.rebar.oracle=corpus restores static mode.
Suite: 226 pass / 0 fail / 2 bomb-skips — ascii-only and dot-matches-byte
both pass via re2j-live. This is exactly the fuzzer's contract (differential
vs patched re2j) applied to the curated corpus, and it retires the
JDK/Unicode-DB-drift count patches (03/04/05 stay only for the corpus-
fallback class). Docs updated (PARITY-PLAN live-oracle section; stale 01
patch references de-staled). Gates now include :tests:parity:rebar.

ROUND 18 (2026-09-03): overnight 645958308 triage — nested-lazy {n,m}
priority + a 90x determinization collapse as a side effect.
480x1min chunks, ~300M cases accumulated: 0 hangs, 0 known, 0 oracle
hangs; 54384 BUDGET_REJECT (6798 distinct — the bomb family, now fast
deterministic records); ONE real finding (2 records, same pattern):
((.{1,5}?[ space-newline-U+11C07-spaceclass]?){0,5}?)z — vm==asm==sim
reported the inner-lazy EXTENSION span for g2 where re2j AND JDK report
the NEXT COPY's (g2=last char vs the 2-char prefix).
ROOT CAUSE 1 (Tnfa.buildRepeat): our {n,m} desugared to a FLAT tail
B?B?B?; re2j Simplify nests it RIGHT: (x(x(x)?)?)?. Match-equivalent,
but the flat chain resolves the priority tie "enter next optional copy"
vs "extend current copy's inner lazy body" the opposite way. Mirrored
the nested suffix.
ROOT CAUSE 2 (PikeSim): the sim applied ntags destructively
(cap[-tag]=-1). re2j's pike prog NEVER writes empty captures on skip
edges — a capture persists once set (((a)?x){2} on "axax": g2="a";
((a)|b)+ on "ab": g2="a"); unmatched groups are null by absence. The
determinizer already had this right (ntags never enter l). Sim no
longer touches cap for negative tags (POSIX compare still gets them via
the utree path).
SIDE EFFECT (big): the nested suffix collapsed the plain nested-counted
bomb family — (a{1,100}){1,100} went OOM/117s -> compiles in ~1s at
10001 states / 148K kernels (was 19.6M kernels); n=50 2.9s -> 0.22s.
The remaining fuzz budget-rejects are the true cross-product family
(alternation inside counted reps, 224/304 on this seed). CompileBudgetTest
reworked: alternation bomb replaces the plain nested ones (they now
COMPILE — pinned by nestedCountedNowCompiles), closure-spike test uses a
13-arm alternation, kernel-override test recalibrated 100K -> 20K cap.
Pinned in QuantifierParityTest (4 shapes, all tiers). Gates green; 4-min
fuzz slice on the overnight seed: 0 failures/0 hangs/0 known, both
recorded caseSeeds replay clean.
META: the first attempt at this entry wiped TODO.md (surrogate chars in
the heredoc hit a UnicodeEncodeError mid-write after truncation; the
empty file rode along in 06869bc). Restored from 06869bc~1; lesson:
never let the TODO writer embed lone-surrogate text, and write via
temp+rename.

ROUND 17 (2026-09-02): NFA pre-walk explored and measured — negative result.
Built a pure subset-construction probe (long[] bitsets, global breakpoint
classes, no tags/regs/histories, hash-deduped closures; PureProbe in the
probe dir) to test "walk the NFA graph and predict the explosion
allocation-free". Result: the hang bombs' NFA graphs are combinatorially
SMALL — 357-1879 distinct pure closures (one 83k) while their real
determinizations exceed 100k states; legit (a{1,100}){1,100} has 10001
pure subsets and compiles fine, so no threshold separates them. For
tag-simple shapes pure == real ((a{1,50}){1,50}: 2501 == 2501 — the
determinizer merges optimally when histories don't vary). The bombs'
explosion lives in the tag-history/register dimension (l-variants ×
register assignments × assertion masks ~300x over a ~350-state skeleton),
which only the full determinizer models — there is no cheap graph-side
lower bound. Containment stays: metered ticks + state/kernel/closure caps
+ the fuzz-scoped work budget (round 16).

ROUND 16 (2026-09-02): no more background spin — full work-meter coverage +
fuzz-scoped budget (deterministic, throttle-immune).
User request after a 5-min run with 6 engine hangs: make them not spin.
Watchdog cancellation was rejected (time-based = unpredictable under
throttling). Deterministic route taken instead:
- NFA-size pre-walk MEASURED AND REJECTED as discriminator: hang patterns
  have 60-533-state NFAs while legit (a{1,100}){1,100} has 20k — the
  blowup is in determinization dynamics, not NFA size.
- Tick-density fixes (a tick must roughly match work density or the
  budget trips long after the wall does): FallbackOps.accumulateClobbered
  per-edge + per-op (was UNMETERED — the >150s family; now clean
  work-budget reject at 2^32 in 117s, and in ~1-3s under the fuzz cap);
  stepOnSymbol per (config,symbol); tryMap bijection per (config,tag);
  ops-rewrite per op; topologicalSort per iteration; canonSignature per
  (config,tag) + its boxed classIdMap replaced by epoch-stamped arrays
  (boxing made ticks so expensive the watchdog beat the budget);
  regopt registerAllocation gets the meter (phase-2 O(n^2) pair scan
  ticked); livenessAnalysis per (successor, word).
- Fuzz-scoped work budget: tdfa.max.work defaults to 8M ticks inside
  DifferentialFuzzer.run (set/restored around the run; -Dfuzz.max.work=0
  restores the library 2^32). Bombs reject in ~1-3s — under the 10s case
  watchdog, so no sacrificed threads spinning until cap/chunk death.
Result on the user's exact seed (41347903, 5 min): 6 hangs -> 0 hangs,
0 oracle hangs, 0 known, 128 BUDGET_REJECT records (the documented
nested-counted class, now visible as fast deterministic rejections
instead of 10-30s spin). Library defaults unchanged (2^32; battery +
(a{1,50}){1,50} still compile; gates green).
Note: fuzz throughput reads lower (~240k/min on this run) — the rejected
bombs now run to their 8M ticks before rejecting instead of being killed
at 10s mid-flight; case count for the 5-min window also reflects machine
load. The wall spent per bomb is bounded and small either way.

ROUND 15 (2026-09-02): nested-counted bombs — clean rejection, memory-
calibrated caps (the loop-based bounded-repetition rework stays deferred).
The classic (a{1,100}){1,100} OOM-ed the boxed determinization phase
(OutOfMemoryError, not a clean reject): kernelsTotal only counts AFTER
addState, and the 50M default assumed >2.5 GB heaps. Measured: 6.4M
kernels peaks <1 GB ((a{1,50}){1,50}, 2501 states, 2.9 s), 18M exceeds it
((a{1,65}){1,65}). New defaults: tdfa.max.kernels 50M -> 10M (clean
reject on default heaps, raise for heavier legit use) and a per-kernel
spike cap tdfa.max.closure=100k checked DURING closure construction (one
closure could exhaust the heap before any total counts). Ladder at 1g
heap: n=50 OK; n=65/80/100 all clean PatternSyntaxException in seconds.
Battery: (a|a{50}){50}, ((a{0,2}){0,2}){0,50}, (a{1,50}){1,50},
(?:ab{1,20}){1,20}, (a?{50}){50} all compile; only (a{1,100}){1,100}
rejects. Pinned in CompileBudgetTest (clean reject, largest-legit-still-
compiles, closure spike cap). Gates green; 1.59M-case fuzz slice 0
failures (4 hangs = cliff family). re2j compiles (a{1,100}){1,100} where
we reject — documented divergence class (eager full-DFA vs pike VM);
loop-based bounded repetition remains the structural fix if it ever
matters in practice.

ROUND 14 (2026-09-02): history hash-consing (HistTable).
Config.h/.l are now hash-consed IDS (HistTable, primitive-chained intern
table): identical tag-history sequences share one id, one backing array,
and one cached derivation — the has-history bitset and per-tag last-sign
table are computed once per sequence instead of rescanned per config per
state. fillKeySig hashes one int per config (was O(sum|l|)); addState's
hasHist fill became a cache fetch; transitionRegops/finalRegopsOf read
the last-sign cache; vmap (tag,sign)->reg became a flat int[2*tags] per
source state; the emitted-dedup HashSet became a bounded linear scan
(opList <= 2*tags). Dead canonical DfaStateKey(List) ctors removed.
Cliff seeds: tryMap-family 13.2->8.2s; others flat; the l-heavy family
was the predicted winner. Memory: no measurable RSS change on the cliff
seeds (their l's are short — heap is Config/regs dominated); the win
applies to history-bloated shapes. Fuzz 1.85M-case slice: 0 result
mismatches, 0 exceptions; 8 BUDGET_REJECTs = the pre-existing
nested-counted bomb family (verified identical on HEAD via stash cycle),
7 bounded hangs (cliff family).

ROUND 13 (2026-09-02): compile-cliff root causes fixed — the interning was
the quadratic, not the closure.
Profiled the 4 overnight hang families (JFR): 70-80% of wall time was
state INTERNING, in three stacked layers, all now fixed (clean-rebuild
measured, one hang-family seed each):
- CANONICAL key → ORDER-EXACT key (Tdfa.fillKeySig): the state index
  hashed the state-SORTED multiset, but tryMap only merges exact-arrival-
  order matches — the index admitted every permutation of a multiset and
  tryMap linearly rejected them (nested counted reps produce many arrival
  orders; buckets grew into the hundreds). Exact keys admit only viable
  candidates. tryMap's phase-1 state/Arrays.equals(l) sweep became
  redundant (key equality implies it) — deleted.
- Bijection via boxed HashMap<Integer,Integer> → primitive epoch-stamped
  arrays (registers are globally allocated: size by nextReg, NOT
  regs.length — that crash was the giveaway). A register may be new-side
  of one tag and old-side of another: two separate epoch arrays.
- CANONICAL FORM prefilter: bijection-compatible ⟺ position-equality
  patterns of the register vectors match (rn_p==rn_q ⟺ ro_p==ro_q),
  exactly equality of first-appearance-canonical flat arrays. Bucket
  members indexed by canon hash; and canon-equal members are
  INTERCHANGEABLE (success/failure and merged-op values depend only on
  the attempt) — probe members[0] only. The residual quadratic was
  appendInt-growing canon lists nobody read beyond [0].
- WorkMeter.tick(n) bulk form; fillKeySig ticks per 64 sig ints so the
  work budget stays proportional to real work (was calibrated to the
  deleted scan loops).
Seeds (before → after, all clean -Xss16m single runs):
  tryMap 35.7s→13.2s · addState 92.7s→10.0s · containsKey ≥34s→13.8s ·
  equals 92.7s→60.1s (now determinizes to the 100k-state cap; before it
  was work-budget-cut earlier). Legal heavy pattern
  (x{2,4}?z|\D{1,6}?.+$|~|W(...)...){4,}: 15.0s→9.1s.
Remaining cliff: O(sum|l|) per state (sig fill/copy + hasHist over long
history sequences) — the honest next step is hash-consing l sequences
(configs share them across states; also a memory win on legal bombs like
the 234k-state bounded-repeat case). Nested-counted expansion bombs
(loop-based bounded repetition) remain the structural item.
Lesson recorded: German-locale javac errors ("Fehler") were invisible to
'error:' greps — several intermediate builds silently failed and runs
used stale classes; clean-rebuild numbers above are the ground truth.
Gates green; 674k-case fresh fuzz slice 0 failures / 0 known.

ROUND 12 (2026-09-02 morning): the round-11 fix + perf groundwork + MT fuzzer.
- LAZY-\b FIXED (fb64f09): pike post-match pruning determinized (live sets
  truncated below the first ALIVE accept config — leftmost-first discards
  anything below-ranked threads reach; kernel/stop-tables untouched) AND
  entry ownership across overlapping contexts is now most-specific-
  satisfied-mask (popcount, ties to index) at all three VM scan sites plus
  the ASM dispatch chain (dead markers included, emitted most-specific
  first). The lo-sorted-table shadowing (broad mask-0 range before the
  specific dead marker) was the second half of the root — pruning alone
  was inert. All 16 repros + round-10's 15 + gates green; pinned in
  AnchorContextParityTest.
- PERF (77166f6): tryMap/transitionRegops per-config history memoization
  (bitset / last-sign, one pass over the history sequence). Cliff seeds
  still exceed the watchdog — cost is tryMap CALL VOLUME + addState
  interning (O(distinct²)); that restructure remains the open item.
- FUZZER: multi-threaded out of the box (cores-1, cap 8; -Dfuzz.threads=1
  restores sequential). Batches drawn/generated/RECORDED on main — case
  order and ndjson byte-identical across thread counts (verified 1 vs 4);
  hang post-mortem uses the batch's own worker (currentWorker static
  removed — it raced). ~1.6x on this machine (495k vs 310k cases/min;
  compile-bound). Chunked soaks still recommended: sacrificed hang
  threads spin until chunk death.

ROUND 11 (2026-09-02, overnight 2 triage — lazy-\b family DIAGNOSED, fix
deferred): ~195M cases, 0 failures, 0 known divergences (fix2 oracle clean);
46 RESULT_MISMATCH/CONSTRUCTION records → 16 distinct patterns → minimizer
collapses ALL to one shape: LAZY quantifier + \b/\B + optional tail
(.+?\b[^\d]* on "ß9": re2j/sim/jdk match [0,1), we match [0,2]).
Cliff census at scale: 292 tryMap + 97 accumulateClobbered + ~100 misc
hangs, 304 budget-rejects (items 4-5 unchanged).

DIAGNOSIS (instrumented, all layers ruled out in order):
- runtime \b wordness is CORRECT (ASCII default; 9\bß on "9ß" agrees
  with re2j in default mode — the engine computes WB=boundary at pos 1);
- the byMask accept table is CORRECT (fmCell=0 ≥ 0 = accept alive at pos 1
  under M=WB);
- the stop-table NEVER_STOP under M=WB is CORRECT BY DESIGN: the
  [^\d]*-entry config (order 4) genuinely outranks the direct accept
  (order 5) — for input "ßx" the extension DOES win ([0,2), re2j agrees);
- THE BUG: after recording the pos-1 accept, the walk takes a transition
  from a config ranked BELOW the recorded accept (the lazy . body,
  order 6, matches '9' where the outranking [^\d] cannot), re-accepts at
  pos 2, and the runner OVERWRITES lastAccept unconditionally
  (extractFrom: lastAcceptPos = pos). That implements leftmost-LONGEST
  for the post-accept window; Perl leftmost-first requires the FIRST
  (highest-priority) accept to survive lower-priority later accepts —
  re2j records only the first Match. Narrow trigger: mask-gated accept
  (\b/\B) + a lower-priority mask-0 body re-entering an accepting state.
FIX DIRECTION (next determinizer round, ~half day + revalidation): carry
per-range source-config rank (or per-(state,M) accept rank from the
byMask winner) and prune continuations ranked below the last recorded
accept — the DFA equivalent of pike's post-match thread pruning. Needs a
rank field on range entries or an equivalent; the 16 minimized repros
are the acceptance set (all in this TODO's ndjson).

ROUND 10 (2026-09-01, CONSTRUCTION family fixed + oracle fix2):
- re2j fork fix2 (fa71014): Regexp.equals/hashCode now compare FoldCase
  for LITERAL/CHAR_CLASS — the port dropped Go's Flags&FoldCase comparison,
  so alternation factoring merged a folded literal with its case-sensitive
  twin and DELETED the case-sensitive arm: (?i:Z)x|Z matched "z" (JDK and
  we: no). The "residual lone-low interior" record was actually this leak
  wearing the known-divergence coat — second mislabel by the shape
  heuristic (the PARSER-layer gate caught the first). Vendored as
  re2j-1.8-jemmix-fix2.jar; patched-oracle soak now shows 0 known
  divergences. More upstream material.
- CONSTRUCTION family (39 records / 15 patterns, all anchors-under-
  optional constructs): minimizer built (delta-debug on pattern+input vs
  sim/vm divergence); 15 crisp repros exposed THREE determinizer defects:
  (1) dead-marker scan order — the transition scan broke the walk on any
  satisfied dead marker before reaching lower-index satisfied LIVE
  entries ((\b)?^[\d] on "0" died at the dead WB context, never saw the
  live WB|BEGIN owner); the ladder scans had the dual flaw — SKIPPING
  dead markers, falling through to dead contexts. Rule now uniform: the
  lowest-index mask-satisfied entry owns the step, dead or live.
  (2) tagless OR-of-assertion-gated accepts: Z(?:\A|\B) accepted at pos 1
  where both arms fail — the conjunctive accept mask (intersection)
  collapses a disjunction to 0 = unconditional; the byMask final-ops
  table expresses per-M aliveness but was only built when tags>0; now
  built for tagless kernels too (packed form; variants degenerate to
  empty ops — the cell sign is the aliveness).
  (3) literal-needle shortcut past position-dependent accepts: the same
  pattern also matched via indexOf; detectLiteralNeedle now declines
  final states with byMask rows.
  All 15 repros + all original replay seeds agree with the oracle; gates
  green (forced rerun); pinned in AnchorContextParityTest (11 dead-marker
  shapes + 4 disjunctive-accept shapes, both engine factories).

DECISION (2026-08-30, governance — option A): the lone-surrogate ×
pair-interior divergence is an artifact in re2j, not a semantic to
replicate. The oracle charter is re2j, and "we're in the majority with
the JDK" is explicitly NOT a decision procedure — flip-flopping to re2j
would flip the majority. Chosen on the merits:
- RE2, the system re2j ports, operates on UTF-8 runes: codepoint-interior
  match positions cannot exist in that model. Unit-interior matching is a
  UTF-16 port artifact, not family semantics.
- Preliminary evidence re2j is internally INCONSISTENT on this family:
  our fork's literal-prefix fix (fix-surrogate-pair-interior-prefix,
  4facb96) left residual interior matches via other paths (2 records in
  562k patched-oracle cases). If re2j's own paths disagree, option B
  ("replicate re2j") has no well-defined target and A is forced.
- JDK + Unicode scalar semantics side with codepoint boundaries
  (corroboration only).
- B's price (~2-5 days: hybrid per-position input relation in the
  determinizer, ~10 pairInterior guard sites across both tiers, PikeSim,
  loss of JDK-parity corpus pins) buys replication of an accident.
WORK ITEM: assemble the repro set (LayeredComparatorTest's (\uDC21) pin +
fuzz.one seeds from r13's residual records); verify whether re2j's paths
are actually inconsistent or uniformly unit-based; file google/re2j
issue + PR from the fork branch. FALSIFIER: upstream rules the behavior
intended and pinned (won't-fix) → revisit B. Until then the drop-in
charter carries this one documented exception and the PARSER-gated fuzzer
allowlist stays.

ROUND 8 (2026-08-30, first v3 soak triage): the 10-min user run (seed
31765, 1.5M cases) reported "0 failures" — but its 3 KNOWN_DIVERGENCE
records were wearing the wrong coat. The layer field said CONSTRUCTION,
not PARSER: a real engine bug swallowed by the loose lone-surrogate
heuristic. The layered audit did its job; the classifier didn't.

- ENGINE (literal needle): pattern of two LONE surrogate symbols kept apart
  by syntax — (?i:\uD800)\uDFFF — re-encodes its needle as adjacent units,
  the same UTF-16 text as the pair codepoint they are not. Unit-wise
  indexOf then matched a well-formed input pair (0..2) where re2j, JDK,
  and PikeSim-over-our-own-Tnfa all said no. Key subtlety: the shape
  [lone high][lone low] is UNSATISFIABLE by the alphabet contract (adjacent
  high+low units always decode as a pair), so the needle was matching
  where the pattern never could. detectLiteralNeedle now declines needles
  containing adjacent high+low units; the DFA walk handles the (empty)
  match set correctly. Covers both tiers (ASM asks at emit time).
- SIM (PikeSim): find() read charAt(len) in the pair-interior skip when the
  input ends in a lone high — StringIndexOutOfBoundsException poisoned the
  sim column for the whole input class. The 41/41 historical corpus never
  had a trailing lone high.
- FUZZER: the KNOWN_DIVERGENCE swallow is now gated on the verdict being
  PARSER — the known divergence IS a parser-boundary semantics difference;
  any other layer with a lone-surrogate coat is a real finding (this one
  was, for a whole night).
- Pinned: SupplementaryCodepointClassTest.loneSurrogateNeedleAdjacency...,
  LayeredComparatorTest.loneSurrogateNeedleAdjacencyIsPassAfterFix.
  Replay seed of the original record: fuzz.one=8000609719577733824.
  Re-run of seed 31765 post-fix: 1.57M cases, 0 failures, 0 known.

LANDED (2026-08-29, fuzz round 7) — the layered audit, as a component trio:

- **`lib/pikesim` — PikeSim** (io.github.jemmix.tdfa.sim): a ~300-line reference pike
  VM running directly over the Tnfa — no determinization, nothing deferred;
  assertions checked at edge crossing against direct position truth; captures
  as copy-on-write path state (the engine's functional tag-history semantics,
  and the exact equivalent of re2j's scoped write/restore GIVEN the shared
  nullable-X* = quest(plus(X)) shape — verified against re2j's Machine.java
  source from the fork). Public API + CLI for end-user debugging; validated
  41/41 on the full historical bug corpus and as a hard fourth column of the
  63k ZeroWidthExhaustiveTest (the column caught its first sim bug — group-0
  indexing — during bring-up: the audit audits the auditor too).
- **`:testlib:parity` — LayeredComparator**: the four-column vote (re2j /
  sim / vm / asm) with a total classification — PASS, TIER (vm!=asm),
  CONSTRUCTION (vm==asm!=sim; sim==re2j), PARSER (vm==asm==sim!=re2j),
  SIM_SUSPECT (vm==re2j!=sim), CHAOS — plus a CLI probe. Compile rejections
  normalize to one token (three exception classes would masquerade as
  divergence). Own acceptance test = retrodiction: the synthetic verdict
  table, the round-3..6 repro families (all PASS now), and the live
  lone-surrogate case classified PARSER (our deliberate codepoint-boundary
  semantics — the KNOWN_DIVERGENCE classifier composes on top).
- **Threaded everywhere**: ZeroWidthExhaustiveTest hard-gates the sim column;
  SemanticsContractTest/FinalOpsParityTest get failure-time attribution (zero
  green-path cost — the verdict lands in the assertion message); the fuzzer
  records a `layer` field per failure and groups the soak summary by layer.
  Not built: an always-on per-case sim column in the soak (the failure-path
  attribution covers localization; an always-on column would ~halve throughput
  for no extra signal).

What already worked and stays: exhaustive enumerators as acceptance gates,
hard-gating every fixed family, replay corpora, probe-before-fix.


- [x] **`(a*)(a*)` crashes at compile time** — FIXED. `ArrayIndexOutOfBoundsException` in `Optimize.findFinalRegBase` on both backends and both disambiguation modes; affected `(a+)(a*)`, `(a*)(a?)`, `(.*) (.*)`, `(a*)(a*)(a*)`. Root cause: register allocation coalesced FINAL registers — with each other via the same-value rule (two SET-pos finals), or with working registers via COPY coalescing. Finals must share nothing: the `MatchResult` readout protocol requires tag *t* at a dedicated `regs[finalRegBase + t - 1]` slot. When finals coalesced, the block either scattered (silently wrong captures) or `regCount` dropped below `tagCount` (negative base → crash). Fix: `Optimize.registerAllocation` runs coalescing over working registers only and assigns finals dedicated consecutive top slots in tag order (BT22's dedicated `R_f` layout). Tests: `CaptureGroupAllocationTest` (ungated).
- [x] **`\b` mishandles supplementary codepoints** — FIXED. `isWordChar(char)` checked individual UTF-16 code units, so supplementary letters weren't word chars even under `(?u)`. Now `TdfaRunner.isWordBefore`/`isWordAt` decode surrogate pairs and search `wordRanges` by codepoint; the ASM backend emits equivalent `isWordBefore`/`isWordAt` helpers used by both `positionFlagsC` and the inline PF. Tests: `WordBoundaryTest` (ungated).
- [x] **`(?iu)` char-class ranges don't include Unicode fold equivalents** — FIXED. `Parser.parseClass` now expands every member codepoint's `CaseFoldTable.foldRanges` when `unicodeShorthand && caseInsensitive` (`(?iu)[r-t]` matches `ſ`); ASCII-only fold retained without `(?u)` (re2j semantics).
- [x] **`(?iu)` negated classes don't exclude Unicode fold equivalents** — FIXED by the same range-fold expansion (fold members join the positive set before `CharClass` negation, so `(?iu)[^s]` rejects `ſ`).
- [x] **`JdkUnicodeDataProvider.foldTableFor` incomplete** — FIXED. `buildFoldTable` only closed the `toUpperCase` direction (worked for `\p{Lu}`, null for `\p{Ll}`). Now checks all `CaseFoldTable` fold-group members, so `(?i)\p{Ll}` matches `A` and multi-member groups (s/S/ſ) close in both directions.
- [x] ~~**Zero-width-anchored alternation matches [0,1] instead of [0,0]**~~ — RESOLVED
      BY AUDIT (2026-08, commit `66f31ef`): does not reproduce; the re2j-suite
      mask hiding it was stale (6/6 green unmasked at the session baseline). The
      final-register dedicated-slot fix in `Optimize.registerAllocation` plus the
      accept-suppression safety rule in `stepOnSymbol` (ungated lower-priority
      paths kept when their assertions differ from the accept's — the
      `^x*|y` [0,1]-vs-[0,0] shape) cover the family. re2j-suite is hard-gated.
- [x] **PERL disambiguation with `\b` in alternation** — was described as "picks wrong alternative" but root cause was a determinization flaw: `\b`-guarded transitions didn't include identifier continuations from non-`\b` branches, causing dead-end DFA paths. Fixed in commit `d133d20` by including subset-mask configs in the step input during mask-group processing.
- [x] **Unicode case folding for single-char literals** — Fixed in commit `74ab652`. Added `CaseFoldTable` with a reverse fold table; parser uses it when `unicodeShorthand && caseInsensitive`. `(?i)s` now matches `ſ` (U+017F).
- [x] ~~**`\w` / `\b` are ASCII-only, not Unicode-aware**~~ — NOT A BUG (phantom). Our
  ASCII-default `\w`/`\d`/`\s`/`\b` is exactly re2j 1.8 semantics — the API contract.
  re2's `\w` is also ASCII (re2 is *excluded* from rebar's `08-words/all-russian` for
  exactly this reason — see the scenario's own analysis). The `want=107391` recorded
  here earlier was the `.*` fallback count, which on that scenario belongs to
  Unicode-`\w` engines (rust/regex, python, perl) — never re2's. Our test correctly
  resolves `java/hotspot`'s 53986 (Unicode via `UNICODE_CHARACTER_CLASS` when the
  scenario sets `unicode = true`) and passes. Unicode-`\w` remains available via
  `(?u)` opt-in. Corrected `docs/PARITY-PLAN.md` accordingly.
- [x] **`\p{L}{N}` returns 0 matches for N ≥ 25** — root cause was the `stateMeta` packing: the range-base field used only 15 bits (bits 17-31, max 32767), but `\p{L}` has ~1369 Unicode ranges per state, so `base` overflowed at state 24 (24×1369=32856). Fixed by splitting `base` into a separate `stateBase[]` array (full 32-bit range), removing the artificial limit. `\p{L}{256}` now compiles and matches correctly on both VM and ASM backends.
- [x] **`.` on non-BMP codepoints undercounts vs re2** — PERMANENT DOCUMENTED DIVERGENCE:
      `.` on `💩` (U+1F4A9) gives 1 match in our engine (one codepoint); re2 gives 4
      (UTF-8 bytes). Architecture choice (codepoint-oriented like `java.util.regex`,
      not byte-oriented like re2), not a bug. Originally resolved at the test level via
      `vendor/patches/rebar/01-dot-matches-byte-codepoint.patch`; that patch was
      consolidated away at `6e3b63f` and the scenario now resolves through the live
      patched-re2j oracle (round 19, dd3f3bc) — suite green 226/0/2.
- [x] **Supplementary literals under groups/quantifiers/alternation fail to match** — FIXED (2026-08-27,
      structural). Root cause: the parser read patterns one UTF-16 unit at a time while the engine executes
      one CODEPOINT per transition (every step loop decodes surrogate pairs). Two-unit literals were therefore
      unrepresentable in the DFA alphabet; the shapes that "worked" were rescued by the literal-needle indexOf
      path, which bypasses the automaton. Fix: one shared decoder (`ast/Alphabet.decode` — used by the parser's
      atom/class readers AND all nine runner step loops AND `\Q..\E`), supplementary literal → single-codepoint
      CharClass (same shape `\x{...}` always had). Both backends; corpus 216 mismatch cases green.
- [x] **Supplementary class-range endpoints parsed at UTF-16-unit level** — FIXED same round: `parseClassChar`
      reads codepoints (`[𐐁-𑰇]` = U+10401–U+11C07), octal no longer `(char)`-truncates. The 100 "inverted range"
      corpus rejections green.
- [x] **Identity escapes rejected** — FIXED: re2j's actual policy (probed) is reject only unknown
      ASCII-alphanumeric escapes; non-ASCII identity escapes (`\䑄`, `\Ω`) are literals, inside and outside classes.
- [x] **Fixed-tags counted every class as UTF-16 distance 1** — FOUND while fixing the above: `(\x{10421})`
      reported g1=[1,2) (JDK [0,2)); `(.)([^a])xyz` on supplementary input was off by one. `CharClass.fixedUtf16Width()`
      (1 BMP-only / 2 supplementary-only / poison mixed and negated) now feeds FixedTags distances.
- [x] **Matches could start at the low half of a surrogate pair** — FOUND by the corpus's lone-surrogate lines:
      `[\x{dc00}-\x{dfff}]` matched the second unit of `💩`. New alphabet rule (`Alphabet.pairInterior`): pair-interior
      positions are not boundaries; guarded in the literal-needle hit, all candidate scans, both restart loops,
      and the ASM-emitted ladder (which calls the same one definition).
- [x] **Unknown inline flags were silent no-ops** — `(?x)` was accepted and ignored (neither re2j's rejection nor
      JDK's comments mode: a silent misparse). Now: unknown flag letters reject like re2j; `(?U)` ungreedy implemented
      (re2j has it); `(?-)` with no flag rejects. `(?x)` comments mode remains an extension candidate (re2j reject-space).
- [x] **Tdfa construction now validates its packed program** (range bounds/order, target/op/mask domains,
      prefix-max invariant, final-register block) — packing bugs die at construction, not as wrong matches.
      Two of the initial checks were wrong about real invariants (equal-lo entries are legal; accept mask is not
      a subset of entry) and were corrected after the suites objected — the checks document actual laws now.
- [x] **Differential fuzzing vs re2j** — `tests/parity/re2j:fuzz` (custom harness, `DifferentialFuzzer`):
      random grammar-biased patterns × inputs (supplementary + lone surrogates first-class), re2j oracle,
      both engines, per-case watchdog (hangs recorded with stacks, soak continues), failures.ndjson with
      reproducible per-case seeds. Overnight: `./gradlew :tests:parity:re2j:fuzz -Pfuzz.minutes=480`.
      Fixed-seed 500-case slice is a hard default gate (`FuzzSmokeTest`). First 20k-case run (2026-08-27):
      0 contract failures after fixes, 3 findings triaged below.
- [x] **Determinization hang on nested-quantifier bombs** — RESOLVED 2026-08-28 by the WorkMeter
      (compile work budget; see that entry). Original finding, tryMap family:
      (2026-08-27): `(\u03A9(\be.z|\W.+(.{1,2}))(?s:\u3042??)){1,6}h` compiles unboundedly; the compile
      budget does not trip for this shape. Repro: any seed in `build/fuzz/failures.ndjson` with
      `"kind":"HANG_ENGINE"`, via `-Dfuzz.one=<caseSeed>`. The fuzz watchdog survives it; users would not.
      Budget needs a tryMap/steps cap that covers nested star-of-bounded families. Second soak
      (2026-08-27 evening): same family also hangs in `Tdfa$Compiler.epsilonClosure` and
      `Optimize.livenessAnalysis` — the budget gap is determinization-wide, not tryMap-specific.
      (That soak's `hangsEngine=33` was misclassification — see harness round below.)
- [x] **Non-participating group under a quantifier reports `""` instead of `null`** — FIXED 2026-08-28
      (fuzz round 3: eager φ + mask-before-ops + position-aware final table; see that entry).
      FOUND by the fuzzer
      (2026-08-27, 2 of the soak's 3 contract failures; seeds `-7645183372330529930` `((?s:\b))?`,
      `-4431982515513205820` `(.\z)*`). Minimal: `((?s:\b))?` on `\udc00\ud800\r` → jdk/re2j g1=null,
      both our engines g1=`''`. Scope: skipped groups with zero/variable-width content —
      `(a)?b` and `(a\z)*` are correct, so tags written during a partially-matched iteration leak
      into the final registers; unset-vs-[0,0) is ambiguous in the readout. Core tag layer (both engines).
- [x] **`.+\b.` undershoots when the last boundary precedes a supplementary pair** — FOUND by the fuzzer
      (2026-08-27; seed `7713360668071298679`). Minimal: `.+\b.` on `\u03a99\ud800\udfff` →
      jdk AND re2j match `\u03a99\ud800\udfff` (boundary after 9, `.` eats the pair); both our engines
      stop at `\u03a99` (earlier accept at boundary Ω|9). Single-boundary input `9\ud800\udfff` is
      correct — needs two candidate boundaries. Not the documented codepoint-vs-unit divergence:
      our own codepoint semantics say the answer includes the pair.
      FIXED en route by the fuzz-round-4 assertion-context split ("`.+\b.` before supplementary
      pairs" in that entry); `LayeredComparatorTest.historicalBugFamiliesArePassNow` pins it.
- [x] **`(?:.*?9{0,}\b){1,}` prefers a non-empty first iteration** — FOUND by the fuzzer (2026-08-27;
      seed `516468605110627597`). Minimal: on `Z\t` → jdk/re2j match `[]@0` (lazy `.*?` stays empty,
      `\b` holds at input start before word `Z`); both our engines match `[Z]@0`. Components alone
      (`.*?\b`, `(?:.*?\b){1,}`, `9{0,}\b`) are all correct — the nesting is the trigger.
      FIXED by the fuzz-round-5 empty-iteration subsumption cut (now matches `[0,0)` like the refs);
      `LayeredComparatorTest.historicalBugFamiliesArePassNow` pins it.
- [x] **Fuzz harness round 2 (leak containment + honest hang accounting)** — first soak (2026-08-27
      evening) degraded to 74 % of wall time waiting on watchdog timeouts, throughput 11k→7.5k cases/min
      and falling: 44 of ~50 hangs were re2j's own `(?i)` wide-range class-fold expansion (oracle-side),
      each leaking a still-running daemon thread. Fixes: (a) generator avoids wide `(?i)` class ranges
      (ASCII-narrow only, counted as ciWideRangeAvoided) — 6000-case probe: 0 hangs; (b) hang classifier
      checks engine frames excluding harness wrappers (was `indexOf("io.github.jemmix")`, which matches
      every stack — 33 oracle hangs were tagged HANG_ENGINE); (c) `scripts/fuzz-soak.sh`: 480×1-min
      chunked JVMs sharing one out dir via `-Pfuzz.append` — leaked threads die with their chunk;
      (d) `-Pfuzz.one` forwarded by the fuzz task (turnkey repro). Overnight relaunch:
      `scripts/fuzz-soak.sh`.
- [x] **Literal-needle path ignored surrogate pairing** — FOUND by the fuzzer, FIXED (2026-08-27): raw
      `indexOf` matched needle unit sequences that start mid-pair or end on the high half of a pair
      (`a\uD800` matched inside `a\uD800\uDFFF`). `TdfaRunner.literalIndexOf` (shared by find/extract/
      candidate paths and the ASM-emitted ladder) enforces both boundary guards.
- [x] **re2j plain-`(?i)` folds class ranges with full Unicode simple folding** (`(?i)\w` matches ſ;
      ſ/K/Ω fold groups) while we folded ASCII-only without `(?u)` — DECIDED 2026-08-29
      (commit `12d9921`): adopt full-BMP simple folding as the default (re2j parity) for
      literals, explicit classes, and word shorthands; `(?u)` remains the explicit Unicode
      tier. The fuzzer's knownDivergence entry was removed (fold divergence = real bug now),
      `SemanticsContractTest` gates the folding behavior permanently, and the rebar suite
      resolves expected counts from the live patched-re2j oracle at run time
      (dd3f3bc) — no hand-patched divergence counts needed.
- [ ] **re2j matches lone-LOW surrogate patterns at/into pair interiors** — SUSPECTED RE2J BUG,
      fix on our fork: https://github.com/jemmix/re2j/tree/fix-surrogate-pair-interior-prefix
      (commit `4facb96`, +test). Root cause there: the literal-prefix fast path jumps to raw
      `String.indexOf` unit positions (UTF16Input.index), which can land on a pair's low half;
      only patterns compiling to a singleton literal prefix are affected, so `\uDC21` matches
      where `\uDC21|\uDC22` (a strict superset) and `[\uD800-\uDFFF]` do not — a monotonicity
      violation (range/singletons without a single-rune prefix never enter interiors: stepping
      is codepoint-aligned). JDK agrees with us on every row. Upstream issue creation is
      restricted on google/re2j; PR from the fork pending. We keep codepoint-boundary semantics
      regardless — the fuzzer classifies the family as KNOWN_DIVERGENCE, and
      `-Pfuzz.patchedOracle=true` (vendor/re2j-jemmix/) fuzzes against the patched re2j to
      shrink the divergence stream. NB: singleton CLASS `[\uDC21]` matches like the literal;
      only the RANGE form avoids the fast path (earlier note here said "class form" — imprecise).
- [x] **Fuzz round 3 engine fixes (2026-08-28) — final-ops correctness by construction.**
      Three coordinated changes, all gates green (unit + re2j parity + corpus/Fowler + conformance):
      (a) **Eager φ at accept-record time** (both tiers): final ops read the accept config's
      working registers, which hold correct values only AT accept — the former lazy replay at
      walk end read post-accept clobbered values, producing inverted group spans (the 13 fuzz
      crashes, `Range [13, 8)`). The ψ/φ runtime selection became moot (pos == lastAcceptPos at
      record time by construction). (b) **No ops from mask-failing transitions**: the target's
      entry mask is a position predicate evaluated BEFORE the transition's ops run — tag writes
      from dead paths no longer contaminate the register file (the "skipped group reports `''`
      instead of null" family, 447+ findings). (c) **Position-aware final-ops table**
      (`Tdfa.stateFinalOpsByMask`, per-(accepting-state, posFlags) accept-config winner,
      threaded through regopt as sibling FINAL blocks and through the minimizer signature):
      a state may merge accept configs whose assertions differ; when the runtime mask kills
      the highest-priority one, priority falls to the next ALIVE config whose tag outcome
      differs — and the table replaces the over-strict accept-mask-intersection gate for
      variant-carrying states. computeNeedsWordFlags extends the R5 trim to the table (the
      `\b`-dependent winner needs word flags even when masks/ranges/stop cells don't).
      Construction-time invariants
      added to Tdfa.validate: transition ops never write the final block; byMask cells bounded.
       Tests: `FinalOpsParityTest` (hard gate: the fixed families, incl. >64-char inputs so the
       ASM emitted ladder is exercised, not the delegation path); `ZeroWidthExhaustiveTest`
       (~63k cases: {zero-width atoms} × {quantifiers} × {grouping} × {prefix/suffix} ×
       boundary-rich inputs, both engines vs re2j) is now hard-gated (was `tdfa.pending`-gated
       until the stop-or-extend family landed); `CompileBudgetTest.workBudgetRejectsClosureSpinners` gates
       the WorkMeter. Replaying 959 recorded overnight mismatch seeds: ~72% now pass; remainder
       = stop/empty-iteration priority family (next item) + documented fold divergence.
- [x] **Compile work budget (`WorkMeter`)** — every unbounded/fixpoint loop in the compile
      pipeline (determinize worklist, ε-closure DFS, addState/history/sortConfigs/
      transitionRegops, regopt liveness fixpoint, §3.2 fallback accumulation) ticks a meter;
      exhaustion throws the same clean "pattern too large" error as the state cap
      (`-Dtdfa.max.work`, default 1<<32). Verified on the true tryMap-family spinner (clean
      reject in ~6 s at a 200 M budget; was an infinite loop). Note: many overnight
      "HANG_ENGINE" records were borderline-slow compiles (9-12 s) under leaked-oracle-thread
      CPU contention, not infinite loops — the meter classifies both honestly.
- [x] **Zero-width assertions promoted into the alphabet (stop-or-extend family)**
      (2026-08-29, fuzz round 4). Landed as four constructions, all validated by the
      now-hard-gated `ZeroWidthExhaustiveTest` (63 000 enumerated cases) plus
      `FinalOpsParityTest.stopOrExtendThroughAssertions`/`groupScopedMultilineAnchors`:
      (a) **assertion-context split** — per distinct live-set of the closure (mask ⊆ M),
      stepped in true closure-priority order, so every target is liveness-complete AND
      priority-correct (replaces the own/subset mask-group split whose appended kernels
      inverted priority — the `[^0..]+\b\W` greedy-exit undershoot);
      (b) **per-edge anchor flavors** — BEGIN_TEXT/END_TEXT are now unconditionally
      line-flavored posFlag bits; each `^`/`$` edge requires ABS_BEGIN/ABS_END (plain) or
      BEGIN_TEXT/END_TEXT (under parse-time (?m)) — group-scoped `(?m:...)` anchors work
      (were: global flag only; `\D(?m:\S.$)` and `(?m:$)` families fixed); `\A`/`\z` = the
      ABS bits alone;
      (c) **pike-cut stepping** — within a context where the first accept config is alive,
      lower-priority configs are dropped from the step exactly like a pike VM cuts threads
      below a match-recorder (the old mask-superset safety condition is subsumed by the
      context split);
      (d) **dead markers** — a more-specific context with no transition for a symbol cell
      emits an explicit dead range (target −1) that terminally blocks less-specific
      contexts' ranges for those M (`\D+?\s*\B` on "aß#": stop at the accept where the
      greedy `\s`-consume dies, was: one char too far).
      Also fixed en route: `(\A)??.+` lazy-skip capture priority (context-true kernel
      order), `.+\b.` before supplementary pairs, `(?:..{3,5}?\B)+\S`. Overnight seed
      replay 77% → 94% (remainder: ſ-folding oracle divergence + the corner below).
- [x] **Empty-loop-iteration cut** (2026-08-29, fuzz round 5). Landed as a
      SUBSUMPTION CUT in epsilonClosure: a re-arrival (state, newMask) is dropped when
      an earlier, higher-priority variant (state, m' ⊆ newMask) was already popped —
      the earlier variant is alive at every position the re-arrival could serve and
      produces the same continuations from the same NFA state (exactly re2j's
      per-position pc dedup, which its threads carry no deferred masks to dodge).
      Nullable loop bodies re-enter with the cycle's accumulated assertion bits — a
      superset — so empty iterations die ((?:.*?9{0,}\b){1,} on "99x" now matches
      [0,0) like the refs). Incomparable-mask re-arrivals survive ((?:^|$)+ needs both
      junction variants — a blanket state-only dedup regresses it; verified).
      Implementation: per-state popped-mask bitsets (masks are 6 bits) + exact submask
      check, epoch-stamped, O(2^popcount) per edge, no O(V) clearing. Validated by the
      RE2 exhaustive corpus + ZeroWidthExhaustiveTest + FinalOpsParityTest
      .emptyIterationCut.
- [x] **Empty-iteration capture reporting under post-loop anchors** (2026-08-29,
      fuzz round 6). `(\B)*\z` on "!" reporting g1=null (re2j: '' — the empty
      iteration's capture writes reach the loop-exit accept) was NOT missing
      machinery: the kernel already carried the one-iteration accept config
      (NWB|ABS_END-gated, tag written) and the byMask winner table selected it
      correctly. The defect was computeNeedsWordFlags' variant-equality check
      iterating base 0..15 — a range that spans word bits and never covers the
      ABS-bit combinations — so word flags were trimmed, runtime posFlags lacked
      NWB, and the table fell back to the zero-iteration winner. The base now
      ranges over the 16 combinations of the four NON-word bits (BEGIN/END_TEXT,
      ABS_BEGIN/ABS_END) comparing fm/soa cells across the word variants of each
      base. JDK reports null here (documented re2j-vs-JDK divergence, we side
      with re2j — same family as `$` before a final newline). Validated by
      FinalOpsParityTest.emptyIterationCapturesUnderAnchors (12 shapes incl.
      lazy, mandatory-iteration, and sibling-symbol-path controls).
- [x] **ASM-tier devirtualization is now machine-checked, two layers** (2026-08-27):
      `EmittedBytecodePolicyTest` (default gate: no INVOKEINTERFACE/INVOKEDYNAMIC; every INVOKEVIRTUAL
      receiver is a final class — checked reflectively on the dumped bytes) and
      `:tests:unit:inliningGuard` (separate action, forks a `-XX:+PrintInlining` JVM, parses compilation
      events + call sites, fails on megamorphic dispatch inside generated classes; full logs in
      `build/reports/inlining-guard/`). Baseline: CLEAN — 0 morphic failures across 51 compiled
      generated-class methods; size-related non-inlines are warnings.
- [ ] Deterministic compilation — same regex → identical TDFA across runs
- [ ] `map` + topological sort: reject non-trivial cycles (BT22 §3.3)
- [x] Fallback / backup operations (BT22 §3.2) — restore clobberable registers on dead-end
      paths; landed as `FallbackOps` (README "What's implemented" §6.2; WorkMeter covers
      the fallback accumulation loop).
- [ ] Verify TDFA(1) strict conformance vs paper wording (lookahead delay semantics)
- [ ] Multi-valued tags (tags under repetition accumulating multiple offsets)
- [x] Property-based testing (random regex generation + differential oracle) — the
      `DifferentialFuzzer` IS this (grammar-biased generator, re2j oracle, deterministic
      records, ~300 M accumulated cases; see the fuzz entries in Correctness). Remaining
      wishlist beyond it: property shrinking for minimal repros.

## Performance

- [x] **O(n²) unanchored `find()` — no-match case** — fixed via multi-state parallel simulation in `TdfaRunner.multiStateAnyMatch`: a single forward pass tracks the set of all DFA states reachable from any start position (O(n × |states|)), replacing the outer-loop restart. Used for boolean `find()` directly and as a no-match pre-check for the extract path. 200 K-char no-match haystack: ~14 ms (was >30 s).
- [x] **O(n²) `find()` on dense matches** — FIXED (P1). `multiStateLeftmostStart` runs the multi-state simulation with per-state origin tracking (double-buffered with the state sets); the extract walk starts directly at the leftmost match position, replacing the retry-every-failed-start shape. leipzig `[a-zA-Z]+ing` findAll: 41 ms → 19 ms per 512 KB (2.2×, both backends, same 2351 matches). Note: the original 249 s/16 MB figure was stale — the stopOnAccept short-circuit (REBAR-SPEEDUP-PLAN §Tier-2 #3) had already cut it to ~41 ms/512 KB before P1 landed.
- [x] **ASM backend hits the 65 KB JVM method limit** — fully solved via `TdfaAsmBackend.pickMode`, which selects one of two dispatch modes per pattern: `INLINED` (per-state range checks, fastest) or `DELEGATE` (thin wrapper that forwards to a `TdfaRunner`). (A third mode, `TABLE_SCAN`, existed briefly and was deleted in the kernel refactor — measured NO-GO, see the P5 row above.) Combined with `<clinit>` no longer materializing per-element arrays (ENTRY/ACCEPT/STOP/IS_ACCEPT/ASCII_TARGET all become reference copies or runtime loops in `<init>`), no in-scope rebar pattern throws `MethodTooLargeException`. The old VM-retry path in `RebarScenarioParityTest` was removed; both backends now run as peer parameter values.
- [x] ~~DFA minimization (Moore-style, register-aware)~~ — DONE (has been in
      `Tdfa` all along, default-on, `-Dtdfa.nominimize` to disable; 20 K-state
      cap via `-Dtdfa.minimize.max`). The TODO claim that it "would clear the
      COMPILE_TIMEOUT skips" was doubly wrong: the bombs explode during
      DETERMINIZATION (before the minimizer runs, above its cap), and their
      DFAs are essentially minimal anyway (see next item).
- [x] **Huge-DFA bounded-repeat patterns** — e.g. rebar `curated/10-bounded-repeat/context`:
      `[\s\S]{0,100}` × 2 makes the DFA track both counters through every char,
      so the MINIMAL DFA is the counter cross-product. Measured: simplified
      analog `[A-Z]{10}\s+[\s\S]{0,100}Z[\s\S]{0,100}\s+[A-Z]{10}` = 60 604
      states, 60 603 after Moore (already minimal); the real regex = 200 K+
      states (48.6 s + 12 GB heap to compile uncapped).
      RESOLVED 2026-08-19 by adopting re2c's design verbatim (verified against
      re2c 4.5.1, the paper's reference implementation: its determinization
      aborts with "DFA has too many states" — `MAX_DFA_STATES = 100 K` states /
      `MAX_DFA_SIZE = 50 M` kernel-total, src/dfa/determinization.cc): the
      engine enforces the same caps during determinization and fails
      compilation with a clean "pattern too large" `PatternSyntaxException`
      (override: `-Dtdfa.max.states` / `-Dtdfa.max.kernels`). Our construction
      strictly dominates the reference on this axis — re2c refuses two-site
      `[^]{0,16}x[^]{0,16}` outright, while ours determinizes that family
      compactly ({0,100} = 10 K states) and caps only the intrinsically-huge
      ones. `CompileBudgetTest` guards fail-fast + override +
      legit-under-budget.
      FULL CORPUS VERIFICATION (2026-08-19): the rebar suite retries
      budget-rejected in-scope scenarios once at a raised ceiling
      (400 K states / 150 M kernels) and VERIFIES them — no scenario skip.
      All in-scope params pass. The one shape needing it (the context
      scenario): 234 369 states, kernel total ~44 M (default kernel cap
      non-binding — the state cap is the only one). MEASURED 2026-08-20
      after the compile/runtime memory work (M1-M3: visited-set sizing,
      tagless fast paths, packed kernels + dense sigs + dead-data release,
      ASCII-dispatch cap, stop-mask tiers): solo compile ~21 s, fits
      -Xmx1g (was ~5-6 GB transient; peak live Configs 66.27 M -> 391),
      retained DFA ~82 MB (was 378 MB), run 43-48 s over the 7 MB
      haystack, count=53 both backends. G1 quirk: -Xmx1g25m/1g5m OOM via
      humongous-region fragmentation while 1 g compacts fine — use 1 g.
      The suite verifies it via -Dtdfa.test.rebar.skipBombs=false.
      DEFAULT-CAP DECISION: stays at re2c's 100 K. Raising it to ~250 K so
      this class compiles out-of-the-box would make every over-cap pattern
      (adversarial ones included) burn ~20 s and ~1 GB transient BEFORE the
      clean rejection; exactly one legit in-scope pattern needs the raise
      (opt-in via the documented flag). CANOR COST,
      stated: re2j and java.util.regex ACCEPT the context pattern in ~10 ms
      (lazy NFA / backtracker — no eager determinization price); at the
      default cap we reject it. This is the honest cost of the single-
      algorithm AOT design, documented in README. REJECTED alternatives
      unchanged: Pike-VM/NFA fallback and lazy §7 determinization are
      multi-engine / different-architecture (non-goals).
- [x] **M2 regopt interference-analysis bug** — Fixed in commit `6b335e2`. `Optimize.interferenceAnalysis` walked ops FORWARD and cloned `L[b]` (end-of-block liveness) for EACH op, missing the fact that COPY sources become live BEFORE the op and conflict with registers written by LATER ops in the same block. Rewrote to walk ops in REVERSE with a running live set (BT22 Fig. 7), keeping a forward pre-pass for the value-tracking `V[]` snapshots. All 61500 veryl matches now report exactly 1 participating group, matching `java.util.regex`.
- [x] **`\b` in alternation causes dead-end DFA paths** — Fixed in commit `d133d20`. Modified `Tdfa.Compiler.compile()` to include subset-mask group configs in the step input, ensuring `\b`-guarded transitions include identifier continuations. Group's own configs added first to preserve priority in ε-closure dedup. The veryl scenario now reports the expected 124800 captures.
- [x] **Unicode case-fold `s ↔ ſ` for literal chars under `(?i)`** — Fixed in commit `74ab652`. Added `CaseFoldTable` (core/.../unicode/CaseFoldTable.java) with a reverse fold table mapping `toUpperCase(toLowerCase(cp))` to all BMP codepoints sharing it. Parser uses it when `unicodeShorthand && caseInsensitive`.
- [x] **Facade `matches()` tight-loop floor** — RESOLVED (2026-09-11): does
      NOT reproduce; retired as a session artifact. Re-ran the full 5×5
      `ParameterizedShortInputBench` matrix (same harness as the recorded
      table: gradle-jmh defaults, OPI 50 M) plus independent 3+5-iteration
      CLI runs, and captured the loop's inlining with
      `-Xlog:jit+inlining=debug`. The suspect (facade→engine indirection no
      longer inlining) is disproven: the final C2 compile of the hot loop
      devirtualizes and inlines END-TO-END — Function.apply → lambda →
      TDFAPattern.matches → GenNPattern.matcher → GenNMatcher.<init> →
      GenNMatcher.matches → TDFAPattern.wholeEngine → generated engine.match
      → TdfaRunner.match → runStringExtract — all inline with 100 %
      monomorphic type profiles; only the extractFrom walk leaf stays
      out-of-line ("hot method too big", by design). Numbers: both tiers at
      java.util.regex parity or better on 4/5 shapes (lit 30.7/28.4 vs jur
      29.8; ip 158.9/152.1 vs 189.6 — FASTER; two 186.5/174.2 vs 189.7 —
      faster; redos 308.7/293.2 vs 285.3), geomean ASM 1.02× / VM 0.90× jur.
      The 2026-09-03 session's own jur rows also moved −10…−20 % between the
      two dates (machine/JIT-state drift; rounds 6–7 code changes may share
      credit), which is what a session artifact looks like. Tables refreshed
      in BENCHMARKS.md §1 + README; no code change needed or made.
- [ ] ASM register coalescing / scalar replacement (registers → JVM locals)
- [ ] Revisit internals access: replace the strategy-trace hook (`-Dtdfa.trace.strategy`, `TdfaRunner.traceSnapshot`) with first-class observer/event API. Direction agreed 2026-08-15: expose the compilation pipeline (String regex → AST → TNFA → TDFA) to end-users for maximum reusability; the trace hook is a temporary conformance instrument, out of scope until the API-surface review.
- [ ] Cache-friendly flat-array data layout for VM backend

## Benchmark coverage

- [x] Vendor [rebar](https://github.com/BurntSushi/rebar) scenario corpus — `vendor/rebar-<sha>.tar.gz`; parsed by `:testlib:rebar`.
- [x] Vendor Glenn Fowler's testregex corpus — `vendor/testregex-<sha>.tar.gz` (preserved mirror of the AT&T original, ISC-style license); `TestregexFowlerTest` hard-gates re2j-longest parity over the 5 ERE spec files (578 params; Fowler's own POSIX expectations soft-reported — see the class javadoc).
- [x] Tracer-bullet parity test against rebar scenarios — `:tests:parity:rebar:RebarScenarioParityTest`. With the radical timeout/cap relaxation (`COMPILE_TIMEOUT_MS` 5 s → 2 min, `RUN_TIMEOUT_MS` 10 s → 10 min, `MAX_HAYSTACK_BYTES` 16 MB → 80 MB, `MAX_REGEX_LEN` 32 KB → 2 MB), `utf8-lossy` loader support, the scope restricted to scenarios rebar actually tests against Java (`java/hotspot` in engines list — see `docs/PARITY-PLAN.md`), and `compile` / `grep-captures` models implemented: **108 of 114 in-scope scenarios pass** (94.7 %), 2 surface known engine bugs (see "Correctness" below), 4 skip on `COMPILE_TIMEOUT` (bounded-repeat state explosion — see Performance below), 245 skip on the Java-scope filter (out of scope per the locked 2026-08 rule). End-of-suite `@AfterAll` summary prints skip-reason histogram + top-20 slowest tests + wall-time totals — see `docs/REBAR-PARITY-PLAN.md`.
- [x] Expand rebar parity — Phase 6.3 of `REBAR-PARITY-PLAN.md`: utf8-lossy loader fix, radical timeout/cap bumps. Surfaced the O(n²) extract bug (Phase 6.1) and the 4 bounded-repeat compile bombs (Phase 6.2). +34 scenarios passing (74 → 108).
- [x] Remaining rebar parity — **All in-scope scenarios now pass** (718/718 parameterized cases, 0 failures). The 3 engine correctness bugs (§A regopt interference, §B `\b` dead-end, §C Unicode case-fold) are fixed. 2026-08-18: the date/aws compile bombs were un-skipped after the determinize fast-path (date counts verified equal to live `java.util.regex` — JDK-26 tables drift patched in `vendor/patches/rebar/05-*.patch`); `CompileLatencyGuardTest` pins <5 s facade compiles. 2026-08-19: the test-side AST bomb heuristic was deleted — the engine's own determinization budget (re2c-identical caps) now rejects the one remaining shape. 2026-08-20 suite restructure: scope filtering moved to parameter-build time (228 cases, out-of-scope scenarios no longer appear), the context bomb skips by name (`BOMB_SCENARIOS`, opt-in via `-Dtdfa.test.rebar.skipBombs=false` + `-Dtdfa.max.states=250000` + ≥6 GB heap), the numeric time/size gates and raised-budget retry were removed, a budget rejection on any non-listed scenario is a FAILURE, and the module heap dropped 12 g → 2 g. The bomb's solo measurements live in the Performance note below.
- [ ] Hyperscan corpus / Snort rule set
- [ ] Long-input scan across diverse patterns (not just `\w+\d+\w+`)
- [ ] CI performance regression tracking — the harness landed (P7: `RegressionBench`/
      `QuickBench`, `scripts/bench-regression.sh [--quick|--jmh] [--capture]`,
      `bench-compare.py`, per-machine baselines — quick threshold 15 %, JMH 10 %);
      the CI wiring does not exist (ci.yml is correctness-only). Shared-runner
      timing noise needs a threshold decision before wiring.

## Engineering — "SQLite levels"

- [ ] **Namespace move `io.github.jemmix.*` → `io.github.tagmaton.*`** — must land
      before the first Maven publish (coordinates + Automatic-Module-Names are
      user-visible forever; also the re2j-jemmix vendor dir and the
      `github.com/jemmix/re2j` fork URL). Blast radius (145+ files): every java
      package tree (all modules, tests, benchmarks), per-module
      `Automatic-Module-Name`s, scripts (`bench-regression.sh`, `bench-rebar.sh`,
      `fuzz-soak.sh`, `lint-spotbugs.sh`, `vendor.sh`, `gen-unicode.py`),
      `config/spotbugs/exclusions.xml`, `ci/Smoke8.java`, README/docs, and the
      patched-re2j oracle's package-rewrite patch (`vendor/patches/re2j/`).
      JMH baselines record benchmark-class FQNs → re-capture
      `benchmarks/baselines/*` after the rename (quick mode suffices). Mechanical
      sed + full gate + jars pipeline + JDK 8 smoke.
- [ ] **Review/organize budget & tuning flags** — the `-D` surface grew
      accretion-style: compile caps `tdfa.max.{states,kernels,closure,work}`,
      minimizer/regopt knobs `tdfa.minimize.max`, `tdfa.minimize.norm.cells`,
      `tdfa.nominimize`, `tdfa.noregopt`, `tdfa.regopt.max`, runtime/engine
      `tdfa.engine`, `tdfa.trace.strategy`, diagnostics `tdfa.debug*` /
      `tdfa.asm.dump` / `tdfa.gen.debug`, plus the harness family `fuzz.*`
      (incl. the `fuzz.maxWork` gradle → `fuzz.max.work` → `tdfa.max.work`
      passthrough). Read-timing is already unified and pinned
      (`CompileKnobTimingTest`, c838547; inventory comment at Tdfa.java:496).
      Remaining: naming consistency (one `tdfa.max.*` family for the caps?),
      user-facing vs test/diagnostic split, and ONE documented table with
      defaults (README) — today the flags live only in javadoc, comments, and
      error-message strings.
- [x] SpotBugs / Error Prone / PMD — zero warnings — DONE (2026-09-04, REVIEW-2026-09 §1):
      ErrorProne 2.50.0 on facade/core/asm (zero findings after ~40 driven fixes;
      every suppression carries written rationale) + SpotBugs 4.9.8 hard-fail on
      findings AND analysis errors with gated per-item exclusions
      (`config/spotbugs/exclusions.xml`); CI-enforced (`spotbugs` job on JDK 25 —
      4.9.x cannot scan JDK 26 runtime classes, tasks self-skip there) and
      standalone-decoupled (`scripts/lint-spotbugs.sh`; EP as plain javac plugin).
      PMD never adopted — EP+SpotBugs cover the chosen ground; Checkstyle/Qodana
      one-offs run and declined with reasons (§1b).
- [x] ~~Checkstyle / Spotless — enforced code style~~ — RESOLVED AS DECLINED
      (2026-09-04, REVIEW-2026-09 §1b): Checkstyle 14.1 one-off over facade/core/asm —
      curated semantic config: 55 findings / 8 real, all fixed; google_checks 99.7 %
      formatting noise (8325 Indentation alone). Verdict: not worth wiring on a frozen
      codebase (EP + SpotBugs cover the semantic ground); Spotless moot — no
      formatter churn wanted on a freeze-bound tree.
- [ ] JaCoCo coverage targets (line + branch)
- [ ] JavaDoc for all public API surface
- [ ] API stability guarantees (signatures locked at 1.0)
- [ ] Multi-JDK CI matrix — SUPERSEDED SHAPE; CI live since 2026-09-04
      (REVIEW-2026-09 §1c/§1d): `.github/workflows/ci.yml` = `check`@JDK 26 (full
      gate incl. EP), `spotbugs`@JDK 25 (where 4.9.x actually enforces),
      `jars-and-tests` (core+asm by a real JDK 8 javac, unit tests vs packaged jars
      on 25, JDK 8 runtime smoke; SHA-pinned actions, wrapper validation) — a
      deliberate floor-proof trio, not an 11/17/21/25 runtime matrix. A broader
      consumer-runtime matrix remains unadopted; reopen only with evidence of a
      runtime we actually need to cover.
- [ ] GraalVM native-image compatibility
- [ ] Android API-level compatibility check
- [ ] JPMS module info (`module-info.java`)
- [ ] Reproducible builds (deterministic jar output)
- [x] Thread safety audit (`Matcher` reuse, `Pattern` sharing) — DONE (2026-09-04,
      REVIEW-2026-09 §1 B4 + Phase C, 13912fd): SearchDfa mutation confined under a
      lock with lock-free immutable-snapshot reads; walkBlockIdx tables published via
      AtomicReferenceArray; the false "ThreadLocal" comment fixed. Thread-safety +
      mutable-CharSequence contracts documented on Pattern/PatternMatcher/
      CompiledRegex/MatchResult. Pinned by `ConcurrencyHammerTest` (8 threads,
      bit-identical digests vs the single-threaded reference).
- [ ] Memory leak testing (generated class GC under load)
- [x] Security review (untrusted regex DoS: compile-time blowup, state explosion) —
      DONE: every compile phase is work-metered with clean `PatternSyntaxException`
      rejection (WorkMeter + re2c-parity state/kernel/closure caps, `CompileBudgetTest`;
      minimizer cell budget P1 #3), parser resource caps close the pre-determinization
      surface ({n,m} ≤ 1000, group depth ≤ 256 — REVIEW §1 B2), and the fuzz-scoped
      budget demonstrates the bomb families rejecting in seconds. README documents
      the honest eager-DFA cost vs lazy engines.

## Single-compile whole/find (2026-09-12)

`Pattern.compile` builds everything eagerly from one parse: the whole-match
artifact is a cut-free (`compileUnpruned`) determinization of the same TNFA,
shared with find() whenever the pike-cut predicate says the cut would change
nothing (see `Tdfa.pikeCutMatters`); divergence-class patterns keep a pruned
find artifact beside it. `matchWhole` walks to EOF — an accept config alive at
end-of-input is a full match. Work that remains:

- [ ] **DESIGN RULE — no lazy compiles, ever: if it dies during construction,
      it dies.** `Pattern.compile` must be the whole story; no engine may be
      materialized on first match call. The current `LazyEngine` corner in
      `PatternCompiler`'s budget ladder (whole build over `WHOLE_WORK_CAP` →
      historical lazy anchored engine, rejection surfaces at first
      `matches()`) is an accepted-for-now DEVIATION kept solely so
      compile() acceptance stays tied to the find artifact — remove it: the
      whole artifact is built eagerly or `compile()` fails with the budget
      rejection outright. Landing this requires a corpus-impact decision
      first (bombs like `(a{1,100}){1,100}` currently compile find-only and
      would start failing compile(); `SingleCompileWholeTest.
      bombOverBudgetKeepsHistoricalContract` pins the deviation and flips
      with it).
- [ ] Inline `matchWhole` into generated engines (ASM tier) — it is currently
      a delegating stub to the embedded `TdfaRunner`, so `matches()` runs the
      interpreted walk instead of the previously-inlined generated anchored
      walk. Emit the whole-walk leaf the same way `extractOne` is emitted if
      the benchmark run below shows it matters.
- [ ] **Benchmark the single-compile change** — not yet measured. Run
      `scripts/bench-regression.sh --quick` (then `--jmh` if quick is clean)
      against `benchmarks/baselines/`: expected finds: compile deltas
      (dictionary single-compile faster; datefinder ~2× slower — two eager
      determinizations, unpruned whole + pruned find), `matches()`
      throughput (interpreted walk now — see previous item), `find()`
      expected flat (tables identical: same artifact for safe patterns, same
      pruned compile for cut-matters). Recapture baselines per policy if
      within thresholds.
- [ ] Pre-existing, now more visible: `RegexEngine.matches` (the boolean
      whole walk) is wrong on raw PRUNED artifacts — `(a|ab)` on `"ab"`
      returns false (the cut deleted the `ab` continuation; facade-built
      engines are correct since they carry the unpruned artifact). Either
      reroute it through `matchWhole != null` everywhere or document the
      anchored-artifact-only contract on the interface.
- [ ] Overnight fuzz round for the unpruned determinization (validated so
      far with a 4×2 min patched-oracle soak vs the pre-change commit:
      0 mismatches both sides).
- [ ] Maybe: shave the double determinization for cut-matters patterns
      (parse is shared; determinization is the whole cost — a smarter
      reuse would need the subordinate-marking refinement discussed in the
      design round; only worth it if datefinder-class compile walls bother
      anyone after the benchmark run).

## Wishlist (maybe, someday, if motivated)

- [ ] `condy` / `invokedynamic` for lazy per-regex specialization
- [ ] Tiered compilation hints (`@Contended`, `@Stable`)
- [ ] SIMD-accelerated `find()` for fixed-string prefixes (`String.indexOf` vectorization)
- [ ] Ahead-of-time class persistence (compile regex to `.class` on disk, load at startup)
- [ ] POSIX longest-leftmost capture groups (not just match boundaries) — TRUE POSIX
      submatch maximization. Note: the BT19 §7 winner-selection scaffolding that used to
      sit dormant in Tdfa.java was removed in the 2026-09-03 cleanup (NOT-NEEDED for the
      re2j-parity contract, proven by the 2026-08-18 soak); resurrect from git history
      (tag pre-cleanup) + the BT19 paper if this is ever taken up.
- [ ] Streaming input (match against `InputStream` / `ByteBuffer` without materializing)
