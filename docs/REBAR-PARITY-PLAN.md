# Rebar Scenario Parity — Triage & Plan

State as of commit `08a83ba`. Suite runs in **6 seconds** (was 12+ minutes)
thanks to per-case virtual-thread timeouts (300 ms compile / 500 ms run).

## Current breakdown: 359 cases

| Bucket      | Count | Meaning                                              |
|-------------|-------|------------------------------------------------------|
| PASS        | 88    | Engine produces correct count                        |
| FAIL        | 27    | Real semantic divergence from rebar's expected count |
| COMPILE_T/O | 1     | `\p{L}{256}` — DFA state explosion                   |
| RUN_T/O     | 2     | Complex char class with `{100,...}` quantifier       |
| SKIP        | 241   | Unsupported syntax, oversized haystack/regex, etc.   |

## The 27 failures, grouped by root cause

### A. Unicode class expansion (15 tests) — biggest bucket

```
test/unicode/word/*           (5)   \w matches nothing in Unicode mode
test/unicode/word-boundary/*  (5)   \b matches nothing in Unicode mode
test/unicode/decimal/*        (1)   \d
test/unicode/whitespace/*     (1)   \s
test/unicode/case/*           (2)   /s/ and /Δ/ with Unicode flag
test/unicode/utf8/*           (1)   dot-matches-byte (UTF-8 vs UTF-16)
```

**Root cause**: `\w`, `\d`, `\s`, `\b`, `\p{L}` expand to ASCII-only ranges.
rebar's Unicode-mode scenarios expect full Unicode coverage.

**Fix**: wire `UnicodeDataProvider` into the TDFA compiler's character-class
expansion (overlaps with PARITY-PLAN.md Commit 10). One fix clears all 15.

### B. Veryl/Parol lexer regexes (6 tests)

```
curated/05-lexer-veryl/single     want=124800  got=0
curated/05-lexer-veryl/multi      want=150600  got=0
wild/parol-veryl/ascii            want=124800  got=0
wild/parol-veryl/unicode          want=124800  got=0
wild/parol-veryl/multi-patternid  want=150600  got=0
wild/parol-veryl/multi-captures   want=124800  got=0
```

**Root cause**: all share the same regex shape
`(\r\n|\r|\n)([\t\v\f ]+)((?://.*(?:\r\n|\r|\n))|...)`. Compiles (155 ms)
but returns zero matches on 120-150 KB haystacks. Likely a nested-group or
alternation execution bug in the VM backend.

**Fix**: isolate the minimal failing sub-pattern, add a unit test, debug.

### C. Keyword alternations (4 tests)

```
reported/i787-keywords/ascii        want=5674
reported/i787-keywords/unicode      want=5674
aho-corasick/teddy/reported-i787    want=4896
aho-corasick/dictionary/i787-noword want=4861
```

**Root cause**: large `as|break|const|...` alternation lists. Could be
leftmost-first semantics (see D) or alternation ordering in the NFA.

### D. Core semantics (2 tests)

```
test/func/leftmost-first   want=3  /sam|samwise/    — matches sam, skips samwise
test/func/non-greedy       want=3  /[a-z]+?/        — non-greedy returns wrong count
```

**Root cause (leftmost-first)**: TDFA uses POSIX leftmost-longest; re2j/rebar
expect leftmost-first (PCRE semantics). This is an engine-wide disambiguation
policy issue, not a per-regex bug.

**Root cause (non-greedy)**: lazy quantifier semantics not honored — the DFA
may be collapsing `{n,}?` to `{n,}?` → greedy.

## Timeouts (3 tests)

```
reported/i1095-word-repetition/unicode-search   COMPILE_T/O  \p{L}{256}
opt/nfa-sparse/small-repeated-class-bytes       RUN_T/O      [...]{100,...}
opt/nfa-sparse/small-repeated-class-unicode     RUN_T/O      [...]{100,...}
```

**Root cause**: DFA state explosion from high-repetition quantifiers on
wide character classes. The TDFA determinization produces an exponential
number of states.

**Fix**: state-budget cap in determinization (fall back to NFA simulation
above a threshold), or lazy DFA construction (build states on-demand during
matching rather than upfront).

## Skips (241 tests)

Breakdown not yet computed. Expected categories:
- Unsupported regex syntax (backreferences, lookaround) — out of scope
- Haystack > 200 KB — cap raise needed
- Regex > 2000 chars — dictionary mega-alternations
- Model not supported (some `count-spans`/`grep` variants)
- Compile errors (rejected patterns)

## Recommended fix order

1. **Unicode classes (A)** — highest count (15), single fix point, unblocks
   PARITY-PLAN.md Commit 10.
2. **Leftmost-first (D)** — engine-wide policy change, also clears the
   keyword alternation failures (C) if they're caused by the same issue.
3. **Veryl/Parol (B)** — isolate and debug; likely a single bug clearing 6.
4. **Non-greedy (D)** — small, isolated.
5. **Timeouts** — state-budget cap in determinization.
6. **Re-baseline skip count** — raise caps, re-run, triage next layer.

## How to reproduce

```sh
./gradlew :tests:parity:rebar:test --rerun-tasks
```

Triage script (extract from XML):

```python
python3 -c "
import xml.etree.ElementTree as ET
tree = ET.parse('tests/parity/rebar/build/test-results/test/TEST-io.github.jemmix.tdfa.RebarScenarioParityTest.xml')
root = tree.getroot()
for tc in root.findall('.//testcase'):
    name = tc.get('name')
    sk = tc.find('.//skipped'); fl = tc.find('.//failure')
    if fl is not None:   print(f'FAIL  {name}')
    elif sk is not None: print(f'SKIP  {name}  ({sk.get(\"message\",\"\")[:40]})')
" | sort
```
