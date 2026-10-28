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
| A. Memo-deletion debris | C1–C5, C39–C40 | 7/7 | **done** — 2 remove, 1 simplify, 1 already-landed, 1 keep, +2 new prose items |
| B. Runner dispatch ladder | C6–C12, C46–C48 | 10/10 | **done** — 6 simplify, 2 keep, 2 measure-first, +3 new |
| C. ASM backend | C13–C16, C49–C51 | 7/7 | **done** — 4 keep (bench artifacts decide), +3 new |
| D. Facades & API | C17–C21, C52–C53 | 7/7 | **done** — 4 keep, 1 remove (C20 unreachable), +2 new |
| E. Compile pipeline & determinizer | C22–C29, C41–C42 | 10/10 | **done** — 1 simplify, 5 keep, 2 measure-first, +2 new |
| F. Unicode & data | C30–C31 | 2/2* | **done** (with G) — 1 keep, 1 simplify |
| G. Budget system | C32 | 1/1* | **done** — simplify; FrameBudget/WorkMeter keep |
| H. Tests & infrastructure | C33–C38, C54 | 7/7 | **done** — 3 remove, 2 simplify, 1 keep, 1 referred, +1 new |
| I. Mission challenges (2nd pass) | C55–C59 | 5/5 | **done** — 4 decisions for the owner, 1 measure-first |
| **Total** | **59** | **59** | **review complete — two passes, every item verdicted** |

*New-item IDs are unique across clusters: A → C39/C40, E → C41/C42,
F+G → C43–C45, B → C46–C48, C → C49–C51, D → C52/C53, H → C54.
Reviewers' internal numbering collided at C39 and was renumbered on
recording.*

## The board after review — 59 items, 59 verdicts

| Verdict | Count | Items |
|---|---:|---|
| remove | 9 | C1, C3, C20, C36, C37, C39, C40, C41, C43 |
| simplify | 20 | C2, C6, C8–C10, C22, C31–C34, C42, C44, C45, C47–C51, C53, C54 |
| measure-first | 6 | C7, C25, C26, C46, C52, C59 |
| keep | 18 | C5, C11–C19, C21, C23, C24*, C27–C30, C38 |
| decision (owner) | 5 | C35, C55, C56, C57, C58 |
| already-landed | 1 | C4 (#99) |

*C24's keep is superseded by challenge C55; C35 was referred back to its
TODO's owner after the second pass (see below).*

## Mission challenges — the second pass

The first pass verified *facts*; this pass asks a harder question of the
features that survived it: **does the project's mission need this, or is
it nice-to-have machinery the memo precedent teaches us to price again?**
Mission, per README: linear-time by construction, the speed headline,
drop-in per lineage (jur + re2j shapes), sane compile cost. A feature
fails the test when no drop-in surface exposes it AND it is not part of
the measured headline AND it carries real machinery. Each challenge below
ends at an owner decision — the evidence is ours, the intent is not.

- **C55 — `MULTI_VALUED_TAGS`: the flagship challenge** (decision, L).
  The mission test **fails**: neither `java.util.regex` nor re2j has
  `groupSpans` — no drop-in consumer exists — and it is not part of the
  speed headline. What it costs: a parallel compile path
  (`Tnfa.compileMulti`), `TagTree` + `OP_APPEND_POS` threaded through
  determinize/dfa/tnfa/engine/ASM (~160 branch sites), its own parity
  suite and fuzz plan — **and the fuzz lane does not exist yet** (TODO:
  "Fuzz the multi-valued lane"). README's own words: "an obscure corner,
  kept deliberately." That was C24's keep verdict, and it holds *only if
  the capability is a deliberate differentiator worth its keep-cost*. The
  honest challenge: pre-1.0 (no publish, no adoption to break) is the
  one window to delete it cheaply — the docs and tests are complete
  design memory for a re-add. If it stays, the unfuzzed lane is the
  standing correctness risk and the fuzz TODO becomes mandatory, not
  optional. Recommend: decide now — delete, or commit to fuzzing it.
  Keeping it unfuzzed is the one indefensible option.
- **C56 — `CompileOptions.semantics()`: the public axis probe**
  (decision, S/M). The re2j facade's own javadoc disclaims it as "a
  debugging tool, not a user contract". The two lanes are baked per
  surface; per-axis mixing has six test-side consumers and zero product
  ones. Delete the *public* probe (keep `Semantics` as the internal
  lane representation C21 recorded); the PivotLanes hybrid batteries
  move behind a test seam. The API surface shrinks and the 1.0 lock has
  one less thing to freeze by accident. Composes with C20.
- **C57 — `RegexEngineFactory`: bring-your-own-engine as public API**
  (decision, M). Every consumer is test-side (verified: unit/parity
  tests and benches use the factory overload to force VM/ASM twins;
  nothing else). The drop-in surfaces never need a third-party engine;
  `-Dtdfa.engine=VM` already covers tier selection. Folding the factory
  to a test-only seam deletes the public overload pair, the
  `noCodegenWrapper` lane, and the BYO-shell emission path in
  `PatternCompiler`/`ShellEmitter` — the "tracer, experimental backend"
  story is speculation, and YAGNI is cheapest pre-1.0.
- **C58 — `CompileObserver`/`CompilationReport` as public machinery**
  (decision, S/M). Same shape as C57: every observer consumer is a test
  or the fuzz harness; observability is a development concern. Internalze
  to `core.compile` (test-fixtures reach it by package); two fewer
  public types to freeze, document, and keep japicmp-stable.
- **C59 — stack-regs: re-price C14's keep against the headline**
  (measure-first, M). C14 kept the axis on a recorded −9..−19 % class
  win (4–16-register walks) — exactly the shape the memo precedent
  teaches to re-price: a narrow regime carrying ~120 lines of emission
  variant. Measure the *headline* rows (the README table's five
  ParameterizedShortInput shapes + ShortFindBench captures) with
  stack-regs disabled: if the flagship geomean moves <5 %, delete the
  axis; the class win is then the same "2–7× inside one narrow regime"
  the search-DFA memo died of. If the headline rows do carry it, the
  keep stands with a better argument than it has today.

Challenged and **survived** (recorded so the sweep is complete):
`LONGEST_MATCH` (advertised in the pitch, both facades expose it);
serialization (both drop-in targets are `Serializable`); the whole-match
machinery (C23's shipped pricing history); the ASM INLINED tier (it *is*
the speed headline); ORIGIN_SIM (degenerate-shape bounder — but its open
correctness bug makes it fix-or-delete, already TODO'd); the pinned-
Unicode provider API (documented consumer story; only its broken `-D`
selection branch is dead — C45).

### Intent corrections — verdicts walked back or reframed

The second pass also swept the *remove/simplify* list for the opposite
error: things the owner left deliberately. Found and fixed:

- **C35 (Qodana) — remove → referred.** "Keep or remove" is the owner's
  open TODO, not a verdict waiting for us. The evidence stands (zero
  findings-driven fixes; nothing invokes `qodanaScan`, not even
  `check`), but the *decision* — including "wire it into CI and give it
  a chance" — belongs to that TODO. Recommend deciding at the pre-freeze
  review; today it costs a plugin line and a config file.
- **C33 — the soak's `nested-loop-capture` skip is now owner-confirm.**
  #86 re-added it 25 minutes after #87's deletion — that timeline reads
  as a deliberate revert, not an accident. Deletion proposal withdrawn
  pending the owner's word; the rest of C33's dead-code deletions
  (zero-caller helpers, dead counter, stale allowlist entry) stand
  unaffected.
- **C21 — the TODO is not ours to close.** "Decide Semantics scope"
  stays open; this review's answer (axes are representation, not knobs)
  is recorded as a *recommendation* feeding it.
- **C34 — pikesim move rides the publish TODO.** "First Maven publish"
  is open; if pikesim is intended product then, the move is wrong. The
  README's own four-module story says test fixture today — confirm
  intent at the publish decision.
- Strengthened, not weakened: **C1/C2/C3 answer questions the owner
  explicitly left "for the review"** — `docs/search-dfa-memo-tradeoffs.md`
  names the budget split and `Strategy.TRIGGER` as deliberately deferred
  to this review, so the remove verdicts are the solicited answers, not
  overreach (each carries a two-line revert path if the memo returns).

Suggested landing order (each PR rides `./gradlew check`; bench-gated
items ride `scripts/bench-regression.sh`, 15 % rule):

0. **Owner decisions first** (they gate everything below — one-liners in
   [complexity-decisions.md](complexity-decisions.md)): C55
   (multi-valued: delete or commit to the fuzz lane), C56/C57/C58
   (public surface: axis probe, BYO factory, observer), C35 (Qodana,
   with its TODO), C34-confirm (pikesim, with the publish TODO).
1. **Pure removals** (dead code, no behavior change): C3, C20, C41,
   C49, C50, C51 — then C36, C43, and the prose-truth pair C39/C40.
2. **Placement + harness truth**: C34 (pikesim → testlib), C33 (fuzzer
   dead code; the nested-loop skip waits for owner word), C54 (knob
   forwardings), C31 (NoUnicodeProvider).
3. **Budget endgame** (one PR): C32 — subsumes C1 + C2 + C44 and marks
   the tradeoffs-doc TODO resolved.
4. **Pipeline shape**: C22 (with C41's ALL_AXES fold + C53's minimal
   fix), C42 (mode enum), C45 + C52 (test additions).
5. **Ladder program** (gated; C46 rows first): C48 → C8 → C7 scope 1 →
   C9 → C47, then C10's test-hook seam.
6. **Measure-first verdicts to run**: C25, C26, C59 (throwaway patches),
   C7 scope 2 (expected to close keep-with-numbers).
7. **Chore**: C37 branch/worktree purge — after this review's own branch
   churn settles.
8. **Keeps** stand as recorded; re-litigation requires new evidence
   (each keep argument above names what would reopen it).

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
  **Verdict: remove** (cluster A review, verified). Full deletion
  inventory: Budgets.java:190–226 (methods + section header),
  BudgetWeights.java:169–215 (nine constants + two headers),
  Budgets.java:30–31, 129–137 (class-doc references),
  BudgetModelTest.java:65–76, 87–88; prose re-points at
  docs/search-dfa-memo-tradeoffs.md:97–101 and Tdfa.java:1170. Bonus
  found by the review: `WALK_STATE_TABLE_BYTES` (BudgetWeights.java:205)
  has zero references anywhere — not even the dead caps use it.
  Enabling change for C32.
- **C2 — `memoBudgetBytes` plumbing** (S/M, simplify). The field on
  `TdfaRunner` calls itself VESTIGIAL in its own javadoc, yet threads
  through `EngineBackend.generate`, both `TdfaAsmBackend.generate`
  overloads, `genInit`/`genDelegateInit` (emitted LDC!), `AsmEngineBackend`,
  and `PatternCompiler`'s split arithmetic. With no memo left the whole
  parameter is theatre; collapse to nothing (or a plain retained-bytes
  check) and delete the emitted constant.
  **Verdict: simplify** (cluster A review — stronger than listed: the
  field is **write-only**; its sole reference is its own assignment
  (TdfaRunner.java:351), and there is no split arithmetic to preserve —
  PatternCompiler.java:199 passes the identical value to both engines
  (the "half each" prose is fiction, see C39). Target: `TdfaRunner(Tdfa)`
  sole constructor, `EngineBackend.generate(Tdfa)` (one implementation,
  documented not-shipped-API), `"(TDFA)V"` replaces the emitted
  `LDC`+`"(TDFA)J)V"` sites (TdfaAsmBackend.java:296–297, 2448–2449),
  `Budgets.runtimeMemoAllowance` loses its last production caller.
  Nothing breaks: generated classes are in-memory only (AOT is a
  wishlist TODO), no test pins the constructor shape; blast radius is
  ~10 test/doc sites (inventoried in the review, PR #102 series).
  Pre-1.0, so the public-constructor removal is policy-clean.)
- **C3 — `Strategy.TRIGGER`** (S, remove/alias). The enum javadoc admits the
  rung "now traces RAW_SCAN" — the trigger scan *is* the raw scan since #97.
  The value survives as a forced-rung identity in the fuzzer and three
  battery tests. Merge into `RAW_SCAN` (keep the trace string stable where
  the fuzzer parses it).
  **Verdict: remove** (cluster A review, verified). `trace(TRIGGER)` is
  emitted nowhere — trigger paths trace RAW_SCAN (TdfaRunner.java:1680,
  1695) and the emitted ladder delegates to `triggerScanTop`/
  `unitTriggerTop` (whose javadoc at 1914 still says "Memoized
  search-DFA trigger scan" — reword, see C40). Forcing TRIGGER and
  RAW_SCAN execute byte-identical work (same switch arm 1271–1272; the
  5-arg `triggerScan`'s `force` param is literally unused), so the
  fuzzer/batteries lose zero coverage and drop one redundant pass each;
  StrategyConformanceTest compares enums, never names. Collapse the
  4-arg/5-arg `triggerScan` pair into one method while there. If the
  memo is ever restored the rung re-diverges — a two-line revert.
- **C4 — `Tdfa` G3 lazy caches** (S, simplify — already decided, execute).
  `stopMaskTableCache`, `posFlagDepsCache`, `absBeginBoundCache` were
  `@MemoCache`-annotated benign-race lazy fields whose W4 disposition
  ("precompute at construction; delete the three fields") was agreed in
  `docs/runner-immutability.md`.
  **Verdict: already-landed — #99** (verified: the fields and the
  duplicated javadoc are gone from `Tdfa.java`; the finality gate's memo
  allowlist is empty). Closed without a delegate.
- **C5 — `@MemoCache` + finality-gate allowlist endgame** (S, keep-but-shrink).
  With W4/W5 done the annotation has zero annotated fields by design. The
  gate (RunnerFinalityGateTest) stays; decide whether the annotation type
  itself stays as the mandated shape for any future cache (the doc says yes)
  or goes until needed. Record it; one-line either way.
  **Verdict: keep** (cluster A review). The gate actively parses the
  annotation in three branches (RunnerFinalityGateTest.java:119–127, 147) —
  with the allowlist empty those branches are the ratchet's teeth; the
  restoration criteria are live (TODO.md:17). Zero runtime cost (RUNTIME
  retention, no runtime reader); deletion costs a gate rewrite plus
  re-adding both when a cache returns. One touch for W5's landing note:
  the doc says "statics allowlist exactly the four justified entries"
  (runner-immutability.md:258–259) while the gate correctly carries two
  (`TRACE`, `anyForced`) because `TRACE_BUF`/`FORCED` are final
  containers skipped by construction.

### New items found by the cluster A review

- **C39 — the "per-engine half-split" budget contract is fiction** (S,
  remove). Budgets.java:50–54 and TdfaAsmBackend.java:103–107 document a
  two-engine pattern "splits the residual in half per engine" — no such
  arithmetic exists: PatternCompiler.java:199 passes the identical
  allowance to both engines, and the runner ignores it entirely (C2).
  No behavioral consequence, but it misleads anyone reasoning about the
  budget contract (ExecutionRamCheckTest.java:92 shows the note-side
  wording was already scrubbed while the javadocs kept the claim).
  C2's landing deletes these paragraphs wholesale; tracked separately so
  the doc-integrity fix happens even if C2 slips.
- **C40 — stale memo prose inventory** (S, remove). The memo deletions
  left a wider prose trail than C1–C3 enumerate:
  TdfaRunner.java:1914–1916 (`triggerScanTop` javadoc still says
  "Memoized search-DFA trigger scan"), Tdfa.java:384 (names the deleted
  memo among consumers), Tdfa.java:1081, Tdfa.java:1170, Budgets.java:8–9
  and 23–25, DifferentialFuzzer.java:121–124 (stretched-input comment
  claims the memoized path "actually runs"). One doc-truth pass closes
  them all; each C1/C2/C3 PR carries the sites in its blast radius.

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
  **Verdict: simplify** (cluster B review — only TRIGGER is deletable
  (the C3 merge); every other rung earns its slot, and the "overlapping
  regimes" framing is wrong: EXACT_FROM→PREFIX→CAND_SCAN are disjoint
  ordered guards, not overlap. Per-rung evidence: LITERAL ~0.2 vs
  ~6.5 ns/char; PREFIX bounds the dense-hit adversarial shape
  (`PREFIX_WALK_BUDGET=16`) and has active investment (TODO "Prefix
  needle v2"); CAND_SCAN measured 492→142 ns (ipExtract) with bounded
  64² worst case; RAW_SCAN is the campaign's degenerate-shape bounder
  (`.+zz` 200–460 ms → 1–3 ms). ORIGIN_SIM is **keep-with-caveat**: it
  serves only the codepoint lane (the default unit surface routes around
  it), carries an OPEN correctness bug (TODO.md:16, forced-rung
  leftmost-start miss) and zero bench coverage (C46) — re-price when the
  fix lands. Concrete shape: the TRIGGER merge only.)
- **C7 — bool/extract path duality** (M/L, measure-first). The ladder
  exists twice — boolean and extract variants hand-duplicated:
  `prefixScanBool`/`prefixScanExtract`, `candScanBool`/`candScanExtract`,
  `boolWalkAt`/`extractWalkAt`, `runStringFindFast` vs
  `runStringExtractFast`, anchored pairs. A single extract path with a thin
  boolean wrapper would halve the paths; the counterargument is
  register-allocation cost on failed boolean walks. Measure the find()
  benches both ways before folding.
  **Verdict: measure-first** (cluster B review — the duality is also the
  C12 prefilter mechanism, and the counterargument is already priced:
  boolean walks skip regs entirely while extract walks pay `takeRegs` +
  fill + applyOps — measured 1143 vs 498 ns on emailNoMatch (d192462),
  ~2.3×, so folding is *predicted to fail the gate*. Split: **scope 1**
  (S, likely-pass) merge only the candidate-enumeration loops
  (`prefixScan`/`candScan` parameterized by mode, ~30 lines); **scope 2**
  (M, likely-fail) fold the walk leaves. Bench:
  `./scripts/bench-regression.sh` (15 %) then `--jmh` (10 %) plus
  ShortFindBench `emailNoMatch` on vm/asm — a ≥15 % emailNoMatch
  regression closes scope 2 with numbers.)
- **C8 — unit/codepoint scan twins** (M, simplify). `triggerScan` vs
  `unitTrigger`, `rawScan` vs `unitRawScan` (plus a `unitSeeds` flag
  variant): the same simulation twice, once per boundary discipline. The
  difference is small (pair-interior skips, start enumeration); a
  parameterized walk is plausible. Same shape as C7 — attempt after, not
  before.
  **Verdict: simplify** (cluster B review — the premise is stale: post-#97
  there is already ONE parameterized walk, `rawScan(…, boolean unitSeeds)`
  (1733–1845); the "twins" are four thin entry wrappers plus dead
  memo-era plumbing: the 5-arg `rawScan` and `unitRawScan` have one
  caller each, the `wIn`/`liveIn` params are `from, null` at every call
  site (the deleted SearchDfa's hand-off), and `triggerScan`'s `force`
  param is never read. Delete wrappers + dead params; `@EmittedSurface`
  hooks keep their names. Mechanical, ~30 lines; do before C7 so the
  fold works against the minimal shape.)
- **C9 — `runGeneric`, the third copy** (M, simplify). The CharSequence
  (non-String) fallback re-implements the walk against `charAt`. With C7/C8
  folded this collapses to the one parameterized walk plus a String fast
  prefix. Keep the GENERIC trace rung.
  **Verdict: simplify** (cluster B review — sequence matters: C48 first
  (the predicate-twin wall, S, gated), then C8, then C7; after which
  runGeneric collapses toward one parameterized walk plus its
  anchored/restart specifics. Gate each step on `info.anchored.vm/asm`,
  `info.extract.vm/asm`, `findAllDense.vm/asm` within 15 %.)
- **C10 — test hooks in the production runner** (M, simplify — TODO'd).
  `FORCED`/`anyForced` statics, `setForcedStrategy`, `forcedSearch`,
  `forcedRestart`, `TRACE`/`setTracing`, `TRACE_BUF`, `WTRACE` — all
  fuzz/test instruments living inside the shipped engine class (G5
  allowlist). TODO already carries "TdfaRunner trace → per-engine
  (test-only)". Move behind a test-visible seam (subclass or
  package-private SPI in a test artifact) so the runner body carries zero
  instrument branches. Note `forcedStrategy()` is read on EVERY find/match
  call — a ThreadLocal get on the hot path.
  **Verdict: simplify** (cluster B review — ~195 lines quantified
  (trace ≈35, forced-rung ≈130, WTRACE ≈30). Two factual corrections:
  `forcedStrategy()` is one volatile read of `anyForced` with the
  ThreadLocal get only when forcing is active (favorable); but the class's
  own "zero cost when disabled" comment is **false** — `TRACE` is
  volatile, so ~34 `trace()` sites pay a static call + volatile load per
  rung transition. Seam: (1) a `StrategyTraces` holder the emitter
  re-targets (one INVOKESTATIC constant, conformance-pinned); (2) the
  FORCED machinery wholesale into a test-sources class in the same
  package, widening six private members to package-private — deletes
  ~160 lines from the shipped class and the per-entry volatile read; (3)
  delete WTRACE's per-position branches. Finality-gate static allowlist
  shrinks to match — the W5 direction.)
- **C11 — dual dispatch-table tiers** (M, measure-first). `asciiTarget`
  (128, simulations) vs `latinTarget` (256, walks) + `latinLimit` +
  `LATIN1_MAX_STATES` (8 192) + `ASCII_TABLE_MAX_STATES` (16 384) + the
  same-object aliasing rules. Two thresholds and a table-span dance for a
  measured 15 % ASCII-scan effect (documented) vs direct dispatch on
  accented text. Price one tier (256 with the compact-table guard) against
  the two-tier logic.
  **Verdict: keep** (cluster B review — both one-tier directions already
  have recorded measurements at or over the gate: 256-stride simulations
  slowed pure-ASCII scans 15–18 % (commit 9c587ea; the row survives as
  `scanNoMatch.*`), 128-only forfeits the Latin-1 walk win
  (findAllLatin1 −15..−38 %). The two constants guard different
  resources (table memory vs the 234 K-state bomb's ~228 MB), and the
  aliasing rule already avoids a third allocation. Re-litigation must
  name a single tier serving both `scanNoMatch` and `findAllLatin1`
  within the gate.)
- **C12 — `ADAPTIVE_PREFILTER_AFTER`** (S, measure-first). After 3 failed
  extract walks the candidate loop switches to a boolean pre-filter. The
  switch is an `@EmittedSurface` constant baked into generated classes —
  two loop shapes in both tiers. If the pre-filter wins everywhere now,
  make it unconditional; if it never wins, delete it.
  **Verdict: keep** (cluster B review — the deciding number is on record:
  1143 ns (extract per candidate) vs 498 ns (prefiltered) on the dense
  no-match shape (d192462), and that gap is the regs-fill cost C7's fold
  would pay. Unconditional would double per-candidate work where real
  matches live (findAllDense); deleting regresses 2.3×. The two-tier
  cost is one constant read at emit time + a conformance-pinned hook. Do
  not re-open without a bench row showing the threshold's middle
  dominated by either extreme.)

### New items found by the cluster B review

*(numbered C39–C41 by the reviewer; recorded as C46–C48)*

- **C46 — the codepoint lane is invisible to the bench gate** (S,
  measure-first). Every bench row compiles through the root
  (unit-semantics) surface; no row ever exercises `ORIGIN_SIM` (routed
  around on the unit lane), the codepoint-lane `triggerScan`/`rawScan`,
  the `scanSkipsPairs` branches, or the `prefixHitUsable` selection — the
  exact rungs C6–C9 fold. A ladder refactor could regress that lane and
  still pass the gate. Shape: add two rows (dense findAll + no-match long
  scan compiled on the `:re2j` surface, supplementary-bearing input),
  `--capture`, then they gate every C6–C9 change. Until then
  ORIGIN_SIM's keep-verdict is provisional.
- **C47 — `runStringAnchored`, a third copy of the mask-aware transition
  protocol** (M, simplify). ~186 lines transcribing the same
  most-specific-mask/dispatch protocol as `extractFrom` and
  `runStringMatchFrom`, with a drift-prone return quirk of its own — and
  it is the rung the 2026-10-16 matches() divergence lived in (prior
  drift history). After C7's fold, matches() should be expressible via
  the single parameterized walk, equivalence pinned by a battery over
  the anchor-gated catalog; if the proof fails, keep with a comment
  naming it a deliberate third instance.
- **C48 — the String/CharSequence predicate-twin wall** (S, simplify).
  Six method pairs with byte-identical bodies differing only in receiver
  type (`maskSatisfied`/`CS`, `positionFlags`/`CS`, `lineBegin`/`lineEnd`/
  `finalEnd` ×2 — ~105 lines). `String.charAt` is final, so the
  CharSequence-typed call on a String receiver should devirtualize —
  delete the String twins, keep the CS versions. The natural first PR of
  the C9 program; gate on the walk-heavy rows
  (`info.anchored.vm/asm`, `info.extract.vm/asm`, `findAllDense.vm/asm`);
  any regression closes it as a measured JIT artifact, named.

## C. ASM backend (`TdfaAsmBackend`, 2 929 lines)

- **C13 — INLINED vs DELEGATE emission modes** (L, measure-first). Two
  full emission programs: `pickMode` inlines fastPath DFAs into dedicated
  method bodies; everything else delegates to an embedded `TdfaRunner`.
  The delegate mode is the correctness net the INLINED mode is measured
  against — but is the INLINED tier's win worth a second emitter, or can
  the generated class be a thin monomorphic shell over the shared runner
  (DELEGATE-only) with named-method dispatch? The bench gate decides; the
 Emitter halves if it passes.
  **Verdict: keep** (cluster C review — the split is smaller than listed:
  the DELEGATE tier is ~96 lines of trivial forwarders, and even INLINED
  classes delegate matches()/find() outright. The measurement already
  exists in committed artifacts: BENCHMARKS.md §5 shows ASM/VM at
  0.29–0.68× on capture-dense walks but ~1.08–1.15× where both tiers
  delegate — i.e. DELEGATE-only ≈ the VM column. A fold regresses the
  deciding rows (§1 short-input: 17.0 vs 73.1 ns; ShortFindBench
  captures ~2× VM; LogExtract prefix rows 1.5–2.3×) by 2–4×, an order of
  magnitude past the gate. DELEGATE stays as the method-cap/non-fastPath
  valve it was born as.)
- **C14 — the stack-register emission axis** (M, measure-first). A third
  variant bit: `stackRegsEligible()` + `genExtractOne(stackRegs)` +
  `genWholeOne(stackRegs)` + carrier-free classes + the 65 KB soft budget.
  If C13 folds to DELEGATE, this dies with it; independently, price
  stack-regs vs the plain regs pool on the extract benches.
  **Verdict: keep** (cluster C review — the A/B is already in the
  javadoc, landed with the axis (#12): −18 % (4 regs) … −9..−19 %
  (12–16) … parity at 40+ (TdfaAsmBackend.java:2789–2797). Deleting the
  ~120-line axis regresses 4–16-register extract walks at or past the
  gate. Corrections: the soft budget is **30 KB** (`INLINE_BUDGET_BYTES`
  — 65 KB is the JVM hard cap it leaves headroom under), and it guards
  the INLINED tier (C13), not this axis.)
- **C15 — emitted-shell machinery** (M, simplify). `ShellEmitter` (402),
  `ShellSpec`, `genDelegateInit`, `genMetadataMethods`, the
  `@EmittedSurface` 7-field shell contract on `engine.Matcher`, and
  `PatternMatcher` extended by generated children in child classloaders.
  A generic (non-emitted) shell over a `RegexEngine` reference exists
  conceptually in the VM path; quantify what the emitted shell buys
  (devirtualization) vs its maintenance surface, or at least shrink the
  emitted surface to the three hot entry points it justifies.
  **Verdict: keep** (cluster C review — the proposed middle shape **is
  the shipped shape**: the shell emits exactly a ctor + find/matches/
  lookingAt, nothing else; the linked surface is **8 fields, not 7**
  (seven bookkeeping + `scratch`). The generic-shell alternative is the
  VM carrier, whose `find()` call site goes megamorphic once ≥3 engine
  types flow through it; the emitted shell's per-pattern concrete
  receivers are mechanically enforced (EmittedBytecodePolicyTest: no
  INVOKEINTERFACE, every INVOKEVIRTUAL receiver final) and measured
  (~19 ns overhead on the anchored row, realized jointly with INLINED).
  The BYO shell lane serves the public factory API — not dead.)
- **C16 — emitted ladder re-implements the VM ladder** (L, keep-or-shared).
  The generated find/match ladders mirror `TdfaRunner`'s semantics by
  hand. Related TODO: "Shared Tdfa for asm+vm (halve fuzz compile churn)".
  A single parametric ladder spec consumed by both tiers would delete a
  whole class of cross-tier drift bugs (the fuzz forced-rung machinery
  exists largely to police this). Big lift; the drift evidence decides.
  **Verdict: keep** (cluster C review — two factual corrections: the
  forced-rung machinery polices **VM-internal** rung equivalence only
  (ASM is not forced); cross-tier drift is policed by
  StrategyConformanceTest's trace-equality assertions (17 shapes × 20
  lengths × 4 entry points), and the "Shared Tdfa" TODO is compile-
  artifact reuse, not a shared ladder. The hand-mirror is thinner than
  it looks — strategy decisions are monomorphic calls into the one
  embedded runner, thresholds linked at emit time. Drift bit exactly
  twice and was caught by the net both times; no open cross-tier item.
  A shared spec is an XL bet against C13's measured value.)

### New items found by the cluster C review

*(numbered C39–C41 by the reviewer; recorded as C49–C51)*

- **C49 — stale `TABLE_SCAN` mode reference in rebar parity javadoc**
  (S, simplify). RebarScenarioParityTest.java:46–47 describes "three
  `DispatchMode`s (`INLINED`/`TABLE_SCAN`/`DELEGATE`)" — the enum has
  exactly two values; TABLE_SCAN died in the kernel-unification refactor.
  A C16-class prose drift sitting in the backend-pair coverage suite.
  One-line comment fix.
- **C50 — never-fired emission paths** (S, simplify). Two emitters produce
  code no generated class can use: the entry-mask machinery
  (`genEntryOkC`, `ENTRY_MASK` declaration/copy, dispatch branch,
  start-state check) is gated on `hasEntryMasks`, but `pickMode` only
  INLINEs fastPath DFAs and that predicate rejects any nonzero entry
  mask — the code's own comment concedes "ship in no generated class
  today"; and `genPositionFlagsC` is emitted unconditionally though its
  only live caller is the wholeOne EOF path — every INLINED class with
  no `stateFinalOpsByMask` carries a caller-less method. Delete the
  entry-mask path (git-recoverable); gate positionFlagsC on
  `stateFinalOpsByMask() != null`.
- **C51 — `genDelegateMatches`/`genDelegateFind` byte-for-byte duplicates**
  (S, simplify). The DELEGATE forwarders emit method bodies identical to
  `genMatches`/`genFind` — both modes' classes carry the same `runner`
  field; nothing distinguishes the pairs. Delete the two `genDelegate*`
  forwarders and call the shared emitters from the DELEGATE branch:
  ~22 lines, identical bytes emitted.

## D. Facades & API

- **C17 — surface registry & serialization layering** (M, simplify). Two
  surfaces (root jur `:`, `:re2j`) is decided policy (two readings, baked
  in). But the machinery underneath — `PatternSurface.register/resolve`,
  `triggerClass` classloading, `SerialProxy`, `noCodegenWrapper`,
  `VmPattern` wrappers, `Surfaces` holders — is a lot of moving parts for
  two call sites. Examine: direct references from each facade instead of a
  string-keyed registry, with serialization pinned to a two-entry enum.
  **Verdict: keep** (cluster D review — direct references from core are
  structurally impossible: the module DAG is root→core and re2j→core, so
  core cannot name either surface, and the reader process may never have
  touched the surface module, so the reflective tail must exist no matter
  what; a two-entry enum only replaces the id string while keeping it. A
  per-surface proxy costs a base class + emitter plumbing (~10 moving
  parts vs today's ~9). The registry's one consumer is `resolve`; the
  facades' 6 static sites funnel through the 4-line
  `PatternSurface.compile`. Contract pinned by PatternSerializationTest +
  Re2jSurfaceTest. One nit: the two `VmPattern` copy ctors spell 7 fields
  by hand and rot silently.)
- **C18 — `Pattern.reset()` no-op** (S, remove). Documented no-op on both
  facades ("releases internal caches" — there are none since the memos
  died). Pure re2j-API-compat residue; drop before the 1.0 API lock or
  keep deliberately for drop-in parity — one-line decision, currently
  undecided complexity.
  **Verdict: keep** (cluster D review — the premise was wrong in a
  decisive way: re2j 1.8's own `Pattern.reset()` is **not** a no-op (it
  nulls the pooled cache via `RE2.reset()`), so real re2j user code
  plausibly calls it and the drop-in promise (README "Drop-in, per
  lineage") requires accepting it. Cost: one interface method + empty
  body + honest javadoc; already parity-pinned. Keep deliberately; close
  before the 1.0 lock so it is not re-litigated.)
- **C19 — compile-channel sprawl** (S/M, simplify). `Pattern` offers
  compile ×4 overloads + compileFind ×2 + CompileOptions (semantics,
  provider, observer) + the flag int. Three channels to say one thing.
  Pre-1.0 is the moment to pick: flags + CompileOptions, and let the
  factory overloads go through options.
  **Verdict: keep** (cluster D review — the channels are layered by
  audience, not redundant: the flag int is the re2j-parity surface,
  CompileOptions is the core tier's only knob carrier (no flags param
  there), Semantics is the machinery value. Every overload is consumed
  (3-arg ≈105 sites; 4-arg ≈10 real sites; compileFind is the
  README-advertised escape hatch). The facade's re-encode of three
  options booleans into flag bits is load-bearing (core's only spelling
  vs the facade's only spelling). Record as the pre-1.0 answer.)
- **C20 — machinery-internal axis bits on `PatternSurface`** (S, simplify).
  `UNIX_LINES..SPACE_RE2` + `RE2_AXES` exist only for `semanticsOf`'s
  bit→axis mapping and tests; no surface accepts them. `Semantics` already
  spells the axes; the int vocabulary is a second spelling of the same
  thing — fold `semanticsOf` into `Semantics`-of-flags or delete the
  constants and switch the one consumer.
  **Verdict: remove** (cluster D review — stronger than listed: not just
  a second spelling but **unreachable dead code**. `semanticsOf`'s eight
  axis branches can never fire: flags are validated against the surface
  roster first and neither roster contains any axis bit (both reject
  256+, pinned by Re2jSurfaceTest) — so `semanticsOf` always returns
  `surface.defaultSemantics()` unchanged, and `RE2_AXES` has zero
  references anywhere. Delete the constants + `semanticsOf`
  (PatternSurface.java:74–103, PatternCompiler.java:68–95), replace the
  call with a direct default/override choice; repoint the two test
  consumers at the j.u.r constants they translate to anyway.)
- **C21 — the 8-axis `Semantics` matrix** (M, decide). Every axis pair is
  pinned by tests; TODO carries "Decide Semantics scope (parity axes vs
  knobs) before lock". If per-axis mixing has no consumer beyond probes,
  freeze the two lanes as named presets and make `CompileOptions.semantics`
  lane-selecting rather than axis-building. Half the matrix's test surface
  evaporates.
  **Verdict: keep** (cluster D review — the evaporation estimate was
  wrong: there are no cross-product pair tests to delete, only per-axis
  separability batteries — and those are load-bearing evidence: single-
  axis resolution against the live JDK oracle is what **found SPACE_RE2**
  ("the JUR default had silently kept re2j's table"). Internally the
  matrix IS the lane representation; a Lane enum would re-encode the
  same 8 booleans per stage. Close the TODO with this recorded answer:
  axes are parity-axis representation, not user knobs. One javadoc fix:
  `CompileOptions.semantics()` still lists seven axes — missing
  SPACE_RE2.)

### New items found by the cluster D review

*(numbered C39–C40 by the reviewer; recorded as C52–C53)*

- **C52 — `PatternSurface.resolve`'s lazy triggerClass branch is
  untested** (S, measure-first). The entire reason `triggerClass` exists —
  loading a surface the reading process has not compiled through — has
  zero coverage: every round-trip test compiles first, so `REGISTERED`
  always hits and the reflective branch can regress silently until a real
  cross-process deserialization fails. Test-presence gate: serialize a
  re2j-surface pattern, evict the registration, deserialize, assert the
  lane survives and the unknown-id failure throws as documented — if (a)
  cannot be made to re-register through the documented path, that is a
  latent bug in the pinned contract.
- **C53 — `Tdfa.ALL_AXES` drifted from `Tnfa.ALL_AXES` (7 axes vs 8)**
  (S, simplify). Tnfa's constant spells all eight axes (incl.
  `.spaceRe2()`); Tdfa's stops at seven while claiming "Every axis set" —
  SPACE_RE2 (#66) updated one and missed the other. Benign today only
  because no post-compile consumer reads `isSpaceRe2` — accidental safety
  that evaporates on the next axis. Real fix rides C22 (one shared
  lane constant); minimal fix now: add the missing wither + correct the
  comment.

## E. Compile pipeline & determinizer

- **C22 — `Tdfa` telescoping constructors** (S, simplify). 24-, 29-, and
  31-arg telescoping constructors, the shorter ones fabricating defaults
  (ALL_AXES!). Callers: the materializer + PikeSim + tests. One builder (or
  keep the 31-arg and make the rest static factories with names) deletes
  two footguns and the legacy-lane trap.
  **Verdict: simplify** (cluster E review, with corrections: the ctors are
  24/33/34-arg at Tdfa.java:316–349, and **PikeSim is not a caller** — it
  runs over Tnfa directly. Production caller: TdfaMaterializer.java:883–888
  (34-arg) only; the 33-arg middle ctor has zero external callers; the
  24-arg is TdfaValidateHardeningTest only and fabricates `ALL_AXES`
  (Tdfa.java:312–314) — the legacy-lane footgun, confirmed. Target: delete
  the 24- and 33-arg ctors, keep the 34-arg as the only public one, move
  the hardening test onto it (`false/null` constants). No builder needed
  for two callers; pre-1.0 (japicmp/publish TODOs open) is the last cheap
  moment.)
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
  **Verdict: keep** (cluster E review — the alternatives are not
  hypotheticals; they **shipped and were priced**: world (b) was #13
  (conditional cut-free second artifact), replaced by #24 because the side
  table interns into the shared state space and explores only the cut
  delta — on the flagship hazardous shape (rebar datefinder) the side
  completes at 127/129 whole states, 4211/8250 entries; #26 deleted the
  two-artifact fallback after measuring it rescued nothing, ever. World
  (a) was the pre-#13 world with a concrete acceptance regression:
  `(a{1,100}){1,100}`'s find artifact compiles while its whole divergence
  exceeds the caps (WholeMatchTest.java:318–333), so unpruned-always
  rejects patterns that compile today and FIND_ONLY could not save them.
  Correctness pinned by two independent oracles + a 900-pattern sweep
  (PartialWholeTest.java:72–168). The genuinely duplicated part —
  wholeWalk vs extractFrom, genWholeOne vs extractOne — is the C7/C13/C16
  cross-tier duplication problem; reprice it there, not here.)
- **C24 — multi-valued tags lane** (M, keep — re-price). BT22 §3.1: a
  parallel compile path (`Tnfa.compileMulti`), `TagTree`, `OP_APPEND_POS`,
  `groupSpans`, its own fuzz plan and parity tests. README calls it "an
  obscure corner, kept deliberately". Verify the keep-cost is still
  acceptable (it blocks the fixed-tag/register optimizations on its
  artifacts only) and record the verdict; no action expected.
  **Verdict: keep** (cluster E review — isolation verified end to end:
  reachable only via `MULTI_VALUED_TAGS` (PatternCompiler.java:152);
  append ops rejected at construction on single-valued artifacts
  (Tdfa.java:601, pinned by TdfaValidateHardeningTest.java:143–152);
  regopt and sticky-capture marking skip multi compiles
  (TdfaMaterializer.java:100–105, Tnfa.java:321); plain-lane immunity is
  a pinned property (MultiValuedTagsTest bit-identical assertions).
  Maintenance re-priced: ~133 `multi` mentions + TagTree 149 lines,
  touched once since 2026-08. Open residual: the lane is unfuzzed —
  TODO's "Fuzz the multi-valued lane" closes the keep-cost's main risk.)
  **Superseded by challenge C55 (second pass): the keep holds only if
  the differentiator is worth its keep-cost — delete-or-fuzz is the
  honest framing, and the owner decides.**
- **C25 — `Optimize` N=2 loop** (S, measure-first). The paper runs
  liveness/DCE/interference/alloc/normalize twice; the doc says iteration
  2 finds new coalescing. Price N=1 on the compile-time benches: if the
  second iteration buys <noise in registers saved, make N a constant worth
  deleting.
  **Verdict: measure-first** (cluster E review — protocol: a throwaway
  patch reporting `cfg.regCount` per iteration (or flipping the constant
  at Optimize.java:56–65) over the rebar corpus + DeterminismCorpus +
  PartialWhole CATALOG, then `./scripts/bench-regression.sh` (15 % gate).
  Delete N=2 only if final register counts match on ≥95 % of corpus
  patterns (≤+1 elsewhere) AND the bench gate stays green AND REGOPT
  stage time drops ≥15 % on the CompileLatencyGuardTest bombs; else keep
  and record the deltas.)
- **C26 — `DfaMinimizer` range normalization + degrade path** (M, examine).
  The normalizer (same per-codepoint behavior, different boundaries) has
  its own cell budget and a degrade-to-unnormalized fallback. How much does
  it merge on real patterns (dictionary? log shapes?) vs its complexity?
  If it merges ~nothing post-minimize, retire the pass; if it pays, keep
  and document the measured shapes.
  **Verdict: measure-first** (cluster E review — the javadoc's "difference
  between minimization working vs not" claim (DfaMinimizer.java:38–42) is
  thinner than the record: the landing commit found rebar DFAs "already
  minimal" and the 44 846-state dictionary "provably minimal"; the one
  modern datapoint is curated/12-dictionary 19.5K→6.8K
  (CompileLatencyGuardTest.java:35–38), and nothing pins that those merges
  need the normalized signature. Protocol: one-line
  `useNormalized = false` patch, compile the four latency-guard shapes +
  rebar corpus, compare final `states=`; retire iff counts match ±0 and
  dictionary keeps 6.8K; any movement goes through bench-regression.sh.)
- **C27 — `TdfaStateIndex` key zoo** (S/M, examine). `ProbeKey` vs
  `DfaStateKey`, class-signature canonicalization, `tryMap` register
  bijection, addState. The probe-reuse trick saves 2 KB per hit (1.4 M
  lookups on the 234 K-state bomb) — load-bearing there. Examine whether
  the dual-key shape can simplify without giving back the win.
  **Verdict: keep** (cluster E review — every piece carries a measured
  in-code justification: get-only probe saves ~2 KB per hit on the
  dominant case; order-exact keys prevent bucket blowup from multiset
  arrival orders (116–128); tryMap's primitive bijection replaced a boxed
  HashMap measured at 32 % getNode + 7 % putVal in JFR (472–478); the
  class-signature partition avoids a 78 %-of-wall rescan; seed-nulling
  avoids ~22 M dead Configs. No simpler shape returns these wins on the
  adversarial class the budget system exists to survive.)
- **C28 — `Parser`/`Tnfa` monoliths** (S, optional). 2 145 / 1 662 lines,
  single class each. Splitting is cosmetic churn unless a section is dead
  (none found); the non-recursive parser TODO is the real change if it
  happens. Verdict likely "keep".
  **Verdict: keep** (cluster E review — no dead section in either: the
  literal-run machinery feeds the prefix-needle strategy, `NFrame` is
  already a non-recursive nullable evaluator, and the Tnfa builder is
  already iterative (`BuildFrame` stacks). Factual correction: the
  "non-recursive parser TODO" **does not exist** — TODO.md carries no
  parser item; this list conflated it with the already-iterative NFA
  builder. The real adjacent finding is C41.)
- **C29 — `HistTable` hash-consing** (keep — verify only). Documented as
  the difference between O(sum|l|) per state and O(1) lookups; not a
  candidate. Listed so "examined, load-bearing" is on the record.
  **Verdict: keep** (cluster E review — verified: ids make signatures one
  int per config at the two consumption sites
  (TdfaStateIndex.java:129–141, 249–263), the structure is budget-charged
  and deliberately primitive-chained to avoid a boxed-map profile entry.
  Nothing to simplify without reintroducing the documented regression.)

### New items found by the cluster E review

*(the reviewer numbered these C39/C40; recorded as C41/C42 — see the
table footnote)*

- **C41 — `Tnfa` telescoping constructors ×6, five dead** (S, remove).
  `Tnfa` ships six public telescoping constructors (19–24 args,
  Tnfa.java:158–237) and the only `new Tnfa(` in the tree is the internal
  builder calling the 24-arg form (Tnfa.java:1657). The five shorter ones
  exist only to chain toward it while fabricating the `ALL_AXES` default
  — the same legacy-lane trap as C22 with zero callers. Pure deletion.
  While there: `ALL_AXES` is spelled in three places (Tnfa.java:154–156,
  Tdfa.java:312–314, `ParseOptions.re2Lane()`) — fold to one shared
  constant, riding with C20/C22.
- **C42 — `TdfaCompiler` boolean-triplet mode encoding** (S, simplify).
  Four real modes encoded in three booleans — 8 configured worlds, of
  which `wholeSide && (longestMatch || unpruned)` is silently rewritten
  to "no side" at TdfaCompiler.java:378 instead of rejected. Target: a
  package-private enum (`PERL_PRUNED`, `PERL_WHOLE`, `POSIX`, `UNPRUNED`)
  so an invalid combination is unrepresentable; fix the dangling
  `{@link Tdfa#compileWithWholeSide}` javadoc (the method lives on
  `Determinizer`). Exactly the "two flags that must agree" class this
  review hunts; makes the C23 surface harder to misuse.

## F. Unicode & data

- **C30 — generated version modules** (M, examine). `:unicode:v6_0` and
  `:unicode:v17_0` both ship; `JdkUnicodeDataProvider` (637) resolves
  live-JDK tables; `CaseFoldTable` (335) carries folds. Who consumes v6_0
  (a pinned-Unicode consumer story exists in tests)? If v6_0 is
  demo-only, fold to one generated module + the JDK provider.
  **Verdict: keep** (F+G review — v6_0 is **not** demo-only: it is half
  of the only stable version-divergence proof in the tree
  (PinnedUnicodeTablesTest's v6-vs-v17 deltas — Osage Lu, U+1C80 fold);
  a v17-vs-JDK contrast cannot replace it because the JDK's Unicode
  version floats. Both modules are deterministic generated code with
  ~zero maintenance. One open decision before "First Maven publish":
  v6_0 as product tier (then document it + add a v6_0-vs-re2j
  bit-exactness test — the "re2j's version" label is currently
  unverified) or test fixture (then drop it from BytecodeFloorTest
  shippedPaths/ANCHORS). Either way the modules stay.)
- **C31 — provider indirection** (S, examine). `UnicodeDataProvider` +
  `UnicodeProviders` + `NoUnicodeProvider` + `Re2jUniverseProvider` +
  surface `defaultProvider` suppliers + failure memo. Each has a stated
  role (pinned tables, fold-universe seam); verify all five still pull
  weight or collapse the empty ones.
  **Verdict: simplify** (F+G review — four of six pull weight; two are
  dead: `NoUnicodeProvider` (the `"no-unicode"` switch arm has zero
  in-tree consumers and duplicates the tested `DISABLE_UNICODE_GROUPS`
  flag) and `resetForTest()` (a test hook no test calls). Delete both;
  keep the rest as-is.)

## G. Budget system

- **C32 — post-memo budget simplification** (M, simplify). With both
  runtime memos gone, the runtime side of the budget reduces to the
  retained-tables check (`retainedTableBytes >= budget` ⇒ reject) — yet
  `BudgetWeights` still carries the full runtime weight model, memo
  floors, and residual-split prose, `WorkMeter` keeps the fork ledger the
  side-table shares draw from (still used), and `FrameBudget` (62 lines)
  needs a usage audit. Slim the runtime half of the model to what the
  retained check needs; keep the compile half.
  **Verdict: simplify** (F+G review — every claim verifies; #99 touched
  nothing in core/budget. The two audits resolve to **keep**:
  `FrameBudget` has three live consumers (the nesting-depth RAM guard,
  62 lines well spent) and `WorkMeter`'s fork ledger is the whole-side
  compile split — leave both. Target shape subsumes C1 + C2's budget
  side, one PR: `Budgets` keeps the three properties + compile caps,
  deletes the memo allowance/caps sections; `BudgetWeights` deletes its
  runtime/memo sections; `EngineBackend.generate(Tdfa,long)` →
  `generate(Tdfa)`; `TdfaRunner` deletes `memoBudgetBytes` + the 2-arg
  ctor (pre-1.0); `PatternCompiler` drops the memo plumbing and rewords
  the `runtimeFootprint` note; test blast radius: BudgetModelTest rows,
  ExecutionRamCheckTest, the `1 << 20` callers in CampaignFuzzer/
  PivotLanes/Family6/ZeroWidthExit batteries; mark the "Deliberately
  left behind" paragraph in search-dfa-memo-tradeoffs.md resolved.)

### New items found by the F+G review

*(numbered C39–C41 by the reviewer; recorded as C43–C45)*

- **C43 — phantom `tdfa-unicode-re2j` jar promised in SPI javadoc**
  (S, remove). `UnicodeProviders.java:14–17` and
  `JdkUnicodeDataProvider.java:18–25` tell users a separate
  `tdfa-unicode-re2j` jar exists for bit-exact re2j parity — no such
  module exists anywhere. A user following the javadoc hits a wall.
  Point both at `:unicode:v6_0` (the re2j-era tier) or delete the
  sentence.
- **C44 — the retained-tables check is facade-only; core tier bypasses
  it** (S, simplify). `CompiledRegex.compile` (the public core-tier
  entry, javadoc promising budget rejections) never performs the
  runtime-memory check the facade enforces (PatternCompiler.java:190–196)
  — the same pattern rejects on the facade and compiles on the core tier
  under the same `-D`. Factor the check into one shared site both entry
  points call (5 lines; preserves the README "if it compiles, it
  executes within budget" story). Rides with C32.
- **C45 — provider-selection SPI untested and cannot load the project's
  own pinned modules** (S, simplify). `UnicodeProviders.resolve()`'s
  custom-class branch instantiates via a public no-arg constructor, but
  both shipped pinned providers have private ctors + `static provider()`
  (only SerialProxy honors that convention) — so pinning v6_0 globally
  via `-D` throws. Zero test coverage for the whole SPI. Fix: try
  `static provider()` before the no-arg ctor + one unit test, or delete
  the reflective branch if global selection is not a 1.0 feature.

## H. Tests & infrastructure

- **C33 — fuzzer harness complexity** (M, simplify). `DifferentialFuzzer`
  (2 114) + `CampaignFuzzer` (1 535): forced-rung machinery, warm replay,
  determinism double-run, JURLANE_SKIP recording with caps. Several lanes
  retired in the last months (SKIP families reclassified); sweep the
  harness for retired knobs and dead classifiers before the next campaign.
  **Verdict: simplify** (cluster H review — with the premise corrected:
  JURLANE_SKIP recording is **alive** (the 2026-10-24 soak fired it hard:
  266 restart-tag-leak + 3 840 v1-ungreedy-scope records in 52.3 M cases),
  and `-Pfuzz.warm`/double-run are tripwires pinned by
  WarmReplayDoubleRunTest (#105), not retired knobs. What IS dead:
  `CampaignFuzzer.startsInterior` + `rSpanOf` (zero callers since #86/#103),
  `DifferentialFuzzer.JUR_UCASE` (own copy in CampaignFuzzer), the
  `ciSuppAvoided` counter (no increment site since 12d9921 — reports a
  permanent 0), `CampaignSmokeTest`'s `SKIP_NESTED_LOOP_CAPTURE` allowlist
  entry (no producer since #87), and stale class-doc prose
  naming retired families as live residuals. One PR plus a prose-truth
  pass; `FORCED_RUNGS`' TRIGGER entry rides C3. **Withdrawn from the
  deletion list (second pass): the soak's `nested-loop-capture` skip** —
  #86 re-added it 25 minutes after #87's deletion, a timeline that reads
  as a deliberate revert; owner-confirm before touching.)
- **C34 — `lib:pikesim` placement** (S/M, simplify). The reference
  simulator ships as a first-class module (Java 25 bytecode, in
  BytecodeFloorTest's shippedPaths) but its consumers are tests only
  (parity columns, unit audits). If it is not a published artifact, move
  it under `testlib/` and drop it from the shipped set — one less module
  claiming to be product.
  **Verdict: simplify** (cluster H review — verified: **no published
  artifact can include it** — there is no maven-publish block anywhere and
  "First Maven publish" is still open; README's module story never
  mentions PikeSim. Target: `git mv lib/pikesim testlib/pikesim`, repoint
  the two test consumer paths, drop the tests/unit dependency + the
  BytecodeFloorTest anchor (the Java-25 tier keeps the two unicode
  modules), update the "seven shipped jars" comments — six shipped jars.)
- **C35 — Qodana** (S, decide). Plugin + `qodana.yaml` + CI spend, with
  TODO "keep or remove". Decide by what it has caught lately; if nothing,
  remove the plugin from the build.
  **Verdict: referred to the owner (second pass; was remove).** The
  "keep or remove" TODO is a deliberate, open owner decision — this
  review's evidence (zero findings-driven fixes; nothing invokes
  `qodanaScan`, not even `check`; r10 already called the wiring dead
  weight) feeds that decision but does not close it. The live option
  space includes wiring it into CI and giving it a real chance. Decide
  at the pre-freeze review; today it costs a plugin line and a config
  file.
- **C36 — `scripts/migration`** (S, remove). `add-braces.py`,
  `expand-star-imports.py` — one-shot codestyle migration scripts, already
  applied tree-wide (spotless enforces the style now). Delete.
  **Verdict: remove** (cluster H review — both scripts self-identify as
  one-shot in their docstrings; the rules are permanently enforced by
  checkstyle `NeedBraces`/`AvoidStarImport` riding `./gradlew check`;
  nothing references the scripts since the migration (#15). Delete the
  directory.)
- **C37 — branch debris** (S, remove). ~90 local branches, half `t3code/*`
  scraps; the stale-branch-report workflow exists for exactly this. Prune
  the merged/dead ones.
  **Verdict: remove** (cluster H review — worse than listed: **158 local
  branches + 77 registered worktrees** (a branch checked out in a
  worktree cannot be deleted until the worktree goes). The remote is
  clean (1 head). Safe classes in order: stale tracking refs
  (`git fetch --prune`); merged branches (worktree remove, then
  `git branch --merged origin/main | grep -vE 'main$' | xargs -n1 git
  branch -d`); squash-merged PR heads not ancestry-merged (cross-ref
  `gh pr list --state merged` exactly as stale-branch-report.yml category
  1 does). Keep genuinely parked work (`feat/non-recursive-parser`, the
  active fuzz24 finding branches). Do it after this review's own branch
  churn settles.)
- **C38 — vendored re2j patch debt** (M, keep — watch). `vendor/` +
  `scripts/vendor.sh` + `re2j-jemmix` fork with upstream PRs #208/#212/#213
  pending maintainer review. Not removable while the parity suite depends
  on it; the debt closes when upstream lands. Record and leave.
  **Verdict: keep** (cluster H review — the parity suite hard-depends on
  the vendored tree and the patched fork is the DEFAULT oracle for the
  whole parity/fuzz surface. The load-bearing argument: the fork makes
  **every** divergence a real finding (the released 1.8 oracle has known
  bugs that would need swallow-classifiers), the archive is pinned to the
  re2j-1.8 tag, and it rebuilds bit-for-bit from one committed tarball.
  Patch count small and every unit upstreamable. Watch only: upstream
  PRs #208/#212/#213 verified still open on google/re2j; the debt closes
  by swapping patches for a released artifact when they land.)

### New item found by the cluster H review

- **C54 — documented fuzzer knobs are unreachable through the Gradle
  entry point** (S, simplify). The `fuzz` JavaExec task forwards exactly
  13 `fuzz.*` properties, but the fuzzer reads and documents four more:
  `fuzz.threads`, `fuzz.graceMs`, `fuzz.jfr`, `fuzz.caseTimeoutMs` —
  unreachable via `-Pfuzz.…` because JavaExec forks a fresh JVM passing
  only the declared properties, and `scripts/fuzz-soak.sh` advertises two
  of them in its own header. These are exactly the knobs wanted on a
  misbehaving overnight soak. Fix: add the four systemProperty
  forwardings (or scrub the doc mentions — forwarding is smaller and more
  useful).

## Progress log

- **2026-10-28** — list built from a full-tree pass (engine, ASM, facades,
  pipeline, unicode, budget, tests, scripts); 38 items across 8 clusters.
  Delegated reviewers queued, one per cluster.
- **2026-10-28 (update 1)** — landed on main via #100. **C4 closed:
  already-landed by #99** (W4 executed the same day this list was built —
  the three lazy caches are constructor-final now, the duplicated javadoc
  went with them, and the finality gate's memo allowlist is empty).
  7 delegated cluster reviews running.
- **2026-10-28 (update 2)** — **cluster A verdicts in** (delegated review):
  C1 remove (dead caps, plus never-referenced `WALK_STATE_TABLE_BYTES`),
  C2 simplify (memoBudgetBytes is write-only; no split arithmetic exists),
  C3 remove (TRIGGER never traced; forced-TRIGGER ≡ forced-RAW_SCAN
  byte-identical), C5 keep (gate's ratchet teeth). Two new items:
  C39 (fictional half-split prose), C40 (stale memo prose inventory).
  List grows to 40 items; clusters B–H still in review.
- **2026-10-29 (update 3)** — **clusters B, C, D, E, F+G verdicts in**
  (delegated reviews). Headlines: the whole-match machinery stays (its
  alternatives already shipped and were priced out — #13/#24/#26); the ASM
  dual emitters stay (committed bench artifacts decide C13/C14 at 2–4×);
  the ladder keeps every rung except TRIGGER (≡ C3), with the fold
  sequenced C48→C8→C7 (scope 1) →C9; `Pattern.reset()` stays (re2j's own
  reset is not a no-op); C20's axis bits are **unreachable dead code**;
  C1/C3/C41/C43 removals confirmed dead. Reviewers corrected the list in
  nine places (constructor arities, PikeSim caller claim, forced-rung
  scope, 30 KB budget, 8 shell fields, phantom "non-recursive parser
  TODO", ALL_AXES drift, semanticsOf unreachability, the half-split
  fiction). 53 items, 47 verdicts; H (relaunched twice after quota
  failures) in review.
- **2026-10-29 (update 4, final)** — **cluster H verdicts in; the review
  is complete: 54/54 items verdicted.** H corrected the C33 premise
  (JURLANE_SKIP is alive and firing; warm/double-run are pinned
  tripwires), confirmed Qodana (C35) and the migration scripts (C36) as
  pure removals — Qodana has zero findings and is invoked by nothing —
  moved pikesim to testlib-territory (C34, no publish config exists),
  priced the branch/worktree debris at 158 + 77 (C37), recorded the
  re2j fork's keep argument (C38, upstream PRs still open), and found
  the unreachable fuzzer knobs (C54). Final board: 10 remove,
  20 simplify, 5 measure-first, 18 keep, 1 already-landed. Execution
  order recorded above; examination phase closed.
- **2026-10-29 (update 5, second pass)** — challenged the first pass's
  keeps against the mission test (drop-in surfaces + speed headline +
  sane compile). Five challenges recorded as owner decisions: **C55
  multi-valued tags delete-or-fuzz** (no drop-in consumer, unfuzzed
  lane, pre-1.0 window), C56 public axis probe, C57 BYO-engine factory,
  C58 public observer, C59 stack-regs re-priced against the headline.
  Intent sweep walked back the overreach: **C35 Qodana referred** to its
  TODO's owner (evidence stands, decision doesn't), C33's nested-loop
  skip withdrawn (possible deliberate #86 revert), C21's TODO left open,
  C34 gated on the publish TODO; C1/C2/C3 confirmed as answering
  questions the tradeoffs doc explicitly left "for the review". Board:
  59 items — 9 remove, 20 simplify, 6 measure-first, 18 keep,
  5 owner decisions, 1 already-landed.
