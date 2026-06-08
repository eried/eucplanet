# Installing the watch app on a Galaxy Watch (or any Wear OS 4+)

The Wear OS companion is shipped two ways:

- **Bundled inside the phone AAB on Google Play.** Installing the phone
  app from Play automatically delivers the watch app to any Wear OS
  device already paired to the phone. Nothing else to do; nothing to
  sideload. This is the recommended path.
- **As a standalone `wearos-<version>.apk`** attached to every GitHub
  release. Use this when you have a Play-distributed phone build that
  doesn't auto-deliver, or you want to test a pre-release watch build
  ahead of Play rollout.

The rest of this guide is the **sideload** path.

## The error that brings most people here

> `/data/local/tmp/wearos-0.9.17.apk[1]: syntax error: unexpected
> 'M-ILI,IK-*53JK'`

That is the Android shell trying to *execute* the APK as a shell script.
An APK is a ZIP archive; `sh` reads the first byte (`PK\x03\x04` — the
ZIP magic), can't tokenize it, and dies. **The APK is fine.** Whatever
tool you used picked an "Open" / "Run" action instead of an "Install"
action; pick install and the same file goes on cleanly.

## Path A — sideload from a PC

Easiest if you already have `adb` (Android Studio's platform-tools).

1. **On the watch**: Settings → About watch → Software (or "Software
   information") → tap **Software version** 7 times until "Developer
   mode enabled" appears. Then Settings → Developer options → enable
   **ADB debugging** and **Debug over Wi-Fi**. The watch will display
   an IP + port and a pairing code.
2. **Make sure the watch and the PC are on the same Wi-Fi network**
   (Bluetooth alone is not enough for wireless ADB).
3. **On the PC**:
   ```
   adb pair <watch-ip>:<pair-port>    # paste the pairing code from the watch
   adb connect <watch-ip>:<port>
   adb devices                         # should list the watch
   adb -s <watch-device-id> install wearos-<version>.apk
   ```
4. The app appears in the watch launcher. First launch needs the
   permissions the watch will prompt for (sensors, microphone if you
   use voice).

## Path B — sideload from a phone (no PC)

Use a phone-side ADB client. The community usually picks
[Bugjaeger](https://play.google.com/store/apps/details?id=com.example.bugjaeger).
Same flow, no PC required:

1. Enable ADB debugging and Debug over Wi-Fi on the watch as in
   Path A step 1. Note the IP + port + pairing code.
2. In Bugjaeger, add a new device → **Pair with code** → enter the
   watch IP / port / code from the watch. Wait for the device to
   appear in Bugjaeger's device list.
3. Make sure the **target device** in Bugjaeger is the watch (not the
   phone you're running Bugjaeger on).
4. Tap **Install APK** and pick the `wearos-<version>.apk` file. Do
   NOT use "Open" / "Run" — that's the path that produces the
   `syntax error` above.

## Path C — Play Store auto-delivery

If you install the phone app from Google Play and a Wear OS device is
already paired to the phone, Play auto-delivers the embedded watch
APK within a few minutes. Open the Play Store on the watch to nudge
it if it doesn't arrive on its own. No sideload needed.

## Troubleshooting

| Symptom | Likely cause / fix |
|---|---|
| `syntax error: unexpected …` | APK was *executed* instead of *installed*. Use the install action. |
| `INSTALL_FAILED_INSUFFICIENT_STORAGE` | Free space on the watch. ~80 MB needed. |
| `INSTALL_FAILED_VERSION_DOWNGRADE` | Already a newer build installed. `pm uninstall com.eried.eucplanet` first, then install. |
| `INSTALL_PARSE_FAILED_NO_CERTIFICATES` | Re-download the APK; the previous transfer was truncated. |
| Pair succeeds, connect times out | Watch and PC/phone aren't on the same Wi-Fi network, or the watch's port rotated. Re-read the port from the watch and try again. |
| App installs but won't open | Watch is on Wear OS 3 or older. EUC Planet's watch app needs Wear OS 4+. |

## Where to find the APK

Each [GitHub release](https://github.com/eried/eucplanet/releases)
attaches `wearos-<version>.apk` alongside the phone APK. The rolling
branch pre-releases follow the same pattern
(`wearos-<branch>-<sha>.apk`).
