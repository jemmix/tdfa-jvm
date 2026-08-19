#!/usr/bin/env bash
# Benchmark regression gate.
#
#   ./scripts/bench-regression.sh                # QUICK harness (~15 s) + compare (fails on regression)
#   ./scripts/bench-regression.sh --capture      # QUICK harness, (re)write this machine's quick baseline
#   ./scripts/bench-regression.sh --jmh          # full JMH RegressionBench (~5-10 min) + compare
#   ./scripts/bench-regression.sh --jmh --capture
#
# Baselines are per-machine under benchmarks/baselines/ (quick: <host>-quick.json,
# jmh: <host>.json). Re-capture after intentional performance changes and commit
# the new file. Never compare a quick baseline against a JMH run (or vice versa).
set -euo pipefail
cd "$(dirname "$0")/.."

HOST="$(uname -s)-$(uname -m)-$(scutil --get ComputerName 2>/dev/null || hostname | cut -d. -f1 | tr -d ' ')"
QUICK=1
CAPTURE=0
for a in "$@"; do
    case "$a" in
        --jmh) QUICK=0 ;;
        --capture) CAPTURE=1 ;;
    esac
done

if [[ $QUICK == 1 ]]; then
    BASELINE="benchmarks/baselines/${HOST}-quick.json"
    RESULTS="benchmarks/micro/build/reports/jmh/quick.json"
    ASM_JAR="$(find ~/.gradle/caches/modules-2 -name 'asm-9.9.1.jar' | head -1)"
    ./gradlew :benchmarks:micro:compileJmhJava -q
    # Module-restructure layout: QuickBench exercises the facade (root) plus the
    # core interpreter and the ASM backend's generated-code path — all three
    # class dirs must be on the runtime classpath.
    java -cp "benchmarks/micro/build/classes/java/jmh:build/classes/java/main:core/build/classes/java/main:asm/build/classes/java/main:$ASM_JAR" \
        io.github.jemmix.tdfa.bench.QuickBench "$RESULTS" 1>&2
    THRESHOLD=0.15   # quick harness: min-of-3 keeps noise low, but allow a bit more than JMH
else
    BASELINE="benchmarks/baselines/${HOST}.json"
    RESULTS="benchmarks/micro/build/reports/jmh/regression.json"
    ./gradlew :benchmarks:micro:jmh -PjmhInclude='io.github.jemmix.tdfa.bench.RegressionBench' --rerun-tasks -q
    cp benchmarks/micro/build/reports/jmh/results.json "$RESULTS"
    THRESHOLD=0.10
fi

if [[ $CAPTURE == 1 ]]; then
    mkdir -p benchmarks/baselines
    cp "$RESULTS" "$BASELINE"
    echo "baseline captured: $BASELINE"
    exit 0
fi
if [[ ! -f "$BASELINE" ]]; then
    echo "no baseline for this machine ($BASELINE) — capturing one now"
    mkdir -p benchmarks/baselines
    cp "$RESULTS" "$BASELINE"
    echo "baseline captured: $BASELINE"
    exit 0
fi
python3 scripts/bench-compare.py "$BASELINE" "$RESULTS" --threshold "$THRESHOLD"
