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

## 1. Prerequisite — 0.5 d

- [ ] Fix finding 1: CharSequence scan pair-interior skip in `runGeneric` (the `Alphabet.pairInterior` guard) — a live default-lane bug until the flip, a `CODEPOINT_BOUNDARIES`-lane obligation after

## 2. Parameterize the pivots — 3–5 d

Both behaviors selectable, **default unchanged**; audit every rung × tier × input type.

- [ ] Terminator set: `positionFlags`/`positionFlagsCS` (`TdfaRunner`) + parser `DOT` set (`Parser`) select full JUR set vs `\n`-only, gated on the `UNIX_LINES` axis
- [ ] Anchor EOL rules: caret-after-final-newline (`EMPTY_LAST_LINE`) and dollar-before-final-newline (`END_OF_TEXT_ONLY`) in the same `positionFlags*`; decide the split-vs-`RE2_LINE_ANCHORS`-bundle open question
- [ ] Per-unit vs codepoint wordness + scan gating: `isWordBefore`/`isWordAt`, `Alphabet.pairInterior` gating (~10 `TdfaRunner` sites, `RunnerTables.needleEndOverlapsPair`, ASM `INVOKESTATIC` helper) on the `CODEPOINT_BOUNDARIES` axis
- [ ] Thread `Semantics` into `Parser.parseResult` (the parser-side pivots need it: `DOT` set, fold universe, `(?U`)
- [ ] ASM tier: emit-time specialization per axis (the `genPositionFlagsC(..., boolean, boolean)` template) for every pivot the interpreter gained
- [ ] Campaign probes both lanes while the shipped default stays RE2

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
