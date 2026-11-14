# The facade kit: building a compat facade

How to ship an engine-compatibility facade over the TDFA machinery —
the sanctioned path, so "compatible with engine X" never means "fork
core." The architecture and doctrine live in
[engine-compat-surface.md](engine-compat-surface.md); this is the
operator's guide. The two shipped facades (`io.github.jemmix.tdfa`
jur-parity, `io.github.jemmix.tdfa.re2j` RE2-lineage) are the reference
implementations for everything below.

## What a facade is

Three selections plus a shell:

1. **A grammar** — which spellings exist and what each rejects. Today
   this is the grammar-shaped `ParseOptions` (the escape alphabet, the
   inline-flag letter bindings, the fold-universe seed); the
   escape-readings-as-data table (`Grammar`) is deliberately deferred
   until a third surface needs it — the closed-vocabulary doctrine
   (see the design record's non-goals).
2. **A `Semantics` preset** — the meaning policy: terminator set, fold
   universe, boundary discipline, span protocols, the `\s` table. One
   immutable value per compile; the meaning-resolution pass
   (`core.semantic.Resolve`) applies it. Bake the lane into the
   facade's default so it is un-gettable-wrong (see both `Lane`
   classes).
3. **A shell** — the API surface: your `Pattern`/`Matcher` types, your
   flag roster and validation, your serialization proxy. Copy the
   shipped triple: `Lane` (the compile funnel), `Shells` (the API-typed
   subclass minting), `SerializationProxy` (pattern + flags + semantics
   payload; recompile on read). The machinery below the funnel is
   facade-blind.

For any PCRE-family engine (ECMAScript, Python, .NET, Ruby, PCRE2),
that is the whole job: point 1 is data, point 2 is a preset, point 3 is
a copy-adapt of ~600 lines across the triple.

## Alien syntaxes: the IR entry

A syntax that is not PCRE-shaped (glob, SQL `LIKE`, a DSL) does not go
through the parser at all. Build the meaning IR directly — the public
`core.ast` vocabulary — and hand it to `Tnfa.compile(ParseResult,
multiValuedTags, Semantics, provider, observer, meter)`:

- The IR **can express exactly what the pipeline can compile**. There
  is no node for a backreference or lookaround — reject those in your
  producer, with your engine's own wording (the reject catalog below is
  the reference list).
- Symbolic atoms (`Ast.Dot`, `Ast.FoldLiteral`, `Ast.Shorthand`,
  `Ast.ClassExpr`, policy-pending `Ast.AssertionKind.DOLLAR`) are
  welcome in a producer tree: the entry resolves them against the
  `Semantics` you pass. Nothing symbolic reaches the builder.
- Producer metadata is validated (tag/group/named-group consistency
  with the tree); the tier is machinery, not a hostile-input boundary —
  a producer that lies about its scan-pivot facts gets its own reading.
- One budget doctrine: pass a `WorkMeter` and it spans your build like
  the parse it replaces.

## The capability manifest (reject catalog)

What core cannot run is rejected at the grammar tier, loudly, with
per-reading wording. The families (pinned cell-for-cell by
`RejectCatalogTest`, which is the executable manifest — extend it when
a reading changes):

| Family | Example | jur lane | re2j lane |
|---|---|---|---|
| Lookahead | `(?=a` | reject | reject |
| Lookbehind | `(?<=a` | reject | reject |
| `\C` (any byte) | `\C` | reject | reject |
| java-only escapes | `\e \R \cX \N{..} \Z \v \h` (+uppercase) | accepted | reject |
| `(?x)` free-spacing | `(?x)a b` | accepted | reject (unbound letter) |

The pinned set is the **stable** exclusions — families outside every open
work item. The families with open TODO items are deliberately unpinned
here (backreference digit precedence, possessive quantifiers, atomic
groups, the grapheme boundary clauses): their readings are moving, and
the catalog grows a row when each lands rather than fighting it.

Contribution path: when core learns a behavior (possessive quantifiers
and atomic groups are on the TODO), it lands engine-neutral and every
facade inherits it — flip your grammar data, never patch core per
engine. **Core learns behaviors, never engines.**

## Conformance: the differential discipline

A compat facade is only as good as its oracle. Do not skip this:

- **Differential vs the live engine**: the parity module
  (`:tests:parity:re2j`) is the template — corpus-driven, cell-for-cell
  against the real oracle (see `JurApiParityTest`,
  `JdkRegressionCorpusTest`, the testregex corpus, and the fuzz/campaign
  harness for overnight soaks). Wire your oracle as another
  `RegexEngineFactory` and reuse the layered comparison (your facade /
  the reference Pike VM / both engine tiers).
- **Artifact identity**: stock-lane compiles must be deterministic —
  pin with the `ArtifactFingerprint` oracle (see
  `DeterministicCompilationTest`).
- **Rejections are behavior too**: pin every reject message against the
  engine's own wording (that is what `RejectCatalogTest` does for the
  stock lanes).

## Checklist

1. `Grammar`/`Semantics` selections + flag roster validation in your
   `Lane` funnel (copy a shipped one).
2. `Shells` + `SerializationProxy` for your API types.
3. Default provider policy (fold universe!) — facade-owned on purpose
   (see `Re2FoldUniverse`).
4. A conformance suite against your engine's oracle, corpus + fuzz.
5. A reject-catalog leg for your lane's wording.
