#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
JDK8="${JDK8:-/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home}"
BASE="${TMPDIR:-$HOME/.cache}"
mkdir -p "$BASE"
TEST_TMP=$(mktemp -d "${BASE%/}/ilo3-tls-tests.XXXXXX")
trap 'rm -rf "$TEST_TMP"' EXIT
"$JDK8/bin/keytool" -genkeypair -alias server -keystore "$TEST_TMP/fixture.jks" \
    -storetype JKS -storepass test-only -keypass test-only -dname CN=fixture.invalid \
    -keyalg RSA -keysize 2048 -validity 2 -ext SAN=dns:localhost >/dev/null 2>&1
"$JDK8/bin/javac" -Xlint:all -d "$TEST_TMP" src/IloSupport.java src/SecureIlo.java tests/SecureIloTest.java
"$JDK8/bin/java" -Djava.awt.headless=true -cp "$TEST_TMP" SecureIloTest "$TEST_TMP/fixture.jks"
