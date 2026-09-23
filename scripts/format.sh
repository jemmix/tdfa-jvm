#!/bin/sh
# Codestyle reformatter: applies config/codestyle/ (eclipse formatter via
# spotless + import layout), then reports what still needs HAND fixes —
# checkstyle enforces the rules no formatter can rewrite (mandatory braces,
# no star imports, member order).
#
#   ./scripts/format.sh            # format + report manual leftovers
#   ./scripts/format.sh --check    # CI mode: verify only, change nothing
#
# CI equivalence: `./gradlew check` runs spotlessCheck + checkstyle* as part
# of the gate. IDE equivalence: import config/codestyle/tdfa-idea.xml.
set -eu

REPO=$(cd "$(dirname "$0")/.." && pwd)
cd "$REPO"

if [ "${1:-}" = "--check" ]; then
    ./gradlew spotlessCheck checkstyleMain checkstyleTest checkstyleJmh
    echo "codestyle: clean"
    exit 0
fi

./gradlew spotlessApply

# Non-fatal advisory: list members/braces the formatter cannot fix. Empty
# output means the tree is fully codestyle-clean.
if ./gradlew -q checkstyleMain checkstyleTest checkstyleJmh 2>/dev/null; then
    echo "codestyle: clean (formatter covered everything)"
else
    echo "codestyle: manual fixes still required (braces / star imports /"
    echo "member order) — see the checkstyle report above, then re-run."
    exit 1
fi
