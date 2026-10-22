# Fuzz campaign runs — 2026-10-16

Two lane-pure overnight soaks, run serially off `main` at `04c4297` (JDK
26.0.2, 8-core macOS): the re2j surface vs the patched re2j fork, then the
jur facade lane vs live `java.util.regex`. Seeds are date-shaped and
deterministic; every record replays standalone via `-Pfuzz.one=<caseSeed>`.

```bash
scripts/fuzz-soak.sh 480 1 build/fuzz-re2j 20261015 -Pfuzz.lane=re2j   # 20:00 → 04:12
scripts/fuzz-soak.sh 480 1 build/fuzz-jur   20261016 -Pfuzz.lane=jur   # 04:12 → 12:26
```

## Summary of findings

| # | Severity | Finding |
|---|----------|---------|
| 1 | **Engine divergence (both tiers)** | İ in CI-closed classes/ranges: tdfa's simple `İ→i` fold admits/excludes İ where `java.util.regex` full case folding disagrees. 3 records; the exact family the `04c4297` "İ/ı fold fix" touched — residual edges. |
| 2 | **Engine divergence (both tiers)** | `(\A|.?o*.){3}` on ASCII inputs: tdfa reports whole-region `matches()` spans jur denies — alternation preference inside a repeated group. 2 records. Not covered by the group-participation classifier (spans differ, not just group clauses). |
| 3 | **Engine pathology (match-time, both tiers)** | `TdfaRunner` unanchored-scan restart loop burns 10 s+ (watchdog) / 16–55 s (solo) on *tiny* inputs for `.*`-before-anchor shapes (`.*(?:\A\W )h`, `.+^\W`). Stacks: `extractFrom → forcedRestart/forcedSearch/tryStartFast`, one `runStringExtract`, one `positionFlags/isUnicodeWordCodepoint`. The 8M-tick budget bounds *compile* only; match-time work is unbounded. |
| 4 | **Oracle behavior (jur, expected)** | `java.util.regex` backtracking bombs: 3 spins reproduce solo >100 s, 5 complete in 12–23 s (over the 10 s watchdog solo). Stacks entirely `Pattern$LazyLoop/Curly/GroupCurly` — catastrophic backtracking, not our engine. Needs generation guards (the `ciWideRangeAvoided` pattern), like the existing re2j oracle-hang guard. |
| 5 | **Soak-stage bugs (fixed here)** | (a) replay stage wedged on macOS: `timeout` + SIGTTIN stops the gradle wrapper in `T` state forever — zero replays executed, bogus `wall=1240s` verdicts; (b) replay command omitted the lane pin — jur hang cases replayed against the *re2j* oracle, flipping 5 of 14 verdicts in the control run. |
| 6 | Clean bill | re2j lane: **147.4 M cases, 0 real findings** (all 33,968 records are the documented `BUDGET_REJECT` class, 0.023%). jur lane beyond findings 1–4: 52.3 M cases, 2,652 `BUDGET_REJECT`, expected `JURLANE_SKIP`s only. |

## The runs

| Run | Lane | Wall | Cases | Rate | Records |
|-----|------|------|-------|------|---------|
| R1 | re2j (vs patched fork) | 8 h 12 m | 147,406,080 | ~307k/min | 33,968 × BUDGET_REJECT |
| R2 | jur (vs live java.util.regex) | 8 h 14 m | 52,338,240 | ~109k/min | 5 mismatch, 14 hang, 2,652 budget, 7,974 skip |

Throughput flat across both nights (re2j quartile averages 301–308k; median
chunk wall 61 s, max 74 s, none >120 s). Zero `Pause Full` in any GC log —
the chunked-JVM design and the GC-stall false-hang grace both held.

## Finding 1 — İ fold divergences (3 records)

All three: CI on, İ (U+0130) in input, class/range in pattern. tdfa folds
İ→i (simple fold) and admits/excludes İ accordingly; jur full-folds
(İ lowercases to `i` + U+0307, a two-char sequence) and disagrees.

| seed | pattern | input | disagreement |
|------|---------|-------|--------------|
| 2318791749481276809 | `[^ᲈ@\--_]{2,5}` | `İ\n.\n\n` | jur F=0..2, tdfa F=3..5 (first-match shift) |
| 1645459121172146311 | `\W?[\--hᲀ-𑰇b]+` | `İ\n\n` | jur F=false, tdfa F=true 0..1 |
| 6262284812096447653 | `[\s_@-Z]{2,}` | `\n\nİ` | jur F=0..2, tdfa F=0..3 whole-region |

## Finding 2 — quantified-group alternation order (2 records)

`(\A|.?o*.){3}`: jur prefers the `\A` (empty) branch every repetition →
empty overall span → `matches()` false. tdfa explores branch orders under
the whole-region demand and reports `M=true 0..1` on `Y`, `M=true 0..2` on
`Z\n` — the `Z\n` case consumes a `\n` through `.` without DOTALL.
Seeds `1180600198348663672`, `1180600198348663673`.

## Finding 3 — engine match-time restart blowup (6 hang records)

Soak records (10 s watchdog, `verdict=spin`, `cpuMs` ≈ 10 s):

| seed | pattern (trunc) | input len | hot path | solo replay |
|------|-----------------|-----------|----------|-------------|
| 266921720266595953 | `(.+$\D|.+ \udc00\D+…)\b` | 12 | forcedRestart (crossRung leg) | 9 s env |
| 7259375349118265321 | `.*(((?m:$\d)b)\s){0,4}n` | 45 | forcedRestart (crossRung leg) | 9 s env |
| 8711857026143046313 | `.*(?:\A\W )h` | 5 | runStringExtract | **39 s** |
| 2272272519475091607 | `.{1,}(?:\A\w(?m:…))\w` | 31 | runStringExtract | **55 s** |
| 5294710294757508443 | `(?i:((9@^|\s{0,2}?.{2,})?\D)\b\udc21)` | 29 | positionFlags/isUnicodeWordCodepoint | 4 s env |
| 5327627878984006277 | `.+^\W` | 2 | forcedRestart | **16 s** |

The three slow-but-completing replays prove real engine cost independent of
soak load: no match-time budget exists, only the case watchdog. JFR dumps
per record: `build/fuzz-jur/hang-<seed>.jfr`.

## Finding 4 — jur oracle backtracking bombs (8 hang records)

3 spins reproduce solo >100 s: seeds `2330342924249124857`, `9136944985473555852`,
`1332651887052906266` — nested quantified groups (`(?:\D+[^\ds-z#𐐂]*?){2,}?`
and friends), stacks 100% `java.util.regex.Pattern$*`. 5 more complete in
12–23 s solo (over the watchdog). The harness handles them (worker
sacrifice, correct `HANG_ORACLE` attribution); the fix is to stop
*generating* them — extend the oracle-hang guard family.

## Finding 5 — soak replay-stage bugs (this PR)

**SIGTTIN wedge.** `timeout(1)` runs the child in its own process group;
the gradle wrapper touches the controlling terminal; macOS stops it
(`T` state). `timeout`'s SIGTERM cannot reach a stopped process — the stage
sat 20 min per seed executing nothing, writing `wall=1240s` verdicts.
Fix: `</dev/null` on the replay invocation.

**Missing lane pin.** The replay command dropped `$EXTRA`, so `fuzz.one`
regenerated the batch's *natural* lane (RE2J) for jur-soak cases —
replaying against the wrong oracle. Control run: 5 of 14 verdicts flipped
vs the lane-pinned rerun (e.g. seed `2272272519475091607`: 2 s wrong-lane
"clean" vs 55 s jur-lane slow). Fix: pass `$EXTRA` through.

**JFR caveat (documentation only).** Chunk-level JFR dumps carry cumulative
`jdk.ExecutionSample`s across the whole 60 s chunk — dominated by other
batches' engine work. They mislead per-case triage (this report initially
"proved" misattribution from them). The per-record `stack` field is the
per-case truth; `isEngineStack` attribution was correct for all 14 records.

## Reproduction steps

```bash
# mismatches (findings 1+2) — prints oracle/asm/vm protocols side by side
./gradlew -q :tests:parity:re2j:fuzz -Pfuzz.lane=jur -Pfuzz.one=2318791749481276809
./gradlew -q :tests:parity:re2j:fuzz -Pfuzz.lane=jur -Pfuzz.one=1180600198348663672

# engine slow cases (finding 3) — wall-clock them; 39 s / 55 s / 16 s observed
time ./gradlew -q :tests:parity:re2j:fuzz -Pfuzz.lane=jur -Pfuzz.one=8711857026143046313 \
    -Pfuzz.maxWork=8388608 -Pfuzz.out=build/repro
time ./gradlew -q :tests:parity:re2j:fuzz -Pfuzz.lane=jur -Pfuzz.one=2272272519475091607 \
    -Pfuzz.maxWork=8388608 -Pfuzz.out=build/repro
time ./gradlew -q :tests:parity:re2j:fuzz -Pfuzz.lane=jur -Pfuzz.one=5327627878984006277 \
    -Pfuzz.maxWork=8388608 -Pfuzz.out=build/repro

# jur oracle bombs (finding 4) — spins; expect >100 s or watchdog death
timeout 120 ./gradlew -q :tests:parity:re2j:fuzz -Pfuzz.lane=jur -Pfuzz.one=2330342924249124857 \
    -Pfuzz.maxWork=8388608 -Pfuzz.out=build/repro </dev/null

# artifacts
#   build/fuzz-re2j/{failures.ndjson,summary.txt,progress.log,iterations.log}
#   build/fuzz-jur/{failures.ndjson,summary.txt,progress.log,iterations.log,hang-*.jfr,hang-replays.log}
# CI gates for the harnesses
./gradlew -q :tests:parity:re2j:test --tests 'io.github.jemmix.tdfa.fuzz.*'
```

Artifacts: same layout as the 2026-10-03 campaign; `hang-replays.log`
contains the lane-pinned manual replay verdicts for all 14 hang seeds
(the driver's own stage was wedged — finding 5 — so the pass was rerun by
hand with identical command/verdict rules).

## Follow-ups (2026-10-17)

The pre-soak fix pass, landed as #69–#71:

- **Finding 4 → guarded (#69).** Jur-lane batches whose pattern nests an
  unbounded quantifier over a wide atom inside an unbounded-quantified
  group are skipped as `JURLANE_GUARD` records (0.33 % of jur cases; the
  re2j lane still runs them). All three >100 s bomb seeds now replay
  instantly with a guard notice.
- **Finding 3 → bounded (#70), root work started.** A per-operation
  match-work budget (`tdfa.budget.match.ms`, default 2 s wall — a WALL
  bound, not steps: the diseases are per-step cost explosions on step
  counts legit work also reaches; the conformance suite caught a first
  steps-based cut false-tripping on `[a-z]+qrst`-style churn) checked
  once per 256 restart-loop iterations, so the walk leaves stay pristine
  (a per-step debit cost 10–14 % on the scan benches). All six engine-spin
  seeds replay in 1–3 s with the classified `MATCH_BUDGET` record; a
  2-minute mixed soak ran 276 K cases with zero hangs. The root quadratic
  (no trigger bound under unit semantics) stays open in TODO.
  Root-work note (2026-10-18): profiling the finding-3 seeds split the
  per-step cost into two diseases — (a) the position-flag battery at
  every entry/required-mask probe (the emitted tier computed the word
  pair even for `\b`-free artifacts), addressed by mask-directed probes
  (`maskSatisfied` at the entry/entryNext sites; `positionFlagsC`
  specialized by `posFlagDeps()`), and (b) the restart ladder itself
  (n restarts × O(n) walk, each step paying a ~800-candidate prefix-max
  back-scan on the CI+UCC fold-expanded states).
  (b) split again by anchor class: the `\A`/non-multiline-`^` shapes
  (`.*(?:\A\W )h` 39 s, `.{1,}(?:\A\w…)\w` 55 s, `.+^\W` 16 s) are
  root-fixed by the abs-begin ladder bound
  (`Tdfa.absBeginBound()` — every accepting path crosses an \A gate ⇒
  restarts past the origin provably fruitless ⇒ the ladder collapses to
  the exact walk; replays now < 1 s, a 4 400-char no-match find
  measures 2–5 ms). The unit-semantics trigger bound followed
  (`unitRawScan`: the raw scan's kill-point proof with interior-unit
  seeds — at every surrogate pair the start state's transitions on the
  lone low unit join the live set, making the no-match proof and the
  kill-point window sound under unit semantics): the plain `.+tail`
  family (`.+zz`, `\S{4,}\.`) went from 200–460 ms per 4 400-char
  no-match find to 1–3 ms, and the multiline-gated seeds dropped to
  1.5–2.3 s replays. The residue — boundary-gated alternations
  (`(.+$\D|.+ x\D+)\b`) whose accepting states look live to the
  mask-blind simulation, defeating the window early — stays
  budget-contained (~170–250 ms per 4 400-char no-match find) pending a
  mask-aware simulation or an accepted-residue decision (open TODO).
  The mask-aware simulation then landed (the raw scan gates its
  transitions and accept reports on the walks' own range/accept masks,
  making the simulation's liveness exact): the residue class went
  linear — 10–18 ms per 44 000-char no-match find, where
  java.util.regex measures 4.7 s — and with every degenerate class
  root-fixed the match-work budget itself was retired (the
  {@code tdfa.budget.match.ms} property, {@code
  MatchBudgetExceededException}, the metering ThreadLocal and the
  emitted-ladder hook all deleted): a wall-clock exception in a
  linear-time engine's API was a smell the root fixes made
  unnecessary.
- **Finding 1 → fixed (#71).** Root cause: java.util.regex folds WRITTEN
  ranges by a simple-case IMAGE test, not the member-orbit closure it
  uses for singles/literals — `satisfies(c, R) ⟺ IMG(c) ∩ R ≠ ∅` plus
  (JDK 26+) a member-side clause and the `toLower∘toUpper` image, with
  `IMG(x) = {x, toLower(x), toUpper(x)}`. The clauses are PROBED from
  the live JVM (JDK 26 regex tests more than 25/11 did — the CI's JDK 25
  row caught it). All three seeds replay bit-for-bit equal to the
  oracle; 28-pattern × 15-input live-oracle battery added in
  `PivotLanesTest`.
- **Finding 2 → fixed (root cause corrected in the landing).** The
  divergence is NOT GroupCurly's cmin-commit: `(\A|.)`-class bodies are
  non-deterministic (Branch.study), so java compiles the `{n,m}` as
  `Prolog`+`Loop` — and `Loop.match`'s guard `i > locals[beginIndex]`
  forces the loop to EXIT after any zero-width iteration, the count
  check sitting inside the guard (an under-`cmin` exit on ε is legal —
  `(\A|.){2}` find on `"Y"` reports `0..0` with ONE iteration). Our
  structural desugar's concatenated copies admit `[ε, consume]` paths
  java's backtracker never explores. Probed surface wider than the
  campaign's paraphrase: `{0,2}`/`{1,2}` diverge on GROUPS (`(\b|Y){0,2}`
  on `"Y"`: jur `g1 1..1` via `[Y,ε]`, ours `g1 0..1` via `[ε,Y]`), lazy
  variants and `{2,}` prefix copies included; all-zero-width
  deterministic bodies (`(\b){2}`) keep agreeing (java's GroupCurly
  COMMITS those — no guard). Fix: `Ast.Repeat.zeroWidthExit`
  (parser-marked, JUR reading only — nullable-through-choice bodies,
  `{n,m}` with max ≥ 2 and `{n,}` with min ≥ 2) drives the NFA builder's
  BOUNDED_ZWE frame: each desugared copy's ε-reachable subgraph is
  duplicated, the duplicates' copy-exit arrivals re-route to the
  quantifier exit (tags carried — java reports the exiting iteration's
  spans), consuming edges rejoin the original nodes. Both campaign seeds
  replay bit-for-bit equal; `zwexit-battery.tsv` (41 rows, live-JDK
  re-verified each run) pins JUR and RE2 columns on both tiers; a
  30 K randomized bounded battery and a 0.9 M-case mixed fuzz batch
  found zero divergences outside the documented classes. Residue found
  by the shakeout, now the open TODO: the UNBOUNDED plus has the
  mirror-image bug (`(Y?|a\z)+` on `"Ya"`: jur `[0,1) g1 1..1`, ours
  `[0,2)` — the hub's same-position re-entry dedup kills the preferred
  ε-exit-with-span path).
- **Soak readiness.** With 1, 2 and 4 addressed and 3 closed root-and-branch
  (#74 mask-directed probes, #75 abs-begin ladder bound, #76 unit-semantics
  trigger, #77 exact simulation + budget retirement), the next both-lane
  soak's expected residue is the unbounded-plus ε-exit family (the finding-2
  landing's shakeout residue, TODO) plus the documented
  BUDGET_REJECT / JURLANE_GUARD / JURLANE_SKIP classes — MATCH_BUDGET no
  longer exists (every degenerate class root-fixed).
