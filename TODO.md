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
> headers, first Maven publish.

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
- [ ] Add more parity tests — expand coverage to edge cases not yet exercised (backreference semantics, large repetition counts, nested quantifiers, Unicode line boundaries, canonical equivalents, etc.). Gate known-failing or not-yet-implemented cases with `@EnabledIfSystemProperty(named = "tdfa.pending", matches = "true")` and a `// PENDING:` comment; run the full set with `./gradlew test -Dtdfa.pending=true`. Clear the gate as each feature lands.
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
- [ ] **`.` on non-BMP codepoints undercounts vs re2** — `.` on `💩` (U+1F4A9) gives 1 match in our engine (one codepoint); re2 gives 4 (UTF-8 bytes). Our engine is codepoint-oriented like `java.util.regex`, not byte-oriented like re2. **Resolved at the test level**: `vendor/patches/rebar/01-dot-matches-byte-codepoint.patch` rewrites the rebar scenario to record our actual count (1) under an explicit `{ engine = 're2', count = 1 }` entry — see the patch file for the rationale comment. Fundamental architecture choice, not a bug.
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
- [ ] **`.+\b.` undershoots when the last boundary precedes a supplementary pair** — FOUND by the fuzzer
      (2026-08-27; seed `7713360668071298679`). Minimal: `.+\b.` on `\u03a99\ud800\udfff` →
      jdk AND re2j match `\u03a99\ud800\udfff` (boundary after 9, `.` eats the pair); both our engines
      stop at `\u03a99` (earlier accept at boundary Ω|9). Single-boundary input `9\ud800\udfff` is
      correct — needs two candidate boundaries. Not the documented codepoint-vs-unit divergence:
      our own codepoint semantics say the answer includes the pair. Core walk (position flags ×
      pair step × accept priority), both engines.
- [ ] **`(?:.*?9{0,}\b){1,}` prefers a non-empty first iteration** — FOUND by the fuzzer (2026-08-27;
      seed `516468605110627597`). Minimal: on `Z\t` → jdk/re2j match `[]@0` (lazy `.*?` stays empty,
      `\b` holds at input start before word `Z`); both our engines match `[Z]@0`. Components alone
      (`.*?\b`, `(?:.*?\b){1,}`, `9{0,}\b`) are all correct — the nesting is the trigger.
      Zero-width-iteration priority in the core, both engines.
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
- [ ] **re2j plain-`(?i)` folds class ranges with full Unicode simple folding** (`(?i)\w` matches ſ;
      ſ/K/Ω fold groups) while we fold ASCII-only without `(?u)` — documented divergence in the fuzzer;
      decide: adopt full-BMP simple folding as the default (re2j parity) or keep the two-tier design.
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
      boundary-rich inputs, both engines vs re2j) is `tdfa.pending`-gated until the remaining
      stop-or-extend family lands; `CompileBudgetTest.workBudgetRejectsClosureSpinners` gates
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
- [ ] Fallback / backup operations (BT22 §3.2) — restore clobberable registers on dead-end
- [ ] Verify TDFA(1) strict conformance vs paper wording (lookahead delay semantics)
- [ ] Multi-valued tags (tags under repetition accumulating multiple offsets)
- [ ] Property-based testing (random regex generation + differential oracle)

## Performance

- [x] **O(n²) unanchored `find()` — no-match case** — fixed via multi-state parallel simulation in `TdfaRunner.multiStateAnyMatch`: a single forward pass tracks the set of all DFA states reachable from any start position (O(n × |states|)), replacing the outer-loop restart. Used for boolean `find()` directly and as a no-match pre-check for the extract path. 200 K-char no-match haystack: ~14 ms (was >30 s).
- [x] **O(n²) `find()` on dense matches** — FIXED (P1). `multiStateLeftmostStart` runs the multi-state simulation with per-state origin tracking (double-buffered with the state sets); the extract walk starts directly at the leftmost match position, replacing the retry-every-failed-start shape. leipzig `[a-zA-Z]+ing` findAll: 41 ms → 19 ms per 512 KB (2.2×, both backends, same 2351 matches). Note: the original 249 s/16 MB figure was stale — the stopOnAccept short-circuit (REBAR-SPEEDUP-PLAN §Tier-2 #3) had already cut it to ~41 ms/512 KB before P1 landed.
- [x] **ASM backend hits the 65 KB JVM method limit** — fully solved via `TdfaAsmBackend.pickMode`, which selects one of three dispatch modes per pattern: `INLINED` (per-state range checks, fastest), `TABLE_SCAN` (per-state compact binary search over `RANGES_TABLE`), or `DELEGATE` (thin wrapper that forwards to a `TdfaRunner`). Combined with `<clinit>` no longer materializing per-element arrays (ENTRY/ACCEPT/STOP/IS_ACCEPT/ASCII_TARGET all become reference copies or runtime loops in `<init>`), no in-scope rebar pattern throws `MethodTooLargeException`. The old VM-retry path in `RebarScenarioParityTest` was removed; both backends now run as peer parameter values.
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
- [x] **Unicode case-fold `s ↔ ſ` for literal chars under `(?i)`** — Fixed in commit `74ab652`. Added `CaseFoldTable` (unicode/CaseFoldTable.java) with a reverse fold table mapping `toUpperCase(toLowerCase(cp))` to all BMP codepoints sharing it. Parser uses it when `unicodeShorthand && caseInsensitive`.
- [ ] ASM register coalescing / scalar replacement (registers → JVM locals)
- [ ] Revisit internals access: replace the strategy-trace hook (`-Dtdfa.trace.strategy`, `TdfaRunner.traceSnapshot`) with first-class observer/event API. Direction agreed 2026-08-15: expose the compilation pipeline (String regex → AST → TNFA → TDFA) to end-users for maximum reusability; the trace hook is a temporary conformance instrument, out of scope until the API-surface review.
- [ ] Cache-friendly flat-array data layout for VM backend

## Benchmark coverage

- [x] Vendor [rebar](https://github.com/BurntSushi/rebar) scenario corpus — `vendor/rebar-<sha>.tar.gz`; parsed by `:testlib:rebar`.
- [x] Vendor Glenn Fowler's testregex corpus — `vendor/testregex-<sha>.tar.gz` (preserved mirror of the AT&T original, ISC-style license); `TestregexFowlerTest` hard-gates re2j-longest parity over the 5 ERE spec files (578 params; Fowler's own POSIX expectations soft-reported — see the class javadoc).
- [x] Tracer-bullet parity test against rebar scenarios — `:tests:parity:rebar:RebarScenarioParityTest`. With the radical timeout/cap relaxation (`COMPILE_TIMEOUT_MS` 5 s → 2 min, `RUN_TIMEOUT_MS` 10 s → 10 min, `MAX_HAYSTACK_BYTES` 16 MB → 80 MB, `MAX_REGEX_LEN` 32 KB → 2 MB), `utf8-lossy` loader support, the scope restricted to scenarios rebar actually tests against Java (`java/hotspot` in engines list — see `docs/PARITY-PLAN.md`), and `compile` / `grep-captures` models implemented: **108 of 114 in-scope scenarios pass** (94.7 %), 2 surface known engine bugs (see "Correctness" below), 4 skip on `COMPILE_TIMEOUT` (bounded-repeat state explosion — see Performance below), 245 skip on the Java-scope filter (out of scope per the locked 2025-08 rule). End-of-suite `@AfterAll` summary prints skip-reason histogram + top-20 slowest tests + wall-time totals — see `docs/REBAR-PARITY-PLAN.md`.
- [x] Expand rebar parity — Phase 6.3 of `REBAR-PARITY-PLAN.md`: utf8-lossy loader fix, radical timeout/cap bumps. Surfaced the O(n²) extract bug (Phase 6.1) and the 4 bounded-repeat compile bombs (Phase 6.2). +34 scenarios passing (74 → 108).
- [x] Remaining rebar parity — **All in-scope scenarios now pass** (718/718 parameterized cases, 0 failures). The 3 engine correctness bugs (§A regopt interference, §B `\b` dead-end, §C Unicode case-fold) are fixed. 2026-08-18: the date/aws compile bombs were un-skipped after the determinize fast-path (date counts verified equal to live `java.util.regex` — JDK-26 tables drift patched in `vendor/patches/rebar/05-*.patch`); `CompileLatencyGuardTest` pins <5 s facade compiles. 2026-08-19: the test-side AST bomb heuristic was deleted — the engine's own determinization budget (re2c-identical caps) now rejects the one remaining shape. 2026-08-20 suite restructure: scope filtering moved to parameter-build time (228 cases, out-of-scope scenarios no longer appear), the context bomb skips by name (`BOMB_SCENARIOS`, opt-in via `-Dtdfa.test.rebar.skipBombs=false` + `-Dtdfa.max.states=250000` + ≥6 GB heap), the numeric time/size gates and raised-budget retry were removed, a budget rejection on any non-listed scenario is a FAILURE, and the module heap dropped 12 g → 2 g. The bomb's solo measurements live in the Performance note below.
- [ ] Hyperscan corpus / Snort rule set
- [ ] Long-input scan across diverse patterns (not just `\w+\d+\w+`)
- [ ] CI performance regression tracking (JMH + comparison thresholds; NOTE `scripts/bench-regression.sh` needed a classpath fix post-module-restructure — re-captured the quick baseline 2026-08-18)

## Engineering — "SQLite levels"

- [ ] SpotBugs / Error Prone / PMD — zero warnings
- [ ] Checkstyle / Spotless — enforced code style
- [ ] JaCoCo coverage targets (line + branch)
- [ ] JavaDoc for all public API surface
- [ ] API stability guarantees (signatures locked at 1.0)
- [ ] Multi-JDK CI matrix (11, 17, 21, 25)
- [ ] GraalVM native-image compatibility
- [ ] Android API-level compatibility check
- [ ] JPMS module info (`module-info.java`)
- [ ] Reproducible builds (deterministic jar output)
- [ ] Thread safety audit (`Matcher` reuse, `Pattern` sharing)
- [ ] Memory leak testing (generated class GC under load)
- [ ] Security review (untrusted regex DoS: compile-time blowup, state explosion)

## Wishlist (maybe, someday, if motivated)

- [ ] `condy` / `invokedynamic` for lazy per-regex specialization
- [ ] Tiered compilation hints (`@Contended`, `@Stable`)
- [ ] SIMD-accelerated `find()` for fixed-string prefixes (`String.indexOf` vectorization)
- [ ] Ahead-of-time class persistence (compile regex to `.class` on disk, load at startup)
- [ ] POSIX longest-leftmost capture groups (not just match boundaries)
- [ ] Streaming input (match against `InputStream` / `ByteBuffer` without materializing)
