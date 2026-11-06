# Streaming input — design record

Decision record, 2026-11-05, for the Wishlist item *Streaming input
(InputStream / ByteBuffer)*: match over chunked/streaming input without
materializing the whole input as a String. The design holds the two
invariants this library is built on — **linear time by construction**
(no backtracking escape hatch appears just because the input now arrives
in pieces) and the **frozen runner** ([runner-immutability](
runner-immutability.md): not one field of a shared artifact unfreezes;
all new mutable state is a sanctioned per-call carrier).

## The shape of the problem

The engine answers find() through a search ladder whose rungs all assume
one random-access input in memory. Streaming breaks exactly two of its
assumptions and nothing else:

1. **The input end is not known.** Every end-of-input predicate —
   END_TEXT/ABS_END (`$`/`\z`), FINAL_END (plain-`$` before a final
   terminator run), the EOF half of word boundaries — is evaluated
   against `pos == len`. Mid-stream, "no more input yet" and "no more
   input ever" must never be confused.
2. **The input prefix may be gone.** The ladder's restart rung re-walks
   from later start positions; a dropped chunk is unreadable, so
   retention has to be an explicit, budgeted decision — never an
   accident of buffer growth.

The second point is where re2j and `java.util.regex` offer nothing
(neither has a streaming surface) and where Hyperscan's answer — bounded
lookbehind windows with per-pattern state blocks — is the shape to copy,
minus the parts TDFA does not need: **TDFA has no lookbehind at all**
(lookaround is rejected at compile time, loudly, today), so there is no
per-pattern history to budget. What streams instead is the ladder
itself: a resumable walk plus a resumable kill-point simulation, both
carried chunk-to-chunk, over a window that retains exactly the input the
not-yet-decided part of the search can still touch.

## API

```java
// the SPI: pull-based, CharSequence chunks of UTF-16 units
 CharSequence next();          // next non-empty chunk, null = end of input

// adapters (io.github.jemmix.tdfa.core.engine.ChunkSources)
ChunkSources.utf8(inputStream)            // incremental UTF-8 via java.nio.charset
                                          // CharsetDecoder (Java-4 API; multi-byte
                                          // sequences and surrogate pairs carry
                                          // across chunk boundaries)
ChunkSources.utf8(inputStream, chunkChars)
ChunkSources.reader(reader)               // any charset, incremental
ChunkSources.decoder(charsetDecoder)      // arbitrary decoder over the stream feed
ChunkSources.byteBuffers(iterator, decoder, chunkChars)
                                          // the ByteBuffer case: decode buffers as
                                          // they arrive; splits carry

// both facades (jur default + re2j lane), both engines (VM + ASM)
StreamMatcher m = Pattern.compile("(\\w+)@(\\w+)").streamingMatcher(source);
while (m.next()) {
    long from = m.result().start(0);      // ABSOLUTE stream offsets (UTF-16 units)
    String user = m.group(1);             // text valid until the next next()
}
```

- The SPI (`ChunkSource`, the adapters, the overflow rejection) lives in
  `core.engine` beside the other match-time surface types;
  `StreamMatcher` and the narrow `StreamEngine` seam live in `core.dfa`
  beside the runner — the seam returns the dfa-tier carrier, and the
  core package DAG keeps `engine` below `dfa`.
- `StreamMatcher`: `next()` advances to the next confirmed match,
  `result()` returns the immutable `MatchResult` (absolute offsets, all
  groups, multi-valued readouts), `group(int)`/`group(String)` slice the
  retained window. One matcher per stream, single-threaded — the
  `Matcher` contract, verbatim.
- Offsets are absolute UTF-16 unit positions in the whole stream, the
  same coordinate system the String surface reports over the
  concatenated text (a stream longer than 2³¹−1 units is rejected
  loudly — the engine's coordinate system is `int`).
- **Group text is valid until the next `next()`** — the window compacts
  past a reported match as the search advances. Slicing a span the
  window has dropped fails with `IllegalStateException` naming the span
  — loud, never a wrong slice. Callers who need the text longer copy it
  then; callers who only need offsets pay nothing.
- Not offered, deliberately: `matches()`/`lookingAt()`-shaped streaming
  (a whole-input match over an unbounded stream is decidable only at
  EOF; express it as a find with `\A…\z` and the walk carries O(1)
  state), `split`/`replaceAll` (they materialize output by nature), and
  reset/reuse (one matcher, one stream). Each rejection is a missing
  method or an immediate throw, never a degraded answer.

## The chunking protocol

The driver runs the search ladder's definitive semantics incrementally:
leftmost-first search = walk(start) per candidate start in order; the
first walk that accepts wins, exactly the WALK_RESTART rung's contract.

- **The resumable walk.** A single-start walk (`extractFrom`'s machine:
  dispatch, masks, φ table, stop table, eager final ops, tag tree) runs
  over the window until it (a) records a priority-final accept —
  confirmed; (b) dies — next candidate start; or (c) **pends**. All
  walk state (DFA state, register file, tag tree, last-accept, position)
  is carried across `next()` calls; nothing is re-walked. On resume the
  loop continues at the frontier.
- **The pending rule.** Every position predicate this engine evaluates
  reads at most `charAt(pos-1)` and `charAt(pos)` and compares `pos`
  with `len` (see `positionFlags`/`maskSatisfied`: line-begin reads
  `pos-1`, line-end reads `pos`, word boundaries read both, FINAL_END
  scans the terminator run to `len`). At any `pos < frontier` those
  reads land inside the window and the end-relative bits are false —
  **identical to the true-EOF answers**. At `pos == frontier` (or a
  surrogate pair's second unit, or a terminator run touching the
  frontier) the answer could change with the next chunk, so the walk
  pends instead of deciding. This one rule is the whole soundness
  argument for mid-stream confirmation.
- **The EOF-endgame simulation.** The kill-point raw scan (the RAW_SCAN
  rung) runs over the final window once per search epoch — at EOF every
  position predicate is exact, so it delivers its full String-ladder
  power there: a dead live set past W proves no match exists (the
  no-match verdict, in one linear pass), and the restart enumeration is
  confined to the window the simulation admits — the same
  linear-time-with-bounded-restarts guarantee the String ladder has,
  including its honest corner (the defensive per-start loop when the
  simulation and the walks disagree — bounded by the same window).
  Mid-stream the simulation is not needed at all: the leftmost pending
  walk *blocks* candidate enumeration (a match at an earlier start beats
  anything later), so restarts cannot pile up before EOF — which is
  also why the walk carry, not a carried simulation, is the load-bearing
  mid-stream machinery.
- **Candidate enumeration** skips non-startBits chars when the start
  state cannot accept (the CAND_SCAN gate), skips surrogate-pair
  interiors under codepoint-boundary semantics, and collapses entirely
  on `\A`-bounded artifacts (every walk past 0 dies at its entry mask —
  the absBeginBound analysis, reused). Pure-literal patterns
  (literalNeedle) search the window with `indexOf`-style stepping and
  keep only a needle-length tail at the frontier — the bounded-memory
  flagship.

What stays out of v1, stated plainly: the PREFIX rung (required-literal
chain candidate scan) is not mirrored; prefix-chain shapes serve through
the startBits gate + carried simulation at walk-per-candidate cost. The
numbers land in BENCHMARKS.md with the gap named.

## Memory and time budget

- **Retention floor** = the leftmost start the search has not yet
  decided (minus one unit for the look-back predicates), or the
  needle-length tail for literal searches. Everything before the floor
  is compacted away on advance. The window is therefore **not O(1) by
  promise** — it is bounded by *the distance from the leftmost
  undecided match start to the frontier*, which the pattern and input
  determine: needle searches pin it at needle-length; a live-tail
  pattern (`.*x` with `x` never arriving) pins the floor at 0 and grows
  until resolution. That growth is the honest streaming cost of an
  unresolvable walk; it is capped, loudly.
- **The cap**: `tdfa.budget.stream.window` (UTF-16 units, default
  16 MiB, mirroring the runtime-memory budget family). Exceeding it
  throws `StreamWindowOverflowException` naming the knob and the
  pattern shape responsible — the "pattern too large" family's
  runtime-side sibling: a typed rejection, never a silent spill, never
  a truncated answer.
- **Time**: per chunk, the walk resumes (no re-read of the window) and
  confirmations report; at EOF the kill-point simulation bounds the
  endgame in one linear pass. No
  backtracking exists to budget — the restart enumeration is bounded by
  the simulation's kill window exactly as in the String ladder.
  Zero-width protocol preserved: an empty confirmed match advances the
  next candidate by one, mirroring `findAll`.

## Engines, immutability, and the oracle ladder

- **VM**: the driver lives in `TdfaRunner` as a streaming section
  beside the ladder (the walk/simulation logic reads the frozen tables
  directly, the `wholeWalk` precedent for a transplanted machine); the
  mutable search state rides the `StreamMatcher` carrier in `core.dfa`.
  No `TdfaRunner` field changes; the carrier is a per-call carrier in
  the finality gate's sanctioned sense (per-stream, contractually
  single-threaded, `Matcher`-shaped), named in the gate's carrier list.
- **ASM**: generated classes implement the new narrow seam
  `StreamEngine.streamingMatcher(source)` by delegating to their
  embedded `TdfaRunner` — the same both-tiers-one-definition move the
  INLINED tier already makes for `matchWhole` on partial-whole
  artifacts. The emitted String fast paths are untouched; streaming is
  served by the one driver, so VM/ASM answers are identical by
  construction. A bring-your-own engine that does not implement the
  seam gets `UnsupportedOperationException` from the facade, naming the
  engine — loud, per the house rule.
- **PikeSim needs no streaming mode.** The spec-of-record argument: the
  driver runs the *same* walk and simulation definitions the String
  surface runs (the very code the differential suites pin against
  PikeSim, re2j, and `java.util.regex`), over a window whose predicate
  soundness is the pending rule above. Equivalence is therefore pinned
  the honest way — by a chunk-split differential battery (every split
  position on small inputs, randomized splits on large ones, both
  engines, both lanes, zero-width and surrogate-splitting chunks
  included) against the String surface of the same artifact and the
  live oracles — not by writing a second streaming implementation to
  diff against a first.

## Bytecode floor

The surface lands in core (Java 8 floor): chunks are `CharSequence`
views, decoding uses `java.nio.charset.CharsetDecoder` (Java 4 API),
chunk append/compaction is plain arrays. No `InputStream.transferTo`,
no `readAllBytes`, no post-8 `Reader`/`ByteBuffer` API. The CI floor
job's JDK 8 smoke exercises the streaming path end-to-end
(`ci/Smoke8.java`).

## Landing plan

1. This record (linked from the Wishlist line).
2. Core protocol + VM driver + adapters + facade/re2j entries + unit
   tests + gate carrier entry + JDK 8 smoke.
3. ASM tier: emitted `StreamEngine` implementation.
4. Chunk-split parity battery (both lanes, both engines, oracles).
5. JMH streaming benchmarks + README/BENCHMARKS.md + Wishlist line
   deleted on genuine completion.
