# Engine-compat surface: meanings in, syntax out

Design record for the compat-surface ladder. Rung 1 lands as one commit
(the meaning-resolution pass, the symbolic dot, the protocol marks moved
out of the parser, the IR entry); the rungs below it are queued, not
speculative — each answers a standing pain ("patch core all the time to
be compatible with engine X").

## The problem

Engine compatibility work arrives as parser knobs. Every parity delta
between readings — an escape family, a letter binding, a span protocol, a
scan quirk — becomes a new `ParseOptions` axis or a new axis-conditional
branch in `core.parser`, so core grows *readings* when it should only
grow *behaviors*. A third facade (an end-user's ECMAScript- or
Python-compat surface) cannot ship at all without patching core, because
the only entry takes a pattern string in the one shipped grammar.

## The principle

**Core learns behaviors, never engines; syntax is pluggable, semantics
are contributed.** Three consequences:

1. **Meanings, not syntax, cross tier boundaries.** The parse output is
   a syntax-faithful tree (symbolic atoms carrying their scoped flag
   state, structural facts like "this quantifier rides a capture"); a
   meaning-resolution pass applies the compile's `Semantics` to it. The
   facade→machinery contract sits after resolution: the IR is exactly
   what the pipeline can compile.
2. **The IR is inexpressible about what core cannot run.** There is no
   backreference node, no lookaround node. Unsupported constructs are
   rejected by the grammar tier, where the engine's own wording lives
   (`REJECT`-style readings once rung 2 lands); a producer cannot
   smuggle a meaning past the boundary because the vocabulary cannot
   spell it. Vocabulary tracks capability, never leads it: a node kind
   or axis lands in the same commit as the compiler support for it.
3. **Presets are data, never branches** (unchanged from
   [dialect-agnostic-core.md](dialect-agnostic-core.md)) — now extended
   one level up: a facade is a selection (a grammar bundle + a semantics
   preset + a matcher shell), and core additions are named, default-off,
   engine-neutral behaviors any facade can select without knowing their
   provenance.

## The architecture

```
facade (jur · re2j · third-party)
 │  Grammar value   — escape readings, flag letters, group forms, reject messages
 │  Semantics value — tables, fold universe, protocols, terminators
 │  shell           — Lane / Shells / SerializationProxy / matcher roster
 ▼
core Parser        string ──► symbolic AST      (syntax + scoping only)
 ▼
core Resolve       symbolic AST ──► meaning IR  (the one place meanings are applied)
 ▼
meaning IR (validated, inexpressible-unsupported) ──► Tnfa ──► Tdfa ──► VM/ASM
```

A PCRE-family engine facade is a `Grammar` value + a `Semantics` preset
+ a shell — zero new parsing code. An alien syntax (glob, SQL LIKE, a
user DSL) is a hand-rolled producer that builds the meaning IR directly
through the validated entry, composing core's IR vocabulary and
pipeline.

## Rung 1 (this landing)

The re-partition of what the parser already does, no behavior change —
gated by artifact identity (stock lanes bit-identical; family-6 and
zero-width-exit marks are no-ops on the axis-set reading, so the RE2
lane's artifacts never move):

- **`core.semantic.Resolve`** — new package between `parser` and `tnfa`
  in the (bytecode-enforced) layer DAG. It applies the meaning-shaped
  policy axes directly from `Semantics`, so no parse-side axis mirrors
  them:
  - the **symbolic dot** (`Ast.Dot{dotall}`) expands against the
    compile's `Terminators` value (stock constants preserved — stock-lane
    output bit-identical; custom sets build their complement from the
    value);
  - the **family-6 marks** (`Tag.dissolved`/`Tag.surfaceFinal`) and
    `Repeat.zeroWidthExit` are placed from the EMPTY_ITERATION_SPANS
    policy, keyed on the parser's new structural fact
    `Repeat.quantifiedCapture` (set at the capturing group's `)`: the
    same resolved tree shape encodes both `(\b)*` and `(?:(\b))*`, and
    only the former dissolves).
- **`ParseOptions` shrinks to the grammar-shaped axes** (fold universe,
  `\s` table, shorthand fold, escapes, flag bindings): the terminator
  and span-protocol axes are gone from the parser tier; the
  `Tnfa.compile` projection no longer mirrors them.
- **The IR entry**: `Tnfa.compile(ParseResult, multiValuedTags,
  Semantics, observer, meter)` — the same resolution, fixed-tags pass,
  TNFA build and budget doctrine as the string path, minus the parse.
  Producer metadata is validated (tag/group/named-group consistency with
  the tree); symbolic dots in a producer tree resolve against the
  policy. Machinery tier, not a hostile-input boundary: a producer that
  lies about its scan-pivot facts gets its own reading.
- The pass is iterative throughout (one frame stack, results on a value
  stack) — the front-end's standing rule that AST depth costs heap,
  never JVM stack.

## Rungs 2–4 (queued)

- **Rung 2 — the fold/class axes move**: class items become structured
  (written-range/shorthand markers survive into the tree), literal and
  class folding + the `\s`/`\d`/`\w` tables resolve in the pass, and the
  remaining three meaning axes leave `ParseOptions`. What remains is the
  `Grammar` value: `EscapeAlphabet` widens from a two-value enum into
  escape-readings-as-data (letter → reading, `REJECT(message)` included),
  plus group-form admission and error-message templates.
- **Rung 3 — the assertion family**: `StartAnchor`/`EndAnchor`/
  `WordBoundary`/`NoWordBoundary` unify into
  `Assertion{kind, negative}` over a closed kind vocabulary, with kinds
  landing alongside their support (`\G` as a matcher-state predicate,
  grapheme/segment boundaries on the provider's break-property data).
  Anchors migrate in under artifact-identity gates.
- **Rung 4 — the facade kit**: the reject catalog (a documented
  unsupported-families list pinned by a test to the grammar's reject
  readings — the capability manifest), the facade-author guide (the
  Lane/Shells/proxy triple as a template), and a reusable conformance
  harness (differential vs the target engine's live oracle) so a
  third-party facade runs the same discipline the stock lanes do.

## Non-goals

- **Open plugin interfaces** (strategy seams at every divergence):
  protocols as lambdas cannot cross the serialization wire, cannot drive
  ASM emission ("no runtime axis check — the selection is bytecode"),
  cannot be budget-metered, and would make `Semantics` equality — the
  compile cache and wire axis — undefined. Closed vocabularies plus data
  plus a small contribution path is the version of extensible that fits
  this library.
- **Backreference/lookaround nodes "for future proofing"**: the IR names
  only what core can compile (the inexpressible-IR rule). Rejection is
  grammar-tier, with the engine's own wording.
- **A minimal ASCII parser in core**: forking the grammar family buys no
  dependency win (core carries the Unicode machinery regardless) and
  strands core-tier users on a dialect nobody differentially pins. The
  one stock parser stays.
