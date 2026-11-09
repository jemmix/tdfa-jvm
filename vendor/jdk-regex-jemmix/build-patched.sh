#!/usr/bin/env bash
# Rebuild the patched java.util.regex oracle module from the single committed
# pristine source (../archives/jdk26u-regex-<sha>.tar.gz, openjdk/jdk26u tag
# jdk-26.0.2-ga) plus the patches in patches/, applied in order.
#
#   vendor/jdk-regex-jemmix/build-patched.sh
#
# Compiles the two touched files as a java.base override (javac
# --patch-module) into openjdk-regex-exact-restore/ — a gitignored build
# output consumed by --patch-module on the fuzz fork ONLY (never a classpath,
# never the parity tests). Requires JDK >= 26 (the vendored sources use
# 26-only APIs). Ends with a semantics probe: the canonical JDK-8381696
# restart-leak case must come back unset, or the build fails.
set -euo pipefail
cd "$(dirname "$0")"

archive="$(ls ../archives/jdk26u-regex-*.tar.gz)"
if [ "$(printf '%s\n' "$archive" | wc -l)" -ne 1 ]; then
  echo "error: expected exactly one jdk26u-regex archive in ../archives/" >&2; exit 1
fi
if [ -f "$archive.sha256" ]; then
  if command -v sha256sum >/dev/null 2>&1; then actual=$(sha256sum "$archive" | awk '{print $1}')
  else actual=$(shasum -a 256 "$archive" | awk '{print $1}'); fi
  expected=$(awk '{print $1}' "$archive.sha256")
  if [ "$actual" != "$expected" ]; then
    echo "error: checksum mismatch for $archive" >&2; exit 1
  fi
fi

jdk_home="${JAVA_HOME:-}"
if [ -z "$jdk_home" ]; then
  jdk_home=$(java -XshowSettings:properties -version 2>&1 \
    | awk -F'= ' '/^[ +]*java\.home/{print $2; exit}')
fi
[ -n "$jdk_home" ] && [ -d "$jdk_home" ] \
  || { echo "error: no JDK home (set JAVA_HOME)" >&2; exit 1; }
# 26-only APIs in the vendored sources; a lower javac fails with confusing
# errors and lower-compiled classes would not load — fail with the reason.
jdk_ver=$("$jdk_home/bin/java" -version 2>&1 | head -1)
case "$jdk_ver" in
  *version\ \"2[6-9]\.*|*version\ \"[3-9][0-9]\.*) ;;
  *) echo "error: JDK >= 26 required to build the patched oracle, found: $jdk_ver" >&2; exit 1 ;;
esac

work=".build"
rm -rf "$work" "$PWD/openjdk-regex-exact-restore"
mkdir -p "$work/src" "$work/classes"
tar -xzf "$archive" -C "$work"
# AppleDouble guard (bsdtar-stamped xattrs become ._ files under GNU tar)
find "$work/src" -name '._*' -delete

for p in patches/*.patch; do
  echo "applying $(basename "$p")"
  (cd "$work" && patch -p1 --silent < "../$p")
done

"$jdk_home/bin/javac" --patch-module "java.base=$work/src" -d "$work/classes" \
  "$work"/src/java.base/share/classes/java/util/regex/*.java

# Semantics probe, not just a compile check: the canonical restart-tag-leak
# case must come back unset (single-file source launch, nothing written
# outside the build dir).
cat > "$work/Probe.java" <<'EOF'
public class Probe {
    public static void main(String[] a) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?:(\\A))*(\\n)").matcher("a\n");
        if (!m.find()) throw new AssertionError("probe: no match");
        if (m.group(1) != null) {
            System.out.println("probe: g1 LEAKED — patch not in effect");
            System.exit(1);
        }
        System.out.println("probe: g1 unset — patched engine active");
    }
}
EOF
"$jdk_home/bin/java" --patch-module "java.base=$work/classes" "$work/Probe.java"

mv "$work/classes" openjdk-regex-exact-restore
rm -rf "$work"
echo "wrote vendor/jdk-regex-jemmix/openjdk-regex-exact-restore ($(find openjdk-regex-exact-restore -name '*.class' | wc -l | tr -d ' ') classes)"
