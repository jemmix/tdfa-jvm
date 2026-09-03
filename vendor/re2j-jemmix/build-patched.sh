#!/usr/bin/env bash
# Rebuild the patched re2j oracle jars from the single committed pristine
# source (../archives/re2j-<sha>.tar.gz, upstream tag re2j-1.8 =
# 57278921a609461c14d9cdb057d7aa9511c8f7ac) plus one patch per fix,
# applied in order from patches/.
#
#   vendor/re2j-jemmix/build-patched.sh          # fix1+fix2 (default)
#   vendor/re2j-jemmix/build-patched.sh 1        # only fix1 (patch 0001)
#   vendor/re2j-jemmix/build-patched.sh 2        # fix1+fix2
#
# Compiles java/ only (javatests/ is not shipped). Pure tar+patch+javac+jar,
# no build system needed. Writes re2j-1.8-jemmix-fix{1,2}.jar into this
# directory — build outputs, gitignored (never committed).
set -euo pipefail
cd "$(dirname "$0")"

level="${1:-2}"
case "$level" in 1|2) ;; *) echo "usage: $0 [1|2]" >&2; exit 2 ;; esac

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

work=".build"
rm -rf "$work"
mkdir -p "$work/src" "$work/classes"
# Tarball top-level dir is re2j/ — strip it.
tar -xzf "$archive" -C "$work/src" --strip-components=1

count=0
for p in patches/*.patch; do
  count=$((count + 1))
  if [ "$count" -gt "$level" ]; then break; fi
  echo "applying $(basename "$p")"
  (cd "$work/src" && patch -p1 --silent < "../../$p")
done

# -/super/ is GWT super-source (overrides for GWT builds), not javac source
find "$work/src/java" -name '*.java' -not -path '*/super/*' > "$work/sources.txt"
javac --release 8 -nowarn -d "$work/classes" @"$work/sources.txt"
manifest="$work/MANIFEST.MF"
cat > "$manifest" <<'M'
Implementation-Title: re2j-jemmix
Implementation-Version: 1.8-jemmix-fixLEVEL
M
sed -i '' "s/fixLEVEL/fix${level}/" "$manifest"
jar cfm "re2j-1.8-jemmix-fix${level}.jar" "$manifest" -C "$work/classes" .
rm -rf "$work"
echo "wrote re2j-1.8-jemmix-fix${level}.jar ($(wc -c < "re2j-1.8-jemmix-fix${level}.jar" | tr -d ' ') bytes)"
