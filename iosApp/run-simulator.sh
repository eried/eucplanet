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
  CODE_SIGN_IDENTITY=- CODE_SIGN_STYLE=Manual DEVELOPMENT_TEAM= PROVISIONING_PROFILE_SPECIFIER= build | grep -E "error:|BUILD (SUCCEEDED|FAILED)"
DEV=$(xcrun simctl list devices available | grep -m1 "    $NAME (" | grep -oE '[0-9A-F-]{36}')
xcrun simctl boot "$DEV" 2>/dev/null || true
xcrun simctl bootstatus "$DEV" -b >/dev/null
xcrun simctl install "$DEV" "$DD/Build/Products/Debug-iphonesimulator/EucPlanet.app"
# Leaderboard uploads go to a local eucstats (uvicorn on port 8000), never the
# real one: set SIMCTL_CHILD_EUCSTATS_API_BASE_URL to point elsewhere.
export SIMCTL_CHILD_EUCSTATS_API_BASE_URL="${SIMCTL_CHILD_EUCSTATS_API_BASE_URL:-http://127.0.0.1:8000/api/v1}"
xcrun simctl launch "$DEV" com.eried.eucplanet.ios
if [ -n "$2" ]; then sleep 6; xcrun simctl io "$DEV" screenshot "$2"; fi
