# Possessive quantifiers: the fork rule

Design record for the TODO `## Features` landing (prune directive,
greedy-coincident tags). Live-JDK probed before design; every cell
below is battery-pinned against the oracle on both engine tiers.

## Semantics (source + oracle pinned)

`x*+`, `x++`, `x?+`, `x{n,m}+` — maximal munch, never give back:

- **Language.** `a++a`, `a*+a`, `a?+a` are empty (the quantifier ate
  what the continuation needed and cannot return it); `a*+b`,
  `a{1,2}+b`, `[ab]++c` match like their greedy forms when no give-back
  is required. `(a|ab)++c` on `"abc"` and `(?:a|b)*+b` on `"ab"`
  REJECT — each iteration commits to the first body alternative that
  matches, and the loop never re-tries a completed iteration.
- **Tags are greedy-coincident.** On every string where the possessive
  form accepts, it accepts along greedy's FIRST-preference path, so the
  reported groups are identical: `(a++)b` on `"aab"` reports
  `g1="aa"`; `(a|b)++` reports the LAST iteration like greedy's loop.
  Consequence: no new tag semantics — the determinizer's existing
  priority order is the whole story.
- **`a++?` rejects** as nested repetition (java's own "dangling meta
  character" shape) — the parser's existing nested-quantifier guard
  already covers it once `+` is a legal suffix.

## The representation decision

The naive reading — "prune the exit when the body is live" — is not
statically sound: `a*+b` on `"b"` needs the exit at position 0 (the
body cannot start there). The condition is per NEXT-CHAR, which the
position-bit masks cannot express and a static edge deletion cannot
respect.

The sound form is **per (kernel, range), resolved at compile time**:

> Within a possessive fork's kernel configs — the body alternatives'
> entry configs in closure-priority order, then the fork's exit
> continuation — for each symbol range r: the FIRST config with a live
> edge on r steps; every LOWER-priority same-fork config's stepping on
> r is dropped.

- On `r ⊆ first(body)`: the first body alternative steps, later
  alternatives and the exit die for r — the per-iteration alternation
  commit AND the no-give-back rule in one stroke.
- On `r ∩ first(body) = ∅`: the body configs are dead on r, the exit
  steps — `a*+b` on `"b"` serves.
- At end of input: no range fires; the exit abides (zero-width), and
  its continuation's own assertions gate position legality as usual.

Because the determinizer already enumerates (kernel, range) targets,
the rule folds into `stepOnSymbol`: nothing new at runtime. The runner
and the ASM emitter see only ordinary transition tables — zero walker
changes, zero emitted-code changes, zero hot-path cost for
non-possessive patterns. (This is why the earlier "research gate"
grading was too conservative: the runtime-generality worry presumed a
runtime gate; the oracle probes showed the gate resolves per range at
compile time.)

The zero-width side needs no fork rule: a mid-run zero-width accept at
the fork exit is always superseded by the later accept of the
continued run (`(a*+)` on `"aaa"` reports `[0,3)`, not `[0,1)`), and
any position-legality (`$`-flavors, boundaries) rides the exit
continuation's own assertion masks.

## Mechanics

- **AST/parser**: `Ast.Repeat` gains `possessive`; a trailing `+` after
  a quantifier selects it (`*+`, `++`, `?+`, `{n,m}+`). The
  nested-repetition guard then sees `a++?` as a quantifier after a
  quantifier and rejects, exactly like java.
- **TNFA**: the fork's hub carries fork metadata — per state, a fork
  role (fork id + the config-order rank the rule needs). The exit
  junction and each body alternative's entry state are marked; nothing
  else changes on the wire-visible shape (the arrays are compile-time
  only).
- **Determinizer**: `stepOnSymbol` applies the first-live-wins rule
  per (fork, range) among the kernel's fork-role configs. `a++a`
  compiles to an artifact whose `{a}`-transitions never reach the
  trailing `a` — the language is empty by construction, not by
  rejection.
- **RE2 lineage**: re2j has no possessive quantifiers — the re2j
  facade keeps rejecting the spellings (oracle-pinned), like `\G`.

## Test plan

Battery (live-JDK oracle, both engine factories): the language cells
above; the tag cells; the pass-through cells; give-back corners
(`(?:a|b)*+b`, `(a|ab)++c`); the bounded form; the nested-repetition
rejects (`a++?`, `a*++`); the re2j-facade rejects. The existing
greedy/lazy batteries stay green bit-identically (possessive is a new
suffix, not a change to the default readings).
