# JDK-8381696: the capture-group leaks in java.util.regex, and the fix shape we expect upstream

The exclusion PR #85 decided (`SKIP_RESTART_TAG_LEAK`, pinned both ways in
`RestartTagLeakParityTest`) is a *java.util.regex* bug with a public record:
[JDK-8381696](https://bugs.openjdk.org/browse/JDK-8381696) — *"Matcher leaks
capture groups from failed starting positions when capture is inside
quantified non-capturing group"* — filed 2026-03-31 from the SafeRE
cross-engine effort ([eaftan/safere#52](https://github.com/eaftan/safere/issues/52),
with the deepest public analysis), verified by Oracle on JDK 8u481 and
27-ea, triaged **P4 / fixVersion tbd** — open, unfixed, as of 2026-10-24.
Our campaign found it independently six months later (seed 2819403508500839673,
`(?:(\A))*(\n)` on `"a\n"`; [#84](https://github.com/jemmix/tdfa-jvm/pull/84)
classified it, #85 decided it), and the later campaigns added two adjacent
families now pinned in `GroupCurlyStaleWriteParityTest`: the stale nested
write after a greedy back-off, and its displaced twin.

This doc records the JDK-side analysis: the mechanism, the fix direction a
prototype implements, the decisions and tradeoffs behind that direction, the
alternatives rejected and why, the verification, and what lands for us the
day the JDK fixes it. An earlier revision of this document proposed a
different fix — clearing the group array at every scan-loop restart — and
that proposal is kept below under "Alternatives", with the reasons it was
dropped.

## The mechanism (verified by instrumented runs against JDK sources)

Three facts compose into the leaks.

1. **`Matcher.search(int from)` resets the group array once per `find()`**
   and hands off to `root.match`. The scan loops (`Start`, `StartS`, `First`)
   retry at successive positions without re-clearing. A capture written
   during a failed position survives into every later attempt of the same
   `find()`.

2. **A quantified group compiles to one of two drivers.** If the body
   `study()`s as deterministic, `group0()` builds `GroupCurly`, which runs
   repetitions in a loop and backs off by decrementing counters. Otherwise
   it builds the recursive `Prolog`/`Loop` pair. The recursive driver is
   transactional for free: each `GroupTail` in the body saves its group's
   positions on entry and restores them on failure, through the Java call
   stack. `GroupCurly` is not: it restores its *own* group's two slots when
   `capture` is true, and never touches the slots of groups *nested in its
   body*. Those writes are made by the inner `GroupTail` as each repetition
   completes; nothing undoes them when the repetition is undone.

3. **Two adjacent defects widen the same hole.** Possessive quantifiers on
   groups (`(a)*+`) compile to a plain `Curly`/`Ques` wrapping the group
   nodes, which restores nothing on failure. And `GroupCurly.match0`'s
   back-off success path rewrote its own group's slots from stale offsets
   (`i-k`), clobbering values written when the continuation re-ran the same
   group through an enclosing quantifier — a wrong *value*, not just a stale
   one (`(?:(a){0,2})*` on `"aaa"` reported `(1,2)` where the participating
   iteration ended at `(2,3)`).

The zero-width assertions and atomic groups leak through the same opening
by a different route: `(?=…)`, `(?!…)`, `(?<=…)`, `(?<!…)` and `(?>…)`
never unwind the captures their condition or body wrote when the construct
fails (`(?!(a))b` on `"ab"` leaves g1 set; `(?>(a))x|(.)` on `"ab"` leaks
g1 into the `(.)` match). When such a construct *succeeds*, its captures
persist by documented behavior and must be kept.

Our engine pins the correct reading in three tiers: `RestartTagLeakParityTest`
(cross-restart carry), `GroupCurlyStaleWriteParityTest.staleWrites` (the
discarded iteration's span reported as set), and its `displacedWrites` (the
discarded iteration reported where the participating one belongs). All
three are one bug class: a capture from an abandoned attempt or iteration
outliving it.

## The fix: restore the exact saved positions when an attempt is undone

The prototype patch
([`docs/patches/jdk-8381696-exact-restore-prototype.patch`](docs/patches/jdk-8381696-exact-restore-prototype.patch),
generated from an `openjdk/jdk` tree, base `d39a62afc66`, JDK 28 dev)
implements the transaction at the level that owns the repetition — the
quantifier driver. The principle:

> Before an attempt that can be undone, save the group positions its body
> can write. When the attempt is undone, restore the saved values. When the
> construct fails as a whole, restore the values from before its first
> attempt. When it succeeds, keep the current values.

This mirrors what the recursive path obtains from `GroupTail` unwinding,
which is also the arbiter for every semantics question below.

### Decision 1: transaction at the driver, exact values

The transaction boundary is the repetition, not the scan loop and not
`GroupTail`. Restoring *values* (not markers) matters because the state
being restored is the positions of earlier, still-standing iterations, not
a blank slate. It also preserves the documented within-match retention —
`(a(b)?)+` on `"aba"` keeps g2 `"b"` — because only *undone* repetitions
restore.

### Decision 2: compile-time range, runtime stack

`group0()` records the capture-group count before parsing a quantified
group's body. Group numbering in `java.util.regex` is lexical and has no
branch resets, so `[count-before, count-after)` is exactly the contiguous
range of groups nested in the body, in `groups[]` slot terms. `GroupCurly`
carries that range. At match time a growable `int[]` stack on the Matcher
(`groupPosStack`, top reset by `search()`/`match()`) holds the saved
frames. Nodes are shared across matchers, so all mutable state lives on
the Matcher.

Frame discipline per driver:

| driver | frames |
|---|---|
| `match` (cmin loop) | one frame before the first repetition |
| `match0` (greedy) | one frame per repetition attempt; one pop-restore per repetition undone on back-off; entry frame on total failure |
| `match1` (lazy) | one frame, deferred until a repetition is attempted — a zero-repetition match, the common lazy case, saves nothing |
| `match2` (possessive) | one frame before the first repetition |
| assertions, atomic group | one frame; restored when the construct fails, kept when it succeeds |

For a single nested group — the common case — the single-frame drivers keep
the two values in Java locals of the invocation; only the greedy driver
needs the heap stack, because its frames must outlive the Java stack.

### Decision 3: bookkeeping gated on nested captures

`nestedCount > 0` guards every save and restore. A pattern without a
capture nested in a quantified group, like `(a)*`, pays nothing: no
allocation, no stack traffic, no measurable change.

### Decision 4: possessive quantifiers route to `GroupCurly`

`group0()` builds `GroupCurly(POSSESSIVE)` instead of the raw `Curly`/
`Ques` whenever the group can write group slots. The iterative possessive
driver restores on failure; the recursive one never did.

### Decision 5: the greedy back-off rewrite is deleted

The rewrite of the group's own slots in `match0`'s back-off success path
was redundant — every other writer already maintains exact per-iteration
values — and was the source of the stale-offset wrong values. Removing it
fixes the displaced family.

### Decision 6: assertions and atomic groups restore on failure

Each such node carries the range of groups inside it and wraps its result:
failure restores, success keeps. One addition in `GroupCurly.match0` covers
the lookahead that matched inside a later-failing repetition: a failed
repetition attempt restores its saved frame rather than assuming the body
unwound itself.

### Invariant worth writing down

`GroupCurly.match0` restarts its inner loop with a fresh constant length
when a repetition's width changes: the attempt that revealed the change is
discarded and retried recursively. A discarded attempt must be undone
*before* the recursion captures its entry state, or the recursion's failure
restore writes the discarded attempt's values back. The prototype had this
bug; only inputs mixing one-unit and two-unit characters (surrogate pairs
with lone surrogates) can trigger the width change, which is why ASCII-only
differential corpora missed it. It is fixed by restoring the frame before
the recursive call, and it is the reason the verification below includes a
surrogate-heavy corpus.

## Semantics after the fix

A group reports the positions of its last repetition that participated in
the accepted match, or null if none did. Successful lookahead and atomic
captures persist; failed ones do not. Backreferences make the cleanup
visible in match results: a backreference to a group that did not
participate fails, so patterns that matched *because of* a leaked span stop
matching (`(?<mn>a(?!(b))){0,3}+\2` on `"ab"`). Python `re` agrees on
every such case examined. The fixed JDK's spans equal our pinned engine
spans on all fourteen cases of `RestartTagLeakParityTest` and
`GroupCurlyStaleWriteParityTest` — all three tiers.

## Verification

- **Differential fuzzing against a reference build** that forces every
  slot-writing quantified group onto the recursive `Prolog`/`Loop` path
  (correct by construction): 588k generated cases over greedy, lazy,
  possessive, alternation, backreference, named-group, lookaround,
  supplementary and surrogate-mixing alphabets — zero divergences; stock
  diverges on ~4,500.
- **Python `re` as an independent oracle** on 104k cases (possessive
  included): the only divergences are the documented engine differences
  (empty-match advance policy; Python's per-code-point string model makes
  it unusable as an oracle on surrogate-mixing inputs).
- **The JDK's own suite**: `test/jdk/java/util/regex` in full (1,002
  framework tests) passes unchanged — no existing test relies on the
  leaking behavior.
- **Targeted batteries**: the published reproducer set, zero-length
  repetition participation (`(?:(a?))*` keeps `(1,1)`), within-match
  retention, region/flags/`CANON_EQ` matrices, `hitEnd`/`requireEnd`,
  matcher reuse, adversarial memory shapes.
- **Adversarial review** of API contracts, compilation-graph invariants
  (the nested-range computation audited against an independent lexical
  scanner over 24k generated patterns), and performance/memory.

## Performance and memory

Isolated JMH measurements (one fork per benchmark; shared forks give
polluted JIT profiles and wildly wrong numbers):

- Patterns without nested captures: unchanged.
- Zero-repetition lazy, possessive optional: within noise of stock.
- Realistic deterministic bodies with one nested group: −7% to +33%
  (about 3 ns per repetition — two loads, two stores, a counter).
- The recursive alternative (decision 2's reference build) is 1.2–2.1×
  slower than the fixed driver on the same shapes.

Memory: the greedy driver's stack is O(repetitions × nested groups) while
backtracking through them, comparable in kind to Python's capture stack
(4.4× our footprint on the same shape) and friendlier than the recursive
path, which overflows the Java stack far earlier. The stack is retained at
its high-water mark for the Matcher's life, like `localsPos`. Early
versions pushed one frame per repetition in *all* drivers; an adversarial
reviewer demonstrated an `OutOfMemoryError` reachable with a tiny input and
a large `{n}` bound, which is why the single-frame drivers save once (and
the lazy one defers even that).

## Alternatives, and why they were rejected

- **Reset the group array at every scan-loop restart** (this document's
  earlier proposal). It clears cross-restart carry but nothing else: the
  stale nested write after a greedy back-off (`(?:(b))*b`), the displaced
  write, the possessive leaks, the assertion leaks, and the stale-offset
  wrong values all survive, because they live *within* one scan position.
  It also costs a flag write on every `GroupTail` commit — on every
  pattern, including those that cannot leak — and an O(groups) fill per
  dirty restart. Fuzzing its 945-case battery found no backreference
  interactions; the exact-restore battery did (`{0,3}+\2` above), which
  is a sampling statement, not a semantic one.
- **Force the recursive `Loop` path** for every slot-writing quantified
  group. Correct by construction, and the reference implementation used to
  verify the prototype. Rejected as the fix: 1.2–2.1× slower on the
  affected patterns, and it changes the compiled shape of common patterns.
- **Transactional `GroupTail`** (restore on any downstream failure).
  Breaks the documented within-match retention. The tail is also the wrong
  owner: the repetition, not the group, is the transaction.
- **Generation-tagged groups** (validate slots on read). O(1) restarts,
  but it touches every read path plus `toMatchResult`/`usePattern`, for no
  measured gain over exact restore.

## What lands for us

The parity pins were built as the retirement signal, and this fix shape
retires all of them: the fixed JDK's spans equal the pinned engine spans
on every case of `RestartTagLeakParityTest` and both tiers of
`GroupCurlyStaleWriteParityTest` — verified case for case against the
prototype. The same GroupCurly rewrite carries the nested-loop
inner-capture quirk (`((a)*b)*` first-writing spans): #179 retired the
engine-side sticky emulation, so the engine already holds the
exact-restore discipline there and `SKIP_NESTED_LOOP_CAPTURE` rides the
same `JUR_LEAKS` probe (pinned both ways in
`NestedQuantifierParityTest`, whose oracle-side pins also fail on
purpose the day a stock JDK carries the fix). The day a JDK carries such
a fix, the *"live oracle must still leak"* assertions fail on purpose
and `SKIP_RESTART_TAG_LEAK` and `SKIP_GROUPCURLY_STALE_WRITE` retire the
way earlier exclusions did. A JDK fix shaped differently (say,
normalization to RE2-style sticky captures) would instead surface as new
hard mismatches; the classifier retirement is the reviewable moment
either way. TODO tracks it.

## Reproducing

The prototype patch applies to an `openjdk/jdk` checkout (base `d39a62afc66`)
with `patch -p1` / `git apply`. It is a derivative of OpenJDK
`java.util.regex` (Oracle, GPLv2+CE), kept as an upstream prototype. The same
change rebased onto vendored vanilla sources (openjdk/jdk26u tag
`jdk-26.0.2-ga`) lives in `vendor/jdk-regex-jemmix/` — same layout as the
patched re2j oracle: sha256-pinned pristine archive + per-fix patches +
`build-patched.sh`, built on demand, never on any tdfa classpath. The module
dir is consumed per fork via `--patch-module`, and the fuzzers use it as the
DEFAULT jur-lane oracle — only the fuzz JVM runs the patched base; the Gradle
daemon, workers and the parity tests (which keep asserting the stock leak
until a real JDK carries a fix) stay on the unpatched JDK:

```sh
./gradlew :tests:parity:re2j:fuzz                       # jur lane = patched oracle
./gradlew :tests:parity:re2j:fuzz -Pfuzz.pristineJur=true   # vs stock (leaking) jur
./gradlew :tests:parity:re2j:camp  -Pcamp.mode=jur
```

The fuzzer PROBES whether the live oracle leaks
(`DifferentialFuzzer.JUR_LEAKS`, pinned in `JurOracleLeakProbeTest`) and
gates its leak-family soft classes on it: under the patched (or any fixed)
oracle `SKIP_RESTART_TAG_LEAK` / `SKIP_GROUPCURLY_STALE_WRITE` cannot fire
and every jur-lane divergence is a hard finding. The same probe is the
retirement signal — when a stock JDK ships a fix of this shape, the pin
tests and the probe test fail on purpose and the exclusions retire (see
"What lands for us").

The differential harnesses used for the numbers above are not part of this
repository; the battery that matters here is the two parity tests — point
them at a patched JDK and the pins above become the acceptance criteria.
