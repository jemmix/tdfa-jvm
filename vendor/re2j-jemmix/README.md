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
- `build-patched.sh` — verifies the archive checksum, extracts into a
  gitignored `.build/` scratch dir, applies patches, compiles `java/` only
  (pure javac+jar, no build system needed):
  `vendor/re2j-jemmix/build-patched.sh [1|2|3]` (default 3).
- `re2j-1.8-jemmix-fix{1,2,3}.jar` — build outputs, **gitignored**. Built on
  demand: `:tests:parity:re2j:buildPatchedOracle` runs the script when the
  patched oracle is requested.

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

    ./gradlew :tests:parity:re2j:fuzz -Pfuzz.patchedOracle=true -Pfuzz.minutes=480 ...

The Gradle property builds the jar automatically via `buildPatchedOracle`
(no local toolchain beyond javac + tar needed).
