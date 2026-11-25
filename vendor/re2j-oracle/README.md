# re2j (the pristine oracle)

The re2j this repo tests against: a **pristine snapshot of upstream**
([google/re2j](https://github.com/google/re2j)), built as-is — no patches, no
fork. Fully reproducible from this repo.

## Source of truth

`../archives/re2j-29bb9e1b1430289b69246b3935cb010350aa0b4a.tar.gz` — upstream
`master` at that commit (2026-11-23, "Allow unescaped '$' in
Matcher.appendReplacement when not a group reference", #223), sha256
`77a5f11559dafc6c3f30e21a6ed005ffe56a2b853a8bbc8a85aa6c7954d518e8` per its
`.sha256` sidecar. No extracted tree is committed.

## Layout

- `build-oracle.sh` — verifies the archive checksum, extracts into a
  gitignored `.build/` scratch dir, compiles `java/` only (javac + jar, no
  build system needed) and writes the oracle jar here:

      vendor/re2j-oracle/build-oracle.sh <path to org.jspecify:jspecify jar>

  The one argument exists because upstream's API sources are
  JSpecify-annotated (google/re2j #210): javac needs the annotations on its
  classpath (the coordinates upstream's own `build.gradle` declares).
- `re2j-pristine.jar` — the oracle, **gitignored**. Built on demand:
  `:tests:parity:re2j:buildOracle` runs the script (resolving the
  annotation jar) whenever the oracle is on a classpath. Its manifest
  carries the snapshot's commit (`Implementation-Version`): the identity
  `OracleIdentityTest` pins and the fuzz `summary.txt` records.

## Why pristine

The three fixes the former patched fork (jemmix/re2j; patches 0001–0003
against tag 1.8, in this repo's history) held are upstream, so the fork is
retired:

| fix | upstream |
|---|---|
| literal-prefix search starting inside surrogate pairs | [#208](https://github.com/google/re2j/pull/208) |
| `FoldCase` in `Regexp.equals`/`hashCode` for LITERAL/CHAR_CLASS | [#212](https://github.com/google/re2j/pull/212) |
| `simpleFold` follows only symmetric case mappings | [#213](https://github.com/google/re2j/pull/213) |

Under it every divergence is a real finding: the fuzzer probes which re2j
is on the classpath and keeps its known-divergence classifier and hang
guards off.

## What the snapshot carries beyond those three

`master` is 21 commits past 1.8. What each one meant for the oracle
contract, measured against the surface when this snapshot was vendored:

- **#202** — `split` at limit 0 drops the trailing segment of an input the
  delimiters consumed whole (`","` on `",,"` → `[]`). The machinery's
  `TDFAPattern.split` ports it — which also closes the jur facade's
  documented divergence from the JDK on the same case.
- **#205** — a failed match (`find`/`matches`/`lookingAt`) clears the match
  state: `group()/start()/end()` throw instead of answering the previous
  match, and the next `find()` begins at the start of the input. The
  surface already behaved this way; the cells are pinned
  (`MatcherApiParityTest`).
- **#220** — `simpleFold`'s fallback is bounded by `MAX_FOLD` (0x1044F):
  the fold universe moved (below).
- **#199** — the fold tables were regenerated: `(?i)\p{Ll}` now reaches
  the uppercase letters (ASCII A–Z included). The bridge reads the tables
  live; `CaseSensitivityParityTest` pins the direction.
- **#193** — the UTF-8 byte path's lookback read signed bytes, so `(?m)^`
  matched after every multibyte character. Fixed; the surface decodes to
  text and already answered this way.
- **#219** — compiled programs are capped at 500 000 instructions
  (`PatternSyntaxException: expression too large`). No new divergence: the
  only accept/reject differences are patterns the oracle takes and the
  surface rejects by its own, tighter compile budget — the fuzzer's
  documented budget class, unchanged since 1.8.
- **#204 / #223** — `appendReplacement` was rewritten. Behaviourally
  identical to the 1.8 code the surface ports: an 8 064-comparison
  replacement-string differential is equal except one exception *message*
  (`${name with a space}`: upstream looks the name up, the JDK and the
  surface report the missing `}`). A corner where upstream now disagrees
  with the JDK; the shared matcher keeps the JDK's reading.
- **#188** (one fewer groups-array copy), **#210** (JSpecify), and the
  test/CI commits — no observable change.

## The one known residual

`find()` with no match state to continue from scans from the **start of
the input** (re2j's contract: "the end of the last match, or else the
beginning of the input"). The surface scans from the append position
instead, which is the same thing except after an `appendReplacement` — the
next `find()` then resumes at the append position rather than re-finding
the first match. TODO.md tracks it: closing it means carrying the lane
into the matcher's emitted `find()` — a matcher-protocol change, not an
oracle bump — because the jur facade's reading differs: java resumes from
the end of the last successful match since the reset, so a `find()` after
an exhausted iteration stays `false` where the surface answers `true`.

## The fold universe: what the oracle does, and how we target it

tdfa targets it BY CONSTRUCTION, not by replication: the parity/fuzz
pipeline compiles tdfa with `com.google.re2j.Re2jUnicodeProvider`, whose
`foldCounterparts` walks the oracle's own `Unicode.simpleFold` — whatever
is on the classpath, tdfa folds identically (fold divergence = real bug
under ANY oracle).

The snapshot's universe is "generated 6.0 table ⊕ runtime symmetric
mappings below `MAX_FOLD` (0x1044F)". Measured against the retired fork by
walking every codepoint: identical except exactly **534 codepoints** —
eight scripts' symmetric case pairs above the ceiling (Georgian Mtavruli
U+104B0..104FB, Vithkuqi U+10570..105BC, Old Hungarian U+10C80..10CF2,
Garay U+10D50..10D85, Warang Citi U+118A0..118DF, Medefaidrin
U+16E40..16E7F, U+16EA0..16ED3, Adlam U+1E900..1E943), which the fork's
unbounded walk folded and #220 leaves fold-inert.

tdfa's DEFAULT universe (no provider) stays JDK-modern —
`java.util.regex` parity. It therefore differs from the oracle's on two
families, both pinned in `FoldCaseParityTest` as divergences of the
default lane, never of the bridged one:

- the Cyrillic historic letters U+1C80..U+1C88 (fold-inert in the oracle
  since #213);
- the 534 codepoints above `MAX_FOLD`.

Post-6.0 pairs below the ceiling (U+A7CC/U+A7CD, Unicode 14.0) fold on
every oracle: the bound is a ceiling, not a Unicode-version cut.

## Use as the fuzz oracle

The pristine snapshot is the DEFAULT re2j everywhere one answers — the
parity suites, the rebar scenarios, the fuzzer — and the only
`com.google.re2j` on their runtime classpaths.

    ./gradlew :tests:parity:re2j:fuzz -Pfuzz.lane=re2j -Pfuzz.minutes=480 ...

`summary.txt` names it:

    oracle: re2j upstream snapshot 29bb9e1b1430289b69246b3935cb010350aa0b4a (known-divergence classifier off) + ...

To run against RELEASED re2j 1.8 from Maven instead — an oracle with known
bugs of its own (the pair-interior match, the `FoldCase` factoring bug, the
`(?i)` compile hang on U+1C80..U+1C88), which the fuzzer probes and answers
with its known-divergence classifier and hang guards:

    ./gradlew :tests:parity:re2j:fuzz -Pfuzz.releasedOracle=true -Pfuzz.lane=re2j ...

No gate runs it. The parity suite holds under both oracles (every
oracle-dependent cell probes which one is answering); the one exception
predates this snapshot: `CampaignSmokeTest`'s stretch and budget legs
report a hard finding against 1.8, because the campaign harness has no
released-oracle classifier.

### Re-base soak

The README's fuzz evidence was re-based on this oracle by one local
overnight soak (2026-11-25; engine at `1a2f4cf8`, main after #204):

    scripts/fuzz-soak.sh 480 1 build/fuzz-re2j 20400 -Pfuzz.lane=re2j

480 one-minute chunks (seeds 20401–20880), every default axis on (flag
matrix, multi-valued lane, cross-rung ladder, stretch, CharSequence
wrappers, region/bounds, determinism double-run):

- **73 346 168 cases, 0 divergences.** No `RESULT_MISMATCH`,
  `COMPILE_PARITY`, `EXCEPTION`, `MEMO_STATE_MISMATCH`, cross-rung,
  stretch, region, multi-valued or hang record in any chunk. Nothing is
  classified away under this oracle: a divergence would have been
  recorded as a finding.
- **15 328 `BUDGET_REJECT` cases (0.021%) over 1 916 patterns** — the
  documented budget class (above, #219) and the only failure records of
  the night. Raised-knob replay of the first chunk's three: two compile
  at the library budget and answer the oracle byte-identically on all
  eight inputs; the third still rejects there (54 MB of retained tables
  against the 16 MiB runtime cap).
- **`MVT_BUDGET_REJECT` on 15 patterns** — plain fits, both multi tiers
  reject at the scoped budget; counted, never a finding.

Every case replays from its seed (`-Pfuzz.one=<caseSeed>`).

## Bumping the snapshot

    ./scripts/vendor.sh refresh re2j <full sha>

1. `./scripts/vendor.sh prepare`: `vendor/patches/re2j/01-package-rewrite.patch`
   (the re2j-suite's package rename) must apply leaving no `.orig` behind —
   its context includes `UnicodeTables.java`'s "Generated at" stamp line,
   which changes whenever upstream regenerates the tables.
2. `./gradlew check`: `OracleIdentityTest` fails if the jar on the classpath
   is not the archive's, and the parity suites fail wherever the surface
   must follow a behaviour change. "What the snapshot carries" above is the
   record of the last such pass — rewrite it for the new one.
3. Update this file (the sha, the commit, the lists).
