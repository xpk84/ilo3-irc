#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
JDK8=${JDK8:-/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home}
VERSION=$("$JDK8/bin/java" -version 2>&1)
case "$VERSION" in *'version "1.8.'*) ;; *) printf '%s\n' 'Tests require JDK 8; set JDK8 to its home.' >&2; exit 1 ;; esac
TMP_BASE=${TMPDIR:-${HOME}/.hermes/cache/scratch}
mkdir -p "$TMP_BASE"
BUILD=$(mktemp -d "$TMP_BASE/ilo-migration-tests.XXXXXXXX")
trap 'rm -rf "$BUILD"' EXIT HUP INT TERM
if [ "${1:-}" = --launcher ]; then
    "$JDK8/bin/javac" -encoding UTF-8 -d "$BUILD" "$ROOT"/src/*.java "$ROOT/tests/LegacyTrustMigrationTest.java"
else
    "$JDK8/bin/javac" -encoding UTF-8 -d "$BUILD" "$ROOT/src/IloSupport.java" "$ROOT/src/KnownControllers.java" "$ROOT/src/TrustPolicy.java" "$ROOT/src/LegacyTrustMigration.java" "$ROOT/tests/LegacyTrustMigrationTest.java"
fi
"$JDK8/bin/java" -Djava.awt.headless=true -Djava.util.prefs.userRoot="$BUILD/prefs" -Djava.io.tmpdir="$BUILD" -cp "$BUILD" LegacyTrustMigrationTest "$@"
