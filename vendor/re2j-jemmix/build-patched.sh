#!/usr/bin/env bash
# Rebuild the patched re2j oracle jars from the vendored pristine source
# (re2j-1.8/, upstream tag re2j-1.8 = 57278921a609461c14d9cdb057d7aa9511c8f7ac)
# plus one patch per fix, applied in order from patches/.
#
#   vendor/re2j-jemmix/build-patched.sh          # rebuild both jars in place
#   vendor/re2j-jemmix/build-patched.sh 1        # only fix1 (patch 0001)
#   vendor/re2j-jemmix/build-patched.sh 2        # fix1+fix2 (default)
#
# Compiles java/ only (javatests/ is not shipped). Pure javac+jar, no build
# system needed. Overwrites re2j-1.8-jemmix-fix{1,2}.jar in this directory.
set -euo pipefail
cd "$(dirname "$0")"

level="${1:-2}"
case "$level" in 1|2) ;; *) echo "usage: $0 [1|2]" >&2; exit 2 ;; esac

work=".build"
rm -rf "$work"
mkdir -p "$work/src" "$work/classes"
cp -R re2j-1.8/. "$work/src/"

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
