#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
JDK8="${JDK8:-/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home}"
BASE="${TMPDIR:-$HOME/.cache}"
mkdir -p "$BASE"
TEST_TMP=$(mktemp -d "${BASE%/}/ilo3-main-tests.XXXXXX")
trap 'rm -rf "$TEST_TMP"' EXIT
"$JDK8/bin/javac" -Xlint:all -encoding UTF-8 -d "$TEST_TMP" src/*.java tests/LauncherTest.java tests/MessagesTest.java
JAVA=("$JDK8/bin/java" -Djava.awt.headless=true -Dfile.encoding=UTF-8 "-Djava.util.prefs.userRoot=$TEST_TMP/prefs" -cp "$TEST_TMP")
"${JAVA[@]}" LauncherTest
"${JAVA[@]}" MessagesTest
HELP=$("${JAVA[@]}" ILO3IRC --help)
[[ "$HELP" == *"Usage:"* && "$HELP" == *"independently verified"* ]]
printf '%s\n' 'PASS --help without GUI/network'
VERSION=$("${JAVA[@]}" ILO3IRC --version)
[[ "$VERSION" == "ilo3-irc 1.2.0" ]]
printf '%s\n' 'PASS --version without GUI/network'
CHECK=$("${JAVA[@]}" ILO3IRC --check)
[[ "$CHECK" == *"runtime/classes OK"* ]]
printf '%s\n' 'PASS --check without GUI/network'
if "${JAVA[@]}" ILO3IRC --bad-argument >"$TEST_TMP/error" 2>&1; then
    printf '%s\n' 'FAIL unknown argument accepted' >&2; exit 1
fi
printf '%s\n' 'PASS invalid arguments fail closed' 'LAUNCHER CLI TESTS: 4 PASS'
for language in en ru auto; do
    VERSION=$("${JAVA[@]}" ILO3IRC --language "$language" --version)
    [[ "$VERSION" == "ilo3-irc 1.2.0" ]]
    HELP=$("${JAVA[@]}" ILO3IRC --language "$language" --help)
    [[ "$HELP" == *"--language en|ru|auto"* ]]
done
for args in "--language" "--language fr" "--language en --bad-argument"; do
    read -r -a flags <<< "$args"
    if "${JAVA[@]}" ILO3IRC "${flags[@]}" >"$TEST_TMP/error" 2>&1; then
        printf 'FAIL invalid language arguments accepted: %s\n' "$args" >&2; exit 1
    fi
done
if "${JAVA[@]}" ILO3IRC --language ru --bad-argument >"$TEST_TMP/error" 2>&1; then exit 1; fi
grep -q 'Ошибка подключения/запуска' "$TEST_TMP/error"
printf '%s\n' 'PASS language CLI validation and localized startup errors'
