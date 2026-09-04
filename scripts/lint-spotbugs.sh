#!/bin/sh
# Standalone SpotBugs invocation over the three library modules (facade,
# core, asm). Deliberately decoupled from Gradle so the analysis survives
# build-tool churn (decade-stability: SpotBugs is a plain-Java CLI over
# compiled classfiles).
#
# WHEN TO USE: the Gradle-integrated spotbugs tasks auto-skip on JDK >= 26
# (SpotBugs 4.9.x cannot scan JDK 26 runtime classes — class file major 70 —
# and a degraded analysis is worse than none). Run this script from a
# JDK 17..25 JVM instead:
#
#   JAVA_HOME=$HOME/jdks/temurin-17 ./scripts/lint-spotbugs.sh
#
# It exits non-zero on any finding (mirror of the Gradle task semantics).
# Reports land in build/spotbugs-standalone/*.html.
set -eu

REPO=$(cd "$(dirname "$0")/.." && pwd)
JDK="${JAVA_HOME:-$(/usr/libexec/java_home 2>/dev/null || echo)}"
if [ -z "$JDK" ]; then echo "error: JAVA_HOME not set" >&2; exit 2; fi

# Resolve the SpotBugs distribution: $SPOTBUGS_HOME wins; else the
# Gradle-resolved jar set from the local cache; else download-once hint.
if [ -n "${SPOTBUGS_HOME:-}" ] && [ -x "$SPOTBUGS_HOME/bin/spotbugs" ]; then
    SB="$SPOTBUGS_HOME/bin/spotbugs"
else
    JAR=$(find "$HOME/.gradle/caches/modules-2/files-2.1/com.github.spotbugs/spotbugs" \
          -name 'spotbugs-*.jar' ! -name '*sources*' ! -name '*javadoc*' 2>/dev/null \
          | sort | tail -1)
    if [ -z "$JAR" ]; then
        echo "error: no SpotBugs in the Gradle cache; set SPOTBUGS_HOME or run" >&2
        echo "       ./gradlew :core:spotbugsMain once on a supported JDK to populate it" >&2
        exit 2
    fi
    SB="$JDK/bin/java -jar $JAR"
fi

OUT="$REPO/build/spotbugs-standalone"
mkdir -p "$OUT"

"$SB" -textui -effort:max -medium -html -output "$OUT/core.html" \
    -auxclasspath "$REPO/core/build/classes/java/main" \
    -onlyAnalyze 'io.github.jemmix.tdfa.*' \
    -exitcode \
    "$REPO/core/build/classes/java/main" \
    && echo "spotbugs: core clean"

"$SB" -textui -effort:max -medium -html -output "$OUT/asm.html" \
    -auxclasspath "$REPO/core/build/classes/java/main" \
    -onlyAnalyze 'io.github.jemmix.tdfa.*' \
    -exitcode \
    "$REPO/asm/build/classes/java/main" \
    && echo "spotbugs: asm clean"

"$SB" -textui -effort:max -medium -html -output "$OUT/facade.html" \
    -auxclasspath "$REPO/core/build/classes/java/main:$REPO/asm/build/classes/java/main" \
    -onlyAnalyze 'io.github.jemmix.tdfa.*' \
    -exitcode \
    "$REPO/build/classes/java/main" \
    && echo "spotbugs: facade clean"

echo "spotbugs: all modules clean (reports in $OUT)"
