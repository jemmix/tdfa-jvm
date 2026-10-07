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

## 3. Fold + U bits — 1 d

- [ ] `UNICODE_CASE`: ASCII fold universe at the `Parser.foldUniverse` seam; bare CI folds ASCII, CI|UNICODE_CASE folds full Unicode
- [ ] `UNIX_LINES` bit activates the terminator-set pivot of item 2
- [ ] `UNGREEDY_U`: `(?U)`/`(?U:...)` repurpose to scoped Unicode-case; group-scoped use rejects with `PatternSyntaxException` (v1 top-level-only); retire the campaign's `SKIP_UNGREEDY` skip lane
- [ ] `CompileOptions` withers (or a `semantics(Semantics)` wither) for the core-tier spelling

## 4. Family 6 — 2–5 d

- [ ] φ-finals variant suppressing zero-width final-iteration spans (`applyFinalOps`, `TdfaFinalVariants`), mode-gated on the `EMPTY_ITERATION_SPANS` axis so the RE2 lane is untouched — the one research-y item

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
