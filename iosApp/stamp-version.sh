#!/bin/sh
# Xcode build phase: stamps the built Info.plist with the app's real version,
# so a build from Xcode (simulator, debug, deploy-device.sh) shows what the
# Android app and TestFlight show instead of the project's 0.1 (1).
#
# The version is Android's versionName in app/build.gradle.kts, the same
# source testflight.sh reads; the build number is the commit count, as
# testflight.sh numbers its uploads. testflight.sh sets MARKETING_VERSION and
# CURRENT_PROJECT_VERSION itself, and then this leaves the plist alone.
set -e
PLIST="$TARGET_BUILD_DIR/$INFOPLIST_PATH"
[ -f "$PLIST" ] || exit 0
cd "$SRCROOT/.."
if [ "$MARKETING_VERSION" = "0.1" ]; then
  VERSION=$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts | head -n 1)
  [ -n "$VERSION" ] && /usr/libexec/PlistBuddy -c "Set :CFBundleShortVersionString $VERSION" "$PLIST"
fi
if [ "$CURRENT_PROJECT_VERSION" = "1" ]; then
  BUILD=$(git rev-list --count HEAD 2>/dev/null || true)
  [ -n "$BUILD" ] && /usr/libexec/PlistBuddy -c "Set :CFBundleVersion $BUILD" "$PLIST"
fi
exit 0
