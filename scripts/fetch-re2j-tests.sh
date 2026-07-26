#!/usr/bin/env bash
# scripts/fetch-re2j-tests.sh
#
# Pin and refresh re2j's test corpus + Java test sources against our engine.
#
# What this script does:
#   1. Shallow-clone google/re2j at the pinned release tag.
#   2. Record commit hash + date to src/test/resources/re2j-commit.txt
#      so the exact re2j version under test is in our repo history.
#   3. Refresh testdata/re2-exhaustive.txt.gz and the Fowler .dat files.
#   4. Copy re2j's Java test files (Strconv, UNIXBufferedReader, ExecTest, etc.)
#      into src/test/java/io/github/jemmix/tdfa/re2j/, sed-rewriting the
#      package declaration and any in-package imports from
#      com.google.re2j → io.github.jemmix.tdfa.re2j. External imports
#      (Guava, JUnit, java.*) are left untouched.
#
# The result: re2j's test classes reference our io.github.jemmix.tdfa.re2j
# shim (RE2, PatternSyntaxException) which delegates to our TDFA engine.
# When re2j ships a new release, re-running this script refreshes both the
# test corpus and the test code, picking up any new cases.
#
# Usage:
#   scripts/fetch-re2j-tests.sh            # use RE2J_VERSION from env or default
#   scripts/fetch-re2j-tests.sh 1.8        # pin a specific tag
#
# Brittleness note: the sed is one-line (package rename). If re2j refactors
# its test code to depend on new internal helpers, we may need to add files
# to RE2J_TEST_SOURCES below.

set -euo pipefail

VERSION="${1:-${RE2J_VERSION:-1.8}}"
# re2j's git tags are prefixed with 're2j-'; the Maven artifact is just the version.
TAG="re2j-$VERSION"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
TMPDIR="$(mktemp -d)"
trap 'rm -rf "$TMPDIR"' EXIT

# Test source files (relative to javatests/com/google/re2j/) to copy + sed.
# Add to this list when re2j's tests grow new internal helpers.
RE2J_TEST_SOURCES=(
    Strconv.java
    UNIXBufferedReader.java
    ExecTest.java
)

# Main source files (relative to java/com/google/re2j/) needed as test helpers.
# These are package-private utilities that the test files above depend on.
RE2J_MAIN_HELPERS=(
    Utils.java
    Unicode.java
    UnicodeTables.java
    Characters.java
)

echo "==> Cloning google/re2j@$TAG"
git clone --depth 1 --branch "$TAG" https://github.com/google/re2j.git "$TMPDIR/re2j"

cd "$TMPDIR/re2j"
COMMIT=$(git rev-parse HEAD)
SHORT=$(git rev-parse --short HEAD)
DATE=$(git log -1 --format=%cI)
echo "==> re2j $VERSION @ $SHORT ($DATE)"

# --- 1. Record provenance --------------------------------------------------
cat > "$ROOT/src/test/resources/re2j-commit.txt" <<EOF
# Source of test corpus + Java test sources used by Re2jCompat tests.
# Refreshed by scripts/fetch-re2j-tests.sh
re2j-version: $VERSION
re2j-commit:  $COMMIT
re2j-date:    $DATE
source:       https://github.com/google/re2j
EOF
echo "==> Wrote src/test/resources/re2j-commit.txt"

# --- 2. Refresh testdata ---------------------------------------------------
mkdir -p "$ROOT/src/test/resources"
cp testdata/re2-exhaustive.txt.gz "$ROOT/src/test/resources/"
cp testdata/re2-search.txt      "$ROOT/src/test/resources/"
for dat in basic.dat nullsubexpr.dat repetition.dat; do
    if [ -f "testdata/$dat" ]; then
        cp "testdata/$dat" "$ROOT/src/test/resources/"
    fi
done
echo "==> Copied testdata (re2-exhaustive.txt.gz + re2-search.txt + Fowler .dat files)"

# --- 3. Copy + sed Java test sources --------------------------------------
SRC_DIR="javatests/com/google/re2j"
MAIN_SRC_DIR="java/com/google/re2j"
DST_DIR="$ROOT/src/test/java/io/github/jemmix/tdfa/re2j"
mkdir -p "$DST_DIR"

# Helper: sed com.google.re2j → io.github.jemmix.tdfa.re2j (package + same-package imports).
sed_repkg() {
    sed -E \
        -e 's|^package com\.google\.re2j;|package io.github.jemmix.tdfa.re2j;|' \
        -e 's|^import com\.google\.re2j\.|import io.github.jemmix.tdfa.re2j.|g' \
        "$1"
}

for src in "${RE2J_TEST_SOURCES[@]}"; do
    src_path="$SRC_DIR/$src"
    if [ ! -f "$src_path" ]; then
        echo "!! Test source not found: $src_path (re2j may have renamed it)" >&2
        exit 1
    fi
    sed_repkg "$src_path" > "$DST_DIR/$src"
    echo "==> Copied test: $src"
done

for src in "${RE2J_MAIN_HELPERS[@]}"; do
    src_path="$MAIN_SRC_DIR/$src"
    if [ ! -f "$src_path" ]; then
        echo "!! Helper source not found: $src_path" >&2
        exit 1
    fi
    sed_repkg "$src_path" > "$DST_DIR/$src"
    echo "==> Copied helper: $src"
done

cat <<EOF

Done. re2j $VERSION @ $SHORT.

Test files are in src/test/java/io/github/jemmix/tdfa/re2j/.
They run against our io.github.jemmix.tdfa.re2j.RE2 shim.

Re-run with: ./gradlew test --tests 'io.github.jemmix.tdfa.re2j.*'
EOF
