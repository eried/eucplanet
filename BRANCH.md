# Legal Mode Lockdown

A manufacturer code that pins the app to a simple, locked screen and stops every
surface from lifting the wheel's legal speed limits.

## The problem

Legal Mode already exists: the legal tiltback and alarm in Wheel parameters,
toggled from the dashboard tile, a Flic button, the volume keys, the watch or the
HUD. Any rider can switch it off in one tap.

For a wheel handed over by a shop or a distributor, or shown to a reviewer, that
is not enough. The limits have to hold, and the app around them has to be simple
enough that nothing else can be reached.

## What changed

- **A new locked screen.** While armed the whole nav graph is replaced by
  `LegalLockdownScreen`: the speedometer capped at the legal tiltback, six fixed
  metrics in 2 x 3 (battery, temperature, voltage, amps, PWM, trip), and four big
  buttons in 2 x 2 under them (horn, light, speed limit, vehicle). Only the
  Bluetooth icon remains as chrome. The pills are inert, back does nothing, and
  the screen is pinned to portrait.
- **One gate, first in line.** `FlicManager.executeAction` is already the single
  dispatch table for Flic, the volume keys, the watch and the HUD, so an allowlist
  there covers every remote surface. It runs before the custom-BLE branch and
  before the catalog precondition, so raw frames cannot slip through and a
  legal-mode hotkey press with no wheel connected still opens the unlock dialog
  rather than silently doing nothing. `WheelRepository` refuses the off direction
  as a backstop.
- **The limits follow the wheel.** Arming with no wheel present is allowed, and
  the legal limits are pushed on every connect and after every settings readback.
- **The rider's settings are never touched.** The lock lives in its own DataStore
  file, not in `AppSettings`, so the arm and disarm paths never call
  `SettingsRepository.update()`. Unlocking gives back the exact dashboard layout,
  action order, rotation behaviour and auto-lights state the rider had.

## Why the state is not an AppSettings field

Everything in `AppSettings` flows through `SettingsJson` and `SyncManager`, and
the restore path overwrites the device's current values from the payload. A
lockdown flag living there would be cleared by restoring any older backup, which
is a one-tap bypass of the whole feature. Its own store makes that impossible by
construction rather than by remembering to strip a field in two places. It is
also excluded from Android auto-backup and device transfer, so the lock does not
ride a cloud backup onto a new phone and a reinstall is genuinely clean.

## What stops while armed

Trip recording and auto-record, navigation, the floating overlay, home screen
widgets, service mode and diagnostics recording, the notification's action
buttons, media control, and proximity auto-lock. The wheel cannot be locked or
unlocked at all.

Voice splits cleanly: `AlarmEngine` speaks through `VoiceService.speak`, so the
rider's own alarms still fire, while `announceEvent`, `announceTrigger` and
`announceStatus` go quiet. Those are the ones that would say "legal mode" or
"recording".

Auto lights and auto volume keep running. The light button skips
`notifyManualLightChange()` while armed, so a temporary mode never leaves
auto-lights suspended for the rest of the session.

## Recovery

There is no backdoor. The arming dialog lists all eighteen limitations and states
plainly that losing the code means uninstalling and reinstalling.

## Verified

`./gradlew :app:testDebugUnitTest`: 741 tests, 0 failures. `:app:assembleDebug`
BUILD SUCCESSFUL.

On a clean `legalmode` AVD (Pixel 7, API 36.1), in English and German:

- The row sits under Legal mode speed. Turning it on lists every limitation and
  takes the code twice. A mismatch and a 3-digit code are both refused.
- Arming lands on the locked screen. Six pills 2 x 3, four buttons 2 x 2, gauge
  capped at the legal tiltback, static version line.
- Metric pills do nothing, with no ripple. Back does nothing.
- Forcing `user_rotation` to landscape leaves the display at 1080x2400.
- Speed limit shows the real limit and alarm. A wrong code shows "Incorrect code"
  and disables Unlock for 3 seconds. Vehicle with no wheel shows the connect line.
- Force-stop and relaunch comes back locked.
- Volume-down bound to Legal OFF, with no wheel connected: refused, and the
  unlock dialog opens by itself.
- The correct code returns the dashboard identical to before arming: same metric
  order, same six action tiles in three columns, same units and gauge.

Not exercised on device, since no wheel can be connected to an emulator: the
recorder, navigation, overlay, widget and notification suppression, and the
push-limits-on-connect path. Those are single guarded early-returns on the armed
flag.
