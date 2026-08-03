# tdfa-jvm

A regex engine for the JVM that compiles every accepted pattern to a tagged
deterministic finite automaton, then to JVM bytecode. **No backtracking — ever.**

- vs [`java.util.regex`](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/regex/package-summary.html): about 2× faster on average — a modest improvement. The real difference is **ReDoS immunity**: patterns like `(a+)+b` that send `java.util.regex` into near-infinite loops run in linear time here.
- vs [`re2j`](https://github.com/google/re2j): radically faster — 10–50× across all tested patterns — while remaining a drop-in replacement.
- vs [`reggie`](https://github.com/DataDog/java-reggie): a huge inspiration and a serious optimization effort. They're faster on short capture-heavy patterns today; we aim to reach parity and hope our ideas are useful to them too. But our goal is a **finished, stable library** — SOTA mid-2026, not chasing SOTA forever.

An implementation of Borsotti–Trofimovich 2022
(*A closer look at TDFA* — [paper](https://github.com/skvadrik/re2c/blob/master/doc/papers/2022_a_closer_look_at_tdfa/2022_borsotti_trofimovich_a_closer_look_at_tdfa.pdf)).
Apache 2.0.

## Headline benchmark

JMH AverageTime, ns/op. JDK 26. Lower is better. Reproduce with `./gradlew jmh`.
Full tables in [`BENCHMARKS.md`](BENCHMARKS.md).

| Engine | `(a+)+b` ReDoS | `(a\|b)*c` | `(\w+)\s+(\w+)` | IPv4 |
|---|---:|---:|---:|---:|
| **tdfa-jvm ASM** | **16,558** | **13,896** | 42,026 | 34,841 |
| tdfa-jvm VM | 159,704 | 82,668 | 177,431 | 124,965 |
| java.util.regex | 2,624,056 | 84,770 | 70,085 | 84,118 |
| re2j 1.8 | 833,464 | 214,668 | 421,970 | 356,723 |
| reggie | 528 | 250,320 | 17,400 | 11,149 |

On a 1000-character scan, ASM is **4.3× faster than `java.util.regex`** and
scales linearly where `reggie` goes super-linear (150× gap at 1000 chars).

See [`TODO.md`](TODO.md) for the optimizations still needed to close the gap
with `reggie` on short capture-heavy patterns.

## Vision

A **finished library** — bounded scope, all bugs fixed, then frozen. Think TeX,
not a platform.

One algorithm (TDFA) for every pattern. If a pattern requires backtracking
(backreferences, lookaround), we reject it at compile time rather than silently
falling back to a slower engine.

**Goals**
- Faithful implementation of BT2022 (TNFA, TDFA(1), lookahead tags, registers).
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
- Multiline mode `(?m)` not implemented (flag accepted, ignored)
- Full POSIX closure (BT22 §7) — heuristic only
- No differential fuzzing yet — see [`TODO.md`](TODO.md)

## What's implemented

**Parser** — PCRE-ish subset: literals, classes (`[a-z]`, `\d \w \s`, `[:alpha:]`,
`\p{L}`), `.`, quantifiers (`* + ? {n,m}`, greedy + lazy), alternation, capturing
& non-capturing groups, anchors, word boundaries, inline flags, atomic groups,
possessive quantifiers. Rejects `\C` and backtracking-required syntax.

**Two backends**, same `Tdfa` IR:
- **ASM** (default) — emits a specialized JVM class per regex via runtime
  bytecode generation. 4–10× faster than the VM backend.
- **VM** — table-walking interpreter. Correct, portable, slower.

`EngineFactory` selects the backend per-compile (ASM, VM, or custom lambda).
Default resolved once from `-Dtdfa.engine=ASM|VM`.

## Build & test

```bash
./gradlew test    # 1127 tests, 0 failures
./gradlew jmh     # benchmarks, ~5 min
```

JDK 17+. Targets JDK 11 bytecode.

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
