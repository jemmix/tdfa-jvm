# java.util.regex (jemmix patched build)

Patched OpenJDK `java.util.regex` used as the JUR-lane fuzz/soak oracle —
the "what if the JDK fixed JDK-8381696" dress rehearsal, exactly parallel to
the patched re2j oracle in `../re2j-jemmix/`: pristine vanilla sources
vendored as one archive, one patch per fix, built on demand, patched by
default. Fully reproducible from this repo — no external fetches needed.

## What it fixes

[JDK-8381696](https://bugs.openjdk.org/browse/JDK-8381696) — Matcher leaks
capture groups from undone attempts (failed scan restarts, greedy back-offs,
possessive quantifiers, lookarounds, atomic groups). Stock behavior is the
fuzzer's soft classes `SKIP_RESTART_TAG_LEAK` / `SKIP_GROUPCURLY_STALE_WRITE`
(and the parity pins `RestartTagLeakParityTest` /
`GroupCurlyStaleWriteParityTest`, which keep asserting the leak on purpose
and run on the STOCK JDK — this patched base never touches them). The fix —
exact restore of the saved group positions when an attempt is undone — is
designed and verified in
[docs/jdk-8381696-restart-tag-leak-fix.md](../../docs/jdk-8381696-restart-tag-leak-fix.md);
the patched engine's spans equal our pinned engine spans on every case of
both pins.

## Layout

- Source of truth: `../archives/jdk26u-regex-cbb726ed98991a99983c372b2922d7d4f224271f.tar.gz`
  — the two files the patch touches (`java/util/regex/Pattern.java`,
  `Matcher.java`) from the [openjdk/jdk26u](https://github.com/openjdk/jdk26u)
  tag `jdk-26.0.2-ga` (commit `cbb726ed9899`), byte-identical to the
  `src.zip` of the JDK this repo tests on (Oracle 26.0.2+10). sha256 per the
  `.sha256` sidecar. No extracted tree is committed.
- `patches/` — one patch per fix, applied cumulatively in filename order:
  - `0001` — exact restore of capture-group positions when an attempt is
    undone. The upstream prototype is
    [`docs/patches/jdk-8381696-exact-restore-prototype.patch`](../../docs/patches/jdk-8381696-exact-restore-prototype.patch)
    (generated from an `openjdk/jdk` tree, base `d39a62afc66`, JDK 28 dev;
    kept as the upstreamable artifact). 26.0.2-ga is 12 diff lines away from
    that base in `Pattern.java` (a sharp-S fold change and a
    `jdk.internal.lang.CaseFolding` range hook; `Matcher.java` identical), so
    `0001` is the same change rebased onto the vendored base — round-trip
    exact (applies with zero fuzz, result byte-identical to the fuzz-applied
    prototype).
- `build-patched.sh` — verifies the archive checksum, extracts into a
  gitignored `.build/` scratch dir, applies patches, compiles the two files
  as a `java.base` override (`javac --patch-module`), and probes the result:
  `vendor/jdk-regex-jemmix/build-patched.sh` (no arguments; re2j's script
  takes a level, this one builds the single fix).
- `openjdk-regex-exact-restore/` — the compiled module dir, **gitignored**
  build output. Consumed by `--patch-module java.base=…` on the fuzz fork
  only — never on a classpath, never in the parity tests.

## JDK requirements

The sources are 26.0.2 (26-only APIs, e.g.
`jdk.internal.lang.CaseFolding`): building and RUNNING the patched oracle
requires JDK ≥ 26 — the same major the CI test lane and the local default
use. A JDK-25 runner cannot load the module (class-file version), which is
why `fuzz-nightly.yml` pins 26. The classes otherwise behave exactly as the
running JDK's own regex — all Unicode data (`Character`, `\p{…}` classes)
comes from the runtime, not from these two files.

## Use as the fuzz oracle

The patched build is the DEFAULT jur-lane oracle — plain invocations patch
the fuzz fork automatically (built via `:tests:parity:re2j:buildPatchedJurOracle`):

    ./gradlew :tests:parity:re2j:fuzz                     # jur lane = patched oracle
    ./gradlew :tests:parity:re2j:camp -Pcamp.mode=jur     # same for the campaign

To soak against the running JDK's stock (leaking) java.util.regex instead:

    ./gradlew :tests:parity:re2j:fuzz -Pfuzz.pristineJur=true ...

The fuzzer probes whether the live oracle leaks (the canonical
`(?:(\A))*(\n)` on `"a\n"` case is the discriminator) and gates its
leak-family soft classes on it: under the patched (or any fixed) oracle the
`SKIP_RESTART_TAG_LEAK` / `SKIP_GROUPCURLY_STALE_WRITE` classifications
cannot fire and every divergence is a hard finding — the same
probed-not-declared contract as the re2j `RELEASED_ORACLE` classifier.

## License

OpenJDK `java.util.regex` is Copyright Oracle, GPLv2+CE; this directory
(vanilla archive + derivative patch) is a derivative distributed under the
same terms — same standing as the upstream prototype it carries.
