#!/bin/bash
# Install ilo3-irc as /Applications/iLO 3 Console.app (icon included).
set -euo pipefail
cd "$(dirname "$0")"

APP="/Applications/iLO 3 Console.app"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"

cp ilo3-irc.sh "$APP/Contents/MacOS/ilo3-console"
chmod +x "$APP/Contents/MacOS/ilo3-console"

cp app-icon.icns "$APP/Contents/Resources/" 2>/dev/null || true

cat > "$APP/Contents/Info.plist" <<'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleExecutable</key><string>ilo3-console</string>
    <key>CFBundleIdentifier</key><string>io.github.xpk84.ilo3irc</string>
    <key>CFBundleName</key><string>iLO 3 Console</string>
    <key>CFBundleDisplayName</key><string>iLO 3 Console</string>
    <key>CFBundlePackageType</key><string>APPL</string>
    <key>CFBundleIconFile</key><string>app-icon</string>
    <key>CFBundleShortVersionString</key><string>1.0.0</string>
    <key>LSApplicationCategoryType</key><string>public.app-category.utilities</string>
</dict>
</plist>
EOF

# Register with Launch Services
/System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister -f "$APP"

echo "Installed: $APP"
echo "Run it from Spotlight: 'iLO'"
