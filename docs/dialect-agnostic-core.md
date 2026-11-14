# Dialect-agnostic core: sets as data, alphabets first-class

Design record for the refactor landing as the series tracked in
[wbs-dialect-agnostic-core.md](wbs-dialect-agnostic-core.md).

## The problem

Core's interpretation policy grew out of a two-lineage flip (RE2 descent
vs java.util.regex parity), and the join shows:

- `Semantics` axes are **polarized by lineage**: set = "the RE2 side",
  unset = "the jur side", and two axes carry the lineage in their
  identifiers (`spaceRe2`, `escapesRe2`) and one in its mechanism
  (`ungreedyU` exists only so `Tnfa.compile` can pick a stock
  `FlagBindings` — the one place core branches on lane *identity*).
- Several axes are really **a choice between two hard-coded sets** (the
  ASCII `\s` table, the line-terminator set, the escape alphabet) spelled
  as a boolean. A boolean cannot express a third table, and the sets
  themselves are not data: a dialect cannot bring its own.
- The custom-surface seam `FlagBindings` — the letter&rarr;behavior map —
  is second-class: it is not part of `TDFAPattern` equality and does not
  survive serialization (a serialized custom-alphabet compile silently
  re-derives a stock alphabet from the `UNGREEDY_U` bit on read).

## The principle

Already stated by `FlagBindings`' class doc, applied uniformly:

> **Core owns lineage-free vocabularies. Dialect names appear only as
> selectable values — presets — and presets are data, never branches.**

Three orthogonal vocabularies, one carrier:

- **Tables** (`Terminators`, `ClassTable`): which codepoints a set-shaped
  reading contains. Values, comparable, serializable, printable.
- **Alphabets** (`EscapeAlphabet`, `FlagBindings`): which spellings exist
  (escape letters; inline flag letters) and what each letter does.
- **Policies** (the boolean bits that remain on `Semantics`):
  protocols and disciplines with exactly two readings
  (`EMPTY_LAST_LINE`, `END_OF_TEXT_ONLY`, `SHORTHAND_FOLD`,
  `UNICODE_CASE`, `CODEPOINT_BOUNDARIES`, `EMPTY_ITERATION_SPANS`).
  A well-named boolean is the honest shape for each; they are not sets.

## Placement: a new leaf package `core.dialect`

The package DAG is bytecode-enforced (`PackageLayeringTest`), and the
value types are needed by tiers that cannot see each other: the parser
consumes the space table and the DOT complement, the runner and the ASM
emitter consume the terminator set, the tnfa carrier and the compile
tier hold all of it. No existing package is visible to all of them.

`io.github.jemmix.tdfa.core.dialect` is a new leaf (references nothing
internal), added to the ALLOWED sets of `parser`, `tnfa`, `dfa` and
`compile`. It houses:

- `Terminators` — a set of single codepoints plus at most one ordered
  pair whose interior is not a line boundary (the JDK set is five units
  + the `\r\n` pair; `\n`-only is one unit, no pair).
- `ClassTable` — ascending lo/hi ranges (the `int[]` convention the
  parser's `R_*` constants and `UnicodeDataProvider` already use).
- `EscapeAlphabet` — closed enum (`JDK`, `RE2_MINIMAL`), the D4
  widening seam.
- `FlagAction`, `FlagBindings` — moved from `core.parser` (unchanged
  shape; they were parser-local only while the parser was their sole
  consumer).

Provenance stays where it belongs: comments citing re2j/re2c/JDK
behavior, JDK-mirrored flag names (`UNIX_LINES`, `LITERAL`), and preset
constants named after the engines they quote (`Terminators.JDK`,
`ClassTable.SPACE_RE2`, `FlagBindings.RE2`) — a preset named "RE2" is
honest data; a boolean meaning "switch to the RE2 side" was the smell.

## `Semantics` after

One immutable value per compile, as today, now with four components
beside the policy bits:

```
Semantics.of()                                    // every stock component, no bits (jur default)
  .unicodeCase().codepointBoundaries()            // policy bits (names/values unchanged)
  .emptyLastLine().endOfTextOnly()
  .emptyIterationSpans().shorthandFold()
  .terminators(Terminators.NL_ONLY)               // was the UNIX_LINES bit
  .spaceTable(ClassTable.SPACE_RE2)               // was the SPACE_RE2 bit
  .escapes(EscapeAlphabet.RE2_MINIMAL)            // was the ESCAPES_RE2 bit
  .flagBindings(FlagBindings.RE2)                 // was the UNGREEDY_U bit
```

- The four set/alphabet axes leave the bitset. `bits()` reports only
  the six policy bits; the retired bit values (1, 64, 128, 512) are
  reserved forever.
- `ofBits(int)` — the v1 wire read — **translates**: a retired bit set
  selects the corresponding stock component (`UNIX_LINES` &rarr;
  `Terminators.NL_ONLY`, `UNGREEDY_U` &rarr; `FlagBindings.RE2`, ...).
  Unknown bits still reject loudly. Old streams read correctly; old
  readers cannot read new streams at all (see wire).
- Equality is field equality over bits + components; `TDFAPattern`
  equality, the compile cache and the wire all inherit the fix: two
  compiles differing in a custom table or alphabet are no longer equal,
  and a custom alphabet survives serialization.
- The `Tnfa.compile` flagBindings-override overload collapses: the
  alphabet rides the policy, one parameter, one source of truth. The
  legacy no-`Semantics` entries keep the RE2-lineage preset default
  (pre-flip compat), now spelled as components.

`ParseOptions` mirrors the same values (still no `tnfa` import — the
DAG holds): `terminators(Terminators)`, `spaceTable(ClassTable)`,
`escapes(EscapeAlphabet)`, `flagBindings(FlagBindings)` plus the boolean
withers. The hard-lane sugar is deleted — `re2Lane()`,
`dotNlOnly(boolean)` (which silently set every other axis to the RE2
side) and `ungreedyU(boolean)` (which silently replaced the whole
alphabet). The only way to get a bundle is to name it; `Tnfa.compile`
is the single projection site.

## Match time: the one runtime-consumed set

Every table except the terminators is consumed at parse and baked into
`CharClass` ranges &rarr; alphabet breakpoints &rarr; transition tables;
the boolean was already gone by match time. Only the terminator set
reaches the runner (`lineBegin`/`lineEnd`/`finalEnd` via
`positionFlags`) and the ASM emitter.

- `Tdfa`/`TdfaRunner` carry the `Terminators` value; the three
  predicates generalize over {units, pair}: membership plus
  `c == pairLo && next == pairHi` interior exclusion, and the
  final-terminator-run rule falls out of the pair. Both stock values
  reproduce the current branches exactly (the `\n`-only value has no
  pair, so the pair checks are vacuous — one compare, as today).
- These predicates run only when the artifact's masks/stop-table cells
  actually consult an anchor, behind the existing per-step posFlags
  cache — not in the innermost scan loop. A &le;5-unit membership probe
  is the same compare chain the hand code emits today.
- The ASM tier keeps its doctrine ("no runtime axis check — the
  selection is bytecode"): `PfAxes` carries the value and
  `emitTerminatorCheck` emits one `IF_ICMPEQ` per unit **from the
  data**. For the two stock values the emitted sequence is identical to
  today's (same units, same order), so generated code for stock lanes
  does not move; custom sets get the same specialization for free.

`ParseOptions`' DOT (`.` = complement of the terminator set) uses the
value; the stock values keep today's precomputed `DOT`/`DOT_JUR`
constants so stock-lane parse output is bit-identical.

Predicates (`IntPredicate` lambdas) stop at the authoring tier: they
cannot feed ASM codegen, the breakpoint grid, or serialization. The
machinery speaks ranges/units only — "lambdas at the edge, data in the
core". A future `ClassTable.of(...)` materializer may enumerate a
predicate once at compile time; pathological results explode into the
existing class-count budgets ("pattern too large"), which is the right
backstop.

## Wire format

Proxies (core `SerialProxy`, both facades') keep `pattern + flags +
semanticsBits + providerClass` and **append** component payloads
(terminator units + pair, space ranges, escape-alphabet id, binding
letter&rarr;action pairs; stock components may travel as preset ids for
size). Their `serialVersionUID`s bump: an old reader meeting a new
stream fails loudly (`InvalidClassException`) rather than silently
recompiling in a stock dialect — same doctrine as `ofBits`. A new
reader meets an old stream via the `ofBits` translation. Unknown preset
ids / actions / oversized payloads reject on read.

## Test strategy

1. **Bit-identical artifacts**: stock-lane compiles before/after the
   runner/ASM/parser changes must produce identical `Tdfa` tables (the
   terminator choice never enters transition tables; the `\s`/DOT
   constants are preserved). Pinned by an identity test that hashes the
   materialized arrays across a pattern corpus.
2. **Anchor battery**: the existing JDK-exact anchor/pivot suites
   (`PivotLanesTest`, the parity legs) pin `lineBegin`/`lineEnd`/
   `finalEnd` behavior; the generalization must pass them unchanged.
3. **Wire round-trip**: v1 bits &rarr; components translation; new
   stream round-trip including custom tables/alphabets; loud-failure
   cases.
4. **Value semantics**: equality/hashCode/toString/validation of the
   new types; `FlagBindings` presets renamed and audited.

## Non-goals (seams, deliberately unbuilt)

- Enum-widening beyond two readings per table (a third `\s` table, a
  PCRE escape alphabet): the value types are the seam; nothing else
  needs to change when one lands.
- A formal `Dialect` record for third facades: the facade `Lane`
   funnels already bundle {default policy, roster, provider default};
   formalizing waits for a third surface to justify it.
- Scrubbing lineage words from comments — they are provenance and stay.
