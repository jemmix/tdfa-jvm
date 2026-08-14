#!/bin/bash
# rebar-corpus 5-engine benchmark (RebarBench).
#
# Usage:
#   scripts/bench-rebar.sh fast|accurate [--filter substr] [--passes N] [--max-chars N]
#
# fast:     ~5-10 min triage signal (2M-char haystack cap, min of 2 passes)
# accurate: overnight (full haystacks, min of 5 passes)
#
# Requires the rebar benchmarks tree at build/vendor/rebar/pristine/rebar/benchmarks
# (materialized by :prepareVendor, same as the rebar parity suite).
set -euo pipefail
cd "$(dirname "$0")/.."

MODE="${1:-fast}"; shift || true

BENCH_DIR="build/vendor/rebar/pristine/rebar/benchmarks"
if [ ! -d "$BENCH_DIR/definitions" ]; then
  echo "rebar benchmarks not found at $BENCH_DIR — running :prepareVendor first" >&2
  ./gradlew :prepareVendor -q
fi

./gradlew :benchmarks:micro:compileJmhJava -q 2>&1 | grep -vE 'Hinweis|Wiederholen' || true

ASM=$(find ~/.gradle/caches/modules-2 -name 'asm-9.9.1.jar' | head -1)
ASMC=$(find ~/.gradle/caches/modules-2 -name 'asm-commons-9.9.1.jar' | head -1)
ASMT=$(find ~/.gradle/caches/modules-2 -name 'asm-tree-9.9.1.jar' | head -1)
ASMA=$(find ~/.gradle/caches/modules-2 -name 'asm-analysis-9.9.1.jar' | head -1)
ASMU=$(find ~/.gradle/caches/modules-2 -name 'asm-util-9.9.1.jar' | head -1)
RE2J=$(find ~/.gradle/caches/modules-2 -path '*com.google.re2j*' -name 're2j-1.8.jar' | head -1)
REGGIE=$(find ~/.gradle/caches/modules-2 -name 'java-reggie-f437ac8*.jar' | head -1)
TOMLJ=$(find ~/.gradle/caches/modules-2 -name 'tomlj-*.jar' ! -name '*sources*' ! -name '*javadoc*' | head -1)
ANTLR=$(find ~/.gradle/caches/modules-2 -name 'antlr4-runtime-*.jar' ! -name '*sources*' | head -1)

CP="benchmarks/micro/build/classes/java/jmh"
CP+=":build/classes/java/main"
CP+=":testlib/rebar/build/classes/java/main"
CP+=":$RE2J:$REGGIE:$TOMLJ:$ANTLR:$ASM:$ASMC:$ASMT:$ASMA:$ASMU"

exec java -Xmx8g -cp "$CP" io.github.jemmix.tdfa.bench.RebarBench "$MODE" --dir "$BENCH_DIR" "$@"
