# WBS: dialect-agnostic core

Ladder for [dialect-agnostic-core.md](dialect-agnostic-core.md). Each
step lands as one commit on `origin/main`, compiling and green before
push. Landing protocol under parallel agents: `git fetch origin` before
each push; if `origin/main` moved, rebase the unlanded commits, re-run
the step's gate, push again.

Gates (per step, at minimum): `./gradlew :tests:unit:test` plus the
step-specific suites named below. Final step runs the full `check`.

- [x] **C1 — docs**: this WBS + the design doc + TODO.md pointer.
- [x] **C2 — `core.dialect` value types**: new leaf package
      `Terminators` (units + pair, `JDK`/`NL_ONLY` presets, `isUnit`,
      `dotRanges`, validation), `ClassTable` (ranges, `SPACE_JDK`/
      `SPACE_RE2` presets, `ofRanges`), `EscapeAlphabet` (`JDK`,
      `RE2_MINIMAL`); move `FlagAction`/`FlagBindings` from
      `core.parser`; extend `PackageLayeringTest`'s DAG and the
      `package-info`s. Additive; no pipeline wiring.
      Gate: unit tests incl. new `DialectValuesTest`; layering green.
- [x] **C3 — `Semantics` carries components**: four component fields
      with withers; retired bits (1, 64, 128, 512) reserved;
      `ofBits` translates retired bits to stock components; equality/
      toString over bits + components; `ParseOptions` carries the
      values, hard-lane sugar (`re2Lane`, `dotNlOnly(boolean)`,
      `ungreedyU(boolean)`) deleted; `Tnfa.compile` single projection +
      flagBindings overload collapse (alphabet rides the policy); the
      parser consumes `ClassTable`/`EscapeAlphabet`/`Terminators`
      (`\s` sites, escapes, DOT); both facade `Lane`s and
      `FlagBindings` presets renamed (`JDK`/`RE2`). Legacy derived
      queries (`isUnixLines()`, ...) stay one commit as deprecated
      delegates so C4 can delete their consumers.
      Gate: unit tests incl. `PivotLanesTest`, `Family6BatteryTest`,
      `FlagBindingsTest` updates; parity legs not yet touched (behavior
      unchanged — stock constants preserved).
- [x] **C4 — runner/ASM over the value**: `TdfaRunner` predicates
      generalized over `Terminators`; `nlOnlyTerminators`/
      `isJurTerminator` deleted; `PfAxes` carries the value;
      `emitTerminatorCheck` emits from the units (stock order
      preserved); deprecated derived queries deleted. Add the
      artifact-identity pin (stock corpus hashed before/after) and the
      ASM golden if the emitted-class tests allow it.
      Gate: unit tests (anchor batteries, `PositionFlagMaskTest`,
      `EmittedSurfaceConformanceTest`); `:tests:parity:re2j:test`.
- [ ] **C5 — wire**: proxy payload fields + `serialVersionUID` bumps
      (core `SerialProxy` 5&rarr;6, facade proxies 1&rarr;2), v1-bits
      translation on read, loud rejection tests, custom-table/alphabet
      round-trips; `Semantics`/`FlagBindings` serialization support.
      Gate: unit tests incl. `PatternSerializationTest`; facade
      serialization suites.
- [ ] **C6 — provider move**: `Re2jUniverseProvider` &rarr; `:re2j`
      module renamed after what it supplies (the RE2/Go fold universe);
      core unicode javadocs updated to name readings, not surfaces.
      Gate: `:re2j` compiles; unit tests; parity-re2j leg.
- [ ] **C7 — docs pass + full gate**: `Semantics`/`ParseOptions`/
      `CompileOptions`/`PatternCompiler` javadoc rewrites (polarity by
      behavior, axis list complete), glossary entries, README touch if
      needed; WBS completion sweep; full `./gradlew check` (+ the
      jars job if runnable locally).

Risks & mitigations: stock-exactness by preserved constants +
artifact-identity pin; layer DAG by the bytecode-enforced test; wire
compat by append-only payloads + UID bumps + translation on read;
parallel agents by the fetch-rebase-push protocol above.
