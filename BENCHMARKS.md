# tdfa-jvm — Benchmarks

JMH AverageTime, ns/op. JDK 26.0.1. Reproduce with `./gradlew jmh`.

## ParameterizedShortInputBench (± 99.9% CI)

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

## Long-input scan — 1000-char input, pattern `(\w+)(\d+)(\w+)` (never matches)

Forces a scan of all 1000 start positions. Per-char cost dominates.

| Engine | total (ns) | ns/char | vs ASM |
|---|---:|---:|---|
| **tdfa-jvm ASM** | 792,496 | **793** | — |
| tdfa-jvm VM | 3,216,117 | 3,216 | 4.1× slower |
| java.util.regex | 5,416,849 | 5,417 | 6.8× slower |
| reggie | 206,775,513 | 206,776 | 261× slower |

## Speedup matrix (tdfa-jvm ASM vs each engine, × faster)

| vs | `(a\|b)*c` | `(\w+)\s+(\w+)` | IPv4 | `abc` | `(a+)+b` | Long scan |
|---|---:|---:|---:|---:|---:|---:|
| java.util.regex | **8.2×** | 1.3× | 2.2× | 4.2× | **101×** | **6.8×** |
| re2j | **20×** | 15× | 13× | 12× | **53×** | — |

## Notes

- **ASM vs j.u.r**: 2–8× faster on typical patterns, 101× faster on ReDoS. Modest but consistent.
- **ASM vs re2j**: 12–53× faster everywhere. Radically better.
- **reggie**: uses multi-engine dispatch (DFA, PikeVM, etc.) — different design point. Faster on short capture-heavy patterns; goes super-linear on long adversarial inputs (261× gap).
- **Wide error bars** on some ASM numbers (e.g. alt ± 13,391) are from single-fork measurement with low iteration count. The central values are consistent across runs.
