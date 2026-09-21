#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
JDK8="${JDK8:-/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home}"
BASE="${TMPDIR:-$HOME/.cache}"
mkdir -p "$BASE"
TEST_TMP=$(mktemp -d "${BASE%/}/ilo3-main-tests.XXXXXX")
trap 'rm -rf "$TEST_TMP"' EXIT
"$JDK8/bin/javac" -Xlint:all -d "$TEST_TMP" src/*.java tests/LauncherTest.java
JAVA=("$JDK8/bin/java" -Djava.awt.headless=true "-Djava.util.prefs.userRoot=$TEST_TMP/prefs" -cp "$TEST_TMP")
"${JAVA[@]}" LauncherTest
HELP=$("${JAVA[@]}" ILO3IRC --help)
[[ "$HELP" == *"Usage:"* && "$HELP" == *"independently verified"* ]]
printf '%s\n' 'PASS --help without GUI/network'
VERSION=$("${JAVA[@]}" ILO3IRC --version)
[[ "$VERSION" == "ilo3-irc 1.1.0" ]]
printf '%s\n' 'PASS --version without GUI/network'
CHECK=$("${JAVA[@]}" ILO3IRC --check)
[[ "$CHECK" == *"runtime/classes OK"* ]]
printf '%s\n' 'PASS --check without GUI/network'
if "${JAVA[@]}" ILO3IRC --bad-argument >"$TEST_TMP/error" 2>&1; then
    printf '%s\n' 'FAIL unknown argument accepted' >&2; exit 1
fi
printf '%s\n' 'PASS invalid arguments fail closed' 'LAUNCHER CLI TESTS: 4 PASS'
