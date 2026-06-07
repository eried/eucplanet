# Pebble Time 2 telemetry watchapp - design

**Status:** approved design, pre-implementation
**Date:** 2026-06-07
**Branch:** pebble-support

## Goal

Add the new Pebble Time 2 - and other PebbleOS watches - as a telemetry companion, glanceable on the wrist while riding, mirroring the existing WearOS and Garmin companions. First cut is telemetry-only.

## Scope (this branch)

- A Pebble **watchapp** rendering live EUC telemetry (speed, battery, PWM, temp, units), kept foreground while riding.
- A phone-side **PebbleBridge** that pushes telemetry to the watchapp over Bluetooth via PebbleKitAndroid2.
- A Settings section + a docs chip, mirroring the other companions.

## Non-goals (deferred follow-up, NOT this branch)

- Button actions (horn / light / configurable) and a `PEBBLE` `ActionSurface`. The phone listener service exists now only for watchapp open/close lifecycle; inbound button handling is a later branch - same staging WearOS and Garmin went through.
- Classic (pre-PebbleOS) Pebble support. Target is the new Pebble (Time 2 + PebbleOS / Core Devices).

## Architecture (mirrors the Garmin companion)

### Watch side - `pebble-watch-app/` (new top-level dir, sibling of `garmin-watch-app/`)

- Pebble C-SDK watchapp. `package.json` declares a fixed app UUID, target platforms (`emery` = Pebble Time 2, plus other PebbleOS platforms that build cleanly), and `companionApp: com.eried.eucplanet`.
- AppMessage inbox handler decodes the telemetry dict and renders a glanceable dial: large speed (primary), battery, a PWM bar, temp, units. Layout mirrors Garmin's `EucPlanetView` + `SpeedGauge`.
- Prevents screen timeout while active (ride-long display).
- Likely files: `package.json`, build config, `src/c/main.c` (app + window), `src/c/dial.c` (rendering), `src/c/comm.c` (AppMessage), `src/c/keys.h` (key ids matching the phone side).

### Phone side - `PebbleBridge` (gated like Garmin)

- Gated by a `-PpebbleEnabled` build property + a `src/pebbleEnabled/kotlin` vs `src/pebbleStub/kotlin` source-set swap; the `io.rebble.pebblekit2:client` dependency (JitPack, 1.1.0) is only pulled when enabled. `pebbleStub` is a no-op bridge so slim builds compile without the dependency. Default enabled, matching Garmin.
- `PebbleBridge` (DI singleton, wired like `GarminBridge`): collects `WheelRepository.wheelData` + relevant settings, throttles to ~3-4 Hz, builds a `Map<Int, Any>` from `PebbleKeys`, and calls `DefaultPebbleSender().sendDataToPebble(PEBBLE_APP_UUID, dataMap)` (suspending).
- `PebbleKeys` object: short integer keys for speed / battery / voltage / current / pwm / temp / connected / unitSpeed / unitTemp / accent - mirroring `GarminKeys` and `WatchKeys` so a future shared protocol module can unify all three.
- `PebbleListenerService` extends `BasePebbleListenerService`; this cut uses only `onAppOpened` / `onAppClosed` to start/stop the telemetry push (don't push when the watchapp isn't open). Manifest entry with the `io.rebble.pebblekit2.RECEIVE_DATA_FROM_WATCH` intent filter.

### Settings

- A "Pebble" section in Settings, mirroring the HUD / Garmin / Wear sections: enable toggle, connection status line, "get the watchapp" hint. New `pebbleEnabled` field in `AppSettings` AND in `SettingsJson.toJson/fromJson` (parity rule). New strings added in all 14 locales.

### Docs

- A "Pebble" chip in the `docs/index.html` chip-row, grouped with the other companion chips (apply the same wrap-pairing treatment if it crowds the row).

## Telemetry rate + lifecycle

- ~3-4 Hz starting point (Pebble BLE / AppMessage bandwidth is tighter than the HUD's 5 Hz WiFi; tune on hardware). Coalesce: send only when values changed meaningfully, to spare BLE + battery.
- Push only while the watchapp is open (`onAppOpened` / `onAppClosed`) and a Pebble is connected (`DefaultPebbleInfoRetriever`).

## Testing

- **Watchapp UI + telemetry rendering - Pebble emulator, no hardware:** build with the pebble tool, `pebble install --emulator emery` (emery = Pebble Time 2), inject test AppMessages (PebbleKitJS shim or the pebble tool) to verify the dial across speed / battery / PWM ranges. On Windows: WSL + pebble-tool, or CloudPebble's in-browser emulator (no local SDK setup).
- **Phone bridge - unit test:** the `PebbleKeys` dict assembly from a `WheelData` snapshot (like the Garmin/Wear protocol tests). No Pebble needed.
- **End-to-end BLE - physical Pebble Time 2:** the final phone-app to watch link over Bluetooth needs real hardware (or an emulator-via-DevConnect setup, to be validated) - the same "final verify on hardware" reality as the HUD and Garmin.

## Risks / open questions

- Telemetry rate needs tuning on real BLE (3-4 Hz is a starting guess).
- Windows dev environment for the watchapp: the Pebble SDK is Linux/macOS-first; on Windows use WSL + pebble-tool or CloudPebble. Dev-environment setup is the first implementation task.
- Emulator vs PebbleKitAndroid2 end-to-end: unverified whether the emulator (via the phone Dev Connect) can stand in for a real watch in the PebbleKitAndroid2 path. The watchapp UI itself is fully emulator-testable regardless.
- PebbleKitAndroid2 maturity: published 1.1.0 (Apr 2026) but newer than the Garmin SDK; validate the send path early.

## Touch points (files)

- New: `pebble-watch-app/` (watchapp); `app/src/pebbleEnabled/kotlin/.../PebbleBridge.kt` + `PebbleKeys.kt` + `PebbleListenerService.kt`; `app/src/pebbleStub/kotlin/.../PebbleBridge.kt` (no-op).
- Edit: `app/build.gradle.kts` (pebbleEnabled property + source-set swap + dependency); `AndroidManifest.xml` (listener service); `AppSettings.kt` + `SettingsJson.kt` (pebbleEnabled field); `SettingsScreen.kt` + `SettingsViewModel.kt` (Pebble section); `res/values*/strings.xml` (14 locales); `docs/index.html` (chip); the Hilt module (provide `PebbleBridge`); the telemetry publish site (start the bridge like `GarminBridge`).
