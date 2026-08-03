# tdfa-jvm

A regex engine for the JVM that compiles every accepted pattern to a tagged
deterministic finite automaton, then to JVM bytecode. **No backtracking — ever.**

- vs [`java.util.regex`](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/regex/package-summary.html): 2–4× faster on typical patterns — a modest improvement. The real difference is **ReDoS immunity**: patterns like `(a+)+b` that send `java.util.regex` into near-infinite loops run in linear time here.
- vs [`re2j`](https://github.com/google/re2j): radically faster — 12–53× across all tested patterns — while remaining a drop-in replacement.
- vs [`reggie`](https://github.com/DataDog/java-reggie): a huge inspiration. They dispatch across multiple regex engines per pattern for peak performance; we use one algorithm for everything by design. Different tradeoffs.

An implementation of Borsotti–Trofimovich 2022
(*A closer look at TDFA* — [paper](https://github.com/skvadrik/re2c/blob/master/doc/papers/2022_a_closer_look_at_tdfa/2022_borsotti_trofimovich_a_closer_look_at_tdfa.pdf)).
Apache 2.0.

## Headline benchmark

JMH AverageTime, ns/op (± 99.9% CI). JDK 26. Reproduce with `./gradlew jmh`.
Full tables in [`BENCHMARKS.md`](BENCHMARKS.md).

Boolean match, short inputs:

| Engine | `(a\|b)*c` | `(\w+)\s+(\w+)` | IPv4 | `abc` | `(a+)+b` ReDoS¹ |
|---|---:|---:|---:|---:|---:|
| tdfa-jvm ASM | **11,249 ± 13,391** | 29,941 ± 1,413 | 28,299 ± 13,101 | 7,221 ± 3,429 | 16,325 ± 0.219 |
| tdfa-jvm VM | 15,546 ± 2,033 | 65,068 ± 24,547 | 60,381 ± 13,666 | 11,446 ± 1,599 | 49,563 ± 20,032 |
| java.util.regex | 92,093 ± 70,775 | 40,275 ± 4,623 | 62,385 ± 7,213 | 30,633 ± 1,129 | 1,651,798 ± 207,479 |
| re2j 1.8 | 226,989 ± 31,115 | 447,525 ± 42,973 | 378,607 ± 27,544 | 85,460 ± 15,739 | 859,977 ± 39,105 |
| reggie | 277,921 ± 2,765 | **18,008 ± 0.709** | **11,415 ± 0.741** | 0.040 ± 0.003² | **5,054 ± 0.608** |

¹ Input: 20 × `a` + `c` — `java.util.regex` goes exponential (101× slower than ASM).
² JIT constant-folded; not representative of real throughput.

Long-input scan (1000 chars, pattern never matches):

| Engine | ns/char | vs ASM |
|---|---:|---|
| **tdfa-jvm ASM** | **793** | — |
| tdfa-jvm VM | 3,216 | 4.1× slower |
| java.util.regex | 5,417 | 6.8× slower |
| reggie | 206,776 | 261× slower |

vs `java.util.regex`: **2–8× faster** on typical patterns, **101× faster** on ReDoS.
vs `re2j`: **12–53× faster** everywhere.

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
