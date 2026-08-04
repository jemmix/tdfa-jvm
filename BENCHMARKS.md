# tdfa-jvm — Benchmarks

JMH AverageTime, ns/op. JDK 26.0.1. 500 measurement iterations. Reproduce with `./gradlew jmh`.

## ParameterizedShortInputBench

Boolean match, short inputs:

| Engine | `(a\|b)*c` | `(\w+)\s+(\w+)` | IPv4 | `abc` | `(a+)+b` ReDoS¹ |
|---|---:|---:|---:|---:|---:|
| **tdfa-jvm ASM** | **3.639** | **10.535** | **10.454** | **2.653** | 12.622 |
| tdfa-jvm VM | 3.967 | 10.777 | 10.956 | 2.965 | 12.805 |
| java.util.regex | 89.692 | 39.032 | 55.139 | 51.844 | 1,582.145 |
| re2j 1.8 | 226.229 | 425.640 | 368.296 | 98.586 | 850.239 |
| reggie | 273.811 | 17.555 | 11.084 | 0.038² | **4.911** |

¹ Input: 20 × `a` + `c` — `java.util.regex` goes exponential (125× slower than ASM).
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
| java.util.regex | **24.6×** | 3.7× | 5.3× | 19.5× | **125×** | **6.8×** |
| re2j | **62.1×** | 40.4× | 35.2× | 37.1× | **67.4×** | — |

## Notes

- **ASM vs j.u.r**: 4–25× faster on typical patterns, 125× faster on ReDoS. Modest but consistent.
- **ASM vs re2j**: 35–68× faster everywhere. Radically better.
- **reggie**: uses multi-engine dispatch (DFA, PikeVM, etc.) — different design point. Faster on short capture-heavy patterns; goes super-linear on long adversarial inputs (261× gap).
- **reggie `abc` at 0.038 ns** is a JIT artifact — HotSpot constant-folds the match when both pattern and input are compile-time constants in the benchmark harness. Not representative of real-world throughput.
