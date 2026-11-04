# Fuzzing the multi-valued lane — landed

Landed: `MULTI_VALUED_TAGS` (BT22 §3.1) is threaded into the differential
fuzzer (`DifferentialFuzzer`), on by default beside the lane mix, the
cross-rung ladder and the double-run tripwire. Same entry points as the
plain soak:

```
./gradlew :tests:parity:re2j:fuzz                       # lane on (default)
./gradlew :tests:parity:re2j:fuzz -Pfuzz.mvt=0          # plain two-compile protocol
./gradlew :tests:parity:re2j:fuzz -Pfuzz.mvtEvery=16    # sample the lane 1-in-16 batches
```

The fixed-seed gate is `FuzzSmokeTest` (lane-count asserts keep the wiring
honest) plus `MultiValuedFuzzLaneTest` (the parity corpus through the
lane's own oracles).

## Wiring, as built

The seams were where the plan said they are:

- **Derived flag axis.** `prepare()` additionally compiles the batch's
  pattern as ASM and VM with `flags | MULTI_VALUED_TAGS` (bit 128,
  `PatternSurface`-shared across surfaces). The bit is tdfa-only and never
  reaches either oracle compile — the matrix contract ("identical values
  in both libraries") covers the shared bits only.
- **Protocol reuse.** The same five-probe `compute()` protocol runs on the
  multi-valued compiles, plus a spans-suffixed variant (`computeMulti`)
  that appends every group's `groupSpans(g)` as `<[s,e][s,e]…>` to each
  successful probe's span blob.
- **Generator unchanged.** `quant()` puts `*`/`+`/`{n,m}` on capturing
  groups in roughly a quarter of quantifiable draws — repetition shapes
  are free entropy.
- **Cost.** Compile-bound: four tdfa compiles plus (where it applies) one
  `PikeSim.compileMulti` per batch instead of two — the 20-min validation
  chunk ran ~82 k cases/min with the lane on. `fuzz.mvtEvery=N` samples
  the lane 1-in-N (a pure function of the pattern, so `fuzz.one` replay
  holds at any N).

## Oracles, as built

1. **`MVT_SELF`** (no reference) — the invariants documented on
   `MatchResult.groupSpans`, asserted per probe on both tiers: the LAST
   pair of every group's list is exactly the single-valued
   `start(g)..end(g)`; a group that never matched yields the single pair
   `(-1,-1)`; pairs are well-formed (`start(0) ≤ s ≤ e ≤ end(0)`) with
   non-decreasing starts.
2. **`MVT_SINGLE`** — the multi compile's single-value protocol must equal
   the plain compile's (itself oracle-checked): generalizes the parity
   suite's fixed corpus to the whole random grammar × flag matrix,
   `LONGEST_MATCH` composition included. A tagged multi column against a
   real plain protocol is the same finding (one-tier rejects included).
3. **`MVT_TIER`** — MVT-ASM vs MVT-VM equality of the spans-suffixed
   protocol — the only oracle that covers the ASM tier's tree plumbing
   (the static `appendVal` hook, holder-carried tree snapshots), which
   nothing oracle-derived can touch.
4. **`MVT_SIM`** — `PikeSim.compileMulti(pat, provider)` keeps the winning
   thread's (tag, pos) write log; `spansOf(g)` must equal `groupSpans(g)`
   exactly, first `find()` only. **Served on RE2J-lane flags=0 batches
   only** — narrower than the plan's "skip LONGEST": the reference
   compiles the bare pattern at the RE2-lineage reading, so it can follow
   neither compile-level flag bits (CI/DOTALL/MULTILINE change what
   matches, LONGEST changes which parse wins) nor the jur facade's
   java-parity axes. ~22% of soak cases land in that cell; the
   lane-internal oracles (SELF/SINGLE/TIER/RUNG) run everywhere.

Free once the lane exists, both landed:

- **`MVT_RUNG`** — the cross-rung forced ladder and the stretched-input
  probes re-run on the multi-valued VM compile against the
  spans-suffixed protocol: the tree must survive every forced rung with an
  identical spans string, and thousands of appends per stretched match
  exercise node-pool doubling and `snapshot()` trimming at a scale short
  inputs never reach.

## Expected-divergence classes — decided

- **`MVT_BUDGET_REJECT`** — the multi lane permanently skips the
  fixed-tag and regopt passes, so a pattern near the budget can reject as
  "too large" where the plain compile fits
  ([multi-valued-tags.md](multi-valued-tags.md)). Classified when BOTH
  multi tiers budget-reject while the plain pair compiled: counted,
  capped-logged, never a finding, dropped by the nightly issue gate
  (anything asymmetric — one tier rejecting — stays a real finding). Did
  not fire once in the 1.6 M-case validation soak; the classifier is
  defensive.
- **The `-1` padding path — decided ILLEGAL.** `groupSpans` pads with
  `-1` when a group's open/close histories differ in length (`no != nc`).
  Open and close crossings of a group alternate along any accepting path
  (every entered group is exited before the pattern's end state), so the
  histories are equinumerous and the padding is defensive dead code —
  reachable only through TagTree readout corruption. Observed padding is
  a finding with its own kind (`MVT_PAD`), not a skip.

## Acceptance

The 2026-10-29/11-04 validation soaks (seed 20261029; 1.64 M and 3.93 M
cases) ran all four oracles plus the forced ladder and stretched probes:
**zero** MVT_SELF / MVT_PAD / MVT_SINGLE / MVT_TIER / MVT_RUNG /
MVT_THREW findings across 3.86 M multi-valued cases (852 k sim-served),
and the MVT_BUDGET_REJECT classifier fired on 8 cases exactly as designed
(plain fit and answered the oracle, both multi tiers rejected cleanly at
the scoped budget). Two finding classes surfaced and are tracked, not
classified away:

- **one MVT_SIM signature** (~1-in-4 M rate): the zero-width final
  iteration of a counted loop over a body with a zero-width branch
  appends the group's OPEN but not its CLOSE — minimized to
  `(\B|b){2,}?c` on `"bc"` (reference write log `[0,1][1,1]`, engine
  `[0,1][1,-1]` — the padding path firing, i.e. open/close histories
  diverge, while the single-value report stays `1..1` on both sides).
  Filed as #149 for the determinizer's final-variant machinery
  (dissolve/surface × zero-width-exit split); the nightly's issue gate
  picks the signature up from night one.
- the soak's plain-lane RESULT_MISMATCH records are the pre-existing #127
  family the nightly already tracks — unrelated to this lane.

From here the lane rides the nightly soak; new findings flow through the
per-run issue gate like every other kind. README fuzz-number refresh
stays with the general soak TODO item.
