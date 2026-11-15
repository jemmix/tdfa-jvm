#!/bin/sh
# Repo-hygiene gate: scratch/agent droppings never land in the tree.
#   - the repo root is an allowlist — a new top-level entry is a
#     decision, not an accident. One-off probes and output dumps
#     belong in benchmarks/, docs/, or outside the repo entirely
#     (see WideScanProbe.java, wide-scan-probe-*.txt, dump/ — all
#     once committed by an agent, all since removed)
#   - no backup droppings: `*-E` files are the BSD sed pitfall
#     (`sed -i -E` treats -E as the backup suffix; on macOS write
#     `sed -i '' -E`), plus *.orig/*.rej/*.bak/*~/.DS_Store litter
#   - no compiled *.class loose in the tree (build output lives under
#     build/, which is ignored) and no JVM crash dumps (hs_err_pid*,
#     replay_pid*, *.hprof)
#
#   ./scripts/check-repo-hygiene.sh     # verify only — no fix mode; delete by hand
#
# Checks tracked files (so a fresh CI checkout catches committed trash)
# and, run locally, untracked-but-not-ignored leftovers too. Rides
# `./gradlew check` via the root `repoHygieneCheck` task, so the CI
# gate enforces the same rules.
set -eu

REPO=$(cd "$(dirname "$0")/.." && pwd)
cd "$REPO"

# Tracked top-level entries, sorted. Adding a root entry? That's a
# deliberate act — extend this list in the same PR.
root_ok() {
    case "$1" in
        .github|.gitignore|asm|benchmarks|BENCHMARKS.md|build.gradle|ci|config|CONTRIBUTING.md|core|docs|gradle|gradle.properties|gradlew|gradlew.bat|lib|LICENSE|qodana-ci.yaml|qodana.yaml|README.md|re2j|scripts|settings.gradle|src|testlib|tests|TODO.md|unicode|vendor|website) return 0 ;;
        *) return 1 ;;
    esac
}

# A *-E file whose un-suffixed sibling exists (tracked or on disk) is a
# sed/cp backup; any *.java-E is one outright — no Java source name can
# legitimately end in -E.
is_backup() {
    base=${1%-E}
    [ "$base" != "$1" ] || return 1
    [ -e "$base" ] && return 0
    case "$1" in *.java-E) return 0 ;; esac
    return 1
}

report() {
    printf 'hygiene: %s: %s\n' "$1" "$2"
}

# One report line per untracked dropping (case stays OUTSIDE the
# command substitution below — bash 3.2 (macOS /bin/sh) ends a $()
# at the first case-arm `)` it sees).
report_untracked() {
    case "$1" in
        *-E) is_backup "$1" && report "$1" 'untracked backup droppings — delete it' ;;
        *) report "$1" 'untracked droppings — delete or ignore it' ;;
    esac
}

violations=$(
    {
        # root allowlist — tracked top-level entries only
        for top in $(git ls-files | LC_ALL=C cut -d/ -f1 | LC_ALL=C sort -u); do
            root_ok "$top" || report "$top" 'unexpected repo-root entry — one-off probes/output belong in benchmarks/, docs/, or outside the repo (allowlist: scripts/check-repo-hygiene.sh)'
        done

        # tracked backup/compiled/crash droppings
        git ls-files | grep -E '(\.class|\.orig|\.rej|\.bak|\.hprof|~|\.DS_Store)$|^hs_err_pid|^replay_pid' \
            | while IFS= read -r f; do
                report "$f" 'backup/compiled/crash droppings — delete it'
            done

        # tracked sed/cp backup droppings (*-E next to their source)
        git ls-files | grep -e '-E$' \
            | while IFS= read -r f; do
                is_backup "$f" && report "$f" "backup droppings next to ${f%-E} — the BSD sed -i -E pitfall; delete it"
            done

        # untracked leftovers (local only; a fresh CI checkout has none)
        git ls-files --others --exclude-standard \
            | grep -E '(\.orig|\.rej|\.bak|\.hprof|~|\.DS_Store|\.class)$|-E$|^hs_err_pid|^replay_pid' \
            | while IFS= read -r f; do
                report_untracked "$f"
            done
    } | LC_ALL=C sort -u
)

if [ -n "$violations" ]; then
    printf '%s\n' "$violations"
    echo "repo: $(printf '%s\n' "$violations" | grep -c .) hygiene violation(s) (rules in scripts/check-repo-hygiene.sh)" >&2
    exit 1
fi

echo "repo: clean"
