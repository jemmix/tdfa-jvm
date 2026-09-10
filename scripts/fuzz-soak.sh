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
#
# The fuzzer is multi-threaded by default (-Dfuzz.threads, cores-1 capped
# at 8; case order and ndjson records are thread-count-invariant). Run N
# independent soaks ONLY if you want N separate out-dirs; one soak already
# uses the machine. Chunking stays: hang-sacrificed threads spin CPU until
# the chunk JVM dies.
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

# Post-soak hang verification: replay every recorded HANG caseSeed solo in a
# fresh JVM. The overnight-491362528 lesson: GC-stalled batches (humongous
# fragmentation -> Full GC pause + compaction steal late in a chunk) cross the
# 10s watchdog and get recorded as hangs although they replay in milliseconds;
# the new cpuMs/verdict fields in each record say so at recording time, this
# pass confirms it end-to-end. VERDICT lines land in $OUT/hang-replays.log.
if grep -q '"kind":"HANG_' "$OUT/failures.ndjson" 2>/dev/null; then
    REP="$OUT/hang-replays.log"
    : > "$REP"
    for s in $(grep '"kind":"HANG_' "$OUT/failures.ndjson" | sed 's/.*"caseSeed":\([0-9]*\).*/\1/' | sort -u); do
        [ -z "$s" ] && continue
        t0=$(date +%s)
        if timeout 120 ./gradlew -q :tests:parity:re2j:fuzz -Pfuzz.one="$s" \
                -Pfuzz.maxWork=8388608 -Pfuzz.out="$OUT-replays" >> "$LOG" 2>&1; then
            v="REPLAYS-CLEAN (environmental stall, not an engine spin)"
        else
            v="REPLAY NONZERO — triage by hand (see $OUT-replays + stack/cpuMs in the record)"
        fi
        echo "$(date '+%F %T') seed=$s wall=$(( $(date +%s) - t0 ))s $v" | tee -a "$REP"
    done
    echo "$(date '+%F %T') hang verification done: $(wc -l < "$REP" | tr -d ' ') record(s)" >> "$LOG"
fi
