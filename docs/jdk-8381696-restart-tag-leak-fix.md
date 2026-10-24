# JDK-8381696: the restart-tag leak from the JDK side

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
classified it, #85 decided it).

This doc records what we learned taking the bug apart from the JDK side —
the exact mechanism in the JDK 26 sources, a prototype fix, its measured
performance, and what lands for us the day the JDK fixes it either way.

## The mechanism (JDK 26.0.2 sources, verified by instrumented trace)

Two facts compose into the leak:

1. **`Matcher.search(int from)` resets the group array once per `find()`**
   (`for (i…) groups[i] = -1;`) and hands off to `root.match`. The scan
   loop's restart attempts — `Start`/`StartS`'s `for (; i <= guard; i++)`
   — never re-clear. A span committed during a failed start position
   survives into every later attempt of the same `find()`.

2. **Quantifier drivers commit captures outside any transaction that a
   later failure unwinds.** The decisive contrast, from a
   `--patch-module` instrumented build (`(?:(a))*$` on `"aaab"`):

   ```
   (a)*$      → GroupCurly capture=true  … FAILED → restore g1 [-1,-1]     (clean)
   (?:(a))*$  → GroupCurly capture=false … GT g1 write [0,1][1,2][2,3]
                                        … FAILED → restore g0 (locals only)  g1=[2,3] stands
   ```

   When a quantifier applies to a *bare* capture (`(a)*`), `closure()`
   extracts it into `GroupCurly(capture=true)`, which snapshots the
   group's slots on entry and restores them on its own failure — fully
   transactional (and it sets `locals[localIndex] = -1` so the inner
   `GroupTail` takes its no-op "group reference" branch). When the
   quantified atom merely *contains* a capture — wrapped in `(?:…)`, or
   with neighbors — the atom runs under `capture=false`, the inner
   `GroupTail` writes each completed iteration, and its own save/restore
   ends at the atom boundary: by the time the driver's exit continuation
   fails, the stack holds no `GroupTail` frame, so every completed
   iteration's write stands.

   This is why the leak is family-shaped, not one node's bug: the
   `GroupCurly capture=false` escape explains `(?:(a))*$`; the
   `Loop`-driven inner-atom shape `(a(b))*c` on `"aba c"` (outer group
   restored, inner `g2 1..2` leaked outside match `4..5`) escapes the
   same way through a different driver; the [#89 displaced-write
   variant](https://github.com/jemmix/tdfa-jvm/pull/89) is the same carry
   landing past the accepted match from the loop's last atomic span.
   Node-by-node fixing is whack-a-mole — the same conclusion our
   classifier's exact signature (skeleton-equal + an oracle group span
   outside its own match, displaced-write variants included per #89)
   encodes: detect the carry, not the node.

## The fix: capture reset at the restart boundary

The coherent boundary is the one `search()` already uses — reset capture
state per restart attempt. Ship shape (30 changed lines; the full
diff is [`docs/patches/jdk-8381696-restart-reset-prototype.patch`]
(docs/patches/jdk-8381696-restart-reset-prototype.patch), generated
against JDK 26.0.2 `src.zip`, OpenJDK repo layout):

- `Matcher`: a `capturesDirty` flag plus `resetCaptures()` (fill
  `groups[2..] = -1`); `search()` clears the flag with its existing reset.
- `Start`/`StartS`: after a failed attempt, `if (matcher.capturesDirty)
  matcher.resetCaptures();`
- The flag is set at exactly two choke points that cover every capture
  write outside the already-transactional `capture=true` path:
  `GroupTail`'s normal-case write, and `GroupCurly`'s entry when
  `capture` (its writes are restored on failure, but the flag is
  conservative and costs nothing).

The patch file is a pure unified diff (no preamble, `patch -p1` and
`git apply` clean): a derivative of OpenJDK `java.util.regex` (Oracle,
GPLv2+CE), kept as an upstream prototype — not vendored code, never on
any tdfa classpath.

This is *our* reading — per-restart reset — implemented in the oracle
itself: groups describe the accepted match; within-match retention the
javadoc promises (`(a(b)?)+` on `"aba"` keeps g2 `"b"`) is untouched,
because it lives inside a single attempt.

### Verification (all on JDK 26.0.2 via `--patch-module`)

- **Battery**: every published leak shape goes clean — the JBS/safere
  set (`(?:(a))*$` greedy/lazy/`{0,}`, on `"aaab"`/`"ab"`), our #85 set
  (`(?:(\A))*(\n)` canonical + lazy mirror, `(a(b))*c`,
  `(?:(\A))*(?:b)`) — and the fixed JDK's spans are **identical to the
  `tdfaSpans` pinned in `RestartTagLeakParityTest`**, case for case.
  Controls (`(x)*y`, `(\A)?x*(a)`) and the documented `(a(b)?)+`
  retention are unchanged.
- **945-case differential fuzzer** (25 base patterns × 15 inputs × 35
  core×tail mutations, backreference shapes included — `(a)(b)?\2`,
  `((a)\2?)*`, `(a)?b(\1)`): 29 cases differ between stock and fixed;
  **all 29 are exactly the leak signature** (identical match spans and
  counts; only a group span strictly outside the match → `-1,-1`).
  Zero collateral change; no case where a backreference's view of
  leaked state changes matching itself.

### Performance (best-of-6 after 4 warmups, Apple Silicon, 26.0.2)

| bench                              | stock   | naive reset          | guarded reset |
|------------------------------------|---------|----------------------|---------------|
| B1 fail-fast scan, 20 grp, 4 MB    | 0.67 ms | **14.27 ms (21×)**   | 0.67 ms       |
| B1x fail-fast scan, 100 grp, 4 MB  | 5.38 ms | **34.57 ms (6.4×)**  | 5.75 ms       |
| B2 dirty-then-fail, 20 grp, 1 MB   | 5.91 ms | 8.11 ms (+37%)       | 5.95 ms       |
| B3 find-all emails, 4 MB           | 14.99 ms| 13.99 ms             | 14.29 ms      |

The **naive** fix — unconditional `groups[2..] = -1` per failed scan
position — is unshippable: O(groups) per *scanned position* lands on the
hottest `find()` path (pure failing scans), 21× at 20 groups. The
**guarded** fix costs one boolean store per capture write and one test
per restart; the O(groups) fill runs only after attempts that actually
dirtied captures — within run-to-run noise on every bench, including the
adversarial B2 (20 captures written at every position). No allocation,
no successful-path change, `matches()`/`lookingAt()` untouched.

## Alternatives, and why the restart boundary wins

- **Fully transactional `GroupTail`** (restore on every downstream
  failure): breaks the javadoc'd within-match retention — `(a(b)?)+`
  must keep g2 `"b"` per the `Pattern` "Groups and capturing" contract.
  A spec change, not a fix.
- **Driver-level snapshot/restore of the atom's compile-time capture
  set**: fixes `GroupCurly(capture=false)` "properly" but must be
  replicated across every driver (`Curly`, `Ques`, `Branch`, `Loop`/
  `LazyLoop` bodies) — whack-a-mole, and per-attempt snapshot cost
  scales with backtracking depth rather than scan positions.
- **Generation-tagged groups** (validate slots on read): O(1) restarts
  but touches every read path plus `toMatchResult`/`usePattern`; far
  more invasive for no measurable gain over the guard.

The restart boundary is one-size: cross-attempt carry becomes impossible
by construction, for every current and future driver, while
within-attempt semantics stay byte-identical.

## What lands for us

`RestartTagLeakParityTest` was built as the tripwire: the day a JDK
fixes JDK-8381696 this way, the *"live oracle must still leak"*
assertions fail on purpose, the fixed JDK's spans already match our
pinned engine spans (verified above, case for case), and
`SKIP_RESTART_TAG_LEAK` retires like `SKIP_SCOPED_U_UNTRANSLATABLE`
(#82) and `SKIP_SURROGATE_UNIT_SEMANTICS` (#86) did — the exclusion
becomes a hard comparison. TODO tracks it. A JDK-side fix shaped
differently (e.g. normalization to RE2-style sticky captures) would
instead surface as new hard mismatches — the classifier retirement is
the reviewable moment either way.

Upstream appetite is the bottleneck, not difficulty: P4/tbd, 20+ years
old, rare impact. The analysis above (mechanism, guard, the naive-perf
trap, the backreference non-issue) is exactly the review packet the JDK
assignee needs.

## Reproducing

Everything runs against the running JDK's own `src.zip` — no OpenJDK
checkout needed:

```sh
JH=$(/usr/libexec/java_home)          # macOS; JDK 26.0.2 used here
mkdir -p jdk/src/java.base/share/classes/java/util/regex mod
unzip -o "$JH/lib/src.zip" 'java.base/java/util/regex/Pattern.java' \
      'java.base/java/util/regex/Matcher.java' -d raw
cp raw/java.base/java/util/regex/*.java jdk/src/java.base/share/classes/java/util/regex/
(cd jdk && patch -p1 < ../docs/patches/jdk-8381696-restart-reset-prototype.patch)
javac --patch-module java.base=jdk/src/java.base -d mod \
      jdk/src/java.base/share/classes/java/util/regex/*.java
java --patch-module java.base=$PWD/mod …   # fixed oracle; omit the flag
java …                                     # for stock; diff the outputs
```

The patch's diff paths use the OpenJDK repo layout
(`src/java.base/share/classes/java/util/regex/…`) so it drops into an
`openjdk/jdk` checkout with `patch -p1` unchanged; verified to apply
cleanly to 26.0.2 pristine sources and to produce the tree tested above.
