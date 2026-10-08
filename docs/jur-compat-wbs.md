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

Provisional research pinned the rule (one battery, all three engines run
against it: live JDK 26, stock re2j 1.8, the current engine): java
compiles a quantified group TWO ways and the two keep different
zero-width-iteration protocols. Bodies `study()` calls deterministic
(assertion chains, `()`, fixed-consuming) run as `GroupCurly`, whose
greedy path rolls a zero-width iteration past `cmin` BACK to the
pre-curly bounds (the JDK's `Pattern.java`, `GroupCurly.match0`: the
`k <= 0` break restores the saved pair — `(\b)*` → g1 −1,
`(\b)+`/`(\b){2}` keep the forced iteration); everything else
(alternation, optional, variable quantifier anywhere in the body) runs
as `Prolog`+`Loop`, which reports
every completed iteration INCLUDING a final zero-width one (`(a*)*` on
`"aa"` → g1 2..2 — java keeps the empty last iteration). re2j and the
current engine answered identically on the whole battery (0 diffs —
the RE2 side is what the soak already pins), and lazy quantifiers agree
with JUR everywhere (both prefer zero iterations: `(\z)*?` → −1,
`(a*)*?` → −1). So the axis is two sub-families of opposite polarity,
both protocol-only (skeletons already agree — the campaign classified
only group-clause differences):

- **A — suppress** (greedy min-0 quantifier directly on a capture whose
  body is all-zero-width; alternation defeats the shape — `(\b|)` keeps
  the span, `(?:(\b))*` too, only the quantified capture itself
  dissolves): `(\z)*`, `(\b)*`, `()*`, `(\b\b)*`, `(\b){0,2}` — JUR −1,
  we report the sole zero-width span (0..0). Statically recognizable at
  the parse; the φ fix is a SET_NIL override for that group's tags.
- **B — surface** (greedy quantifier directly on a capture whose body
  can match empty but is NOT deterministic): `(a*)*`, `(a?)*`,
  `(a|\b)+`, `(a{0,2})*`, `(a*\b)*`, the outer group of `((a)*)*`,
  `(\b)(a|\b)*` g2 — JUR reports the final zero-width iteration
  (`(a|\b)*` on `"aa"` → 2..2); we report the earlier maximal iteration
  (1..2) because the zero-width re-entry dies in the closure's
  same-position subsumption — its tag writes must surface as a φ
  SET_POS at the accept position (emptiness gated on assertions, e.g.
  `a?\b`, rides the posFlags-mask variant machinery; consuming-only
  emptiness is uniform).

- [ ] 4a — spec + corpus (0.5 d): expand the research summary above
  into the design doc's family-6 mechanics note (the flag row and
  critical-path bullet are corrected already); correct the
  `Semantics.emptyIterationSpans` javadoc (it, too, describes
  sub-family A only); land the battery as
  replayable expected-value data verified against the live JDK (both
  sub-families × greedy/lazy/`?`/`+`/`{m,n}` × named groups × nested
  composition `((a|\b)*)*`), assertions dark until 4b/4c light them;
  decide the two documented interactions — the axis under
  `MULTI_VALUED_TAGS` (does a surfaced zero-width final iteration
  append a participation) and under `LONGEST_MATCH` (defined or
  documented no-op).
- [ ] 4b — sub-family A: suppression (0.5–1 d): static shape mark at
  the parse/TNFA build + φ override to SET_NIL for the dissolved
  group's tags on the JUR lane (`TdfaFinalVariants.finalRegopsOf` /
  the variant tables); RE2-lane artifacts bit-identical; VM + ASM
  tiers; 4a's A-assertions on.
- [ ] 4c — sub-family B: surface the final iteration (1–2 d, the
  determinizer item): detect the cut zero-width re-entry per accepting
  kernel and emit a φ SET_POS-at-accept override for the quantified
  group's tags — thread through `TdfaFinalVariants`, materialization
  (`stateFinalOpsByMask`), minimizer signatures (states differing only
  in the override must not merge), ASM emission, `applyFinalOps`
  ordering; 4a's B-assertions on.
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
