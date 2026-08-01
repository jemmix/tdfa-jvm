# tdfa-jvm

A standalone JVM reference implementation of the Borsotti–Trofimovich 2022 TDFA algorithm
(*A closer look at TDFA* — https://github.com/skvadrik/re2c/blob/master/doc/papers/2022_a_closer_look_at_tdfa/).

Apache 2.0.

## What's here

A working silver-bullet proof of concept:

- **Parser** for a PCRE-ish subset: literals, char classes (`[abc]`, `[a-z]`, `\d \w \s` and
  negations), `.`, quantifiers (`* + ? {n} {n,m}`, greedy), alternation, capturing &
  non-capturing groups, anchors `^ $` and `\A \z`, word boundaries `\b \B`, POSIX character
  classes (`[:alpha:]` etc.), Unicode property classes (`\p{L}` etc.), inline flags
  `(?i) (?s) (?-s)`, octal/hex escapes, lazy quantifiers, atomic groups, possessive quantifiers.
  Rejects `\C` (RE2 semantics).
- **TNFA construction** — paper Algorithm 2 (Thompson-style with tagged ε-transitions,
  priorities for leftmost-greedy).
- **TDFA(1) determinization** — paper Algorithm 3 end-to-end: lookahead tags, register
  allocation via `transition_regops`, state deduplication via `map` + topological sort,
  final-register ops. Single-valued tags (sufficient for j.u.r-style captures). Equivalence-class
  partitioned alphabet (RE2-style byte-class partitioning). **Zero-width assertions**
  (`^ $ \A \z \b \B`) are encoded as a per-state entry/accept mask plus a per-transition
  required-mask — position-bound, not pattern-level. Verified against 5,716,884 cases from
  RE2's exhaustive suite with zero failures.
- **Two backends**, both consuming the same `Tdfa` IR:
  - **VM**: table-walking interpreter (`TdfaRunner`). Correct, slower.
  - **ASM (source emission)**: lowers the TDFA to a specialized Java class via
    `javax.tools.JavaCompiler`. Hard-codes per-state dispatch + register ops as straight-line
    code. 4–10× faster than the VM.
- **JMH benchmarks** vs `java.util.regex`, `re2j`, and `DataDog/java-reggie`.

## Headline benchmark

JMH AverageTime, ns/op. JDK 26. Lower is better. Reproduce with `./gradlew jmh`.
See [`BENCHMARKS.md`](BENCHMARKS.md) for the full table.

| Engine | `(a+)+b` ReDoS | `(a\|b)*c` | `(\w+)\s+(\w+)` | IPv4 |
|---|---:|---:|---:|---:|
| tdfa-jvm VM | 159,704 | 82,668 | 177,431 | 124,965 |
| tdfa-jvm ASM | **16,558** | **13,896** | **42,026** | **34,841** |
| java.util.regex | 2,624,056 | 84,770 | 70,085 | 84,118 |
| re2j 1.8 | 833,464 | 214,668 | 421,970 | 356,723 |
| Reggie | 528 | 250,320 | 17,400 | 11,149 |

ASM backend beats `java.util.regex` on every tested pattern (1.7–158× faster) and beats `re2j`
everywhere (10–50×). Reggie still wins on realistic capture-heavy patterns (`(\w+)\s+(\w+)`, IPv4)
because their DFA substrate has optimizations we haven't implemented yet (tag-lifetime, register
coalescing, minimization — see [`TODO.md`](TODO.md)).

## Build & test

```bash
./gradlew test    # 264 tests, 0 failures (includes re2j ExecTest — 5.7M differential cases)
./gradlew jmh     # full benchmark sweep, ~3-5 min
```

JDK 17+ required (project targets JDK 11 bytecode but uses JDK 17 source features).

## API

```java
import io.github.jemmix.tdfa.Regex;

Regex vm  = Regex.compileVm("(\\w+)\\s+(\\w+)");     // interpreted
Regex asm = Regex.compileAsm("(\\w+)\\s+(\\w+)");    // source-emitted, faster

if (asm.matches("hello world")) {
    io.github.jemmix.tdfa.vm.MatchResult m = asm.find("hello world", 0);
    int g1start = m.start(1);  // 0
    int g1end   = m.end(1);    // 5
}
```

## re2j drop-in compatibility

The `io.github.jemmix.tdfa.re2j` package provides a drop-in replacement for Google's re2j:

```java
import io.github.jemmix.tdfa.re2j.Pattern;
import io.github.jemmix.tdfa.re2j.Matcher;
import io.github.jemmix.tdfa.re2j.RE2;

// java.util.regex-style API
Pattern p = Pattern.compile("(\\w+)@(\\w+)");
Matcher m = p.matcher("hello user@host bye");
while (m.find()) {
    System.out.println(m.group(1) + " at " + m.group(2));
}

// RE2-style API (matches re2j's RE2 class)
RE2 re = RE2.compile("foo(.*)");
int[] submatch = re.findSubmatchIndex("foobar");
```

Both Perl (leftmost-first) and POSIX (leftmost-longest) disambiguation modes are supported.
The entire re2j test suite (`ExecTest`) runs verbatim — **5,716,884 differential cases, 0 failures**.

## Status & roadmap

This is a research reference implementation. The mission is to be the canonical mapping of
Borsotti/Trofimovich 2022 → JVM code, with publishable benchmarks. See [`TODO.md`](TODO.md)
for the optimization backlog (data layout compaction, minimization, multi-valued tags,
multi-pass JIT determinization, BT19 POSIX closure activation, etc.).

## License

Apache License 2.0.

## Citation

Borsotti, Trofimovich. *A closer look at TDFA* (2022).
https://github.com/skvadrik/re2c/blob/master/doc/papers/2022_a_closer_look_at_tdfa/2022_borsotti_trofimovich_a_closer_look_at_tdfa.pdf
