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
