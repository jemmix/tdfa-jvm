# re2j (the pristine oracle)

The re2j this repo tests against: **the 1.9 release, pristine** — upstream
([google/re2j](https://github.com/google/re2j)) at its `re2j-1.9` tag,
vendored as a snapshot and built as-is — no patches, no fork. Fully
reproducible from this repo.

## Source of truth

`../archives/re2j-3c8a8fba6537c5bb732807f3a8e1304a1bbcf712.tar.gz` — upstream
tag `re2j-1.9` (2026-11-25, "Replace HTML4 `<tt>` tag with `{@code}` in
PatternSyntaxException javadoc", #229), sha256
`e3c9b9072d71112fb7cbe912d7724ec2449af3affd07860799cf878b23a612fd` per its
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
against tag 1.8, in this repo's history) held are upstream and ship in the
1.9 release, so the fork is retired:

| fix | upstream |
|---|---|
| literal-prefix search starting inside surrogate pairs | [#208](https://github.com/google/re2j/pull/208) |
| `FoldCase` in `Regexp.equals`/`hashCode` for LITERAL/CHAR_CLASS | [#212](https://github.com/google/re2j/pull/212) |
| `simpleFold` follows only symmetric case mappings | [#213](https://github.com/google/re2j/pull/213) |

Under it every divergence is a real finding: the fuzzer probes which re2j
is on the classpath and keeps its known-divergence classifier and hang
guards off.

## What the snapshot carries beyond those three

The tag is 25 commits past 1.8: the 21 `master` carried when this oracle was
first vendored (`29bb9e1b`, 2026-11-23) and the four the release added (last
bullet). What each one meant for the oracle contract, measured against the
surface:

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
- **#225 / #226 / #227 / #229** — the release itself: the version bump, an
  `Automatic-Module-Name` on the jar manifest and the Maven Central Portal
  deploy (`build.gradle`, `RELEASING.md`, `README.md`), and one javadoc line
  in `PatternSyntaxException`. No bytecode: built with `build-oracle.sh`,
  all 46 class files are byte-identical to the `29bb9e1b` jar's — only the
  manifest's `Implementation-Version` moves — so every pin and every soak on
  record against that jar carry over unchanged.

## The released artifact

`build-oracle.sh` compiles the tag; it does not fetch
`com.google.re2j:re2j:1.9`. How the jar built here relates to the published
one (measured 2026-11-26 against Maven Central; the download matched the
repository's published sha1 and md5):

- **Same source.** The published `re2j-1.9-sources.jar` equals the tag's
  `java/` tree file for file — 22 files, upstream's main sourceSet
  excluding the single GWT `super/` source, as the oracle build does.
- **Not the same bytes.** Upstream compiles with
  `targetCompatibility = 1.6` on an older javac; the oracle build uses
  `javac --release 8` on the JDK at hand. So class version 50 against 52,
  and of the 46 classes 34 have the same instruction stream in every method
  while 12 differ only in compiler synthetics: the published jar's 47th
  class `Regexp$1` (the enum-switch `$SwitchMap` holder an older javac
  emits, where `--release 8` switches on `ordinal()` directly), the
  `$values()` method a newer javac adds to the two enums, and the
  constructor shape of the ten anonymous `RE2$N` classes. Beyond those
  synthetics the declared members are identical everywhere, and so is the
  public API of 1.8 and 1.9.
- **Same behaviour.** A differential run (a throwaway driver, outside the
  repo) recorded one hash per case over everything observable: the compile
  pipeline (parse tree, simplified tree, program), every `Pattern`/`Matcher`
  operation on `String`, `StringBuilder` and `CharBuffer` inputs and on the
  UTF-8 byte path (match, find loops, `find(int)`, reset, groups and names,
  `split` at seven limits, `replaceAll`/`replaceFirst` and the
  `appendReplacement` loop over 33 replacement strings, exceptions with
  their messages), and the `RE2`-level API. Over 3 107 196 cases — a fixed
  edge list naming the release-relevant behaviours (the
  surrogate, fold, split, failed-match-state, size-cap and
  `appendReplacement` cases); RE2's own `re2-search.txt`, 944 patterns ×
  472 strings (every string under no flags, a stride sample under each of
  the other 16 flag settings); and 100 000 seeded random patterns with
  mutations — the jar built here and the published jar produced identical
  transcripts.
- **Why not depend on the published jar.** Its manifest carries no commit
  (`Manifest-Version` and `Automatic-Module-Name` only): the oracle's
  probe reads it as a post-#208 build, `implementationVersion()` answers
  `null`, and `OracleIdentityTest` fails. The archive stays the single
  committed source of truth.

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

    oracle: re2j upstream snapshot 3c8a8fba6537c5bb732807f3a8e1304a1bbcf712 (known-divergence classifier off) + ...

To run against the PREVIOUS release, 1.8, from Maven instead — the last
build with known bugs of its own (the pair-interior match, the `FoldCase`
factoring bug, the `(?i)` compile hang on U+1C80..U+1C88), which the fuzzer
probes and answers with its known-divergence classifier and hang guards:

    ./gradlew :tests:parity:re2j:fuzz -Pfuzz.releasedOracle=true -Pfuzz.lane=re2j ...

The property keeps its historical name and selects 1.8 by coordinate. What
the fuzzer probes is the pre-#208 behaviour, not "releasedness" — a 1.9
artifact answers like the snapshot — so re-pointing the property at 1.9 is
not a configuration the suite accepts (`OracleIdentityTest` fails: no
commit to pin) and has nothing to find: it is the default oracle's own
source.

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

The soak ran against the `29bb9e1b` jar. The oracle was moved to the
`re2j-1.9` tag on 2026-11-26; its 46 class files are byte-identical to that
jar's, so the evidence carries over as recorded.

## Bumping the snapshot

    ./scripts/vendor.sh refresh re2j <full sha>

`refresh` is content-reproducible, not byte-reproducible: the top-level
directory's mtime and the gzip header's timestamp are the creation time, so
re-running it on the same commit writes a different sha256 over an
identical tree. The sidecar pins the archive that was committed.

1. `./scripts/vendor.sh prepare`: `vendor/patches/re2j/01-package-rewrite.patch`
   (the re2j-suite's package rename) must apply leaving no `.orig` behind —
   its context includes `UnicodeTables.java`'s "Generated at" stamp line,
   which changes whenever upstream regenerates the tables.
2. `./gradlew check`: `OracleIdentityTest` fails if the jar on the classpath
   is not the archive's, and the parity suites fail wherever the surface
   must follow a behaviour change. "What the snapshot carries" above is the
   record of the last such pass — rewrite it for the new one.
3. Update this file (the sha, the commit, the lists).
