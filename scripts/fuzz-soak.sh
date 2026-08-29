#!/usr/bin/env bash
# Overnight differential fuzz soak, chunked into short-lived JVMs.
#
#   scripts/fuzz-soak.sh [iterations=480] [minutes-per-iter=1] [out=build/fuzz] [seed-base=random] [extra gradle args...]
#
# Why chunked: a hang under the per-case watchdog sacrifices its worker thread
# (daemon, left running). A single 8h JVM leaks those threads — they steal CPU
# and heap until the run degrades or OOMs. One JVM per iteration bounds every
# leak to the chunk length; ~3-5 s of gradle+JIT overhead per restart is the
# price.
#
# iterations 2..N pass -Pfuzz.append=true: failures.ndjson / progress.log
# accumulate across chunks in one out dir (summary.txt always reflects only
# the latest chunk — the ndjson is the real artifact, every line reproducible
# via -Pfuzz.one=<caseSeed>). A nonzero fuzzer exit (findings found) is logged
# and does NOT stop the soak.
#
# Fuzz against our patched re2j fork (surrogate-pair-interior fix,
# vendor/re2j-jemmix/): append -Pfuzz.patchedOracle=true
#   scripts/fuzz-soak.sh 480 1 build/fuzz '' -Pfuzz.patchedOracle=true
set -u
cd "$(dirname "$0")/.."

ITERS=${1:-480}
MINS=${2:-1}
OUT=${3:-build/fuzz}
BASE=${4:-$((RANDOM * 32768 + RANDOM))}
shift 4 2>/dev/null || shift $#
EXTRA="$*"
# The fuzz JVM resolves a relative out dir against ITS working dir (the
# subproject), not the repo root — absolutize against where we run.
case "$OUT" in
    /*) ;;
    *) OUT="$PWD/$OUT" ;;
esac
LOG="$OUT/iterations.log"

mkdir -p "$OUT"
echo "$(date '+%F %T') soak start: iters=$ITERS mins=$MINS base-seed=$BASE out=$OUT extra=$EXTRA" >> "$LOG"
for i in $(seq 1 "$ITERS"); do
    seed=$((BASE + i))
    append=""
    [ "$i" -gt 1 ] && append="-Pfuzz.append=true"
    echo "$(date '+%F %T') iter $i/$ITERS seed $seed" >> "$LOG"
    if ./gradlew -q :tests:parity:re2j:fuzz -Pfuzz.minutes="$MINS" -Pfuzz.seed="$seed" \
            -Pfuzz.out="$OUT" $append $EXTRA >> "$LOG" 2>&1; then
        echo "$(date '+%F %T') iter $i/$ITERS ok" >> "$LOG"
    else
        echo "$(date '+%F %T') iter $i/$ITERS EXIT NONZERO — findings recorded (failures.ndjson)" >> "$LOG"
    fi
done
echo "$(date '+%F %T') soak done" >> "$LOG"
