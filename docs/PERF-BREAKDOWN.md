# Performance Optimization Breakdown

Status ledger of the scan-performance work: what was measured, what landed,
what it bought, what remains, and what was deliberately not done. All numbers
are from this codebase's harnesses (steady-state probes, RebarBench
fast/accurate, JMH ShortFindBench, FloorProbe) unless noted. Companion living
plan: `TODO.md` (rebar section); harness docs: `README.md` (search
acceleration), `docs/REBAR-SPEEDUP-PLAN.md` (earlier tiers).

## 1. The goal, as agreed

- **Beat re2j decisively** — geomean AND per-row record, not just aggregates.
- **Parity with java.util.regex (jur)** — including short inputs, where
  per-call costs dominate.
- reggie = reference only (generated direct-call matchers, no Matcher API).
- All accelerations must be semantics-transparent (detection/skip only, exact
  walks confirm) and disclosed (`README.md` → "Search acceleration, disclosed").

## 2. Where we started (2026-08-14 baseline)

Fast-bench geomean over 110 in-scope rebar scenarios:
vm **1.07x** / asm **1.35x** vs re2j; vm **1.86x** / asm **2.34x** vs jur —
losing to both. Steady-state probes of the worst shapes: literal search
6.6 ns/char (re2j 0.26 — no prefilter), `\b\w{12,}\b` unicode on Russian
7164 ns/char (jur 27), `\p{L}{256}` ~3000 ns/char.

## 3. What landed (commits ad984c4..732a5c2, all gates green)

### 3.1 W1a — drop dead gap-filler range entries
`fillGaps()` tiled every state's transition list with target=-1 entries so
ranges covered [0,0x10FFFF]. Semantically unnecessary (no-hit = death in every
consumer; the ASM reader already filtered them) but they double wide-class
entry counts. **Effect:** `\p{L}` 1369→684 entries max, `\w` states
2800→1866. Compile-time only, zero match-path risk.

### 3.2 W1b — binary-search dispatch with per-entry prefix-max-hi
All five linear range scans in `TdfaRunner` became: binary search for the
rightmost entry with `lo <= c`, then a backward walk while the per-state
prefix-max-hi (`Tdfa.entryHiPrefix`, 1 int per entry) still reaches c.
O(log cnt + overlap-depth) instead of O(cnt); preserves entry-index priority
(alternation and mask-specificity order — caught by WordBoundaryTest when the
first version walked wrong). Per-state sorted-by-lo now enforced at
finalization (minimizer/regopt can reorder). **Effect:** long-russian
7164 → 117 ns/char (61x).

### 3.3 W2' — lazy search-DFA trigger with kill-point windows
The multi-state bitset simulation of the implicit `.*?` prefix is memoized
into flat rows; transitions materialize lazily as deduplicated 512-codepoint
BMP blocks, so scanning is a table lookup per char. Kill points (every live
configuration dies on a char) advance a window bound W; the exact extract and
the origin-tracking leftmost sim both start at W. Same over-approximation as
the old pre-check (masks ignored, masked accepts included) — can fire
 spuriously, never misses; exact walk confirms.

Guardrails, each earned by a caught bug:
- **per-Tdfa memo, not per-runner** — per-runner memos OOM'd the 1M-compile
  exhaustive suite (452 MB of int[] before GC could keep up)
- **2048-char window floor** — short scans raw-scan (a block build = 512
  interned steps, never amortizes on short inputs)
- **capped fallback carries the exact live set** — restarting from a bare seed
  dropped in-flight configurations and skipped real matches (leipzig
  `[a-q][^u-z]{13}x`: 541 vs 543 matches)
- **kill re-seeds the live set** — an empty set killed every later step too,
  masking all matches after the first dead char (re2j-suite catch)

**Effect:** long-russian → 16 ns/char (now beating re2j 23 / jur 28);
zh literal 18 → 9; delta256 2659 → 2082.

### 3.4 W2 — exact-literal `String.indexOf` fast path
When the whole regex is a plain literal (single char-chain DFA, no
groups/tags/ops/masks, final state with **no live out-transition** — the
`a+` self-loop was the initial miss), `find()`/`leftmostStart()`/extract use
`String.indexOf` (JIT-intrinsic, vectorized). **Effect:** Twain 6.6 →
0.22 ns/char = re2j parity; Holmes 0.19; CJK literal 1.78 (re2j 2.12).
Disclosed in README.

### 3.5 Budgeted origin-sim (dense/sparse split)
The unconditional trigger pre-scan doubled work for dense-match loops
(findAllDense/Latin1 +38–50% in the regression harness). Now the
origin-tracking sim runs with a 4096-char budget: found inside budget → done
(old dense speed, trigger never runs); budget exceeded → trigger bounds the
window, sim finishes over [W, to]. **Effect:** dense regressions gone;
scanNoMatch −21..−25%.

### 3.6 Exact-walk-first in generic paths
`runStringExtract`/`find()` ran the trigger pre-check before any exact
attempt; for prefix-chain DFAs (`\p{L}{256}`) the raw-scan pre-check is
O(len²) in live-set size. One exact single-start walk from `from` now runs
first (refactored `extractFrom`). **Effect:** `\p{L}{256}` 1106 → 82 µs.

## 4. Scoreboard after the round

| comparison | before | after (fast) | after (accurate) |
|---|---|---|---|
| vm vs re2j geomean | 1.07x | 0.57x | **0.50x** |
| asm vs re2j geomean | 1.35x | 0.82x | 0.74x |
| vm vs jur geomean | 1.86x | 0.94x | **0.92x** |
| asm vs jur geomean | 2.34x | 1.35x | 1.36x |

Per-row (accurate, 70 real scenarios — `test/*` micro rows excluded as
fixed-overhead noise): **vm vs re2j 60W/6T/4L** (decisive; worst loss
all-russian 6.4x). vm vs jur 34W/10T/26L — faster overall but not per-row
parity; losses cluster in exactly three shapes (§6).

Blowout wins: i1095-ascii **200x faster than jur** (and 190x re2j),
lexer-veryl 12x, overlapping-words 12x, quadratic-10x 9x, dictionary 23x jur.

Compile: vm 4.8s / asm 3.8s total vs jur ~0.1s — 80% is dictionary (2.2s) +
i1095 (0.9s); the known eager-determinization cost, separate roadmap
(minimization, lazy determinization).

## 5. Short inputs — the honest picture (JMH ShortFindBench, ns/op)

| slug | jur | re2j | reggie | vm | asm | vm/jur |
|---|---|---|---|---|---|---|
| litFind | 75 | 173 | 13 | **24** | 51 | 0.31x |
| caseiLit `(?i)sherlock` | **33** | 278 | 12 | 176 | 183 | 5.3x |
| wordB `\bword\b` | **171** | 462 | 42 | 276 | 424 | 1.6x |
| wordUnicodeCls `\p{L}{2,}` | **25** | 266 | 80 | 135 | 135 | 5.3x |
| lettersRu | **65** | 252 | 4 | 68 | 155 | 1.04x |
| boundedSpan `"[^"]{5,20}"` | **40** | 452 | 31 | 310 | 428 | 7.8x |
| ipExtract | **159** | 1203 | 111 | 492 | 676 | 3.1x |
| alternation | 124 | 428 | 537 | **68** | 299 | 0.55x |
| emailNoMatch | **343** | 1601 | 241 | 634 | 1082 | 1.85x |
| litNoMatch | 37 | 37 | 12 | **19** | 25 | 0.51x |
| **geomean** | | | | | | **1.48x** (vs 0.66x on full haystacks) |

vs re2j: **0.37x geomean — decisive at every input size.** vs jur: short
inputs are exactly where we lose; every loss traces to per-call machinery,
not fixed overhead (FloorProbe: our Matcher allocation = 0.0 ns delta
fresh-vs-reset — escape-analyzed away; jur pays 7.5 ns; no string copying).

## 6. Remaining gaps, root-caused and sized

| # | cluster | evidence | root cause | fix | effort |
|---|---|---|---|---|---|
| R1 | short-input sim machinery (boundedSpan 7.8x, caseiLit 5.3x, ipExtract 3.1x, emailNoMatch 1.85x) | JMH | origin-sim + budgeting costs more than the walk it guards on 40-char inputs | first-char-set candidate scan (start state's outgoing char set, 256-bit table loop) + exact walk; engage below a length threshold so the long-input trigger regime is untouched | S–M |
| R2 | shim `matches()` path 51.6 ns vs 2 ns core | FloorProbe | layer chain + volatile wholeEngine lookup on the anchored hot path | trim the shim path | S |
| R3 | ASM +25 ns constant on short matches (m@0: asm 31.7 vs vm 6.3; alternation 299 vs 68) | FloorProbe | genMatch chars-cache + emitted extract cost more per call than the runner fast path | delegate to `runner.match` below length threshold | S |
| R4 | c≥0x100 walk dispatch (wordUnicodeCls 5.3x) | JMH | extract walks binary-search 684-entry tables per char for non-Latin-1 | W1c: 512-cp BMP blocks for the walk path (same shape as trigger blocks) | M |
| R5 | wordB 1.6x | JMH | positionFlags word-class checks per position | bitset for the word class | S |
| R6 | rebar unicode scan tail (all-russian 3.9x, letters-ru 4.7x, casei scans 3.2–3.3x, quotes-bounded 4.7x vs jur) | accurate | same families as R1/R4 at haystack scale | mostly falls out of R1+R4; re-measure | — |
| R7 | compile time (dictionary 2.2s, i1095 0.9s) | accurate | eager determinization + minimization | separate roadmap (lazy DFA build, better minimization) | M–L |

## 7. Deliberately not done (with reasons)

- **W1c for scan loops** — measured unnecessary after W1b+W2' (the trigger's
  blocks already give O(1) BMP dispatch for scanning; long-russian beats jur).
  The walk-path variant survives as R4.
- **W3 "ASM slower than VM on big DFAs"** — closed as measurement artifact:
  steady-state VM=ASM=0.7 µs (sustained warmup); fast-bench gaps were
  JIT-cold asymmetry at few reps.
- **ASM scalar replacement of hot registers (regs → JVM locals)** — deferred
  backlog item. Ops after regopt+fixed-tags measure **max 1 op/transition,
  avg ≤0.5** on all capture patterns (DumpOps), so conversion saves ~1–2 ns
  on ~5 ops per match: 0% on scan rows, ~5–15% on capture rows. The right
  lever only if (a) VM capture parity (applyOps dispatch, 4–5 ns/char)
  becomes a goal, or (b) adversarial tag-dense patterns regopt can't compact
  appear. Both current ops emitters are array stores (`emitOpsInline` in
  INLINED mode; runner `applyOps` interpreter in DELEGATE).
- **reggie-class direct-call API** (no Matcher allocation, 4–13 ns floors) —
  out of scope; it's a different API contract.

## 8. The per-character contract (targets after R1–R5)

| regime | target | basis |
|---|---|---|
| fixed per-call cost (warm) | **~2 ns core / ~5 ns shim** | core measured 1.9 ns (matches, ParameterizedShortInputBench lineage); shim fat is R2/R3 |
| SIMD-skippable (literal, narrow first-char sets) | **0.3–0.4 ns/char** | measured 0.44 slope (indexOf intrinsic); re2j 0.39, jur 1.12 |
| general DFA stepping | **~2 ns/char** | serial load-after-load chain at L1 latency; re2's DFA sits at the same bound; lettersRu already 1.5 |
| capture walks | **2–2.5 ns/char ASM / 4–5 ns/char VM** | ops ≤1/char measured; ASM emits stores (~0.5 ns), VM dispatches (~2–3 ns) |
| sub-15-char inputs | **≤2x reggie floor** | ns/char is the wrong unit below the fixed cost; our 19–24 ns floor already beats jur's 33–37 |

Structural corners where 2 ns/char does not hold: VM capture dispatch
(declared, portability backend), adversarial tag-dense patterns (bounded by
~#branches/char; none in the 5.8M-case corpus exceed 1), first-call/cold-memo
(one-time). Rejected syntax (backrefs, lookaround) means no backtracking path
can reintroduce per-char blowup.

## 9. Harness inventory

- `scripts/bench-rebar.sh fast|accurate` → `RebarBench`: 110 scenarios × 5
  engines, rebar models, count-verified vs jur, ms/MB tables + compile table
  + geomeans + worst-lists (raw ns since the %.0fµs "0 µs" fix).
  fast ≈ 2 min triage; accurate ≈ 17 min full haystacks min-of-5.
- `ShortFindBench` (JMH): 10 slugs × 5 engines, ns/op single-shot,
  setup-verified engine agreement. `benchmarks/results-shortfind-jmh.txt`.
- `scripts/bench-regression.sh [--capture]` → QuickBench vs
  `benchmarks/baselines/`: per-op regression gate (15%) for landing work.
- Steady-state probes (min-of-N isolated JVMs) as ground truth for
  single-shape claims — fast-bench rows run hot vs probes under sustained
  load (documented in RebarBench header).
