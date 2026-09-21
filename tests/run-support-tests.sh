#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
JDK8=${JDK8:-${JAVA_HOME:-/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home}}
JAVAC="$JDK8/bin/javac"
JAVA="$JDK8/bin/java"
VERSION=$("$JAVA" -version 2>&1)
case "$VERSION" in *'version "1.8.'*) ;; *) printf '%s\n' 'Tests require JDK 8; set JDK8 to its home.' >&2; exit 1 ;; esac
TMP_BASE=${TMPDIR:-${HOME}/.cache}
mkdir -p "$TMP_BASE"
BUILD=$(mktemp -d "$TMP_BASE/ilo-support-tests.XXXXXXXX")
trap 'rm -rf "$BUILD"' EXIT HUP INT TERM
"$JAVAC" -encoding UTF-8 -d "$BUILD" "$ROOT/src/IloSupport.java" "$ROOT/tests/IloSupportTest.java"
"$JAVA" -Djava.io.tmpdir="$BUILD" -cp "$BUILD" IloSupportTest "$@"
