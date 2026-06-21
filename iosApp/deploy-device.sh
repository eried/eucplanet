#!/usr/bin/env bash
# One-command build + sign + install of EUC Planet on a paired iPhone (USB or wifi).
#
# ONE-TIME prereqs (only you can do these — see DEVICE_TESTING.md):
#   1. Add your Apple ID in Xcode (Settings > Accounts) -> creates a signing cert.
#   2. Cable the iPhone to the Mac once; enable Developer Mode + Trust.
#   3. Find your Team ID: Xcode > Settings > Accounts > your team, or
#      `security find-identity -v -p codesigning` (the "(TEAMID)" suffix).
#
# Then, from the repo root on the Mac:
#   TEAM=<YOURTEAMID> bash iosApp/deploy-device.sh
# After the first USB run, check "Connect via network" in Xcode and it's all wifi.
set -e
cd "$(dirname "$0")/.."

export JAVA_HOME="${JAVA_HOME:-$(ls -d "$HOME"/.jdks/jdk-17*/Contents/Home 2>/dev/null | head -1)}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"

# Locate the paired physical device (not a simulator).
DEV_ID=$(xcrun xctrace list devices 2>&1 \
  | grep -iE 'iPhone|iPad' | grep -vi simulator \
  | grep -oE '[0-9A-Fa-f]{8}-[0-9A-Fa-f]{16}|[0-9a-f]{40}' | head -1)
if [ -z "$DEV_ID" ]; then
  echo "No paired iPhone/iPad found. Cable it once, enable Developer Mode, tap Trust."
  echo "Devices seen:"; xcrun xctrace list devices 2>&1 | sed -n '/== Devices ==/,/== Simulators ==/p'
  exit 1
fi
echo "Target device: $DEV_ID"

# Best-effort: auto-detect the signing team from the installed Apple Development
# cert, so this script is zero-config once you've signed into Xcode (Settings >
# Accounts). The Team ID is the cert subject's organizationalUnitName (OU).
if [ -z "${TEAM:-}" ]; then
  TEAM=$(security find-certificate -a -c "Apple Develop" -p 2>/dev/null \
    | openssl x509 -noout -subject -nameopt multiline 2>/dev/null \
    | sed -n 's/.*organizationalUnitName *= *//p' | head -1)
  [ -n "$TEAM" ] && echo "Auto-detected signing team: $TEAM"
fi
if ! security find-identity -v -p codesigning 2>/dev/null | grep -q "Apple Develop"; then
  echo "WARNING: no 'Apple Development' signing identity in the keychain yet."
  echo "  One-time: Xcode > Settings (Cmd+,) > Accounts > + > Apple ID (free is OK),"
  echo "  then re-run this script. (Code-signing will fail until that is done.)"
fi

DD=/tmp/eucdd-device
xcodebuild -project iosApp/iosApp.xcodeproj -scheme EucPlanet -configuration Debug \
  -destination "generic/platform=iOS" -derivedDataPath "$DD" \
  -allowProvisioningUpdates -allowProvisioningDeviceRegistration ${TEAM:+DEVELOPMENT_TEAM="$TEAM"} build

APP="$DD/Build/Products/Debug-iphoneos/EucPlanet.app"
echo "Installing $APP ..."
xcrun devicectl device install app --device "$DEV_ID" "$APP"
echo "Done. Launch on the iPhone, or:"
echo "  xcrun devicectl device process launch --device $DEV_ID com.eried.eucplanet.ios"
echo "  (watch the screen: xcrun devicectl device screenshot --device $DEV_ID /tmp/dev.png)"
