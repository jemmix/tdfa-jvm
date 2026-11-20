# Atomic groups: the decision record

Gates the TODO `## Features` item ("decision record, VM prototype
first"). Live-JDK probed before design; the battery will pin every
cell below on both engine tiers.

## Semantics (oracle-pinned)

`(?>X)` — once X's submatch succeeds (first preference, standalone),
the group locks: the engine never re-enters it, even when the
continuation fails.

- `(?>a|ab)c` on `"abc"` — **no match**: the alternation commits to
  `a`, `c` meets `b`, no retry of `ab` (the greedy twin matches).
- `(?>a+)a` on `"aa"` — **no match**: the loop commits to the maximal
  run (`a++` in possessive spelling — java itself desugars `x*+` to
  `(?>x*)`).
- `(?>a+)b` on `"aab"` — `[0,3)`: pass-through where no give-back is
  required.
- `a(?>b|bc)d` on `"abcd"` — **no match**: mid-pattern commit.
- `(?>(a+))\1` on `"aaaa"` — **no match**: captures inside the group
  report normally (the span survives the lock), and a backreference
  after it applies to the locked span — nothing here for it to match.
  (Backreferences themselves stay unsupported; the cell pins that the
  CAPTURE side composes.)
- `(?>a*)b`, `(?:(?>a+))b` — the transparent wrapper and the
  zero-width-capable body behave as their possessive twins.

## The landing

Landed on both engine tiers (the prototype step found nothing tier-
specific — the fork rule is determinizer-level, exactly as #195
predicted). Two falsifiers the smoke caught:

1. The first shape was QUEST-like — a decision state with body and
   SKIP edges. Java's body is MANDATORY (a failed body fails the
   group); the skip edge let `(?>a|ab)c` match the bare `c` at a
   restart. The landed shape is PLUS-like: entry &rarr; body (the
   BODY-stamped edge, no skip), body &rarr; exit hub, hub &rarr; out
   (the EXIT-stamped reset).
2. Fork bits now SURVIVE the symbol step. The old step-reset was
   correct for possessive quantifiers (whose choice kernels sit at
   the quantifier boundary, every boundary edge stamped) but wrong
   for atomic, whose fork spans an arbitrary body — kernels inside
   the body must keep competing (`(?>a+)a` matched by giving one
   iteration back). The EXIT-stamped edges do all the resetting; the
   possessive battery stayed green unchanged.

## The decision

**Atomic lowers onto the possessive fork machinery — one mechanism,
two front-ends.** Possessive (landed, #195) already solves the hard
part: per (kernel, range), the first fork config with a live edge
steps and every lower-priority same-fork config's step on that range
is dropped, with the entry-wrap stamping BODY on the whole construct
and every EXIT edge resetting the fork's bits. Atomic differs only in
what the fork's body is (an arbitrary subpattern, not a quantifier's
repeated unit) and in one detail:

> the commit is **continuation-blind**: the body's first-preference
> submatch resolves WITHOUT consulting what follows — the
> determinizer's closure must not let the continuation's priority
> leak into the fork's internal choices.

Possessive never faced this because a quantifier body's internal
priority is the loop's own. For `(?>a|ab)c` the fork body is the
alternation `[a, ab]`; the rule must kill `ab` on `{a}` at the fork
kernel even though nothing about the body consumed differently —
which is exactly what the current first-live-edge-wins rule does
(alternatives are same-fork configs in priority order). The
continuation-blindness falls out for free: the fork configs are
dropped per range at the fork kernel, before any continuation
priority can participate.

**Plan (per the TODO's own gate):**
1. **VM prototype** — parser clause `(?>` … `)` on the jur lane
   (re2j rejects; pin), a fork-body AST wrapper reusing
   `Repeat.possessive`'s stamping path (a BODY-stamped entry wrap
   around the body, no quantifier frames), battery of the cells
   above + the possessive-equivalence set. Interpreter tier only.
2. **Both tiers** — the ASM side needs nothing new if the prototype
   holds (the fork rule is determinizer-level; the runner/ASM see
   ordinary tables — the #195 result).
3. **Nested/interleaved forks** (`(?>a(?>b|bc)c|d)`): the Config
   bitset already carries multiple forks; pin interleavings with
   possessive.

**Risks:** (a) the group's tags must flow through the lock
(unlike a possessive quantifier's iteration-local writes) — the
battery's capture cell guards it; (b) `(?>X)` where X can match
empty: the zero-width-iteration protocol must not fire (it is a
quantifier concept) — the wrapper mints no loop, so nothing fires;
pin `(?>a?)b` both ways; (c) bounded bodies under the wrapper
(`(?>a{1,2})a` = the possessive-bounded twin, already pinned by
#195's battery — reuse those cells).

The `\G`/`\b{g}` landings removed the other historic risk (the
"needs a submatch predicate" worry): the fork rule resolves at
compile time and needs no runtime state beyond what a plain
alternation consumes.
