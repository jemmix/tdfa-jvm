# Two `Pattern` surfaces — work breakdown

Open work only, [TODO](../TODO.md)-style one-liners; when every box is
checked the split has landed and this page retires. The
[design](re2j-surface-split.md) is the full accounting (the shape, the
rejected alternatives, the collision, the break). Feeds the
[TODO](../TODO.md) 1.0 API-lock item; the decision itself retired
[jur-compat work breakdown](jur-compat-wbs.md) item 8.

## 0. Machinery fold into `:core`

Landable with zero behavior change; everything downstream re-points.

- [x] Move `TDFAPattern`, `PatternCompiler`, `PatternMatcher`, `SerialProxy`, `FindOnlyWholeEngine` → `core.compile` (public-but-internal, documented not-shipped)
- [x] Two-method SPI in `core.compile` (generate + emit), implemented in `:asm`, wired by the surfaces' `compile` — the machinery's only asm touch is `PatternCompiler.java:238`/`:263`
- [x] Re-point `ShellEmitter` descriptors (`TDFAPattern`/`PatternMatcher`/`Pattern$Utf8` → `core.compile` names); keep the `io.github.jemmix.tdfa.gen` shell package
- [x] `writeReplace` stays public (cross-package shell inheritance)
- [x] Re-pin `EmittedSurfaceConformanceTest`, `PatternSerializationTest`, `EmittedBytecodePolicyTest`; full `./gradlew check`, both backends

## 1. The `:re2j` surface

- [x] `re2j/` module (`:re2j`, Java 8, `api :core` + `:asm`), package `io.github.jemmix.tdfa.re2j`, `Automatic-Module-Name`
- [x] `Pattern` with the re2j `Semantics` preset baked in; flag roster per the design's open decision 1
- [x] `CompileOptions.semantics` public on both surfaces (the per-axis escape hatch)
- [x] Surface-aware `SerialProxy` (records surface + flags, recompiles on read)

## 2. Facade flag trim

- [ ] Remove the seven opt-out bits + `RE2_COMPAT` from the facade `Pattern`; `VALID_FLAGS` shrinks; javadoc re-narrative

## 3. Vendored package rename

- [x] `io.github.jemmix.tdfa.re2j` (vendored test package) → `io.github.jemmix.tdfa.re2jsuite`: `scripts/vendor.sh` copy map, `01-package-rewrite.patch`, the `RE2.java` fixture + module glue — must precede item 1 on the same classpath

## 4. Consumer migration (~37 files)

- [x] `tests/unit` (14): preset deletion; `Re2CompatFlagsTest` dissolves; `PivotLanesTest` mixed-axis probes move to `CompileOptions`
- [x] `tests/parity/re2j` (~15): oracles, parity suites, fuzzers — the `RE2_COMPAT` lane becomes the re2j-surface lane (campaign matrix axis included)
- [x] `tests/parity/re2j-suite`: the `RE2.java` fixture (rides item 3's rename)
- [x] `testlib/parity`: `LayeredComparator`
- [x] Audit `benchmarks/micro` + `ci/Smoke8.java` (expected: no `RE2_COMPAT` usage; Smoke8 gains a re2j-surface smoke)

## 5. Gradle / CI / docs

- [ ] `settings.gradle` + `re2j/build.gradle`; publishing metadata (fourth artifact + japicmp baseline)
- [ ] `ci.yml` jars-and-tests job, `tests/unit` `-PagainstJars`, `codestyleExempt`
- [ ] README drop-in narrative per surface; TODO refresh (this item's line rewrites to the residual)

## 6. Open decisions to close at landing

- [ ] re2j-surface flag roster (design, open decision 1)
- [ ] Matcher typing: shared `core.compile.PatternMatcher` vs per-surface wrappers (design, open decision 2)
- [ ] Sequencing vs the jemmix → tagmaton namespace move (design, open decision 3)
