# re2j (jemmix patched builds)

Patched re2j used as the parity/fuzz oracle. Fully reproducible from this
directory — no external fetches needed to rebuild.

## Layout

- `re2j-1.8/` — pristine upstream re2j source, tag `re2j-1.8`
  (commit `57278921a609461c14d9cdb057d7aa9511c8f7ac`,
  tarball sha256 `1a5c40a76cd8b640021522b71f81d765282f29d0cb1fe7977b0987a3617a5d3a`),
  vendored verbatim (Apache License 2.0, see `re2j-1.8/LICENSE`).
- `patches/` — one patch per fix, applied in filename order:
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
- `re2j-1.8-jemmix-fix1.jar` / `re2j-1.8-jemmix-fix2.jar` — build outputs,
  checked in so the test suites need no local toolchain beyond javac.
- `build-patched.sh` — rebuilds a jar level from pristine + patches:
  `vendor/re2j-jemmix/build-patched.sh [1|2]` (default 2).

## Fork / upstreaming

Same fixes live on https://github.com/jemmix/re2j branch
`fix-surrogate-pair-interior-prefix` (commits `4facb96`, `fa71014` — the
patches there carry unrelated workflow bumps from being cut off master;
the patch files here are the canonical per-fix diffs against tag
`re2j-1.8`). To be upstreamed to google/re2j (TODO decision A).

## Use as the fuzz oracle

    ./gradlew :tests:parity:re2j:fuzz -Pfuzz.patchedOracle=true -Pfuzz.minutes=480 ...
