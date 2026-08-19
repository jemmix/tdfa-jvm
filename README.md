# tdfa-jvm

A regex engine for the JVM that compiles every accepted pattern to a tagged
deterministic finite automaton, then to JVM bytecode. **No backtracking — ever.**

- vs [`java.util.regex`](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/regex/package-summary.html): 2.8–20× faster on typical patterns, at parity-or-faster on search workloads — and the real difference is **ReDoS immunity**: patterns like `(a+)+b` that send `java.util.regex` into near-infinite backtracking run in linear time here (111× on the benchmark row).
- vs [`re2j`](https://github.com/google/re2j): **19–58× faster on short matches, 2–6× faster on search workloads** (scan geomeans 0.18–0.50×, faster on the large majority of corpus rows) — while remaining a drop-in replacement with identical results on 5.7 M differential cases.
- vs [`reggie`](https://github.com/DataDog/java-reggie): a huge inspiration. They dispatch across multiple regex engines per pattern for peak performance; we use one algorithm for everything by design. Different tradeoffs.
- **Cost, stated up front**: compile is ~52 µs (VM) / ~301 µs (ASM) per pattern vs ~4 µs for `java.util.regex` — eager AOT determinization is the price of the linear-time guarantee.

An implementation of Borsotti–Trofimovich 2022
(*A closer look at TDFA* — [paper](https://github.com/skvadrik/re2c/blob/master/doc/papers/2022_a_closer_look_at_tdfa/2022_borsotti_trofimovich_a_closer_look_at_tdfa.pdf)).
Apache 2.0.

## Headline benchmark

JMH SingleShotTime, ns/op. JDK 26.0.2, 2026-08-15 (post-kernel refactor).
Reproduce with `./gradlew :benchmarks:micro:jmh -Pjmh.include='ParameterizedShortInputBench'`.
Full tables + committed artifacts in [`BENCHMARKS.md`](BENCHMARKS.md).

Anchored match, short inputs:

| Engine | `(a\|b)*c` | `(\w+)\s+(\w+)` | IPv4 | `abc` | `(a+)+b` ReDoS¹ |
|---|---:|---:|---:|---:|---:|
| **tdfa-jvm ASM** | **5.8** | **14.1** | **14.2** | 4.3 | 15.0 |
| tdfa-jvm VM | 5.8 | 14.2 | 14.5 | 4.6 | 15.3 |
| java.util.regex | 112.9 | 40.0 | 55.8 | 34.6 | 1,670 |
| re2j 1.8 | 220.7 | 437.4 | 381.4 | 83.0 | 875.4 |
| reggie | 280.0 | 18.0 | 11.1 | 0.03² | **4.9** |

¹ Input: 20 × `a` + `c` — `java.util.regex` goes exponential (111× slower than ASM).
² Reggie special-cases literal patterns to `String.indexOf`, which the JVM vectorizes (SIMD). We do this too when the *whole pattern* is one literal — disclosed in the search-acceleration section below — but not per-alternative branch.

Unanchored search (the harder regime for DFA engines; committed artifacts):

| Benchmark | tdfa-jvm VM | tdfa-jvm ASM |
|---|---|---|
| Short-input `find()`, 10 shapes — geomean vs `java.util.regex` | 0.86× | **0.77×** |
| Short-input `find()` — geomean vs re2j | **0.18×** | 0.19× |
| rebar corpus, 110 scenarios — scan geomean vs re2j | **0.44×** (fast) / 0.50× (accurate) | 0.83× / 0.74× |
| Log-field extraction, 200 k lines — geomean vs re2j | 2.3× faster | 2.3× faster |
| Literal search (`"Twain"` in 16 MB corpus) | 0.22 ns/char | 0.22 ns/char (`String.indexOf` path) |

**Known gaps** (so the numbers above stay credible): `java.util.regex` wins
literal-*prefixed* search on medium inputs (its Boyer-Moore-class filtering
beats us ~4× on `ip=`-shaped log queries — tracked as the next work item);
2.2× on unicode-class short-input rows; ASM compile costs ~300 µs/pattern
and a per-pattern classload (cold start) — VM is the zero-codegen tier.

## Vision

A **finished library** — bounded scope, all bugs fixed, then frozen. Think TeX,
not a platform.

One algorithm (TDFA) for every pattern. If a pattern requires backtracking
(backreferences, lookaround), we reject it at compile time rather than silently
falling back to a slower engine.

**AOT, not JIT.** The paper presents two TDFA architectures: canonical
single-pass TDFA with registers (§5–6, suited to ahead-of-time determinization
such as lexer generators) and multi-pass TDFA without registers (§7, suited to
just-in-time determinization such as runtime regex libraries). We implement
only the **canonical AOT algorithm** — compilation is the AOT step,
matching is then bytecode-fast. This mirrors re2c's `src/` (AOT lexer
generator, full §6 optimization pipeline) rather than re2c's `lib/` (JIT
regex library using multi-pass TDFA). The tradeoff is correct for our
compile-once-match-many model; multi-pass is solving a different problem.

**Goals**
- Faithful implementation of BT2022 §5–6 (TNFA, TDFA(1), lookahead tags,
  registers, and the §6 optimization pipeline: fixed tags, register
  optimizations, fallback operations, minimization).
- Drop-in `re2j` replacement that is faster, not slower.
- Compile regexes to JVM bytecode for state-of-the-art throughput.

**Non-goals**
- PCRE / `java.util.regex` backtracking features.
- Multi-engine dispatch ("if backtracking needed, switch to NFA").

## How it was tested

**Correctness**
- **5,716,884** differential cases from RE2's exhaustive test suite — 0 failures
  (each suite runs on both backends)
- 440 unit tests — including a **strategy-conformance sweep**: both backends
  must pick *identical search strategies* (literal / candidate-scan /
  simulation / walk) across a shape × boundary-length catalog, not just
  identical results — the guard against silently running different algorithms
- 1,534 re2j-parity tests with the re2j engine as a live oracle — including
  **leftmost-longest capture parity** (curated + 3 K randomized differential,
  both backends) and **Glenn Fowler's testregex corpus** (578 ERE specs;
  hard gate = re2j `LONGEST_MATCH` parity, Fowler's stricter POSIX
  expectations reported for insight)
- 220 in-scope rebar scenarios × both backends (compile + grep-captures models)
- Full `re2j` API surface (Pattern, Matcher); leftmost-first (default) and
  leftmost-longest (`LONGEST_MATCH`) semantics tested independently; zero-width
  assertions, Unicode, non-BMP, named groups, case folding

**Performance** — all harnesses in-repo, results committed as artifacts
(`benchmarks/results-*.txt`):
- `ParameterizedShortInputBench` / `ShortFindBench` (JMH): anchored /
  unanchored short inputs, 5 engines
- `RebarBench` fast|accurate: 110-scenario rebar corpus, count-verified vs
  `java.util.regex`
- `LogExtractMacro`: log-pipeline field extraction (200 k lines, cold + warm)
- `QuickBench` + per-machine baselines (`scripts/bench-regression.sh`):
  15 % regression gate on every landing

**Not yet verified**
- No differential fuzzing yet — see [`TODO.md`](TODO.md)

## What's implemented

**Parser** — PCRE-ish subset: literals, classes (`[a-z]`, `\d \w \s`, `[:alpha:]`,
`\p{L}`), `.`, quantifiers (`* + ? {n,m}`, greedy + lazy), alternation, capturing
& non-capturing groups, named groups, anchors (`^ $ \A \z`, multiline `(?m)`),
word boundaries (Unicode + supplementary-codepoint aware under `(?u)`), inline
flags. Rejects `\C`, atomic groups, possessive quantifiers, backreferences,
lookaround, and other backtracking-required syntax.

**Two backends, one algorithm** — both run the *same* search strategy (see
"Backend architecture" below) over the same `Tdfa` IR; a conformance test
proves they pick identical strategies, so results and complexity guarantees
are the same by construction. What differs is who executes the walk:

- **ASM** (default) — compiles the pattern all the way to generated JVM
  classes: a per-pattern engine whose walk loop is emitted as bytecode
  (per-state switch dispatch, register ops inlined as straight-line stores),
  plus a generated Pattern/Matcher shell so the whole `find()` chain
  devirtualizes and inlines end-to-end. **0.42–0.89× the VM's time on
  capture-dense walks, never measurably slower warm.** Cost: ~300 µs compile
  and a classload per pattern (per-pattern JIT warmup).
- **VM** — table-walking interpreter, zero code generation. Equal on
  scan-dominated workloads, ~1.1× behind ASM on capture-dense walks, ~52 µs
  compile. The portability/reference tier. Select globally with
  `-Dtdfa.engine=VM` (no code generation anywhere).

**Bring your own engine:** pass any `RegexEngineFactory`
(`Pattern.compile(regex, flags, TdfaRunner::new)`) and the facade emits a
generic shell around your engine — same monomorphic call chain, custom
execution.

**BT22 §5–6 TDFA pipeline** (full faithfulness):
- §5 determinization with `map`+`topological_sort` dedup
- §6.1 UTree tag-path prefix tree (BT19)
- §6.2 fallback operations — backup/restore ops on fallback states for
  correct POSIX longest-match capture extraction
- §6.2.2 register-aware Moore minimization
- §6.3 register optimizations pipeline — compaction, liveness, DCE,
  interference, allocation with copy coalescing, normalization (paper's
  N=2 iteration loop)
- §6.4 fixed tags — drop tags reconstructible post-match from a sibling

Toggle individually: `-Dtdfa.nofixedtags`, `-Dtdfa.noregopt`,
`-Dtdfa.nofallback`, `-Dtdfa.nominimize`.

**Search acceleration, disclosed** — unanchored `find()` does not walk the DFA
character-by-character in three cases, in service of scan throughput (the
rebar-corpus goal: match re2j/jur on bulk text):

1. **Lazy search-DFA trigger** (general mechanism, re2-style): the live-set
   simulation of the implicit `.*?` prefix is memoized into a small lazily
   materialized DFA (512-codepoint blocks, per-`Tdfa`, capped; falls back to
   the unmemoized simulation past the cap or on short inputs). This only
   *finds candidate windows faster* — the tagged DFA still confirms every
   match exactly, and the over-approximation is the same one the pre-check
   always used, so no match can be missed or invented.
2. **Exact-literal fast path**: when the whole regex is a plain literal string
   (no groups, no flags beyond a single case-sensitive literal), `find()` uses
   `String.indexOf` — the JIT's intrinsified vectorized scan. The result is
   bit-identical to the DFA's (`"Twain"` finds the same spans either way).
3. **Latin-1 / BMP block tables** for walk dispatch (O(1) below 256, block
   lookup above).
4. **First-char-set candidate scan** (short inputs, ≤ 64 chars): a bitset of
   the start state's outgoing characters skips positions that cannot begin a
   match; each surviving candidate gets an exact walk. The bitset is a
   superset of true first characters (mask-gated entries included), and the
   walk checks masks exactly — candidates can be tried and rejected, never
   wrongly accepted; coverage is complete because a consuming match must
   consume its first character. Built only when the start state cannot accept
   (an accepting start admits empty matches anywhere, which the bits cannot
   represent — those shapes keep the simulation paths).

**Backend architecture (kernel refactor)** — the search strategy (which
ladder branch serves a call: literal / candidate scan / origin sim /
trigger / walk) lives in exactly one place, the `TdfaRunner`; both backends
call it. The VM backend interprets it directly. The ASM backend generates a
per-pattern class whose `match()` transcribes the same ladder (calling the
runner's monomorphic hooks) and owns only the walk leaf — per-state switch
dispatch with register ops inlined as straight-line bytecode. Under the ASM
default, `Pattern.compile` additionally generates the Pattern/Matcher shell
into the same classloader, so the whole `Matcher.find()` chain
devirtualizes and inlines end-to-end. Non-fastPath DFAs (word boundaries,
anchors, big DFAs) and literal needles compile to thin delegate classes.
A **strategy-conformance test** (`StrategyConformanceTest`, traceable via
`-Dtdfa.trace.strategy`) asserts both backends pick identical strategy
sequences across shape × boundary-length sweeps — the guard against the
ladders drifting with identical results.

These are throughput optimizations only; match semantics (leftmost-first,
captures, anchors, word boundaries) are unchanged and remain fully covered by
the parity suites.

## Build & test

The repo is a multi-module Gradle build: `core` (evergreen pipeline +
interpreter, frozen when finished), `asm` (per-pattern bytecode generation,
swappable backend), and the root facade `tdfa` (the re2j-mirroring API most
users want). The growing test/benchmark surface is split into self-contained
subprojects.

```
tdfa-jvm/                             ← root = the facade artifact (io.github.jemmix:tdfa)
├── core/                             ← tdfa-core: pipeline + core API tier (interpreter-only)
├── asm/                              ← tdfa-asm: per-pattern engine + shell emission
├── tests/
│   ├── unit/                         ← own correctness tests
│   └── parity/
│       ├── re2j/                     ← own parity tests (re2j engine as live oracle)
│       ├── re2j-suite/               ← Google's patched ExecTest + corpus (vendored)
│       └── rebar/                    ← own tests using rebar's scenario corpus
├── benchmarks/micro/                 ← own JMH micros
├── testlib/rebar/                    ← shared parser lib for rebar's TOML scenarios
└── vendor/                           ← pristine third-party archives + patches
```

```bash
./gradlew check                       # run everything: all test modules on both backends
./gradlew :tests:unit:test            # own unit tests only (fast)
./gradlew :tests:parity:re2j:test     # re2j parity suites only
./gradlew :tests:parity:re2j-suite:check  # Google's ExecTest against ASM + VM
./gradlew :tests:parity:rebar:test    # rebar scenario parity (tracer-bullet)
./gradlew :benchmarks:micro:jmh       # JMH microbenchmarks
```

JDK 17+. Vendored deps (re2j, rebar) are extracted
automatically by the `:prepareVendor` task before any test that needs them;
run `./gradlew prepareVendor` once before opening in IntelliJ so generated
sources appear in the IDE. See [`vendor/README.md`](vendor/README.md) for the
upgrade workflow.

## API

The facade mirrors the re2j / `java.util.regex` surface:

```java
import io.github.jemmix.tdfa.Pattern;
import io.github.jemmix.tdfa.core.Matcher;

Pattern p = Pattern.compile("(\\w+)@(\\w+)");                    // generated engine by default
Pattern p = Pattern.compile(regex, flags, TdfaRunner::new);      // bring-your-own engine

Matcher m = p.matcher("hello user@host bye");
while (m.find())
    System.out.println(m.group(1) + " @ " + m.group(2));
```

The core module ships the evergreen, interpreter-only tier (zero
dependencies):

```java
import io.github.jemmix.tdfa.core.CompiledRegex;

CompiledRegex r = CompiledRegex.compile("(\\w+)\\s+(\\w+)");       // leftmost-first
CompiledRegex longest = CompiledRegex.compile(re, CompileOptions.of().longestMatch());
for (MatchResult m : r.findAll(text)) { ... }                  // tag-level captures: m.tag(t)
```

Modules: `tdfa` (facade, depends on `tdfa-asm` + `tdfa-core`) ·
`tdfa-core` (pipeline + interpreter; frozen) · `tdfa-asm` (per-pattern
bytecode generation; swappable).

## License

Apache License 2.0.

## Citation

Borsotti, Trofimovich. *A closer look at TDFA* (2022).
https://github.com/skvadrik/re2c/blob/master/doc/papers/2022_a_closer_look_at_tdfa/2022_borsotti_trofimovich_a_closer_look_at_tdfa.pdf
