#!/bin/bash
set -euo pipefail
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
JDK8="${JDK8:-/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home}"
VERSION=$("$JDK8/bin/java" -version 2>&1)
case "$VERSION" in *'version "1.8.'*) ;; *) printf '%s\n' 'Tests require JDK 8; set JDK8 to its home.' >&2; exit 1 ;; esac
BASE="${TMPDIR:-$HOME/.hermes/cache/scratch}"
mkdir -p "$BASE"
TEST_TMP=$(mktemp -d "${BASE%/}/ilo3-probe-tests.XXXXXXXX")
trap 'rm -rf "$TEST_TMP"' EXIT
umask 077
"$JDK8/bin/keytool" -genkeypair -alias server -keystore "$TEST_TMP/fixture.jks" \
    -storetype JKS -storepass test-only -keypass test-only -dname CN=isolated-probe.invalid \
    -keyalg RSA -keysize 2048 -sigalg SHA256withRSA -validity 2 >/dev/null 2>&1
SOURCES=("$ROOT/src/IloSupport.java" "$ROOT/src/SecureIlo.java" "$ROOT/src/CertificateProbe.java"
    "$ROOT/tests/CertificateObservationTest.java" "$ROOT/tests/IsolatedCertificateProbeTest.java")
"$JDK8/bin/javac" -Xlint:all -d "$TEST_TMP" "${SOURCES[@]}"
mkdir "$TEST_TMP/faults"
"$JDK8/bin/javac" -Xlint:all -d "$TEST_TMP/faults" "$ROOT/tests/probe-fixture/CertificateProbe.java"
for MODE in faults timeout cancel surface cold defaults; do
    "$JDK8/bin/java" -Djava.awt.headless=true -Djava.io.tmpdir="$TEST_TMP" \
        -Dprobe.fixture.classpath="$TEST_TMP/faults" -cp "$TEST_TMP" \
        IsolatedCertificateProbeTest "$MODE" "$TEST_TMP/fixture.jks"
done
