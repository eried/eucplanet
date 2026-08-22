# Legal Mode Lockdown

Status: approved, ready for planning
Branch: `feature/legal-mode-lockdown` (off `next-version`)
Date: 2026-08-22

## Problem

The app already has a Legal Mode: `WheelRepository.safetySpeedActive`, backed by
the "Legal mode speed" tiltback and alarm values in the Wheel parameters tab. Any
rider can switch it off in one tap, from the dashboard tile, a Flic button, the
volume keys, the watch or the HUD.

For a wheel handed over by a shop, a distributor, or shown to a reviewer, that is
not enough. The limits have to hold, and the app around them has to be simple
enough that nothing else can be reached.

Legal Mode Lockdown adds a manufacturer code. Once armed, the app runs a fixed,
stripped screen and the legal limits cannot be lifted from any surface until the
code is entered.

## Non-goals

- Changing how today's Legal Mode works when the lockdown is off. That path must
  behave exactly as it does now.
- Any persistent change to the rider's configuration. Lockdown is a runtime
  overlay. Arming and disarming must leave every `AppSettings` field untouched.
- Protecting against an attacker with a debugger or root. The code stops a rider
  or a customer, not a reverse engineer.

## Decisions

| Area | Behaviour |
|---|---|
| Code | 4 to 8 digit numeric PIN, stored as MD5, no backdoor |
| Wrong code | "Incorrect code", flat 3 second cooldown, unlimited attempts |
| Persistence | Re-arms on launch, survives app kill and reboot |
| Wheel limits | Forced on at arm time, arms with no wheel, re-pushed on every connect |
| Live trip at arm time | Finalised and saved, then the recorder stops |
| Metric pills | 2 columns x 3 rows, completely inert, no ripple, no detail screen |
| Buttons | 2 columns x 2 rows: HORN, LIGHT, SPEED LIMIT, VEHICLE |
| Alarms | The rider's own alarm rules still fire |
| Voice | Periodic reports, triggers and accel splits are silent |
| Wheel lock | Blocked entirely, manual and automatic |
| Auto lights | Keeps running, and the LIGHT button no longer suspends it |
| Auto volume | Untouched |
| Media control | Stopped |
| Proximity auto-lock | Blocked |
| Rotation | Forced portrait, without touching any `rotate*` setting |
| Recovery | Uninstall and reinstall, warned before arming |

## Architecture

### Storage: a dedicated store, not AppSettings

Lockdown state lives in its own DataStore file, `legal_lockdown.preferences_pb`,
owned by `LegalLockdownStore`. Two keys:

- `armed: Boolean`
- `codeHash: String` (MD5 of the PIN, blank when never configured)

This deliberately steps outside `AppSettings`, and the reason is the guarantee
the feature has to make. If the state were an `AppSettings` field it would flow
through `SettingsJson.toJson` / `fromJson`, and `SyncManager`'s restore path uses
the current settings as the floor and overwrites from the payload. Restoring any
older backup would then set `armed = false`, which is a one-tap bypass. Keeping
the state in a separate store makes that impossible by construction rather than
by remembering to strip a field in two places.

It also delivers the "your settings are unchanged" promise provably: the arm and
disarm paths never call `SettingsRepository.update()` at all.

`AndroidManifest` gains `android:dataExtractionRules` and
`android:fullBackupContent` that exclude only this one file, so the lock does not
ride Android auto-backup to a new phone and a reinstall is genuinely clean.

### The controller

`LegalLockdownController`, a Hilt `@Singleton`, is the only writer:

```kotlin
val armed: StateFlow<Boolean>
val configured: StateFlow<Boolean>      // a code has been set

suspend fun arm(pin: String)
suspend fun tryDisarm(pin: String): Boolean
```

`arm()` hashes the PIN, persists, and runs the side effects (finalise the trip,
stop the recorder, force legal mode on). `tryDisarm()` compares MD5 and, on a
match, clears `armed` and lets every gate fall open again. Everything else in the
app only reads `armed`.

MD5 is chosen over a stronger hash on purpose: this is a 4 to 8 digit PIN
evaluated on a phone that may be doing telemetry at 250 ms ticks, and the threat
model is a rider, not an offline cracker. The cooldown, not the hash, is what
makes guessing impractical.

### The gates

**Gate 1, `FlicManager.executeAction()`.** This function is already the single
dispatch table for Flic buttons, the volume keys, the Wear watch
(`PhoneWearListenerService`), the HUD (`HudCommandSink`) and the dashboard action
tiles. Gating one function therefore covers every remote surface. While armed:

- `HORN` and `LIGHT_TOGGLE` execute.
- `SAFETY_TOGGLE`, `SAFETY_ON`, `SAFETY_OFF` do not fire. They post to
  `LockdownPromptBus` instead, which raises the unlock dialog on the locked
  screen. This is the same pattern as the existing `DashboardDialogBus`.
- Everything else, including `LOCK_TOGGLE`, `RECORD_TOGGLE`, `VOICE_ANNOUNCE`,
  custom BLE keys and every `OPEN_*`, is a logged no-op.

**Gate 2, `WheelRepository`.** Defence in depth for callers that do not go
through the dispatch table. While armed, `disableSafetySpeed()` and the off-half
of `toggleSafetySpeed()` refuse. `enableSafetySpeed()` stays allowed.
`SettingsViewModel.updateSafetyTiltback` and `updateSafetyAlarm` also refuse,
otherwise raising the legal tiltback to 60 km/h would be the bypass.

**Gate 3, on connect.** `WheelRepository` already syncs `_safetySpeedActive` from
the wheel when a connection is established. While armed, if the wheel comes back
reporting legal mode off, the app re-applies it. This is what makes arming with
no wheel connected meaningful.

### The runtime overlay

Consumers read `LegalLockdownController.armed` and suppress their own behaviour.
Nothing writes settings.

| Subsystem | While armed |
|---|---|
| `TripRepository` | `startRecording()` is a no-op, `AutoRecordPolicy` suppressed |
| Navigation engine | stopped, route start suppressed |
| `PhoneHudWindow` overlay | hidden, no updates |
| `EucWidget`, `ActionWidgets` | updates suppressed |
| `DiagnosticsLogger`, service overlay | recording stopped, volume-key entry disabled |
| `WheelService.buildNotificationActions` | returns no actions |
| `AlarmEngine` | unchanged, the rider's own rules |
| `VoiceService` | periodic reports, triggers and accel splits suppressed at playback |
| `AutomationManager.evaluate` | skips `evaluateMediaControl` and `evaluateProximityLock` |
| `notifyManualLightChange()` | skipped, so auto-lights is never suspended |
| Wear and HUD streams | keep running, gated by gate 1 |
| Cloud sync | left alone, no new trips are produced |

The auto-lights row is the subtle one. Today both `DashboardViewModel.onLightToggle()`
and `FlicManager`'s `LIGHT_TOGGLE` call `automationManager.notifyManualLightChange()`,
which suspends auto-lights for the rest of the session and surfaces "Suspended due
to user light toggle for this session" in settings. A temporary mode must not
leave that behind, so in lockdown the LIGHT button toggles the light without the
suspension and without telling the rider anything was overridden.

### The screen

A new `LegalLockdownScreen.kt`, reusing the existing `internal fun SpeedGauge`.
Not conditionals inside `DashboardScreen.kt`: that file is 4197 lines in a single
composable, and the normal dashboard has to stay byte-identical.

`NavGraph`'s start destination becomes `legal_lockdown` while armed and no other
route is reachable. Back press is consumed. `MainActivity` short-circuits its
existing `requestedOrientation` block to `SCREEN_ORIENTATION_PORTRAIT`, ignoring
`rotateSettings`, `rotateOtherScreens` and `ignoreSystemRotateLock` without
changing any of them.

```
+------------------------------+
|                    (BT icon) |   only chrome. no gear, no camera,
|          +--------+          |   GPS, PND or navigator icons, no
|          |   24   |          |   warnings panel, no Flic indicator,
|          |  km/h  |          |   no charging button
|          +--------+          |
|                              |
|  +----------+ +----------+   |
|  | BATTERY  | |   TEMP   |   |   fixed, hard-coded, inert.
|  +----------+ +----------+   |   no ripple, no detail screen,
|  | VOLTAGE  | | CURRENT  |   |   no long-press
|  +----------+ +----------+   |
|  |   PWM    | |   TRIP   |   |
|  +----------+ +----------+   |
|                              |
|  +----------+ +----------+   |
|  |   HORN   | |  LIGHT   |   |
|  +----------+ +----------+   |
|  |SPEED LIM.| | VEHICLE  |   |
|  +----------+ +----------+   |
|                              |
|       EUC Planet 0.17.0      |   static, not tappable
+------------------------------+
```

Those six metrics are already the catalog defaults
(`BATTERY, TEMPERATURE, VOLTAGE, CURRENT, LOAD, TRIP`, where `LOAD` is labelled
PWM) and `dashboardMetricsColumns` already defaults to 2, so the grid reads as
the stock layout while being hard-coded rather than read from settings. The
action grid is hard-coded to 2 columns, overriding the `dashboardActionsColumns`
default of 3, so the four buttons line up under the six pills.

### The two dialogs

**SPEED LIMIT** shows the live values, then the way out:

```
Speed limit          25 km/h
Alarm                20 km/h

Unlock with manufacturer code
        [ * * * * ]
   [ Cancel ]   [ Unlock ]
```

A correct code disarms and lands on the normal dashboard. A wrong code shows
"Incorrect code" and disables Unlock for 3 seconds.

**VEHICLE**, when connected: Bluetooth name, brand, model, max speed limit
(the legal tiltback), alarm, odometer. When not connected: "No wheel connected.
Use the Bluetooth icon to connect."

### Arming

Settings, Wheel parameters tab, directly under the existing "Legal mode speed"
section. A switch labelled "Legal Mode Lockdown". Turning it on opens a dialog
with `dismissOnClickOutside = false` (rule 11) that lists every limitation, warns
about recovery, and takes the PIN twice. On confirm: the in-progress trip is
finalised and saved, the recorder stops, legal mode is forced on, `armed`
persists, and the app navigates to the locked screen.

Full warning text (all user-facing strings go in `strings.xml`, rule 12):

> **Legal Mode Lockdown**
>
> This switches the app to a simple, locked screen. While it is on:
>
> - Legal mode speed limits are forced on and cannot be turned off, from the
>   screen, a Flic button, the volume keys, the watch or the HUD.
> - The legal tiltback and alarm speeds cannot be changed.
> - Trip recording stops, and auto record will not start new trips.
> - Navigation stops and routes cannot be started.
> - The floating overlay is hidden.
> - Home screen widgets stop updating.
> - Service mode and diagnostics recording stop.
> - The dashboard is replaced by a fixed screen: speedometer, six metrics, four
>   buttons.
> - The screen will not rotate.
> - Settings cannot be opened.
> - Trip history, the battery monitor, the studio and metric details cannot be
>   opened.
> - Tapping a metric does nothing.
> - Voice announcements, periodic reports and accel splits are silent. Your
>   alarms still work.
> - The wheel cannot be locked or unlocked, and proximity auto-lock will not run.
> - Auto lights and auto volume keep working. Media control stops.
> - The notification loses its buttons.
> - The version number will not open About.
> - This mode is not included in settings backups.
>
> Your settings are not changed. Everything comes back exactly as it is now when
> you unlock.
>
> The only way out is the manufacturer code, from the SPEED LIMIT button. If you
> lose that code, the only way to recover the app is to uninstall and reinstall
> it.

## Testing

Unit tests:

- PIN hashing and verification, including blank and out-of-range lengths.
- Every `SAFETY_*` path refused, asserted from all five entry points that reach
  `executeAction`.
- `LOCK_TOGGLE` refused, and `evaluateProximityLock` not invoked while armed.
- `notifyManualLightChange()` not invoked by a lockdown LIGHT press.
- The guarantee, as a test: arm then disarm leaves every `AppSettings` field
  byte-identical to the snapshot taken before arming.
- `SettingsJson` round-trip is unaffected, since lockdown state is not in it.

On a fresh `legalmode` AVD:

- Arm, kill the app, relaunch. Still locked.
- Wrong code, and the 3 second cooldown.
- Rotate the device while armed. Stays portrait.
- Correct code, then confirm dashboard layout, action order, rotation behaviour
  and auto-lights suspension state are exactly what they were before arming.
- Arm with no wheel, then connect. Legal limits applied on connect.
- Regression: with the switch off, today's Legal Mode behaves as it does now.

## Risks

- **Backup and restore.** Handled by the separate store plus the manifest backup
  exclusions, but every future settings-transport feature must be checked against
  it. Worth a comment at the store declaring why it is not an `AppSettings` field.
- **A rider who forgets the code.** Accepted, and warned about before arming. The
  documented recovery is uninstall and reinstall.
- **Surface drift.** A new remote surface added later that does not funnel through
  `FlicManager.executeAction()` would be ungated. Gate 2 in `WheelRepository` is
  the backstop, which is why it exists despite being redundant today.
