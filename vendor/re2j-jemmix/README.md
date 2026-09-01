# re2j (jemmix fork builds)

Patched re2j builds used as alternative fuzzing oracles.

- `re2j-1.8-jemmix-fix1.jar` — re2j 1.8 + the literal-prefix pair-interior fix
  from https://github.com/jemmix/re2j/tree/fix-surrogate-pair-interior-prefix
  (commit `4facb96`). Fixes the monotonicity violation where `\uDC21` matched
  inside a well-formed surrogate pair while `\uDC21|\uDC22` and
  `[\uD800-\uDFFF]` did not (raw `String.indexOf` positions in the
  literal-prefix fast path).

Built by compiling the fork branch's `java/com/google/re2j/` (tests excluded)
and jarring the classes; no other changes vs the released 1.8 artifact.

Use as the fuzz oracle:
    ./gradlew :tests:parity:re2j:fuzz -Pfuzz.patchedOracle=true -Pfuzz.minutes=480 ...

- `re2j-1.8-jemmix-fix2.jar` — fix1 + FoldCase in `Regexp.equals`/`hashCode`
  for LITERAL/CHAR_CLASS (fork commit `fa71014`). Alternation factoring merged
  a folded literal with its case-sensitive twin, deleting the case-sensitive
  arm: `(?i:Z)x|Z` matched lowercase "z" (Go's regexp/syntax compares
  Flags&FoldCase; the port dropped it). This was misread as a residual
  lone-surrogate oracle divergence (fuzz record 2026-08-30): the leaked
  `(?i:)` was what matched, not the interior position.
