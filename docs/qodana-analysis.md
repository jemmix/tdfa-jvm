# Qodana analysis — first real scan, classification, and the gate decision

Date: 2026-10-29 · linter `qodana-jvm-community` 2026.2 (native mode, no
Docker) · profile `qodana.recommended` · scope: the four own-code modules
(`core`, `asm`, `re2j`, root facade `src/` — 83 files, ~25.7 k LOC).

This is the evidence base behind the C35 owner call ([complexity
review](complexity-review.md) → decision 5 in the [decision
queue](complexity-decisions.md)): **keep Qodana, gated on javadoc
integrity only** (`.github/workflows/qodana.yml`, config
`qodana-ci.yaml`). The wider-gate question (baseline the rest vs fix vs
decline) stays open below.

## What the first honest run found

**139 findings** (133 High, 6 Moderate): core 132, asm 7, facade/re2j 0.
Artifact: `build/qodana-result/qodana.sarif.json` after `qodana scan`.

Two premises died on contact:

- *"Qodana has zero findings"* (complexity review, C35 evidence) — it had
  never actually been run; the properly-scoped first run found 139.
- *The config scoped the scan to own code* — it did not. The legacy
  top-level `include:`/`exclude:` blocks in `qodana.yaml` are **silently
  ignored** by Qodana 2026.x; the first run leaked 40 findings in from
  `benchmarks/tests/testlib/lib/unicode`. Scoping lives under
  `profile.inspections[].ignore` now (`dir/**` globs — bare `dir` does
  not recurse). Fixed and verified: same tree, 179 → 139.

## Classification (all 139, categories spot-verified in source)

### Correctness wins — 43

| What | Count | Verdict |
|---|---|---|
| `JavadocReference` | 40 | Real defects: broken `{@link}`s to symbols deleted/renamed in the budget and immutability cleanups (`Budgets` → deleted `walkMaxBytes`/`sdfaMaxBlocks`/`sdfaMaxRows`; `Tdfa#compileUnpruned` → moved to `Determinizer`; private members linked cross-class). **All 40 fixed** and now gated in CI |
| "Variable already assigned" ×2 + redundant init ×1 | 3 | Vestige smells, audited **benign**: `TdfaFinalVariants:377`'s `prev = dst` never differs from `dst` (dead machinery, the name lies); `Tnfa:956` re-loads an unchanged frame value; `Parser:209` dead `false` init. Optional 5-min cleanups, not bugs |

**Zero verified runtime bugs.** No finding in the set changes behavior if
left alone.

### Performance wins — 0

The one candidate (`TdfaCompiler:2069` "add break") is style-only — the
loop already early-exits via its `&& !blocked` condition. `Math.max`
rewrites are JIT-neutral. The perf relevance of this scan runs the other
way: the "make it worse" bucket below, if applied, **adds branches to
benchmark-pinned match loops**.

### Code-beauty wins — 46

- `UnnecessaryLocalVariable` 23 — safe inlining; **21 are in
  `TdfaRunner`**, so batch them in one no-behavior-change pass + full
  benchmark gate (house rule), or leave.
- `UnnecessaryUnicodeEscape` 14 — mechanically safe (UTF-8 pinned) but
  flips an implicit ASCII-only-sources convention: policy call, declined
  for now.
- Genuine trivial wins: 2 unused imports, `Ast:46` redundant
  `toString()`, `TdfaCompiler:782` (verified: `singletonList` is already
  immutable — the `unmodifiableList` wrapper is dead weight), `TagTree`
  `Math.max`, 2 debug-string duplications, 1 unchecked warning (add the
  targeted `@SuppressWarnings` used at 11 existing sites), 1
  identical-branches in asm codegen.

### Would make the code worse — 50

| What | Count | Why worse (verified) |
|---|---|---|
| `ConstantValue` "always true" | 16 | 12 are the `posFlags = -1` lazy-memo lanes + cross-lane defensive checks (`TdfaRunner:445`) in benchmark-pinned match loops; removal couples code to analyzer proofs — the exact churn class `build.gradle` bans for ErrorProne |
| `DataFlowIssue` NPE-guards | 17 | Pipeline-owned arrays (same trusted-tier posture as the documented SpotBugs `EI_EXPOSE` exclusions) + stateful invariants (`Parser:300` peek-after-push). Null-checks add hot-path branches for states that cannot occur |
| `TrivialIf` 3 + `DoubleNegation` 2 | 5 | All in the tuned word-boundary logic (`TdfaRunner:3055/3082`) |
| `Tnfa` `instanceof` sites | (in the 16) | Java-8-canonical test-and-bind; the "fix" is the pattern-matching modernization the build deliberately disables |
| `ExtractMethodRecommender` | 3 | Mega-method shapes were adjudicated by the complexity review (59 items) |
| `FieldCanBeLocal` | 2 | `TdfaRunner` fields are deliberate engine state |
| `ConditionCoveredByFurtherCondition` | 3 | Unbounded-first guard ordering in quantifier handling is the readable form |
| `SuspiciousMethodCalls` | 1 | Sits under an existing ErrorProne suppression with a written rationale (`TdfaStateIndex:238`) |
| `CaughtExceptionImmediatelyRethrown` | 1 | **Load-bearing**: `SerialProxy:97` routes `InvalidObjectException` past the wrapping catch — removing it double-wraps |
| `SynchronizationOnLocalVariableOrMethodParameter` | 1 | **Deliberate**: the comment at `JdkUnicodeDataProvider:243` documents the fine-grained monitor design |
| `IfStatementMissingBreakInLoop` | 1 | Style only (see performance above) |

## Effort (if full compliance were ever wanted)

- Literal 139/139: **5–8 focused dev-days** + benchmark gates — and ~50
  of the "fixes" make the code worse. Not recommended.
- The landed middle path: 40 javadoc fixes (done, ~1 day incl. this
  analysis) + javadoc-only CI gate (done) — the only tier with no
  downside.
- Possible next steps, in value order: the handful of zero-risk beauty
  wins (~0.5 day, batch + bench); `compileOnly` JetBrains annotations +
  `@NotNullApi` package-infos to teach nullability (~0.5 day); baseline
  mode (`--baseline sarif`) to ratchet new code under the full profile
  without the archaeology.

## Verified configuration knobs (Qodana 2026.2, tried here)

| Mechanism | Where | Result |
|---|---|---|
| Disable an inspection project-wide | `profile.inspections[].inspection: X, enabled: false` | ✅ verified (ExtractMethodRecommender, FieldCanBeLocal) |
| Disable per path (hot-file carve-out) | `inspection: X, ignore: [path]` | ✅ verified (`ConstantValue`/`DataFlowIssue` on `TdfaRunner.java`; 139 → 121) |
| Narrow gate profile | `profile.name: empty` + one inspection enabled | ✅ verified (`qodana-ci.yaml`, what CI runs) |
| Per-inspection `severity:` downgrade | yaml | ❌ **silently inert** — accepted, changes nothing in the SARIF. Not a knob |
| In-code suppression | `// noinspection <Id>` / `@SuppressWarnings("<Id>")` | standard IDEA mechanism; matches the existing `@SuppressWarnings("CollectionIncompatibleType")` house style |
| Baseline ratchet | `--baseline <sarif>` | documented; unused so far |

`qodana.yaml` carries the full own-code policy (scope + the verified
harmful-check suppressions above); `qodana-ci.yaml` is the CI gate
(empty profile + `JavadocReference`, `--fail-threshold 0`). The Gradle
task `qodanaScan` wires the two (`build.gradle`).

## Teaching the analyzer null-safety (not yet adopted)

What works: `compileOnly 'org.jetbrains:annotations'` (CLASS retention,
not shipped, Java 8 fine) + `@NotNullApi` package-info defaults +
`@NotNull` fields/params + `@Contract`. That shrinks *future*
nullability noise — the analyzer stops asking "may be null?" where the
API contract already answers.

What annotations cannot fix: stateful invariants (peek-after-push), DCL
monitor patterns, pipeline-owned arrays proven by construction. Those
get `// noinspection DataFlowIssue` with a one-line why — or the per-path
ignore already in `qodana.yaml`.

## Open items

1. Widen the gate? Baseline the remaining ~100 non-javadoc findings and
   ratchet new code, or keep the javadoc-only gate until the beauty
   batch lands. (Owner call; this doc is the evidence.)
2. The beauty batch (23 inline-temps, 2 imports, wrapper, toString,
   Math.max, duplicates) — one no-behavior-change PR + benchmark gate.
3. Nullability annotations adoption (recipe above).
