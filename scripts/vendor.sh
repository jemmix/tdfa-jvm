#!/usr/bin/env bash
# scripts/vendor.sh
#
# Manage vendored third-party dependencies.
#
# Subcommands:
#   refresh <dep> [<sha>]   Fetch upstream at SHA (or default branch tip if omitted),
#                           tar+gzip into vendor/archives/, write .sha256 sidecar.
#   prepare                 Extract all vendor archives, copy files into
#                           build/generated/, apply patches. Idempotent.
#
# Layout:
#   vendor/archives/<dep>-<sha>.tar.gz        pristine upstream snapshot
#   vendor/archives/<dep>-<sha>.tar.gz.sha256 sha256 digest
#   vendor/patches/<dep>/NN-*.patch           patches applied in lexical order
#
# Output (gitignored):
#   build/vendor/<dep>/pristine/              extracted upstream tree
#   build/generated/sources/<dep>/java/       patched sources wired into sourceSets
#   build/generated/resources/<dep>/          generated resources
#
# Prerequisites: bash 3.2+, tar, gzip, shasum (or sha256sum), patch, git.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
VENDOR_DIR="$ROOT/vendor"
ARCHIVES_DIR="$VENDOR_DIR/archives"
PATCHES_DIR="$VENDOR_DIR/patches"
BUILD_DIR="$ROOT/build"

usage() {
    cat <<EOF
Usage:
  $0 refresh <dep> [<sha>]
  $0 prepare

Commands:
  refresh <dep> [<sha>]   Fetch upstream <dep> at <sha> (or default branch tip if
                          omitted), build vendor/archives/<dep>-<sha>.tar.gz.

  prepare                 Extract all archives, copy files per the in-script maps,
                          apply patches. Idempotent — safe to re-run.

Known deps: re2j, rebar
EOF
}

sha256sum_cmd() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$@"
    else
        shasum -a 256 "$@"
    fi
}

upstream_url_for() {
    case "$1" in
        re2j)  echo "https://github.com/google/re2j.git" ;;
        rebar) echo "https://github.com/BurntSushi/rebar.git" ;;
        *)     return 1 ;;
    esac
}

# Apply a dep's file copy map. Outputs files to $gen_src_dir / $gen_res_dir.
# Reads src/dst pairs from stdin: "<src-rel-path> <java|resources> <dst-rel-path>".
# Sources are relative to $1 (pristine top dir). Destinations under $2/$3.
apply_copy_map() {
    local pristine_dir="$1"
    local gen_src_dir="$2"
    local gen_res_dir="$3"
    local src_rel dst_kind dst_path src_path
    while read -r src_rel dst_kind dst_path; do
        [ -z "$src_rel" ] && continue
        src_path="$pristine_dir/$src_rel"
        if [ ! -f "$src_path" ]; then
            echo "error: expected file not found in pristine tree: $src_rel" >&2
            echo "       (upstream layout may have changed; refresh + update copy map)" >&2
            return 1
        fi
        case "$dst_kind" in
            java)
                mkdir -p "$gen_src_dir/$(dirname "$dst_path")"
                cp "$src_path" "$gen_src_dir/$dst_path"
                ;;
            resources)
                mkdir -p "$gen_res_dir/$(dirname "$dst_path")"
                cp "$src_path" "$gen_res_dir/$dst_path"
                ;;
            *)
                echo "error: unknown dst kind '$dst_kind'" >&2
                return 1
                ;;
        esac
    done
}

# Try to apply $2 (patch file) to $1 (target dir) with -p0. Forwards on
# success, reports "already applied" on reverse-apply success, returns
# non-zero otherwise. Idempotent: re-running `prepare` is safe.
apply_patch() {
    local target_dir="$1" patch_file="$2"
    if patch -d "$target_dir" -p0 --dry-run --forward -i "$patch_file" >/dev/null 2>&1; then
        patch -d "$target_dir" -p0 --forward --reject-file=/dev/null -i "$patch_file" >/dev/null
    elif patch -d "$target_dir" -p0 --dry-run -R -i "$patch_file" >/dev/null 2>&1; then
        echo "      (already applied at $target_dir, skipping)"
    else
        return 1
    fi
}

# Emit the copy map for a dep on stdout (one entry per line, space-separated).
copy_map_for() {
    case "$1" in
        re2j)
            cat <<'MAP'
re2j/java/com/google/re2j/Utils.java java io/github/jemmix/tdfa/re2j/Utils.java
re2j/java/com/google/re2j/Unicode.java java io/github/jemmix/tdfa/re2j/Unicode.java
re2j/java/com/google/re2j/UnicodeTables.java java io/github/jemmix/tdfa/re2j/UnicodeTables.java
re2j/java/com/google/re2j/Characters.java java io/github/jemmix/tdfa/re2j/Characters.java
re2j/javatests/com/google/re2j/ExecTest.java java io/github/jemmix/tdfa/re2j/ExecTest.java
re2j/javatests/com/google/re2j/Strconv.java java io/github/jemmix/tdfa/re2j/Strconv.java
re2j/javatests/com/google/re2j/UNIXBufferedReader.java java io/github/jemmix/tdfa/re2j/UNIXBufferedReader.java
re2j/testdata/re2-search.txt resources re2-search.txt
MAP
            ;;
        rebar)
            # Filled in when rebar integration lands.
            :
            ;;
        *)
            echo "warning: no copy map defined for $dep (skipping file copy)" >&2
            ;;
    esac
}

# Find the archive for a dep. Errors if multiple found (ambiguous).
archive_for() {
    local dep="$1"
    local matches
    matches=$(ls -1 "$ARCHIVES_DIR/${dep}-"*.tar.gz 2>/dev/null || true)
    local count
    count=$(echo "$matches" | grep -c . || true)
    if [ "$count" = "0" ]; then
        echo "error: no archive found for $dep in $ARCHIVES_DIR/" >&2
        echo "run: $0 refresh $dep <sha>" >&2
        return 1
    fi
    if [ "$count" -gt 1 ]; then
        echo "error: multiple archives found for $dep (expected exactly one):" >&2
        echo "$matches" >&2
        return 1
    fi
    echo "$matches"
}

do_refresh() {
    local dep="${1:-}"
    local sha="${2:-HEAD}"
    if [ -z "$dep" ]; then
        usage
        echo "error: refresh requires <dep>" >&2
        return 1
    fi
    local url
    url=$(upstream_url_for "$dep") || {
        echo "error: unknown dep '$dep'. Add to upstream_url_for() in $0." >&2
        return 1
    }

    local tmp
    tmp=$(mktemp -d)
    trap 'rm -rf "$tmp"' RETURN

    echo "==> Fetching $dep @ $sha"
    git clone --quiet --no-checkout "$url" "$tmp/repo"
    (cd "$tmp/repo" && git checkout --quiet "$sha")
    local resolved_sha resolved_date
    resolved_sha=$(cd "$tmp/repo" && git rev-parse HEAD)
    resolved_date=$(cd "$tmp/repo" && git log -1 --format=%cI)
    echo "    resolved: ${resolved_sha:0:12} ($resolved_date)"

    # Build pristine snapshot (no .git) using git archive.
    mkdir -p "$tmp/snapshot/$dep"
    (cd "$tmp/repo" && git archive --format=tar HEAD) | tar -x -C "$tmp/snapshot/$dep"

    # Remove any prior archive for this dep (one-archive-per-dep invariant).
    rm -f "$ARCHIVES_DIR/${dep}-"*.tar.gz "$ARCHIVES_DIR/${dep}-"*.tar.gz.sha256

    local archive="$ARCHIVES_DIR/${dep}-${resolved_sha}.tar.gz"
    tar -czf "$archive" -C "$tmp/snapshot" "$dep"
    sha256sum_cmd "$archive" | awk -v f="$archive" '{print $1"  "f}' > "$archive.sha256"

    echo "==> Wrote $archive ($(du -h "$archive" | cut -f1))"
    echo "==> Wrote $archive.sha256"
    echo
    echo "Next: if patches need updating, regenerate against the new tree:"
    echo "  diff -ruN <pristine-at-dest> <patched-at-dest> > vendor/patches/$dep/NN-name.patch"
    echo "Then: $0 prepare"
}

do_prepare_dep() {
    local dep="$1"
    local archive
    archive=$(archive_for "$dep") || return 1

    local sha256_file="${archive}.sha256"
    if [ ! -f "$sha256_file" ]; then
        echo "error: missing $sha256_file" >&2
        return 1
    fi

    # Verify checksum.
    local expected actual
    expected=$(awk '{print $1}' "$sha256_file")
    actual=$(sha256sum_cmd "$archive" | awk '{print $1}')
    if [ "$expected" != "$actual" ]; then
        echo "error: checksum mismatch for $archive" >&2
        echo "    expected: $expected" >&2
        echo "    actual:   $actual" >&2
        return 1
    fi

    # Extract pristine.
    local pristine_dir="$BUILD_DIR/vendor/$dep/pristine"
    rm -rf "$pristine_dir"
    mkdir -p "$pristine_dir"
    tar -xzf "$archive" -C "$pristine_dir"

    # Copy files per the dep's map.
    local gen_src_dir="$BUILD_DIR/generated/sources/$dep/java"
    local gen_res_dir="$BUILD_DIR/generated/resources/$dep"
    rm -rf "$gen_src_dir" "$gen_res_dir"
    mkdir -p "$gen_src_dir" "$gen_res_dir"

    # The pristine tarball contains a top-level dir matching $dep.
    # apply_copy_map reads from $pristine_dir (which contains $dep/...).
    copy_map_for "$dep" | apply_copy_map "$pristine_dir" "$gen_src_dir" "$gen_res_dir"

    # Apply patches in lexical order. Each patch is tried against
    # $gen_src_dir first (e.g. re2j's package-rewrite patch targets
    # generated Java sources), then against $pristine_dir/$dep (e.g. rebar
    # patches that modify the upstream benchmarks/ corpus in-place — the
    # parity test reads scenarios from the pristine extract directly).
    local patch_dir="$PATCHES_DIR/$dep"
    if [ -d "$patch_dir" ]; then
        local patches=()
        local p
        for p in "$patch_dir"/*.patch; do
            [ -f "$p" ] || continue
            patches+=("$p")
        done
        if [ ${#patches[@]} -gt 0 ]; then
            echo "==> $dep: applying ${#patches[@]} patch(es)"
            for p in "${patches[@]}"; do
                echo "    $(basename "$p")"
                if apply_patch "$gen_src_dir" "$p"; then
                    :
                elif apply_patch "$pristine_dir/$dep" "$p"; then
                    :
                else
                    echo "error: patch does not apply cleanly: $p" >&2
                    echo "       tried: $gen_src_dir and $pristine_dir/$dep" >&2
                    echo "       regenerate against current pristine tree and retry" >&2
                    return 1
                fi
            done
        fi
    fi

    echo "==> $dep: prepared at $gen_src_dir (+$gen_res_dir)"
}

do_prepare() {
    # Discover all deps from archive filenames (filename pattern: <dep>-<sha>.tar.gz).
    local deps=()
    local f base dep
    for f in "$ARCHIVES_DIR"/*.tar.gz; do
        [ -f "$f" ] || continue
        base=$(basename "$f" .tar.gz)
        dep="${base%-*}"
        local found=0
        local d
        for d in "${deps[@]:-}"; do
            [ "$d" = "$dep" ] && { found=1; break; }
        done
        [ "$found" = "0" ] && deps+=("$dep")
    done

    if [ ${#deps[@]} -eq 0 ]; then
        echo "no vendored deps found in $ARCHIVES_DIR/" >&2
        return 0
    fi

    for dep in "${deps[@]}"; do
        do_prepare_dep "$dep"
    done
}

main() {
    local cmd="${1:-}"
    shift || true
    case "$cmd" in
        refresh) do_refresh "$@" ;;
        prepare) do_prepare "$@" ;;
        ""|-h|--help|help) usage ;;
        *) echo "unknown command: $cmd" >&2; usage; return 1 ;;
    esac
}

main "$@"
