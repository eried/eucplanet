# Installing the watch app (Wear OS 4+)

Works the same on Pixel Watch, Galaxy Watch, TicWatch, OnePlus Watch
and any other Wear OS 4+ device. The only step that differs between
brands is how you reach Developer options (covered below).

The watch app ships two ways:

- **Bundled in the phone AAB on Google Play.** Installing the phone
  app from Play auto-delivers the watch app to any paired Wear OS
  device. Nothing to sideload. This is the recommended path.
- **Standalone `wearos-<version>.apk`** on every GitHub release. Use
  this for pre-release builds, or when Play auto-delivery is not an
  option.

The rest is the sideload path.

## "syntax error: unexpected 'M-ILI,IK-*53JK'"

The shell is trying to run the APK as a script. Pick the install
action in your ADB tool instead of open/run and the same file goes
on cleanly.

## 1. Enable ADB on the watch

Reach Developer options. The path differs by brand:

- **Samsung Galaxy Watch:** Settings, About watch, Software, tap
  Software version 7 times.
- **Pixel Watch / OnePlus / TicWatch / generic Wear OS:** Settings,
  System, About, tap Build number 7 times.

Then in Settings, Developer options, enable:

- ADB debugging
- Debug over Wi-Fi

The watch shows an IP, a port and a pairing code. Keep them visible.

The watch and the device running ADB must be on the same Wi-Fi
network. Bluetooth alone is not enough.

## 2a. Install from a PC

```
adb pair <watch-ip>:<pair-port>     # paste the code from the watch
adb connect <watch-ip>:<port>
adb -s <watch-id> install wearos-<version>.apk
```

## 2b. Install from a phone (no PC)

Use a phone-side ADB client like
[Bugjaeger](https://play.google.com/store/apps/details?id=com.example.bugjaeger).

1. Add device, Pair with code, enter the watch IP / port / code.
2. Confirm the target device is the watch (not the phone you are
  running Bugjaeger on).
3. Tap Install APK and pick `wearos-<version>.apk`. Do not pick
  Open or Run.

## Troubleshooting

| Symptom | Fix |
|---|---|
| `syntax error: unexpected …` | Install action, not Open/Run. |
| `INSTALL_FAILED_INSUFFICIENT_STORAGE` | Free ~80 MB on the watch. |
| `INSTALL_FAILED_VERSION_DOWNGRADE` | `pm uninstall com.eried.eucplanet` first. |
| `INSTALL_PARSE_FAILED_NO_CERTIFICATES` | Re-download; the file was truncated. |
| Pair succeeds, connect times out | Same Wi-Fi, fresh port from the watch. |
| Installs but won't open | Needs Wear OS 4+. |

## APK location

Each [GitHub release](https://github.com/eried/eucplanet/releases)
attaches `wearos-<version>.apk` alongside the phone APK. Branch
pre-releases follow the same pattern (`wearos-<branch>-<sha>.apk`).
