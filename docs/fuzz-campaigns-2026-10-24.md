# Fuzz campaign run — 2026-10-24 (jur lane, overnight)

One lane-pure overnight soak off `main` at `5a06e17` (JDK 26.0.2, 8-core
macOS): the jur facade (both tiers) vs the live `java.util.regex` oracle,
cross-rung differential and stretched probes on (the defaults). First
overnight evidence since #82–#90 landed. Seeds are date-shaped and
deterministic; every record replays standalone via `-Pfuzz.one=<caseSeed>`
— with one load-bearing caveat, see the replayability note.

```bash
scripts/fuzz-soak.sh 480 1 build/fuzz-jur 202610241337 -Pfuzz.lane=jur  # 21:17 → 04:28 (+ replays → 04:32)
```

## The run

| | |
|---|---|
| Wall | 7 h 14 m (480/480 chunks, ~54 s median incl. gradle restart) |
| Cases | **168,857,946** (3.2× the 2026-10-16 jur leg; ~392 k/min avg — 3.6× its 109 k/min, the #74–#77 root fixes paying out) |
| Failure-class events | 116,447 (see breakdown below) |
| GC | 480 chunk logs, **zero `Pause Full`** |
| Real findings | 285 records outside the expected allowlist |

Recorded records (`failures.ndjson`, 122,980 lines; skip/guard records cap
at 8 per reason per chunk — counters keep running):

| kind | records | notes |
|---|---:|---|
| `BUDGET_REJECT` | 114,680 | ≈ 116.2 k true — watch item F7 |
| `RESULT_MISMATCH (both engines)` | 270 | F 137 · I 118 · R 10 · M 5 — findings 2–3 |
| `JURLANE_SKIP (restart-tag-leak)` | 266 | decided exclusion, cap 8/chunk |
| `JURLANE_GUARD` | 3,840 | ~0.34 % of cases (last chunk 1,264/372,072) |
| `JURLANE_SKIP (v1-ungreedy-scope)` | 3,840 | ~6.85 % of cases — documented rate |
| `HANG_ENGINE` | 10 | findings 4–5 |
| `HANG_ORACLE` | 4 | finding 6 |
| `CROSS_RUNG (MISMATCH)` | 1 | finding 1 |

## Summary of findings — the fix list

| # | Fix | Effort | Finding |
|---|---|---|---|
| F1 | ~~Cross-rung warm-state self-inconsistency~~ → ORIGIN_SIM leftmost-start miss (deterministic, cold; replay reporting gap fixed 2026-10-26, see the update under finding 1) | 2–5 d | **1 below** — engine vs itself, oracle-free |
| F2 | Extend `leakedRestartSpan` to the `I`/`R` probe clauses | hours | **2 below** — ~90 misclassified records |
| F3 | `(X)*tail` in-span participation: sticky-split extension or decided exclusion, pinned tests + battery; triage the one overall-span record separately | 2–4 d | **3 below** — ~180 records |
| F4 | Walk-loop spins, round 4: profile the three slow seeds, extend the ladder bounds | 1–3 d | **4 below** |
| F5 | `addZeroOpFrontier` tick starvation: meter the closure | hours | **5 below** — compile-time |
| F6 | `JURLANE_GUARD` predicate: lazy nested-quantifier bombs | hours | **6 below** — oracle-side |
| F7 | `BUDGET_REJECT` rate 13×: raised-knob replay verification; carry the shift into the README numbers | hours | **7 below** — watch item |

## Finding 1 — CROSS_RUNG, warm-state (1 record, unreproduced solo)

Seed `8663892547704900790`, pattern `((?s:(?m:(?:.{3}){3,}\S))){3}?`,
stretched input (~4.3 k chars): forced `ORIGIN_SIM` answered
`F=true 1..4301 (4287..4301)` where the natural ladder answered
`F=true 0..4299 (4285..4299)` (`L`/`R` differ too). A forced rung must be
protocol-identical to the natural ladder — this is the engine disagreeing
with itself, no oracle involved. Highest severity of the night; one
occurrence in 168.9 M cases.

**Solo replay is clean** (exit 0, no record) while the stretch draw is a
pure pattern/input hash — so the same probes ran and agreed. The
asymmetry: `fuzz.one` replays the case on a **cold artifact**, but a batch
shares one compiled artifact across its 8 cases; this seed is case index 6
of batch `1082986568463112598` (siblings `…84`–`…91`) and ran on a memo
warmed by six siblings. Prime suspect: lazy memo state on the shared
runner (`SearchDfa` rows/blocks; sibling inputs visit different rows).
Whether it is warm/cold determinism or a true cross-thread race is
exactly what the [runner-immutability program](runner-immutability.md)
sets out to make unrepresentable.

**Update 2026-10-28 — immutability program complete (#93–#100).** F1's
original suspicion (lazy memo state on the shared runner) is retired
**by construction**: both match-time memos were deleted outright after
their honest-cache conversions ([pricing](runner-immutability.md),
[tradeoffs](search-dfa-memo-tradeoffs.md)) — there is no memo state
left to be warm, cold, or racy. What remains of F1 is the ORIGIN_SIM
leftmost bug itself (its own TODO item above), which the 2026-10-26
update below adjudicated.

**Update 2026-10-26 — warmth hypothesis retired (immutability W1).** The
solo-replay cleanliness was a reporting gap, not a warmth effect: the
one-mode replay never printed or checked the cross-rung findings
(`o.cross`), so the divergence was there and silent. W1's replay fix
prints them — the record now reproduces **deterministically, cold,
single-threaded, every run** (`-Pfuzz.one=8663892547704900790
-Pfuzz.lane=jur`), with the double-run on and off, warm mode on and off.
The live `java.util.regex` oracle adjudicates: the natural ladder
(`F=true 0..4299 …`) is **right**, the forced `ORIGIN_SIM` answer
(`F=true 1..4301 …`) is **wrong** — a leftmost-start miss in
`multiStateLeftmostStart` (or its forced-entry plumbing), not memo
state. Minimal family: the same pattern on 3× the 18-unit base input
(54 chars, far under every budget) already diverges the same way;
forced `TRIGGER`/`RAW_SCAN`/`WALK_RESTART` all agree with the oracle.
The one-in-168.9 M rarity is reachability, not flakiness: the stretch
probe (1-in-8 hash sample) is the only natural route to the forced
rung for this shape. Fix: the ORIGIN_SIM leftmost discipline — tracked
as its own correctness item; the immutability program proceeds on the
state-discipline axis regardless (W2–W4 still delete the surfaces a
warm-state bug would have needed).

**Update 2026-10-28 — fixed (#110).** Root cause was in the sim itself:
the unbeatability scan's early exit stopped as soon as the PARTIAL
live-origin minimum reached `best` — a tie read as "no future accept
can beat it" while a smaller origin sat in an unscanned word (accept
origin 1 at pos 43, origin 0 alive in a later word ⇒ leftmost 1). The
natural ladder's exact walk at `from` answers 0 first, which is why
only the forced rung exposed it. The exit now stops only when decided:
partial min < `best`, or at `from` (the origin floor). Pinned in
`OriginSimLeftmostStartTest` (54-char minimal family, 4×/5×, stretched
shape; all rungs + live oracle).

## Finding 2 — the leak classifier reads only the first find (~90 records)

`leakedRestartSpan` inspects `firstFindClause` only, so JDK-8381696
restart-tag leaks surfacing on the iteration (`I`) or restart (`R`)
probes escape both skip predicates and land as `RESULT_MISMATCH`. Shape:
the oracle reports a group span strictly outside the entry's overall span
on a non-F probe; ours reports none. Examples (all replay clean
standalone):

| seed | pattern | flags | shape |
|---|---|---|---|
| 5354081794006039706 | `((?<n1>Z).)*?\s+?` | 32 | I entry `10..11 (- 4..5)` vs ours `(- -)` |
| 6211629843725923612 | `(?:(\udc21))*?\s{1}` | 5 | I entry `6..7 (4..5)` vs ours `(-)` |
| 4633556065707114785 | `(?:(?i:\udc21)(\D{1,1})\udc00)*.` | 0 | I entry `3..4 (4..5)` vs ours `(-)` |

Fix is harness-side (F2): run the strictly-outside predicate over every
`I` entry and the `R` clause. The predicate stays conservative; the
`CampaignSmokeTest` allowlist keeps it honest.

## Finding 3 — `(X)*tail` in-span participation (~180 records, new family)

Both engines vs the oracle: java reports a group **inside** the overall
span where we report nothing. Representative shapes/seeds:

| seed | pattern | flags | divergence |
|---|---|---|---|
| 7340427838858965240–47 | `((\s))*\s` | 0 | oracle `F=true 2..3 (- 2..3)` vs ours `(- -)` |
| 2135724555999141656 | `(?:(b))*b` | 0 | `F 18..19 (18..19)` vs `( -)` |
| 1900010144022828840 | `(?:( ))* ` | 2 | `F 14..15 (14..15)` vs `( -)` |
| 1606260156672107336 | `((\U00010000))*\U00010000` | 35 | `F 6..8 (- 6..8)` vs `(- -)` |
| 179386094230408747 | `(?:.(9)){0,}\d` | 0 | `F 4..5 (4..5)` vs `( -)` |

Mechanism: java's `Loop`/`GroupTail` commit discipline keeps a *discarded*
iteration's span (the loop backtracks to zero iterations, the tail atom
consumes the same text, the stale write survives). #87's sticky split
cloned this for `GroupCurly` bodies; these are Prolog/Loop bodies —
nested capture under `*`/`{0,}` with a tail atom — which the split does
not cover. Neither existing predicate fires (leak requires strictly
outside; nested-loop-capture requires nested quantified groups). The F3
decision: extend the sticky clone to Loop bodies (engine fix, battery
re-verification) or a conscious exclusion in the #89 style. One record is
**not** participation-shaped and must be triaged separately: seed
`2858084537373459748`, `((((\U000103ff)){0,}\D)(\udc00))x?` — oracle
`F=true 3..6` vs ours `3..5`, an overall-span disagreement.

## Finding 4 — walk-loop spins, round 4 (6 records, 3 real)

`TdfaRunner.extractFrom:1408` — the 2026-10-16 finding-3 family back in
new shapes, tiny inputs:

| seed | pattern | input len | solo replay |
|---|---|---:|---|
| 7149332676185069355 | `.+$\w` | 1 | **36 s** |
| 8643965881125084892 | `.+(?m:^\w)` | 5 | **32 s** |
| 4848892083176547229 | `[^9]+?\B\w+?` | 1 | **20 s** |
| 541553829559589526 | `(?s:.*?$\w)(\Q#@0\Eт)` | 16 | 9 s — clean |
| 2799524498384578279 | `(?m:.*$)\w\D` | 1 | 8 s — clean |
| 5866438399429661988 | `.{2,}$\D` | 4 | 5 s — clean |

(Plus `784949443052652145`, `extractFrom:1524`, replayed clean —
environmental.) The #75–#77 bounds covered the old shapes; `$`/`\B`/`^`
gates after unbounded class-plus slip past. JFR dumps:
`build/fuzz-jur/hang-<seed>.jfr`.

## Finding 5 — compile-time spins in `addZeroOpFrontier` (3 records)

`TdfaMaterializer.addZeroOpFrontier` — 10 s+ watchdog trips at *compile*:
the zero-op frontier closure iterates whole range lists between the
caller's meter ticks, so the churn never reaches the 8M-tick cap. Not an
infinite loop (the `visited` set bounds it) — tick starvation. F5: one
`meter.tick()` per range inside the loop turns the hang into the designed
clean `PatternTooLargeException`.

| seed | pattern (trunc) | solo replay |
|---|---|---|
| 2845852313826387552 | `\~(?:((?i:\udbff[^\u1c80\udfff_]+\w{0,6})g\u1c84)(?m:\.)){3` | 25 s |
| 9193966964223274768 | `\udbff\udbff(\~\B(\ud800…` | 21 s |
| 6737807483309015440 | `(?<n0>(?:\~(u(?:a*a+))\U000103ff)…` | 14 s |

## Finding 6 — jur oracle bombs past the guard (4 records, 2 slow)

Stacks 100 % `java.util.regex.Pattern$*`. The `JURLANE_GUARD` predicate
(#69) matches eager nested quantifiers; these are lazy-star shapes:

| seed | pattern | solo replay |
|---|---|---|
| 7532091064100310438 | `(.{0,6}?)*?\U00011c07` | **22 s** |
| 1764815253230278528 | `(.*?.*?){2,6}\t` | **15 s** |
| 7448041313256672922 | `((?:(?:.(?:\D??){2,}?)*(?:\udc21 (?:\udc21))\udc00){3,})` | clean |
| 3626555931799142662 | `(?:(.{0,5}?(.))*)*\U0001d504\n` | clean |

## Finding 7 — BUDGET_REJECT rate ×13 (watch item)

0.069 % of cases (≈ 116.2 k / 168.9 M) vs 0.005 % on the 2026-10-16 jur
leg (2,652 / 52.3 M) — systematic (per-chunk band 200–256, flat all
night), all deep-nesting monster shapes. Working hypothesis: #83's
`BOUNDED_ZWE` ε-subgraph duplication grows compile state on exactly these
patterns. Not a correctness finding — the rejection is clean and
documented — but F7 verifies classification (replay a sample with raised
`-Dtdfa.budget.compile.*` knobs: accept ⇒ compare clean) and the shift
must be stated in the README numbers refresh, not silently inherited.

**Update 2026-10-28 — verified, classification holds (0 mismatches).**
Two seed windows — a fresh 2-minute jur draw and this campaign's own
first chunk re-run (seed 202610241338: 192 rejects, inside the 200–256
band) — 16 distinct-shape seeds replayed with raised knobs
(`-Pfuzz.maxWork=0`, i.e. the library 500 M-tick compute budget, plus
`-Dtdfa.budget.compile.memory=1g` / `-Dtdfa.budget.runtime.memory=512m`
where the reject message named those): **13 accepted and answered the
five-probe protocol byte-identical to java.util.regex on both engines; 3
stayed over budget at any raise** — the `(\w.{0,}){1,6}` CFG family
expands to fill whatever memory cap it is given (4.19 M successor arcs at
128 MiB, 33.5 M at 1 GiB) and one `{2,4}`-nested monster burns the full
500 M-tick library budget (~55 s wall) — genuinely too large, the
documented clean rejection. Consistent with the #83 hypothesis (simple
CI shapes like `(\w)+` or `[^.\n]+(\w)` reject at the 8 M scoped ticks
yet compile oracle-clean at 500 M; the ε-duplication shapes grow
superlinearly); root-causing stays with the watch item. Pinned in
`BudgetRejectReplayTest`; the rate shift is now stated in the README
("How it's tested").

**Coda — the one flag that did not reproduce.** Mid-verification, an
F5-seed raised-knob replay (seed `2845852313826387552`) was flagged as
"accepts at a 16 M-tick raise, then mismatches the oracle" — which
would have been a real correctness finding. Exhaustive replay could NOT
reproduce it. At this verification's base (`8910439`): the 16 M compute
raise rejects cleanly (16,477,749/16,477,748 ticks — with and without a
1 GiB memory raise), and the one accepting configuration, the library
500 M budget, compiles BOTH engines and answers the five-probe protocol
byte-identical to java.util.regex, cold and warm (the seed is batch
index 0, so warmth adds nothing). The F5 siblings behave the same
(`9193966964223274768` accepts clean at 500 M; `6737807483309015440`
fills any raised memory cap, stays too large). On main, #104's per-range
frontier metering makes the seed's true cost visible — it burns the
ENTIRE library budget (499,700,533 ticks, ~13 s wall) and rejects
cleanly at 8 M, 16 M and 500 M alike, so the raised-knob path can no
longer materialize that artifact at all. Compute acceptance yields the
identical artifact in every accepting configuration, so a compute-raise
mismatch would have shown at the 500 M replay. The flag stays open in
TODO.md; if the original observation resurfaces, the divergence lives
somewhere this knob matrix cannot reach.

## Reproduction steps

```bash
# finding 1 — replays CLEAN cold; the warmth hypothesis needs the batch
# (case index 6; siblings 10829865684631125984..91 warm the shared artifact)
./gradlew -q :tests:parity:re2j:fuzz -Pfuzz.lane=jur -Pfuzz.one=8663892547704900790

# findings 2–3 — mismatches, all replay standalone
./gradlew -q :tests:parity:re2j:fuzz -Pfuzz.lane=jur -Pfuzz.one=5354081794006039706
./gradlew -q :tests:parity:re2j:fuzz -Pfuzz.lane=jur -Pfuzz.one=7340427838858965240
./gradlew -q :tests:parity:re2j:fuzz -Pfuzz.lane=jur -Pfuzz.one=2858084537373459748

# findings 4–6 — hangs; wall-clock them
time ./gradlew -q :tests:parity:re2j:fuzz -Pfuzz.lane=jur -Pfuzz.one=7149332676185069355 \
    -Pfuzz.maxWork=8388608 -Pfuzz.out=build/repro </dev/null
time ./gradlew -q :tests:parity:re2j:fuzz -Pfuzz.lane=jur -Pfuzz.one=2845852313826387552 \
    -Pfuzz.maxWork=8388608 -Pfuzz.out=build/repro </dev/null
timeout 120 ./gradlew -q :tests:parity:re2j:fuzz -Pfuzz.lane=jur -Pfuzz.one=7532091064100310438 \
    -Pfuzz.maxWork=8388608 -Pfuzz.out=build/repro </dev/null

# artifacts
#   build/fuzz-jur/{failures.ndjson,summary.txt,progress.log,iterations.log,
#                   gc-*.log,hang-*.jfr,hang-replays.log}
# CI gates for the harnesses
./gradlew -q :tests:parity:re2j:test --tests 'io.github.jemmix.tdfa.fuzz.*'
```

## Replayability note (the soak-stage lesson of this run)

`fuzz.one` regenerates the batch but executes only the one case — on a
**cold** compiled artifact. Records whose behavior depends on artifact
warmth (memo rows interned by sibling cases sharing the artifact) are
therefore *unreplayable standalone* even though the draw and probes are
deterministic. Same genre as the 2026-10-16 replay-stage bugs: the record
is only as good as its replay. Fix rides F1: a warm replay mode (run the
batch, then the case) plus the determinism double-run in the harness
(re-run the protocol on the same artifact, byte-compare, record any
`MEMO_STATE_MISMATCH`) — the double-run would have self-pinned finding 1
in the same chunk that produced it.

## What stays open

The both-lane soak TODO stays open: the re2j leg has not run, and the
clean-evidence re-run should follow F1–F6 (the re2j night would only
re-find the lane-agnostic classes — the cross-rung bug and the
materializer spins). README numbers refresh afterwards, with the F7 rate
shift stated.
