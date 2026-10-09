#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
JDK8="${JDK8:-/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home}"
BASE="${TMPDIR:-$HOME/.cache}"
mkdir -p "$BASE"
BASE="$(cd "$BASE" && pwd -P)"
BUILD=$(mktemp -d "${BASE%/}/ilo3-ui.XXXXXX")
trap 'rm -rf "$BUILD"' EXIT
"$JDK8/bin/javac" -Xlint:all -encoding UTF-8 -d "$BUILD" src/*.java tests/TofuLayoutTest.java tests/TofuFlowTest.java tests/TofuHistoryTest.java
for language in en ru; do
    "$JDK8/bin/java" "-Duser.language=$language" "-Dilo3.registry.path=$BUILD/$language/layout/known.properties" "-Djava.util.prefs.userRoot=$BUILD/prefs" -cp "$BUILD" TofuLayoutTest
    "$JDK8/bin/java" "-Duser.language=$language" -cp "$BUILD" TofuFlowTest "$BUILD/$language"
    "$JDK8/bin/java" "-Duser.language=$language" "-Djava.util.prefs.userRoot=$BUILD/prefs" -cp "$BUILD" TofuHistoryTest "$BUILD/$language"
done
