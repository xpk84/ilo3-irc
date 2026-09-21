#!/bin/bash
# ilo3-irc — run the iLO 3 Java IRC on macOS (Apple Silicon native).
# Usage: ./ilo3-irc.sh [host]
set -euo pipefail
cd "$(dirname "$0")"

# Locate a Java 8 (applet API required). Prefers ARM64 builds on Apple Silicon.
J8=""
for cand in \
    /Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home/bin/java \
    "$(command -v java || true)"; do
    [ -x "$cand" ] || continue
    if "$cand" -version 2>&1 | head -1 | grep -q '"1.8'; then J8="$cand"; break; fi
done
if [ -z "$J8" ]; then
    echo "Java 8 not found. Install an ARM64 JDK 8, e.g.:"
    echo "  brew install --cask zulu@8"
    exit 1
fi

# Build if needed
if [ ! -f build/ILO3IRC.class ] || [ src/ILO3IRC.java -nt build/ILO3IRC.class ]; then
    mkdir -p build
    "$J8"/javac -d build src/ILO3IRC.java
fi

# Proxies break the raw KVM TCP stream — start clean.
unset http_proxy https_proxy HTTP_PROXY HTTPS_PROXY all_proxy ALL_PROXY 2>/dev/null || true

exec "$J8" -cp build ILO3IRC "$@"
