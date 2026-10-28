# Runner immutability — frozen artifacts, honest caches

> **Program complete (#93–#103, Oct 2026).** The end state: every
> field on a compiled artifact and its engines is `final` — both
> match-time memos were deleted on measured evidence after their
> honest-cache conversions made the fallbacks provably pure
> ([pricing](search-dfa-memo-tradeoffs.md)); the only mutable match-time
> state is the sanctioned per-call carriers; the gate below enforces it
> permanently. This page is the decision record.

The match-time runner carries lazy mutable state on shared artifacts.
The 2026-10-24 jur soak caught it misbehaving once in 168.9 M cases
([finding 1](fuzz-campaigns-2026-10-24.md) — a forced rung disagreeing
with the natural ladder, unreproducible on a cold artifact), and the
audit that followed found the trade had never been made explicitly.
This doc is the decision: **audit every field; make most
immutable/precomputed; convert the genuinely lazy rest into an honest
cache built on pure functions — done right.**

## Ground truth — where the mutability actually lives

The original audit's headline ("~49 non-final private fields on
`TdfaRunner`") does not survive contact with the tree. A reflection
pass over `core.dfa`/`core.engine`/`asm` finds the runner body itself
already frozen: **every `TdfaRunner` instance field is `final`**. The
real mutable surface on shared artifacts is five clusters, ~20 fields,
living in the objects the runner holds — which is exactly why the
finding was warmth-shaped: none of it is visible until a second call
touches the same artifact. The inventory, verified field-by-field
against `main` at #91:

| Group | Where | Fields (all non-final) | Sharing |
|---|---|---|---|
| G1 — search-DFA memo | `SearchDfa` | `capped` (volatile), `rowWordsArr`, `rowBlockIdsArr` (volatile COW **+ in-place cell fill**), `blocksArr`, `rowById`/`blockById`/`probe` (`Wrapper` field reassignment, under `lock`) | per-runner, cross-thread |
| G2 — walk-block memo | `WalkIndex` | `walkBlocksArr` (volatile COW), `walkBlockCount`, `chargedBytes` (guarded), `walkBlockIdx` cells **+ in-place `pub[b]` writes** | per-runner, cross-thread |
| G3 — artifact-derived lazy caches | `Tdfa` | `stopMaskTableCache` (volatile), `posFlagDepsCache`, `absBeginBoundCache` (benign-race) | per-artifact, cross-thread |
| G4 — emitted-tier residue | `TdfaAsmBackend` | generated `WORD_RANGES` (`private static`, written in emitted `<init>`) | per-emitted-class |
| G5 — test/debug statics | `TdfaRunner` | `TRACE`, `anyForced` (volatile statics, runtime settable); `TRACE_BUF`, `FORCED` (final `ThreadLocal` refs) | JVM-global |

The remaining mutability in scope is **sanctioned by design** and needs
a disposition, not a fix: per-call carriers (`Matcher`'s seven
protected fields, `MatchScratch`'s grow-only buffers, `TagTree`,
`findAll`'s iterator) and compile-time-only builders
(`DfaStateBuilder`, `Range.ops` under the CFG flush) are never shared
across threads — the gate below names them so the classification is
checkable, not just traditional.

## The invariant

Two sentences, enforced mechanically once this lands:

1. **Artifacts are frozen at construction.** Every field is `final`
   (or lives in a final container), every published object fully built
   before it becomes reachable. No field of a shared artifact is ever
   written after construction — not by the owner thread, not under a
   lock, not "safely".
2. **Caches are unobservable.** A cached value is a pure function of
   (artifact, key): warm == cold == concurrent, byte-for-byte, forever.
   Duplicate computation is legal; wrong or partial publication is not;
   eviction changes performance, never answers.

Everything below is machinery for those two sentences.

## Load-bearing audit: what the lazy state actually buys

The only laziness with real value is the **search-DFA memo**
(`SearchDfa`): a lazy determinization of the unanchored-search automaton
(`.*?` + pattern) whose rows are interned by content — the input only
chooses which rows get visited. It engages above `SDFA_MIN_WINDOW`
(2 048 chars) and past its caps falls back to the kill-point-aware
unmemoized simulation. The audit's conclusions:

- **Correctness: zero load-bearing.** The unmemoized fallback is
  first-class and budget-gated by design; inputs under 2 KB — the
  entire common case — never touch the memo at all.
- **Performance: load-bearing in exactly one regime** — unanchored
  `find()` over multi-KB inputs, where it converts a per-char bitset
  simulation step into ~3 array loads.
- **Precomputing it wholesale is the wrong trade.** Eager determinization
  risks state blowup (the reason every RE2-lineage engine does this
  lazily), multiplies the µs–ms compile cost for every pattern, and
  turns a lazily-touched runtime budget (default 16 MiB/pattern) into a
  static per-pattern cost. Rejected — the memo stays lazy, but becomes
  *honest*.

**Update (post-W2b):** the regime proved narrower than this audit
assumed — the memo engaged only for codepoint-lane wide-class patterns
over multi-KB inputs (bounded-ASCII patterns served raw at the same
speed), and the priced no-memo worst case was a flat ~63 ns/ch on every
measured shape. The memo was **deleted**; the full pricing and the
restoration criteria live in
[search-dfa-memo-tradeoffs.md](search-dfa-memo-tradeoffs.md). The
program's state-discipline work was the precondition: W2 made the
fallback provably pure, so deletion became a one-rung change. The
walk-block memo (G2) followed the same path one work item later (W3b):
measured a wash at best and 13–31 % slower than its own fallback on
few-range classes.

The walk-block memo (G2) is the same argument one size smaller: lazily
materialized 512-codepoint dispatch blocks for wide-class walks, with a
binary-search fallback that is always correct.

## Dispositions — what happens to each group

| Group | Disposition | Why |
|---|---|---|
| G1 `SearchDfa` | ~~Honest cache~~ → **deleted outright** — the measured regime (codepoint-lane wide-class multi-KB scans, 2–7×) did not justify the keep-cost; the trigger rung serves via the raw scan ([tradeoffs](search-dfa-memo-tradeoffs.md), #97) | The honest-cache conversion (W2, #95) made the deletion safe: pure fallback, no state to reason about |
| G2 `WalkIndex` | ~~Honest shape, flattened~~ → **deleted** — measured a wash at best (within noise on many-range wide-class walks) and 13–31 % *slower* than the binary search on few-range classes, with zero warmth value ([tradeoffs](search-dfa-memo-tradeoffs.md), #98) | The honest-cache conversion (W3, #96) made deletion safe; dispatch keeps the exact first-class fallback |
| G3 `Tdfa` caches | **Precompute at construction**; delete the three fields — *landed (W4)* | Pure functions of final tables, each already computed on every runner/engine construction anyway — the "most precomputed" category, literally |
| G4 `WORD_RANGES` | **`static final`** via `ACC_FINAL` (emitted classes assign static finals in `<init>` via PUTSTATIC across JDK 8–26 — the `ENTRY_MASK` pattern) | The one non-final emitted field; the second-instance rebind hazard dies |
| G5 statics | **Stay, on the documented allowlist** the finality gate enforces: `TRACE`/`anyForced` (test instruments, off the answer path), `TRACE_BUF`/`FORCED` (final containers) | Conformance/fuzz hooks; WTRACE-style freeze-at-init already covers the compile knobs |
| Per-call carriers | **None** — named as the sanctioned destination in the gate (per-`Matcher`, contractually single-threaded) | This is where match-time state *should* live |
| Compile-time builders | **Out of the match-time invariant's scope** — gate allowlist, "compile-time only, never reachable from a compiled Pattern" | `DfaStateBuilder`, `Range.ops`, emitter counters die at compile |

Expected end state: a runner cluster with **zero** non-final fields
outside the honest-cache containers (and their clock bookkeeping), the
containers themselves final, the statics allowlist exactly four entries
with justifications.

## The honest cache — done right

One cache shape for the remaining lazy values. *(Historical: `SearchDfa`
was its first application (W2) and `WalkIndex` its last (W3) — both
caches were subsequently deleted (W2b, W3b), so the shape currently has
zero instances; it is recorded here as the mandated form for any future
cache, with the tripwires unchanged.)* Concretely, as `SearchDfa` had it:

- **Immutable entries.** A `Row` is `{final int[] words; final
  AtomicReferenceArray<Block> cells(128); final boolean accept}` —
  frozen at build. A `Block` is `{final Row[] targets(512)}` (`null` =
  kill), frozen at build then published into its row's cell. No
  in-place cell mutation after publish — the copy-on-write arrays and
  locked cell fill-in are deleted, and with them the "stale snapshot
  degrades to the locked path" reasoning.
- **Pure keys, pure values.** Rows are interned by content in a
  `ConcurrentHashMap` (the key is the live-set bitset; the value is a
  deterministic function of (artifact, key)). A compute/publish race
  produces two equal candidates; `putIfAbsent` keeps one and the loser's
  charge is refunded. Duplicate computation is the benign cost of lock
  freedom. Cell publication uses `AtomicReferenceArray.set` — a fully
  built immutable object behind a volatile, nothing subtler.
- **Lock-free reads.** The per-char path is one volatile cell load plus
  one array load — no monitor, no synchronized read path, no volatile
  array dance, no id indirection (blocks reference rows directly;
  ids stop existing).
- **Honest accounting.** One `AtomicLong` charge per memo (row weight +
  block weight, per the existing weight model); charge-before-publish,
  refund-on-race-loss. Over budget: the row is not interned / the block
  is not built, and the *scan* continues via the exact-live-set raw
  fallback — today's `v == -1` semantics, triggered by charge instead of
  a sticky global `capped` flag that never resets. **Eviction: decided
  against for now** (charge-bound instead) — recency tracking needs
  mutable bits beside the frozen entries, a side table that costs more
  than it reclaims at these sizes, and eviction is only observable as
  warmth under budget churn, a regime no soak has evidenced; the bench
  and the tripwires own that claim, and the alternative stays listed.
- **Both tiers.** The ASM tier embeds `new TdfaRunner(tdfa, memoBudget)`
  in emitted code, so both backends share the one memo shape; the
  emitted classes shed their locking machinery (G4 is the only emitted
  mutable field, and it goes final).

What this deletes outright: the cross-thread "safe:" argument (there is
nothing left to argue about), the warm/cold divergence class (finding 1
becomes unrepresentable rather than merely absent), and the audit burden
of reasoning about interleavings on the match path.

## Alternatives considered

- **Eager precomputation of everything** — rejected (state blowup,
  compile cost, static memory; the library's pitch is fast compile of
  every pattern with a frozen lazily-touched budget).
- **Charge-bound, no eviction** (degrade-per-structure instead of
  reclaiming) — strictly simpler; kept as the fallback if the clock
  shows bugs or the bench shows the sweep costing real time. Eviction
  is only observable as warmth, and only budget-churn inputs can tell.
- **Thread-confined per-Matcher memo** — viable, strictly simpler, loses
  warmth across Matchers/threads; kept as the fallback if the honest
  cache misses the bench gate.
- **Delete the memo** (always run the unmemoized simulation) — gated by
  `scripts/bench-regression.sh` (15 % rule) on the long-input rows; the
  TODO "long-input scans" benchmark work decides whether the simpler
  world passes. If it passes, prefer deletion over any cache.
  **Decided 2026-10-27: deleted** — the regime pricing and restoration
  criteria are in [search-dfa-memo-tradeoffs.md](search-dfa-memo-tradeoffs.md).

## Enforcement — the tripwires that keep it honest

1. **Finality gate** (plain JUnit, zero deps): reflection scan of
   `core.dfa`/`core.engine` — every non-final instance field must be a
   sanctioned per-call carrier, live in a gate-named honest-cache
   container, or sit on the memo allowlist with a purity argument;
   every non-final static on the allowlist with a justification line
   (G5's four entries). The allowlist only ever shrinks — each WBS
   chunk below deletes its entries. Rides `./gradlew check`.
2. **Determinism double-run** in the differential fuzzer: after the
   normal protocol, re-run every probe on the same artifact and
   byte-compare; any diff records as `MEMO_STATE_MISMATCH`. Warm ==
   cold becomes a tested property at fuzz scale, not a hope.
3. **Warm replay mode** for `fuzz.one` (run the batch, then the case) so
   warmth-dependent records replay — the gap that stranded finding 1.
4. **Sampled memo recompute** (`-Dtdfa.memo.audit=1`): 1-in-N cache
   reads recompute the value and compare against the published entry —
   a corrupt warm row fails instantly, with the row identified. Rides
   the concurrency test and any soak that opts in. *(Retired with the
   search-DFA memo it audited — W2b; the hook returns with any restored
   cache.)*
5. **Concurrency hammer + freeze watchdog** (replacing the originally
   sketched jcstress suite): N threads scan one shared `Pattern` on
   multi-KB inputs, byte-compared thread-vs-thread and vs a cold
   artifact twin; before/after, every final table of the artifact is
   deep-hashed — any post-construction mutation (including stray
   out-of-bounds writes) trips. jcstress exists to stress a publication
   *protocol*; the honest cache has none left (final fields + CHM/ARA
   publication are guarantees, not arguments). If a protocol ever
   returns, jcstress returns with it.

## Program record

Executed as a WBS of PR-sized chunks (tripwires first, heavier
refactors next), 2026-10-26 → 2026-10-28, all through the PR pipeline;
the itemized WBS is retired per house rule (open work only, history in
git) — the PR trail: **#93** doc/groups/dispositions · **#94**
tripwires + finding 1 de-mystified · **#95/#97** SearchDfa honest cache
→ deleted on pricing · **#96/#98** WalkIndex honest cache → deleted on
pricing · **#99** `Tdfa` precompute + emitted finality · **#102** gate
sealed · **#103** remnant cleanup (budget split, weights, vestigial
field). The full measurement record lives in
[search-dfa-memo-tradeoffs.md](search-dfa-memo-tradeoffs.md).

The end state exceeded the original plan: instead of "most immutable,
the rest in honest caches", the measured evidence deleted both caches
outright — the artifacts are frozen absolutely (every field final, no
atomics, no containers), the only mutable match-time state is the
sanctioned per-call carriers, and the tripwires (gate, hammer, freeze
watchdog, determinism double-run, warm replay) stay as the permanent
net. What the honest-cache chapter above preserves is the mandated
SHAPE for any future cache, not a live instance.

The tripwires went first by design: they converted this design from
prose into tests before the risky refactors began. The alternatives
section's deletion branch is the one that fired — the audit and gates
remain. One open caveat: the full-JMH record run at close was
invalidated by host contention (external load ~130); rerun on a quiet
host before the next baseline re-capture.

## Non-goals

- No behavior changes: protocols, budgets, and public API are untouched;
  this is a state-discipline refactor with identical observable answers.
- No new concurrency primitives beyond `ConcurrentHashMap`,
  `AtomicReferenceArray`, `AtomicLong`, and (on the eviction write path
  only) one monitor — no exotic structures until the bench demands them.
