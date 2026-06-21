#!/usr/bin/env bash
# Assemble the headless iOS demo app and run it on a booted simulator, then
# screenshot it. Run on the Mac from the repo root AFTER linking the framework:
#   ./gradlew :shared:linkDebugFrameworkIosSimulatorArm64
#   bash iosApp/build-run.sh [out.png]
#
# Why this shape (all learned the hard way):
#  - Shared framework must be DYNAMIC (isStatic=false) so hand-linking resolves
#    Skia/skiko symbols.
#  - Host is UIKit (not SwiftUI): SwiftUICore can't be linked outside a real
#    Xcode target on Xcode 26.
#  - Info.plist needs CADisableMinimumFrameDurationOnPhone (Compose aborts
#    without it) + CFBundleSupportedPlatforms/DTPlatformName/UIDeviceFamily
#    (else `simctl install` fails "set metadata") + an ad-hoc codesign.
set -euo pipefail
OUT="${1:-/tmp/euc_ios.png}"
BUNDLE_ID="com.eried.eucplanet.iosdemo"
FW="shared/build/bin/iosSimulatorArm64/debugFramework"
APP="/tmp/EucPlanet.app"

rm -rf "$APP"; mkdir -p "$APP/Frameworks"
xcrun -sdk iphonesimulator swiftc -target arm64-apple-ios15.0-simulator -parse-as-library \
  -F "$FW" -framework Shared -framework WatchConnectivity -framework PhotosUI -framework CoreLocation \
  -Xlinker -rpath -Xlinker @executable_path/Frameworks \
  iosApp/AppDelegate.swift iosApp/WatchSessionManager.swift iosApp/AvatarPhotoPicker.swift iosApp/LocationBridge.swift iosApp/HudDiscoveryBridge.swift -o "$APP/EucPlanet"
cp iosApp/Info.plist "$APP/Info.plist"
cp -R "$FW/Shared.framework" "$APP/Frameworks/"
# Bundle the Compose Multiplatform resources (reused Android strings.xml) at the
# EXACT path the generated accessors load: composeResources/<resClassPackage>/...
RESDIR="shared/build/generated/compose/resourceGenerator/preparedResources/commonMain/composeResources"
[ -d "$RESDIR" ] && { D="$APP/compose-resources/composeResources/com.eried.eucplanet.resources"; mkdir -p "$D"; cp -R "$RESDIR/." "$D/"; }
codesign --force --sign - "$APP/Frameworks/Shared.framework" >/dev/null 2>&1
codesign --force --sign - "$APP" >/dev/null 2>&1

DEV="$(xcrun simctl list devices booted | grep -m1 'iPhone' | grep -oE '\([0-9A-F-]{36}\)' | tr -d '()' || true)"
if [ -z "$DEV" ]; then
  DEV="$(xcrun simctl list devices available | grep -m1 'iPhone' | grep -oE '\([0-9A-F-]{36}\)' | tr -d '()')"
  xcrun simctl boot "$DEV"; xcrun simctl bootstatus "$DEV" -b
fi
xcrun simctl uninstall "$DEV" "$BUNDLE_ID" 2>/dev/null || true
xcrun simctl install "$DEV" "$APP"
xcrun simctl launch "$DEV" "$BUNDLE_ID"
sleep 6
xcrun simctl io "$DEV" screenshot "$OUT"
echo "screenshot -> $OUT (device $DEV)"
