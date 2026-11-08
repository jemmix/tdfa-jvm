# SafeRE head-to-head — measured comparison and the borrowability line

Comparison record, 2026-11-08. [SafeRE](https://github.com/eaftan/safere)
1.1.0 (`org.safere:safere`, Maven Central, 2026-11-01) is the closest
living relative of this library: linear-time by construction, a
`Pattern`/`Matcher` String API, RE2 lineage — and, unlike re2j, an
actively optimized 2026 engine. This page records what a same-run
head-to-head measured, *why* each side wins where it does (SafeRE's own
runtime diagnostics attribute the rows), which of its devices could be
borrowed without betraying the [vision](../README.md#vision), what this
library could contribute *back*, and what the comparison implies for
off-JVM AOT — mapping onto the weak-point table of the
[dispatch-engine record](dispatch-engine.md) (W1–W5, 2026-11-04).

Environment: JDK 26.0.2, macOS arm64 (M-series laptop — ratios
within-run are the durable claims, per BENCHMARKS.md; run-to-run drift
measured <±5 % across two full passes). Engine lanes: `jur`
(java.util.regex), re2j 1.8 (SafeRE's ancestor), SafeRE 1.1.0 default
provider, tdfa VM, tdfa ASM. Every cell's match count / presence is
verified against jur in the trial setup.

## The bench

Five new JMH benches (`benchmarks/micro`, `SafeRe*Bench.java`), slugs
mirroring §1/§2 of BENCHMARKS.md plus 64 KB scan regimes:

- `SafeReAnchoredBench` — anchored `matches()`, single-shot, ns/op.
- `SafeReFindBench` — short-input `find()`, ShortFindBench slugs, ns/op.
- `SafeReScanBench` — 64 KB deterministic corpus (word soup + dates +
  one needle; Cyrillic twin), find-once and find-all, µs/pass.
- `SafeReCaptureScanBench` — the same find-alls **reading `group()`** —
  the LogExtractMacro idiom; isolates what capture resolution costs each
  engine (SafeRE defers it; we pay inline tags).
- `SafeReCompileBench` — `Pattern.compile`, single-shot µs.

Artifact: `benchmarks/results-safere-headtohead-jmh.txt`; summary in
BENCHMARKS.md §8. One portability finding worth keeping: script-property
syntax (`\p{Greek}` vs `\p{IsGreek}`) diverges across all four engines —
the corpus sticks to universally-supported class syntax, and the bench's
count oracle (jur) constrains the slug set by design.

## Results

| regime (geomean) | tdfa ASM | tdfa VM | SafeRE | jur | re2j |
|---|---:|---:|---:|---:|---:|
| anchored short matches (ns/op) | **32.9** | 86.2 | 38.0 | 111.6 | 301.6 |
| short-input find (ns/op) | **60.0** | 83.5 | 69.2 | 76.8 | 337.7 |
| 64 KB scans (µs/pass) | 340.8 | 363.8 | **80.4** | 190.2 | 728.2 |
| Pattern.compile (µs) | 2 480–3 210 | 1 390–1 620 | **268–491** | 16–54 | 33–74 |

The scan geomean is dominated by one row (email no-match, 452×);
excluding it SafeRE stays ~2.4× ahead on this corpus. Full tables in the
artifact. The rows that carry the story:

- **tdfa ASM wins per-call work**: anchored 0.87× SafeRE (alt 0.52×,
  IPv4 0.48×), short find 0.87× (Cyrillic class 0.23×, literal 0.57×).
  The `lit`/`redos` rows flip (1.5×) — see why below.
- **SafeRE wins whole-haystack rejection**: email no-match 3.3 µs vs
  1 512 µs per 64 KB pass (~0.05 vs ~23 ns/char); `(?i)twain` 19.6×;
  wide-class no-match `[\p{Lu}\d]{5,}` 8.7×.
- **tdfa wins literal-family extraction**: `\d{2}/\d{2}/\d{4}` find-all
  0.72× — 0.62× with `group()` reads. The `ip=`-shape regime this
  library's prefix-scan rung exists for.
- **Group reads narrow but do not flip the find-all rows**: SafeRE pays
  1.43–1.70× (deferred capture resolution), tdfa 1.11–1.26× (inline
  tags) — SafeRE still ahead on wide-class find-all, tdfa still ahead on
  the literal-family rows.
- **jur is not dead**: it *wins* dense find-all+capture (words: 276 µs
  vs SafeRE 891 / ASM 1 708) — a lazy NFA is ideal when matches are
  short and dense — and loses everywhere it backtracks (redos 1.56 µs)
  or lacks a prefilter (email 491 µs).
- **The compile ladder inverts**: SafeRE 268–491 µs sits between the
  lazy engines and our eager tiers — closer to our bet than to jur's,
  and ~10× above the spin-off sketch's ≤30 µs analysis target.

## Why — rows attributed with SafeRE's own diagnostics

SafeRE ships a runtime listener (`SafeReMatchDiagnostics`); every claim
below is an observed strategy, not inference:

- **No bytecode generation anywhere in SafeRE.** All matching is
  interpreted Java (`Prog`/`Inst`) across a strategy ladder — OnePass,
  lazy forward/reverse DFA with state budgets, BitState, Pike VM NFA —
  plus prefilter layers. That is precisely the regime where our ASM tier
  wins: the alt/two/IPv4 rows ran `ONE_PASS`, and an interpreted program
  paying engine selection loses to generated dispatch with tags in
  locals. SafeRE's `matches()` wins over jur here (0.34×) but not over
  the compiled automaton.
- **The prefilter layer is their scan weapon** (jar: `RejectPrefilter{Literal,
  CharClass, Composite, DisjointLiterals, EndAnchoredSuffix, …}`,
  `StringStartAccelerator{Literal, CharClass, CaseInsensitiveLiteral,
  UnicodeCaseInsensitiveLiteral, …}`). The 452× email row is
  `CHARACTER_CLASS/REJECT_PREFILTER`: extract a character any match must
  contain (`@`), find none, vector-skip 64 KB. `(?i)twain` is a
  folded-literal scanner (0.66 ns/char vs our 13 ns/char fold walk).
  `lit`/`redos` anchored rows lose for the same reason in reverse —
  `LITERAL` / required-char prefilters answer before an engine runs
  (18 ns), while our generated shell has no literal lane (W1).
- **Find-all rows ride a lazy DFA with deferred captures**: counting
  loops never spin up an exact engine (`capture=NONE`); cached
  transitions give ~2 ns/char warm vs our ~19–24 ns/char wide-class
  walk. Reading `group()` forces the deferred resolution — the measured
  1.43–1.70× delta.
- **The Vector API is a byte-lane device**: their SIMD kernels
  (`ScanKind`: BYTE/PAIR/TRIPLE/CLASS/IGNORE_CASE/TEDDY — Teddy from
  Hyperscan, as in Rust regex) serve UTF-8 input only. Enabling
  `-Dorg.safere.experimental.vectorScanProvider=vector` +
  `--add-modules=jdk.incubator.vector` changed **zero** String-API cell
  (±3 %, all rows, two full runs). On Strings, everyone rides the same
  JIT intrinsic (`String.indexOf`) — hence literal-row parity at ~30 µs
  per 64 KB for tdfa/SafeRE/re2j alike.
- **SWAR without the Vector API**: `ByteSwarScan`/`ShortSwarScan` are
  plain-Java long-lane bit tricks — SIMD-within-a-register, no incubator
  module, Java 8-clean.

## What is borrowable under the vision

The [dispatch-engine record](dispatch-engine.md) already drew the line:
dynamic dispatch machinery goes to the spin-off; static ladder rungs are
this repo's native idiom. Sorting SafeRE's devices by that line:

**Mission-true (static, compile-time analysis; the TDFA stays the sole
match authority):**

- **Required-infix needle / required-char reject prefilter** — the
  generalization of our existing single-needle rung from "literal
  prefix" to "must-contain anywhere". Same contract (can only reject,
  no-ops on a hit), computed on the TNFA before determinization.
  Addresses W3 and the 452×/21.5× email rows. Needs its own decision
  record (TODO).
- **Fold-needle sets** — `(?i)` chains with no case-stable run (W3's
  `(?i)kelvin`): enumerate the bounded fold variants of the needle, scan
  each with the intrinsified `indexOf`. No Vector API involved.
- **SWAR scans** for the byte[]/streaming lane and the first-char-set
  rung, where `String.indexOf` cannot apply (the documented no-PREFIX
  gap in [streaming-input.md](streaming-input.md)): long-lane bit
  tricks, zero floor risk.

**Anti-mission here (each already priced on the record):**

- **Lazy DFA / memoized transition cache** — this repo built one (the
  `SearchDfa` memo), measured 2–7× in one narrow regime, and deleted it
  (#97) under the immutability program; see
  [search-dfa-memo-tradeoffs.md](search-dfa-memo-tradeoffs.md) for
  restoration criteria. Eager determinization-to-bytecode is the
  identity of this library.
- **Vector API kernels** — the Java 8 floor is CI-pinned; "bytecode
  floor mismatch" is an explicit spin-off rationale in the dispatch
  record. Java 21+ territory.
- **Adaptive re-routing / deferred captures** — the complexity review
  deleted speculative caches on evidence; deferral buys little against
  inline tags (1.11–1.26× measured delta).

**Validation for the house bet:** SafeRE has no codegen and loses both
per-call regimes to the ASM tier (0.87× geomeans); it has prefilters and
wins whole-haystack rejection. The designs are near-complements — which
is the spin-off's thesis (tdfa as the G lane under an L0 scanner), now
with a same-run number attached to every row of it.

## Their approach — RE2's engine stack, extended

Not "an optimized Pike VM": the Pike NFA is the semantic *floor*, not
the working engine. SafeRE is a faithful port of RE2's multi-engine
design — `Prog`/`Inst`, `OnePass`, `BitState`, `Nfa` are RE2's class
names; `Nfa$NfaThread`/`QueueState` is RE2's sparse-set thread queue
verbatim — extended well past re2j. Compile is *analysis* (~110× jur);
match time executes the plan down a dispatch stack, in roughly this
order (all observed in our traces except where noted):

1. `LENGTH` — minimum-match-length rejection before any engine.
2. Literal / character-class / keyword paths, literal alternation.
3. Start acceleration — skip positions that cannot begin a match
   (literal at fixed offset, class, folded literal).
4. Reject prefilters — required-literal/char/composite; "cannot match
   anywhere" without running an engine (the 452× row).
5. **ShiftDFA** — a port of RE2's 2025 ShiftDFA (`ShiftDfa` +
   `Pattern$ShiftDfaAnalysis` + prepared runners): automaton state in a
   machine word, transitions by shift/mask arithmetic, near-zero table
   memory; lazy resolution with a fallback runner.
6. Lazy memoized **DFA**, forward and reverse, state budgets.
7. **OnePass** — deterministic-per-position programs, capture-capable,
   actions packed into `long` cells (our anchored rows).
8. **BitState** — RE2's bounded backtracker with visited bits; linear
   via work caps.
9. **Pike VM NFA** — only when nothing simpler is authoritative:
   nullable-loop semantics and deferred-capture resolution (the
   group-read bench forced it: 1.43–1.70×).

Two input lanes — String (UTF-16 `StringInputScanner`) and UTF-8 bytes
(`Utf8InputScanner` + `Utf8Matcher`); the SWAR scans are portable
plain-Java, the Vector kernels byte-lane only (measured inert on every
String cell).

One philosophical contrast worth naming: their budget story is
**graceful degradation** — an exhausted DFA cache falls back to a
slower-but-still-linear engine, so matching never fails. Ours is
**typed rejection** — `PatternTooLargeException`, deterministic on
recompile, so match time never surprises. Different contracts, both
defensible; neither is obviously the right one for every consumer.

## Knowledge that could flow the other way

- **The JDK-8381696 fix shape.** The capture-leak bug was *filed from
  SafeRE's own cross-engine effort* (eaftan/safere#52, their deepest
  public analysis) and remains open P4. This library found it
  independently, classified all three defect families, and prototyped
  the OpenJDK patch
  ([jdk-8381696-restart-tag-leak-fix.md](jdk-8381696-restart-tag-leak-fix.md)):
  transaction at the quantifier driver, exact-value restore, possessive
  routing to `GroupCurly`, and the surrogate width-change invariant
  their ASCII-only fuzzing would miss. Their oracle *is* the buggy jur —
  the analysis would let them pre-adapt to the fixed JDK and cross-check
  their own nullable-loop capture handling.
- **The hot-pattern codegen tier evidence.** Their interpreted OnePass
  loses per-call work to our compiled TDFA (0.87× geomeans, `alt` 0.52×,
  IPv4 0.48×), and their own report concedes a real-world `matches()`
  row at 3.16× slower than jur. The BT22 TDFA implementation knowledge
  (tag registers in JVM locals, eager determinization under budgets) is
  the missing tier — for them as much as for the spin-off.
- **Multi-valued tags** (BT22 §3.1, `groupSpans`): every iteration's
  offsets under repetition — neither jur nor SafeRE can express it;
  semantics and costs are written up in
  [multi-valued-tags.md](multi-valued-tags.md).
- **Streaming with a retention floor** — chunked matching with captures
  correct across boundaries, the window pinned at the search's retention
  floor under a loud budget, and the every-split-position differential
  battery ([streaming-input.md](streaming-input.md)); their closest
  surface is direct UTF-8, not chunked streaming.
- **Budget-contract testing discipline** — typed rejections,
  recompile-determinism, raised-knob replay byte-identical, both tiers
  agreeing; a testable contract even for a fallback-based design.
- **Datapoints** — the arm64 same-run head-to-head including the
  String-lane vector null result (their published numbers are Intel
  WSL2), and the cross-engine `\p{Greek}`/`\p{IsGreek}` syntax
  divergence map hit while making the corpus portable.

## Kotlin/Native compile-time compilation — port versus lower

The question: pattern → specialized native matcher at build time
(proc-macro style), on Kotlin/Native. The two architectures sit at
opposite ends of it.

**SafeRE: a port of most of the library that still interprets.** Two
hard blockers stack. (1) K/N has no JVM interop — no JARs, no Java
bytecode — so even *using* it means a KMP port of the ~300-class engine
stack, regenerating the Unicode tables (their generators ride JDK
`Character`/ICU4J, which do not exist on K/N), and re-implementing the
SIMD story (the Vector API is JVM-only). (2) Even ported, there is no
per-pattern code to compile *to*: compile produces `Prog` (data)
interpreted by generic engines; lazy-DFA memoization, engine re-selection
per input regime, and deferred captures are runtime machinery. Build-time
precompilation buys exactly the 268–491 µs Prog build (a serialized
`Pattern`); the match-time zoo ships and runs regardless. A codegen tier
would have to be built first — the per-call rows above are the evidence
it is missing.

**tdfa: nearly the designed-for case.** The TDFA is pure data and match
time is generated code by design — no lazy caches, no engine dispatch,
no input-dependent strategy, nothing runtime-adaptive to port. The
compiler side runs where build tools already run (KSP/Gradle execute on
the JVM), so the build step *is* this library; a `KotlinEmitterBackend`
plugs into `core`'s `EngineBackend` SPI without a fork (the facades pass
`AsmEngineBackend` in one line today). The dense dispatch lowers to
native jump tables; compile budgets become build errors. Costs, honestly:
the Unicode tables are data to emit (mechanical, bulky), and the search
ladder (needle/prefix/first-char rungs) lives in the facade, not the
automaton — port it into the emitter or the long-scan no-match regimes
regress to raw-DFA scanning.

| | SafeRE → K/N | tdfa → K/N |
|---|---|---|
| what ships at match time | the ported engine zoo, interpreted | only the generated per-pattern loop |
| port scope | ~300 classes + table regeneration + SIMD re-implementation | one emitter backend + table/ladder emission |
| what build-time buys | the Prog build (~0.3–0.5 ms/pattern) | all runtime compile + interpretation |
| perf character after | scan/prefilter wins (if kept); per-call stays interpreted | per-call wins carry over, amplified natively; scans need the ladder port |

This is the same family as the Engineering/Wishlist items
*condy/invokedynamic per-regex specialization*, *ahead-of-time class
persistence*, and *GraalVM native-image compatibility* — that idea
generalized off the JVM.

## Reproducing

```bash
./gradlew :benchmarks:micro:jmh -PjmhInclude='SafeRe(Anchored|Find|Scan|CaptureScan|Compile)Bench'
# SafeRE's experimental vector provider (byte lane; no String-API effect measured):
./gradlew :benchmarks:micro:jmh -PjmhInclude='SafeReScanBench' -PsafereVector
```
