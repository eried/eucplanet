#!/bin/sh
# Builds the iOS app for a simulator, installs it and launches it.
#   sh iosApp/run-simulator.sh [simulator name] [screenshot.png]
set -e
# Fail on an xcodebuild error even though its output is filtered.
set -o pipefail
cd "$(dirname "$0")/.."
NAME="${1:-iPhone 17}"
DD=build/ios-derived
xcodebuild -project iosApp/iosApp.xcodeproj -scheme EucPlanet -configuration Debug \
  -destination "platform=iOS Simulator,name=$NAME" -derivedDataPath "$DD" \
  CODE_SIGNING_ALLOWED=NO build | grep -E "error:|BUILD (SUCCEEDED|FAILED)"
DEV=$(xcrun simctl list devices available | grep -m1 "    $NAME (" | grep -oE '[0-9A-F-]{36}')
xcrun simctl boot "$DEV" 2>/dev/null || true
xcrun simctl bootstatus "$DEV" -b >/dev/null
xcrun simctl install "$DEV" "$DD/Build/Products/Debug-iphonesimulator/EucPlanet.app"
xcrun simctl launch "$DEV" com.eried.eucplanet.ios
if [ -n "$2" ]; then sleep 6; xcrun simctl io "$DEV" screenshot "$2"; fi
