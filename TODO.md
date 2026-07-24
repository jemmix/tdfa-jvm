# tdfa-jvm — TODO

## Compaction / cache-friendly data layout (M3)

Current `Tdfa` pokes all over the heap: per-state `int[][] rangeBounds`, `int[][] rangeTargets`,
`int[][][] rangeOps`, plus `BitSet acceptStates` and `int[][] finalOps`. Every transition lookup
in `TdfaRunner` is a pointer-chase:

```
rangeBounds[state]            // deref 1
  -> binary search on int[]   // boatload of cache lines if range count grows
rangeTargets[state][mid]      // deref 2
rangeOps[state][mid]          // deref 3
```

Goal: collapse to a flat SOA layout that streams.

### Concrete plan

- **Per-state header (struct-of-arrays):** pack `(firstRangeIdx, rangeCount, isAccept, finalOpsIdx)`
  into a single `long` (or two `int`s) per state. State array is then one tightly packed `long[]`.
- **Ranges:** flatten all states' ranges into a single `int[]` quad-stream:
  `[lo, hi, target, opsOffset, lo, hi, target, opsOffset, ...]`. Linear scan within a state's slice
  is faster than binary search up to ~16 ranges (cache-dense, branch-predictable).
- **Ops:** flatten all ops into a single `int[]` triple-stream
  `[op, dst, src, op, dst, src, ...]`. `opsOffset` in the range entry indexes this array.
- **Accept + finalOps:** single `byte[] isAccept` (or pack into the header bit), and a parallel
  `int[] finalOpsOffsets` indexed by state.
- **`TdfaRunner` rewrite:** drop `Map<Integer, ...>` lookups entirely. The hot path becomes:
  ```
  long hdr = stateHeaders[state];
  int rangeBase = firstRange(hdr), rangeCount = rangeCount(hdr);
  for (int i = 0; i < rangeCount; i++) {
      int o = (rangeBase + i) * 4;
      if (c >= ranges[o] && c <= ranges[o + 1]) {
          int opsOff = ranges[o + 3];
          // apply ops[opsOff..] until sentinel
          state = ranges[o + 2];
          ... continue outer loop
      }
  }
  return dead;
  ```
  Two array loads per char in the common case (header + first range slot), no Map, no binary search.
- **Bytes rather than ints for state ids** once state count < 256 — cuts the layout by 4×.
- **Pad range quads to cache lines** so a single state's ranges usually live in one 64-byte line.

### Optional further wins

- **Specialize for ASCII.** If every range's `lo/hi < 128`, lower to a `byte[128]` per state
  (one byte = class id) and a `byte[] targetByClassAndState`. Drops the dispatch to a single
  indexed byte load.
- **Specialize for "one range" states** (vast majority). Skip the loop, single `if` check.
- **Prefetch next state's header** at the bottom of the current iteration on JDK 25+ (`MemorySegment` prefetch).
- **`invokedynamic` + condy** to lazily specialize a per-regex `TdfaRunner` subclass at first match — but that's what the ASM backend (M4) already does explicitly.

## Other known limitations (not compaction)

- **Anchors (`^`/`$`)** not yet enforced — `StartAnchor`/`EndAnchor` in AST are accepted by parser
  but ignored at match time. Three tests in `RegexTest` are `assumeTrue(false, ...)`-gated.
- **Negated char classes** (`[^0-9]`) blow up the alphabet enumeration; needs alphabet partition
  rather than character expansion. Test `negatedClass` is gated.
- **POSIX disambiguation** (`(a|a)+b`) — not yet implemented; only leftmost-greedy supported.
- **`map` + topological sort** is implemented but skips the "reject non-trivial cycles" rule from
  the paper (we currently succeed-and-rewrite for any acyclic bijection). For pathological patterns
  with append-style ops this can produce wrong results; not exercised by current tests.
- **Multi-valued tags** (tags under repetition accumulating multiple offsets) — single-valued only.
- **`Matcher.find()` unanchored search** is O(n × states) — restarts from each position. Should
  prefix the pattern with a `.*?` desugaring or add a separate "scan DFA".

## Algorithmic gaps vs paper (M2+)

- **Lookahead-TDFA(1) vs TDFA(0):** our implementation is closer to TDFA(0) with lookahead-style
  delay. Strict paper conformance would also defer ε-closure tag recording into the next state's
  `h` and apply on the OUTGOING transition — verify against paper §3 wording.
- **Fallback / backup operations** (paper §3.2): not implemented. `Matcher.find` longest-match is
  handled by re-running from each start; for true fallback we should backup clobberable registers
  on transition out of accepting states and restore on dead-end.
- **Minimization** (Moore-style with register awareness): not implemented. State counts for
  non-trivial patterns (e.g. `(a|b)*c(a|b)*d`) are 2-3× larger than necessary.
