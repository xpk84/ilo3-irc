#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
JDK8="${JDK8:-/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home}"
BASE="${TMPDIR:-$HOME/.cache}"; mkdir -p "$BASE"
BUILD=$(mktemp -d "${BASE%/}/ilo3-trust-policy.XXXXXX")
trap 'rm -rf "$BUILD"' EXIT
"$JDK8/bin/javac" -Xlint:all -d "$BUILD" src/TrustPolicy.java tests/TrustPolicyTest.java
"$JDK8/bin/java" -Djava.awt.headless=true -cp "$BUILD" TrustPolicyTest
