# Glossary

The project's coined vocabulary, one line each. Every term below is made up
here — either invented outright or an ordinary/paper word this tree bends into
a precise project-specific meaning (those carry a *(borrowed)* marker with
their root). Standard regex/CS textbook terms (ε-closure, subset construction,
leftmost-first, surrogate pair...) are **not** repeated; neither are external
names in their ordinary sense (RE2, re2j, JMH, ASM-the-library, UCD).

Grouped by area, alphabetical inside each group. "Lives in" names the
defining file(s), not every occurrence.

## Shorthands and acronyms

- **BT19 / BT22** — citation shorthand for the two papers the engine
  implements: Borsotti–Trofimovich 2019 (ntag/U-tree machinery, §7.3) and
  2022, *A closer look at TDFA* (arXiv:2206.01398). Section refs (§3.1
  multi-valued tags, §6.3 register optimization, §6.4 fixed tags) appear
  throughout javadoc. Lives in: everywhere; expanded in `README.md`.
- **jur / JUR / j.u.r** — **J**ava.**u**til.**r**egex, the JDK regex engine
  and the parity reading named after it (jur facade, jur lane, jur oracle).
  Self-defined in `docs/jur-compat-default.md`.
- **MVT** — multi-valued tags, i.e. `MULTI_VALUED_TAGS` (BT22 §3.1). Fuzz
  record kinds prefix it (`MVT_SELF`, `MVT_TIER`, `MVT_SIM`, ...).
- **sdfa** — the deleted lazy search-DFA (`SearchDfa`); survives in budget
  identifiers (`sdfaMaxRows`) and docs. See `docs/search-dfa-memo-tradeoffs.md`.
- **UCC** — `UNICODE_CHARACTER_CLASS` ("jur-UCC word set", the UCC oracle).
- **ZWE / BOUNDED_ZWE** — zero-width exit; the builder frame kind that splits
  bounded repeats so a zero-width iteration exits the loop (java's `Loop`
  guard). Lives in: `core/tnfa/Tnfa.java`, `zwexit-battery.tsv`.
- **ELL** — `EMPTY_LAST_LINE` (the `(?m)^`-after-trailing-newline axis).
- **bare CI** — `CASE_INSENSITIVE` without any unicode widening: the 26 ASCII
  letter pairs, nothing else.
- **BYO** — bring-your-own (engine/factory): a caller-supplied
  `RegexEngineFactory`. BYO shells wrap consumer engines.
- **UOE** — `UnsupportedOperationException`, thrown by every whole-input
  method of a `FIND_ONLY` compile.
- **reggie** — DataDog's java-reggie, external sparring partner and bench
  column; "reggie-style dispatch" names the multi-engine alternative the
  dispatch-engine spin-off proposes.
- **jemmix** — the author namespace (`io.github.jemmix`, `re2j-jemmix`).
- **tdfa / tdfa-jvm** — the engine: tagged DFA on the JVM. Class prefix
  `Tdfa*` throughout.

## Surfaces, lanes, and semantics

- **surface** — a published API persona over the shared machinery. Two
  ship as facades: the jur facade (root, `io.github.jemmix.tdfa.Pattern`)
  and the re2j facade (`:re2j`); before C17's removal they were registered
  as `PatternSurface` entities — now each persona is just its facade plus
  a `Lane` funnel. Also "String surface" vs streaming, "whole/find
  surface" for API areas.
- **facade (root facade)** — the top-level `Pattern` API class; "facade
  tier" is the layer above core. The jur facade is the default since the
  flip. Each facade compiles through its package-private `Lane` funnel
  (roster validation, default lane, default provider, VM-path wrap).
- **thin surface / two-surface split** — the architectural event/shape of the
  API split: minimal API-only modules over one blind machinery.
- **lane** — a fixed bundle of semantics decisions a compile runs in (jur
  lane = every axis unset; RE2 lane = every axis set; also codepoint/unit
  lane, multi-valued lane, fold lane, default lane, stock lanes). A facade
  *bakes in* its lane (`Lane.DEFAULT_SEMANTICS`).
- **the flip / pre-flip / post-flip** — the switch of the shipped default
  from the RE2 reading to jur parity (`docs/jur-compat-default.md`).
- **reading** — one coherent interpretation of the syntax ("the jur reading
  of `(?U)`"); *spelling* is one syntactic form of it (`(?U:...)`, in-group,
  negated).
- **lineage** — which engine family a behavior descends from
  (RE2-lineage/re2j lineage vs jur parity).
- **axis / axes** — one independent semantic dial of the `Semantics`
  bitset. Shipped axes: `UNIX_LINES`, `UNICODE_CASE`, `CODEPOINT_BOUNDARIES`,
  `EMPTY_LAST_LINE`, `END_OF_TEXT_ONLY`, `EMPTY_ITERATION_SPANS`,
  `UNGREEDY_U`, `SPACE_RE2`, `SHORTHAND_FOLD`. Set = RE2 side, unset = jur
  side (the *polarity*).
- **pivot** — a code point where two readings diverge, parameterized so both
  lanes can select ("candidate-scan pivot", "parser-side pivot",
  "parameterize the pivots" in the WBS). `PivotLanesTest` audits them.
- **mixed probes / canned lane / per-axis ceremony / un-gettable-wrong** —
  `CompileOptions.semantics(...)` picks axes individually ("mixed probes");
  a canned lane is a preset; the re2j facade removes the "ceremony" so its
  reading is "un-gettable-wrong".
- **roster / alphabet** — the exact closed set of flag bits a facade accepts
  (out-of-roster bits reject, validated in its `Lane`); the set of inline
  flag *letters* it binds.
  `FlagAction` is the closed behavior vocabulary; `FlagBindings` the
  letter→behavior map (`JUR_LANE` / `RE2_LANE` are the stock bindings).
- **crossed u/U** — the engine's `(?u)`/`(?U)` letters are swapped vs the
  JDK: our `(?U)` is java's `(?u)`.
- **fold universe** — the set of case-fold equivalences in force (ASCII-only,
  full Unicode simple fold, snapshot-pinned). Rides the facade default.
- **orbit / i-orbit / s-orbit** *(borrowed: math/re2j `CASE_ORBIT`)* — a
  case-fold equivalence class; the i-orbit is the one İ/ı either join (jur)
  or don't (RE2). A codepoint whose orbit is a singleton is **fold-inert**;
  the **İ/ı pin** is the one deliberate divergence.
- **pin / pinned / frozen / floats** — tables or folds locked to a fixed
  Unicode version (`:unicode:v6_0` = re2j's 6.0 tier, `:unicode:v17_0` =
  modern tier) vs ones that follow the runtime JDK. Providers
  *pin a snapshot* or *bridge the live oracle* (parity tiers).
- **oracle** — a reference implementation whose answers define truth: the
  live oracle (running JDK), the patched oracle (this repo's patched re2j
  fork) vs the pristine/released oracle (stock re2j 1.8), the contract
  oracle (re2j in hard-gate suites). "Probed, not declared": oracle flavor
  is detected by behavior (the lone-low-interior discriminator).
- **oracle ladder** — the stacked oracles (re2j → PikeSim → VM → ASM) that
  attribute a divergence to one layer; `LayeredComparator` is its harness.
- **StartS / plain Start / hasSupplementaryStarts** *(borrowed: JDK
  internals)* — java's choice of codepoint-aligned vs unit-aligned candidate
  scanning, mirrored and frozen onto the artifact; patterns *flip* it
  per-atom (the **slice quirk**: multi-char literal runs never flip).
- **pair interior** — the low half of a well-formed surrogate pair; no match
  may start, restart, or re-seed there (`Alphabet.pairInterior`).

## Compile pipeline

- **artifact** — a compiled, frozen `Tdfa` (or `DeterminizedDfa`); "one
  artifact, one engine" is the rule that find and whole share it.
- **determinizer** — the subset-construction stage (`TdfaCompiler`);
  **materializer** — the freeze-into-flat-arrays stage (`TdfaMaterializer`);
  **minimizer** — the register-aware Moore pass (`DfaMinimizer`).
- **kernel / config** *(borrowed: re2c `kernels_total`, paper "kernel")* —
  a DFA state's closure in arrival/priority order; one member is a `Config`
  `(state, regs, h, l, pri, emptyMask)`. Kernel *spikes* are transient
  closure memory, capped separately.
- **breakpoints / cells / minterms** — the global codepoint-partition cuts
  and the equivalence classes between them; each cell is probed at its
  representative. The minimizer re-cuts states on one **global breakpoint
  grid** so differently-cut twins compare equal.
- **h / l** — the two hash-consed tag-history ids: `l` accumulates writes
  during this ε-closure; `stepOnSymbol` *rotates* `l` into `h` (rotated,
  not accumulated) so kernels stay finite. `HistTable` interns them.
- **mint / minting order / fresh()** — allocating a new NFA state; the
  numbering order is part of the bit-identical-artifact contract.
- **quest** — the project's name for the `?` fragment; a nullable `X*`
  compiles as **quest(plus(X))**.
- **(loop) hub** — the shared loop state where iterate-vs-exit is decided;
  **pre-loop fork** is the enter-vs-skip split placed before it.
- **ntag / ntag chain** — a negative tag (ε-edge writing `-t`, clearing a
  group to nil) placed on skip paths so non-participation is structural
  (BT19 §7.3).
- **desugar / plus tail / right-nested suffix / flat tail** — the eager
  `{n,m}` rewrite: `x{n,}` = copies + `x+` (the plus tail cuts empty
  re-iteration); `{n,m}` uses the right-nested `(x(x(x)?)?)?` shape (a flat
  `B?B?B?` tail resolves priority ties the opposite way).
- **tryMap / register bijection** — merging a new closure into an existing
  state by proving a register bijection (the paper's `map`), prepending
  COPY ops; rejected on a **non-trivial cycle**.
- **regopt** — REGister OPTimization, the BT22 §6.3 pass (CFG → liveness →
  DCE → interference/coalescing → normalization), run N=2. `tdfa.noregopt`
  skips it.
- **fixed tags / base tag / fix on** — tags reconstructable as
  `tag[base] - offset` (BT22 §6.4), dropped from the NFA and rebuilt at
  match time (`reconstructFixed`). Distances **poison** to NaN on
  ambiguity/overflow.
- **tag / tag pair / NIL** — a capture position register; group g owns tags
  `2g-1` (open) and `2g` (close); NIL (-1) means unset, and
  `start(g) == -1` *is* the participation test.
- **register file / working vs final registers / finalRegBase** — the flat
  `int[]` per walk; finals occupy the top block and are never coalesced
  (the readout protocol `regs[finalRegBase+t-1]` must stay fixed).
- **OP_SET_POS / OP_SET_NIL / OP_COPY / OP_APPEND_POS / OP_END** — the
  register-op ISA; append ops extend a tag's offset sequence (multi-valued
  lane) in **append chains** scheduled readers-before-writers.
- **φ ops / final ops / eager φ** *(borrowed: SSA φ)* — the accept-time ops
  that fix final register values; applied *eagerly at accept-record time*
  (a lazy end-of-walk replay would be unsound). `stateFinalOpsByMask` is
  the position-aware **φ table**; a state is **mask-uniform** when one
  block serves all positions; the **winner** is the highest-priority accept
  config alive under the position flags.
- **last sign** — the final +/- a tag had in a history; what transition ops
  synthesize.
- **pike cut** *(borrowed: Pike VM)* — the Perl/leftmost-first pruning
  during determinization that deletes continuations ranked below an alive
  accept (the determinized analog of a Pike VM cutting threads below the
  recorder). It **bites** when it deletes something; **pike-cut matters** is
  the hazard flag for whole-input walks; POSIX never cuts.
- **whole side / partial-whole side table / side sweep / whole-bomb** — the
  bounded secondary determinization recording a cut state's *uncut
  continuations* beside the pruned ones, so one artifact serves `find()` and
  `matches()`; abandoned over budget (**accept-only-what-ships** rejects the
  compile) — a pattern whose side explodes is a whole-bomb.
- **FIND_ONLY / compileFind / find-shaped** — the consumer contract that
  skips all whole machinery; whole-input methods then refuse with UOE.
  Everything except whole-input matching is "find-shaped".
- **stop table / stop-on-accept / NEVER_STOP / stop-or-extend** — the
  Perl-only per-(state, posFlags) decision to stop on an accept or extend
  (a higher-priority symbol-bearing config outranks it). Stored in tiers:
  full 2-D, 1 B/state uniform, or none (POSIX).
- **posFlags / posFlagCells / posFlagDeps** — the runtime zero-width
  assertion bitmask at the cursor (64 cells, or 128 when any edge carries
  FINAL_END); deps = the bits the artifact actually distinguishes.
- **entry mask / accept mask / requiredMask / dead marker** — per-state,
  per-accept, and per-range assertion gates (target -1 = the dead marker;
  "only -1 is the dead marker").
- **subsumption cut / empty-iteration cut** — the closure rule killing a
  re-arrival whose mask is subsumed by an earlier pop; the determinized form
  of the Pike pc-dedup that makes empty loop iterations die.
- **family 6 / sub-family A (dissolve) / sub-family B (surface)** — the
  numbered divergence family for zero-width-iteration spans: A = the pair
  reports NIL at every accept (a **dissolved** pair, SET_NIL φ override);
  B = the final zero-width iteration's write is **surfaced** as a SET_POS at
  the accept position (determinizer **cut surfacing**). Recorded in
  `family6-battery.tsv`.
- **sticky split / first writing iteration / invocation frontier /
  W-current / W-later** — the clone construction reproducing java's
  nested-loop inner-capture quirk: the *first* writing iteration's span
  sticks; writes are cloned into one world, the frontier's nil-clears into
  another.
- **zero-width exit / REST position / consuming tail** — the rule that a
  bounded quantifier exits after a zero-width iteration (java's `Loop`
  guard); the unbounded **mirror** re-routes pure-ε completions onto a
  normal tagged ε-edge at the loop's REST position. Tail shapes
  (`(a*)*b`) are the family's residual slice.
- **restart-tag leak / ATOMIC span / stale write / displaced write /
  exact-restore** — the JDK bug family (JDK-8381696) where a capture from a
  *failed* restart attempt survives into the accepted match: backtracking
  commits an ATOMIC span that never unwinds; `GroupCurly` leaves stale or
  displaced (wrong-value) writes. Decided fix shape: exact-restore
  (`docs/jdk-8381696-restart-tag-leak-fix.md`).
- **wide dispatch index / Latin-1 tables / one-load dispatch** — the eager,
  immutable two-level `(state, codepoint)` map giving wide classes
  (~700-range `\p{L}`) one array load past the flat Latin-1 tables, on
  every walk and scan path (`docs/wide-dispatch-index.md`).
- **exactness gate / whole-exact / wholeWalkExact** — whether whole-input
  walks on the artifact are exact (cut never fired, or side recorded).

## Match time

- **runner** — `TdfaRunner`, the table interpreter; "the runner" also means
  the match-time tier generally. **Tier** picks the backend: VM
  (interpreter, `-Dtdfa.engine=VM`) vs ASM (per-pattern generated class).
- **walk / scan** — a walk runs the DFA from one candidate start (exact
  walk, candidate walk, boolean walk, whole walk, generic walk, fast/generic
  pair); a scan sweeps input for starts (trigger scan, raw scan, candidate
  scan). The pair is the project's core execution vocabulary.
- **ladder / rung / Strategy** — the ordered search strategies, one rung
  each: `LITERAL`, `PREFIX`, `CAND_SCAN`, `EXACT_FROM`, `ORIGIN_SIM`,
  `TRIGGER`, `RAW_SCAN`, `WALK_RESTART`, `ANCHORED_FAST`, `ANCHORED`,
  `GENERIC`. `setForcedStrategy` forces a rung (fuzzing); the **natural
  ladder** is the organically chosen path; **cross-rung differential**
  re-runs each; **strategy conformance** asserts both tiers pick identical
  rungs ("identical results can hide a different algorithm").
- **needle / prefix chain / case-stable run / fold steps** — the literal
  substring the detector extracts for `String.indexOf` candidate
  enumeration; a chain may pass through case-*fold* steps before its
  case-stable run; the needle sits at a fixed **offset** from match start.
  Guards: **start guard** (candidate not pair-interior), **end guard**
  (needle hit not split by a pair), **hit floor** (resume position after a
  hit), `PREFIX_WALK_BUDGET` (**dense-hit adversarial shapes** fall back to
  the complete ladder).
- **adaptive prefilter** — after N failed extract walks, run the cheaper
  register-free boolean walk first per candidate.
- **origin sim / origin tracking / min-merge / LSS** — the budgeted
  multi-state simulation tracking each state's smallest live start
  (**leftmost-start sim**, `LSS_BUDGET` is its exhaustion sentinel).
- **kill-point / kill-point window / W** — when a scan step kills every
  live thread, no match can start before the next position: the restart
  loop is bounded to `[W, to]`. The runtime product of the trigger/raw scan.
- **live set / seed / seed-only / re-seed / interior seeds** — the bitset of
  live configurations; seeding re-injects the start state each position
  (unanchored-ness as a self-loop, "the implicit `.*?` prefix"); a
  **seed-only** step is one where the live set is exactly the re-seed
  (single-word DFAs answer it in one compare); the unit lane adds interior
  surrogate seeds (the **unit twin**).
- **trigger / trigger scan / raw scan** — the kill-point-aware multi-state
  scan, once memoized behind `Strategy.TRIGGER`, now the unmemoized
  `RAW_SCAN`. Triggers over-approximate: they can fire without a match but
  never miss one.
- **restart ladder / restart floor / defensive restart** — the per-start
  re-walk rung, defensively re-running when sim and walk disagree; its
  floor is bounded by W.
- **abs-begin ladder bound** — when every accepting path crosses `\A`, the
  ladder collapses to the exact walk at the search origin ("the classic
  start-gated collapse").
- **fastPath** — the DFA shape with no masks and disjoint ranges that both
  tiers consult (`fastPathEligible`, "the ONE predicate") for flat-table
  dispatch.
- **shell / GenNNNPattern / Shell\<hex\> / GenClassLoader** — the emitted
  per-pattern `Pattern`/`PatternMatcher` wrapper pair ("one brain, thin
  generated hot paths"); BYO shells wrap factory engines; generated classes
  load through a per-pattern `GenClassLoader` under the
  `io.github.jemmix.tdfa.gen` package.
- **INLINED / DELEGATE / pickMode** — the ASM dispatch modes: inline the
  ladder into the generated class (under the 30 KB `INLINE_BUDGET_BYTES`,
  below the JVM's 65 KB per-method cliff) or delegate to the embedded
  runner ("it IS the VM ladder"). `phiMasked` is the position-aware accept
  helper; `extractOne`/`wholeOne` are the generated walk leaves.
- **stack-register / zero-register leaves** — emission variants keeping the
  register file in JVM local slots (or having none at all), borrowing
  nothing from the carrier on hot paths.
- **carrier / scratch / takeRegs / takeTree / grow-only / wantsScratch** —
  `MatchScratch`, the per-matcher pooled buffer object ("one carrier per
  matcher"); takers rent arrays with contents undefined on take, cloned
  before anything escapes; carrier-free engines decline one.
- **MatchHolder / TagTree / head / tree head / snapshot** — the walk's
  mutable result holder; the multi-valued append-only prefix tree whose
  node ids (**heads**) sit in register slots; `snapshot()` freezes a
  trimmed copy for the immutable `MatchResult`.
- **@EmittedSurface / hook surface / emitted check** — the annotation and
  naming contract for members generated bytecode links *by name*
  (`EmittedSurfaceConformanceTest` mirrors the descriptor manifest); an
  emitted check is a guard compiled into generated code.
- **mask-before-ops / ownership rule** — entry-mask evaluation precedes
  transition ops so dying paths can't *contaminate the register file*; on
  overlapping masked ranges, the most-specific satisfied mask (popcount)
  owns the step, ties to the lowest index, with no fallthrough past a dead
  marker.
- **stateMeta / stateBase / entryHiPrefix** — the packed per-state layout:
  `(rangeCount << 1) | acceptBit`, per-state range-table base, and the
  prefix-max-of-hi enabling the anchor + walk-back range lookup.
- **programSize** — the DFA state count as the public cost estimate (NOT
  comparable to re2j's NFA-instruction count of the same name).
- **one definition / transcription** — the design rule that emitted code is
  a bytecode *transcription* of one shared interpreter definition, so tiers
  cannot drift.

## Streaming input

- **ChunkSource / ChunkSources / chunk / feed / pull** — the pull-based
  chunk SPI and its adapters (utf8, reader, byteBuffers, of); chunks are
  non-empty by contract (an empty chunk fails loudly); byte-side adapters
  carry partial sequences across pulls; the **supplementary quantum** is
  the two-char minimum decode granularity.
- **StreamMatcher / streamingMatcher / driver (the one driver)** — the
  per-stream matcher; both tiers forward to the single resumable driver
  inside `TdfaRunner`.
- **pend rule / pending** — the whole soundness argument: at `pos ==
  frontier` (or a straddled surrogate, or a terminator run touching it) an
  answer could change with the next chunk, so the walk *pends* instead of
  deciding; every suspension point must be a pend-rule site.
- **frontier / window / retention floor / compaction** — the boundary of
  arrived input; the retained window runs from the floor (leftmost
  undecided start, or needle-length for literal searches) to the frontier
  and compacts past reported matches; `tdfa.budget.stream.window` caps it
  (`StreamWindowOverflowException` — "a typed rejection, never a silent
  spill").
- **confirmed / decidable** — a match that can no longer change with future
  chunks; matches are reported as soon as decidable.
- **epoch** — one search segment between reported matches; the kill-point
  simulation runs once per epoch, and the **EOF endgame** bounds the final
  scan at end of stream.
- **live-tail shape** — `.*x` with x never arriving: the undecided walk pins
  the floor at 0 and the window grows to the stream ("the honest worst
  case").
- **carried walk** — walk state (state, regs, tree, last-accept) transported
  across `next()` calls; "the walk is carried, not re-run".

## Budgets

- **budget / knob** — the resource-limit system and its `-D` properties
  (`tdfa.budget.compile.memory|compile.compute|runtime.memory|stream.window`),
  read fresh per compile ("one knob per resource, so the caps can never
  drift").
- **tick / ledger / fork / WorkMeter** — the machine-independent compute
  unit (≈10 ns; TICKS_PER_SECOND = 100 M), the one shared cross-attempt CPU
  pool every compile phase debits, and `WorkMeter.fork(cap)`'s bounded
  child meters ("wall-clock-free: the same pattern burns the same ticks
  everywhere").
- **weight model / weighted bytes / charge / refund** — `BudgetWeights`'
  assumed per-structure costs that turn budgets into caps; work is
  *charged* (and refunded on race loss, in the deleted memo design).
- **"pattern too large" / budget rejection** — the typed clean
  `PatternTooLargeException` family ("raised where the budget trips"),
  deliberately distinct from `PatternSyntaxException`.
- **bomb / monster / spinner** — pathological compile shapes: state bombs,
  closure bombs, CFG-edge bombs, whole-bombs, **nested-counted** bombs,
  two-site bounded-gap shapes; **budget monsters** burn the whole 500 M
  ticks and still exit; **closure spinners** churn fixpoints without
  materializing output (why work is metered, not just output).
- **parenthesis soup** — machine-generated depth-only patterns; nesting is
  bounded by `FrameBudget` (frame-RAM accounting), not a depth cap.
- **closure spike** — the transient per-closure RAM burst, capped at 1/16
  of the compile budget before totals can count it.
- **watchdog / in-watchdog / sacrifice** — the fuzzer's per-case timeout;
  rejections that fire before it are "in-watchdog"; hung worker threads are
  sacrificed (abandoned), and **chunked soaks** (one short-lived JVM per
  chunk) bound the leaks.
- **scoped vs library budget** — the soak's 8 M-tick budget vs the shipped
  500 M default; **raised-knob replay** recompiles rejections at library
  defaults.
- **retained tables / residual / r11 scope decision / disclosed constants**
  — the end-of-compile execution-RAM check; the (deleted) memo allowances
  drew from the residual; review item r11 keeps engine-tier
  construction-time tables outside the budget as disclosed constants.
- **eighths partition / memoBudgetBytes** *(both deleted)* — the old
  runtime-memo budget split and the write-only parameter whose removal
  spawned the `.java-E` fossils.

## Multi-valued tags

- **multi-valued tags / compile lane** — the opt-in `MULTI_VALUED_TAGS`
  lane (BT22 §3.1): every tag carries its whole offset sequence under
  repetition; "the lane is fenced" — single values stay bit-identical.
- **groupSpans / participation / bypassed iteration** — the per-iteration
  span readout; iterations that wrote the tag *participate*; iterations
  that skipped the group contribute nothing (per the **re2j capture
  contract**: a value once set stays set).
- **per-iteration correlation** — the capability nothing else offers:
  iteration k of one group aligned with iteration k of another.
- **the four MVT oracles** — the lane's fuzz checks: self-consistency,
  latest==plain (the **latest** pair equals the single-value report), tier
  equality, and the PikeSim write log.

## Testing and fuzzing

- **parity / pinned / retired** — differential equality with an oracle;
  tests *pin* behavior (a **literal pin** is a transcribed expectation;
  **the data is the live JDK** when re-verified per run); exclusions
  *retire* when the upstream bug is fixed.
- **twin / jur twin / cross-pin** — the same suite re-run against the other
  oracle/lane; a test in one suite additionally locking the other backend.
- **battery / M=|F=|R= protocol** — a replayable expected-value corpus
  (`family6-battery.tsv`, `zwexit-battery.tsv`); each row records
  whole-match, first-find, and restart probes as span lists; JUR columns
  re-verify against a live JDK every run.
- **campaign / soak / leg / finding / F-numbers / caseSeed** — dated,
  timeboxed fuzz efforts (CampaignFuzzer `-Pcamp.*`) vs the standing
  nightly DifferentialFuzzer **soak** (chunked, both lanes drawn per
  batch, pinnable via `-Pfuzz.lane`); a **leg** is one run; findings are
  numbered (F5, F7); every case replays from its seed (`-Pfuzz.one`).
- **stretch / stretched inputs** — whole-string repeats past 4300 chars so
  long-scan paths run with real kill-point windows.
- **shrink / CaseShrinker** — delta-debugging a failing case to a minimal
  same-kind repro over six structural passes (alternation, unwrap,
  quantifier, atom, class member, literal).
- **five-probe protocol / F I M L R** — the compared answer string: first
  find, find-iteration, matches, lookingAt, and the mid-input restart
  probe `find(len/2)`; "spans only — no free text".
- **skeleton / clause** — the protocol with group clauses stripped
  (skeleton-equal means only group bookkeeping diverged); named slices of
  the protocol string.
- **hard vs soft kinds / SKIP_* / KNOWN_* / JURLANE_*** — the closed
  finding taxonomy: hard kinds fail the run; soft kinds are recorded
  (documented contracts: `SKIP_RESTART_TAG_LEAK`,
  `SKIP_NESTED_LOOP_CAPTURE`, `KNOWN_DIVERGENCE`, `JURLANE_GUARD`...).
  "No soft kind may appear that is not consciously enumerated."
- **oracle bomb / bomb oracle** — a catastrophic-backtracking shape for
  the java.util.regex oracle; guarded batches are skipped whole.
- **LayeredComparator / verdicts PASS TIER CONSTRUCTION PARSER SIM_SUSPECT
  CHAOS / R S V A** — the four-column comparator (re2j, PikeSim sim, VM,
  ASM) whose "one case, four engines, one pre-localized verdict" replaces
  manual bisection with a column vote.
- **PikeSim / spec-of-record / pike queue marking / Pike record rule /
  parked** — the deliberately-direct Pike-VM interpreter over the compiled
  `Tnfa`, the engine's specification-of-record; first thread to arrive at
  a state claims it (the queue marking); a recorder cuts every
  lower-priority thread (the record rule); symbol-waiting threads are
  **parked**. Capture arrays fork copy-on-write (**caps / cap fork**).
- **patched vs released oracle / pristine** — the vendored re2j fork
  (`vendor/re2j-jemmix`, fixes 0001-0003) vs stock re2j 1.8; the fuzzer
  probes which is on the classpath.
- **re2j-suite / testregex / Fowler / .dat corpora / prepareVendor** — the
  vendored upstream re2j test suite and Glenn Fowler's testregex corpora
  (basic.dat, nullsubexpr.dat, repetition.dat); `./gradlew prepareVendor`
  materializes them from `vendor/archives`.
- **rebar / scenario / haystack / curated / in-scope / identity
  (java/hotspot)** — BurntSushi's vendored regex benchmark corpus; each
  TOML `[[bench]]` is a scenario over a haystack file; the project claims
  the `java/hotspot` then `re2` then `.*` identity when resolving expected
  counts; "we are the only engine under test."
- **tripwire / finality gate / @MemoCache / allowlist / ratchet** —
  permanent tests that fire on drift; the finality gate is the reflection
  scan enforcing runner immutability (non-final fields only on sanctioned
  carriers or allowlisted with justification; the allowlist only ever
  shrinks).
- **warm == cold == concurrent / warm replay / determinism double-run** —
  the memo-purity tripwires: a warm artifact must answer byte-for-byte
  what a cold one did; batch siblings are replayed to reproduce warmth;
  every case runs twice.
- **hammer / freeze watchdog** — N threads stressing one shared Pattern,
  with content hashes of every frozen table taken before/after.
- **smoke / Smoke8 / floor proof** — minimal end-to-end sanity runs; Smoke8
  proves the four Java-8-floor jars on a real JDK 8.
- **.java-E files** — compiled-out fossil twins of five tests + the fuzzer,
  parked outside javac's `*.java` glob when the `memoBudgetBytes`
  constructors were deleted (commit f3993ee); they preserve the old-API
  call shape. What "E" stands for is nowhere written.
- **gray skips / green set / skip buckets** — the rebar parity suite's
  visible skips with a recorded reason family and a capped total, so the
  green set cannot silently shrink ("surface, never silently absorb").
- **evidence re-base** — re-running the committed differential evidence
  (5.7 M exhaustive cases, ~300 M fuzz) after the flip, in both lanes.
- **retrodiction** — re-running the LayeredComparator against historical
  bug shapes to prove it would have localized them.

## Docs and process vocabulary

- **WBS / item numbers** — the (retired) jur-compat work-breakdown plan,
  cited by item ("WBS item 7", "the item-4 essays") as provenance.
- **C-numbers (C1-C59) / W-numbers / G-numbers / r-numbers / M3 / P1 P6** —
  the item-ID schemes of the complexity review (C; clusters A-I with
  verdicts remove/simplify/measure-first/keep), the runner-immutability
  program (W work items, G mutability clusters), the wide-dispatch review
  (r10, r11), an internal milestone, and perf-roadmap items.
- **price / pricing / keep-cost / load-bearing / earn its slot** — the
  review's economics: complexity is priced against measured benefit; a
  structure's keep-cost must be paid by warmth/perf; load-bearing code is
  necessary despite looks.
- **decision record / decision queue / owner call / verdict** — the house
  doc genre (why + evidence, re-litigable); the curated shortlist of open
  calls; decisions reserved to the owner; the closed verdict set.
- **house rule** — a documented standing convention ("docs track open work
  only", "unsupported features fail loudly and name the culprit").
- **landing / landed / landing order / landing gate** — merging through the
  PR pipeline; the review's suggested PR sequence; the perf gate
  (`bench-regression.sh`, the 15% rule) with committed baselines.
- **honest cache / first-class / regime / restoration criteria** — a cache
  that is a pure function of (artifact, key); a supported permanent path
  (not a degraded fallback); the input class where a structure matters;
  the pre-registered measurements that would resurrect a deleted one.
- **wishlist** — the README's feature list; lines are deleted on genuine
  completion.
- **beauty batch / no-behavior-change round / freeze-bound / verified-hot /
  false green** — the batched cosmetic cleanup PR; the only sanctioned way
  to touch verified-hot (benchmark-pinned) paths on a freeze-bound
  codebase; a degraded analysis must exit non-zero, never a false green.
- **prose-truth / vestige / debris** — doc-accuracy findings; leftover code
  from removed machinery; branch/worktree leftovers.

## Benchmarks

- **QuickBench / RegressionBench / ShortFindBench / CaptureBench /
  ParameterizedShortInputBench / LogExtractMacro / StreamingBench /
  RebarBench** — the bench family: ~15 s no-JMH quick signal (writes
  JMH-schema JSON), the ~1 min JMH regression gate, short-input find,
  capture-heavy "profit cases", parameterized anchored shapes, the
  plain-main log-extraction macro, streaming vs String, and the 5-engine
  rebar sweep (jur, re2j, reggie, vm, asm).
- **op naming (`<family>.<engine>`, .vm / .asm / .re2j)** — QuickBench's
  dotted rows: `info.anchored.asm`, `info.extract.vm`,
  `re2jFindAllWide`, `scanNoMatch`, `findAllDense`,
  `prefixWordDense`, `compile.re2j`; `.vm` = interpreter, `.asm` =
  generated, `.re2j` = through the re2j facade. CaptureBench instead
  says `tdfa`/`asmc` for the same two tiers.
- **slug** — a short key selecting a (pattern, input) pair
  (litFind, wordUnicodeCls, lettersRu, ipExtract, redos...).
- **control / norm** — QuickBench's branch-free char-sum control loop and
  its normalized score (raw ÷ control × 1000) that survives CPU drift;
  **min-of-N** is the repetition statistic; "never mix harnesses".
- **durable claims / self-race / W T L / DNF / geomean** — only
  engine-vs-engine ratios within one run are durable; the pre-fix jur
  column was a self-race (our engine in jur's calling idiom); rebar
  scoreboards count wins/ties/losses; DNF marks a row that didn't finish.
- **count-verified / count-divergent (`*`)** — match counts checked against
  jur before timing is trusted; rows where engines disagree on count are
  starred and excluded.
- **loss clusters / dictionary / lexer-veryl / i1095 / datefinder / Twain /
  liveTail** — named rows: where rebar showed losses; the giant
  alternation scenarios; the datefinder alternation (pike-cut hazardous);
  the 16 MB literal search riding the `indexOf` intrinsic; the streaming
  worst case.
- **fast / accurate / triage signal / thermal fairness** — rebar bench
  modes: fast caps haystacks (2 M chars, min-of-3) as a triage signal;
  accurate runs full haystacks overnight; engines interleave per pass so
  throttling hits all columns symmetrically.
- **landing baseline / `<host>-quick.json`** — committed per-machine
  reference scores the gate compares against.

## Vendor and generated code

- **pristine / patched / refresh / prepare / copy map** — the vendor tree's
  states and operations: pristine upstream tarballs (one archive per dep,
  sha256 sidecars, `NN-*.patch` applied in lexical order) extracted and
  patched into `build/generated` by `scripts/vendor.sh`.
- **re2j-jemmix / fix levels / upstreamable unit** — the patched oracle
  fork; jars build at cumulative fix levels 1-3; each patch is a
  self-contained upstream candidate.
- **pinned table modules / Tables0..N / CHUNK_BUDGET** — the generated
  `:unicode:v6_0` / `:unicode:v17_0` providers: fold orbits frozen at the
  UCD snapshot ("folding cannot float with the runtime JDK"), tables split
  across holder classes so each `<clinit>` stays under the 64 KB method
  cap.
- **{lo, hi, stride} triples / fold pairs** — the re2j-style compressed
  range encoding and the flat orbit edge list the generated providers
  emit.

## Website

- **lab / island / mini-library / faithful miniature** — the explainer
  site's interactive playgrounds (Astro islands: BacktrackingLab,
  AstLab, PikeVmLab, AlphabetLab, SubsetLab, RegisterLab, SearchLab) and
  the `src/lib/*.ts` ports of the engine they run.
- **iterative descent** — the parser chapter's pun on recursive descent:
  loops + an explicit `GroupFrame` stack, no JVM recursion ("nesting is
  first-class").
- **table walk / free move edges** — the site's lay phrasings for the
  match loop and ε-edges.
- **quest** — the `?` operator's nickname (as in "a quest around a plus",
  the nullable-star shape).
- **minterms** *(borrowed: automata literature)* — the site's name for
  breakpoint cells.

## Conventions worth knowing

- **fails loudly / never a silent ... / rejected loudly** — the standing
  design stance: typed exceptions that name the culprit; no silent
  fallback, spill, no-op, or degradation ("We never silently fall back to
  a slower engine").
- **byte-identical / bit-identical artifacts** — same pattern + flags ⇒
  identical tables across runs, tiers, and lanes (the deterministic-
  compilation gate hashes them via `ArtifactFingerprint`).
- **floor (Java 8 floor / floor policy / floor modules)** — the minimum
  bytecode level: core, asm, facade, re2j ship Java 8 (proved by real-JDK-8
  builds and Smoke8); pikesim and the unicode modules ride the Java 25
  floor.
- **leg** — one parallel matrix instance of a CI job ("a 5-leg matrix").
- **rides** — piggybacks on: a task "rides `check`", tables "ride the wide
  dispatch index", the observer "rides the Tnfa".
