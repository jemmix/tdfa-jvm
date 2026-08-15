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

## 5. Short inputs — before and after the parity round (JMH ShortFindBench, ns/op)

After the short-input round (commits d192462, e949759):

| slug | jur | re2j | reggie | vm | asm | vm/jur |
|---|---|---|---|---|---|---|
| litFind | 94 | 215 | 16 | **26** | 49 | 0.27x |
| caseiLit `(?i)sherlock` | **37** | 350 | 15 | 49 | 49 | 1.31x |
| wordB `\bword\b` | 202 | 632 | 50 | **89** | 91 | 0.44x |
| wordUnicodeCls `\p{L}{2,}` | **36** | 358 | 83 | 83 | 87 | 2.28x |
| lettersRu | **74** | 316 | 4 | 106 | 107 | 1.44x |
| boundedSpan `"[^"]{5,20}"` | **45** | 571 | 34 | 82 | 84 | 1.82x |
| ipExtract | 188 | 1512 | 127 | **128** | 133 | 0.68x |
| alternation | 143 | 527 | 562 | **67** | 67 | 0.47x |
| emailNoMatch | 437 | 1965 | 242 | **703** | 744 | 1.61x |
| litNoMatch | 46 | 43 | 12 | **18** | 19 | 0.39x |
| **geomean** | | | | | | **vm 0.85x / asm 0.93x** |

Pre-round geomean was vm/jur **1.48x**; per-row then→now: caseiLit
5.3x→1.31x, wordB 1.6x→0.44x, wordUnicodeCls 5.3x→2.28x, boundedSpan
7.8x→1.82x, ipExtract 3.1x→0.68x, emailNoMatch 1.85x→1.61x. vs re2j:
**0.18x geomean, decisive at every input size.** FloorProbe attribution
correction (measured): Matcher allocation = 0.0 ns delta fresh-vs-reset
(escape analysis; jur pays 7.5 ns), no string copying, vm intercept ~6 ns
on the shim find path (the core anchored path is ~2 ns and can inline to
~0) — the short-input losses were sim/walk machinery, which is what the
round removed.

What landed (see `TODO.md` rebar section for the full table):
- **R1 first-char-set candidate scan** — startBits long[1024] over UTF-16
  units from the start state's outgoing ranges; ≤64-char inputs do
  bit-test + exact-walk in find/extract/leftmostStart instead of the
  raw-scan simulation and the budgeted origin sim. Built only when the
  start state cannot accept (empty-match completeness). Adaptive boolean
  pre-filter after 3 failed extract walks (dense-candidate no-match
  shapes: emailNoMatch 634→1143 without the filter, 498 with it).
- **R5 word-flag trim** — needsWordFlags (stop-table variant equality +
  mask scan) gates both word-class checks in positionFlags; the class
  itself became a BMP bitset. wordB 276→89.
- **R3 ASM short-input delegation** — generated match() forwards to
  runner.match ≤64 chars; the +25 ns asm/vm constant is gone
  (alternation 299→72).
- **Flat walk dispatch** — extractFrom/runStringMatchFrom use
  asciiRangeFlat wherever ranges are disjoint (priority only matters
  when they overlap). This is what took shim matches() from 51.6 ns to
  9–64 ns.
- **R4 lazy BMP walk blocks** — per-state 512-cp blocks for c ≥ 256,
  volatile copy-on-grow publication (JMM-correct lazy init), 64-block
  cap with binary-search fallback, disjoint DFAs only (non-disjoint
  priority needs the mask-aware walk-back). wordUnicodeCls 135→83.

## 6. Remaining gaps, root-caused and sized

| # | cluster | evidence | root cause | fix | effort |
|---|---|---|---|---|---|
| R6 | short-input residual rows: wordUnicodeCls 2.28x, boundedSpan 1.82x, emailNoMatch 1.61x, lettersRu 1.44x, caseiLit 1.31x | JMH | per-candidate walk + Matcher/MatchHolder layers vs jur's lazy NFA on inputs where the whole match is ~10 walk steps | accept (all rows beat re2j 3–7x), or regs scalar replacement + leaner extract; diminishing | S–M, deferred |
| R6′ | ~~asm = vm + 1–3% on short inputs~~ | — | **closed by the kernel refactor**: one strategy brain + generated own-loop leaves + generated Pattern/Matcher tier; asm/vm geomean 0.89x, no warm regressions | done (2026-08-15) |
| R2′ | shim `matches()` residual: 9–64 ns vs jur 6–26 | FloorProbe-style probe | MatchHolder + MatchResult + eager register ops in the anchored extract | only matters if matches() becomes hot; route is regs scalar replacement | M, deferred |
| R7 | rebar unicode scan tail (all-russian 3.9x, letters-ru 4.7x, casei scans 3.2–3.3x, quotes-bounded 4.7x vs jur) | accurate | haystack-scale walks; candidate scan is ≤64-char-only by design; scan itself already beats re2j | re-measure after this round (walk blocks help the extract tail); possibly extend candidate scan threshold | — |
| R8 | compile time (dictionary 2.2s, i1095 0.9s) | accurate | eager determinization + minimization | separate roadmap (lazy DFA build, better minimization) | M–L |

## 7. Deliberately not done (with reasons)

- **W1c for scan loops** — measured unnecessary after W1b+W2' (the trigger's
  blocks already give O(1) BMP dispatch for scanning; long-russian beats jur).
  The walk-path variant WAS later built as R4 (lazy per-state walk blocks,
  short-input round) — see §5.
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

## 8. The per-character contract (post-round status)

| regime | target | status (measured) |
|---|---|---|
| fixed per-call cost (warm) | ~2 ns core / ~5 ns shim | core anchored ~2 ns (inlines to ~0); shim find ~6 ns intercept; shim matches() 9–64 ns (R2′ residual: holder/result + eager ops) |
| SIMD-skippable (literal, narrow first-char sets) | 0.3–0.4 ns/char | 0.44 measured (indexOf intrinsic); re2j 0.39, jur 1.12 |
| general DFA stepping | ~2 ns/char | lettersRu 106/45 ≈ 2.3; candidate scan skips non-candidates entirely (bit test) |
| capture walks | 2–2.5 ns/char ASM / 4–5 ns/char VM | ipExtract 128 ns/13-char ≈ 10 ns/char total incl. layers — walk itself ~2–3; VM dispatch corner stands |
| sub-15-char inputs | ≤2x reggie floor | alternation 67 vs reggie 562; litNoMatch 18 vs jur 46 |

Structural corners where 2 ns/char does not hold: VM capture dispatch
(declared, portability backend), adversarial tag-dense patterns (bounded by
~#branches/char; none in the 5.8M-case corpus exceed 1), first-call/cold-memo
(one-time). Rejected syntax (backrefs, lookaround) means no backtracking path
can reintroduce per-char blowup.

## 9a. Kernel refactor round (2026-08-15, commits 858b2f6..bab3fde)

P0 strategy trace hook (`TdfaRunner.Strategy`, `-Dtdfa.trace.strategy`) →
P1 ASM emission = shared strategy + own-loop leaves (deleted: chars cache,
SHORT_DELEGATE_LEN, O(n²) restart, TABLE_SCAN mode) → P2 Pattern/Matcher
interfaces + `EngineFactory.generatesPerPattern()` → P3 generated
Regex/Pattern/Matcher under ASM (per-pattern classloader, writeReplace
serialization proxy) → P4 benchmarks.

| metric | pre-kernel | post-kernel |
|---|---|---|
| ShortFindBench asm/jur geomean | 0.93x | **0.77x** (vm 0.86x) |
| asm/vm geomean | ~1.00x | **0.89x** (lettersRu 0.42x, ipExtract 0.74x) |
| LogExtractMacro (200k lines) | — | asm = vm warm (0.95–1.00x), both 3–4x re2j; jur wins 3/4 rows via literal-prefix search (deferred prefix-acceleration family) |
| compile latency | — | vm 52 µs / asm 301 µs / jur 4.5 µs (unchanged in kind) |
| metaspace | — | generated patterns unload with their loader; 135 B/compile residual |
| drift protection | two ladders (litFind bug class) | one strategy brain + emitted transcription + strategy-conformance CI test |

ASM's selling point, post-kernel: faster than VM on walk/capture-dense
shapes (0.42–0.87x) and never measurably slower warm; the cost is compile
latency (+250 µs/pattern) and per-pattern classloading. See
`benchmarks/results-shortfind-jmh.txt` and `benchmarks/results-logextract-macro.txt`.

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
