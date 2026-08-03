# tdfa-jvm

Tagged deterministic finite automata for the JVM, after Borsotti–Trofimovich 2022
(*A closer look at TDFA* — https://github.com/skvadrik/re2c/blob/master/doc/papers/2022_a_closer_look_at_tdfa/).

Apache 2.0.

## Vision

A **finished library** — bounded scope, all bugs fixed, then frozen.

The same regex never backtracks and never catastrophic-backtracks. One algorithm
(TDFA), compiled to JVM bytecode, for every pattern it accepts. If a pattern
requires backtracking, we reject it at compile time rather than silently falling
back to a slower engine.

**Goals**
- Faithful, readable implementation of BT2022 (TNFA construction, TDFA(1)
  determinization, lookahead tags, register allocation).
- Drop-in `re2j` replacement that is faster, not slower.
- Compile regexes to JVM bytecode for state-of-the-art throughput.

**Non-goals**
- PCRE / `java.util.regex` backtracking features (backreferences, lookaround).
- Multi-engine dispatch (no "if backtracking needed, switch to NFA").

## Correctness checklist

- [x] TNFA construction — BT22 Algorithm 2
- [x] TDFA(1) determinization — BT22 Algorithm 3
- [x] Lookahead tags, register allocation, state deduplication
- [x] Perl (leftmost-first) disambiguation
- [x] POSIX (leftmost-longest) — heuristic; full BT22 §7 closure pending
- [x] Zero-width assertions (`^ $ \A \z \b \B`)
- [x] Equivalence-class alphabet (RE2 byte-class partitioning)
- [x] Non-BMP Unicode (int-based DFA alphabet)
- [x] Case-insensitive `(?i)`, dot-all `(?s)`, named groups, `\Q...\E`
- [x] ASM bytecode backend (runtime code generation, GC-able classes)
- [x] re2j exhaustive differential testing — **5,716,884 cases, 0 failures**
- [x] re2j API parity — 14 parameterized suites (ASM + VM), 1127 tests
- [ ] Multiline mode `(?m)` — flag accepted, not yet honored
- [ ] Full POSIX leftmost-longest (BT22 §7 `closure_gtop`)
- [ ] Differential fuzzing vs `re2j` / `java.util.regex`
- [ ] Deterministic compilation (same regex → identical TDFA across runs)
- [ ] JVM method-size splitting for large automata (>65 KB)

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

## Headline benchmark

JMH AverageTime, ns/op. JDK 26. Lower is better. Reproduce with `./gradlew jmh`.
Full tables in [`BENCHMARKS.md`](BENCHMARKS.md).

| Engine | `(a+)+b` ReDoS | `(a\|b)*c` | `(\w+)\s+(\w+)` | IPv4 |
|---|---:|---:|---:|---:|
| **tdfa-jvm ASM** | **16,558** | **13,896** | **42,026** | **34,841** |
| tdfa-jvm VM | 159,704 | 82,668 | 177,431 | 124,965 |
| java.util.regex | 2,624,056 | 84,770 | 70,085 | 84,118 |
| re2j 1.8 | 833,464 | 214,668 | 421,970 | 356,723 |

ASM beats `java.util.regex` on every tested pattern (1.7–158×) and `re2j`
everywhere (10–50×). No ReDoS vulnerability — `(a+)+b` on a non-matching input
is linear-time, 158× faster than `java.util.regex`.

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

Pattern p = Pattern.compile("(\\w+)@(\\w+)");           // ASM by default
Pattern p = Pattern.compile(regex, flags, EngineFactory.VM);  // explicit

Matcher m = p.matcher("hello user@host bye");
while (m.find())
    System.out.println(m.group(1) + " @ " + m.group(2));
```

```java
import io.github.jemmix.tdfa.Regex;

Regex r = Regex.compile("(\\w+)\\s+(\\w+)");             // ASM by default
Regex r = Regex.compile(pattern, EngineFactory.VM);      // explicit

if (r.matches("hello world")) { ... }
```

Both Perl and POSIX disambiguation supported. See [`TODO.md`](TODO.md) for the
roadmap to the finish line.

## License

Apache License 2.0.

## Citation

Borsotti, Trofimovich. *A closer look at TDFA* (2022).
https://github.com/skvadrik/re2c/blob/master/doc/papers/2022_a_closer_look_at_tdfa/2022_borsotti_trofimovich_a_closer_look_at_tdfa.pdf
