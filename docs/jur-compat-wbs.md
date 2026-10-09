# JUR-compat default — work breakdown

Open work only; when every box is checked the default is flipped and the
evidence re-based. The [design](jur-compat-default.md) is the full
accounting (families, flags, consequences, open decisions); this page
tracks the build order in [TODO](../TODO.md) format — completed items
live in git history.

## 0. Boilerplate — landed

- [x] Flag surface: the seven opt-out bits (`UNIX_LINES` … `UNGREEDY_U`) + `RE2_COMPAT` preset on `Pattern`, whitelisted in `PatternCompiler` — pre-flip accepted no-ops (the current default already is the set-side behavior on every axis)
- [x] `Semantics` carrier (core.tnfa): rides the `Tnfa` beside `multiValuedTags`, freezes onto `Tdfa` (`semantics()`), copied into `TdfaRunner` — one read site per tier for the pivot gating
- [x] Pre-flip wiring: every facade compile runs the every-axis-set value (the RE2 lane — `RE2_LANE`, composed from the withers; the type keeps no lineage-named constant); the flip is replacing that one line in `PatternCompiler` with the user-bit mapping
- [x] No-op equivalence pinned per axis + preset across both engine tiers (`Re2CompatFlagsTest`); unknown-flag parity probe moved off 0x100 (now `UNIX_LINES`)

## 1. Prerequisite — landed

- [x] Fix finding 1: CharSequence scan pair-interior skip in `runGeneric` (the `Alphabet.pairInterior` guard) — a live default-lane bug until the flip, a `CODEPOINT_BOUNDARIES`-lane obligation after (`CharSequencePairInteriorTest`; the campaign's `SEQ_KNOWN_PAIR_INTERIOR_SCAN` soft lane retired — seq mismatches are hard again)

## 2. Parameterize the pivots — landed

Both behaviors selectable, **default unchanged** (every facade compile still
runs the RE2 lane; RE2-lane artifacts are bit-identical to pre-item builds);
audited every rung × tier × input type (`PivotLanesTest`).

- [x] Terminator set: `positionFlags`/`positionFlagsCS` (`TdfaRunner`) select
  full JUR set vs `\n`-only on the `UNIX_LINES` axis; parser `DOT` set the
  same way — `Semantics` reaches the parser as a projection
  (`ParseOptions.dotNlOnly`, built in `Tnfa.compile`; the parser package
  stays below `tnfa` in the layer DAG, so the carrier itself cannot be the
  parameter). JDK-exact details verified against a live java.util.regex:
  `(?m)^` never matches at end of input — not even position 0 of an empty
  input — and `(?m)$`/plain `$` exclude `\r\n` interiors (the `\r` position
  owns the run).
- [x] Anchor EOL rules: `EMPTY_LAST_LINE` (caret-after-final-newline) in
  `BEGIN_TEXT`; `END_OF_TEXT_ONLY` (dollar-before-final-newline = java's
  `\Z`) as a NEW position bit `Tnfa.FINAL_END` — plain `$` lowers to
  `FINAL_END` when the axis is unset, to `ABS_END` (today's reading) when
  set, so the RE2 lane never emits the bit. The posFlags-indexed tables
  (stop, φ-by-mask, minimizer signatures, ASM `TABLESWITCH`) widened to a
  per-artifact stride (`Tdfa.posFlagCells()`: 64, or 128 on JUR-`$`
  compiles) — one decision in the determinizer, every reader takes it from
  the value chain. **Open question decided: keep the split** (see the
  design's open decisions).
- [x] Wordness + scan gating on `CODEPOINT_BOUNDARIES`: the ~10
  `pairInterior` start gates (candidate scans, restart loops, `runGeneric`
  reseeds, `RunnerTables.literalIndexOf`, the prefix hooks) select on the
  axis; `prefixHitUsable` gained a unit-lane twin (`prefixHitUsableUnit` —
  ASM 9.10.1's frame computation rejects the boolean-arg descriptor). The
  sim/trigger rungs (origin sim, search-DFA trigger) step codepoints, so
  under unit semantics BOTH tiers' ladders skip them for the complete
  restart floor. `isWordBefore`/`isWordAt` keep their decode gate
  (`unicodeWordBoundary`) — JDK-verified: plain `\b` is per-unit,
  UCC `\b` decodes pairs, in BOTH lanes; the axis moves start positions,
  not the word predicate. `needleEndOverlapsPair` is a walk-decode
  property and stays unconditional.
- [x] `Semantics` threaded into the parse (the `ParseOptions` projection
  above); fold universe and `(?U` join at item 3 through the same seam
- [x] ASM tier: emit-time specialization per axis (`PfAxes` + the shared
  bit emitters behind `genPositionFlagsC`/`emitPFInline`; FINAL_END,
  full-set terminator checks, `\r\n` interiors, cand-scan guard, prefix
  hook selection, rung-2 gating, cell stride) — default-lane bytecode
  unchanged
- [x] Campaign probes both lanes (`CampaignFuzzer` jur mode: the RE2-lane
  case unchanged; a JUR-lane case compiles `Semantics.of()` at the core
  tier and checks VM==ASM plus the live java.util.regex oracle, with the
  residual surrogate/fold families soft and everything else hard)

## 3. Fold + U bits — landed

Both parser-side axes selectable at the fold and `(?U)` seams, **default
unchanged** (every facade compile still runs the RE2 lane; the legacy parse
overloads keep it bit-for-bit); audited in `PivotLanesTest` (JUR lane vs
the live java.util.regex oracle, RE2 lane vs the facade, the axis-single
fold lane, the v1 rejections, the facade options route).

- [x] `UNICODE_CASE`: ASCII fold universe at the `Parser.foldUniverse` seam
  (`CaseFoldTable.asciiFoldRanges`) — bare CI folds the 26 letter pairs
  and nothing else (JDK-verified against a live java.util.regex: ſ,
  K U+212A and the Turkic İ/ı pair are all inert under bare CI — İ/ı
  agree lane-vs-oracle there, the merge needs UNICODE_CASE); the axis set
  (or a scoped `(?U)` upgrade) folds full — the provider's own universe
  when it supplies one. JDK finding (the campaign's JUR lane caught it as
  10 hard mismatches before the fix): `UNICODE_CHARACTER_CLASS` IMPLIES
  Unicode-aware CI — java under CI|UCC folds the full universe exactly
  like CI|UNICODE_CASE — so tdfa's `(?u)` widens the fold universe the
  same way (group-scoped like the flag itself). The `\p{...}` fold twin
  (`foldTableFor`) is a different seam and stays universe-full in both
  lanes: the JDK folds property classes under bare CI already. The
  campaign's JUR-lane oracle compares under plain CASE_INSENSITIVE now
  (the bare-CI universes agree by construction; UCC lanes fold full on
  both sides, with the İ/ı full-universe residual still soft).
- [x] `UNIX_LINES` bit activates the terminator-set pivot of item 2 through
  the same route — `CompileOptions.semantics(...unixLines())`, the
  facade's options entry; the int-flag mapping of the axes remains the
  flip's one-line change.
- [x] `UNGREEDY_U`: `(?U)`/`(?U:...)` pivot on the axis — set keeps the
  ungreedy reading (scoped, save/restore, unchanged); unset repurposes
  `(?U)` to scoped Unicode-case, a point-forward fold-universe upgrade
  (v1 top-level-only: the colon form, a negated `(?-U)` and any in-group
  use reject with `PatternSyntaxException` — per-scope fold universes are
  the v2 work). The campaign's JUR-lane `SKIP_UNGREEDY` skip lane retired:
  `(?U:...)` shapes reject by design and count soft, a leading `(?U)`
  translates to the oracle's UNICODE_CASE flag; the RE2-lane probe keeps
  its skip (the facade reads `(?U:...)` as ungreedy, untranslatable to
  java until the flip).
- [x] `CompileOptions` `semantics(Semantics)` wither for the core-tier
  spelling — honored by `CompiledRegex` and the facade's
  `Pattern.compile(regex, CompileOptions)` (the one pre-flip way a facade
  compile leaves the RE2 lane).

## 4. Family 6 — 2.5–4.5 d, four landable sub-items

The research rule (one battery, all three engines run against it: live
JDK 26, stock re2j 1.8, the current engine) is now the design doc's
family-6 mechanics note
([mechanics](jur-compat-default.md#family-6-mechanics-the-groupcurlyloop-split)):
java compiles a quantified group TWO ways (`GroupCurly` for
`study()`-deterministic bodies — greedy zero-width iterations roll back
past `cmin` to the pre-curly bounds; `Prolog`+`Loop` for everything
else — every completed iteration reports, a final zero-width one
included), so the axis is two sub-families of opposite polarity, both
protocol-only (skeletons already agree; re2j and the engine answered
identically on the whole battery, and lazy quantifiers agree with JUR
everywhere):

- **A — suppress** (greedy min-0 quantifier directly on a capture whose
  body is all-zero-width; alternation defeats the shape): JUR −1, we
  report the sole zero-width span. Statically recognizable at the
  parse; the φ fix is a SET_NIL override for that group's tags.
- **B — surface** (greedy quantifier directly on a capture whose body
  can match empty but is NOT deterministic): JUR reports the final
  zero-width iteration, we report the earlier maximal iteration because
  the zero-width re-entry dies in the closure's same-position
  subsumption — its tag writes must surface as a φ SET_POS at the
  accept position (emptiness gated on assertions, e.g. `a?\b`, rides
  the posFlags-mask variant machinery; consuming-only emptiness is
  uniform).

- [x] 4a — spec + corpus — landed: the mechanics note (sub-families,
  both fix shapes, the decided interactions) lives in the design doc;
  the `Semantics.emptyIterationSpans` javadoc describes both
  sub-families; the battery is `family6-battery.tsv` (51 shapes — both
  sub-families × greedy/lazy/`?`/`+`/`{m,n}` × named groups × nested
  composition `((a|\b)*)*` — JUR column re-verified against the live
  JDK on every run, RE2 column pinned on both tiers =
  `Family6BatteryTest`, JUR-lane assertions dark per sub-family until
  4b/4c light them). Interactions decided: under `MULTI_VALUED_TAGS`
  a surfaced final iteration (B) appends a participation and a
  suppressed one (A) appends nothing; under `LONGEST_MATCH` the
  protocol applies to the reported accept, no separate interplay.
- [x] 4b — sub-family A: suppression — landed: the parser marks the
  dissolved pair at `closeGroup` (greedy min-0 loop, `cmax >= 2` or
  unbounded — `{0,1}`/`?` and `min >= 1` keep, both JDK-verified —
  directly on a CAPTURE, so `(?:(\b))*` keeps the span; body
  all-zero-width with no choice points, so `(\b|)*`/`(a?)*` are B) via
  the new `ParseOptions.emptyIterationSpans` projection; the marks ride
  the `Tnfa` (`dissolvedTags`) and `TdfaFinalVariants.finalRegopsOf`
  overrides both φ branches (single: unconditional SET_NIL; multi: the
  empty tree head — no participation, per the 4a decision); the
  fixed-tags pass keeps dissolved tags un-fixed and base-free
  (`((\b))*`: the inner capture reconstructs from ITS close, not the
  dissolved outer's); RE2 lane parses/artifacts untouched (null marks,
  bit-identical — the re2j parity suite and the battery's RE2 column
  re-pin); VM + ASM tiers; 4a's A-assertions LIVE (battery grown to 41
  shapes: `(\b)*a`, `a(\b)*`, `(\b)*|a`, `(\b)*(a)`, `(?m)(^)*`,
  `(())*`, `((\b)*)*` composition) + the 4a interaction pins (multi ×
  inner-capture spans, longest).
- [x] 4c — sub-family B: surface the final iteration — landed: the
  parser B-marks an UNBOUNDED greedy quantifier directly on a capture
  whose body is nullable through a choice point (`*`, `+`, `{n,}` —
  bounded desugars agree already; lazy never marks), marks extending
  to every capture INSIDE the body (containment: `((a*))*` on "aa"
  reports BOTH groups 2..2); the determinizer records, per closure,
  every B-marked tag on a dying zero-width re-entry (the subsumption
  cut OR the exact (state,mask) revisit — the death can fire anywhere
  on the cycle, so the edge tag AND the dying path's lookahead history
  are both consulted, pairs normalized to the close tag, assertion
  masks accumulated) and binds them to the (deduped) target state;
  `TdfaFinalVariants` overrides the finals per posFlags mask (single:
  SET_POS at the accept position; multi: one appended participation —
  the 4a decisions) — mask-dependent cuts force the variant table
  even on winner-uniform states; minimizer signatures need no change
  (the overrides live IN the ops content). RE2 lane: null marks, no
  recording, bit-identical artifacts (re2j parity suite + battery RE2
  column re-pinned; a 90 s jur campaign slice: 78,444 cases, 0 hard
  findings). 4a's B-assertions LIVE (battery at 51 shapes — `+`,
  `{n,}`/`{2,}` unbounded-tail rows, nested composition, `(x(y|))*`
  and `(xy*)*` non-nullable-body agrees) + the B interaction pins
  (multi appends the participation, longest composes).
- [ ] 4d — audit + evidence (0.5–1 d): rung × tier × input-type audit
  (the `PivotLanesTest` battery pattern); the campaign's jur lane stops
  stripping group clauses (probe + oracle sides) and A/B flip to hard
  vs the java oracle; re2j-lane slice re-run (RE2 protocol unchanged);
  meter/budget accounting for the closure analysis;
  `PivotLanesTest`'s "not pinned here" note removed.

## 5. The flip — 1 d

- [ ] One-line default change in `PatternCompiler` (`RE2_LANE` → the user-bit mapping); pivot defaults flip with it
- [ ] Javadoc rewrite: drop the pre-flip axes note; README names the new default contract
- [ ] README / `BENCHMARKS.md` re-baseline; decide the `JUR_COMPAT = 0` sugar constant (open decision)

## 6. Evidence re-base — 1.5–2 d

- [ ] Per-flag unit tests from the campaign replay seeds
- [ ] jur campaign classifiers flip from soft-known to hard assertions
- [ ] `DifferentialFuzzer` matrix gains the `RE2_COMPAT` lane
- [ ] Re-run the re2j-pinned evidence (5.7 M exhaustive, ~300 M fuzz) in the `RE2_COMPAT` lane
- [ ] Overnight soak + README numbers refresh
