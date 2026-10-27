#!/bin/sh
# Builds, signs and installs the iOS app on a cabled (or paired) iPhone.
#   sh iosApp/deploy-device.sh
#
# One-time, by hand: sign in to Xcode with an Apple ID (Settings > Accounts,
# a free one works), plug the iPhone in, tap Trust, turn on Developer Mode.
# After the first install, trust the developer on the phone: Settings >
# General > VPN & Device Management. Free installs expire after 7 days.
set -e
# Fail on an xcodebuild error even though its output is filtered.
set -o pipefail
cd "$(dirname "$0")/.."

DEV=$(xcrun devicectl list devices 2>/dev/null | grep -E "iPhone|iPad" | grep -E "connected|available" \
  | grep -oE '[0-9A-F]{8}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{12}' | head -1)
if [ -z "$DEV" ]; then
  echo "No paired iPhone found. Plug it in, unlock it and tap Trust."
  xcrun devicectl list devices
  exit 1
fi

# The team is the OU of the Apple Development certificate Xcode created.
if [ -z "$TEAM" ]; then
  TEAM=$(security find-certificate -a -c "Apple Development" -p 2>/dev/null \
    | openssl x509 -noout -subject -nameopt multiline 2>/dev/null \
    | sed -n 's/.*organizationalUnitName *= *//p' | head -1)
fi
[ -n "$TEAM" ] || { echo "No Apple Development certificate. Sign in to Xcode (Settings > Accounts)."; exit 1; }

DD=build/ios-derived-device
xcodebuild -project iosApp/iosApp.xcodeproj -scheme EucPlanet -configuration Debug \
  -destination "generic/platform=iOS" -derivedDataPath "$DD" \
  -allowProvisioningUpdates -allowProvisioningDeviceRegistration DEVELOPMENT_TEAM="$TEAM" build \
  | grep -E "error:|BUILD (SUCCEEDED|FAILED)"
xcrun devicectl device install app --device "$DEV" "$DD/Build/Products/Debug-iphoneos/EucPlanet.app"
xcrun devicectl device process launch --device "$DEV" com.eried.eucplanet.ios || \
  echo "Installed. If launch was refused, trust the developer on the phone: Settings > General > VPN & Device Management."
