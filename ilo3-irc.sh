#!/bin/bash
# Build and launch from source. --check validates without starting the GUI.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
source "$ROOT/scripts/runtime.sh"
source "$ROOT/scripts/build.sh"
ilo_find_runtime yes
ilo_build "$ROOT"
if ilo_check_requested "$@"; then
    ilo_print_runtime
    printf 'Classes OK: %s/build\n' "$ROOT"
    exit 0
fi
# Proxies must not intercept the raw KVM connection.
unset http_proxy https_proxy HTTP_PROXY HTTPS_PROXY all_proxy ALL_PROXY
exec "$ILO_JAVA" -cp "$ROOT/build" ILO3IRC "$@"
