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
  on short-input search and **4–11× on anchored matches** — as a drop-in
  replacement with identical results. Against `java.util.regex`: at or ahead
  on search and anchored matching (anchored geomean: ASM 0.22×, VM 0.57×),
  and **ahead on literal-prefixed log queries** (`ip=…` shapes ride the JIT's
  vectorized `String.indexOf` on the required literal prefix). Full tables
  and known gaps: [`BENCHMARKS.md`](BENCHMARKS.md).
- **Drop-in.** re2j-shaped `Pattern`/`Matcher` API, both leftmost-first
  (default) and leftmost-longest (`LONGEST_MATCH`) semantics.

The trade: compilation is eager and slower — ~290 µs (VM) / ~1.3 ms (ASM) per
pattern cold, vs ~16 µs for `java.util.regex` (~32–38 µs steady-state). The
payoff is bytecode-fast linear-time matching. Patterns whose TDFA would be too
large fail compilation with a clean `PatternTooLargeException` ("pattern too
large" — raised where the budget trips, distinct from the parser's
`PatternSyntaxException` for malformed syntax) instead of
exhausting time and memory; the limits are three `-D` properties
(`tdfa.budget.compile.memory`, `tdfa.budget.compile.compute`,
`tdfa.budget.runtime.memory`) — raise them if you legitimately need bigger.
One rejection family has an API escape hatch: by default one artifact serves
`find()` **and** whole-input `matches()` (a side table of un-pruned
continuations recorded during determinization), and a pattern whose whole
divergence blows that side's budget fails the whole compile. If you only ever
`find()`, say so — `Pattern.compileFind(...)` (or the `FIND_ONLY` flag) skips
the whole machinery entirely, accepts exactly what a find-only consumer can
run, and throws `UnsupportedOperationException` from whole-input methods.

Known gap: `java.util.regex` still beats us ~2–4× on unicode wide-class
scans (`\p{L}`, `(?u)\w`-shaped rows in the benchmark tables) — the next
work item. Literal-prefixed search of medium inputs (the `ip=`-shaped log
queries that used to lose ~2–4×) now rides `String.indexOf` over the
required literal prefix and beats `java.util.regex` on both backends.

**Multi-valued tags** (BT22 §3.1): an opt-in flag (`MULTI_VALUED_TAGS`)
where a group under repetition keeps every iteration's offsets
(`matcher(...).groupSpans(g)`) — the one capability `java.util.regex` and
re2j cannot match. Single-value results stay bit-identical. An obscure
corner, kept deliberately: reasoning, usage examples and costs in
[`docs/multi-valued-tags.md`](docs/multi-valued-tags.md).

## Headline numbers

JMH, JDK 26, short inputs, ns/op — lower is better
([artifact](BENCHMARKS.md), 2026-10-01):

| Engine | `(a\|b)*c` | `(\w+)\s+(\w+)` | IPv4 | `abc` | `(a+)+b` ReDoS¹ |
|---|---:|---:|---:|---:|---:|
| tdfa-jvm ASM | **17.0** | **64.9** | **40.0** | 44.8 | **16.2** |
| tdfa-jvm VM | 73.1 | 88.5 | 125.1 | **25.9** | 188.2 |
| java.util.regex | 123.4 | 75.0 | 94.4 | 40.4 | 1,862.5 |
| re2j 1.8 | 269.6 | 586.1 | 420.9 | 101.0 | 883.6 |

¹ 20 × `a` + `c` — `java.util.regex` pays its quadratic backtracking (~1.9 µs);
every engine here that is linear by construction is µs-free.

On unanchored search (the harder regime for DFA engines): geomean **0.75×**
`java.util.regex` and **0.17×** re2j on short-input `find()` (ASM; VM 0.94× /
0.22×); **0.45–0.47×** re2j (VM) / **0.65–0.69×** (ASM) across the
110-scenario rebar corpus; ~1.3–2× faster than `java.util.regex` on the
literal-prefixed log-extraction rows (re2j is 4–10× slower there). Reproduce
with `./gradlew :benchmarks:micro:jmh -PjmhInclude='ParameterizedShortInputBench'`.

## Quick start

```java
import io.github.jemmix.tdfa.Pattern;
import io.github.jemmix.tdfa.core.engine.Matcher;

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

## Contributing

Working principles — do it the hard way, prevent problems by design, reduce
to the simplest form — plus the codestyle ruleset: see
[`CONTRIBUTING.md`](CONTRIBUTING.md).

## Vision

A **finished library** — bounded scope, all bugs fixed, then frozen. Think
TeX, not a platform. One algorithm (TDFA) for every pattern.
[reggie](https://github.com/DataDog/java-reggie) was a huge inspiration; they
dispatch across multiple engines for peak performance, we use one algorithm
for everything — different tradeoffs.

## License

Apache License 2.0.
