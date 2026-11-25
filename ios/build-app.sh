#!/bin/bash
# Builds the iPad app for the simulator (default) or a device: translates the core, generates the Xcode project, builds.
#   ios/build-app.sh [simulator|device]
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
MODE="${1:-simulator}"
TRANSLATE_ONLY=1 "$HERE/build-core.sh"   # Xcode compiles the generated code itself
cd "$HERE/App"
xcodegen generate --quiet
if [ "$MODE" = device ]; then SDK=iphoneos; DEST="generic/platform=iOS"; else SDK=iphonesimulator; DEST="generic/platform=iOS Simulator"; fi
xcodebuild -project Kotoba.xcodeproj -scheme Kotoba -sdk $SDK -destination "$DEST" -configuration Debug \
  -derivedDataPath "$HERE/build/derived" ${SIGN_ARGS} build 2>&1 | grep -E "error:|warning: unable|BUILD|Signing" | head -30
echo "$HERE/build/derived/Build/Products/Debug-$SDK/Kotoba.app"
