#!/bin/sh
# Archives the iOS app and uploads it to App Store Connect for TestFlight.
#   TEAM=<team id> ASC_KEY_ID=<key id> ASC_ISSUER_ID=<issuer id> sh iosApp/testflight.sh
#
# One-time, by hand: an App Store Connect API key with Admin access (Users and
# Access > Integrations), its .p8 saved as
# ~/.appstoreconnect/private_keys/AuthKey_<key id>.p8, and the app created in
# App Store Connect with the bundle id com.eried.eucplanet.ios. The key lets
# Xcode create the distribution certificate and profile itself.
#
# The version is Android's versionName; the build number is the commit count,
# so each upload from a newer commit is accepted.
set -e
# Fail on an xcodebuild error even though its output is filtered.
set -o pipefail
cd "$(dirname "$0")/.."

: "${TEAM:?Set TEAM to the developer team id (developer.apple.com > Membership)}"
: "${ASC_KEY_ID:?Set ASC_KEY_ID to the App Store Connect API key id}"
: "${ASC_ISSUER_ID:?Set ASC_ISSUER_ID to the App Store Connect issuer id}"
KEY="$HOME/.appstoreconnect/private_keys/AuthKey_$ASC_KEY_ID.p8"
[ -f "$KEY" ] || { echo "No key at $KEY"; exit 1; }

VERSION=$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts)
BUILD=$(git rev-list --count HEAD)
OUT=build/ios-release
rm -rf "$OUT"
mkdir -p "$OUT"
cat > "$OUT/ExportOptions.plist" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>method</key>
    <string>app-store-connect</string>
    <key>destination</key>
    <string>upload</string>
    <key>teamID</key>
    <string>$TEAM</string>
    <key>signingStyle</key>
    <string>automatic</string>
</dict>
</plist>
EOF

set -- -allowProvisioningUpdates -authenticationKeyPath "$KEY" \
  -authenticationKeyID "$ASC_KEY_ID" -authenticationKeyIssuerID "$ASC_ISSUER_ID"

# The release link of the Kotlin framework needs more heap than a debug build.
export EUC_GRADLE_ARGS="${EUC_GRADLE_ARGS:--Dorg.gradle.jvmargs=-Xmx6g}"
sh gradlew --stop >/dev/null

echo "Archiving $VERSION ($BUILD) for team $TEAM"
xcodebuild -project iosApp/iosApp.xcodeproj -scheme EucPlanet -configuration Release \
  -destination generic/platform=iOS -archivePath "$OUT/EucPlanet.xcarchive" \
  -derivedDataPath build/ios-derived-release \
  DEVELOPMENT_TEAM="$TEAM" MARKETING_VERSION="$VERSION" CURRENT_PROJECT_VERSION="$BUILD" \
  "$@" archive | grep -E "error:|ARCHIVE (SUCCEEDED|FAILED)"

echo "Uploading"
xcodebuild -exportArchive -archivePath "$OUT/EucPlanet.xcarchive" \
  -exportOptionsPlist "$OUT/ExportOptions.plist" -exportPath "$OUT/export" \
  "$@" | grep -E "error:|Upload|EXPORT (SUCCEEDED|FAILED)"
echo "Uploaded $VERSION ($BUILD). It shows in TestFlight once Apple has processed it (about 15 minutes)."
