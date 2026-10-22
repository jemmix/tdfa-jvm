#!/bin/sh
# TODO.md format gate: the file is the open-work contract —
#   - every item open (`- [ ]`): completed items are deleted on
#     landing (history lives in git), and
#   - one terse line per item, at most 10 words (whitespace-separated
#     tokens; a markdown link or code span counts as one). Detail
#     belongs in a linked doc, not inline prose.
#
#   ./scripts/check-todo.sh     # verify only — no fix mode; terse by hand
#
# Rides `./gradlew check` via the root `todoCheck` task, so the CI
# gate enforces the same rules.
set -eu

REPO=$(cd "$(dirname "$0")/.." && pwd)

status=0
awk '
    /^- \[/ {
        if ($0 ~ /^- \[[xX]\]/) {
            printf "TODO.md:%d: completed item — delete it, git keeps history\n    %s\n", NR, $0
            bad = 1
            next
        }
        text = $0
        sub(/^- \[ \][[:space:]]*/, "", text)
        n = split(text, w, " ")
        if (n > 10) {
            printf "TODO.md:%d: %d words (max 10) — move detail to a linked doc\n    %s\n", NR, n, $0
            bad = 1
        }
    }
    END { exit bad ? 1 : 0 }
' "$REPO/TODO.md" || status=1

if [ "$status" -ne 0 ]; then
    echo "todo: format violations (rules in the TODO.md header)" >&2
    exit 1
fi

echo "todo: clean"
