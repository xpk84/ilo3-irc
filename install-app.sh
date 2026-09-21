#!/bin/bash
# Build a self-contained app; runtime remains external. Never replace an app.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
APP='/Applications/iLO 3-4 Console.app'
usage() { printf 'Usage: %s [--output /absolute/path/Candidate.app]\n' "$0" >&2; }
if [ "$#" -ne 0 ]; then
    if [ "$#" -ne 2 ] || [ "$1" != --output ]; then usage; exit 2; fi
    APP="$2"
fi
case "$APP" in /*.app) ;; *) usage; printf 'Output must be an absolute .app path.\n' >&2; exit 2 ;; esac
if [ -e "$APP" ] || [ -L "$APP" ]; then
    printf 'Refusing output: target already exists: %s\n' "$APP" >&2
    exit 1
fi
PARENT="$(dirname "$APP")"
[ -d "$PARENT" ] || { printf 'Output parent directory does not exist: %s\n' "$PARENT" >&2; exit 1; }
source "$ROOT/scripts/runtime.sh"
source "$ROOT/scripts/build.sh"
ilo_find_runtime yes
ilo_build "$ROOT"
STAGING="$(mktemp -d "$PARENT/.ilo3-package.XXXXXX")"
CREATED=no
cleanup() {
    rm -rf "$STAGING"
    if [ "$CREATED" = yes ]; then rm -rf "$APP"; fi
}
trap cleanup EXIT
CONTENTS="$STAGING/Candidate.app/Contents"
mkdir -p "$CONTENTS/MacOS" "$CONTENTS/Resources/classes"
cp -R "$ROOT/build/." "$CONTENTS/Resources/classes/"
cp "$ROOT/scripts/runtime.sh" "$CONTENTS/Resources/runtime.sh"
cp "$ROOT/app-icon.icns" "$CONTENTS/Resources/app-icon.icns"
# cksum detects missing/corrupt packaged content without requiring javac or Python.
# It is an integrity check, not a cryptographic signature (codesign is below).
(
    cd "$CONTENTS/Resources/classes"
    find . -type f -exec cksum {} \;
) > "$CONTENTS/Resources/classes.cksum"
cat > "$CONTENTS/MacOS/ilo3-console" <<'LAUNCHER'
#!/bin/bash
set -euo pipefail
CONTENTS="$(cd "$(dirname "$0")/.." && pwd)"
RESOURCES="$CONTENTS/Resources"
source "$RESOURCES/runtime.sh"
ilo_find_runtime no
if [ "${1:-}" = --check ]; then
    [ "$#" -eq 1 ] || { printf 'Usage: %s --check (no other arguments)\n' "$0" >&2; exit 2; }
    [ -s "$RESOURCES/classes/ILO3IRC.class" ] && [ -s "$RESOURCES/classes.cksum" ] || {
        printf 'Missing bundled classes or manifest.\n' >&2; exit 1;
    }
    while read -r checksum size name; do
        case "$name" in ./*) ;; *) printf 'Invalid class manifest path: %s\n' "$name" >&2; exit 1 ;; esac
        file="$RESOURCES/classes/$name"
        [ -f "$file" ] || { printf 'Missing bundled file: %s\n' "$name" >&2; exit 1; }
        actual="$(cksum < "$file")"
        read -r actual_checksum actual_size <<< "$actual"
        if [ "$checksum" != "$actual_checksum" ] || [ "$size" != "$actual_size" ]; then
            printf 'Corrupt bundled file: %s\n' "$name" >&2; exit 1
        fi
    done < "$RESOURCES/classes.cksum"
    ilo_print_runtime
    printf 'Classes OK: %s/classes\n' "$RESOURCES"
    exit 0
fi
unset http_proxy https_proxy HTTP_PROXY HTTPS_PROXY all_proxy ALL_PROXY
exec "$ILO_JAVA" -cp "$RESOURCES/classes" ILO3IRC "$@"
LAUNCHER
chmod +x "$CONTENTS/MacOS/ilo3-console"
cat > "$CONTENTS/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleExecutable</key><string>ilo3-console</string>
    <key>CFBundleIdentifier</key><string>io.github.xpk84.ilo3irc</string>
    <key>CFBundleName</key><string>iLO 3/4 Console</string>
    <key>CFBundleDisplayName</key><string>iLO 3/4 Console</string>
    <key>CFBundlePackageType</key><string>APPL</string>
    <key>CFBundleIconFile</key><string>app-icon</string>
    <key>CFBundleShortVersionString</key><string>1.2.0</string>
    <key>CFBundleVersion</key><string>1</string>
    <key>LSApplicationCategoryType</key><string>public.app-category.utilities</string>
</dict>
</plist>
PLIST
if command -v codesign >/dev/null 2>&1; then
    codesign --force --sign - "$STAGING/Candidate.app"
    codesign --verify --deep --strict "$STAGING/Candidate.app"
    printf 'Ad-hoc signed (not notarized).\n'
else
    printf 'codesign unavailable; bundle is unsigned.\n' >&2
fi
# mkdir is the final atomic no-clobber guard, including a concurrently made target.
mkdir "$APP" || { printf 'Cannot create output (possibly already exists): %s\n' "$APP" >&2; exit 1; }
CREATED=yes
cp -R "$STAGING/Candidate.app/." "$APP/"
"$APP/Contents/MacOS/ilo3-console" --check
if command -v codesign >/dev/null 2>&1; then codesign --verify --deep --strict "$APP"; fi
CREATED=no
printf 'Created: %s\n' "$APP"
