# tdfa-jvm

A regex engine for the JVM that compiles every accepted pattern to a tagged
deterministic finite automaton, then to JVM bytecode. **No backtracking — ever.**

An implementation of Borsotti–Trofimovich 2022, [*A closer look at TDFA*](https://arxiv.org/abs/2206.01398)
([TeX source](https://github.com/skvadrik/re2c/tree/master/doc/papers/2022_a_closer_look_at_tdfa)).
Apache 2.0.

## Why

- **Linear time by construction.** There is no backtracking engine in this
  library, so pathological patterns cannot burn match-time budget. Current
  JDKs have tamed many `java.util.regex` blowups; the guarantee here is
  structural, not empirical.
- **Fast.** Expect **2–6× faster than [re2j](https://github.com/google/re2j)**
  on short-input search and 2–3× on anchored matches — as a drop-in
  replacement with identical results. Against `java.util.regex`: slightly
  ahead on both search and anchored matching. Full tables and known gaps:
  [`BENCHMARKS.md`](BENCHMARKS.md).
- **Drop-in.** re2j-shaped `Pattern`/`Matcher` API, both leftmost-first
  (default) and leftmost-longest (`LONGEST_MATCH`) semantics.

The trade: compilation is eager and slower — ~290 µs (VM) / ~1.3 ms (ASM) per
pattern cold, vs ~16 µs for `java.util.regex` (~32–38 µs steady-state). The
payoff is bytecode-fast linear-time matching. Patterns whose TDFA would be too
large fail compilation with a clean "pattern too large" error instead of
exhausting time and memory; the limits are three `-D` properties
(`tdfa.budget.compile.memory`, `tdfa.budget.compile.compute`,
`tdfa.budget.runtime.memory`) — raise them if you legitimately need bigger.

Known gap: `java.util.regex` still beats us ~2× on literal-prefixed search of
medium inputs (`ip=`-shaped log queries) — the next work item.

## Headline numbers

JMH, JDK 26, short inputs, ns/op — lower is better:

| Engine | `(a\|b)*c` | `(\w+)\s+(\w+)` | IPv4 | `abc` | `(a+)+b` ReDoS¹ |
|---|---:|---:|---:|---:|---:|
| tdfa-jvm VM | **68.2** | **174.2** | **152.1** | **28.4** | 293.2 |
| tdfa-jvm ASM | 97.0 | 186.5 | 158.9 | 30.7 | 308.7 |
| java.util.regex | 81.2 | 189.7 | 189.6 | 29.8 | **285.3** |
| re2j 1.8 | 259.9 | 482.3 | 415.2 | 93.5 | 1,101.0 |

¹ 20 × `a` + `c`. Every engine here is linear-time on this JDK — the
no-backtracking guarantee is the point, not this row.

On unanchored search (the harder regime for DFA engines): geomean **0.75×**
`java.util.regex` and **0.17×** re2j on short-input `find()`; **0.37–0.51×**
re2j across the 110-scenario rebar corpus; ~2× re2j on 200 k-line log-field
extraction. Reproduce with
`./gradlew :benchmarks:micro:jmh -Pjmh.include='ParameterizedShortInputBench'`.

## Quick start

```java
import io.github.jemmix.tdfa.Pattern;
import io.github.jemmix.tdfa.core.Matcher;

Pattern p = Pattern.compile("(\\w+)@(\\w+)");
Matcher m = p.matcher("hello user@host bye");
while (m.find())
    System.out.println(m.group(1) + " @ " + m.group(2));
```

Modules: `tdfa` (facade, the API above) · `tdfa-asm` (default backend:
per-pattern JVM bytecode) · `tdfa-core` (interpreter-only, zero dependencies,
Java 8 floor). Prefer zero code generation? Run with `-Dtdfa.engine=VM`.

## Supported syntax

PCRE-ish subset: literals, classes (`[a-z]`, `\d \w \s`, `[:alpha:]`,
`\p{L}`), `.`, quantifiers (`* + ? {n,m}`, greedy + lazy), alternation,
groups (capturing, non-capturing, named), anchors, multiline, Unicode-aware
word boundaries, inline flags.

Backtracking-dependent features — backreferences, lookaround, atomic groups,
possessive quantifiers — are rejected at compile time. We never silently fall
back to a slower engine.

## How it's tested

- **5.7 M** differential cases from RE2's exhaustive test suite: 0 failures.
- **~300 M** differential fuzz cases vs re2j (overnight soaks): 0 divergences.
- re2j-parity suites, Glenn Fowler's testregex corpus, OpenJDK's
  `java.util.regex` regression corpus, and a layered oracle (re2j / reference
  Pike VM / VM / ASM) that pins any divergence to a single layer.

## Build & test

```
./gradlew check                    # all gating tests, both backends
./gradlew :tests:unit:test         # fast unit tests only
./gradlew :tests:parity:re2j:fuzz  # differential fuzz soak vs re2j
./gradlew :benchmarks:micro:jmh    # JMH microbenchmarks
```

JDK 25+ to build (artifacts target Java 8). Run `./gradlew prepareVendor` once
before opening in IntelliJ so vendored sources appear. Test JVMs want 2 GB
heap (fuzz: 4 GB). Perf gate: `scripts/bench-regression.sh` (15% rule).
Details: [`vendor/README.md`](vendor/README.md).

## Vision

A **finished library** — bounded scope, all bugs fixed, then frozen. Think
TeX, not a platform. One algorithm (TDFA) for every pattern.
[reggie](https://github.com/DataDog/java-reggie) was a huge inspiration; they
dispatch across multiple engines for peak performance, we use one algorithm
for everything — different tradeoffs.

## License

Apache License 2.0.
