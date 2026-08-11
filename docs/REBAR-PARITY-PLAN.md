# Rebar Scenario Parity — Triage & Plan

**Working rule: commit liberally on every improvement.** Every time the
pass-count goes up, the skip-count goes down, a timeout turns into a pass
(or even a clean fail), or a phase item lands — commit it. Small, bisectable,
self-contained commits make it possible to tell which change moved the needle
and to roll back the ones that didn't. One phase item per commit (or finer);
never accumulate a phase's worth of work into a single drop.

Suite runs in **9 seconds** for the 114 runnable cases (was 12+ minutes
before per-case virtual-thread timeouts landed in `08a83ba`).

## Current breakdown: 359 cases (after the wrong-mode/loader fixes)

| Bucket          | Count | Meaning                                                            |
|-----------------|-------|-------------------------------------------------------------------|
| PASS            | 106   | Engine produces correct count                                     |
| FAIL            | 8     | Real semantic divergence from rebar's expected count              |
| SKIP            | 245   | Filtered out — see "skip categories" below                        |

Of the 245 skips, the large buckets are 153 `HAYSTACK_TOO_BIG` (200 KB cap),
61 `UNSUPPORTED_MODEL`, 17 `REGEX_TOO_LONG`, and the rest are real
compile/parse failures. The skip reasons are surfaced in each test's
`Assumption failed: …` message so they're visible in IDE/CI rather than
silently filtered.

## The 8 failures, grouped by root cause

### A. PERL `\b` + alternation priority (6 tests) — biggest bucket

```
curated/05-lexer-veryl/single         want=124800  got=123000
curated/05-lexer-veryl/multi          want=150600  got=145800
wild/parol-veryl/ascii                want=124800  got=123000
wild/parol-veryl/unicode              want=124800  got=123000
wild/parol-veryl/multi-patternid      want=150600  got=145800
wild/parol-veryl/multi-captures       want=124800  got=123000
```

**Root cause**: when an earlier alternation begins with `\b` and a later
alternation matches the same prefix through a char-class, PERL disambiguation
picks the wrong (later) group. Minimal repro:

```
(\bvar\b)|([a-zA-Z_][0-9a-zA-Z_]*)   on   "var"
```

returns group 2 (identifier) instead of group 1 (`\bvar\b`). Both backends
repro, so the bug is in TDFA compilation, not in a runner. Each missed token
sums to ~1 800 captures / ~4 800 spans per scenario.

**Fix**: isolate the priority bug in `Tdfa.compile` (likely in
`computePerStateOrder` or the `stopOnAcceptMask` build at `Tdfa.java:337`),
add a unit test from the minimal repro above.

### B. Unicode case folding for single-char literals (1 test)

```
test/unicode/case/ascii-with-unicode   want=1  got=0   /s/  on  'ſ'
```

**Root cause**: `Parser.java:160-164` and `:537-541` only fold via
`Character.toLowerCase` / `toUpperCase`. That doesn't see the ASCII → Unicode
folds like `s ↔ ſ` (U+017F). re2j/re2 do Unicode simple case folding.

**Fix**: extend `UnicodeDataProvider` with a `caseFolds(codepoint)` API and
use it in those two parser paths.

### C. `.` on non-BMP under-counts vs re2's byte semantics (1 test)

```
test/unicode/utf8/dot-matches-byte   want=4  got=1   /./  on  '💩'
```

**Root cause**: our engine matches codepoints (1 for `💩`); re2 matches
UTF-8 bytes (4). Our behaviour matches `java.util.regex`, not re2.

**Fix**: pick `java/.*` count from the per-engine list for this scenario
instead of `re2`, or skip with a clear "engine-architecture" reason.

---

## Skip categories (245 tests)

| Reason                  | Count | Notes                                              |
|-------------------------|------:|----------------------------------------------------|
| `HAYSTACK_TOO_BIG`      |   153 | Cap is 200 KB; most are 1–10 MB files              |
| `UNSUPPORTED_MODEL`     |    61 | `compile`, `grep-captures`, `regex-redux`           |
| `REGEX_TOO_LONG`        |    17 | Cap is 2 KB; dictionary mega-alternations          |
| `COMPILE_FAIL`          |     5 | Long-form `\p{Letter}` etc. not supported           |
| `HAYSTACK_RESOLVE`      |     5 | Invalid UTF-8 — Java strings can't represent them  |
| `RUN_TIMEOUT`           |     3 | O(n²) `find()` on no-match patterns                |
| `COMPILE_TIMEOUT`       |     1 | `\p{L}{256}` — 510 ms on VM                         |

---

# Plan: get the full suite running end-to-end

**Goal:** every one of the 359 scenarios runs to completion — no timeouts, no
`OutOfMemoryError`s. Failures stay (they're the real engine bugs the parity
test surfaces) but infrastructure-skip causes go to zero, or to a small,
documented set we accept.

## Where the bombs are today

Profiled directly (not from the test report, which under-reports because the
filters hide things):

| Class | Repro | Cost today | Root cause |
|---|---|---|---|
| **Compile: alternation under `{N}`** | `(a\|b\|c\|...\|z){50}` | **1.5 s**, 1301 DFA states | Tnfa expands the repetition × alternatives; Tdfa determinization builds 25× the NFA's states |
| **Compile: `\p{...}` under `{N}`** | `\p{L}{5}` | ASM MethodTooLarge; `\p{L}{256}` is 510 ms on VM | ASM backend inlines every range check (`emitDfaDispatch:797`), `\p{L}` has ~400 Unicode ranges → ~30 KB bytecode per state |
| **Compile: huge alternations** | noseyparker (96 secret-detector patterns) | **>30 s**, never finishes | Same as above but worse — 96-way alternation of complex sub-patterns causes state explosion in Tdfa |
| **Compile: any wide class + repetition** | i787-keywords, parol-veryl | ASM MethodTooLarge (~11 scenarios) | `emitDfaDispatch` design |
| **Compile: long-form Unicode property** | `\p{Letter}`, `\p{gc=Letter}`, `\p{math}` | Parse error | Parser only knows 1-/2-letter aliases, not the long names |
| **Run: O(n²) `find()` on no-match** | `opt/nfa-sparse/small-repeated-class-{bytes,unicode}` | ~30 s on 92 KB haystack, >5 s on most `curated/06-cloud-flare-redos` shapes | `TdfaRunner.runStringFindFast:227` and `runStringExtractFast:252` restart the DFA from every position; same shape in ASM `TdfaAsmBackend.emitRunCore:510` |
| **Memory: large haystacks** | 153 scenarios over the 200 KB cap | Skipped today | The cap is conservative — 2 GB heap can easily hold the 18 MB largest *tested* haystack (leipzig-3200); even the 39 MB `lh3lh3-reb-howto.txt` is fine. The real risk is `repeat = N` in the spec blowing the resolved size |
| **Models** | 61 scenarios | Skipped today | `compile`, `grep-captures`, `regex-redux` not implemented in the harness |

The smallest bucket of "real work" is making the test infra robust; the biggest
single perf win is the O(n²) `find()` fix; the biggest *correctness* win is the
PERL `\b` + alternation priority bug.

---

## Phase 1 — fast-fail the compile bombs (≈ ½ day, low risk)

Goal: never hang or burn the full timeout budget on a regex we can predict is
a bomb. Replace "compile then maybe timeout" with "estimate, then decide."

### 1.1 Pre-compile AST budget check

After parsing, before `Tdfa.compile`, walk the AST and estimate:

- `astHeight(t)` — quantifier nesting depth (`(?:A+){200}` is height 2 but the
  repetition count multiplies cost; treat `(?:X){N}` as height `N × height(X)`).
- `altFanout(t)` — max alternation width at any level (`(a|b|...|z)` = 26).
- `classWidth(t)` — for char classes, sum of range count after Unicode
  expansion (`\p{L}` ≈ 400).
- `repetitionBudget(t) = Σ over (X){N} of N × astHeight(X) × altFanout(X)`.

Skip the scenario with `assumeTrue(false, "compile-budget: …")` when:

- `repetitionBudget > 5_000` (catches `(?:A+){200}` ≈ 200, noseyparker's
  `(?:X){96}` where X itself has alternation, `(a|...|z){50}` ≈ 1 300), **or**
- `classWidth × maxRepetition > 1_000` (catches `\p{L}{5}` = 2 000), **or**
- raw regex length > 32 KB (catches `huge-character-class.txt`).

Tune the constants by re-running the suite until exactly the known bombs are
flagged and nothing else.

### 1.2 Catch OOM and other `Throwable` at compile

`withTimeout` runs in a virtual thread that may OOM before the timeout fires.
Wrap the body in `try { … } catch (Throwable t)` and convert
`OutOfMemoryError` to a skip with reason `"compile OOM"`. Don't swallow — log.

### 1.3 ASM MethodTooLarge: VM retry is already in place

Keep the existing `looksLikeAsmOnlyFailure` path; just make sure the
`ASM-FAIL` log line is parsed by the suite summary so we can see the count
without grepping stdout.

### 1.4 Add a global `COMPILE_HARD_TIMEOUT_MS = 5_000`

The 300 ms per-compile budget is fine for fast tests but the suite has
legitimate ~250 ms compiles (parol-veryl). Bump to a more forgiving 5 s as the
hard ceiling; the budget check above is the *primary* filter, this is the
safety net for anything we misjudged.

**Exit criterion for Phase 1:** zero `COMPILE_TIMEOUT` and zero
`COMPILE_OOM` skips in the suite; everything we don't run is skipped by
`compile-budget`.

## Phase 2 — handle the run-time bombs (≈ 1 day)

### 2.1 Multi-state unanchored `find()` for the VM backend

Single biggest perf win. Replace the `for (int from = 0; …) { int state =
startState; … }` loop in `runStringFindFast` / `runStringExtractFast` /
`runStringExtract` with a single forward pass that maintains the *set* of
states the DFA could be in if the match had started at any earlier position.

Sketch (replace the outer `for` loop):

```java
// liveStates: bitset of states reachable from some start ≤ pos
BitSet live = new BitSet(nStates);
live.set(startState);
int pos = from;
while (pos <= to) {
    // any accepting state in `live` ⇒ leftmost match ends at `pos`
    int acceptState = findAccepting(live);
    if (acceptState >= 0) return extract(startSearchFor(acceptState), pos);
    if (pos == to) break;
    char c = input.charAt(pos);
    BitSet next = new BitSet(nStates);
    next.set(startState); // implicit .*? — can always restart
    for (int s = live.nextSetBit(0); s >= 0; s = live.nextSetBit(s + 1)) {
        int target = transition(s, c);
        if (target >= 0) next.set(target);
    }
    live = next;
    pos++;
}
return null;
```

This is O(n × |states|) per call instead of O(n²). For the
`small-repeated-class-bytes` repro (92 K chars, 103 states) it's ~9.5 M ops ≈
5–10 ms instead of 30+ seconds.

**Caveats to handle:**
- Register extraction: we need to keep one `regs[]` per live state to recover
  capture spans. For now, only do multi-state for `find()` (boolean); on
  accept, re-run the *anchored* extract on the discovered start position.
  That's O(states) wasted work per match but matches are rare.
- PERL `stopOnAccept` semantics: a multi-state search needs to stop on the
  *first* accept in priority order, which the bitset loses. Refine: stop the
  search as soon as any state with `stateAcceptMask != 0` enters `live`.
- Entry-mask assertions (`^`, `\b`): per-state, per-position, already computed.

### 2.2 Same for ASM `emitRunCore`

The ASM backend has the same O(n²) shape (`emitRunCore:510`). Either:
(a) emit a parallel multi-state version, or
(b) have ASM fall back to calling the VM's multi-state path when the DFA is
small enough (<256 states, say) and only emit the inline O(n²) code for big
DFAs where the per-state dispatch dominates anyway.

(b) is much less code; (a) is the principled fix.

### 2.3 Hard `RUN_TIMEOUT` raise

After 2.1/2.2 the worst case is O(n × |states|). Bump
`RUN_TIMEOUT_MS` 500 → 10 000 (matches rebar's own timeout). With the
multi-state fix, only genuinely state-exploded regexes will hit it.

**Exit criterion for Phase 2:** zero `RUN_TIMEOUT` in the suite; the existing
3 timeout cases complete in < 100 ms.

## Phase 3 — bump the size caps (≈ ½ day, but needs Phase 2)

Now that the runner is O(n), the haystack size cap is just heap.

### 3.1 Memory budget

```
MAX_HAYSTACK_BYTES    200 KB   →  16 MB   (covers 18 of 21 haystacks ≥ 100 KB)
MAX_REGEX_LEN         2 KB     →  32 KB   (covers all of wild/ except huge-character-class, which Phase 1.1 skips anyway)
```

The remaining three haystacks >16 MB (`cpython-226484e4.py` 32 MB,
`lh3lh3-reb-howto.txt` 39 MB, `leipzig-3200.txt` 16 MB) — cap them at 16 MB.
Materializing as `String` is 2× UTF-16 = 64 MB peak per test; with `-Xmx2g`
and one test at a time (JUnit 5 default), fine.

### 3.2 Watch the `repeat` spec

`haystack = { contents = "…", repeat = 1_000_000 }` would OOM. The current
`haystackByteSize` already multiplies by `repeat`, so it's covered by the cap
— *if* the resulting size doesn't overflow `long`. Add an explicit
`Math.multiplyExact` and skip with `"resolved-haystack-too-big"` on overflow.

### 3.3 JVM heap

`-Xmx2g` → `-Xmx4g` in `tests/parity/rebar/build.gradle:35`. 4 GB is the
rebar host's default for big-engine benchmarks.

**Exit criterion for Phase 3:** zero `HAYSTACK_TOO_BIG` skips for haystacks
≤ 16 MB (which is all of them except the three outliers). The three outliers
stay skipped with a clear reason.

## Phase 4 — close out the unsupported models (≈ ½ day)

### 4.1 `compile` model

Trivial: time the compilation, then run a count check for verification. We
already do both, we just don't *call* it the compile model. Add to
`runModel`:

```java
case "compile": // measure is "did it compile + verify"; no extra work
    return countMatches(r, haystack);
```

Unlocks 26 scenarios (the `aho-corasick/compile/*`, `curated/05-…/compile-*`,
etc.).

### 4.2 `grep-captures` model

Combine the existing `grepLines` line-iteration with the `countCaptures`
inner loop. ~10 lines of code. Unlocks ~6 scenarios.

### 4.3 `regex-redux`

Skip — it's a bespoke model with embedded regexes and a 9-line expected output
check. The juice isn't worth the squeeze; document as "intentionally skipped,
see `MODELS.md §regex-redux`". 1–2 scenarios.

### 4.4 Long-form Unicode property names

Add an alias table in `JdkUnicodeDataProvider.tableFor`: `Letter → L`,
`Lowercase_Letter → Ll`, `gc=Letter → L`, etc. rebar's `test/unicode/letter/*`
and `opt/fixed-length/too-small-unicode` need this. ~30 lines, straightforward
mapping table. Unlocks 5 currently-skipped scenarios.

**Exit criterion for Phase 4:** only `regex-redux` (1–2 scenarios) remains
in `UNSUPPORTED_MODEL`.

## Phase 5 — the underlying engine bugs (out of scope for "no timeouts")

These are real bugs the test will then *fail* on (not skip). Tracked in
`TODO.md` "Correctness". Listed for completeness:

- PERL disambiguation picks the wrong alternative when an earlier branch
  starts with `\b` and a later branch matches the same prefix through a
  char-class — affects 6 parol-veryl scenarios.
- Unicode simple case folding for single-char literals under `(?i)` —
  `(?i)s` doesn't match `ſ`. Affects 1 case.
- `.` on non-BMP under-counts vs re2's byte semantics — fundamental
  architecture choice, not really a bug. Either pick `java/.*` count or
  skip.
- ASM method-splitting for >200-state DFAs — would let ASM stop falling back
  to VM on the 11 currently-flagged scenarios (no test-count change, but
  faster).
- `\u{XXXX}` syntax (used by `huge-character-class.txt`) — parser doesn't
  support Unicode codepoint escape syntax.

---

## Sequencing and effort

| Phase | Effort | Unlocks (approx) | Risk |
|---|---|---|---|
| 1 — fast-fail | ½ day | 0 scenarios (just stops hangs) | low — pure test code |
| 2 — multi-state find | 1 day | 3 timeouts fixed + headroom for Phase 3 | medium — touches VM core, needs benching |
| 3 — size caps | ½ day | ~150 haystack-skips → runnable | low — bounded by Phase 2 |
| 4 — models + long-name Unicode | ½ day | ~35 model skips + 5 parse skips | low — additive |
| 5 — engine bugs | 1 week+ | turns skips into failures (not passes) | high — engine core |

After Phase 1–4: **~340 scenarios run to completion**, ~15 skipped for the
documented reasons (3 huge haystacks, regex-redux, 5–8 surfaced bugs the
test fails on instead of skipping). The remaining 8–15 failures are all real
engine bugs, not infrastructure.

After Phase 5: those failures get fixed one by one; the suite goes green.

## Order to actually do this in

1. **Phase 1** first — it's pure test code and stops the suite from being
   unusable when you bump the timeouts.
2. **Phase 3.1 / 3.3** (the easy parts: bump caps, bump Xmx) — instant,
   exposes the run-time bombs that Phase 2 has to fix.
3. **Phase 2.1** (VM multi-state find) — single biggest perf win.
4. **Phase 4** — cheap, removes a category of skips.
5. **Phase 2.2** (ASM multi-state) — only if 2.1 isn't enough on its own.
6. **Phase 5** as separate engine work.

## How to reproduce

```sh
./gradlew :tests:parity:rebar:test --rerun-tasks
```

Triage script (extract from XML):

```python
python3 -c "
import re, xml.etree.ElementTree as ET
from collections import Counter
root = ET.fromstring(open('tests/parity/rebar/build/test-results/test/TEST-io.github.jemmix.tdfa.RebarScenarioParityTest.xml').read())
fail_re = re.compile(r'expected: (\d+)L\s+but was: (\d+)L')
fc=sc=pc=0; fs=[]
bucket = Counter()
for tc in root.findall('.//testcase'):
    f = tc.find('failure'); s = tc.find('skipped')
    if f is not None:
        fc += 1
        m = fail_re.search(f.get('message',''))
        if m: fs.append((tc.get('name'), m.group(1), m.group(2)))
    elif s is not None:
        sc += 1
        msg = s.get('message','')
        for k in ['COMPILE_TIMEOUT','RUN_TIMEOUT','compile failed','unsupported model',
                  'no scalar','regex too long','haystack too big','haystack resolve']:
            if k in msg: bucket[k]+=1; break
        else: bucket['OTHER']+=1
    else: pc += 1
print(f'Pass: {pc}, Fail: {fc}, Skip: {sc}')
for n,w,g in fs: print(f'  want={w:>8}  got={g:>8}  | {n[:80]}')
print('Skips:', dict(bucket))
"
```
