# Adversarial review r11 — budget accounting, all three budgets

2026-09-18. A dedicated adversarial pass over the resource-budget contract
itself: every allocation site and every loop in the compile pipeline, the
facade ladder, and the match-time memos, audited against the claim that the
three `tdfa.budget.*` properties bound the engine's resource consumption.
Twelve holes found (5 compile CPU, 4 compile RAM, 3 runtime RAM); all fixed
in this change, each pinned by a test. Findings are numbered B1–B12
(B = budget); severity here is "contract violation", not "crashes today" —
though B2 and B10 are OOM/hang reachable from pattern text alone.

**Gate status:** `./gradlew check` green (JDK 26), `:tests:unit:test
-PrealJdk8 -PagainstJars` green (real JDK 8 javac for core+asm+facade, unit
suite on JDK 25 against the packaged jars), parity suites (re2j, rebar,
re2j-suite) green.

---

## Compile CPU (`tdfa.budget.compile.compute`)

### B1. The whole-match ladder could burn 2× the budget the user set — P0

`SingleCompile.artifacts`/`wholeEngine` gave every eager attempt its own
full/fractional budget: front-end meter (B), unpruned whole meter (⅓B),
pruned find meter (B), anchored re-parse meter (a FRESH B — the parse was
never capped), anchored determinize meter (⅔B). Worst case one
`Pattern.compile` burned ≈ 2B+ of the user's "5 seconds".

**Fix — one ledger per compile.** `WorkMeter` grows a shared ledger:
`fork(cap)` derives a per-attempt meter capped at `min(cap, remaining)`
whose ticks debit the pool; `charge(n)` settles external spend. The
SHIPPED work (front-end, a succeeded whole, find, anchored parse +
determinize) all debits one root ledger created in `PatternCompiler` /
`CompiledRegex`, so shipped work per compile ≤ B. The unpruned whole
attempt is a **probe**: fraction-capped on its own meter, charged only on
success — a rejected probe's bounded churn (≤ ⅓B) is the documented price
of trying (the pinned calibration shape
`((.{1,5}?[…]?){0,5}?)z` measurably needs whole 166.7 M doomed + find
178.8 M + anchored 252.9 M; its shipped work fits one 500 M budget, its
wall time does not — no honest sum semantics admits it at these
fractions). Worst-case wall drops from ≈2B to ≤ B + ⅓B.

### B2. The active-set precompute was invisible to both budgets — P0

`TdfaCompiler`'s constructor built `long[cells][words]` active-edge sets
with an O(cells × edges) `cc.matches` scan and a QUADRATIC distinct-set
intern (linear `Arrays.equals` scan per cell). A ~100 K-arm
distinct-single-char alternation (~1 MB of pattern text, ~8 M TNFA ticks —
front-end-legal) asked for **2.4 GB of arrays and 2×10¹⁰ probes before any
cap existed**. Silent OOM/hang.

**Fix:** the arrays are charged up front against the compile RAM budget
(before allocation, standard rejection), the scan and interning tick the
meter (`cells × (edges + words)` — deterministic), and interning is
hash-based with first-seen ids (bit-identical assignment to the old scan).
Pinned: `BudgetModelTest.activeSetPrecomputeIsBudgetVisible`.

### B3. The determinize sweep's per-cell fast paths were unmetered

The per-active-set DEDUP path (`perSetDone`) calls `addRange` per cell
with no tick — states × cells boxed appends, up to 10¹⁰ on cap-scale DFAs.
The dead-marker loop (cells × contexts) likewise. Both now tick per cell
iteration.

### B4. `sortedOutgoing`'s insertion sort was the front-door quadratic

One hub state with a 100 K-arm alternation fan-in (front-end-legal, ~13 MB
TNFA) made the O(d²) priority sort 5×10⁹ unmetered compares. Every shift
now ticks.

### B5. Assorted unmetered loops: breakpoint scan, stop-mask DFS, final-variant enumeration, materialization

`computeBreakpoints` (per class range), `computePerStateOrderDfs` (runs
64× per accepting state), `computeFinalVariants` (64 × n per accepting
state), the coalesce/sort/flat-fill materialization passes — all linear-or-
worse sweeps no meter saw. All tick now (bulk `tick(n)` where the trip
count is known).

## Compile RAM (`tdfa.budget.compile.memory`)

### B6. Kernel weights ignored `tags` — the per-config cost is not flat

Every `Config` carries an `int[tags]` register slice (cloned per transition
op allocation): a 1000-group pattern pays ~8 KB per config against a flat
80 B weight — a 100× undercharge on capture-heavy closures. The kernel
total and the closure-spike cap now use `KERNEL_CONFIG_BYTES + 4×tags` per
config (tagless accounting unchanged — the pinned caps keep their meaning).
Pinned: `BudgetModelTest.kernelWeightsAreTagAware`.

### B7. The Perl stop table lived outside the per-state weight

`stateStopOnAcceptMask` is `int[n*64]` — exactly 256 B/state ON TOP of the
"256 B all-in" assumption: at the derived state cap the stop table alone
consumed the entire modeled budget (Perl-mode peak undercounted ≥2×).
`maxDfaStates` now takes the mode's surcharge (`STOP_TABLE_STATE_BYTES` in
Perl mode, none in POSIX). Pinned: the 262 144 Perl cap in
`derivedCapsPinTheWeightModel`.

### B8. Tag histories and their derived caches were unaccounted

`HistTable` interns one `int[]` per distinct tag history and memoizes a
bits row (per word) plus a lastSign row (per tag) per id — history-heavy
tagged compiles could dwarf the kernels the 80 B weight claimed to cover.
Sequences and cache rows are now charged on intern/materialization
(`HIST_FIXED_BYTES` + content bytes). Pinned:
`BudgetModelTest.tagHistoriesAreCharged`.

### B9. The boxed-Range storm was unaccounted

Each determinize sweep emitted one boxed `Range` per breakpoint cell per
context, all retained until the materialization-time coalesce — the live
boxed set could exceed the modeled per-state budget by orders of magnitude
on class-dense patterns. `addRange` now coalesces INLINE with the previous
adjacent entry (emission is ascending per context, so the live count IS
the post-coalesce count from the start), and each NEW live range is
charged (`RANGE_BOXED_BYTES`). The legit dictionary shape stays far under
budget (~10 MB weighted).

## Runtime RAM (`tdfa.budget.runtime.memory`)

### B10. The walk-block memo was entirely outside the budget — P0

`WalkIndex` capped its shared blocks at a magic 64 but allocated one
`int[128]` id table PER STATE lazily — 512 B × stateCount of unaccounted
match-time RAM (100+ MB on a large disjoint DFA scanning wide codepoints).
The memo now draws from a budget-derived share (⅛ of the runner's budget:
`walkMaxBytes`), charging each published per-state table and block; past
the allowance, wide-codepoint dispatch falls back to binary search
(correct, slower). The runtime budget's partition is now explicit eighths:
search-DFA rows 4/8 (unchanged), search-DFA blocks 3/8 (was 4/8), walk
memo 1/8 — the pinned `sdfaMaxBlocks` default moves 3855 → 2891. Pinned:
`BudgetModelTest.walkMemoIsBudgetBoundedAndCorrect`.

### B11. A find+whole engine pair doubled the per-pattern memos

The budget claims to be per PATTERN, but a non-shared ladder (pike-cut
shapes like `ab|a|ac`, or the anchored over-budget corner) retains TWO
runners — two full memo budgets. The facade now splits: each engine of a
pair gets half (`TdfaRunner(Tdfa, long memoBudgetBytes)`), including the
ASM tier (the emitted constructors burn the share in as a constant;
`TdfaAsmBackend.generate(Tdfa, long)`). BYO factory engines remain outside
the facade's accounting (documented — they already can be arbitrary).

### B12. `SearchDfa`'s copy-on-write contradicted its own protocol

`setRowCell` cloned the entire `rowBlockIdsArr` snapshot per CELL write —
O(rows) per (row, block) pair, transient churn no cap modeled — while the
class doc itself specifies the safe publication protocol (cells only ever
transition −1 → final under the lock; readers re-check on −1 and
length-check stale ids). Cells are now written in place; the outer array
still only grows (which is what readers rely on). The intern probe wrapper
is also reused instead of allocated per probe.

---

## Deliberate scope notes (not holes)

- **BYO engines** (`RegexEngineFactory`) construct arbitrary user objects;
  the facade cannot account for their internals. The memo split applies to
  the facade's own runners; the BYO shell documents this.
- **Eager dispatch tables** (`asciiTarget`/`latinTarget`/`asciiRangeFlat`,
  ≤ ~21 MB at the fixed 8 192/16 384-state tiers) remain construction-time
  constants of the runner, not lazy memo: they are deterministic from the
  artifact, disclosed in the runner docs, and flooring them on the runtime
  budget would flip the flagship dictionary shapes off their fast paths.
- **The minimizer's degrade-not-reject semantics** are unchanged: `Exhausted`
  inside an optional pass still skips the pass; with the ledger it can no
  longer starve later mandatory attempts of shared-budget ticks (B1).
- **`Scratch`/`TRACE_BUF` ThreadLocals** remain the documented retention
  trade-off from review r10 P2.
