#!/bin/sh
# Builds desktop/build/Kotoba.app: the Swift app, the Java core and the interface assets.
# Needs: JDK 21, Swift (Command Line Tools), mpv (brew install mpv) for the video player.
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
"$HERE/build-core.sh" >/dev/null
if ! ( cd "$HERE/mac" && swift build -c release --disable-sandbox > "$HERE/build/swift.log" 2>&1 ); then grep -E "error" -A3 "$HERE/build/swift.log"; exit 1; fi
APP="$HERE/build/Kotoba.app"
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources/core" "$APP/Contents/Resources/assets" "$APP/Contents/Resources/web"
cp "$HERE/mac/.build/release/Kotoba" "$APP/Contents/MacOS/Kotoba"
cp "$HERE/mac/.build/release/KotobaOCR" "$APP/Contents/MacOS/kotoba-ocr"
cp "$HERE/build/kotoba-core.jar" "$HERE"/libs/*.jar "$APP/Contents/Resources/core/"
# The phone's interface, with the OCR models for the comic reader's text layer.
( cd "$HERE/../android/assets" && find . -type f | while IFS= read -r f; do mkdir -p "$APP/Contents/Resources/assets/$(dirname "$f")"; cp "$f" "$APP/Contents/Resources/assets/$f"; done )
cp "$HERE"/web/* "$APP/Contents/Resources/web/"
cat > "$APP/Contents/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>CFBundleName</key><string>Kotoba</string>
  <key>CFBundleDisplayName</key><string>Kotoba</string>
  <key>CFBundleIdentifier</key><string>app.kotoba.desktop</string>
  <key>CFBundleExecutable</key><string>Kotoba</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>CFBundleShortVersionString</key><string>0.1.0</string>
  <key>CFBundleVersion</key><string>1</string>
  <key>LSMinimumSystemVersion</key><string>13.0</string>
  <key>NSHighResolutionCapable</key><true/>
  <key>NSAppTransportSecurity</key><dict><key>NSAllowsLocalNetworking</key><true/></dict>
</dict></plist>
PLIST
codesign --force --deep --sign - "$APP" 2>/dev/null || true
echo "$APP"
