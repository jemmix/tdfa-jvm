# Fuzz campaign runs — 2026-10-03

Timeboxed test runs (~1 hour total of fuzz activity) against the six
fuzzer-related TODO.md items. Runs executed at commit `f1a0225` (JDK
26.0.2, 8-core macOS); the harness landed rebased onto main at `073a37c`
(after the multi-valued-tags merge) — finding 1's minimal repro and all
smoke gates were re-verified after the rebase. Two new harnesses ship with
this document and back every number below:

- **`CampaignFuzzer`** (`tests/parity/re2j`, gradle task `:tests:parity:re2j:camp`)
  — four modes covering the TODO items the overnight soak does not:
  `jur` ((?u) lane vs `java.util.regex`), `stretch` (oracle + ASM on
  stretched inputs), `seq` (non-String CharSequence vs the PikeSim
  reference), `families` (fact-gated rung families). `CampaignSmokeTest`
  gates a fixed-seed slice of each mode in CI.
- **The existing `fuzz` task** with shrunk `-Pfuzz.maxWork` (budget-exhaustion
  paths) and plain (cross-rung soak datapoint).

Every harness record carries a `caseSeed`; each replays standalone (commands
below). Campaign runs scope compile work to the fuzzer's 8M-tick slice
exactly like the overnight soak.

## Summary of findings

| # | Severity | Finding |
|---|----------|---------|
| 1 | **Engine bug (CharSequence inputs only)** | `runGeneric`'s unanchored scan never skips surrogate-pair interiors: under non-String `CharSequence` inputs, matches can **start inside well-formed surrogate pairs** (lone-low runes matching the low half; `\B`/empty-capable patterns matching at interior unit positions; leftmost-first shifts for anything that can start anywhere). String paths and `PikeSim` (spec) both implement the skip. Both engine tiers affected — ASM delegates CharSequence to the same runner path. |
| 2 | **Known-family policy for the (?u)/jur lane** | Five jur-vs-RE2 semantic families diverge by design; the `jur` campaign now classifies all five (was: 27 unclassified mismatches in the first R4 pass, 0 after classification — every one decomposed into a family). Any (?u)-lane integration into `DifferentialFuzzer` needs this classifier. |
| 3 | Coverage note | The soak counts `BUDGET_REJECT` cases in its `failures` count and exits nonzero (1008/5.07M in R1, all that kind). Reading the exit code requires kind-level triage, not counts. |
| 4 | Coverage data (families) | Fact-gated rung routing: `anchored-fast` shapes are served by `ANCHORED_FAST` at the **engine tier only** — the facade's `matches()` routes through `matchWhole` (traces `ANCHORED`) and can never observe that rung. Literal/prefix/cand-scan gates fire at 66–93% on family shapes (misses = short needles / alternation-heavy prefixes falling through, oracle-correct). |
| 5 | Clean bill | (?u) lane vs jur: **0 unclassified divergences** in 542,679 cases. Stretched inputs (oracle+ASM+VM): **0 divergences** in 200,239 cases. CharSequence vs PikeSim outside finding 1: **0 divergences** (261,567 cases). Cross-rung soak: **0 real findings** in 5.07M cases. |

## The runs

| Run | Item (TODO.md) | Command sketch | Wall | Cases |
|-----|----------------|----------------|------|-------|
| R1 | Overnight soak w/ cross-rung (timeboxed) | `:tests:parity:re2j:fuzz -Pfuzz.minutes=20 -Pfuzz.seed=2026100301` | 20 m | 5,065,704 |
| R2 | Budget exhaustion | `… -Pfuzz.minutes=6 -Pfuzz.maxWork=200000 -Pfuzz.seed=2026100302` | 6 m | 1,576,192 |
| R3 | Budget exhaustion (deep) | `… -Pfuzz.minutes=4 -Pfuzz.maxWork=20000  -Pfuzz.seed=2026100303` | 4 m | 1,204,160 |
| R4a | (?u) lane vs jur | `:tests:parity:re2j:camp -Pcamp.mode=jur -Pcamp.seconds=450 …` | 7.5 m | 542,679 |
| R4b | Stretched inputs, oracle + ASM | `… -Pcamp.mode=stretch -Pcamp.seconds=450 …` | 7.5 m | 200,239 |
| R4c | CharSequence vs PikeSim | `… -Pcamp.mode=seq -Pcamp.seconds=450 …` | 7.5 m | 261,567 |
| R4d | Fact-gated rung families | `… -Pcamp.mode=families -Pcamp.seconds=450 …` | 7.5 m | 270,007 |

Primary campaign activity ≈ 60 minutes (R1–R3 sequential; R4 modes ran as
parallel single-threaded campaigns in one JVM, `-Pcamp.mode=all`). The
final R4a numbers come from a same-seed solo rerun after the last
known-family classifier landed (the parallel pass had already covered the
identical case-sequence prefix); that validation rerun plus harness
shakedown slices add ~10 minutes. CI-gate slices: `FuzzSmokeTest`,
`CampaignSmokeTest`.

## Finding 1 — GENERIC rung starts matches inside surrogate pairs

**Status**: fixed — the `runGeneric` restart advance now applies the
`Alphabet.pairInterior` skip at both of its sites (the entry-check
restart and the walk-failure restart); pinned by
`CharSequencePairInteriorTest` and the campaign's `seq` leg, whose
`SEQ_KNOWN_PAIR_INTERIOR_SCAN` soft lane is retired (wrapper-vs-String
mismatches are hard findings again).

**Severity**: correctness bug, CharSequence (non-String) inputs only.
**Surface**: `io.github.jemmix.tdfa.core.dfa.TdfaRunner#runGeneric` — the
restart loop advances `startSearch++` without the pair-interior skip that
the String scan paths and `PikeSim.find()` (the specification-of-record,
"skipping surrogate-pair interiors") both apply.

**Minimal repro** (facade, either engine tier):

```java
// String input: codepoint-boundary semantics — no match (correct; JDK agrees)
io.github.jemmix.tdfa.Pattern.compile("\uDC21").matcher("a\uD801\uDC21zz").find();              // false

// Same input as StringBuilder: GENERIC matches the LOW HALF of the 𐡡 pair
io.github.jemmix.tdfa.Pattern.compile("\uDC21").matcher(new StringBuilder("a\uD801\uDC21zz")).find(); // true, 2..3
```

Second manifestation (empty-capable patterns; from the campaign records):

```
pattern \B  input '𑰇Y𝔄@'   (chars: 𑰇=0,1  Y=2  𝔄=3,4  @=5)
String path:  I=[0..0 1..1 5..5 6..6]      // scan skips the 𝔄-pair interior at 4
CharSequence: I=[0..0 1..1 4..4 5..5 6..6] // GENERIC also matches at 4 — inside the pair
PikeSim:      I=[0..0 1..1 5..5 6..6]      // spec agrees with the String path
```

**Campaign evidence** (`camp -Pcamp.mode=seq`, kind
`SEQ_KNOWN_PAIR_INTERIOR_SCAN`): hundreds of records across three wrappers
(StringBuilder, CharBuffer, char[] view) and both engine tiers; in every
record vm == asm (shared path) and PikeSim == String path. Divergence
shapes seen: interior matches for lone-low runes and boundary assertions;
first-match shifts (wrapper finds an interior match earlier than the String
path's genuine match); iteration continuation into interiors.

This is the same semantic family as re2j's documented lone-low
pair-interior bug (the reason the patched oracle fork exists — see
`DifferentialFuzzer.knownDivergence`): the String paths were hardened to
codepoint-boundary semantics, the CharSequence fallback was not.

**Expected fix shape** (not applied in this round — engine change, needs
its own correctness+perf round): skip pair interiors when advancing
`startSearch`, mirroring `PikeSim.find()`:

```java
// runGeneric restart advance
startSearch++;
if (startSearch < to && isHigh(input.charAt(startSearch - 1)) && isLow(input.charAt(startSearch))) {
    startSearch++;   // never START inside a well-formed pair
}
```

(The explicit `from` must stay honored as-is — engine parity with
`match(input, from)`; only the scan advance skips.)

**Reproduce**:

```bash
./gradlew :tests:parity:re2j:camp -Pcamp.mode=seq -Pcamp.seconds=450 -Pcamp.seed=2026100304
./gradlew :tests:parity:re2j:camp -Pcamp.mode=seq -Pcamp.one=<caseSeed>   # any failures.ndjson line
# minimal one-record replay (pair-interior match, spec agrees with String path):
./gradlew :tests:parity:re2j:camp -Pcamp.mode=seq -Pcamp.one=308827754503170982
```

Until fixed, the harness classifies the family as
`SEQ_KNOWN_PAIR_INTERIOR_SCAN` — spec-backed, not shape-guessed: soft only
when PikeSim agrees with the String path AND the input contains a
well-formed pair. Any CharSequence divergence outside that shape remains a
hard finding (and `SEQ_SIM_MISMATCH` — pike vs engine on the wrapper — is
always hard).

## Finding 2 — (?u)/jur lane known-divergence families

The first R4 pass recorded 27 unclassified jur mismatches; all decomposed
into six semantic families (tdfa keeps the RE2-lineage contract the soak
pins against re2j; jur is a UTF-16 code-unit engine with Perl-lineage
anchors and a backtracking capture model). The final pass: 542,679 cases,
**0 unclassified divergences**, 2,330 classified records. The `jur`
campaign classifies all six (soft, recorded with full detail):

| family | trigger | semantics |
|--------|---------|-----------|
| surrogate-code-unit | lone surrogate in pattern/input | jur matches into pair interiors; tdfa keeps codepoint boundaries |
| line-terminator-set | input contains `\r` | jur treats `\r`/U+0085/U+2028/U+2029 as line terminators for `.`/`^`/`$`; RE2 lineage sees `\n` only |
| unit-boundary-positions | `\b`/`\B` + well-formed pair in input | jur evaluates word boundaries at every UTF-16 unit (including pair interiors); tdfa only at codepoint boundaries |
| caret-after-final-newline | MULTILINE + input ends `\n` + `^` | tdfa/re2j match the empty last line; jur does not |
| dollar-before-final-newline | no MULTILINE + input ends `\n` + `$` | jur matches before the final line terminator; RE2 lineage requires end-of-text |
| group-participation-lineage | quantified group, only group clauses differ (skeleton-equal protocols) | `(\z)*` on `""`: jur reports g1 non-participating; re2j, PikeSim AND tdfa all report the final empty iteration's span (verified against all three) |

Counts in the final 542k-case jur pass: surrogate 2114, line-terminator-set
104, unit-boundary-positions 49, caret-after-final-newline 40,
dollar-before-final-newline 23. Skips (not divergences): CI fold-ambiguous
149,331 (jur's own CI vs CI\|UNICODE_CASE answers differ — the universes
legitimately differ there), untranslatable `(?U:` ungreedy groups 38,413,
budget 584.

Representative records (replay via `-Pcamp.mode=jur -Pcamp.one=<seed>`):
`\B` on `Z💩💩𐐂a` (jur iterates interior unit positions, seed
`263695807358897136`); `^` (?m) on `"\n-İa\n"` (seed `349139821284141069`);
`.$` on `'€ ᲅ漢т𐐡\n'` (seed `553574611371856328`); `(\z)*` on `""` (seed
`964326838431220311`).

Any future wiring of the UCC flag into `DifferentialFuzzer`'s matrix needs
this classifier (plus the `(?U:`-group skip and the CI fold-ambiguity dual
probe already implemented in `genJur`/`jurCase`).

## Finding 3 — soak exit codes count budget rejections

R1 (default 8M-tick fuzz scope) exited nonzero with 1008 `failures`, all
kind `BUDGET_REJECT` (nested counted-quantifier bombs rejecting under the
scoped compile budget — the documented contract; see
`DifferentialFuzzer.fuzzWorkBudget`). Zero `RESULT_MISMATCH`,
`COMPILE_PARITY`, `HANG_*`, `CROSS_RUNG` or `CROSS_ROUTING` records.
Automation reading the soak's exit code must triage by record kind;
counting alone overstates findings by ~0.02% of cases. No change proposed —
this note exists so the next soak reader doesn't misread the exit code.

## Finding 4 — fact-gated rung routing (coverage data)

R4d routing histogram (`families` mode; `family -> serving rung` from the
VM strategy trace):

| family | target rung | fired | fell through to |
|--------|-------------|-------|-----------------|
| literal (65,830) | LITERAL | 82.4% | CAND_SCAN 15.4%, PREFIX 6.5%, EXACT_FROM 2.0% |
| prefix (69,999) | PREFIX | 83.0% | CAND_SCAN 15.7%, EXACT_FROM 1.3% |
| cand-scan (69,677) | CAND_SCAN | 97.1% | EXACT_FROM 2.9% |
| anchored-fast (70,123) | ANCHORED_FAST | 100% (engine tier) | — |

Observations:

- **Facade blindness for ANCHORED_FAST**: `PatternMatcher.matches()` routes
  through `matchWhole`, which always serves/traces `ANCHORED`. The
  `ANCHORED_FAST` flat-table rung is reachable only at the engine tier
  (`RegexEngine.matches`). The campaign probes it engine-tier
  (Tnfa→Determinizer→TdfaRunner, same composition as
  `StrategyConformanceTest`); a facade-level consumer can never observe it.
  Worth a javadoc line on `Pattern`/`PatternMatcher` if nothing else.
- Literal/prefix gate misses concentrate on 1–2 char needles (below the
  detector's payoff threshold) and alternation-heavy prefixes — they fall
  through to CAND_SCAN/EXACT_FROM and stay oracle-correct; the misses are
  detector-threshold decisions, not bugs.
- Zero family mismatches vs the re2j oracle (both engines, five-probe
  protocol) in 270,007 cases.

## Per-item status vs TODO.md

- **Fuzz (?u) lane vs jur oracle** — harness now exists (`camp` jur mode,
  `CampaignSmokeTest.jurSliceIsClean` gates it in CI). 542,679 cases / 7.5 min:
  **0 unclassified divergences**; six known families classified (finding 2),
  plus skips (`(?U:` ungreedy groups untranslatable to jur ~7%, CI
  fold-ambiguous ~28%, budget ~0.1%). The matrix translation (tdfa bit 32 →
  jur `UNICODE_CHARACTER_CLASS`, CI → CASE_INSENSITIVE, no LONGEST) lives in
  `genJur`. The item stays open: this was a 7.5-minute slice, not a soak,
  and the lane is not yet wired into `DifferentialFuzzer`'s own matrix.
- **Overnight soak with cross-rung** — 20-minute datapoint: 253,285
  cases/min (cross-rung on, patched oracle), 0 real findings; consistent
  with the 267k/min figure in `3f8615f`. The actual overnight run (and
  README number refresh) remains open; these numbers are a rate sample,
  not a replacement.
- **Fuzz families for fact-gated rungs** — harness exists (`camp` families
  mode) with family-biased generators and routing verification; coverage
  data in finding 4. Zero correctness findings in 270,007 cases across
  literal / prefix / cand-scan / anchored-fast shapes.
- **Oracle-check stretched inputs; compare ASM** — harness exists
  (`camp` stretch mode): every case's input stretched past the 2048-char
  memo window (≥4300 chars) and the full five-probe protocol compared
  across re2j oracle + ASM + VM. 200,239 cases: **0 divergences**
  (empty-input skips 2.9%, budget 0.03%). Item addressed for the
  timeboxed scope.
- **Fuzz non-String CharSequence vs reference sim** — harness exists
  (`camp` seq mode; StringBuilder/CharBuffer/char[] wrappers × both
  engines × PikeSim + routing check): **finding 1** above (2,127
  classified records in 261,567 cases); otherwise clean.
- **Fuzz budget-exhaustion paths** — exercised via the soak itself with
  shrunk knobs: R2 (`maxWork=200000`, 40× shrunk) and R3 (`maxWork=20000`,
  400× shrunk). Results below.

### Budget-exhaustion runs (R2/R3)

| run | maxWork | cases | failures (all BUDGET_REJECT) | real findings | hangs |
|-----|---------|-------|------------------------------|---------------|-------|
| R2 | 200,000 ticks | 1,576,192 | 4,864 (0.31%) | 0 | 0 |
| R3 | 20,000 ticks | 1,204,160 | 16,360 (1.36%) | 0 | 0 |

Every rejection surfaced as `PatternTooLargeException` at compile time on
BOTH engine tiers with the oracle accepting (classified BUDGET_REJECT by
the harness) — no deferred explosions, no `EXCEPTION` kinds, no hangs, no
watchdog sacrifices: the typed-rejection path (`e9d6cec`) holds under mass
exhaustion. Note R3's case rate went UP vs R1 (300.7k vs 253.3k cases/min):
rejecting a compile early is cheaper than matching it.

## Reproduction steps (all runs)

```bash
# R1 — cross-rung soak slice (20 min)
./gradlew :tests:parity:re2j:fuzz -Pfuzz.minutes=20 -Pfuzz.seed=2026100301 \
    -Pfuzz.out=build/fuzz-runs/2026-10-03/soak-crossrung

# R2/R3 — shrunk-budget soaks
./gradlew :tests:parity:re2j:fuzz -Pfuzz.minutes=6 -Pfuzz.seed=2026100302 -Pfuzz.maxWork=200000 \
    -Pfuzz.out=build/fuzz-runs/2026-10-03/soak-budget-200k
./gradlew :tests:parity:re2j:fuzz -Pfuzz.minutes=4 -Pfuzz.seed=2026100303 -Pfuzz.maxWork=20000 \
    -Pfuzz.out=build/fuzz-runs/2026-10-03/soak-budget-20k

# R4 — the four campaign modes ('all' runs them as parallel campaigns in one JVM)
./gradlew :tests:parity:re2j:camp -Pcamp.mode=all -Pcamp.seconds=450 -Pcamp.seed=2026100304 \
    -Pcamp.out=build/fuzz-runs/2026-10-03/camp

# Replay any record (caseSeed from the mode's failures.ndjson)
./gradlew :tests:parity:re2j:camp -Pcamp.mode=seq -Pcamp.one=<caseSeed>

# CI gates for the harnesses
./gradlew :tests:parity:re2j:test --tests 'io.github.jemmix.tdfa.fuzz.*'
```

Artifacts: soaks write `failures.ndjson` / `summary.txt` / `progress.log`
under the out dir; campaigns write the same per mode under
`<out>/<mode>/` (plus `camp.log` with per-mode progress lines and
`summary.txt` with soft-kind counts and the routing histogram).
