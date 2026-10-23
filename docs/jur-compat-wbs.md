# JUR-compat default — work breakdown

Open work only, [TODO](../TODO.md)-style one-liners; when every box is
checked the JUR-parity default is fully evidenced and this page retires.
Items 0–5 — boilerplate, the finding-1 prerequisite, pivot parameterization,
fold + U bits, family 6, the flip — are landed; their essays and round logs
live in git history (this file before 2026-10-11). The
[design](jur-compat-default.md) is the full accounting (families, flags,
consequences, decided open questions).

## 6. Evidence re-base

- [x] Per-flag unit tests from the campaign replay seeds (`PerFlagReplayTest` — the finding-2 representative seeds + a deterministic per-axis sweep, every flag setting, jur facade both tiers vs the live oracle; divergences allowed only inside the asserted domain boundary)
- [x] jur campaign classifiers flip from soft-known to hard assertions (each residual — group-participation-tail, İ/ı full-fold, lone-surrogate — fixed or consciously reclassified, none left soft by default) — **İ/ı FIXED** (the fold universe rides the surface default: the jur surface's JDK provider folds the java.util.regex reading — İ/ı merged into the i-orbit under full fold — while the re2j surface pins the built-in RE2-lineage reading, so each surface folds its oracle's orbits); **tail + surrogate reclassified** as conscious domain exclusions (SKIP kinds, not KNOWN divergences: `SKIP_GROUP_PARTICIPATION_TAIL` — the 4d ε-write engine work: CLOSED by the unbounded zero-width-exit split (the redirect IS the position-carrying ε-write; the skip retired); `SKIP_SURROGATE_UNIT_SEMANTICS` — the lane's designed unit semantics vs java's pair-aware discipline, covering lone surrogates, pair-interior spans, surrogate-covering classes incl. `[a-b]` ranges and the `\b`/`\B` codepoint-wordness twin; a pair-aware JUR reading stays open; the successor tail-shaped skip `SKIP_NESTED_LOOP_CAPTURE` — java's nested-loop inner-capture quirk (`((a)*b)*` restores the FIRST iteration's inner group) — RETIRED by the sticky split (the GroupCurly backoff's outermost-frame-last rewrite: the first-writing iteration's span, cloned into the automaton; pinned in `NestedQuantifierParityTest`)). `CampaignSmokeTest` asserts the enumerated soft-kind allowlist — no soft kind can appear silently, and the campaign's compile catch-all no longer swallows non-budget exceptions (the flip surfaced and fixed a real `cutOverrideByState` AIOOBE, pinned in `CutSurfacingStateGrowthTest`)
- [x] `DifferentialFuzzer` matrix gains the `RE2_COMPAT` lane (lane becomes a matrix axis beside the flag bits; the jur side needs the campaign's flag translation) — post-split the axis is re2j-surface-vs-re2j beside jur-facade-vs-`java.util.regex`, drawn per batch from its own stream (patterns/flags replay-stable), pin via `-Pfuzz.lane=re2j|jur`
- [x] `(?u)`/UCC flag in the differential matrix vs the jur oracle — the [TODO](../TODO.md) "Fuzz (?u) lane vs jur oracle" item joins this program (the jur lane's flag draw: CI/DOTALL/MULTILINE/UCC, no LONGEST; leading `(?U)` → the oracle's UNICODE_CASE)
- [x] Re-run the re2j-pinned evidence — exhaustive part: **5,826,402 RE2-exhaustive cases, 0 failures, both tiers** (ASM + VM), through the re2j surface (the RE2 lane) post-split and post-fold-fix; dev slices: ~568k mixed-lane cases and ~643k jur-pinned cases with zero divergences outside the asserted skips + the documented budget class
- [ ] Re-run the ~300 M fuzz leg in the re2j lane + jur leg vs the default lane — overnight soak, both lanes (`scripts/fuzz-soak.sh` mixes both by default; `-Pfuzz.lane=re2j`/`=jur` pin a lane-pure run), then the README numbers refresh

## 7. Parity suites vs both oracles

The re2j parity suites (`tests/parity/re2j`, the `*ParityTest` classes)
pin the `RE2_COMPAT` lane against re2j; post-flip that oracle monopoly is
historical. Widen every suite that translates to ALSO pin the default
(JUR) lane against the live `java.util.regex` oracle.

- [x] `JurOracle` beside `Re2jOracle`: default-lane facade compile + the
  bit↔JDK-flag translation (`CASE_INSENSITIVE`/`DOTALL`/`MULTILINE`/
  `UNIX_LINES`/`UNICODE_CASE`/`UNICODE_CHARACTER_CLASS` map — the last two
  ride the `CompileOptions.semantics` route, ASM-only;
  `LONGEST_MATCH`, `MULTI_VALUED_TAGS`, `FIND_ONLY`, `DISABLE_UNICODE_GROUPS`
  and the RE2-only spellings — ungreedy `(?U:` — have no jur reading and
  reject in the translation) — **landed (2026-10-15)**
- [x] Adopt the jur twin per suite as appropriate: translatable cases run
  both lanes on both engine factories (ASM + VM); re2j-only shapes (POSIX
  longest, ungreedy `(?U`, re2j-specific accept/reject) document themselves
  RE2-lane-pinned — **landed (2026-10-15)**: twins in 18 suites
  (`JurOracle` `jurTwin*` methods); RE2-lane-pinned by construction:
  `LongestMatchParityTest`, `MultiValuedParityTest`, `TestregexFowlerTest`
  (documented in their javadocs); `JdkRegressionCorpusTest` already layers
  both oracles; accept/reject divergences pinned both ways
  (`assertDivergentReject`/`assertDivergentAccept`: JDK-only syntax we
  reject — backrefs, lookarounds, possessives, `\R`/`\e`; our RE2-lineage
  spellings the JDK rejects — `(?P<>)`, POSIX brackets, bare script names,
  `\p{Any}`/`\p{^X}`, digit-first group names, `\0`/`\12`-style octals,
  lenient dangling-`$` replacements, all-delimiter splits)
- [x] Group-protocol comparison on the jur side carries group clauses (the
  family-6 battery's live-JDK re-verification is the shape — no skeleton
  stripping) — **landed (2026-10-15)**: `JurOracle` spans + the
  `SemanticsContract`/`FinalOps`/`ZeroWidthExhaustive` jur protocols carry
  every group clause

Findings the widening surfaced (fixed here): the `\s`-vs-`\u000B` cell
silently kept re2j's table under the JUR default — now the `SPACE_RE2`
axis (design doc's flags table; re2j surface sets it, jur default mirrors
the JDK); and a `TdfaCompiler.solveFinalOps` crash (cut-override table
sized before late kernels — `(.{0,5}){3,}`), unreachable on the RE2 lane
because the family-6 marks never fire there. Residual twins exclude and
document: the campaign's known soft list (group-participation-tail —
incl. `(X)*tail` and the nested-loop inner-capture quirk — İ/ı
full-fold, lone-surrogate scans), which item 6 owns.

## 8. One `Pattern` or two — decide before the API lock

The flip put two lineages on one surface: the default is JUR parity, the
re2j reading rides the seven opt-out bits / the `RE2_COMPAT` migration OR.
The alternative shape: jur-compat `Pattern` and re2j-compat `Pattern` as
SEPARATE surfaces, each owning its default outright (no migration OR, no
opt-out ceremony; open questions: what happens to the seven public bits,
the `CompileOptions.semantics` route, japicmp surface cost, README/javadoc
narrative).

- [x] Evaluate and decide — **decided: two surfaces, landed (2026-10-13)**
  (the jur facade keeps the root module; the re2j reading ships from its own
  `re2j/` module, the seven bits + `RE2_COMPAT` preset are gone from the
  public API, the machinery lives in `core.compile` behind a two-method emit
  SPI). The split's design + work breakdown pages are retired (landed); the
  `surface-split/*` squash commits carry the design accounting and the
  closed open decisions (re2j flag roster, shared matcher typing,
  split-before-namespace-move sequencing)
