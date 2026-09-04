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
- `build-patched.sh` — verifies the archive checksum, extracts into a
  gitignored `.build/` scratch dir, applies patches, compiles `java/` only
  (pure javac+jar, no build system needed):
  `vendor/re2j-jemmix/build-patched.sh [1|2]` (default 2).
- `re2j-1.8-jemmix-fix{1,2}.jar` — build outputs, **gitignored**. Built on
  demand: `:tests:parity:re2j:buildPatchedOracle` runs the script when the
  patched oracle is requested.

## Fork / upstreaming

Same fixes live on https://github.com/jemmix/re2j branch
`fix-surrogate-pair-interior-prefix` (commits `4facb96`, `fa71014` — the
patches there carry unrelated workflow bumps from being cut off master;
the patch files here are the canonical per-fix diffs against tag
`re2j-1.8`). To be upstreamed to google/re2j (TODO decision A).

## Use as the fuzz oracle

    ./gradlew :tests:parity:re2j:fuzz -Pfuzz.patchedOracle=true -Pfuzz.minutes=480 ...

The Gradle property builds the jar automatically via `buildPatchedOracle`
(no local toolchain beyond javac + tar needed).
