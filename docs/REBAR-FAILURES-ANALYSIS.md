# Rebar Parity Failures — Root-Cause Analysis (All Fixed)

Detailed investigation of the 4 rebar parity failures (2 bugs ×
2 backends, parameterized over `EngineFactory.ASM` and `EngineFactory.VM`)
that existed as of 2026-08. All are now fixed.

| Scenario | Model | Expected | Was | Bug | Status |
|---|---|---|---|---|---|
| `curated/05-lexer-veryl/single` | count-captures | 124800 | 123000 | §A + §B | Fixed |
| `test/unicode/case/ascii-with-unicode` | count | 1 | 0 | §C | Fixed |

**All three bugs are now resolved.** The rebar parity suite passes 100%
of in-scope scenarios (220/220 parameterized over ASM + VM).

The veryl scenario had **two stacked bugs**: a register-optimization
interference-analysis flaw (§A, **fixed** in commit `6b335e2`) that
corrupted capture-group readout, and a `\b`-in-alternation
determinization flaw (§B, open) that drops 900 matches. The regopt bug
previously masked the match-count shortfall — its phantom captures
inflated the total to 123999 (close to the expected 124800), making the
900-match gap look like a 801-count discrepancy. After the regopt fix,
the count is a clean 123000 = 61500 × 2 (exactly 2 per match: group 0 +
1 capture), making the 900-match shortfall from §B visible.

---

## §A. Register-optimization interference analysis (FIXED)

**Status:** Fixed in commit `6b335e2`.
**File:** `src/main/java/io/github/jemmix/tdfa/cfg/Optimize.java`,
`interferenceAnalysis`.
**Flag:** Default-on (`-Dtdfa.noregopt=true` to disable the entire
register-optimization pipeline; the bug was in Stage 3, which runs even
under the default config).

### Symptom

On the veryl lexer regex (88-way alternation, 88 capture groups = 176
tags, ~20 working registers), capture groups were reported with wrong
start/end values. A match on a single space reported:

```
g1=[-1,0]  g2=[-1,1]
```

instead of the correct:

```
g2=[0,1]
```

— group 1 (newlines) was partially set (`end=0` despite not
participating), and group 2 (whitespace) had its open tag at -1 instead
of 0. Across the full 150 KB haystack, 25701 of 61500 matches reported 0
participating groups, and 19400 reported >1 (impossible in a pure
alternation).

### Root cause

`Optimize.interferenceAnalysis` walked each CFG block's ops in FORWARD
order but cloned `L[b]` (the end-of-block liveness seed) for EVERY op.
That snapshot only ever contains registers live at the END of the block —
it never reflects liveness changes caused by ops WITHIN the block.

Concretely, each FINAL block in the veryl DFA looks like:

```
COPY F0 <- r0; COPY F1 <- r1; COPY F2 <- W20; POS F3; COPY F4 <- r4; ...
```

Walking forward with `Ib = L[b] = {F0..F9}` correctly marks
`I[F0][F1..F9]`, `I[F1][F0,F2..F9]`, etc. — final registers interfere
with each other. But it never marks `I[W20][F0]` or `I[W20][F1]`, even
though `W20` (the working register for g2-open in the whitespace branch)
is live alongside `F0` and `F1` within the block. `W20` becomes live
when the backward walk (which the liveness analysis IS doing correctly)
crosses the `COPY F2 <- W20`, but the forward interference walk doesn't
see this — it only looks at `L[b]`, which is end-of-block.

The allocator, seeing no interference between `W20` and `F1`,
coalesced them into the same physical slot. The whitespace transition
then wrote `pos=0` into that slot, the final block's `COPY F1 <- r1`
became a self-copy no-op (both renamed to the same slot), and `g1-close`
was read back as 0 instead of -1 (NIL).

The same aliasing meant `g2-open`'s working register shared a slot with
`g1-close`'s final register — the capture readout came back scrambled.

### Fix

Rewrote `interferenceAnalysis` to walk ops in REVERSE (as BT22 Fig. 7
specifies), maintaining a running live set seeded from `L[b]`. For each
op:

1. The dst interferes with everything currently live (except itself and
   same-value registers — the "same value → no interference" rule from
   the paper).
2. Update the live set going backward: dst dies, COPY src becomes live.

This correctly captures that `W20` is live alongside `F0`/`F1` within
the block, so `I[W20][F0]` and `I[W20][F1]` are now marked, preventing
the aliasing.

Value tracking (`V[]`) is preserved via a forward pre-pass that
snapshots `V` at each op position; the backward pass reads from the
snapshot to apply the same-value-no-interference optimization. This
keeps the coalescing of same-value COPY chains (the performance
optimization) working correctly.

### Verification

| Probe | Before fix | After fix |
|---|---|---|
| n=5 veryl subset, input `" "` | `g1=[-1,0] g2=[-1,1]` | `g2=[0,1]` |
| Full 88-branch veryl, matches with 0 participating groups | 25701 | 0 |
| Full 88-branch veryl, matches with >1 participating groups | 19400 | 0 |
| Full 88-branch veryl, per-group g1 count | 20200 (wrong) | 5800 (matches `java.util.regex`) |
| `:tests:unit:test`, `:tests:parity:re2j:test` | green | green |

### Reproduction (pre-fix)

```java
// (\\r\\n|\\r|\\n)|([\\t\\v\\f ]+)|(...)|([0-9]+...[eE]...)|([0-9]+...)
Pattern p = Pattern.compile(regex, 0, EngineFactory.VM);
Matcher m = p.matcher(" ");
m.find();
// g1=[-1,0]  g2=[-1,1]   ← bug
```

With `-Dtdfa.noregopt=true` the bug disappears (the flawed interference
pass is skipped), confirming it as a regopt-only issue.

---

## §B. `\b` in alternation produces dead-end DFA paths (FIXED)

**Status:** Fixed in commit `d133d20`. Accounts for 900 missing matches in
the veryl scenario.
**File:** `src/main/java/io/github/jemmix/tdfa/tdfa/Tdfa.java`,
determinization loop in `Compiler.compile()` (mask-group subset inclusion).

### Symptom

After the §A fix, the veryl scenario reports `expected: 124800, was:
123000`. The shortfall is exactly 900 matches (62400 expected vs 61500
actual). The 900 missing matches are all single-character identifiers
(e.g., the variable `a` in `var a : logic;`) that should match g87
(`[a-zA-Z_][0-9a-zA-Z_]*`) but are silently skipped.

Bisection across the 88 alternation branches shows the bug appears
specifically when `\bvar\b` (line 87) is added to the regex. Adding
`\bvar\b` introduces `\b`-guarded transitions for letters `v` and `a`
that dead-end when the letter doesn't start a keyword.

### Root cause

The veryl regex has the shape:

```
(?:\bvar\b)|(?:\balways_comb\b)|...|(?:\bas\b)|...|(?:[a-zA-Z_][0-9a-zA-Z_]*)
```

— keyword branches anchored with `\b`, followed by a catch-all
identifier branch with no `\b`. The DFA determinization produces two
ranges per letter that starts a keyword:

```
['a'] -> state 29  reqMask=0x4   (\b required, keyword path)
['a'] -> state 25  reqMask=0x0   (no requirement, identifier path)
```

The runner (`TdfaRunner.runStringExtract`) tries ranges in order and
COMMITS to the first one whose `reqMask` passes. When `\b` IS satisfied
(at a word/non-word boundary), it always takes the keyword path — even
when the char doesn't start any keyword.

State 29 (keyword path, after `a` at `\b`) only has transitions for
keyword continuations:

```
State 29:
  ['l'] -> state 208    (always_comb, always_ff, ...)
  ['s'] -> state 209    (as)
  [everything else] -> state -1   (dead end)
```

It's missing identifier-continuation transitions (`[0-9A-Z_a-z]` for the
`[a-zA-Z_][0-9a-zA-Z_]*` branch). So after seeing `a` at a `\b`, if the
next char isn't a keyword letter, the DFA dead-ends and reports no match.

At position 26 in the veryl haystack (`var a : logic;`), the char is
`a`, preceded by a space. `\b` IS satisfied (space is non-word, `a` is
word). The runner takes the `\b`-guarded range to state 29. State 29
has no transition for space (the next char at position 27), so the DFA
dies. `haveAccept` is false, and the engine bumps `startSearch` to 27 —
skipping the match on `a` entirely.

### Why this is a determinization bug

In the NFA, after consuming `a`, both the keyword path AND the
identifier path are live. The ε-closure of the post-`a` NFA states
should include:

- Keyword NFA states (waiting for the next keyword letter)
- Identifier NFA states (the `[a-zA-Z_]` has matched, now in the
  `[0-9a-zA-Z_]*` loop)

The DFA state for "after `a` at `\b`" should represent BOTH paths. Its
transition table should include keyword continuations AND identifier
continuations. Instead, state 29 only has keyword continuations — the
identifier NFA states were dropped from the closure.

The root issue is how `\b` (a zero-width assertion modeled as an
ε-transition with an assertion mask) interacts with alternation during
determinization. The `\b`-guarded ε-edge into the keyword branch creates
a DFA state keyed on `(NFA states, \b satisfied)`. The non-`\b`
identifier branch creates a separate DFA state keyed on `(NFA states, no
\b requirement)`. These two states never merge because their assertion
masks differ, even though they share the same input position and the
identifier path is viable in both.

### Fix

Modified the main determinization loop in `Tdfa.Compiler.compile()`:
when processing mask group M, also step configs from subset-mask groups
(mask ⊆ M). The group's own configs are added to the step input FIRST
(higher priority), so they survive the ε-closure `(state, mask)` dedup
with their tags and registers intact. The `requiredMask` is set to
`groupMask` (not the intersection, which gets diluted by subset configs).

This ensures the `\b`-guarded transition's target DFA state includes
continuations from BOTH the keyword path AND the identifier path. The
non-`\b` transition (mask=0) still leads to an identifier-only state,
correctly handling positions where `\b` doesn't hold.

The fix is minimal — it only changes the composition of the step input
list and the requiredMask computation. The closure, register allocation,
and state dedup machinery are unchanged. For groups with no subsets
(mask=0, or the only group), behavior is identical to before.

### Verification

| Probe | Before fix | After fix |
|---|---|---|
| `(\bas\b)|([a-zA-Z_]...)` on `"a b"` | 1 match (`b` only) | 2 matches (`a`, `b`) |
| Full veryl scenario | 123000 (900 short) | 124800 (exact) |
| `:tests:unit:test`, `:tests:parity:re2j:test` | green | green |
| `:tests:parity:rebar:test` | 2 failures (veryl × 2 backends) | 0 failures |

### Reproduction

```java
// From build/vendor/rebar/pristine/rebar/benchmarks/regexes/wild/parol-veryl.txt
// Join 88 lines as (?:line1)|(?:line2)|...|(?:line88)
Pattern p = Pattern.compile(regex, 0, EngineFactory.VM);
Matcher m = p.matcher("var a : logic;");
while (m.find()) {
    System.out.printf("[%d,%d]=\"%s\"%n", m.start(), m.end(),
                      input.substring(m.start(), m.end()));
}
// Expected: [0,3]="var", [4,5]=" ", [5,6]="a", [6,7]=" ", ...
// Actual:   [0,3]="var", [4,5]=" ",          [6,7]=" ", ...  ("a" missing)
```

Position-level probe: `find()` starting at position 26 (the `a` in
`var a`) returns `[27,36]` (the following whitespace) instead of
`[26,27]` (the `a`).

---

## §C. Unicode case-fold `s ↔ ſ` for literal chars (FIXED)

**Status:** Fixed in commit `74ab652`. 1 scenario
(`test/unicode/case/ascii-with-unicode`).
**File:** `src/main/java/io/github/jemmix/tdfa/unicode/CaseFoldTable.java`,
`src/main/java/io/github/jemmix/tdfa/parser/Parser.java`
(single-char literal fold, 3 locations).

### Symptom

```
regex = 's', unicode = true, case-insensitive = true
haystack = 'ſ'   (U+017F, LATIN SMALL LETTER LONG S)
expected count = 1   (s matches ſ under Unicode case folding)
got = 0
```

### Root cause

`Parser.parseQuoted` and the single-char literal path under
case-insensitive mode use `Character.toLowerCase(ch)` and
`Character.toUpperCase(ch)`:

```java
// Parser.java:612-616
if (caseInsensitive) {
    char lo = Character.toLowerCase(ch);
    char hi = Character.toUpperCase(ch);
    if (lo != hi) return new CharClass(new int[]{lo, lo, hi, hi}, false);
}
```

For ASCII `s`, `Character.toLowerCase('s')` = `'s'` and
`Character.toUpperCase('s')` = `'S'`. The regex becomes `[sS]`. This is
correct for ASCII-only folding but misses the Unicode simple case fold
`s ↔ ſ` (U+017F LATIN SMALL LETTER LONG S), which case-folds to `s`
under Unicode `CaseFolding.txt` (status `S` — simple, common).

The same issue affects char-class ranges under `(?i)` (line 306-328),
which is explicitly flagged in a comment: "Pending full Unicode case
folding for arbitrary class ranges; currently ASCII-only."

### Fix path

Implement Unicode CaseFolding.txt-based folding for literal chars and
class ranges. The existing `UnicodeDataProvider.foldTableFor(name)`
infrastructure (used for `\p{Greek}` under `(?i)`) could be extended
with a `caseFolds(int codepoint) : int[]` API returning the set of
codepoints that fold to the same canonical form.

For the literal case, `Parser` would expand each char `c` under `(?i)`
to a class `[c, toUpperCase(c), toLowerCase(c), ...caseFolds(c)]`. For
the class-range case, each user range `[lo,hi]` would be augmented with
the case-fold counterparts of any sub-range containing foldable
codepoints.

Data source: the JDK's bundled Unicode database
(`java.lang.Character` via `JdkUnicodeDataProvider`) has the case
mapping for individual codepoints but not the reverse mapping (from
canonical form back to all codepoints that fold to it). A small static
table derived from Unicode `CaseFolding.txt` (≈70 KB, ~1500 entries for
simple case folds) would cover this. The table can be generated at
build time or vendored as a resource.

### Reproduction

```java
Pattern p = Pattern.compile("s", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);
Matcher m = p.matcher("ſ");
m.find();  // Expected: match at [0,1]. Actual: no match.
```

Note: the `test/unicode/case/ascii-only` scenario (same regex, no
`unicode = true` flag) expects count = 0 for the same haystack —
correctly, because ASCII-only case-insensitive does NOT fold `s ↔ ſ`.
Our engine passes that scenario. Only the `ascii-with-unicode` variant
(with `unicode = true`) fails.

---

## Investigation methodology

The path from "4 failures" to "3 root causes" used these techniques:

1. **Parameterized cross-product (commit `358a61e`)** — confirmed both
   bugs reproduce identically on ASM and VM, ruling out backend-specific
   causes and pointing at shared TDFA-construction code.

2. **Bisection over alternation branches** — for the veryl regex,
   incrementally added branches 1..88 and checked when capture-group
   anomalies appeared. The n=5 subset (`(\r\n|\r|\n)|([\t\v\f ]+)|...|
   ([0-9]+...[eE]...)|([0-9]+...)`) was the minimal repro for the regopt
   bug.

3. **`-Dtdfa.noregopt=true` A/B testing** — the regopt bug vanished
   with the flag set, isolating it to the register-optimization
   pipeline. The `\b` dead-end bug persisted regardless of the flag,
   confirming it as a core TDFA bug.

4. **DFA-structure dumps** — reflection-based probes reading
   `Tdfa.ranges`, `Tdfa.ops`, `Tdfa.stateFinalOpsOff` directly, to see
   the exact transition and final-op triples the runner executes.
   Revealed the `POS 1<-0` transition op writing into a final-register
   slot (regopt bug) and the two-range split with `reqMask=0x4`
   (`\b` dead-end bug).

5. **`java.util.regex` as oracle** — for the veryl regex, JUR reports
   62400 matches, each with exactly 1 participating capture group, and
   agrees with our engine on every per-group count after the regopt
   fix. The remaining gap is purely in match COUNT (positions where our
   engine finds no match), confirming the `\b` dead-end as a
   match-finding bug, not a capture-reporting bug.
