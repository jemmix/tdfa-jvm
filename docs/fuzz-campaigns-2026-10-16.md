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
