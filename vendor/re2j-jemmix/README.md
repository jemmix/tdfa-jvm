# re2j (jemmix patched builds)

Patched re2j used as the parity/fuzz oracle. Fully reproducible from this
repo — no external fetches needed to rebuild.

## Layout

- Source of truth: `../archives/re2j-57278921a609461c14d9cdb057d7aa9511c8f7ac.tar.gz`
  (upstream tag `re2j-1.8`, sha256 `ed9bad5166a29760f29f37644add40070c59baaa30662dbfbbc408e2135cbf3b`
  per its `.sha256` sidecar). No extracted tree is committed.
- `patches/` — one patch per fix, applied cumulatively in filename order:
  - `0001` — literal-prefix search starting inside surrogate pairs
    (`MachineInput.StringInput.index`: a raw `indexOf` hit on the low half
    of a well-formed pair is not a codepoint boundary; skip and keep
    searching). Fixes the monotonicity violation where `\uDC21` matched
    inside a surrogate pair while `\uDC21|\uDC22` and `[\uD800-\uDFFF]`
    did not. Adds `javatests/.../SurrogatePairTest.java`.
  - `0002` — compare FoldCase in `Regexp.equals`/`hashCode` for
    LITERAL/CHAR_CLASS. Alternation factoring merged a folded literal with
    its case-sensitive twin and deleted the case-sensitive arm:
    `(?i:Z)x|Z` matched lowercase "z" (Go's regexp/syntax compares
    Flags&FoldCase; the port dropped it). Was misread as a residual
    lone-surrogate oracle divergence (fuzz record 2026-08-30).
  - `0003` — `0001` overcorrected: it skipped interior `indexOf` hits
    unconditionally, including a hit AT the explicitly given search start.
    `Matcher.find(int)` handed a pair-interior position must match there
    (java.util.regex does; tdfa's engines do; released re2j's non-prefix
    paths do). The skip now applies only to hits strictly beyond the
    start. Found by the tdfa fuzzer's `find(len/2)` restart probe.
  - `0004` — bound the simple-fold orbit walks
    (`CharClass.appendFoldedRange`, `Parser.minFoldRune`,
    `Unicode.equalsIgnoreCase`) by `Unicode.MAX_FOLD_ORBIT_HOPS`. The walks
    assume `simpleFold`'s next-pointer graph cycles back to the start; that
    holds for `CASE_ORBIT` (generated as closed orbits) and the
    toLower/toUpper fallback on symmetric pairs, but breaks for runes whose
    asymmetric case mappings postdate the table's Unicode version
    (U+1C80..U+1C88, Unicode 9.0 vs tables at 6.0): the fallback steps into
    the partner's symmetric orbit and never returns — `Pattern.compile`
    hangs forever (e.g. `(?i)Ꚁ`). Simple-fold orbits have at most 4
    members, so the cap is inert on well-formed data (verified: compile
    results are bit-identical); on the stale-table runes it degrades the
    hang to a bounded walk over the orbit the current data can express
    (7 of 9 literals collect the exact modern orbit; Ꚅ/ꚅ collect a strict
    subset — the full orbit needs `0005`). Adds
    `javatests/.../CaseFoldTerminationTest.java`. Control-flow-only fix,
    zero data changes — upstreamable as-is (Go is immune only because its
    `unicode.caseOrbit` is regenerated per release; the same fallback code
    shape exists there).
  - `0005` — overlay the asymmetric case-fold orbits the generated table
    (Unicode 6.0) predates: the Cyrillic historic letters U+1C80..U+1C88
    (Unicode 9.0), eight orbits wired as closed next-pointer cycles beside
    CASE_ORBIT (`UnicodeTables.CASE_ORBIT_OVERLAY`, consulted on table
    miss only, so generated entries keep precedence — a parallel table
    because A64A/A64B exceed the char-indexed array's length). With 0004
    alone, folding these runes degrades (partner-side walks miss the
    historic letter); 0005 completes them. After 0004+0005 the fork's fold
    universe is bit-identical to tdfa's across all 0x110000 codepoints
    (verified by exhaustive orbit diff). NOT for the 0004 upstream PR:
    upstream should regenerate the tables from current Unicode instead of
    carrying a hand-written overlay (the generator's data source is the
    stale part, ICU 6.0-era).
- `build-patched.sh` — verifies the archive checksum, extracts into a
  gitignored `.build/` scratch dir, applies patches, compiles `java/` only
  (pure javac+jar, no build system needed):
  `vendor/re2j-jemmix/build-patched.sh [1|2|3|4]` (default 4).
- `re2j-1.8-jemmix-fix{1,2,3,4}.jar` — build outputs, **gitignored**. Built on
  demand: `:tests:parity:re2j:buildPatchedOracle` runs the script whenever
  the default (patched) oracle is on the test classpath.

## Fork / upstreaming

Same fixes live on https://github.com/jemmix/re2j, one branch per fix,
both based on upstream master and passing re2j's format/license gates
(`verifyGoogleJavaFormat` + `license`; the patch files here remain the
canonical per-fix diffs against tag `re2j-1.8`):

- `fix-surrogate-pair-interior-prefix` (`dfea17f`) — fix1 **(stale: predates
  patch 0003 — see below; do not upstream as-is)**
- `fix-foldcase-in-regexp-equals` (`9a7eca4`) — fix2

Upstreaming notes:

- **fix1 + 0003 are one logical fix.** `dfea17f` skips interior `indexOf`
  hits unconditionally — including a hit at the explicitly given search
  start — which diverges from `java.util.regex` (and released re2j's own
  non-prefix paths) on `Matcher.find(int)` with a pair-interior start.
  Before opening the upstream PR, squash 0001+0003 into a single commit on
  the branch (both `SurrogatePairTest` additions included). The bug itself
  remains upstream-worthy: results depended on pattern shape
  (`\uDC21` matched inside a pair while the wider `\uDC21|\uDC22` and
  `[\uD800-\uDFFF]` did not — a monotonicity violation), and JDK 26's
  java.util.regex also refuses interior starts on scan.
- fix2 is upstream-ready as branched.

To be upstreamed to google/re2j as two issue/PR pairs (TODO decision A;
Google individual CLA is a merge prerequisite).

## Use as the fuzz oracle

The patched build is the DEFAULT oracle for the parity/fuzz subproject —
plain invocations use it, building the jar automatically via
`buildPatchedOracle` (no local toolchain beyond javac + tar needed):

    ./gradlew :tests:parity:re2j:fuzz -Pfuzz.minutes=480 ...

To fuzz/parity-test against released (pristine) re2j 1.8 from Maven instead:

    ./gradlew :tests:parity:re2j:fuzz -Pfuzz.pristineOracle=true -Pfuzz.minutes=480 ...

The fuzzer probes which oracle is on the classpath (the lone-low-surrogate
interior behavior is the discriminator) and turns its known-divergence
classifier off under the patched build — every divergence is a real finding.
