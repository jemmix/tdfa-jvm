# The match-time memos — priced tradeoffs and their deletion

Decision record for the two lazily materialized match-time structures the
runner used to carry: the **search-DFA memo** (`SearchDfa`, the lazily
determinized `.*?`+pattern automaton that served the trigger rung above
2 048 chars) and the **walk-block memo** (`WalkIndex`, per-state
512-codepoint dispatch blocks for the walk paths past the flat Latin-1
tables). The first was converted to the honest-cache shape in the
[immutability program](runner-immutability.md) W2 (#95) and then
**deleted** (#97); the second got the same conversion in W3 (#96) and
the same deletion (#98) once measured. This page preserves the numbers
so both decisions can be re-litigated with evidence, and names the
restoration criteria.

All measurements: Apple M1, JDK 26, 16 KB–256 KB inputs, min-of-windows,
strategy traces verified per row (which rung actually served). Where a
number is an estimate it is marked *est.*

## What the memo actually bought — the regime map

Strategy traces showed the memo engaged for exactly one pattern family:
**codepoint-lane wide-class patterns** (universe/complement classes ⇒
`scanSkipsPairs`) over **multi-KB** inputs. On the default
(java.util.regex-compatible, unit-semantics) surface, bounded-ASCII
patterns (`[a-z]+qrst`, `(a|b)*cde`, dotall shapes) **never reached the
memo at all** — they served the raw scan, at the same speed the memo
would have given.

| scenario (16 KB unless noted) | tdfa, memo | tdfa, raw | engaged? |
|---|---:|---:|---|
| unicode wide-class `\p{L}{2,}x`, no match | 22.4 | 52.9 | yes |
| same, 256 KB | 11.8 | 52.9 | yes |
| unicode wide-class, match at end | 8.4 | 62.9 | yes |
| unicode alternation, no match | 18.5 | 38.2 | yes |
| ascii literal tail, no match | 14.6 | 14.7 | no — raw serves |
| dotall repeat, no match | 45.2 | 45.2 | no — raw serves |
| chain alternation `(a\|b)*cde`, no match | 19.8 | 19.7 | no — raw serves |

So the memo's value = **2–7× inside one narrow regime**, ~1× elsewhere.
Memo occupancy for sensible patterns: 3 rows / 3 blocks ≈ 8 KB after a
scan; 4 rows / 15 blocks ≈ 34 KB after 50 diverse inputs (the 16 MiB
runtime budget is a lazily-charged ceiling, not an allocation).

## The options, priced

| design | first scan | repeat scans | build race (4 threads) | compile | RAM | verdict |
|---|---:|---:|---:|---:|---|---|
| shared honest cache (W2) | 1.1–1.35× warm | best | **1.09×** | +0 | ~8–34 KB/pattern (ceiling 16 MiB) | correct but narrow |
| per-`Matcher` confined memo | same (born cold, warms in-scan) | same within a Matcher | 1.0× | +0 | same, per live Matcher | deletes a penalty that measures ~0 |
| eager full precompute | warm | warm | — | **5–29 ms/row-universe** (20–100× compile) on toys; 13–70 s for adversarial | 278 KB/row dense | unbounded tail — rejected |
| sparse/interval rows (the "trie" form) | *est.* 30–40 ns/ch | *est.* same | — | cheap if built at breakpoints | ~100–1000× less per row | fixes RAM, not row blowup — still needs laziness |
| **no memo (chosen)** | raw = 14–63 ns/ch flat, forever | same | none | +0 | 0 | worst case is a constant, not a cliff |

Cold-scan amortization within one scan (the per-Matcher model):
1.19× at 2 KB → 1.35× at 16 KB (round noise) → 1.07× at 64 KB →
**1.01× at 256 KB** — the build cost is a prefix, amortized to ~1% on
long inputs.

Build-race contention (the real cost of shared mutable state): warm
shared reads replicate read-only cache lines across cores (measured
0.98–1.02× vs private); the 1.09× penalty appears only while threads
build entries simultaneously, and would be larger multi-socket.

## Against the friends, same challenging shapes

ns/char; java.util.regex shown as its 4 KB → 16 KB → full-length curve
(**DNF** = single call over budget or engine died):

| scenario | tdfa (memo) | **tdfa (no memo)** | java.util.regex | re2j |
|---|---:|---:|---|---:|
| unicode wide-class, no match, 16 K | 22.4 | 52.9 | 17 170 / 55 171 / 55 151 (quadratic) | 56.9 |
| same, 256 K | 11.8 | 52.9 | 13 747 / 55 194 / **DNF** (~minutes/call) | 57.4 |
| unicode alternation, no match, 256 K | 11.1 | 38.5 | 37 / 38 / 38 (linear, fast) | 75.8 |
| ascii literal tail, no match | 14.7 | 14.7 | 7 / 7 / 7 (best on the board) | 26.3 |
| dotall repeat, no match | 45.3 | 45.2 | 41 / 42 / 42 | 77.0 |
| chain alternation, no match | 19.9 | 20.0 | – / – / **DNF** (`StackOverflowError`) | 38.2 |
| unicode wide-class, match at end | 8.4 | 62.9 | 10 / 5 / 5 (first-char bitmap skip) | 61.3 |

Reading: **no-memo tdfa's worst case is ~63 ns/char, flat with length,
on every shape** — never quadratic, never a crash. It beat or matched
re2j on *every row*. java.util.regex is bimodal: 5–9 ns/char where its
first-char bitmap works, seconds-per-call or `StackOverflowError` where
greedy repeats meet no-match. The memo was what put 2–7× of daylight
between tdfa and re2j in the unicode long-scan regime specifically.

## The decision

Delete. The keep-cost (a cache to reason about, cap, and test; a
narrow regime as its only payoff) exceeds the measured value on both
surfaces this library ships. The trigger rung serves via the
kill-point-aware raw simulation; correctness artifacts (no-match proof,
kill-point window) are unchanged; the immutability program's remaining
work (W4 precompute + W5 gate-to-end-state) is unaffected — one honest
cache (`WalkIndex`, walk blocks) remains, and it is uncontroversial:
tiny, input-visited, with an exact binary-search fallback.

Deliberately left behind for the review (TODO): the now-unused budget
split (`Budgets.sdfaMaxRows`/`sdfaMaxBlocks`, the RUNTIME_ROW/BLOCK
weights, and the eighths partition — the walk memo can take the whole
memo share), and `Strategy.TRIGGER` (kept as a forced-rung identity;
it now traces RAW_SCAN).

## Restoration criteria — when to bring it back

Bring the memo back (as the W2 honest cache, or the two-level variant
below) if **any** of these measures flips:

1. **The long-input bench grows unicode-heavy rows** (the TODO
   "long-input scans" work): if the codepoint-lane surface with >64 KB
   no-match scans becomes a headline workload, 2–5× there justifies the
   cache again.
2. **A real workload shows re2j-class competition matters there** — the
   board above is the baseline to beat.
3. **Multi-socket build contention or cap-pressure churn** ever shows up
   as a finding class in soaks (it cannot today: there is no shared
   build path left).

## If restored: the two-level shape is the best candidate

`Matcher`-local L1 (on `MatchScratch`, the sanctioned single-threaded
carrier) + engine-shared L2 (the W2 honest cache) + **merge-once-per-
Matcher of ≥2-hit entries** (the frequency filter keeps both the merge
burst and L2 pollution bounded; L2 ends up holding only recurring
structure). Wins over the pure shared cache: the 1.09× build race, a
fresh local budget under cap pressure, plain loads on the hot path.
Costs: ~200 lines, per-Matcher RAM, two lookup levels. It composes with
the immutability invariant unchanged (L1 on the carrier, L2 pure
`putIfAbsent` merges).

The budget knob meanwhile: 16 MiB/pattern was never an allocation, but
2 MiB is the defensible ceiling for the walk memo if it is ever
revisited — nothing sensible measured above ~1 MB.

## Part II — the walk-block memo (`WalkIndex`), same treatment

The walk memo served the WALK paths (extract/matches loops): for
codepoints past the flat Latin-1 tables it turned "binary-search the
state's char-class ranges" into one array load, via per-state lazily
published immutable 512-codepoint blocks. After W3 it was the last
shared, thread-safe mutable structure in the engine cluster.

### Measurements (alternating same-session A/B, min across runs; two machine states — quiet and thermally noisy — shown where the sign flipped)

| shape | ranges/state | memo (current) | no memo (binary search) | memo occupied |
|---|---:|---:|---:|---:|
| explicit Cyrillic class `[А-я]+@…` | ~2 | 32–40 | **27–28** | 21 KB / 9 blocks |
| mixed wide `[\p{L}\p{N}]{3,}:` | ~600 | 50–65 | **51–52** | 10 KB / 4 blocks |
| wide-class extract `(\p{L}{2,})x` | ~600 | 44 / 55 | 46 / **47** | 10 KB / 4 blocks |
| cold vs warm (memo) | | ~1.0× on every shape | — | |

Reading:

- **The memo never shows a robust win.** On many-range wide-class shapes
  — its theoretical home turf (1 array load vs ~10 binary-search
  compares) — the two dispatch forms land within noise of each other
  (±10 %, sign flipping with machine state): dispatch is a minority of
  per-char walk cost (ops, regs, masks dominate). On few-range explicit
  classes — keywords, bounded classes, the common case — the binary
  search is 1–2 compares and **beats the memo's 3 dependent loads by
  13–31 % in every session**.
- **No warmth value**: cold ≈ warm everywhere; blocks build in
  microseconds; occupancy 4–9 blocks (10–21 KB) — there was no
  inter-call warmth worth sharing, which was the entire reason the
  structure was shared and thread-safe.
- **Per-`Matcher` confinement was strictly dominated**: same wash
  economics (the lookup itself loses; sharing was never the issue), plus
  plumbing — several walk paths (`matchFromFast` et al.) carry no
  `MatchScratch`.
- **Precompute-everything** enumerates cheaply (content is a pure
  function of (state, block); < 1 ms, ~900 KB for the wide-class toys)
  but is pointless when the dense lookup loses to the search on common
  shapes, and explodes to ~GB on dictionary-scale wide-class DFAs — the
  exact class the caps existed for.

### Decision

Delete (#98). Every walk dispatches via the binary search that already
existed as the exact first-class fallback. Consequences: the runner
cluster reaches **absolute** frozen-at-construction — no atomics, no
CAS, no cap markers, no "lives in a final container" clause — and the
invariant's second sentence ("caches are unobservable") becomes vacuously
true. The only shapes that could miss it are unicode-heavy
many-hundred-range class walks, where it measures within noise; that
claim is recorded here with data.

Restoration criteria (both memos): see Part I's list; the walk memo's
trigger would be a measured regime where wide-class walks dominate AND
binary search demonstrably bottlenecks — no such shape was found in two
days of probing.
