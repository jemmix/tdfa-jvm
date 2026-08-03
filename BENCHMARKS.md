# tdfa-jvm — Benchmarks

JMH AverageTime, ns/op. JDK 26.0.1. Lower is better. Reproduce with `./gradlew jmh`.

## Short-input patterns (boolean match, CaptureBench)

| Engine | `(a+)+b` ReDoS¹ | `(a\|b)*c` | `(\w+)\s+(\w+)` | IPv4 |
|---|---:|---:|---:|---:|
| **tdfa-jvm ASM** | **17,352** | **15,793** | 36,388 | 36,310 |
| tdfa-jvm VM | 57,538 | 20,084 | 64,092 | 61,855 |
| java.util.regex | 1,837,680 | 147,171 | 48,369 | 64,717 |
| re2j 1.8 | 945,049 | 232,059 | 545,922 | 434,960 |
| reggie | <1² | 344,005 | **20,805** | **12,340** |

¹ Input: 20 × `a` + `c` — `java.util.regex` goes exponential (106× slower than ASM).
² JIT constant-folded in this microbenchmark; not representative of real throughput.

## Long-input scan — 1000-char input, pattern `(\w+)(\d+)(\w+)` (never matches)

Forces a scan of all 1000 start positions. Per-char cost dominates.

| Engine | total (ns) | ns/char | vs ASM |
|---|---:|---:|---|
| **tdfa-jvm ASM** | 792,496 | **793** | — |
| tdfa-jvm VM | 3,216,117 | 3,216 | 4.1× slower |
| java.util.regex | 5,416,849 | 5,417 | 6.8× slower |
| reggie | 206,775,513 | 206,776 | 261× slower |

## Short-input patterns (ShortInputBench — higher warmup, 2 forks)

| Engine | `(a\|b)*c` | `(\w+)\s+(\w+)` | IPv4 | `abc` literal |
|---|---:|---:|---:|---:|
| tdfa-jvm ASM | 13,493 | 30,052 | 27,547 | 5,444 |
| tdfa-jvm VM | 27,536 | 108,621 | 97,939 | 18,525 |
| java.util.regex | 89,488 | 47,470 | 98,305 | 33,415 |
| re2j 1.8 | 250,856 | 555,781 | 425,346 | 90,264 |
| reggie | 352,383 | 25,159 | 26,193 | <1³ |

³ JIT constant-folded.

## Speedup matrix (tdfa-jvm ASM vs each engine, × faster)

| vs | `(a+)+b` | `(a\|b)*c` | `(\w+)\s+(\w+)` | IPv4 | Long scan |
|---|---:|---:|---:|---:|---:|
| java.util.regex | **106×** | 9.3× | 1.3× | 1.8× | **6.8×** |
| re2j | **55×** | 14.7× | 15.0× | 12.0× | — |
| reggie | — | 21.8× | 0.57× | 0.34× | **261×** |

## Notes

- **ASM vs j.u.r**: 1.3–9× faster on realistic patterns, 106× faster on ReDoS. Modest but consistent.
- **ASM vs re2j**: 12–55× faster everywhere. Radically better.
- **ASM vs reggie**: Reggie wins on short capture-heavy patterns (1.7–3×) due to tag-lifetime optimization, register coalescing, and minimization — all on our TODO. But reggie goes super-linear on long adversarial inputs (261× gap).
- **Reggie <1 ns** on `(a+)+b` and `abc` is a JIT artifact — HotSpot constant-folds the match when both pattern and input are compile-time constants in the benchmark harness. Not representative of real-world throughput.
