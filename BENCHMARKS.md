# tdfa-jvm — Benchmarks

All numbers below are from committed artifacts in `benchmarks/` unless noted.
Environment: JDK 26.0.2, macOS arm64 (Apple M-series; single-user laptop —
machine drifts ±30 % between runs; every claim here is min-of-N or JMH
single-shot, and the important comparisons are engine-vs-engine in the same
run). Dated 2026-08-15, post-kernel refactor (one shared search strategy +
generated ASM tier).

Reproduce:

```bash
./gradlew :benchmarks:micro:jmh -Pjmh.include='ParameterizedShortInputBench'   # anchored short inputs
./gradlew :benchmarks:micro:jmh -Pjmh.include='ShortFindBench'                 # short-input search
./scripts/bench-rebar.sh fast|accurate                                          # rebar corpus
# LogExtractMacro: classpath per scripts/bench-rebar.sh, then
#   java io.github.jemmix.tdfa.bench.LogExtractMacro
./scripts/bench-regression.sh                                                   # landing gate vs baselines
```

## 1. Anchored short inputs — `ParameterizedShortInputBench` (JMH, ns/op)

Tight loop over `matches()`, per-single-match time via OperationsPerInvocation.

| Engine | `(a\|b)*c` | `(\w+)\s+(\w+)` | IPv4 | `abc` | `(a+)+b` ReDoS¹ |
|---|---:|---:|---:|---:|---:|
| **tdfa-jvm ASM** | **5.8** | **14.1** | **14.2** | 4.3 | 15.0 |
| tdfa-jvm VM | 5.8 | 14.2 | 14.5 | 4.6 | 15.3 |
| java.util.regex | 112.9 | 40.0 | 55.8 | 34.6 | 1,670 |
| re2j 1.8 | 220.7 | 437.4 | 381.4 | 83.0 | 875.4 |
| reggie | 280.0 | 18.0 | 11.1 | 0.03² | **4.9** |

¹ 20 × `a` + `c` — `java.util.regex` backtracks exponentially: **111× slower
than ASM**. re2j, also linear-time, is 58× slower on this shape.
² Reggie special-cases literal patterns to `String.indexOf` (SIMD). We do this
too when the whole pattern is one literal (disclosed in README) — but not
per-alternative branch; that is the single-algorithm tradeoff.

**vs java.util.regex: 2.8–20× on typical patterns, 111× on ReDoS.**
**vs re2j: 19–58×.**

## 2. Short-input search — `ShortFindBench` (JMH SingleShotTime, ns/op)

Unanchored `Matcher.find()` on 30–65-char inputs — the regime where lazy NFA
engines usually win and per-call overhead dominates. Artifact:
`benchmarks/results-shortfind-jmh.txt`.

| shape | jur | re2j | reggie | VM | ASM | ASM/jur |
|---|---:|---:|---:|---:|---:|---:|
| literal find | 88.4 | 207.3 | 13.8 | 29.8 | **29.4** | 0.33× |
| `(?i)sherlock` | **36.8** | 342.1 | 12.3 | 48.6 | 53.5 | 1.45× |
| `\bword\b` | 219.5 | 528.0 | 42.5 | 81.3 | **80.1** | 0.37× |
| `\p{L}{2,}` (Cyrillic) | **35.0** | 310.8 | 80.5 | 76.2 | 77.4 | 2.21× |
| `[а-яА-ЯёЁ]{4,}` | 74.8 | 287.8 | 4.3 | 100.0 | **42.3** | **0.57×** |
| `"[^"]{5,20}"` | 51.6 | 523.9 | 31.0 | 84.8 | **74.1** | 1.44× |
| IPv4 extract | 171.3 | 1,356.6 | 113.0 | 126.7 | **94.4** | **0.55×** |
| `(a\|b)*c` | 155.9 | 430.6 | 527.7 | **61.3** | 65.1 | 0.42× |
| email no-match | 398.3 | 1,637.0 | 237.7 | **728.1** | 728.2 | 1.83× |
| literal no-match | 44.9 | 36.8 | 11.5 | 24.9 | **24.6** | 0.55× |
| **geomean** | | | | 0.86× jur / 0.18× re2j | **0.77× jur** / 0.19× re2j | |

**ASM beats `java.util.regex` on the geomean** (0.77×); both backends beat
re2j 5×+. The rows jur still wins are the known gaps: `(?i)` and unicode-class
scans (2.2×), dense-candidate no-match scans (1.8×).

## 3. rebar corpus — `RebarBench` (110 scenarios, full haystacks, 5 engines)

Count-verified against `java.util.regex`; interleaved passes. Artifacts:
`benchmarks/results-rebar-fast.txt`, `benchmarks/results-rebar-accurate.txt`.

- Scan geomean vs re2j: **VM 0.44×** (fast) / 0.50× (accurate); ASM 0.83× /
  0.74×.
- Scan geomean vs jur: **VM 0.73×** (fast) / 0.92× (accurate). ASM's fast-mode
  geomean vs jur (1.39×) is dominated by µs-scale micro rows' cold JIT — a
  harness artifact, documented in the artifact headers.
- Per-row (accurate, 70 real-corpus rows): VM vs re2j **60 W / 6 T / 4 L**
  (geomean 0.34×); worst losses are 4 unicode-scan rows. VM vs jur 34 W /
  10 T / 26 L (geomean 0.66×) — losses cluster in literal-prefixed searches
  (the known gap) and unicode wide classes.
- Blowouts: i1095-ascii **200× jur** (and 190× re2j), dictionary **23× jur**,
  lexer-veryl 12×, quadratic shapes 9–12×.
- Literal search `"Twain"` (16 MB): **0.22 ns/char** — `String.indexOf`
  intrinsic path, re2j parity. Wide-class unicode scan (long-russian):
  16 ns/char vs jur 28, re2j 23.

## 4. Log-pipeline extraction — `LogExtractMacro` (200 k logfmt lines)

`Matcher.find()` + group capture per line; cold = first 10 k calls, warm =
min-of-5 × 100 k lines. Artifact: `benchmarks/results-logextract-macro.txt`.

| query | jur | re2j | VM | ASM |
|---|---:|---:|---:|---:|
| `ip=(\d+\.\d+\.\d+\.\d+)` (ns/line, warm) | **294** | 1,352 | 1,261 | **1,252** |
| `user_id=(\d+).*?status=(\d+)` | **237** | 4,712 | 1,294 | **1,227** |
| `path=(/[a-z0-9/]+)` | **197** | 1,830 | 723 | 724 |
| `[a-z]+@[a-z]+\.[a-z]{3}` (no-match) | 1,243 | 3,121 | **1,027** | **1,027** |

**VM ≈ ASM warm on every row** (0.95–1.00×); **geomean 2.3× faster than
re2j** (2.5–3.8× on three rows, parity on the literal-prefixed `ip=` row).
`java.util.regex` wins all four via literal-prefix search — the known gap and
the next work item. No ASM cold penalty (first-10 k passes: ASM 12–41 ms,
VM 7–55 ms).

## 5. Backend comparison — ASM vs VM, and when to pick which

Same strategy, different walk executor (conformance-tested):

| workload | ASM/VM time | why |
|---|---|---|
| Cyrillic class walk (`lettersRu`) | **0.42×** | generated per-state switch dispatch vs table loads |
| IPv4 capture extract | **0.74×** | register ops inlined as straight-line bytecode vs interpreted |
| bounded-span extract | 0.87× | same |
| dense findAll 1 MB | 0.86× | same |
| scan rows (literal / no-match) | 0.98–1.00× | strategy is shared; both delegate |

**Never measurably slower warm.** The costs: compile ~301 µs vs ~52 µs, one
classload per pattern (cold start), metaspace proportional to live patterns
(unloaded with the pattern — verified 10 k-pattern probe).

## 6. Compile latency (µs/pattern, min-of-5 × 500)

| VM | ASM | java.util.regex | re2j |
|---:|---:|---:|---:|
| 52 | 301 | 4.5 | 2.4 |

Eager AOT determinization + (for ASM) emission/classload. Dictionary-scale
patterns cost more (see `compile` rows in the rebar artifacts); this is the
known price of the linear-time guarantee, tracked in TODO.md.

## Historical (pre-scan-acceleration, JDK 26.0.1 — kept for the arc)

The original 2026-08 headline tables (before the search-acceleration and
kernel rounds) are in git history of this file. Notable then-vs-now:
long-input scan 793 → **0.22–16 ns/char** (literal / wide-class), VM-vs-ASM
"4.1×" on scans → **parity** (shared strategy), ASM "4–10× faster than VM"
→ **0.89× geomean, wins only on capture-dense walks**.
