# Two `Pattern` surfaces — re2j-compat onto its own module

Status: **decided, pre-execution** — the one-`Pattern`-vs-two evaluation
([jur-compat work breakdown](jur-compat-wbs.md) item 8) resolves to two
surfaces: the jur-compat `Pattern` keeps the root facade, the re2j-compat
`Pattern` ships from a new `re2j/` gradle module
(`io.github.jemmix.tdfa.re2j.Pattern`), and the seven opt-out bits plus the
`RE2_COMPAT` preset leave the public API. The flag/`Semantics`
representation underneath stays — the lane becomes a choice of surface at
compile time, not a runtime OR. The split is only cheap before the first
Maven publish / 1.0 API lock (both open in [TODO](../TODO.md)); after that
it is a major version. Execution lives in the
[work breakdown](re2j-surface-split-wbs.md); this page is the full
accounting of the shape, the rejected alternatives, and the break.

## Scope and terms

"Two surfaces" means exactly:

- **Facade (root module, `io.github.jemmix.tdfa`) becomes jur-only.**
  `Pattern.compile` defaults to JUR parity outright; the seven opt-out
  bits (`UNIX_LINES`, `UNICODE_CASE`, `CODEPOINT_BOUNDARIES`,
  `EMPTY_LAST_LINE`, `END_OF_TEXT_ONLY`, `EMPTY_ITERATION_SPANS`,
  `UNGREEDY_U`) and the `RE2_COMPAT` preset are removed from the public
  interface. The jur-roster flags stay (`CASE_INSENSITIVE`, `DOTALL`,
  `MULTILINE`, `LONGEST_MATCH`, `UNICODE_CHARACTER_CLASS`, `FIND_ONLY`,
  `DISABLE_UNICODE_GROUPS`, `MULTI_VALUED_TAGS`).
- **`re2j/` module (`:re2j`, `io.github.jemmix.tdfa.re2j`) owns the re2j
  reading.** Its `Pattern` bakes the re2j defaults in — every axis the
  seven bits used to restore is simply the compile's `Semantics` preset.
  No migration OR, no opt-out ceremony; the lane is un-gettable-wrong.
- **The internal representation is untouched.** The `Semantics` carrier,
  the axis pivots, the bit translation in `PatternCompiler` — all remain;
  what changes is who may dial them publicly.
  `CompileOptions.semantics` stays public on both surfaces as the
  per-axis escape hatch (the `PivotLanesTest` shape: mixed-axis probes
  are a debugging tool, not a user contract).
- No ServiceLoader / `META-INF/services` surface exists today, so nothing
  hidden moves — the split is package/gradle work plus the one SPI below.

## The shape, and what was rejected

| shape | verdict |
|---|---|
| one surface + bits + preset (status quo) | rejected — two lineages share one interface; every re2j-lane caller hand-repeats the preset with a comment; the 16-constant surface is the japicmp/API-lock liability ([jur-compat design](jur-compat-default.md) item-8 accounting) |
| thin `:re2j` delegating through a facade-kept semantics route | rejected — only works if the facade keeps public re2j-mode entry points (`compile(String, CompileOptions)` riding `CompileOptions.semantics`), which is exactly the ceremony the split exists to remove |
| duplicated machinery per surface | rejected — see below |
| third shared module (`:engine`/`:pattern`) for the machinery | deferred — `:core` already owns this vocabulary; extraction stays available later |
| **machinery folded into `:core`, two thin peer surfaces** | **the shape** |

### Why not duplicate the machinery

The two lanes differ by one argument — the `Semantics` preset (and which
flag bits validate). Everything else is identical: the
determinize→translate→emit pipeline, budget validation, the emitted-shell
superclass chain, the serialization proxy, the `equals`/`hashCode`
contract. Duplicating means two `PatternCompiler`s kept in lockstep (every
budget fix, prefix change, emit change lands twice), two `TDFAPattern`
heirs — but `ShellEmitter` pins one superclass by descriptor
(`ShellEmitter.java:58-60`), so duplication forces per-lane emission
variants and doubles the conformance pinning in the riskiest area — and
two serialization proxies. ~1k lines copied to avoid a dependency edge
that already exists in substance. That is not YAGNI (which guards against
speculative abstraction); it is copy-paste of concrete shared code.

### Why `:core` and not a third module

The machinery's only `:asm` dependency is two imports and two call sites
(`PatternCompiler.java:238` `ShellEmitter.emit`, `:263`
`TdfaAsmBackend.generate`). Everything else it touches is already
`io.github.jemmix.tdfa.core.*` — and core already owns this vocabulary:
`core.compile` hosts `CompileOptions`/`RegexEngineFactory`,
`core.emit` hosts `EmittedSurface`, `core.engine.Matcher` is the emitted
shell base, and `Semantics`, the budgets, and `PatternSyntaxException`
live there. So:

- `TDFAPattern`, `PatternCompiler`, `PatternMatcher`, `SerialProxy`,
  `FindOnlyWholeEngine` move to `core.compile` (public-but-internal —
  `@ApiStatus.Internal`-style, documented not-shipped). `Pattern` itself
  does not move: each surface keeps its own interface.
- A two-method SPI in `core.compile` (generate the engine, emit the
  shell), implemented in `:asm`, wired in one line by each surface's
  `compile`. `:asm → :core` stays the only edge; no cycle, no new
  published artifact.
- `ShellEmitter`'s three descriptor strings and the generated-shell
  package re-point at the `core.compile` names; `writeReplace` stays
  public (cross-package inheritance from generated shells —
  `TDFAPattern.java:180-186`).

If core must ever return to strictly-engine-only, the extraction is a
later mechanical move — and it would be named by contents (`:pattern`),
never by role (`engine`/`runtime`/`api`/`common` are all banned; the tree
already carries one generically-named module too many).

## The collision: the vendored `re2j` package

`io.github.jemmix.tdfa.re2j` already exists — as the **vendored** re2j
test package in `:tests:parity:re2j-suite` (`scripts/vendor.sh` copy map
+ `vendor/patches/re2j/01-package-rewrite.patch`: `Utils`, `Unicode`,
`UnicodeTables`, `Characters`, `ExecTest`, `Strconv`,
`UNIXBufferedReader` + the `RE2.java` fixture). A production API in that
package puts the same package in two jars — a split package, fatal on the
JPMS module path. The vendored package renames to
`io.github.jemmix.tdfa.re2jsuite` (patch + fixture + module glue); the
production surface takes the bare name.

## Consequences

- **Public break, pre-publish.** ~37 files use `RE2_COMPAT` / the seven
  bits: 14 in `tests/unit` (`Re2CompatFlagsTest` dissolves — its preset
  arithmetic has no public bits to test; `PivotLanesTest` keeps its
  mixed-axis probes via `CompileOptions`), ~15 in `tests/parity/re2j`
  (oracles, parity suites, fuzzers — the campaign matrix's
  `RE2_COMPAT` lane becomes the re2j-surface lane), the `re2j-suite`
  `RE2` fixture, and `testlib/parity` `LayeredComparator`. Jur-lane
  callers see zero import churn — `io.github.jemmix.tdfa.Pattern` keeps
  its package; only `TDFAPattern`/`PatternMatcher` references move.
- **The emitted surface re-pins.** `ShellEmitter` descriptors, the gen
  shell package, `Pattern$Utf8` (`Pattern.java:452`),
  `EmittedSurfaceConformanceTest:133-137`,
  `PatternSerializationTest`, `EmittedBytecodePolicyTest` — mechanical,
  but it is the pinned-emitted-bytecode territory: full `./gradlew check`
  gate, both backends.
- **Serialization breaks once.** `SerialProxy` class names change with
  the package move; serialized forms are not a compatibility promise
  before the first publish (none exists).
- **Artifact count grows to four** — `core`, `asm`, the jur facade, the
  re2j surface — with a fourth japicmp baseline (TODO's "baselines ×4"
  absorbs it). The re2j jar pulls core+asm transitively, same as the
  facade; "which jar" is answered by which `Pattern` you import.
- **CI touch points:** `settings.gradle` + `re2j/build.gradle`,
  the `ci.yml` jars-and-tests job, `ci/Smoke8.java`, `tests/unit`
  `-PagainstJars`, the root `codestyleExempt` list.
- **Narrative:** README's "drop-in" claim maps to an actual package per
  lineage; the [jur-compat design](jur-compat-default.md) open decision
  retires with this page.

## Work breakdown

| item | effort |
|---|---|
| Machinery fold into `core.compile` + the two-method SPI + emission/serialization re-pinning | 2–3 d |
| The `:re2j` surface (module, `Pattern` + flag roster, wiring, surface-aware proxy) | 1 d |
| Facade flag trim (seven bits + preset, `VALID_FLAGS`, javadoc) | 0.5 d |
| Vendored package rename (`vendor.sh`, patch, suite fixture) | 0.5 d |
| Consumer migration (~37 files, mostly preset deletion) | 1–1.5 d |
| Gradle / CI / README / TODO | 1 d |

Total ≈ **6–8 d** (1–1.5 weeks). Ordering: the machinery fold is
landable with zero behavior change (descriptors re-point, tests re-pin,
nothing flips) — same discipline as the jur flip's parameterization item:
the split itself is then one small, separately reviewable commit pair
(facade trim + re2j surface).

## Open decisions

- **re2j-surface flag roster.** Proposal: `CASE_INSENSITIVE`,
  `DOTALL`, `MULTILINE`, `LONGEST_MATCH`, `DISABLE_UNICODE_GROUPS` (the
  re2j dials) plus `FIND_ONLY`/`MULTI_VALUED_TAGS` documented as tdfa
  extensions; no `UNIX_LINES`/`UNICODE_CASE` (those axes are the
  surface's default) and no `UNICODE_CHARACTER_CLASS` (the re2j lane's
  class sets are already the re2j reading; `DISABLE_UNICODE_GROUPS` is
  the re2j dial for them).
- **Matcher typing.** Shared `core.compile.PatternMatcher` (with
  `pattern()` returning a machinery-level type) vs thin per-surface
  wrappers returning their own `Pattern`. Leaning shared — the emitted
  shells construct it by descriptor — but the return type leak needs a
  look at landing time.
- **Sequencing vs the namespace move** (TODO Engineering:
  jemmix → tagmaton, gates publish). Proposal: split first under
  `io.github.jemmix`, rename rides the move — renaming four artifacts
  once beats renaming two twice.
