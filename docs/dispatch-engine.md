# The dispatch-engine spin-off — rationale and design sketch

Decision record, 2026-11-04. tdfa-jvm stays what its
[vision](../README.md#vision) says: one algorithm, frozen, finished. The
regimes where that single-algorithm tradeoff loses — measured below — are
covered by a **separate project**: a reggie-style **dispatching engine**
that routes each pattern (and, at match time, each input regime) to the
cheapest strategy that can decide it, with this library as its general
lane. This page records the why, the evidence, and the sketch; the build
happens in the new repo, not here.

## The tradeoff this repo made — and keeps

The [vision](../README.md#vision) is explicit: reggie dispatches across
multiple engines for peak performance; we use one algorithm for everything.
That choice is load-bearing: it is why linearity is by construction, why
the runner could be frozen immutable, and why the complexity review could
delete every speculative cache ([priced here](search-dfa-memo-tradeoffs.md))
without an asterisk. None of that is re-litigated. The spin-off exists
because the same evidence that closed the gaps *inside* the algorithm
keeps hitting walls that are **architectural** — a second algorithm class
is the fix, and bolting it on here would undo the review program.

## The weak points, with committed numbers

All numbers from the artifacts in `benchmarks/` (see
[BENCHMARKS.md](../BENCHMARKS.md); engine-vs-engine ratios within one run
are the durable claims).

| # | regime | evidence (ns/op or µs) | root cause |
|---|---|---|---|
| W1 | short-input per-call floor | literal find: reggie 13.2 vs ASM 30.7; `(?i)sherlock`: 12.0 vs 32.9 | generated-class dispatch shell + no per-alternative literal lane (BENCHMARKS §1 fn 2 names this exactly) |
| W2 | short anchored class shapes | IPv4: reggie 12.7 vs ASM 40.0; `(\w+)\s+(\w+)`: 19.5 vs 64.9 | same; a needle/onepass-shaped shape pays the full DFA shell |
| W3 | dense no-match scans | email no-match: jur 345 vs ASM 913; class no-match (§4): 1341 vs 1502 | one needle strategy per pattern; fold-all-the-way chains (`(?i)kelvin`) have **no** needle — 2^k variants cannot be one `indexOf` |
| W4 | unicode wide-class 16 KB scans | ~2.1× jur post-[wide-dispatch-index](wide-dispatch-index.md); residue = the ladder's double pass (open TODO) | two passes (trigger sim → DFA) where one would do |
| W5 | compile latency | 291 µs VM / 1273 µs ASM vs jur 16.4 / re2j 28.0 | eager determinization + emission — the price of the linear-time guarantee; ruinous for one-shot patterns |

What is **not** a weak point, and must not become one: ReDoS linearity
(`(a+)+b`: 16.2 vs jur 1862.5), literal long scans (~0.5 ns/char,
`String.indexOf` parity), capture-dense walks (§5: ASM 0.3–0.7× VM), the
dictionary blowout win (64× jur). Note also that dispatch is not
uniformly better even where it applies — reggie loses `(a|b)*c` anchored
286.5 vs 17.0 — which is precisely why the spin-off keeps a TDFA as its
general lane instead of a zoo of heuristics.

## Why a separate project, not a lane here

- **Frozen-core invariants are poison for a dispatcher.** Dispatch wants
  runtime adaptivity: speculative lanes, per-call re-routing, promotion
  counters, tiered compilation. Everything the
  [complexity review](complexity-review.md) deleted here was a smaller
  version of exactly that machinery, deleted on evidence.
- **Bytecode floor mismatch.** The interesting scan lanes want the Vector
  API / SIMD intrinsics (Java 21+); this repo ships Java 8 bytecode for
  core+asm+facade+re2j, floor-pinned by CI.
- **Cadence.** This library is finished-then-frozen (TeX, not a platform);
  a dispatcher is an open-ended performance project. Different lifecycles.
- **Zero-risk reuse.** The spin-off consumes tdfa-jvm's published jars as
  its general lane and correctness oracle — this repo's API surface,
  semantics, and guarantees stay untouched (gated on the open
  *First Maven publish* TODO item).

## Sketch

Compile = **analysis only** (target ≤ 30 µs): literal prefix/suffix/infix
extraction as **needle sets** (fold-aware, generalizing this repo's single
chain), first-char discrimination sets, one-pass detection, capture plan,
anchoring/width bounds. Full TDFA compilation is **deferred and promoted in
the background** — W5's 1.3 ms cliff becomes latency-tolerable instead of
per-call.

Lanes (each small, table-driven, independently tested):

| lane | serves | notes |
|---|---|---|
| L0 needle / multi-needle scan | W1, W3, literal shapes | intrinsified `indexOf`; Aho-Corasick for literal alternations; fold needle *sets* for W3's fold-chains |
| L1 needle-gated TDFA walk | W2, capture shapes | this repo's runner behind a candidate enumerator (the PREFIX strategy generalized) |
| L2 one-pass | deterministic-per-position patterns | re2j-style; small, capture-capable |
| L3 bounded bit-state | tiny pattern × tiny input | re2j-style; visited-bitset cap keeps it linear — the ReDoS guarantee survives by construction, not hope |
| G general TDFA | everything else, incl. `(a\|b)*c` | tdfa-jvm jar, VM tier first, ASM when hot |

Dispatcher: static route from the analysis, dynamic re-route by input
length class and hit density (dense-candidate budgets, like this repo's
PREFIX_WALK_BUDGET), promotion/demotion with hysteresis. Dispatch overhead
budget: ~5 ns/call; every lane must be **oracle-checked identical** to the
G lane on every input — the differential methodology is this repo's
`LayeredComparator` / parity harness, reimplemented there, with rebar as a
shared corpus.

## Success criteria (initial; re-base against fresh artifacts)

| shape | target | from |
|---|---|---|
| literal find, short | ≤ 15 ns | 30.7 (reggie 13.2) |
| `(?i)sherlock`, short | ≤ 15 ns | 32.9 (reggie 12.0) |
| anchored IPv4 | ≤ 20 ns | 40.0 (reggie 12.7) |
| email no-match, short | ≤ jur | 913 vs 345 |
| fold-chain no-match 16 KB | ≤ 100 ns/op equiv. | no needle today (§4) |
| compile → first match | ≤ 50 µs one-shot mode | 291–1273 µs eager |
| `(a\|b)*c` anchored (G-lane guard) | ≤ 20 ns — no regression vs pure TDFA | 17.0 |
| ReDoS `(a+)+b` | linear, ≤ 2× TDFA | 16.2 |

## Sequencing and relationship

1. *First Maven publish* here (open TODO) — the spin-off consumes artifacts,
   never source forks.
2. Spin-off repo (planned tagmaton namespace, per the namespace-move TODO),
   Apache-2.0, Java 21+ floor; CI mirrors this repo's discipline: differential
   parity vs tdfa-jvm G lane + jur, rebar corpus, JMH landing gates.
3. This repo: no internal changes anticipated for the spin-off. If a gap
   surfaces that would require one (e.g. a runner hook for candidate
   enumeration), it gets its own decision record here first.

## Risks

- **Lane disagreement** — the fatal one. Mitigation: every lane
  oracle-checked against the G lane and jur in CI, fuzz soak from day one
  (this repo's `tests/parity` harness is the template and the bar).
- **Megamorphic dispatch sites** polluting JIT profiles — per-lane
  monomorphic entry points, JMH-verified, dispatch budget enforced.
- **N-engine maintenance cost** — lanes stay small because the TDFA carries
  every hard shape; a lane that grows a corner-case archive gets deleted,
  not patched (same rule that governs this repo).
- **Semantics drift between repos** — the spin-off's surface re-checked
  against this repo's jars in CI, so drift surfaces as a failing diff, not
  a user bug.
