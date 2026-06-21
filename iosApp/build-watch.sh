#!/usr/bin/env bash
# Assemble the EUC Planet watchOS app and run it on a booted Apple Watch
# simulator, then screenshot it. Standalone (no companion needed) — renders the
# demo dial when no phone frame has arrived. Run on the Mac from the repo root:
#   bash iosApp/build-watch.sh [out.png]
# Pick a watch with WATCH="Apple Watch SE 3 (44mm)" bash iosApp/build-watch.sh
set -euo pipefail
OUT="${1:-/tmp/euc_watch.png}"
BUNDLE_ID="com.eried.eucplanet.watch"
APP="/tmp/EucPlanetWatch.app"
WATCH_NAME="${WATCH:-Apple Watch Ultra 3 (49mm)}"

rm -rf "$APP"; mkdir -p "$APP"
xcrun -sdk watchsimulator swiftc -target arm64-apple-watchos10.0-simulator -parse-as-library \
  iosApp/watch/WatchApp.swift -o "$APP/EucPlanetWatch"
cp iosApp/watch/Info-watch.plist "$APP/Info.plist"
codesign --force --sign - "$APP" >/dev/null 2>&1 || true

DEV="$(xcrun simctl list devices available | grep -m1 "$WATCH_NAME" | grep -oE '\([0-9A-F-]{36}\)' | tr -d '()')"
if [ -z "$DEV" ]; then echo "No watch sim matching '$WATCH_NAME'"; exit 1; fi
xcrun simctl boot "$DEV" 2>/dev/null || true
xcrun simctl bootstatus "$DEV" -b || true
xcrun simctl uninstall "$DEV" "$BUNDLE_ID" 2>/dev/null || true
xcrun simctl install "$DEV" "$APP"
xcrun simctl launch "$DEV" "$BUNDLE_ID"
sleep 5
xcrun simctl io "$DEV" screenshot "$OUT"
echo "watch screenshot -> $OUT (device $DEV)"
