# Fuzzing the multi-valued lane — plan

Open work: thread `MULTI_VALUED_TAGS` (BT22 §3.1) into the differential
fuzzer. Today the lane is covered by `MultiValuedParityTest` (fixed 17×19
corpus, two oracles) and the determinism corpus; the fuzzer never compiles
it. The plan below adds the lane to the soak with four independent oracles,
only one of which needs a new reference. Tracked in
[TODO.md](../TODO.md); the feature itself and why it stays are documented in
[multi-valued-tags.md](multi-valued-tags.md).

## Wiring

The seams already exist in `DifferentialFuzzer`:

- **Derived flag axis.** `prepare()` additionally compiles the batch's
  pattern as ASM and VM with `flags | MULTI_VALUED_TAGS`. The bit (128) is
  tdfa-only and must be masked off the re2j oracle compile — the existing
  matrix contract ("identical values in both libraries") covers the shared
  bits only.
- **Protocol reuse.** Run the same five-probe `compute()` protocol (F/I/M/L/R)
  on the multi-valued compiles, plus a spans-suffixed variant that appends
  every group's `groupSpans(g)`.
- **Generator unchanged.** `quant()` already puts `*`/`+`/`{n,m}` on
  capturing groups in roughly a quarter of quantifiable draws — repetition
  shapes are free entropy, no generator work needed.
- **Cost.** Compile-bound: four compiles per batch instead of two, roughly
  +30–50% batch time. If soak rate matters, sample the lane 1-in-N the way
  `CROSS_STRETCH` does.

## Oracles, ranked by yield per unit of new machinery

1. **Self-consistency (no reference at all).** The invariants are already
   documented on `MatchResult.groupSpans`: the LAST pair of every group's
   list is exactly the single-valued `start(g)..end(g)` report; a group that
   never matched yields the single pair `(-1,-1)`; pairs are well-formed
   (`start(0) ≤ s ≤ e ≤ end(0)`) with non-decreasing starts. Asserted per
   case, these catch TagTree readout corruption on any random shape.
2. **Latest == plain.** The multi-valued compile's single-value protocol
   string must equal the plain compile's — which is itself re2j-checked.
   This generalizes the parity suite's fixed corpus to the whole random
   grammar × flag matrix, including `LONGEST_MATCH` composition.
3. **Tier vs tier.** MVT-ASM vs MVT-VM protocol+spans equality — the only
   oracle that covers the ASM tier's tree plumbing (the static `appendVal`
   hook, holder-carried tree snapshots), which nothing re2j-derived can
   touch.
4. **PikeSim write-log (full spans).** `PikeSim.compileMulti(pat, …)`
   keeps the winning thread's (tag, pos) write log; `spansOf(g)` must equal
   `groupSpans(g)` exactly. Leftmost-first only, so skip on
   `LONGEST_MATCH` batches. Backtracking-sim cost is fine on ≤24-char
   inputs; it already runs per-pattern in the parity suite.

Also free once the lane exists:

- **Cross-rung forced ladder** on the multi-valued VM compile — the tree
  must survive every forced rung with an identical spans-suffixed string.
- **Stretched inputs with the lane on** — thousands of appends per match
  exercise node-pool doubling and `snapshot()` trimming at a scale short
  inputs never reach.

## Expected-divergence classes (classify, don't count as findings)

- **MVT budget reject.** The multi lane skips the fixed-tag and regopt
  passes, so a pattern near the budget can reject as "too large" where the
  plain compile fits — documented in
  [multi-valued-tags.md](multi-valued-tags.md). Plain-fits/MVT-rejects needs
  its own classifier or the soak floods.
- **The `-1` padding path.** `groupSpans` pads with `-1` when a group's
  open/close histories differ in length (`no != nc`). Nothing asserts today
  whether that path is reachable or legal. Decide before fuzzing it, or the
  fuzzer will answer the question the loud way.

## Acceptance

Overnight soak with the lane on, all four oracles armed, zero unclassified
findings; refresh the README fuzz numbers afterwards (existing TODO item
covers the general soak).
