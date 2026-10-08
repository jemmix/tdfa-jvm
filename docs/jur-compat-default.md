# JUR-compat default — flip the divergence families, invert the flags

Status: **in progress** — the flag-surface boilerplate is landed (bits,
`RE2_COMPAT` preset, `Semantics` carrier Tnfa→Tdfa→runner; pre-flip
no-ops), the match-time pivots are parameterized (both sides selectable,
default unchanged), and the fold + `(?U)` axes joined them through the
same `CompileOptions.semantics` route. Landing the rest is only cheap
before the first Maven publish / 1.0 API lock (both still open in
[TODO](../TODO.md)) — after that the same flip is a major version. This
page is the full accounting: what flips, what the API looks like, what it
costs, and how it's phased; the build order lives in the
[work breakdown](jur-compat-wbs.md).

## Scope and terms

"JUR" in this tree means `java.util.regex` (the fuzz-campaign shorthand —
see [fuzz-campaigns-2026-10-03.md](fuzz-campaigns-2026-10-03.md)). "JUR compat" means:

- **No new supported regexes.** The accepted syntax is exactly today's;
  compile parity already holds — the jur campaign's 542,679-case pass found
  **0 unclassified** compile or result divergences outside the six known
  families.
- **Results align with JUR** on everything both engines accept: same
  spans, same group protocols, same accepts/rejects.

The design decision: JUR parity becomes the **default** and the flags are
inverted — each divergence family becomes an opt-out bit that restores
today's RE2-lineage behavior. No `enum EngineSemantics { RE2J, JUR }`
selector; a flag per semantic axis, plus one preset OR-composed from them.
Presets are plain `int` constants, so `& ~BIT` subtraction and OR-composition
with existing flags both work.

Why inverted rather than opt-in (the alternative shape was
`JUR_COMPAT = UTF16_UNITS | …`): inversion snaps two dials onto canonical
JDK flag names with JDK-exact semantics (`UNIX_LINES`, `UNICODE_CASE`), so
the default surface is literally "what a `java.util.regex` user expects,
including the flags" — and the opt-outs are exactly the dials that user
would need to describe RE2.

## The six families, and where they live

All six are already classified with replayable seeds and counts
(`docs/fuzz-campaigns-2026-10-03.md`, finding 2; representative records at
the seeds listed there). The engine pivots are centralized — the ASM tier
calls back into the shared helpers rather than reimplementing them:

| family | pivot |
|---|---|
| surrogate-code-unit + unit-boundary-positions | `Alphabet.pairInterior` gating (~10 sites in `TdfaRunner`, `RunnerTables.needleEndOverlapsPair`; ASM emits `INVOKESTATIC` to the same helper — `TdfaAsmBackend.java:611`), `isWordBefore`/`isWordAt` |
| line-terminator-set | `positionFlags`/`positionFlagsCS` (`TdfaRunner.java:2739`, `:2770`), parser `DOT` set (`Parser.java:104`) |
| caret-after-final-newline / dollar-before-final-newline | same `positionFlags*` |
| group-participation-lineage | determinization φ-finals (`applyFinalOps` and the final-tag protocol) |

JUR directions for the anchor rules were re-verified empirically against a
live JDK before this design: `^`(?m) never matches the empty position after
a trailing terminator (only `0..0` on `"a\n"`, nothing at 2); `$` without
(?m) matches before the final terminator (`0..1` on `"a\n"`); `.` skips
`\r` and U+0085; `(\z)*` on `""` reports g1 non-participating.

## The flags

Set = today's behavior (the opt-out). Unset = the new JUR-parity default.

| flag | off (default) | on |
|---|---|---|
| `UNIX_LINES` | `.`/`^`/`$` recognize `\n`, `\r`, `\r\n`, U+0085, U+2028, U+2029 | `\n` only — JDK-exact name and semantics |
| `UNICODE_CASE` | `CASE_INSENSITIVE` folds ASCII only (JUR bare CI) | full Unicode fold — JDK-exact name and semantics |
| `CODEPOINT_BOUNDARIES` | UTF-16 unit semantics: matches start/end at any unit, lone surrogates match as single units, `\b`/`\B` evaluate at every unit position | codepoint discipline: scans skip pair interiors, boundaries at codepoint edges |
| `EMPTY_LAST_LINE` | `^`(?m) never matches after a trailing terminator | may match the empty last line |
| `END_OF_TEXT_ONLY` | `$` may match before the final terminator (no (?m)) | `$` ≡ `\z` |
| `EMPTY_ITERATION_SPANS` | last-completed-iteration protocol: a final zero-width iteration reports its span (`(a*)*` on `"aa"` → g1 `2..2`) EXCEPT all-zero-width min-0 bodies stay non-participating (`(\z)*` on `""` → g1 `−1` — java's `GroupCurly` rollback; see the WBS item 4 research notes) | maximal-iteration protocol: `(a*)*` → g1 `0..2`; a sole zero-width iteration still reports (`(\z)*` → `0..0`) |
| `UNGREEDY_U` | `(?U:`/`(?U)` = scoped `UNICODE_CASE` | ungreedy (PCRE/RE2 meaning) |

`EMPTY_LAST_LINE` + `END_OF_TEXT_ONLY` are the old bundled
`FINAL_TERMINATOR_ANCHORS` pair, split: inverted flags should name their own
positive effect, and "empty last line" and "$ before final newline" are
different sentences. **Decision (the pivot item): keep the split.** The
flag surface already shipped the two bits, the effects are independently
selectable in the pivots (a full-terminator-set + empty-last-line lane is a
coherent reading no single bundle could express), and `RE2_COMPAT`
re-composes them; collapsing to one bit now would change the surface for
no semantic gain. A single `RE2_LINE_ANCHORS` bundle remains off the table.
```java
/** Preserves pre-flip (re2j-pinned) behavior. Migration is one OR. */
public static final int RE2_COMPAT =
    UNIX_LINES | UNICODE_CASE | CODEPOINT_BOUNDARIES
  | EMPTY_LAST_LINE | END_OF_TEXT_ONLY
  | EMPTY_ITERATION_SPANS | UNGREEDY_U;
```

Composition rules:

- `MULTILINE`, `DOTALL`, `UNICODE_CHARACTER_CLASS`, `LONGEST_MATCH`,
  `FIND_ONLY`, `MULTI_VALUED_TAGS` stay independently OR-able. JUR has no
  longest mode but the semantics don't conflict — legal, documented.
- No new flag is needed for the Unicode class sets: `UNICODE_CHARACTER_CLASS`
  (`(?u)`) already matches JUR's UCC word set exactly (`621fb5f`).
- Degenerate combos are documented no-ops, not errors: `UNIX_LINES` with no
  `.`/anchors, `UNICODE_CASE` without `CASE_INSENSITIVE`, `UNGREEDY_U` with
  no `(?U` in the pattern.
- Bit values do not mirror the JDK's (today's `MULTILINE=4` already differs
  from the JDK's 8); names and semantics mirror, numerics don't.
- v1 limit, stated loudly: `(?U:` scoped fold upgrades need per-scope fold
  universes in the parser; v1 accepts `UNGREEDY_U`/inline `(?U)` at top
  level only and rejects group-scoped use with a `PatternSyntaxException`.

## Consequences of flipping the default

- **The default contract changes.** This is the point, but it means the
  re2j-pinned evidence (5.7 M RE2 exhaustive cases, ~300 M fuzz) moves to
  the `RE2_COMPAT` lane unchanged (same bits, same code paths — re-gated,
  re-run) while the new default lane's oracle becomes `java.util.regex`.
  The jur campaign harness is that spec: its six classifiers flip from
  soft-known to hard assertions. README's parity claims need refreshed
  numbers before they can name the new default.
- **Family 6 goes on the critical path.** `EMPTY_ITERATION_SPANS` unset is
  default behavior, so the φ-finals protocol change (protocol-only —
  skeletons already agree; mode-gated so the RE2 lane is untouched) is
  mandatory, not an opt-in nicety. Provisional research pinned the rule
  (WBS item 4): java's `GroupCurly`-vs-`Loop` compile split gives the
  axis two sub-families of opposite polarity — suppress (all-zero-width
  min-0 bodies → −1) and surface (empty-capable non-deterministic
  bodies → report the final zero-width iteration).
- **Most user-visible break is fold.** Current `CASE_INSENSITIVE` compiles
  silently lose non-ASCII folding unless they add `UNICODE_CASE`. The rest
  only bites inputs with `\r`/trailing newlines, lone surrogates/well-formed
  pairs, and `(?U` syntax.
- **Finding 1 folds in.** The CharSequence pair-interior scan bug
  (`runGeneric` missing the skip) becomes a `CODEPOINT_BOUNDARIES`-lane
  obligation; the default (unit) lane is trivially consistent there. The
  fix still lands first — it's a live default-lane bug until the flip, and
  a RE2_COMPAT-lane bug after.

## Work breakdown

| item | effort |
|---|---|
| Fix finding 1 (CharSequence scan pair-interior skip) — prerequisite either way — **landed** | 0.5 d |
| Parameterize the pivots: terminator set + anchor EOL rules in `positionFlags*` + `DOT`, per-unit vs codepoint wordness, scan gating — both behaviors selectable, **default unchanged**, campaign probes both lanes — **landed** | 3–5 d (audit-heavy: every rung × tier × input type) |
| `UNIX_LINES`, `UNICODE_CASE` bits + ASCII fold universe; `UNGREEDY_U` parse + top-level-only scoped-fold rejection — **landed** (selectable + `CompileOptions.semantics`, default unchanged) | 1 d |
| Family 6: group-participation protocol — two sub-families (suppress the min-0 all-zero-width span; surface the subsumed zero-width final iteration), four sub-items in the [work breakdown](jur-compat-wbs.md) | 2.5–4.5 d |
| The flip commit: default changes, `RE2_COMPAT` preset, `PatternCompiler` whitelist, javadoc, README/`BENCHMARKS.md` re-baseline | 1 d |
| Tests: per-flag unit tests from the campaign replay seeds; jur campaign classifiers → assertions; `DifferentialFuzzer` matrix gains the `RE2_COMPAT` lane; soak + README numbers | 1.5–2 d |

Total ≈ **1.5–2 weeks** focused. Ordering inside it: everything up to and
including the flip commit is landable with default behavior unchanged (the
flags exist, the campaign probes both lanes, nothing flips), so the flip
itself is one small, separately reviewable commit.

## Open decisions

- ~~Bundle `EMPTY_LAST_LINE` + `END_OF_TEXT_ONLY` into one bit or keep the
  split~~ **Decided with the pivot item: keep the split** (rationale above).
- Whether `JUR_COMPAT = 0` ships as a self-documenting constant (sugar;
  harmless).
- Scoped `(?U:...)` fold in v2 vs the v1 top-level-only rejection (v1 limit
  above).
