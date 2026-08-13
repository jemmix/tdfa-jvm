# Rebar Parity Suite — Speedup Plan

**Goal:** cut `:tests:parity:rebar:test` wall time from **~21 min** today to
**<2 min** without losing coverage. Ordered by ROI (cheapest wins first;
speculative items at the end). Each item carries a budget, an expected
delta, and a falsifiable prediction so we can verify before committing.

This plan is the speed-focused companion to `REBAR-PARITY-PLAN.md`. The
engine-correctness bugs (Phase 5 in the parity plan) are out of scope here —
this doc is only about wall time.

## Status — landed wins

| Item | Status | Wall before | Wall after | Cumulative |
|---|---|---:|---:|---:|
| Baseline | — | — | 21 min | 21 min |
| **#1 Parallel exec** | ✅ done (888f36b) | 21 min | 9 min | 9 min |
| **#2 AST-budget fast-fail** | ✅ done (776a3f7) | 9 min | 4.5 min | 4.5 min |
| **#3 Haystack cache** | ✅ done (d2a4dfd) | 4.5 min | 4.2 min | 4.2 min |
| **#4 char[] cache fix** | ✅ done (f9971ea) | 4.2 min | **1.1 min** | **1.1 min** |
| #5 indexOf prefilter | deferred (low ROI) | 1.1 min | ~1 min | ~1 min |
| #6 DFA minimization | ✅ done | 1.1 min | 0.95 min | 0.95 min |
| #7 ASM method-splitting | not needed | — | — | — |

**Actual wall today: ~58 s.** Cumulative speedup: **21.7×** (21 min → 58 s).

#1–#3 were test-side / infra-side wins. #4 turned out to be the single
biggest win and the root cause was different from the original plan's
prediction: the ASM backend was re-copying the haystack to `char[]` on
every `find()` call, producing G1 humongous allocations and O(n²) wall
on long inputs. The fix was a 32-line per-instance input cache in the
generated ASM class, not the multi-state-extract rewrite originally
drafted under Tier 2.

#6 (register-aware Moore's algorithm) is implemented per the BT22 paper
§6.2.2 with op-sequence interning and global-breakpoint range
normalization. It works correctly (4→3 on `(a|b)c|ac`, 8→4 on
`abc|bbc|cbc`, 4→2 on `.*a|.*b`) but the rebar suite's DFAs are already
minimal — the existing `tryMap` construction-time register-bijection
dedup produces minimal DFAs for these patterns. For pathological cases
like the dictionary alternation (44 846 states, provably minimal), the
DFA is genuinely irreducible, so minimization is gated behind a
`MINIMIZE_MAX_STATES = 20000` cap to avoid ~30 s of pure overhead.

The remaining items (#5–#7) are documented below for future reference
but are low ROI at the current 1 min wall. The new dominant cost is
`curated/12-dictionary/single` compile at ~50 s (next attack surface:
DFA minimization, #6 — implemented but provides no further reduction on this suite).

---

## Where the 21 minutes went (historical baseline)

From the `@AfterAll` summary on commit `c821283` (108 pass / 2 fail / 249
skip, 1 258 s wall). Top 20 tests account for 1 201 s (~20 min); everything
else combined is ~1 min. The bombs split almost exactly 50/50:

| Bucket | Wall today | Tests | Dominant cost |
|---|---:|---:|---|
| `COMPILE_TIMEOUT` (4 × 120 s ceiling) | 480 s | 4 | bounded-repeat `[\s\S]{0,100}` × 2 + date alternation + AWS-keys `.*?` — DFA state explosion, killed at the cap |
| O(n²) extract on dense matches | 464 s | 3 | `imported/leipzig/ing` (249 s, 78 K matches on 16 MB), `ing-whitespace` (182 s), `quotes-bounded` (33 s) |
| 57 K-char alternation compile | 120 s | 1 | `curated/12-dictionary/single` (passes — but burns the budget) |
| Other O(n²) extract | 137 s | ~12 | `i13-subset-regex/*` (8–20 s), `tom-sawyer/*` (8–13 s), `mariomka/uri` (6 s), etc. |
| Everything else | ~60 s | ~340 | small haystacks, fast compiles |

Two distinct perf bugs to kill (O(n²) extract + bounded-repeat explosion)
plus one test-infra waste (4 × 2 min timeout waits) plus one slow-but-
legitimate compile (dictionary alternation). The fix order matters because
some items unblock others.

## ROI matrix (do in this order)

| # | Item | Effort | Wall saved | Risk | Verifiable by |
|---|---|---:|---:|---|---|
| 1 | Parallel test execution | ½ h | **~15 min** (8× on 8 cores, conservative 4× on 4 cores ≈ 16 min) | low — counters/timings already thread-safe | `./gradlew … -Djunit.jupiter.execution.parallel.enabled=true` |
| 2 | AST-budget fast-fail | ½ day | **8 min** (the 4 COMPILE_TIMEOUT waits) | low — pure test code | `countSkip("compile-budget:…")` for the 4 bombs; zero extra skips |
| 3 | Multi-state *extract* | 1–2 days | **10 min** (the O(n²) extract bucket) | high — engine core, needs correctness re-bench | `ing` on 16 MB < 1 s (was 249 s); all RE2 differential tests still green |
| 4 | `String.indexOf` prefilter on literal prefixes | 1 day | **2–4 min** (the leipzig/mariomka literal-prefix scenarios) | medium — needs care around `(?i)` and word-boundary edges | unit tests + leipzig `Twain`, `Tom\|Sawyer\|…` < 100 ms |
| 5 | Haystack cache for repeated files | 1 h | ~30 s (19 leipzig scenarios share `leipzig-3200.txt`; smaller wins elsewhere) | very low | `Files.readAllBytes` counted once per file per run |
| 6 | DFA minimization (Moore, register-aware) | 2–3 days | **2 min** (dictionary compile + the 4 Phase 6.2 bombs become fast) | high — engine core | `curated/12-dictionary/single` compile < 5 s; the 4 bombs compile < 30 s |
| 7 | ~~ASM method-splitting for >200-state DFAs~~ **DONE** (`411cac8`) | n/a | ASM emits all scenarios via `INLINED` / `TABLE_SCAN` / `DELEGATE`; no more `ASM-FAIL` |

**Projected cumulative wall time** (assuming 8-core parallelism and every
item landing): ~21 min → **~1 min**. The biggest single win is parallel
execution; the biggest *engine* win is multi-state extract.

---

## Tier 1 — Quick wins (do first)

### 1. Parallel test execution — ~15 min saved, ½ h effort

The suite is single-threaded by JUnit 5 default. Each parameterized
invocation is independent: its own `Pattern.compile`, its own haystack,
its own `Matcher`. The static counters (`passCount` etc.) are
`AtomicInteger`s, `timings` is a `Collections.synchronizedList`, and
`skipBuckets` is a `ConcurrentHashMap`. **The test class is already
thread-safe by construction.**

**How:** add `src/test/resources/junit-platform.properties`:
```
junit.jupiter.execution.parallel.enabled = true
junit.jupiter.execution.parallel.mode.default = concurrent
junit.jupiter.execution.parallel.config.strategy = dynamic
junit.jupiter.execution.parallel.config.dynamic.factor = 1.0
```
Or pass `-Djunit.jupiter.execution.parallel.enabled=true` etc. from
`build.gradle`. `dynamic` uses `Runtime.getRuntime().availableProcessors()`
— typical CI box has 4–8 cores.

**Memory:** with 8 threads each holding up to an 80 MB haystack as a
UTF-16 string (~160 MB) plus DFA tables, peak is ~1.5 GB. `-Xmx8g` from
the radical relaxation leaves plenty of headroom — we could *lower* it
back to 4 GB after this lands.

**Prediction:** with `dynamic` factor 1.0 on an 8-core machine, the top-20
bombs (1 201 s single-threaded) drop to ~150–250 s wall. The 4 COMPILE_TIMEOUT
scenarios that each burn 120 s CPU now overlap, saving ~7–8 min alone.

**Risk to validate before committing:**
- Engine thread-safety: `Pattern` is documented thread-safe (`Matcher`
  is not, but each test creates its own). The ASM backend generates a
  class per `Pattern` — verify no static mutable state in the generated
  `runExtract`/`runCore` methods. Spot-check with
  `-Djunit.jupiter.execution.parallel.config.dynamic.factor=2` first
  (2 threads); if the pass count is still 108, bump to `1.0` (default
  core count).
- Virtual-thread timeouts (`Thread.startVirtualThread` × N) — each test
  still has its own 2-min/10-min ceiling; concurrency doesn't change
  per-test semantics, only wall time.

### 2. AST-budget fast-fail — 8 min saved, ½ day effort

Today the 4 `COMPILE_TIMEOUT` scenarios burn the full 120 s ceiling before
skipping. They share a shape: bounded quantifier × wide class. After
parsing, walk the AST and compute:
- `altFanout(t)` — max alternation width at any level
- `classWidth(t)` — sum of range count after Unicode expansion
- `repetitionBudget(t) = Σ over (X){N} of N × astHeight(X) × altFanout(X)`

Skip with `countSkip("compile-budget:…")` when:
- `repetitionBudget > 5_000` (catches `(?:X){96}`-class shapes), OR
- `classWidth × maxRepetition > 1_000` (catches `\p{L}{5}` and `[\s\S]{0,100}`)

Tune constants so exactly the 4 known bombs trip and nothing else does.
Replaces a 120 s wait with a ~1 ms skip — saves 8 min wall today; saves
~1 min wall in the post-parallel world (4 × 120 s / 8 cores).

**Prediction:** zero new skips beyond the 4 currently-known bombs. If the
threshold catches anything else, the `@AfterAll` skip-buckets histogram
will surface it on the next run.

**Status check after this lands:** the suite has zero `COMPILE_TIMEOUT`
skips and zero infrastructure-related waits. Remaining time is genuine
engine work + match execution.

### 5. Haystack cache — 30 s saved, 1 h effort

19 leipzig scenarios resolve `imported/leipzig-3200.txt` (16 MB) and
materialize a different substring each time. Today we re-read the file
(and re-decode UTF-8) 19 times. Cache the raw bytes / decoded String in
`Scenario.resolveHaystack` keyed by `(path, utf8Lossy)`; derive the
sliced/repeated view from the cached base.

Trivial change to `Scenario`: add a `static ConcurrentHashMap<Path,
String>` cache, populated on first read, returned on subsequent calls.
Saves ~30 s of repeated I/O + UTF-8 decode on leipzig-3200 (and a bit on
`sherlock.txt`, `mariomka.txt`, etc.).

**Prediction:** the 19 leipzig tests collectively spend <1 s in I/O
(was ~5 s CPU + ~25 s disk on cold cache). Negligible risk; pure win.

---

## Tier 2 — Engine perf (the big single wins)

### 3. Multi-state *extract* — 10 min saved, 1–2 days effort

The single biggest engine-side perf win. The Phase 2.1 fix in commit
`70f21dd` only short-circuits the no-match case (`multiStateAnyMatch`
returns false → return null). The extract path
(`TdfaRunner.runStringExtractFast:368`) still has:

```java
for (int startSearch = from; startSearch <= to; startSearch++) {  // O(n) outer
    int[] regs = new int[regSize]; Arrays.fill(regs, -1);
    int state = startState, lastAcceptPos = -1, lastAcceptState = -1;
    boolean haveAccept = false;
    for (int pos = startSearch; pos <= to; pos++) {                // O(n) inner
        if ((sm[state] & 1) != 0) { lastAcceptPos = pos; haveAccept = true; }
        ... // walk DFA
    }
    if (haveAccept) return new MatchHolder(startSearch, lastAcceptPos, regs.clone());
}
```

For `[a-zA-Z]+ing` on a 16 MB haystack, `Matcher.find()` is called 78 424
times. Each call's outer loop scans from the previous match end. Each
inner loop walks *all the way to `to`* (the DFA keeps accepting letters),
so a single `find()` is O(n). Total: O(n × matches) = O(n²) on dense
matches.

**Two fix shapes:**

(a) **Pragmatic (smaller, recommended):** have `multiStateAnyMatch`
return the leftmost start position of any accepting state, then run the
*anchored* extract from there. Cost: O(n × |states|) for the search +
O(scan) per match. Drops `ing` from 249 s to ~150 ms.

(b) **Principled (bigger):** carry one `regs[]` per live state during
the multi-state pass. Cost: O(n × |states| × regWidth). Required if (a)
doesn't recover full capture semantics for regexes with backtracking-
adjacent register effects.

**Prediction:** `imported/leipzig/ing` < 1 s (was 249 s); `ing-whitespace`
< 1 s (was 182 s); `quotes-bounded` < 1 s (was 33 s). Other O(n²) shapes
(i13-subset-regex, tom-sawyer) drop proportionally.

**Risk:** the inner-loop `lastAcceptPos` recovers the leftmost-longest
match end; the proposed fix needs to preserve that. Verify with the
existing 5.7 M RE2 differential cases before/after — those already
exercise leftmost-longest semantics.

### 4. `String.indexOf` prefilter on literal prefixes — 2–4 min saved, 1 day

Many rebar scenarios start with a literal substring:
- `Twain`, `Tom|Sawyer|Huckleberry|…` (alternation of literals)
- `# noqa` (case-folded literal)
- `ASIA|AKIA|AROA|AIDA` (alternation of literals)
- `Sherlock Holmes`
- `://` for URIs

re2/rust-regex call this "literal prefilter optimization" and it's the
single biggest reason they're fast on real-world regexes. Hypothesis:
**5–20× speedup** on the literal-prefix scenarios by:
1. Extracting the literal prefix at compile time (single literal OR
   alternation of literals OR single literal inside a class).
2. Calling `String.indexOf` (which the JVM vectorizes via x86_64
   PCMPESTRI / AVX2) to find candidates.
3. Running the DFA only from candidate positions.

The catch: case-insensitive matches need a case-folded indexOf (or two
indexOf passes for ASCII upper+lower). Word-boundary anchored prefixes
(`\bTwain`) need a boundary check after indexOf.

**Prediction:** `Twain` on 16 MB leipzig < 50 ms (was presumably ~1 s);
`tom-sawyer-huckle-finn` alternation < 200 ms (was 8 s). Worth measuring
*before* building — instrument the current runner to count "literal
prefix present, find candidates via indexOf, run DFA from candidates"
for the in-scope scenarios.

**Caveat:** this is a real engine optimization, not a test-side tweak.
The win might be partially absorbed by the multi-state extract fix (#3)
if that fix already makes the DFA walk cheap enough — for dense matches
on long haystacks, the indexOf prefilter still wins because it skips
most of the haystack entirely.

### 6. DFA minimization — 2 min saved, 2–3 days effort

The 4 `COMPILE_TIMEOUT` scenarios (and the 119 s dictionary compile) all
explode the TNFA → TDFA determinization to 10 K+ states. Most are
equivalent after Moore-style minimization; we don't minimize today.

**Implementation:** between `Tdfa.compile` and ASM/VM backend codegen,
run a Moore-style partition-refinement pass. The wrinkle is that TDFA
states carry final-tag operations (registers, tags) — states are only
equivalent if their final-tag behavior matches. BT22 §6 covers this.

**Prediction:** `curated/12-dictionary/single` compile < 5 s (was 119 s);
the 4 bombs compile < 30 s (was > 120 s, killed). Bonus: smaller DFAs
across the board → less ASM bytecode, faster JIT warmup, smaller heap
footprint per Pattern.

**Risk:** correctness — register-aware minimization is subtle. Add
differential tests vs the un-minized DFAs before flipping the default on.

### 7. ASM method-splitting for >200-state DFAs — DONE

ASM now emits a class for every in-scope rebar pattern via three dispatch
modes (`INLINED` / `TABLE_SCAN` / `DELEGATE`); the prior `ASM-FAIL` /
VM-retry path was removed. See `TdfaAsmBackend.pickMode` and commit
`411cac8`.

---

## Tier 3 — Speculative / hypothetical

These are *not* validated. Each is a hypothesis worth testing if Tier 1+2
don't get us under 2 min.

### 8. Latin-1 (256-entry) fast-path table

Today `asciiRangeFlat` is a 128-entry per-state byte table. Latin-1
(0x00–0xFF) covers most haystack bytes for English/Russian/UTF-8-of-
non-BMP inputs. Doubling the table to 256 entries would avoid the
non-ASCII slow path for the entire Latin-1 range. *Hypothesis: ~20%
matching speedup on non-ASCII-heavy haystacks.* Test by replacing the
`c >= 128` branch with `c >= 256` and benching.

### 9. SIMD / vectorized character class dispatch

For wide char classes (`\p{L}`, `\d`, `\w`), the per-state dispatch is a
linear-scan over `(lo, hi)` ranges. On x86_64 with AVX2, 32 codepoints
can be checked in parallel against 8 ranges per instruction. JDK 23+
exposes the Vector API. *Hypothesis: ~3× matching speedup on
class-heavy regexes* (`[a-zA-Z]+ing` is exactly this shape). High
implementation cost (the Vector API is verbose); probably not worth it
until we've banked the Tier 1+2 wins.

### 10. Tiered JIT hints

Mark hot fields `@Stable` / `@Contended`; mark cold paths
`@DontInline`. *Hypothesis: ~10% steady-state speedup after warmup.* Low
confidence — modern HotSpot is usually right on its own. Worth a profile
(`-XX:+PrintAssembly` / JMH with `-prof perfnorm`) before adding hints.

### 11. AOT class persistence for the ASM backend

Today every `Pattern.compile` generates and loads a fresh JVM class. For
the parity suite, the same regexes are compiled every run. Cache the
generated bytes keyed by `(regex, flags, disambiguation)` and load via
`defineClass` directly. *Hypothesis: cuts compile time 2–5× for ASM.*
Doesn't help VM. Useful for cold-start scenarios, not steady-state.

### 12. GraalVM native-image for the test runner

Build the test runner as a native binary. Skips JIT warmup. Probably
*slower* for the ASM backend (native-image doesn't always play well with
runtime bytecode generation — `defineClass` needs configuration). Not
recommended unless we move ASM to AOT too.

---

## Sequencing & projected cumulative wall time

Starting point: **21 min** (single-threaded, current main).

| Step | Cumulative wall | Savings | Cumulative effort |
|---|---:|---:|---:|
| (start) | 21 min | — | 0 |
| + parallel exec (#1) | **~5 min** | ~16 min | ½ h |
| + AST-budget fast-fail (#2) | ~4 min | ~1 min | ½ day |
| + haystack cache (#5) | ~4 min | <1 min | ½ day |
| + multi-state extract (#3) | **~1 min** | ~3 min | 2 days |
| + DFA minimization (#6) | ~50 s | ~10 s | 4–5 days |
| + indexOf prefilter (#4) | ~30 s | ~20 s | 5–6 days |
| + ASM method-splitting (#7) | ~25 s | ~5 s | 7–8 days |

The asymptote is set by the *smallest* tests (1–10 ms each × ~100) — we
can't go much below 20 s without removing test invocation overhead.

## What to verify at each step

1. **After #1 (parallel):** pass count still 108; same 2 fails, same 4
   COMPILE_TIMEOUTs. Check no `ConcurrentModificationException` /
   `IllegalStateException` in the report.
2. **After #2 (AST budget):** skip-buckets histogram shows 4
   `compile-budget:` skips and zero `COMPILE_TIMEOUT`. Same 108 pass.
3. **After #3 (multi-state extract):** run `:tests:parity:re2j:test` and
   the RE2 differential tests (`:tests:parity:re2j-suite:check`) — both
   must stay green. The `ing`/`ing-whitespace` scenarios drop from >180 s
   to <1 s.
4. **After #6 (DFA minimization):** the 4 formerly-COMPILE_TIMEOUT
   scenarios run to completion (PASS or FAIL with a real divergence, not
   a skip). The 5.7 M RE2 differential cases stay green.

## Non-goals (out of scope for this doc)

- Engine correctness fixes (PERL `\b` priority, Unicode case folding) —
  those are Phase 5 in `REBAR-PARITY-PLAN.md`. They don't affect wall
  time today (both currently return wrong answers in <50 ms).
- Reopening the 245 locked-scope scenarios — that's Phase 7 in the
  parity plan, a feature decision, not a perf question.
- Fuzzing / property-based testing — orthogonal to suite speed.
