# Vendor

Pristine snapshots of third-party dependencies, committed as compressed
tarballs. Decoupled from upstream availability and reproducible bit-for-bit
across clones.

## Layout

```
vendor/
├── archives/
│   ├── <dep>-<full-sha>.tar.gz          pristine upstream snapshot
│   └── <dep>-<full-sha>.tar.gz.sha256   sha256 digest
└── patches/
    └── <dep>/
        └── NN-name.patch                patches applied in lexical order
```

One archive per dep. The current vendored SHA is the SHA embedded in the
archive filename. Upgrades replace the old archive (one-for-one).

## Operations

### Prepare all vendored deps (called by Gradle)

```bash
./scripts/vendor.sh prepare
```

Extracts each `vendor/archives/<dep>-*.tar.gz`, copies specific files into
`build/generated/{sources,resources}/<dep>/`, and applies any patches in
`vendor/patches/<dep>/`. Idempotent — safe to re-run any time.

Gradle wires this as the `:prepareVendor` task; `:tests:parity:re2j-suite:
compileTestJava` depends on it. IntelliJ users should run `./gradlew
prepareVendor` once before the initial Gradle sync so generated sources
appear in the IDE.

### Add or upgrade a vendored dep

```bash
./scripts/vendor.sh refresh <dep> [<sha>]
```

Fetches `<dep>` from its upstream URL at `<sha>` (or branch tip if omitted),
writes a fresh tarball + sha256 sidecar. Removes any prior archive for the
dep (one-for-one replacement). Does NOT update patches — if upstream
changed line numbers near a patched region, the patch may fail to apply
on the next `prepare`.

To add a new dep:
1. Add an entry to `upstream_url_for()` and `copy_map_for()` in
   `scripts/vendor.sh`.
2. Run `./scripts/vendor.sh refresh <newdep> <sha>`.
3. If patches are needed, generate them:
   ```bash
   diff -ruN <pristine-at-dest-layout> <patched-at-dest-layout> \
       > vendor/patches/<newdep>/01-name.patch
   ```
4. Run `./scripts/vendor.sh prepare` to verify.

## Prerequisites

- `tar`, `gzip`, `patch`, `git` (all standard on macOS/Linux).
- `shasum` (macOS) or `sha256sum` (Linux).

## License preservation

Each archive contains the upstream `LICENSE`/`NOTICE` files verbatim — the
full pristine tree is snapshotted. Consult the archive contents for
attribution. Vendored code remains under its upstream license (re2j: BSD-3;
rebar: MIT/Unlicense — TBD).
