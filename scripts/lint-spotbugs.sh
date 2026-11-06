#!/bin/sh
# Standalone SpotBugs invocation over the three library modules (facade,
# core, asm). Deliberately decoupled from Gradle so the analysis survives
# build-tool churn (decade-stability: SpotBugs is a plain-Java CLI over
# compiled classfiles).
#
# When the Gradle tasks enforced only on JDK <= 25 (analyzer 4.9.x could
# not scan JDK 26 runtime classes — class file major 70), this was the
# JDK-independent path. Analyzer 4.10.5+ (BCEL 6.13/ASM 9.11) made the
# Gradle tasks JDK-agnostic, so today this is the escape hatch for
# build-tool churn and ad-hoc runs over already-built classfiles:
#
#   JAVA_HOME=$HOME/jdks/temurin-25 ./scripts/lint-spotbugs.sh
#
# Exits non-zero on any finding or analysis error (mirror of the Gradle
# task semantics — never a false green). Reports land in
# build/spotbugs-standalone/*.html.
set -eu

REPO=$(cd "$(dirname "$0")/.." && pwd)
JDK="${JAVA_HOME:-$(/usr/libexec/java_home 2>/dev/null || echo)}"
if [ -z "$JDK" ]; then echo "error: JAVA_HOME not set" >&2; exit 2; fi

# Resolve the SpotBugs distribution: $SPOTBUGS_HOME wins; else the
# tool-classpath file materialized by `./gradlew spotbugsToolClasspath`
# (the thin Central jar cannot run standalone — it needs its dependency
# closure on the classpath, which that task writes out in lockstep with
# the analyzer version pinned in build.gradle).
if [ -n "${SPOTBUGS_HOME:-}" ] && [ -x "$SPOTBUGS_HOME/bin/spotbugs" ]; then
    run_sb() { "$SPOTBUGS_HOME/bin/spotbugs" "$@"; }
else
    CP_FILE="$REPO/build/spotbugs-standalone/tool.classpath"
    if [ ! -f "$CP_FILE" ]; then
        echo "error: $CP_FILE missing; run ./gradlew spotbugsToolClasspath" >&2
        echo "       (or set SPOTBUGS_HOME to a SpotBugs distribution)" >&2
        exit 2
    fi
    run_sb() { "$JDK/bin/java" -cp "$(cat "$CP_FILE")" edu.umd.cs.findbugs.LaunchAppropriateUI "$@"; }
fi

OUT="$REPO/build/spotbugs-standalone"
mkdir -p "$OUT"

# :asm references org.objectweb.asm (asm 9.9.1, pinned by asm/build.gradle);
# without it on the auxclasspath the scan is degraded (missing classes) and
# hard-fails below. Resolved from the Gradle cache; single artifact, no
# closure needed.
ASM_JAR=$(find "$HOME/.gradle/caches/modules-2/files-2.1/org.ow2.asm/asm" \
      -name 'asm-*.jar' ! -name '*sources*' ! -name '*javadoc*' 2>/dev/null | head -1)
if [ -z "$ASM_JAR" ]; then
    echo "error: org.ow2.asm:asm not in the Gradle cache; run ./gradlew :asm:classes once" >&2
    exit 2
fi

# Each invocation must exit 0 — SpotBugs -exitcode returns non-zero on
# findings AND on analysis errors. Explicit status check: the `cmd &&
# echo` pattern would silently swallow failures (AND-list members are
# exempt from set -e) — a false green.
scan() {
    name=$1
    shift
    if run_sb -textui -effort:max -medium -html -output "$OUT/$name.html" \
        -exitcode "$@"; then
        echo "spotbugs: $name clean"
    else
        echo "spotbugs: $name FAILED (report: $OUT/$name.html)" >&2
        exit 1
    fi
}

scan core \
    -auxclasspath "$REPO/core/build/classes/java/main" \
    -onlyAnalyze 'io.github.jemmix.tdfa.*' \
    "$REPO/core/build/classes/java/main"

scan asm \
    -auxclasspath "$REPO/core/build/classes/java/main:$ASM_JAR" \
    -onlyAnalyze 'io.github.jemmix.tdfa.*' \
    "$REPO/asm/build/classes/java/main"

scan facade \
    -auxclasspath "$REPO/core/build/classes/java/main:$REPO/asm/build/classes/java/main:$ASM_JAR" \
    -onlyAnalyze 'io.github.jemmix.tdfa.*' \
    "$REPO/build/classes/java/main"

echo "spotbugs: all modules clean (reports in $OUT)"
