# re2j (jemmix patched builds)

Patched re2j used as the parity/fuzz oracle. Fully reproducible from this
repo — no external fetches needed to rebuild.

## Layout

- Source of truth: `../archives/re2j-57278921a609461c14d9cdb057d7aa9511c8f7ac.tar.gz`
  (upstream tag `re2j-1.8`, sha256 `ed9bad5166a29760f29f37644add40070c59baaa30662dbfbbc408e2135cbf3b`
  per its `.sha256` sidecar). No extracted tree is committed.
- `patches/` — one patch per fix, applied cumulatively in filename order.
  Each patch is an upstreamable unit (see "Fork / upstreaming"):
  - `0001` — literal-prefix search starting inside surrogate pairs
    (`MachineInput.StringInput.index`: a raw `indexOf` hit on the low half
    of a well-formed pair is not a codepoint boundary; skip and keep
    searching — but honor an explicitly given search start, which
    `Matcher.find(int)` may legitimately land on a pair interior
    (java.util.regex parity: only hits strictly beyond the start are scan
    hits). Fixes the monotonicity violation where `\uDC21` matched inside
    a surrogate pair while `\uDC21|\uDC22` and `[\uD800-\uDFFF]` did not.
    Adds `javatests/.../SurrogatePairTest.java`. (Formerly two patches,
    0001+0003; squashed — the overcorrection and its fix are one logical
    change and neither behavior is defensible alone.)
  - `0002` — compare FoldCase in `Regexp.equals`/`hashCode` for
    LITERAL/CHAR_CLASS. Alternation factoring merged a folded literal with
    its case-sensitive twin and deleted the case-sensitive arm:
    `(?i:Z)x|Z` matched lowercase "z" (Go's regexp/syntax compares
    Flags&FoldCase; the port dropped it). Was misread as a residual
    lone-surrogate oracle divergence (fuzz record 2026-08-30). Adds
    `javatests/.../FoldCaseFactoringTest.java`.
  - `0003` — make `simpleFold`'s fallback follow only symmetric case
    mappings. The fallback (table miss → `toLower`/`toUpper`) assumed the
    mappings form closed two-element orbits; for runes whose asymmetric
    mappings postdate the table's Unicode version (U+1C80..U+1C88,
    Unicode 9.0 vs tables at 6.0) it steps into the partner's own orbit
    and never cycles back — `Pattern.compile("(?i)Ꚁ")` hangs forever. The
    guard follows a mapping only when the partner maps back, so those
    runes are fold-inert and every walk terminates structurally (no hop
    cap needed). Control-flow-only, zero data changes; symmetric pairs —
    including post-6.0 ones reached via the runtime's mappings — fold
    exactly as before. Adds `javatests/.../CaseFoldTerminationTest.java`.
    (Replaces the former hop-cap patch 0004: a cap makes the walks
    terminate but leaves the degraded results — measured on the fix4
    build: `(?i)Ꚁ` failed to match `Ꚁ` ITSELF (minFoldRune canonicalized
    the literal to В, whose orbit walk never returns), literal and class
    forms disagreed, and folds were asymmetric (`(?i)Ꚁ`→в matched,
    `(?i)в`→Ꚁ did not). Not a behavior anyone should target or ship.)
- `build-patched.sh` — verifies the archive checksum, extracts into a
  gitignored `.build/` scratch dir, applies patches, compiles `java/` only
  (pure javac+jar, no build system needed):
  `vendor/re2j-jemmix/build-patched.sh [1|2|3]` (default 3).
- `re2j-1.8-jemmix-fix{1,2,3}.jar` — build outputs, **gitignored**. Built on
  demand: `:tests:parity:re2j:buildPatchedOracle` runs the script whenever
  the default (patched) oracle is on the test classpath.

## The fold universe: what the oracle does, and how we target it

The former patch 0005 (hand-written overlay of the eight post-6.0 orbits)
is GONE by design: it was not upstreamable (upstream should regenerate the
tables from current Unicode, not carry an overlay), and it made the fork's
fold universe something no dataset expresses. With 0003 alone the oracle's
fold universe is exactly "generated 6.0 table ⊕ runtime symmetric
mappings" — characterized exhaustively (JDK 26): it differs from tdfa's
default (JDK-derived modern) universe on precisely the 25 codepoints of
the U+1C80..U+1C88 family (9 letters fold-inert; 16 partners keep their
plain pairs). Everything else — İ/ı inert, post-6.0 symmetric pairs —
agrees.

tdfa targets it BY CONSTRUCTION, not by replication: the parity/fuzz
pipeline compiles tdfa with `com.google.re2j.Re2jUnicodeProvider`, whose
`foldCounterparts` walks the oracle's own `Unicode.simpleFold` — released
or patched, whatever is on the classpath, tdfa folds identically (fold
divergence = real bug under ANY oracle; the fuzzer's stale-orbit
known-divergence entry is retired). tdfa's DEFAULT universe (no provider)
stays JDK-modern — java.util.regex parity — with the family gap pinned as
a documented divergence in `FoldCaseParityTest`. The pinned
`unicode/v6_0`/`v17_0` modules now also supply fold universes from their
embedded CaseFolding snapshots (BMP, C+S), so a pinned tier's folding
cannot float with the runtime JDK.

## Fork / upstreaming

Same fixes live on https://github.com/jemmix/re2j, one branch per fix,
based on upstream master (951a615) and passing re2j's format/license
gates (`verifyGoogleJavaFormat` + `license`) and the full test suite on
JDK 8 (`./gradlew check`, per their CI). The patch files here remain the
canonical per-fix diffs against tag `re2j-1.8` — regenerated from the
branched content (the two new test classes were reflowed by
`googleJavaFormat` during branch verification; `java/` hunks unchanged).

All three are filed upstream (2026-09-17), each with its issue:

- `fix-surrogate-pair-interior-prefix` (`420ec9c`) — issue #207, PR
  google/re2j#208. The PR (originally the withdrawn pre-squash branch
  dfea17f) was REOPENED after the rebuild, with a comment explaining the
  withdrawal (the original patch refused explicit `Matcher.find(int)`
  interior starts) and what changed; branch rebuilt on master from
  patch 0001. Reopen required briefly restoring dfea17f as branch head —
  GitHub refuses to reopen a closed PR whose branch was force-pushed —
  then re-pushing 420ec9c.
- `fix-foldcase-in-regexp-equals` (`9a7eca4`) — issue google/re2j#211
  (filed with repro; confirmed on pristine master), PR google/re2j#212.
- `fix-simple-fold-asymmetric-mappings` (`98bfd5f`) — PR
  google/re2j#213, fixing EXISTING issue google/re2j#168 (fmeum, 2023 —
  the U+1C80 hang; rsc's comment there already sketched our exact
  fallback guard, so no duplicate issue was filed). Positioned as the
  minimal control-flow fix, explicitly complementary to the table
  regeneration discussed in that thread.

CLA: signed (the re-run check on #208 went green 2026-09-11, before it
was withdrawn). Upstream Java CI on all three PRs sits at
`action_required` — the standard fork-PR workflow-approval gate for a
first-time contributor; a maintainer must approve the runs. Local
verification: `./gradlew check` on host Zulu JDK 8 (1832/1831/1829
tests, 0 failures). Issues and PRs disclose the agentic (GLM 5.3)
composition of analysis/patches, matching the disclosure already on
#207.

## Use as the fuzz oracle

The patched build is the DEFAULT oracle for the parity/fuzz subproject —
plain invocations use it, building the jar automatically via
`buildPatchedOracle` (no local toolchain beyond javac + tar needed):

    ./gradlew :tests:parity:re2j:fuzz -Pfuzz.minutes=480 ...

To fuzz/parity-test against released (pristine) re2j 1.8 from Maven instead:

    ./gradlew :tests:parity:re2j:fuzz -Pfuzz.pristineOracle=true -Pfuzz.minutes=480 ...

The fuzzer probes which oracle is on the classpath (the lone-low-surrogate
interior behavior is the discriminator) and turns its known-divergence
classifier off under the patched build — every divergence is a real
finding. Fold behavior needs no classifier under either oracle: tdfa is
compiled with the bridge provider above, so the fold universes cannot
disagree.
