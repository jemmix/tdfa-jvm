# Complexity review — the laundry list

A fine-toothed-comb pass over the tree for **reducible complexity**: dead
machinery, dispatch ladders with redundant rungs, algorithms carrying more
generality than they earn, vestigial API, and layering that can fold. The
precedent is the 2026-10 memo deletions (`SearchDfa` #97, `WalkIndex` #98):
structures that survived audits until someone priced them. This list is the
inventory of everything else that looks priceable, big and small.

**Method.** Every item below is grounded in the tree at `8910439` (main,
post-#98). Each item is being examined by a delegated reviewer against the
actual code, the priced docs, and the test/bench surface; verdicts are
recorded here as they land. An item's verdict is one of:

- **remove** — dead or dominated; deleting is correct, no measurement needed;
- **simplify** — same answer, less machinery; the shape is named;
- **measure-first** — plausible but must clear the bench gate (15 % rule) first;
- **keep** — the complexity is load-bearing; the argument is recorded so it
  is not re-litigated blind.

Sizes: S = mechanical (hours), M = one PR, L = multi-PR program,
XL = architectural bet.

## Status at a glance

| Cluster | Items | Verdicts in | State |
|---|---|---|---|
| A. Memo-deletion debris | C1–C5 | 0/5 | pending |
| B. Runner dispatch ladder | C6–C12 | 0/7 | pending |
| C. ASM backend | C13–C16 | 0/4 | pending |
| D. Facades & API | C17–C21 | 0/5 | pending |
| E. Compile pipeline & determinizer | C22–C29 | 0/8 | pending |
| F. Unicode & data | C30–C31 | 0/2 | pending |
| G. Budget system | C32 | 0/1 | pending |
| H. Tests & infrastructure | C33–C38 | 0/6 | pending |
| **Total** | **38** | **0** | **in review** |

## A. Memo-deletion debris (the #97/#98 tail)

The two match-time memos are gone; the machinery that sized, split, and
capped them is largely still here. The tradeoffs doc already flags this
("Deliberately left behind for the review").

- **C1 — dead memo budget caps** (S, remove). `Budgets.sdfaMaxRows`,
  `sdfaMaxBlocks`, `walkMaxBytes` have **zero production callers** (only
  `BudgetModelTest` exercises them). Their `BudgetWeights` constants
  (`RUNTIME_ROW_FIXED_BYTES`, `RUNTIME_ROW_STATE_BYTES`, `RUNTIME_BLOCK_BYTES`,
  `RUNTIME_MIN_ROWS/BLOCKS`, `WALK_*`, `RUNTIME_WALK_DIVISOR`) and the
  eighths-partition javadoc ride along. Delete the methods + constants +
  the test rows; re-point the docs.
- **C2 — `memoBudgetBytes` plumbing** (S/M, simplify). The field on
  `TdfaRunner` calls itself VESTIGIAL in its own javadoc, yet threads
  through `EngineBackend.generate`, both `TdfaAsmBackend.generate`
  overloads, `genInit`/`genDelegateInit` (emitted LDC!), `AsmEngineBackend`,
  and `PatternCompiler`'s split arithmetic. With no memo left the whole
  parameter is theatre; collapse to nothing (or a plain retained-bytes
  check) and delete the emitted constant.
- **C3 — `Strategy.TRIGGER`** (S, remove/alias). The enum javadoc admits the
  rung "now traces RAW_SCAN" — the trigger scan *is* the raw scan since #97.
  The value survives as a forced-rung identity in the fuzzer and three
  battery tests. Merge into `RAW_SCAN` (keep the trace string stable where
  the fuzzer parses it).
- **C4 — `Tdfa` G3 lazy caches** (S/M, simplify — already decided, execute).
  `stopMaskTableCache`, `posFlagDepsCache`, `absBeginBoundCache` are
  `@MemoCache`-annotated benign-race lazy fields whose W4 disposition
  ("precompute at construction; delete the three fields") is agreed in
  `docs/runner-immutability.md` but unexecuted. Also: the
  `stopMaskTableCache` javadoc is pasted twice (Tdfa.java ~293–303) — a
  two-line cleanup whenever touched.
- **C5 — `@MemoCache` + finality-gate allowlist endgame** (S, keep-but-shrink).
  With W4/W5 done the annotation has zero annotated fields by design. The
  gate (RunnerFinalityGateTest) stays; decide whether the annotation type
  itself stays as the mandated shape for any future cache (the doc says yes)
  or goes until needed. Record it; one-line either way.

## B. Runner dispatch ladder (`TdfaRunner`, 3 474 lines)

- **C6 — the 11-rung strategy ladder** (M/L, simplify). `LITERAL, PREFIX,
  CAND_SCAN, EXACT_FROM, ORIGIN_SIM, TRIGGER, RAW_SCAN, WALK_RESTART,
  ANCHORED_FAST, ANCHORED, GENERIC`. Two are duplicates post-#97 (TRIGGER,
  and `ORIGIN_SIM` vs the restart loop it bounds — `LSS_BUDGET_CHARS`,
  `multiStateLeftmostStart`, `LSS_BUDGET`). EXACT_FROM-before-PREFIX,
  PREFIX, and CAND_SCAN cover overlapping short-input regimes. Question per
  rung: does it still clear its keep-cost on the post-memo board, or is it
  a rung the raw scan now serves at par? (Precedent: the memo's trigger
  rung survived by serving raw.)
- **C7 — bool/extract path duality** (M/L, measure-first). The ladder
  exists twice — boolean and extract variants hand-duplicated:
  `prefixScanBool`/`prefixScanExtract`, `candScanBool`/`candScanExtract`,
  `boolWalkAt`/`extractWalkAt`, `runStringFindFast` vs
  `runStringExtractFast`, anchored pairs. A single extract path with a thin
  boolean wrapper would halve the paths; the counterargument is
  register-allocation cost on failed boolean walks. Measure the find()
  benches both ways before folding.
- **C8 — unit/codepoint scan twins** (M, simplify). `triggerScan` vs
  `unitTrigger`, `rawScan` vs `unitRawScan` (plus a `unitSeeds` flag
  variant): the same simulation twice, once per boundary discipline. The
  difference is small (pair-interior skips, start enumeration); a
  parameterized walk is plausible. Same shape as C7 — attempt after, not
  before.
- **C9 — `runGeneric`, the third copy** (M, simplify). The CharSequence
  (non-String) fallback re-implements the walk against `charAt`. With C7/C8
  folded this collapses to the one parameterized walk plus a String fast
  prefix. Keep the GENERIC trace rung.
- **C10 — test hooks in the production runner** (M, simplify — TODO'd).
  `FORCED`/`anyForced` statics, `setForcedStrategy`, `forcedSearch`,
  `forcedRestart`, `TRACE`/`setTracing`, `TRACE_BUF`, `WTRACE` — all
  fuzz/test instruments living inside the shipped engine class (G5
  allowlist). TODO already carries "TdfaRunner trace → per-engine
  (test-only)". Move behind a test-visible seam (subclass or
  package-private SPI in a test artifact) so the runner body carries zero
  instrument branches. Note `forcedStrategy()` is read on EVERY find/match
  call — a ThreadLocal get on the hot path.
- **C11 — dual dispatch-table tiers** (M, measure-first). `asciiTarget`
  (128, simulations) vs `latinTarget` (256, walks) + `latinLimit` +
  `LATIN1_MAX_STATES` (8 192) + `ASCII_TABLE_MAX_STATES` (16 384) + the
  same-object aliasing rules. Two thresholds and a table-span dance for a
  measured 15 % ASCII-scan effect (documented) vs direct dispatch on
  accented text. Price one tier (256 with the compact-table guard) against
  the two-tier logic.
- **C12 — `ADAPTIVE_PREFILTER_AFTER`** (S, measure-first). After 3 failed
  extract walks the candidate loop switches to a boolean pre-filter. The
  switch is an `@EmittedSurface` constant baked into generated classes —
  two loop shapes in both tiers. If the pre-filter wins everywhere now,
  make it unconditional; if it never wins, delete it.

## C. ASM backend (`TdfaAsmBackend`, 2 929 lines)

- **C13 — INLINED vs DELEGATE emission modes** (L, measure-first). Two
  full emission programs: `pickMode` inlines fastPath DFAs into dedicated
  method bodies; everything else delegates to an embedded `TdfaRunner`.
  The delegate mode is the correctness net the INLINED mode is measured
  against — but is the INLINED tier's win worth a second emitter, or can
  the generated class be a thin monomorphic shell over the shared runner
  (DELEGATE-only) with named-method dispatch? The bench gate decides; the
 Emitter halves if it passes.
- **C14 — the stack-register emission axis** (M, measure-first). A third
  variant bit: `stackRegsEligible()` + `genExtractOne(stackRegs)` +
  `genWholeOne(stackRegs)` + carrier-free classes + the 65 KB soft budget.
  If C13 folds to DELEGATE, this dies with it; independently, price
  stack-regs vs the plain regs pool on the extract benches.
- **C15 — emitted-shell machinery** (M, simplify). `ShellEmitter` (402),
  `ShellSpec`, `genDelegateInit`, `genMetadataMethods`, the
  `@EmittedSurface` 7-field shell contract on `engine.Matcher`, and
  `PatternMatcher` extended by generated children in child classloaders.
  A generic (non-emitted) shell over a `RegexEngine` reference exists
  conceptually in the VM path; quantify what the emitted shell buys
  (devirtualization) vs its maintenance surface, or at least shrink the
  emitted surface to the three hot entry points it justifies.
- **C16 — emitted ladder re-implements the VM ladder** (L, keep-or-shared).
  The generated find/match ladders mirror `TdfaRunner`'s semantics by
  hand. Related TODO: "Shared Tdfa for asm+vm (halve fuzz compile churn)".
  A single parametric ladder spec consumed by both tiers would delete a
  whole class of cross-tier drift bugs (the fuzz forced-rung machinery
  exists largely to police this). Big lift; the drift evidence decides.

## D. Facades & API

- **C17 — surface registry & serialization layering** (M, simplify). Two
  surfaces (root jur `:`, `:re2j`) is decided policy (two readings, baked
  in). But the machinery underneath — `PatternSurface.register/resolve`,
  `triggerClass` classloading, `SerialProxy`, `noCodegenWrapper`,
  `VmPattern` wrappers, `Surfaces` holders — is a lot of moving parts for
  two call sites. Examine: direct references from each facade instead of a
  string-keyed registry, with serialization pinned to a two-entry enum.
- **C18 — `Pattern.reset()` no-op** (S, remove). Documented no-op on both
  facades ("releases internal caches" — there are none since the memos
  died). Pure re2j-API-compat residue; drop before the 1.0 API lock or
  keep deliberately for drop-in parity — one-line decision, currently
  undecided complexity.
- **C19 — compile-channel sprawl** (S/M, simplify). `Pattern` offers
  compile ×4 overloads + compileFind ×2 + CompileOptions (semantics,
  provider, observer) + the flag int. Three channels to say one thing.
  Pre-1.0 is the moment to pick: flags + CompileOptions, and let the
  factory overloads go through options.
- **C20 — machinery-internal axis bits on `PatternSurface`** (S, simplify).
  `UNIX_LINES..SPACE_RE2` + `RE2_AXES` exist only for `semanticsOf`'s
  bit→axis mapping and tests; no surface accepts them. `Semantics` already
  spells the axes; the int vocabulary is a second spelling of the same
  thing — fold `semanticsOf` into `Semantics`-of-flags or delete the
  constants and switch the one consumer.
- **C21 — the 8-axis `Semantics` matrix** (M, decide). Every axis pair is
  pinned by tests; TODO carries "Decide Semantics scope (parity axes vs
  knobs) before lock". If per-axis mixing has no consumer beyond probes,
  freeze the two lanes as named presets and make `CompileOptions.semantics`
  lane-selecting rather than axis-building. Half the matrix's test surface
  evaporates.

## E. Compile pipeline & determinizer

- **C22 — `Tdfa` telescoping constructors** (S, simplify). 24-, 29-, and
  31-arg telescoping constructors, the shorter ones fabricating defaults
  (ALL_AXES!). Callers: the materializer + PikeSim + tests. One builder (or
  keep the 31-arg and make the rest static factories with names) deletes
  two footguns and the legacy-lane trap.
- **C23 — the whole-match machinery** (XL, keep-or-simplify — the biggest
  item on the list). One artifact serving find() AND matches() costs:
  `compileWithWholeSide`, the partial-whole side tables
  (`wholeRanges/Base/Count/Rhp`, `wholeSideComplete`), the exactness gate
  + accept-only-what-ships rejection, `wholeWalk` (~180 lines, "a verbatim
  transplant of extractFrom"), `WholeEngine` seam, `FindOnlyWholeEngine`,
  `matchWhole`/`genMatchWholeInlined`/`genWholeOne`, and the FIND_ONLY
  escape hatch that exists because the machinery can reject compiles.
  Alternative worlds: (a) unpruned determinization always (paper-simple,
  pay state count), (b) whole = second cheap artifact, (c) status quo.
  Each is priceable; this is the memo-deletion question one size up.
- **C24 — multi-valued tags lane** (M, keep — re-price). BT22 §3.1: a
  parallel compile path (`Tnfa.compileMulti`), `TagTree`, `OP_APPEND_POS`,
  `groupSpans`, its own fuzz plan and parity tests. README calls it "an
  obscure corner, kept deliberately". Verify the keep-cost is still
  acceptable (it blocks the fixed-tag/register optimizations on its
  artifacts only) and record the verdict; no action expected.
- **C25 — `Optimize` N=2 loop** (S, measure-first). The paper runs
  liveness/DCE/interference/alloc/normalize twice; the doc says iteration
  2 finds new coalescing. Price N=1 on the compile-time benches: if the
  second iteration buys <noise in registers saved, make N a constant worth
  deleting.
- **C26 — `DfaMinimizer` range normalization + degrade path** (M, examine).
  The normalizer (same per-codepoint behavior, different boundaries) has
  its own cell budget and a degrade-to-unnormalized fallback. How much does
  it merge on real patterns (dictionary? log shapes?) vs its complexity?
  If it merges ~nothing post-minimize, retire the pass; if it pays, keep
  and document the measured shapes.
- **C27 — `TdfaStateIndex` key zoo** (S/M, examine). `ProbeKey` vs
  `DfaStateKey`, class-signature canonicalization, `tryMap` register
  bijection, addState. The probe-reuse trick saves 2 KB per hit (1.4 M
  lookups on the 234 K-state bomb) — load-bearing there. Examine whether
  the dual-key shape can simplify without giving back the win.
- **C28 — `Parser`/`Tnfa` monoliths** (S, optional). 2 145 / 1 662 lines,
  single class each. Splitting is cosmetic churn unless a section is dead
  (none found); the non-recursive parser TODO is the real change if it
  happens. Verdict likely "keep".
- **C29 — `HistTable` hash-consing** (keep — verify only). Documented as
  the difference between O(sum|l|) per state and O(1) lookups; not a
  candidate. Listed so "examined, load-bearing" is on the record.

## F. Unicode & data

- **C30 — generated version modules** (M, examine). `:unicode:v6_0` and
  `:unicode:v17_0` both ship; `JdkUnicodeDataProvider` (637) resolves
  live-JDK tables; `CaseFoldTable` (335) carries folds. Who consumes v6_0
  (a pinned-Unicode consumer story exists in tests)? If v6_0 is
  demo-only, fold to one generated module + the JDK provider.
- **C31 — provider indirection** (S, examine). `UnicodeDataProvider` +
  `UnicodeProviders` + `NoUnicodeProvider` + `Re2jUniverseProvider` +
  surface `defaultProvider` suppliers + failure memo. Each has a stated
  role (pinned tables, fold-universe seam); verify all five still pull
  weight or collapse the empty ones.

## G. Budget system

- **C32 — post-memo budget simplification** (M, simplify). With both
  runtime memos gone, the runtime side of the budget reduces to the
  retained-tables check (`retainedTableBytes >= budget` ⇒ reject) — yet
  `BudgetWeights` still carries the full runtime weight model, memo
  floors, and residual-split prose, `WorkMeter` keeps the fork ledger the
  side-table shares draw from (still used), and `FrameBudget` (62 lines)
  needs a usage audit. Slim the runtime half of the model to what the
  retained check needs; keep the compile half.

## H. Tests & infrastructure

- **C33 — fuzzer harness complexity** (M, simplify). `DifferentialFuzzer`
  (2 114) + `CampaignFuzzer` (1 535): forced-rung machinery, warm replay,
  determinism double-run, JURLANE_SKIP recording with caps. Several lanes
  retired in the last months (SKIP families reclassified); sweep the
  harness for retired knobs and dead classifiers before the next campaign.
- **C34 — `lib:pikesim` placement** (S/M, simplify). The reference
  simulator ships as a first-class module (Java 25 bytecode, in
  BytecodeFloorTest's shippedPaths) but its consumers are tests only
  (parity columns, unit audits). If it is not a published artifact, move
  it under `testlib/` and drop it from the shipped set — one less module
  claiming to be product.
- **C35 — Qodana** (S, decide). Plugin + `qodana.yaml` + CI spend, with
  TODO "keep or remove". Decide by what it has caught lately; if nothing,
  remove the plugin from the build.
- **C36 — `scripts/migration`** (S, remove). `add-braces.py`,
  `expand-star-imports.py` — one-shot codestyle migration scripts, already
  applied tree-wide (spotless enforces the style now). Delete.
- **C37 — branch debris** (S, remove). ~90 local branches, half `t3code/*`
  scraps; the stale-branch-report workflow exists for exactly this. Prune
  the merged/dead ones.
- **C38 — vendored re2j patch debt** (M, keep — watch). `vendor/` +
  `scripts/vendor.sh` + `re2j-jemmix` fork with upstream PRs #208/#212/#213
  pending maintainer review. Not removable while the parity suite depends
  on it; the debt closes when upstream lands. Record and leave.

## Progress log

- **2026-10-28** — list built from a full-tree pass (engine, ASM, facades,
  pipeline, unicode, budget, tests, scripts); 38 items across 8 clusters.
  Delegated reviewers queued, one per cluster.
