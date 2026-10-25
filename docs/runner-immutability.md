# Runner immutability — frozen artifacts, honest caches

The match-time runner carries lazy mutable state on shared artifacts.
The 2026-10-24 jur soak caught it misbehaving once in 168.9 M cases
([finding 1](fuzz-campaigns-2026-10-24.md) — a forced rung disagreeing
with the natural ladder, unreproducible on a cold artifact), and the
audit that followed found the trade had never been made explicitly:
`TdfaRunner` has ~49 non-final private fields beside its 35 final ones,
and the search-DFA memo's cross-thread safety lives in a comment
("shared across threads matching the same Pattern (safe: …)") rather
than in a checkable invariant. This doc is the decision: **audit every
field; make most immutable/precomputed; convert the genuinely lazy rest
into an honest LRU cache built on pure functions — done right.**

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

## Target state

### 1. Field audit — every non-final field gets a category

A reflection gate (below) fails the build on unclassified mutability.
The categories, in the order they should be applied:

| Category | Disposition | Examples |
|---|---|---|
| Frozen tables | Make `final`; verify no write after construction | transition tables, `finalOpsByMask`, ascii dispatch |
| Compile-derivable, cheap | **Precompute at construction** (the "most immutable/precomputed" of the program) | trigger/kill-point statics, derived caps, flat op arrays |
| Per-call / per-Matcher carriers | Move to the `Matcher` (already contractually single-threaded) | pooled regs, scratch trees |
| Genuinely lazy, pattern+input-visitation-derived | The honest LRU below | search-DFA rows and blocks |
| Statics | `final`, or an explicit documented allowlist | `WTRACE`, `anyForced` (document the never-reset latch) |

Expected end state: a runner with **zero** non-final fields outside the
LRU container, and the LRU container itself final.

### 2. The honest LRU — done right

One cache shape for every remaining lazy value, `SearchDfa` first:

- **Immutable entries.** A row/block is built completely, then published
  as a frozen object. No in-place cell mutation after publish — the
  current copy-on-write arrays with locked cell fill-in are deleted, and
  with them the "stale snapshot degrades to the locked path" reasoning.
- **Pure keys, pure values.** The key is content (the intern key), the
  value a deterministic function of (artifact, key). A compute/publish
  race produces two equal candidates; `putIfAbsent` keeps one. Duplicate
  computation is the benign cost of lock freedom.
- **Lock-free reads.** `ConcurrentHashMap` lookup; no synchronized read
  path, no volatile array dances, nothing to get subtly wrong under
  publication.
- **Honest accounting.** Size is bounded by the existing weight model
  (`Budgets.runtimeMemoryBytes()`); eviction is LRU (a second-chance/
  clock list maintained on the write path is acceptable if it keeps
  reads lock-free; a strict LRU list under a striped lock if the bench
  shows clock thrashing). Eviction must never be observable — pure
  values make that free.
- **Both tiers.** The ASM backend embeds its own memo budget in emitted
  code; it inherits the same shape, and the emitted classes shed their
  locking machinery.

What this deletes outright: the cross-thread "safe:" argument (there is
nothing left to argue about), the warm/cold divergence class (finding 1
becomes unrepresentable rather than merely absent), and the audit burden
of reasoning about interleavings on the match path.

## Alternatives considered

- **Eager precomputation of everything** — rejected (state blowup,
  compile cost, static memory; the library's pitch is fast compile of
  every pattern with a frozen lazily-touched budget).
- **Thread-confined per-Matcher memo** — viable, strictly simpler, loses
  warmth across Matchers/threads; kept as the fallback if the LRU misses
  the bench gate.
- **Delete the memo** (always run the unmemoized simulation) — gated by
  `scripts/bench-regression.sh` (15 % rule) on the long-input rows; the
  TODO "long-input scans" benchmark work decides whether the simpler
  world passes. If it passes, prefer deletion over any cache.

## Enforcement — the tripwires that keep it honest

1. **Finality gate** (plain JUnit, zero deps): reflection scan of
   `core.dfa`/`core.engine` — every non-final instance field must carry
   `@MemoCache` (with the purity argument in its javadoc); every
   non-final static on an allowlist with a justification line. Rides
   `./gradlew check`.
2. **Determinism double-run** in the differential fuzzer: after the
   normal protocol, re-run every probe on the same artifact and
   byte-compare; any diff records as `MEMO_STATE_MISMATCH`. Warm ==
   cold becomes a tested property at fuzz scale, not a hope.
3. **Warm replay mode** for `fuzz.one` (run the batch, then the case) so
   warmth-dependent records replay — the gap that stranded finding 1.
4. **Sampled memo recompute**: 1-in-N cache reads recompute the value
   and compare against the published entry — a corrupt warm row fails
   instantly, with the row identified.
5. **jcstress** publication tests per entry type (publish-on-one-thread,
   read-on-another) — proves the protocol, entry by entry.
6. **Artifact twin-diff + freeze watchdog**: compile the same pattern
   twice in different threads, deep-compare (compile determinism);
   CRC the frozen tables at construction and re-hash after batches (any
   post-construction mutation trips, including stray out-of-bounds
   writes).

## Phases

| Phase | Exit criterion |
|---|---|
| 0 — Tripwires | 1–3 above landed; finding 1 reproduces (or is proven not warmth-dependent) via warm replay |
| 1 — Audit | Field inventory categorized; finality gate green on the allowlist + `@MemoCache` set |
| 2 — SearchDfa conversion | Rows/blocks as immutable LRU entries; locks deleted; jcstress green; determinism double-run clean over a mixed chunk |
| 3 — Remainder | Every other lazy field precomputed, Matcher-confined, or LRU'd; zero unclassified mutability |
| 4 — Bench + close | Both tiers through the 15 % gate; the 2026-10-24 findings doc F1 closed by construction |

Phase 0 is deliberately first: the tripwires convert this design from
prose into tests before the risky refactor begins, and they stay as the
permanent regression net. If the long-input benches ever let the memo be
deleted outright (the alternatives section), phases 2–3 collapse — the
audit and gates remain.

## Non-goals

- No behavior changes: protocols, budgets, and public API are untouched;
  this is a state-discipline refactor with identical observable answers.
- No new concurrency primitives beyond `ConcurrentHashMap` and (if
  needed) a striped lock on the eviction path — no exotic structures
  until the bench demands them.
