#!/usr/bin/env bash
# Build the pristine re2j oracle jar from the single committed upstream
# snapshot (../archives/re2j-<sha>.tar.gz) — exactly as upstream ships it,
# no patches.
#
#   vendor/re2j-oracle/build-oracle.sh <jspecify.jar>
#
# <jspecify.jar> is org.jspecify:jspecify, the coordinates upstream's
# build.gradle declares: the snapshot's API sources carry JSpecify nullness
# annotations, so javac needs them on its classpath. Gradle resolves the jar
# and passes its path (:tests:parity:re2j:buildOracle).
#
# Compiles java/ only (javatests/ is not shipped). Pure tar+javac+jar, no
# build system needed. Writes re2j-pristine.jar into this directory — a
# build output, gitignored (never committed). Its manifest carries the
# snapshot's commit SHA (Implementation-Version): the provenance the fuzz
# summary reports and the oracle-identity tripwire pins.
set -euo pipefail
cd "$(dirname "$0")"

if [ "$#" -ne 1 ] || [ ! -f "$1" ]; then
  echo "usage: $0 <path to org.jspecify:jspecify jar>" >&2; exit 2
fi

archive="$(ls ../archives/re2j-*.tar.gz)"
if [ "$(printf '%s\n' "$archive" | wc -l)" -ne 1 ]; then
  echo "error: expected exactly one re2j archive in ../archives/" >&2; exit 1
fi
if [ -f "$archive.sha256" ]; then
  if command -v sha256sum >/dev/null 2>&1; then actual=$(sha256sum "$archive" | awk '{print $1}')
  else actual=$(shasum -a 256 "$archive" | awk '{print $1}'); fi
  expected=$(awk '{print $1}' "$archive.sha256")
  if [ "$actual" != "$expected" ]; then
    echo "error: checksum mismatch for $archive" >&2; exit 1
  fi
fi
sha="$(basename "$archive")"
sha="${sha#re2j-}"
sha="${sha%.tar.gz}"

work=".build"
rm -rf "$work"
mkdir -p "$work/src" "$work/classes"
# Tarball top-level dir is re2j/ — strip it.
tar -xzf "$archive" -C "$work/src" --strip-components=1

# -/super/ is GWT super-source (overrides for GWT builds), not javac source
find "$work/src/java" -name '*.java' -not -path '*/super/*' > "$work/sources.txt"
javac --release 8 -nowarn -cp "$1" -d "$work/classes" @"$work/sources.txt"
# Unquoted heredoc expands ${sha} (portable: no sed -i, whose backup-suffix
# argument is spelled differently by BSD and GNU).
cat > "$work/MANIFEST.MF" <<M
Implementation-Title: re2j
Implementation-Version: ${sha}
M
jar cfm "re2j-pristine.jar" "$work/MANIFEST.MF" -C "$work/classes" .
rm -rf "$work"
echo "wrote re2j-pristine.jar (re2j @ ${sha}, $(wc -c < re2j-pristine.jar | tr -d ' ') bytes)"
