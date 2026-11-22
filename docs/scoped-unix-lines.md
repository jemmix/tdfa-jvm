# Scoped inline `(?d)`: per-scope terminator tables — the design record

Live-JDK probed before design (every cell below is battery-bound for
the landing). The last TODO `## Features` item.

## Semantics (oracle-pinned)

The scoped spellings — `(?d)`, `(?d:…)`, `(?-d)` off, composable with
`(?m)` in either order (`(?dm:^b)` ≡ `(?md:^b)`) — switch the
LINE-TERMINATOR SET for every terminator-sensitive reading inside
their scope, exactly like the compile-global `UNIX_LINES` flag but
scoped:

- **`^`/`$` under `(?m)`**: `(?dm)b$` on `"a\r\nb\rc\n"` rejects
  (unix `$` never matches before `\r`); `(?m)b$` accepts `[3,4)`.
  `(?dm)^c` rejects after `\r` where `(?m)^c` accepts.
- **The dot**: `(?dm:a.c)` on `"a\rc"` MATCHES `[0,3)` — the unix
  dot is `[^\n]`, so `\r` rides through; the plain dot rejects.
  (The dot is a class atom — resolved per scoped state at
  meaning-resolution, the symbolic-dot seam #186/#187 built.)
- Scopes END at their group (`(?dm:^b)(?m:^c)`: the second anchor is
  back on the JDK set); captures inside work; in a class `(?d)` is
  just the flag-setting group (classes are unaffected beyond the dot
  interplay). `\A`/`\z`/`\Z` are terminator-blind (no change).

## The landing (as-built)

The parallel-families design landed with ONE addition the flag
composition forced: a THIRD family. The probes had pinned the scoped
spellings, but the battery's flag-composition cell
(`UNIX_LINES + (?-d:…)`) exposed that java's model is flag-picks-
default / scope-overrides: an explicit `(?-d)` under the flag
restores the JDK set — a third live set in one artifact. The parse
tracks `dScoped` (any `(?d)`/`(?-d)` letter seen) and the compile's
unix-default (the Semantics terminators axis); the emission picks
plain / UNIX / OTHER per effective set, and the dot carries a
tri-state (ON / explicit-off / untouched).

Two representation fixes the falsifiers forced: the closure's
popped-mask bitset now sizes its stride from the compile's HIGHEST
mask bit (the unix family's bit 512 overflowed the fixed 3-word
stride — silently aliasing neighbor states when it did not crash),
and `posFlagCells` is now next-pow2(maxBit+1) rather than a family
count (the OTHER family sits at bit 4096+ — the cell index IS the
posFlags value, so sparse high bits need the address space, and
`allowedMaskBits` admits exactly the addressable bits).

## The design

**The axis stays ONE `Terminators` value per *scope*, not per
compile.** The parse records the scoped unix-lines state exactly as it
records scoped `(?i)`/`(?s)` (the `FlagAction` machinery already
carries it — `FlagAction.UNIX_LINES` exists? if the binding is
missing it is added, jur lane only; the re2j alphabet keeps
rejecting `d`). Two consumers change:

1. **The dot (already scoped-shaped)**: `Ast.Dot` carries its scoped
   state into meaning-resolution; the resolver picks
   `Terminators.NL_ONLY`'s complement vs the compile value's — one
   branch in the existing dot resolution. No new machinery.
2. **The anchors (the real work)**: today `^m`/`$m` map one-to-one
   onto `BEGIN_TEXT`/`END_TEXT`/`DOLLAR` bits whose runtime formulas
   consult the compile-global terminator set. Scoping needs BOTH sets
   live in one artifact — the honest shapes:
   - **(a) Parallel bit families** — `BEGIN_TEXT_UNIX`,
     `END_TEXT_UNIX`, `FINAL_END_UNIX` minted only when a compile
     mixes scopes (a ninth-ish dimension of the position-flag cells,
     the `\G`/`\b{g}` precedent: the cells grow only for artifacts
     that consult them). The parser's anchor emission picks the
     family per scoped state. The formulas: same predicates with the
     NL_ONLY set — the runner's `lineBegin`/`lineEnd`/`finalEnd`
     parameterize over the set (they already take the pair; the
     per-call set comes from the bit family consulted). ASM:
     `emitTerminatorCheck` emits per-unit compares from data — it
     emits the unix sequence for the unix-family bits, the same
     specialization from a second set.
   - (b) Per-state `Terminators` tables on the artifact — the walk
     consults `stateTerminators[state]`; rejected: it threads a
     second runtime dimension through every walker for one feature,
     and the bit-family shape already exists.

   **Decision: (a).** The `DOLLAR` policy rewrite
   (`END_OF_TEXT_ONLY`) composes per-scope as today — the resolver
   does the rewrite against the *scoped* set's family bits.

## Test plan

Battery (live-JDK oracle, both engine factories): the probe corpus —
`(?dm)` vs `(?m)` over `\r`/`\n`/`\r\n` inputs for `^`, `$`, the
dot; scope-restore cells; `(?-d)` off; `(?d)` alone (no `m` — `^`
stays start-anchored, the dot still narrows); combos with `(?s)`
(dotall wins for the dot, terminators unchanged for anchors); the
re2j-lane `(?d)` rejects. The compile-global `UNIX_LINES` flag
batteries stay green (the flag becomes sugar for a whole-pattern
scope, or stays its own path — whichever the resolver's shape makes
simpler; the batteries pin equivalence).

## Risks

- ~~The `FINAL_END` interaction~~ **Resolved by probe**: the plain-`$`
  (non-multiline) before-final-terminator rule IS scoped — `(?d)b$`
  rejects before both `\r` and `\r\n` where the default accepts; the
  resolver's `DOLLAR` rewrite must consult the scoped family.
  `(?dm:a$)` likewise rejects before `\r`. `(?ds:…)`: dotall wins for
  the dot, terminators unchanged. `(?d)` without `m`: `^` stays
  start-anchored (probe: `(?d)^b` on `"a\nb"` rejects).
- The stop-table's position-flag cells double when both families
  appear — the 512/1024-cell artifacts need budget headroom (the
  `\G`+`\b{g}` combination already proved the machinery at 512).
- `\b` is unaffected (word predicates, not terminators) — pinned.
