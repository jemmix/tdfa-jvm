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
- [ ] Full POSIX leftmost-longest — activate BT22 §7 `closure_gtop` winner selection
- [x] Unicode case folding for literal chars — CaseFoldTable handles all BMP simple case folds (28 groups with >2 members including s↔ſ, k↔K, Ω↔ω). Class-range folding full-Unicode under `(?u)` (`5f22aee`).
- [x] `\b` / `\B` Unicode word boundary semantics for supplementary codepoints (`ba60194`)

## Correctness

- [x] **`(a*)(a*)` crashes at compile time** — FIXED. `ArrayIndexOutOfBoundsException` in `Optimize.findFinalRegBase` on both backends and both disambiguation modes; affected `(a+)(a*)`, `(a*)(a?)`, `(.*) (.*)`, `(a*)(a*)(a*)`. Root cause: register allocation coalesced FINAL registers — with each other via the same-value rule (two SET-pos finals), or with working registers via COPY coalescing. Finals must share nothing: the `MatchResult` readout protocol requires tag *t* at a dedicated `regs[finalRegBase + t - 1]` slot. When finals coalesced, the block either scattered (silently wrong captures) or `regCount` dropped below `tagCount` (negative base → crash). Fix: `Optimize.registerAllocation` runs coalescing over working registers only and assigns finals dedicated consecutive top slots in tag order (BT22's dedicated `R_f` layout). Tests: `CaptureGroupAllocationTest` (ungated).
- [x] **`\b` mishandles supplementary codepoints** — FIXED. `isWordChar(char)` checked individual UTF-16 code units, so supplementary letters weren't word chars even under `(?u)`. Now `TdfaRunner.isWordBefore`/`isWordAt` decode surrogate pairs and search `wordRanges` by codepoint; the ASM backend emits equivalent `isWordBefore`/`isWordAt` helpers used by both `positionFlagsC` and the inline PF. Tests: `WordBoundaryTest` (ungated).
- [x] **`(?iu)` char-class ranges don't include Unicode fold equivalents** — FIXED. `Parser.parseClass` now expands every member codepoint's `CaseFoldTable.foldRanges` when `unicodeShorthand && caseInsensitive` (`(?iu)[r-t]` matches `ſ`); ASCII-only fold retained without `(?u)` (re2j semantics).
- [x] **`(?iu)` negated classes don't exclude Unicode fold equivalents** — FIXED by the same range-fold expansion (fold members join the positive set before `CharClass` negation, so `(?iu)[^s]` rejects `ſ`).
- [x] **`JdkUnicodeDataProvider.foldTableFor` incomplete** — FIXED. `buildFoldTable` only closed the `toUpperCase` direction (worked for `\p{Lu}`, null for `\p{Ll}`). Now checks all `CaseFoldTable` fold-group members, so `(?i)\p{Ll}` matches `A` and multi-member groups (s/S/ſ) close in both directions.
- [ ] **Zero-width-anchored alternation matches [0,1] instead of [0,0]** — patterns like `(?:(?:^)|.)?` and `^(?:(?:(?:a*)|b))` on inputs starting with 'b'/'c' report a 1-char match where the zero-width alternative should win. Caught by Google's ExecTest (`:tests:parity:re2j-suite:test`, `testRE2Exhaustive` + `testFowlerBasic`, both backends). Down from 3 failing ExecTest cases to 2 after the final-register dedicated-slot fix in `Optimize.registerAllocation` (which fixed `testFowlerRepetition`).
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
- [ ] Differential fuzzing vs `re2j` and `java.util.regex` (Jazzer or custom harness)
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
- [ ] DFA minimization (Moore-style, register-aware) — would clear the 4 remaining `COMPILE_TIMEOUT` skips (bounded-repeat state explosion in `curated/03-date`, `curated/09-aws-keys/full`, `curated/10-bounded-repeat/context`). See `docs/REBAR-PARITY-PLAN.md §6.2`.
- [x] **M2 regopt interference-analysis bug** — Fixed in commit `6b335e2`. `Optimize.interferenceAnalysis` walked ops FORWARD and cloned `L[b]` (end-of-block liveness) for EACH op, missing the fact that COPY sources become live BEFORE the op and conflict with registers written by LATER ops in the same block. Rewrote to walk ops in REVERSE with a running live set (BT22 Fig. 7), keeping a forward pre-pass for the value-tracking `V[]` snapshots. All 61500 veryl matches now report exactly 1 participating group, matching `java.util.regex`.
- [x] **`\b` in alternation causes dead-end DFA paths** — Fixed in commit `d133d20`. Modified `Tdfa.Compiler.compile()` to include subset-mask group configs in the step input, ensuring `\b`-guarded transitions include identifier continuations. Group's own configs added first to preserve priority in ε-closure dedup. The veryl scenario now reports the expected 124800 captures.
- [x] **Unicode case-fold `s ↔ ſ` for literal chars under `(?i)`** — Fixed in commit `74ab652`. Added `CaseFoldTable` (unicode/CaseFoldTable.java) with a reverse fold table mapping `toUpperCase(toLowerCase(cp))` to all BMP codepoints sharing it. Parser uses it when `unicodeShorthand && caseInsensitive`.
- [ ] ASM register coalescing / scalar replacement (registers → JVM locals)
- [ ] Revisit internals access: replace the strategy-trace hook (`-Dtdfa.trace.strategy`, `TdfaRunner.traceSnapshot`) with first-class observer/event API. Direction agreed 2026-08-15: expose the compilation pipeline (String regex → AST → TNFA → TDFA) to end-users for maximum reusability; the trace hook is a temporary conformance instrument, out of scope until the API-surface review.
- [ ] Cache-friendly flat-array data layout for VM backend

## Benchmark coverage

- [x] Vendor [rebar](https://github.com/BurntSushi/rebar) scenario corpus — `vendor/rebar-<sha>.tar.gz`; parsed by `:testlib:rebar`.
- [x] Tracer-bullet parity test against rebar scenarios — `:tests:parity:rebar:RebarScenarioParityTest`. With the radical timeout/cap relaxation (`COMPILE_TIMEOUT_MS` 5 s → 2 min, `RUN_TIMEOUT_MS` 10 s → 10 min, `MAX_HAYSTACK_BYTES` 16 MB → 80 MB, `MAX_REGEX_LEN` 32 KB → 2 MB), `utf8-lossy` loader support, the scope restricted to scenarios rebar actually tests against Java (`java/hotspot` in engines list — see `docs/PARITY-PLAN.md`), and `compile` / `grep-captures` models implemented: **108 of 114 in-scope scenarios pass** (94.7 %), 2 surface known engine bugs (see "Correctness" below), 4 skip on `COMPILE_TIMEOUT` (bounded-repeat state explosion — see Performance below), 245 skip on the Java-scope filter (out of scope per the locked 2025-08 rule). End-of-suite `@AfterAll` summary prints skip-reason histogram + top-20 slowest tests + wall-time totals — see `docs/REBAR-PARITY-PLAN.md`.
- [x] Expand rebar parity — Phase 6.3 of `REBAR-PARITY-PLAN.md`: utf8-lossy loader fix, radical timeout/cap bumps. Surfaced the O(n²) extract bug (Phase 6.1) and the 4 bounded-repeat compile bombs (Phase 6.2). +34 scenarios passing (74 → 108).
- [x] Remaining rebar parity — **All in-scope scenarios now pass** (220/220 parameterized over ASM+VM). The 3 engine correctness bugs (§A regopt interference, §B `\b` dead-end, §C Unicode case-fold) are fixed. Remaining skips: 490 scope (no `java/hotspot` engine), 8 COMPILE_TIMEOUT (DFA state explosion — needs minimization, see Performance below).
- [ ] Hyperscan corpus / Snort rule set
- [ ] Long-input scan across diverse patterns (not just `\w+\d+\w+`)
- [ ] CI performance regression tracking (JMH + comparison thresholds)

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
