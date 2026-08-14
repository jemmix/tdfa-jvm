# tdfa-jvm

A regex engine for the JVM that compiles every accepted pattern to a tagged
deterministic finite automaton, then to JVM bytecode. **No backtracking — ever.**

- vs [`java.util.regex`](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/regex/package-summary.html): 4–25× faster on typical patterns — a modest improvement. The real difference is **ReDoS immunity**: patterns like `(a+)+b` that send `java.util.regex` into near-infinite loops run in linear time here.
- vs [`re2j`](https://github.com/google/re2j): radically faster — 35–68× across all tested patterns — while remaining a drop-in replacement.
- vs [`reggie`](https://github.com/DataDog/java-reggie): a huge inspiration. They dispatch across multiple regex engines per pattern for peak performance; we use one algorithm for everything by design. Different tradeoffs.

An implementation of Borsotti–Trofimovich 2022
(*A closer look at TDFA* — [paper](https://github.com/skvadrik/re2c/blob/master/doc/papers/2022_a_closer_look_at_tdfa/2022_borsotti_trofimovich_a_closer_look_at_tdfa.pdf)).
Apache 2.0.

## Headline benchmark

JMH AverageTime, ns/op. JDK 26. 500 measurement iterations. Reproduce with `./gradlew jmh`.
Full tables in [`BENCHMARKS.md`](BENCHMARKS.md).

Boolean match, short inputs:

| Engine | `(a\|b)*c` | `(\w+)\s+(\w+)` | IPv4 | `abc` | `(a+)+b` ReDoS¹ |
|---|---:|---:|---:|---:|---:|
| **tdfa-jvm ASM** | **3.6** | **10.5** | **10.5** | **2.7** | 12.6 |
| tdfa-jvm VM | 4.0 | 10.8 | 11.0 | 3.0 | 12.8 |
| java.util.regex | 89.7 | 39.0 | 55.1 | 51.8 | 1,582 |
| re2j 1.8 | 226 | 426 | 368 | 98.6 | 850 |
| reggie | 274 | 17.6 | 11.1 | 0.04² | **4.9** |

¹ Input: 20 × `a` + `c` — `java.util.regex` goes exponential (125× slower than ASM).
² Reggie special-cases literal patterns to `String.indexOf`, which the JVM vectorizes (SIMD). Single-algorithm design means we don't do this.

Long-input scan (1000 chars, pattern never matches):

| Engine | ns/char | vs ASM |
|---|---:|---|
| **tdfa-jvm ASM** | **793** | — |
| tdfa-jvm VM | 3,216 | 4.1× slower |
| java.util.regex | 5,417 | 6.8× slower |
| reggie | 206,776 | 261× slower |

vs `java.util.regex`: **4–25× faster** on typical patterns, **125× faster** on ReDoS.
vs `re2j`: **35–68× faster** everywhere.

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
only the **canonical AOT algorithm** — `Regex.compile()` is the AOT step,
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
- 1127 tests across 14 parameterized parity suites, each running on **both** ASM and VM backends
- Full `re2j` API surface covered (Pattern, Matcher, RE2)
- Perl (leftmost-first) and POSIX (leftmost-longest) disambiguation tested independently
- Zero-width assertions, Unicode, non-BMP, named groups, case folding

**Performance**
- JMH microbenchmarks vs `java.util.regex`, `re2j 1.8`, `DataDog/java-reggie`
- Short-input (11-char) and long-input (1000-char) patterns
- ReDoS resistance verified — `(a+)+b` on non-matching input is linear-time

**Not yet verified**
- No differential fuzzing yet — see [`TODO.md`](TODO.md)

## What's implemented

**Parser** — PCRE-ish subset: literals, classes (`[a-z]`, `\d \w \s`, `[:alpha:]`,
`\p{L}`), `.`, quantifiers (`* + ? {n,m}`, greedy + lazy), alternation, capturing
& non-capturing groups, named groups, anchors (`^ $ \A \z`, multiline `(?m)`),
word boundaries (Unicode + supplementary-codepoint aware under `(?u)`), inline
flags. Rejects `\C`, atomic groups, possessive quantifiers, backreferences,
lookaround, and other backtracking-required syntax.

**Two backends**, same `Tdfa` IR:
- **ASM** (default) — emits a specialized JVM class per regex via runtime
  bytecode generation. 4–10× faster than the VM backend.
- **VM** — table-walking interpreter. Correct, portable, slower.

`EngineFactory` selects the backend per-compile (ASM, VM, or custom lambda).
Default resolved once from `-Dtdfa.engine=ASM|VM`.

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

## Build & test

The repo is a multi-module Gradle build. The evergreen library lives at the
root (`src/main/java/`); the growing test/benchmark surface is split into
self-contained subprojects.

```
tdfa-jvm/                             ← root = the library
├── src/main/java/...                 ← evergreen core (frozen when finished)
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

JDK 17+. Targets JDK 11 bytecode. Vendored deps (re2j, rebar) are extracted
automatically by the `:prepareVendor` task before any test that needs them;
run `./gradlew prepareVendor` once before opening in IntelliJ so generated
sources appear in the IDE. See [`vendor/README.md`](vendor/README.md) for the
upgrade workflow.

## API

```java
import io.github.jemmix.tdfa.re2j.Pattern;
import io.github.jemmix.tdfa.re2j.Matcher;
import io.github.jemmix.tdfa.EngineFactory;

Pattern p = Pattern.compile("(\\w+)@(\\w+)");                    // ASM by default
Pattern p = Pattern.compile(regex, flags, EngineFactory.VM);     // explicit

Matcher m = p.matcher("hello user@host bye");
while (m.find())
    System.out.println(m.group(1) + " @ " + m.group(2));
```

```java
import io.github.jemmix.tdfa.Regex;

Regex r = Regex.compile("(\\w+)\\s+(\\w+)");                      // ASM by default
Regex r = Regex.compile(pattern, EngineFactory.VM);              // explicit
```

## License

Apache License 2.0.

## Citation

Borsotti, Trofimovich. *A closer look at TDFA* (2022).
https://github.com/skvadrik/re2c/blob/master/doc/papers/2022_a_closer_look_at_tdfa/2022_borsotti_trofimovich_a_closer_look_at_tdfa.pdf
