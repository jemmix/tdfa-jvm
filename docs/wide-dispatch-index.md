# The unicode wide-class dispatch fix — closing the README's known gap

Date: 2026-11-03 · the work item that closed the README's "unicode
wide-class scan" known gap (`java.util.regex` 2–4× ahead on `\p{L}`,
unicode-`\w`-shaped scans), without restoring either deleted memo
([search-dfa-memo-tradeoffs.md](search-dfa-memo-tradeoffs.md)).

## Diagnosis

Strategy traces over the gap shapes showed the ladder was never the
problem — dispatch was. Wide-class DFAs are tiny in states (2–5) but
carry ~680–800 range entries per state (`\p{L}` ≈ 684, unicode `\w` ≈
804, `[\p{L}\p{N}]` ≈ 779 — measured on v17.0 tables), and every
match-time consumer paid a ~10-compare binary search per consumed
codepoint past the Latin-1 flat tables:

- the walks (`tryStartFast` bailed to `extractFrom` at the first
  non-Latin-1 char; every walk step binary-searched),
- the origin simulation (searched for every codepoint ≥ 128),
- the raw scan (searched for EVERY codepoint, ASCII included).

That is the whole gap: jur's per-char cost on these shapes is a
first-char bitmap probe (~5–9 ns/char) while ours was a mispredicty
search per live state per char (measured 37–63 ns/char on the scan
regimes, 2026-10-29 probe, pre-fix). The search-DFA memo used to hide
this for the scan paths (its lazily determinized rows gave one-load
dispatch); deleting it (#97) exposed the walks, which the memo never
served anyway.

## The fix: the wide dispatch index

`WideDispatch` (core, package `core.dfa`) — an eager, immutable
two-level map from `(state, codepoint)` to range-entry index:

- **L1** — one 1088-entry row per indexed state (1024-codepoint blocks
  over the whole space, supplementary planes included); a cell is either
  *uniform* (the whole block resolves to one entry — the overwhelming
  majority: entire scripts sit inside one range) or points to a
  **boundary block**: a tiny sorted slice list for the handful of blocks
  that straddle range edges.
- **Content dedup** — the near-identical class rows of a wide DFA
  (`\p{L}{2,}` has three states with the same 684-range partition)
  collapse to one shared block set; `\p{L}{2,}` lands near 25 KB,
  `[\p{L}\p{N}]{3,}:` near 40 KB.
- **Exactness gate** — built only when the artifact's ranges are
  pairwise disjoint (the same property `asciiRangeFlat` already relies
  on): at most one entry contains any codepoint, so the index resolves
  precisely the entry the binary search finds; required masks, dead
  markers, and ops stay in the entries, read exactly as before.
  Overlapping (mask-priority) artifacts keep the search.
- **Caps** — states under 8 entries are not indexed (the search is ≤ 3
  compares); ≤ 64 indexed states; ≤ 512 KiB all-or-nothing. Per the r11
  scope decision these are engine-tier construction-time tables charged
  as disclosed constants, like the Latin-1 tiers, not against the
  runtime budget.

Not a memo: nothing lazy, mutable, shared-mutable, capped-at-runtime, or
invalidated — a pure function of the artifact, frozen at construction.
The runner cluster stays absolutely frozen (#98's invariant untouched).

Wired into every dispatch site: `extractFrom`, `wholeWalk` (non-whole
states), `tryStartFast` (which now walks non-Latin-1 text inline, pair
advance and all, instead of bailing to the generic walk), `matchFromFast`,
`runStringAnchored`, `runStringMatchFrom`, `runGeneric`, the origin
simulation, and the raw scan's per-state step (`scanStep`, the one
definition the seed-only path and interior-unit seeds share).

Two structural riders fell out of the same root cause:

- **Seed-only sim steps**: on sparse-candidate scans the live set is
  exactly the re-seeded start state almost every char (every non-class
  char kills). Single-word DFAs now track that in one compare and step
  with ONE dispatch instead of the bitset iteration (`rawScan`,
  `multiStateLeftmostStart`) — the same products (targets ∪ reseed,
  kill ⇒ W advance), same answers.
- Both backends: wide-class artifacts exceed the ASM inline budget
  (~700 entries × ~25 B/state), so they compile to DELEGATE classes —
  fixing `TdfaRunner` fixes ASM and VM together. The ladder, its
  strategy traces, and the emitted/interpreted conformance are
  unchanged.

## Numbers (Apple M-series, JDK 26, ns/char, interleaved min-of-5)

Pre-fix numbers from the same probe on the pre-change build (2026-10-29,
quiet machine); post-fix 2026-11-03.

| shape (16 KB unless noted) | jur | VM before → after | ASM before → after |
|---|---:|---|---|
| `\p{L}{2,}` short Cyrillic find (45 units) | 1.6 | 7.4 → **2.0** | 9.2 → **2.0** |
| `\p{L}{2,}x` match at end | 5.1 | 37.2 → **11.1** | 73.9 → **11.2** |
| `\p{L}{2,}x` no-match, sparse letters | 5.9 | ~14 → **12.9** | ~14 → **12.7** |
| `\p{L}{2,}x` no-match, dense letters | 43 674 (quadratic) | ~50 → **51.5** | ~50 → **51.4** |
| `\p{L}{2,}` non-BMP walk (match at 0) | 3.2 | 20.9 → **8.4** | 23.1 → **8.3** |
| `\p{L}{2,}x` no-match, non-BMP | 20 880 (quadratic) | 118 → **49.1** | 125 → **49.1** |
| unicode-`\w`-class no-match (dense) | 18 887 (quadratic) | — → **44.5** | — → **44.3** |

Reading: the README's declared 2–4× gap rows land at 1.2–2.6× (walk
rows ~1.2×, non-BMP walks 2.6×, scan rows ~2.1×); the shapes where jur
has no first-char shortcut stay wins by two to three orders of
magnitude. The remaining ~2× on the scan rows is NOT dispatch — it is
the ladder's pre-existing double pass (the budgeted origin sim scans
4 096 chars, then the trigger raw scan re-scans from `from`; one
live-set handoff between them would collapse it to a single pass) —
filed as the TODO item below this fix.

## Why not restore the memo

The memo priced 2–7× in exactly one regime (codepoint-lane wide-class
scans of multi-KB inputs) at the cost of a lazily built, thread-safe,
capped cache — and it never served the walks. The wide index serves
walks AND both simulations at one-flat-load cost, eagerly, with no
invalidation — strictly more regime coverage for none of the memo's
reasoning burden. Both restoration criteria (unicode-heavy long-input
rows; jur-class competition there) are answered by a structure the
frozen-at-construction invariant already accommodates.

## Post-handoff addendum (2026-11-06)

The double pass this doc priced as the scan rows' residue is gone: the
budgeted origin sim now hands its live set and scan position to the
trigger scan at budget exhaustion (one pass over the budget window, not
two; the parked-state continuation rides `rawScan`'s existing
`liveIn`/`wIn` parameters, and the fused `originSimRungTop` hook serves
the VM ladder, the forced-rung path, and the emitted ASM ladder from one
definition). Same-session interleaved probe (16 KB, min-of-5, ns/char,
before = origin/main, after = the handoff):

| shape | jur | VM before → after | ASM before → after |
|---|---:|---|---|
| `\p{L}{2,}x` no-match, sparse letters | 11.5 | 23.0 → **20.1** | 21.7 → **20.2** |
| `\p{L}{2,}x` no-match, dense letters | 104 621 (quadratic) | 62.8 → **43.2** | 63.0 → **43.2** |
| `\p{L}{2,}x` no-match, dense words | 39.9 | 45.4 → **28.0** | 45.5 → **28.0** |
| `\p{L}{2,}x` no-match, non-BMP | 25 111 (quadratic) | 32.9 → **22.1** | 32.7 → **22.1** |
| unicode-`\w` no-match, dense | 176 312 (quadratic) | 73.5 → **46.5** | 69.5 → **46.4** |
| `\p{L}{2,}x` match at end | 5.9 | 100.2 → **78.7** | 100.6 → **78.7** |

Reading: the scan rows the README's residue note priced at ~2.1× jur now
sit at ~1.0–1.7× (dense-words 0.93× — ahead of jur; sparse 1.65×; the
quadratic-jur shapes stay 3–4 orders ahead). Correctness: the fuzz
cross-rung differential (224 648 cases, both lanes, forced-rung
ORIGIN_SIM + stretch) recorded zero cross-rung/cross-routing
divergences, and `SimTriggerHandoffTest` pins the handoff against the
pre-handoff composition over an exhaustive budget sweep.

## Tests

`WideDispatchTest` — exhaustive index-vs-binary-search agreement over
the wide corpus (every entry edge ±1, touched-block edges ±1, full BMP
sweeps, supplementary windows), synthetic boundary/dead-marker/cap
artifacts, non-disjoint rejection, and the gap shapes pinned against the
live `java.util.regex` oracle across both backends and every forceable
rung. The existing cross-rung differential fuzzers and the
strategy-conformance suite cover the rest by construction (no ladder
rung changed).
