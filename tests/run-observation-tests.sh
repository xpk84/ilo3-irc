#!/bin/bash
set -euo pipefail
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
JDK8="${JDK8:-/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home}"
VERSION=$("$JDK8/bin/java" -version 2>&1)
case "$VERSION" in *'version "1.8.'*) ;; *) printf '%s\n' 'Tests require JDK 8; set JDK8 to its home.' >&2; exit 1 ;; esac
BASE="${TMPDIR:-$HOME/.hermes/cache/scratch}"
mkdir -p "$BASE"
TEST_TMP=$(mktemp -d "${BASE%/}/ilo3-observation-tests.XXXXXXXX")
trap 'rm -rf "$TEST_TMP"' EXIT
umask 077
"$JDK8/bin/keytool" -genkeypair -alias server -keystore "$TEST_TMP/fixture.jks" \
    -storetype JKS -storepass test-only -keypass test-only -dname CN=observation-fixture.invalid \
    -keyalg RSA -keysize 2048 -sigalg SHA256withRSA -validity 2 >/dev/null 2>&1
"$JDK8/bin/keytool" -genkeypair -alias server -keystore "$TEST_TMP/replacement.jks" \
    -storetype JKS -storepass test-only -keypass test-only -dname CN=observation-replacement.invalid \
    -keyalg RSA -keysize 2048 -sigalg SHA256withRSA -validity 2 >/dev/null 2>&1
"$JDK8/bin/keytool" -genkeypair -alias server -keystore "$TEST_TMP/expired.jks" \
    -storetype JKS -storepass test-only -keypass test-only -dname CN=observation-expired.invalid \
    -keyalg RSA -keysize 2048 -sigalg SHA256withRSA -startdate '2020/01/01 00:00:00' -validity 1 >/dev/null 2>&1
"$JDK8/bin/javac" -Xlint:all -d "$TEST_TMP" "$ROOT/src/IloSupport.java" \
    "$ROOT/src/SecureIlo.java" "$ROOT/tests/CertificateObservationTest.java"
"$JDK8/bin/java" -Djava.awt.headless=true -Djava.io.tmpdir="$TEST_TMP" -cp "$TEST_TMP" \
    CertificateObservationTest "$TEST_TMP/fixture.jks" "$TEST_TMP/replacement.jks" "$TEST_TMP/expired.jks"
"$JDK8/bin/java" -Djava.awt.headless=true -Djava.io.tmpdir="$TEST_TMP" -cp "$TEST_TMP" \
    CertificateObservationTest legacy "$TEST_TMP/fixture.jks"
