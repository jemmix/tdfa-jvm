# JUR API parity — the matcher family, the flags, the escapes

Status: **landed** (2026-11-11; the vs-jur region/grapheme fuzz
expansion — JDK-8394894 — landed 2026-11-13, see the region/bounds
axis section). The TODO item "Jur Matcher API parity:
region, bounds, hitEnd" ([JDK-8394894](https://bugs.openjdk.org/browse/JDK-8394894))
is closed by this page's landing, which covered the whole region/bounds
family plus the JDK-mirroring compile flags and the java-only backslash
escapes. This page is the full accounting: what shipped, how each piece
was verified (every cell was probed on a live JDK 26 before
implementation — the vendored `Pattern.java`/`Matcher.java` sources
pinned the mechanics), and the consciously excluded tail.

## The matcher family (methods/signatures)

On the shared machinery matcher (`core.engine.Matcher`, both facades'
matchers extend it; the JUR facade is the one that claims the family):

- `region(int, int)` / `regionStart()` / `regionEnd()` — matching
  confined to `[from, to)`, reported positions ABSOLUTE, `region()`
  resets the matcher, `reset()`/`reset(CharSequence)` return it to the
  default region while the BOUNDS FLAGS survive, `find(int)` resets the
  region — every protocol detail JDK-exact.
- `useAnchoringBounds(boolean)` / `hasAnchoringBounds()` (default on):
  `^`/`$`/`\A`/`\Z`/`\z` anchor at the region edges; off: at the true
  text edges. `useTransparentBounds(boolean)` /
  `hasTransparentBounds()` (default off): `\b`/`\B` read their true
  neighbors across the region edge vs treating the region as the whole
  text. (Lookarounds do not exist in this engine — the word pair is the
  whole affected family.)
- `hitEnd()` / `requireEnd()` — **re-derived cold**: a tagless probe
  over the artifact (a deterministic replay on success, a live-set scan
  on failure) rather than hot-path bookkeeping, so the fast lanes pay
  nothing. The probe mirrors what java's nodes record: end-anchor gates
  that held (`$`/`\Z` set both flags, `\z` only hitEnd), word pairs
  merely EVALUATED past the region end (both flags — the failed
  `a\B`-on-`"xa"` probe is the pinned shape), a walk alive at the
  region end (java's greedy probing), the stop-table quiet-win
  exception (`a|ab` completing at the end sets neither), and java's
  Start-node rule on failure (unanchored searches always scanned to
  the end → hitEnd; `absBeginBound` artifacts are the Begin-rooted
  exception). Battery: `JurApiParityTest.hitEndAndRequireEnd...`.
- `toMatchResult()` — an immutable `MatchSnapshot` that IMPLEMENTS
  `java.util.regex.MatchResult` (the JDK's named-group defaults ride
  the engine's `namedGroups()` map; the tdfa `groupSpans` readout is
  additive); a failed find yields the JDK's empty shape, not a throw.
- `usePattern(TDFAPattern)` — position and append state kept, group
  info lost, JDK-exact — **machinery tier only**: the ASM tier's
  generated matchers bake their engine's concrete type and refuse
  loudly (`-Dtdfa.engine=VM` serves the full contract). Every facade
  pattern IS a `TDFAPattern` at runtime (the minted shells extend it),
  which is the one spelling the shared machinery can offer.
- `replaceAll`/`replaceFirst` (`Function<java.util.regex.MatchResult,
  String>` — the exact JDK signature; null answers NPE like the JDK),
  `appendReplacement(StringBuilder, Function)`, `results()`,
  `hasMatch()`/`hasGroup(int)` (JDK 20+ interface defaults, overridden).

## The region/bounds fuzz axis (JDK-8394894) and its findings

The vs-jur fuzz expansion added a region/bounds axis to the overnight
soak (a deterministic 1-in-N sample of cases draws a region and a
bounds combo — all four, default-weighted — and re-runs a region'd
F/I/L/M protocol with `hitEnd`/`requireEnd` after every probe over
BOTH tiers, plus the live oracle under the identical operations on the
jur lane; `-Pfuzz.region=0` off, `-Pfuzz.regionEvery=N` resamples) and
a grapheme generator family. Its landing fixed eight region/end-flag
divergences the cell battery could not see:

- **Failed `matches()`/`lookingAt()` end flags** — java resets
  `hitEnd`/`requireEnd` per operation; a failed anchored op reports the
  anchored walk's own flags, never the failed-search scan rule
  (`RegexEngine.probeEndFlags` gained the op's anchor mode; the emitted
  shells maintain it).
- **`lookingAt` under non-default bounds** — the region seam applied
  the whole-region tail rule to a prefix op; the walk's start and tail
  disciplines split (`runGeneric`'s `anchored`/`whole`).
- **Regions cutting surrogate pairs** — java's consuming nodes read
  the full codepoint and require both units inside the region; the
  subSequence view handed the engine a genuinely-lone high surrogate
  (matchable as one unit). Cut and split regions now serve through the
  bounded seam (`matchRegionWhole` joins it), and the walk encodes the
  cut-pair rule.
- **Continuation `find()` bases** — the search's resume point was
  shifting the region's anchor bases; the seam now carries the region
  head and the scan start separately.
- **Word-boundary reads** — the right-side read decodes pairs across
  the window end (java's unbounded `codePointAt`).
- **The stale-search flags** — java's early-return `find()`
  (`nextSearchIndex > to`) keeps the last executed search's flags.
- **The Start rule's exception** — only the BARE root begin anchor
  (`\A`/`^` at the pattern head, nothing group-shaped before, no root
  alternation — java's `matchRoot instanceof Begin`) skips the
  failed-search `hitEnd`; the parse-frozen `rootBeginAnchor` carries
  the exact shape, and java's Start `minLength` guard bounds which
  starts ever run.
- The region'd end-flag derivation as a whole stays battery-pinned,
  not fuzz-general: after the eight fixes above, overnight-slice
  scales still surface residue families (java's per-path zero-width
  gate pruning, sequential gate order within one mask, Curly unit
  reads) that a deterministic artifact cannot fully mirror — the fuzz
  axis compares SPANS only, the battery (the no-region END_FLAG_CELLS
  plus the region'd/failed-anchored cells) owns the flags.

Two java-backtracker SPAN shapes the DFA cannot see are counted skips
in the fuzz axis, never findings, and pinned both ways in
`JurApiParityTest`: the Curly cut-pair unit-read quirk (a quantified
wide atom matches a cut pair's high half as one unit where the
unquantified atom dies on the consumption-fits check) and stacked
zero-width heads (`$\A9` — java evaluates the gates in sequence and
the held-before-failing ones set flags; the mask bit-set has no
order). A third cell skips by construction: `matches()` over a
pair-splitting region needs the whole (ENDANCHOR) artifact's region
walk — the find artifact's leftmost-first determinization prunes paths
java's backtracker explores there (a bare lazy star's consuming path),
and the whole artifact has no region-bases walk; those regions keep
F/I/L hard and skip M (`M=-`).

## Pattern-surface spellings (the reflection-checked set)

`asPredicate()`, `asMatchPredicate()` (JDK 21+), `split(CharSequence)`,
`split(CharSequence, int)`, `splitAsStream(CharSequence)` (the JDK's
empty-input cell pinned: one `""`, where `split("")` is the empty
array), `splitWithDelimiters(CharSequence, int)` (JDK 21+, the JDK's
own algorithm ported verbatim). Flag constants `UNIX_LINES`,
`UNICODE_CASE`, `LITERAL`, `COMMENTS` join the roster — names and
semantics JDK-exact, numerics deliberately not (the long-standing
policy). `LITERAL` is a compile-source rewrite (the pattern quoted
before parsing), which composes with `CASE_INSENSITIVE` (folded
literals) and round-trips the ORIGINAL pattern/flags; `COMMENTS` is
the parser's free-spacing mode, also the scoped `(?x)` inline letter
(bound on the JUR lane only — re2j's alphabet has no `x`).

## The escapes (regex syntax, within the mission bounds)

One new `Semantics` axis, `ESCAPES_RE2` (set = re2j's readings, unset =
the JDK's — the same shape as `SPACE_RE2`), gates the java-only
backslash escapes. Unset (the jur facade):

- `\v`/`\V` — the JDK's vertical-whitespace CLASS
  `[\n\x0B\f\r\x85\u2028\u2029]` (re2j's `\v` = U+000B stays on the
  RE2 lane); in-class, `[\v-z]` reads `\v` as U+000B at a range
  endpoint (java's `isrange` look-ahead, live-verified).
- `\h`/`\H` — horizontal whitespace
  `[ \t\xA0\u1680\u180E\u2000-\u200A\u202F\u205F\u2060\u3000]`.
- `\e` — ESC (U+001B). `\R` — any Unicode linebreak
  (`\r\n` at leftmost-first priority). `\cX` — control char
  (`read ^ 0x40`, any char, in-class too). `\N{NAME}` —
  `Character.codePointOf(String)` (@since 9 — reached reflectively so
  the Java 8 floor holds; a Java 8 RUNTIME rejects `\N{...}` loudly).
  `\Z` — end of input or before the final terminator, immune to
  `(?m)` (the plain-`$` rule — it lowers to FINAL_END exactly like
  java's `Dollar(false)`).
- Bare `\0` now REJECTS like the JDK 26+ parser ("illegal octal
  escape" — `\00`/`\000` are the NUL spellings); `\0n`-octal still
  reads 1–3 digits capped at 0xFF.

Set (the re2j facade): every letter above rejects at the parse
(re2j's alphabet — pinned by `JurApiParityTest.re2jFacadeKeeps...`),
`\v` stays U+000B.

**Consciously out of this landing** (the mission bounds): lookarounds
and backreferences (backtracking-dependent, always rejected), `\G`
(needs a previous-match-end predicate threaded through both tiers'
entries — a feature of its own), `\X`/`\b{g}` (graphemes — rejected
loudly, see below), `CANON_EQ` (normalization-aware compilation), scoped
inline `(?d)` (the terminator-set axis is compile-global; the
UNIX_LINES FLAG is the JDK-exact spelling), and java's `\1`-`\9`
backreference readings of digit escapes (the octal fallback stays
lineage-shared — the BackrefParityTest twins pin the divergence class).

**Graphemes (JDK-8394894) — rejected loudly, never silently degraded.**
`\X` rejects at the parse (re2j's alphabet — both facades, oracle-parity
on the re2j lane). The java boundary clauses `\b{g}`, `\b{gc=…}` and
every other non-quantifier brace after `\b`/`\B` reject under the jur
reading (the ESCAPES_RE2 axis's unset side): java reads that brace as
its boundary-type clause or an illegal repetition — NEVER a literal —
while the re2j facade keeps the literal-brace reading (re2j's alphabet,
pinned by `re2jFacadeKeepsTheLiteralBraceReading` and the RE2J fuzz
lane; the literal spelling is `\b\{`). Before the vs-jur fuzz landing
the parser silently read the RE2 literal on the jur lane — the first
finding of the region/grapheme axis. The fuzz coverage: a fifth
generator family draws `\X`/`\b{g}` shapes over cluster-carrying
inputs (the jur lane classifies them `graphemes-unsupported`, the RE2J
lane compares the literal reading hard), pinned cell-for-cell in
`JurApiParityTest.graphemeBoundaryClausesRejectLoudly…`.

## How it is verified

- **Reflection, per surface** — `JurApiCompatTest` (unit suite: our
  jur `Pattern` + shared matcher + snapshot vs the LIVE JDK's
  `Pattern`/`Matcher`/`MatchResult`, member by member, type-mapped,
  with `CANON_EQ` the one asserted exclusion and the roster message
  naming it) and `Re2jApiCompatTest` (the re2j twin, vs
  `com.google.re2j` off the patched oracle jar; the JDK family on the
  shared matcher asserted as ADDITIVE from re2j's point of view).
  Before this landing the re2j facade's API compat was verified by
  running re2j's own vendored test suite against it plus the parity
  batteries — the reflection checks add the mechanical member-by-member
  layer on top, for both facades.
- **Behavior, per cell** — `JurApiParityTest` (parity suite: region/
  bounds over find/lookingAt/matches, the hitEnd/requireEnd battery,
  the escape cells, the flag batteries, usePattern, snapshot,
  function replace, `splitWithDelimiters`/`asMatchPredicate`/
  `splitAsStream`), every cell compared against the live
  `java.util.regex` oracle, parameterized over both engine tiers. The
  pre-landing probes live in this page's PR (every flag semantics,
  the `\E`-LITERAL quirk, the JDK's `splitAsStream("")` oddity, the
  `usePattern` position protocol were probed on a live JDK first).

## Engine shape (why nothing got slower)

Region'd operations never touch the String fast lanes: the generated
matcher's inlined `find()`/`matches()`/`lookingAt()` carry one
predictable never-taken branch that bails to the machinery
implementation when a region is active; the default bounds combo
serves through a subSequence view (positions rebased by a
`MatchResult.shifted` twin), the opt-out combos through a new cold
`RegexEngine.matchRegion`/`matchRegionLookingAt` seam (region-aware
bases threaded through the generic walk only; the ASM tier delegates
to its embedded runner, the posture of `matches()`). `hitEnd`/
`requireEnd` are cold probes (`RegexEngine.probeEndFlags`). BYO
engines that do not override the seams reject loudly — a region query
is never answered over the wrong bounds. `matches()` under
NON-DEFAULT bounds over a predicate-carrying artifact rejects loudly
too (the whole engine cannot bound predicates; normalize the region
first) — predicate-free artifacts are exact under any combo.
