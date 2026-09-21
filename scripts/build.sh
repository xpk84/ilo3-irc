#!/bin/bash
# Source this after runtime.sh; build in isolation so stale classes never survive.
ilo_build() (
    local root="$1" staging
    staging="$(mktemp -d "$root/.build.XXXXXX")"
    trap 'rm -rf "$staging"' EXIT
    # Rebuild all sources in a clean directory; never trust an old main class.
    # Copy classpath resources first so compiled classes cannot be shadowed.
    if [ -d "$root/resources" ]; then cp -R "$root/resources/." "$staging/"; fi
    "$ILO_JAVAC" -encoding UTF-8 -d "$staging" "$root"/src/*.java
    [ -f "$staging/ILO3IRC.class" ] || { printf 'Missing ILO3IRC.class\n' >&2; exit 1; }
    rm -rf "$root/build"
    mv "$staging" "$root/build"
)
