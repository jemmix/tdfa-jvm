# tdfa-jvm — TODO

## Compaction / cache-friendly data layout (Tier A — IN PROGRESS)

Current `Tdfa` pokes all over the heap: per-state `int[][] rangeBounds`, `int[][] rangeTargets`,
`int[][][] rangeOps`, plus `BitSet acceptStates` and `int[][] finalOps`. Every transition lookup
in `TdfaRunner` is a pointer-chase:

```
rangeBounds[state]            // deref 1
  -> binary search on int[]   // boatload of cache lines if range count grows
rangeTargets[state][mid]      // deref 2
rangeOps[state][mid]          // deref 3
```

### Target layout (5 int arrays total — fits L1 trivially)

```
int[] stateRangeInfo  // [state] packed: (rangeBase << 8) | rangeCount
int[] stateFinalInfo  // [state] packed: (finalOpsOff << 1) | acceptBit
int[] ranges          // flat: [lo0, hi0, target0, opsOff0, lo1, hi1, target1, opsOff1, ...]
int[] ops             // flat: [op, dst, src, op, dst, src, ...] blocks terminated by OP_END=0
int[] regs (runtime)  // per-match register file, see register-alloc notes below
```

For a 20-state / 60-range / 30-ops regex: ~1 KB total across 4 contiguous slabs. Hot state's
range block fits in one 64-byte cache line.

### Runner hot path after refactor

```java
int meta = stateRangeInfo[state];
int base = meta >>> 8, count = meta & 0xFF;
for (int i = 0; i < count; i++) {
    int o = (base + i) << 2;
    if (c >= ranges[o] && c <= ranges[o + 1]) {
        int opsOff = ranges[o + 3];
        for (int j = opsOff; ops[j] != OP_END; j += 3) {
            int op = ops[j];
            if (op == OP_SET_POS) regs[ops[j+1]] = pos;
            else if (op == OP_COPY) regs[ops[j+1]] = regs[ops[j+2]];
            else regs[ops[j+1]] = -1;
        }
        state = ranges[o + 2];
        pos++;
        continue outer;
    }
}
// dead
```

**Per-char cost:** 2 sequential cache-line reads (state range block + ops block). For typical
states (≤4 ranges, ≤6 ops) both blocks fit in a single 64-byte cache line.

### Optional further wins (Tier B+)

- **Specialize for ASCII.** If every range's `lo/hi < 128`, lower to a `byte[128]` per state
  (one byte = class id) and a `byte[] targetByClassAndState`. Drops the dispatch to a single
  indexed byte load.
- **Specialize for "one range" states** (vast majority). Skip the loop, single `if` check.
- **Bytes rather than ints for state ids** once state count < 256 — cuts the layout by 4×.
- **Minimization** (Moore-style with register awareness): not implemented. State counts for
  non-trivial patterns (e.g. `(a|b)*c(a|b)*d`) are 2-3× larger than necessary.
- **`invokedynamic` + condy** to lazily specialize a per-regex `TdfaRunner` subclass at first match — but that's what the ASM backend (Tier B) already does explicitly.

## Register file (rejected: pooling)

Per-match `new int[registerCount]` + `Arrays.fill(-1)` is ~10 ns, < 0.01 % of VM per-match
latency. **Register pooling / generation-marked thread-local caches / off-heap schemes rejected
after Amdahl analysis**: maximum theoretical gain ≈ 0.02 %, against hundreds of lines of reset /
thread-affinity / lifecycle machinery. HotSpot's TLAB allocation is already near-free for arrays
of this size (≤ 96 bytes).

Revisit only if a benchmark at >10M matches/sec shows GC pressure as a top profiler hit.
Currently no such evidence.

**Action:** keep `final int[] baseRegs = new int[tdfa.registerCount]; Arrays.fill(baseRegs, -1);`
verbatim. Register optimization budget goes to:
- **A2 / R1** — lazy snapshot: don't `regs.clone()` on every accept-state visit. Track
  `(lastAcceptPos, lastAcceptState)` cheaply; materialize the snapshot lazily on dead-end.
- **R4 (Tier B)** — ASM scalar replacement: lower registers to JVM locals when registerCount ≤ 16,
  let HotSpot put them in CPU registers. Eliminates the array entirely on the fast path.

## Other known limitations (not compaction)

- **Negated char classes** (`[^0-9]`) blow up the alphabet enumeration; needs alphabet partition
  rather than character expansion. Test `negatedClass` is gated.
- **POSIX disambiguation** (`(a|a)+b`) — not yet implemented; only leftmost-greedy supported.
- **`map` + topological sort** is implemented but skips the "reject non-trivial cycles" rule from
  the paper (we currently succeed-and-rewrite for any acyclic bijection). For pathological patterns
  with append-style ops this can produce wrong results; not exercised by current tests.
- **Multi-valued tags** (tags under repetition accumulating multiple offsets) — single-valued only.
- **`Matcher.find()` unanchored search** is O(n × states) — restarts from each position. Should
  prefix the pattern with a `.*?` desugaring or add a separate "scan DFA".
- **Class-leading-`]`** (`[]...]`) — POSIX says a `]` immediately after `[` (or `[^`) is a literal
  character; our parser closes the class there. ~7K residual failures in the re2-exhaustive suite.
- **Leftmost-longest vs leftmost-first** — the runner returns the longest match from the leftmost
  start position (POSIX flavour). re2j's default is leftmost-first (Perl). Patterns with
  alternation overlap like `(a|ab)` therefore report col-3 results, not col-1; ~130K cases in the
  re2-exhaustive suite. Not a bug — a semantic choice documented here.

## Algorithmic gaps vs paper

- **Lookahead-TDFA(1) vs TDFA(0):** our implementation is closer to TDFA(0) with lookahead-style
  delay. Strict paper conformance would also defer ε-closure tag recording into the next state's
  `h` and apply on the OUTGOING transition — verify against paper §3 wording.
- **Fallback / backup operations** (paper §3.2): not implemented. `Matcher.find` longest-match is
  handled by re-running from each start; for true fallback we should backup clobberable registers
  on transition out of accepting states and restore on dead-end.
